package de.embl.iclm;

import java.awt.Color;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntPredicate;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import bdv.cache.SharedQueue;
import bdv.util.BdvFunctions;
import bdv.util.BdvHandle;
import bdv.util.BdvHandleFrame;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.TimePointListener;
import bdv.viewer.ViewerFrame;
import bdv.viewer.ViewerPanel;
import ij.IJ;
import ij.WindowManager;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.display.RealARGBColorConverter;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.volatiles.VolatileUnsignedShortType;
import net.imglib2.view.Views;

/**
 * One open Online Deskew View: the BigDataViewer window and everything it uses (joint contract
 * C7.1). The session owns the catalogue, the two camera-half sources, the fetch queue, the raw
 * volume store, the status card, its timer and listeners, and the background check of the
 * folder's files.
 *
 * <p><b>Closing.</b> The session closes when its window is closed, by the user, by code or by
 * Fiji's quit. Fiji's quit disposes the window and so delivers {@code windowClosed}, never
 * {@code windowClosing}; BigDataViewer stops its renderer only on {@code windowClosing}. The
 * session therefore stops the viewer itself. The close is terminal and idempotent: timers and
 * listeners stop on the event thread, and the blocking part - stopping the renderer, shutting the
 * queue and draining the store's reads within a deadline - runs on a daemon thread, never on the
 * event thread (C7.2). {@link #closed()} reports how it went.
 */
final class OnlineDeskewSession {

	/** The session opened last; for tests and the smoke test, not for program logic. */
	static volatile OnlineDeskewSession latest;

	private final OnlineDeskewView.Settings settings;
	private final RawAcquisition acquisition;
	private final List<RawAcquisition.TimeKey> times;
	private final RawGeometry geometry;
	private final RawSourceSpec[] specs;
	private final SharedQueue queue;
	private final TiffRawVolumeStore store;
	private final int firstIndex;
	private final AtomicBoolean closing = new AtomicBoolean();
	private final CompletableFuture<RawVolumeStore.CloseResult> closed = new CompletableFuture<RawVolumeStore.CloseResult>();

	private BdvHandle handle;
	private ViewerFrame frame;
	private JTextArea status;
	private Timer statusTimer;
	private TimePointListener timeListener;

	private OnlineDeskewSession(OnlineDeskewView.Settings settings, RawAcquisition acquisition,
			List<RawAcquisition.TimeKey> times, RawGeometry geometry, RawSourceSpec[] specs, int firstIndex,
			RawTiffLayout layout) {
		this.settings = settings;
		this.acquisition = acquisition;
		this.times = times;
		this.geometry = geometry;
		this.specs = specs;
		this.firstIndex = firstIndex;
		int cores = Runtime.getRuntime().availableProcessors();
		this.queue = new SharedQueue(Math.max(1, Math.min(8, cores - 1)), 1);
		this.store = new TiffRawVolumeStore(settings.budgetBytes(), TiffRawVolumeStore.cellBytes(layout),
				Math.max(2, Math.min(8, cores / 2)), queue);
	}

	/**
	 * Scan the folder, resolve the geometry, check the first time point, and open the view.
	 *
	 * @throws IOException				when the folder or a file cannot be read
	 * @throws IllegalArgumentException	when the folder holds nothing this view can show
	 */
	static OnlineDeskewSession open(OnlineDeskewView.Settings s) throws Exception {
		File folder = s.folder();
		if (!folder.isDirectory()) throw new IllegalArgumentException("Not a folder: " + folder);
		IJ.showStatus(OnlineDeskewView.MENU + ": reading " + folder.getName());
		RawAcquisition acquisition = RawAcquisition.scan(folder);
		if (acquisition.fileCount() == 0) throw new IllegalArgumentException("No TIFF files in " + folder);
		List<RawAcquisition.TimeKey> times = acquisition.times(s.position, s.channel);
		if (times.isEmpty())
			throw new IllegalArgumentException(String.format(Locale.US,
					"No files of position %d, acquisition channel %d in %s.%nPositions found: %s; channels found: %s",
					s.position, s.channel, folder, acquisition.positions(), acquisition.channels()));

		RawGeometry geometry;
		if (s.parametersFromFile()) {
			File parameters = new File(folder, "ExperimentalParameters.txt");
			if (!parameters.isFile())
				throw new IllegalArgumentException("No ExperimentalParameters.txt in " + folder
						+ "; choose \"manual\" and type the deskew parameters.");
			geometry = RawGeometry.fromExperimentalParameters(parameters);
		} else geometry = new RawGeometry(s.dz, s.dxy, s.angle, "manual");

		// the first time point that can be shown opens the view; the rest are checked behind it
		int first = -1;
		RawAcquisition.Entry entry = null;
		for (int i = 0; i < times.size() && first < 0; i++) {
			entry = acquisition.entry(times.get(i), s.channel);
			if (entry.validate() == RawAcquisition.Readiness.COMPLETE
					|| entry.readiness() == RawAcquisition.Readiness.DEPTH_UNVERIFIED) first = i;
		}
		if (first < 0)
			throw new IllegalArgumentException("No file of channel " + s.channel + " can be shown; the first one is "
					+ acquisition.entry(times.get(0), s.channel).detail());

		AlignmentMatrixSet alignments = null;
		if (!s.alignmentCsv.isEmpty()) {
			alignments = AlignmentMatrixSet.load(s.alignmentCsv);
			if (alignments == null) throw new IllegalArgumentException("Cannot read the alignment matrix " + s.alignmentCsv);
		}
		RawTiffLayout layout = entry.layout();
		RawSourceSpec[] specs = {
			RawSourceSpec.half(alignments, s.channel, true, s.flipLeft(), layout.width, 0x00ff00),
			RawSourceSpec.half(alignments, s.channel, false, s.flipLeft(), layout.width, 0xff00ff)
		};
		final OnlineDeskewSession session = new OnlineDeskewSession(s, acquisition, times, geometry, specs, first, layout);
		try {
			session.show();
		} catch (Exception e) {
			session.closeResources();
			throw e;
		}
		session.validateTheRest();
		latest = session;
		return session;
	}

	private void show() throws Exception {
		final RawAcquisition.Entry entry = acquisition.entry(times.get(firstIndex), settings.channel);
		final long[] referenceDims = entry.layout().dimensions();
		final double[][] ranges = new double[specs.length][];
		for (int i = 0; i < specs.length; i++)
			ranges[i] = displayRange(RawSampling.crop(store.blocking(entry.key(), entry.layout()), specs[i]));
		final Exception[] failure = new Exception[1];
		SwingUtilities.invokeAndWait(new Runnable() {
			@Override public void run() {
				try {
					showOnEventThread(referenceDims, ranges);
				} catch (Exception e) {
					failure[0] = e;
				}
			}
		});
		if (failure[0] != null) throw failure[0];
	}

	private void showOnEventThread(long[] referenceDims, double[][] ranges) {
		String title = OnlineDeskewView.MENU + " - " + settings.folder().getName();
		BdvStackSource<?> shown = null;
		for (int i = 0; i < specs.length; i++) {
			RawPartSource<UnsignedShortType> blocking = new RawPartSource<UnsignedShortType>(acquisition, times, specs[i],
					geometry, store::blocking, new UnsignedShortType(), referenceDims);
			RawPartSource<VolatileUnsignedShortType> volatileSource = new RawPartSource<VolatileUnsignedShortType>(
					acquisition, times, specs[i], geometry, store::volatileView, new VolatileUnsignedShortType(), referenceDims);
			SourceAndConverter<VolatileUnsignedShortType> volatileSoc = new SourceAndConverter<VolatileUnsignedShortType>(
					volatileSource, RealARGBColorConverter.create(new VolatileUnsignedShortType(), ranges[i][0], ranges[i][1]));
			SourceAndConverter<UnsignedShortType> soc = new SourceAndConverter<UnsignedShortType>(blocking,
					RealARGBColorConverter.create(new UnsignedShortType(), ranges[i][0], ranges[i][1]), volatileSoc);
			BdvOptions options = i == 0 ? BdvOptions.options().frameTitle(title) : BdvOptions.options().addTo(handle);
			shown = BdvFunctions.show(soc, times.size(), options);
			if (i == 0) handle = shown.getBdvHandle();
			shown.setColor(new ARGBType(0xff000000 | specs[i].rgb));
			shown.setDisplayRange(ranges[i][0], ranges[i][1]);
		}
		handle.getCacheControls().addCacheControl(queue);
		ViewerPanel viewer = handle.getViewerPanel();
		viewer.state().setInterpolation(settings.interpolation());
		viewer.setTimepoint(firstIndex);

		status = new JTextArea(9, 30);
		status.setEditable(false);
		status.setLineWrap(true);
		status.setWrapStyleWord(true);
		status.setOpaque(false);
		status.setForeground(Color.DARK_GRAY);
		handle.getCardPanel().addCard("opm-online-deskew", "Online Deskew", status, true);
		timeListener = new TimePointListener() {
			@Override public void timePointChanged(int timePointIndex) {
				updateStatus();
			}
		};
		viewer.addTimePointListener(timeListener);
		statusTimer = new Timer(1000, e -> updateStatus());
		statusTimer.start();
		updateStatus();

		frame = ((BdvHandleFrame) handle).getBigDataViewer().getViewerFrame();
		frame.addWindowListener(new WindowAdapter() {
			@Override public void windowClosed(WindowEvent e) {
				close();
			}
		});
		// listed in Fiji's Window menu, and disposed - hence closed - when Fiji quits
		WindowManager.addWindow(frame);
		SwingUtilities.invokeLater(this::fitToView);
	}

	/** Look down the deskewed Z at mid depth, the union of both halves filling the window. */
	private void fitToView() {
		if (closing.get()) return;
		ViewerPanel viewer = handle.getViewerPanel();
		RawAcquisition.Entry entry = acquisition.entry(times.get(firstIndex), settings.channel);
		long[] dims = entry.layout().dimensions();
		double[] min = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
		double[] max = { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
		double[] corner = new double[3], world = new double[3];
		for (RawSourceSpec spec : specs) {
			AffineTransform3D toWorld = geometry.sourceToWorld(dims, spec, null);
			long w = spec.cropWidth(dims[0]);
			for (int c = 0; c < 8; c++) {
				corner[0] = (c & 1) == 0 ? 0 : w - 1;
				corner[1] = (c & 2) == 0 ? 0 : dims[1] - 1;
				corner[2] = (c & 4) == 0 ? 0 : dims[2] - 1;
				toWorld.apply(corner, world);
				for (int d = 0; d < 3; d++) {
					min[d] = Math.min(min[d], world[d]);
					max[d] = Math.max(max[d], world[d]);
				}
			}
		}
		int width = Math.max(1, viewer.getDisplay().getWidth()), height = Math.max(1, viewer.getDisplay().getHeight());
		double scale = 0.95 * Math.min(width / (max[0] - min[0]), height / (max[1] - min[1]));
		AffineTransform3D view = new AffineTransform3D();
		view.translate(-0.5 * (min[0] + max[0]), -0.5 * (min[1] + max[1]), -0.5 * (min[2] + max[2]));
		view.scale(scale);
		view.translate(0.5 * width, 0.5 * height, 0);
		viewer.state().setViewerTransform(view);
	}

	/**
	 * Check the remaining time points on a daemon thread; redraw when the shown one is ready.
	 *
	 * <p>Checking a camera file walks its whole IFD chain, a few hundred milliseconds each, so a
	 * folder of fifty takes a while. The time point on screen is therefore asked for before every
	 * file: a jump on the slider waits for at most the one check already running, not for every
	 * file in front of it (found by the Fiji smoke test, where a jump to time point 11 stayed blank).
	 */
	private void validateTheRest() {
		Thread checker = Shutdown.daemon(new Runnable() {
			@Override public void run() {
				while (!closing.get()) {
					BdvHandle h = handle;
					int shown = h == null ? firstIndex : h.getViewerPanel().state().getCurrentTimepoint();
					int i = nextToCheck(shown, times.size(),
							k -> acquisition.entry(times.get(k), settings.channel).readiness() == RawAcquisition.Readiness.PENDING);
					if (i < 0) return;
					acquisition.entry(times.get(i), settings.channel).validate();
					h = handle;
					if (h != null && !closing.get() && h.getViewerPanel().state().getCurrentTimepoint() == i)
						h.getViewerPanel().requestRepaint();
				}
			}
		}, "OPM-online-deskew-check");
		checker.start();
	}

	/**
	 * The time point to check next: the shown one, then those after it - playback runs forward -
	 * then those before it. -1 when none is left.
	 */
	static int nextToCheck(int shown, int count, IntPredicate pending) {
		for (int k = 0; k < count; k++) {
			int i = (Math.max(0, shown) + k) % count;
			if (pending.test(i)) return i;
		}
		return -1;
	}

	private void updateStatus() {
		if (status == null || closing.get()) return;
		int t = handle.getViewerPanel().state().getCurrentTimepoint();
		RawAcquisition.Entry e = t >= 0 && t < times.size() ? acquisition.entry(times.get(t), settings.channel) : null;
		Map<RawAcquisition.Readiness, Integer> census = acquisition.census(times, settings.channel);
		StringBuilder text = new StringBuilder();
		text.append(settings.folder().getAbsolutePath()).append('\n');
		text.append("Deskew: ").append(geometry).append('\n');
		text.append(String.format(Locale.US, "Position %d, acquisition channel %d: %s (green), %s (magenta)%n",
				settings.position, settings.channel, specs[0], specs[1]));
		if (e != null)
			text.append(String.format(Locale.US, "%s - %d of %d - %s: %s%n", e.time, t + 1, times.size(),
					e.readiness().name().toLowerCase(Locale.US).replace('_', ' '),
					e.readiness() == RawAcquisition.Readiness.PENDING ? "being checked" : e.detail()));
		text.append(String.format(Locale.US, "Files: %d complete, %d depth unverified, %d incomplete, %d failed, %d not checked yet%n",
				census.get(RawAcquisition.Readiness.COMPLETE), census.get(RawAcquisition.Readiness.DEPTH_UNVERIFIED),
				census.get(RawAcquisition.Readiness.INCOMPLETE), census.get(RawAcquisition.Readiness.FAILED),
				census.get(RawAcquisition.Readiness.PENDING)));
		text.append("Cache: ").append(store.stats());
		status.setText(text.toString());
	}

	/**
	 * Close everything, once. Called on the event thread by {@code windowClosed}; the blocking part
	 * runs on a daemon thread.
	 */
	void close() {
		if (!closing.compareAndSet(false, true)) return;
		if (statusTimer != null) statusTimer.stop();
		if (handle != null && timeListener != null) handle.getViewerPanel().removeTimePointListener(timeListener);
		if (frame != null) WindowManager.removeWindow(frame);
		Thread finisher = Shutdown.daemon(new Runnable() {
			@Override public void run() {
				closeResources();
			}
		}, "OPM-online-deskew-close");
		finisher.start();
	}

	/** Stop the viewer, the queue and the store; wait for the store within its deadline. */
	private void closeResources() {
		closing.set(true);
		try {
			// BigDataViewer stops its renderer only on windowClosing, which Fiji's quit never sends
			if (handle != null) handle.getViewerPanel().stop();
		} catch (RuntimeException e) {
			System.out.println(OnlineDeskewView.MENU + ": stopping the viewer failed: " + e);
		}
		queue.shutdown();
		RawVolumeStore.CloseResult result;
		try {
			result = store.closeAsync().get(TiffRawVolumeStore.CLOSE_DEADLINE_MS + 2000, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			result = new RawVolumeStore.CloseResult(false, -1, store.stats().inFlight);
		}
		System.out.println(OnlineDeskewView.MENU + " " + settings.folder().getName() + ": " + result);
		closed.complete(result);
	}

	/** Display range from a middle plane of a part: the 0.1 and 99.95 percentiles. */
	static double[] displayRange(RandomAccessibleInterval<UnsignedShortType> part) {
		long z = part.min(2) + part.dimension(2) / 2;
		RandomAccessibleInterval<UnsignedShortType> plane = Views.hyperSlice(part, 2, z);
		int[] values = new int[(int) (plane.dimension(0) * plane.dimension(1))];
		int n = 0;
		Cursor<UnsignedShortType> c = Views.flatIterable(plane).cursor();
		while (c.hasNext()) values[n++] = c.next().get();
		Arrays.sort(values, 0, n);
		double low = values[(int) (0.001 * (n - 1))], high = values[(int) (0.9995 * (n - 1))];
		return new double[] { low, high > low ? high : low + 1 };
	}

	// for tests and the smoke test

	CompletableFuture<RawVolumeStore.CloseResult> closed() { return closed; }
	BdvHandle handle() { return handle; }
	ViewerFrame frame() { return frame; }
	RawVolumeStore store() { return store; }
	RawAcquisition acquisition() { return acquisition; }
	List<RawAcquisition.TimeKey> times() { return times; }
	RawSourceSpec[] specs() { return specs.clone(); }
	RawGeometry geometry() { return geometry; }
	boolean isClosing() { return closing.get(); }
	String statusText() { return status == null ? "" : status.getText(); }
}
