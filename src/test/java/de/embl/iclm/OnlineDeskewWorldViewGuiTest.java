package de.embl.iclm;

import static org.junit.Assert.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import bdv.viewer.Interpolation;
import bdv.viewer.ViewerPanel;
import net.imglib2.realtransform.AffineTransform3D;

/** Toolbar and navigation on a real Swing/BDV canvas. Enable with -Dopm.test.gui=true. */
public class OnlineDeskewWorldViewGuiTest {

	@Rule public TemporaryFolder folder = new TemporaryFolder();
	private OnlineDeskewSession session;
	private TiffRawVolumeStore reference;
	private final List<Throwable> uncaught = Collections.synchronizedList(new ArrayList<Throwable>());
	private Thread.UncaughtExceptionHandler handler;
	private final AtomicBoolean edtRead = new AtomicBoolean();
	private CountDownLatch release;

	@Before public void displayRequired() {
		Assume.assumeTrue("Enable with -Dopm.test.gui=true", Boolean.getBoolean("opm.test.gui"));
		handler = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((thread, error) -> { uncaught.add(error); error.printStackTrace(); });
	}

	@After public void close() throws Exception {
		if (!Boolean.getBoolean("opm.test.gui")) return;
		if (release != null) release.countDown();
		RawStrictPlayer.beforeHandOverForTest = null;
		OnlineDeskewSession.openingForTest = null;
		if (session != null) {
			edt(() -> { session.close(); return null; });
			OnlineDeskewSession.CloseResult result = session.closed().get(15, TimeUnit.SECONDS);
			assertTrue(result.toString(), result.drained);
			assertEquals(0, result.store.after.stagedBytes);
			assertEquals(0, result.store.after.pinnedBytes);
		}
		if (reference != null) reference.closeAsync().get(10, TimeUnit.SECONDS);
		edt(() -> null);
		Thread.setDefaultUncaughtExceptionHandler(handler);
		assertFalse("no raw IO on Swing", edtRead.get());
		assertTrue("uncaught exceptions: " + uncaught, uncaught.isEmpty());
	}

	private void open(boolean incompleteLast) throws Exception {
		for (int t = 0; t < 3; t++) {
			final int time = t;
			RawTiffFixture.write(new File(folder.getRoot(), "v_Time00000" + (t + 1) + "_Channel0001_Frames_1_20.tiff"),
					64, 40, 20, 8, incompleteLast && t == 2 ? 19 : 20,
					(x, y, k) -> y < 6 ? 65535 : 500 + time * 1000 + x * 11 + y * 17 + k * 31);
		}
		File csv = new File(folder.getRoot(), "alignment.csv");
		double theta = .07;
		assertTrue(AlignmentMatrixSet.legacy(new double[][] {
			{ Math.cos(theta), -Math.sin(theta), 2 }, { Math.sin(theta), Math.cos(theta), -1 } }).save(csv));
		OnlineDeskewView.Settings settings = new OnlineDeskewView.Settings();
		settings.folder = folder.getRoot().getAbsolutePath(); settings.parameterSource = "manual";
		settings.angle = -33.5; settings.budgetMb = 64; settings.alignmentCsv = csv.getAbsolutePath();
		session = OnlineDeskewSession.open(settings);
		edt(() -> { session.frame().setSize(520, 420); return null; });
		// Let the real layout and component-resized event finish before freezing a viewport.
		edt(() -> null); edt(() -> null);
		session.store().setReadHook(cell -> { if (SwingUtilities.isEventDispatchThread()) edtRead.set(true); });
		reference = new TiffRawVolumeStore(64L << 20, 1024, 2, null);
	}

	static <T> T edt(Callable<T> task) throws Exception {
		AtomicReference<T> value = new AtomicReference<T>();
		AtomicReference<Throwable> error = new AtomicReference<Throwable>();
		SwingUtilities.invokeAndWait(() -> { try { value.set(task.call()); } catch (Throwable x) { error.set(x); } });
		if (error.get() != null) throw new AssertionError("EDT task", error.get());
		return value.get();
	}

	private static JButton button(Container parent, String name) {
		for (Component c : parent.getComponents()) {
			if (c instanceof JButton && name.equals(((JButton) c).getText())) return (JButton) c;
			if (c instanceof Container) { JButton found = button((Container) c, name); if (found != null) return found; }
		}
		return null;
	}

	private CompletableFuture<RawStrictPlayer.Result> click(RawWorldView plane) throws Exception {
		edt(() -> {
			JButton b = button(session.frame(), plane.label);
			assertNotNull("toolbar button " + plane, b); assertTrue(b.isEnabled()); b.doClick(0);
			return null;
		});
		return edt(session::navigationResult); // the coalesced capture runs after the action
	}

	private RawStrictPlayer.Result presented(CompletableFuture<RawStrictPlayer.Result> f) throws Exception {
		RawStrictPlayer.Result r = f.get(20, TimeUnit.SECONDS);
		assertEquals(r.toString(), RawStrictPlayer.Outcome.PRESENTED, r.outcome);
		assertEquals(0, r.coverageMisses);
		return r;
	}

	private RawStrictPlayer.Result show(int time) throws Exception {
		return presented(edt(() -> session.requestStrictFrame(time)));
	}

	private CountDownLatch holdNextTimeOne() {
		CountDownLatch entered = new CountDownLatch(1); release = new CountDownLatch(1);
		AtomicBoolean held = new AtomicBoolean();
		RawStrictPlayer.beforeHandOverForTest = r -> {
			if (r.timeIndex == 1 && held.compareAndSet(false, true)) {
				entered.countDown();
				try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("held frame deadline"); }
				catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
			}
		};
		return entered;
	}

	@Test public void actualButtonsPreserveCentreZoomSourcesAndTimeAndPaintEachWorldPlane() throws Exception {
		open(false);
		ViewerPanel viewer = session.handle().getViewerPanel();
		// Progressive actions do not start the internal completed mode.
		click(RawWorldView.XZ); assertNull(session.player()); click(RawWorldView.XY);
		RawFrameRequest original = show(1).request;
		double[] centre = { original.width / 2.0, original.height / 2.0, 0 }, anchor = new double[3];
		original.viewer.inverse().apply(centre, anchor);
		double scale = original.viewer.get(0, 0);
		List<Integer> hashes = new ArrayList<Integer>();
		for (Interpolation interpolation : Interpolation.values()) {
			edt(() -> { viewer.state().setInterpolation(interpolation); return null; });
			for (RawWorldView plane : RawWorldView.values()) {
				RawFrameRequest r = presented(click(plane)).request;
				assertEquals(1, r.timeIndex); assertEquals(interpolation, r.interpolation); assertEquals(2, r.layers.size());
				RawWorldViewTest.assertPlane(plane, r.viewer, scale);
				double[] position = new double[3]; r.viewer.apply(anchor, position); assertArrayEquals(centre, position, 1e-8);
				for (int i = 0; i < 2; i++) {
					assertArrayEquals(original.layers.get(i).sourceToWorld.getRowPackedCopy(), r.layers.get(i).sourceToWorld.getRowPackedCopy(), 0);
					assertEquals(original.layers.get(i).argb, r.layers.get(i).argb);
					assertEquals(original.layers.get(i).min, r.layers.get(i).min, 0);
					assertEquals(original.layers.get(i).max, r.layers.get(i).max, 0);
				}
				BufferedImage canvas = edt(() -> {
					Component c = viewer.getDisplayComponent();
					BufferedImage image = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_ARGB);
					java.awt.Graphics2D g = image.createGraphics(); c.paint(g); g.dispose(); return image;
				});
				int hash = 0, coloured = 0;
				for (int y = 20; y < r.height - 50; y += 13)
					for (int x = 20; x < r.width - 20; x += 13) {
						int rgb = canvas.getRGB(x, y);
						RawFrameParityTest.assertLegalPixel(reference, r, x, y, rgb);
						hash = 31 * hash + rgb; if ((rgb & 0xffffff) != 0) coloured++;
					}
				assertTrue("the plane has data", coloured > 10); hashes.add(hash);
			}
		}
		assertNotEquals(hashes.get(0), hashes.get(1)); assertNotEquals(hashes.get(1), hashes.get(2));
	}

	@Test public void rapidPlanesRetainPendingTimeAndNeverCommitObsoleteView() throws Exception {
		open(false); RawFrameRequest shown = show(0).request;
		CountDownLatch entered = holdNextTimeOne();
		CompletableFuture<RawStrictPlayer.Result> old = edt(() -> session.requestStrictFrame(1));
		assertTrue(entered.await(10, TimeUnit.SECONDS));
		List<CompletableFuture<RawStrictPlayer.Result>> asks = new ArrayList<CompletableFuture<RawStrictPlayer.Result>>();
		for (RawWorldView plane : RawWorldView.values()) asks.add(click(plane));
		assertSame(shown, edt(() -> session.presenter().shownRequest()));
		release.countDown();
		assertEquals(RawStrictPlayer.Outcome.SUPERSEDED, old.get(10, TimeUnit.SECONDS).outcome);
		assertTrue("replaced before submission", asks.get(0).isCancelled());
		assertTrue("replaced before submission", asks.get(1).isCancelled());
		RawFrameRequest last = presented(asks.get(2)).request;
		assertEquals(1, last.timeIndex); assertEquals(0, last.viewer.get(0, 0), 0);
		assertTrue(last.viewer.get(0, 1) > 0); assertEquals(2, session.player().count(RawStrictPlayer.Outcome.PRESENTED));
	}

	@Test public void unavailableTargetInvalidatesOldWorkAndKeepsTheShownImage() throws Exception {
		open(true); RawFrameRequest shown = show(0).request;
		CountDownLatch entered = holdNextTimeOne();
		CompletableFuture<RawStrictPlayer.Result> old = edt(() -> session.requestStrictFrame(1));
		assertTrue(entered.await(10, TimeUnit.SECONDS));
		CompletableFuture<RawStrictPlayer.Result> unavailable = edt(() -> session.requestStrictFrame(2));
		assertThrows(java.util.concurrent.ExecutionException.class, () -> unavailable.get(10, TimeUnit.SECONDS));
		assertThrows(java.util.concurrent.ExecutionException.class, () -> click(RawWorldView.YZ).get(10, TimeUnit.SECONDS));
		release.countDown();
		assertEquals(RawStrictPlayer.Outcome.SUPERSEDED, old.get(10, TimeUnit.SECONDS).outcome);
		assertSame(shown, edt(() -> session.presenter().shownRequest()));
		assertTrue(edt(session::navigationNote).contains("unavailable"));
	}

	@Test public void resizeReissuesLatestTimeAndCloseCancelsPreparation() throws Exception {
		open(false); show(0);
		CountDownLatch entered = holdNextTimeOne();
		CompletableFuture<RawStrictPlayer.Result> old = edt(() -> session.requestStrictFrame(1));
		assertTrue(entered.await(10, TimeUnit.SECONDS));
		edt(() -> { session.frame().setSize(570, 460); return null; });
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (edt(session::navigationResult) == old && System.nanoTime() < deadline) Thread.sleep(10);
		CompletableFuture<RawStrictPlayer.Result> resized = edt(session::navigationResult);
		assertNotSame(old, resized); release.countDown();
		RawFrameRequest next = presented(resized).request;
		assertEquals(1, next.timeIndex);
		assertEquals((int) edt(() -> session.handle().getViewerPanel().getDisplayComponent().getWidth()), next.width);
		assertEquals(RawStrictPlayer.Outcome.SUPERSEDED, old.get(10, TimeUnit.SECONDS).outcome);
		entered = holdNextTimeOne();
		CompletableFuture<RawStrictPlayer.Result> closing = click(RawWorldView.XZ);
		assertTrue(entered.await(10, TimeUnit.SECONDS));
		edt(() -> { session.close(); return null; }); // returns while the worker is held
		assertFalse(session.closed().isDone()); release.countDown();
		assertNotEquals(RawStrictPlayer.Outcome.PRESENTED, closing.get(10, TimeUnit.SECONDS).outcome);
		assertFalse(edt(() -> session.worldAction(RawWorldView.XY).isEnabled()));
	}

	@Test public void postedPresentationFromStoppedPlayerCannotMoveNewPlayersSlider() throws Exception {
		open(false); show(0);
		CountDownLatch entered = holdNextTimeOne();
		CompletableFuture<RawStrictPlayer.Result> old = edt(() -> session.requestStrictFrame(1));
		assertTrue(entered.await(10, TimeUnit.SECONDS));
		CompletableFuture<Void> restarted = old.thenAccept(result -> {
			assertTrue(SwingUtilities.isEventDispatchThread());
			session.stopStrictPlayback();
			session.handle().getViewerPanel().setTimepoint(2);
			session.strictPlayer();
		});
		release.countDown(); restarted.get(10, TimeUnit.SECONDS);
		edt(() -> null); edt(() -> null);
		assertEquals(2, (int) edt(() -> session.handle().getViewerPanel().state().getCurrentTimepoint()));
		assertNull(edt(() -> session.presenter().shownRequest()));
	}

	@Test public void programmaticSliderFollowIsMarkedAndCannotTriggerAFeedbackRequest() throws Exception {
		open(false); show(0);
		AtomicBoolean followed = new AtomicBoolean(); AtomicBoolean requestedAgain = new AtomicBoolean();
		edt(() -> {
			session.handle().getViewerPanel().addTimePointListener(t -> {
				if (session.followingPresentation()) followed.set(true);
				else requestedAgain.set(true); // the branch the future playback controller must use
			}); return null;
		});
		requestedAgain.set(false); show(1); edt(() -> null); edt(() -> null);
		assertTrue(followed.get()); assertFalse(requestedAgain.get());
		assertEquals(2, session.player().count(RawStrictPlayer.Outcome.PRESENTED));
	}

	private void holdValidation() {
		CountDownLatch locked = new CountDownLatch(1); release = new CountDownLatch(1);
		OnlineDeskewSession.openingForTest = s -> {
			RawAcquisition.Entry pending = s.acquisition().entry(s.times().get(1), 1);
			Shutdown.daemon(() -> {
				synchronized (pending) { // hold only validation's monitor; metadata reads must stay non-blocking
					locked.countDown();
					try { if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("validation deadline"); }
					catch (InterruptedException e) { throw new AssertionError(e); }
				}
			}, "test-hold-validation").start();
			try { assertTrue(locked.await(5, TimeUnit.SECONDS)); }
			catch (InterruptedException e) { throw new AssertionError(e); }
		};
	}

	@Test public void pendingValidationNeverBlocksTheEdtAndOnlyLatestTargetResumes() throws Exception {
		holdValidation();
		open(false); RawFrameRequest shown = show(0).request;
		CompletableFuture<RawStrictPlayer.Result> first = edt(() -> session.requestStrictFrame(1));
		assertFalse(first.isDone()); assertTrue(edt(session::navigationNote).startsWith("Checking"));
		CompletableFuture<RawStrictPlayer.Result> later = edt(() -> session.requestStrictFrame(2));
		CompletableFuture<RawStrictPlayer.Result> plane = click(RawWorldView.XZ);
		assertThrows(java.util.concurrent.CancellationException.class, () -> first.get(1, TimeUnit.SECONDS));
		assertThrows(java.util.concurrent.CancellationException.class, () -> later.get(1, TimeUnit.SECONDS));
		assertSame(shown, edt(() -> session.presenter().shownRequest()));
		release.countDown(); RawFrameRequest result = presented(plane).request;
		assertEquals(2, result.timeIndex); assertTrue(result.viewer.get(1, 2) > 0);
		assertEquals(2, session.player().count(RawStrictPlayer.Outcome.PRESENTED));
	}

	@Test public void cancellationCallbackCannotRestartPlaybackHalfwayThroughStop() throws Exception {
		holdValidation(); open(false); show(0);
		CompletableFuture<RawStrictPlayer.Result> waiting = edt(() -> session.requestStrictFrame(1));
		AtomicReference<CompletableFuture<RawStrictPlayer.Result>> reentrant = new AtomicReference<>();
		waiting.whenComplete((result, failure) -> reentrant.set(session.requestStrictFrame(2)));
		edt(() -> { session.stopStrictPlayback(); return null; });
		assertTrue(waiting.isCancelled()); assertNotNull(reentrant.get()); assertTrue(reentrant.get().isCancelled());
		assertNull(session.player()); assertNull(session.presenter()); assertNull(edt(session::navigationResult));
		release.countDown();
	}

	@Test public void closingFromAnotherThreadRejectsRequestsBeforeEdtDetachRuns() throws Exception {
		open(false); show(0);
		edt(() -> {
			Thread close = Shutdown.daemon(session::close, "test-close"); close.start(); close.join(2000);
			assertFalse(close.isAlive()); assertTrue(session.isClosing());
			assertTrue("terminal before the queued Swing detach", session.requestStrictFrame(1).isCancelled());
			return null;
		});
	}

	@Test public void invalidCameraIsReportedWithoutChangingTheCurrentRequest() throws Exception {
		open(false); show(0);
		AffineTransform3D shear = new AffineTransform3D();
		shear.set(1, .2, 0, 100, 0, 1, 0, 100, 0, 0, 1, 0);
		edt(() -> { session.handle().getViewerPanel().state().setViewerTransform(shear); return null; });
		CompletableFuture<RawStrictPlayer.Result> previous = edt(session::navigationResult);
		presented(previous); // the ordinary camera edit now redraws before the invalid world action
		assertSame(previous, click(RawWorldView.YZ));
		assertArrayEquals(shear.getRowPackedCopy(), edt(() -> session.handle().getViewerPanel().state().getViewerTransform().getRowPackedCopy()), 0);
		assertTrue(edt(session::navigationNote).startsWith("Cannot orient view"));
	}
}
