package de.embl.iclm;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import bdv.viewer.Interpolation;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * Why trilinear staged frames do not scale with threads: two threads each draw whole frames at the
 * same time, from one shared store or from a store each, so that what they share is the only
 * difference. A main program:
 * <pre>
 * java -cp ... de.embl.iclm.RawFrameScalingProbe E:\OPM\3_timelapse_0 [threads]
 * </pre>
 */
public final class RawFrameScalingProbe {

	private RawFrameScalingProbe() {}

	public static void main(String[] args) throws Exception {
		File folder = new File(args[0]);
		int threads = args.length > 1 ? Integer.parseInt(args[1]) : 2;
		int w = 1600, h = 1000;
		RawGeometry geometry = RawGeometry.fromExperimentalParameters(new File(folder, "ExperimentalParameters.txt"));
		RawAcquisition acquisition = RawAcquisition.scan(folder);
		List<RawAcquisition.TimeKey> times = acquisition.times(1, 1);
		RawAcquisition.Entry e = acquisition.entry(times.get(0), 1);
		e.validate();
		RawTiffLayout layout = e.layout();
		long[] dims = layout.dimensions();
		RawSourceSpec[] specs = { RawSourceSpec.half(null, 1, true, false, layout.width, 0x00ff00),
				RawSourceSpec.half(null, 1, false, false, layout.width, 0xff00ff) };
		AffineTransform3D view = RawFrameRenderBenchmark.xyView(geometry, dims, specs, w, h);
		List<RawFrameRequest.Layer> layers = new ArrayList<RawFrameRequest.Layer>();
		for (RawSourceSpec spec : specs)
			layers.add(new RawFrameRequest.Layer(spec, geometry.sourceToWorld(dims, spec, null), 80, 150, 0xff000000 | spec.rgb));
		final RawFrameRequest r = new RawFrameRequest(1, 0, e, view, w, h, Interpolation.NLINEAR, layers);
		System.out.println("trilinear, " + threads + " threads each drawing whole frames on a renderer of one thread");
		for (String mode : new String[] { "alone", "one shared store", "a store each", "one shared store", "a store each" }) {
			int n = mode.equals("alone") ? 1 : threads;
			final TiffRawVolumeStore shared = new TiffRawVolumeStore(4L << 30, TiffRawVolumeStore.cellBytes(layout), 4, null);
			final List<TiffRawVolumeStore> stores = new ArrayList<TiffRawVolumeStore>();
			for (int i = 0; i < n; i++)
				stores.add(mode.equals("a store each") ? new TiffRawVolumeStore(2L << 30, TiffRawVolumeStore.cellBytes(layout), 4, null)
						: shared);
			final double[] ms = new double[n];
			final CountDownLatch ready = new CountDownLatch(n), go = new CountDownLatch(1);
			List<Thread> workers = new ArrayList<Thread>();
			for (int i = 0; i < n; i++) {
				final int k = i;
				Thread t = new Thread(() -> {
					try (RawFrameRenderer renderer = new RawFrameRenderer(1)) {
						TiffRawVolumeStore store = stores.get(k);
						for (int warm = 0; warm < 3; warm++) renderer.render(r, store::blocking, () -> false);
						ready.countDown();
						go.await();
						int frames = 6;
						long t0 = System.nanoTime();
						for (int f = 0; f < frames; f++) renderer.render(r, store::blocking, () -> false);
						ms[k] = (System.nanoTime() - t0) / 1e6 / frames;
					} catch (Exception x) {
						throw new IllegalStateException(x);
					}
				});
				t.start();
				workers.add(t);
			}
			ready.await();
			go.countDown();
			for (Thread t : workers) t.join();
			StringBuilder line = new StringBuilder(String.format(Locale.US, "%-18s", mode));
			for (double m : ms) line.append(String.format(Locale.US, "\t%.1f ms", m));
			System.out.println(line);
			for (TiffRawVolumeStore s : stores) if (s != shared) s.closeAsync().get(30, TimeUnit.SECONDS);
			shared.closeAsync().get(30, TimeUnit.SECONDS);
		}
	}
}
