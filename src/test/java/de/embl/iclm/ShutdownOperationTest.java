package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;

/**
 * Terminating one batch run without touching the others.
 *
 * <p>The property under test is isolation. {@link Shutdown#request()} is a single global flag
 * and stops everything in the JVM, which is the wrong answer when two runs are in flight and
 * only one is the mistake - so a per-run cancellation has to reach exactly the run it names,
 * its own worker pools included, and nothing else.
 *
 * <p>Everything here is polled with a deadline rather than slept through. These are real
 * threads and a fixed sleep either makes the test slow or makes it flaky on a loaded machine.
 */
public class ShutdownOperationTest {

	private static final long TIMEOUT_MS = 10000;

	@After
	public void clearGlobalFlag() {
		// begin() clears the global stop flag; leaving it set would fail every later test.
		Shutdown.begin();
	}

	@Test
	public void aRunIsListedWhileItRunsAndDroppedWhenItEnds() throws Exception {
		AtomicBoolean release = new AtomicBoolean(false);
		Thread plugin = startRun("listed-run", release, null);
		try {
			Shutdown.Operation operation = awaitOperation("listed-run");
			assertTrue("listed while running", operation.isRunning());
			assertFalse(operation.isCancelled());
			assertEquals("listed-run", operation.getName());
		} finally {
			release.set(true);
			plugin.join(TIMEOUT_MS);
		}
		awaitGone("listed-run");
	}

	@Test
	public void terminatingOneRunLeavesTheOthersAlone() throws Exception {
		AtomicBoolean releaseA = new AtomicBoolean(false);
		AtomicBoolean releaseB = new AtomicBoolean(false);
		AtomicBoolean stoppedA = new AtomicBoolean(false);
		AtomicBoolean stoppedB = new AtomicBoolean(false);
		Thread pluginA = startRun("run-A", releaseA, stoppedA);
		Thread pluginB = startRun("run-B", releaseB, stoppedB);
		try {
			Shutdown.Operation a = awaitOperation("run-A");
			awaitOperation("run-B");

			Shutdown.cancel(a);

			// A unwinds on its own, without its caller having to abandon it.
			pluginA.join(TIMEOUT_MS);
			assertFalse("A's plugin thread must return", pluginA.isAlive());
			assertTrue("A saw the stop", stoppedA.get());
			awaitGone("run-A");

			/* The whole point: B is still going, and still says so. A global flag would have
			 * taken it down with A. */
			assertFalse("B must not have been stopped", stoppedB.get());
			assertTrue("B must still be listed", awaitOperation("run-B").isRunning());
		} finally {
			releaseA.set(true);
			releaseB.set(true);
			pluginA.join(TIMEOUT_MS);
			pluginB.join(TIMEOUT_MS);
		}
		awaitGone("run-B");
	}

	@Test
	public void cancellationReachesTheWorkerThreadsTheRunStarted() throws Exception {
		/* A run creates pools of its own - chunk writers, plane readers - and those threads
		 * have to stop with it. They inherit the run through an InheritableThreadLocal, so
		 * none of them has to know it is part of one. */
		final AtomicBoolean release = new AtomicBoolean(false);
		final AtomicReference<Boolean> childSawStop = new AtomicReference<Boolean>(null);
		final AtomicBoolean childReady = new AtomicBoolean(false);

		Thread plugin = new Thread(new Runnable() {
			@Override public void run() {
				Shutdown.runCancellable("run-with-children", new Runnable() {
					@Override public void run() {
						Thread child = new Thread(new Runnable() {
							@Override public void run() {
								childReady.set(true);
								while (!Shutdown.stopping() && !release.get()) Thread.yield();
								childSawStop.set(Boolean.valueOf(Shutdown.stopping()));
							}
						}, "child-of-run");
						child.setDaemon(true);
						child.start();
						try { child.join(TIMEOUT_MS); } catch (InterruptedException stop) { }
					}
				});
			}
		}, "plugin-with-children");
		plugin.setDaemon(true);
		plugin.start();

		try {
			Shutdown.Operation operation = awaitOperation("run-with-children");
			awaitTrue(childReady, "the child thread to start");

			Shutdown.cancel(operation);
			plugin.join(TIMEOUT_MS);

			assertEquals("the child inherited the cancellation",
					Boolean.TRUE, childSawStop.get());
		} finally {
			release.set(true);
			plugin.join(TIMEOUT_MS);
		}
	}

	@Test
	public void cancellingSomethingAlreadyFinishedIsHarmless() throws Exception {
		AtomicBoolean release = new AtomicBoolean(true);	// returns immediately
		Thread plugin = startRun("brief-run", release, null);
		Shutdown.Operation operation = null;
		// It may already be gone; either way there must be no exception and no global effect.
		for (long deadline = System.currentTimeMillis() + 1000;
				operation == null && System.currentTimeMillis() < deadline; ) {
			operation = find("brief-run");
		}
		plugin.join(TIMEOUT_MS);
		if (operation != null) {
			Shutdown.cancel(operation);
			assertFalse("a finished run is not running", operation.isRunning());
		}
		Shutdown.cancel(null);
		assertFalse("cancelling one run must not set the global flag", Shutdown.stopping());
	}


	// ---- helpers ---------------------------------------------------------------------

	/**			A run that spins until it is cancelled or released
	 * <p>		A spin rather than a sleep because cancelling interrupts the worker, and a
	 * 			sleep would turn that into an InterruptedException on a different line than
	 * 			the one under test.
	 */
	private Thread startRun(final String name, final AtomicBoolean release,
			final AtomicBoolean stopped) {
		Thread plugin = new Thread(new Runnable() {
			@Override public void run() {
				Shutdown.runCancellable(name, new Runnable() {
					@Override public void run() {
						while (!Shutdown.stopping() && !release.get()) Thread.yield();
						if (stopped != null && Shutdown.stopping()) stopped.set(true);
					}
				});
			}
		}, "plugin-" + name);
		plugin.setDaemon(true);
		plugin.start();
		return plugin;
	}

	private static Shutdown.Operation find(String name) {
		List<Shutdown.Operation> running = Shutdown.running();
		for (Shutdown.Operation operation : running)
			if (name.equals(operation.getName())) return operation;
		return null;
	}

	private static Shutdown.Operation awaitOperation(String name) {
		long deadline = System.currentTimeMillis() + TIMEOUT_MS;
		while (System.currentTimeMillis() < deadline) {
			Shutdown.Operation found = find(name);
			if (found != null) return found;
			Thread.yield();
		}
		throw new AssertionError("timed out waiting for " + name + " to be listed");
	}

	private static void awaitGone(String name) {
		long deadline = System.currentTimeMillis() + TIMEOUT_MS;
		while (System.currentTimeMillis() < deadline) {
			if (find(name) == null) return;
			Thread.yield();
		}
		throw new AssertionError("timed out waiting for " + name + " to be unregistered");
	}

	private static void awaitTrue(AtomicBoolean flag, String what) {
		long deadline = System.currentTimeMillis() + TIMEOUT_MS;
		while (System.currentTimeMillis() < deadline) {
			if (flag.get()) return;
			Thread.yield();
		}
		throw new AssertionError("timed out waiting for " + what);
	}
}
