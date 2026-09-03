package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Headless end-to-end viewer tests against chunks written by the canonical writer. */
public class OpmZarrViewTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void opensVirtualAlignedFiveDimensionalHyperstackWithCalibration() throws Exception {
		OpmZarrDataset dataset = createDataset();
		OpmZarrView.Options options = new OpmZarrView.Options();
		options.tryGpu = false;
		options.operation = OpmZarrView.Operation.FLIP_ALIGN_RIGHT;

		ImagePlus image = OpmZarrView.openVirtualVolume(dataset, options, -1);
		assertTrue(image.getStack().isVirtual());
		assertEquals(4, image.getWidth());
		assertEquals(2, image.getHeight());
		assertEquals(2, image.getNChannels());
		assertEquals(2, image.getNSlices());
		assertEquals(2, image.getNFrames());
		assertEquals(0.1, image.getCalibration().pixelWidth, 0);
		assertEquals(0.2, image.getCalibration().pixelHeight, 0);
		assertEquals(0.3, image.getCalibration().pixelDepth, 0);
		assertEquals(2.5, image.getCalibration().frameInterval, 0);

		ImageProcessor rightFirst = image.getStack().getProcessor(image.getStackIndex(2, 1, 1));
		assertArrayEquals(new short[] { 40, 30, 20, 10, 44, 33, 22, 11 },
				(short[]) rightFirst.getPixels());
		ImageProcessor leftSecondTime = image.getStack().getProcessor(image.getStackIndex(1, 1, 2));
		assertEquals(101, leftSecondTime.get(0, 0));
		image.close();
	}

	@Test
	public void combinesPairSideBySideAndHonorsAcquisitionSelection() throws Exception {
		OpmZarrDataset dataset = createDataset();
		OpmZarrView.Options options = new OpmZarrView.Options();
		options.tryGpu = false;
		options.operation = OpmZarrView.Operation.SIDE_BY_SIDE;
		options.requestedChannels.add("_Channel0001-right");

		ImagePlus image = OpmZarrView.openVirtualVolume(dataset, options, 0);
		assertEquals(8, image.getWidth());
		assertEquals(1, image.getNChannels());
		assertArrayEquals(new short[] { 1, 2, 3, 4, 10, 20, 30, 40,
				5, 6, 7, 8, 11, 22, 33, 44 }, (short[]) image.getStack().getProcessor(1).getPixels());
		image.close();
	}

	@Test
	public void alignsLeftAndDoesNotTransformAnAlreadyAlignedDataset() throws Exception {
		OpmZarrDataset dataset = createDataset();
		OpmZarrView.Options leftOptions = new OpmZarrView.Options();
		leftOptions.tryGpu = false;
		leftOptions.operation = OpmZarrView.Operation.FLIP_ALIGN_LEFT;
		leftOptions.requestedChannels.add("_Channel0001-left");
		ImagePlus left = OpmZarrView.openVirtualVolume(dataset, leftOptions, 0);
		assertArrayEquals(new short[] { 4, 3, 2, 1, 8, 7, 6, 5 },
				(short[]) left.getStack().getProcessor(1).getPixels());
		left.close();

		File attrsFile = new File(dataset.getRoot(), ".zattrs");
		JsonObject attrs = JsonParser.parseString(new String(
				Files.readAllBytes(attrsFile.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
		attrs.getAsJsonObject("opm").addProperty("alignApplied", true);
		Files.write(attrsFile.toPath(), new GsonBuilder().setPrettyPrinting().create()
				.toJson(attrs).getBytes(StandardCharsets.UTF_8));
		OpmZarrDataset alreadyAligned = OpmZarrDataset.read(dataset.getRoot());
		OpmZarrView.Options rightOptions = new OpmZarrView.Options();
		rightOptions.tryGpu = false;
		rightOptions.operation = OpmZarrView.Operation.FLIP_ALIGN_RIGHT;
		rightOptions.requestedChannels.add("_Channel0001-right");
		ImagePlus right = OpmZarrView.openVirtualVolume(alreadyAligned, rightOptions, 0);
		assertArrayEquals(new short[] { 10, 20, 30, 40, 11, 22, 33, 44 },
				(short[]) right.getStack().getProcessor(1).getPixels());
		right.close();
	}

	@Test
	public void opensProjectionMovieAndMaterialisesWithCpuFallback() throws Exception {
		OpmZarrDataset dataset = createDataset();
		OpmZarrView.Options projectionOptions = new OpmZarrView.Options();
		projectionOptions.tryGpu = false;
		projectionOptions.requestedChannels.add("_Channel0001-left");
		ImagePlus movie = OpmZarrView.openProjectionMovie(dataset, "maxZ", projectionOptions);
		assertEquals(1, movie.getNChannels());
		assertEquals(2, movie.getNFrames());
		assertEquals(0.1, movie.getCalibration().pixelWidth, 0);
		assertEquals(0.2, movie.getCalibration().pixelHeight, 0);
		assertEquals(2.5, movie.getCalibration().frameInterval, 0);
		assertEquals(501, movie.getStack().getProcessor(1).get(0, 0));
		assertEquals(601, movie.getStack().getProcessor(2).get(0, 0));
		movie.close();

		OpmZarrView.Options materialOptions = new OpmZarrView.Options();
		materialOptions.tryGpu = false;
		materialOptions.operation = OpmZarrView.Operation.FLIP_ALIGN_RIGHT;
		materialOptions.requestedChannels.add("_Channel0001-right");
		ImagePlus material = OpmZarrView.openMaterializedVolume(dataset, materialOptions, -1);
		assertFalse(material.getStack().isVirtual());
		assertEquals(1, material.getNChannels());
		assertEquals(2, material.getNSlices());
		assertEquals(2, material.getNFrames());
		assertArrayEquals(new short[] { 40, 30, 20, 10, 44, 33, 22, 11 },
				(short[]) material.getStack().getProcessor(1).getPixels());
		material.close();
	}

	@Test
	public void opensMultiChannelViewsInCompositeModeWithoutMakeComposite() throws Exception {
		OpmZarrDataset dataset = createDataset();
		OpmZarrView.Options options = new OpmZarrView.Options();
		options.tryGpu = false;

		/* The file cannot record a display mode, so every multi-channel view has to arrive
		 * composite or the user must run Image > Color > Make Composite by hand. */
		ImagePlus virtual = OpmZarrView.openVirtualVolume(dataset, options, -1);
		assertTrue(virtual.isComposite());
		assertEquals(CompositeImage.COMPOSITE, ((CompositeImage) virtual).getMode());
		assertTrue(virtual.getStack().isVirtual());
		assertEquals(2, virtual.getNChannels());
		assertEquals(2, virtual.getNSlices());
		assertEquals(2, virtual.getNFrames());
		assertEquals(0.3, virtual.getCalibration().pixelDepth, 0);
		/* CompositeImage copies Info but not the properties set through setProperty, so the
		 * viewer has to re-attach them after the conversion. */
		assertTrue(String.valueOf(virtual.getProperty("opm.channelLabels")).contains("_Channel0001-left"));
		assertTrue(String.valueOf(virtual.getProperty("Info")).contains("OME-Zarr:"));
		virtual.close();

		ImagePlus movie = OpmZarrView.openProjectionMovie(dataset, "maxZ", options);
		assertTrue(movie.isComposite());
		assertEquals(2, movie.getNChannels());
		assertEquals(2, movie.getNFrames());
		movie.close();

		ImagePlus material = OpmZarrView.openMaterializedVolume(dataset, options, -1);
		assertTrue(material.isComposite());
		assertEquals(2, material.getNChannels());
		assertEquals(2, material.getNSlices());
		assertEquals(2, material.getNFrames());
		material.close();

		OpmZarrView.Options single = new OpmZarrView.Options();
		single.tryGpu = false;
		single.requestedChannels.add("_Channel0001-left");
		ImagePlus one = OpmZarrView.openVirtualVolume(dataset, single, -1);
		assertFalse(one.isComposite());
		assertEquals(1, one.getNChannels());
		one.close();
	}

	@Test
	public void refusesSideBySideOnTheProjectionThatHasNoXAxis() throws Exception {
		OpmZarrDataset dataset = createDataset();
		OpmZarrView.Options options = new OpmZarrView.Options();
		options.tryGpu = false;
		options.operation = OpmZarrView.Operation.SIDE_BY_SIDE;

		/* maxZ keeps X, which is the axis the two halves are neighbours along. */
		ImagePlus alongX = OpmZarrView.openProjectionMovie(dataset, "maxZ", options);
		assertEquals(8, alongX.getWidth());
		alongX.close();

		/* maxX has projected X away; its width axis is Z, so concatenating there would butt
		 * two ZY views together rather than rebuild the camera field. */
		try {
			OpmZarrView.openProjectionMovie(dataset, "maxX", options);
			fail("side by side should be refused on a projection without an X axis");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("projected X away"));
		}
	}

	@Test
	public void configuredOrderFallsBackWhenItNamesChannelsThisDatasetLacks() throws Exception {
		OpmZarrDataset dataset = createDataset();
		ChannelOperationSettings settings = new ChannelOperationSettings();
		for (int i = 0; i < settings.channelOrder.length; i++)
			settings.channelOrder[i] = BatchChannelOperation.SKIP_CHANNEL;
		settings.channelOrder[0] = ChannelOperationSettings.sourceKey(4, true);

		/* A setup left over from a four-file acquisition must not make a two-channel dataset
		 * unopenable; showing everything is the useful answer. */
		assertEquals(dataset.getChannelLabels(), OpmZarrView.channelsForSelection(
				dataset, OpmZarrView.SELECT_CONFIGURED, settings));

		settings.channelOrder[1] = "_Channel0001-right";
		assertEquals(Collections.singletonList("_Channel0001-right"), OpmZarrView.channelsForSelection(
				dataset, OpmZarrView.SELECT_CONFIGURED, settings));
	}

	@Test
	public void projectsTheAlignmentOntoThePlaneEachProjectionStillCarries() {
		double[][] matrix = { { 0.99, -0.1, 5 }, { 0.1, 0.99, -3 } };

		/* A Z projection keeps both axes the alignment acts on: it needs no approximation. */
		assertNull(OpmZarrView.orthogonalAlignment(matrix, 'Z', 101, 51));

		/* A Y projection keeps X, so the half is still mirrored and the alignment becomes a
		 * scale along X plus the offset the collapsed Y contributes at its centre. */
		OpmZarrView.OrthogonalAlignment alongY = OpmZarrView.orthogonalAlignment(matrix, 'Y', 101, 51);
		assertTrue(alongY.flip);
		assertArrayEquals(new double[] { 0.99, 0, -0.1 * 25 + 5 }, alongY.planeMatrix[0], 1e-12);
		assertArrayEquals(new double[] { 0, 1, 0 }, alongY.planeMatrix[1], 1e-12);
		assertEquals(0.1 * 25, alongY.spreadPixels, 1e-12);

		/* An X projection has lost the mirror axis entirely, and moves along Y instead. */
		OpmZarrView.OrthogonalAlignment alongX = OpmZarrView.orthogonalAlignment(matrix, 'X', 101, 51);
		assertFalse(alongX.flip);
		assertArrayEquals(new double[] { 1, 0, 0 }, alongX.planeMatrix[0], 1e-12);
		assertArrayEquals(new double[] { 0, 0.99, 0.1 * 50 - 3 }, alongX.planeMatrix[1], 1e-12);
		assertEquals(0.1 * 50, alongX.spreadPixels, 1e-12);

		/* Without rotation there is nothing left to disagree about, whatever the translation. */
		OpmZarrView.OrthogonalAlignment pure =
				OpmZarrView.orthogonalAlignment(new double[][] { { 1, 0, 9 }, { 0, 1, -4 } }, 'X', 101, 51);
		assertEquals(0.0, pure.spreadPixels, 0);
	}

	@Test
	public void wholePixelTranslationMakesTheOrthogonalOverlayExact() throws Exception {
		/* The claim the approximation rests on: a translation perpendicular to the projection
		 * axis is absorbed by the reduction, and one parallel to it is a plain shift. At whole
		 * pixels there is no interpolation left either, so the overlay must match a genuine
		 * recompute from the aligned volume exactly. */
		OpmZarrDataset dataset = createProjectionFixture(new double[][] { { 1, 0, 2 }, { 0, 1, -1 } });
		OpmZarrView.Options options = new OpmZarrView.Options();
		options.tryGpu = false;
		options.interpolate = false;
		options.operation = OpmZarrView.Operation.FLIP_ALIGN_RIGHT;
		options.requestedChannels.add("_Channel0001-right");

		ImagePlus aligned = OpmZarrView.openMaterializedVolume(dataset, options, 0);
		ImagePlus movieY = OpmZarrView.openProjectionMovie(dataset, "maxY", options);
		ImagePlus movieX = OpmZarrView.openProjectionMovie(dataset, "maxX", options);
		assertArrayEquals((short[]) trueMaxY(aligned).getPixels(),
				(short[]) movieY.getStack().getProcessor(1).getPixels());
		assertArrayEquals((short[]) trueMaxX(aligned).getPixels(),
				(short[]) movieX.getStack().getProcessor(1).getPixels());

		/* It is still labelled as an overlay: the reduction happened before the alignment. */
		assertTrue(movieY.getTitle(), movieY.getTitle().contains("rough overlay"));
		assertEquals("true", movieY.getProperty("opm.approximateOverlay"));
		assertTrue(String.valueOf(movieY.getProperty("Info")).contains("ROUGH OVERLAY"));
		aligned.close(); movieY.close(); movieX.close();
	}

	@Test
	public void rotationMakesTheOverlayDisagreeAndTheWarningSaysByHowMuch() throws Exception {
		double sine = 0.05;
		OpmZarrDataset dataset = createProjectionFixture(new double[][] {
				{ Math.sqrt(1 - sine * sine), -sine, 0 }, { sine, Math.sqrt(1 - sine * sine), 0 } });
		OpmZarrView.Options options = new OpmZarrView.Options();
		options.tryGpu = false;
		options.operation = OpmZarrView.Operation.FLIP_ALIGN_RIGHT;
		options.requestedChannels.add("_Channel0001-right");

		String warning = OpmZarrView.approximateProjectionWarning(dataset, "maxX",
				OpmZarrView.Operation.FLIP_ALIGN_RIGHT, sine * (dataset.getWidth() - 1) / 2.0);
		assertTrue(warning, warning.contains("ROUGH OVERLAY"));
		assertTrue(warning, warning.contains("not a true maximum projection"));
		assertTrue(warning, warning.contains("do not measure"));
		/* The bound is stated in pixels, and in microns when the dataset is calibrated. */
		String stated = OpmZarrView.approximateProjectionWarning(dataset, "maxX",
				OpmZarrView.Operation.FLIP_ALIGN_RIGHT, 3.5);
		assertTrue(stated, stated.contains("3.50 px"));
		assertTrue(stated, stated.contains("um)"));
		assertTrue(OpmZarrView.approximateProjectionWarning(dataset, "meanY",
				OpmZarrView.Operation.FLIP_ALIGN_RIGHT, 1).contains("not a true mean projection"));

		/* A Z projection carries the same alignment exactly, so it is never marked. */
		ImagePlus exact = OpmZarrView.openProjectionMovie(dataset, "maxZ", options);
		assertFalse(exact.getTitle(), exact.getTitle().contains("rough overlay"));
		assertNull(exact.getProperty("opm.approximateOverlay"));
		exact.close();
	}

	/** A volume together with its genuine maxY/maxX, so the overlay can be checked against truth. */
	private OpmZarrDataset createProjectionFixture(double[][] align) throws Exception {
		File root = new File(folder.getRoot(), "ortho" + align[0][2] + align[1][0] + ".ome.zarr");
		OpmProvenance provenance = OpmZarrDatasetTest.sampleProvenance("ortho", true);
		provenance.alignMatrix = align;
		provenance.alignFlipHalf = BatchChannelOperation.FLIP_RIGHT;
		int w = 6, h = 3, d = 2;
		OpmZarrWriter writer = new OpmZarrWriter(root);
		writer.createAppendableVolume(w, h, d, 2);
		writer.createAppendableProjection("maxY", w, d, 2);
		writer.createAppendableProjection("maxX", d, h, 2);
		writer.createAppendableProjection("maxZ", w, h, 2);
		writer.writeMetadata(provenance, Arrays.asList("maxY", "maxX", "maxZ"));
		for (int c = 0; c < 2; c++) {
			ImagePlus channel = patternVolume(w, h, d, c);
			writer.writeVolumeChannel(channel, c, 0);
			writer.writeProjection(new ImagePlus("y", trueMaxY(channel)), "maxY", c, 0);
			writer.writeProjection(new ImagePlus("x", trueMaxX(channel)), "maxX", c, 0);
			writer.writeProjection(new ImagePlus("z", trueMaxZ(channel)), "maxZ", c, 0);
		}
		writer.commitTimePoint(0, "Time1", Collections.singletonList("raw.tif"), 0);
		writer.markComplete();
		return OpmZarrDataset.read(root);
	}

	private static ImagePlus patternVolume(int w, int h, int d, int channel) {
		ImageStack stack = new ImageStack(w, h);
		for (int z = 0; z < d; z++) {
			ShortProcessor plane = new ShortProcessor(w, h);
			for (int y = 0; y < h; y++)
				for (int x = 0; x < w; x++)
					plane.set(x, y, 100 * channel + 10 * z + 3 * x + y + 1);
			stack.addSlice(plane);
		}
		return new ImagePlus("pattern", stack);
	}

	private static ImageProcessor trueMaxY(ImagePlus volume) {
		int w = volume.getWidth(), h = volume.getHeight(), d = volume.getStackSize();
		ShortProcessor out = new ShortProcessor(w, d);
		for (int z = 0; z < d; z++) {
			ImageProcessor plane = volume.getStack().getProcessor(z + 1);
			for (int x = 0; x < w; x++) {
				int best = 0;
				for (int y = 0; y < h; y++) best = Math.max(best, plane.get(x, y));
				out.set(x, z, best);
			}
		}
		return out;
	}

	private static ImageProcessor trueMaxX(ImagePlus volume) {
		int w = volume.getWidth(), h = volume.getHeight(), d = volume.getStackSize();
		ShortProcessor out = new ShortProcessor(d, h);
		for (int z = 0; z < d; z++) {
			ImageProcessor plane = volume.getStack().getProcessor(z + 1);
			for (int y = 0; y < h; y++) {
				int best = 0;
				for (int x = 0; x < w; x++) best = Math.max(best, plane.get(x, y));
				out.set(z, y, best);
			}
		}
		return out;
	}

	private static ImageProcessor trueMaxZ(ImagePlus volume) {
		int w = volume.getWidth(), h = volume.getHeight(), d = volume.getStackSize();
		ShortProcessor out = new ShortProcessor(w, h);
		for (int z = 0; z < d; z++) {
			ImageProcessor plane = volume.getStack().getProcessor(z + 1);
			for (int y = 0; y < h; y++)
				for (int x = 0; x < w; x++)
					out.set(x, y, Math.max(out.get(x, y), plane.get(x, y)));
		}
		return out;
	}

	@Test
	public void channelSetupIsSizedByTheDatasetNotAFixedSixSlots() throws Exception {
		for (int acquisitions = 1; acquisitions <= BatchChannelOperation.MAX_ACQUISITION_CHANNELS; acquisitions++) {
			OpmZarrDataset dataset = createWideDataset(acquisitions);
			int channels = acquisitions * 2;
			String where = "acquisitions=" + acquisitions;

			assertEquals(where, channels, OpmZarrView.channelSetupSlots(dataset));
			String[] sources = OpmZarrView.channelSetupSources(dataset);
			assertEquals(where, channels + 1, sources.length);
			assertEquals(where, BatchChannelOperation.SKIP_CHANNEL, sources[channels]);
			assertEquals(where, dataset.getChannelLabels(),
					Arrays.asList(sources).subList(0, channels));

			/* Channels / side scales the same way: the three fixed entries, one per acquisition
			 * base, then every stored half. */
			List<String> selections = OpmZarrView.selectionOptions(dataset);
			assertEquals(where, 3 + acquisitions + channels, selections.size());
			assertTrue(where, selections.contains(
					ChannelOperationSettings.sourceKey(acquisitions, false)));
		}
	}

	@Test
	public void channelSetupKeepsWhatItCanAndRepairsWhatItCannot() throws Exception {
		OpmZarrDataset dataset = createWideDataset(3);
		ChannelOperationSettings configured = new ChannelOperationSettings();

		String[] defaults = OpmZarrView.channelSetupDefaults(dataset, configured);
		assertEquals(6, defaults.length);
		/* The stored two-file setup is honoured where this dataset can honour it, skips and all,
		 * and the dialog is told which of its channels that leaves out. */
		for (int i = 0; i < 4; i++) assertEquals(configured.channelOrder[i], defaults[i]);
		assertEquals(BatchChannelOperation.SKIP_CHANNEL, defaults[4]);
		assertEquals(Arrays.asList("_Channel0003-left", "_Channel0003-right"),
				OpmZarrView.channelsNotSelected(dataset, defaults));

		/* A name from a four-file acquisition this dataset never had repairs to its own slot. */
		configured.channelOrder[0] = ChannelOperationSettings.sourceKey(4, true);
		assertEquals("_Channel0001-left", OpmZarrView.channelSetupDefaults(dataset, configured)[0]);
	}

	/** One time point whose width is {@code acquisitions} files, so two halves each. */
	private OpmZarrDataset createWideDataset(int acquisitions) throws Exception {
		File root = new File(folder.getRoot(), "wide" + acquisitions + ".ome.zarr");
		OpmProvenance provenance = OpmZarrDatasetTest.sampleProvenance("wide", true);
		List<String> labels = new ArrayList<String>();
		for (int a = 1; a <= acquisitions; a++) {
			labels.add(ChannelOperationSettings.sourceKey(a, true));
			labels.add(ChannelOperationSettings.sourceKey(a, false));
		}
		provenance.channelLabels = labels;
		OpmZarrWriter writer = new OpmZarrWriter(root);
		writer.createAppendableVolume(4, 2, 2, labels.size());
		writer.createAppendableProjection("maxZ", 4, 2, labels.size());
		writer.writeMetadata(provenance, Collections.singletonList("maxZ"));
		for (int c = 0; c < labels.size(); c++) {
			writer.writeVolumeChannel(volume(c % 2 == 1, 0), c, 0);
			writer.writeProjection(plane(4, 2, 500 + c), "maxZ", c, 0);
		}
		writer.commitTimePoint(0, "Time1", Collections.singletonList("raw.tif"), 0);
		writer.markComplete();
		return OpmZarrDataset.read(root);
	}

	private OpmZarrDataset createDataset() throws Exception {
		File root = new File(folder.getRoot(), "viewer.ome.zarr");
		OpmProvenance provenance = OpmZarrDatasetTest.sampleProvenance("viewer", true);
		OpmZarrWriter writer = new OpmZarrWriter(root);
		writer.createAppendableVolume(4, 2, 2, 2);
		writer.createAppendableProjection("maxZ", 4, 2, 2);
		writer.createAppendableProjection("maxX", 2, 2, 2);
		writer.writeMetadata(provenance, Arrays.asList("maxZ", "maxX"));
		for (int t = 0; t < 2; t++) {
			writer.writeVolumeChannel(volume(false, t), 0, t);
			writer.writeVolumeChannel(volume(true, t), 1, t);
			writer.writeProjection(plane(4, 2, 501 + t * 100), "maxZ", 0, t);
			writer.writeProjection(plane(4, 2, 701 + t * 100), "maxZ", 1, t);
			writer.writeProjection(plane(2, 2, 901 + t * 100), "maxX", 0, t);
			writer.writeProjection(plane(2, 2, 951 + t * 100), "maxX", 1, t);
			writer.commitTimePoint(t, "Time" + (t + 1), Collections.singletonList("raw.tif"), t * 2.5);
		}
		writer.markComplete();
		return OpmZarrDataset.read(root);
	}

	private static ImagePlus volume(boolean right, int timepoint) {
		ImageStack stack = new ImageStack(4, 2);
		for (int z = 0; z < 2; z++) {
			int base = timepoint * 100 + z * 50;
			short[] pixels = right
					? new short[] { (short) (10 + base), (short) (20 + base), (short) (30 + base), (short) (40 + base),
						(short) (11 + base), (short) (22 + base), (short) (33 + base), (short) (44 + base) }
					: new short[] { (short) (1 + base), (short) (2 + base), (short) (3 + base), (short) (4 + base),
						(short) (5 + base), (short) (6 + base), (short) (7 + base), (short) (8 + base) };
			stack.addSlice(new ShortProcessor(4, 2, pixels, null));
		}
		return new ImagePlus(right ? "right" : "left", stack);
	}

	private static ImagePlus plane(int width, int height, int first) {
		short[] pixels = new short[width * height];
		for (int i = 0; i < pixels.length; i++) pixels[i] = (short) (first + i);
		return new ImagePlus("projection", new ShortProcessor(width, height, pixels, null));
	}
}
