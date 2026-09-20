package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.util.List;

import org.junit.Test;

import ij.ImagePlus;
import ij.process.ShortProcessor;

/**
 * The TIFF viewer path against a real Deskew Batch result folder.
 *
 * <p>Skipped unless {@code -Dopm.test.tiff=<result folder>} names one, like the OME-Zarr
 * real-data test. What it checks is what a synthetic fixture cannot: that production file
 * names group into one dataset, that the ImageJ description production writes carries the C
 * and Z this reads back, and that a region read returns the pixels the whole-plane read does
 * on files of production size.
 */
public class TiffResultRealDataTest {

	private static File folder() {
		String path = System.getProperty("opm.test.tiff", "");
		return path.isEmpty() ? null : new File(path);
	}

	@Test
	public void aRealResultFolderReadsAsOneDataset() throws Exception {
		File root = folder();
		assumeTrue("set -Dopm.test.tiff to a result folder", root != null && root.isDirectory());

		List<TiffResultDataset> datasets = TiffResultDataset.discover(root);
		assertTrue("at least one acquisition under " + root, !datasets.isEmpty());
		TiffResultDataset dataset = datasets.get(0);
		System.out.println("TIFF dataset: " + dataset.getDisplayName());
		System.out.println("  volume: " + (dataset.hasVolume()
				? dataset.getView(TiffResultDataset.VOLUME).frameCount() + " time points" : "none"));
		for (String projection : dataset.getAvailableProjections())
			System.out.println("  " + projection + ": "
					+ dataset.getView(projection).frameCount() + " time points");

		assertTrue("a deskew result has a volume or a projection",
				dataset.hasVolume() || !dataset.getAvailableProjections().isEmpty());

		String viewKey = dataset.hasVolume()
				? TiffResultDataset.VOLUME : dataset.getAvailableProjections().get(0);
		TiffResultDataset.Layout layout = dataset.getView(viewKey).getLayout();
		System.out.println("  layout " + layout.width + "x" + layout.height + ", C="
				+ layout.channels + ", Z=" + layout.slices + ", voxel " + layout.pixelWidth
				+ "/" + layout.pixelHeight + "/" + layout.pixelDepth + " " + layout.unit);

		ImagePlus view = TiffResultView.openVirtual(dataset, viewKey, new TiffResultView.Options());
		try {
			assertEquals(layout.channels, view.getNChannels());
			assertEquals(layout.slices, view.getNSlices());
			assertEquals(dataset.getView(viewKey).frameCount(), view.getNFrames());
		} finally { view.close(); }
	}

	/** A region read of a production plane is the crop of the whole-plane read, and faster. */
	@Test
	public void aRegionOfARealPlaneMatchesTheWholePlane() throws Exception {
		File root = folder();
		assumeTrue("set -Dopm.test.tiff to a result folder", root != null && root.isDirectory());
		List<TiffResultDataset> datasets = TiffResultDataset.discover(root);
		assumeTrue("no datasets found", !datasets.isEmpty());
		TiffResultDataset dataset = datasets.get(0);
		String viewKey = dataset.hasVolume()
				? TiffResultDataset.VOLUME : dataset.getAvailableProjections().get(0);
		TiffResultDataset.Layout layout = dataset.getView(viewKey).getLayout();

		int width = Math.min(64, layout.width);
		int height = Math.min(64, layout.height);
		int x = Math.max(0, layout.width / 2 - width / 2);
		int y = Math.max(0, layout.height / 2 - height / 2);
		int zEnd = Math.min(layout.slices, 4);

		File file = dataset.getView(viewKey).frame(0).file;
		FastTiffReader.PlaneReader reader = new FastTiffReader.PlaneReader(file);
		short[] whole;
		long wholeNanos = System.nanoTime();
		try {
			whole = reader.readPixels(0);
			wholeNanos = System.nanoTime() - wholeNanos;
		} finally { reader.close(); }

		TiffResultView.Options options = new TiffResultView.Options();
		options.bounds = new OmeZarrView.Bounds(x, y, width, height, 0, zEnd);
		options.outputChannelCount = 1;
		long regionNanos = System.nanoTime();
		ImagePlus region = TiffResultView.openMaterialised(dataset, viewKey, options, 0, 1);
		regionNanos = System.nanoTime() - regionNanos;
		try {
			assertEquals(width, region.getWidth());
			assertEquals(height, region.getHeight());
			assertEquals(1, region.getNChannels());
			assertEquals(zEnd, region.getNSlices());
			ShortProcessor plane = (ShortProcessor) region.getStack()
					.getProcessor(region.getStackIndex(1, 1, 1));
			for (int row = 0; row < height; row++)
				for (int column = 0; column < width; column++)
					assertEquals("row " + row + " column " + column,
							whole[(y + row) * layout.width + x + column],
							(short) plane.get(column, row));
			System.out.println("  whole plane " + (wholeNanos / 1e6) + " ms; " + width + "x"
					+ height + " region over " + zEnd + " Z " + (regionNanos / 1e6) + " ms");
		} finally { region.close(); }
	}
}
