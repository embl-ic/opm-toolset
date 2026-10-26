package de.embl.iclm;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import bdv.viewer.OverlayRenderer;

/**
 * The presentation boundary of completed-frame playback (joint decision D2, contracts C6.2, C6.3):
 * an overlay on BigDataViewer's canvas that shows staged frames - each drawn whole by
 * {@link RawFrameRenderer} before it is handed over - and acknowledges each one, as
 * {@link Presented}, from the canvas paint that draws it whole.
 *
 * <ul>
 * <li><b>A frame is presented by a full paint of the canvas, and by nothing else.</b> Handing it
 *     over, a paint clipped to part of the canvas, or painting the shown frame again acknowledges
 *     nothing. A clipped paint redraws what is shown and asks for a full one.</li>
 * <li><b>Only the newest request can be presented.</b> Every request the player accepts is
 *     announced here ({@link #accept}) as it is accepted, and a frame of an older revision that is
 *     still waiting for paint is dropped at once - it was never on screen, and the frame shown
 *     before it stays. The commit itself checks the revision again, the file and the canvas size:
 *     a frame for a canvas since resized is not shown on it.</li>
 * <li><b>The last complete frame stays until its replacement has been painted</b>, with its own
 *     time label and count; a request still in progress is named on a line of its own, as pending.</li>
 * <li><b>A shown frame whose file is found changed is withdrawn</b>: the canvas goes black and says
 *     so, and a waiting frame of that file version is dropped.</li>
 * <li><b>Each frame's pixels are owned once.</b> A {@link Frame} holds the image and the budget it
 *     is charged to, and from the hand-over the presenter alone releases it - when it is replaced
 *     on screen (after the replacement has been drawn), dropped, withdrawn or stopped - which clears
 *     the pixels and the charge together. {@link Presented} carries no pixels.</li>
 * </ul>
 *
 * <p>All state is guarded by this object; the commit, the drop of an older frame, a withdrawal and
 * a stop cannot interleave. Futures complete, and frames are released, after the lock is let go.
 * The frame covers BigDataViewer's own image completely: it is drawn over an opaque black ground,
 * as BigDataViewer draws its own image over its black canvas.
 */
final class RawPresenter implements OverlayRenderer {

	/** Why a frame handed over was not shown. */
	enum Reason {
		/** A newer request was accepted before it was painted. */
		SUPERSEDED,
		/** Its file was found changed. */
		FILE_CHANGED,
		/** The canvas no longer has the size the frame was drawn for. */
		VIEWPORT_CHANGED,
		/** Playback stopped. */
		STOPPED
	}

	/** The acknowledgement that a frame was not, and will not be, shown. */
	static final class NotShown extends CancellationException {
		private static final long serialVersionUID = 1L;
		final Reason reason;

		NotShown(Reason reason, String message) {
			super(message);
			this.reason = reason;
		}
	}

	/**
	 * A finished frame and the budget its pixels are charged to. Released once, by whoever owns it
	 * - the player until the hand-over, the presenter from then on - which drops the pixels and the
	 * charge together.
	 */
	static final class Frame {
		final RawFrameRequest request;
		private BufferedImage image;
		private final AutoCloseable budget;
		private boolean released;

		Frame(RawFrameRequest request, BufferedImage image, AutoCloseable budget) {
			this.request = request;
			this.image = image;
			this.budget = budget;
		}

		synchronized BufferedImage image() {
			return image;
		}

		void release() {
			AutoCloseable b;
			synchronized (this) {
				if (released) return;
				released = true;
				image = null;
				b = budget;
			}
			try {
				if (b != null) b.close();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}

		synchronized boolean isReleased() {
			return released;
		}
	}

	/** A frame that has been painted: the request it answers, when, and how many before it. No pixels. */
	static final class Presented {
		final RawFrameRequest request;
		final long nanos;
		final long delivered;
		/** Frames per second over the last few presented, or 0 before there are two. */
		final double fps;

		Presented(RawFrameRequest request, long nanos, long delivered, double fps) {
			this.request = request;
			this.nanos = nanos;
			this.delivered = delivered;
			this.fps = fps;
		}

		@Override public String toString() {
			return String.format(Locale.US, "presented %s as frame %d (%.1f fps)", request, delivered, fps);
		}
	}

	private static final class Waiting {
		final Frame frame;
		final CompletableFuture<Presented> done;

		Waiting(Frame frame, CompletableFuture<Presented> done) {
			this.frame = frame;
			this.done = done;
		}
	}

	private static final int FPS_WINDOW = 10;

	private final Component canvas;
	/* guarded by this */
	private long accepted = Long.MIN_VALUE;
	private Waiting pending;
	private Frame shown;
	private RawFrameRequest withdrawn;
	private final Set<RawAcquisition.FileKey> withdrawnKeys = new HashSet<RawAcquisition.FileKey>();
	private boolean active, stopped;
	private long delivered;
	private final ArrayDeque<Long> recent = new ArrayDeque<Long>();
	private double fps;
	/* what is being prepared, for the pending line; null when nothing is */
	private volatile String pendingNote;
	/** Test seam: told, on the event thread, of each frame as it is committed; it must not keep the image. */
	private volatile BiConsumer<RawFrameRequest, BufferedImage> commitObserver;

	/** @param canvas : the component this overlay is drawn on, repainted when a frame is handed over */
	RawPresenter(Component canvas) {
		this.canvas = canvas;
	}

	/**
	 * A request with this revision has been accepted: no older frame may be presented from now on.
	 * One still waiting for paint is dropped now, as superseded.
	 */
	void accept(long revision) {
		Waiting dropped = null;
		synchronized (this) {
			if (revision > accepted) accepted = revision;
			if (pending != null && pending.frame.request.revision < accepted) {
				dropped = pending;
				pending = null;
			}
		}
		if (dropped != null)
			drop(dropped, Reason.SUPERSEDED, dropped.frame.request + " superseded by request " + revision + " before it was painted");
	}

	/**
	 * Hand over a complete frame, and its ownership. The future completes, on the event thread,
	 * once a full paint of the canvas has drawn the frame; or fails with {@link NotShown} if it is
	 * superseded, its file is found changed, the canvas is resized or playback stops before that.
	 */
	CompletableFuture<Presented> present(Frame frame) {
		CompletableFuture<Presented> done = new CompletableFuture<Presented>();
		Waiting now = new Waiting(frame, done), older = null;
		Reason refused = null;
		synchronized (this) {
			RawFrameRequest r = frame.request;
			if (stopped) refused = Reason.STOPPED;
			else if (r.revision != accepted) refused = Reason.SUPERSEDED;
			else if (withdrawnKeys.contains(r.key)) refused = Reason.FILE_CHANGED;
			else {
				active = true;
				older = pending;
				pending = now;
			}
		}
		if (refused != null) {
			drop(now, refused, frame.request + " not shown: " + describe(refused));
			return done;
		}
		if (older != null) drop(older, Reason.SUPERSEDED, older.frame.request + " superseded before it was painted");
		canvas.repaint();
		return done;
	}

	/** Name the frame being prepared, or null for none; it is shown as pending, never as the time shown. */
	void pending(String note) {
		pendingNote = note;
		canvas.repaint();
	}

	/**
	 * The file version a frame was drawn from was found changed: a waiting frame of it is dropped,
	 * a shown one taken off, and none of it is accepted from now on. Any thread.
	 */
	void withdraw(RawAcquisition.FileKey key) {
		Waiting dropped = null;
		Frame taken = null;
		synchronized (this) {
			withdrawnKeys.add(key);
			if (pending != null && pending.frame.request.key.equals(key)) {
				dropped = pending;
				pending = null;
			}
			if (shown != null && shown.request.key.equals(key)) {
				taken = shown;
				withdrawn = shown.request;
				shown = null;
			}
		}
		if (dropped != null) drop(dropped, Reason.FILE_CHANGED, dropped.frame.request + " not shown: its file changed");
		if (taken != null) taken.release();
		canvas.repaint();
	}

	/** Stop for good: nothing more is shown, a waiting frame is dropped, the shown one released. */
	void stop() {
		Waiting dropped;
		Frame taken;
		synchronized (this) {
			stopped = true;
			active = false;
			dropped = pending;
			pending = null;
			taken = shown;
			shown = null;
			withdrawn = null;
		}
		if (dropped != null) drop(dropped, Reason.STOPPED, dropped.frame.request + " not shown: playback stopped");
		if (taken != null) taken.release();
		canvas.repaint();
	}

	private static void drop(Waiting w, Reason reason, String message) {
		w.frame.release();
		w.done.completeExceptionally(new NotShown(reason, message));
	}

	private static String describe(Reason reason) {
		switch (reason) {
		case FILE_CHANGED: return "its file changed";
		case STOPPED: return "playback stopped";
		case VIEWPORT_CHANGED: return "the canvas was resized";
		default: return "superseded";
		}
	}

	/** Whether this paint reaches every pixel of a frame of {@code w x h}: no clip, or one that holds it. */
	private static boolean full(Graphics g, int w, int h) {
		Rectangle clip = g.getClipBounds();
		return clip == null || (clip.x <= 0 && clip.y <= 0 && clip.x + clip.width >= w && clip.y + clip.height >= h);
	}

	@Override
	public void drawOverlays(Graphics g) {
		Waiting committed = null, dropped = null;
		Frame replaced = null;
		Presented presented = null;
		boolean askFullPaint = false;
		synchronized (this) {
			if (!active || stopped) return;
			int cw = canvas.getWidth(), ch = canvas.getHeight();
			Waiting w = pending;
			if (w != null) {
				RawFrameRequest r = w.frame.request;
				if (r.width != cw || r.height != ch) {
					pending = null;
					dropped = w;
				} else if (!full(g, r.width, r.height)) {
					askFullPaint = true;		// redraw what is shown; the waiting frame waits for a full paint
				} else {
					pending = null;
					committed = w;
					replaced = shown;
					shown = w.frame;
					withdrawn = null;
					delivered++;		// before the label is drawn, which shows it
					recent.addLast(System.nanoTime());
					if (recent.size() > FPS_WINDOW) recent.removeFirst();
					fps = recent.size() < 2 ? 0 : (recent.size() - 1) / ((recent.getLast() - recent.getFirst()) / 1e9);
				}
			}
			BufferedImage image = shown == null ? null : shown.image();
			if (image != null || withdrawn != null) {
				g.setColor(Color.BLACK);
				g.fillRect(0, 0, Math.max(cw, image == null ? 0 : image.getWidth()), Math.max(ch, image == null ? 0 : image.getHeight()));
				if (image != null) g.drawImage(image, 0, 0, null);
				drawLabel((Graphics2D) g, image);
			}
			if (committed != null) {
				BiConsumer<RawFrameRequest, BufferedImage> observer = commitObserver;
				if (observer != null) observer.accept(committed.frame.request, image);
				presented = new Presented(committed.frame.request, System.nanoTime(), delivered, fps);
			}
		}
		// the replaced frame goes only now, after its replacement has been drawn: both were charged meanwhile
		if (replaced != null) replaced.release();
		if (dropped != null) drop(dropped, Reason.VIEWPORT_CHANGED, dropped.frame.request + " not shown: the canvas is now "
				+ canvas.getWidth() + " x " + canvas.getHeight());
		if (askFullPaint) canvas.repaint();
		if (committed != null) committed.done.complete(presented);
	}

	/** Caller holds the lock. */
	private void drawLabel(Graphics2D g, BufferedImage image) {
		String label = image != null
				? String.format(Locale.US, "t = %d   %s   frame %d   %.1f fps", shown.request.timeIndex + 1, shown.request.entry.time,
						delivered, fps)
				: String.format(Locale.US, "t = %d   %s   withdrawn: the file changed", withdrawn.timeIndex + 1, withdrawn.entry.time);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		int bottom = Math.max(30, (image != null ? image.getHeight() : canvas.getHeight()) - 10);
		g.setColor(Color.WHITE);
		g.drawString(label, 10, bottom);
		String note = pendingNote;
		if (note != null) {
			g.setColor(Color.LIGHT_GRAY);
			g.drawString("pending: " + note, 10, bottom - 16);
		}
	}

	/** The file versions whose generation matters now: the frame shown and the one waiting for paint. */
	synchronized Set<RawAcquisition.FileKey> watchedKeys() {
		Set<RawAcquisition.FileKey> keys = new LinkedHashSet<RawAcquisition.FileKey>();
		if (shown != null) keys.add(shown.request.key);
		if (pending != null) keys.add(pending.frame.request.key);
		return keys;
	}

	/** The time label as drawn now. */
	synchronized String label() {
		if (shown != null) return "t = " + (shown.request.timeIndex + 1) + " " + shown.request.entry.time + " frame " + delivered;
		if (withdrawn != null) return "t = " + (withdrawn.timeIndex + 1) + " " + withdrawn.entry.time + " withdrawn";
		return "";
	}

	void setCommitObserver(BiConsumer<RawFrameRequest, BufferedImage> observer) {
		this.commitObserver = observer;
	}

	// for tests and the session

	synchronized RawFrameRequest shownRequest() { return shown == null ? null : shown.request; }
	synchronized RawFrameRequest withdrawnRequest() { return withdrawn; }
	/** Whether pixels are held for the shown frame; the image itself is not handed out. */
	synchronized boolean hasShownImage() { return shown != null && shown.image() != null; }
	synchronized long delivered() { return delivered; }
	/** Frames per second over the last few presented, or 0 before there are two. */
	synchronized double fps() { return fps; }
	/** A frame has been handed over and not painted yet. */
	synchronized boolean waiting() { return pending != null; }
	synchronized boolean isActive() { return active; }
	String pendingNote() { return pendingNote; }
}
