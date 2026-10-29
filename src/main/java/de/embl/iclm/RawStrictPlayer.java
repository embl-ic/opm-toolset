package de.embl.iclm;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
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
 * <li>the request is announced to the presenter as it is accepted, so that no older frame can be
 *     presented from then on;</li>
 * <li>the raw cells the view will sample are worked out from the geometry ({@link RawCoverage}) and
 *     pinned by the store ({@link TiffRawVolumeStore#prepare}) - Ready means the data is resident,
 *     nothing more;</li>
 * <li>the frame is drawn whole, off screen, through the lease's own image, which serves the pinned
 *     cells and refuses any other ({@link RawFrameRenderer});</li>
 * <li>the file is checked on disk once more, and the frame handed to {@link RawPresenter}, whose
 *     acknowledgement - given when a full paint of the canvas has drawn it - is the only thing that
 *     completes a frame as presented.</li>
 * </ol>
 *
 * <p>The newest request wins: an older one still queued, preparing or drawing stops at its next
 * check, and one handed over but not yet painted is dropped by the presenter. Whatever fails, the
 * last presented frame stays on screen, with its own time label, until a replacement is painted.
 *
 * <p><b>One budget, owned once.</b> Pinned cells, staged images, cells lent to BigDataViewer, reads
 * and read buffers are counted in one ledger. A frame's lease pins its cells from preparation until
 * every band that draws it has finished; then the image needs no cells, and the lease is released.
 * The image and the drawing scratch are charged ({@link TiffRawVolumeStore.Reservation}) before
 * drawing starts; the scratch until drawing has finished, the image by the {@link RawPresenter.Frame}
 * that holds it - the player's until the hand-over, the presenter's from then on, until the frame
 * has been replaced on screen. A frame the budget cannot hold is refused, and nothing of it is shown.
 *
 * <p><b>Prefetch, conservative</b> (C3.5): {@link #prefetch} prepares the next frame's cells - one
 * frame ahead, on the same driver, after whatever it is doing - and holds them under a lease of
 * their own, without superseding anything. The frame asked for next uses that lease when it is of
 * the same file version and covers every cell the frame needs; otherwise the lease is released. A
 * prefetch the budget cannot hold beside what is pinned and staged is dropped, never refused: it
 * only uses what is left over.
 *
 * <p>The player owns its renderer: {@link #close()} stops both, and {@link #awaitStopped} says
 * whether everything they started has finished.
 */
final class RawStrictPlayer implements AutoCloseable {

	enum Outcome {
		/** Painted on the canvas: its {@link RawPresenter.Presented} acknowledgement. */
		PRESENTED,
		/** A newer request came first, or the canvas changed size; it was never on screen. */
		SUPERSEDED,
		/** Its file was found changed while it was prepared, drawn or waiting; it was not shown. */
		CHANGED,
		/** The budget cannot hold it; it was not shown. */
		REFUSED,
		/** A read failed, a cell outside its plan was asked for, or the store or the player closed; it was not shown. */
		FAILED
	}

	/** How one request ended, and what each stage cost. Holds no pixels. */
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
		/** Values the frame asked of cells outside its lease: what the coverage missed. A frame with any fails. */
		final long coverageMisses;
		/** Cells the store read, for anyone, while the frame was drawn: a diagnostic, not the frame's own. */
		final long storeLoadsWhileDrawing;
		/** The frame's cells came from a prefetch: nothing was prepared when it was asked for. */
		final boolean prefetched;

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
			this.coverageMisses = s.misses;
			this.storeLoadsWhileDrawing = s.storeLoads;
			this.prefetched = s.prefetched;
		}

		/** A result carrying an outcome and nothing else, for tests of what reads only that. */
		static Result ofOutcomeForTest(Outcome outcome, Throwable cause) {
			return new Result(new Stages(null), outcome, null, cause, 0);
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
		long queue, prepare, render, misses, storeLoads, bytes;
		boolean prefetched;
		int cells;

		Stages(RawFrameRequest request) {
			this.request = request;
			this.requested = System.nanoTime();
		}
	}

	/** Test seam: called on the driver after a frame is drawn, before its file is checked again. */
	static volatile Consumer<RawFrameRequest> beforeCheckForTest;
	/** Test seam: called on the driver after the file was checked, just before the frame is handed over. */
	static volatile Consumer<RawFrameRequest> beforeHandOverForTest;
	/** Test seam: may change the cells a request's lease pins, to leave one out. */
	static volatile Consumer<BitSet> coverageForTest;

	private final TiffRawVolumeStore store;
	private final RawFrameRenderer renderer;
	private final RawPresenter presenter;
	private final ExecutorService driver = Executors.newSingleThreadExecutor(Shutdown.daemonThreads("OPM-raw-play"));
	private final AtomicLong latest = new AtomicLong(Long.MIN_VALUE);
	private volatile boolean closed;
	/** The request being prepared or drawn, if any. */
	private volatile RawFrameRequest working;
	private volatile Consumer<RawPresenter.Presented> presentedListener;
	private final AtomicLong[] counts = new AtomicLong[Outcome.values().length];
	/** How many presented frames {@link #summary} takes its stage percentiles over. */
	static final int STAGE_WINDOW = 256;
	/** Queue, prepare, render and present nanoseconds of the frames presented last, a ring; guarded by itself. */
	private final long[][] stages = new long[4][STAGE_WINDOW];
	private int stagesRecorded;
	/** Which prefetch is current: a newer one, a request for another file, or close moves it on. */
	private final AtomicLong prefetchToken = new AtomicLong();
	/** The prefetched lease, if any; guarded by this. */
	private Prefetched prefetched;
	/** Prefetches started, used by the frame asked for, dropped as not fitting the budget, and released unused. */
	private final AtomicLong prefetchesStarted = new AtomicLong(), prefetchesUsed = new AtomicLong(),
			prefetchesRefused = new AtomicLong(), prefetchesReleased = new AtomicLong();

	/** A lease held ahead of the frame that will need it. */
	private static final class Prefetched {
		final List<TiffRawVolumeStore.Want> wants;
		final TiffRawVolumeStore.Ready ready;

		Prefetched(List<TiffRawVolumeStore.Want> wants, TiffRawVolumeStore.Ready ready) {
			this.wants = wants;
			this.ready = ready;
		}
	}

	/** @param renderer : owned from now on; closed with the player */
	RawStrictPlayer(TiffRawVolumeStore store, RawFrameRenderer renderer, RawPresenter presenter) {
		this.store = store;
		this.renderer = renderer;
		this.presenter = presenter;
		for (int i = 0; i < counts.length; i++) counts[i] = new AtomicLong();
	}

	/** Be told of each presented frame, on the event thread, after its result is complete. */
	void setPresentedListener(Consumer<RawPresenter.Presented> listener) {
		this.presentedListener = listener;
	}

	/**
	 * Ask for a frame. Revisions must grow: a request older than one already made is superseded at
	 * once. The future completes when the frame has been painted, or with the outcome that kept it
	 * off the screen; it never fails.
	 */
	CompletableFuture<Result> request(final RawFrameRequest r) {
		final Stages s = new Stages(r);
		final CompletableFuture<Result> result = new CompletableFuture<Result>();
		synchronized (this) {
			if (prefetchTarget != null && !prefetchTarget.equals(r.keys)) prefetchToken.incrementAndGet();
		}
		supersede(r.revision);
		presenter.pending(String.format(Locale.US, "t = %d   %s", r.timeIndex + 1, r.entry.time));
		try {
			driver.execute(() -> play(s, result));
		} catch (RejectedExecutionException closedMeanwhile) {
			finish(result, new Result(s, Outcome.FAILED, null, new IllegalStateException("The player is closed"), 0));
		}
		return result;
	}

	/**
	 * Invalidate older pending/preparing work before a new view's file has been validated or its
	 * canvas laid out. Keeps the already presented frame; does no IO and never waits for workers.
	 * A later request at this revision is allowed, an older one is not.
	 */
	void supersede(long revision) {
		latest.accumulateAndGet(revision, Math::max);
		presenter.accept(latest.get());
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
		working = r;
		TiffRawVolumeStore.Ready ready = null;
		TiffRawVolumeStore.Reservation image = null, scratch = null;
		try {
			List<TiffRawVolumeStore.Want> wants = wants(r);
			ready = takePrefetched(wants);
			if (ready != null) s.prefetched = true;
			else ready = store.prepare(r.revision, wants, () -> superseded(r)).get();
			s.cells = ready.cells();
			s.bytes = ready.bytes();
			long prepared = System.nanoTime();
			s.prepare = prepared - start;
			if (superseded(r)) throw new CancellationException(r + " superseded");

			image = store.reserve(4L * r.width * r.height);
			scratch = store.reserve(renderer.scratchBytes(r.width));
			long loadsBefore = store.stats().loads;
			BufferedImage frame;
			try {
				frame = renderer.render(r, ready, () -> superseded(r));		// returns once every band has finished
			} finally {
				s.misses = ready.misses();
				s.storeLoads = store.stats().loads - loadsBefore;
				scratch.close();
				scratch = null;
			}
			Consumer<RawFrameRequest> hook = beforeCheckForTest;
			if (hook != null) hook.accept(r);
			boolean valid = ready.isCurrentAndValid();
			ready.close();		// no band samples the cells any more: they are cache again
			ready = null;
			s.render = System.nanoTime() - prepared;
			if (!valid) throw new TiffRawVolumeStore.StaleFileException("A selected file of " + r.time + " changed while its frame was drawn");
			if (superseded(r)) throw new CancellationException(r + " superseded");
			hook = beforeHandOverForTest;
			if (hook != null) hook.accept(r);

			final long handedOver = System.nanoTime();
			RawPresenter.Frame staged = new RawPresenter.Frame(r, frame, image);
			image = null;		// the frame owns its charge now, and the presenter the frame
			presenter.present(staged).whenComplete((p, t) -> {
				long presentNanos = System.nanoTime() - handedOver;
				if (p != null) {
					finish(result, new Result(s, Outcome.PRESENTED, p, null, presentNanos));
					Consumer<RawPresenter.Presented> listener = presentedListener;
					if (listener != null) listener.accept(p);
				} else finish(result, new Result(s, outcomeOf(t), null, t, presentNanos));
			});
		} catch (Throwable t) {
			if (ready != null) ready.close();
			if (scratch != null) scratch.close();
			if (image != null) image.close();
			Throwable cause = unwrap(t);
			if (s.prepare == 0) s.prepare = System.nanoTime() - start;
			finish(result, new Result(s, outcomeOf(cause), null, cause, 0));
		} finally {
			working = null;
		}
	}

	private static Throwable unwrap(Throwable t) {
		while ((t instanceof ExecutionException || t instanceof CompletionException) && t.getCause() != null) t = t.getCause();
		return t;
	}

	private static Outcome outcomeOf(Throwable cause) {
		if (cause instanceof RawPresenter.NotShown) {
			switch (((RawPresenter.NotShown) cause).reason) {
			case FILE_CHANGED: return Outcome.CHANGED;
			case WITHDRAWN: return Outcome.CHANGED;
			case STOPPED: return Outcome.FAILED;
			default: return Outcome.SUPERSEDED;
			}
		}
		if (cause instanceof CancellationException) return Outcome.SUPERSEDED;
		if (cause instanceof TiffRawVolumeStore.StaleFileException) return Outcome.CHANGED;
		if (cause instanceof TiffRawVolumeStore.BudgetException) return Outcome.REFUSED;
		return Outcome.FAILED;
	}

	private void finish(CompletableFuture<Result> result, Result r) {
		counts[r.outcome.ordinal()].incrementAndGet();
		if (r.outcome == Outcome.PRESENTED)
			synchronized (stages) {
				int i = stagesRecorded++ % STAGE_WINDOW;
				stages[0][i] = r.queueNanos;
				stages[1][i] = r.prepareNanos;
				stages[2][i] = r.renderNanos;
				stages[3][i] = r.presentNanos;
			}
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

	/**
	 * The file versions whose changes matter now: the frame shown, the one waiting for paint, and
	 * the one being prepared or drawn.
	 */
	Set<RawAcquisition.FileKey> watchedKeys() {
		Set<RawAcquisition.FileKey> keys = new LinkedHashSet<RawAcquisition.FileKey>(presenter.watchedKeys());
		RawFrameRequest w = working;
		if (w != null) keys.addAll(w.keys);
		return keys;
	}

	/** How many requests ended each way. */
	long count(Outcome outcome) {
		return counts[outcome.ordinal()].get();
	}

	/** The file version being prefetched, if any; guarded by this. */
	private Set<RawAcquisition.FileKey> prefetchTarget;

	/**
	 * Prepare this frame's cells ahead of its request, without superseding anything. One frame
	 * ahead: a newer prefetch replaces this one. Dropped, not refused, when the budget has no room.
	 */
	void prefetch(final RawFrameRequest r) {
		final long token;
		synchronized (this) {
			if (closed) return;
			token = prefetchToken.incrementAndGet();
			prefetchTarget = r.keys;
		}
		try {
			driver.execute(() -> prepareAhead(r, token));
		} catch (RejectedExecutionException closedMeanwhile) {
			// closing: nothing to prepare
		}
	}

	private boolean prefetchCancelled(long token) {
		return closed || prefetchToken.get() != token;
	}

	/** On the driver: prepare and hold one frame's cells. */
	private void prepareAhead(RawFrameRequest r, long token) {
		if (prefetchCancelled(token)) return;
		releasePrefetched();
		List<TiffRawVolumeStore.Want> wants = wants(r);
		prefetchesStarted.incrementAndGet();
		TiffRawVolumeStore.Ready ready;
		try {
			ready = store.prepare(r.revision, wants,
					() -> prefetchCancelled(token)).get();
		} catch (Throwable t) {
			if (unwrap(t) instanceof TiffRawVolumeStore.BudgetException) prefetchesRefused.incrementAndGet();
			return;		// cancelled, changed or failed: the frame, if asked for, prepares its own
		}
		synchronized (this) {
			if (!prefetchCancelled(token)) {
				prefetched = new Prefetched(wants, ready);
				return;
			}
		}
		ready.close();
		prefetchesReleased.incrementAndGet();
	}

	/** The prefetched lease if it is of this file version and holds every cell; otherwise none, and it is released. */
	private TiffRawVolumeStore.Ready takePrefetched(List<TiffRawVolumeStore.Want> wants) {
		Prefetched p;
		synchronized (this) {
			p = prefetched;
			prefetched = null;
		}
		if (p == null) return null;
		if (covers(p.wants, wants) && p.ready.isCurrentAndValid()) {
			prefetchesUsed.incrementAndGet();
			return p.ready;
		}
		p.ready.close();
		prefetchesReleased.incrementAndGet();
		return null;
	}

	/** One coverage union per file, then one admission for the entire logical time point. */
	static List<TiffRawVolumeStore.Want> wants(RawFrameRequest r) {
		List<TiffRawVolumeStore.Want> wants = new ArrayList<TiffRawVolumeStore.Want>();
		for (RawFrameRequest.Input input : r.inputs) {
			BitSet cells = RawCoverage.cells(input.layout.dimensions(), input.layout.rowsPerStrip, r.coverageParts(input),
					r.width, r.height, r.interpolation);
			Consumer<BitSet> edit = coverageForTest;
			if (edit != null) edit.accept(cells);
			wants.add(new TiffRawVolumeStore.Want(input.key, input.layout, cells));
		}
		return wants;
	}

	private static boolean covers(List<TiffRawVolumeStore.Want> held, List<TiffRawVolumeStore.Want> wanted) {
		if (held.size() != wanted.size()) return false;
		for (TiffRawVolumeStore.Want w : wanted) {
			boolean found = false;
			for (TiffRawVolumeStore.Want h : held) {
				if (!h.key.equals(w.key)) continue;
				BitSet missing = (BitSet) w.cells.clone();
				missing.andNot(h.cells);
				if (!missing.isEmpty()) return false;
				found = true;
				break;
			}
			if (!found) return false;
		}
		return true;
	}

	private void releasePrefetched() {
		Prefetched p;
		synchronized (this) {
			p = prefetched;
			prefetched = null;
		}
		if (p != null) {
			p.ready.close();
			prefetchesReleased.incrementAndGet();
		}
	}

	/** Prefetch counters: started, used, dropped as not fitting, released unused. */
	long[] prefetchCounts() {
		return new long[] { prefetchesStarted.get(), prefetchesUsed.get(), prefetchesRefused.get(), prefetchesReleased.get() };
	}

	String summary() {
		String s = String.format(Locale.US, "%d presented, %d superseded, %d changed, %d refused, %d failed", count(Outcome.PRESENTED),
				count(Outcome.SUPERSEDED), count(Outcome.CHANGED), count(Outcome.REFUSED), count(Outcome.FAILED));
		double[] m = stageMillis(0.5), p = stageMillis(0.95);
		if (m == null) return s;
		return s + String.format(Locale.US, "; median (95%%) of the last %d presented: queue %.1f (%.1f) ms, prepare %.1f (%.1f) ms, "
				+ "render %.1f (%.1f) ms, present %.1f (%.1f) ms", Math.min(stagesRecorded, STAGE_WINDOW), m[0], p[0], m[1], p[1], m[2], p[2],
				m[3], p[3]);
	}

	/** The median of each stage - queue, prepare, render, present - over the frames presented last; see {@link #stageMillis}. */
	double[] stageMedianMillis() {
		return stageMillis(0.5);
	}

	/**
	 * Each stage - queue, prepare, render, present - at this quantile, nearest rank, over the frames
	 * presented last, up to {@link #STAGE_WINDOW}, in milliseconds; null before any was presented.
	 */
	double[] stageMillis(double quantile) {
		synchronized (stages) {
			int n = Math.min(stagesRecorded, STAGE_WINDOW);
			if (n == 0) return null;
			int rank = Math.max(1, Math.min(n, (int) Math.ceil(quantile * n)));
			double[] at = new double[4];
			for (int k = 0; k < 4; k++) {
				long[] sorted = java.util.Arrays.copyOf(stages[k], n);
				java.util.Arrays.sort(sorted);
				at[k] = sorted[rank - 1] / 1e6;
			}
			return at;
		}
	}

	/**
	 * Stop: nothing more is prepared or shown, the frame on screen is taken off and its image
	 * released. Never waits: a request still running stops at its next check, releases what it
	 * holds once its bands have finished, and the renderer is closed after it; see {@link #awaitStopped}.
	 */
	@Override
	public void close() {
		synchronized (this) {
			if (closed) return;
			closed = true;
		}
		presenter.stop();
		try {
			driver.execute(this::releasePrefetched);		// after a prefetch still preparing has stopped
		} catch (RejectedExecutionException alreadyShut) {
			releasePrefetched();
		}
		try {
			driver.execute(renderer::close);		// after whatever is running or queued
		} catch (RejectedExecutionException alreadyShut) {
			renderer.close();
		}
		driver.shutdown();
	}

	/** Whether everything the player and its renderer started has finished, within {@code millis} of now. */
	boolean awaitStopped(long millis) {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
		try {
			if (!driver.awaitTermination(millis, TimeUnit.MILLISECONDS)) return false;
			return renderer.awaitTermination(Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}
}
