package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.tools.brightness.ConverterSetup;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerPanel;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * The Ready -> Presented spike in a real Online Deskew View (joint decision D2; contracts C6.2,
 * C6.3): completed-frame playback on BigDataViewer's own canvas, painted by Swing. Needs a display:
 * {@code mvn test -Dtest=OnlineDeskewStrictGuiTest -Dopm.test.gui=true}.
 *
 * <ul>
 * <li>A frame is acknowledged by the canvas's own paint, and what the canvas then shows is that
 *     request's image with that request's time label; BigDataViewer's sources draw nothing under it.</li>
 * <li>Consecutive time points under one view, then rapid changes of time, view and display range
 *     under delayed reads: the newest request is shown, frames are presented in order, each one its
 *     own request's image, and the label follows what is shown.</li>
 * <li>A file rewritten under the shown frame takes it off the canvas, found by the view's own check.</li>
 * <li>Closing the view releases the staged frame with everything else.</li>
 * <li><b>An exception thrown on the event thread or a renderer thread fails the test</b>, as in
 *     {@link OnlineDeskewSessionGuiTest}.</li>
 * </ul>
 */
public class OnlineDeskewStrictGuiTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final int W = 64, H = 40, D = 20;
	private final List<Throwable> uncaught = Collections.synchronizedList(new ArrayList<Throwable>());
	private Thread.UncaughtExceptionHandler previousHandler;
	private TiffRawVolumeStore reference;
	private RawFrameRenderer referenceRenderer;

	@Before
	public void needsADisplay() {
		Assume.assumeTrue("Enable with -Dopm.test.gui=true", Boolean.getBoolean("opm.test.gui"));
		previousHandler = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
			uncaught.add(new AssertionError("uncaught on " + thread.getName() + ": " + e, e));
			e.printStackTrace();
		});
		referenceRenderer = new RawFrameRenderer(2);
	}

	@After
	public void noExceptionEscapedOnAnotherThread() throws Exception {
		if (!Boolean.getBoolean("opm.test.gui")) return;
		SwingUtilities.invokeAndWait(() -> {});
		Thread.setDefaultUncaughtExceptionHandler(previousHandler);
		referenceRenderer.close();
		if (reference != null) reference.closeAsync().get(10, TimeUnit.SECONDS);
		if (!uncaught.isEmpty()) throw (AssertionError) uncaught.get(0);
	}

	private OnlineDeskewView.Settings fixtureFolder() throws Exception {
		for (int t = 1; t <= 3; t++) {
			final int base = 600 + 900 * (t - 1);
			RawTiffFixture.write(new File(folder.getRoot(), String.format("v_Position0001_Time%06d_Channel0001_Frames_1_%d.tiff", t, D)),
					W, H, D, 8, D, (x, y, k) -> base + (x * 7 + y * 13 + k * 29) % 500);
		}
		try (FileWriter w = new FileWriter(new File(folder.getRoot(), "ExperimentalParameters.txt"))) {
			w.write("Interval: 5 Minute\nTilt angle: 33.5\num per galvo DU: 0.01325\nGalvo DUs per step: 20\n"
					+ "Pixel size at object /um: 0.116\nCamera ROI X0, X1, Y0, Y1: 0 " + W + " 0 " + H + "\n");
		}
		OnlineDeskewView.Settings s = new OnlineDeskewView.Settings();
		s.folder = folder.getRoot().getAbsolutePath();
		s.budgetMb = 64;
		reference = new TiffRawVolumeStore(64L << 20, 2L * W * 8, 2, null);
		return s;
	}

	private static <T> T onEventThread(Callable<T> read) throws Exception {
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

	/** What the viewer's canvas draws now - BigDataViewer's image and every overlay - painted into an image. */
	private static BufferedImage canvas(OnlineDeskewSession session) throws Exception {
		return onEventThread(() -> {
			Component c = session.handle().getViewerPanel().getDisplayComponent();
			BufferedImage image = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = image.createGraphics();
			c.paint(g);
			g.dispose();
			return image;
		});
	}

	/** An image as the presenter paints it over black: the rows above its label. */
	private static int[] overBlack(BufferedImage frame) {
		BufferedImage screen = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = screen.createGraphics();
		g.setColor(Color.BLACK);
		g.fillRect(0, 0, frame.getWidth(), frame.getHeight());
		g.drawImage(frame, 0, 0, null);
		g.dispose();
		return rows(screen);
	}

	private static int[] rows(BufferedImage image) {
		int h = image.getHeight() - 44;
		return image.getRGB(0, 0, image.getWidth(), h, null, 0, image.getWidth());
	}

	/** The request's frame drawn independently, from a store of its own. */
	private int[] referenceOf(RawFrameRequest r) throws Exception {
		BufferedImage image = referenceRenderer.render(r, reference::blocking, () -> false);
		return image.getRGB(0, 0, r.width, r.height, null, 0, r.width);
	}

	private static int[] all(BufferedImage image) {
		return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
	}

	/** A request for time index {@code t} as the view shows it now, from the test thread, which may check the file. */
	private static RawFrameRequest request(OnlineDeskewSession session, int t) {
		RawFrameRequest r = session.frameRequest(t);
		assertNotNull("time index " + t + " can be shown", r);
		return r;
	}

	private static boolean allSourcesAbsent(OnlineDeskewSession session, int t) {
		for (SourceAndConverter<?> soc : session.handle().getViewerPanel().state().getSources()) {
			Source<?> s = soc.getSpimSource();
			if (s.isPresent(t)) return false;
		}
		return true;
	}

	/**
	 * Close, and check that the staged frame went with everything the view owns. A cell lent to
	 * BigDataViewer's own volatile view of a file found changed stays charged as lent until the
	 * collector clears the last access holding it (contract C1 of M1), so that alone may remain.
	 */
	private static void closeAndWait(OnlineDeskewSession session) throws Exception {
		SwingUtilities.invokeAndWait(() -> session.frame().dispose());
		OnlineDeskewSession.CloseResult result = session.closed().get(15, TimeUnit.SECONDS);
		assertTrue("closed within its deadlines: " + result, result.drained);
		RawVolumeStore.CacheStats after = result.store.after;
		assertEquals("no staged frame after the close: " + result, 0, after.stagedBytes);
		assertEquals("no pin after the close: " + result, 0, after.pinnedBytes);
		assertEquals("nothing else accounted after the close but lent cells: " + result, after.lentBytes, after.totalBytes());
	}

	@Test
	public void aFrameIsPresentedByTheCanvasesOwnPaintAndIsWhatTheCanvasShows() throws Exception {
		final OnlineDeskewSession session = OnlineDeskewSession.open(fixtureFolder());
		try {
			RawStrictPlayer player = onEventThread(session::strictPlayer);
			assertNotNull(player);
			RawFrameRequest r = request(session, 0);
			assertEquals(2, r.layers.size());
			RawStrictPlayer.Result result = player.request(r).get(15, TimeUnit.SECONDS);
			System.out.println("first strict frame on the canvas: " + result);
			assertEquals(result.toString(), RawStrictPlayer.Outcome.PRESENTED, result.outcome);
			assertSame(r, result.presented.request);
			assertEquals(0, result.readsWhileDrawing);
			assertArrayEquals("the frame is its request's image", referenceOf(r), all(result.presented.image));
			assertArrayEquals("the canvas shows that frame", overBlack(result.presented.image), rows(canvas(session)));
			RawPresenter presenter = session.presenter();
			assertSame(r, onEventThread(presenter::shownRequest));
			assertTrue(onEventThread(presenter::label).startsWith("t = 1 " + r.entry.time + " frame 1"));
			// BigDataViewer's own sources draw nothing under the staged frame
			long deadline = System.currentTimeMillis() + 5000;
			while (!allSourcesAbsent(session, 0) && System.currentTimeMillis() < deadline) {
				session.handle().getViewerPanel().requestRepaint();
				Thread.sleep(20);
			}
			assertTrue("hidden from BigDataViewer while strict playback draws", allSourcesAbsent(session, 0));
			SwingUtilities.invokeAndWait(session::stopStrictPlayback);
			deadline = System.currentTimeMillis() + 5000;
			while (allSourcesAbsent(session, 0) && System.currentTimeMillis() < deadline) {
				session.handle().getViewerPanel().requestRepaint();
				Thread.sleep(20);
			}
			assertFalse("shown again by BigDataViewer once it stops", allSourcesAbsent(session, 0));
			assertEquals("its frame released when it stops", 0, session.store().stats().stagedBytes);
		} finally {
			closeAndWait(session);
		}
	}

	@Test
	public void consecutiveTimesThenRapidChangesShowTheNewestAndEveryFrameIsItsOwnRequest() throws Exception {
		final OnlineDeskewSession session = OnlineDeskewSession.open(fixtureFolder());
		try {
			final RawStrictPlayer player = onEventThread(session::strictPlayer);
			final ViewerPanel viewer = session.handle().getViewerPanel();
			final RawPresenter presenter = session.presenter();
			// three consecutive time points under one view: three frames, each shown in turn
			RawFrameRequest previous = null;
			for (int t = 0; t < 3; t++) {
				RawFrameRequest r = request(session, t);
				if (previous != null) {
					assertEquals("the same view", previous.viewer.toString(), r.viewer.toString());
					assertFalse("a different picture", java.util.Arrays.equals(referenceOf(previous), referenceOf(r)));
				}
				RawStrictPlayer.Result result = player.request(r).get(15, TimeUnit.SECONDS);
				assertEquals(RawStrictPlayer.Outcome.PRESENTED, result.outcome);
				assertSame(r, result.presented.request);
				assertEquals(t + 1, result.presented.delivered);
				assertArrayEquals(overBlack(result.presented.image), rows(canvas(session)));
				assertTrue(onEventThread(presenter::label).startsWith("t = " + (t + 1) + " " + r.entry.time));
				previous = r;
			}

			// then rapid changes of time, view and display range, with every read delayed
			session.store().setReadHook(cell -> {
				try {
					Thread.sleep(2);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			final AffineTransform3D start = viewer.state().getViewerTransform();
			Random random = new Random(11);
			List<CompletableFuture<RawStrictPlayer.Result>> results = new ArrayList<CompletableFuture<RawStrictPlayer.Result>>();
			for (int i = 0; i < 30; i++) {
				final int change = random.nextInt(3);
				final double amount = random.nextDouble();
				SwingUtilities.invokeAndWait(() -> {
					if (change == 1) {
						AffineTransform3D v = start.copy();
						v.translate(20 * (amount - 0.5), 10 * (amount - 0.5), 0);
						viewer.state().setViewerTransform(v);
					} else if (change == 2) {
						for (SourceAndConverter<?> soc : viewer.state().getSources()) {
							ConverterSetup setup = session.handle().getConverterSetups().getConverterSetup(soc);
							setup.setDisplayRange(400 * amount, 2000 + 2000 * amount);
						}
					}
				});
				results.add(player.request(request(session, i % 3)));
				Thread.sleep(random.nextInt(30));
			}
			RawFrameRequest newest = null;
			long lastDelivered = 3;
			int superseded = 0;
			for (CompletableFuture<RawStrictPlayer.Result> f : results) {
				RawStrictPlayer.Result result = f.get(30, TimeUnit.SECONDS);
				newest = result.request;
				if (result.outcome == RawStrictPlayer.Outcome.SUPERSEDED) {
					superseded++;
					continue;
				}
				assertEquals(result.toString(), RawStrictPlayer.Outcome.PRESENTED, result.outcome);
				assertEquals("presented in order, one count each", lastDelivered + 1, result.presented.delivered);
				assertEquals(0, result.readsWhileDrawing);
				assertArrayEquals("frame " + result.request.revision + " is its own request's image", referenceOf(result.request),
						all(result.presented.image));
				lastDelivered = result.presented.delivered;
			}
			RawStrictPlayer.Result last = results.get(results.size() - 1).get();
			System.out.println("rapid changes on the canvas: " + player.summary() + "; " + superseded + " superseded; "
					+ session.store().stats());
			assertEquals("the newest request is shown", RawStrictPlayer.Outcome.PRESENTED, last.outcome);
			assertSame(newest, onEventThread(presenter::shownRequest));
			assertArrayEquals(overBlack(last.presented.image), rows(canvas(session)));
			assertTrue(onEventThread(presenter::label).startsWith("t = " + (newest.timeIndex + 1) + " " + newest.entry.time));
			RawVolumeStore.CacheStats s = session.store().stats();
			assertEquals(0, s.pinnedBytes);
			assertEquals("one staged frame, the one on the canvas", 4L * newest.width * newest.height, s.stagedBytes);
			assertTrue(s.peakTotalBytes <= s.budgetBytes);
		} finally {
			session.store().setReadHook(null);
			closeAndWait(session);
		}
	}

	/** Rewrite the first plane of a file in place, same size, and move its time on. */
	private static void rewrite(RawAcquisition.Entry e, int value) throws Exception {
		RawTiffLayout layout = e.layout();
		ByteBuffer replacement = ByteBuffer.allocate(2 * layout.width * layout.height).order(ByteOrder.LITTLE_ENDIAN);
		while (replacement.hasRemaining()) replacement.putShort((short) value);
		replacement.flip();
		try (FileChannel out = FileChannel.open(e.file.toPath(), StandardOpenOption.WRITE)) {
			long at = layout.offset(0, 0, 0);
			while (replacement.hasRemaining()) at += out.write(replacement, at);
		}
		Files.setLastModifiedTime(e.file.toPath(), FileTime.fromMillis(e.key().modified + 2000));
	}

	@Test
	public void aFileRewrittenUnderTheShownFrameTakesItOffTheCanvas() throws Exception {
		final OnlineDeskewSession session = OnlineDeskewSession.open(fixtureFolder());
		try {
			final RawStrictPlayer player = onEventThread(session::strictPlayer);
			final RawPresenter presenter = session.presenter();
			RawFrameRequest r = request(session, 0);
			assertEquals(RawStrictPlayer.Outcome.PRESENTED, player.request(r).get(15, TimeUnit.SECONDS).outcome);
			rewrite(r.entry, 9);
			// the view's own check of the shown file finds it, about once a second
			long deadline = System.currentTimeMillis() + 6000;
			while (onEventThread(presenter::withdrawnRequest) == null && System.currentTimeMillis() < deadline) Thread.sleep(50);
			assertSame("taken off the canvas", r, onEventThread(presenter::withdrawnRequest));
			assertNull(onEventThread(presenter::shownImage));
			assertTrue(onEventThread(presenter::label).endsWith("withdrawn"));
			for (int p : rows(canvas(session))) assertEquals("nothing of the rewritten file is drawn", 0xff000000, p);
			RawStrictPlayer.Result next = player.request(request(session, 1)).get(15, TimeUnit.SECONDS);
			assertEquals(RawStrictPlayer.Outcome.PRESENTED, next.outcome);
			assertEquals("the withdrawn frame was counted once", 2, next.presented.delivered);
			assertArrayEquals(overBlack(next.presented.image), rows(canvas(session)));
		} finally {
			closeAndWait(session);
		}
	}
}
