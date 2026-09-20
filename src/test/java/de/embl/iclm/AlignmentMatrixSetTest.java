package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import ij.process.FloatProcessor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class AlignmentMatrixSetTest {

	private static final double[][] MATRIX = { { 0.9999, -0.01, 3.25 }, { 0.01, 0.9999, -1.5 } };

	@Test
	public void classicPairStaysBareTwoByThreeCsv() throws Exception {
		AlignmentMatrixSet set = new AlignmentMatrixSet(ChannelOperationSettings.sourceKey(1, true));
		set.put(ChannelOperationSettings.sourceKey(1, false), MATRIX);
		File file = File.createTempFile("opm-alignment-classic", ".csv");
		try {
			assertTrue(set.save(file));
			String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
			assertFalse(text.contains(AlignmentMatrixSet.HEADER));
			assertEquals(2, text.trim().split("\\R").length);
			AlignmentMatrixSet loaded = AlignmentMatrixSet.load(file.getAbsolutePath());
			assertTrue(loaded.isLegacy());
			assertArrayEquals(MATRIX[0], loaded.legacyMatrix()[0], 0.0);
			assertArrayEquals(MATRIX[1], IO.loadMatrixFromFile(file.getAbsolutePath())[1], 0.0);
		} finally { file.delete(); }
	}

	@Test
	public void taggedSetRoundTripsEverySourceAndOldLoaderGetsFallback() throws Exception {
		String reference = ChannelOperationSettings.sourceKey(1, true);
		AlignmentMatrixSet set = new AlignmentMatrixSet(reference);
		set.put(ChannelOperationSettings.sourceKey(1, false), MATRIX);
		set.put(ChannelOperationSettings.sourceKey(2, true), new double[][] { { 1, 0, 7 }, { 0, 1, 2 } });
		set.put(ChannelOperationSettings.sourceKey(2, false), new double[][] { { 1, 0, 8 }, { 0, 1, 3 } });
		File file = File.createTempFile("opm-alignment-multi", ".csv");
		try {
			assertTrue(set.save(file));
			String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
			assertTrue(text.startsWith(AlignmentMatrixSet.HEADER));
			AlignmentMatrixSet loaded = AlignmentMatrixSet.load(file.getAbsolutePath());
			assertNotNull(loaded);
			assertFalse(loaded.isLegacy());
			assertEquals(reference, loaded.reference());
			assertEquals(4, loaded.size());
			assertEquals(8.0, loaded.matrixFor(ChannelOperationSettings.sourceKey(2, false))[0][2], 0.0);
			assertArrayEquals(MATRIX[0], IO.loadMatrixFromFile(file.getAbsolutePath())[0], 0.0);
		} finally { file.delete(); }
	}

	// ---- the runtime flip ---------------------------------------------------------------

	private static final int W = 128, H = 96;
	private static final String L1 = ChannelOperationSettings.sourceKey(1, true);
	private static final String R1 = ChannelOperationSettings.sourceKey(1, false);
	private static final String L2 = ChannelOperationSettings.sourceKey(2, true);
	private static final String R2 = ChannelOperationSettings.sourceKey(2, false);

	/**
	 * Measured once, shown from either side.
	 *
	 * <p>Four camera halves of two files are rendered analytically from one scene, each so that
	 * its stored right-mirrored matrix maps it exactly onto the reference. Flipping the right
	 * halves must then reproduce the scene; flipping the left halves must reproduce the scene as
	 * the unflipped right half of the reference channel sees it - every source, the reference
	 * included, landing on the same pixels. Rendering the sources from the scene function rather
	 * than warping an image keeps each output to a single interpolation, so the tolerance
	 * measures the rule rather than accumulated resampling.
	 */
	@Test
	public void flipLeftIsTheSameAlignmentSeenFromTheOtherSide() {
		double[][] mR1 = rigid(0.6, 3.2, -2.1);
		double[][] mL2 = rigid(0.3, 2.4, 1.3);
		double[][] mR2 = rigid(-0.4, -1.7, 2.6);
		AlignmentMatrixSet set = new AlignmentMatrixSet(L1);
		set.put(R1, mR1);
		set.put(L2, mL2);
		set.put(R2, mR2);
		java.util.Map<String, double[][]> stored = new java.util.LinkedHashMap<String, double[][]>();
		stored.put(L1, AlignmentMatrixSet.identity2d());
		stored.put(R1, mR1);
		stored.put(L2, mL2);
		stored.put(R2, mR2);

		double[][] toRightFrame = times(h(mR1), mirror());			// M_R1 F
		for (java.util.Map.Entry<String, double[][]> source : stored.entrySet()) {
			boolean right = source.getKey().endsWith("-right");
			double[][] placement = right ? times(h(source.getValue()), mirror()) : h(source.getValue());
			FloatProcessor acquired = render(placement);

			AlignmentMatrixSet.Placement asMeasured =
					AlignmentMatrixSet.placement(set, source.getKey(), false, W);
			assertEquals(source.getKey() + " mirrored as measured", right, asMeasured.mirror);
			assertTrue(source.getKey() + " flipped right lands on the scene",
					error(place(acquired, asMeasured), identity3()) < 0.1);

			AlignmentMatrixSet.Placement otherSide =
					AlignmentMatrixSet.placement(set, source.getKey(), true, W);
			assertEquals(source.getKey() + " mirrored from the other side", !right, otherSide.mirror);
			FloatProcessor seen = place(acquired, otherSide);
			assertTrue(source.getKey() + " flipped left lands where the unflipped right half sees it",
					error(seen, toRightFrame) < 0.1);
			assertTrue(source.getKey() + ": the check can fail - the scene itself is elsewhere",
					error(seen, identity3()) > 0.5);
		}
		assertNull("the new fixed frame is the unflipped right half itself",
				AlignmentMatrixSet.placement(set, R1, true, W).matrix);
	}

	/** A bare CSV already had a flip-left rule; the general one must be exactly that rule. */
	@Test
	public void aBareCsvFlipsLeftExactlyAsItAlwaysHas() {
		double[][] m = rigid(0.26, 11.66, -7.49);
		AlignmentMatrixSet legacy = AlignmentMatrixSet.legacy(m);
		for (int channel = 1; channel <= 2; channel++) {
			String left = ChannelOperationSettings.sourceKey(channel, true);
			String right = ChannelOperationSettings.sourceKey(channel, false);
			AlignmentMatrixSet.Placement rightAsMeasured = AlignmentMatrixSet.placement(legacy, right, false, W);
			assertTrue(rightAsMeasured.mirror);
			assertArrayEquals(m[0], rightAsMeasured.matrix[0], 0.0);
			assertNull(AlignmentMatrixSet.placement(legacy, left, false, W).matrix);

			AlignmentMatrixSet.Placement leftFlipped = AlignmentMatrixSet.placement(legacy, left, true, W);
			assertTrue(leftFlipped.mirror);
			double[][] historic = Transform.mirrorAlignmentMatrix2D(m, W);
			for (int row = 0; row < 2; row++)
				assertArrayEquals("channel " + channel, historic[row], leftFlipped.matrix[row], 1e-9);
			AlignmentMatrixSet.Placement rightKept = AlignmentMatrixSet.placement(legacy, right, true, W);
			assertFalse(rightKept.mirror);
			assertNull("the kept right half is the frame", rightKept.matrix);
		}
	}

	/** A full camera width carries both halves in place, so no flip choice mirrors it. */
	@Test
	public void aWholeWidthSourceIsNeverMirrored() {
		String whole = ChannelOperationSettings.wholeSourceKey(2);
		AlignmentMatrixSet set = new AlignmentMatrixSet(ChannelOperationSettings.wholeSourceKey(1));
		set.put(whole, MATRIX);
		for (boolean flipLeft : new boolean[] { false, true }) {
			AlignmentMatrixSet.Placement placement = AlignmentMatrixSet.placement(set, whole, flipLeft, W);
			assertFalse(placement.mirror);
			assertArrayEquals(MATRIX[0], placement.matrix[0], 0.0);
		}
		AlignmentMatrixSet.Placement none = AlignmentMatrixSet.placement(null, R2, true, W);
		assertFalse("no matrix file still flips the chosen side only", none.mirror);
		assertNull(none.matrix);
	}

	private static double[][] rigid(double degrees, double tx, double ty) {
		double a = Math.toRadians(degrees);
		return new double[][] { { Math.cos(a), -Math.sin(a), tx }, { Math.sin(a), Math.cos(a), ty } };
	}

	private static double[][] h(double[][] m) {
		return new double[][] { { m[0][0], m[0][1], m[0][2] }, { m[1][0], m[1][1], m[1][2] }, { 0, 0, 1 } };
	}

	private static double[][] mirror() {
		return new double[][] { { -1, 0, W - 1 }, { 0, 1, 0 }, { 0, 0, 1 } };
	}

	private static double[][] identity3() {
		return new double[][] { { 1, 0, 0 }, { 0, 1, 0 }, { 0, 0, 1 } };
	}

	private static double[][] times(double[][] a, double[][] b) {
		double[][] c = new double[3][3];
		for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++)
			for (int k = 0; k < 3; k++) c[i][j] += a[i][k] * b[k][j];
		return c;
	}

	/** A smooth, asymmetric scene, so a mirror or a shift of a pixel is plainly visible. */
	private static double scene(double x, double y) {
		double[][] blobs = { { 24, 22, 900 }, { 41, 60, 700 }, { 58, 31, 1000 }, { 77, 71, 600 },
				{ 93, 26, 800 }, { 104, 55, 950 }, { 35, 40, 500 }, { 66, 50, 750 }, { 85, 44, 650 } };
		double value = 0;
		for (double[] b : blobs) {
			double dx = x - b[0], dy = y - b[1];
			value += b[2] * Math.exp(-(dx * dx + dy * dy) / (2 * 2.5 * 2.5));
		}
		return value;
	}

	/** An image that a warp by {@code placement} would map onto the scene: src(q) = s(A q). */
	private static FloatProcessor render(double[][] placement) {
		FloatProcessor image = new FloatProcessor(W, H);
		for (int y = 0; y < H; y++) for (int x = 0; x < W; x++)
			image.setf(x, y, (float) scene(placement[0][0] * x + placement[0][1] * y + placement[0][2],
					placement[1][0] * x + placement[1][1] * y + placement[1][2]));
		return image;
	}

	private static FloatProcessor place(FloatProcessor acquired, AlignmentMatrixSet.Placement placement) {
		ij.process.ImageProcessor image = acquired.duplicate();
		if (placement.mirror) image.flipHorizontal();
		if (placement.matrix != null) image = SIFT.alignWithRigid2DMatrix(image, placement.matrix, true);
		return (FloatProcessor) image;
	}

	/**
	 * Total absolute difference from s(G p) over the interior, relative to the total signal.
	 * <p>
	 * Relative to the signal rather than the peak: beads are sparse, so a per-pixel mean is
	 * dominated by empty background and scores even a wholly misplaced scene as close.
	 */
	private static double error(FloatProcessor image, double[][] g) {
		int margin = 14;
		double difference = 0, signal = 0;
		for (int y = margin; y < H - margin; y++) for (int x = margin; x < W - margin; x++) {
			double expected = scene(g[0][0] * x + g[0][1] * y + g[0][2], g[1][0] * x + g[1][1] * y + g[1][2]);
			difference += Math.abs(image.getf(x, y) - expected);
			signal += expected;
		}
		return difference / signal;
	}

	@Test
	public void manualAdjustmentIsAppliedAfterAutomaticFit() {
		double[][] adjusted = ChannelAlignment.manualAdjustment(
				new double[][] { { 1, 0, 2 }, { 0, 1, -1 } },
				new double[] { 3, 4, 0 }, 100, 80);
		assertArrayEquals(new double[] { 1, 0, 5 }, adjusted[0], 1e-12);
		assertArrayEquals(new double[] { 0, 1, 3 }, adjusted[1], 1e-12);
	}

	@Test
	public void logCentresRecoverSubpixelTranslation() {
		FloatProcessor fixed = beads(320, 220, 0, 0);
		FloatProcessor moving = beads(320, 220, 4, -3);
		BeadAlignment.Result result = BeadAlignment.logOnly(fixed, moving);
		assertNotNull(result);
		assertTrue(result.matches >= 8);
		assertEquals(-4.0, result.matrix[0][2], 0.35);
		assertEquals(3.0, result.matrix[1][2], 0.35);
		assertTrue(result.rmsPixels < 0.5);
	}

	private static FloatProcessor beads(int width, int height, double dx, double dy) {
		float[] pixels = new float[width * height];
		for (int row = 0; row < 4; row++) for (int column = 0; column < 7; column++) {
			double x = 35 + column * 39 + (row % 2) * 7 + dx;
			double y = 32 + row * 46 + (column % 3) * 5 + dy;
			double amplitude = 500 + 17 * row + 11 * column;
			for (int py = (int) y - 7; py <= (int) y + 7; py++)
				for (int px = (int) x - 7; px <= (int) x + 7; px++) {
					if (px < 0 || py < 0 || px >= width || py >= height) continue;
					double sx = px - x, sy = py - y;
					pixels[py * width + px] += amplitude * Math.exp(-(sx * sx + sy * sy) / 8.0);
				}
		}
		return new FloatProcessor(width, height, pixels);
	}
}
