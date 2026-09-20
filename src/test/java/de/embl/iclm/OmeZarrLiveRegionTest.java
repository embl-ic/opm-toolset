package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;

import java.io.File;
import java.util.Collections;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Reading part of a volume, while that volume is still being written, and paying only for it.
 *
 * <p>Three claims are pinned here, and each fails in a way the others would not catch.
 *
 * <p>The first is that a cropped view is still a live view. A region fixes X, Y and Z; growth
 * extends T. They are perpendicular, but only if the crop is re-derived from the same extents
 * every time, so a view opened with a region has to take up new time points at the cropped size
 * and keep every already committed plane byte for byte.
 *
 * <p>The second is that a region describes the next view and nothing else. The viewer lets the
 * region be changed while windows are open, which is only safe because every view holds a copy
 * of the options it was built from; if that copy were ever dropped, an open window would start
 * showing a different part of the volume on its next repaint.
 *
 * <p>The third is that the region is read rather than cropped out of whole planes. A plane is a
 * grid of chunks, each its own gzip stream, and the pixels come out identical whether the box
 * is cut before or after the read - so nothing about the pixels can show which happened. The
 * chunk count can, which is why it is instrumented.
 */
public class OmeZarrLiveRegionTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	/** Wider and taller than one 512 x 512 chunk, so the chunk grid is 3 x 2 per plane. */
	private static final int WIDE = 1200;
	private static final int TALL = 600;
	private static final int DEPTH = 2;
	private static final int CHUNK_COLUMNS = 3;
	private static final int CHUNK_ROWS = 2;

	/** Small, because the growth tests care about time points and not about chunk counts. */
	private static final int SMALL_WIDTH = 8;
	private static final int SMALL_HEIGHT = 6;
	private static final int SMALL_DEPTH = 4;

	// ---- a cropped view is still a live view ----------------------------------------

	@Test
	public void aCroppedVirtualViewGrowsWithTheAcquisitionAndKeepsItsCrop() throws Exception {
		File root = new File(folder.getRoot(), "cropped-growing.ome.zarr");
		writeSmall(root, 2);
		OmeZarrDataset small = OmeZarrDataset.read(root);

		OmeZarrView.Bounds box = new OmeZarrView.Bounds(2, 1, 4, 3, 1, 3);
		OmeZarrView.Options options = stored(box);
		ImagePlus volume = OmeZarrView.openVirtualVolume(small, options, -1);
		ImagePlus movie = OmeZarrView.openVirtualProjectionMovie(small, "maxZ", options);
		try {
			assertEquals("cropped width", 4, volume.getWidth());
			assertEquals("cropped height", 3, volume.getHeight());
			assertEquals("cropped depth", 2, volume.getNSlices());
			assertEquals(2, volume.getNFrames());
			/* A projection has already collapsed Z, so the box does not describe a region of
			 * it and the renderer drops it rather than cropping the wrong axes. */
			assertEquals("projection ignores the box", SMALL_WIDTH, movie.getWidth());

			CompositeImage composite = (CompositeImage) volume;
			composite.getChannelLut(1).min = 11;
			composite.getChannelLut(1).max = 99;
			ImageStack sameStack = volume.getStack();
			short[] firstPlaneBefore = (short[]) volume.getStack().getProcessor(1).getPixels();

			writeSmall(root, 5);
			OmeZarrDataset grown = OmeZarrDataset.read(root);
			assertEquals(5, grown.getTimepointCount());

			assertEquals(5, OmeZarrView.growVirtualView(volume, grown));
			assertEquals(5, OmeZarrView.growVirtualView(movie, grown));

			assertSame("the stack must be grown, not replaced", sameStack, volume.getStack());
			assertEquals("still cropped after growing", 4, volume.getWidth());
			assertEquals("still cropped after growing", 3, volume.getHeight());
			assertEquals(2, volume.getNSlices());
			assertEquals(11.0, composite.getChannelLut(1).min, 0);
			assertEquals(99.0, composite.getChannelLut(1).max, 0);

			/* Already committed pixels are untouched, and the new time points read back at the
			 * cropped size rather than at the volume's. */
			assertArrayEquals(firstPlaneBefore,
					(short[]) volume.getStack().getProcessor(1).getPixels());
			ShortProcessor last = (ShortProcessor) volume.getStack().getProcessor(volume.getStackSize());
			assertNotNull(last);
			assertEquals(4, last.getWidth());
			assertEquals(3, last.getHeight());

			/* And they are the right pixels: the same box read from a descriptor that has
			 * always known about all five time points. */
			ImagePlus reference = OmeZarrView.openVirtualVolume(grown, stored(box), -1);
			try {
				assertArrayEquals("last plane of the grown view",
						(short[]) reference.getStack().getProcessor(reference.getStackSize()).getPixels(),
						(short[]) last.getPixels());
			} finally {
				close(reference);
			}
		} finally {
			close(volume);
			close(movie);
		}
	}

	@Test
	public void aRegionChangedAfterOpeningDoesNotReachIntoTheOpenView() throws Exception {
		File root = new File(folder.getRoot(), "isolated.ome.zarr");
		writeSmall(root, 2);
		OmeZarrDataset dataset = OmeZarrDataset.read(root);

		/* The viewer hands the same Options object to one open after another and edits the
		 * region between them. A view that kept the caller's object rather than a copy would
		 * start showing a different part of the volume on its next repaint. */
		OmeZarrView.Options shared = stored(new OmeZarrView.Bounds(1, 1, 3, 2, 0, 2));
		ImagePlus opened = OmeZarrView.openVirtualVolume(dataset, shared, -1);
		try {
			short[] before = (short[]) opened.getStack().getProcessor(1).getPixels();

			shared.bounds = new OmeZarrView.Bounds(4, 3, 2, 2, 2, 4);
			shared.operation = OmeZarrView.Operation.SIDE_BY_SIDE;

			assertEquals("width is what it was opened with", 3, opened.getWidth());
			assertEquals("height is what it was opened with", 2, opened.getHeight());
			assertArrayEquals("pixels are what they were opened with",
					before, (short[]) opened.getStack().getProcessor(1).getPixels());
		} finally {
			close(opened);
		}
	}

	// ---- the region is read, not cropped out of whole planes -------------------------

	@Test
	public void readingABoxTouchesOnlyTheChunksItCovers() throws Exception {
		OmeZarrDataset dataset = createWideDataset();
		OmeZarrPlaneReader reader = new OmeZarrPlaneReader(dataset, "s0");
		try {
			reader.readPlane(0, 0, 0);
			assertEquals("a whole plane is every chunk of it",
					CHUNK_COLUMNS * CHUNK_ROWS, reader.blocksRead());

			long before = reader.blocksRead();
			// Wholly inside the middle chunk column, first chunk row.
			reader.readPlane(new OmeZarrPlaneReader.Rect(600, 100, 64, 64), 1, 0, 0);
			assertEquals("one chunk for a box inside one chunk", 1, reader.blocksRead() - before);

			before = reader.blocksRead();
			// Straddles the first chunk boundary in x only.
			reader.readPlane(new OmeZarrPlaneReader.Rect(500, 100, 40, 64), 1, 0, 0);
			assertEquals("two chunks for a box across one boundary", 2, reader.blocksRead() - before);

			before = reader.blocksRead();
			reader.readPlane(new OmeZarrPlaneReader.Rect(600, 100, 64, 64), 1, 0, 0);
			assertEquals("a repeat is served from the cache", 0, reader.blocksRead() - before);
		} finally {
			reader.close();
		}
	}

	@Test
	public void aBoxReadsTheSamePixelsAsTheWholePlaneWouldHaveShown() throws Exception {
		OmeZarrDataset dataset = createWideDataset();
		OmeZarrPlaneReader reader = new OmeZarrPlaneReader(dataset, "s0");
		try {
			ShortProcessor whole = (ShortProcessor) reader.readPlane(1, 1, 0);
			// A box crossing both a column and a row boundary, so every copy case is used.
			OmeZarrPlaneReader.Rect box = new OmeZarrPlaneReader.Rect(500, 480, 60, 70);
			ShortProcessor read = (ShortProcessor) reader.readPlane(box, 1, 1, 0);

			assertEquals(60, read.getWidth());
			assertEquals(70, read.getHeight());
			whole.setRoi(box.x, box.y, box.width, box.height);
			assertArrayEquals((short[]) whole.crop().getPixels(), (short[]) read.getPixels());
		} finally {
			reader.close();
		}
	}

	@Test
	public void aCachedBoxIsNeverServedAsThoughItWereTheWholePlane() throws Exception {
		OmeZarrDataset dataset = createWideDataset();
		OmeZarrPlaneReader reader = new OmeZarrPlaneReader(dataset, "s0");
		try {
			ShortProcessor box = (ShortProcessor) reader.readPlane(
					new OmeZarrPlaneReader.Rect(0, 0, 16, 16), 0, 0, 0);
			ShortProcessor whole = (ShortProcessor) reader.readPlane(0, 0, 0);

			assertEquals(16, box.getWidth());
			assertEquals("the window is part of the cache key", WIDE, whole.getWidth());
			assertEquals(TALL, whole.getHeight());
		} finally {
			reader.close();
		}
	}

	@Test
	public void materialisingARegionCostsTheBoxAndNotTheVolume() throws Exception {
		OmeZarrDataset dataset = createWideDataset();

		OmeZarrView.Options whole = stored(null);
		long everything = OmeZarrView.blocksReadForRegion(dataset, whole, 0);

		OmeZarrView.Options cropped = stored(new OmeZarrView.Bounds(600, 100, 64, 64, 0, DEPTH));
		long box = OmeZarrView.blocksReadForRegion(dataset, cropped, 0);

		/* Two channels, DEPTH planes each: every chunk of every plane, against one chunk of
		 * each - the whole reason for pushing the crop down into the reader. */
		assertEquals(2 * DEPTH * CHUNK_COLUMNS * CHUNK_ROWS, everything);
		assertEquals(2 * DEPTH, box);
		assertTrue("a box must cost less than the volume", box < everything);
	}

	@Test
	public void aCroppedViewMatchesTheWholeViewForEveryRuntimeOperation() throws Exception {
		OmeZarrDataset dataset = createWideDataset();
		OmeZarrView.Bounds box = new OmeZarrView.Bounds(500, 480, 60, 70, 0, DEPTH);

		for (OmeZarrView.Operation operation : OmeZarrView.Operation.values()) {
			OmeZarrView.Options full = stored(null);
			full.operation = operation;
			OmeZarrView.Options part = stored(box.copy());
			part.operation = operation;

			ImagePlus whole = OmeZarrView.openVirtualVolume(dataset, full, 0);
			ImagePlus cropped = OmeZarrView.openVirtualVolume(dataset, part, 0);
			try {
				assertEquals(operation + " keeps its channels", whole.getNChannels(), cropped.getNChannels());
				assertEquals(operation + " width", 60, cropped.getWidth());
				for (int slice = 1; slice <= cropped.getStackSize(); slice++) {
					ShortProcessor reference = (ShortProcessor) whole.getStack().getProcessor(slice);
					reference.setRoi(box.x, box.y, box.width, box.height);
					assertArrayEquals(operation + " slice " + slice,
							(short[]) reference.crop().getPixels(),
							(short[]) cropped.getStack().getProcessor(slice).getPixels());
				}
			} finally {
				close(whole);
				close(cropped);
			}
		}
	}

	// ---- the aligned region has to be the aligned pixels ------------------------------

	/**
	 * The test the identity matrix cannot do.
	 *
	 * <p>An aligned region is not read as a rectangle of the source: a rotation mixes columns,
	 * so the source rectangle is a padded bounding box and the warp is re-based onto it. Every
	 * part of that can be wrong by a pixel or a fraction of one while still looking plausible,
	 * and with an identity alignment none of it is exercised at all. The matrix here is the one
	 * measured on production data - a quarter of a degree, with a real translation.
	 */
	@Test
	public void anAlignedRegionIsTheAlignedPixelsUnderARealRotation() throws Exception {
		OmeZarrDataset dataset = createRotatedDataset();
		OmeZarrView.Operation[] aligned = {
			OmeZarrView.Operation.FLIP_ALIGN_RIGHT, OmeZarrView.Operation.FLIP_ALIGN_LEFT
		};
		OmeZarrView.Bounds[] boxes = {
			new OmeZarrView.Bounds(500, 480, 60, 70, 0, DEPTH),	// crosses two chunk boundaries
			new OmeZarrView.Bounds(0, 0, 40, 40, 0, DEPTH),		// against the origin corner
			new OmeZarrView.Bounds(WIDE - 40, TALL - 40, 40, 40, 0, DEPTH)	// against the far corner
		};

		for (OmeZarrView.Operation operation : aligned) {
			for (boolean interpolate : new boolean[] { true, false }) {
				for (OmeZarrView.Bounds box : boxes) {
					OmeZarrView.Options full = stored(null);
					full.operation = operation;
					full.interpolate = interpolate;
					OmeZarrView.Options part = stored(box.copy());
					part.operation = operation;
					part.interpolate = interpolate;
					String what = operation + ", interpolate=" + interpolate + ", " + box;

					ImagePlus whole = OmeZarrView.openVirtualVolume(dataset, full, 0);
					ImagePlus cropped = OmeZarrView.openVirtualVolume(dataset, part, 0);
					try {
						assertEquals(what + " width", box.width, cropped.getWidth());
						assertEquals(what + " height", box.height, cropped.getHeight());
						for (int slice = 1; slice <= cropped.getStackSize(); slice++) {
							ShortProcessor reference =
									(ShortProcessor) whole.getStack().getProcessor(slice);
							reference.setRoi(box.x, box.y, box.width, box.height);
							assertArrayEquals(what + " slice " + slice,
									(short[]) reference.crop().getPixels(),
									(short[]) cropped.getStack().getProcessor(slice).getPixels());
						}
					} finally {
						close(whole);
						close(cropped);
					}
				}
			}
		}
	}

	@Test
	public void anAlignedRegionAlsoCostsTheBoxAndNotTheVolume() throws Exception {
		OmeZarrDataset dataset = createRotatedDataset();
		OmeZarrView.Options options = stored(new OmeZarrView.Bounds(600, 100, 64, 64, 0, DEPTH));
		options.operation = OmeZarrView.Operation.FLIP_ALIGN_RIGHT;
		long box = OmeZarrView.blocksReadForRegion(dataset, options, 0);

		OmeZarrView.Options whole = stored(null);
		whole.operation = OmeZarrView.Operation.FLIP_ALIGN_RIGHT;
		long everything = OmeZarrView.blocksReadForRegion(dataset, whole, 0);

		/* Before the inverse-mapped read, the aligned half rendered whole planes and cropped
		 * afterwards, so it cost every chunk of every plane however small the box was. */
		assertEquals(2 * DEPTH * CHUNK_COLUMNS * CHUNK_ROWS, everything);
		assertEquals("an aligned box is one chunk per plane, like an unaligned one", 2 * DEPTH, box);
	}

	// ---- helpers ---------------------------------------------------------------------

	private static OmeZarrView.Options stored(OmeZarrView.Bounds bounds) {
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

	/** Append time points to a small dataset, leaving what is already committed alone. */
	private void writeSmall(File root, int timepoints) throws Exception {
		OmeZarrWriter writer = new OmeZarrWriter(root);
		try {
			writer.createAppendableVolume(SMALL_WIDTH, SMALL_HEIGHT, SMALL_DEPTH, 2);
			writer.createAppendableProjection("maxZ", SMALL_WIDTH, SMALL_HEIGHT, 2);
			writer.writeMetadata(OmeZarrDatasetTest.sampleProvenance("cropped-live", true),
					Collections.singletonList("maxZ"));
			for (int t = 0; t < timepoints; t++) {
				if (writer.isTimePointCommitted("Time" + (t + 1))) continue;
				writer.writeVolumeChannel(smallVolume(0, t), 0, t);
				writer.writeVolumeChannel(smallVolume(1, t), 1, t);
				writer.writeProjection(smallPlane(300 + t * 100), "maxZ", 0, t);
				writer.writeProjection(smallPlane(700 + t * 100), "maxZ", 1, t);
				writer.commitTimePoint(t, "Time" + (t + 1),
						Collections.singletonList("raw.tif"), t * 2.5);
			}
			writer.markComplete();
		} finally {
			writer.close();
		}
	}

	/** Every voxel distinct, so a wrong offset in x, y, z, c or t cannot pass unnoticed. */
	private static ImagePlus smallVolume(int channel, int timepoint) {
		ImageStack stack = new ImageStack(SMALL_WIDTH, SMALL_HEIGHT);
		for (int z = 0; z < SMALL_DEPTH; z++) {
			short[] pixels = new short[SMALL_WIDTH * SMALL_HEIGHT];
			for (int y = 0; y < SMALL_HEIGHT; y++)
				for (int x = 0; x < SMALL_WIDTH; x++)
					pixels[y * SMALL_WIDTH + x] = (short) (
							x + 10 * y + 100 * z + 1000 * channel + 3000 * timepoint + 1);
			stack.addSlice(new ShortProcessor(SMALL_WIDTH, SMALL_HEIGHT, pixels, null));
		}
		return new ImagePlus(channel == 0 ? "left" : "right", stack);
	}

	private static ImagePlus smallPlane(int first) {
		short[] pixels = new short[SMALL_WIDTH * SMALL_HEIGHT];
		for (int i = 0; i < pixels.length; i++) pixels[i] = (short) (first + i);
		return new ImagePlus("projection",
				new ShortProcessor(SMALL_WIDTH, SMALL_HEIGHT, pixels, null));
	}

	/**			One time point, wider and taller than a single 512 x 512 chunk
	 * <p>		The size is the whole point: with a plane inside one chunk there is nothing for
	 * 			a chunk-restricted read to skip, and a test over such a dataset would pass
	 * 			whether or not the crop was ever pushed down.
	 */
	private OmeZarrDataset createWideDataset() throws Exception {
		File root = new File(folder.getRoot(), "wide-chunks.ome.zarr");
		OmeZarrWriter writer = new OmeZarrWriter(root);
		try {
			writer.createAppendableVolume(WIDE, TALL, DEPTH, 2);
			writer.createAppendableProjection("maxZ", WIDE, TALL, 2);
			writer.writeMetadata(OmeZarrDatasetTest.sampleProvenance("wide-chunks", true),
					Collections.singletonList("maxZ"));
			writer.writeVolumeChannel(wideVolume(0), 0, 0);
			writer.writeVolumeChannel(wideVolume(1), 1, 0);
			writer.writeProjection(widePlane(0), "maxZ", 0, 0);
			writer.writeProjection(widePlane(1), "maxZ", 1, 0);
			writer.commitTimePoint(0, "Time1", Collections.singletonList("raw.tif"), 0);
			writer.markComplete();
		} finally {
			writer.close();
		}
		return OmeZarrDataset.read(root);
	}

	/** The wide dataset again, but recording the rotation measured on production data. */
	private OmeZarrDataset createRotatedDataset() throws Exception {
		File root = new File(folder.getRoot(), "rotated-chunks.ome.zarr");
		OpmProvenance provenance = OmeZarrDatasetTest.sampleProvenance("rotated-chunks", true);
		provenance.alignMatrix = new double[][] {
			{ 0.9999905126191354, -0.004355992621549795, 11.659000466098544 },
			{ 0.004355992621549795, 0.9999905126191354, -7.493068474056827 }
		};
		OmeZarrWriter writer = new OmeZarrWriter(root);
		try {
			writer.createAppendableVolume(WIDE, TALL, DEPTH, 2);
			writer.createAppendableProjection("maxZ", WIDE, TALL, 2);
			writer.writeMetadata(provenance, Collections.singletonList("maxZ"));
			writer.writeVolumeChannel(wideVolume(0), 0, 0);
			writer.writeVolumeChannel(wideVolume(1), 1, 0);
			writer.writeProjection(widePlane(0), "maxZ", 0, 0);
			writer.writeProjection(widePlane(1), "maxZ", 1, 0);
			writer.commitTimePoint(0, "Time1", Collections.singletonList("raw.tif"), 0);
			writer.markComplete();
		} finally {
			writer.close();
		}
		return OmeZarrDataset.read(root);
	}

	private static ImagePlus wideVolume(int channel) {
		ImageStack stack = new ImageStack(WIDE, TALL);
		for (int z = 0; z < DEPTH; z++) stack.addSlice(widePattern(z, channel));
		return new ImagePlus(channel == 0 ? "left" : "right", stack);
	}

	private static ImagePlus widePlane(int channel) {
		return new ImagePlus("projection", widePattern(0, channel + 5));
	}

	/** Coprime multipliers, so no two positions in the chunk grid share a value by accident. */
	private static ShortProcessor widePattern(int z, int channel) {
		short[] pixels = new short[WIDE * TALL];
		for (int y = 0; y < TALL; y++)
			for (int x = 0; x < WIDE; x++)
				pixels[y * WIDE + x] = (short) (x * 3 + y * 7 + z * 1009 + channel * 4409 + 1);
		return new ShortProcessor(WIDE, TALL, pixels, null);
	}
}
