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
	 * When the next frame is due on a cadence of {@code fps}: one period after the last was due, not
	 * after it was actually asked for, so a timer that fired late - by up to the system timer's
	 * granularity, about 16 ms on Windows - is made up on the next frame instead of slowing every
	 * one; but never before {@code now}: a frame slower than the period restarts the cadence rather
	 * than being followed by a burst.
	 */
	static long nextDue(long dueNanos, long nowNanos, double fps) {
		return Math.max(dueNanos + Math.round(1e9 / fps), nowNanos);
	}

	/** Milliseconds to wait, at {@code now}, for a frame due at {@code dueNanos}: rounded up, 0 when it is due. */
	static long waitMillis(long dueNanos, long nowNanos) {
		long left = dueNanos - nowNanos;
		return left <= 0 ? 0 : (left + 999999) / 1000000;
	}
}
