package de.embl.iclm;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Tracks a continuous quiet period and then applies the structural TIFF completion check.
 *
 * <p>Kept outside the Swing listener so its timing can be exercised with a real chunked file
 * writer. WatchService calls {@link #observe(File)} for every create/modify event; polling is
 * still authoritative for changed size or modification time when an event is coalesced.
 */
final class TiffReadinessGate {

	interface Running {
		boolean get();
	}

	private final ConcurrentHashMap<String, Observation> observations =
			new ConcurrentHashMap<String, Observation>();

	void reset() {
		observations.clear();
	}

	/** Record an event or path announcement, including a write that leaves size unchanged. */
	void observe(File file) {
		if (file != null) observation(file).changed();
	}

	void forget(File file) {
		if (file != null) observations.remove(key(file));
	}

	/**
	 * Wait until the file has been quiet for the requested duration and is structurally ready.
	 * A false result means cancellation, disappearance, or this queue turn's timeout.
	 */
	boolean await(File file, Running running, long pollMs, long quietMs, long timeoutMs)
			throws InterruptedException {
		if (file == null || running == null) return false;
		long pollNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, pollMs));
		long quietNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, quietMs));
		long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMs));
		Observation observed = observation(file);
		long deadline = System.nanoTime() + timeoutNanos;
		while (running.get() && System.nanoTime() <= deadline) {
			if (!file.isFile()) return false;
			long generation;
			long quietSince;
			long sampledSize;
			long sampledModified;
			long now = System.nanoTime();
			synchronized (observed) {
				long size = file.length();
				long modified = file.lastModified();
				if (observed.size == Long.MIN_VALUE) {
					observed.size = size;
					observed.modified = modified;
				} else if (size != observed.size || modified != observed.modified) {
					observed.size = size;
					observed.modified = modified;
					observed.quietSinceNanos = now;
					observed.generation++;
				}
				generation = observed.generation;
				quietSince = observed.quietSinceNanos;
				sampledSize = observed.size;
				sampledModified = observed.modified;
			}

			if (now - quietSince >= quietNanos && TiffCompletionCheck.isReady(file)) {
				/* Reject a file that changed during the metadata scan, even if the operating
				 * system has not delivered its WatchService event yet. */
				synchronized (observed) {
					long afterSize = file.length();
					long afterModified = file.lastModified();
					if (afterSize != sampledSize || afterModified != sampledModified) {
						observed.size = afterSize;
						observed.modified = afterModified;
						observed.quietSinceNanos = System.nanoTime();
						observed.generation++;
					} else if (generation == observed.generation
							&& System.nanoTime() - observed.quietSinceNanos >= quietNanos) {
						return running.get();
					}
				}
			}
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0) break;
			TimeUnit.NANOSECONDS.sleep(Math.min(pollNanos, remaining));
		}
		return false;
	}

	private Observation observation(File file) {
		String path = key(file);
		Observation existing = observations.get(path);
		if (existing != null) return existing;
		Observation created = new Observation();
		Observation raced = observations.putIfAbsent(path, created);
		return raced == null ? created : raced;
	}

	private static String key(File file) {
		return file.getAbsolutePath().toLowerCase(Locale.ROOT);
	}

	private static final class Observation {
		long size = Long.MIN_VALUE;
		long modified = Long.MIN_VALUE;
		long quietSinceNanos = System.nanoTime();
		long generation = 0;

		synchronized void changed() {
			quietSinceNanos = System.nanoTime();
			generation++;
		}
	}
}
