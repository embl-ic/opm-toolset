package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.ImageWindow;
import ij.gui.StackWindow;
import ij.process.ShortProcessor;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;

import javax.swing.SwingUtilities;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Live views in real windows: opened at one time point, as a live preview is, then grown.
 *
 * <p>Needs a display, so it runs only with {@code -Dopm.test.gui=true}. The headless tests grow
 * stacks that were never shown, and what goes wrong here happens only in a window: ImageJ builds a
 * volume at one time point with a Z slider and nothing else, and a single-channel movie with no
 * slider at all. Both used to keep that window as the view grew, and a TIFF result that was never
 * flagged as a hyperstack opened with one slider over every plane of every time point.
 */
public class LiveViewWindowTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Before
	public void needsADisplay() {
		Assume.assumeTrue("Enable with -Dopm.test.gui=true", Boolean.getBoolean("opm.test.gui"));
	}

	@Test
	public void aSingleChannelTiffVolumeGainsItsTimeSliderAsItGrows() throws Exception {
		File root = folder.newFolder("tiff");
		writeTiff(root, 1);
		final TiffResultDataset dataset = TiffResultDataset.discover(root).get(0);
		final ImagePlus image = TiffResultView.openVirtual(dataset, TiffResultDataset.VOLUME,
				new TiffResultView.Options(), -1);
		show(image);
		try {
			for (int t = 2; t <= 3; t++) {
				writeTiff(root, t);
				onEdt(new Runnable() { @Override public void run() {
					TiffResultView.growVirtualView(image, dataset, true);
				} });
				assertShape("TIFF volume at T=" + t, image, 1, 3, t);
			}
		} finally {
			close(image);
		}

		ImagePlus reopened = TiffResultView.openVirtual(TiffResultDataset.discover(root).get(0),
				TiffResultDataset.VOLUME, new TiffResultView.Options(), -1);
		show(reopened);
		try {
			assertShape("a TIFF volume opened with three time points", reopened, 1, 3, 3);
		} finally {
			close(reopened);
		}
	}

	@Test
	public void sideBySideAndStoredHalvesBothGrowIntoReachableTimePoints() throws Exception {
		for (OmeZarrView.Operation operation : new OmeZarrView.Operation[] {
				OmeZarrView.Operation.SIDE_BY_SIDE, OmeZarrView.Operation.STORED_CHANNELS }) {
			File root = new File(folder.getRoot(), operation.name() + ".ome.zarr");
			writeZarr(root, 1);
			OmeZarrView.Options options = new OmeZarrView.Options();
			options.tryGpu = false;
			options.operation = operation;
			int channels = operation == OmeZarrView.Operation.SIDE_BY_SIDE ? 1 : 2;
			int listenersBefore = listeners();
			final ImagePlus volume = OmeZarrView.openVirtualVolume(OmeZarrDataset.read(root), options, -1);
			final ImagePlus movie = OmeZarrView.openVirtualProjectionMovie(
					OmeZarrDataset.read(root), "maxZ", options);
			show(volume);
			show(movie);
			try {
				for (int t = 2; t <= 3; t++) {
					writeZarr(root, t);
					final OmeZarrDataset grown = OmeZarrDataset.read(root);
					final ImageWindow before = volume.getWindow();
					onEdt(new Runnable() { @Override public void run() {
						OmeZarrView.growVirtualView(volume, grown);
						OmeZarrView.growVirtualView(movie, grown);
					} });
					assertShape(operation + " volume at T=" + t, volume, channels, 3, t);
					assertShape(operation + " maxZ at T=" + t, movie, channels, 1, t);
					/* The rebuild that gave it a time slider reports the old window as the
					 * image closing; the store's reader must survive that. */
					assertEquals("readers kept open", listenersBefore + 2, listeners());
					int last = 100 * t + channels - 1;
					assertEquals(last, volume.getStack().getProcessor(volume.getStackSize()).get(0, 0));
					assertEquals(last, movie.getStack().getProcessor(movie.getStackSize()).get(0, 0));
					if (t == 3) assertSame("grown in place once it has a time slider", before, volume.getWindow());
				}
			} finally {
				close(volume);
				close(movie);
			}
			Thread.sleep(500);
			assertEquals("closing the views releases their readers", listenersBefore, listeners());
		}
	}

	/** The time axis is reachable: a T slider on a hyperstack, or the one slider of a movie. */
	private static void assertShape(String what, ImagePlus image, int channels, int slices, int frames)
			throws Exception {
		Thread.sleep(300);
		assertEquals(what + " C", channels, image.getNChannels());
		assertEquals(what + " Z", slices, image.getNSlices());
		assertEquals(what + " T", frames, image.getNFrames());
		ImageWindow window = image.getWindow();
		assertTrue(what + " is shown in a stack window", window instanceof StackWindow);
		if (channels == 1 && slices == 1) {
			assertEquals(what + " single slider", frames + 1, maximum(window, "zSelector"));
		} else {
			assertTrue(what + " is a hyperstack", image.isDisplayedHyperStack());
			assertEquals(what + " time slider", frames + 1, maximum(window, "tSelector"));
			if (slices > 1) assertEquals(what + " Z slider", slices + 1, maximum(window, "zSelector"));
		}
	}

	private static int maximum(ImageWindow window, String selector) throws Exception {
		Field field = StackWindow.class.getDeclaredField(selector);
		field.setAccessible(true);
		Object scrollbar = field.get(window);
		assertTrue(selector + " exists", scrollbar != null);
		return (Integer) scrollbar.getClass().getMethod("getMaximum").invoke(scrollbar);
	}

	private static int listeners() throws Exception {
		Field field = ImagePlus.class.getDeclaredField("listeners");
		field.setAccessible(true);
		return ((Collection<?>) field.get(null)).size();
	}

	private static void show(final ImagePlus image) throws Exception {
		onEdt(new Runnable() { @Override public void run() { image.show(); } });
		Thread.sleep(500);
	}

	private static void close(final ImagePlus image) throws Exception {
		onEdt(new Runnable() { @Override public void run() {
			image.changes = false;
			image.close();
		} });
	}

	private static void onEdt(Runnable action) throws Exception {
		SwingUtilities.invokeAndWait(action);
	}

	private static void writeTiff(File root, int timepoint) {
		File deskew = new File(root, "deskew");
		assertTrue(deskew.isDirectory() || deskew.mkdirs());
		ImageStack stack = new ImageStack(8, 4);
		for (int z = 0; z < 3; z++) stack.addSlice(new ShortProcessor(8, 4));
		ImagePlus image = new ImagePlus("volume", stack);
		image.setDimensions(1, 3, 1);
		String stem = String.format("run_Position0001_Time%06d_Channel0001", timepoint);
		assertTrue(VolumeIO.saveTiff(image, new File(deskew, stem + "-deskewed.tif").getPath()));
	}

	/** Write, or extend, a store to {@code timepoints}; pixel (0,0) of every plane is 100*T + C. */
	private static void writeZarr(File root, int timepoints) throws Exception {
		OmeZarrWriter writer = new OmeZarrWriter(root);
		try {
			writer.createAppendableVolume(4, 2, 3, 2);
			writer.createAppendableProjection("maxZ", 4, 2, 2);
			writer.writeMetadata(OmeZarrDatasetTest.sampleProvenance("live-window", true),
					Collections.singletonList("maxZ"));
			for (int t = 0; t < timepoints; t++) {
				if (writer.isTimePointCommitted("Time" + (t + 1))) continue;
				for (int c = 0; c < 2; c++) {
					ImageStack stack = new ImageStack(4, 2);
					for (int z = 0; z < 3; z++) {
						ShortProcessor plane = new ShortProcessor(4, 2);
						plane.set(0, 0, 100 * (t + 1) + c);
						stack.addSlice(plane);
					}
					writer.writeVolumeChannel(new ImagePlus("volume", stack), c, t);
					ShortProcessor projection = new ShortProcessor(4, 2);
					projection.set(0, 0, 100 * (t + 1) + c);
					writer.writeProjection(new ImagePlus("projection", projection), "maxZ", c, t);
				}
				writer.commitTimePoint(t, "Time" + (t + 1), Collections.singletonList("raw.tif"), t);
			}
		} finally {
			writer.close();
		}
	}
}
