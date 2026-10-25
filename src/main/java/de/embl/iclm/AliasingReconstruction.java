package de.embl.iclm;

import edu.emory.mathcs.jtransforms.fft.FloatFFT_2D;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

/**
 * Comb upsampling and tilted Fourier-support selection for raw OPM volumes.
 *
 * <p>Adapted from McFadden, Manton and Fiolka, doi:10.1101/2024.12.25.630337. In this toolset's
 * geometry ({@link Transform#deskew}) the raw sample at camera row u and scan index v sits at
 * Y = p*cos(theta)*u + d*v, Z = -p*sin(theta)*u + constant. A lab frequency (kY, kZ) therefore
 * appears on the raw grid at fu = p*(cos(theta)*kY - sin(theta)*kZ), fv = d*kY, and
 * fv - s*fu = d*tan(theta)*kZ with s = d/(p*cos(theta)). Keeping |fv - s*fu| < 1/(2m) selects the
 * axial slab |kZ| < cot(theta)/(2*m*d), the support the authors keep, applied on the raw grid
 * so that no zero comb is ever interpolated. d is the RECONSTRUCTED scan pitch, m the integer
 * upsampling factor. It is an adaptation, not a port of the authors' PetaKit5D code.
 *
 * <p>That selection is exact only for content whose support lies in the intended lattice cell.
 * The raw-grid mask is periodic: it stands for the slab and every copy of it on the reciprocal
 * lattice of the fine grid, so a frequency already aliased on that lattice cannot be told apart
 * and is not removed. kY = 1/d, kZ = cot(theta)/d, for one, samples exactly like DC. The method
 * separates the copies made by the coarse scan; it cannot undo aliasing in the data themselves.
 *
 * <p>The distance to that band is taken modulo one cycle per sample ({@link #bandDistance}). A
 * raw-grid frequency is only defined modulo 1, and the band is steep - s is about 2.7 at
 * 0.116 um, 33.5 degrees and 0.265 um - so it wraps round the fv axis for every |kY| above
 * 1/(2d). Measured without the wrap, that part of the band was removed along with its aliases:
 * a hidden lab-Y low-pass at the target step's Nyquist, which erased a kZ = 0 pattern that
 * every slab has to keep. The authors deskew the comb before masking and never meet this;
 * filtering on the raw grid does.
 *
 * <p>The mask does not depend on kX, so independent Y/scan FFTs are equivalent to a full 3D
 * FFT and need bounded scratch memory. The output stays on the RAW grid; the caller deskews it
 * at acquiredStep/factor. Recoverable bandwidth has to be established from a dense PSF or
 * reference - this is not generic missing-plane recovery. Negative ringing is kept.
 */
public final class AliasingReconstruction {

	private AliasingReconstruction () { }

	/** Geometry and filter settings of one reconstruction. */
	public static final class Settings {
		public double pixelUm = 0.116;
		/** Step between the planes that were acquired, not the step reconstructed. */
		public double acquiredStepUm = 0.530;
		public double angleDegrees = 33.5;
		/** Integer upsampling factor: acquired step / reconstructed step. */
		public int factor = 2;
		/** Fine-grid planes of padding on each side, filled with the background. */
		public int padding = 32;
		/** Subtracted before the comb operation and restored afterwards. */
		public double background = 0;
		/** Fraction of the half-band occupied by a cosine transition; 0 = the paper's top hat. */
		public double taper = 0;
		public boolean gpu = false;

		public void validate () {
			if (!Double.isFinite(pixelUm) || pixelUm <= 0
					|| !Double.isFinite(acquiredStepUm) || acquiredStepUm <= 0
					|| !Double.isFinite(angleDegrees) || angleDegrees <= 0 || angleDegrees >= 89.9
					|| factor < 1 || factor > 64 || padding < 0 || padding > 4096
					|| !Double.isFinite(background) || !Double.isFinite(taper) || taper < 0 || taper >= 1)
				throw new IllegalArgumentException("Use positive pitches, 0 < angle < 89.9, integer factor 1-64, "
						+ "padding 0-4096 and 0 <= taper < 1.");
		}

		public double fineStepUm () {
			return acquiredStepUm / factor;
		}

		/** Slope s of the signal band on the reconstructed raw grid, scan cycles per camera cycle. */
		public double slope () {
			return fineStepUm() / (pixelUm * Math.cos(Math.toRadians(angleDegrees)));
		}
	}

	public static void validateVolume (ImagePlus input) {
		if (input == null || input.getNChannels() != 1 || input.getNFrames() != 1 || input.getStackSize() < 2)
			throw new IllegalArgumentException("Use one raw 3D volume, one channel and one time point. Split hyperstacks first.");
		if (input.getBitDepth() == 24)
			throw new IllegalArgumentException("RGB data are not raw scalar intensities. Use an 8, 16 or 32-bit stack.");
	}

	/** The power of two that holds extent plus padding on both sides. */
	static int paddedSize (int extent, int padding) {
		long needed = (long) extent + 2L * padding;
		int size = 2;
		while (size < needed && size < (1 << 29)) size <<= 1;
		if (size < needed) throw new IllegalArgumentException("FFT dimension is too large.");
		return size;
	}

	/** Principal frequency of FFT bin index, in cycles per sample, in [-1/2, 1/2). */
	static double frequency (int index, int size) {
		return (index < (size + 1) / 2 ? index : index - size) / (double) size;
	}

	/**
	 * Distance of a raw-grid frequency from the signal band, in cycles per fine scan sample.
	 * <p>
	 * Taken modulo 1: the stored scan frequency of a component is its true one less an integer,
	 * and the band is steep enough to wrap. See the class comment.
	 */
	static double bandDistance (double scanFrequency, double cameraFrequency, double slope) {
		double offset = scanFrequency - slope * cameraFrequency;
		return Math.abs(offset - Math.rint(offset));
	}

	private static float weight (double distance, int factor, double taper) {
		double edge = 0.5 / factor;
		if (distance >= edge) return 0;
		if (taper == 0 || distance <= edge * (1 - taper)) return 1;
		return (float) (0.5 + 0.5 * Math.cos(Math.PI * (distance - edge * (1 - taper)) / (edge * taper)));
	}

	/** The mask on the padded scan x camera-row FFT grid, scan-major. */
	static float[] weights (int nz, int ny, Settings settings) {
		if ((long) nz * ny > Integer.MAX_VALUE / 2)
			throw new IllegalArgumentException("Y/scan FFT exceeds the Java array limit; crop a smaller ROI.");
		float[] result = new float[nz * ny];
		if (settings.factor == 1) {
			Arrays.fill(result, 1);
			return result;
		}
		double slope = settings.slope();
		for (int z = 0; z < nz; z++) {
			for (int y = 0; y < ny; y++) {
				double distance = bandDistance(frequency(z, nz), frequency(y, ny), slope);
				double conjugate = bandDistance(frequency((nz - z) % nz, nz), frequency((ny - y) % ny, ny), slope);
				// Nyquist rows and columns are their own conjugates; the minimum keeps the result real.
				result[z * ny + y] = Math.min(weight(distance, settings.factor, settings.taper),
						weight(conjugate, settings.factor, settings.taper));
			}
		}
		return result;
	}

	/**
	 * Reconstruct a sparse raw volume onto a scan grid factor times finer.
	 * <p>
	 * The output spans the first through the last acquired plane, (N-1)*factor+1 planes, and is
	 * 32-bit with negative ringing kept. Its calibration carries the reconstructed pitch.
	 */
	public static ImagePlus reconstruct (ImagePlus sparse, Settings settings) {
		validateVolume(sparse);
		settings.validate();
		int w = sparse.getWidth();
		int h = sparse.getHeight();
		int n = sparse.getStackSize();
		long span = (n - 1L) * settings.factor + 1;
		if (span > Integer.MAX_VALUE || (long) w * h > Integer.MAX_VALUE)
			throw new IllegalArgumentException("Output dimensions exceed ImageJ limits.");
		int depth = (int) span;
		int nz = paddedSize(depth, settings.padding);
		int ny = paddedSize(h, settings.padding);
		int batch = settings.gpu ? Math.max(1, Math.min(Math.min(16, w), (int) (8388608L / ((long) nz * ny)))) : 1;
		long estimate = 4L * w * h * depth + 4L * w * h * n + 24L * nz * ny * batch;
		long available = Runtime.getRuntime().maxMemory()
				- (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
		if (estimate > available * 0.8)
			throw new IllegalArgumentException("Reconstruction needs approximately " + (estimate / 1048576)
					+ " MiB of additional working memory. Increase Fiji memory or use a smaller raw ROI.");
		float[] mask = weights(nz, ny, settings);
		float[][] source = new float[n][];
		for (int z = 0; z < n; z++) {
			source[z] = (float[]) sparse.getStack().getProcessor(z + 1).convertToFloatProcessor().getPixels();
			for (float value : source[z])
				if (!Float.isFinite(value)) throw new IllegalArgumentException("Input contains NaN or infinite intensities.");
		}
		ImageStack stack = new ImageStack(w, h);
		float[][] output = new float[depth][];
		for (int z = 0; z < depth; z++) {
			output[z] = new float[w * h];
			stack.addSlice(new FloatProcessor(w, h, output[z]));
		}
		FloatFFT_2D fft = settings.gpu ? null : new FloatFFT_2D(nz, ny);
		AliasingGpuFilter gpu = settings.gpu ? new AliasingGpuFilter(nz, ny, batch, mask) : null;
		float[] work = new float[2 * nz * ny * batch];
		int pad = settings.padding;
		try {
			for (int x = 0; x < w; x += batch) {
				checkCancelled();
				int sections = Math.min(batch, w - x);
				Arrays.fill(work, 0);
				// Comb: the acquired planes land on every factor-th fine plane, the rest stay zero.
				for (int b = 0; b < sections; b++)
					for (int z = 0; z < n; z++)
						for (int y = 0; y < h; y++)
							work[2 * ((b * nz + pad + z * settings.factor) * ny + pad + y)] =
									(float) (source[z][y * w + x + b] - settings.background);
				if (gpu != null) {
					gpu.filter(work);
				} else {
					fft.complexForward(work);
					for (int i = 0; i < mask.length; i++) {
						work[2 * i] *= mask[i];
						work[2 * i + 1] *= mask[i];
					}
					fft.complexInverse(work, true);
				}
				// The comb holds one sample in factor, so the copy kept has 1/factor of the amplitude.
				for (int b = 0; b < sections; b++)
					for (int z = 0; z < depth; z++)
						for (int y = 0; y < h; y++)
							output[z][y * w + x + b] = (float) (work[2 * ((b * nz + z + pad) * ny + y + pad)]
									* settings.factor + settings.background);
				IJ.showProgress(Math.min(x + batch, w), w);
			}
		} finally {
			if (gpu != null) gpu.close();
		}
		ImagePlus result = new ImagePlus(sparse.getShortTitle() + "-alias-recovered-raw", stack);
		Calibration cal = sparse.getCalibration().copy();
		cal.pixelWidth = settings.pixelUm;
		cal.pixelHeight = settings.pixelUm;
		cal.pixelDepth = settings.fineStepUm();
		cal.zOrigin *= settings.factor;
		cal.setUnit("um");
		result.setCalibration(cal);
		result.setProperty("Info", "OPM aliasing reconstruction; RAW oblique grid, not Cartesian Z.\n"
				+ "source=" + sparse.getTitle()
				+ "\nfactor=" + settings.factor
				+ "\nacquired_step_um=" + settings.acquiredStepUm
				+ "\nreconstructed_step_um=" + settings.fineStepUm()
				+ "\npixel_um=" + settings.pixelUm
				+ "\nangle_deg=" + settings.angleDegrees
				+ "\nbackground=" + settings.background
				+ "\ntaper=" + settings.taper
				+ "\npadding=" + settings.padding
				+ "\nbackend=" + (settings.gpu ? "CLIJ2-FFT" : "JTransforms")
				+ "\nmask=|wrap(f_scan - fine_step/(pixel*cos(angle))*f_camera_y)| < 1/(2*factor), wrap to the nearest integer\n"
				+ "No tail extrapolation or negative-value clipping. Validate boundaries and PSF before quantitative use.");
		result.setProperty("opm.aliasing.raw", Boolean.TRUE);
		return result;
	}

	/** Keep every factor-th plane from phase on; the calibration keeps the planes where they were. */
	public static ImagePlus decimate (ImagePlus dense, int factor, int phase) {
		validateVolume(dense);
		if (factor < 1 || phase < 0 || phase >= factor || phase + factor >= dense.getStackSize())
			throw new IllegalArgumentException("Factor/phase must retain at least two planes.");
		ImageStack stack = new ImageStack(dense.getWidth(), dense.getHeight());
		for (int z = phase; z < dense.getStackSize(); z += factor)
			stack.addSlice(dense.getStack().getProcessor(z + 1).duplicate());
		ImagePlus result = new ImagePlus(dense.getShortTitle() + "-every-" + factor + "-phase-" + phase, stack);
		Calibration cal = dense.getCalibration().copy();
		cal.pixelDepth *= factor;
		cal.zOrigin = (cal.zOrigin - phase) / factor;
		result.setCalibration(cal);
		result.setProperty("Info", "Even raw-plane subset; factor=" + factor + "; zero-based phase=" + phase
				+ "; source=" + dense.getTitle());
		return result;
	}

	/** Linear interpolation along the raw scan axis, the baseline the reconstruction is compared with. */
	public static ImagePlus interpolate (ImagePlus sparse, int factor) {
		validateVolume(sparse);
		if (factor < 1 || factor > 64) throw new IllegalArgumentException("Factor must be 1-64.");
		int depth = (sparse.getStackSize() - 1) * factor + 1;
		ImageStack stack = new ImageStack(sparse.getWidth(), sparse.getHeight());
		for (int z = 0; z < depth; z++) {
			int lo = z / factor;
			int hi = Math.min(lo + 1, sparse.getStackSize() - 1);
			float[] a = (float[]) sparse.getStack().getProcessor(lo + 1).convertToFloatProcessor().getPixels();
			float[] b = (float[]) sparse.getStack().getProcessor(hi + 1).convertToFloatProcessor().getPixels();
			float t = (z % factor) / (float) factor;
			float[] value = new float[a.length];
			for (int i = 0; i < value.length; i++) value[i] = a[i] * (1 - t) + b[i] * t;
			stack.addSlice(new FloatProcessor(sparse.getWidth(), sparse.getHeight(), value));
		}
		ImagePlus result = new ImagePlus("Raw-Z linear interpolation", stack);
		Calibration cal = sparse.getCalibration().copy();
		cal.pixelDepth /= factor;
		cal.zOrigin *= factor;
		result.setCalibration(cal);
		return result;
	}

	/** A dense raw bead's kX = 0 spectrum section, and how coarse a scan its support allows. */
	static final class Spectrum {
		/** log10 of amplitude over DC, zero frequency centred; ny wide, nz high. */
		final float[] logAmplitude;
		final int ny;
		final int nz;
		/** Largest band distance of a sample at or above the threshold, cycles per dense scan sample. */
		final double support;

		Spectrum (float[] logAmplitude, int ny, int nz, double support) {
			this.logAmplitude = logAmplitude;
			this.ny = ny;
			this.nz = nz;
			this.support = support;
		}

		/** The largest factor m whose half-band 1/(2m) still holds the support. */
		double factorBound () {
			return support > 0 ? 0.5 / support : Double.POSITIVE_INFINITY;
		}
	}

	/**
	 * The kX = 0 section of a dense raw bead's spectrum, and the support above a threshold.
	 * <p>
	 * settings.acquiredStepUm is the DENSE step here and settings.factor is ignored. The band
	 * distance is the reconstruction's own, wrapped the same way, so the bound it gives is the
	 * one the reconstruction would honour. The DC term is the integrated signal: a background
	 * set too high makes it negative, and that is refused rather than normalised by its size.
	 */
	static Spectrum spectrum (ImagePlus bead, Settings settings, double threshold) {
		validateVolume(bead);
		settings.validate();
		if (!(threshold > 0 && threshold < 1))
			throw new IllegalArgumentException("Threshold must be between 0 and 1.");
		int w = bead.getWidth();
		int h = bead.getHeight();
		int n = bead.getStackSize();
		int ny = paddedSize(h, 32);
		int nz = paddedSize(n, 32);
		if (2L * ny * nz > Integer.MAX_VALUE || 12L * ny * nz > Runtime.getRuntime().maxMemory() / 2)
			throw new IllegalArgumentException("Use a smaller isolated bead ROI for OTF inspection.");
		float[] section = new float[2 * ny * nz];
		for (int z = 0; z < n; z++) {
			ImageProcessor plane = bead.getStack().getProcessor(z + 1);
			for (int y = 0; y < h; y++) {
				double sum = 0;
				for (int x = 0; x < w; x++) {
					float value = plane.getf(x, y);
					if (!Float.isFinite(value)) throw new IllegalArgumentException("Input contains NaN or infinite intensities.");
					sum += value - settings.background;
				}
				section[2 * (z * ny + y)] = (float) sum;
			}
		}
		new FloatFFT_2D(nz, ny).complexForward(section);
		double dc = section[0];
		if (!(dc > 0))
			throw new IllegalArgumentException("The bead has no positive integrated signal above the background; "
					+ "check the background value.");
		double slope = settings.acquiredStepUm / (settings.pixelUm * Math.cos(Math.toRadians(settings.angleDegrees)));
		float[] display = new float[nz * ny];
		double support = 0;
		for (int z = 0; z < nz; z++) {
			for (int y = 0; y < ny; y++) {
				int i = z * ny + y;
				double amplitude = Math.hypot(section[2 * i], section[2 * i + 1]) / dc;
				display[((z + nz / 2) % nz) * ny + (y + ny / 2) % ny] = (float) Math.log10(Math.max(amplitude, 1e-6));
				if (amplitude >= threshold)
					support = Math.max(support, bandDistance(frequency(z, nz), frequency(y, ny), slope));
			}
		}
		return new Spectrum(display, ny, nz, support);
	}

	static void checkCancelled () {
		if (Shutdown.stopping())
			throw new CancellationException("Aliasing reconstruction cancelled: " + Shutdown.reason() + ".");
	}
}
