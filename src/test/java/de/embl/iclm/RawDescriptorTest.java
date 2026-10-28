package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.viewer.Interpolation;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedShortType;

/**
 * M3-A-F1: a file's version and its layout are one descriptor, taken in one read, wherever a frame is drawn from them - a
 * BigDataViewer source call over a presence whose frame changes meanwhile, and a completed-frame request made while the file
 * is checked again. B's two probes, ported; the files alternate between two heights, so that a version paired with another
 * check's layout is seen.
 */
public class RawDescriptorTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@After
	public void reset() {
		RawAcquisition.quietMsForTest = -1;
	}

	private File write(String prefix, int time, int height) throws Exception {
		return RawTiffFixture.write(new File(folder.getRoot(), prefix + "_Time" + time + "_Channel1_Frames_1_4.tiff"), 32, height, 4, 8,
				4, RawTiffFixture.RAMP);
	}

	/**
	 * Two snapshots with the same numbering, each coherent, whose one time point is shown from files of different heights;
	 * a thread switches the presence between them frame after frame while sources are asked for. Every call hands the volume
	 * supplier a version with its own layout.
	 */
	@Test
	public void aSourceCallUsesOneFrameAcrossConcurrentFrameRefreshes() throws Exception {
		File f1 = write("one", 1, 20), f2 = write("two", 2, 24);
		RawAcquisition a = RawAcquisition.scan(folder.getRoot());
		for (RawAcquisition.Entry e : a.snapshot(1, 1).entries) e.validate();
		RawAcquisition.Entry e1 = a.entry(new RawAcquisition.TimeKey(1, 1), 1);
		RawAcquisition.Entry e2 = new RawAcquisition.Entry(e1.time, 1, f2, 4, null);
		e2.validate();
		RawAcquisition.TimeKey time = new RawAcquisition.TimeKey(1, 1);
		RawAcquisition.Snapshot s1 = new RawAcquisition.Snapshot(1, 1, 1, Collections.singletonList(time), Collections.singletonList(e1));
		RawAcquisition.Snapshot s2 = new RawAcquisition.Snapshot(2, 1, 1, Collections.singletonList(time), Collections.singletonList(e2));
		AtomicReference<RawAcquisition.Snapshot> current = new AtomicReference<RawAcquisition.Snapshot>(s1);
		RawPresence presence = new RawPresence(current::get);
		AtomicBoolean stop = new AtomicBoolean();
		AtomicReference<Throwable> error = new AtomicReference<Throwable>();
		final RandomAccessibleInterval<UnsignedShortType> pixels = ArrayImgs.unsignedShorts(32, 24, 4);
		final int[] mixed = new int[1];
		RawPartSource<UnsignedShortType> source = new RawPartSource<UnsignedShortType>(RawSourceSpec.half(null, 1, true, false, 32, 0xffffff),
				new RawGeometry(.265, .116, 33.5, "descriptor test"), (key, layout) -> {
					if (layout.height != (key.file.equals(f1) ? 20 : 24)) mixed[0]++;
					return pixels;
				}, new UnsignedShortType(), new long[] { 32, 20, 4 }, presence);
		Thread refresh = new Thread(() -> {
			try {
				while (!stop.get()) {
					current.set(s2);
					presence.prepareNextFrame();
					current.set(s1);
					presence.prepareNextFrame();
				}
			} catch (Throwable t) {
				error.set(t);
			}
		}, "descriptor-test-frames");
		refresh.start();
		try {
			for (int i = 0; i < 500000; i++) source.getSource(0, 0);
		} finally {
			stop.set(true);
			refresh.join(5000);
		}
		assertFalse(refresh.isAlive());
		if (error.get() != null) throw new AssertionError(error.get());
		assertEquals("source calls whose version and layout came from different frames", 0, mixed[0]);
	}

	/**
	 * A file rewritten again and again, alternating between two heights, and checked again each time while requests are made
	 * of its entry. A request is refused while the entry shows nothing; every request made has one check's version and layout.
	 */
	@Test
	public void aRequestMadeWhileItsFileIsCheckedAgainHasOneChecksVersionAndLayout() throws Exception {
		File f = write("b", 1, 24);
		final long big = f.length();
		RawTiffFixture.write(f, 32, 20, 4, 8, 4, RawTiffFixture.RAMP);
		final long small = f.length();
		RawAcquisition a = RawAcquisition.scan(folder.getRoot());
		RawAcquisition.Entry e = a.snapshot(1, 1).entry(0);
		e.validate();
		AtomicBoolean done = new AtomicBoolean(), stop = new AtomicBoolean();
		AtomicReference<Throwable> error = new AtomicReference<Throwable>();
		RawAcquisition.quietMsForTest = 0;
		Thread writer = new Thread(() -> {
			try {
				for (int i = 0; i < 1000 && !stop.get(); i++) {
					RawAcquisition.FileKey old = e.key();
					RawTiffFixture.write(f, 32, (i & 1) == 0 ? 24 : 20, 4, 8, 4, RawTiffFixture.RAMP);
					e.observe(RawAcquisition.FileKey.of(f, 1), System.currentTimeMillis());
					e.markChanged(old, "rewritten by the test");
					e.recheck(System.currentTimeMillis());
				}
			} catch (Throwable t) {
				error.set(t);
			} finally {
				done.set(true);
			}
		}, "descriptor-test-revalidation");
		writer.start();
		long deadline = System.nanoTime() + 15000000000L;
		int made = 0;
		try {
			while ((!done.get() || made < 100) && System.nanoTime() < deadline) {
				RawFrameRequest r;
				try {
					r = new RawFrameRequest(1, 0, e, new AffineTransform3D(), 32, 24, Interpolation.NLINEAR,
							Collections.<RawFrameRequest.Layer> emptyList());
				} catch (IllegalArgumentException notShown) {
					continue;
				}
				made++;
				assertNotNull("a request made has its version", r.key);
				assertNotNull("and its layout", r.layout);
				assertTrue("and they are its publication's", r.key == r.publication.key && r.layout == r.publication.layout);
				int expected = r.key.size == small ? 20 : r.key.size == big ? 24 : -1;
				assertEquals("a version of " + r.key.size + " bytes with another check's layout", expected, r.layout.height);
			}
			assertTrue("the writer finished", done.get());
			assertTrue("requests made: " + made, made >= 100);
		} finally {
			stop.set(true);
			writer.join(5000);
			System.out.println("requests made while the file was checked again: " + made);
		}
		assertFalse(writer.isAlive());
		if (error.get() != null) throw new AssertionError(error.get());
	}

	/**
	 * The same property, deterministic: a request carries the very publication its entry shows - one object, made by one
	 * check - and its version and layout are that object's, never a pair read from the entry one after the other; an entry
	 * that shows nothing gives no request. That the frame's layers are placed by the same object is checked in the view
	 * ({@code OnlineDeskewWithdrawalGuiTest}).
	 */
	@Test
	public void aRequestCarriesTheEntrysOwnPublication() throws Exception {
		write("p", 1, 20);
		RawAcquisition a = RawAcquisition.scan(folder.getRoot());
		RawAcquisition.Entry e = a.snapshot(1, 1).entry(0);
		e.validate();
		RawAcquisition.Entry.Shown shown = e.shown();
		assertNotNull(shown);
		RawFrameRequest r = new RawFrameRequest(1, 0, e, new AffineTransform3D(), 32, 20, Interpolation.NLINEAR,
				Collections.<RawFrameRequest.Layer> emptyList());
		assertSame("the entry's own publication", shown, r.publication);
		assertSame(shown.key, r.key);
		assertSame(shown.layout, r.layout);
		assertTrue(e.markChanged(shown.key, "changed by the test"));
		try {
			new RawFrameRequest(2, 0, e, new AffineTransform3D(), 32, 20, Interpolation.NLINEAR,
					Collections.<RawFrameRequest.Layer> emptyList());
			fail("a request of an entry that shows nothing");
		} catch (IllegalArgumentException expected) {
			// refused: never half a request
		}
	}
}
