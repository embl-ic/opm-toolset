package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.Checkbox;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Label;
import java.awt.TextField;
import java.awt.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import ij.process.ShortProcessor;

/**
 * The batch dialogs, opened for real: each builds, reads as it should, and is non-blocking.
 *
 * <p>What the headless tests cannot reach is the dialog code itself - the components captured
 * as they are added, the live folder scan, the casts - which only runs with a display. Each
 * command is started on a thread of its own, exactly as ImageJ would, inspected, and cancelled.
 * Enable with {@code -Dopm.test.gui=true}.
 */
public class BatchDialogsGuiTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Before
	public void needsADisplay() {
		Assume.assumeTrue("Enable with -Dopm.test.gui=true", Boolean.getBoolean("opm.test.gui"));
		Assume.assumeFalse(GraphicsEnvironment.isHeadless());
	}

	@Test
	public void batchDeskewPreviewsOnlyVirtually() throws Exception {
		GenericDialog dialog = open(new Batch(), "", "Batch Processing - Deskew");
		try {
			assertFalse("a dialog opened on the plugin thread does not block the desktop", dialog.isModal());
			List<String> boxes = checkboxLabels(dialog);
			assertTrue(boxes.toString(), boxes.contains("projection view (virtual)"));
			assertFalse("the separate virtual box is gone", boxes.contains("virtual"));
		} finally {
			cancel(dialog);
		}
	}

	@Test
	public void channelOperationScansTheFolderAndOffersItsViews() throws Exception {
		File results = folder.newFolder("results");
		writeResult(new File(results, "deskew/sample_Time00001-deskewed.tif"), 3);
		writeResult(new File(results, "maxZ/sample_Time00001-deskewed-maxZprojection.tif"), 1);

		GenericDialog dialog = open(new BatchChannelOperation(), "", "Batch Processing - Channel Operation");
		try {
			assertFalse(dialog.isModal());
			setText((TextField) dialog.getStringFields().get(0), results.getAbsolutePath());
			/* The dialog scans the folder it remembers first; the typed one supersedes it, so wait
			 * for that folder's own line rather than for the first scan to land. */
			Label summary = waitForLabel(dialog, "Found 1 TIFF result");
			assertTrue(summary.getText(), summary.getText().contains("1 TIFF result"));
			List<Checkbox> views = viewBoxes(dialog);
			assertEquals(7, views.size());
			assertTrue("the volume is there", views.get(0).isEnabled() && views.get(0).getState());
			assertTrue("maxZ is there", views.get(3).isEnabled() && views.get(3).getState());
			assertTrue("maxX can be recomputed from the volume", views.get(1).isEnabled());
			assertFalse("but is not ticked, since the folder holds none", views.get(1).getState());
			assertTrue(containsLabel(dialog, "[-]") || hasButton(dialog, "-"));
		} finally {
			cancel(dialog);
		}
	}

	@Test
	public void formatConversionSaysWhatTheTargetWillSkip() throws Exception {
		File data = folder.newFolder("data");
		writeResult(new File(data, "packed.tif"), 3);	// deflated by the writer

		GenericDialog dialog = open(new FormatConversion(), "", "Batch Processing - Format Conversion");
		try {
			assertFalse(dialog.isModal());
			for (Object field : dialog.getNumericFields() == null ? new ArrayList<Object>() : dialog.getNumericFields())
				assertFalse("no Deflate level is offered", String.valueOf(field).contains("Deflate"));
			setText((TextField) dialog.getStringFields().get(0), data.getAbsolutePath());
			waitForLabel(dialog, "Found 1 other TIFF volume");
			setChoice(dialog, FormatConversion.TARGET_TIFF);
			Label gate = waitForLabel(dialog, "already deflated TIFF");
			assertNotNull(gate);
		} finally {
			cancel(dialog);
		}
	}

	@Test
	public void generateProjectionHasTheDeskewSections() throws Exception {
		GenericDialog dialog = open(new BatchProjection(), "", "Batch Processing - Generate Projection Image");
		try {
			assertFalse(dialog.isModal());
			for (String section : new String[] { "Input setup:", "Deskew parameters:", "Channels:",
					"Projection:", "Output setup:" })
				assertTrue(section, containsLabel(dialog, section));
			assertEquals(Parameter.CHANNEL_OPTIONS.length, ((java.awt.Choice) dialog.getChoices().get(1)).getItemCount());
		} finally {
			cancel(dialog);
		}
	}

	@Test
	public void batchDeconvolutionIsNonBlockingWithSections() throws Exception {
		GenericDialog dialog = open(new BatchDeconvolution(), "", "Batch Processing - Deconvolution");
		try {
			assertFalse(dialog.isModal());
			assertTrue(containsLabel(dialog, "Output setup:"));
		} finally {
			cancel(dialog);
		}
	}


	// ---- helpers --------------------------------------------------------------------

	private static GenericDialog open(final PlugIn command, final String arg, String title) throws Exception {
		Thread runner = new Thread(new Runnable() {
			@Override public void run() { command.run(arg); }
		}, "test-" + title);
		runner.setDaemon(true);
		runner.start();
		long until = System.currentTimeMillis() + 20000;
		while (System.currentTimeMillis() < until) {
			for (Window window : Window.getWindows())
				if (window instanceof GenericDialog && window.isShowing()
						&& title.equals(((GenericDialog) window).getTitle()))
					return (GenericDialog) window;
			Thread.sleep(50);
		}
		throw new AssertionError("no dialog titled " + title + " appeared");
	}

	private static void cancel(final GenericDialog dialog) throws Exception {
		EventQueue.invokeAndWait(new Runnable() {
			@Override public void run() { dialog.windowClosing(null); }
		});
	}

	private static void setText(final TextField field, final String text) throws Exception {
		EventQueue.invokeAndWait(new Runnable() {
			@Override public void run() { field.setText(text); }
		});
	}

	private static void setChoice(final GenericDialog dialog, final String item) throws Exception {
		EventQueue.invokeAndWait(new Runnable() {
			@Override public void run() {
				for (Object choice : dialog.getChoices()) {
					java.awt.Choice c = (java.awt.Choice) choice;
					for (int i = 0; i < c.getItemCount(); i++)
						if (item.equals(c.getItem(i))) {
							c.select(i);
							dialog.itemStateChanged(new java.awt.event.ItemEvent(c,
									java.awt.event.ItemEvent.ITEM_STATE_CHANGED, item,
									java.awt.event.ItemEvent.SELECTED));
							return;
						}
				}
			}
		});
	}

	private static Label waitForLabel(GenericDialog dialog, String fragment) throws Exception {
		long until = System.currentTimeMillis() + 10000;
		while (System.currentTimeMillis() < until) {
			for (Label label : labels(dialog)) if (label.getText().contains(fragment)) return label;
			Thread.sleep(50);
		}
		throw new AssertionError("no label containing '" + fragment + "' in " + labelTexts(dialog));
	}

	/** The seven view boxes: the checkboxes that come after the view heading. */
	private static List<Checkbox> viewBoxes(GenericDialog dialog) {
		List<Checkbox> views = new ArrayList<Checkbox>();
		for (Object box : dialog.getCheckboxes()) {
			String label = ((Checkbox) box).getLabel();
			if (label.equals("deskewed volume") || label.matches("(max|mean)[XYZ]"))
				views.add((Checkbox) box);
		}
		return views;
	}

	private static List<String> checkboxLabels(GenericDialog dialog) {
		List<String> labels = new ArrayList<String>();
		for (Object box : dialog.getCheckboxes()) labels.add(((Checkbox) box).getLabel());
		return labels;
	}

	private static boolean containsLabel(GenericDialog dialog, String text) {
		for (Label label : labels(dialog)) if (label.getText().trim().equals(text)) return true;
		return false;
	}

	private static boolean hasButton(Container container, String text) {
		for (Component child : container.getComponents()) {
			if (child instanceof java.awt.Button && text.equals(((java.awt.Button) child).getLabel())) return true;
			if (child instanceof Container && hasButton((Container) child, text)) return true;
		}
		return false;
	}

	private static List<Label> labels(Container container) {
		List<Label> found = new ArrayList<Label>();
		for (Component child : container.getComponents()) {
			if (child instanceof Label) found.add((Label) child);
			if (child instanceof Container) found.addAll(labels((Container) child));
		}
		return found;
	}

	private static List<String> labelTexts(GenericDialog dialog) {
		List<String> texts = new ArrayList<String>();
		for (Label label : labels(dialog)) texts.add(label.getText());
		return texts;
	}

	private static void writeResult(File file, int depth) {
		file.getParentFile().mkdirs();
		ImageStack stack = new ImageStack(16, 8);
		for (int z = 0; z < depth; z++) stack.addSlice(null, new ShortProcessor(16, 8));
		ImagePlus image = new ImagePlus(file.getName().replaceAll("[.]tif$", ""), stack);
		assertTrue(VolumeIO.saveTiff(image, file));
	}
}
