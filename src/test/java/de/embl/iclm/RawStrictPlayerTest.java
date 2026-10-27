package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.viewer.Interpolation;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * Completed-frame playback without a window (joint decision D2; contracts C4.2, C6.2, C6.3): the
 * player, the store's leases and reservations, and the presenter, whose {@code drawOverlays} is
 * called here as the canvas would call it - which is what makes each paint, and so each
 * acknowledgement, the test's to decide.
 *
 * <ul>
 * <li>A frame is acknowledged only by a full paint that draws it: not when it is prepared, drawn,
 *     handed over, painted again, or painted through a clip; and never once a newer request has
 *     been accepted (agent-B's S1, S2).</li>
 * <li>Consecutive time points under one view are separate frames; the frame shown stays, with its
 *     own label and count, until its replacement is painted.</li>
 * <li>The newest request wins, and every frame presented is its own request's image, under
 *     delayed reads and rapid changes of time, view and display range.</li>
 * <li>A frame's cells and image stay charged until the last band drawing it has finished, also
 *     when it is superseded or the player is closed meanwhile (S3).</li>
 * <li>Acknowledgements hold no pixels; a frame replaced, withdrawn or stopped can be collected,
 *     and its charge goes with it (S4).</li>
 * <li>The files watched are those of the frames shown, waiting and in preparation, and a change
 *     found in any of them keeps the frame off the screen (S5).</li>
 * <li>A frame reads its lease's cells and nothing else: a cell left out of its plan fails it (S7).</li>
 * <li>A frame the budget cannot hold is refused before anything is read.</li>
 * </ul>
 */
public class RawStrictPlayerTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final int SCREEN_W = 96, SCREEN_H = 120;
	/** Rows above the time label and the pending line, which are compared with the reference. */
	private static final int IMAGE_ROWS = SCREEN_H - RawFrameFixture.LABEL_ROWS;

	private RawFrameFixture f;
	private final List<AutoCloseable> closing = new ArrayList<AutoCloseable>();

	@Before
	public void writeThreeTimePoints() throws Exception {
		f = new RawFrameFixture(folder.getRoot());
	}

	@After
	public void closeEverything() throws Exception {
		RawStrictPlayer.beforeCheckForTest = null;
		RawStrictPlayer.beforeHandOverForTest = null;
		RawStrictPlayer.coverageForTest = null;
		RawFrameRenderer.rowHookForTest = null;
		Collections.reverse(closing);
		for (AutoCloseable c : closing) c.close();
		f.close();
	}

	/* ---------------------------------------------------------------- the rig */

	private AffineTransform3D view(double depthFraction, double angle) {
		return RawFrameFixture.view(f.dims(), depthFraction, angle, SCREEN_W, SCREEN_H);
	}

	/** Display range -1000 to {@code max}: 0 maps to grey, so a fabricated zero would show. */
	private RawFrameRequest request(long revision, int t, AffineTransform3D view, Interpolation method, double max) {
		return f.request(revision, t, view, method, -1000, max, SCREEN_W, SCREEN_H);
	}

	private RawFrameRequest request(long revision, int t, AffineTransform3D view) {
		return request(revision, t, view, Interpolation.NLINEAR, 2500);
	}

	private static int[] imageRows(BufferedImage screen) {
		return screen.getRGB(0, 0, screen.getWidth(), IMAGE_ROWS, null, 0, screen.getWidth());
	}

	private long frameBytes() {
		return 4L * SCREEN_W * SCREEN_H;
	}

	/** A player over its own store, with a presenter on a canvas that is never shown. */
	private final class Rig {
		final TiffRawVolumeStore store;
		final JPanel canvas = new JPanel();
		final RawPresenter presenter;
		final RawFrameRenderer renderer = new RawFrameRenderer(2);
		final RawStrictPlayer player;
		/** A digest of each frame as it was committed, by revision: pixels are not kept. */
		final Map<Long, Integer> committed = new ConcurrentHashMap<Long, Integer>();

		Rig() {
			this(64L << 20, 2, SCREEN_W, SCREEN_H);
		}

		Rig(long budget, int ioThreads, int w, int h) {
			store = new TiffRawVolumeStore(budget, f.cell, ioThreads, null);
			canvas.setSize(w, h);
			presenter = new RawPresenter(canvas);
			presenter.setCommitObserver((r, image) -> committed.put(r.revision, RawFrameFixture.rowsHash(image, rows(r))));
			player = new RawStrictPlayer(store, renderer, presenter);
			closing.add(() -> store.closeAsync().get(10, TimeUnit.SECONDS));
			closing.add(() -> {
				player.close();
				assertTrue("the player stopped", player.awaitStopped(10000));
			});
		}

		int rows(RawFrameRequest r) {
			return r.height > 2 * RawFrameFixture.LABEL_ROWS ? r.height - RawFrameFixture.LABEL_ROWS : r.height;
		}

		/** One full paint of the canvas, on the event thread, as Swing would do it. */
		BufferedImage paint() throws Exception {
			return paint(null);
		}

		/** One paint, through {@code clip} when given. */
		BufferedImage paint(final Rectangle clip) throws Exception {
			final BufferedImage screen = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_ARGB);
			SwingUtilities.invokeAndWait(() -> {
				Graphics2D g = screen.createGraphics();
				if (clip != null) g.setClip(clip);
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
			CompletableFuture<RawStrictPlayer.Result> result = player.request(r);
			awaitHandedOver();
			paint();
			RawStrictPlayer.Result done = result.get(10, TimeUnit.SECONDS);
			assertEquals(done.toString(), RawStrictPlayer.Outcome.PRESENTED, done.outcome);
			return done;
		}

		/** The frame committed for {@code r} was its own request's image. */
		void assertCommittedImageOf(RawFrameRequest r) throws Exception {
			assertEquals("frame " + r.revision + " committed its own request's image",
					Integer.valueOf(RawFrameFixture.rowsHash(f.referenceImage(r), rows(r))), committed.get(r.revision));
		}

		RawVolumeStore.CacheStats stats() {
			return store.stats();
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("latch deadline");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/* ---------------------------------------------------------------- presentation */

	@Test
	public void invalidatingBeforeACapturableRequestDropsPendingAndKeepsShown() throws Exception {
		Rig rig = new Rig();
		RawFrameRequest shown = request(1, 0, view(0.5, 0));
		rig.present(shown);
		CompletableFuture<RawStrictPlayer.Result> old = rig.player.request(request(2, 1, view(0.5, 0)));
		rig.awaitHandedOver();
		rig.player.supersede(3); // the next file has not been checked yet
		assertEquals(RawStrictPlayer.Outcome.SUPERSEDED, old.get(5, TimeUnit.SECONDS).outcome);
		rig.paint();
		assertSame(shown, rig.presenter.shownRequest());
		assertEquals(frameBytes(), rig.stats().stagedBytes);
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, rig.present(request(3, 2, view(0.5, 0))).outcome);
	}

	@Test
	public void aFrameIsPresentedByAFullPaintThatDrawsItAndByNothingBefore() throws Exception {
		Rig rig = new Rig();
		rig.store.setReadHook(cell -> sleep(5));		// delayed loads
		RawFrameRequest r = request(1, 0, view(0.5, 0));
		CompletableFuture<RawStrictPlayer.Result> result = rig.player.request(r);
		rig.awaitHandedOver();
		Thread.sleep(200);
		assertFalse("prepared, drawn and handed over is not presented", result.isDone());
		RawVolumeStore.CacheStats waiting = rig.stats();
		assertEquals("the lease is released once every band has finished: " + waiting, 0, waiting.pinnedBytes);
		assertTrue("the lease pinned the frame's cells while it was prepared: " + waiting, waiting.peakPinnedBytes > 0);
		assertEquals("the image waiting to be painted is charged, its scratch no longer: " + waiting, frameBytes(),
				waiting.stagedBytes);
		assertTrue("the scratch was charged while it was drawn", waiting.peakStagedBytes > frameBytes());

		BufferedImage screen = rig.paint();
		RawStrictPlayer.Result done = result.get(10, TimeUnit.SECONDS);
		System.out.println("delayed loads: " + done);
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, done.outcome);
		assertSame("the acknowledgement names its own request", r, done.presented.request);
		assertEquals(1, done.presented.delivered);
		assertEquals("every value the frame read was in its lease", 0, done.coverageMisses);
		assertTrue(done.leaseCells > 0);
		assertArrayEquals("the canvas shows the request's frame", f.referenceOnBlack(r, IMAGE_ROWS), imageRows(screen));
		rig.assertCommittedImageOf(r);
		assertTrue(rig.presenter.label().startsWith("t = 1 " + f.entry(0).time + " paint 1"));

		rig.paint();
		rig.paint();
		assertEquals("painting the same frame again acknowledges nothing", 1L, rig.presenter.delivered());
		RawVolumeStore.CacheStats shown = rig.stats();
		assertEquals("one staged image, the one on screen: " + shown, frameBytes(), shown.stagedBytes);
		assertTrue("within the budget: " + shown, shown.peakTotalBytes <= shown.budgetBytes);
	}

	@Test
	public void consecutiveTimesUnderOneViewAreSeparateFramesAndTheShownOneStaysUntilItsReplacementIsPainted() throws Exception {
		Rig rig = new Rig();
		AffineTransform3D view = view(0.5, 0);
		RawFrameRequest first = request(1, 0, view), second = request(2, 1, view);
		assertFalse("the two time points look different",
				Arrays.equals(f.referenceOnBlack(first, IMAGE_ROWS), f.referenceOnBlack(second, IMAGE_ROWS)));
		rig.present(first);

		CompletableFuture<RawStrictPlayer.Result> result = rig.player.request(second);
		rig.awaitHandedOver();
		assertSame("until the next paint the first frame is shown", first, rig.presenter.shownRequest());
		assertTrue(rig.presenter.label().startsWith("t = 1 " + f.entry(0).time + " paint 1"));
		assertEquals("both images are held while the second waits", 2 * frameBytes(), rig.stats().stagedBytes);
		assertFalse(result.isDone());

		BufferedImage screen = rig.paint();
		RawStrictPlayer.Result done = result.get(10, TimeUnit.SECONDS);
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, done.outcome);
		assertSame(second, done.presented.request);
		assertEquals(2, done.presented.delivered);
		assertArrayEquals(f.referenceOnBlack(second, IMAGE_ROWS), imageRows(screen));
		assertTrue(rig.presenter.label().startsWith("t = 2 " + f.entry(1).time + " paint 2"));
		assertEquals("the replaced image is released", frameBytes(), rig.stats().stagedBytes);
	}

	/**
	 * Agent-B's S1: a newer request is accepted while an older frame waits for paint, and the canvas
	 * paints while the newer one is still being prepared. The older frame must not be presented: the
	 * frame shown before it stays, with its label and count.
	 */
	@Test
	public void aNewerRequestDropsAFrameStillWaitingForPaintAndTheShownOneStays() throws Exception {
		final Rig rig = new Rig();
		AffineTransform3D view = view(0.5, 0);
		RawFrameRequest shown = request(1, 0, view), waiting = request(2, 1, view), newest = request(3, 2, view);
		rig.present(shown);
		CompletableFuture<RawStrictPlayer.Result> second = rig.player.request(waiting);
		rig.awaitHandedOver();
		final CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
		rig.store.setReadHook(cell -> {		// the newest frame's first read waits
			reading.countDown();
			await(release);
		});
		try {
			CompletableFuture<RawStrictPlayer.Result> third = rig.player.request(newest);
			RawStrictPlayer.Result dropped = second.get(5, TimeUnit.SECONDS);
			assertEquals("dropped as the newer request was accepted: " + dropped, RawStrictPlayer.Outcome.SUPERSEDED, dropped.outcome);
			assertTrue("the newest frame is still being prepared", reading.await(5, TimeUnit.SECONDS));
			assertFalse(rig.presenter.waiting());
			BufferedImage screen = rig.paint();
			assertSame("the frame shown before stays", shown, rig.presenter.shownRequest());
			assertEquals("no obsolete frame is counted", 1L, rig.presenter.delivered());
			assertArrayEquals(f.referenceOnBlack(shown, IMAGE_ROWS), imageRows(screen));
			assertTrue(rig.presenter.label().startsWith("t = 1 " + f.entry(0).time + " paint 1"));
			assertEquals("the dropped frame's image is released", frameBytes(), rig.stats().stagedBytes);
			release.countDown();
			rig.awaitHandedOver();
			rig.paint();
			RawStrictPlayer.Result presented = third.get(10, TimeUnit.SECONDS);
			assertEquals(RawStrictPlayer.Outcome.PRESENTED, presented.outcome);
			assertEquals(2, presented.presented.delivered);
			rig.assertCommittedImageOf(newest);
			assertFalse("the dropped frame was never committed", rig.committed.containsKey(waiting.revision));
		} finally {
			release.countDown();
			rig.store.setReadHook(null);
		}
	}

	/**
	 * S1 at the hand-over: a newer request accepted after the player's last check and before the
	 * hand-over. The presenter refuses the older frame itself; the newer one is presented.
	 */
	@Test
	public void aRequestAcceptedJustBeforeTheHandOverSupersedesTheFrame() throws Exception {
		final Rig rig = new Rig();
		AffineTransform3D view = view(0.5, 0);
		RawFrameRequest shown = request(1, 0, view), older = request(2, 1, view);
		final RawFrameRequest newer = request(3, 2, view);
		rig.present(shown);
		final CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
		rig.store.setReadHook(cell -> {		// the newer frame's preparation waits, so the canvas can paint meanwhile
			if (cell.startsWith(newer.key.toString())) {
				reading.countDown();
				await(release);
			}
		});
		final List<CompletableFuture<RawStrictPlayer.Result>> late = new ArrayList<CompletableFuture<RawStrictPlayer.Result>>();
		RawStrictPlayer.beforeHandOverForTest = r -> {
			if (r.revision == 2) late.add(rig.player.request(newer));		// accepted now, on the driver, before the hand-over
		};
		try {
			CompletableFuture<RawStrictPlayer.Result> second = rig.player.request(older);
			assertTrue("the newer frame is being prepared, after the older one's hand-over", reading.await(5, TimeUnit.SECONDS));
			rig.paint();
			RawStrictPlayer.Result result = second.get(5, TimeUnit.SECONDS);
			assertEquals(result.toString(), RawStrictPlayer.Outcome.SUPERSEDED, result.outcome);
			assertTrue(String.valueOf(result.cause), result.cause instanceof RawPresenter.NotShown);
			assertFalse("the older frame was never committed", rig.committed.containsKey(older.revision));
			assertSame(shown, rig.presenter.shownRequest());
			assertEquals(1L, rig.presenter.delivered());
			release.countDown();
			rig.awaitHandedOver();
			rig.paint();
			RawStrictPlayer.Result presented = late.get(0).get(10, TimeUnit.SECONDS);
			assertEquals(RawStrictPlayer.Outcome.PRESENTED, presented.outcome);
			assertEquals(2, presented.presented.delivered);
			assertEquals(frameBytes(), rig.stats().stagedBytes);
		} finally {
			release.countDown();
			rig.store.setReadHook(null);
		}
	}

	/**
	 * S1, the other way: the newer request is refused by the budget. The frame waiting for paint
	 * was dropped when it was accepted, and the last presented frame stays.
	 */
	@Test
	public void aRefusedReplacementLeavesTheLastPresentedFrame() throws Exception {
		final int w = 32, h = 32;
		long[] dims = f.dims();
		AffineTransform3D close = RawFrameFixture.view(dims, 0.5, 0, w, h);
		AffineTransform3D zoom = new AffineTransform3D();
		zoom.translate(-0.5 * w, -0.5 * h, 0);
		zoom.scale(6);
		zoom.translate(0.5 * w, 0.5 * h, 0);
		close.preConcatenate(zoom);
		AffineTransform3D whole = RawFrameFixture.view(dims, 0.5, 0, w, h);
		RawFrameRequest shown = f.request(1, 0, close, Interpolation.NLINEAR, -1000, 2500, w, h);
		RawFrameRequest waiting = f.request(2, 1, close, Interpolation.NLINEAR, -1000, 2500, w, h);
		RawFrameRequest big = f.request(3, 2, whole, Interpolation.NLINEAR, -1000, 2500, w, h);
		int small = Math.max(cellsOf(shown).cardinality(), cellsOf(waiting).cardinality()), large = cellsOf(big).cardinality();
		long frame = 4L * w * h, cell = f.cell;
		int io = 1;
		long scratch = new RawFrameRenderer(2).scratchBytes(w);
		// room for a small lease beside two images; not for the big one beside the shown image (the waiting
		// frame is dropped, and its image released, as soon as the big request is accepted)
		long budget = io * cell + cell + 2 * frame + scratch + (small + 2) * cell;
		assertTrue("the fixture separates them: " + small + " and " + large + " cells",
				large * cell > budget - io * cell - cell - frame);
		assertTrue(budget >= TiffRawVolumeStore.minimumBudget(cell, io));
		Rig rig = new Rig(budget, io, w, h);
		rig.present(shown);
		CompletableFuture<RawStrictPlayer.Result> second = rig.player.request(waiting);
		rig.awaitHandedOver();
		RawVolumeStore.CacheStats before = rig.stats();
		RawStrictPlayer.Result refused = rig.player.request(big).get(10, TimeUnit.SECONDS);
		assertEquals(refused.toString(), RawStrictPlayer.Outcome.REFUSED, refused.outcome);
		assertEquals("nothing was read for it", before.loads, rig.stats().loads);
		assertEquals(RawStrictPlayer.Outcome.SUPERSEDED, second.get(5, TimeUnit.SECONDS).outcome);
		rig.paint();
		assertSame("the last presented frame stays", shown, rig.presenter.shownRequest());
		assertEquals(1L, rig.presenter.delivered());
		assertTrue(rig.presenter.pendingNote(), rig.presenter.pendingNote().contains("cache budget cannot hold the frame"));
		RawVolumeStore.CacheStats after = rig.stats();
		assertEquals(0, after.pinnedBytes);
		assertEquals(frame, after.stagedBytes);
		assertTrue(after.peakTotalBytes <= after.budgetBytes);
	}

	private BitSet cellsOf(RawFrameRequest r) {
		return RawCoverage.cells(r.layout.dimensions(), r.layout.rowsPerStrip, r.coverageParts(), r.width, r.height, r.interpolation);
	}

	@Test
	public void oversizedWorldYZFrameIsRefusedAndReleasesItsLease() throws Exception {
		Rig rig = new Rig();
		RawFrameRequest shown = request(1, 0, view(0.5, 0));
		rig.present(shown);
		RawFrameRequest tooLarge = f.request(2, 1, RawWorldView.YZ.orient(shown.viewer, 9000, 9000),
				Interpolation.NLINEAR, 0, 2500, 9000, 9000);
		RawStrictPlayer.Result refused = rig.player.request(tooLarge).get(10, TimeUnit.SECONDS);
		assertEquals(RawStrictPlayer.Outcome.REFUSED, refused.outcome);
		assertSame(shown, rig.presenter.shownRequest());
		assertEquals(0, rig.stats().pinnedBytes);
		assertEquals(frameBytes(), rig.stats().stagedBytes);
		assertTrue(rig.stats().peakTotalBytes <= rig.stats().budgetBytes);
	}

	/** Agent-B's S2: a paint clipped to one pixel redraws what is shown and acknowledges nothing. */
	@Test
	public void aClippedPaintRedrawsTheShownFrameAndKeepsTheWaitingOneForAFullPaint() throws Exception {
		Rig rig = new Rig();
		AffineTransform3D view = view(0.5, 0);
		RawFrameRequest shown = request(1, 0, view), next = request(2, 1, view);
		rig.present(shown);
		CompletableFuture<RawStrictPlayer.Result> result = rig.player.request(next);
		rig.awaitHandedOver();
		BufferedImage clipped = rig.paint(new Rectangle(0, 0, 1, 1));
		assertFalse("a clipped paint acknowledges nothing", result.isDone());
		assertTrue("the frame still waits", rig.presenter.waiting());
		assertSame(shown, rig.presenter.shownRequest());
		assertEquals(1L, rig.presenter.delivered());
		assertEquals("the clip was honoured", 0, clipped.getRGB(40, 40));
		assertEquals("the shown frame was redrawn where the clip allowed", f.referenceOnBlack(shown, 1)[0], clipped.getRGB(0, 0));
		rig.paint(new Rectangle(0, 0, SCREEN_W, SCREEN_H - 1));
		assertFalse("nor does a paint missing one row", result.isDone());

		BufferedImage screen = rig.paint();
		RawStrictPlayer.Result done = result.get(10, TimeUnit.SECONDS);
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, done.outcome);
		assertEquals(2, done.presented.delivered);
		assertArrayEquals(f.referenceOnBlack(next, IMAGE_ROWS), imageRows(screen));
		assertTrue(rig.presenter.label().startsWith("t = 2 " + f.entry(1).time + " paint 2"));
	}

	/** S2: the canvas is resized between the hand-over and the paint; the frame drawn for the old size is not shown. */
	@Test
	public void aFrameForACanvasSinceResizedIsNotShown() throws Exception {
		Rig rig = new Rig();
		AffineTransform3D view = view(0.5, 0);
		RawFrameRequest shown = request(1, 0, view), old = request(2, 1, view);
		rig.present(shown);
		CompletableFuture<RawStrictPlayer.Result> result = rig.player.request(old);
		rig.awaitHandedOver();
		rig.canvas.setSize(SCREEN_W + 8, SCREEN_H);
		BufferedImage screen = rig.paint();
		RawStrictPlayer.Result done = result.get(5, TimeUnit.SECONDS);
		assertEquals(done.toString(), RawStrictPlayer.Outcome.SUPERSEDED, done.outcome);
		assertTrue(String.valueOf(done.cause), done.cause instanceof RawPresenter.NotShown
				&& ((RawPresenter.NotShown) done.cause).reason == RawPresenter.Reason.VIEWPORT_CHANGED);
		assertSame("the frame shown before stays", shown, rig.presenter.shownRequest());
		assertEquals(1L, rig.presenter.delivered());
		assertArrayEquals(f.referenceOnBlack(shown, 1), screen.getRGB(0, 0, SCREEN_W, 1, null, 0, SCREEN_W));
		assertEquals("its image is released", frameBytes(), rig.stats().stagedBytes);
		RawFrameRequest resized = f.request(3, 1, view, Interpolation.NLINEAR, -1000, 2500, SCREEN_W + 8, SCREEN_H);
		assertEquals(2, rig.present(resized).presented.delivered);
	}

	@Test
	public void rapidRequestsEndWithTheNewestAndEveryPresentedFrameIsItsOwnRequest() throws Exception {
		final Rig rig = new Rig();
		rig.store.setReadHook(cell -> sleep(1));
		AffineTransform3D[] views = { view(0.5, 0), view(0.4, 0.3), view(0.6, -0.2) };
		double[] ranges = { 2500, 4000 };
		// the canvas paints on its own, every few milliseconds
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

		List<RawFrameRequest> requests = new ArrayList<RawFrameRequest>();
		List<CompletableFuture<RawStrictPlayer.Result>> results = new ArrayList<CompletableFuture<RawStrictPlayer.Result>>();
		Random random = new Random(7);
		for (int i = 0; i < 40; i++) {
			RawFrameRequest r = request(i + 1, i % 3, views[random.nextInt(views.length)],
					random.nextBoolean() ? Interpolation.NLINEAR : Interpolation.NEARESTNEIGHBOR, ranges[random.nextInt(2)]);
			requests.add(r);
			results.add(rig.player.request(r));
			Thread.sleep(random.nextInt(25));
		}
		for (CompletableFuture<RawStrictPlayer.Result> r : results) r.get(30, TimeUnit.SECONDS);
		painting.set(false);
		painter.join(5000);

		RawStrictPlayer.Result last = results.get(results.size() - 1).get();
		assertEquals("the newest request is shown: " + last, RawStrictPlayer.Outcome.PRESENTED, last.outcome);
		long lastDelivered = 0, othersLoads = 0;
		int presented = 0, superseded = 0;
		for (CompletableFuture<RawStrictPlayer.Result> future : results) {		// in request order
			RawStrictPlayer.Result r = future.get();
			assertEquals("no frame read outside its lease: " + r, 0, r.coverageMisses);
			othersLoads += r.storeLoadsWhileDrawing;
			if (r.outcome == RawStrictPlayer.Outcome.SUPERSEDED) {
				superseded++;
				assertFalse("a superseded frame was never committed", rig.committed.containsKey(r.request.revision));
				continue;
			}
			assertEquals(r.toString(), RawStrictPlayer.Outcome.PRESENTED, r.outcome);
			assertSame("the acknowledgement names its own request", r.request, r.presented.request);
			assertEquals("revisions are presented in order, one count each", lastDelivered + 1, r.presented.delivered);
			rig.assertCommittedImageOf(r.request);
			lastDelivered = r.presented.delivered;
			presented++;
		}
		RawVolumeStore.CacheStats s = rig.stats();
		System.out.println("rapid requests: " + presented + " presented, " + superseded + " superseded; 0 values outside a lease; "
				+ othersLoads + " cells loaded by the store for others while frames were drawn; " + s);
		assertTrue("some frames were presented along the way", presented >= 2);
		assertTrue("some frames were superseded", superseded > 0);
		assertEquals(0, s.pinnedBytes);
		assertEquals("one staged image, the one on screen", frameBytes(), s.stagedBytes);
		assertTrue(s.peakTotalBytes <= s.budgetBytes);
	}

	/* ---------------------------------------------------------------- files that change (S5) */

	@Test
	public void aFileChangedWhileItsFrameIsPreparedIsNotShownAndTheShownFrameStays() throws Exception {
		final Rig rig = new Rig();
		AffineTransform3D view = view(0.5, 0);
		RawFrameRequest first = request(1, 0, view);
		rig.present(first);

		final RawAcquisition.Entry changing = f.entry(1);
		final AtomicBoolean touched = new AtomicBoolean();
		rig.store.setReadHook(cell -> {
			if (cell.startsWith(changing.key().toString()) && touched.compareAndSet(false, true)) RawFrameFixture.touch(changing);
		});
		RawStrictPlayer.Result result = rig.player.request(request(2, 1, view)).get(10, TimeUnit.SECONDS);
		assertTrue("the file was changed under the preparation", touched.get());
		assertEquals(result.toString(), RawStrictPlayer.Outcome.CHANGED, result.outcome);
		assertTrue(rig.store.isStale(changing.key()));
		BufferedImage screen = rig.paint();
		assertSame(first, rig.presenter.shownRequest());
		assertArrayEquals("the shown frame stays", f.referenceOnBlack(first, IMAGE_ROWS), imageRows(screen));
		assertEquals(1L, rig.presenter.delivered());
		assertTrue(rig.presenter.pendingNote(), rig.presenter.pendingNote().contains("not shown: the file changed"));
		RawVolumeStore.CacheStats s = rig.stats();
		assertEquals("a failed preparation keeps no pin: " + s, 0, s.pinnedBytes);
		assertEquals(frameBytes(), s.stagedBytes);
	}

	@Test
	public void aFileChangedAfterItsFrameIsDrawnIsNotShown() throws Exception {
		Rig rig = new Rig();
		AffineTransform3D view = view(0.5, 0);
		RawFrameRequest first = request(1, 0, view);
		rig.present(first);
		RawStrictPlayer.beforeCheckForTest = r -> {
			if (r.timeIndex == 1) RawFrameFixture.touch(r.entry);
		};
		RawStrictPlayer.Result result = rig.player.request(request(2, 1, view)).get(10, TimeUnit.SECONDS);
		assertEquals(result.toString(), RawStrictPlayer.Outcome.CHANGED, result.outcome);
		assertFalse("never handed over", rig.presenter.waiting());
		assertArrayEquals(f.referenceOnBlack(first, IMAGE_ROWS), imageRows(rig.paint()));
		RawVolumeStore.CacheStats s = rig.stats();
		assertEquals("its image is released: " + s, frameBytes(), s.stagedBytes);
		assertEquals(0, s.pinnedBytes);
	}

	/** A change found between the last check and the hand-over: the presenter refuses the frame. */
	@Test
	public void aChangeFoundJustBeforeTheHandOverKeepsTheFrameOff() throws Exception {
		final Rig rig = new Rig();
		rig.store.setStaleListener((key, why) -> rig.player.withdraw(key));
		AffineTransform3D view = view(0.5, 0);
		RawFrameRequest first = request(1, 0, view);
		rig.present(first);
		RawStrictPlayer.beforeHandOverForTest = r -> {
			if (r.timeIndex != 1) return;
			RawFrameFixture.touch(r.entry);
			assertFalse(rig.store.checkGeneration(r.key));		// what the view's checker does, found now
		};
		RawStrictPlayer.Result result = rig.player.request(request(2, 1, view)).get(10, TimeUnit.SECONDS);
		assertEquals(result.toString(), RawStrictPlayer.Outcome.CHANGED, result.outcome);
		assertFalse(rig.presenter.waiting());
		rig.paint();
		assertSame(first, rig.presenter.shownRequest());
		assertEquals(1L, rig.presenter.delivered());
		assertEquals(frameBytes(), rig.stats().stagedBytes);
	}

	/** A change found while the frame waits for paint: it is dropped, and the shown frame stays. */
	@Test
	public void aChangeFoundWhileAFrameWaitsForPaintKeepsItOff() throws Exception {
		final Rig rig = new Rig();
		rig.store.setStaleListener((key, why) -> rig.player.withdraw(key));
		AffineTransform3D view = view(0.5, 0);
		RawFrameRequest first = request(1, 0, view), waiting = request(2, 1, view);
		rig.present(first);
		CompletableFuture<RawStrictPlayer.Result> result = rig.player.request(waiting);
		rig.awaitHandedOver();
		RawFrameFixture.touch(waiting.entry);
		assertFalse(rig.store.checkGeneration(waiting.key));
		RawStrictPlayer.Result done = result.get(5, TimeUnit.SECONDS);
		assertEquals(done.toString(), RawStrictPlayer.Outcome.CHANGED, done.outcome);
		rig.paint();
		assertSame(first, rig.presenter.shownRequest());
		assertEquals(1L, rig.presenter.delivered());
		assertEquals("its image is released", frameBytes(), rig.stats().stagedBytes);
	}

	/** What the view's checker watches: the shown frame's file, the waiting one's, and the one in preparation. */
	@Test
	public void theFilesWatchedAreThoseShownWaitingAndInPreparation() throws Exception {
		final Rig rig = new Rig();
		AffineTransform3D view = view(0.5, 0);
		RawFrameRequest first = request(1, 0, view), next = request(2, 1, view);
		rig.present(first);
		assertEquals(Collections.singleton(first.key), rig.player.watchedKeys());
		final CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
		rig.store.setReadHook(cell -> {
			reading.countDown();
			await(release);
		});
		try {
			CompletableFuture<RawStrictPlayer.Result> result = rig.player.request(next);
			assertTrue(reading.await(5, TimeUnit.SECONDS));
			Set<RawAcquisition.FileKey> preparing = rig.player.watchedKeys();
			assertTrue("the shown frame's file: " + preparing, preparing.contains(first.key));
			assertTrue("the file in preparation: " + preparing, preparing.contains(next.key));
			release.countDown();
			rig.awaitHandedOver();
			Set<RawAcquisition.FileKey> waiting = rig.player.watchedKeys();
			assertTrue(waiting.contains(first.key) && waiting.contains(next.key));
			rig.paint();
			assertEquals(RawStrictPlayer.Outcome.PRESENTED, result.get(5, TimeUnit.SECONDS).outcome);
			assertEquals(Collections.singleton(next.key), rig.player.watchedKeys());
		} finally {
			release.countDown();
			rig.store.setReadHook(null);
		}
	}

	@Test
	public void aShownFrameWhoseFileChangesIsTakenOffAndItsChargeReleased() throws Exception {
		final Rig rig = new Rig();
		rig.store.setStaleListener((key, why) -> rig.player.withdraw(key));
		AffineTransform3D view = view(0.5, 0);
		RawFrameRequest first = request(1, 0, view);
		rig.present(first);
		assertEquals(frameBytes(), rig.stats().stagedBytes);
		RawFrameFixture.touch(f.entry(0));
		assertFalse(rig.store.checkGeneration(f.entry(0).key()));
		assertFalse(rig.presenter.hasShownImage());
		assertSame(first, rig.presenter.withdrawnRequest());
		assertTrue(rig.presenter.label().endsWith("withdrawn"));
		assertEquals("the withdrawn frame's charge goes with it", 0, rig.stats().stagedBytes);
		for (int p : imageRows(rig.paint())) assertEquals("nothing of the changed file is drawn", 0xff000000, p);

		RawFrameRequest next = request(2, 1, view);
		RawStrictPlayer.Result result = rig.present(next);
		assertEquals(2, result.presented.delivered);
		assertEquals(null, rig.presenter.withdrawnRequest());
		rig.assertCommittedImageOf(next);
	}

	/* ---------------------------------------------------------------- ownership (S3, S4) */

	/**
	 * Agent-B's S4: acknowledgements kept by the caller hold no pixels, and a replaced frame's image
	 * can be collected; after the player stops, the last one can too, and nothing stays charged.
	 */
	@Test
	public void acknowledgementsHoldNoPixelsAndReplacedFramesCanBeCollected() throws Exception {
		Rig rig = new Rig();
		final Map<Long, WeakReference<BufferedImage>> painted = new ConcurrentHashMap<Long, WeakReference<BufferedImage>>();
		rig.presenter.setCommitObserver((r, image) -> painted.put(r.revision, new WeakReference<BufferedImage>(image)));
		AffineTransform3D view = view(0.5, 0);
		List<RawStrictPlayer.Result> kept = new ArrayList<RawStrictPlayer.Result>();
		for (int n = 1; n <= 8; n++) kept.add(rig.present(request(n, n % 3, view)));
		assertEquals("only the shown frame is charged", frameBytes(), rig.stats().stagedBytes);
		awaitCollected(painted, 1, 7);
		assertNotNull("the shown frame is held by the presenter", painted.get(8L).get());
		rig.player.close();
		assertTrue(rig.player.awaitStopped(5000));
		assertEquals(0, rig.stats().stagedBytes);
		assertFalse(rig.presenter.hasShownImage());
		awaitCollected(painted, 8, 8);
		assertEquals("the acknowledgements are still held", 8, kept.size());
		for (int n = 0; n < 8; n++) assertEquals(n + 1, kept.get(n).presented.request.revision);
	}

	private static void awaitCollected(Map<Long, WeakReference<BufferedImage>> painted, long from, long to)
			throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10000;
		for (;;) {
			boolean all = true;
			for (long n = from; n <= to; n++) all &= painted.get(n).get() == null;
			if (all) return;
			if (System.currentTimeMillis() > deadline) {
				for (long n = from; n <= to; n++)
					if (painted.get(n).get() != null) fail("the image of frame " + n + " is still reachable");
			}
			System.gc();
			Thread.sleep(20);
		}
	}

	/**
	 * Agent-B's S3: a frame superseded while one of its bands is still drawing keeps its cells
	 * pinned and its image and scratch charged until that band has finished; the newer frame waits
	 * its turn.
	 */
	@Test
	public void aSupersededFrameKeepsItsCellsAndImageUntilItsLastBandHasFinished() throws Exception {
		Rig rig = new Rig();
		AffineTransform3D view = view(0.5, 0);
		rig.present(request(1, 0, view));
		final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		final AtomicBoolean once = new AtomicBoolean();
		RawFrameRenderer.rowHookForTest = row -> {
			if (once.compareAndSet(false, true)) {
				entered.countDown();
				await(release);
			}
		};
		try {
			CompletableFuture<RawStrictPlayer.Result> second = rig.player.request(request(2, 1, view));
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			CompletableFuture<RawStrictPlayer.Result> third = rig.player.request(request(3, 2, view));
			Thread.sleep(200);
			assertFalse("the superseded frame waits for its last band", second.isDone());
			RawVolumeStore.CacheStats held = rig.stats();
			assertTrue("its cells are still pinned: " + held, held.pinnedBytes > 0);
			assertEquals("the shown frame, its image and its scratch are charged: " + held,
					2 * frameBytes() + rig.renderer.scratchBytes(SCREEN_W), held.stagedBytes);
			release.countDown();
			assertEquals(RawStrictPlayer.Outcome.SUPERSEDED, second.get(5, TimeUnit.SECONDS).outcome);
			rig.awaitHandedOver();
			rig.paint();
			assertEquals(RawStrictPlayer.Outcome.PRESENTED, third.get(5, TimeUnit.SECONDS).outcome);
			RawVolumeStore.CacheStats after = rig.stats();
			assertEquals(0, after.pinnedBytes);
			assertEquals(frameBytes(), after.stagedBytes);
		} finally {
			release.countDown();
		}
	}

	/** S3, closing: the player says it has not stopped while a band still draws, and releases everything once it has. */
	@Test
	public void closingWhileABandStillDrawsIsReportedAndReleasesOnceItHasFinished() throws Exception {
		Rig rig = new Rig();
		AffineTransform3D view = view(0.5, 0);
		rig.present(request(1, 0, view));
		final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		final AtomicBoolean once = new AtomicBoolean();
		RawFrameRenderer.rowHookForTest = row -> {
			if (once.compareAndSet(false, true)) {
				entered.countDown();
				await(release);
			}
		};
		try {
			CompletableFuture<RawStrictPlayer.Result> second = rig.player.request(request(2, 1, view));
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			rig.player.close();
			assertFalse("not stopped while a band draws", rig.player.awaitStopped(300));
			RawVolumeStore.CacheStats held = rig.stats();
			assertTrue("the drawing frame's cells are still pinned: " + held, held.pinnedBytes > 0);
			assertEquals("its image and scratch are still charged, the shown frame no longer: " + held,
					frameBytes() + rig.renderer.scratchBytes(SCREEN_W), held.stagedBytes);
			assertFalse(rig.presenter.hasShownImage());
			release.countDown();
			assertTrue("stopped once the band finished", rig.player.awaitStopped(5000));
			RawVolumeStore.CacheStats after = rig.stats();
			assertEquals(0, after.pinnedBytes);
			assertEquals(0, after.stagedBytes);
			assertNotEquals(RawStrictPlayer.Outcome.PRESENTED, second.get(5, TimeUnit.SECONDS).outcome);
		} finally {
			release.countDown();
		}
	}

	@Test
	public void closingThePlayerReleasesTheShownImageAndShowsNothingMore() throws Exception {
		Rig rig = new Rig();
		AffineTransform3D view = view(0.5, 0);
		rig.present(request(1, 0, view));
		assertEquals(frameBytes(), rig.stats().stagedBytes);
		rig.player.close();
		assertTrue(rig.player.awaitStopped(5000));
		assertEquals("the shown image is released", 0, rig.stats().stagedBytes);
		assertFalse(rig.presenter.isActive());
		assertFalse(rig.presenter.hasShownImage());
		for (int p : rig.paint().getRGB(0, 0, SCREEN_W, SCREEN_H, null, 0, SCREEN_W)) assertEquals("nothing is drawn", 0, p);
		RawStrictPlayer.Result after = rig.player.request(request(2, 1, view)).get(10, TimeUnit.SECONDS);
		assertEquals(RawStrictPlayer.Outcome.FAILED, after.outcome);
		assertNotNull(after.cause);
	}

	/* ---------------------------------------------------------------- the plan (S7) */

	/**
	 * Agent-B's S7: a frame reads its lease's cells and nothing else. Leaving out of the plan a cell
	 * the frame certainly samples - the one under the screen centre - fails it, counted against it;
	 * the full plan reads nothing outside.
	 */
	@Test
	public void aFrameReadsNothingOutsideItsPlan() throws Exception {
		Rig rig = new Rig();
		AffineTransform3D view = view(0.5, 0);
		final RawFrameRequest r = request(1, 0, view);
		RawFrameRequest.Layer left = r.layers.get(0);
		double[] p = new double[3];
		r.sourceToScreen(left).inverse().apply(new double[] { SCREEN_W / 2, IMAGE_ROWS / 2, 0 }, p);
		int strips = (RawFrameFixture.H + RawFrameFixture.ROWS - 1) / RawFrameFixture.ROWS;
		final int centre = (int) Math.floor(p[1]) / RawFrameFixture.ROWS + strips * (int) Math.floor(p[2]);
		RawStrictPlayer.coverageForTest = cells -> {
			assertTrue("the plan holds the centre's cell", cells.get(centre));
			cells.clear(centre);
		};
		RawStrictPlayer.Result missed = rig.player.request(r).get(10, TimeUnit.SECONDS);
		assertEquals(missed.toString(), RawStrictPlayer.Outcome.FAILED, missed.outcome);
		assertTrue(String.valueOf(missed.cause), missed.cause instanceof TiffRawVolumeStore.CoverageMissException);
		assertTrue(missed.coverageMisses > 0);
		assertFalse(rig.presenter.waiting());
		assertEquals(0, rig.stats().pinnedBytes);
		assertEquals(0, rig.stats().stagedBytes);

		RawStrictPlayer.coverageForTest = null;
		RawStrictPlayer.Result whole = rig.present(request(2, 0, view));
		assertEquals(0, whole.coverageMisses);
		rig.assertCommittedImageOf(whole.request);
	}

	/* ---------------------------------------------------------------- prefetch (C3.5) */

	private void awaitPrefetch(Rig rig, long started) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10000;
		while (System.currentTimeMillis() < deadline) {
			long[] c = rig.player.prefetchCounts();
			if (c[0] > started && rig.stats().pinnedBytes > 0) return;
			Thread.sleep(5);
		}
		fail("the prefetch did not complete");
	}

	/** A prefetched frame's cells are read ahead; asking for the frame then reads nothing more. */
	@Test
	public void aPrefetchedFrameReadsNothingWhenItIsAskedFor() throws Exception {
		Rig rig = new Rig();
		AffineTransform3D view = view(0.5, 0);
		rig.present(request(1, 0, view));
		RawFrameRequest next = request(2, 1, view);
		rig.player.prefetch(request(0, 1, view));		// the same frame, made ahead, its revision unknown yet
		awaitPrefetch(rig, 0);
		long loads = rig.stats().loads;
		assertTrue("its cells are pinned ahead: " + rig.stats(), rig.stats().pinnedBytes > 0);
		RawStrictPlayer.Result result = rig.present(next);
		assertTrue(result.prefetched);
		assertEquals("nothing read when it was asked for", loads, rig.stats().loads);
		assertEquals(0, result.coverageMisses);
		rig.assertCommittedImageOf(next);
		assertEquals(0, rig.stats().pinnedBytes);
		assertEquals(1, rig.player.prefetchCounts()[1]);
	}

	/**
	 * The summary gives each stage's median over the frames presented: a superseded frame is not
	 * one of them, and nothing is given before a frame was presented.
	 */
	@Test
	public void theSummaryGivesEachStagesMedianOverPresentedFrames() throws Exception {
		Rig rig = new Rig();
		assertNull(rig.player.stageMedianMillis());
		assertFalse(rig.player.summary(), rig.player.summary().contains("median"));
		List<RawStrictPlayer.Result> presented = new ArrayList<RawStrictPlayer.Result>();
		presented.add(rig.present(request(1, 0, view(0.5, 0))));
		CompletableFuture<RawStrictPlayer.Result> dropped = rig.player.request(request(2, 1, view(0.5, 0)));
		rig.awaitHandedOver();
		presented.add(rig.present(request(3, 2, view(0.5, 0))));
		assertEquals(RawStrictPlayer.Outcome.SUPERSEDED, dropped.get(5, TimeUnit.SECONDS).outcome);
		presented.add(rig.present(request(4, 0, view(0.4, 0))));
		double[] medians = rig.player.stageMedianMillis();
		long[][] stages = new long[4][presented.size()];
		for (int i = 0; i < presented.size(); i++) {
			RawStrictPlayer.Result r = presented.get(i);
			assertEquals(RawStrictPlayer.Outcome.PRESENTED, r.outcome);
			stages[0][i] = r.queueNanos;
			stages[1][i] = r.prepareNanos;
			stages[2][i] = r.renderNanos;
			stages[3][i] = r.presentNanos;
		}
		double[] tail = rig.player.stageMillis(0.95);
		for (int k = 0; k < 4; k++) {
			java.util.Arrays.sort(stages[k]);
			assertEquals("stage " + k, stages[k][1] / 1e6, medians[k], 1e-9);
			assertEquals("stage " + k + ", the slowest of three", stages[k][2] / 1e6, tail[k], 1e-9);
		}
		assertTrue(rig.player.summary(), rig.player.summary().contains("median (95%) of the last 3 presented"));
	}

	/** A prefetch supersedes nothing: a frame waiting for paint stays, and is presented. */
	@Test
	public void aPrefetchSupersedesNothing() throws Exception {
		Rig rig = new Rig();
		AffineTransform3D view = view(0.5, 0);
		rig.present(request(1, 0, view));
		CompletableFuture<RawStrictPlayer.Result> waiting = rig.player.request(request(2, 1, view));
		rig.awaitHandedOver();
		rig.player.prefetch(request(0, 2, view));
		awaitPrefetch(rig, 0);
		assertTrue("the waiting frame is still waiting", rig.presenter.waiting());
		rig.paint();
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, waiting.get(5, TimeUnit.SECONDS).outcome);
	}

	/** A prefetch for one time point is released when another is asked for. */
	@Test
	public void aPrefetchOfAnotherTimePointIsReleased() throws Exception {
		Rig rig = new Rig();
		AffineTransform3D view = view(0.5, 0);
		rig.present(request(1, 0, view));
		rig.player.prefetch(request(0, 1, view));
		awaitPrefetch(rig, 0);
		RawStrictPlayer.Result other = rig.present(request(2, 2, view));
		assertFalse(other.prefetched);
		assertEquals("nothing pinned once the other frame is shown", 0, rig.stats().pinnedBytes);
		assertEquals(1, rig.player.prefetchCounts()[3]);
	}

	/**
	 * A prefetch the budget cannot hold beside what is staged is dropped, never refused: made while
	 * a frame still waits for paint (two images held), it does not fit; the frame asked for later,
	 * beside one image, is shown.
	 */
	@Test
	public void aPrefetchThatDoesNotFitIsDroppedNotRefused() throws Exception {
		final int w = 16, h = 16;
		long[] dims = f.dims();
		AffineTransform3D whole = RawFrameFixture.view(dims, 0.5, 0, w, h);
		RawFrameRequest shown = f.request(1, 0, whole, Interpolation.NLINEAR, -1000, 2500, w, h);
		RawFrameRequest waiting = f.request(2, 1, whole, Interpolation.NLINEAR, -1000, 2500, w, h);
		RawFrameRequest later = f.request(3, 2, whole, Interpolation.NLINEAR, -1000, 2500, w, h);
		int cells = Math.max(Math.max(cellsOf(shown).cardinality(), cellsOf(waiting).cardinality()), cellsOf(later).cardinality());
		long frame = 4L * w * h, cell = f.cell, scratch = new RawFrameRenderer(2).scratchBytes(w);
		assertTrue("the render scratch is smaller than a cell", scratch < cell);
		// a frame asked for beside one shown image fits (cells, two images, scratch, a read buffer);
		// a prefetch beside two images and the room kept for reads does not
		long budget = cells * cell + 2 * frame + scratch + cell + (cell - scratch) / 2;
		assertTrue(budget < cells * cell + 2 * frame + 2 * cell);
		assertTrue(budget >= TiffRawVolumeStore.minimumBudget(cell, 1));
		Rig rig = new Rig(budget, 1, w, h);
		rig.present(shown);
		CompletableFuture<RawStrictPlayer.Result> second = rig.player.request(waiting);
		rig.awaitHandedOver();
		rig.player.prefetch(f.request(0, 2, whole, Interpolation.NLINEAR, -1000, 2500, w, h));
		long deadline = System.currentTimeMillis() + 10000;
		while (rig.player.prefetchCounts()[2] == 0 && System.currentTimeMillis() < deadline) Thread.sleep(5);
		assertEquals("dropped as not fitting", 1, rig.player.prefetchCounts()[2]);
		assertEquals(0, rig.stats().pinnedBytes);
		rig.paint();
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, second.get(5, TimeUnit.SECONDS).outcome);
		RawStrictPlayer.Result next = rig.present(later);
		assertFalse(next.prefetched);
		assertTrue(rig.stats().peakTotalBytes <= rig.stats().budgetBytes);
	}

	/** Closing releases a prefetched lease. */
	@Test
	public void closingReleasesAPrefetchedLease() throws Exception {
		Rig rig = new Rig();
		AffineTransform3D view = view(0.5, 0);
		rig.present(request(1, 0, view));
		rig.player.prefetch(request(0, 1, view));
		awaitPrefetch(rig, 0);
		rig.player.close();
		assertTrue(rig.player.awaitStopped(5000));
		assertEquals(0, rig.stats().pinnedBytes);
		assertEquals(0, rig.stats().stagedBytes);
	}
}
