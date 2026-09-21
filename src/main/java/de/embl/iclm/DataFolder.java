package de.embl.iclm;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a folder holds, found the way the OPM Data Viewer finds it.
 *
 * <p>The viewer takes one root folder and lists the datasets under it, in both of the formats
 * this toolset writes: canonical OME-Zarr stores, and deskewed TIFF results in either of the
 * layouts {@link TiffResultDataset} recognises - one sub-folder per view, or every view in one
 * folder. The batch commands that act on results take their input the same way, through this
 * class, so a folder that opens in the viewer is a folder they can process, and the two can
 * never disagree about what is in it.
 *
 * <p>A conversion also wants what the viewer ignores: TIFF volumes that belong to no result
 * dataset, which in practice are the raw acquisition BigTIFFs. Those are the {@link #loose}
 * TIFFs, found only when asked for, and each is sorted by whether it is already Deflate
 * compressed - from its first directory alone, never its pixels.
 *
 * <p>Nothing here writes or opens pixel data. A scan is metadata only, which is what makes it
 * safe to run from a dialog while the user is still typing.
 */
final class DataFolder {

	/** A TIFF volume that is part of no result dataset, and whether it is already Deflate. */
	static final class LooseTiff {
		final File file;
		final boolean deflated;

		LooseTiff(File file, boolean deflated) {
			this.file = file;
			this.deflated = deflated;
		}
	}

	/** What to include beyond the result datasets, and how widely to look for it. */
	static final class Filter {
		String include = "";
		String exclude = "";
		boolean recursive;
		/** A folder to leave out entirely - the run's own output, when it sits under the input. */
		File outputRoot;
	}

	/** How a {@link Watch} scans a path. */
	interface Scan { DataFolder scan(File selection); }
	/** What a dialog does with a scan; {@code found} is null for an empty path. */
	interface Show { void show(DataFolder found); }

	/**
	 * A folder scan that follows a path field while a dialog is open.
	 * <p>
	 * One scan at a time on a daemon thread, each tagged, and a short pause first: a path typed
	 * character by character settles on the last one, and only the newest result is handed back
	 * - on the event thread, where the dialog can use it. A scan is metadata only, but a
	 * network folder can still take a moment, and the dialog must not stall while it does.
	 */
	static final class Watch {
		private final Scan scan;
		private final Show show;
		private final java.util.concurrent.atomic.AtomicInteger latest =
				new java.util.concurrent.atomic.AtomicInteger();
		private String requested;

		Watch(Scan scan, Show show) {
			this.scan = scan;
			this.show = show;
		}

		/** Scan this path, unless it is the one already scanned or being scanned. */
		void request(String path) {
			final String trimmed = path == null ? "" : path.trim();
			if (trimmed.equals(requested)) return;
			requested = trimmed;
			final int ticket = latest.incrementAndGet();
			Shutdown.daemon(new Runnable() {
				@Override public void run() {
					try { Thread.sleep(250); } catch (InterruptedException e) { return; }
					if (ticket != latest.get()) return;		// typed on since
					DataFolder found = null;
					if (!trimmed.isEmpty()) {
						try { found = scan.scan(new File(trimmed)); }
						catch (Throwable unreadable) { found = failed(new File(trimmed), unreadable); }
					}
					final DataFolder result = found;
					java.awt.EventQueue.invokeLater(new Runnable() {
						@Override public void run() { if (ticket == latest.get()) show.show(result); }
					});
				}
			}, "OPM-folder-scan").start();
		}
	}

	private static DataFolder failed(File selection, Throwable why) {
		DataFolder folder = new DataFolder(selection, null);
		folder.problems.add(String.valueOf(why));
		return folder;
	}

	/** The folder that was scanned; the file's folder when a single file was given. */
	final File root;
	/** The one file asked about, when the selection was a file rather than a folder. */
	final File singleFile;
	final List<OmeZarrDataset> zarr = new ArrayList<OmeZarrDataset>();
	final List<TiffResultDataset> results = new ArrayList<TiffResultDataset>();
	final List<LooseTiff> loose = new ArrayList<LooseTiff>();
	/** Anything that could not be read, reported rather than silently dropped. */
	final List<String> problems = new ArrayList<String>();

	private DataFolder(File root, File singleFile) {
		this.root = root;
		this.singleFile = singleFile;
	}

	/**
	 * Find the result datasets under a folder, as the viewer would list them.
	 *
	 * @param selection		: the result root, a view sub-folder, or a path inside an OME-Zarr
	 */
	static DataFolder scanResults(File selection) {
		return scan(selection, null, false);
	}

	/**
	 * Find the result datasets and, when asked for, every other TIFF volume too.
	 *
	 * @param selection		: a folder, a path inside an OME-Zarr store, or one TIFF file
	 * @param filter		: names to include or exclude and a folder to leave out; may be null
	 * @param withLoose		: also list the TIFF volumes that belong to no result dataset
	 */
	static DataFolder scan(File selection, Filter filter, boolean withLoose) {
		if (selection == null) return new DataFolder(null, null);
		final Filter looseFilter = filter == null ? new Filter() : filter;
		/* One TIFF named outright is one volume to convert, whatever sits beside it. Checked
		 * before resolving, which would widen a file to its folder. */
		if (withLoose && selection.isFile() && isTiff(selection)) {
			DataFolder single = new DataFolder(selection.getParentFile(), selection);
			single.addLoose(selection);
			return single;
		}
		File resolved = OmeZarrDataset.resolveDatasetFolder(selection);
		DataFolder found = new DataFolder(resolved, null);
		if (!resolved.isDirectory()) return found;
		try {
			for (OmeZarrDataset dataset : OmeZarrDataset.discover(resolved))
				if (!inside(dataset.getRoot(), looseFilter.outputRoot)
						&& accepts(dataset.getDisplayName(), looseFilter))
					found.zarr.add(dataset);
		} catch (Throwable unreadable) {
			found.problems.add("OME-Zarr scan failed: " + unreadable);
		}
		Set<String> claimed = new HashSet<String>();
		try {
			for (TiffResultDataset dataset : TiffResultDataset.discover(resolved)) {
				if (!accepts(dataset.getDisplayName(), looseFilter)) continue;
				if (inside(dataset.getView(views(dataset).get(0)).folder, looseFilter.outputRoot))
					continue;
				found.results.add(dataset);
				for (String view : views(dataset))
					for (TiffResultDataset.Frame frame : dataset.getView(view).getFrames())
						claimed.add(canonical(frame.file));
			}
		} catch (Throwable unreadable) {
			found.problems.add("TIFF result scan failed: " + unreadable);
		}
		if (!withLoose) return found;

		List<File> tiffs = BatchProcessingUtils.listTiffs(resolved, looseFilter.include,
				looseFilter.exclude, looseFilter.recursive);
		for (File tiff : BatchProcessingUtils.excludeTree(tiffs, looseFilter.outputRoot)) {
			if (claimed.contains(canonical(tiff))) continue;
			found.addLoose(tiff);
		}
		return found;
	}

	private void addLoose(File tiff) {
		boolean deflated = false;
		try {
			deflated = FastTiffReader.isDeflate(FastTiffReader.firstPlaneCompression(tiff));
		} catch (Throwable unreadable) {
			/* Not our reader's kind of TIFF. Leave it to the conversion, which falls back to
			 * ImageJ and reports a file it cannot open; here it is simply not deflated. */
		}
		loose.add(new LooseTiff(tiff, deflated));
	}

	/** The views of a TIFF result, volume first: what its per-view check boxes offer. */
	static List<String> views(TiffResultDataset dataset) {
		List<String> keys = new ArrayList<String>();
		if (dataset.hasVolume()) keys.add(TiffResultDataset.VOLUME);
		keys.addAll(dataset.getAvailableProjections());
		return keys;
	}

	/** Every view any of these results holds, in canonical spelling (mean, not avg). */
	Set<String> viewKinds() {
		Set<String> kinds = new LinkedHashSet<String>();
		for (TiffResultDataset dataset : results)
			for (String view : views(dataset)) kinds.add(canonicalView(view));
		return kinds;
	}

	/**
	 * One spelling per kind of view. A TIFF result names a mean projection {@code avg} in its
	 * file names while the viewer and every dialog call it {@code mean}; the check boxes use the
	 * dialog's word and match either.
	 */
	static String canonicalView(String key) {
		if (key == null) return null;
		String lower = key.toLowerCase(Locale.ROOT);
		if (lower.startsWith("avg")) return "mean" + key.substring(3).toUpperCase(Locale.ROOT);
		return key;
	}

	int deflatedLooseCount() {
		int count = 0;
		for (LooseTiff tiff : loose) if (tiff.deflated) count++;
		return count;
	}

	boolean isEmpty() {
		return zarr.isEmpty() && results.isEmpty() && loose.isEmpty();
	}

	/** One line for a dialog: what was found, counted by kind. */
	String summary() {
		if (root == null || (!root.isDirectory() && singleFile == null))
			return "Not a folder: nothing found.";
		List<String> parts = new ArrayList<String>();
		if (!results.isEmpty()) parts.add(count(results.size(), "TIFF result"));
		if (!zarr.isEmpty()) parts.add(count(zarr.size(), "OME-Zarr dataset"));
		if (!loose.isEmpty()) {
			int deflated = deflatedLooseCount();
			parts.add(count(loose.size(), "other TIFF volume")
					+ (deflated > 0 ? " (" + deflated + " already deflated)" : ""));
		}
		String text = parts.isEmpty() ? "Nothing found" : "Found " + join(parts);
		return problems.isEmpty() ? text + "." : text + "; " + problems.size() + " unreadable.";
	}

	private static String count(int n, String noun) {
		return n + " " + noun + (n == 1 ? "" : "s");
	}

	private static String join(List<String> parts) {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < parts.size(); i++) {
			if (i > 0) text.append(i == parts.size() - 1 ? " and " : ", ");
			text.append(parts.get(i));
		}
		return text.toString();
	}

	private static boolean accepts(String name, Filter filter) {
		return BatchProcessingUtils.accepts(name, filter.include, filter.exclude);
	}

	private static boolean inside(File file, File directory) {
		return file != null && directory != null && BatchProcessingUtils.isInside(file, directory);
	}

	static boolean isTiff(File file) {
		String name = file.getName().toLowerCase(Locale.ROOT);
		return name.endsWith(".tif") || name.endsWith(".tiff");
	}

	private static String canonical(File file) {
		try { return file.getCanonicalPath(); }
		catch (java.io.IOException unresolvable) { return file.getAbsolutePath(); }
	}
}
