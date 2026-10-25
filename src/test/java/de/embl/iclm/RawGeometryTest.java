package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Random;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.viewer.Interpolation;
import ij.ImagePlus;
import ij.process.ShortProcessor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.integer.UnsignedShortType;

/**
 * The geometry of the Online Deskew View: the source-to-world transform {@code S M F D_eff P_l}
 * of joint contract C1, checked against the toolset's own deskew.
 *
 * <p>The exactness tests transcribe CLIJ2's buffer kernel ({@code affine_transform_3d_x.cl}):
 * for output voxel {@code o} it reads source voxel {@code floor(M^-1 (o + 1/2))}, and zero when
 * that falls outside. That kernel produced every stored deskew in the toolset, so a raw view
 * sampled through {@link RawGeometry#sourceToWorld} with nearest neighbour has to reproduce it
 * voxel for voxel. Real data confirmed this in research (0 of 14.2 M pixels differ); here it is
 * pinned on synthetic volumes, for both angle signs and for a mirrored half.
 */
public class RawGeometryTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final double DZ = 0.265, DXY = 0.116;

	@Test
	public void theDeskewIsTheToolsetsOwnMatrixAndGrid() {
		for (double angle : new double[] { 25, 33.5 }) {
			RawGeometry g = new RawGeometry(DZ, DXY, angle, "test");
			double[][] expected = Transform.autoCenter(new long[] { 1600, 500, 451 },
					Transform.deskew(DZ, DXY, angle, 500));
			double[][] actual = g.deskewMatrix(1600, 500, 451);
			for (int r = 0; r < 4; r++) assertArrayEquals(expected[r], actual[r], 0);
		}
		// the sample acquisition's half at 33.5 degrees, and the stored reference's 25 degrees
		assertArrayEquals(new long[] { 1600, 1448, 276 }, new RawGeometry(DZ, DXY, 33.5, "test").deskewedGrid(1600, 500, 451));
		assertArrayEquals(new long[] { 1600, 1484, 212 }, new RawGeometry(DZ, DXY, 25, "test").deskewedGrid(1600, 500, 451));
	}

	@Test
	public void aNegativeAngleStillLandsInsideItsGrid() {
		RawGeometry g = new RawGeometry(DZ, DXY, -25, "test");
		long[] dims = { 1600, 500, 451 };
		double[][] m = g.deskewMatrix(dims[0], dims[1], dims[2]);
		double[][] minMax = Transform.getCoordMinMax(dims, m);
		long[] grid = g.deskewedGrid(dims[0], dims[1], dims[2]);
		for (int d = 0; d < 3; d++) {
			assertTrue("axis " + d + " starts inside the grid", minMax[0][d] >= -1e-9);
			assertTrue("axis " + d + " ends inside the grid", minMax[1][d] <= grid[d] + 1e-9);
		}
	}

	@Test
	public void theHalfVoxelConjugationIsClijsConvention() {
		double[][] m = new RawGeometry(DZ, DXY, 33.5, "test").deskewMatrix(40, 17, 23);
		AffineTransform3D forward = new AffineTransform3D();
		forward.set(m[0][0], m[0][1], m[0][2], m[0][3], m[1][0], m[1][1], m[1][2], m[1][3], m[2][0], m[2][1], m[2][2], m[2][3]);
		AffineTransform3D conjugate = RawGeometry.halfVoxelConjugate(m);
		double[] p = { 3, 5, 7 }, viaClij = new double[3], viaConjugate = new double[3];
		forward.apply(new double[] { p[0] + 0.5, p[1] + 0.5, p[2] + 0.5 }, viaClij);
		conjugate.apply(p, viaConjugate);
		for (int d = 0; d < 3; d++) assertEquals(viaClij[d] - 0.5, viaConjugate[d], 1e-9);
		// X is untouched by the deskew, so the conjugation adds nothing there
		assertEquals(0, conjugate.get(0, 3), 1e-12);
	}

	@Test
	public void theCompositionIsScaleMatrixMirrorDeskewLevel() {
		RawGeometry g = new RawGeometry(DZ, DXY, 33.5, "test");
		double[][] matrix = { { Math.cos(0.01), -Math.sin(0.01), 11.2 }, { Math.sin(0.01), Math.cos(0.01), -6.9 } };
		RawSourceSpec spec = RawSourceSpec.half(AlignmentMatrixSet.legacy(matrix), 1, false, false, 3200, 0xff00ff);
		assertTrue(spec.mirror);
		long[] dims = { 3200, 500, 451 };
		double[] factors = { 2, 2, 1 };
		AffineTransform3D composed = g.sourceToWorld(dims, spec, factors);
		double[] level = { 10, 20, 30 }, step = new double[3], viaComposed = new double[3];
		// rightmost first: level -> deskew -> mirror -> matrix -> scale
		RawGeometry.levelToBase(factors).apply(level, step);
		RawGeometry.halfVoxelConjugate(g.deskewMatrix(dims[0], dims[1], dims[2])).apply(step, step);
		RawGeometry.mirrorX(1600).apply(step, step);
		RawGeometry.xyPlacement(matrix).apply(step, step);
		for (int d = 0; d < 3; d++) step[d] *= DXY;
		composed.apply(level, viaComposed);
		assertArrayEquals(step, viaComposed, 1e-9);
	}

	@Test
	public void theMirrorIsAboutTheLastPixelCentre() {
		double[] out = new double[3];
		RawGeometry.mirrorX(1600).apply(new double[] { 0, 4, 5 }, out);
		assertArrayEquals(new double[] { 1599, 4, 5 }, out, 0);
		RawGeometry.mirrorX(1600).apply(new double[] { 1599, 4, 5 }, out);
		assertArrayEquals(new double[] { 0, 4, 5 }, out, 0);
	}

	@Test
	public void levelVoxelCentresMapToLevelZeroCentres() {
		double[] out = new double[3];
		AffineTransform3D p = RawGeometry.levelToBase(new double[] { 2, 2, 1 });
		p.apply(new double[] { 0, 0, 0 }, out);
		assertArrayEquals(new double[] { 0.5, 0.5, 0 }, out, 0);
		p.apply(new double[] { 1, 3, 7 }, out);
		assertArrayEquals(new double[] { 2.5, 6.5, 7 }, out, 0);
		assertArrayEquals(new AffineTransform3D().getRowPackedCopy(),
				RawGeometry.levelToBase(new double[] { 1, 1, 1 }).getRowPackedCopy(), 0);
	}

	@Test
	public void placementComesFromAlignmentMatrixSet() {
		double[][] m = { { 0.99999, -0.0044, 11.66 }, { 0.0044, 0.99999, -7.49 } };
		AlignmentMatrixSet legacy = AlignmentMatrixSet.legacy(m);
		RawSourceSpec right = RawSourceSpec.half(legacy, 1, false, false, 3200, 0);
		RawSourceSpec left = RawSourceSpec.half(legacy, 1, true, false, 3200, 0);
		assertTrue(right.mirror);
		assertArrayEquals(m[0], right.matrix[0], 0);
		assertFalse(left.mirror);
		assertNull(left.matrix);
		// flip the left halves instead: the left half is mirrored and carries F M^-1 F
		RawSourceSpec leftFlipped = RawSourceSpec.half(legacy, 1, true, true, 3200, 0);
		assertTrue(leftFlipped.mirror);
		double[][] reframed = Transform.mirrorAlignmentMatrix2D(m, 1600);
		for (int r = 0; r < 2; r++) assertArrayEquals(reframed[r], leftFlipped.matrix[r], 1e-9);
		assertFalse(RawSourceSpec.half(legacy, 1, false, true, 3200, 0).mirror);
		// no matrix file: the configured half is still mirrored, as the toolset does
		assertTrue(RawSourceSpec.half(null, 1, false, false, 3200, 0).mirror);
		assertFalse(RawSourceSpec.whole(legacy, 1, 0).mirror);
		assertNull(RawSourceSpec.whole(legacy, 1, 0).matrix);
	}

	@Test
	public void halvesFollowPartitionIncludingAnOddWidth() {
		for (int width : new int[] { 8, 7 }) {
			ImagePlus imp = new ImagePlus("raw", new ShortProcessor(width, 3));
			for (int x = 0; x < width; x++) for (int y = 0; y < 3; y++) imp.getProcessor().set(x, y, 100 + x);
			ImagePlus[] halves = Partition.separateImageLeftRight(imp, "left & right separately");
			RandomAccessibleInterval<UnsignedShortType> raw = ArrayImgs.unsignedShorts(
					(short[]) imp.getProcessor().getPixels(), width, 3, 1);
			for (int side = 0; side < 2; side++) {
				RawSourceSpec spec = RawSourceSpec.half(null, 1, side == 0, false, width, 0);
				RandomAccessibleInterval<UnsignedShortType> part = RawSampling.crop(raw, spec);
				assertEquals("width of the " + spec.part + " half of " + width, halves[side].getWidth(), part.dimension(0));
				RandomAccess<UnsignedShortType> a = part.randomAccess();
				for (int x = 0; x < part.dimension(0); x++) {
					a.setPosition(new long[] { x, 1, 0 });
					assertEquals(spec.part + " column " + x + " of width " + width,
							halves[side].getProcessor().get(x, 1), a.get().get());
				}
			}
		}
	}

	@Test
	public void deskewOnlyNearestIsTheClijKernelVoxelForVoxel() {
		for (double angle : new double[] { 25, 33.5, -25 }) {
			Random random = new Random(11);
			int width = 26, height = 17, depth = 23;
			short[] data = new short[width * height * depth];
			for (int i = 0; i < data.length; i++) data[i] = (short) random.nextInt(65536);
			RandomAccessibleInterval<UnsignedShortType> raw = ArrayImgs.unsignedShorts(data, width, height, depth);
			RawGeometry g = new RawGeometry(DZ, DXY, angle, "test");
			for (boolean left : new boolean[] { true, false }) {
				// unmirrored halves: what the canonical OME-Zarr stores
				RawSourceSpec spec = RawSourceSpec.half(null, 1, left, !left, width, 0);
				assertFalse(spec.mirror);
				RandomAccessibleInterval<UnsignedShortType> part = RawSampling.crop(raw, spec);
				long w = part.dimension(0);
				double[][] clij = g.deskewMatrix(w, height, depth);
				assertSameVoxels("angle " + angle + " " + spec.part, part, clij, g, spec, w, height, depth);
			}
		}
	}

	@Test
	public void aMirroredHalfIsTheToolsetsFlippedDeskewVoxelForVoxel() {
		for (double angle : new double[] { 33.5, -25 }) {
			Random random = new Random(5);
			int width = 26, height = 17, depth = 23;
			short[] data = new short[width * height * depth];
			for (int i = 0; i < data.length; i++) data[i] = (short) random.nextInt(65536);
			RandomAccessibleInterval<UnsignedShortType> raw = ArrayImgs.unsignedShorts(data, width, height, depth);
			RawGeometry g = new RawGeometry(DZ, DXY, angle, "test");
			RawSourceSpec spec = RawSourceSpec.half(null, 1, false, false, width, 0);
			assertTrue(spec.mirror);
			RandomAccessibleInterval<UnsignedShortType> part = RawSampling.crop(raw, spec);
			long w = part.dimension(0);
			// MultiChannelDeskew.deskewHalves: Transform.matrix_flipX, then GPU.transform's autoCenter
			double[][] flipped = Transform.matrix_flipX(new ImagePlus("half", new ShortProcessor((int) w, height)),
					Transform.deskew(DZ, DXY, angle, height));
			Transform.autoCenter(new long[] { w, height, depth }, flipped);
			assertSameVoxels("mirrored, angle " + angle, part, flipped, g, spec, w, height, depth);
		}
	}

	@Test
	public void invalidGeometryIsRefused() {
		double[][] bad = { { 0, DXY, 30 }, { DZ, 0, 30 }, { DZ, DXY, 0 }, { DZ, DXY, 90 }, { Double.NaN, DXY, 30 },
				{ DZ, Double.POSITIVE_INFINITY, 30 }, { DZ, DXY, Double.NaN } };
		for (double[] b : bad) {
			try {
				new RawGeometry(b[0], b[1], b[2], "test");
				fail("accepted " + java.util.Arrays.toString(b));
			} catch (IllegalArgumentException expected) {
				// refused, as it should be
			}
		}
	}

	@Test
	public void theGeometryIsReadFromExperimentalParameters() throws IOException {
		File file = folder.newFile("ExperimentalParameters.txt");
		try (FileWriter w = new FileWriter(file)) {
			w.write("EXPERIMENT:\nInterval: 5 Minute\nNumber of Channels: 2\nOPM:\nTilt angle: 33.500000\n"
					+ "um per galvo DU: 0.013250\nGalvo DUs per step: 20\nPlanes per volume: 451\nCAMERA:\n"
					+ "Pixel size at object /um: 0.116000\nCamera ROI X0, X1, Y0, Y1: 0 3200 1350 1850\n");
		}
		RawGeometry g = RawGeometry.fromExperimentalParameters(file);
		assertEquals(0.265, g.dzUm, 1e-9);
		assertEquals(0.116, g.dxyUm, 1e-9);
		assertEquals(33.5, g.angleDeg, 1e-9);
		assertEquals(file.getAbsolutePath(), g.origin);
		try {
			RawGeometry.fromExperimentalParameters(new File(folder.getRoot(), "missing.txt"));
			fail("a missing file produced a geometry");
		} catch (IllegalArgumentException expected) {
			// refused
		}
	}

	/**
	 * Every voxel of the deskewed grid, sampled through the view's transform with nearest
	 * neighbour, against the CLIJ2 buffer kernel run with {@code clijForward}.
	 */
	private static void assertSameVoxels(String what, RandomAccessibleInterval<UnsignedShortType> part,
			double[][] clijForward, RawGeometry g, RawSourceSpec spec, long w, int height, int depth) {
		long[] grid = g.deskewedGrid(w, height, depth);
		AffineTransform3D forward = new AffineTransform3D();
		double[][] m = clijForward;
		forward.set(m[0][0], m[0][1], m[0][2], m[0][3], m[1][0], m[1][1], m[1][2], m[1][3], m[2][0], m[2][1], m[2][2], m[2][3]);
		AffineTransform3D clijInverse = forward.inverse();
		RandomAccess<UnsignedShortType> source = part.randomAccess();

		// the view: source-to-world, taken back to grid units
		AffineTransform3D toGrid = g.sourceToWorld(new long[] { 2 * w, height, depth }, spec, null);
		AffineTransform3D unscale = new AffineTransform3D();
		unscale.scale(1 / g.dxyUm);
		toGrid.preConcatenate(unscale);
		RandomAccess<UnsignedShortType> view = RealViews.affine(
				RawSampling.interpolate(part, Interpolation.NEARESTNEIGHBOR), toGrid).randomAccess();

		long n = 0, inside = 0;
		double[] o = new double[3], s = new double[3];
		long[] dims = { w, height, depth };
		for (long z = 0; z < grid[2]; z++)
			for (long y = 0; y < grid[1]; y++)
				for (long x = 0; x < grid[0]; x++) {
					o[0] = x + 0.5; o[1] = y + 0.5; o[2] = z + 0.5;
					clijInverse.apply(o, s);
					int expected = 0;
					if (s[0] >= 0 && s[1] >= 0 && s[2] >= 0 && s[0] < dims[0] && s[1] < dims[1] && s[2] < dims[2]) {
						source.setPosition(new long[] { (long) Math.floor(s[0]), (long) Math.floor(s[1]), (long) Math.floor(s[2]) });
						expected = source.get().get();
						inside++;
					}
					view.setPosition(new long[] { x, y, z });
					assertEquals(what + " at " + x + "," + y + "," + z, expected, view.get().get());
					n++;
				}
		assertTrue(what + ": the grid holds data", inside > n / 4);
	}
}
