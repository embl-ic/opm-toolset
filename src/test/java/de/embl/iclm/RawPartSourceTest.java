package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.cache.CacheControl;
import bdv.viewer.BasicViewerState;
import bdv.viewer.DisplayMode;
import bdv.viewer.Interpolation;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.render.AccumulateProjectorARGB;
import bdv.viewer.render.MultiResolutionRenderer;
import bdv.viewer.render.RenderTarget;
import bdv.viewer.render.awt.BufferedImageRenderResult;
import net.imglib2.RandomAccess;
import net.imglib2.display.RealARGBColorConverter;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedShortType;

/**
 * The Online Deskew View's BigDataViewer sources (joint contract C5.1): each time index shows its
 * own file through the shared store, a time point that cannot be shown is absent, each file's own
 * height enters its transform, and BigDataViewer's renderer draws both camera halves.
 *
 * <p>The render test builds BigDataViewer's renderer directly, which only test code may do: its
 * constructor changed between the managed release and the one Fiji ships (contract C5.3).
 */
public class RawPartSourceTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final int W = 26;
	private static final RawGeometry GEOMETRY = new RawGeometry(0.265, 0.116, 33.5, "test");

	private RawAcquisition acquisition(int[] heights, RawTiffFixture.Pixels[] pixels, int[] written) throws IOException {
		for (int t = 0; t < heights.length; t++)
			RawTiffFixture.write(new File(folder.getRoot(), String.format("p_Time%06d_Channel0001_Frames_1_9.tiff", t + 1)),
					W, heights[t], 9, 8, written[t], pixels[t]);
		return RawAcquisition.scan(folder.getRoot());
	}

	private static RawPartSource<UnsignedShortType> source(RawAcquisition a, TiffRawVolumeStore store, boolean left) {
		List<RawAcquisition.TimeKey> times = a.times(1, 1);
		RawAcquisition.Entry first = a.entry(times.get(0), 1);
		first.validate();
		return new RawPartSource<UnsignedShortType>(a, times, RawSourceSpec.half(null, 1, left, false, W, left ? 0x00ff00 : 0xff00ff),
				GEOMETRY, store::blocking, new UnsignedShortType(), first.layout().dimensions());
	}

	private static TiffRawVolumeStore store() {
		return new TiffRawVolumeStore(64L << 20, 2L * W * 8, 2, null);
	}

	@Test
	public void eachTimeIndexShowsItsOwnFile() throws Exception {
		RawTiffFixture.Pixels second = (x, y, k) -> 40000 + x + y + k;
		RawAcquisition a = acquisition(new int[] { 17, 17 }, new RawTiffFixture.Pixels[] { RawTiffFixture.RAMP, second },
				new int[] { 9, 9 });
		for (RawAcquisition.TimeKey t : a.times(1, 1)) a.entry(t, 1).validate();
		TiffRawVolumeStore store = store();
		try {
			RawPartSource<UnsignedShortType> left = source(a, store, true);
			assertTrue(left.isPresent(0));
			assertTrue(left.isPresent(1));
			assertFalse(left.isPresent(2));
			RandomAccess<UnsignedShortType> t0 = left.getSource(0, 0).randomAccess(), t1 = left.getSource(1, 0).randomAccess();
			t0.setPosition(new long[] { 4, 6, 3 });
			t1.setPosition(new long[] { 4, 6, 3 });
			assertEquals(RawTiffFixture.RAMP.at(4, 6, 3), t0.get().get());
			assertEquals(second.at(4, 6, 3), t1.get().get());
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	public void aTimePointThatCannotBeShownIsAbsent() throws Exception {
		RawAcquisition a = acquisition(new int[] { 17, 17 },
				new RawTiffFixture.Pixels[] { RawTiffFixture.RAMP, RawTiffFixture.RAMP }, new int[] { 9, 4 });
		TiffRawVolumeStore store = store();
		try {
			RawPartSource<UnsignedShortType> left = source(a, store, true);
			assertFalse("not checked yet is not present", left.isPresent(1));
			assertEquals(RawAcquisition.Readiness.INCOMPLETE, a.entry(a.times(1, 1).get(1), 1).validate());
			assertFalse("4 of 9 planes is not present", left.isPresent(1));
			assertTrue(left.isPresent(0));
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	public void eachFilesOwnHeightEntersItsTransform() throws Exception {
		RawAcquisition a = acquisition(new int[] { 17, 21 },
				new RawTiffFixture.Pixels[] { RawTiffFixture.RAMP, RawTiffFixture.RAMP }, new int[] { 9, 9 });
		for (RawAcquisition.TimeKey t : a.times(1, 1)) a.entry(t, 1).validate();
		TiffRawVolumeStore store = store();
		try {
			RawPartSource<UnsignedShortType> right = source(a, store, false);
			AffineTransform3D t0 = new AffineTransform3D(), t1 = new AffineTransform3D();
			right.getSourceTransform(0, 0, t0);
			right.getSourceTransform(1, 0, t1);
			assertArrayEquals(GEOMETRY.sourceToWorld(new long[] { W, 17, 9 }, right.spec(), null).getRowPackedCopy(),
					t0.getRowPackedCopy(), 1e-12);
			assertArrayEquals(GEOMETRY.sourceToWorld(new long[] { W, 21, 9 }, right.spec(), null).getRowPackedCopy(),
					t1.getRowPackedCopy(), 1e-12);
			assertNotEquals("the Z translation follows the height", t0.get(2, 3), t1.get(2, 3), 1e-6);
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	public void bigDataViewersRendererDrawsBothHalves() throws Exception {
		RawTiffFixture.Pixels bright = (x, y, k) -> 1000;
		RawAcquisition a = acquisition(new int[] { 17 }, new RawTiffFixture.Pixels[] { bright }, new int[] { 9 });
		TiffRawVolumeStore store = store();
		try {
			BasicViewerState state = new BasicViewerState();
			for (boolean left : new boolean[] { true, false }) {
				RawPartSource<UnsignedShortType> s = source(a, store, left);
				RealARGBColorConverter<UnsignedShortType> c = RealARGBColorConverter.create(new UnsignedShortType(), 0, 1000);
				c.setColor(new ARGBType(0xff000000 | s.spec().rgb));
				SourceAndConverter<UnsignedShortType> soc = new SourceAndConverter<UnsignedShortType>(s, c);
				state.addSource(soc);
				state.setSourceActive(soc, true);
			}
			state.setDisplayMode(DisplayMode.FUSED);
			state.setNumTimepoints(1);
			state.setInterpolation(Interpolation.NEARESTNEIGHBOR);
			// look down the deskewed Z through the middle of the volume, 40 screen pixels per micrometre
			long[] grid = GEOMETRY.deskewedGrid(W / 2, 17, 9);
			AffineTransform3D view = new AffineTransform3D();
			view.translate(0, 0, -0.5 * grid[2] * GEOMETRY.dxyUm);
			view.scale(40);
			state.setViewerTransform(view);
			final BufferedImageRenderResult result = new BufferedImageRenderResult();
			RenderTarget<BufferedImageRenderResult> target = new RenderTarget<BufferedImageRenderResult>() {
				@Override public BufferedImageRenderResult getReusableRenderResult() { return result; }
				@Override public BufferedImageRenderResult createRenderResult() { return new BufferedImageRenderResult(); }
				@Override public void setRenderResult(BufferedImageRenderResult r) {}
				@Override public int getWidth() { return 120; }
				@Override public int getHeight() { return 120; }
			};
			MultiResolutionRenderer renderer = new MultiResolutionRenderer(target, () -> {}, new double[] { 1 }, 0, 2, null,
					false, AccumulateProjectorARGB.factory, new CacheControl.Dummy());
			renderer.requestRepaint();
			renderer.paint(state);
			BufferedImage image = result.getBufferedImage();
			boolean green = false, magenta = false;
			for (int y = 0; y < image.getHeight(); y++)
				for (int x = 0; x < image.getWidth(); x++) {
					int rgb = image.getRGB(x, y);
					int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
					green |= g > 200;
					magenta |= r > 200 && b > 200;
				}
			assertTrue("the left half is drawn", green);
			assertTrue("the mirrored right half is drawn", magenta);
			renderer.kill();
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}
}
