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

	/** A TIFF composite which may share read-only planes with canonical Zarr channels. */
	static final class PreparedComposite implements AutoCloseable {
		final ImagePlus image;
		private final List<ImagePlus> generated;

		PreparedComposite(ImagePlus image, List<ImagePlus> generated) {
			this.image = image;
			this.generated = generated;
		}

		@Override
		public void close() {
			BatchProcessingUtils.close(image);
			for (ImagePlus volume : generated) BatchProcessingUtils.close(volume);
			generated.clear();
		}
	}

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

		AlignmentMatrixSet alignments = alignmentSet(parameter);

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
					/* Only produce what the selection asks for. Deskewing both halves for a
					 * whole-width-only selection would double the GPU work for nothing. */
					if (wantsWhole ( settings, acquisitionChannel )) {
						ImagePlus whole = deskewWhole ( raw, parameter );
						if (whole != null) {
							String wholeKey = ChannelOperationSettings.wholeSourceKey(acquisitionChannel);
							/* A tagged set may also describe one full-width file relative to another.
							 * Unlike a camera half, a whole source is never mirrored. */
							AlignmentMatrixSet.Placement placement = AlignmentMatrixSet.placement (
									alignments, wholeKey, settings.isFlipLeft(), whole.getWidth() );
							if (placement.matrix != null)
								SIFT.alignStackSIFT2 ( whole, placement.matrix, settings.interpolate );
							sources.put ( wholeKey, whole );
							opened.add ( whole );
						}
					}
					if (wantsHalf ( settings, acquisitionChannel )) {
						ImagePlus[] halves = deskewHalves ( raw, parameter, settings, alignments,
								acquisitionChannel );
						if (halves == null) continue;
						sources.put ( ChannelOperationSettings.sourceKey(acquisitionChannel, true), halves[0] );
						sources.put ( ChannelOperationSettings.sourceKey(acquisitionChannel, false), halves[1] );
						opened.add ( halves[0] );
						opened.add ( halves[1] );
					}
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

	/**
	 * Build the selected TIFF channel view from an already deskewed canonical time point.
	 * Unchanged channels share their processor arrays with the Zarr writer; only the half that
	 * requires flip/alignment allocates a transformed volume. No 3-D deskew is repeated.
	 */
	static PreparedComposite fromCanonical(
			OpmTimepointProcessor.Result canonical,
			Parameter parameter,
			ChannelOperationSettings settings,
			String title) {
		if (canonical == null || canonical.channels.isEmpty()) return null;
		Map<String, ImagePlus> sources = new LinkedHashMap<String, ImagePlus>();
		for (int i = 0; i < canonical.channels.size(); i++)
			sources.put(canonical.channelLabels.get(i), canonical.channels.get(i));

		AlignmentMatrixSet alignments = alignmentSet(parameter);
		List<ImagePlus> generated = new ArrayList<ImagePlus>();
		Map<String, ImagePlus> transformed = new LinkedHashMap<String, ImagePlus>();
		List<ImagePlus> selected = new ArrayList<ImagePlus>();
		try {
			for (String wanted : settings.channelOrder) {
				if (BatchChannelOperation.SKIP_CHANNEL.equals(wanted)) continue;
				if (ChannelOperationSettings.isWholeSource(wanted)) {
					/* The canonical time point holds the two deskewed halves, and the full
					 * width is not their concatenation - the flip has not been applied and
					 * the halves overlap in the deskewed frame. Re-deskewing the raw file
					 * here would undo the single-pass guarantee of the dual-output path. */
					IJ.log("OPM multi-channel: whole-width sources are not available when writing"
							+ " OME-Zarr and TIFF together, skipped: " + wanted);
					continue;
				}
				ImagePlus source = sources.get(wanted);
				if (source == null) {
					IJ.log("OPM multi-channel: source not present, skipped: " + wanted);
					continue;
				}
				/* One rule for a bare CSV and a tagged set, and for either flip. The bare CSV used
				 * to hand its single matrix to every source here, so a real bead matrix moved the
				 * unflipped reference half as well as the mirrored one - invisible to a test whose
				 * matrix was the identity. */
				AlignmentMatrixSet.Placement placement = AlignmentMatrixSet.placement(
						alignments, wanted, settings.isFlipLeft(), source.getWidth());
				double[][] applied = placement.matrix;
				boolean flip = placement.mirror;
				boolean mustTransform = flip || applied != null;
				if (!mustTransform) {
					selected.add(source);
					continue;
				}
				ImagePlus aligned = transformed.get(wanted);
				if (aligned == null) {
					if (parameter.tryGPU) try {
						aligned = flip
								? OpmRuntimeAlignment.transformVolumeGpu(source, applied, settings.interpolate)
								: OpmRuntimeAlignment.transformVolumeGpuWithoutFlip(source, applied, settings.interpolate);
					} catch (Throwable gpuFailure) {
						IJ.log("OPM TIFF: GPU runtime alignment failed for " + wanted
								+ "; using CPU: " + gpuFailure.getMessage());
					}
					if (aligned == null)
						aligned = flip
								? OpmRuntimeAlignment.transformVolumeCpu(source, applied, settings.interpolate)
								: OpmRuntimeAlignment.transformVolumeCpuWithoutFlip(source, applied, settings.interpolate);
					aligned.setTitle(wanted);
					transformed.put(wanted, aligned);
					generated.add(aligned);
				}
				selected.add(aligned);
			}
			if (selected.isEmpty()) return null;
			ImagePlus combined = combine(selected, title, false);
			return new PreparedComposite(combined, generated);
		} catch (RuntimeException failure) {
			for (ImagePlus volume : generated) BatchProcessingUtils.close(volume);
			throw failure;
		} catch (Error failure) {
			for (ImagePlus volume : generated) BatchProcessingUtils.close(volume);
			throw failure;
		}
	}


	/** Whether any output slot asks for one camera half of this acquisition channel. */
	private static boolean wantsHalf (
			ChannelOperationSettings settings,
			int acquisitionChannel
			) {
		for (String wanted : settings.channelOrder) {
			if (BatchChannelOperation.SKIP_CHANNEL.equals(wanted)) continue;
			if (ChannelOperationSettings.isWholeSource(wanted)) continue;
			if (ChannelOperationSettings.acquisitionChannelOf(wanted) == acquisitionChannel) return true;
		}
		return false;
	}

	/** Whether any output slot asks for the un-split full width of this acquisition channel. */
	private static boolean wantsWhole (
			ChannelOperationSettings settings,
			int acquisitionChannel
			) {
		for (String wanted : settings.channelOrder) {
			if (!ChannelOperationSettings.isWholeSource(wanted)) continue;
			if (ChannelOperationSettings.acquisitionChannelOf(wanted) == acquisitionChannel) return true;
		}
		return false;
	}

	/**			Deskew one raw file at its full camera width, without splitting or aligning
	 * <p>		There is nothing to flip or align here: the halves are what the 2D alignment
	 * 			matrix relates, so a full-width volume simply carries both, in their acquired
	 * 			positions.
	 *
	 * @return					: the deskewed full-width volume, or null when deskew failed
	 */
	private static ImagePlus deskewWhole (
			ImagePlus raw,
			Parameter parameter
			) {
		// the setup-file camera height can differ from the height of this TIFF
		parameter.impInput = raw;
		parameter.updateDeskewMatrix();
		ImagePlus whole = Deskew.deskew_image ( raw, parameter.deskewMatrix, parameter.tryGPU );
		if (whole != null) whole.setTitle ( "whole" );
		return whole;
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
		return deskewHalves(raw, parameter, settings,
				alignMatrix == null ? null : AlignmentMatrixSet.legacy(alignMatrix), 1);
	}

	/** Source-aware form used by Batch and Live when a tagged matrix set is loaded. */
	static ImagePlus[] deskewHalves (
			ImagePlus raw,
			Parameter parameter,
			ChannelOperationSettings settings,
			AlignmentMatrixSet alignments,
			int acquisitionChannel
			) {
		// Keep the grouped path consistent with Deskew.processFile: the setup-file
		// camera height can differ from the height of the TIFF actually being processed.
		parameter.impInput = raw;
		parameter.updateDeskewMatrix();
		ImagePlus[] halves = Partition.separateImageLeftRight ( raw, "left & right separately", true );
		if (halves == null || halves.length < 2) return null;

		/* The flip is the user's for every kind of matrix file; the matrices are always measured
		 * right-mirrored and AlignmentMatrixSet.placement takes them to the side chosen. */
		boolean flipLeft = settings.isFlipLeft();
		ImagePlus keep = flipLeft ? halves[1] : halves[0];		// the half left as acquired
		ImagePlus flip = flipLeft ? halves[0] : halves[1];		// the half that is mirrored

		ImagePlus keepOut = Deskew.deskew_image ( keep, parameter.deskewMatrix, parameter.tryGPU );
		double[][] flipMatrix = Transform.matrix_flipX ( flip, parameter.deskewMatrix );
		ImagePlus flipOut = Deskew.deskew_image ( flip, flipMatrix, parameter.tryGPU );

		keep.changes = false; keep.close();
		flip.changes = false; flip.close();
		if (keepOut == null || flipOut == null) return null;

		ImagePlus left = flipLeft ? flipOut : keepOut;
		ImagePlus right = flipLeft ? keepOut : flipOut;
		if (alignments != null) {
			int width = flipOut.getWidth();
			AlignmentMatrixSet.Placement leftPlacement = AlignmentMatrixSet.placement ( alignments,
					ChannelOperationSettings.sourceKey(acquisitionChannel, true), flipLeft, width );
			AlignmentMatrixSet.Placement rightPlacement = AlignmentMatrixSet.placement ( alignments,
					ChannelOperationSettings.sourceKey(acquisitionChannel, false), flipLeft, width );
			if (leftPlacement.matrix != null)
				SIFT.alignStackSIFT2 ( left, leftPlacement.matrix, settings.interpolate );
			if (rightPlacement.matrix != null)
				SIFT.alignStackSIFT2 ( right, rightPlacement.matrix, settings.interpolate );
		}
		left.setTitle ( "left" );
		right.setTitle ( "right" );
		return new ImagePlus[] { left, right };
	}

	private static AlignmentMatrixSet alignmentSet(Parameter parameter) {
		if (parameter == null) return null;
		if (parameter.alignmentMatrices != null) return parameter.alignmentMatrices;
		AlignmentMatrixSet loaded = AlignmentMatrixSet.load(parameter.alignmFile);
		if (loaded != null) return parameter.alignmentMatrices = loaded;
		if (parameter.alignMatrix != null) return AlignmentMatrixSet.legacy(parameter.alignMatrix);
		double[][] legacy = IO.loadMatrixFromFile(parameter.alignmFile);
		return legacy == null ? null : AlignmentMatrixSet.legacy(legacy);
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
		return combine(channels, title, true);
	}

	/** Combine channels, optionally retaining shared read-only plane arrays. */
	private static ImagePlus combine (
			List<ImagePlus> channels,
			String title,
			boolean duplicatePlanes
			) {
		if (channels == null || channels.isEmpty()) return null;
		ImagePlus first = channels.get(0);
		if (channels.size() == 1 && duplicatePlanes) {
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
					stack.addSlice ( channel.getTitle(), duplicatePlanes
							? channel.getStack().getProcessor(index).duplicate()
							: channel.getStack().getProcessor(index) );
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
