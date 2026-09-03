package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import org.junit.Assume;
import org.junit.Test;

/** Matrix-direction and mirror tests for the headless CPU reference path. */
public class OpmRuntimeAlignmentTest {

	@Test
	public void flipWithIdentityAlignmentHasExactPixelOrder() {
		short[] pixels = { 1, 2, 3, 4, 5, 6 };
		ImageProcessor transformed = OpmRuntimeAlignment.transformPlane(
				new ShortProcessor(3, 2, pixels, null),
				new double[][] { { 1, 0, 0 }, { 0, 1, 0 } }, true, false);
		assertArrayEquals(new short[] { 3, 2, 1, 6, 5, 4 }, (short[]) transformed.getPixels());
	}

	@Test
	public void gpuSamplingMatrixIsInverseOfFlipThenAlignment() {
		double[][] sampling = OpmRuntimeAlignment.inverseCombinedFlipAndAlignment(
				new double[][] { { 1, 0, 2 }, { 0, 1, -1 } }, 5);
		// Forward is x'=-(x)+6, y'=y-1; target-to-source is x=6-x', y=y'+1.
		org.junit.Assert.assertEquals(-1, sampling[0][0], 0);
		org.junit.Assert.assertEquals(6, sampling[0][2], 0);
		org.junit.Assert.assertEquals(1, sampling[1][1], 0);
		org.junit.Assert.assertEquals(1, sampling[1][2], 0);
		double[][] gpu = OpmRuntimeAlignment.gpuSamplingMatrix(null, 5);
		org.junit.Assert.assertEquals(-1, gpu[0][0], 0);
		org.junit.Assert.assertEquals(5, gpu[0][2], 0);
	}

	@Test
	public void gpuFlipMatchesCpuReferenceWhenExplicitlyEnabled() {
		Assume.assumeTrue("Enable with -Dopm.test.gpu=true", Boolean.getBoolean("opm.test.gpu"));
		short[] pixels = { 1, 2, 3, 4, 5, 6, 7, 8 };
		ImageProcessor gpu = OpmRuntimeAlignment.transformPlaneGpu(
				new ShortProcessor(4, 2, pixels, null), null, false);
		assertArrayEquals(new short[] { 4, 3, 2, 1, 8, 7, 6, 5 }, (short[]) gpu.getPixels());

		double[][] translated = { { 1, 0, 1 }, { 0, 1, 0 } };
		ImageProcessor cpuAligned = OpmRuntimeAlignment.transformPlane(
				new ShortProcessor(4, 2, pixels, null), translated, true, false);
		ImageProcessor gpuAligned = OpmRuntimeAlignment.transformPlaneGpu(
				new ShortProcessor(4, 2, pixels, null), translated, false);
		assertArrayEquals((short[]) cpuAligned.getPixels(), (short[]) gpuAligned.getPixels());

		double[][] subpixel = { { 1, 0, 0.25 }, { 0, 1, 0 } };
		ImageProcessor cpuInterpolated = OpmRuntimeAlignment.transformPlane(
				new ShortProcessor(4, 2, pixels, null), subpixel, true, true);
		ImageProcessor gpuInterpolated = OpmRuntimeAlignment.transformPlaneGpu(
				new ShortProcessor(4, 2, pixels, null), subpixel, true);
		assertArrayEquals((short[]) cpuInterpolated.getPixels(), (short[]) gpuInterpolated.getPixels());

		ImageStack sourceStack = new ImageStack(4, 2);
		sourceStack.addSlice(new ShortProcessor(4, 2, pixels.clone(), null));
		sourceStack.addSlice(new ShortProcessor(4, 2,
				new short[] { 11, 12, 13, 14, 15, 16, 17, 18 }, null));
		ImagePlus sourceVolume = new ImagePlus("source", sourceStack);
		ImagePlus cpuVolume = OpmRuntimeAlignment.transformVolumeCpu(sourceVolume, subpixel, true);
		ImagePlus gpuVolume = OpmRuntimeAlignment.transformVolumeGpu(sourceVolume, subpixel, true);
		for (int z = 1; z <= 2; z++)
			assertArrayEquals((short[]) cpuVolume.getStack().getPixels(z),
					(short[]) gpuVolume.getStack().getPixels(z));
		sourceVolume.close();
		cpuVolume.close();
		gpuVolume.close();
	}
}
