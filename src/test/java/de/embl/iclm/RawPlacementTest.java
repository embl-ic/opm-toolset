package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.Random;

import org.junit.Test;

import bdv.viewer.Interpolation;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.integer.UnsignedShortType;

/** M4-B layout/placement contracts, including two probes that fail unchanged at the M4-A head. */
public class RawPlacementTest {

	static final String L1 = "_Channel0001-left", R1 = "_Channel0001-right";
	static final String L2 = "_Channel0002-left", R2 = "_Channel0002-right";
	static final String W1 = "_Channel0001-whole", W2 = "_Channel0002-whole";
	static final RawGeometry G = new RawGeometry(0.265, 0.116, 33.5, "placement test");

	static double[][] rigid(double degrees, double x, double y) {
		double a = Math.toRadians(degrees);
		return new double[][] { { Math.cos(a), -Math.sin(a), x }, { Math.sin(a), Math.cos(a), y } };
	}

	static AlignmentMatrixSet tagged() {
		AlignmentMatrixSet set = new AlignmentMatrixSet(L1);
		set.put(R1, rigid(1.3, 3.2, -2.1));
		set.put(L2, rigid(-0.7, -1.7, 2.6));
		set.put(R2, rigid(0.8, 2.4, 1.3));
		set.put(W1, rigid(-0.4, -3.3, 1.1));
		set.put(W2, rigid(0.6, 1.8, -2.7));
		return set;
	}

	@Test
	public void wholeWidthSlotsRetainOrderRepeatsAndSelectedFilesOnly() {
		OnlineDeskewView.Settings settings = RawMultiFileTest.settings(new File("."), W2,
				BatchChannelOperation.SKIP_CHANNEL, R1, W2, L1);
		assertEquals(Arrays.asList(W2, R1, W2, L1), settings.sourceKeys());
		assertArrayEquals(new int[] { 1, 2 }, settings.requiredChannels());
	}

	@Test
	public void flipLeftPlacementUsesTheCurrentFilesWidth() {
		for (AlignmentMatrixSet set : new AlignmentMatrixSet[] { tagged(), AlignmentMatrixSet.legacy(rigid(1.1, 2.7, -1.4)) })
			for (int channel : new int[] { 1, 2 }) for (boolean left : new boolean[] { true, false }) {
				RawSourceSpec spec = RawSourceSpec.half(set, channel, left, true, 32, 0);
				for (long width : new long[] { 32, 54, 53 }) {
					long[] dims = { width, 24, 16 };
					AlignmentMatrixSet.Placement placed = AlignmentMatrixSet.placement(set, spec.key, true, (int) (width / 2));
					AffineTransform3D expected = RawGeometry.halfVoxelConjugate(G.deskewMatrix(width, 24, 16));
					if (placed.mirror) expected.preConcatenate(RawGeometry.mirrorX(width / 2));
					if (placed.matrix != null) expected.preConcatenate(RawGeometry.xyPlacement(placed.matrix));
					AffineTransform3D scale = new AffineTransform3D();
					scale.scale(G.dxyUm);
					expected.preConcatenate(scale);
					assertArrayEquals(spec.key + " current width " + width, expected.getRowPackedCopy(),
							G.sourceToWorld(dims, spec, null).getRowPackedCopy(), 1e-12);
				}
			}
	}

	@Test
	public void alignmentInputIsFrozenEvenWhenTheWidthChangesLater() {
		AlignmentMatrixSet set = tagged();
		RawSourceSpec spec = RawSourceSpec.half(set, 2, true, true, 32, 0);
		long[] dims = { 54, 24, 16 };
		double[] before = G.sourceToWorld(dims, spec, null).getRowPackedCopy();
		set.put(L2, rigid(20, 70, 80));
		set.put(R1, rigid(-10, -90, -30));
		assertArrayEquals(before, G.sourceToWorld(dims, spec, null).getRowPackedCopy(), 0);
	}

	@Test
	public void wholeSourcesHaveTheirOwnTaggedMatrixAndNeverReflect() {
		for (int channel : new int[] { 1, 2 }) {
			AlignmentMatrixSet set = tagged();
			RawSourceSpec spec = RawSourceSpec.whole(set, channel, 0);
			assertEquals(RawSourceSpec.Part.WHOLE, spec.part);
			assertFalse(spec.mirror);
			long[] dims = { 53, 24, 16 };
			AffineTransform3D expected = RawGeometry.halfVoxelConjugate(G.deskewMatrix(53, 24, 16));
			expected.preConcatenate(RawGeometry.xyPlacement(set.matrixFor(spec.key)));
			AffineTransform3D scale = new AffineTransform3D();
			scale.scale(G.dxyUm);
			expected.preConcatenate(scale);
			assertArrayEquals(expected.getRowPackedCopy(), G.sourceToWorld(dims, spec, null).getRowPackedCopy(), 1e-12);
		}
	}

	@Test
	public void bothFlipsPutEveryTaggedHalfInTheSamePhysicalFrame() {
		AlignmentMatrixSet set = tagged();
		long[] dims = { 3200, 500, 451 };
		AffineTransform3D changeFrame = RawGeometry.mirrorX(1600);
		changeFrame.concatenate(RawGeometry.xyPlacement(set.matrixFor(R1)).inverse());
		for (int channel : new int[] { 1, 2 }) for (boolean left : new boolean[] { true, false }) {
			AffineTransform3D rightFlip = G.sourceToWorld(dims, RawSourceSpec.half(set, channel, left, false, 3200, 0), null);
			AffineTransform3D leftFlip = G.sourceToWorld(dims, RawSourceSpec.half(set, channel, left, true, 3200, 0), null);
			for (double[] raw : new double[][] { { 7.3, 30.1, 19.2 }, { 793.5, 252.7, 121.4 }, { 1511.2, 455.4, 430.1 } }) {
				double[] expected = new double[3], actual = new double[3];
				rightFlip.apply(raw, expected);
				for (int d = 0; d < 3; d++) expected[d] /= G.dxyUm;
				changeFrame.apply(expected, expected);
				for (int d = 0; d < 3; d++) expected[d] *= G.dxyUm;
				leftFlip.apply(raw, actual);
				assertArrayEquals(expected, actual, 1e-10);
			}
		}
	}

	@Test
	public void wholeNearestIsTheUnmirroredHalvesSideBySideAndKeepsOddCentre() {
		for (int width : new int[] { 32, 33 }) for (double angle : new double[] { 33.5, -25 }) {
			int height = 17, depth = 23;
			short[] values = new short[width * height * depth];
			Random random = new Random(251);
			for (int i = 0; i < values.length; i++) values[i] = (short) random.nextInt(65536);
			RandomAccessibleInterval<UnsignedShortType> raw = ArrayImgs.unsignedShorts(values, width, height, depth);
			RawGeometry geometry = new RawGeometry(0.265, 0.116, angle, "whole parity");
			long[] dims = { width, height, depth };
			RandomAccess<UnsignedShortType> whole = onGrid(raw, RawSourceSpec.whole(null, 1, 0), geometry, dims);
			long[] grid = geometry.deskewedGrid(width, height, depth);
			long compared = 0;
			for (boolean left : new boolean[] { true, false }) {
				RawSourceSpec half = RawSourceSpec.half(null, 1, left, !left, width, 0);
				RandomAccess<UnsignedShortType> cropped = onGrid(raw, half, geometry, dims);
				for (int z = 0; z < grid[2]; z++) for (int y = 0; y < grid[1]; y++)
					for (int x = 0; x < width / 2; x++) {
						whole.setPosition(new long[] { x + half.cropMin(width), y, z });
						cropped.setPosition(new long[] { x, y, z });
						assertEquals(whole.get().get(), cropped.get().get());
						compared++;
					}
			}
			assertEquals(width, RawSampling.crop(raw, RawSourceSpec.whole(null, 1, 0)).dimension(0));
			assertTrue(compared > 1000);
		}
	}

	static RandomAccess<UnsignedShortType> onGrid(RandomAccessibleInterval<UnsignedShortType> raw, RawSourceSpec spec,
			RawGeometry geometry, long[] dims) {
		AffineTransform3D toGrid = geometry.sourceToWorld(dims, spec, null);
		AffineTransform3D unscale = new AffineTransform3D();
		unscale.scale(1 / geometry.dxyUm);
		toGrid.preConcatenate(unscale);
		return RealViews.affine(RawSampling.interpolate(RawSampling.crop(raw, spec), Interpolation.NEARESTNEIGHBOR), toGrid).randomAccess();
	}
}
