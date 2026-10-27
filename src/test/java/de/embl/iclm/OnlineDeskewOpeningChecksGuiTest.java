package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingUtilities;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The view's file checks as playback meets them right after opening (M2-Q-F1), with every check
 * slowed down on purpose, and nothing timed: the time point asked for is checked before the ones
 * in between, a frame waits for its own check and is then presented, and playback started before
 * the checks have caught up presents every time point in order and skips none. Needs a display:
 * {@code -Dopm.test.gui=true}.
 */
public class OnlineDeskewOpeningChecksGuiTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

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
		OnlineDeskewSession.checkerHookForTest = null;
		try {
			if (session != null && !session.isClosing()) {
				SwingUtilities.invokeAndWait(() -> session.frame().dispose());
				assertTrue(session.closed().get(15, TimeUnit.SECONDS).drained);
			}
		} finally {
			Thread.setDefaultUncaughtExceptionHandler(previousHandler);
		}
		if (!uncaught.isEmpty()) throw (AssertionError) uncaught.get(0);
	}

	private OnlineDeskewView.Settings fixture(int count) throws Exception {
		for (int t = 1; t <= count; t++)
			RawTiffFixture.write(new File(folder.getRoot(), String.format("o_Position0001_Time%06d_Channel0001_Frames_1_20.tiff", t)),
					64, 40, 20, 8, 20, (x, y, k) -> 600 + x * 7 + y * 13 + k * 29);
		try (FileWriter w = new FileWriter(new File(folder.getRoot(), "ExperimentalParameters.txt"))) {
			w.write("Interval: 5 Minute\nTilt angle: 33.5\num per galvo DU: 0.01325\nGalvo DUs per step: 20\n"
					+ "Pixel size at object /um: 0.116\nCamera ROI X0, X1, Y0, Y1: 0 64 0 40\n");
		}
		OnlineDeskewView.Settings s = new OnlineDeskewView.Settings();
		s.folder = folder.getRoot().getAbsolutePath();
		s.budgetMb = 64;
		return s;
	}

	private static <T> T edt(Callable<T> work) throws Exception {
		final Object[] value = new Object[1];
		final Exception[] failure = new Exception[1];
		SwingUtilities.invokeAndWait(() -> {
			try {
				value[0] = work.call();
			} catch (Exception e) {
				failure[0] = e;
			}
		});
		if (failure[0] != null) throw failure[0];
		@SuppressWarnings("unchecked") T t = (T) value[0];
		return t;
	}

	private boolean checked(int t) {
		return session.acquisition().entry(session.times().get(t), 1).readiness() != RawAcquisition.Readiness.PENDING;
	}

	/**
	 * The checker is held at its first round until a far time point has been asked for: the next
	 * file it checks is that one, and the frame waiting for it is presented before any file in
	 * between has been checked.
	 */
	@Test
	public void theTimePointAskedForIsCheckedFirstAndThenPresented() throws Exception {
		final int count = 8, far = 6;
		CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
		AtomicBoolean first = new AtomicBoolean(true);
		List<Integer> order = Collections.synchronizedList(new ArrayList<Integer>());
		boolean[] seen = new boolean[count];
		OnlineDeskewSession.checkerHookForTest = () -> {
			if (first.compareAndSet(true, false)) {
				held.countDown();
				try {
					release.await(20, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			if (session == null) return;
			for (int t = 0; t < count; t++)		// one file is checked per round: the order is exact
				if (!seen[t] && checked(t)) {
					seen[t] = true;
					order.add(t);
				}
		};
		session = OnlineDeskewSession.open(fixture(count));
		assertTrue("the checker began", held.await(10, TimeUnit.SECONDS));
		for (int t = 0; t < count; t++) if (checked(t)) {
			seen[t] = true;		// checked while opening
		}
		CompletableFuture<RawStrictPlayer.Result> asked = edt(() -> {
			session.playback().setMode(RawPlayback.Mode.COMPLETED);
			return session.requestStrictFrame(far);
		});
		release.countDown();
		RawStrictPlayer.Result result = asked.get(20, TimeUnit.SECONDS);
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, result.outcome);
		assertEquals(far, result.request.timeIndex);
		long deadline = System.currentTimeMillis() + 10000;
		while (order.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10);
		assertEquals("the time point asked for is the first file checked: " + order, Integer.valueOf(far), order.get(0));
	}

	/**
	 * Completed playback started right after opening, every check slowed so that playback runs
	 * ahead of them: every time point is presented once, in order, none skipped as unavailable.
	 */
	@Test
	public void playbackRightAfterOpeningWaitsForEachCheckAndSkipsNothing() throws Exception {
		final int count = 8;
		CountDownLatch release = new CountDownLatch(1);
		OnlineDeskewSession.checkerHookForTest = () -> {
			try {
				release.await(20, TimeUnit.SECONDS);		// no check until Play has been pressed
				Thread.sleep(60);		// then every round slower than a frame at 30 fps
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		};
		session = OnlineDeskewSession.open(fixture(count));
		edt(() -> {
			session.playback().setMode(RawPlayback.Mode.COMPLETED);
			return null;
		});
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, session.navigationResult().get(20, TimeUnit.SECONDS).outcome);
		List<Integer> commits = Collections.synchronizedList(new ArrayList<Integer>());
		session.presenter().setCommitObserver((r, image) -> commits.add(r.timeIndex));
		int unchecked = 0;
		for (int t = 1; t < count; t++) if (!checked(t)) unchecked++;
		edt(() -> {
			session.playback().setTargetFps(30);
			session.playback().play();
			return null;
		});
		release.countDown();
		long deadline = System.currentTimeMillis() + 30000;
		while (edt(() -> session.playback().isPlaying()) && System.currentTimeMillis() < deadline) Thread.sleep(10);
		RawPlayback.Stats s = edt(() -> session.playback().stats());
		System.out.println("playback right after opening, " + unchecked + " of " + (count - 1) + " files unchecked at Play: " + commits + "; "
				+ edt(session::playbackText));
		assertTrue("playback ran ahead of the checks", unchecked > 0);
		assertEquals("every time point presented", count - 1, s.presented);
		assertEquals("none skipped", 0, s.skipped);
		List<Integer> expected = new ArrayList<Integer>();
		for (int t = 1; t < count; t++) expected.add(t);
		assertEquals("in order, each once", expected, new ArrayList<Integer>(commits));
	}
}
