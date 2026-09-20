package de.embl.iclm;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How long the disks a run is writing to will last at the rate that run is filling them.
 *
 * <p>An OPM acquisition writes at a rate that makes free space a per-hour problem rather than
 * a per-week one: a single time point of a two-colour acquisition is roughly three gigabytes
 * of raw camera data and a comparable amount of deskewed result. A run that quietly stops
 * after twelve hours because the volume filled has lost the tail of the experiment, and the
 * only warning was a number nobody was watching.
 *
 * <p>The estimate is deliberately measured rather than predicted. Nothing here knows how big a
 * time point is, whether the raw files land on the same volume as the results, or whether
 * something else on the machine is also writing; it samples free space, and the slope of that
 * is the truth about all three at once. The cost is that an estimate needs two samples a
 * little apart, so the first minute of a run reports free space and no forecast.
 *
 * <p>Volumes are tracked by their file-system root, so watching an input folder and an output
 * folder that happen to live on the same disk produces one line, not two - and produces the
 * right slope, because both are filling it.
 */
public final class DiskSpace {

	/** Below this many hours of headroom, say so loudly. */
	public static final double CRITICAL_HOURS = 1.0;
	/**
	 * Below this many hours of headroom, mention it.
	 * <p>
	 * A warning means one thing only: the free space left is not enough for four more hours
	 * <em>at the rate this run is actually filling the disk</em>. It is therefore never raised
	 * before a rate has been measured - an idle disk with little space is not a run that is
	 * about to fail, and a warning that fires on a number rather than on a trend is one users
	 * learn to ignore.
	 */
	public static final double WARNING_HOURS = 4.0;
	/**
	 * Below this much free space, report CRITICAL whatever the rate says.
	 * <p>
	 * Deliberately kept as a floor on the critical level only, never on the warning level: a
	 * volume with a couple of gigabytes left cannot absorb one more time point, and that is
	 * true during the first half minute of a run when no trend exists yet.
	 */
	public static final long CRITICAL_FREE_BYTES = 5L * 1024 * 1024 * 1024;

	private static final double GB = 1024.0 * 1024.0 * 1024.0;
	/** Rates computed over less than this are noise, not a trend. */
	private static final long MINIMUM_SPAN_MS = 30000;

	/** How urgent a volume's remaining headroom is. */
	public enum Level {
		OK, WARNING, CRITICAL
	}

	/** One volume's free space, the rate it is being consumed, and what that implies. */
	public static final class Report {
		public final String volume;
		public final long freeBytes;
		public final long totalBytes;
		/** Bytes consumed per second; zero or negative when the volume is not shrinking. */
		public final double bytesPerSecond;
		/** Hours until full at that rate, or {@link Double#POSITIVE_INFINITY} when not shrinking. */
		public final double hoursRemaining;

		Report(String volume, long freeBytes, long totalBytes, double bytesPerSecond) {
			this.volume = volume;
			this.freeBytes = freeBytes;
			this.totalBytes = totalBytes;
			this.bytesPerSecond = bytesPerSecond;
			this.hoursRemaining = bytesPerSecond > 0
					? freeBytes / bytesPerSecond / 3600.0 : Double.POSITIVE_INFINITY;
		}

		public Level level() {
			if (freeBytes < CRITICAL_FREE_BYTES) return Level.CRITICAL;
			if (hoursRemaining < CRITICAL_HOURS) return Level.CRITICAL;
			if (hoursRemaining < WARNING_HOURS) return Level.WARNING;
			return Level.OK;
		}

		/** Whether a rate has been measured yet; before that only free space is known. */
		public boolean hasRate() {
			return bytesPerSecond > 0;
		}

		/** One line for the status panel and the log. */
		public String format() {
			StringBuilder text = new StringBuilder();
			text.append(volume).append(' ')
					.append(String.format(Locale.US, "%.1f GB free", freeBytes / GB));
			if (hasRate()) {
				text.append(String.format(Locale.US, ", filling at %.2f GB/min",
						bytesPerSecond * 60.0 / GB));
				text.append(", full in ").append(formatHours(hoursRemaining));
			} else {
				text.append(", rate not measured yet");
			}
			/* The marker is appended whether or not a rate is known. A volume with two
			 * gigabytes left is alarming during the first minute of a run too, and that is
			 * exactly when no trend exists yet. */
			if (level() == Level.CRITICAL) text.append("  <-- DISK ALMOST FULL");
			else if (level() == Level.WARNING) text.append("  <-- low");
			return text.toString();
		}

		@Override
		public String toString() { return format(); }
	}

	/** A remaining time a person can act on: minutes when it is short, hours when it is not. */
	public static String formatHours(double hours) {
		if (Double.isInfinite(hours) || hours > 24 * 365) return "not at this rate";
		if (hours < 1) return String.format(Locale.US, "%.0f min", Math.max(1, hours * 60));
		if (hours < 48) return String.format(Locale.US, "%.1f h", hours);
		return String.format(Locale.US, "%.0f days", hours / 24);
	}

	private static final class Volume {
		final String name;
		final File probe;
		long firstMillis;
		long firstFree;
		long latestMillis;
		long latestFree;
		long totalBytes;

		Volume(String name, File probe) {
			this.name = name;
			this.probe = probe;
		}
	}

	private final Map<String, Volume> volumes = new LinkedHashMap<String, Volume>();

	/**			Track the volume a folder lives on
	 * <p>		Idempotent, and safe to call with a folder that does not exist yet: the nearest
	 * 			existing ancestor is probed instead, which is the same file system. Output
	 * 			folders are routinely created only when the first result is written.
	 *
	 * @param folder			: any path on the volume to watch
	 */
	public synchronized void watch (
			File folder
			) {
		File probe = nearestExisting ( folder );
		if (probe == null) return;
		String name = volumeName ( probe );
		if (volumes.containsKey ( name )) return;
		Volume volume = new Volume ( name, probe );
		volumes.put ( name, volume );
		read ( volume );
		volume.firstMillis = volume.latestMillis;
		volume.firstFree = volume.latestFree;
	}

	/** Take a reading of every tracked volume; call once per finished unit of work. */
	public synchronized void sample () {
		for (Volume volume : volumes.values()) read ( volume );
	}

	/** Current state of every tracked volume, in the order they were added. */
	public synchronized List<Report> reports () {
		List<Report> reports = new ArrayList<Report>();
		for (Volume volume : volumes.values()) reports.add ( report ( volume ) );
		return reports;
	}

	/**			The state of the volume one path lives on
	 * <p>		For a status line that names a folder - where the raw data arrives, where the
	 * 			results go - rather than listing every volume the run touches. Two paths on one
	 * 			disk report the same volume, with the slope both are contributing to.
	 *
	 * @param path				: any path, existing or not yet created
	 * @return					: that volume's report, or null when it is not being tracked
	 */
	public synchronized Report reportFor (
			File path
			) {
		File probe = nearestExisting ( path );
		if (probe == null) return null;
		Volume volume = volumes.get ( volumeName ( probe ) );
		return volume == null ? null : report ( volume );
	}

	/** The volume that will run out first, or null when nothing is tracked. */
	public synchronized Report worst () {
		Report worst = null;
		for (Volume volume : volumes.values()) {
			Report report = report ( volume );
			if (worst == null || report.hoursRemaining < worst.hoursRemaining) worst = report;
		}
		return worst;
	}

	/** Everything on one line, for the status panel. */
	public synchronized String summary () {
		List<Report> reports = reports();
		if (reports.isEmpty()) return "not measured";
		StringBuilder text = new StringBuilder();
		for (Report report : reports) {
			if (text.length() > 0) text.append("; ");
			text.append ( report.format() );
		}
		return text.toString();
	}

	/** Forget every volume and every sample, for a new run. */
	public synchronized void reset () {
		volumes.clear();
	}

	private static void read (Volume volume) {
		volume.latestMillis = System.currentTimeMillis();
		volume.latestFree = volume.probe.getUsableSpace();
		volume.totalBytes = volume.probe.getTotalSpace();
	}

	private static Report report (Volume volume) {
		long span = volume.latestMillis - volume.firstMillis;
		long consumed = volume.firstFree - volume.latestFree;
		// a short span or a volume that grew again says nothing about a trend
		double rate = span >= MINIMUM_SPAN_MS && consumed > 0
				? consumed * 1000.0 / span : 0;
		return new Report ( volume.name, volume.latestFree, volume.totalBytes, rate );
	}

	/**			The first existing folder at or above this one
	 * <p>		Free space cannot be read from a path that does not exist, and an output folder
	 * 			normally does not exist until the first result is written.
	 */
	static File nearestExisting (
			File folder
			) {
		File candidate = folder;
		try {
			if (candidate != null) candidate = candidate.getAbsoluteFile();
		} catch (Throwable unusable) {
			return null;
		}
		while (candidate != null && !candidate.exists()) candidate = candidate.getParentFile();
		return candidate;
	}

	/** The file-system root a path sits on, so two folders on one disk are counted once. */
	static String volumeName (
			File path
			) {
		try {
			java.nio.file.Path root = path.getCanonicalFile().toPath().getRoot();
			if (root != null) return root.toString();
		} catch (IOException unresolved) {
			// fall through to the absolute path's root
		}
		java.nio.file.Path root = path.getAbsoluteFile().toPath().getRoot();
		return root == null ? path.getAbsolutePath() : root.toString();
	}
}
