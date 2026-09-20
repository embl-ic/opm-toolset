package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
import net.haesleinhuepf.clij2.CLIJ2;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * An X projection is depth wide and height high, by every path that makes one.
 *
 * <p>The TIFF fast path gave CLIJ2 a {height, depth} destination. CLIJ2 writes pixel (z, y) and
 * iterates over the destination's own size, so every maxX it saved projected only the first
 * {@code depth} rows, with z past the last slice clamped to it: 1448 x 276 with a peak of 461,
 * beside a maxZ peaking at 17242. The existing projection test used a volume whose height equals
 * its depth, which is exactly the one shape where that swap cannot be seen - so every volume here
 * has all three sizes different.
 */
public class XProjectionShapeTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final int WIDTH = 7, HEIGHT = 5, DEPTH = 3;

	@Test
	public void theSharedShapeIsWhatTheCpuProjectionProduces() {
		ImagePlus volume = volume(WIDTH, HEIGHT, DEPTH);
		long[] dimensions = { WIDTH, HEIGHT, DEPTH };
		List<ProjectionBatch.Request> requests = Arrays.asList(new ProjectionBatch.Request("X", "max"),
				new ProjectionBatch.Request("Y", "max"), new ProjectionBatch.Request("Z", "max"));
		List<ImagePlus> projections = ProjectionBatch.compute(volume, requests, false);
		try {
			char[] axes = { 'X', 'Y', 'Z' };
			for (int i = 0; i < axes.length; i++) {
				long[] shape = ProjectionBatch.outputDimensions(dimensions, axes[i]);
				assertEquals(axes[i] + " width", shape[0], projections.get(i).getWidth());
				assertEquals(axes[i] + " height", shape[1], projections.get(i).getHeight());
			}
			assertArrayEquals("X is depth wide and height high", new long[] { DEPTH, HEIGHT },
					ProjectionBatch.outputDimensions(dimensions, 'X'));
			// pixel (z, y) of maxX is the largest value along that row of that slice
			assertEquals(value(WIDTH - 1, 4, 2), projections.get(0).getProcessor().get(2, 4));
		} finally {
			for (ImagePlus projection : projections) BatchProcessingUtils.close(projection);
		}
	}

	/** CLIJ2's own kernels, into destinations sized by the shared rule, against the CPU. */
	@Test
	public void clij2ProjectionsIntoTheSharedShapeMatchTheCpu() {
		Assume.assumeTrue("Enable with -Dopm.test.gpu=true", Boolean.getBoolean("opm.test.gpu"));
		ImagePlus volume = volume(WIDTH, HEIGHT, DEPTH);
		List<ProjectionBatch.Request> requests = Arrays.asList(new ProjectionBatch.Request("X", "max"),
				new ProjectionBatch.Request("Y", "max"), new ProjectionBatch.Request("Z", "max"));
		List<ImagePlus> cpu = ProjectionBatch.compute(volume, requests, false);
		CLIJ2 clij2 = CLIJ2.getInstance();
		ClearCLBuffer source = clij2.push(volume);
		try {
			char[] axes = { 'X', 'Y', 'Z' };
			for (int i = 0; i < axes.length; i++) {
				ClearCLBuffer destination = clij2.create(
						ProjectionBatch.outputDimensions(source.getDimensions(), axes[i]), source.getNativeType());
				try {
					if (axes[i] == 'X') clij2.maximumXProjection(source, destination);
					else if (axes[i] == 'Y') clij2.maximumYProjection(source, destination);
					else clij2.maximumZProjection(source, destination);
					ImagePlus gpu = clij2.pull(destination);
					assertArrayEquals("max" + axes[i], (short[]) cpu.get(i).getProcessor().getPixels(),
							(short[]) gpu.getProcessor().getPixels());
					BatchProcessingUtils.close(gpu);
				} finally {
					clij2.release(destination);
				}
			}
		} finally {
			clij2.release(source);
			for (ImagePlus projection : cpu) BatchProcessingUtils.close(projection);
		}
	}

	/** The fast deskew path end to end: every projection it saves is the projection of the volume it saves. */
	@Test
	public void theFastPathSavesTheProjectionsOfItsOwnDeskewedVolume() throws Exception {
		Assume.assumeTrue("Enable with -Dopm.test.gpu=true", Boolean.getBoolean("opm.test.gpu"));
		File raw = new File(folder.newFolder("raw"), "run_Time000001_Channel0001.tif");
		assertTrue(VolumeIO.saveTiff(volume(48, 40, 16), raw.getPath()));
		File results = folder.newFolder("results");

		Parameter parameter = new Parameter("fast-projection-test");
		parameter.xyPixelSize = 116;
		parameter.zStepSize = 265;
		parameter.opmAngle = 33.5;
		parameter.saveToSame = false;
		parameter.saveDir = results.getAbsolutePath();
		parameter.saveSeparate = true;
		parameter.projX = parameter.projY = parameter.projZ = true;
		parameter.maxProj = true;
		parameter.avgProj = false;
		parameter.doProjection = true;
		FastClijDeskew.Options options = FastClijDeskew.optionsFromParameter(parameter);
		options.saveDeskewTiff = true;
		options.makeMipMovies = false;
		options.displayMipMovies = false;

		FastClijDeskew.Result result = FastClijDeskew.processFile(raw, parameter, options, null, 0);
		assertTrue(result.allWritten);

		// the fast path names its result the way every deskew route does
		assertEquals("run_Time000001_Channel0001-deskewed", result.name);
		ImagePlus deskewed = VolumeIO.open(new File(new File(results, "deskew"), result.name + ".tif").getPath());
		List<ProjectionBatch.Request> requests = Arrays.asList(new ProjectionBatch.Request("X", "max"),
				new ProjectionBatch.Request("Y", "max"), new ProjectionBatch.Request("Z", "max"));
		List<ImagePlus> expected = ProjectionBatch.compute(deskewed, requests, false);
		try {
			String[] names = { "maxX", "maxY", "maxZ" };
			for (int i = 0; i < names.length; i++) {
				ImagePlus saved = VolumeIO.open(new File(new File(results, names[i]),
						result.name + "-" + names[i] + "projection.tif").getPath());
				assertEquals(names[i] + " width", expected.get(i).getWidth(), saved.getWidth());
				assertEquals(names[i] + " height", expected.get(i).getHeight(), saved.getHeight());
				assertArrayEquals(names[i], (short[]) expected.get(i).getProcessor().getPixels(),
						(short[]) saved.getProcessor().getPixels());
				BatchProcessingUtils.close(saved);
			}
			assertEquals("maxX is depth wide", deskewed.getNSlices(), expected.get(0).getWidth());
		} finally {
			for (ImagePlus projection : expected) BatchProcessingUtils.close(projection);
			BatchProcessingUtils.close(deskewed);
		}
	}

	/** A value unique to its voxel and rising along X, so a projection read from the wrong place shows. */
	private static int value(int x, int y, int z) {
		return 1 + x + 16 * y + 256 * z;
	}

	private static ImagePlus volume(int width, int height, int depth) {
		ImageStack stack = new ImageStack(width, height);
		for (int z = 0; z < depth; z++) {
			short[] pixels = new short[width * height];
			for (int y = 0; y < height; y++)
				for (int x = 0; x < width; x++)
					pixels[y * width + x] = (short) value(x, y, z);
			stack.addSlice(new ShortProcessor(width, height, pixels, null));
		}
		ImagePlus image = new ImagePlus("volume", stack);
		image.setDimensions(1, depth, 1);
		return image;
	}
}
