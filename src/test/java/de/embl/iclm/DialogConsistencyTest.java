package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * The dialogs of the toolset read as one design, and only block where they have to.
 *
 * <p>Read from the sources, like {@link CommandCoverageTest}, so a dialog added later is held to
 * the same rules the day it is added.
 */
public class DialogConsistencyTest {

	private static final File SOURCES = new File("src/main/java/de/embl/iclm");

	/** The commands under Batch Processing that open a dialog of their own. */
	private static final List<String> BATCH_DIALOGS = Arrays.asList(
			"BatchProjection.java", "BatchChannelOperation.java", "BatchDeconvolution.java",
			"FormatConversion.java");

	/**
	 * Every dialog that is still modal, and why it has to be.
	 * <p>
	 * A {@code NonBlockingGenericDialog} waits in {@code showDialog()} for its own OK. On the
	 * plugin thread that is what makes it non-blocking; on the event thread - a dialog raised
	 * from another window's button - nothing can press OK, because the wait holds the thread
	 * that would deliver the press. So a dialog opened from inside another window stays modal.
	 */
	private static final Set<String> MODAL = new TreeSet<String>(Arrays.asList(
			// raised from a button of another window, on the event thread
			"Parameter.java: Save Deskew Setting",
			"Parameter.java: Beads Image Preparation",
			"OpmDataViewer.java: OME-Zarr viewer channel setup",
			"OpmDataViewer.java: Materialise TIFF result",
			"OpmDataViewer.java: Materialise OME-Zarr",
			"OpmDataViewer.java: OME-Zarr region",	// "TIFF result region" for a TIFF
			"OpmDataViewer.java: Materialise OME-Zarr ROI",
			"OpmDataViewer.java: Materialise TIFF ROI",
			"OpmDataViewer.java: Export region",		// the Region row's Export..., same reason
			// commands with no menu entry any more; unreachable, left as they were
			"Parameter.java: OPM Folder Watcher",
			"Parameter.java: OPM Processing Setup",
			"Parameter.java: OPM TCP-IP Listener",
			"Batch2.java: Deskew Batch2 Processing"));

	@Test
	public void everyBatchProcessingDialogIsTitledAsOne() throws Exception {
		List<String> titles = new ArrayList<String>();
		for (String name : BATCH_DIALOGS) titles.addAll(titles(read(name), "OpmDialog"));
		titles.addAll(titles(read("Parameter.java"), "OpmDialog"));
		for (String expected : new String[] { "Batch Processing - Deskew",
				"Batch Processing - Generate Projection Image", "Batch Processing - Channel Operation",
				"Batch Processing - Deconvolution", "Batch Processing - Format Conversion" })
			assertTrue(expected + " among " + titles, titles.contains(expected) || containsConstant(expected));
	}

	@Test
	public void batchProcessingDialogsAreNonBlockingAndHaveHelp() throws Exception {
		for (String name : BATCH_DIALOGS) {
			String source = read(name);
			assertFalse(name + " builds a modal dialog", source.contains("new OpmDialogPlus("));
			assertTrue(name + " builds no non-blocking dialog", source.contains("new OpmDialog("));
			assertTrue(name + " offers no Help", source.contains("addHelp("));
		}
		String deskew = method(read("Parameter.java"), "public boolean deskew_batch");
		assertTrue(deskew.contains("new OpmDialog(\"Batch Processing - Deskew\")"));
		assertTrue(deskew.contains("addHelp("));
	}

	@Test
	public void onlyDialogsRaisedFromAnotherWindowStayModal() throws Exception {
		Set<String> found = new TreeSet<String>();
		File[] files = SOURCES.listFiles();
		for (File file : files) {
			if (!file.getName().endsWith(".java") || file.getName().startsWith("OpmDialog")) continue;
			for (String title : titles(read(file.getName()), "OpmDialogPlus"))
				found.add(file.getName() + ": " + title);
		}
		assertEquals("a new modal dialog has to be justified here, or be a OpmDialog", MODAL, found);
	}

	@Test
	public void theBatchAndLivePreviewRowsSayTheSameThing() throws Exception {
		assertTrue(read("Parameter.java").contains("\"projection view (virtual)\""));
		assertTrue(read("LiveSetupDialog.java").contains("\"show live projection view (virtual)\""));
		assertFalse("the projection preview is always virtual now",
				read("LiveSetupDialog.java").contains("chkPreviewProjVirtual"));
	}

	@Test
	public void theSingleFileConversionsAreGoneFromTheMenu() throws Exception {
		String config = new String(Files.readAllBytes(new File("src/main/resources/plugins.config").toPath()),
				StandardCharsets.UTF_8);
		assertFalse(config.contains("Convert to deflated TIFF"));
		assertFalse(config.contains("Convert to OME-Zarr\""));
		assertTrue("the region export is not a format change and stays",
				config.contains("Export OME-Zarr region to TIFF"));
	}


	// ---- helpers --------------------------------------------------------------------

	/** The title of every {@code new <kind>(...)}, a literal, a constant, or the word it ends on. */
	private static List<String> titles(String source, String kind) {
		List<String> titles = new ArrayList<String>();
		Matcher found = Pattern.compile("new\\s+" + kind + "\\s*\\(\\s*([^;]*?)\\)\\s*;",
				Pattern.DOTALL).matcher(source);
		while (found.find()) {
			String argument = found.group(1).trim();
			Matcher literal = Pattern.compile("\"([^\"]*)\"\\s*\\)?\\s*$").matcher(argument);
			if (argument.startsWith("\"") && argument.endsWith("\"") && argument.indexOf('"', 1) == argument.length() - 1)
				titles.add(argument.substring(1, argument.length() - 1));
			else if (literal.find()) titles.add(literal.group(1).trim());
			else titles.add(argument);
		}
		return titles;
	}

	private static boolean containsConstant(String title) throws Exception {
		for (String name : BATCH_DIALOGS)
			if (read(name).contains("TITLE = \"" + title + "\"")) return true;
		return false;
	}

	private static String method(String source, String signature) {
		int start = source.indexOf(signature);
		assertTrue(signature + " not found", start >= 0);
		int end = source.indexOf("\n\t}", start);
		return source.substring(start, end);
	}

	private static String read(String name) throws Exception {
		return new String(Files.readAllBytes(new File(SOURCES, name).toPath()), StandardCharsets.UTF_8);
	}
}
