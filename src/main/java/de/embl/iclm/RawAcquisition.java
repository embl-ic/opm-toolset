package de.embl.iclm;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The raw acquisition files of one folder, as the Online Deskew View sees them: which file holds
 * which position, time point and acquisition channel, and whether it can be shown (joint
 * contracts C2.1 - C2.6).
 *
 * <p>The acquisition names its files {@code <name>_Position<P>_Time<T>_Channel<C>_Frames_<a>_<b>}
 * and writes them under that final name (C2.4). The list of files is fixed when the folder is
 * scanned; following a growing folder is a later phase. Each file is <em>validated</em> once,
 * lazily: the TIFF structure has to be complete ({@link TiffCompletionCheck#isReady}), the layout
 * has to suit the direct reader ({@link RawTiffLayout}), and the number of planes has to equal
 * the {@code _Frames_a_b} count, {@code b - a + 1}. A structurally valid file can still be a
 * volume caught mid-write - the plane count is what tells them apart.
 *
 * <p>Time points are identified by position and the acquisition's own time number, never by
 * their index in a list, and ordered numerically, so {@code _Time000010} follows
 * {@code _Time000009} (C2.1). A folder whose files carry no {@code _Time} token at all is read
 * as one time point per file, in name order, and says so.
 *
 * <p><b>A name token is absent, valid or invalid, and the three are kept apart.</b> A file with no
 * {@code _Frames} token can be opened by hand with its depth unverified; one whose token is
 * reversed, malformed or too large is refused with that reason, never quietly treated as having
 * none. A {@code _Position}, {@code _Time} or {@code _Channel} number too large to be one, or a
 * name carrying two different values of a token, cannot be placed: the file is listed under
 * {@link #problems()} and the rest of the folder opens as usual. Two files claiming the same
 * position, time point and channel are both reported, and that slot is shown from neither.
 *
 * <p><b>A file that changes after it was checked</b> is {@link Readiness#CHANGED}: the raw store
 * notices it when it reads ({@link #markChanged}), and the view withholds it rather than mixing
 * pixels of two versions. Checking it again is a reopen of the folder in this phase.
 */
final class RawAcquisition {

	enum Readiness {
		/** Not checked yet. */
		PENDING,
		/** Complete: structure valid and the plane count the name promises. */
		COMPLETE,
		/** Structure valid, but the name states no plane count to check against. */
		DEPTH_UNVERIFIED,
		/** Fewer planes than promised, or the TIFF structure not complete. */
		INCOMPLETE,
		/** Unreadable, unsupported, more planes than the name says, or an invalid name. */
		FAILED,
		/** Changed, replaced or deleted after it was checked; withheld until the folder is reopened. */
		CHANGED
	}

	/** A position and the acquisition's own time number. */
	static final class TimeKey implements Comparable<TimeKey> {
		final int position;
		final int timeId;

		TimeKey(int position, int timeId) {
			this.position = position;
			this.timeId = timeId;
		}

		@Override public int compareTo(TimeKey o) {
			return position != o.position ? Integer.compare(position, o.position) : Integer.compare(timeId, o.timeId);
		}

		@Override public boolean equals(Object o) {
			return o instanceof TimeKey && ((TimeKey) o).position == position && ((TimeKey) o).timeId == timeId;
		}

		@Override public int hashCode() {
			return 31 * position + timeId;
		}

		@Override public String toString() {
			return String.format(Locale.US, "Position%04d Time%06d", position, timeId);
		}
	}

	/**
	 * One version of one file - its generation: path, acquisition channel, and the size,
	 * modification time and creation time it had when it was validated, read in one call. A file
	 * that is appended to, rewritten, truncated or replaced is a different key, so pixels cached
	 * for one version are never served for another (C2.6, C3.8).
	 *
	 * <p>What the operating system reports is all there is to go on: a rewrite of the same size
	 * that leaves no new modification time cannot be told apart. The acquisition writes each file
	 * once under its final name (C2.4), so what matters in practice - a file replaced, cut or still
	 * growing - is always seen.
	 */
	static final class FileKey {
		final File file;
		final int channel;
		final long size;
		final long modified;
		final long created;

		FileKey(File file, int channel, long size, long modified, long created) {
			this.file = file;
			this.channel = channel;
			this.size = size;
			this.modified = modified;
			this.created = created;
		}

		/** The file as it is on disk now, or null when it is gone or not a regular file. */
		static FileKey of(File file, int channel) {
			try {
				BasicFileAttributes a = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
				return a.isRegularFile() ? new FileKey(file, channel, a.size(), a.lastModifiedTime().toMillis(),
						a.creationTime().toMillis()) : null;
			} catch (IOException | RuntimeException gone) {
				return null;
			}
		}

		/** Whether the file on disk is still this version. */
		boolean current() {
			return equals(of(file, channel));
		}

		/** Why the file is no longer this version, for the status line. */
		String change() {
			FileKey now = of(file, channel);
			if (now == null) return "deleted or no longer readable";
			if (now.created != created) return "replaced by another file";
			if (now.size != size) return "its size changed from " + size + " to " + now.size + " bytes";
			if (now.modified != modified) return "rewritten (modification time changed)";
			return "unchanged";
		}

		@Override public boolean equals(Object o) {
			if (!(o instanceof FileKey)) return false;
			FileKey k = (FileKey) o;
			return k.channel == channel && k.size == size && k.modified == modified && k.created == created
					&& k.file.equals(file);
		}

		@Override public int hashCode() {
			return file.hashCode() * 31 + Long.hashCode(size) * 17 + Long.hashCode(modified) * 7
					+ Long.hashCode(created) + channel;
		}

		@Override public String toString() {
			return file.getName();
		}
	}

	/** One file of the acquisition and, once checked, whether it can be shown. */
	static final class Entry {
		final TimeKey time;
		final int channel;
		final File file;
		/** Planes promised by {@code _Frames_a_b}, or -1 when the name states none or an invalid one. */
		final int expectedDepth;
		/** Why the name's plane count cannot be used, or null when it is valid or absent. */
		private volatile String nameProblem;

		private volatile Readiness readiness = Readiness.PENDING;
		private volatile String detail = "";
		private volatile RawTiffLayout layout;
		private volatile FileKey key;

		Entry(TimeKey time, int channel, File file, int expectedDepth, String nameProblem) {
			this.time = time;
			this.channel = channel;
			this.file = file;
			this.expectedDepth = expectedDepth;
			this.nameProblem = nameProblem;
		}

		/** Check the file once; later calls return the stored outcome. */
		synchronized Readiness validate() {
			if (readiness != Readiness.PENDING) return readiness;
			FileKey before = FileKey.of(file, channel);
			RawTiffLayout found = null;
			Readiness outcome;
			String why;
			if (nameProblem != null) {
				outcome = Readiness.FAILED;
				why = nameProblem;
			} else if (before == null) {
				outcome = Readiness.FAILED;
				why = "missing";
			} else if (!TiffCompletionCheck.isReady(file)) {
				// a file still being written and one that will never be a TIFF look the same here
				outcome = Readiness.INCOMPLETE;
				why = "the TIFF structure is not complete or not valid";
			} else {
				try {
					found = RawTiffLayout.read(file);
					if (!found.supported()) {
						outcome = Readiness.FAILED;
						why = "unsupported layout: " + found.unsupportedReason;
					} else if (expectedDepth < 0) {
						outcome = Readiness.DEPTH_UNVERIFIED;
						why = found.depth + " planes; the name states no plane count";
					} else if (found.depth < expectedDepth) {
						outcome = Readiness.INCOMPLETE;
						why = found.depth + " of " + expectedDepth + " planes";
					} else if (found.depth > expectedDepth) {
						outcome = Readiness.FAILED;
						why = found.depth + " planes where the name says " + expectedDepth;
					} else {
						outcome = Readiness.COMPLETE;
						why = found.depth + " planes";
					}
				} catch (IOException e) {
					outcome = Readiness.FAILED;
					why = "unreadable: " + e.getMessage();
				}
			}
			// a file that changed while it was being checked has not been checked
			if (before != null && !before.current()) {
				outcome = Readiness.INCOMPLETE;
				why = "changed while it was checked";
			}
			layout = outcome == Readiness.COMPLETE || outcome == Readiness.DEPTH_UNVERIFIED ? found : null;
			key = layout == null ? null : before;
			detail = why;
			readiness = outcome;
			return outcome;
		}

		/**
		 * Withhold this file because the version that was checked is no longer the one on disk.
		 * Only the generation it was checked as can be withdrawn: a stale report about another
		 * version is ignored.
		 *
		 * @return true when this call changed the entry
		 */
		synchronized boolean markChanged(FileKey stale, String why) {
			if (key == null || !key.equals(stale) || readiness == Readiness.CHANGED) return false;
			readiness = Readiness.CHANGED;
			detail = "changed after it was checked (" + why + "); reopen the folder to show it";
			return true;
		}

		Readiness readiness() { return readiness; }
		String detail() { return detail; }
		/** The validated layout, or null unless the file can be shown. */
		RawTiffLayout layout() { return viewable() ? layout : null; }
		/** The validated file version, or null if the file was never showable. */
		FileKey key() { return key; }

		/** Whether this file can be shown: complete, or (opened by hand) of unverified depth. */
		boolean viewable() {
			Readiness r = readiness;
			return r == Readiness.COMPLETE || r == Readiness.DEPTH_UNVERIFIED;
		}
	}

	/** The tokens of one file name; each is absent, valid, or the reason it is invalid. */
	static final class Name {
		int position = 1, channel = 1;
		/** The time number, or -1 when the name has no {@code _Time} token. */
		int time = -1;
		/** Planes promised by {@code _Frames_a_b}, or -1. */
		int expectedDepth = -1;
		/** Why the file cannot be placed at all (position, time or channel), or null. */
		String placement;
		/** Why the name's plane count is invalid, or null when it is valid or absent. */
		String frames;

		static Name parse(String file) {
			Name n = new Name();
			n.time = n.number(file, TIME, "_Time", -1);
			n.position = n.number(file, POSITION, "_Position", 1);
			n.channel = n.number(file, CHANNEL, "_Channel", 1);
			n.frames(file);
			return n;
		}

		private int number(String file, Pattern pattern, String token, int absent) {
			Set<BigInteger> values = new LinkedHashSet<BigInteger>();
			for (Matcher m = pattern.matcher(file); m.find();) values.add(new BigInteger(m.group(1)));
			if (values.isEmpty()) return absent;
			if (values.size() > 1) {
				if (placement == null) placement = "the name carries two different " + token + " numbers " + values;
				return absent;
			}
			BigInteger v = values.iterator().next();
			if (v.compareTo(INT_MAX) > 0) {
				if (placement == null) placement = token + " number " + v + " is too large";
				return absent;
			}
			return v.intValue();
		}

		private void frames(String file) {
			Set<String> ranges = new LinkedHashSet<String>();
			int tokens = 0;
			for (Matcher any = FRAMES_TOKEN.matcher(file); any.find();) {
				tokens++;
				Matcher m = FRAMES.matcher(file);
				if (!m.find(any.start()) || m.start() != any.start()) {
					frames = "malformed plane range in the name near \"" + excerpt(file, any.start()) + "\"";
					return;
				}
				ranges.add(m.group(1) + "_" + m.group(2));
			}
			if (tokens == 0) return;
			if (ranges.size() > 1) {
				frames = "the name carries two different plane ranges " + ranges;
				return;
			}
			String[] r = ranges.iterator().next().split("_");
			BigInteger first = new BigInteger(r[0]), last = new BigInteger(r[1]);
			if (last.compareTo(first) < 0) {
				frames = "invalid plane range in the name: _Frames_" + r[0] + "_" + r[1] + " is reversed";
				return;
			}
			BigInteger depth = last.subtract(first).add(BigInteger.ONE);
			if (depth.compareTo(INT_MAX) > 0) {
				frames = "invalid plane range in the name: _Frames_" + r[0] + "_" + r[1] + " is too large";
				return;
			}
			expectedDepth = depth.intValue();
		}

		private static String excerpt(String file, int at) {
			return file.substring(at, Math.min(file.length(), at + 24));
		}
	}

	private static final BigInteger INT_MAX = BigInteger.valueOf(Integer.MAX_VALUE);
	private static final Pattern POSITION = Pattern.compile("(?i)_Position(\\d+)");
	private static final Pattern TIME = Pattern.compile("(?i)_Time(\\d+)");
	private static final Pattern CHANNEL = Pattern.compile("(?i)_Channel(\\d+)");
	private static final Pattern FRAMES_TOKEN = Pattern.compile("(?i)_Frames_");
	private static final Pattern FRAMES = Pattern.compile("(?i)_Frames_(\\d+)_(\\d+)(?!\\d)");

	final File folder;
	/** True when time points come from {@code _Time} tokens, false when from name order. */
	final boolean timesFromNames;
	/** Files that could not be placed: no or an invalid {@code _Time} token, or a duplicate. */
	final int unplaced;
	private final List<String> problems;
	private final TreeMap<TimeKey, TreeMap<Integer, Entry>> entries;

	private RawAcquisition(File folder, boolean timesFromNames, List<String> problems, int unplaced,
			TreeMap<TimeKey, TreeMap<Integer, Entry>> entries) {
		this.folder = folder;
		this.timesFromNames = timesFromNames;
		this.problems = Collections.unmodifiableList(problems);
		this.unplaced = unplaced;
		this.entries = entries;
	}

	/** List the folder's TIFF files and place them; nothing is opened yet. A bad name affects only its file. */
	static RawAcquisition scan(File folder) {
		File[] listed = folder == null ? null : folder.listFiles();
		List<File> tiffs = new ArrayList<File>();
		if (listed != null)
			for (File f : listed) {
				String name = f.getName().toLowerCase(Locale.US);
				if (f.isFile() && !name.startsWith(".") && (name.endsWith(".tif") || name.endsWith(".tiff"))) tiffs.add(f);
			}
		Collections.sort(tiffs);
		List<Name> names = new ArrayList<Name>();
		boolean anyTime = false;
		for (File f : tiffs) {
			Name n = Name.parse(f.getName());
			names.add(n);
			anyTime |= n.time >= 0 || TIME.matcher(f.getName()).find();
		}
		TreeMap<TimeKey, TreeMap<Integer, Entry>> entries = new TreeMap<TimeKey, TreeMap<Integer, Entry>>();
		List<String> problems = new ArrayList<String>();
		int unplaced = 0, order = 0;
		for (int i = 0; i < tiffs.size(); i++) {
			File f = tiffs.get(i);
			Name n = names.get(i);
			if (n.placement != null) {
				problems.add(f.getName() + ": " + n.placement);
				unplaced++;
				continue;
			}
			int timeId;
			if (n.time >= 0) timeId = n.time;
			else if (anyTime) {
				problems.add(f.getName() + ": no _Time token among files that have one");
				unplaced++;
				continue;
			} else timeId = ++order;
			TimeKey key = new TimeKey(n.position, timeId);
			TreeMap<Integer, Entry> channels = entries.get(key);
			if (channels == null) entries.put(key, channels = new TreeMap<Integer, Entry>());
			Entry first = channels.get(n.channel);
			if (first == null) {
				channels.put(n.channel, new Entry(key, n.channel, f, n.expectedDepth, n.frames));
				if (n.frames != null) problems.add(f.getName() + ": " + n.frames);
			} else {
				// two files claim one slot: neither is shown, both are named
				String clash = "ambiguous: " + first.file.getName() + " and " + f.getName() + " both claim " + key
						+ " channel " + n.channel;
				first.nameProblem = clash;
				problems.add(f.getName() + ": " + clash);
				unplaced++;
			}
		}
		return new RawAcquisition(folder, anyTime, problems, unplaced, entries);
	}

	SortedSet<Integer> positions() {
		TreeSet<Integer> p = new TreeSet<Integer>();
		for (TimeKey t : entries.keySet()) p.add(t.position);
		return p;
	}

	SortedSet<Integer> channels() {
		TreeSet<Integer> c = new TreeSet<Integer>();
		for (TreeMap<Integer, Entry> m : entries.values()) c.addAll(m.keySet());
		return c;
	}

	/** The time points of one position that have a file of this channel, in time order. */
	List<TimeKey> times(int position, int channel) {
		List<TimeKey> list = new ArrayList<TimeKey>();
		for (Map.Entry<TimeKey, TreeMap<Integer, Entry>> e : entries.entrySet())
			if (e.getKey().position == position && e.getValue().containsKey(channel)) list.add(e.getKey());
		return Collections.unmodifiableList(list);
	}

	Entry entry(TimeKey time, int channel) {
		TreeMap<Integer, Entry> channels = entries.get(time);
		return channels == null ? null : channels.get(channel);
	}

	/**
	 * Withhold the entry checked as this file version, if there is one; called when the raw store
	 * finds the file changed under it.
	 *
	 * @return the entry that was withdrawn, or null
	 */
	Entry markChanged(FileKey stale, String why) {
		for (TreeMap<Integer, Entry> m : entries.values()) {
			Entry e = m.get(stale.channel);
			if (e != null && e.file.equals(stale.file)) return e.markChanged(stale, why) ? e : null;
		}
		return null;
	}

	int fileCount() {
		int n = 0;
		for (TreeMap<Integer, Entry> m : entries.values()) n += m.size();
		return n;
	}

	/** Why particular files cannot be placed or their names cannot be trusted, one line per file. */
	List<String> problems() {
		return problems;
	}

	/** Counts per readiness over the given time points of one channel, for the status line. */
	Map<Readiness, Integer> census(List<TimeKey> times, int channel) {
		EnumMap<Readiness, Integer> counts = new EnumMap<Readiness, Integer>(Readiness.class);
		for (Readiness r : Readiness.values()) counts.put(r, 0);
		for (TimeKey t : times) {
			Entry e = entry(t, channel);
			if (e != null) counts.put(e.readiness(), counts.get(e.readiness()) + 1);
		}
		return counts;
	}

	@Override
	public String toString() {
		return folder + ": " + fileCount() + " files, positions " + positions() + ", channels " + channels()
				+ (timesFromNames ? "" : ", one time point per file in name order")
				+ (unplaced > 0 ? ", " + unplaced + " files not placed" : "")
				+ (problems.isEmpty() ? "" : ", " + problems.size() + " name problems");
	}
}
