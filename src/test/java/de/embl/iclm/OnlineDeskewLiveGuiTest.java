package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.viewer.Source;
import bdv.viewer.ViewerPanel;
import net.imglib2.RandomAccess;
import net.imglib2.type.volatiles.VolatileUnsignedShortType;

/**
 * M3-A: a folder that grows while the Online Deskew View is open (joint contracts C2.1-C2.3, C2.5, C2.6), with a test producer
 * writing BigTIFFs plane by plane. A file that arrives joins the time axis, stays pending as {@code k of n} while it is short,
 * and is published once complete; a rewritten file is withdrawn and republished; a late file with an older time number is
 * inserted in order, and the time point shown stays the one shown. Publication while playing, in both modes, keeps the cadence,
 * presents nothing withdrawn and keeps the counters right. Closing releases the folder watch and its thread, and stops a look at
 * the folder between two files. No file is touched on the event thread, and nothing escapes on any thread. Needs a display:
 * {@code -Dopm.test.gui=true}.
 */
public class OnlineDeskewLiveGuiTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	static final int W = 64, H = 40, D = 12, ROWS = 8;
	private OnlineDeskewSession session;
	private final List<Throwable> uncaught = Collections.synchronizedList(new ArrayList<Throwable>());
	private Thread.UncaughtExceptionHandler previousHandler;

	@Before
	public void needsADisplay() {
		Assume.assumeTrue("Enable with -Dopm.test.gui=true", Boolean.getBoolean("opm.test.gui"));
		RawAcquisition.eventThreadFileAccessForTest.set(0);
		previousHandler = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
			uncaught.add(new AssertionError("uncaught on " + thread.getName() + ": " + e, e));
			e.printStackTrace();
		});
	}

	@After
	public void close() throws Exception {
		if (!Boolean.getBoolean("opm.test.gui")) return;
		try {
			if (session != null && !session.isClosing()) {
				SwingUtilities.invokeAndWait(() -> session.frame().dispose());
				OnlineDeskewSession.CloseResult result = session.closed().get(15, TimeUnit.SECONDS);
				assertTrue("closed cleanly: " + result, result.drained);
			}
			SwingUtilities.invokeAndWait(() -> {});
		} finally {
			Thread.setDefaultUncaughtExceptionHandler(previousHandler);
			RawAcquisition.lookHookForTest = null;
			OnlineDeskewSession.reconcileMsForTest = 0;
		}
		if (!uncaught.isEmpty()) throw (AssertionError) uncaught.get(0);
		assertEquals("no file attributes read or file checked on the event thread", 0, RawAcquisition.eventThreadFileAccessForTest.get());
	}

	static String name(int time) {
		return String.format("g_Position0001_Time%06d_Channel0001_Frames_1_%d.tiff", time, D);
	}

	static RawTiffFixture.Pixels values(int time) {
		return (x, y, k) -> 500 + 300 * time + (x * 7 + y * 13 + k * 29) % 250;
	}

	/** A complete file of time point {@code time}, written whole. */
	File complete(int time) throws Exception {
		return RawTiffFixture.write(new File(folder.getRoot(), name(time)), W, H, D, ROWS, D, values(time));
	}

	/**
	 * The file of a time point written again, whole, with the pixels of {@code as}; its time moved past now, so that its new
	 * version is never the old one and is quiet only once a quiet period has passed since it was seen.
	 */
	static void replace(RawAcquisition.Entry e, int as) throws Exception {
		long was = e.file.lastModified();
		RawTiffFixture.write(e.file, W, H, D, ROWS, D, values(as));
		Files.setLastModifiedTime(e.file.toPath(), FileTime.fromMillis(Math.max(was, System.currentTimeMillis()) + 2000));
	}

	OnlineDeskewView.Settings settings() throws Exception {
		File parameters = new File(folder.getRoot(), "ExperimentalParameters.txt");
		if (!parameters.isFile())
			try (FileWriter w = new FileWriter(parameters)) {
				w.write("Interval: 5 Minute\nTilt angle: 33.5\num per galvo DU: 0.01325\nGalvo DUs per step: 20\n"
						+ "Pixel size at object /um: 0.116\nCamera ROI X0, X1, Y0, Y1: 0 64 0 40\n");
			}
		OnlineDeskewView.Settings s = new OnlineDeskewView.Settings();
		s.folder = folder.getRoot().getAbsolutePath();
		s.budgetMb = 64;
		return s;
	}

	static <T> T edt(Callable<T> work) throws Exception {
		final Object[] value = new Object[1];
		final Exception[] failure = new Exception[1];
		SwingUtilities.invokeAndWait(() -> {
			try {
				value[0] = work.call();
			} catch (Exception e) {
				failure[0] = e;
			}
		});
		if (failure[0] != null) throw failure[0];
		@SuppressWarnings("unchecked") T t = (T) value[0];
		return t;
	}

	static void edtRun(Runnable r) throws Exception {
		SwingUtilities.invokeAndWait(r);
	}

	static void await(String what, long millis, BooleanSupplier condition) throws Exception {
		long deadline = System.currentTimeMillis() + millis;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) throw new AssertionError("not within " + millis + " ms: " + what);
			Thread.sleep(20);
		}
	}

	private int timeCount() throws Exception {
		return edt(() -> session.handle().getViewerPanel().state().getNumTimepoints());
	}

	private int shownTimeId() throws Exception {
		return edt(() -> session.times().get(session.handle().getViewerPanel().state().getCurrentTimepoint()).timeId);
	}

	private RawAcquisition.Entry entry(int time) {
		return session.acquisition().entry(new RawAcquisition.TimeKey(1, time), 1);
	}

	private List<Integer> axis() {
		List<Integer> ids = new ArrayList<Integer>();
		for (RawAcquisition.TimeKey t : session.snapshot().times) ids.add(t.timeId);
		return ids;
	}

	/** The pixel of BigDataViewer's first source at a time index and voxel, once a frame has loaded it; -1 if it never does. */
	@SuppressWarnings("unchecked")
	private int pixel(int t, long x, long y, long z) throws Exception {
		ViewerPanel viewer = session.handle().getViewerPanel();
		long deadline = System.currentTimeMillis() + 10000;
		while (System.currentTimeMillis() < deadline) {
			viewer.requestRepaint();
			Thread.sleep(20);
			Source<VolatileUnsignedShortType> half = (Source<VolatileUnsignedShortType>) viewer.state().getSources().get(0).asVolatile()
					.getSpimSource();
			if (!half.isPresent(t)) continue;
			RandomAccess<VolatileUnsignedShortType> a = half.getSource(t, 0).randomAccess();
			a.setPosition(new long[] { x, y, z });
			if (a.get().isValid()) return a.get().get().get();
		}
		return -1;
	}

	/* ---------------------------------------------------------------- arrival, replacement, renumbering */

	@Test
	public void aFileArrivingAfterOpeningJoinsTheTimeAxis() throws Exception {
		complete(1);
		complete(2);
		session = OnlineDeskewSession.open(settings());
		assertEquals(2, timeCount());
		complete(3);
		await("the third time point on the axis", 15000, () -> {
			try {
				return timeCount() == 3 && session.times().size() == 3;
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		RawAcquisition.Entry third = session.acquisition().entry(session.times().get(2), 1);
		await("the third time point published", 15000, () -> third.readiness() == RawAcquisition.Readiness.COMPLETE);
		assertTrue(third.viewable());
	}

	@Test
	public void aRewrittenFileIsWithdrawnAndRepublished() throws Exception {
		complete(1);
		complete(2);
		session = OnlineDeskewSession.open(settings());
		RawAcquisition.Entry first = session.acquisition().entry(session.times().get(0), 1);
		await("checked", 15000, () -> first.readiness() == RawAcquisition.Readiness.COMPLETE);
		RawAcquisition.FileKey before = first.key();
		Thread.sleep(50);
		RawTiffFixture.write(first.file, W, H, D, ROWS, D, values(7));		// the same name, new pixels
		await("withdrawn", 15000, () -> first.readiness() != RawAcquisition.Readiness.COMPLETE || !before.equals(first.key()));
		await("republished as the new version", 15000,
				() -> first.readiness() == RawAcquisition.Readiness.COMPLETE && first.key() != null && !before.equals(first.key()));
	}

	@Test
	public void aLateOlderTimePointIsInsertedAndTheShownOneStays() throws Exception {
		complete(2);
		complete(3);
		session = OnlineDeskewSession.open(settings());
		edt(() -> {
			session.handle().getViewerPanel().setTimepoint(1);
			return null;
		});
		assertEquals(3, shownTimeId());
		complete(1);
		await("the late time point inserted", 15000, () -> {
			try {
				return timeCount() == 3 && session.times().size() == 3 && session.times().get(0).timeId == 1;
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		assertEquals("the time point shown is still Time000003", 3, shownTimeId());
		assertEquals(2, (int) edt(() -> session.handle().getViewerPanel().state().getCurrentTimepoint()));
	}

	/**
	 * Acceptance 1, in the view: a file written plane by plane is on the axis at once, not shown while it is short - the status
	 * card says {@code k of n} - and published, with its own pixels, once its last plane is written.
	 */
	@Test
	public void aGrowingFileStaysPendingAsKOfNAndIsPublishedWhenComplete() throws Exception {
		complete(1);
		session = OnlineDeskewSession.open(settings());
		try (RawTiffFixture.Producer p = new RawTiffFixture.Producer(new File(folder.getRoot(), name(2)), W, H, ROWS, values(2))) {
			p.write(4);
			await("on the axis", 15000, () -> axis().equals(Arrays.asList(1, 2)));
			RawAcquisition.Entry second = entry(2);
			await("checked as short once quiet", 15000, () -> second.readiness() == RawAcquisition.Readiness.INCOMPLETE);
			assertEquals("4 of 12 planes", second.detail());
			edtRun(() -> session.handle().getViewerPanel().setTimepoint(1));
			await("the status card says so", 5000, () -> {
				try {
					return edt(session::statusText).contains("pending: 4 of 12 planes");
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			});
			assertFalse("not shown while short", edt(() -> session.handle().getViewerPanel().state().getSources().get(0).getSpimSource()
					.isPresent(1)));
			for (int k = 4; k < D; k += 2) {
				p.write(2);
				Thread.sleep(100);
			}
		}
		RawAcquisition.Entry second = entry(2);
		await("published once complete", 15000, () -> second.readiness() == RawAcquisition.Readiness.COMPLETE);
		assertEquals("its own last plane", values(2).at(2, 2, D - 1), pixel(1, 2, 2, D - 1));
		assertEquals("published, never republished", 0, second.republished());
		assertTrue(edt(session::statusText), edt(session::statusText).contains(String.format(Locale.US, "%s - 2 of 2 - complete", second.time)));
	}

	/* ---------------------------------------------------------------- publication while playing */

	/** One frame the presenter committed, and whether the catalogue had withdrawn its file version by then. */
	static final class Committed {
		final int timeId;
		final RawAcquisition.FileKey key;
		final long nanos;
		final boolean withdrawn;

		Committed(RawFrameRequest r) {
			RawAcquisition.Entry e = r.entry;
			timeId = e.time.timeId;
			key = r.key;
			nanos = System.nanoTime();
			withdrawn = e.readiness() == RawAcquisition.Readiness.CHANGED || !r.key.equals(e.key());
		}

		@Override public String toString() {
			return timeId + (withdrawn ? "!" : "");
		}
	}

	/** The time ids in turn, a frame drawn again for the same time point and version - a renumbering's redraw - taken once. */
	static List<Integer> turns(List<Committed> log) {
		List<Integer> ids = new ArrayList<Integer>();
		Committed last = null;
		for (Committed c : log) {
			if (last == null || c.timeId != last.timeId || !c.key.equals(last.key)) ids.add(c.timeId);
			last = c;
		}
		return ids;
	}

	/** The longest wait between two consecutive commits, in milliseconds. */
	static double longestGap(List<Long> nanos) {
		double gap = 0;
		for (int i = 1; i < nanos.size(); i++) gap = Math.max(gap, (nanos.get(i) - nanos.get(i - 1)) / 1e6);
		return gap;
	}

	/** Each step goes to a later time number, or wraps to the first. */
	static void assertInTurn(List<Integer> turns, int first) {
		for (int i = 1; i < turns.size(); i++) {
			int a = turns.get(i - 1), b = turns.get(i);
			assertTrue("in turn, or wrapping to the first, at " + i + ": " + turns, b > a || b == first);
		}
	}

	/**
	 * While playback plays the folder grows: a time point is written plane by plane over almost two seconds, an older time
	 * number arrives late and renumbers the axis, and a time point already played is replaced. Nothing waits for the file being
	 * written; every time point is played in turn as the axis stands, each new one included; and the producer's new folder is
	 * the playback's new axis.
	 */
	private void growTheFolderWhilePlaying(BooleanSupplier stillPlaying) throws Exception {
		try (RawTiffFixture.Producer p = new RawTiffFixture.Producer(new File(folder.getRoot(), name(8)), W, H, ROWS, values(8))) {
			for (int k = 0; k < D; k += 2) {
				p.write(2);
				Thread.sleep(300);
				assertTrue("still playing", stillPlaying.getAsBoolean());
			}
		}
		complete(3);		// late, older than every time point but one
		await("the late time point on the axis", 15000, () -> axis().equals(Arrays.asList(2, 3, 4, 6, 8)));
		RawAcquisition.Entry four = entry(4);
		await("time 4 checked", 15000, () -> four.viewable());
		final RawAcquisition.FileKey old = four.key();
		replace(four, 40);
		await("time 4 republished", 20000, () -> four.viewable() && !old.equals(four.key()));
		assertTrue("still playing", stillPlaying.getAsBoolean());
	}

	/**
	 * Acceptance 2, completed frames: the run keeps its cadence while a file is being written - nothing waits for it - and
	 * across a renumbering and a replacement; no frame of a version the catalogue has withdrawn is ever presented; the frames
	 * come in turn on the axis as it stands, the new time points included; and the counters add up.
	 */
	@Test
	public void publicationWhileCompletedFramesPlay() throws Exception {
		complete(2);
		complete(4);
		complete(6);
		session = OnlineDeskewSession.open(settings());
		edtRun(() -> session.playback().setMode(RawPlayback.Mode.COMPLETED));
		final List<Committed> log = Collections.synchronizedList(new ArrayList<Committed>());
		session.presenter().setCommitObserver((r, image) -> log.add(new Committed(r)));
		edtRun(() -> {
			session.playback().setLoop(true);
			session.playback().setTargetFps(20);
			session.playback().play();
		});
		await("playing", 10000, () -> log.size() >= 6);
		growTheFolderWhilePlaying(() -> {
			try {
				return edt(() -> session.playback().isPlaying());
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		final RawAcquisition.FileKey replaced = entry(4).key();
		await("the new time points and the new version played", 20000, () -> {
			synchronized (log) {
				boolean three = false, eight = false, four = false;
				for (Committed c : log) {
					three |= c.timeId == 3;
					eight |= c.timeId == 8;
					four |= c.timeId == 4 && c.key.equals(replaced);
				}
				return three && eight && four;
			}
		});
		int before = log.size();
		await("a full loop after", 10000, () -> log.size() >= before + 6);
		edtRun(() -> session.playback().pause());
		Thread.sleep(200);
		RawPlayback.Stats s = edt(() -> session.playback().stats());
		List<Committed> seen;
		synchronized (log) {
			seen = new ArrayList<Committed>(log);
		}
		List<Long> at = new ArrayList<Long>();
		for (Committed c : seen) at.add(c.nanos);
		List<Integer> turns = turns(seen);
		System.out.printf(Locale.US, "completed playback over a growing folder: %d commits, %d turns, longest gap %.0f ms; %s%n  %s%n",
				seen.size(), turns.size(), longestGap(at), edt(session::playbackText), turns);
		assertTrue("the cadence holds - nothing waited for the file being written: " + longestGap(at) + " ms", longestGap(at) < 1000);
		for (Committed c : seen) assertFalse("a withdrawn version presented: " + turns, c.withdrawn);
		boolean newFour = false;
		for (Committed c : seen)
			if (c.timeId == 4) {
				if (c.key.equals(replaced)) newFour = true;
				else assertFalse("the old version of time 4 after its new one", newFour);
			}
		assertInTurn(turns, 2);
		assertEquals(Arrays.asList(2, 3, 4, 6, 8), axis());
		assertEquals("BigDataViewer's time count grew with the catalogue", 5, timeCount());
		assertEquals(1, session.renumberings());
		assertTrue("the counters add up: " + s.asked + " asked, " + s.presented + " presented, " + s.skipped + " skipped",
				s.asked - s.presented - s.skipped >= 0 && s.asked - s.presented - s.skipped <= 1);
		assertTrue("every first presentation is a commit", s.presented <= seen.size());
		// paused: the frame shown is the slider's time point, numbered by the axis as it stands
		RawFrameRequest shown = edt(() -> session.presenter().shownRequest());
		int slider = edt(() -> session.handle().getViewerPanel().state().getCurrentTimepoint());
		assertEquals(session.snapshot().time(slider), shown.entry.time);
		assertTrue(session.presenter().label(), session.presenter().label().startsWith("t = " + (slider + 1) + " " + shown.entry.time));
	}

	/**
	 * Acceptance 2, progressive: BigDataViewer's time point moves at the rate through the growing axis - a renumbering keeps the
	 * time point shown and is not taken for the user's move - and the replaced time point is drawn from its new version.
	 */
	@Test
	public void publicationWhileProgressivePlaybackPlays() throws Exception {
		complete(2);
		complete(4);
		complete(6);
		session = OnlineDeskewSession.open(settings());
		ViewerPanel viewer = session.handle().getViewerPanel();
		final List<Integer> moves = Collections.synchronizedList(new ArrayList<Integer>());
		final List<Long> at = Collections.synchronizedList(new ArrayList<Long>());
		edtRun(() -> viewer.addTimePointListener(t -> {
			RawAcquisition.TimeKey k = session.snapshot().time(t);
			moves.add(k == null ? -1 : k.timeId);
			at.add(System.nanoTime());
		}));
		moves.clear();
		at.clear();
		edtRun(() -> {
			session.playback().setLoop(true);
			session.playback().setTargetFps(20);
			session.playback().play();
		});
		growTheFolderWhilePlaying(() -> {
			try {
				return edt(() -> session.playback().isPlaying());
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		Thread.sleep(1000);		// a few loops over the axis as it stands
		edtRun(() -> session.playback().pause());
		RawPlayback.Stats s = edt(() -> session.playback().stats());
		List<Integer> seen;
		List<Long> times;
		synchronized (moves) {
			seen = new ArrayList<Integer>(moves);
			times = new ArrayList<Long>(at);
		}
		List<Integer> turns = new ArrayList<Integer>();
		for (int id : seen)
			if (turns.isEmpty() || turns.get(turns.size() - 1) != id) turns.add(id);
		System.out.printf(Locale.US, "progressive playback over a growing folder: %d moves, longest gap %.0f ms; %s%n  %s%n", seen.size(),
				longestGap(times), edt(session::playbackText), turns);
		assertFalse("never paused as though moved by hand: " + edt(session::playbackText), edt(session::playbackText).contains("by hand"));
		assertTrue("the cadence holds: " + longestGap(times) + " ms", longestGap(times) < 1000);
		assertInTurn(turns, 2);
		Set<Integer> played = new TreeSet<Integer>(turns);
		assertEquals("every time point played, the new ones included", new TreeSet<Integer>(Arrays.asList(2, 3, 4, 6, 8)), played);
		assertEquals(1, session.renumberings());
		assertTrue(s.advanced >= turns.size() - 1);
		assertTrue(s.movesWithPlaceholderActivity <= s.advanced);
		assertEquals("time 4 drawn from its new version", values(40).at(2, 2, 0), pixel(2, 2, 2, 0));
		for (int t = 0; t < session.snapshot().size(); t++) {
			final int i = t;
			assertEquals("what BigDataViewer draws at " + t + " is what the catalogue publishes", session.snapshot().entry(t).viewable(),
					(boolean) edt(() -> viewer.state().getSources().get(0).getSpimSource().isPresent(i)));
		}
	}

	/* ---------------------------------------------------------------- resources */

	private static List<Thread> threadsNamed(String... names) {
		List<Thread> found = new ArrayList<Thread>();
		for (Thread t : Thread.getAllStackTraces().keySet())
			for (String n : names)
				if (t.getName().startsWith(n) && t.isAlive()) found.add(t);
		return found;
	}

	/** Acceptance 3: closing releases the folder watch, its thread and the checker; nothing more is looked at. */
	@Test
	public void closingReleasesTheFolderWatchItsThreadAndTheChecker() throws Exception {
		complete(1);
		List<Thread> before = threadsNamed("OPM-online-deskew-watch", "OPM-online-deskew-check");
		session = OnlineDeskewSession.open(settings());
		RawFolderWatcher watch = session.watcher();
		assertTrue("the folder is watched", watch != null && watch.isRunning());
		List<Thread> ours = threadsNamed("OPM-online-deskew-watch", "OPM-online-deskew-check");
		ours.removeAll(before);
		assertEquals("a watch thread and a checker: " + ours, 2, ours.size());
		complete(2);
		await("the watch named the new file", 15000, () -> watch.events() > 0);
		edtRun(() -> session.frame().dispose());
		OnlineDeskewSession.CloseResult result = session.closed().get(15, TimeUnit.SECONDS);
		assertTrue(result.toString(), result.drained && result.checkerStopped);
		assertFalse("the watch is closed", watch.isRunning());
		for (Thread t : ours) assertFalse(t.getName() + " ended", t.isAlive());
		int files = session.acquisition().fileCount();
		complete(3);
		Thread.sleep(500);
		assertEquals("closed: nothing more is looked at", files, session.acquisition().fileCount());
	}

	/** Acceptance 1 and 3: a view closed while it looks at the whole folder stops between two files, and closes cleanly. */
	@Test
	public void closingDuringALookAtTheFolderStopsItBetweenFiles() throws Exception {
		final int files = 100;
		for (int t = 1; t <= files; t++) complete(t);
		OnlineDeskewSession.reconcileMsForTest = 100;
		session = OnlineDeskewSession.open(settings());
		final AtomicBoolean armed = new AtomicBoolean(true);
		final AtomicInteger reached = new AtomicInteger();
		final CountDownLatch inLook = new CountDownLatch(1);
		RawAcquisition.lookHookForTest = () -> {
			if (!armed.get()) return;
			if (reached.incrementAndGet() == 1) {
				inLook.countDown();
				long deadline = System.currentTimeMillis() + 10000;		// hold the look until the view is closing
				while (!session.isClosing() && System.currentTimeMillis() < deadline)
					try {
						Thread.sleep(5);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
			}
		};
		assertTrue("a look began", inLook.await(15, TimeUnit.SECONDS));
		edtRun(() -> session.frame().dispose());
		OnlineDeskewSession.CloseResult result = session.closed().get(15, TimeUnit.SECONDS);
		armed.set(false);
		System.out.println("closed during a look, after " + reached.get() + " of " + files + " files: " + result);
		assertTrue(result.toString(), result.drained);
		assertTrue("stopped between files: " + reached.get() + " of " + files, reached.get() < files);
	}
}
