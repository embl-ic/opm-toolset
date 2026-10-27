package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/**
 * Playback's own rules, without a view (joint contract C6.1; INTERFACES §4): completed mode asks
 * for each time point in turn, only after the last has ended and no faster than the target rate,
 * never skipping one to keep the rate; an unavailable time point is skipped with a note; a refused
 * or failed frame pauses; progressive mode moves at the rate and counts what it left incomplete.
 */
public class RawPlaybackTest {

	/** B's B4 probe, ported: a run's latency storage stops growing once its window is full. */
	@Test
	public void liveLatencyStorageIsBoundedByItsWindow() throws Exception {
		RawPlayback.Stats stats = new RawPlayback.Stats();
		java.lang.reflect.Field field = RawPlayback.Stats.class.getDeclaredField("latencies");
		field.setAccessible(true);
		for (int i = 0; i < 65536; i++) stats.presented(i, i + 1, false);
		int before = ((long[]) field.get(stats)).length;
		for (int i = 65536; i < 131072; i++) stats.presented(i, i + 1, false);
		assertEquals("storage plateaus", before, ((long[]) field.get(stats)).length);
		assertEquals("the counts stay cumulative", 131072, stats.presented);
	}

	/** B's B5 probe, ported: the tick that finds the end is not a move, whatever the view drew meanwhile. */
	@Test
	public void theTickThatFindsTheEndIsNotAMove() {
		host.current = 3;		// five time points: one move left
		playback.play();
		host.placeholders++;
		waitOut();		// to 4
		host.placeholders++;
		waitOut();		// the end: no move
		assertFalse(playback.isPlaying());
		assertEquals(1, playback.stats().advanced);
		assertEquals("only the real move, which left a time point with placeholder activity", 1,
				playback.stats().movesWithPlaceholderActivity);
	}

	/** Acceptance item 4: the counter never exceeds the moves, with one time point, with or without looping. */
	@Test
	public void withOneTimePointTheCounterNeverExceedsTheMoves() {
		for (boolean loop : new boolean[] { false, true }) {
			FakeHost one = new FakeHost(1);
			ManualScheduler timers = new ManualScheduler();
			RawPlayback p = new RawPlayback(one, timers, nanos::get);
			p.setLoop(loop);
			p.play();
			for (int i = 0; i < 5 && timers.waiting() > 0; i++) {
				one.placeholders++;
				nanos.addAndGet(100000000L);
				timers.runNext();
			}
			assertTrue(loop + ": " + p.stats().movesWithPlaceholderActivity + " of " + p.stats().advanced,
					p.stats().movesWithPlaceholderActivity <= p.stats().advanced);
			if (!loop) {
				assertFalse("nowhere to go: stopped at once", p.isPlaying());
				assertEquals(0, p.stats().advanced);
				assertEquals(0, p.stats().movesWithPlaceholderActivity);
			}
		}
	}

	/** Acceptance item 4: looping, the counter counts each move whose interval had activity, and never the end. */
	@Test
	public void loopingTheCounterCountsOnlyMovesWithActivity() {
		playback.setLoop(true);
		playback.play();
		for (int i = 0; i < 12; i++) {
			if (i % 3 == 0) host.placeholders++;		// activity before every third move
			waitOut();
		}
		assertEquals(12, playback.stats().advanced);
		assertEquals(4, playback.stats().movesWithPlaceholderActivity);
		assertTrue(playback.stats().movesWithPlaceholderActivity <= playback.stats().advanced);
	}

	/**
	 * Acceptance item 4, and how to read the counter: placeholders handed out while a time point was
	 * shown count its move as one with activity even if every cell arrived and the frame completed
	 * before the move. The counter is a loading diagnostic, not a count of frames left incomplete.
	 */
	@Test
	public void activityFollowedByCompletionBeforeTheMoveStillCounts() {
		playback.play();
		host.placeholders += 5;		// the time point shown draws placeholders; then its cells all arrive
		// ... and the view repaints complete, handing out no more placeholders, before the move
		waitOut();
		assertEquals(1, playback.stats().advanced);
		assertEquals("activity since the previous move, not incompleteness at departure", 1,
				playback.stats().movesWithPlaceholderActivity);
		waitOut();
		assertEquals("no activity in that interval", 1, playback.stats().movesWithPlaceholderActivity);
	}

	/**
	 * Acceptance item 3: 200,000 presented frames on a fake clock. Latency storage stays at its window
	 * once full, the percentiles cover exactly the last window of frames, the counts and the rate are
	 * exact for the whole run, the report on pause sorts the window only, and Play starts afresh.
	 */
	@Test
	public void twoHundredThousandFramesKeepTelemetryConstantAndTotalsExact() throws Exception {
		final int frames = 200000, count = 50, window = RawPlayback.Stats.LATENCY_WINDOW;
		final int[] asked = { -1 };
		final Runnable[] timer = { null };
		final String[] note = { "" };
		RawPlayback.Host lean = new RawPlayback.Host() {		// keeps nothing per frame
			@Override public int timeCount() { return count; }
			@Override public int currentTime() { return Math.max(0, asked[0]); }
			@Override public int requestedTime() { return Math.max(0, asked[0]); }
			@Override public void showTime(int t) {}
			@Override public CompletableFuture<RawStrictPlayer.Result> requestFrame(int t) { asked[0] = t; return null; }
			@Override public void prefetch(int t) {}
			@Override public boolean startCompleted() { return true; }
			@Override public void stopCompleted() {}
			@Override public long placeholdersHandedOut() { return 0; }
			@Override public double presentedFps() { return 0; }
			@Override public void note(String text) { note[0] = text; }
		};
		RawPlayback.Scheduler one = (task, delay) -> {
			timer[0] = task;
			return () -> { if (timer[0] == task) timer[0] = null; };
		};
		AtomicLong clock = new AtomicLong(1000000000L);
		RawPlayback p = new RawPlayback(lean, one, clock::get);
		RawStrictPlayer.Result presented = result(RawStrictPlayer.Outcome.PRESENTED);
		p.setMode(RawPlayback.Mode.COMPLETED);
		clock.addAndGet(1000000L);
		p.navigationEnded(asked[0], presented, null);
		p.setLoop(true);
		p.setTargetFps(1000);		// a period of 1 ms, shorter than every latency: asked as soon as presented
		p.play();
		java.lang.reflect.Field field = RawPlayback.Stats.class.getDeclaredField("latencies");
		field.setAccessible(true);
		long firstAt = 0, lastAt = 0;
		long[] last = new long[window];		// the latencies of the last window of frames, in ms
		long pauseAtThousand = 0;
		for (int i = 0; i < frames; i++) {
			long latency = 2 + (i * 7919L) % 97;		// 2 to 98 ms, in no order
			clock.addAndGet(latency * 1000000L);
			p.navigationEnded(asked[0], presented, null);
			if (i == 0) firstAt = clock.get();
			lastAt = clock.get();
			last[i % window] = latency;
			if (i == window) assertEquals("full", window, ((long[]) field.get(p.stats())).length);
			if (i == 999) {		// the cost of a report at a thousand frames, for comparison
				long t0 = System.nanoTime();
				p.stats().latencyMillis(0.95);
				pauseAtThousand = System.nanoTime() - t0;
			}
			Runnable next = timer[0];
			assertTrue("one advance scheduled after each frame", next != null);
			timer[0] = null;
			next.run();
		}
		RawPlayback.Stats s = p.stats();
		assertEquals("storage constant once the window filled", window, ((long[]) field.get(s)).length);
		assertEquals(frames, s.presented);
		assertEquals(frames + 1, s.asked);		// the last frame asked for is still to come
		assertEquals("the whole run's rate", (frames - 1) * 1e9 / (lastAt - firstAt), s.deliveredFps(), 1e-9);
		assertEquals("the percentiles cover the window", window, s.latencySamples());
		long[] sorted = last.clone();
		java.util.Arrays.sort(sorted);
		assertEquals(sorted[(int) Math.ceil(0.5 * window) - 1], s.latencyMillis(0.5), 1e-9);
		assertEquals(sorted[(int) Math.ceil(0.95 * window) - 1], s.latencyMillis(0.95), 1e-9);
		long t0 = System.nanoTime();
		p.pause();		// the report on pause sorts the window, not the run
		long pauseAtEnd = System.nanoTime() - t0;
		System.out.printf("report at 1,000 frames %.3f ms, pause at %,d frames %.3f ms%n", pauseAtThousand / 1e6, frames, pauseAtEnd / 1e6);
		assertTrue(note[0], note[0].contains(frames + " presented"));
		assertTrue(note[0], note[0].contains("over the last " + window));
		p.play();
		assertEquals("Play starts a new run", 0, p.stats().presented);
		assertEquals(0, p.stats().latencySamples());
		assertEquals(0, p.stats().latencyMillis(0.5), 0);
	}

	@Test
	public void aRedrawWhileWaitingLeavesOneAutomaticTimer() {
		enterCompleted();
		playback.play();
		end(RawStrictPlayer.Outcome.PRESENTED, 20);
		assertEquals(1, scheduler.waiting());
		host.requestFrame(1); // the session reissues the current time after a view change
		end(RawStrictPlayer.Outcome.PRESENTED, 10);
		assertEquals("a redraw cannot start another movie chain", 1, scheduler.waiting());
	}

	@Test
	public void aPresentedTimesRedrawIsNotAnotherMovieFrame() {
		enterCompleted();
		playback.play();
		end(RawStrictPlayer.Outcome.PRESENTED, 20);
		long prefetched = host.prefetched.size();
		host.requestFrame(1);
		end(RawStrictPlayer.Outcome.PRESENTED, 70);
		assertEquals("N1: count the movie time once", 1, playback.stats().presented);
		assertEquals("N1: no redraw latency sample", 20, playback.stats().latencyMillis(1), 0);
		assertEquals("N1: no redundant prefetch on presentation", prefetched, host.prefetched.size());
		assertTrue("one movie presentation has no measured interval: " + host.note, host.note.contains("0.0 fps delivered, 1 presented"));
	}

	@Test
	public void playAdoptsAPendingSteppedFrameBeforeAdvancing() {
		enterCompleted();
		playback.step(1);
		assertEquals(Arrays.asList(0, 1), host.asked);
		playback.play();
		assertEquals("N2: do not supersede the pending step with the next time", Arrays.asList(0, 1), host.asked);
		end(RawStrictPlayer.Outcome.PRESENTED, 30);
		assertEquals(1, playback.stats().presented);
		waitOut();
		assertEquals(Arrays.asList(0, 1, 2), host.asked);
	}

	@Test
	public void aRedrawBeforeFirstPresentationKeepsTheOriginalAskAndCountsOnce() {
		enterCompleted();
		playback.play();
		nanos.addAndGet(30000000);
		playback.navigationStarted(1);
		host.requestFrame(1);
		playback.viewChanged();
		end(RawStrictPlayer.Outcome.PRESENTED, 50);
		assertEquals(1, playback.stats().presented);
		assertEquals(80, playback.stats().latencyMillis(1), 0);
		assertEquals(1, scheduler.waiting());
		assertEquals(Arrays.asList(2), host.prefetched);
	}

	@Test
	public void viewChangesRefreshPrefetchWithoutMovingTheCadenceOrCountingRedraws() {
		enterCompleted();
		playback.play();
		end(RawStrictPlayer.Outcome.PRESENTED, 20);
		long delay = scheduler.lastDelay();
		for (int i = 0; i < 5; i++) {
			playback.navigationStarted(1);
			playback.viewChanged(); // world actions and resize use this same path
			host.requestFrame(1);
			end(RawStrictPlayer.Outcome.PRESENTED, 5);
			assertEquals(1, scheduler.waiting());
			assertEquals(delay, scheduler.lastDelay());
			assertEquals(1, playback.stats().presented);
		}
		assertEquals(6, host.prefetched.size()); // first presentation, then five changed views
		waitOut();
		assertEquals(2, (int) host.asked.get(host.asked.size() - 1));
		end(RawStrictPlayer.Outcome.PRESENTED, 10);
		assertEquals(2, playback.stats().presented);
		assertEquals(20, playback.stats().latencyMillis(1), 0);
	}

	@Test
	public void cancelledCallbacksCannotAdvanceAfterPausePlayModeSwitchOrClose() {
		enterCompleted();
		playback.play();
		end(RawStrictPlayer.Outcome.PRESENTED, 20);
		Runnable old = scheduler.tasks.get(scheduler.tasks.size() - 1);
		playback.pause();
		assertEquals(0, scheduler.waiting()); // actual timer cancellation, not only a callback guard
		playback.play();
		List<Integer> asks = new ArrayList<Integer>(host.asked);
		old.run(); // cancellation cannot retract a callback already posted to Swing
		assertEquals(asks, host.asked);
		end(RawStrictPlayer.Outcome.PRESENTED, 20);
		old = scheduler.tasks.get(scheduler.tasks.size() - 1);
		playback.setMode(RawPlayback.Mode.PROGRESSIVE);
		assertEquals(1, scheduler.waiting());
		playback.setMode(RawPlayback.Mode.COMPLETED);
		asks = new ArrayList<Integer>(host.asked);
		old.run();
		assertEquals(asks, host.asked);
		end(RawStrictPlayer.Outcome.PRESENTED, 20);
		old = scheduler.tasks.get(scheduler.tasks.size() - 1);
		playback.close();
		assertEquals(0, scheduler.waiting());
		old.run();
		assertEquals(asks, host.asked);
	}

	/** A view with {@code count} time points, its requests answered by the test. */
	private static final class FakeHost implements RawPlayback.Host {
		final int count;
		int current, requested;
		boolean completed;
		final List<Integer> asked = new ArrayList<Integer>(), shown = new ArrayList<Integer>(), prefetched = new ArrayList<Integer>();
		final List<CompletableFuture<RawStrictPlayer.Result>> futures = new ArrayList<CompletableFuture<RawStrictPlayer.Result>>();
		long placeholders;
		String note = "";

		FakeHost(int count) {
			this.count = count;
		}

		@Override public int timeCount() { return count; }
		@Override public int currentTime() { return current; }
		@Override public int requestedTime() { return requested; }
		@Override public void showTime(int t) { current = t; shown.add(t); }
		@Override public CompletableFuture<RawStrictPlayer.Result> requestFrame(int t) {
			requested = t;
			asked.add(t);
			CompletableFuture<RawStrictPlayer.Result> f = new CompletableFuture<RawStrictPlayer.Result>();
			futures.add(f);
			return f;
		}
		@Override public void prefetch(int t) { prefetched.add(t); }
		@Override public boolean startCompleted() { completed = true; return true; }
		@Override public void stopCompleted() { completed = false; }
		@Override public long placeholdersHandedOut() { return placeholders; }
		@Override public double presentedFps() { return 99; } // canvas paints may include redraws, not movie delivery
		@Override public void note(String text) { note = text; }
	}

	/** Tasks run when the test says, with the delay they asked for. */
	private static final class ManualScheduler implements RawPlayback.Scheduler {
		final List<Runnable> tasks = new ArrayList<Runnable>();
		final List<Long> delays = new ArrayList<Long>();
		final List<boolean[]> cancelled = new ArrayList<boolean[]>();

		@Override public Runnable schedule(Runnable task, long delayMillis) {
			final boolean[] off = { false };
			tasks.add(task);
			delays.add(delayMillis);
			cancelled.add(off);
			return () -> off[0] = true;
		}

		int waiting() {
			int n = 0;
			for (int i = 0; i < tasks.size(); i++) if (!cancelled.get(i)[0] && tasks.get(i) != null) n++;
			return n;
		}

		long lastDelay() {
			return delays.get(delays.size() - 1);
		}

		/** Run the oldest task still waiting. */
		void runNext() {
			for (int i = 0; i < tasks.size(); i++)
				if (tasks.get(i) != null && !cancelled.get(i)[0]) {
					Runnable task = tasks.get(i);
					tasks.set(i, null);
					task.run();
					return;
				}
			throw new AssertionError("nothing scheduled");
		}
	}

	private final AtomicLong nanos = new AtomicLong(1000000000L);
	private final FakeHost host = new FakeHost(5);
	private final ManualScheduler scheduler = new ManualScheduler();
	private final RawPlayback playback = new RawPlayback(host, scheduler, nanos::get);

	private static RawStrictPlayer.Result result(RawStrictPlayer.Outcome outcome) {
		return RawStrictPlayer.Result.ofOutcomeForTest(outcome, null);
	}

	/** End the last frame asked for: the session reports it, as on the event thread. */
	private void end(RawStrictPlayer.Outcome outcome, long afterMillis) {
		nanos.addAndGet(afterMillis * 1000000L);
		int t = host.asked.get(host.asked.size() - 1);
		playback.navigationEnded(t, result(outcome), null);
	}

	private void enterCompleted() {
		playback.setMode(RawPlayback.Mode.COMPLETED);
		assertTrue(host.completed);
		assertEquals("entering shows the current time point complete", Arrays.asList(0), host.asked);
		end(RawStrictPlayer.Outcome.PRESENTED, 30);
	}

	@Test
	public void completedFramesAreAskedForInTurnAfterEachHasEndedAndNoFasterThanTheRate() {
		enterCompleted();
		playback.setTargetFps(10);
		playback.play();
		assertEquals("from the frame shown on", Arrays.asList(0, 1), host.asked);
		assertEquals("one frame at a time", 0, scheduler.waiting());
		end(RawStrictPlayer.Outcome.PRESENTED, 20);		// fast: wait out the rest of 100 ms
		assertEquals(Arrays.asList(0, 1), host.asked);
		assertEquals("the next frame's cells are prepared while this one is shown", Arrays.asList(2), host.prefetched);
		assertEquals(80, scheduler.lastDelay());
		scheduler.runNext();
		assertEquals(Arrays.asList(0, 1, 2), host.asked);
		end(RawStrictPlayer.Outcome.PRESENTED, 450);		// slow: no wait, and no skip
		assertEquals(0, scheduler.lastDelay());
		scheduler.runNext();
		assertEquals("a slow frame slows playback, it is never skipped", Arrays.asList(0, 1, 2, 3), host.asked);
		assertEquals(2, playback.stats().presented);
		assertEquals(0, playback.stats().skipped);
	}

	/** Wait out what playback scheduled, on its clock, and run it. */
	private void waitOut() {
		nanos.addAndGet(scheduler.lastDelay() * 1000000L);
		scheduler.runNext();
	}

	/**
	 * A run measures each frame from being asked for to being presented, and the rate frames were
	 * presented at, on playback's own clock; once it stops, the status line gives the median and the
	 * 95th percentile. The frame shown on entering the mode is not part of the run.
	 */
	@Test
	public void aRunReportsItsLatencyAndDeliveredRateWhenItStops() {
		enterCompleted();
		playback.setTargetFps(10);
		playback.play();
		end(RawStrictPlayer.Outcome.PRESENTED, 40);		// asked at 0, presented at 40 ms
		waitOut();		// asked at 100
		end(RawStrictPlayer.Outcome.PRESENTED, 120);		// presented at 220
		waitOut();		// asked at once
		end(RawStrictPlayer.Outcome.PRESENTED, 50);		// presented at 270
		assertTrue("while playing, the live rate: " + host.note, host.note.contains("fps delivered, 3 presented"));
		waitOut();		// asked at 320
		end(RawStrictPlayer.Outcome.PRESENTED, 200);		// presented at 520: the last time point
		assertFalse(playback.isPlaying());
		RawPlayback.Stats s = playback.stats();
		assertEquals(4, s.presented);
		assertEquals(0, s.prefetched);
		assertEquals("three intervals over 480 ms", 6.25, s.deliveredFps(), 1e-9);
		assertEquals("median of 40, 50, 120, 200", 50, s.latencyMillis(0.5), 1e-9);
		assertEquals(200, s.latencyMillis(0.95), 1e-9);
		assertEquals(40, s.latencyMillis(0), 1e-9);
		assertTrue(host.note, host.note.contains("4 presented at 6.3 fps, latency median 50 ms, 95% 200 ms"));
		assertTrue(host.note, host.note.contains("Stopped at the last time point"));
		playback.play();		// a new run starts its own measurement
		assertEquals(0, playback.stats().presented);
		assertEquals(0, playback.stats().deliveredFps(), 0);
		assertEquals(0, playback.stats().latencyMillis(0.5), 0);
	}

	/**
	 * Frames are asked for on a cadence: a timer that fired late is made up on the next frame instead
	 * of slowing every one, and a frame slower than the period restarts the cadence without a burst.
	 */
	@Test
	public void aLateTimerIsMadeUpOnTheNextFrameAndASlowFrameRestartsTheCadence() {
		enterCompleted();
		playback.setTargetFps(10);
		playback.setLoop(true);		// five time points: on past the last
		playback.play();		// time 1 asked at 0, due at 0
		end(RawStrictPlayer.Outcome.PRESENTED, 30);
		assertEquals(70, scheduler.lastDelay());
		nanos.addAndGet(85 * 1000000L);		// the timer fires 15 ms late
		scheduler.runNext();		// time 2 asked at 115, due at 100
		end(RawStrictPlayer.Outcome.PRESENTED, 30);		// presented at 145
		assertEquals("due at 200, not 215", 55, scheduler.lastDelay());
		assertEquals("latency from when it was asked for, not when it was due", 30, playback.stats().latencyMillis(1), 1e-9);
		waitOut();		// time 3 asked at 200
		end(RawStrictPlayer.Outcome.PRESENTED, 250);		// presented at 450, past its 300
		assertEquals("asked for at once", 0, scheduler.lastDelay());
		scheduler.runNext();		// time 4 asked at 450
		end(RawStrictPlayer.Outcome.PRESENTED, 10);		// presented at 460
		assertEquals("a period after 450: no burst to catch up", 90, scheduler.lastDelay());
	}

	@Test
	public void progressiveMovesKeepTheirCadenceWhenATimerFiresLate() {
		playback.setTargetFps(20);
		playback.play();
		assertEquals(50, scheduler.lastDelay());
		nanos.addAndGet(60 * 1000000L);		// 10 ms late
		scheduler.runNext();
		assertEquals("due at 100", 40, scheduler.lastDelay());
	}

	@Test
	public void anUnavailableTimePointIsSkippedWithANoteAndACount() {
		enterCompleted();
		playback.play();
		nanos.addAndGet(5000000L);
		playback.navigationEnded(1, null, new IllegalStateException("Time 2 unavailable: incomplete, 4 of 9 planes"));
		assertTrue(host.note, host.note.contains("Skipped time 2"));
		assertEquals(1, playback.stats().skipped);
		scheduler.runNext();
		assertEquals(Arrays.asList(0, 1, 2), host.asked);
		end(RawStrictPlayer.Outcome.CHANGED, 5);
		assertEquals(2, playback.stats().skipped);
		scheduler.runNext();
		assertEquals(Arrays.asList(0, 1, 2, 3), host.asked);
	}

	@Test
	public void aRefusedOrFailedFramePausesAndSaysWhy() {
		enterCompleted();
		playback.play();
		end(RawStrictPlayer.Outcome.REFUSED, 5);
		assertFalse(playback.isPlaying());
		assertTrue(host.note, host.note.contains("cache budget cannot hold time 2"));
		assertEquals(0, scheduler.waiting());
		playback.play();
		end(RawStrictPlayer.Outcome.FAILED, 5);
		assertFalse(playback.isPlaying());
		assertTrue(host.note, host.note.contains("failed"));
	}

	@Test
	public void theEndStopsPlaybackUnlessItLoops() {
		enterCompleted();
		host.requested = 3;
		playback.play();
		assertEquals(4, (int) host.asked.get(host.asked.size() - 1));
		end(RawStrictPlayer.Outcome.PRESENTED, 200);
		assertFalse(playback.isPlaying());
		assertTrue(host.note, host.note.contains("last time point"));
		playback.setLoop(true);
		playback.play();
		assertEquals("wraps to the first", 0, (int) host.asked.get(host.asked.size() - 1));
	}

	@Test
	public void supersededAndCancelledNavigationsAreNotTakenForTheEnd() {
		enterCompleted();
		playback.play();
		playback.navigationEnded(3, result(RawStrictPlayer.Outcome.PRESENTED), null);		// not the one playback asked for
		assertEquals(0, scheduler.waiting());
		playback.navigationEnded(1, null, new CancellationException("superseded"));
		assertEquals(0, scheduler.waiting());
		assertTrue(playback.isPlaying());
		end(RawStrictPlayer.Outcome.PRESENTED, 200);
		assertEquals(1, scheduler.waiting());
	}

	@Test
	public void steppingAndMovingByHandPause() {
		enterCompleted();
		playback.play();
		playback.step(1);
		assertFalse(playback.isPlaying());
		assertEquals("one on from the time asked for", 2, (int) host.asked.get(host.asked.size() - 1));
		playback.step(-1);
		assertEquals(1, (int) host.asked.get(host.asked.size() - 1));
		host.requested = 0;
		playback.step(-1);
		assertTrue("no wrap without looping: " + host.note, host.note.contains("first time point"));
		playback.play();
		playback.movedByHand();
		assertFalse(playback.isPlaying());
		assertTrue(host.note, host.note.contains("moved by hand"));
	}

	@Test
	public void progressiveModeMovesAtTheRateAndCountsMovesLeftIncomplete() {
		playback.setTargetFps(20);
		playback.play();
		assertEquals(50, scheduler.lastDelay());
		scheduler.runNext();
		assertEquals(Arrays.asList(1), host.shown);
		host.placeholders = 7;		// the view drew placeholders meanwhile
		scheduler.runNext();
		assertEquals(Arrays.asList(1, 2), host.shown);
		scheduler.runNext();
		assertEquals(3, playback.stats().advanced);
		assertEquals("one move with placeholder activity since the previous move", 1, playback.stats().movesWithPlaceholderActivity);
		assertTrue(host.note, host.note.contains("3 moves, 1 with placeholder activity since the previous move"));
		scheduler.runNext();
		scheduler.runNext();		// at 4: the end
		assertFalse(playback.isPlaying());
		assertEquals(Arrays.asList(1, 2, 3, 4), host.shown);
		assertTrue(host.asked.isEmpty());
	}

	@Test
	public void switchingModesWhilePlayingCarriesOn() {
		playback.play();
		playback.setMode(RawPlayback.Mode.COMPLETED);
		assertTrue(playback.isPlaying());
		assertTrue(host.completed);
		assertEquals("completed playback asks for the next time point", Arrays.asList(1), host.asked);
		end(RawStrictPlayer.Outcome.PRESENTED, 200);
		playback.setMode(RawPlayback.Mode.PROGRESSIVE);
		assertFalse(host.completed);
		assertTrue(playback.isPlaying());
		scheduler.runNext();		// the completed step was cancelled; the progressive tick runs
		assertFalse(host.shown.isEmpty());
	}

	@Test
	public void closingCancelsWhatWaitsAndIgnoresLateReports() {
		enterCompleted();
		playback.play();
		end(RawStrictPlayer.Outcome.PRESENTED, 10);
		assertEquals(1, scheduler.waiting());
		playback.close();
		assertEquals(0, scheduler.waiting());
		int asked = host.asked.size();
		playback.navigationEnded(2, result(RawStrictPlayer.Outcome.PRESENTED), null);
		playback.play();
		assertEquals(asked, host.asked.size());
	}
}
