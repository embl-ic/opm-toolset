package de.embl.iclm;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import ij.ImagePlus;
import ij.process.ShortProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TiffResultDiscoveryTest {
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	static File result(File folder, String stem, int t) throws Exception {
		Files.createDirectories(folder.toPath());
		File file = new File(folder, stem + String.format("_Time%06d_Channel0001-deskewed-maxZprojection.tif", t));
		ImagePlus image = new ImagePlus("result", new ShortProcessor(8, 6));
		try { assertTrue(VolumeIO.saveTiff(image, file)); } finally { image.close(); }
		return file;
	}

	static void old(File file) throws Exception {
		Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(
				System.currentTimeMillis() - TiffResultDataset.RECENT_FILE_MILLIS * 2));
	}

	@Test public void rawFilesNeverReachTheCompletionCheck() throws Exception {
		File root = temporary.getRoot();
		Files.write(new File(root, "raw_Time999999_Channel0001.tiff").toPath(), new byte[] { 1 });
		File result = result(root, "sample", 1);
		List<File> checked = new ArrayList<File>();
		List<TiffResultDataset> found = TiffResultDataset.discover(root, file -> {
			assertTrue("raw files must never be opened", file.getName().contains("-deskewed"));
			checked.add(file);
			return TiffCompletionCheck.isReady(file);
		});
		assertEquals(1, found.size());
		assertEquals(java.util.Collections.singletonList(result), checked);
	}

	@Test public void acquisitionAndParentFindBothRunsAndKeepTheirResultRoots() throws Exception {
		File root = temporary.getRoot();
		for (String run : new String[] { "first", "second" }) {
			File acquisition = new File(root, run);
			File results = new File(acquisition, "result");
			File view = new File(results, "maxZ");
			result(view, "sample_Position0001", 1);
			assertEquals(results, TiffResultDataset.discover(acquisition).get(0).getRoot());
			assertEquals(results, TiffResultDataset.discover(view).get(0).getRoot());
		}
		assertEquals(2, TiffResultDataset.discover(root).size());
		result(new File(root, "a/b/c/d"), "tooDeep", 1);
		assertEquals("only three folder levels below the selection", 2, TiffResultDataset.discover(root).size());
	}

	@Test public void storesAndUnnamedZarrGroupsAreOpaqueToBothScans() throws Exception {
		File root = temporary.getRoot();
		for (String marker : new String[] { ".zgroup", ".zarray", "zarr.json" }) {
			File group = new File(root, "group-" + marker.substring(1));
			Files.createDirectories(group.toPath());
			Files.write(new File(group, marker).toPath(), new byte[] { '{', '}' });
			result(group, "decoy", 1);
			Files.createDirectories(new File(group, "chunks/decoy.ome.zarr").toPath());
		}
		File store = new File(root, "run.ome.zarr");
		result(store, "decoy", 1);
		Files.createDirectories(new File(store, "chunks/decoy.ome.zarr").toPath());
		assertTrue(TiffResultDataset.discover(root, file -> { fail("entered Zarr tree: " + file); return false; }).isEmpty());
		assertEquals("only the outer named store is a dataset", 1, OmeZarrDataset.discover(root).size());
	}

	@Test public void equalNamesInDifferentResultsStaySeparateAndHaveDistinctLabels() throws Exception {
		File root = temporary.getRoot();
		result(new File(root, "result/maxZ"), "sample_Position0001", 1);
		result(new File(root, "result_v2/maxZ"), "sample_Position0001", 1);
		List<TiffResultDataset> found = TiffResultDataset.discover(root);
		assertEquals(2, found.size());
		assertEquals(found.get(0).getDisplayName(), found.get(1).getDisplayName());
		List<OpmDataViewer.Entry> entries = OpmDataViewer.groupTiffFamilies(found);
		OpmDataViewer.labelDuplicateEntries(entries, root);
		assertFalse(entries.get(0).toString().equals(entries.get(1).toString()));
		assertTrue(entries.get(0).toString().contains("Position 1"));
		for (TiffResultDataset dataset : found) {
			assertEquals(1, dataset.getView("maxZ").frameCount());
			assertEquals(0, dataset.refresh());
		}
	}

	@Test public void onlyNewestAndRecentFilesAreCheckedAndPartialFilesAreDropped() throws Exception {
		File root = temporary.getRoot();
		File first = result(root, "sample", 1); old(first);
		File oldDamaged = result(root, "sample", 2); Files.write(oldDamaged.toPath(), new byte[] { 1 }); old(oldDamaged);
		File recentDamaged = result(root, "sample", 3); Files.write(recentDamaged.toPath(), new byte[] { 1 });
		File newest = result(root, "sample", 4); Files.write(newest.toPath(), new byte[] { 1 }); old(newest);
		List<File> checked = new ArrayList<File>();
		List<TiffResultDataset> found = TiffResultDataset.discover(root, file -> {
			checked.add(file); return TiffCompletionCheck.isReady(file);
		});
		assertEquals(2, checked.size());
		assertTrue(checked.contains(newest));
		assertTrue(checked.contains(recentDamaged));
		assertFalse(checked.contains(first));
		assertEquals("old damage is deferred until read, as agreed", 2, found.get(0).getView("maxZ").frameCount());
	}

	@Test public void refreshAddsOnlyNewFramesAndKeepsHeldFrames() throws Exception {
		File root = temporary.getRoot();
		File first = result(root, "sample", 1);
		TiffResultDataset dataset = TiffResultDataset.discover(root).get(0);
		result(root, "sample", 2);
		assertEquals(1, dataset.refresh());
		assertEquals(0, dataset.refresh());
		Files.write(first.toPath(), new byte[] { 1 });
		assertEquals(0, dataset.refresh());
		assertEquals(2, dataset.getView("maxZ").frameCount());
	}
}
