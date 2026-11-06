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
		for (int time : followAfterSwitch(false)) assertTrue("follow crawled through intermediate Time " + time, time == 1 || time == 6);
	}

	/**
	 * The live case: the newest file of the position is still being written, so it is not checked yet. Checking goes backwards
	 * from it to the newest finished time point; going forward instead would start again at Time 1 and walk the backlog.
	 */
	@Test public void aNewestFileStillBeingWrittenIsPassedBackwardsNotFromTheStart() throws Exception {
		Assume.assumeTrue("Enable with -Dopm.test.gui=true", Boolean.getBoolean("opm.test.gui"));
		for (int time : followAfterSwitch(true)) assertTrue("follow crawled through intermediate Time " + time, time == 1 || time == 5);
	}

	/**
	 * Open Position 1, switch with Follow latest on to six times in Position 2, none of them checked, and return the Position 2
	 * time numbers the viewer showed until it settled on the newest finished one. With {@code newestBeingWritten}, Time 6
	 * arrives after the opening and stays being written; files there at the opening are quiet at once.
	 */
	private List<Integer> followAfterSwitch(boolean newestBeingWritten) throws Exception {
		for (int p = 1; p <= 2; p++) for (int t = 1; t <= (p == 1 ? 1 : newestBeingWritten ? 5 : 6); t++) {
			File f = new File(folder.getRoot(), String.format("g_Position%04d_Time%06d_Channel0001_Frames_1_5.tiff", p, t));
			RawTiffFixture.write(f, 32, 24, 5, 8, 5, RawTiffFixture.RAMP);
			Files.setLastModifiedTime(f.toPath(), FileTime.fromMillis(System.currentTimeMillis() - 5000));
		}
		final int newest = newestBeingWritten ? 5 : 6;
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
		if (newestBeingWritten) RawAcquisition.quietMsForTest = 60000;
		OnlineDeskewSession session = null;
		try {
			OnlineDeskewView.Settings settings = new OnlineDeskewView.Settings();
			settings.folder = folder.getRoot().getAbsolutePath();
			settings.parameterSource = OnlineDeskewView.PARAMETER_SOURCES[1];
			settings.budgetMb = 64;
			session = OnlineDeskewSession.open(settings);
			assertTrue(checkerStarted.await(5, TimeUnit.SECONDS));
			if (newestBeingWritten) {
				// as the folder watch would announce it, while the checker is held
				File late = new File(folder.getRoot(), "g_Position0002_Time000006_Channel0001_Frames_1_5.tiff");
				RawTiffFixture.write(late, 32, 24, 5, 8, 5, RawTiffFixture.RAMP);
				session.acquisition().hint(late);
				assertEquals(6, session.acquisition().snapshot(2, 1).size());
				assertTrue("Time 6 is being written", session.acquisition().snapshot(2, 1).entry(5).beingWritten(System.currentTimeMillis()));
			}
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
						&& view.presenter().shownRequest().entry.time.equals(new RawAcquisition.TimeKey(2, newest))
						&& viewer.state().getCurrentTimepoint() == newest - 1);
			}
			assertTrue("settles at the newest finished time, Time " + newest + "; shown " + shown, settled[0]);
			SwingUtilities.invokeAndWait(() -> {
				System.out.println("Position 2 time-point events while following: " + shown);
				assertTrue(view.isFollowingLatest());
			});
			return shown;
		} finally {
			release.countDown();
			OnlineDeskewSession.checkerHookForTest = null;
			RawAcquisition.quietMsForTest = -1;
			ij.Prefs.set("opm.onlineDeskew.position", remembered);
			if (session != null) {
				session.close();
				assertTrue(session.closed().get(15, TimeUnit.SECONDS).drained);
			}
		}
	}
}
