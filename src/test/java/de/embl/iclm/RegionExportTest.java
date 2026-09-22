package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

/**
 * The OPM Data Viewer's <b>Export...</b>: a region written straight to disk, as TIFF, as its own
 * OME-Zarr, or as both - and, whichever source it came from, holding what the viewer would show.
 */
public class RegionExportTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final int WIDTH = 16, HEIGHT = 8, DEPTH = 3;

	/** A plane whose every pixel says which channel, z and time point it came from. */
	private static final RegionExport.Planes COUNTED = new RegionExport.Planes() {
		@Override public ImageProcessor plane(int channel, int z, int timepoint) {
			ShortProcessor plane = new ShortProcessor(4, 3);
			plane.set(1, 1, 100 * (timepoint + 1) + 10 * (z + 1) + channel + 1);
			return plane;
		}
	};

	@Test
	public void aRegionIsWrittenAsBothFormatsAndReadsBackAsItWasWritten() throws Exception {
		File out = folder.newFolder("export");
		RegionExport.Request request = request(out, true, true);
		String said = RegionExport.run(request);
		assertTrue(said, said.contains("2 channel(s), 2 time point(s)"));

		ImagePlus tiff = VolumeIO.open(new File(out, "region.tif").getAbsolutePath());
		assertEquals(2, tiff.getNChannels());
		assertEquals(3, tiff.getNSlices());
		assertEquals(2, tiff.getNFrames());
		assertEquals("channel 2, z 3, time point 5 of the source",
				100 * 5 + 10 * 3 + 2, tiff.getStack().getProcessor(tiff.getStackIndex(2, 3, 1)).get(1, 1));
		assertEquals("and the second exported time point is the source's sixth",
				100 * 6 + 10 * 1 + 1, tiff.getStack().getProcessor(tiff.getStackIndex(1, 1, 2)).get(1, 1));
		tiff.close();

		OmeZarrDataset store = OmeZarrDataset.read(new File(out, "region.ome.zarr"));
		assertEquals(2, store.getTimepointCount());
		assertEquals(Arrays.asList("left", "right"), store.getChannelLabels());
		assertEquals("the same pixels in the store", 100 * 5 + 10 * 3 + 2, plane(store, 1, 0, 2).get(1, 1));
		assertTrue("its pixels are what was shown, so nothing is left to align",
				store.getProvenance().alignApplied);
	}

	@Test
	public void anExistingExportIsRefusedUnlessOverwriteIsAsked() throws Exception {
		File out = folder.newFolder("twice");
		RegionExport.run(request(out, true, false));
		try {
			RegionExport.run(request(out, true, false));
			fail("an export that would replace a file should say so");
		} catch (IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("exists already"));
		}
		RegionExport.Request again = request(out, true, false);
		again.overwrite = true;
		assertTrue(RegionExport.run(again).contains("region.tif"));
	}

	/** From a TIFF result: the export holds the same pixels as materialising that region would. */
	@Test
	public void aTiffResultRegionIsWhatTheViewWouldShow() throws Exception {
		File results = folder.newFolder("results");
		writeResult(new File(results, "sample_Time00001-deskewed.tif"));
		TiffResultDataset dataset = TiffResultDataset.discover(results).get(0);

		TiffResultView.Options options = new TiffResultView.Options();
		options.bounds = new OmeZarrView.Bounds(4, 2, 6, 4, 1, 3);
		File out = folder.newFolder("tiff-region");
		TiffResultView.RegionPlanes planes =
				TiffResultView.regionPlanes(dataset, TiffResultDataset.VOLUME, options);
		try {
			RegionExport.Request request = new RegionExport.Request();
			request.planes = planes;
			request.name = "sample-region";
			request.folder = out;
			request.writeTiff = true;
			request.width = planes.width();
			request.height = planes.height();
			request.depth = planes.depth();
			request.channels = planes.channels();
			request.frames = 1;
			RegionExport.run(request);
		} finally {
			planes.close();
		}

		ImagePlus exported = VolumeIO.open(new File(out, "sample-region.tif").getAbsolutePath());
		ImagePlus materialised = TiffResultView.openMaterialised(
				dataset, TiffResultDataset.VOLUME, options, 0, 1);
		assertEquals(6, exported.getWidth());
		assertEquals(4, exported.getHeight());
		assertEquals(2, exported.getNSlices());
		assertEquals("the same region, without ever being an image in between",
				materialised.getStack().getProcessor(1).get(1, 1),
				exported.getStack().getProcessor(1).get(1, 1));
		exported.close();
		materialised.close();
	}

	/** From an OME-Zarr: the runtime view is what lands on disk, region and all. */
	@Test
	public void anOmeZarrRegionIsTheRuntimeViewOnDisk() throws Exception {
		File raw = new File(folder.newFolder("raw"), "volume.tif");
		writeResult(raw);
		File store = new File(folder.newFolder("stores"), "volume.ome.zarr");
		FormatConversion.Options conversion = new FormatConversion.Options();
		conversion.tryGpu = false;
		FormatConversion.convertToOmeZarr(raw, store, conversion);
		OmeZarrDataset dataset = OmeZarrDataset.read(store);

		OmeZarrView.Options options = new OmeZarrView.Options();
		options.tryGpu = false;
		options.bounds = new OmeZarrView.Bounds(4, 2, 6, 4, 0, 2);
		File out = folder.newFolder("zarr-region");
		OmeZarrView.RegionPlanes planes = OmeZarrView.regionPlanes(dataset, options);
		try {
			RegionExport.Request request = new RegionExport.Request();
			request.planes = planes;
			request.name = "volume-region";
			request.folder = out;
			request.writeTiff = true;
			request.writeZarr = true;
			request.width = planes.width();
			request.height = planes.height();
			request.depth = planes.depth();
			request.channels = planes.channels();
			request.frames = 1;
			request.source = store;
			RegionExport.run(request);
		} finally {
			planes.close();
		}

		ImagePlus exported = VolumeIO.open(new File(out, "volume-region.tif").getAbsolutePath());
		ImagePlus materialised = OmeZarrView.openMaterializedVolume(dataset, options, 0, 1);
		assertEquals(6, exported.getWidth());
		assertEquals(2, exported.getNSlices());
		assertEquals(materialised.getStack().getProcessor(1).get(2, 1),
				exported.getStack().getProcessor(1).get(2, 1));
		OmeZarrDataset region = OmeZarrDataset.read(new File(out, "volume-region.ome.zarr"));
		assertEquals(1, region.getTimepointCount());
		assertEquals("and the store holds the same region",
				materialised.getStack().getProcessor(1).get(2, 1), plane(region, 0, 0, 0).get(2, 1));
		exported.close();
		materialised.close();
	}


	// ---- helpers --------------------------------------------------------------------

	private static RegionExport.Request request(File out, boolean tiff, boolean zarr) {
		RegionExport.Request request = new RegionExport.Request();
		request.planes = COUNTED;
		request.name = "region";
		request.folder = out;
		request.writeTiff = tiff;
		request.writeZarr = zarr;
		request.width = 4;
		request.height = 3;
		request.depth = 3;
		request.channels = 2;
		request.firstTimepoint = 4;		// the source's fifth time point
		request.frames = 2;
		request.channelLabels.addAll(Arrays.asList("left", "right"));
		request.pixelSizeUm = 0.116;
		request.voxelDepthUm = 0.116;
		return request;
	}

	private static ImageProcessor plane(OmeZarrDataset dataset, int channel, int time, int z)
			throws Exception {
		OmeZarrPlaneReader reader = new OmeZarrPlaneReader(dataset, "s0");
		try { return reader.readPlane(z, channel, time); }
		finally { reader.close(); }
	}

	/** A small whole-width volume, values rising with x so a crop is recognisable. */
	private static void writeResult(File file) {
		file.getParentFile().mkdirs();
		ImageStack stack = new ImageStack(WIDTH, HEIGHT);
		for (int z = 0; z < DEPTH; z++) {
			ShortProcessor plane = new ShortProcessor(WIDTH, HEIGHT);
			for (int y = 0; y < HEIGHT; y++)
				for (int x = 0; x < WIDTH; x++) plane.set(x, y, 100 * (z + 1) + 10 * y + x);
			stack.addSlice(plane);
		}
		ImagePlus image = new ImagePlus(file.getName().replaceAll("[.]tif$", ""), stack);
		assertTrue(VolumeIO.saveTiff(image, file));
	}
}
