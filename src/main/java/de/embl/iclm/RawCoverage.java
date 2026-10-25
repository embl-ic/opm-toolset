package de.embl.iclm;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import bdv.viewer.Interpolation;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * The raw cells a view can read, worked out from the geometry rather than by sampling it (joint
 * contract C4.1). Completed-frame playback pins these cells before a frame is drawn, so the frame
 * finds every value it needs in memory.
 *
 * <p>A view draws the screen plane z = 0. Each screen pixel {@code (u, v)} is sampled at the raw
 * position {@code A^-1 (u, v, 0)}, which is affine in {@code (u, v)}. The sample reads the lattice
 * points its interpolator touches: the nearest one, or both neighbours along every axis for
 * trilinear. So raw plane {@code k} is read by exactly the screen pixels whose raw z lies in a slab
 * around {@code k} - and whose raw x and y lie within reach of the part - which, clipped to the
 * screen, is a convex polygon. The raw row is affine too, so its extremes over that polygon are at
 * the polygon's vertices; the rows between them, widened by the interpolation's reach, give the
 * strips of plane {@code k}. Exact up to a rounding margin, never smaller than what the samples
 * read, which the tests check against every pixel of the screen.
 *
 * <p>The cells are indexed as the raw store's grid indexes them: full-width strips, so a cell is
 * {@code strip + strips * plane}. Both camera halves read the same cells, and their sets are joined.
 */
final class RawCoverage {

	/** Rounding margin, in raw voxels, so that a sample exactly on a boundary is never left out. */
	private static final double EPSILON = 1e-6;

	private RawCoverage() {}

	/** One displayed part and where its raw voxels land on the screen. */
	static final class Part {
		final RawSourceSpec spec;
		final AffineTransform3D sourceToScreen;

		Part(RawSourceSpec spec, AffineTransform3D sourceToScreen) {
			this.spec = spec;
			this.sourceToScreen = sourceToScreen.copy();
		}
	}

	/**
	 * The cells of one file version that a screen of {@code width x height} pixels reads through
	 * these parts.
	 *
	 * @param fileDims		: raw width, height and planes of the file
	 * @param rowsPerStrip	: rows per cell, the TIFF's strip
	 */
	static BitSet cells(long[] fileDims, int rowsPerStrip, List<Part> parts, int width, int height, Interpolation method) {
		int planes = (int) fileDims[2], rows = (int) fileDims[1];
		int strips = (rows + rowsPerStrip - 1) / rowsPerStrip;
		boolean trilinear = method == Interpolation.NLINEAR;
		// a coordinate s reads lattice points floor(s) and floor(s)+1 (trilinear) or floor(s + 0.5)
		double below = trilinear ? 1 : 0.5, above = trilinear ? 1 : 0.5;
		BitSet cells = new BitSet(strips * planes);
		for (Part part : parts) {
			AffineTransform3D toSource = part.sourceToScreen.inverse();
			// source coordinate d of screen (u, v, 0) = a[d] u + b[d] v + c[d]
			double[] a = new double[3], b = new double[3], c = new double[3];
			for (int d = 0; d < 3; d++) {
				a[d] = toSource.get(d, 0);
				b[d] = toSource.get(d, 1);
				c[d] = toSource.get(d, 3);
			}
			long partWidth = part.spec.cropWidth(fileDims[0]);
			// the screen's samples are at whole pixels 0..width-1, 0..height-1
			List<double[]> screen = new ArrayList<double[]>();
			screen.add(new double[] { 0, 0 });
			screen.add(new double[] { width - 1, 0 });
			screen.add(new double[] { width - 1, height - 1 });
			screen.add(new double[] { 0, height - 1 });
			// only samples that can reach a voxel of the part read a cell at all
			List<double[]> reach = clip(screen, a[0], b[0], c[0] + below - 0 + EPSILON);				// x > -below
			reach = clip(reach, -a[0], -b[0], -c[0] + (partWidth - 1) + above + EPSILON);				// x < w - 1 + above
			reach = clip(reach, a[1], b[1], c[1] + below + EPSILON);
			reach = clip(reach, -a[1], -b[1], -c[1] + (rows - 1) + above + EPSILON);
			if (reach.isEmpty()) continue;
			for (int k = 0; k < planes; k++) {
				// plane k is read by samples with k - above < z < k + below
				List<double[]> slab = clip(reach, a[2], b[2], c[2] - (k - above) + EPSILON);
				slab = clip(slab, -a[2], -b[2], -c[2] + (k + below) + EPSILON);
				if (slab.isEmpty()) continue;
				double yMin = Double.MAX_VALUE, yMax = -Double.MAX_VALUE;
				for (double[] p : slab) {
					double y = a[1] * p[0] + b[1] * p[1] + c[1];
					yMin = Math.min(yMin, y);
					yMax = Math.max(yMax, y);
				}
				int first = (int) Math.max(0, Math.floor(yMin - (trilinear ? 0 : -0.5) - EPSILON));
				int last = (int) Math.min(rows - 1, Math.floor(yMax + (trilinear ? 1 : 0.5) + EPSILON));
				for (int s = first / rowsPerStrip; s <= last / rowsPerStrip; s++) cells.set(s + strips * k);
			}
		}
		return cells;
	}

	/**
	 * Clip a convex polygon in screen coordinates to the half-plane {@code a u + b v + c >= 0}
	 * (Sutherland-Hodgman, one edge).
	 */
	static List<double[]> clip(List<double[]> polygon, double a, double b, double c) {
		List<double[]> out = new ArrayList<double[]>();
		int n = polygon.size();
		for (int i = 0; i < n; i++) {
			double[] p = polygon.get(i), q = polygon.get((i + 1) % n);
			double fp = a * p[0] + b * p[1] + c, fq = a * q[0] + b * q[1] + c;
			if (fp >= 0) out.add(p);
			if ((fp >= 0) != (fq >= 0)) {
				double t = fp / (fp - fq);
				out.add(new double[] { p[0] + t * (q[0] - p[0]), p[1] + t * (q[1] - p[1]) });
			}
		}
		return out;
	}
}
