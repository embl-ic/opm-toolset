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
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.SwingUtilities;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.viewer.ViewerPanel;
import ij.WindowManager;
import net.imglib2.RandomAccess;
import net.imglib2.type.numeric.integer.UnsignedShortType;

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
 * <li>A file rewritten under the view is withheld and the status card says so.</li>
 * </ul>
 */
public class OnlineDeskewSessionGuiTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Before
	public void needsADisplay() {
		Assume.assumeTrue("Enable with -Dopm.test.gui=true", Boolean.getBoolean("opm.test.gui"));
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
			assertFalse(viewer.state().getSources().get(0).getSpimSource().isPresent(1));
			assertTrue(viewer.state().getSources().get(0).getSpimSource().isPresent(2));
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
			session.store().blocking(null, null);
			fail("the store still served after the window closed");
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

	@Test
	public void aFileRewrittenUnderTheViewIsWithheldAndSaid() throws Exception {
		final OnlineDeskewSession session = OnlineDeskewSession.open(fixtureFolder());
		try {
			Thread.sleep(500);
			final RawAcquisition.Entry shown = session.acquisition().entry(session.times().get(0), 1);
			assertTrue(shown.viewable());
			// rewrite the first plane in place, same size, and move its time on
			RawTiffLayout layout = shown.layout();
			ByteBuffer replacement = ByteBuffer.allocate(2 * layout.width * layout.height).order(ByteOrder.LITTLE_ENDIAN);
			while (replacement.hasRemaining()) replacement.putShort((short) 900);
			replacement.flip();
			try (FileChannel out = FileChannel.open(shown.file.toPath(), StandardOpenOption.WRITE)) {
				long at = layout.offset(0, 0, 0);
				while (replacement.hasRemaining()) at += out.write(replacement, at);
			}
			Files.setLastModifiedTime(shown.file.toPath(), FileTime.fromMillis(shown.key().modified + 2000));
			long deadline = System.currentTimeMillis() + 5000;
			while (shown.readiness() != RawAcquisition.Readiness.CHANGED && System.currentTimeMillis() < deadline)
				Thread.sleep(50);
			assertEquals("the shown file's change was noticed without a read", RawAcquisition.Readiness.CHANGED, shown.readiness());
			assertFalse(session.handle().getViewerPanel().state().getSources().get(0).getSpimSource().isPresent(0));
			Thread.sleep(1200);
			assertTrue(session.statusText(), session.statusText().contains("changed after it was checked"));
			assertTrue(session.statusText(), session.statusText().contains("1 changed"));
		} finally {
			SwingUtilities.invokeAndWait(() -> session.frame().dispose());
			session.closed().get(15, TimeUnit.SECONDS);
		}
	}
}
