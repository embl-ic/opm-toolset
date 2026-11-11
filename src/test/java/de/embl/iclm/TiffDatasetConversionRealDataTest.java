package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ij.process.ImageProcessor;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import org.junit.Assume;
import org.junit.Test;

/**
 * Dataset conversion of a real raw acquisition, two time points of both acquisition channels.
 *
 * <p>Gated: {@code -Dopm.test.data=<raw acquisition folder>} (written for
 * {@code 3_timelapse_0}: one position, two channels, 3200x500x451 BigTIFFs) and
 * {@code -Dopm.test.conversion.out=<scratch folder>}, which receives about 6 GB. The source is only
 * read. The default test heap of 512 MB is smaller than one raw volume, so a pass also shows the
 * conversion streams.
 */
public class TiffDatasetConversionRealDataTest {

	@Test
	public void twoTimePointsOfBothChannelsSplitIntoOneStore() throws Exception {
		String data = System.getProperty("opm.test.data");
		String out = System.getProperty("opm.test.conversion.out");
		Assume.assumeTrue("set -Dopm.test.data and -Dopm.test.conversion.out", data != null && out != null);
		File source = new File(data);
		File output = new File(out, "split");
		Assume.assumeTrue(source.isDirectory());

		DataFolder.Filter filter = new DataFolder.Filter();
		filter.include = "Time000001_,Time000002_";
		DataFolder found = DataFolder.scan(source, filter, true);
		FormatConversion.Options options = new FormatConversion.Options();
		options.target = FormatConversion.TARGET_ZARR;
		options.channels = TiffConversionPlan.CHANNELS_SPLIT;
		options.overwrite = true;
		options.tryGpu = false;

		TiffConversionPlan plan = TiffConversionPlan.byName(FormatConversion.looseFiles(found), options.planSettings());
		long checkStart = System.nanoTime();
		plan.check();
		double checkSeconds = (System.nanoTime() - checkStart) / 1e9;
		assertEquals(1, plan.datasets.size());
		TiffConversionPlan.Dataset planned = plan.datasets.get(0);
		System.out.println("plan check of " + planned.sources.size() + " headers: " + checkSeconds + " s; " + plan.report());
		assertTrue(planned.problemText(), planned.convertible());
		assertEquals("3_timelapse_Position0001", planned.name);
		assertEquals(Arrays.asList("_Channel0001-left", "_Channel0001-right", "_Channel0002-left", "_Channel0002-right"),
				planned.channelLabels);

		long start = System.nanoTime();
		FormatConversion.convertFolder(found, output, options, false);
		System.out.println("conversion: " + (System.nanoTime() - start) / 1e9 + " s");

		OmeZarrDataset store = OmeZarrDataset.read(new File(output, "3_timelapse_Position0001.ome.zarr"));
		assertTrue(store.isComplete());
		assertEquals(2, store.getTimepointCount());
		assertEquals(planned.channelLabels, store.getChannelLabels());
		assertEquals(1600, store.getWidth());
		assertEquals(500, store.getHeight());
		assertEquals(451, store.getDepth());
		OpmProvenance provenance = store.getProvenance();
		assertEquals(OpmProvenance.CONTENT_RAW, provenance.contentKind);
		assertEquals(0.116, provenance.xyPixelSizeUm, 1e-9);
		assertEquals(0.265, provenance.zStepSizeUm, 1e-9);
		assertEquals(300, provenance.frameIntervalSeconds, 1e-9);
		assertEquals(4, provenance.sourceFiles.size());
		System.out.println("time points " + store.getTimePointLabels() + ", elapsed " + provenance.timePointElapsedSeconds
				+ ", notes " + planned.notes);

		// sample planes against the raw files, both halves, unflipped
		OmeZarrPlaneReader stored = new OmeZarrPlaneReader(store, "s0");
		try {
			for (int t = 0; t < 2; t++)
				for (int c = 0; c < 2; c++) {
					File raw = new File(source, String.format(
							"3_timelapse_Position0001_Time%06d_Channel%04d_Frames_1_451.tiff", t + 1, c + 1));
					FastTiffReader.PlaneReader reader = new FastTiffReader.PlaneReader(raw);
					try {
						for (int z : new int[] { 0, 225, 450 }) {
							short[] full = reader.readPixels(z);
							for (int side = 0; side < 2; side++) {
								ImageProcessor plane = stored.readPlane(z, 2 * c + side, t);
								long differing = 0;
								for (int y = 0; y < 500; y++)
									for (int x = 0; x < 1600; x++)
										if (plane.get(x, y) != (full[y * 3200 + side * 1600 + x] & 0xffff)) differing++;
								assertEquals("t" + t + " c" + c + " side " + side + " z" + z, 0, differing);
							}
						}
					} finally {
						reader.close();
					}
				}
		} finally {
			stored.close();
		}
		List<String> labels = store.getTimePointLabels();
		assertEquals("3_timelapse_Position0001_Time000001", labels.get(0));
	}

	/**
	 * The older combined result in {@code result}: written with a unit and a spacing of 0.116 but
	 * no resolution tags, and a frame interval of its own (30 s) that is not the acquisition's
	 * (300 s). Planned only - headers, no pixels, nothing written.
	 */
	@Test
	public void anOlderCombinedResultKeepsItsCalibrationAndInterval() throws Exception {
		String data = System.getProperty("opm.test.data");
		Assume.assumeTrue("set -Dopm.test.data", data != null);
		File source = new File(data, "result");
		Assume.assumeTrue(source.isDirectory());
		DataFolder found = DataFolder.scan(source, null, true);
		TiffConversionPlan plan = TiffConversionPlan.byName(FormatConversion.looseFiles(found), found.results,
				new TiffConversionPlan.Settings());
		plan.check();
		System.out.println("older result plan: " + plan.report());
		TiffConversionPlan.Dataset combined = null;
		for (TiffConversionPlan.Dataset dataset : plan.datasets)
			if (dataset.name.contains("Channels0001-0002")) combined = dataset;
		Assume.assumeTrue("no combined result series in " + source, combined != null);
		assertTrue(combined.problemText(), combined.convertible());
		assertTrue(combined.processed);
		for (int i = 0; i < 3; i++) assertEquals("axis " + i, 0.116, combined.sampling[i], 1e-6);
		assertEquals("the result's own interval", 30, combined.frameIntervalSeconds, 1e-9);
		assertEquals(OpmProvenance.Conversion.LAYOUT_STORED, combined.storedLayout);
	}

	/**
	 * The per-channel deskew results in {@code result_tiffonly}, two time points, as one store of
	 * four halves with its six projections. A result's include filter sees series names, which
	 * carry no time number, so the plan is cut to two time points here.
	 */
	@Test
	public void twoTimePointsOfAPerChannelResultSplitIntoOneStore() throws Exception {
		String data = System.getProperty("opm.test.data");
		String out = System.getProperty("opm.test.conversion.out");
		Assume.assumeTrue("set -Dopm.test.data and -Dopm.test.conversion.out", data != null && out != null);
		File source = new File(data, "result_tiffonly");
		Assume.assumeTrue(source.isDirectory());

		DataFolder found = DataFolder.scan(source, null, true);
		FormatConversion.Options options = new FormatConversion.Options();
		options.target = FormatConversion.TARGET_ZARR;
		options.channels = TiffConversionPlan.CHANNELS_SPLIT;
		options.tryGpu = false;
		TiffConversionPlan plan = TiffConversionPlan.byName(FormatConversion.looseFiles(found), found.results,
				options.planSettings());
		plan.assignOutputs(new File(out, "result-split"), source, false, null);
		long checkStart = System.nanoTime();
		plan.check();
		System.out.println("result plan check: " + (System.nanoTime() - checkStart) / 1e9 + " s; " + plan.report());
		assertEquals(1, plan.datasets.size());
		TiffConversionPlan.Dataset dataset = plan.datasets.get(0);
		assertTrue(dataset.problemText(), dataset.convertible());
		assertTrue(dataset.processed);
		dataset.timePoints.subList(2, dataset.timePoints.size()).clear();
		if (dataset.output.exists()) org.apache.commons.io.FileUtils.deleteDirectory(dataset.output);

		long start = System.nanoTime();
		assertEquals(2, FormatConversion.writeStore(dataset, options));
		System.out.println("result conversion: " + (System.nanoTime() - start) / 1e9 + " s");

		OmeZarrDataset store = OmeZarrDataset.read(dataset.output);
		assertTrue(store.isComplete());
		assertEquals(Arrays.asList("_Channel0001-left", "_Channel0001-right", "_Channel0002-left", "_Channel0002-right"),
				store.getChannelLabels());
		assertEquals(OpmProvenance.CONTENT_DESKEWED, store.getProvenance().contentKind);
		assertEquals(6, store.getAvailableProjections().size());
		System.out.println("result store " + dataset.output.getName() + ": " + store.getWidth() + "x" + store.getHeight()
				+ "x" + store.getDepth() + ", voxel " + Arrays.toString(store.getProvenance().deskewedVoxelSizeUm)
				+ ", interval " + store.getProvenance().frameIntervalSeconds + " s, notes " + dataset.notes);

		OmeZarrPlaneReader stored = new OmeZarrPlaneReader(store, "s0");
		try {
			for (int t = 0; t < 2; t++)
				for (int c = 0; c < 2; c++) {
					TiffConversionPlan.Slot left = dataset.timePoints.get(t).slots.get(2 * c);
					FastTiffReader.PlaneReader reader = new FastTiffReader.PlaneReader(left.source.file);
					try {
						int width = reader.width(), half = width / 2, height = reader.height();
						for (int z : new int[] { 0, store.getDepth() / 2, store.getDepth() - 1 }) {
							short[] full = reader.readPixels(z);
							for (int side = 0; side < 2; side++) {
								ImageProcessor plane = stored.readPlane(z, 2 * c + side, t);
								long differing = 0;
								for (int y = 0; y < height; y++)
									for (int x = 0; x < half; x++)
										if (plane.get(x, y) != (full[y * width + side * half + x] & 0xffff)) differing++;
								assertEquals(left.source.name + " side " + side + " z" + z, 0, differing);
							}
						}
					} finally {
						reader.close();
					}
				}
		} finally {
			stored.close();
		}
	}
}
