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
 * and fills in the rest as it arrives. Both are views of one cell cache, bounded by the session's
 * byte budget, so the left and right camera halves - two views of one file - load each cell once.
 *
 * <p>The interface grows with the plan: {@code prepare(RenderRequest)} for completed-frame
 * playback arrives in M2, coarser levels with the storage gate.
 */
interface RawVolumeStore extends AutoCloseable {

	/** The full-width raw volume of one file version: x = camera column, y = camera row, z = plane. */
	RandomAccessibleInterval<UnsignedShortType> blocking(RawAcquisition.FileKey key, RawTiffLayout layout);

	/** The same pixels as a volatile image, loaded in the background through the session's queue. */
	RandomAccessibleInterval<VolatileUnsignedShortType> volatileView(RawAcquisition.FileKey key, RawTiffLayout layout);

	CacheStats stats();

	/** Begin the terminal close; never blocks the caller. The future reports how it went. */
	CompletableFuture<CloseResult> closeAsync();

	/** Same as {@link #closeAsync()}, ignoring the result. */
	@Override
	void close();

	/** What the store holds and has done, for the status line and the tests. */
	final class CacheStats {
		final long budgetBytes, cellBytes, maxCells, loads, bytesRead, failures, inFlight, openChannels, files;

		CacheStats(long budgetBytes, long cellBytes, long maxCells, long loads, long bytesRead, long failures,
				long inFlight, long openChannels, long files) {
			this.budgetBytes = budgetBytes;
			this.cellBytes = cellBytes;
			this.maxCells = maxCells;
			this.loads = loads;
			this.bytesRead = bytesRead;
			this.failures = failures;
			this.inFlight = inFlight;
			this.openChannels = openChannels;
			this.files = files;
		}

		@Override public String toString() {
			return String.format(Locale.US, "%.0f MB read in %d cells, budget %.0f MB (%d cells), %d failed, %d loading, %d files open",
					bytesRead / 1e6, loads, budgetBytes / 1e6, maxCells, failures, inFlight, openChannels);
		}
	}

	/** How a close went: whether every read finished before the deadline. */
	final class CloseResult {
		final boolean drained;
		final long millis;
		final long abandonedReads;

		CloseResult(boolean drained, long millis, long abandonedReads) {
			this.drained = drained;
			this.millis = millis;
			this.abandonedReads = abandonedReads;
		}

		@Override public String toString() {
			return drained ? "closed in " + millis + " ms"
					: "closed after " + millis + " ms with " + abandonedReads + " reads still running";
		}
	}
}
