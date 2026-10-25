package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.cache.SharedQueue;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.volatiles.VolatileUnsignedShortType;

/**
 * The Online Deskew View's raw store (joint contracts C2.6, C3.1 - C3.8, C7.2): exact pixels
 * through strip-shaped cells; an accounted byte budget that admission enforces synchronously, for
 * strips of any height, with concurrent readers, and with accesses that keep cells after their
 * eviction; reservations released by failed and interrupted reads and by the close; and a file
 * that changes - before, between or during reads, or by replacement or deletion - withheld, never
 * read as a mixture of two versions.
 *
 * <p>Nothing here calls {@code System.gc()} to make a bound hold. One test calls it only to watch
 * an evicted array become unreachable while an access still holds its cell.
 */
public class TiffRawVolumeStoreTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final int W = 26, H = 17;

	/** A validated entry for one fixture file, alone in its own folder. */
	private RawAcquisition.Entry entry(String name, int width, int height, int depth, int rows, RawTiffFixture.Pixels pixels)
			throws IOException {
		File dir = folder.newFolder();
		RawTiffFixture.write(new File(dir, name), width, height, depth, rows, depth, pixels);
		RawAcquisition acquisition = RawAcquisition.scan(dir);
		RawAcquisition.Entry e = acquisition.entry(acquisition.times(1, 1).get(0), 1);
		assertEquals(e.detail(), RawAcquisition.Readiness.COMPLETE, e.validate());
		return e;
	}

	private RawAcquisition.Entry entry(String name, int depth) throws IOException {
		return entry(name, W, H, depth, 8, RawTiffFixture.RAMP);
	}

	/** A store whose budget holds its fixed reservations plus {@code spare} more cells. */
	private static TiffRawVolumeStore store(RawAcquisition.Entry e, int ioThreads, long spareCells, SharedQueue queue) {
		long cell = TiffRawVolumeStore.cellBytes(e.layout());
		return new TiffRawVolumeStore(TiffRawVolumeStore.minimumBudget(cell, ioThreads) + spareCells * cell, cell,
				ioThreads, queue);
	}

	private static int read(RandomAccessibleInterval<UnsignedShortType> img, long x, long y, long z) {
		RandomAccess<UnsignedShortType> a = img.randomAccess();
		a.setPosition(new long[] { x, y, z });
		return a.get().get();
	}

	private static void assertWithinBudget(RawVolumeStore.CacheStats s) {
		assertTrue("peak " + s.peakTotalBytes + " within budget " + s.budgetBytes, s.peakTotalBytes <= s.budgetBytes);
		assertTrue("now " + s.totalBytes() + " within budget " + s.budgetBytes, s.totalBytes() <= s.budgetBytes);
	}

	@Test
	public void everyVoxelIsTheRawValueAndBothHalvesShareTheCells() throws Exception {
		RawAcquisition.Entry e = entry("s_Time000001_Channel0001_Frames_1_7.tiff", 7);
		TiffRawVolumeStore store = store(e, 3, 1000, null);
		try {
			RandomAccessibleInterval<UnsignedShortType> raw = store.blocking(e.key(), e.layout());
			RandomAccess<UnsignedShortType> a = raw.randomAccess();
			for (int k = 0; k < 7; k++)
				for (int y = 0; y < H; y++)
					for (int x = 0; x < W; x++) {
						a.setPosition(new long[] { x, y, k });
						assertEquals(x + "," + y + "," + k, RawTiffFixture.RAMP.at(x, y, k), a.get().get());
					}
			RawVolumeStore.CacheStats s = store.stats();
			assertEquals("7 planes of 3 strips, each read once", 21, s.loads);
			// the last strip of each plane is one row: counted as what it is, not as a full strip
			assertEquals(7 * 2L * W * (8 + 8 + 1), s.residentBytes);
			// the right half is a view of the same cells: reading it reads nothing new
			RawSourceSpec right = RawSourceSpec.half(null, 1, false, false, W, 0);
			RandomAccess<UnsignedShortType> r = RawSampling.crop(raw, right).randomAccess();
			r.setPosition(new long[] { 0, 5, 3 });
			assertEquals(RawTiffFixture.RAMP.at(W - W / 2, 5, 3), r.get().get());
			assertEquals(21, store.stats().loads);
		} finally {
			assertTrue(store.closeAsync().get(10, TimeUnit.SECONDS).drained);
		}
	}

	@Test
	public void theVolatileViewFillsInThroughTheQueue() throws Exception {
		RawAcquisition.Entry e = entry("q_Time000001_Channel0001_Frames_1_4.tiff", 4);
		SharedQueue queue = new SharedQueue(2, 1);
		TiffRawVolumeStore store = store(e, 3, 1000, queue);
		try {
			RandomAccessibleInterval<VolatileUnsignedShortType> view = store.volatileView(e.key(), e.layout());
			RandomAccess<VolatileUnsignedShortType> v = view.randomAccess();
			v.setPosition(new long[] { 11, 9, 2 });
			assertFalse("a placeholder first", v.get().isValid());
			long deadline = System.currentTimeMillis() + 5000;
			while (!v.get().isValid() && System.currentTimeMillis() < deadline) {
				Thread.sleep(10);
				// an access keeps the placeholder it first saw; BigDataViewer takes a new one per frame
				v = view.randomAccess();
				v.setPosition(new long[] { 11, 9, 2 });
			}
			assertTrue("the cell was loaded in the background", v.get().isValid());
			assertEquals(RawTiffFixture.RAMP.at(11, 9, 2), v.get().get().get());
		} finally {
			queue.shutdown();
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	public void aBudgetSmallerThanTheVolumeEvictsAndReloadsWithinItsBound() throws Exception {
		RawAcquisition.Entry e = entry("b_Time000001_Channel0001_Frames_1_40.tiff", 40);
		TiffRawVolumeStore store = store(e, 3, 2, null);		// 120 cells of data; room for 10
		try {
			RandomAccessibleInterval<UnsignedShortType> raw = store.blocking(e.key(), e.layout());
			for (int pass = 0; pass < 2; pass++)
				for (int k = 0; k < 40; k++)
					for (int y = 0; y < H; y += 8) assertEquals(RawTiffFixture.RAMP.at(5, y, k), read(raw, 5, y, k));
			RawVolumeStore.CacheStats s = store.stats();
			assertTrue("cells were evicted and read again: " + s, s.loads > 120 && s.rereads > 0 && s.evictions > 0);
			assertWithinBudget(s);
			assertEquals(0, s.refusals);
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	/**
	 * Agent-B's R2 probe, inverted: two files of the same camera size whose strips differ eight
	 * times in height, and an access kept on every cell. The store counts each cell at its own
	 * size, so the total never exceeds the budget, and every kept access still reads its value.
	 */
	@Test
	public void stripsOfDifferentHeightsAreCountedAtTheirOwnSize() throws Exception {
		RawAcquisition.Entry small = entry("r_Time000001_Channel0001_Frames_0_0.tiff", 64, 128, 1, 1, (x, y, k) -> 123);
		RawAcquisition.Entry large = entry("r_Time000002_Channel0001_Frames_0_0.tiff", 64, 128, 1, 8, (x, y, k) -> 456);
		long smallCell = TiffRawVolumeStore.cellBytes(small.layout()), largeCell = TiffRawVolumeStore.cellBytes(large.layout());
		assertEquals(8 * smallCell, largeCell);
		long budget = 16 * smallCell;		// B's figure: 2048 bytes
		TiffRawVolumeStore store = new TiffRawVolumeStore(budget, smallCell, 1, null);
		try {
			RandomAccessibleInterval<UnsignedShortType> img = store.blocking(large.key(), large.layout());
			List<RandomAccess<UnsignedShortType>> kept = new ArrayList<RandomAccess<UnsignedShortType>>();
			for (int y = 0; y < 128; y += 8) {
				RandomAccess<UnsignedShortType> a = img.randomAccess();
				a.setPosition(new long[] { 0, y, 0 });
				assertEquals(456, a.get().get());
				kept.add(a);
				assertWithinBudget(store.stats());
			}
			for (RandomAccess<UnsignedShortType> a : kept) assertEquals("a kept access still reads its cell", 456, a.get().get());
			RawVolumeStore.CacheStats s = store.stats();
			assertWithinBudget(s);
			assertEquals("resident bytes are the cells' own sizes", s.residentCells * largeCell, s.residentBytes);
			assertEquals("the read buffer grew to the larger strip and is counted", largeCell, s.scratchBytes);
			assertTrue("cells evicted under kept accesses were read again: " + s, s.loads > 16);
			assertEquals(0, s.refusals);
			// and the small file shares the same budget
			assertEquals(123, read(store.blocking(small.key(), small.layout()), 3, 100, 0));
			assertWithinBudget(store.stats());
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	/**
	 * Eviction takes the pixels out of the cell, so an access that still holds an evicted cell holds
	 * no pixels: the array becomes unreachable at once. The collector is called only to observe that.
	 */
	@Test
	public void anAccessKeptOnAnEvictedCellHoldsNoPixels() throws Exception {
		RawAcquisition.Entry e = entry("k_Time000001_Channel0001_Frames_1_30.tiff", 30);
		TiffRawVolumeStore store = store(e, 1, 1, null);
		try {
			RandomAccessibleInterval<UnsignedShortType> raw = store.blocking(e.key(), e.layout());
			RandomAccess<UnsignedShortType> kept = raw.randomAccess();
			kept.setPosition(new long[] { 4, 2, 0 });
			assertEquals(RawTiffFixture.RAMP.at(4, 2, 0), kept.get().get());
			WeakReference<short[]> pixels = new WeakReference<short[]>(store.residentPixels(e.key(), 0));
			assertTrue(pixels.get() != null);
			for (int k = 1; k < 30; k++) read(raw, 0, 0, k);		// evicts cell 0
			assertNull("cell 0 was evicted", store.residentPixels(e.key(), 0));
			for (int i = 0; i < 10 && pixels.get() != null; i++) {
				System.gc();
				Thread.sleep(20);
			}
			assertNull("the kept access did not keep the evicted pixels alive", pixels.get());
			assertEquals("and it still reads the right value", RawTiffFixture.RAMP.at(4, 2, 0), kept.get().get());
			assertWithinBudget(store.stats());
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	public void concurrentReadersUnderASmallBudgetStayWithinIt() throws Exception {
		final List<RandomAccessibleInterval<UnsignedShortType>> images = new ArrayList<RandomAccessibleInterval<UnsignedShortType>>();
		RawAcquisition.Entry first = entry("c1_Time000001_Channel0001_Frames_1_30.tiff", 30);
		final TiffRawVolumeStore store = store(first, 2, 4, null);
		final AtomicLong wrong = new AtomicLong(), errors = new AtomicLong();
		try {
			images.add(store.blocking(first.key(), first.layout()));
			for (int f = 2; f <= 3; f++) {
				RawAcquisition.Entry e = entry("c" + f + "_Time000001_Channel0001_Frames_1_30.tiff", 30);
				images.add(store.blocking(e.key(), e.layout()));
			}
			List<Thread> readers = new ArrayList<Thread>();
			for (int t = 0; t < 8; t++) {
				final long seed = t;
				Thread reader = new Thread(() -> {
					Random random = new Random(seed);
					for (int i = 0; i < 1500; i++) {
						int x = random.nextInt(W), y = random.nextInt(H), k = random.nextInt(30);
						try {
							if (read(images.get(random.nextInt(3)), x, y, k) != RawTiffFixture.RAMP.at(x, y, k)) wrong.incrementAndGet();
						} catch (RuntimeException e) {
							errors.incrementAndGet();
						}
					}
				});
				reader.start();
				readers.add(reader);
			}
			for (Thread t : readers) t.join(60000);
			RawVolumeStore.CacheStats s = store.stats();
			assertEquals("no value was wrong", 0, wrong.get());
			assertEquals("no read failed or was refused: " + s, 0, errors.get());
			assertWithinBudget(s);
			assertEquals("no read left reserved", 0, s.inFlightBytes);
			assertTrue("the budget was actually under pressure: " + s, s.evictions > 0);
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	public void aBudgetBelowItsFixedReservationsIsRefused() {
		try {
			new TiffRawVolumeStore(TiffRawVolumeStore.minimumBudget(416, 2) - 1, 416, 2, null);
			fail("a budget that cannot hold its reads and read buffers was accepted");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("cannot hold"));
		}
	}

	/* ------------------------------------------------------------ a file that changes (R1) */

	/** Overwrite the first plane's pixels in place, keeping the file's structure and size; then move its time. */
	private static void rewriteFirstPlane(RawAcquisition.Entry e, int value) throws IOException {
		RawTiffLayout layout = RawTiffLayout.read(e.file);
		ByteBuffer replacement = ByteBuffer.allocate(2 * layout.width * layout.height).order(ByteOrder.LITTLE_ENDIAN);
		while (replacement.hasRemaining()) replacement.putShort((short) value);
		replacement.flip();
		try (FileChannel out = FileChannel.open(e.file.toPath(), StandardOpenOption.WRITE)) {
			long at = layout.offset(0, 0, 0);
			while (replacement.hasRemaining()) at += out.write(replacement, at);
			out.force(true);
		}
		Files.setLastModifiedTime(e.file.toPath(), FileTime.fromMillis(e.key().modified + 2000));
	}

	@Test
	public void aFileChangedBeforeItsFirstReadIsRefused() throws Exception {
		RawAcquisition.Entry e = entry("c_Time000001_Channel0001_Frames_1_3.tiff", 3);
		TiffRawVolumeStore store = store(e, 3, 1000, null);
		final AtomicReference<String> told = new AtomicReference<String>();
		store.setStaleListener((key, why) -> told.set(why));
		try (FileChannel out = FileChannel.open(e.file.toPath(), StandardOpenOption.APPEND)) {
			out.write(ByteBuffer.wrap("the writer came back".getBytes("US-ASCII")));
		}
		try {
			read(store.blocking(e.key(), e.layout()), 0, 0, 0);
			fail("a changed file was read");
		} catch (TiffRawVolumeStore.StaleFileException expected) {
			assertTrue(store.isStale(e.key()));
			assertTrue("the session is told why: " + told.get(), String.valueOf(told.get()).contains("size changed"));
			assertEquals(1, store.stats().staleReads);
			assertEquals("a stale file is not a successful read", 0, store.stats().loads);
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	/**
	 * Agent-B's R1 probe, inverted: a cell is read, the file is rewritten in place through an
	 * already open handle, and another cell is read. Before: the old cell said 100 and the new one
	 * 900, under one file version. Now the new read notices the change, the old cell is dropped, and
	 * neither value is served again.
	 */
	@Test
	public void aFileChangedAfterAWarmReadIsWithheldNotMixed() throws Exception {
		RawAcquisition.Entry e = entry("w_Time000001_Channel0001_Frames_0_2.tiff", W, H, 3, 8, (x, y, k) -> 100);
		TiffRawVolumeStore store = store(e, 1, 1000, null);
		try {
			RandomAccessibleInterval<UnsignedShortType> img = store.blocking(e.key(), e.layout());
			RandomAccess<UnsignedShortType> old = img.randomAccess();
			old.setPosition(new long[] { 0, 0, 0 });
			assertEquals(100, old.get().get());
			assertEquals("the handle is open before the change", 1, store.stats().openChannels);
			rewriteFirstPlane(e, 900);
			assertFalse(e.key().current());
			try {
				read(img, 0, 8, 0);		// a cell not read yet
				fail("a cell of the changed file was read");
			} catch (TiffRawVolumeStore.StaleFileException expected) {
				// noticed after the read, before publishing it
			}
			try {
				old.get().get();
				fail("the cell read before the change was still served");
			} catch (TiffRawVolumeStore.StaleFileException expected) {
				// its pixels were dropped with the file's other cells
			}
			RawVolumeStore.CacheStats s = store.stats();
			assertEquals(0, s.residentBytes);
			assertEquals("one read, the first", 1, s.loads);
			assertTrue(s.staleReads >= 1);
			try {
				store.blocking(e.key(), e.layout());
				fail("a stale file was handed out again");
			} catch (TiffRawVolumeStore.StaleFileException expected) {
				// refused
			}
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	public void aFileChangedDuringAReadIsDiscarded() throws Exception {
		final RawAcquisition.Entry e = entry("d_Time000001_Channel0001_Frames_0_2.tiff", W, H, 3, 8, (x, y, k) -> 100);
		TiffRawVolumeStore store = store(e, 1, 1000, null);
		final AtomicBoolean changed = new AtomicBoolean();
		store.setReadHook(cell -> {
			if (changed.compareAndSet(false, true))
				try {
					rewriteFirstPlane(e, 900);		// between the read and its check
				} catch (IOException io) {
					throw new IllegalStateException(io);
				}
		});
		try {
			read(store.blocking(e.key(), e.layout()), 0, 0, 0);
			fail("a read the file changed under was published");
		} catch (TiffRawVolumeStore.StaleFileException expected) {
			RawVolumeStore.CacheStats s = store.stats();
			assertEquals("nothing published", 0, s.loads);
			assertEquals(0, s.residentBytes);
			assertEquals("its reservation was released", 0, s.inFlightBytes);
			assertEquals(1, s.staleReads);
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	public void aReplacedFileIsWithheld() throws Exception {
		RawAcquisition.Entry e = entry("p_Time000001_Channel0001_Frames_1_3.tiff", 3);
		TiffRawVolumeStore store = store(e, 1, 1000, null);
		try {
			RandomAccessibleInterval<UnsignedShortType> img = store.blocking(e.key(), e.layout());
			assertEquals(RawTiffFixture.RAMP.at(1, 1, 0), read(img, 1, 1, 0));
			// a new file of the same name moved over it, while the store holds the old one open
			File replacement = new File(folder.newFolder(), "replacement.tiff");
			RawTiffFixture.write(replacement, W, H, 3, 8, 3, (x, y, k) -> 7);
			Files.move(replacement.toPath(), e.file.toPath(), StandardCopyOption.REPLACE_EXISTING);
			assertFalse(store.checkGeneration(e.key()));
			assertTrue(store.isStale(e.key()));
			try {
				read(img, 1, 1, 1);
				fail("the replaced file was read");
			} catch (TiffRawVolumeStore.StaleFileException expected) {
				// withheld
			}
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	public void aDeletedFileIsWithheld() throws Exception {
		RawAcquisition.Entry e = entry("x_Time000001_Channel0001_Frames_1_3.tiff", 3);
		TiffRawVolumeStore store = store(e, 1, 1000, null);
		final AtomicReference<String> told = new AtomicReference<String>();
		store.setStaleListener((key, why) -> told.set(why));
		try {
			RandomAccessibleInterval<UnsignedShortType> img = store.blocking(e.key(), e.layout());
			read(img, 1, 1, 0);
			Files.delete(e.file.toPath());		// an NIO handle does not prevent it
			try {
				read(img, 1, 1, 2);
				fail("the deleted file was read");
			} catch (TiffRawVolumeStore.StaleFileException expected) {
				assertTrue(told.get(), told.get().contains("deleted"));
			}
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	/* ------------------------------------------------------------ failures, interruptions and the close */

	@Test
	public void aFailedReadReleasesItsReservation() throws Exception {
		RawAcquisition.Entry e = entry("f_Time000001_Channel0001_Frames_1_3.tiff", 3);
		TiffRawVolumeStore store = store(e, 1, 1000, null);
		store.setReadHook(cell -> {
			throw new IllegalStateException("injected read failure");
		});
		try {
			read(store.blocking(e.key(), e.layout()), 0, 0, 0);
			fail("an injected failure was not reported");
		} catch (IllegalStateException expected) {
			RawVolumeStore.CacheStats s = store.stats();
			assertEquals(0, s.inFlightBytes);
			assertEquals(0, s.inFlight);
			assertEquals(1, s.failures);
			assertEquals(0, s.loads);
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	public void anInterruptedWaiterLeavesTheReadToFinishAndReleasesItOnce() throws Exception {
		RawAcquisition.Entry e = entry("i_Time000001_Channel0001_Frames_1_60.tiff", 60);
		final TiffRawVolumeStore store = store(e, 2, 1000, null);
		final RandomAccessibleInterval<UnsignedShortType> raw = store.blocking(e.key(), e.layout());
		final CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
		store.setReadHook(cell -> {
			reading.countDown();
			try {
				release.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException never) {
				throw new IllegalStateException("an IO thread was interrupted");
			}
		});
		try {
			final AtomicReference<Throwable> seen = new AtomicReference<Throwable>();
			Thread waiter = new Thread(() -> {
				try {
					read(raw, 1, 16, 0);
				} catch (Throwable t) {
					seen.set(t);
				}
			});
			waiter.start();
			assertTrue(reading.await(5, TimeUnit.SECONDS));
			waiter.interrupt();
			waiter.join(5000);
			assertTrue("the waiter gave up: " + seen.get(), seen.get() instanceof TiffRawVolumeStore.RawReadException);
			assertEquals("the read is still running, still reserved", 1, store.stats().inFlight);
			store.setReadHook(null);
			release.countDown();
			// the read finished and was published for whoever asks next; the shared channel is intact
			for (int k = 0; k < 60; k++)
				for (int y = 0; y < H; y += 8) assertEquals(RawTiffFixture.RAMP.at(3, y, k), read(raw, 3, y, k));
			RawVolumeStore.CacheStats s = store.stats();
			assertEquals(0, s.inFlightBytes);
			assertEquals(0, s.failures);
			assertWithinBudget(s);
		} finally {
			release.countDown();
			Thread.interrupted();
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	public void closingWhileLoadingDrainsReleasesEverythingAndThenRefuses() throws Exception {
		RawAcquisition.Entry e = entry("z_Time000001_Channel0001_Frames_1_80.tiff", 80);
		final TiffRawVolumeStore store = store(e, 3, 6, null);
		final RandomAccessibleInterval<UnsignedShortType> raw = store.blocking(e.key(), e.layout());
		final AtomicBoolean stop = new AtomicBoolean();
		final AtomicLong wrong = new AtomicLong();
		List<Thread> readers = new ArrayList<Thread>();
		for (int i = 0; i < 6; i++) {
			final int offset = i;
			Thread t = new Thread(() -> {
				RandomAccess<UnsignedShortType> a = raw.randomAccess();
				for (int k = offset; !stop.get(); k = (k + 7) % 80)
					try {
						a.setPosition(new long[] { 2, 9, k });
						if (a.get().get() != RawTiffFixture.RAMP.at(2, 9, k)) wrong.incrementAndGet();
					} catch (RuntimeException closed) {
						return;		// the store closed under this reader: it stops, it is not misread
					}
			});
			t.setDaemon(true);
			t.start();
			readers.add(t);
		}
		Thread.sleep(200);
		long start = System.nanoTime();
		RawVolumeStore.CloseResult result = store.closeAsync().get(10, TimeUnit.SECONDS);
		long millis = (System.nanoTime() - start) / 1000000;
		stop.set(true);
		for (Thread t : readers) t.join(5000);
		assertTrue("closed within the deadline: " + result, result.drained);
		assertTrue("close took " + millis + " ms", millis < TiffRawVolumeStore.CLOSE_DEADLINE_MS);
		assertEquals("no value was ever wrong", 0, wrong.get());
		RawVolumeStore.CacheStats after = result.after;
		assertEquals("nothing resident after the close", 0, after.residentBytes);
		assertEquals("nothing reserved after the close", 0, after.inFlightBytes);
		assertEquals("no read buffer after the close", 0, after.scratchBytes);
		assertEquals("no file handle is left open", 0, after.openChannels);
		assertTrue("the ledger held to its budget throughout: " + after, after.peakTotalBytes <= after.budgetBytes);
		long loads = store.stats().loads;
		try {
			store.blocking(e.key(), e.layout());
			fail("a closed store handed out an image");
		} catch (IllegalStateException expected) {
			// refused
		}
		try {
			read(raw, 2, 9, 1);
			fail("an image of a closed store read");
		} catch (IllegalStateException expected) {
			// refused, and no read was started
		}
		assertEquals("no read after the close", loads, store.stats().loads);
	}
}
