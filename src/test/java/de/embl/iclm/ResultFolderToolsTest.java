package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

/**
 * The batch commands that take a result folder: how they find what is in it, and what Channel
 * Operation and Format Conversion then make of it.
 *
 * <p>Every volume here is a whole camera width, 16 pixels, with one bead in each half: the left
 * one at x = 2 and the right one at x = 13, which is x = 5 of the right half and lands on x = 2
 * again once that half is mirrored. So a correct flip is one pixel value in one place.
 */
public class ResultFolderToolsTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final int WIDTH = 16, HEIGHT = 8, DEPTH = 3;
	private static final double[][] IDENTITY = { { 1, 0, 0 }, { 0, 1, 0 } };


	// ---- finding what a folder holds ------------------------------------------------

	@Test
	public void aFolderIsReadAsTheViewerReadsItPlusItsOtherVolumes() throws Exception {
		File data = folder.newFolder("data");
		writeResult(new File(data, "sample_Time00001-deskewed.tif"));
		writeResult(new File(data, "sample_Time00002-deskewed.tif"));
		writeResult(new File(data, "sample_Time00001-deskewed-maxZprojection.tif"), volume(1));
		File raw = writePlain(new File(data, "raw.tif"));
		File packed = writeDeflated(new File(data, "packed.tif"));
		File store = new File(data, "stores/other.ome.zarr");
		FormatConversion.convertToOmeZarr(writePlain(new File(folder.newFolder("elsewhere"), "other.tif")),
				store, new FormatConversion.Options());

		DataFolder found = DataFolder.scan(data, null, true);

		assertEquals("one TIFF result, its volume and its projection together", 1, found.results.size());
		assertEquals(Arrays.asList(TiffResultDataset.VOLUME, "maxZ"), DataFolder.views(found.results.get(0)));
		assertEquals(1, found.zarr.size());
		assertEquals("the result's own files are not loose volumes as well", 2, found.loose.size());
		assertEquals(1, found.deflatedLooseCount());
		for (DataFolder.LooseTiff tiff : found.loose)
			assertEquals(tiff.file.getName(), tiff.file.equals(packed), tiff.deflated);
		assertTrue(found.summary(), found.summary().contains("1 TIFF result"));
		assertTrue(found.summary(), found.summary().contains("1 already deflated"));
		assertTrue(found.loose.get(0).file.equals(raw) || found.loose.get(1).file.equals(raw));

		DataFolder resultsOnly = DataFolder.scanResults(data);
		assertTrue("the viewer's scan never lists loose volumes", resultsOnly.loose.isEmpty());
		assertEquals(1, resultsOnly.results.size());
	}

	@Test
	public void oneNamedTiffIsOneVolumeWhateverSitsBesideIt() throws Exception {
		File data = folder.newFolder("single");
		File raw = writePlain(new File(data, "raw.tif"));
		writePlain(new File(data, "neighbour.tif"));
		DataFolder found = DataFolder.scan(raw, null, true);
		assertEquals(1, found.loose.size());
		assertEquals(raw, found.loose.get(0).file);
		assertEquals(raw, found.singleFile);
	}

	@Test
	public void aMeanProjectionIsCalledMeanWhateverItsFileSays() {
		assertEquals("meanZ", DataFolder.canonicalView("avgZ"));
		assertEquals("maxY", DataFolder.canonicalView("maxY"));
	}


	// ---- Format Conversion ----------------------------------------------------------

	@Test
	public void deflatedToDeflatedIsTheOneCombinationRefused() throws Exception {
		FormatConversion.Options tiff = options(FormatConversion.TARGET_TIFF);
		FormatConversion.Options both = options(FormatConversion.TARGET_BOTH);
		assertNull("a deflated TIFF has nothing to become as deflated TIFF", tiff.forInput(true));
		assertEquals("but under both it still goes to OME-Zarr",
				FormatConversion.TARGET_ZARR, both.forInput(true).target);
		assertEquals(FormatConversion.TARGET_TIFF, tiff.forInput(false).target);

		File data = folder.newFolder("packed-only");
		writeDeflated(new File(data, "a.tif"));
		DataFolder found = DataFolder.scan(data, null, true);
		assertNotNull("nothing converts, and it says why",
				FormatConversion.nothingToDo(found, tiff));
		assertNull(FormatConversion.nothingToDo(found, options(FormatConversion.TARGET_ZARR)));
		assertTrue(FormatConversion.gateText(found, FormatConversion.TARGET_BOTH)
				.contains("already deflated"));
	}

	@Test
	public void everyKindInAFolderConvertsToWhatItCan() throws Exception {
		File data = folder.newFolder("mixed");
		writeResult(new File(data, "sample_Time00001-deskewed.tif"));
		writeResult(new File(data, "sample_Time00002-deskewed.tif"));
		writePlain(new File(data, "raw.tif"));
		writeDeflated(new File(data, "packed.tif"));
		FormatConversion.convertToOmeZarr(writePlain(new File(folder.newFolder("elsewhere2"), "other.tif")),
				new File(data, "stores/other.ome.zarr"), new FormatConversion.Options());
		File out = folder.newFolder("converted");

		DataFolder found = DataFolder.scan(data, null, true);
		FormatConversion.convertFolder(found, out, options(FormatConversion.TARGET_BOTH), false);

		assertTrue("a plain TIFF becomes deflated TIFF", new File(out, "raw-deflated.tif").isFile());
		assertTrue("and OME-Zarr", new File(out, "raw.ome.zarr").isDirectory());
		assertFalse("a deflated TIFF is not copied as deflated TIFF", new File(out, "packed-deflated.tif").exists());
		assertTrue("but does become OME-Zarr", new File(out, "packed.ome.zarr").isDirectory());
		assertTrue("an OME-Zarr becomes TIFF", new File(out, "other-deflated.tif").isFile());

		OmeZarrDataset result = OmeZarrDataset.read(new File(out, "sample.ome.zarr"));
		assertEquals("a TIFF result is one dataset over its time points", 2, result.getTimepointCount());
		assertEquals(Arrays.asList("C1"), result.getChannelLabels());
		assertTrue("its pixels are final, so nothing is applied at view time",
				result.getProvenance().alignApplied);
		assertEquals("with the six projections the viewer contract carries",
				6, result.getAvailableProjections().size());
		assertEquals(200, plane(result, 0, 0).get(13, 3));
	}

	@Test
	public void anOmeZarrComesBackAsATiffResultTheViewerReads() throws Exception {
		File source = folder.newFolder("chop");
		writeResult(new File(source, "sample_Time00001-deskewed.tif"));
		File stores = folder.newFolder("stores");
		FormatConversion.convertFolder(DataFolder.scan(source, null, true), stores,
				options(FormatConversion.TARGET_ZARR), false);
		OmeZarrDataset dataset = OmeZarrDataset.read(new File(stores, "sample.ome.zarr"));

		File back = folder.newFolder("back");
		FormatConversion.convertZarrToTiff(dataset, back, options(FormatConversion.TARGET_TIFF), null);

		List<TiffResultDataset> results = TiffResultDataset.discover(back);
		assertEquals(1, results.size());
		assertTrue(results.get(0).hasVolume());
		assertEquals("and its projections come with it", 6, results.get(0).getAvailableProjections().size());
		ImagePlus volume = VolumeIO.open(results.get(0).getView(TiffResultDataset.VOLUME)
				.frame(0).file.getAbsolutePath());
		assertEquals(200, volume.getStack().getProcessor(1).get(13, 3));
	}


	// ---- Channel Operation ----------------------------------------------------------

	@Test
	public void aWholeWidthResultIsSplitFlippedAndWrittenInBothFormats() throws Exception {
		File data = folder.newFolder("result");
		writeResult(new File(data, "sample_Time00001-deskewed.tif"));
		writeResult(new File(data, "sample_Time00002-deskewed.tif"));
		File out = folder.newFolder("operated");

		BatchChannelOperation operation = new BatchChannelOperation();
		operation.run(DataFolder.scanResults(data), AlignmentMatrixSet.legacy(IDENTITY), out,
				settings(Parameter.FORMAT_BOTH), TiffResultDataset.VOLUME, "maxZ");

		File tiff = BatchTiffOutput.volumeFile(out, true, "sample_Time00001-deskewed");
		assertTrue("the TIFF keeps the input's name and layout", VolumeIO.isCompleteTiff(tiff));
		ImagePlus composed = VolumeIO.open(tiff.getAbsolutePath());
		assertEquals(2, composed.getNChannels());
		assertEquals("the left half as it was", 100, composed.getStack().getProcessor(1).get(2, 3));
		assertEquals("the right half mirrored onto it", 200, composed.getStack().getProcessor(2).get(2, 3));
		assertTrue("its projection is recomputed from the operated volume",
				VolumeIO.isCompleteTiff(BatchTiffOutput.projectionFile(out, true,
						"sample_Time00001-deskewed", "max", "Z")));
		assertFalse("and only the views asked for", BatchTiffOutput.projectionFile(out, true,
				"sample_Time00001-deskewed", "max", "X").exists());

		OmeZarrDataset store = OmeZarrDataset.read(new File(out, "sample.ome.zarr"));
		assertEquals(2, store.getTimepointCount());
		assertEquals(Arrays.asList("_Channel0001-left", "_Channel0001-right"), store.getChannelLabels());
		assertEquals("the halves are stored unflipped, as the deskew stores them",
				200, plane(store, 1, 0).get(5, 3));
		OpmProvenance provenance = store.getProvenance();
		assertFalse("the alignment is metadata, applied when viewed", provenance.alignApplied);
		assertTrue("and the slot order is what the viewer composes", provenance.deskewCombineChannels);
		assertEquals(Arrays.asList("_Channel0001-left", "_Channel0001-right"), provenance.deskewChannelOrder);
		assertEquals(BatchChannelOperation.FLIP_RIGHT, provenance.alignFlipHalf);
		assertNotNull("so the viewer opens it composed",
				DeskewChannelView.of(provenance));
	}

	@Test
	public void anOmeZarrOnlyHasItsAlignmentMetadataReplaced() throws Exception {
		File data = folder.newFolder("canonical");
		writeResult(new File(data, "sample_Time00001-deskewed.tif"));
		File stores = folder.newFolder("canonical-out");
		new BatchChannelOperation().run(DataFolder.scanResults(data),
				AlignmentMatrixSet.legacy(IDENTITY), stores, settings(Parameter.FORMAT_ZARR),
				TiffResultDataset.VOLUME);
		File root = new File(stores, "sample.ome.zarr");
		File chunk = new File(root, "s0/0.1.0.0.0");
		assertTrue(chunk.isFile());
		long before = chunk.lastModified();
		Thread.sleep(20);

		AlignmentMatrixSet tagged = new AlignmentMatrixSet("_Channel0001-left");
		tagged.put("_Channel0001-right", new double[][] { { 1, 0, 1.5 }, { 0, 1, -0.5 } });
		tagged.put("_Channel0002-left", new double[][] { { 1, 0, 2 }, { 0, 1, 0 } });
		new BatchChannelOperation().run(DataFolder.scanResults(stores), tagged,
				folder.newFolder("unused"), settings(Parameter.FORMAT_TIFF), TiffResultDataset.VOLUME);

		OpmProvenance provenance = OmeZarrDataset.read(root).getProvenance();
		assertEquals("_Channel0001-left", provenance.alignReference);
		assertArrayEquals(new double[] { 1, 0, 2 }, provenance.alignMatrices.get("_Channel0002-left")[0], 0);
		assertEquals(AlignmentMatrixSet.CONVENTION, provenance.alignMatrixConvention);
		assertTrue("the attributes as they were are kept", new File(root, ".zattrs.original").isFile());
		assertEquals("and no pixel was written", before, chunk.lastModified());
	}

	@Test
	public void resultsOfOneTimePointArePairedByTimeAndNamedAsOneDataset() throws Exception {
		File data = folder.newFolder("pairs");
		writeResult(new File(data, "sample_Channel0001_Time00001-deskewed.tif"));
		writeResult(new File(data, "sample_Channel0001_Time00002-deskewed.tif"));
		writeResult(new File(data, "sample_Channel0002_Time00002-deskewed.tif"));
		BatchChannelOperation operation = new BatchChannelOperation();
		operation.setCombineAcquisitionChannels(true);
		List<BatchChannelOperation.Group> groups = operation.group(DataFolder.scanResults(data).results);
		assertEquals("both files of the acquisition are one group", 1, groups.size());
		List<List<TiffResultDataset.Frame>> paired =
				BatchChannelOperation.pairFrames(groups.get(0), TiffResultDataset.VOLUME);
		assertEquals("time point 1 is missing Channel0002, so only 2 is whole", 1, paired.size());
		assertEquals("sample_Channels0001-0002_Time00002-deskewed", BatchChannelOperation.outputName(
				Arrays.asList(paired.get(0).get(0).file, paired.get(0).get(1).file)));
		assertEquals("a single file keeps its own name", "sample_Channel0001_Time00001-deskewed",
				BatchChannelOperation.outputName(Arrays.asList(
						new File(data, "sample_Channel0001_Time00001-deskewed.tif"))));
	}


	/**
	 * A result whose channels are already made: every channel can be reordered, with or without
	 * a matrix, and no OME-Zarr is written from it.
	 *
	 * <p>Its halves have been flipped and aligned into the pixels, so there are no canonical
	 * halves left to store. The guard used to read the input layout <em>setting</em>, so on
	 * auto detect - the default - each channel image was cut in half and stored as though those
	 * were the halves. Reordering reached the first two channels only; the rest were dropped.
	 */
	@Test
	public void aFinishedChannelHyperstackIsReorderedWholeAndWritesNoStore() throws Exception {
		File data = folder.newFolder("channels");
		writeResult(new File(data, "sample_Time00001-deskewed.tif"), channelStack(4));
		File out = folder.newFolder("channels-out");

		BatchChannelOperation operation = new BatchChannelOperation();
		operation.setChannelOrder("_Channel0002-left", "_Channel0001-right");
		// no matrix: reordering a finished hyperstack is a use of its own
		operation.run(DataFolder.scanResults(data), AlignmentMatrixSet.legacy(null), out,
				settings(Parameter.FORMAT_BOTH), TiffResultDataset.VOLUME);

		File tiff = BatchTiffOutput.volumeFile(out, true, "sample_Time00001-deskewed");
		assertTrue("the TIFF is written without a matrix", VolumeIO.isCompleteTiff(tiff));
		ImagePlus composed = VolumeIO.open(tiff.getAbsolutePath());
		assertEquals(2, composed.getNChannels());
		assertEquals("the third channel, which used to be out of reach",
				300, composed.getStack().getProcessor(1).get(2, 3));
		assertEquals("then the second", 200, composed.getStack().getProcessor(2).get(2, 3));
		composed.close();
		assertFalse("no store is written from channels that are already flipped and aligned",
				new File(out, "sample.ome.zarr").exists());
	}


	// ---- Generate Projection Image --------------------------------------------------

	@Test
	public void theDeskewChannelOptionsSplitAWholeWidthBeforeItIsProjected() throws Exception {
		ImagePlus whole = volume(DEPTH);
		List<BatchProjection.Part> fold = BatchProjection.channelParts(whole, "fold by midline", null, true);
		assertEquals(1, fold.size());
		assertEquals(2, fold.get(0).image.getNChannels());
		assertEquals("the right half mirrored onto the left", 200,
				fold.get(0).image.getStack().getProcessor(2).get(2, 3));

		List<BatchProjection.Part> separately =
				BatchProjection.channelParts(whole, "left & right separately", null, true);
		assertEquals(Arrays.asList("-left", "-right"),
				Arrays.asList(separately.get(0).suffix, separately.get(1).suffix));
		assertEquals("separately leaves the right half as it is", 200,
				separately.get(1).image.getStack().getProcessor(1).get(5, 3));

		assertEquals(1, BatchProjection.channelParts(whole, "only right", null, true).size());
		ImagePlus composite = fold.get(0).image;
		assertEquals("a volume that already has channels is projected as it is", 1,
				BatchProjection.channelParts(composite, "fold by midline", null, true).size());
		assertEquals(composite, BatchProjection.channelParts(composite, "fold by midline", null, true).get(0).image);
	}


	// ---- helpers --------------------------------------------------------------------

	private static FormatConversion.Options options(String target) {
		FormatConversion.Options options = new FormatConversion.Options();
		options.target = target;
		options.tryGpu = false;
		return options;
	}

	private Parameter settings(String format) {
		Parameter parameter = Parameter.scratch();
		parameter.outputFormat = format;
		parameter.saveSeparate = true;
		parameter.fileExistStr = "skip";
		parameter.tryGPU = false;
		parameter.alignmFile = "";
		return parameter;
	}

	/** A finished result: channels already split, flipped and aligned, C1 at 100, C2 at 200, ... */
	private static ImagePlus channelStack(int channels) {
		ImageStack stack = new ImageStack(WIDTH / 2, HEIGHT);
		for (int z = 0; z < DEPTH; z++)
			for (int c = 0; c < channels; c++) {
				ShortProcessor plane = new ShortProcessor(WIDTH / 2, HEIGHT);
				plane.set(2, 3, 100 * (c + 1));
				stack.addSlice(plane);
			}
		ImagePlus image = new ImagePlus("channels", stack);
		image.setDimensions(channels, DEPTH, 1);
		image.setOpenAsHyperStack(true);
		return new ij.CompositeImage(image, ij.CompositeImage.COMPOSITE);
	}

	private static ImageProcessor plane(OmeZarrDataset dataset, int channel, int time) throws Exception {
		OmeZarrPlaneReader reader = new OmeZarrPlaneReader(dataset, "s0");
		try { return reader.readPlane(1, channel, time); }
		finally { reader.close(); }
	}

	/** A whole camera width: a bead at x = 2 in the left half and at x = 13 in the right. */
	private static ImagePlus volume(int depth) {
		ImageStack stack = new ImageStack(WIDTH, HEIGHT);
		for (int z = 0; z < depth; z++) {
			ShortProcessor plane = new ShortProcessor(WIDTH, HEIGHT);
			plane.set(2, 3, 100);
			plane.set(13, 3, 200);
			stack.addSlice(null, plane);
		}
		ImagePlus image = new ImagePlus("volume", stack);
		image.setDimensions(1, depth, 1);
		Calibration calibration = new Calibration();
		calibration.pixelWidth = calibration.pixelHeight = calibration.pixelDepth = 0.116;
		calibration.setUnit("micron");
		image.setCalibration(calibration);
		return image;
	}

	private static File writeResult(File file) {
		return writeResult(file, volume(DEPTH));
	}

	private static File writeResult(File file, ImagePlus image) {
		file.getParentFile().mkdirs();
		image.setTitle(file.getName().replaceAll("[.]tif$", ""));
		assertTrue(VolumeIO.saveTiff(image, file));
		return file;
	}

	private static File writeDeflated(File file) {
		return writeResult(file);
	}

	private static File writePlain(File file) {
		file.getParentFile().mkdirs();
		assertTrue(new FileSaver(volume(DEPTH)).saveAsTiffStack(file.getAbsolutePath()));
		return file;
	}
}
