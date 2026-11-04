package de.embl.iclm;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.measure.ResultsTable;
import ij.plugin.PlugIn;
import ij.process.FloatProcessor;
import java.util.concurrent.CancellationException;

/**
 * Fiji entry points for raw-volume aliasing reconstruction and acquisition validation.
 *
 * <p>Five commands under Aliasing Resampling, selected by the argument in plugins.config:
 * reconstruct, subset, benchmark (test a dense raw volume), otf (inspect a bead's spectrum)
 * and plan. The numerical work is in {@link AliasingReconstruction}; this class holds the
 * dialogs and what is shown or logged.
 */
public class AliasingResampling implements PlugIn {

	@Override
	public void run (String arg) {
		Debug.commandStarted("Aliasing Resampling > " + arg);
		try {
			if ("plan".equals(arg)) {
				plan();
				return;
			}
			ImagePlus input = WindowManager.getCurrentImage();
			AliasingReconstruction.validateVolume(input);
			Shutdown.begin();
			if ("subset".equals(arg)) {
				subset(input);
				return;
			}
			if ("otf".equals(arg)) {
				otf(input);
				return;
			}
			reconstructOrBenchmark(input, "benchmark".equals(arg));
		} catch (CancellationException e) {
			IJ.showStatus(e.getMessage());
		} catch (Exception | LinkageError e) {
			IJ.handleException(e);
			IJ.error("Aliasing Resampling", e.getMessage() == null ? e.toString() : e.getMessage());
		}
	}

	private void reconstructOrBenchmark (ImagePlus input, boolean benchmark) {
		AliasingMetadata metadata = AliasingMetadata.from(input);
		GenericDialog gd = new OpmDialog(benchmark
				? "Aliasing Resampling - Test dense raw volume"
				: "Aliasing Resampling - Reconstruct raw volume");
		gd.addMessage("Input: " + input.getTitle() + "\nRaw oblique volume, before deskew or deconvolution.\n"
				+ (benchmark
						? "Tests factors 2 through the selected maximum, all slice offsets."
						: "Input must already be sparsely acquired. Factor = acquired step / target step."));
		gd.addMessage(metadata.note);
		gd.addNumericField("Camera pixel at object (um)", metadata.pixel, 4);
		gd.addNumericField(benchmark ? "Dense scan step (um)" : "Acquired scan step (um)", metadata.step, 4);
		gd.addNumericField("OPM angle (degrees)", metadata.angle, 2);
		gd.addNumericField(benchmark ? "Maximum subset factor" : "Integer reconstruction factor", 2, 0);
		gd.addNumericField("Camera background (intensity units)", 0, 2);
		gd.addNumericField("Padding (fine-grid pixels)", 32, 0);
		gd.addNumericField("Cosine edge fraction (0 = top hat)", 0, 2);
		gd.addChoice("FFT backend", new String[] { "CPU (JTransforms)", "GPU (CLIJ2-FFT, experimental)" }, "CPU (JTransforms)");
		if (!benchmark) gd.addCheckbox("Also show deskewed volume", false);
		gd.addMessage("Recovery is conditional on non-overlapping optical spectra.\n"
				+ "Output remains a raw grid; use acquired step / factor for deskew.\n"
				+ "Deskew interpolates along the scan: a finer target (about 0.13 um) loses less.\n"
				+ "Use a representative ROI for tests. Escape cancels; edges need a guard region.");
		gd.addHelp(Help.aliasingResampling);
		gd.showDialog();
		if (gd.wasCanceled()) return;
		AliasingReconstruction.Settings settings = new AliasingReconstruction.Settings();
		settings.pixelUm = gd.getNextNumber();
		settings.acquiredStepUm = gd.getNextNumber();
		settings.angleDegrees = gd.getNextNumber();
		settings.factor = integer(gd.getNextNumber(), "Factor");
		settings.background = gd.getNextNumber();
		settings.padding = integer(gd.getNextNumber(), "Padding");
		settings.taper = gd.getNextNumber();
		settings.gpu = gd.getNextChoiceIndex() == 1;
		settings.validate();
		if (benchmark) {
			benchmark(input, settings);
			return;
		}
		boolean deskew = gd.getNextBoolean();
		long start = System.nanoTime();
		ImagePlus result = AliasingReconstruction.reconstruct(input, settings);
		result.show();
		IJ.log("Aliasing Resampling: " + (System.nanoTime() - start) / 1e9 + " s; " + result.getStackSize()
				+ " raw planes, scan step " + settings.fineStepUm() + " um. Inspect boundary ringing and validate the PSF.");
		if (!deskew) return;
		// The float path, not the virtual unsigned-short one: the reconstruction carries signed ringing.
		ImagePlus lab = CPU.transform(result,
				Transform.deskew(settings.fineStepUm(), settings.pixelUm, settings.angleDegrees, result.getHeight()), false);
		if (lab == null) throw new IllegalStateException("Deskew failed; the recovered raw volume is still available.");
		lab.setTitle(input.getShortTitle() + "-alias-recovered-deskewed");
		lab.getCalibration().pixelWidth = settings.pixelUm;
		lab.getCalibration().pixelHeight = settings.pixelUm;
		lab.getCalibration().pixelDepth = settings.pixelUm;
		lab.getCalibration().setUnit("um");
		lab.show();
	}

	static int integer (double value, String label) {
		if (!Double.isFinite(value) || value != Math.rint(value) || value < 0 || value > Integer.MAX_VALUE)
			throw new IllegalArgumentException(label + " must be a non-negative integer.");
		return (int) value;
	}

	private void subset (ImagePlus input) {
		GenericDialog gd = new OpmDialog("Aliasing Resampling - Create raw slice subset");
		gd.addNumericField("Keep every Nth plane", 2, 0);
		gd.addNumericField("First-plane offset (0 based)", 0, 0);
		gd.addHelp(Help.aliasingResampling);
		gd.showDialog();
		if (gd.wasCanceled()) return;
		int factor = integer(gd.getNextNumber(), "Factor");
		int offset = integer(gd.getNextNumber(), "Offset");
		AliasingReconstruction.decimate(input, factor, offset).show();
	}

	private void benchmark (ImagePlus dense, AliasingReconstruction.Settings settings) {
		int maximum = settings.factor;
		if (maximum < 2 || maximum > 16 || dense.getStackSize() < 2 * maximum)
			throw new IllegalArgumentException("Choose maximum factor 2-16 and a stack with at least twice that many planes.");
		double denseStep = settings.acquiredStepUm;
		ResultsTable table = new ResultsTable();
		for (int m = 2; m <= maximum; m++) {
			for (int phase = 0; phase < m; phase++) {
				AliasingReconstruction.checkCancelled();
				ImagePlus sparse = AliasingReconstruction.decimate(dense, m, phase);
				settings.factor = m;
				settings.acquiredStepUm = denseStep * m;
				for (int method = 0; method < 2; method++) {
					long start = System.nanoTime();
					ImagePlus recovered = method == 0
							? AliasingReconstruction.interpolate(sparse, m)
							: AliasingReconstruction.reconstruct(sparse, settings);
					double seconds = (System.nanoTime() - start) / 1e9;
					try {
						double[] values = errors(dense, recovered, phase, settings.background, Math.max(4, 2 * m));
						table.incrementCounter();
						table.addValue("factor", m);
						table.addValue("phase", phase);
						table.addValue("method", method == 0 ? "linear" : "fourier");
						table.addValue("step_um", denseStep * m);
						table.addValue("NRMSE", values[0]);
						table.addValue("bias", values[1]);
						table.addValue("voxels", values[2]);
						table.addValue("seconds", seconds);
					} finally {
						recovered.close();
					}
				}
				sparse.close();
				table.show("Aliasing dense-reference comparison");
			}
		}
		IJ.log("Aliasing benchmark: NRMSE = RMS(error)/RMS(reference-background), guarded raw-volume interior. "
				+ "A dense measured volume is a noisy reference, not optical ground truth. "
				+ "Save the Results table; validate PSF widths separately.");
	}

	/** NRMSE, bias and voxel count of result against dense, over the interior inside guard. */
	static double[] errors (ImagePlus dense, ImagePlus result, int phase, double background, int guard) {
		int w = result.getWidth();
		int h = result.getHeight();
		int d = result.getStackSize();
		if (w <= 2 * guard || h <= 2 * guard || d <= 2 * guard)
			throw new IllegalArgumentException("ROI too small for benchmark boundary guard.");
		double error = 0;
		double power = 0;
		double bias = 0;
		long count = 0;
		for (int z = guard; z < d - guard; z++) {
			FloatProcessor a = dense.getStack().getProcessor(z + phase + 1).convertToFloatProcessor();
			FloatProcessor b = result.getStack().getProcessor(z + 1).convertToFloatProcessor();
			for (int y = guard; y < h - guard; y++) {
				for (int x = guard; x < w - guard; x++) {
					double reference = a.getf(x, y) - background;
					double delta = b.getf(x, y) - a.getf(x, y);
					error += delta * delta;
					power += reference * reference;
					bias += delta;
					count++;
				}
			}
		}
		return new double[] { Math.sqrt(error / Math.max(power, 1e-30)), bias / count, count };
	}

	private void otf (ImagePlus input) {
		GenericDialog gd = new OpmDialog("Aliasing Resampling - Inspect raw bead PSF spectrum");
		AliasingMetadata metadata = AliasingMetadata.from(input);
		gd.addMessage("Use a tight, isolated bead ROI from a dense RAW volume.\n"
				+ "The displayed section is kX=0 (sum over X), not the full 3D OTF.\n"
				+ "Bead diameter, background and noise limit the support estimate.");
		gd.addMessage(metadata.note);
		gd.addNumericField("Camera pixel at object (um)", metadata.pixel, 4);
		gd.addNumericField("Dense scan step (um)", metadata.step, 4);
		gd.addNumericField("OPM angle (degrees)", metadata.angle, 2);
		gd.addNumericField("Background intensity", 0, 2);
		gd.addNumericField("Relative amplitude threshold", 0.10, 3);
		gd.addHelp(Help.aliasingResampling);
		gd.showDialog();
		if (gd.wasCanceled()) return;
		AliasingReconstruction.Settings settings = new AliasingReconstruction.Settings();
		settings.factor = 1;
		settings.pixelUm = gd.getNextNumber();
		settings.acquiredStepUm = gd.getNextNumber();
		settings.angleDegrees = gd.getNextNumber();
		settings.background = gd.getNextNumber();
		double threshold = gd.getNextNumber();
		AliasingReconstruction.Spectrum spectrum = AliasingReconstruction.spectrum(input, settings, threshold);
		new ImagePlus("Raw PSF kX=0 spectrum (log10 amplitude/DC)",
				new FloatProcessor(spectrum.ny, spectrum.nz, spectrum.logAmplitude)).show();
		IJ.log("PSF spectrum: threshold=" + threshold + ", q support=" + spectrum.support
				+ " cycles/dense scan sample; slab factor bound=" + spectrum.factorBound()
				+ ". Section-only diagnostic; inspect multiple beads and 3D support before choosing a step.");
	}

	private void plan () {
		GenericDialog gd = new OpmDialog("Aliasing Resampling - Acquisition planner");
		AliasingMetadata metadata = AliasingMetadata.from(WindowManager.getCurrentImage());
		gd.addMessage("Conditional axial-slab bound for this toolset's geometry.\n"
				+ "Supply measured axial OTF support; a generic image spectrum is not an OTF.");
		gd.addMessage(metadata.note);
		gd.addNumericField("OPM angle (degrees)", metadata.angle, 2);
		gd.addNumericField("Measured axial OTF half support (cycles/um)", 1, 3);
		gd.addNumericField("Safety fraction of support bound", 0.8, 2);
		gd.addNumericField("Desired scan step (um)", 0.530, 4);
		gd.addNumericField("Scan extent (um)", 119.25, 2);
		gd.addNumericField("Exposure (ms)", metadata.exposure, 2);
		gd.addNumericField("Readout/settle overhead per plane (ms)", 0, 2);
		gd.addNumericField("Flyback per volume (ms)", 0, 2);
		gd.addCheckbox("Galvo moves continuously during exposure", true);
		gd.addNumericField("Measured frame period (ms; 0 = exposure + overhead)", 0, 2);
		gd.addHelp(Help.aliasingResampling);
		gd.showDialog();
		if (gd.wasCanceled()) return;
		double angle = gd.getNextNumber();
		double kz = gd.getNextNumber();
		double safety = gd.getNextNumber();
		double step = gd.getNextNumber();
		double extent = gd.getNextNumber();
		double exposure = gd.getNextNumber();
		double overhead = gd.getNextNumber();
		double flyback = gd.getNextNumber();
		boolean continuous = gd.getNextBoolean();
		double framePeriod = gd.getNextNumber();
		for (double value : new double[] { angle, kz, safety, step, extent, exposure, overhead, flyback })
			if (!Double.isFinite(value)) throw new IllegalArgumentException("All planner values must be finite.");
		if (angle <= 0 || angle >= 89.9 || kz <= 0 || safety <= 0 || safety > 1 || step <= 0 || extent <= 0
				|| exposure <= 0 || overhead < 0 || flyback < 0)
			throw new IllegalArgumentException("Invalid planning values.");
		if (!Double.isFinite(framePeriod) || framePeriod < 0 || (framePeriod > 0 && framePeriod < exposure))
			throw new IllegalArgumentException("Frame period must be zero or at least the exposure time.");
		if (framePeriod == 0) framePeriod = exposure + overhead;
		double bound = 1 / (2 * kz * Math.tan(Math.toRadians(angle)));
		long planes = (long) Math.ceil(extent / step) + 1;
		ResultsTable table = new ResultsTable();
		table.incrementCounter();
		table.addValue("slab_bound_um", bound);
		table.addValue("conservative_step_um", bound * safety);
		table.addValue("requested_step_um", step);
		table.addValue("within_bound", step <= bound * safety ? "yes (conditional)" : "no");
		table.addValue("planes", planes);
		table.addValue("volume_seconds", (planes * framePeriod + flyback) / 1000);
		double blur = continuous ? step * exposure / framePeriod : 0;
		table.addValue("continuous_blur_length_um", blur);
		table.addValue("motion_OTF_first_zero_cycles_per_um", blur > 0 ? 1 / blur : Double.NaN);
		table.addValue("plane_normal_spacing_um", step * Math.sin(Math.toRadians(angle)));
		table.show("Aliasing acquisition plan");
		IJ.log("Exposure is a user input, not inferred from aliasing. The uniform continuous-scan model uses "
				+ "L=step*exposure/framePeriod along laboratory Y. The motion OTF sinc has its first zero at 1/L. "
				+ "Compare this with measured Y bandwidth; the axial alias bound does not protect against motion blur. "
				+ "Use a measured frame period when readout and exposure overlap.");
	}
}
