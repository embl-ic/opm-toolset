package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

/**
 * Regression tests for the CPU processing path.
 *
 * <p>These all run headless and without a GPU, which is the point: the CPU path is the
 * fallback used exactly when there is no display and no working OpenCL device, and three
 * of its methods used to fail silently in that situation.
 *
 * <p>Covered here:
 * <ul>
 * <li>{@link CPU#transform} sizes its output from {@link Transform#getTransformedDim}, the
 *     same authority {@link GPU#transform} allocates from. It previously let imagescience
 *     pick the size, which rounds where getTransformedDim ceils, so the two paths returned
 *     volumes differing by one voxel and were not interchangeable.</li>
 * <li>{@link CPU#permute} and {@link CPU#transpose} reorder axes by copying voxels. They
 *     previously drove the ImageJ "Reslice [/]..." menu command and fetched the result from
 *     the WindowManager, neither of which exists headless.</li>
 * <li>{@link CPU#projection_x} and {@link CPU#projection_y} build on that reorder, and so
 *     returned null headless.</li>
 * <li>{@link CPU#scale} resamples Z directly instead of through "Reslice Z" plus the
 *     WindowManager, which returned null and then threw a NullPointerException.</li>
 * </ul>
 */
public class CpuTransformTest {

	private static final int[][] SHAPES = {
		{ 78, 66, 21 }, { 64, 48, 16 }, { 100, 80, 32 }, { 53, 37, 11 }, { 40, 30, 9 }
	};
	/** { z step size, xy pixel size, OPM angle } in the units Transform.deskew expects. */
	private static final double[][] GEOMETRIES = {
		{ 0.400, 0.116, 25.0 }, { 0.265, 0.116, 25.0 }, { 0.132, 0.116, 30.0 }
	};

	/**
	 * The deskew output size must come from Transform.getTransformedDim.
	 *
	 * <p>This is the guard on the GPU/CPU fallback being interchangeable. GPU.transform
	 * allocates its output buffer from getTransformedDim, so if the CPU path agrees with
	 * getTransformedDim it agrees with the GPU path, and this assertion needs no GPU to run.
	 */
	@Test
	public void cpuTransformMatchesCanonicalOutputSize() {
		for (double[] geometry : GEOMETRIES) {
			for (int[] shape : SHAPES) {
				ImagePlus input = ramp(shape[0], shape[1], shape[2]);
				double[][] matrix = Transform.deskew(geometry[0], geometry[1], geometry[2], shape[1]);
				long[] expected = Transform.getTransformedDim(
						input.getDimensions(true), Transform.copy(matrix), false);

				ImagePlus result = CPU.transform(input, Transform.copy(matrix));

				String label = describe(shape, geometry);
				assertNotNull(label + ": transform returned null", result);
				assertEquals(label + ": width", expected[0], result.getWidth());
				assertEquals(label + ": height", expected[1], result.getHeight());
				assertEquals(label + ": depth", expected[2], result.getStackSize());
			}
		}
	}

	/** fitToSize pads with zero at the far edge and leaves existing voxels where they were. */
	@Test
	public void fitToSizePadsAtTheFarEdgeWithoutMovingContent() {
		ImagePlus input = ramp(6, 5, 4);
		ImagePlus padded = CPU.fitToSize(input, new long[] { 8, 7, 6 });

		assertEquals(8, padded.getWidth());
		assertEquals(7, padded.getHeight());
		assertEquals(6, padded.getStackSize());
		for (int z = 1; z <= 4; z++) {
			ImageProcessor before = input.getStack().getProcessor(z);
			ImageProcessor after = padded.getStack().getProcessor(z);
			for (int y = 0; y < 5; y++)
				for (int x = 0; x < 6; x++)
					assertEquals("voxel " + x + "," + y + "," + z, before.getf(x, y), after.getf(x, y), 0f);
		}
		assertEquals("padding is zero", 0f, padded.getStack().getProcessor(6).getf(7, 6), 0f);
	}

	/** fitToSize crops from the far edge, and returns the input untouched when nothing is needed. */
	@Test
	public void fitToSizeCropsAndIsIdentityWhenSizeAlreadyMatches() {
		ImagePlus input = ramp(6, 5, 4);

		ImagePlus cropped = CPU.fitToSize(input, new long[] { 4, 3, 2 });
		assertEquals(4, cropped.getWidth());
		assertEquals(3, cropped.getHeight());
		assertEquals(2, cropped.getStackSize());
		assertEquals(input.getStack().getProcessor(1).getf(3, 2),
				cropped.getStack().getProcessor(1).getf(3, 2), 0f);

		assertTrue("no copy when the size already matches",
				input == CPU.fitToSize(input, new long[] { 6, 5, 4 }));
	}

	/**
	 * Every axis reorder must place each voxel where the order string says.
	 *
	 * <p>The order names the input axis that becomes the output width, height and depth, so
	 * "-&gt;ZYX" produces ZY slices stacked along X. Checked against a direct index mapping
	 * rather than against another implementation.
	 */
	@Test
	public void permuteReordersAxesExactly() {
		int w = 7, h = 5, d = 3;
		ImagePlus input = ramp(w, h, d);
		int[] extent = { w, h, d };
		String[] orders = { "->XYZ", "->YXZ", "->ZYX", "->XZY", "->YZX", "->ZXY" };

		for (String order : orders) {
			int[] axis = axisOrder(order);
			ImagePlus result = CPU.permute(input, order);

			assertNotNull(order + ": permute returned null", result);
			assertEquals(order + ": width", extent[axis[0]], result.getWidth());
			assertEquals(order + ": height", extent[axis[1]], result.getHeight());
			assertEquals(order + ": depth", extent[axis[2]], result.getStackSize());

			int[] coord = new int[3];
			for (int c = 0; c < result.getStackSize(); c++) {
				ImageProcessor slice = result.getStack().getProcessor(c + 1);
				for (int b = 0; b < result.getHeight(); b++) {
					for (int a = 0; a < result.getWidth(); a++) {
						coord[axis[0]] = a;
						coord[axis[1]] = b;
						coord[axis[2]] = c;
						float expected = rampValue(coord[0], coord[1], coord[2]);
						assertEquals(order + ": voxel " + a + "," + b + "," + c,
								expected, slice.getf(a, b), 0f);
					}
				}
			}
		}
	}

	/** transpose is the two-axis special case of the same reorder, and must agree with it. */
	@Test
	public void transposeAgreesWithPermute() {
		ImagePlus input = ramp(7, 5, 3);
		String[][] equivalent = { { "xy", "->YXZ" }, { "xz", "->ZYX" }, { "yz", "->XZY" } };

		for (String[] pair : equivalent) {
			ImagePlus viaTranspose = CPU.transpose(input, pair[0]);
			ImagePlus viaPermute = CPU.permute(input, pair[1]);
			assertSameVolume(pair[0] + " vs " + pair[1], viaPermute, viaTranspose);
		}
	}

	/**
	 * X and Y projections must work with no GUI.
	 *
	 * <p>They previously returned null headless, which in an unattended batch or live run
	 * showed up only as a missing output file.
	 */
	@Test
	public void projectionsAlongEveryAxisWorkHeadless() {
		int w = 11, h = 9, d = 5;
		ImagePlus input = ramp(w, h, d);

		for (String type : new String[] { "max", "min", "avg", "sum" }) {
			ImagePlus xProjection = CPU.projection_x(input, type);
			ImagePlus yProjection = CPU.projection_y(input, type);
			ImagePlus zProjection = CPU.projection_z(input, type);

			assertNotNull(type + " X projection is null", xProjection);
			assertNotNull(type + " Y projection is null", yProjection);
			assertNotNull(type + " Z projection is null", zProjection);

			assertEquals(type + " X projection width", d, xProjection.getWidth());
			assertEquals(type + " X projection height", h, xProjection.getHeight());
			assertEquals(type + " Y projection width", w, yProjection.getWidth());
			assertEquals(type + " Y projection height", d, yProjection.getHeight());
			assertEquals(type + " Z projection width", w, zProjection.getWidth());
			assertEquals(type + " Z projection height", h, zProjection.getHeight());
		}
	}

	/** A max X projection reduces over X, so its value at (z, y) is the row maximum. */
	@Test
	public void maxProjectionsReduceOverTheRightAxis() {
		int w = 11, h = 9, d = 5;
		ImagePlus input = ramp(w, h, d);

		ImageProcessor xProjection = CPU.projection_x(input, "max").getProcessor();
		for (int z = 0; z < d; z++) {
			for (int y = 0; y < h; y++) {
				float expected = Float.NEGATIVE_INFINITY;
				for (int x = 0; x < w; x++) expected = Math.max(expected, rampValue(x, y, z));
				assertEquals("max X at z=" + z + " y=" + y, expected, xProjection.getf(z, y), 0f);
			}
		}

		ImageProcessor yProjection = CPU.projection_y(input, "max").getProcessor();
		for (int z = 0; z < d; z++) {
			for (int x = 0; x < w; x++) {
				float expected = Float.NEGATIVE_INFINITY;
				for (int y = 0; y < h; y++) expected = Math.max(expected, rampValue(x, y, z));
				assertEquals("max Y at x=" + x + " z=" + z, expected, yProjection.getf(x, z), 0f);
			}
		}
	}

	/** scale must return a resampled volume headless instead of throwing on a null window. */
	@Test
	public void scaleWorksHeadless() {
		ImagePlus input = ramp(40, 32, 20);

		assertVolume(CPU.scale(input, 0.5, 0.5, 0.5), 20, 16, 10);
		assertVolume(CPU.scale(input, 0.25, 0.25, 0.25), 10, 8, 5);
		assertVolume(CPU.scale(input, 2.0, 2.0, 2.0), 80, 64, 40);
	}

	/** scaleZ keeps the first and last slice and interpolates between them. */
	@Test
	public void scaleZKeepsTheEndSlices() {
		ImagePlus input = ramp(4, 3, 5);
		ImagePlus stretched = CPU.scaleZ(input, 2.0);

		assertEquals(10, stretched.getStackSize());
		assertEquals("first slice is preserved",
				input.getStack().getProcessor(1).getf(2, 1),
				stretched.getStack().getProcessor(1).getf(2, 1), 0f);
		assertEquals("last slice is preserved",
				input.getStack().getProcessor(5).getf(2, 1),
				stretched.getStack().getProcessor(10).getf(2, 1), 0f);
		assertTrue("no copy when the slice count already matches",
				input == CPU.scaleZ(input, 1.0));
	}

	// ---- helpers -------------------------------------------------------------------

	/** A volume whose every voxel value identifies its own coordinate, so misplacement shows. */
	private static ImagePlus ramp(int w, int h, int d) {
		ImageStack stack = new ImageStack(w, h);
		for (int z = 0; z < d; z++) {
			ShortProcessor slice = new ShortProcessor(w, h);
			for (int y = 0; y < h; y++)
				for (int x = 0; x < w; x++)
					slice.set(x, y, (int) rampValue(x, y, z));
			stack.addSlice(slice);
		}
		return new ImagePlus("ramp", stack);
	}

	private static float rampValue(int x, int y, int z) {
		return x + 100 * y + 10000 * z;		// unique per coordinate for the sizes used here
	}

	private static int[] axisOrder(String order) {
		String axes = order.substring(2);
		int[] result = new int[3];
		for (int i = 0; i < 3; i++) result[i] = "XYZ".indexOf(axes.charAt(i));
		return result;
	}

	private static void assertVolume(ImagePlus imp, int w, int h, int d) {
		assertNotNull("scale returned null", imp);
		assertEquals("width", w, imp.getWidth());
		assertEquals("height", h, imp.getHeight());
		assertEquals("depth", d, imp.getStackSize());
	}

	private static void assertSameVolume(String label, ImagePlus expected, ImagePlus actual) {
		assertNotNull(label + ": null result", actual);
		assertEquals(label + ": width", expected.getWidth(), actual.getWidth());
		assertEquals(label + ": height", expected.getHeight(), actual.getHeight());
		assertEquals(label + ": depth", expected.getStackSize(), actual.getStackSize());
		for (int z = 1; z <= expected.getStackSize(); z++) {
			ImageProcessor a = expected.getStack().getProcessor(z);
			ImageProcessor b = actual.getStack().getProcessor(z);
			for (int i = 0; i < a.getPixelCount(); i++)
				assertEquals(label + ": slice " + z + " index " + i, a.getf(i), b.getf(i), 0f);
		}
	}

	private static String describe(int[] shape, double[] geometry) {
		return shape[0] + "x" + shape[1] + "x" + shape[2]
				+ " zStep=" + geometry[0] + " angle=" + geometry[2];
	}
}
