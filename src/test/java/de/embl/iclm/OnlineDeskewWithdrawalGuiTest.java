package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * M3-A-F1 and F2 in a real Online Deskew View: a completed frame is drawn from one publication of its file - version, layout
 * and every layer's placement from one check - and a publication the catalogue takes away is revoked at the presentation
 * gate before the catalogue says so: whether the frame is queued, waiting to be handed over or on screen, and whether the
 * file was deleted or another file claims its slot. A conflict that is over publishes the unchanged file again, as a new
 * publication the gate accepts; a frame of the old one stays refused. B's two withdrawal probes are among them. Needs a
 * display: {@code -Dopm.test.gui=true}.
 */
public class OnlineDeskewWithdrawalGuiTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	static final int W = 64, H = 40, D = 12, ROWS = 8;
	private OnlineDeskewSession session;
	private final CountDownLatch rendererGo = new CountDownLatch(1), checkerGo = new CountDownLatch(1);
	/** Every publication a frame was committed from, as the presenter committed it. */
	private final List<RawAcquisition.Entry.Shown> committed = Collections.synchronizedList(new ArrayList<RawAcquisition.Entry.Shown>());

	@Before
	public void needsADisplay() {
		Assume.assumeTrue("Enable with -Dopm.test.gui=true", Boolean.getBoolean("opm.test.gui"));
		RawAcquisition.eventThreadFileAccessForTest.set(0);
	}

	@After
	public void close() throws Exception {
		if (!Boolean.getBoolean("opm.test.gui")) return;
		rendererGo.countDown();
		checkerGo.countDown();
		RawStrictPlayer.beforeHandOverForTest = null;
		OnlineDeskewSession.checkerHookForTest = null;
		OnlineDeskewSession.captureHookForTest = null;
		RawAcquisition.quietMsForTest = -1;
		if (session != null && !session.isClosing()) {
			OnlineDeskewLiveGuiTest.edtRun(() -> session.frame().dispose());
			assertTrue(session.closed().get(20, TimeUnit.SECONDS).drained);
		}
		assertEquals("no file touched on the event thread", 0, RawAcquisition.eventThreadFileAccessForTest.get());
	}

	private File file(String prefix, int t) {
		return new File(folder.getRoot(), prefix + "_Time" + t + "_Channel1_Frames_1_" + D + ".tiff");
	}

	private static File write(File f) throws Exception {
		return RawTiffFixture.write(f, W, H, D, ROWS, D, RawTiffFixture.RAMP);
	}

	private void open() throws Exception {
		write(file("b", 1));
		write(file("b", 2));
		try (FileWriter w = new FileWriter(new File(folder.getRoot(), "ExperimentalParameters.txt"))) {
			w.write("Interval: 5 Minute\nTilt angle: 33.5\num per galvo DU: 0.01325\nGalvo DUs per step: 20\n"
					+ "Pixel size at object /um: 0.116\nCamera ROI X0, X1, Y0, Y1: 0 64 0 40\n");
		}
		OnlineDeskewView.Settings s = new OnlineDeskewView.Settings();
		s.folder = folder.getRoot().getAbsolutePath();
		s.budgetMb = 64;
		session = OnlineDeskewSession.open(s);
		OnlineDeskewLiveGuiTest.await("both checked", 10000, () -> session.snapshot().entry(1).viewable());
	}

	/** Completed frames on, and every commit recorded. */
	private void watchCommits() throws Exception {
		OnlineDeskewLiveGuiTest.edt(() -> session.strictPlayer());
		session.presenter().setCommitObserver((r, image) -> committed.add(r.publication));
	}

	private void holdAtHandOver(Predicate<RawFrameRequest> which, CountDownLatch rendered) {
		RawStrictPlayer.beforeHandOverForTest = r -> {
			if (which.test(r)) {
				rendered.countDown();
				waitFor(rendererGo);
			}
		};
	}

	private static void waitFor(CountDownLatch latch) {
		try {
			if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("latch timed out");
		} catch (InterruptedException e) {
			throw new AssertionError(e);
		}
	}

	/** B's probe, ported: a frame rendered and waiting to be handed over when the catalogue sees its file deleted. */
	@Test
	public void bProbeDeletionObservedByTheCatalogueCannotCommitAPreparedFrameAfterwards() throws Exception {
		open();
		RawAcquisition.Entry e = session.snapshot().entry(1);
		CountDownLatch rendered = new CountDownLatch(1), removed = new CountDownLatch(1);
		AtomicBoolean arm = new AtomicBoolean(), badCommit = new AtomicBoolean();
		holdAtHandOver(r -> r.entry == e, rendered);
		CompletableFuture<RawStrictPlayer.Result> result = OnlineDeskewLiveGuiTest.edt(() -> session.requestStrictFrame(1));
		assertTrue("the frame passed its last check of the file's version", rendered.await(10, TimeUnit.SECONDS));
		session.presenter().setCommitObserver((r, image) -> {
			if (r.entry == e && !e.viewable()) badCommit.set(true);
		});
		// the deletion, seen by the catalogue's own worker, which is then held before the store hears of it
		OnlineDeskewSession.checkerHookForTest = () -> {
			if (arm.get() && !e.file.exists()) {
				session.acquisition().hint(e.file);
				if (e.readiness() == RawAcquisition.Readiness.REMOVED) {
					removed.countDown();
					waitFor(checkerGo);
				}
			}
		};
		arm.set(true);
		Files.delete(e.file.toPath());
		assertTrue("the worker saw the deletion", removed.await(10, TimeUnit.SECONDS));
		rendererGo.countDown();
		RawStrictPlayer.Result r = result.get(10, TimeUnit.SECONDS);
		System.out.println("after the catalogue's deletion: " + r.outcome + ", " + e.readiness() + ", bad commit " + badCommit.get());
		assertFalse("no frame of a version the catalogue has removed is committed", badCommit.get());
		assertNotEquals(RawStrictPlayer.Outcome.PRESENTED, r.outcome);
	}

	/** B's probe, ported: a frame on screen when another file claims its slot is taken off. */
	@Test
	public void bProbeAnAmbiguousArrivalWithdrawsAnAlreadyShownCompletedFrame() throws Exception {
		open();
		RawStrictPlayer.Result result = OnlineDeskewLiveGuiTest.edt(() -> session.requestStrictFrame(0)).get(10, TimeUnit.SECONDS);
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, result.outcome);
		RawAcquisition.Entry e = session.snapshot().entry(0);
		write(file("duplicate", 1));
		OnlineDeskewLiveGuiTest.await("the duplicate seen", 10000, () -> e.readiness() == RawAcquisition.Readiness.FAILED);
		assertFalse(e.viewable());
		assertNull("neither file is shown, so the completed frame is taken off too", session.presenter().shownRequest());
		assertTrue(session.presenter().label(), session.presenter().label().endsWith("withdrawn"));
	}

	/** A frame on screen whose file is deleted is taken off as the catalogue sees it go, with no read of the file. */
	@Test
	public void aShownFrameWhoseFileIsDeletedIsTakenOff() throws Exception {
		open();
		watchCommits();
		RawStrictPlayer.Result result = OnlineDeskewLiveGuiTest.edt(() -> session.requestStrictFrame(0)).get(10, TimeUnit.SECONDS);
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, result.outcome);
		RawAcquisition.Entry e = session.snapshot().entry(0);
		RawAcquisition.Entry.Shown publication = e.shown();
		Files.delete(e.file.toPath());
		OnlineDeskewLiveGuiTest.await("the deletion seen", 10000, () -> e.readiness() == RawAcquisition.Readiness.REMOVED);
		assertNull("taken off", session.presenter().shownRequest());
		OnlineDeskewLiveGuiTest.edtRun(() -> {});
		assertTrue("the publication was committed once, before", committed.size() == 1 && committed.get(0) == publication);
	}

	/**
	 * A frame queued behind another, not yet drawn, when another file claims its slot: its file is untouched, so only the
	 * gate can keep it off the screen - and does.
	 */
	@Test
	public void aQueuedFrameOfADisputedSlotIsNeverPresented() throws Exception {
		open();
		watchCommits();
		RawAcquisition.Entry second = session.snapshot().entry(1);
		RawAcquisition.Entry.Shown publication = second.shown();
		CountDownLatch rendered = new CountDownLatch(1);
		holdAtHandOver(r -> r.timeIndex == 0, rendered);
		OnlineDeskewLiveGuiTest.edt(() -> session.requestStrictFrame(0));
		assertTrue(rendered.await(10, TimeUnit.SECONDS));
		RawFrameRequest queued = session.frameRequest(1);
		assertSame(publication, queued.publication);
		CompletableFuture<RawStrictPlayer.Result> waiting = session.player().request(queued);		// behind the held frame
		write(file("duplicate", 2));
		OnlineDeskewLiveGuiTest.await("the duplicate seen", 10000, () -> second.readiness() == RawAcquisition.Readiness.FAILED);
		rendererGo.countDown();
		RawStrictPlayer.Result r = waiting.get(10, TimeUnit.SECONDS);
		assertNotEquals("refused at the gate: " + r, RawStrictPlayer.Outcome.PRESENTED, r.outcome);
		assertFalse("never committed", committed.contains(publication));
	}

	/**
	 * A frame waiting to be handed over while a duplicate claims its slot and goes again: the unchanged file is published
	 * again, the frame of the old publication is still refused, and the time point comes back by itself - asked again, it is
	 * presented from the new publication, and BigDataViewer draws it again - with no reopening.
	 */
	@Test
	public void aConflictThatIsOverPublishesTheFileAgainAndRefusesTheOldFrame() throws Exception {
		open();
		watchCommits();
		RawAcquisition.Entry second = session.snapshot().entry(1);
		RawAcquisition.Entry.Shown before = second.shown();
		CountDownLatch rendered = new CountDownLatch(1);
		holdAtHandOver(r -> r.publication == before, rendered);
		CompletableFuture<RawStrictPlayer.Result> held = OnlineDeskewLiveGuiTest.edt(() -> session.requestStrictFrame(1));
		assertTrue(rendered.await(10, TimeUnit.SECONDS));
		File duplicate = write(file("duplicate", 2));
		OnlineDeskewLiveGuiTest.await("the duplicate seen", 10000, () -> second.readiness() == RawAcquisition.Readiness.FAILED);
		Files.delete(duplicate.toPath());
		OnlineDeskewLiveGuiTest.await("published again", 10000, () -> second.shown() != null && second.shown() != before);
		RawAcquisition.Entry.Shown after = second.shown();
		assertEquals("the same file version", before.key, after.key);
		assertSame("the slot is the same file's", second, session.snapshot().entry(1));
		rendererGo.countDown();
		RawStrictPlayer.Result old = held.get(10, TimeUnit.SECONDS);
		assertNotEquals("the old publication's frame stays refused: " + old, RawStrictPlayer.Outcome.PRESENTED, old.outcome);
		RawStrictPlayer.Result again = OnlineDeskewLiveGuiTest.edt(() -> session.requestStrictFrame(1)).get(10, TimeUnit.SECONDS);
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, again.outcome);
		assertSame("drawn from the new publication", after, session.presenter().shownRequest().publication);
		assertFalse("the old one never committed", committed.contains(before));
		assertTrue(committed.contains(after));
		OnlineDeskewLiveGuiTest.edt(() -> {
			session.stopStrictPlayback();
			return null;
		});
		OnlineDeskewLiveGuiTest.await("BigDataViewer draws it again", 10000, () -> {
			session.handle().getViewerPanel().requestRepaint();
			return session.presence().present(1);
		});
	}

	/**
	 * M3-A-F1 in the view: the file is rewritten taller and checked again between the frame taking its publication and placing
	 * its layers. The request keeps the publication it took - version, layout - and both halves are placed by that layout.
	 */
	@Test
	public void aFrameCapturedWhileItsFileIsCheckedAgainIsPlacedByTheVersionItIsDrawnFrom() throws Exception {
		open();
		RawAcquisition.Entry first = session.snapshot().entry(0);
		RawAcquisition.Entry.Shown before = first.shown();
		AtomicBoolean armed = new AtomicBoolean(true);
		OnlineDeskewSession.captureHookForTest = () -> {
			if (!armed.getAndSet(false)) return;
			try {
				long was = first.file.lastModified();
				RawTiffFixture.write(first.file, W, H + 8, D, ROWS, D, RawTiffFixture.RAMP);
				Files.setLastModifiedTime(first.file.toPath(), FileTime.fromMillis(Math.max(was, System.currentTimeMillis()) + 2000));
				first.observe(RawAcquisition.FileKey.of(first.file, 1), System.currentTimeMillis());
				first.markChanged(before.key, "rewritten by the test");
				RawAcquisition.quietMsForTest = 0;
				first.recheck(System.currentTimeMillis());
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		};
		RawFrameRequest r = session.frameRequest(0);		// off the event thread, as playback's preparation is
		assertNotNull(r);
		OnlineDeskewLiveGuiTest.await("published again, taller", 10000,
				() -> first.shown() != null && first.shown() != before && first.shown().layout.height == H + 8);
		assertSame("the publication it took", before, r.publication);
		assertEquals(H, r.layout.height);
		assertSame(before.key, r.key);
		assertEquals("both halves", 2, r.layers.size());
		for (RawFrameRequest.Layer l : r.layers)
			assertArrayEquals(l.spec.key + " placed by the layout it is drawn from",
					session.geometry().sourceToWorld(before.layout.dimensions(), l.spec, null).getRowPackedCopy(),
					l.sourceToWorld.getRowPackedCopy(), 0);
	}
}
