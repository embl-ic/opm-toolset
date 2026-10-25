package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.cache.SharedQueue;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.volatiles.VolatileUnsignedShortType;

/**
 * The Online Deskew View's raw store (joint contracts C3.1 - C3.8, C7.2): exact pixels through
 * strip-shaped cells, a byte budget that holds under a working set larger than itself, a changed
 * file refused rather than misread, and a close that drains running reads without blocking the
 * caller.
 */
public class TiffRawVolumeStoreTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final int W = 26, H = 17;

	private RawAcquisition.Entry entry(String name, int depth) throws IOException {
		RawTiffFixture.write(new File(folder.getRoot(), name), W, H, depth, 8, depth, RawTiffFixture.RAMP);
		RawAcquisition acquisition = RawAcquisition.scan(folder.getRoot());
		for (RawAcquisition.TimeKey t : acquisition.times(1, 1)) {
			RawAcquisition.Entry e = acquisition.entry(t, 1);
			if (e.file.getName().equals(name)) {
				assertEquals(RawAcquisition.Readiness.COMPLETE, e.validate());
				return e;
			}
		}
		throw new IllegalStateException("fixture not found");
	}

	private static TiffRawVolumeStore store(RawAcquisition.Entry e, long cells, SharedQueue queue) {
		return new TiffRawVolumeStore(cells * TiffRawVolumeStore.cellBytes(e.layout()),
				TiffRawVolumeStore.cellBytes(e.layout()), 3, queue);
	}

	@Test
	public void everyVoxelIsTheRawValueAndBothHalvesShareTheCells() throws Exception {
		RawAcquisition.Entry e = entry("s_Time000001_Channel0001_Frames_1_7.tiff", 7);
		TiffRawVolumeStore store = store(e, 1000, null);
		try {
			RandomAccessibleInterval<UnsignedShortType> raw = store.blocking(e.key(), e.layout());
			RandomAccess<UnsignedShortType> a = raw.randomAccess();
			for (int k = 0; k < 7; k++)
				for (int y = 0; y < H; y++)
					for (int x = 0; x < W; x++) {
						a.setPosition(new long[] { x, y, k });
						assertEquals(x + "," + y + "," + k, RawTiffFixture.RAMP.at(x, y, k), a.get().get());
					}
			long loads = store.stats().loads;
			assertEquals("7 planes of 3 strips, each read once", 21, loads);
			// the right half is a view of the same cells: reading it all reads nothing new
			RawSourceSpec right = RawSourceSpec.half(null, 1, false, false, W, 0);
			RandomAccess<UnsignedShortType> r = RawSampling.crop(raw, right).randomAccess();
			r.setPosition(new long[] { 0, 5, 3 });
			assertEquals(RawTiffFixture.RAMP.at(W - W / 2, 5, 3), r.get().get());
			assertEquals(loads, store.stats().loads);
		} finally {
			assertTrue(store.closeAsync().get(10, TimeUnit.SECONDS).drained);
		}
	}

	@Test
	public void theVolatileViewFillsInThroughTheQueue() throws Exception {
		RawAcquisition.Entry e = entry("q_Time000001_Channel0001_Frames_1_4.tiff", 4);
		SharedQueue queue = new SharedQueue(2, 1);
		TiffRawVolumeStore store = store(e, 1000, queue);
		try {
			RandomAccessibleInterval<VolatileUnsignedShortType> view = store.volatileView(e.key(), e.layout());
			RandomAccess<VolatileUnsignedShortType> v = view.randomAccess();
			v.setPosition(new long[] { 11, 9, 2 });
			long deadline = System.currentTimeMillis() + 5000;
			while (!v.get().isValid() && System.currentTimeMillis() < deadline) {
				Thread.sleep(10);
				// an access keeps the placeholder cell it first saw; BigDataViewer takes a new one per frame
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
	public void aBudgetSmallerThanTheVolumeEvictsAndReloadsCorrectly() throws Exception {
		RawAcquisition.Entry e = entry("b_Time000001_Channel0001_Frames_1_40.tiff", 40);
		TiffRawVolumeStore store = store(e, 16, null);		// 120 cells of data, 16 allowed
		try {
			assertEquals(16, store.stats().maxCells);
			RandomAccessibleInterval<UnsignedShortType> raw = store.blocking(e.key(), e.layout());
			for (int pass = 0; pass < 2; pass++) {
				RandomAccess<UnsignedShortType> a = raw.randomAccess();
				for (int k = 0; k < 40; k++)
					for (int y = 0; y < H; y += 8) {
						a.setPosition(new long[] { 5, y, k });
						assertEquals(RawTiffFixture.RAMP.at(5, y, k), a.get().get());
					}
				a = null;
				// eviction is asynchronous and evicted cells are freed by the collector
				store.cleanUp();
				for (int i = 0; i < 3; i++) System.gc();
				store.cleanUp();
			}
			assertTrue("cells were evicted and read again: " + store.stats().loads, store.stats().loads > 120);
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	public void aFileChangedAfterItsCheckIsRefused() throws Exception {
		RawAcquisition.Entry e = entry("c_Time000001_Channel0001_Frames_1_3.tiff", 3);
		TiffRawVolumeStore store = store(e, 1000, null);
		try (FileWriter w = new FileWriter(e.file, true)) {
			w.write("the writer came back");
		}
		try {
			RandomAccess<UnsignedShortType> a = store.blocking(e.key(), e.layout()).randomAccess();
			a.setPosition(new long[] { 0, 0, 0 });
			a.get();
			fail("a changed file was read");
		} catch (RuntimeException expected) {
			assertTrue(String.valueOf(expected), String.valueOf(rootCause(expected)).contains("changed after it was checked"));
			assertEquals(1, store.stats().failures);
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	public void anInterruptedWaiterDoesNotBreakTheOtherReaders() throws Exception {
		RawAcquisition.Entry e = entry("i_Time000001_Channel0001_Frames_1_60.tiff", 60);
		final TiffRawVolumeStore store = store(e, 1000, null);
		final RandomAccessibleInterval<UnsignedShortType> raw = store.blocking(e.key(), e.layout());
		try {
			Thread waiter = new Thread(() -> {
				RandomAccess<UnsignedShortType> a = raw.randomAccess();
				try {
					for (int k = 0; k < 60; k++) {
						a.setPosition(new long[] { 1, 16, k });
						a.get();
					}
				} catch (RuntimeException interrupted) {
					// the interrupt surfaces as a failed load; that is all it may do
				}
			});
			waiter.start();
			waiter.interrupt();
			waiter.join(5000);
			// the IO threads were never interrupted, so the shared channel is still usable
			RandomAccess<UnsignedShortType> a = raw.randomAccess();
			for (int k = 0; k < 60; k++)
				for (int y = 0; y < H; y += 8) {
					a.setPosition(new long[] { 3, y, k });
					assertEquals(RawTiffFixture.RAMP.at(3, y, k), a.get().get());
				}
		} finally {
			Thread.interrupted();
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	public void closingWhileLoadingDrainsAndThenRefuses() throws Exception {
		RawAcquisition.Entry e = entry("z_Time000001_Channel0001_Frames_1_80.tiff", 80);
		final TiffRawVolumeStore store = store(e, 16, null);
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
		assertEquals("no file handle is left open", 0, store.stats().openChannels);
		try {
			store.blocking(e.key(), e.layout());
			fail("a closed store handed out an image");
		} catch (IllegalStateException expected) {
			// refused
		}
	}

	private static Throwable rootCause(Throwable t) {
		while (t.getCause() != null && t.getCause() != t) t = t.getCause();
		return t;
	}
}
