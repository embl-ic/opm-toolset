package de.embl.iclm;

import java.util.List;

import bdv.cache.CacheControl;

/**
 * Which time points of one acquisition channel the Online Deskew View shows, decided once per
 * frame (joint contracts C2.6, C5.1).
 *
 * <p>BigDataViewer asks each source {@code isPresent} and then, separately, for its pixels and
 * geometry - the renderer while it draws, the box overlay on the event thread. A file can be
 * withdrawn between those calls, and if each half asked the catalogue directly one half could be
 * drawn and the other skipped in the same frame. So every source of a view shares one presence,
 * and it changes only at the start of a frame: BigDataViewer calls {@link #prepareNextFrame()} on
 * the view's cache controls before it draws each frame. A time point withdrawn or newly checked
 * appears or goes in the next frame, both halves together; the session asks for that frame.
 */
final class RawPresence implements CacheControl {

	private final RawAcquisition acquisition;
	private final List<RawAcquisition.TimeKey> times;
	private final int channel;
	private volatile boolean[] shown;

	RawPresence(RawAcquisition acquisition, List<RawAcquisition.TimeKey> times, int channel) {
		this.acquisition = acquisition;
		this.times = times;
		this.channel = channel;
		refresh();
	}

	/** Whether time index {@code t} is shown in the frame being drawn. */
	boolean present(int t) {
		boolean[] s = shown;
		return t >= 0 && t < s.length && s[t];
	}

	/** Take the catalogue's current state for the next frame. */
	void refresh() {
		boolean[] s = new boolean[times.size()];
		for (int t = 0; t < s.length; t++) {
			RawAcquisition.Entry e = acquisition.entry(times.get(t), channel);
			s[t] = e != null && e.viewable();
		}
		shown = s;
	}

	@Override
	public void prepareNextFrame() {
		refresh();
	}
}
