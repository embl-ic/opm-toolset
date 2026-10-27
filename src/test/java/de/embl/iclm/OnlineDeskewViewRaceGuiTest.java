package de.embl.iclm;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.viewer.SynchronizedViewerState;
import bdv.viewer.ViewerPanel;
import net.imglib2.realtransform.AffineTransform3D;

/** Completed-view regressions for off-thread edits, deferred redraws and raced presentations. */
public class OnlineDeskewViewRaceGuiTest {
	@Rule public TemporaryFolder folder = new TemporaryFolder();
	private OnlineDeskewSession session;
	private final List<CountDownLatch> releases = Collections.synchronizedList(new ArrayList<CountDownLatch>());
	private final List<Throwable> uncaught = Collections.synchronizedList(new ArrayList<Throwable>());
	private final List<String> edtReads = Collections.synchronizedList(new ArrayList<String>());
	private Thread.UncaughtExceptionHandler handler;

	@Before public void display() {
		Assume.assumeTrue(Boolean.getBoolean("opm.test.gui"));
		handler = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((thread, error) -> { uncaught.add(error); error.printStackTrace(); });
	}

	@After public void close() throws Exception {
		if (!Boolean.getBoolean("opm.test.gui")) return;
		try {
			for (CountDownLatch l : releases) l.countDown();
			RawFrameRenderer.rowHookForTest = null;
			RawStrictPlayer.beforeHandOverForTest = null;
			if (session != null) {
				edt(() -> { session.frame().dispose(); return null; });
				assertTrue(session.closed().get(15, TimeUnit.SECONDS).drained);
			}
		} finally { Thread.setDefaultUncaughtExceptionHandler(handler); }
		assertTrue("uncaught exceptions: " + uncaught, uncaught.isEmpty());
		assertTrue("raw reads on the event thread: " + edtReads, edtReads.isEmpty());
	}

	/* ---------------------------------------------------------------- fixture */

	private static <T> T edt(Callable<T> work) throws Exception {
		final Object[] result = new Object[1];
		final Throwable[] failure = new Throwable[1];
		SwingUtilities.invokeAndWait(() -> {
			try { result[0] = work.call(); } catch (Throwable t) { failure[0] = t; }
		});
		if (failure[0] != null) throw new AssertionError(failure[0]);
		@SuppressWarnings("unchecked") T out = (T) result[0];
		return out;
	}

	private void open(int timepoints) throws Exception {
		for (int t = 1; t <= timepoints; t++)
			RawTiffFixture.write(new File(folder.getRoot(), String.format("v_Position0001_Time%06d_Channel0001_Frames_1_20.tiff", t)),
					64, 40, 20, 8, 20, (x, y, k) -> 600 + x * 7 + y * 13 + k * 29);
		try (FileWriter out = new FileWriter(new File(folder.getRoot(), "ExperimentalParameters.txt"))) {
			out.write("Interval: 5 Minute\nTilt angle: 33.5\num per galvo DU: 0.01325\n"
					+ "Galvo DUs per step: 20\nPixel size at object /um: 0.116\n"
					+ "Camera ROI X0, X1, Y0, Y1: 0 64 0 40\n");
		}
		OnlineDeskewView.Settings settings = new OnlineDeskewView.Settings();
		settings.folder = folder.getRoot().getAbsolutePath();
		settings.budgetMb = 64;
		session = OnlineDeskewSession.open(settings);
		edt(() -> { session.frame().setSize(500, 400); return null; });
		edt(() -> null); edt(() -> null);
		readHook(0);
	}

	/** Every raw read off the event thread, optionally slowed. */
	private void readHook(long sleepMillis) {
		session.store().setReadHook(cell -> {
			if (SwingUtilities.isEventDispatchThread()) edtReads.add(cell);
			if (sleepMillis > 0) try { Thread.sleep(sleepMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
		});
	}

	private RawFrameRequest completed() throws Exception {
		edt(() -> { session.playback().setMode(RawPlayback.Mode.COMPLETED); return null; });
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, session.navigationResult().get(10, TimeUnit.SECONDS).outcome);
		return edt(() -> session.presenter().shownRequest());
	}

	private static void until(String what, long seconds, BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
		assertTrue(what, condition.getAsBoolean());
	}

	private static void await(CountDownLatch latch) {
		try { assertTrue("latch deadline", latch.await(15, TimeUnit.SECONDS)); }
		catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
	}

	/** Holds the first render that starts from now on, in its first row, until the returned latch is released. */
	private CountDownLatch holdNextRender(CountDownLatch entered) {
		CountDownLatch release = new CountDownLatch(1);
		releases.add(release);
		AtomicBoolean first = new AtomicBoolean(true);
		RawFrameRenderer.rowHookForTest = row -> { if (first.compareAndSet(true, false)) { entered.countDown(); await(release); } };
		return release;
	}

	private ViewerPanel viewer() { return session.handle().getViewerPanel(); }

	private static AffineTransform3D panned(AffineTransform3D view, double dx) {
		AffineTransform3D t = view.copy();
		t.set(t.get(0, 3) + dx, 0, 3);
		return t;
	}

	/** The fields a completed frame samples, compared without the production helper. */
	private static void assertCapture(String what, RawFrameRequest expected, RawFrameRequest shown) {
		assertNotNull(what + ": nothing shown", shown);
		assertEquals(what, expected.timeIndex, shown.timeIndex);
		assertEquals(what, expected.key, shown.key);
		assertEquals(what, expected.width, shown.width);
		assertEquals(what, expected.height, shown.height);
		assertArrayEquals(what, expected.viewer.getRowPackedCopy(), shown.viewer.getRowPackedCopy(), 0);
		assertEquals(what, expected.interpolation, shown.interpolation);
		assertEquals(what, expected.layers.size(), shown.layers.size());
		for (int i = 0; i < expected.layers.size(); i++) {
			assertSame(what, expected.layers.get(i).spec, shown.layers.get(i).spec);
			assertArrayEquals(what, expected.layers.get(i).sourceToWorld.getRowPackedCopy(), shown.layers.get(i).sourceToWorld.getRowPackedCopy(), 0);
			assertEquals(what, expected.layers.get(i).min, shown.layers.get(i).min, 0);
			assertEquals(what, expected.layers.get(i).max, shown.layers.get(i).max, 0);
			assertEquals(what, expected.layers.get(i).argb, shown.layers.get(i).argb);
		}
	}

	private long outcomes() {
		long n = 0;
		for (RawStrictPlayer.Outcome o : RawStrictPlayer.Outcome.values()) n += session.player().count(o);
		return n;
	}

	private static Object field(Object object, String name) throws Exception {
		Field f = object.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(object);
	}

	/** Commit order of every frame painted from now on. */
	private List<Integer> recordCommits() {
		List<Integer> times = Collections.synchronizedList(new ArrayList<Integer>());
		session.presenter().setCommitObserver((r, image) -> times.add(r.timeIndex));
		return times;
	}

	/** A script edits from its own thread while the event thread runs freely; then a close lands in another such burst. */
	@Test public void aScriptThreadBurstStaysBoundedAndACloseDuringOneDetachesBothListeners() throws Exception {
		open(4);
		completed();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = holdNextRender(entered);
		CompletableFuture<RawStrictPlayer.Result> held = edt(() -> session.requestStrictFrame(1));
		await(entered);
		long before = outcomes();
		Thread script = new Thread(() -> {
			SynchronizedViewerState state = viewer().state();
			for (int i = 0; i < 100; i++) { state.setViewerTransform(panned(state.getViewerTransform(), 2)); Thread.yield(); }
		}, "probe-script");
		script.start();
		script.join(10000);
		assertFalse(script.isAlive());
		edt(() -> null); edt(() -> null);
		CompletableFuture<RawStrictPlayer.Result> latest = edt(session::navigationResult);
		RawFrameRequest expected = edt(() -> session.frameRequest(1));
		release.countDown();
		RawStrictPlayer.Result result = latest.get(10, TimeUnit.SECONDS);
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, result.outcome);
		assertCapture("after an off-EDT burst", expected, result.request);
		assertEquals(RawStrictPlayer.Outcome.SUPERSEDED, held.get(10, TimeUnit.SECONDS).outcome);
		edt(() -> null);
		assertEquals("two requests reached the player", before + 2, outcomes());

		List<?> states = ((org.scijava.listeners.Listeners.List<?>) viewer().state().changeListeners()).list;
		List<?> setups = ((org.scijava.listeners.Listeners.List<?>) session.handle().getConverterSetups().listeners()).list;
		Object stateListener = field(session, "viewListener"), setupListener = field(session, "setupListener");
		AtomicBoolean stop = new AtomicBoolean();
		AtomicInteger edits = new AtomicInteger();
		SynchronizedViewerState state = viewer().state();
		Thread burst = new Thread(() -> {
			while (!stop.get()) { state.setViewerTransform(panned(state.getViewerTransform(), 1)); edits.incrementAndGet(); }
		}, "probe-burst");
		burst.start();
		try {
			until("the burst is running", 5, () -> edits.get() > 50);
			edt(() -> { session.frame().dispose(); return null; });
			assertTrue(session.closed().get(15, TimeUnit.SECONDS).drained);
		} finally {
			stop.set(true);
			burst.join(5000);
		}
		assertFalse("script thread stopped", burst.isAlive());
		assertFalse(states.contains(stateListener));
		assertFalse(setups.contains(setupListener));
		session = null;
	}

	/* ---------------------------------------------------------------- requirement 7 */

	private void prefetchAfterWorldActions(int editsWhileTheFirstRedrawIsHeld) throws Exception {
		open(5);
		completed();
		List<Integer> commits = recordCommits();
		edt(() -> { session.playback().setTargetFps(0.5); session.playback().play(); return null; });
		until("the first movie frame", 10, () -> commits.contains(1));
		until("its advance is scheduled", 5, () -> {
			try { return edt(() -> session.playback().hasScheduledAdvance()); } catch (Exception e) { throw new AssertionError(e); }
		});
		long used = session.player().prefetchCounts()[1];
		CountDownLatch release = null;
		if (editsWhileTheFirstRedrawIsHeld > 0) {
			CountDownLatch entered = new CountDownLatch(1);
			release = holdNextRender(entered);
			edt(() -> { session.worldAction(RawWorldView.XZ).actionPerformed(null); return null; });
			await(entered);		// the XZ redraw renders; YZ arrives meanwhile
			edt(() -> { session.worldAction(RawWorldView.YZ).actionPerformed(null); return null; });
			edt(() -> null);
			// Each edit gets its own event and coalesced capture, as in a mouse drag.
			for (int i = 1; i < editsWhileTheFirstRedrawIsHeld; i++) {
				edt(() -> { viewer().state().setViewerTransform(panned(viewer().state().getViewerTransform(), 1)); return null; });
				edt(() -> null);
			}
			release.countDown();
		} else {
			edt(() -> { session.worldAction(RawWorldView.XZ).actionPerformed(null); return null; });
			edt(() -> null);
		}
		CompletableFuture<RawStrictPlayer.Result> redraw = edt(session::navigationResult);
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, redraw.get(10, TimeUnit.SECONDS).outcome);
		assertTrue("still waiting on the cadence", edt(() -> session.playback().hasScheduledAdvance()));
		until("the next movie frame", 10, () -> commits.contains(2));
		edt(() -> { session.playback().pause(); return null; });
		long[] counts = session.player().prefetchCounts();
		System.out.println("prefetch after " + (editsWhileTheFirstRedrawIsHeld + 1) + " view edits: started/used/refused/released "
				+ java.util.Arrays.toString(counts) + ", used before " + used);
		assertEquals("requirement 7: the next movie frame uses the prefetch reissued at the new view", used + 1, counts[1]);
	}

	@Test public void theNextFrameIsPrefetchedAtTheNewViewAfterOneWorldAction() throws Exception {
		prefetchAfterWorldActions(0);
	}

	@Test public void theNextFrameIsPrefetchedAtTheNewViewAfterTwoQuickWorldActions() throws Exception {
		prefetchAfterWorldActions(1);
	}

	@Test public void theNextFrameIsPrefetchedAtTheNewViewAfterADragDuringARedraw() throws Exception {
		prefetchAfterWorldActions(8);
	}

	/* ---------------------------------------------------------------- a refresh after a newer navigation */

	/**
	 * A timer or slider event already queued behind an edit asks a new time before the edit's
	 * coalesced capture runs. That navigation is newer than the edit and captures the edited view
	 * itself, so the refresh has nothing left to do; reissuing it supersedes work for nothing.
	 */
	@Test public void aNavigationAskedAfterAnEditIsNotReissuedByThatEditsRefresh() throws Exception {
		open(4);
		completed();
		OnlineDeskewView.Settings settings = (OnlineDeskewView.Settings) field(session, "settings");
		until("every file checked", 10, () -> session.times().stream()
				.allMatch(k -> session.acquisition().entry(k, settings.channel).readiness() != RawAcquisition.Readiness.PENDING));
		long superseded = session.player().count(RawStrictPlayer.Outcome.SUPERSEDED);
		CompletableFuture<RawStrictPlayer.Result> asked = edt(() -> {
			viewer().state().setViewerTransform(panned(viewer().state().getViewerTransform(), 9));
			return session.requestStrictFrame(2);
		});
		edt(() -> null); edt(() -> null);
		RawStrictPlayer.Result last = edt(session::navigationResult).get(10, TimeUnit.SECONDS);
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, last.outcome);
		assertCapture("time 2 at the edited view", edt(() -> session.frameRequest(2)), last.request);
		assertSame("the navigation asked after the edit is the one presented", asked, edt(session::navigationResult));
		assertEquals("nothing superseded", superseded, session.player().count(RawStrictPlayer.Outcome.SUPERSEDED));
	}

	/* ---------------------------------------------------------------- an off-EDT edit in the commit window */

	/**
	 * Forces, deterministically, the interleaving a script or BigDataViewer's painter thread can
	 * produce: inside the paint that commits frame {@code at}, another thread changes the view (and
	 * has invalidated before it reaches the presenter's monitor), then changes it back before the
	 * coalesced capture runs. The frame on screen is then the frame of the current view, and it was
	 * presented: playback must still hear of it.
	 */
	private void editAndRevertOffEdtInTheCommitOf(int at) throws Exception {
		AtomicBoolean armed = new AtomicBoolean(true);
		AtomicLong invalidated = (AtomicLong) field(session, "viewInvalidated");
		session.presenter().setCommitObserver((r, image) -> {
			if (r.timeIndex != at || !armed.compareAndSet(true, false)) return;
			AffineTransform3D original = r.viewer.copy();
			CountDownLatch revert = new CountDownLatch(1);
			SynchronizedViewerState state = viewer().state();
			Thread script = new Thread(() -> {
				state.setViewerTransform(panned(original, 17));
				await(revert);
				state.setViewerTransform(original);
			}, "probe-commit-window");
			script.start();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (invalidated.get() <= r.revision && System.nanoTime() < deadline) Thread.yield();
			assertTrue("precondition: the other thread invalidated inside the commit", invalidated.get() > r.revision);
			// runs before the refresh that the session's end-of-frame callback is about to post
			SwingUtilities.invokeLater(() -> {
				revert.countDown();
				try { script.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
			});
		});
	}

	@Test public void aPausedStepPresentedInARacedCommitIsStillReportedSoPlayCarriesOn() throws Exception {
		open(5);
		completed();
		editAndRevertOffEdtInTheCommitOf(1);
		edt(() -> { session.playback().step(1); return null; });
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, session.navigationResult().get(10, TimeUnit.SECONDS).outcome);
		edt(() -> null); edt(() -> null); edt(() -> null);
		RawFrameRequest shown = edt(() -> session.presenter().shownRequest());
		assertCapture("the shown frame is the current view: nothing to redraw", edt(() -> session.frameRequest(1)), shown);
		List<Integer> commits = recordCommits();
		edt(() -> { session.playback().setTargetFps(10); session.playback().play(); return null; });
		Thread.sleep(3000);
		boolean advanced = commits.contains(2);
		String state = edt(() -> "playing " + session.playback().isPlaying() + ", advance scheduled " + session.playback().hasScheduledAdvance()
				+ ", presented " + session.playback().stats().presented + ", navigationPending " + field(session.playback(), "navigationPending")
				+ ", status '" + session.playbackText() + "'");
		System.out.println("paused raced commit, then Play: commits " + commits + "; " + state);
		assertTrue("Play after a raced commit must carry on; " + state, advanced);
	}

	@Test public void aMovieFramePresentedInARacedCommitIsStillCountedSoTheMovieCarriesOn() throws Exception {
		open(6);
		completed();
		editAndRevertOffEdtInTheCommitOf(2);
		edt(() -> { session.playback().setTargetFps(10); session.playback().play(); return null; });
		Thread.sleep(3000);
		String state = edt(() -> "playing " + session.playback().isPlaying() + ", advance scheduled " + session.playback().hasScheduledAdvance()
				+ ", presented " + session.playback().stats().presented + ", shown t=" + (session.presenter().shownRequest().timeIndex)
				+ ", status '" + session.playbackText() + "'");
		System.out.println("raced commit while playing: " + state);
		assertEquals("the movie reaches its last frame; " + state, 5, (int) edt(() -> session.presenter().shownRequest().timeIndex));
		assertEquals("each movie frame, including the raced one, counts once", 5, (long) edt(() -> session.playback().stats().presented));
		assertFalse("stopped at the last frame", edt(() -> session.playback().isPlaying()));
	}
}
