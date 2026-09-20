package de.embl.iclm;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * much smaller class, because a TIFF result has no runtime composition to do. There is no
 * flip, no alignment matrix and no side-by-side: those were applied when the file was written.
 * What is left is choosing which planes to read, which is the part that matters for a dataset
 * measured in hundreds of gigabytes.
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

		public Options copy() {
			Options copy = new Options();
			copy.virtual = virtual;
			copy.bounds = bounds == null ? null : bounds.copy();
			copy.firstOutputChannel = firstOutputChannel;
			copy.outputChannelCount = outputChannelCount;
			return copy;
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
		final int x;
		final int y;
		final int width;
		final int height;
		final int firstZ;
		final int slices;
		final int firstChannel;
		final int channels;

		Selection(TiffResultDataset.View view, TiffResultDataset.Layout layout, Options options)
				throws IOException {
			this.view = view;
			this.layout = layout;
			OmeZarrView.Bounds box = options.bounds == null
					? OmeZarrView.Bounds.full(layout.width, layout.height, layout.slices)
					: options.bounds.clampedTo(layout.width, layout.height, layout.slices);
			this.x = box.x;
			this.y = box.y;
			this.width = box.width;
			this.height = box.height;
			this.firstZ = box.zStart;
			this.slices = Math.max(1, box.depth());
			int first = options.firstOutputChannel;
			int count = options.outputChannelCount < 0
					? layout.channels - first : options.outputChannelCount;
			if (first < 0 || first >= layout.channels || count < 1 || first + count > layout.channels)
				throw new IOException("Channel range " + (first + 1) + "-" + (first + count)
						+ " is outside 1-" + layout.channels + ".");
			this.firstChannel = first;
			this.channels = count;
		}

		int planesPerTimepoint() { return channels * slices; }

		/** The plane in the source file that output (channel, z) reads. */
		int filePlane(int channel, int z) {
			return (firstZ + z) * layout.channels + firstChannel + channel;
		}

		boolean isWholePlane() {
			return x == 0 && y == 0 && width == layout.width && height == layout.height;
		}
	}

	// ---- opening ---------------------------------------------------------------------

	/** The size of the view these options produce: {width, height, depth, channels}. */
	public static int[] viewExtent(TiffResultDataset dataset, String viewKey, Options options)
			throws IOException {
		TiffResultDataset.View view = requireView(dataset, viewKey);
		Selection selection = new Selection(view, view.getLayout(), unbounded(options));
		return new int[] { selection.layout.width, selection.layout.height,
				selection.layout.slices, selection.layout.channels };
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
		if (view.frameCount() < 1)
			throw new IOException("No time points written yet in " + view.folder);
		if (timepoint >= view.frameCount())
			throw new IOException("Time point " + (timepoint + 1) + " is outside 1-"
					+ view.frameCount() + ".");
		Selection selection = new Selection(view, view.getLayout(), options);
		GrowingTiffStack stack = new GrowingTiffStack(selection, cacheFor(dataset), timepoint);
		ImagePlus image = new ImagePlus(title(dataset, viewKey, selection, true), stack);
		image.setDimensions(selection.channels, selection.slices, stack.frameCount());
		calibrate(image, selection, viewKey);
		attachInfo(image, dataset, viewKey, selection);
		/* As a hyperstack, always. Without it a single-channel result - a whole-image run - opened
		 * as a plain stack with one slider over every plane of every time point. */
		ImagePlus shown = asComposite(image, selection.channels);
		shown.setOpenAsHyperStack(shown.getStackSize() > 1);
		return shown;
	}

	/** The same view, read into memory for a range of time points. */
	public static ImagePlus openMaterialised(TiffResultDataset dataset, String viewKey,
			Options options, int firstTimepoint, int frames) throws IOException {
		TiffResultDataset.View view = requireView(dataset, viewKey);
		Selection selection = new Selection(view, view.getLayout(), options);
		List<TiffResultDataset.Frame> all = view.getFrames();
		if (firstTimepoint < 0 || frames < 1 || firstTimepoint + frames > all.size())
			throw new IOException("Time point range " + (firstTimepoint + 1) + "-"
					+ (firstTimepoint + frames) + " is outside 1-" + all.size() + ".");
		List<TiffResultDataset.Frame> wanted =
				new ArrayList<TiffResultDataset.Frame>(all.subList(firstTimepoint, firstTimepoint + frames));

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

		if (refresh) dataset.refresh();
		int frames = ((GrowingTiffStack) stack).grow();
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
			final List<TiffResultDataset.Frame> frames) throws IOException {
		final int perTimepoint = selection.planesPerTimepoint();
		int total = Math.multiplyExact(perTimepoint, frames.size());
		final short[][] output = new short[total][];
		int workers = Math.max(1, Math.min(FastTiffReader.logicalProcessorCount(), total));
		final int lanes = Math.max(1, Math.min(perTimepoint,
				(int) Math.ceil(workers / (double) frames.size())));

		ExecutorService pool = Executors.newFixedThreadPool(workers,
				Shutdown.daemonThreads("OPM-tiff-view"));
		final AtomicInteger done = new AtomicInteger();
		final int totalPlanes = total;
		List<Future<Void>> jobs = new ArrayList<Future<Void>>();
		try {
			for (int t = 0; t < frames.size(); t++) {
				final int timeIndex = t;
				final TiffResultDataset.Frame frame = frames.get(t);
				for (int lane = 0; lane < lanes; lane++) {
					final int myLane = lane;
					jobs.add(pool.submit(new Callable<Void>() {
						@Override public Void call() throws Exception {
							FastTiffReader.PlaneReader reader =
									new FastTiffReader.PlaneReader(frame.file);
							try {
								for (int local = myLane; local < perTimepoint; local += lanes) {
									if (Shutdown.stopping() || Thread.currentThread().isInterrupted())
										throw new IOException("Cancelled.");
									int channel = local % selection.channels;
									int z = local / selection.channels;
									output[timeIndex * perTimepoint + local] =
											read(reader, selection, channel, z);
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
		int plane = selection.filePlane(channel, z);
		if (selection.isWholePlane()) return reader.readPixels(plane);
		short[] band = reader.readRows(plane, selection.y, selection.height);
		if (selection.width == selection.layout.width) return band;
		short[] cropped = new short[Math.multiplyExact(selection.width, selection.height)];
		for (int row = 0; row < selection.height; row++)
			System.arraycopy(band, row * selection.layout.width + selection.x,
					cropped, row * selection.width, selection.width);
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
		private final List<TiffResultDataset.Frame> frames;
		private final Set<String> reported = new HashSet<String>();

		/** -1 to follow the view as it grows, or the one time point this view is pinned to. */
		private final int pinned;

		GrowingTiffStack(Selection selection, PlaneCache cache, int pinned) {
			super(selection.width, selection.height);
			this.selection = selection;
			this.cache = cache;
			this.pinned = pinned;
			List<TiffResultDataset.Frame> all = selection.view.getFrames();
			this.frames = pinned < 0 ? all
					: new ArrayList<TiffResultDataset.Frame>(all.subList(pinned, pinned + 1));
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
			List<TiffResultDataset.Frame> current = selection.view.getFrames();
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
				frame = frames.get(zero / perTimepoint);
				int local = zero % perTimepoint;
				channel = local % selection.channels;
				z = local / selection.channels;
			}
			String key = frame.file.getAbsolutePath().toLowerCase(Locale.ROOT) + '#'
					+ selection.filePlane(channel, z) + '#' + selection.x + ',' + selection.y
					+ ',' + selection.width + 'x' + selection.height;
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
				TiffResultDataset.Frame frame = frames.get(zero / perTimepoint);
				int local = zero % perTimepoint;
				return label(selection, local % selection.channels,
						local / selection.channels, frame);
			}
		}

		@Override public String getFileName(int index) {
			synchronized (this) {
				if (index < 1 || index > getSize()) return null;
				return frames.get((index - 1) / selection.planesPerTimepoint()).file.getName();
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

	private static String label(Selection selection, int channel, int z,
			TiffResultDataset.Frame frame) {
		return "C=" + (selection.firstChannel + channel + 1)
				+ (selection.layout.slices > 1 ? ", Z=" + (selection.firstZ + z + 1) : "")
				+ ", Time" + String.format(Locale.ROOT, "%06d", frame.timeNumber);
	}

	private static String title(TiffResultDataset dataset, String viewKey, Selection selection,
			boolean virtual) {
		StringBuilder title = new StringBuilder(dataset.getDisplayName());
		title.append(" [").append(viewKey).append(']');
		if (!selection.isWholePlane() || selection.slices != selection.layout.slices
				|| selection.channels != selection.layout.channels)
			title.append(" x=").append(selection.x).append(" y=").append(selection.y)
					.append(' ').append(selection.width).append('x').append(selection.height)
					.append(" z=").append(selection.firstZ + 1).append('-')
					.append(selection.firstZ + selection.slices)
					.append(" c=").append(selection.firstChannel + 1).append('-')
					.append(selection.firstChannel + selection.channels);
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
