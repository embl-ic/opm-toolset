package de.embl.iclm;

import java.awt.Polygon;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.plugin.Duplicator;
import ij.plugin.ZProjector;
import ij.plugin.filter.MaximumFinder;
import ij.plugin.frame.RoiManager;
import ij.process.AutoThresholder;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import ij.process.StackStatistics;
import inra.ijpb.data.border.ConstantBorder3D;

public class Beads {

	public static final String REJECT_EDGE = "crop touches image edge";
	public static final String REJECT_NEIGHBOR = "another bead is inside crop support";
	public static final String REJECT_SATURATED = "saturated voxel";
	public static final String REJECT_LOW_SNR = "low peak SNR";
	public static final String REJECT_LOW_SBR = "low peak/background ratio";
	public static final String REJECT_OFF_CENTER = "peak too far from crop center";
	public static final String REJECT_EMPTY = "no positive signal after background correction";

	/** Settings used to turn detected bead candidates into deconvolution kernels. */
	public static final class PsfQualitySettings {
		public double shellFraction = 0.15d;
		public double minSnr = 5.0d;
		public double minSbr = 1.5d;
		public double maxCenterOffset = 0.5d;
		public double saturationLevel = 0.0d;
		public boolean rejectNeighbors = true;
	}

	/** Accepted, normalized bead crops plus an auditable rejection summary. */
	public static final class PsfPreparation {
		private final List<ImagePlus> accepted = new ArrayList<ImagePlus>();
		private final Map<String, Integer> rejected = new LinkedHashMap<String, Integer>();
		private int candidateCount;

		public List<ImagePlus> getAccepted() {
			return accepted;
		}

		public Map<String, Integer> getRejected() {
			return rejected;
		}

		public int getCandidateCount() {
			return candidateCount;
		}

		public int getRejectedCount() {
			return candidateCount - accepted.size();
		}

		private void reject(String reason) {
			Integer count = rejected.get(reason);
			rejected.put(reason, count == null ? 1 : count + 1);
		}

		public void logSummary(String source) {
			String label = source == null || source.trim().isEmpty() ? "bead volume" : source;
			IJ.log("PSF quality control - " + label + ": accepted " + accepted.size() +
					" / " + candidateCount + " candidate(s).");
			for (Map.Entry<String, Integer> entry : rejected.entrySet())
				IJ.log("  rejected " + entry.getValue() + ": " + entry.getKey());
		}
	}

	
	
	//TODO: add align channel with rigid body sift here
	
	
	
	/**		Find the region of a bead volume that actually holds sample
	 * <br>		A dual camera bead acquisition has one bright region per half; anything else is
	 * <br>		reported rather than guessed at, because a wrong region silently spoils the PSF.
	 *
	 * @param imp		: bead volume
	 * @param roiType	: how to tighten the detected regions, e.g. "hull"
	 * <p>
	 * @return			: one ROI per sample region found, at most two
	 */
	public static Roi[] guessSampleRegion (
			ImagePlus imp,
			String roiType
			) {
		
		int h = imp.getHeight();
		int w = imp.getWidth();
		double bgRadius = (double)h / 2.0d;
		long minRoiSize = w * h / 8 ;
		
		ImagePlus impZ = ZProjector.run(imp, "max all");
		IJ.run(impZ, "Subtract Background...", "rolling=" + bgRadius + " create sliding");
		impZ.getProcessor().setAutoThreshold("Mean", true, ImageProcessor.NO_LUT_UPDATE);
		IJ.run(impZ, "Analyze Particles...", "size=" + minRoiSize + "-Infinity pixel show=Overlay include");
		
		Roi[] rois = impZ.getOverlay().toArray();
		if (null == rois || rois.length > 2 || rois.length==0) {
			System.out.println("Error: more than 2 sample ROIs...");
			impZ.show();
		} else {
			impZ.changes = false;
			impZ.close();
		}
		return Utils.modifyRoi ( rois, "hull" );
		
	}
	
	
	public static int[][] pointFromManager () {
		RoiManager rm = RoiManager.getInstance();
		if ( null == rm || 0 == rm.getCount() ) return null;
		Roi[] rois = rm.getRoisAsArray();
		List<int[]> pointList = new ArrayList<int[]>();
		for (int i=0; i<rois.length; i++) {
			if ( !(rois[i] instanceof PointRoi) ) {
				IJ.log(" entry " + (i+1) + " in ROI Manager with name (" + rm.getName(i) + ") is not point ROI.");
				IJ.log(" will try to take its centroid as the peak position.");
			}
			int zPos = rois[i].getZPosition();
			if ( zPos <= 0 ) {
				IJ.log(" entry " + (i+1) + " in ROI Manager with name (" + rm.getName(i) + ") has no Z position defined.");
				IJ.log(" it will be omit from importing.");
				continue;
			}
			Rectangle box = rois[i].getBounds();
			int[] center = new int[3];
			center[0] = (int) (box.x + (double)box.width/2d - 0.5);
			center[1] = (int) (box.y + (double)box.height/2d - 0.5);
			center[2] = zPos - 1;
			pointList.add(center);
		}
		if ( 0 == pointList.size() ) return null;
		return pointList.toArray(new int[pointList.size()][3]);
	}

	
	/**			Save points as pointRoi to ROI Manager
	 * <br>		So a detection can be inspected, corrected by hand, and read back in through
	 * <br>		pointFromManager before the PSF is built.
	 *
	 * @param points	: bead positions as {x, y, z}, with a 0 based z
	 */
	public static void pointToManager(
			int[][] points
			) {
		if (null == points) return;
		RoiManager rm = RoiManager.getInstance();
		if (null==rm) rm = new RoiManager();
		int nPoints = points.length;
		int ndigit = (int) (Math.floor(Math.log10(nPoints)) + 1);
		for (int i=0; i<points.length; i++) {
			int[] xyz = points[i];
			PointRoi roi = new PointRoi(xyz[0], xyz[1]);
			roi.setPosition(0, xyz[2]+1, 0);	// Z index in point is 0-based
			rm.addRoi(roi);
			rm.rename(rm.getCount()-1, "point-"+IJ.pad(i+1, ndigit));
		}
	}
	
	
	/**			Get XY coordinates of the local maxima points
	 * <br>		with pre-defined local maxima tolerance			
	 * 
	 * @param imp		: bead volume
	 * @param tolerance	: prominence a maximum must have to count as a bead
	 * <p>
	 * @return			: bead positions as {x, y, z}, with a 0 based z at the bead's brightest
	 * 					  slice; an empty array when none were found
	 */
	public static int[][] getMaxPoints (
			ImagePlus imp,
			double tolerance
			) {
		if (imp==null) return null;
		int numZ = imp.getNSlices();
		int x0 = 0; int y0 = 0;
		Roi roi = imp.getRoi();
		ImagePlus imp_dup = imp;
		if (null != roi) {
			x0 = (int)roi.getXBase();
			y0 = (int)roi.getYBase();
			imp_dup = new Duplicator().run(imp);
		}
		
		ImageStack stack = imp_dup.getStack();
		ImagePlus imp_zMax = Projection.projection(imp_dup, "Z", "max", true);// ZProjector.run(imp_dup, "max all");
		
		boolean strict = true;
		boolean excludeOnEdges = true;
		
		Polygon polygon = new MaximumFinder().getMaxima(imp_zMax.getProcessor(), tolerance, strict, excludeOnEdges);
		imp_zMax.close();
		if (polygon==null || polygon.npoints==0) return new int[0][0];
		
		int nPoints = polygon.npoints;
		int[][] maxPoints = new int[nPoints][3];
		for (int i=0; i<nPoints; i++) {
			int[] xyz = new int[3];
			xyz[0] = (int) polygon.xpoints[i];
			xyz[1] = (int) polygon.ypoints[i];
			
			float[] zValues = new float[numZ];
			zValues = stack.getVoxels(xyz[0], xyz[1], 0, 1, 1, numZ, zValues);
			int maxIdx = findMaxZindex(zValues);
			xyz[0] += x0; 
			xyz[1] += y0;
			xyz[2] = maxIdx;
			maxPoints[i] = xyz;
		}
		return maxPoints;
	}
	
	
	/**			Get XY coordinates of the local maxima points
	 * <br>		with pre-defined prospective number of points
	 * <br>		The tolerance is searched for rather than asked for, so the user says how many
	 * <br>		beads they expect instead of guessing at an intensity threshold.
	 *
	 * @param imp		: bead volume
	 * @param nPoints	: how many beads the volume is expected to contain
	 * <p>
	 * @return			: bead positions as {x, y, z}, with a 0 based z
	 */
	public static int[][] getMaxPoints (
			ImagePlus imp,
			int nPoints
			) {
		if (imp==null) return null;
		ImageProcessor ip = null;		
		// check whether image has multiple Z slices
		if (1 == imp.getNSlices()) {
			ip = imp.getProcessor().crop();
		} else {
			ImagePlus imp_dup = imp;
			if ( null != imp.getRoi() )
				imp_dup = new Duplicator().run(imp);
			ImagePlus imp_zMax = ZProjector.run(imp_dup, "max all");
			ip = imp_zMax.getProcessor();
			if ( null != imp.getRoi() )
				imp_dup.close();
		}
		ImageStatistics stat = ip.getStats();
		double tolerance = getFuncValueBisection ( ip, nPoints, stat.min, stat.max );
		return getMaxPoints (imp, tolerance);
	}
	
	
	/**			How far the maxima count at one tolerance is from the expected bead count
	 * <br>		Positive means too many maxima were found, negative means too few. The count
	 * <br>		falls as tolerance rises, so this function is monotonically decreasing, which is
	 * <br>		what lets getFuncValueBisection bracket it.
	 *
	 * @param ip		: ImageProcessor of the input image (2D)
	 * @param tolerance	: prominence tolerance handed to MaximumFinder
	 * @param nPoints	: expected number of max points in image
	 * <p>
	 * @return			: found maxima count minus expected count, as a signed number of beads
	 */
	public static double getNumPointsMismatch (
			ImageProcessor ip,
			double tolerance,
			int nPoints
			) {
  		double nPts = (double)new MaximumFinder().getMaxima(ip, tolerance, true, true).npoints - (double)nPoints;
     	return nPts;
     }

	/** Bead count is matched to within this fraction of the expected count. */
	private static final double COUNT_TOLERANCE_FRACTION = 0.05d;
	/** Bisection stops here whether or not the count converged; each step costs a full detection. */
	private static final int MAX_BISECTION_STEPS = 10;

	/**			find tolerance value corresponding to nPoint ± epsilon in image
	 * <br>		with bisection method
	 * <p>		Convergence is tested on the bead count, which is what the caller cares about and
	 * <br>		the only quantity the two ends of the bracket have in common; the tolerance
	 * <br>		interval itself is an intensity and cannot be compared against a count. The
	 * <br>		mismatch at the low end of the bracket is carried forward instead of being
	 * <br>		recomputed, so one detection runs per step rather than two.
	 *
	 * @param ip				: ImageProcessor of the input image (2D)
	 * @param nPoints			: expected number of max points in image
	 * @param minTolerance		: initial minimum tolerance value
	 * @param maxTolerance		: initial maximum tolerance value
	 * <p>
	 * @return double tolerance : computed tolerance value
	 */
    public static double getFuncValueBisection (
    		ImageProcessor ip,
    		int nPoints,
    		double minTolerance,
    		double maxTolerance
    		) {
    	// at least one bead, so a small expected count cannot ask for an exact match
 		double epsilon = Math.max( 1.0d, nPoints * COUNT_TOLERANCE_FRACTION );
        double tolerance = minTolerance;
        double lowMismatch = getNumPointsMismatch ( ip, minTolerance, nPoints );
        for ( int iter = 0; iter < MAX_BISECTION_STEPS; iter++ ) {
            tolerance = (minTolerance + maxTolerance) / 2;
            double currentMismatch = getNumPointsMismatch ( ip, tolerance, nPoints );
            // close enough on the number of beads: stop
            if ( Math.abs( currentMismatch ) <= epsilon ) break;
            // sign change between the low end and here: the answer is in the lower half
            if ( currentMismatch * lowMismatch < 0 ) {
                maxTolerance = tolerance;
            } else {
                minTolerance = tolerance;
                lowMismatch = currentMismatch;	// carry the bracket end forward, do not re-detect
            }
        }
        return tolerance;
    } 
	
    

	
	
	/**		Locate the (1st) Z slice that contains the maximum voxel value in a max Z projection 
	 * <br>	0-based index
	 *
	 * @param values	: intensity along Z at one XY position
	 * <p>
	 * @return			: index of the brightest slice, 0 based
	 */
	public static int findMaxZindex (
			float[] values
			) {
		int N = values.length;
		float max = Float.MIN_VALUE;
		int maxIdx = -1;
		for (int i=0; i<N; i++) {
			if (values[i] > max) {
				maxIdx = i;
				max = values[i];
			}
		}
		return maxIdx;
	}
	
	/**		Cut one crop around every detected bead
	 * <br>		These crops are what the PSF is averaged from, so each is centred on its bead and
	 * <br>		all of them share one size.
	 *
	 * @param imp		: bead volume
	 * @param points	: bead positions as {x, y, z}, with a 0 based z
	 * @param radiusX	: half width of the crop, in pixels
	 * @param radiusY	: half height of the crop, in pixels
	 * @param radiusZ	: half depth of the crop, in slices
	 * <p>
	 * @return			: one crop per bead that fits inside the volume
	 */
	public static List<ImagePlus> getBeadsImageList (
			ImagePlus imp, 
			int[][] points, 	// 0-based
			int radiusX,
			int radiusY,
			int radiusZ
			) {
		List<ImagePlus> imp_list = new ArrayList<ImagePlus>();
		if (null == imp || null == points || 0 == points.length) return imp_list;
		IJ.showStatus("0 padding input beads image borders...");
		ConstantBorder3D border = new ConstantBorder3D(imp.getStack(), 0);
		ImageStack stack_ext = border.addBorders(
				imp.getStack(), radiusX, radiusX, radiusY, radiusY, radiusZ, radiusZ);
		ImagePlus imp_ext = new ImagePlus("imp_extended", stack_ext);
		for (int i=0; i<points.length; i++) {
			IJ.showStatus("cropping sub-volumes surrounding bead center...");
			IJ.showProgress(i, points.length);
			int[] xyz = points[i];
			Roi roi = new Roi(xyz[0], xyz[1], radiusX*2+1, radiusY*2+1);
			imp_ext.setRoi(roi);
			ImagePlus imp_crop = new Duplicator().run(imp_ext, xyz[2]+1, xyz[2]+radiusZ*2+1);
			imp.deleteRoi();
			imp_list.add(imp_crop);
		}
		imp_ext.close();
		return imp_list;
	}

	/**
	 * Crop, robustly background-correct, quality-filter and unit-normalize bead candidates.
	 * Candidate coordinates are zero based. Crops that do not fit completely are rejected;
	 * no artificial zero padding is introduced into an experimental PSF.
	 */
	public static PsfPreparation preparePsfBeads(
			ImagePlus imp,
			int[][] points,
			int radiusX,
			int radiusY,
			int radiusZ,
			PsfQualitySettings requestedSettings) {
		PsfPreparation result = new PsfPreparation();
		if (imp == null || points == null || points.length == 0) return result;
		result.candidateCount = points.length;
		PsfQualitySettings settings = requestedSettings == null ? new PsfQualitySettings() : requestedSettings;
		radiusX = Math.max(1, radiusX);
		radiusY = Math.max(1, radiusY);
		radiusZ = Math.max(1, radiusZ);
		int width = imp.getWidth();
		int height = imp.getHeight();
		int depth = imp.getStackSize();
		int cropWidth = 2 * radiusX + 1;
		int cropHeight = 2 * radiusY + 1;
		int cropDepth = 2 * radiusZ + 1;
		double saturation = effectiveSaturationLevel(imp, settings.saturationLevel);

		for (int i = 0; i < points.length; i++) {
			IJ.showStatus("Preparing experimental PSF bead " + (i + 1) + " / " + points.length);
			IJ.showProgress(i, points.length);
			int[] center = points[i];
			if (center == null || center.length < 3 ||
					center[0] - radiusX < 0 || center[0] + radiusX >= width ||
					center[1] - radiusY < 0 || center[1] + radiusY >= height ||
					center[2] - radiusZ < 0 || center[2] + radiusZ >= depth) {
				result.reject(REJECT_EDGE);
				continue;
			}
			if (settings.rejectNeighbors && hasNeighborInSupport(points, i, radiusX, radiusY, radiusZ)) {
				result.reject(REJECT_NEIGHBOR);
				continue;
			}

			float[][] planes = new float[cropDepth][cropWidth * cropHeight];
			float[] shell = new float[cropWidth * cropHeight * cropDepth];
			int shellCount = 0;
			boolean saturated = false;
			float rawMaximum = -Float.MAX_VALUE;
			int maximumX = radiusX;
			int maximumY = radiusY;
			int maximumZ = radiusZ;
			int shellX = Math.max(1, (int)Math.ceil(cropWidth * clamp(settings.shellFraction, 0.01d, 0.49d)));
			int shellY = Math.max(1, (int)Math.ceil(cropHeight * clamp(settings.shellFraction, 0.01d, 0.49d)));
			int shellZ = Math.max(1, (int)Math.ceil(cropDepth * clamp(settings.shellFraction, 0.01d, 0.49d)));
			for (int z = 0; z < cropDepth; z++) {
				ImageProcessor source = imp.getStack().getProcessor(center[2] - radiusZ + z + 1);
				float[] plane = planes[z];
				for (int y = 0; y < cropHeight; y++) {
					int sourceY = center[1] - radiusY + y;
					for (int x = 0; x < cropWidth; x++) {
						float value = source.getf(center[0] - radiusX + x, sourceY);
						plane[y * cropWidth + x] = value;
						if (value > rawMaximum) {
							rawMaximum = value;
							maximumX = x;
							maximumY = y;
							maximumZ = z;
						}
						if (value >= saturation) saturated = true;
						boolean inShell = x < shellX || x >= cropWidth - shellX ||
								y < shellY || y >= cropHeight - shellY ||
								z < shellZ || z >= cropDepth - shellZ;
						if (inShell && value < saturation) shell[shellCount++] = value;
					}
				}
			}
			if (saturated) {
				result.reject(REJECT_SATURATED);
				continue;
			}
			if (shellCount == 0) {
				result.reject(REJECT_EMPTY);
				continue;
			}

			double background = median(shell, shellCount);
			float[] deviations = new float[shellCount];
			for (int j = 0; j < shellCount; j++) deviations[j] = (float)Math.abs(shell[j] - background);
			double noise = 1.4826d * median(deviations, shellCount);
			double signal = rawMaximum - background;
			double snr = signal / Math.max(noise, 1.0e-12d);
			double sbr = background > 0.0d ? rawMaximum / background : Double.POSITIVE_INFINITY;
			if (!(signal > 0.0d) || Double.isNaN(snr) || snr < Math.max(0.0d, settings.minSnr)) {
				result.reject(REJECT_LOW_SNR);
				continue;
			}
			if (sbr < Math.max(0.0d, settings.minSbr)) {
				result.reject(REJECT_LOW_SBR);
				continue;
			}
			double offsetX = (maximumX - radiusX) / (double)radiusX;
			double offsetY = (maximumY - radiusY) / (double)radiusY;
			double offsetZ = (maximumZ - radiusZ) / (double)radiusZ;
			double centerOffset = Math.sqrt(offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ);
			if (centerOffset > Math.max(0.0d, settings.maxCenterOffset)) {
				result.reject(REJECT_OFF_CENTER);
				continue;
			}

			double integral = 0.0d;
			for (float[] plane : planes) {
				for (int j = 0; j < plane.length; j++) {
					plane[j] = (float)Math.max(0.0d, plane[j] - background);
					integral += plane[j];
				}
			}
			if (!(integral > 0.0d) || !Double.isFinite(integral)) {
				result.reject(REJECT_EMPTY);
				continue;
			}
			ImageStack stack = new ImageStack(cropWidth, cropHeight);
			for (int z = 0; z < cropDepth; z++) {
				for (int j = 0; j < planes[z].length; j++) planes[z][j] /= (float)integral;
				stack.addSlice(new FloatProcessor(cropWidth, cropHeight, planes[z]));
			}
			ImagePlus bead = new ImagePlus("PSF-bead-" + IJ.pad(i + 1, 4), stack);
			Calibration calibration = imp.getCalibration();
			if (calibration != null) bead.setCalibration(calibration.copy());
			result.accepted.add(bead);
		}
		IJ.showProgress(1.0d);
		return result;
	}

	private static boolean hasNeighborInSupport(int[][] points, int index,
			int radiusX, int radiusY, int radiusZ) {
		int[] point = points[index];
		if (point == null || point.length < 3) return false;
		for (int j = 0; j < points.length; j++) {
			if (j == index || points[j] == null || points[j].length < 3) continue;
			double dx = (point[0] - points[j][0]) / (double)radiusX;
			double dy = (point[1] - points[j][1]) / (double)radiusY;
			double dz = (point[2] - points[j][2]) / (double)radiusZ;
			if (dx * dx + dy * dy + dz * dz <= 1.0d) return true;
		}
		return false;
	}

	private static double effectiveSaturationLevel(ImagePlus imp, double requested) {
		if (requested > 0.0d && Double.isFinite(requested)) return requested;
		if (imp.getBitDepth() == 8) return 255.0d;
		if (imp.getBitDepth() == 16) return 65535.0d;
		return Double.POSITIVE_INFINITY;
	}

	private static double median(float[] values, int length) {
		float[] sorted = Arrays.copyOf(values, length);
		Arrays.sort(sorted);
		int middle = length / 2;
		return length % 2 == 0 ? 0.5d * (sorted[middle - 1] + sorted[middle]) : sorted[middle];
	}

	private static double clamp(double value, double minimum, double maximum) {
		if (!Double.isFinite(value)) return minimum;
		return Math.max(minimum, Math.min(maximum, value));
	}
	
	/**		Average the accepted bead crops into one PSF
	 * <br>		A median average is the default: it rejects a crop spoiled by a neighbouring bead
	 * <br>		or a cosmic ray without needing that crop to be detected and removed first.
	 *
	 * @param imp_list	: accepted bead crops, all of the same size
	 * @param avgMethod	: "median average", "mean average" or "all beads"
	 * <p>
	 * @return			: the averaged PSF, or the whole set when "all beads" was asked for
	 */
	public static ImagePlus combineBeadsImage (
			List<ImagePlus> imp_list,
			String avgMethod
			) {
		if (null == imp_list || 0 == imp_list.size()) return null;
		ImagePlus first = imp_list.get(0);
		int width = first.getWidth();
		int height = first.getHeight();
		int depth = first.getStackSize();
		for (ImagePlus image : imp_list) {
			if (image == null || image.getWidth() != width || image.getHeight() != height ||
					image.getStackSize() != depth)
				throw new IllegalArgumentException("All bead crops must have identical XYZ dimensions.");
		}

		ImageStack output = new ImageStack(width, height);
		if ("all beads".equals(avgMethod)) {
			double[] sums = new double[imp_list.size()];
			for (int bead = 0; bead < imp_list.size(); bead++) {
				for (int z = 1; z <= depth; z++) {
					float[] pixels = (float[])imp_list.get(bead).getStack().getProcessor(z)
							.convertToFloatProcessor().getPixels();
					for (float value : pixels) sums[bead] += Math.max(0.0f, value);
				}
			}
			for (int z = 1; z <= depth; z++) {
				for (int bead = 0; bead < imp_list.size(); bead++) {
					float[] source = (float[])imp_list.get(bead).getStack().getProcessor(z)
							.convertToFloatProcessor().getPixels();
					float[] pixels = new float[source.length];
					if (sums[bead] > 0.0d)
						for (int i = 0; i < source.length; i++)
							pixels[i] = (float)(Math.max(0.0f, source[i]) / sums[bead]);
					output.addSlice(new FloatProcessor(width, height, pixels));
				}
			}
		} else {
			boolean useMedian = avgMethod != null && avgMethod.startsWith("median");
			float[] samples = useMedian ? new float[imp_list.size()] : null;
			for (int z = 1; z <= depth; z++) {
				float[][] source = new float[imp_list.size()][];
				for (int bead = 0; bead < imp_list.size(); bead++)
					source[bead] = (float[])imp_list.get(bead).getStack().getProcessor(z)
							.convertToFloatProcessor().getPixels();
				float[] pixels = new float[width * height];
				for (int pixel = 0; pixel < pixels.length; pixel++) {
					if (useMedian) {
						for (int bead = 0; bead < source.length; bead++) samples[bead] = source[bead][pixel];
						pixels[pixel] = (float)median(samples, samples.length);
					} else {
						double sum = 0.0d;
						for (float[] bead : source) sum += bead[pixel];
						pixels[pixel] = (float)(sum / source.length);
					}
				}
				output.addSlice(new FloatProcessor(width, height, pixels));
			}
			normalizeStack(output);
		}

		ImagePlus combined = new ImagePlus("experimental-PSF", output);
		if ("all beads".equals(avgMethod)) combined.setDimensions(imp_list.size(), depth, 1);
		Calibration calibration = first.getCalibration();
		if (calibration != null) combined.setCalibration(calibration.copy());
		return combined;
	}

	private static void normalizeStack(ImageStack stack) {
		double sum = 0.0d;
		for (int z = 1; z <= stack.getSize(); z++) {
			float[] pixels = (float[])stack.getProcessor(z).getPixels();
			for (float value : pixels) sum += value;
		}
		if (!(sum > 0.0d) || !Double.isFinite(sum)) return;
		for (int z = 1; z <= stack.getSize(); z++) {
			float[] pixels = (float[])stack.getProcessor(z).getPixels();
			for (int i = 0; i < pixels.length; i++) pixels[i] = (float)(pixels[i] / sum);
		}
	}
	
	
	/**				check beads image, that not at the border, and contains only 1 bead
	 * 
	 * @param imp_bead		: one bead crop
	 * @param sizeXY		: expected crop width and height, in pixels
	 * @param sizeZ			: expected crop depth, in slices
	 * <p>
	 * @return boolean		: whether the beads image can be used
	 */
	public static boolean checkBeadImage (ImagePlus imp_bead, int sizeXY, int sizeZ) {
		int[] dims = imp_bead.getDimensions(true);
		if (dims[0]!=sizeXY || dims[1]!=sizeXY || dims[3]!=sizeZ) return false;
		StackStatistics stat = new StackStatistics(imp_bead);
		if (0 == stat.min) return false;
		ImagePlus impZ = ZProjector.run(imp_bead,"avg");
		impZ.getProcessor().setAutoThreshold(AutoThresholder.Method.Otsu, true);
		ByteProcessor bp = impZ.getProcessor().createMask();
		ImagePlus mask = new ImagePlus("mask", bp);
		IJ.run(mask, "Analyze Particles...", "size=3-Infinity include overlay slice");
		Roi[] rois = mask.getOverlay().toArray();
		int nRoi = rois.length;
		if (nRoi > 1) return false;
		return true;
	}
	
	
	/**				Filter beads coordinates, exclude beads on edge or too close to each other
	 * 
	 * @param imp			: input image stack, used to crop the volume around beads
	 * @param points		: XYZ coordinates of beads centroid
	 * @param radiusXY		: width and heigth of crop box
	 * @param radiusZ		: depth of crop box
	 * <p>
	 * @return				: filtered beads centroid coordinates
	 */
	public static int[][] filterPoints (
			ImagePlus imp, 
			int[][] points, 
			double radiusXY, 
			double radiusZ
			) {
		if (null == points || 0 == points.length) return null;
		int[] dims = imp.getDimensions(true);
		int sizeX = dims[0]; int sizeY = dims[1]; int sizeZ = dims[3];
		if ( 1==sizeZ ) sizeZ = dims[4];
		int minX = (int) Math.ceil(radiusXY+1); 	int maxX = (int) Math.floor(sizeX-radiusXY); 
		int minY = (int) Math.ceil(radiusXY+1); 	int maxY = (int) Math.floor(sizeY-radiusXY);
		int minZ = (int) Math.ceil(radiusZ+1);		int maxZ = (int) Math.floor(sizeZ-radiusZ);
		int minDist2 = (int) Math.ceil(Math.sqrt(2*radiusXY*radiusXY + radiusZ*radiusZ));
		
		Set<Integer> exclude_set = new HashSet<Integer>();
		for (int i=0; i<points.length; i++) {
			if (exclude_set.contains(i)) continue;
			int x = points[i][0];
			int y = points[i][1];
			int z = points[i][2];
			// check inter-point distance, exclude both points when their inter-distance is smaller than 3*radius^2
			if (i!=points.length-1) {
				for (int j=i+1; j<points.length; j++) {
					int dx = x - points[j][0];
					int dy = y - points[j][1];
					int dz = z - points[j][2];
					int dist = dx*dx + dy*dy + dz*dz;
					if (dist<minDist2) {
						exclude_set.add(i);
						exclude_set.add(j);
						continue;
					}
				}
			}
			// check border
			if (x<minX || x>maxX || y<minY || y>maxY || z<minZ || z>maxZ)
				exclude_set.add(i);
		}
		int N = points.length - exclude_set.size();
		if (N <= 0) return null;
		int[][] points_filtered = new int[N][3];
		int idx = 0;
		for (int i=0; i<points.length; i++) {
			if (exclude_set.contains(i)) continue;
			points_filtered[idx++] = points[i];
		}
		return points_filtered;
	}

	
	/**		Pad a PSF out to the size of the volume it will deconvolve
	 * <br>		FFT deconvolution needs both arrays the same size; padding with noise rather than
	 * <br>		with zeros avoids the ringing a hard edge introduces.
	 *
	 * @param imp_PSF	: the measured PSF
	 * @param imp_input	: the volume the PSF will be applied to; only its size is used
	 * @param addNoise	: fill the padding with background noise instead of zeros
	 * <p>
	 * @return			: the PSF, centred in a volume of the input's size
	 */
	public static ImagePlus extendBorder (
			ImagePlus imp_PSF, 
			ImagePlus imp_input, 
			boolean addNoise
			) {
		if (null == imp_PSF || null == imp_input) return null;
		// get input image dimensions
		int width = imp_input.getWidth();
		int height = imp_input.getHeight();
		int depth = imp_input.getNSlices();
		if (depth == 1) depth = imp_input.getNFrames();
		if (depth == 1) return imp_PSF;
		// get PSF image dimensions
		int width_PSF = imp_PSF.getWidth();
		int height_PSF = imp_PSF.getHeight();
		int depth_PSF = imp_PSF.getNSlices();
		// calculate the margin size at each side
		int left = (width - width_PSF) / 2;
		int right = width - width_PSF - left;
		int top = (height - height_PSF) / 2;
		int bottom = height - height_PSF - top;
		int front = (depth - depth_PSF) / 2;
		int back = depth - depth_PSF - front;
		// compute mode and stdDev values from the input image stack
		StackStatistics stats = new StackStatistics(imp_PSF);
		int baseLine = (int) stats.umean; double stdDev = stats.stdDev;
		// 0-pad PSF image to be the same size as input image
		ConstantBorder3D border = new ConstantBorder3D(imp_PSF.getStack(), baseLine);
		ImageStack stack_ext = border.addBorders(imp_PSF.getStack(), left, right, top, bottom, front, back);
		ImagePlus PSF_ext = new ImagePlus("PSF_extended", stack_ext);
		// if requesetd, add noise as the input image background to the padded margins
		if (addNoise) {
			Roi roi = new Roi ( left, top, width_PSF, height_PSF );
			roi = roi.getInverse( PSF_ext );
			for (int i=0; i<depth; i++) {
				if (i >= front && i < (depth-back)) {
					stack_ext.getProcessor(i+1).setRoi( roi );
				}
				stack_ext.getProcessor(i+1).noise(stdDev);
			}
			PSF_ext.setStack( stack_ext );
		}
		return PSF_ext;
	}

}
