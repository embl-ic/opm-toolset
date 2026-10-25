package de.embl.iclm;

import java.util.List;
import java.util.function.BiFunction;

import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.NumericType;

/**
 * One displayed part of a raw acquisition - a camera half of one acquisition channel - as a
 * BigDataViewer source over every time point (joint contract C5.1).
 *
 * <p>BigDataViewer's time index {@code t} is a position in the catalogue's list of time points
 * for this channel, frozen when the view opens; the list holds the acquisition's own time numbers
 * (C2.1). For each time point the source is the cropped part of that file's raw volume, and its
 * transform is {@link RawGeometry#sourceToWorld} for that file's own dimensions: the deskew,
 * mirror and alignment are applied by BigDataViewer while it draws, and the raw pixels are never
 * resampled or written.
 *
 * <p>A time point whose file is not validated yet, or cannot be shown, is simply not present:
 * BigDataViewer draws nothing for it, and the view's status line says why.
 *
 * @param <T> UnsignedShortType for the blocking twin, VolatileUnsignedShortType for the volatile one
 */
final class RawPartSource<T extends NumericType<T>> implements Source<T> {

	private final RawAcquisition acquisition;
	private final List<RawAcquisition.TimeKey> times;
	private final RawSourceSpec spec;
	private final RawGeometry geometry;
	private final BiFunction<RawAcquisition.FileKey, RawTiffLayout, RandomAccessibleInterval<T>> volumes;
	private final T type;
	/** Dimensions used for the transform of a time point that cannot be shown. */
	private final long[] referenceDims;

	RawPartSource(RawAcquisition acquisition, List<RawAcquisition.TimeKey> times, RawSourceSpec spec, RawGeometry geometry,
			BiFunction<RawAcquisition.FileKey, RawTiffLayout, RandomAccessibleInterval<T>> volumes, T type, long[] referenceDims) {
		this.acquisition = acquisition;
		this.times = times;
		this.spec = spec;
		this.geometry = geometry;
		this.volumes = volumes;
		this.type = type;
		this.referenceDims = referenceDims.clone();
	}

	/** The catalogue entry this source shows at BigDataViewer time {@code t}, or null. */
	RawAcquisition.Entry entry(int t) {
		return t < 0 || t >= times.size() ? null : acquisition.entry(times.get(t), spec.acquisitionChannel);
	}

	@Override
	public boolean isPresent(int t) {
		RawAcquisition.Entry e = entry(t);
		return e != null && e.viewable();
	}

	@Override
	public RandomAccessibleInterval<T> getSource(int t, int level) {
		RawAcquisition.Entry e = entry(t);
		if (e == null || !e.viewable()) throw new IllegalStateException(spec.key + " has no data at time index " + t);
		return RawSampling.crop(volumes.apply(e.key(), e.layout()), spec);
	}

	@Override
	public RealRandomAccessible<T> getInterpolatedSource(int t, int level, Interpolation method) {
		return RawSampling.interpolate(getSource(t, level), method);
	}

	@Override
	public void getSourceTransform(int t, int level, AffineTransform3D transform) {
		RawAcquisition.Entry e = entry(t);
		long[] dims = e != null && e.viewable() ? e.layout().dimensions() : referenceDims;
		transform.set(geometry.sourceToWorld(dims, spec, null));
	}

	@Override
	public T getType() {
		return type.createVariable();
	}

	@Override
	public String getName() {
		return spec.key;
	}

	@Override
	public VoxelDimensions getVoxelDimensions() {
		// the raw lattice's nominal pitch; the transform carries its oblique directions
		return new FinalVoxelDimensions("um", geometry.dxyUm, geometry.dxyUm, geometry.dzUm);
	}

	@Override
	public int getNumMipmapLevels() {
		return 1;
	}

	RawSourceSpec spec() {
		return spec;
	}
}
