package de.embl.iclm;

import java.awt.image.BufferedImage;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Completed-frame playback (joint decision D2; contracts C4.2, C6.2, C6.3): a frame asked for is
 * shown complete or not at all.
 *
 * <p>Each {@link RawFrameRequest} goes {@code REQUESTED -> PREPARING -> READY -> RENDERING ->
 * PRESENTED}, or ends {@link Outcome#SUPERSEDED}, {@link Outcome#CHANGED}, {@link Outcome#REFUSED}
 * or {@link Outcome#FAILED}:
 * <ol>
 * <li>the raw cells the view will sample are worked out from the geometry ({@link RawCoverage}) and
 *     pinned by the store ({@link TiffRawVolumeStore#prepare}) - Ready means the data is resident,
 *     nothing more;</li>
 * <li>the frame is drawn whole, off screen, from those cells ({@link RawFrameRenderer});</li>
 * <li>the file is checked on disk once more, and the frame handed to {@link RawPresenter}, whose
 *     acknowledgement - given when the canvas has painted that image - is the only thing that
 *     completes a frame as presented.</li>
 * </ol>
 *
 * <p>The newest request wins: an older one still queued, preparing or drawing is dropped at its next
 * check, and one handed over but not yet painted is never shown. Whatever fails, the last presented
 * frame stays on screen, with its own time label, until a replacement is painted.
 *
 * <p><b>One budget.</b> A frame's lease pins its cells from preparation until its image is complete;
 * a shown frame is an image and needs no cells, so its lease is released then, and the cells stay as
 * ordinary cache. Each staged image - being drawn, waiting to be painted, on screen - is charged to
 * the store's budget as a {@link TiffRawVolumeStore.Reservation} until it has been replaced on
 * screen. Pinned cells, staged images, cells lent to BigDataViewer, reads and read buffers are thus
 * counted once each, in one ledger. A frame the budget cannot hold is refused - nothing of it is
 * shown, and the status says why.
 */
final class RawStrictPlayer implements AutoCloseable {

	enum Outcome {
		/** Painted on the canvas: its {@link RawPresenter.Presented} acknowledgement. */
		PRESENTED,
		/** A newer request came first; it was never on screen. */
		SUPERSEDED,
		/** Its file was found changed while it was prepared, drawn or waiting; it was not shown. */
		CHANGED,
		/** The budget cannot hold it; it was not shown. */
		REFUSED,
		/** A read failed, or the store or the player closed; it was not shown. */
		FAILED
	}

	/** How one request ended, and what each stage cost. */
	static final class Result {
		final RawFrameRequest request;
		final Outcome outcome;
		/** The acknowledgement, for {@link Outcome#PRESENTED}; otherwise null. */
		final RawPresenter.Presented presented;
		final Throwable cause;
		/** Nanoseconds: waiting for the driver, preparing, drawing, and from hand-over to paint. */
		final long queueNanos, prepareNanos, renderNanos, presentNanos;
		/** The lease: cells and bytes pinned for the frame. */
		final int leaseCells;
		final long leaseBytes;
		/** Cells the store read while the frame was drawn: what the coverage missed. Zero for a covered frame. */
		final long readsWhileDrawing;

		private Result(Stages s, Outcome outcome, RawPresenter.Presented presented, Throwable cause, long presentNanos) {
			this.request = s.request;
			this.outcome = outcome;
			this.presented = presented;
			this.cause = cause;
			this.queueNanos = s.queue;
			this.prepareNanos = s.prepare;
			this.renderNanos = s.render;
			this.presentNanos = presentNanos;
			this.leaseCells = s.cells;
			this.leaseBytes = s.bytes;
			this.readsWhileDrawing = s.readsWhileDrawing;
		}

		/** From the request to the paint, for a presented frame. */
		long latencyNanos() {
			return queueNanos + prepareNanos + renderNanos + presentNanos;
		}

		@Override public String toString() {
			return String.format(Locale.US, "%s: %s (queue %.1f ms, prepare %.1f ms over %d cells, render %.1f ms, present %.1f ms)%s",
					request, outcome, queueNanos / 1e6, prepareNanos / 1e6, leaseCells, renderNanos / 1e6, presentNanos / 1e6,
					cause == null ? "" : ": " + cause.getMessage());
		}
	}

	/** What a request has cost so far; filled in by the driver. */
	private static final class Stages {
		final RawFrameRequest request;
		final long requested;
		long queue, prepare, render, readsWhileDrawing, bytes;
		int cells;

		Stages(RawFrameRequest request) {
			this.request = request;
			this.requested = System.nanoTime();
		}
	}

	/** Test seam: called on the driver after a frame is drawn, before its file is checked again. */
	static volatile Consumer<RawFrameRequest> beforeCheckForTest;

	private final TiffRawVolumeStore store;
	private final RawFrameRenderer renderer;
	private final RawPresenter presenter;
	private final ExecutorService driver = Executors.newSingleThreadExecutor(Shutdown.daemonThreads("OPM-raw-play"));
	private final AtomicLong latest = new AtomicLong(Long.MIN_VALUE);
	private volatile boolean closed;
	/** The staged image on screen, held until its replacement has been painted. */
	private TiffRawVolumeStore.Reservation shownImage;
	private final AtomicLong[] counts = new AtomicLong[Outcome.values().length];

	RawStrictPlayer(TiffRawVolumeStore store, RawFrameRenderer renderer, RawPresenter presenter) {
		this.store = store;
		this.renderer = renderer;
		this.presenter = presenter;
		for (int i = 0; i < counts.length; i++) counts[i] = new AtomicLong();
	}

	/**
	 * Ask for a frame. Revisions must grow: a request older than one already made is superseded at
	 * once. The future completes when the frame has been painted, or with the outcome that kept it
	 * off the screen; it never fails.
	 */
	CompletableFuture<Result> request(final RawFrameRequest r) {
		final Stages s = new Stages(r);
		final CompletableFuture<Result> result = new CompletableFuture<Result>();
		latest.accumulateAndGet(r.revision, Math::max);
		presenter.pending(String.format(Locale.US, "t = %d   %s", r.timeIndex + 1, r.entry.time));
		try {
			driver.execute(() -> play(s, result));
		} catch (RejectedExecutionException closedMeanwhile) {
			finish(result, new Result(s, Outcome.FAILED, null, new IllegalStateException("The player is closed"), 0));
		}
		return result;
	}

	private boolean superseded(RawFrameRequest r) {
		return closed || latest.get() != r.revision;
	}

	/** One request, on the driver. */
	private void play(Stages s, CompletableFuture<Result> result) {
		RawFrameRequest r = s.request;
		long start = System.nanoTime();
		s.queue = start - s.requested;
		if (superseded(r)) {
			finish(result, new Result(s, Outcome.SUPERSEDED, null, null, 0));
			return;
		}
		TiffRawVolumeStore.Ready ready = null;
		TiffRawVolumeStore.Reservation image = null;
		try {
			BitSet cells = RawCoverage.cells(r.layout.dimensions(), r.layout.rowsPerStrip, r.coverageParts(), r.width, r.height,
					r.interpolation);
			List<TiffRawVolumeStore.Want> wants = Collections.singletonList(new TiffRawVolumeStore.Want(r.key, r.layout, cells));
			ready = await(store.prepare(r.revision, wants, () -> superseded(r)));
			s.cells = ready.cells();
			s.bytes = ready.bytes();
			long prepared = System.nanoTime();
			s.prepare = prepared - start;
			if (superseded(r)) throw new CancellationException(r + " superseded");

			image = store.reserve(4L * r.width * r.height);
			long readsBefore = store.stats().loads;
			BufferedImage frame = renderer.render(r, store::blocking, () -> superseded(r));
			s.readsWhileDrawing = store.stats().loads - readsBefore;
			Consumer<RawFrameRequest> hook = beforeCheckForTest;
			if (hook != null) hook.accept(r);
			boolean valid = ready.isCurrentAndValid();
			ready.close();		// the image is complete: the cells are cache again
			ready = null;
			s.render = System.nanoTime() - prepared;
			if (!valid) throw new TiffRawVolumeStore.StaleFileException(r.entry.file.getName() + " changed while its frame was drawn");
			if (superseded(r)) throw new CancellationException(r + " superseded");

			final long handedOver = System.nanoTime();
			final TiffRawVolumeStore.Reservation staged = image;
			image = null;		// owned by the presentation from here
			presenter.present(frame, r).whenComplete((p, t) -> {
				long presentNanos = System.nanoTime() - handedOver;
				if (p != null) {
					TiffRawVolumeStore.Reservation replaced;
					synchronized (this) {
						replaced = shownImage;
						shownImage = closed ? null : staged;
					}
					if (closed) staged.close();		// painted as the player closed: nothing keeps it
					if (replaced != null) replaced.close();
					finish(result, new Result(s, Outcome.PRESENTED, p, null, presentNanos));
				} else {
					staged.close();
					finish(result, new Result(s, Outcome.SUPERSEDED, null, t, presentNanos));
				}
			});
		} catch (Throwable t) {
			if (ready != null) ready.close();
			if (image != null) image.close();
			Throwable cause = unwrap(t);
			if (s.prepare == 0) s.prepare = System.nanoTime() - start;
			finish(result, new Result(s, outcomeOf(cause), null, cause, 0));
		}
	}

	private static <T> T await(CompletableFuture<T> f) throws InterruptedException, ExecutionException {
		return f.get();
	}

	private static Throwable unwrap(Throwable t) {
		while ((t instanceof ExecutionException || t instanceof CompletionException) && t.getCause() != null) t = t.getCause();
		return t;
	}

	private Outcome outcomeOf(Throwable cause) {
		if (cause instanceof CancellationException) return Outcome.SUPERSEDED;
		if (cause instanceof TiffRawVolumeStore.StaleFileException) return Outcome.CHANGED;
		if (cause instanceof TiffRawVolumeStore.BudgetException) return Outcome.REFUSED;
		return Outcome.FAILED;
	}

	private void finish(CompletableFuture<Result> result, Result r) {
		counts[r.outcome.ordinal()].incrementAndGet();
		if (r.outcome != Outcome.PRESENTED && r.outcome != Outcome.SUPERSEDED && latest.get() == r.request.revision)
			presenter.pending(String.format(Locale.US, "t = %d   %s   not shown: %s", r.request.timeIndex + 1, r.request.entry.time,
					describe(r)));
		else if (latest.get() == r.request.revision) presenter.pending(null);
		result.complete(r);
	}

	private static String describe(Result r) {
		switch (r.outcome) {
		case CHANGED: return "the file changed";
		case REFUSED: return "the cache budget cannot hold the frame";
		default: return r.cause == null ? r.outcome.toString() : r.cause.getMessage();
		}
	}

	/** A file was found changed: a frame of it is not shown, and one on screen is taken off. */
	void withdraw(RawAcquisition.FileKey key) {
		presenter.withdraw(key);
	}

	/** How many requests ended each way. */
	long count(Outcome outcome) {
		return counts[outcome.ordinal()].get();
	}

	String summary() {
		return String.format(Locale.US, "%d presented, %d superseded, %d changed, %d refused, %d failed", count(Outcome.PRESENTED),
				count(Outcome.SUPERSEDED), count(Outcome.CHANGED), count(Outcome.REFUSED), count(Outcome.FAILED));
	}

	/**
	 * Stop: nothing more is prepared or shown, the frame on screen is taken off and its image
	 * released. Never waits; a request still running ends at its next check.
	 */
	@Override
	public void close() {
		if (closed) return;
		closed = true;
		driver.shutdown();
		presenter.stop();
		TiffRawVolumeStore.Reservation replaced;
		synchronized (this) {
			replaced = shownImage;
			shownImage = null;
		}
		if (replaced != null) replaced.close();
	}
}
