package de.embl.iclm;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The raw acquisition files of one folder, as the Online Deskew View sees them: which file holds
 * which position, time point and acquisition channel, and whether it can be shown (joint
 * contracts C2.1 - C2.5).
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
 */
final class RawAcquisition {

	enum Readiness {
		/** Not checked yet. */
		PENDING,
		/** Complete: structure valid and the plane count the name promises. */
		COMPLETE,
		/** Structure valid, but the name states no plane count to check against. */
		DEPTH_UNVERIFIED,
		/** Fewer planes than promised, or the TIFF structure not complete yet. */
		INCOMPLETE,
		/** Unreadable, unsupported, or more planes than the name says. */
		FAILED
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
	 * One version of one file: its path, acquisition channel, and the size and modification time
	 * it had when it was validated. A file that changes is a different key, so pixels cached for
	 * the old version are never served for the new one (C3.8).
	 */
	static final class FileKey {
		final File file;
		final int channel;
		final long size;
		final long modified;

		FileKey(File file, int channel, long size, long modified) {
			this.file = file;
			this.channel = channel;
			this.size = size;
			this.modified = modified;
		}

		/** Whether the file on disk is still this version. */
		boolean current() {
			return file.length() == size && file.lastModified() == modified;
		}

		@Override public boolean equals(Object o) {
			if (!(o instanceof FileKey)) return false;
			FileKey k = (FileKey) o;
			return k.channel == channel && k.size == size && k.modified == modified && k.file.equals(file);
		}

		@Override public int hashCode() {
			return file.hashCode() * 31 + Long.hashCode(size) * 17 + Long.hashCode(modified) + channel;
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
		/** Planes promised by {@code _Frames_a_b}, or -1 when the name states none. */
		final int expectedDepth;

		private volatile Readiness readiness = Readiness.PENDING;
		private volatile String detail = "";
		private volatile RawTiffLayout layout;
		private volatile FileKey key;

		Entry(TimeKey time, int channel, File file, int expectedDepth) {
			this.time = time;
			this.channel = channel;
			this.file = file;
			this.expectedDepth = expectedDepth;
		}

		/** Check the file once; later calls return the stored outcome. */
		synchronized Readiness validate() {
			if (readiness != Readiness.PENDING) return readiness;
			long size = file.length(), modified = file.lastModified();
			RawTiffLayout found = null;
			Readiness outcome;
			String why;
			if (!file.isFile()) {
				outcome = Readiness.FAILED;
				why = "missing";
			} else if (!TiffCompletionCheck.isReady(file)) {
				outcome = Readiness.INCOMPLETE;
				why = "the TIFF structure is not complete";
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
			if (file.length() != size || file.lastModified() != modified) {
				outcome = Readiness.INCOMPLETE;
				why = "changed while it was checked";
			}
			layout = outcome == Readiness.COMPLETE || outcome == Readiness.DEPTH_UNVERIFIED ? found : null;
			key = layout == null ? null : new FileKey(file, channel, size, modified);
			detail = why;
			readiness = outcome;
			return outcome;
		}

		Readiness readiness() { return readiness; }
		String detail() { return detail; }
		/** The validated layout, or null unless the file can be shown. */
		RawTiffLayout layout() { return layout; }
		/** The validated file version, or null unless the file can be shown. */
		FileKey key() { return key; }

		/** Whether this file can be shown: complete, or (opened by hand) of unverified depth. */
		boolean viewable() {
			Readiness r = readiness;
			return r == Readiness.COMPLETE || r == Readiness.DEPTH_UNVERIFIED;
		}
	}

	private static final Pattern POSITION = Pattern.compile("(?i)_Position(\\d+)");
	private static final Pattern TIME = Pattern.compile("(?i)_Time(\\d+)");
	private static final Pattern CHANNEL = Pattern.compile("(?i)_Channel(\\d+)");
	private static final Pattern FRAMES = Pattern.compile("(?i)_Frames_(\\d+)_(\\d+)");

	final File folder;
	/** True when time points come from {@code _Time} tokens, false when from name order. */
	final boolean timesFromNames;
	/** Files that could not be placed (no {@code _Time} token in a folder that otherwise has them). */
	final int unplaced;
	private final TreeMap<TimeKey, TreeMap<Integer, Entry>> entries;

	private RawAcquisition(File folder, boolean timesFromNames, int unplaced, TreeMap<TimeKey, TreeMap<Integer, Entry>> entries) {
		this.folder = folder;
		this.timesFromNames = timesFromNames;
		this.unplaced = unplaced;
		this.entries = entries;
	}

	/** List the folder's TIFF files and place them; nothing is opened yet. */
	static RawAcquisition scan(File folder) {
		File[] listed = folder == null ? null : folder.listFiles();
		List<File> tiffs = new ArrayList<File>();
		if (listed != null)
			for (File f : listed) {
				String name = f.getName().toLowerCase(Locale.US);
				if (f.isFile() && !name.startsWith(".") && (name.endsWith(".tif") || name.endsWith(".tiff"))) tiffs.add(f);
			}
		Collections.sort(tiffs);
		boolean anyTime = false;
		for (File f : tiffs) anyTime |= TIME.matcher(f.getName()).find();
		TreeMap<TimeKey, TreeMap<Integer, Entry>> entries = new TreeMap<TimeKey, TreeMap<Integer, Entry>>();
		int unplaced = 0, order = 0;
		for (File f : tiffs) {
			String name = f.getName();
			Matcher time = TIME.matcher(name);
			int timeId;
			if (time.find()) timeId = Integer.parseInt(time.group(1));
			else if (anyTime) { unplaced++; continue; }
			else timeId = ++order;
			TimeKey key = new TimeKey(number(POSITION, name, 1), timeId);
			int channel = number(CHANNEL, name, 1);
			Matcher frames = FRAMES.matcher(name);
			int expected = -1;
			if (frames.find()) {
				int first = Integer.parseInt(frames.group(1)), last = Integer.parseInt(frames.group(2));
				if (last >= first) expected = last - first + 1;
			}
			TreeMap<Integer, Entry> channels = entries.get(key);
			if (channels == null) entries.put(key, channels = new TreeMap<Integer, Entry>());
			if (!channels.containsKey(channel)) channels.put(channel, new Entry(key, channel, f, expected));
			else unplaced++;		// a second file claiming the same slot; the first one wins
		}
		return new RawAcquisition(folder, anyTime, unplaced, entries);
	}

	private static int number(Pattern pattern, String name, int fallback) {
		Matcher m = pattern.matcher(name);
		return m.find() ? Integer.parseInt(m.group(1)) : fallback;
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

	int fileCount() {
		int n = 0;
		for (TreeMap<Integer, Entry> m : entries.values()) n += m.size();
		return n;
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
				+ (unplaced > 0 ? ", " + unplaced + " files not placed" : "");
	}
}
