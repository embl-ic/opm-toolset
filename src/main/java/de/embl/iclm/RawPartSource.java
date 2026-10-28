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
 * <p>BigDataViewer's time index {@code t} is a position in a catalogue snapshot's list of time
 * points for this channel; the list holds the acquisition's own time numbers (C2.1). A source made
 * over a {@link RawPresence} resolves every index through the frame being drawn - which time point,
 * whether it is shown, from which file version - so that the halves of one frame agree on all
 * three, and a growing axis needs no new source (M3-A). For each time point the source is the
 * cropped part of that file's raw volume, and its
 * transform is {@link RawGeometry#sourceToWorld} for that file's own dimensions: the deskew,
 * mirror and alignment are applied by BigDataViewer while it draws, and the raw pixels are never
 * resampled or written.
 *
 * <p>A time point whose file is not validated yet, or cannot be shown, is simply not present:
 * BigDataViewer draws nothing for it, and the view's status line says why. Presence is decided
 * once per frame by a {@link RawPresence} that every source of the view shares.
 *
 * <p><b>Withdrawal is safe for calls already under way.</b> BigDataViewer may have found a time
 * point present just before its file was found changed, and then ask for its source. The geometry
 * - interval and transform - comes from the version that was checked, which the catalogue keeps,
 * so those questions are always answered. The pixels are not: the store refuses a blocking read
 * of a withdrawn file and leaves a volatile one invalid, never a value it does not have.
 *
 * @param <T> UnsignedShortType for the blocking twin, VolatileUnsignedShortType for the volatile one
 */
final class RawPartSource<T extends NumericType<T>> implements Source<T> {

	/** Null for a source over a presence, which resolves every time index itself. */
	private final RawAcquisition acquisition;
	private final List<RawAcquisition.TimeKey> times;
	private final RawSourceSpec spec;
	private final RawGeometry geometry;
	private final BiFunction<RawAcquisition.FileKey, RawTiffLayout, RandomAccessibleInterval<T>> volumes;
	private final T type;
	/** Dimensions used for the transform of a time point that never could be shown. */
	private final long[] referenceDims;
	/** Null: presence follows the catalogue directly, as in tests without a viewer. */
	private final RawPresence presence;

	RawPartSource(RawAcquisition acquisition, List<RawAcquisition.TimeKey> times, RawSourceSpec spec, RawGeometry geometry,
			BiFunction<RawAcquisition.FileKey, RawTiffLayout, RandomAccessibleInterval<T>> volumes, T type, long[] referenceDims) {
		this(acquisition, times, spec, geometry, volumes, type, referenceDims, null);
	}

	RawPartSource(RawAcquisition acquisition, List<RawAcquisition.TimeKey> times, RawSourceSpec spec, RawGeometry geometry,
			BiFunction<RawAcquisition.FileKey, RawTiffLayout, RandomAccessibleInterval<T>> volumes, T type, long[] referenceDims,
			RawPresence presence) {
		this.acquisition = acquisition;
		this.times = times;
		this.spec = spec;
		this.geometry = geometry;
		this.volumes = volumes;
		this.type = type;
		this.referenceDims = referenceDims.clone();
		this.presence = presence;
	}

	/**
	 * A source of the Online Deskew View: every time index resolved through {@code presence}'s frame - the time
	 * point, whether it is shown and its file version - shared by every source of the view (M3-A).
	 */
	RawPartSource(RawSourceSpec spec, RawGeometry geometry,
			BiFunction<RawAcquisition.FileKey, RawTiffLayout, RandomAccessibleInterval<T>> volumes, T type, long[] referenceDims,
			RawPresence presence) {
		this(null, null, spec, geometry, volumes, type, referenceDims, presence);
		if (presence == null) throw new IllegalArgumentException("A source over a presence needs one");
	}

	/** The catalogue entry this source shows at BigDataViewer time {@code t}, or null. */
	RawAcquisition.Entry entry(int t) {
		if (acquisition == null) return presence.frame().snapshot.entry(t);
		return t < 0 || t >= times.size() ? null : acquisition.entry(times.get(t), spec.acquisitionChannel);
	}

	@Override
	public boolean isPresent(int t) {
		if (presence != null) return presence.present(t);
		RawAcquisition.Entry e = entry(t);
		return e != null && e.viewable();
	}

	/** The version and layout time index {@code t} is drawn from: the frame's, for a source over a presence. */
	private RawAcquisition.FileKey key(int t) {
		if (presence != null) return presence.frame().key(t);
		RawAcquisition.Entry e = entry(t);
		return e == null ? null : e.key();
	}

	private RawTiffLayout layout(int t) {
		if (presence != null) return presence.frame().layout(t);
		RawAcquisition.Entry e = entry(t);
		return e == null ? null : e.layout();
	}

	/**
	 * The cropped part of time point {@code t}'s volume, for any time point that was ever shown -
	 * withdrawn ones included, whose pixels then cannot be read.
	 *
	 * @throws IllegalStateException for a time point that never could be shown, which no caller
	 * 								 that asked {@link #isPresent} first can reach
	 */
	@Override
	public RandomAccessibleInterval<T> getSource(int t, int level) {
		RawAcquisition.FileKey key = key(t);
		RawTiffLayout layout = layout(t);
		if (key == null || layout == null) throw new IllegalStateException(spec.key + " has no data at time index " + t);
		return RawSampling.crop(volumes.apply(key, layout), spec);
	}

	@Override
	public RealRandomAccessible<T> getInterpolatedSource(int t, int level, Interpolation method) {
		return RawSampling.interpolate(getSource(t, level), method);
	}

	@Override
	public void getSourceTransform(int t, int level, AffineTransform3D transform) {
		RawTiffLayout layout = layout(t);
		transform.set(geometry.sourceToWorld(layout != null ? layout.dimensions() : referenceDims, spec, null));
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
