package de.embl.iclm;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ij.io.FileInfo;
import ij.io.TiffDecoder;

/**
 * A deflated-TIFF deskew result, read as a dataset rather than as a pile of files.
 *
 * <p>Deskew Batch and Live Processing write one TIFF per time point per view: the volume as
 * {@code <name>-deskewed.tif} and each projection as
 * {@code <name>-deskewed-<type><axis>projection.tif}, either in sub-folders named
 * {@code deskew}, {@code maxZ}, {@code meanY} and so on, or side by side in one folder when
 * {@code separate results to sub-folders} is off. Both layouts are the same dataset; only the
 * folder differs, so both are recognised and neither is preferred.
 *
 * <p>What makes one dataset is the file name with the time number and the view suffix taken
 * out. Two acquisitions writing into one result folder therefore stay apart, and the volume
 * and its projections come back together.
 *
 * <p>Unlike a canonical OME-Zarr, a TIFF result is already composed: the flip, the 2-D
 * alignment and the channel order were applied when it was written, and the file holds the
 * finished pixels. There is no unflipped half to recover and no matrix to apply, which is why
 * the viewer's runtime controls have nothing to offer here. What a TIFF result does still
 * support is everything that is a matter of *which planes to read*: a region, a channel or Z
 * or time range, and a view that grows as more time points arrive.
 *
 * <p>Views are counted separately on purpose. A run interrupted between writing the volume and
 * writing its projections leaves one view a time point shorter than another, and reporting one
 * number for the dataset would make a complete view look truncated or an incomplete one look
 * whole.
 */
public final class TiffResultDataset {

	/** The view key of the deskewed volume; every other key is a projection. */
	public static final String VOLUME = "deskew";

	/**
	 * {@code -deskewed} before the extension, optionally followed by a projection suffix.
	 * <p>
	 * One spelling only, and every deskew route writes it: a user sees the kind of result a
	 * file is, not the route that produced it. The CLIJ fast path once wrote {@code -DS.tif},
	 * which this did not recognise as a volume at all.
	 */
	private static final Pattern VOLUME_SUFFIX =
			Pattern.compile("(?i)-deskewed$");

	/** {@code -maxZprojection}, {@code -meanYprojection}, and the rest of the family. */
	private static final Pattern PROJECTION_SUFFIX =
			Pattern.compile("(?i)-(max|avg|mean|min|sum|med|std)([xyz])projection$");

	private static final Pattern TIME_NUMBER = Pattern.compile("(?i)_Time(\\d+)");

	/** One time point of one view. */
	public static final class Frame {
		public final File file;
		public final long timeNumber;

		Frame(File file, long timeNumber) {
			this.file = file;
			this.timeNumber = timeNumber;
		}
	}

	/**
	 * One view of a dataset - the volume, or one projection - as a growing list of time points.
	 *
	 * <p>The layout is read from the first frame and every later frame is checked against it,
	 * because a view whose C/Z changes half way through is not one view.
	 */
	public static final class View {
		public final String key;
		public final File folder;
		private final List<Frame> frames = new ArrayList<Frame>();
		private Layout layout;

		View(String key, File folder) {
			this.key = key;
			this.folder = folder;
		}

		public boolean isVolume() { return VOLUME.equals(key); }
		public int frameCount() { synchronized (frames) { return frames.size(); } }

		public List<Frame> getFrames() {
			synchronized (frames) { return new ArrayList<Frame>(frames); }
		}

		public Frame frame(int index) {
			synchronized (frames) { return frames.get(index); }
		}

		void add(Frame frame) {
			synchronized (frames) { frames.add(frame); }
		}

		/** The C/Z/XY layout and calibration of this view, read once from its first frame. */
		public synchronized Layout getLayout() throws IOException {
			if (layout == null) {
				if (frameCount() < 1) throw new IOException("No TIFF time points in " + folder);
				layout = Layout.read(frame(0).file);
			}
			return layout;
		}
	}

	/**
	 * What one TIFF says about itself: the plane grid and the calibration.
	 *
	 * <p>C and Z come from ImageJ's own {@code ImageDescription}, which is what wrote them.
	 * When it is absent - a file saved by something else - one channel is assumed and Z is the
	 * plane count, which is right for a single-channel result and obviously wrong for anything
	 * else, so the two are cross-checked against the plane count and a mismatch is refused
	 * rather than silently reshaped.
	 */
	public static final class Layout {
		public final int width;
		public final int height;
		public final int channels;
		public final int slices;
		public final double pixelWidth;
		public final double pixelHeight;
		public final double pixelDepth;
		public final double frameInterval;
		public final String unit;

		Layout(int width, int height, int channels, int slices, double pixelWidth,
				double pixelHeight, double pixelDepth, double frameInterval, String unit) {
			this.width = width;
			this.height = height;
			this.channels = channels;
			this.slices = slices;
			this.pixelWidth = pixelWidth;
			this.pixelHeight = pixelHeight;
			this.pixelDepth = pixelDepth;
			this.frameInterval = frameInterval;
			this.unit = unit;
		}

		public int planesPerTimepoint() { return channels * slices; }

		static Layout read(File file) throws IOException {
			FastTiffReader.Info info = FastTiffReader.parse(file);
			if (info.bitsPerSample != 16 || info.samplesPerPixel != 1)
				throw new IOException("Expected 16-bit grayscale TIFF planes: " + file);
			if (info.depth() < 1) throw new IOException("No TIFF planes in " + file);
			return read(file, info);
		}

		static Layout read(File file, FastTiffReader.Info info) throws IOException {
			String description = description(file);
			int channels = positiveInteger(description, "channels", 1);
			int frames = positiveInteger(description, "frames", 1);
			int slices = positiveInteger(description, "slices", -1);
			if (frames != 1)
				throw new IOException("Expected one time point per TIFF, but the metadata says"
						+ " frames=" + frames + ": " + file);
			if (slices < 1) {
				if (info.depth() % channels != 0)
					throw new IOException("Cannot infer Z from " + info.depth() + " planes and C="
							+ channels + ": " + file);
				slices = info.depth() / channels;
			}
			if (channels * slices != info.depth())
				throw new IOException("The metadata declares C=" + channels + ", Z=" + slices
						+ ", T=1, but the file holds " + info.depth() + " planes: " + file);

			String unit = textValue(description, "unit", "micron");
			double spacing = positiveDouble(description, "spacing", 1.0d);
			double interval = positiveDouble(description, "finterval", 0.0d);

			/* These results carry Z spacing in the ImageJ description but often no TIFF
			 * XResolution tag at all, and ImageJ reports a missing one as 1.0 with a null
			 * unit - indistinguishable from a genuine 1 micron pixel unless the unit is
			 * checked. Falling back to the spacing is right rather than merely safe: the
			 * deskew affine is evaluated on a grid measured in camera pixels, so a deskewed
			 * volume is isotropic and its Z spacing is its XY pixel size. */
			double pixelWidth = spacing;
			double pixelHeight = spacing;
			FileInfo[] fileInfo = tiffInfo(file);
			if (fileInfo != null && fileInfo.length > 0 && fileInfo[0].unit != null) {
				if (fileInfo[0].pixelWidth > 0.0d) pixelWidth = fileInfo[0].pixelWidth;
				if (fileInfo[0].pixelHeight > 0.0d) pixelHeight = fileInfo[0].pixelHeight;
			}
			return new Layout(info.width, info.height, channels, slices,
					pixelWidth, pixelHeight, spacing, interval, unit);
		}

		private static FileInfo[] tiffInfo(File file) {
			try {
				File parent = file.getParentFile();
				String folder = parent == null ? "" : parent.getPath() + File.separator;
				return new TiffDecoder(folder, file.getName()).getTiffInfo();
			} catch (Throwable unreadable) {
				return null;
			}
		}

		private static String description(File file) {
			FileInfo[] info = tiffInfo(file);
			return info != null && info.length > 0 ? info[0].description : null;
		}

		private static String metadataValue(String description, String name) {
			if (description == null) return null;
			Matcher matcher = Pattern.compile("(?mi)^" + Pattern.quote(name) + "=([^\\r\\n]+)")
					.matcher(description);
			return matcher.find() ? matcher.group(1).trim() : null;
		}

		private static int positiveInteger(String description, String name, int fallback) {
			String value = metadataValue(description, name);
			if (value == null) return fallback;
			try {
				int parsed = Integer.parseInt(value);
				return parsed > 0 ? parsed : fallback;
			} catch (NumberFormatException notANumber) { return fallback; }
		}

		private static double positiveDouble(String description, String name, double fallback) {
			String value = metadataValue(description, name);
			if (value == null) return fallback;
			try {
				double parsed = Double.parseDouble(value);
				return parsed > 0.0d && !Double.isInfinite(parsed) && !Double.isNaN(parsed)
						? parsed : fallback;
			} catch (NumberFormatException notANumber) { return fallback; }
		}

		private static String textValue(String description, String name, String fallback) {
			String value = metadataValue(description, name);
			return value == null || value.trim().isEmpty() ? fallback : value.trim();
		}
	}

	private final String displayName;
	private final File root;
	private final Map<String, View> views;

	private TiffResultDataset(String displayName, File root, Map<String, View> views) {
		this.displayName = displayName;
		this.root = root;
		this.views = views;
	}

	public String getDisplayName() { return displayName; }
	public File getRoot() { return root; }
	public boolean hasVolume() { return views.containsKey(VOLUME); }
	public View getView(String key) { return views.get(key); }

	/** Every projection this result holds, in the order the projection folders sort. */
	public List<String> getAvailableProjections() {
		List<String> keys = new ArrayList<String>();
		for (String key : views.keySet()) if (!VOLUME.equals(key)) keys.add(key);
		return keys;
	}

	/** The time points of the longest view: what the dataset has reached, not what it has all of. */
	public int getTimepointCount() {
		int longest = 0;
		for (View view : views.values()) longest = Math.max(longest, view.frameCount());
		return longest;
	}

	@Override public String toString() { return displayName; }

	/**
	 * Find every TIFF deskew result under a folder.
	 *
	 * <p>The folder itself and its immediate sub-folders are read, which covers both layouts
	 * the writer produces without walking an acquisition tree of raw data. Files that are not
	 * a deskew result - a raw acquisition TIFF, a MIP movie - match neither suffix and are
	 * passed over.
	 *
	 * @param selection	: the result root, or one of its view sub-folders
	 * @return			: one entry per acquisition found, by display name
	 */
	public static List<TiffResultDataset> discover(File selection) {
		Map<String, Map<String, View>> series = new TreeMap<String, Map<String, View>>();
		File root = selection;
		if (root != null && root.isDirectory()) {
			collect(root, series);
			File[] children = root.listFiles();
			if (children != null) {
				List<File> folders = new ArrayList<File>();
				for (File child : children) if (child.isDirectory()) folders.add(child);
				Collections.sort(folders, new Comparator<File>() {
					@Override public int compare(File a, File b) {
						return a.getName().compareToIgnoreCase(b.getName());
					}
				});
				for (File folder : folders) collect(folder, series);
			}
		}
		List<TiffResultDataset> datasets = new ArrayList<TiffResultDataset>();
		for (Map.Entry<String, Map<String, View>> entry : series.entrySet()) {
			Map<String, View> ordered = new LinkedHashMap<String, View>();
			if (entry.getValue().containsKey(VOLUME))
				ordered.put(VOLUME, entry.getValue().get(VOLUME));
			List<String> projections = new ArrayList<String>(entry.getValue().keySet());
			Collections.sort(projections);
			for (String key : projections)
				if (!VOLUME.equals(key)) ordered.put(key, entry.getValue().get(key));
			for (View view : ordered.values()) sortByTime(view);
			datasets.add(new TiffResultDataset(entry.getKey(), root, ordered));
		}
		return datasets;
	}

	private static void sortByTime(View view) {
		synchronized (view.frames) {
			Collections.sort(view.frames, new Comparator<Frame>() {
				@Override public int compare(Frame a, Frame b) {
					if (a.timeNumber != b.timeNumber) return a.timeNumber < b.timeNumber ? -1 : 1;
					return a.file.getName().compareToIgnoreCase(b.file.getName());
				}
			});
		}
	}

	private static void collect(File folder, Map<String, Map<String, View>> series) {
		File[] files = folder.listFiles();
		if (files == null) return;
		for (File file : files) {
			if (!file.isFile()) continue;
			String name = file.getName();
			String lower = name.toLowerCase(Locale.ROOT);
			if (!lower.endsWith(".tif") && !lower.endsWith(".tiff")) continue;
			/* A file the deskew is still writing is passed over until its IFD chain and strip
			 * ranges reach its own end. This is what makes scanning a folder that is being
			 * written to safe, and it is metadata only - no pixels are decoded to decide. */
			if (!TiffCompletionCheck.isReady(file)) continue;
			String stem = name.substring(0, name.lastIndexOf('.'));

			String key = null;
			Matcher projection = PROJECTION_SUFFIX.matcher(stem);
			if (projection.find()) {
				key = projection.group(1).toLowerCase(Locale.ROOT)
						+ projection.group(2).toUpperCase(Locale.ROOT);
				stem = stem.substring(0, projection.start());
			}
			Matcher volume = VOLUME_SUFFIX.matcher(stem);
			if (volume.find()) stem = stem.substring(0, volume.start());
			else if (key == null) continue;			// neither a volume nor a projection
			if (key == null) key = VOLUME;

			long time = 0L;
			Matcher timeNumber = TIME_NUMBER.matcher(stem);
			if (timeNumber.find()) {
				try { time = Long.parseLong(timeNumber.group(1)); }
				catch (NumberFormatException tooLong) { time = 0L; }
				stem = stem.substring(0, timeNumber.start()) + "_Time"
						+ stem.substring(timeNumber.end());
			}

			Map<String, View> views = series.get(stem);
			if (views == null) {
				views = new LinkedHashMap<String, View>();
				series.put(stem, views);
			}
			View view = views.get(key);
			if (view == null) {
				view = new View(key, folder);
				views.put(key, view);
			}
			view.add(new Frame(file, time));
		}
	}

	/**
	 * Re-read the folder and append time points that have appeared since, in place.
	 *
	 * <p>Only frames whose name this dataset does not already hold are added, so a poll that
	 * finds nothing new changes nothing and a view already on screen keeps every index it has
	 * handed out.
	 *
	 * @return	: how many frames were added, across all views
	 */
	public int refresh() {
		int added = 0;
		List<TiffResultDataset> found = discover(root);
		for (TiffResultDataset candidate : found) {
			if (!displayName.equals(candidate.displayName)) continue;
			for (Map.Entry<String, View> entry : candidate.views.entrySet()) {
				View mine = views.get(entry.getKey());
				if (mine == null) continue;			// a view that appeared later needs a re-scan
				List<String> known = new ArrayList<String>();
				for (Frame frame : mine.getFrames())
					known.add(frame.file.getName().toLowerCase(Locale.ROOT));
				for (Frame frame : entry.getValue().getFrames()) {
					if (known.contains(frame.file.getName().toLowerCase(Locale.ROOT))) continue;
					mine.add(frame);
					added++;
				}
				if (added > 0) sortByTime(mine);
			}
		}
		return added;
	}
}
