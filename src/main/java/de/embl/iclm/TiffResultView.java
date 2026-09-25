package de.embl.iclm;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.VirtualStack;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

/**
 * Fiji views of a deflated-TIFF deskew result: virtual, growing, and croppable.
 *
 * <p>The counterpart of {@link OmeZarrView} for {@link TiffResultDataset}, and deliberately a
 * much smaller class. A composed TIFF result needs no runtime composition, but a run that
 * wrote one whole-width {@code _ChannelNNNN} series per acquisition channel still preserves
 * its camera halves. {@link VirtualChannel} can expose those halves as a non-destructive,
 * per-viewer overlay, optionally mirroring and rigidly aligning them while each plane is read.
 *
 * <p>One stack class serves both the volume and a projection movie. A projection TIFF is a
 * volume whose Z is 1, so the only difference is the dimensions handed to the
 * {@link ImagePlus}; giving them separate implementations would mean fixing every bug twice,
 * which is the mistake the second OME-Zarr viewer made.
 *
 * <p>Cropping happens on the way out of the reader, not after the plane is built, and the
 * reader is asked for the rows of the crop only. On a TIFF written by this toolset - one
 * Deflate strip per plane - that saves the rows below the region but not the rows above it,
 * because a Deflate stream has to be decoded to reach them. The saving that does scale is in
 * planes: a region over 40 Z of 212, one channel of four, ten time points of fifty, reads
 * exactly those planes and no others.
 */
public final class TiffResultView {

	private TiffResultView() { }

	/** What to show of a dataset. The region is in stored coordinates; for TIFF there is no other kind. */
	public static final class Options {
		public boolean virtual = true;
		public OmeZarrView.Bounds bounds;
		public int firstOutputChannel = 0;
		public int outputChannelCount = -1;
		/** Empty keeps the channels exactly as written in the selected TIFF series. */
		public final List<VirtualChannel> virtualChannels = new ArrayList<VirtualChannel>();
		/** Sampling used by a runtime rigid transform. */
		public boolean interpolate = true;

		public Options copy() {
			Options copy = new Options();
			copy.virtual = virtual;
			copy.bounds = bounds == null ? null : bounds.copy();
			copy.firstOutputChannel = firstOutputChannel;
			copy.outputChannelCount = outputChannelCount;
			copy.virtualChannels.addAll(virtualChannels);
			copy.interpolate = interpolate;
			return copy;
		}
	}

	/** The horizontal part of a whole-width TIFF plane used as one virtual output channel. */
	public enum HorizontalPart { WHOLE, LEFT, RIGHT }

	/**
	 * One runtime-only output channel.
	 *
	 * <p>The dataset identifies the {@code _ChannelNNNN} file series; the label identifies the
	 * optical source and is also what a tagged alignment matrix addresses. The source TIFF must
	 * contain one stored channel. That restriction avoids inventing an ambiguous mapping between
	 * an already-composed hyperstack and acquisition-channel names.
	 */
	public static final class VirtualChannel {
		public final TiffResultDataset dataset;
		public final String label;
		public final HorizontalPart part;
		public final AlignmentMatrixSet alignment;
		public final boolean flipLeft;
		/**
		 * Whether the half is mirrored (and aligned) at all, or taken exactly as stored.
		 * <p>
		 * A half is normally mirrored onto the other's frame, which is what makes two halves one
		 * result - {@link AlignmentMatrixSet#placement} mirrors whichever side the flip names
		 * even with no matrix. Cleared, this is the {@code stored halves as separate channels}
		 * view: the two halves side by side as the camera saw them, no transform of any kind.
		 * Without it the viewer's {@code Runtime view} would have no way to express that for a
		 * TIFF, although it is the plainest reading of one.
		 */
		public final boolean transform;

		public VirtualChannel(TiffResultDataset dataset, String label, HorizontalPart part,
				AlignmentMatrixSet alignment, boolean flipLeft) {
			this(dataset, label, part, alignment, flipLeft, true);
		}

		public VirtualChannel(TiffResultDataset dataset, String label, HorizontalPart part,
				AlignmentMatrixSet alignment, boolean flipLeft, boolean transform) {
			if (dataset == null) throw new IllegalArgumentException("A TIFF source dataset is required.");
			if (label == null || label.trim().isEmpty())
				throw new IllegalArgumentException("A TIFF virtual-channel label is required.");
			this.dataset = dataset;
			this.label = label;
			this.part = part == null ? HorizontalPart.WHOLE : part;
			this.alignment = alignment;
			this.flipLeft = flipLeft;
			this.transform = transform;
		}

		AlignmentMatrixSet.Placement placement(int width) {
			return transform ? AlignmentMatrixSet.placement(alignment, label, flipLeft, width)
					: new AlignmentMatrixSet.Placement(false, null);
		}

		String signature() {
			StringBuilder value = new StringBuilder(label).append('@')
					.append(dataset.getDisplayName()).append(':').append(part).append(':')
					.append(flipLeft).append(':').append(transform ? "transformed" : "as-stored");
			if (alignment != null) {
				value.append(':').append(alignment.reference());
				for (String source : alignment.sources()) value.append(':').append(source).append('=')
						.append(java.util.Arrays.deepToString(alignment.matrixFor(source)));
			}
			return value.toString();
		}
	}

	/**
	 * The plane grid a set of options selects out of a view, resolved once and then trusted.
	 *
	 * <p>Everything downstream - the virtual stack, the materialiser, the size estimate - works
	 * from this, so a region that reached past an edge or a channel range the file cannot
	 * satisfy is caught in one place instead of four.
	 */
	static final class Selection {
		final TiffResultDataset.View view;
		final TiffResultDataset.Layout layout;
		final List<OutputChannel> outputs;
		final boolean interpolate;
		final int x;
		final int y;
		final int width;
		final int height;
		final int firstZ;
		final int slices;
		final int channels;

		Selection(TiffResultDataset.View view, TiffResultDataset.Layout layout, Options options)
				throws IOException {
			this.view = view;
			this.layout = layout;
			this.interpolate = options.interpolate;
			List<OutputChannel> available = new ArrayList<OutputChannel>();
			if (options.virtualChannels.isEmpty()) {
				for (int channel = 0; channel < layout.channels; channel++)
					available.add(OutputChannel.stored(view, layout, channel));
			} else {
				if (!view.isVolume() && !view.key.toLowerCase(Locale.ROOT).endsWith("z"))
					throw new IOException("Virtual TIFF channel composition is exact for deskewed volumes "
							+ "and Z projections. Open " + view.key + " as written instead.");
				for (VirtualChannel source : options.virtualChannels)
					available.add(OutputChannel.virtual(source, view.key));
			}
			if (available.isEmpty()) throw new IOException("No TIFF output channel was selected.");
			int first = options.firstOutputChannel;
			int count = options.outputChannelCount < 0
					? available.size() - first : options.outputChannelCount;
			if (first < 0 || first >= available.size() || count < 1 || first + count > available.size())
				throw new IOException("Channel range " + (first + 1) + "-" + (first + count)
						+ " is outside 1-" + available.size() + ".");
			this.outputs = new ArrayList<OutputChannel>(available.subList(first, first + count));

			OutputChannel reference = outputs.get(0);
			for (OutputChannel output : outputs) output.requireCompatible(reference);
			OmeZarrView.Bounds box = options.bounds == null
					? OmeZarrView.Bounds.full(reference.width, reference.layout.height,
							reference.layout.slices)
					: options.bounds.clampedTo(reference.width, reference.layout.height,
							reference.layout.slices);
			this.x = box.x;
			this.y = box.y;
			this.width = box.width;
			this.height = box.height;
			this.firstZ = box.zStart;
			this.slices = Math.max(1, box.depth());
			this.channels = count;
		}

		int planesPerTimepoint() { return channels * slices; }

		/** The plane in the source file that output (channel, z) reads. */
		int filePlane(int channel, int z) {
			OutputChannel output = outputs.get(channel);
			return (firstZ + z) * output.layout.channels + output.storedChannel;
		}

		boolean isWholePlane() {
			OutputChannel output = outputs.get(0);
			return x == 0 && y == 0 && width == output.width && height == output.layout.height;
		}

		List<Timepoint> timepoints() {
			List<Map<Long, TiffResultDataset.Frame>> indexed =
					new ArrayList<Map<Long, TiffResultDataset.Frame>>();
			for (OutputChannel output : outputs) indexed.add(index(output.view));
			List<Timepoint> result = new ArrayList<Timepoint>();
			for (Map.Entry<Long, TiffResultDataset.Frame> first : indexed.get(0).entrySet()) {
				List<TiffResultDataset.Frame> frames = new ArrayList<TiffResultDataset.Frame>();
				frames.add(first.getValue());
				boolean complete = true;
				for (int channel = 1; channel < indexed.size(); channel++) {
					TiffResultDataset.Frame frame = indexed.get(channel).get(first.getKey());
					if (frame == null) { complete = false; break; }
					frames.add(frame);
				}
				if (complete) result.add(new Timepoint(first.getValue().timeNumber, frames));
			}
			return result;
		}

		int refreshDatasets() {
			int added = 0;
			Set<TiffResultDataset> datasets = new LinkedHashSet<TiffResultDataset>();
			for (OutputChannel output : outputs)
				if (output.dataset != null) datasets.add(output.dataset);
			for (TiffResultDataset dataset : datasets) added += dataset.refresh();
			return added;
		}

		List<String> labels() {
			List<String> labels = new ArrayList<String>();
			for (OutputChannel output : outputs) labels.add(output.label);
			return labels;
		}

		String signature() {
			StringBuilder value = new StringBuilder();
			for (OutputChannel output : outputs) value.append(output.signature()).append('|');
			return value.append(interpolate).toString();
		}
	}

	/** One resolved output channel, against the view currently being opened. */
	static final class OutputChannel {
		final TiffResultDataset dataset;
		final TiffResultDataset.View view;
		final TiffResultDataset.Layout layout;
		final int storedChannel;
		final String label;
		final HorizontalPart part;
		final int sourceX;
		final int width;
		final AlignmentMatrixSet.Placement placement;
		final String sourceSignature;

		private OutputChannel(TiffResultDataset dataset, TiffResultDataset.View view,
				TiffResultDataset.Layout layout, int storedChannel, String label,
				HorizontalPart part, AlignmentMatrixSet.Placement placement, String signature)
				throws IOException {
			this.dataset = dataset;
			this.view = view;
			this.layout = layout;
			this.storedChannel = storedChannel;
			this.label = label;
			this.part = part;
			this.width = part == HorizontalPart.WHOLE ? layout.width : layout.width / 2;
			if (width < 1 || (part != HorizontalPart.WHOLE && (layout.width & 1) != 0))
				throw new IOException("Cannot split odd TIFF width " + layout.width + " for " + label + ".");
			this.sourceX = part == HorizontalPart.RIGHT ? layout.width - width : 0;
			this.placement = placement;
			this.sourceSignature = signature;
		}

		static OutputChannel stored(TiffResultDataset.View view, TiffResultDataset.Layout layout,
				int channel) throws IOException {
			return new OutputChannel(null, view, layout, channel, "C" + (channel + 1),
					HorizontalPart.WHOLE, new AlignmentMatrixSet.Placement(false, null),
					"stored-C" + (channel + 1));
		}

		static OutputChannel virtual(VirtualChannel source, String viewKey) throws IOException {
			TiffResultDataset.View view = source.dataset.getView(viewKey);
			if (view == null) throw new IOException(source.dataset.getDisplayName()
					+ " has no " + viewKey + " view for " + source.label + ".");
			TiffResultDataset.Layout layout = view.getLayout();
			if (layout.channels != 1)
				throw new IOException(source.dataset.getDisplayName() + " stores C=" + layout.channels
						+ "; a virtual _ChannelNNNN source must be one stored channel."
						+ " Open this TIFF as written instead.");
			int width = source.part == HorizontalPart.WHOLE ? layout.width : layout.width / 2;
			return new OutputChannel(source.dataset, view, layout, 0, source.label, source.part,
					source.placement(width), source.signature());
		}

		void requireCompatible(OutputChannel other) throws IOException {
			if (width != other.width || layout.height != other.layout.height
					|| layout.slices != other.layout.slices)
				throw new IOException("TIFF virtual channels must have the same output XYZ extent; "
						+ other.label + " is " + other.width + "x" + other.layout.height + "x"
						+ other.layout.slices + ", but " + label + " is " + width + "x"
						+ layout.height + "x" + layout.slices + ".");
			if (!close(layout.pixelWidth, other.layout.pixelWidth)
					|| !close(layout.pixelHeight, other.layout.pixelHeight)
					|| !close(layout.pixelDepth, other.layout.pixelDepth))
				throw new IOException("TIFF virtual channels must have matching voxel calibration; "
						+ other.label + " and " + label + " differ.");
		}

		private static boolean close(double a, double b) {
			return Math.abs(a - b) <= 1e-9 * Math.max(1.0d, Math.max(Math.abs(a), Math.abs(b)));
		}

		String signature() { return sourceSignature + ':' + part + ':' + storedChannel; }
	}

	static final class Timepoint {
		final long number;
		final List<TiffResultDataset.Frame> frames;
		Timepoint(long number, List<TiffResultDataset.Frame> frames) {
			this.number = number;
			this.frames = frames;
		}
	}

	private static Map<Long, TiffResultDataset.Frame> index(TiffResultDataset.View view) {
		Map<Long, TiffResultDataset.Frame> indexed = new TreeMap<Long, TiffResultDataset.Frame>();
		int ordinal = 0;
		for (TiffResultDataset.Frame frame : view.getFrames()) {
			long key = frame.timeNumber > 0 ? frame.timeNumber : Long.MIN_VALUE + ordinal;
			indexed.put(Long.valueOf(key), frame);
			ordinal++;
		}
		return indexed;
	}

	// ---- opening ---------------------------------------------------------------------

	/** The size of the view these options produce: {width, height, depth, channels}. */
	public static int[] viewExtent(TiffResultDataset dataset, String viewKey, Options options)
			throws IOException {
		TiffResultDataset.View view = requireView(dataset, viewKey);
		Selection selection = new Selection(view, view.getLayout(), unbounded(options));
		OutputChannel first = selection.outputs.get(0);
		return new int[] { first.width, first.layout.height,
				first.layout.slices, selection.channels };
	}

	/** Output labels after an optional virtual-channel setup and channel range. */
	public static List<String> outputChannelLabels(TiffResultDataset dataset, String viewKey,
			Options options) throws IOException {
		TiffResultDataset.View view = requireView(dataset, viewKey);
		return new Selection(view, view.getLayout(), options).labels();
	}

	/** Complete time points shared by every selected virtual source. */
	public static int availableFrameCount(TiffResultDataset dataset, String viewKey, Options options)
			throws IOException {
		TiffResultDataset.View view = requireView(dataset, viewKey);
		return new Selection(view, view.getLayout(), options).timepoints().size();
	}

	/** Refresh the selected TIFF series, returning the number of newly discovered files. */
	public static int refreshSources(TiffResultDataset dataset, String viewKey, Options options)
			throws IOException {
		TiffResultDataset.View view = requireView(dataset, viewKey);
		Selection selection = new Selection(view, view.getLayout(), options);
		int added = dataset.refresh();
		for (OutputChannel output : selection.outputs)
			if (output.dataset != null && output.dataset != dataset) added += output.dataset.refresh();
		return added;
	}

	/** Stable description used to decide whether an open TIFF view needs rebuilding. */
	static String compositionOf(Options options) {
		if (options == null || options.virtualChannels.isEmpty()) return "TIFF-as-written";
		StringBuilder value = new StringBuilder("TIFF-virtual|");
		for (VirtualChannel channel : options.virtualChannels)
			value.append(channel.signature()).append('|');
		return value.append(options.interpolate).append('|').append(options.firstOutputChannel)
				.append('|').append(options.outputChannelCount).toString();
	}

	/** A growing virtual view of one TIFF view, with the region and ranges already applied. */
	public static ImagePlus openVirtual(TiffResultDataset dataset, String viewKey, Options options)
			throws IOException {
		return openVirtual(dataset, viewKey, options, -1);
	}

	/**
	 * A virtual view, either of the whole run or of one time point.
	 *
	 * @param timepoint	: 0-based time point to pin to, or -1 for every one written and growing
	 */
	public static ImagePlus openVirtual(TiffResultDataset dataset, String viewKey, Options options,
			int timepoint) throws IOException {
		TiffResultDataset.View view = requireView(dataset, viewKey);
		Selection selection = new Selection(view, view.getLayout(), options);
		int available = selection.timepoints().size();
		if (available < 1)
			throw new IOException("No complete time points written yet for the selected TIFF channels.");
		if (timepoint >= available)
			throw new IOException("Time point " + (timepoint + 1) + " is outside 1-"
					+ available + ".");
		GrowingTiffStack stack = new GrowingTiffStack(selection, cacheFor(dataset), timepoint);
		ImagePlus image = new ImagePlus(title(dataset, viewKey, selection, true), stack);
		image.setDimensions(selection.channels, selection.slices, stack.frameCount());
		calibrate(image, selection, viewKey);
		attachInfo(image, dataset, viewKey, selection);
		/* As a hyperstack, always. Without it a single-channel result - a whole-image run - opened
		 * as a plain stack with one slider over every plane of every time point. */
		ImagePlus shown = asComposite(image, selection.channels);
		attachInfo(shown, dataset, viewKey, selection);
		shown.setOpenAsHyperStack(shown.getStackSize() > 1);
		return shown;
	}

	/**
	 * One TIFF view as a plane source, for writing a region straight to disk.
	 *
	 * <p>The virtual view is the reader: it applies the region and the channel range, and opens
	 * only the planes asked for - which is what makes an export cost one plane of memory rather
	 * than the whole region. Nothing is shown; the caller closes it.
	 */
	static RegionPlanes regionPlanes(TiffResultDataset dataset, String viewKey, Options options)
			throws IOException {
		return new RegionPlanes(openVirtual(dataset, viewKey, options));
	}

	/** A TIFF view's planes, one at a time; see {@link #regionPlanes}. */
	static final class RegionPlanes implements RegionExport.Planes, java.io.Closeable {
		private final ImagePlus image;

		private RegionPlanes(ImagePlus image) { this.image = image; }

		@Override public ImageProcessor plane(int channel, int z, int timepoint) {
			return image.getStack().getProcessor(image.getStackIndex(channel + 1, z + 1, timepoint + 1));
		}

		int width() { return image.getWidth(); }
		int height() { return image.getHeight(); }
		int depth() { return Math.max(1, image.getNSlices()); }
		int channels() { return Math.max(1, image.getNChannels()); }
		int frames() { return Math.max(1, image.getNFrames()); }
		double pixelSizeUm() { return image.getCalibration().pixelWidth; }
		double voxelDepthUm() { return image.getCalibration().pixelDepth; }
		double frameIntervalSeconds() { return image.getCalibration().frameInterval; }

		@Override public void close() {
			image.changes = false;		// never shown, so flush is the whole of it
			image.flush();
		}
	}

	/** The same view, read into memory for a range of time points. */
	public static ImagePlus openMaterialised(TiffResultDataset dataset, String viewKey,
			Options options, int firstTimepoint, int frames) throws IOException {
		TiffResultDataset.View view = requireView(dataset, viewKey);
		Selection selection = new Selection(view, view.getLayout(), options);
		List<Timepoint> all = selection.timepoints();
		if (firstTimepoint < 0 || frames < 1 || firstTimepoint + frames > all.size())
			throw new IOException("Time point range " + (firstTimepoint + 1) + "-"
					+ (firstTimepoint + frames) + " is outside 1-" + all.size() + ".");
		List<Timepoint> wanted =
				new ArrayList<Timepoint>(all.subList(firstTimepoint, firstTimepoint + frames));

		short[][] planes = readPlanes(selection, wanted);
		ImageStack stack = new ImageStack(selection.width, selection.height);
		int perTimepoint = selection.planesPerTimepoint();
		for (int t = 0; t < wanted.size(); t++)
			for (int z = 0; z < selection.slices; z++)
				for (int c = 0; c < selection.channels; c++) {
					int index = t * perTimepoint + z * selection.channels + c;
					stack.addSlice(label(selection, c, z, wanted.get(t)),
							new ShortProcessor(selection.width, selection.height, planes[index], null));
				}
		ImagePlus image = new ImagePlus(title(dataset, viewKey, selection, false), stack);
		image.setDimensions(selection.channels, selection.slices, wanted.size());
		calibrate(image, selection, viewKey);
		attachInfo(image, dataset, viewKey, selection);
		/* As a hyperstack, always. Without it a single-channel result - a whole-image run - opened
		 * as a plain stack with one slider over every plane of every time point. */
		ImagePlus shown = asComposite(image, selection.channels);
		attachInfo(shown, dataset, viewKey, selection);
		shown.setOpenAsHyperStack(shown.getStackSize() > 1);
		return shown;
	}

	/**
	 * How many bytes {@link #openMaterialised} would allocate for one time point.
	 *
	 * <p>Exact rather than estimated: the pixels are 16 bit and the plane count is known, so
	 * the figure a confirmation dialog quotes is the figure that will be allocated.
	 */
	public static long estimateMaterialisedBytes(TiffResultDataset dataset, String viewKey,
			Options options) throws IOException {
		TiffResultDataset.View view = requireView(dataset, viewKey);
		Selection selection = new Selection(view, view.getLayout(), options);
		return 2L * selection.width * selection.height * selection.planesPerTimepoint();
	}

	/**
	 * Take on the time points that have appeared since a virtual view was opened.
	 *
	 * @return	: the number of time points the view now has, or -1 if it is not one of ours
	 */
	public static int growVirtualView(ImagePlus image, TiffResultDataset dataset) {
		return growVirtualView(image, dataset, true);
	}

	/**
	 * The same, optionally without re-reading the folder first.
	 * <p>
	 * A viewer following a run has already refreshed the dataset once for the tick; walking the
	 * result folder again for every open view only repeats that.
	 *
	 * @param refresh			: re-scan the result folder before growing
	 */
	static int growVirtualView(ImagePlus image, TiffResultDataset dataset, boolean refresh) {
		if (image == null || dataset == null) return -1;
		ImageStack stack = image.getStack();
		if (!(stack instanceof GrowingTiffStack)) return -1;

		/* Read the layout before growing anything: ImagePlus.verifyDimensions collapses a
		 * hyperstack to one channel the moment the stack is longer than C*Z*T. */
		final int channels = image.getNChannels();
		final int slices = image.getNSlices();
		final int before = image.getNFrames();

		GrowingTiffStack growing = (GrowingTiffStack) stack;
		if (refresh) {
			dataset.refresh();
			growing.selection.refreshDatasets();
		}
		int frames = growing.grow();
		if (frames <= before) return before;

		OmeZarrView.showGrownTimeAxis(image, channels, slices, frames);
		return frames;
	}

	/** Whether this image is a TIFF view of this dataset, and so can be grown or re-read. */
	public static boolean isViewOf(ImagePlus image, TiffResultDataset dataset) {
		if (image == null || dataset == null) return false;
		ImageStack stack = image.getStack();
		if (!(stack instanceof GrowingTiffStack)) return false;
		File folder = ((GrowingTiffStack) stack).selection.view.folder;
		File root = dataset.getRoot();
		return folder != null && root != null
				&& folder.getAbsolutePath().startsWith(root.getAbsolutePath());
	}

	// ---- reading ---------------------------------------------------------------------

	/**
	 * Read every selected plane of every selected time point, in parallel over the files.
	 *
	 * <p>One {@link FastTiffReader.PlaneReader} per lane, each keeping its own file channel,
	 * inflater and scratch buffer: a reader is not safe to share and a new one per plane would
	 * re-open the file for every Z. Lanes are spread over the time points first, because those
	 * are separate files and therefore separate reads.
	 */
	private static short[][] readPlanes(final Selection selection,
			final List<Timepoint> frames) throws IOException {
		final int perTimepoint = selection.planesPerTimepoint();
		int total = Math.multiplyExact(perTimepoint, frames.size());
		final short[][] output = new short[total][];
		int workers = Math.max(1, Math.min(FastTiffReader.logicalProcessorCount(), total));

		ExecutorService pool = Executors.newFixedThreadPool(workers,
				Shutdown.daemonThreads("OPM-tiff-view"));
		final AtomicInteger done = new AtomicInteger();
		final int totalPlanes = total;
		List<Future<Void>> jobs = new ArrayList<Future<Void>>();
		try {
			for (int t = 0; t < frames.size(); t++) {
				final int timeIndex = t;
				final Timepoint timepoint = frames.get(t);
				for (int channel = 0; channel < selection.channels; channel++) {
					final int outputChannel = channel;
					jobs.add(pool.submit(new Callable<Void>() {
						@Override public Void call() throws Exception {
							TiffResultDataset.Frame frame = timepoint.frames.get(outputChannel);
							FastTiffReader.PlaneReader reader =
									new FastTiffReader.PlaneReader(frame.file);
							try {
								for (int z = 0; z < selection.slices; z++) {
									if (Shutdown.stopping() || Thread.currentThread().isInterrupted())
										throw new IOException("Cancelled.");
									int local = z * selection.channels + outputChannel;
									output[timeIndex * perTimepoint + local] =
											read(reader, selection, outputChannel, z);
									int finished = done.incrementAndGet();
									if ((finished & 31) == 0 || finished == totalPlanes) {
										IJ.showProgress(finished, totalPlanes);
										IJ.showStatus("Reading TIFF planes: " + finished + "/"
												+ totalPlanes);
									}
								}
							} finally { reader.close(); }
							return null;
						}
					}));
				}
			}
			for (Future<Void> job : jobs) {
				try { job.get(); }
				catch (InterruptedException stopped) {
					Thread.currentThread().interrupt();
					throw new IOException("Cancelled.");
				} catch (ExecutionException failure) {
					Throwable cause = failure.getCause();
					if (cause instanceof IOException) throw (IOException) cause;
					throw new IOException("Could not read the TIFF planes: "
							+ (cause == null ? failure.toString() : cause.toString()), cause);
				}
			}
		} finally {
			pool.shutdownNow();
			IJ.showProgress(1.0d);
		}
		return output;
	}

	/** One output plane: the rows of the region, cropped to its columns. */
	private static short[] read(FastTiffReader.PlaneReader reader, Selection selection,
			int channel, int z) throws IOException {
		OutputChannel output = selection.outputs.get(channel);
		int plane = selection.filePlane(channel, z);
		boolean transformed = output.placement.mirror || output.placement.matrix != null;
		if (!transformed) {
			if (selection.isWholePlane() && output.part == HorizontalPart.WHOLE)
				return reader.readPixels(plane);
			short[] band = reader.readRows(plane, selection.y, selection.height);
			if (selection.width == output.layout.width && output.sourceX == 0) return band;
			short[] cropped = new short[Math.multiplyExact(selection.width, selection.height)];
			int sourceX = output.sourceX + selection.x;
			for (int row = 0; row < selection.height; row++)
				System.arraycopy(band, row * output.layout.width + sourceX,
						cropped, row * selection.width, selection.width);
			return cropped;
		}

		/* A rigid transform can draw from anywhere in the source half, so it must see the whole
		 * half before the output region is cropped. This remains non-destructive: only the plane
		 * returned to ImageJ is transformed; the TIFF bytes are never touched. */
		short[] whole = reader.readPixels(plane);
		short[] source = new short[Math.multiplyExact(output.width, output.layout.height)];
		for (int row = 0; row < output.layout.height; row++)
			System.arraycopy(whole, row * output.layout.width + output.sourceX,
					source, row * output.width, output.width);
		ImageProcessor original = new ShortProcessor(output.width, output.layout.height, source, null);
		ImageProcessor placed = output.placement.mirror
				? OpmRuntimeAlignment.transformPlane(original, output.placement.matrix, true,
						selection.interpolate)
				: OpmRuntimeAlignment.transformPlaneWithoutFlip(original, output.placement.matrix,
						selection.interpolate);
		short[] cropped = new short[Math.multiplyExact(selection.width, selection.height)];
		for (int row = 0; row < selection.height; row++)
			for (int column = 0; column < selection.width; column++)
				cropped[row * selection.width + column] =
						(short) placed.get(selection.x + column, selection.y + row);
		return cropped;
	}

	// ---- the growing stack -----------------------------------------------------------

	/**
	 * A C-fastest, then Z, then T virtual stack over one TIFF per time point.
	 *
	 * <p>The list of time points grows in place, so a view opened during an acquisition keeps
	 * every index it has already handed out. Pixels come back cloned, because an ImageJ command
	 * that writes into the processor it was given would otherwise corrupt the cache.
	 */
	static final class GrowingTiffStack extends VirtualStack {
		final Selection selection;
		private final PlaneCache cache;
		private final List<Timepoint> frames;
		private final Set<String> reported = new HashSet<String>();

		/** -1 to follow the view as it grows, or the one time point this view is pinned to. */
		private final int pinned;

		GrowingTiffStack(Selection selection, PlaneCache cache, int pinned) {
			super(selection.width, selection.height);
			this.selection = selection;
			this.cache = cache;
			this.pinned = pinned;
			List<Timepoint> all = selection.timepoints();
			this.frames = pinned < 0 ? all
					: new ArrayList<Timepoint>(all.subList(pinned, pinned + 1));
			setBitDepth(16);
		}

		/**
		 * Take on whatever the dataset has found since, and report the new length.
		 *
		 * <p>A view pinned to one time point does not grow, and saying so here rather than at
		 * the call site is what keeps a pinned view out of the live extension by construction.
		 */
		synchronized int grow() {
			if (pinned >= 0) return frames.size();
			List<Timepoint> current = selection.timepoints();
			if (current.size() > frames.size()) {
				frames.clear();
				frames.addAll(current);
			}
			return frames.size();
		}

		synchronized int frameCount() { return frames.size(); }

		@Override public synchronized int size() { return getSize(); }

		@Override public synchronized int getSize() {
			return selection.planesPerTimepoint() * frames.size();
		}

		@Override public int getBitDepth() { return 16; }

		@Override public ImageProcessor getProcessor(int index) {
			TiffResultDataset.Frame frame;
			int channel;
			int z;
			synchronized (this) {
				if (index < 1 || index > getSize())
					throw new IllegalArgumentException("Plane out of range: " + index);
				int zero = index - 1;
				int perTimepoint = selection.planesPerTimepoint();
				Timepoint timepoint = frames.get(zero / perTimepoint);
				int local = zero % perTimepoint;
				channel = local % selection.channels;
				z = local / selection.channels;
				frame = timepoint.frames.get(channel);
			}
			String key = frame.file.getAbsolutePath().toLowerCase(Locale.ROOT) + '#'
					+ selection.filePlane(channel, z) + '#' + selection.x + ',' + selection.y
					+ ',' + selection.width + 'x' + selection.height + '#'
					+ selection.outputs.get(channel).signature() + '#' + selection.interpolate;
			short[] pixels = cache.get(key);
			if (pixels == null) {
				FastTiffReader.PlaneReader reader = null;
				try {
					reader = new FastTiffReader.PlaneReader(frame.file);
					pixels = read(reader, selection, channel, z);
					cache.put(key, pixels);
				} catch (Throwable unreadable) {
					synchronized (reported) {
						if (reported.add(key))
							IJ.log("OPM TIFF view could not read " + frame.file + ", C="
									+ (channel + 1) + ", Z=" + (z + 1) + ": " + unreadable);
					}
					return new ShortProcessor(selection.width, selection.height);
				} finally {
					if (reader != null) try { reader.close(); } catch (IOException ignored) { }
				}
			}
			return new ShortProcessor(selection.width, selection.height,
					Arrays.copyOf(pixels, pixels.length), null);
		}

		@Override public String getSliceLabel(int index) {
			synchronized (this) {
				if (index < 1 || index > getSize()) return null;
				int zero = index - 1;
				int perTimepoint = selection.planesPerTimepoint();
				Timepoint frame = frames.get(zero / perTimepoint);
				int local = zero % perTimepoint;
				return label(selection, local % selection.channels,
						local / selection.channels, frame);
			}
		}

		@Override public String getFileName(int index) {
			synchronized (this) {
				if (index < 1 || index > getSize()) return null;
				int zero = index - 1;
				int local = zero % selection.planesPerTimepoint();
				int channel = local % selection.channels;
				return frames.get(zero / selection.planesPerTimepoint()).frames.get(channel).file.getName();
			}
		}
	}

	/**
	 * A byte-budgeted cache of decoded planes, one per dataset root.
	 *
	 * <p>Budgeted in bytes rather than in planes because a plane here is anything from a 64x64
	 * region to a 1600x1484 whole one, and a count that is right for one is wrong for the
	 * other by four orders of magnitude. Shared across the views of a dataset, so opening
	 * maxX, maxY and maxZ does not reserve three caches.
	 */
	static final class PlaneCache {
		private final long budget;
		private long used;
		private final LinkedHashMap<String, short[]> planes =
				new LinkedHashMap<String, short[]>(16, 0.75f, true);

		PlaneCache(long budget) { this.budget = Math.max(1L, budget); }

		synchronized short[] get(String key) { return planes.get(key); }

		synchronized void put(String key, short[] pixels) {
			if (pixels == null) return;
			short[] previous = planes.put(key, pixels);
			if (previous != null) used -= 2L * previous.length;
			used += 2L * pixels.length;
			java.util.Iterator<Map.Entry<String, short[]>> oldest = planes.entrySet().iterator();
			while (used > budget && planes.size() > 1 && oldest.hasNext()) {
				Map.Entry<String, short[]> entry = oldest.next();
				used -= 2L * entry.getValue().length;
				oldest.remove();
			}
		}
	}

	private static final long CACHE_BYTES = 128L * 1024L * 1024L;
	private static final Map<String, PlaneCache> CACHES = new LinkedHashMap<String, PlaneCache>();

	private static synchronized PlaneCache cacheFor(TiffResultDataset dataset) {
		String key = dataset.getRoot() == null
				? dataset.getDisplayName() : dataset.getRoot().getAbsolutePath();
		PlaneCache cache = CACHES.get(key);
		if (cache == null) {
			cache = new PlaneCache(CACHE_BYTES);
			CACHES.put(key, cache);
		}
		return cache;
	}

	// ---- presentation ----------------------------------------------------------------

	private static TiffResultDataset.View requireView(TiffResultDataset dataset, String viewKey)
			throws IOException {
		if (dataset == null) throw new IOException("No TIFF dataset selected.");
		TiffResultDataset.View view = dataset.getView(viewKey);
		if (view == null)
			throw new IOException("This result has no " + viewKey + " view.");
		return view;
	}

	/** The same options with no region, for asking what the whole view measures. */
	private static Options unbounded(Options options) {
		Options copy = options == null ? new Options() : options.copy();
		copy.bounds = null;
		copy.firstOutputChannel = 0;
		copy.outputChannelCount = -1;
		return copy;
	}

	private static String label(Selection selection, int channel, int z, Timepoint frame) {
		return selection.outputs.get(channel).label
				+ (selection.layout.slices > 1 ? ", Z=" + (selection.firstZ + z + 1) : "")
				+ ", Time" + String.format(Locale.ROOT, "%06d", frame.number);
	}

	private static String title(TiffResultDataset dataset, String viewKey, Selection selection,
			boolean virtual) {
		StringBuilder title = new StringBuilder(dataset.getDisplayName());
		title.append(" [").append(viewKey).append(']');
		if (!selection.isWholePlane() || selection.slices != selection.layout.slices
				|| selection.channels != selection.layout.channels
				|| !selection.outputs.get(0).label.startsWith("C"))
			title.append(" x=").append(selection.x).append(" y=").append(selection.y)
					.append(' ').append(selection.width).append('x').append(selection.height)
					.append(" z=").append(selection.firstZ + 1).append('-')
					.append(selection.firstZ + selection.slices)
					.append(" channels=").append(selection.labels());
		if (virtual) title.append(" (virtual)");
		return title.toString();
	}

	private static void calibrate(ImagePlus image, Selection selection, String viewKey) {
		Calibration calibration = image.getCalibration();
		TiffResultDataset.Layout layout = selection.layout;
		calibration.pixelWidth = layout.pixelWidth;
		calibration.pixelHeight = layout.pixelHeight;
		calibration.pixelDepth = layout.pixelDepth;
		calibration.frameInterval = layout.frameInterval;
		calibration.setUnit(layout.unit);
		image.setCalibration(calibration);
	}

	/**
	 * Stamp the view so an ROI drawn on it can be mapped back to the dataset it came from.
	 *
	 * <p>The same two keys {@link OmeZarrView#attachInfo} writes, and for the same reason: an
	 * ROI drawn on an already cropped view is in that crop's coordinates, and without the
	 * origin it would be applied to the dataset as though the crop had never happened.
	 */
	private static void attachInfo(ImagePlus image, TiffResultDataset dataset, String viewKey,
			Selection selection) {
		StringBuilder info = new StringBuilder();
		info.append("opm.tiffRoot = ").append(dataset.getRoot()).append('\n');
		info.append("opm.zarrRoot = ").append(dataset.getRoot()).append('\n');
		info.append("opm.viewOrigin = ").append(selection.x).append(',').append(selection.y)
				.append('\n');
		info.append("opm.view = ").append(viewKey).append('\n');
		info.append("opm.contentKind = deskewed TIFF\n");
		image.setProperty("Info", info.toString());
		image.setProperty("opm.channelLabels", selection.labels().toString());
	}

	/**
	 * Multi-channel views open as composites, through the OME-Zarr view's own routine.
	 *
	 * <p>Shared rather than copied: that method installs the colours before the display ranges
	 * because the two-argument {@code setChannelLut} would otherwise reset each range to the
	 * LUT default, and it contrasts from a mid-stack plane because slice 1 of a deskewed volume
	 * is the empty leading edge of the shear. Both are traps this view walks into identically.
	 */
	private static ImagePlus asComposite(ImagePlus image, int channels) {
		return OmeZarrView.asComposite(image, channels);
	}
}
