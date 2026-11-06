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
