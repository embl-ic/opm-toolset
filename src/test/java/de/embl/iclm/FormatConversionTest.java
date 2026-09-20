package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.io.File;
import java.io.IOException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests for the storage-format conversion.
 *
 * <p>The property that matters most is the dull one: a format conversion must change the
 * format and nothing else. Every test here reads the pixels back and compares them to what
 * went in, because a conversion that silently transposed, cropped or rescaled a raw
 * acquisition would look entirely plausible in a file listing.
 *
 * <p>The second property is that neither direction needs the volume in RAM. That is checked
 * structurally - {@link FormatConversion.VolumeSource} must stream a plain 16-bit TIFF rather
 * than fall back to opening it whole - because a unit test cannot meaningfully assert on heap
 * behaviour, and the fallback is silent when it happens.
 */
public class FormatConversionTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final int WIDTH = 7;
	private static final int HEIGHT = 5;
	private static final int DEPTH = 6;


	@Test
	public void deflatedTiffKeepsEveryPixelAndIsSmaller() throws Exception {
		File input = writeSourceVolume("raw.tif");
		File output = new File(folder.getRoot(), "out/raw-deflated.tif");

		long bytes = FormatConversion.convertToDeflatedTiff(input, output, 1);

		assertTrue("the converted file exists", output.isFile());
		assertEquals(output.length(), bytes);
		/* A synthetic ramp compresses; the point of the format is that it does. This is a
		 * sanity check on the writer being used at all, not a claim about real data. */
		assertTrue("deflated output should be smaller than the uncompressed source: "
				+ bytes + " vs " + input.length(), bytes < input.length());

		assertVolumeMatchesSource(output);
	}

	@Test
	public void deflatedTiffReopensWithTheRightShape() throws Exception {
		File input = writeSourceVolume("raw.tif");
		File output = new File(folder.getRoot(), "shape.tif");
		FormatConversion.convertToDeflatedTiff(input, output, 1);

		FastTiffReader.Info info = FastTiffReader.parse(output);
		assertEquals(WIDTH, info.width);
		assertEquals(HEIGHT, info.height);
		assertEquals(DEPTH, info.depth());
		assertEquals("planes must be deflated, not merely copied",
				FastTiffReader.COMPRESSION_DEFLATE, info.compression);
	}

	@Test
	public void omeZarrConversionStoresRawPixelsAndSaysSo() throws Exception {
		File input = writeSourceVolume("raw.tif");
		File root = new File(folder.getRoot(), "raw.ome.zarr");
		FormatConversion.Options options = new FormatConversion.Options();
		options.target = FormatConversion.TARGET_ZARR;
		options.xyPixelSizeUm = 0.116;
		options.zStepSizeUm = 0.4;

		FormatConversion.convertToOmeZarr(input, root, options);

		OmeZarrDataset dataset = OmeZarrDataset.read(root);
		assertNotNull(dataset);
		assertTrue(dataset.hasVolume());
		assertEquals(WIDTH, dataset.getWidth());
		assertEquals(HEIGHT, dataset.getHeight());
		assertEquals(DEPTH, dataset.getDepth());
		assertEquals(1, dataset.getChannelCount());
		assertEquals(1, dataset.getTimepointCount());
		assertTrue("a completed conversion is marked complete", dataset.isComplete());

		OpmProvenance provenance = dataset.getProvenance();
		assertNotNull(provenance);
		assertEquals("a converted store must not be mistaken for a deskewed one",
				OpmProvenance.CONTENT_RAW, provenance.contentKind);
		assertEquals(0.116, provenance.xyPixelSizeUm, 1e-9);
		assertEquals(0.4, provenance.zStepSizeUm, 1e-9);
		assertFalse("no alignment can have been applied to raw pixels", provenance.alignApplied);

		// the pixels themselves, read back the way the viewer would
		OmeZarrView.Options view = new OmeZarrView.Options();
		view.tryGpu = false;
		ImagePlus opened = OmeZarrView.openVirtualVolume(dataset, view, 0);
		try {
			assertEquals(DEPTH, opened.getStackSize());
			for (int z = 0; z < DEPTH; z++)
				assertArrayEquals("plane " + z, expectedPlane(z),
						(short[]) opened.getStack().getProcessor(z + 1).getPixels());
		} finally {
			opened.changes = false;
			opened.close();
		}
	}

	/** A dataset written before contentKind existed still reads as the deskewed data it is. */
	@Test
	public void contentKindDefaultsToDeskewed() {
		assertEquals(OpmProvenance.CONTENT_DESKEWED, new OpmProvenance().contentKind);
	}

	@Test
	public void aPlainTiffIsStreamedRatherThanLoadedWhole() throws Exception {
		File input = writeSourceVolume("raw.tif");
		FormatConversion.VolumeSource source = FormatConversion.VolumeSource.open(input);
		try {
			assertTrue("a 16-bit strip TIFF must take the streaming path", source.isStreaming());
			assertEquals(WIDTH, source.width);
			assertEquals(HEIGHT, source.height);
			assertEquals(DEPTH, source.depth);
			// planes come back independently, and out of order, without reopening the file
			assertArrayEquals(expectedPlane(4), (short[]) source.plane(4).getPixels());
			assertArrayEquals(expectedPlane(0), (short[]) source.plane(0).getPixels());
		} finally {
			source.close();
		}
	}

	@Test
	public void bothTargetsWriteBothOutputsAndSkippingIsHonoured() throws Exception {
		File input = writeSourceVolume("raw.tif");
		File out = folder.newFolder("both");
		FormatConversion.Options options = new FormatConversion.Options();
		options.target = FormatConversion.TARGET_BOTH;

		assertEquals(2, FormatConversion.convertOne(input, out, options, null));
		assertTrue(FormatConversion.tiffOutput(input, out).isFile());
		assertTrue(FormatConversion.zarrOutput(input, out).isDirectory());

		assertEquals("a second pass with skip must write nothing",
				0, FormatConversion.convertOne(input, out, options, null));

		options.overwrite = true;
		assertEquals("overwrite redoes both", 2, FormatConversion.convertOne(input, out, options, null));
	}

	/** The converted TIFF must not be able to land on top of its own source. */
	@Test
	public void outputNamesCannotCollideWithTheInput() throws Exception {
		File input = writeSourceVolume("raw.tif");
		File sameFolder = input.getParentFile();
		assertFalse(FormatConversion.tiffOutput(input, sameFolder).equals(input));
		assertTrue(FormatConversion.tiffOutput(input, sameFolder).getName()
				.endsWith(FormatConversion.TIFF_SUFFIX + ".tif"));
		assertTrue(FormatConversion.zarrOutput(input, sameFolder).getName().endsWith(".ome.zarr"));
	}

	@Test
	public void anUnreadableInputIsReportedRatherThanSkipped() {
		File missing = new File(folder.getRoot(), "not-there.tif");
		try {
			FormatConversion.convertToDeflatedTiff(missing, new File(folder.getRoot(), "x.tif"), 1);
			fail("a missing input must not convert silently");
		} catch (IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("not exist"));
		}
	}

	@Test
	public void deflateLevelIsClampedRatherThanRejected() {
		assertEquals(0, FormatConversion.clampLevel(-3));
		assertEquals(9, FormatConversion.clampLevel(42));
		assertEquals(1, FormatConversion.clampLevel(1));
	}


	// ---- helpers --------------------------------------------------------------------

	/** An uncompressed 16-bit stack, which is the shape the microscope writes. */
	private File writeSourceVolume(String name) throws IOException {
		ImageStack stack = new ImageStack(WIDTH, HEIGHT);
		for (int z = 0; z < DEPTH; z++)
			stack.addSlice(new ShortProcessor(WIDTH, HEIGHT, expectedPlane(z), null));
		ImagePlus imp = new ImagePlus("raw", stack);
		File file = new File(folder.getRoot(), name);
		// level 0 stores rather than compresses, which is the closest stand-in for a raw file
		FastTiffWriter.write(imp, file, 0);
		imp.changes = false;
		imp.close();
		return file;
	}

	private static short[] expectedPlane(int z) {
		short[] pixels = new short[WIDTH * HEIGHT];
		for (int i = 0; i < pixels.length; i++) pixels[i] = (short) (1000 * z + i);
		return pixels;
	}

	private void assertVolumeMatchesSource(File converted) throws Exception {
		FastTiffReader.PlaneReader reader = new FastTiffReader.PlaneReader(converted);
		try {
			assertEquals(DEPTH, reader.depth());
			for (int z = 0; z < DEPTH; z++) {
				ImageProcessor plane = reader.readPlane(z);
				assertEquals(WIDTH, plane.getWidth());
				assertEquals(HEIGHT, plane.getHeight());
				assertArrayEquals("plane " + z, expectedPlane(z), (short[]) plane.getPixels());
			}
		} finally {
			reader.close();
		}
	}
}
