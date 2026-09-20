package de.embl.iclm;

import ij.IJ;
import ij.ImagePlus;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Shared raw-timepoint preparation for converter, Batch and Live OME-Zarr output. */
public final class OpmTimepointProcessor {

	private static final Pattern CHANNEL = Pattern.compile("(?i)_Channel\\d+");

	private OpmTimepointProcessor() { }

	/** One complete acquisition time point, with exactly one file per acquisition channel. */
	public static final class TimePoint {
		public final String label;
		public final List<File> files;
		public final double elapsedSeconds;

		TimePoint(String label, List<File> files, double elapsedSeconds) {
			this.label = label;
			this.files = Collections.unmodifiableList(new ArrayList<File>(files));
			this.elapsedSeconds = elapsedSeconds;
		}
	}

	/** Deskewed, unmirrored, unaligned left/right channels owned by the caller. */
	public static final class Result implements AutoCloseable {
		public final List<ImagePlus> channels = new ArrayList<ImagePlus>();
		public final List<String> channelLabels = new ArrayList<String>();
		public boolean usedGpu;

		@Override
		public void close() {
			for (ImagePlus image : channels) BatchProcessingUtils.close(image);
			channels.clear();
		}
	}

	/**
	 * Deterministically group files and keep only groups with the exact expected channel set.
	 *
	 * <p>The expected set is the widest set observed; when several equally wide signatures
	 * occur, the most frequent wins. This rejects both missing and duplicate channel files and
	 * avoids treating the first, still-arriving live time point as the format definition.
	 */
	public static List<TimePoint> completeTimePoints(
			List<File> inputFiles, double frameIntervalSeconds) {
		List<File> files = new ArrayList<File>();
		if (inputFiles != null) for (File file : inputFiles) if (file != null) files.add(file);
		Collections.sort(files, FILE_ORDER);

		Map<String, List<File>> groups = new TreeMap<String, List<File>>(NATURAL_TEXT);
		for (File file : files) {
			String key = BatchProcessingUtils.channelGroupKey(file);
			List<File> group = groups.get(key);
			if (group == null) { group = new ArrayList<File>(); groups.put(key, group); }
			group.add(file);
		}
		for (List<File> group : groups.values()) ChannelOperationSettings.sortByAcquisitionChannel(group);

		Map<String, Integer> frequencies = new LinkedHashMap<String, Integer>();
		Map<String, Set<Integer>> signatures = new LinkedHashMap<String, Set<Integer>>();
		for (Map.Entry<String, List<File>> entry : groups.entrySet()) {
			Set<Integer> channels = channelSet(entry.getValue());
			String signature = signature(channels);
			signatures.put(entry.getKey(), channels);
			frequencies.put(signature, Integer.valueOf(frequencies.containsKey(signature)
					? frequencies.get(signature).intValue() + 1 : 1));
		}
		Set<Integer> expected = new LinkedHashSet<Integer>();
		int bestWidth = -1, bestFrequency = -1;
		for (Map.Entry<String, Integer> entry : frequencies.entrySet()) {
			Set<Integer> candidate = parseSignature(entry.getKey());
			int width = candidate.size(), frequency = entry.getValue().intValue();
			if (width > bestWidth || (width == bestWidth && frequency > bestFrequency)) {
				expected = candidate;
				bestWidth = width;
				bestFrequency = frequency;
			}
		}

		List<TimePoint> result = new ArrayList<TimePoint>();
		long firstTimestamp = -1;
		for (Map.Entry<String, List<File>> entry : groups.entrySet()) {
			List<File> group = entry.getValue();
			Set<Integer> present = signatures.get(entry.getKey());
			boolean duplicate = present.size() != group.size();
			if (duplicate || !present.equals(expected)) {
				IJ.log("OPM Zarr: skipping incomplete/ambiguous time point " + timeLabel(group)
						+ "; channels " + present + ", expected exactly " + expected);
				continue;
			}
			long timestamp = earliestModified(group);
			if (firstTimestamp < 0) firstTimestamp = timestamp;
			double elapsed = timestamp >= firstTimestamp ? (timestamp - firstTimestamp) / 1000.0
					: result.size() * Math.max(0, frameIntervalSeconds);
			if (elapsed == 0 && result.size() > 0 && frameIntervalSeconds > 0)
				elapsed = result.size() * frameIntervalSeconds;
			result.add(new TimePoint(timeLabel(group), group, elapsed));
		}
		return result;
	}

	/** True only when the group contains each required channel once and no extra channel. */
	public static boolean hasExactChannels(List<File> group, int[] requiredChannels) {
		if (group == null || requiredChannels == null || group.size() != requiredChannels.length) return false;
		Set<Integer> actual = channelSet(group);
		if (actual.size() != group.size()) return false;
		Set<Integer> expected = new LinkedHashSet<Integer>();
		for (int channel : requiredChannels) expected.add(Integer.valueOf(channel));
		return actual.equals(expected);
	}

	/** Ordered stored-channel labels without opening pixel data. */
	public static List<String> channelLabels(TimePoint timePoint) {
		if (timePoint == null) throw new IllegalArgumentException("A time point is required.");
		List<String> labels = new ArrayList<String>();
		int fallbackChannel = 0;
		for (File file : timePoint.files) {
			fallbackChannel++;
			int acquisition = BatchProcessingUtils.acquisitionChannel(file);
			if (acquisition < 0) acquisition = fallbackChannel;
			labels.add(ChannelOperationSettings.sourceKey(acquisition, true));
			labels.add(ChannelOperationSettings.sourceKey(acquisition, false));
		}
		return labels;
	}

	/** Consumer used by the bounded-memory writer path; the image is valid only during the call. */
	interface ChannelSink {
		void accept(ImagePlus image, String label, int channelIndex, boolean usedGpu) throws Exception;
	}

	/**
	 * Process one half at a time and release it as soon as the sink returns.
	 * This is the production path for large Batch and Live acquisitions.
	 */
	static boolean processChannels(
			TimePoint timePoint, double[][] deskewMatrix, boolean tryGpu, ChannelSink sink) throws Exception {
		if (sink == null) throw new IllegalArgumentException("A channel sink is required.");
		Result result = processInternal(timePoint, deskewMatrix, tryGpu, sink);
		try {
			return result.usedGpu;
		} finally {
			result.close();
		}
	}

	/** Split every raw file and deskew both halves without mirroring or applying alignment. */
	public static Result process(
			TimePoint timePoint, double[][] deskewMatrix, boolean tryGpu) {
		try {
			return processInternal(timePoint, deskewMatrix, tryGpu, null);
		} catch (RuntimeException failure) {
			throw failure;
		} catch (Exception failure) {
			throw new RuntimeException(failure);
		}
	}

	private static Result processInternal(
			TimePoint timePoint, double[][] deskewMatrix, boolean tryGpu, ChannelSink sink) throws Exception {
		if (timePoint == null || timePoint.files.isEmpty())
			throw new IllegalArgumentException("A non-empty time point is required.");
		Result result = new Result();
		try {
			int fallbackChannel = 0;
			int expectedWidth = -1, expectedHeight = -1, expectedDepth = -1;
			for (File file : timePoint.files) {
				fallbackChannel++;
				int acquisition = BatchProcessingUtils.acquisitionChannel(file);
				if (acquisition < 0) acquisition = fallbackChannel;
				ImagePlus raw = VolumeIO.open(file.getAbsolutePath());
				if (raw == null) throw new IllegalStateException("Could not read " + file);
				ImagePlus[] halves = null;
				try {
					halves = Partition.separateImageLeftRight(raw, "left & right separately", true);
					if (halves == null || halves.length != 2)
						throw new IllegalStateException("Could not split left/right camera halves in " + file);
					BatchProcessingUtils.close(raw);
					raw = null;
					for (int side = 0; side < 2; side++) {
						ImagePlus deskewed = null;
						boolean channelUsedGpu = false;
						long deskewStart = System.nanoTime();
						if (tryGpu) {
							try { deskewed = GPU.transform(halves[side], Transform.copy(deskewMatrix)); }
							catch (Throwable gpuFailure) {
								IJ.log("OPM Zarr: GPU deskew failed for " + file.getName()
										+ "; using CPU: " + gpuFailure.getMessage());
							}
							if (deskewed != null) channelUsedGpu = result.usedGpu = true;
						}
						if (deskewed == null) deskewed = CPU.transform(halves[side], Transform.copy(deskewMatrix));
						if (deskewed == null) throw new IllegalStateException("Deskew failed for " + file);
						/* The same throughput line Deskew.deskew_image prints. This path calls
						 * GPU.transform directly, so an OME-Zarr run used to produce no deskew
						 * report at all and there was no way to see whether the GPU was in use. */
						reportDeskewRate(file, side, halves[side], channelUsedGpu,
								(System.nanoTime() - deskewStart) / 1.0e6);
						// The source half is no longer needed once deskew returns. Release it before
						// projections temporarily allocate anything else for this output volume.
						BatchProcessingUtils.close(halves[side]);
						halves[side] = null;
						if (expectedWidth < 0) {
							expectedWidth = deskewed.getWidth();
							expectedHeight = deskewed.getHeight();
							expectedDepth = deskewed.getStackSize();
						} else if (deskewed.getWidth() != expectedWidth || deskewed.getHeight() != expectedHeight
								|| deskewed.getStackSize() != expectedDepth) {
							BatchProcessingUtils.close(deskewed);
							throw new IllegalArgumentException("Deskewed channel dimensions changed within "
									+ timePoint.label);
						}
						String label = ChannelOperationSettings.sourceKey(acquisition, side == 0);
						deskewed.setTitle(label);
						if (sink == null) {
							result.channels.add(deskewed);
							result.channelLabels.add(label);
							deskewed = null; // ownership transferred to Result
						} else {
							try {
								sink.accept(deskewed, label, result.channelLabels.size(), channelUsedGpu);
								result.channelLabels.add(label);
							} finally {
								BatchProcessingUtils.close(deskewed);
							}
						}
					}
				} finally {
					if (halves != null) for (ImagePlus half : halves) BatchProcessingUtils.close(half);
					BatchProcessingUtils.close(raw);
				}
			}
			return result;
		} catch (Throwable failure) {
			result.close();
			if (failure instanceof Exception) throw (Exception) failure;
			if (failure instanceof Error) throw (Error) failure;
			throw new RuntimeException(failure);
		}
	}

	/**			Print one deskew throughput line per camera half
	 * <p>		Matches what the interactive and TIFF paths already print, so the console reads
	 * 			the same whichever output format a run was configured for.
	 *
	 * @param file				: the raw file the half came from
	 * @param side				: 0 for the left half, 1 for the right
	 * @param source			: the half that was transformed, for its pixel count
	 * @param usedGpu			: whether CLIJ did the transform
	 * @param durationMs		: wall time the transform took
	 */
	private static void reportDeskewRate(
			File file, int side, ImagePlus source, boolean usedGpu, double durationMs) {
		if (source == null) return;
		double pixels = (double) source.getWidth() * source.getHeight() * source.getStackSize();
		double rate = durationMs <= 0 ? 0 : pixels / durationMs / 1000.0;
		System.out.printf(Locale.US, "%n	deskew %s %s on %s in %.1f ms, ~ %.1fk pixels per ms.%n",
				file.getName(), side == 0 ? "left" : "right", usedGpu ? "GPU" : "CPU",
				Double.valueOf(durationMs), Double.valueOf(rate));
	}

	public static String timeLabel(List<File> group) {
		if (group == null || group.isEmpty()) return "timepoint";
		return CHANNEL.matcher(BatchProcessingUtils.baseName(group.get(0))).replaceFirst("_Channel####");
	}

	private static Set<Integer> channelSet(List<File> group) {
		Set<Integer> set = new LinkedHashSet<Integer>();
		int fallback = 0;
		for (File file : group) {
			fallback++;
			int channel = BatchProcessingUtils.acquisitionChannel(file);
			set.add(Integer.valueOf(channel < 0 ? fallback : channel));
		}
		return set;
	}

	private static String signature(Set<Integer> channels) {
		List<Integer> sorted = new ArrayList<Integer>(channels);
		Collections.sort(sorted);
		StringBuilder text = new StringBuilder();
		for (Integer channel : sorted) {
			if (text.length() > 0) text.append(',');
			text.append(channel.intValue());
		}
		return text.toString();
	}

	private static Set<Integer> parseSignature(String signature) {
		Set<Integer> channels = new LinkedHashSet<Integer>();
		if (signature == null || signature.isEmpty()) return channels;
		for (String value : signature.split(",")) channels.add(Integer.valueOf(value));
		return channels;
	}

	private static long earliestModified(List<File> files) {
		long earliest = Long.MAX_VALUE;
		for (File file : files) if (file.lastModified() > 0) earliest = Math.min(earliest, file.lastModified());
		return earliest == Long.MAX_VALUE ? 0 : earliest;
	}

	private static final Comparator<File> FILE_ORDER = new Comparator<File>() {
		@Override public int compare(File a, File b) {
			return NATURAL_TEXT.compare(a.getAbsolutePath(), b.getAbsolutePath());
		}
	};

	private static final Comparator<String> NATURAL_TEXT = new Comparator<String>() {
		@Override public int compare(String a, String b) {
			int ia = 0, ib = 0;
			while (ia < a.length() && ib < b.length()) {
				char ca = a.charAt(ia), cb = b.charAt(ib);
				if (Character.isDigit(ca) && Character.isDigit(cb)) {
					int ea = ia, eb = ib;
					while (ea < a.length() && Character.isDigit(a.charAt(ea))) ea++;
					while (eb < b.length() && Character.isDigit(b.charAt(eb))) eb++;
					String na = a.substring(ia, ea).replaceFirst("^0+(?!$)", "");
					String nb = b.substring(ib, eb).replaceFirst("^0+(?!$)", "");
					if (na.length() != nb.length()) return na.length() < nb.length() ? -1 : 1;
					int number = na.compareTo(nb);
					if (number != 0) return number;
					ia = ea; ib = eb; continue;
				}
				int compare = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
				if (compare != 0) return compare;
				ia++; ib++;
			}
			return Integer.compare(a.length(), b.length());
		}
	};
}
