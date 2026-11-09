package de.embl.iclm;

import static org.junit.Assert.*;

import java.awt.Component;
import java.awt.Container;
import java.awt.TextField;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import javax.swing.JButton;
import javax.swing.SwingUtilities;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.tools.brightness.ConverterSetup;
import bdv.ui.sourcetable.SourceTable;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.SourceGroup;
import bdv.viewer.SynchronizedViewerState;
import bdv.viewer.ViewerPanel;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;

/**
 * Changing the deskew parameters of an open Online Deskew View (user's request, 2026-11-09): the same window redraws
 * with the new geometry in both modes, keeps its sources' setups, Position, time and following, keeps the raw point
 * under the window centre where it was, and never commits a frame or an Auto range of the old geometry as the new
 * one. Requires {@code -Dopm.test.gui=true}.
 */
public class OnlineDeskewGeometryEditGuiTest {
	@Rule public TemporaryFolder folder = new TemporaryFolder();
	private final List<OnlineDeskewSession> sessions = new ArrayList<OnlineDeskewSession>();
	private final List<Throwable> uncaught = Collections.synchronizedList(new ArrayList<Throwable>());
	private Thread.UncaughtExceptionHandler handler;
	private CountDownLatch release;
	private double rememberedPosition;

	@Before public void display() {
		Assume.assumeTrue("Enable with -Dopm.test.gui=true", Boolean.getBoolean("opm.test.gui"));
		handler = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((thread, error) -> { uncaught.add(error); error.printStackTrace(); });
		rememberedPosition = ij.Prefs.get("opm.onlineDeskew.position", 1);
	}

	@After public void close() throws Exception {
		if (!Boolean.getBoolean("opm.test.gui")) return;
		try {
			if (release != null) release.countDown();
			RawFrameRenderer.rowHookForTest = null;
			OnlineDeskewAutoContrast.sampledHookForTest = null;
			for (OnlineDeskewSession s : sessions) {
				s.close();
				assertTrue(s.closed().get(15, TimeUnit.SECONDS).drained);
			}
		} finally {
			ij.Prefs.set("opm.onlineDeskew.position", rememberedPosition);
			Thread.setDefaultUncaughtExceptionHandler(handler);
		}
		assertTrue("uncaught exceptions: " + uncaught, uncaught.isEmpty());
	}

	private static <T> T edt(Callable<T> work) throws Exception {
		final Object[] result = new Object[1];
		final Throwable[] failure = new Throwable[1];
		SwingUtilities.invokeAndWait(() -> {
			try { result[0] = work.call(); } catch (Throwable t) { failure[0] = t; }
		});
		if (failure[0] != null) throw new AssertionError(failure[0]);
		@SuppressWarnings("unchecked") T out = (T) result[0];
		return out;
	}

	private static void until(String what, BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
		assertTrue(what, condition.getAsBoolean());
	}

	/** Two positions of four time points each, 33.5 degrees in the folder's parameter file. */
	private void write() throws Exception {
		for (int p = 1; p <= 2; p++) for (int t = 1; t <= 4; t++) {
			final int offset = p * 300;
			File f = new File(folder.getRoot(), String.format("g_Position%04d_Time%06d_Channel0001_Frames_1_20.tiff", p, t));
			RawTiffFixture.write(f, 64, 40, 20, 8, 20, (x, y, k) -> offset + 600 + x * 7 + y * 13 + k * 29 + (x * y) % 17);
			Files.setLastModifiedTime(f.toPath(), FileTime.fromMillis(System.currentTimeMillis() - 5000));
		}
		try (FileWriter out = new FileWriter(new File(folder.getRoot(), "ExperimentalParameters.txt"))) {
			out.write("Interval: 5 Minute\nTilt angle: 33.5\num per galvo DU: 0.01325\n"
					+ "Galvo DUs per step: 20\nPixel size at object /um: 0.116\n"
					+ "Camera ROI X0, X1, Y0, Y1: 0 64 0 40\n");
		}
	}

	private OnlineDeskewSession open(OnlineDeskewView.Settings settings) throws Exception {
		settings.folder = folder.getRoot().getAbsolutePath();
		settings.position = 1;
		settings.budgetMb = 64;
		OnlineDeskewSession session = OnlineDeskewSession.open(settings);
		sessions.add(session);
		edt(() -> { session.frame().setSize(560, 420); return null; });
		edt(() -> null); edt(() -> null);
		return session;
	}

	private OnlineDeskewSession open() throws Exception {
		return open(new OnlineDeskewView.Settings());		// parameters from the folder's file
	}

	/** {@code g} at another angle, as the toolbar's dialog makes it. */
	private static RawGeometry at(RawGeometry g, double angle) {
		return new RawGeometry(g.dzUm, g.dxyUm, angle, OnlineDeskewSession.EDITED_ORIGIN);
	}

	private static AffineTransform3D sourceToWorld(SourceAndConverter<?> soc, int t) {
		AffineTransform3D m = new AffineTransform3D();
		soc.getSpimSource().getSourceTransform(t, 0, m);
		return m;
	}

	/** What a source of {@code spec} must use under {@code g}, independently of the session. */
	private static AffineTransform3D expected(RawGeometry g, OnlineDeskewSession session, RawSourceSpec spec, int t) {
		RawAcquisition.Entry.Shown v = session.snapshot().entry(t, spec.acquisitionChannel).shown();
		return g.sourceToWorld(v.layout.dimensions(), spec, null);
	}

	/** Wait for the checker to publish every selected file of time index {@code t} of the axis shown now. */
	private static void published(OnlineDeskewSession session, int t) throws Exception {
		until("time index " + t + " is published", () -> {
			RawAcquisition.Snapshot s = session.snapshot();
			for (int c : s.channels) {
				RawAcquisition.Entry e = s.entry(t, c);
				if (e == null || e.shown() == null) return false;
			}
			return true;
		});
	}

	private static void assertSameMatrix(String what, AffineTransform3D expected, AffineTransform3D actual) {
		assertArrayEquals(what, expected.getRowPackedCopy(), actual.getRowPackedCopy(), 0);
	}

	@Test public void progressiveEditKeepsTheViewSetupAndRedrawsWithTheNewTransform() throws Exception {
		write();
		OnlineDeskewSession session = open();
		RawGeometry opened = session.geometry();
		assertEquals(33.5, opened.angleDeg, 0);
		ViewerPanel viewer = session.handle().getViewerPanel();
		SynchronizedViewerState state = viewer.state();
		SourceGroup group = new SourceGroup();
		List<ConverterSetup> setups = edt(() -> {
			viewer.setTimepoint(2);
			state.addGroup(group);
			state.setGroupName(group, "left only");
			state.addSourceToGroup(state.getSources().get(0), group);
			state.setSourceActive(state.getSources().get(1), false);
			state.setCurrentSource(state.getSources().get(0));
			List<ConverterSetup> s = new ArrayList<ConverterSetup>();
			for (SourceAndConverter<?> soc : state.getSources()) s.add(session.handle().getConverterSetups().getConverterSetup(soc));
			s.get(0).setDisplayRange(410, 1910);
			s.get(1).setColor(new ARGBType(0xff1030ff));
			return s;
		});
		published(session, 2);
		List<SourceAndConverter<?>> before = edt(() -> new ArrayList<SourceAndConverter<?>>(state.getSources()));
		List<AffineTransform3D> openedTransforms = new ArrayList<AffineTransform3D>();
		for (SourceAndConverter<?> soc : before) openedTransforms.add(sourceToWorld(soc, 2));
		AffineTransform3D camera = edt(state::getViewerTransform);
		int width = edt(() -> viewer.getDisplayComponent().getWidth()), height = edt(() -> viewer.getDisplayComponent().getHeight());

		String[] remembered = { "dz", "dxy", "angle", "parameterSource" };
		String[] before0 = new String[remembered.length];
		for (int i = 0; i < remembered.length; i++) before0[i] = ij.Prefs.get("opm.onlineDeskew." + remembered[i], "unset");
		RawGeometry steep = at(opened, 26);
		assertTrue(edt(() -> session.setGeometry(steep)));
		assertFalse("the same values again change nothing", edt(() -> session.setGeometry(at(steep, 26))));
		for (int i = 0; i < remembered.length; i++)
			assertEquals("not remembered: " + remembered[i], before0[i], ij.Prefs.get("opm.onlineDeskew." + remembered[i], "unset"));

		List<SourceAndConverter<?>> after = edt(() -> new ArrayList<SourceAndConverter<?>>(state.getSources()));
		assertEquals(before.size(), after.size());
		for (int i = 0; i < after.size(); i++) {
			SourceAndConverter<?> was = before.get(i), now = after.get(i);
			assertNotSame("a new source over the new geometry", was, now);
			assertSame("the setup is handed over, not recreated", setups.get(i), session.handle().getConverterSetups().getConverterSetup(now));
			assertSame("the same converter", was.getConverter(), now.getConverter());
			RawSourceSpec spec = ((RawPartSource<?>) now.getSpimSource()).spec();
			assertSame(((RawPartSource<?>) was.getSpimSource()).spec(), spec);
			assertSameMatrix("source " + i + " at 26 degrees", expected(steep, session, spec, 2), sourceToWorld(now, 2));
			assertFalse("the transform did change", java.util.Arrays.equals(openedTransforms.get(i).getRowPackedCopy(),
					sourceToWorld(now, 2).getRowPackedCopy()));
		}
		assertEquals(410, setups.get(0).getDisplayRangeMin(), 0);
		assertEquals(1910, setups.get(0).getDisplayRangeMax(), 0);
		assertEquals(0xff1030ff, setups.get(1).getColor().get());
		edt(() -> {
			assertTrue(state.isSourceActive(after.get(0)));
			assertFalse(state.isSourceActive(after.get(1)));
			assertSame(after.get(0), state.getCurrentSource());
			assertEquals(Collections.singleton(after.get(0)), state.getSourcesInGroup(group));
			assertEquals("the time point stays", 2, state.getCurrentTimepoint());
			assertEquals(1, session.position());
			assertSame(steep, session.geometry());
			assertSame(opened, session.openedGeometry());
			assertTrue(session.statusText(), session.statusText().contains("26.00 deg (edited in this view)"));
			assertTrue(session.statusText(), session.statusText().contains("Opened with: "));
			return null;
		});

		// orientation and zoom kept; the raw point under the window centre is there again
		AffineTransform3D moved = edt(state::getViewerTransform);
		for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) assertEquals(camera.get(r, c), moved.get(r, c), 0);
		double[] centre = { 0.5 * width, 0.5 * height, 0 }, world = new double[3], raw = new double[3], back = new double[3];
		camera.applyInverse(world, centre);
		openedTransforms.get(0).applyInverse(raw, world);
		sourceToWorld(after.get(0), 2).apply(raw, world);
		moved.apply(world, back);
		assertArrayEquals("the anchored raw point is at the centre again", centre, back, 1e-6);
		assertFalse("the camera had to move", java.util.Arrays.equals(camera.getRowPackedCopy(), moved.getRowPackedCopy()));

		// a Position switch makes its sources with the edited geometry
		edt(() -> { session.switchPosition(2); return null; });
		List<SourceAndConverter<?>> other = edt(() -> new ArrayList<SourceAndConverter<?>>(state.getSources()));
		int t = edt(state::getCurrentTimepoint);
		published(session, t);		// position 2 was not checked before the switch
		for (SourceAndConverter<?> soc : other) {
			RawSourceSpec spec = ((RawPartSource<?>) soc.getSpimSource()).spec();
			assertSameMatrix("position 2 at 26 degrees", expected(steep, session, spec, t), sourceToWorld(soc, t));
		}
		edt(() -> { session.switchPosition(1); viewer.setTimepoint(2); return null; });

		// and back to the opening values: exactly the opening transforms, under the opening's provenance
		RawGeometry again = new RawGeometry(opened.dzUm, opened.dxyUm, opened.angleDeg, "typed again");
		assertTrue(edt(() -> session.setGeometry(again)));
		List<SourceAndConverter<?>> restored = edt(() -> new ArrayList<SourceAndConverter<?>>(state.getSources()));
		for (int i = 0; i < restored.size(); i++)
			assertSameMatrix("source " + i + " back at 33.5 degrees", openedTransforms.get(i), sourceToWorld(restored.get(i), 2));
		edt(() -> {
			assertEquals(opened.origin, session.geometry().origin);
			assertFalse(session.statusText(), session.statusText().contains("Opened with: "));
			return null;
		});

		session.close();
		assertFalse("a closing view takes no edit", edt(() -> session.setGeometry(steep)));
	}

	@Test public void followingContinuesAfterAnEdit() throws Exception {
		write();
		OnlineDeskewSession session = open();
		ViewerPanel viewer = session.handle().getViewerPanel();
		edt(() -> { session.setFollowLatest(true); return null; });
		until("follows the newest", () -> {
			try { return edt(() -> viewer.state().getCurrentTimepoint()) == 3; } catch (Exception e) { throw new AssertionError(e); }
		});
		assertTrue(edt(() -> session.setGeometry(at(session.geometry(), 26))));
		assertTrue(edt(session::isFollowingLatest));
		File late = new File(folder.getRoot(), "g_Position0001_Time000005_Channel0001_Frames_1_20.tiff");
		RawTiffFixture.write(late, 64, 40, 20, 8, 20, RawTiffFixture.RAMP);
		Files.setLastModifiedTime(late.toPath(), FileTime.fromMillis(System.currentTimeMillis() - 5000));
		RawFileNotifications.accepted(late);		// as Live Deskew announces a file; the worker takes it
		until("follows the arrival after the edit", () -> {
			try { return edt(() -> viewer.state().getCurrentTimepoint()) == 4; } catch (Exception e) { throw new AssertionError(e); }
		});
		SourceAndConverter<?> soc = edt(() -> viewer.state().getSources().get(0));
		RawSourceSpec spec = ((RawPartSource<?>) soc.getSpimSource()).spec();
		until("the arrival is published", () -> session.snapshot().entry(4, spec.acquisitionChannel).shown() != null);
		assertSameMatrix("the arrival at 26 degrees", expected(session.geometry(), session, spec, 4), sourceToWorld(soc, 4));
	}

	private RawFrameRequest completed(OnlineDeskewSession session) throws Exception {
		edt(() -> { session.playback().setMode(RawPlayback.Mode.COMPLETED); return null; });
		session.navigationResult().get(10, TimeUnit.SECONDS);
		return edt(() -> session.presenter().shownRequest());
	}

	private static void await(CountDownLatch latch) {
		try { assertTrue("latch deadline", latch.await(15, TimeUnit.SECONDS)); }
		catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
	}

	/** The newest navigation, after Swing has run whatever invalidation was queued. */
	private static RawStrictPlayer.Result latest(OnlineDeskewSession session) throws Exception {
		edt(() -> null);
		CompletableFuture<RawStrictPlayer.Result> f = edt(session::navigationResult);
		return f.get(10, TimeUnit.SECONDS);
	}

	@Test public void anOldAngleFrameHeldWhileRenderingNeverCommitsAfterTheEdit() throws Exception {
		write();
		OnlineDeskewSession session = open();
		completed(session);
		List<Long> committed = Collections.synchronizedList(new ArrayList<Long>());
		session.presenter().setCommitObserver((r, pixels) -> committed.add(r.revision));
		CountDownLatch entered = new CountDownLatch(1);
		release = new CountDownLatch(1);
		AtomicBoolean first = new AtomicBoolean(true);
		RawFrameRenderer.rowHookForTest = row -> { if (first.compareAndSet(true, false)) { entered.countDown(); await(release); } };
		CompletableFuture<RawStrictPlayer.Result> obsolete = edt(() -> session.requestStrictFrame(1));
		await(entered);
		RawGeometry steep = at(session.geometry(), 26);
		assertTrue(edt(() -> session.setGeometry(steep)));
		CompletableFuture<RawStrictPlayer.Result> replacement = edt(session::navigationResult);
		assertNotSame(obsolete, replacement);
		release.countDown();
		assertEquals(RawStrictPlayer.Outcome.SUPERSEDED, obsolete.get(10, TimeUnit.SECONDS).outcome);
		RawStrictPlayer.Result shown = latest(session);
		assertEquals(shown.toString(), RawStrictPlayer.Outcome.PRESENTED, shown.outcome);
		assertFalse("the old-angle frame never committed", committed.contains(obsolete.get().request.revision));
		assertEquals("the navigation keeps its time point", 1, shown.request.timeIndex);
		assertEquals(0, shown.coverageMisses);
		assertEquals(2, shown.request.layers.size());
		for (RawFrameRequest.Layer layer : shown.request.layers)
			assertSameMatrix("completed layer at 26 degrees", expected(steep, session, layer.spec, 1), layer.sourceToWorld);
		assertSame(shown.request, edt(() -> session.presenter().shownRequest()));
	}

	@Test public void completedPlaybackRunsThroughAnEditWithoutSkippingOrGoingBack() throws Exception {
		write();
		OnlineDeskewSession session = open();
		completed(session);
		List<RawFrameRequest> committed = Collections.synchronizedList(new ArrayList<RawFrameRequest>());
		session.presenter().setCommitObserver((r, image) -> committed.add(r));
		edt(() -> { session.playback().setLoop(false); session.playback().setTargetFps(2); session.playback().play(); return null; });
		until("the movie has started", () -> !committed.isEmpty());
		RawGeometry steep = at(session.geometry(), 26);
		assertTrue(edt(() -> session.setGeometry(steep)));
		until("the movie reached its end at the new angle", () -> {
			synchronized (committed) {
				RawFrameRequest last = committed.get(committed.size() - 1);
				return last.timeIndex == 3 && Arrays.equals(last.layers.get(0).sourceToWorld.getRowPackedCopy(),
						expected(steep, session, last.layers.get(0).spec, 3).getRowPackedCopy());
			}
		});
		List<RawFrameRequest> frames;
		synchronized (committed) { frames = new ArrayList<RawFrameRequest>(committed); }
		boolean steepSeen = false;
		int previous = frames.get(0).timeIndex;
		List<Integer> times = new ArrayList<Integer>();
		for (RawFrameRequest r : frames) {
			times.add(r.timeIndex);
			assertTrue("never back in time: " + times, r.timeIndex >= previous);
			assertTrue("never skipped a time point: " + times, r.timeIndex <= previous + 1);
			previous = r.timeIndex;
			boolean steepFrame = true;
			for (RawFrameRequest.Layer layer : r.layers)
				steepFrame &= Arrays.equals(layer.sourceToWorld.getRowPackedCopy(), expected(steep, session, layer.spec, r.timeIndex).getRowPackedCopy());
			assertTrue("no old-angle frame after a new-angle one: " + times, steepFrame || !steepSeen);
			steepSeen |= steepFrame;
		}
		System.out.println("completed movie through a geometry edit, time indices committed: " + times);
	}

	@Test public void completedEditMatchesAFreshViewOpenedAtTheNewAngle() throws Exception {
		write();
		OnlineDeskewSession edited = open();
		completed(edited);
		Map<Long, int[]> editedPixels = new ConcurrentHashMap<Long, int[]>();
		edited.presenter().setCommitObserver((r, image) -> editedPixels.put(r.revision, pixels(image)));
		RawGeometry steep = at(edited.geometry(), 26);
		assertTrue(edt(() -> edited.setGeometry(steep)));
		RawStrictPlayer.Result a = latest(edited);
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, a.outcome);
		assertNotNull("the edited frame was committed", editedPixels.get(a.request.revision));

		OnlineDeskewView.Settings manual = new OnlineDeskewView.Settings();
		manual.parameterSource = OnlineDeskewView.PARAMETER_SOURCES[1];
		manual.dz = steep.dzUm; manual.dxy = steep.dxyUm; manual.angle = 26;
		OnlineDeskewSession fresh = open(manual);
		AffineTransform3D camera = a.request.viewer;
		edt(() -> {
			ViewerPanel viewer = fresh.handle().getViewerPanel();
			viewer.state().setViewerTransform(camera);
			for (int i = 0; i < a.request.layers.size(); i++) {
				ConverterSetup setup = fresh.handle().getConverterSetups().getConverterSetup(viewer.state().getSources().get(i));
				setup.setDisplayRange(a.request.layers.get(i).min, a.request.layers.get(i).max);
			}
			viewer.setTimepoint(a.request.timeIndex);
			return null;
		});
		Map<Long, int[]> freshPixels = new ConcurrentHashMap<Long, int[]>();
		edt(() -> {
			fresh.playback().setMode(RawPlayback.Mode.COMPLETED);		// the presenter exists from here; nothing is painted yet
			fresh.presenter().setCommitObserver((r, image) -> freshPixels.put(r.revision, pixels(image)));
			return null;
		});
		RawStrictPlayer.Result b = latest(fresh);
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, b.outcome);
		assertNotNull("the fresh frame was committed", freshPixels.get(b.request.revision));
		assertArrayEquals("the same camera", camera.getRowPackedCopy(), b.request.viewer.getRowPackedCopy(), 0);

		Assume.assumeTrue("both canvases have one size", a.request.width == b.request.width && a.request.height == b.request.height);
		assertEquals(a.request.timeIndex, b.request.timeIndex);
		assertEquals(a.request.layers.size(), b.request.layers.size());
		for (int i = 0; i < a.request.layers.size(); i++)
			assertSameMatrix("layer " + i + " as opened at 26", b.request.layers.get(i).sourceToWorld, a.request.layers.get(i).sourceToWorld);
		int[] drawn = editedPixels.get(a.request.revision);
		assertArrayEquals("the edited view draws what a view opened at 26 degrees draws", freshPixels.get(b.request.revision), drawn);
		int lit = 0;
		for (int p : drawn) if ((p & 0xffffff) != 0) lit++;
		assertTrue("the comparison covers drawn pixels: " + lit, lit > 1000);
	}

	private static int[] pixels(BufferedImage image) {
		return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
	}

	@Test public void autoSampledBeforeAnEditIsDiscarded() throws Exception {
		write();
		OnlineDeskewSession session = open();
		Component cards = session.handle().getCardPanel().getComponent();
		SourceTable table = OnlineDeskewAutoContrast.find(cards, SourceTable.class);
		JButton auto = null;
		for (JButton b : autoButtons(cards)) if (OnlineDeskewAutoContrast.find(b.getParent().getParent(), SourceTable.class) != null) auto = b;
		assertNotNull(auto);
		final JButton button = auto;
		ConverterSetup setup = edt(() -> session.handle().getConverterSetups().getConverterSetup(
				session.handle().getViewerPanel().state().getSources().get(0)));
		edt(() -> { table.getSelectionModel().setSelectionInterval(0, 0); setup.setDisplayRange(10, 60000); return null; });
		CountDownLatch sampled = new CountDownLatch(1);
		release = new CountDownLatch(1);
		OnlineDeskewAutoContrast.sampledHookForTest = () -> { sampled.countDown(); await(release); };
		edt(() -> { button.doClick(); return null; });
		await(sampled);
		assertTrue(edt(() -> session.setGeometry(at(session.geometry(), 26))));
		OnlineDeskewAutoContrast.sampledHookForTest = null;
		release.countDown();
		// the exchange of sources may clear the table's selection, which disables the button: wait for the outcome instead
		until("the Auto worker finished", () -> {
			try { return edt(session::navigationNote).contains("deskew parameters changed"); } catch (Exception e) { throw new AssertionError(e); }
		});
		assertEquals("a range sampled at the old angle is not applied", 10, setup.getDisplayRangeMin(), 0);
		assertEquals(60000, setup.getDisplayRangeMax(), 0);
		assertTrue(session.navigationNote(), session.navigationNote().contains("deskew parameters changed"));
		// the handed-over setup still takes Auto at the new angle
		edt(() -> { table.getSelectionModel().setSelectionInterval(0, 0); button.doClick(); return null; });
		until("Auto applies at the new angle", () -> setup.getDisplayRangeMax() != 60000);
	}

	private static List<JButton> autoButtons(Component c) {
		List<JButton> found = new ArrayList<JButton>();
		if (c instanceof JButton && "opm.online.auto".equals(c.getName())) found.add((JButton) c);
		if (c instanceof Container) for (Component child : ((Container) c).getComponents()) found.addAll(autoButtons(child));
		return found;
	}

	private static ij.gui.GenericDialog dialog() throws Exception {
		long deadline = System.currentTimeMillis() + 10000;
		while (System.currentTimeMillis() < deadline) {
			for (java.awt.Window w : java.awt.Window.getWindows())
				if (w instanceof ij.gui.GenericDialog && w.isShowing()
						&& OnlineDeskewSession.GEOMETRY_TITLE.equals(((ij.gui.GenericDialog) w).getTitle())) return (ij.gui.GenericDialog) w;
			Thread.sleep(25);
		}
		throw new AssertionError("no " + OnlineDeskewSession.GEOMETRY_TITLE + " dialog");
	}

	private static void press(ij.gui.GenericDialog d, int button) throws Exception {
		SwingUtilities.invokeAndWait(() -> d.actionPerformed(new java.awt.event.ActionEvent(d.getButtons()[button],
				java.awt.event.ActionEvent.ACTION_PERFORMED, button == 0 ? "OK" : "Cancel")));
	}

	@Test public void theToolbarDialogShowsTheEffectiveValuesAndAppliesOnlyOnOk() throws Exception {
		write();
		OnlineDeskewSession session = open();
		RawGeometry opened = session.geometry();
		javax.swing.Action action = session.geometryAction();
		assertNotNull("a Deskew parameters... control", action);
		assertEquals("Deskew parameters...", action.getValue(javax.swing.Action.NAME));

		SwingUtilities.invokeLater(() -> action.actionPerformed(null));
		ij.gui.GenericDialog d = dialog();
		edt(() -> {
			assertTrue("raised from the viewer's button, so modal", d.isModal());
			assertEquals(3, d.getNumericFields().size());
			assertEquals("0.2650", ((TextField) d.getNumericFields().get(0)).getText());
			assertEquals("0.1160", ((TextField) d.getNumericFields().get(1)).getText());
			assertEquals("33.50", ((TextField) d.getNumericFields().get(2)).getText());
			((TextField) d.getNumericFields().get(2)).setText("26");
			return null;
		});
		press(d, 1);
		edt(() -> null);
		assertSame("Cancel changes nothing", opened, session.geometry());

		SwingUtilities.invokeLater(() -> action.actionPerformed(null));
		ij.gui.GenericDialog unchanged = dialog();
		press(unchanged, 0);
		edt(() -> null);
		assertSame("OK on unedited values changes nothing", opened, session.geometry());

		SwingUtilities.invokeLater(() -> action.actionPerformed(null));
		ij.gui.GenericDialog edit = dialog();
		edt(() -> { ((TextField) edit.getNumericFields().get(2)).setText("26"); return null; });
		press(edit, 0);
		edt(() -> null);
		RawGeometry now = session.geometry();
		assertEquals(26, now.angleDeg, 0);
		assertEquals("an unedited field keeps the exact value", opened.dzUm, now.dzUm, 0);
		assertEquals(opened.dxyUm, now.dxyUm, 0);
		assertEquals("edited in this view", now.origin);

		// the effective values, not the opening's, are what the dialog offers next
		SwingUtilities.invokeLater(() -> action.actionPerformed(null));
		ij.gui.GenericDialog next = dialog();
		assertEquals("26.00", edt(() -> ((TextField) next.getNumericFields().get(2)).getText()));
		press(next, 1);
		edt(() -> null);
	}
}
