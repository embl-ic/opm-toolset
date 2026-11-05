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

	/**
	 * Picking another position keeps the view setup, and another acquisition keeps its own: what is
	 * set on one position is what the next shows, and coming back finds it as it was left.
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void positionsShareOneViewSetupAndAnotherAcquisitionKeepsItsOwn() throws Exception {
		File result = folder.newFolder("result"), other = folder.newFolder("other");
		for (int position = 1; position <= 2; position++)
			for (int channel = 1; channel <= 2; channel++)
				OpmDataViewerViewsTest.writePosition(result, "acq", position, channel, 1);
		for (int channel = 1; channel <= 2; channel++) OpmDataViewerViewsTest.writePosition(other, "next", 1, channel, 1);
		final java.util.List<OpmDataViewer.Entry> entries =
				OpmDataViewer.groupTiffFamilies(TiffResultDataset.discover(result));
		entries.addAll(OpmDataViewer.groupTiffFamilies(TiffResultDataset.discover(other)));
		assertEquals(3, entries.size());
		java.lang.reflect.Constructor<OpmDataViewer> constructor = OpmDataViewer.class.getDeclaredConstructor(boolean.class);
		constructor.setAccessible(true);
		final OpmDataViewer viewer = constructor.newInstance(false);
		final javax.swing.JComboBox<OpmDataViewer.Entry> datasets =
				(javax.swing.JComboBox<OpmDataViewer.Entry>) PositionZarrTest.get(viewer, "datasets");
		final javax.swing.JComboBox<OpmDataViewer.OpenMode> openMode =
				(javax.swing.JComboBox<OpmDataViewer.OpenMode>) PositionZarrTest.get(viewer, "openMode");
		final javax.swing.JComboBox<String> projections = (javax.swing.JComboBox<String>) PositionZarrTest.get(viewer, "projections");
		final javax.swing.JComboBox<String> selections = (javax.swing.JComboBox<String>) PositionZarrTest.get(viewer, "selections");
		final javax.swing.JComboBox<OmeZarrView.Operation> operations =
				(javax.swing.JComboBox<OmeZarrView.Operation>) PositionZarrTest.get(viewer, "operations");
		final javax.swing.JCheckBox virtual = (javax.swing.JCheckBox) PositionZarrTest.get(viewer, "virtual");
		final java.util.List<Object> shown = new java.util.ArrayList<Object>();
		final Runnable read = () -> {
			shown.clear();
			shown.add(openMode.getSelectedItem()); shown.add(virtual.isSelected());
			shown.add(projections.getSelectedItem()); shown.add(selections.getSelectedItem());
			shown.add(operations.getSelectedItem());
		};
		java.util.List<Object> set = java.util.Arrays.<Object>asList(OpmDataViewer.OpenMode.PROJECTION, false,
				"maxY", OmeZarrView.SELECT_RIGHT, OmeZarrView.Operation.FLIP_ONLY);
		try {
			onEdt(() -> {
				for (OpmDataViewer.Entry entry : entries) datasets.addItem(entry);	// Position 1 selected
				openMode.setSelectedItem(OpmDataViewer.OpenMode.PROJECTION);
				virtual.setSelected(false);
				projections.setSelectedItem("maxY");
				selections.setSelectedItem(OmeZarrView.SELECT_RIGHT);
				operations.setSelectedItem(OmeZarrView.Operation.FLIP_ONLY);
				datasets.setSelectedIndex(1);
				read.run();
			});
			assertEquals("Position 2 shows what was set on Position 1", set, new java.util.ArrayList<Object>(shown));

			onEdt(() -> { datasets.setSelectedIndex(2); read.run(); });
			assertEquals("another acquisition starts as written, Z on top, virtual; the mode was never reset",
					java.util.Arrays.<Object>asList(OpmDataViewer.OpenMode.PROJECTION, true, "maxZ",
							OmeZarrView.SELECT_ALL, OmeZarrView.Operation.SIDE_BY_SIDE), new java.util.ArrayList<Object>(shown));

			onEdt(() -> {
				operations.setSelectedItem(OmeZarrView.Operation.STORED_CHANNELS);
				datasets.setSelectedIndex(0);
				read.run();
			});
			assertEquals("back on Position 1, its acquisition's setup", set, new java.util.ArrayList<Object>(shown));

			onEdt(() -> { datasets.setSelectedIndex(2); read.run(); });
			assertEquals("and the other acquisition kept its own", OmeZarrView.Operation.STORED_CHANNELS, shown.get(4));
			assertEquals(true, shown.get(1));
		} finally { onEdt(() -> viewer.close()); }
	}

	@Test
	@SuppressWarnings("unchecked")
	public void tiffPositionWindowsRebuildAndGrowIndependently() throws Exception {
		File root = folder.newFolder("positions");
		TiffResultDiscoveryTest.result(root, "sample_Position0001", 1);
		TiffResultDiscoveryTest.result(root, "sample_Position0002", 1);
		java.util.List<TiffResultDataset> found = TiffResultDataset.discover(root);
		final TiffResultDataset first = found.get(0), second = found.get(1);
		java.lang.reflect.Constructor<OpmDataViewer> constructor = OpmDataViewer.class.getDeclaredConstructor(boolean.class);
		constructor.setAccessible(true);
		final OpmDataViewer viewer = constructor.newInstance(false);
		final javax.swing.JComboBox<OpmDataViewer.Entry> datasets =
				(javax.swing.JComboBox<OpmDataViewer.Entry>) PositionZarrTest.get(viewer, "datasets");
		final OpmDataViewer.ViewRegistry registry = (OpmDataViewer.ViewRegistry) PositionZarrTest.get(viewer, "views");
		final ImagePlus one = TiffResultView.openVirtual(first, "maxZ", new TiffResultView.Options(), -1);
		final ImagePlus two = TiffResultView.openVirtual(second, "maxZ", new TiffResultView.Options(), -1);
		final OpmDataViewer.ManagedView firstView = OpmDataViewer.ManagedView.tiff(one, first, "maxZ",
				OpmDataViewer.OpenMode.PROJECTION, -1, true, false, new TiffResultView.Options());
		final OpmDataViewer.ManagedView secondView = OpmDataViewer.ManagedView.tiff(two, second, "maxZ",
				OpmDataViewer.OpenMode.PROJECTION, -1, true, false, new TiffResultView.Options());
		try {
			onEdt(() -> {
				datasets.addItem(new OpmDataViewer.Entry(first));
				datasets.addItem(new OpmDataViewer.Entry(second));
				invoke(viewer, "present", new Class<?>[] { OpmDataViewer.ManagedView.class }, firstView);
				invoke(viewer, "present", new Class<?>[] { OpmDataViewer.ManagedView.class }, secondView);
				datasets.setSelectedIndex(1);
			});
			assertEquals(2, registry.size());
			assertTrue(one.getWindow() != null && two.getWindow() != null);
			TiffResultDiscoveryTest.result(root, "sample_Position0001", 2);
			TiffResultDiscoveryTest.result(root, "sample_Position0002", 2);
			first.refresh(); second.refresh();
			onEdt(() -> {
				invoke(viewer, "catchUp", new Class<?>[] { OpmDataViewer.ManagedView.class }, firstView);
			});
			assertEquals("catchUp must ignore another selected position", 1, one.getNFrames());
			onEdt(() -> invoke(viewer, "tiffGrew", new Class<?>[] { TiffResultDataset.class, int.class, int.class }, second, 1, 2));
			assertEquals("a tick for P2 leaves P1 alone", 1, one.getNFrames());
			assertEquals(2, two.getNFrames());
			onEdt(() -> invoke(viewer, "recomposeOpenTiffViews", new Class<?>[] { TiffResultDataset.class, boolean.class }, second, true));
			long until = System.currentTimeMillis() + 10000;
			while ((Boolean) PositionZarrTest.get(viewer, "busy") && System.currentTimeMillis() < until) {
				Thread.sleep(25); onEdt(() -> {});
			}
			onEdt(() -> {});
			assertSame("P1 window survives P2 composition change", one, registry.find(first.identity(), "maxZ").image);
			assertTrue("P2 gets its own replacement", two != registry.find(second.identity(), "maxZ").image);
			assertTrue(one.getWindow() != null);
			assertEquals(2, registry.size());
		} finally { onEdt(() -> viewer.close()); }
	}

	@Test
	public void batchProjectionMoviesKeepPositionsApart() throws Exception {
		Parameter p = new Parameter("position-movie-test");
		p.doProjection = true; p.projAxes = Collections.singletonList("Z"); p.projTypes = Collections.singletonList("max");
		p.makeTimeLapse = true; p.tryGPU = false;
		try {
			for (int t = 1; t <= 2; t++) for (int position = 1; position <= 2; position++) {
				ShortProcessor plane = new ShortProcessor(8, 6); plane.set(0, 0, 100 * position + t);
				ImagePlus volume = new ImagePlus(String.format("sample_Position%04d_Time%06d-deskewed", position, t), plane);
				try (BatchTiffOutput prepared = BatchTiffOutput.prepare(volume, p)) { /* movie built during preparation */ }
			}
			for (int position = 1; position <= 2; position++) {
				ImagePlus movie = ij.WindowManager.getImage(String.format("_Position%04d-maxZprojection", position));
				assertTrue("one movie per position", movie != null);
				assertEquals(2, movie.getNFrames());
				assertEquals(100 * position + 1, movie.getStack().getProcessor(1).get(0, 0));
				assertEquals(100 * position + 2, movie.getStack().getProcessor(2).get(0, 0));
			}
		} finally {
			for (int position = 1; position <= 2; position++) {
				ImagePlus movie = ij.WindowManager.getImage(String.format("_Position%04d-maxZprojection", position));
				if (movie != null) close(movie);
			}
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void zarrPreviewKeepsItsRequestedPositionAndScanListsLaterStores() throws Exception {
		File output = folder.newFolder("preview");
		File first = new File(output, "run_Position0001.ome.zarr");
		File second = new File(output, "run_Position0002.ome.zarr");
		writeZarr(first, 1); writeZarr(second, 2);
		java.lang.reflect.Constructor<OpmDataViewer> constructor = OpmDataViewer.class.getDeclaredConstructor(boolean.class);
		constructor.setAccessible(true);
		final OpmDataViewer viewer = constructor.newInstance(false);
		Class<?> requestClass = Class.forName("de.embl.iclm.OpmDataViewer$LiveRequest");
		java.lang.reflect.Constructor<?> requestConstructor = requestClass.getDeclaredConstructor(File.class,
				boolean.class, boolean.class, boolean.class, boolean.class, DeskewChannelView.class, java.util.List.class);
		requestConstructor.setAccessible(true);
		Object request = requestConstructor.newInstance(first, false, true, false, true, null, Collections.singletonList("maxZ"));
		javax.swing.JComboBox<OpmDataViewer.Entry> datasets =
				(javax.swing.JComboBox<OpmDataViewer.Entry>) PositionZarrTest.get(viewer, "datasets");
		try {
			onEdt(() -> invoke(viewer, "requestLive", new Class<?>[] { requestClass }, request));
			waitForViewer(viewer);
			assertEquals("the requested store wins over a newer sibling", first,
					((OpmDataViewer.Entry) datasets.getSelectedItem()).root());
			assertEquals(output.getAbsolutePath(), ((javax.swing.JTextField) PositionZarrTest.get(viewer, "path")).getText());
			assertEquals(2, datasets.getItemCount());
			writeZarr(new File(output, "run_Position0003.ome.zarr"), 1);
			onEdt(() -> invoke(viewer, "scan", new Class<?>[0]));
			waitForViewer(viewer);
			assertEquals("Scan discovers positions written since the first preview", 3, datasets.getItemCount());
		} finally { onEdt(() -> viewer.close()); }
	}

	private static void waitForViewer(OpmDataViewer viewer) throws Exception {
		long until = System.currentTimeMillis() + 15000;
		while (System.currentTimeMillis() < until) {
			Thread.sleep(25); onEdt(() -> {});
			if (!(Boolean) PositionZarrTest.get(viewer, "busy")) { onEdt(() -> {}); return; }
		}
		throw new AssertionError("viewer remained busy");
	}

	private static Object invoke(Object target, String name, Class<?>[] types, Object... args) {
		try {
			java.lang.reflect.Method method = target.getClass().getDeclaredMethod(name, types);
			method.setAccessible(true);
			return method.invoke(target, args);
		} catch (Exception failure) { throw new RuntimeException(failure); }
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
