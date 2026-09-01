package de.embl.iclm;

import ij.IJ;
import ij.ImagePlus;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a folder of raw OPM acquisition files into one OME-Zarr dataset.
 *
 * <p>Each time point contributes one {@code _ChannelNNNN} file per acquisition channel, and
 * each of those splits into a left and a right camera half - so a two-channel acquisition
 * becomes four stored channels. Every half is deskewed; none is aligned. The rigid transform
 * measured on a bead acquisition is recorded in the provenance and applied at view time.
 *
 * <p>Projections are written alongside the volume so a viewer can play a time-lapse of any
 * of them without opening a single volume.
 */
public class OpmZarrConverter {

	/** Which projections to compute and store. */
	public static final String[] PROJECTIONS = { "maxX", "maxY", "maxZ", "meanX", "meanY", "meanZ" };

	public static class Options {
		public double xyPixelSizeUm = 0.116;
		public double zStepSizeUm = 0.265;
		public double opmAngleDegrees = 25.0;
		public double frameIntervalSeconds = 0;
		public boolean flipRight = true;			// which half the alignment maps onto the other
		public boolean tryGPU = true;
		public double[][] alignMatrix = null;		// recorded, not applied
		public String alignMatrixSource = null;
		public String experimentalParametersFile = null;
		public boolean writeProjections = true;
	}

	/**			Convert one acquisition folder
	 *
	 * @param inputFolder		: folder holding the raw _ChannelNNNN files
	 * @param zarrRoot			: the .ome.zarr folder to create
	 * @param options			: geometry, the alignment matrix to record, and what to write
	 * <p>
	 * @return					: the provenance that was stored
	 */
	public static OpmProvenance convert (
			File inputFolder,
			File zarrRoot,
			Options options
			) throws Exception {

		List<File> files = new ArrayList<File>();
		Live2.collectTiffs ( inputFolder, false, "", files, 0 );
		if (files.isEmpty()) throw new IllegalArgumentException ( "No TIFF found in " + inputFolder );

		// group by time point, and inside each by acquisition channel
		Map<String, List<File>> byTime = new LinkedHashMap<String, List<File>>();
		for (File file : files) {
			String key = timeKey ( file );
			List<File> group = byTime.get ( key );
			if (group == null) { group = new ArrayList<File>(); byTime.put ( key, group ); }
			group.add ( file );
		}
		for (List<File> group : byTime.values()) ChannelOperationSettings.sortByAcquisitionChannel ( group );

		// only complete time points go in, so the channel axis means the same thing throughout
		int channelsPerTime = 0;
		for (List<File> group : byTime.values()) channelsPerTime = Math.max ( channelsPerTime, group.size() );
		List<String> timeKeys = new ArrayList<String>();
		for (Map.Entry<String, List<File>> e : byTime.entrySet()) {
			if (e.getValue().size() == channelsPerTime) timeKeys.add ( e.getKey() );
			else IJ.log ( "OPM zarr: skipping incomplete time point " + e.getKey()
					+ " (" + e.getValue().size() + " of " + channelsPerTime + " acquisition channels)" );
		}
		if (timeKeys.isEmpty()) throw new IllegalArgumentException ( "No complete time point found." );

		int storedChannels = channelsPerTime * 2;			// a left and a right half each
		IJ.log ( "OPM zarr: " + timeKeys.size() + " complete time point(s), "
				+ channelsPerTime + " acquisition channel(s) -> " + storedChannels + " stored channel(s)" );

		// deskew geometry, fixed for the whole dataset
		ImagePlus firstRaw = VolumeIO.open ( byTime.get(timeKeys.get(0)).get(0).getAbsolutePath() );
		if (firstRaw == null) throw new IllegalArgumentException ( "Could not read the first volume." );
		int rawHeight = firstRaw.getHeight();
		int halfWidth = (firstRaw.getWidth() + 1) / 2;
		int rawDepth = firstRaw.getStackSize();
		firstRaw.close();

		double[][] deskewMatrix = Transform.deskew (
				options.zStepSizeUm, options.xyPixelSizeUm, options.opmAngleDegrees, rawHeight );
		long[] outDims = Transform.getTransformedDim (
				new long[] { halfWidth, rawHeight, rawDepth }, Transform.copy(deskewMatrix), false );
		IJ.log ( "OPM zarr: each half deskews to " + Arrays.toString(outDims) );

		OpmProvenance provenance = new OpmProvenance();
		provenance.datasetName = zarrRoot.getName().replaceAll ( "[.]ome[.]zarr$", "" );
		provenance.sourceFolder = inputFolder.getAbsolutePath();
		provenance.experimentalParametersFile = options.experimentalParametersFile;
		provenance.xyPixelSizeUm = options.xyPixelSizeUm;
		provenance.zStepSizeUm = options.zStepSizeUm;
		provenance.opmAngleDegrees = options.opmAngleDegrees;
		provenance.frameIntervalSeconds = options.frameIntervalSeconds;
		provenance.deskewMatrix = Transform.copy ( deskewMatrix );
		provenance.alignMatrix = options.alignMatrix;
		provenance.alignMatrixSource = options.alignMatrixSource;
		provenance.alignApplied = false;
		provenance.alignFlipHalf = options.flipRight
				? BatchChannelOperation.FLIP_RIGHT : BatchChannelOperation.FLIP_LEFT;
		provenance.computeDeskewedVoxelSize();

		OpmZarrWriter writer = new OpmZarrWriter ( zarrRoot );
		boolean usedGpu = false;
		try {
			writer.createVolume ( (int) outDims[0], (int) outDims[1], (int) outDims[2],
					storedChannels, timeKeys.size() );

			List<String> labels = new ArrayList<String>();
			for (int t = 0; t < timeKeys.size(); t++) {
				List<File> group = byTime.get ( timeKeys.get(t) );
				int channel = 0;
				for (File file : group) {
					int acquisition = BatchProcessingUtils.acquisitionChannel ( file );
					if (acquisition < 0) acquisition = channel / 2 + 1;
					if (t == 0) provenance.sourceFiles.add ( file.getName() );

					ImagePlus raw = VolumeIO.open ( file.getAbsolutePath() );
					if (raw == null) throw new IllegalStateException ( "Could not read " + file );
					ImagePlus[] halves = Partition.separateImageLeftRight ( raw, "left & right separately", true );
					raw.changes = false; raw.close();

					for (int side = 0; side < 2; side++) {
						boolean left = (side == 0);
						ImagePlus deskewed = null;
						if (options.tryGPU) {
							deskewed = GPU.transform ( halves[side], Transform.copy(deskewMatrix) );
							if (deskewed != null) usedGpu = true;
						}
						if (deskewed == null) deskewed = CPU.transform ( halves[side], Transform.copy(deskewMatrix) );
						halves[side].changes = false; halves[side].close();

						writer.writeVolumeChannel ( deskewed, channel, t );
						if (options.writeProjections) {
							for (String name : PROJECTIONS) {
								ImagePlus projection = project ( deskewed, name, options.tryGPU );
								if (projection == null) continue;
								if (!writer.hasDataset("projections/" + name))
									writer.createProjection ( name, projection.getWidth(),
											projection.getHeight(), storedChannels, timeKeys.size() );
								writer.writeProjection ( projection, name, channel, t );
								projection.changes = false; projection.close();
							}
						}
						if (t == 0) labels.add ( ChannelOperationSettings.sourceKey(acquisition, left) );
						IJ.log ( String.format ( "OPM zarr: t=%d c=%d  %s  %dx%dx%d",
								t, channel, ChannelOperationSettings.sourceKey(acquisition, left),
								deskewed.getWidth(), deskewed.getHeight(), deskewed.getStackSize() ) );
						deskewed.changes = false; deskewed.close();
						channel++;
						Utils.collectGarbage();
					}
				}
			}
			provenance.channelLabels = labels;
			provenance.stampEnvironment ( usedGpu );
			writer.writeMetadata ( provenance,
					options.writeProjections ? Arrays.asList(PROJECTIONS) : new ArrayList<String>() );
		} finally {
			writer.close();
		}
		return provenance;
	}

	/** A projection by name, GPU first. */
	private static ImagePlus project (
			ImagePlus imp,
			String name,
			boolean tryGPU
			) {
		String axis = name.substring ( name.length() - 1 );			// X, Y or Z
		String type = name.startsWith("mean") ? "avg" : "max";
		return Projection.projection ( imp, axis, type, tryGPU );
	}

	/** Everything before the _ChannelNNNN token identifies a time point. */
	private static String timeKey (
			File file
			) {
		return BatchProcessingUtils.channelGroupKey ( file );
	}
}
