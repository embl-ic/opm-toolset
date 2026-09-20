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
		final String folder;

		ProjectionOutput(ImagePlus image, String folder) {
			this.image = image;
			this.folder = folder;
		}
	}

	private final ImagePlus volume;
	private final List<ProjectionOutput> projections = new ArrayList<ProjectionOutput>();

	private BatchTiffOutput(ImagePlus volume) {
		this.volume = volume;
	}

	/** Prepare calibration and every requested MIP before the two disk writers are submitted. */
	static BatchTiffOutput prepare(ImagePlus volume, Parameter parameter) throws IOException {
		if (volume == null) throw new IOException("The prepared TIFF volume is empty.");
		BatchTiffOutput output = new BatchTiffOutput(volume);
		try {
			Calibration calibration = calibration(parameter);
			volume.setCalibration(calibration);
			if (parameter.doProjection && !volume.getStack().isVirtual()) {
				String name = Utils.getName(volume);
				List<ProjectionBatch.Request> requests = projectionRequests(parameter);
				List<ImagePlus> prepared = ProjectionBatch.supports(volume, requests)
						? ProjectionBatch.compute(volume, requests, parameter.tryGPU) : null;
				int request = 0;
				for (String axis : parameter.projAxes) for (String type : parameter.projTypes) {
					ImagePlus projection = prepared == null
							? Projection.projection(volume, axis, type, parameter.tryGPU)
							: prepared.get(request++);
					if (projection == null)
						throw new IOException("Could not prepare TIFF " + type + axis + " projection.");
					String title = name + "-" + type + axis + "projection";
					projection.setTitle(title);
					projection.setCalibration(calibration.copy());
					projection.getImageStack().setSliceLabel(title, 1);
					output.projections.add(new ProjectionOutput(projection, type + axis));
					if (parameter.makeTimeLapse) {
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

	/** Whether skip mode still needs any requested TIFF file for this time point. */
	static boolean needsWrite(Parameter parameter, String volumeName) {
		if (parameter == null || "overwrite".equals(parameter.fileExistStr)) return true;
		if (parameter.saveDeskewImage) {
			File folder = parameter.saveSeparate
					? new File(parameter.saveDir, "deskew") : new File(parameter.saveDir);
			if (!VolumeIO.isCompleteTiff(new File(folder, VolumeIO.tiffPath(volumeName)))) return true;
		}
		if (parameter.doProjection) {
			for (String axis : parameter.projAxes) for (String type : parameter.projTypes) {
				File folder = parameter.saveSeparate
						? new File(parameter.saveDir, type + axis) : new File(parameter.saveDir);
				String title = volumeName + "-" + type + axis + "projection";
				if (!VolumeIO.isCompleteTiff(new File(folder, VolumeIO.tiffPath(title)))) return true;
			}
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
		boolean overwrite = "overwrite".equals(parameter.fileExistStr);
		if (parameter.saveDeskewImage) {
			File folder = parameter.saveSeparate
					? new File(parameter.saveDir, "deskew") : new File(parameter.saveDir);
			write(volume, new File(folder, VolumeIO.tiffPath(Utils.getName(volume))), overwrite,
					"deskew TIFF");
		}
		for (ProjectionOutput projection : projections) {
			File folder = parameter.saveSeparate
					? new File(parameter.saveDir, projection.folder) : new File(parameter.saveDir);
			write(projection.image,
					new File(folder, VolumeIO.tiffPath(Utils.getName(projection.image))), overwrite,
					"projection TIFF");
		}
	}

	private static void write(
			ImagePlus image, File file, boolean overwrite, String description) throws IOException {
		// an incomplete file is a write that was cut off, not a result to keep
		if (!overwrite && VolumeIO.isCompleteTiff(file)) return;
		File parent = file.getParentFile();
		if (parent != null) Files.createDirectories(parent.toPath());
		if (!VolumeIO.saveTiff(image, file))
			throw new IOException("Could not save " + description + ": " + file);
	}

	private static Calibration calibration(Parameter parameter) {
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
