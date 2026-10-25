package de.embl.iclm;

import bdv.viewer.Interpolation;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.view.Views;

/**
 * How the Online Deskew View samples a raw part (joint contract C1.9).
 *
 * <ul>
 * <li>A part is cropped out of the full camera width and zero-minned <em>before</em> it is
 *     extended, so interpolating near a half's edge blends with zero and never with the other
 *     half (C1.7).</li>
 * <li>Nearest neighbour returns the raw samples themselves; it is the parity mode, bit-exact
 *     with the toolset's stored deskew.</li>
 * <li>Trilinear uses ImgLib2's {@link ClampingNLinearInterpolatorFactory}: it accumulates in
 *     {@code double} and converts once, clamped to the type's range. ImgLib2's plain
 *     {@code NLinearInterpolatorFactory} accumulates in the pixel type itself, so eight weighted
 *     uint16 terms can round past 65535 and wrap: a saturated neighbourhood comes back as 0. The
 *     clamping factory also serves the volatile twin, which it marks valid only when every
 *     neighbour is valid.</li>
 * </ul>
 */
final class RawSampling {

	private RawSampling() {}

	/** The interpolator for a display mode: nearest, or the saturation-safe trilinear. */
	static <T extends NumericType<T>> InterpolatorFactory<T, RandomAccessible<T>> factory(Interpolation method) {
		if (method == Interpolation.NLINEAR) return new ClampingNLinearInterpolatorFactory<T>();
		return new NearestNeighborInterpolatorFactory<T>();
	}

	/**
	 * One part of a full-width raw volume as its own zero-based image.
	 *
	 * @param fullWidth	: the raw volume, x = camera column, y = camera row, z = plane
	 * @param spec		: which part
	 * @return			: a view of that part only, starting at 0
	 */
	static <T> RandomAccessibleInterval<T> crop(RandomAccessibleInterval<T> fullWidth, RawSourceSpec spec) {
		long width = fullWidth.dimension(0);
		long x0 = fullWidth.min(0) + spec.cropMin(width);
		long[] min = { x0, fullWidth.min(1), fullWidth.min(2) };
		long[] max = { x0 + spec.cropWidth(width) - 1, fullWidth.max(1), fullWidth.max(2) };
		return Views.zeroMin(Views.interval(fullWidth, min, max));
	}

	/** A cropped part, zero outside it, interpolated as {@code method} says. */
	static <T extends NumericType<T>> RealRandomAccessible<T> interpolate(RandomAccessibleInterval<T> part,
			Interpolation method) {
		return Views.interpolate(Views.extendZero(part), RawSampling.<T>factory(method));
	}
}
