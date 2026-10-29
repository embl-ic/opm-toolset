package de.embl.iclm;

import static de.embl.iclm.OnlineDeskewLiveGuiTest.await;
import static de.embl.iclm.OnlineDeskewLiveGuiTest.edt;
import static de.embl.iclm.OnlineDeskewLiveGuiTest.edtRun;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.viewer.SourceAndConverter;

/** M4-A's actual viewer: grouped source order/presence, late files, follow, frozen requests and rapid scrubbing. */
public class OnlineDeskewMultiFileGuiTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();
	private OnlineDeskewSession session;

	@Before
	public void display() {
		Assume.assumeTrue("Enable with -Dopm.test.gui=true", Boolean.getBoolean("opm.test.gui"));
		RawAcquisition.eventThreadFileAccessForTest.set(0);
	}

	@After
	public void close() throws Exception {
		OnlineDeskewSession.captureHookForTest = null;
		RawFrameRenderer.rowHookForTest = null;
		if (session != null) {
			session.close();
			assertTrue(session.closed().get(15, TimeUnit.SECONDS).drained);
		}
		RawAcquisition.quietMsForTest = -1;
		assertEquals(0, RawAcquisition.eventThreadFileAccessForTest.get());
	}

	private File write(int time, int channel) throws Exception {
		return RawMultiFileTest.write(folder.getRoot(), time, channel);
	}

	private void open(String... keys) throws Exception {
		session = OnlineDeskewSession.open(RawMultiFileTest.settings(folder.getRoot(), keys));
	}

	private void ready(int time) throws Exception {
		await("both selected files ready", 10000, () -> {
			RawAcquisition.Snapshot s = session.snapshot();
			int i = s.indexOf(new RawAcquisition.TimeKey(1, time));
			return i >= 0 && s.capture(i).complete();
		});
	}

	@Test
	public void sourceSlotsAndTheirVolatileTwinsUseTheSelectedFiles() throws Exception {
		write(1, 1);
		write(1, 2);
		String[] keys = { "_Channel0002-right", "_Channel0001-left", "_Channel0002-left", "_Channel0001-right" };
		open(keys);
		ready(1);
		edtRun(() -> {
			List<SourceAndConverter<?>> sources = session.handle().getViewerPanel().state().getSources();
			assertEquals(keys.length, sources.size());
			for (int i = 0; i < keys.length; i++) {
				RawPartSource<?> source = (RawPartSource<?>) sources.get(i).getSpimSource();
				RawPartSource<?> twin = (RawPartSource<?>) sources.get(i).asVolatile().getSpimSource();
				assertEquals(keys[i], source.spec().key);
				assertSame(source.spec(), twin.spec());
				assertTrue(source.isPresent(0));
				assertTrue(twin.isPresent(0));
			}
		});
		RawFrameRequest r = session.frameRequest(0);
		assertEquals(2, r.inputs.size());
		assertEquals(4, r.layers.size());
		assertEquals("each layer reads its own channel", 2, r.input(r.layers.get(0)).entry.channel);
	}

	@Test
	public void aMissingSecondFileOpensAbsentAndItsArrivalMakesTheWholeTimeAvailable() throws Exception {
		write(1, 1);
		open("_Channel0001-left", "_Channel0002-right");
		assertNull(session.frameRequest(0));
		assertTrue(session.snapshot().capture(0).problem().contains("_Channel0002: missing file"));
		assertTrue(edt(session::statusText), edt(session::statusText).contains("_Channel0002: missing file"));
		edtRun(() -> {
			for (SourceAndConverter<?> source : session.handle().getViewerPanel().state().getSources())
				assertFalse(source.getSpimSource().isPresent(0));
		});
		File late = write(1, 2);
		RawFileNotifications.accepted(late);
		ready(1);
		RawFrameRequest r = session.frameRequest(0);
		assertEquals(2, r.inputs.size());
		assertEquals(RawStrictPlayer.Outcome.PRESENTED, edt(() -> session.requestStrictFrame(0)).get(10, TimeUnit.SECONDS).outcome);
	}

	@Test
	public void followWaitsForSelectedPartnersAndIgnoresUnselectedChannels() throws Exception {
		write(1, 2);
		write(1, 4);
		open("_Channel0002-left", "_Channel0004-right");
		edtRun(() -> { session.playback().setMode(RawPlayback.Mode.COMPLETED); session.setFollowLatest(true); });
		await("initial completed frame", 10000, () -> session.presenter() != null && session.presenter().shownRequest() != null);
		File f = write(2, 2);
		RawFileNotifications.accepted(f);
		await("new incomplete time on axis", 10000, () -> session.snapshot().size() == 2);
		assertFalse(session.snapshot().capture(1).complete());
		assertEquals(1, session.presenter().shownRequest().time.timeId);
		f = write(2, 4);
		RawFileNotifications.accepted(f);
		ready(2);
		await("follow complete pair", 10000, () -> session.presenter().shownRequest() != null && session.presenter().shownRequest().time.timeId == 2);
		assertEquals(Arrays.asList(2, 4), session.snapshot().channels);
		assertTrue(edt(() -> session.isFollowingLatest()));
		RawFileNotifications.accepted(write(999, 1));
		Thread.sleep(300);
		assertEquals("unselected-only time is not on this axis", 2, session.snapshot().size());
		assertEquals(2, session.presenter().shownRequest().time.timeId);
	}

	@Test
	public void capturingARequestKeepsTheSecondFilesOriginalPublicationAndGeometry() throws Exception {
		write(1, 1);
		write(1, 2);
		open("_Channel0001-left", "_Channel0002-right");
		RawAcquisition.Entry second = session.snapshot().entry(0, 2);
		RawAcquisition.Entry.Shown was = second.shown();
		OnlineDeskewSession.captureHookForTest = () -> {
			OnlineDeskewSession.captureHookForTest = null;
			assertTrue(second.markChanged(was.key, "during capture"));
		};
		RawFrameRequest r = session.frameRequest(0);
		assertSame(was, r.inputs.get(1).publication);
		assertArrayEquals(RawMultiFileTest.GEOMETRY.sourceToWorld(was.layout.dimensions(), r.layers.get(1).spec, null).getRowPackedCopy(),
				r.layers.get(1).sourceToWorld.getRowPackedCopy(), 1e-12);
		assertFalse("captured revocation cannot be presented", edt(() -> session.strictPlayer()).request(r).get(10, TimeUnit.SECONDS).outcome
				== RawStrictPlayer.Outcome.PRESENTED);
	}

	@Test
	public void rapidScrubbingCommitsOneTimeForBothFilesAndTheCorrectPixels() throws Exception {
		for (int t = 1; t <= 4; t++) { write(t, 1); write(t, 2); }
		open("_Channel0002-right", "_Channel0001-left");
		ready(4);
		CountDownLatch drawing = new CountDownLatch(1), release = new CountDownLatch(1);
		AtomicInteger commits = new AtomicInteger();
		AtomicReference<Throwable> error = new AtomicReference<Throwable>();
		TiffRawVolumeStore oracle = new TiffRawVolumeStore(8L << 20, 512, 2, null);
		try (RawFrameRenderer renderer = new RawFrameRenderer(1)) {
			edtRun(() -> {
				session.strictPlayer();
				session.presenter().setCommitObserver((r, image) -> {
					try {
						assertEquals(2, r.inputs.size());
						for (RawFrameRequest.Input input : r.inputs) assertEquals(r.time, input.entry.time);
						BufferedImage expected = renderer.render(r, oracle::blocking, () -> false);
						assertArrayEquals(expected.getRGB(0, 0, r.width, r.height, null, 0, r.width),
								image.getRGB(0, 0, r.width, r.height, null, 0, r.width));
						commits.incrementAndGet();
					} catch (Throwable ex) { error.compareAndSet(null, ex); }
				});
			});
			RawFrameRenderer.rowHookForTest = row -> {
				if (Thread.currentThread().getName().contains("OPM")) {
					drawing.countDown();
					try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
				}
			};
			List<CompletableFuture<RawStrictPlayer.Result>> results = new ArrayList<CompletableFuture<RawStrictPlayer.Result>>();
			results.add(edt(() -> session.requestStrictFrame(0)));
			assertTrue(drawing.await(10, TimeUnit.SECONDS));
			edtRun(() -> { for (int i = 1; i < 25; i++) results.add(session.requestStrictFrame(i % 4)); });
			RawFrameRenderer.rowHookForTest = null;
			release.countDown();
			assertEquals(RawStrictPlayer.Outcome.PRESENTED, results.get(results.size() - 1).get(15, TimeUnit.SECONDS).outcome);
			if (error.get() != null) throw new AssertionError(error.get());
			assertTrue(commits.get() > 0);
			assertEquals(1, session.presenter().shownRequest().time.timeId);
			for (CompletableFuture<?> result : results) assertTrue("every obsolete request terminates", result.isDone());
		} finally {
			RawFrameRenderer.rowHookForTest = null;
			release.countDown();
			oracle.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}
}
