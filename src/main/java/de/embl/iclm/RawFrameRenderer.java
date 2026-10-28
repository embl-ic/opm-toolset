package de.embl.iclm;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

import bdv.viewer.Interpolation;
import net.imglib2.FinalRealInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.display.RealARGBColorConverter;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedShortType;

/**
 * Draws a {@link RawFrameRequest} into an image of its own, off screen, for completed-frame
 * playback - the "staged final image" of joint contract C6.3.
 *
 * <p>BigDataViewer's own display cannot say which request an image on screen answers: it announces
 * a newly shown image with nothing but its viewer transform, which is the full-resolution transform
 * even for a coarse intermediate image, and carries no time point (verified in 10.6.4 and 10.6.11).
 * So a frame for strict playback is drawn here instead, from the lease's own image of the cells a
 * {@link TiffRawVolumeStore.Ready} holds resident - every sample is a value, the image is complete
 * by construction - and handed whole to {@link RawPresenter}.
 *
 * <p>It draws what BigDataViewer draws: each layer sampled through the same transform and the same
 * saturation-safe interpolation, converted by ImgLib2's {@link RealARGBColorConverter} with the
 * layer's range and colour, and the layers summed per channel and clamped to 255, which is
 * BigDataViewer's {@code AccumulateProjectorARGB}. A layer is drawn only inside its bounding box on
 * screen, widened by the interpolation's reach, and not at all when the screen plane misses it -
 * BigDataViewer's {@code VisibleSourcesOnScreenBounds}. BigDataViewer applies that box by tiles,
 * which it stops splitting below 64 pixels, so next to a box edge it can draw a part a few pixels
 * beyond the box, where the part shows its zero extension. That is black whenever the display
 * minimum is zero or more; only a negative minimum gives such pixels a colour. Culling at the box is
 * this view's declared policy; it never changes what a part draws inside its own box.
 *
 * <p>A frame of a lease's pinned cells - every frame of completed-frame playback - is sampled from the cells' arrays by
 * {@link PinnedLayer} rather than through ImgLib2, with the same arithmetic and so the same pixels: through ImgLib2 its speed
 * depended on what else in the runtime had used ImgLib2 first (M2-Q-F2). Any other image is drawn through ImgLib2, which
 * stays the reference the pinned path is held to.
 *
 * <p><b>{@link #render} returns only once every band it started has finished</b> - drawn, failed or
 * stopped - so that whoever holds the frame's cells and image can release them the moment it
 * returns, and no band still samples or writes them. Bands stop cooperatively, at the next row, once
 * the frame is cancelled or another band has failed.
 */
final class RawFrameRenderer implements AutoCloseable {

	/** Rows drawn per task: small enough to share the work, large enough to keep tasks cheap. */
	private static final int BAND = 16;
	/** Bytes of scratch a band holds per screen column: four sums of int. */
	private static final int SCRATCH_PER_COLUMN = 16;

	/** Test seam: called by a band before it draws each row, with the row. */
	static volatile IntConsumer rowHookForTest;

	private final ExecutorService pool;
	private final int threads;

	RawFrameRenderer(int threads) {
		this.threads = Math.max(1, threads);
		this.pool = Executors.newFixedThreadPool(this.threads, Shutdown.daemonThreads("OPM-raw-frame"));
	}

	/** The scratch a frame {@code width} pixels wide holds while it is drawn, beside its image. */
	long scratchBytes(int width) {
		return (long) SCRATCH_PER_COLUMN * width * threads;
	}

	/**
	 * The frame, complete, or an exception - in every case only after every band that started has
	 * finished. Cancelled ({@link CancellationException}) once {@code cancelled} says so; a band that
	 * fails stops the others and its cause is thrown ({@link ExecutionException}); a renderer that is
	 * closed refuses the frame ({@link IllegalStateException}). An interrupt does not cut the wait
	 * short: it stops the bands, and is kept for the caller.
	 *
	 * @param images	: the blocking image of a file version; a strict frame is drawn from its lease instead
	 * 				  ({@link #render(RawFrameRequest, TiffRawVolumeStore.Ready, BooleanSupplier)})
	 */
	BufferedImage render(final RawFrameRequest r,
			BiFunction<RawAcquisition.FileKey, RawTiffLayout, RandomAccessibleInterval<UnsignedShortType>> images,
			final BooleanSupplier cancelled) throws ExecutionException {
		final List<RandomAccessible<UnsignedShortType>> onScreen = new ArrayList<RandomAccessible<UnsignedShortType>>();
		final List<int[]> bounds = new ArrayList<int[]>();
		for (RawFrameRequest.Layer l : r.layers) {
			RandomAccessibleInterval<UnsignedShortType> part = RawSampling.crop(images.apply(r.key, r.layout), l.spec);
			onScreen.add(RealViews.affine(RawSampling.interpolate(part, r.interpolation), r.sourceToScreen(l)));
			bounds.add(onScreenBounds(r, l));
		}
		return draw(r, (pixels, first, last, stop) -> drawRows(r, onScreen, bounds, pixels, first, last, stop), cancelled);
	}

	/**
	 * The frame of a lease's pinned cells, as {@link #render(RawFrameRequest, BiFunction, BooleanSupplier)} draws it from the
	 * lease's own image ({@link TiffRawVolumeStore.Ready#blocking}), value for value, but sampled by {@link PinnedLayer}
	 * straight from the cells' arrays (M2-Q-F2). The same cells are read and refused, the frame waits for its bands the same
	 * way, and it throws what that one throws.
	 */
	BufferedImage render(final RawFrameRequest r, TiffRawVolumeStore.Ready lease, final BooleanSupplier cancelled) throws ExecutionException {
		final List<PinnedLayer> layers = new ArrayList<PinnedLayer>();
		for (RawFrameRequest.Layer l : r.layers) layers.add(new PinnedLayer(r, l, lease.pixels(r.key, r.layout)));
		return draw(r, (pixels, first, last, stop) -> drawPinnedRows(r, layers, pixels, first, last, stop), cancelled);
	}

	/** Rows {@code first} to {@code last - 1} of a frame drawn into its pixels, stopping at a row once {@code stop} says so. */
	private interface Band {
		void draw(int[] pixels, int first, int last, BooleanSupplier stop);
	}

	private BufferedImage draw(final RawFrameRequest r, final Band band, final BooleanSupplier cancelled) throws ExecutionException {
		final BufferedImage image = new BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_ARGB);
		final int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		final AtomicBoolean abort = new AtomicBoolean();
		final BooleanSupplier stop = () -> abort.get() || cancelled.getAsBoolean();
		List<Future<?>> bands = new ArrayList<Future<?>>();
		Throwable failure = null;
		try {
			for (int y0 = 0; y0 < r.height; y0 += BAND) {
				final int first = y0, last = Math.min(r.height, y0 + BAND);
				bands.add(pool.submit(() -> {
					band.draw(pixels, first, last, stop);
					return null;
				}));
			}
		} catch (RejectedExecutionException closed) {
			failure = new IllegalStateException("The frame renderer is closed", closed);
			abort.set(true);
		}
		// wait for every band that was submitted, whatever happened: none may outlive this call
		boolean interrupted = false;
		for (Future<?> f : bands) {
			for (;;) {
				try {
					f.get();
					break;
				} catch (InterruptedException e) {
					interrupted = true;
					abort.set(true);
				} catch (ExecutionException e) {
					Throwable cause = e.getCause();
					if (failure == null && !(cause instanceof CancellationException)) failure = cause;
					abort.set(true);
					break;
				}
			}
		}
		if (interrupted) Thread.currentThread().interrupt();
		if (failure instanceof IllegalStateException && failure.getCause() instanceof RejectedExecutionException)
			throw (IllegalStateException) failure;
		if (failure != null) throw new ExecutionException(failure);
		if (abort.get() || cancelled.getAsBoolean()) throw new CancellationException(r + " cancelled");
		return image;
	}

	/**
	 * Where a layer is drawn: {@code {minX, minY, maxX, maxY}} in screen pixels, or null when the
	 * screen plane misses it. The part's voxel box, widened by the interpolation's reach, mapped to
	 * the screen and bounded - BigDataViewer's own culling rule.
	 */
	static int[] onScreenBounds(RawFrameRequest r, RawFrameRequest.Layer l) {
		long[] dims = r.layout.dimensions();
		double expand = r.interpolation == Interpolation.NEARESTNEIGHBOR ? 0.5 : 1.0;
		double[] min = { -expand, -expand, -expand };
		double[] max = { l.spec.cropWidth(dims[0]) - 1 + expand, dims[1] - 1 + expand, dims[2] - 1 + expand };
		FinalRealInterval box = r.sourceToScreen(l).estimateBounds(FinalRealInterval.wrap(min, max));
		boolean seen = box.realMax(0) >= 0 && box.realMin(0) <= r.width - 1 && box.realMax(1) >= 0 && box.realMin(1) <= r.height - 1
				&& box.realMax(2) >= 0 && box.realMin(2) <= 0;
		if (!seen) return null;
		return new int[] { (int) Math.floor(box.realMin(0)), (int) Math.floor(box.realMin(1)), (int) Math.ceil(box.realMax(0)),
				(int) Math.ceil(box.realMax(1)) };
	}

	private static void drawRows(RawFrameRequest r, List<RandomAccessible<UnsignedShortType>> onScreen, List<int[]> bounds,
			int[] pixels, int first, int last, BooleanSupplier stop) {
		int n = onScreen.size(), w = r.width;
		@SuppressWarnings("unchecked")
		RandomAccess<UnsignedShortType>[] access = new RandomAccess[n];
		@SuppressWarnings("unchecked")
		RealARGBColorConverter<UnsignedShortType>[] convert = new RealARGBColorConverter[n];
		for (int i = 0; i < n; i++) {
			access[i] = onScreen.get(i).randomAccess();
			RawFrameRequest.Layer l = r.layers.get(i);
			convert[i] = RealARGBColorConverter.create(new UnsignedShortType(), l.min, l.max);
			convert[i].setColor(new ARGBType(l.argb));
		}
		int[] alpha = new int[w], red = new int[w], green = new int[w], blue = new int[w];
		ARGBType argb = new ARGBType();
		long[] start = new long[3];
		for (int y = first; y < last; y++) {
			if (stop.getAsBoolean()) throw new CancellationException("stopped at row " + y);
			IntConsumer hook = rowHookForTest;
			if (hook != null) hook.accept(y);
			Arrays.fill(alpha, 0);
			Arrays.fill(red, 0);
			Arrays.fill(green, 0);
			Arrays.fill(blue, 0);
			for (int i = 0; i < n; i++) {
				int[] b = bounds.get(i);
				if (b == null || y < b[1] || y > b[3]) continue;
				int x0 = Math.max(0, b[0]), x1 = Math.min(w - 1, b[2]);
				if (x0 > x1) continue;
				start[0] = x0;
				start[1] = y;
				access[i].setPosition(start);
				for (int x = x0; x <= x1; x++) {
					convert[i].convert(access[i].get(), argb);
					int v = argb.get();
					alpha[x] += ARGBType.alpha(v);
					red[x] += ARGBType.red(v);
					green[x] += ARGBType.green(v);
					blue[x] += ARGBType.blue(v);
					access[i].fwd(0);
				}
			}
			// BigDataViewer's AccumulateProjectorARGB: summed per channel, clamped; nothing drawn is 0
			int row = y * w;
			for (int x = 0; x < w; x++)
				pixels[row + x] = ARGBType.rgba(Math.min(red[x], 255), Math.min(green[x], 255), Math.min(blue[x], 255),
						Math.min(alpha[x], 255));
		}
	}

	/** {@link #drawRows}, each layer sampled by its {@link PinnedLayer}: the same rows, boxes, sums and clamping. */
	private static void drawPinnedRows(RawFrameRequest r, List<PinnedLayer> layers, int[] pixels, int first, int last, BooleanSupplier stop) {
		int n = layers.size(), w = r.width;
		int[] alpha = new int[w], red = new int[w], green = new int[w], blue = new int[w];
		for (int y = first; y < last; y++) {
			if (stop.getAsBoolean()) throw new CancellationException("stopped at row " + y);
			IntConsumer hook = rowHookForTest;
			if (hook != null) hook.accept(y);
			Arrays.fill(alpha, 0);
			Arrays.fill(red, 0);
			Arrays.fill(green, 0);
			Arrays.fill(blue, 0);
			for (int i = 0; i < n; i++) {
				PinnedLayer l = layers.get(i);
				int[] b = l.box;
				if (b == null || y < b[1] || y > b[3]) continue;
				int x0 = Math.max(0, b[0]), x1 = Math.min(w - 1, b[2]);
				if (x0 > x1) continue;
				l.drawRow(y, x0, x1, alpha, red, green, blue);
			}
			int row = y * w;
			for (int x = 0; x < w; x++)
				pixels[row + x] = ARGBType.rgba(Math.min(red[x], 255), Math.min(green[x], 255), Math.min(blue[x], 255),
						Math.min(alpha[x], 255));
		}
	}

	/**
	 * One layer of a frame, sampled straight from a lease's pinned cells (M2-Q-F2).
	 *
	 * <p><b>Why not through ImgLib2.</b> Drawn through ImgLib2's cell images, every move of a sample into another cell - at
	 * least twice per trilinear sample, since every plane is a cell of its own - runs ImgLib2's cell switch, and its casts of
	 * the cell's access to {@code DataAccess} ({@code AbstractCellImg.update}) and to {@code ShortAccess}
	 * ({@code GenericShortType.updateContainer}) are compiled from type profiles shared by every user of ImgLib2 in the
	 * runtime. Once other access classes have passed through them - BigDataViewer's own drawing passes the store's lent and
	 * invalid accesses - the JIT compiles both as HotSpot's general interface check, whose one-entry cache the two casts then
	 * overwrite in turn on the same class from every render thread (JDK-8180450, fixed only in JDK 23). Such a runtime drew
	 * a world XY frame about five times slower and no faster with more threads, for as long as it ran. Nothing here depends
	 * on another caller's profile: it reads short arrays, in this one class.
	 *
	 * <p><b>What it computes.</b> The ImgLib2 path's arithmetic, step for step, so that every value is the same: the
	 * screen-to-part transform as {@code AffineRandomAccess} holds it, a row's positions accumulated as it moves them; the
	 * part's columns of the full-width cells, zero outside the part ({@link RawSampling}); ImgLib2's floor and its trilinear
	 * weights in their order, accumulated in {@code ClampingNLinearInterpolatorRealType}'s Gray code order, clamped to
	 * 0-65535 and rounded as {@code UnsignedShortType.setReal} rounds; or ImgLib2's nearest voxel; then
	 * {@code RealARGBColorConverter}'s colour. {@code RawFramePinnedSamplerTest} holds the two paths to the same pixels.
	 *
	 * <p><b>What it reads.</b> Exactly the cells the ImgLib2 path reads - each neighbour inside the part, none outside it - and
	 * through {@link TiffRawVolumeStore.Ready.Pixels}, which refuses them as the lease's image does. The cells a sample reads
	 * are asked for again whenever the sample moves to another camera row or plane, and in every screen row.
	 */
	private static final class PinnedLayer {
		private final TiffRawVolumeStore.Ready.Pixels source;
		/** Where the layer is drawn, as {@link #onScreenBounds}, or null. */
		final int[] box;
		private final boolean linear;
		/** The part's first column in the full camera width, its width, and the file's rows and planes. */
		private final int column0, partWidth, height, planes;
		/** Screen to part: the inverse of the layer's placement, which {@code RealViews.affine} gives its random access. */
		private final double m00, m01, m02, m03, m10, m11, m12, m13, m20, m21, m22, m23;
		/** {@code RealARGBColorConverter}'s state for the layer's range and colour, computed as it computes it. */
		private final double min, scaleR, scaleG, scaleB;
		private final int alpha, black;

		PinnedLayer(RawFrameRequest r, RawFrameRequest.Layer l, TiffRawVolumeStore.Ready.Pixels source) {
			this.source = source;
			this.box = onScreenBounds(r, l);
			this.linear = r.interpolation == Interpolation.NLINEAR;		// RawSampling.factory: anything else is nearest
			this.column0 = (int) l.spec.cropMin(source.width);
			this.partWidth = (int) l.spec.cropWidth(source.width);
			this.height = source.height;
			this.planes = source.planes;
			AffineTransform3D toPart = r.sourceToScreen(l).inverse();
			m00 = toPart.get(0, 0);
			m01 = toPart.get(0, 1);
			m02 = toPart.get(0, 2);
			m03 = toPart.get(0, 3);
			m10 = toPart.get(1, 0);
			m11 = toPart.get(1, 1);
			m12 = toPart.get(1, 2);
			m13 = toPart.get(1, 3);
			m20 = toPart.get(2, 0);
			m21 = toPart.get(2, 1);
			m22 = toPart.get(2, 2);
			m23 = toPart.get(2, 3);
			final double scale = 1.0 / (l.max - l.min);
			this.min = l.min;
			this.alpha = ARGBType.alpha(l.argb);
			this.scaleR = ARGBType.red(l.argb) * scale;
			this.scaleG = ARGBType.green(l.argb) * scale;
			this.scaleB = ARGBType.blue(l.argb) * scale;
			this.black = ARGBType.rgba(0, 0, 0, alpha);
		}

		/** Screen row {@code y}, columns {@code from} to {@code to}, added to the band's sums. */
		void drawRow(int y, int from, int to, int[] sumA, int[] sumR, int[] sumG, int[] sumB) {
			// where AffineRandomAccess.setPosition puts the row's first sample: the transform applied to (from, y, 0)
			final double s0 = from, s1 = y, s2 = 0;
			double px = s0 * m00 + s1 * m01 + s2 * m02 + m03;
			double py = s0 * m10 + s1 * m11 + s2 * m12 + m13;
			double pz = s0 * m20 + s1 * m21 + s2 * m22 + m23;
			if (linear) linearRow(px, py, pz, from, to, sumA, sumR, sumG, sumB);
			else nearestRow(px, py, pz, from, to, sumA, sumR, sumG, sumB);
		}

		private void linearRow(double px, double py, double pz, int from, int to, int[] sumA, int[] sumR, int[] sumG, int[] sumB) {
			long cy = Long.MIN_VALUE, cz = Long.MIN_VALUE;		// the camera row and plane whose cells the arrays below hold
			short[] a00 = null, a10 = null, a01 = null, a11 = null;		// rows y and y + 1 of planes z and z + 1
			int o00 = 0, o10 = 0, o01 = 0, o11 = 0;
			for (int x = from; x <= to; x++) {
				final long fx = floor(px), fy = floor(py), fz = floor(pz);
				final double wx = px - fx, wy = py - fy, wz = pz - fz;
				final double ix = 1.0d - wx, iy = 1.0d - wy, iz = 1.0d - wz;
				// AbstractNLinearInterpolator.fillWeights, from the last dimension down; weight i is corner i, bit 1 x, 2 y, 4 z
				double w0 = 1.0d, w1, w2, w3, w4, w5, w6, w7;
				w4 = w0 * wz;
				w0 *= iz;
				w2 = w0 * wy;
				w0 *= iy;
				w6 = w4 * wy;
				w4 *= iy;
				w1 = w0 * wx;
				w0 *= ix;
				w3 = w2 * wx;
				w2 *= ix;
				w5 = w4 * wx;
				w4 *= ix;
				w7 = w6 * wx;
				w6 *= ix;
				final double v0, v1, v2, v3, v4, v5, v6, v7;
				if (fx >= 0 && fx < partWidth - 1 && fy >= 0 && fy < height - 1 && fz >= 0 && fz < planes - 1) {
					if (fy != cy || fz != cz) {		// in the order the ImgLib2 path first reads them
						final int row = (int) fy, plane = (int) fz;
						a00 = source.cell(row, plane);
						o00 = source.rowStart(row);
						a10 = source.cell(row + 1, plane);
						o10 = source.rowStart(row + 1);
						a11 = source.cell(row + 1, plane + 1);
						o11 = o10;
						a01 = source.cell(row, plane + 1);
						o01 = o00;
						cy = fy;
						cz = fz;
					}
					final int c = column0 + (int) fx;
					v0 = a00[o00 + c] & 0xffff;
					v1 = a00[o00 + c + 1] & 0xffff;
					v2 = a10[o10 + c] & 0xffff;
					v3 = a10[o10 + c + 1] & 0xffff;
					v4 = a01[o01 + c] & 0xffff;
					v5 = a01[o01 + c + 1] & 0xffff;
					v6 = a11[o11 + c] & 0xffff;
					v7 = a11[o11 + c + 1] & 0xffff;
				} else {		// at the part's edge: a neighbour outside it is the zero extension, and nothing is read for it
					v0 = value(fx, fy, fz);
					v1 = value(fx + 1, fy, fz);
					v3 = value(fx + 1, fy + 1, fz);
					v2 = value(fx, fy + 1, fz);
					v6 = value(fx, fy + 1, fz + 1);
					v7 = value(fx + 1, fy + 1, fz + 1);
					v5 = value(fx + 1, fy, fz + 1);
					v4 = value(fx, fy, fz + 1);
				}
				// ClampingNLinearInterpolatorRealType.get: its Gray code order, then clamped to the type and rounded by setReal
				double acc = v0 * w0;
				acc += v1 * w1;
				acc += v3 * w3;
				acc += v2 * w2;
				acc += v6 * w6;
				acc += v7 * w7;
				acc += v5 * w5;
				acc += v4 * w4;
				final double clamped = Math.max(0.0, Math.min(65535.0, acc));
				add((int) (long) (clamped + 0.5d * Math.signum(clamped)), x, sumA, sumR, sumG, sumB);
				px += m00;		// AffineRandomAccess.fwd(0): its transform's first column, added
				py += m10;
				pz += m20;
			}
		}

		private void nearestRow(double px, double py, double pz, int from, int to, int[] sumA, int[] sumR, int[] sumG, int[] sumB) {
			long cy = Long.MIN_VALUE, cz = Long.MIN_VALUE;
			short[] cell = null;
			int start = 0;
			for (int x = from; x <= to; x++) {
				final long rx = round(px), ry = round(py), rz = round(pz);
				int value = 0;		// the zero extension, outside the part
				if (rx >= 0 && rx < partWidth && ry >= 0 && ry < height && rz >= 0 && rz < planes) {
					if (ry != cy || rz != cz) {
						cell = source.cell((int) ry, (int) rz);
						start = source.rowStart((int) ry);
						cy = ry;
						cz = rz;
					}
					value = cell[start + column0 + (int) rx] & 0xffff;
				}
				add(value, x, sumA, sumR, sumG, sumB);
				px += m00;
				py += m10;
				pz += m20;
			}
		}

		/** One voxel of the part, read from its cell; zero outside the part, where nothing is read. */
		private double value(long x, long y, long z) {
			if (x < 0 || x >= partWidth || y < 0 || y >= height || z < 0 || z >= planes) return 0;
			final int row = (int) y;
			return source.cell(row, (int) z)[source.rowStart(row) + column0 + (int) x] & 0xffff;
		}

		/** RealARGBColorConverter.convert of {@code value}, added to the band's sums at {@code x}. */
		private void add(int value, int x, int[] sumA, int[] sumR, int[] sumG, int[] sumB) {
			final double v = value - min;
			final int c;
			if (v < 0) c = black;
			else c = ARGBType.rgba(Math.min(255, (int) (scaleR * v + 0.5)), Math.min(255, (int) (scaleG * v + 0.5)),
					Math.min(255, (int) (scaleB * v + 0.5)), alpha);
			sumA[x] += ARGBType.alpha(c);
			sumR[x] += ARGBType.red(c);
			sumG[x] += ARGBType.green(c);
			sumB[x] += ARGBType.blue(c);
		}

		/** ImgLib2's {@code Floor.floor}. */
		private static long floor(double r) {
			return r < 0 ? (long) r - 1 : (long) r;
		}

		/** ImgLib2's {@code Round.round}. */
		private static long round(double r) {
			return r < 0 ? (long) (r - 0.5) : (long) (r + 0.5);
		}
	}

	int threads() {
		return threads;
	}

	/** Refuse new frames; a frame being drawn finishes or stops on its own. */
	@Override
	public void close() {
		pool.shutdown();
	}

	/** Whether every band has finished, within {@code millis}, once closed. */
	boolean awaitTermination(long millis) throws InterruptedException {
		return pool.awaitTermination(millis, TimeUnit.MILLISECONDS);
	}
}
