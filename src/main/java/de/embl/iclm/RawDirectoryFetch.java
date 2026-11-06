package de.embl.iclm;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * Bring raw TIFFs' directories into the operating system's file cache with parallel reads, before they are walked.
 *
 * <p>Checking a raw file ({@link RawAcquisition.Entry#validate}) walks its IFD chain twice - the completeness check, then
 * the layout - and each directory names the next, so a walk is one read after another. Over a network share every read is
 * a round trip: the 435 directories of a 1.4 GB volume took 7.9 s to walk on the institute's server, and 0.01 s once they
 * were cached (2026-11-06). The camera writes each directory right after its plane, at one stride, so where all of them
 * are follows from the first two. Those blocks are fetched eight reads at a time, and the walks that follow read the
 * cache; more readers were slower there (16: 2.3 s, 32: 3.7 s, 64: 4.3 s for one file's directories).
 *
 * <p>The blocks fetched are the ones {@link TiffCompletionCheck} reads - {@link TiffCompletionCheck#BLOCK_BYTES} on a
 * multiple of it - not just the directory: a block the cache holds only part of is fetched from the server again, one
 * round trip per directory, and fetching 4 KB at each directory therefore saved only a third of the walk.
 *
 * <p>The reads go through NIO channels, one per reader, as the store's do: an open NIO handle leaves the acquisition
 * software free to rename or delete the file, and a channel of its own spares each reader the position lock that makes a
 * shared channel's reads take turns on Windows.
 *
 * <p>Nothing here decides anything: the walks still read and check every directory. A file whose directories do not
 * follow one stride is fetched only where predicted, and the walk reads the rest itself, as before; any failure here is
 * ignored for the same reason.
 */
final class RawDirectoryFetch {

	/** Reads in flight at once; see the class comment for why not more. */
	static final int READERS = 8;
	/** The completeness check's block, read whole wherever a directory lies in it. */
	static final int BLOCK = TiffCompletionCheck.BLOCK_BYTES;
	/** How far past its start a directory and its tables reach, at most, for fetching: a camera's are 1072 bytes. */
	static final int SPAN = 4096;
	/** At most this many directories are predicted for one file. */
	static final int MAX_DIRECTORIES = 100000;
	/** How long one fetch may take before the walk goes ahead without it. */
	static final long DEADLINE_MS = 60000;
	/** How often a fetch asks whether it is still wanted. */
	static final long CANCEL_POLL_MS = 20;
	/** A file version fetched this recently is in the cache still: a second fetch would only open it again. */
	static final long FRESH_MS = 60000;

	private static final long[] NONE = new long[0];
	private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(READERS, READERS, 30, TimeUnit.SECONDS,
			new LinkedBlockingQueue<Runnable>(), Shutdown.daemonThreads("OPM-raw-directory-fetch"));
	static {
		POOL.allowCoreThreadTimeOut(true);
	}
	/** File versions fetched, by path, length and modification time, and when. */
	private static final Map<String, Long> fetched = new ConcurrentHashMap<String, Long>();

	private RawDirectoryFetch() {
	}

	/**
	 * Fetch the directories of these files together and return once they are in the cache, or the deadline has passed.
	 * Never throws; an interrupt stops the reads and is kept.
	 */
	static void fetch(Collection<File> files) {
		fetch(files, () -> false);
	}

	/**
	 * As {@link #fetch(Collection)}, giving up as soon as {@code cancelled} says so: a check of a time point nobody has
	 * asked for yet gives way to one that a jump of the time slider asks for. Only a fetch that finished counts as fetched.
	 *
	 * @return	: false when it was cancelled or interrupted
	 */
	static boolean fetch(Collection<File> files, BooleanSupplier cancelled) {
		List<File> due = new ArrayList<File>();
		List<long[]> where = new ArrayList<long[]>();
		List<String> versions = new ArrayList<String>();
		long now = System.currentTimeMillis();
		for (File file : files) {
			String version = file.getAbsolutePath() + '|' + file.length() + '|' + file.lastModified();
			Long when = fetched.get(version);
			if (when != null && now - when < FRESH_MS) continue;
			long[] offsets = predicted(file);
			if (offsets.length == 0) continue;
			due.add(file);
			where.add(offsets);
			versions.add(version);
		}
		if (due.isEmpty()) return true;
		if (cancelled.getAsBoolean()) return false;
		List<Future<?>> reads = new ArrayList<Future<?>>();
		for (int f = 0; f < due.size(); f++) {
			final File file = due.get(f);
			final long[] offsets = where.get(f);
			for (int reader = 0; reader < READERS && reader < offsets.length; reader++) {
				final int first = reader;
				reads.add(POOL.submit(() -> read(file, offsets, first)));
			}
		}
		long until = now + DEADLINE_MS;
		for (Future<?> read : reads) {
			while (!read.isDone()) {
				if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted() || System.currentTimeMillis() > until) {
					for (Future<?> each : reads) each.cancel(true);
					return false;
				}
				try {
					read.get(CANCEL_POLL_MS, TimeUnit.MILLISECONDS);
				} catch (InterruptedException interrupted) {
					for (Future<?> each : reads) each.cancel(true);
					Thread.currentThread().interrupt();
					return false;
				} catch (ExecutionException | TimeoutException notYet) {
					// done with a failure, which the walk reports, or not done yet
				}
			}
		}
		for (String version : versions) fetched.put(version, now);
		if (fetched.size() > 1024)
			for (Iterator<Long> it = fetched.values().iterator(); it.hasNext();) if (now - it.next() >= FRESH_MS) it.remove();
		return true;
	}

	/**
	 * Where a file's directories are, if they follow the stride of its first two: an offset per directory, up to the end
	 * of the file. None for a file with one directory, one whose second directory does not follow its first, or one that
	 * is not a TIFF.
	 */
	static long[] predicted(File file) {
		try (FileChannel in = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
			long length = in.size();
			if (length < 16) return NONE;
			ByteBuffer head = at(in, 0, 16, ByteOrder.LITTLE_ENDIAN);
			ByteOrder order;
			if (head.get(0) == 'I' && head.get(1) == 'I') order = ByteOrder.LITTLE_ENDIAN;
			else if (head.get(0) == 'M' && head.get(1) == 'M') order = ByteOrder.BIG_ENDIAN;
			else return NONE;
			head.order(order);
			int magic = head.getShort(2) & 0xffff;
			boolean big = magic == 43;
			if (!big && magic != 42) return NONE;
			long first = big ? head.getLong(8) : head.getInt(4) & 0xffffffffL;
			if (first < 8 || first >= length) return NONE;
			long second = next(in, order, big, first, length);
			if (second <= first || second >= length) return NONE;
			long stride = second - first;
			int count = (int) Math.min(MAX_DIRECTORIES, (length - first + stride - 1) / stride);
			long[] offsets = new long[count];
			for (int k = 0; k < count; k++) offsets[k] = first + k * stride;
			return offsets;
		} catch (IOException | RuntimeException unreadable) {
			return NONE;
		}
	}

	/** The offset of the directory after the one at {@code ifd}, or 0. */
	private static long next(FileChannel in, ByteOrder order, boolean big, long ifd, long length) throws IOException {
		int countBytes = big ? 8 : 2, entryBytes = big ? 20 : 12, offsetBytes = big ? 8 : 4;
		if (ifd + countBytes > length) return 0;
		ByteBuffer count = at(in, ifd, countBytes, order);
		long entries = big ? count.getLong(0) : count.getShort(0) & 0xffff;
		if (entries < 0 || entries > MAX_DIRECTORIES) return 0;
		long position = ifd + countBytes + entries * entryBytes;
		if (position + offsetBytes > length) return 0;
		ByteBuffer offset = at(in, position, offsetBytes, order);
		return big ? offset.getLong(0) : offset.getInt(0) & 0xffffffffL;
	}

	/** {@code bytes} bytes of {@code in} from {@code position}, whole. */
	private static ByteBuffer at(FileChannel in, long position, int bytes, ByteOrder order) throws IOException {
		ByteBuffer buffer = ByteBuffer.allocate(bytes);
		while (buffer.hasRemaining()) {
			int n = in.read(buffer, position + buffer.position());
			if (n < 0) throw new IOException("the file ends before " + (position + bytes));
		}
		return (ByteBuffer) buffer.order(order).flip();
	}

	/**
	 * One reader's share of a file: every {@link #READERS}th predicted directory from {@code first}, through its own handle,
	 * each as the whole block or blocks the completeness check will read for it.
	 */
	private static void read(File file, long[] offsets, int first) {
		ByteBuffer block = ByteBuffer.allocate(BLOCK);
		try (FileChannel in = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
			long length = in.size();
			for (int k = first; k < offsets.length && offsets[k] < length; k += READERS) {
				long end = Math.min(length, offsets[k] + SPAN);
				for (long at = offsets[k] - offsets[k] % BLOCK; at < end; at += BLOCK) {
					if (Thread.currentThread().isInterrupted()) return;
					block.clear();
					block.limit((int) Math.min(BLOCK, length - at));
					while (block.hasRemaining() && in.read(block, at + block.position()) >= 0) {
						// the whole block, as the check will read it
					}
				}
			}
		} catch (IOException unreadable) {
			// the walk reads it, and says why it cannot
		}
	}
}
