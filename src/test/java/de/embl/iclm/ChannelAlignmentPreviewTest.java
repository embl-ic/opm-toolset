package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.JOptionPane;

import org.junit.Test;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.PointRoi;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

/** Display-state regressions for the separate channel windows and composite preview. */
public class ChannelAlignmentPreviewTest {

	@Test
	public void rightIsFlippedByDefaultButLeftAndWholeAreNot() {
		assertFalse(ChannelAlignment.defaultFlip(BeadAlignment.Side.LEFT));
		assertTrue(ChannelAlignment.defaultFlip(BeadAlignment.Side.RIGHT));
		assertFalse(ChannelAlignment.defaultFlip(BeadAlignment.Side.WHOLE));
	}

	@Test
	public void displayFlipIsRelativeToTheCanonicalMeasuredOrientation() {
		ByteProcessor canonical = new ByteProcessor(3, 1, new byte[] { 1, 2, 3 });

		ImageProcessor leftKept = ChannelAlignment.projectionForDisplay(
				canonical, BeadAlignment.Side.LEFT, false);
		ImageProcessor leftFlipped = ChannelAlignment.projectionForDisplay(
				canonical, BeadAlignment.Side.LEFT, true);
		ImageProcessor rightKept = ChannelAlignment.projectionForDisplay(
				canonical, BeadAlignment.Side.RIGHT, true);
		ImageProcessor rightFlippedBack = ChannelAlignment.projectionForDisplay(
				canonical, BeadAlignment.Side.RIGHT, false);

		assertPixels(new int[] { 1, 2, 3 }, leftKept);
		assertPixels(new int[] { 3, 2, 1 }, leftFlipped);
		assertPixels(new int[] { 1, 2, 3 }, rightKept);
		assertPixels(new int[] { 3, 2, 1 }, rightFlippedBack);
		assertPixels(new int[] { 1, 2, 3 }, canonical);
	}

	@Test
	public void beadPointUsesTheSameFlipThenAlignmentOrderAsItsImage() {
		double[][] matrix = { { 0, -1, 20 }, { 1, 0, 30 } };
		// x=2 becomes 7 on a width-10 horizontal flip, then the matrix maps (7,4).
		assertArrayEquals(new double[] { 16, 37 },
				ChannelAlignment.displayedPoint(2, 4, 10, true, matrix), 0.0);
		assertArrayEquals(new double[] { 2, 4 },
				ChannelAlignment.displayedPoint(2, 4, 10, false, null), 0.0);
	}

	@Test
	public void aDisplayFlipCarriesTheMeasuredMatrixIntoTheMirroredFrame() {
		/* The flip is a view state: the measured matrix stays canonical, and the overlay frame is
		 * the reference's displayed frame, so a mirrored view needs F * M * F, not M. Applying M
		 * to a mirrored plane put the channel out by twice the matrix's X translation. */
		double[][] measured = { { 1, 0, 3 }, { 0, 1, 2 } };
		double[][] both = ChannelAlignment.overlayMatrix(measured, true, true, 64);
		assertArrayEquals(new double[] { 1, 0, -3 }, both[0], 1e-12);
		assertArrayEquals(new double[] { 0, 1, 2 }, both[1], 1e-12);
		// Nothing flipped is the matrix itself, and the reference's own transform is the identity.
		assertTrue(ChannelAlignment.sameMatrix(measured,
				ChannelAlignment.overlayMatrix(measured, false, false, 64)));
		assertTrue(ChannelAlignment.sameMatrix(ChannelAlignment.identity2d(),
				ChannelAlignment.overlayMatrix(ChannelAlignment.identity2d(), true, true, 64)));
	}

	@Test
	public void aMirroredOverlayPutsTheMovingBeadWhereTheMirroredReferenceBeadIs() {
		/* 64 wide: the reference bead at 20 is drawn at 43 once mirrored, and the moving bead,
		 * which the matrix places at 23, belongs at 40 - not at 46, which is where applying the
		 * canonical matrix to the mirrored plane put it. */
		double[][] measured = { { 1, 0, 3 }, { 0, 1, 0 } };
		double[][] display = ChannelAlignment.overlayMatrix(measured, true, true, 64);
		double[] referenceBead = ChannelAlignment.displayedPoint(20, 5, 64, true,
				ChannelAlignment.overlayMatrix(ChannelAlignment.identity2d(), true, true, 64));
		double[] movingBead = ChannelAlignment.displayedPoint(20, 5, 64, true, display);
		assertArrayEquals(new double[] { 43, 5 }, referenceBead, 1e-12);
		assertArrayEquals(new double[] { 40, 5 }, movingBead, 1e-12);

		FloatProcessor canonical = new FloatProcessor(64, 11);
		canonical.setf(20, 5, 100);
		ImageProcessor mirrored = ChannelAlignment.projectionForDisplay(
				canonical, BeadAlignment.Side.LEFT, true);
		ImageProcessor drawn = SIFT.alignWithRigid2DMatrix(mirrored, display, false);
		assertEquals("the pixels land where the point says", 100f, drawn.getf(40, 5), 0f);
	}

	@Test
	public void aMirroredOverlayReversesTheSenseOfAMeasuredRotation() {
		double angle = Math.toRadians(7), cosine = Math.cos(angle), sine = Math.sin(angle);
		double[][] measured = { { cosine, -sine, 0 }, { sine, cosine, 0 } };
		double[][] display = ChannelAlignment.overlayMatrix(measured, true, true, 64);
		assertEquals(cosine, display[0][0], 1e-12);
		assertEquals(sine, display[0][1], 1e-12);
		assertEquals(-sine, display[1][0], 1e-12);
		assertEquals(cosine, display[1][1], 1e-12);
	}

	@Test
	public void displayedPointRoundTripsBackToCanonicalCoordinates() {
		double[][] matrix = { { 0, -1, 20 }, { 1, 0, 30 } };
		double[] displayed = ChannelAlignment.displayedPoint(2.25, 4.5, 10, true, matrix);
		assertArrayEquals(new double[] { 2.25, 4.5 },
				ChannelAlignment.canonicalPoint(displayed[0], displayed[1], 10, true, matrix), 1e-12);
	}

	@Test
	public void floatAutoContrastUsesTheRealIntensityUnits() {
		FloatProcessor image = new FloatProcessor(3, 2,
				new float[] { 1000, 1000, 2000, 3000, 4000, 5000 });
		double[] range = ChannelAlignment.automaticDisplayRange(image);
		assertTrue(range[0] >= 1000);
		assertTrue(range[1] > 4000);
	}

	@Test
	public void blankHalfBorrowsTheContrastOfItsPartnerFromTheSameFile() {
		File file = new File("beads_Channel0001.tif");
		List<ChannelAlignment.SourceChoice> sources = Arrays.asList(
				new ChannelAlignment.SourceChoice(file, BeadAlignment.Side.LEFT, 1),
				new ChannelAlignment.SourceChoice(file, BeadAlignment.Side.RIGHT, 1));
		LinkedHashMap<String, ImageProcessor> images = new LinkedHashMap<String, ImageProcessor>();
		images.put(sources.get(0).key(), new FloatProcessor(2, 2, new float[4]));
		images.put(sources.get(1).key(), new FloatProcessor(2, 2,
				new float[] { 100, 200, 300, 400 }));

		double[][] ranges = ChannelAlignment.previewDisplayRanges(sources, images);
		assertArrayEquals(ranges[1], ranges[0], 0.0);
		assertTrue(ranges[0][1] > ranges[0][0]);
	}

	@Test
	public void threeLabelledPointPairsRecoverAnExactRigidTransform() {
		List<double[]> moving = points(new double[][] { { 1, 2 }, { 5, 3 }, { 2, 8 }, { 9, 7 } });
		double angle = Math.toRadians(17), cosine = Math.cos(angle), sine = Math.sin(angle);
		List<double[]> reference = new ArrayList<double[]>();
		for (double[] point : moving) reference.add(new double[] {
				cosine * point[0] - sine * point[1] + 4.5,
				sine * point[0] + cosine * point[1] - 2.25 });
		ChannelAlignment.ManualFit fit = ChannelAlignment.fitManualRigid(reference, moving);
		assertArrayEquals(new double[] { cosine, -sine, 4.5 }, fit.matrix[0], 1e-12);
		assertArrayEquals(new double[] { sine, cosine, -2.25 }, fit.matrix[1], 1e-12);
		assertEquals(4, fit.matches);
		assertEquals(0, fit.rmsPixels, 1e-12);
		assertNull(ChannelAlignment.fitManualRigid(reference.subList(0, 2), moving.subList(0, 2)));
	}

	@Test
	public void manualRotationKeepsTheGeometricImageCentreFixed() {
		double[][] adjusted = ChannelAlignment.manualAdjustment(ChannelAlignment.identity2d(),
				new double[] { 0, 0, 90 }, 101, 81);
		double[] centre = ChannelAlignment.displayedPoint(50, 40, 101, false, adjusted);
		assertArrayEquals(new double[] { 50, 40 }, centre, 1e-12);
		// Image Y increases downward: positive 90 degrees moves a point on the right upward.
		double[] right = ChannelAlignment.displayedPoint(60, 40, 101, false, adjusted);
		assertArrayEquals(new double[] { 50, 30 }, right, 1e-12);
	}

	@Test
	public void manualAdjustmentIsComposedOntoTheComputedMatrixNotAccumulated() {
		double[][] computed = { { 1, 0, 4 }, { 0, 1, -3 } };
		double[] values = { 1.5, -2.5, 0 };
		double[][] first = ChannelAlignment.manualAdjustment(computed, values, 101, 81);
		double[][] second = ChannelAlignment.manualAdjustment(computed, values, 101, 81);
		assertArrayEquals(new double[] { 1, 0, 5.5 }, first[0], 0.0);
		assertArrayEquals(new double[] { 0, 1, -5.5 }, first[1], 0.0);
		assertArrayEquals(first[0], second[0], 0.0);
		assertArrayEquals(first[1], second[1], 0.0);
	}

	@Test
	public void movingReferenceRightReframesEveryStoredChannelLeft() {
		double[][] computed = { { 1, 0, 4 }, { 0, 1, -3 } };
		double[][] adjusted = ChannelAlignment.reframedManualAdjustment(computed,
				new double[] { 1, 0, 0 }, new double[] { 0, 0, 0 }, 101, 81);
		assertArrayEquals(new double[] { 1, 0, 3 }, adjusted[0], 1e-12);
		assertArrayEquals(new double[] { 0, 1, -3 }, adjusted[1], 1e-12);
	}

	@Test
	public void equalReferenceAndChannelCorrectionsCancelInStoredCoordinates() {
		double[][] computed = { { 1, 0, 4 }, { 0, 1, -3 } };
		double[] correction = { 1.5, -2.5, 7 };
		double[][] adjusted = ChannelAlignment.reframedManualAdjustment(computed,
				correction, correction, 101, 81);
		assertArrayEquals(computed[0], adjusted[0], 1e-12);
		assertArrayEquals(computed[1], adjusted[1], 1e-12);
	}

	@Test
	public void aPlaneReplacedInAnOpenOverlayIsThePlaneItDraws() {
		/* The manual X/Y/rotation never moved the overlay: the plane was swapped in with
		 * ImageStack.setProcessor, and the composite kept drawing the array it was built with.
		 * With the replaced channel current, cycling channels even put the old plane back. */
		for (int mode : new int[] { CompositeImage.COMPOSITE, CompositeImage.COLOR }) {
			for (int current = 1; current <= 2; current++) {
				CompositeImage overlay = twoChannelOverlay(mode);
				overlay.setC(mode == CompositeImage.COLOR ? 2 : current);
				assertEquals(0, brightestColumn(overlay));

				ChannelAlignment.replacePlane(overlay, 2,
						new FloatProcessor(4, 1, new float[] { 0, 0, 0, 100 }));
				assertEquals("mode " + mode + ", channel " + current + " current", 3, brightestColumn(overlay));

				overlay.setC(1); overlay.setC(2); overlay.setC(mode == CompositeImage.COLOR ? 2 : current);
				assertEquals("after a channel cycle", 3, brightestColumn(overlay));
				assertEquals(100f, ((float[]) overlay.getImageStack().getPixels(2))[3], 0f);
			}
		}
	}

	@Test
	public void withManualAdjustmentOffOrResetAChannelKeepsItsTransformBeforeAdjustment() {
		double[][] fitted = { { 0.99, -0.14, 4.25 }, { 0.14, 0.99, -3.5 } };
		// Unticked: the rows are set aside, not applied.
		assertTrue(ChannelAlignment.sameMatrix(fitted,
				ChannelAlignment.reframedManualAdjustment(fitted, null, null, 101, 81)));
		// Reset: zeroed rows give back exactly the transform they started from.
		assertTrue(ChannelAlignment.sameMatrix(fitted, ChannelAlignment.reframedManualAdjustment(
				fitted, new double[3], new double[3], 101, 81)));
		// After "remove all" that transform is the identity, so a row is the whole transform.
		double[][] manualOnly = ChannelAlignment.reframedManualAdjustment(ChannelAlignment.identity2d(),
				new double[3], new double[] { 2, -1, 0 }, 101, 81);
		assertArrayEquals(new double[] { 1, 0, 2 }, manualOnly[0], 1e-12);
		assertArrayEquals(new double[] { 0, 1, -1 }, manualOnly[1], 1e-12);
	}

	@Test
	public void matricesAreComparedByValue() {
		double[][] identity = ChannelAlignment.identity2d();
		assertTrue(ChannelAlignment.sameMatrix(identity, new double[][] { { 1, -0.0, -0.0 }, { 0, 1, 0 } }));
		assertFalse(ChannelAlignment.sameMatrix(identity, new double[][] { { 1, 0, 0.1 }, { 0, 1, 0 } }));
		assertFalse(ChannelAlignment.sameMatrix(identity, null));
	}

	@Test
	public void anInterestPointLeavesItsBeadVisibleAndOverlappingChannelsBlend() {
		/* HYBRID painted an opaque white cross and a filled square over the bead, and the channel
		 * drawn last hid every marker under it. */
		BufferedImage canvas = new BufferedImage(21, 21, BufferedImage.TYPE_INT_RGB);
		PointRoi first = new PointRoi(new float[] { 10 }, new float[] { 10 }, 1);
		PointRoi second = new PointRoi(new float[] { 10 }, new float[] { 10 }, 1);
		ChannelAlignment.stylePoints(first, Color.RED, true);
		ChannelAlignment.stylePoints(second, Color.GREEN, true);
		ImagePlus windowless = new ImagePlus("beads", new ByteProcessor(21, 21));	// PointRoi.draw needs one
		first.setImage(windowless);
		second.setImage(windowless);
		Graphics2D graphics = canvas.createGraphics();
		first.drawOverlay(graphics);
		int brightestRed = 0;
		for (int y = 0; y < 21; y++) for (int x = 0; x < 21; x++)
			brightestRed = Math.max(brightestRed, (canvas.getRGB(x, y) >> 16) & 0xff);
		second.drawOverlay(graphics);
		graphics.dispose();

		assertEquals("the bead under the point is not painted", 0, canvas.getRGB(10, 10) & 0xffffff);
		assertTrue("the marker is drawn", brightestRed > 0);
		assertTrue("the marker is translucent", brightestRed <= ChannelAlignment.POINT_ALPHA + 1);
		int x = -1, y = -1, greenest = -1;
		for (int row = 0; row < 21; row++) for (int column = 0; column < 21; column++) {
			int green = (canvas.getRGB(column, row) >> 8) & 0xff;
			if (green > greenest) { greenest = green; x = column; y = row; }
		}
		assertTrue("the channel underneath still shows where the top one covers it most",
				((canvas.getRGB(x, y) >> 16) & 0xff) > 0);
	}

	@Test
	public void theLabelCheckboxDecidesWhetherPointsAreNumbered() {
		PointRoi points = new PointRoi(new float[] { 1, 5 }, new float[] { 1, 5 }, 2);
		ChannelAlignment.stylePoints(points, Color.RED, false);
		assertFalse(points.getShowLabels());
		ChannelAlignment.stylePoints(points, Color.RED, true);
		assertTrue(points.getShowLabels());
	}

	@Test
	public void theHelpIsAboutAThirdToAHalfWiderThanItsOldHandWrappedLines() {
		/* It was wrapped by hand at 108 characters at most, and read thin and long. Measured in the
		 * real dialog: 683 px wide before, 943 px after. */
		JOptionPane help = ChannelAlignment.Dialog.helpPane(ChannelAlignment.Dialog.HELP_LINE_LENGTH);
		JOptionPane handWrapped = ChannelAlignment.Dialog.helpPane(108);
		double ratio = help.getPreferredSize().width / (double) handWrapped.getPreferredSize().width;
		assertTrue("width ratio " + ratio, ratio >= 1.3 && ratio <= 1.5);
		int longest = 0;
		for (JLabel line : helpLines(help, new ArrayList<JLabel>())) longest = Math.max(longest, line.getText().length());
		assertTrue(longest <= ChannelAlignment.Dialog.HELP_LINE_LENGTH);
		assertTrue("the lines use the width", longest > ChannelAlignment.Dialog.HELP_LINE_LENGTH - 20);
	}

	private static List<JLabel> helpLines(Container container, List<JLabel> lines) {
		for (Component child : container.getComponents()) {
			if (child instanceof JLabel && "OptionPane.label".equals(child.getName())) lines.add((JLabel) child);
			if (child instanceof Container) helpLines((Container) child, lines);
		}
		return lines;
	}

	/** Channel 1 blank, channel 2 one bright pixel at x = 0, both ranged 0-100. */
	private static CompositeImage twoChannelOverlay(int mode) {
		ImageStack stack = new ImageStack(4, 1);
		stack.addSlice("c1", new FloatProcessor(4, 1, new float[4]));
		stack.addSlice("c2", new FloatProcessor(4, 1, new float[] { 100, 0, 0, 0 }));
		ImagePlus base = new ImagePlus("overlay", stack);
		base.setDimensions(2, 1, 1);
		base.setOpenAsHyperStack(true);
		CompositeImage overlay = new CompositeImage(base, mode);
		for (int channel = 1; channel <= 2; channel++) {
			overlay.setC(channel);
			overlay.setDisplayRange(0, 100);
		}
		return overlay;
	}

	/** The column the overlay draws brightest: what a user sees, not what the stack holds. */
	private static int brightestColumn(CompositeImage overlay) {
		overlay.updateAllChannelsAndDraw();
		ColorProcessor drawn = new ColorProcessor(overlay.getImage());
		int best = -1, brightest = -1;
		for (int x = 0; x < drawn.getWidth(); x++) {
			int[] rgb = drawn.getPixel(x, 0, null);
			int value = rgb[0] + rgb[1] + rgb[2];
			if (value > brightest) { brightest = value; best = x; }
		}
		return best;
	}

	private static List<double[]> points(double[][] points) {
		return new ArrayList<double[]>(Arrays.asList(points));
	}

	private static void assertPixels(int[] expected, ImageProcessor actual) {
		assertEquals(expected.length, actual.getWidth());
		for (int x = 0; x < expected.length; x++) assertEquals(expected[x], actual.get(x, 0));
	}
}
