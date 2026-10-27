package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingUtilities;

import org.junit.Assume;
import org.junit.Test;

/**
 * Completed-frame playback started as soon as a folder is open (M2-Q, §9 of its report): the view
 * checks each file's structure in the background, one after another, and a frame whose file is not
 * checked yet waits for it. The isolated smoke plays this way, the traversal harness never does - it
 * checks every file first. This plays World XY at the view's default size from the first time point
 * to the last at 10 fps right after opening, recording when each file's check finished and when each
 * frame was presented, and then plays the same pass again with every file checked.
 * <pre>
 * run-harness.ps1 -Source &lt;checkout&gt; -Out &lt;log&gt; -Test de.embl.iclm.OnlineDeskewOpeningPlaybackRealDataTest -Gate opm.test.opening
 * </pre>
 * It asserts only that every time point was presented and none skipped.
 */
public class OnlineDeskewOpeningPlaybackRealDataTest {

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

	@Test
	public void completedPlaybackRightAfterOpening() throws Exception {
		String data = System.getProperty("opm.test.opening", "");
		Assume.assumeTrue("Set -Dopm.test.opening", !data.isEmpty());
		double fps = Double.parseDouble(System.getProperty("opm.opening.fps", "10"));
		OnlineDeskewView.Settings settings = new OnlineDeskewView.Settings();
		settings.folder = data;
		settings.budgetMb = Long.getLong("opm.opening.budgetMb", 6000);
		long t0 = System.nanoTime();
		OnlineDeskewSession session = OnlineDeskewSession.open(settings);
		int count = session.times().size();
		long[] checkedAt = new long[count];		// nanos after t0 when each file was first seen checked; 0 before
		List<long[]> commits = Collections.synchronizedList(new ArrayList<long[]>());		// time index, nanos after t0
		AtomicBoolean watching = new AtomicBoolean(true);
		Thread watcher = new Thread(() -> {
			while (watching.get()) {
				for (int t = 0; t < count; t++)
					if (checkedAt[t] == 0 && session.acquisition().entry(session.times().get(t), settings.channel).readiness() != RawAcquisition.Readiness.PENDING)
						checkedAt[t] = System.nanoTime() - t0;
				try {
					Thread.sleep(20);
				} catch (InterruptedException e) {
					return;
				}
			}
		}, "test-check-watcher");
		watcher.setDaemon(true);
		watcher.start();
		List<String> rows = new ArrayList<String>();
		try {
			int[] canvas = edt(() -> new int[] { session.handle().getViewerPanel().getDisplayComponent().getWidth(),
					session.handle().getViewerPanel().getDisplayComponent().getHeight() });
			edt(() -> {
				session.playback().setMode(RawPlayback.Mode.COMPLETED);
				return null;
			});
			assertEquals(RawStrictPlayer.Outcome.PRESENTED, session.navigationResult().get(120, TimeUnit.SECONDS).outcome);
			session.presenter().setCommitObserver((r, image) -> commits.add(new long[] { r.timeIndex, System.nanoTime() - t0 }));
			long readyAt = System.nanoTime() - t0;
			for (int pass = 1; pass <= 2; pass++) {
				commits.clear();
				long start = System.nanoTime() - t0;
				edt(() -> {
					session.playback().setLoop(false);
					session.playback().setTargetFps(fps);
					session.playback().play();
					return null;
				});
				while (edt(() -> session.playback().isPlaying()) && System.nanoTime() - t0 - start < 600e9) Thread.sleep(20);
				long end = System.nanoTime() - t0;
				RawPlayback.Stats s = edt(() -> session.playback().stats());
				String status = edt(session::playbackText);
				assertEquals("every time point presented: " + status, count - 1, s.presented);
				assertEquals(0, s.skipped);
				// a movie frame waited for its file's check if the check finished after the previous frame was presented
				int waited = 0;
				double waitedMillis = 0;
				List<long[]> seen = new ArrayList<long[]>(commits);
				for (int i = 1; i < seen.size(); i++) {
					int t = (int) seen.get(i)[0];
					long previous = seen.get(i - 1)[1];
					if (checkedAt[t] > previous) {
						waited++;
						waitedMillis += (checkedAt[t] - previous) / 1e6;
					}
				}
				int checkedBefore = 0;
				for (long at : checkedAt) if (at > 0 && at <= start) checkedBefore++;
				rows.add(String.format(Locale.US, "%d\t%.2f\t%.2f\t%d\t%.1f\t%.0f\t%.0f\t%d\t%d\t%.0f\t%s", pass, start / 1e9, (end - start) / 1e9, s.presented,
						s.deliveredFps(), s.latencyMillis(0.5), s.latencyMillis(0.95), checkedBefore, waited, waitedMillis, status));
				// rewind for the second pass, every file checked by then
				long deadline = System.nanoTime() + (long) 120e9;
				while (System.nanoTime() < deadline && session.acquisition().census(session.times(), settings.channel)
						.get(RawAcquisition.Readiness.PENDING) > 0) Thread.sleep(50);
				edt(() -> {
					session.handle().getViewerPanel().setTimepoint(0);		// back to the first time point by hand
					return null;
				});
				session.navigationResult().get(120, TimeUnit.SECONDS);
			}
			long lastChecked = 0;
			for (long at : checkedAt) lastChecked = Math.max(lastChecked, at);
			System.out.printf(Locale.US, "java %s; %d time points; canvas %d x %d; budget %.0f MB; completed playback at %.0f fps; first frame shown "
					+ "%.2f s after opening began; every file checked %.2f s after, %.2f files/s%n", System.getProperty("java.version"), count, canvas[0],
					canvas[1], settings.budgetMb, fps, readyAt / 1e9, lastChecked / 1e9, count / (lastChecked / 1e9));
			System.out.println("pass\tstarted_s\tseconds\tpresented\tdelivered_fps\tlatency_p50_ms\tlatency_p95_ms\tfiles_checked_at_start\t"
					+ "frames_that_waited_for_a_check\tms_waited_in_all\tstatus");
			for (String row : rows) System.out.println(row);
		} finally {
			watching.set(false);
			watcher.interrupt();
			SwingUtilities.invokeAndWait(() -> session.frame().dispose());
			assertTrue(session.closed().get(30, TimeUnit.SECONDS).drained);
		}
	}
}
