package de.embl.iclm;

import ij.CompositeImage;
import ij.IJ;
import ij.ImageListener;
import ij.ImagePlus;
import ij.ImageStack;
import ij.VirtualStack;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import ij.process.LUT;
import ij.process.ShortProcessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Fiji-native virtual and materialised views of a canonical OPM OME-Zarr dataset. */
public final class OpmZarrView {

	public static final String SELECT_ALL = "all stored channels";
	/** Defer to the order chosen in the viewer's own channel setup dialog. */
	public static final String SELECT_CONFIGURED = "configured channel order";
	public static final String SELECT_LEFT = "all left sides";
	public static final String SELECT_RIGHT = "all right sides";

	public enum Operation {
		STORED_CHANNELS("stored channels (no runtime transform)"),
		SIDE_BY_SIDE("whole: left + right side by side"),
		FLIP_ONLY("flip only (side from metadata)"),
		FLIP_ALIGN_RIGHT("flip + align right onto left"),
		FLIP_ALIGN_LEFT("flip + align left onto right");

		private final String label;
		Operation(String label) { this.label = label; }
		@Override public String toString() { return label; }
	}

	/** Immutable-enough request object; callers may fill fields directly before opening. */
	public static final class Options {
		public Operation operation = Operation.STORED_CHANNELS;
		/** Empty means all channels. Entries use {@link ChannelOperationSettings#sourceKey}. */
		public final List<String> requestedChannels = new ArrayList<String>();
		public boolean interpolate = true;
		public boolean tryGpu = true;
		/** Optional flip-only override; null uses {@code opm.alignFlipHalf}. */
		public String flipHalf;

		public Options copy() {
			Options copy = new Options();
			copy.operation = operation;
			copy.requestedChannels.addAll(requestedChannels);
			copy.interpolate = interpolate;
			copy.tryGpu = tryGpu;
			copy.flipHalf = flipHalf;
			return copy;
		}
	}

	private OpmZarrView() {}

	/** Channel/acquisition/side choices suitable for the viewer control. */
	public static List<String> selectionOptions(OpmZarrDataset dataset) {
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
	public static List<String> channelsForSelection(OpmZarrDataset dataset, String selection,
			ChannelOperationSettings configuredOrder) {
		if (selection == null || SELECT_ALL.equals(selection)) return dataset.getChannelLabels();
		if (SELECT_CONFIGURED.equals(selection) && configuredOrder != null) {
			List<String> configured = new ArrayList<String>();
			for (String source : configuredOrder.channelOrder)
				if (!BatchChannelOperation.SKIP_CHANNEL.equals(source)
						&& dataset.getChannelLabels().contains(source)) configured.add(source);
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
	public static String[] channelSetupSources(OpmZarrDataset dataset) {
		List<String> labels = dataset.getChannelLabels();
		String[] sources = new String[labels.size() + 1];
		for (int i = 0; i < labels.size(); i++) sources[i] = labels.get(i);
		sources[labels.size()] = BatchChannelOperation.SKIP_CHANNEL;
		return sources;
	}

	/** How many output slots a setup dialog should draw: one per stored channel, up to the cap. */
	public static int channelSetupSlots(OpmZarrDataset dataset) {
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
			OpmZarrDataset dataset, ChannelOperationSettings configured) {
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
	public static List<String> channelsNotSelected(OpmZarrDataset dataset, String[] slots) {
		List<String> missing = new ArrayList<String>();
		for (String label : dataset.getChannelLabels()) {
			boolean used = false;
			if (slots != null) for (String slot : slots) if (label.equals(slot)) used = true;
			if (!used) missing.add(label);
		}
		return missing;
	}

	/** Open one T position ({@code timepoint >= 0}) or all T positions ({@code -1}) lazily. */
	public static ImagePlus openVirtualVolume(OpmZarrDataset dataset, Options options, int timepoint) {
		validateVolume(dataset);
		int firstT = timepoint < 0 ? 0 : checkedTimepoint(dataset, timepoint);
		int frames = timepoint < 0 ? dataset.getTimepointCount() : 1;
		ViewRenderer renderer = new ViewRenderer(dataset, options, "s0", false);
		try {
			OpmVirtualStack stack = new OpmVirtualStack(renderer, dataset.getDepth(), firstT, frames);
			ImagePlus base = new ImagePlus(title(dataset, timepoint < 0 ? "virtual 5D" : "virtual volume"), stack);
			base.setDimensions(renderer.outputCount(), dataset.getDepth(), frames);
			base.setOpenAsHyperStack(renderer.outputCount() > 1 || dataset.getDepth() > 1 || frames > 1);
			ImagePlus result = asComposite(base, renderer.outputCount());
			calibrateVolume(result, dataset);
			attachInfo(result, dataset, renderer.outputLabels());
			result.changes = false;
			closeRendererWhenImageCloses(result, renderer);
			return result;
		} catch (RuntimeException error) {
			renderer.close();
			throw error;
		}
	}

	/** Eager MIP movie; projection planes are small and playback should not block on I/O. */
	public static ImagePlus openProjectionMovie(OpmZarrDataset dataset, String projection, Options options) {
		if (!dataset.hasProjection(projection))
			throw new IllegalArgumentException("Projection is not present: " + projection);
		refuseImpossibleProjectionView(projection, options.operation);
		long[] dimensions = dataset.getProjectionDimensions(projection);
		if (dimensions.length != 4) throw new IllegalArgumentException("Invalid projection dimensions: " + projection);
		ViewRenderer renderer = new ViewRenderer(dataset, options, "projections/" + projection, true);
		try {
			int frames = (int) Math.min(dataset.getTimepointCount(), dimensions[3]);
			ImageStack stack = new ImageStack(renderer.outputWidth(), renderer.outputHeight());
			for (int t = 0; t < frames; t++) {
				for (int c = 0; c < renderer.outputCount(); c++) {
					stack.addSlice(renderer.outputLabel(c) + " / " + timeLabel(dataset, t),
							renderer.renderProjection(c, t));
				}
			}
			double spread = renderer.approximateSpreadPixels();
			String name = spread < 0 ? projection + " movie" : projection + " movie (rough overlay)";
			ImagePlus base = new ImagePlus(title(dataset, name), stack);
			base.setDimensions(renderer.outputCount(), 1, frames);
			base.setOpenAsHyperStack(renderer.outputCount() > 1 || frames > 1);
			ImagePlus result = asComposite(base, renderer.outputCount());
			calibrateProjection(result, dataset, projection);
			attachInfo(result, dataset, renderer.outputLabels());
			if (spread >= 0) {
				String warning = approximateProjectionWarning(dataset, projection, options.operation, spread);
				warn(warning);
				result.setProperty("Info", result.getProperty("Info") + "\n\n" + warning);
				result.setProperty("opm.approximateOverlay", "true");
			}
			result.changes = false;
			return result;
		} finally {
			renderer.close();
		}
	}

	/** Materialise one T ({@code >=0}) or all T ({@code -1}); GPU transforms one Z volume per call. */
	public static ImagePlus openMaterializedVolume(OpmZarrDataset dataset, Options options, int timepoint) {
		validateVolume(dataset);
		int firstT = timepoint < 0 ? 0 : checkedTimepoint(dataset, timepoint);
		int frames = timepoint < 0 ? dataset.getTimepointCount() : 1;
		ViewRenderer renderer = new ViewRenderer(dataset, options, "s0", false);
		try {
			ImageStack resultStack = new ImageStack(renderer.outputWidth(), renderer.outputHeight());
			for (int localT = 0; localT < frames; localT++) {
				int sourceT = firstT + localT;
				List<ImagePlus> channels = new ArrayList<ImagePlus>();
				try {
					for (int c = 0; c < renderer.outputCount(); c++)
						channels.add(renderer.materializeChannel(c, sourceT));
					for (int z = 1; z <= dataset.getDepth(); z++)
						for (ImagePlus channel : channels)
							resultStack.addSlice(channel.getTitle(), channel.getStack().getProcessor(z));
				} finally {
					for (ImagePlus channel : channels) {
						channel.changes = false;
						channel.close();
					}
				}
			}
			ImagePlus base = new ImagePlus(title(dataset, timepoint < 0 ? "materialised 5D" : "materialised volume"), resultStack);
			base.setDimensions(renderer.outputCount(), dataset.getDepth(), frames);
			base.setOpenAsHyperStack(renderer.outputCount() > 1 || dataset.getDepth() > 1 || frames > 1);
			ImagePlus result = asComposite(base, renderer.outputCount());
			calibrateVolume(result, dataset);
			attachInfo(result, dataset, renderer.outputLabels());
			result.changes = false;
			return result;
		} finally {
			renderer.close();
		}
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
			OpmZarrDataset dataset, String projection, Operation operation, double spreadPixels) {
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

	/** Estimated output allocation in bytes, before ImageJ object overhead. */
	public static long estimateMaterializedBytes(OpmZarrDataset dataset, Options options, int timepoint) {
		ViewRenderer renderer = new ViewRenderer(dataset, options, "s0", false);
		try {
			long frames = timepoint < 0 ? dataset.getTimepointCount() : 1;
			return saturatedMultiply(2L, renderer.outputWidth(), renderer.outputHeight(), dataset.getDepth(),
					renderer.outputCount(), frames);
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

	private static void validateVolume(OpmZarrDataset dataset) {
		if (dataset == null || !dataset.hasVolume()) throw new IllegalArgumentException("Dataset has no readable s0 volume.");
		if (dataset.getTimepointCount() < 1) throw new IllegalArgumentException("Dataset has no committed timepoints yet.");
	}

	private static int checkedTimepoint(OpmZarrDataset dataset, int timepoint) {
		if (timepoint < 0 || timepoint >= dataset.getTimepointCount())
			throw new IllegalArgumentException("Timepoint is outside the committed range: " + timepoint);
		return timepoint;
	}

	/**
	 * Refuse the runtime views a projection axis cannot express.
	 * <p>
	 * The rigid alignment acts in XY, so it needs both of those axes: only maxZ and meanZ keep
	 * them. Side by side needs X alone, because that is the axis the two camera halves are
	 * neighbours along; maxX and meanX have projected it away, and their width is Z, so
	 * concatenating there would butt two ZY views together instead of rebuilding the camera
	 * field. Both were reachable from the viewer, the second silently producing a doubled-Z
	 * image that looks plausible.
	 */
	private static void refuseImpossibleProjectionView(String projection, Operation operation) {
		String axis = projection == null ? "" : projection.toUpperCase(Locale.US);
		if (operation == Operation.SIDE_BY_SIDE && axis.endsWith("X"))
			throw new IllegalArgumentException("Left and right are neighbours along X, and " + projection
					+ " has projected X away; its width axis is Z. " + projection
					+ " can still be opened as stored channels.");
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

	private static String timeLabel(OpmZarrDataset dataset, int timepoint) {
		List<String> labels = dataset.getTimePointLabels();
		return timepoint >= 0 && timepoint < labels.size() ? labels.get(timepoint) : "t" + timepoint;
	}

	private static String title(OpmZarrDataset dataset, String suffix) {
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
	private static ImagePlus asComposite(ImagePlus image, int channels) {
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

	private static void calibrateVolume(ImagePlus image, OpmZarrDataset dataset) {
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

	private static void calibrateProjection(ImagePlus image, OpmZarrDataset dataset, String projection) {
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

	private static void attachInfo(ImagePlus image, OpmZarrDataset dataset, List<String> channels) {
		StringBuilder info = new StringBuilder();
		info.append("OME-Zarr: ").append(dataset.getRoot().getAbsolutePath()).append('\n');
		info.append("Channels: ").append(channels).append('\n');
		if (dataset.getProvenance() != null) info.append(dataset.getProvenance().toPrettyJson());
		if (!dataset.getWarnings().isEmpty()) info.append("\nWarnings: ").append(dataset.getWarnings());
		image.setProperty("Info", info.toString());
		image.setProperty("opm.channelLabels", channels.toString());
	}

	private static void closeRendererWhenImageCloses(final ImagePlus owner, final ViewRenderer renderer) {
		ImagePlus.addImageListener(new ImageListener() {
			@Override public void imageOpened(ImagePlus image) { }
			@Override public void imageUpdated(ImagePlus image) { }
			@Override public void imageClosed(ImagePlus image) {
				if (image != owner) return;
				renderer.close();
				ImagePlus.removeImageListener(this);
			}
		});
	}

	private static final class OpmVirtualStack extends VirtualStack {
		private final ViewRenderer renderer;
		private final int depth;
		private final int firstTimepoint;
		private final int frames;

		OpmVirtualStack(ViewRenderer renderer, int depth, int firstTimepoint, int frames) {
			super(renderer.outputWidth(), renderer.outputHeight());
			this.renderer = renderer;
			this.depth = depth;
			this.firstTimepoint = firstTimepoint;
			this.frames = frames;
			setBitDepth(16);
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

	private static final class ViewRenderer {
		private final OpmZarrDataset dataset;
		private final Options options;
		private final OpmZarrPlaneReader reader;
		private final List<OutputChannel> outputs;
		private final boolean projection;
		private final char projectionAxis;
		private boolean gpuUsable;
		private boolean gpuFailureLogged;

		ViewRenderer(OpmZarrDataset dataset, Options options, String path, boolean projection) {
			if (dataset == null) throw new IllegalArgumentException("Dataset is required.");
			this.dataset = dataset;
			this.options = options == null ? new Options() : options.copy();
			this.projection = projection;
			this.projectionAxis = projection ? Character.toUpperCase(path.charAt(path.length() - 1)) : 0;
			this.reader = new OpmZarrPlaneReader(dataset, path);
			this.outputs = resolveOutputs(dataset, this.options);
			if (outputs.isEmpty())
				throw new IllegalArgumentException("None of the requested channels "
						+ this.options.requestedChannels + " exist in this dataset, which stores "
						+ dataset.getChannelLabels() + ".");
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

		int outputCount() { return outputs.size(); }
		int outputWidth() { return options.operation == Operation.SIDE_BY_SIDE ? reader.width() * 2 : reader.width(); }
		int outputHeight() { return reader.height(); }
		String outputLabel(int index) { return outputs.get(index).label; }
		List<String> outputLabels() {
			List<String> labels = new ArrayList<String>();
			for (OutputChannel output : outputs) labels.add(output.label);
			return labels;
		}

		ImageProcessor renderVolume(int output, int z, int timepoint) {
			return render(outputs.get(output), new long[] { z, sourceIndex(outputs.get(output)), timepoint }, z, timepoint);
		}

		ImageProcessor renderProjection(int output, int timepoint) {
			return render(outputs.get(output), new long[] { sourceIndex(outputs.get(output)), timepoint }, 0, timepoint);
		}

		private ImageProcessor render(OutputChannel output, long[] coordinates, int z, int timepoint) {
			if (output.sideBySide) {
				ImageProcessor left = read(output.leftIndex, z, timepoint);
				ImageProcessor right = read(output.rightIndex, z, timepoint);
				return concatenate(left, right);
			}
			ImageProcessor source = reader.readPlane(coordinates);
			OrthogonalAlignment orthogonal = orthogonalFor(output);
			if (orthogonal != null) return orthogonal.apply(source, options.interpolate);
			TransformRequest transform = transformFor(output);
			if (!transform.flip) return source;
			if (gpuUsable) {
				try {
					return OpmRuntimeAlignment.transformPlaneGpu(source, transform.alignment, options.interpolate);
				} catch (Throwable error) {
					gpuUsable = false;
					logGpuFallback(error);
				}
			}
			return OpmRuntimeAlignment.transformPlane(source, transform.alignment, true, options.interpolate);
		}

		private ImageProcessor read(int sourceIndex, int z, int timepoint) {
			if (sourceIndex < 0) return new ShortProcessor(reader.width(), reader.height());
			return projection ? reader.readPlane(sourceIndex, timepoint) : reader.readPlane(z, sourceIndex, timepoint);
		}

		ImagePlus materializeChannel(int outputIndex, int timepoint) {
			OutputChannel output = outputs.get(outputIndex);
			if (output.sideBySide) {
				ImageStack stack = new ImageStack(outputWidth(), outputHeight());
				for (int z = 0; z < dataset.getDepth(); z++)
					stack.addSlice(concatenate(read(output.leftIndex, z, timepoint), read(output.rightIndex, z, timepoint)));
				return new ImagePlus(output.label, stack);
			}
			ImageStack rawStack = new ImageStack(reader.width(), reader.height());
			for (int z = 0; z < dataset.getDepth(); z++) rawStack.addSlice(read(output.sourceIndex, z, timepoint));
			ImagePlus raw = new ImagePlus(output.label, rawStack);
			TransformRequest transform = transformFor(output);
			if (!transform.flip) return raw;
			if (gpuUsable) {
				try {
					ImagePlus transformed = OpmRuntimeAlignment.transformVolumeGpu(raw, transform.alignment, options.interpolate);
					raw.changes = false;
					raw.close();
					return transformed;
				} catch (Throwable error) {
					gpuUsable = false;
					logGpuFallback(error);
				}
			}
			ImagePlus transformed = OpmRuntimeAlignment.transformVolumeCpu(raw, transform.alignment, options.interpolate);
			raw.changes = false;
			raw.close();
			return transformed;
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
			if (provenance == null || provenance.alignApplied || provenance.alignMatrix == null) return null;
			boolean transforms = options.operation == Operation.FLIP_ALIGN_RIGHT ? output.right : output.left;
			if (!transforms) return null;
			return output.left
					? Transform.mirrorAlignmentMatrix2D(provenance.alignMatrix, dataset.getWidth())
					: provenance.alignMatrix;
		}

		/** The approximate plane transform for this output, or null when the exact path applies. */
		private OrthogonalAlignment orthogonalFor(OutputChannel output) {
			if (!projection || projectionAxis == 'Z') return null;
			return orthogonalAlignment(alignmentMatrixFor(output), projectionAxis,
					dataset.getWidth(), dataset.getHeight());
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

	private static List<OutputChannel> resolveOutputs(OpmZarrDataset dataset, Options options) {
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
