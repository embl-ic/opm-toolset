package de.embl.iclm;

import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Test;

public class ShutdownAsyncTest {
	@After public void clear() { Shutdown.begin(); }
	static void await(CountDownLatch latch) throws Exception { assertTrue("deadline", latch.await(10, TimeUnit.SECONDS)); }
	static void uninterruptible(CountDownLatch latch) { boolean done=false; while (!done) try { latch.await(); done=true; } catch (InterruptedException ignored) { } }
	@Test public void stopIsScopedAndCompletionWaitsForActualCleanup() throws Exception {
		CountDownLatch ready = new CountDownLatch(2), cleanup = new CountDownLatch(1), releaseCleanup = new CountDownLatch(1), releaseB = new CountDownLatch(1), finished = new CountDownLatch(2);
		AtomicBoolean bStopped = new AtomicBoolean(), aCompleted = new AtomicBoolean();
		Shutdown.Operation a = Shutdown.startCancellable("async A", () -> {
			ready.countDown();
			try { while (!Shutdown.stopping()) Thread.yield(); }
			finally { cleanup.countDown(); uninterruptible(releaseCleanup); }
		}, op -> { assertFalse(op.isRunning()); assertFalse(Shutdown.running().contains(op)); aCompleted.set(true); finished.countDown(); });
		Shutdown.Operation b = Shutdown.startCancellable("async B", () -> { ready.countDown(); uninterruptible(releaseB); bStopped.set(Shutdown.stopping()); }, op -> finished.countDown());
		try {
			await(ready); Shutdown.cancel(a); await(cleanup);
			assertTrue(a.isRunning()); assertFalse(aCompleted.get()); assertTrue(b.isRunning()); assertFalse(b.isCancelled());
		} finally { releaseCleanup.countDown(); releaseB.countDown(); }
		await(finished); assertFalse(bStopped.get()); assertTrue(a.isCancelled());
	}
	@Test public void freshStartCannotEraseAnotherOperationsGlobalCancellation() throws Exception {
		CountDownLatch ready = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(2);
		Shutdown.Operation a = Shutdown.startCancellable("global A", () -> { ready.countDown(); uninterruptible(release); }, op -> done.countDown());
		await(ready);
		try {
			Shutdown.request(); Shutdown.begin(); // legacy begin may clear the global flag, but A remains cancelled
			assertTrue(a.isCancelled());
			Shutdown.startCancellable("global B", () -> {}, op -> done.countDown());
		} finally { release.countDown(); }
		await(done); assertFalse(a.isRunning());
	}
	@Test public void failureReachesOwnedHandleAfterDeregistration() throws Exception {
		CountDownLatch done = new CountDownLatch(1);
		Shutdown.Operation operation = Shutdown.startCancellable("failure", () -> { throw new IllegalArgumentException("deliberate"); }, op -> done.countDown());
		await(done); assertFalse(operation.isRunning()); assertEquals("deliberate", operation.getFailure().getMessage());
	}
}
