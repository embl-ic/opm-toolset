package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.SwingUtilities;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.viewer.Source;
import bdv.viewer.ViewerPanel;
import ij.WindowManager;
import net.imglib2.RandomAccess;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.volatiles.VolatileUnsignedShortType;

/**
 * The Online Deskew View's window, for real (joint contracts C7.1 - C7.3). Needs a display:
 * {@code mvn test -Dtest=OnlineDeskewSessionGuiTest -Dopm.test.gui=true}.
 *
 * <ul>
 * <li>Fiji's quit is {@code dispose()}, which delivers {@code windowClosed} and never
 *     {@code windowClosing}; the close button delivers both.</li>
 * <li>Agent-B's close-button probe, inverted: with BigDataViewer's painter thread held, so that
 *     stopping the renderer cannot finish, the close button returns at once and the event thread
 *     goes on running other work. Holding the painter thread's monitor is the probe's own
 *     technique; the reflection is confined to this test.</li>
 * <li>A renderer that does not stop within the deadline, and a read that outlasts the store's
 *     deadline, are reported as such, never as a clean close.</li>
 * <li>A file rewritten under the view is withheld and the status card says so; files rewritten
 *     and deleted while the view paints and steps are withdrawn from both halves together.</li>
 * <li><b>Every test fails on an exception thrown on the event thread or a renderer thread.</b>
 *     JUnit sees only the test thread, so an exception thrown while Swing paints passed unnoticed
 *     here once, though it was in the log (agent-B's C2). The collector below makes it a failure.</li>
 * </ul>
 */
public class OnlineDeskewSessionGuiTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private final List<Throwable> uncaught = Collections.synchronizedList(new ArrayList<Throwable>());
	private Thread.UncaughtExceptionHandler previousHandler;

	@Before
	public void needsADisplay() {
		Assume.assumeTrue("Enable with -Dopm.test.gui=true", Boolean.getBoolean("opm.test.gui"));
		previousHandler = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
			uncaught.add(new AssertionError("uncaught on " + thread.getName() + ": " + e, e));
			e.printStackTrace();
		});
	}

	/** An exception on the event thread or a renderer thread fails the test that caused it. */
	@After
	public void noExceptionEscapedOnAnotherThread() throws Exception {
		if (!Boolean.getBoolean("opm.test.gui")) return;
		SwingUtilities.invokeAndWait(() -> {});		// what the event thread had queued has run
		Thread.setDefaultUncaughtExceptionHandler(previousHandler);
		if (!uncaught.isEmpty()) throw (AssertionError) uncaught.get(0);
	}

	/** Every source of the view says the same about time index {@code t}, and that is {@code present}. */
	private static boolean allSources(OnlineDeskewSession session, int t, boolean present) {
		for (bdv.viewer.SourceAndConverter<?> soc : session.handle().getViewerPanel().state().getSources()) {
			Source<?> s = soc.getSpimSource();
			if (s.isPresent(t) != present) return false;
			if (soc.asVolatile() != null && soc.asVolatile().getSpimSource().isPresent(t) != present) return false;
		}
		return true;
	}

	/** Wait until the view has drawn a frame without time index {@code t} - both halves, blocking and volatile. */
	private static void awaitWithdrawn(OnlineDeskewSession session, int t) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (!allSources(session, t, false) && System.currentTimeMillis() < deadline) {
			session.handle().getViewerPanel().requestRepaint();
			Thread.sleep(20);
		}
		assertTrue("time index " + t + " withdrawn from every source", allSources(session, t, false));
	}

	/** Rewrite the first plane of a file in place, same size, and move its time on. */
	private static void rewrite(RawAcquisition.Entry e, int value) throws Exception {
		RawTiffLayout layout = e.layout();
		ByteBuffer replacement = ByteBuffer.allocate(2 * layout.width * layout.height).order(ByteOrder.LITTLE_ENDIAN);
		while (replacement.hasRemaining()) replacement.putShort((short) value);
		replacement.flip();
		try (FileChannel out = FileChannel.open(e.file.toPath(), StandardOpenOption.WRITE)) {
			long at = layout.offset(0, 0, 0);
			while (replacement.hasRemaining()) at += out.write(replacement, at);
		}
		Files.setLastModifiedTime(e.file.toPath(), FileTime.fromMillis(e.key().modified + 2000));
	}

	private OnlineDeskewView.Settings fixtureFolder() throws Exception {
		for (int t = 1; t <= 3; t++)
			RawTiffFixture.write(new File(folder.getRoot(), String.format("g_Position0001_Time%06d_Channel0001_Frames_1_9.tiff", t)),
					26, 17, 9, 8, t == 2 ? 4 : 9, RawTiffFixture.RAMP);		// time point 2 is cut short
		try (FileWriter w = new FileWriter(new File(folder.getRoot(), "ExperimentalParameters.txt"))) {
			w.write("Interval: 5 Minute\nTilt angle: 33.5\num per galvo DU: 0.01325\nGalvo DUs per step: 20\n"
					+ "Pixel size at object /um: 0.116\nCamera ROI X0, X1, Y0, Y1: 0 26 0 17\n");
		}
		OnlineDeskewView.Settings s = new OnlineDeskewView.Settings();
		s.folder = folder.getRoot().getAbsolutePath();
		s.budgetMb = 64;
		return s;
	}

	/** The close button, as the window system sends it: on the event thread, timed. */
	private static long pressClose(final OnlineDeskewSession session) throws Exception {
		final AtomicLong millis = new AtomicLong();
		SwingUtilities.invokeAndWait(() -> {
			long start = System.nanoTime();
			session.frame().dispatchEvent(new WindowEvent(session.frame(), WindowEvent.WINDOW_CLOSING));
			millis.set((System.nanoTime() - start) / 1000000);
		});
		return millis.get();
	}

	/** How long the event thread takes to run something posted to it now. */
	private static long heartbeat() throws Exception {
		final CountDownLatch beat = new CountDownLatch(1);
		long start = System.nanoTime();
		SwingUtilities.invokeLater(beat::countDown);
		assertTrue("the event thread ran nothing for 5 s", beat.await(5, TimeUnit.SECONDS));
		return (System.nanoTime() - start) / 1000000;
	}

	@Test
	public void theWindowOpensStepsAndClosesLikeFijisQuit() throws Exception {
		final OnlineDeskewSession session = OnlineDeskewSession.open(fixtureFolder());
		try {
			assertTrue(session.frame().isVisible());
			assertTrue(session.frame().getTitle().contains(OnlineDeskewView.MENU));
			assertTrue("listed in Fiji's Window menu", Arrays.asList(WindowManager.getNonImageWindows()).contains(session.frame()));
			final ViewerPanel viewer = session.handle().getViewerPanel();
			assertEquals(2, viewer.state().getSources().size());
			assertEquals(3, viewer.state().getNumTimepoints());
			// the cut time point is never shown; the complete ones are
			long deadline = System.currentTimeMillis() + 10000;
			while (session.acquisition().entry(session.times().get(1), 1).readiness() == RawAcquisition.Readiness.PENDING
					&& System.currentTimeMillis() < deadline) Thread.sleep(20);
			// presence is decided as a frame starts: let one be drawn after the checks
			deadline = System.currentTimeMillis() + 5000;
			while (!allSources(session, 2, true) && System.currentTimeMillis() < deadline) {
				viewer.requestRepaint();
				Thread.sleep(20);
			}
			assertTrue("the complete one is shown by every source", allSources(session, 2, true));
			assertTrue("the cut one by none", allSources(session, 1, false));
			SwingUtilities.invokeAndWait(() -> viewer.setTimepoint(2));
			Thread.sleep(1200);		// the status card refreshes every second
			assertTrue(session.statusText(), session.statusText().contains("Time000003"));
			assertTrue(session.statusText(), session.statusText().contains("MB"));
		} finally {
			SwingUtilities.invokeAndWait(() -> session.frame().dispose());
		}
		OnlineDeskewSession.CloseResult result = session.closed().get(15, TimeUnit.SECONDS);
		assertTrue("every step within its deadline: " + result, result.drained);
		assertTrue(session.isClosing());
		assertFalse("the checking worker stopped", session.checker().isAlive());
		assertFalse("no longer listed", Arrays.asList(WindowManager.getNonImageWindows()).contains(session.frame()));
		assertEquals("nothing accounted after the close", 0, result.store.after.totalBytes());
		long loads = session.store().stats().loads;
		session.close();		// a second close is harmless
		try {
			RawAcquisition.Entry first = session.acquisition().entry(session.times().get(0), 1);
			RandomAccess<UnsignedShortType> a = session.store().blocking(first.key(), first.layout()).randomAccess();
			a.setPosition(new long[] { 1, 1, 1 });
			a.get().get();
			fail("the store still read after the window closed");
		} catch (IllegalStateException expected) {
			// closed
		}
		assertEquals("no read after the close", loads, session.store().stats().loads);
	}

	/** The painter thread's monitor: holding it keeps BigDataViewer's stop() from finishing its join. */
	private static Object painter(OnlineDeskewSession session) throws Exception {
		Field field = ViewerPanel.class.getDeclaredField("painterThread");
		field.setAccessible(true);
		return field.get(session.handle().getViewerPanel());
	}

	@Test
	public void theCloseButtonNeverHoldsTheEventThread() throws Exception {
		final OnlineDeskewSession session = OnlineDeskewSession.open(fixtureFolder());
		Thread.sleep(500);
		long dispatch, beat;
		boolean closingAtOnce;
		Object painter = painter(session);
		synchronized (painter) {
			dispatch = pressClose(session);
			closingAtOnce = session.isClosing();
			beat = heartbeat();
			Thread.sleep(1500);		// the renderer is slow to stop, within the deadline
		}
		OnlineDeskewSession.CloseResult result = session.closed().get(15, TimeUnit.SECONDS);
		System.out.printf("close button with the renderer held 1.5 s: dispatch %d ms, event-thread heartbeat %d ms; %s%n",
				dispatch, beat, result);
		assertTrue("the close button returned in " + dispatch + " ms", dispatch < 500);
		assertTrue("closing began at the press", closingAtOnce);
		assertTrue("the event thread ran other work after " + beat + " ms", beat < 500);
		assertTrue("the renderer stopped: " + result, result.viewerStopped);
		assertTrue("its stop was waited for off the event thread: " + result, result.viewerMillis >= 1000);
		assertTrue(result.drained);
		assertFalse("the window went away", session.frame().isDisplayable());
	}

	@Test
	public void aRendererThatDoesNotStopInTimeIsReportedNotWaitedFor() throws Exception {
		final OnlineDeskewSession session = OnlineDeskewSession.open(fixtureFolder());
		Thread.sleep(500);
		OnlineDeskewSession.CloseResult result;
		long beat;
		Object painter = painter(session);
		synchronized (painter) {
			pressClose(session);
			beat = heartbeat();
			// the session's close completes on its deadline while the renderer is still held
			result = session.closed().get(OnlineDeskewSession.STOP_DEADLINE_MS + TiffRawVolumeStore.CLOSE_DEADLINE_MS + 3000,
					TimeUnit.MILLISECONDS);
		}
		System.out.println("renderer held past the deadline: heartbeat " + beat + " ms; " + result);
		assertTrue(beat < 500);
		assertFalse("reported as not stopped: " + result, result.viewerStopped);
		assertFalse("so not a clean close", result.drained);
		assertTrue("the store closed regardless: " + result, result.store.drained);
	}

	@Test
	public void aSlowReadDuringTheCloseIsReportedNotWaitedFor() throws Exception {
		final OnlineDeskewSession session = OnlineDeskewSession.open(fixtureFolder());
		Thread.sleep(500);
		final CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
		session.store().setReadHook(cell -> {
			reading.countDown();
			try {
				release.await(TiffRawVolumeStore.CLOSE_DEADLINE_MS + 5000, TimeUnit.MILLISECONDS);
			} catch (InterruptedException never) {
				throw new IllegalStateException("an IO thread was interrupted");
			}
		});
		final RawAcquisition.Entry last = session.acquisition().entry(session.times().get(2), 1);
		assertEquals(RawAcquisition.Readiness.COMPLETE, last.validate());
		Thread reader = new Thread(() -> {
			try {
				RandomAccess<UnsignedShortType> a = session.store().blocking(last.key(), last.layout()).randomAccess();
				a.setPosition(new long[] { 3, 16, 8 });		// a strip the view has not needed
				a.get().get();
			} catch (RuntimeException closedUnderIt) {
				// expected once the store closes
			}
		});
		reader.setDaemon(true);
		reader.start();
		try {
			assertTrue(reading.await(5, TimeUnit.SECONDS));
			long dispatch = pressClose(session);
			long beat = heartbeat();
			OnlineDeskewSession.CloseResult result = session.closed().get(TiffRawVolumeStore.CLOSE_DEADLINE_MS + 5000,
					TimeUnit.MILLISECONDS);
			System.out.printf("slow read during the close: dispatch %d ms, heartbeat %d ms; %s%n", dispatch, beat, result);
			assertTrue(dispatch < 500 && beat < 500);
			assertFalse("the store reports the read it could not drain: " + result, result.store.drained);
			assertTrue("the read held is among those abandoned: " + result, result.store.abandonedReads >= 1);
			assertFalse(result.drained);
		} finally {
			release.countDown();
		}
	}

	/**
	 * C2's race, provoked: files are rewritten and deleted while the view paints and steps between
	 * time points, a thread asking for frames all the while. Nothing may throw on any thread; each
	 * changed time point leaves every source together; a fresh volatile access of it is never
	 * valid; and a time point left alone still shows its values.
	 */
	@Test
	public void filesChangedAndDeletedWhileTheViewPaintsAndStepsThrowNothing() throws Exception {
		for (int t = 1; t <= 6; t++)
			RawTiffFixture.write(new File(folder.getRoot(), String.format("s_Position0001_Time%06d_Channel0001_Frames_1_9.tiff", t)),
					26, 17, 9, 8, 9, RawTiffFixture.RAMP);
		try (FileWriter w = new FileWriter(new File(folder.getRoot(), "ExperimentalParameters.txt"))) {
			w.write("Interval: 5 Minute\nTilt angle: 33.5\num per galvo DU: 0.01325\nGalvo DUs per step: 20\n"
					+ "Pixel size at object /um: 0.116\nCamera ROI X0, X1, Y0, Y1: 0 26 0 17\n");
		}
		OnlineDeskewView.Settings settings = new OnlineDeskewView.Settings();
		settings.folder = folder.getRoot().getAbsolutePath();
		settings.budgetMb = 64;
		final OnlineDeskewSession session = OnlineDeskewSession.open(settings);
		final ViewerPanel viewer = session.handle().getViewerPanel();
		final AtomicBoolean stop = new AtomicBoolean();
		Thread painter = new Thread(() -> {
			while (!stop.get()) {
				viewer.requestRepaint();
				try {
					Thread.sleep(3);
				} catch (InterruptedException end) {
					return;
				}
			}
		});
		painter.start();
		try {
			for (int t = 0; t < 5; t++) {
				final int shown = t, next = t + 1;
				RawAcquisition.Entry e = session.acquisition().entry(session.times().get(t), 1);
				assertEquals(RawAcquisition.Readiness.COMPLETE, e.validate());
				SwingUtilities.invokeAndWait(() -> viewer.setTimepoint(shown));
				Thread.sleep(150);		// drawn, and its cells being read
				if (t % 2 == 0) rewrite(e, 900);
				else Files.delete(e.file.toPath());
				// step back and forth across it while its change is found
				long deadline = System.currentTimeMillis() + 5000;
				for (int i = 0; e.readiness() != RawAcquisition.Readiness.CHANGED && System.currentTimeMillis() < deadline; i++) {
					final int to = i % 3 == 2 ? next : shown;
					SwingUtilities.invokeAndWait(() -> viewer.setTimepoint(to));
					Thread.sleep(15);
				}
				assertEquals(e.file.getName() + " withdrawn", RawAcquisition.Readiness.CHANGED, e.readiness());
				SwingUtilities.invokeAndWait(() -> viewer.setTimepoint(shown));
				awaitWithdrawn(session, shown);
				RandomAccess<VolatileUnsignedShortType> fresh = ((Source<VolatileUnsignedShortType>) viewer.state().getSources()
						.get(0).asVolatile().getSpimSource()).getSource(shown, 0).randomAccess();
				fresh.setPosition(new long[] { 2, 2, 2 });
				assertFalse("a changed time point's pixels are never valid", fresh.get().isValid());
			}
			// the last time point was left alone and still shows its values
			SwingUtilities.invokeAndWait(() -> viewer.setTimepoint(5));
			Source<VolatileUnsignedShortType> left = (Source<VolatileUnsignedShortType>) viewer.state().getSources().get(0)
					.asVolatile().getSpimSource();
			long deadline = System.currentTimeMillis() + 5000;
			boolean shows = false;
			while (!shows && System.currentTimeMillis() < deadline) {
				RandomAccess<VolatileUnsignedShortType> a = left.getSource(5, 0).randomAccess();
				a.setPosition(new long[] { 4, 6, 2 });
				shows = left.isPresent(5) && a.get().isValid() && a.get().get().get() == RawTiffFixture.RAMP.at(4, 6, 2);
				if (!shows) Thread.sleep(20);
			}
			assertTrue("an untouched time point still shows its values", shows);
			Thread.sleep(1200);
			assertTrue(session.statusText(), session.statusText().contains("5 changed"));
		} finally {
			stop.set(true);
			painter.join(5000);
			SwingUtilities.invokeAndWait(() -> session.frame().dispose());
			session.closed().get(15, TimeUnit.SECONDS);
		}
	}

	@Test
	public void aFileRewrittenUnderTheViewIsWithheldAndSaid() throws Exception {
		final OnlineDeskewSession session = OnlineDeskewSession.open(fixtureFolder());
		try {
			Thread.sleep(500);
			final RawAcquisition.Entry shown = session.acquisition().entry(session.times().get(0), 1);
			assertTrue(shown.viewable());
			rewrite(shown, 900);
			long deadline = System.currentTimeMillis() + 5000;
			while (shown.readiness() != RawAcquisition.Readiness.CHANGED && System.currentTimeMillis() < deadline)
				Thread.sleep(50);
			assertEquals("the shown file's change was noticed without a read", RawAcquisition.Readiness.CHANGED, shown.readiness());
			awaitWithdrawn(session, 0);
			Thread.sleep(1200);
			assertTrue(session.statusText(), session.statusText().contains("changed after it was checked"));
			assertTrue(session.statusText(), session.statusText().contains("1 changed"));
		} finally {
			SwingUtilities.invokeAndWait(() -> session.frame().dispose());
			session.closed().get(15, TimeUnit.SECONDS);
		}
	}
}
