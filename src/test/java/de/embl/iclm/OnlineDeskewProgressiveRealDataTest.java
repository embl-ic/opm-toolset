package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import javax.swing.JFrame;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;

import org.junit.Assume;
import org.junit.Test;

import bdv.viewer.ViewerPanel;

/**
 * Progressive playback qualified in a real BigDataViewer window (M2-Q, Q2): the same 50 files at the
 * protocol's 10 fps in World XY, XZ and YZ, on a 1600 x 1000 canvas where the screen allows it.
 * For each plane, a pass from the first time point to the last: the moves delivered per second,
 * the moves with placeholder activity since the previous move (the M2-C2 diagnostic, which is not a
 * count of incomplete frames), the placeholders handed out and the bytes read; then, separately,
 * how long the last time point takes to settle once playback has stopped - a refinement observation.
 * Then a second pass interrupted by a slider event posted to the event thread: how long until it
 * runs and pauses playback, and that the chosen time point stays. Heap after a collection, threads
 * and process handles around each plane, and the close.
 *
 * <p>Progressive playback promises a fixed rate of moves, not complete frames; nothing here asserts
 * a rate or a time. It asserts only behaviour: the slider pauses playback and the time stays, the
 * counter never exceeds the moves, and the view closes having drained. Run outside Maven, for
 * timings without JaCoCo, with the shared kit:
 * <pre>
 * run-harness.ps1 -Source &lt;checkout&gt; -Out &lt;log&gt; -Test de.embl.iclm.OnlineDeskewProgressiveRealDataTest -Gate opm.test.progressive
 * </pre>
 * Optional: {@code -Dopm.progressive.planes=XY,XZ,YZ -Dopm.progressive.fps=10 -Dopm.progressive.budgetMb=6000}.
 */
public class OnlineDeskewProgressiveRealDataTest {

	private static final int W = 1600, H = 1000;

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

	private static long heapAfterCollection() throws InterruptedException {
		System.gc();
		System.gc();
		Thread.sleep(200);
		return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
	}

	/** Until no cell is being read and none was loaded for a second; the seconds that took. */
	private static double settle(TiffRawVolumeStore store) throws InterruptedException {
		long start = System.nanoTime(), quietSince = System.nanoTime(), last = -1;
		while (System.nanoTime() - start < 120e9) {
			RawVolumeStore.CacheStats s = store.stats();
			if (s.loads != last || s.inFlight > 0) {
				last = s.loads;
				quietSince = System.nanoTime();
			} else if (System.nanoTime() - quietSince > 1e9) break;
			Thread.sleep(20);
		}
		return (quietSince - start) / 1e9;
	}

	@Test
	public void progressivePlaybackInARealWindow() throws Exception {
		String data = System.getProperty("opm.test.progressive", "");
		Assume.assumeTrue("Set -Dopm.test.progressive", !data.isEmpty());
		String[] planes = System.getProperty("opm.progressive.planes", "XY,XZ,YZ").split(",");
		double fps = Double.parseDouble(System.getProperty("opm.progressive.fps", "10"));
		int threadsBefore = ManagementFactory.getThreadMXBean().getThreadCount();
		long handlesBefore = RawPlaybackTraversalRealDataTest.handles();

		OnlineDeskewView.Settings settings = new OnlineDeskewView.Settings();
		settings.folder = data;
		settings.budgetMb = Long.getLong("opm.progressive.budgetMb", 6000);
		long openStart = System.nanoTime();
		OnlineDeskewSession session = OnlineDeskewSession.open(settings);
		double openSeconds = (System.nanoTime() - openStart) / 1e9;
		List<String> rows = new ArrayList<String>();
		try {
			ViewerPanel viewer = session.handle().getViewerPanel();
			JFrame frame = session.frame();
			Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
			int[] canvas = edt(() -> {
				for (int i = 0; i < 3; i++) {		// grow the frame by what the canvas lacks, within the screen
					Component c = viewer.getDisplayComponent();
					int fw = Math.min(screen.width, frame.getWidth() + W - c.getWidth());
					int fh = Math.min(screen.height, frame.getHeight() + H - c.getHeight());
					frame.setBounds(screen.x, screen.y, fw, fh);
					frame.validate();
				}
				return new int[] { viewer.getDisplayComponent().getWidth(), viewer.getDisplayComponent().getHeight() };
			});
			int count = session.times().size();
			long checkStart = System.nanoTime();
			while (session.acquisition().census(session.times(), settings.channel).get(RawAcquisition.Readiness.PENDING) > 0
					&& System.nanoTime() - checkStart < 600e9) Thread.sleep(100);
			double checkSeconds = (System.nanoTime() - checkStart) / 1e9;
			assertEquals("every file checked", 0, (int) session.acquisition().census(session.times(), settings.channel)
					.get(RawAcquisition.Readiness.PENDING));
			System.out.printf(Locale.US, "java %s; %d time points; opened in %.2f s, every file checked %.1f s later; canvas %d x %d "
					+ "(screen %d x %d); budget %.0f MB; %s; progressive at %.0f fps%n", System.getProperty("java.version"), count, openSeconds,
					checkSeconds, canvas[0], canvas[1], screen.width, screen.height, settings.budgetMb, settings.interpolation, fps);
			JSlider slider = edt(() -> find(viewer, count - 1));
			assertNotNull("BigDataViewer's time slider", slider);
			TiffRawVolumeStore store = session.store();

			for (String name : planes) {
				RawWorldView plane = RawWorldView.valueOf(name.trim());
				edt(() -> {
					session.worldAction(plane).actionPerformed(null);
					viewer.setTimepoint(0);
					return null;
				});
				double firstSettle = settle(store);
				long heapBefore = heapAfterCollection();
				int threadsAtPlane = ManagementFactory.getThreadMXBean().getThreadCount();

				// a whole pass, first time point to last
				RawVolumeStore.CacheStats before = store.stats();
				long placeholdersBefore = store.placeholdersHandedOut();
				long start = System.nanoTime();
				edt(() -> {
					session.playback().setLoop(false);
					session.playback().setTargetFps(fps);
					session.playback().play();
					return null;
				});
				while (edt(() -> session.playback().isPlaying()) && System.nanoTime() - start < 300e9) Thread.sleep(10);
				double seconds = (System.nanoTime() - start) / 1e9;
				RawPlayback.Stats s = edt(() -> session.playback().stats());
				String status = edt(session::playbackText);
				long placeholders = store.placeholdersHandedOut() - placeholdersBefore;
				RawVolumeStore.CacheStats after = store.stats();
				// refinement, recorded apart: how long the last time point takes to settle after playback stopped
				long placeholdersAtStop = store.placeholdersHandedOut();
				double lastSettle = settle(store);
				long placeholdersSettling = store.placeholdersHandedOut() - placeholdersAtStop;
				assertTrue("the counter never exceeds the moves: " + status, s.movesWithPlaceholderActivity <= s.advanced);
				assertEquals("reached the last time point", count - 1, (int) edt(() -> viewer.state().getCurrentTimepoint()));

				// a second pass, interrupted by the slider as the user moves it
				edt(() -> {
					viewer.setTimepoint(0);
					return null;
				});
				settle(store);
				edt(() -> {
					session.playback().play();
					return null;
				});
				Thread.sleep(2000);
				int chosen = (edt(() -> viewer.state().getCurrentTimepoint()) + count / 2) % count;
				final long[] times = new long[3];
				final boolean[] pausedAt = new boolean[1];
				times[0] = System.nanoTime();
				SwingUtilities.invokeLater(() -> {
					times[1] = System.nanoTime();
					slider.setValue(chosen);
					pausedAt[0] = !session.playback().isPlaying();
					times[2] = System.nanoTime();
				});
				long waitStart = System.nanoTime();
				while (times[2] == 0 && System.nanoTime() - waitStart < 30e9) Thread.sleep(1);
				double queued = (times[1] - times[0]) / 1e6, handled = (times[2] - times[1]) / 1e6;
				long movesAtPause = edt(() -> session.playback().stats().advanced);
				Thread.sleep((long) (2500 / fps));
				assertTrue("the slider paused playback", pausedAt[0]);
				assertFalse(edt(() -> session.playback().isPlaying()));
				assertEquals("the chosen time point stays", chosen, (int) edt(() -> viewer.state().getCurrentTimepoint()));
				assertEquals("no move after the pause", movesAtPause, (long) edt(() -> session.playback().stats().advanced));
				settle(store);
				long heapAfter = heapAfterCollection();

				rows.add(String.format(Locale.US, "%s\t%d\t%.2f\t%.2f\t%d\t%d\t%.0f\t%.2f\t%d\t%.1f\t%.2f\t%.0f\t%.0f\t%d\t%d\t%.0f\t%.1f",
						plane, s.advanced, seconds, s.advanced / seconds, s.movesWithPlaceholderActivity, placeholders,
						(after.bytesRead - before.bytesRead) / 1e6, lastSettle, placeholdersSettling, firstSettle, queued + handled,
						heapBefore / 1e6, heapAfter / 1e6, threadsAtPlane, ManagementFactory.getThreadMXBean().getThreadCount(),
						after.peakTotalBytes / 1e6, queued));
				System.out.println("plane " + plane + ": " + status);
			}
		} finally {
			long closeStart = System.nanoTime();
			SwingUtilities.invokeAndWait(() -> session.frame().dispose());
			OnlineDeskewSession.CloseResult closed = session.closed().get(30, TimeUnit.SECONDS);
			double closeWall = (System.nanoTime() - closeStart) / 1e3 / 1e3;
			int threadsAfter = ManagementFactory.getThreadMXBean().getThreadCount();
			for (long deadline = System.currentTimeMillis() + 5000; threadsAfter > threadsBefore + 2 && System.currentTimeMillis() < deadline;) {
				Thread.sleep(50);
				threadsAfter = ManagementFactory.getThreadMXBean().getThreadCount();
			}
			rows.add(String.format(Locale.US, "closed: %s; %.0f ms from the close button to drained; threads %d before opening, %d after; "
					+ "process handles %d before, %d after", closed, closeWall, threadsBefore, threadsAfter, handlesBefore,
					RawPlaybackTraversalRealDataTest.handles()));
			System.out.println();
			System.out.println("plane\tmoves\tseconds\tmoves_per_s\tmoves_with_placeholder_activity\tplaceholders\tread_MB\tlast_settle_s\t"
					+ "placeholders_settling\tplane_settle_s\tslider_to_paused_ms\theap_before_MB\theap_after_MB\tthreads_before\tthreads_after\t"
					+ "store_peak_MB\tslider_queued_ms");
			for (String row : rows) System.out.println(row);
			assertTrue("closed and drained: " + closed, closed.drained);
		}
	}
}
