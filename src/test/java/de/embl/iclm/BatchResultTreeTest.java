package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;

/**
 * Deskew Batch over an input folder with sub-folders: "reproduce input folder structure" puts
 * each sub-folder's results in the same sub-folder of the result folder, and unticked puts them
 * all in the result folder itself - in every write path, TIFF and OME-Zarr alike, with one
 * OME-Zarr dataset per acquisition folder either way.
 */
public class BatchResultTreeTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();


	// ---- the rule -------------------------------------------------------------------------

	@Test
	public void theTreeIsReproducedOnlyWithSubFoldersAndWhenAsked() {
		Parameter p = new Parameter("batch-tree-rule-test");
		p.saveToSame = false;
		p.recursive = false;
		p.reproduceInputTree = true;
		assertFalse("no sub-folders, no tree", Batch.mirrorsTree(p));
		p.recursive = true;
		assertTrue(Batch.mirrorsTree(p));
		p.reproduceInputTree = false;
		assertFalse("unticked: everything into the result folder", Batch.mirrorsTree(p));
		p.saveToSame = true;
		assertTrue("beside the data the tree is always kept", Batch.mirrorsTree(p));
	}

	@Test
	public void aSubFolderIsReproducedRelativeToTheInputFolder() {
		File input = new File(folder.getRoot(), "input");
		File output = new File(folder.getRoot(), "output");
		String sep = File.separator;
		assertEquals(new File(output, "A" + sep + "run_0"), BatchProcessingUtils.mirroredUnder(
				new File(input, "A" + sep + "run_0" + sep + "x.tif"), input, output));
		assertEquals("a file in the input folder itself", output,
				BatchProcessingUtils.mirroredUnder(new File(input, "x.tif"), input, output));
		assertEquals("a file outside it", output, BatchProcessingUtils.mirroredUnder(
				new File(folder.getRoot(), "elsewhere" + sep + "x.tif"), input, output));
	}


	// ---- the runs -------------------------------------------------------------------------

	/** TIFF and OME-Zarr together, sub-folders reproduced: each acquisition's results in its own folder. */
	@Test
	public void reproducedEachAcquisitionWritesUnderItsOwnSubFolder() throws Exception {
		File input = folder.newFolder("input");
		File output = folder.newFolder("output");
		Acquisition first = acquisition(input, "A" + File.separator + "run_0", "a");
		Acquisition second = acquisition(input, "B" + File.separator + "run_1", "b");

		runDual(input, output, true, first, second);

		File firstResults = new File(output, "A" + File.separator + "run_0");
		File secondResults = new File(output, "B" + File.separator + "run_1");
		assertTrue(tiff(firstResults, "a").isFile());
		assertTrue(tiff(secondResults, "b").isFile());
		assertEquals(1, committed(new File(firstResults, "run_0.ome.zarr")));
		assertEquals(1, committed(new File(secondResults, "run_1.ome.zarr")));
		assertFalse("nothing is left in the result folder itself", tiff(output, "a").isFile());
	}

	/**
	 * Unticked, every result goes into the result folder - but still one dataset per acquisition.
	 *
	 * <p>All sub-folders used to go into one store named after the input folder. The second
	 * acquisition's time point carries the same label as the first one's, so it was skipped as
	 * already committed and never written.
	 */
	@Test
	public void flatEveryResultGoesIntoTheResultFolderWithOneDatasetPerAcquisition() throws Exception {
		File input = folder.newFolder("input");
		File output = folder.newFolder("output");
		Acquisition first = acquisition(input, "A" + File.separator + "run_0", "a");
		Acquisition second = acquisition(input, "B" + File.separator + "run_1", "b");

		runDual(input, output, false, first, second);

		assertTrue(tiff(output, "a").isFile());
		assertTrue(tiff(output, "b").isFile());
		assertEquals(1, committed(new File(output, "run_0.ome.zarr")));
		assertEquals("the second acquisition is written, not skipped as already committed",
				1, committed(new File(output, "run_1.ome.zarr")));
		assertFalse("and no store merges the two", new File(output, "input.ome.zarr").exists());
	}

	/** The other two paths: combined channels to TIFF only, and OME-Zarr only. */
	@Test
	public void theTiffOnlyAndZarrOnlyPathsFollowTheCheckboxToo() throws Exception {
		File input = folder.newFolder("input");
		File tiffOutput = folder.newFolder("tiff-output");
		File zarrOutput = folder.newFolder("zarr-output");
		Acquisition first = acquisition(input, "A" + File.separator + "run_0", "a");
		Acquisition second = acquisition(input, "B" + File.separator + "run_1", "b");

		run(input, tiffOutput, true, true, false, first, second);
		assertTrue(tiff(new File(tiffOutput, "A" + File.separator + "run_0"), "a").isFile());
		assertTrue(tiff(new File(tiffOutput, "B" + File.separator + "run_1"), "b").isFile());

		run(input, zarrOutput, true, false, true, first, second);
		assertEquals(1, committed(new File(new File(zarrOutput, "A" + File.separator + "run_0"), "run_0.ome.zarr")));
		assertEquals(1, committed(new File(new File(zarrOutput, "B" + File.separator + "run_1"), "run_1.ome.zarr")));
	}

	/** The single-file TIFF path, which used to reproduce the tree whatever the checkbox said. */
	@Test
	public void theSingleFilePathFollowsTheCheckboxToo() throws Exception {
		for (boolean reproduce : new boolean[] { true, false }) {
			File input = folder.newFolder("single-input-" + reproduce);
			File output = folder.newFolder("single-output-" + reproduce);
			Acquisition acquisition = acquisition(input, "A" + File.separator + "run_0", "a");
			Parameter parameter = parameter("batch-tree-single-test", input, output);
			parameter.outputFormat = Parameter.FORMAT_TIFF;
			parameter.saveDeskewZarr = false;
			parameter.recursive = true;
			parameter.reproduceInputTree = reproduce;

			Batch batch = batch(parameter, input, output, reproduce);
			set(batch, "inputFileList", new String[] { acquisition.files.get(0).getAbsolutePath() });
			((ChannelOperationSettings) get(batch, "channels")).combineAcquisitionChannels = false;
			batch.processFiles();

			File mirrored = new File(new File(new File(output, "A" + File.separator + "run_0"), "deskew"),
					"a_Time000001_Channel0001-deskewed.tif");
			File flat = new File(new File(output, "deskew"), "a_Time000001_Channel0001-deskewed.tif");
			assertEquals("reproduce " + reproduce + ": " + mirrored, reproduce, mirrored.isFile());
			assertEquals("reproduce " + reproduce + ": " + flat, !reproduce, flat.isFile());
			assertTrue("and the parameters are handed back as they were",
					parameter.recursive && output.getAbsolutePath().equals(parameter.saveDir));
		}
	}


	// ---- helpers --------------------------------------------------------------------

	private static final class Acquisition {
		final List<File> files = new ArrayList<File>();
	}

	/** One time point, two acquisition channels, in {@code input/<relative>}. */
	private Acquisition acquisition(File input, String relative, String name) throws Exception {
		File directory = new File(input, relative);
		assertTrue(directory.mkdirs());
		Acquisition acquisition = new Acquisition();
		acquisition.files.add(raw(directory, name + "_Time000001_Channel0001.tif", 10));
		acquisition.files.add(raw(directory, name + "_Time000001_Channel0002.tif", 100));
		return acquisition;
	}

	private void runDual(File input, File output, boolean reproduce, Acquisition... acquisitions)
			throws Exception {
		run(input, output, reproduce, true, true, acquisitions);
	}

	/** A combined-channel run over the acquisitions, writing TIFF, OME-Zarr or both. */
	private void run(File input, File output, boolean reproduce, boolean tiff, boolean zarr,
			Acquisition... acquisitions) throws Exception {
		Parameter parameter = parameter("batch-tree-dual-test", input, output);
		parameter.saveDeskewImage = tiff;
		parameter.saveDeskewZarr = zarr;
		parameter.outputFormat = tiff && zarr ? Parameter.FORMAT_BOTH : tiff ? Parameter.FORMAT_TIFF : Parameter.FORMAT_ZARR;
		parameter.recursive = true;
		parameter.reproduceInputTree = reproduce;
		List<File> files = new ArrayList<File>();
		for (Acquisition acquisition : acquisitions) files.addAll(acquisition.files);
		List<String> paths = new ArrayList<String>();
		for (File file : files) paths.add(file.getAbsolutePath());

		Batch batch = batch(parameter, input, output, reproduce);
		set(batch, "inputFileList", paths.toArray(new String[0]));
		set(batch, "zarrInputFiles", files);
		ChannelOperationSettings settings = (ChannelOperationSettings) get(batch, "channels");
		settings.combineAcquisitionChannels = true;
		settings.flipHalf = BatchChannelOperation.FLIP_RIGHT;
		settings.channelOrder[0] = "_Channel0001-left";
		settings.channelOrder[1] = "_Channel0001-right";
		for (int i = 2; i < settings.channelOrder.length; i++)
			settings.channelOrder[i] = BatchChannelOperation.SKIP_CHANNEL;
		batch.processFiles();
	}

	private static Parameter parameter(String name, File input, File output) {
		Parameter parameter = new Parameter(name);
		parameter.inputDir = input.getAbsolutePath();
		parameter.saveDir = output.getAbsolutePath();
		parameter.saveToSame = false;
		parameter.saveSeparate = true;
		parameter.saveDeskewImage = true;
		parameter.fileExistStr = "overwrite";
		parameter.tryGPU = false;
		parameter.doProjection = false;
		parameter.livePreview = false;
		parameter.xyPixelSize = 100.0;
		parameter.zStepSize = 100.0;
		parameter.opmAngle = 90.0;
		parameter.alignMatrix = new double[][] { { 1, 0, 0 }, { 0, 1, 0 } };
		Batch.configureFileOutput(parameter);
		return parameter;
	}

	private static Batch batch(Parameter parameter, File input, File output, boolean reproduce) throws Exception {
		Batch batch = new Batch();
		set(batch, "parameter", parameter);
		set(batch, "inputFolder", input);
		set(batch, "saveFolder", output);
		set(batch, "overwrite", Boolean.TRUE);
		set(batch, "mirrorTree", Boolean.valueOf(Batch.mirrorsTree(parameter)));
		assertEquals(reproduce, Batch.mirrorsTree(parameter));
		return batch;
	}

	private static File tiff(File results, String name) {
		return new File(new File(results, "deskew"), name + "_Time000001_Channels0001-0002-aligned-deskewed.tif");
	}

	private static int committed(File zarr) throws Exception {
		if (!new File(zarr, ".zattrs").isFile()) return 0;
		return com.google.gson.JsonParser.parseString(new String(
				Files.readAllBytes(new File(zarr, ".zattrs").toPath()), StandardCharsets.UTF_8))
				.getAsJsonObject().getAsJsonObject(OmeZarrWriter.WRITE_STATE_KEY)
				.get("committedTimepoints").getAsInt();
	}

	private static File raw(File directory, String name, int offset) throws Exception {
		File file = new File(directory, name);
		ImageStack stack = new ImageStack(4, 3);
		for (int z = 0; z < 2; z++) {
			short[] pixels = new short[12];
			for (int i = 0; i < pixels.length; i++) pixels[i] = (short) (offset + z * 20 + i);
			stack.addSlice(new ShortProcessor(4, 3, pixels, null));
		}
		ImagePlus image = new ImagePlus(name, stack);
		boolean compression = VolumeIO.isCompressOutput();
		VolumeIO.setCompressOutput(false);
		try {
			assertTrue(VolumeIO.saveTiff(image, file));
		} finally {
			VolumeIO.setCompressOutput(compression);
			image.close();
		}
		return file;
	}

	private static Object get(Object object, String name) throws Exception {
		Field field = object.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(object);
	}

	private static void set(Object object, String name, Object value) throws Exception {
		Field field = object.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(object, value);
	}

}
