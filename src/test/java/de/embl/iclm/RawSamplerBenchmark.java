package de.embl.iclm;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import bdv.cache.SharedQueue;
import bdv.viewer.Interpolation;
import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.img.planar.PlanarImgFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.volatiles.VolatileUnsignedShortType;
import net.imglib2.view.Views;

/**
 * Joint contract C9.3: how much the image family and the sampler cost when a deskewed view is
 * drawn from raw data, measured rather than assumed.
 *
 * <p>One camera half (800 x 500 x 100 raw voxels, as the camera writes them: 8-row strips) is
 * sampled through the view's transform for an 800 x 500 screen, looking down the deskewed Z
 * ({@code xy}) or from the side ({@code xz}), with nearest neighbour, the saturation-safe
 * trilinear ({@code ClampingNLinear}, what the viewer uses) or the plain trilinear ({@code nlinear},
 * unsafe, for comparison). Image families:
 * <ul>
 * <li>{@code blocking}: the raw store's blocking image, all cells resident;</li>
 * <li>{@code volatile}: the raw store's volatile image - what BigDataViewer samples - all cells lent;</li>
 * <li>{@code cell}: ImgLib2's own CellImg with the same strip cells, copied in memory;</li>
 * <li>{@code planar}: a PlanarImg copy (one array per plane);</li>
 * <li>{@code array}: an ArrayImg copy (one array).</li>
 * </ul>
 * Families listed together are measured round-robin in one JVM, so the sampling code's call
 * sites see all of them - the "mixed families" case; a single family per JVM is the monomorphic
 * case. The driver, {@code scripts/sampler-benchmark.ps1}, starts one JVM per configuration on
 * each Java stack.
 *
 * <p>Arguments: {@code families=blocking,cell sampler=clamping|nearest|nlinear view=xy|xz
 * rounds=15 warmup=5 data=<folder for the fixture>}. Prints one line per family:
 * nanoseconds per screen sample, median and p95 over the measured rounds.
 */
public final class RawSamplerBenchmark {

	private static final int W = 1600, H = 500, D = 100, SCREEN_W = 800, SCREEN_H = 500;
	private static final RawGeometry GEOMETRY = new RawGeometry(0.265, 0.116, 33.5, "benchmark");

	private RawSamplerBenchmark() {}

	public static void main(String[] args) throws Exception {
		Map<String, String> a = new LinkedHashMap<String, String>();
		for (String s : args) a.put(s.substring(0, s.indexOf('=')), s.substring(s.indexOf('=') + 1));
		List<String> families = Arrays.asList(a.getOrDefault("families", "blocking").split(","));
		String sampler = a.getOrDefault("sampler", "clamping"), view = a.getOrDefault("view", "xy");
		int rounds = Integer.parseInt(a.getOrDefault("rounds", "15")), warmup = Integer.parseInt(a.getOrDefault("warmup", "5"));
		File folder = new File(a.getOrDefault("data", System.getProperty("java.io.tmpdir") + "/opm-sampler-benchmark"));

		File raw = new File(folder, "bench_Position0001_Time000001_Channel0001_Frames_1_" + D + ".tiff");
		if (!raw.isFile()) {
			folder.mkdirs();
			RawTiffFixture.write(raw, W, H, D, 8, D, (x, y, k) -> 1000 + ((x * 7 + y * 13 + k * 29) & 2047));
		}
		RawAcquisition acquisition = RawAcquisition.scan(folder);
		RawAcquisition.Entry e = acquisition.entry(acquisition.times(1, 1).get(0), 1);
		if (e.validate() != RawAcquisition.Readiness.COMPLETE) throw new IllegalStateException(e.detail());
		RawSourceSpec spec = RawSourceSpec.half(null, 1, true, false, W, 0);
		SharedQueue queue = new SharedQueue(2, 1);
		TiffRawVolumeStore store = new TiffRawVolumeStore(1L << 30, TiffRawVolumeStore.cellBytes(e.layout()), 4, queue);
		try {
			RandomAccessibleInterval<UnsignedShortType> blocking = RawSampling.crop(store.blocking(e.key(), e.layout()), spec);
			long sum = 0;
			for (Cursor<UnsignedShortType> c = Views.flatIterable(blocking).cursor(); c.hasNext();) sum += c.next().get();		// every cell resident
			Map<String, Object> images = new LinkedHashMap<String, Object>();
			for (String family : families) images.put(family, image(family, blocking, store, e, spec));
			AffineTransform3D toScreen = toScreen(spec, e.layout().dimensions(), view);
			Interpolation method = sampler.equals("nearest") ? Interpolation.NEARESTNEIGHBOR : Interpolation.NLINEAR;

			Map<String, List<Double>> nanos = new LinkedHashMap<String, List<Double>>();
			for (String family : families) nanos.put(family, new ArrayList<Double>());
			for (int round = 0; round < warmup + rounds; round++)
				for (String family : families) {
					queue.prepareNextFrame();		// a new frame for the volatile family, as the viewer does
					long start = System.nanoTime();
					sum += draw(images.get(family), toScreen, method, sampler.equals("nlinear"));
					double perSample = (System.nanoTime() - start) / (double) (SCREEN_W * SCREEN_H);
					if (round >= warmup) nanos.get(family).add(perSample);
				}
			String mix = families.size() == 1 ? "alone" : "mixed with " + (families.size() - 1);
			for (String family : families) {
				List<Double> v = nanos.get(family);
				java.util.Collections.sort(v);
				System.out.printf(Locale.US, "RESULT java=%s family=%s sampler=%s view=%s %s median=%.1f p95=%.1f min=%.1f ns/sample%n",
						System.getProperty("java.version"), family, sampler, view, mix, v.get(v.size() / 2),
						v.get((int) Math.ceil(0.95 * v.size()) - 1), v.get(0));
			}
			System.out.println("checksum " + sum);
		} finally {
			queue.shutdown();
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	/** The family's image of the same left half. */
	private static Object image(String family, RandomAccessibleInterval<UnsignedShortType> blocking, TiffRawVolumeStore store,
			RawAcquisition.Entry e, RawSourceSpec spec) {
		long[] dims = { blocking.dimension(0), blocking.dimension(1), blocking.dimension(2) };
		switch (family) {
		case "blocking":
			return blocking;
		case "volatile":
			return RawSampling.crop(store.volatileView(e.key(), e.layout()), spec);
		case "cell":
			return copy(blocking, new CellImgFactory<UnsignedShortType>(new UnsignedShortType(), (int) dims[0], 8, 1).create(dims));
		case "planar":
			return copy(blocking, new PlanarImgFactory<UnsignedShortType>(new UnsignedShortType()).create(dims));
		case "array":
			return copy(blocking, new ArrayImgFactory<UnsignedShortType>(new UnsignedShortType()).create(dims));
		default:
			throw new IllegalArgumentException("Unknown family " + family);
		}
	}

	private static Img<UnsignedShortType> copy(RandomAccessibleInterval<UnsignedShortType> from, Img<UnsignedShortType> to) {
		Cursor<UnsignedShortType> c = Views.flatIterable(to).cursor();
		Cursor<UnsignedShortType> s = Views.flatIterable(from).cursor();
		while (c.hasNext()) c.next().set(s.next());
		return to;
	}

	/** Screen pixels of the view, as BigDataViewer's projector visits them: row by row with fwd. */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static long draw(Object image, AffineTransform3D toScreen, Interpolation method, boolean unsafeTrilinear) {
		RandomAccessibleInterval img = (RandomAccessibleInterval) image;
		RealRandomAccessible interpolated = unsafeTrilinear
				? Views.interpolate(Views.extendZero(img), new NLinearInterpolatorFactory())
				: RawSampling.interpolate(img, method);
		RandomAccessible screen = RealViews.affine(interpolated, toScreen);
		RandomAccess ra = screen.randomAccess();
		long sum = 0;
		boolean isVolatile = Views.iterable(img).firstElement() instanceof VolatileUnsignedShortType;
		for (int y = 0; y < SCREEN_H; y++) {
			ra.setPosition(new long[] { 0, y, 0 });
			for (int x = 0; x < SCREEN_W; x++) {
				Object v = ra.get();
				if (isVolatile) {
					VolatileUnsignedShortType vv = (VolatileUnsignedShortType) v;
					if (vv.isValid()) sum += vv.get().get();
				} else sum += (long) ((RealType) v).getRealDouble();
				ra.fwd(0);
			}
		}
		return sum;
	}

	/** World to screen: the half filling an 800 x 500 screen, through the middle of the volume. */
	private static AffineTransform3D toScreen(RawSourceSpec spec, long[] dims, String view) {
		AffineTransform3D toWorld = GEOMETRY.sourceToWorld(dims, spec, null);
		double[] min = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
		double[] max = { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
		double[] corner = new double[3], world = new double[3];
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
		AffineTransform3D view3 = new AffineTransform3D();
		view3.translate(-0.5 * (min[0] + max[0]), -0.5 * (min[1] + max[1]), -0.5 * (min[2] + max[2]));
		if (view.equals("xz")) view3.rotate(0, -Math.PI / 2);		// look along Y: the deskewed Z goes up the screen
		double extentX = max[0] - min[0], extentY = view.equals("xz") ? max[2] - min[2] : max[1] - min[1];
		view3.scale(Math.min(SCREEN_W / extentX, SCREEN_H / extentY));
		view3.translate(SCREEN_W / 2.0, SCREEN_H / 2.0, 0);
		AffineTransform3D sourceToScreen = toWorld.copy();
		sourceToScreen.preConcatenate(view3);
		return sourceToScreen;
	}
}
