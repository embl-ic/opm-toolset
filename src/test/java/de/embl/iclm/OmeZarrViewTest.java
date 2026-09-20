package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

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
public class OmeZarrViewTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void opensVirtualAlignedFiveDimensionalHyperstackWithCalibration() throws Exception {
		OmeZarrDataset dataset = createDataset();
		OmeZarrView.Options options = new OmeZarrView.Options();
		options.tryGpu = false;
		options.operation = OmeZarrView.Operation.FLIP_ALIGN_RIGHT;

		ImagePlus image = OmeZarrView.openVirtualVolume(dataset, options, -1);
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
		OmeZarrDataset dataset = createDataset();
		OmeZarrView.Options options = new OmeZarrView.Options();
		options.tryGpu = false;
		options.operation = OmeZarrView.Operation.SIDE_BY_SIDE;
		options.requestedChannels.add("_Channel0001-right");

		ImagePlus image = OmeZarrView.openVirtualVolume(dataset, options, 0);
		assertEquals(8, image.getWidth());
		assertEquals(1, image.getNChannels());
		assertArrayEquals(new short[] { 1, 2, 3, 4, 10, 20, 30, 40,
				5, 6, 7, 8, 11, 22, 33, 44 }, (short[]) image.getStack().getProcessor(1).getPixels());
		image.close();
	}

	/**
	 * The fault a live preview of a whole-image run showed: the store's two halves laid over each
	 * other as two channels. Applied to the viewer's controls, the run's layout gives one channel
	 * of the full camera width, in the volume and in the projection movie alike.
	 */
	@Test
	public void aWholeImageRunOpensAsOneFullWidthChannelInVolumeAndProjection() throws Exception {
		OmeZarrDataset dataset = createDataset();
		DeskewChannelView layout = DeskewChannelView.of("whole image", false,
				Collections.<String>emptyList(), BatchChannelOperation.FLIP_RIGHT, true);
		OmeZarrView.Options options = new OmeZarrView.Options();
		options.tryGpu = false;
		options.operation = layout.operation;
		options.requestedChannels.addAll(
				OmeZarrView.channelsForSelection(dataset, layout.selection, new ChannelOperationSettings()));

		ImagePlus volume = OmeZarrView.openVirtualVolume(dataset, options, -1);
		try {
			assertEquals(1, volume.getNChannels());
			assertEquals(8, volume.getWidth());
			assertTrue(volume.getOpenAsHyperStack());
			assertArrayEquals("left then right, neither flipped", new short[] { 1, 2, 3, 4, 10, 20, 30, 40,
					5, 6, 7, 8, 11, 22, 33, 44 }, (short[]) volume.getStack().getProcessor(1).getPixels());
		} finally { volume.close(); }

		ImagePlus movie = OmeZarrView.openVirtualProjectionMovie(dataset, "maxZ", options);
		try {
			assertEquals(1, movie.getNChannels());
			assertEquals(8, movie.getWidth());
			assertTrue("opened as a hyperstack, so C, Z and T are not run together into one slider",
					movie.getOpenAsHyperStack());
			assertArrayEquals(new short[] { 501, 502, 503, 504, 701, 702, 703, 704,
					505, 506, 507, 508, 705, 706, 707, 708 },
					(short[]) movie.getStack().getProcessor(1).getPixels());
		} finally { movie.close(); }
	}

	/**
	 * A whole-image X projection from a store equals the X projection of the full-width volume.
	 * <p>
	 * Side by side along X used to be refused - the halves' maxX planes are ZY views, and joining
	 * them doubled Z - so a whole-image run's preview could not show X at all. The halves are
	 * merged now: the maximum exactly, the mean to within one grey level of rounding.
	 */
	@Test
	public void aWholeWidthXProjectionIsTheProjectionOfTheFullWidthVolume() throws Exception {
		int width = 5, height = 4, depth = 3;
		ImagePlus left = randomVolume(width, height, depth, 11);
		ImagePlus right = randomVolume(width, height, depth, 12);
		List<ProjectionBatch.Request> requests = Arrays.asList(
				new ProjectionBatch.Request("X", "max"), new ProjectionBatch.Request("X", "avg"));
		List<ImagePlus> leftX = ProjectionBatch.compute(left, requests, false);
		List<ImagePlus> rightX = ProjectionBatch.compute(right, requests, false);

		File root = new File(folder.getRoot(), "whole-x.ome.zarr");
		OmeZarrWriter writer = new OmeZarrWriter(root);
		writer.createAppendableVolume(width, height, depth, 2);
		writer.createAppendableProjection("maxX", depth, height, 2);
		writer.createAppendableProjection("meanX", depth, height, 2);
		writer.writeMetadata(OmeZarrDatasetTest.sampleProvenance("whole-x", false), Arrays.asList("maxX", "meanX"));
		writer.writeVolumeChannel(left, 0, 0);
		writer.writeVolumeChannel(right, 1, 0);
		writer.writeProjection(leftX.get(0), "maxX", 0, 0);
		writer.writeProjection(rightX.get(0), "maxX", 1, 0);
		writer.writeProjection(leftX.get(1), "meanX", 0, 0);
		writer.writeProjection(rightX.get(1), "meanX", 1, 0);
		writer.commitTimePoint(0, "Time1", Collections.singletonList("raw.tif"), 0);
		writer.markComplete();
		OmeZarrDataset dataset = OmeZarrDataset.read(root);

		ImageStack fullStack = new ImageStack(2 * width, height);
		for (int z = 1; z <= depth; z++) {
			ShortProcessor joined = new ShortProcessor(2 * width, height);
			joined.insert(left.getStack().getProcessor(z), 0, 0);
			joined.insert(right.getStack().getProcessor(z), width, 0);
			fullStack.addSlice(joined);
		}
		ImagePlus full = new ImagePlus("full width", fullStack);
		full.setDimensions(1, depth, 1);
		List<ImagePlus> fullX = ProjectionBatch.compute(full, requests, false);

		OmeZarrView.Options options = new OmeZarrView.Options();
		options.tryGpu = false;
		options.operation = OmeZarrView.Operation.SIDE_BY_SIDE;
		ImagePlus maxX = OmeZarrView.openVirtualProjectionMovie(dataset, "maxX", options);
		ImagePlus meanX = OmeZarrView.openVirtualProjectionMovie(dataset, "meanX", options);
		try {
			assertEquals("one whole-width channel", 1, maxX.getNChannels());
			assertEquals("Z wide, not two Z side by side", depth, maxX.getWidth());
			assertArrayEquals((short[]) fullX.get(0).getProcessor().getPixels(),
					(short[]) maxX.getStack().getProcessor(1).getPixels());
			short[] expectedMean = (short[]) fullX.get(1).getProcessor().getPixels();
			short[] mergedMean = (short[]) meanX.getStack().getProcessor(1).getPixels();
			for (int i = 0; i < expectedMean.length; i++)
				assertTrue("mean within rounding at " + i,
						Math.abs((expectedMean[i] & 0xffff) - (mergedMean[i] & 0xffff)) <= 1);
		} finally {
			maxX.close();
			meanX.close();
		}
	}

	private static ImagePlus randomVolume(int width, int height, int depth, long seed) {
		java.util.Random random = new java.util.Random(seed);
		ImageStack stack = new ImageStack(width, height);
		for (int z = 0; z < depth; z++) {
			short[] pixels = new short[width * height];
			for (int i = 0; i < pixels.length; i++) pixels[i] = (short) random.nextInt(4000);
			stack.addSlice(new ShortProcessor(width, height, pixels, null));
		}
		ImagePlus image = new ImagePlus("half", stack);
		image.setDimensions(1, depth, 1);
		return image;
	}

	/** A combined run's whole-width slots name both halves of their acquisition channel. */
	@Test
	public void aConfiguredWholeWidthSourceSelectsBothOfItsHalves() throws Exception {
		OmeZarrDataset dataset = createDataset();
		ChannelOperationSettings configured = new ChannelOperationSettings();
		java.util.Arrays.fill(configured.channelOrder, BatchChannelOperation.SKIP_CHANNEL);
		configured.channelOrder[0] = ChannelOperationSettings.wholeSourceKey(1);
		assertEquals(Arrays.asList("_Channel0001-left", "_Channel0001-right"),
				OmeZarrView.channelsForSelection(dataset, OmeZarrView.SELECT_CONFIGURED, configured));

		OmeZarrView.Options options = new OmeZarrView.Options();
		options.tryGpu = false;
		options.operation = OmeZarrView.Operation.SIDE_BY_SIDE;
		options.requestedChannels.addAll(
				OmeZarrView.channelsForSelection(dataset, OmeZarrView.SELECT_CONFIGURED, configured));
		assertEquals(Collections.singletonList("_Channel0001-whole"),
				OmeZarrView.outputChannelLabels(dataset, options));
	}

	@Test
	public void alignsLeftAndDoesNotTransformAnAlreadyAlignedDataset() throws Exception {
		OmeZarrDataset dataset = createDataset();
		OmeZarrView.Options leftOptions = new OmeZarrView.Options();
		leftOptions.tryGpu = false;
		leftOptions.operation = OmeZarrView.Operation.FLIP_ALIGN_LEFT;
		leftOptions.requestedChannels.add("_Channel0001-left");
		ImagePlus left = OmeZarrView.openVirtualVolume(dataset, leftOptions, 0);
		assertArrayEquals(new short[] { 4, 3, 2, 1, 8, 7, 6, 5 },
				(short[]) left.getStack().getProcessor(1).getPixels());
		left.close();

		File attrsFile = new File(dataset.getRoot(), ".zattrs");
		JsonObject attrs = JsonParser.parseString(new String(
				Files.readAllBytes(attrsFile.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
		attrs.getAsJsonObject("opm").addProperty("alignApplied", true);
		Files.write(attrsFile.toPath(), new GsonBuilder().setPrettyPrinting().create()
				.toJson(attrs).getBytes(StandardCharsets.UTF_8));
		OmeZarrDataset alreadyAligned = OmeZarrDataset.read(dataset.getRoot());
		OmeZarrView.Options rightOptions = new OmeZarrView.Options();
		rightOptions.tryGpu = false;
		rightOptions.operation = OmeZarrView.Operation.FLIP_ALIGN_RIGHT;
		rightOptions.requestedChannels.add("_Channel0001-right");
		ImagePlus right = OmeZarrView.openVirtualVolume(alreadyAligned, rightOptions, 0);
		assertArrayEquals(new short[] { 10, 20, 30, 40, 11, 22, 33, 44 },
				(short[]) right.getStack().getProcessor(1).getPixels());
		right.close();
	}

	@Test
	public void sourceSpecificMatrixSetTransformsLeftAndRightIntoOneReference() throws Exception {
		OmeZarrDataset original = createDataset();
		File attrsFile = new File(original.getRoot(), ".zattrs");
		JsonObject attrs = JsonParser.parseString(new String(
				Files.readAllBytes(attrsFile.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
		OpmProvenance provenance = original.getProvenance();
		provenance.alignReference = "_Channel0001-left";
		provenance.alignMatrices.put("_Channel0001-left",
				new double[][] { { 1, 0, 1 }, { 0, 1, 0 } });
		provenance.alignMatrices.put("_Channel0001-right",
				new double[][] { { 1, 0, 0 }, { 0, 1, 0 } });
		attrs.add(OpmProvenance.KEY, provenance.toJson());
		Files.write(attrsFile.toPath(), new GsonBuilder().setPrettyPrinting().create()
				.toJson(attrs).getBytes(StandardCharsets.UTF_8));

		OmeZarrDataset dataset = OmeZarrDataset.read(original.getRoot());
		OmeZarrView.Options options = new OmeZarrView.Options();
		options.tryGpu = false;
		options.operation = OmeZarrView.Operation.FLIP_ALIGN_RIGHT;
		ImagePlus image = OmeZarrView.openVirtualVolume(dataset, options, 0);
		try {
			assertArrayEquals(new short[] { 0, 1, 2, 3, 0, 5, 6, 7 },
					(short[]) image.getStack().getProcessor(image.getStackIndex(1, 1, 1)).getPixels());
			assertArrayEquals(new short[] { 40, 30, 20, 10, 44, 33, 22, 11 },
					(short[]) image.getStack().getProcessor(image.getStackIndex(2, 1, 1)).getPixels());
		} finally { image.close(); }
	}

	/**
	 * The same tagged store, flipped on the other side: the left half is mirrored and the right
	 * half is the fixed frame. With the right half's own matrix the identity, that is exactly
	 * the right-flipped view mirrored - which is what makes the expectation checkable by eye.
	 */
	@Test
	public void aSourceSpecificMatrixSetCanBeViewedFlippedLeft() throws Exception {
		OmeZarrDataset original = createDataset();
		File attrsFile = new File(original.getRoot(), ".zattrs");
		JsonObject attrs = JsonParser.parseString(new String(
				Files.readAllBytes(attrsFile.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
		OpmProvenance provenance = original.getProvenance();
		provenance.alignReference = "_Channel0001-left";
		provenance.alignMatrices.put("_Channel0001-left",
				new double[][] { { 1, 0, 1 }, { 0, 1, 0 } });
		provenance.alignMatrices.put("_Channel0001-right",
				new double[][] { { 1, 0, 0 }, { 0, 1, 0 } });
		attrs.add(OpmProvenance.KEY, provenance.toJson());
		Files.write(attrsFile.toPath(), new GsonBuilder().setPrettyPrinting().create()
				.toJson(attrs).getBytes(StandardCharsets.UTF_8));

		OmeZarrDataset dataset = OmeZarrDataset.read(original.getRoot());
		OmeZarrView.Options options = new OmeZarrView.Options();
		options.tryGpu = false;
		options.operation = OmeZarrView.Operation.FLIP_ALIGN_LEFT;
		ImagePlus image = OmeZarrView.openVirtualVolume(dataset, options, 0);
		try {
			assertArrayEquals("left: mirrored, then its matrix seen from the right",
					new short[] { 3, 2, 1, 0, 7, 6, 5, 0 },
					(short[]) image.getStack().getProcessor(image.getStackIndex(1, 1, 1)).getPixels());
			assertArrayEquals("right: the fixed frame, as stored",
					new short[] { 10, 20, 30, 40, 11, 22, 33, 44 },
					(short[]) image.getStack().getProcessor(image.getStackIndex(2, 1, 1)).getPixels());
		} finally { image.close(); }
	}

	@Test
	public void opensProjectionMovieAndMaterialisesWithCpuFallback() throws Exception {
		OmeZarrDataset dataset = createDataset();
		OmeZarrView.Options projectionOptions = new OmeZarrView.Options();
		projectionOptions.tryGpu = false;
		projectionOptions.requestedChannels.add("_Channel0001-left");
		ImagePlus movie = OmeZarrView.openMaterializedProjectionMovie(dataset, "maxZ", projectionOptions);
		assertFalse(movie.getStack().isVirtual());
		assertEquals(1, movie.getNChannels());
		assertEquals(2, movie.getNFrames());
		assertEquals(0.1, movie.getCalibration().pixelWidth, 0);
		assertEquals(0.2, movie.getCalibration().pixelHeight, 0);
		assertEquals(2.5, movie.getCalibration().frameInterval, 0);
		assertEquals(501, movie.getStack().getProcessor(1).get(0, 0));
		assertEquals(601, movie.getStack().getProcessor(2).get(0, 0));
		movie.close();

		OmeZarrView.Options materialOptions = new OmeZarrView.Options();
		materialOptions.tryGpu = false;
		materialOptions.operation = OmeZarrView.Operation.FLIP_ALIGN_RIGHT;
		materialOptions.requestedChannels.add("_Channel0001-right");
		ImagePlus material = OmeZarrView.openMaterializedVolume(dataset, materialOptions, -1);
		assertFalse(material.getStack().isVirtual());
		assertEquals(1, material.getNChannels());
		assertEquals(2, material.getNSlices());
		assertEquals(2, material.getNFrames());
		assertArrayEquals(new short[] { 40, 30, 20, 10, 44, 33, 22, 11 },
				(short[]) material.getStack().getProcessor(1).getPixels());
		material.close();
	}

	@Test
	public void opensProjectionMovieAsVirtualCByTStack() throws Exception {
		OmeZarrDataset dataset = createDataset();
		OmeZarrView.Options options = new OmeZarrView.Options();
		options.tryGpu = false;

		ImagePlus movie = OmeZarrView.openVirtualProjectionMovie(dataset, "maxZ", options);
		try {
			assertTrue(movie.getStack().isVirtual());
			assertTrue(movie.isComposite());
			assertEquals(2, movie.getNChannels());
			assertEquals(1, movie.getNSlices());
			assertEquals(2, movie.getNFrames());
			assertEquals(4, movie.getStackSize());
			assertEquals(501, movie.getStack().getProcessor(movie.getStackIndex(1, 1, 1)).get(0, 0));
			assertEquals(701, movie.getStack().getProcessor(movie.getStackIndex(2, 1, 1)).get(0, 0));
			assertEquals(601, movie.getStack().getProcessor(movie.getStackIndex(1, 1, 2)).get(0, 0));
			assertTrue(movie.getStack().getSliceLabel(3).contains("Time2"));
			assertEquals(64L, OmeZarrView.estimateMaterializedProjectionBytes(
					dataset, "maxZ", options));
		} finally {
			movie.close();
		}
	}

	@Test
	public void opensMultiChannelViewsInCompositeModeWithoutMakeComposite() throws Exception {
		OmeZarrDataset dataset = createDataset();
		OmeZarrView.Options options = new OmeZarrView.Options();
		options.tryGpu = false;

		/* The file cannot record a display mode, so every multi-channel view has to arrive
		 * composite or the user must run Image > Color > Make Composite by hand. */
		ImagePlus virtual = OmeZarrView.openVirtualVolume(dataset, options, -1);
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

		ImagePlus movie = OmeZarrView.openProjectionMovie(dataset, "maxZ", options);
		assertTrue(movie.isComposite());
		assertEquals(2, movie.getNChannels());
		assertEquals(2, movie.getNFrames());
		movie.close();

		ImagePlus material = OmeZarrView.openMaterializedVolume(dataset, options, -1);
		assertTrue(material.isComposite());
		assertEquals(2, material.getNChannels());
		assertEquals(2, material.getNSlices());
		assertEquals(2, material.getNFrames());
		material.close();

		OmeZarrView.Options single = new OmeZarrView.Options();
		single.tryGpu = false;
		single.requestedChannels.add("_Channel0001-left");
		ImagePlus one = OmeZarrView.openVirtualVolume(dataset, single, -1);
		assertFalse(one.isComposite());
		assertEquals(1, one.getNChannels());
		one.close();
	}

	@Test
	public void sideBySideJoinsHalvesAlongXAndMergesThemOnTheProjectionThatHasNoXAxis() throws Exception {
		OmeZarrDataset dataset = createDataset();
		OmeZarrView.Options options = new OmeZarrView.Options();
		options.tryGpu = false;
		options.operation = OmeZarrView.Operation.SIDE_BY_SIDE;

		/* maxZ keeps X, which is the axis the two halves are neighbours along. */
		ImagePlus alongX = OmeZarrView.openProjectionMovie(dataset, "maxZ", options);
		assertEquals(8, alongX.getWidth());
		alongX.close();

		/* maxX has projected X away; its width axis is Z. Joining the halves there butted two ZY
		 * views together into a doubled Z, which is why this used to be refused. They are merged
		 * instead: still Z wide, the larger of the two halves at every pixel. */
		ImagePlus withoutX = OmeZarrView.openProjectionMovie(dataset, "maxX", options);
		try {
			assertEquals("Z wide, not two Z side by side", 2, withoutX.getWidth());
			assertEquals(1, withoutX.getNChannels());
			assertArrayEquals(new short[] { 951, 952, 953, 954 },
					(short[]) withoutX.getStack().getProcessor(1).getPixels());
		} finally {
			withoutX.close();
		}
	}

	@Test
	public void configuredOrderFallsBackWhenItNamesChannelsThisDatasetLacks() throws Exception {
		OmeZarrDataset dataset = createDataset();
		ChannelOperationSettings settings = new ChannelOperationSettings();
		for (int i = 0; i < settings.channelOrder.length; i++)
			settings.channelOrder[i] = BatchChannelOperation.SKIP_CHANNEL;
		settings.channelOrder[0] = ChannelOperationSettings.sourceKey(4, true);

		/* A setup left over from a four-file acquisition must not make a two-channel dataset
		 * unopenable; showing everything is the useful answer. */
		assertEquals(dataset.getChannelLabels(), OmeZarrView.channelsForSelection(
				dataset, OmeZarrView.SELECT_CONFIGURED, settings));

		settings.channelOrder[1] = "_Channel0001-right";
		assertEquals(Collections.singletonList("_Channel0001-right"), OmeZarrView.channelsForSelection(
				dataset, OmeZarrView.SELECT_CONFIGURED, settings));
	}

	@Test
	public void projectsTheAlignmentOntoThePlaneEachProjectionStillCarries() {
		double[][] matrix = { { 0.99, -0.1, 5 }, { 0.1, 0.99, -3 } };

		/* A Z projection keeps both axes the alignment acts on: it needs no approximation. */
		assertNull(OmeZarrView.orthogonalAlignment(matrix, 'Z', 101, 51));

		/* A Y projection keeps X, so the half is still mirrored and the alignment becomes a
		 * scale along X plus the offset the collapsed Y contributes at its centre. */
		OmeZarrView.OrthogonalAlignment alongY = OmeZarrView.orthogonalAlignment(matrix, 'Y', 101, 51);
		assertTrue(alongY.flip);
		assertArrayEquals(new double[] { 0.99, 0, -0.1 * 25 + 5 }, alongY.planeMatrix[0], 1e-12);
		assertArrayEquals(new double[] { 0, 1, 0 }, alongY.planeMatrix[1], 1e-12);
		assertEquals(0.1 * 25, alongY.spreadPixels, 1e-12);

		/* An X projection has lost the mirror axis entirely, and moves along Y instead. */
		OmeZarrView.OrthogonalAlignment alongX = OmeZarrView.orthogonalAlignment(matrix, 'X', 101, 51);
		assertFalse(alongX.flip);
		assertArrayEquals(new double[] { 1, 0, 0 }, alongX.planeMatrix[0], 1e-12);
		assertArrayEquals(new double[] { 0, 0.99, 0.1 * 50 - 3 }, alongX.planeMatrix[1], 1e-12);
		assertEquals(0.1 * 50, alongX.spreadPixels, 1e-12);

		/* Without rotation there is nothing left to disagree about, whatever the translation. */
		OmeZarrView.OrthogonalAlignment pure =
				OmeZarrView.orthogonalAlignment(new double[][] { { 1, 0, 9 }, { 0, 1, -4 } }, 'X', 101, 51);
		assertEquals(0.0, pure.spreadPixels, 0);
	}

	@Test
	public void wholePixelTranslationMakesTheOrthogonalOverlayExact() throws Exception {
		/* The claim the approximation rests on: a translation perpendicular to the projection
		 * axis is absorbed by the reduction, and one parallel to it is a plain shift. At whole
		 * pixels there is no interpolation left either, so the overlay must match a genuine
		 * recompute from the aligned volume exactly. */
		OmeZarrDataset dataset = createProjectionFixture(new double[][] { { 1, 0, 2 }, { 0, 1, -1 } });
		OmeZarrView.Options options = new OmeZarrView.Options();
		options.tryGpu = false;
		options.interpolate = false;
		options.operation = OmeZarrView.Operation.FLIP_ALIGN_RIGHT;
		options.requestedChannels.add("_Channel0001-right");

		ImagePlus aligned = OmeZarrView.openMaterializedVolume(dataset, options, 0);
		ImagePlus movieY = OmeZarrView.openProjectionMovie(dataset, "maxY", options);
		ImagePlus movieX = OmeZarrView.openProjectionMovie(dataset, "maxX", options);
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
		OmeZarrDataset dataset = createProjectionFixture(new double[][] {
				{ Math.sqrt(1 - sine * sine), -sine, 0 }, { sine, Math.sqrt(1 - sine * sine), 0 } });
		OmeZarrView.Options options = new OmeZarrView.Options();
		options.tryGpu = false;
		options.operation = OmeZarrView.Operation.FLIP_ALIGN_RIGHT;
		options.requestedChannels.add("_Channel0001-right");

		String warning = OmeZarrView.approximateProjectionWarning(dataset, "maxX",
				OmeZarrView.Operation.FLIP_ALIGN_RIGHT, sine * (dataset.getWidth() - 1) / 2.0);
		assertTrue(warning, warning.contains("ROUGH OVERLAY"));
		assertTrue(warning, warning.contains("not a true maximum projection"));
		assertTrue(warning, warning.contains("do not measure"));
		/* The bound is stated in pixels, and in microns when the dataset is calibrated. */
		String stated = OmeZarrView.approximateProjectionWarning(dataset, "maxX",
				OmeZarrView.Operation.FLIP_ALIGN_RIGHT, 3.5);
		assertTrue(stated, stated.contains("3.50 px"));
		assertTrue(stated, stated.contains("um)"));
		assertTrue(OmeZarrView.approximateProjectionWarning(dataset, "meanY",
				OmeZarrView.Operation.FLIP_ALIGN_RIGHT, 1).contains("not a true mean projection"));

		/* A Z projection carries the same alignment exactly, so it is never marked. */
		ImagePlus exact = OmeZarrView.openProjectionMovie(dataset, "maxZ", options);
		assertFalse(exact.getTitle(), exact.getTitle().contains("rough overlay"));
		assertNull(exact.getProperty("opm.approximateOverlay"));
		exact.close();
	}

	/** A volume together with its genuine maxY/maxX, so the overlay can be checked against truth. */
	private OmeZarrDataset createProjectionFixture(double[][] align) throws Exception {
		File root = new File(folder.getRoot(), "ortho" + align[0][2] + align[1][0] + ".ome.zarr");
		OpmProvenance provenance = OmeZarrDatasetTest.sampleProvenance("ortho", true);
		provenance.alignMatrix = align;
		provenance.alignFlipHalf = BatchChannelOperation.FLIP_RIGHT;
		int w = 6, h = 3, d = 2;
		OmeZarrWriter writer = new OmeZarrWriter(root);
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
		return OmeZarrDataset.read(root);
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
			OmeZarrDataset dataset = createWideDataset(acquisitions);
			int channels = acquisitions * 2;
			String where = "acquisitions=" + acquisitions;

			assertEquals(where, channels, OmeZarrView.channelSetupSlots(dataset));
			String[] sources = OmeZarrView.channelSetupSources(dataset);
			assertEquals(where, channels + 1, sources.length);
			assertEquals(where, BatchChannelOperation.SKIP_CHANNEL, sources[channels]);
			assertEquals(where, dataset.getChannelLabels(),
					Arrays.asList(sources).subList(0, channels));

			/* Channels / side scales the same way: the three fixed entries, one per acquisition
			 * base, then every stored half. */
			List<String> selections = OmeZarrView.selectionOptions(dataset);
			assertEquals(where, 3 + acquisitions + channels, selections.size());
			assertTrue(where, selections.contains(
					ChannelOperationSettings.sourceKey(acquisitions, false)));
		}
	}

	@Test
	public void channelSetupKeepsWhatItCanAndRepairsWhatItCannot() throws Exception {
		OmeZarrDataset dataset = createWideDataset(3);
		ChannelOperationSettings configured = new ChannelOperationSettings();
		// a stored two-file setup, which is wider than the new default of one
		configured.channelOrder[2] = ChannelOperationSettings.sourceKey(2, true);
		configured.channelOrder[3] = ChannelOperationSettings.sourceKey(2, false);

		String[] defaults = OmeZarrView.channelSetupDefaults(dataset, configured);
		assertEquals(6, defaults.length);
		/* The stored two-file setup is honoured where this dataset can honour it, skips and all,
		 * and the dialog is told which of its channels that leaves out. */
		for (int i = 0; i < 4; i++) assertEquals(configured.channelOrder[i], defaults[i]);
		assertEquals(BatchChannelOperation.SKIP_CHANNEL, defaults[4]);
		assertEquals(Arrays.asList("_Channel0003-left", "_Channel0003-right"),
				OmeZarrView.channelsNotSelected(dataset, defaults));

		/* A name from a four-file acquisition this dataset never had repairs to its own slot. */
		configured.channelOrder[0] = ChannelOperationSettings.sourceKey(4, true);
		assertEquals("_Channel0001-left", OmeZarrView.channelSetupDefaults(dataset, configured)[0]);
	}

	@Test
	public void virtualViewsGrowInPlaceAndKeepWhatTheUserSet() throws Exception {
		/* A live acquisition commits further time points while views are open. Those views
		 * must take them up without being rebuilt: a rebuilt StackWindow loses its size,
		 * position, zoom and per-channel display ranges, which is the whole point of growing
		 * the stack in place rather than handing ImageJ a replacement. */
		File root = new File(folder.getRoot(), "growing.ome.zarr");
		writeTimepoints(root, 2);
		OmeZarrDataset small = OmeZarrDataset.read(root);

		OmeZarrView.Options options = new OmeZarrView.Options();
		options.tryGpu = false;
		ImagePlus volume = OmeZarrView.openVirtualVolume(small, options, -1);
		ImagePlus movie = OmeZarrView.openVirtualProjectionMovie(small, "maxZ", options);
		ImagePlus single = OmeZarrView.openVirtualVolume(small, options, 0);
		try {
			assertEquals(2, volume.getNFrames());
			assertEquals(2, movie.getNFrames());
			assertEquals(1, single.getNFrames());

			/* The composite built from the base did not always keep the base's flag: a
			 * two-channel movie then opened as a plain stack whose one slider never reached T. */
			assertTrue(movie.getOpenAsHyperStack());
			assertTrue(volume.getOpenAsHyperStack());

			CompositeImage composite = (CompositeImage) volume;
			composite.getChannelLut(1).min = 7;
			composite.getChannelLut(1).max = 77;
			ImageStack sameStack = volume.getStack();
			short[] frameOneBefore = (short[]) movie.getStack().getProcessor(1).getPixels();

			writeTimepoints(root, 4);
			OmeZarrDataset grown = OmeZarrDataset.read(root);
			assertEquals(4, grown.getTimepointCount());

			assertEquals(4, OmeZarrView.growVirtualView(volume, grown));
			assertEquals(4, OmeZarrView.growVirtualView(movie, grown));
			/* A view opened at one time point stays there; only an all-T view follows. */
			assertEquals(1, OmeZarrView.growVirtualView(single, grown));

			assertEquals(4, volume.getNFrames());
			assertEquals(4, movie.getNFrames());
			assertSame("the stack must be grown, not replaced", sameStack, volume.getStack());
			assertEquals(7.0, composite.getChannelLut(1).min, 0);
			assertEquals(77.0, composite.getChannelLut(1).max, 0);

			/* Already committed pixels are untouched, and the new ones are readable. */
			assertArrayEquals(frameOneBefore, (short[]) movie.getStack().getProcessor(1).getPixels());
			assertNotNull(movie.getStack().getProcessor(movie.getStackSize()));
			assertNotNull(volume.getStack().getProcessor(volume.getStackSize()));

			/* A materialised view has an ordinary stack and simply refuses. */
			ImagePlus material = OmeZarrView.openMaterializedProjectionMovie(grown, "maxZ", options);
			assertEquals(-1, OmeZarrView.growVirtualView(material, grown));
			material.close();
		} finally {
			volume.close();
			movie.close();
			single.close();
		}
	}

	/** Write, or extend, a dataset to {@code timepoints} committed time points. */
	private void writeTimepoints(File root, int timepoints) throws Exception {
		OmeZarrWriter writer = new OmeZarrWriter(root);
		try {
			writer.createAppendableVolume(4, 2, 2, 2);
			writer.createAppendableProjection("maxZ", 4, 2, 2);
			writer.writeMetadata(OmeZarrDatasetTest.sampleProvenance("growing", true),
					Collections.singletonList("maxZ"));
			for (int t = 0; t < timepoints; t++) {
				if (writer.isTimePointCommitted("Time" + (t + 1))) continue;
				writer.writeVolumeChannel(volume(false, t), 0, t);
				writer.writeVolumeChannel(volume(true, t), 1, t);
				writer.writeProjection(plane(4, 2, 501 + t * 100), "maxZ", 0, t);
				writer.writeProjection(plane(4, 2, 701 + t * 100), "maxZ", 1, t);
				writer.commitTimePoint(t, "Time" + (t + 1), Collections.singletonList("raw.tif"), t * 2.5);
			}
			writer.markComplete();
		} finally {
			writer.close();
		}
	}

	/** One time point whose width is {@code acquisitions} files, so two halves each. */
	private OmeZarrDataset createWideDataset(int acquisitions) throws Exception {
		File root = new File(folder.getRoot(), "wide" + acquisitions + ".ome.zarr");
		OpmProvenance provenance = OmeZarrDatasetTest.sampleProvenance("wide", true);
		List<String> labels = new ArrayList<String>();
		for (int a = 1; a <= acquisitions; a++) {
			labels.add(ChannelOperationSettings.sourceKey(a, true));
			labels.add(ChannelOperationSettings.sourceKey(a, false));
		}
		provenance.channelLabels = labels;
		OmeZarrWriter writer = new OmeZarrWriter(root);
		writer.createAppendableVolume(4, 2, 2, labels.size());
		writer.createAppendableProjection("maxZ", 4, 2, labels.size());
		writer.writeMetadata(provenance, Collections.singletonList("maxZ"));
		for (int c = 0; c < labels.size(); c++) {
			writer.writeVolumeChannel(volume(c % 2 == 1, 0), c, 0);
			writer.writeProjection(plane(4, 2, 500 + c), "maxZ", c, 0);
		}
		writer.commitTimePoint(0, "Time1", Collections.singletonList("raw.tif"), 0);
		writer.markComplete();
		return OmeZarrDataset.read(root);
	}

	private OmeZarrDataset createDataset() throws Exception {
		File root = new File(folder.getRoot(), "viewer.ome.zarr");
		OpmProvenance provenance = OmeZarrDatasetTest.sampleProvenance("viewer", true);
		OmeZarrWriter writer = new OmeZarrWriter(root);
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
		return OmeZarrDataset.read(root);
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
