package de.embl.iclm;

import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import bdv.viewer.Interpolation;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * Joint contract C4.1: the cells a view reads, worked out analytically, never miss a cell that any
 * screen pixel's samples read - checked pixel by pixel against brute force - for views down the
 * deskewed Z, from the side and the end, oblique, zoomed in and out; both signs of the OPM angle;
 * the left half and the mirrored, aligned right half; an odd width and a short last strip; nearest
 * and trilinear sampling. Where a screen pixel is no larger than a raw voxel the analytic set is
 * also tight: it adds little that no sample reads.
 */
public class RawCoverageTest {

	private static final int W = 51, H = 37, D = 23, ROWS = 8, SCREEN_W = 120, SCREEN_H = 90;
	private static final int STRIPS = (H + ROWS - 1) / ROWS;

	/** Cells read by sampling every screen pixel, as the interpolators do. */
	private static BitSet bruteForce(List<RawCoverage.Part> parts, Interpolation method) {
		BitSet cells = new BitSet();
		double[] screen = new double[3], s = new double[3];
		for (RawCoverage.Part part : parts) {
			AffineTransform3D toSource = part.sourceToScreen.inverse();
			long w = part.spec.cropWidth(W);
			for (int v = 0; v < SCREEN_H; v++)
				for (int u = 0; u < SCREEN_W; u++) {
					screen[0] = u;
					screen[1] = v;
					toSource.apply(screen, s);
					long[][] reads = new long[3][];
					for (int d = 0; d < 3; d++)
						reads[d] = method == Interpolation.NLINEAR
								? new long[] { (long) Math.floor(s[d]), (long) Math.floor(s[d]) + 1 }
								: new long[] { (long) Math.floor(s[d] + 0.5) };
					for (long x : reads[0])
						for (long y : reads[1])
							for (long z : reads[2])
								if (x >= 0 && x < w && y >= 0 && y < H && z >= 0 && z < D) cells.set((int) (y / ROWS + STRIPS * z));
				}
		}
		return cells;
	}

	/** The view's screen transform: world to screen, looking through the volume's middle. */
	private static AffineTransform3D view(RawGeometry g, RawSourceSpec spec, double[] axis, double angle, double zoom,
			double depthFraction) {
		AffineTransform3D toWorld = g.sourceToWorld(new long[] { W, H, D }, spec, null);
		double[] min = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
		double[] max = { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
		double[] corner = new double[3], world = new double[3];
		for (int c = 0; c < 8; c++) {
			corner[0] = (c & 1) == 0 ? 0 : spec.cropWidth(W) - 1;
			corner[1] = (c & 2) == 0 ? 0 : H - 1;
			corner[2] = (c & 4) == 0 ? 0 : D - 1;
			toWorld.apply(corner, world);
			for (int d = 0; d < 3; d++) {
				min[d] = Math.min(min[d], world[d]);
				max[d] = Math.max(max[d], world[d]);
			}
		}
		AffineTransform3D view = new AffineTransform3D();
		view.translate(-0.5 * (min[0] + max[0]), -0.5 * (min[1] + max[1]), -(min[2] + depthFraction * (max[2] - min[2])));
		if (axis != null) view.rotate(axis[0] != 0 ? 0 : axis[1] != 0 ? 1 : 2, angle);
		view.scale(zoom * 0.9 * SCREEN_W / Math.max(max[0] - min[0], Math.max(max[1] - min[1], max[2] - min[2])));
		view.translate(SCREEN_W / 2.0, SCREEN_H / 2.0, 0);
		AffineTransform3D toScreen = toWorld.copy();
		toScreen.preConcatenate(view);
		return toScreen;
	}

	@Test
	public void theAnalyticCellsContainEveryCellAnySampleReads() {
		double[][] matrix = { { Math.cos(0.0045), -Math.sin(0.0045), 1.7 }, { Math.sin(0.0045), Math.cos(0.0045), -2.3 } };
		int checked = 0;
		for (double angle : new double[] { 33.5, -25 }) {
			RawGeometry g = new RawGeometry(0.265, 0.116, angle, "coverage test");
			RawSourceSpec left = RawSourceSpec.half(null, 1, true, false, W, 0);
			RawSourceSpec right = RawSourceSpec.half(AlignmentMatrixSet.legacy(matrix), 1, false, false, W, 0);
			Object[][] views = {		// axis, angle, zoom, depth
				{ null, 0.0, 1.0, 0.5 }, { null, 0.0, 1.0, 0.1 }, { new double[] { 1, 0, 0 }, -Math.PI / 2, 1.0, 0.5 },
				{ new double[] { 0, 1, 0 }, Math.PI / 2, 1.0, 0.5 }, { new double[] { 1, 0, 0 }, 0.6, 1.3, 0.4 },
				{ new double[] { 0, 0, 1 }, 0.4, 6.0, 0.5 }, { null, 0.0, 0.2, 0.5 } };
			for (Object[] v : views)
				for (Interpolation method : Interpolation.values())
					for (List<RawCoverage.Part> parts : Arrays.asList(
							Collections.singletonList(new RawCoverage.Part(left, view(g, left, (double[]) v[0], (Double) v[1], (Double) v[2], (Double) v[3]))),
							Arrays.asList(new RawCoverage.Part(left, view(g, left, (double[]) v[0], (Double) v[1], (Double) v[2], (Double) v[3])),
									new RawCoverage.Part(right, view(g, left, (double[]) v[0], (Double) v[1], (Double) v[2], (Double) v[3])
											.concatenate(g.sourceToWorld(new long[] { W, H, D }, left, null).inverse())
											.concatenate(g.sourceToWorld(new long[] { W, H, D }, right, null)))))) {
						BitSet analytic = RawCoverage.cells(new long[] { W, H, D }, ROWS, parts, SCREEN_W, SCREEN_H, method);
						BitSet brute = bruteForce(parts, method);
						BitSet missed = (BitSet) brute.clone();
						missed.andNot(analytic);
						String what = String.format("angle %.1f, view %s, %s, %d part(s)", angle, Arrays.deepToString(v), method,
								parts.size());
						assertTrue(what + ": cells read but not covered: " + missed, missed.isEmpty());
						boolean pixelsNoLargerThanVoxels = (Double) v[2] >= 1.0;
						if (pixelsNoLargerThanVoxels && brute.cardinality() > 0)
							assertTrue(what + ": " + analytic.cardinality() + " cells covered for " + brute.cardinality() + " read",
									analytic.cardinality() <= 1.25 * brute.cardinality() + 4);
						checked++;
					}
		}
		assertTrue(checked == 2 * 7 * 2 * 2);
	}

	/** A view that misses the volume reads nothing, and the analytic set says so. */
	@Test
	public void aViewBesideTheVolumeCoversNothing() {
		RawGeometry g = new RawGeometry(0.265, 0.116, 33.5, "coverage test");
		RawSourceSpec left = RawSourceSpec.half(null, 1, true, false, W, 0);
		AffineTransform3D toScreen = view(g, left, null, 0, 1, 0.5);
		toScreen.preConcatenate(new AffineTransform3D() {
			{
				translate(10 * SCREEN_W, 0, 0);
			}
		});
		List<RawCoverage.Part> parts = Collections.singletonList(new RawCoverage.Part(left, toScreen));
		assertTrue(RawCoverage.cells(new long[] { W, H, D }, ROWS, parts, SCREEN_W, SCREEN_H, Interpolation.NLINEAR).isEmpty());
		assertTrue(bruteForce(parts, Interpolation.NLINEAR).isEmpty());
	}
}
