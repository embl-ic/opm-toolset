package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** End-to-end append/resume contract for the shared Batch/Live OME-Zarr session. */
public class OmeZarrSessionTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void resumesSuppressesDuplicatesAndFinalizes() throws Exception {
		File rawFolder = folder.newFolder("raw");
		File zarrRoot = new File(folder.getRoot(), "acquisition.ome.zarr");
		File firstC1 = raw(rawFolder, "sample_Time000001_Channel0001.tif", 10);
		File firstC2 = raw(rawFolder, "sample_Time000001_Channel0002.tif", 50);
		File secondC1 = raw(rawFolder, "sample_Time000002_Channel0001.tif", 100);
		File secondC2 = raw(rawFolder, "sample_Time000002_Channel0002.tif", 150);
		OpmProvenance requested = provenance(rawFolder);
		File emptyRoot = new File(folder.getRoot(), "empty.ome.zarr");
		OmeZarrSession empty = new OmeZarrSession(emptyRoot, rawFolder, provenance(rawFolder),
				Transform.identity(), false, true);
		empty.markComplete();
		empty.close();
		assertFalse("an empty/failed Live session must not get a success marker",
				new File(emptyRoot, OmeZarrWriter.SUCCESS_FILE).exists());

		OpmTimepointProcessor.TimePoint first = timePoint(
				"Time000001", 0.0, firstC1, firstC2);
		OmeZarrSession initial = new OmeZarrSession(zarrRoot, rawFolder, requested,
				Transform.identity(), false, true);
		OpmTimepointProcessor.Result preparedChannels = OpmTimepointProcessor.process(
				first, Transform.identity(), false);
		OmeZarrSession.PreparedTimePoint prepared = OmeZarrSession.prepare(
				preparedChannels, false, true);
		try {
			assertTrue(initial.appendPrepared(first, prepared));
		} finally {
			prepared.close();
			preparedChannels.close();
		}
		assertEquals(1, initial.getCommittedTimepoints());
		initial.close(); // interrupted acquisition: committed data remains valid, but not final
		assertFalse(new File(zarrRoot, OmeZarrWriter.SUCCESS_FILE).exists());

		OmeZarrSession resumed = new OmeZarrSession(zarrRoot, rawFolder, provenance(rawFolder),
				Transform.identity(), false, true);
		assertFalse("a queued sibling or restart must not duplicate an existing T label",
				resumed.append(first));
		assertTrue(resumed.append(timePoint("Time000002", 2.5, secondC1, secondC2)));
		resumed.markComplete();
		resumed.close();

		JsonObject volume = json(new File(zarrRoot, "s0/.zarray"));
		assertEquals(2, volume.getAsJsonArray("shape").get(0).getAsInt());
		assertEquals(4, volume.getAsJsonArray("shape").get(1).getAsInt());
		JsonObject attrs = json(new File(zarrRoot, ".zattrs"));
		JsonObject opm = attrs.getAsJsonObject(OpmProvenance.KEY);
		assertEquals(Arrays.asList("Time000001", "Time000002"),
				Arrays.asList(opm.getAsJsonArray("timePointLabels").get(0).getAsString(),
						opm.getAsJsonArray("timePointLabels").get(1).getAsString()));
		assertEquals(2.5, opm.getAsJsonArray("timePointElapsedSeconds").get(1).getAsDouble(), 0.0);
		assertEquals("sample_Time000001_Channel0001.tif",
				opm.getAsJsonArray("sourceFiles").get(0).getAsString());
		assertEquals("complete", attrs.getAsJsonObject(OmeZarrWriter.WRITE_STATE_KEY)
				.get("status").getAsString());
		assertTrue(new File(zarrRoot, OmeZarrWriter.SUCCESS_FILE).isFile());
		for (String projection : OmeZarrSession.PROJECTIONS)
			assertEquals(2, json(new File(zarrRoot, "projections/" + projection + "/.zarray"))
					.getAsJsonArray("shape").get(0).getAsInt());
	}

	/**
	 * The channel layout is display metadata that arrived after stores were already being
	 * written. A store without one takes the resuming run's, so it opens as that run's result;
	 * a store that has one keeps it, as the rest of its provenance is kept.
	 */
	@Test
	public void aResumedStoreGainsARecordedChannelLayoutButKeepsAnExistingOne() throws Exception {
		File rawFolder = folder.newFolder("raw");
		File zarrRoot = new File(folder.getRoot(), "layout.ome.zarr");
		File firstC1 = raw(rawFolder, "sample_Time000001_Channel0001.tif", 10);
		File secondC1 = raw(rawFolder, "sample_Time000002_Channel0001.tif", 100);
		File thirdC1 = raw(rawFolder, "sample_Time000003_Channel0001.tif", 200);

		OmeZarrSession older = new OmeZarrSession(zarrRoot, rawFolder, provenance(rawFolder),
				Transform.identity(), false, true);
		assertTrue(older.append(timePoint("Time000001", 0.0, firstC1)));
		older.close();
		assertFalse("written before the layout was recorded",
				json(new File(zarrRoot, ".zattrs")).getAsJsonObject(OpmProvenance.KEY).has("deskewChannelOption"));

		OpmProvenance whole = provenance(rawFolder);
		whole.deskewChannelOption = "whole image";
		OmeZarrSession resumed = new OmeZarrSession(zarrRoot, rawFolder, whole,
				Transform.identity(), false, true);
		assertTrue(resumed.append(timePoint("Time000002", 2.5, secondC1)));
		resumed.close();
		assertEquals("whole image", json(new File(zarrRoot, ".zattrs"))
				.getAsJsonObject(OpmProvenance.KEY).get("deskewChannelOption").getAsString());

		OpmProvenance fold = provenance(rawFolder);
		fold.deskewChannelOption = "fold by midline";
		OmeZarrSession again = new OmeZarrSession(zarrRoot, rawFolder, fold,
				Transform.identity(), false, true);
		assertTrue(again.append(timePoint("Time000003", 5.0, thirdC1)));
		again.close();
		assertEquals("whole image", json(new File(zarrRoot, ".zattrs"))
				.getAsJsonObject(OpmProvenance.KEY).get("deskewChannelOption").getAsString());
	}

	/**
	 * The flip side and the interpolation are how a store is shown, not what it stores, so a
	 * run resumed with either changed appends and the store keeps what it began with. Anything
	 * that does reach a pixel - the geometry, a matrix - is still refused.
	 */
	@Test
	public void aResumeMayChangeHowTheStoreIsShownButNotWhatItStores() throws Exception {
		File rawFolder = folder.newFolder("raw");
		File zarrRoot = new File(folder.getRoot(), "viewing.ome.zarr");
		File firstC1 = raw(rawFolder, "sample_Time000001_Channel0001.tif", 10);
		File secondC1 = raw(rawFolder, "sample_Time000002_Channel0001.tif", 100);

		OpmProvenance begun = provenance(rawFolder);
		begun.alignInterpolate = true;
		OmeZarrSession first = new OmeZarrSession(zarrRoot, rawFolder, begun,
				Transform.identity(), false, true);
		assertTrue(first.append(timePoint("Time000001", 0.0, firstC1)));
		first.close();

		OpmProvenance otherView = provenance(rawFolder);
		otherView.alignFlipHalf = BatchChannelOperation.FLIP_LEFT;
		otherView.alignInterpolate = false;
		OmeZarrSession resumed = new OmeZarrSession(zarrRoot, rawFolder, otherView,
				Transform.identity(), false, true);
		assertTrue("the other side and nearest neighbour still resume",
				resumed.append(timePoint("Time000002", 2.5, secondC1)));
		assertEquals(2, resumed.getCommittedTimepoints());
		resumed.close();
		JsonObject opm = json(new File(zarrRoot, ".zattrs")).getAsJsonObject(OpmProvenance.KEY);
		assertEquals("the store keeps the side it began with",
				BatchChannelOperation.FLIP_RIGHT, opm.get("alignFlipHalf").getAsString());
		assertTrue("and the sampling", opm.get("alignInterpolate").getAsBoolean());

		OpmProvenance otherMatrix = provenance(rawFolder);
		otherMatrix.alignMatrix = new double[][] { { 1, 0, 9 }, { 0, 1, 0 } };
		try {
			new OmeZarrSession(zarrRoot, rawFolder, otherMatrix, Transform.identity(), false, true).close();
			org.junit.Assert.fail("a different alignment matrix must still refuse to resume");
		} catch (java.io.IOException expected) {
			assertTrue(expected.getMessage().contains("different geometry or alignment"));
		}
	}

	private File raw(File rawFolder, String name, int offset) throws Exception {
		File file = new File(rawFolder, name);
		ImageStack stack = new ImageStack(4, 3);
		for (int z = 0; z < 2; z++) {
			short[] pixels = new short[12];
			for (int i = 0; i < pixels.length; i++) pixels[i] = (short) (offset + z * 20 + i);
			stack.addSlice(new ShortProcessor(4, 3, pixels, null));
		}
		ImagePlus image = new ImagePlus(name, stack);
		boolean compression = VolumeIO.isCompressOutput();
		VolumeIO.setCompressOutput(false);
		try {
			assertTrue(VolumeIO.saveTiff(image, file));
		} finally {
			VolumeIO.setCompressOutput(compression);
			image.close();
		}
		return file;
	}

	private static OpmTimepointProcessor.TimePoint timePoint(
			String label, double elapsedSeconds, File... files) {
		return new OpmTimepointProcessor.TimePoint(label, Arrays.asList(files), elapsedSeconds);
	}

	private static OpmProvenance provenance(File rawFolder) {
		OpmProvenance provenance = new OpmProvenance();
		provenance.datasetName = "acquisition";
		provenance.sourceFolder = rawFolder.getAbsolutePath();
		provenance.xyPixelSizeUm = 0.1;
		provenance.zStepSizeUm = 0.4;
		provenance.opmAngleDegrees = 30.0;
		provenance.frameIntervalSeconds = 2.5;
		provenance.deskewedVoxelSizeUm = new double[] { 0.1, 0.2, 0.3 };
		provenance.deskewMatrix = Transform.identity();
		provenance.alignMatrix = new double[][] { { 1, 0, 3.25 }, { 0, 1, -1.5 } };
		provenance.alignMatrixSource = "beads-align.csv";
		provenance.alignApplied = false;
		provenance.alignFlipHalf = BatchChannelOperation.FLIP_RIGHT;
		return provenance;
	}

	private static JsonObject json(File file) throws Exception {
		return JsonParser.parseString(new String(
				Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
	}
}
