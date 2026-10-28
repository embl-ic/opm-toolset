package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;

import org.junit.Assume;
import org.junit.Test;

import bdv.viewer.Interpolation;
import net.imglib2.RandomAccess;
import net.imglib2.img.basictypeaccess.ShortAccess;
import net.imglib2.img.basictypeaccess.array.ShortArray;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileShortArray;
import net.imglib2.img.cell.Cell;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.img.cell.LazyCellImg;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedShortType;

/**
 * Window versus harness drawing (M2-Q, Q4; B's review §3-4). The window drew a frame several times
 * slower per data pixel than the traversal harness, at a different zoom, and no cause was measured.
 * This probe measures before anyone optimises:
 *
 * <ol>
 * <li>{@link #theWindowsFramesRedrawnWithoutTheWindow} - frames presented by a real Online Deskew View
 *     at its default size, each drawn again at the same view so its cells are resident, with the
 *     player's own render time; then the identical requests redrawn in the same runtime with the
 *     window closed, through the harness's store, player and canvas. The requests are written to a
 *     file ({@code -Dopm.drawing.capture=<file>}).</li>
 * <li>{@link #capturedRequestsRedrawnInAFreshRuntime} - the same requests read back and redrawn in a
 *     runtime that never had a window ({@code -Dopm.drawing.replay=<file>}).</li>
 * <li>{@link #drawingCostAcrossZoomPlanesAndCameras} - one time point at 1600 x 1000, in XY, XZ, YZ and
 *     an oblique camera, at 0.25, 0.5, 1 and 2 screen pixels per voxel ({@code -Dopm.drawing.sweep=true}).</li>
 * </ol>
 *
 * Each request is described by its geometry, computed from the request alone and outside the timed
 * code: the screen's zoom in pixels per voxel, the cells it reads, the screen pixels that sample a
 * voxel, and the estimated cell crossings per row - how often the sample walks from one raw cell
 * (one strip of one plane) into another along a screen row. Every timed draw is kept. Each request
 * is drawn once untimed to read its cells, then {@code -Dopm.drawing.repeats} times (default 5);
 * and the whole set is drawn in {@code -Dopm.drawing.passes} passes (default 2), because a runtime's
 * first draws are its JIT's warm-up: the comparisons use the last pass, the first is kept. Run
 * outside Maven, without JaCoCo, through the shared kit, gate {@code opm.test.drawing}. It asserts
 * only that every frame was presented complete from its lease.
 */
public class RawDrawingProbeRealDataTest {

	private static final int REPEATS = Integer.getInteger("opm.drawing.repeats", 5);
	private static final int PASSES = Integer.getInteger("opm.drawing.passes", 2);
	private static final long BUDGET = Long.getLong("opm.drawing.budgetMb", 6000) * 1000000L;

	private static <T> T edt(Callable<T> work) throws Exception {
		final Object[] value = new Object[1];
		final Exception[] failure = new Exception[1];
		SwingUtilities.invokeAndWait(() -> {
			try {
				value[0] = work.call();
			} catch (Exception e) {
				failure[0] = e;
			}
		});
		if (failure[0] != null) throw failure[0];
		@SuppressWarnings("unchecked") T t = (T) value[0];
		return t;
	}

	private static String data() {
		String data = System.getProperty("opm.test.drawing", "");
		Assume.assumeTrue("Set -Dopm.test.drawing", !data.isEmpty());
		return data;
	}

	private static int renderThreads() {
		return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));		// as the session
	}

	private static int ioThreads() {
		return Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
	}

	/* ---------------------------------------------------------------- 1. the window, then without it */

	@Test
	public void theWindowsFramesRedrawnWithoutTheWindow() throws Exception {
		String data = data();
		String capture = System.getProperty("opm.drawing.capture", "");
		Assume.assumeTrue("Set -Dopm.drawing.capture=<file>", !capture.isEmpty());
		String[] cameras = System.getProperty("opm.drawing.cameras", "XY,XZ,YZ,OBL-Y30").split(",");
		int times = Integer.getInteger("opm.drawing.times", 3);
		OnlineDeskewView.Settings settings = new OnlineDeskewView.Settings();
		settings.folder = data;
		settings.budgetMb = BUDGET / 1e6;
		OnlineDeskewSession session = OnlineDeskewSession.open(settings);
		List<RawFrameRequest> captured = new ArrayList<RawFrameRequest>();		// the last pass's
		List<String> cameraOf = new ArrayList<String>();
		List<double[][]> windowMillis = new ArrayList<double[][]>();		// per request: per pass, the warm draws
		try {
			for (int t = 0; t < times; t++) {
				RawAcquisition.Entry e = session.acquisition().entry(session.times().get(t), settings.channel);
				long deadline = System.nanoTime() + (long) 120e9;
				while (e.readiness() == RawAcquisition.Readiness.PENDING && System.nanoTime() < deadline) Thread.sleep(50);
				assertEquals(e.detail(), RawAcquisition.Readiness.COMPLETE, e.readiness());
			}
			edt(() -> {
				session.playback().setMode(RawPlayback.Mode.COMPLETED);
				return null;
			});
			assertEquals(RawStrictPlayer.Outcome.PRESENTED, session.navigationResult().get(120, TimeUnit.SECONDS).outcome);
			int[] canvas = edt(() -> new int[] { session.handle().getViewerPanel().getDisplayComponent().getWidth(),
					session.handle().getViewerPanel().getDisplayComponent().getHeight() });
			System.out.printf(Locale.US, "java %s; window canvas %d x %d; %d render threads; %d warm draws per request, %d passes%n",
					System.getProperty("java.version"), canvas[0], canvas[1], renderThreads(), REPEATS, PASSES);
			for (int pass = 0; pass < PASSES; pass++)
			for (int c = 0; c < cameras.length; c++) {
				final String camera = cameras[c].trim();
				CompletableFuture<RawStrictPlayer.Result> oriented = edt(() -> {
					session.worldAction(camera.startsWith("OBL") ? RawWorldView.XY : RawWorldView.valueOf(camera)).actionPerformed(null);
					return null;
				});
				SwingUtilities.invokeAndWait(() -> {});		// the world action's capture runs on the next turn
				if (camera.startsWith("OBL")) {
					edt(() -> {
						bdv.viewer.ViewerPanel v = session.handle().getViewerPanel();
						AffineTransform3D xy = v.state().getViewerTransform();
						AffineTransform3D turned = RawPlaybackTraversalRealDataTest.camera(camera, xy, canvas[0], canvas[1]);
						v.state().setViewerTransform(turned);
						return null;
					});
					SwingUtilities.invokeAndWait(() -> {});
				}
				oriented = edt(session::navigationResult);
				assertEquals(RawStrictPlayer.Outcome.PRESENTED, oriented.get(300, TimeUnit.SECONDS).outcome);
				for (int t = 0; t < times; t++) {
					final int time = t;
					RawStrictPlayer.Result first = edt(() -> session.requestStrictFrame(time)).get(300, TimeUnit.SECONDS);
					assertEquals(String.valueOf(first), RawStrictPlayer.Outcome.PRESENTED, first.outcome);
					double[] ms = new double[REPEATS];
					RawFrameRequest last = first.request;
					for (int k = 0; k < REPEATS; k++) {		// the same time and view again: its cells are resident
						RawStrictPlayer.Result again = edt(() -> session.requestStrictFrame(time)).get(300, TimeUnit.SECONDS);
						assertEquals(String.valueOf(again), RawStrictPlayer.Outcome.PRESENTED, again.outcome);
						assertEquals(0, again.coverageMisses);
						ms[k] = again.renderNanos / 1e6;
						last = again.request;
					}
					int index = c * times + t;
					if (pass == 0) {
						captured.add(last);
						cameraOf.add(camera);
						windowMillis.add(new double[PASSES][]);
					} else captured.set(index, last);
					windowMillis.get(index)[pass] = ms;
				}
			}
		} finally {
			SwingUtilities.invokeAndWait(() -> session.frame().dispose());
			assertTrue(session.closed().get(30, TimeUnit.SECONDS).drained);
		}
		write(new File(capture), cameraOf, captured);
		// the identical requests, in this runtime, the window gone
		List<double[][]> harnessMillis = redraw(captured, session.acquisition(), session.times(), session.geometry(), session.specs());
		int last = PASSES - 1;
		System.out.println();
		System.out.println("camera\ttime\t" + Metrics.HEADER + "\twindow_first_pass_ms\twindow_ms\twindow_min_ms\tharness_first_pass_ms\tharness_ms\t"
				+ "harness_min_ms\twindow_over_harness");
		for (int i = 0; i < captured.size(); i++) {
			double w = median(windowMillis.get(i)[last]), h = median(harnessMillis.get(i)[last]);
			System.out.printf(Locale.US, "%s\t%d\t%s\t%.1f\t%.1f\t%.1f\t%.1f\t%.1f\t%.1f\t%.2f%n", cameraOf.get(i), captured.get(i).timeIndex + 1,
					Metrics.of(captured.get(i)), median(windowMillis.get(i)[0]), w, min(windowMillis.get(i)[last]),
					median(harnessMillis.get(i)[0]), h, min(harnessMillis.get(i)[last]), w / h);
		}
		System.out.println("every draw, ms, pass by pass: window | harness");
		for (int i = 0; i < captured.size(); i++)
			System.out.println(cameraOf.get(i) + "\t" + (captured.get(i).timeIndex + 1) + "\t" + Arrays.deepToString(windowMillis.get(i)) + " | "
					+ Arrays.deepToString(harnessMillis.get(i)));
	}

	/* ---------------------------------------------------------------- 2. a fresh runtime */

	@Test
	public void capturedRequestsRedrawnInAFreshRuntime() throws Exception {
		String data = data();
		String replay = System.getProperty("opm.drawing.replay", "");
		Assume.assumeTrue("Set -Dopm.drawing.replay=<file>", !replay.isEmpty());
		RawPlaybackTraversalRealDataTest.Acquired a = RawPlaybackTraversalRealDataTest.acquire(new File(data),
				Integer.getInteger("opm.drawing.times", 6));
		List<String> cameraOf = new ArrayList<String>();
		List<RawFrameRequest> requests = read(new File(replay), a, cameraOf);
		System.out.printf(Locale.US, "java %s; %d requests read back; %d render threads; %d warm draws per request%n",
				System.getProperty("java.version"), requests.size(), renderThreads(), REPEATS);
		List<double[][]> millis = redraw(requests, a.acquisition, a.times, a.geometry, a.specs);
		int last = PASSES - 1;
		System.out.println();
		System.out.println("camera\ttime\t" + Metrics.HEADER + "\tfresh_first_pass_ms\tfresh_ms\tfresh_min_ms\tevery_draw_ms_by_pass");
		for (int i = 0; i < requests.size(); i++)
			System.out.printf(Locale.US, "%s\t%d\t%s\t%.1f\t%.1f\t%.1f\t%s%n", cameraOf.get(i), requests.get(i).timeIndex + 1, Metrics.of(requests.get(i)),
					median(millis.get(i)[0]), median(millis.get(i)[last]), min(millis.get(i)[last]), Arrays.deepToString(millis.get(i)));
	}

	/* ---------------------------------------------------------------- 2b. before and after BigDataViewer drew */

	/**
	 * What in the window's runtime made the same draws slower: the captured requests drawn in a fresh
	 * runtime; drawn again, as a control for drift; then an Online Deskew View opened in this runtime
	 * and left to BigDataViewer's own progressive drawing in World XY, XZ and YZ - no completed frame
	 * is made - and closed; then the requests drawn again ({@code -Dopm.drawing.before-after=<file>}).
	 */
	@Test
	public void theSameRequestsBeforeAndAfterBigDataViewerDrewInThisRuntime() throws Exception {
		String data = data();
		String file = System.getProperty("opm.drawing.before-after", "");
		Assume.assumeTrue("Set -Dopm.drawing.before-after=<file>", !file.isEmpty());
		RawPlaybackTraversalRealDataTest.Acquired a = RawPlaybackTraversalRealDataTest.acquire(new File(data),
				Integer.getInteger("opm.drawing.times", 3));
		List<String> cameraOf = new ArrayList<String>();
		List<RawFrameRequest> requests = read(new File(file), a, cameraOf);
		System.out.printf(Locale.US, "java %s; %d requests read back; %d render threads; %d warm draws per request, %d passes per phase%n",
				System.getProperty("java.version"), requests.size(), renderThreads(), REPEATS, PASSES);
		List<double[][]> before = redraw(requests, a.acquisition, a.times, a.geometry, a.specs);
		List<double[][]> control = redraw(requests, a.acquisition, a.times, a.geometry, a.specs);
		long loads = bigDataViewerDrawsProgressively(data);
		List<double[][]> after = redraw(requests, a.acquisition, a.times, a.geometry, a.specs);
		int last = PASSES - 1;
		System.out.println();
		System.out.printf(Locale.US, "BigDataViewer drew progressively in XY, XZ and YZ, %d cells loaded, between the second and third phases%n", loads);
		System.out.println("camera\ttime\t" + Metrics.HEADER + "\tbefore_ms\tcontrol_ms\tafter_ms\tafter_over_before");
		for (int i = 0; i < requests.size(); i++) {
			double b = median(before.get(i)[last]), c = median(control.get(i)[last]), x = median(after.get(i)[last]);
			System.out.printf(Locale.US, "%s\t%d\t%s\t%.1f\t%.1f\t%.1f\t%.2f%n", cameraOf.get(i), requests.get(i).timeIndex + 1,
					Metrics.of(requests.get(i)), b, c, x, x / b);
		}
		System.out.println("every draw, ms, pass by pass: before | control | after");
		for (int i = 0; i < requests.size(); i++)
			System.out.println(cameraOf.get(i) + "\t" + (requests.get(i).timeIndex + 1) + "\t" + Arrays.deepToString(before.get(i)) + " | "
					+ Arrays.deepToString(control.get(i)) + " | " + Arrays.deepToString(after.get(i)));
	}

	/**
	 * The other order: in a fresh runtime, BigDataViewer draws progressively in World XY, XZ and YZ
	 * first - before this runtime has drawn a single completed frame - and only then are the captured
	 * requests drawn ({@code -Dopm.drawing.bdv-first=<file>}). Compared with the fresh-runtime replay,
	 * this tells whether what ran before the renderer's first frames decides how fast it draws.
	 */
	@Test
	public void theSameRequestsInARuntimeWhereBigDataViewerDrewFirst() throws Exception {
		String data = data();
		String file = System.getProperty("opm.drawing.bdv-first", "");
		Assume.assumeTrue("Set -Dopm.drawing.bdv-first=<file>", !file.isEmpty());
		long loads = bigDataViewerDrawsProgressively(data);
		RawPlaybackTraversalRealDataTest.Acquired a = RawPlaybackTraversalRealDataTest.acquire(new File(data),
				Integer.getInteger("opm.drawing.times", 3));
		List<String> cameraOf = new ArrayList<String>();
		List<RawFrameRequest> requests = read(new File(file), a, cameraOf);
		System.out.printf(Locale.US, "java %s; BigDataViewer drew progressively in XY, XZ and YZ first, %d cells loaded; then %d requests, "
				+ "%d render threads, %d warm draws per request, %d passes%n", System.getProperty("java.version"), loads, requests.size(),
				renderThreads(), REPEATS, PASSES);
		List<double[][]> millis = redraw(requests, a.acquisition, a.times, a.geometry, a.specs);
		int last = PASSES - 1;
		System.out.println();
		System.out.println("camera\ttime\t" + Metrics.HEADER + "\tfirst_pass_ms\tafter_bdv_first_ms\tmin_ms\tevery_draw_ms_by_pass");
		for (int i = 0; i < requests.size(); i++)
			System.out.printf(Locale.US, "%s\t%d\t%s\t%.1f\t%.1f\t%.1f\t%s%n", cameraOf.get(i), requests.get(i).timeIndex + 1, Metrics.of(requests.get(i)),
					median(millis.get(i)[0]), median(millis.get(i)[last]), min(millis.get(i)[last]), Arrays.deepToString(millis.get(i)));
	}

	/* ---------------------------------------------------------------- 2c. other access classes first, and the render threads */

	/**
	 * M2-Q-F2: in the slow runtimes most of a frame is spent in ImgLib2's cell switch, at its casts of a cell's pixel access
	 * to {@code DataAccess} ({@code AbstractCellImg.update}) and to {@code ShortAccess} ({@code GenericShortType.updateContainer}).
	 * This asks two questions of a fresh runtime ({@code -Dopm.drawing.threads-sweep=<file>}). With
	 * {@code -Dopm.drawing.pollute=true}, ImgLib2's cell switch is first run over cells of two other access classes, ImgLib2's
	 * {@code ShortArray} and {@code VolatileShortArray}, as BigDataViewer's volatile drawing runs it over the store's lent and
	 * invalid accesses: does that alone make the runtime slow, every time? Then the captured requests are drawn with each render
	 * thread count of {@code -Dopm.drawing.threads} (default 8,1,2,4,8, the first a warm-up): is a slow frame slow because the
	 * threads contend, so that adding threads stops helping?
	 *
	 * <p>M2-Q-F3 separates the two things that polluting does: {@code -Dopm.drawing.pollute=imglib2-cached} passes the other
	 * classes through the cell switch from cells made once, so that nothing is allocated, and {@code =garbage} allocates the
	 * same cells and pixel arrays without passing them through the cell switch at all. {@code true} is {@code imglib2}. Every
	 * timed draw's garbage collections are counted and printed beside its time.
	 */
	@Test
	public void theSameRequestsAcrossRenderThreadCounts() throws Exception {
		String data = data();
		String file = System.getProperty("opm.drawing.threads-sweep", "");
		Assume.assumeTrue("Set -Dopm.drawing.threads-sweep=<file>", !file.isEmpty());
		String arm = System.getProperty("opm.drawing.pollute", "false");
		if (arm.equals("true")) arm = "imglib2";
		long n = Long.getLong("opm.drawing.polluteSwitches", 2000000), switches = 0;
		String before;
		switch (arm) {
		case "false":
			before = "fresh runtime, nothing drawn before";
			break;
		case "imglib2":
			switches = crossCellsOfOtherAccessClasses(n, false);
			before = "ImgLib2's cell switch first crossed " + switches + " cells of ShortArray and VolatileShortArray";
			break;
		case "imglib2-cached":
			switches = crossCellsOfOtherAccessClasses(n, true);
			before = "ImgLib2's cell switch first crossed " + switches + " cells of ShortArray and VolatileShortArray, made once";
			break;
		case "garbage":
			switches = allocateTheSameCells(n);
			before = "the same " + switches + " cells of ShortArray and VolatileShortArray first allocated, never crossed";
			break;
		default:
			throw new IllegalArgumentException("-Dopm.drawing.pollute=" + arm);
		}
		RawPlaybackTraversalRealDataTest.Acquired a = RawPlaybackTraversalRealDataTest.acquire(new File(data),
				Integer.getInteger("opm.drawing.times", 3));
		List<String> cameraOf = new ArrayList<String>();
		List<RawFrameRequest> requests = read(new File(file), a, cameraOf);
		String[] counts = System.getProperty("opm.drawing.threads", "8,1,2,4,8").split(",");
		System.out.printf(Locale.US, "java %s; arm %s: %s; %d requests, %d warm draws per request, %d passes per thread count%n",
				System.getProperty("java.version"), arm, before, requests.size(), REPEATS, PASSES);
		List<List<double[][]>> byCount = new ArrayList<List<double[][]>>();
		List<List<int[][]>> collections = new ArrayList<List<int[][]>>();
		for (String c : counts) {
			List<int[][]> gc = new ArrayList<int[][]>();
			byCount.add(redraw(requests, a.acquisition, a.times, a.geometry, a.specs, Integer.parseInt(c.trim()), gc));
			collections.add(gc);
		}
		int last = PASSES - 1;
		System.out.println();
		StringBuilder header = new StringBuilder("camera\ttime\t" + Metrics.HEADER);
		for (String c : counts) header.append("\tthreads_").append(c.trim()).append("_ms");
		System.out.println(header);
		for (int i = 0; i < requests.size(); i++) {
			StringBuilder row = new StringBuilder(String.format(Locale.US, "%s\t%d\t%s", cameraOf.get(i), requests.get(i).timeIndex + 1,
					Metrics.of(requests.get(i))));
			for (List<double[][]> m : byCount) row.append(String.format(Locale.US, "\t%.1f", median(m.get(i)[last])));
			System.out.println(row);
		}
		System.out.println("every draw, ms, pass by pass, thread count by thread count (" + String.join(",", counts) + ")");
		for (int i = 0; i < requests.size(); i++) {
			StringBuilder row = new StringBuilder(cameraOf.get(i) + "\t" + (requests.get(i).timeIndex + 1));
			for (List<double[][]> m : byCount) row.append("\t").append(Arrays.deepToString(m.get(i)));
			System.out.println(row);
		}
		System.out.println("every draw's garbage collections, the same layout");
		for (int i = 0; i < requests.size(); i++) {
			StringBuilder row = new StringBuilder("gc\t" + cameraOf.get(i) + "\t" + (requests.get(i).timeIndex + 1));
			for (List<int[][]> g : collections) row.append("\t").append(Arrays.deepToString(g.get(i)));
			System.out.println(row);
		}
	}

	/** Garbage collections so far, every collector. */
	private static long collections() {
		long n = 0;
		for (java.lang.management.GarbageCollectorMXBean b : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans())
			n += Math.max(0, b.getCollectionCount());
		return n;
	}

	/** Keeps each allocated cell until the next, as a cell switch keeps its current cell. */
	private static final Object[] HELD = new Object[2];

	/**
	 * The cells and pixel arrays {@link #crossCellsOfOtherAccessClasses} allocates, {@code count} of them, alternately of the
	 * two classes, each held until the next: the same garbage, with no cell switch. Returns the cells made.
	 */
	static long allocateTheSameCells(long count) {
		for (long k = 0; k < count; k++)
			HELD[(int) (k & 1)] = new Cell<ShortAccess>(new int[] { 64, 8, 1 }, new long[] { 0, 0, (k >> 1) % 64 },
					(k & 1) == 0 ? new ShortArray(64 * 8) : new VolatileShortArray(64 * 8, true));
		return count;
	}

	/**
	 * ImgLib2's cell switch - {@code CellRandomAccess.get}, {@code GenericShortType.updateContainer},
	 * {@code AbstractCellImg.update} - run {@code switches} times over in-memory cells of two access classes other than the
	 * store's, alternately, one plane per cell. Every switch makes its cell afresh - {@code LazyCellImg} keeps none - unless
	 * {@code cached}, when each cell is made once and the switches allocate nothing. Returns the cells crossed.
	 */
	static long crossCellsOfOtherAccessClasses(long switches, boolean cached) {
		final CellGrid grid = new CellGrid(new long[] { 64, 8, 64 }, new int[] { 64, 8, 1 });
		final List<Cell<ShortAccess>> plainCells = new ArrayList<Cell<ShortAccess>>(), markedCells = new ArrayList<Cell<ShortAccess>>();
		for (int index = 0; index < 64; index++) {
			plainCells.add(new Cell<ShortAccess>(new int[] { 64, 8, 1 }, new long[] { 0, 0, index }, new ShortArray(64 * 8)));
			markedCells.add(new Cell<ShortAccess>(new int[] { 64, 8, 1 }, new long[] { 0, 0, index }, new VolatileShortArray(64 * 8, true)));
		}
		LazyCellImg<UnsignedShortType, ShortAccess> plain = new LazyCellImg<UnsignedShortType, ShortAccess>(grid, new UnsignedShortType(),
				index -> cached ? plainCells.get((int) index)
						: new Cell<ShortAccess>(new int[] { 64, 8, 1 }, new long[] { 0, 0, index }, new ShortArray(64 * 8)));
		LazyCellImg<UnsignedShortType, ShortAccess> marked = new LazyCellImg<UnsignedShortType, ShortAccess>(grid, new UnsignedShortType(),
				index -> cached ? markedCells.get((int) index)
						: new Cell<ShortAccess>(new int[] { 64, 8, 1 }, new long[] { 0, 0, index }, new VolatileShortArray(64 * 8, true)));
		RandomAccess<UnsignedShortType> p = plain.randomAccess(), m = marked.randomAccess();
		long sum = 0;
		for (long k = 0; k < switches; k++) {
			RandomAccess<UnsignedShortType> r = (k & 1) == 0 ? p : m;
			r.setPosition((int) (k % 64), 0);
			r.setPosition((int) ((k >> 1) % 64), 2);		// another plane, another cell
			sum += r.get().get();
		}
		assertEquals("zero-filled cells", 0, sum);
		return switches;
	}

	/**
	 * An Online Deskew View opened in this runtime and left to BigDataViewer's own progressive drawing:
	 * World XY, XZ and YZ, three time points each, every one until nothing was read for a second. No
	 * completed frame is made. Returns the cells loaded.
	 */
	private static long bigDataViewerDrawsProgressively(String data) throws Exception {
		OnlineDeskewView.Settings settings = new OnlineDeskewView.Settings();
		settings.folder = data;
		settings.budgetMb = BUDGET / 1e6;
		OnlineDeskewSession session = OnlineDeskewSession.open(settings);
		try {
			for (RawWorldView plane : RawWorldView.values()) {
				edt(() -> {
					session.worldAction(plane).actionPerformed(null);
					return null;
				});
				for (int t = 0; t < Math.min(3, session.times().size()); t++) {
					final int time = t;
					edt(() -> {
						session.handle().getViewerPanel().setTimepoint(time);
						return null;
					});
					long start = System.nanoTime(), quiet = System.nanoTime(), last = -1;
					while (System.nanoTime() - start < 60e9) {		// until BigDataViewer has drawn it and read nothing for a second
						RawVolumeStore.CacheStats s = session.store().stats();
						if (s.loads != last || s.inFlight > 0) {
							last = s.loads;
							quiet = System.nanoTime();
						} else if (System.nanoTime() - quiet > 1e9) break;
						Thread.sleep(20);
					}
				}
			}
			assertTrue("no completed frame was made", edt(() -> session.player() == null));
			return session.store().stats().loads;
		} finally {
			SwingUtilities.invokeAndWait(() -> session.frame().dispose());
			assertTrue(session.closed().get(30, TimeUnit.SECONDS).drained);
		}
	}

	/* ---------------------------------------------------------------- 3. the zoom sweep */

	@Test
	public void drawingCostAcrossZoomPlanesAndCameras() throws Exception {
		String data = data();
		Assume.assumeTrue("Set -Dopm.drawing.sweep=true", Boolean.getBoolean("opm.drawing.sweep"));
		String[] cameras = System.getProperty("opm.drawing.cameras", "XY,XZ,YZ,OBL-Y30").split(",");
		double[] zooms = { 0.25, 0.5, 1, 2 };
		int w = 1600, h = 1000;
		RawPlaybackTraversalRealDataTest.Acquired a = RawPlaybackTraversalRealDataTest.acquire(new File(data), 1);
		AffineTransform3D xy = RawFrameRenderBenchmark.xyView(a.geometry, a.dims, a.specs, w, h);
		List<RawFrameRequest> requests = new ArrayList<RawFrameRequest>();
		List<String> labels = new ArrayList<String>();
		for (String name : cameras) {
			AffineTransform3D base = RawPlaybackTraversalRealDataTest.camera(name.trim(), xy, w, h);
			RawFrameRequest probe = request(a, 0, base, w, h, 0);
			double now = Metrics.pixelsPerVoxel(probe);
			for (double zoom : zooms) {
				AffineTransform3D view = base.copy();
				view.translate(-0.5 * w, -0.5 * h, 0);
				view.scale(zoom / now);
				view.translate(0.5 * w, 0.5 * h, 0);
				requests.add(request(a, 0, view, w, h, 0));
				labels.add(name.trim() + "@" + zoom);
			}
		}
		System.out.printf(Locale.US, "java %s; %d x %d; %s; %d render threads; %d warm draws per request; budget %.0f MB%n",
				System.getProperty("java.version"), w, h, Interpolation.NLINEAR, renderThreads(), REPEATS, BUDGET / 1e6);
		List<double[][]> millis = redraw(requests, a.acquisition, a.times, a.geometry, a.specs);
		int last = PASSES - 1;
		System.out.println();
		System.out.println("camera@zoom\t" + Metrics.HEADER + "\tfirst_pass_ms\trender_ms\tmin_ms\tns_per_data_pixel\tns_per_crossing\tevery_draw_ms_by_pass");
		for (int i = 0; i < requests.size(); i++) {
			Metrics m = Metrics.of(requests.get(i));
			double med = median(millis.get(i)[last]);
			System.out.printf(Locale.US, "%s\t%s\t%.1f\t%.1f\t%.1f\t%.1f\t%.1f\t%s%n", labels.get(i), m, median(millis.get(i)[0]), med,
					min(millis.get(i)[last]), m.dataPixels == 0 ? 0 : med * 1e6 / m.dataPixels, m.crossings == 0 ? 0 : med * 1e6 / m.crossings,
					Arrays.deepToString(millis.get(i)));
		}
	}

	/* ---------------------------------------------------------------- drawing through the harness */

	private static RawFrameRequest request(RawPlaybackTraversalRealDataTest.Acquired a, int t, AffineTransform3D view, int w, int h, long revision) {
		RawAcquisition.Entry e = a.acquisition.entry(a.times.get(t), 1);
		List<RawFrameRequest.Layer> layers = new ArrayList<RawFrameRequest.Layer>();
		for (RawSourceSpec spec : a.specs)
			layers.add(new RawFrameRequest.Layer(spec, a.geometry.sourceToWorld(e.layout().dimensions(), spec, null), a.range[0], a.range[1],
					0xff000000 | spec.rgb));
		return new RawFrameRequest(revision, t, e, view, w, h, Interpolation.NLINEAR, layers);
	}

	/**
	 * The requests drawn through a harness rig - store, strict player, canvas of their size painting
	 * on the event thread - in {@link #PASSES} passes over the whole set; within a pass each request
	 * once to read its cells, then {@link #REPEATS} times. Per request, per pass: the player's render
	 * time of each repeat.
	 */
	private static List<double[][]> redraw(List<RawFrameRequest> requests, RawAcquisition acquisition, List<RawAcquisition.TimeKey> times,
			RawGeometry geometry, RawSourceSpec[] specs) throws Exception {
		return redraw(requests, acquisition, times, geometry, specs, renderThreads());
	}

	private static List<double[][]> redraw(List<RawFrameRequest> requests, RawAcquisition acquisition, List<RawAcquisition.TimeKey> times,
			RawGeometry geometry, RawSourceSpec[] specs, int renderThreads) throws Exception {
		return redraw(requests, acquisition, times, geometry, specs, renderThreads, null);
	}

	/** As above; with {@code collections} given, each timed draw's garbage collections are added to it, in the same layout. */
	private static List<double[][]> redraw(List<RawFrameRequest> requests, RawAcquisition acquisition, List<RawAcquisition.TimeKey> times,
			RawGeometry geometry, RawSourceSpec[] specs, int renderThreads, List<int[][]> collections) throws Exception {
		RawFrameRequest any = requests.get(0);
		for (RawFrameRequest r : requests) assertTrue("one canvas size per rig", r.width == any.width && r.height == any.height);
		RawPlaybackTraversalRealDataTest.Rig rig = new RawPlaybackTraversalRealDataTest.Rig(acquisition, times, geometry, specs, any.viewer,
				new double[] { 0, 1 }, BUDGET, TiffRawVolumeStore.cellBytes(any.layout), ioThreads(), renderThreads, any.width, any.height);
		List<double[][]> millis = new ArrayList<double[][]>();
		for (int i = 0; i < requests.size(); i++) millis.add(new double[PASSES][REPEATS]);
		if (collections != null) for (int i = 0; i < requests.size(); i++) collections.add(new int[PASSES][REPEATS]);
		long revision = 0;
		try {
			for (int pass = 0; pass < PASSES; pass++)
				for (int i = 0; i < requests.size(); i++) {
					RawFrameRequest r = requests.get(i);
					for (int k = -1; k < REPEATS; k++) {
						RawFrameRequest copy = new RawFrameRequest(++revision, r.timeIndex, r.entry, r.viewer, r.width, r.height, r.interpolation,
								r.layers);
						long gcBefore = collections == null ? 0 : collections();
						RawStrictPlayer.Result result = rig.player.request(copy).get(300, TimeUnit.SECONDS);
						if (k >= 0 && collections != null) collections.get(i)[pass][k] = (int) (collections() - gcBefore);
						assertEquals(String.valueOf(result), RawStrictPlayer.Outcome.PRESENTED, result.outcome);
						assertEquals(0, result.coverageMisses);
						if (k >= 0) millis.get(i)[pass][k] = result.renderNanos / 1e6;
					}
				}
		} finally {
			rig.close();
		}
		return millis;
	}

	private static double median(double[] v) {
		return RawPlaybackTraversalRealDataTest.quantile(v, 0.5);
	}

	private static double min(double[] v) {
		double m = Double.MAX_VALUE;
		for (double x : v) m = Math.min(m, x);
		return m;
	}

	/* ---------------------------------------------------------------- the geometry of a request */

	/** What a request asks of the renderer, from its geometry alone. */
	static final class Metrics {
		static final String HEADER = "canvas\tpx_per_voxel\tcells\tdata_pixels\tcrossings\tcrossings_per_row";
		int width, height;
		double pixelsPerVoxel;
		long cells, dataPixels, crossings, rowsWithData;

		/** Screen pixels per raw voxel: the viewer's scale times one raw x step's length in the world. */
		static double pixelsPerVoxel(RawFrameRequest r) {
			AffineTransform3D v = r.viewer;
			double det = v.get(0, 0) * (v.get(1, 1) * v.get(2, 2) - v.get(1, 2) * v.get(2, 1))
					- v.get(0, 1) * (v.get(1, 0) * v.get(2, 2) - v.get(1, 2) * v.get(2, 0))
					+ v.get(0, 2) * (v.get(1, 0) * v.get(2, 1) - v.get(1, 1) * v.get(2, 0));
			AffineTransform3D s = r.layers.get(0).sourceToWorld;
			double step = Math.sqrt(s.get(0, 0) * s.get(0, 0) + s.get(1, 0) * s.get(1, 0) + s.get(2, 0) * s.get(2, 0));
			return Math.cbrt(Math.abs(det)) * step;
		}

		/**
		 * The cells the frame reads, and along each screen row, how often consecutive samples of one
		 * layer fall in different raw cells - strip {@code floor(y) / rowsPerStrip} of plane
		 * {@code floor(z)}, the lower corner a trilinear sample reads - summed over the layers.
		 */
		static Metrics of(RawFrameRequest r) {
			Metrics m = new Metrics();
			m.width = r.width;
			m.height = r.height;
			m.pixelsPerVoxel = pixelsPerVoxel(r);
			long[] dims = r.layout.dimensions();
			int rowsPerStrip = r.layout.rowsPerStrip, rows = (int) dims[1], planes = (int) dims[2];
			int strips = (rows + rowsPerStrip - 1) / rowsPerStrip;
			m.cells = RawCoverage.cells(dims, rowsPerStrip, r.coverageParts(), r.width, r.height, r.interpolation).cardinality();
			boolean[] rowHasData = new boolean[r.height];
			for (RawFrameRequest.Layer layer : r.layers) {
				AffineTransform3D toSource = r.sourceToScreen(layer).inverse();
				double[] a = new double[3], b = new double[3], c = new double[3];
				for (int d = 0; d < 3; d++) {
					a[d] = toSource.get(d, 0);
					b[d] = toSource.get(d, 1);
					c[d] = toSource.get(d, 3);
				}
				long partWidth = layer.spec.cropWidth(dims[0]);
				for (int v = 0; v < r.height; v++) {
					long previous = -1;
					for (int u = 0; u < r.width; u++) {
						double x = a[0] * u + b[0] * v + c[0], y = a[1] * u + b[1] * v + c[1], z = a[2] * u + b[2] * v + c[2];
						if (x < 0 || x > partWidth - 1 || y < 0 || y > rows - 1 || z < 0 || z > planes - 1) {
							previous = -1;		// a gap: the next voxel starts a run, it does not cross into one
							continue;
						}
						m.dataPixels++;
						rowHasData[v] = true;
						long cell = (long) Math.floor(y) / rowsPerStrip + (long) strips * (long) Math.floor(z);
						if (previous >= 0 && cell != previous) m.crossings++;
						previous = cell;
					}
				}
			}
			for (boolean b : rowHasData) if (b) m.rowsWithData++;
			return m;
		}

		@Override public String toString() {
			return String.format(Locale.US, "%dx%d\t%.3f\t%d\t%d\t%d\t%.1f", width, height, pixelsPerVoxel, cells, dataPixels, crossings,
					rowsWithData == 0 ? 0 : (double) crossings / rowsWithData);
		}
	}

	/* ---------------------------------------------------------------- the requests on disk */

	/** One request per line: camera, time index, canvas, interpolation, viewer, then each layer's half, range, colour and placement. */
	private static void write(File file, List<String> cameras, List<RawFrameRequest> requests) throws Exception {
		try (BufferedWriter out = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
			for (int i = 0; i < requests.size(); i++) {
				RawFrameRequest r = requests.get(i);
				StringBuilder s = new StringBuilder();
				s.append(cameras.get(i)).append('\t').append(r.timeIndex).append('\t').append(r.width).append('\t').append(r.height).append('\t')
						.append(r.interpolation.name()).append('\t').append(join(r.viewer.getRowPackedCopy())).append('\t').append(r.layers.size());
				for (RawFrameRequest.Layer l : r.layers)
					s.append('\t').append(l.spec.part.name()).append('\t').append(Double.toString(l.min)).append('\t').append(Double.toString(l.max))
							.append('\t').append(l.argb).append('\t').append(join(l.sourceToWorld.getRowPackedCopy()));
				out.write(s.toString());
				out.newLine();
			}
		}
	}

	private static List<RawFrameRequest> read(File file, RawPlaybackTraversalRealDataTest.Acquired a, List<String> cameras) throws Exception {
		List<RawFrameRequest> requests = new ArrayList<RawFrameRequest>();
		long revision = 0;
		for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
			if (line.trim().isEmpty()) continue;
			String[] f = line.split("\t");
			int i = 0;
			cameras.add(f[i++]);
			int t = Integer.parseInt(f[i++]), w = Integer.parseInt(f[i++]), h = Integer.parseInt(f[i++]);
			Interpolation interpolation = Interpolation.valueOf(f[i++]);
			AffineTransform3D viewer = new AffineTransform3D();
			viewer.set(split(f[i++]));
			int n = Integer.parseInt(f[i++]);
			RawAcquisition.Entry e = a.acquisition.entry(a.times.get(t), 1);
			List<RawFrameRequest.Layer> layers = new ArrayList<RawFrameRequest.Layer>();
			for (int k = 0; k < n; k++) {
				RawSourceSpec.Part part = RawSourceSpec.Part.valueOf(f[i++]);
				double min = Double.parseDouble(f[i++]), max = Double.parseDouble(f[i++]);
				int argb = Integer.parseInt(f[i++]);
				AffineTransform3D toWorld = new AffineTransform3D();
				toWorld.set(split(f[i++]));
				RawSourceSpec spec = part == RawSourceSpec.Part.LEFT ? a.specs[0] : a.specs[1];
				layers.add(new RawFrameRequest.Layer(spec, toWorld, min, max, argb));
			}
			requests.add(new RawFrameRequest(++revision, t, e, viewer, w, h, interpolation, layers));
		}
		return requests;
	}

	private static String join(double[] v) {
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < v.length; i++) s.append(i == 0 ? "" : ",").append(Double.toString(v[i]));
		return s.toString();
	}

	private static double[] split(String s) {
		String[] p = s.split(",");
		double[] v = new double[p.length];
		for (int i = 0; i < p.length; i++) v[i] = Double.parseDouble(p[i]);
		return v;
	}
}
