package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;

import org.junit.Test;

import com.google.gson.JsonObject;

/**
 * Tests for the record that travels with a deskewed dataset.
 *
 * <p>A deskewed volume on its own cannot say how it was made: the geometry, the source file
 * and the matrices all live outside the pixels. Everything the viewer needs to reconstruct a
 * view has to survive the round trip through JSON, and the alignment matrix in particular
 * has to come back exactly - it is applied to pixels, so a lost digit is a visible shift.
 */
public class OpmProvenanceTest {

	/** Every field the viewer relies on has to survive being written and read back. */
	@Test
	public void roundTripsThroughJson() {
		OpmProvenance before = sample();
		OpmProvenance after = OpmProvenance.fromJson(before.toJson());

		assertNotNull(after);
		assertEquals(OpmProvenance.SCHEMA, after.schema);
		assertEquals("3_timelapse", after.datasetName);
		assertEquals(before.sourceFolder, after.sourceFolder);
		assertEquals(before.sourceFiles, after.sourceFiles);
		assertEquals(before.timePointLabels, after.timePointLabels);
		assertEquals(before.timePointElapsedSeconds, after.timePointElapsedSeconds);
		assertEquals(0.116, after.xyPixelSizeUm, 0.0);
		assertEquals(0.265, after.zStepSizeUm, 0.0);
		assertEquals(33.5, after.opmAngleDegrees, 0.0);
		assertEquals(300.0, after.frameIntervalSeconds, 0.0);
		assertEquals(before.channelLabels, after.channelLabels);
		assertEquals(before.alignMatrixModifiedUtc, after.alignMatrixModifiedUtc);
		assertEquals(before.computerName, after.computerName);
		assertEquals(before.pluginVersion, after.pluginVersion);
		assertEquals(before.processor, after.processor);
	}

	/**
	 * The alignment matrix is applied to pixels, so it must come back bit for bit.
	 *
	 * <p>These are the real values measured on a bead acquisition; a rounding loss here is a
	 * sub-pixel shift in every biological dataset that borrows the matrix.
	 */
	@Test
	public void alignMatrixSurvivesExactly() {
		OpmProvenance before = sample();
		double[][] original = before.alignMatrix;

		OpmProvenance after = OpmProvenance.fromJson(before.toJson());

		assertNotNull(after.alignMatrix);
		assertEquals(2, after.alignMatrix.length);
		for (int r = 0; r < 2; r++) {
			assertEquals(3, after.alignMatrix[r].length);
			for (int c = 0; c < 3; c++)
				assertEquals("row " + r + " col " + c, original[r][c], after.alignMatrix[r][c], 0.0);
		}
	}

	/** The deskew matrix likewise, since it is what makes the geometry reproducible. */
	@Test
	public void deskewMatrixSurvivesExactly() {
		OpmProvenance before = sample();
		OpmProvenance after = OpmProvenance.fromJson(before.toJson());

		assertEquals(4, after.deskewMatrix.length);
		for (int r = 0; r < 4; r++)
			for (int c = 0; c < 4; c++)
				assertEquals(before.deskewMatrix[r][c], after.deskewMatrix[r][c], 0.0);
	}

	/**
	 * The matrix is recorded, not applied.
	 *
	 * <p>The bead acquisition supplies the transform; the biological time-lapse borrows it and
	 * applies it when viewed, so the stored pixels stay resampled once rather than twice. A
	 * viewer that misread this flag would align already-aligned data.
	 */
	@Test
	public void alignmentIsRecordedNotApplied() {
		OpmProvenance after = OpmProvenance.fromJson(sample().toJson());
		assertFalse("stored pixels are unaligned", after.alignApplied);
		assertEquals("right-flipped-to-left", after.alignMatrixConvention);
		assertEquals(BatchChannelOperation.FLIP_RIGHT, after.alignFlipHalf);
		assertTrue(after.alignInterpolate);
		assertTrue("the matrix names where it came from",
				after.alignMatrixSource.contains("beads"));
	}

	/**
	 * The matrices are measured right-mirrored whatever is chosen, and the side flipped is the
	 * user's: a tagged set records the fixed convention and the run's own flip, as a bare CSV
	 * always did. It used to force the flip to the right half for a tagged set.
	 */
	@Test
	public void taggedMatricesKeepTheirConventionAndRecordTheChosenFlip() {
		OmeZarrConverter.Options options = new OmeZarrConverter.Options();
		String left = ChannelOperationSettings.sourceKey(1, true);
		String right = ChannelOperationSettings.sourceKey(1, false);
		options.alignReference = left;
		options.alignMatrices.put(left, AlignmentMatrixSet.identity2d());
		options.alignMatrices.put(right, new double[][] { { 1, 0, 4 }, { 0, 1, -2 } });
		options.flipRight = false;

		OpmProvenance provenance = OmeZarrConverter.provenance(
				new File("source"), new File("beads.ome.zarr"), options, Transform.identity());
		OpmProvenance after = OpmProvenance.fromJson(provenance.toJson());

		assertEquals("the measurement convention does not follow the flip",
				AlignmentMatrixSet.CONVENTION, after.alignMatrixConvention);
		assertEquals("the flip does", BatchChannelOperation.FLIP_LEFT, after.alignFlipHalf);
		assertEquals(left, after.alignReference);
		assertEquals("the stored matrices are the measured ones, unconverted",
				4.0, after.alignMatrices.get(right)[0][2], 0.0);
	}

	/** The deskew affine is sampled on a regular camera-pixel grid in all three output axes. */
	@Test
	public void computesDeskewedVoxelSize() {
		OpmProvenance p = new OpmProvenance();
		p.xyPixelSizeUm = 0.116;
		p.zStepSizeUm = 0.265;
		p.opmAngleDegrees = 33.5;
		p.computeDeskewedVoxelSize();

		assertEquals("x is unchanged", 0.116, p.deskewedVoxelSizeUm[0], 1e-12);
		assertEquals("y uses the output grid pitch", 0.116, p.deskewedVoxelSizeUm[1], 1e-12);
		assertEquals("z uses the output grid pitch", 0.116, p.deskewedVoxelSizeUm[2], 1e-12);
	}

	/** Existing schema-2 files with the exact old formula are corrected without touching others. */
	@Test
	public void recognisesLegacyDeskewedVoxelSize() {
		OpmProvenance p = new OpmProvenance();
		p.xyPixelSizeUm = 0.116;
		p.zStepSizeUm = 0.265;
		p.opmAngleDegrees = 25;
		p.deskewMatrix = Transform.deskew(0.265, 0.116, 25, 500);
		p.deskewedVoxelSizeUm = new double[] {
				0.116, 0.116 * Math.cos(Math.toRadians(25)), 0.265 * Math.sin(Math.toRadians(25)) };

		assertTrue(p.hasLegacyDeskewedVoxelSize());
		assertEquals(0.116, p.effectiveDeskewedVoxelSizeUm()[1], 0);
		assertEquals(0.116, p.effectiveDeskewedVoxelSizeUm()[2], 0);

		p.deskewedVoxelSizeUm = new double[] { 0.1, 0.2, 0.3 };
		assertFalse(p.hasLegacyDeskewedVoxelSize());
		assertEquals(0.2, p.effectiveDeskewedVoxelSizeUm()[1], 0);
	}

	/** The environment stamp has to produce something usable, not nulls. */
	@Test
	public void stampsTheEnvironment() {
		OpmProvenance p = new OpmProvenance().stampEnvironment(false);

		assertNotNull(p.createdUtc);
		assertTrue("ISO-8601 UTC: " + p.createdUtc, p.createdUtc.endsWith("Z") && p.createdUtc.contains("T"));
		assertNotNull(p.computerName);
		assertNotNull(p.operatingSystem);
		assertNotNull(p.javaVersion);
		assertNotNull(p.pluginVersion);
		assertEquals("CPU", p.processor);
		assertTrue(p.threadsUsed >= 1);
	}

	/** A dataset with no alignment yet is valid; the viewer just has nothing to apply. */
	@Test
	public void toleratesAMissingAlignment() {
		OpmProvenance p = new OpmProvenance();
		p.datasetName = "no-alignment";
		p.deskewMatrix = Transform.identity();
		p.computeDeskewedVoxelSize();

		OpmProvenance after = OpmProvenance.fromJson(p.toJson());
		assertNull(after.alignMatrix);
		assertNull(after.alignMatrixSource);
		assertFalse(after.alignApplied);
	}

	/** Null in, null out - a group with no record must not throw. */
	@Test
	public void handlesMissingRecord() {
		assertNull(OpmProvenance.fromJson(null));
	}

	/** The pretty form is what a human reads out of .zattrs, so it has to be real JSON. */
	@Test
	public void prettyJsonIsParseable() {
		String text = sample().toPrettyJson();
		assertTrue(text.contains("\"opmAngleDegrees\""));
		assertTrue(text.contains("\"alignMatrix\""));
		JsonObject parsed = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
		assertEquals(33.5, parsed.get("opmAngleDegrees").getAsDouble(), 0.0);
	}

	private static OpmProvenance sample() {
		OpmProvenance p = new OpmProvenance();
		p.datasetName = "3_timelapse";
		p.sourceFolder = "D:\\Workspace\\Microscopes\\OPM\\multi-channel-test\\3_timelapse_0";
		p.sourceFiles = Arrays.asList(
				"3_timelapse_Position0001_Time000001_Channel0001_Frames_1_451.tiff",
				"3_timelapse_Position0001_Time000001_Channel0002_Frames_1_451.tiff");
		p.timePointLabels = Arrays.asList("Time000001", "Time000002");
		p.timePointElapsedSeconds = Arrays.asList(0.0, 300.0);
		p.xyPixelSizeUm = 0.116;
		p.zStepSizeUm = 0.265;
		p.opmAngleDegrees = 33.5;
		p.frameIntervalSeconds = 300.0;
		p.deskewMatrix = Transform.deskew(0.265, 0.116, 33.5, 500);
		// the real values measured on the bead acquisition
		p.alignMatrix = new double[][] {
			{ 0.9999905126191354, -0.004355992621549795, 11.659000466098544 },
			{ 0.004355992621549795, 0.9999905126191354, -7.493068474056827 }
		};
		p.alignMatrixSource = "4_beads_for_overlay_Position0001_Time000001_Channel0001_Frames_1_451_align.csv";
		p.alignFlipHalf = BatchChannelOperation.FLIP_RIGHT;
		p.channelLabels = Arrays.asList(
				"_Channel0001-left", "_Channel0001-right", "_Channel0002-left", "_Channel0002-right");
		p.computeDeskewedVoxelSize();
		return p.stampEnvironment(false);
	}
}
