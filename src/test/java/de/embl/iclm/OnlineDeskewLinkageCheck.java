package de.embl.iclm;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import bdv.util.RandomAccessibleIntervalSource;
import bdv.viewer.Interpolation;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.integer.UnsignedShortType;

/**
 * Runs the Online Deskew View's code against whatever BigDataViewer and ImgLib2 are on the class
 * path: in practice an installed Fiji's own jars (joint contract C8.3).
 *
 * <p>The Maven build compiles and tests against pom-scijava's managed releases (bigdataviewer-core
 * 10.6.4, ImgLib2 7.1.5); Fiji runs newer ones on Java 21 (10.6.11, ImgLib2 8). Code can compile
 * against one and fail to link against the other - {@code MultiResolutionRenderer}'s constructor
 * changed between those two releases. This program catches that before a user does:
 * <ol>
 * <li>every declared member of every viewer class is resolved, which loads every type in their
 *     signatures;</li>
 * <li>the geometry, the sampling and a BDV source are exercised on a synthetic volume, which
 *     links the calls inside them.</li>
 * </ol>
 * It is not a JUnit test, because Maven's test run uses the managed libraries; it is started by
 * {@code scripts/check-fiji-linkage.ps1}. Exit code 0 means every check passed.
 */
public final class OnlineDeskewLinkageCheck {

	/** Prefixes of the viewer's classes. */
	private static final String[] PREFIXES = { "Raw", "OnlineDeskew", "TiffRawVolumeStore" };

	private OnlineDeskewLinkageCheck() {}

	public static void main(String[] args) throws Exception {
		System.out.println("java " + System.getProperty("java.version"));
		for (Class<?> c : new Class<?>[] { bdv.viewer.Source.class, net.imglib2.Interval.class,
				net.imglib2.cache.img.CachedCellImg.class, AffineTransform3D.class, RawGeometry.class })
			System.out.println("  " + c.getName() + " from " + origin(c));

		int resolved = 0;
		for (String name : viewerClasses()) {
			Class<?> c = Class.forName(name);
			resolved += c.getDeclaredMethods().length + c.getDeclaredFields().length + c.getDeclaredConstructors().length;
			for (Class<?> inner : c.getDeclaredClasses())
				resolved += inner.getDeclaredMethods().length + inner.getDeclaredFields().length;
			System.out.println("  resolved " + name);
		}
		System.out.println("members resolved: " + resolved);

		exercise();
		exerciseStore();
		System.out.println("PASS: the Online Deskew View links and runs against this class path");
	}

	/**
	 * The raw store on a small camera-style BigTIFF: the catalogue, the shared bounded cache
	 * (imglib2-cache), the blocking image, the volatile view through BigDataViewer's queue, a
	 * time-aware source, and the close. These are the calls most likely to differ between
	 * library releases.
	 */
	private static void exerciseStore() throws Exception {
		File dir = java.nio.file.Files.createTempDirectory("opm-linkage").toFile();
		File raw = new File(dir, "l_Position0001_Time000001_Channel0001_Frames_1_6.tiff");
		RawTiffFixture.write(raw, 26, 17, 6, 8, 6, RawTiffFixture.RAMP);
		RawAcquisition acquisition = RawAcquisition.scan(dir);
		java.util.List<RawAcquisition.TimeKey> times = acquisition.times(1, 1);
		RawAcquisition.Entry entry = acquisition.entry(times.get(0), 1);
		require(entry.validate() == RawAcquisition.Readiness.COMPLETE, "the fixture validates: " + entry.detail());
		bdv.cache.SharedQueue queue = new bdv.cache.SharedQueue(2, 1);
		TiffRawVolumeStore store = new TiffRawVolumeStore(64L << 20, TiffRawVolumeStore.cellBytes(entry.layout()), 2, queue);
		try {
			RandomAccess<UnsignedShortType> a = store.blocking(entry.key(), entry.layout()).randomAccess();
			a.setPosition(new long[] { 7, 9, 4 });
			require(a.get().get() == RawTiffFixture.RAMP.at(7, 9, 4), "the store reads the raw value");
			RandomAccessibleInterval<net.imglib2.type.volatiles.VolatileUnsignedShortType> view =
					store.volatileView(entry.key(), entry.layout());
			boolean valid = false;
			for (int i = 0; i < 500 && !valid; i++) {
				RandomAccess<net.imglib2.type.volatiles.VolatileUnsignedShortType> v = view.randomAccess();
				v.setPosition(new long[] { 3, 16, 5 });
				valid = v.get().isValid() && v.get().get().get() == RawTiffFixture.RAMP.at(3, 16, 5);
				if (!valid) Thread.sleep(10);
			}
			require(valid, "the volatile view fills in through the queue");
			RawSourceSpec spec = RawSourceSpec.half(null, 1, false, false, 26, 0xff00ff);
			RawPartSource<UnsignedShortType> source = new RawPartSource<UnsignedShortType>(acquisition, times, spec,
					new RawGeometry(0.265, 0.116, 33.5, "linkage check"), store::blocking, new UnsignedShortType(),
					entry.layout().dimensions());
			require(source.isPresent(0), "the source sees the validated time point");
			source.getInterpolatedSource(0, 0, Interpolation.NLINEAR).realRandomAccess().get();
			System.out.println("  store: " + store.stats());
		} finally {
			queue.shutdown();
			RawVolumeStore.CloseResult result = store.closeAsync().get(10, java.util.concurrent.TimeUnit.SECONDS);
			require(result.drained, "the store closes: " + result);
			raw.delete();
			dir.delete();
		}
	}

	/** Geometry, sampling and a BDV source on a 26 x 17 x 23 synthetic volume. */
	private static void exercise() {
		int width = 26, height = 17, depth = 23;
		short[] data = new short[width * height * depth];
		Arrays.fill(data, (short) 65535);
		RandomAccessibleInterval<UnsignedShortType> raw = ArrayImgs.unsignedShorts(data, width, height, depth);
		RawGeometry geometry = new RawGeometry(0.265, 0.116, 33.5, "linkage check");
		double[][] matrix = { { 0.99999, -0.0044, 11.66 }, { 0.0044, 0.99999, -7.49 } };
		RawSourceSpec spec = RawSourceSpec.half(AlignmentMatrixSet.legacy(matrix), 1, false, false, width, 0xff00ff);
		RandomAccessibleInterval<UnsignedShortType> part = RawSampling.crop(raw, spec);
		AffineTransform3D toWorld = geometry.sourceToWorld(new long[] { width, height, depth }, spec, null);

		RandomAccessibleIntervalSource<UnsignedShortType> source = new RandomAccessibleIntervalSource<UnsignedShortType>(
				part, new UnsignedShortType(), toWorld, spec.key);
		AffineTransform3D check = new AffineTransform3D();
		source.getSourceTransform(0, 0, check);
		require(Arrays.equals(check.getRowPackedCopy(), toWorld.getRowPackedCopy()), "BDV source keeps the transform");

		for (Interpolation method : new Interpolation[] { Interpolation.NEARESTNEIGHBOR, Interpolation.NLINEAR }) {
			RealRandomAccess<UnsignedShortType> sample = RawSampling.interpolate(part, method).realRandomAccess();
			sample.setPosition(new double[] { 5.5, 7.5, 9.5 });
			require(sample.get().get() == 65535, method + " keeps a saturated neighbourhood saturated");
			RandomAccess<UnsignedShortType> view = RealViews.affine(RawSampling.interpolate(part, method), toWorld).randomAccess();
			view.setPosition(new long[] { 0, 0, 0 });
			view.get().get();
		}
	}

	private static void require(boolean condition, String what) {
		if (!condition) throw new IllegalStateException("FAILED: " + what);
	}

	/** The viewer's classes, found next to {@link RawGeometry} in a class folder or a jar. */
	private static List<String> viewerClasses() throws Exception {
		List<String> names = new ArrayList<String>();
		URL location = RawGeometry.class.getProtectionDomain().getCodeSource().getLocation();
		File where = new File(location.toURI());
		if (where.isDirectory()) {
			File[] files = new File(where, "de/embl/iclm").listFiles();
			if (files != null) for (File f : files) add(names, f.getName());
		} else {
			try (JarFile jar = new JarFile(where)) {
				for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements();) {
					String entry = e.nextElement().getName();
					if (entry.startsWith("de/embl/iclm/") && entry.indexOf('/', 13) < 0) add(names, entry.substring(13));
				}
			}
		}
		if (names.isEmpty()) throw new IllegalStateException("no viewer classes found at " + where);
		return names;
	}

	private static void add(List<String> names, String file) {
		if (!file.endsWith(".class") || file.contains("$")) return;
		for (String prefix : PREFIXES)
			if (file.startsWith(prefix)) names.add("de.embl.iclm." + file.substring(0, file.length() - 6));
	}

	private static String origin(Class<?> c) {
		try {
			return String.valueOf(c.getProtectionDomain().getCodeSource().getLocation());
		} catch (RuntimeException e) {
			return "unknown";
		}
	}
}
