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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import bdv.viewer.Interpolation;
import bdv.viewer.ViewerPanel;
import bdv.viewer.DisplayMode;
import bdv.tools.brightness.ConverterSetup;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;

/** Rendering-state and playback integration checks for M2-C1. Requires opm.test.gui=true. */
public class OnlineDeskewViewStateGuiTest {
	@Rule public TemporaryFolder folder = new TemporaryFolder();
	private OnlineDeskewSession session;
	private CountDownLatch release;
	private final List<Throwable> uncaught = Collections.synchronizedList(new ArrayList<Throwable>());
	private Thread.UncaughtExceptionHandler handler;

	@Before public void display() {
		Assume.assumeTrue(Boolean.getBoolean("opm.test.gui"));
		handler = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((thread, error) -> { uncaught.add(error); error.printStackTrace(); });
	}

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

	private void open() throws Exception {
		for (int t = 1; t <= 4; t++)
			RawTiffFixture.write(new File(folder.getRoot(), String.format(
				"v_Position0001_Time%06d_Channel0001_Frames_1_20.tiff", t)),
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
	}

	@After public void close() throws Exception {
		if (!Boolean.getBoolean("opm.test.gui")) return;
		try { closeSession(); } finally { Thread.setDefaultUncaughtExceptionHandler(handler); }
		assertTrue("uncaught exceptions: " + uncaught, uncaught.isEmpty());
	}

	private void closeSession() throws Exception {
		if (release != null) release.countDown();
		RawFrameRenderer.rowHookForTest = null;
		RawStrictPlayer.beforeHandOverForTest = null;
		if (session != null) {
			edt(() -> { session.frame().dispose(); return null; });
			assertTrue(session.closed().get(15, TimeUnit.SECONDS).drained);
			session = null;
		}
	}

	private RawFrameRequest completed() throws Exception {
		edt(() -> { session.playback().setMode(RawPlayback.Mode.COMPLETED); return null; });
		session.navigationResult().get(10, TimeUnit.SECONDS);
		return edt(() -> session.presenter().shownRequest());
	}

	private RawFrameRequest awaitReplacement(RawFrameRequest old) throws Exception {
		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		RawFrameRequest current;
		do {
			current = edt(() -> session.presenter().shownRequest());
			if (current != old) return current;
			Thread.sleep(10);
		} while (System.nanoTime() < until);
		return current;
	}

	@Test public void pausedCompletedCameraChangeRequestsANewFrame() throws Exception {
		open();
		RawFrameRequest old = completed();
		edt(() -> {
			ViewerPanel viewer = session.handle().getViewerPanel();
			AffineTransform3D changed = viewer.state().getViewerTransform();
			changed.set(changed.get(0, 3) + 31, 0, 3);
			viewer.state().setViewerTransform(changed);
			viewer.requestRepaint();
			return null;
		});
		assertNotSame("B1: camera change must update completed image", old, awaitReplacement(old));
	}

	@Test public void pausedCompletedInterpolationChangeRequestsANewFrame() throws Exception {
		open();
		RawFrameRequest old = completed();
		edt(() -> {
			ViewerPanel viewer = session.handle().getViewerPanel();
			viewer.state().setInterpolation(old.interpolation == Interpolation.NLINEAR
				? Interpolation.NEARESTNEIGHBOR : Interpolation.NLINEAR);
			viewer.requestRepaint();
			return null;
		});
		assertNotSame("B1: interpolation change must update completed image", old, awaitReplacement(old));
	}

	@Test public void pausedCompletedVisibilityChangeRequestsANewFrame() throws Exception {
		open();
		RawFrameRequest old = completed();
		edt(() -> {
			ViewerPanel viewer = session.handle().getViewerPanel();
			viewer.state().setSourceActive(viewer.state().getSources().get(0), false);
			viewer.requestRepaint();
			return null;
		});
		assertNotSame("B1: source toggle must update completed image", old, awaitReplacement(old));
	}

	private enum Edit { PAN, ZOOM, SLICE, OBLIQUE, INTERPOLATION, SOURCE, RANGE, COLOUR }
	private enum Phase { PAUSED, PREPARING, RENDERING, AWAITING_PAINT }

	/** Called on the EDT except for the awaiting-paint test of synchronous off-EDT invalidation. */
	private void edit(Edit edit) {
		ViewerPanel viewer = session.handle().getViewerPanel();
		AffineTransform3D view = viewer.state().getViewerTransform();
		switch (edit) {
		case PAN: view.set(view.get(0, 3) + 13, 0, 3); break;
		case ZOOM: view.scale(1.1); break;
		case SLICE: view.set(view.get(2, 3) + 2, 2, 3); break;
		case OBLIQUE: view.rotate(0, .17); view.rotate(1, .11); break;
		case INTERPOLATION: viewer.state().setInterpolation(Interpolation.NEARESTNEIGHBOR); return;
		case SOURCE: viewer.state().setSourceActive(viewer.state().getSources().get(0), false); return;
		case RANGE: setup().setDisplayRange(400, 1900); return;
		case COLOUR: setup().setColor(new ARGBType(0xff1030ff)); return;
		default: throw new AssertionError(edit);
		}
		viewer.state().setViewerTransform(view);
	}

	private ConverterSetup setup() {
		return session.handle().getConverterSetups().getConverterSetup(session.handle().getViewerPanel().state().getSources().get(0));
	}

	private static void await(CountDownLatch latch) {
		try { assertTrue("latch deadline", latch.await(15, TimeUnit.SECONDS)); }
		catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
	}

	private static void until(BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
		assertTrue("condition deadline", condition.getAsBoolean());
	}

	private CountDownLatch hold(Phase phase) {
		CountDownLatch entered = new CountDownLatch(1);
		release = new CountDownLatch(1);
		AtomicBoolean first = new AtomicBoolean(true);
		Runnable gate = () -> { if (first.compareAndSet(true, false)) { entered.countDown(); await(release); } };
		if (phase == Phase.PREPARING) session.store().setReadHook(cell -> {
			assertFalse("no raw read on the EDT", SwingUtilities.isEventDispatchThread()); gate.run();
		});
		if (phase == Phase.RENDERING) RawFrameRenderer.rowHookForTest = row -> gate.run();
		if (phase == Phase.AWAITING_PAINT) RawStrictPlayer.beforeHandOverForTest = request -> gate.run();
		return entered;
	}

	/** Independent comparison, rather than using the production equality helper as its own oracle. */
	private static void assertCapture(RawFrameRequest expected, RawFrameRequest shown) {
		assertEquals(expected.timeIndex, shown.timeIndex);
		assertEquals(expected.key, shown.key);
		assertEquals(expected.width, shown.width); assertEquals(expected.height, shown.height);
		assertArrayEquals(expected.viewer.getRowPackedCopy(), shown.viewer.getRowPackedCopy(), 0);
		assertEquals(expected.interpolation, shown.interpolation);
		assertEquals(expected.layers.size(), shown.layers.size());
		for (int i = 0; i < expected.layers.size(); i++) {
			RawFrameRequest.Layer e = expected.layers.get(i), s = shown.layers.get(i);
			assertSame(e.spec, s.spec);
			assertArrayEquals(e.sourceToWorld.getRowPackedCopy(), s.sourceToWorld.getRowPackedCopy(), 0);
			assertEquals(e.min, s.min, 0); assertEquals(e.max, s.max, 0); assertEquals(e.argb, s.argb);
		}
	}

	private void matrix(Phase phase) throws Exception {
		for (Edit edit : Edit.values()) {
			open();
			RawFrameRequest oldShown = completed();
			List<Long> committed = Collections.synchronizedList(new ArrayList<Long>());
			session.presenter().setCommitObserver((r, pixels) -> committed.add(r.revision));
			CompletableFuture<RawStrictPlayer.Result> obsolete = null;
			CountDownLatch paintRelease = new CountDownLatch(1);
			try {
				int desired = phase == Phase.PAUSED ? 0 : 1;
				if (phase != Phase.PAUSED) {
					CountDownLatch entered = hold(phase);
					obsolete = edt(() -> session.requestStrictFrame(1));
					await(entered);
				}
				if (phase == Phase.AWAITING_PAINT) {
					CountDownLatch paintBlocked = new CountDownLatch(1);
					SwingUtilities.invokeLater(() -> { paintBlocked.countDown(); await(paintRelease); });
					await(paintBlocked);
					release.countDown();
					until(() -> session.presenter().waiting());
					edit(edit); // must invalidate immediately, while Swing cannot run the queued capture
					assertEquals(RawStrictPlayer.Outcome.SUPERSEDED, obsolete.get(2, TimeUnit.SECONDS).outcome);
					assertSame(oldShown, session.presenter().shownRequest());
					paintRelease.countDown();
				} else edt(() -> { edit(edit); return null; });
				// Flush the coalesced capture. The old prepare/render is still held here.
				CompletableFuture<RawStrictPlayer.Result> latest = edt(session::navigationResult);
				RawFrameRequest expected = edt(() -> session.frameRequest(desired));
				if (release != null) release.countDown();
				RawStrictPlayer.Result result = latest.get(10, TimeUnit.SECONDS);
				assertEquals(phase + " " + edit + ": " + result, RawStrictPlayer.Outcome.PRESENTED, result.outcome);
				assertCapture(expected, result.request);
				assertSame(result.request, edt(() -> session.presenter().shownRequest()));
				assertEquals(0, result.coverageMisses);
				if (obsolete != null) {
					RawStrictPlayer.Result dropped = obsolete.get(10, TimeUnit.SECONDS);
					assertEquals(RawStrictPlayer.Outcome.SUPERSEDED, dropped.outcome);
					assertFalse("obsolete revision did not commit", committed.contains(dropped.request.revision));
				}
				assertEquals("one replacement paint", 1, committed.size());
				System.out.println("view-state matrix: " + phase + " / " + edit + " PASS");
			} finally { paintRelease.countDown(); closeSession(); }
		}
	}

	@Test public void allCapturedEditsWhilePaused() throws Exception { matrix(Phase.PAUSED); }
	@Test public void allCapturedEditsDuringPreparation() throws Exception { matrix(Phase.PREPARING); }
	@Test public void allCapturedEditsDuringRendering() throws Exception { matrix(Phase.RENDERING); }
	@Test public void allCapturedEditsAwaitingPaintInvalidateOffEdt() throws Exception { matrix(Phase.AWAITING_PAINT); }

	private static Object field(Object object, String name) throws Exception {
		Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
	}

	@Test public void transformBurstsStayBoundedAndCloseDetachesBothListeners() throws Exception {
		open(); completed();
		CountDownLatch entered = hold(Phase.RENDERING);
		CompletableFuture<RawStrictPlayer.Result> running = edt(() -> session.requestStrictFrame(1));
		await(entered);
		AtomicLong revisions = (AtomicLong) field(session, "revisions");
		long before = revisions.get();
		edt(() -> { for (int i = 0; i < 100; i++) edit(Edit.PAN); return null; });
		CompletableFuture<RawStrictPlayer.Result> pending = edt(session::navigationResult);
		assertNotSame(running, pending);
		assertTrue("100 invalidations and at most one capture, not 100 queued captures", revisions.get() - before <= 101);
		assertNotNull(field(session, "inFlight"));
		assertEquals(false, field(field(session, "navigation"), "submitted"));
		assertFalse(pending.isDone());
		Object stateListener = field(session, "viewListener"), converterListener = field(session, "setupListener");
		List<?> states = ((org.scijava.listeners.Listeners.List<?>) session.handle().getViewerPanel().state().changeListeners()).list;
		List<?> setups = ((org.scijava.listeners.Listeners.List<?>) session.handle().getConverterSetups().listeners()).list;
		assertTrue(states.contains(stateListener)); assertTrue(setups.contains(converterListener));
		edt(() -> { for (int i = 0; i < 100; i++) edit(Edit.ZOOM); session.close(); return null; });
		assertFalse(states.contains(stateListener)); assertFalse(setups.contains(converterListener));
		release.countDown();
		assertTrue(session.closed().get(10, TimeUnit.SECONDS).drained);
		assertNotEquals(RawStrictPlayer.Outcome.PRESENTED, running.get(10, TimeUnit.SECONDS).outcome);
		assertTrue(pending.isCancelled());
	}

	@Test public void equalStateEventsDoNotSubmitAnotherFrame() throws Exception {
		open(); RawFrameRequest shown = completed();
		CompletableFuture<RawStrictPlayer.Result> before = edt(session::navigationResult);
		edt(() -> {
			ViewerPanel viewer = session.handle().getViewerPanel();
			viewer.state().setViewerTransform(viewer.state().getViewerTransform());
			// Current source changes in fused mode emit a state event but capture the same layers.
			viewer.state().setCurrentSource(viewer.state().getSources().get(
				viewer.state().getCurrentSource() == viewer.state().getSources().get(0) ? 1 : 0));
			return null;
		});
		edt(() -> null);
		assertSame(before, edt(session::navigationResult));
		assertSame(shown, edt(() -> session.presenter().shownRequest()));
		assertEquals(1, session.player().count(RawStrictPlayer.Outcome.PRESENTED));
	}

	@Test public void pendingStepIsTheFirstMovieFrameAndWorldRedrawKeepsCadence() throws Exception {
		open(); completed();
		List<Integer> times = Collections.synchronizedList(new ArrayList<Integer>());
		session.presenter().setCommitObserver((r, image) -> times.add(r.timeIndex));
		CountDownLatch entered = hold(Phase.PREPARING);
		edt(() -> { session.playback().step(1); return null; });
		await(entered);
		CompletableFuture<RawStrictPlayer.Result> step = edt(session::navigationResult);
		edt(() -> { session.playback().setTargetFps(1); session.playback().play(); return null; });
		assertSame("Play adopts the pending request", step, edt(session::navigationResult));
		release.countDown();
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, step.get(10, TimeUnit.SECONDS).outcome);
		edt(() -> null);
		assertEquals(Collections.singletonList(1), times);
		assertTrue(edt(() -> session.playback().hasScheduledAdvance()));
		long prefetched = session.player().prefetchCounts()[0];
		// Delay later cells too, while keeping the redraw shorter than this one-second cadence.
		session.store().setReadHook(cell -> { try { Thread.sleep(15); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
		edt(() -> { session.worldAction(RawWorldView.XZ).actionPerformed(null); return null; });
		CompletableFuture<RawStrictPlayer.Result> redraw = edt(session::navigationResult);
		RawStrictPlayer.Result changed = redraw.get(10, TimeUnit.SECONDS);
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, changed.outcome);
		assertTrue(changed.request.viewer.get(1, 2) > 0);
		edt(() -> null);
		assertEquals(1, (long) edt(() -> session.playback().stats().presented));
		assertTrue(edt(() -> session.playback().hasScheduledAdvance()));
		until(() -> session.player().prefetchCounts()[0] > prefetched);
		until(() -> times.contains(3));
		edt(() -> null);
		assertEquals(java.util.Arrays.asList(1, 1, 2, 3), new ArrayList<Integer>(times));
		assertEquals(3, (long) edt(() -> session.playback().stats().presented));
		assertFalse(edt(() -> session.playback().hasScheduledAdvance()));
	}

	@Test public void returningToTheShownStateWithinABurstNeedsNoReplacement() throws Exception {
		open(); RawFrameRequest shown = completed();
		CompletableFuture<RawStrictPlayer.Result> before = edt(session::navigationResult);
		edt(() -> {
			edit(Edit.PAN);
			session.handle().getViewerPanel().state().setViewerTransform(shown.viewer);
			return null;
		});
		edt(() -> null);
		assertSame(before, edt(session::navigationResult));
		assertSame(shown, edt(() -> session.presenter().shownRequest()));
		assertEquals(1, session.player().count(RawStrictPlayer.Outcome.PRESENTED));
	}

	@Test public void returningToAnUnpresentedStateReissuesTheAlreadyInvalidatedWork() throws Exception {
		open(); completed();
		CountDownLatch entered = hold(Phase.RENDERING);
		CompletableFuture<RawStrictPlayer.Result> old = edt(() -> session.requestStrictFrame(1));
		await(entered);
		edt(() -> {
			ViewerPanel v = session.handle().getViewerPanel();
			AffineTransform3D original = v.state().getViewerTransform();
			edit(Edit.PAN);
			v.state().setViewerTransform(original);
			return null;
		});
		CompletableFuture<RawStrictPlayer.Result> replacement = edt(session::navigationResult);
		assertNotSame(old, replacement);
		release.countDown();
		assertEquals(RawStrictPlayer.Outcome.SUPERSEDED, old.get(10, TimeUnit.SECONDS).outcome);
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, replacement.get(10, TimeUnit.SECONDS).outcome);
	}

	@Test public void equalEventDuringRenderingDoesNotCancelTheFrame() throws Exception {
		open(); completed();
		CountDownLatch entered = hold(Phase.RENDERING);
		CompletableFuture<RawStrictPlayer.Result> old = edt(() -> session.requestStrictFrame(1));
		await(entered);
		edt(() -> {
			ViewerPanel viewer = session.handle().getViewerPanel();
			viewer.state().setCurrentSource(viewer.state().getSources().get(
				viewer.state().getCurrentSource() == viewer.state().getSources().get(0) ? 1 : 0));
			return null;
		});
		assertSame(old, edt(session::navigationResult));
		release.countDown();
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, old.get(10, TimeUnit.SECONDS).outcome);
	}

	@Test public void displayModeAndGroupMembershipRecaptureVisibleLayers() throws Exception {
		open(); completed();
		edt(() -> { session.handle().getViewerPanel().state().setDisplayMode(DisplayMode.SINGLE); return null; });
		assertEquals(1, edt(session::navigationResult).get(10, TimeUnit.SECONDS).request.layers.size());
		bdv.viewer.SourceGroup group = new bdv.viewer.SourceGroup();
		edt(() -> {
			bdv.viewer.SynchronizedViewerState state = session.handle().getViewerPanel().state();
			state.addGroup(group);
			state.setGroupName(group, "test pair"); // BDV's source-info overlay expects a named group
			state.addSourceToGroup(state.getSources().get(0), group);
			state.addSourceToGroup(state.getSources().get(1), group);
			state.setCurrentGroup(group);
			state.setDisplayMode(DisplayMode.GROUP);
			return null;
		});
		assertEquals(2, edt(session::navigationResult).get(10, TimeUnit.SECONDS).request.layers.size());
		edt(() -> {
			bdv.viewer.SynchronizedViewerState state = session.handle().getViewerPanel().state();
			state.removeSourceFromGroup(state.getSources().get(0), group);
			return null;
		});
		assertEquals(1, edt(session::navigationResult).get(10, TimeUnit.SECONDS).request.layers.size());
	}
}
