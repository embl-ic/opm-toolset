package de.embl.iclm;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import bdv.viewer.Interpolation;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * How long {@link RawFrameRenderer} takes to draw one staged frame of a real acquisition, by
 * thread count, with every cell already resident - the drawing alone. A main program, not a test:
 * <pre>
 * java -cp ... de.embl.iclm.RawFrameRenderBenchmark E:\OPM\3_timelapse_0 [seconds] [nearest|linear|both] [threads,...]
 * </pre>
 */
public final class RawFrameRenderBenchmark {

	private RawFrameRenderBenchmark() {}

	public static void main(String[] args) throws Exception {
		File folder = new File(args[0]);
		double seconds = args.length > 1 ? Double.parseDouble(args[1]) : 4;
		String which = args.length > 2 ? args[2] : "both";
		int cores = Runtime.getRuntime().availableProcessors();
		int[] threadCounts = { 1, 2, 4, Math.max(1, cores - 1) };
		if (args.length > 3) {
			String[] t = args[3].split(",");
			threadCounts = new int[t.length];
			for (int i = 0; i < t.length; i++) threadCounts[i] = Integer.parseInt(t[i].trim());
		}
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
		AffineTransform3D view = xyView(geometry, dims, specs, w, h);
		TiffRawVolumeStore store = new TiffRawVolumeStore(1L << 30, TiffRawVolumeStore.cellBytes(layout), 4, null);
		System.out.printf(Locale.US, "java %s, %d cores; %s %d x %d x %d; frame %d x %d, two halves%n", System.getProperty("java.version"),
				cores, folder.getName(), dims[0], dims[1], dims[2], w, h);
		System.out.println("interpolation\tthreads\tframes\tmedian_ms\tmin_ms\tns_per_sample_thread");
		try {
			for (Interpolation method : new Interpolation[] { Interpolation.NEARESTNEIGHBOR, Interpolation.NLINEAR }) {
				if (which.equals("nearest") && method != Interpolation.NEARESTNEIGHBOR) continue;
				if (which.equals("linear") && method != Interpolation.NLINEAR) continue;
				List<RawFrameRequest.Layer> layers = new ArrayList<RawFrameRequest.Layer>();
				for (RawSourceSpec spec : specs)
					layers.add(new RawFrameRequest.Layer(spec, geometry.sourceToWorld(dims, spec, null), 80, 150, 0xff000000 | spec.rgb));
				RawFrameRequest r = new RawFrameRequest(1, 0, e, view, w, h, method, layers);
				for (int threads : threadCounts) {
					RawFrameRenderer renderer = new RawFrameRenderer(threads);
					try {
						renderer.render(r, store::blocking, () -> false);		// reads the cells, warms up
						List<Double> ms = new ArrayList<Double>();
						long end = System.nanoTime() + (long) (seconds * 1e9);
						while (System.nanoTime() < end || ms.size() < 3) {
							long t0 = System.nanoTime();
							renderer.render(r, store::blocking, () -> false);
							ms.add((System.nanoTime() - t0) / 1e6);
						}
						double[] sorted = new double[ms.size()];
						for (int i = 0; i < sorted.length; i++) sorted[i] = ms.get(i);
						Arrays.sort(sorted);
						double median = sorted[sorted.length / 2];
						System.out.printf(Locale.US, "%s\t%d\t%d\t%.1f\t%.1f\t%.1f%n", method, threads, sorted.length, median, sorted[0],
								median * 1e6 * threads / (2.0 * w * h));
					} finally {
						renderer.close();
					}
				}
			}
		} finally {
			store.closeAsync().get(30, TimeUnit.SECONDS);
		}
	}

	static AffineTransform3D xyView(RawGeometry geometry, long[] dims, RawSourceSpec[] specs, int w, int h) {
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
		view.scale(Math.min(w / (max[0] - min[0]), h / (max[1] - min[1])));
		view.translate(0.5 * w, 0.5 * h, 0);
		return view;
	}
}
