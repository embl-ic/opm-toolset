package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Regression tests for the v2.1.6 audit findings repaired in this half of the work.
 *
 * <p>Each of these encodes a defect that shipped because nothing exercised the path:
 *
 * <ul>
 * <li>Deskew Batch's <em>default</em> configuration discovered zero files, because an empty
 *     keyword array cannot match anything in a for-each;</li>
 * <li>the CPU projection fallback flattened channels into the depth axis, so a multi-channel
 *     max-X came out with the wrong size and a maximum taken across channels - and only when
 *     the GPU was unavailable, which is exactly when nobody is watching;</li>
 * <li>every {@code Parameter} instance method wrote through a static, so two open commands
 *     shared one set of settings;</li>
 * <li>the disk warning fired on a threshold that did not match the requirement.</li>
 * </ul>
 */
public class AuditRepairTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();


	// ---- Batch discovery ------------------------------------------------------------

	/** The default "file name contains" field is blank, and blank has to mean "everything". */
	@Test
	public void aBlankKeywordDiscoversEveryTiff() throws IOException {
		File acquisition = folder.newFolder("acq");
		touch(new File(acquisition, "vol_Time000001_Channel0001.tif"));
		touch(new File(acquisition, "vol_Time000001_Channel0002.tif"));
		touch(new File(acquisition, "vol_Time000002_Channel0001.tif"));
		touch(new File(acquisition, "notes.txt"));

		String[] blank = Batch.getFileList(acquisition, Parameter.extensions, new String[0], false);
		assertEquals("a blank filter must accept every TIFF", 3, blank.length);

		String[] nulled = Batch.getFileList(acquisition, Parameter.extensions, null, false);
		assertEquals("a null filter behaves like a blank one", 3, nulled.length);

		String[] named = Batch.getFileList(acquisition, Parameter.extensions,
				new String[] { "Channel0001" }, false);
		assertEquals("a named filter still filters", 2, named.length);
	}

	/** The two discovery paths must agree, or a Zarr run and a TIFF run see different files. */
	@Test
	public void bothDiscoveryPathsAgreeOnABlankFilter() throws IOException {
		File acquisition = folder.newFolder("agree");
		touch(new File(acquisition, "a_Time000001.tif"));
		touch(new File(acquisition, "b_Time000002.tif"));

		assertEquals(BatchProcessingUtils.listTiffs(acquisition, "", false).size(),
				Batch.getFileList(acquisition, Parameter.extensions, new String[0], false).length);
	}

	/**
	 * A name with no digits at all reaches the sort once the blank filter accepts everything.
	 * The old comparator called Long.valueOf on an empty string and took the run down.
	 */
	@Test
	public void aFileWithNoDigitsDoesNotBreakTheOrdering() throws IOException {
		File acquisition = folder.newFolder("digits");
		touch(new File(acquisition, "reference.tif"));
		touch(new File(acquisition, "vol_Time000002.tif"));
		touch(new File(acquisition, "vol_Time000001.tif"));

		// the assertion that matters is that this returns at all: the old comparator threw
		String[] found = Batch.getFileList(acquisition, Parameter.extensions, new String[0], false);
		assertEquals(3, found.length);

		boolean sawReference = false;
		for (String path : found) if (path.endsWith("reference.tif")) sawReference = true;
		assertTrue("the digit-free name is discovered, not dropped", sawReference);

		/* Ordering of the two numbered volumes is what the comparator is for; the digit-free
		 * name has no time point, so where it lands is unspecified and deliberately unasserted
		 * (the temporary folder's own digits are part of every path here). */
		int first = indexEndingWith(found, "vol_Time000001.tif");
		int second = indexEndingWith(found, "vol_Time000002.tif");
		assertTrue("time points stay in acquisition order", first < second);

		assertEquals("no digits at all is zero, not an exception", 0, Batch.trailingDigits("reference.tif"));
		assertEquals(0, Batch.trailingDigits(null));
		assertEquals(0, Batch.trailingDigits(""));
		assertEquals(12, Batch.trailingDigits("a1b2.tif"));
	}


	// ---- CPU projection fallback ----------------------------------------------------

	/**
	 * The defect: reorderAxes read getStackSize() as the Z extent, so a four-channel
	 * 32x20x9 volume transposed as if it were 36 deep and produced a single-channel
	 * 36x20 max-X instead of a four-channel 9x20 one.
	 */
	@Test
	public void cpuProjectionKeepsChannelsOutOfTheSpatialAxes() {
		ImagePlus volume = hyperstack(32, 20, 9, 4);

		ImagePlus maxX = CPU.projection_x(volume, "max");
		assertNotNull(maxX);
		assertEquals("max-X width is Z, not Z times channels", 9, maxX.getWidth());
		assertEquals(20, maxX.getHeight());
		assertEquals(4, maxX.getNChannels());
		assertEquals(4, maxX.getStackSize());

		ImagePlus maxY = CPU.projection_y(volume, "max");
		assertNotNull(maxY);
		assertEquals(32, maxY.getWidth());
		assertEquals("max-Y height is Z, not Z times channels", 9, maxY.getHeight());
		assertEquals(4, maxY.getNChannels());

		ImagePlus maxZ = CPU.projection_z(volume, "max");
		assertNotNull(maxZ);
		assertEquals(32, maxZ.getWidth());
		assertEquals(20, maxZ.getHeight());
		assertEquals(4, maxZ.getNChannels());

		close(maxX);
		close(maxY);
		close(maxZ);
		close(volume);
	}

	/** Each channel must keep its own values; the old path maximised across channels too. */
	@Test
	public void cpuProjectionMaximisesWithinAChannelOnly() {
		// channel c is filled with the constant 1000*c + z, so channel 3 is the brightest
		ImagePlus volume = hyperstack(8, 6, 5, 4);
		ImagePlus maxX = CPU.projection_x(volume, "max");
		try {
			/* max-X collapses X, so the output's own X axis is Z. Plane c, column z therefore
			 * holds that channel's constant for that slice - which pins two things at once:
			 * the channels stayed separate, and Z did not get folded into the width. */
			for (int c = 1; c <= 4; c++) {
				ImageProcessor plane = maxX.getStack().getProcessor(c);
				assertEquals("max-X width is the Z extent", 5, plane.getWidth());
				for (int z = 0; z < 5; z++)
					assertEquals("channel " + c + " slice " + z + " must not see its neighbours",
							1000 * (c - 1) + z, plane.get(z, 0));
			}
		} finally {
			close(maxX);
			close(volume);
		}
	}

	/** A plain single-channel stack must come through exactly as it did before. */
	@Test
	public void cpuProjectionStillHandlesAPlainStack() {
		ImageStack stack = new ImageStack(12, 7);
		for (int z = 0; z < 5; z++) {
			short[] pixels = new short[12 * 7];
			java.util.Arrays.fill(pixels, (short) (z + 1));
			stack.addSlice(new ShortProcessor(12, 7, pixels, null));
		}
		ImagePlus volume = new ImagePlus("plain", stack);

		ImagePlus maxX = CPU.projection_x(volume, "max");
		try {
			assertEquals(5, maxX.getWidth());
			assertEquals(7, maxX.getHeight());
			assertEquals(1, maxX.getNChannels());
			assertEquals(1, maxX.getStackSize());
		} finally {
			close(maxX);
			close(volume);
		}
	}


	// ---- Parameter aliasing ---------------------------------------------------------

	/**
	 * Two commands open at once must not share settings. The seven parse/update methods used
	 * to operate on the static instance, so whichever Parameter was constructed last received
	 * every geometry, projection and alignment update the others made.
	 */
	@Test
	public void twoParametersDoNotShareTheirProjectionSettings() {
		Parameter live = new Parameter("junit-alias-live");
		Parameter batch = new Parameter("junit-alias-batch");	// constructed last, owns the static

		live.projX = true; live.projY = false; live.projZ = false;
		live.maxProj = true; live.avgProj = false;
		live.minProj = live.sumProj = live.medProj = live.stdProj = false;

		batch.projX = false; batch.projY = true; batch.projZ = true;
		batch.maxProj = false; batch.avgProj = true;
		batch.minProj = batch.sumProj = batch.medProj = batch.stdProj = false;

		live.parseProjectionParameter();
		batch.parseProjectionParameter();

		assertEquals("live must keep its own axes", java.util.Arrays.asList("X"), live.projAxes);
		assertEquals(java.util.Arrays.asList("max"), live.projTypes);
		assertEquals(java.util.Arrays.asList("Y", "Z"), batch.projAxes);
		assertEquals(java.util.Arrays.asList("avg"), batch.projTypes);
	}

	@Test
	public void updateDeskewMatrixWritesIntoItsOwnParameter() {
		Parameter first = new Parameter("junit-alias-first");
		first.deskewMatrix = Transform.deskew(265.0, 116.0, 25.0, 0);
		first.impInput = blank(64, 500);

		Parameter second = new Parameter("junit-alias-second");	// now owns the static
		second.deskewMatrix = Transform.deskew(265.0, 116.0, 25.0, 0);
		second.impInput = blank(64, 200);

		first.updateDeskewMatrix();
		second.updateDeskewMatrix();

		double sin = Math.abs(first.deskewMatrix[2][1]);
		assertEquals("500 * sin(theta) belongs to the first parameter",
				500 * sin, first.deskewMatrix[2][3], 1e-9);
		assertEquals("200 * sin(theta) belongs to the second",
				200 * sin, second.deskewMatrix[2][3], 1e-9);

		close(first.impInput);
		close(second.impInput);
	}


	// ---- disk thresholds ------------------------------------------------------------

	/** The requirement: warn when the free space is not enough for four more hours. */
	@Test
	public void theWarningThresholdIsFourHours() {
		assertEquals(4.0, DiskSpace.WARNING_HOURS, 0);
		assertEquals(1.0, DiskSpace.CRITICAL_HOURS, 0);

		long gb = 1024L * 1024 * 1024;
		// 300 GB at 1 GB/min is five hours: still fine
		assertEquals(DiskSpace.Level.OK, report(300 * gb, 1000 * gb, gb / 60.0).level());
		// 200 GB at the same rate is three hours and twenty minutes
		assertEquals(DiskSpace.Level.WARNING, report(200 * gb, 1000 * gb, gb / 60.0).level());
		// 50 GB is under an hour
		assertEquals(DiskSpace.Level.CRITICAL, report(50 * gb, 1000 * gb, gb / 60.0).level());
	}

	/** A warning is about a trend, so an idle disk never raises one however little is left. */
	@Test
	public void anIdleDiskNeverWarns() {
		long gb = 1024L * 1024 * 1024;
		DiskSpace.Report idle = report(20 * gb, 1000 * gb, 0);
		assertFalse(idle.hasRate());
		assertEquals(DiskSpace.Level.OK, idle.level());
		assertFalse(idle.format(), idle.format().contains("low"));
	}


	// ---- helpers --------------------------------------------------------------------

	private static DiskSpace.Report report(long free, long total, double bytesPerSecond) {
		return new DiskSpace.Report("T:\\", free, total, bytesPerSecond);
	}

	/** XYCZT with the channel fastest, the order ImageJ uses and the projections assume. */
	private static ImagePlus hyperstack(int width, int height, int slices, int channels) {
		ImageStack stack = new ImageStack(width, height);
		for (int z = 0; z < slices; z++) {
			for (int c = 0; c < channels; c++) {
				short[] pixels = new short[width * height];
				java.util.Arrays.fill(pixels, (short) (1000 * c + z));
				stack.addSlice(new ShortProcessor(width, height, pixels, null));
			}
		}
		ImagePlus image = new ImagePlus("hyper", stack);
		image.setDimensions(channels, slices, 1);
		image.setOpenAsHyperStack(true);
		return image;
	}

	private static ImagePlus blank(int width, int height) {
		return new ImagePlus("blank", new ShortProcessor(width, height));
	}

	private static void close(ImagePlus image) {
		if (image == null) return;
		image.changes = false;
		image.close();
	}

	private static int indexEndingWith(String[] paths, String suffix) {
		for (int i = 0; i < paths.length; i++) if (paths[i].endsWith(suffix)) return i;
		return -1;
	}

	private static void touch(File file) throws IOException {
		File parent = file.getParentFile();
		if (parent != null) parent.mkdirs();
		Files.write(file.toPath(), new byte[16]);
	}
}
