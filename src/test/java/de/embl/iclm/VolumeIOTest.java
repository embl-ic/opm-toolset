package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Assume;
import org.junit.Test;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;

/**
 * Tests for the single raw-volume loading and axis-normalising entry point.
 *
 * <p>The acquisition writes BigTIFF, which Bio-Formats reads back with the planes of one
 * volume on the time axis. The swap that puts them back on Z used to be repeated at seven
 * call sites, with two different guards, and was missing at two more. These tests pin the
 * rule down in one place.
 *
 * <p>Point {@code -Dopm.test.data} at a folder of raw acquisition volumes to also exercise
 * the real reader against real files; without it those tests are skipped.
 */
public class VolumeIOTest {

	/**
	 * The acquisition writes BigTIFF and nothing this plugin writes is one, so the header alone
	 * decides that a file goes to the fast reader first. Both byte orders, and never a classic
	 * TIFF - that is every result, whose channels and calibration ImageJ has to keep.
	 */
	@Test
	public void aBigTiffIsRecognisedFromItsHeaderAlone() throws Exception {
		File folder = java.nio.file.Files.createTempDirectory("opm-bigtiff").toFile();
		try {
			File little = new File(folder, "little.tif");
			java.nio.file.Files.write(little.toPath(), new byte[] { 'I', 'I', 43, 0, 8, 0, 0, 0, 0, 0 });
			File big = new File(folder, "big.tif");
			java.nio.file.Files.write(big.toPath(), new byte[] { 'M', 'M', 0, 43, 0, 8, 0, 0, 0, 0 });
			File classic = new File(folder, "classic.tif");
			assertTrue(VolumeIO.saveTiff(stack(8, 6, 3), classic));

			assertTrue(VolumeIO.isBigTiff(little));
			assertTrue(VolumeIO.isBigTiff(big));
			assertFalse("a result written by this plugin is classic TIFF", VolumeIO.isBigTiff(classic));
			assertFalse("a missing file is not a BigTIFF", VolumeIO.isBigTiff(new File(folder, "none.tif")));
		} finally {
			for (File file : folder.listFiles()) file.delete();
			folder.delete();
		}
	}

	/** The deskew matrix needs only the height, which the IFD holds; no pixels are decoded. */
	@Test
	public void theHeightComesFromTheMetadata() throws Exception {
		File file = File.createTempFile("opm-height", ".tif");
		try {
			assertTrue(VolumeIO.saveTiff(stack(8, 6, 3), file));
			assertEquals(6, VolumeIO.height(file));
		} finally {
			file.delete();
		}
	}

	/** A volume whose planes landed on T is a volume, and belongs back on Z. */
	@Test
	public void normalizeMovesPlanesFromTimeBackToDepth() {
		ImagePlus imp = stack(8, 6, 21);
		imp.setDimensions(1, 1, 21);					// what Bio-Formats hands back for raw BigTIFF

		assertTrue("should be recognised as needing the swap", VolumeIO.needsAxisSwap(imp));
		VolumeIO.normalize(imp);

		assertEquals("planes belong on Z", 21, imp.getNSlices());
		assertEquals("nothing left on T", 1, imp.getNFrames());
		assertEquals("channels untouched", 1, imp.getNChannels());
		assertEquals("stack size unchanged", 21, imp.getStackSize());
	}

	/** A volume that already has depth is left alone, however many time points it has. */
	@Test
	public void normalizeLeavesRealTimeLapsesAlone() {
		ImagePlus timelapse = stack(8, 6, 12);
		timelapse.setDimensions(1, 4, 3);				// real depth 4, three time points

		assertFalse(VolumeIO.needsAxisSwap(timelapse));
		VolumeIO.normalize(timelapse);

		assertEquals("depth kept", 4, timelapse.getNSlices());
		assertEquals("time points kept", 3, timelapse.getNFrames());
	}

	/** A single plane has nothing to swap, and must not be turned into anything else. */
	@Test
	public void normalizeLeavesSinglePlanesAlone() {
		ImagePlus single = stack(8, 6, 1);

		assertFalse(VolumeIO.needsAxisSwap(single));
		VolumeIO.normalize(single);

		assertEquals(1, single.getNSlices());
		assertEquals(1, single.getNFrames());
	}

	/** Applying the rule twice must not undo it: callers apply it defensively. */
	@Test
	public void normalizeIsIdempotent() {
		ImagePlus imp = stack(8, 6, 21);
		imp.setDimensions(1, 1, 21);

		VolumeIO.normalize(imp);
		VolumeIO.normalize(imp);

		assertEquals(21, imp.getNSlices());
		assertEquals(1, imp.getNFrames());
	}

	/** A multi-channel volume keeps its channels when its planes move back to Z. */
	@Test
	public void normalizeKeepsChannelsWhenSwapping() {
		ImagePlus imp = stack(8, 6, 20);
		imp.setDimensions(2, 1, 10);					// two channels, planes on T

		VolumeIO.normalize(imp);

		assertEquals("channels kept", 2, imp.getNChannels());
		assertEquals("planes on Z", 10, imp.getNSlices());
		assertEquals(1, imp.getNFrames());
	}

	/** Null and unreadable paths must produce null rather than an exception. */
	@Test
	public void openHandlesMissingInputWithoutThrowing() {
		assertNull(VolumeIO.normalize(null));
		assertFalse(VolumeIO.needsAxisSwap(null));
		assertNull(VolumeIO.open(null));
		assertNull(VolumeIO.open("   "));
		assertNull(VolumeIO.open(new File("no-such-directory", "no-such-file.tif").getPath()));
		assertNull(VolumeIO.openFast(new File("no-such-directory", "no-such-file.tif").getPath()));
	}

	/**
	 * The direct reader must read a real acquisition file with its planes already on Z.
	 *
	 * <p>This is the property that makes it a usable fallback where ImageJ has no
	 * Bio-Formats: it reads plane count from the TIFF itself and never puts them on T.
	 */
	@Test
	public void fastReaderReturnsRawVolumesWithPlanesOnDepth() {
		File volume = anyRawVolume();
		Assume.assumeTrue("Set -Dopm.test.data to a folder of raw OPM volumes", volume != null);

		ImagePlus imp = VolumeIO.openFast(volume.getAbsolutePath());

		assertNotNull("fast reader could not open " + volume, imp);
		assertTrue("a volume should have depth", imp.getNSlices() > 1);
		assertEquals("nothing on the time axis", 1, imp.getNFrames());
		assertFalse("already normalised", VolumeIO.needsAxisSwap(imp));
		assertEquals("raw acquisition data is 16-bit", 16, imp.getBitDepth());
	}

	/** However a real file is read, it must come back as an XY-Z stack of the same size. */
	@Test
	public void openAgreesWithTheFastReaderOnRealFiles() {
		File volume = anyRawVolume();
		Assume.assumeTrue("Set -Dopm.test.data to a folder of raw OPM volumes", volume != null);

		ImagePlus viaOpen = VolumeIO.open(volume.getAbsolutePath());
		ImagePlus viaFast = VolumeIO.openFast(volume.getAbsolutePath());

		assertNotNull(viaOpen);
		assertNotNull(viaFast);
		assertEquals("width", viaFast.getWidth(), viaOpen.getWidth());
		assertEquals("height", viaFast.getHeight(), viaOpen.getHeight());
		assertEquals("depth", viaFast.getNSlices(), viaOpen.getNSlices());
		assertFalse("open normalises its result", VolumeIO.needsAxisSwap(viaOpen));
	}

	// ---- helpers -------------------------------------------------------------------

	private static ImagePlus stack(int w, int h, int planes) {
		ImageStack stack = new ImageStack(w, h);
		for (int i = 0; i < planes; i++) {
			ShortProcessor slice = new ShortProcessor(w, h);
			slice.set(0, 0, i);
			stack.addSlice(slice);
		}
		return new ImagePlus("stack", stack);
	}

	/** First .tif/.tiff found under -Dopm.test.data, or null when that is not set. */
	private static File anyRawVolume() {
		String root = System.getProperty("opm.test.data");
		if (root == null || root.trim().isEmpty()) return null;
		return firstVolume(new File(root.trim()), 0);
	}

	private static File firstVolume(File dir, int depth) {
		if (depth > 4 || dir == null || !dir.isDirectory()) return null;
		File[] entries = dir.listFiles();
		if (entries == null) return null;
		for (File entry : entries) {
			if (entry.isFile()) {
				String name = entry.getName().toLowerCase();
				if (name.endsWith(".tif") || name.endsWith(".tiff")) return entry;
			}
		}
		for (File entry : entries) {
			if (entry.isDirectory()) {
				File found = firstVolume(entry, depth + 1);
				if (found != null) return found;
			}
		}
		return null;
	}
}
