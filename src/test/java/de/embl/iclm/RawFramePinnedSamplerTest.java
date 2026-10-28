package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.viewer.Interpolation;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * The completed-frame renderer's pinned path (M2-Q-F2) against its ImgLib2 path: a lease's frame sampled from the pinned
 * cells' arrays is the frame ImgLib2 samples from the lease's own image, and from the store's, pixel for pixel - over
 * random views of both halves, world planes and obliques, near and far, partly and wholly outside the volume, both
 * interpolations, display ranges negative, narrow, empty and inverted, and data that saturates. And it reads and refuses
 * what that path reads and refuses: nothing outside the plan, nothing through a closed lease, nothing of a changed file.
 */
public class RawFramePinnedSamplerTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final int SW = 96, SH = 120;
	private static final double[][] RANGES = { { 0, 2500 }, { -1000, 2500 }, { 600, 3500 }, { 1000, 1001 }, { 1500, 1500 }, { 2500, 0 },
			{ 0, 65535 }, { 60000, 65535 } };

	private RawFrameFixture f;
	private RawFrameRenderer renderer;

	@Before
	public void fixture() throws Exception {
		f = new RawFrameFixture(folder.newFolder());
		renderer = new RawFrameRenderer(3);
	}

	@After
	public void close() throws Exception {
		renderer.close();
		f.close();
	}

	/** A world view of the fixture, turned, tilted out of the plane, zoomed and shifted by a seeded draw. */
	private static AffineTransform3D view(long[] dims, Random random) {
		AffineTransform3D v = RawFrameFixture.view(dims, -0.1 + 1.2 * random.nextDouble(), 2 * Math.PI * random.nextDouble(), SW, SH);
		v.translate(-0.5 * SW, -0.5 * SH, 0);
		v.rotate(0, random.nextBoolean() ? 0 : -1.3 + 2.6 * random.nextDouble());		// half of them oblique
		v.rotate(1, random.nextBoolean() ? 0 : -1.3 + 2.6 * random.nextDouble());
		v.scale(Math.exp(Math.log(0.3) + random.nextDouble() * Math.log(3 / 0.3)));
		v.translate(0.5 * SW + 6 * (random.nextDouble() - 0.5), 0.5 * SH + 6 * (random.nextDouble() - 0.5), 4 * (random.nextDouble() - 0.5));
		return v;
	}

	private static BitSet coverage(RawFrameRequest r) {
		return RawCoverage.cells(r.layout.dimensions(), r.layout.rowsPerStrip, r.coverageParts(), r.width, r.height, r.interpolation);
	}

	private static TiffRawVolumeStore.Ready lease(TiffRawVolumeStore store, RawFrameRequest r, BitSet cells) throws Exception {
		return store.prepare(r.revision, Collections.singletonList(new TiffRawVolumeStore.Want(r.key, r.layout, cells)), () -> false)
				.get(10, TimeUnit.SECONDS);
	}

	private static int[] rgb(BufferedImage image) {
		return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
	}

	private static void assertSamePixels(String what, int[] expected, int[] actual) {
		assertEquals(what, expected.length, actual.length);
		for (int i = 0; i < expected.length; i++)
			if (expected[i] != actual[i])
				fail(String.format(Locale.US, "%s: pixel (%d, %d) is %08x through ImgLib2 and %08x from the pinned cells", what, i % SW, i / SW,
						expected[i], actual[i]));
	}

	/** Every seeded request, drawn three ways: the pinned path, ImgLib2 over the same lease, ImgLib2 over the store. */
	private int drawnAlike(RawFrameFixture fixture, int requests, long seed) throws Exception {
		Random random = new Random(seed);
		long[] dims = fixture.dims();
		int compared = 0;
		for (int k = 0; k < requests; k++) {
			Interpolation method = k % 2 == 0 ? Interpolation.NLINEAR : Interpolation.NEARESTNEIGHBOR;
			double[] range = RANGES[random.nextInt(RANGES.length)];
			RawFrameRequest r = fixture.request(k + 1, random.nextInt(3), view(dims, random), method, range[0], range[1], SW, SH);
			String what = String.format(Locale.US, "request %d: %s, range %s-%s, view %s", k, method, range[0], range[1], r.viewer);
			TiffRawVolumeStore.Ready lease = lease(fixture.reference, r, coverage(r));
			try {
				int[] pinned = rgb(renderer.render(r, lease, () -> false));
				assertSamePixels(what + ", against the lease's image", rgb(renderer.render(r, lease::blocking, () -> false)), pinned);
				assertSamePixels(what + ", against the store's image", rgb(renderer.render(r, fixture.reference::blocking, () -> false)), pinned);
				assertEquals(what + ": no value outside the plan", 0, lease.misses());
			} finally {
				lease.close();
			}
			compared++;
		}
		return compared;
	}

	@Test
	public void aLeasesFrameFromItsPinnedCellsIsTheImgLib2FramePixelForPixel() throws Exception {
		assertEquals(240, drawnAlike(f, 240, 20261028));
	}

	/**
	 * Data at and near the top of the type, and alternating with zero: trilinear sums that pass 65535 by a rounding error are
	 * clamped, and every corner weight counts, as in ImgLib2's clamping interpolator.
	 */
	@Test
	public void saturatedAndAlternatingDataDrawAlike() throws Exception {
		File saturated = folder.newFolder("saturated");
		for (int t = 0; t < 3; t++) {
			final int time = t;
			RawTiffFixture.write(new File(saturated, String.format("z_Time%06d_Channel0001_Frames_1_%d.tiff", t + 1, RawFrameFixture.D)),
					RawFrameFixture.W, RawFrameFixture.H, RawFrameFixture.D, RawFrameFixture.ROWS, RawFrameFixture.D,
					(x, y, z) -> time == 1 ? ((x + y + z) % 2 == 0 ? 65535 : 0) : 65535 - (x * 7 + y * 13 + z * 29) % (time == 0 ? 3 : 1));
		}
		try (RawFrameFixture top = RawFrameFixture.over(saturated)) {
			assertEquals(160, drawnAlike(top, 160, 65535));
		}
	}

	/** A sample the plan left out is refused by both paths, and counted; nothing is read from disk for it. */
	@Test
	public void aCellOutsideThePlanIsRefusedAsTheLeasesImageRefusesIt() throws Exception {
		RawFrameRequest r = f.request(1, 0, RawFrameFixture.view(f.dims(), 0.45, 0.37, SW, SH), Interpolation.NLINEAR, 0, 2500, SW, SH);
		BitSet cells = coverage(r);
		int sampled = cellUnderTheScreenCentre(r);
		assertTrue("the plan holds it", cells.get(sampled));
		cells.clear(sampled);
		TiffRawVolumeStore.Ready lease = lease(f.reference, r, cells);
		try {
			long loads = f.reference.stats().loads;
			assertRefused(() -> renderer.render(r, lease, () -> false), TiffRawVolumeStore.CoverageMissException.class, "not in the frame's plan");
			long pinnedMisses = lease.misses();
			assertTrue("counted: " + pinnedMisses, pinnedMisses > 0);
			assertRefused(() -> renderer.render(r, lease::blocking, () -> false), TiffRawVolumeStore.CoverageMissException.class,
					"not in the frame's plan");
			assertTrue(lease.misses() > pinnedMisses);
			assertEquals("nothing read from disk for a refused cell", loads, f.reference.stats().loads);
		} finally {
			lease.close();
		}
	}

	/** The cell the first half's sample under the middle of the screen falls in: one every trilinear frame of it reads. */
	private static int cellUnderTheScreenCentre(RawFrameRequest r) {
		double[] p = new double[3];
		r.sourceToScreen(r.layers.get(0)).inverse().apply(new double[] { SW / 2, SH / 2, 0 }, p);
		long[] dims = r.layout.dimensions();
		long x = (long) Math.floor(p[0]), y = (long) Math.floor(p[1]), z = (long) Math.floor(p[2]);
		assertTrue("inside the half: " + java.util.Arrays.toString(p),
				x >= 0 && x < r.layers.get(0).spec.cropWidth(dims[0]) && y >= 0 && y < dims[1] && z >= 0 && z < dims[2]);
		int strips = (int) ((dims[1] + r.layout.rowsPerStrip - 1) / r.layout.rowsPerStrip);
		return (int) (y / r.layout.rowsPerStrip + (long) strips * z);
	}

	/** Nothing is drawn through a closed lease, by either path. */
	@Test
	public void aClosedLeaseIsRefusedAsTheLeasesImageRefusesIt() throws Exception {
		RawFrameRequest r = f.request(1, 1, RawFrameFixture.view(f.dims(), 0.45, 0.37, SW, SH), Interpolation.NEARESTNEIGHBOR, 0, 2500, SW, SH);
		TiffRawVolumeStore.Ready lease = lease(f.reference, r, coverage(r));
		lease.close();
		assertRefused(() -> renderer.render(r, lease, () -> false), TiffRawVolumeStore.CoverageMissException.class, "closed");
		assertRefused(() -> renderer.render(r, lease::blocking, () -> false), TiffRawVolumeStore.CoverageMissException.class, "closed");
	}

	/** A lease of a file found changed draws nothing of it, by either path: its cells are withdrawn. */
	@Test
	public void aChangedFilesCellsAreRefusedAsStaleByBothPaths() throws Exception {
		RawFrameRequest r = f.request(1, 2, RawFrameFixture.view(f.dims(), 0.45, 0.37, SW, SH), Interpolation.NLINEAR, 0, 2500, SW, SH);
		TiffRawVolumeStore.Ready lease = lease(f.reference, r, coverage(r));
		try {
			RawFrameFixture.touch(f.entry(2));
			assertTrue("found changed", !f.reference.checkGeneration(r.key));
			assertRefused(() -> renderer.render(r, lease, () -> false), TiffRawVolumeStore.StaleFileException.class, "changed");
			assertRefused(() -> renderer.render(r, lease::blocking, () -> false), TiffRawVolumeStore.StaleFileException.class, "changed");
		} finally {
			lease.close();
		}
	}

	private interface Frame {
		BufferedImage draw() throws Exception;
	}

	private static void assertRefused(Frame frame, Class<? extends RuntimeException> refusal, String message) throws Exception {
		try {
			frame.draw();
			fail("drawn");
		} catch (ExecutionException e) {
			assertTrue(String.valueOf(e.getCause()), refusal.isInstance(e.getCause()));
			assertTrue(e.getCause().getMessage(), e.getCause().getMessage().contains(message));
		}
	}

	/** The pinned arrays of a lease, row by row, are its image's values. */
	@Test
	public void aLeasesPixelsAreItsImagesValuesRowByRow() throws Exception {
		RawFrameRequest r = f.request(1, 0, RawFrameFixture.view(f.dims(), 0.45, 0, SW, SH), Interpolation.NLINEAR, 0, 2500, SW, SH);
		long[] dims = f.dims();
		BitSet all = new BitSet();
		int strips = (int) ((dims[1] + RawFrameFixture.ROWS - 1) / RawFrameFixture.ROWS);
		all.set(0, (int) (strips * dims[2]));
		TiffRawVolumeStore.Ready lease = lease(f.reference, r, all);
		try {
			TiffRawVolumeStore.Ready.Pixels pixels = lease.pixels(r.key, r.layout);
			assertEquals(dims[0], pixels.width);
			assertEquals(dims[1], pixels.height);
			assertEquals(dims[2], pixels.planes);
			net.imglib2.RandomAccess<net.imglib2.type.numeric.integer.UnsignedShortType> image = lease.blocking(r.key, r.layout).randomAccess();
			List<String> wrong = new ArrayList<String>();
			for (int z = 0; z < dims[2]; z++)
				for (int y = 0; y < dims[1]; y++)
					for (int x = 0; x < dims[0]; x++) {
						image.setPosition(new long[] { x, y, z });
						int expected = image.get().get(), actual = pixels.cell(y, z)[pixels.rowStart(y) + x] & 0xffff;
						if (expected != actual && wrong.size() < 5) wrong.add(x + "," + y + "," + z + ": " + expected + " against " + actual);
					}
			assertTrue(wrong.toString(), wrong.isEmpty());
		} finally {
			lease.close();
		}
	}
}
