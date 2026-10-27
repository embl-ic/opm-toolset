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
 *     the time point at the target rate whatever has been drawn. As a loading diagnostic it counts
 *     the moves with placeholder activity since the previous move: the view handed out placeholders
 *     for cells not yet read while that time point was shown. That is activity, not a verdict: a
 *     frame can draw placeholders and still complete before it is left.</li>
 * <li><b>Completed frames</b> - each time point is asked for through the session's navigation
 *     entry and shown only once it is complete (joint decision D2). The next one is asked for
 *     only after the last has ended, and no sooner than the target rate allows: a slow frame slows
 *     playback, it is never skipped to keep the rate. A time point that cannot be shown - its file
 *     incomplete, failed or changed - is skipped, with a note and a count, never silently. A frame
 *     the budget cannot hold, or one that fails, pauses playback and says why.</li>
 * </ul>
 *
 * Stepping and moving the time point by hand pause playback, in either mode. Loop wraps at either
 * end; without it playback stops at the end. The view's own controls - its world-plane actions, its
 * resize - re-ask the time point being played, and playback carries on from it.
 *
 * <p>What a completed-frame run delivered is measured on playback's own clock (C9.1): each frame's
 * latency from being asked for to being presented, the rate frames were presented at, and how many
 * a prefetch had prepared. The counts and the rate cover the whole run; the latency median and 95th
 * percentile, given once the run stops, cover the last {@link Stats#LATENCY_WINDOW} presented frames,
 * so that a run looping for hours holds no more than that. A benchmark wanting a finite run's exact
 * percentiles records its own frames, as {@code RawPlaybackTraversalRealDataTest} does.
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
		/** Completed mode: prepare the cells of the frame that will be asked for next, if the budget allows. */
		void prefetch(int t);
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

	/**
	 * What playback has done since it was last started. The counts and the delivered rate are the
	 * whole run's; the latency percentiles are the last {@link #LATENCY_WINDOW} presented frames', in
	 * storage that does not grow however long a run loops.
	 */
	static final class Stats {
		/** Presented frames the latency percentiles are taken over: as many as the player's own stage window. */
		static final int LATENCY_WINDOW = RawStrictPlayer.STAGE_WINDOW;
		/** Completed mode: frames asked for, presented, and time points skipped as unavailable. */
		long asked, presented, skipped;
		/** Completed mode: presented frames whose cells a prefetch had prepared before they were asked for. */
		long prefetched;
		/**
		 * Progressive mode: moves made, and of those, the moves with placeholder activity since the
		 * previous move - the view handed out placeholder cells while the time point being left was shown.
		 */
		long advanced, movesWithPlaceholderActivity;
		/** Completed mode: the latest presented frames' latencies, asked for to presented, in nanoseconds; a ring. */
		private final long[] latencies = new long[LATENCY_WINDOW];
		/** When the first and the last frame of the run were presented. */
		private long firstPresented, lastPresented;

		void presented(long latencyNanos, long atNanos, boolean fromPrefetch) {
			latencies[(int) (presented % LATENCY_WINDOW)] = latencyNanos;
			if (presented == 0) firstPresented = atNanos;
			lastPresented = atNanos;
			presented++;
			if (fromPrefetch) prefetched++;
		}

		/** Frames presented per second, from the first presented to the last; 0 for fewer than two. */
		double deliveredFps() {
			return presented < 2 || lastPresented == firstPresented ? 0 : (presented - 1) * 1e9 / (lastPresented - firstPresented);
		}

		/** How many presented frames the latency percentiles cover: all of them, up to {@link #LATENCY_WINDOW}. */
		int latencySamples() {
			return (int) Math.min(presented, LATENCY_WINDOW);
		}

		/**
		 * The latency at this quantile over the last {@link #latencySamples()} presented frames, nearest
		 * rank, in milliseconds; 0 when nothing was presented. Its cost is that of sorting the window.
		 */
		double latencyMillis(double quantile) {
			int n = latencySamples();
			if (n == 0) return 0;
			long[] sorted = java.util.Arrays.copyOf(latencies, n);		// the ring's order does not matter to a quantile
			java.util.Arrays.sort(sorted);
			int rank = (int) Math.ceil(quantile * sorted.length);
			return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))] / 1e6;
		}
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
	/** Invalidates a timer even if its callback was already posted when it was cancelled. */
	private long timerGeneration;
	/** First presentation of this automatic time step; subsequent same-time paints are redraws. */
	private boolean askedPresented;
	/** Latest session navigation, including a paused step or manual seek, awaiting its first result. */
	private boolean navigationPending;
	private int navigationTime = -1;
	private long navigationNanos;
	/** Completed mode: the time point playback asked for last, and when; -1 for none. */
	private int asked = -1;
	private long askedNanos;
	/** When the frame asked for last, or the last progressive move, was due on the cadence of the target rate. */
	private long dueNanos;
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
	boolean hasScheduledAdvance() { return pending != null; }

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
			navigationPending = false;
			int t = host.currentTime();
			if (!was) {
				asked = t;
				askedNanos = clock.getAsLong();
				requestFrame(t);
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
			int from = host.requestedTime();
			dueNanos = clock.getAsLong();
			if (navigationPending && navigationTime == from) {
				// A paused step/seek has not arrived yet. Adopt it, without superseding its work.
				asked = from;
				askedNanos = navigationNanos;
				askedPresented = false;
				stats.asked++;
			} else {
				int t = RawPlaybackSchedule.next(from, direction, host.timeCount(), true);
				askCompleted(t < 0 ? from : t);
			}
		} else {
			placeholdersSeen = host.placeholdersHandedOut();
			dueNanos = clock.getAsLong();
			scheduleTick();
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
			requestFrame(t);
		} else host.showTime(t);
		report();
	}

	/**
	 * The user moved the time point by hand, in either mode: playback pauses, and the session shows
	 * that time point. The session reports only the user's moves, never its own.
	 */
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
		cancelAdvance();
		if (!why.isEmpty()) lastEvent = why;
	}

	private void cancelAdvance() {
		timerGeneration++;
		if (pending != null) pending.run();
		pending = null;
	}

	/** One owned timer. Cancellation and the token both matter for already posted callbacks. */
	private void scheduleAdvance(Runnable task, long delay) {
		cancelAdvance();
		final long token = timerGeneration;
		pending = scheduler.schedule(() -> {
			if (closed || token != timerGeneration) return;
			pending = null;
			timerGeneration++;
			task.run();
		}, delay);
	}

	/* ---------------------------------------------------------------- completed frames */

	private void askCompleted(int t) {
		asked = t;
		askedNanos = clock.getAsLong();
		askedPresented = false;
		stats.asked++;
		requestFrame(t);
	}

	private void requestFrame(int t) {
		navigationStarted(t);
		host.requestFrame(t);
	}

	/** The session also reports manual navigation and view reissues through this entry, on the EDT. */
	void navigationStarted(int t) {
		if (!navigationPending || navigationTime != t) navigationNanos = clock.getAsLong();
		navigationTime = t;
		navigationPending = true;
	}

	/** A coalesced view change refreshes speculative cells without moving the existing cadence. */
	void viewChanged() {
		if (!closed && playing && mode == Mode.COMPLETED && askedPresented && pending != null) {
			int next = RawPlaybackSchedule.next(asked, direction, host.timeCount(), loop);
			if (next >= 0) host.prefetch(next);
		}
	}

	/**
	 * The session's latest navigation in completed mode has ended - presented, not shown, or
	 * unavailable before a frame could be made of it. Superseded navigations are not reported.
	 */
	void navigationEnded(int t, RawStrictPlayer.Result result, Throwable failure) {
		if (closed || mode != Mode.COMPLETED) return;
		boolean presented = result != null && result.outcome == RawStrictPlayer.Outcome.PRESENTED;
		if (t == navigationTime && !(failure instanceof CancellationException)
				&& (result == null || result.outcome != RawStrictPlayer.Outcome.SUPERSEDED)) navigationPending = false;
		if (!playing || t != asked) {
			report();
			return;
		}
		if (presented) {
			if (askedPresented) {
				report(); // a redraw: no sample, prefetch or new advance
				return;
			}
			askedPresented = true;
			long now = clock.getAsLong();
			stats.presented(now - askedNanos, now, result.prefetched);
			scheduleNext(t);
		} else if (failure instanceof CancellationException) report();		// a newer navigation will report
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
			requestFrame(t); // retry this movie step, preserving its original ask and presentation count
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
		long now = clock.getAsLong();
		final long due = RawPlaybackSchedule.nextDue(dueNanos, now, fps);
		host.prefetch(next);		// its cells are read while this frame is shown
		scheduleAdvance(() -> {
			if (playing && mode == Mode.COMPLETED && !closed) {
				dueNanos = due;
				askCompleted(next);
			}
		}, RawPlaybackSchedule.waitMillis(due, now));
		report();
	}

	/* ---------------------------------------------------------------- progressive */

	private void tick() {
		pending = null;
		if (!playing || mode != Mode.PROGRESSIVE || closed) return;
		int next = RawPlaybackSchedule.next(host.currentTime(), direction, host.timeCount(), loop);
		if (next < 0) {		// the end is not a move, whatever the view drew meanwhile
			stop("Stopped at the last time point");
			report();
			return;
		}
		long placeholders = host.placeholdersHandedOut();
		if (placeholders != placeholdersSeen) stats.movesWithPlaceholderActivity++;
		placeholdersSeen = placeholders;
		stats.advanced++;
		host.showTime(next);
		scheduleTick();
		report();
	}

	/** The next progressive move, on the cadence of the target rate. */
	private void scheduleTick() {
		long now = clock.getAsLong();
		final long due = RawPlaybackSchedule.nextDue(dueNanos, now, fps);
		scheduleAdvance(() -> {
			dueNanos = due;
			tick();
		}, RawPlaybackSchedule.waitMillis(due, now));
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
			if (playing || stats.presented < 2)
				s.append(String.format(Locale.US, "; %.1f fps delivered, %d presented", stats.deliveredFps(), stats.presented));
			else		// the run as a whole once it has stopped; its latency over the window
				s.append(String.format(Locale.US, "; %d presented at %.1f fps, latency median %.0f ms, 95%% %.0f ms over the last %d",
						stats.presented, stats.deliveredFps(), stats.latencyMillis(0.5), stats.latencyMillis(0.95), stats.latencySamples()));
			if (stats.skipped > 0) s.append(String.format(Locale.US, ", %d skipped", stats.skipped));
		} else if (stats.advanced > 0)
			s.append(String.format(Locale.US, "; %d moves, %d with placeholder activity since the previous move", stats.advanced,
					stats.movesWithPlaceholderActivity));
		if (!lastEvent.isEmpty()) s.append(" - ").append(lastEvent);
		host.note(s.toString());
	}
}
