package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

/**
 * What "align with SIFT" in Deskew Image now measures with.
 * <p>
 * The option's label is unchanged and so is its result - a 2 x 3 rigid matrix mapping the flipped
 * right half onto the left one - but the measurement is the bead engine the Align Channel of OPM
 * Data tool uses, with SIFT + RANSAC kept as the fallback for data it cannot handle.
 */
public class DeskewAlignmentEngineTest {

	private static final int WIDTH = 480, HEIGHT = 360;
	/** What two halves of one camera chip are plausibly apart. */
	private static final double SHIFT_X = 6.4, SHIFT_Y = -2.7, ANGLE = 0.26;

	@Test
	public void aBeadFieldIsMeasuredToWellUnderAPixel() {
		FloatProcessor reference = beads(41, 5);
		double[][] truth = rigid(ANGLE, SHIFT_X, SHIFT_Y);
		ImageProcessor moving = warpedByInverseOf(reference, truth);

		double[][] measured = SIFT.measureAlignment(reference, moving);

		assertNotNull("a bead field must be measurable", measured);
		assertEquals("X", SHIFT_X, measured[0][2], 0.35);
		assertEquals("Y", SHIFT_Y, measured[1][2], 0.35);
		assertEquals("rotation", Math.sin(Math.toRadians(ANGLE)), measured[1][0], 0.004);
	}

	@Test
	public void itBeatsPlainSiftOnBeadsWithOrWithoutBackgroundTexture() {
		/* The point of the change. Diffraction-limited beads are all the same round blob, so a
		 * SIFT descriptor cannot tell one from another and RANSAC has to find a consensus among
		 * mostly arbitrary pairings. Localising the beads and pairing them by position is what a
		 * bead field affords. Both engines are given exactly the same two images.
		 *
		 * Both characters are tested because they fail differently: on a clean bead field SIFT
		 * has nothing to describe and returns no matrix at all, while background texture gives
		 * it keypoints and it then lands within a fraction of a pixel - just not as close. */
		double[][] truth = rigid(ANGLE, SHIFT_X, SHIFT_Y);
		for (double texture : new double[] { 0, 900 }) {
			FloatProcessor reference = beads(41, 5, texture);
			ImageProcessor moving = warpedByInverseOf(reference, truth);

			double bead = translationError(SIFT.measureAlignment(reference, moving), truth);
			double sift = translationError(
					SIFT.computeAlignMatrix(reference.duplicate(), moving.duplicate(), false), truth);

			System.out.printf("MEASURE texture %3.0f: bead engine %s, SIFT + RANSAC %s%n",
					texture, report(bead), report(sift));
			assertTrue("bead engine " + report(bead), bead < 0.5);
			assertTrue("bead engine " + report(bead) + " against SIFT " + report(sift), bead <= sift);
		}
	}

	@Test
	public void halvesOfAnOddWidthChipFallBackInsteadOfThrowing() {
		/* BeadAlignment pairs spots by position and demands one coordinate system; SIFT does not.
		 * An odd camera width leaves the two halves a pixel apart, which must not throw. */
		FloatProcessor reference = beads(41, 5);
		ImageProcessor narrower = reference.resize(WIDTH - 1, HEIGHT, true);

		double[][] measured = SIFT.measureAlignment(reference, narrower);

		assertNotNull("the SIFT fallback still answers", measured);
		assertTrue("and it is a 2 x 3", AlignmentMatrixSet.is2d(measured));
	}

	@Test
	public void aFeaturelessPairIsRefusedRatherThanGuessedAt() {
		// Flat noise has no beads and no texture: both engines fail and the caller keeps the flip.
		FloatProcessor flat = noise(3);
		double[][] measured = SIFT.measureAlignment(flat, noise(4));
		assertTrue("an unmeasurable pair gives nothing to apply",
				measured == null || measured[0][0] == 0 || !SIFT.checkAlignMatrix(measured));
	}

	private static String report(double error) {
		return error == Double.MAX_VALUE ? "no matrix" : String.format("%.3f px", error);
	}

	private static FloatProcessor beads(int count, long seed) {
		return beads(count, seed, 0);
	}

	/**
	 * A field of diffraction-limited beads: the calibration image this option is pointed at.
	 *
	 * @param texture	: amplitude of smooth background structure, which is what gives SIFT
	 * 					  something to describe; 0 is a clean bead field
	 */
	private static FloatProcessor beads(int count, long seed, double texture) {
		FloatProcessor image = new FloatProcessor(WIDTH, HEIGHT);
		Random random = new Random(seed);
		double sigma = 2.0;
		if (texture > 0) {
			FloatProcessor rough = new FloatProcessor(WIDTH, HEIGHT);
			for (int i = 0; i < WIDTH * HEIGHT; i++) rough.setf(i, (float) (random.nextDouble() * texture));
			new ij.plugin.filter.GaussianBlur().blurGaussian(rough, 6, 6, 0.01);
			for (int i = 0; i < WIDTH * HEIGHT; i++) image.setf(i, rough.getf(i) * 6);
		}
		for (int i = 0; i < count; i++) {
			double cx = 30 + random.nextDouble() * (WIDTH - 60);
			double cy = 30 + random.nextDouble() * (HEIGHT - 60);
			double amplitude = 800 + random.nextDouble() * 1600;
			for (int y = (int) cy - 7; y <= cy + 7; y++) for (int x = (int) cx - 7; x <= cx + 7; x++) {
				if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) continue;
				double dx = x - cx, dy = y - cy;
				image.setf(x, y, image.getf(x, y)
						+ (float) (amplitude * Math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma))));
			}
		}
		for (int i = 0; i < WIDTH * HEIGHT; i++)
			image.setf(i, image.getf(i) + 20 + (float) (random.nextDouble() * 8));
		return image;
	}

	private static FloatProcessor noise(long seed) {
		FloatProcessor image = new FloatProcessor(WIDTH, HEIGHT);
		Random random = new Random(seed);
		for (int i = 0; i < WIDTH * HEIGHT; i++) image.setf(i, 100 + (float) (random.nextDouble() * 5));
		return image;
	}

	/** The moving image whose pixels {@code matrix} maps back onto the reference. */
	private static ImageProcessor warpedByInverseOf(ImageProcessor source, double[][] matrix) {
		return SIFT.alignWithRigid2DMatrix(source, Transform.inverseAlignmentMatrix2D(matrix), true);
	}

	private static double[][] rigid(double degrees, double x, double y) {
		double angle = Math.toRadians(degrees), cos = Math.cos(angle), sin = Math.sin(angle);
		return new double[][] { { cos, -sin, x }, { sin, cos, y } };
	}

	private static double translationError(double[][] measured, double[][] truth) {
		if (measured == null || measured[0][0] == 0) return Double.MAX_VALUE;
		double dx = measured[0][2] - truth[0][2], dy = measured[1][2] - truth[1][2];
		return Math.sqrt(dx * dx + dy * dy);
	}
}
