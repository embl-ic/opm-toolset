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
	 * @param images	: the blocking image of a file version - the lease's own, for a strict frame
	 */
	BufferedImage render(final RawFrameRequest r,
			BiFunction<RawAcquisition.FileKey, RawTiffLayout, RandomAccessibleInterval<UnsignedShortType>> images,
			final BooleanSupplier cancelled) throws ExecutionException {
		final BufferedImage image = new BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_ARGB);
		final int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		final List<RandomAccessible<UnsignedShortType>> onScreen = new ArrayList<RandomAccessible<UnsignedShortType>>();
		final List<int[]> bounds = new ArrayList<int[]>();
		for (RawFrameRequest.Layer l : r.layers) {
			RandomAccessibleInterval<UnsignedShortType> part = RawSampling.crop(images.apply(r.key, r.layout), l.spec);
			onScreen.add(RealViews.affine(RawSampling.interpolate(part, r.interpolation), r.sourceToScreen(l)));
			bounds.add(onScreenBounds(r, l));
		}
		final AtomicBoolean abort = new AtomicBoolean();
		final BooleanSupplier stop = () -> abort.get() || cancelled.getAsBoolean();
		List<Future<?>> bands = new ArrayList<Future<?>>();
		Throwable failure = null;
		try {
			for (int y0 = 0; y0 < r.height; y0 += BAND) {
				final int first = y0, last = Math.min(r.height, y0 + BAND);
				bands.add(pool.submit(() -> {
					drawRows(r, onScreen, bounds, pixels, first, last, stop);
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
