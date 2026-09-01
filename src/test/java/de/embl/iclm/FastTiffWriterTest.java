package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

/**
 * Tests for the Deflate TIFF writer and the reader's matching decode path.
 *
 * <p>Deskewing leaves between a third and three quarters of the output bounding box empty,
 * so the deskewed volume is several times larger than the raw one it came from. Compressing
 * it away has to be lossless and has to stay readable by everything, which is what these
 * tests hold in place: every file written here is read back both by {@link FastTiffReader}
 * and by ImageJ's own decoder, and compared voxel by voxel.
 */
public class FastTiffWriterTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	/** Round-trip through our own parallel reader, at several shapes and levels. */
	@Test
	public void roundTripsThroughFastTiffReader() throws IOException {
		int[][] shapes = { { 64, 48, 7 }, { 200, 137, 13 }, { 1, 1, 1 }, { 333, 1, 3 }, { 17, 29, 1 } };
		for (int[] shape : shapes) {
			ImagePlus source = ramp(shape[0], shape[1], shape[2]);
			File file = folder.newFile("rt_" + shape[0] + "_" + shape[1] + "_" + shape[2] + ".tif");
			FastTiffWriter.write(source, file);
			assertSameVolume("FastTiffReader " + describe(shape), source, VolumeIO.openFast(file.getAbsolutePath()));
		}
	}

	/** The file must also be an ordinary TIFF that ImageJ's own decoder reads. */
	@Test
	public void roundTripsThroughImageJ() throws IOException {
		ImagePlus source = ramp(96, 71, 9);
		File file = folder.newFile("ij.tif");
		FastTiffWriter.write(source, file);
		assertSameVolume("IJ.openImage", source, IJ.openImage(file.getAbsolutePath()));
	}

	/** Every level, including 0 which stores, must survive the round trip unchanged. */
	@Test
	public void everyDeflateLevelIsLossless() throws IOException {
		ImagePlus source = ramp(128, 96, 11);
		for (int level = 0; level <= 9; level++) {
			File file = folder.newFile("level" + level + ".tif");
			FastTiffWriter.write(source, file, level);
			assertSameVolume("level " + level, source, VolumeIO.openFast(file.getAbsolutePath()));
		}
	}

	/**
	 * Empty voxels must actually be cheap, since that is the whole point.
	 *
	 * <p>A volume that is mostly zero, as a deskewed volume is, has to end up a small
	 * fraction of its uncompressed size.
	 */
	@Test
	public void mostlyEmptyVolumesCompressHard() throws IOException {
		int w = 256, h = 256, d = 16;
		ImageStack stack = new ImageStack(w, h);
		for (int z = 0; z < d; z++) {
			ShortProcessor slice = new ShortProcessor(w, h);
			for (int y = 0; y < h / 4; y++)					// only the top quarter carries signal
				for (int x = 0; x < w; x++)
					slice.set(x, y, (x * 7 + y * 13 + z * 31) & 0xfff);
			stack.addSlice(slice);
		}
		ImagePlus sparse = new ImagePlus("sparse", stack);
		File file = folder.newFile("sparse.tif");
		FastTiffWriter.write(sparse, file);

		long uncompressed = (long) w * h * d * 2;
		assertTrue("a three-quarters-empty volume should compress well below half its size, got "
				+ file.length() + " of " + uncompressed, file.length() < uncompressed / 2);
		assertSameVolume("sparse", sparse, VolumeIO.openFast(file.getAbsolutePath()));
	}

	/** Calibration and the slice count have to survive, or downstream geometry breaks. */
	@Test
	public void preservesStackMetadata() throws IOException {
		ImagePlus source = ramp(40, 30, 9);
		Calibration calibration = source.getCalibration();
		calibration.setUnit("micron");
		calibration.pixelWidth = 0.116;
		calibration.pixelHeight = 0.116;
		calibration.pixelDepth = 0.112;

		File file = folder.newFile("calibrated.tif");
		FastTiffWriter.write(source, file);
		ImagePlus back = IJ.openImage(file.getAbsolutePath());

		assertNotNull(back);
		assertEquals("slice count", 9, back.getStackSize());
		assertEquals("planes stay on Z", 9, back.getNSlices());
		assertEquals("unit", "micron", back.getCalibration().getUnit());
		assertEquals("z spacing", 0.112, back.getCalibration().pixelDepth, 1e-9);
	}

	/** VolumeIO.saveTiff routes 16-bit stacks through the compressing writer. */
	@Test
	public void volumeIoSavesCompressedAndReadsBack() throws IOException {
		ImagePlus source = ramp(120, 90, 8);
		File compressed = folder.newFile("compressed.tif");
		File plain = folder.newFile("plain.tif");

		assertTrue(VolumeIO.saveTiff(source, compressed));
		VolumeIO.setCompressOutput(false);
		try {
			assertTrue(VolumeIO.saveTiff(source, plain));
		} finally {
			VolumeIO.setCompressOutput(true);
		}

		assertTrue("compression should shrink the file: " + compressed.length() + " vs " + plain.length(),
				compressed.length() < plain.length());
		assertSameVolume("compressed", source, VolumeIO.open(compressed.getAbsolutePath()));
		assertSameVolume("uncompressed", source, VolumeIO.open(plain.getAbsolutePath()));
	}

	/** Anything the writer cannot handle must be refused clearly, not written wrong. */
	@Test
	public void refusesWhatItCannotWrite() throws IOException {
		assertFalse(FastTiffWriter.canWrite(null));
		assertFalse("8-bit", FastTiffWriter.canWrite(new ImagePlus("b", new ByteProcessor(4, 4))));
		assertFalse("32-bit float", FastTiffWriter.canWrite(new ImagePlus("f", new FloatProcessor(4, 4))));
		assertTrue("16-bit", FastTiffWriter.canWrite(ramp(4, 4, 2)));

		try {
			FastTiffWriter.write(new ImagePlus("f", new FloatProcessor(4, 4)), folder.newFile("bad.tif"));
			fail("writing a 32-bit image should throw");
		} catch (IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("16-bit"));
		}
		try {
			FastTiffWriter.write(ramp(4, 4, 2), folder.newFile("badlevel.tif"), 11);
			fail("an out-of-range Deflate level should throw");
		} catch (IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("level"));
		}
	}

	/** VolumeIO.saveTiff must still write something for images the fast writer refuses. */
	@Test
	public void saveTiffFallsBackForUnsupportedImages() throws IOException {
		ImagePlus floatImage = new ImagePlus("f", new FloatProcessor(16, 12));
		File file = folder.newFile("float.tif");

		assertTrue("fallback should still save", VolumeIO.saveTiff(floatImage, file));
		ImagePlus back = IJ.openImage(file.getAbsolutePath());
		assertNotNull(back);
		assertEquals(32, back.getBitDepth());
		assertEquals(16, back.getWidth());
	}

	/**
	 * An explicit thread count must be honoured and must not change the result.
	 *
	 * <p>The overload used to ignore its argument, which quietly removed the knob the
	 * earlier reader benchmark existed to find.
	 */
	@Test
	public void readsIdenticallyAtEveryThreadCount() throws Exception {
		ImagePlus source = ramp(64, 48, 9);
		File file = folder.newFile("threads.tif");
		FastTiffWriter.write(source, file);
		FastTiffReader.Info info = FastTiffReader.parse(file);

		for (int threads : new int[] { 0, 1, 2, 4, 32 }) {
			short[][] volume = FastTiffReader.readPixelsParallel(file, info, threads);
			ImagePlus back = FastTiffReader.wrapShortVolume("t", volume, info.width, info.height);
			assertSameVolume(threads + " thread(s)", source, back);
		}
	}

	/** The reader advertises exactly the compression tags it can decode. */
	@Test
	public void readerReportsSupportedCompression() {
		assertTrue(FastTiffReader.isSupportedCompression(FastTiffReader.COMPRESSION_NONE));
		assertTrue(FastTiffReader.isSupportedCompression(FastTiffReader.COMPRESSION_DEFLATE));
		assertTrue(FastTiffReader.isSupportedCompression(FastTiffReader.COMPRESSION_DEFLATE_OLD));
		assertFalse("LZW is not decoded here", FastTiffReader.isSupportedCompression(5));
		assertFalse("PackBits is not decoded here", FastTiffReader.isSupportedCompression(32773));
	}

	// ---- helpers -------------------------------------------------------------------

	/** A stack whose voxel values identify their own coordinate, so misplacement shows. */
	private static ImagePlus ramp(int w, int h, int d) {
		ImageStack stack = new ImageStack(w, h);
		for (int z = 0; z < d; z++) {
			ShortProcessor slice = new ShortProcessor(w, h);
			for (int y = 0; y < h; y++)
				for (int x = 0; x < w; x++)
					slice.set(x, y, (x * 7 + y * 13 + z * 1013) & 0xffff);
			stack.addSlice(slice);
		}
		ImagePlus imp = new ImagePlus("ramp", stack);
		imp.setDimensions(1, d, 1);
		return imp;
	}

	private static String describe(int[] shape) {
		return shape[0] + "x" + shape[1] + "x" + shape[2];
	}

	private static void assertSameVolume(String label, ImagePlus expected, ImagePlus actual) {
		assertNotNull(label + ": read returned null", actual);
		assertEquals(label + ": width", expected.getWidth(), actual.getWidth());
		assertEquals(label + ": height", expected.getHeight(), actual.getHeight());
		assertEquals(label + ": depth", expected.getStackSize(), actual.getStackSize());
		for (int z = 1; z <= expected.getStackSize(); z++) {
			ImageProcessor a = expected.getStack().getProcessor(z);
			ImageProcessor b = actual.getStack().getProcessor(z);
			for (int i = 0; i < a.getPixelCount(); i++)
				assertEquals(label + ": slice " + z + " index " + i, a.get(i), b.get(i));
		}
	}
}
