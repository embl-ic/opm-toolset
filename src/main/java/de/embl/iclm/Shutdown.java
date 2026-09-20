package de.embl.iclm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

import ij.IJ;
import ij.ImageJ;

/**
 * The one place that answers "should this long operation stop now?".
 *
 * <p>A Deskew Batch run used to be unstoppable, and worse, it kept the whole JVM alive after
 * Fiji had closed. Two independent reasons for that, both fixed here:
 *
 * <ul>
 * <li>ImageJ runs a menu command on an {@link ij.Executer} thread, which is <em>not</em> a
 *     daemon thread, and Fiji leaves {@code exitWhenQuitting} false so that quitting disposes
 *     the main window and then simply waits for every non-daemon thread to end. A batch loop
 *     that never asks whether it should stop therefore holds the process open with no window
 *     left to close it from.</li>
 * <li>The parallel TIFF and Zarr chunk pools were created with the default thread factory,
 *     which also produces non-daemon threads. A pool that is not shut down on every path
 *     keeps its idle workers - and the JVM - alive on its own.</li>
 * </ul>
 *
 * <p>{@link #stopping()} is the cooperative signal: loops call it between units of work and
 * unwind. It is deliberately derived from state ImageJ already maintains rather than from a
 * new stop button - {@link ImageJ#quitting()} flips to true as the first statement of the
 * quit thread, before any window is closed, and {@code Escape} is the abort gesture every
 * other ImageJ command already honours.
 *
 * <p>Nothing here reports true when {@link IJ#getInstance()} is null. A headless build or a
 * unit test has no ImageJ to quit, and must not have its work cancelled by this class.
 */
public final class Shutdown {

	/** Set by {@link #request()}; cleared by {@link #begin()}. */
	private static volatile boolean requested = false;

	private static final AtomicLong SEQUENCE = new AtomicLong();
	private static final AtomicLong OPERATIONS = new AtomicLong();

	/** Every cancellable operation currently in flight, by id. */
	private static final Map<Long, Operation> RUNNING = new ConcurrentHashMap<Long, Operation>();

	/**
	 * The operation the calling thread belongs to, if any.
	 * <p>
	 * Inheritable on purpose. A run creates worker pools of its own, and a thread inherits this
	 * when it is created, so cancelling the operation reaches the chunk writers and plane
	 * readers it started without any of them having to know they are part of it.
	 */
	private static final InheritableThreadLocal<Operation> CURRENT =
			new InheritableThreadLocal<Operation>();

	private Shutdown () { }

	/**			Start a cancellable operation
	 * <p>		Clears both this class's own flag and ImageJ's Escape flag, so a stray Escape
	 * 			pressed long before the command was started cannot abort it on its first
	 * 			checkpoint. Call once, at the top of an operation, not inside its loop.
	 */
	public static void begin () {
		requested = false;
		try {
			IJ.resetEscape();
		} catch (Throwable headless) {
			// no ImageJ to reset; nothing to clear
		}
	}

	/** Ask every cancellable operation in this JVM to unwind at its next checkpoint. */
	public static void request () {
		requested = true;
	}

	/**			Whether the current operation should stop
	 * <p>		True when this class was asked to stop, when the calling thread was
	 * 			interrupted, when Escape was pressed, or when Fiji is quitting or already gone.
	 *
	 * @return					: true when the caller should unwind as soon as it safely can
	 */
	public static boolean stopping () {
		// This operation asked to stop, whatever the rest of the JVM is doing.
		Operation operation = CURRENT.get();
		if (operation != null && operation.cancelled) return true;
		if (requested) return true;
		if (Thread.currentThread().isInterrupted()) return true;
		ImageJ instance;
		try {
			instance = IJ.getInstance();
		} catch (Throwable headless) {
			return false;
		}
		// No GUI: a headless conversion or a unit test is never "quitting".
		if (instance == null) return false;
		if (instance.quitting()) return true;
		if (!instance.isDisplayable()) return true;		// dispose() has already run
		return IJ.escapePressed();
	}

	/** Why the operation is stopping, for one line in a log. */
	public static String reason () {
		Operation operation = CURRENT.get();
		if (operation != null && operation.cancelled) return "it was terminated from Batch Processing > Terminate";
		if (requested) return "a stop was requested";
		if (Thread.currentThread().isInterrupted()) return "the processing thread was interrupted";
		ImageJ instance = null;
		try {
			instance = IJ.getInstance();
		} catch (Throwable headless) {
			// fall through to the generic answer
		}
		if (instance != null && (instance.quitting() || !instance.isDisplayable()))
			return "Fiji is quitting";
		if (IJ.escapePressed()) return "Escape was pressed";
		return "the operation was cancelled";
	}

	/**
	 * One cancellable operation, for as long as it runs.
	 * <p>
	 * The reason this exists at all is that {@link #request()} is a single global flag: it
	 * stops everything in the JVM, which is the wrong answer when two runs are in flight and
	 * only one of them is the mistake. An operation is therefore also cancellable on its own.
	 * <p>
	 * Cancelling is cooperative, exactly as pressing Escape is. The flag is set and the worker
	 * interrupted; the run unwinds at its next {@link Shutdown#stopping()} checkpoint, which is
	 * between units of work - after the current plane, chunk or time point has been written.
	 * That is the entire point: killing Fiji mid-write leaves a partial chunk or a truncated
	 * TIFF behind, and this does not.
	 */
	public static final class Operation {
		private final long id;
		private final String name;
		private final long startedNanos;
		private final Thread worker;
		private volatile boolean cancelled;
		/* Explicit rather than Thread.isAlive(). The operation is registered before its worker
		 * is started, so isAlive() reports a run that has not begun yet as already over. */
		private volatile boolean finished;

		private Operation (long id, String name, Thread worker) {
			this.id = id;
			this.name = name;
			this.worker = worker;
			this.startedNanos = System.nanoTime();
		}

		public long getId () { return id; }
		public String getName () { return name; }
		public boolean isCancelled () { return cancelled; }
		/** False once the run has actually unwound, which can lag the cancellation. */
		public boolean isRunning () { return !finished; }
		public long elapsedSeconds () { return (System.nanoTime() - startedNanos) / 1000000000L; }

		/** What a list of running operations shows for this one. */
		@Override
		public String toString () {
			String state = finished ? "finished"
					: cancelled ? "stopping at the next safe point"
					: "running " + describeDuration ( elapsedSeconds() );
			return name + "  -  " + state;
		}
	}

	/** "4 min 12 s", or "12 s"; short enough for a list row. */
	private static String describeDuration (
			long seconds
			) {
		if (seconds < 60) return seconds + " s";
		long minutes = seconds / 60;
		if (minutes < 60) return minutes + " min " + (seconds % 60) + " s";
		return (minutes / 60) + " h " + (minutes % 60) + " min";
	}

	/**			Every cancellable operation currently registered, oldest first
	 * <p>		A snapshot: an operation can finish while the list is being looked at, which is
	 * 			why {@link #cancel} on one that has already ended is harmless rather than an
	 * 			error.
	 */
	public static List<Operation> running () {
		List<Operation> result = new ArrayList<Operation> ( RUNNING.values() );
		Collections.sort ( result, new Comparator<Operation>() {
			@Override
			public int compare (Operation a, Operation b) {
				return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
			}
		});
		return result;
	}

	/**			Ask one operation to stop, without touching any other
	 * <p>		Both halves matter. The flag is what a loop polling {@link #stopping()} sees,
	 * 			and the interrupt is what releases a worker already blocked in a read, a sleep
	 * 			or an {@code awaitTermination}. Neither alone is enough: an interrupt is cleared
	 * 			by the first {@code InterruptedException} that is caught, and a flag is never
	 * 			seen by a thread that is not running.
	 */
	public static void cancel (
			Operation operation
			) {
		if (operation == null) return;
		operation.cancelled = true;
		operation.worker.interrupt();
	}

	/**			A thread factory whose threads never hold the JVM open
	 * <p>		Every worker pool in the toolset uses this. A pool left running by an error
	 * 			path is then a leak of one idle thread, not a Fiji that cannot be quit.
	 *
	 * @param name				: base name; a sequence number is appended per thread
	 */
	public static ThreadFactory daemonThreads (
			final String name
			) {
		return new ThreadFactory() {
			@Override
			public Thread newThread (Runnable work) {
				Thread thread = new Thread ( work, name + "-" + SEQUENCE.incrementAndGet() );
				thread.setDaemon ( true );
				return thread;
			}
		};
	}

	/** A started daemon thread, for the listeners and watchers that outlive one call. */
	public static Thread daemon (
			Runnable work,
			String name
			) {
		Thread thread = new Thread ( work, name );
		thread.setDaemon ( true );
		return thread;
	}

	/**			Run one long operation so that quitting Fiji is never blocked by it
	 * <p>		The body runs on a daemon thread and the calling plugin thread only waits for
	 * 			it, releasing that wait as soon as {@link #stopping()} turns true. The plugin
	 * 			thread - the non-daemon one - therefore always ends promptly, and whatever the
	 * 			body is still doing dies with the JVM instead of outliving the application.
	 *
	 * @param name				: thread name, used in logs and stack dumps
	 * @param body				: the work; it should also poll {@link #stopping()} itself
	 * <p>
	 * @return					: true when the body ran to completion
	 */
	public static boolean runCancellable (
			String name,
			final Runnable body
			) {
		begin();
		final Throwable[] failure = new Throwable[1];
		final Operation[] operation = new Operation[1];
		Thread worker = daemon ( new Runnable() {
			@Override
			public void run () {
				/* Set here rather than before the thread starts: an InheritableThreadLocal is
				 * copied when a thread is created, so setting it on the plugin thread would
				 * have made every later thread that plugin creates part of this operation. */
				CURRENT.set ( operation[0] );
				try {
					body.run();
				} catch (Throwable t) {
					failure[0] = t;
				} finally {
					operation[0].finished = true;
					RUNNING.remove ( operation[0].id );
				}
			}
		}, name );
		operation[0] = new Operation ( OPERATIONS.incrementAndGet(), name, worker );
		RUNNING.put ( operation[0].id, operation[0] );
		try {
			worker.start();
		} catch (Throwable notStarted) {
			operation[0].finished = true;
			RUNNING.remove ( operation[0].id );
			throw notStarted instanceof RuntimeException
					? (RuntimeException) notStarted : new RuntimeException ( notStarted );
		}
		while (worker.isAlive()) {
			if (stopping()) {
				worker.interrupt();				// let a blocking read or sleep unwind too
				IJ.log ( "OPM: " + name + " is stopping because " + reason() + "." );
				return false;
			}
			try {
				worker.join ( 200 );
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				worker.interrupt();
				return false;
			}
		}
		if (failure[0] != null) {
			IJ.log ( "OPM: " + name + " failed: " + failure[0] );
			failure[0].printStackTrace();
		}
		return failure[0] == null;
	}
}
