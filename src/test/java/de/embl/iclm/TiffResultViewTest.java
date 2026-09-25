package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

/**
 * The deflated-TIFF side of the data viewer, over both layouts the writer produces.
 *
 * <p>Every fixture here is written through {@link VolumeIO#saveTiff}, the same call Deskew
 * Batch uses, so the ImageJ description these tests read C and Z out of is the one production
 * writes rather than one composed for the test.
 */
public class TiffResultViewTest {

	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	private static final int WIDTH = 24;
	private static final int HEIGHT = 16;
	private static final int CHANNELS = 2;
	private static final int SLICES = 3;

	/** A value that identifies the plane it came from, so a misindexed read is visible. */
	private static short value(int timepoint, int channel, int z, int x, int y) {
		return (short) (1000 * timepoint + 100 * channel + 10 * z + (x % 7) + (y % 5) * 7);
	}

	private static ImagePlus volume(int timepoint) {
		ImageStack stack = new ImageStack(WIDTH, HEIGHT);
		for (int z = 0; z < SLICES; z++)
			for (int c = 0; c < CHANNELS; c++) {
				short[] pixels = new short[WIDTH * HEIGHT];
				for (int y = 0; y < HEIGHT; y++)
					for (int x = 0; x < WIDTH; x++)
						pixels[y * WIDTH + x] = value(timepoint, c, z, x, y);
				stack.addSlice("c" + c + "z" + z, new ShortProcessor(WIDTH, HEIGHT, pixels, null));
			}
		ImagePlus image = new ImagePlus("volume", stack);
		image.setDimensions(CHANNELS, SLICES, 1);
		return image;
	}

	private static ImagePlus projection(int timepoint) {
		ImageStack stack = new ImageStack(WIDTH, HEIGHT);
		for (int c = 0; c < CHANNELS; c++) {
			short[] pixels = new short[WIDTH * HEIGHT];
			for (int y = 0; y < HEIGHT; y++)
				for (int x = 0; x < WIDTH; x++)
					pixels[y * WIDTH + x] = value(timepoint, c, 0, x, y);
			stack.addSlice("c" + c, new ShortProcessor(WIDTH, HEIGHT, pixels, null));
		}
		ImagePlus image = new ImagePlus("projection", stack);
		image.setDimensions(CHANNELS, 1, 1);
		return image;
	}

	/** One whole camera width in one _ChannelNNNN file, before any half composition. */
	private static ImagePlus wholeChannel(int timepoint, int acquisition) {
		ImageStack stack = new ImageStack(WIDTH, HEIGHT);
		for (int z = 0; z < SLICES; z++) {
			short[] pixels = new short[WIDTH * HEIGHT];
			for (int y = 0; y < HEIGHT; y++)
				for (int x = 0; x < WIDTH; x++)
					pixels[y * WIDTH + x] = (short) (10000 * acquisition + 1000 * timepoint
							+ 100 * z + 10 * y + x);
			stack.addSlice(new ShortProcessor(WIDTH, HEIGHT, pixels, null));
		}
		ImagePlus image = new ImagePlus("whole", stack);
		image.setDimensions(1, SLICES, 1);
		return image;
	}

	private static File writeWhole(File root, int timepoint, int acquisition) throws Exception {
		File deskew = new File(root, "deskew");
		assertTrue(deskew.isDirectory() || deskew.mkdirs());
		File file = new File(deskew, String.format(
				"split_Time%06d_Channel%04d-deskewed.tif", timepoint, acquisition));
		assertTrue(VolumeIO.saveTiff(wholeChannel(timepoint, acquisition), file.getPath()));
		return file;
	}

	private static TiffResultDataset acquisition(File root, int number) {
		for (TiffResultDataset dataset : TiffResultDataset.discover(root))
			if (dataset.getDisplayName().contains(String.format("_Channel%04d", number))) return dataset;
		throw new AssertionError("No Channel" + number + " result in " + root);
	}

	private static String stem(int timepoint) {
		return String.format("run_Position0001_Time%06d_Channels0001-0002-aligned", timepoint);
	}

	/** Write one acquisition, either into view sub-folders or all into one folder. */
	private File writeResult(File root, int timepoints, boolean separate) throws Exception {
		for (int t = 1; t <= timepoints; t++) {
			File volumeFolder = separate ? new File(root, "deskew") : root;
			File projectionFolder = separate ? new File(root, "maxZ") : root;
			assertTrue(volumeFolder.isDirectory() || volumeFolder.mkdirs());
			assertTrue(projectionFolder.isDirectory() || projectionFolder.mkdirs());
			assertTrue(VolumeIO.saveTiff(volume(t),
					new File(volumeFolder, stem(t) + "-deskewed.tif").getPath()));
			assertTrue(VolumeIO.saveTiff(projection(t),
					new File(projectionFolder, stem(t) + "-deskewed-maxZprojection.tif").getPath()));
		}
		return root;
	}

	private static TiffResultDataset only(File root) {
		List<TiffResultDataset> found = TiffResultDataset.discover(root);
		assertEquals("one acquisition in " + root, 1, found.size());
		return found.get(0);
	}

	@Test
	public void bothResultLayoutsAreTheSameDataset() throws Exception {
		File nested = writeResult(temporary.newFolder("nested"), 2, true);
		File flat = writeResult(temporary.newFolder("flat"), 2, false);

		TiffResultDataset a = only(nested);
		TiffResultDataset b = only(flat);
		assertEquals(a.getDisplayName(), b.getDisplayName());
		for (TiffResultDataset dataset : Arrays.asList(a, b)) {
			assertTrue(dataset.hasVolume());
			assertEquals(Arrays.asList("maxZ"), dataset.getAvailableProjections());
			assertEquals(2, dataset.getView(TiffResultDataset.VOLUME).frameCount());
			assertEquals(2, dataset.getView("maxZ").frameCount());
		}
	}

	/** The time number is what orders the list, not the order the directory happens to list. */
	@Test
	public void timePointsAreOrderedByTheirTimeNumber() throws Exception {
		File root = temporary.newFolder("order");
		File folder = new File(root, "deskew");
		assertTrue(folder.mkdirs());
		for (int t : new int[] { 11, 2, 1 })
			assertTrue(VolumeIO.saveTiff(volume(t),
					new File(folder, stem(t) + "-deskewed.tif").getPath()));
		TiffResultDataset.View view = only(root).getView(TiffResultDataset.VOLUME);
		assertEquals(3, view.frameCount());
		assertEquals(1L, view.frame(0).timeNumber);
		assertEquals(2L, view.frame(1).timeNumber);
		assertEquals(11L, view.frame(2).timeNumber);
	}

	/** A view that is one time point behind another is reported as it is, not averaged away. */
	@Test
	public void viewsAreCountedSeparately() throws Exception {
		File root = temporary.newFolder("partial");
		writeResult(root, 3, true);
		File extra = new File(new File(root, "maxZ"), stem(4) + "-deskewed-maxZprojection.tif");
		assertTrue(VolumeIO.saveTiff(projection(4), extra.getPath()));

		TiffResultDataset dataset = only(root);
		assertEquals(3, dataset.getView(TiffResultDataset.VOLUME).frameCount());
		assertEquals(4, dataset.getView("maxZ").frameCount());
		assertEquals("the longest view is what the dataset has reached", 4,
				dataset.getTimepointCount());
	}

	@Test
	public void aVirtualVolumeReadsThePlaneItWasAskedFor() throws Exception {
		File root = writeResult(temporary.newFolder("virtual"), 2, true);
		TiffResultDataset dataset = only(root);
		ImagePlus image = TiffResultView.openVirtual(
				dataset, TiffResultDataset.VOLUME, new TiffResultView.Options());
		try {
			assertEquals(CHANNELS, image.getNChannels());
			assertEquals(SLICES, image.getNSlices());
			assertEquals(2, image.getNFrames());
			assertTrue(image.getStack().isVirtual());
			for (int t = 1; t <= 2; t++)
				for (int z = 1; z <= SLICES; z++)
					for (int c = 1; c <= CHANNELS; c++) {
						ShortProcessor plane = (ShortProcessor) image.getStack()
								.getProcessor(image.getStackIndex(c, z, t));
						assertEquals("C" + c + " Z" + z + " T" + t,
								value(t, c - 1, z - 1, 3, 4), (short) plane.get(3, 4));
					}
		} finally { image.close(); }
	}

	/** A projection TIFF is a volume of one Z, and opens through the same path. */
	@Test
	public void aProjectionMovieIsChannelsAndTime() throws Exception {
		File root = writeResult(temporary.newFolder("movie"), 3, true);
		ImagePlus image = TiffResultView.openVirtual(
				only(root), "maxZ", new TiffResultView.Options());
		try {
			assertEquals(CHANNELS, image.getNChannels());
			assertEquals(1, image.getNSlices());
			assertEquals(3, image.getNFrames());
			ShortProcessor plane = (ShortProcessor) image.getStack()
					.getProcessor(image.getStackIndex(2, 1, 3));
			assertEquals(value(3, 1, 0, 5, 6), (short) plane.get(5, 6));
		} finally { image.close(); }
	}

	/**
	 * Separate whole-width acquisition files become one lazy four-channel overlay. The right
	 * halves are mirrored at read time, while the TIFF pixels on disk remain untouched.
	 */
	@Test
	public void wholeWidthSeriesCanBeSplitIntoVirtualOverlayChannels() throws Exception {
		File root = temporary.newFolder("virtual-channels");
		for (int t = 1; t <= 2; t++) {
			writeWhole(root, t, 1);
			writeWhole(root, t, 2);
		}
		TiffResultDataset one = acquisition(root, 1);
		TiffResultDataset two = acquisition(root, 2);
		TiffResultView.Options options = new TiffResultView.Options();
		for (TiffResultDataset dataset : Arrays.asList(one, two)) {
			int acquisition = dataset == one ? 1 : 2;
			options.virtualChannels.add(new TiffResultView.VirtualChannel(dataset,
					ChannelOperationSettings.sourceKey(acquisition, true),
					TiffResultView.HorizontalPart.LEFT, null, false));
			options.virtualChannels.add(new TiffResultView.VirtualChannel(dataset,
					ChannelOperationSettings.sourceKey(acquisition, false),
					TiffResultView.HorizontalPart.RIGHT, null, false));
		}

		ImagePlus image = TiffResultView.openVirtual(one, TiffResultDataset.VOLUME, options);
		try {
			assertEquals(WIDTH / 2, image.getWidth());
			assertEquals(4, image.getNChannels());
			assertEquals(SLICES, image.getNSlices());
			assertEquals(2, image.getNFrames());
			assertEquals("[_Channel0001-left, _Channel0001-right, _Channel0002-left, "
					+ "_Channel0002-right]", image.getProperty("opm.channelLabels"));

			ShortProcessor c1left = (ShortProcessor) image.getStack()
					.getProcessor(image.getStackIndex(1, 2, 2));
			ShortProcessor c1right = (ShortProcessor) image.getStack()
					.getProcessor(image.getStackIndex(2, 2, 2));
			ShortProcessor c2left = (ShortProcessor) image.getStack()
					.getProcessor(image.getStackIndex(3, 2, 2));
			assertEquals((short) (10000 + 2000 + 100 + 30 + 4), (short) c1left.get(4, 3));
			/* Output x=4 of the mirrored right half came from full-width x=23-4. */
			assertEquals((short) (10000 + 2000 + 100 + 30 + (WIDTH - 1 - 4)),
					(short) c1right.get(4, 3));
			assertEquals((short) (20000 + 2000 + 100 + 30 + 4), (short) c2left.get(4, 3));
		} finally { image.close(); }
	}

	/** A growing overlay exposes only time numbers present in every requested source. */
	@Test
	public void virtualOverlayPairsGrowingSeriesByTimeNumber() throws Exception {
		File root = temporary.newFolder("virtual-growing");
		writeWhole(root, 1, 1);
		writeWhole(root, 2, 1);
		writeWhole(root, 1, 2);
		TiffResultDataset one = acquisition(root, 1);
		TiffResultDataset two = acquisition(root, 2);
		TiffResultView.Options options = new TiffResultView.Options();
		options.virtualChannels.add(new TiffResultView.VirtualChannel(one,
				ChannelOperationSettings.sourceKey(1, true), TiffResultView.HorizontalPart.LEFT,
				null, false));
		options.virtualChannels.add(new TiffResultView.VirtualChannel(two,
				ChannelOperationSettings.sourceKey(2, true), TiffResultView.HorizontalPart.LEFT,
				null, false));

		ImagePlus image = TiffResultView.openVirtual(one, TiffResultDataset.VOLUME, options);
		try {
			assertEquals("Channel0002 has not written time 2 yet", 1, image.getNFrames());
			writeWhole(root, 2, 2);
			assertEquals(2, TiffResultView.growVirtualView(image, one));
			assertEquals(2, image.getNFrames());
			ShortProcessor second = (ShortProcessor) image.getStack()
					.getProcessor(image.getStackIndex(2, 1, 2));
			assertEquals((short) (20000 + 2000 + 2), (short) second.get(2, 0));
		} finally { image.close(); }
	}

	/** A tagged per-source matrix is evaluated on the virtual half, never written back. */
	@Test
	public void virtualTiffChannelAppliesItsRigidMatrixAtReadTime() throws Exception {
		File root = temporary.newFolder("virtual-aligned");
		writeWhole(root, 1, 2);
		TiffResultDataset two = acquisition(root, 2);
		String source = ChannelOperationSettings.sourceKey(2, true);
		double[][] translation = { { 1, 0, 1 }, { 0, 1, -1 } };
		AlignmentMatrixSet matrices = new AlignmentMatrixSet(source);
		matrices.put(source, translation);
		TiffResultView.Options options = new TiffResultView.Options();
		options.virtualChannels.add(new TiffResultView.VirtualChannel(two, source,
				TiffResultView.HorizontalPart.LEFT, matrices, false));

		ImagePlus virtual = TiffResultView.openVirtual(two, TiffResultDataset.VOLUME, options);
		ImagePlus materialised = TiffResultView.openMaterialised(
				two, TiffResultDataset.VOLUME, options, 0, 1);
		ImagePlus sourceImage = wholeChannel(1, 2);
		try {
			ImageProcessor raw = sourceImage.getStack().getProcessor(1).duplicate();
			raw.setRoi(0, 0, WIDTH / 2, HEIGHT);
			ImageProcessor expected = OpmRuntimeAlignment.transformPlaneWithoutFlip(
					raw.crop(), translation, true);
			for (ImagePlus shown : Arrays.asList(virtual, materialised)) {
				ImageProcessor actual = shown.getStack().getProcessor(1);
				for (int y = 0; y < HEIGHT; y++)
					for (int x = 0; x < WIDTH / 2; x++)
						assertEquals("x=" + x + " y=" + y, expected.get(x, y), actual.get(x, y));
			}
		} finally {
			virtual.close();
			materialised.close();
			sourceImage.close();
		}
	}

	/**
	 * A region and a channel range select planes and pixels, and agree between the two paths.
	 *
	 * <p>Virtual and materialised are separate implementations of the same selection, so the
	 * test that matters is that they return the same pixels for the same request.
	 */
	@Test
	public void aRegionSelectsTheSamePixelsVirtuallyAndMaterialised() throws Exception {
		File root = writeResult(temporary.newFolder("region"), 2, true);
		TiffResultDataset dataset = only(root);

		TiffResultView.Options options = new TiffResultView.Options();
		options.bounds = new OmeZarrView.Bounds(5, 3, 9, 7, 1, 3);	// z 1..2 of 0..2
		options.firstOutputChannel = 1;
		options.outputChannelCount = 1;

		ImagePlus virtual = TiffResultView.openVirtual(dataset, TiffResultDataset.VOLUME, options);
		ImagePlus solid = TiffResultView.openMaterialised(
				dataset, TiffResultDataset.VOLUME, options, 0, 2);
		try {
			for (ImagePlus image : Arrays.asList(virtual, solid)) {
				assertEquals(1, image.getNChannels());
				assertEquals(2, image.getNSlices());
				assertEquals(2, image.getNFrames());
				assertEquals(9, image.getWidth());
				assertEquals(7, image.getHeight());
			}
			for (int t = 1; t <= 2; t++)
				for (int z = 1; z <= 2; z++) {
					ShortProcessor lazy = (ShortProcessor) virtual.getStack()
							.getProcessor(virtual.getStackIndex(1, z, t));
					ShortProcessor read = (ShortProcessor) solid.getStack()
							.getProcessor(solid.getStackIndex(1, z, t));
					for (int y = 0; y < 7; y++)
						for (int x = 0; x < 9; x++) {
							short expected = value(t, 1, z, x + 5, y + 3);
							assertEquals("virtual Z" + z + " T" + t,
									expected, (short) lazy.get(x, y));
							assertEquals("materialised Z" + z + " T" + t,
									expected, (short) read.get(x, y));
						}
				}
		} finally { virtual.close(); solid.close(); }
	}

	/** The figure quoted before allocating is the figure allocated. */
	@Test
	public void theSizeEstimateIsTheSizeAllocated() throws Exception {
		File root = writeResult(temporary.newFolder("estimate"), 1, true);
		TiffResultView.Options options = new TiffResultView.Options();
		options.bounds = new OmeZarrView.Bounds(2, 2, 10, 8, 0, 2);
		assertEquals(2L * 10 * 8 * 2 * CHANNELS, TiffResultView.estimateMaterialisedBytes(
				only(root), TiffResultDataset.VOLUME, options));
	}

	/** A view opened mid-acquisition takes on the time points written after it. */
	@Test
	public void aVirtualViewGrowsWithTheFolder() throws Exception {
		File root = writeResult(temporary.newFolder("growing"), 2, true);
		TiffResultDataset dataset = only(root);
		ImagePlus image = TiffResultView.openVirtual(
				dataset, TiffResultDataset.VOLUME, new TiffResultView.Options());
		try {
			assertEquals(2, image.getNFrames());
			assertTrue("a TIFF view was never flagged, so a single-channel result opened with one"
					+ " slider over every plane of every time point", image.getOpenAsHyperStack());
			assertEquals("nothing new yet", 2, TiffResultView.growVirtualView(image, dataset));

			assertTrue(VolumeIO.saveTiff(volume(3), new File(new File(root, "deskew"),
					stem(3) + "-deskewed.tif").getPath()));
			assertEquals(3, TiffResultView.growVirtualView(image, dataset));
			assertEquals(3, image.getNFrames());
			ShortProcessor plane = (ShortProcessor) image.getStack()
					.getProcessor(image.getStackIndex(1, 2, 3));
			assertEquals(value(3, 0, 1, 2, 2), (short) plane.get(2, 2));
		} finally { image.close(); }
	}

	/** Growing is refused for an image that is not one of these views. */
	@Test
	public void growingIgnoresForeignImages() throws Exception {
		File root = writeResult(temporary.newFolder("foreign"), 1, true);
		assertEquals(-1, TiffResultView.growVirtualView(volume(1), only(root)));
	}

	/**
	 * A row band is the same pixels the whole plane would have given for those rows.
	 *
	 * <p>This is what makes a region read cheaper than a whole-plane read and a crop, so the
	 * pixels have to be identical or the saving is bought with wrong data.
	 */
	@Test
	public void aRowBandMatchesTheWholePlane() throws Exception {
		File root = temporary.newFolder("rows");
		File file = new File(root, stem(1) + "-deskewed.tif");
		assertTrue(VolumeIO.saveTiff(volume(1), file.getPath()));

		FastTiffReader.PlaneReader reader = new FastTiffReader.PlaneReader(file);
		try {
			for (int plane = 0; plane < CHANNELS * SLICES; plane++) {
				short[] whole = reader.readPixels(plane);
				for (int[] band : new int[][] { { 0, HEIGHT }, { 0, 1 }, { 5, 4 },
						{ HEIGHT - 1, 1 }, { 3, HEIGHT - 3 } }) {
					short[] rows = reader.readRows(plane, band[0], band[1]);
					assertEquals(band[1] * WIDTH, rows.length);
					for (int row = 0; row < band[1]; row++)
						for (int x = 0; x < WIDTH; x++)
							assertEquals("plane " + plane + " row " + (band[0] + row),
									whole[(band[0] + row) * WIDTH + x], rows[row * WIDTH + x]);
				}
			}
		} finally { reader.close(); }
	}

	/** A band outside the plane is refused rather than clamped into silence. */
	@Test
	public void aRowBandOutsideThePlaneIsRefused() throws Exception {
		File root = temporary.newFolder("rowsbad");
		File file = new File(root, stem(1) + "-deskewed.tif");
		assertTrue(VolumeIO.saveTiff(volume(1), file.getPath()));
		FastTiffReader.PlaneReader reader = new FastTiffReader.PlaneReader(file);
		try {
			reader.readRows(0, HEIGHT - 2, 5);
			fail("expected a refusal");
		} catch (Exception expected) {
			assertNotNull(expected.getMessage());
		} finally { reader.close(); }
	}

	/** Files that are neither a volume nor a projection are not mistaken for either. */
	@Test
	public void unrelatedTiffsAreNotCollected() throws Exception {
		File root = temporary.newFolder("mixed");
		writeResult(root, 1, false);
		assertTrue(VolumeIO.saveTiff(volume(1),
				new File(root, "raw_Position0001_Time000001_Channel0001.tif").getPath()));
		TiffResultDataset dataset = only(root);
		assertEquals(1, dataset.getView(TiffResultDataset.VOLUME).frameCount());
	}
}
