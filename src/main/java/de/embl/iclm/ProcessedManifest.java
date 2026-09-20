package de.embl.iclm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A durable record of which input files a live or batch run has already finished.
 *
 * <p>Without this, "already processed" is only ever an in-memory set, so an interrupted run
 * starts over, a volume that failed once is never retried, and joining an acquisition that
 * is already under way is guesswork. With it, all three become the same lookup: enumerate
 * the folder, ask the manifest what is missing, queue the difference.
 *
 * <p>The file is a tab-separated log beside the output, appended to and flushed as each
 * volume finishes, so a run that is killed mid-way still leaves a usable record. An entry
 * only counts as done when the input's size and modification time still match what was
 * recorded - a file that was replaced after processing is offered again rather than
 * silently skipped.
 */
public class ProcessedManifest {

	/** Name of the manifest file written into the output folder. */
	public static final String FILE_NAME = ".opm-processed.tsv";

	private static final String HEADER = "#path\tsize\tmodified\tstatus\tfinished\tformats";
	private static final String OK = "OK";
	private static final String FAILED = "FAILED";
	private static final String TIFF = "TIFF";
	private static final String ZARR = "OME-Zarr";

	private final File file;
	private final Map<String, Entry> entries = new HashMap<String, Entry>();

	private static class Entry {
		long size;
		long modified;
		boolean recordedDone;
		final Set<String> formats = new LinkedHashSet<String>();
	}

	/**			Open, creating the folder if needed, and read whatever is already recorded
	 *
	 * @param folder			: folder the manifest lives in, normally the output root
	 */
	public ProcessedManifest (
			File folder
			) {
		this.file = new File ( folder, FILE_NAME );
		load ();
	}

	/** The manifest file itself, so callers can report or delete it. */
	public File getFile () {
		return file;
	}

	/** How many inputs are recorded as finished. */
	public synchronized int doneCount () {
		int n = 0;
		for (Entry e : entries.values()) if (e.recordedDone) n++;
		return n;
	}

	/**			Whether this input has already been processed successfully and is unchanged
	 * <p>		A recorded input whose size or modification time has since changed is not
	 * 			treated as done: the file on disk is no longer the one that was processed.
	 *
	 * @param input				: the input file to test
	 * <p>
	 * @return					: true when this exact file has already been finished
	 */
	public synchronized boolean isDone (File input, String format) {
		if (input == null) return false;
		Entry entry = entries.get ( key(input) );
		Set<String> requested = formatsFor(format);
		if (entry == null || requested.isEmpty() || !entry.formats.containsAll(requested)) return false;
		return entry.size == input.length() && entry.modified == input.lastModified();
	}

	/** Compatibility for older callers: the original manifest described TIFF output only. */
	public synchronized boolean isDone (File input) {
		return isDone(input, Parameter.FORMAT_TIFF);
	}

	/** Record that an input finished successfully. */
	public synchronized void markDone (File input, String format) {
		record ( input, true, format );
	}

	/** Compatibility for older callers that only produced TIFF. */
	public synchronized void markDone (File input) {
		markDone(input, Parameter.FORMAT_TIFF);
	}

	/**			Record that an input failed
	 * <p>		A failure is written for the log, but deliberately does not mark the input
	 * 			done, so the next pass offers it again. A transient error - a truncated file,
	 * 			a busy GPU - should cost one retry, not one missing volume in the series.
	 */
	public synchronized void markFailed (
			File input
			) {
		record ( input, false, null );
	}

	/** Record a failed attempt without forgetting other formats that already succeeded. */
	public synchronized void markFailed (File input, String format) {
		record ( input, false, format );
	}

	private void record (
			File input,
			boolean done,
			String format
			) {
		if (input == null) return;
		Entry entry = new Entry();
		entry.size = input.length();
		entry.modified = input.lastModified();
		Entry previous = entries.get(key(input));
		if (previous != null && previous.size == entry.size && previous.modified == entry.modified)
			entry.formats.addAll(previous.formats);
		Set<String> attempted = formatsFor(format);
		if (done) entry.formats.addAll(attempted);
		else if (format == null) entry.formats.clear();
		else entry.formats.removeAll(attempted);
		entry.recordedDone = done || !entry.formats.isEmpty();
		entries.put ( key(input), entry );
		append ( input, entry, done );
	}

	private void append (
			File input,
			Entry entry,
			boolean done
			) {
		Writer writer = null;
		try {
			File parent = file.getParentFile();
			if (parent != null && !parent.isDirectory()) parent.mkdirs();
			boolean fresh = !file.isFile() || file.length() == 0;
			writer = new OutputStreamWriter ( new FileOutputStream(file, true), "UTF-8" );
			if (fresh) writer.write ( HEADER + "\n" );
			writer.write ( String.format ( Locale.US, "%s\t%d\t%d\t%s\t%d\t%s%n",
					input.getAbsolutePath(), entry.size, entry.modified,
					done ? OK : FAILED, System.currentTimeMillis(), join(entry.formats) ) );
			writer.flush();				// a killed run must still leave the record behind
		} catch (IOException e) {
			// a manifest that cannot be written must not stop processing; the in-memory
			// map still prevents repeats for the rest of this session
		} finally {
			if (writer != null) try { writer.close(); } catch (IOException ignored) { }
		}
	}

	private void load () {
		if (!file.isFile()) return;
		BufferedReader reader = null;
		try {
			reader = new BufferedReader ( new FileReader(file) );
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isEmpty() || line.charAt(0) == '#') continue;
				String[] parts = line.split("\t", -1);
				if (parts.length < 4) continue;
				try {
					Entry entry = new Entry();
					entry.size = Long.parseLong ( parts[1] );
					entry.modified = Long.parseLong ( parts[2] );
					boolean successfulRow = OK.equals(parts[3]);
					if (!successfulRow && !FAILED.equals(parts[3])) continue;
					if (parts.length >= 6) entry.formats.addAll(parseFormats(parts[5]));
					/* A legacy OK row remains visible in doneCount, but has no format and can
					 * therefore never satisfy isDone. The first post-upgrade run re-verifies it. */
					entry.recordedDone = successfulRow || !entry.formats.isEmpty();
					// later lines win, so a retry that succeeded overrides an earlier failure
					entries.put ( key(new File(parts[0])), entry );
				} catch (NumberFormatException ignored) {
					// a corrupt line costs one file's memory of being processed, nothing more
				}
			}
		} catch (IOException e) {
			// unreadable manifest: start from empty rather than refusing to run
		} finally {
			if (reader != null) try { reader.close(); } catch (IOException ignored) { }
		}
	}

	private static Set<String> formatsFor(String format) {
		Set<String> formats = new LinkedHashSet<String>();
		if (Parameter.FORMAT_TIFF.equals(format) || Parameter.FORMAT_BOTH.equals(format))
			formats.add(TIFF);
		if (Parameter.FORMAT_ZARR.equals(format) || Parameter.FORMAT_BOTH.equals(format))
			formats.add(ZARR);
		return formats;
	}

	private static Set<String> parseFormats(String value) {
		Set<String> formats = new LinkedHashSet<String>();
		if (value == null || value.trim().isEmpty()) return formats;
		for (String format : value.split(",")) {
			format = format.trim();
			if (TIFF.equals(format) || ZARR.equals(format)) formats.add(format);
		}
		return formats;
	}

	private static String join(Set<String> formats) {
		StringBuilder text = new StringBuilder();
		for (String format : formats) {
			if (text.length() > 0) text.append(',');
			text.append(format);
		}
		return text.toString();
	}

	private static String key (
			File input
			) {
		return input.getAbsolutePath().toLowerCase ( Locale.ROOT );
	}
}
