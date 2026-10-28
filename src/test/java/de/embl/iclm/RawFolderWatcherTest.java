package de.embl.iclm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * M3-A's folder watch (C2.3, C2.7): events become hints about files and nothing more; a folder that cannot be watched any
 * longer asks for a whole look; closing releases the watch and its thread. Events are the operating system's to deliver, so
 * each wait here is generous and nothing is timed.
 */
public class RawFolderWatcherTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static void await(String what, java.util.function.BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 15000;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) throw new AssertionError("not within 15 s: " + what);
			Thread.sleep(20);
		}
	}

	@Test
	public void eventsBecomeHintsAndClosingReleasesTheWatch() throws Exception {
		Set<File> hinted = ConcurrentHashMap.newKeySet();
		AtomicInteger looks = new AtomicInteger();
		RawFolderWatcher w = RawFolderWatcher.start(folder.getRoot(), hinted::add, looks::incrementAndGet);
		try {
			assertTrue(w.isRunning());
			File created = new File(folder.getRoot(), "w_Time000001_Channel0001_Frames_1_2.tiff");
			Files.write(created.toPath(), new byte[] { 1, 2, 3 });
			await("the new file named", () -> hinted.contains(created));
			Files.write(created.toPath(), new byte[] { 4, 5, 6, 7 });
			Files.delete(created.toPath());
			File other = new File(folder.getRoot(), "w_Time000002_Channel0001_Frames_1_2.tiff");
			Files.write(other.toPath(), new byte[] { 1 });
			await("the next file named", () -> hinted.contains(other));
			assertTrue(w.events() >= 2);
		} finally {
			assertTrue("the watch thread ended", w.close(5000));
		}
		assertFalse(w.isRunning());
		int before = looks.get();
		File late = new File(folder.getRoot(), "w_Time000003_Channel0001_Frames_1_2.tiff");
		Files.write(late.toPath(), new byte[] { 1 });
		Thread.sleep(300);
		assertFalse("closed: nothing more is named", hinted.contains(late));
		assertTrue(looks.get() == before);
	}

	@Test
	public void aFolderThatGoesAwayAsksForALookAndTheWatchEnds() throws Exception {
		File watched = folder.newFolder("acquisition");
		AtomicInteger looks = new AtomicInteger();
		RawFolderWatcher w = RawFolderWatcher.start(watched, f -> {}, looks::incrementAndGet);
		try {
			Files.delete(watched.toPath());
			await("a look asked for", () -> looks.get() > 0);
			await("the watch thread ended", () -> !w.isRunning());
		} finally {
			w.close(5000);
		}
	}

	@Test(expected = java.io.IOException.class)
	public void aFolderThatCannotBeWatchedSaysSo() throws Exception {
		RawFolderWatcher.start(new File(folder.getRoot(), "missing"), f -> {}, () -> {});
	}
}
