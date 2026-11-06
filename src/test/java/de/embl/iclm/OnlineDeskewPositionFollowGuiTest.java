package de.embl.iclm;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import bdv.viewer.ViewerPanel;

/** Follow latest must validate the end of an unchecked position before its intervening times. */
public class OnlineDeskewPositionFollowGuiTest {
	@Rule public TemporaryFolder folder = new TemporaryFolder();

	@Test public void followsTheNewestAfterAPositionSwitchWithoutWalkingTheBacklog() throws Exception {
		Assume.assumeTrue("Enable with -Dopm.test.gui=true", Boolean.getBoolean("opm.test.gui"));
		for (int p = 1; p <= 2; p++) for (int t = 1; t <= (p == 1 ? 1 : 6); t++) {
			File f = new File(folder.getRoot(), String.format("g_Position%04d_Time%06d_Channel0001_Frames_1_5.tiff", p, t));
			RawTiffFixture.write(f, 32, 24, 5, 8, 5, RawTiffFixture.RAMP);
			Files.setLastModifiedTime(f.toPath(), FileTime.fromMillis(System.currentTimeMillis() - 5000));
		}
		CountDownLatch checkerStarted = new CountDownLatch(1), release = new CountDownLatch(1);
		OnlineDeskewSession.checkerHookForTest = () -> {
			checkerStarted.countDown();
			try {
				if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("checker was not released");
				// Let each publication reach Swing before the next check; the test must observe a crawl.
				Thread.sleep(600);
				SwingUtilities.invokeAndWait(() -> {});
			} catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
			catch (Exception failure) { throw new RuntimeException(failure); }
		};
		double remembered = ij.Prefs.get("opm.onlineDeskew.position", 1);
		OnlineDeskewSession session = null;
		try {
			OnlineDeskewView.Settings settings = new OnlineDeskewView.Settings();
			settings.folder = folder.getRoot().getAbsolutePath();
			settings.parameterSource = OnlineDeskewView.PARAMETER_SOURCES[1];
			settings.budgetMb = 64;
			session = OnlineDeskewSession.open(settings);
			assertTrue(checkerStarted.await(5, TimeUnit.SECONDS));
			final OnlineDeskewSession view = session;
			for (RawAcquisition.Entry entry : session.acquisition().snapshot(2, 1).entries)
				assertEquals("position 2 starts unchecked", RawAcquisition.Readiness.PENDING, entry.readiness());
			List<Integer> shown = new ArrayList<Integer>();
			ViewerPanel viewer = session.handle().getViewerPanel();
			SwingUtilities.invokeAndWait(() -> {
				viewer.timePointListeners().add(t -> {
					RawAcquisition.TimeKey key = view.snapshot().time(t);
					if (key != null && key.position == 2) shown.add(key.timeId);
				});
				view.playback().setMode(RawPlayback.Mode.COMPLETED);
				view.setFollowLatest(true);
				view.switchPosition(2);
			});
			release.countDown();
			long deadline = System.currentTimeMillis() + 15000;
			boolean[] settled = { false };
			while (!settled[0] && System.currentTimeMillis() < deadline) {
				Thread.sleep(25);
				SwingUtilities.invokeAndWait(() -> settled[0] = view.presenter().shownRequest() != null
						&& view.presenter().shownRequest().entry.time.equals(new RawAcquisition.TimeKey(2, 6))
						&& viewer.state().getCurrentTimepoint() == 5);
			}
			assertTrue("settles at the newest time", settled[0]);
			SwingUtilities.invokeAndWait(() -> {
				System.out.println("Position 2 time-point events while following: " + shown);
				assertTrue(view.isFollowingLatest());
			});
			for (int time : shown) assertTrue("follow crawled through intermediate Time " + time + ": " + shown,
					time == 1 || time == 6);
		} finally {
			release.countDown();
			OnlineDeskewSession.checkerHookForTest = null;
			ij.Prefs.set("opm.onlineDeskew.position", remembered);
			if (session != null) {
				session.close();
				assertTrue(session.closed().get(15, TimeUnit.SECONDS).drained);
			}
		}
	}
}
