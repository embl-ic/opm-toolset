package de.embl.iclm;

import static org.junit.Assert.*;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import bdv.tools.brightness.ConverterSetup;
import bdv.ui.sourcegrouptree.SourceGroupTree;
import bdv.ui.sourcetable.SourceTable;
import bdv.viewer.Source;
import net.imglib2.realtransform.AffineTransform3D;

public class OnlineDeskewAutoContrastGuiTest {
	@Rule public TemporaryFolder folder = new TemporaryFolder();

	@Test public void sourceAndGroupRowsHaveAutoAndOnlySelectedSourcesChange() throws Exception {
		Assume.assumeTrue(Boolean.getBoolean("opm.test.gui"));
		File file = new File(folder.getRoot(), "auto_Position0001_Time000001_Channel0001_Frames_1_3.tiff");
		RawTiffFixture.write(file, 256, 128, 3, 8, 3, (x, y, z) -> {
			int i = x % 128 + y * 128;
			return i == 0 ? 0 : i == 1 ? 10000 : 1000 + i % 1000;
		});
		OnlineDeskewView.Settings settings = new OnlineDeskewView.Settings();
		settings.folder = folder.getRoot().getAbsolutePath(); settings.parameterSource = OnlineDeskewView.PARAMETER_SOURCES[1]; settings.budgetMb = 64;
		OnlineDeskewSession session = OnlineDeskewSession.open(settings);
		try {
			Component cards = session.handle().getCardPanel().getComponent();
			SourceTable table = OnlineDeskewAutoContrast.find(cards, SourceTable.class);
			SourceGroupTree tree = OnlineDeskewAutoContrast.find(cards, SourceGroupTree.class);
			List<JButton> buttons = new ArrayList<JButton>(); collectAuto(cards, buttons);
			assertEquals("Sources and Groups", 2, buttons.size());
			JButton auto = OnlineDeskewAutoContrast.find(table.getParent().getParent().getParent(), JButton.class);
			// Match the Auto button to the card containing the table, not another editor button.
			for (JButton button : buttons) if (OnlineDeskewAutoContrast.find(button.getParent().getParent(), SourceTable.class) != null) auto = button;
			assertNotNull(auto);
			final JButton sourceAuto = auto;
			ConverterSetup one = session.handle().getConverterSetups().getConverterSetup(session.handle().getViewerPanel().state().getSources().get(0));
			ConverterSetup two = session.handle().getConverterSetups().getConverterSetup(session.handle().getViewerPanel().state().getSources().get(1));
			Source<?> source = session.handle().getViewerPanel().state().getSources().get(0).getSpimSource();
			AffineTransform3D sourceToWorld = new AffineTransform3D(); source.getSourceTransform(0, 0, sourceToWorld);
			AffineTransform3D screen = new AffineTransform3D(); screen.scale(2); screen.translate(20, 20, -2);
			AffineTransform3D camera = screen.concatenate(sourceToWorld.inverse());
			SwingUtilities.invokeAndWait(() -> {
				session.handle().getViewerPanel().state().setViewerTransform(camera);
				table.clearSelection(); tree.clearSelection();
				for (JButton button : buttons) assertFalse("no selected row", button.isEnabled());
				table.getSelectionModel().setSelectionInterval(0, 0);
				assertTrue(sourceAuto.isEnabled()); assertFalse(sourceAuto.isFocusable());
				one.setDisplayRange(10, 60000); two.setDisplayRange(17, 7777);
			});
			int[] pixels = OnlineDeskewAutoContrast.sample(source, 0, camera,
					session.handle().getViewerPanel().getDisplay().getWidth(), session.handle().getViewerPanel().getDisplay().getHeight(), () -> false);
			assertTrue(pixels.length > 1000);
			OnlineDeskewAutoContrast.Range expected = OnlineDeskewAutoContrast.autoRange(pixels, 0);
			click(sourceAuto);
			assertEquals(expected.min, one.getDisplayRangeMin(), 0); assertEquals(expected.max, one.getDisplayRangeMax(), 0);
			assertEquals(17, two.getDisplayRangeMin(), 0); assertEquals(7777, two.getDisplayRangeMax(), 0);
			boolean reset = false;
			for (int i = 0; i < 12; i++) {
				expected = OnlineDeskewAutoContrast.autoRange(pixels, expected.stage);
				click(sourceAuto);
				assertEquals(expected.min, one.getDisplayRangeMin(), 0); assertEquals(expected.max, one.getDisplayRangeMax(), 0);
				if (expected.stage == 0) { reset = true; break; }
			}
			assertTrue("clicks reach the full range", reset);
			click(sourceAuto);
			expected = OnlineDeskewAutoContrast.autoRange(pixels, 0);
			assertEquals(expected.max, one.getDisplayRangeMax(), 0);
			assertEquals(7777, two.getDisplayRangeMax(), 0);
		} finally { session.close(); assertTrue(session.closed().get(15, TimeUnit.SECONDS).drained); }
	}

	private static void click(JButton button) throws Exception {
		SwingUtilities.invokeAndWait(button::doClick);
		long deadline = System.currentTimeMillis() + 10000;
		boolean[] done = { false };
		while (!done[0] && System.currentTimeMillis() < deadline) {
			Thread.sleep(20); SwingUtilities.invokeAndWait(() -> done[0] = button.isEnabled());
		}
		assertTrue("Auto worker finishes", done[0]);
	}

	private static void collectAuto(Component c, List<JButton> buttons) {
		if (c instanceof JButton && "opm.online.auto".equals(c.getName())) buttons.add((JButton) c);
		if (c instanceof Container) for (Component child : ((Container) c).getComponents()) collectAuto(child, buttons);
	}
}
