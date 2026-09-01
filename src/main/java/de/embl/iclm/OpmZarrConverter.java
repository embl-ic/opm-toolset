package de.embl.iclm;

import ij.IJ;
import ij.ImagePlus;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;

/** Converts raw OPM acquisition TIFFs into one resumable canonical OME-Zarr dataset. */
public class OpmZarrConverter {

	public static final String[] PROJECTIONS = OpmZarrSession.PROJECTIONS.toArray(new String[6]);

	public static class Options {
		public double xyPixelSizeUm = 0.116;
		public double zStepSizeUm = 0.265;
		public double opmAngleDegrees = 25.0;
		public double frameIntervalSeconds = 0;
		public boolean flipRight = true;
		public boolean alignInterpolate = true;
		public boolean tryGPU = true;
		public double[][] alignMatrix = null;
		public String alignMatrixSource = null;
		public String experimentalParametersFile = null;
		public boolean writeProjections = true;
		/** Delete an existing output before conversion; otherwise a compatible partial output resumes. */
		public boolean overwriteExisting = false;
	}

	/** Convert every matching TIFF in one acquisition folder. */
	public static OpmProvenance convert(
			File inputFolder, File zarrRoot, Options options) throws Exception {
		List<File> files = BatchProcessingUtils.listTiffs(inputFolder, "", false);
		return convertFiles(inputFolder, files, zarrRoot, options);
	}

	/** Convert an explicitly filtered file list, used by the registered Batch workflows. */
	public static OpmProvenance convertFiles(
			File inputFolder, List<File> inputFiles, File zarrRoot, Options options) throws Exception {
		if (inputFolder == null || !inputFolder.isDirectory())
			throw new IllegalArgumentException("Invalid OPM input folder: " + inputFolder);
		if (options == null) options = new Options();
		if (inputFiles == null || inputFiles.isEmpty())
			throw new IllegalArgumentException("No TIFF found in " + inputFolder);

		List<OpmTimepointProcessor.TimePoint> timePoints = OpmTimepointProcessor.completeTimePoints(
				inputFiles, options.frameIntervalSeconds);
		if (timePoints.isEmpty()) throw new IllegalArgumentException("No complete time point found.");

		if (options.overwriteExisting && zarrRoot.exists()) FileUtils.deleteDirectory(zarrRoot);

		ImagePlus firstRaw = VolumeIO.open(timePoints.get(0).files.get(0).getAbsolutePath());
		if (firstRaw == null) throw new IllegalArgumentException("Could not read the first volume.");
		double[][] deskewMatrix;
		try {
			deskewMatrix = Transform.deskew(options.zStepSizeUm, options.xyPixelSizeUm,
					options.opmAngleDegrees, firstRaw.getHeight());
		} finally {
			BatchProcessingUtils.close(firstRaw);
		}

		OpmProvenance provenance = provenance(inputFolder, zarrRoot, options, deskewMatrix);
		OpmZarrSession session = new OpmZarrSession(zarrRoot, inputFolder, provenance,
				deskewMatrix, options.tryGPU, options.writeProjections);
		try {
			IJ.log("OPM Zarr: " + timePoints.size() + " complete time point(s); output "
					+ zarrRoot.getAbsolutePath());
			int index = 0;
			for (OpmTimepointProcessor.TimePoint timePoint : timePoints) {
				index++;
				if (session.isCommitted(timePoint.label)) {
					IJ.log("OPM Zarr: already committed, skipping " + timePoint.label);
					continue;
				}
				IJ.showProgress(index - 1, timePoints.size());
				IJ.log("OPM Zarr: processing " + timePoint.label + " (" + index + "/"
						+ timePoints.size() + ")");
				session.append(timePoint);
				Utils.collectGarbage();
			}
			session.markComplete();
			IJ.showProgress(1.0);
			return session.getProvenance();
		} finally {
			session.close();
		}
	}

	/** Translate the existing nm-based UI parameters into the µm-based format contract. */
	static Options optionsFromParameter(Parameter parameter, ChannelOperationSettings channels, File sourceFolder) {
		Options options = new Options();
		options.xyPixelSizeUm = parameter.xyPixelSize / 1000.0;
		options.zStepSizeUm = parameter.zStepSize / 1000.0;
		options.opmAngleDegrees = parameter.opmAngle;
		options.frameIntervalSeconds = parameter.frameInterval;
		options.tryGPU = parameter.tryGPU;
		options.flipRight = channels == null || !channels.isFlipLeft();
		options.alignInterpolate = channels == null || channels.interpolate;
		options.alignMatrix = parameter.alignMatrix == null ? null : Transform.copy(parameter.alignMatrix);
		options.alignMatrixSource = usablePath(parameter.alignmFile) ? parameter.alignmFile : null;
		File experimental = sourceFolder == null ? null : new File(sourceFolder, "ExperimentalParameters.txt");
		options.experimentalParametersFile = experimental != null && experimental.isFile()
				? experimental.getAbsolutePath() : (usablePath(parameter.deskewmFile) ? parameter.deskewmFile : null);
		options.writeProjections = true; // the canonical viewer contract always carries all six
		options.overwriteExisting = parameter.fileExistStr != null && parameter.fileExistStr.equals("overwrite");
		return options;
	}

	static File defaultRoot(File saveFolder, File inputFolder) {
		String name = inputFolder == null ? "opm-dataset" : inputFolder.getName();
		if (name.toLowerCase().endsWith(".ome.zarr")) return new File(saveFolder, name);
		return new File(saveFolder, name + ".ome.zarr");
	}

	static OpmProvenance provenance(
			File inputFolder, File zarrRoot, Options options, double[][] deskewMatrix) {
		OpmProvenance provenance = new OpmProvenance();
		provenance.datasetName = zarrRoot.getName().replaceAll("(?i)[.]ome[.]zarr$", "");
		provenance.sourceFolder = inputFolder.getAbsolutePath();
		provenance.experimentalParametersFile = options.experimentalParametersFile;
		provenance.xyPixelSizeUm = options.xyPixelSizeUm;
		provenance.zStepSizeUm = options.zStepSizeUm;
		provenance.opmAngleDegrees = options.opmAngleDegrees;
		provenance.frameIntervalSeconds = options.frameIntervalSeconds;
		provenance.deskewMatrix = Transform.copy(deskewMatrix);
		provenance.alignMatrix = options.alignMatrix == null ? null : Transform.copy(options.alignMatrix);
		provenance.alignMatrixSource = options.alignMatrixSource;
		provenance.alignApplied = false;
		provenance.alignFlipHalf = options.flipRight
				? BatchChannelOperation.FLIP_RIGHT : BatchChannelOperation.FLIP_LEFT;
		provenance.alignInterpolate = options.alignInterpolate;
		return provenance.computeDeskewedVoxelSize();
	}

	private static boolean usablePath(String path) {
		return path != null && !path.trim().isEmpty() && new File(path).isFile();
	}
}
