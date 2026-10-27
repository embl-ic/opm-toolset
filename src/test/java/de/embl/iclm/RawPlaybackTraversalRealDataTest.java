package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.junit.Assume;
import org.junit.Test;

import bdv.viewer.Interpolation;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * The M2 performance protocol (PLAN section 2; joint contracts C9.1, C9.2) over a real acquisition:
 * completed-frame playback through its time points as the view plays them - {@link RawPlayback}
 * asking for each frame through a strict player, one frame prefetched ahead, presented by a canvas
 * painting on the event thread - at 1600 x 1000, both camera halves of one file, safe trilinear,
 * in the XY, XZ and YZ planes. For each plane, with a store of its own: the first frame (startup),
 * a first pass over the rest at the target rate, and a replay, as fast as playback allows, of the
 * span the budget holds with its cells resident. Reported: median and 95th percentile latency and
 * interval, delivered fps, bytes read, the store's high-water marks, open files, the heap's and
 * direct buffers' high-water marks, and the process's threads and handles before and after.
 *
 * <p>The first pass starts with the store empty. The operating system may hold some of the files
 * in its own cache, which this test does not control: application-cache cold is not device cold.
 * Skipped unless the data are named:
 * <pre>
 * mvn test -Dtest=RawPlaybackTraversalRealDataTest -Dopm.test.traversal=E:\OPM\3_timelapse_0 -Dopm.test.jvm.args=-Xmx16g
 * </pre>
 * Optional: {@code -Dopm.traversal.planes=XY,XZ,YZ -Dopm.traversal.times=50 -Dopm.traversal.fps=10
 * -Dopm.traversal.replayFps=60 -Dopm.traversal.budgetMb=6000}. Like the other real-data tests it
 * asserts what every frame must be - presented, complete, read only from its lease, within the
 * budget, nothing left pinned, open or running - and nothing about time.
 *
 * <p>A plane is {@code XY}, {@code XZ} or {@code YZ}, as the toolbar's world actions orient it, or an
 * oblique camera {@code OBL-<axis><degrees>[-<axis><degrees>...]}: World XY turned about the screen's
 * own axes through the canvas centre, in the order given - {@code OBL-Y30} is 30 degrees about the
 * screen vertical, {@code OBL-X45-Y20} 45 degrees about the horizontal and then 20 about the
 * vertical (M2-Q, Q1). Each camera's viewer matrix is logged.
 *
 * <p>{@link #completedPlaybackLoopsOverARealAcquisition} is the loop durability run (M2-Q, Q3),
 * gated separately by {@code -Dopm.test.loop}.
 */
public class RawPlaybackTraversalRealDataTest {

	private static final int W = 1600, H = 1000;
	private static final long PHASE_TIMEOUT_MS = 15 * 60 * 1000;

	@Test
	public void completedPlaybackTraversesARealAcquisition() throws Exception {
		String data = System.getProperty("opm.test.traversal", "");
		Assume.assumeTrue("Set -Dopm.test.traversal", !data.isEmpty());
		File folder = new File(data);
		String[] planes = System.getProperty("opm.traversal.planes", "XY,XZ,YZ").split(",");
		int wanted = Integer.getInteger("opm.traversal.times", 50);
		double fps = Double.parseDouble(System.getProperty("opm.traversal.fps", "10"));
		double replayFps = Double.parseDouble(System.getProperty("opm.traversal.replayFps", "60"));
		long budget = Long.getLong("opm.traversal.budgetMb", 6000) * 1000000L;
		int cores = Runtime.getRuntime().availableProcessors();
		int ioThreads = Math.max(2, Math.min(8, cores / 2)), renderThreads = Math.max(1, Math.min(8, cores - 1));		// as the session

		Acquired a = acquire(folder, wanted);
		RawGeometry geometry = a.geometry;
		RawAcquisition acquisition = a.acquisition;
		List<RawAcquisition.TimeKey> times = a.times;
		int count = a.count;
		long discovery = a.discoveryNanos;
		RawTiffLayout layout = a.layout;
		long[] dims = a.dims;
		RawSourceSpec[] specs = a.specs;
		double[] range = a.range;

		System.out.printf(Locale.US, "java %s, %d cores, heap max %.0f MB; %s: %d x %d x %d, %d time points, discovered and checked in %.0f ms%n",
				System.getProperty("java.version"), cores, Runtime.getRuntime().maxMemory() / 1e6, folder.getName(), dims[0], dims[1], dims[2],
				count, discovery / 1e6);
		System.out.printf(Locale.US, "protocol: %d x %d, two halves, %s, budget %.0f MB, %d IO threads, %d render threads, first pass at %.0f fps, "
				+ "replay at %.0f fps%n", W, H, Interpolation.NLINEAR, budget / 1e6, ioThreads, renderThreads, fps, replayFps);
		System.out.println("frame\tplane\tphase\ttime\toutcome\tprefetched\tlease_MB\tprepare_ms\trender_ms\tpresent_ms\tasked_to_presented_ms");
		List<String> summaries = new ArrayList<String>();
		for (String name : planes) {
			final String plane = name.trim();
			AffineTransform3D view = camera(plane, RawFrameRenderBenchmark.xyView(geometry, dims, specs, W, H), W, H);
			System.out.printf(Locale.US, "camera %s: viewer %s%n", plane, Arrays.toString(view.getRowPackedCopy()));
			int threadsBefore = ManagementFactory.getThreadMXBean().getThreadCount();
			long handlesBefore = handles();
			Rig rig = new Rig(acquisition, times, geometry, specs, view, range, budget, TiffRawVolumeStore.cellBytes(layout), ioThreads,
					renderThreads);
			try {
				// startup and the first pass, from an empty store
				resetHeapPeaks();
				Phase first = new Phase(rig, plane, "first pass", 0, count);
				Frame startup = first.start();
				assertEquals(String.valueOf(startup), RawStrictPlayer.Outcome.PRESENTED, startup.outcome());
				first.play(fps, false, Integer.MAX_VALUE);
				assertEquals("every time point presented: " + first.note, count - 1, first.playback.stats().presented);
				assertEquals(0, first.playback.stats().skipped);
				first.checkTheRecordAgainstTheStatusLine();
				summaries.add(String.format(Locale.US, "%s\tstartup\t1\t\t\t\t%.0f\t\t\t\t\t%.1f", plane, startup.millis(), startup.leaseMB()));
				summaries.add(first.summary());

				// the replay: the last time points the budget holds, resident
				double frameBytes = first.meanLeaseBytes();
				int span = (int) Math.max(2, Math.min(count, Math.floor(0.9 * budget / frameBytes) - 1));
				Phase replay = new Phase(rig, plane, "replay of " + span, count - span, span);
				Frame again = replay.start();
				assertEquals(String.valueOf(again), RawStrictPlayer.Outcome.PRESENTED, again.outcome());
				replay.play(replayFps, true, Math.max(span - 1, 20));
				assertTrue("presented: " + replay.note, replay.playback.stats().presented >= Math.max(span - 1, 20));
				replay.checkTheRecordAgainstTheStatusLine();
				assertEquals("a replay within the budget reads nothing: " + replay.summary(), 0, replay.bytesRead());
				summaries.add(replay.summary());

				for (Frame f : rig.frames) assertEquals("every value a frame read was in its lease: " + f, 0, f.result == null ? 0 : f.result.coverageMisses);
				RawVolumeStore.CacheStats s = rig.store.stats();
				long[] prefetch = rig.player.prefetchCounts();
				summaries.add(String.format(Locale.US, "%s\tstore: %s; open files %d; prefetches started %d, used %d, dropped %d, released %d; "
						+ "heap peak %.0f MB, direct buffers %.0f MB", plane, s, s.openChannels, prefetch[0], prefetch[1], prefetch[2], prefetch[3],
						heapPeak() / 1e6, direct() / 1e6));
				assertTrue("within the budget: " + s, s.peakTotalBytes <= s.budgetBytes);
				assertTrue("open files bounded: " + s.openChannels, s.openChannels <= TiffRawVolumeStore.MAX_OPEN_FILES);
			} finally {
				rig.close();
			}
			RawVolumeStore.CacheStats closed = rig.store.stats();
			assertEquals("nothing left pinned", 0, closed.pinnedBytes);
			assertEquals("nothing left staged", 0, closed.stagedBytes);
			assertEquals("nothing left being read", 0, closed.inFlightBytes);
			int threadsAfter = ManagementFactory.getThreadMXBean().getThreadCount();
			for (long deadline = System.currentTimeMillis() + 5000; threadsAfter > threadsBefore && System.currentTimeMillis() < deadline;) {
				Thread.sleep(50);
				threadsAfter = ManagementFactory.getThreadMXBean().getThreadCount();
			}
			long handlesAfter = handles();
			summaries.add(String.format(Locale.US, "%s\tclosed: threads %d before, %d after; process handles %d before, %d after", plane,
					threadsBefore, threadsAfter, handlesBefore, handlesAfter));
			assertTrue("the player's and the store's threads ended: " + threadsBefore + " before, " + threadsAfter + " after",
					threadsAfter <= threadsBefore + 1);
		}
		System.out.println();
		System.out.println("plane\tphase\tframes\tskipped\tprefetched\tdelivered_fps\tlatency_p50_ms\tlatency_p95_ms\tinterval_p50_ms\t"
				+ "interval_p95_ms\tprepare_p50_ms\trender_p50_ms\tlease_MB\tread_MB\tstatus_p50_ms\tstatus_p95_ms\tstatus_window");
		for (String line : summaries) System.out.println(line);
	}

	/**
	 * Loop durability (M2-Q, Q3): completed playback looping over the acquisition in one plane for
	 * {@code wraps} wraps, the session's metadata checks running throughout. After each wrap, and an
	 * explicit garbage collection: the heap used, direct buffers, threads, process handles, open
	 * files, the store's resident and peak bytes, what the wrap read and evicted, and the size of
	 * playback's latency storage. Then a timed pause, and a close that must release all it owned.
	 * <pre>
	 * ... -Dopm.test.loop=E:\OPM\3_timelapse_0 -Dopm.loop.plane=XY -Dopm.loop.wraps=10 -Dopm.loop.budgetMb=600 -Dopm.loop.fps=30
	 * </pre>
	 * It asserts what must hold - every frame presented, the budget kept, telemetry storage flat,
	 * nothing owned after close - and reports growth rather than judging it.
	 */
	@Test
	public void completedPlaybackLoopsOverARealAcquisition() throws Exception {
		String data = System.getProperty("opm.test.loop", "");
		Assume.assumeTrue("Set -Dopm.test.loop", !data.isEmpty());
		String plane = System.getProperty("opm.loop.plane", "XY");
		int wraps = Integer.getInteger("opm.loop.wraps", 10);
		double fps = Double.parseDouble(System.getProperty("opm.loop.fps", "30"));
		long budget = Long.getLong("opm.loop.budgetMb", 6000) * 1000000L;
		int cores = Runtime.getRuntime().availableProcessors();
		int ioThreads = Math.max(2, Math.min(8, cores / 2)), renderThreads = Math.max(1, Math.min(8, cores - 1));
		Acquired a = acquire(new File(data), Integer.getInteger("opm.loop.times", 50));
		AffineTransform3D view = camera(plane, RawFrameRenderBenchmark.xyView(a.geometry, a.dims, a.specs, W, H), W, H);
		System.out.printf(Locale.US, "java %s, %d cores, heap max %.0f MB; %d time points; loop of %s: %d wraps at %.0f fps, budget %.0f MB, "
				+ "%d IO and %d render threads; viewer %s%n", System.getProperty("java.version"), cores, Runtime.getRuntime().maxMemory() / 1e6,
				a.count, plane, wraps, fps, budget / 1e6, ioThreads, renderThreads, Arrays.toString(view.getRowPackedCopy()));
		Field latencyStorage = RawPlayback.Stats.class.getDeclaredField("latencies");
		latencyStorage.setAccessible(true);
		int threadsBefore = ManagementFactory.getThreadMXBean().getThreadCount();
		long handlesBefore = handles();
		Rig rig = new Rig(a.acquisition, a.times, a.geometry, a.specs, view, a.range, budget, TiffRawVolumeStore.cellBytes(a.layout), ioThreads,
				renderThreads);
		Phase loop = new Phase(rig, plane, "loop", 0, a.count);
		AtomicBoolean checking = new AtomicBoolean(true);
		long[] checks = { 0 };
		// the session's file checker: the generation of the files being shown and prepared, once a second
		Thread checker = new Thread(() -> {
			while (checking.get()) {
				int t = Math.max(0, loop.lastAsked);
				for (int k = t; k <= Math.min(a.count - 1, t + 1); k++) {
					rig.store.checkGeneration(a.acquisition.entry(a.times.get(k), 1).key());
					checks[0]++;
				}
				try {
					Thread.sleep(OnlineDeskewSession.GENERATION_CHECK_MS);
				} catch (InterruptedException e) {
					return;
				}
			}
		}, "test-file-checker");
		checker.setDaemon(true);
		List<String> rows = new ArrayList<String>();
		try {
			Frame startup = loop.start();
			assertEquals(String.valueOf(startup), RawStrictPlayer.Outcome.PRESENTED, startup.outcome());
			checker.start();
			long[] previous = { rig.store.stats().bytesRead, rig.store.stats().evictions };
			long start = System.nanoTime();
			double[] last = { 0 };
			rows.add(sample(rig, loop, latencyStorage, 0, 0, previous, last));
			loop.playInBackground(fps, true);
			for (int w = 1; w <= wraps; w++) {
				long wanted = (long) w * a.count;
				long deadline = System.currentTimeMillis() + PHASE_TIMEOUT_MS;
				while (presented(loop) < wanted && playing(loop) && System.currentTimeMillis() < deadline) Thread.sleep(20);
				assertTrue("wrap " + w + " ended in time and playing: " + loop.note, presented(loop) >= wanted);
				rows.add(sample(rig, loop, latencyStorage, w, (System.nanoTime() - start) / 1e9, previous, last));
			}
			// pause, as the button does, and see that it holds
			long before = presented(loop);
			long t0 = System.nanoTime();
			SwingUtilities.invokeAndWait(() -> loop.playback.pause());
			double pauseMillis = (System.nanoTime() - t0) / 1e6;
			Thread.sleep((long) (3000 / fps) + 500);
			long after = presented(loop);
			rows.add(String.format(Locale.US, "pause: returned in %.2f ms; %d presented before, %d after three periods; checker ran %d checks",
					pauseMillis, before, after, checks[0]));
			assertTrue("at most the frame in flight arrives after the pause", after <= before + 1);
			RawPlayback.Stats s = playbackStats(loop);
			assertEquals("none skipped", 0, s.skipped);
			assertEquals("latency storage stays at its window", RawPlayback.Stats.LATENCY_WINDOW, ((long[]) latencyStorage.get(s)).length);
			assertTrue("within the budget: " + rig.store.stats(), rig.store.stats().peakTotalBytes <= budget);
			for (Frame f : rig.frames) assertEquals("every value a frame read was in its lease: " + f, 0, f.result == null ? 0 : f.result.coverageMisses);
		} finally {
			checking.set(false);
			checker.interrupt();
			checker.join(5000);
			rig.close();
		}
		RawVolumeStore.CacheStats closed = rig.store.stats();
		int threadsAfter = ManagementFactory.getThreadMXBean().getThreadCount();
		for (long deadline = System.currentTimeMillis() + 5000; threadsAfter > threadsBefore && System.currentTimeMillis() < deadline;) {
			Thread.sleep(50);
			threadsAfter = ManagementFactory.getThreadMXBean().getThreadCount();
		}
		rows.add(String.format(Locale.US, "closed: pinned %d, staged %d, in flight %d bytes, open files %d; threads %d before, %d after; "
				+ "process handles %d before, %d after", closed.pinnedBytes, closed.stagedBytes, closed.inFlightBytes, closed.openChannels,
				threadsBefore, threadsAfter, handlesBefore, handles()));
		System.out.println();
		System.out.println("wrap\tpresented\tseconds\twrap_fps\tread_MB\tevictions\tresident_MB\tpeak_MB\theap_after_gc_MB\tdirect_MB\tthreads\t"
				+ "handles\topen_files\tlatency_storage\tprefetch_used");
		for (String row : rows) System.out.println(row);
		assertEquals("nothing left pinned", 0, closed.pinnedBytes);
		assertEquals("nothing left staged", 0, closed.stagedBytes);
		assertEquals("nothing left being read", 0, closed.inFlightBytes);
		assertTrue("the player's and the store's threads ended", threadsAfter <= threadsBefore + 1);
	}

	private static long presented(Phase p) throws Exception {
		long[] n = new long[1];
		SwingUtilities.invokeAndWait(() -> n[0] = p.playback.stats().presented);
		return n[0];
	}

	private static boolean playing(Phase p) throws Exception {
		boolean[] b = new boolean[1];
		SwingUtilities.invokeAndWait(() -> b[0] = p.playback.isPlaying());
		return b[0];
	}

	private static RawPlayback.Stats playbackStats(Phase p) throws Exception {
		RawPlayback.Stats[] s = new RawPlayback.Stats[1];
		SwingUtilities.invokeAndWait(() -> s[0] = p.playback.stats());
		return s[0];
	}

	/** One row of the loop table, after an explicit collection; {@code previous} carries the store's read and eviction counts on. */
	private static String sample(Rig rig, Phase loop, Field latencyStorage, int wrap, double seconds, long[] previous, double[] lastSeconds)
			throws Exception {
		System.gc();
		System.gc();
		Thread.sleep(200);
		long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
		RawVolumeStore.CacheStats s = rig.store.stats();
		RawPlayback.Stats p = playbackStats(loop);
		long[] prefetch = rig.player.prefetchCounts();
		double wrapFps = wrap == 0 ? 0 : loop.count / Math.max(1e-9, seconds - lastSeconds[0]);
		String row = String.format(Locale.US, "%d\t%d\t%.1f\t%.1f\t%.0f\t%d\t%.0f\t%.0f\t%.0f\t%.1f\t%d\t%d\t%d\t%d\t%d", wrap, p.presented, seconds,
				wrapFps, (s.bytesRead - previous[0]) / 1e6, s.evictions - previous[1], s.residentBytes / 1e6, s.peakTotalBytes / 1e6, heap / 1e6,
				direct() / 1e6, ManagementFactory.getThreadMXBean().getThreadCount(), handles(), s.openChannels,
				((long[]) latencyStorage.get(p)).length, prefetch[1]);
		previous[0] = s.bytesRead;
		previous[1] = s.evictions;
		lastSeconds[0] = seconds;
		System.out.println("loop sample: " + row);
		return row;
	}

	/* ---------------------------------------------------------------- the acquisition and the cameras */

	/** What a run reads before it plays: the time points checked, the two halves' specs, a display range. */
	static final class Acquired {
		RawGeometry geometry;
		RawAcquisition acquisition;
		List<RawAcquisition.TimeKey> times;
		int count;
		long discoveryNanos;
		RawTiffLayout layout;
		long[] dims;
		RawSourceSpec[] specs;
		double[] range;
	}

	static Acquired acquire(File folder, int wanted) throws Exception {
		Acquired a = new Acquired();
		long discovery = System.nanoTime();
		a.geometry = RawGeometry.fromExperimentalParameters(new File(folder, "ExperimentalParameters.txt"));
		a.acquisition = RawAcquisition.scan(folder);
		a.times = a.acquisition.times(1, 1);
		a.count = Math.min(wanted, a.times.size());
		for (int t = 0; t < a.count; t++) {
			RawAcquisition.Entry e = a.acquisition.entry(a.times.get(t), 1);
			assertEquals(e.detail(), RawAcquisition.Readiness.COMPLETE, e.validate());
		}
		a.discoveryNanos = System.nanoTime() - discovery;
		a.layout = a.acquisition.entry(a.times.get(0), 1).layout();
		a.dims = a.layout.dimensions();
		a.specs = new RawSourceSpec[] { RawSourceSpec.half(null, 1, true, false, a.layout.width, 0x00ff00),
				RawSourceSpec.half(null, 1, false, false, a.layout.width, 0xff00ff) };
		TiffRawVolumeStore ranging = new TiffRawVolumeStore(1L << 30, TiffRawVolumeStore.cellBytes(a.layout), 4, null);
		a.range = OnlineDeskewSession.displayRange(RawSampling.crop(ranging.blocking(a.acquisition.entry(a.times.get(0), 1).key(), a.layout),
				a.specs[0]));
		ranging.closeAsync().get(30, TimeUnit.SECONDS);
		SwingUtilities.invokeAndWait(() -> new javax.swing.Timer(1, e -> {}).start());		// the event thread and the timer queue, before counting threads
		return a;
	}

	/**
	 * A camera by name, as the class comment describes: a world plane, or World XY turned about the
	 * screen's axes through the canvas centre.
	 */
	static AffineTransform3D camera(String name, AffineTransform3D xy, int w, int h) {
		if (!name.startsWith("OBL")) return RawWorldView.valueOf(name).orient(xy, w, h);
		AffineTransform3D view = RawWorldView.XY.orient(xy, w, h);
		for (String turn : name.substring(3).split("-")) {
			if (turn.isEmpty()) continue;
			int axis = "XYZ".indexOf(Character.toUpperCase(turn.charAt(0)));
			if (axis < 0) throw new IllegalArgumentException("No screen axis in " + name);
			view.translate(-0.5 * w, -0.5 * h, 0);
			view.rotate(axis, Math.toRadians(Double.parseDouble(turn.substring(1))));
			view.translate(0.5 * w, 0.5 * h, 0);
		}
		return view;
	}

	/* ---------------------------------------------------------------- the view, without BigDataViewer */

	/**
	 * A store, a strict player and a canvas painting on the event thread, as the session builds them.
	 * The drawing probe (M2-Q, Q4) replays the window's own requests through one.
	 */
	static final class Rig {
		final RawAcquisition acquisition;
		final List<RawAcquisition.TimeKey> times;
		final RawGeometry geometry;
		final RawSourceSpec[] specs;
		final AffineTransform3D view;
		final double[] range;
		final TiffRawVolumeStore store;
		final RawPresenter presenter;
		final RawStrictPlayer player;
		final List<Frame> frames = new ArrayList<Frame>();		// on the event thread
		final AtomicBoolean painting = new AtomicBoolean(true);
		final Thread painter;
		long revision;

		final int width, height;

		Rig(RawAcquisition acquisition, List<RawAcquisition.TimeKey> times, RawGeometry geometry, RawSourceSpec[] specs, AffineTransform3D view,
				double[] range, long budget, long cell, int ioThreads, int renderThreads) {
			this(acquisition, times, geometry, specs, view, range, budget, cell, ioThreads, renderThreads, W, H);
		}

		/** A rig whose canvas is {@code width x height}: a frame is presented only on a canvas of its own size. */
		Rig(RawAcquisition acquisition, List<RawAcquisition.TimeKey> times, RawGeometry geometry, RawSourceSpec[] specs, AffineTransform3D view,
				double[] range, long budget, long cell, int ioThreads, int renderThreads, int width, int height) {
			this.width = width;
			this.height = height;
			this.acquisition = acquisition;
			this.times = times;
			this.geometry = geometry;
			this.specs = specs;
			this.view = view;
			this.range = range;
			this.store = new TiffRawVolumeStore(budget, cell, ioThreads, null);
			JPanel canvas = new JPanel();
			canvas.setSize(width, height);
			this.presenter = new RawPresenter(canvas);
			this.player = new RawStrictPlayer(store, new RawFrameRenderer(renderThreads), presenter);
			final BufferedImage screen = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			// the canvas paints as Swing would, on the event thread, every few milliseconds
			this.painter = new Thread(() -> {
				while (painting.get()) {
					try {
						SwingUtilities.invokeAndWait(() -> {
							Graphics2D g = screen.createGraphics();
							presenter.drawOverlays(g);
							g.dispose();
						});
						Thread.sleep(4);
					} catch (Exception e) {
						return;
					}
				}
			}, "test-painter");
			painter.setDaemon(true);
			painter.start();
		}

		RawFrameRequest request(long revision, int t) {
			RawAcquisition.Entry e = acquisition.entry(times.get(t), 1);
			List<RawFrameRequest.Layer> layers = new ArrayList<RawFrameRequest.Layer>();
			for (RawSourceSpec spec : specs)
				layers.add(new RawFrameRequest.Layer(spec, geometry.sourceToWorld(e.layout().dimensions(), spec, null), range[0], range[1],
						0xff000000 | spec.rgb));
			return new RawFrameRequest(revision, t, e, view, width, height, Interpolation.NLINEAR, layers);
		}

		void close() throws Exception {
			painting.set(false);
			painter.join(5000);
			player.close();
			assertTrue("the player stopped", player.awaitStopped(30000));
			store.closeAsync().get(30, TimeUnit.SECONDS);
		}
	}

	/** One frame as playback saw it end. */
	private static final class Frame {
		final String plane, phase;
		final int time;
		final long asked, ended;
		final RawStrictPlayer.Result result;
		final Throwable failure;

		Frame(String plane, String phase, int time, long asked, long ended, RawStrictPlayer.Result result, Throwable failure) {
			this.plane = plane;
			this.phase = phase;
			this.time = time;
			this.asked = asked;
			this.ended = ended;
			this.result = result;
			this.failure = failure;
		}

		RawStrictPlayer.Outcome outcome() {
			return result == null ? null : result.outcome;
		}

		boolean presented() {
			return outcome() == RawStrictPlayer.Outcome.PRESENTED;
		}

		double millis() {
			return (ended - asked) / 1e6;
		}

		double leaseMB() {
			return result == null ? 0 : result.leaseBytes / 1e6;
		}

		@Override public String toString() {
			return time + ": " + (result != null ? result : failure);
		}
	}

	/** Playback over {@code count} time points from {@code offset}, as the view plays them; on the event thread. */
	private static final class Phase implements RawPlayback.Host {
		final Rig rig;
		final String plane, name;
		final int offset, count;
		final RawPlayback playback;
		final CountDownLatch firstEnded = new CountDownLatch(1);
		final List<Frame> frames = new ArrayList<Frame>();
		int requested, stopAfter = Integer.MAX_VALUE;
		/** The time point asked for last, for the file checker's thread; -1 before any. */
		volatile int lastAsked = -1;
		volatile String note = "";
		long bytesAtStart, bytesAtEnd;

		Phase(Rig rig, String plane, String name, int offset, int count) {
			this.rig = rig;
			this.plane = plane;
			this.name = name;
			this.offset = offset;
			this.count = count;
			this.playback = new RawPlayback(this, RawPlayback.SWING, System::nanoTime);
		}

		/** Enter completed mode: the first time point, shown complete; returns when it has ended. */
		Frame start() throws Exception {
			bytesAtStart = rig.store.stats().bytesRead;
			SwingUtilities.invokeAndWait(() -> playback.setMode(RawPlayback.Mode.COMPLETED));
			assertTrue("the first frame ended", firstEnded.await(PHASE_TIMEOUT_MS, TimeUnit.MILLISECONDS));
			Frame[] first = new Frame[1];
			SwingUtilities.invokeAndWait(() -> first[0] = frames.get(0));
			return first[0];
		}

		/** Play on from the first time point until the end, or until {@code presented} frames were presented. */
		void play(double fps, boolean loop, int presented) throws Exception {
			SwingUtilities.invokeAndWait(() -> {
				stopAfter = presented;
				playback.setLoop(loop);
				playback.setTargetFps(fps);
				playback.play();
			});
			boolean[] playing = { true };
			long deadline = System.currentTimeMillis() + PHASE_TIMEOUT_MS;
			while (playing[0] && System.currentTimeMillis() < deadline) {
				Thread.sleep(50);
				SwingUtilities.invokeAndWait(() -> playing[0] = playback.isPlaying());
			}
			assertTrue("the phase ended in time: " + note, !playing[0]);
			bytesAtEnd = rig.store.stats().bytesRead;
			SwingUtilities.invokeAndWait(() -> {
				for (int i = 0; i < frames.size(); i++) {
					Frame f = frames.get(i);
					System.out.printf(Locale.US, "%s\t%s\t%s\t%d\t%s\t%s\t%.1f\t%.1f\t%.1f\t%.1f\t%.1f%n", i == 0 ? "startup" : String.valueOf(i),
							plane, name, f.time + 1, f.outcome(), f.result != null && f.result.prefetched, f.leaseMB(),
							f.result == null ? 0 : f.result.prepareNanos / 1e6, f.result == null ? 0 : f.result.renderNanos / 1e6,
							f.result == null ? 0 : f.result.presentNanos / 1e6, f.millis());
				}
			});
		}

		/** Start playing from the time shown and return at once; the caller decides when to pause. */
		void playInBackground(double fps, boolean loop) throws Exception {
			SwingUtilities.invokeAndWait(() -> {
				playback.setLoop(loop);
				playback.setTargetFps(fps);
				playback.play();
			});
		}

		/** Bytes the store read from entering the mode to the end of the phase. */
		long bytesRead() {
			return bytesAtEnd - bytesAtStart;
		}

		/** The played frames: all but the first, which entering the mode showed. */
		private List<Frame> played() {
			List<Frame> played = new ArrayList<Frame>();
			for (int i = 1; i < frames.size(); i++) if (frames.get(i).presented()) played.add(frames.get(i));
			return played;
		}

		double meanLeaseBytes() {
			List<Frame> played = played();
			double sum = 0;
			for (Frame f : played) sum += f.result.leaseBytes;
			return played.isEmpty() ? 1 : sum / played.size();
		}

		/** Each played frame's latency from this harness's own record, asked for to presented, in ms: the table's last column. */
		double[] latencies() {
			List<Frame> played = played();
			double[] latency = new double[played.size()];
			for (int i = 0; i < latency.length; i++) latency[i] = played.get(i).millis();
			return latency;
		}

		/**
		 * The phase's line. Its latency percentiles are exact, from this harness's own per-frame record;
		 * the status line's, which cover the last {@link RawPlayback.Stats#LATENCY_WINDOW} frames only,
		 * follow for comparison.
		 */
		String summary() {
			List<Frame> played = played();
			int n = played.size();
			double[] intervals = new double[Math.max(0, n - 1)], prepare = new double[n], render = new double[n];
			for (int i = 0; i < n; i++) {
				if (i > 0) intervals[i - 1] = (played.get(i).ended - played.get(i - 1).ended) / 1e6;
				prepare[i] = played.get(i).result.prepareNanos / 1e6;
				render[i] = played.get(i).result.renderNanos / 1e6;
			}
			double[] latency = latencies();
			RawPlayback.Stats s = playback.stats();
			return String.format(Locale.US, "%s\t%s\t%d\t%d\t%d\t%.1f\t%.0f\t%.0f\t%.0f\t%.0f\t%.0f\t%.0f\t%.1f\t%.0f\t%.0f\t%.0f\t%d", plane, name,
					s.presented, s.skipped, s.prefetched, s.deliveredFps(), quantile(latency, 0.5), quantile(latency, 0.95), quantile(intervals, 0.5),
					quantile(intervals, 0.95), quantile(prepare, 0.5), quantile(render, 0.5), meanLeaseBytes() / 1e6, bytesRead() / 1e6,
					s.latencyMillis(0.5), s.latencyMillis(0.95), s.latencySamples());
		}

		/**
		 * While the run fits the status line's window, its percentiles and this record's are of the
		 * same frames, a few microseconds apart: the two clocks are read at the two ends of one call.
		 */
		void checkTheRecordAgainstTheStatusLine() {
			double[] latency = latencies();
			RawPlayback.Stats s = playback.stats();
			assertEquals("the record holds every presented frame", s.presented, latency.length);
			if (latency.length > RawPlayback.Stats.LATENCY_WINDOW) return;
			assertEquals("p50", quantile(latency, 0.5), s.latencyMillis(0.5), 1.0);
			assertEquals("p95", quantile(latency, 0.95), s.latencyMillis(0.95), 1.0);
		}

		@Override public int timeCount() { return count; }
		@Override public int currentTime() { return requested; }
		@Override public int requestedTime() { return requested; }
		@Override public void showTime(int t) { throw new UnsupportedOperationException("completed frames only"); }

		@Override public CompletableFuture<RawStrictPlayer.Result> requestFrame(final int t) {
			requested = t;
			lastAsked = offset + t;
			final long asked = System.nanoTime();
			CompletableFuture<RawStrictPlayer.Result> f = rig.player.request(rig.request(++rig.revision, offset + t));
			f.whenComplete((r, e) -> SwingUtilities.invokeLater(() -> ended(t, asked, r, e)));
			return f;
		}

		private void ended(int t, long asked, RawStrictPlayer.Result r, Throwable e) {
			Frame f = new Frame(plane, name, offset + t, asked, System.nanoTime(), r, e);
			frames.add(f);
			rig.frames.add(f);
			playback.navigationEnded(t, r, e);
			if (playback.isPlaying() && playback.stats().presented >= stopAfter) playback.pause();
			firstEnded.countDown();
		}

		@Override public void prefetch(int t) { rig.player.prefetch(rig.request(0, offset + t)); }
		@Override public boolean startCompleted() { return true; }
		@Override public void stopCompleted() {}
		@Override public long placeholdersHandedOut() { return 0; }
		@Override public double presentedFps() { return rig.presenter.fps(); }
		@Override public void note(String text) { note = text; }
	}

	/* ---------------------------------------------------------------- measurements */

	/** Nearest rank, as {@link RawPlayback.Stats#latencyMillis}. */
	static double quantile(double[] values, double q) {
		if (values.length == 0) return 0;
		double[] sorted = values.clone();
		Arrays.sort(sorted);
		return sorted[Math.max(0, Math.min(sorted.length - 1, (int) Math.ceil(q * sorted.length) - 1))];
	}

	private static void resetHeapPeaks() {
		for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) if (pool.getType() == MemoryType.HEAP) pool.resetPeakUsage();
	}

	/** The heap pools' peaks summed: an upper bound of what the heap held, garbage included. */
	private static long heapPeak() {
		long sum = 0;
		for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) if (pool.getType() == MemoryType.HEAP) sum += pool.getPeakUsage().getUsed();
		return sum;
	}

	static long direct() {
		for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class))
			if ("direct".equals(pool.getName())) return pool.getMemoryUsed();
		return -1;
	}

	/** The process's open handles on Windows, as the task manager counts them; -1 elsewhere. */
	static long handles() {
		if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return -1;
		String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
		try {
			Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command", "(Get-Process -Id " + pid + ").HandleCount")
					.redirectErrorStream(true).start();
			String line;
			try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
				line = in.readLine();
			}
			p.waitFor(30, TimeUnit.SECONDS);
			return Long.parseLong(line.trim());
		} catch (Exception e) {
			return -1;
		}
	}
}
