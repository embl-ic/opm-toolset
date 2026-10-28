package de.embl.iclm;

import java.io.File;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The Online Deskew View's watch on its acquisition folder (M3-A; joint contracts C2.3, C2.7): an
 * operating-system folder watch on a {@link Shutdown} daemon thread, which turns each event into a
 * hint about one file and nothing more. A hint is never taken as a file being complete, or even as
 * existing: the catalogue looks at the file itself ({@link RawAcquisition#hint}). An event the
 * operating system dropped - its queue overflowed - or a watch it stopped is reported as a call to
 * look at the whole folder, and the catalogue's periodic look repairs any event missed without one.
 * Nothing here opens, reads or waits on a file.
 *
 * <p>{@link #close()} cancels the watch, closes the watch service - which ends the thread's wait -
 * and waits a bounded time for the thread to end; it is called off the event thread.
 */
final class RawFolderWatcher implements AutoCloseable {

	private final File folder;
	private final WatchService service;
	private final WatchKey key;
	private final Consumer<File> hint;
	private final Runnable lookAgain;
	private final Thread thread;
	private final AtomicLong events = new AtomicLong(), overflows = new AtomicLong();
	private volatile boolean closed;

	private RawFolderWatcher(File folder, Consumer<File> hint, Runnable lookAgain) throws IOException {
		this.folder = folder;
		this.hint = hint;
		this.lookAgain = lookAgain;
		this.service = FileSystems.getDefault().newWatchService();
		try {
			this.key = folder.toPath().register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY,
					StandardWatchEventKinds.ENTRY_DELETE);
		} catch (IOException | RuntimeException e) {
			service.close();
			throw e;
		}
		this.thread = Shutdown.daemon(this::run, "OPM-online-deskew-watch");
	}

	/**
	 * Watch {@code folder}: each event names a file to {@code hint}; {@code lookAgain} is called when events may have been
	 * lost and the whole folder has to be looked at.
	 *
	 * @throws IOException when the folder cannot be watched - a share without notifications, say; the periodic look is then all
	 * 					   there is
	 */
	static RawFolderWatcher start(File folder, Consumer<File> hint, Runnable lookAgain) throws IOException {
		RawFolderWatcher w = new RawFolderWatcher(folder, hint, lookAgain);
		w.thread.start();
		return w;
	}

	private void run() {
		while (!closed) {
			WatchKey k;
			try {
				k = service.take();
			} catch (InterruptedException | ClosedWatchServiceException ended) {
				return;
			}
			for (WatchEvent<?> e : k.pollEvents()) {
				if (e.kind() == StandardWatchEventKinds.OVERFLOW) {
					overflows.incrementAndGet();
					lookAgain.run();
					continue;
				}
				events.incrementAndGet();
				Object context = e.context();
				if (context instanceof Path) hint.accept(new File(folder, ((Path) context).toString()));
			}
			if (!k.reset()) {		// the folder itself is gone or can no longer be watched
				if (!closed) lookAgain.run();
				return;
			}
		}
	}

	/** Events seen, and the times the operating system said it dropped some. */
	long events() {
		return events.get();
	}

	long overflows() {
		return overflows.get();
	}

	/** Whether the watch thread is still running. */
	boolean isRunning() {
		return thread.isAlive();
	}

	/** Stop watching; true when the watch thread ended within {@code millis}. Off the event thread. */
	boolean close(long millis) {
		closed = true;
		key.cancel();
		try {
			service.close();
		} catch (IOException e) {
			// closing what is closing: nothing more to release
		}
		try {
			thread.join(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return !thread.isAlive();
	}

	@Override
	public void close() {
		close(OnlineDeskewSession.STOP_DEADLINE_MS);
	}
}
