package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression tests for the deskew geometry, which had no test coverage at all.
 *
 * <p>These are pure functions over numbers: no image, no GPU, no Fiji. That is exactly why
 * they are worth pinning - a sign error or a swapped axis in an affine matrix still looks
 * plausible on screen, so the eye is a poor check and an assertion is a good one.
 *
 * <p>Covered here:
 * <ul>
 * <li>{@link Transform#deskew} builds the 4x4 matrix documented in {@link Deskew}: shear Y along
 *     Z by dz_step/dxy, cos&theta; on Y, -sin&theta; on the Z row, and a Z translation of
 *     imageHeight*sin&theta;.</li>
 * <li>{@code matrix[2][2]} stays at {@link Double#MIN_VALUE} rather than 0. The value is
 *     deliberate and undocumented failures were once attributed to a zero row, so it is
 *     pinned here: anyone tidying it to 0.0 has to do so knowingly.</li>
 * <li>{@link Transform#reverse_deskew} recovers the acquisition geometry from the matrix, so
 *     a saved matrix can repopulate the dialog. This is the round trip that makes a stored
 *     CSV self describing.</li>
 * <li>{@link Transform#parseTransformation} and {@link Transform#getPartitionAxis} no longer
 *     fall through from one shear case into the next. Before, shearing X along X returned the
 *     matrix for shearing Y along X, silently.</li>
 * </ul>
 */
public class TransformDeskewTest {

	/** House geometry of the EMBL IC OPM: 116 nm pixels, 132.5 nm steps, 25 degrees. */
	private static final double XY_PIXEL_NM = 116.0d;
	private static final double Z_STEP_NM = 132.5d;
	private static final double OPM_ANGLE_DEG = 25.0d;
	private static final double IMAGE_HEIGHT = 800.0d;

	private static final double TOLERANCE = 1e-9;

	@Test
	public void deskewMatrixMatchesTheDocumentedGeometry () {
		double[][] matrix = Transform.deskew ( Z_STEP_NM, XY_PIXEL_NM, OPM_ANGLE_DEG, IMAGE_HEIGHT );

		assertNotNull ( "deskew must always return a matrix", matrix );
		// a full 4x4 homogeneous matrix; the last row stays (0,0,0,1)
		assertEquals ( "matrix is 4 rows of 4", 4, matrix.length );
		assertEquals ( "matrix is 4 rows of 4", 4, matrix[0].length );
		assertEquals ( 0.0d, matrix[3][0], TOLERANCE );
		assertEquals ( 0.0d, matrix[3][1], TOLERANCE );
		assertEquals ( 0.0d, matrix[3][2], TOLERANCE );
		assertEquals ( 1.0d, matrix[3][3], TOLERANCE );

		// X is untouched: the shear happens in the YZ plane only
		assertEquals ( 1.0d, matrix[0][0], TOLERANCE );
		assertEquals ( 0.0d, matrix[0][1], TOLERANCE );
		assertEquals ( 0.0d, matrix[0][2], TOLERANCE );
		assertEquals ( 0.0d, matrix[0][3], TOLERANCE );

		assertEquals ( "Y scaled by cos(theta)", Math.cos(Math.toRadians(OPM_ANGLE_DEG)), matrix[1][1], TOLERANCE );
		assertEquals ( "Y sheared along Z by dz/dxy", Z_STEP_NM / XY_PIXEL_NM, matrix[1][2], TOLERANCE );
		assertEquals ( "Z row carries -sin(theta)", -Math.sin(Math.toRadians(OPM_ANGLE_DEG)), matrix[2][1], TOLERANCE );
		assertEquals ( "Z translated by h*sin(theta)",
				IMAGE_HEIGHT * Math.sin(Math.toRadians(OPM_ANGLE_DEG)), matrix[2][3], TOLERANCE );
	}

	@Test
	public void zRowScaleStaysTheDeliberateNearZero () {
		double[][] matrix = Transform.deskew ( Z_STEP_NM, XY_PIXEL_NM, OPM_ANGLE_DEG, IMAGE_HEIGHT );
		assertEquals ( "matrix[2][2] is deliberately the smallest positive double, not 0",
				Transform.Z_SCALE_NEAR_ZERO, matrix[2][2], 0.0d );
		assertTrue ( "and it is still positive", matrix[2][2] > 0.0d );
	}

	@Test
	public void theLinearPartStaysInvertible () {
		/* The determinant is carried by (dz/dxy) * sin(theta), not by matrix[2][2], which is
		 * why the near zero entry is numerically harmless. */
		double[][] m = Transform.deskew ( Z_STEP_NM, XY_PIXEL_NM, OPM_ANGLE_DEG, IMAGE_HEIGHT );
		double determinant =
				  m[0][0] * (m[1][1]*m[2][2] - m[1][2]*m[2][1])
				- m[0][1] * (m[1][0]*m[2][2] - m[1][2]*m[2][0])
				+ m[0][2] * (m[1][0]*m[2][1] - m[1][1]*m[2][0]);
		double expected = (Z_STEP_NM / XY_PIXEL_NM) * Math.sin(Math.toRadians(OPM_ANGLE_DEG));
		assertEquals ( expected, determinant, 1e-9 );
		assertTrue ( "the deskew matrix is not singular", Math.abs(determinant) > 1e-6 );
	}

	@Test
	public void reverseDeskewRecoversTheAcquisitionGeometry () {
		double[][] matrix = Transform.deskew ( Z_STEP_NM, XY_PIXEL_NM, OPM_ANGLE_DEG, IMAGE_HEIGHT );
		double[] recovered = Transform.reverse_deskew ( matrix, IMAGE_HEIGHT );

		assertNotNull ( "a matrix built by deskew must be readable by reverse_deskew", recovered );
		assertEquals ( "dz_step / dxy", Z_STEP_NM / XY_PIXEL_NM, recovered[0], TOLERANCE );
		assertEquals ( "OPM angle in degrees", OPM_ANGLE_DEG, recovered[1], 0.05d );
		assertEquals ( "Z translation for this image height",
				IMAGE_HEIGHT * Math.sin(Math.toRadians(OPM_ANGLE_DEG)), recovered[2], 1e-3 );
	}

	@Test
	public void reverseDeskewRoundTripsOverTheUsefulAngleRange () {
		for ( double angle = 5.0d; angle <= 60.0d; angle += 5.0d ) {
			double[][] matrix = Transform.deskew ( Z_STEP_NM, XY_PIXEL_NM, angle, IMAGE_HEIGHT );
			double[] recovered = Transform.reverse_deskew ( matrix, IMAGE_HEIGHT );
			assertNotNull ( "angle " + angle + " must round trip", recovered );
			assertEquals ( "angle " + angle, angle, recovered[1], 0.05d );
			assertEquals ( "step ratio at angle " + angle,
					Z_STEP_NM / XY_PIXEL_NM, recovered[0], TOLERANCE );
		}
	}

	@Test
	public void reverseDeskewRefusesAMatrixThatIsNotADeskew () {
		double[][] identity = Transform.identity();
		identity[1][1] = 0.5d;		// cos and sin no longer agree on one angle
		identity[2][1] = -0.99d;
		assertNull ( "an inconsistent matrix must be reported, not silently accepted",
				Transform.reverse_deskew ( identity, IMAGE_HEIGHT ) );
	}

	@Test
	public void reverseDeskewRescalesTheZTranslationToTheNewImageHeight () {
		/* This is the reason reverse_deskew takes a height at all: a matrix saved from an 800
		 * pixel acquisition has to be usable on a 500 pixel one. */
		double[][] matrix = Transform.deskew ( Z_STEP_NM, XY_PIXEL_NM, OPM_ANGLE_DEG, 800.0d );
		double[] recovered = Transform.reverse_deskew ( matrix, 500.0d );
		assertNotNull ( recovered );
		assertEquals ( 500.0d * Math.sin(Math.toRadians(OPM_ANGLE_DEG)), recovered[2], 1e-3 );
	}

	@Test
	public void shearCasesDoNotFallThroughIntoTheNextShearType () {
		// shearing X along X is not one of the offered transformations
		assertNull ( "shear_X along X must be refused",
				Transform.parseTransformation ( "shear_X", "X", 1.0d ) );
		assertNull ( "shear_Y along Y must be refused",
				Transform.parseTransformation ( "shear_Y", "Y", 1.0d ) );
		assertNull ( "shear_Z along Z must be refused",
				Transform.parseTransformation ( "shear_Z", "Z", 1.0d ) );

		assertNull ( Transform.getPartitionAxis ( "shear_X", "X", 1.0d ) );
		assertNull ( Transform.getPartitionAxis ( "shear_Y", "Y", 1.0d ) );
		assertNull ( Transform.getPartitionAxis ( "shear_Z", "Z", 1.0d ) );
	}

	@Test
	public void validShearCombinationsStillResolve () {
		assertNotNull ( Transform.parseTransformation ( "shear_X", "Y", 1.0d ) );
		assertNotNull ( Transform.parseTransformation ( "shear_X", "Z", 1.0d ) );
		assertNotNull ( Transform.parseTransformation ( "shear_Y", "X", 1.0d ) );
		assertNotNull ( Transform.parseTransformation ( "shear_Z", "Y", 1.0d ) );

		assertEquals ( "Z", Transform.getPartitionAxis ( "shear_X", "Y", 1.0d ) );
		assertEquals ( "Y", Transform.getPartitionAxis ( "shear_X", "Z", 1.0d ) );
		assertEquals ( "Y", Transform.getPartitionAxis ( "translate", "X", 1.0d ) );
		assertEquals ( "X", Transform.getPartitionAxis ( "scale", "Z", 1.0d ) );
		assertEquals ( "Z", Transform.getPartitionAxis ( "rotate", "Z", 1.0d ) );
	}

	@Test
	public void axisComparisonDoesNotDependOnStringIdentity () {
		/* The axis used to be compared with ==, which only works for interned literals. A value
		 * built at runtime, as it is when it comes from a dialog or a settings file, took the
		 * wrong branch. */
		String axisFromInput = new StringBuilder("Y").toString();
		assertNotNull ( "an axis built at runtime must behave like the literal",
				Transform.parseTransformation ( "shear_X", axisFromInput, 1.0d ) );
		assertEquals ( "Z", Transform.getPartitionAxis ( "shear_X", axisFromInput, 1.0d ) );
	}
}
