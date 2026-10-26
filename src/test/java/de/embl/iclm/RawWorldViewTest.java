package de.embl.iclm;

import static org.junit.Assert.*;
import java.util.Random;
import org.junit.Test;
import net.imglib2.realtransform.AffineTransform3D;

public class RawWorldViewTest {

	// Independent statement of the required screen-right, screen-down and depth directions.
	static final double[][][] AXES = {
		{ { 1, 0, 0 }, { 0, 1, 0 }, { 0, 0, 1 } },
		{ { 1, 0, 0 }, { 0, 0, 1 }, { 0, -1, 0 } },
		{ { 0, 1, 0 }, { 0, 0, 1 }, { 1, 0, 0 } }
	};

	static void assertPlane(RawWorldView plane, AffineTransform3D view, double scale) {
		for (int i = 0; i < 3; i++)
			for (int j = 0; j < 3; j++) assertEquals(scale * AXES[plane.ordinal()][i][j], view.get(i, j), scale * 1e-10);
	}

	@Test public void planesPreserveAnchorZoomAndHandednessAcrossRotatedViews() {
		Random random = new Random(26102026);
		for (int n = 0; n < 1000; n++) {
			AffineTransform3D old = new AffineTransform3D();
			for (int axis = 0; axis < 3; axis++) old.rotate(axis, random.nextDouble() * 2 * Math.PI);
			double scale = Math.pow(10, random.nextDouble() * 6 - 3);
			old.scale(scale);
			old.translate(random.nextDouble() * 2000 - 1000, random.nextDouble() * 2000 - 1000, random.nextDouble() * 1000);
			double[] saved = old.getRowPackedCopy();
			int width = 17 + random.nextInt(4080), height = 17 + random.nextInt(4080);
			double[] centre = { width / 2.0, height / 2.0, 0 }, anchor = new double[3], screen = new double[3];
			old.inverse().apply(centre, anchor);
			AffineTransform3D sequence = old;
			for (RawWorldView plane : RawWorldView.values()) {
				AffineTransform3D next = plane.orient(old, width, height);
				assertPlane(plane, next, scale);
				next.apply(anchor, screen);
				assertArrayEquals(centre, screen, 1e-8);
				assertArrayEquals(next.getRowPackedCopy(), plane.orient(next, width, height).getRowPackedCopy(), 1e-8);
				sequence = plane.orient(sequence, width, height);
				assertArrayEquals(next.getRowPackedCopy(), sequence.getRowPackedCopy(), 1e-8);
				assertArrayEquals(saved, old.getRowPackedCopy(), 0);
			}
		}
	}

	@Test public void invalidCameraOrCanvasIsRefusedWithoutMutation() {
		AffineTransform3D identity = new AffineTransform3D();
		assertThrows(IllegalArgumentException.class, () -> RawWorldView.XZ.orient(identity, 0, 20));
		assertThrows(IllegalArgumentException.class, () -> RawWorldView.XZ.orient(identity, 20, -1));
		for (double[] a : new double[][] {
			{ 2, 0, 0, 10, 0, 1, 0, 20, 0, 0, 1, 30 },
			{ 1, .1, 0, 10, 0, 1, 0, 20, 0, 0, 1, 30 },
			{ -1, 0, 0, 10, 0, 1, 0, 20, 0, 0, 1, 30 },
			{ 1, 0, 0, Double.NaN, 0, 1, 0, 20, 0, 0, 1, 30 },
			{ 1, 0, 0, Double.POSITIVE_INFINITY, 0, 1, 0, 20, 0, 0, 1, 30 }
		}) {
			AffineTransform3D bad = new AffineTransform3D(); bad.set(a);
			assertThrows(IllegalArgumentException.class, () -> RawWorldView.YZ.orient(bad, 100, 100));
			assertArrayEquals(a, bad.getRowPackedCopy(), 0);
		}
	}
}
