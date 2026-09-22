package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Where Live Deskew writes each acquisition's results, above all with "reproduce input folder
 * structure": the input folder's whole path under the "save to" folder, less its drive, then
 * {@code result} - decided by the file's own path, whatever announced it and whenever.
 */
public class LiveOutputFolderTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final String TIMELAPSE = "3_timelapse_Position0001_Time000001_Channel0001_Frames_1_451.tiff";
	private static final String TIMELAPSE_2 = "3_timelapse_Position0001_Time000002_Channel0001_Frames_1_451.tiff";
	private static final String BEADS = "4_beads_for_overlay_Position0001_Time000001_Channel0001_Frames_1_451.tiff";


	// ---- the rule on its own ---------------------------------------------------------

	/** The example this was specified with, path for path. Windows paths, so on Windows. */
	@Test
	public void theSpecifiedExampleLandsWhereItWasAskedTo() {
		Assume.assumeTrue(File.separatorChar == '\\');
		File saveTo = new File("I:\\Group\\Ziqiang\\OPM\\New folder");
		assertEquals("I:\\Group\\Ziqiang\\OPM\\New folder\\OPM\\3_timelapse_0\\result",
				BatchProcessingUtils.mirroredResultFolder(
						new File("E:\\OPM\\3_timelapse_0\\" + TIMELAPSE), saveTo).getPath());
		assertEquals("the next time point, from the same folder, goes to the same place",
				"I:\\Group\\Ziqiang\\OPM\\New folder\\OPM\\3_timelapse_0\\result",
				BatchProcessingUtils.mirroredResultFolder(
						new File("E:\\OPM\\3_timelapse_0\\" + TIMELAPSE_2), saveTo).getPath());
		assertEquals("I:\\Group\\Ziqiang\\OPM\\New folder\\OPM\\4_beads_for_overlay_0\\result",
				BatchProcessingUtils.mirroredResultFolder(
						new File("E:\\OPM\\4_beads_for_overlay_0\\" + BEADS), saveTo).getPath());
	}

	/** A UNC root is dropped like a drive letter: server and share are the root. */
	@Test
	public void aNetworkShareRootIsDroppedLikeADrive() {
		Assume.assumeTrue(File.separatorChar == '\\');
		assertEquals("D:\\out\\OPM\\run_0\\result", BatchProcessingUtils.mirroredResultFolder(
				new File("\\\\scope-pc\\data\\OPM\\run_0\\" + TIMELAPSE), new File("D:\\out")).getPath());
	}

	/** The same on any system: only the root of the path goes. */
	@Test
	public void onlyTheRootOfTheInputPathIsDropped() {
		File root = folder.getRoot().getAbsoluteFile().toPath().getRoot().toFile();
		File input = new File(root, "OPM" + File.separator + "3_timelapse_0" + File.separator + TIMELAPSE);
		File saveTo = new File(folder.getRoot(), "save to");
		assertEquals(new File(saveTo, "OPM" + File.separator + "3_timelapse_0" + File.separator + "result"),
				BatchProcessingUtils.mirroredResultFolder(input, saveTo));
		assertEquals("a file at the root itself has no folders to reproduce", new File(saveTo, "result"),
				BatchProcessingUtils.mirroredResultFolder(new File(root, TIMELAPSE), saveTo));
		File dotted = new File(root, "OPM" + File.separator + "x" + File.separator + ".." + File.separator
				+ "3_timelapse_0" + File.separator + TIMELAPSE);
		assertEquals("'..' is resolved, not reproduced",
				new File(saveTo, "OPM" + File.separator + "3_timelapse_0" + File.separator + "result"),
				BatchProcessingUtils.mirroredResultFolder(dotted, saveTo));
	}


	// ---- the live listener ------------------------------------------------------------

	/**
	 * The TCP/IP case: paths announced one after another, then a new session.
	 *
	 * <p>The mirror used to be taken relative to the first folder a path was announced from, so
	 * the first acquisition landed straight in the "save to" folder and a second one landed there
	 * with it. It now depends on the file's own path only.
	 */
	@Test
	public void announcedAcquisitionsEachGetTheirOwnReproducedResultFolder() throws Exception {
		File opm = folder.newFolder("E", "OPM");
		File timelapse = new File(opm, "3_timelapse_0");
		File beads = new File(opm, "4_beads_for_overlay_0");
		File saveTo = folder.newFolder("New folder");
		File expectedTimelapse = new File(new File(saveTo, withoutRoot(timelapse)), "result");
		File expectedBeads = new File(new File(saveTo, withoutRoot(beads)), "result");

		Live2 live = new Live2();
		try {
			Parameter p = tcpRun(live, saveTo, true);
			set(live, "primaryRoot", timelapse);		// the first announced path's folder
			assertEquals(expectedTimelapse, outputFolderFor(live, new File(timelapse, TIMELAPSE)));
			assertEquals(expectedTimelapse, outputFolderFor(live, new File(timelapse, TIMELAPSE_2)));
			assertEquals("a second acquisition in the same session has a folder of its own",
					expectedBeads, outputFolderFor(live, new File(beads, BEADS)));

			p.reproduceInputTree = false;
			assertEquals("unticked, every acquisition goes into the save-to folder itself",
					saveTo, outputFolderFor(live, new File(beads, BEADS)));
			p.reproduceInputTree = true;
			p.saveToSame = true;
			assertEquals("save to the same folder wins over both", new File(beads, "result"),
					outputFolderFor(live, new File(beads, BEADS)));
		} finally {
			live.close();
		}

		Live2 later = new Live2();
		try {
			tcpRun(later, saveTo, true);
			set(later, "primaryRoot", beads);			// a new session, announcing the other acquisition first
			assertEquals(expectedBeads, outputFolderFor(later, new File(beads, BEADS)));
			assertEquals(expectedTimelapse, outputFolderFor(later, new File(timelapse, TIMELAPSE)));
		} finally {
			later.close();
		}
	}

	/** A watched folder gets the same result folder as an announced path to the same file. */
	@Test
	public void aWatchedFolderIsReproducedTheSameWayAsAnAnnouncedPath() throws Exception {
		File opm = folder.newFolder("E", "OPM");
		File timelapse = new File(opm, "3_timelapse_0");
		timelapse.mkdirs();
		File saveTo = folder.newFolder("out");
		Live2 live = new Live2();
		try {
			Parameter p = tcpRun(live, saveTo, true);
			p.listenTcpIp = false;
			p.watchExplicitFolder = true;
			p.recursive = true;
			p.watchDir = opm.getAbsolutePath();
			set(live, "primaryRoot", opm);
			assertEquals(new File(new File(saveTo, withoutRoot(timelapse)), "result"),
					outputFolderFor(live, new File(timelapse, TIMELAPSE)));
		} finally {
			live.close();
		}
	}

	/**
	 * While a volume is being written its folder sits in {@code parameter.saveDir}; the watcher
	 * and the status panel asking at that moment must still get the configured folder's answer.
	 */
	@Test
	public void aPerFileFolderInTheParametersDoesNotMoveTheTree() throws Exception {
		File timelapse = folder.newFolder("E", "OPM", "3_timelapse_0");
		File saveTo = folder.newFolder("out");
		File expected = new File(new File(saveTo, withoutRoot(timelapse)), "result");
		Live2 live = new Live2();
		try {
			Parameter p = tcpRun(live, saveTo, true);
			set(live, "runSaveDir", saveTo.getAbsolutePath());	// what startLive takes
			p.saveDir = expected.getAbsolutePath();				// what a processing path puts there
			assertEquals(expected, outputFolderFor(live, new File(timelapse, TIMELAPSE)));
			File written = new File(expected, "deskew" + File.separator + "x-deskewed.tif");
			assertTrue("its own result is still recognised", (Boolean) call(live, "isOwnOutput", written));
			assertFalse("and the raw volume is not", (Boolean) call(live, "isOwnOutput", new File(timelapse, TIMELAPSE)));
		} finally {
			live.close();
		}
	}

	/** The log and the processed record sit in the acquisition's reproduced result folder. */
	@Test
	public void theLogAndTheProcessedRecordSitWithTheResults() throws Exception {
		File timelapse = folder.newFolder("E", "OPM", "3_timelapse_0");
		File saveTo = folder.newFolder("out");
		File expected = new File(new File(saveTo, withoutRoot(timelapse)), "result");
		Live2 live = new Live2();
		try {
			tcpRun(live, saveTo, true);
			assertEquals(expected, call(live, "logFolder", timelapse));
			assertEquals(expected, call(live, "manifestFolder", timelapse));
		} finally {
			live.close();
		}
	}


	// ---- helpers --------------------------------------------------------------------

	/** A TCP/IP-only run saving to {@code saveTo}. */
	private static Parameter tcpRun(Live2 live, File saveTo, boolean reproduce) throws Exception {
		Field f = Live2.class.getDeclaredField("parameter");
		f.setAccessible(true);
		Parameter p = (Parameter) f.get(live);
		p.listenTcpIp = true;
		p.watchExplicitFolder = false;
		p.saveToSame = false;
		p.saveDir = saveTo.getAbsolutePath();
		p.reproduceInputTree = reproduce;
		return p;
	}

	/** The folder's path with its root taken off, written independently of the rule under test. */
	private static String withoutRoot(File folder) {
		Path path = folder.getAbsoluteFile().toPath();
		return path.toString().substring(path.getRoot().toString().length());
	}

	private static File outputFolderFor(Live2 live, File file) throws Exception {
		return (File) call(live, "outputFolderFor", file);
	}

	private static Object call(Live2 live, String name, File argument) throws Exception {
		Method m = Live2.class.getDeclaredMethod(name, File.class);
		m.setAccessible(true);
		return m.invoke(live, argument);
	}

	private static void set(Live2 live, String name, Object value) throws Exception {
		Field f = Live2.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(live, value);
	}
}
