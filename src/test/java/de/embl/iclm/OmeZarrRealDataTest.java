package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.io.File;

import org.junit.Assume;
import org.junit.Test;

/** Optional production-data smoke test; enable with {@code -Dopm.test.zarr=<dataset>}. */
public class OmeZarrRealDataTest {

	@Test
	public void opensProductionDatasetVirtually() {
		String configured = System.getProperty("opm.test.zarr", "").trim();
		Assume.assumeTrue("Set -Dopm.test.zarr to run the production Zarr smoke test.", !configured.isEmpty());
		File root = new File(configured);
		Assume.assumeTrue(root.isDirectory());

		long discoveryStart = System.nanoTime();
		OmeZarrDataset dataset = OmeZarrDataset.read(root);
		double discoverySeconds = (System.nanoTime() - discoveryStart) / 1e9;
		System.out.println("OPM viewer metadata discovery: " + (discoverySeconds * 1000) + " ms");
		assertEquals(1600, dataset.getWidth());
		assertEquals(1448, dataset.getHeight());
		assertEquals(276, dataset.getDepth());
		assertEquals(4, dataset.getChannelCount());
		assertEquals(2, dataset.getTimepointCount());
		assertTrue("metadata discovery took " + discoverySeconds + " s", discoverySeconds < 0.5);

		OmeZarrView.Options volumeOptions = new OmeZarrView.Options();
		volumeOptions.tryGpu = false;
		volumeOptions.requestedChannels.add(dataset.getChannelLabels().get(0));
		ImagePlus volume = OmeZarrView.openVirtualVolume(dataset, volumeOptions, 0);
		assertTrue(volume.getStack().isVirtual());
		long sliceStart = System.nanoTime();
		assertEquals(1600, volume.getStack().getProcessor(139).getWidth());
		double sliceSeconds = (System.nanoTime() - sliceStart) / 1e9;
		System.out.println("OPM viewer uncached virtual slice: " + (sliceSeconds * 1000) + " ms");
		assertTrue("uncached virtual slice took " + sliceSeconds + " s", sliceSeconds < 0.1);
		volume.close();

		OmeZarrView.Options projectionOptions = new OmeZarrView.Options();
		projectionOptions.tryGpu = false;
		projectionOptions.requestedChannels.add(dataset.getChannelLabels().get(0));
		long movieStart = System.nanoTime();
		ImagePlus movie = OmeZarrView.openProjectionMovie(dataset, "maxZ", projectionOptions);
		double movieMilliseconds = (System.nanoTime() - movieStart) / 1e6;
		System.out.println("OPM viewer eager two-frame maxZ movie: " + movieMilliseconds + " ms");
		assertTrue("two-frame maxZ movie took " + movieMilliseconds + " ms", movieMilliseconds < 200);
		assertEquals(2, movie.getNFrames());
		assertEquals(1600, movie.getWidth());
		assertEquals(1448, movie.getHeight());
		movie.close();

		if (Boolean.getBoolean("opm.test.gpu")) {
			OmeZarrPlaneReader reader = new OmeZarrPlaneReader(dataset, "s0");
			try {
				ImageProcessor source = reader.readPlane(138, 1, 0);
				double[][] matrix = dataset.getProvenance().alignMatrix;
				OpmRuntimeAlignment.transformPlaneGpu(source, matrix, true); // compile/warm the kernel
				long gpuStart = System.nanoTime();
				ImageProcessor transformed = OpmRuntimeAlignment.transformPlaneGpu(source, matrix, true);
				double gpuMilliseconds = (System.nanoTime() - gpuStart) / 1e6;
				assertEquals(source.getWidth(), transformed.getWidth());
				assertEquals(source.getHeight(), transformed.getHeight());
				System.out.println("OPM viewer warm GPU flip+align: " + gpuMilliseconds + " ms");
				assertTrue("warm GPU flip+align took " + gpuMilliseconds + " ms", gpuMilliseconds < 50);
			} finally {
				try { reader.close(); } catch (java.io.IOException ignored) { }
			}
		}
	}
}
