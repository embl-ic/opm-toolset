package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.junit.Assume;
import org.junit.Test;

import bdv.viewer.BasicViewerState;
import bdv.viewer.DisplayMode;
import bdv.viewer.Interpolation;
import bdv.viewer.SourceAndConverter;
import net.imglib2.display.RealARGBColorConverter;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedShortType;

/**
 * Completed-frame playback over a real acquisition, timed (joint contracts C6.4, C9.1): the
 * reference protocol's 1600 x 1000 XY view of both camera halves of one file, nearest and safe
 * trilinear, through consecutive time points and then again with their cells in the store. Each
 * stage - preparation, drawing, presentation - is reported apart, beside BigDataViewer's own
 * blocking renderer drawing the same request from the cells the frame has just read. The first
 * pass starts with the store empty; the operating system may hold the files in its own cache,
 * which this test does not control. Skipped unless the data are named:
 * <pre>
 * mvn test -Dtest=RawStrictPlaybackRealDataTest -Dopm.test.jvm.args=-Xmx6g -Dopm.test.data=E:\OPM\3_timelapse_0
 * </pre>
 * It asserts what a frame must be - presented, complete, read only from its lease, within the
 * budget - and nothing about time: a threshold on someone else's disk fails for the wrong reason.
 */
public class RawStrictPlaybackRealDataTest {

	private static final int SCREEN_W = 1600, SCREEN_H = 1000, TIMES = 6;

	@Test
	public void strictFramesOfARealAcquisitionAreTimedStageByStage() throws Exception {
		String data = System.getProperty("opm.test.data", "");
		Assume.assumeTrue("Set -Dopm.test.data", !data.isEmpty());
		File folder = new File(data);
		RawGeometry geometry = RawGeometry.fromExperimentalParameters(new File(folder, "ExperimentalParameters.txt"));
		RawAcquisition acquisition = RawAcquisition.scan(folder);
		List<RawAcquisition.TimeKey> times = acquisition.times(1, 1);
		for (int t = 0; t < TIMES; t++) {
			RawAcquisition.Entry e = acquisition.entry(times.get(t), 1);
			assertEquals(e.detail(), RawAcquisition.Readiness.COMPLETE, e.validate());
		}
		RawAcquisition.Entry first = acquisition.entry(times.get(0), 1);
		RawTiffLayout layout = first.layout();
		long[] dims = layout.dimensions();
		int width = layout.width;
		RawSourceSpec[] specs = { RawSourceSpec.half(null, 1, true, false, width, 0x00ff00),
				RawSourceSpec.half(null, 1, false, false, width, 0xff00ff) };
		TiffRawVolumeStore ranging = new TiffRawVolumeStore(1L << 30, TiffRawVolumeStore.cellBytes(layout), 4, null);
		double[] range = OnlineDeskewSession.displayRange(RawSampling.crop(ranging.blocking(first.key(), layout), specs[0]));
		ranging.closeAsync().get(30, TimeUnit.SECONDS);
		AffineTransform3D view = xyView(geometry, dims, specs);
		int cores = Runtime.getRuntime().availableProcessors();
		System.out.printf(Locale.US, "real acquisition %s: %d x %d x %d, %d rows per strip (%s cells); %d cores; range %.0f-%.0f%n",
				folder.getName(), dims[0], dims[1], dims[2], layout.rowsPerStrip,
				RawVolumeStore.CacheStats.size(TiffRawVolumeStore.cellBytes(layout)), cores, range[0], range[1]);
		System.out.println("interpolation\tpass\ttime\tcells\tlease\tprepare_ms\trender_ms\tpresent_ms\tlatency_ms\tbdv_blocking_ms");

		for (Interpolation method : new Interpolation[] { Interpolation.NEARESTNEIGHBOR, Interpolation.NLINEAR }) {
			final TiffRawVolumeStore store = new TiffRawVolumeStore(1L << 30, TiffRawVolumeStore.cellBytes(layout), 4, null);
			RawFrameRenderer renderer = new RawFrameRenderer(Math.max(1, cores - 1));
			JPanel canvas = new JPanel();
			canvas.setSize(SCREEN_W, SCREEN_H);
			final RawPresenter presenter = new RawPresenter(canvas);
			RawStrictPlayer player = new RawStrictPlayer(store, renderer, presenter);
			// the canvas paints as Swing would, on the event thread, every few milliseconds
			final AtomicBoolean painting = new AtomicBoolean(true);
			final BufferedImage screen = new BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_ARGB);
			Thread painter = new Thread(() -> {
				while (painting.get()) {
					try {
						SwingUtilities.invokeAndWait(() -> {
							Graphics2D g = screen.createGraphics();
							presenter.drawOverlays(g);
							g.dispose();
						});
						Thread.sleep(4);
					} catch (Exception e) {
						return;
					}
				}
			}, "test-painter");
			painter.setDaemon(true);
			painter.start();
			long revision = 0;
			List<double[]> latencies = new ArrayList<double[]>();
			try {
				for (int pass = 0; pass < 2; pass++)		// the first reads the files, the second finds the cells resident
					for (int t = 0; t < TIMES; t++) {
						RawAcquisition.Entry e = acquisition.entry(times.get(t), 1);
						RawFrameRequest r = request(++revision, t, e, geometry, specs, view, method, range);
						CompletableFuture<RawStrictPlayer.Result> f = player.request(r);
						RawStrictPlayer.Result result = f.get(120, TimeUnit.SECONDS);
						// afterwards, so that the strict frame prepares its own cells and BigDataViewer only draws
						long bdv = Boolean.parseBoolean(System.getProperty("opm.timing.bdv", "true"))
								? bigDataViewersBlockingRender(acquisition, times, geometry, store, r) : 0;
						assertEquals(result.toString(), RawStrictPlayer.Outcome.PRESENTED, result.outcome);
						assertEquals("every value the frame read was in its lease", 0, result.coverageMisses);
						System.out.printf(Locale.US, "%s\t%s\t%d\t%d\t%s\t%.1f\t%.1f\t%.1f\t%.1f\t%.1f%n", method,
								pass == 0 ? "files" : "resident", t + 1, result.leaseCells, RawVolumeStore.CacheStats.size(result.leaseBytes),
								result.prepareNanos / 1e6, result.renderNanos / 1e6, result.presentNanos / 1e6, result.latencyNanos() / 1e6,
								bdv / 1e6);
						if (pass == 1) latencies.add(new double[] { result.renderNanos / 1e6, result.latencyNanos() / 1e6 });
					}
				RawVolumeStore.CacheStats s = store.stats();
				System.out.println(method + ": " + player.summary() + "; " + s);
				assertEquals(0, s.pinnedBytes);
				assertTrue("within the budget: " + s, s.peakTotalBytes <= s.budgetBytes);
				double[] render = new double[latencies.size()], latency = new double[latencies.size()];
				for (int i = 0; i < render.length; i++) {
					render[i] = latencies.get(i)[0];
					latency[i] = latencies.get(i)[1];
				}
				Arrays.sort(render);
				Arrays.sort(latency);
				System.out.printf(Locale.US, "%s resident: render median %.1f ms, latency median %.1f ms (%.1f fps if frames followed at once)%n",
						method, render[render.length / 2], latency[latency.length / 2], 1000 / latency[latency.length / 2]);
			} finally {
				painting.set(false);
				painter.join(5000);
				player.close();
				renderer.close();
				store.closeAsync().get(30, TimeUnit.SECONDS);
			}
		}
	}

	/** Down the deskewed Z at mid depth, both halves filling the screen: the reference protocol's XY view. */
	private static AffineTransform3D xyView(RawGeometry geometry, long[] dims, RawSourceSpec[] specs) {
		double[] min = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
		double[] max = { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
		double[] corner = new double[3], world = new double[3];
		for (RawSourceSpec spec : specs) {
			AffineTransform3D toWorld = geometry.sourceToWorld(dims, spec, null);
			for (int c = 0; c < 8; c++) {
				corner[0] = (c & 1) == 0 ? 0 : spec.cropWidth(dims[0]) - 1;
				corner[1] = (c & 2) == 0 ? 0 : dims[1] - 1;
				corner[2] = (c & 4) == 0 ? 0 : dims[2] - 1;
				toWorld.apply(corner, world);
				for (int d = 0; d < 3; d++) {
					min[d] = Math.min(min[d], world[d]);
					max[d] = Math.max(max[d], world[d]);
				}
			}
		}
		AffineTransform3D view = new AffineTransform3D();
		view.translate(-0.5 * (min[0] + max[0]), -0.5 * (min[1] + max[1]), -0.5 * (min[2] + max[2]));
		view.scale(Math.min(SCREEN_W / (max[0] - min[0]), SCREEN_H / (max[1] - min[1])));
		view.translate(0.5 * SCREEN_W, 0.5 * SCREEN_H, 0);
		return view;
	}

	private static RawFrameRequest request(long revision, int t, RawAcquisition.Entry e, RawGeometry geometry, RawSourceSpec[] specs,
			AffineTransform3D view, Interpolation method, double[] range) {
		List<RawFrameRequest.Layer> layers = new ArrayList<RawFrameRequest.Layer>();
		for (RawSourceSpec spec : specs)
			layers.add(new RawFrameRequest.Layer(spec, geometry.sourceToWorld(e.layout().dimensions(), spec, null), range[0], range[1],
					0xff000000 | spec.rgb));
		return new RawFrameRequest(revision, t, e, view, SCREEN_W, SCREEN_H, method, layers);
	}

	/** BigDataViewer's blocking renderer, two threads, drawing the same request from the same store: nanoseconds. */
	private static long bigDataViewersBlockingRender(RawAcquisition acquisition, List<RawAcquisition.TimeKey> times, RawGeometry geometry,
			TiffRawVolumeStore store, RawFrameRequest r) {
		BasicViewerState state = new BasicViewerState();
		for (RawFrameRequest.Layer l : r.layers) {
			RawPartSource<UnsignedShortType> source = new RawPartSource<UnsignedShortType>(acquisition, times, l.spec, geometry,
					store::blocking, new UnsignedShortType(), r.layout.dimensions());
			RealARGBColorConverter<UnsignedShortType> c = RealARGBColorConverter.create(new UnsignedShortType(), l.min, l.max);
			c.setColor(new ARGBType(l.argb));
			SourceAndConverter<UnsignedShortType> soc = new SourceAndConverter<UnsignedShortType>(source, c);
			state.addSource(soc);
			state.setSourceActive(soc, true);
		}
		state.setDisplayMode(DisplayMode.FUSED);
		state.setNumTimepoints(times.size());
		state.setCurrentTimepoint(r.timeIndex);
		state.setInterpolation(r.interpolation);
		state.setViewerTransform(r.viewer);
		long start = System.nanoTime();
		BdvTestRenderer.paint(state, r.width, r.height);
		return System.nanoTime() - start;
	}
}
