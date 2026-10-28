package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import bdv.viewer.Source;
import bdv.viewer.ViewerPanel;
import net.imglib2.RandomAccess;
import net.imglib2.type.volatiles.VolatileUnsignedShortType;

/**
 * M3-A acceptance 4: a real acquisition copied into an empty folder file by file, each file written as it grows - as the Live
 * Deskew runs were tested - while the Online Deskew View follows the folder. Every time point appears exactly once, and only
 * once it is complete; a file checked half way through its copy is not shown; the other acquisition channel, which the view
 * does not show, gates nothing and is never checked. Skipped unless the data, a scratch folder with room for the copies and a
 * display are named:
 * <pre>
 * mvn test -Dtest=OnlineDeskewLiveRealDataTest -Dopm.test.gui=true -Dopm.test.jvm.args=-Xmx6g
 *     -Dopm.test.data=E:\OPM\3_timelapse_0 -Dopm.test.live.scratch=D:\Temp [-Dopm.test.live.times=3]
 * </pre>
 * Each time point of {@code 3_timelapse_0} is two files of 1.44 GB; the copy is deleted afterwards.
 */
public class OnlineDeskewLiveRealDataTest {

	/** Bytes copied before the copy pauses for the throttle, and how long it pauses: about 0.3 GB/s. */
	static final int CHUNK = 16 << 20;
	static final long PAUSE_MS = 50;

	private File into;
	private OnlineDeskewSession session;

	@Before
	public void needsDataAScratchFolderAndADisplay() {
		Assume.assumeTrue("Enable with -Dopm.test.gui=true", Boolean.getBoolean("opm.test.gui"));
		Assume.assumeTrue("Set -Dopm.test.data and -Dopm.test.live.scratch",
				!System.getProperty("opm.test.data", "").isEmpty() && !System.getProperty("opm.test.live.scratch", "").isEmpty());
		RawAcquisition.eventThreadFileAccessForTest.set(0);
	}

	@After
	public void closeAndDelete() throws Exception {
		try {
			if (session != null && !session.isClosing()) {
				SwingUtilities.invokeAndWait(() -> session.frame().dispose());
				OnlineDeskewSession.CloseResult result = session.closed().get(30, TimeUnit.SECONDS);
				assertTrue("closed cleanly: " + result, result.drained);
			}
		} finally {
			if (into != null) {
				File[] left = into.listFiles();
				if (left != null) for (File f : left) Files.deleteIfExists(f.toPath());
				Files.deleteIfExists(into.toPath());
			}
		}
		assertEquals("no file touched on the event thread", 0, RawAcquisition.eventThreadFileAccessForTest.get());
	}

	/**
	 * Copy a file as an acquisition writes it: in chunks, at a bounded rate, its time moving on with every chunk. Half way
	 * through, {@code atHalfway} runs, if there is one, while the copy waits for it.
	 */
	static void grow(File from, File to, Runnable atHalfway) throws Exception {
		try (FileChannel in = FileChannel.open(from.toPath(), StandardOpenOption.READ);
				FileChannel out = FileChannel.open(to.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
						StandardOpenOption.TRUNCATE_EXISTING)) {
			long size = in.size(), done = 0;
			ByteBuffer buffer = ByteBuffer.allocateDirect(CHUNK);
			boolean paused = atHalfway == null;
			while (done < size) {
				buffer.clear();
				int n = in.read(buffer, done);
				if (n < 0) throw new IOException("short read of " + from);
				buffer.flip();
				while (buffer.hasRemaining()) done += out.write(buffer, done);
				if (!paused && done >= size / 2) {
					paused = true;
					atHalfway.run();
				}
				Thread.sleep(PAUSE_MS);
			}
		}
	}

	static void await(String what, long millis, BooleanSupplier condition) throws Exception {
		long deadline = System.currentTimeMillis() + millis;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) throw new AssertionError("not within " + millis + " ms: " + what);
			Thread.sleep(50);
		}
	}

	private RawAcquisition.Entry entry(int time, int channel) {
		return session.acquisition().entry(new RawAcquisition.TimeKey(1, time), channel);
	}

	@Test
	public void aCopiedAcquisitionAppearsTimePointByTimePointEachOnceComplete() throws Exception {
		final File source = new File(System.getProperty("opm.test.data"));
		final int count = Integer.getInteger("opm.test.live.times", 3);
		File scratch = new File(System.getProperty("opm.test.live.scratch"));
		into = new File(scratch, "opm-online-deskew-live-" + System.currentTimeMillis());
		assertTrue(into.mkdirs());
		Files.copy(new File(source, "ExperimentalParameters.txt").toPath(), new File(into, "ExperimentalParameters.txt").toPath(),
				StandardCopyOption.COPY_ATTRIBUTES);
		List<File> files = new ArrayList<File>();
		for (File f : source.listFiles()) {
			RawAcquisition.Name n = RawAcquisition.Name.parse(f.getName());
			if (f.isFile() && f.getName().endsWith(".tiff") && n.time >= 1 && n.time <= count) files.add(f);
		}
		files.sort(Comparator.comparingInt((File f) -> RawAcquisition.Name.parse(f.getName()).time)
				.thenComparingInt(f -> RawAcquisition.Name.parse(f.getName()).channel));
		assertEquals("two files per time point", 2 * count, files.size());

		// the first file whole, so that there is something to open; then the view follows the rest
		long start = System.nanoTime();
		grow(files.get(0), new File(into, files.get(0).getName()), null);
		OnlineDeskewView.Settings s = new OnlineDeskewView.Settings();
		s.folder = into.getAbsolutePath();
		s.budgetMb = 1024;
		session = OnlineDeskewSession.open(s);
		final ViewerPanel viewer = session.handle().getViewerPanel();
		assertEquals(1, session.snapshot().size());

		final String[] halfway = new String[1];
		final long[] lastByte = new long[count + 1], published = new long[count + 1];
		Thread watch = Shutdown.daemon(() -> {		// when each time point is published, as the catalogue says
			while (!Thread.currentThread().isInterrupted()) {
				for (int t = 1; t <= count; t++) {
					RawAcquisition.Entry e = session.acquisition().entry(new RawAcquisition.TimeKey(1, t), 1);
					if (published[t] == 0 && e != null && e.viewable()) published[t] = System.nanoTime();
				}
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
					return;
				}
			}
		}, "test-publication-watch");
		watch.start();
		try {
			for (int i = 1; i < files.size(); i++) {
				final File from = files.get(i), to = new File(into, from.getName());
				final RawAcquisition.Name n = RawAcquisition.Name.parse(from.getName());
				Runnable atHalfway = i != 2 ? null : () -> {
					// half the file on disk, quiet for longer than the quiet time: it is checked, and must not be shown
					try {
						await("the half-copied file checked", 15000, () -> {
							RawAcquisition.Entry e = entry(n.time, n.channel);
							return e != null && e.checks() > 0;
						});
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
					RawAcquisition.Entry e = entry(n.time, n.channel);
					halfway[0] = e.readiness() + ": " + e.describe();
					assertFalse("a half-copied file is not shown: " + halfway[0], e.viewable());
				};
				grow(from, to, atHalfway);
				if (n.channel == 1) lastByte[n.time] = System.nanoTime();
			}
			await("every time point published", 120000, () -> {
				for (int t = 1; t <= count; t++)
					if (!entry(t, 1).viewable()) return false;
				return true;
			});
		} finally {
			watch.interrupt();
			watch.join(1000);
		}
		double seconds = (System.nanoTime() - start) / 1e9;
		SwingUtilities.invokeAndWait(() -> {});

		List<Integer> expected = new ArrayList<Integer>(), axis = new ArrayList<Integer>();
		for (int t = 1; t <= count; t++) expected.add(t);
		for (RawAcquisition.TimeKey t : session.snapshot().times) axis.add(t.timeId);
		assertEquals("every time point exactly once, in order", expected, axis);
		assertEquals("BigDataViewer's time count", count, (int) edt(() -> viewer.state().getNumTimepoints()));
		StringBuilder report = new StringBuilder();
		for (int t = 1; t <= count; t++) {
			RawAcquisition.Entry e = entry(t, 1);
			File copy = e.file;
			assertEquals(e.describe(), RawAcquisition.Readiness.COMPLETE, e.readiness());
			assertEquals("the whole file", copy.length(), e.key().size);
			assertEquals("every plane", 451, e.layout().depth);
			assertEquals("published once, never withdrawn and published again", 0, e.republished());
			RawAcquisition.Entry other = entry(t, 2);
			assertTrue("the other channel is catalogued", other != null);
			assertEquals("the other channel gates nothing and is never checked", 0, other.checks());
			report.append(String.format(Locale.US, "  Time %d: %d checks, published %s%n", t, e.checks(),
					t == 1 ? "before opening" : String.format(Locale.US, "%.0f ms after its last byte", (published[t] - lastByte[t]) / 1e6)));
		}
		// what the view draws of the last time point is the file's own
		RawTiffLayout layout = entry(count, 1).layout();
		final int last = count - 1, x = layout.width / 4, y = layout.height / 2, z = layout.depth / 2;
		int drawn = pixel(viewer, last, x, y, z);
		int onDisk;
		try (FastTiffReader.PlaneReader reader = new FastTiffReader.PlaneReader(entry(count, 1).file)) {
			onDisk = reader.readPlane(z).get(x, y);
		}
		assertEquals("the last time point's voxel through the view", onDisk, drawn);
		String status = edt(session::statusText);
		System.out.printf(Locale.US, "copied %d files (%.1f GB) in %.1f s; the half-copied file was %s%n%s%s%n", files.size(),
				files.size() * files.get(0).length() / 1e9, seconds, halfway[0], report, status);
		assertTrue("the half-copied file was looked at", halfway[0] != null);
		assertTrue(status, status.contains("Files: " + count + " complete"));
	}

	private static <T> T edt(java.util.concurrent.Callable<T> work) throws Exception {
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

	@SuppressWarnings("unchecked")
	private static int pixel(ViewerPanel viewer, int t, long x, long y, long z) throws Exception {
		long deadline = System.currentTimeMillis() + 30000;
		while (System.currentTimeMillis() < deadline) {
			viewer.requestRepaint();
			Thread.sleep(50);
			Source<VolatileUnsignedShortType> half = (Source<VolatileUnsignedShortType>) viewer.state().getSources().get(0).asVolatile()
					.getSpimSource();
			if (!half.isPresent(t)) continue;
			RandomAccess<VolatileUnsignedShortType> a = half.getSource(t, 0).randomAccess();
			a.setPosition(new long[] { x, y, z });
			if (a.get().isValid()) return a.get().get().get();
		}
		return -1;
	}
}
