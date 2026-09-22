package de.embl.iclm;

import ij.ImagePlus;
import ij.WindowManager;
import ij.measure.Calibration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Precompute Batch TIFF projections, then perform only disk IO on the TIFF writer thread. */
final class BatchTiffOutput implements AutoCloseable {

	private static final class ProjectionOutput {
		final ImagePlus image;
		final String type;
		final String axis;

		ProjectionOutput(ImagePlus image, String type, String axis) {
			this.image = image;
			this.type = type;
			this.axis = axis;
		}
	}

	private final ImagePlus volume;
	private final List<ProjectionOutput> projections = new ArrayList<ProjectionOutput>();

	private BatchTiffOutput(ImagePlus volume) {
		this.volume = volume;
	}

	/** Prepare calibration and every requested MIP before the two disk writers are submitted. */
	static BatchTiffOutput prepare(ImagePlus volume, Parameter parameter) throws IOException {
		List<ProjectionBatch.Request> requests = parameter.doProjection
				? projectionRequests(parameter) : new ArrayList<ProjectionBatch.Request>();
		return prepare(volume, calibrationFor(parameter), requests, parameter.tryGPU,
				parameter.makeTimeLapse);
	}

	/**
	 * Prepare exactly the projections asked for, rather than every axis crossed with every type.
	 * <p>
	 * For a caller that offers one check box per view: ticking maxX and meanZ must not also
	 * produce maxZ and meanX, which is what the cross product of the two lists would.
	 */
	static BatchTiffOutput prepare(ImagePlus volume, Calibration calibration,
			List<ProjectionBatch.Request> requests, boolean tryGpu) throws IOException {
		return prepare(volume, calibration, requests, tryGpu, false);
	}

	private static BatchTiffOutput prepare(ImagePlus volume, Calibration calibration,
			List<ProjectionBatch.Request> requests, boolean tryGpu, boolean timeLapse)
			throws IOException {
		if (volume == null) throw new IOException("The prepared TIFF volume is empty.");
		BatchTiffOutput output = new BatchTiffOutput(volume);
		try {
			volume.setCalibration(calibration);
			if (!requests.isEmpty() && !volume.getStack().isVirtual()) {
				String name = Utils.getName(volume);
				List<ImagePlus> prepared = ProjectionBatch.supports(volume, requests)
						? ProjectionBatch.compute(volume, requests, tryGpu) : null;
				for (int request = 0; request < requests.size(); request++) {
					String axis = String.valueOf(requests.get(request).axis);
					String type = requests.get(request).type;
					ImagePlus projection = prepared == null
							? Projection.projection(volume, axis, type, tryGpu)
							: prepared.get(request);
					if (projection == null)
						throw new IOException("Could not prepare TIFF " + type + axis + " projection.");
					String title = name + "-" + type + axis + "projection";
					projection.setTitle(title);
					projection.setCalibration(calibration.copy());
					projection.getImageStack().setSliceLabel(title, 1);
					output.projections.add(new ProjectionOutput(projection, type, axis));
					if (timeLapse) {
						String movieTitle = "-" + type + axis + "projection";
						Partition.combineTimelapse(WindowManager.getImage(movieTitle), projection, movieTitle);
					}
				}
			}
			return output;
		} catch (Throwable failure) {
			output.close();
			if (failure instanceof IOException) throw (IOException) failure;
			throw new IOException("Could not prepare TIFF output.", failure);
		}
	}

	/**
	 * Where a result volume is written: {@code deskew/} when results are separated by view.
	 * <p>
	 * The one naming rule for TIFF results, kept here so every command that writes one - Deskew
	 * Batch, Live, Channel Operation - lands in the layout {@link TiffResultDataset} reads.
	 */
	static File volumeFile(File saveDir, boolean separate, String volumeName) {
		File folder = separate ? new File(saveDir, "deskew") : saveDir;
		return new File(folder, VolumeIO.tiffPath(volumeName));
	}

	/** Where one projection of a result volume is written; {@code type} is max, avg, ... */
	static File projectionFile(File saveDir, boolean separate, String volumeName,
			String type, String axis) {
		File folder = separate ? new File(saveDir, type + axis) : saveDir;
		return new File(folder, VolumeIO.tiffPath(volumeName + "-" + type + axis + "projection"));
	}

	/** Whether skip mode still needs any requested TIFF file for this time point. */
	static boolean needsWrite(Parameter parameter, String volumeName) {
		if (parameter == null || "overwrite".equals(parameter.fileExistStr)) return true;
		File saveDir = new File(parameter.saveDir);
		if (parameter.saveDeskewImage
				&& !VolumeIO.isCompleteTiff(volumeFile(saveDir, parameter.saveSeparate, volumeName)))
			return true;
		if (parameter.doProjection) {
			for (String axis : parameter.projAxes) for (String type : parameter.projTypes)
				if (!VolumeIO.isCompleteTiff(projectionFile(saveDir, parameter.saveSeparate,
						volumeName, type, axis))) return true;
		}
		return false;
	}

	private static List<ProjectionBatch.Request> projectionRequests(Parameter parameter) {
		List<ProjectionBatch.Request> requests = new ArrayList<ProjectionBatch.Request>();
		for (String axis : parameter.projAxes) for (String type : parameter.projTypes)
			requests.add(new ProjectionBatch.Request(axis, type));
		return requests;
	}

	/** Write the volume and precomputed projections through the custom TIFF writer. */
	void write(Parameter parameter) throws IOException {
		write(new File(parameter.saveDir), parameter.saveSeparate, parameter.saveDeskewImage,
				"overwrite".equals(parameter.fileExistStr));
	}

	/**
	 * Write the prepared result under {@code saveDir}, in the layout {@link TiffResultDataset}
	 * reads: one sub-folder per view when {@code separate}, all in one folder otherwise.
	 */
	void write(File saveDir, boolean separate, boolean saveVolume, boolean overwrite)
			throws IOException {
		String name = Utils.getName(volume);
		if (saveVolume)
			write(volume, volumeFile(saveDir, separate, name), overwrite, "deskew TIFF");
		for (ProjectionOutput projection : projections)
			write(projection.image, projectionFile(saveDir, separate, name,
					projection.type, projection.axis), overwrite, "projection TIFF");
	}

	static void write(
			ImagePlus image, File file, boolean overwrite, String description) throws IOException {
		// an incomplete file is a write that was cut off, not a result to keep
		if (!overwrite && VolumeIO.isCompleteTiff(file)) return;
		File parent = file.getParentFile();
		if (parent != null) Files.createDirectories(parent.toPath());
		if (!VolumeIO.saveTiff(image, file))
			throw new IOException("Could not save " + description + ": " + file);
	}

	/**			The calibration a deskewed result carries
	 * <p>		Isotropic at the camera pixel size, because the deskew affine is evaluated on a grid
	 * <br>		measured in camera pixels; the stage step and the OPM angle decide the transformed
	 * <br>		bounds, not the output voxel pitch. Shared with the CLIJ fast path, which pulls its
	 * <br>		results off the GPU and so has no calibration of its own to keep.
	 */
	static Calibration calibrationFor(Parameter parameter) {
		Calibration calibration = new Calibration();
		calibration.pixelWidth = calibration.pixelHeight = calibration.pixelDepth
				= parameter.xyPixelSize / 1000.0;
		calibration.setUnit("micron");
		calibration.frameInterval = parameter.frameInterval;
		calibration.setTimeUnit("second");
		return calibration;
	}

	@Override
	public void close() {
		for (ProjectionOutput projection : projections)
			BatchProcessingUtils.close(projection.image);
		projections.clear();
	}
}
