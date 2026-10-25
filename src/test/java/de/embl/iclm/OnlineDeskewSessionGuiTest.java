package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.viewer.ViewerPanel;
import ij.WindowManager;

/**
 * The Online Deskew View's window, for real (joint contracts C7.1 - C7.3). Needs a display:
 * {@code mvn test -Dtest=OnlineDeskewSessionGuiTest -Dopm.test.gui=true}.
 *
 * <p>Closing is exercised the way Fiji's quit does it: {@code dispose()}, which delivers
 * {@code windowClosed} but never {@code windowClosing} - the event BigDataViewer itself stops on.
 */
public class OnlineDeskewSessionGuiTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

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

	@Test
	public void theWindowOpensStepsAndClosesLikeFijisQuit() throws Exception {
		Assume.assumeTrue("Enable with -Dopm.test.gui=true", Boolean.getBoolean("opm.test.gui"));
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
		} finally {
			SwingUtilities.invokeAndWait(() -> session.frame().dispose());
		}
		RawVolumeStore.CloseResult result = session.closed().get(15, TimeUnit.SECONDS);
		assertTrue("drained: " + result, result.drained);
		assertTrue(session.isClosing());
		assertFalse("no longer listed", Arrays.asList(WindowManager.getNonImageWindows()).contains(session.frame()));
		session.close();		// a second close is harmless
		try {
			session.store().blocking(null, null);
			fail("the store still served after the window closed");
		} catch (IllegalStateException expected) {
			// closed
		}
	}
}
