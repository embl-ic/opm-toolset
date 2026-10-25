package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.viewer.BasicViewerState;
import bdv.viewer.DisplayMode;
import bdv.viewer.Interpolation;
import bdv.viewer.SourceAndConverter;
import net.imglib2.display.RealARGBColorConverter;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedShortType;

/**
 * Completed-frame playback without a window (joint decision D2; contracts C4.2, C6.2, C6.3): the
 * player, the store's leases and reservations, and the presenter, whose {@code drawOverlays} is
 * called here as the canvas would call it - which is what makes each paint, and so each
 * acknowledgement, the test's to decide.
 *
 * <ul>
 * <li>A staged frame is pixel for pixel what BigDataViewer's own renderer draws.</li>
 * <li>A frame is acknowledged only by the paint that draws it: not when it is prepared, drawn,
 *     handed over, or painted again.</li>
 * <li>Consecutive time points under one view are separate frames; the frame shown stays, with its
 *     own label and count, until its replacement is painted.</li>
 * <li>The newest request wins, and every frame presented is its own request's image, under
 *     delayed reads and rapid changes of time, view and display range.</li>
 * <li>A file changed while its frame is prepared or drawn is not shown; a shown frame whose file
 *     changes is taken off.</li>
 * <li>A frame the budget cannot hold is refused before anything is read.</li>
 * <li>The ledger holds no pins between frames and exactly one staged image, the one on screen.</li>
 * </ul>
 */
public class RawStrictPlayerTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final int W = 64, H = 40, D = 20, ROWS = 8;
	private static final int SCREEN_W = 96, SCREEN_H = 120;
	/** Rows above the time label and the pending line, which are compared with the reference. */
	private static final int IMAGE_ROWS = SCREEN_H - 44;
	private static final RawGeometry GEOMETRY = new RawGeometry(0.265, 0.116, 33.5, "test");
	private static final RawSourceSpec[] SPECS = { RawSourceSpec.half(null, 1, true, false, W, 0x00ff00),
			RawSourceSpec.half(null, 1, false, false, W, 0xff00ff) };

	private RawAcquisition acquisition;
	private List<RawAcquisition.TimeKey> times;
	private long cell;
	private TiffRawVolumeStore reference;
	private RawFrameRenderer referenceRenderer;
	private final List<AutoCloseable> closing = new ArrayList<AutoCloseable>();

	@Before
	public void writeThreeTimePoints() throws Exception {
		for (int t = 0; t < 3; t++) {
			final int base = 600 + 900 * t;
			RawTiffFixture.write(new File(folder.getRoot(), String.format("s_Time%06d_Channel0001_Frames_1_%d.tiff", t + 1, D)), W, H,
					D, ROWS, D, (x, y, k) -> base + (x * 7 + y * 13 + k * 29) % 500);
		}
		acquisition = RawAcquisition.scan(folder.getRoot());
		times = acquisition.times(1, 1);
		assertEquals(3, times.size());
		for (int t = 0; t < 3; t++) assertEquals(RawAcquisition.Readiness.COMPLETE, entry(t).validate());
		cell = TiffRawVolumeStore.cellBytes(entry(0).layout());
		reference = new TiffRawVolumeStore(64L << 20, cell, 2, null);
		referenceRenderer = new RawFrameRenderer(2);
	}

	@After
	public void closeEverything() throws Exception {
		RawStrictPlayer.beforeCheckForTest = null;
		Collections.reverse(closing);
		for (AutoCloseable c : closing) c.close();
		referenceRenderer.close();
		reference.closeAsync().get(10, TimeUnit.SECONDS);
	}

	private RawAcquisition.Entry entry(int t) {
		return acquisition.entry(times.get(t), 1);
	}

	/* ---------------------------------------------------------------- fixture */

	/** Down the deskewed Z at a fraction of the depth, both halves filling the upper part of the screen; turned by {@code angle}. */
	private static AffineTransform3D view(long[] dims, double depthFraction, double angle, int screenW, int screenH) {
		double[] min = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
		double[] max = { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
		double[] corner = new double[3], world = new double[3];
		for (RawSourceSpec spec : SPECS) {
			AffineTransform3D toWorld = GEOMETRY.sourceToWorld(dims, spec, null);
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
		}
		int imageH = screenH > 88 ? screenH - 44 : screenH;		// above the label, on a screen tall enough for one
		AffineTransform3D view = new AffineTransform3D();
		view.translate(-0.5 * (min[0] + max[0]), -0.5 * (min[1] + max[1]), -(min[2] + depthFraction * (max[2] - min[2])));
		view.rotate(2, angle);
		view.scale(0.9 * Math.min(screenW, imageH) / Math.max(max[0] - min[0], max[1] - min[1]));
		view.translate(0.5 * screenW, 0.5 * imageH, 0);
		return view;
	}

	private static AffineTransform3D view(long[] dims, double depthFraction, double angle) {
		return view(dims, depthFraction, angle, SCREEN_W, SCREEN_H);
	}

	/** Display range -1000 to {@code max}: 0 maps to grey, so a fabricated zero would show. */
	private RawFrameRequest request(long revision, int t, AffineTransform3D view, Interpolation method, double max) {
		return request(revision, t, view, method, -1000, max, SCREEN_W, SCREEN_H);
	}

	private RawFrameRequest request(long revision, int t, AffineTransform3D view, Interpolation method, double max, int w, int h) {
		return request(revision, t, view, method, -1000, max, w, h);
	}

	private RawFrameRequest request(long revision, int t, AffineTransform3D view, Interpolation method, double min, double max, int w,
			int h) {
		RawAcquisition.Entry e = entry(t);
		List<RawFrameRequest.Layer> layers = new ArrayList<RawFrameRequest.Layer>();
		for (RawSourceSpec spec : SPECS)
			layers.add(new RawFrameRequest.Layer(spec, GEOMETRY.sourceToWorld(e.layout().dimensions(), spec, null), min, max,
					0xff000000 | spec.rgb));
		return new RawFrameRequest(revision, t, e, view, w, h, method, layers);
	}

	/** The request's frame as an image, drawn from a store with room for everything: its rows above the label. */
	private int[] referenceImage(RawFrameRequest r) throws Exception {
		BufferedImage image = referenceRenderer.render(r, reference::blocking, () -> false);
		return image.getRGB(0, 0, r.width, IMAGE_ROWS, null, 0, r.width);
	}

	/** The request's frame as the presenter paints it, over black: its rows above the label. */
	private int[] referenceOf(RawFrameRequest r) throws Exception {
		BufferedImage image = referenceRenderer.render(r, reference::blocking, () -> false);
		BufferedImage screen = new BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = screen.createGraphics();
		g.setColor(java.awt.Color.BLACK);
		g.fillRect(0, 0, r.width, r.height);
		g.drawImage(image, 0, 0, null);
		g.dispose();
		return screen.getRGB(0, 0, r.width, IMAGE_ROWS, null, 0, r.width);
	}

	private static int[] imageRows(BufferedImage screen) {
		return screen.getRGB(0, 0, screen.getWidth(), IMAGE_ROWS, null, 0, screen.getWidth());
	}

	/** A player over its own store, with a presenter on a canvas that is never shown. */
	private final class Rig {
		final TiffRawVolumeStore store;
		final RawPresenter presenter;
		final RawFrameRenderer renderer = new RawFrameRenderer(2);
		final RawStrictPlayer player;

		Rig(long budget, int ioThreads) {
			store = new TiffRawVolumeStore(budget, cell, ioThreads, null);
			JPanel canvas = new JPanel();
			canvas.setSize(SCREEN_W, SCREEN_H);
			presenter = new RawPresenter(canvas);
			player = new RawStrictPlayer(store, renderer, presenter);
			closing.add(() -> store.closeAsync().get(10, TimeUnit.SECONDS));
			closing.add(renderer);
			closing.add(player);
		}

		/** One paint of the canvas, on the event thread, as Swing would do it. */
		BufferedImage paint() throws Exception {
			final BufferedImage screen = new BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_ARGB);
			SwingUtilities.invokeAndWait(() -> {
				Graphics2D g = screen.createGraphics();
				presenter.drawOverlays(g);
				g.dispose();
			});
			return screen;
		}

		void awaitHandedOver() throws InterruptedException {
			long deadline = System.currentTimeMillis() + 10000;
			while (!presenter.waiting() && System.currentTimeMillis() < deadline) Thread.sleep(2);
			assertTrue("a frame was handed over", presenter.waiting());
		}

		/** Request, wait for the hand-over, paint once: the presented result. */
		RawStrictPlayer.Result present(RawFrameRequest r) throws Exception {
			CompletableFuture<RawStrictPlayer.Result> f = player.request(r);
			awaitHandedOver();
			paint();
			RawStrictPlayer.Result result = f.get(10, TimeUnit.SECONDS);
			assertEquals(result.toString(), RawStrictPlayer.Outcome.PRESENTED, result.outcome);
			return result;
		}

		<T> T onEventThread(java.util.concurrent.Callable<T> read) throws Exception {
			final Object[] value = new Object[1];
			final Exception[] failure = new Exception[1];
			SwingUtilities.invokeAndWait(() -> {
				try {
					value[0] = read.call();
				} catch (Exception e) {
					failure[0] = e;
				}
			});
			if (failure[0] != null) throw failure[0];
			@SuppressWarnings("unchecked") T t = (T) value[0];
			return t;
		}

		RawVolumeStore.CacheStats stats() {
			return store.stats();
		}
	}

	private static void touch(RawAcquisition.Entry e) {
		try {
			Files.setLastModifiedTime(e.file.toPath(), FileTime.fromMillis(e.key().modified + 5000));
		} catch (java.io.IOException x) {
			throw new IllegalStateException(x);
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private long frameBytes() {
		return 4L * SCREEN_W * SCREEN_H;
	}

	/* ---------------------------------------------------------------- tests */

	/** BigDataViewer's own renderer over the same parts, range and view as the request. */
	private int[] bigDataViewersFrame(RawFrameRequest r) {
		long[] dims = r.layout.dimensions();
		BasicViewerState state = new BasicViewerState();
		for (RawFrameRequest.Layer l : r.layers) {
			RawPartSource<UnsignedShortType> source = new RawPartSource<UnsignedShortType>(acquisition, times, l.spec, GEOMETRY,
					reference::blocking, new UnsignedShortType(), dims);
			RealARGBColorConverter<UnsignedShortType> c = RealARGBColorConverter.create(new UnsignedShortType(), l.min, l.max);
			c.setColor(new ARGBType(l.argb));
			SourceAndConverter<UnsignedShortType> soc = new SourceAndConverter<UnsignedShortType>(source, c);
			state.addSource(soc);
			state.setSourceActive(soc, true);
		}
		state.setDisplayMode(DisplayMode.FUSED);
		state.setNumTimepoints(times.size());
		state.setCurrentTimepoint(r.timeIndex);
		state.setInterpolation(r.interpolation);
		state.setViewerTransform(r.viewer);
		return BdvTestRenderer.paint(state, r.width, r.height).getRGB(0, 0, r.width, r.height, null, 0, r.width);
	}

	/**
	 * Why a staged pixel may differ from BigDataViewer's, or null when nothing does: the two walk a
	 * screen row from different starting columns - BigDataViewer from its tile's edge - so a sample
	 * position is the same number reached by different floating-point steps. A nearest-neighbour
	 * sample exactly half way between two voxels may then round either way, and a trilinear value
	 * exactly half way between two integers likewise, by one grey level.
	 */
	private static String tie(RawFrameRequest r, int x, int y, int bdv, int staged) {
		if (r.interpolation == Interpolation.NLINEAR) {
			for (int shift = 0; shift <= 16; shift += 8)
				if (Math.abs(((bdv >> shift) & 0xff) - ((staged >> shift) & 0xff)) > 1) return null;
			return "trilinear rounding";
		}
		double[] p = new double[3];
		for (RawFrameRequest.Layer l : r.layers) {
			r.sourceToScreen(l).inverse().apply(new double[] { x, y, 0 }, p);
			for (double c : p)
				if (Math.abs(c - Math.floor(c) - 0.5) < 1e-6) return "nearest-neighbour tie";
		}
		return null;
	}

	/**
	 * The colour of every pixel is BigDataViewer's, for both interpolations, a straight and a turned
	 * view, two time points, and display minimums of 0 and 300 - but for samples exactly half way
	 * between two values ({@link #tie}), which the view through the middle of the volume has on
	 * purpose, and which are counted. Alpha is not compared: BigDataViewer leaves it 0 where it
	 * draws nothing, as the staged frame does, and both are painted over black.
	 */
	@Test
	public void aStagedFrameIsWhatBigDataViewerDraws() throws Exception {
		long[] dims = entry(0).layout().dimensions();
		int compared = 0, pixels = 0, ties = 0;
		for (Interpolation method : Interpolation.values())
			for (double angle : new double[] { 0, 0.37 })
				for (int t = 0; t < 2; t++)
					for (double min : new double[] { 0, 300 }) {
						RawFrameRequest r = request(1, t, view(dims, 0.45, angle), method, min, 2500, SCREEN_W, SCREEN_H);
						int[] bdv = bigDataViewersFrame(r);
						int[] staged = referenceRenderer.render(r, reference::blocking, () -> false).getRGB(0, 0, SCREEN_W, SCREEN_H, null, 0,
								SCREEN_W);
						Set<Integer> colours = new HashSet<Integer>();
						for (int p = 0; p < staged.length; p++, pixels++) {
							int x = p % SCREEN_W, y = p / SCREEN_W, b = bdv[p] & 0xffffff, s = staged[p] & 0xffffff;
							colours.add(s);
							if (b == s) continue;
							if (tie(r, x, y, b, s) == null)
								fail(String.format("%s, angle %.2f, t %d, minimum %.0f, pixel (%d, %d): BigDataViewer %06x, staged %06x", method,
										angle, t, min, x, y, b, s));
							ties++;
						}
						assertTrue("the frame shows structure, not a flat field: " + colours.size(), colours.size() > 100);
						compared++;
					}
		System.out.println("staged against BigDataViewer: " + compared + " frames, " + pixels + " pixels, " + ties
				+ " differ, each a nearest-neighbour sample half way between two voxels or a trilinear colour one level apart");
		assertEquals(16, compared);
		assertTrue("ties are rare: " + ties, ties * 200 < pixels);
	}

	/**
	 * With a negative display minimum the zero outside a part has a colour, and BigDataViewer draws
	 * a part by tiles of up to 64 pixels around its box: a pixel may then differ, but only one
	 * outside the box of some part, where BigDataViewer coloured that part's zero and the staged
	 * frame drew nothing of it. Inside every box, every pixel is BigDataViewer's, ties apart; and
	 * where BigDataViewer drew nothing at all, the staged frame draws nothing either - which is what
	 * culling to the boxes shows, since the zero would be drawn grey.
	 */
	@Test
	public void underANegativeMinimumAStagedFrameDiffersOnlyOutsideAPartsBox() throws Exception {
		long[] dims = entry(0).layout().dimensions();
		int outsideABox = 0, tiesInside = 0, inside = 0, empty = 0;
		for (Interpolation method : Interpolation.values())
			for (double angle : new double[] { 0, 0.37 }) {
				RawFrameRequest r = request(1, 0, view(dims, 0.45, angle), method, -1000, 2500, SCREEN_W, SCREEN_H);
				int[] bdv = bigDataViewersFrame(r);
				int[] staged = referenceRenderer.render(r, reference::blocking, () -> false).getRGB(0, 0, SCREEN_W, SCREEN_H, null, 0,
						SCREEN_W);
				List<int[]> boxes = new ArrayList<int[]>();
				for (RawFrameRequest.Layer l : r.layers) boxes.add(RawFrameRenderer.onScreenBounds(r, l));
				for (int p = 0; p < staged.length; p++) {
					int x = p % SCREEN_W, y = p / SCREEN_W;
					boolean inEveryBox = true;
					for (int[] b : boxes) inEveryBox &= b != null && x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3];
					if (inEveryBox) inside++;
					if ((bdv[p] & 0xffffff) == 0) {		// nothing drawn: no value of the fixture maps to black here
						empty++;
						if ((staged[p] & 0xffffff) != 0)
							fail(String.format("%s, angle %.2f, pixel (%d, %d): BigDataViewer drew nothing, the staged frame %06x", method,
									angle, x, y, staged[p] & 0xffffff));
					}
					if ((bdv[p] & 0xffffff) == (staged[p] & 0xffffff)) continue;
					if (inEveryBox) tiesInside++;
					else outsideABox++;
					if (inEveryBox && tie(r, x, y, bdv[p] & 0xffffff, staged[p] & 0xffffff) == null)
						fail(String.format("%s, angle %.2f, pixel (%d, %d) inside every part's box: BigDataViewer %06x, staged %06x", method,
								angle, x, y, bdv[p] & 0xffffff, staged[p] & 0xffffff));
				}
			}
		System.out.println("negative minimum: " + outsideABox + " pixels differ outside a part's box, " + tiesInside + " at ties of the "
				+ inside + " inside every box; " + empty + " drawn by neither");
		assertTrue(inside > 1000);
		assertTrue("the view leaves part of the screen empty", empty > 1000);
	}

	@Test
	public void aFrameIsPresentedByThePaintThatDrawsItAndByNothingBefore() throws Exception {
		Rig rig = new Rig(64L << 20, 2);
		rig.store.setReadHook(cell -> sleep(5));		// delayed loads
		RawFrameRequest r = request(1, 0, view(entry(0).layout().dimensions(), 0.5, 0), Interpolation.NLINEAR, 2500);
		CompletableFuture<RawStrictPlayer.Result> f = rig.player.request(r);
		rig.awaitHandedOver();
		Thread.sleep(200);
		assertFalse("prepared, drawn and handed over is not presented", f.isDone());
		RawVolumeStore.CacheStats waiting = rig.stats();
		assertEquals("the lease is released once the image is complete: " + waiting, 0, waiting.pinnedBytes);
		assertTrue("the lease pinned the frame's cells while it was prepared: " + waiting, waiting.peakPinnedBytes > 0);
		assertEquals("the image waiting to be painted is charged: " + waiting, frameBytes(), waiting.stagedBytes);

		BufferedImage screen = rig.paint();
		RawStrictPlayer.Result result = f.get(10, TimeUnit.SECONDS);
		System.out.println("delayed loads: " + result);
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, result.outcome);
		assertSame("the acknowledgement names its own request", r, result.presented.request);
		assertEquals(1, result.presented.delivered);
		assertEquals("the coverage held every cell the frame read", 0, result.readsWhileDrawing);
		assertTrue(result.leaseCells > 0);
		assertArrayEquals("the canvas shows the request's frame", referenceOf(r), imageRows(screen));
		assertArrayEquals("the acknowledged image is the one painted", referenceImage(r),
				result.presented.image.getRGB(0, 0, SCREEN_W, IMAGE_ROWS, null, 0, SCREEN_W));
		assertTrue(rig.onEventThread(() -> rig.presenter.label()).startsWith("t = 1 " + entry(0).time + " frame 1"));

		rig.paint();
		rig.paint();
		assertEquals("painting the same frame again acknowledges nothing", 1L, (long) rig.onEventThread(() -> rig.presenter.delivered()));
		RawVolumeStore.CacheStats shown = rig.stats();
		assertEquals("one staged image, the one on screen: " + shown, frameBytes(), shown.stagedBytes);
		assertTrue("within the budget: " + shown, shown.peakTotalBytes <= shown.budgetBytes);
	}

	@Test
	public void consecutiveTimesUnderOneViewAreSeparateFramesAndTheShownOneStaysUntilItsReplacementIsPainted() throws Exception {
		Rig rig = new Rig(64L << 20, 2);
		AffineTransform3D view = view(entry(0).layout().dimensions(), 0.5, 0);
		RawFrameRequest first = request(1, 0, view, Interpolation.NLINEAR, 2500);
		RawFrameRequest second = request(2, 1, view, Interpolation.NLINEAR, 2500);
		assertFalse("the two time points look different", java.util.Arrays.equals(referenceOf(first), referenceOf(second)));
		rig.present(first);

		CompletableFuture<RawStrictPlayer.Result> f = rig.player.request(second);
		rig.awaitHandedOver();
		assertSame("until the next paint the first frame is shown", first, rig.onEventThread(() -> rig.presenter.shownRequest()));
		assertTrue(rig.onEventThread(() -> rig.presenter.label()).startsWith("t = 1 " + entry(0).time + " frame 1"));
		assertEquals("both images are held while the second waits", 2 * frameBytes(), rig.stats().stagedBytes);
		assertFalse(f.isDone());

		BufferedImage screen = rig.paint();
		RawStrictPlayer.Result result = f.get(10, TimeUnit.SECONDS);
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, result.outcome);
		assertSame(second, result.presented.request);
		assertEquals(2, result.presented.delivered);
		assertArrayEquals(referenceOf(second), imageRows(screen));
		assertTrue(rig.onEventThread(() -> rig.presenter.label()).startsWith("t = 2 " + entry(1).time + " frame 2"));
		assertEquals("the replaced image is released", frameBytes(), rig.stats().stagedBytes);
	}

	@Test
	public void aFrameHandedOverButNotPaintedIsSupersededAndNeverCounted() throws Exception {
		Rig rig = new Rig(64L << 20, 2);
		AffineTransform3D view = view(entry(0).layout().dimensions(), 0.5, 0);
		RawFrameRequest older = request(1, 0, view, Interpolation.NLINEAR, 2500);
		RawFrameRequest newer = request(2, 1, view, Interpolation.NLINEAR, 2500);
		CompletableFuture<RawStrictPlayer.Result> first = rig.player.request(older);
		rig.awaitHandedOver();
		CompletableFuture<RawStrictPlayer.Result> second = rig.player.request(newer);
		RawStrictPlayer.Result superseded = first.get(10, TimeUnit.SECONDS);		// at the newer one's hand-over
		assertEquals(RawStrictPlayer.Outcome.SUPERSEDED, superseded.outcome);
		assertNull(superseded.presented);
		rig.awaitHandedOver();
		BufferedImage screen = rig.paint();
		RawStrictPlayer.Result shown = second.get(10, TimeUnit.SECONDS);
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, shown.outcome);
		assertEquals("the superseded frame was never on screen", 1, shown.presented.delivered);
		assertArrayEquals(referenceOf(newer), imageRows(screen));
		assertEquals(frameBytes(), rig.stats().stagedBytes);
	}

	@Test
	public void rapidRequestsEndWithTheNewestAndEveryPresentedFrameIsItsOwnRequest() throws Exception {
		final Rig rig = new Rig(64L << 20, 2);
		rig.store.setReadHook(cell -> sleep(1));
		long[] dims = entry(0).layout().dimensions();
		AffineTransform3D[] views = { view(dims, 0.5, 0), view(dims, 0.4, 0.3), view(dims, 0.6, -0.2) };
		double[] ranges = { 2500, 4000 };
		// the canvas paints on its own, every few milliseconds, recording what each paint acknowledged
		final AtomicBoolean painting = new AtomicBoolean(true);
		Thread painter = new Thread(() -> {
			while (painting.get()) {
				try {
					rig.paint();
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
				sleep(3);
			}
		}, "test-painter");
		painter.setDaemon(true);
		painter.start();

		final List<RawFrameRequest> requests = new ArrayList<RawFrameRequest>();
		final List<CompletableFuture<RawStrictPlayer.Result>> results = new ArrayList<CompletableFuture<RawStrictPlayer.Result>>();
		Random random = new Random(7);
		for (int i = 0; i < 40; i++) {
			RawFrameRequest r = request(i + 1, i % 3, views[random.nextInt(views.length)],
					random.nextBoolean() ? Interpolation.NLINEAR : Interpolation.NEARESTNEIGHBOR, ranges[random.nextInt(2)]);
			requests.add(r);
			results.add(rig.player.request(r));
			Thread.sleep(random.nextInt(25));
		}
		for (CompletableFuture<RawStrictPlayer.Result> f : results) f.get(30, TimeUnit.SECONDS);
		painting.set(false);
		painter.join(5000);

		RawStrictPlayer.Result last = results.get(results.size() - 1).get();
		assertEquals("the newest request is shown: " + last, RawStrictPlayer.Outcome.PRESENTED, last.outcome);
		List<RawStrictPlayer.Result> presented = new ArrayList<RawStrictPlayer.Result>();
		for (CompletableFuture<RawStrictPlayer.Result> f : results)
			if (f.get().outcome == RawStrictPlayer.Outcome.PRESENTED) presented.add(f.get());
		long lastDelivered = 0;
		for (RawStrictPlayer.Result result : presented) {		// in request order
			assertSame("the acknowledgement names its own request", result.request, result.presented.request);
			assertEquals("revisions are presented in order, one count each", lastDelivered + 1, result.presented.delivered);
			assertEquals(0, result.readsWhileDrawing);
			assertArrayEquals("frame " + result.request.revision + " painted its own request's image", referenceImage(result.request),
					result.presented.image.getRGB(0, 0, SCREEN_W, IMAGE_ROWS, null, 0, SCREEN_W));
			lastDelivered = result.presented.delivered;
		}
		int superseded = 0;
		for (CompletableFuture<RawStrictPlayer.Result> f : results) {
			RawStrictPlayer.Outcome o = f.get().outcome;
			assertTrue(o.toString(), o == RawStrictPlayer.Outcome.PRESENTED || o == RawStrictPlayer.Outcome.SUPERSEDED);
			if (o == RawStrictPlayer.Outcome.SUPERSEDED) superseded++;
		}
		RawVolumeStore.CacheStats s = rig.stats();
		System.out.println("rapid requests: " + presented.size() + " presented, " + superseded + " superseded; " + rig.player.summary()
				+ "; " + s);
		assertTrue("some frames were presented along the way", presented.size() >= 2);
		assertTrue("some frames were superseded", superseded > 0);
		assertEquals(0, s.pinnedBytes);
		assertEquals("one staged image, the one on screen", frameBytes(), s.stagedBytes);
		assertTrue(s.peakTotalBytes <= s.budgetBytes);
	}

	@Test
	public void aFileChangedWhileItsFrameIsPreparedIsNotShownAndTheShownFrameStays() throws Exception {
		final Rig rig = new Rig(64L << 20, 2);
		AffineTransform3D view = view(entry(0).layout().dimensions(), 0.5, 0);
		RawFrameRequest first = request(1, 0, view, Interpolation.NLINEAR, 2500);
		rig.present(first);

		final RawAcquisition.Entry changing = entry(1);
		final AtomicBoolean touched = new AtomicBoolean();
		rig.store.setReadHook(cell -> {
			if (cell.startsWith(changing.key().toString()) && touched.compareAndSet(false, true)) touch(changing);
		});
		RawStrictPlayer.Result result = rig.player.request(request(2, 1, view, Interpolation.NLINEAR, 2500)).get(10, TimeUnit.SECONDS);
		assertTrue("the file was changed under the preparation", touched.get());
		assertEquals(result.toString(), RawStrictPlayer.Outcome.CHANGED, result.outcome);
		assertTrue(rig.store.isStale(changing.key()));
		BufferedImage screen = rig.paint();
		assertSame(first, rig.onEventThread(() -> rig.presenter.shownRequest()));
		assertArrayEquals("the shown frame stays", referenceOf(first), imageRows(screen));
		assertEquals(1L, (long) rig.onEventThread(() -> rig.presenter.delivered()));
		assertTrue(rig.presenter.pendingNote(), rig.presenter.pendingNote().contains("not shown: the file changed"));
		RawVolumeStore.CacheStats s = rig.stats();
		assertEquals("a failed preparation keeps no pin: " + s, 0, s.pinnedBytes);
		assertEquals(frameBytes(), s.stagedBytes);
	}

	@Test
	public void aFileChangedAfterItsFrameIsDrawnIsNotShown() throws Exception {
		Rig rig = new Rig(64L << 20, 2);
		AffineTransform3D view = view(entry(0).layout().dimensions(), 0.5, 0);
		RawFrameRequest first = request(1, 0, view, Interpolation.NLINEAR, 2500);
		rig.present(first);
		RawStrictPlayer.beforeCheckForTest = r -> {
			if (r.timeIndex == 1) touch(r.entry);
		};
		RawStrictPlayer.Result result = rig.player.request(request(2, 1, view, Interpolation.NLINEAR, 2500)).get(10, TimeUnit.SECONDS);
		assertEquals(result.toString(), RawStrictPlayer.Outcome.CHANGED, result.outcome);
		assertFalse("never handed over", rig.presenter.waiting());
		assertArrayEquals(referenceOf(first), imageRows(rig.paint()));
		RawVolumeStore.CacheStats s = rig.stats();
		assertEquals("its image is released: " + s, frameBytes(), s.stagedBytes);
		assertEquals(0, s.pinnedBytes);
	}

	@Test
	public void aShownFrameWhoseFileChangesIsTakenOffTheCanvas() throws Exception {
		final Rig rig = new Rig(64L << 20, 2);
		rig.store.setStaleListener((key, why) -> rig.player.withdraw(key));
		AffineTransform3D view = view(entry(0).layout().dimensions(), 0.5, 0);
		RawFrameRequest first = request(1, 0, view, Interpolation.NLINEAR, 2500);
		rig.present(first);
		touch(entry(0));
		assertFalse(rig.store.checkGeneration(entry(0).key()));
		SwingUtilities.invokeAndWait(() -> {});
		SwingUtilities.invokeAndWait(() -> {});
		assertNull(rig.onEventThread(() -> rig.presenter.shownImage()));
		assertSame(first, rig.onEventThread(() -> rig.presenter.withdrawnRequest()));
		assertTrue(rig.onEventThread(() -> rig.presenter.label()).endsWith("withdrawn"));
		for (int p : imageRows(rig.paint())) assertEquals("nothing of the changed file is drawn", 0xff000000, p);

		RawFrameRequest next = request(2, 1, view, Interpolation.NLINEAR, 2500);
		BufferedImage screen;
		RawStrictPlayer.Result result = rig.present(next);
		screen = rig.paint();
		assertEquals(2, result.presented.delivered);
		assertNull(rig.onEventThread(() -> rig.presenter.withdrawnRequest()));
		assertArrayEquals(referenceOf(next), imageRows(screen));
	}

	@Test
	public void aFrameTheBudgetCannotHoldIsRefusedBeforeAnythingIsReadAndTheShownFrameStays() throws Exception {
		final int w = 32, h = 32;
		long[] dims = entry(0).layout().dimensions();
		AffineTransform3D close = view(dims, 0.5, 0, w, h);
		AffineTransform3D zoom = new AffineTransform3D();
		zoom.translate(-0.5 * w, -0.5 * h, 0);
		zoom.scale(6);
		zoom.translate(0.5 * w, 0.5 * h, 0);
		close.preConcatenate(zoom);
		AffineTransform3D whole = view(dims, 0.5, 0, w, h);
		RawFrameRequest small = request(1, 0, close, Interpolation.NLINEAR, 2500, w, h);
		RawFrameRequest big = request(2, 1, whole, Interpolation.NLINEAR, 2500, w, h);
		BitSet smallCells = RawCoverage.cells(dims, ROWS, small.coverageParts(), w, h, Interpolation.NLINEAR);
		BitSet bigCells = RawCoverage.cells(dims, ROWS, big.coverageParts(), w, h, Interpolation.NLINEAR);
		long frame = 4L * w * h;
		int io = 1;
		// room for the small frame's cells and two images; not for the big frame's cells beside the shown image
		long budget = io * cell + cell + 2 * frame + (smallCells.cardinality() + bigCells.cardinality()) / 2 * cell;
		assertTrue("the fixture separates the two: " + smallCells.cardinality() + " and " + bigCells.cardinality() + " cells",
				bigCells.cardinality() * cell > budget - io * cell - cell - frame);
		assertTrue(budget >= TiffRawVolumeStore.minimumBudget(cell, io));
		final Rig rig = new Rig(budget, io);
		CompletableFuture<RawStrictPlayer.Result> f = rig.player.request(small);
		rig.awaitHandedOver();
		rig.paint();
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, f.get(10, TimeUnit.SECONDS).outcome);

		RawVolumeStore.CacheStats before = rig.stats();
		RawStrictPlayer.Result refused = rig.player.request(big).get(10, TimeUnit.SECONDS);
		RawVolumeStore.CacheStats after = rig.stats();
		System.out.println("refused: " + refused + "; " + after);
		assertEquals(RawStrictPlayer.Outcome.REFUSED, refused.outcome);
		assertTrue(refused.cause instanceof TiffRawVolumeStore.BudgetException);
		assertEquals("nothing was read for it", before.loads, after.loads);
		assertEquals(before.refusals + 1, after.refusals);
		assertSame("the shown frame stays", small, rig.onEventThread(() -> rig.presenter.shownRequest()));
		assertTrue(rig.presenter.pendingNote(), rig.presenter.pendingNote().contains("cache budget cannot hold the frame"));
		assertEquals(0, after.pinnedBytes);
		assertEquals(frame, after.stagedBytes);
		assertTrue(after.peakTotalBytes <= after.budgetBytes);
	}

	@Test
	public void closingThePlayerReleasesTheShownImageAndShowsNothingMore() throws Exception {
		Rig rig = new Rig(64L << 20, 2);
		AffineTransform3D view = view(entry(0).layout().dimensions(), 0.5, 0);
		rig.present(request(1, 0, view, Interpolation.NLINEAR, 2500));
		assertEquals(frameBytes(), rig.stats().stagedBytes);
		rig.player.close();
		assertEquals("the shown image is released", 0, rig.stats().stagedBytes);
		assertFalse(rig.presenter.isActive());
		for (int p : rig.paint().getRGB(0, 0, SCREEN_W, SCREEN_H, null, 0, SCREEN_W)) assertEquals("nothing is drawn", 0, p);
		RawStrictPlayer.Result after = rig.player.request(request(2, 1, view, Interpolation.NLINEAR, 2500)).get(10, TimeUnit.SECONDS);
		assertEquals(RawStrictPlayer.Outcome.FAILED, after.outcome);
		assertNotNull(after.cause);
	}
}
