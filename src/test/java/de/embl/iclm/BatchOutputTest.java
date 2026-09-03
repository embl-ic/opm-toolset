package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;

/** Regression coverage for Batch TIFF output state and result-folder handling. */
public class BatchOutputTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void tiffCompositeReachesAllEightHalvesOfAFourFileAcquisition() {
		/* The canonical Zarr writer already stored both halves of every file it found; the TIFF
		 * side was the half that could not express more than three acquisition channels. */
		Parameter parameter = new Parameter("eight-channel-test");
		parameter.tryGPU = false;
		parameter.alignMatrix = new double[][] { { 1, 0, 0 }, { 0, 1, 0 } };

		ChannelOperationSettings settings = new ChannelOperationSettings();
		settings.flipHalf = BatchChannelOperation.FLIP_RIGHT;
		OpmTimepointProcessor.Result canonical = new OpmTimepointProcessor.Result();
		for (int acquisition = 1; acquisition <= 4; acquisition++)
			for (int side = 0; side < 2; side++) {
				String label = ChannelOperationSettings.sourceKey(acquisition, side == 0);
				ImagePlus half = halfVolume(label, acquisition * 10 + side);
				canonical.channels.add(half);
				canonical.channelLabels.add(label);
				settings.channelOrder[2 * acquisition - 2 + side] = label;
			}

		MultiChannelDeskew.PreparedComposite prepared = null;
		try {
			prepared = MultiChannelDeskew.fromCanonical(canonical, parameter, settings, "eight");
			assertEquals(8, prepared.image.getNChannels());
			assertEquals(2, prepared.image.getNSlices());
			assertEquals(16, prepared.image.getStackSize());
			/* Every half arrives, in the order the setup asked for. The right halves are the
			 * flipped ones here, so only their pixels are resampled. */
			for (int c = 1; c <= 8; c++) {
				int acquisition = (c + 1) / 2;
				boolean left = c % 2 == 1;
				int expected = acquisition * 10 + (left ? 0 : 1);
				int value = prepared.image.getStack()
						.getProcessor(prepared.image.getStackIndex(c, 1, 1)).get(left ? 0 : 3, 0);
				assertEquals("channel " + c, expected, value);
			}
			/* Eight channels stay eight distinguishable colours rather than collapsing to white. */
			CompositeImage composite = new CompositeImage(prepared.image, CompositeImage.COMPOSITE);
			Utils.autoSetLUTs(composite);
			java.util.Set<String> colours = new java.util.LinkedHashSet<String>();
			for (int c = 1; c <= 8; c++) {
				ij.process.LUT lut = composite.getChannelLut(c);
				colours.add(lut.getRed(255) + "," + lut.getGreen(255) + "," + lut.getBlue(255));
			}
			assertEquals(8, colours.size());
		} finally {
			if (prepared != null) prepared.close();
		}
	}

	/** A 4 x 1 x 2 half whose left column carries {@code value} and right column {@code value}. */
	private static ImagePlus halfVolume(String label, int value) {
		ImageStack stack = new ImageStack(4, 1);
		for (int z = 0; z < 2; z++)
			stack.addSlice(new ShortProcessor(4, 1,
					new short[] { (short) value, 0, 0, (short) value }, null));
		ImagePlus image = new ImagePlus(label, stack);
		return image;
	}

	@Test
	public void batchAlwaysUsesFileOutputEvenForCombinedAcquisitionChannels() {
		Parameter parameter = new Parameter("batch-output-test");
		parameter.displayResult = true;

		Batch.configureFileOutput(parameter);

		assertFalse(parameter.displayResult);
	}

	@Test
	public void selectedResultFolderIsNormalizedAndStoredWithoutTrailingSeparator() throws Exception {
		File input = folder.newFolder("input");
		File selected = new File(folder.getRoot(), "chosen");
		Parameter parameter = new Parameter("batch-output-test");
		parameter.saveToSame = false;
		parameter.saveDir = selected.getAbsolutePath().replace(File.separatorChar, '/') + "/";

		File resolved = Batch.resolveSaveFolder(parameter, input);

		assertEquals(selected.getCanonicalFile(), resolved.getCanonicalFile());
		assertEquals(resolved.getPath(), parameter.saveDir);
		assertFalse(parameter.saveDir.endsWith("/") || parameter.saveDir.endsWith("\\"));
	}

	@Test
	public void sameDataFolderUsesANormalizedResultChild() throws Exception {
		File input = folder.newFolder("input");
		Parameter parameter = new Parameter("batch-output-test");
		parameter.saveToSame = true;
		parameter.saveDir = "ignored/old/value/";

		File resolved = Batch.resolveSaveFolder(parameter, input);

		assertEquals(new File(input, "result").getCanonicalFile(), resolved.getCanonicalFile());
		assertEquals(resolved.getPath(), parameter.saveDir);
		assertTrue(resolved.getParentFile().getCanonicalFile().equals(input.getCanonicalFile()));
	}

	@Test
	public void combinedSiftStyleResultIsWrittenInsteadOfDisplayed() throws Exception {
		File output = folder.newFolder("result");
		Parameter parameter = new Parameter("batch-output-test");
		parameter.saveDir = output.getAbsolutePath();
		parameter.saveDeskewImage = true;
		parameter.saveSeparate = true;
		parameter.fileExistStr = "overwrite";
		parameter.doProjection = false;
		Batch.configureFileOutput(parameter);

		ImageStack stack = new ImageStack(4, 3);
		for (int plane = 0; plane < 4; plane++) {
			ShortProcessor processor = new ShortProcessor(4, 3);
			processor.set(0, 0, plane + 1);
			stack.addSlice(processor);
		}
		ImagePlus base = new ImagePlus("combined-deskewed", stack);
		base.setDimensions(2, 2, 1);
		ImagePlus combined = new CompositeImage(base, CompositeImage.COMPOSITE);

		Deskew.prepareResults(new ImagePlus[] { combined }, parameter);

		File saved = new File(new File(output, "deskew"), "combined-deskewed.tif");
		assertTrue("Expected Batch TIFF at " + saved, saved.isFile());
		assertTrue(saved.length() > 0);
	}

	@Test
	public void dualOutputBatchWritesTiffAndZarrFromOneTimepoint() throws Exception {
		File input = folder.newFolder("dual-input");
		File output = folder.newFolder("dual-output");
		File channel1 = raw(input, "sample_Time000001_Channel0001.tif", 10);
		File channel2 = raw(input, "sample_Time000001_Channel0002.tif", 100);

		Parameter parameter = new Parameter("batch-dual-output-test");
		parameter.inputDir = input.getAbsolutePath();
		parameter.saveDir = output.getAbsolutePath();
		parameter.saveToSame = false;
		parameter.saveSeparate = true;
		parameter.saveDeskewImage = true;
		parameter.saveDeskewZarr = true;
		parameter.fileExistStr = "overwrite";
		parameter.tryGPU = false;
		parameter.doProjection = false;
		parameter.xyPixelSize = 100.0;
		parameter.zStepSize = 100.0;
		parameter.opmAngle = 90.0;
		parameter.alignMatrix = new double[][] { { 1, 0, 0 }, { 0, 1, 0 } };
		Batch.configureFileOutput(parameter);

		Batch batch = new Batch();
		set(batch, "parameter", parameter);
		set(batch, "inputFolder", input);
		set(batch, "saveFolder", output);
		set(batch, "overwrite", Boolean.TRUE);
		set(batch, "inputFileList", new String[] {
				channel1.getAbsolutePath(), channel2.getAbsolutePath() });
		set(batch, "zarrInputFiles", new ArrayList<File>(Arrays.asList(channel1, channel2)));
		ChannelOperationSettings settings = (ChannelOperationSettings) get(batch, "channels");
		settings.combineAcquisitionChannels = true;
		settings.flipHalf = BatchChannelOperation.FLIP_RIGHT;
		settings.channelOrder[0] = "_Channel0001-left";
		settings.channelOrder[1] = "_Channel0001-right";
		for (int i = 2; i < settings.channelOrder.length; i++)
			settings.channelOrder[i] = BatchChannelOperation.SKIP_CHANNEL;

		batch.processFiles();

		File tiff = new File(new File(output, "deskew"),
				"sample_Time000001_Channels0001-0002-aligned-deskewed.tif");
		File zarr = new File(output, input.getName() + ".ome.zarr");
		assertTrue("Expected parallel TIFF output at " + tiff, tiff.isFile());
		assertTrue(tiff.length() > 0);
		assertTrue(new File(zarr, OpmZarrWriter.SUCCESS_FILE).isFile());
		assertEquals(1, com.google.gson.JsonParser.parseString(new String(
				java.nio.file.Files.readAllBytes(new File(zarr, ".zattrs").toPath()),
				java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject()
				.getAsJsonObject(OpmZarrWriter.WRITE_STATE_KEY)
				.get("committedTimepoints").getAsInt());
	}

	@Test
	public void dualOutputSkipPlanningChecksEveryRequestedTiffBeforeDeskew() throws Exception {
		File output = folder.newFolder("skip-output");
		Parameter parameter = new Parameter("batch-skip-output-test");
		parameter.saveDir = output.getAbsolutePath();
		parameter.saveSeparate = true;
		parameter.saveDeskewImage = true;
		parameter.fileExistStr = "skip";
		parameter.doProjection = true;
		parameter.projAxes = new ArrayList<String>(Arrays.asList("X", "Z"));
		parameter.projTypes = new ArrayList<String>(Arrays.asList("max", "avg"));
		String name = "sample-aligned-deskewed";

		assertTrue(BatchTiffOutput.needsWrite(parameter, name));
		createTiffPlaceholder(output, "deskew", name);
		assertTrue("missing MIPs still require TIFF preparation",
				BatchTiffOutput.needsWrite(parameter, name));
		for (String axis : parameter.projAxes) for (String type : parameter.projTypes)
			createTiffPlaceholder(output, type + axis, name + "-" + type + axis + "projection");
		assertFalse("a complete TIFF result skips before raw pixels are opened",
				BatchTiffOutput.needsWrite(parameter, name));

		new File(new File(output, "avgZ"), VolumeIO.tiffPath(name + "-avgZprojection")).delete();
		assertTrue("one missing output resumes only the TIFF side",
				BatchTiffOutput.needsWrite(parameter, name));
	}

	private static void createTiffPlaceholder(File root, String folder, String name) throws Exception {
		File directory = new File(root, folder);
		java.nio.file.Files.createDirectories(directory.toPath());
		java.nio.file.Files.write(new File(directory, VolumeIO.tiffPath(name)).toPath(), new byte[] { 1 });
	}

	private File raw(File directory, String name, int offset) throws Exception {
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
