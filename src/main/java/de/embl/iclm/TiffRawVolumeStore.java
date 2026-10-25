package de.embl.iclm;

import java.io.EOFException;
import java.io.IOException;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import bdv.cache.SharedQueue;
import bdv.img.cache.VolatileCachedCellImg;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.volatiles.CacheHints;
import net.imglib2.cache.volatiles.LoadingStrategy;
import net.imglib2.img.basictypeaccess.ShortAccess;
import net.imglib2.img.basictypeaccess.volatiles.VolatileShortAccess;
import net.imglib2.img.cell.Cell;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.img.cell.LazyCellImg;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.volatiles.VolatileUnsignedShortType;

/**
 * Raw acquisition TIFFs read directly, a strip at a time, for the Online Deskew View
 * (joint contracts C3.1 - C3.8, C2.6, C7.2).
 *
 * <ul>
 * <li><b>Cells are the TIFF's own strips</b>: the full camera width by {@code rowsPerStrip} rows
 *     of one plane, one positioned read each, nothing to decode. A deskewed XY slice needs one
 *     camera row of every plane, which is one strip per plane. Both camera halves - and the
 *     blocking and the volatile image - are views of the same full-width cells, so a strip is
 *     read and held once.</li>
 * <li><b>One byte ledger for the session</b> (C3.4, C3.5). Every pixel buffer the store owns is
 *     accounted at its exact size: cells resident in the cache, cells being read (reserved before
 *     their buffer is allocated), and the IO threads' read buffers. A load is admitted only when
 *     the total stays within the budget; to make room the least recently used cells are evicted
 *     there and then, synchronously. Strips of different heights and the shorter last strip are
 *     each counted as what they are.</li>
 * <li><b>An evicted cell gives its pixels back, and no access is ever left reading nothing.</b>
 *     A cell's data lives in a {@link Slot} that the cells handed to images only point to;
 *     eviction takes the array out of the slot, so the release is real at once and admission never
 *     waits for the garbage collector. An access still on an evicted cell reads it again through
 *     the store - a blocking access always did; a volatile one must too, because ImgLib2's volatile
 *     type copies a cell's validity when it enters the cell and never asks again, so an emptied
 *     cell would read zeros it believes valid (found by agent-B). A cell lent to the view in the
 *     current frame is never evicted: BigDataViewer draws each frame with new accesses, so the frame
 *     being drawn keeps its pixels and that read never happens while drawing.</li>
 * <li><b>A file found changed keeps the arrays already lent</b>, for the accesses that hold them:
 *     they go on reading the version they were given - never a mixture, never zeros - and no new
 *     access is given the file. Those arrays stay charged, as lent, until the garbage collector
 *     shows no access holds them.</li>
 * <li><b>A file is read only as the version that was checked</b> (C2.6, C3.8). After every read,
 *     before the cell is published, the file's size, modification and creation time are compared
 *     with the checked version. A difference - rewritten, appended, cut, replaced or deleted -
 *     makes the file stale: its cells are dropped, nothing more is read from it, the read that
 *     noticed fails, and the session is told so it can withhold that time point. Pixels of two
 *     versions are never combined.</li>
 * <li><b>Reads run on dedicated IO threads</b>, never on the thread that asked. A
 *     {@link FileChannel} is interruptible: interrupting one thread inside a read closes the
 *     channel for every reader of that file. A requester only waits; if it is interrupted it stops
 *     waiting, and the read finishes on its own (C3.7). Requests are bounded: at most
 *     {@code 2 x ioThreads} reads are admitted at once, the rest wait for one of them.</li>
 * <li><b>File handles are leased</b> from a small pool and closed only when no read holds them
 *     (C3.6). They are NIO channels: an open NIO read handle leaves the acquisition software free to
 *     rename or delete the file, which a {@code RandomAccessFile} would not.</li>
 * <li><b>Closing never blocks the caller</b>: {@link #closeAsync()} refuses new reads, lets the
 *     running ones finish within a deadline on a daemon thread, releases every cell, closes the
 *     channels and reports whether it drained in time and what the ledger held afterwards (C7.2).</li>
 * </ul>
 *
 * <p>The images are ImgLib2's {@link LazyCellImg} and BigDataViewer's {@link VolatileCachedCellImg},
 * each built on this store's own cell lookup. BigDataViewer's {@code WeakRefVolatileCache} is not
 * used: invalidating an entry in it pauses the whole fetch queue until every fetcher is idle,
 * which is no way to drop a cell.
 */
final class TiffRawVolumeStore implements RawVolumeStore {

	/** How long a close waits for running reads before closing their channels under them. */
	static final long CLOSE_DEADLINE_MS = 5000;
	/** File handles kept open at most, beyond those a read is using. */
	static final int MAX_OPEN_FILES = 16;
	/** Cells the budget must hold beyond the reads and read buffers, or a view cannot progress. */
	static final int MIN_RESIDENT_CELLS = 8;
	/** How long a load waits for running reads to make room before it is refused. */
	static final long ADMISSION_WAIT_MS = 2000;
	/** Evicted cells remembered, to count cells read again soon after eviction (thrashing). */
	private static final int RECENT_EVICTIONS = 16384;
	private static final CacheHints VOLATILE_HINTS = new CacheHints(LoadingStrategy.VOLATILE, 0, false);

	private final long budget;
	private final int ioThreads;
	private final int maxReads;
	private final ExecutorService io;
	private final SharedQueue queue;
	private final Channels channels = new Channels(MAX_OPEN_FILES);
	private final ConcurrentHashMap<RawAcquisition.FileKey, Volumes> volumes =
			new ConcurrentHashMap<RawAcquisition.FileKey, Volumes>();
	private final Set<RawAcquisition.FileKey> stale = ConcurrentHashMap.newKeySet();
	private final ThreadLocal<ByteBuffer> scratchBuffer = new ThreadLocal<ByteBuffer>();
	/** Lent volatile accesses: their references are queued here once no image access holds them. */
	private final ReferenceQueue<LentAccess> released = new ReferenceQueue<LentAccess>();
	private final CompletableFuture<CloseResult> closed = new CompletableFuture<CloseResult>();
	private final AtomicLong loads = new AtomicLong(), bytesRead = new AtomicLong(), failures = new AtomicLong(),
			staleReads = new AtomicLong(), refusals = new AtomicLong(), rereads = new AtomicLong(),
			evictions = new AtomicLong();
	private volatile boolean closing;
	private volatile BiConsumer<RawAcquisition.FileKey, String> staleListener;
	private volatile Consumer<String> readHook;

	/* The ledger. Every field below is guarded by `ledger`. */
	private final Object ledger = new Object();
	/** Resident cells in clock order: a cell used since the hand last passed gets a second chance. */
	private final LinkedHashSet<Slot> clock = new LinkedHashSet<Slot>();
	private final LinkedHashMap<String, Boolean> recentlyEvicted = new LinkedHashMap<String, Boolean>(64, 0.75f, false) {
		private static final long serialVersionUID = 1L;
		@Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
			return size() > RECENT_EVICTIONS;
		}
	};
	/** The weak references of lent accesses still alive; held here so that they are queued at all. */
	private final Set<LentRef> lentRefs = new HashSet<LentRef>();
	private long resident, lent, inFlight, scratch, peakResident, peakLent, peakInFlight, peakScratch, peakTotal;
	private int reads;

	/**
	 * @param budgetBytes	: the viewer-data budget: resident cells, reads in progress and read buffers
	 * @param cellBytes		: size of one cell of the acquisition, {@link #cellBytes(RawTiffLayout)}, which
	 * 						  sets the minimum the budget must hold
	 * @param ioThreads		: threads that read from disk
	 * @param queue			: the session's BigDataViewer fetch queue, for the volatile views; may be null
	 * 						  when only blocking images are used
	 * @throws IllegalArgumentException when the budget cannot hold the read buffers, the reads in
	 * 						  progress and {@link #MIN_RESIDENT_CELLS} cells
	 */
	TiffRawVolumeStore(long budgetBytes, long cellBytes, int ioThreads, SharedQueue queue) {
		this.ioThreads = Math.max(1, ioThreads);
		this.maxReads = 2 * this.ioThreads;
		long minimum = minimumBudget(cellBytes, this.ioThreads);
		if (budgetBytes < minimum)
			throw new IllegalArgumentException(String.format(java.util.Locale.US,
					"A cache budget of %d bytes cannot hold %d read buffers, %d reads and %d cells of %d bytes; at least %d bytes are needed",
					budgetBytes, this.ioThreads, maxReads, MIN_RESIDENT_CELLS, cellBytes, minimum));
		this.budget = budgetBytes;
		this.io = Executors.newFixedThreadPool(this.ioThreads, Shutdown.daemonThreads("OPM-raw-io"));
		this.queue = queue;
	}

	/** The smallest budget a store with these cells and threads accepts. */
	static long minimumBudget(long cellBytes, int ioThreads) {
		int threads = Math.max(1, ioThreads);
		return Math.max(1, cellBytes) * (threads + 2L * threads + MIN_RESIDENT_CELLS);
	}

	/** Rows per cell: the TIFF's own strip. */
	static int cellRows(RawTiffLayout layout) {
		return layout.rowsPerStrip;
	}

	/** Bytes of one full cell of this layout; the last strip of a plane can be shorter. */
	static long cellBytes(RawTiffLayout layout) {
		return 2L * layout.width * cellRows(layout);
	}

	/** Be told when a file is found to have changed after it was checked; called on the reading thread. */
	void setStaleListener(BiConsumer<RawAcquisition.FileKey, String> listener) {
		this.staleListener = listener;
	}

	/** Test seam: runs on the IO thread after a cell's bytes are read and before they are checked and published. */
	void setReadHook(Consumer<String> hook) {
		this.readHook = hook;
	}

	/** Test seam: the pixel array of a resident cell, or null; lets a test watch an evicted array be released. */
	short[] residentPixels(RawAcquisition.FileKey key, long index) {
		Volumes v = volumes.get(key);
		Slot s = v == null ? null : v.slots.get(index);
		return s == null ? null : s.data;
	}

	@Override
	public RandomAccessibleInterval<UnsignedShortType> blocking(RawAcquisition.FileKey key, RawTiffLayout layout) {
		return volumes(key, layout).blocking;
	}

	@Override
	public RandomAccessibleInterval<VolatileUnsignedShortType> volatileView(RawAcquisition.FileKey key, RawTiffLayout layout) {
		if (queue == null) throw new IllegalStateException("This store has no fetch queue for volatile views");
		return volumes(key, layout).volatileImg;
	}

	@Override
	public boolean isStale(RawAcquisition.FileKey key) {
		return stale.contains(key);
	}

	/**
	 * Whether the file is still the version that was checked; if not, it is made stale now. A
	 * metadata read, for checks at the moments that matter - a time point being shown - rather
	 * than only when a cell happens to be read.
	 */
	@Override
	public boolean checkGeneration(RawAcquisition.FileKey key) {
		if (stale.contains(key)) return false;
		if (key.current()) return true;
		markStale(key, key.change());
		return false;
	}

	/**
	 * The images of one file version. They are handed out even for a file found stale, or by a
	 * closed store: their geometry is what BigDataViewer asks for while a time point is being
	 * withdrawn (its box overlay calls {@code getSource} after {@code isPresent}). Their pixels are
	 * not: a blocking read is refused with an exception and a volatile one stays invalid.
	 */
	private Volumes volumes(final RawAcquisition.FileKey key, final RawTiffLayout layout) {
		if (layout == null) throw new IllegalArgumentException(key + " has no validated layout");
		if (!layout.supported()) throw new IllegalArgumentException(key + ": " + layout.unsupportedReason);
		long cell = cellBytes(layout);
		if ((long) ioThreads * cell + cell > budget)
			throw new IllegalArgumentException(String.format(java.util.Locale.US,
					"%s: a strip of %d bytes cannot be read within a cache budget of %d bytes", key, cell, budget));
		Volumes v = volumes.get(key);
		if (v == null) {
			Volumes made = new Volumes(key, layout);
			v = volumes.putIfAbsent(key, made);
			if (v == null) v = made;
		}
		return v;
	}

	private void refuseIfClosingOrStale(RawAcquisition.FileKey key) {
		if (closing) throw new IllegalStateException("The raw volume store is closed");
		if (stale.contains(key))
			throw new StaleFileException(key + " changed after it was checked; it is no longer read");
	}

	@Override
	public CacheStats stats() {
		synchronized (ledger) {
			collectReleased();
			return new CacheStats(budget, resident, clock.size(), lent, inFlight, reads, scratch, peakResident, peakLent,
					peakInFlight, peakScratch, peakTotal, loads.get(), bytesRead.get(), rereads.get(), evictions.get(),
					failures.get(), staleReads.get(), refusals.get(), channels.size(), volumes.size(), stale.size());
		}
	}

	@Override
	public synchronized CompletableFuture<CloseResult> closeAsync() {
		if (closing) return closed;
		closing = true;
		final long start = System.nanoTime();
		synchronized (ledger) {
			ledger.notifyAll();		// waiting admissions see the close and give up
		}
		io.shutdown();		// no interrupt: a read in progress shares its file's channel
		Thread finisher = Shutdown.daemon(new Runnable() {
			@Override public void run() {
				boolean drained;
				try {
					drained = io.awaitTermination(CLOSE_DEADLINE_MS, TimeUnit.MILLISECONDS);
				} catch (InterruptedException e) {
					drained = false;
				}
				long abandoned;
				synchronized (ledger) {
					abandoned = reads;
					// an access still holding a lent cell keeps its array: it stays charged until released
					for (Slot s : new ArrayList<Slot>(clock)) {
						clock.remove(s);
						drop(s);
					}
					// the IO threads and their buffers are gone once the pool has drained
					if (drained) scratch = 0;
				}
				channels.terminate();
				volumes.clear();
				closed.complete(new CloseResult(drained, (System.nanoTime() - start) / 1000000, abandoned, stats()));
			}
		}, "OPM-raw-store-close");
		finisher.start();
		return closed;
	}

	@Override
	public void close() {
		closeAsync();
	}

	/* ---------------------------------------------------------------- loading */

	/**
	 * The pixels of one cell, read now if they are not resident; blocks the caller. A read that
	 * fails is reported to everyone waiting for it, never retried here.
	 */
	private short[] resolve(Volumes v, long index) {
		for (;;) {
			refuseIfClosingOrStale(v.key);
			Slot s = v.slots.get(index);
			if (s == null) {
				Slot made = new Slot(v, index);
				s = v.slots.putIfAbsent(index, made);
				if (s == null) {
					s = made;
					load(made);
				}
			}
			short[] data = s.data;
			if (data == null) {
				await(s);		// this requester's read or someone else's; rethrows its failure
				data = s.data;
				if (data == null) continue;		// read, then evicted again before this look: read it anew
			}
			s.referenced = true;
			return data;
		}
	}

	/** Wait for a slot's read; an interrupt ends the wait, never the read. */
	private static void await(Slot s) {
		try {
			s.loaded.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RawReadException("Interrupted while waiting for " + s, e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException) throw (RuntimeException) cause;
			throw new RawReadException("Cannot read " + s + ": " + cause, cause);
		}
	}

	/** Admit and submit the read of a new slot; the IO thread publishes or fails it. */
	private void load(final Slot s) {
		try {
			admitRead(s.bytes);
		} catch (RuntimeException refused) {
			s.volumes.slots.remove(s.index, s);
			s.loaded.completeExceptionally(refused);
			throw refused;
		}
		try {
			io.execute(new Runnable() {
				@Override public void run() {
					read(s);
				}
			});
		} catch (RejectedExecutionException closedMeanwhile) {
			fail(s, new IllegalStateException("The raw volume store is closed"));
		}
	}

	/** On an IO thread: read, check the file is still the checked version, publish. */
	private void read(Slot s) {
		RawAcquisition.FileKey key = s.volumes.key;
		try {
			refuseIfClosingOrStale(key);
			short[] data = new short[(int) (s.bytes / 2)];		// reserved by admitRead
			readCell(s, data);
			Consumer<String> hook = readHook;
			if (hook != null) hook.accept(s.toString());
			if (!key.current()) {
				markStale(key, key.change());
				throw new StaleFileException(key + " changed while it was read; the read is discarded");
			}
			publish(s, data);
		} catch (Throwable t) {
			if (t instanceof StaleFileException) markStale(key, key.change());		// found when its handle was opened
			fail(s, t);
		}
	}

	private void fail(Slot s, Throwable t) {
		synchronized (ledger) {
			inFlight -= s.bytes;
			reads--;
			ledger.notifyAll();
		}
		s.volumes.slots.remove(s.index, s);
		if (t instanceof StaleFileException) staleReads.incrementAndGet();
		else if (!closing) failures.incrementAndGet();
		s.loaded.completeExceptionally(t);
	}

	/** Reserve a read: its buffer's bytes in flight, making room by eviction, waiting a bounded time for reads to finish. */
	private void admitRead(long bytes) {
		synchronized (ledger) {
			long deadline = System.currentTimeMillis() + ADMISSION_WAIT_MS;
			for (;;) {
				if (closing) throw new IllegalStateException("The raw volume store is closed");
				collectReleased();
				boolean fits = total() + bytes <= budget;
				if (fits && reads < maxReads) break;
				if (!fits && evictOne()) continue;
				long left = deadline - System.currentTimeMillis();
				if (left <= 0 || (reads == 0 && lent == 0)) {
					refusals.incrementAndGet();
					throw new BudgetException(String.format(java.util.Locale.US,
							"The cache budget (%.1f MB) cannot admit another read: %.1f MB resident, %.1f MB lent to the view, "
									+ "%.1f MB being read, %.1f MB read buffers",
							budget / 1e6, resident / 1e6, lent / 1e6, inFlight / 1e6, scratch / 1e6));
				}
				try {
					// a finished read notifies; a lent cell is released by the collector, which does not
					ledger.wait(lent > 0 ? Math.min(left, 50) : left);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new RawReadException("Interrupted while waiting for room in the cache", e);
				}
			}
			inFlight += bytes;
			reads++;
			peakInFlight = Math.max(peakInFlight, inFlight);
			peakTotal = Math.max(peakTotal, total());
		}
	}

	/** A read's bytes become resident; a read of a file that went stale meanwhile is dropped instead. */
	private void publish(Slot s, short[] data) {
		boolean kept;
		synchronized (ledger) {
			inFlight -= s.bytes;
			reads--;
			kept = !closing && !stale.contains(s.volumes.key);
			if (kept) {
				s.data = data;
				s.referenced = true;
				clock.add(s);
				resident += s.bytes;
				peakResident = Math.max(peakResident, resident);
				peakTotal = Math.max(peakTotal, total());
				if (recentlyEvicted.remove(s.toString()) != null) rereads.incrementAndGet();
			}
			ledger.notifyAll();
		}
		if (!kept) {
			s.volumes.slots.remove(s.index, s);
			RuntimeException why;
			if (closing) why = new IllegalStateException("The raw volume store is closed");
			else {
				why = new StaleFileException(s.volumes.key + " changed while it was read; the read is discarded");
				staleReads.incrementAndGet();
			}
			s.loaded.completeExceptionally(why);
			return;
		}
		loads.incrementAndGet();
		bytesRead.addAndGet(s.bytes);
		s.loaded.complete(Boolean.TRUE);
	}

	/**
	 * Evict one cell, least recently used first with a second chance; caller holds the ledger. A
	 * cell lent to the view in the current frame is passed over: the frame being drawn is reading it.
	 */
	private boolean evictOne() {
		boolean lends = queue != null;		// without a fetch queue nothing is lent
		long frame = lends ? queue.getCurrentFrame() : 0;
		for (int pass = 0, n = clock.size(); pass < 2 * n + 1 && !clock.isEmpty(); pass++) {
			Iterator<Slot> it = clock.iterator();
			Slot s = it.next();
			it.remove();
			if (s.referenced || (lends && s.lentFrame == frame)) {
				s.referenced = false;
				clock.add(s);		// to the back of the line
				continue;
			}
			drop(s);
			return true;
		}
		return false;
	}

	/** Release a resident cell; caller holds the ledger and has taken it out of the clock. */
	private void drop(Slot s) {
		s.evict();
		s.volumes.slots.remove(s.index, s);
		resident -= s.bytes;
		evictions.incrementAndGet();
		recentlyEvicted.put(s.toString(), Boolean.TRUE);
	}

	/**
	 * Withdraw a resident cell of a file found changed; caller holds the ledger and has taken it
	 * out of the clock. An access lent to the view may still hold it, and must go on reading what
	 * it was given: the array stays, charged as lent, until no such access is left.
	 */
	private void withdraw(Slot s) {
		s.withdrawn = true;
		s.lentCell = null;
		s.volumes.slots.remove(s.index, s);
		resident -= s.bytes;
		if (s.lentLive > 0) {
			s.lentOut = true;
			lent += s.bytes;
			peakLent = Math.max(peakLent, lent);
		} else s.evict();
	}

	/** Everything the ledger holds now; caller holds the ledger. */
	private long total() {
		return resident + lent + inFlight + scratch;
	}

	/** Release the lent cells no image access holds any more; caller holds the ledger. */
	private void collectReleased() {
		for (java.lang.ref.Reference<? extends LentAccess> r; (r = released.poll()) != null;) {
			LentRef ref = (LentRef) r;
			lentRefs.remove(ref);
			Slot s = ref.slot;
			if (--s.lentLive == 0 && s.lentOut) {
				s.lentOut = false;
				lent -= s.bytes;
				s.evict();
			}
		}
	}

	/**
	 * The volatile cell of a resident slot for the current frame; or null if the slot was evicted
	 * meanwhile, or its file found changed. One access per slot and frame: a cell lent in the
	 * current frame is not evicted, and the access is watched in case its file goes stale.
	 */
	private Cell<VolatileShortAccess> lend(Slot s) {
		// a file found changed lends nothing more; accesses lent before keep what they were given
		if (stale.contains(s.volumes.key) || closing) return null;
		long frame = queue.getCurrentFrame();
		Cell<VolatileShortAccess> cell = s.lentCell;
		if (cell != null && s.lentFrame == frame) return cell;
		synchronized (ledger) {
			collectReleased();
			short[] data = s.data;
			if (data == null || stale.contains(s.volumes.key)) return null;
			if (s.lentCell != null && s.lentFrame == frame) return s.lentCell;
			LentAccess access = new LentAccess(s);
			lentRefs.add(new LentRef(access, s, released));
			s.lentLive++;
			cell = new Cell<VolatileShortAccess>(s.dims, s.min, access);
			s.lentFrame = frame;
			s.lentCell = cell;
			return cell;
		}
	}

	/** The file is not the version that was checked: drop its cells, read nothing more, tell the session. */
	private void markStale(RawAcquisition.FileKey key, String why) {
		if (!stale.add(key)) return;
		synchronized (ledger) {
			List<Slot> victims = new ArrayList<Slot>();
			for (Slot s : clock) if (s.volumes.key.equals(key)) victims.add(s);
			for (Slot s : victims) {
				clock.remove(s);
				withdraw(s);
			}
		}
		channels.discard(key);
		BiConsumer<RawAcquisition.FileKey, String> listener = staleListener;
		if (listener != null) listener.accept(key, why);
	}

	private void readCell(Slot s, short[] data) throws IOException {
		RawTiffLayout layout = s.volumes.layout;
		RawAcquisition.FileKey key = s.volumes.key;
		int y0 = (int) s.min[1], k = (int) s.min[2], nx = s.dims[0], ny = s.dims[1];
		int bytes = 2 * nx * ny;
		for (int attempt = 0;; attempt++) {
			try (Channels.Lease lease = channels.acquire(key)) {
				ByteBuffer buffer = read(lease.channel, bytes, layout.offset(0, y0, k), layout.order);
				buffer.asShortBuffer().get(data, 0, nx * ny);
				return;
			} catch (ClosedChannelException e) {
				// closed under this read: reopen once, only for an open store and an unchanged file
				if (closing || stale.contains(key) || attempt > 0) throw e;
				channels.discard(key);
			}
		}
	}

	private ByteBuffer read(FileChannel channel, int bytes, long position, ByteOrder order) throws IOException {
		ByteBuffer buffer = scratch(bytes);
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

	/** This IO thread's read buffer, grown to {@code bytes} if need be; its size is accounted as scratch. */
	private ByteBuffer scratch(int bytes) {
		ByteBuffer buffer = scratchBuffer.get();
		if (buffer != null && buffer.capacity() >= bytes) return buffer;
		long grow = bytes - (buffer == null ? 0 : buffer.capacity());
		synchronized (ledger) {
			// never wait here: this thread's own read is part of what is in flight
			collectReleased();
			while (total() + grow > budget)
				if (!evictOne()) {
					refusals.incrementAndGet();
					throw new BudgetException("The cache budget cannot hold another read buffer of " + bytes + " bytes");
				}
			scratch += grow;
			peakScratch = Math.max(peakScratch, scratch);
			peakTotal = Math.max(peakTotal, total());
		}
		buffer = ByteBuffer.allocateDirect(bytes);
		scratchBuffer.set(buffer);
		return buffer;
	}

	/* ---------------------------------------------------------------- images */

	/** One file version's images over the store's cells. */
	private final class Volumes {
		final RawAcquisition.FileKey key;
		final RawTiffLayout layout;
		final CellGrid grid;
		final ConcurrentHashMap<Long, Slot> slots = new ConcurrentHashMap<Long, Slot>();
		/** Cells asked for by the volatile view, by the fetch-queue frame they were enqueued in. */
		final ConcurrentHashMap<Long, Long> enqueued = new ConcurrentHashMap<Long, Long>();
		final LazyCellImg<UnsignedShortType, ShortAccess> blocking;
		final VolatileCachedCellImg<VolatileUnsignedShortType, VolatileShortAccess> volatileImg;

		Volumes(RawAcquisition.FileKey key, RawTiffLayout layout) {
			this.key = key;
			this.layout = layout;
			this.grid = new CellGrid(layout.dimensions(), new int[] { layout.width, cellRows(layout), 1 });
			this.blocking = new LazyCellImg<UnsignedShortType, ShortAccess>(grid, new UnsignedShortType(),
					index -> blockingCell(this, index));
			this.volatileImg = queue == null ? null
					: new VolatileCachedCellImg<VolatileUnsignedShortType, VolatileShortAccess>(grid,
							new VolatileUnsignedShortType(), VOLATILE_HINTS, (index, hints) -> volatileCell(this, index, hints));
		}
	}

	private Cell<ShortAccess> blockingCell(Volumes v, long index) {
		resolve(v, index);
		Slot s = v.slots.get(index);
		// evicted again between the read and here: a cell over the slot still reads through the store
		return s != null ? s.blockingCell : new Slot(v, index).blockingCell;
	}

	/**
	 * The volatile view's cell: the resident one, or an invalid placeholder while the read is
	 * queued on BigDataViewer's fetch queue. A placeholder holds no pixels.
	 */
	private Cell<VolatileShortAccess> volatileCell(final Volumes v, final long index, CacheHints hints) {
		Slot s = v.slots.get(index);
		if (s != null && s.data != null) {
			Cell<VolatileShortAccess> lentCell = lend(s);
			if (lentCell != null) {
				s.referenced = true;
				return lentCell;
			}
		}
		LoadingStrategy strategy = hints == null ? LoadingStrategy.VOLATILE : hints.getLoadingStrategy();
		if (!closing && !stale.contains(v.key) && strategy != LoadingStrategy.DONTLOAD) {
			if (strategy == LoadingStrategy.BLOCKING) {
				resolve(v, index);
				Slot loaded = v.slots.get(index);
				Cell<VolatileShortAccess> lentCell = loaded == null ? null : lend(loaded);
				if (lentCell != null) return lentCell;
			} else {
				long frame = queue.getCurrentFrame();
				Long asked = v.enqueued.get(index);
				// the queue drops what a frame did not reach, so ask again in each new frame
				if (asked == null || asked < frame) {
					v.enqueued.put(index, frame);
					queue.put(() -> {
						try {
							if (!closing && !stale.contains(v.key)) resolve(v, index);
						} catch (RuntimeException counted) {
							// counted in the store's statistics; the view asks again in a later frame
						} finally {
							v.enqueued.remove(index);
						}
						return null;
					}, hints == null ? 0 : hints.getQueuePriority(), hints != null && hints.isEnqueuToFront());
				}
			}
		}
		long[] min = new long[3];
		int[] dims = new int[3];
		v.grid.getCellDimensions(index, min, dims);
		return new Cell<VolatileShortAccess>(dims, min, InvalidAccess.INSTANCE);
	}

	/**
	 * Where one cell's pixels live while they are resident. Cells handed to images point here;
	 * eviction empties it, so no image keeps an evicted cell's pixels alive.
	 */
	private final class Slot {
		final Volumes volumes;
		final long index;
		final long[] min = new long[3];
		final int[] dims = new int[3];
		final long bytes;
		/** Done when the read ends; it carries no pixels, so nothing outside the slot keeps them. */
		final CompletableFuture<Boolean> loaded = new CompletableFuture<Boolean>();
		final Cell<ShortAccess> blockingCell;
		volatile short[] data;
		volatile boolean referenced;
		/** The volatile cell lent for {@link #lentFrame}; dropped at eviction so that only image accesses hold it. */
		volatile Cell<VolatileShortAccess> lentCell;
		volatile long lentFrame = Long.MIN_VALUE;
		/** Lent accesses still alive, and whether this slot's bytes are charged as lent; both under the ledger. */
		int lentLive;
		boolean lentOut;
		/** Its file was found changed: blocking reads are refused even while lent accesses keep the array. */
		volatile boolean withdrawn;

		Slot(Volumes volumes, long index) {
			this.volumes = volumes;
			this.index = index;
			volumes.grid.getCellDimensions(index, min, dims);
			this.bytes = 2L * dims[0] * dims[1] * dims[2];
			this.blockingCell = new Cell<ShortAccess>(dims, min, new BlockingAccess(this));
		}

		void evict() {
			data = null;
			lentCell = null;
		}

		@Override public String toString() {
			return volumes.key + " plane " + min[2] + " rows " + min[1] + "-" + (min[1] + dims[1] - 1);
		}
	}

	/** Blocking pixels: read again through the store when the slot was evicted under this access. */
	private final class BlockingAccess implements ShortAccess {
		private final Slot slot;

		BlockingAccess(Slot slot) {
			this.slot = slot;
		}

		@Override public short getValue(int index) {
			if (slot.withdrawn)
				throw new StaleFileException(slot.volumes.key + " changed after it was checked; it is no longer read");
			short[] d = slot.data;
			if (d == null) d = resolve(slot.volumes, slot.index);
			return d[index];
		}

		@Override public void setValue(int index, short value) {
			throw new UnsupportedOperationException("Raw acquisition pixels are read-only");
		}
	}

	/**
	 * Volatile pixels lent to the view, valid for as long as the access lives. The type that reads
	 * them took that validity once, so the value must be right on every read: the slot's array, or
	 * - should the cell have been evicted under an access kept beyond its frame - the cell read
	 * again. A cell of a file found changed keeps its array for the accesses lent it.
	 */
	private final class LentAccess implements VolatileShortAccess {
		private final Slot slot;

		LentAccess(Slot slot) {
			this.slot = slot;
		}

		@Override public short getValue(int index) {
			short[] d = slot.data;
			if (d == null) d = resolve(slot.volumes, slot.index);
			return d[index];
		}

		@Override public void setValue(int index, short value) {
			throw new UnsupportedOperationException("Raw acquisition pixels are read-only");
		}

		@Override public boolean isValid() {
			return true;
		}
	}

	/** A lent access, watched until no image access holds it. */
	private static final class LentRef extends WeakReference<LentAccess> {
		final Slot slot;

		LentRef(LentAccess access, Slot slot, ReferenceQueue<LentAccess> queue) {
			super(access, queue);
			this.slot = slot;
		}
	}

	/** The pixels of a cell not loaded yet: none. */
	private static final class InvalidAccess implements VolatileShortAccess {
		static final InvalidAccess INSTANCE = new InvalidAccess();

		@Override public short getValue(int index) {
			return 0;
		}

		@Override public void setValue(int index, short value) {
			throw new UnsupportedOperationException("Raw acquisition pixels are read-only");
		}

		@Override public boolean isValid() {
			return false;
		}
	}

	/** A file read after it changed; its pixels are withheld. */
	static final class StaleFileException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		StaleFileException(String message) {
			super(message);
		}
	}

	/** A read the budget cannot admit. */
	static final class BudgetException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		BudgetException(String message) {
			super(message);
		}
	}

	/** A read that failed or whose wait was interrupted. */
	static final class RawReadException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		RawReadException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/* ---------------------------------------------------------------- file handles */

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
				if (!key.current()) throw new StaleFileException(key + " changed after it was checked; it is no longer read");
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

		/** Forget a file's handle - closed under its readers, or the file changed; closed once idle. */
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
