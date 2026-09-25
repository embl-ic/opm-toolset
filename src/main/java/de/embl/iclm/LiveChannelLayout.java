package de.embl.iclm;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a live acquisition's file names say about its channel layout, and the layout that follows.
 *
 * <p>An acquisition writes either one file per time point - whose channels are then whatever the
 * channel option makes of its camera halves - or several files per time point, one per
 * acquisition channel, which belong in one result. Until now the user had to know which and tick
 * <i>combine matching _Channel#### files</i> accordingly. A wrong tick is expensive both ways: on
 * for a single-file rig, every time point waits for a sibling that never arrives; off for a
 * multi-file one, each channel is written as a result of its own.
 *
 * <p>The names answer the question, and the second announced file is nearly enough to ask it:
 * <ul>
 * <li>the same {@code _ChannelNNNN} at a later {@code _TimeNNNNNN} means one file per time point;
 * <li>a later {@code _ChannelNNNN} at the same {@code _TimeNNNNNN} means several per time point,
 *     and <em>how many</em> is known when the time number finally moves on;
 * <li>both changing at once says neither, and is left to the configured setup.
 * </ul>
 *
 * <p>Deliberately pure: names and a clock, no AWT, no preferences, no disk, which is what lets
 * {@link LiveChannelLayoutTest} put whole acquisitions through it in milliseconds. {@link Live2}
 * holds the files it cannot yet place and releases them in arrival order once it can, so nothing
 * is processed under a layout that the next file would have contradicted.
 */
final class LiveChannelLayout {

	private static final Pattern CHANNEL = Pattern.compile("(?i)_Channel(\\d+)");
	private static final Pattern TIME = Pattern.compile("(?i)_Time(\\d+)");

	/**
	 * How long a held time point waits for the file that would decide it.
	 * <p>
	 * An acquisition of a single time point never announces a second one, and one stopped after
	 * its first channel file never announces the sibling. Both are real - a test snap, an
	 * aborted run - and both would otherwise hold a result back for good. What has arrived when
	 * the quiet period passes is then taken to be all there is.
	 */
	static final long QUIET_MS = 30000;

	/** What the names turned out to say. */
	enum Kind {
		/** One file per time point; its channels come from the channel option. */
		SINGLE_FILE,
		/** Several files per time point, one per acquisition channel, combined into one result. */
		MULTI_FILE,
		/** The names do not say; the configured setup is used unchanged. */
		AMBIGUOUS
	}

	/** The layout an acquisition turned out to have, with the sentence that says why. */
	static final class Decision {
		final Kind kind;
		/** The acquisition channel numbers one time point supplies, ascending; never empty. */
		final int[] acquisitionChannels;
		/** Plain English, for the status panel and the log. */
		final String reason;

		Decision(Kind kind, int[] acquisitionChannels, String reason) {
			this.kind = kind;
			this.acquisitionChannels = acquisitionChannels;
			this.reason = reason;
		}

		boolean combines() { return kind == Kind.MULTI_FILE; }

		int filesPerTimePoint() { return kind == Kind.MULTI_FILE ? acquisitionChannels.length : 1; }
	}

	/** Paths seen so far, so a file announced twice is not counted twice. */
	private final Set<String> seen = new LinkedHashSet<String>();
	/** Channel numbers per time token, so the first time point's file count can be read off. */
	private final Map<String, Set<Integer>> channelsByTime = new LinkedHashMap<String, Set<Integer>>();
	private String firstTime;
	private int firstChannel = -1;
	private long lastObservedMs;
	private Decision decision;

	/**			Take note of one announced file, and decide as soon as the names allow
	 *
	 * @param file				: the file just announced or discovered
	 * @param nowMs				: the wall clock, so the quiet period is testable
	 * <p>
	 * @return					: the decision, or null while a further file is needed
	 */
	Decision observe (
			File file,
			long nowMs
			) {
		if (decision != null) return decision;
		if (file == null || !seen.add(file.getAbsolutePath())) return decision;
		lastObservedMs = nowMs;

		String time = token(TIME, file);
		int channel = number(CHANNEL, file);
		channelsAt(time).add(Integer.valueOf(channel));

		if (seen.size() == 1) {
			firstTime = time;
			firstChannel = channel;
			/* Nothing to combine and nothing to wait for: a name with no channel token has no
			 * siblings, because the group key is then the name itself. */
			if (channel < 0) return decide(Kind.SINGLE_FILE, new int[] { 1 },
					"the file name carries no _Channel####, so one file is one time point");
			return null;
		}

		boolean sameTime = equal(time, firstTime);
		int filesAtFirstTime = channelsAt(firstTime).size();
		if (!sameTime) {
			/* The time number has moved on, which closes the first time point: whatever it
			 * collected is what one time point holds. */
			if (filesAtFirstTime > 1) {
				int[] channels = channelsOfFirstTimePoint();
				return decide(Kind.MULTI_FILE, channels, "time point " + label(firstTime)
						+ " was announced as " + channels.length + " files, one per channel, "
						+ "before the time number moved on");
			}
			if (channel == firstChannel)
				return decide(Kind.SINGLE_FILE, new int[] { Math.max(1, firstChannel) },
						"the next file repeats " + channelToken(firstChannel) + " at a later time point");
			return decide(Kind.AMBIGUOUS, new int[] { Math.max(1, firstChannel) },
					"the next file changes its channel and its time number at once");
		}
		// another channel of the same time point; how many there are needs the next time point
		return decision;
	}

	/**			Decide on what has arrived, once nothing more has for {@link #QUIET_MS}
	 *
	 * @param nowMs				: the wall clock
	 * <p>
	 * @return					: the decision, or null while the quiet period has not passed
	 */
	Decision decideIfQuiet (
			long nowMs
			) {
		if (decision != null) return decision;
		if (seen.isEmpty() || nowMs - lastObservedMs < QUIET_MS) return null;
		String quiet = "nothing further arrived for " + (QUIET_MS / 1000) + " s, so ";
		int[] channels = channelsOfFirstTimePoint();
		if (channels.length > 1)
			return decide(Kind.MULTI_FILE, channels,
					quiet + "this time point's " + channels.length + " files are all of it");
		return decide(Kind.SINGLE_FILE, channels, quiet + "one file is one time point");
	}

	/** The decision, or null while there is not one yet. */
	Decision decision () { return decision; }

	/** How many files have been seen, for the status line while it is still holding. */
	int observedCount () { return seen.size(); }

	private Set<Integer> channelsAt (String time) {
		String key = time == null ? "" : time.toLowerCase(Locale.ROOT);
		Set<Integer> found = channelsByTime.get(key);
		if (found == null) {
			found = new LinkedHashSet<Integer>();
			channelsByTime.put(key, found);
		}
		return found;
	}

	/** The channel numbers the first time point supplied, ascending and gap-free from one. */
	private int[] channelsOfFirstTimePoint () {
		int highest = 0;
		for (Integer channel : channelsAt(firstTime)) highest = Math.max(highest, channel.intValue());
		if (highest <= 0) return new int[] { 1 };
		/* Sequential up to the highest seen, as requiredAcquisitionChannels is: the canonical
		 * OME-Zarr writer rejects a time point whose channel set is not the expected one, and a
		 * gap in the numbering is a missing file rather than a layout. */
		int[] channels = new int[Math.min(highest, BatchChannelOperation.MAX_ACQUISITION_CHANNELS)];
		for (int i = 0; i < channels.length; i++) channels[i] = i + 1;
		return channels;
	}

	private Decision decide (Kind kind, int[] channels, String reason) {
		decision = new Decision(kind, channels, reason);
		return decision;
	}

	private static String label (String time) { return time == null ? "the first" : time; }

	private static String channelToken (int channel) {
		return channel < 0 ? "no channel" : String.format(Locale.US, "_Channel%04d", channel);
	}

	private static boolean equal (String a, String b) { return a == null ? b == null : a.equals(b); }

	private static String token (Pattern pattern, File file) {
		Matcher matcher = pattern.matcher(BatchProcessingUtils.baseName(file));
		return matcher.find() ? matcher.group() : null;
	}

	private static int number (Pattern pattern, File file) {
		Matcher matcher = pattern.matcher(BatchProcessingUtils.baseName(file));
		if (!matcher.find()) return -1;
		try {
			return Integer.parseInt(matcher.group(1));
		} catch (NumberFormatException unparsable) {
			return -1;
		}
	}


	// ---- automatic channel assignment ------------------------------------------------

	/**			The output channels a decided layout produces under a given channel option
	 * <p>		One entry per output channel, in the order they appear in the result:
	 * 			acquisition channel first, left half before right. What one acquisition channel
	 * 			contributes is the channel option's business and only the option knows it - a
	 * 			whole image is one output channel of the full camera width, a folded or aligned
	 * 			one is its two halves, and asking for one side is that half alone. The mapping is
	 * 			{@link DeskewChannelView}'s, which is how a single file's channels are already
	 * 			read back from a finished result.
	 *
	 * @param channelOption		: one of {@link Parameter#CHANNEL_OPTIONS}
	 * @param acquisitionChannels	: the channel numbers one time point supplies
	 * <p>
	 * @return					: source keys for {@link ChannelOperationSettings#channelOrder}
	 */
	static List<String> outputSources (
			String channelOption,
			int[] acquisitionChannels
			) {
		List<String> sources = new ArrayList<String>();
		if (acquisitionChannels == null) return sources;
		for (int channel : acquisitionChannels) {
			if (channel < 1 || channel > BatchChannelOperation.MAX_ACQUISITION_CHANNELS) continue;
			if ("whole image".equals(channelOption))
				sources.add(ChannelOperationSettings.wholeSourceKey(channel));
			else if ("only left".equals(channelOption))
				sources.add(ChannelOperationSettings.sourceKey(channel, true));
			else if ("only right".equals(channelOption))
				sources.add(ChannelOperationSettings.sourceKey(channel, false));
			else {
				// folded, SIFT aligned, or kept apart: both halves, left before right
				sources.add(ChannelOperationSettings.sourceKey(channel, true));
				sources.add(ChannelOperationSettings.sourceKey(channel, false));
			}
		}
		while (sources.size() > BatchChannelOperation.MAX_OUTPUT_CHANNELS)
			sources.remove(sources.size() - 1);
		return sources;
	}

	/**			Write a decided layout into the shared channel settings
	 * <p>		Everything the user can no longer reach while auto assignment is on is written
	 * 			here, so the greyed controls say what the run is doing rather than what was last
	 * 			typed: whether the files are combined, which half is flipped, and the slots. The
	 * 			right half is the flipped one - this toolset's convention for a single file, and
	 * 			what {@code Deskew.process} does whatever else is configured.
	 *
	 * @param channels			: the settings to write; its other fields are left alone
	 * @param channelOption		: one of {@link Parameter#CHANNEL_OPTIONS}
	 * @param decision			: the layout the names decided
	 * <p>
	 * @return					: what was assigned, for the log and the status panel
	 */
	static String apply (
			ChannelOperationSettings channels,
			String channelOption,
			Decision decision
			) {
		if (channels == null || decision == null || decision.kind == Kind.AMBIGUOUS) return "";
		channels.combineAcquisitionChannels = decision.combines();
		channels.flipHalf = BatchChannelOperation.FLIP_RIGHT;
		/* The slots are filled for a single-file layout too, although only a combined run reads
		 * them to build its result. They are what the greyed rows show, and what
		 * requiredAcquisitionChannels answers with - which the canonical OME-Zarr writer asks
		 * whether the files are combined or not. */
		List<String> sources = outputSources(channelOption, decision.acquisitionChannels);
		for (int slot = 0; slot < channels.channelOrder.length; slot++)
			channels.channelOrder[slot] = slot < sources.size()
					? sources.get(slot) : BatchChannelOperation.SKIP_CHANNEL;
		StringBuilder text = new StringBuilder();
		for (String source : sources) {
			if (text.length() > 0) text.append(", ");
			text.append(source);
		}
		return (decision.combines()
				? decision.acquisitionChannels.length + " files per time point"
				: "one file per time point") + ", \"" + channelOption + "\" as " + text;
	}
}
