package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests for how the live listener decides which files exist and which to pick up.
 *
 * <p>Every acquisition in the sample tree writes its volumes into a per-run sub-folder -
 * <code>mitosis-test-timelapse_0/</code>, <code>test_6/</code> - so pointing the watcher at
 * the folder data is "saved to" means pointing it at a parent. The previous implementation
 * looked only at the top level, with a flat listFiles() and a non-recursive registration,
 * so that entirely reasonable setup silently produced nothing at all. These tests hold the
 * recursive behaviour in place.
 */
public class LiveDiscoveryTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	/** The case that used to fail silently: volumes one level down from the watch folder. */
	@Test
	public void findsVolumesInAcquisitionSubfolders() throws IOException {
		File run = folder.newFolder("mitosis-test-timelapse_0");
		touch(new File(run, "vol_Time000001.tiff"));
		touch(new File(run, "vol_Time000002.tiff"));
		touch(new File(run, "ExperimentalParameters.txt"));

		List<File> recursive = collect(true, "");
		assertEquals("both volumes found below the watch folder", 2, recursive.size());

		List<File> flat = collect(false, "");
		assertTrue("a non-recursive scan sees nothing here, which is the trap", flat.isEmpty());
	}

	/** Nested runs, and a depth bound so a pathological tree cannot spin. */
	@Test
	public void descendsThroughNestedFolders() throws IOException {
		File deep = folder.newFolder("day1", "sample_A", "run_0");
		touch(new File(deep, "a.tif"));
		touch(new File(folder.getRoot(), "top.tif"));

		List<File> found = collect(true, "");
		assertEquals("top level and nested volumes", 2, found.size());
	}

	/** Files at the top level are still found when recursion is off. */
	@Test
	public void findsTopLevelVolumesWithoutRecursion() throws IOException {
		touch(new File(folder.getRoot(), "a.tif"));
		touch(new File(folder.getRoot(), "b.tiff"));
		assertEquals(2, collect(false, "").size());
	}

	/** Only TIFFs, and only ones matching the keyword filter. */
	@Test
	public void appliesExtensionAndKeywordFilters() throws IOException {
		File run = folder.newFolder("run_0");
		touch(new File(run, "sample_Channel0001.tiff"));
		touch(new File(run, "sample_Channel0002.tiff"));
		touch(new File(run, "beads_Channel0001.tiff"));
		touch(new File(run, "notes.txt"));
		touch(new File(run, "preview.png"));

		assertEquals("non-TIFFs excluded", 3, collect(true, "").size());
		assertEquals("keyword filter applied", 2, collect(true, "sample").size());
		assertEquals("several keywords are alternatives", 3, collect(true, "sample, beads").size());
		assertEquals("blank keywords ignored", 3, collect(true, " , ").size());
		assertEquals("no match means nothing queued", 0, collect(true, "nothing-like-this").size());
	}

	/** The name test alone, including the cases that must be rejected. */
	@Test
	public void recognisesTiffFiles() throws IOException {
		File tif = touch(new File(folder.getRoot(), "a.tif"));
		File tiff = touch(new File(folder.getRoot(), "b.TIFF"));
		File text = touch(new File(folder.getRoot(), "c.txt"));

		assertTrue(Live2.isTiffNamed(tif, null));
		assertTrue("extension match is case insensitive", Live2.isTiffNamed(tiff, ""));
		assertFalse(Live2.isTiffNamed(text, ""));
		assertFalse("null is not a file", Live2.isTiffNamed(null, ""));
		assertFalse("a folder is not a volume", Live2.isTiffNamed(folder.getRoot(), ""));
		assertFalse("a file that does not exist yet", Live2.isTiffNamed(new File(folder.getRoot(), "gone.tif"), ""));
	}

	/** Backfill plus manifest is the resume path: only what is missing gets queued. */
	@Test
	public void backfillQueuesOnlyWhatTheManifestIsMissing() throws IOException {
		File run = folder.newFolder("run_0");
		File first = touch(new File(run, "t1.tiff"));
		File second = touch(new File(run, "t2.tiff"));
		File third = touch(new File(run, "t3.tiff"));

		ProcessedManifest manifest = new ProcessedManifest(folder.getRoot());
		manifest.markDone(first);
		manifest.markFailed(second);					// failed: must be offered again

		List<File> onDisk = collect(true, "");
		assertEquals(3, onDisk.size());

		List<File> toQueue = new ArrayList<File>();
		for (File file : onDisk) if (!manifest.isDone(file)) toQueue.add(file);

		assertEquals("the finished one is skipped, the failed one is retried", 2, toQueue.size());
		assertTrue(toQueue.contains(second));
		assertTrue(toQueue.contains(third));
		assertFalse(toQueue.contains(first));
	}

	private List<File> collect(boolean recursive, String keywords) {
		List<File> out = new ArrayList<File>();
		Live2.collectTiffs(folder.getRoot(), recursive, keywords, out, 0);
		return out;
	}

	private static File touch(File file) throws IOException {
		File parent = file.getParentFile();
		if (parent != null) parent.mkdirs();
		OutputStream out = new FileOutputStream(file);
		try {
			out.write(new byte[16]);
		} finally {
			out.close();
		}
		return file;
	}
}
