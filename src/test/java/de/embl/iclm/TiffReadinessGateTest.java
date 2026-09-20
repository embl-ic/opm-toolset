package de.embl.iclm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Timing tests for the quiet-period gate used by the live folder watcher. */
public class TiffReadinessGateTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void waitsForAFullQuietPeriodAfterAChunkedWriteCompletes() throws Exception {
		byte[] complete = classicTiff();
		File file = write("chunked.tif", complete, 30);
		TiffReadinessGate gate = new TiffReadinessGate();
		gate.observe(file);

		ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			Future<Boolean> ready = worker.submit(new java.util.concurrent.Callable<Boolean>() {
				@Override public Boolean call() throws Exception {
					return gate.await(file, alwaysRunning(), 20, 250, 2000);
				}
			});
			Thread.sleep(120);
			assertFalse("an incomplete TIFF must not pass the gate", ready.isDone());

			append(file, complete, 30, complete.length - 30);
			gate.observe(file); // the WatchService ENTRY_MODIFY event
			long completedAt = System.nanoTime();
			assertTrue(ready.get(2, TimeUnit.SECONDS));
			long quietElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - completedAt);
			assertTrue("accepted after only " + quietElapsedMs + " ms of quiet",
					quietElapsedMs >= 200);
		} finally {
			worker.shutdownNow();
		}
	}

	@Test
	public void sameSizeModifyEventRestartsTheQuietPeriod() throws Exception {
		File file = write("same-size-event.tif", classicTiff(), classicTiff().length);
		TiffReadinessGate gate = new TiffReadinessGate();
		gate.observe(file);
		CountDownLatch started = new CountDownLatch(1);

		ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			Future<Boolean> ready = worker.submit(new java.util.concurrent.Callable<Boolean>() {
				@Override public Boolean call() throws Exception {
					return gate.await(file, new TiffReadinessGate.Running() {
						@Override public boolean get() {
							started.countDown();
							return true;
						}
					}, 20, 300, 2000);
				}
			});
			assertTrue(started.await(1, TimeUnit.SECONDS));
			Thread.sleep(150);
			gate.observe(file); // size and timestamp may be unchanged, but an event is activity
			long eventAt = System.nanoTime();

			assertTrue(ready.get(2, TimeUnit.SECONDS));
			long quietElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - eventAt);
			assertTrue("same-size event did not restart quiet time: " + quietElapsedMs + " ms",
					quietElapsedMs >= 250);
		} finally {
			worker.shutdownNow();
		}
	}

	@Test
	public void structurallyIncompleteTiffTimesOut() throws Exception {
		File file = write("never-complete.tif", classicTiff(), 30);
		TiffReadinessGate gate = new TiffReadinessGate();
		gate.observe(file);
		long started = System.nanoTime();

		assertFalse(gate.await(file, alwaysRunning(), 20, 80, 350));
		long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
		assertTrue("timeout returned too early: " + elapsedMs + " ms", elapsedMs >= 300);
	}

	@Test
	public void cancellationStopsAWaitPromptly() throws Exception {
		File file = write("cancel.tif", classicTiff(), classicTiff().length);
		TiffReadinessGate gate = new TiffReadinessGate();
		gate.observe(file);
		AtomicBoolean running = new AtomicBoolean(true);
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			Future<Boolean> ready = worker.submit(new java.util.concurrent.Callable<Boolean>() {
				@Override public Boolean call() throws Exception {
					return gate.await(file, new TiffReadinessGate.Running() {
						@Override public boolean get() {
							return running.get();
						}
					}, 20, 5000, 5000);
				}
			});
			Thread.sleep(80);
			running.set(false);
			assertFalse(ready.get(1, TimeUnit.SECONDS));
		} finally {
			worker.shutdownNow();
		}
	}

	private static TiffReadinessGate.Running alwaysRunning() {
		return new TiffReadinessGate.Running() {
			@Override public boolean get() {
				return true;
			}
		};
	}

	private File write(String name, byte[] bytes, int length) throws IOException {
		File file = new File(folder.getRoot(), name);
		FileOutputStream output = new FileOutputStream(file);
		try {
			output.write(bytes, 0, length);
		} finally {
			output.close();
		}
		return file;
	}

	private static void append(File file, byte[] bytes, int offset, int length)
			throws IOException {
		FileOutputStream output = new FileOutputStream(file, true);
		try {
			output.write(bytes, offset, length);
		} finally {
			output.close();
		}
	}

	/** One 1x1 classic TIFF with five entries and one byte of uncompressed pixel data. */
	private static byte[] classicTiff() {
		final int pixel = 74;
		ByteBuffer out = ByteBuffer.allocate(pixel + 1).order(ByteOrder.LITTLE_ENDIAN);
		out.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
		out.putShort((short) 5);
		entry(out, 256, 4, 1, 1);
		entry(out, 257, 4, 1, 1);
		entry(out, 273, 4, 1, pixel);
		entry(out, 278, 4, 1, 1);
		entry(out, 279, 4, 1, 1);
		out.putInt(0);
		out.put((byte) 7);
		return out.array();
	}

	private static void entry(ByteBuffer out, int tag, int type, int count, int value) {
		out.putShort((short) tag).putShort((short) type).putInt(count).putInt(value);
	}
}
