package de.embl.iclm;

import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * Play, pause, step and loop over the time points of an Online Deskew View, in either of the two
 * labelled modes of joint contract C6.1. On the event thread only.
 *
 * <ul>
 * <li><b>Progressive</b> - BigDataViewer's own drawing, refining as cells arrive. Playback moves
 *     the time point at the target rate whatever has been drawn, and counts the moves made while
 *     the view was still drawing placeholders for cells not yet read: those frames were not
 *     complete when they were left.</li>
 * <li><b>Completed frames</b> - each time point is asked for through the session's navigation
 *     entry and shown only once it is complete (joint decision D2). The next one is asked for
 *     only after the last has ended, and no sooner than the target rate allows: a slow frame slows
 *     playback, it is never skipped to keep the rate. A time point that cannot be shown - its file
 *     incomplete, failed or changed - is skipped, with a note and a count, never silently. A frame
 *     the budget cannot hold, or one that fails, pauses playback and says why.</li>
 * </ul>
 *
 * Stepping and the slider pause playback. Loop wraps at either end; without it playback stops at
 * the end. The view's own controls - its world-plane actions, its resize - re-ask the time point
 * being played, and playback carries on from it.
 */
final class RawPlayback {

	enum Mode {
		PROGRESSIVE("Progressive"), COMPLETED("Completed frames");

		final String label;

		Mode(String label) {
			this.label = label;
		}

		@Override public String toString() {
			return label;
		}
	}

	/** What playback needs of the view. */
	interface Host {
		int timeCount();
		/** The time point BigDataViewer shows. */
		int currentTime();
		/** In completed mode, the time point last asked for, shown or not. */
		int requestedTime();
		/** Progressive mode: move BigDataViewer's time point. */
		void showTime(int t);
		/** Completed mode: ask for a frame through the session's navigation entry. */
		CompletableFuture<RawStrictPlayer.Result> requestFrame(int t);
		/** Enter completed mode; false if the view cannot. */
		boolean startCompleted();
		void stopCompleted();
		/** Placeholder cells handed to the progressive view so far: a count that grows while it draws incomplete data. */
		long placeholdersHandedOut();
		/** Frames per second over the last few presented, or 0. */
		double presentedFps();
		/** Say what playback is doing. */
		void note(String text);
	}

	/** Run a task on the event thread after a delay; returns what cancels it. */
	interface Scheduler {
		Runnable schedule(Runnable task, long delayMillis);
	}

	/** The event thread's own timer. */
	static final Scheduler SWING = (task, delay) -> {
		javax.swing.Timer timer = new javax.swing.Timer((int) Math.max(0, delay), e -> task.run());
		timer.setRepeats(false);
		timer.start();
		return timer::stop;
	};

	/** What playback has done since it was last started. */
	static final class Stats {
		/** Completed mode: frames asked for, presented, and time points skipped as unavailable. */
		long asked, presented, skipped;
		/** Progressive mode: moves made, and of those, moves made while the view was still drawing placeholders. */
		long advanced, advancedWhileLoading;
	}

	private final Host host;
	private final Scheduler scheduler;
	private final LongSupplier clock;
	private Mode mode = Mode.PROGRESSIVE;
	private boolean playing, loop, closed;
	private double fps = 10;
	private int direction = 1;
	/** The step or tick waiting to run, if any: what cancels it. */
	private Runnable pending;
	/** Completed mode: the time point playback asked for last, and when; -1 for none. */
	private int asked = -1;
	private long askedNanos;
	private long placeholdersSeen;
	private String lastEvent = "";
	private Stats stats = new Stats();

	RawPlayback(Host host, Scheduler scheduler, LongSupplier clock) {
		this.host = host;
		this.scheduler = scheduler;
		this.clock = clock;
	}

	/* ---------------------------------------------------------------- controls */

	Mode mode() { return mode; }
	boolean isPlaying() { return playing; }
	boolean isLooping() { return loop; }
	double targetFps() { return fps; }
	Stats stats() { return stats; }

	void setLoop(boolean loop) {
		this.loop = loop;
		report();
	}

	void setTargetFps(double fps) {
		if (!(fps > 0) || Double.isInfinite(fps)) throw new IllegalArgumentException("A rate of " + fps + " frames per second");
		this.fps = fps;
		report();
	}

	/** Switch modes; playing carries on in the new mode. Entering completed mode shows the current time point complete. */
	void setMode(Mode next) {
		if (closed || next == mode) return;
		boolean was = playing;
		stop("");
		if (next == Mode.COMPLETED) {
			if (!host.startCompleted()) {
				event("Completed frames are not available now");
				return;
			}
			mode = next;
			int t = host.currentTime();
			if (!was) {
				asked = t;
				askedNanos = clock.getAsLong();
				host.requestFrame(t);
			}
		} else {
			host.stopCompleted();
			mode = next;
		}
		if (was) play();
		else report();
	}

	void play() {
		if (closed || playing) return;
		playing = true;
		stats = new Stats();
		lastEvent = "";
		if (mode == Mode.COMPLETED) {
			// from the frame shown on: the next one; asked first, at once
			int from = host.requestedTime();
			int t = RawPlaybackSchedule.next(from, direction, host.timeCount(), true);
			askCompleted(t < 0 ? from : t);
		} else {
			placeholdersSeen = host.placeholdersHandedOut();
			pending = scheduler.schedule(this::tick, periodMillis());
		}
		report();
	}

	void pause() {
		stop("");
		report();
	}

	/** One time point on or back, paused. Wraps only when looping. */
	void step(int towards) {
		if (closed) return;
		stop("");
		int from = mode == Mode.COMPLETED ? host.requestedTime() : host.currentTime();
		int t = RawPlaybackSchedule.next(from, towards < 0 ? -1 : 1, host.timeCount(), loop);
		if (t < 0) {
			event(towards < 0 ? "At the first time point" : "At the last time point");
			return;
		}
		if (mode == Mode.COMPLETED) {
			asked = t;
			askedNanos = clock.getAsLong();
			host.requestFrame(t);
		} else host.showTime(t);
		report();
	}

	/** The user moved the time point by hand: playback pauses, and the session shows that time point. */
	void movedByHand() {
		if (playing) stop("Paused: the time point was moved by hand");
		report();
	}

	/** No more playback: the view is closing. */
	void close() {
		stop("");
		closed = true;
	}

	private void stop(String why) {
		playing = false;
		if (pending != null) pending.run();
		pending = null;
		if (!why.isEmpty()) lastEvent = why;
	}

	/* ---------------------------------------------------------------- completed frames */

	private void askCompleted(int t) {
		asked = t;
		askedNanos = clock.getAsLong();
		stats.asked++;
		host.requestFrame(t);
	}

	/**
	 * The session's latest navigation in completed mode has ended - presented, not shown, or
	 * unavailable before a frame could be made of it. Superseded navigations are not reported.
	 */
	void navigationEnded(int t, RawStrictPlayer.Result result, Throwable failure) {
		if (closed || mode != Mode.COMPLETED) return;
		boolean presented = result != null && result.outcome == RawStrictPlayer.Outcome.PRESENTED;
		if (presented && playing) stats.presented++;
		if (!playing || t != asked) {
			report();
			return;
		}
		if (presented) scheduleNext(t);
		else if (failure instanceof CancellationException) report();		// a newer navigation will report
		else if (failure != null || result.outcome == RawStrictPlayer.Outcome.CHANGED) {
			stats.skipped++;
			String why = failure != null ? failure.getMessage() : "its file changed";
			lastEvent = String.format(Locale.US, "Skipped time %d: %s", t + 1, why);
			scheduleNext(t);
		} else if (result.outcome == RawStrictPlayer.Outcome.REFUSED) {
			stop(String.format(Locale.US, "Paused: the cache budget cannot hold time %d", t + 1));
			report();
		} else if (result.outcome == RawStrictPlayer.Outcome.FAILED) {
			stop(String.format(Locale.US, "Paused: time %d failed: %s", t + 1, result.cause == null ? "" : result.cause.getMessage()));
			report();
		} else {		// superseded with nothing newer, as by a canvas resized in between: ask again
			askCompleted(t);
			report();
		}
	}

	private void scheduleNext(int t) {
		int next = RawPlaybackSchedule.next(t, direction, host.timeCount(), loop);
		if (next < 0) {
			stop("Stopped at the last time point");
			report();
			return;
		}
		long wait = RawPlaybackSchedule.waitMillis(askedNanos, clock.getAsLong(), fps);
		pending = scheduler.schedule(() -> {
			pending = null;
			if (playing && mode == Mode.COMPLETED && !closed) askCompleted(next);
		}, wait);
		report();
	}

	/* ---------------------------------------------------------------- progressive */

	private void tick() {
		pending = null;
		if (!playing || mode != Mode.PROGRESSIVE || closed) return;
		long placeholders = host.placeholdersHandedOut();
		if (placeholders != placeholdersSeen) stats.advancedWhileLoading++;
		placeholdersSeen = placeholders;
		int next = RawPlaybackSchedule.next(host.currentTime(), direction, host.timeCount(), loop);
		if (next < 0) {
			stop("Stopped at the last time point");
			report();
			return;
		}
		stats.advanced++;
		host.showTime(next);
		pending = scheduler.schedule(this::tick, periodMillis());
		report();
	}

	private long periodMillis() {
		return Math.max(1, Math.round(1000 / fps));
	}

	/* ---------------------------------------------------------------- what it says */

	private void event(String text) {
		lastEvent = text;
		report();
	}

	private void report() {
		if (closed) return;
		StringBuilder s = new StringBuilder();
		s.append(mode.label).append(playing ? ", playing" : ", paused");
		s.append(String.format(Locale.US, " at %.0f fps%s", fps, loop ? ", looping" : ""));
		if (mode == Mode.COMPLETED) {
			s.append(String.format(Locale.US, "; %.1f fps delivered, %d presented", host.presentedFps(), stats.presented));
			if (stats.skipped > 0) s.append(String.format(Locale.US, ", %d skipped", stats.skipped));
		} else if (stats.advanced > 0)
			s.append(String.format(Locale.US, "; %d moves, %d while cells were still loading", stats.advanced, stats.advancedWhileLoading));
		if (!lastEvent.isEmpty()) s.append(" - ").append(lastEvent);
		host.note(s.toString());
	}
}
