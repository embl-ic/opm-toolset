package de.embl.iclm;

import edu.emory.mathcs.jtransforms.fft.FloatFFT_2D;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Bounded raw-reference experiment. Only original dense sample locations are scored. */
final class AliasingQualitySweep {
	static final String OUTPUT = "opm.aliasing.advisor.output";
	static final class Snapshot {
		final ImagePlus image;
		final String hash, sourceTitle;
		final int parentWidth, parentHeight, parentDepth;
		final long saturated;
		Snapshot(ImagePlus image, String hash, String title, int w, int h, int d, long saturated) {
			this.image = image; this.hash = hash; sourceTitle = title;
			parentWidth = w; parentHeight = h; parentDepth = d; this.saturated = saturated;
		}
		void close() { image.flush(); }
	}
	static void preflight(AliasingAdvisorSettings s) {
		int padding = AliasingStepModel.padding(s);
		long fineDepth = Math.addExact(Math.multiplyExact((long) s.depth - 1, s.refinement), 1);
		long voxels = Math.multiplyExact(Math.multiplyExact((long) s.width, s.height), fineDepth);
		int nz = AliasingReconstruction.paddedSize(Math.toIntExact(fineDepth), padding);
		int ny = AliasingReconstruction.paddedSize(s.height, padding);
		long fft = Math.multiplyExact((long) nz, ny);
		if (voxels > Integer.MAX_VALUE || fft > Integer.MAX_VALUE / (s.gpu ? 32 : 2))
			throw new IllegalArgumentException("ROI/refinement exceeds array limits");
		// Snapshot, mask, smoothed reference, noise signal map, propagated floors, candidate, linear,
		// preview/error copies and FFT/backend scratch.
		long bytes = Math.addExact(Math.multiplyExact(voxels, 56), Math.multiplyExact(fft, s.gpu ? 512 : 40));
		Runtime runtime = Runtime.getRuntime();
		long available = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
		if (bytes > available * .6) throw new IllegalArgumentException("Advisor needs about " + bytes / 1048576
				+ " MiB including previews; reduce ROI or refinement (available " + available / 1048576 + " MiB)");
	}
	static Snapshot snapshot(ImagePlus input, AliasingAdvisorSettings s) {
		s.validate(); AliasingReconstruction.validateVolume(input); preflight(s);
		if (!s.rawConfirmed) throw new IllegalArgumentException("Confirm that the selected source is an unchanged raw, static volume");
		if (input.getProperty(OUTPUT) != null) throw new IllegalArgumentException("An advisor preview is not an original raw reference");
		String info = String.valueOf(input.getProperty("Info")).toLowerCase(java.util.Locale.ROOT);
		if (info.contains("aliasing reconstruction") || input.getTitle().endsWith("-alias-recovered-deskewed"))
			throw new IllegalArgumentException("Select original raw data, not a processed reconstruction");
		int w = input.getWidth(), h = input.getHeight(), d = input.getStackSize();
		if ((long)s.x + s.width > w || (long)s.y + s.height > h || (long)s.z + s.depth > d)
			throw new IllegalArgumentException("ROI is outside selected source; use its current ROI or adjust bounds");
		if (!input.lockSilently()) throw new IllegalArgumentException("Source is busy; use a stable raw volume");
		Calibration before = input.getCalibration().copy();
		ImageStack stack = new ImageStack(s.width, s.height);
		long saturated = 0;
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			ByteBuffer bytes = ByteBuffer.allocate(Math.multiplyExact(Math.multiplyExact(s.width, s.height), 4));
			for (int z = 0; z < s.depth; z++) {
				AliasingReconstruction.checkCancelled();
				ImageProcessor plane = input.getStack().getProcessor(s.z + z + 1);
				float[] pixels = new float[s.width * s.height]; bytes.clear();
				for (int y = 0; y < s.height; y++) for (int x = 0; x < s.width; x++) {
					float value = plane.getf(s.x + x, s.y + y);
					if (!Float.isFinite(value)) throw new IllegalArgumentException("Source contains nonfinite pixels");
					if ((input.getBitDepth() == 16 && value >= 65535) || (input.getBitDepth() == 8 && value >= 255)) saturated++;
					pixels[y * s.width + x] = value; bytes.putFloat(value);
				}
				digest.update(bytes.array()); stack.addSlice(new FloatProcessor(s.width, s.height, pixels));
			}
			Calibration now = input.getCalibration();
			if (input.getWidth() != w || input.getHeight() != h || input.getStackSize() != d
					|| now.pixelWidth != before.pixelWidth || now.pixelHeight != before.pixelHeight || now.pixelDepth != before.pixelDepth
					|| input.getNChannels() != 1 || input.getNFrames() != 1)
				throw new IllegalArgumentException("Source changed while copying; use a static volume");
			StringBuilder hash = new StringBuilder(); for (byte b : digest.digest()) hash.append(String.format("%02x", b & 255));
			ImagePlus copy = new ImagePlus(input.getTitle() + " reference ROI", stack);
			Calibration cal = before.copy(); cal.pixelWidth = cal.pixelHeight = s.pixel; cal.pixelDepth = s.step; cal.setUnit("um");
			cal.xOrigin -= s.x; cal.yOrigin -= s.y; cal.zOrigin -= s.z; copy.setCalibration(cal);
			return new Snapshot(copy, hash.toString(), input.getTitle(), w, h, d, saturated);
		} catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
		finally { input.unlock(); }
	}
	static final class Stats {
		double error2, power, delta, referenceSum, predictionSum, reference2, prediction2, product;
		/**
		 * The expected noise in error2 (floor) and in power (the reference's own), split by what
		 * multiplies it. The noise model is v = sigma^2 + gain * S and every term is linear in v, so
		 * read* is the coefficient of sigma^2 and shot* that of the gain: any fit applies afterwards.
		 */
		double readFloor, shotFloor, readNoise, shotNoise;
		long count;
		void add(double ref, double prediction, double background) { add(ref, prediction, background, 0, 0, 0, 0); }
		void add(double ref, double prediction, double background, double readFloor, double shotFloor, double readNoise, double shotNoise) {
			double difference = prediction - ref;
			error2 += difference * difference; power += (ref - background) * (ref - background); delta += difference;
			referenceSum += ref; predictionSum += prediction; reference2 += ref * ref;
			prediction2 += prediction * prediction; product += ref * prediction; count++;
			this.readFloor += readFloor; this.shotFloor += shotFloor; this.readNoise += readNoise; this.shotNoise += shotNoise;
		}
		void merge(Stats b) {
			error2 += b.error2; power += b.power; delta += b.delta; count += b.count;
			referenceSum += b.referenceSum; predictionSum += b.predictionSum; reference2 += b.reference2;
			prediction2 += b.prediction2; product += b.product;
			readFloor += b.readFloor; shotFloor += b.shotFloor; readNoise += b.readNoise; shotNoise += b.shotNoise;
		}
		double nrmse() { return count > 0 && power > 0 ? Math.sqrt(error2 / power) : Double.NaN; }
		double floor(NoiseModel.Fit fit) { return fit == null ? Double.NaN : fit.variance() * readFloor + fit.gain * shotFloor; }
		/**
		 * Held-out error with the expected noise floor removed, over the reference's noise-free power:
		 * sqrt(max(0, SSE - floor) / (power - reference noise)). An estimate: it is only as good as the
		 * fit, and the subtraction truncates at zero. NaN without a fit.
		 */
		double excess(NoiseModel.Fit fit) {
			if (fit == null) return Double.NaN;
			double clean = power - fit.variance() * readNoise - fit.gain * shotNoise;
			return count > 0 && clean > 0 ? Math.sqrt(Math.max(0, error2 - floor(fit)) / clean) : Double.NaN;
		}
		double noiseShare(NoiseModel.Fit fit) { return fit != null && error2 > 0 ? floor(fit) / error2 : Double.NaN; }
		double bias() { return count > 0 ? delta / count : Double.NaN; }
		double correlation() {
			double a = reference2 - referenceSum * referenceSum / Math.max(1, count);
			double b = prediction2 - predictionSum * predictionSum / Math.max(1, count);
			return a > 1e-12 && b > 1e-12 ? (product - referenceSum * predictionSum / count) / Math.sqrt(a * b) : Double.NaN;
		}
	}
	static final class Row {
		int factor, phase, padding, fftRows, fftScan;
		String method;
		double stepUm, seconds;
		final Stats acquired = new Stats(), heldout = new Stats();
	}
	static final class Aggregate {
		int factor, phases;
		String method, verdict;
		final Stats acquired = new Stats(), heldout = new Stats();
		double worstPhase = 0;
		boolean phaseUndefined;
		/**
		 * Estimates, never verdicts: the noise-corrected excess with the accepted fit and with the band
		 * check's fit, its worst phase, and sqrt(excess^2 - excess(factor 2)^2) - the increase in
		 * estimated error energy over factor 2, which a cross term keeps from being "added error".
		 */
		double excess = Double.NaN, excessCheck = Double.NaN, worstExcess = Double.NaN, increase = Double.NaN;
		boolean usable() { return phases == factor && !phaseUndefined; }
	}
	/**
	 * Per-voxel noise variance of the reference, v = sigma^2 + gain * S, S the lightly smoothed
	 * signal. Fitted to the reference's own noise-only band - scan frequencies outside the slab
	 * the entered optics allow - so shot noise is included without a camera calibration. Whether
	 * that band really holds noise alone is an assumption, so a fit is used only once the entered,
	 * verified background sigma and a second fit further out in the band both agree with it.
	 */
	static final class NoiseModel {
		static final class Fit {
			double sigma, gain, band = Double.NaN, fraction = Double.NaN;
			long voxels;
			double variance() { return sigma * sigma; }
		}
		Fit fit, check;
		boolean accepted;
		double meanSignal = Double.NaN;
		String status = "Not estimated";
		transient float[][] signal;
		/** The fit estimates are made with, or null when there are none. */
		Fit used() { return accepted ? fit : null; }
		Fit usedCheck() { return accepted ? check : null; }
	}
	static final class Run {
		String version = "OPM Toolset 2.1.6 / Z-Step Advisor schema " + AliasingAdvisorSettings.SCHEMA, created = java.time.Instant.now().toString();
		/** What a Pass means and how the estimates beside it were made, recorded with every export. */
		String criterion = "absolute: raw held-out NRMSE <= ceiling pooled and in every phase; range contiguous from factor 2";
		String estimator = "exploratory: excess = sqrt(max(0, SSE - floor) / (energy - reference noise)); v = sigma^2 + gain*S fitted to the "
				+ "noise-only band, accepted only if sigma agrees with the verified background sigma and a second band within 25%; Fourier floor "
				+ "propagated through the reconstruction kernel; mask from a 1-voxel Gaussian-smoothed reference";
		String backend = "CPU JTransforms";
		AliasingAdvisorSettings settings;
		String sourceTitle, snapshotSha256, status = "Running", adequacy;
		int parentWidth, parentHeight, parentDepth, first, last;
		long signalVoxels, saturated;
		double maskThreshold;
		List<Row> rows = new ArrayList<Row>();
		List<Aggregate> aggregates = new ArrayList<Aggregate>();
		NoiseModel noise = new NoiseModel();
		transient Snapshot snapshot;
		transient boolean[][] mask;
		boolean complete;
		Run(Snapshot snapshot, AliasingAdvisorSettings settings) {
			this.snapshot = snapshot; this.settings = settings.copy(); sourceTitle = snapshot.sourceTitle;
			snapshotSha256 = snapshot.hash; parentWidth = snapshot.parentWidth; parentHeight = snapshot.parentHeight;
			parentDepth = snapshot.parentDepth; saturated = snapshot.saturated;
		}
		void release() { if (snapshot != null) snapshot.close(); snapshot = null; mask = null; noise.signal = null; }
		private List<Aggregate> fourier() {
			List<Aggregate> list = new ArrayList<Aggregate>();
			for (Aggregate a : aggregates) if (a.method.equals("Fourier")) list.add(a);
			return list;
		}
		/**
		 * The contiguous passing run of factors from 2 - m = 1 is the reference itself - plus any
		 * isolated passes, listed apart. When factor 2 was not tested there is no range to start, so
		 * passing factors are listed only. No row's verdict rests on another's.
		 */
		String recommendation() {
			if (!complete) return "Incomplete sweep: no complete tested range";
			if (!"Eligible".equals(adequacy)) return "Insufficient evidence: " + adequacy;
			List<Aggregate> fourier = fourier();
			int lastPassing = -1, i = 0;
			if (!fourier.isEmpty() && fourier.get(0).factor == 2)
				for (; i < fourier.size() && fourier.get(i).verdict.equals("Pass"); i++) lastPassing = fourier.get(i).factor;
			StringBuilder isolated = new StringBuilder();
			for (; i < fourier.size(); i++) if (fourier.get(i).verdict.equals("Pass")) isolated.append(isolated.length() == 0 ? "" : ", ").append(fourier.get(i).factor);
			String criterion = String.format(Locale.ROOT, "raw held-out NRMSE within %.1f%% pooled and in every phase", 100 * settings.maximumNrmse);
			if (lastPassing < 0 && isolated.length() == 0) {
				Aggregate lowest = null;
				for (Aggregate a : fourier) if (Double.isFinite(a.heldout.nrmse()) && (lowest == null || a.heldout.nrmse() < lowest.heldout.nrmse())) lowest = a;
				return "No tested step meets the chosen criterion (" + criterion + ")" + (lowest == null ? ""
						: String.format(Locale.ROOT, "; lowest pooled %.1f%% at factor %d", 100 * lowest.heldout.nrmse(), lowest.factor));
			}
			if (lastPassing < 0) return "Passing points only, not a range" + (fourier.get(0).factor == 2 ? "" : " (factor 2 not tested)")
					+ ": factors " + isolated + " (" + criterion + "); XYZ resolution not assessed";
			return String.format(Locale.ROOT, "Tested reference agreement: factors 2..%d (%.4f..%.4f um), %s; XYZ resolution not assessed%s",
					lastPassing, 2 * settings.step, lastPassing * settings.step, criterion,
					isolated.length() == 0 ? "" : "; isolated passes at factors " + isolated + " (not a range)");
		}
		/** The noise-corrected estimates in words. Never an acceptance; see {@link AliasingQualitySweep#aggregate}. */
		String estimate() {
			if (settings.syntheticNoiseFree && noise.accepted) return "Noise-free reference: the raw held-out NRMSE is the structural error itself";
			if (!noise.accepted) return "No noise-corrected estimate: " + noise.status;
			List<Aggregate> fourier = fourier();
			Aggregate two = !fourier.isEmpty() && fourier.get(0).factor == 2 && fourier.get(0).usable() && Double.isFinite(fourier.get(0).excess) ? fourier.get(0) : null;
			if (two == null) return "Noise-corrected excess per factor only: factor 2 not tested or incomplete, so no increase is estimated";
			Aggregate lowest = two;
			for (Aggregate a : fourier) if (a.usable() && Double.isFinite(a.excess) && a.excess < lowest.excess) lowest = a;
			double limit = settings.maximumIncrement;
			if (lowest.excess * lowest.excess < two.excess * two.excess - limit * limit)
				return String.format(Locale.ROOT, "Estimate not interpretable: the noise-corrected excess falls from %.3f at factor 2 to %.3f at factor %d, "
						+ "so factor 2 itself disagrees with the reference", two.excess, lowest.excess, lowest.factor);
			int through = 2;
			Aggregate stop = null;
			for (int i = 1; i < fourier.size() && stop == null; i++) {
				Aggregate a = fourier.get(i);
				if (a.usable() && Double.isFinite(a.increase) && a.increase <= limit) through = a.factor; else stop = a;
			}
			boolean measured = stop != null && stop.usable() && Double.isFinite(stop.increase);
			String rise = through > 2 ? String.format(Locale.ROOT, "the estimated error energy rises by at most %.1f%% over factor 2 through factor %d", 100 * limit, through)
					+ (stop == null ? "" : measured ? String.format(Locale.ROOT, ", then %.1f%% at factor %d", 100 * stop.increase, stop.factor)
							: ", then factor " + stop.factor + " has no estimate")
					: stop == null ? "no coarser factor tested"
					: measured ? String.format(Locale.ROOT, "the estimated error energy already rises by %.1f%% over factor 2 at factor %d", 100 * stop.increase, stop.factor)
					: "factor " + stop.factor + " has no estimate";
			return String.format(Locale.ROOT, "Exploratory, not an acceptance: noise-corrected excess %.3f at factor 2 (band check %.3f; not itself qualified); %s",
					two.excess, two.excessCheck, rise);
		}
	}
	static void prepare(Run run) {
		AliasingAdvisorSettings s = run.settings;
		int[] domain = AliasingStepModel.domain(s.depth, AliasingStepModel.factors(s), s.guard);
		run.first = domain[0]; run.last = domain[1];
		if (s.width <= 2 * s.guard || s.height <= 2 * s.guard) throw new IllegalArgumentException("ROI is smaller than the boundary guards");
		// The mask is read from a smoothed reference: one taken from the noisy voxels it then scores
		// keeps those whose noise happens to be high and biases the error it measures.
		float[][] smoothed = smooth(run, MASK_KERNEL);
		float[] distribution = new float[Math.multiplyExact(Math.multiplyExact(s.width - 2 * s.guard, s.height - 2 * s.guard), run.last - run.first + 1)];
		int index = 0;
		for (int z = run.first; z <= run.last; z++) {
			AliasingReconstruction.checkCancelled();
			for (int y = s.guard; y < s.height - s.guard; y++) for (int x = s.guard; x < s.width - s.guard; x++)
				distribution[index++] = smoothed[z][y * s.width + x];
		}
		Arrays.sort(distribution);
		run.maskThreshold = Math.max(s.syntheticNoiseFree ? 0 : s.sigmaGate * s.noiseSigma,
				s.maskFraction * distribution[(int) Math.floor(.995 * (distribution.length - 1))]);
		distribution = null;
		run.mask = new boolean[s.depth][];
		int signalPlanes = 0;
		for (int z = run.first; z <= run.last; z++) {
			boolean[] mask = new boolean[s.width * s.height]; run.mask[z] = mask;
			int count = 0;
			for (int y = s.guard; y < s.height - s.guard; y++) for (int x = s.guard; x < s.width - s.guard; x++) {
				int i = y * s.width + x;
				if (smoothed[z][i] > run.maskThreshold) { mask[i] = true; count++; }
			}
			run.signalVoxels += count; if (count > 0) signalPlanes++;
		}
		smoothed = null;
		run.adequacy = !s.geometryConfirmed ? "Reference geometry/source association unverified"
				: (!s.noiseKnown && !s.syntheticNoiseFree) ? "Background/noise unverified"
				: !s.syntheticNoiseFree && s.noiseSigma == 0 ? "Zero noise estimate; verify quantization or declare a noise-free synthetic reference"
				: run.signalVoxels < s.minimumSignal || signalPlanes < 3 ? "Insufficient foreground"
				: run.saturated > 0 ? "Saturated source pixels; inspect signal ROI" : "Eligible";
		estimateNoise(run);
	}

	/** Scan frequencies with |wrap(fv - s fu)| above this band hold noise only (signal sits below it). */
	static final double NOISE_BAND = .30;
	/** How far the fitted read sigma, and the noise at the scored signal between two bands, may differ. */
	static final double NOISE_AGREEMENT = .25;
	static final float[] SIGNAL_KERNEL = gaussian(.7, 2), MASK_KERNEL = gaussian(1, 4);

	/**
	 * Fit the {@link NoiseModel} and decide whether estimates may use it. Two of the review's
	 * counterexamples set the checks: a structure above the band the optics were assumed to allow
	 * was fitted as 88 ADU of read noise against a known 1 ADU, and removed as noise the whole error
	 * it should have measured. So the fit must agree with the background sigma the user verified,
	 * and with a second fit halfway to the band's edge, where any leaked signal weighs differently.
	 */
	static void estimateNoise(Run run) {
		AliasingAdvisorSettings s = run.settings;
		NoiseModel model = run.noise;
		model.signal = null; model.fit = model.check = null; model.accepted = false;
		if (s.syntheticNoiseFree) {
			// a declared noise-free reference has a zero floor; there is no noise band to fit
			model.fit = new NoiseModel.Fit(); model.accepted = true;
			model.status = "declared noise-free synthetic reference: zero noise floor";
			return;
		}
		double tan = Math.tan(Math.toRadians(s.angle));
		double band = Math.max(NOISE_BAND, 1.5 * s.step * tan * AliasingStepModel.support(s).kz);
		if (band > .45) {
			model.status = "dense step too coarse to leave a noise-only band";
			return;
		}
		model.fit = fitNoise(run, band);
		if (model.fit == null) { model.status = "noise fit failed"; return; }
		model.check = fitNoise(run, (band + .5) / 2);
		float[][] signal = smooth(run, SIGNAL_KERNEL);
		double sum = 0; long count = 0;
		for (int z = run.first; z <= run.last; z++) {
			float[] plane = signal[z];
			for (int i = 0; i < plane.length; i++) if (run.mask[z][i]) { sum += Math.max(0, plane[i]); count++; }
		}
		for (float[] plane : signal) for (int i = 0; i < plane.length; i++) plane[i] = Math.max(0, plane[i]);
		model.meanSignal = count > 0 ? sum / count : 0;
		String fitted = String.format(Locale.ROOT, "fitted read sigma %.3g ADU, gain %.3g per signal unit at band |q| > %.2f",
				model.fit.sigma, model.fit.gain, model.fit.band);
		if (!s.noiseKnown) model.status = fitted + "; not used: the background sigma it is checked against is unverified";
		else if (!(s.noiseSigma > 0)) model.status = fitted + "; not used: no background sigma to check it against";
		else if (Math.abs(model.fit.sigma / s.noiseSigma - 1) > NOISE_AGREEMENT)
			model.status = fitted + String.format(Locale.ROOT, "; not used: contradicts the verified background sigma %.3g ADU (the band may hold signal)", s.noiseSigma);
		else if (model.check == null) model.status = fitted + "; not used: the band check fit failed";
		else {
			double v = model.fit.variance() + model.fit.gain * model.meanSignal, w = model.check.variance() + model.check.gain * model.meanSignal;
			String check = String.format(Locale.ROOT, "noise at the mean scored signal %.3g at |q| > %.2f, %.3g at |q| > %.2f", v, model.fit.band, w, model.check.band);
			if (Math.abs(w / v - 1) > NOISE_AGREEMENT) model.status = fitted + "; not used: unstable across the band (" + check + ")";
			else { model.accepted = true; model.status = fitted + "; agrees with the background sigma and the band check (" + check + ")"; }
		}
		if (model.accepted) model.signal = signal;
	}

	/**
	 * Fit v = sigma^2 + gain * S to the residual above one noise band.
	 * <p>
	 * The band-limited residual r = h * noise has variance sum_k h_k^2 v(x - k): each voxel's
	 * noise is spread over its neighbours, so a local average of r^2 underestimates exactly at
	 * the bright voxels the mask keeps. Regressing r^2 on the signal spread by the same h^2, then
	 * applying the fit to each voxel's own lightly smoothed signal, does not. Validated on
	 * Gaussian-approximated shot noise over synthetic beads: gain 2.04 fitted for 2.0
	 * (scripts/aliasing_noise_floor_probe.py, local).
	 */
	private static NoiseModel.Fit fitNoise(Run run, double band) {
		AliasingAdvisorSettings s = run.settings;
		int w = s.width, h = s.height, d = s.depth, pad = 32;
		int nz = AliasingReconstruction.paddedSize(d, pad), ny = AliasingReconstruction.paddedSize(h, pad);
		if ((long) nz * ny > Integer.MAX_VALUE / 4) return null;
		double slope = s.step / (s.pixel * Math.cos(Math.toRadians(s.angle)));
		float[] keep = new float[nz * ny];
		double kept = 0;
		for (int z = 0; z < nz; z++) {
			for (int y = 0; y < ny; y++) {
				double q = AliasingReconstruction.bandDistance(AliasingReconstruction.frequency(z, nz), AliasingReconstruction.frequency(y, ny), slope);
				double c = AliasingReconstruction.bandDistance(AliasingReconstruction.frequency((nz - z) % nz, nz),
						AliasingReconstruction.frequency((ny - y) % ny, ny), slope);
				if (q > band && c > band) { keep[z * ny + y] = 1; kept++; }
			}
		}
		double fraction = kept / keep.length;
		FloatFFT_2D fft = new FloatFFT_2D(nz, ny);
		float[] kernel = new float[2 * nz * ny];
		for (int i = 0; i < keep.length; i++) kernel[2 * i] = keep[i];
		fft.complexInverse(kernel, true);
		float[] spreading = new float[2 * nz * ny];
		for (int i = 0; i < keep.length; i++) spreading[2 * i] = kernel[2 * i] * kernel[2 * i] + kernel[2 * i + 1] * kernel[2 * i + 1];
		kernel = null;
		fft.complexForward(spreading);
		float[] work = new float[2 * nz * ny], spread = new float[2 * nz * ny];
		double n = 0, sumS = 0, sumSS = 0, sumR = 0, sumSR = 0;
		for (int x = s.guard; x < w - s.guard; x++) {
			AliasingReconstruction.checkCancelled();
			Arrays.fill(work, 0);
			for (int z = 0; z < d; z++) {
				float[] pixels = (float[]) run.snapshot.image.getStack().getPixels(z + 1);
				for (int y = 0; y < h; y++) work[2 * ((z + pad) * ny + y + pad)] = (float) (pixels[y * w + x] - s.background);
			}
			fft.complexForward(work);
			for (int i = 0; i < keep.length; i++) {
				float re = work[2 * i], im = work[2 * i + 1], hr = spreading[2 * i], hi = spreading[2 * i + 1];
				spread[2 * i] = re * hr - im * hi;
				spread[2 * i + 1] = re * hi + im * hr;
				work[2 * i] *= keep[i];
				work[2 * i + 1] *= keep[i];
			}
			fft.complexInverse(work, true);
			fft.complexInverse(spread, true);
			for (int z = run.first; z <= run.last; z++) {
				for (int y = s.guard; y < h - s.guard; y++) {
					int i = 2 * ((z + pad) * ny + y + pad);
					double r2 = (double) work[i] * work[i], spreadSignal = spread[i];
					n++; sumS += spreadSignal; sumSS += spreadSignal * spreadSignal; sumR += r2; sumSR += spreadSignal * r2;
				}
			}
		}
		double denominator = n * sumSS - sumS * sumS;
		double gain = n > 2 && denominator > 0 ? (n * sumSR - sumS * sumR) / denominator : 0;
		if (!(gain > 0)) gain = 0;
		double variance0 = (sumR - gain * sumS) / Math.max(1, n) / fraction;
		if (!(variance0 > 0) || !Double.isFinite(variance0)) return null;
		NoiseModel.Fit fit = new NoiseModel.Fit();
		fit.sigma = Math.sqrt(variance0); fit.gain = gain; fit.band = band; fit.fraction = fraction; fit.voxels = (long) n;
		return fit;
	}

	/** Normalised Gaussian taps, sigma in voxels, radius taps either side. */
	static float[] gaussian(double sigma, int radius) {
		float[] kernel = new float[2 * radius + 1];
		double sum = 0;
		for (int k = -radius; k <= radius; k++) sum += Math.exp(-.5 * k * k / (sigma * sigma));
		for (int k = -radius; k <= radius; k++) kernel[k + radius] = (float) (Math.exp(-.5 * k * k / (sigma * sigma)) / sum);
		return kernel;
	}

	/** (I - B) under a separable kernel, edges clamped. */
	private static float[][] smooth(Run run, float[] kernel) {
		AliasingAdvisorSettings s = run.settings;
		int w = s.width, h = s.height, d = s.depth;
		float[][] volume = new float[d][];
		for (int z = 0; z < d; z++) {
			float[] pixels = (float[]) run.snapshot.image.getStack().getPixels(z + 1);
			float[] plane = new float[w * h];
			for (int i = 0; i < plane.length; i++) plane[i] = (float) (pixels[i] - s.background);
			volume[z] = plane;
		}
		float[] line = new float[Math.max(w, Math.max(h, d))];
		for (int z = 0; z < d; z++) {
			AliasingReconstruction.checkCancelled();
			float[] plane = volume[z];
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) line[x] = plane[y * w + x];
				for (int x = 0; x < w; x++) plane[y * w + x] = convolve(line, w, x, kernel);
			}
			for (int x = 0; x < w; x++) {
				for (int y = 0; y < h; y++) line[y] = plane[y * w + x];
				for (int y = 0; y < h; y++) plane[y * w + x] = convolve(line, h, y, kernel);
			}
		}
		for (int i = 0; i < w * h; i++) {
			for (int z = 0; z < d; z++) line[z] = volume[z][i];
			for (int z = 0; z < d; z++) volume[z][i] = convolve(line, d, z, kernel);
		}
		return volume;
	}

	private static float convolve(float[] line, int length, int at, float[] kernel) {
		int radius = kernel.length / 2;
		float sum = 0;
		for (int k = -radius; k <= radius; k++) sum += kernel[k + radius] * line[Math.min(length - 1, Math.max(0, at + k))];
		return sum;
	}
	static void execute(Run run, Consumer<String> progress) {
		AliasingAdvisorSettings s = run.settings;
		try {
			prepare(run);
			if (s.gpu) run.backend = "Experimental CLIJ2-FFT: " + net.haesleinhuepf.clij2.CLIJ2.getInstance().getGPUName();
			for (int m : AliasingStepModel.factors(s)) {
				for (int phase = 0; phase < m; phase++) {
					AliasingReconstruction.checkCancelled();
					ImagePlus sparse = AliasingReconstruction.decimate(run.snapshot.image, m, phase);
					try {
						AliasingReconstruction.Settings core = core(s, m);
						for (int method = 0; method < 2; method++) {
							AliasingReconstruction.checkCancelled();
							if (progress != null) progress.accept("Factor " + m + ", phase " + (phase + 1) + "/" + m + (method == 0 ? " Fourier" : " linear"));
							long start = System.nanoTime();
							ImagePlus recovered = method == 0 ? AliasingReconstruction.reconstruct(sparse, core) : AliasingReconstruction.interpolate(sparse, core.factor);
							try {
								double seconds = (System.nanoTime() - start) / 1e9;
								Row row = measure(run, recovered, m, phase, method == 0, method == 0 ? fourierFloor(run, m, phase, core) : null);
								row.method = method == 0 ? "Fourier" : "Linear";
								row.seconds = seconds; row.padding = core.padding;
								row.fftRows = AliasingReconstruction.paddedSize(s.height, core.padding);
								row.fftScan = AliasingReconstruction.paddedSize(recovered.getStackSize(), core.padding);
								run.rows.add(row);
							} finally { recovered.flush(); }
						}
					} finally { sparse.flush(); }
				}
			}
			run.complete = true; run.status = "Completed";
		} catch (CancellationException cancelled) { run.status = "Cancelled; partial rows"; throw cancelled; }
		catch (RuntimeException failure) { run.status = "Failed: " + failure.getMessage(); throw failure; }
		finally { aggregate(run); }
	}
	static AliasingReconstruction.Settings core(AliasingAdvisorSettings s, int m) {
		AliasingReconstruction.Settings core = new AliasingReconstruction.Settings();
		core.pixelUm = s.pixel; core.angleDegrees = s.angle; core.acquiredStepUm = m * s.step;
		core.factor = m * s.refinement; core.background = s.background; core.taper = s.taper;
		core.padding = AliasingStepModel.padding(s); core.gpu = s.gpu; core.validate(); return core;
	}
	/**
	 * Variance a Fourier reconstruction carries from independent noise on its acquired planes:
	 * Var(out_j) = sum_k k(j - k)^2 Var(in_k), with k = factor * IFFT(mask) the reconstruction's own
	 * kernel on its own padded grid, so this is the same circular convolution it makes. Exact for a
	 * variance that varies in space; the reconstruction is nonlocal, so a voxel's own variance times
	 * the white-noise gain is not. The kernel is real, so two maps travel as the real and imaginary
	 * parts of one transform.
	 *
	 * @param first   variance on each acquired plane, w * h
	 * @param second  a second map, propagated alongside
	 * @param planes  output (fine) planes wanted
	 * @return [map][planes index][w * h]; columns outside [x0, x1) are left zero
	 */
	static float[][][] propagate(float[][] first, float[][] second, int w, int h, AliasingReconstruction.Settings core, int[] planes, int x0, int x1) {
		int n = first.length, depth = (n - 1) * core.factor + 1, pad = core.padding;
		int nz = AliasingReconstruction.paddedSize(depth, pad), ny = AliasingReconstruction.paddedSize(h, pad);
		float[] weights = AliasingReconstruction.weights(nz, ny, core);
		FloatFFT_2D fft = new FloatFFT_2D(nz, ny);
		float[] squared = new float[2 * nz * ny];
		for (int i = 0; i < weights.length; i++) squared[2 * i] = weights[i];
		weights = null;
		fft.complexInverse(squared, true);
		double scale = (double) core.factor * core.factor;
		for (int i = 0; i < squared.length; i += 2) { double k = squared[i]; squared[i] = (float) (scale * k * k); squared[i + 1] = 0; }
		fft.complexForward(squared);
		float[][][] out = new float[2][planes.length][w * h];
		float[] work = new float[2 * nz * ny];
		for (int x = x0; x < x1; x++) {
			AliasingReconstruction.checkCancelled();
			Arrays.fill(work, 0);
			for (int z = 0; z < n; z++)
				for (int y = 0; y < h; y++) {
					int i = 2 * ((pad + z * core.factor) * ny + pad + y);
					work[i] = first[z][y * w + x]; work[i + 1] = second[z][y * w + x];
				}
			fft.complexForward(work);
			for (int i = 0; i < work.length; i += 2) {
				float re = work[i], im = work[i + 1], kr = squared[i], ki = squared[i + 1];
				work[i] = re * kr - im * ki; work[i + 1] = re * ki + im * kr;
			}
			fft.complexInverse(work, true);
			for (int p = 0; p < planes.length; p++)
				for (int y = 0; y < h; y++) {
					int i = 2 * ((pad + planes[p]) * ny + pad + y);
					out[0][p][y * w + x] = work[i]; out[1][p][y * w + x] = work[i + 1];
				}
		}
		return out;
	}
	/** The Fourier floor of one subset at its held-out dense planes, [read, shot][dense plane][voxel]; null without a noise model. */
	static float[][][] fourierFloor(Run run, int m, int phase, AliasingReconstruction.Settings core) {
		float[][] signal = run.noise.signal;
		if (signal == null) return null;
		AliasingAdvisorSettings s = run.settings;
		int n = (s.depth - 1 - phase) / m + 1;
		float[] ones = new float[s.width * s.height]; Arrays.fill(ones, 1);
		float[][] read = new float[n][], shot = new float[n][];
		for (int z = 0; z < n; z++) { read[z] = ones; shot[z] = signal[phase + z * m]; }
		List<Integer> heldout = new ArrayList<Integer>();
		for (int j = run.first; j <= run.last; j++) if ((j - phase) % m != 0) heldout.add(j);
		int[] planes = new int[heldout.size()];
		for (int p = 0; p < planes.length; p++) planes[p] = s.refinement * (heldout.get(p) - phase);
		float[][][] out = propagate(read, shot, s.width, s.height, core, planes, s.guard, s.width - s.guard);
		float[][][] floor = new float[2][s.depth][];
		for (int p = 0; p < planes.length; p++) { floor[0][heldout.get(p)] = out[0][p]; floor[1][heldout.get(p)] = out[1][p]; }
		return floor;
	}
	/**
	 * Score one reconstruction at the original dense planes. Each held-out voxel also records its
	 * expected noise, as coefficients of the read variance and the gain: the reference's own, plus
	 * what the reconstruction carries there - propagated through the kernel for Fourier
	 * ({@code floor}), the interpolation weights' (1-t)^2 v_lo + t^2 v_hi for linear. The
	 * reconstruction at a held-out plane is built from acquired planes only, so the two noises
	 * are independent.
	 */
	static Row measure(Run run, ImagePlus recovered, int m, int phase, boolean fourier, float[][][] floor) {
		Row row = new Row(); row.factor = m; row.phase = phase; row.stepUm = run.settings.step * m;
		float[][] signal = run.noise.signal;
		boolean noise = signal != null && (!fourier || floor != null);
		for (int j = run.first; j <= run.last; j++) {
			AliasingReconstruction.checkCancelled();
			float[] reference = (float[]) run.snapshot.image.getStack().getPixels(j + 1);
			float[] prediction = (float[]) recovered.getStack().getPixels(run.settings.refinement * (j - phase) + 1);
			int offset = (j - phase) % m;
			boolean acquired = offset == 0;
			Stats stats = acquired ? row.acquired : row.heldout;
			int lo = j - offset, hi = lo + m;
			double t = offset / (double) m, a = (1 - t) * (1 - t), b = t * t;
			for (int i = 0; i < reference.length; i++) {
				if (!run.mask[j][i]) continue;
				double readFloor = 0, shotFloor = 0, readNoise = 0, shotNoise = 0;
				if (noise) {
					readNoise = 1; shotNoise = signal[j][i];
					if (!acquired && fourier) { readFloor = 1 + floor[0][j][i]; shotFloor = shotNoise + floor[1][j][i]; }
					else if (!acquired) { readFloor = 1 + a + b; shotFloor = shotNoise + a * signal[lo][i] + b * signal[hi][i]; }
				}
				stats.add(reference[i], prediction[i], run.settings.background, readFloor, shotFloor, readNoise, shotNoise);
			}
		}
		return row;
	}
	/**
	 * Acceptance is absolute and per row: the pooled held-out NRMSE and every phase's must be within
	 * the ceiling. Against a noisy reference that error also holds the reference's and the
	 * reconstruction's noise, independent of the structural error, so in expectation it bounds the
	 * structural error from above - a Pass is conservative and rests on no fitted model. Against a
	 * declared noise-free reference it is the structural error itself.
	 * <p>
	 * The noise-corrected excess and its increase over factor 2 are estimates beside the verdict,
	 * never part of it. A relative criterion passed reconstructions that were wrong at every step
	 * alike - an error that falls with the step clips to no increase - and a noise fit that took a
	 * structure for noise subtracted the very error it should have measured (A and B in
	 * Z_STEP_ADVISOR_REVIEW_RESPONSE.md). The increase is computed only over a factor 2 whose every
	 * phase was scored, and only for rows whose every phase was.
	 */
	static void aggregate(Run run) {
		run.aggregates.clear();
		AliasingAdvisorSettings s = run.settings;
		NoiseModel.Fit fit = run.noise.used(), check = run.noise.usedCheck();
		for (String method : new String[] {"Fourier", "Linear"}) {
			List<Aggregate> list = new ArrayList<Aggregate>();
			for (int m : AliasingStepModel.factors(s)) {
				Aggregate a = new Aggregate(); a.factor = m; a.method = method;
				for (Row row : run.rows) if (row.factor == m && row.method.equals(method)) {
					a.phases++; a.acquired.merge(row.acquired); a.heldout.merge(row.heldout);
					double phase = row.heldout.nrmse(), excess = row.heldout.excess(fit);
					if (Double.isFinite(phase)) a.worstPhase = Math.max(a.worstPhase, phase); else a.phaseUndefined = true;
					if (Double.isFinite(excess)) a.worstExcess = Double.isNaN(a.worstExcess) ? excess : Math.max(a.worstExcess, excess);
				}
				a.excess = a.heldout.excess(fit); a.excessCheck = a.heldout.excess(check);
				if (a.phases != a.factor) a.verdict = "Incomplete";
				else if (!"Eligible".equals(run.adequacy) || a.phaseUndefined || !Double.isFinite(a.heldout.nrmse())) a.verdict = "Insufficient evidence";
				else a.verdict = a.heldout.nrmse() <= s.maximumNrmse && a.worstPhase <= s.maximumNrmse ? "Pass" : "Fails raw NRMSE ceiling";
				list.add(a);
			}
			Aggregate two = list.get(0).factor == 2 && list.get(0).usable() && Double.isFinite(list.get(0).excess) ? list.get(0) : null;
			if (two != null)
				for (Aggregate a : list)
					if (a.usable() && Double.isFinite(a.excess)) a.increase = Math.sqrt(Math.max(0, a.excess * a.excess - two.excess * two.excess));
			run.aggregates.addAll(list);
		}
		// keep the table in factor order, Fourier before linear
		run.aggregates.sort((x, y) -> x.factor != y.factor ? Integer.compare(x.factor, y.factor) : x.method.equals("Fourier") ? -1 : 1);
	}
	static double[] background(ImagePlus image, java.awt.Rectangle roi) {
		return background(image, roi, image == null ? 1 : image.getCurrentSlice());
	}
	static double[] background(ImagePlus image, java.awt.Rectangle roi, int plane) {
		if (image == null || roi == null || roi.width * (long) roi.height < 64) throw new IllegalArgumentException("Select a representative background ROI (at least 64 pixels)");
		if (roi.x < 0 || roi.y < 0 || (long)roi.x + roi.width > image.getWidth() || (long)roi.y + roi.height > image.getHeight()
				|| plane < 1 || plane > image.getStackSize()) throw new IllegalArgumentException("Background ROI or plane is outside the source");
		if (!image.lockSilently()) throw new IllegalArgumentException("Background image is busy");
		try {
			ImageProcessor p = image.getStack().getProcessor(plane); float[] values = new float[Math.multiplyExact(roi.width, roi.height)];
			int i = 0;
			for (int y = roi.y; y < roi.y + roi.height; y++) for (int x = roi.x; x < roi.x + roi.width; x++) {
				float value = p.getf(x, y); if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite background pixels"); values[i++] = value;
			}
			Arrays.sort(values); double median = values[values.length / 2];
			for (i = 0; i < values.length; i++) values[i] = (float) Math.abs(values[i] - median);
			Arrays.sort(values); return new double[] {median, values[values.length / 2] / .6744897501960817};
		} finally { image.unlock(); }
	}
}
