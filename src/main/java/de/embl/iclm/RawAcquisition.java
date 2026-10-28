package de.embl.iclm;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.SwingUtilities;

/**
 * The raw acquisition files of one folder, as the Online Deskew View sees them: which file holds
 * which position, time point and acquisition channel, and whether it can be shown (joint
 * contracts C2.1 - C2.6).
 *
 * <p>The acquisition names its files {@code <name>_Position<P>_Time<T>_Channel<C>_Frames_<a>_<b>}
 * and writes them under that final name (C2.4). A file is <em>validated</em> before it is shown:
 * the TIFF structure has to be complete ({@link TiffCompletionCheck#isReady}), the layout has to
 * suit the direct reader ({@link RawTiffLayout}), and the number of planes has to equal the
 * {@code _Frames_a_b} count, {@code b - a + 1}. A structurally valid file can still be a volume
 * caught mid-write - the plane count is what tells them apart.
 *
 * <p><b>The catalogue follows a folder that grows</b> (M3-A). {@link #scan} lists the folder once;
 * {@link #reconcile} looks again, and {@link #hint} looks at one file a folder watch named - an
 * event is only a hint, so a missed or a repeated one is repaired by the next look. Each look notes
 * every file's version on disk ({@link FileKey}). A version is checked once it is <em>quiet</em> -
 * unchanged for {@link #quietMs()}, by its modification time or by how long it has been seen - or
 * once its producer said it is finished ({@link #announce}); a file is never waited on. A file
 * that is underfilled stays {@link Readiness#INCOMPLETE} as {@code k of n planes} and is checked
 * again when it changes; one that changes after it was shown is withdrawn ({@link #markChanged}),
 * checked again and republished; one that is deleted is {@link Readiness#REMOVED}. A late file
 * with an older time number takes its place in order.
 *
 * <p><b>Every change is published as a new {@link Snapshot}</b>: the time points of one display
 * plan, sorted by their own time number, and the entries that hold their files. A snapshot never
 * changes once made, so whoever holds one - a frame being drawn - keeps a consistent time axis.
 *
 * <p>Time points are identified by position and the acquisition's own time number, never by
 * their index in a list, and ordered numerically, so {@code _Time000010} follows
 * {@code _Time000009} (C2.1). A folder whose files carry no {@code _Time} token at all is read
 * as one time point per file, in name order, and says so; a file arriving later in such a folder
 * becomes the next time point.
 *
 * <p><b>A name token is absent, valid or invalid, and the three are kept apart.</b> A file with no
 * {@code _Frames} token can be opened by hand with its depth unverified; one whose token is
 * reversed, malformed or too large is refused with that reason, never quietly treated as having
 * none. A {@code _Position}, {@code _Time} or {@code _Channel} number too large to be one, or a
 * name carrying two different values of a token, cannot be placed: the file is listed under
 * {@link #problems()} and the rest of the folder opens as usual. Two files claiming the same
 * position, time point and channel are both reported, and that slot is shown from neither.
 *
 * <p><b>Two policies for a file of unverified depth</b> (C2.3): one present when the folder was
 * scanned - a manual opening of a finished acquisition - is shown, with its status; one arriving
 * later is not published into the live view unless {@link #setShowUnverifiedArrivals} allows it.
 *
 * <p>The catalogue is changed by one worker at a time - the view's checking thread - and read from
 * any thread: its maps are replaced whole on every change, never edited where a reader can see them.
 */
final class RawAcquisition {

	enum Readiness {
		/** Not checked yet, or a new version waiting to be checked. */
		PENDING,
		/** Complete: structure valid and the plane count the name promises. */
		COMPLETE,
		/** Structure valid, but the name states no plane count to check against. */
		DEPTH_UNVERIFIED,
		/** Fewer planes than promised, or the TIFF structure not complete: pending until it changes. */
		INCOMPLETE,
		/** Unreadable, unsupported, more planes than the name says, or an invalid name. */
		FAILED,
		/** Changed, replaced or cut after it was checked: withdrawn, and checked again once it is quiet. */
		CHANGED,
		/** Deleted: unavailable until a file of that name appears again. */
		REMOVED
	}

	/** How long a file's version has to stay the same before it is checked, unless its producer said it is finished. */
	static final long QUIET_MS = 1000;
	/** Test seam: a quiet time other than {@link #QUIET_MS}, when not negative. */
	static volatile long quietMsForTest = -1;
	/** Test seam: file attribute reads and file checks made on the event thread, which should make none. */
	static final AtomicLong eventThreadFileAccessForTest = new AtomicLong();
	/** Test seam: file attribute reads, every thread - a look at the folder costs one per file. */
	static final AtomicLong attributeReadsForTest = new AtomicLong();
	/** Test seam: run for every file a look at the whole folder reaches, before its attributes are read. */
	static volatile Runnable lookHookForTest;

	static long quietMs() {
		long t = quietMsForTest;
		return t >= 0 ? t : QUIET_MS;
	}

	private static void countIfEventThread() {
		if (SwingUtilities.isEventDispatchThread()) eventThreadFileAccessForTest.incrementAndGet();
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
			countIfEventThread();
			attributeReadsForTest.incrementAndGet();
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
		/**
		 * One publication of a version that can be shown: the version and its layout, made together and never changed, so that
		 * a reader who takes one object has both from the same check (M3-A-F1). Its identity is the publication's: a version
		 * published again - checked again after a change, or once a conflict over its slot has gone - is a new object, so a
		 * presentation gate that let go of the old one does not refuse the new (M3-A-F2, F3).
		 */
		static final class Shown {
			final FileKey key;
			final RawTiffLayout layout;

			Shown(FileKey key, RawTiffLayout layout) {
				this.key = key;
				this.layout = layout;
			}
		}

		final TimeKey time;
		final int channel;
		final File file;
		/** Planes promised by {@code _Frames_a_b}, or -1 when the name states none or an invalid one. */
		final int expectedDepth;
		/** Found after the folder was first scanned: a file that arrived while it was being followed. */
		final boolean arrived;
		/** Whether a version of unverified depth may be shown (C2.3): always for a scanned file, for an arrival only if allowed. */
		private final boolean unverifiedShown;
		/** The catalogue's: every change of this entry is a new version of the catalogue. */
		private final AtomicLong catalogueVersion;
		/** The catalogue's: told of a publication before this entry takes it away (M3-A-F2). */
		private final AtomicReference<Consumer<Shown>> gate;
		/** Why the name's plane count cannot be used, or null when it is valid or absent: the name's, so for good. */
		private final String nameProblem;
		/** Why other files' claims on this file's slot keep it from being shown, or null; undone when they go (M3-A-F3). */
		private volatile String ambiguity;

		private volatile Readiness readiness = Readiness.PENDING;
		private volatile String detail = "";
		/** The version validated as showable, with its layout, or null; kept while it is withdrawn, for its geometry. */
		private volatile Shown held;
		/** The publication shown now - the same object as {@link #held} - or null: what {@link #viewable()} means. */
		private volatile Shown shown;
		/** The version the last check ran on, whatever it found; null before the first. */
		private volatile FileKey checkedAs;
		/** The version on disk at the last look, null when it was gone; when that version was first seen. */
		private volatile FileKey observed;
		private volatile long observedSince;
		/** Its producer said this version is finished. */
		private volatile boolean announced;
		/** The last version shown, whether or not it still is: a different one shown later is a republication. */
		private volatile FileKey lastShown;
		private volatile int checks, republished;

		Entry(TimeKey time, int channel, File file, int expectedDepth, String nameProblem) {
			this(time, channel, file, expectedDepth, nameProblem, false, true, new AtomicLong(), new AtomicReference<Consumer<Shown>>());
		}

		Entry(TimeKey time, int channel, File file, int expectedDepth, String nameProblem, boolean arrived, boolean unverifiedShown,
				AtomicLong catalogueVersion, AtomicReference<Consumer<Shown>> gate) {
			this.time = time;
			this.channel = channel;
			this.file = file;
			this.expectedDepth = expectedDepth;
			this.nameProblem = nameProblem;
			this.arrived = arrived;
			this.unverifiedShown = unverifiedShown;
			this.catalogueVersion = catalogueVersion;
			this.gate = gate;
		}

		/** Check the file once; later calls return the stored outcome until a new version is to be checked. */
		synchronized Readiness validate() {
			if (readiness != Readiness.PENDING) return readiness;
			countIfEventThread();
			FileKey before = FileKey.of(file, channel);
			RawTiffLayout found = null;
			Readiness outcome;
			String why;
			if (ambiguity != null) {
				outcome = Readiness.FAILED;
				why = ambiguity;
			} else if (nameProblem != null) {
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
						why = found.depth + " planes; the name states no plane count"
								+ (unverifiedShown ? "" : "; not shown, since it arrived while the folder was followed");
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
				before = null;		// so that the next version is checked in its turn
			}
			boolean showable = outcome == Readiness.COMPLETE || outcome == Readiness.DEPTH_UNVERIFIED;
			if (showable) {
				if (lastShown != null && !lastShown.equals(before)) republished++;
				lastShown = before;
			}
			// a pending entry shows nothing, so this publishes and never takes a publication away
			Shown version = showable ? new Shown(before, found) : null;
			held = version;
			shown = outcome == Readiness.COMPLETE || outcome == Readiness.DEPTH_UNVERIFIED && unverifiedShown ? version : null;
			checkedAs = before;
			checks++;
			detail = why;
			readiness = outcome;
			catalogueVersion.incrementAndGet();
			return outcome;
		}

		/**
		 * Check this file again: its version on disk is not the one last checked, and is quiet. Never a
		 * file shown: {@link #due} is false for one, which is first withdrawn ({@link #markChanged}).
		 *
		 * @return the outcome, or null when there is nothing new to check
		 */
		synchronized Readiness recheck(long now) {
			if (!due(now)) return null;
			if (readiness != Readiness.PENDING) {
				shown = null;		// already null: nothing is taken away here
				readiness = Readiness.PENDING;
				held = null;
			}
			return validate();
		}

		/**
		 * Take this entry's publication away, if {@code applies} says the change does (M3-A-F2): the gate - what presents
		 * frames - lets go of it first, and {@code change} then runs under this entry's monitor. The gate is called outside
		 * the monitor, so that its own locks are never taken under it; a publication made meanwhile is let go of in turn.
		 */
		private boolean withdrawing(BooleanSupplier applies, BooleanSupplier change) {
			while (true) {
				Shown publication;
				synchronized (this) {
					if (!applies.getAsBoolean()) return false;
					publication = shown;
				}
				Consumer<Shown> g = gate.get();
				if (publication != null && g != null) g.accept(publication);
				synchronized (this) {
					if (shown != publication) continue;
					if (!applies.getAsBoolean()) return false;
					return change.getAsBoolean();
				}
			}
		}

		/**
		 * Withhold this file because the version that was checked is no longer the one on disk.
		 * Only the generation it was checked as can be withdrawn: a stale report about another
		 * version is ignored. It is checked again once its new version is quiet.
		 *
		 * @return true when this call changed the entry
		 */
		boolean markChanged(FileKey stale, String why) {
			return withdrawing(() -> {
				Shown h = held;
				return h != null && h.key.equals(stale) && readiness != Readiness.CHANGED && readiness != Readiness.REMOVED;
			}, () -> {
				shown = null;
				readiness = Readiness.CHANGED;
				detail = "changed after it was checked (" + why + "); checked again once it is quiet";
				catalogueVersion.incrementAndGet();
				return true;
			});
		}

		/**
		 * Note the version on disk now, {@code null} when the file is gone, as seen at {@code now}. A
		 * deleted file becomes {@link Readiness#REMOVED}, its publication taken away through the gate
		 * first; a shown file whose version differs is left for the store to withdraw, which then
		 * reports it ({@link #markChanged}).
		 *
		 * @return true when this changed what is known of the file
		 */
		boolean observe(FileKey seen, long now) {
			boolean changed = false;
			synchronized (this) {
				if (seen == null ? observed != null : !seen.equals(observed)) {
					observed = seen;
					observedSince = now;
					announced = false;
					changed = true;
				}
			}
			if (seen == null && withdrawing(() -> readiness != Readiness.REMOVED, () -> {
				shown = null;
				readiness = Readiness.REMOVED;
				detail = "deleted";
				return true;
			})) changed = true;
			if (changed) catalogueVersion.incrementAndGet();
			return changed;
		}

		/**
		 * Other files claim this one's slot (M3-A-F3): while they exist it is not shown - its publication taken away through
		 * the gate first - and says why. Undone by {@link #resolve}.
		 */
		void conflict(String why) {
			withdrawing(() -> !why.equals(ambiguity) || readiness != Readiness.FAILED && readiness != Readiness.REMOVED, () -> {
				ambiguity = why;
				shown = null;
				if (readiness != Readiness.REMOVED) {
					readiness = Readiness.FAILED;
					detail = why;
				}
				catalogueVersion.incrementAndGet();
				return true;
			});
		}

		/** The other claims on this file's slot are gone (M3-A-F3): it is checked again, and shown again if it still can be. */
		synchronized boolean resolve() {
			if (ambiguity == null) return false;
			ambiguity = null;
			if (readiness != Readiness.REMOVED) {
				readiness = Readiness.PENDING;
				detail = "";
				held = null;
			}
			catalogueVersion.incrementAndGet();
			return true;
		}

		/** The producer said the version now on disk is finished: it need not wait to be quiet. */
		synchronized void announce() {
			announced = true;
			catalogueVersion.incrementAndGet();
		}

		/**
		 * Whether this file has a version to check now: one not checked yet, or one that differs from what was
		 * checked and is not shown - and either way quiet or announced. A shown file that changed is withdrawn
		 * first; a file with an invalid name never has.
		 */
		boolean due(long now) {
			if (nameProblem != null && checks > 0 || ambiguity != null) return false;
			FileKey o = observed;
			if (o == null) return false;
			Readiness r = readiness;
			if (r == Readiness.PENDING || r == Readiness.REMOVED) return quiet(o, now);		// new, or deleted and back
			if (o.equals(checkedAs) || r == Readiness.COMPLETE || r == Readiness.DEPTH_UNVERIFIED) return false;
			return quiet(o, now);
		}

		/** When the version on disk becomes quiet; {@code Long.MAX_VALUE} when there is none. */
		long quietAt() {
			FileKey o = observed;
			return o == null ? Long.MAX_VALUE : Math.min(observedSince, o.modified) + quietMs();
		}

		/** Whether a new version is on disk that this entry has not checked: being written, or waiting to be quiet. */
		boolean changing() {
			FileKey o = observed;
			Readiness r = readiness;
			return o != null && !o.equals(checkedAs) && r != Readiness.COMPLETE && r != Readiness.DEPTH_UNVERIFIED && r != Readiness.REMOVED;
		}

		/**
		 * Whether a version not checked yet is still being written at {@code now}: not announced and not quiet. A file found by a
		 * look and quiet already is only waiting for its turn to be checked, which takes milliseconds; one being written can take
		 * as long as its acquisition, so nothing waits for it (C2.3).
		 */
		boolean beingWritten(long now) {
			FileKey o = observed;
			return changing() && o != null && !quiet(o, now);
		}

		private boolean quiet(FileKey o, long now) {
			long quiet = quietMs();
			return announced || now - observedSince >= quiet || now - o.modified >= quiet;
		}

		/** The version on disk at the last look, or null when the file was gone. */
		FileKey observed() { return observed; }
		Readiness readiness() { return readiness; }
		String detail() { return detail; }
		/** Checks run, and new versions published after a change. */
		int checks() { return checks; }
		int republished() { return republished; }
		/**
		 * The layout the file was validated with, or null if it cannot be shown now. While a file is
		 * withdrawn ({@link Readiness#CHANGED}) the layout of the version shown is kept: the view still
		 * needs its geometry while the time point is being taken away. Whether its pixels can be read
		 * is {@link #viewable()}. A reader that needs the version too takes {@link #held()} once instead.
		 */
		RawTiffLayout layout() {
			Shown h = held;
			return h == null ? null : h.layout;
		}

		/** The validated file version, or null if the file is not showable now. */
		FileKey key() {
			Shown h = held;
			return h == null ? null : h.key;
		}

		/** The validated version and its layout, in one read: kept while it is withdrawn; null when there is none. */
		Shown held() {
			return held;
		}

		/** Whether this file can be shown: complete, or of unverified depth where its policy allows (C2.3). */
		boolean viewable() {
			return shown != null;
		}

		/** The version shown now with its layout, read in one step; null when the file cannot be shown. */
		Shown shown() {
			return shown;
		}

		/** What the status card says of this file: its readiness, and whether a new version is being written. */
		String describe() {
			Readiness r = readiness;
			String now = r == Readiness.PENDING ? "being checked"
					: r == Readiness.INCOMPLETE ? "pending: " + detail : r.name().toLowerCase(Locale.US).replace('_', ' ') + ": " + detail;
			FileKey o = observed;
			if (r == Readiness.CHANGED || o == null || !changing()) return now;
			if (beingWritten(System.currentTimeMillis()))
				return (r == Readiness.PENDING ? "being written" : now + "; a new version being written")
						+ String.format(Locale.US, " (%.1f MB so far); checked once it is quiet", o.size / 1e6);
			return r == Readiness.PENDING ? now : now + "; a new version waiting to be checked";
		}
	}

	/**
	 * One publication of the catalogue for a display plan - one position and acquisition channel (C2.1, C2.5): its time
	 * points in their own order, and the entries holding their files. Never changes once made. Whether each file can be
	 * shown is the entry's to say at the moment it is asked; a view freezes that per frame ({@link RawPresence}).
	 */
	static final class Snapshot {
		final long version;
		final int position, channel;
		final List<TimeKey> times;
		final List<Entry> entries;

		Snapshot(long version, int position, int channel, List<TimeKey> times, List<Entry> entries) {
			this.version = version;
			this.position = position;
			this.channel = channel;
			this.times = Collections.unmodifiableList(new ArrayList<TimeKey>(times));
			this.entries = Collections.unmodifiableList(new ArrayList<Entry>(entries));
		}

		/** A fixed list of time points over a catalogue, for views of a folder that is not followed (tests, benchmarks). */
		static Snapshot of(RawAcquisition acquisition, List<TimeKey> times, int channel) {
			List<Entry> entries = new ArrayList<Entry>();
			for (TimeKey t : times) entries.add(acquisition.entry(t, channel));
			return new Snapshot(0, times.isEmpty() ? 1 : times.get(0).position, channel, times, entries);
		}

		int size() { return times.size(); }
		TimeKey time(int t) { return t < 0 || t >= times.size() ? null : times.get(t); }
		Entry entry(int t) { return t < 0 || t >= entries.size() ? null : entries.get(t); }

		/** The index of a time point, or -1. */
		int indexOf(TimeKey key) {
			if (key == null) return -1;
			int i = Collections.binarySearch(times, key);
			return i >= 0 ? i : -1;
		}

		/**
		 * Whether every time point of {@code earlier} keeps its index here: time points were only added after them. The file
		 * holding a time point may have been replaced; its number is what a slider position means.
		 */
		boolean extendsFrom(Snapshot earlier) {
			if (earlier.times.size() > times.size() || earlier.position != position || earlier.channel != channel) return false;
			for (int i = 0; i < earlier.times.size(); i++)
				if (!earlier.times.get(i).equals(times.get(i))) return false;
			return true;
		}

		@Override public String toString() {
			return "catalogue version " + version + ": " + times.size() + " time points";
		}
	}

	/** What one look at the folder found. */
	static final class Change {
		int added, changed, removed, looked;

		boolean any() {
			return added + changed + removed > 0;
		}

		@Override public String toString() {
			return String.format(Locale.US, "%d files looked at: %d new, %d changed, %d removed", looked, added, changed, removed);
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
	/** Files that cannot be placed now: no or an invalid {@code _Time} token, or a rival for a slot another file holds. */
	volatile int unplaced;
	private final List<String> problems = new CopyOnWriteArrayList<String>();
	/** Every change - a file found, changed, checked or removed - counts once here. */
	private final AtomicLong version = new AtomicLong();
	/** Replaced whole on every change, under this object's monitor; read without it. */
	private volatile TreeMap<TimeKey, TreeMap<Integer, Entry>> entries;
	private volatile Map<File, Entry> byFile;
	/** Names already found unplaceable, so that a look again does not list them again: a name does not change. */
	private final Set<File> refused = new HashSet<File>();
	/**
	 * Files claiming a slot another file holds, and the slot, under this object's monitor (M3-A-F3). Unlike a refused name a
	 * rival is temporary: each look and each hint settles its slot again from the files that are there.
	 */
	private final Map<File, Slot> rivals = new HashMap<File, Slot>();
	/** The problem line each rival put on the list, taken off again when it goes. */
	private final Map<File, String> rivalProblems = new HashMap<File, String>();
	/** Told of each publication before an entry takes it away (M3-A-F2); shared with every entry. */
	private final AtomicReference<Consumer<Entry.Shown>> gate = new AtomicReference<Consumer<Entry.Shown>>();
	private volatile boolean showUnverifiedArrivals;
	/** Time numbers given in name order, when names carry none: the last one given. */
	private int order;

	private RawAcquisition(File folder, boolean timesFromNames) {
		this.folder = folder;
		this.timesFromNames = timesFromNames;
		this.entries = new TreeMap<TimeKey, TreeMap<Integer, Entry>>();
		this.byFile = new HashMap<File, Entry>();
	}

	/** List the folder's TIFF files and place them; nothing is opened yet. A bad name affects only its file. */
	static RawAcquisition scan(File folder) {
		List<File> tiffs = list(folder);
		boolean anyTime = false;
		for (File f : tiffs) {
			Name n = Name.parse(f.getName());
			anyTime |= n.time >= 0 || TIME.matcher(f.getName()).find();
		}
		RawAcquisition a = new RawAcquisition(folder, anyTime);
		long now = System.currentTimeMillis();
		synchronized (a) {
			TreeMap<TimeKey, TreeMap<Integer, Entry>> entries = new TreeMap<TimeKey, TreeMap<Integer, Entry>>();
			Map<File, Entry> byFile = new HashMap<File, Entry>();
			for (File f : tiffs) a.place(f, false, entries, byFile, now, null);
			a.entries = entries;
			a.byFile = byFile;
			a.version.incrementAndGet();
		}
		return a;
	}

	/** The folder's TIFF files, in name order. */
	private static List<File> list(File folder) {
		File[] listed = folder == null ? null : folder.listFiles();
		List<File> tiffs = new ArrayList<File>();
		if (listed != null)
			for (File f : listed)
				if (isTiff(f) && f.isFile()) tiffs.add(f);
		Collections.sort(tiffs);
		return tiffs;
	}

	/** One time point's file of one acquisition channel: what two files with the same tokens both claim. */
	static final class Slot {
		final TimeKey time;
		final int channel;

		Slot(TimeKey time, int channel) {
			this.time = time;
			this.channel = channel;
		}

		@Override public boolean equals(Object o) {
			return o instanceof Slot && ((Slot) o).time.equals(time) && ((Slot) o).channel == channel;
		}

		@Override public int hashCode() {
			return time.hashCode() * 31 + channel;
		}

		@Override public String toString() {
			return time + " channel " + channel;
		}
	}

	private static boolean isTiff(File f) {
		String name = f.getName().toLowerCase(Locale.US);
		return !name.startsWith(".") && (name.endsWith(".tif") || name.endsWith(".tiff"));
	}

	/**
	 * Place one file into the maps being built, under this object's monitor; a file arriving later ({@code arrived}) is
	 * noted with its version. Returns the new entry, or null when the file cannot be placed or its slot is taken.
	 */
	private Entry place(File f, boolean arrived, TreeMap<TimeKey, TreeMap<Integer, Entry>> entries, Map<File, Entry> byFile, long now,
			Change change) {
		Name n = Name.parse(f.getName());
		if (n.placement != null) {
			if (refused.add(f)) {
				problems.add(f.getName() + ": " + n.placement);
				countUnplaced();
			}
			return null;
		}
		int timeId;
		if (n.time >= 0) timeId = n.time;
		else if (timesFromNames) {
			if (refused.add(f)) {
				problems.add(f.getName() + ": no _Time token among files that have one");
				countUnplaced();
			}
			return null;
		} else timeId = ++order;
		TimeKey key = new TimeKey(n.position, timeId);
		TreeMap<Integer, Entry> channels = entries.get(key);
		channels = channels == null ? new TreeMap<Integer, Entry>() : new TreeMap<Integer, Entry>(channels);
		Entry first = channels.get(n.channel);
		if (first != null && first.readiness() != Readiness.REMOVED) {
			// two files claim one slot: neither is shown while both are there, and both are named
			Slot slot = new Slot(key, n.channel);
			rivals.put(f, slot);
			String line = f.getName() + ": ambiguous: claims " + slot + ", which " + first.file.getName() + " holds";
			if (rivalProblems.put(f, line) == null) problems.add(line);
			countUnplaced();
			first.conflict(conflict(first, slot));
			return null;
		}
		Entry e = new Entry(key, n.channel, f, n.expectedDepth, n.frames, arrived, !arrived || showUnverifiedArrivals, version, gate);
		if (first != null) byFile.remove(first.file);		// a removed file's slot, taken by a new name
		channels.put(n.channel, e);
		entries.put(key, channels);
		byFile.put(f, e);
		String framesLine = f.getName() + ": " + n.frames;
		if (n.frames != null && !problems.contains(framesLine)) problems.add(framesLine);
		// a file there when the folder was scanned is what the user opened: quiet already; an arrival is seen from now
		e.observe(FileKey.of(f, n.channel), arrived ? now : 0);
		if (change != null) change.added++;
		return e;
	}

	/** Caller holds the monitor. */
	private void countUnplaced() {
		unplaced = refused.size() + rivals.size();
	}

	/** What a slot's holder says while rivals claim it: every file that does. Caller holds the monitor. */
	private String conflict(Entry holder, Slot slot) {
		List<String> names = new ArrayList<String>();
		names.add(holder.file.getName());
		for (Map.Entry<File, Slot> r : rivals.entrySet())
			if (r.getValue().equals(slot)) names.add(r.getKey().getName());
		Collections.sort(names.subList(1, names.size()));
		return "ambiguous: " + String.join(" and ", names) + " all claim " + slot + "; shown again once one is left";
	}

	/** A rival that went. Caller holds the monitor. */
	private void forgetRival(File f) {
		rivals.remove(f);
		String line = rivalProblems.remove(f);
		if (line != null) problems.remove(line);
		countUnplaced();
	}

	/**
	 * Settle one slot from the files that claim it now (M3-A-F3), into the maps being built, under this object's monitor:
	 * while its holder and a rival are both there the holder is not shown; once only one file is left it is checked again
	 * and shown again if it can be; a holder that went gives the slot to the first rival left, in name order, which the
	 * others then rival.
	 */
	private void settle(Slot slot, TreeMap<TimeKey, TreeMap<Integer, Entry>> entries, Map<File, Entry> byFile, long now,
			Change change) {
		List<File> claims = new ArrayList<File>();
		for (Map.Entry<File, Slot> r : rivals.entrySet())
			if (r.getValue().equals(slot)) claims.add(r.getKey());
		Collections.sort(claims);
		TreeMap<Integer, Entry> channels = entries.get(slot.time);
		Entry holder = channels == null ? null : channels.get(slot.channel);
		if (holder != null && holder.readiness() == Readiness.REMOVED && !claims.isEmpty()) {
			File next = claims.remove(0);
			forgetRival(next);
			holder = place(next, true, entries, byFile, now, change);
		}
		if (holder == null || holder.readiness() == Readiness.REMOVED) return;
		if (claims.isEmpty()) holder.resolve();
		else holder.conflict(conflict(holder, slot));
	}

	/**
	 * Look at the whole folder again: files that are new, changed or gone (M3-A). Repairs whatever a missed or a doubled
	 * folder event left, and settles every slot that two files claimed (M3-A-F3). No file is opened: the listing checks that
	 * each TIFF name is a regular file, and each known file's attributes are then read once (M3-A-N1). For the catalogue's
	 * one worker.
	 */
	Change reconcile() {
		return reconcile(() -> false);
	}

	/** As {@link #reconcile()}, stopping between files once {@code stop} says so; what was not reached is left for the next look. */
	Change reconcile(java.util.function.BooleanSupplier stop) {
		long now = System.currentTimeMillis();
		Change change = new Change();
		List<File> listed = list(folder);
		Set<File> present = new HashSet<File>(listed);
		List<File> fresh = new ArrayList<File>();
		Map<File, Entry> known = byFile;
		Set<File> unknown = unplaceable();
		for (File f : listed) {
			if (stop.getAsBoolean()) return change;		// a partial look: removals wait for a whole one
			Runnable hook = lookHookForTest;
			if (hook != null) hook.run();
			change.looked++;
			Entry e = known.get(f);
			if (e == null) {
				if (!unknown.contains(f)) fresh.add(f);
			} else if (e.observe(FileKey.of(f, e.channel), now)) change.changed++;
		}
		Set<Slot> unsettled = new HashSet<Slot>();
		for (Entry e : known.values())
			if (!present.contains(e.file) && e.observe(null, now)) {
				change.removed++;
				unsettled.add(new Slot(e.time, e.channel));
			}
		synchronized (this) {
			for (Map.Entry<File, Slot> r : rivals.entrySet()) {
				unsettled.add(r.getValue());		// every disputed slot, settled again from what is there
				if (!present.contains(r.getKey())) change.removed++;
			}
			for (File f : new ArrayList<File>(rivals.keySet()))
				if (!present.contains(f)) forgetRival(f);
		}
		if (!fresh.isEmpty() || !unsettled.isEmpty()) update(fresh, unsettled, now, change);
		return change;
	}

	/** Names refused for good and rivals for a slot: files a look does not place again. */
	private synchronized Set<File> unplaceable() {
		Set<File> files = new HashSet<File>(refused);
		files.addAll(rivals.keySet());
		return files;
	}

	/**
	 * Look at one file a folder event named - only a hint: whatever it says is read from the disk. For the catalogue's one
	 * worker. Returns what changed.
	 */
	Change hint(File f) {
		long now = System.currentTimeMillis();
		Change change = new Change();
		if (f == null || !isTiff(f) || !folder.equals(f.getParentFile())) return change;
		change.looked++;
		Entry e = byFile.get(f);
		if (e != null) {
			FileKey seen = FileKey.of(f, e.channel);
			if (e.observe(seen, now)) {
				if (seen == null) {
					change.removed++;
					update(Collections.<File> emptyList(), Collections.singleton(new Slot(e.time, e.channel)), now, change);
				} else change.changed++;
			}
			return change;
		}
		Slot rivalled;
		synchronized (this) {
			rivalled = rivals.get(f);
			if (rivalled != null && !f.isFile()) forgetRival(f);
		}
		if (rivalled != null) {
			if (!f.isFile()) {
				change.removed++;
				update(Collections.<File> emptyList(), Collections.singleton(rivalled), now, change);
			}
		} else if (!refused.contains(f) && f.isFile()) update(Collections.singletonList(f), Collections.<Slot> emptySet(), now, change);
		return change;
	}

	/** Place files found after the scan, in name order, then settle the slots whose claims changed (M3-A-F3). */
	private synchronized void update(List<File> files, Set<Slot> unsettled, long now, Change change) {
		long before = version.get();
		TreeMap<TimeKey, TreeMap<Integer, Entry>> next = new TreeMap<TimeKey, TreeMap<Integer, Entry>>(entries);
		Map<File, Entry> nextByFile = new HashMap<File, Entry>(byFile);
		List<File> sorted = new ArrayList<File>(files);
		Collections.sort(sorted);
		for (File f : sorted)
			if (!nextByFile.containsKey(f) && !rivals.containsKey(f)) place(f, true, next, nextByFile, now, change);
		for (Slot slot : unsettled) settle(slot, next, nextByFile, now, change);
		entries = next;
		byFile = nextByFile;
		// published again after the maps, so that a snapshot taken meanwhile is followed by one that has them
		if (!sorted.isEmpty() || version.get() != before) version.incrementAndGet();
	}

	/** Tell {@code gate} of each publication before an entry takes it away (M3-A-F2): what presents frames lets go of it first. */
	void setGate(Consumer<Entry.Shown> gate) {
		this.gate.set(gate);
	}

	/** The producer said this file is finished (a hint of its own, C2.7): it is checked without waiting to be quiet. */
	boolean announce(File f) {
		Entry e = byFile.get(f);
		if (e == null) return false;
		e.announce();
		return true;
	}

	/** Allow a file of unverified depth that arrives later to be shown, which by default it is not (C2.3). */
	void setShowUnverifiedArrivals(boolean show) {
		showUnverifiedArrivals = show;
	}

	/** Increases with every change: a file found, changed, checked, withdrawn or removed. */
	long version() {
		return version.get();
	}

	/** The time points of one display plan as they are now, with their entries. */
	Snapshot snapshot(int position, int channel) {
		long v = version.get();		// read first: a change made meanwhile is published again
		List<TimeKey> times = new ArrayList<TimeKey>();
		List<Entry> list = new ArrayList<Entry>();
		for (Map.Entry<TimeKey, TreeMap<Integer, Entry>> e : entries.entrySet()) {
			Entry entry = e.getValue().get(channel);
			if (e.getKey().position == position && entry != null) {
				times.add(e.getKey());
				list.add(entry);
			}
		}
		return new Snapshot(v, position, channel, times, list);
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
		return snapshot(position, channel).times;
	}

	Entry entry(TimeKey time, int channel) {
		TreeMap<Integer, Entry> channels = time == null ? null : entries.get(time);
		return channels == null ? null : channels.get(channel);
	}

	/**
	 * Withhold the entry checked as this file version, if there is one; called when the raw store
	 * finds the file changed under it.
	 *
	 * @return the entry that was withdrawn, or null
	 */
	Entry markChanged(FileKey stale, String why) {
		Entry e = byFile.get(stale.file);
		return e != null && e.channel == stale.channel && e.markChanged(stale, why) ? e : null;
	}

	int fileCount() {
		return byFile.size();
	}

	/** Why particular files cannot be placed or their names cannot be trusted, one line per file. */
	List<String> problems() {
		return Collections.unmodifiableList(new ArrayList<String>(problems));
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
