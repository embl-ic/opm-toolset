package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.Checkbox;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Label;
import java.awt.TextField;
import java.awt.Window;
import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;

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
			/* One event-thread step writes the summary and then the view boxes; seeing the text
			 * from this thread can land between the two, so let that step finish before reading. */
			onEdt(new Runnable() {
				@Override public void run() { }
			});
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

	/**
	 * A section folds away under its heading and comes back as it was.
	 *
	 * <p>"As it was" is the point: the Channels section holds eight slot rows of which the dialog
	 * itself shows two, so an unfold that simply showed every member would bring six unused
	 * slots up out of nowhere.
	 */
	@Test
	public void aSectionFoldsAndUnfoldsKeepingTheRowsTheDialogHid() throws Exception {
		final OpmDialog dialog = (OpmDialog) open(new Batch(), "", "Batch Processing - Deskew");
		try {
			final SectionFolds folds = dialog.folds();
			assertNotNull("the headings became folds", folds);
			final List<String> titles = new ArrayList<String>();
			for (SectionFolds.Section section : folds.sections()) {
				titles.add(section.title);
				assertTrue(section.heading.getText(), section.heading.getText().startsWith(SectionFolds.OPEN)
						|| section.heading.getText().startsWith(SectionFolds.FOLDED));
			}
			assertEquals(Arrays.asList("Input setup:", "Deskew parameters:", "Channels:", "Projection:",
					"Output setup:"), titles);

			final SectionFolds.Section channels = folds.sections().get(2);
			final Map<Component, Boolean> before = new HashMap<Component, Boolean>();
			final int[] height = new int[2];
			onEdt(new Runnable() {
				@Override public void run() {
					if (!channels.isExpanded()) folds.toggle(channels);	// a small screen may have folded it
					for (Component member : channels.members) before.put(member, member.isVisible());
					height[0] = dialog.getHeight();
					folds.toggle(channels);
					height[1] = dialog.getHeight();
				}
			});
			assertTrue("some slot rows are the dialog's own hidden ones", before.containsValue(false));
			assertTrue(channels.heading.getText(), channels.heading.getText().startsWith(SectionFolds.FOLDED));
			for (Component member : channels.members) assertFalse("folded away: " + member, member.isVisible());
			assertTrue("the dialog is shorter: " + height[1] + " < " + height[0], height[1] < height[0]);

			onEdt(new Runnable() {
				@Override public void run() { folds.toggle(channels); }
			});
			assertTrue(channels.heading.getText().startsWith(SectionFolds.OPEN));
			for (Component member : channels.members)
				assertEquals("back as it was: " + member, before.get(member), member.isVisible());
		} finally {
			cancel(dialog);
		}
	}

	/** A dialog opens folded the way it was last left. */
	@Test
	public void aDialogOpensFoldedTheWayItWasLeft() throws Exception {
		OpmDialog dialog = (OpmDialog) open(new Batch(), "", "Batch Processing - Deskew");
		final SectionFolds.Section[] parameters = new SectionFolds.Section[1];
		try {
			final SectionFolds folds = dialog.folds();
			parameters[0] = folds.sections().get(1);
			onEdt(new Runnable() {
				@Override public void run() {
					if (!parameters[0].isExpanded()) folds.toggle(parameters[0]);
					folds.toggle(parameters[0]);
				}
			});
			assertFalse(parameters[0].isExpanded());
		} finally {
			cancel(dialog);
		}
		dialog = (OpmDialog) open(new Batch(), "", "Batch Processing - Deskew");
		try {
			final SectionFolds folds = dialog.folds();
			final SectionFolds.Section again = folds.sections().get(1);
			assertEquals("Deskew parameters:", again.title);
			assertFalse("still folded", again.isExpanded());
			onEdt(new Runnable() {
				@Override public void run() { folds.toggle(again); }	// and leave it open again
			});
			assertTrue(again.isExpanded());
			assertFalse(SectionFolds.storedFolded("Batch Processing - Deskew", "Deskew parameters:"));
		} finally {
			cancel(dialog);
		}
	}

	/** Taller than its screen, a dialog folds its largest open sections until it fits. */
	@Test
	public void aDialogTooTallForItsScreenFoldsItsLargestSectionsFirst() throws Exception {
		final OpmDialog dialog = (OpmDialog) open(new Batch(), "", "Batch Processing - Deskew");
		try {
			final SectionFolds folds = dialog.folds();
			final SectionFolds.Section[] expected = new SectionFolds.Section[1];
			final SectionFolds.Section[] kept = new SectionFolds.Section[1];
			final Map<SectionFolds.Section, Boolean> open = new HashMap<SectionFolds.Section, Boolean>();
			onEdt(new Runnable() {
				@Override public void run() {
					/* From whatever the dialog opened as: on a small screen it has folded some
					 * sections already, which is this same rule at work. */
					kept[0] = folds.sectionOf(dialog.getFocusOwner());
					for (SectionFolds.Section section : folds.sections()) {
						open.put(section, section.isExpanded());
						if (section.isExpanded() && section != kept[0]
								&& (expected[0] == null || section.extent() > expected[0].extent()))
							expected[0] = section;
					}
					if (expected[0] != null) folds.fitTo(dialog.getHeight() - 1);
				}
			});
			assertNotNull("an open section to fold", expected[0]);
			for (SectionFolds.Section section : folds.sections())
				assertEquals(section.title + " (largest open is " + expected[0].title + ")",
						open.get(section) && section != expected[0], section.isExpanded());

			onEdt(new Runnable() {
				@Override public void run() {
					// focus arrives on its own after the window opens: read it with the fit, not before
					kept[0] = folds.sectionOf(dialog.getFocusOwner());
					folds.fitTo(1);
				}
			});
			for (SectionFolds.Section section : folds.sections())
				assertEquals("all but the one being worked in fold: " + section.title,
						section == kept[0], section.isExpanded());
		} finally {
			cancel(dialog);
		}
	}

	/**
	 * The Live setup's sections fold the same way, and folding composes with simple mode.
	 *
	 * <p>A folded section stays folded when advanced mode reveals rows in it, and unfolding it
	 * shows what the mode shows - not more.
	 */
	@Test
	public void theLiveSetupSectionsFoldAndComposeWithTheMode() throws Exception {
		final Constructor<LiveSetupDialog> make = LiveSetupDialog.class.getDeclaredConstructor(
				Frame.class, Parameter.class, ChannelOperationSettings.class);
		make.setAccessible(true);
		final LiveSetupDialog[] built = new LiveSetupDialog[1];
		onEdt(new Runnable() {
			@Override public void run() {
				try {
					built[0] = make.newInstance(null, new Parameter("junit-fold-live"), new ChannelOperationSettings());
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		});
		final LiveSetupDialog dialog = built[0];
		try {
			final JButton output = heading(dialog, "Output setup:");
			final JButton mode = button(dialog, "advanced mode", "simple mode");
			final JLabel saveTo = jlabel(dialog, "save to");
			final JLabel ifExists = jlabel(dialog, "if result exists");
			onEdt(new Runnable() {
				@Override public void run() { if (mode.getText().equals("simple mode")) mode.doClick(); }
			});
			assertTrue(output.getText().startsWith(SectionFolds.OPEN));
			assertTrue(saveTo.isVisible());
			assertFalse("an advanced row, hidden in simple mode", ifExists.isVisible());

			final int[] height = new int[2];
			onEdt(new Runnable() {
				@Override public void run() {
					height[0] = dialog.getHeight();
					output.doClick();
					height[1] = dialog.getHeight();
				}
			});
			assertTrue(output.getText().startsWith(SectionFolds.FOLDED));
			assertFalse(saveTo.isVisible());
			assertTrue("shorter: " + height[1] + " < " + height[0], height[1] < height[0]);

			onEdt(new Runnable() {
				@Override public void run() { mode.doClick(); }	// to advanced
			});
			assertFalse("advanced mode does not open a folded section", ifExists.isVisible());
			onEdt(new Runnable() {
				@Override public void run() { output.doClick(); }
			});
			assertTrue(saveTo.isVisible());
			assertTrue("unfolded in advanced mode, its advanced rows show", ifExists.isVisible());
			onEdt(new Runnable() {
				@Override public void run() { mode.doClick(); }	// back to simple
			});
			assertFalse(ifExists.isVisible());
		} finally {
			onEdt(new Runnable() {
				@Override public void run() { dialog.dispose(); }
			});
		}
	}

	/**
	 * The viewer's <b>Export...</b> sits on the Region row, beside the controls that choose what
	 * it exports: <b>Set region...</b>, <b>Whole volume</b> and <b>Materialise with ROI...</b>.
	 */
	@Test
	public void theViewerExportButtonIsOnTheRegionRow() throws Exception {
		// the constructor that does not scan the folder browsed last time
		final Constructor<OpmDataViewer> make =
				OpmDataViewer.class.getDeclaredConstructor(boolean.class);
		make.setAccessible(true);
		final OpmDataViewer[] built = new OpmDataViewer[1];
		onEdt(new Runnable() {
			@Override public void run() {
				try { built[0] = make.newInstance(Boolean.FALSE); }
				catch (Exception e) { throw new RuntimeException(e); }
			}
		});
		final OpmDataViewer viewer = built[0];
		try {
			JButton export = button(viewer, "Export...");
			JButton materialise = button(viewer, "Materialise with ROI...");
			assertEquals("the same row as the region controls", materialise.getParent(), export.getParent());
			List<Component> row = Arrays.asList(export.getParent().getComponents());
			assertEquals("and to the right of it", row.indexOf(materialise) + 1, row.indexOf(export));
			for (String expected : new String[] { "Set region...", "Whole volume" })
				assertEquals(expected + " is on it too", export.getParent(),
						button(viewer, expected).getParent());

			/* And it exports what the window is showing: the volume, or the chosen projection
			 * movie. An OME-Zarr export used to take the volume whatever the controls said. */
			final java.lang.reflect.Method viewKey =
					OpmDataViewer.class.getDeclaredMethod("selectedViewKey");
			viewKey.setAccessible(true);
			assertEquals(TiffResultDataset.VOLUME, viewKey.invoke(viewer));
			onEdt(new Runnable() {
				@Override public void run() { select(viewer, "openMode", OpmDataViewer.OpenMode.PROJECTION); }
			});
			assertEquals("with no dataset loaded there is no projection to name",
					TiffResultDataset.VOLUME, viewKey.invoke(viewer));
			onEdt(new Runnable() {
				@Override public void run() { addAndSelect(viewer, "projections", "maxZ"); }
			});
			assertEquals("the projection the list names", "maxZ", viewKey.invoke(viewer));
			onEdt(new Runnable() {
				@Override public void run() { select(viewer, "openMode", OpmDataViewer.OpenMode.VOLUME_ALL); }
			});
			assertEquals("and the volume again when the mode says so",
					TiffResultDataset.VOLUME, viewKey.invoke(viewer));
		} finally {
			onEdt(new Runnable() {
				@Override public void run() { viewer.dispose(); }
			});
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

	/**
	 * A label with this text, or a section heading - which once the dialog is shown is a flat
	 * button in the label's place, and is compared without its fold marker.
	 */
	private static boolean containsLabel(GenericDialog dialog, String text) {
		for (Label label : labels(dialog)) if (label.getText().trim().equals(text)) return true;
		for (Component c : all(dialog))
			if (c instanceof AbstractButton && text.equals(SectionFolds.title(((AbstractButton) c).getText()).trim()))
				return true;
		return false;
	}

	private static void onEdt(Runnable work) throws Exception {
		EventQueue.invokeAndWait(work);
	}

	/** One of the viewer's own controls, by field name. */
	private static JComboBox<?> combo(Object owner, String name) {
		try {
			java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
			field.setAccessible(true);
			return (JComboBox<?>) field.get(owner);
		} catch (Exception failure) {
			throw new RuntimeException(failure);
		}
	}

	private static void select(Object owner, String name, Object item) {
		combo(owner, name).setSelectedItem(item);
	}

	/** Put an item in a list a loaded dataset would have filled, and choose it. */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static void addAndSelect(Object owner, String name, String item) {
		JComboBox box = combo(owner, name);
		box.addItem(item);
		box.setSelectedItem(item);
	}

	/** The Live setup's heading button for a section, found by its title. */
	/**
	 * The automatic channel layout owns the controls it decides, and says so by greying them.
	 *
	 * <p>Three states, all of them reachable by a user: reading the names and assigning the
	 * slots, reading the names but leaving the slots alone, and neither. What must never happen
	 * is a row that looks editable while the listener is about to overwrite it.
	 */
	@Test
	public void theAutomaticChannelLayoutGreysWhatItDecides() throws Exception {
		final Constructor<LiveSetupDialog> make = LiveSetupDialog.class.getDeclaredConstructor(
				Frame.class, Parameter.class, ChannelOperationSettings.class);
		make.setAccessible(true);
		final ChannelOperationSettings channels = new ChannelOperationSettings();
		channels.autoCombineChannels = true;
		channels.autoChannelAssignment = true;
		channels.combineAcquisitionChannels = true;
		final LiveSetupDialog[] built = new LiveSetupDialog[1];
		onEdt(new Runnable() {
			@Override public void run() {
				try {
					built[0] = make.newInstance(null, new Parameter("junit-auto-live"), channels);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		});
		final LiveSetupDialog dialog = built[0];
		try {
			final JCheckBox auto = checkbox(dialog, "automatic combine matching _Channel#### files");
			final JCheckBox assign = checkbox(dialog, "auto channel assignment");
			final JCheckBox combine = checkbox(dialog, "combine matching _Channel#### files");
			final JButton fewer = button(dialog, "-");
			final JButton more = button(dialog, "+");
			final JLabel first = jlabel(dialog, ChannelOperationSettings.slotLabel(1));

			assertTrue(auto.isSelected());
			assertTrue("the run fills the tick, so the user does not", !combine.isEnabled());
			assertTrue(assign.isEnabled());
			assertFalse("assigned automatically", first.isEnabled());
			assertFalse(fewer.isEnabled());
			assertFalse(more.isEnabled());

			// automatic combining, manual slots: the slots come back
			onEdt(new Runnable() {
				@Override public void run() { assign.doClick(); }
			});
			assertTrue("the slots are the user's again", first.isEnabled());
			assertFalse("but the combine tick is still the run's", combine.isEnabled());

			// nothing automatic: the dialog is the one it always was
			onEdt(new Runnable() {
				@Override public void run() { auto.doClick(); }
			});
			assertTrue(combine.isEnabled());
			assertFalse("auto assignment means nothing without it", assign.isEnabled());
			assertTrue(first.isEnabled());
		} finally {
			onEdt(new Runnable() {
				@Override public void run() { dialog.dispose(); }
			});
		}
	}

	private static JButton heading(Container root, String title) {
		for (Component c : all(root))
			if (c instanceof JButton && title.equals(SectionFolds.title(((JButton) c).getText()))) return (JButton) c;
		throw new AssertionError("no heading " + title);
	}

	private static JButton button(Container root, String... texts) {
		for (Component c : all(root))
			if (c instanceof JButton && Arrays.asList(texts).contains(((AbstractButton) c).getText())) return (JButton) c;
		throw new AssertionError("no button " + Arrays.toString(texts));
	}

	private static JCheckBox checkbox(Container root, String text) {
		for (Component c : all(root))
			if (c instanceof JCheckBox && text.equals(((JCheckBox) c).getText())) return (JCheckBox) c;
		throw new AssertionError("no check box " + text);
	}

	private static JLabel jlabel(Container root, String text) {
		for (Component c : all(root))
			if (c instanceof JLabel && text.equals(((JLabel) c).getText())) return (JLabel) c;
		throw new AssertionError("no label " + text);
	}

	private static List<Component> all(Container root) {
		List<Component> found = new ArrayList<Component>();
		for (Component child : root.getComponents()) {
			found.add(child);
			if (child instanceof Container) found.addAll(all((Container) child));
		}
		return found;
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
