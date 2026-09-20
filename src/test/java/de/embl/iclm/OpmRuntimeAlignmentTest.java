package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
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
	public void interpolationBlendsAndNearestNeighbourDoesNot() {
		/* A linear ramp cannot distinguish the two: a 0.25 px bilinear blend of 1,2,3,4 rounds
		 * straight back to 1,2,3,4. A step edge can, which is why this uses one - the previous
		 * data hid a bilinear request silently producing nearest-neighbour output. */
		short[] edge = { 0, 0, 1000, 1000, 0, 0, 1000, 1000 };
		double[][] halfPixel = { { 1, 0, 0.5 }, { 0, 1, 0 } };

		ImageProcessor blended = OpmRuntimeAlignment.transformPlaneWithoutFlip(
				new ShortProcessor(4, 2, edge.clone(), null), halfPixel, true);
		ImageProcessor copied = OpmRuntimeAlignment.transformPlaneWithoutFlip(
				new ShortProcessor(4, 2, edge.clone(), null), halfPixel, false);

		assertEquals("bilinear must blend across the step", 500, blended.get(2, 0));
		assertEquals("nearest neighbour must copy one source pixel", 1000, copied.get(2, 0));
	}

	@Test
	public void floatInterpolationBlendsValuesNotBitPatterns() {
		/* FloatProcessor.getPixel is the float's bit pattern. Blending those gave 2.6e-18 for the
		 * midpoint of 0 and 1000, and 322 for 100 and 1000 - the bead projections in Channel
		 * Alignment are float, so its bilinear overlay dimmed every sub-pixel shift. */
		double[][] halfPixel = { { 1, 0, 0.5 }, { 0, 1, 0 } };
		ImageProcessor fromZero = SIFT.alignWithRigid2DMatrix(
				new FloatProcessor(4, 1, new float[] { 0, 0, 1000, 1000 }), halfPixel, true);
		ImageProcessor fromBackground = SIFT.alignWithRigid2DMatrix(
				new FloatProcessor(4, 1, new float[] { 100, 100, 1000, 1000 }), halfPixel, true);
		ImageProcessor fractional = SIFT.alignWithRigid2DMatrix(
				new FloatProcessor(4, 1, new float[] { 0, 0.25f, 0.75f, 1 }), halfPixel, true);
		ImageProcessor copied = SIFT.alignWithRigid2DMatrix(
				new FloatProcessor(4, 1, new float[] { 0, 0, 1000.5f, 1000.5f }), halfPixel, false);

		assertEquals(500, fromZero.getf(2, 0), 1e-3);
		assertEquals(550, fromBackground.getf(2, 0), 1e-3);
		assertEquals("a float sample is not rounded to an integer", 0.5, fractional.getf(2, 0), 1e-6);
		assertEquals("nearest neighbour still copies the float exactly", 1000.5f, copied.getf(2, 0), 0f);
	}

	@Test
	public void alignmentLeavesTheCallersInterpolationMethodAlone() {
		/* The source's interpolation method is what mpicbg reads, so it has to be set - and put
		 * back, because the caller may still be holding that processor. */
		ShortProcessor source = new ShortProcessor(4, 2, new short[] { 1, 2, 3, 4, 5, 6, 7, 8 }, null);
		source.setInterpolationMethod(ImageProcessor.BICUBIC);
		SIFT.alignWithRigid2DMatrix(source, new double[][] { { 1, 0, 0.5 }, { 0, 1, 0 } }, true);
		assertEquals(ImageProcessor.BICUBIC, source.getInterpolationMethod());
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

		/* A step edge, not the ramp above: only a non-linear signal shows whether both paths
		 * really interpolate, rather than both quietly falling back to nearest neighbour.
		 *
		 * Every pixel is compared, border included. Both paths zero outside the pixel-centre
		 * range [0, n-1] inclusive and blend inside it, so there is no rim to excuse. */
		int edgeWidth = 8, edgeHeight = 6;
		short[] edge = new short[edgeWidth * edgeHeight];
		for (int y = 0; y < edgeHeight; y++)
			for (int x = 0; x < edgeWidth; x++)
				edge[y * edgeWidth + x] = (short) (x < edgeWidth / 2 ? 0 : 1000);
		double[][] halfPixel = { { 1, 0, 0.5 }, { 0, 1, 0 } };
		ImageProcessor cpuInterpolated = OpmRuntimeAlignment.transformPlane(
				new ShortProcessor(edgeWidth, edgeHeight, edge.clone(), null), halfPixel, true, true);
		ImageProcessor gpuInterpolated = OpmRuntimeAlignment.transformPlaneGpu(
				new ShortProcessor(edgeWidth, edgeHeight, edge.clone(), null), halfPixel, true);
		assertArrayEquals((short[]) cpuInterpolated.getPixels(), (short[]) gpuInterpolated.getPixels());
		boolean sawBlend = false;
		for (int y = 0; y < edgeHeight; y++)
			for (int x = 0; x < edgeWidth; x++)
				if (cpuInterpolated.get(x, y) == 500) sawBlend = true;
		assertTrue("a half-pixel shift of a step edge must produce a blended value", sawBlend);

		/* The volume path, on an image large enough to have an inside. A quarter-pixel shift
		 * rather than a half: an exact 0.5 blend lands on a rounding tie that ImageJ and the
		 * OpenCL sampler break in opposite directions. */
		double[][] quarterPixel = { { 1, 0, 0.25 }, { 0, 1, 0 } };
		ImageStack sourceStack = new ImageStack(edgeWidth, edgeHeight);
		for (int z = 0; z < 2; z++) {
			short[] plane = new short[edgeWidth * edgeHeight];
			for (int y = 0; y < edgeHeight; y++)
				for (int x = 0; x < edgeWidth; x++)
					plane[y * edgeWidth + x] = (short) (x < edgeWidth / 2 ? 100 * z : 1000 + 100 * z);
			sourceStack.addSlice(new ShortProcessor(edgeWidth, edgeHeight, plane, null));
		}
		ImagePlus sourceVolume = new ImagePlus("source", sourceStack);
		ImagePlus cpuVolume = OpmRuntimeAlignment.transformVolumeCpu(sourceVolume, quarterPixel, true);
		ImagePlus gpuVolume = OpmRuntimeAlignment.transformVolumeGpu(sourceVolume, quarterPixel, true);
		for (int z = 1; z <= 2; z++)
			assertArrayEquals("CPU and GPU volumes must agree at z=" + z,
					(short[]) cpuVolume.getStack().getPixels(z),
					(short[]) gpuVolume.getStack().getPixels(z));
		sourceVolume.close();
		cpuVolume.close();
		gpuVolume.close();
	}
}
