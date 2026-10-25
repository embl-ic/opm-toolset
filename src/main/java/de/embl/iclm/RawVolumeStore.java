package de.embl.iclm;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.volatiles.VolatileUnsignedShortType;

/**
 * Pixels of raw acquisition files for the Online Deskew View (joint INTERFACES section 2).
 *
 * <p>A store hands out each validated file version as a lazily loaded image: a blocking one for
 * work that needs every value, and a volatile one for BigDataViewer, which draws what is loaded
 * and fills in the rest as it arrives. Both are views of one set of cells, held within the
 * session's accounted byte budget, so the left and right camera halves - two views of one file -
 * load each cell once.
 *
 * <p>The interface grows with the plan: {@code prepare(RenderRequest)} for completed-frame
 * playback arrives in M2, coarser levels with the storage gate.
 */
interface RawVolumeStore extends AutoCloseable {

	/** The full-width raw volume of one file version: x = camera column, y = camera row, z = plane. */
	RandomAccessibleInterval<UnsignedShortType> blocking(RawAcquisition.FileKey key, RawTiffLayout layout);

	/** The same pixels as a volatile image, loaded in the background through the session's queue. */
	RandomAccessibleInterval<VolatileUnsignedShortType> volatileView(RawAcquisition.FileKey key, RawTiffLayout layout);

	/** Whether this file version was found changed; its pixels are then no longer served. */
	boolean isStale(RawAcquisition.FileKey key);

	/** Whether the file on disk is still this version; if not, it becomes stale now. */
	boolean checkGeneration(RawAcquisition.FileKey key);

	CacheStats stats();

	/** Begin the terminal close; never blocks the caller. The future reports how it went. */
	CompletableFuture<CloseResult> closeAsync();

	/** Same as {@link #closeAsync()}, ignoring the result. */
	@Override
	void close();

	/**
	 * The store's byte ledger and what it has done, for the status line and the tests. Every
	 * figure is the store's own accounting of pixel buffers it owns; the JVM and BigDataViewer
	 * have memory of their own beyond it.
	 */
	final class CacheStats {
		/** The accounted budget, and what it holds now: resident cells, reads in progress, read buffers. */
		final long budgetBytes, residentBytes, residentCells, inFlightBytes, inFlight, scratchBytes;
		/** The most each category - and their sum - has held at once. */
		final long peakResidentBytes, peakInFlightBytes, peakScratchBytes, peakTotalBytes;
		final long loads, bytesRead, rereads, evictions, failures, staleReads, refusals, openChannels, files, staleFiles;

		CacheStats(long budgetBytes, long residentBytes, long residentCells, long inFlightBytes, long inFlight,
				long scratchBytes, long peakResidentBytes, long peakInFlightBytes, long peakScratchBytes, long peakTotalBytes,
				long loads, long bytesRead, long rereads, long evictions, long failures, long staleReads, long refusals,
				long openChannels, long files, long staleFiles) {
			this.budgetBytes = budgetBytes;
			this.residentBytes = residentBytes;
			this.residentCells = residentCells;
			this.inFlightBytes = inFlightBytes;
			this.inFlight = inFlight;
			this.scratchBytes = scratchBytes;
			this.peakResidentBytes = peakResidentBytes;
			this.peakInFlightBytes = peakInFlightBytes;
			this.peakScratchBytes = peakScratchBytes;
			this.peakTotalBytes = peakTotalBytes;
			this.loads = loads;
			this.bytesRead = bytesRead;
			this.rereads = rereads;
			this.evictions = evictions;
			this.failures = failures;
			this.staleReads = staleReads;
			this.refusals = refusals;
			this.openChannels = openChannels;
			this.files = files;
			this.staleFiles = staleFiles;
		}

		/** Everything accounted now. */
		long totalBytes() {
			return residentBytes + inFlightBytes + scratchBytes;
		}

		@Override public String toString() {
			return String.format(Locale.US,
					"%.1f of %.0f MB: %.1f MB in %d cells, %.1f MB in %d reads, %.1f MB read buffers; peak %.1f MB "
							+ "(cells %.1f, reads %.1f, buffers %.1f). %.0f MB read in %d loads, %d evicted, %d read again, "
							+ "%d failed, %d stale, %d refused; %d files open",
					totalBytes() / 1e6, budgetBytes / 1e6, residentBytes / 1e6, residentCells, inFlightBytes / 1e6, inFlight,
					scratchBytes / 1e6, peakTotalBytes / 1e6, peakResidentBytes / 1e6, peakInFlightBytes / 1e6,
					peakScratchBytes / 1e6, bytesRead / 1e6, loads, evictions, rereads, failures, staleReads, refusals,
					openChannels);
		}
	}

	/** How a close went: whether every read finished before the deadline, and the ledger afterwards. */
	final class CloseResult {
		final boolean drained;
		final long millis;
		final long abandonedReads;
		/** The ledger once closed: nothing resident; reads and buffers only if the drain timed out. */
		final CacheStats after;

		CloseResult(boolean drained, long millis, long abandonedReads, CacheStats after) {
			this.drained = drained;
			this.millis = millis;
			this.abandonedReads = abandonedReads;
			this.after = after;
		}

		@Override public String toString() {
			String held = after == null ? "" : String.format(Locale.US, "; afterwards %.1f MB accounted, %d files open",
					after.totalBytes() / 1e6, after.openChannels);
			return (drained ? "closed in " + millis + " ms"
					: "closed after " + millis + " ms with " + abandonedReads + " reads still running") + held;
		}
	}
}
