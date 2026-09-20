package de.embl.iclm;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
import net.haesleinhuepf.clij.clearcl.ClearCLImage;
import net.haesleinhuepf.clij.clearcl.enums.ImageChannelDataType;
import net.haesleinhuepf.clij.coremem.enums.NativeTypeEnum;
import net.haesleinhuepf.clij2.CLIJ2;

import java.nio.FloatBuffer;
import java.util.HashMap;

/** Runtime-only horizontal flip and per-Z-plane XY rigid alignment. */
final class OpmRuntimeAlignment {

	private OpmRuntimeAlignment() {}

	/** CPU reference implementation used in headless tests and as the no-GPU fallback. */
	static ImageProcessor transformPlane(ImageProcessor source, double[][] alignment,
			boolean flip, boolean interpolate) {
		ImageProcessor result = source.duplicate();
		if (!flip) return result;
		result.flipHorizontal();
		return alignment == null ? result : SIFT.alignWithRigid2DMatrix(result, alignment, interpolate);
	}

	/**
	 * Apply a plane transform without the mirror step.
	 * <p>
	 * A projection along X has already collapsed the axis the camera halves are mirrored
	 * about, so there is nothing left to flip, but the alignment's translation along Y still
	 * has to be applied. {@link #transformPlane} cannot express that: it treats a false flip
	 * as "leave this channel alone" and drops the matrix with it.
	 */
	static ImageProcessor transformPlaneWithoutFlip(ImageProcessor source, double[][] alignment,
			boolean interpolate) {
		if (alignment == null) return source.duplicate();
		return SIFT.alignWithRigid2DMatrix(source, alignment, interpolate);
	}

	/**
	 * Run one plane as one GPU operation. The CLIJ kernel expects target-to-source coordinates,
	 * whereas the stored SIFT matrix is source-to-target, hence the explicit inverse.
	 */
	static ImageProcessor transformPlaneGpu(ImageProcessor source, double[][] alignment,
			boolean interpolate) {
		ImagePlus input = new ImagePlus("opm-runtime-plane", source);
		ImagePlus output = transformGpu(input, alignment, interpolate, false);
		try {
			return output.getProcessor().duplicate();
		} finally {
			output.changes = false;
			output.close();
		}
	}

	/** GPU plane transform without the camera-half reflection. */
	static ImageProcessor transformPlaneGpuWithoutFlip(ImageProcessor source, double[][] alignment,
			boolean interpolate) {
		ImagePlus input = new ImagePlus("opm-runtime-plane", source);
		ImagePlus output = transformGpuWithoutFlip(input, alignment, interpolate);
		try {
			return output.getProcessor().duplicate();
		} finally {
			output.changes = false;
			output.close();
		}
	}

	/** One GPU call for a complete Z volume; used by explicit materialisation. */
	static ImagePlus transformVolumeGpu(ImagePlus source, double[][] alignment, boolean interpolate) {
		return transformGpu(source, alignment, interpolate, true);
	}

	/** GPU whole-volume transform for a left/whole source that must not be mirrored. */
	static ImagePlus transformVolumeGpuWithoutFlip(ImagePlus source, double[][] alignment, boolean interpolate) {
		return transformGpuWithoutFlip(source, alignment, interpolate);
	}

	/** CPU whole-volume fallback. */
	static ImagePlus transformVolumeCpu(ImagePlus source, double[][] alignment, boolean interpolate) {
		ImageStack stack = new ImageStack(source.getWidth(), source.getHeight());
		for (int z = 1; z <= source.getStackSize(); z++)
			stack.addSlice(source.getStack().getSliceLabel(z),
					transformPlane(source.getStack().getProcessor(z), alignment, true, interpolate));
		ImagePlus result = new ImagePlus(source.getTitle() + "-runtime-aligned", stack);
		if (source.getCalibration() != null) result.setCalibration(source.getCalibration().copy());
		return result;
	}

	/** CPU whole-volume matrix application without the camera-half mirror. */
	static ImagePlus transformVolumeCpuWithoutFlip(ImagePlus source, double[][] alignment, boolean interpolate) {
		ImageStack stack = new ImageStack(source.getWidth(), source.getHeight());
		for (int z = 1; z <= source.getStackSize(); z++)
			stack.addSlice(source.getStack().getSliceLabel(z),
					transformPlaneWithoutFlip(source.getStack().getProcessor(z), alignment, interpolate));
		ImagePlus result = new ImagePlus(source.getTitle() + "-runtime-aligned", stack);
		if (source.getCalibration() != null) result.setCalibration(source.getCalibration().copy());
		return result;
	}

	private static ImagePlus transformGpuWithoutFlip(ImagePlus source, double[][] alignment,
			boolean interpolate) {
		CLIJ2 clij2 = CLIJ2.getInstance();
		ClearCLBuffer input = null;
		ClearCLBuffer output = null;
		ClearCLImage interpolatedInput = null;
		try {
			input = clij2.push(source);
			output = clij2.create(input);
			boolean linear = interpolate && clij2.hasImageSupport();
			double[][] sampling = alignment == null
					? new double[][] { { 1, 0, 0 }, { 0, 1, 0 } }
					: Transform.inverseAlignmentMatrix2D(alignment);
			sampling = halfPixelBasis(sampling);
			float[] matrix = floats(
					sampling[0][0], sampling[0][1], 0, sampling[0][2],
					sampling[1][0], sampling[1][1], 0, sampling[1][2],
					0, 0, 1, 0);
			if (linear) {
				interpolatedInput = clij2.create(input.getDimensions(), ImageChannelDataType.Float);
				clij2.copy(input, interpolatedInput);
				executeAffine(clij2, interpolatedInput, output, matrix,
						"opm_runtime_affine_3d_interpolate.cl", "opm_runtime_affine_3d_interpolate");
			} else {
				executeAffine(clij2, input, output, matrix,
						"opm_runtime_affine_3d_nearest.cl", "opm_runtime_affine_3d_nearest");
			}
			ImagePlus result = clij2.pull(output);
			result.setTitle(source.getTitle() + "-runtime-aligned");
			if (source.getCalibration() != null) result.setCalibration(source.getCalibration().copy());
			return result;
		} finally {
			if (interpolatedInput != null) clij2.release(interpolatedInput);
			if (output != null) clij2.release(output);
			if (input != null) clij2.release(input);
		}
	}

	private static ImagePlus transformGpu(ImagePlus source, double[][] alignment,
			boolean interpolate, boolean volume) {
		CLIJ2 clij2 = CLIJ2.getInstance();
		ClearCLBuffer input = null;
		ClearCLBuffer output = null;
		ClearCLImage interpolatedInput = null;
		try {
			input = clij2.push(source);
			output = clij2.create(input);
			boolean linear = interpolate && clij2.hasImageSupport();
			// Nearest-neighbour must round in the already-flipped coordinate system to
			// reproduce ImageJ's two-stage flip-then-align operation exactly.
			double[][] sampling = linear ? gpuSamplingMatrix(alignment, source.getWidth())
					: gpuNearestSamplingMatrix(alignment);
			if (volume) {
				float[] matrix = floats(
						sampling[0][0], sampling[0][1], 0, sampling[0][2],
						sampling[1][0], sampling[1][1], 0, sampling[1][2],
						0, 0, 1, 0);
				if (linear) {
					interpolatedInput = clij2.create(input.getDimensions(), ImageChannelDataType.Float);
					clij2.copy(input, interpolatedInput);
					executeAffine(clij2, interpolatedInput, output, matrix,
							"opm_runtime_affine_3d_interpolate.cl", "opm_runtime_affine_3d_interpolate");
				} else {
					executeAffine(clij2, input, output, matrix,
							"opm_runtime_affine_3d_nearest.cl", "opm_runtime_affine_3d_nearest");
				}
			} else {
				float[] matrix = floats(
						sampling[0][0], sampling[0][1], sampling[0][2],
						sampling[1][0], sampling[1][1], sampling[1][2]);
				if (linear) {
					interpolatedInput = clij2.create(input.getDimensions(), ImageChannelDataType.Float);
					clij2.copy(input, interpolatedInput);
					executeAffine(clij2, interpolatedInput, output, matrix,
							"opm_runtime_affine_2d_interpolate.cl", "opm_runtime_affine_2d_interpolate");
				} else {
					executeAffine(clij2, input, output, matrix,
							"opm_runtime_affine_2d_nearest.cl", "opm_runtime_affine_2d_nearest");
				}
			}
			ImagePlus result = clij2.pull(output);
			result.setTitle(source.getTitle() + "-runtime-aligned");
			if (source.getCalibration() != null) result.setCalibration(source.getCalibration().copy());
			return result;
		} finally {
			if (interpolatedInput != null) clij2.release(interpolatedInput);
			if (output != null) clij2.release(output);
			if (input != null) clij2.release(input);
		}
	}

	private static void executeAffine(CLIJ2 clij2, Object input, ClearCLBuffer output,
			float[] matrix, String source, String kernel) {
		ClearCLBuffer matrixBuffer = clij2.create(new long[] { matrix.length, 1, 1 }, NativeTypeEnum.Float);
		try {
			matrixBuffer.readFrom(FloatBuffer.wrap(matrix), true);
			HashMap<String, Object> parameters = new HashMap<String, Object>();
			parameters.put("input", input);
			parameters.put("output", output);
			parameters.put("mat", matrixBuffer);
			clij2.execute(OpmRuntimeAlignment.class, source, kernel,
					output.getDimensions(), output.getDimensions(), parameters);
		} finally {
			matrixBuffer.close();
		}
	}

	private static float[] floats(double... values) {
		float[] result = new float[values.length];
		for (int i = 0; i < values.length; i++) result[i] = (float) values[i];
		return result;
	}

	/** Return target-to-original-source coordinates for alignment-after-horizontal-flip. */
	static double[][] inverseCombinedFlipAndAlignment(double[][] alignment, int width) {
		double edge = width - 1.0;
		double[][] flip = {
			{ -1, 0, edge },
			{ 0, 1, 0 },
			{ 0, 0, 1 }
		};
		double[][] forward = alignment == null ? flip : multiply(homogeneous(alignment), flip);
		double[][] compact = {
			{ forward[0][0], forward[0][1], forward[0][2] },
			{ forward[1][0], forward[1][1], forward[1][2] }
		};
		return Transform.inverseAlignmentMatrix2D(compact);
	}

	/**
	 * Convert integer-centred ImageJ/SIFT coordinates to CLIJ's half-pixel-centred kernel
	 * coordinates: p_gpu = A * (q_gpu - 0.5) + 0.5.
	 */
	static double[][] gpuSamplingMatrix(double[][] alignment, int width) {
		double[][] sampling = inverseCombinedFlipAndAlignment(alignment, width);
		return halfPixelBasis(sampling);
	}

	/** Target-to-flipped-source sampling; the nearest kernels reflect the rounded X index. */
	static double[][] gpuNearestSamplingMatrix(double[][] alignment) {
		double[][] sampling = alignment == null
				? new double[][] { { 1, 0, 0 }, { 0, 1, 0 } }
				: Transform.inverseAlignmentMatrix2D(alignment);
		return halfPixelBasis(sampling);
	}

	private static double[][] halfPixelBasis(double[][] sampling) {
		double[][] gpu = Transform.copy(sampling);
		gpu[0][2] += 0.5 - 0.5 * (sampling[0][0] + sampling[0][1]);
		gpu[1][2] += 0.5 - 0.5 * (sampling[1][0] + sampling[1][1]);
		return gpu;
	}

	private static double[][] homogeneous(double[][] matrix) {
		if (matrix == null || matrix.length < 2 || matrix[0] == null || matrix[1] == null ||
				matrix[0].length < 3 || matrix[1].length < 3)
			throw new IllegalArgumentException("A 2 x 3 runtime alignment matrix is required.");
		return new double[][] {
			{ matrix[0][0], matrix[0][1], matrix[0][2] },
			{ matrix[1][0], matrix[1][1], matrix[1][2] },
			{ 0, 0, 1 }
		};
	}

	private static double[][] multiply(double[][] left, double[][] right) {
		double[][] result = new double[3][3];
		for (int row = 0; row < 3; row++)
			for (int column = 0; column < 3; column++)
				for (int i = 0; i < 3; i++) result[row][column] += left[row][i] * right[i][column];
		return result;
	}
}
