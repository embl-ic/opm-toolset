package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Format Conversion of TIFF volumes as datasets: one OME-Zarr per acquisition position, with its
 * time points along T and its acquisition channels - whole, split or one half - along C.
 *
 * <p>Every source pixel here encodes where it came from - position, time point, acquisition
 * channel, plane, row and column - so a store that put one file's plane in another's slot, or
 * mirrored a half, or shifted a crop by a column, fails on the first pixel it got wrong instead
 * of looking plausible.
 */
public class TiffDatasetConversionTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final int WIDTH = 8, HEIGHT = 4, DEPTH = 3;


	// ---- grouping -----------------------------------------------------------------------

	@Test
	public void twoPositionsOfTimePointsAndChannelsMakeOneStorePerPosition() throws Exception {
		File data = folder.newFolder("acquisition");
		writeAcquisition(data, "acq", new int[] { 1, 2 }, 3, 2, WIDTH, DEPTH);
		File out = folder.newFolder("out");

		FormatConversion.convertFolder(DataFolder.scan(data, null, true), out, zarr(), false);

		for (int position = 1; position <= 2; position++) {
			File root = new File(out, String.format("acq_Position%04d.ome.zarr", position));
			assertTrue("one store per position: " + root, root.isDirectory());
			OmeZarrDataset dataset = OmeZarrDataset.read(root);
			assertEquals(3, dataset.getTimepointCount());
			assertEquals(Arrays.asList("_Channel0001-whole", "_Channel0002-whole"), dataset.getChannelLabels());
			assertEquals(WIDTH, dataset.getWidth());
			assertEquals(DEPTH, dataset.getDepth());
			assertTrue(dataset.isComplete());
			assertEquals(String.format("acq_Position%04d_Time000002", position), dataset.getTimePointLabels().get(1));
			OpmProvenance provenance = dataset.getProvenance();
			assertEquals(OpmProvenance.CONTENT_RAW, provenance.contentKind);
			assertFalse(provenance.alignApplied);
			assertEquals("every file is recorded", 6, provenance.sourceFiles.size());
			assertNotNull(provenance.conversion);
			assertEquals(OpmProvenance.Conversion.LAYOUT_WHOLE, provenance.conversion.storedLayout);
			assertEquals(3, provenance.conversion.timePointSources.size());
			for (int t = 1; t <= 3; t++)
				for (int c = 1; c <= 2; c++)
					assertPlanes(dataset, t - 1, c - 1, position, t, c, 0, WIDTH);
		}
		int stores = 0;
		for (String name : out.list()) if (name.endsWith(".ome.zarr")) stores++;
		assertEquals("one store per position and no other", 2, stores);
	}

	@Test
	public void timeNumbersOrderNumericallyWhateverTheirDigits() throws Exception {
		File data = folder.newFolder("digits");
		// the first argument marks each file's pixels, since 10 and 100 share a last digit
		writeVolume(new File(data, "run_Time10_Channel1.tif"), 1, 10, 1, WIDTH, DEPTH);
		writeVolume(new File(data, "run_Time9_Channel1.tif"), 0, 9, 1, WIDTH, DEPTH);
		writeVolume(new File(data, "run_Time100_Channel1.tif"), 2, 100, 1, WIDTH, DEPTH);
		File out = folder.newFolder("out");

		FormatConversion.convertFolder(DataFolder.scan(data, null, true), out, zarr(), false);

		OmeZarrDataset dataset = OmeZarrDataset.read(new File(out, "run.ome.zarr"));
		assertEquals(Arrays.asList("run_Time9", "run_Time10", "run_Time100"), dataset.getTimePointLabels());
		assertPlanes(dataset, 0, 0, 0, 9, 1, 0, WIDTH);
		assertPlanes(dataset, 1, 0, 1, 10, 1, 0, WIDTH);
		assertPlanes(dataset, 2, 0, 2, 100, 1, 0, WIDTH);
	}

	@Test
	public void aProcessedCopyIsNeverMergedWithItsSource() throws Exception {
		File data = folder.newFolder("mixed");
		writeVolume(new File(data, "acq_Position0001_Time000001_Channel0001_Frames_1_3.tif"), 1, 1, 1, WIDTH, DEPTH);
		writeVolume(new File(data, "acq_Position0001_Time000001_Channel0001_Frames_1_3-deflated.tif"), 1, 1, 1, WIDTH, DEPTH);

		TiffConversionPlan plan = TiffConversionPlan.byName(
				FormatConversion.looseFiles(DataFolder.scan(data, null, true)), new TiffConversionPlan.Settings());

		assertEquals(2, plan.datasets.size());
		List<String> names = new ArrayList<String>();
		for (TiffConversionPlan.Dataset dataset : plan.datasets) names.add(dataset.name);
		assertTrue(names.toString(), names.containsAll(Arrays.asList("acq_Position0001", "acq_Position0001-deflated")));
	}

	@Test
	public void anIncludeFilterChoosingOneChannelMakesAOneChannelStore() throws Exception {
		File data = folder.newFolder("filtered");
		writeAcquisition(data, "acq", new int[] { 1 }, 2, 2, WIDTH, DEPTH);
		DataFolder.Filter filter = new DataFolder.Filter();
		filter.include = "Channel0002";
		File out = folder.newFolder("out");

		FormatConversion.convertFolder(DataFolder.scan(data, filter, true), out, zarr(), false);

		OmeZarrDataset dataset = OmeZarrDataset.read(new File(out, "acq_Position0001.ome.zarr"));
		assertEquals(Arrays.asList("_Channel0002-whole"), dataset.getChannelLabels());
		assertEquals(2, dataset.getTimepointCount());
		assertPlanes(dataset, 1, 0, 1, 2, 2, 0, WIDTH);
	}

	@Test
	public void oneNamedFileStandsForItsDatasetOnlyInDatasetScope() throws Exception {
		File data = folder.newFolder("named");
		writeAcquisition(data, "acq", new int[] { 1, 2 }, 2, 2, WIDTH, DEPTH);
		File named = new File(data, "acq_Position0001_Time000002_Channel0001_Frames_1_3.tif");

		DataFolder dataset = FormatConversion.scanSelection(named, null, TiffConversionPlan.SCOPE_DATASET);
		assertEquals("its position's four files, not the other position's", 4, dataset.loose.size());
		for (DataFolder.LooseTiff tiff : dataset.loose)
			assertTrue(tiff.file.getName(), tiff.file.getName().startsWith("acq_Position0001_"));
		assertEquals(named, dataset.singleFile);

		DataFolder one = FormatConversion.scanSelection(named, null, TiffConversionPlan.SCOPE_FILE);
		assertEquals(1, one.loose.size());
		assertEquals(named, one.loose.get(0).file);
	}

	@Test
	public void eachTiffSeparatelyStillMakesOneStorePerFile() throws Exception {
		File data = folder.newFolder("separate");
		writeAcquisition(data, "acq", new int[] { 1 }, 2, 1, WIDTH, DEPTH);
		File out = folder.newFolder("out");
		FormatConversion.Options options = zarr();
		options.scope = TiffConversionPlan.SCOPE_FILE;

		FormatConversion.convertFolder(DataFolder.scan(data, null, true), out, options, false);

		File root = new File(out, "acq_Position0001_Time000002_Channel0001_Frames_1_3.ome.zarr");
		OmeZarrDataset dataset = OmeZarrDataset.read(root);
		assertEquals(1, dataset.getTimepointCount());
		assertEquals(Arrays.asList("_Channel0001-whole"), dataset.getChannelLabels());
		assertPlanes(dataset, 0, 0, 1, 2, 1, 0, WIDTH);
	}


	// ---- the camera halves --------------------------------------------------------------

	@Test
	public void aSplitStoresBothHalvesUnflippedAndTogetherEveryPixel() throws Exception {
		File data = folder.newFolder("split");
		writeAcquisition(data, "acq", new int[] { 1 }, 2, 2, WIDTH, DEPTH);
		File out = folder.newFolder("out");
		FormatConversion.Options options = zarr();
		options.channels = TiffConversionPlan.CHANNELS_SPLIT;

		FormatConversion.convertFolder(DataFolder.scan(data, null, true), out, options, false);

		OmeZarrDataset dataset = OmeZarrDataset.read(new File(out, "acq_Position0001.ome.zarr"));
		assertEquals(Arrays.asList("_Channel0001-left", "_Channel0001-right", "_Channel0002-left", "_Channel0002-right"),
				dataset.getChannelLabels());
		assertEquals(WIDTH / 2, dataset.getWidth());
		assertEquals(OpmProvenance.Conversion.LAYOUT_HALVES, dataset.getProvenance().conversion.storedLayout);
		assertFalse("unflipped, unaligned halves", dataset.getProvenance().alignApplied);
		for (int t = 1; t <= 2; t++)
			for (int c = 1; c <= 2; c++) {
				assertPlanes(dataset, t - 1, 2 * (c - 1), 1, t, c, 0, WIDTH / 2);
				assertPlanes(dataset, t - 1, 2 * (c - 1) + 1, 1, t, c, WIDTH / 2, WIDTH / 2);
			}
	}

	@Test
	public void oneHalfOnlyKeepsThatHalfOfEveryChannel() throws Exception {
		File data = folder.newFolder("halves");
		writeAcquisition(data, "acq", new int[] { 1 }, 1, 2, WIDTH, DEPTH);
		for (String side : new String[] { TiffConversionPlan.CHANNELS_LEFT, TiffConversionPlan.CHANNELS_RIGHT }) {
			File out = folder.newFolder("out-" + side.replace(' ', '-'));
			FormatConversion.Options options = zarr();
			options.channels = side;
			FormatConversion.convertFolder(DataFolder.scan(data, null, true), out, options, false);
			OmeZarrDataset dataset = OmeZarrDataset.read(new File(out, "acq_Position0001.ome.zarr"));
			boolean left = TiffConversionPlan.CHANNELS_LEFT.equals(side);
			String suffix = left ? "-left" : "-right";
			assertEquals(Arrays.asList("_Channel0001" + suffix, "_Channel0002" + suffix), dataset.getChannelLabels());
			assertPlanes(dataset, 0, 1, 1, 1, 2, left ? 0 : WIDTH / 2, WIDTH / 2);
		}
	}

	@Test
	public void anOddWidthIsNotSplitAndSaysWhy() throws Exception {
		File data = folder.newFolder("odd");
		writeAcquisition(data, "acq", new int[] { 1 }, 1, 1, 7, DEPTH);
		TiffConversionPlan.Settings settings = new TiffConversionPlan.Settings();
		settings.channels = TiffConversionPlan.CHANNELS_SPLIT;
		TiffConversionPlan plan = plan(data, settings);
		assertFalse(plan.datasets.get(0).convertible());
		assertTrue(plan.datasets.get(0).problemText(), plan.datasets.get(0).problemText().contains("odd"));
	}

	@Test
	public void aSplitNeedsAWholeCameraWidthNotJustAnyOneChannelTiff() throws Exception {
		File data = folder.newFolder("unknown");
		writeVolume(new File(data, "snapshot.tif"), 1, 1, 1, WIDTH, DEPTH);
		TiffConversionPlan.Settings settings = new TiffConversionPlan.Settings();
		settings.channels = TiffConversionPlan.CHANNELS_SPLIT;

		TiffConversionPlan.Dataset refused = plan(data, settings).datasets.get(0);
		assertFalse("nothing says this is a camera image", refused.convertible());
		assertTrue(refused.problemText(), refused.problemText().contains("whole camera width"));

		settings.inputLayout = TiffConversionPlan.LAYOUT_WHOLE;
		TiffConversionPlan.Dataset declared = plan(data, settings).datasets.get(0);
		assertTrue("declared, it splits: " + declared.problemText(), declared.convertible());
		assertEquals(Arrays.asList("_Channel0001-left", "_Channel0001-right"), declared.channelLabels);
	}


	// ---- what is refused before anything is written -------------------------------------

	@Test
	public void aMissingChannelRefusesTheDatasetUnlessIncompleteTimePointsAreLeftOut() throws Exception {
		File data = folder.newFolder("missing");
		writeAcquisition(data, "acq", new int[] { 1 }, 3, 2, WIDTH, DEPTH);
		assertTrue(new File(data, "acq_Position0001_Time000002_Channel0002_Frames_1_3.tif").delete());
		File out = folder.newFolder("out");

		FormatConversion.convertFolder(DataFolder.scan(data, null, true), out, zarr(), false);
		assertFalse("refused before anything is written", new File(out, "acq_Position0001.ome.zarr").exists());

		FormatConversion.Options leaveOut = zarr();
		leaveOut.incomplete = TiffConversionPlan.INCOMPLETE_LEAVE_OUT;
		FormatConversion.convertFolder(DataFolder.scan(data, null, true), out, leaveOut, false);
		OmeZarrDataset dataset = OmeZarrDataset.read(new File(out, "acq_Position0001.ome.zarr"));
		assertEquals(Arrays.asList("acq_Position0001_Time000001", "acq_Position0001_Time000003"),
				dataset.getTimePointLabels());
		assertPlanes(dataset, 1, 1, 1, 3, 2, 0, WIDTH);
	}

	@Test
	public void aFileWhosePlaneCountDiffersFromItsNameIsNamed() throws Exception {
		File data = folder.newFolder("short");
		writeAcquisition(data, "acq", new int[] { 1 }, 2, 1, WIDTH, DEPTH);
		File truncated = new File(data, "acq_Position0001_Time000002_Channel0001_Frames_1_3.tif");
		writeVolume(truncated, 1, 2, 1, WIDTH, DEPTH - 1);

		TiffConversionPlan.Dataset dataset = plan(data, new TiffConversionPlan.Settings()).datasets.get(0);
		assertFalse(dataset.convertible());
		assertTrue(dataset.problemText(), dataset.problemText().contains(truncated.getName()));
	}

	@Test
	public void storesThatWouldShareANameAreBothRefused() throws Exception {
		File data = folder.newFolder("tree");
		writeVolume(new File(new File(data, "a"), "x_Time1.tif"), 1, 1, 1, WIDTH, DEPTH);
		writeVolume(new File(new File(data, "b"), "x_Time1.tif"), 1, 1, 1, WIDTH, DEPTH);
		DataFolder.Filter filter = new DataFolder.Filter();
		filter.recursive = true;
		TiffConversionPlan plan = TiffConversionPlan.byName(
				FormatConversion.looseFiles(DataFolder.scan(data, filter, true)), new TiffConversionPlan.Settings());

		plan.assignOutputs(folder.newFolder("flat"), data, false, null);
		assertEquals("never merged across folders", 2, plan.datasets.size());
		for (TiffConversionPlan.Dataset dataset : plan.datasets)
			assertTrue(dataset.problemText(), dataset.problemText().contains("same store"));

		TiffConversionPlan tree = TiffConversionPlan.byName(
				FormatConversion.looseFiles(DataFolder.scan(data, filter, true)), new TiffConversionPlan.Settings());
		tree.assignOutputs(folder.newFolder("mirrored"), data, true, null);
		for (TiffConversionPlan.Dataset dataset : tree.datasets)
			assertTrue("reproducing the tree keeps them apart: " + dataset.problemText(), dataset.problems.isEmpty());
	}


	// ---- a TIFF that holds its own C and T ----------------------------------------------

	@Test
	public void aHyperstackKeepsItsChannelsAndTimePointsInsteadOfAFlatZ() throws Exception {
		File data = folder.newFolder("hyper");
		File file = new File(data, "export.tif");
		writeHyperstack(file, 2, DEPTH, 2);
		File out = folder.newFolder("out");

		FormatConversion.convertFolder(DataFolder.scan(data, null, true), out, zarr(), false);

		OmeZarrDataset dataset = OmeZarrDataset.read(new File(out, "export.ome.zarr"));
		assertEquals(2, dataset.getTimepointCount());
		assertEquals(DEPTH, dataset.getDepth());
		assertEquals(Arrays.asList("C1", "C2"), dataset.getChannelLabels());
		assertEquals(OpmProvenance.Conversion.LAYOUT_STORED, dataset.getProvenance().conversion.storedLayout);
		assertEquals("its calibration is kept", 0.2, dataset.getProvenance().xyPixelSizeUm, 1e-9);
		for (int t = 1; t <= 2; t++)
			for (int c = 1; c <= 2; c++)
				assertPlanes(dataset, t - 1, c - 1, 1, t, c, 0, WIDTH);

		TiffConversionPlan.Settings split = new TiffConversionPlan.Settings();
		split.channels = TiffConversionPlan.CHANNELS_SPLIT;
		TiffConversionPlan.Dataset refused = plan(data, split).datasets.get(0);
		assertFalse("its channels are not camera halves to cut again", refused.convertible());
	}

	@Test
	public void severalFilesThatEachHoldTimePointsAreRefused() throws Exception {
		File data = folder.newFolder("ambiguous");
		writeHyperstack(new File(data, "export_Time1.tif"), 1, DEPTH, 2);
		writeHyperstack(new File(data, "export_Time2.tif"), 1, DEPTH, 2);
		TiffConversionPlan.Dataset dataset = plan(data, new TiffConversionPlan.Settings()).datasets.get(0);
		assertFalse(dataset.convertible());
		assertTrue(dataset.problemText(), dataset.problemText().contains("time points of its own"));
	}

	@Test
	public void aDeflatedCopyOfAHyperstackKeepsItsLayout() throws Exception {
		File source = new File(folder.newFolder("tiff"), "export.tif");
		writeHyperstack(source, 2, DEPTH, 2);
		File copy = new File(folder.getRoot(), "copy.tif");

		FormatConversion.convertToDeflatedTiff(source, copy, 1, new FormatConversion.Options());

		TiffConversionPlan.Header header = TiffConversionPlan.readHeader(copy);
		assertEquals(2, header.channels);
		assertEquals(DEPTH, header.slices);
		assertEquals(2, header.frames);
		assertTrue(header.xyCalibrated);
		assertEquals(0.2, header.pixelWidthUm, 1e-9);
	}


	// ---- sampling and time --------------------------------------------------------------

	@Test
	public void theAcquisitionsOwnSamplingAndIntervalAreRecordedAndOnlyPresentTimePointsWritten() throws Exception {
		File data = folder.newFolder("sampled");
		writeAcquisition(data, "acq", new int[] { 1 }, 3, 1, WIDTH, DEPTH);
		writeParameters(new File(data, TiffConversionPlan.PARAMETERS_FILE), 817, DEPTH);
		File out = folder.newFolder("out");

		FormatConversion.convertFolder(DataFolder.scan(data, null, true), out, zarr(), false);

		OmeZarrDataset dataset = OmeZarrDataset.read(new File(out, "acq_Position0001.ome.zarr"));
		assertEquals("not 817 blank frames", 3, dataset.getTimepointCount());
		OpmProvenance provenance = dataset.getProvenance();
		assertEquals(0.116, provenance.xyPixelSizeUm, 1e-9);
		assertEquals(0.265, provenance.zStepSizeUm, 1e-9);
		assertEquals(300, provenance.frameIntervalSeconds, 1e-9);
		assertEquals(Arrays.asList(0.0, 300.0, 600.0), provenance.timePointElapsedSeconds);
		assertEquals("never made isotropic", 0.265, provenance.deskewedVoxelSizeUm[2], 1e-9);

		TiffConversionPlan.Dataset planned = plan(data, new TiffConversionPlan.Settings()).datasets.get(0);
		boolean said = false;
		for (String note : planned.notes) said |= note.contains("3 of the 817");
		assertTrue("the planned count is reported: " + planned.notes, said);
	}


	// ---- existing stores ----------------------------------------------------------------

	@Test
	public void anUnfinishedStoreIsResumedOnlyWhenItWasStartedByTheSamePlan() throws Exception {
		File data = folder.newFolder("growing");
		writeAcquisition(data, "acq", new int[] { 1 }, 2, 2, WIDTH, DEPTH);
		File out = folder.newFolder("out");
		File root = new File(out, "acq_Position0001.ome.zarr");
		FormatConversion.convertFolder(DataFolder.scan(data, null, true), out, zarr(), false);
		// as though the run had stopped after two of three time points
		assertTrue(new File(root, OmeZarrWriter.SUCCESS_FILE).delete());
		writeVolume(new File(data, "acq_Position0001_Time000003_Channel0001_Frames_1_3.tif"), 1, 3, 1, WIDTH, DEPTH);
		writeVolume(new File(data, "acq_Position0001_Time000003_Channel0002_Frames_1_3.tif"), 1, 3, 2, WIDTH, DEPTH);

		FormatConversion.convertFolder(DataFolder.scan(data, null, true), out, zarr(), false);
		assertEquals("skip leaves an unfinished store alone", 2, OmeZarrDataset.read(root).getTimepointCount());

		FormatConversion.Options split = zarr();
		split.resume = true;
		split.channels = TiffConversionPlan.CHANNELS_SPLIT;
		FormatConversion.convertFolder(DataFolder.scan(data, null, true), out, split, false);
		OmeZarrDataset unchanged = OmeZarrDataset.read(root);
		assertEquals("another layout is refused, not appended", 2, unchanged.getTimepointCount());
		assertEquals(2, unchanged.getChannelCount());

		FormatConversion.Options resume = zarr();
		resume.resume = true;
		FormatConversion.convertFolder(DataFolder.scan(data, null, true), out, resume, false);
		OmeZarrDataset resumed = OmeZarrDataset.read(root);
		assertEquals(Arrays.asList("acq_Position0001_Time000001", "acq_Position0001_Time000002",
				"acq_Position0001_Time000003"), resumed.getTimePointLabels());
		assertTrue(resumed.isComplete());
		assertEquals(6, resumed.getProvenance().sourceFiles.size());
		assertEquals(3, resumed.getProvenance().conversion.timePointSources.size());
		assertPlanes(resumed, 2, 1, 1, 3, 2, 0, WIDTH);
		assertPlanes(resumed, 0, 0, 1, 1, 1, 0, WIDTH);
	}

	@Test
	public void aStoreWhoseFilesChangedIsNotResumed() throws Exception {
		File data = folder.newFolder("changed");
		writeAcquisition(data, "acq", new int[] { 1 }, 2, 1, WIDTH, DEPTH);
		File out = folder.newFolder("out");
		File root = new File(out, "acq_Position0001.ome.zarr");
		FormatConversion.convertFolder(DataFolder.scan(data, null, true), out, zarr(), false);
		assertTrue(new File(root, OmeZarrWriter.SUCCESS_FILE).delete());
		File first = new File(data, "acq_Position0001_Time000001_Channel0001_Frames_1_3.tif");
		assertTrue(first.setLastModified(first.lastModified() - 60000));

		TiffConversionPlan plan = plan(data, new TiffConversionPlan.Settings());
		TiffConversionPlan.Dataset dataset = plan.datasets.get(0);
		dataset.output = root;
		FormatConversion.Options resume = zarr();
		resume.resume = true;
		try {
			FormatConversion.convertDataset(dataset, resume, null);
			fail("a committed file that changed must refuse the resume");
		} catch (IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("changed"));
		}
	}

	@Test
	public void aFileChangedAfterItsCheckIsNotCommitted() throws Exception {
		File data = folder.newFolder("moving");
		writeAcquisition(data, "acq", new int[] { 1 }, 1, 1, WIDTH, DEPTH);
		TiffConversionPlan plan = plan(data, new TiffConversionPlan.Settings());
		TiffConversionPlan.Dataset dataset = plan.datasets.get(0);
		dataset.output = new File(folder.getRoot(), "moving.ome.zarr");
		File file = dataset.sources.get(0).file;
		assertTrue(file.setLastModified(file.lastModified() + 60000));
		try {
			FormatConversion.writeStore(dataset, zarr());
			fail("the time point must not be committed from a file that changed");
		} catch (IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("changed after it was checked"));
		}
		assertEquals(0, new OmeZarrWriter(dataset.output).getCommittedTimepoints());
		assertFalse(new File(dataset.output, OmeZarrWriter.SUCCESS_FILE).exists());
	}


	// ---- in the viewer ------------------------------------------------------------------

	@Test
	public void aSplitStoreShowsTheWholeCameraSideBySideAndAWholeStoreIsNotDoubled() throws Exception {
		File data = folder.newFolder("view");
		writeAcquisition(data, "acq", new int[] { 1 }, 1, 1, WIDTH, DEPTH);
		File splitOut = folder.newFolder("split-out"), wholeOut = folder.newFolder("whole-out");
		FormatConversion.Options split = zarr();
		split.channels = TiffConversionPlan.CHANNELS_SPLIT;
		FormatConversion.convertFolder(DataFolder.scan(data, null, true), splitOut, split, false);
		FormatConversion.convertFolder(DataFolder.scan(data, null, true), wholeOut, zarr(), false);

		OmeZarrView.Options sideBySide = new OmeZarrView.Options();
		sideBySide.operation = OmeZarrView.Operation.SIDE_BY_SIDE;
		sideBySide.tryGpu = false;
		OmeZarrDataset halves = OmeZarrDataset.read(new File(splitOut, "acq_Position0001.ome.zarr"));
		ImagePlus joined = OmeZarrView.openVirtualVolume(halves, sideBySide, 0);
		try {
			assertEquals(WIDTH, joined.getWidth());
			ImageProcessor plane = joined.getStack().getProcessor(2);
			for (int x = 0; x < WIDTH; x++) assertEquals(value(1, 1, 1, 1, x, 2), plane.get(x, 2));
		} finally {
			joined.changes = false;
			joined.close();
		}

		OmeZarrDataset whole = OmeZarrDataset.read(new File(wholeOut, "acq_Position0001.ome.zarr"));
		assertEquals("a whole width has no half to pair", WIDTH, OmeZarrView.viewExtent(whole, sideBySide)[0]);
	}

	@Test
	public void theFirstDirectoryGivesTheDescriptionAndPixelSize() throws Exception {
		File file = new File(folder.getRoot(), "calibrated.tif");
		writeHyperstack(file, 2, DEPTH, 2);
		FastTiffReader.FirstPlane first = FastTiffReader.firstPlane(file);
		assertNotNull(first.description);
		assertTrue(first.description, first.description.startsWith("ImageJ="));
		assertTrue(first.description, first.description.contains("channels=2"));
		assertEquals(1 / 0.2, first.xResolution, 1e-6);
		File plain = writeVolume(new File(folder.getRoot(), "plain.tif"), 1, 1, 1, WIDTH, DEPTH);
		assertFalse("an uncalibrated file states no pixel size", TiffConversionPlan.readHeader(plain).calibrated());
	}


	// ---- review findings (2026-11-11), each first reproduced by a failing probe -----------

	@After
	public void clearStopAndHook() {
		FormatConversion.planeReadForTest = null;
		Shutdown.begin();
	}

	/** F1: Y keeps its own pitch; nothing makes a stored anisotropic pixel square. */
	@Test
	public void anAnisotropicPixelKeepsItsOwnYPitch() throws Exception {
		File file = writeCalibrated(new File(folder.newFolder("anisotropic"), "export.tif"), 1, 1, 1, WIDTH, DEPTH,
				0.2, 0.4, 0.5);
		File root = new File(folder.getRoot(), "anisotropic.ome.zarr");
		FormatConversion.convertToOmeZarr(file, root, zarr());
		double[] voxel = OmeZarrDataset.read(root).getProvenance().deskewedVoxelSizeUm;
		assertEquals(0.2, voxel[0], 1e-6);
		assertEquals(0.4, voxel[1], 1e-6);
		assertEquals(0.5, voxel[2], 1e-6);
		assertEquals("the TIFF calibration", OmeZarrDataset.read(root).getProvenance().conversion.samplingSource);
	}

	/** F2: one store has one sampling; a time point that states another is never relabelled. */
	@Test
	public void filesStatingDifferentPixelSizesAreNotStoredAsOne() throws Exception {
		File data = folder.newFolder("conflict");
		writeCalibrated(new File(data, "acq_Time1_Channel1.tif"), 1, 1, 1, WIDTH, DEPTH, 0.2, 0.2, 0.5);
		writeCalibrated(new File(data, "acq_Time2_Channel1.tif"), 1, 2, 1, WIDTH, DEPTH, 0.3, 0.3, 0.5);
		TiffConversionPlan.Settings settings = new TiffConversionPlan.Settings();
		settings.incomplete = TiffConversionPlan.INCOMPLETE_LEAVE_OUT;
		TiffConversionPlan.Dataset tied = plan(data, settings).datasets.get(0);
		assertFalse("neither is the more common, so neither is chosen", tied.convertible());
		assertTrue(tied.problemText(), tied.problemText().contains("pixel size"));

		writeCalibrated(new File(data, "acq_Time3_Channel1.tif"), 1, 3, 1, WIDTH, DEPTH, 0.2, 0.2, 0.5);
		TiffConversionPlan.Dataset leftOut = plan(data, settings).datasets.get(0);
		assertTrue(leftOut.problemText(), leftOut.convertible());
		assertEquals("the odd time point is left out, not relabelled", 2, leftOut.timePoints.size());
		assertEquals(0.2, leftOut.sampling[0], 1e-6);
		assertFalse("and refused by default",
				plan(data, new TiffConversionPlan.Settings()).datasets.get(0).convertible());
	}

	/** F2: a raw file stating another pixel size than its acquisition's parameter file is not that acquisition. */
	@Test
	public void aFileContradictingTheParameterFileIsNotPartOfTheAcquisition() throws Exception {
		File data = folder.newFolder("contradicted");
		writeAcquisition(data, "acq", new int[] { 1 }, 1, 1, WIDTH, DEPTH);
		writeCalibrated(new File(data, "acq_Position0001_Time000002_Channel0001_Frames_1_3.tif"), 1, 2, 1, WIDTH, DEPTH,
				0.2, 0.2, 0.5);
		writeParameters(new File(data, TiffConversionPlan.PARAMETERS_FILE), 2, DEPTH);
		TiffConversionPlan.Dataset dataset = plan(data, new TiffConversionPlan.Settings()).datasets.get(0);
		assertFalse(dataset.convertible());
		assertTrue(dataset.problemText(), dataset.problemText().contains(PARAMETERS));
	}

	/** F4: a time token says nothing about a camera; a crop is not cut into halves it never had. */
	@Test
	public void aTimeTokenAloneDoesNotMakeACameraImage() throws Exception {
		File data = folder.newFolder("crop");
		writeCalibrated(new File(data, "crop_Time1.tif"), 1, 1, 1, WIDTH, DEPTH, 0.2, 0.2, 0.5);
		TiffConversionPlan.Settings split = new TiffConversionPlan.Settings();
		split.channels = TiffConversionPlan.CHANNELS_SPLIT;
		TiffConversionPlan.Dataset dataset = plan(data, split).datasets.get(0);
		assertFalse(dataset.convertible());
		assertTrue(dataset.problemText(), dataset.problemText().contains("declare 'whole camera width'"));
		TiffConversionPlan.Dataset kept = plan(data, new TiffConversionPlan.Settings()).datasets.get(0);
		assertEquals("kept as stored", OpmProvenance.Conversion.LAYOUT_STORED, kept.storedLayout);
	}

	/** F4: with the parameter file there, the camera ROI decides, whatever the names say. */
	@Test
	public void theCameraRoiDecidesWhatIsAWholeCameraWidth() throws Exception {
		File data = folder.newFolder("roi");
		writeAcquisition(data, "acq", new int[] { 1 }, 1, 1, WIDTH, DEPTH);
		File parameters = new File(data, TiffConversionPlan.PARAMETERS_FILE);
		TiffConversionPlan.Settings split = new TiffConversionPlan.Settings();
		split.channels = TiffConversionPlan.CHANNELS_SPLIT;

		writeParameters(parameters, 1, DEPTH);		// ROI 8 x 4: these files
		assertTrue(plan(data, split).datasets.get(0).convertible());

		Files.write(parameters.toPath(), new String(Files.readAllBytes(parameters.toPath()), StandardCharsets.ISO_8859_1)
				.replace("0 8 0 4", "0 16 0 4").getBytes(StandardCharsets.ISO_8859_1));
		TiffConversionPlan.Dataset cropped = plan(data, split).datasets.get(0);
		assertFalse("8 wide is not the 16-wide camera", cropped.convertible());
		assertTrue(cropped.problemText(), cropped.problemText().contains("camera ROI"));
	}

	/** F5: a file whose pixel strips lie past its end is refused before any store is opened. */
	@Test
	public void aFileWhoseStripsLiePastItsEndIsRefusedBeforeWriting() throws Exception {
		File data = folder.newFolder("cut");
		writeAcquisition(data, "acq", new int[] { 1 }, 1, 1, WIDTH, DEPTH);
		File file = new File(data, "acq_Position0001_Time000001_Channel0001_Frames_1_3.tif");
		pointFirstStripPastTheEnd(file);
		assertFalse(TiffCompletionCheck.isReady(file));
		File out = folder.newFolder("out");

		FormatConversion.Options overwrite = zarr();
		overwrite.overwrite = true;
		File store = new File(out, "acq_Position0001.ome.zarr");
		assertTrue(store.mkdirs());
		assertTrue(new File(store, "keep").createNewFile());
		FormatConversion.convertFolder(DataFolder.scan(data, null, true), out, overwrite, false);
		assertTrue("refused before overwrite could remove anything", new File(store, "keep").isFile());
	}

	/** F6: short files do not decide the shape the one complete file is measured against. */
	@Test
	public void shortFilesDoNotOutvoteTheCompleteOne() throws Exception {
		File data = folder.newFolder("short-majority");
		writeVolume(new File(data, "acq_Time1_Channel1_Frames_1_3.tif"), 1, 1, 1, WIDTH, DEPTH - 1);
		writeVolume(new File(data, "acq_Time2_Channel1_Frames_1_3.tif"), 1, 2, 1, WIDTH, DEPTH - 1);
		writeVolume(new File(data, "acq_Time3_Channel1_Frames_1_3.tif"), 1, 3, 1, WIDTH, DEPTH);
		TiffConversionPlan.Settings settings = new TiffConversionPlan.Settings();
		settings.incomplete = TiffConversionPlan.INCOMPLETE_LEAVE_OUT;
		TiffConversionPlan.Dataset dataset = plan(data, settings).datasets.get(0);
		assertTrue(dataset.problemText(), dataset.convertible());
		assertEquals(1, dataset.timePoints.size());
		assertEquals(DEPTH, dataset.depth);
		assertEquals("acq_Time3", dataset.timePoints.get(0).label);
	}

	/** F7: the named file picks the dataset; the filters still decide what of it is converted. */
	@Test
	public void theNamedFileIsFilteredLikeItsSiblings() throws Exception {
		File data = folder.newFolder("anchor");
		writeAcquisition(data, "acq", new int[] { 1 }, 2, 2, WIDTH, DEPTH);
		File named = new File(data, "acq_Position0001_Time000001_Channel0001_Frames_1_3.tif");
		DataFolder.Filter filter = new DataFolder.Filter();
		filter.include = "Channel0002";
		DataFolder found = FormatConversion.scanSelection(named, filter, TiffConversionPlan.SCOPE_DATASET);
		assertEquals(2, found.loose.size());
		for (DataFolder.LooseTiff tiff : found.loose) assertTrue(tiff.file.getName().contains("Channel0002"));
		FormatConversion.Options options = zarr();
		assertTrue(FormatConversion.planText(found, options), FormatConversion.planText(found, options)
				.startsWith("The filters leave out the named TIFF itself."));
	}

	/** F8: committed files are looked at again at resume, not only as the plan first read them. */
	@Test
	public void aResumeLooksAtCommittedFilesAgain() throws Exception {
		File data = folder.newFolder("stale");
		writeAcquisition(data, "acq", new int[] { 1 }, 2, 1, WIDTH, DEPTH);
		File out = folder.newFolder("out");
		File root = new File(out, "acq_Position0001.ome.zarr");
		File second = new File(data, "acq_Position0001_Time000002_Channel0001_Frames_1_3.tif");
		File hidden = new File(data, "later.bin");
		assertTrue(second.renameTo(hidden));
		FormatConversion.convertFolder(DataFolder.scan(data, null, true), out, zarr(), false);
		assertTrue(hidden.renameTo(second));
		assertTrue(new File(root, OmeZarrWriter.SUCCESS_FILE).delete());	// as though stopped after T1

		TiffConversionPlan plan = plan(data, new TiffConversionPlan.Settings());
		TiffConversionPlan.Dataset dataset = plan.datasets.get(0);
		dataset.output = root;
		File committed = dataset.timePoints.get(0).sources().get(0).file;
		assertTrue(committed.setLastModified(committed.lastModified() + 60000));	// after the plan read it
		FormatConversion.Options resume = zarr();
		resume.resume = true;
		try {
			FormatConversion.convertDataset(dataset, resume, null);
			fail("a committed file changed after the check must refuse the resume");
		} catch (IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("changed since this run checked it"));
		}
		assertEquals(1, new OmeZarrWriter(root).getCommittedTimepoints());
	}

	/** F9: a stop is polled before every plane, not only between channels. */
	@Test
	public void eachPlaneAsksWhetherToStop() throws Exception {
		File data = folder.newFolder("poll");
		writeAcquisition(data, "acq", new int[] { 1 }, 1, 1, WIDTH, DEPTH);
		TiffConversionPlan.Slot slot = plan(data, new TiffConversionPlan.Settings()).datasets.get(0)
				.timePoints.get(0).slots.get(0);
		FastTiffReader.PlaneReader reader = new FastTiffReader.PlaneReader(slot.source.file);
		try {
			OmeZarrWriter.VolumePlanes planes = FormatConversion.slotPlanes(reader, slot);
			assertNotNull(planes.plane(0));
			Shutdown.request();
			try {
				planes.plane(1);
				fail("a plane must not be read once a stop was asked for");
			} catch (FormatConversion.Stopped expected) {
				// the channel unwinds here
			}
		} finally {
			reader.close();
		}
	}

	/** F9: a stop in the middle of a channel leaves the store at its last whole time point, and a resume finishes it. */
	@Test
	public void aStopInsideAChannelPublishesNothingAndAResumeFinishes() throws Exception {
		File data = folder.newFolder("interrupted");
		writeAcquisition(data, "acq", new int[] { 1 }, 2, 2, WIDTH, DEPTH);
		File out = folder.newFolder("out");
		File root = new File(out, "acq_Position0001.ome.zarr");
		FormatConversion.Options split = zarr();
		split.channels = TiffConversionPlan.CHANNELS_SPLIT;
		FormatConversion.planeReadForTest = new FormatConversion.PlaneRead() {
			@Override public void read(TiffConversionPlan.Slot slot, int z) {
				// time point 2, acquisition channel 2, its left half, after its second plane
				if (slot.source.time == 2 && slot.source.channel == 2 && slot.x0 == 0 && z == 1) Shutdown.request();
			}
		};
		FormatConversion.convertFolder(DataFolder.scan(data, null, true), out, split, false);
		FormatConversion.planeReadForTest = null;

		assertEquals("time point 2 is not published", 1, new OmeZarrWriter(root).getCommittedTimepoints());
		assertFalse(new File(root, OmeZarrWriter.SUCCESS_FILE).exists());
		assertTrue("its first channels were staged", new File(root, "s0" + File.separator + "1.0.0.0.0").isFile());

		Shutdown.begin();
		FormatConversion.Options resume = zarr();
		resume.channels = TiffConversionPlan.CHANNELS_SPLIT;
		resume.resume = true;
		FormatConversion.convertFolder(DataFolder.scan(data, null, true), out, resume, false);
		OmeZarrDataset finished = OmeZarrDataset.read(root);
		assertTrue(finished.isComplete());
		assertEquals(2, finished.getTimepointCount());
		for (int c = 1; c <= 2; c++) {
			assertPlanes(finished, 1, 2 * (c - 1), 1, 2, c, 0, WIDTH / 2);
			assertPlanes(finished, 1, 2 * (c - 1) + 1, 1, 2, c, WIDTH / 2, WIDTH / 2);
		}
	}


	// ---- deskewed TIFF results (F3) -----------------------------------------------------

	/** Channel series of one result become one store; split, the halves get the six projections of themselves. */
	@Test
	public void resultSeriesOfOneAcquisitionBecomeOneStoreAndSplit() throws Exception {
		File data = folder.newFolder("results");
		int depth = 5;		// not the _Frames_1_3 the names still carry from the raw files
		for (int t = 1; t <= 2; t++)
			for (int c = 1; c <= 2; c++)
				writeCalibrated(new File(data, String.format("acq_Position0001_Time%06d_Channel%04d_Frames_1_3-deskewed.tif",
						t, c)), 1, t, c, WIDTH, depth, 0.116, 0.116, 0.116);
		DataFolder found = DataFolder.scan(data, null, true);
		assertEquals(2, found.results.size());
		assertTrue(found.loose.isEmpty());
		File out = folder.newFolder("out");
		FormatConversion.Options split = zarr();
		split.channels = TiffConversionPlan.CHANNELS_SPLIT;

		FormatConversion.convertFolder(found, out, split, false);

		File[] stores = out.listFiles(new java.io.FileFilter() {
			@Override public boolean accept(File file) { return file.getName().endsWith(".ome.zarr"); }
		});
		assertEquals("one acquisition, one store", 1, stores.length);
		OmeZarrDataset dataset = OmeZarrDataset.read(stores[0]);
		assertEquals("acq_Position0001_Frames_1_3.ome.zarr", stores[0].getName());
		assertEquals(Arrays.asList("_Channel0001-left", "_Channel0001-right", "_Channel0002-left", "_Channel0002-right"),
				dataset.getChannelLabels());
		assertEquals(2, dataset.getTimepointCount());
		assertEquals(depth, dataset.getDepth());
		OpmProvenance provenance = dataset.getProvenance();
		assertEquals(OpmProvenance.CONTENT_DESKEWED, provenance.contentKind);
		assertFalse("deskewed halves, unflipped and unaligned", provenance.alignApplied);
		assertEquals(0.116, provenance.deskewedVoxelSizeUm[2], 1e-6);
		assertEquals(6, dataset.getAvailableProjections().size());
		for (int t = 1; t <= 2; t++)
			for (int c = 1; c <= 2; c++)
				for (int side = 0; side < 2; side++) {
					int stored = 2 * (c - 1) + side;
					assertPlanes(dataset, t - 1, stored, 1, t, c, side * WIDTH / 2, WIDTH / 2);
					assertProjectionsOfHalf(dataset, t - 1, stored, 1, t, c, side * WIDTH / 2, depth);
				}
	}

	/** A composed result keeps its channels, final pixels, and is not split again. */
	@Test
	public void aComposedResultKeepsItsChannelsAndIsNotSplitAgain() throws Exception {
		File data = folder.newFolder("composed");
		File file = new File(data, "acq_Position0001_Time000001_Channels0001-0002_Frames_1_3-aligned-deskewed.tif");
		writeHyperstack(file, 2, DEPTH, 1);
		File out = folder.newFolder("out");
		FormatConversion.convertFolder(DataFolder.scan(data, null, true), out, zarr(), false);
		OmeZarrDataset dataset = OmeZarrDataset.read(
				new File(out, "acq_Position0001_Channels0001-0002_Frames_1_3-aligned.ome.zarr"));
		assertEquals(Arrays.asList("C1", "C2"), dataset.getChannelLabels());
		assertTrue("its flip and alignment are in its pixels", dataset.getProvenance().alignApplied);

		TiffConversionPlan.Settings split = new TiffConversionPlan.Settings();
		split.channels = TiffConversionPlan.CHANNELS_SPLIT;
		TiffConversionPlan plan = TiffConversionPlan.byName(Collections.<File>emptyList(),
				DataFolder.scan(data, null, true).results, split);
		plan.check();
		assertFalse(plan.datasets.get(0).convertible());
	}

	/** Each result series on its own in file scope, with its old store name. */
	@Test
	public void inFileScopeEachResultSeriesIsItsOwnStore() throws Exception {
		File data = folder.newFolder("series");
		for (int c = 1; c <= 2; c++)
			writeCalibrated(new File(data, String.format("acq_Time000001_Channel%04d-deskewed.tif", c)), 1, 1, c,
					WIDTH, DEPTH, 0.116, 0.116, 0.116);
		File out = folder.newFolder("out");
		FormatConversion.Options options = zarr();
		options.scope = TiffConversionPlan.SCOPE_FILE;
		FormatConversion.convertFolder(DataFolder.scan(data, null, true), out, options, false);
		for (int c = 1; c <= 2; c++) {
			OmeZarrDataset dataset = OmeZarrDataset.read(new File(out, String.format("acq_Channel%04d.ome.zarr", c)));
			assertEquals(Arrays.asList(String.format("_Channel%04d-whole", c)), dataset.getChannelLabels());
		}
	}

	/** A renamed deskewed TIFF is taken as deskewed when the run says so: its projections come with it. */
	@Test
	public void declaredDeskewedContentIsStoredWithItsProjections() throws Exception {
		File data = folder.newFolder("renamed");
		writeCalibrated(new File(data, "volume.tif"), 1, 1, 1, WIDTH, DEPTH, 0.116, 0.116, 0.116);
		File out = folder.newFolder("out");
		FormatConversion.Options options = zarr();
		options.content = TiffConversionPlan.CONTENT_DESKEWED;
		FormatConversion.convertFolder(DataFolder.scan(data, null, true), out, options, false);
		OmeZarrDataset dataset = OmeZarrDataset.read(new File(out, "volume.ome.zarr"));
		assertEquals(OpmProvenance.CONTENT_DESKEWED, dataset.getProvenance().contentKind);
		assertEquals(6, dataset.getAvailableProjections().size());
	}


	// ---- second review (2026-11-11): R2-A, R2-B, R2-C -----------------------------------

	/** R2-A: an older deskew result states only its unit and spacing; it is isotropic at that spacing, as it always read. */
	@Test
	public void anOlderDeskewResultWithOnlyASpacingKeepsItsIsotropicPitch() throws Exception {
		File data = folder.newFolder("legacy");
		File file = writeSpacingOnly(new File(data, "sample_Time1_Channels0001-0002-aligned-deskewed.tif"), 0.116);
		assertEquals("what the result reader has always said", 0.116, TiffResultDataset.Layout.read(file).pixelWidth, 1e-9);
		TiffConversionPlan.Dataset dataset = plan(data, new TiffConversionPlan.Settings()).datasets.get(0);
		assertTrue(dataset.problemText(), dataset.convertible());
		assertEquals(0.116, dataset.sampling[0], 1e-9);
		assertEquals(0.116, dataset.sampling[1], 1e-9);
		assertEquals(0.116, dataset.sampling[2], 1e-9);
		assertTrue(dataset.samplingSource, dataset.samplingSource.contains("from its Z spacing"));
	}

	/** R2-A: a raw or renamed file with only a spacing keeps its Z; X and Y are not made up from it. */
	@Test
	public void aSpacingAloneGivesARawFileItsZButNotItsXY() throws Exception {
		File data = folder.newFolder("spacing-only");
		writeSpacingOnly(new File(data, "volume.tif"), 0.5);
		TiffConversionPlan.Dataset dataset = plan(data, new TiffConversionPlan.Settings()).datasets.get(0);
		assertTrue(dataset.problemText(), dataset.convertible());
		assertEquals(1, dataset.sampling[0], 1e-9);
		assertEquals(1, dataset.sampling[1], 1e-9);
		assertEquals(0.5, dataset.sampling[2], 1e-9);
		boolean said = false;
		for (String note : dataset.notes) said |= note.contains("no X/Y pixel size");
		assertTrue(dataset.notes.toString(), said);
	}

	/** R2-B: a deskewed result's own interval wins over the acquisition's; the parameter file only fills a gap. */
	@Test
	public void aDeskewedResultKeepsTheIntervalItStates() throws Exception {
		File acquisition = folder.newFolder("timing");
		writeParameters(new File(acquisition, PARAMETERS), 817, DEPTH);		// 300 s
		File result = new File(acquisition, "result");
		writeTimed(new File(result, "sample_Time1_Channel0001-deskewed.tif"), 1, 600);
		writeTimed(new File(result, "sample_Time2_Channel0001-deskewed.tif"), 2, 600);
		TiffConversionPlan.Dataset decimated = plan(result, new TiffConversionPlan.Settings()).datasets.get(0);
		assertTrue(decimated.problemText(), decimated.convertible());
		assertEquals(600, decimated.frameIntervalSeconds, 1e-9);
		assertEquals(600, decimated.timePoints.get(1).elapsedSeconds, 1e-9);

		File unstated = new File(acquisition, "unstated");
		writeTimed(new File(unstated, "sample_Time1_Channel0001-deskewed.tif"), 1, 0);
		writeTimed(new File(unstated, "sample_Time2_Channel0001-deskewed.tif"), 2, 0);
		TiffConversionPlan.Dataset filled = plan(unstated, new TiffConversionPlan.Settings()).datasets.get(0);
		assertEquals("from the parameter file one folder up", 300, filled.frameIntervalSeconds, 1e-9);

		File mixed = new File(acquisition, "mixed");
		writeTimed(new File(mixed, "sample_Time1_Channel0001-deskewed.tif"), 1, 600);
		writeTimed(new File(mixed, "sample_Time2_Channel0001-deskewed.tif"), 2, 300);
		TiffConversionPlan.Dataset refused = plan(mixed, new TiffConversionPlan.Settings()).datasets.get(0);
		assertFalse(refused.convertible());
		assertTrue(refused.problemText(), refused.problemText().contains("different frame intervals"));
	}

	/** R2-C: a named result left out by the filters still says which family is meant; its other channels remain. */
	@Test
	public void aNamedResultLeftOutByTheFiltersStillPicksItsFamily() throws Exception {
		File data = folder.newFolder("result-anchor");
		File named = null;
		for (int t = 1; t <= 2; t++)
			for (int c = 1; c <= 2; c++) {
				File file = writeTimed(new File(data, "sample_Time" + t + "_Channel000" + c + "-deskewed.tif"), t, 300);
				if (t == 1 && c == 1) named = file;
			}
		DataFolder.Filter filter = new DataFolder.Filter();
		filter.include = "Channel0002";
		DataFolder found = FormatConversion.scanSelection(named, filter, TiffConversionPlan.SCOPE_DATASET);
		assertEquals(1, found.results.size());
		assertEquals(2, found.results.get(0).getView(TiffResultDataset.VOLUME).frameCount());
		assertTrue(found.results.get(0).getDisplayName().contains("Channel0002"));
		assertTrue(found.loose.isEmpty());
	}


	// ---- helpers ------------------------------------------------------------------------

	private static final String PARAMETERS = TiffConversionPlan.PARAMETERS_FILE;

	/** A unit and a spacing in the description and no resolution tags, as older deskew results were written. */
	private static File writeSpacingOnly(File file, double spacing) throws IOException {
		FastTiffWriter.Layout layout = new FastTiffWriter.Layout();
		layout.width = WIDTH;
		layout.height = HEIGHT;
		layout.slices = DEPTH;
		layout.unit = "micron";
		layout.pixelDepth = spacing;
		file.getParentFile().mkdirs();
		FastTiffWriter.write(new FastTiffWriter.PlaneSource() {
			@Override public ImageProcessor plane(int z) { return TiffDatasetConversionTest.plane(1, 1, 1, z, WIDTH); }
		}, layout, file, 1);
		return file;
	}

	/** A calibrated 0.2 micron result volume stating this frame interval; 0 states none. */
	private static File writeTimed(File file, int time, double interval) throws IOException {
		ImageStack stack = new ImageStack(WIDTH, HEIGHT);
		for (int z = 0; z < DEPTH; z++) stack.addSlice(null, plane(1, time, 1, z, WIDTH));
		ImagePlus image = new ImagePlus("timed", stack);
		Calibration calibration = new Calibration();
		calibration.pixelWidth = calibration.pixelHeight = calibration.pixelDepth = 0.2;
		calibration.setUnit("micron");
		calibration.frameInterval = interval;
		image.setCalibration(calibration);
		file.getParentFile().mkdirs();
		FastTiffWriter.write(image, file, 1);
		return file;
	}

	/** A calibrated 16-bit stack in microns, as ImageJ and the deskew writers leave one. */
	private static File writeCalibrated(File file, int position, int time, int channel, int width, int depth,
			double x, double y, double z) throws IOException {
		ImageStack stack = new ImageStack(width, HEIGHT);
		for (int plane = 0; plane < depth; plane++) stack.addSlice(null, plane(position, time, channel, plane, width));
		ImagePlus image = new ImagePlus("calibrated", stack);
		Calibration calibration = new Calibration();
		calibration.pixelWidth = x;
		calibration.pixelHeight = y;
		calibration.pixelDepth = z;
		calibration.setUnit("micron");
		image.setCalibration(calibration);
		file.getParentFile().mkdirs();
		FastTiffWriter.write(image, file, 1);
		return file;
	}

	/** Every stored projection of one channel equals ProjectionBatch's own of that half of its source. */
	private static void assertProjectionsOfHalf(OmeZarrDataset dataset, int storedTime, int storedChannel,
			int position, int time, int channel, int x0, int depth) throws Exception {
		int width = WIDTH / 2;
		ImageStack stack = new ImageStack(width, HEIGHT);
		for (int z = 0; z < depth; z++) {
			ShortProcessor half = new ShortProcessor(width, HEIGHT);
			for (int y = 0; y < HEIGHT; y++)
				for (int x = 0; x < width; x++) half.set(x, y, value(position, time, channel, z, x0 + x, y));
			stack.addSlice(null, half);
		}
		List<ProjectionBatch.Request> requests = FormatConversion.projectionRequests(OmeZarrSession.PROJECTIONS);
		List<ImagePlus> expected = ProjectionBatch.compute(new ImagePlus("half", stack), requests, false);
		for (int p = 0; p < requests.size(); p++) {
			String name = OmeZarrSession.PROJECTIONS.get(p);
			OmeZarrPlaneReader reader = new OmeZarrPlaneReader(dataset, "projections/" + name);
			try {
				ImageProcessor stored = reader.readPlane(storedChannel, storedTime);
				ImageProcessor wanted = expected.get(p).getProcessor();
				assertEquals(name, wanted.getWidth(), stored.getWidth());
				assertEquals(name, wanted.getHeight(), stored.getHeight());
				for (int y = 0; y < wanted.getHeight(); y++)
					for (int x = 0; x < wanted.getWidth(); x++)
						assertEquals(name + " (" + x + "," + y + ")", wanted.get(x, y), stored.get(x, y));
			} finally {
				reader.close();
			}
		}
	}

	/** Point a TIFF's first pixel strip past its end, leaving every directory readable. */
	private static void pointFirstStripPastTheEnd(File file) throws IOException {
		java.io.RandomAccessFile raw = new java.io.RandomAccessFile(file, "rw");
		try {
			raw.seek(4);
			long ifd = Integer.reverseBytes(raw.readInt()) & 0xffffffffL;
			raw.seek(ifd);
			int entries = Short.reverseBytes(raw.readShort()) & 0xffff;
			for (int i = 0; i < entries; i++) {
				long at = ifd + 2 + 12L * i;
				raw.seek(at);
				if ((Short.reverseBytes(raw.readShort()) & 0xffff) == 273) {
					raw.seek(at + 8);
					raw.writeInt(Integer.reverseBytes((int) file.length() + 4096));
					return;
				}
			}
			fail("no StripOffsets in " + file);
		} finally {
			raw.close();
		}
	}

	private static FormatConversion.Options zarr() {
		FormatConversion.Options options = new FormatConversion.Options();
		options.target = FormatConversion.TARGET_ZARR;
		options.tryGpu = false;
		return options;
	}

	/** The plan a run would make of this folder: its loose TIFFs and its recognised results. */
	private static TiffConversionPlan plan(File data, TiffConversionPlan.Settings settings) {
		DataFolder found = DataFolder.scan(data, null, true);
		TiffConversionPlan plan = TiffConversionPlan.byName(FormatConversion.looseFiles(found), found.results, settings);
		plan.check();
		return plan;
	}

	/** The microscope's naming: one file per position, time point and acquisition channel. */
	private void writeAcquisition(File data, String stem, int[] positions, int times, int channels,
			int width, int depth) throws IOException {
		for (int position : positions)
			for (int t = 1; t <= times; t++)
				for (int c = 1; c <= channels; c++)
					writeVolume(new File(data, String.format("%s_Position%04d_Time%06d_Channel%04d_Frames_1_%d.tif",
							stem, position, t, c, depth)), position, t, c, width, depth);
	}

	/** Every pixel says where it came from: position, time, channel, plane, row and column. */
	static int value(int position, int time, int channel, int z, int x, int y) {
		return (position * 20000 + (time % 10) * 2000 + channel * 500 + z * 40 + y * 8 + x) & 0xffff;
	}

	/** An uncompressed, uncalibrated 16-bit stack: the closest stand-in for a raw file. */
	private static File writeVolume(File file, int position, int time, int channel, int width, int depth)
			throws IOException {
		ImageStack stack = new ImageStack(width, HEIGHT);
		for (int z = 0; z < depth; z++) stack.addSlice(null, plane(position, time, channel, z, width));
		ImagePlus image = new ImagePlus("raw", stack);
		file.getParentFile().mkdirs();
		FastTiffWriter.write(image, file, 0);
		return file;
	}

	/** One ImageJ hyperstack, C x Z x T in XYCZT order, calibrated at 0.2 micron. */
	private static File writeHyperstack(File file, int channels, int depth, int frames) throws IOException {
		ImageStack stack = new ImageStack(WIDTH, HEIGHT);
		for (int t = 1; t <= frames; t++)
			for (int z = 0; z < depth; z++)
				for (int c = 1; c <= channels; c++) stack.addSlice(null, plane(1, t, c, z, WIDTH));
		ImagePlus image = new ImagePlus("hyper", stack);
		image.setDimensions(channels, depth, frames);
		image.setOpenAsHyperStack(true);
		Calibration calibration = new Calibration();
		calibration.pixelWidth = calibration.pixelHeight = 0.2;
		calibration.pixelDepth = 0.5;
		calibration.setUnit("micron");
		image.setCalibration(calibration);
		file.getParentFile().mkdirs();
		FastTiffWriter.write(image, file, 1);
		return file;
	}

	private static ShortProcessor plane(int position, int time, int channel, int z, int width) {
		ShortProcessor plane = new ShortProcessor(width, HEIGHT);
		for (int y = 0; y < HEIGHT; y++)
			for (int x = 0; x < width; x++) plane.set(x, y, value(position, time, channel, z, x, y));
		return plane;
	}

	/** Every plane of one stored channel equals columns {@code x0..x0+width} of its source. */
	private static void assertPlanes(OmeZarrDataset dataset, int storedTime, int storedChannel,
			int position, int time, int channel, int x0, int width) throws Exception {
		OmeZarrPlaneReader reader = new OmeZarrPlaneReader(dataset, "s0");
		try {
			for (int z = 0; z < dataset.getDepth(); z++) {
				ImageProcessor plane = reader.readPlane(z, storedChannel, storedTime);
				assertEquals(width, plane.getWidth());
				for (int y = 0; y < HEIGHT; y++)
					for (int x = 0; x < width; x++)
						assertEquals("t" + storedTime + " c" + storedChannel + " z" + z + " (" + x + "," + y + ")",
								value(position, time, channel, z, x0 + x, y), plane.get(x, y));
			}
		} finally {
			reader.close();
		}
	}

	private static void writeParameters(File file, int plannedTimePoints, int planes) throws IOException {
		List<String> lines = new ArrayList<String>(Arrays.asList(
				"EXPERIMENT:", "Interval: 5 Minute", "Number of Channels: 2",
				"Number of Time Points: " + plannedTimePoints, "OPM:", "Tilt angle: 33.500000",
				"um per galvo DU: 0.013250", "Galvo DUs per step: 20", "Planes per volume: " + planes,
				"CAMERA:", "Pixel size at object /um: 0.116000", "Camera ROI X0, X1, Y0, Y1: 0 8 0 4"));
		Files.write(file.toPath(), lines, StandardCharsets.ISO_8859_1);
	}
}
