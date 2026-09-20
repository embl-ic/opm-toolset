package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests for the durable record of what a live run has finished.
 *
 * <p>This is what makes three separate problems one mechanism: resuming after Fiji is
 * restarted, retrying a volume that failed, and joining an acquisition that is already
 * running. Each of those is a question about what is already done, so each of them is
 * tested here.
 */
public class ProcessedManifestTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	/** A file that has not been seen is not done. */
	@Test
	public void unknownFilesAreNotDone() throws IOException {
		ProcessedManifest manifest = new ProcessedManifest(folder.getRoot());
		assertFalse(manifest.isDone(folder.newFile("never-seen.tif")));
		assertFalse("null is not done", manifest.isDone(null));
		assertEquals(0, manifest.doneCount());
	}

	/** Marking a file done is remembered immediately. */
	@Test
	public void marksFilesDone() throws IOException {
		File volume = write(folder.newFile("volume.tif"), 128);
		ProcessedManifest manifest = new ProcessedManifest(folder.getRoot());

		assertFalse(manifest.isDone(volume));
		manifest.markDone(volume);
		assertTrue(manifest.isDone(volume));
		assertEquals(1, manifest.doneCount());
	}

	/**
	 * The record has to survive the process that wrote it.
	 *
	 * <p>This is the resume case: Fiji is restarted mid-acquisition and must not reprocess
	 * everything it had already finished.
	 */
	@Test
	public void survivesReopening() throws IOException {
		File first = write(folder.newFile("a.tif"), 64);
		File second = write(folder.newFile("b.tif"), 64);

		ProcessedManifest before = new ProcessedManifest(folder.getRoot());
		before.markDone(first);

		ProcessedManifest after = new ProcessedManifest(folder.getRoot());
		assertTrue("a was finished before the restart", after.isDone(first));
		assertFalse("b was not", after.isDone(second));
		assertEquals(1, after.doneCount());
	}

	/**
	 * A failure must not count as done.
	 *
	 * <p>This is the retry case: one truncated file or one busy GPU should cost a retry,
	 * not a missing timepoint in the series.
	 */
	@Test
	public void failuresAreRecordedButNotTreatedAsDone() throws IOException {
		File volume = write(folder.newFile("failed.tif"), 100);
		ProcessedManifest manifest = new ProcessedManifest(folder.getRoot());

		manifest.markFailed(volume);
		assertFalse("a failed file must be offered again", manifest.isDone(volume));
		assertEquals(0, manifest.doneCount());

		manifest.markDone(volume);
		assertTrue("the retry succeeded", manifest.isDone(volume));

		ProcessedManifest reopened = new ProcessedManifest(folder.getRoot());
		assertTrue("the later success wins over the earlier failure", reopened.isDone(volume));
	}

	/** A file replaced after processing is a different file, and must be offered again. */
	@Test
	public void changedFilesAreNotConsideredDone() throws IOException {
		File volume = write(folder.newFile("changed.tif"), 100);
		ProcessedManifest manifest = new ProcessedManifest(folder.getRoot());
		manifest.markDone(volume);
		assertTrue(manifest.isDone(volume));

		write(volume, 250);							// re-acquired, different content
		assertFalse("a file that changed on disk is not the one that was processed",
				manifest.isDone(volume));
	}

	/** The manifest is written where the caller asked, and creates the folder if needed. */
	@Test
	public void writesIntoAMissingFolder() throws IOException {
		File target = new File(folder.getRoot(), "results/deskew");
		ProcessedManifest manifest = new ProcessedManifest(target);
		manifest.markDone(write(folder.newFile("v.tif"), 32));

		assertTrue("manifest file created", manifest.getFile().isFile());
		assertEquals(ProcessedManifest.FILE_NAME, manifest.getFile().getName());
		assertTrue(new ProcessedManifest(target).doneCount() > 0);
	}

	/** A damaged manifest must degrade to "nothing recorded", never refuse to run. */
	@Test
	public void toleratesACorruptManifest() throws IOException {
		File volume = write(folder.newFile("v.tif"), 40);
		File manifestFile = new File(folder.getRoot(), ProcessedManifest.FILE_NAME);
		OutputStream out = new FileOutputStream(manifestFile);
		out.write("#path\tsize\tmodified\tstatus\tfinished\nnot a real line\nalso\tbroken\n".getBytes("UTF-8"));
		out.close();

		ProcessedManifest manifest = new ProcessedManifest(folder.getRoot());
		assertEquals(0, manifest.doneCount());
		assertFalse(manifest.isDone(volume));
		manifest.markDone(volume);
		assertTrue("still usable after a corrupt file", manifest.isDone(volume));
	}

	@Test
	public void completionIsKeptSeparatelyForTiffAndZarr() throws IOException {
		File volume = write(folder.newFile("cross-format.tif"), 96);
		ProcessedManifest manifest = new ProcessedManifest(folder.getRoot());
		manifest.markDone(volume, Parameter.FORMAT_TIFF);

		assertTrue(manifest.isDone(volume, Parameter.FORMAT_TIFF));
		assertFalse("a TIFF run must not suppress a later OME-Zarr run",
				manifest.isDone(volume, Parameter.FORMAT_ZARR));
		assertFalse(manifest.isDone(volume, Parameter.FORMAT_BOTH));

		manifest.markDone(volume, Parameter.FORMAT_ZARR);
		ProcessedManifest reopened = new ProcessedManifest(folder.getRoot());
		assertTrue(reopened.isDone(volume, Parameter.FORMAT_TIFF));
		assertTrue(reopened.isDone(volume, Parameter.FORMAT_ZARR));
		assertTrue("two separate successful runs also satisfy a later both-format run",
				reopened.isDone(volume, Parameter.FORMAT_BOTH));
	}

	@Test
	public void legacyRowsNeverSatisfyAFormatAwareSkip() throws IOException {
		File volume = write(folder.newFile("legacy.tif"), 72);
		File manifestFile = new File(folder.getRoot(), ProcessedManifest.FILE_NAME);
		OutputStream out = new FileOutputStream(manifestFile);
		String row = "#path\tsize\tmodified\tstatus\tfinished\n"
				+ volume.getAbsolutePath() + "\t" + volume.length() + "\t"
				+ volume.lastModified() + "\tOK\t1234\n";
		out.write(row.getBytes("UTF-8"));
		out.close();

		ProcessedManifest manifest = new ProcessedManifest(folder.getRoot());
		assertEquals("legacy completions remain visible in the startup count", 1, manifest.doneCount());
		assertFalse(manifest.isDone(volume, Parameter.FORMAT_TIFF));
		assertFalse(manifest.isDone(volume, Parameter.FORMAT_ZARR));
	}

	@Test
	public void aFailedSecondFormatDoesNotForgetTheFirst() throws IOException {
		File volume = write(folder.newFile("partial.tif"), 88);
		ProcessedManifest manifest = new ProcessedManifest(folder.getRoot());
		manifest.markDone(volume, Parameter.FORMAT_TIFF);
		manifest.markFailed(volume, Parameter.FORMAT_ZARR);

		ProcessedManifest reopened = new ProcessedManifest(folder.getRoot());
		assertTrue(reopened.isDone(volume, Parameter.FORMAT_TIFF));
		assertFalse(reopened.isDone(volume, Parameter.FORMAT_ZARR));
	}

	private static File write(File file, int bytes) throws IOException {
		OutputStream out = new FileOutputStream(file);
		try {
			out.write(new byte[bytes]);
		} finally {
			out.close();
		}
		// make the modification time unambiguous for the change detection test
		file.setLastModified(1000000000000L + bytes);
		return file;
	}
}
