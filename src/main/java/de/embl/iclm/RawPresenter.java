package de.embl.iclm;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import bdv.viewer.OverlayRenderer;

/**
 * The presentation boundary of completed-frame playback (joint decision D2, contracts C6.2, C6.3):
 * an overlay on BigDataViewer's canvas that shows staged frames - each drawn whole by
 * {@link RawFrameRenderer} before it is handed over - and acknowledges each one, as
 * {@link Presented}, when the canvas has painted that very image.
 *
 * <ul>
 * <li><b>The acknowledgement is tied to the request.</b> It is given in {@link #drawOverlays} on the
 *     event thread, after the image handed over with that request has been drawn - not on a render
 *     callback, a transform or a counter, none of which says which request an image answers.</li>
 * <li><b>The last complete frame stays until its replacement is shown.</b> Nothing is drawn between
 *     one presented frame and the next; a frame handed over is painted whole or not at all.</li>
 * <li><b>Frames are shown in order, the newest winning.</b> A frame handed over while an older one
 *     is still waiting to be painted replaces it, and the older one's acknowledgement fails as
 *     superseded: it was never on screen.</li>
 * <li><b>The time label and the delivered-frame counter follow what is shown</b>: they are drawn
 *     here, from the presented request, not from BigDataViewer's state, whose own label moves as
 *     soon as a time point is asked for. A frame still being prepared is named on a line of its
 *     own, as pending.</li>
 * <li><b>A shown frame whose file is found changed is withdrawn</b>: the canvas goes black and
 *     says so. Its pixels came from a version that has been checked and is no longer on disk,
 *     which a strict view does not go on showing as the acquisition.</li>
 * </ul>
 *
 * <p>The frame covers BigDataViewer's own image completely: it is drawn over an opaque black
 * ground, as BigDataViewer draws its own image over its black canvas.
 */
final class RawPresenter implements OverlayRenderer {

	/** A frame that has been painted: the request it answers, the image painted, when, and how many before it. */
	static final class Presented {
		final RawFrameRequest request;
		/** The very image the acknowledging paint drew. */
		final BufferedImage image;
		final long nanos;
		final long delivered;
		/** Frames per second over the last few presented, or 0 before there are two. */
		final double fps;

		Presented(RawFrameRequest request, BufferedImage image, long nanos, long delivered, double fps) {
			this.request = request;
			this.image = image;
			this.nanos = nanos;
			this.delivered = delivered;
			this.fps = fps;
		}

		@Override public String toString() {
			return String.format(Locale.US, "presented %s as frame %d (%.1f fps)", request, delivered, fps);
		}
	}

	private static final class Pending {
		final BufferedImage image;
		final RawFrameRequest request;
		final CompletableFuture<Presented> done;

		Pending(BufferedImage image, RawFrameRequest request, CompletableFuture<Presented> done) {
			this.image = image;
			this.request = request;
			this.done = done;
		}
	}

	private static final int FPS_WINDOW = 10;

	private final java.awt.Component canvas;
	private final AtomicReference<Pending> pending = new AtomicReference<Pending>();
	/** What is being prepared, for the pending line; null when nothing is. */
	private volatile String pendingNote;
	/* The event thread's own state. */
	private BufferedImage shown;
	private RawFrameRequest shownRequest;
	/** The request whose frame was withdrawn from the screen, until another is shown. */
	private RawFrameRequest withdrawn;
	private long delivered;
	private final ArrayDeque<Long> recent = new ArrayDeque<Long>();
	private double fps;
	private volatile boolean active;
	/** Stopped for good: a frame handed over now is refused at once, so that it neither shows nor waits for ever. */
	private boolean stopped;

	/** @param canvas : the component this overlay is drawn on, repainted when a frame is handed over */
	RawPresenter(java.awt.Component canvas) {
		this.canvas = canvas;
	}

	/**
	 * Hand over a complete frame. The future completes on the event thread once the frame has been
	 * painted, or fails if a newer frame is handed over before that happens.
	 */
	CompletableFuture<Presented> present(BufferedImage image, RawFrameRequest request) {
		CompletableFuture<Presented> done = new CompletableFuture<Presented>();
		Pending older;
		synchronized (this) {
			if (stopped) {
				done.completeExceptionally(new CancellationException(request + " not shown: playback stopped"));
				return done;
			}
			active = true;
			older = pending.getAndSet(new Pending(image, request, done));
		}
		if (older != null) older.done.completeExceptionally(new CancellationException(older.request + " superseded before it was shown"));
		canvas.repaint();
		return done;
	}

	/** Name the frame being prepared, or null for none; it is shown as pending, never as the time shown. */
	void pending(String note) {
		pendingNote = note;
		canvas.repaint();
	}

	/**
	 * The file a frame was drawn from was found changed: a frame of it still waiting is not shown,
	 * and one on screen is taken off. Any thread.
	 */
	void withdraw(final RawAcquisition.FileKey key) {
		Pending p = pending.get();
		if (p != null && p.request.key.equals(key) && pending.compareAndSet(p, null))
			p.done.completeExceptionally(new CancellationException(p.request + " not shown: its file changed"));
		javax.swing.SwingUtilities.invokeLater(() -> {
			if (shownRequest != null && shownRequest.key.equals(key)) {
				withdrawn = shownRequest;
				shown = null;
				shownRequest = null;
			}
			canvas.repaint();
		});
	}

	/** Stop drawing over BigDataViewer's own image, for good. A frame still waiting is not shown. */
	void stop() {
		Pending older;
		synchronized (this) {
			stopped = true;
			active = false;
			older = pending.getAndSet(null);
		}
		if (older != null) older.done.completeExceptionally(new CancellationException(older.request + " not shown: playback stopped"));
		canvas.repaint();
	}

	@Override
	public void drawOverlays(Graphics g) {
		if (!active) return;
		Pending p = pending.getAndSet(null);
		if (p != null) {		// counted before the label is drawn, which shows it
			shown = p.image;
			shownRequest = p.request;
			withdrawn = null;
			delivered++;
			recent.addLast(System.nanoTime());
			if (recent.size() > FPS_WINDOW) recent.removeFirst();
			fps = recent.size() < 2 ? 0 : (recent.size() - 1) / ((recent.getLast() - recent.getFirst()) / 1e9);
		}
		if (shown == null && withdrawn == null) return;
		g.setColor(Color.BLACK);
		g.fillRect(0, 0, Math.max(canvas.getWidth(), shown == null ? 0 : shown.getWidth()),
				Math.max(canvas.getHeight(), shown == null ? 0 : shown.getHeight()));
		if (shown != null) g.drawImage(shown, 0, 0, null);
		drawLabel((Graphics2D) g);
		// acknowledged once the image and its label are drawn, never before
		if (p != null) p.done.complete(new Presented(p.request, p.image, System.nanoTime(), delivered, fps));
	}

	private void drawLabel(Graphics2D g) {
		String label = shown != null
				? String.format(Locale.US, "t = %d   %s   frame %d   %.1f fps", shownRequest.timeIndex + 1, shownRequest.entry.time,
						delivered, fps)
				: String.format(Locale.US, "t = %d   %s   withdrawn: the file changed", withdrawn.timeIndex + 1, withdrawn.entry.time);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		int bottom = Math.max(30, (shown != null ? shown.getHeight() : canvas.getHeight()) - 10);
		g.setColor(Color.WHITE);
		g.drawString(label, 10, bottom);
		String note = pendingNote;
		if (note != null) {
			g.setColor(Color.LIGHT_GRAY);
			g.drawString("pending: " + note, 10, bottom - 16);
		}
	}

	/** The time label as drawn now; for tests, on the event thread. */
	String label() {
		if (shown != null) return "t = " + (shownRequest.timeIndex + 1) + " " + shownRequest.entry.time + " frame " + delivered;
		if (withdrawn != null) return "t = " + (withdrawn.timeIndex + 1) + " " + withdrawn.entry.time + " withdrawn";
		return "";
	}

	// for tests: the event thread's state, read on the event thread

	RawFrameRequest shownRequest() { return shownRequest; }
	RawFrameRequest withdrawnRequest() { return withdrawn; }
	BufferedImage shownImage() { return shown; }
	long delivered() { return delivered; }
	/** A frame has been handed over and not painted yet; any thread. */
	boolean waiting() { return pending.get() != null; }
	String pendingNote() { return pendingNote; }
	boolean isActive() { return active; }
}
