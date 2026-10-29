package de.embl.iclm;

import static de.embl.iclm.OnlineDeskewLiveGuiTest.await;
import static de.embl.iclm.OnlineDeskewLiveGuiTest.edt;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
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
import net.imglib2.realtransform.AffineTransform3D;

/** M4-B's real viewer sources, volatile twins, captured requests and presentation with general layouts. */
public class OnlineDeskewPlacementGuiTest {

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
		if (session != null) {
			session.close();
			assertTrue(session.closed().get(15, TimeUnit.SECONDS).drained);
		}
		RawAcquisition.quietMsForTest = -1;
		assertEquals(0, RawAcquisition.eventThreadFileAccessForTest.get());
	}

	private OnlineDeskewView.Settings settings(boolean flipLeft, String... keys) throws Exception {
		OnlineDeskewView.Settings settings = RawMultiFileTest.settings(folder.getRoot(), keys);
		File csv = new File(folder.getRoot(), "placement.csv");
		assertTrue(RawPlacementTest.tagged().save(csv));
		settings.alignmentCsv = csv.getAbsolutePath();
		settings.channels.flipHalf = flipLeft ? BatchChannelOperation.FLIP_LEFT : BatchChannelOperation.FLIP_RIGHT;
		return settings;
	}

	@Test
	public void wholeAndHalfSlotsHaveTheirOwnPixelsTransformsAndCompletedImage() throws Exception {
		RawMultiFileTest.write(folder.getRoot(), 1, 1);
		RawMultiFileTest.write(folder.getRoot(), 1, 2);
		String[] keys = { RawPlacementTest.W2, RawPlacementTest.L1, RawPlacementTest.R2, RawPlacementTest.W2 };
		session = OnlineDeskewSession.open(settings(true, keys));
		await("selected group ready", 10000, () -> session.snapshot().capture(0).complete());
		session.presence().prepareNextFrame();
		List<SourceAndConverter<?>> sources = edt(() -> session.handle().getViewerPanel().state().getSources());
		assertEquals(4, sources.size());
		RawFrameRequest request = session.frameRequest(0);
		assertNotNull(request);
		for (int i = 0; i < keys.length; i++) {
			RawPartSource<?> source = (RawPartSource<?>) sources.get(i).getSpimSource();
			assertEquals(keys[i], source.getName());
			boolean whole = ChannelOperationSettings.isWholeSource(keys[i]);
			assertEquals(whole ? 32 : 16, source.getSource(0, 0).dimension(0));
			if (whole) assertFalse(source.spec().mirror);
			AffineTransform3D actual = new AffineTransform3D(), twin = new AffineTransform3D();
			source.getSourceTransform(0, 0, actual);
			sources.get(i).asVolatile().getSpimSource().getSourceTransform(0, 0, twin);
			assertArrayEquals(actual.getRowPackedCopy(), twin.getRowPackedCopy(), 0);
			assertArrayEquals(actual.getRowPackedCopy(), request.layers.get(i).sourceToWorld.getRowPackedCopy(), 0);
		}
		TiffRawVolumeStore oracle = new TiffRawVolumeStore(8L << 20, 512, 2, null);
		try (RawFrameRenderer renderer = new RawFrameRenderer(1)) {
			AtomicReference<Throwable> error = new AtomicReference<Throwable>();
			AtomicInteger compared = new AtomicInteger();
			edt(() -> {
				session.strictPlayer();
				session.presenter().setCommitObserver((shown, actual) -> {
					try {
						BufferedImage expected = renderer.render(shown, oracle::blocking, () -> false);
						assertArrayEquals(expected.getRGB(0, 0, shown.width, shown.height, null, 0, shown.width),
								actual.getRGB(0, 0, shown.width, shown.height, null, 0, shown.width));
						compared.incrementAndGet();
					} catch (Throwable failure) { error.compareAndSet(null, failure); }
				});
				return null;
			});
			assertEquals(RawStrictPlayer.Outcome.PRESENTED, edt(() -> session.requestStrictFrame(0)).get(15, TimeUnit.SECONDS).outcome);
			if (error.get() != null) throw new AssertionError(error.get());
			assertTrue(compared.get() > 0);
		} finally { oracle.closeAsync().get(10, TimeUnit.SECONDS); }
	}

	@Test
	public void lateAndLaterWidthUseTheirOwnFlipLeftPlacementInBothModes() throws Exception {
		RawMultiFileTest.write(folder.getRoot(), 1, 1);
		session = OnlineDeskewSession.open(settings(true, RawPlacementTest.L1, RawPlacementTest.L2, RawPlacementTest.R2));
		assertNull(session.frameRequest(0));
		for (int time = 1; time <= 2; time++) {
			if (time == 2) RawFileNotifications.accepted(RawMultiFileTest.write(folder.getRoot(), time, 1));
			int width = time == 1 ? 54 : 70;
			File file = new File(folder.getRoot(), String.format("m_Position0001_Time%06d_Channel0002_Frames_1_16.tiff", time));
			RawTiffFixture.write(file, width, 24, 16, 6, 16, (x, y, z) -> 4000 + 7 * x + 3 * y + z);
			RawFileNotifications.accepted(file);
			final int index = time - 1;
			await("late width published", 10000, () -> session.snapshot().size() > index && session.snapshot().capture(index).complete());
			if (index > 0) edt(() -> { session.stopStrictPlayback(); return null; });
			session.presence().prepareNextFrame();
			List<SourceAndConverter<?>> sources = edt(() -> session.handle().getViewerPanel().state().getSources());
			RawFrameRequest request = session.frameRequest(index);
			for (int i = 1; i <= 2; i++) {
				RawSourceSpec independentlyBuilt = RawSourceSpec.half(RawPlacementTest.tagged(), 2, i == 1, true, width, 0);
				double[] expected = RawPlacementTest.G.sourceToWorld(new long[] { width, 24, 16 }, independentlyBuilt, null).getRowPackedCopy();
				AffineTransform3D actual = new AffineTransform3D();
				sources.get(i).getSpimSource().getSourceTransform(index, 0, actual);
				assertArrayEquals("progressive current-file width", expected, actual.getRowPackedCopy(), 1e-12);
				sources.get(i).asVolatile().getSpimSource().getSourceTransform(index, 0, actual);
				assertArrayEquals("volatile current-file width", expected, actual.getRowPackedCopy(), 1e-12);
				assertArrayEquals("completed captured-file width", expected, request.layers.get(i).sourceToWorld.getRowPackedCopy(), 1e-12);
			}
			assertEquals(RawStrictPlayer.Outcome.PRESENTED, edt(() -> session.requestStrictFrame(index)).get(15, TimeUnit.SECONDS).outcome);
		}
	}
}
