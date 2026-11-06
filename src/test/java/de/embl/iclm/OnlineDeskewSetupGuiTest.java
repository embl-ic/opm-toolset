package de.embl.iclm;

import static org.junit.Assert.*;
import java.awt.Checkbox;
import java.awt.Choice;
import java.awt.TextField;
import java.io.File;
import javax.swing.SwingUtilities;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class OnlineDeskewSetupGuiTest {
	@Rule public TemporaryFolder folder = new TemporaryFolder();

	@Test public void theRealDialogsReadBackOrderMatchesTheMovedControls() throws Exception {
		Assume.assumeTrue(Boolean.getBoolean("opm.test.gui"));
		OnlineDeskewView.Settings settings = new OnlineDeskewView.Settings();
		settings.folder = folder.getRoot().getAbsolutePath();
		settings.parameterSource = OnlineDeskewView.PARAMETER_SOURCES[1];
		settings.dz = 0.321; settings.dxy = 0.123; settings.angle = 42.5; settings.channel = 3; settings.budgetMb = 128;
		settings.parameterFile = "restored-parameters.txt"; settings.alignmentCsv = "restored-alignment.csv";
		settings.alignChannels = false; settings.channels.combineAcquisitionChannels = false;
		java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
		boolean[] accepted = { false };
		Thread worker = Shutdown.daemon(() -> {
			try { accepted[0] = OnlineDeskewView.dialog(settings); } catch (Throwable e) { failure.set(e); }
		}, "online-setup-dialog-test");
		worker.start();
		ij.gui.GenericDialog dialog = null;
		try {
			long deadline = System.currentTimeMillis() + 10000;
			while (dialog == null && System.currentTimeMillis() < deadline) {
				for (java.awt.Window window : java.awt.Window.getWindows())
					if (window instanceof ij.gui.GenericDialog && window.isShowing()
							&& OnlineDeskewView.TITLE.equals(((ij.gui.GenericDialog) window).getTitle())) dialog = (ij.gui.GenericDialog) window;
				Thread.sleep(25);
			}
			assertNotNull(dialog);
			final ij.gui.GenericDialog shown = dialog;
			String capture = System.getProperty("opm.test.captureDir", "");
			if (!capture.isEmpty()) {
				File directory = new File(capture); assertTrue(directory.isDirectory() || directory.mkdirs());
				javax.imageio.ImageIO.write(new java.awt.Robot().createScreenCapture(shown.getBounds()), "png", new File(directory, "online-view-setup.png"));
			}
			SwingUtilities.invokeAndWait(() -> {
				assertFalse(shown.isModal());
				assertEquals(5, shown.getNumericFields().size()); assertEquals(3, shown.getStringFields().size());
				assertEquals("Z step comes before acquisition channel", "0.3210", ((TextField) shown.getNumericFields().get(0)).getText());
				shown.actionPerformed(new java.awt.event.ActionEvent(shown.getButtons()[0], java.awt.event.ActionEvent.ACTION_PERFORMED, "OK"));
			});
			worker.join(5000); assertFalse(worker.isAlive());
			if (failure.get() != null) throw new AssertionError(failure.get());
			assertTrue(accepted[0]);
			assertEquals(0.321, settings.dz, 0); assertEquals(0.123, settings.dxy, 0); assertEquals(42.5, settings.angle, 0);
			assertEquals(3, settings.channel); assertEquals(128, settings.budgetMb, 0);
			assertEquals(OnlineDeskewView.PARAMETER_SOURCES[1], settings.parameterSource);
			assertEquals("restored-parameters.txt", settings.parameterFile); assertEquals("restored-alignment.csv", settings.alignmentCsv);
			assertFalse(settings.alignChannels); assertFalse(settings.channels.combineAcquisitionChannels);
		} finally {
			final ij.gui.GenericDialog left = dialog;
			if (left != null) SwingUtilities.invokeAndWait(() -> left.windowClosing(null));
			worker.join(5000);
		}
	}

	@Test public void choicesAndArrivalsControlOnlyWhatUsesTheFields() throws Exception {
		Assume.assumeTrue(Boolean.getBoolean("opm.test.gui"));
		File txt = folder.newFile("geometry.TXT"), tif = folder.newFile("raw.tif"), csv = folder.newFile("alignment.csv");
		SwingUtilities.invokeAndWait(() -> {
			OpmDialog dialog = new OpmDialog("Online setup rule test");
			try {
				dialog.addChoice("source", OnlineDeskewView.PARAMETER_SOURCES, OnlineDeskewView.PARAMETER_SOURCES[0]);
				Choice source = (Choice) dialog.getChoices().lastElement();
				dialog.addNumericField("dz", 0.265, 3);
				TextField dz = (TextField) dialog.getNumericFields().lastElement();
				dialog.addFileField("parameters", txt.getAbsolutePath());
				TextField parameters = (TextField) dialog.getStringFields().lastElement();
				dialog.addNumericField("acquisition", 1, 0);
				TextField acquisition = (TextField) dialog.getNumericFields().lastElement();
				ChannelOperationSettings channels = new ChannelOperationSettings();
				ChannelOperationSettings.SlotRows slots = channels.addToDialog(dialog, 120);
				dialog.addCheckbox("align", false);
				Checkbox align = (Checkbox) dialog.getCheckboxes().lastElement();
				dialog.addFileField("matrix", csv.getAbsolutePath());
				TextField matrix = (TextField) dialog.getStringFields().lastElement();
				OnlineDeskewView.SetupRule rule = new OnlineDeskewView.SetupRule(source, new TextField[] { dz },
						parameters, align, matrix, acquisition, slots);
				assertEquals("restored paths do not select a mode", 0, source.getSelectedIndex());
				assertFalse(align.getState());
				assertFalse(dz.isEnabled()); assertFalse(parameters.isEditable()); assertFalse(matrix.isEditable());
				assertTrue("a grey path field can receive drops", parameters.isEnabled());
				source.select(1); rule.refresh();
				assertTrue(dz.isEnabled()); assertFalse(parameters.isEditable());
				assertFalse(rule.parameters.arrived(tif.getAbsolutePath()));
				assertEquals(txt.getAbsolutePath(), parameters.getText()); assertEquals(1, source.getSelectedIndex());
				assertTrue(rule.parameters.arrived(txt.getAbsolutePath()));
				assertEquals(2, source.getSelectedIndex()); assertTrue(parameters.isEditable()); assertFalse(dz.isEnabled());
				assertFalse(rule.parameters.arrived(folder.getRoot().getAbsolutePath()));
				assertTrue(rule.matrix.arrived(csv.getAbsolutePath()));
				assertTrue(align.getState()); assertTrue(matrix.isEditable());
				align.setState(false); rule.refresh(); assertFalse(matrix.isEditable());
				rule.refresh(); assertFalse("refresh does not undo an explicit untick", align.getState());
				matrix.setText(tif.getAbsolutePath()); rule.refresh(); assertTrue("browse/typing also selects alignment", align.getState());
				slots.combine.setState(true); rule.refresh(); assertFalse(acquisition.isEnabled());
				slots.combine.setState(false); rule.refresh(); assertTrue(acquisition.isEnabled());
			} finally { dialog.dispose(); }
		});
	}
}
