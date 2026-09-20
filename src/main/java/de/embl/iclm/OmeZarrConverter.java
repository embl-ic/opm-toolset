package de.embl.iclm;

import ij.IJ;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;

/** Converts raw OPM acquisition TIFFs into one resumable canonical OME-Zarr dataset. */
public class OmeZarrConverter {

	public static final String[] PROJECTIONS = OmeZarrSession.PROJECTIONS.toArray(new String[6]);

	/** Prepared acquisition conversion shared by standalone and dual-output Batch paths. */
	static final class Conversion implements AutoCloseable {
		final List<OpmTimepointProcessor.TimePoint> timePoints;
		final double[][] deskewMatrix;
		final OmeZarrSession session;

		Conversion(List<OpmTimepointProcessor.TimePoint> timePoints,
				double[][] deskewMatrix, OmeZarrSession session) {
			this.timePoints = timePoints;
			this.deskewMatrix = deskewMatrix;
			this.session = session;
		}

		@Override
		public void close() { session.close(); }
	}

	public static class Options {
		public double xyPixelSizeUm = 0.116;
		public double zStepSizeUm = 0.265;
		public double opmAngleDegrees = 25.0;
		public double frameIntervalSeconds = 0;
		public boolean flipRight = true;
		public boolean alignInterpolate = true;
		public boolean tryGPU = true;
		public double[][] alignMatrix = null;
		public Map<String, double[][]> alignMatrices = new LinkedHashMap<String, double[][]>();
		public String alignReference = null;
		public String alignMatrixSource = null;
		public String experimentalParametersFile = null;
		public boolean writeProjections = true;
		/** Delete an existing output before conversion; otherwise a compatible partial output resumes. */
		public boolean overwriteExisting = false;
		/** The run's channel layout, recorded so a viewer can reproduce its result; see {@link DeskewChannelView}. */
		public String channelOption = null;
		public boolean combineChannels = false;
		public List<String> channelOrder = new ArrayList<String>();
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
		Conversion conversion = openConversion(inputFolder, inputFiles, zarrRoot, options);
		try {
			IJ.log("OPM Zarr: " + conversion.timePoints.size() + " complete time point(s); output "
					+ zarrRoot.getAbsolutePath());
			int index = 0;
			for (OpmTimepointProcessor.TimePoint timePoint : conversion.timePoints) {
				if (Shutdown.stopping()) {
					// leave the dataset unmarked: an interrupted conversion must stay resumable
					IJ.log("OPM Zarr: stopped before the next time point, " + Shutdown.reason() + ".");
					return conversion.session.getProvenance();
				}
				index++;
				if (conversion.session.isCommitted(timePoint.label)) {
					IJ.log("OPM Zarr: already committed, skipping " + timePoint.label);
					continue;
				}
				IJ.showProgress(index - 1, conversion.timePoints.size());
				IJ.log("OPM Zarr: processing " + timePoint.label + " (" + index + "/"
						+ conversion.timePoints.size() + ")");
				conversion.session.append(timePoint);
				Utils.collectGarbage();
			}
			conversion.session.markComplete();
			IJ.showProgress(1.0);
			return conversion.session.getProvenance();
		} finally {
			conversion.close();
		}
	}

	/** Open a resumable writer and discover its timepoints without processing pixels yet. */
	static Conversion openConversion(
			File inputFolder, List<File> inputFiles, File zarrRoot, Options options) throws Exception {
		if (inputFolder == null || !inputFolder.isDirectory())
			throw new IllegalArgumentException("Invalid OPM input folder: " + inputFolder);
		if (zarrRoot == null) throw new IllegalArgumentException("An output OME-Zarr folder is required.");
		if (options == null) options = new Options();
		if (inputFiles == null || inputFiles.isEmpty())
			throw new IllegalArgumentException("No TIFF found in " + inputFolder);

		List<OpmTimepointProcessor.TimePoint> timePoints = OpmTimepointProcessor.completeTimePoints(
				inputFiles, options.frameIntervalSeconds);
		if (timePoints.isEmpty()) throw new IllegalArgumentException("No complete time point found.");

		if (options.overwriteExisting && zarrRoot.exists()) FileUtils.deleteDirectory(zarrRoot);

		// the camera height is in the TIFF metadata; the pixels are not needed to know it
		double[][] deskewMatrix;
		try {
			deskewMatrix = Transform.deskew(options.zStepSizeUm, options.xyPixelSizeUm,
					options.opmAngleDegrees, VolumeIO.height(timePoints.get(0).files.get(0)));
		} catch (IllegalStateException unreadable) {
			throw new IllegalArgumentException("Could not read the first volume.", unreadable);
		}

		OpmProvenance provenance = provenance(inputFolder, zarrRoot, options, deskewMatrix);
		OmeZarrSession session = new OmeZarrSession(zarrRoot, inputFolder, provenance,
				deskewMatrix, options.tryGPU, options.writeProjections);
		return new Conversion(timePoints, deskewMatrix, session);
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
		if (parameter.alignmentMatrices != null && !parameter.alignmentMatrices.isLegacy()) {
			options.alignMatrices = parameter.alignmentMatrices.matrices();
			options.alignReference = parameter.alignmentMatrices.reference();
		}
		options.alignMatrixSource = usablePath(parameter.alignmFile) ? parameter.alignmFile : null;
		File experimental = sourceFolder == null ? null : new File(sourceFolder, "ExperimentalParameters.txt");
		options.experimentalParametersFile = experimental != null && experimental.isFile()
				? experimental.getAbsolutePath() : (usablePath(parameter.deskewmFile) ? parameter.deskewmFile : null);
		options.writeProjections = true; // the canonical viewer contract always carries all six
		options.overwriteExisting = parameter.fileExistStr != null && parameter.fileExistStr.equals("overwrite");
		options.channelOption = parameter.channelStr;
		options.combineChannels = channels != null && channels.combineAcquisitionChannels;
		if (channels != null) options.channelOrder.addAll(DeskewChannelView.selectedSources(channels.channelOrder));
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
		provenance.alignMatrices.clear();
		if (options.alignMatrices != null) for (Map.Entry<String, double[][]> entry : options.alignMatrices.entrySet())
			provenance.alignMatrices.put(entry.getKey(), AlignmentMatrixSet.copy(entry.getValue()));
		provenance.alignReference = options.alignReference;
		if (!provenance.alignMatrices.isEmpty())
			provenance.alignMatrixConvention = AlignmentMatrixSet.CONVENTION;
		provenance.alignMatrixSource = options.alignMatrixSource;
		if (provenance.alignMatrix != null && usablePath(options.alignMatrixSource)) {
			File alignmentFile = new File(options.alignMatrixSource);
			provenance.alignMatrixModifiedUtc = OpmProvenance.utcTimestamp(alignmentFile.lastModified());
		}
		provenance.alignApplied = false;
		/* The run's own choice, for a tagged set as for a bare CSV. It is the side a viewer opens
		 * the store flipped on, never a property of the stored pixels or of the matrices, which
		 * are measured right-mirrored whatever is chosen here. */
		provenance.alignFlipHalf = options.flipRight
				? BatchChannelOperation.FLIP_RIGHT : BatchChannelOperation.FLIP_LEFT;
		provenance.alignInterpolate = options.alignInterpolate;
		provenance.deskewChannelOption = options.channelOption;
		provenance.deskewCombineChannels = options.combineChannels;
		if (options.channelOrder != null) provenance.deskewChannelOrder.addAll(options.channelOrder);
		return provenance.computeDeskewedVoxelSize();
	}

	private static boolean usablePath(String path) {
		return path != null && !path.trim().isEmpty() && new File(path).isFile();
	}
}
