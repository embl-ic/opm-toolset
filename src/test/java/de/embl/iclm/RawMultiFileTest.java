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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import javax.swing.JPanel;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.viewer.Interpolation;
import net.imglib2.realtransform.AffineTransform3D;

/** M4-A: selected-file groups, immutable frames, per-file coverage, and one presentation gate. */
public class RawMultiFileTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	static final int W = 32, H = 24, D = 16;
	static final RawGeometry GEOMETRY = new RawGeometry(0.265, 0.116, 33.5, "multi-file test");

	@After
	public void reset() {
		RawAcquisition.quietMsForTest = -1;
	}

	static File write(File folder, int time, int channel) throws Exception {
		return RawTiffFixture.write(new File(folder, String.format("m_Position0001_Time%06d_Channel%04d_Frames_1_%d.tiff", time, channel, D)),
				W, H, D, channel == 1 ? 8 : 6, D, (x, y, z) -> 100 * time + 2000 * channel + 7 * x + 3 * y + z);
	}

	static OnlineDeskewView.Settings settings(File folder, String... keys) {
		OnlineDeskewView.Settings s = new OnlineDeskewView.Settings();
		s.folder = folder.getAbsolutePath();
		s.parameterSource = OnlineDeskewView.PARAMETER_SOURCES[1];
		s.budgetMb = 64;
		s.channels.combineAcquisitionChannels = true;
		Arrays.fill(s.channels.channelOrder, BatchChannelOperation.SKIP_CHANNEL);
		System.arraycopy(keys, 0, s.channels.channelOrder, 0, keys.length);
		return s;
	}

	static RawAcquisition catalogue(File folder, int... channels) throws Exception {
		RawAcquisition a = RawAcquisition.scan(folder);
		for (RawAcquisition.Entry e : a.snapshot(1, channels).entries) e.validate();
		return a;
	}

	static RawFrameRequest request(RawAcquisition.Snapshot s, int time, long revision, Interpolation method) {
		RawAcquisition.Group g = s.capture(time);
		List<RawFrameRequest.Input> inputs = new ArrayList<RawFrameRequest.Input>();
		List<RawFrameRequest.Layer> layers = new ArrayList<RawFrameRequest.Layer>();
		for (int c = 0; c < g.channels.size(); c++) {
			RawFrameRequest.Input input = new RawFrameRequest.Input(g.entries.get(c), g.publications.get(c));
			inputs.add(input);
			for (boolean left : new boolean[] { true, false }) {
				RawSourceSpec spec = RawSourceSpec.half(null, g.channels.get(c), left, false, input.layout.width,
						c == 0 ? 0x00ff00 : 0xff00ff);
				layers.add(new RawFrameRequest.Layer(spec, GEOMETRY.sourceToWorld(input.layout.dimensions(), spec, null), 0, 12000,
						0xff000000 | spec.rgb));
			}
		}
		AffineTransform3D view = RawFrameFixture.view(inputs.get(0).layout.dimensions(), 0.45, 0.17, 96, 120);
		return new RawFrameRequest(revision, time, inputs, view, 96, 120, method, layers);
	}

	@Test
	public void selectionUsesSlotOrderAndOnlySelectedFiles() {
		OnlineDeskewView.Settings s = settings(folder.getRoot(), "_Channel0002-right", BatchChannelOperation.SKIP_CHANNEL,
				"_Channel0004-left", "_Channel0002-left", "_Channel0002-right");
		assertEquals(Arrays.asList("_Channel0002-right", "_Channel0004-left", "_Channel0002-left", "_Channel0002-right"), s.sourceKeys());
		assertArrayEquals(new int[] { 2, 4 }, s.requiredChannels());
		s.channels.combineAcquisitionChannels = false;
		s.channel = 3;
		assertArrayEquals(new int[] { 3 }, s.requiredChannels());
		assertEquals(Arrays.asList("_Channel0003-left", "_Channel0003-right"), s.sourceKeys());
	}

	@Test
	public void emptyOrUnsupportedSelectionIsRefused() {
		for (String[] keys : new String[][] { {}, { "_Channel0001" }, { "wrong" } }) {
			try { settings(folder.getRoot(), keys).requiredChannels(); fail("invalid selection accepted"); }
			catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
		}
	}

	@Test
	public void missingLateAndUnselectedFilesHaveDifferentPolicies() throws Exception {
		write(folder.getRoot(), 1, 2);
		write(folder.getRoot(), 2, 4);
		write(folder.getRoot(), 99, 1);
		RawAcquisition a = catalogue(folder.getRoot(), 2, 4);
		RawAcquisition.Snapshot before = a.snapshot(1, new int[] { 4, 2 });
		assertEquals(2, before.size());
		assertFalse(before.capture(0).complete());
		assertTrue(before.capture(0).problem(), before.capture(0).problem().contains("_Channel0004: missing file"));
		assertFalse(before.capture(1).complete());
		File late = write(folder.getRoot(), 1, 4);
		a.hint(late);
		a.announce(late);
		RawAcquisition.Snapshot after = a.snapshot(1, new int[] { 2, 4 });
		after.entry(0, 4).recheck(System.currentTimeMillis());
		assertTrue(after.extendsFrom(before));
		assertTrue(after.capture(0).problem(), after.capture(0).complete());
		assertNull("old mapping remains frozen", before.entry(0, 4));
		assertFalse(a.snapshot(1, new int[] { 2, 3 }).extendsFrom(before));
		assertEquals(3, after.entries.size());
	}

	@Test
	public void progressivePresenceFreezesBothPublicationsAndWithdrawsTogetherNextFrame() throws Exception {
		write(folder.getRoot(), 1, 1);
		write(folder.getRoot(), 1, 2);
		RawAcquisition a = catalogue(folder.getRoot(), 1, 2);
		RawAcquisition.Snapshot s = a.snapshot(1, new int[] { 1, 2 });
		RawPresence p = new RawPresence(() -> s);
		RawPresence.Frame first = p.frame();
		assertTrue(first.present(0));
		RawAcquisition.Entry second = s.entry(0, 2);
		RawAcquisition.Entry.Shown was = second.shown();
		assertTrue(second.markChanged(was.key, "test withdrawal"));
		assertTrue(first.present(0));
		assertSame(was, first.descriptor(0, 2));
		p.prepareNextFrame();
		assertFalse(p.present(0));
		assertNotNull(p.frame().descriptor(0, 1));
	}

	@Test
	public void aRequestCannotMixTimePointsOrUseAnUnselectedFile() throws Exception {
		write(folder.getRoot(), 1, 1);
		write(folder.getRoot(), 2, 2);
		RawAcquisition a = catalogue(folder.getRoot(), 1, 2);
		RawAcquisition.Snapshot s = a.snapshot(1, new int[] { 1, 2 });
		RawFrameRequest.Input one = new RawFrameRequest.Input(s.entry(0, 1), s.entry(0, 1).shown());
		RawFrameRequest.Input two = new RawFrameRequest.Input(s.entry(1, 2), s.entry(1, 2).shown());
		try {
			new RawFrameRequest(1, 0, Arrays.asList(one, two), new AffineTransform3D(), 20, 20, Interpolation.NLINEAR,
					Collections.<RawFrameRequest.Layer>emptyList());
			fail("mixed time points");
		} catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("time points")); }
		RawSourceSpec wrong = RawSourceSpec.half(null, 2, true, false, W, 0xffffff);
		try {
			new RawFrameRequest(1, 0, Collections.singletonList(one), new AffineTransform3D(), 20, 20, Interpolation.NLINEAR,
					Collections.singletonList(new RawFrameRequest.Layer(wrong, new AffineTransform3D(), 0, 10000, -1)));
			fail("unselected file sampled as the first file");
		} catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("_Channel0002")); }
	}

	@Test
	public void perFileCoverageAndPinnedRenderingMatchTheBlockingSampler() throws Exception {
		write(folder.getRoot(), 1, 1);
		write(folder.getRoot(), 1, 2);
		RawAcquisition.Snapshot s = catalogue(folder.getRoot(), 1, 2).snapshot(1, new int[] { 1, 2 });
		TiffRawVolumeStore store = new TiffRawVolumeStore(8L << 20, TiffRawVolumeStore.cellBytes(s.entry(0).layout()), 2, null);
		try (RawFrameRenderer renderer = new RawFrameRenderer(2)) {
			for (Interpolation method : Interpolation.values()) {
				RawFrameRequest r = request(s, 0, 1, method);
				List<TiffRawVolumeStore.Want> wants = RawStrictPlayer.wants(r);
				assertEquals(2, wants.size());
				for (TiffRawVolumeStore.Want w : wants) assertFalse(w.cells.isEmpty());
				try (TiffRawVolumeStore.Ready ready = store.prepare(1, wants, () -> false).get(10, TimeUnit.SECONDS)) {
					BufferedImage pinned = renderer.render(r, ready, () -> false);
					BufferedImage blocking = renderer.render(r, store::blocking, () -> false);
					assertArrayEquals(blocking.getRGB(0, 0, 96, 120, null, 0, 96), pinned.getRGB(0, 0, 96, 120, null, 0, 96));
					assertEquals(0, ready.misses());
					assertTrue(ready.isCurrentAndValid());
				}
			}
		} finally { store.closeAsync().get(10, TimeUnit.SECONDS); }
	}

	@Test
	public void theUnionIsRefusedBeforeReadingWhenOnlyOneFileFits() throws Exception {
		write(folder.getRoot(), 1, 1);
		write(folder.getRoot(), 1, 2);
		RawAcquisition.Snapshot s = catalogue(folder.getRoot(), 1, 2).snapshot(1, new int[] { 1, 2 });
		List<TiffRawVolumeStore.Want> wants = new ArrayList<TiffRawVolumeStore.Want>();
		for (RawAcquisition.Entry e : s.entries) {
			BitSet all = new BitSet();
			all.set(0, (int) (e.layout().depth * ((H + e.layout().rowsPerStrip - 1) / e.layout().rowsPerStrip)));
			wants.add(new TiffRawVolumeStore.Want(e.key(), e.layout(), all));
		}
		long cell = TiffRawVolumeStore.cellBytes(s.entry(0).layout());
		long budget = Math.max(TiffRawVolumeStore.minimumBudget(cell, 1), 2L * W * H * D + 8 * cell);
		TiffRawVolumeStore store = new TiffRawVolumeStore(budget, cell, 1, null);
		try {
			try { store.prepare(1, wants, () -> false).get(10, TimeUnit.SECONDS); fail("both files exceed one budget"); }
			catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof TiffRawVolumeStore.BudgetException); }
			assertEquals(0, store.stats().loads);
			try (TiffRawVolumeStore.Ready one = store.prepare(2, wants.subList(0, 1), () -> false).get(10, TimeUnit.SECONDS)) {
				assertTrue(one.isCurrentAndValid());
			}
			assertTrue(store.stats().loads > 0);
		} finally { store.closeAsync().get(10, TimeUnit.SECONDS); }
	}

	@Test
	public void evenAHiddenSelectedFileIsCheckedAfterRendering() throws Exception {
		write(folder.getRoot(), 1, 1);
		write(folder.getRoot(), 1, 2);
		RawAcquisition.Snapshot s = catalogue(folder.getRoot(), 1, 2).snapshot(1, new int[] { 1, 2 });
		RawFrameRequest all = request(s, 0, 1, Interpolation.NLINEAR);
		RawFrameRequest r = new RawFrameRequest(2, 0, all.inputs, all.viewer, all.width, all.height, all.interpolation, all.layers.subList(0, 2));
		List<TiffRawVolumeStore.Want> wants = RawStrictPlayer.wants(r);
		assertTrue("invisible file needs no cells", wants.get(1).cells.isEmpty());
		assertEquals("but its generation still belongs to the frame", 2, wants.size());
		TiffRawVolumeStore store = new TiffRawVolumeStore(8L << 20, 512, 2, null);
		try (TiffRawVolumeStore.Ready ready = store.prepare(1, wants, () -> false).get(10, TimeUnit.SECONDS)) {
			assertTrue(ready.isCurrentAndValid());
			RawFrameFixture.touch(s.entry(0, 2));
			assertFalse("any selected file changing invalidates the whole frame", ready.isCurrentAndValid());
		} finally { store.closeAsync().get(10, TimeUnit.SECONDS); }
	}

	@Test
	public void prefetchedCellsAreReusedOnlyWhenBothFilesCoverageFits() throws Exception {
		write(folder.getRoot(), 1, 1);
		write(folder.getRoot(), 1, 2);
		RawAcquisition.Snapshot s = catalogue(folder.getRoot(), 1, 2).snapshot(1, new int[] { 1, 2 });
		RawFrameRequest first = request(s, 0, 1, Interpolation.NLINEAR);
		List<RawFrameRequest.Layer> shifted = new ArrayList<RawFrameRequest.Layer>();
		for (RawFrameRequest.Layer l : first.layers) {
			AffineTransform3D transform = l.sourceToWorld.copy();
			if (l.spec.acquisitionChannel == 2) transform.translate(0, 0, 0.6);
			shifted.add(new RawFrameRequest.Layer(l.spec, transform, l.min, l.max, l.argb));
		}
		RawFrameRequest next = new RawFrameRequest(2, 0, first.inputs, first.viewer, first.width, first.height, first.interpolation, shifted);
		BitSet newSecond = (BitSet) RawStrictPlayer.wants(next).get(1).cells.clone();
		newSecond.andNot(RawStrictPlayer.wants(first).get(1).cells);
		assertFalse("test asks new cells of the second file", newSecond.isEmpty());
		TiffRawVolumeStore store = new TiffRawVolumeStore(8L << 20, 512, 2, null);
		JPanel canvas = new JPanel();
		canvas.setSize(96, 120);
		RawPresenter presenter = new RawPresenter(canvas);
		RawStrictPlayer player = new RawStrictPlayer(store, new RawFrameRenderer(2), presenter);
		try {
			player.prefetch(first);
			java.lang.reflect.Field prefetched = RawStrictPlayer.class.getDeclaredField("prefetched");
			prefetched.setAccessible(true);
			OnlineDeskewLiveGuiTest.await("prefetched pair", 5000, () -> {
				synchronized (player) {
					try { return prefetched.get(player) != null; } catch (IllegalAccessException e) { throw new AssertionError(e); }
				}
			});
			CompletableFuture<RawStrictPlayer.Result> result = player.request(next);
			OnlineDeskewLiveGuiTest.await("frame complete", 5000, () -> {
				Graphics2D g = new BufferedImage(96, 120, BufferedImage.TYPE_INT_ARGB).createGraphics();
				try { presenter.drawOverlays(g); } finally { g.dispose(); }
				return result.isDone();
			});
			assertEquals(RawStrictPlayer.Outcome.PRESENTED, result.get().outcome);
			assertEquals("incomplete second-file coverage cannot be reused", 0, player.prefetchCounts()[1]);
			assertSame(next, presenter.shownRequest());
		} finally {
			player.close();
			assertTrue(player.awaitStopped(10000));
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	public void secondFileRevocationRefusesPendingAndShownFrames() throws Exception {
		write(folder.getRoot(), 1, 1);
		write(folder.getRoot(), 1, 2);
		RawAcquisition.Snapshot s = catalogue(folder.getRoot(), 1, 2).snapshot(1, new int[] { 1, 2 });
		for (int phase = 0; phase < 3; phase++) {
			RawFrameRequest r = request(s, 0, 1, Interpolation.NLINEAR);
			Set<RawAcquisition.Entry.Shown> revoked = ConcurrentHashMap.newKeySet();
			JPanel canvas = new JPanel();
			canvas.setSize(96, 120);
			RawPresenter presenter = new RawPresenter(canvas, revoked);
			try {
				presenter.accept(1);
				if (phase == 0) revoked.add(r.inputs.get(1).publication);
				CompletableFuture<RawPresenter.Presented> result = presenter.present(new RawPresenter.Frame(r,
						new BufferedImage(96, 120, BufferedImage.TYPE_INT_ARGB), null));
				if (phase == 1) revoked.add(r.inputs.get(1).publication); // commit check, without invoking the withdrawal callback
				Graphics2D g = new BufferedImage(96, 120, BufferedImage.TYPE_INT_ARGB).createGraphics();
				try { presenter.drawOverlays(g); } finally { g.dispose(); }
				if (phase < 2) {
					assertTrue(result.isCompletedExceptionally());
					assertNull(presenter.shownRequest());
				} else {
					assertSame(r, result.get(2, TimeUnit.SECONDS).request);
					assertEquals(r.keys, presenter.watchedKeys());
					presenter.revoke(r.inputs.get(1).publication);
					assertNull(presenter.shownRequest());
				}
			} finally { presenter.stop(); }
		}
	}
}
