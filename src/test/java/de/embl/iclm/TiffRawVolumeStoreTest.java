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
			// its image is still handed out, for its geometry - BigDataViewer asks for that while withdrawing it
			RandomAccessibleInterval<UnsignedShortType> again = store.blocking(e.key(), e.layout());
			assertEquals(e.layout().width, again.dimension(0));
			try {
				read(again, 3, 9, 1);
				fail("a pixel of a stale file was read");
			} catch (TiffRawVolumeStore.StaleFileException expected) {
				// its pixels are refused
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
			read(store.blocking(e.key(), e.layout()), 2, 9, 3);		// an image, for its geometry; no pixels
			fail("a closed store read");
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

	/* ------------------------------------------------------------ volatile accesses kept across eviction or a change (C1) */

	/** A volatile access that has become valid at {@code pos}: new accesses are taken until BigDataViewer's queue has loaded the cell. */
	private static RandomAccess<VolatileUnsignedShortType> validAccess(RandomAccessibleInterval<VolatileUnsignedShortType> view,
			long... pos) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		do {
			RandomAccess<VolatileUnsignedShortType> a = view.randomAccess();
			a.setPosition(pos);
			if (a.get().isValid()) return a;
			Thread.sleep(5);
		} while (System.currentTimeMillis() < deadline);
		throw new AssertionError("the cell at " + java.util.Arrays.toString(pos) + " never loaded");
	}

	/**
	 * Agent-B's C1 probe, inverted: a volatile access becomes valid on a strip, the strip is evicted
	 * in a later frame, and the access is read again - where it stands, one pixel on, and further
	 * along the same strip. Before: 0, still valid. Now: the value, valid; the cell is read again for
	 * it, and the evicted array itself is not kept.
	 */
	@Test
	public void aVolatileAccessKeptAcrossEvictionReadsItsValueNeverAValidZero() throws Exception {
		RawAcquisition.Entry e = entry("v_Time000001_Channel0001_Frames_0_39.tiff", W, H, 40, 8, (x, y, k) -> 1234);
		SharedQueue queue = new SharedQueue(1, 1);
		TiffRawVolumeStore store = store(e, 1, 2, queue);
		try {
			RandomAccess<VolatileUnsignedShortType> held = validAccess(store.volatileView(e.key(), e.layout()), 0, 0, 0);
			assertEquals(1234, held.get().get().get());
			WeakReference<short[]> pixels = new WeakReference<short[]>(store.residentPixels(e.key(), 0));
			queue.prepareNextFrame();		// the access now belongs to a frame already drawn
			RandomAccessibleInterval<UnsignedShortType> raw = store.blocking(e.key(), e.layout());
			for (int k = 1; k < 40; k++) assertEquals(1234, read(raw, 0, 0, k));
			assertNull("the strip was evicted", store.residentPixels(e.key(), 0));
			assertTrue(held.get().isValid());
			assertEquals("where it stands", 1234, held.get().get().get());
			held.fwd(0);
			assertEquals("one pixel on", 1234, held.get().get().get());
			held.setPosition(new long[] { 20, 7, 0 });
			assertTrue(held.get().isValid());
			assertEquals("further along the same strip", 1234, held.get().get().get());
			assertWithinBudget(store.stats());
			assertEquals("nothing is lent: an evicted array is released", 0, store.stats().lentBytes);
			for (int i = 0; i < 10 && pixels.get() != null; i++) {		// the collector only to observe it
				System.gc();
				Thread.sleep(20);
			}
			assertNull("the access did not keep the evicted array alive", pixels.get());
		} finally {
			queue.shutdown();
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	/** The frame being drawn keeps its pixels: a cell lent in the current frame is passed over by eviction. */
	@Test
	public void aCellLentInTheCurrentFrameIsNotEvicted() throws Exception {
		RawAcquisition.Entry e = entry("n_Time000001_Channel0001_Frames_0_39.tiff", W, H, 40, 8, (x, y, k) -> 4321);
		SharedQueue queue = new SharedQueue(1, 1);
		TiffRawVolumeStore store = store(e, 1, 2, queue);
		try {
			RandomAccess<VolatileUnsignedShortType> held = validAccess(store.volatileView(e.key(), e.layout()), 0, 0, 0);
			RandomAccessibleInterval<UnsignedShortType> raw = store.blocking(e.key(), e.layout());
			for (int k = 1; k < 40; k++) assertEquals(4321, read(raw, 0, 0, k));
			assertTrue("still resident", store.residentPixels(e.key(), 0) != null);
			long loads = store.stats().loads;
			assertEquals(4321, held.get().get().get());
			assertEquals("read without reading the file again", loads, store.stats().loads);
			assertWithinBudget(store.stats());
		} finally {
			queue.shutdown();
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	/**
	 * A file found changed: an access already lent goes on reading the version it was given -
	 * coherent, valid, never zeros - and that array is charged as lent until no access holds it. A
	 * new access gets nothing valid, and a blocking read is refused.
	 */
	@Test
	public void aVolatileAccessKeptAcrossAChangeReadsTheVersionItWasGiven() throws Exception {
		RawAcquisition.Entry e = entry("g_Time000001_Channel0001_Frames_0_39.tiff", W, H, 40, 8, (x, y, k) -> 1234);
		SharedQueue queue = new SharedQueue(1, 1);
		TiffRawVolumeStore store = store(e, 1, 4, queue);
		try {
			RandomAccessibleInterval<VolatileUnsignedShortType> view = store.volatileView(e.key(), e.layout());
			RandomAccess<VolatileUnsignedShortType> held = validAccess(view, 0, 0, 0);
			Files.setLastModifiedTime(e.file.toPath(), FileTime.fromMillis(e.key().modified + 2000));
			assertFalse(store.checkGeneration(e.key()));
			assertTrue(held.get().isValid());
			assertEquals(1234, held.get().get().get());
			held.setPosition(new long[] { 11, 5, 0 });
			assertEquals(1234, held.get().get().get());
			long strip = 2L * W * 8;
			assertEquals("its array is charged while the access may read it", strip, store.stats().lentBytes);
			assertWithinBudget(store.stats());
			for (int i = 0; i < 20; i++) {
				RandomAccess<VolatileUnsignedShortType> fresh = view.randomAccess();
				fresh.setPosition(new long[] { 0, 0, 0 });
				assertFalse("a new access of a changed file is never valid", fresh.get().isValid());
				Thread.sleep(5);
			}
			try {
				read(store.blocking(e.key(), e.layout()), 0, 0, 0);
				fail("a blocking read of a changed file");
			} catch (TiffRawVolumeStore.StaleFileException expected) {
				// refused, though the array is still kept for the lent access
			}
			held = null;
			for (int i = 0; i < 20 && store.stats().lentBytes > 0; i++) {		// the collector only to observe the release
				System.gc();
				Thread.sleep(20);
			}
			assertEquals("released once no access holds it", 0, store.stats().lentBytes);
		} finally {
			queue.shutdown();
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	/**
	 * Samplers keep and move volatile accesses while other threads force eviction under a small
	 * budget and the frames advance: every sample that says it is valid has the file's value.
	 */
	@Test
	public void concurrentEvictionNeverShowsAFabricatedValue() throws Exception {
		final List<RandomAccessibleInterval<VolatileUnsignedShortType>> views = new ArrayList<RandomAccessibleInterval<VolatileUnsignedShortType>>();
		final List<RandomAccessibleInterval<UnsignedShortType>> images = new ArrayList<RandomAccessibleInterval<UnsignedShortType>>();
		final SharedQueue queue = new SharedQueue(2, 1);
		RawAcquisition.Entry first = entry("e1_Time000001_Channel0001_Frames_0_29.tiff", W, H, 30, 8, (x, y, k) -> value(1, x, y, k));
		final TiffRawVolumeStore store = store(first, 2, 6, queue);
		final AtomicBoolean stop = new AtomicBoolean();
		final AtomicLong fabricated = new AtomicLong(), validSamples = new AtomicLong(), refused = new AtomicLong(),
				refusedRereads = new AtomicLong();
		final List<Throwable> escaped = java.util.Collections.synchronizedList(new ArrayList<Throwable>());
		try {
			for (int f = 1; f <= 3; f++) {
				RawAcquisition.Entry e = f == 1 ? first : entry("e" + f + "_Time000001_Channel0001_Frames_0_29.tiff", W, H, 30, 8,
						pixelsOf(f));
				views.add(store.volatileView(e.key(), e.layout()));
				images.add(store.blocking(e.key(), e.layout()));
			}
			List<Thread> threads = new ArrayList<Thread>();
			threads.add(new Thread(() -> {
				while (!stop.get()) {
					queue.prepareNextFrame();
					try {
						Thread.sleep(2);
					} catch (InterruptedException end) {
						return;
					}
				}
			}));
			for (int t = 0; t < 4; t++) {
				final long seed = t;
				threads.add(new Thread(() -> {
					Random random = new Random(seed);
					List<RandomAccess<VolatileUnsignedShortType>> kept = new ArrayList<RandomAccess<VolatileUnsignedShortType>>();
					List<Integer> keptFile = new ArrayList<Integer>();
					while (!stop.get()) {
						int f = random.nextInt(3);
						RandomAccess<VolatileUnsignedShortType> a;
						if (kept.size() < 4 || random.nextBoolean()) {
							a = views.get(f).randomAccess();
							if (kept.size() < 4) {
								kept.add(a);
								keptFile.add(f);
							}
						} else {
							int i = random.nextInt(kept.size());
							a = kept.get(i);
							f = keptFile.get(i);
						}
						int x = random.nextInt(W), y = random.nextInt(H), k = random.nextInt(30);
						a.setPosition(new long[] { x, y, k });
						for (int step = 0; step < 3; step++) {		// and along the row, within the cell
							try {
								VolatileUnsignedShortType v = a.get();
								if (v.isValid()) {
									validSamples.incrementAndGet();
									if (v.get().get() != value(f + 1, (int) a.getLongPosition(0), y, k)) fabricated.incrementAndGet();
								}
							} catch (TiffRawVolumeStore.BudgetException refusedReread) {
								// an access kept beyond its frame on an evicted cell reads it again; under this
								// budget that read can be refused, and the access says so rather than give a value
								refusedRereads.incrementAndGet();
							}
							if (a.getLongPosition(0) < W - 1) a.fwd(0);
						}
					}
				}));
			}
			for (int t = 0; t < 3; t++) {
				final long seed = 100 + t;
				threads.add(new Thread(() -> {
					Random random = new Random(seed);
					while (!stop.get())
						try {
							read(images.get(random.nextInt(3)), random.nextInt(W), random.nextInt(H), random.nextInt(30));
						} catch (TiffRawVolumeStore.BudgetException e) {
							refused.incrementAndGet();
						}
				}));
			}
			for (Thread t : threads) {
				t.setUncaughtExceptionHandler((thread, e) -> escaped.add(e));
				t.start();
			}
			Thread.sleep(2000);
			stop.set(true);
			for (Thread t : threads) t.join(10000);
			RawVolumeStore.CacheStats s = store.stats();
			System.out.println("concurrent eviction: " + validSamples.get() + " valid samples, " + fabricated.get() + " wrong; "
					+ refused.get() + " blocking reads and " + refusedRereads.get() + " re-reads of kept accesses refused; " + s);
			assertEquals("a valid sample with a value the file does not have", 0, fabricated.get());
			assertTrue("no thread died of an exception: " + escaped, escaped.isEmpty());
			assertTrue("the samplers saw valid data: " + validSamples.get(), validSamples.get() > 1000);
			assertTrue("the budget was under pressure: " + s, s.evictions > 100);
			assertWithinBudget(s);
		} finally {
			stop.set(true);
			queue.shutdown();
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	/* ------------------------------------------------------------ leases for prepared frames (C4.2) */

	/** The first strip of each listed plane: whole strips, so each is exactly one cell's bytes. */
	private static TiffRawVolumeStore.Want firstStrips(RawAcquisition.Entry e, int... planes) {
		java.util.BitSet cells = new java.util.BitSet();
		int strips = (H + 7) / 8;
		for (int k : planes) cells.set(strips * k);
		return new TiffRawVolumeStore.Want(e.key(), e.layout(), cells);
	}

	private static TiffRawVolumeStore.Ready ready(TiffRawVolumeStore store, long revision, TiffRawVolumeStore.Want want)
			throws Exception {
		return store.prepare(revision, java.util.Collections.singletonList(want), () -> false).get(10, TimeUnit.SECONDS);
	}

	@Test
	public void aLeasePinsItsCellsAgainstEvictionUntilClosed() throws Exception {
		RawAcquisition.Entry e = entry("l_Time000001_Channel0001_Frames_1_40.tiff", 40);
		TiffRawVolumeStore store = store(e, 1, 6, null);
		long strip = 2L * W * 8;
		try {
			TiffRawVolumeStore.Ready lease = ready(store, 7, firstStrips(e, 0, 10, 20, 30, 39));
			assertEquals(7, lease.revision);
			assertEquals(5, lease.cells());
			assertEquals(5 * strip, store.stats().pinnedBytes);
			RandomAccessibleInterval<UnsignedShortType> raw = store.blocking(e.key(), e.layout());
			for (int k = 0; k < 40; k++)
				for (int y = 0; y < H; y += 8) read(raw, 1, y, k);		// far more than the budget holds
			assertTrue("the budget was under pressure", store.stats().evictions > 50);
			for (int k : new int[] { 0, 10, 20, 30, 39 })
				assertTrue("plane " + k + " stayed resident", store.residentPixels(e.key(), 3L * k) != null);
			assertWithinBudget(store.stats());
			assertTrue(lease.isCurrentAndValid());
			lease.close();
			lease.close();		// once
			assertEquals(0, store.stats().pinnedBytes);
			for (int k = 0; k < 40; k++)
				for (int y = 0; y < H; y += 8) read(raw, 1, y, k);
			int left = 0;
			for (int k : new int[] { 0, 10, 20, 30, 39 }) if (store.residentPixels(e.key(), 3L * k) != null) left++;
			assertTrue("once released, they are evicted like any other: " + left + " left", left < 5);
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	public void twoLeasesSharingCellsCountThemOnce() throws Exception {
		RawAcquisition.Entry e = entry("t_Time000001_Channel0001_Frames_1_9.tiff", 9);
		TiffRawVolumeStore store = store(e, 1, 100, null);
		long strip = 2L * W * 8;
		try {
			TiffRawVolumeStore.Ready shown = ready(store, 1, firstStrips(e, 0, 1, 2));
			TiffRawVolumeStore.Ready next = ready(store, 2, firstStrips(e, 2, 3));
			assertEquals("the active and the prepared frame share plane 2", 4 * strip, store.stats().pinnedBytes);
			shown.close();
			assertEquals("plane 2 is still held by the next frame", 2 * strip, store.stats().pinnedBytes);
			next.close();
			assertEquals(0, store.stats().pinnedBytes);
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	public void aCancelledPreparationReleasesWhatItPinned() throws Exception {
		RawAcquisition.Entry e = entry("x_Time000001_Channel0001_Frames_1_30.tiff", 30);
		TiffRawVolumeStore store = store(e, 1, 100, null);
		final AtomicLong asked = new AtomicLong();
		try {
			int[] planes = new int[30];
			for (int k = 0; k < 30; k++) planes[k] = k;
			java.util.concurrent.CompletableFuture<TiffRawVolumeStore.Ready> f = store.prepare(3,
					java.util.Collections.singletonList(firstStrips(e, planes)), () -> asked.incrementAndGet() > 10);
			try {
				f.get(10, TimeUnit.SECONDS);
				fail("a cancelled preparation completed");
			} catch (java.util.concurrent.CancellationException | java.util.concurrent.ExecutionException expected) {
				// cancelled part way
			}
			assertEquals("nothing left pinned", 0, store.stats().pinnedBytes);
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	public void aPreparationTheBudgetCannotHoldIsRefusedAndHoldsNothing() throws Exception {
		RawAcquisition.Entry e = entry("o_Time000001_Channel0001_Frames_1_40.tiff", 40);
		TiffRawVolumeStore store = store(e, 1, 2, null);		// room for about 10 cells
		try {
			int[] planes = new int[40];
			for (int k = 0; k < 40; k++) planes[k] = k;
			try {
				ready(store, 4, firstStrips(e, planes));
				fail("a frame larger than the budget was prepared");
			} catch (java.util.concurrent.ExecutionException expected) {
				assertTrue(String.valueOf(expected.getCause()), expected.getCause() instanceof TiffRawVolumeStore.BudgetException);
			}
			RawVolumeStore.CacheStats s = store.stats();
			assertEquals("nothing left pinned", 0, s.pinnedBytes);
			assertWithinBudget(s);
			assertTrue("refused, not half-prepared", s.refusals > 0);
			assertEquals("refused before anything was read", 0, s.loads);
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	public void aFileChangedDuringOrAfterPreparationIsSeenByItsLease() throws Exception {
		final RawAcquisition.Entry e = entry("c_Time000001_Channel0001_Frames_0_9.tiff", W, H, 10, 8, (x, y, k) -> 100);
		TiffRawVolumeStore store = store(e, 1, 100, null);
		try {
			TiffRawVolumeStore.Ready lease = ready(store, 5, firstStrips(e, 0, 1));
			assertTrue(lease.isCurrentAndValid());
			Files.setLastModifiedTime(e.file.toPath(), FileTime.fromMillis(e.key().modified + 2000));
			assertFalse("a lease of a file changed since says so", lease.isCurrentAndValid());
			lease.close();
			assertEquals(0, store.stats().pinnedBytes);
			try {
				ready(store, 6, firstStrips(e, 2, 3));
				fail("a changed file was prepared");
			} catch (java.util.concurrent.ExecutionException expected) {
				assertTrue(String.valueOf(expected.getCause()), expected.getCause() instanceof TiffRawVolumeStore.StaleFileException);
			}
			assertEquals(0, store.stats().pinnedBytes);
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	/**
	 * An image access is handed a cell whenever it asks for its pixel, and it may ask without
	 * reading a value: ImgLib2's trilinear interpolator takes its pixel type from a fresh access at
	 * the origin when it is made. Being handed a cell reads nothing; reading a value reads its cell
	 * - so a frame reads exactly what its samples read, which is what its coverage pins.
	 */
	@Test
	public void aCellIsReadWhenAValueIsReadNotWhenItIsHandedOver() throws Exception {
		RawAcquisition.Entry e = entry("d_Time000001_Channel0001_Frames_1_12.tiff", 12);
		TiffRawVolumeStore store = store(e, 1, 100, null);
		try {
			RandomAccessibleInterval<UnsignedShortType> raw = store.blocking(e.key(), e.layout());
			UnsignedShortType type = raw.randomAccess().get();		// what the interpolator does when it is made
			type.createVariable();
			net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory<UnsignedShortType> trilinear =
					new net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory<UnsignedShortType>();
			net.imglib2.RealRandomAccess<UnsignedShortType> sample = trilinear.create(raw);
			assertEquals("handing over a cell read nothing", 0, store.stats().loads);
			RandomAccess<UnsignedShortType> a = raw.randomAccess();
			a.setPosition(new long[] { 3, 2, 0 });
			for (int k = 1; k < 12; k++) a.fwd(2);
			a.setPosition(9, 1);
			UnsignedShortType there = a.get();
			assertEquals("nor did moving and being handed another", 0, store.stats().loads);
			assertEquals(RawTiffFixture.RAMP.at(3, 9, 11), there.get());
			assertEquals("the value read its own cell, and only that", 1, store.stats().loads);
			a.setPosition(new long[] { 4, 9, 11 });
			assertEquals(RawTiffFixture.RAMP.at(4, 9, 11), a.get().get());
			assertEquals("a resident cell is not read again", 1, store.stats().loads);
			// on a voxel, trilinear still reads the next one along every axis, at weight 0: planes 5 and 6
			sample.setPosition(new double[] { 3, 9, 5 });
			assertEquals(RawTiffFixture.RAMP.at(3, 9, 5), sample.get().get());
			assertEquals("the sample read the two cells it touches", 3, store.stats().loads);
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	public void aStagedFrameIsChargedToTheBudgetMakesRoomByEvictionAndIsRefusedWhenPinsLeaveNone() throws Exception {
		RawAcquisition.Entry e = entry("f_Time000001_Channel0001_Frames_1_40.tiff", 40);
		long cell = TiffRawVolumeStore.cellBytes(e.layout());
		TiffRawVolumeStore store = store(e, 1, 6, null);
		try {
			RandomAccessibleInterval<UnsignedShortType> raw = store.blocking(e.key(), e.layout());
			for (int k = 0; k < 40; k++) read(raw, 1, 1, k);		// the budget filled with cells
			long resident = store.stats().residentBytes;
			TiffRawVolumeStore.Reservation frame = store.reserve(4 * cell);
			RawVolumeStore.CacheStats s = store.stats();
			assertEquals(4 * cell, s.stagedBytes);
			assertTrue("room was made by evicting cells: " + s, s.residentBytes < resident);
			assertWithinBudget(s);
			TiffRawVolumeStore.Ready lease = ready(store, 1, firstStrips(e, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9));
			try {
				store.reserve(s.budgetBytes);
				fail("a frame larger than what the pins leave was reserved");
			} catch (TiffRawVolumeStore.BudgetException expected) {
				// refused at once
			}
			assertEquals("the refused frame holds nothing", 4 * cell, store.stats().stagedBytes);
			assertTrue(store.stats().refusals > 0);
			lease.close();
			frame.close();
			frame.close();		// once
			assertEquals(0, store.stats().stagedBytes);
			assertWithinBudget(store.stats());
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	/** A value per file and voxel, never 0. */
	private static int value(int file, int x, int y, int k) {
		return 1 + file * 5000 + x + 30 * y + 7 * k;
	}

	private static RawTiffFixture.Pixels pixelsOf(final int file) {
		return (x, y, k) -> value(file, x, y, k);
	}
}
