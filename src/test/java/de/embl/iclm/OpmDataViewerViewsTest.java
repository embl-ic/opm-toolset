package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The rules the OPM Data Viewer keeps over the windows it opens, pinned without a window.
 *
 * <p>One view per dataset and kind is what stops a live run from appending new time points to a
 * window whose composition the controls no longer describe: a second view of the same kind is a
 * replacement, not a companion. Two datasets still get a view each.
 */
public class OpmDataViewerViewsTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void aSecondViewOfTheSameKindReplacesTheFirst() throws Exception {
		File root = folder.newFolder("run.ome.zarr");
		OpmDataViewer.ViewRegistry views = new OpmDataViewer.ViewRegistry();
		OpmDataViewer.ManagedView stored = view(root, OpmDataViewer.VOLUME_KIND);
		OpmDataViewer.ManagedView whole = view(root, OpmDataViewer.VOLUME_KIND);

		assertNull(views.put(stored));
		assertSame("the view it replaces is handed back to be closed", stored, views.put(whole));
		assertEquals(1, views.size());
		assertSame(whole, views.find(root, OpmDataViewer.VOLUME_KIND));
	}

	@Test
	public void kindsAndDatasetsDoNotReplaceEachOther() throws Exception {
		File first = folder.newFolder("first.ome.zarr");
		File second = folder.newFolder("second.ome.zarr");
		OpmDataViewer.ViewRegistry views = new OpmDataViewer.ViewRegistry();
		views.put(view(first, OpmDataViewer.VOLUME_KIND));
		views.put(view(first, "maxZ"));
		views.put(view(first, "maxY"));
		views.put(view(second, OpmDataViewer.VOLUME_KIND));

		assertEquals(4, views.size());
		assertEquals("growth and rebuilds reach one dataset's views only", 3, views.of(first).size());
		assertEquals(1, views.of(second).size());
		assertEquals("the same folder however it is spelled",
				3, views.of(new File(first, "../first.ome.zarr")).size());
	}

	/** Volume at one time point and volume over all of them are both the volume. */
	@Test
	public void everyVolumeModeIsOneKindAndEachProjectionItsOwn() {
		assertEquals(OpmDataViewer.VOLUME_KIND,
				OpmDataViewer.kindOf(OpmDataViewer.OpenMode.VOLUME_ALL, null));
		assertEquals(OpmDataViewer.VOLUME_KIND,
				OpmDataViewer.kindOf(OpmDataViewer.OpenMode.VOLUME_SINGLE, "maxZ"));
		assertEquals("maxZ", OpmDataViewer.kindOf(OpmDataViewer.OpenMode.PROJECTION, "maxZ"));
		assertEquals("meanZ", OpmDataViewer.kindOf(OpmDataViewer.OpenMode.PROJECTION, "meanZ"));
	}

	/** Only shown views are recorded, so a view without a window is one the user closed. */
	@Test
	public void closedViewsArePrunedAndClearingEmptiesTheRegistry() throws Exception {
		File root = folder.newFolder("run.ome.zarr");
		OpmDataViewer.ViewRegistry views = new OpmDataViewer.ViewRegistry();
		views.put(view(root, "maxZ"));
		views.put(view(root, OpmDataViewer.VOLUME_KIND));
		assertEquals(2, views.prune());
		assertEquals(0, views.size());

		views.put(view(root, "maxZ"));
		assertEquals(1, views.clear().size());
		assertEquals(0, views.size());
	}

	/**
	 * A rebuild keeps the contrast the user set, but only over the same channels: a range tuned
	 * for a left half means nothing once the view is the full width.
	 */
	@Test
	public void contrastCarriesOverOnlyBetweenTheSameChannels() {
		CompositeImage before = composite("[_Channel0001-left, _Channel0001-right]");
		before.getChannelLut(1).min = 100;
		before.getChannelLut(1).max = 900;
		before.getChannelLut(2).min = 7;
		before.getChannelLut(2).max = 70;

		CompositeImage same = composite("[_Channel0001-left, _Channel0001-right]");
		OpmDataViewer.carryContrast(before, same);
		assertEquals(100, same.getChannelLut(1).min, 0);
		assertEquals(900, same.getChannelLut(1).max, 0);
		assertEquals(70, same.getChannelLut(2).max, 0);

		CompositeImage other = composite("[_Channel0001-right, _Channel0001-left]");
		double untouched = other.getChannelLut(1).max;
		OpmDataViewer.carryContrast(before, other);
		assertEquals("a different selection keeps its own range", untouched, other.getChannelLut(1).max, 0);
	}

	/**
	 * A TIFF result opens as written until its channel-setup button defines a virtual overlay,
	 * so the descriptive combo boxes are greyed. What they show must describe the TIFF, not an
	 * OME-Zarr choice: a whole-image TIFF must not read "stored halves as separate channels"
	 * over a full-width image.
	 */
	@Test
	public void aTiffResultsGreyedControlsSayWhatTheTiffHolds() {
		DeskewChannelView whole = DeskewChannelView.of("whole image", false,
				java.util.Collections.<String>emptyList(), BatchChannelOperation.FLIP_RIGHT, true);
		assertEquals("whole: left + right side by side (in the TIFF pixels)",
				OpmDataViewer.writtenComposition(whole, true));
		assertEquals("as written: whole image", OpmDataViewer.writtenComposition(whole, false));
		assertEquals("a result no live run described", "applied when the TIFF was written",
				OpmDataViewer.writtenComposition(null, true));
	}

	/**
	 * Two {@code _ChannelNNNN} series of one acquisition are one entry in the list.
	 *
	 * <p>They are the channels of one acquisition; listing them separately asked the user to
	 * open them one at a time, and made two windows of the same kind compete for one slot in
	 * the view registry. Anything without a channel token, or from another acquisition, stays
	 * an entry of its own, and discovery order is kept.
	 */
	@Test
	public void theChannelSeriesOfOneAcquisitionAreOneEntry() throws Exception {
		File root = folder.newFolder("two-channels");
		write(root, 1, 1);
		write(root, 1, 2);
		write(root, 2, 1);
		write(root, 2, 2);
		java.util.List<TiffResultDataset> found = TiffResultDataset.discover(root);
		assertEquals("two series on disk", 2, found.size());

		java.util.List<OpmDataViewer.Entry> entries = OpmDataViewer.groupTiffFamilies(found);
		assertEquals("listed as one acquisition", 1, entries.size());
		OpmDataViewer.Entry entry = entries.get(0);
		assertEquals(2, entry.series.size());
		assertEquals("_Channel0001 anchors the family", 1,
				entry.series.indexOf(entry.tiff) + 1);
		assertTrue("the name says the family: " + entry.displayName(),
				entry.displayName().contains("_Channel####"));
		assertTrue(entry.toString().contains("2 channel series"));
		assertEquals("the halves both series can offer", java.util.Arrays.asList(
				"_Channel0001-left", "_Channel0001-right", "_Channel0002-left", "_Channel0002-right"),
				OpmDataViewer.tiffChannelLabels(entry.series));
	}

	/**
	 * The two composition controls mean the same thing for a TIFF family as for a store.
	 *
	 * <p>Side by side is the stored width - the deskew shear acts in Y and Z only - so it asks
	 * for one full-width channel per series and no transform at all. Stored halves splits
	 * without mirroring, which a half of a store is by definition and a half of a TIFF has to
	 * be told; flip and flip + align mirror the side the operation names.
	 */
	@Test
	public void theRuntimeViewMeansTheSameForATiffFamily() throws Exception {
		File root = folder.newFolder("composition");
		write(root, 1, 1);
		write(root, 1, 2);
		java.util.List<TiffResultDataset> series = TiffResultDataset.discover(root);
		java.util.Collections.sort(series, new java.util.Comparator<TiffResultDataset>() {
			@Override public int compare(TiffResultDataset a, TiffResultDataset b) {
				return a.getDisplayName().compareTo(b.getDisplayName());
			}
		});
		OpmDataViewer.TiffVirtualSetup setup = new OpmDataViewer.TiffVirtualSetup();
		setup.order.addAll(OpmDataViewer.tiffChannelLabels(series));

		java.util.List<TiffResultView.VirtualChannel> whole = OpmDataViewer.tiffVirtualChannels(
				series, OmeZarrView.SELECT_ALL, OmeZarrView.Operation.SIDE_BY_SIDE, setup);
		assertEquals("one full width per series", 2, whole.size());
		assertEquals(TiffResultView.HorizontalPart.WHOLE, whole.get(0).part);
		assertNull("a stored width is never aligned", whole.get(0).alignment);

		java.util.List<TiffResultView.VirtualChannel> stored = OpmDataViewer.tiffVirtualChannels(
				series, OmeZarrView.SELECT_ALL, OmeZarrView.Operation.STORED_CHANNELS, setup);
		assertEquals(4, stored.size());
		assertEquals(TiffResultView.HorizontalPart.LEFT, stored.get(0).part);
		assertEquals(TiffResultView.HorizontalPart.RIGHT, stored.get(1).part);
		for (TiffResultView.VirtualChannel channel : stored)
			assertFalse("stored halves are not mirrored", channel.transform);

		java.util.List<TiffResultView.VirtualChannel> flipped = OpmDataViewer.tiffVirtualChannels(
				series, OmeZarrView.SELECT_ALL, OmeZarrView.Operation.FLIP_ONLY, setup);
		for (TiffResultView.VirtualChannel channel : flipped)
			assertTrue("flip only still transforms", channel.transform);
		assertFalse("the right half is the mirrored one by default", flipped.get(0).flipLeft);
		assertTrue(OpmDataViewer.tiffVirtualChannels(series, OmeZarrView.SELECT_ALL,
				OmeZarrView.Operation.FLIP_ALIGN_LEFT, setup).get(0).flipLeft);

		java.util.List<TiffResultView.VirtualChannel> right = OpmDataViewer.tiffVirtualChannels(
				series, OmeZarrView.SELECT_RIGHT, OmeZarrView.Operation.FLIP_ONLY, setup);
		assertEquals("one right half per series", 2, right.size());
		assertEquals("_Channel0001-right", right.get(0).label);
		assertEquals("_Channel0002-right", right.get(1).label);
	}

	/** A split needs the axis the halves lie along, so only the volume and Z projections take one. */
	@Test
	public void onlyTheVolumeAndZProjectionsCanBeSplit() {
		assertTrue(OpmDataViewer.tiffCompositionApplies(TiffResultDataset.VOLUME));
		assertTrue(OpmDataViewer.tiffCompositionApplies("maxZ"));
		assertTrue(OpmDataViewer.tiffCompositionApplies("avgZ"));
		assertFalse("X has collapsed the axis the halves lie along",
				OpmDataViewer.tiffCompositionApplies("maxX"));
		assertFalse("Y has collapsed the plane the alignment lives in",
				OpmDataViewer.tiffCompositionApplies("meanY"));
		assertFalse(OpmDataViewer.tiffCompositionApplies(null));
	}

	/** One whole-width single-channel time point of one acquisition channel. */
	private static void write(File root, int timepoint, int acquisition) throws Exception {
		File deskew = new File(root, "deskew");
		assertTrue(deskew.isDirectory() || deskew.mkdirs());
		ImageStack stack = new ImageStack(8, 4);
		for (int z = 0; z < 3; z++) {
			short[] pixels = new short[8 * 4];
			for (int i = 0; i < pixels.length; i++)
				pixels[i] = (short) (acquisition * 1000 + timepoint * 100 + z * 10 + i);
			stack.addSlice(new ShortProcessor(8, 4, pixels, null));
		}
		ImagePlus image = new ImagePlus("whole", stack);
		image.setDimensions(1, 3, 1);
		assertTrue(VolumeIO.saveTiff(image, new File(deskew, String.format(
				"split_Time%06d_Channel%04d-deskewed.tif", timepoint, acquisition)).getPath()));
	}

	private static OpmDataViewer.ManagedView view(File root, String kind) {
		OpmDataViewer.OpenMode mode = OpmDataViewer.VOLUME_KIND.equals(kind)
				? OpmDataViewer.OpenMode.VOLUME_ALL : OpmDataViewer.OpenMode.PROJECTION;
		return new OpmDataViewer.ManagedView(new ImagePlus(kind, new ShortProcessor(2, 2)),
				root, kind, mode, -1, true, false, new OmeZarrView.Options());
	}

	private static CompositeImage composite(String labels) {
		ImageStack stack = new ImageStack(2, 2);
		stack.addSlice(new ShortProcessor(2, 2, new short[] { 1, 2, 3, 4 }, null));
		stack.addSlice(new ShortProcessor(2, 2, new short[] { 5, 6, 7, 8 }, null));
		ImagePlus base = new ImagePlus("composite", stack);
		base.setDimensions(2, 1, 1);
		CompositeImage image = new CompositeImage(base, CompositeImage.COMPOSITE);
		image.setProperty("opm.channelLabels", labels);
		return image;
	}
}
