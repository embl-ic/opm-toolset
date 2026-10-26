package de.embl.iclm;

/** The two decisions of playback that need no view: which time point comes next, and how long to wait for it. */
final class RawPlaybackSchedule {

	private RawPlaybackSchedule() {}

	/**
	 * The time point after {@code t} going {@code direction} (+1 or -1) among {@code count}; wrapping
	 * at either end when looping, -1 at the end otherwise.
	 */
	static int next(int t, int direction, int count, boolean loop) {
		if (count <= 0) return -1;
		int n = t + (direction < 0 ? -1 : 1);
		if (n >= 0 && n < count) return n;
		if (!loop) return -1;
		return n < 0 ? count - 1 : 0;
	}

	/**
	 * Milliseconds still to wait, at {@code now}, before asking for the next frame when the last was
	 * asked for at {@code askedNanos}, so as not to exceed {@code fps}; 0 when the frame took longer.
	 */
	static long waitMillis(long askedNanos, long nowNanos, double fps) {
		long periodNanos = Math.round(1e9 / fps);
		long left = askedNanos + periodNanos - nowNanos;
		return left <= 0 ? 0 : (left + 999999) / 1000000;
	}
}
