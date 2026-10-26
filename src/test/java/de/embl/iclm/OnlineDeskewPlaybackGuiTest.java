package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerPanel;

/**
 * Playback in a real Online Deskew View (joint contract C6.1): the playback row drives the view in
 * both modes, through the same navigation entry as the world-plane actions. Needs a display:
 * {@code mvn test -Dtest=OnlineDeskewPlaybackGuiTest -Dopm.test.gui=true}.
 *
 * <ul>
 * <li>Completed frames present every time point in order - none skipped to keep the rate - and
 *     loop; a time point whose file is incomplete is skipped with a note; a slow frame slows
 *     playback down.</li>
 * <li>Moving the slider by hand pauses playback and shows that time point; a world-plane action
 *     keeps playback going in the new plane.</li>
 * <li>Progressive playback moves BigDataViewer's time point at the target rate.</li>
 * <li>Leaving completed mode releases its frames and gives BigDataViewer its sources back; closing
 *     while playing is clean.</li>
 * <li>An exception on the event thread or a worker fails the test.</li>
 * </ul>
 */
public class OnlineDeskewPlaybackGuiTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final int W = 64, H = 40, D = 20;
	private final List<Throwable> uncaught = Collections.synchronizedList(new ArrayList<Throwable>());
	private Thread.UncaughtExceptionHandler previousHandler;
	private OnlineDeskewSession session;

	@Before
	public void needsADisplay() {
		Assume.assumeTrue("Enable with -Dopm.test.gui=true", Boolean.getBoolean("opm.test.gui"));
		previousHandler = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
			uncaught.add(new AssertionError("uncaught on " + thread.getName() + ": " + e, e));
			e.printStackTrace();
		});
	}

	@After
	public void closeAndCheck() throws Exception {
		if (!Boolean.getBoolean("opm.test.gui")) return;
		try {
			if (session != null && !session.isClosing()) {
				SwingUtilities.invokeAndWait(() -> session.frame().dispose());
				OnlineDeskewSession.CloseResult result = session.closed().get(15, TimeUnit.SECONDS);
				assertTrue("closed cleanly: " + result, result.drained);
				assertEquals(0, result.store.after.stagedBytes);
				assertEquals(0, result.store.after.pinnedBytes);
			}
			SwingUtilities.invokeAndWait(() -> {});
		} finally {
			Thread.setDefaultUncaughtExceptionHandler(previousHandler);
		}
		if (!uncaught.isEmpty()) throw (AssertionError) uncaught.get(0);
	}

	/** {@code count} time points; those listed in {@code cut} are written with half their planes. */
	private OnlineDeskewView.Settings fixtureFolder(int count, int... cut) throws Exception {
		for (int t = 1; t <= count; t++) {
			final int base = 600 + 400 * (t - 1);
			boolean short_ = false;
			for (int c : cut) short_ |= c == t - 1;
			RawTiffFixture.write(new File(folder.getRoot(), String.format("p_Position0001_Time%06d_Channel0001_Frames_1_%d.tiff", t, D)),
					W, H, D, 8, short_ ? D / 2 : D, (x, y, k) -> base + (x * 7 + y * 13 + k * 29) % 300);
		}
		try (FileWriter w = new FileWriter(new File(folder.getRoot(), "ExperimentalParameters.txt"))) {
			w.write("Interval: 5 Minute\nTilt angle: 33.5\num per galvo DU: 0.01325\nGalvo DUs per step: 20\n"
					+ "Pixel size at object /um: 0.116\nCamera ROI X0, X1, Y0, Y1: 0 " + W + " 0 " + H + "\n");
		}
		OnlineDeskewView.Settings s = new OnlineDeskewView.Settings();
		s.folder = folder.getRoot().getAbsolutePath();
		s.budgetMb = 64;
		return s;
	}

	private static <T> T edt(Callable<T> read) throws Exception {
		final Object[] value = new Object[1];
		final Exception[] failure = new Exception[1];
		SwingUtilities.invokeAndWait(() -> {
			try {
				value[0] = read.call();
			} catch (Exception e) {
				failure[0] = e;
			}
		});
		if (failure[0] != null) throw failure[0];
		@SuppressWarnings("unchecked") T t = (T) value[0];
		return t;
	}

	private static void edtRun(Runnable r) throws Exception {
		SwingUtilities.invokeAndWait(r);
	}

	/** Every time index committed by the presenter, in order. */
	private List<Integer> watchPresented() {
		final List<Integer> presented = Collections.synchronizedList(new ArrayList<Integer>());
		session.presenter().setCommitObserver((r, image) -> presented.add(r.timeIndex));
		return presented;
	}

	private static void awaitSize(List<Integer> list, int size, long millis) throws InterruptedException {
		long deadline = System.currentTimeMillis() + millis;
		while (list.size() < size && System.currentTimeMillis() < deadline) Thread.sleep(10);
		assertTrue("reached " + list.size() + " of " + size + ": " + list, list.size() >= size);
	}

	/** Enter completed mode, as the mode box does, and wait until its first frame is on screen. */
	private List<Integer> enterCompleted() throws Exception {
		edtRun(() -> session.playback().setMode(RawPlayback.Mode.COMPLETED));
		assertTrue(edt(() -> session.player() != null));
		List<Integer> presented = watchPresented();
		long deadline = System.currentTimeMillis() + 10000;
		while (edt(() -> session.presenter().shownRequest()) == null && System.currentTimeMillis() < deadline) Thread.sleep(10);
		return presented;
	}

	private static boolean consecutive(List<Integer> times, int count) {
		for (int i = 1; i < times.size(); i++)
			if (times.get(i) != (times.get(i - 1) + 1) % count) return false;
		return true;
	}

	@Test
	public void completedFramesArePresentedInOrderNoneSkippedAndLoop() throws Exception {
		session = OnlineDeskewSession.open(fixtureFolder(3));
		List<Integer> presented = enterCompleted();
		edtRun(() -> {
			session.playback().setLoop(true);
			session.playback().setTargetFps(30);
			session.playback().play();
		});
		awaitSize(presented, 8, 20000);
		edtRun(() -> session.playback().pause());
		List<Integer> seen = new ArrayList<Integer>(presented);
		System.out.println("completed playback: " + seen + "; " + edt(session::playbackText));
		assertTrue("every time point in turn, wrapping: " + seen, consecutive(seen, 3));
		assertTrue(edt(session::playbackText), edt(session::playbackText).contains("Completed frames, paused"));
		RawPlayback.Stats s = edt(() -> session.playback().stats());
		assertEquals(0, s.skipped);
		long[] prefetch = session.player().prefetchCounts();
		System.out.println("prefetch: " + prefetch[0] + " started, " + prefetch[1] + " used, " + prefetch[2] + " not fitting, "
				+ prefetch[3] + " released unused");
		assertTrue("the next frame's cells were prepared ahead and used", prefetch[1] > 0);
		Thread.sleep(300);
		int last = seen.get(seen.size() - 1);
		ViewerPanel viewer = session.handle().getViewerPanel();
		assertEquals("the slider follows the presented frame", (Object) presented.get(presented.size() - 1),
				edt(() -> viewer.state().getCurrentTimepoint()));
		assertTrue("paused: at most the frame already asked for arrives", presented.size() <= seen.size() + 1);
		assertTrue(last >= 0);
	}

	@Test
	public void anIncompleteTimePointIsSkippedWithANote() throws Exception {
		session = OnlineDeskewSession.open(fixtureFolder(4, 2));		// time index 2 is cut short
		List<Integer> presented = enterCompleted();
		edtRun(() -> {
			session.playback().setLoop(true);
			session.playback().setTargetFps(30);
			session.playback().play();
		});
		awaitSize(presented, 7, 20000);
		edtRun(() -> session.playback().pause());
		List<Integer> seen = new ArrayList<Integer>(presented);
		System.out.println("playback over an incomplete file: " + seen + "; " + edt(session::playbackText));
		assertFalse("never shown: " + seen, seen.contains(2));
		for (int i = 1; i < seen.size(); i++) {
			int expected = seen.get(i - 1) == 1 ? 3 : (seen.get(i - 1) + 1) % 4;
			assertEquals("in order, past the incomplete one: " + seen, expected, (int) seen.get(i));
		}
		assertTrue(edt(() -> session.playback().stats()).skipped > 0);
		assertTrue(edt(session::playbackText).contains("skipped"));
	}

	@Test
	public void aSlowFrameSlowsPlaybackButNoneIsSkipped() throws Exception {
		session = OnlineDeskewSession.open(fixtureFolder(4));
		List<Integer> presented = enterCompleted();
		session.store().setReadHook(cell -> {		// every cell not read yet takes 40 ms
			try {
				Thread.sleep(40);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		long start = System.nanoTime();
		edtRun(() -> {
			session.playback().setTargetFps(60);
			session.playback().play();
		});
		awaitSize(presented, 4, 30000);		// times 1, 2, 3 are read for the first time
		double seconds = (System.nanoTime() - start) / 1e9;
		edtRun(() -> session.playback().pause());
		List<Integer> seen = new ArrayList<Integer>(presented);
		System.out.printf("slow frames: %s in %.2f s at a target of 60 fps%n", seen, seconds);
		assertTrue("every time point in turn: " + seen, consecutive(seen, 4));
		assertTrue("slowed by the reads, not skipped to keep the rate: " + seconds, 3 / seconds < 20);
		session.store().setReadHook(null);
	}

	@Test
	public void movingTheSliderByHandPausesAndShowsThatTimePoint() throws Exception {
		session = OnlineDeskewSession.open(fixtureFolder(5));
		List<Integer> presented = enterCompleted();
		edtRun(() -> {
			session.playback().setLoop(true);
			session.playback().setTargetFps(20);
			session.playback().play();
		});
		awaitSize(presented, 3, 10000);
		ViewerPanel viewer = session.handle().getViewerPanel();
		edtRun(() -> viewer.setTimepoint(4));		// by hand
		assertFalse(edt(() -> session.playback().isPlaying()));
		assertTrue(edt(session::playbackText).contains("moved by hand"));
		long deadline = System.currentTimeMillis() + 10000;
		while (edt(() -> session.presenter().shownRequest().timeIndex) != 4 && System.currentTimeMillis() < deadline) Thread.sleep(10);
		assertEquals(4, (int) edt(() -> session.presenter().shownRequest().timeIndex));
	}

	@Test
	public void aWorldPlaneActionKeepsPlaybackGoingInTheNewPlane() throws Exception {
		session = OnlineDeskewSession.open(fixtureFolder(3));
		List<Integer> presented = enterCompleted();
		edtRun(() -> {
			session.playback().setLoop(true);
			session.playback().setTargetFps(20);
			session.playback().play();
		});
		awaitSize(presented, 2, 10000);
		edtRun(() -> session.worldAction(RawWorldView.XZ).actionPerformed(null));
		int before = presented.size();
		awaitSize(presented, before + 4, 20000);
		assertTrue(edt(() -> session.playback().isPlaying()));
		edtRun(() -> session.playback().pause());
		double[] view = edt(() -> session.presenter().shownRequest().viewer.getRowPackedCopy());
		assertEquals("screen down is world Z: the XZ plane", 0, view[4 * 1 + 1], 1e-9);
		assertTrue(view[4 * 1 + 2] > 0);
	}

	@Test
	public void progressivePlaybackMovesTheTimePointAtTheRate() throws Exception {
		session = OnlineDeskewSession.open(fixtureFolder(5));
		ViewerPanel viewer = session.handle().getViewerPanel();
		final List<Integer> times = Collections.synchronizedList(new ArrayList<Integer>());
		edtRun(() -> viewer.addTimePointListener(times::add));
		edtRun(() -> {
			session.playback().setLoop(true);
			session.playback().setTargetFps(20);
			session.playback().play();
		});
		Thread.sleep(600);
		edtRun(() -> session.playback().pause());
		RawPlayback.Stats s = edt(() -> session.playback().stats());
		System.out.println("progressive playback: " + times + "; " + edt(session::playbackText));
		assertTrue("moved several times: " + s.advanced, s.advanced >= 4);
		assertTrue("every move seen, in order: " + times, times.size() >= s.advanced && consecutive(times, 5));
		assertTrue("no completed frames are made", edt(() -> session.player() == null));
	}

	@Test
	public void leavingCompletedModeReleasesItsFramesAndGivesBigDataViewerItsSourcesBack() throws Exception {
		session = OnlineDeskewSession.open(fixtureFolder(3));
		enterCompleted();
		assertTrue(session.store().stats().stagedBytes > 0);
		edtRun(() -> session.playback().play());
		edtRun(() -> session.playback().setMode(RawPlayback.Mode.PROGRESSIVE));
		assertTrue("still playing, progressively", edt(() -> session.playback().isPlaying()));
		edtRun(() -> session.playback().pause());
		RawStrictPlayer stopped = session.playersForTest().get(0);
		assertTrue(stopped.awaitStopped(5000));
		assertEquals(0, session.store().stats().stagedBytes);
		long deadline = System.currentTimeMillis() + 5000;
		boolean present = false;
		while (!present && System.currentTimeMillis() < deadline) {
			session.handle().getViewerPanel().requestRepaint();
			Thread.sleep(20);
			present = true;
			for (SourceAndConverter<?> soc : session.handle().getViewerPanel().state().getSources())
				present &= soc.getSpimSource().isPresent(0);
		}
		assertTrue("BigDataViewer draws its own sources again", present);
	}

	@Test
	public void closingWhilePlayingIsClean() throws Exception {
		session = OnlineDeskewSession.open(fixtureFolder(3));
		List<Integer> presented = enterCompleted();
		edtRun(() -> {
			session.playback().setLoop(true);
			session.playback().setTargetFps(30);
			session.playback().play();
		});
		awaitSize(presented, 3, 10000);
		edtRun(() -> session.frame().dispose());
		OnlineDeskewSession.CloseResult result = session.closed().get(15, TimeUnit.SECONDS);
		System.out.println("closed while playing: " + result);
		assertTrue(result.drained);
		assertEquals(0, result.store.after.stagedBytes);
		assertFalse(edt(() -> session.playback().isPlaying()));
	}
}
