package de.embl.iclm;

import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.plugin.filter.GaussianBlur;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;

/** Bead-specific detection and rigid registration used by the file-driven alignment tool. */
public final class BeadAlignment {

	public enum Side { LEFT, RIGHT, WHOLE }

	/** One sub-pixel bead centre and its scale-space response. */
	public static final class Spot {
		public final double x, y, response;
		Spot(double x, double y, double response) {
			this.x = x; this.y = y; this.response = response;
		}
	}

	public static final class Result {
		public final double[][] matrix;
		public final List<Spot> referenceSpots;
		public final List<Spot> movingSpots;
		public final int matches;
		public final double rmsPixels;
		public final String method;

		Result(double[][] matrix, List<Spot> referenceSpots, List<Spot> movingSpots,
				int matches, double rmsPixels, String method) {
			this.matrix = matrix;
			this.referenceSpots = referenceSpots;
			this.movingSpots = movingSpots;
			this.matches = matches;
			this.rmsPixels = rmsPixels;
			this.method = method;
		}
	}

	private BeadAlignment() { }

	/**
	 * Robust coarse registration followed by bead-centroid refinement. Bounded DoG image
	 * correlation is the fast default for the small expected microscope offset; SIFT remains
	 * a fallback for a harder field. The final matrix is always fitted from sub-pixel LoG centres.
	 */
	public static Result align(ImageProcessor reference, ImageProcessor moving) {
		if (reference == null || moving == null) return null;
		if (reference.getWidth() != moving.getWidth() || reference.getHeight() != moving.getHeight())
			throw new IllegalArgumentException("Alignment projections must have the same XY dimensions.");
		List<Spot> fixed = detect(reference, recommendedSigma(reference));
		List<Spot> mobile = detect(moving, recommendedSigma(moving));
		if (fixed.size() < 3 || mobile.size() < 3) return null;

		String method = "DoG correlation + LoG sub-pixel refinement";
		Fit fit = refine(fixed, mobile, coarseImageTranslation(reference, moving, 80, 35));
		if (fit == null) {
			double[][] sift = null;
			try { sift = SIFT.computeAlignMatrix(reference.duplicate(), moving.duplicate(), false); }
			catch (Throwable ignored) { }
			if (plausible(sift)) {
				fit = refine(fixed, mobile, sift);
				method = "SIFT coarse + LoG sub-pixel refinement (correlation fallback)";
			}
		}
		if (fit == null) {
			fit = refine(fixed, mobile, coarseTranslation(fixed, mobile, 80, 35));
			method = "LoG sub-pixel centres + displacement consensus fallback";
		}
		if (fit == null) return null;
		return new Result(fit.matrix, fixed, mobile, fit.matches.size(), fit.rms, method);
	}

	/** SIFT-only result, retained for the method survey and a deterministic fallback. */
	public static Result siftOnly(ImageProcessor reference, ImageProcessor moving) {
		double[][] matrix = SIFT.computeAlignMatrix(reference.duplicate(), moving.duplicate(), false);
		if (!plausible(matrix)) return null;
		return new Result(matrix, Collections.<Spot>emptyList(), Collections.<Spot>emptyList(),
				0, Double.NaN, "SIFT + RANSAC");
	}

	/** LoG-only result, useful for validation against the hybrid default. */
	public static Result logOnly(ImageProcessor reference, ImageProcessor moving) {
		List<Spot> fixed = detect(reference, recommendedSigma(reference));
		List<Spot> mobile = detect(moving, recommendedSigma(moving));
		if (fixed.size() < 3 || mobile.size() < 3) return null;
		Fit fit = refine(fixed, mobile, coarseImageTranslation(reference, moving, 80, 35));
		if (fit == null) fit = refine(fixed, mobile, coarseTranslation(fixed, mobile, 80, 35));
		return fit == null ? null : new Result(fit.matrix, fixed, mobile, fit.matches.size(),
				fit.rms, "LoG sub-pixel centres + displacement consensus");
	}

	/** Score an externally estimated matrix against the same sub-pixel bead centres. */
	public static Result evaluate(ImageProcessor reference, ImageProcessor moving,
			double[][] matrix, String method) {
		if (!AlignmentMatrixSet.is2d(matrix)) return null;
		List<Spot> fixed = detect(reference, recommendedSigma(reference));
		List<Spot> mobile = detect(moving, recommendedSigma(moving));
		List<PointMatch> matches = mutualMatches(fixed, mobile, matrix, 3.0);
		if (matches.isEmpty()) return new Result(matrix, fixed, mobile, 0, Double.NaN, method);
		double sum = 0;
		for (PointMatch match : matches) {
			double[] source = match.getP1().getL(), target = match.getP2().getL();
			double x = matrix[0][0] * source[0] + matrix[0][1] * source[1] + matrix[0][2];
			double y = matrix[1][0] * source[0] + matrix[1][1] * source[1] + matrix[1][2];
			double dx = x - target[0], dy = y - target[1];
			sum += dx * dx + dy * dy;
		}
		return new Result(matrix, fixed, mobile, matches.size(), Math.sqrt(sum / matches.size()), method);
	}

	/**
	 * Detect bright, approximately diffraction-limited beads with a two-scale DoG (a close LoG
	 * approximation), robust response thresholding, non-maximum suppression and a quadratic
	 * sub-pixel peak.  The values are intentionally fixed here instead of exposed in the UI.
	 */
	public static List<Spot> detect(ImageProcessor input, double sigma) {
		FloatProcessor small = (FloatProcessor) input.convertToFloatProcessor();
		FloatProcessor large = (FloatProcessor) small.duplicate();
		GaussianBlur blur = new GaussianBlur();
		blur.blurGaussian(small, sigma, sigma, 0.01);
		blur.blurGaussian(large, sigma * 1.6, sigma * 1.6, 0.01);
		float[] response = (float[]) small.getPixels();
		float[] surround = (float[]) large.getPixels();
		for (int i = 0; i < response.length; i++) response[i] -= surround[i];

		double[] robust = medianMad(response);
		double threshold = robust[0] + Math.max(6.0 * robust[1], 1.0);
		int width = input.getWidth(), height = input.getHeight();
		int border = Math.max(3, (int) Math.ceil(3 * sigma));
		List<Spot> candidates = new ArrayList<Spot>();
		for (int y = border; y < height - border; y++) {
			int row = y * width;
			for (int x = border; x < width - border; x++) {
				int index = row + x;
				float centre = response[index];
				if (centre <= threshold || !localMaximum(response, index, width)) continue;
				double dx = parabola(response[index - 1], centre, response[index + 1]);
				double dy = parabola(response[index - width], centre, response[index + width]);
				candidates.add(new Spot(x + dx, y + dy, centre));
			}
		}
		Collections.sort(candidates, new Comparator<Spot>() {
			@Override public int compare(Spot a, Spot b) { return Double.compare(b.response, a.response); }
		});
		List<Spot> kept = new ArrayList<Spot>();
		double minDistance2 = Math.max(9.0, 4.0 * sigma * sigma);
		for (Spot candidate : candidates) {
			boolean near = false;
			for (Spot spot : kept) {
				double dx = spot.x - candidate.x, dy = spot.y - candidate.y;
				if (dx * dx + dy * dy < minDistance2) { near = true; break; }
			}
			if (!near) kept.add(candidate);
			if (kept.size() >= 2000) break;
		}
		return kept;
	}

	/** The supplied 200-nm bead data measures close to 2-3 pixels sigma after deskew. */
	static double recommendedSigma(ImageProcessor input) { return 2.0; }

	/**
	 * Stream a raw OPM TIFF once and make every requested deskewed Z-maximum projection.
	 * Only one raw plane is resident.  Right is mirrored in X here, establishing the saved
	 * matrix convention before either SIFT or bead localization sees it.
	 */
	public static Map<Side, ImageProcessor> deskewedMax(
			File file, Iterable<Side> wanted, double xyPixelSize, double zStepSize,
			double opmAngle) throws IOException {
		if (file == null || !file.isFile()) throw new IOException("TIFF does not exist: " + file);
		FastTiffReader.Info info = FastTiffReader.parse(file);
		EnumMap<Side, float[]> pixels = new EnumMap<Side, float[]>(Side.class);
		int halfWidth = (info.width + 1) / 2;
		double cos = Math.cos(Math.toRadians(opmAngle));
		double step = zStepSize / xyPixelSize;
		double[] corners = { 0, cos * (info.height - 1), step * (info.depth() - 1),
				cos * (info.height - 1) + step * (info.depth() - 1) };
		double minY = corners[0], maxY = corners[0];
		for (double value : corners) { minY = Math.min(minY, value); maxY = Math.max(maxY, value); }
		int outputHeight = Math.max(1, (int) Math.ceil(maxY - minY) + 1);
		for (Side side : wanted) {
			if (side != null && !pixels.containsKey(side)) {
				int width = side == Side.WHOLE ? info.width : halfWidth;
				pixels.put(side, new float[Math.multiplyExact(width, outputHeight)]);
			}
		}
		FastTiffReader.PlaneReader reader = new FastTiffReader.PlaneReader(file, info);
		try {
			for (int z = 0; z < info.depth(); z++) {
				short[] plane = reader.readPixels(z);
				for (int y = 0; y < info.height; y++) {
					int outputY = (int) Math.round(cos * y + step * z - minY);
					if (outputY < 0 || outputY >= outputHeight) continue;
					int sourceRow = y * info.width;
					for (Map.Entry<Side, float[]> entry : pixels.entrySet()) {
						Side side = entry.getKey();
						float[] destination = entry.getValue();
						int width = side == Side.WHOLE ? info.width : halfWidth;
						int sourceX = side == Side.RIGHT ? info.width - halfWidth : 0;
						int destinationRow = outputY * width;
						for (int x = 0; x < width; x++) {
							int value = plane[sourceRow + sourceX + x] & 0xffff;
							int targetX = side == Side.RIGHT ? width - 1 - x : x;
							int index = destinationRow + targetX;
							if (value > destination[index]) destination[index] = value;
						}
					}
				}
			}
		} finally { reader.close(); }
		EnumMap<Side, ImageProcessor> result = new EnumMap<Side, ImageProcessor>(Side.class);
		for (Map.Entry<Side, float[]> entry : pixels.entrySet()) {
			int width = entry.getKey() == Side.WHOLE ? info.width : halfWidth;
			result.put(entry.getKey(), new FloatProcessor(width, outputHeight, entry.getValue()));
		}
		return result;
	}

	private static Fit refine(List<Spot> fixed, List<Spot> mobile, double[][] initial) {
		if (!AlignmentMatrixSet.is2d(initial)) return null;
		double[][] matrix = AlignmentMatrixSet.copy(initial);
		Fit fit = null;
		for (int pass = 0; pass < 3; pass++) {
			double radius = pass == 0 ? 8.0 : (pass == 1 ? 4.0 : 2.5);
			List<PointMatch> matches = mutualMatches(fixed, mobile, matrix, radius);
			fit = robustFit(matches, pass == 0 ? 2.0 : 1.25);
			if (fit == null) break;
			matrix = fit.matrix;
		}
		return fit;
	}

	private static final class Fit {
		final double[][] matrix;
		final List<PointMatch> matches;
		final double rms;
		Fit(double[][] matrix, List<PointMatch> matches, double rms) {
			this.matrix = matrix; this.matches = matches; this.rms = rms;
		}
	}

	private static Fit robustFit(List<PointMatch> matches, double epsilon) {
		if (matches.size() < 3) return null;
		AbstractAffineModel2D<?> model = new RigidModel2D();
		ArrayList<PointMatch> inliers = new ArrayList<PointMatch>();
		try {
			if (!model.filterRansac(matches, inliers, 2000, epsilon, 0.20, 3)) return null;
		} catch (NotEnoughDataPointsException error) { return null; }
		double[][] matrix = new double[2][3];
		model.toMatrix(matrix);
		double sum = 0;
		for (PointMatch match : inliers) {
			double[] source = match.getP1().getL();
			double[] target = match.getP2().getL();
			double x = matrix[0][0] * source[0] + matrix[0][1] * source[1] + matrix[0][2];
			double y = matrix[1][0] * source[0] + matrix[1][1] * source[1] + matrix[1][2];
			double dx = x - target[0], dy = y - target[1];
			sum += dx * dx + dy * dy;
		}
		return new Fit(matrix, inliers, Math.sqrt(sum / Math.max(1, inliers.size())));
	}

	private static List<PointMatch> mutualMatches(List<Spot> fixed, List<Spot> mobile,
			double[][] matrix, double radius) {
		double limit = radius * radius;
		/* A dim spectral channel can have 50 trustworthy spots while the bright reference has
		 * thousands of lower-ranked maxima.  Compare equally credible prefixes; otherwise a
		 * false reference maximum often sits closer than the real bead. */
		int fixedLimit = Math.min(fixed.size(), Math.max(100, mobile.size() * 3));
		int mobileLimit = Math.min(mobile.size(), Math.max(100, fixed.size() * 3));
		int[] bestFixed = new int[mobileLimit];
		double[] bestDistance = new double[mobileLimit];
		java.util.Arrays.fill(bestFixed, -1);
		java.util.Arrays.fill(bestDistance, Double.POSITIVE_INFINITY);
		int[] bestMobile = new int[fixedLimit];
		double[] reverseDistance = new double[fixedLimit];
		java.util.Arrays.fill(bestMobile, -1);
		java.util.Arrays.fill(reverseDistance, Double.POSITIVE_INFINITY);
		for (int m = 0; m < mobileLimit; m++) {
			Spot source = mobile.get(m);
			double x = matrix[0][0] * source.x + matrix[0][1] * source.y + matrix[0][2];
			double y = matrix[1][0] * source.x + matrix[1][1] * source.y + matrix[1][2];
			for (int f = 0; f < fixedLimit; f++) {
				Spot target = fixed.get(f);
				double dx = x - target.x, dy = y - target.y, distance = dx * dx + dy * dy;
				if (distance < bestDistance[m]) { bestDistance[m] = distance; bestFixed[m] = f; }
				if (distance < reverseDistance[f]) { reverseDistance[f] = distance; bestMobile[f] = m; }
			}
		}
		List<PointMatch> result = new ArrayList<PointMatch>();
		for (int m = 0; m < mobileLimit; m++) {
			int f = bestFixed[m];
			if (f < 0 || bestDistance[m] > limit || bestMobile[f] != m) continue;
			Spot source = mobile.get(m), target = fixed.get(f);
			result.add(new PointMatch(new Point(new double[] { source.x, source.y }),
					new Point(new double[] { target.x, target.y })));
		}
		return result;
	}

	private static double[][] coarseTranslation(List<Spot> fixed, List<Spot> mobile,
			int maxX, int maxY) {
		Map<Long, Integer> votes = new HashMap<Long, Integer>();
		int best = 0, bestDx = 0, bestDy = 0;
		int useFixed = Math.min(300, fixed.size()), useMobile = Math.min(300, mobile.size());
		for (int m = 0; m < useMobile; m++) for (int f = 0; f < useFixed; f++) {
			int dx = (int) Math.round(fixed.get(f).x - mobile.get(m).x);
			int dy = (int) Math.round(fixed.get(f).y - mobile.get(m).y);
			if (Math.abs(dx) > maxX || Math.abs(dy) > maxY) continue;
			/* Two-pixel bins combine the small channel-dependent localization offsets that
			 * otherwise split the true displacement peak over adjacent integer bins. */
			dx = (int) Math.round(dx / 2.0);
			dy = (int) Math.round(dy / 2.0);
			long key = (((long) dx) << 32) ^ (dy & 0xffffffffL);
			Integer old = votes.get(key);
			int count = old == null ? 1 : old.intValue() + 1;
			votes.put(key, Integer.valueOf(count));
			if (count > best) { best = count; bestDx = 2 * dx; bestDy = 2 * dy; }
		}
		return new double[][] { { 1, 0, bestDx }, { 0, 1, bestDy } };
	}

	/** Bounded positive-DoG correlation, downsampled for a cheap cross-spectral coarse shift. */
	static double[][] coarseImageTranslation(ImageProcessor fixed, ImageProcessor moving,
			int maxX, int maxY) {
		final int factor = 4;
		int width = Math.max(16, fixed.getWidth() / factor);
		int height = Math.max(16, fixed.getHeight() / factor);
		FloatProcessor a = (FloatProcessor) fixed.resize(width, height, true).convertToFloatProcessor();
		FloatProcessor b = (FloatProcessor) moving.resize(width, height, true).convertToFloatProcessor();
		float[] ap = positiveDog(a), bp = positiveDog(b);
		int rangeX = Math.max(1, (int) Math.ceil(maxX / (double) factor));
		int rangeY = Math.max(1, (int) Math.ceil(maxY / (double) factor));
		double best = -Double.MAX_VALUE;
		int bestX = 0, bestY = 0;
		for (int dy = -rangeY; dy <= rangeY; dy++) {
			int yStart = Math.max(0, dy), yEnd = Math.min(height, height + dy);
			for (int dx = -rangeX; dx <= rangeX; dx++) {
				int xStart = Math.max(0, dx), xEnd = Math.min(width, width + dx);
				double dot = 0, aa = 0, bb = 0;
				for (int y = yStart; y < yEnd; y++) {
					int fixedRow = y * width, movingRow = (y - dy) * width;
					for (int x = xStart; x < xEnd; x++) {
						double av = ap[fixedRow + x], bv = bp[movingRow + x - dx];
						dot += av * bv; aa += av * av; bb += bv * bv;
					}
				}
				double score = dot / Math.sqrt(Math.max(1e-30, aa * bb));
				if (score > best) { best = score; bestX = dx; bestY = dy; }
			}
		}
		return new double[][] { { 1, 0, bestX * factor }, { 0, 1, bestY * factor } };
	}

	private static float[] positiveDog(FloatProcessor input) {
		FloatProcessor small = (FloatProcessor) input.duplicate();
		FloatProcessor large = (FloatProcessor) input.duplicate();
		GaussianBlur blur = new GaussianBlur();
		blur.blurGaussian(small, 0.8, 0.8, 0.01);
		blur.blurGaussian(large, 2.4, 2.4, 0.01);
		float[] result = (float[]) small.getPixels(), background = (float[]) large.getPixels();
		for (int i = 0; i < result.length; i++) result[i] = Math.max(0, result[i] - background[i]);
		double[] robust = medianMad(result);
		float threshold = (float) (robust[0] + 3 * robust[1]);
		for (int i = 0; i < result.length; i++) if (result[i] < threshold) result[i] = 0;
		return result;
	}

	private static boolean plausible(double[][] matrix) {
		if (!AlignmentMatrixSet.is2d(matrix)) return false;
		for (int row = 0; row < 2; row++) for (int column = 0; column < 3; column++)
			if (!Double.isFinite(matrix[row][column])) return false;
		double determinant = matrix[0][0] * matrix[1][1] - matrix[0][1] * matrix[1][0];
		if (Math.abs(determinant) < 0.5) return false;
		return SIFT.checkAlignMatrix(matrix, 2.0, 80.0, 35.0);
	}

	private static boolean localMaximum(float[] values, int index, int width) {
		float centre = values[index];
		for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
			if (dx == 0 && dy == 0) continue;
			if (values[index + dy * width + dx] >= centre) return false;
		}
		return true;
	}

	private static double parabola(double minus, double centre, double plus) {
		double denominator = minus - 2 * centre + plus;
		if (Math.abs(denominator) < 1e-12) return 0;
		return Math.max(-0.5, Math.min(0.5, 0.5 * (minus - plus) / denominator));
	}

	private static double[] medianMad(float[] values) {
		int stride = Math.max(1, values.length / 200000);
		float[] sample = new float[(values.length + stride - 1) / stride];
		int n = 0;
		for (int i = 0; i < values.length; i += stride) sample[n++] = values[i];
		java.util.Arrays.sort(sample, 0, n);
		double median = sample[n / 2];
		for (int i = 0; i < n; i++) sample[i] = (float) Math.abs(sample[i] - median);
		java.util.Arrays.sort(sample, 0, n);
		double madSigma = 1.4826 * sample[n / 2];
		return new double[] { median, madSigma };
	}
}
