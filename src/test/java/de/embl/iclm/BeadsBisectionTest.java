package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

/**
 * Regression tests for the automatic maxima tolerance search used when preparing a PSF from a
 * bead volume.
 *
 * <p>The search bisects the MaximumFinder prominence tolerance until the number of detected
 * beads is close to the number the user expects. Three things were wrong with it:
 *
 * <ul>
 * <li>the convergence tolerance was {@code nPoints/20} in integer arithmetic, so any expected
 *     count below 20 gave 0 and the loop could only ever end on its iteration cap;</li>
 * <li>the loop condition compared that count based tolerance against
 *     {@code maxTolerance - minTolerance}, which is an intensity interval - two different
 *     quantities, so the test meant nothing;</li>
 * <li>the mismatch at the low end of the bracket was recomputed on every iteration, doubling
 *     the number of full maxima detections the search costs.</li>
 * </ul>
 *
 * <p>The synthetic field below gives its beads a range of peak heights on purpose. With
 * identical beads the detected count is a step function of tolerance - all of them, then none
 * - and no bisection could land on an intermediate number.
 */
public class BeadsBisectionTest {

	private static final float BACKGROUND = 100f;
	private static final float DIMMEST_PEAK = 400f;
	private static final float BRIGHTEST_PEAK = 4000f;

	/**
	 * Beads on a regular grid with peak heights rising linearly from the dimmest to the
	 * brightest, so that raising the tolerance removes them a few at a time.
	 *
	 * @param size		: width and height of the square test image
	 * @param spacing	: grid pitch in pixels; wide enough that neighbours never merge
	 */
	private static ImageProcessor gradedBeadField ( int size, int spacing ) {
		FloatProcessor ip = new FloatProcessor ( size, size );
		for ( int i = 0; i < size * size; i++ ) ip.setf ( i, BACKGROUND );

		int columns = (size - spacing) / spacing;
		int rows = (size - spacing) / spacing;
		int total = Math.max ( 1, columns * rows );
		int placed = 0;
		for ( int row = 0; row < rows; row++ ) {
			for ( int column = 0; column < columns; column++ ) {
				int x = spacing + column * spacing;
				int y = spacing + row * spacing;
				float peak = DIMMEST_PEAK
						+ (BRIGHTEST_PEAK - DIMMEST_PEAK) * placed / (float) total;
				ip.setf ( x, y, peak );
				// a small skirt, so each bead is a peak rather than a single hot pixel
				ip.setf ( x-1, y, BACKGROUND + (peak - BACKGROUND) * 0.3f );
				ip.setf ( x+1, y, BACKGROUND + (peak - BACKGROUND) * 0.3f );
				ip.setf ( x, y-1, BACKGROUND + (peak - BACKGROUND) * 0.3f );
				ip.setf ( x, y+1, BACKGROUND + (peak - BACKGROUND) * 0.3f );
				placed++;
			}
		}
		return ip;
	}

	/** How many maxima this image actually offers, at a tolerance low enough to keep them all. */
	private static int availableBeads ( ImageProcessor ip ) {
		return (int) ( Beads.getNumPointsMismatch ( ip, 1.0d, 0 ) );
	}

	@Test
	public void findsAToleranceMatchingASmallExpectedCount () {
		/* Fewer than 20 beads is the case the old integer division broke: nPoints/20 was 0, so
		 * the loop had no meaningful stopping condition and returned whatever the tenth
		 * bisection step happened to be. */
		ImageProcessor ip = gradedBeadField ( 160, 20 );
		int available = availableBeads ( ip );
		assertTrue ( "the test image must offer more beads than we ask for", available > 12 );

		int expected = 12;
		double tolerance = Beads.getFuncValueBisection ( ip, expected, 0.0d, BRIGHTEST_PEAK );
		double mismatch = Beads.getNumPointsMismatch ( ip, tolerance, expected );

		assertTrue ( "tolerance must stay inside the searched bracket",
				tolerance > 0.0d && tolerance < BRIGHTEST_PEAK );
		assertTrue ( "detected count must land within one bead of the expected " + expected
				+ " (was off by " + mismatch + ")", Math.abs ( mismatch ) <= 1.0d );
	}

	@Test
	public void findsAToleranceMatchingALargeExpectedCount () {
		ImageProcessor ip = gradedBeadField ( 320, 10 );
		int available = availableBeads ( ip );
		assertTrue ( "the test image must offer plenty of beads", available > 200 );

		int expected = available / 2;
		double tolerance = Beads.getFuncValueBisection ( ip, expected, 0.0d, BRIGHTEST_PEAK );
		double mismatch = Beads.getNumPointsMismatch ( ip, tolerance, expected );

		// 5% of the expected count is the documented accuracy of the search
		assertTrue ( "detected count must land within 5% of " + expected
				+ " (was off by " + mismatch + ")", Math.abs ( mismatch ) <= expected * 0.05d );
	}

	@Test
	public void convergesForAnExpectedCountBelowTheOldEpsilonFloor () {
		/* Every count below 20 used to give an epsilon of exactly 0 through integer division.
		 * Walk the small counts to show they all converge now. */
		ImageProcessor ip = gradedBeadField ( 240, 15 );
		int available = availableBeads ( ip );
		for ( int expected = 2; expected <= 15; expected++ ) {
			if ( expected >= available ) break;
			double tolerance = Beads.getFuncValueBisection ( ip, expected, 0.0d, BRIGHTEST_PEAK );
			double mismatch = Beads.getNumPointsMismatch ( ip, tolerance, expected );
			assertTrue ( "expected " + expected + " beads, off by " + mismatch,
					Math.abs ( mismatch ) <= 1.0d );
		}
	}

	@Test
	public void mismatchIsSignedAndFallsAsToleranceRises () {
		/* The bisection relies on the count falling as the tolerance rises. If that ever stops
		 * being true the bracket logic is meaningless, so assert it directly. */
		ImageProcessor ip = gradedBeadField ( 200, 20 );
		int expected = 8;

		double low = Beads.getNumPointsMismatch ( ip, 10.0d, expected );
		double middle = Beads.getNumPointsMismatch ( ip, 2000.0d, expected );
		double high = Beads.getNumPointsMismatch ( ip, 1e6d, expected );

		assertTrue ( "a low tolerance finds at least as many maxima as a middle one", low >= middle );
		assertTrue ( "a middle tolerance finds at least as many maxima as a huge one", middle >= high );
		assertEquals ( "a tolerance above every peak finds nothing, so the mismatch is -expected",
				-expected, high, 0.0d );
	}
}
