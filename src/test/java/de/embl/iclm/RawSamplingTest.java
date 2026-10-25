package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.junit.Test;

import bdv.viewer.Interpolation;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileShortArray;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.volatiles.VolatileUnsignedShortType;
import net.imglib2.util.Fraction;

/**
 * How the Online Deskew View samples a raw part (joint contract C1.9, decision D1).
 *
 * <p>The saturation case is the reason this class exists. ImgLib2's plain trilinear interpolator
 * accumulates in the pixel type: eight uint16 terms of a saturated neighbourhood each round up,
 * sum to 65536 and wrap, so a saturated bead would be drawn with a black centre wherever it is
 * sampled between voxels. {@link RawSampling} uses the clamping interpolator instead, for the
 * blocking source and for its volatile twin alike.
 */
public class RawSamplingTest {

	@Test
	public void aSaturatedNeighbourhoodStaysSaturated() {
		short[] saturated = new short[8];
		Arrays.fill(saturated, (short) 65535);
		RealRandomAccess<UnsignedShortType> sample = RawSampling.interpolate(
				ArrayImgs.unsignedShorts(saturated, 2, 2, 2), Interpolation.NLINEAR).realRandomAccess();
		sample.setPosition(new double[] { 0.5, 0.5, 0.5 });
		assertEquals(65535, sample.get().get());
	}

	@Test
	public void trilinearIsOneRoundingFromAnExactInterpolation() {
		int n = 24;
		Random random = new Random(3);
		short[] data = new short[n * n * n];
		for (int i = 0; i < data.length; i++)
			data[i] = (short) (random.nextInt(3) == 0 ? 65535 - random.nextInt(4) : random.nextInt(65536));
		RealRandomAccess<UnsignedShortType> sample = RawSampling.interpolate(
				ArrayImgs.unsignedShorts(data, n, n, n), Interpolation.NLINEAR).realRandomAccess();
		double worst = 0;
		for (int i = 0; i < 50000; i++) {
			double[] p = { random.nextDouble() * (n - 1), random.nextDouble() * (n - 1), random.nextDouble() * (n - 1) };
			sample.setPosition(p);
			worst = Math.max(worst, Math.abs(sample.get().get() - exact(data, n, p)));
		}
		assertTrue("worst difference " + worst, worst <= 0.5 + 1e-9);
	}

	@Test
	public void nearestReturnsTheRawSamples() {
		short[] data = { 10, 20, 30, 40, 50, 60, 70, 80 };
		RealRandomAccess<UnsignedShortType> sample = RawSampling.interpolate(
				ArrayImgs.unsignedShorts(data, 2, 2, 2), Interpolation.NEARESTNEIGHBOR).realRandomAccess();
		sample.setPosition(new double[] { 1.2, 0.1, 0.9 });
		assertEquals(60, sample.get().get());
	}

	@Test
	public void aVolatileSampleIsValidOnlyWhenItsNeighboursAre() {
		for (boolean valid : new boolean[] { true, false }) {
			short[] saturated = new short[8];
			Arrays.fill(saturated, (short) 65535);
			ArrayImg<VolatileUnsignedShortType, VolatileShortArray> img = new ArrayImg<VolatileUnsignedShortType, VolatileShortArray>(
					new VolatileShortArray(saturated, valid), new long[] { 2, 2, 2 }, new Fraction());
			img.setLinkedType(new VolatileUnsignedShortType(img));
			RealRandomAccess<VolatileUnsignedShortType> sample = RawSampling.interpolate(img, Interpolation.NLINEAR).realRandomAccess();
			sample.setPosition(new double[] { 0.5, 0.5, 0.5 });
			assertEquals(valid, sample.get().isValid());
			assertEquals(65535, sample.get().get().get());
			// outside the part the extension is a valid zero, so edges can complete
			sample.setPosition(new double[] { -3, 0, 0 });
			assertTrue("the zero extension is valid whatever the part holds", sample.get().isValid());
			assertEquals(0, sample.get().get().get());
		}
	}

	@Test
	public void aHalfNeverBlendsWithTheOtherHalf() {
		// left half 1000, right half 50000, camera width 8
		short[] data = new short[8 * 2 * 2];
		for (int i = 0; i < data.length; i++) data[i] = (short) (i % 8 < 4 ? 1000 : 50000);
		RandomAccessibleInterval<UnsignedShortType> raw = ArrayImgs.unsignedShorts(data, 8, 2, 2);
		RealRandomAccess<UnsignedShortType> left = RawSampling.interpolate(
				RawSampling.crop(raw, RawSourceSpec.half(null, 1, true, false, 8, 0)), Interpolation.NLINEAR).realRandomAccess();
		RealRandomAccess<UnsignedShortType> right = RawSampling.interpolate(
				RawSampling.crop(raw, RawSourceSpec.half(null, 1, false, false, 8, 0)), Interpolation.NLINEAR).realRandomAccess();
		left.setPosition(new double[] { 3.5, 0, 0 });		// between the last left column and outside
		assertEquals(500, left.get().get());
		right.setPosition(new double[] { -0.5, 0, 0 });		// between outside and the first right column
		assertEquals(25000, right.get().get());
	}

	private static double exact(short[] data, int n, double[] p) {
		int x0 = (int) Math.floor(p[0]), y0 = (int) Math.floor(p[1]), z0 = (int) Math.floor(p[2]);
		double fx = p[0] - x0, fy = p[1] - y0, fz = p[2] - z0, sum = 0;
		for (int dz = 0; dz < 2; dz++)
			for (int dy = 0; dy < 2; dy++)
				for (int dx = 0; dx < 2; dx++) {
					int x = x0 + dx, y = y0 + dy, z = z0 + dz;
					double v = x < n && y < n && z < n ? (data[(z * n + y) * n + x] & 0xffff) : 0;
					sum += v * (dx == 0 ? 1 - fx : fx) * (dy == 0 ? 1 - fy : fy) * (dz == 0 ? 1 - fz : fz);
				}
		return sum;
	}
}
