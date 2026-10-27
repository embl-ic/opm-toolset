package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import javax.swing.JSlider;
import javax.swing.SwingUtilities;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.viewer.ViewerPanel;

/**
 * Moving the time point by hand pauses playback in both modes, and the program's own moves never
 * do (M2-C2, B2). The program marks its moves with a flag held across {@code setTimepoint}, which
 * is sound only because BigDataViewer delivers {@code timePointChanged} inside that call, on both
 * supported stacks; that is pinned here too. Needs a display:
 * {@code mvn test -Dtest=OnlineDeskewManualSeekGuiTest -Dopm.test.gui=true}.
 */
public class OnlineDeskewManualSeekGuiTest {

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
		try {
			if (session != null && !session.isClosing()) {
				SwingUtilities.invokeAndWait(() -> session.frame().dispose());
				assertTrue(session.closed().get(15, TimeUnit.SECONDS).drained);
			}
			SwingUtilities.invokeAndWait(() -> {});
		} finally {
			Thread.setDefaultUncaughtExceptionHandler(previousHandler);
		}
		if (!uncaught.isEmpty()) throw (AssertionError) uncaught.get(0);
	}

	private void open(int count) throws Exception {
		for (int t = 1; t <= count; t++)
			RawTiffFixture.write(new File(folder.getRoot(), String.format("s_Position0001_Time%06d_Channel0001_Frames_1_20.tiff", t)),
					64, 40, 20, 8, 20, (x, y, k) -> 600 + x * 7 + y * 13 + k * 29);
		try (FileWriter w = new FileWriter(new File(folder.getRoot(), "ExperimentalParameters.txt"))) {
			w.write("Interval: 5 Minute\nTilt angle: 33.5\num per galvo DU: 0.01325\nGalvo DUs per step: 20\n"
					+ "Pixel size at object /um: 0.116\nCamera ROI X0, X1, Y0, Y1: 0 64 0 40\n");
		}
		OnlineDeskewView.Settings s = new OnlineDeskewView.Settings();
		s.folder = folder.getRoot().getAbsolutePath();
		s.budgetMb = 64;
		session = OnlineDeskewSession.open(s);
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

	private ViewerPanel viewer() {
		return session.handle().getViewerPanel();
	}

	/** BigDataViewer's own time slider: the user's path to another time point. */
	private JSlider timeSlider() throws Exception {
		int last = session.times().size() - 1;
		return edt(() -> find(viewer(), last));
	}

	private static JSlider find(Container in, int max) {
		for (Component c : in.getComponents()) {
			if (c instanceof JSlider && ((JSlider) c).getMaximum() == max) return (JSlider) c;
			if (c instanceof Container) {
				JSlider s = find((Container) c, max);
				if (s != null) return s;
			}
		}
		return null;
	}

	private void playProgressive(double fps) throws Exception {
		edtRun(() -> {
			session.playback().setLoop(true);
			session.playback().setTargetFps(fps);
			session.playback().play();
		});
	}

	/** B's B2 probe, ported: a time change by hand while progressive playback runs pauses it. */
	@Test
	public void aManualTimeChangePausesProgressivePlayback() throws Exception {
		open(4);
		boolean stillPlaying = edt(() -> {
			session.playback().setLoop(true);
			session.playback().setTargetFps(1);
			session.playback().play();
			viewer().setTimepoint(2);
			return session.playback().isPlaying();
		});
		assertFalse("manual progressive time navigation must pause the movie", stillPlaying);
		assertTrue(edt(session::playbackText), edt(session::playbackText).contains("moved by hand"));
	}

	/**
	 * Acceptance item 2: the slider's own path pauses progressive playback, and the chosen time is
	 * still shown after two further periods - no automatic chain is left running.
	 */
	@Test
	public void theSliderPausesProgressivePlaybackAndTheChosenTimeStays() throws Exception {
		open(5);
		JSlider slider = timeSlider();
		assertNotNull("BigDataViewer's time slider", slider);
		playProgressive(10);
		long deadline = System.currentTimeMillis() + 5000;
		while (edt(() -> session.playback().stats().advanced) < 3 && System.currentTimeMillis() < deadline) Thread.sleep(10);
		int chosen = (edt(() -> viewer().state().getCurrentTimepoint()) + 2) % 5;
		edtRun(() -> slider.setValue(chosen));
		assertFalse("the slider pauses playback", edt(() -> session.playback().isPlaying()));
		assertFalse("and leaves no advance scheduled", edt(() -> session.playback().hasScheduledAdvance()));
		long moves = edt(() -> session.playback().stats().advanced);
		Thread.sleep(250);		// two and a half periods at 10 fps
		assertEquals("the chosen time is still shown", chosen, (int) edt(() -> viewer().state().getCurrentTimepoint()));
		assertEquals("no move after the pause", moves, (long) edt(() -> session.playback().stats().advanced));
		assertTrue(edt(session::playbackText), edt(session::playbackText).contains("moved by hand"));
	}

	/** Acceptance item 2: the program's own progressive moves never pause it. */
	@Test
	public void automaticProgressiveTicksAloneKeepPlaying() throws Exception {
		open(4);
		playProgressive(50);
		long deadline = System.currentTimeMillis() + 10000;
		while (edt(() -> session.playback().stats().advanced) < 20 && System.currentTimeMillis() < deadline) Thread.sleep(10);
		assertTrue("at least 20 moves: " + edt(() -> session.playback().stats().advanced), edt(() -> session.playback().stats().advanced) >= 20);
		assertTrue("still playing after 20 automatic moves: " + edt(session::playbackText), edt(() -> session.playback().isPlaying()));
		edtRun(() -> session.playback().pause());
	}

	/** Acceptance item 2: in completed mode, the slider following each presented frame does not pause playback. */
	@Test
	public void presentationFollowDoesNotPauseCompletedPlayback() throws Exception {
		open(4);
		edtRun(() -> session.playback().setMode(RawPlayback.Mode.COMPLETED));
		session.navigationResult().get(10, TimeUnit.SECONDS);
		List<Integer> followed = Collections.synchronizedList(new ArrayList<Integer>());
		edtRun(() -> viewer().addTimePointListener(followed::add));
		edtRun(() -> {
			session.playback().setLoop(true);
			session.playback().setTargetFps(30);
			session.playback().play();
		});
		long deadline = System.currentTimeMillis() + 10000;
		while (followed.size() < 6 && System.currentTimeMillis() < deadline) Thread.sleep(10);
		assertTrue("the slider followed six presented frames: " + followed, followed.size() >= 6);
		assertTrue("following them did not pause: " + edt(session::playbackText), edt(() -> session.playback().isPlaying()));
		edtRun(() -> session.playback().pause());
	}

	/** In completed mode, the slider still pauses and asks for the chosen time, as before. */
	@Test
	public void theSliderPausesCompletedPlaybackAndAsksForTheChosenTime() throws Exception {
		open(5);
		JSlider slider = timeSlider();
		edtRun(() -> session.playback().setMode(RawPlayback.Mode.COMPLETED));
		session.navigationResult().get(10, TimeUnit.SECONDS);
		edtRun(() -> {
			session.playback().setLoop(true);
			session.playback().setTargetFps(10);
			session.playback().play();
		});
		long deadline = System.currentTimeMillis() + 10000;
		while (edt(() -> session.playback().stats().presented) < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10);
		edtRun(() -> slider.setValue(4));
		assertFalse(edt(() -> session.playback().isPlaying()));
		assertFalse(edt(() -> session.playback().hasScheduledAdvance()));
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, session.navigationResult().get(10, TimeUnit.SECONDS).outcome);
		assertEquals(4, (int) edt(() -> session.presenter().shownRequest().timeIndex));
	}

	/**
	 * The flags that mark the program's own moves hold only if BigDataViewer reports a time change
	 * inside the call that made it. Pinned for {@code setTimepoint} and for the slider, on whichever
	 * stack runs this test (Maven's BigDataViewer 10.6.4 and Fiji's 10.6.11 both run it). Also pinned:
	 * a new listener is told the current time point at once, inside {@code addTimePointListener} -
	 * which is why the session registers its own listener as one of its own moves.
	 */
	@Test
	public void bigDataViewerReportsATimeChangeInsideTheCallThatMadeIt() throws Exception {
		open(4);
		JSlider slider = timeSlider();
		boolean[] inside = { false };
		List<Boolean> seenInside = Collections.synchronizedList(new ArrayList<Boolean>());
		edtRun(() -> {
			inside[0] = true;
			try {
				viewer().addTimePointListener(t -> seenInside.add(inside[0]));
			} finally {
				inside[0] = false;
			}
		});
		assertEquals("registration reports the current time point, inside the call: " + seenInside,
				Collections.singletonList(true), new ArrayList<Boolean>(seenInside));
		seenInside.clear();
		edtRun(() -> {
			inside[0] = true;
			try {
				viewer().setTimepoint(2);
			} finally {
				inside[0] = false;
			}
		});
		edtRun(() -> {
			inside[0] = true;
			try {
				slider.setValue(3);
			} finally {
				inside[0] = false;
			}
		});
		edtRun(() -> {});
		assertEquals("one report per change: " + seenInside, 2, seenInside.size());
		assertTrue("setTimepoint reports inside the call", seenInside.get(0));
		assertTrue("the slider reports inside the call", seenInside.get(1));
	}
}
