package de.embl.iclm;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which OME-Zarr datasets a set of TIFF volumes makes, decided before a pixel is read.
 *
 * <p>The microscope writes one TIFF per position, time point and acquisition channel:
 * {@code 3_timelapse_Position0001_Time000001_Channel0001_Frames_1_451.tiff}. Converted one by
 * one, a 50-time-point, two-channel acquisition became a hundred stores of one channel and one
 * time point each. Grouped, it is one store per position with its time points along T and its
 * acquisition channels along C, which is what the OPM Data Viewer and every OME-Zarr tool expect.
 * A deskewed TIFF result written one series per acquisition channel
 * ({@code ..._Channel0001_...-deskewed.tif}) is grouped the same way.
 *
 * <p>A <b>dataset</b> is every file that shares a folder and a name once its {@code _Time},
 * {@code _Channel} and {@code _Frames_} tokens are taken out (for a recognised result: its result
 * folder and its series name without the channel). The position stays in that name, so each
 * position is a store of its own, and so do suffixes such as {@code -deflated} that mark a
 * processed copy - a copy is never merged with the data it was made from because their numbers
 * match. Files in different folders are never merged. With {@link #SCOPE_FILE} every loose file,
 * and every result series, is a dataset of its own.
 *
 * <p>Planning is in two steps. {@link #byName} groups and checks names only, cheap enough for a
 * dialog to run while the user types. {@link #check} then reads every file's TIFF directories -
 * never its pixels - and refuses, before anything is written, what cannot be stored honestly:
 * a structurally incomplete file, a missing or duplicated channel, a file whose size, plane count
 * or stated pixel size differs from its dataset, a split asked of something not known to be a
 * whole camera width, several files that each carry a time axis of their own. What it accepts it
 * lays out as time points of {@link Slot}s, each saying which file, which of its stored channels
 * and time points, and which X interval becomes one stored channel.
 *
 * <p>Nothing here is deskewed, flipped or aligned. A split is an integer crop at the camera
 * midline: the two halves together hold exactly the pixels of the whole width.
 */
final class TiffConversionPlan {

	// ---- the dialog's choices; these strings are what the preferences store -------------

	static final String SCOPE_DATASET = "whole dataset (time points + channels)";
	static final String SCOPE_FILE = "each TIFF separately";
	static final String[] SCOPES = { SCOPE_DATASET, SCOPE_FILE };

	static final String CHANNELS_KEEP = "keep as stored";
	static final String CHANNELS_SPLIT = "split left/right halves";
	static final String CHANNELS_LEFT = "left half only";
	static final String CHANNELS_RIGHT = "right half only";
	static final String[] CHANNEL_OPERATIONS = { CHANNELS_KEEP, CHANNELS_SPLIT, CHANNELS_LEFT, CHANNELS_RIGHT };

	static final String LAYOUT_AUTO = "auto detect";
	static final String LAYOUT_WHOLE = "whole camera width";
	static final String LAYOUT_STORED = "already separate channels";
	static final String[] INPUT_LAYOUTS = { LAYOUT_AUTO, LAYOUT_WHOLE, LAYOUT_STORED };

	static final String CONTENT_AUTO = "auto detect";
	static final String CONTENT_RAW = "raw acquisition";
	static final String CONTENT_DESKEWED = "deskewed volumes";
	static final String[] INPUT_CONTENTS = { CONTENT_AUTO, CONTENT_RAW, CONTENT_DESKEWED };

	static final String INCOMPLETE_REFUSE = "refuse the dataset";
	static final String INCOMPLETE_LEAVE_OUT = "convert complete time points only";
	static final String[] INCOMPLETE_POLICIES = { INCOMPLETE_REFUSE, INCOMPLETE_LEAVE_OUT };

	/** The acquisition's parameter file, beside its TIFFs. */
	static final String PARAMETERS_FILE = "ExperimentalParameters.txt";

	/** How many individual problems a message lists before it counts the rest. */
	private static final int LISTED = 4;
	/** Calibrations closer than this, relative, are the same: TIFF stores them as rationals. */
	private static final double CALIBRATION_TOLERANCE = 1e-5;

	private static final Pattern TIME = Pattern.compile("(?i)_(Time\\d+)");
	private static final Pattern CHANNEL = Pattern.compile("(?i)_Channel(\\d+)");
	private static final Pattern POSITION = Pattern.compile("(?i)_Position\\d+");
	/** {@code _Frames_a_b} or a lone {@code _Frames_n}: a plane range, never a second time axis. */
	private static final Pattern FRAMES = Pattern.compile("(?i)_Frames_\\d+(?:_\\d+)?(?!\\d)");
	/** The volume suffix every deskew route writes; a loose file named so is taken as deskewed. */
	private static final Pattern DESKEWED = Pattern.compile("(?i)-deskewed$");

	/** The choices that shape a plan. */
	static final class Settings {
		String scope = SCOPE_DATASET;
		String channels = CHANNELS_KEEP;
		String inputLayout = LAYOUT_AUTO;
		String content = CONTENT_AUTO;
		String incomplete = INCOMPLETE_REFUSE;
		/** Geometry given by the caller, preferred to anything the files say; 0 = not given. */
		double xyPixelSizeUm, zStepSizeUm;

		boolean grouped() { return !SCOPE_FILE.equals(scope); }
		boolean splits() { return !CHANNELS_KEEP.equals(channels); }
		boolean leavesOut() { return INCOMPLETE_LEAVE_OUT.equals(incomplete); }
	}

	/** What one TIFF's directories say, read without decoding a pixel. */
	static final class Header {
		int width, height, planes;
		int channels = 1, slices, frames = 1;
		int compression;
		boolean bigTiff;
		/** The file states its X and Y pixel size: an ImageJ length unit and an XResolution. */
		boolean xyCalibrated;
		/** The file states its Z pitch: an ImageJ length unit, with {@code spacing} or ImageJ's default of one unit. */
		boolean zCalibrated;
		/** X and Y were taken from the Z spacing, as for a deskew result written without resolution tags. */
		boolean xyFromSpacing;
		double pixelWidthUm, pixelHeightUm, pixelDepthUm, frameIntervalSeconds;

		/** Whether the file states any part of its pixel size. */
		boolean calibrated() { return xyCalibrated || zCalibrated; }

		/** C, Z and T as one comparable signature, with the plane size. */
		String shape() {
			return width + "x" + height + ", " + slices + " plane" + (slices == 1 ? "" : "s")
					+ (channels > 1 ? ", C=" + channels : "") + (frames > 1 ? ", T=" + frames : "");
		}

		/** The stated pixel size as one comparable signature; {@code ?} for a part the file does not state. */
		String calibration() {
			if (!calibrated()) return "no pixel size";
			return (xyCalibrated ? number(pixelWidthUm) + " x " + number(pixelHeightUm) : "? x ?")
					+ " x " + (zCalibrated ? number(pixelDepthUm) : "?") + " um";
		}

		private static String number(double value) {
			return String.format(Locale.US, "%.6g", value);
		}
	}

	/** One TIFF, as its name and - after {@link #check} - its directories describe it. */
	static final class Source {
		final File file;
		final String name;
		final String familyKey;
		final String datasetName;
		/** The folder the dataset belongs to: the file's own, or a result's root above its view folders. */
		final File folder;
		/** {@code Time000001} as the name spells it, or null. */
		final String timeToken;
		final long time;
		/** The acquisition channel number, or -1 when the name carries none. */
		final int channel;
		/** Planes promised by {@code _Frames_a_b}, or -1; never set for deskewed content. */
		int namedDepth;
		/** Whether the name carries any of the acquisition's tokens. */
		final boolean acquisitionNamed;
		/** Whether the name is the microscope's own: an acquisition channel and a plane range. */
		final boolean cameraNamed;
		/** A volume frame of a recognised deskewed TIFF result. */
		final boolean recognisedResult;
		/** A loose file named as a deskew result writes its volume. */
		final boolean deskewedName;
		/** Why the name cannot be placed, or null. */
		final String nameProblem;
		/** Deskewed content rather than raw acquisition pixels, as the run's choice decides. */
		boolean processed;

		Header header;
		/** Why the header could not be used, or null. */
		String headerProblem;
		/** The fast reader could not decode it, although it is a complete TIFF: another compression, tiles. */
		boolean unreadable;
		long bytes = -1, modified = -1;

		private Source(File file, String familyKey, String datasetName, File folder, String timeToken, long time,
				int channel, int namedDepth, boolean acquisitionNamed, boolean cameraNamed,
				boolean recognisedResult, boolean deskewedName, String nameProblem) {
			this.file = file;
			this.name = file.getName();
			this.familyKey = familyKey;
			this.datasetName = datasetName;
			this.folder = folder;
			this.timeToken = timeToken;
			this.time = time;
			this.channel = channel;
			this.namedDepth = namedDepth;
			this.acquisitionNamed = acquisitionNamed;
			this.cameraNamed = cameraNamed;
			this.recognisedResult = recognisedResult;
			this.deskewedName = deskewedName;
			this.nameProblem = nameProblem;
		}

		/** A TIFF that belongs to no result: a raw acquisition file, or anything else on its own. */
		static Source loose(File file, boolean grouped) {
			String base = BatchProcessingUtils.baseName(file);
			RawAcquisition.Name parsed = RawAcquisition.Name.parse(base);
			Matcher time = TIME.matcher(base);
			boolean hasTime = time.find(), hasChannel = CHANNEL.matcher(base).find();
			boolean hasFrames = FRAMES.matcher(base).find();
			String problem = parsed.placement != null ? parsed.placement : parsed.frames;
			File folder = file.getAbsoluteFile().getParentFile();
			String datasetName, familyKey;
			if (grouped) {
				String stripped = FRAMES.matcher(CHANNEL.matcher(TIME.matcher(base).replaceAll(""))
						.replaceAll("")).replaceAll("");
				stripped = stripped.replaceAll("[-_ ]+$", "").replaceAll("^[-_ ]+", "");
				datasetName = stripped.isEmpty() ? "dataset" : stripped;
				familyKey = canonical(folder) + File.separator + datasetName.toLowerCase(Locale.ROOT);
			} else {
				datasetName = base;
				familyKey = canonical(file);
			}
			return new Source(file, familyKey, datasetName, folder, hasTime ? time.group(1) : null,
					hasTime && parsed.time >= 0 ? parsed.time : -1,
					hasChannel && parsed.placement == null ? parsed.channel : -1,
					parsed.frames == null ? parsed.expectedDepth : -1,
					hasTime || hasChannel || hasFrames || POSITION.matcher(base).find(),
					hasChannel && hasFrames, false, DESKEWED.matcher(base).find(), grouped ? problem : null);
		}

		/**
		 * One volume frame of a recognised TIFF result. Its dataset is the result series - in dataset
		 * scope with the {@code _Channel} token taken out, so the channel series of one acquisition
		 * meet - under the name a converted result has always had.
		 */
		static Source result(TiffResultDataset result, TiffResultDataset.Frame frame, boolean grouped) {
			String display = result.getDisplayName();
			Matcher channelMatch = CHANNEL.matcher(display);
			int channel = -1;
			if (channelMatch.find()) {
				try { channel = Integer.parseInt(channelMatch.group(1)); }
				catch (NumberFormatException tooLong) { channel = -1; }
			}
			String series = grouped ? CHANNEL.matcher(display).replaceAll("") : display;
			String datasetName = FormatConversion.datasetName(series);
			if (datasetName.isEmpty()) datasetName = "dataset";
			File root = result.getRoot() == null ? frame.file.getAbsoluteFile().getParentFile() : result.getRoot();
			Matcher time = TIME.matcher(BatchProcessingUtils.baseName(frame.file));
			boolean hasTime = time.find();
			return new Source(frame.file, "result|" + canonical(root) + "|" + series.toLowerCase(Locale.ROOT),
					datasetName, root, hasTime ? time.group(1) : null, hasTime ? frame.timeNumber : -1,
					channel, -1, true, false, true, true, null);
		}

		/** The acquisition channel number a label uses: the name's, or 1 for a name with none. */
		int labelChannel() { return channel < 0 ? 1 : channel; }

		String fingerprint() { return name + "|" + bytes + "|" + modified; }

		/** Whether the file still has the size and time it had when it was checked. */
		boolean unchanged() {
			return file.length() == bytes && file.lastModified() == modified;
		}
	}

	/** One stored channel at one time point: which file, which of its C and T, which columns. */
	static final class Slot {
		final Source source;
		final int sourceChannel, sourceTime;
		final int x0, width;

		Slot(Source source, int sourceChannel, int sourceTime, int x0, int width) {
			this.source = source;
			this.sourceChannel = sourceChannel;
			this.sourceTime = sourceTime;
			this.x0 = x0;
			this.width = width;
		}

		/** Plane {@code z} of this slot's stored channel and time point, as the file orders them (XYCZT). */
		int planeIndex(int z) {
			Header h = source.header;
			return (sourceTime * h.slices + z) * h.channels + sourceChannel;
		}
	}

	/** One time point of the store: its label, elapsed time and one slot per stored channel. */
	static final class TimePoint {
		final String label;
		final double elapsedSeconds;
		final List<Slot> slots;

		TimePoint(String label, double elapsedSeconds, List<Slot> slots) {
			this.label = label;
			this.elapsedSeconds = elapsedSeconds;
			this.slots = Collections.unmodifiableList(new ArrayList<Slot>(slots));
		}

		/** The files this time point reads, each once, in channel order. */
		List<Source> sources() {
			Set<Source> distinct = new LinkedHashSet<Source>();
			for (Slot slot : slots) distinct.add(slot.source);
			return new ArrayList<Source>(distinct);
		}

		List<String> sourceNames() {
			List<String> names = new ArrayList<String>();
			for (Source source : sources()) names.add(source.name);
			return names;
		}

		/** Each file as it was when its header was read. */
		List<String> fingerprints() {
			List<String> prints = new ArrayList<String>();
			for (Source source : sources()) prints.add(source.fingerprint());
			return prints;
		}
	}

	/** One output store and everything that goes into it. */
	static final class Dataset {
		final String name;
		final File folder;
		final String identity;
		final List<Source> sources = new ArrayList<Source>();
		/** Placed sources: time number (-1 for none) to acquisition channel (-1 for none) to files. */
		final TreeMap<Long, TreeMap<Integer, List<Source>>> grid = new TreeMap<Long, TreeMap<Integer, List<Source>>>();
		final TreeSet<Integer> channels = new TreeSet<Integer>();
		final List<Source> unplaced = new ArrayList<Source>();
		/** Anything here refuses the whole dataset. */
		final List<String> problems = new ArrayList<String>();
		/** What a user should know but that does not stop the conversion. */
		final List<String> notes = new ArrayList<String>();
		/** Why each time number cannot be written as planned; left out or refused by policy. */
		final Map<Long, String> incomplete = new TreeMap<Long, String>();

		File output;
		boolean checked;
		/** Deskewed volumes, stored with their projections; raw acquisition pixels otherwise. */
		boolean processed;
		/** One of the {@link OpmProvenance.Conversion} layouts. */
		String storedLayout;
		int width, height, depth;
		final List<String> channelLabels = new ArrayList<String>();
		final List<String> channelSources = new ArrayList<String>();
		final List<TimePoint> timePoints = new ArrayList<TimePoint>();
		int leftOut;
		/** The stored X, Y and Z pitch in microns, each kept as found. */
		double[] sampling = { 1, 1, 1 };
		/** Where {@link #sampling} came from, for the record. */
		String samplingSource = "none found (1 micron recorded)";
		double frameIntervalSeconds;
		File parameters;
		/** ExperimentalParameters.txt as {@link IO#loadExperimentalParametersFromFile} reads it, or null. */
		double[] parameterValues;
		/** The time points the parameter file planned, or -1. */
		int plannedTimePoints = -1;
		/** Reading the files as one dataset failed for a reason the whole-volume fallback may not share. */
		boolean needsWholeVolumeReader;

		Dataset(String name, File folder, String identity) {
			this.name = name;
			this.folder = folder;
			this.identity = identity;
		}

		boolean convertible() { return checked && problems.isEmpty() && !timePoints.isEmpty(); }

		/** Composed channels of a deskew result are final pixels: their flip and alignment are in them. */
		boolean alignApplied() {
			return processed && OpmProvenance.Conversion.LAYOUT_STORED.equals(storedLayout);
		}

		/** The projections the store carries: the viewer's six for deskewed content, none for raw. */
		List<String> projections() {
			return processed ? OmeZarrSession.PROJECTIONS : Collections.<String>emptyList();
		}

		/** Stored channels, before {@link #check}: what the names alone promise. */
		int namedChannelCount(Settings settings) {
			int files = Math.max(1, channels.size());
			return CHANNELS_SPLIT.equals(settings.channels) ? files * 2 : files;
		}

		int namedTimeCount() {
			return grid.size();
		}

		String problemText() {
			return list(problems);
		}
	}

	final Settings settings;
	final List<Dataset> datasets = new ArrayList<Dataset>();
	/** TIFF results that cannot become a store at all, with the reason: no volume to store. */
	final List<String> skipped = new ArrayList<String>();

	private TiffConversionPlan(Settings settings) {
		this.settings = settings == null ? new Settings() : settings;
	}


	// ---- step 1: names ------------------------------------------------------------------

	/** The dataset a loose TIFF belongs to in dataset scope: its folder and its name without time, channel and plane range. */
	static String familyKey(File file) {
		return Source.loose(file, true).familyKey;
	}

	/** The dataset a TIFF result series belongs to in dataset scope: its folder and its name without the channel. */
	static String familyKey(TiffResultDataset result) {
		String series = CHANNEL.matcher(result.getDisplayName()).replaceAll("");
		File root = result.getRoot();
		return "result|" + (root == null ? "" : canonical(root)) + "|" + series.toLowerCase(Locale.ROOT);
	}

	/** Group loose files into datasets by name alone; nothing is opened. */
	static TiffConversionPlan byName(List<File> files, Settings settings) {
		return byName(files, Collections.<TiffResultDataset>emptyList(), settings);
	}

	/**
	 * Group loose files and the volume frames of TIFF results into datasets by name alone;
	 * nothing is opened.
	 *
	 * @param files		: the loose TIFFs to convert, in the order they were listed
	 * @param results	: recognised TIFF results; one without a volume is listed in {@link #skipped}
	 */
	static TiffConversionPlan byName(List<File> files, List<TiffResultDataset> results, Settings settings) {
		TiffConversionPlan plan = new TiffConversionPlan(settings);
		boolean grouped = plan.settings.grouped();
		Map<String, Dataset> byKey = new LinkedHashMap<String, Dataset>();
		List<Source> sources = new ArrayList<Source>();
		for (File file : files) sources.add(Source.loose(file, grouped));
		for (TiffResultDataset result : results) {
			if (!result.hasVolume()) {
				plan.skipped.add(result.getDisplayName() + ": no deskewed volume to store (a projection is not a volume)");
				continue;
			}
			for (TiffResultDataset.Frame frame : result.getView(TiffResultDataset.VOLUME).getFrames())
				sources.add(Source.result(result, frame, grouped));
		}
		for (Source source : sources) {
			source.processed = plan.processed(source);
			if (source.processed) source.namedDepth = -1;		// a deskewed result's name still carries the raw plane range
			Dataset dataset = byKey.get(source.familyKey);
			if (dataset == null) {
				dataset = new Dataset(source.datasetName, source.folder, source.familyKey);
				dataset.processed = source.processed;
				byKey.put(source.familyKey, dataset);
				plan.datasets.add(dataset);
			}
			dataset.sources.add(source);
			if (source.nameProblem != null) {
				dataset.unplaced.add(source);
				continue;
			}
			// a loose file in file scope is one time point; a result series keeps its own
			long time = grouped || source.recognisedResult ? source.time : -1;
			int channel = grouped ? source.channel : -1;
			TreeMap<Integer, List<Source>> row = dataset.grid.get(time);
			if (row == null) { row = new TreeMap<Integer, List<Source>>(); dataset.grid.put(time, row); }
			List<Source> cell = row.get(channel);
			if (cell == null) { cell = new ArrayList<Source>(); row.put(channel, cell); }
			cell.add(source);
			dataset.channels.add(channel);
		}
		for (Dataset dataset : plan.datasets) plan.checkNames(dataset);
		return plan;
	}

	/** Raw or deskewed, as the run's choice says, or as the file shows: a recognised result, or its name. */
	private boolean processed(Source source) {
		if (CONTENT_DESKEWED.equals(settings.content)) return true;
		if (CONTENT_RAW.equals(settings.content)) return false;
		return source.recognisedResult || source.deskewedName;
	}

	/** What the names alone say is wrong: tokens that disagree, a slot filled twice or not at all. */
	private void checkNames(Dataset dataset) {
		for (Source source : dataset.unplaced) {
			String why = source.name + ": " + source.nameProblem;
			if (settings.leavesOut()) dataset.notes.add("left out " + why);
			else dataset.problems.add(why);
		}
		for (Source source : dataset.sources)
			if (source.processed != dataset.processed) {
				dataset.problems.add("it mixes raw and deskewed files");
				break;
			}
		boolean timed = false, untimed = false;
		for (Long time : dataset.grid.keySet()) { if (time < 0) untimed = true; else timed = true; }
		if (timed && untimed)
			dataset.problems.add("some names carry a _Time number and some do not, so they cannot be put in order");
		boolean withChannel = false, withoutChannel = false;
		for (Integer channel : dataset.channels) { if (channel < 0) withoutChannel = true; else withChannel = true; }
		if (withChannel && withoutChannel)
			dataset.problems.add("some names carry a _Channel number and some do not");

		for (Map.Entry<Long, TreeMap<Integer, List<Source>>> row : dataset.grid.entrySet()) {
			List<String> why = new ArrayList<String>();
			List<Integer> missing = new ArrayList<Integer>();
			for (Integer channel : dataset.channels) {
				List<Source> cell = row.getValue().get(channel);
				if (cell == null) missing.add(channel);
				else if (cell.size() > 1) why.add(cell.size() + " files for " + channelName(channel)
						+ " (" + names(cell) + ")");
			}
			if (!missing.isEmpty()) why.add(0, channelNames(missing) + " missing");
			if (!why.isEmpty()) dataset.incomplete.put(row.getKey(), join(why, "; "));
		}
	}


	// ---- step 2: directories ------------------------------------------------------------

	/**
	 * Read every placed file's TIFF directories and lay each dataset out, or say why it cannot be.
	 * No pixel is read; this is what makes a refusal possible before anything is written.
	 */
	void check() {
		for (Dataset dataset : datasets) {
			if (Shutdown.stopping()) return;
			check(dataset);
		}
	}

	private void check(Dataset dataset) {
		dataset.checked = true;
		readAcquisitionParameters(dataset);
		int planesPerVolume = dataset.processed || dataset.parameters == null ? -1
				: RawAcquisition.planesPerVolume(dataset.parameters);

		// every file on its own first: a broken or short file must not decide what the rest should be
		Map<Source, String> invalid = new LinkedHashMap<Source, String>();
		List<Source> placed = new ArrayList<Source>();
		for (TreeMap<Integer, List<Source>> row : dataset.grid.values())
			for (List<Source> cell : row.values())
				for (Source source : cell) {
					placed.add(source);
					readHeader(source);
					String problem = source.headerProblem;
					Header h = source.header;
					if (problem == null && source.namedDepth > 0 && source.namedDepth != h.slices)
						problem = "its name promises " + source.namedDepth + " planes, it holds " + h.slices;
					if (problem == null && source.namedDepth < 0 && planesPerVolume > 0 && source.acquisitionNamed
							&& h.channels == 1 && h.frames == 1 && h.slices != planesPerVolume)
						problem = "holds " + h.slices + " planes, " + PARAMETERS_FILE + " says " + planesPerVolume
								+ " per volume";
					if (problem != null) invalid.put(source, problem);
				}

		// the shape the valid files share; two shapes equally common is a question, not an answer
		Map<String, List<Source>> shapes = new LinkedHashMap<String, List<Source>>();
		for (Source source : placed)
			if (!invalid.containsKey(source)) add(shapes, source.header.shape(), source);
		if (shapes.isEmpty()) {
			Source first = placed.isEmpty() ? null : placed.get(0);
			dataset.needsWholeVolumeReader = first != null && first.unreadable;
			dataset.problems.add(placed.isEmpty() ? "no file could be placed"
					: "no file is a complete, readable 16-bit TIFF volume (" + first.name + " " + invalid.get(first) + ")");
			return;
		}
		String common = mostCommon(dataset, shapes, "shape");
		if (common == null) return;
		Header reference = shapes.get(common).get(0).header;
		List<Source> usable = shapes.get(common);
		for (Source source : placed) {
			String problem = invalid.get(source);
			if (problem == null && !usable.contains(source))
				problem = "is " + source.header.shape() + ", the dataset is " + common;
			if (problem != null) markIncomplete(dataset, source, problem);
		}

		String layout = layout(dataset, reference);
		if (layout == null) return;
		if (!chooseSampling(dataset, reference, usable)) return;
		dataset.storedLayout = layout;
		dataset.height = reference.height;
		dataset.depth = reference.slices;
		boolean whole = !OpmProvenance.Conversion.LAYOUT_STORED.equals(layout);
		int half = reference.width / 2;
		dataset.width = OpmProvenance.Conversion.LAYOUT_HALVES.equals(layout) ? half : reference.width;

		// the stored channels, the same for every time point
		List<int[]> perFile = new ArrayList<int[]>();		// {sourceChannel, x0, width}
		List<String> suffixes = new ArrayList<String>();
		if (whole) {
			if (CHANNELS_KEEP.equals(settings.channels)) { perFile.add(new int[] { 0, 0, reference.width }); suffixes.add("-whole"); }
			if (CHANNELS_SPLIT.equals(settings.channels) || CHANNELS_LEFT.equals(settings.channels)) {
				perFile.add(new int[] { 0, 0, half }); suffixes.add("-left");
			}
			if (CHANNELS_SPLIT.equals(settings.channels) || CHANNELS_RIGHT.equals(settings.channels)) {
				perFile.add(new int[] { 0, half, half }); suffixes.add("-right");
			}
		} else {
			for (int c = 0; c < reference.channels; c++) {
				perFile.add(new int[] { c, 0, reference.width });
				suffixes.add(reference.channels > 1 ? "-C" + (c + 1) : "");
			}
		}
		for (Integer channel : dataset.channels) {
			/* The acquisition channel a label names is the file's own: in file scope every file
			 * is one column whatever its _Channel number, and that number still belongs in it. */
			Source representative = representative(dataset, channel);
			boolean named = representative != null && representative.channel >= 0;
			int acquisition = representative == null ? 1 : representative.labelChannel();
			for (int i = 0; i < perFile.size(); i++) {
				int[] part = perFile.get(i);
				String label = whole || named
						? String.format(Locale.US, "_Channel%04d%s", acquisition, suffixes.get(i))
						: "C" + (part[0] + 1);
				dataset.channelLabels.add(label);
				dataset.channelSources.add(String.format(Locale.US, "%s: acquisition channel %s, stored channel %d, x %d-%d",
						label, named ? String.valueOf(acquisition) : "-", part[0] + 1, part[1], part[1] + part[2] - 1));
			}
		}

		// the time points: one per time number, or the frames of a file that holds its own T
		boolean internalTime = reference.frames > 1;
		if (internalTime && dataset.grid.size() > 1) {
			dataset.problems.add("each file holds " + reference.frames + " time points of its own and the names number"
					+ " time points as well; refused rather than inventing one time order for both");
			return;
		}
		Long first = null;
		List<String> leftOut = new ArrayList<String>();
		List<Long> numbers = new ArrayList<Long>();
		for (Map.Entry<Long, TreeMap<Integer, List<Source>>> row : dataset.grid.entrySet()) {
			long number = row.getKey();
			String problem = dataset.incomplete.get(number);
			String label = timeLabel(dataset, row.getValue(), number);
			if (problem != null) {
				leftOut.add(label + ": " + problem);
				continue;
			}
			if (first == null) first = number;
			numbers.add(number);
			int frames = internalTime ? reference.frames : 1;
			for (int t = 0; t < frames; t++) {
				List<Slot> slots = new ArrayList<Slot>();
				for (Integer channel : dataset.channels) {
					Source source = row.getValue().get(channel).get(0);
					for (int[] part : perFile) slots.add(new Slot(source, part[0], t, part[1], part[2]));
				}
				double elapsed = internalTime ? t * dataset.frameIntervalSeconds
						: number >= 0 ? (number - first) * dataset.frameIntervalSeconds : 0;
				String frameLabel = internalTime ? String.format(Locale.US, "%s_t%04d", label, t + 1) : label;
				dataset.timePoints.add(new TimePoint(frameLabel, elapsed, slots));
			}
		}
		dataset.leftOut = leftOut.size();
		if (!leftOut.isEmpty()) {
			if (settings.leavesOut())
				dataset.notes.add("left out " + leftOut.size() + " incomplete time point" + plural(leftOut.size())
						+ ": " + list(leftOut));
			else
				dataset.problems.add(leftOut.size() + " incomplete time point" + plural(leftOut.size()) + ": "
						+ list(leftOut));
		}
		if (dataset.timePoints.isEmpty() && dataset.problems.isEmpty())
			dataset.problems.add("no complete time point is left to convert");
		noteGaps(dataset, numbers);
		if (dataset.plannedTimePoints > 0 && !internalTime && dataset.grid.size() < dataset.plannedTimePoints)
			dataset.notes.add(dataset.grid.size() + " of the " + dataset.plannedTimePoints + " time points "
					+ PARAMETERS_FILE + " plans are present; only those are converted");
	}

	/**
	 * The most common key, or null with a problem when two or more are equally common: then the
	 * files themselves do not say which is right, and choosing one would discard the others.
	 */
	private static String mostCommon(Dataset dataset, Map<String, List<Source>> groups, String what) {
		String best = null;
		boolean tied = false;
		for (Map.Entry<String, List<Source>> group : groups.entrySet()) {
			int size = group.getValue().size();
			if (best == null || size > groups.get(best).size()) { best = group.getKey(); tied = false; }
			else if (size == groups.get(best).size()) tied = true;
		}
		if (!tied) return best;
		List<String> counted = new ArrayList<String>();
		for (Map.Entry<String, List<Source>> group : groups.entrySet())
			counted.add(group.getKey() + " (" + group.getValue().size() + " file" + plural(group.getValue().size()) + ")");
		dataset.problems.add("its files disagree on their " + what + " - " + list(counted)
				+ " - and none is the more common; refused rather than choosing one");
		return null;
	}

	private static void markIncomplete(Dataset dataset, Source source, String problem) {
		long time = -1;
		for (Map.Entry<Long, TreeMap<Integer, List<Source>>> row : dataset.grid.entrySet())
			for (List<Source> cell : row.getValue().values())
				if (cell.contains(source)) time = row.getKey();
		String before = dataset.incomplete.get(time);
		String why = source.name + " " + problem;
		dataset.incomplete.put(time, before == null ? why : before + "; " + why);
	}

	private static void add(Map<String, List<Source>> groups, String key, Source source) {
		List<Source> group = groups.get(key);
		if (group == null) { group = new ArrayList<Source>(); groups.put(key, group); }
		group.add(source);
	}

	/**
	 * The stored layout for this dataset and this run's channel choice, or null with a problem.
	 * <p>
	 * A one-channel TIFF is a whole camera width only when something says so: the run's
	 * declaration; for raw pixels the camera ROI in the acquisition's parameter file, or else the
	 * microscope's own naming ({@code _ChannelNNNN} and {@code _Frames_}); for a deskewed result
	 * the viewer's convention, one {@code _ChannelNNNN} series per acquisition channel. A crop or
	 * a composed image is none of these, and is kept as stored rather than cut into halves it
	 * never had.
	 */
	private String layout(Dataset dataset, Header reference) {
		boolean declaredWhole = LAYOUT_WHOLE.equals(settings.inputLayout);
		boolean declaredStored = LAYOUT_STORED.equals(settings.inputLayout);
		boolean whole;
		String why;
		if (reference.channels > 1) {
			if (declaredWhole) {
				dataset.problems.add("its files hold " + reference.channels + " stored channels each, so they are not"
						+ " whole camera widths");
				return null;
			}
			whole = false;
			why = "its files already hold " + reference.channels + " channels each";
		} else if (declaredWhole || declaredStored) {
			whole = declaredWhole;
			why = "they were declared " + settings.inputLayout;
		} else if (dataset.processed) {
			whole = true;
			for (Source source : dataset.sources) whole &= source.channel >= 0;
			why = "a deskewed volume is taken as a whole camera width only when it is one _ChannelNNNN series"
					+ " of one channel";
		} else {
			int[] roi = cameraRoi(dataset);
			if (roi != null) {
				whole = reference.width == roi[0] && reference.height == roi[1];
				why = "the files are " + reference.width + "x" + reference.height + " and the camera ROI in "
						+ PARAMETERS_FILE + " is " + roi[0] + "x" + roi[1];
			} else {
				whole = true;
				for (Source source : dataset.sources) whole &= source.cameraNamed;
				why = "nothing says these are whole camera widths: there is no " + PARAMETERS_FILE + " beside them,"
						+ " and their names are not the microscope's ..._ChannelNNNN_Frames_... names";
			}
		}
		if (!whole && settings.splits()) {
			dataset.problems.add("'" + settings.channels + "' needs whole camera widths, and " + why
					+ (declaredStored ? "" : " (declare 'whole camera width' if they are)"));
			return null;
		}
		if (whole && settings.splits() && reference.width % 2 != 0) {
			dataset.problems.add("the camera width " + reference.width + " is odd, so it has no midline to split at");
			return null;
		}
		if (!whole) return OpmProvenance.Conversion.LAYOUT_STORED;
		return settings.splits() ? OpmProvenance.Conversion.LAYOUT_HALVES : OpmProvenance.Conversion.LAYOUT_WHOLE;
	}

	/** The camera ROI's width and height from the parameter file, or null. */
	private static int[] cameraRoi(Dataset dataset) {
		double[] values = dataset.parameterValues;
		if (values == null || values.length < 6 || !(values[4] > 0) || !(values[5] > 0)) return null;
		return new int[] { (int) Math.round(values[4]), (int) Math.round(values[5]) };
	}

	/**
	 * The stored X, Y and Z pitch, each kept as found, and the frame interval.
	 * <p>
	 * Given by the caller, it is used and recorded as such. For raw pixels with the acquisition's
	 * parameter file beside them it is that file's camera pixel and stage step, and a file that
	 * states a different pixel size is not part of this acquisition as stored. Otherwise it is
	 * what the files state, and they must all state the same - a file without a pixel size
	 * included: one store has one sampling, and a file's geometry is never taken from another.
	 * A file that disagrees makes its time point incomplete. A part no file states - X and Y, or
	 * Z - is recorded as one micron, and said so.
	 * <p>
	 * The frame interval of raw pixels is the acquisition's, from its parameter file, else what
	 * the files state. A deskewed result states its own, which may differ - a result made from
	 * every second time point is twice as far apart - so there it wins, the parameter file only
	 * fills in an interval the files do not state, and files stating different intervals refuse
	 * the dataset rather than one being chosen.
	 *
	 * @return	false when the files disagree with no majority, with a problem
	 */
	private boolean chooseSampling(Dataset dataset, Header reference, List<Source> usable) {
		double[] values = dataset.parameterValues;
		double acquisitionInterval = values != null && values[3] > 0 ? values[3] : 0;
		if (dataset.processed) {
			if (!chooseProcessedInterval(dataset, usable, acquisitionInterval)) return false;
		} else if (acquisitionInterval > 0) {
			dataset.frameIntervalSeconds = acquisitionInterval;
		} else if (reference.frameIntervalSeconds > 0) {
			dataset.frameIntervalSeconds = reference.frameIntervalSeconds;
		}

		if (settings.xyPixelSizeUm > 0) {
			double z = settings.zStepSizeUm > 0 ? settings.zStepSizeUm : 1;
			dataset.sampling = new double[] { settings.xyPixelSizeUm, settings.xyPixelSizeUm, z };
			dataset.samplingSource = "given by the caller";
			for (Source source : usable)
				if (source.header.calibrated() && !matches(source.header, dataset.sampling)) {
					dataset.notes.add("the given pixel size overrides what " + source.name + " states ("
							+ source.header.calibration() + ")");
					break;
				}
			return true;
		}
		if (!dataset.processed && values != null) {
			dataset.sampling = new double[] { values[0] / 1000.0, values[0] / 1000.0, values[1] / 1000.0 };
			dataset.samplingSource = PARAMETERS_FILE;
			for (Source source : usable)
				if (source.header.calibrated() && !matches(source.header, dataset.sampling))
					markIncomplete(dataset, source, "states " + source.header.calibration() + ", " + PARAMETERS_FILE
							+ " gives " + String.format(Locale.US, "%.6g x %.6g x %.6g um",
									dataset.sampling[0], dataset.sampling[1], dataset.sampling[2]));
			return true;
		}
		Map<String, List<Source>> calibrations = new LinkedHashMap<String, List<Source>>();
		for (Source source : usable) add(calibrations, source.header.calibration(), source);
		String common = mostCommon(dataset, calibrations, "pixel size");
		if (common == null) return false;
		for (Source source : usable)
			if (!calibrations.get(common).contains(source))
				markIncomplete(dataset, source, "states " + source.header.calibration() + ", the dataset " + common);
		Header stated = calibrations.get(common).get(0).header;
		dataset.sampling = new double[] {
			stated.xyCalibrated ? stated.pixelWidthUm : 1,
			stated.xyCalibrated ? stated.pixelHeightUm : 1,
			stated.zCalibrated ? stated.pixelDepthUm : 1 };
		if (!stated.calibrated()) {
			dataset.notes.add("no pixel size found (no " + PARAMETERS_FILE + ", no calibrated TIFF); 1 micron is recorded");
			return true;
		}
		dataset.samplingSource = "the TIFF calibration";
		if (stated.xyFromSpacing) {
			dataset.samplingSource += " (X and Y from its Z spacing, as a deskew result without resolution tags records them)";
		} else if (!stated.xyCalibrated) {
			dataset.samplingSource += " (Z only)";
			dataset.notes.add("the TIFFs state no X/Y pixel size; 1 micron is recorded for X and Y");
		}
		return true;
	}

	/** The interval a deskewed result states, else its acquisition's; false, with a problem, when its files disagree. */
	private static boolean chooseProcessedInterval(Dataset dataset, List<Source> usable, double acquisitionInterval) {
		List<Double> stated = new ArrayList<Double>();
		for (Source source : usable) {
			double interval = source.header.frameIntervalSeconds;
			if (!(interval > 0)) continue;
			boolean known = false;
			for (Double seen : stated) known |= near(seen.doubleValue(), interval);
			if (!known) stated.add(Double.valueOf(interval));
		}
		if (stated.size() > 1) {
			List<String> intervals = new ArrayList<String>();
			for (Double interval : stated) intervals.add(interval + " s");
			dataset.problems.add("its files state different frame intervals (" + join(intervals, ", ")
					+ "); refused rather than choosing one");
			return false;
		}
		if (stated.size() == 1) {
			dataset.frameIntervalSeconds = stated.get(0).doubleValue();
			if (acquisitionInterval > 0 && !near(acquisitionInterval, dataset.frameIntervalSeconds))
				dataset.notes.add("the TIFFs state a frame interval of " + dataset.frameIntervalSeconds + " s and "
						+ PARAMETERS_FILE + " " + acquisitionInterval + " s; the TIFFs' is kept");
		} else if (acquisitionInterval > 0) {
			dataset.frameIntervalSeconds = acquisitionInterval;
			dataset.notes.add("the TIFFs state no frame interval; " + acquisitionInterval + " s is taken from "
					+ PARAMETERS_FILE);
		}
		return true;
	}

	/** Whether a header's stated parts of its pixel size equal these; a part it does not state is not compared. */
	private static boolean matches(Header header, double[] sampling) {
		boolean xy = !header.xyCalibrated
				|| (near(header.pixelWidthUm, sampling[0]) && near(header.pixelHeightUm, sampling[1]));
		boolean z = !header.zCalibrated || near(header.pixelDepthUm, sampling[2]);
		return xy && z;
	}

	/** The first file placed in this channel column, or null. */
	private static Source representative(Dataset dataset, Integer channel) {
		for (TreeMap<Integer, List<Source>> row : dataset.grid.values()) {
			List<Source> cell = row.get(channel);
			if (cell != null && !cell.isEmpty()) return cell.get(0);
		}
		return null;
	}

	private static void noteGaps(Dataset dataset, List<Long> numbers) {
		if (numbers.size() < 2 || numbers.get(0) < 0) return;
		List<String> gaps = new ArrayList<String>();
		for (int i = 1; i < numbers.size(); i++)
			if (numbers.get(i) != numbers.get(i - 1) + 1)
				gaps.add(numbers.get(i - 1) + "-" + numbers.get(i));
		if (gaps.isEmpty()) return;
		dataset.notes.add("time numbers are not consecutive (" + list(gaps) + "); T counts only the time points"
				+ " written, their labels and elapsed seconds keep the original numbers, and the OME-Zarr"
				+ " time scale states the nominal interval only");
	}

	private static String timeLabel(Dataset dataset, TreeMap<Integer, List<Source>> row, long number) {
		if (number < 0) return dataset.name;
		for (List<Source> cell : row.values())
			for (Source source : cell)
				if (source.timeToken != null) return dataset.name + "_" + source.timeToken;
		return dataset.name + "_Time" + number;
	}

	/**
	 * The acquisition's parameter file: beside raw files; for deskewed results also one folder up,
	 * where a result folder sits inside its acquisition - used there for the interval and the
	 * planned time points only, since a deskewed volume no longer has the raw sampling.
	 */
	private static void readAcquisitionParameters(Dataset dataset) {
		File parameters = dataset.folder == null ? null : new File(dataset.folder, PARAMETERS_FILE);
		if ((parameters == null || !parameters.isFile()) && dataset.processed && dataset.folder != null
				&& dataset.folder.getParentFile() != null)
			parameters = new File(dataset.folder.getParentFile(), PARAMETERS_FILE);
		if (parameters == null || !parameters.isFile()) return;
		dataset.parameters = parameters;
		dataset.plannedTimePoints = plannedTimePoints(parameters);
		try {
			double[] values = IO.loadExperimentalParametersFromFile(parameters.getAbsolutePath());
			if (values != null && values.length >= 6 && values[0] > 0 && values[1] > 0)
				dataset.parameterValues = values;
		} catch (RuntimeException unparsable) {
			dataset.notes.add(PARAMETERS_FILE + " could not be read (" + unparsable + "); it is not used");
		}
	}

	/** The {@code Number of Time Points} of a parameter file, or -1. */
	static int plannedTimePoints(File parameters) {
		try {
			for (String line : new String(Files.readAllBytes(parameters.toPath()), StandardCharsets.ISO_8859_1)
					.split("\\r?\\n|\\r")) {
				String[] parts = line.split(":", 2);
				if (parts.length == 2 && parts[0].trim().equalsIgnoreCase("Number of Time Points")) {
					int count = Integer.parseInt(parts[1].trim());
					return count > 0 ? count : -1;
				}
			}
		} catch (IOException | NumberFormatException unusable) {
			// nothing planned to compare with
		}
		return -1;
	}

	/**
	 * Read one file's directories into its {@link Source#header}, or say why not. A file whose
	 * directories or pixel strips do not all lie inside it - still being written, or cut short -
	 * is refused here, before any store is opened, by the same metadata-only check the live
	 * paths use.
	 */
	static void readHeader(Source source) {
		source.bytes = source.file.length();
		source.modified = source.file.lastModified();
		if (!TiffCompletionCheck.isReady(source.file)) {
			source.headerProblem = "is not a complete TIFF (missing, still being written, or a directory or pixel"
					+ " strip lies past its end)";
			return;
		}
		try {
			source.header = readHeader(source.file);
			applyDeskewResultCalibration(source);
		} catch (IOException unreadable) {
			source.unreadable = true;
			source.headerProblem = "cannot be read as a 16-bit strip TIFF: " + unreadable.getMessage();
		} catch (RuntimeException unreadable) {
			source.unreadable = true;
			source.headerProblem = "cannot be read: " + unreadable;
		}
	}

	/**
	 * A TIFF's plane grid, C/Z/T and calibration, from its directories alone.
	 * <p>
	 * C, Z and T come from the ImageJ description, which is what wrote them; a file without one
	 * (the microscope's own) is one channel, one time point and as many planes as it has. A
	 * description that does not add up to the plane count is refused rather than reshaped. The
	 * pixel size is X and Y from the resolution tags and Z from {@code spacing}, each in the
	 * description's unit; ImageJ omits a spacing of one unit.
	 */
	static Header readHeader(File file) throws IOException {
		FastTiffReader.Info info = FastTiffReader.parse(file);
		FastTiffReader.FirstPlane first = FastTiffReader.firstPlane(file);
		Header header = new Header();
		header.width = info.width;
		header.height = info.height;
		header.planes = info.depth();
		header.compression = info.compression;
		header.bigTiff = info.bigTiff;
		String description = first.description;
		boolean imagej = description != null && description.startsWith("ImageJ=");
		int slices = -1;
		if (imagej) {
			header.channels = positiveInteger(description, "channels", 1);
			header.frames = positiveInteger(description, "frames", 1);
			slices = positiveInteger(description, "slices", -1);
		}
		int perSlice = header.channels * header.frames;
		if (slices < 1) {
			if (header.planes % perSlice != 0)
				throw new IOException("its description says C=" + header.channels + ", T=" + header.frames
						+ ", which does not divide its " + header.planes + " planes");
			slices = header.planes / perSlice;
		}
		header.slices = slices;
		if ((long) header.channels * header.slices * header.frames != header.planes)
			throw new IOException("its description says C=" + header.channels + ", Z=" + header.slices + ", T="
					+ header.frames + ", but it holds " + header.planes + " planes");

		if (imagej) {
			double micronsPerUnit = micronsPer(value(description, "unit"));
			header.frameIntervalSeconds = positiveDouble(description, "finterval", 0);
			if (micronsPerUnit > 0) {
				// Z on its own: older deskew results carry the unit and the spacing but no resolution tags
				header.zCalibrated = true;
				header.pixelDepthUm = positiveDouble(description, "spacing", 1) * micronsPerUnit;
				if (first.xResolution > 0) {
					header.xyCalibrated = true;
					header.pixelWidthUm = micronsPerUnit / first.xResolution;
					header.pixelHeightUm = first.yResolution > 0 ? micronsPerUnit / first.yResolution : header.pixelWidthUm;
				}
			}
		}
		return header;
	}

	/**
	 * The calibration contract of this toolset's own deskew results, which {@link
	 * TiffResultDataset.Layout} has always applied: a result written with a unit and a spacing but
	 * no resolution tags is isotropic at that spacing, because the deskew affine is evaluated on a
	 * grid measured in camera pixels. Applied only to files named as such results; a raw or
	 * renamed file never has X and Y made up from its Z.
	 */
	private static void applyDeskewResultCalibration(Source source) {
		Header header = source.header;
		if (header == null || header.xyCalibrated || !header.zCalibrated) return;
		if (!source.recognisedResult && !source.deskewedName) return;
		header.pixelWidthUm = header.pixelDepthUm;
		header.pixelHeightUm = header.pixelDepthUm;
		header.xyCalibrated = true;
		header.xyFromSpacing = true;
	}

	/** Microns per one of these units, or 0 for a unit that is not a length this knows. */
	private static double micronsPer(String unit) {
		if (unit == null) return 0;
		String u = unit.trim().toLowerCase(Locale.ROOT);
		if (u.equals("micron") || u.equals("microns") || u.equals("um") || u.equals("µm")
				|| u.equals("\\u00b5m") || u.equals("micrometer") || u.equals("micrometre")) return 1;
		if (u.equals("nm") || u.equals("nanometer") || u.equals("nanometre")) return 1e-3;
		if (u.equals("mm") || u.equals("millimeter") || u.equals("millimetre")) return 1e3;
		if (u.equals("cm")) return 1e4;
		return 0;
	}

	private static String value(String description, String key) {
		Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(key) + "=([^\\r\\n]*)").matcher(description);
		return matcher.find() ? matcher.group(1).trim() : null;
	}

	private static int positiveInteger(String description, String key, int fallback) {
		String text = value(description, key);
		if (text == null) return fallback;
		try {
			int parsed = Integer.parseInt(text);
			return parsed > 0 ? parsed : fallback;
		} catch (NumberFormatException notANumber) { return fallback; }
	}

	private static double positiveDouble(String description, String key, double fallback) {
		String text = value(description, key);
		if (text == null) return fallback;
		try {
			double parsed = Double.parseDouble(text);
			return parsed > 0 && !Double.isInfinite(parsed) ? parsed : fallback;
		} catch (NumberFormatException notANumber) { return fallback; }
	}


	// ---- outputs ------------------------------------------------------------------------

	/**
	 * Give every dataset its store and refuse any two that would write the same one.
	 *
	 * @param reproduceTree	: put each store under the output in the same sub-folder its files have
	 * 						  under the scanned root
	 * @param reserved		: stores something else in this run writes, by path, or null
	 */
	void assignOutputs(File outputRoot, File scannedRoot, boolean reproduceTree, Map<String, String> reserved) {
		Map<String, Dataset> taken = new LinkedHashMap<String, Dataset>();
		for (Dataset dataset : datasets) {
			File folder = outputRoot;
			if (reproduceTree && scannedRoot != null && dataset.folder != null)
				folder = BatchProcessingUtils.saveRootFor(new File(dataset.folder, "x.tif"), scannedRoot,
						outputRoot.getAbsolutePath(), false, true);
			dataset.output = new File(folder, dataset.name + ".ome.zarr");
			String key = canonical(dataset.output).toLowerCase(Locale.ROOT);
			Dataset other = taken.get(key);
			if (other != null) {
				dataset.problems.add("writes the same store " + dataset.output.getName() + " as the files in " + other.folder);
				other.problems.add("writes the same store " + other.output.getName() + " as the files in " + dataset.folder);
			} else {
				taken.put(key, dataset);
			}
			String owner = reserved == null ? null : reserved.get(key);
			if (owner != null)
				dataset.problems.add("writes the same store " + dataset.output.getName() + " as " + owner);
		}
	}


	// ---- describing it ------------------------------------------------------------------

	/** One line for the dialog: what the names make, before any file is opened. */
	String preview() {
		if (datasets.isEmpty()) return skipped.isEmpty() ? "" : "OME-Zarr: nothing to store; " + list(skipped) + ".";
		int files = 0, refused = 0;
		for (Dataset dataset : datasets) {
			files += dataset.sources.size();
			if (!dataset.problems.isEmpty() || (!settings.leavesOut() && !dataset.incomplete.isEmpty())) refused++;
		}
		StringBuilder text = new StringBuilder("OME-Zarr: ");
		text.append(datasets.size()).append(" dataset").append(plural(datasets.size()))
				.append(" from ").append(files).append(" TIFF").append(plural(files));
		Dataset first = datasets.get(0);
		text.append(" - ").append(first.name).append(first.processed ? " (deskewed)" : "")
				.append(": T=").append(first.namedTimeCount())
				.append(", C=").append(first.namedChannelCount(settings));
		if (datasets.size() > 1) text.append(", and ").append(datasets.size() - 1).append(" more");
		if (refused > 0) {
			Dataset bad = null;
			for (Dataset dataset : datasets)
				if (!dataset.problems.isEmpty() || (!settings.leavesOut() && !dataset.incomplete.isEmpty())) { bad = dataset; break; }
			String why = bad == null ? "" : !bad.problems.isEmpty() ? bad.problems.get(0)
					: bad.incomplete.size() + " incomplete time point" + plural(bad.incomplete.size());
			text.append(". ").append(refused).append(" would be refused - ")
					.append(bad == null ? "" : bad.name).append(": ").append(why);
		} else {
			int partial = 0;
			for (Dataset dataset : datasets) partial += dataset.incomplete.size();
			if (partial > 0) text.append(". ").append(partial).append(" incomplete time point")
					.append(plural(partial)).append(" left out");
		}
		return text.append('.').toString();
	}

	/** Every dataset, for the run's log: what is written, what is refused, and why. */
	List<String> report() {
		List<String> lines = new ArrayList<String>();
		for (String reason : skipped) lines.add("skip " + reason);
		for (Dataset dataset : datasets) {
			String head = dataset.name + " (" + dataset.sources.size() + " TIFF" + plural(dataset.sources.size())
					+ " in " + dataset.folder + ", " + (dataset.processed ? "deskewed" : "raw") + ")";
			if (dataset.convertible())
				lines.add("plan " + head + " -> " + (dataset.output == null ? "?" : dataset.output.getName())
						+ ": T=" + dataset.timePoints.size() + ", C=" + dataset.channelLabels.size() + " "
						+ dataset.channelLabels + ", " + dataset.width + "x" + dataset.height + "x" + dataset.depth
						+ ", " + dataset.storedLayout + ", " + String.format(Locale.US, "%.6g x %.6g x %.6g um",
								dataset.sampling[0], dataset.sampling[1], dataset.sampling[2])
						+ " from " + dataset.samplingSource);
			else lines.add("refused " + head + ": " + dataset.problemText());
			for (String note : dataset.notes) lines.add("  note " + dataset.name + ": " + note);
		}
		return lines;
	}


	// ---- small helpers ------------------------------------------------------------------

	private static String channelName(int channel) {
		return channel < 0 ? "the file" : String.format(Locale.US, "_Channel%04d", channel);
	}

	private static String channelNames(List<Integer> channels) {
		List<String> names = new ArrayList<String>();
		for (Integer channel : channels) names.add(channelName(channel));
		return join(names, ", ");
	}

	private static String names(List<Source> sources) {
		List<String> names = new ArrayList<String>();
		for (Source source : sources) names.add(source.name);
		return join(names, ", ");
	}

	/** The first few, and how many more. */
	static String list(List<String> items) {
		if (items.size() <= LISTED) return join(items, "; ");
		return join(items.subList(0, LISTED), "; ") + "; and " + (items.size() - LISTED) + " more";
	}

	private static String join(List<String> parts, String separator) {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < parts.size(); i++) text.append(i == 0 ? "" : separator).append(parts.get(i));
		return text.toString();
	}

	private static String plural(int n) {
		return n == 1 ? "" : "s";
	}

	static boolean near(double a, double b) {
		return Math.abs(a - b) <= CALIBRATION_TOLERANCE * Math.max(Math.abs(a), Math.abs(b));
	}

	static String canonical(File file) {
		if (file == null) return "";
		try { return file.getCanonicalPath(); }
		catch (IOException unresolvable) { return file.getAbsolutePath(); }
	}
}
