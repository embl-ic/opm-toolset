package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;

import org.junit.Assume;
import org.junit.Test;

public class CLIJ2FFTDeconvolutionTest {
	@Test
	public void recognizesOnlyIntegratedIntelDeviceNames() {
		assertTrue(CLIJ2FFTDeconvolution.isIntegratedIntelDeviceName("Intel(R) Iris(R) Xe Graphics"));
		assertTrue(CLIJ2FFTDeconvolution.isIntegratedIntelDeviceName("Intel UHD Graphics 770"));
		assertFalse(CLIJ2FFTDeconvolution.isIntegratedIntelDeviceName("Intel(R) Arc(TM) A770 Graphics"));
		assertFalse(CLIJ2FFTDeconvolution.isIntegratedIntelDeviceName("NVIDIA RTX PRO 2000"));
	}

	@Test
	public void cacheGeometryIsClampedToVolumeAndCellBounds() {
		ImagePlus volume = volume(100, 80, 20);
		try {
			int[] cells = CLIJ2FFTDeconvolution.sanitizeCellSize(volume,
					new int[] { 256, 40, 128 });
			assertArrayEquals(new int[] { 100, 40, 20 }, cells);
			assertArrayEquals(new long[] { 100, 0, 20 },
					CLIJ2FFTDeconvolution.sanitizeOverlap(cells, new long[] { 200, -4, 30 }));
		} finally {
			volume.close();
		}
	}

	@Test
	public void psfOverlapDefaultsToHalfSupportInEachAxis() {
		ImagePlus psf = volume(9, 7, 5);
		try {
			assertArrayEquals(new long[] { 5, 4, 3 }, CLIJ2FFTDeconvolution.defaultOverlap(psf));
		} finally {
			psf.close();
		}
	}

	@Test
	public void releasedCacheBuilderRunsOnConfiguredGpu() {
		Assume.assumeTrue(Boolean.getBoolean("opm.test.gpu"));
		ImagePlus input = volume(16, 16, 8);
		ImagePlus psf = volume(3, 3, 3);
		ImagePlus result = null;
		try {
			input.getStack().getProcessor(4).setf(8, 8, 100f);
			psf.getStack().getProcessor(2).setf(1, 1, 1f);
			result = Partition.tileDeconvolution(input, psf, "Richardson-Lucy (FFT)",
					new int[] { 16, 16, 8 }, new long[] { 0, 0, 0 }, 1, 0.0, false);
			assertNotNull(result);
			assertEquals(16, result.getWidth());
			assertEquals(16, result.getHeight());
			assertEquals(8, result.getStackSize());
		} finally {
			if (result != null) result.close();
			input.close();
			psf.close();
		}
	}

	private static ImagePlus volume(int width, int height, int depth) {
		ImageStack stack = new ImageStack(width, height);
		for (int z = 0; z < depth; z++) stack.addSlice(new FloatProcessor(width, height));
		return new ImagePlus("volume", stack);
	}
}
