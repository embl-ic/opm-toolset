package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Assume;
import org.junit.Test;

public class AliasingReconstructionTest {

	/** A lab-frame intensity, Y and Z in um. */
	interface Field {
		double at (double y, double z);
	}

	/** Dense raw volume of a lab-frame field, in the Transform.deskew geometry, constant along X, over a background of 100. */
	static ImagePlus raw (int width, int height, int depth, double angle, double step, Field field) {
		double theta = Math.toRadians(angle);
		ImageStack stack = new ImageStack(width, height);
		for (int v = 0; v < depth; v++) {
			float[] pixels = new float[width * height];
			for (int u = 0; u < height; u++) {
				double y = 0.116 * Math.cos(theta) * (u - (height - 1) / 2.) + step * (v - (depth - 1) / 2.);
				double z = -0.116 * Math.sin(theta) * (u - (height - 1) / 2.);
				Arrays.fill(pixels, u * width, (u + 1) * width, (float) (100 + field.at(y, z)));
			}
			stack.addSlice(new FloatProcessor(width, height, pixels));
		}
		ImagePlus image = new ImagePlus("analytic raw volume", stack);
		image.getCalibration().pixelWidth = 0.116;
		image.getCalibration().pixelHeight = 0.116;
		image.getCalibration().pixelDepth = step;
		image.getCalibration().setUnit("um");
		return image;
	}

	static Field bead (double fwhmY, double fwhmZ) {
		double sy = fwhmY / 2.3548;
		double sz = fwhmZ / 2.3548;
		return (y, z) -> 1000 * Math.exp(-0.5 * (y * y / (sy * sy) + z * z / (sz * sz)));
	}

	/** A pattern along lab Y only, kZ = 0 under a wide envelope: inside every axial slab. */
	static Field wavePacket (double kY) {
		return (y, z) -> 500 * Math.exp(-(y * y + z * z) / (2 * 1.5 * 1.5)) * Math.cos(2 * Math.PI * kY * y);
	}

	static ImagePlus gaussian (int width, int height, int depth, double angle) {
		ImageStack stack = new ImageStack(width, height);
		double theta = Math.toRadians(angle);
		double a = .265 / .116;
		for (int z = 0; z < depth; z++) {
			float[] pixels = new float[width * height];
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					double labY = a * (z - (depth - 1) / 2.) + Math.cos(theta) * (y - (height - 1) / 2.);
					double labZ = -Math.sin(theta) * (y - (height - 1) / 2.);
					pixels[y * width + x] = (float) (100 + (800 + 200 * Math.cos(2 * Math.PI * x / width))
							* Math.exp(-.5 * (labY * labY / 4 + labZ * labZ / 16)));
				}
			}
			stack.addSlice(new FloatProcessor(width, height, pixels));
		}
		ImagePlus image = new ImagePlus("analytic tilted bead", stack);
		image.getCalibration().pixelWidth = .116;
		image.getCalibration().pixelHeight = .116;
		image.getCalibration().pixelDepth = .265;
		image.getCalibration().setUnit("um");
		return image;
	}

	static AliasingReconstruction.Settings settings (int factor, double denseStep, double background) {
		AliasingReconstruction.Settings settings = new AliasingReconstruction.Settings();
		settings.factor = factor;
		settings.acquiredStepUm = denseStep * factor;
		settings.background = background;
		return settings;
	}

	@Test
	public void recoversAnalyticTiltedPsfAndPreservesIntensityForEveryPhase () {
		ImagePlus dense = gaussian(16, 96, 97, 33.5);
		for (int phase = 0; phase < 2; phase++) {
			ImagePlus sparse = AliasingReconstruction.decimate(dense, 2, phase);
			AliasingReconstruction.Settings s = new AliasingReconstruction.Settings();
			s.background = 100;
			ImagePlus recovered = AliasingReconstruction.reconstruct(sparse, s);
			double[] error = AliasingResampling.errors(dense, recovered, phase, 100, 4);
			assertTrue("relative error=" + error[0], error[0] < .015);
			assertEquals(.265, recovered.getCalibration().pixelDepth, 1e-12);
			assertEquals((sparse.getStackSize() - 1) * 2 + 1, recovered.getStackSize());
			assertEquals(100, dense.getStack().getProcessor(1).getf(0, 0), 1e-5);
		}
	}

	/**
	 * A lab-Y pattern above 1/(2 x target step) wraps round the raw scan-frequency axis. The
	 * mask used to measure its distance from the band without the wrap and erased it entirely
	 * (NRMSE 100%), although kZ = 0 lies inside every axial slab.
	 */
	@Test
	public void aLabYFrequencyAboveTheTargetNyquistIsRecovered () {
		for (double kY : new double[] { 2.5, 3.0 }) {
			assertTrue("the case must lie above the target Nyquist", kY > 1 / (2 * .265));
			ImagePlus dense = raw(10, 128, 129, 33.5, .265, wavePacket(kY));
			ImagePlus recovered = AliasingReconstruction.reconstruct(AliasingReconstruction.decimate(dense, 2, 0),
					settings(2, .265, 100));
			double[] error = AliasingResampling.errors(dense, recovered, 0, 100, 4);
			assertTrue("kY " + kY + "/um: NRMSE " + error[0], error[0] < .01);
		}
	}

	/** A bead sharper than the target Nyquist in Y: 18% NRMSE with the unwrapped mask, 0.5% with the slab. */
	@Test
	public void aBeadNarrowerThanTheTargetNyquistIsRecoveredAtEveryPhase () {
		ImagePlus dense = raw(10, 128, 129, 33.5, .265, bead(0.30, 0.80));
		for (int phase = 0; phase < 2; phase++) {
			ImagePlus recovered = AliasingReconstruction.reconstruct(AliasingReconstruction.decimate(dense, 2, phase),
					settings(2, .265, 100));
			double[] error = AliasingResampling.errors(dense, recovered, phase, 100, 4);
			assertTrue("phase " + phase + ": NRMSE " + error[0], error[0] < .02);
		}
	}

	/**
	 * The comb repeats the spectrum every nz/m scan bins. A mask that keeps two copies of one
	 * frequency lets an alias through; one that keeps none loses the frequency. Away from exact
	 * ties on the band edge, every set of copies must keep exactly one.
	 */
	@Test
	public void theMaskKeepsExactlyOneCopyOfEveryCombReplica () {
		int nz = 120;
		int ny = 64;
		for (int factor : new int[] { 2, 3, 4 }) {
			AliasingReconstruction.Settings s = settings(factor, .265, 0);
			float[] weights = AliasingReconstruction.weights(nz, ny, s);
			int period = nz / factor;
			int exactlyOne = 0;
			for (int y = 0; y < ny; y++) {
				for (int z0 = 0; z0 < period; z0++) {
					double kept = 0;
					for (int k = 0; k < factor; k++) kept += weights[(z0 + k * period) * ny + y];
					assertTrue("factor " + factor + ", row " + y + ": " + kept + " copies kept", kept <= 1);
					if (kept == 1) exactlyOne++;
				}
			}
			assertTrue("factor " + factor + ": only " + exactlyOne + " of " + ny * period + " sets keep one copy",
					exactlyOne >= 0.98 * ny * period);
		}
	}

	@Test
	public void maskPreservesHermitianSymmetryOnNyquistRows () {
		AliasingReconstruction.Settings s = new AliasingReconstruction.Settings();
		s.taper = .2;
		float[] w = AliasingReconstruction.weights(64, 32, s);
		for (int z = 0; z < 64; z++)
			for (int y = 0; y < 32; y++)
				assertEquals(w[z * 32 + y], w[((64 - z) % 64) * 32 + (32 - y) % 32], 0);
		assertEquals(1, w[0], 0);
	}

	@Test
	public void backgroundsAndPhysicalOriginsArePreserved () {
		ImagePlus dense = gaussian(8, 16, 21, 25);
		dense.getCalibration().zOrigin = 3;
		ImagePlus sparse = AliasingReconstruction.decimate(dense, 3, 1);
		assertEquals(2. / 3, sparse.getCalibration().zOrigin, 1e-12);
		for (int z = 1; z <= sparse.getStackSize(); z++) Arrays.fill((float[]) sparse.getStack().getPixels(z), 100);
		AliasingReconstruction.Settings s = new AliasingReconstruction.Settings();
		s.factor = 3;
		s.acquiredStepUm = .795;
		s.background = 100;
		ImagePlus rec = AliasingReconstruction.reconstruct(sparse, s);
		assertEquals(2, rec.getCalibration().zOrigin, 1e-12);
		for (int z = 1; z <= rec.getStackSize(); z++)
			for (float v : (float[]) rec.getStack().getPixels(z)) assertEquals(100, v, 1e-6);
	}

	@Test
	public void rejectsFractionalFactorsAndInvalidGeometry () {
		try {
			AliasingResampling.integer(2.1, "factor");
			fail();
		} catch (IllegalArgumentException expected) { }
		AliasingReconstruction.Settings s = new AliasingReconstruction.Settings();
		s.angleDegrees = 90;
		try {
			s.validate();
			fail();
		} catch (IllegalArgumentException expected) { }
		s.angleDegrees = 25;
		s.acquiredStepUm = Double.NaN;
		try {
			s.validate();
			fail();
		} catch (IllegalArgumentException expected) { }
	}

	@Test
	public void rejectsHyperstacksRatherThanCombiningChannels () {
		ImagePlus v = gaussian(8, 16, 20, 25);
		v.setDimensions(2, 10, 1);
		try {
			AliasingReconstruction.reconstruct(v, new AliasingReconstruction.Settings());
			fail();
		} catch (IllegalArgumentException expected) { }
	}

	@Test
	public void rejectsNonfinitePixels () {
		ImagePlus v = gaussian(8, 16, 20, 25);
		v.getStack().getProcessor(1).setf(0, Float.NaN);
		try {
			AliasingReconstruction.reconstruct(v, new AliasingReconstruction.Settings());
			fail();
		} catch (IllegalArgumentException expected) { }
	}

	@Test
	public void factorOnePreservesArbitrarySignedImage () {
		ImagePlus v = gaussian(8, 16, 20, 25);
		v.getStack().getProcessor(5).setf(40, -23);
		AliasingReconstruction.Settings s = new AliasingReconstruction.Settings();
		s.factor = 1;
		ImagePlus r = AliasingReconstruction.reconstruct(v, s);
		for (int z = 1; z <= 20; z++)
			for (int i = 0; i < 128; i++)
				assertEquals(v.getStack().getProcessor(z).getf(i), r.getStack().getProcessor(z).getf(i), .001);
	}

	/**
	 * The inspector measures support with the reconstruction's own band distance. Unwrapped,
	 * a bead with the measured cohort's widths reported a bound of 0.47 at 5% - "even the dense
	 * scan is too coarse". The analytic Gaussian OTF gives 1/(2 d tan(theta) Kz) = 3.23.
	 */
	@Test
	public void theSpectrumSupportOfADenseBeadMatchesItsAnalyticOtf () {
		ImagePlus dense = raw(4, 128, 129, 33.5, .265, bead(0.45, 1.04));
		AliasingReconstruction.Settings s = settings(1, .265, 100);
		double sigmaZ = 1.04 / 2.3548;
		double kz = Math.sqrt(Math.log(1 / 0.05) / (2 * Math.PI * Math.PI * sigmaZ * sigmaZ));
		double expected = 1 / (2 * .265 * Math.tan(Math.toRadians(33.5)) * kz);
		double bound = AliasingReconstruction.spectrum(dense, s, 0.05).factorBound();
		assertEquals("factor bound", expected, bound, 0.03 * expected);
	}

	/** An over-subtracted background makes the integrated signal negative; it used to pass through its absolute value. */
	@Test
	public void theSpectrumRefusesAnOverSubtractedBackgroundAndNonfinitePixels () {
		ImagePlus dense = raw(4, 64, 65, 33.5, .265, bead(0.45, 1.04));
		try {
			AliasingReconstruction.spectrum(dense, settings(1, .265, 200), 0.05);
			fail("a negative integrated signal was accepted");
		} catch (IllegalArgumentException expected) { }
		dense.getStack().getProcessor(3).setf(1, 1, Float.POSITIVE_INFINITY);
		try {
			AliasingReconstruction.spectrum(dense, settings(1, .265, 100), 0.05);
			fail("an infinite pixel was accepted");
		} catch (IllegalArgumentException expected) { }
	}

	@Test
	public void metadataMismatchDoesNotAssignSparseStepToDenseStack () throws Exception {
		File file = File.createTempFile("aliasing-metadata-", ".txt");
		try {
			Files.write(file.toPath(), ("Tilt angle: 25\nStep size /um: 2\nPixel size at object /um: 0.116\n"
					+ "Planes per volume: 91\nExposure time /ms: 100\n").getBytes(StandardCharsets.UTF_8));
			assertEquals(2, AliasingMetadata.read(file, 91).step, 0);
			assertTrue(AliasingMetadata.read(file, 901).note.contains("UNVERIFIED"));
			assertEquals(.265, AliasingMetadata.read(file, 901).step, 0);
		} finally {
			Files.delete(file.toPath());
		}
	}

	/** The deskew takes its step from the galvo settings (IO.loadExperimentalParametersFromFile); so does this. */
	@Test
	public void metadataTakesTheStepTheDeskewUses () throws Exception {
		File file = File.createTempFile("aliasing-metadata-", ".txt");
		try {
			Files.write(file.toPath(), ("Tilt angle: 33.5\num per galvo DU: 0.013250\nGalvo DUs per step: 20\n"
					+ "Step size /um: 0.3\nPlanes per volume: 451\nPixel size at object /um: 0.116\n")
					.getBytes(StandardCharsets.UTF_8));
			assertEquals(.265, AliasingMetadata.read(file, 451).step, 1e-9);
		} finally {
			Files.delete(file.toPath());
		}
	}

	@Test
	public void clijMatchesCpuIncludingAbsoluteIntensity () {
		Assume.assumeTrue(Boolean.getBoolean("opm.test.gpu"));
		System.out.println("Aliasing GPU devices: " + net.haesleinhuepf.clij.CLIJ.getAvailableDeviceNames());
		System.out.println("Aliasing selected GPU: " + net.haesleinhuepf.clij2.CLIJ2.getInstance().getGPUName());
		ImagePlus sparse = AliasingReconstruction.decimate(gaussian(35, 96, 97, 33.5), 2, 0);
		AliasingReconstruction.Settings s = new AliasingReconstruction.Settings();
		s.background = 100;
		ImagePlus cpu = AliasingReconstruction.reconstruct(sparse, s);
		s.gpu = true;
		ImagePlus gpu = AliasingReconstruction.reconstruct(sparse, s);
		double max = 0;
		for (int z = 1; z <= cpu.getStackSize(); z++)
			for (int i = 0; i < cpu.getWidth() * cpu.getHeight(); i++)
				max = Math.max(max, Math.abs(cpu.getStack().getProcessor(z).getf(i) - gpu.getStack().getProcessor(z).getf(i)));
		System.out.println("Aliasing CPU/GPU maximum absolute error: " + max);
		assertTrue("CPU/GPU max error " + max, max < .01);
		if (System.getProperty("opm.test.aliasing.width") != null)
			sparse = AliasingReconstruction.decimate(gaussian(Integer.getInteger("opm.test.aliasing.width", 35), 96, 97, 33.5), 2, 0);
		for (int repeat = 0; repeat < 3; repeat++) {
			for (boolean useGpu : new boolean[] { false, true }) {
				s.gpu = useGpu;
				long start = System.nanoTime();
				AliasingReconstruction.reconstruct(sparse, s).close();
				System.out.println("ALIAS_TIMING," + (useGpu ? "CLIJ2-FFT" : "JTransforms") + "," + repeat + ","
						+ (System.nanoTime() - start) / 1e9);
			}
		}
	}
}
