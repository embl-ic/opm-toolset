package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests for reading only part of a volume, and for exporting that part.
 *
 * <p>Two things have to hold, and they are easy to get subtly wrong in opposite directions.
 * The region is expressed in the coordinates of the <em>view</em>, not of the stored array, so
 * a box drawn on a flipped or side-by-side view has to cut out the pixels that view showed;
 * and the crop has to happen after the runtime transform, or an aligned region would come from
 * the wrong place. Both are checked against the same pixels read without a region.
 */
public class OmeZarrRegionTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final int WIDTH = 8;
	private static final int HEIGHT = 6;
	private static final int DEPTH = 4;
	private static final int TIMEPOINTS = 2;


	// ---- the box itself -------------------------------------------------------------

	@Test
	public void aBoxIsClampedIntoTheViewRatherThanRefused() {
		OmeZarrView.Bounds asked = new OmeZarrView.Bounds(-4, 30, 1000, 1000, -2, 999);
		OmeZarrView.Bounds clamped = asked.clampedTo(WIDTH, HEIGHT, DEPTH);

		assertEquals(0, clamped.x);
		assertEquals(HEIGHT - 1, clamped.y);
		assertEquals(WIDTH, clamped.width);
		assertEquals(1, clamped.height);
		assertEquals(0, clamped.zStart);
		assertEquals(DEPTH, clamped.zEnd);
		assertEquals(DEPTH, clamped.depth());
	}

	@Test
	public void zeroSizesMeanTheWholeExtent() {
		OmeZarrView.Bounds clamped = new OmeZarrView.Bounds(0, 0, 0, 0, 0, 0)
				.clampedTo(WIDTH, HEIGHT, DEPTH);
		assertEquals(WIDTH, clamped.width);
		assertEquals(HEIGHT, clamped.height);
		assertEquals(DEPTH, clamped.depth());
		assertTrue("a box that covers everything is recognised as such",
				clamped.covers(WIDTH, HEIGHT, DEPTH));
	}

	@Test
	public void aSmallerBoxDoesNotCoverTheView() {
		assertTrue(OmeZarrView.Bounds.full(WIDTH, HEIGHT, DEPTH).covers(WIDTH, HEIGHT, DEPTH));
		assertTrue(!new OmeZarrView.Bounds(1, 0, WIDTH - 1, HEIGHT, 0, DEPTH)
				.covers(WIDTH, HEIGHT, DEPTH));
		assertTrue(!new OmeZarrView.Bounds(0, 0, WIDTH, HEIGHT, 1, DEPTH)
				.covers(WIDTH, HEIGHT, DEPTH));
	}


	// ---- reading a region -----------------------------------------------------------

	@Test
	public void aVirtualRegionIsTheSamePixelsTheWholeViewWouldShow() throws Exception {
		OmeZarrDataset dataset = createDataset();

		ImagePlus whole = OmeZarrView.openVirtualVolume(dataset, storedChannels(null), -1);
		OmeZarrView.Bounds box = new OmeZarrView.Bounds(2, 1, 4, 3, 1, 3);
		ImagePlus part = OmeZarrView.openVirtualVolume(dataset, storedChannels(box), -1);
		try {
			assertEquals(4, part.getWidth());
			assertEquals(3, part.getHeight());
			assertEquals("z is cropped as well as xy", 2, part.getNSlices());
			assertEquals("channels are untouched by a spatial box",
					whole.getNChannels(), part.getNChannels());
			assertEquals("time is untouched by a spatial box", TIMEPOINTS, part.getNFrames());
			assertTrue("a region of a virtual view is still virtual", part.getStack().isVirtual());
			assertTrue("the title must say the view is cropped",
					part.getTitle().contains("region"));

			for (int t = 1; t <= TIMEPOINTS; t++)
				for (int c = 1; c <= part.getNChannels(); c++)
					for (int z = 1; z <= part.getNSlices(); z++) {
						ImageProcessor cropped = part.getStack()
								.getProcessor(part.getStackIndex(c, z, t));
						ImageProcessor full = whole.getStack()
								.getProcessor(whole.getStackIndex(c, z + box.zStart, t));
						full.setRoi(box.x, box.y, box.width, box.height);
						assertArrayEquals("c=" + c + " z=" + z + " t=" + t,
								(short[]) full.crop().getPixels(), (short[]) cropped.getPixels());
					}
		} finally {
			close(whole);
			close(part);
		}
	}

	/** The crop must follow the runtime transform, not precede it. */
	@Test
	public void aRegionOfAnAlignedViewMatchesTheAlignedWhole() throws Exception {
		OmeZarrDataset dataset = createDataset();
		OmeZarrView.Bounds box = new OmeZarrView.Bounds(1, 2, 5, 3, 0, DEPTH);

		OmeZarrView.Options wholeOptions = storedChannels(null);
		wholeOptions.operation = OmeZarrView.Operation.FLIP_ALIGN_RIGHT;
		OmeZarrView.Options partOptions = storedChannels(box);
		partOptions.operation = OmeZarrView.Operation.FLIP_ALIGN_RIGHT;

		ImagePlus whole = OmeZarrView.openVirtualVolume(dataset, wholeOptions, 0);
		ImagePlus part = OmeZarrView.openVirtualVolume(dataset, partOptions, 0);
		try {
			for (int c = 1; c <= part.getNChannels(); c++) {
				ImageProcessor full = whole.getStack().getProcessor(whole.getStackIndex(c, 1, 1));
				full.setRoi(box.x, box.y, box.width, box.height);
				ImageProcessor cropped = part.getStack().getProcessor(part.getStackIndex(c, 1, 1));
				assertArrayEquals("channel " + c,
						(short[]) full.crop().getPixels(), (short[]) cropped.getPixels());
			}
		} finally {
			close(whole);
			close(part);
		}
	}

	/** A side-by-side view is twice as wide, and the box is measured against that. */
	@Test
	public void theViewExtentIsWhatTheRegionIsMeasuredAgainst() throws Exception {
		OmeZarrDataset dataset = createDataset();
		OmeZarrView.Options stored = storedChannels(null);
		assertArrayEquals(new int[] { WIDTH, HEIGHT, DEPTH },
				OmeZarrView.viewExtent(dataset, stored));

		OmeZarrView.Options side = storedChannels(null);
		side.operation = OmeZarrView.Operation.SIDE_BY_SIDE;
		assertArrayEquals("side by side doubles the width",
				new int[] { WIDTH * 2, HEIGHT, DEPTH }, OmeZarrView.viewExtent(dataset, side));
	}

	@Test
	public void aMaterialisedRegionCostsOnlyTheRegion() throws Exception {
		OmeZarrDataset dataset = createDataset();
		long whole = OmeZarrView.estimateMaterializedBytes(dataset, storedChannels(null), 0);
		long part = OmeZarrView.estimateMaterializedBytes(dataset,
				storedChannels(new OmeZarrView.Bounds(0, 0, 4, 3, 0, 2)), 0);
		assertEquals("half the width, half the height, half the depth", whole / 8, part);
	}

	/** A projection has already collapsed an axis, so an XYZ box cannot describe a region of it. */
	@Test
	public void projectionMoviesIgnoreTheRegion() throws Exception {
		OmeZarrDataset dataset = createDataset();
		OmeZarrView.Options options = storedChannels(new OmeZarrView.Bounds(1, 1, 2, 2, 0, 1));
		ImagePlus movie = OmeZarrView.openVirtualProjectionMovie(dataset, "maxZ", options);
		try {
			assertEquals(WIDTH, movie.getWidth());
			assertEquals(HEIGHT, movie.getHeight());
		} finally {
			close(movie);
		}
	}


	// ---- exporting a region ---------------------------------------------------------

	@Test
	public void exportWritesTheRegionAsADeflatedTiffWithoutOpeningTheVolume() throws Exception {
		OmeZarrDataset dataset = createDataset();
		OmeZarrView.Bounds box = new OmeZarrView.Bounds(3, 2, 4, 3, 1, 3);
		File file = new File(folder.getRoot(), "region.tif");

		String report = OmeZarrView.exportRegionToTiff(
				dataset, storedChannels(box), 0, TIMEPOINTS, file, 1);

		assertNotNull(report);
		assertTrue(report, report.contains("4x3x2"));
		assertTrue("the file was written", file.isFile());

		FastTiffReader.Info info = FastTiffReader.parse(file);
		assertEquals(4, info.width);
		assertEquals(3, info.height);
		assertEquals("channels * z * t planes", 2 * 2 * TIMEPOINTS, info.depth());
		assertEquals(FastTiffReader.COMPRESSION_DEFLATE, info.compression);

		// plane order is XYCZT, the order ImageJ reads back
		ImagePlus reference = OmeZarrView.openVirtualVolume(dataset, storedChannels(box), -1);
		FastTiffReader.PlaneReader reader = new FastTiffReader.PlaneReader(file);
		try {
			for (int index = 0; index < info.depth(); index++)
				assertArrayEquals("plane " + index,
						(short[]) reference.getStack().getProcessor(index + 1).getPixels(),
						(short[]) reader.readPlane(index).getPixels());
		} finally {
			reader.close();
			close(reference);
		}
	}

	@Test
	public void exportOfASingleTimepointStopsAtThatTimepoint() throws Exception {
		OmeZarrDataset dataset = createDataset();
		File file = new File(folder.getRoot(), "one-t.tif");
		OmeZarrView.exportRegionToTiff(dataset, storedChannels(null), 1, 1, file, 0);

		FastTiffReader.Info info = FastTiffReader.parse(file);
		assertEquals(2 * DEPTH, info.depth());

		ImagePlus second = OmeZarrView.openVirtualVolume(dataset, storedChannels(null), 1);
		FastTiffReader.PlaneReader reader = new FastTiffReader.PlaneReader(file);
		try {
			assertArrayEquals((short[]) second.getStack().getProcessor(1).getPixels(),
					(short[]) reader.readPlane(0).getPixels());
		} finally {
			reader.close();
			close(second);
		}
	}

	@Test
	public void materialisedRegionCanSelectARangeOfComposedOutputChannels() throws Exception {
		OmeZarrDataset dataset = createDataset();
		OmeZarrView.Bounds box = new OmeZarrView.Bounds(2, 1, 4, 3, 1, 3);
		OmeZarrView.Options selected = storedChannels(box);
		selected.firstOutputChannel = 1;
		selected.outputChannelCount = 1;

		assertEquals(Collections.singletonList("_Channel0001-right"),
				OmeZarrView.outputChannelLabels(dataset, selected));
		ImagePlus material = OmeZarrView.openMaterializedVolume(dataset, selected, 0, 2);
		ImagePlus reference = OmeZarrView.openVirtualVolume(dataset, storedChannels(box), -1);
		try {
			assertEquals(4, material.getWidth());
			assertEquals(3, material.getHeight());
			assertEquals(1, material.getNChannels());
			assertEquals(2, material.getNSlices());
			assertEquals(2, material.getNFrames());
			for (int t = 1; t <= 2; t++) {
				for (int z = 1; z <= 2; z++) {
					int wanted = material.getStackIndex(1, z, t);
					int source = reference.getStackIndex(2, z, t);
					assertArrayEquals((short[]) reference.getStack().getProcessor(source).getPixels(),
							(short[]) material.getStack().getProcessor(wanted).getPixels());
				}
			}
		} finally {
			close(material);
			close(reference);
		}
	}


	// ---- helpers --------------------------------------------------------------------

	private static OmeZarrView.Options storedChannels(OmeZarrView.Bounds bounds) {
		OmeZarrView.Options options = new OmeZarrView.Options();
		options.tryGpu = false;
		options.bounds = bounds;
		return options;
	}

	private static void close(ImagePlus image) {
		if (image == null) return;
		image.changes = false;
		image.close();
	}

	private OmeZarrDataset createDataset() throws Exception {
		File root = new File(folder.getRoot(), "region.ome.zarr");
		OpmProvenance provenance = OmeZarrDatasetTest.sampleProvenance("region", true);
		OmeZarrWriter writer = new OmeZarrWriter(root);
		writer.createAppendableVolume(WIDTH, HEIGHT, DEPTH, 2);
		writer.createAppendableProjection("maxZ", WIDTH, HEIGHT, 2);
		writer.writeMetadata(provenance, Arrays.asList("maxZ"));
		for (int t = 0; t < TIMEPOINTS; t++) {
			writer.writeVolumeChannel(volume(0, t), 0, t);
			writer.writeVolumeChannel(volume(1, t), 1, t);
			writer.writeProjection(plane(WIDTH, HEIGHT, 9000 + t * 100), "maxZ", 0, t);
			writer.writeProjection(plane(WIDTH, HEIGHT, 9500 + t * 100), "maxZ", 1, t);
			writer.commitTimePoint(t, "Time" + (t + 1), Collections.singletonList("raw.tif"), t);
		}
		writer.markComplete();
		return OmeZarrDataset.read(root);
	}

	/** Every voxel distinct, so a wrong offset in x, y, z, c or t cannot pass unnoticed. */
	private static ImagePlus volume(int channel, int timepoint) {
		ImageStack stack = new ImageStack(WIDTH, HEIGHT);
		for (int z = 0; z < DEPTH; z++) {
			short[] pixels = new short[WIDTH * HEIGHT];
			for (int y = 0; y < HEIGHT; y++)
				for (int x = 0; x < WIDTH; x++)
					pixels[y * WIDTH + x] = (short) (
							x + 10 * y + 100 * z + 1000 * channel + 3000 * timepoint + 1);
			stack.addSlice(new ShortProcessor(WIDTH, HEIGHT, pixels, null));
		}
		return new ImagePlus(channel == 0 ? "left" : "right", stack);
	}

	private static ImagePlus plane(int width, int height, int first) {
		short[] pixels = new short[width * height];
		for (int i = 0; i < pixels.length; i++) pixels[i] = (short) (first + i);
		return new ImagePlus("projection", new ShortProcessor(width, height, pixels, null));
	}
}
