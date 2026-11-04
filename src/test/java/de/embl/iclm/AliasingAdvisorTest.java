package de.embl.iclm;

import static org.junit.Assert.*;
import com.google.gson.JsonParser;
import ij.ImagePlus;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Counterexamples from the design review, exercised against the actual advisor. */
public class AliasingAdvisorTest {
	@Rule public TemporaryFolder temporary = new TemporaryFolder();
	@After public void clearCancellation() { Shutdown.begin(); }
	@Test public void fixedPeriodGatingPreservesBlurAndFixedVelocityPreservesTravelTime() {
		AliasingAdvisorSettings s = new AliasingAdvisorSettings(); s.optics = 2;
		AliasingStepModel.Support support = AliasingStepModel.support(s);
		assertEquals(.6819363698580614, AliasingStepModel.predict(s, support, s.step).absoluteMotion, 1e-12);
		s.preserveBlur = true;
		AliasingStepModel.Prediction twice = AliasingStepModel.predict(s, support, 2 * s.step);
		assertEquals(50, twice.exposureMs, 1e-12); assertEquals(100, twice.framePeriodMs, 1e-12); assertEquals(.265, twice.blurUm, 1e-12);
		s.fixedVelocity = true; s.preserveBlur = false;
		AliasingStepModel.Prediction base = AliasingStepModel.predict(s, support, s.step);
		twice = AliasingStepModel.predict(s, support, 2 * s.step);
		assertEquals(base.seconds, twice.seconds, 1e-12); assertEquals(1, twice.speedup, 1e-12);
		s.gateAbsoluteMotion = true;
		assertFalse(AliasingStepModel.predict(s, support, s.step).motionPass);
		s.scanMode = 2; assertTrue(Double.isNaN(AliasingStepModel.predict(s, support, s.step).relativeMotion));
	}
	@Test public void gaussianSupportUsesFullProjectionAndNeverDependsOnCandidateBlur() {
		AliasingAdvisorSettings s = new AliasingAdvisorSettings();
		double sigZ = (1.77 * s.refractiveIndex * .520 / (.8 * .8)) / AliasingStepModel.FWHM;
		double c = Math.log(20) / (2 * Math.PI * Math.PI);
		double qzz = 1 / (sigZ * sigZ) + Math.pow(Math.cos(Math.toRadians(25)) * AliasingStepModel.FWHM / s.sheetFwhm, 2);
		AliasingStepModel.Support a = AliasingStepModel.support(s);
		assertEquals(Math.sqrt(c * qzz), a.kz, 1e-12);
		s.exposureMs = 1; s.maximumStep = 20;
		assertEquals(a.kz, AliasingStepModel.support(s).kz, 0);
		assertEquals(.944271996009313, AliasingStepModel.aliasCap(33.5, .8), 1e-12);
	}
	@Test public void domainsFactorsAndPaddingKeepPhysicalCoordinates() {
		assertArrayEquals(new int[] {19,172}, AliasingStepModel.domain(192, new int[] {2,3,4}, 16));
		AliasingAdvisorSettings s = new AliasingAdvisorSettings(); assertArrayEquals(new int[] {2,3,4}, AliasingStepModel.factors(s));
		assertEquals(32, AliasingStepModel.padding(s)); s.refinement = 8; assertEquals(128, AliasingStepModel.padding(s));
		s.refinement = 32; assertArrayEquals(new int[] {2}, AliasingStepModel.factors(s));
		assertEquals(451, AliasingStepModel.planeCount(119.25, .265));
	}
	@Test public void metadataIsSparseAndInvalidatesCarriedEvidence() throws Exception {
		File file = temporary.newFile("ExperimentalParameter.txt");
		Files.write(file.toPath(), Arrays.asList("Step size /um: 0.3", "um per galvo DU: NaN", "Galvo DUs per step: 20", "Tilt angle: 95", "NA slit width /mm: 2.2", "Planes per volume: 91"), StandardCharsets.UTF_8);
		AliasingMetadata.Parsed patch = AliasingMetadata.parse(file);
		assertFalse(patch.fields.containsKey("angle")); assertFalse(patch.fields.containsKey("na")); assertEquals(.3, patch.fields.get("step"), 0);
		AliasingAdvisorSettings s = new AliasingAdvisorSettings(); s.angle = 33.5; s.geometryConfirmed = s.noiseKnown = s.supportMeasured = true;
		patch.apply(s, 901);
		assertEquals(33.5, s.angle, 0); assertFalse(s.geometryConfirmed); assertFalse(s.noiseKnown); assertFalse(s.supportMeasured);
		assertTrue(s.evidence.get("angle").contains("Unverified")); assertEquals(64, s.metadataHash.length());
		assertEquals(1.2, s.maximumStep, 1e-12);
	}
	@Test public void metadataUsesGalvoAndCameraMagnificationWithoutInferringOptics() throws Exception {
		File file = temporary.newFile("ExperimentalParameters.txt");
		Files.write(file.toPath(), Arrays.asList("Pixel size /um: 6.5", "System magnification: 55.9", "Tilt angle: 33.5", "Step size /um: 2", "um per galvo DU: .01325", "Galvo DUs per step: 20", "Planes per volume: 451", "Exposure time /ms: 100"), StandardCharsets.UTF_8);
		AliasingMetadata.Parsed p = AliasingMetadata.parse(file); assertEquals(.265, p.fields.get("step"), 1e-12); assertEquals(6.5/55.9, p.fields.get("pixel"), 1e-12);
		assertFalse(p.warnings.isEmpty()); assertFalse(p.fields.containsKey("na"));
	}
	@Test public void settingsRoundTripIncludesInactiveInputsAndProvenance() {
		AliasingAdvisorSettings s = new AliasingAdvisorSettings(); s.optics = 2; s.na = .95; s.noiseKnown = true; s.x = 13;
		s.metadataPath = "D:\\Acquisition\\ExperimentalParameters.txt"; s.origins.put("na", "Manual"); s.evidence.put("na", "Assumed"); s.previewPhase = 3;
		AliasingAdvisorSettings copy = s.copy(); copy.validate();
		assertEquals(AliasingAdvisorSettings.JSON.toJson(s), AliasingAdvisorSettings.JSON.toJson(copy));
		copy.origins.put("na", "Measured"); assertEquals("Manual", s.origins.get("na"));
	}
	@Test public void pooledErrorIsNotMeanOfPhaseNrmse() {
		AliasingQualitySweep.Stats first = new AliasingQualitySweep.Stats(), second = new AliasingQualitySweep.Stats();
		first.add(1, 2, 0); second.add(10, 11, 0); double mean = (first.nrmse() + second.nrmse()) / 2;
		first.merge(second); assertEquals(.55, mean, 1e-12); assertEquals(.14071950894605836, first.nrmse(), 1e-12);
		assertTrue(Double.isNaN(new AliasingQualitySweep.Stats().nrmse()));
	}
	static AliasingAdvisorSettings small(ImagePlus image) {
		AliasingAdvisorSettings s = new AliasingAdvisorSettings();
		s.width = image.getWidth(); s.height = image.getHeight(); s.depth = image.getStackSize(); s.angle = 33.5;
		s.guard = 2; s.minimumSignal = 8; s.background = 100; s.noiseSigma = 1;
		s.rawConfirmed = s.geometryConfirmed = s.noiseKnown = true;
		return s;
	}
	@Test public void allPhasesRecoverWavePacketAndExportOriginalSampleMetrics() throws Exception {
		Shutdown.begin();
		ImagePlus dense = AliasingReconstructionTest.raw(8, 64, 65, 33.5, .265, AliasingReconstructionTest.wavePacket(2.5));
		AliasingAdvisorSettings s = small(dense);
		AliasingQualitySweep.Run run = new AliasingQualitySweep.Run(AliasingQualitySweep.snapshot(dense, s), s);
		try {
			AliasingQualitySweep.execute(run, null); assertTrue(run.complete); assertEquals(18, run.rows.size());
			for (int i = 0; i < run.aggregates.size(); i += 2) {
				AliasingQualitySweep.Aggregate f = run.aggregates.get(i), l = run.aggregates.get(i+1);
				assertEquals(run.signalVoxels * (f.factor - 1), f.heldout.count);
				assertEquals(run.signalVoxels, f.acquired.count);
				assertTrue("Fourier " + f.heldout.nrmse() + " versus linear " + l.heldout.nrmse(), f.heldout.nrmse() < l.heldout.nrmse());
			}
			AliasingAdvisorView.export(run, temporary.getRoot().toPath());
			File[] json = temporary.getRoot().listFiles((dir,name) -> name.endsWith(".json")); assertEquals(1, json.length);
			String text = new String(Files.readAllBytes(json[0].toPath()), StandardCharsets.UTF_8);
			assertFalse(text.contains("NaN")); assertFalse(text.contains("Infinity")); assertTrue(text.contains(run.snapshotSha256));
			assertEquals(18, new JsonParser().parse(text).getAsJsonObject().getAsJsonArray("rows").size());
			assertTrue(text.contains("\"criterion\"")); assertTrue(text.contains("\"estimate\""));
			File[] factors = temporary.getRoot().listFiles((dir,name) -> name.endsWith("-factors.csv")); assertEquals(1, factors.length);
			java.util.List<String> lines = Files.readAllLines(factors[0].toPath(), StandardCharsets.UTF_8);
			assertEquals(1 + run.aggregates.size(), lines.size()); assertTrue(lines.get(0).contains("verdict"));
			ImagePlus[] preview = AliasingAdvisorView.preview(run, 2, 1);
			try { assertEquals(4, preview.length); assertNotNull(preview[0].getProperty(AliasingQualitySweep.OUTPUT)); assertTrue(Float.isNaN(preview[1].getStack().getProcessor(2).getf(0))); }
			finally { for (ImagePlus image : preview) image.flush(); }
		} finally { run.release(); dense.flush(); }
	}
	@Test public void pureNoiseAndUnknownGeometryCannotPass() {
		Shutdown.begin(); java.util.Random random = new java.util.Random(1729);
		ImagePlus dense = AliasingReconstructionTest.raw(8, 48, 49, 33.5, .265, (y,z) -> 0);
		for (int j = 1; j <= dense.getStackSize(); j++) for (int i = 0; i < 8*48; i++) dense.getStack().getProcessor(j).setf(i, (float)(100 + random.nextGaussian()));
		AliasingAdvisorSettings s = small(dense);
		AliasingQualitySweep.Run run = new AliasingQualitySweep.Run(AliasingQualitySweep.snapshot(dense, s), s);
		try { AliasingQualitySweep.prepare(run); assertEquals("Insufficient foreground", run.adequacy); assertEquals(0, run.signalVoxels); }
		finally { run.release(); dense.flush(); }
	}
	/** Twelve beads with the cohort's widths; optionally Poisson-like (variance gain * signal) plus read noise. */
	static ImagePlus beads(double gain, double read, long seed) {
		int w = 40, h = 96, depth = 129;
		double theta = Math.toRadians(33.5), sy = .45 / 2.3548, sz = 1.04 / 2.3548, sx = .47 / 2.3548;
		java.util.Random random = new java.util.Random(seed);
		double[][] centres = new double[12][];
		for (int b = 0; b < centres.length; b++)
			centres[b] = new double[] { -10 + 20 * random.nextDouble(), -2 + 4 * random.nextDouble(), -1.5 + 3 * random.nextDouble() };
		ij.ImageStack stack = new ij.ImageStack(w, h);
		for (int v = 0; v < depth; v++) {
			float[] pixels = new float[w * h];
			for (int u = 0; u < h; u++) {
				double y = .116 * Math.cos(theta) * (u - (h - 1) / 2.) + .265 * (v - (depth - 1) / 2.);
				double z = -.116 * Math.sin(theta) * (u - (h - 1) / 2.);
				for (int x = 0; x < w; x++) {
					double xl = .116 * (x - (w - 1) / 2.), clean = 0;
					for (double[] c : centres)
						clean += 800 * Math.exp(-.5 * (Math.pow((y - c[0]) / sy, 2) + Math.pow((z - c[1]) / sz, 2) + Math.pow((xl - c[2]) / sx, 2)));
					double value = 100 + clean;
					if (gain > 0) value += Math.sqrt(gain * clean) * random.nextGaussian() + read * random.nextGaussian();
					pixels[u * w + x] = (float) value;
				}
			}
			stack.addSlice(new ij.process.FloatProcessor(w, h, pixels));
		}
		return new ImagePlus("synthetic beads", stack);
	}
	static AliasingQualitySweep.Run sweep(ImagePlus image, boolean noiseFree, double sigma) {
		AliasingAdvisorSettings s = small(image);
		s.refinement = 1; s.guard = 8; s.minimumSignal = 64; s.noiseSigma = sigma; s.syntheticNoiseFree = noiseFree;
		s.rangeFollowsStep = false; s.minimumStep = 2 * s.step; s.maximumStep = 6 * s.step;
		AliasingQualitySweep.Run run = new AliasingQualitySweep.Run(AliasingQualitySweep.snapshot(image, s), s);
		AliasingQualitySweep.execute(run, null);
		return run;
	}
	static AliasingQualitySweep.Aggregate fourier(AliasingQualitySweep.Run run, int factor) {
		for (AliasingQualitySweep.Aggregate a : run.aggregates) if (a.factor == factor && a.method.equals("Fourier")) return a;
		throw new AssertionError("no Fourier row for factor " + factor);
	}
	/**
	 * Against a noisy reference the raw held-out NRMSE has a floor of about sqrt(2v/P) whatever the
	 * step: the reconstruction carries the acquired planes' noise. The fit must find the camera's
	 * gain and read noise and pass its own checks, and the noise-corrected increase must track the
	 * structural increase of the same beads without noise. Acceptance stays on the raw error, so
	 * the noisy run can only be more cautious than the truth, never less.
	 */
	@Test public void theNoiseFloorIsEstimatedOnBeadsAndAcceptanceStaysConservative() {
		Shutdown.begin();
		ImagePlus noisy = beads(2.0, 8.9, 11), clean = beads(0, 0, 11);
		AliasingQualitySweep.Run measured = sweep(noisy, false, 8.9), truth = sweep(clean, true, 1);
		try {
			assertTrue(measured.noise.status, measured.noise.accepted);
			assertEquals("gain " + measured.noise.status, 2.0, measured.noise.fit.gain, .4);
			assertEquals("read sigma " + measured.noise.status, 8.9, measured.noise.fit.sigma, .9);
			AliasingQualitySweep.Aggregate two = fourier(measured, 2);
			assertTrue("noise share " + two.heldout.noiseShare(measured.noise.used()), two.heldout.noiseShare(measured.noise.used()) > .3);
			assertTrue(two.excess < two.heldout.nrmse());
			for (int m : new int[] { 4, 6 })
				assertEquals("increase at " + m, fourier(truth, m).increase, fourier(measured, m).increase, .03);
			assertEquals("Pass", fourier(truth, 2).verdict);
			assertEquals("Fails raw NRMSE ceiling", fourier(truth, 6).verdict);
			for (int m = 2; m <= 6; m++) {
				AliasingQualitySweep.Aggregate noisyRow = fourier(measured, m), truthRow = fourier(truth, m);
				assertTrue("raw at " + m, noisyRow.heldout.nrmse() >= truthRow.heldout.nrmse());
				if (noisyRow.verdict.equals("Pass")) assertEquals("Pass", truthRow.verdict);
			}
			assertTrue(truth.estimate(), truth.estimate().startsWith("Noise-free reference"));
			assertTrue(measured.estimate(), measured.estimate().startsWith("Exploratory, not an acceptance"));
		} finally { measured.release(); truth.release(); noisy.flush(); clean.flush(); }
	}
	/** The fit holds where read noise and where shot noise dominates, against the sigma entered for each. */
	@Test public void theNoiseFitHoldsAcrossReadNoiseAndGain() {
		Shutdown.begin();
		for (double[] noise : new double[][] { { .5, 3 }, { 4, 15 } }) {
			ImagePlus image = beads(noise[0], noise[1], 5);
			AliasingAdvisorSettings s = small(image);
			s.refinement = 1; s.guard = 8; s.minimumSignal = 64; s.noiseSigma = noise[1];
			s.rangeFollowsStep = false; s.minimumStep = 2 * s.step; s.maximumStep = 4 * s.step;
			AliasingQualitySweep.Run run = new AliasingQualitySweep.Run(AliasingQualitySweep.snapshot(image, s), s);
			try {
				AliasingQualitySweep.prepare(run);
				assertTrue(run.noise.status, run.noise.accepted);
				assertEquals(run.noise.status, noise[0], run.noise.fit.gain, .25 * noise[0]);
				assertEquals(run.noise.status, noise[1], run.noise.fit.sigma, .1 * noise[1]);
			} finally { run.release(); image.flush(); }
		}
	}
	/** A plane wave across the camera, fv cycles per dense plane, 100 ADU over a 100 ADU background. */
	static ImagePlus wave(double fv, double noise, long seed) {
		java.util.Random random = new java.util.Random(seed);
		ij.ImageStack stack = new ij.ImageStack(20, 80);
		for (int z = 0; z < 129; z++) {
			float[] pixels = new float[20 * 80];
			for (int i = 0; i < pixels.length; i++) pixels[i] = (float) (200 + 80 * Math.cos(2 * Math.PI * fv * z) + noise * random.nextGaussian());
			stack.addSlice(new ij.process.FloatProcessor(20, 80, pixels));
		}
		return new ImagePlus("plane wave " + fv, stack);
	}
	static AliasingQualitySweep.Run waveSweep(ImagePlus image, boolean noiseFree) {
		AliasingAdvisorSettings s = small(image);
		s.refinement = 1; s.guard = 4; s.syntheticNoiseFree = noiseFree;
		s.rangeFollowsStep = false; s.minimumStep = 2 * s.step; s.maximumStep = 4 * s.step;
		AliasingQualitySweep.Run run = new AliasingQualitySweep.Run(AliasingQualitySweep.snapshot(image, s), s);
		AliasingQualitySweep.execute(run, null);
		return run;
	}
	/**
	 * Structure a coarse step cannot recover, below the dense scan's Nyquist: every factor is badly
	 * wrong, and the error falls as the step coarsens. A criterion relative to the finest factor read
	 * that as no increase and passed 2..4 (A in Z_STEP_ADVISOR_REVIEW_RESPONSE.md); nothing may pass.
	 */
	@Test public void reconstructionsWrongAtEveryStepNeverPass() {
		Shutdown.begin();
		for (double fv : new double[] { .28, .35, .40 }) {
			ImagePlus image = wave(fv, 0, 0);
			AliasingQualitySweep.Run run = waveSweep(image, true);
			try {
				assertEquals("Eligible", run.adequacy);
				for (int m = 2; m <= 4; m++) {
					assertTrue("raw at " + m, fourier(run, m).heldout.nrmse() > .5);
					assertEquals("fv " + fv + " factor " + m, "Fails raw NRMSE ceiling", fourier(run, m).verdict);
				}
				assertTrue(run.recommendation(), run.recommendation().startsWith("No tested step meets the chosen criterion"));
			} finally { run.release(); image.flush(); }
		}
	}
	/**
	 * The same wave under 1 ADU of known noise, with 1 ADU entered: the band the default optics leave
	 * for noise holds the wave, and the fit took it for 88 ADU of read noise and subtracted the whole
	 * error (B). A fit that contradicts the verified background is not used, and nothing passes.
	 */
	@Test public void aNoiseFitThatContradictsTheBackgroundIsNotUsed() {
		Shutdown.begin();
		ImagePlus image = wave(.35, 1, 10626);
		AliasingQualitySweep.Run run = waveSweep(image, false);
		try {
			assertFalse(run.noise.status, run.noise.accepted);
			assertTrue(run.noise.status, run.noise.status.contains("contradicts the verified background sigma"));
			for (int m = 2; m <= 4; m++) {
				AliasingQualitySweep.Aggregate a = fourier(run, m);
				assertTrue(Double.isNaN(a.excess)); assertTrue(Double.isNaN(a.increase));
				assertEquals("Fails raw NRMSE ceiling", a.verdict);
			}
			assertTrue(run.estimate(), run.estimate().startsWith("No noise-corrected estimate"));
		} finally { run.release(); image.flush(); }
	}
	/**
	 * The Fourier floor is the reconstruction's own variance, wherever the variance changes. The
	 * reconstruction is nonlocal, so a held-out voxel beside a noisier region inherits its noise,
	 * which the voxel's own variance times the white-noise gain misses (C).
	 */
	@Test public void theFourierFloorIsTheReconstructionsOwnVariance() {
		Shutdown.begin();
		int h = 48, n = 24, trials = 400;
		AliasingReconstruction.Settings core = new AliasingReconstruction.Settings();
		core.pixelUm = .116; core.angleDegrees = 33.5; core.acquiredStepUm = 2 * .265; core.factor = 2; core.padding = 16; core.background = 0;
		float[][] variance = new float[n][h];
		for (float[] plane : variance) for (int y = 0; y < h; y++) plane[y] = y < h / 2 ? 1 : 16;
		int[] planes = new int[n - 1];
		for (int p = 0; p < planes.length; p++) planes[p] = 2 * p + 1;
		float[][][] exact = AliasingQualitySweep.propagate(variance, variance, 1, h, core, planes, 0, 1);
		double[] empirical = new double[h];
		java.util.Random random = new java.util.Random(7);
		for (int trial = 0; trial < trials; trial++) {
			ij.ImageStack stack = new ij.ImageStack(1, h);
			for (int z = 0; z < n; z++) {
				float[] pixels = new float[h];
				for (int y = 0; y < h; y++) pixels[y] = (float) (Math.sqrt(variance[z][y]) * random.nextGaussian());
				stack.addSlice(new ij.process.FloatProcessor(1, h, pixels));
			}
			ImagePlus out = AliasingReconstruction.reconstruct(new ImagePlus("noise", stack), core);
			for (int plane : planes) {
				float[] pixels = (float[]) out.getStack().getPixels(plane + 1);
				for (int y = 0; y < h; y++) empirical[y] += (double) pixels[y] * pixels[y] / trials;
			}
			out.flush();
		}
		double worstLocal = 0;
		for (int y = 0; y < h; y++) {
			double predicted = 0, local = (planes.length) * (double) variance[0][y];
			// the kernel's transform is real up to float rounding, which mixes the two channels by about 1e-6
			for (int p = 0; p < planes.length; p++) { predicted += exact[0][p][y]; assertEquals(exact[0][p][y], exact[1][p][y], 1e-5 * exact[0][p][y]); }
			assertEquals("row " + y, 1, empirical[y] / predicted, .1);
			worstLocal = Math.max(worstLocal, Math.abs(empirical[y] / local - 1));
		}
		assertTrue("the local approximation should miss near the step: " + worstLocal, worstLocal > .3);
	}
	/** A range starts at factor 2, as m = 1 is the reference itself; without factor 2, passing points are listed only. */
	@Test public void aRangeStartsAtFactorTwoAndOtherPassesAreListedApart() {
		ImagePlus tiny = AliasingReconstructionTest.raw(8, 16, 20, 25, .265, (y, z) -> 0);
		AliasingAdvisorSettings s = new AliasingAdvisorSettings();
		s.rangeFollowsStep = false; s.minimumStep = 3 * s.step; s.maximumStep = 6 * s.step;
		String[] verdicts = { "Pass", "Pass", "Fails raw NRMSE ceiling", "Pass" };
		for (int first : new int[] { 3, 2 }) {
			AliasingQualitySweep.Run run = new AliasingQualitySweep.Run(new AliasingQualitySweep.Snapshot(tiny, "x", "tiny", 8, 16, 20, 0), s);
			run.complete = true; run.adequacy = "Eligible";
			for (int m = first; m < first + 4; m++) {
				AliasingQualitySweep.Aggregate a = new AliasingQualitySweep.Aggregate(); a.factor = m; a.method = "Fourier"; a.verdict = verdicts[m - first];
				run.aggregates.add(a);
			}
			String recommendation = run.recommendation();
			if (first == 3) {
				assertTrue(recommendation, recommendation.startsWith("Passing points only, not a range (factor 2 not tested): factors 3, 4, 6"));
			} else {
				assertTrue(recommendation, recommendation.startsWith("Tested reference agreement: factors 2..3"));
				assertTrue(recommendation, recommendation.contains("isolated passes at factors 5"));
			}
		}
		tiny.flush();
	}
	static AliasingQualitySweep.Run scored(int... factors) {
		ImagePlus tiny = AliasingReconstructionTest.raw(8, 16, 20, 25, .265, (y, z) -> 0);
		AliasingAdvisorSettings s = new AliasingAdvisorSettings();
		s.rangeFollowsStep = false; s.minimumStep = factors[0] * s.step; s.maximumStep = factors[factors.length - 1] * s.step;
		AliasingQualitySweep.Run run = new AliasingQualitySweep.Run(new AliasingQualitySweep.Snapshot(tiny, "x", "tiny", 8, 16, 20, 0), s);
		run.complete = true; run.adequacy = "Eligible";
		run.noise.fit = new AliasingQualitySweep.NoiseModel.Fit(); run.noise.accepted = true;
		tiny.flush();
		return run;
	}
	static void score(AliasingQualitySweep.Run run, int factor, int phase, double reference, double prediction) {
		AliasingQualitySweep.Row row = new AliasingQualitySweep.Row(); row.factor = factor; row.phase = phase; row.method = "Fourier";
		if (Double.isFinite(prediction)) row.heldout.add(reference, prediction, 0);
		run.rows.add(row);
	}
	/** A phase with no held-out voxels has no score: the factor is unassessed, not failed. */
	@Test public void anUndefinedPhaseScoreIsInsufficientEvidenceNotAFailure() {
		AliasingQualitySweep.Run run = scored(2);
		score(run, 2, 0, 10, 11); score(run, 2, 1, 10, Double.NaN);
		AliasingQualitySweep.aggregate(run);
		assertEquals("Insufficient evidence", fourier(run, 2).verdict);
	}
	/** No row vouches for another: each verdict is the row's own, and an incomplete row neither gets nor gives an increase (D). */
	@Test public void anIncompleteRowNeitherVouchesNorIsVouchedFor() {
		AliasingQualitySweep.Run run = scored(2, 3);
		score(run, 2, 0, 100, 100); score(run, 2, 1, 100, 100);
		score(run, 3, 0, 100, Double.NaN); score(run, 3, 1, 100, 100); score(run, 3, 2, 100, 100);
		AliasingQualitySweep.aggregate(run);
		assertEquals("Pass", fourier(run, 2).verdict);
		assertEquals("Insufficient evidence", fourier(run, 3).verdict);
		assertTrue(Double.isNaN(fourier(run, 3).increase));
		assertTrue(run.recommendation(), run.recommendation().startsWith("Tested reference agreement: factors 2..2"));

		run = scored(2, 3);
		score(run, 2, 0, 100, Double.NaN); score(run, 2, 1, 100, 100);
		score(run, 3, 0, 100, 100); score(run, 3, 1, 100, 100); score(run, 3, 2, 100, 100);
		AliasingQualitySweep.aggregate(run);
		assertEquals("Insufficient evidence", fourier(run, 2).verdict);
		assertEquals("Pass", fourier(run, 3).verdict);
		assertTrue("no baseline, no increase", Double.isNaN(fourier(run, 3).increase));
		assertTrue(run.recommendation(), run.recommendation().startsWith("Passing points only, not a range: factors 3"));
	}
	/** The estimate line gives the increase over factor 2 while it means something, and says when it does not. */
	@Test public void theEstimateLineSaysWhenTheIncreaseMeansNothing() {
		AliasingQualitySweep.Run run = scored(2, 3, 4);
		double[] rising = { .10, .11, .20 };
		for (int m = 2; m <= 4; m++) for (int phase = 0; phase < m; phase++) score(run, m, phase, 100, 100 + 100 * rising[m - 2]);
		AliasingQualitySweep.aggregate(run);
		assertTrue(run.estimate(), run.estimate().endsWith("rises by at most 5.0% over factor 2 through factor 3, then 17.3% at factor 4"));
		run = scored(2, 3, 4);
		double[] falling = { .9, .8, .7 };
		for (int m = 2; m <= 4; m++) for (int phase = 0; phase < m; phase++) score(run, m, phase, 100, 100 + 100 * falling[m - 2]);
		AliasingQualitySweep.aggregate(run);
		assertTrue(run.estimate(), run.estimate().startsWith("Estimate not interpretable"));
		assertEquals(0, fourier(run, 4).increase, 0);
	}
	/** A ceiling saved under schema 1 gates as it did when it was saved (E); a newer schema is refused. */
	@Test public void savedSchemaOneCeilingsKeepGating() {
		AliasingAdvisorSettings legacy = AliasingAdvisorSettings.fromJson("{\"schema\":1,\"maximumNrmse\":0.07}");
		assertEquals(AliasingAdvisorSettings.SCHEMA, legacy.schema); assertEquals(.07, legacy.maximumNrmse, 0);
		AliasingAdvisorSettings interim = AliasingAdvisorSettings.fromJson("{\"schema\":1,\"maximumNrmse\":0.3,\"maximumIncrement\":0.04,\"gateRawNrmse\":false}");
		assertEquals(.3, interim.maximumNrmse, 0); assertEquals(.04, interim.maximumIncrement, 0);
		try { AliasingAdvisorSettings.fromJson("{\"schema\":3}"); fail("newer schema"); } catch (IllegalArgumentException expected) { }
		AliasingQualitySweep.Run run = scored(2);
		run.settings.maximumNrmse = legacy.maximumNrmse;
		score(run, 2, 0, 100, 108); score(run, 2, 1, 100, 108);
		AliasingQualitySweep.aggregate(run);
		assertEquals(.08, fourier(run, 2).heldout.nrmse(), 1e-12);
		assertEquals("Fails raw NRMSE ceiling", fourier(run, 2).verdict);
	}
	/** The open image's own sidecar wins over the file used last time, whose overrides survive only when it is that file. */
	@Test public void theOpenImagesSidecarWinsOverTheFileUsedLastTime() throws Exception {
		File saved = temporary.newFile("ExperimentalParameter.txt"), sidecar = temporary.newFile("ExperimentalParameters.txt");
		String savedPath = saved.getCanonicalPath();
		assertSame(sidecar, AliasingStepAdvisor.startupMetadata(sidecar, savedPath));
		File same = new File(savedPath), chosen = AliasingStepAdvisor.startupMetadata(same, savedPath);
		assertNotSame("the saved file itself keeps its overrides", same, chosen); assertEquals(savedPath, chosen.getPath());
		assertEquals(savedPath, AliasingStepAdvisor.startupMetadata(null, savedPath).getPath());
		assertNull(AliasingStepAdvisor.startupMetadata(null, ""));
	}
	/** Loading metadata with no frame period must not switch the user's measured-period timing off. */
	@Test public void aMetadataFileWithoutAFramePeriodLeavesTheTimingModeAlone() throws Exception {
		File file = temporary.newFile("ExperimentalParameters.txt");
		Files.write(file.toPath(), Arrays.asList("Tilt angle: 33.5", "Step size /um: 0.265", "Exposure time /ms: 100"), StandardCharsets.UTF_8);
		AliasingAdvisorSettings s = new AliasingAdvisorSettings(); s.measuredPeriod = true; s.periodMs = 120;
		AliasingMetadata.parse(file).apply(s, 451);
		assertTrue(s.measuredPeriod); assertEquals(120, s.periodMs, 0);
		assertTrue(s.evidence.get("periodMs").contains("Unverified"));
	}
	@Test public void selectedSnapshotIsIndependentAndInvalidRoiDoesNotMutateSource() {
		Shutdown.begin(); ImagePlus dense = AliasingReconstructionTest.raw(8, 32, 33, 33.5, .265, (y,z) -> 10);
		AliasingAdvisorSettings s = small(dense);
		AliasingQualitySweep.Snapshot snapshot = AliasingQualitySweep.snapshot(dense, s);
		try { dense.getStack().getProcessor(1).setf(0, 999); assertEquals(110, snapshot.image.getStack().getProcessor(1).getf(0), 0); assertFalse(dense.isLocked()); }
		finally { snapshot.close(); }
		s.x = 100;
		try { AliasingQualitySweep.snapshot(dense,s); fail("outside ROI"); } catch (IllegalArgumentException expected) { assertFalse(dense.isLocked()); }
		dense.flush();
	}
}
