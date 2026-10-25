package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.cache.CacheControl;
import bdv.cache.SharedQueue;
import bdv.viewer.BasicViewerState;
import bdv.viewer.DisplayMode;
import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.render.MultiResolutionRenderer;
import bdv.viewer.render.RenderTarget;
import bdv.viewer.render.awt.BufferedImageRenderResult;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.display.RealARGBColorConverter;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.volatiles.VolatileUnsignedShortType;

/**
 * The Online Deskew View's BigDataViewer sources (joint contract C5.1): each time index shows its
 * own file through the shared store, a time point that cannot be shown is absent, each file's own
 * height enters its transform, and BigDataViewer's renderer draws both camera halves.
 *
 * <p>The render tests build BigDataViewer's renderer directly, which only test code may do: its
 * constructor changed between the managed release and the one Fiji ships (contract C5.3), so
 * {@link #renderer} picks whichever is present.
 */
public class RawPartSourceTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final int W = 26;
	private static final RawGeometry GEOMETRY = new RawGeometry(0.265, 0.116, 33.5, "test");

	private RawAcquisition acquisition(int[] heights, RawTiffFixture.Pixels[] pixels, int[] written) throws IOException {
		for (int t = 0; t < heights.length; t++)
			RawTiffFixture.write(new File(folder.getRoot(), String.format("p_Time%06d_Channel0001_Frames_1_9.tiff", t + 1)),
					W, heights[t], 9, 8, written[t], pixels[t]);
		return RawAcquisition.scan(folder.getRoot());
	}

	private static RawPartSource<UnsignedShortType> source(RawAcquisition a, TiffRawVolumeStore store, boolean left) {
		List<RawAcquisition.TimeKey> times = a.times(1, 1);
		RawAcquisition.Entry first = a.entry(times.get(0), 1);
		first.validate();
		return new RawPartSource<UnsignedShortType>(a, times, RawSourceSpec.half(null, 1, left, false, W, left ? 0x00ff00 : 0xff00ff),
				GEOMETRY, store::blocking, new UnsignedShortType(), first.layout().dimensions());
	}

	private static TiffRawVolumeStore store() {
		return new TiffRawVolumeStore(64L << 20, 2L * W * 8, 2, null);
	}

	@Test
	public void eachTimeIndexShowsItsOwnFile() throws Exception {
		RawTiffFixture.Pixels second = (x, y, k) -> 40000 + x + y + k;
		RawAcquisition a = acquisition(new int[] { 17, 17 }, new RawTiffFixture.Pixels[] { RawTiffFixture.RAMP, second },
				new int[] { 9, 9 });
		for (RawAcquisition.TimeKey t : a.times(1, 1)) a.entry(t, 1).validate();
		TiffRawVolumeStore store = store();
		try {
			RawPartSource<UnsignedShortType> left = source(a, store, true);
			assertTrue(left.isPresent(0));
			assertTrue(left.isPresent(1));
			assertFalse(left.isPresent(2));
			RandomAccess<UnsignedShortType> t0 = left.getSource(0, 0).randomAccess(), t1 = left.getSource(1, 0).randomAccess();
			t0.setPosition(new long[] { 4, 6, 3 });
			t1.setPosition(new long[] { 4, 6, 3 });
			assertEquals(RawTiffFixture.RAMP.at(4, 6, 3), t0.get().get());
			assertEquals(second.at(4, 6, 3), t1.get().get());
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	public void aTimePointThatCannotBeShownIsAbsent() throws Exception {
		RawAcquisition a = acquisition(new int[] { 17, 17 },
				new RawTiffFixture.Pixels[] { RawTiffFixture.RAMP, RawTiffFixture.RAMP }, new int[] { 9, 4 });
		TiffRawVolumeStore store = store();
		try {
			RawPartSource<UnsignedShortType> left = source(a, store, true);
			assertFalse("not checked yet is not present", left.isPresent(1));
			assertEquals(RawAcquisition.Readiness.INCOMPLETE, a.entry(a.times(1, 1).get(1), 1).validate());
			assertFalse("4 of 9 planes is not present", left.isPresent(1));
			assertTrue(left.isPresent(0));
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	public void eachFilesOwnHeightEntersItsTransform() throws Exception {
		RawAcquisition a = acquisition(new int[] { 17, 21 },
				new RawTiffFixture.Pixels[] { RawTiffFixture.RAMP, RawTiffFixture.RAMP }, new int[] { 9, 9 });
		for (RawAcquisition.TimeKey t : a.times(1, 1)) a.entry(t, 1).validate();
		TiffRawVolumeStore store = store();
		try {
			RawPartSource<UnsignedShortType> right = source(a, store, false);
			AffineTransform3D t0 = new AffineTransform3D(), t1 = new AffineTransform3D();
			right.getSourceTransform(0, 0, t0);
			right.getSourceTransform(1, 0, t1);
			assertArrayEquals(GEOMETRY.sourceToWorld(new long[] { W, 17, 9 }, right.spec(), null).getRowPackedCopy(),
					t0.getRowPackedCopy(), 1e-12);
			assertArrayEquals(GEOMETRY.sourceToWorld(new long[] { W, 21, 9 }, right.spec(), null).getRowPackedCopy(),
					t1.getRowPackedCopy(), 1e-12);
			assertNotEquals("the Z translation follows the height", t0.get(2, 3), t1.get(2, 3), 1e-6);
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	/**
	 * An odd camera width through the whole path - file, store, source: the centre column belongs
	 * to neither half, as in {@code Partition}, and an interpolated sample at a half's inner edge
	 * blends with the zero outside it, never with the centre column or the other half.
	 */
	@Test
	public void anOddWidthDropsTheCentreColumnAndNoHalfBlendsAcrossTheSeam() throws Exception {
		final int odd = 27, centre = 13;
		RawTiffFixture.Pixels seam = (x, y, k) -> x == centre ? 60000 : 1000;
		RawTiffFixture.write(new File(folder.getRoot(), "o_Time000001_Channel0001_Frames_1_9.tiff"), odd, 17, 9, 8, 9, seam);
		RawAcquisition a = RawAcquisition.scan(folder.getRoot());
		List<RawAcquisition.TimeKey> times = a.times(1, 1);
		RawAcquisition.Entry entry = a.entry(times.get(0), 1);
		assertEquals(RawAcquisition.Readiness.COMPLETE, entry.validate());
		TiffRawVolumeStore store = new TiffRawVolumeStore(64L << 20, TiffRawVolumeStore.cellBytes(entry.layout()), 2, null);
		try {
			for (boolean left : new boolean[] { true, false }) {
				RawPartSource<UnsignedShortType> s = new RawPartSource<UnsignedShortType>(a, times,
						RawSourceSpec.half(null, 1, left, false, odd, 0), GEOMETRY, store::blocking, new UnsignedShortType(),
						entry.layout().dimensions());
				assertEquals("floor(27 / 2)", 13, s.getSource(0, 0).dimension(0));
				for (UnsignedShortType v : net.imglib2.view.Views.iterable(s.getSource(0, 0)))
					assertEquals("the centre column is in neither half", 1000, v.get());
				net.imglib2.RealRandomAccess<UnsignedShortType> edge =
						s.getInterpolatedSource(0, 0, Interpolation.NLINEAR).realRandomAccess();
				edge.setPosition(new double[] { left ? 12.5 : -0.5, 8, 4 });
				assertEquals("inner edge blends with zero only", 500, edge.get().get());
			}
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	/**
	 * C2: BigDataViewer found a time point present, then its file was found changed, and then it
	 * asks for the source - the box overlay does exactly that on the event thread. Geometry is still
	 * answered, from the version that was checked; no call throws; pixels are invalid (volatile) or
	 * refused (blocking), never values. Both halves keep one answer within a frame and drop the time
	 * point together at the next; another time point is untouched.
	 */
	@Test
	public void aWithdrawnTimePointAnswersGeometryGivesNoPixelsAndGoesWithBothHalves() throws Exception {
		final RawAcquisition a = acquisition(new int[] { 17, 17 },
				new RawTiffFixture.Pixels[] { RawTiffFixture.RAMP, RawTiffFixture.RAMP }, new int[] { 9, 9 });
		final List<RawAcquisition.TimeKey> times = a.times(1, 1);
		for (RawAcquisition.TimeKey t : times) a.entry(t, 1).validate();
		RawAcquisition.Entry changed = a.entry(times.get(0), 1);
		SharedQueue queue = new SharedQueue(1, 1);
		TiffRawVolumeStore store = new TiffRawVolumeStore(64L << 20, 2L * W * 8, 2, queue);
		store.setStaleListener(a::markChanged);
		RawPresence presence = new RawPresence(a, times, 1);
		try {
			List<RawPartSource<UnsignedShortType>> blocking = new ArrayList<RawPartSource<UnsignedShortType>>();
			List<RawPartSource<VolatileUnsignedShortType>> volatiles = new ArrayList<RawPartSource<VolatileUnsignedShortType>>();
			for (boolean left : new boolean[] { true, false }) {
				RawSourceSpec spec = RawSourceSpec.half(null, 1, left, false, W, 0);
				blocking.add(new RawPartSource<UnsignedShortType>(a, times, spec, GEOMETRY, store::blocking,
						new UnsignedShortType(), changed.layout().dimensions(), presence));
				volatiles.add(new RawPartSource<VolatileUnsignedShortType>(a, times, spec, GEOMETRY, store::volatileView,
						new VolatileUnsignedShortType(), changed.layout().dimensions(), presence));
			}
			assertEquals(RawTiffFixture.RAMP.at(1, 2, 3), read(blocking.get(0).getSource(0, 0), 1, 2, 3));
			Files.setLastModifiedTime(changed.file.toPath(), FileTime.fromMillis(changed.key().modified + 2000));
			assertFalse(store.checkGeneration(changed.key()));
			assertEquals(RawAcquisition.Readiness.CHANGED, changed.readiness());
			// within the frame being drawn both halves still say present ...
			for (Source<?> s : blocking) assertTrue(s.isPresent(0));
			for (Source<?> s : volatiles) assertTrue(s.isPresent(0));
			// ... and whatever BigDataViewer asks next is answered without an exception
			for (int i = 0; i < 2; i++) {
				RawSourceSpec spec = blocking.get(i).spec();
				for (Source<?> s : new Source<?>[] { blocking.get(i), volatiles.get(i) }) {
					assertEquals(W / 2, s.getSource(0, 0).dimension(0));
					AffineTransform3D t = new AffineTransform3D();
					s.getSourceTransform(0, 0, t);
					assertArrayEquals(GEOMETRY.sourceToWorld(new long[] { W, 17, 9 }, spec, null).getRowPackedCopy(),
							t.getRowPackedCopy(), 1e-12);
				}
				for (Interpolation method : Interpolation.values()) {
					RealRandomAccess<VolatileUnsignedShortType> v = volatiles.get(i).getInterpolatedSource(0, 0, method)
							.realRandomAccess();
					v.setPosition(new double[] { 3.4, 5.5, 2.2 });
					assertFalse(method + ": a withdrawn file's pixels are not valid", v.get().isValid());
					try {
						RealRandomAccess<UnsignedShortType> b = blocking.get(i).getInterpolatedSource(0, 0, method).realRandomAccess();
						b.setPosition(new double[] { 3.4, 5.5, 2.2 });
						b.get().get();
						fail(method + ": a blocking read of a withdrawn file");
					} catch (TiffRawVolumeStore.StaleFileException expected) {
						// refused, never a value
					}
				}
			}
			// the next frame drops it, both halves at once
			presence.prepareNextFrame();
			for (Source<?> s : blocking) assertFalse(s.isPresent(0));
			for (Source<?> s : volatiles) assertFalse(s.isPresent(0));
			// the other time point is as it was
			assertTrue(volatiles.get(1).isPresent(1));
			assertEquals(RawTiffFixture.RAMP.at(W - W / 2 + 4, 6, 2), read(blocking.get(1).getSource(1, 0), 4, 6, 2));
			long deadline = System.currentTimeMillis() + 5000;
			boolean valid = false;
			while (!valid && System.currentTimeMillis() < deadline) {
				RandomAccess<VolatileUnsignedShortType> v = volatiles.get(1).getSource(1, 0).randomAccess();
				v.setPosition(new long[] { 4, 6, 2 });
				valid = v.get().isValid() && v.get().get().get() == RawTiffFixture.RAMP.at(W - W / 2 + 4, 6, 2);
				if (!valid) Thread.sleep(5);
			}
			assertTrue("another time point still shows its pixels", valid);
		} finally {
			queue.shutdown();
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	private static int read(RandomAccessibleInterval<UnsignedShortType> img, long x, long y, long z) {
		RandomAccess<UnsignedShortType> a = img.randomAccess();
		a.setPosition(new long[] { x, y, z });
		return a.get().get();
	}

	/**
	 * C1 through the source: nearest and trilinear samples of a volatile source, kept across the
	 * eviction of their cells, are the values a blocking source with room for everything gives -
	 * where they stand and a little further on.
	 */
	@Test
	public void interpolatedVolatileSamplesKeptAcrossEvictionAreTheReferenceValues() throws Exception {
		RawAcquisition a = acquisition(new int[] { 17 }, new RawTiffFixture.Pixels[] { RawTiffFixture.RAMP }, new int[] { 9 });
		List<RawAcquisition.TimeKey> times = a.times(1, 1);
		RawAcquisition.Entry e = a.entry(times.get(0), 1);
		e.validate();
		long cell = TiffRawVolumeStore.cellBytes(e.layout());
		SharedQueue queue = new SharedQueue(1, 1);
		TiffRawVolumeStore small = new TiffRawVolumeStore(TiffRawVolumeStore.minimumBudget(cell, 1) + 2 * cell, cell, 1, queue);
		TiffRawVolumeStore ample = store();
		try {
			RawSourceSpec spec = RawSourceSpec.half(null, 1, true, false, W, 0);
			RawPartSource<VolatileUnsignedShortType> volatileSource = new RawPartSource<VolatileUnsignedShortType>(a, times, spec,
					GEOMETRY, small::volatileView, new VolatileUnsignedShortType(), e.layout().dimensions());
			RawPartSource<UnsignedShortType> reference = new RawPartSource<UnsignedShortType>(a, times, spec, GEOMETRY,
					ample::blocking, new UnsignedShortType(), e.layout().dimensions());
			for (Interpolation method : Interpolation.values()) {
				double[] at = { 5.3, 7.6, 4.2 }, on = { 5.7, 7.6, 4.2 };
				RealRandomAccess<VolatileUnsignedShortType> held = null;
				long deadline = System.currentTimeMillis() + 5000;
				while (System.currentTimeMillis() < deadline) {
					held = volatileSource.getInterpolatedSource(0, 0, method).realRandomAccess();
					held.setPosition(at);
					if (held.get().isValid()) break;
					Thread.sleep(5);
				}
				assertTrue(method + " loaded", held.get().isValid());
				RealRandomAccess<UnsignedShortType> ref = reference.getInterpolatedSource(0, 0, method).realRandomAccess();
				ref.setPosition(at);
				assertEquals(method + " before eviction", ref.get().get(), held.get().get().get());
				queue.prepareNextFrame();
				RandomAccessibleInterval<UnsignedShortType> raw = small.blocking(e.key(), e.layout());
				for (int k = 0; k < 9; k++)
					for (int y = 0; y < 17; y += 8) read(raw, W - 1, y, k);		// the right half's columns: evicts the rest
				assertTrue(small.stats().evictions > 0);
				// a sample whose neighbours lie in another cell looks that cell up again: it may be
				// invalid while the cell is read anew, but whenever it says valid, it is the value
				assertValidMeansReference(method + " after eviction", held, ref.get().get());
				held.setPosition(on);
				ref.setPosition(on);
				assertValidMeansReference(method + " a little further on", held, ref.get().get());
			}
		} finally {
			queue.shutdown();
			small.closeAsync().get(10, TimeUnit.SECONDS);
			ample.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	/** Sample until valid, within 5 s; every valid sample on the way has the reference value. */
	private static void assertValidMeansReference(String what, RealRandomAccess<VolatileUnsignedShortType> sample, int reference)
			throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		for (;;) {
			VolatileUnsignedShortType v = sample.get();
			if (v.isValid()) {
				assertEquals(what, reference, v.get().get());
				return;
			}
			assertTrue(what + ": still not valid after 5 s", System.currentTimeMillis() < deadline);
			Thread.sleep(5);
		}
	}

	/**
	 * A rendered view under a budget that holds one view but not two: BigDataViewer's own renderer
	 * draws view A, then B, then A again, frame by frame, through the volatile source. Every pixel of
	 * every frame is either the reference render's pixel or not drawn yet (black) - never a
	 * fabricated value, which the colour scale would show as grey, since 0 is mapped well above
	 * black - and each view converges to the reference.
	 */
	@Test
	public void aViewRenderedUnderASmallBudgetIsTheReferenceNeverAFabricatedValue() throws Exception {
		final int w = 64, h = 48, depth = 30;
		File dir = folder.newFolder();
		RawTiffFixture.write(new File(dir, "r_Time000001_Channel0001_Frames_1_30.tiff"), w, h, depth, 8, depth,
				(x, y, k) -> 1000 + (x * 7 + y * 13 + k * 29) % 3000);
		RawAcquisition a = RawAcquisition.scan(dir);
		List<RawAcquisition.TimeKey> times = a.times(1, 1);
		RawAcquisition.Entry e = a.entry(times.get(0), 1);
		assertEquals(RawAcquisition.Readiness.COMPLETE, e.validate());
		RawSourceSpec spec = RawSourceSpec.half(null, 1, true, false, w, 0);
		long[] dims = e.layout().dimensions();
		AffineTransform3D[] views = { viewThrough(spec, dims, 0.35), viewThrough(spec, dims, 0.65), viewThrough(spec, dims, 0.35) };

		TiffRawVolumeStore ample = new TiffRawVolumeStore(64L << 20, TiffRawVolumeStore.cellBytes(e.layout()), 1, null);
		int[][] reference = new int[2][];
		int[] cellsPerView = new int[2];
		for (int v = 0; v < 2; v++) {
			long before = ample.stats().loads;
			reference[v] = render(views[v], soc(new RawPartSource<UnsignedShortType>(a, times, spec, GEOMETRY, ample::blocking,
					new UnsignedShortType(), dims), null), null);
			cellsPerView[v] = (int) (ample.stats().loads - before);
			int bright = 0;		// inside the volume; outside it the zero extension draws grey
			for (int p : reference[v]) if ((p & 0xff) > 120) bright++;
			assertTrue("the reference view shows the volume", bright > 500);
		}
		ample.closeAsync().get(10, TimeUnit.SECONDS);

		long cell = TiffRawVolumeStore.cellBytes(e.layout());
		long budget = TiffRawVolumeStore.minimumBudget(cell, 1) + (Math.max(cellsPerView[0], cellsPerView[1]) + 4) * cell;
		SharedQueue queue = new SharedQueue(2, 1);
		TiffRawVolumeStore small = new TiffRawVolumeStore(budget, cell, 1, queue);
		try {
			SourceAndConverter<UnsignedShortType> soc = soc(new RawPartSource<UnsignedShortType>(a, times, spec, GEOMETRY,
					small::blocking, new UnsignedShortType(), dims), new RawPartSource<VolatileUnsignedShortType>(a, times, spec,
							GEOMETRY, small::volatileView, new VolatileUnsignedShortType(), dims));
			int frames = 0;
			for (int v = 0; v < 3; v++) {
				int[] expected = reference[v % 2 == 0 ? 0 : 1];
				int[] last = null;
				for (int frame = 0; frame < 400; frame++, frames++) {
					last = render(views[v], soc, queue);
					for (int p = 0; p < last.length; p++)
						if (last[p] != 0 && last[p] != expected[p])
							fail(String.format("view %d frame %d pixel %d: %08x, the reference has %08x", v, frame, p, last[p], expected[p]));
					if (Arrays.equals(last, expected)) break;
					Thread.sleep(5);
				}
				assertArrayEquals("view " + v + " converged to the reference", expected, last);
			}
			RawVolumeStore.CacheStats s = small.stats();
			System.out.println("small-budget render: " + frames + " frames; " + s);
			assertTrue("the budget held one view, not two: cells were evicted (" + s + ")", s.evictions > 0);
			assertTrue("within the budget: " + s, s.peakTotalBytes <= s.budgetBytes);
		} finally {
			queue.shutdown();
			small.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	/** BigDataViewer's renderer, two threads, one screen scale; see {@link BdvTestRenderer}. */
	private static MultiResolutionRenderer renderer(RenderTarget<?> target, boolean useVolatile, CacheControl cache) {
		return BdvTestRenderer.renderer(target, useVolatile, cache);
	}

	/** Looking down the deskewed Z at a fraction of the part's depth, the part filling 96 x 96 screen pixels. */
	private static AffineTransform3D viewThrough(RawSourceSpec spec, long[] dims, double depthFraction) {
		AffineTransform3D toWorld = GEOMETRY.sourceToWorld(dims, spec, null);
		double[] min = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
		double[] max = { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
		double[] corner = new double[3], world = new double[3];
		for (int c = 0; c < 8; c++) {
			corner[0] = (c & 1) == 0 ? 0 : spec.cropWidth(dims[0]) - 1;
			corner[1] = (c & 2) == 0 ? 0 : dims[1] - 1;
			corner[2] = (c & 4) == 0 ? 0 : dims[2] - 1;
			toWorld.apply(corner, world);
			for (int d = 0; d < 3; d++) {
				min[d] = Math.min(min[d], world[d]);
				max[d] = Math.max(max[d], world[d]);
			}
		}
		AffineTransform3D view = new AffineTransform3D();
		view.translate(-0.5 * (min[0] + max[0]), -0.5 * (min[1] + max[1]), -(min[2] + depthFraction * (max[2] - min[2])));
		view.scale(0.95 * 96 / Math.max(max[0] - min[0], max[1] - min[1]));
		view.translate(48, 48, 0);
		return view;
	}

	/** A source and its converter, the volatile twin if given; 0 is mapped to grey, not black. */
	private static SourceAndConverter<UnsignedShortType> soc(RawPartSource<UnsignedShortType> blocking,
			RawPartSource<VolatileUnsignedShortType> volatileSource) {
		RealARGBColorConverter<UnsignedShortType> c = RealARGBColorConverter.create(new UnsignedShortType(), -2000, 4000);
		c.setColor(new ARGBType(0xffffffff));
		if (volatileSource == null) return new SourceAndConverter<UnsignedShortType>(blocking, c);
		RealARGBColorConverter<VolatileUnsignedShortType> vc = RealARGBColorConverter.create(new VolatileUnsignedShortType(), -2000, 4000);
		vc.setColor(new ARGBType(0xffffffff));
		return new SourceAndConverter<UnsignedShortType>(blocking, c,
				new SourceAndConverter<VolatileUnsignedShortType>(volatileSource, vc));
	}

	/**
	 * One frame of BigDataViewer's own renderer, trilinear, 96 x 96; through the volatile twin when
	 * a cache control is given. Test code only: the renderer's constructor differs between releases.
	 */
	private static int[] render(AffineTransform3D view, SourceAndConverter<UnsignedShortType> soc, CacheControl cache) {
		BasicViewerState state = new BasicViewerState();
		state.addSource(soc);
		state.setSourceActive(soc, true);
		state.setDisplayMode(DisplayMode.SINGLE);
		state.setCurrentSource(soc);
		state.setNumTimepoints(1);
		state.setInterpolation(Interpolation.NLINEAR);
		state.setViewerTransform(view);
		final BufferedImageRenderResult result = new BufferedImageRenderResult();
		RenderTarget<BufferedImageRenderResult> target = new RenderTarget<BufferedImageRenderResult>() {
			@Override public BufferedImageRenderResult getReusableRenderResult() { return result; }
			@Override public BufferedImageRenderResult createRenderResult() { return new BufferedImageRenderResult(); }
			@Override public void setRenderResult(BufferedImageRenderResult r) {}
			@Override public int getWidth() { return 96; }
			@Override public int getHeight() { return 96; }
		};
		MultiResolutionRenderer renderer = renderer(target, cache != null, cache != null ? cache : new CacheControl.Dummy());
		try {
			renderer.requestRepaint();
			renderer.paint(state);
			int[] rgb = result.getBufferedImage().getRGB(0, 0, 96, 96, null, 0, 96);
			for (int p = 0; p < rgb.length; p++) rgb[p] &= 0xffffff;		// a pixel not drawn is 0; alpha says nothing more
			return rgb;
		} finally {
			renderer.kill();
		}
	}

	@Test
	public void bigDataViewersRendererDrawsBothHalves() throws Exception {
		RawTiffFixture.Pixels bright = (x, y, k) -> 1000;
		RawAcquisition a = acquisition(new int[] { 17 }, new RawTiffFixture.Pixels[] { bright }, new int[] { 9 });
		TiffRawVolumeStore store = store();
		try {
			BasicViewerState state = new BasicViewerState();
			for (boolean left : new boolean[] { true, false }) {
				RawPartSource<UnsignedShortType> s = source(a, store, left);
				RealARGBColorConverter<UnsignedShortType> c = RealARGBColorConverter.create(new UnsignedShortType(), 0, 1000);
				c.setColor(new ARGBType(0xff000000 | s.spec().rgb));
				SourceAndConverter<UnsignedShortType> soc = new SourceAndConverter<UnsignedShortType>(s, c);
				state.addSource(soc);
				state.setSourceActive(soc, true);
			}
			state.setDisplayMode(DisplayMode.FUSED);
			state.setNumTimepoints(1);
			state.setInterpolation(Interpolation.NEARESTNEIGHBOR);
			// look down the deskewed Z through the middle of the volume, 40 screen pixels per micrometre
			long[] grid = GEOMETRY.deskewedGrid(W / 2, 17, 9);
			AffineTransform3D view = new AffineTransform3D();
			view.translate(0, 0, -0.5 * grid[2] * GEOMETRY.dxyUm);
			view.scale(40);
			state.setViewerTransform(view);
			final BufferedImageRenderResult result = new BufferedImageRenderResult();
			RenderTarget<BufferedImageRenderResult> target = new RenderTarget<BufferedImageRenderResult>() {
				@Override public BufferedImageRenderResult getReusableRenderResult() { return result; }
				@Override public BufferedImageRenderResult createRenderResult() { return new BufferedImageRenderResult(); }
				@Override public void setRenderResult(BufferedImageRenderResult r) {}
				@Override public int getWidth() { return 120; }
				@Override public int getHeight() { return 120; }
			};
			MultiResolutionRenderer renderer = renderer(target, false, new CacheControl.Dummy());
			renderer.requestRepaint();
			renderer.paint(state);
			BufferedImage image = result.getBufferedImage();
			boolean green = false, magenta = false;
			for (int y = 0; y < image.getHeight(); y++)
				for (int x = 0; x < image.getWidth(); x++) {
					int rgb = image.getRGB(x, y);
					int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
					green |= g > 200;
					magenta |= r > 200 && b > 200;
				}
			assertTrue("the left half is drawn", green);
			assertTrue("the mirrored right half is drawn", magenta);
			renderer.kill();
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}
}
