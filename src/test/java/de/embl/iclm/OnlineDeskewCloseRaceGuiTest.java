package de.embl.iclm;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.viewer.SynchronizedViewerState;
import bdv.viewer.ViewerStateChange;
import bdv.viewer.ViewerStateChangeListener;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * M2-C1-F3, deterministically: a close lands while another thread is inside BigDataViewer's
 * listener loop. A gate listener, registered after the session's, holds that thread inside the
 * loop until the session's listener has been removed - or for two seconds, which is what happens
 * once removal waits for the state's monitor. Requires opm.test.gui=true.
 */
public class OnlineDeskewCloseRaceGuiTest {
	@Rule public TemporaryFolder folder = new TemporaryFolder();
	private OnlineDeskewSession session;

	@Before public void display() {
		Assume.assumeTrue(Boolean.getBoolean("opm.test.gui"));
	}

	@After public void close() throws Exception {
		if (session != null) {
			edt(() -> { session.frame().dispose(); return null; });
			session.closed().get(15, TimeUnit.SECONDS);
		}
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

	private static Object field(Object object, String name) throws Exception {
		Field f = object.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(object);
	}

	@Test public void closingWhileAnotherThreadIsNotifyingThrowsNothingIntoThatThread() throws Exception {
		for (int t = 1; t <= 2; t++)
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
		edt(() -> { session.playback().setMode(RawPlayback.Mode.COMPLETED); return null; });
		session.navigationResult().get(10, TimeUnit.SECONDS);

		SynchronizedViewerState state = session.handle().getViewerPanel().state();
		List<?> listeners = ((org.scijava.listeners.Listeners.List<?>) state.changeListeners()).list;
		Object sessionListener = field(session, "viewListener");
		// Nothing on the event thread may wait for the state's monitor before the detach, or a pass
		// would prove nothing: the status timer reads the state, so it stops; the edit is one the
		// session compares as unchanged, so it queues no refresh.
		edt(() -> { ((javax.swing.Timer) field(session, "statusTimer")).stop(); return null; });
		CountDownLatch inside = new CountDownLatch(1);
		ViewerStateChangeListener gate = change -> {
			if (change != ViewerStateChange.CURRENT_SOURCE_CHANGED || !"probe-notifier".equals(Thread.currentThread().getName())) return;
			inside.countDown();
			long start = System.nanoTime(), deadline = start + TimeUnit.SECONDS.toNanos(2);
			boolean dumped = false;
			while (listeners.contains(sessionListener) && System.nanoTime() < deadline) {
				try { Thread.sleep(5); } catch (InterruptedException e) { return; }
				if (!dumped && System.nanoTime() - start > TimeUnit.SECONDS.toNanos(1)) {
					dumped = true;		// what keeps the event thread from the detach, if anything
					for (java.util.Map.Entry<Thread, StackTraceElement[]> t : Thread.getAllStackTraces().entrySet())
						if (t.getKey().getName().startsWith("AWT-EventQueue")) {
							System.out.println("event thread at 1 s, " + t.getKey().getState() + ":");
							for (int i = 0; i < Math.min(14, t.getValue().length); i++) System.out.println("    at " + t.getValue()[i]);
						}
				}
			}
			System.out.println("gate: removal " + (listeners.contains(sessionListener) ? "NOT seen" : "seen") + " after "
					+ (System.nanoTime() - start) / 1000000 + " ms inside the listener loop");
		};
		edt(() -> { synchronized (state) { state.changeListeners().add(gate); } return null; });
		AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();
		Thread notifier = new Thread(() -> {
			try {
				// fused mode: a state event that changes nothing a completed frame captures
				state.setCurrentSource(state.getSources().get(state.getCurrentSource() == state.getSources().get(0) ? 1 : 0));
			} catch (Throwable t) { thrown.set(t); }
		}, "probe-notifier");
		// The event thread waits in this task before the notifier starts: anything the notification
		// queues (BigDataViewer's own paint takes the state's monitor) comes after the detach.
		CountDownLatch waiting = new CountDownLatch(1), closeCalled = new CountDownLatch(1);
		SwingUtilities.invokeLater(() -> {
			waiting.countDown();
			try { if (!inside.await(10, TimeUnit.SECONDS)) return; } catch (InterruptedException e) { return; }
			closeCalled.countDown();
			session.close();		// detaches synchronously, on the event thread
		});
		assertTrue("the event thread is waiting in the close task", waiting.await(10, TimeUnit.SECONDS));
		notifier.start();
		assertTrue("the notifier is inside the listener loop", inside.await(10, TimeUnit.SECONDS));
		assertTrue("close was called while the notifier was inside", closeCalled.await(10, TimeUnit.SECONDS));
		notifier.join(15000);
		assertFalse(notifier.isAlive());
		assertTrue(session.closed().get(15, TimeUnit.SECONDS).drained);
		session = null;
		synchronized (state) { state.changeListeners().remove(gate); }
		assertFalse("the session's listener was removed", listeners.contains(sessionListener));
		assertNull("M2-C1-F3: the close threw into the editing thread: " + thrown.get(), thrown.get());
	}
}
