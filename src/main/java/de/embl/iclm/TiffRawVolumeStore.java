package de.embl.iclm;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import bdv.cache.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.Cache;
import net.imglib2.cache.CacheLoader;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.ref.GuardedStrongRefLoaderCache;
import net.imglib2.cache.util.KeyBimap;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileShortArray;
import net.imglib2.img.cell.Cell;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.volatiles.VolatileUnsignedShortType;

/**
 * Raw acquisition TIFFs read directly, a strip at a time, for the Online Deskew View
 * (joint contracts C3.1 - C3.8).
 *
 * <ul>
 * <li><b>Cells are the TIFF's own strips</b>: the full camera width by {@code rowsPerStrip} rows
 *     of one plane, one positioned read each, nothing to decode. A deskewed XY slice needs one
 *     camera row of every plane, which is one strip per plane. Both camera halves are views of the
 *     same full-width cells, so a strip is read once for both.</li>
 * <li><b>One bounded cache for the session</b>: every file's cells live in one
 *     {@link GuardedStrongRefLoaderCache}, keyed by file version and cell index, limited to the
 *     budget divided by the cell size. Cells are all the same size within an acquisition, so the
 *     count bound is a byte bound for resident cells; a cell evicted while a renderer still holds
 *     it stays valid (the guard) and is counted again once it is loaded anew (C3.4).</li>
 * <li><b>Reads run on dedicated IO threads</b>, never on the thread that asked. A
 *     {@link FileChannel} is interruptible: interrupting one thread inside a read closes the
 *     channel for every reader of that file. BigDataViewer interrupts its fetcher threads when its
 *     queue shuts down, so a fetcher only waits for a read here; if it is interrupted it stops
 *     waiting, and the read finishes or is dropped on its own (C3.7).</li>
 * <li><b>File handles are leased</b> from a small pool and closed only when no read holds them
 *     (C3.6). They are NIO channels: an open NIO read handle leaves the acquisition software free to
 *     rename or delete the file, which a {@code RandomAccessFile} would not.</li>
 * <li><b>Closing never blocks the caller</b>: {@link #closeAsync()} refuses new reads, lets the
 *     running ones finish within a deadline on a daemon thread, then closes the channels and
 *     reports whether it drained in time (C7.2).</li>
 * </ul>
 */
final class TiffRawVolumeStore implements RawVolumeStore {

	/** How long a close waits for running reads before closing their channels under them. */
	static final long CLOSE_DEADLINE_MS = 5000;
	/** File handles kept open at most, beyond those a read is using. */
	static final int MAX_OPEN_FILES = 16;

	private final long cellBytes;
	private final long maxCells;
	private final GuardedStrongRefLoaderCache<CellKey, Cell<VolatileShortArray>> cells;
	private final ExecutorService io;
	private final SharedQueue queue;
	private final Channels channels = new Channels(MAX_OPEN_FILES);
	private final ConcurrentHashMap<RawAcquisition.FileKey, Volumes> volumes =
			new ConcurrentHashMap<RawAcquisition.FileKey, Volumes>();
	private final AtomicLong loads = new AtomicLong(), bytesRead = new AtomicLong(), failures = new AtomicLong(),
			inFlight = new AtomicLong();
	private final CompletableFuture<CloseResult> closed = new CompletableFuture<CloseResult>();
	private volatile boolean closing;

	/** One reusable read buffer per IO thread, so scratch memory is bounded by the thread count. */
	private static final ThreadLocal<ByteBuffer> SCRATCH = new ThreadLocal<ByteBuffer>();

	/**
	 * @param budgetBytes	: memory the resident cells may take
	 * @param cellBytes		: size of one cell, {@link #cellBytes(RawTiffLayout)} of the acquisition
	 * @param ioThreads		: threads that read from disk
	 * @param queue			: the session's BigDataViewer fetch queue, for the volatile views
	 */
	TiffRawVolumeStore(long budgetBytes, long cellBytes, int ioThreads, SharedQueue queue) {
		this.cellBytes = Math.max(1, cellBytes);
		this.maxCells = Math.max(16, budgetBytes / this.cellBytes);
		this.cells = new GuardedStrongRefLoaderCache<CellKey, Cell<VolatileShortArray>>(maxCells);
		this.io = Executors.newFixedThreadPool(Math.max(1, ioThreads), Shutdown.daemonThreads("OPM-raw-io"));
		this.queue = queue;
	}

	/** Rows per cell: the TIFF's own strip. */
	static int cellRows(RawTiffLayout layout) {
		return layout.rowsPerStrip;
	}

	/** Bytes of one full cell of this layout. */
	static long cellBytes(RawTiffLayout layout) {
		return 2L * layout.width * cellRows(layout);
	}

	@Override
	public RandomAccessibleInterval<UnsignedShortType> blocking(RawAcquisition.FileKey key, RawTiffLayout layout) {
		return volumes(key, layout).blocking;
	}

	@Override
	public RandomAccessibleInterval<VolatileUnsignedShortType> volatileView(RawAcquisition.FileKey key, RawTiffLayout layout) {
		Volumes v = volumes(key, layout);
		synchronized (v) {
			if (v.volatileView == null) v.volatileView = VolatileViews.wrapAsVolatile(v.blocking, queue);
			return v.volatileView;
		}
	}

	private Volumes volumes(final RawAcquisition.FileKey key, final RawTiffLayout layout) {
		if (closing) throw new IllegalStateException("The raw volume store is closed");
		if (!layout.supported()) throw new IllegalArgumentException(key + ": " + layout.unsupportedReason);
		Volumes v = volumes.get(key);
		if (v == null) {
			Volumes made = new Volumes(key, layout);
			v = volumes.putIfAbsent(key, made);
			if (v == null) v = made;
		}
		return v;
	}

	/**
	 * Run the cache's pending eviction now. The bound is enforced asynchronously - the backing
	 * cache evicts in the background, and an evicted cell stays reachable through its weak guard
	 * until the garbage collector clears it - so the resident set can briefly exceed the budget.
	 */
	void cleanUp() {
		cells.cleanUp();
	}

	@Override
	public CacheStats stats() {
		return new CacheStats(maxCells * cellBytes, cellBytes, maxCells, loads.get(), bytesRead.get(), failures.get(),
				inFlight.get(), channels.size(), volumes.size());
	}

	@Override
	public synchronized CompletableFuture<CloseResult> closeAsync() {
		if (closing) return closed;
		closing = true;
		final long start = System.nanoTime();
		io.shutdown();		// no interrupt: a read in progress shares its file's channel
		Thread finisher = Shutdown.daemon(new Runnable() {
			@Override public void run() {
				boolean drained;
				try {
					drained = io.awaitTermination(CLOSE_DEADLINE_MS, TimeUnit.MILLISECONDS);
				} catch (InterruptedException e) {
					drained = false;
				}
				long abandoned = inFlight.get();
				channels.terminate();
				cells.invalidateAll(10000);
				volumes.clear();
				closed.complete(new CloseResult(drained, (System.nanoTime() - start) / 1000000, abandoned));
			}
		}, "OPM-raw-store-close");
		finisher.start();
		return closed;
	}

	@Override
	public void close() {
		closeAsync();
	}

	/** One file version's images over the shared cache. */
	private final class Volumes {
		final CachedCellImg<UnsignedShortType, VolatileShortArray> blocking;
		RandomAccessibleInterval<VolatileUnsignedShortType> volatileView;

		Volumes(final RawAcquisition.FileKey key, final RawTiffLayout layout) {
			final CellGrid grid = new CellGrid(layout.dimensions(), new int[] { layout.width, cellRows(layout), 1 });
			CacheLoader<Long, Cell<VolatileShortArray>> loader = new CacheLoader<Long, Cell<VolatileShortArray>>() {
				@Override public Cell<VolatileShortArray> get(Long index) throws Exception {
					return load(key, layout, grid, index);
				}
			};
			Cache<Long, Cell<VolatileShortArray>> cache = cells.mapKeys(KeyBimap.<Long, CellKey>build(
					index -> new CellKey(key, index),
					cell -> cell.file.equals(key) ? cell.index : null)).withLoader(loader);
			blocking = new CachedCellImg<UnsignedShortType, VolatileShortArray>(grid, new UnsignedShortType(), cache,
					new VolatileShortArray(1, true));
		}
	}

	/** Fetch one cell: the read itself runs on an IO thread; this thread only waits for it. */
	private Cell<VolatileShortArray> load(final RawAcquisition.FileKey key, final RawTiffLayout layout,
			CellGrid grid, long index) throws Exception {
		if (closing) throw new IllegalStateException("The raw volume store is closed");
		final long[] min = new long[3];
		final int[] dims = new int[3];
		grid.getCellDimensions(index, min, dims);
		final short[] data = new short[dims[0] * dims[1] * dims[2]];
		Future<Object> read;
		try {
			read = io.submit(() -> {
				if (closing) throw new IllegalStateException("The raw volume store is closed");
				inFlight.incrementAndGet();
				try {
					readCell(key, layout, min, dims, data);
				} finally {
					inFlight.decrementAndGet();
				}
				return null;
			});
		} catch (RejectedExecutionException closedMeanwhile) {
			throw new IllegalStateException("The raw volume store is closed");
		}
		try {
			read.get();
		} catch (InterruptedException e) {
			// stop waiting; the read is left to finish on its own, never interrupted
			Thread.currentThread().interrupt();
			throw e;
		} catch (ExecutionException e) {
			failures.incrementAndGet();
			Throwable cause = e.getCause();
			throw cause instanceof Exception ? (Exception) cause : new IOException(cause);
		}
		loads.incrementAndGet();
		bytesRead.addAndGet(2L * data.length);
		return new Cell<VolatileShortArray>(dims, min, new VolatileShortArray(data, true));
	}

	private void readCell(RawAcquisition.FileKey key, RawTiffLayout layout, long[] min, int[] dims, short[] data)
			throws IOException {
		int x0 = (int) min[0], y0 = (int) min[1], k = (int) min[2], nx = dims[0], ny = dims[1];
		boolean fullRows = x0 == 0 && nx == layout.width;
		int bytes = fullRows ? 2 * nx * ny : 2 * nx;
		for (int attempt = 0;; attempt++) {
			try (Channels.Lease lease = channels.acquire(key)) {
				if (fullRows) {
					ByteBuffer buffer = read(lease.channel, bytes, layout.offset(0, y0, k), layout.order);
					buffer.asShortBuffer().get(data, 0, nx * ny);
				} else for (int y = 0; y < ny; y++) {
					ByteBuffer buffer = read(lease.channel, bytes, layout.offset(x0, y0 + y, k), layout.order);
					buffer.asShortBuffer().get(data, y * nx, nx);
				}
				return;
			} catch (ClosedChannelException e) {
				// closed under this read: reopen once, only while the store is still open
				if (closing || attempt > 0) throw e;
				channels.discard(key);
			}
		}
	}

	private static ByteBuffer read(FileChannel channel, int bytes, long position, ByteOrder order) throws IOException {
		ByteBuffer buffer = SCRATCH.get();
		if (buffer == null || buffer.capacity() < bytes) SCRATCH.set(buffer = ByteBuffer.allocateDirect(bytes));
		buffer.clear();
		buffer.limit(bytes);
		while (buffer.hasRemaining()) {
			int n = channel.read(buffer, position);
			if (n < 0) throw new EOFException("The file ended before its layout said it would");
			position += n;
		}
		buffer.flip();
		buffer.order(order);
		return buffer;
	}

	/** Key of one cell in the session cache: file version and cell index. */
	private static final class CellKey {
		final RawAcquisition.FileKey file;
		final long index;

		CellKey(RawAcquisition.FileKey file, long index) {
			this.file = file;
			this.index = index;
		}

		@Override public boolean equals(Object o) {
			return o instanceof CellKey && ((CellKey) o).index == index && ((CellKey) o).file.equals(file);
		}

		@Override public int hashCode() {
			return file.hashCode() * 31 + Long.hashCode(index);
		}
	}

	/** A small pool of leased NIO file handles; a handle closes only when no read holds it. */
	private static final class Channels {
		private final int maxOpen;
		private final LinkedHashMap<RawAcquisition.FileKey, Handle> open =
				new LinkedHashMap<RawAcquisition.FileKey, Handle>(16, 0.75f, true);
		private boolean terminal;

		Channels(int maxOpen) {
			this.maxOpen = maxOpen;
		}

		private static final class Handle {
			final FileChannel channel;
			int leases;

			Handle(FileChannel channel) {
				this.channel = channel;
			}
		}

		final class Lease implements AutoCloseable {
			final FileChannel channel;
			private final RawAcquisition.FileKey key;
			private final Handle handle;

			Lease(RawAcquisition.FileKey key, Handle handle) {
				this.key = key;
				this.handle = handle;
				this.channel = handle.channel;
			}

			@Override public void close() {
				release(this);
			}
		}

		synchronized Lease acquire(RawAcquisition.FileKey key) throws IOException {
			if (terminal) throw new ClosedChannelException();
			Handle h = open.get(key);
			if (h == null || !h.channel.isOpen()) {
				if (!key.current()) throw new IOException(key.file.getName() + " changed after it was checked");
				h = new Handle(FileChannel.open(key.file.toPath(), StandardOpenOption.READ));
				open.put(key, h);
			}
			h.leases++;
			trim();
			return new Lease(key, h);
		}

		private synchronized void release(Lease lease) {
			lease.handle.leases--;
			boolean current = open.get(lease.key) == lease.handle;
			if (lease.handle.leases == 0 && (terminal || !current)) {
				quietClose(lease.handle.channel);
				if (current) open.remove(lease.key);
			}
			trim();
		}

		/** Forget a handle that was closed under its readers; it is reopened on the next acquire. */
		synchronized void discard(RawAcquisition.FileKey key) {
			Handle h = open.remove(key);
			if (h != null && h.leases == 0) quietClose(h.channel);
		}

		/** Close the least recently used idle handles beyond the limit. */
		private void trim() {
			Iterator<Map.Entry<RawAcquisition.FileKey, Handle>> it = open.entrySet().iterator();
			while (open.size() > maxOpen && it.hasNext()) {
				Handle h = it.next().getValue();
				if (h.leases == 0) {
					quietClose(h.channel);
					it.remove();
				}
			}
		}

		synchronized int size() {
			return open.size();
		}

		/** Refuse every new lease and close every handle; a read still running fails and is not retried. */
		synchronized void terminate() {
			terminal = true;
			for (Handle h : open.values()) quietClose(h.channel);
			open.clear();
		}

		private static void quietClose(FileChannel channel) {
			try {
				channel.close();
			} catch (IOException ignored) {
				// closing a read-only handle; nothing to recover
			}
		}
	}
}
