package de.embl.iclm;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deskews the acquisition channels of one timepoint together and combines them in the
 * order the user chose.
 *
 * <p>The microscope writes {@code _Channel0001}, {@code _Channel0002} as separate files,
 * each holding a mirrored pair of camera halves. Deskew Batch and Live Processing used to
 * take those files one at a time, so a two-channel acquisition produced two unrelated
 * results and there was no way to say which halves belonged in which output channel.
 * Channel Operation could arrange them, but only after the fact and without deskewing.
 *
 * <p>This does both at once, per timepoint: split each file into its camera halves, deskew
 * every half, flip and align the selected half onto the other, then emit a single
 * multi-channel volume containing exactly the sources
 * {@link ChannelOperationSettings#channelOrder} asks for, in that order.
 *
 * @see ChannelOperationSettings for the settings, shared with Channel Operation
 */
public class MultiChannelDeskew {

	/**			Deskew one group of acquisition-channel files into a single multi-channel volume
	 *
	 * @param group				: the files of one timepoint, one per acquisition channel
	 * @param parameter			: deskew geometry, GPU preference and the alignment matrix
	 * @param settings			: which halves become which output channels
	 * @param title				: title for the combined result
	 * <p>
	 * @return					: the combined volume, or null if nothing selected was available
	 */
	public static ImagePlus deskewGroup (
			List<File> group,
			Parameter parameter,
			ChannelOperationSettings settings,
			String title
			) {
		if (group == null || group.isEmpty() || parameter == null || settings == null) return null;

		double[][] alignMatrix = parameter.alignMatrix;
		if (alignMatrix == null && parameter.alignmFile != null && !parameter.alignmFile.trim().isEmpty())
			alignMatrix = IO.loadMatrixFromFile ( parameter.alignmFile );

		// every camera half of every file, keyed the way the dialog names it
		Map<String, ImagePlus> sources = new LinkedHashMap<String, ImagePlus>();
		List<ImagePlus> opened = new ArrayList<ImagePlus>();
		try {
			int fallbackChannel = 0;
			for (File file : group) {
				fallbackChannel++;
				int acquisitionChannel = BatchProcessingUtils.acquisitionChannel ( file );
				if (acquisitionChannel < 0) acquisitionChannel = fallbackChannel;

				ImagePlus raw = VolumeIO.open ( file.getAbsolutePath() );
				if (raw == null) {
					IJ.log ( "OPM multi-channel: could not open, skipping: " + file.getAbsolutePath() );
					continue;
				}
				try {
					ImagePlus[] halves = deskewHalves ( raw, parameter, settings, alignMatrix );
					if (halves == null) continue;
					sources.put ( ChannelOperationSettings.sourceKey(acquisitionChannel, true), halves[0] );
					sources.put ( ChannelOperationSettings.sourceKey(acquisitionChannel, false), halves[1] );
					opened.add ( halves[0] );
					opened.add ( halves[1] );
				} finally {
					raw.changes = false;
					raw.close();
				}
			}

			List<ImagePlus> selected = new ArrayList<ImagePlus>();
			for (String wanted : settings.channelOrder) {
				if (BatchChannelOperation.SKIP_CHANNEL.equals(wanted)) continue;
				ImagePlus source = sources.get ( wanted );
				if (source == null) {
					// a three-channel selection on a two-channel acquisition is not an error
					IJ.log ( "OPM multi-channel: source not present in this group, skipped: " + wanted );
					continue;
				}
				selected.add ( source );
			}
			if (selected.isEmpty()) {
				IJ.log ( "OPM multi-channel: none of the selected sources is present in " + title );
				return null;
			}
			return combine ( selected, title );
		} finally {
			for (ImagePlus imp : opened) {
				if (imp != null) { imp.changes = false; imp.close(); }
			}
		}
	}


	/**			Split one raw file into camera halves and deskew both
	 * <p>		The half being flipped is deskewed with a matrix that mirrors and shears in
	 * 			one step - the same thing {@link Deskew} does for its own "align with SIFT"
	 * 			option - and is then brought onto the other half with the 2D matrix.
	 *
	 * @return					: {left, right} deskewed, or null when the split failed
	 */
	static ImagePlus[] deskewHalves (
			ImagePlus raw,
			Parameter parameter,
			ChannelOperationSettings settings,
			double[][] alignMatrix
			) {
		ImagePlus[] halves = Partition.separateImageLeftRight ( raw, "left & right separately", true );
		if (halves == null || halves.length < 2) return null;

		boolean flipLeft = settings.isFlipLeft();
		ImagePlus keep = flipLeft ? halves[1] : halves[0];		// the untouched reference half
		ImagePlus flip = flipLeft ? halves[0] : halves[1];		// the half that is mirrored onto it

		ImagePlus keepOut = Deskew.deskew_image ( keep, parameter.deskewMatrix, parameter.tryGPU );
		double[][] flipMatrix = Transform.matrix_flipX ( flip, parameter.deskewMatrix );
		ImagePlus flipOut = Deskew.deskew_image ( flip, flipMatrix, parameter.tryGPU );

		keep.changes = false; keep.close();
		flip.changes = false; flip.close();
		if (keepOut == null || flipOut == null) return null;

		if (alignMatrix != null) {
			double[][] applied = flipLeft
					? Transform.mirrorAlignmentMatrix2D ( alignMatrix, flipOut.getWidth() )
					: alignMatrix;
			SIFT.alignStackSIFT2 ( flipOut, applied, settings.interpolate );
		}

		ImagePlus left = flipLeft ? flipOut : keepOut;
		ImagePlus right = flipLeft ? keepOut : flipOut;
		left.setTitle ( "left" );
		right.setTitle ( "right" );
		return new ImagePlus[] { left, right };
	}


	/**			Interleave channels into one hyperstack in ImageJ's XYCZT order
	 *
	 * @param channels			: the images to become channels 1..n, all the same size
	 * @param title				: title for the result
	 * <p>
	 * @return					: a multi-channel hyperstack, or the single input unchanged
	 */
	static ImagePlus combine (
			List<ImagePlus> channels,
			String title
			) {
		if (channels == null || channels.isEmpty()) return null;
		ImagePlus first = channels.get(0);
		if (channels.size() == 1) {
			first.setTitle ( title );
			return first;
		}
		int slices = Math.max ( 1, first.getNSlices() );
		int frames = Math.max ( 1, first.getNFrames() );
		for (ImagePlus channel : channels) {
			if (channel.getWidth() != first.getWidth() || channel.getHeight() != first.getHeight())
				throw new IllegalArgumentException (
						"Acquisition channels have different XY sizes: "
						+ first.getWidth() + "x" + first.getHeight() + " and "
						+ channel.getWidth() + "x" + channel.getHeight() );
			if (Math.max(1, channel.getNSlices()) != slices || Math.max(1, channel.getNFrames()) != frames)
				throw new IllegalArgumentException ( "Acquisition channels have different Z/T sizes." );
		}

		ImageStack stack = new ImageStack ( first.getWidth(), first.getHeight() );
		for (int t = 1; t <= frames; t++) {
			for (int z = 1; z <= slices; z++) {
				for (ImagePlus channel : channels) {
					int index = channel.getStackIndex ( 1, z, t );
					stack.addSlice ( channel.getTitle(), channel.getStack().getProcessor(index).duplicate() );
				}
			}
		}
		ImagePlus result = new ImagePlus ( title, stack );
		result.setDimensions ( channels.size(), slices, frames );
		result.setOpenAsHyperStack ( true );
		Calibration calibration = first.getCalibration();
		if (calibration != null) result.setCalibration ( calibration.copy() );
		Utils.autoSetLUTs ( result );
		result.changes = false;
		return result;
	}
}
