package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

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
	 * A TIFF result's composition is in its pixels, so the two controls are greyed. What they
	 * show must describe the TIFF, not an OME-Zarr choice: a whole-image TIFF read "stored halves
	 * as separate channels" over a full-width image.
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
