package de.embl.iclm;

import java.awt.Rectangle;

import ij.CompositeImage;
import ij.IJ;
import ij.ImageListener;
import ij.ImagePlus;
import ij.ImageStack;
import ij.VirtualStack;
import ij.gui.ImageWindow;
import ij.gui.StackWindow;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import ij.process.LUT;
import ij.process.ShortProcessor;

import java.lang.reflect.Field;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Fiji-native virtual and materialised views of a canonical OPM OME-Zarr dataset. */
public final class OmeZarrView {

	public static final String SELECT_ALL = "all stored channels";
	/** Defer to the order chosen in the viewer's own channel setup dialog. */
	public static final String SELECT_CONFIGURED = "configured channel order";
	public static final String SELECT_LEFT = "all left sides";
	public static final String SELECT_RIGHT = "all right sides";

	/**
	 * How the selected stored channels are composed into the view.
	 * <p>
	 * {@code STORED_CHANNELS} is the store's own layout: every camera half its own channel,
	 * unflipped. That is not the result of any deskew run - a whole-image run writes the full
	 * width, a fold writes a mirrored pair - so its label says halves, and the viewer opens a
	 * dataset with {@link DeskewChannelView}'s choice instead of this one.
	 */
	public enum Operation {
		STORED_CHANNELS("stored halves as separate channels (no transform)"),
		SIDE_BY_SIDE("whole: left + right side by side"),
		FLIP_ONLY("flip only (side from metadata)"),
		FLIP_ALIGN_RIGHT("flip + align right onto left"),
		FLIP_ALIGN_LEFT("flip + align left onto right");

		private final String label;
		Operation(String label) { this.label = label; }
		@Override public String toString() { return label; }
	}

	/**
	 * A sub-volume of the view, in the coordinates of the image the view produces.
	 *
	 * <p>Deliberately expressed in <em>output</em> coordinates rather than stored ones: a
	 * region is normally drawn as an ROI on a view that is already flipped, aligned or shown
	 * side by side, and the pixel the user pointed at has to be the pixel that comes out. The
	 * crop is therefore the last step of rendering a plane, not the first.
	 *
	 * <p>Bounds apply to volume views only. A projection has already collapsed one of the three
	 * axes, so an XYZ box does not describe a region of it; {@code openVirtualProjectionMovie}
	 * and its materialised twin ignore them.
	 */
	public static final class Bounds {
		public int x;
		public int y;
		public int width;
		public int height;
		public int zStart;
		/** Exclusive, like every other end index here. */
		public int zEnd;

		public Bounds() { }

		public Bounds(int x, int y, int width, int height, int zStart, int zEnd) {
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			this.zStart = zStart;
			this.zEnd = zEnd;
		}

		public int depth() { return Math.max(0, zEnd - zStart); }

		public Bounds copy() { return new Bounds(x, y, width, height, zStart, zEnd); }

		/** The whole of a view this size. */
		public static Bounds full(int width, int height, int depth) {
			return new Bounds(0, 0, width, height, 0, depth);
		}

		/** Whether this box already covers everything, in which case cropping is skipped. */
		public boolean covers(int fullWidth, int fullHeight, int fullDepth) {
			return x <= 0 && y <= 0 && zStart <= 0
					&& width >= fullWidth && height >= fullHeight && zEnd >= fullDepth;
		}

		/**			Bring a requested box inside a view of this size
		 * <p>		Silently clamping rather than refusing: the box normally comes from an ROI
		 * 			or from typed numbers, both of which can reach past an edge, and a region
		 * 			one pixel too wide should still export.
		 *
		 * @return					: a new box guaranteed to be non-empty and inside the view
		 */
		public Bounds clampedTo(int fullWidth, int fullHeight, int fullDepth) {
			Bounds clamped = new Bounds();
			clamped.x = Math.max(0, Math.min(x, Math.max(0, fullWidth - 1)));
			clamped.y = Math.max(0, Math.min(y, Math.max(0, fullHeight - 1)));
			int requestedWidth = width <= 0 ? fullWidth : width;
			int requestedHeight = height <= 0 ? fullHeight : height;
			clamped.width = Math.max(1, Math.min(requestedWidth, fullWidth - clamped.x));
			clamped.height = Math.max(1, Math.min(requestedHeight, fullHeight - clamped.y));
			clamped.zStart = Math.max(0, Math.min(zStart, Math.max(0, fullDepth - 1)));
			int requestedEnd = zEnd <= 0 ? fullDepth : zEnd;
			clamped.zEnd = Math.max(clamped.zStart + 1, Math.min(requestedEnd, fullDepth));
			return clamped;
		}

		@Override
		public String toString() {
			return "x=" + x + ", y=" + y + ", w=" + width + ", h=" + height
					+ ", z=" + zStart + "-" + (zEnd - 1);
		}
	}

	/** Immutable-enough request object; callers may fill fields directly before opening. */
	public static final class Options {
		public Operation operation = Operation.STORED_CHANNELS;
		/** Empty means all channels. Entries use {@link ChannelOperationSettings#sourceKey}. */
		public final List<String> requestedChannels = new ArrayList<String>();
		/** Zero-based first channel after selection/runtime composition. */
		public int firstOutputChannel = 0;
		/** Number of composed output channels, or -1 for every channel from the first. */
		public int outputChannelCount = -1;
		public boolean interpolate = true;
		public boolean tryGpu = true;
		/** Optional flip-only override; null uses {@code opm.alignFlipHalf}. */
		public String flipHalf;
		/** Sub-volume to read, in output coordinates; null reads everything. */
		public Bounds bounds;

		public Options copy() {
			Options copy = new Options();
			copy.operation = operation;
			copy.requestedChannels.addAll(requestedChannels);
			copy.firstOutputChannel = firstOutputChannel;
			copy.outputChannelCount = outputChannelCount;
			copy.interpolate = interpolate;
			copy.tryGpu = tryGpu;
			copy.flipHalf = flipHalf;
			copy.bounds = bounds == null ? null : bounds.copy();
			return copy;
		}
	}

	private OmeZarrView() {}

	/** Channel/acquisition/side choices suitable for the viewer control. */
	public static List<String> selectionOptions(OmeZarrDataset dataset) {
		List<String> result = new ArrayList<String>();
		result.add(SELECT_ALL);
		result.add(SELECT_LEFT);
		result.add(SELECT_RIGHT);
		Set<String> acquisitions = new LinkedHashSet<String>();
		for (String label : dataset.getChannelLabels()) {
			String base = acquisitionBase(label);
			if (base != null) acquisitions.add(base);
		}
		result.addAll(acquisitions);
		result.addAll(dataset.getChannelLabels());
		return result;
	}

	/** Translate one UI selection to the shared ChannelOperationSettings source-key dialect. */
	public static List<String> channelsForSelection(OmeZarrDataset dataset, String selection,
			ChannelOperationSettings configuredOrder) {
		if (selection == null || SELECT_ALL.equals(selection)) return dataset.getChannelLabels();
		if (SELECT_CONFIGURED.equals(selection) && configuredOrder != null) {
			List<String> labels = dataset.getChannelLabels();
			List<String> configured = new ArrayList<String>();
			for (String source : configuredOrder.channelOrder) {
				if (BatchChannelOperation.SKIP_CHANNEL.equals(source)) continue;
				/* A whole-width source is what a deskew run combining full camera widths
				 * selects. The store holds that width as its two halves, so the source means
				 * both of them - which a side-by-side view then puts back together. */
				if (ChannelOperationSettings.isWholeSource(source)) {
					String base = source.substring(0, source.length() - "-whole".length());
					for (String half : new String[] { base + "-left", base + "-right" })
						if (labels.contains(half) && !configured.contains(half)) configured.add(half);
				} else if (labels.contains(source) && !configured.contains(source)) {
					configured.add(source);
				}
			}
			/* A setup left over from a different acquisition names channels this dataset does
			 * not store. Showing everything beats refusing to open the dataset at all. */
			return configured.isEmpty() ? dataset.getChannelLabels() : configured;
		}
		List<String> result = new ArrayList<String>();
		for (String label : dataset.getChannelLabels()) {
			if (SELECT_LEFT.equals(selection) && label.endsWith("-left")) result.add(label);
			else if (SELECT_RIGHT.equals(selection) && label.endsWith("-right")) result.add(label);
			else if (selection.equals(label)) result.add(label);
			else if (selection.equals(acquisitionBase(label))) result.add(label);
		}
		return result;
	}

	/**
	 * The channel sources a setup dialog should offer for this dataset: its halves, then skip.
	 * <p>
	 * Derived from the dataset rather than from a fixed list, so a two-file acquisition is not
	 * asked about channels it does not have and a four-file one can reach all eight halves.
	 */
	public static String[] channelSetupSources(OmeZarrDataset dataset) {
		List<String> labels = dataset.getChannelLabels();
		String[] sources = new String[labels.size() + 1];
		for (int i = 0; i < labels.size(); i++) sources[i] = labels.get(i);
		sources[labels.size()] = BatchChannelOperation.SKIP_CHANNEL;
		return sources;
	}

	/** How many output slots a setup dialog should draw: one per stored channel, up to the cap. */
	public static int channelSetupSlots(OmeZarrDataset dataset) {
		return Math.min(dataset.getChannelLabels().size(), BatchChannelOperation.MAX_OUTPUT_CHANNELS);
	}

	/**
	 * Per-slot starting values for a setup dialog.
	 * <p>
	 * A stored choice is kept whenever this dataset can honour it, a deliberate skip included.
	 * Anything else - a channel from a different acquisition, or a slot the stored order never
	 * had - falls back to this dataset's own channel at that position, so a setup carried over
	 * from another acquisition repairs itself instead of offering nothing.
	 */
	public static String[] channelSetupDefaults(
			OmeZarrDataset dataset, ChannelOperationSettings configured) {
		List<String> labels = dataset.getChannelLabels();
		String[] sources = channelSetupSources(dataset);
		String[] defaults = new String[channelSetupSlots(dataset)];
		for (int i = 0; i < defaults.length; i++) {
			String stored = configured != null && i < configured.channelOrder.length
					? configured.channelOrder[i] : null;
			defaults[i] = labels.get(i);
			if (stored != null)
				for (String source : sources) if (source.equals(stored)) defaults[i] = stored;
		}
		return defaults;
	}

	/** Which of the dataset's channels no slot asks for, or null when all are spoken for. */
	public static List<String> channelsNotSelected(OmeZarrDataset dataset, String[] slots) {
		List<String> missing = new ArrayList<String>();
		for (String label : dataset.getChannelLabels()) {
			boolean used = false;
			if (slots != null) for (String slot : slots) if (label.equals(slot)) used = true;
			if (!used) missing.add(label);
		}
		return missing;
	}

	/** Open one T position ({@code timepoint >= 0}) or all T positions ({@code -1}) lazily. */
	public static ImagePlus openVirtualVolume(OmeZarrDataset dataset, Options options, int timepoint) {
		validateVolume(dataset);
		int firstT = timepoint < 0 ? 0 : checkedTimepoint(dataset, timepoint);
		int frames = timepoint < 0 ? dataset.getTimepointCount() : 1;
		ViewRenderer renderer = new ViewRenderer(dataset, options, "s0", false);
		try {
			int depth = renderer.outputDepth();
			OmeZarrVirtualStack stack = new OmeZarrVirtualStack(
					renderer, depth, firstT, frames, timepoint < 0);
			ImagePlus base = new ImagePlus(title(dataset, renderer.describeExtent(
					timepoint < 0 ? "virtual 5D" : "virtual volume")), stack);
			base.setDimensions(renderer.outputCount(), depth, frames);
			base.setOpenAsHyperStack(renderer.outputCount() > 1 || depth > 1 || frames > 1);
			ImagePlus result = asHyperStack(asComposite(base, renderer.outputCount()));
			calibrateVolume(result, dataset);
			attachInfo(result, dataset, renderer);
			result.changes = false;
			closeRendererWhenImageCloses(result, renderer);
			return result;
		} catch (RuntimeException error) {
			renderer.close();
			throw error;
		}
	}

	/** Open a projection movie lazily, reading and transforming one C/T plane on demand. */
	public static ImagePlus openVirtualProjectionMovie(
			OmeZarrDataset dataset, String projection, Options options) {
		validateProjection(dataset, projection, options);
		long[] dimensions = dataset.getProjectionDimensions(projection);
		ViewRenderer renderer = new ViewRenderer(dataset, options, "projections/" + projection, true);
		try {
			int frames = projectionFrames(dataset, dimensions);
			OmeZarrVirtualProjectionStack stack = new OmeZarrVirtualProjectionStack(renderer, frames);
			ImagePlus base = new ImagePlus(projectionTitle(dataset, projection, renderer, true), stack);
			base.setDimensions(renderer.outputCount(), 1, frames);
			base.setOpenAsHyperStack(renderer.outputCount() > 1 || frames > 1);
			ImagePlus result = finishProjectionMovie(base, dataset, projection, renderer);
			closeRendererWhenImageCloses(result, renderer);
			return result;
		} catch (RuntimeException error) {
			renderer.close();
			throw error;
		}
	}

	/** Materialise every C/T projection plane in RAM before returning the movie. */
	public static ImagePlus openMaterializedProjectionMovie(
			OmeZarrDataset dataset, String projection, Options options) {
		validateProjection(dataset, projection, options);
		long[] dimensions = dataset.getProjectionDimensions(projection);
		ViewRenderer renderer = new ViewRenderer(dataset, options, "projections/" + projection, true);
		try {
			int frames = projectionFrames(dataset, dimensions);
			ImageStack stack = new ImageStack(renderer.outputWidth(), renderer.outputHeight());
			for (int t = 0; t < frames; t++) {
				for (int c = 0; c < renderer.outputCount(); c++) {
					stack.addSlice(renderer.outputLabel(c) + " / " + timeLabel(dataset, t),
							renderer.renderProjection(c, t));
				}
			}
			ImagePlus base = new ImagePlus(projectionTitle(dataset, projection, renderer, false), stack);
			base.setDimensions(renderer.outputCount(), 1, frames);
			base.setOpenAsHyperStack(renderer.outputCount() > 1 || frames > 1);
			return finishProjectionMovie(base, dataset, projection, renderer);
		} finally {
			renderer.close();
		}
	}

	/** Backwards-compatible name: historically projection movies were always materialised. */
	public static ImagePlus openProjectionMovie(OmeZarrDataset dataset, String projection, Options options) {
		return openMaterializedProjectionMovie(dataset, projection, options);
	}

	private static void validateProjection(
			OmeZarrDataset dataset, String projection, Options options) {
		if (!dataset.hasProjection(projection))
			throw new IllegalArgumentException("Projection is not present: " + projection);
		refuseImpossibleProjectionView(projection, options.operation);
		long[] dimensions = dataset.getProjectionDimensions(projection);
		if (dimensions.length != 4) throw new IllegalArgumentException("Invalid projection dimensions: " + projection);
	}

	private static int projectionFrames(OmeZarrDataset dataset, long[] dimensions) {
		return (int) Math.min(dataset.getTimepointCount(), dimensions[3]);
	}

	private static String projectionTitle(
			OmeZarrDataset dataset, String projection, ViewRenderer renderer, boolean virtual) {
		double spread = renderer.approximateSpreadPixels();
		String name = projection + (virtual ? " virtual movie" : " materialised movie");
		if (spread >= 0) name += " (rough overlay)";
		return title(dataset, name);
	}

	private static ImagePlus finishProjectionMovie(
			ImagePlus base, OmeZarrDataset dataset, String projection, ViewRenderer renderer) {
		double spread = renderer.approximateSpreadPixels();
		ImagePlus result = asHyperStack(asComposite(base, renderer.outputCount()));
		calibrateProjection(result, dataset, projection);
		attachInfo(result, dataset, renderer);
		if (spread >= 0) {
			String warning = approximateProjectionWarning(
					dataset, projection, renderer.options.operation, spread);
			warn(warning);
			result.setProperty("Info", result.getProperty("Info") + "\n\n" + warning);
			result.setProperty("opm.approximateOverlay", "true");
		}
		result.changes = false;
		return result;
	}

	/** Materialise one T ({@code >=0}) or all T ({@code -1}); GPU transforms one Z volume per call. */
	public static ImagePlus openMaterializedVolume(OmeZarrDataset dataset, Options options, int timepoint) {
		validateVolume(dataset);
		int firstT = timepoint < 0 ? 0 : checkedTimepoint(dataset, timepoint);
		int frames = timepoint < 0 ? dataset.getTimepointCount() : 1;
		return openMaterializedVolume(dataset, options, firstT, frames);
	}

	/**			Materialise a chosen stretch of the time-lapse
	 * <p>		A whole 5-D acquisition is the one open that can ask for more memory than the
	 * 			machine has, and it is rarely all of it that is wanted from it. The range is
	 * 			clamped into what is committed rather than refused, for the same reason a
	 * 			region is: the numbers come from a dialog and can reach past the end.
	 *
	 * @param firstTimepoint	: zero based
	 * @param frames			: how many to take from there, clamped to what remains
	 */
	public static ImagePlus openMaterializedVolume(
			OmeZarrDataset dataset, Options options, int firstTimepoint, int frames) {
		validateVolume(dataset);
		int firstT = checkedTimepoint(dataset,
				Math.max(0, Math.min(firstTimepoint, dataset.getTimepointCount() - 1)));
		int count = Math.max(1, Math.min(frames, dataset.getTimepointCount() - firstT));
		ViewRenderer renderer = new ViewRenderer(dataset, options, "s0", false);
		try {
			int depth = renderer.outputDepth();
			ImageStack resultStack = new ImageStack(renderer.outputWidth(), renderer.outputHeight());
			for (int localT = 0; localT < count; localT++) {
				int sourceT = firstT + localT;
				List<ImagePlus> channels = new ArrayList<ImagePlus>();
				try {
					for (int c = 0; c < renderer.outputCount(); c++)
						channels.add(renderer.materializeChannel(c, sourceT));
					for (int z = 1; z <= depth; z++)
						for (ImagePlus channel : channels)
							resultStack.addSlice(channel.getTitle(), channel.getStack().getProcessor(z));
				} finally {
					for (ImagePlus channel : channels) {
						channel.changes = false;
						channel.close();
					}
				}
			}
			ImagePlus base = new ImagePlus(title(dataset, renderer.describeExtent(
					describeTimeRange("materialised", dataset, firstT, count))), resultStack);
			base.setDimensions(renderer.outputCount(), depth, count);
			base.setOpenAsHyperStack(renderer.outputCount() > 1 || depth > 1 || count > 1);
			ImagePlus result = asHyperStack(asComposite(base, renderer.outputCount()));
			calibrateVolume(result, dataset);
			attachInfo(result, dataset, renderer);
			result.changes = false;
			return result;
		} finally {
			renderer.close();
		}
	}

	/** "materialised 5D", or the stretch of it this view actually holds. */
	private static String describeTimeRange(
			String base, OmeZarrDataset dataset, int firstT, int count) {
		if (count <= 1) return base + " volume";
		if (firstT == 0 && count >= dataset.getTimepointCount()) return base + " 5D";
		return base + " 5D T " + (firstT + 1) + "-" + (firstT + count);
	}

	/**
	 * Say plainly that an aligned orthogonal projection is an overlay, not a measurement.
	 * <p>
	 * The reduction happened before the alignment did, so this is neither the true maximum nor
	 * the true mean of the aligned volume: it is the stored reduction of the unaligned voxels,
	 * moved into place. Getting the true one means reading every plane of the volume back and
	 * reducing again, which for a full acquisition is tens of minutes rather than seconds.
	 */
	static String approximateProjectionWarning(
			OmeZarrDataset dataset, String projection, Operation operation, double spreadPixels) {
		boolean alongY = projection.toUpperCase(Locale.US).endsWith("Y");
		String collapsed = alongY ? "Y" : "X";
		String displaced = alongY ? "X" : "Y";
		double[] voxel = dataset.voxelSizeUm();
		double micrometres = spreadPixels * (voxel != null && voxel.length > 1
				? (alongY ? voxel[0] : voxel[1]) : 0);
		String kind = projection.toLowerCase(Locale.US).startsWith("mean") ? "mean" : "maximum";
		return "OPM viewer: " + projection + " with \"" + operation + "\" is a ROUGH OVERLAY"
				+ " for visual inspection only, not a true " + kind + " projection."
				+ "\n  " + projection + " already summed along " + collapsed
				+ ", so the alignment can only be applied as the transform taken at the middle of "
				+ collapsed + ". Features are displaced along " + displaced + " by up to "
				+ IJ.d2s(spreadPixels, 2) + " px"
				+ (micrometres > 0 ? " (" + IJ.d2s(micrometres, 3) + " um)" : "")
				+ " depending on where they sat along " + collapsed + ","
				+ "\n  and the " + kind + " itself is over the unaligned voxels."
				+ "\n  Judge channel overlay with it; do not measure from it."
				+ " maxZ and meanZ carry the same alignment exactly.";
	}

	/**
	 * Both destinations: the Fiji Log window a user watches, and the console a script captures.
	 * <p>
	 * With no ImageJ instance {@link IJ#log} already writes to standard output, so adding the
	 * console copy there would print every warning twice.
	 */
	private static void warn(String message) {
		IJ.log(message);
		if (IJ.getInstance() != null) System.err.println(message);
	}

	/**			The width, height and depth a view with these options would produce
	 * <p>		Needed before anything is opened: a region has to be expressed in the
	 * 			coordinates of the view it will be cut out of, and a side-by-side view is twice
	 * 			as wide as the array it reads from.
	 *
	 * @return					: {width, height, depth} of the un-cropped view
	 */
	public static int[] viewExtent(OmeZarrDataset dataset, Options options) {
		validateVolume(dataset);
		Options whole = options == null ? new Options() : options.copy();
		whole.bounds = null;
		ViewRenderer renderer = new ViewRenderer(dataset, whole, "s0", false);
		try {
			return new int[] { renderer.outputWidth(), renderer.outputHeight(), renderer.outputDepth() };
		} finally {
			renderer.close();
		}
	}

	/** Labels of the channels produced after selection, composition and output sub-ranging. */
	public static List<String> outputChannelLabels(OmeZarrDataset dataset, Options options) {
		validateVolume(dataset);
		ViewRenderer renderer = new ViewRenderer(dataset, options, "s0", false);
		try {
			return renderer.outputLabels();
		} finally {
			renderer.close();
		}
	}

	/** Estimated output allocation in bytes, before ImageJ object overhead. */
	public static long estimateMaterializedBytes(OmeZarrDataset dataset, Options options, int timepoint) {
		ViewRenderer renderer = new ViewRenderer(dataset, options, "s0", false);
		try {
			long frames = timepoint < 0 ? dataset.getTimepointCount() : 1;
			return saturatedMultiply(2L, renderer.outputWidth(), renderer.outputHeight(),
					renderer.outputDepth(), renderer.outputCount(), frames);
		} finally {
			renderer.close();
		}
	}

	/**			Write a region of the volume straight to a Deflate TIFF, without opening it
	 * <p>		This is the export that makes the bounded view worth having. The planes are
	 * 			rendered one at a time and handed to {@link FastTiffWriter}, which pulls them in
	 * 			batches, so exporting a region of a 5-D dataset costs the region and not the
	 * 			dataset. The result is an ordinary multi-page TIFF with the view's channel and
	 * 			time structure declared, so it reopens in Fiji as the same hyperstack.
	 *
	 * @param dataset			: the dataset to read
	 * @param options			: channel selection, runtime view and {@link Options#bounds}
	 * @param firstTimepoint	: first committed T to export, 0 based
	 * @param frames			: how many T positions to export; at least one
	 * @param file				: destination TIFF
	 * @param level				: Deflate level, 0 to 9
	 * <p>
	 * @return					: a one-line description of what was written
	 */
	public static String exportRegionToTiff(OmeZarrDataset dataset, Options options,
			int firstTimepoint, int frames, java.io.File file, int level) throws java.io.IOException {
		validateVolume(dataset);
		int first = checkedTimepoint(dataset, firstTimepoint);
		final int count = Math.max(1, Math.min(frames, dataset.getTimepointCount() - first));
		final ViewRenderer renderer = new ViewRenderer(dataset, options, "s0", false);
		try {
			final int channels = renderer.outputCount();
			final int depth = renderer.outputDepth();
			final int firstT = first;
			FastTiffWriter.Layout layout = new FastTiffWriter.Layout();
			layout.width = renderer.outputWidth();
			layout.height = renderer.outputHeight();
			layout.channels = channels;
			layout.slices = depth;
			layout.frames = count;
			double[] voxel = dataset.voxelSizeUm();
			layout.unit = "micron";
			layout.pixelWidth = voxel != null && voxel.length > 0 ? positiveOrOne(voxel[0]) : 1;
			layout.pixelHeight = voxel != null && voxel.length > 1 ? positiveOrOne(voxel[1]) : 1;
			layout.pixelDepth = voxel != null && voxel.length > 2 ? positiveOrOne(voxel[2]) : 1;
			layout.frameInterval = dataset.frameIntervalSeconds();

			FastTiffWriter.write(new FastTiffWriter.PlaneSource() {
				@Override public ImageProcessor plane(int index) {
					// XYCZT: channel fastest, then Z, then T - the order ImageJ reads back
					int channel = index % channels;
					int z = (index / channels) % depth;
					int timepoint = firstT + index / (channels * depth);
					return renderer.renderVolume(channel, z, timepoint);
				}
			}, layout, file, level);

			return "exported " + layout.width + "x" + layout.height + "x" + depth
					+ ", " + channels + " channel(s), " + count + " time point(s) to "
					+ file.getAbsolutePath() + " (" + IJ.d2s(file.length() / 1048576.0, 1) + " MB)";
		} finally {
			renderer.close();
		}
	}

	/**
	 * The runtime view of a volume as a plane source, for writing a region straight to disk.
	 *
	 * <p>What {@link RegionExport} needs and no more: one plane of the view at a time, flipped,
	 * aligned, put side by side and cropped to the region exactly as a window of it would be. The
	 * caller closes it, and with it the store's reader.
	 */
	static RegionPlanes regionPlanes(OmeZarrDataset dataset, Options options) {
		return regionPlanes(dataset, options, null);
	}

	/**			The same for whichever view is on screen: the volume, or one projection movie
	 * <p>		{@code projection} names a projection ({@code maxZ}, {@code meanY}, ...) or is null
	 * <br>		for the volume. A projection has one plane per channel and time point, and the
	 * <br>		region's Z range means nothing to it - but its X and Y still do, so the box is
	 * <br>		applied here by cropping what the renderer returns. {@link ViewRenderer} deliberately
	 * <br>		drops a box for a projection, because for a <em>view</em> an XYZ box does not
	 * <br>		describe one; an export asks for exactly the rectangle the user drew.
	 */
	static RegionPlanes regionPlanes(OmeZarrDataset dataset, Options options, String projection) {
		if (projection == null || projection.trim().isEmpty()) {
			validateVolume(dataset);
			return new RegionPlanes(new ViewRenderer(dataset, options, "s0", false), null, null);
		}
		validateProjection(dataset, projection, options);
		ViewRenderer renderer = new ViewRenderer(dataset, options, "projections/" + projection, true);
		try {
			Bounds box = options == null ? null : options.bounds;
			Rectangle crop = box == null ? null
					: cropOf(box, renderer.outputWidth(), renderer.outputHeight());
			return new RegionPlanes(renderer, crop,
					Integer.valueOf(projectionFrames(dataset, dataset.getProjectionDimensions(projection))));
		} catch (RuntimeException error) {
			renderer.close();
			throw error;
		}
	}

	/** The box's rectangle within a plane of this size, or null when it covers the whole of it. */
	private static Rectangle cropOf(Bounds box, int width, int height) {
		Bounds clamped = box.clampedTo(width, height, 1);
		if (clamped.x <= 0 && clamped.y <= 0 && clamped.width >= width && clamped.height >= height)
			return null;
		return new Rectangle(clamped.x, clamped.y, clamped.width, clamped.height);
	}

	/** How many time points a projection holds, which a half-written store may cut short. */
	static int projectionFrameCount(OmeZarrDataset dataset, String projection) {
		return projectionFrames(dataset, dataset.getProjectionDimensions(projection));
	}

	/**			What a projection movie's planes measure, as the view produces them
	 * <p>		Asked of the renderer rather than read off the array, for the same reason
	 * <br>		{@link #viewExtent} is: side by side doubles the width of a Z or Y projection, and a
	 * <br>		region drawn on a window is in the coordinates the window shows, not the ones the
	 * <br>		store keeps. Reading the array directly is also easy to get wrong -
	 * <br>		{@code OmeZarrDataset} hands back its dimensions <b>reversed</b> from Zarr's own
	 * <br>		order, so a projection is {@code [x, y, c, t]} and the last two are the channel and
	 * <br>		time counts, not the height and width.
	 * <p>
	 * @return	: {x, y, time points}
	 */
	static int[] projectionViewExtent(OmeZarrDataset dataset, String projection, Options options) {
		validateProjection(dataset, projection, options);
		ViewRenderer renderer = new ViewRenderer(dataset, options, "projections/" + projection, true);
		try {
			return new int[] { renderer.outputWidth(), renderer.outputHeight(),
					projectionFrameCount(dataset, projection) };
		} finally {
			renderer.close();
		}
	}

	/** A view's planes one at a time - the volume, or a projection movie; see {@link #regionPlanes}. */
	static final class RegionPlanes implements RegionExport.Planes, java.io.Closeable {
		private final ViewRenderer renderer;
		/** The region within a projection plane, or null for the volume and for a whole plane. */
		private final Rectangle crop;
		/** A projection's own time point count, or null when this is the volume. */
		private final Integer projectionFrames;

		private RegionPlanes(ViewRenderer renderer, Rectangle crop, Integer projectionFrames) {
			this.renderer = renderer;
			this.crop = crop;
			this.projectionFrames = projectionFrames;
		}

		@Override public ImageProcessor plane(int channel, int z, int timepoint) {
			if (projectionFrames == null) return renderer.renderVolume(channel, z, timepoint);
			ImageProcessor plane = renderer.renderProjection(channel, timepoint);
			if (crop == null) return plane;
			plane.setRoi(crop);
			return plane.crop();
		}

		/** True for a projection movie, which has one plane per channel and time point. */
		boolean isProjection() { return projectionFrames != null; }

		int width() { return crop != null ? crop.width : renderer.outputWidth(); }
		int height() { return crop != null ? crop.height : renderer.outputHeight(); }
		int depth() { return projectionFrames == null ? renderer.outputDepth() : 1; }
		int channels() { return renderer.outputCount(); }
		/** Time points this view has, which for a projection is its own count. */
		int frames() {
			return projectionFrames == null ? renderer.dataset.getTimepointCount()
					: projectionFrames.intValue();
		}

		@Override public void close() { renderer.close(); }
	}

	/**
	 * Extend an open virtual view to the time points committed since it was opened.
	 * <p>
	 * The stack object is grown in place and the window's own time slider is stretched to
	 * match, rather than handing ImageJ a new stack. That distinction is the whole point:
	 * {@code ImagePlus.setStack} finds the window's cached dimensions stale, rebuilds the
	 * StackWindow, and the rebuilt window loses its size, position, zoom and per-channel
	 * display ranges - which is exactly what a live view must not do on every new time point.
	 * <p>
	 * Only a view that spans every time point can grow. A single-T volume stays where it is.
	 *
	 * @param image				: a view previously returned by this class
	 * @param dataset			: a freshly read descriptor of the same dataset
	 * <p>
	 * @return					: frames after growing, or -1 when this view cannot grow
	 */
	public static int growVirtualView(ImagePlus image, OmeZarrDataset dataset) {
		if (image == null || dataset == null) return -1;
		ImageStack stack = image.getStack();
		if (!(stack instanceof OmeZarrVirtualStack) && !(stack instanceof OmeZarrVirtualProjectionStack))
			return -1;

		/* Read the layout before growing anything. ImagePlus.verifyDimensions silently
		 * collapses to a single channel and one long Z run the moment the stack is longer
		 * than nChannels * nSlices * nFrames, so asking afterwards returns nonsense. */
		final int channels = image.getNChannels();
		final int slices = image.getNSlices();
		final int before = image.getNFrames();

		int frames = stack instanceof OmeZarrVirtualStack
				? ((OmeZarrVirtualStack) stack).grow(dataset)
				: ((OmeZarrVirtualProjectionStack) stack).grow(dataset);
		if (frames <= before) return before;

		showGrownTimeAxis(image, channels, slices, frames);
		return frames;
	}

	/**			Make the frames a grown stack now holds reachable in its window
	 * <p>		Which way depends on the window ImageJ built, and a live view is usually built at
	 * 			one time point, when ImageJ gives it no time slider at all. Measured with real
	 * 			windows in ImageJ 1.54f:
	 * <ul>
	 * <li>a hyperstack that already has a time slider - stretched in place, see
	 * 	   {@link #extendTimeAxisInPlace};</li>
	 * <li>a single channel with a single plane per time point - one plain slider, which is the
	 * 	   time axis and the only shape ImageJ ever gives it, whatever the frame count - re-ranged
	 * 	   in place with {@code StackWindow.updateSliceSelector};</li>
	 * <li>anything else - a volume opened at one time point has a Z slider only, a
	 * 	   one-channel projection at one time point no slider - rebuilt once around the same image
	 * 	   and canvas, which is when it gains its time slider.</li>
	 * </ul>
	 * <p>		The old fallback, {@code setDimensions}, rebuilds only a window that is <em>already
	 * 			</em> a hyperstack. Every other window kept its one slider over the first time
	 * 			point, or, where the stack was never flagged as a hyperstack, one slider over every
	 * 			plane of every time point - Z and T run together into one dimension.
	 */
	static void showGrownTimeAxis(ImagePlus image, int channels, int slices, int frames) {
		ImageWindow window = image.getWindow();
		if (window == null) {
			// Nothing displayed, so nothing can be rebuilt; the ordinary call is safe here.
			image.setDimensions(channels, slices, frames);
		} else if (extendTimeAxisInPlace(image, frames)) {
			// the usual case once a view has more than one time point
		} else if (channels == 1 && slices == 1 && window instanceof StackWindow
				&& !((StackWindow) window).isHyperStack()) {
			image.setDimensions(channels, slices, frames);
			((StackWindow) window).updateSliceSelector();
		} else {
			rebuildWindowWithTimeAxis(image, channels, slices, frames);
		}
		image.updateAndDraw();
	}

	/**
	 * Build the window again around the same image and canvas, now with a time axis.
	 * <p>
	 * Reusing the canvas is what keeps the zoom and the visible part of the image; ImageJ keeps
	 * the location itself. Position and the channel tables are put back explicitly, because a new
	 * window resets both. Happens once per view: after it there is a time slider to stretch.
	 * <p>
	 * The dimensions are set without {@code setDimensions} on a displayed hyperstack, which would
	 * build a window of its own first - without the canvas - and reset a composite's ranges.
	 * <p>
	 * ImageJ reports the old window closing as the image closing. A view that releases its reader
	 * on that event must check that the image really has no window left; see
	 * {@link #closeRendererWhenImageCloses}.
	 */
	private static void rebuildWindowWithTimeAxis(ImagePlus image, int channels, int slices, int frames) {
		ImageWindow old = image.getWindow();
		/* The dimensions first, before any getter. The stack has already grown, and ImageJ's
		 * getters verify C*Z*T against its size and, finding it short, fold the image into one
		 * channel of every plane - which for a composite does not come back. Measured: a
		 * two-channel volume grown this way ended as 1/12/1 when its position was read first. */
		boolean dimensionsSet = false;
		if (image.isDisplayedHyperStack()) {
			try {
				Field imageFrames = ImagePlus.class.getDeclaredField("nFrames");
				imageFrames.setAccessible(true);
				imageFrames.setInt(image, frames);
				dimensionsSet = true;
			} catch (Throwable unsupported) {
				// falls through to the ordinary call, which rebuilds a window of its own
			}
		}
		if (!dimensionsSet) image.setDimensions(channels, slices, frames);
		int c = image.getC();
		int z = image.getZ();
		int t = image.getT();
		LUT[] tables = image instanceof CompositeImage ? ((CompositeImage) image).getLuts() : null;
		double min = image.getDisplayRangeMin();
		double max = image.getDisplayRangeMax();
		image.setOpenAsHyperStack(true);
		/* setDimensions on a displayed hyperstack has already replaced the window; only a window
		 * still showing the old shape needs building. */
		if (image.getWindow() == old) new StackWindow(image, old.getCanvas());
		image.setPosition(Math.min(c, channels), Math.min(z, slices), Math.min(t, frames));
		if (tables != null && tables.length >= channels) ((CompositeImage) image).setLuts(tables);
		else if (tables == null) image.setDisplayRange(min, max);
	}

	/**
	 * Lengthen a displayed hyperstack's time axis without rebuilding its window.
	 * <p>
	 * {@link ImagePlus#setDimensions} cannot be used for this. On a displayed hyperstack it
	 * ends with an unconditional {@code new StackWindow(this)} whenever any dimension changed,
	 * and the replacement window comes back at its default size and position with the canvas
	 * zoom reset - the visible "flash and resize" on every new time point. There is no public
	 * API that grows the axis and leaves the window alone, so the three fields that describe it
	 * are set directly: the count ImagePlus reports, the copy StackWindow cached when it built
	 * its sliders, and the slider's own range.
	 * <p>
	 * {@code pack()} is deliberately not called either; it would resize the window to its
	 * preferred size and undo whatever the user had set.
	 *
	 * @return					: false when the window is not a hyperstack window, or when a
	 * 							  future ImageJ keeps these somewhere else
	 */
	static boolean extendTimeAxisInPlace(ImagePlus image, int frames) {
		ImageWindow window = image.getWindow();
		if (!(window instanceof StackWindow)) return false;
		try {
			Field imageFrames = ImagePlus.class.getDeclaredField("nFrames");
			imageFrames.setAccessible(true);
			Field windowFrames = StackWindow.class.getDeclaredField("nFrames");
			windowFrames.setAccessible(true);
			Field selectorField = StackWindow.class.getDeclaredField("tSelector");
			selectorField.setAccessible(true);
			Object selector = selectorField.get(window);
			if (selector == null) return false;	// a window built before there was a T axis

			imageFrames.setInt(image, frames);
			windowFrames.setInt(window, frames);
			selector.getClass().getMethod("setMaximum", int.class).invoke(selector, frames + 1);
			try {
				selector.getClass().getMethod("setBlockIncrement", int.class)
						.invoke(selector, Math.max(1, frames / 10));
			} catch (Throwable optional) {
				// Paging by a tenth is a nicety; not being able to set it is not a failure.
			}
			((java.awt.Component) selector).repaint();
			window.repaint();
			return true;
		} catch (Throwable unsupported) {
			return false;
		}
	}

	/**			The chunks materialising one time point of a region actually decompressed
	 * <p>		A test seam, and the only way to show from outside that a region costs the box
	 * 			rather than the volume: the pixels a cropped view returns are identical either
	 * 			way, so pixel equality alone cannot tell the two apart.
	 */
	static long blocksReadForRegion(OmeZarrDataset dataset, Options options, int timepoint) {
		validateVolume(dataset);
		ViewRenderer renderer = new ViewRenderer(dataset, options, "s0", false);
		try {
			for (int c = 0; c < renderer.outputCount(); c++) {
				ImagePlus channel = renderer.materializeChannel(c, checkedTimepoint(dataset, timepoint));
				channel.changes = false;
				channel.close();
			}
			return renderer.blocksRead();
		} finally {
			renderer.close();
		}
	}

	/** Estimated in-RAM projection movie pixels after channel selection/runtime composition. */
	public static long estimateMaterializedProjectionBytes(
			OmeZarrDataset dataset, String projection, Options options) {
		validateProjection(dataset, projection, options);
		long[] dimensions = dataset.getProjectionDimensions(projection);
		ViewRenderer renderer = new ViewRenderer(dataset, options, "projections/" + projection, true);
		try {
			return saturatedMultiply(2L, renderer.outputWidth(), renderer.outputHeight(),
					renderer.outputCount(), projectionFrames(dataset, dimensions));
		} finally {
			renderer.close();
		}
	}

	private static long saturatedMultiply(long... values) {
		long result = 1;
		for (long value : values) {
			if (value != 0 && result > Long.MAX_VALUE / value) return Long.MAX_VALUE;
			result *= value;
		}
		return result;
	}

	private static void validateVolume(OmeZarrDataset dataset) {
		if (dataset == null || !dataset.hasVolume()) throw new IllegalArgumentException("Dataset has no readable s0 volume.");
		if (dataset.getTimepointCount() < 1) throw new IllegalArgumentException("Dataset has no committed timepoints yet.");
	}

	private static int checkedTimepoint(OmeZarrDataset dataset, int timepoint) {
		if (timepoint < 0 || timepoint >= dataset.getTimepointCount())
			throw new IllegalArgumentException("Timepoint is outside the committed range: " + timepoint);
		return timepoint;
	}

	/**
	 * Refuse the runtime views a projection axis cannot express.
	 * <p>
	 * None is refused now. Side by side along X used to be: the halves are neighbours along X,
	 * which maxX and meanX have projected away, and concatenating their ZY planes butted two
	 * views together into a doubled-Z image that looked plausible. It is not concatenated any
	 * more - see {@link ViewRenderer#mergeAlongX} - so a whole-image view has an X projection
	 * too. Kept as the one place a future impossible combination is turned away.
	 */
	private static void refuseImpossibleProjectionView(String projection, Operation operation) {
	}

	/**
	 * How an XY alignment survives a projection that has already collapsed one of its axes.
	 * <p>
	 * A projection along Z keeps both axes the alignment acts on, so the transform applies
	 * unchanged and the result is exact. A projection along X or Y does not: the alignment
	 * moves a voxel by an amount that depends on the very coordinate the projection summed
	 * away, and that dependence cannot be recovered afterwards.
	 * <p>
	 * What can be recovered is the transform evaluated at the collapsed axis's centre, which
	 * is a scale and an offset along the one spatial axis the projection still carries. That
	 * is exact for a pure translation - the perpendicular component is absorbed by the max or
	 * the mean - and for a rotation it is right on average, leaving features displaced by up
	 * to {@link #spreadPixels} according to where they sat along the collapsed axis.
	 */
	static final class OrthogonalAlignment {
		/** 2 x 3 affine acting on the projection image itself. */
		final double[][] planeMatrix;
		/** Whether the projection image is mirrored first, as the volume half would be. */
		final boolean flip;
		/** Worst displacement, in pixels, between this and a true recompute from the volume. */
		final double spreadPixels;

		OrthogonalAlignment(double[][] planeMatrix, boolean flip, double spreadPixels) {
			this.planeMatrix = planeMatrix;
			this.flip = flip;
			this.spreadPixels = spreadPixels;
		}

		ImageProcessor apply(ImageProcessor source, boolean interpolate) {
			return flip ? OpmRuntimeAlignment.transformPlane(source, planeMatrix, true, interpolate)
					: OpmRuntimeAlignment.transformPlaneWithoutFlip(source, planeMatrix, interpolate);
		}
	}

	/**
	 * Project an XY alignment onto the plane of an orthogonal projection.
	 * <p>
	 * For a Y projection the image is (X, Z): X survives, so the half is still mirrored and the
	 * alignment contributes {@code m00} as a scale along X plus {@code m01 * centreY + m02} as
	 * an offset. For an X projection the image is (Z, Y): X is gone, so nothing is mirrored and
	 * the alignment contributes {@code m11} along Y plus {@code m10 * centreX + m12}. Neither
	 * plane can carry a rotation of its own - rotating them would mix Z into the result, and Z
	 * takes no part in the alignment.
	 *
	 * @param matrix				: 2 x 3 alignment already mirrored for the half it applies to
	 * @param axis					: projection axis, {@code 'X'}, {@code 'Y'} or {@code 'Z'}
	 * @param volumeWidth			: X extent of the volume the projection came from
	 * @param volumeHeight			: Y extent of the volume the projection came from
	 * <p>
	 * @return						: the plane transform, or null when the axis needs none
	 */
	static OrthogonalAlignment orthogonalAlignment(
			double[][] matrix, char axis, int volumeWidth, int volumeHeight) {
		if (matrix == null || matrix.length < 2 || matrix[0].length < 3 || matrix[1].length < 3) return null;
		if (axis == 'Y') {
			double centre = (volumeHeight - 1) / 2.0;
			return new OrthogonalAlignment(new double[][] {
					{ matrix[0][0], 0, matrix[0][1] * centre + matrix[0][2] },
					{ 0, 1, 0 } }, true, Math.abs(matrix[0][1]) * centre);
		}
		if (axis == 'X') {
			double centre = (volumeWidth - 1) / 2.0;
			return new OrthogonalAlignment(new double[][] {
					{ 1, 0, 0 },
					{ 0, matrix[1][1], matrix[1][0] * centre + matrix[1][2] } },
					false, Math.abs(matrix[1][0]) * centre);
		}
		return null;
	}

	private static boolean isAlignment(Operation operation) {
		return operation == Operation.FLIP_ALIGN_RIGHT || operation == Operation.FLIP_ALIGN_LEFT;
	}

	private static String acquisitionBase(String label) {
		if (label == null) return null;
		if (label.endsWith("-left")) return label.substring(0, label.length() - 5);
		if (label.endsWith("-right")) return label.substring(0, label.length() - 6);
		return null;
	}

	private static String timeLabel(OmeZarrDataset dataset, int timepoint) {
		List<String> labels = dataset.getTimePointLabels();
		return timepoint >= 0 && timepoint < labels.size() ? labels.get(timepoint) : "t" + timepoint;
	}

	private static String title(OmeZarrDataset dataset, String suffix) {
		return dataset.getDisplayName() + " — " + suffix;
	}

	/**
	 * Give a multi-channel view the composite display mode, which the file cannot carry.
	 * <p>
	 * OME-Zarr has no display-mode concept. {@code omero.channels} stores colours and windows,
	 * but "composite versus colour versus grayscale" is a runtime property of ImageJ's
	 * {@link CompositeImage}, so nothing the writer emits can set it. Without this every
	 * multi-channel view opened needing a manual Image &gt; Color &gt; Make Composite.
	 * <p>
	 * This is the 16-bit branch of {@code ij.plugin.CompositeConverter.run(...)} without its
	 * dialog and window juggling. {@code CompositeConverter.makeComposite(...)} is no use here:
	 * it returns null for anything that is not 24-bit RGB. The constructor adopts the stack by
	 * reference, so a virtual view stays virtual and no plane is read eagerly. More channels
	 * than {@link CompositeImage#MAX_CHANNELS} fall back to colour mode inside ImageJ, exactly
	 * as the menu command does.
	 */
	static ImagePlus asComposite(ImagePlus image, int channels) {
		if (channels < 2) return image;
		CompositeImage composite = new CompositeImage(image, CompositeImage.COMPOSITE);
		/* Colours first: the two-argument setChannelLut clones the table it is given, so it
		 * also replaces that channel's display range with the LUT default of 0-255. The range
		 * pass below therefore has to come after the colours, not before. */
		Utils.autoSetLUTs(composite);
		composite.reset();
		resetDisplayRangesFromMiddle(composite);
		return composite;
	}

	/**
	 * Contrast each channel from a mid-stack plane instead of the first one.
	 * <p>
	 * {@link CompositeImage#resetDisplayRanges()} samples slice 1 of every channel. In a
	 * deskewed volume that slice is the empty leading edge of the shear, so production data
	 * whose signal reaches about 23000 came back ranged 0-150 and the whole view saturated.
	 * ImageJ contrasts a freshly shown 16-bit image from its first slice too, so this is not
	 * a regression being fixed but the same trap the deskewed geometry walks into either way.
	 * One plane per channel is read, the cost ImageJ already pays, only from the middle.
	 */
	private static void resetDisplayRangesFromMiddle(CompositeImage composite) {
		ImageStack stack = composite.getImageStack();
		int middle = Math.max(1, (composite.getNSlices() + 1) / 2);
		for (int c = 1; c <= composite.getNChannels(); c++) {
			ImageProcessor plane = stack.getProcessor(composite.getStackIndex(c, middle, 1));
			if (plane == null) continue;
			plane.resetMinAndMax();
			/* getChannelLut hands back the live LUT, which is how resetDisplayRanges itself
			 * records a range without disturbing the colour already installed. */
			LUT lut = composite.getChannelLut(c);
			lut.min = plane.getMin();
			lut.max = plane.getMax();
		}
	}

	private static void calibrateVolume(ImagePlus image, OmeZarrDataset dataset) {
		double[] voxel = dataset.voxelSizeUm();
		Calibration calibration = new Calibration();
		calibration.setUnit("µm");
		calibration.pixelWidth = positiveOrOne(voxel[0]);
		calibration.pixelHeight = positiveOrOne(voxel[1]);
		calibration.pixelDepth = positiveOrOne(voxel[2]);
		calibration.frameInterval = dataset.frameIntervalSeconds();
		calibration.setTimeUnit("sec");
		image.setCalibration(calibration);
	}

	private static void calibrateProjection(ImagePlus image, OmeZarrDataset dataset, String projection) {
		double[] pixel = dataset.getProjectionPixelSizeUm(projection);
		Calibration calibration = new Calibration();
		calibration.setUnit("µm");
		calibration.pixelWidth = positiveOrOne(pixel[0]);
		calibration.pixelHeight = positiveOrOne(pixel[1]);
		calibration.frameInterval = dataset.frameIntervalSeconds();
		calibration.setTimeUnit("sec");
		image.setCalibration(calibration);
	}

	private static double positiveOrOne(double value) {
		return Double.isFinite(value) && value > 0 ? value : 1;
	}

	private static void attachInfo(ImagePlus image, OmeZarrDataset dataset, ViewRenderer renderer) {
		List<String> channels = renderer.outputLabels();
		StringBuilder info = new StringBuilder();
		info.append("OME-Zarr: ").append(dataset.getRoot().getAbsolutePath()).append('\n');
		info.append("Channels: ").append(channels).append('\n');
		if (dataset.getProvenance() != null) info.append(dataset.getProvenance().toPrettyJson());
		if (!dataset.getWarnings().isEmpty()) info.append("\nWarnings: ").append(dataset.getWarnings());
		image.setProperty("Info", info.toString());
		image.setProperty("opm.channelLabels", channels.toString());
		/* Where this view sits in the dataset, so an ROI drawn on it can be turned back into
		 * a region of the whole volume. Without it a cropped view's ROI would be out by the
		 * crop's own origin, and nothing about the image itself would reveal that. */
		image.setProperty(OmeZarrRoi.ROOT_PROPERTY, dataset.getRoot().getAbsolutePath());
		image.setProperty(OmeZarrRoi.ORIGIN_PROPERTY, renderer.describeOrigin());
	}

	/**
	 * Release the store's reader once the view is really gone.
	 * <p>
	 * "Really" because ImageJ also reports the image as closed when it replaces the image's
	 * window - which {@link #showGrownTimeAxis} does once, and {@code setDimensions} does on a
	 * displayed hyperstack. Closing the reader then left a live window reading from a closed store.
	 * The decision is therefore taken after the event has been handled, when a rebuilt window has
	 * been attached to the image and a closed one has not.
	 */
	private static void closeRendererWhenImageCloses(final ImagePlus owner, final ViewRenderer renderer) {
		ImagePlus.addImageListener(new ImageListener() {
			@Override public void imageOpened(ImagePlus image) { }
			@Override public void imageUpdated(ImagePlus image) { }
			@Override public void imageClosed(ImagePlus image) {
				if (image != owner) return;
				final ImageListener listener = this;
				java.awt.EventQueue.invokeLater(new Runnable() {
					@Override public void run() {
						ImageWindow window = owner.getWindow();
						if (window != null && !window.isClosed()) return;	// rebuilt, not closed
						renderer.close();
						ImagePlus.removeImageListener(listener);
					}
				});
			}
		});
	}

	/** Every view opens as a hyperstack, so its C, Z and T each get a slider rather than one between them. */
	private static ImagePlus asHyperStack(ImagePlus image) {
		/* Needed on the result and not only on the base: a CompositeImage built from a base with
		 * the flag set does not always carry it, and a two-channel projection at one time point
		 * then opened as a plain stack whose slider could never reach a second time point. */
		image.setOpenAsHyperStack(image.getStackSize() > 1);
		return image;
	}

	private static final class OmeZarrVirtualStack extends VirtualStack {
		private final ViewRenderer renderer;
		private final int depth;
		private final int firstTimepoint;
		/** Grows in place while a live acquisition commits further time points. */
		private volatile int frames;
		private final boolean spansAllTimepoints;

		OmeZarrVirtualStack(ViewRenderer renderer, int depth, int firstTimepoint, int frames,
				boolean spansAllTimepoints) {
			super(renderer.outputWidth(), renderer.outputHeight());
			this.renderer = renderer;
			this.depth = depth;
			this.firstTimepoint = firstTimepoint;
			this.frames = frames;
			this.spansAllTimepoints = spansAllTimepoints;
			setBitDepth(16);
		}

		/** Take up newly committed time points; a single-T view keeps the one it was opened at. */
		int grow(OmeZarrDataset dataset) {
			if (!spansAllTimepoints) return frames;
			int published = Math.min(dataset.getTimepointCount(), renderer.refreshTimepoints());
			if (published > frames) frames = published;
			return frames;
		}

		@Override public int size() { return getSize(); }
		@Override public int getSize() { return renderer.outputCount() * depth * frames; }
		@Override public int getBitDepth() { return 16; }

		@Override
		public ImageProcessor getProcessor(int index) {
			if (index < 1 || index > getSize()) throw new IllegalArgumentException("Slice out of range: " + index);
			int zero = index - 1;
			int channel = zero % renderer.outputCount();
			int z = (zero / renderer.outputCount()) % depth;
			int timepoint = firstTimepoint + zero / (renderer.outputCount() * depth);
			return renderer.renderVolume(channel, z, timepoint);
		}

		@Override
		public String getSliceLabel(int index) {
			int zero = index - 1;
			int channel = zero % renderer.outputCount();
			int z = (zero / renderer.outputCount()) % depth;
			int timepoint = firstTimepoint + zero / (renderer.outputCount() * depth);
			return renderer.outputLabel(channel) + ", z=" + z + ", " + timeLabel(renderer.dataset, timepoint);
		}
	}

	/** C-fastest, then T: the ImageJ hyperstack order for a projection movie with Z=1. */
	private static final class OmeZarrVirtualProjectionStack extends VirtualStack {
		private final ViewRenderer renderer;
		private volatile int frames;

		OmeZarrVirtualProjectionStack(ViewRenderer renderer, int frames) {
			super(renderer.outputWidth(), renderer.outputHeight());
			this.renderer = renderer;
			this.frames = frames;
			setBitDepth(16);
		}

		/** Take up newly committed time points. A projection movie always spans every T. */
		int grow(OmeZarrDataset dataset) {
			int published = Math.min(dataset.getTimepointCount(), renderer.refreshTimepoints());
			if (published > frames) frames = published;
			return frames;
		}

		@Override public int size() { return getSize(); }
		@Override public int getSize() { return renderer.outputCount() * frames; }
		@Override public int getBitDepth() { return 16; }

		@Override
		public ImageProcessor getProcessor(int index) {
			if (index < 1 || index > getSize())
				throw new IllegalArgumentException("Projection plane out of range: " + index);
			int zero = index - 1;
			int channel = zero % renderer.outputCount();
			int timepoint = zero / renderer.outputCount();
			return renderer.renderProjection(channel, timepoint);
		}

		@Override
		public String getSliceLabel(int index) {
			int zero = index - 1;
			int channel = zero % renderer.outputCount();
			int timepoint = zero / renderer.outputCount();
			return renderer.outputLabel(channel) + " / " + timeLabel(renderer.dataset, timepoint);
		}
	}

	private static final class ViewRenderer {
		private final OmeZarrDataset dataset;
		private final Options options;
		private final OmeZarrPlaneReader reader;
		private final List<OutputChannel> outputs;
		private final boolean projection;
		private final char projectionAxis;
		/** A mean projection rather than a maximum; decides how an X projection's halves merge. */
		private final boolean projectionMean;
		/** The clamped region, or null when the whole view is being read. */
		private final Bounds region;
		private boolean gpuUsable;
		private boolean gpuFailureLogged;

		ViewRenderer(OmeZarrDataset dataset, Options options, String path, boolean projection) {
			if (dataset == null) throw new IllegalArgumentException("Dataset is required.");
			this.dataset = dataset;
			this.options = options == null ? new Options() : options.copy();
			this.projection = projection;
			this.projectionAxis = projection ? Character.toUpperCase(path.charAt(path.length() - 1)) : 0;
			String projectionName = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
			this.projectionMean = projection && (projectionName.startsWith("mean") || projectionName.startsWith("avg"));
			this.reader = new OmeZarrPlaneReader(dataset, path);
			this.outputs = outputRange(resolveOutputs(dataset, this.options), this.options);
			if (outputs.isEmpty())
				throw new IllegalArgumentException("None of the requested channels "
						+ this.options.requestedChannels + " exist in this dataset, which stores "
						+ dataset.getChannelLabels() + ".");
			/* A projection has already collapsed one axis, so an XYZ box does not describe a
			 * region of it. Ignoring the box there is better than cropping the wrong axes. */
			Bounds requested = projection ? null : this.options.bounds;
			Bounds clamped = requested == null ? null
					: requested.clampedTo(fullWidth(), fullHeight(), fullDepth());
			this.region = clamped != null && clamped.covers(fullWidth(), fullHeight(), fullDepth())
					? null : clamped;
			this.gpuUsable = this.options.tryGpu;
			logIfRuntimeViewDoesNothing();
		}

		/**
		 * Say so when the chosen runtime view leaves every selected channel untouched.
		 * <p>
		 * The channel filter and the runtime view both speak about sides, so they can cancel
		 * out: asking to align the right half while only left halves are selected transforms
		 * nothing. The view used to open silently, pixel for pixel identical to stored
		 * channels, with no way to tell the request had been dropped.
		 */
		private void logIfRuntimeViewDoesNothing() {
			if (options.operation == Operation.STORED_CHANNELS
					|| options.operation == Operation.SIDE_BY_SIDE) return;
			for (OutputChannel output : outputs)
				if (transformFor(output).flip || orthogonalFor(output) != null) return;
			IJ.log("OPM viewer: \"" + options.operation + "\" leaves " + outputLabels()
					+ " unchanged; these are the stored pixels. " + whyNothingHappened());
		}

		private String whyNothingHappened() {
			OpmProvenance provenance = dataset.getProvenance();
			if (isAlignment(options.operation) && provenance != null && provenance.alignApplied)
				return "The dataset records opm.alignApplied = true, so the alignment is already baked in.";
			if (projection && projectionAxis == 'X')
				return "Projecting along X already removes the effect of an X flip.";
			if (options.operation == Operation.FLIP_ONLY)
				return "No selected channel is on the side opm.alignFlipHalf names.";
			return "No selected channel is on the side this view transforms.";
		}

		/** Re-read how many time points the array now publishes. */
		int refreshTimepoints() { return reader.refreshTimepoints(); }

		/** Chunks this renderer's reader has decompressed; instrumentation, not behaviour. */
		long blocksRead() { return reader.blocksRead(); }

		int outputCount() { return outputs.size(); }

		/** Full output size before any region is applied; the crop is measured against this. */
		private int fullWidth() {
			return options.operation == Operation.SIDE_BY_SIDE && !mergesAlongX()
					? reader.width() * 2 : reader.width();
		}

		/** A whole-width X projection: the halves are merged pixel by pixel, not placed side by side. */
		private boolean mergesAlongX() {
			return projection && projectionAxis == 'X';
		}

		/**
		 * The X projection of the full camera width, from the X projections of its two halves.
		 * <p>
		 * Exact for a maximum, because the deskew shear acts in Y and Z only: every ZY position
		 * of the full width is the same ZY position in both halves, so the maximum over the whole
		 * row is the larger of the two halves' maxima. A mean is the mean of the two halves'
		 * means, which equal-width halves make the whole-row mean to within the rounding each
		 * stored mean already carries. A half this dataset lacks leaves the other as it is.
		 */
		private ImageProcessor mergeAlongX(OutputChannel output, int timepoint) {
			ImageProcessor left = output.leftIndex < 0 ? null : read(output.leftIndex, 0, timepoint);
			ImageProcessor right = output.rightIndex < 0 ? null : read(output.rightIndex, 0, timepoint);
			if (left == null) return right;
			if (right == null) return left;
			short[] a = (short[]) left.getPixels();
			short[] b = (short[]) right.getPixels();
			short[] merged = new short[a.length];
			for (int i = 0; i < merged.length; i++) {
				int l = a[i] & 0xffff;
				int r = b[i] & 0xffff;
				merged[i] = (short) (projectionMean ? (l + r + 1) / 2 : Math.max(l, r));
			}
			return new ShortProcessor(left.getWidth(), left.getHeight(), merged, null);
		}
		private int fullHeight() { return reader.height(); }
		private int fullDepth() { return projection ? 1 : dataset.getDepth(); }

		int outputWidth() { return region == null ? fullWidth() : region.width; }
		int outputHeight() { return region == null ? fullHeight() : region.height; }
		int outputDepth() { return region == null ? fullDepth() : region.depth(); }

		/** "x,y,zStart" of this view within the full volume; zeroes when nothing is cropped. */
		String describeOrigin() {
			return region == null ? "0,0,0" : region.x + "," + region.y + "," + region.zStart;
		}

		/** Add the region to a view title, so a cropped window can never be mistaken for the whole. */
		String describeExtent(String base) {
			return region == null ? base : base + " region [" + region + "]";
		}

		/** Cut the requested box out of a rendered plane; identity when the box covers it. */
		private ImageProcessor crop(ImageProcessor plane) {
			if (region == null || plane == null) return plane;
			plane.setRoi(region.x, region.y, region.width, region.height);
			return plane.crop();
		}
		String outputLabel(int index) { return outputs.get(index).label; }
		List<String> outputLabels() {
			List<String> labels = new ArrayList<String>();
			for (OutputChannel output : outputs) labels.add(output.label);
			return labels;
		}

		ImageProcessor renderVolume(int output, int z, int timepoint) {
			int sourceZ = region == null ? z : region.zStart + z;
			return render(outputs.get(output),
					new long[] { sourceZ, sourceIndex(outputs.get(output)), timepoint }, sourceZ, timepoint);
		}

		ImageProcessor renderProjection(int output, int timepoint) {
			return render(outputs.get(output), new long[] { sourceIndex(outputs.get(output)), timepoint }, 0, timepoint);
		}

		private ImageProcessor render(OutputChannel output, long[] coordinates, int z, int timepoint) {
			if (readsRegionDirectly()) return renderRegionPlane(output, z, timepoint);
			return crop(renderFull(output, coordinates, z, timepoint));
		}

		/**
		 * Whether a region is being read at all.
		 * <p>
		 * A projection has already collapsed an axis, so an XYZ box does not describe a region
		 * of one; everything else with a box reads the box.
		 */
		private boolean readsRegionDirectly() {
			return region != null && !projection;
		}

		/**
		 * Margin round an inverse-mapped box: one pixel for the bilinear footprint, one for the
		 * rounding to whole pixels either side of it.
		 */
		private static final int ALIGNED_MARGIN = 2;

		/**
		 * One plane of the region, however this output is transformed.
		 * <p>
		 * Reached for every volume view that has a region. The two cases differ only in how the
		 * source rectangle is worked out, not in whether one exists: an affine map sends a
		 * rectangle to a parallelogram, and the pixels a box needs are always some bounded
		 * rectangle of the source.
		 */
		private ImageProcessor renderRegionPlane(OutputChannel output, int z, int timepoint) {
			TransformRequest request = transformFor(output);
			double[][] alignment = request.alignment;
			return alignment == null
					? renderRegion(output, z, timepoint)
					: renderAlignedRegion(output, alignment, request.flip, z, timepoint);
		}

		/**			The region of an aligned output plane, without ever reading the whole plane
		 * <p>		This is the case the exact-integer path cannot serve. A rotation mixes
		 * 			neighbouring columns, so no whole-pixel rectangle of the source maps onto
		 * 			the box - but a rectangle that <em>contains</em> everything the box samples
		 * 			does exist, and that is enough. The four corners of the box are mapped back
		 * 			through the alignment's inverse, the bounding box of those points is padded
		 * 			by the interpolation footprint, and only that is read.
		 * <p>		The work is done in the <em>flipped</em> frame, because that is the frame
		 * 			the whole-plane path warps in: it mirrors the plane and then applies the
		 * 			matrix. Collapsing those two into one matrix over unflipped coordinates
		 * 			looks equivalent and is not. The warp rounds its sample coordinate, and
		 * 			rounding does not survive a reflection - a sample at -0.63 rounds into the
		 * 			image, while its mirror at 1199.63 rounds out of it and reads as background.
		 * 			That is a one-pixel border difference, and it is exactly the kind that a
		 * 			test with an identity matrix never sees.
		 * <p>		Mirroring the rectangle rather than the plane is safe on its own terms:
		 * 			reflection is a whole-pixel permutation, so the mirror of a rectangle is a
		 * 			rectangle and the sub-image is the same pixels either way.
		 * <p>		Deliberately the CPU warp. Over a box of a few tens of pixels an upload
		 * 			would cost more than the transform, and the point of arriving here is that
		 * 			the box is small.
		 * <p>		Measured on a 1600x1484x212 4-channel acquisition, one time point of a
		 * 			64x64 region: 5512 chunks and 23 s before, 848 chunks and about 1 s after,
		 * 			with no 1 GB whole-volume buffer in between.
		 */
		private ImageProcessor renderAlignedRegion(
				OutputChannel output, double[][] alignment, boolean flip, int z, int timepoint) {
			double[][] inverse = Transform.inverseAlignmentMatrix2D(alignment);
			int lastX = region.x + region.width - 1;
			int lastY = region.y + region.height - 1;
			double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE;
			double minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
			for (int corner = 0; corner < 4; corner++) {
				double x = (corner & 1) == 0 ? region.x : lastX;
				double y = (corner & 2) == 0 ? region.y : lastY;
				double u = inverse[0][0] * x + inverse[0][1] * y + inverse[0][2];
				double v = inverse[1][0] * x + inverse[1][1] * y + inverse[1][2];
				minU = Math.min(minU, u);
				maxU = Math.max(maxU, u);
				minV = Math.min(minV, v);
				maxV = Math.max(maxV, v);
			}

			/* At least the size of the box, because the warp's output is the size of its input
			 * and the box is cut out of it; never larger than the plane, so a degenerate map
			 * costs a whole plane rather than failing. */
			int width = (int) Math.min(fullWidth(), Math.max(region.width,
					Math.ceil(maxU) - Math.floor(minU) + 1 + 2 * ALIGNED_MARGIN));
			int height = (int) Math.min(fullHeight(), Math.max(region.height,
					Math.ceil(maxV) - Math.floor(minV) + 1 + 2 * ALIGNED_MARGIN));
			int sourceX = clampOrigin((int) Math.floor(minU) - ALIGNED_MARGIN, width, fullWidth());
			int originY = clampOrigin((int) Math.floor(minV) - ALIGNED_MARGIN, height, fullHeight());

			// For a right half, read the stored rectangle whose mirror is sourceX..sourceX+width.
			ImageProcessor source = reader.readPlane(
					new OmeZarrPlaneReader.Rect(flip ? fullWidth() - sourceX - width : sourceX,
							originY, width, height),
					z, output.sourceIndex, timepoint);
			if (flip) source.flipHorizontal();

			/* The warp re-based rather than re-derived: in coordinates local to the box and to
			 * the rectangle, the inverse map keeps its linear part and gains a translation of
			 * L*(regionOrigin) + c - rectangleOrigin. The forward matrix the warp wants is that
			 * inverted, and because the warp returns an image the size of its input, the region
			 * is its top-left corner. */
			double[][] localInverse = {
				{ inverse[0][0], inverse[0][1],
					inverse[0][0] * region.x + inverse[0][1] * region.y + inverse[0][2] - sourceX },
				{ inverse[1][0], inverse[1][1],
					inverse[1][0] * region.x + inverse[1][1] * region.y + inverse[1][2] - originY }
			};
			ImageProcessor warped = SIFT.alignWithRigid2DMatrix(
					source, Transform.inverseAlignmentMatrix2D(localInverse), options.interpolate);
			warped.setRoi(0, 0, region.width, region.height);
			return warped.crop();
		}

		/**
		 * Slide a window of this size inside the plane.
		 * <p>
		 * Sliding rather than shrinking: a window pushed past an edge still has to be able to
		 * hold the box. What moves outside the plane is sampled as zero either way, which is
		 * exactly what the whole-plane path does there, so the result is unchanged.
		 */
		private static int clampOrigin(int origin, int size, int extent) {
			return Math.max(0, Math.min(origin, extent - size));
		}

		/**
		 * One output plane's region, read as the box it is rather than as a whole plane.
		 * <p>
		 * A mirrored half is read from the mirrored box and then mirrored about its own width.
		 * Those are the same pixels the whole-plane flip would have left there: reflection is
		 * its own inverse and carries no interpolation, so the box can be reflected before it
		 * is cut or after it with the same result. A side-by-side output reads the part of the
		 * box falling on each half and joins them, so a box straddling the seam costs the
		 * chunks either side of it and nothing else.
		 * <p>
		 * Deliberately the CPU mirror even where the GPU is available: this is a memory copy
		 * over a box that is normally small, and uploading it would cost more than it saves.
		 */
		private ImageProcessor renderRegion(OutputChannel output, int z, int timepoint) {
			if (output.sideBySide) {
				int half = reader.width();
				ImageProcessor left = readWindow(output.leftIndex, halfWindow(0, half), z, timepoint);
				ImageProcessor right = readWindow(output.rightIndex, halfWindow(half, half), z, timepoint);
				if (left == null) return right;
				if (right == null) return left;
				return concatenate(left, right);
			}
			boolean flip = transformFor(output).flip;
			OmeZarrPlaneReader.Rect window = flip
					? new OmeZarrPlaneReader.Rect(fullWidth() - region.x - region.width,
							region.y, region.width, region.height)
					: new OmeZarrPlaneReader.Rect(region.x, region.y, region.width, region.height);
			ImageProcessor box = reader.readPlane(window, z, output.sourceIndex, timepoint);
			return flip ? OpmRuntimeAlignment.transformPlane(box, null, true, options.interpolate) : box;
		}

		/** The part of the region falling on one half of a side-by-side view, or null. */
		private OmeZarrPlaneReader.Rect halfWindow(int offset, int width) {
			int from = Math.max(region.x, offset);
			int to = Math.min(region.x + region.width, offset + width);
			if (to <= from) return null;
			return new OmeZarrPlaneReader.Rect(from - offset, region.y, to - from, region.height);
		}

		private ImageProcessor readWindow(int sourceIndex, OmeZarrPlaneReader.Rect window,
				int z, int timepoint) {
			if (window == null) return null;
			if (sourceIndex < 0) return new ShortProcessor(window.width, window.height);
			return reader.readPlane(window, z, sourceIndex, timepoint);
		}

		/** The whole plane, before any region is cut out of it. */
		private ImageProcessor renderFull(OutputChannel output, long[] coordinates, int z, int timepoint) {
			if (output.sideBySide && mergesAlongX()) return mergeAlongX(output, timepoint);
			if (output.sideBySide) {
				ImageProcessor left = read(output.leftIndex, z, timepoint);
				ImageProcessor right = read(output.rightIndex, z, timepoint);
				return concatenate(left, right);
			}
			ImageProcessor source = reader.readPlane(coordinates);
			OrthogonalAlignment orthogonal = orthogonalFor(output);
			if (orthogonal != null) return orthogonal.apply(source, options.interpolate);
			TransformRequest transform = transformFor(output);
			if (!transform.flip && transform.alignment == null) return source;
			if (gpuUsable) {
				try {
					return transform.flip
							? OpmRuntimeAlignment.transformPlaneGpu(source, transform.alignment, options.interpolate)
							: OpmRuntimeAlignment.transformPlaneGpuWithoutFlip(source, transform.alignment, options.interpolate);
				} catch (Throwable error) {
					gpuUsable = false;
					logGpuFallback(error);
				}
			}
			return transform.flip
					? OpmRuntimeAlignment.transformPlane(source, transform.alignment, true, options.interpolate)
					: OpmRuntimeAlignment.transformPlaneWithoutFlip(source, transform.alignment, options.interpolate);
		}

		private ImageProcessor read(int sourceIndex, int z, int timepoint) {
			if (sourceIndex < 0) return new ShortProcessor(reader.width(), reader.height());
			return projection ? reader.readPlane(sourceIndex, timepoint) : reader.readPlane(z, sourceIndex, timepoint);
		}

		ImagePlus materializeChannel(int outputIndex, int timepoint) {
			OutputChannel output = outputs.get(outputIndex);
			int fromZ = region == null ? 0 : region.zStart;
			int toZ = fromZ + outputDepth();
			/* The crop is pushed into the reader, so materialising a region reads only the
			 * chunks the region covers and never allocates a whole plane - not even for an
			 * aligned view, which used to build the entire 1 GB volume and crop it. This is
			 * the path a small box out of a large volume takes, and the reason it is worth
			 * taking. */
			if (readsRegionDirectly()) {
				ImageStack stack = new ImageStack(outputWidth(), outputHeight());
				for (int z = fromZ; z < toZ; z++) stack.addSlice(renderRegionPlane(output, z, timepoint));
				return new ImagePlus(output.label, stack);
			}
			if (output.sideBySide) {
				ImageStack stack = new ImageStack(outputWidth(), outputHeight());
				for (int z = fromZ; z < toZ; z++)
					stack.addSlice(crop(concatenate(
							read(output.leftIndex, z, timepoint), read(output.rightIndex, z, timepoint))));
				return new ImagePlus(output.label, stack);
			}
			/* The transform runs on the full plane and the crop comes after it, so a region of
			 * an aligned view is the same pixels the aligned whole view would have shown. */
			ImageStack rawStack = new ImageStack(reader.width(), reader.height());
			for (int z = fromZ; z < toZ; z++) rawStack.addSlice(read(output.sourceIndex, z, timepoint));
			ImagePlus raw = new ImagePlus(output.label, rawStack);
			TransformRequest transform = transformFor(output);
			if (!transform.flip && transform.alignment == null) return cropVolume(raw);
			if (gpuUsable) {
				try {
					ImagePlus transformed = transform.flip
							? OpmRuntimeAlignment.transformVolumeGpu(raw, transform.alignment, options.interpolate)
							: OpmRuntimeAlignment.transformVolumeGpuWithoutFlip(raw, transform.alignment, options.interpolate);
					raw.changes = false;
					raw.close();
					return cropVolume(transformed);
				} catch (Throwable error) {
					gpuUsable = false;
					logGpuFallback(error);
				}
			}
			ImagePlus transformed = transform.flip
					? OpmRuntimeAlignment.transformVolumeCpu(raw, transform.alignment, options.interpolate)
					: OpmRuntimeAlignment.transformVolumeCpuWithoutFlip(raw, transform.alignment, options.interpolate);
			raw.changes = false;
			raw.close();
			return cropVolume(transformed);
		}

		/** Cut the region out of an already transformed volume, replacing it. */
		private ImagePlus cropVolume(ImagePlus volume) {
			if (region == null || volume == null) return volume;
			ImageStack cropped = new ImageStack(region.width, region.height);
			for (int z = 1; z <= volume.getStackSize(); z++)
				cropped.addSlice(crop(volume.getStack().getProcessor(z)));
			ImagePlus result = new ImagePlus(volume.getTitle(), cropped);
			volume.changes = false;
			volume.close();
			return result;
		}

		/**
		 * The alignment matrix this output would be transformed by, ignoring the projection.
		 * <p>
		 * {@link #transformFor} answers the same question for a volume, but it also decides
		 * that an X projection flips nothing and mirrors about {@code reader.width()}, which on
		 * an X projection is the Z extent rather than the camera width. The orthogonal path
		 * needs the volume's own width and needs the matrix even where nothing is mirrored.
		 */
		private double[][] alignmentMatrixFor(OutputChannel output) {
			if (!isAlignment(options.operation)) return null;
			OpmProvenance provenance = dataset.getProvenance();
			if (provenance == null || provenance.alignApplied) return null;
			if (provenance.alignMatrices != null && !provenance.alignMatrices.isEmpty())
				return taggedPlacement(output).matrix;
			if (provenance.alignMatrix == null) return null;
			boolean transforms = options.operation == Operation.FLIP_ALIGN_RIGHT ? output.right : output.left;
			if (!transforms) return null;
			return output.left
					? Transform.mirrorAlignmentMatrix2D(provenance.alignMatrix, dataset.getWidth())
					: provenance.alignMatrix;
		}

		/** The approximate plane transform for this output, or null when the exact path applies. */
		private OrthogonalAlignment orthogonalFor(OutputChannel output) {
			if (!projection || projectionAxis == 'Z') return null;
			OrthogonalAlignment overlay = orthogonalAlignment(alignmentMatrixFor(output), projectionAxis,
					dataset.getWidth(), dataset.getHeight());
			if (overlay == null || !tagged()) return overlay;
			/* A bare matrix only ever belongs to the mirrored half, which is what
			 * orthogonalAlignment assumes. A tagged set gives a matrix to unmirrored halves too -
			 * the reference's own identity, a left half of a second file - and mirroring those
			 * in a Y projection put them on the wrong side. */
			boolean mirror = projectionAxis == 'Y' && taggedPlacement(output).mirror;
			return mirror == overlay.flip ? overlay
					: new OrthogonalAlignment(overlay.planeMatrix, mirror, overlay.spreadPixels);
		}

		private boolean tagged() {
			OpmProvenance provenance = dataset.getProvenance();
			return provenance != null && provenance.alignMatrices != null
					&& !provenance.alignMatrices.isEmpty();
		}

		/** Mirror and matrix for one source of a tagged set, for the half this view flips. */
		private AlignmentMatrixSet.Placement taggedPlacement(OutputChannel output) {
			OpmProvenance provenance = dataset.getProvenance();
			return AlignmentMatrixSet.placement(
					AlignmentMatrixSet.tagged(provenance.alignReference, provenance.alignMatrices),
					output.label, options.operation == Operation.FLIP_ALIGN_LEFT, dataset.getWidth());
		}

		/** The worst displacement any selected channel will show, or -1 when none is approximate. */
		double approximateSpreadPixels() {
			double worst = -1;
			for (OutputChannel output : outputs) {
				OrthogonalAlignment orthogonal = orthogonalFor(output);
				if (orthogonal != null) worst = Math.max(worst, orthogonal.spreadPixels);
			}
			return worst;
		}

		private TransformRequest transformFor(OutputChannel output) {
			OpmProvenance provenance = dataset.getProvenance();
			boolean alreadyAligned = provenance != null && provenance.alignApplied;
			boolean flip = false;
			boolean align = false;
			/* Tagged matrix sets are measured in one convention - right sources mirrored, each
			 * labelled source mapped to alignReference - and shown from whichever side the view
			 * names: "right onto left" as measured, "left onto right" through
			 * AlignmentMatrixSet.placement. They used to ignore the choice and always mirror the
			 * right halves. */
			if (isAlignment(options.operation) && !alreadyAligned && tagged()) {
				AlignmentMatrixSet.Placement placement = taggedPlacement(output);
				flip = placement.mirror;
				if (projection && projectionAxis == 'X') flip = false;
				return new TransformRequest(flip, placement.matrix);
			}
			if (options.operation == Operation.FLIP_ONLY) {
				String side = options.flipHalf;
				if (!BatchChannelOperation.FLIP_LEFT.equals(side) && !BatchChannelOperation.FLIP_RIGHT.equals(side))
					side = provenance == null ? null : provenance.alignFlipHalf;
				boolean left = BatchChannelOperation.FLIP_LEFT.equals(side);
				flip = left ? output.left : output.right;
				// Projecting along X removes the effect of an X reflection; flipping its ZY image is wrong.
				if (projection && projectionAxis == 'X') flip = false;
			} else if (options.operation == Operation.FLIP_ALIGN_RIGHT && !alreadyAligned) {
				flip = output.right;
				align = flip;
			} else if (options.operation == Operation.FLIP_ALIGN_LEFT && !alreadyAligned) {
				flip = output.left;
				align = flip;
			}
			double[][] matrix = provenance == null ? null : provenance.alignMatrix;
			if (align && matrix != null && output.left)
				matrix = Transform.mirrorAlignmentMatrix2D(matrix, reader.width());
			return new TransformRequest(flip, align ? matrix : null);
		}

		private void logGpuFallback(Throwable error) {
			if (gpuFailureLogged) return;
			gpuFailureLogged = true;
			IJ.log("OPM Zarr Viewer: runtime GPU transform unavailable; using CPU. "
					+ (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
		}

		void close() {
			try { reader.close(); }
			catch (java.io.IOException ignored) { }
		}

		private static int sourceIndex(OutputChannel output) { return output.sourceIndex; }
	}

	private static List<OutputChannel> resolveOutputs(OmeZarrDataset dataset, Options options) {
		List<String> labels = dataset.getChannelLabels();
		List<Integer> selected = selectedIndices(labels, options.requestedChannels);
		if (options.operation != Operation.SIDE_BY_SIDE) {
			List<OutputChannel> result = new ArrayList<OutputChannel>();
			for (Integer index : selected) {
				String label = labels.get(index.intValue());
				result.add(OutputChannel.single(label, index.intValue(), label.endsWith("-left"), label.endsWith("-right")));
			}
			return result;
		}

		Map<String, int[]> pairs = new LinkedHashMap<String, int[]>();
		for (Integer index : selected) {
			String label = labels.get(index.intValue());
			String base = acquisitionBase(label);
			if (base == null) base = label;
			int[] pair = pairs.get(base);
			if (pair == null) { pair = new int[] { -1, -1 }; pairs.put(base, pair); }
			if (label.endsWith("-right")) pair[1] = index.intValue(); else pair[0] = index.intValue();
		}
		// A side-specific selection still means "show this acquisition whole": recover its mate.
		for (Map.Entry<String, int[]> entry : pairs.entrySet()) {
			for (int i = 0; i < labels.size(); i++) {
				if ((entry.getKey() + "-left").equals(labels.get(i))) entry.getValue()[0] = i;
				if ((entry.getKey() + "-right").equals(labels.get(i))) entry.getValue()[1] = i;
			}
		}
		List<OutputChannel> result = new ArrayList<OutputChannel>();
		for (Map.Entry<String, int[]> entry : pairs.entrySet())
			result.add(OutputChannel.pair(entry.getKey() + "-whole", entry.getValue()[0], entry.getValue()[1]));
		return result;
	}

	/** Apply a range after runtime composition, where the channel numbers shown by Fiji live. */
	private static List<OutputChannel> outputRange(List<OutputChannel> resolved, Options options) {
		if (resolved.isEmpty()) return resolved;
		int first = options.firstOutputChannel;
		if (first < 0 || first >= resolved.size())
			throw new IllegalArgumentException("First output channel " + (first + 1)
					+ " is outside 1-" + resolved.size() + ".");
		int count = options.outputChannelCount < 0
				? resolved.size() - first : options.outputChannelCount;
		if (count < 1 || first + count > resolved.size())
			throw new IllegalArgumentException("Output channel range " + (first + 1) + "-"
					+ (first + count) + " is outside 1-" + resolved.size() + ".");
		return new ArrayList<OutputChannel>(resolved.subList(first, first + count));
	}

	private static List<Integer> selectedIndices(List<String> labels, List<String> requested) {
		if (requested == null || requested.isEmpty()) {
			List<Integer> all = new ArrayList<Integer>();
			for (int i = 0; i < labels.size(); i++) all.add(Integer.valueOf(i));
			return all;
		}
		Set<Integer> result = new LinkedHashSet<Integer>();
		for (String wanted : requested) {
			for (int i = 0; i < labels.size(); i++) {
				String label = labels.get(i);
				if (label.equals(wanted) || wanted.equals(acquisitionBase(label))) result.add(Integer.valueOf(i));
			}
		}
		return new ArrayList<Integer>(result);
	}

	private static ImageProcessor concatenate(ImageProcessor left, ImageProcessor right) {
		int leftWidth = left.getWidth();
		int rightWidth = right.getWidth();
		int height = Math.max(left.getHeight(), right.getHeight());
		ShortProcessor result = new ShortProcessor(leftWidth + rightWidth, height);
		result.insert(left, 0, 0);
		result.insert(right, leftWidth, 0);
		return result;
	}

	private static final class OutputChannel {
		final String label;
		final int sourceIndex;
		final int leftIndex;
		final int rightIndex;
		final boolean left;
		final boolean right;
		final boolean sideBySide;
		private OutputChannel(String label, int sourceIndex, int leftIndex, int rightIndex,
				boolean left, boolean right, boolean sideBySide) {
			this.label = label;
			this.sourceIndex = sourceIndex;
			this.leftIndex = leftIndex;
			this.rightIndex = rightIndex;
			this.left = left;
			this.right = right;
			this.sideBySide = sideBySide;
		}
		static OutputChannel single(String label, int sourceIndex, boolean left, boolean right) {
			return new OutputChannel(label, sourceIndex, -1, -1, left, right, false);
		}
		static OutputChannel pair(String label, int leftIndex, int rightIndex) {
			return new OutputChannel(label, leftIndex >= 0 ? leftIndex : rightIndex,
					leftIndex, rightIndex, false, false, true);
		}
	}

	private static final class TransformRequest {
		final boolean flip;
		final double[][] alignment;
		TransformRequest(boolean flip, double[][] alignment) {
			this.flip = flip;
			this.alignment = alignment;
		}
	}
}
