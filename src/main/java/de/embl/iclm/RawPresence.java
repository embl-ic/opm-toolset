package de.embl.iclm;

import java.util.List;
import java.util.function.Supplier;

import bdv.cache.CacheControl;

/**
 * Which time points of one display plan the Online Deskew View shows, and from which file versions,
 * decided once per frame (joint contracts C2.6, C5.1; INTERFACES §1).
 *
 * <p>BigDataViewer asks each source {@code isPresent} and then, separately, for its pixels and
 * geometry - the renderer while it draws, the box overlay on the event thread. A file can be
 * withdrawn, checked again or replaced between those calls, and if each half asked the catalogue
 * directly one half could be drawn and the other skipped, or the two drawn from different versions,
 * in the same frame. So every source of a view shares one presence, and it changes only at the start
 * of a frame: BigDataViewer calls {@link #prepareNextFrame()} on the view's cache controls before it
 * draws each frame, and the presence then freezes a {@link Frame} - the catalogue {@link
 * RawAcquisition.Snapshot} it follows, and for each time point whether it is shown and the version and
 * layout it is shown from. A time point withdrawn, checked or published appears or goes in the next
 * frame, both halves together; the session asks for that frame.
 *
 * <p><b>A presence keeps its numbering.</b> It adopts a newer snapshot only while that snapshot keeps
 * every time point it had at its index - a growing axis. A snapshot that renumbers them, because a late
 * time point arrived before others, is the start of a new presence and new sources, which the session
 * puts into BigDataViewer's state in one transaction; a frame still drawing with the old sources keeps
 * the old numbering to the end.
 *
 * <p>While completed-frame playback covers the canvas with its own staged frames, the presence is
 * {@linkplain #setHidden hidden}: BigDataViewer then draws nothing under them, and reads nothing.
 */
final class RawPresence implements CacheControl {

	/** One frame's view of the catalogue: frozen when the frame begins, never changed. */
	static final class Frame {
		final RawAcquisition.Snapshot snapshot;
		private final boolean[] shown;
		private final RawAcquisition.FileKey[] keys;
		private final RawTiffLayout[] layouts;

		private Frame(RawAcquisition.Snapshot snapshot, boolean hidden) {
			this.snapshot = snapshot;
			int n = snapshot.size();
			shown = new boolean[n];
			keys = new RawAcquisition.FileKey[n];
			layouts = new RawTiffLayout[n];
			for (int t = 0; t < n; t++) {
				RawAcquisition.Entry e = snapshot.entry(t);
				if (e == null) continue;
				RawAcquisition.Entry.Shown v = e.shown();		// the version and its layout, in one read
				shown[t] = !hidden && v != null;
				keys[t] = v != null ? v.key : e.key();
				layouts[t] = v != null ? v.layout : e.layout();		// a withdrawn time point keeps its geometry
			}
		}

		int size() {
			return shown.length;
		}

		/** Whether time index {@code t} is shown in this frame. */
		boolean present(int t) {
			return t >= 0 && t < shown.length && shown[t];
		}

		/** The version time index {@code t} is shown from in this frame, or null. */
		RawAcquisition.FileKey key(int t) {
			return t >= 0 && t < keys.length ? keys[t] : null;
		}

		/** The layout of that version, kept for a time point withdrawn meanwhile; null for one never shown. */
		RawTiffLayout layout(int t) {
			return t >= 0 && t < layouts.length ? layouts[t] : null;
		}
	}

	private final Supplier<RawAcquisition.Snapshot> current;
	private volatile Frame frame;
	private volatile boolean hidden;

	/** Following the snapshots {@code current} supplies, within the numbering of the first. */
	RawPresence(Supplier<RawAcquisition.Snapshot> current) {
		this.current = current;
		refresh();
	}

	/** Over a fixed list of time points: a folder that is not followed. */
	RawPresence(RawAcquisition acquisition, List<RawAcquisition.TimeKey> times, int channel) {
		this(constant(RawAcquisition.Snapshot.of(acquisition, times, channel)));
	}

	private static Supplier<RawAcquisition.Snapshot> constant(RawAcquisition.Snapshot s) {
		return () -> s;
	}

	/** The frame being drawn. */
	Frame frame() {
		return frame;
	}

	/** Whether time index {@code t} is shown in the frame being drawn. */
	boolean present(int t) {
		return frame.present(t);
	}

	/** Show nothing from the next frame on, or show again; the caller asks for that frame. */
	void setHidden(boolean hidden) {
		this.hidden = hidden;
	}

	boolean isHidden() {
		return hidden;
	}

	/** Take the catalogue's current state for the next frame: its newest snapshot, if it keeps this presence's numbering. */
	void refresh() {
		Frame f = frame;
		RawAcquisition.Snapshot s = current.get();
		if (f != null && !s.extendsFrom(f.snapshot)) s = f.snapshot;
		frame = new Frame(s, hidden);
	}

	@Override
	public void prepareNextFrame() {
		refresh();
	}
}
