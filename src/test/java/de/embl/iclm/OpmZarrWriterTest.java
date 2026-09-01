package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.zarr.N5ZarrReader;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Format-contract and crash-recovery tests for the canonical writer. */
public class OpmZarrWriterTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void writesCanonicalDatasetAndN5ReadsIt() throws Exception {
		File root = new File(folder.getRoot(), "two-timepoints.ome.zarr");
		OpmProvenance provenance = sampleProvenance("two-timepoints");
		OpmZarrWriter writer = new OpmZarrWriter(root);
		writer.createAppendableVolume(3, 2, 2, 2);
		createAllProjectionArrays(writer, 3, 2, 2, 2);
		writer.writeMetadata(provenance, OpmZarrSession.PROJECTIONS);

		for (int t = 0; t < 2; t++) {
			for (int c = 0; c < 2; c++) {
				ImagePlus volume = volume(3, 2, 2, 1000 * t + 100 * c);
				writer.writeVolumeChannel(volume, c, t);
				writeAllProjections(writer, volume, c, t);
				volume.close();
			}
			writer.commitTimePoint(t, "Time" + (t + 1),
					Arrays.asList("Time" + (t + 1) + "_Channel0001.tif",
							"Time" + (t + 1) + "_Channel0002.tif"), t * 2.5);
		}
		writer.markComplete();

		JsonObject volumeMetadata = json(new File(root, "s0/.zarray"));
		assertEquals(Arrays.asList(2L, 2L, 2L, 2L, 3L), longs(volumeMetadata.getAsJsonArray("shape")));
		assertEquals(Arrays.asList(1L, 1L, 1L, 2L, 3L), longs(volumeMetadata.getAsJsonArray("chunks")));
		assertEquals("<u2", volumeMetadata.get("dtype").getAsString());
		assertEquals("gzip", volumeMetadata.getAsJsonObject("compressor").get("id").getAsString());
		assertEquals(1, volumeMetadata.getAsJsonObject("compressor").get("level").getAsInt());
		assertTrue(volumeMetadata.toString(), volumeMetadata.has("filters"));
		assertTrue(volumeMetadata.get("filters").isJsonNull());

		JsonObject attrs = json(new File(root, ".zattrs"));
		assertEquals("complete", attrs.getAsJsonObject(OpmZarrWriter.WRITE_STATE_KEY)
				.get("status").getAsString());
		assertEquals(2, attrs.getAsJsonObject(OpmZarrWriter.WRITE_STATE_KEY)
				.get("committedTimepoints").getAsInt());
		assertEquals(6, attrs.getAsJsonObject("opm_projections")
				.getAsJsonArray("available").size());
		assertProjectionCalibration(attrs, "maxX", "y", "z", 0.2, 0.3);
		assertProjectionCalibration(attrs, "maxY", "z", "x", 0.3, 0.1);
		assertProjectionCalibration(attrs, "maxZ", "y", "x", 0.2, 0.1);
		assertFalse(attrs.getAsJsonObject("opm").get("alignApplied").getAsBoolean());
		assertEquals(Double.doubleToLongBits(provenance.alignMatrix[0][2]),
				Double.doubleToLongBits(attrs.getAsJsonObject("opm").getAsJsonArray("alignMatrix")
						.get(0).getAsJsonArray().get(2).getAsDouble()));
		assertTrue(new File(root, OpmZarrWriter.SUCCESS_FILE).isFile());

		short[] pixels = chunkPixels(new File(root, "s0/0.0.0.0.0"));
		assertArrayEquals(new short[] { 0, 1, 2, 3, 4, 5 }, pixels);

		N5Reader reader = new N5ZarrReader(root.getAbsolutePath());
		try {
			assertTrue("N5 sees s0; root=" + reader.getURI() + ", entries="
					+ Arrays.toString(reader.list("")), reader.datasetExists("s0"));
			DatasetAttributes n5 = reader.getDatasetAttributes("s0");
			org.junit.Assert.assertNotNull("N5 parsed s0/.zarray", n5);
			// n5-zarr presents C-order Zarr dimensions in its native reversed axis order.
			assertArrayEquals(new long[] { 3, 2, 2, 2, 2 }, n5.getDimensions());
			assertArrayEquals(new int[] { 3, 2, 1, 1, 1 }, n5.getBlockSize());
			DataBlock<?> block = reader.readBlock("s0", n5, 0, 0, 0, 0, 0);
			org.junit.Assert.assertNotNull("N5 read the first gzip chunk", block);
			assertArrayEquals(new short[] { 0, 1, 2, 3, 4, 5 }, (short[]) block.getData());
		} finally {
			reader.close();
		}
	}

	@Test
	public void refusesIncompleteCommitAndRecoversStagedTimepoint() throws Exception {
		File root = new File(folder.getRoot(), "recover.ome.zarr");
		OpmZarrWriter first = new OpmZarrWriter(root);
		first.createAppendableVolume(2, 2, 1, 1);
		first.createAppendableProjection("maxZ", 2, 2, 1);
		first.writeMetadata(sampleProvenance("recover"), Collections.singletonList("maxZ"));
		ImagePlus volume = volume(2, 2, 1, 10);
		first.writeVolumeChannel(volume, 0, 0);
		try {
			first.commitTimePoint(0, "Time1", Collections.singletonList("a.tif"), 0);
			fail("projection chunk is required before publication");
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains("Missing staged Zarr chunk"));
		}
		first.close(); // simulate interruption, without commit or _SUCCESS

		OpmZarrWriter resumed = new OpmZarrWriter(root);
		assertEquals(0, resumed.getCommittedTimepoints());
		assertEquals(0L, json(new File(root, "s0/.zarray")).getAsJsonArray("shape").get(0).getAsLong());
		resumed.writeVolumeChannel(volume, 0, 0); // safely overwrite staging chunks
		ImagePlus projection = image(2, 2, 20);
		resumed.writeProjection(projection, "maxZ", 0, 0);
		resumed.commitTimePoint(0, "Time1", Collections.singletonList("a.tif"), 0);
		assertTrue(resumed.isTimePointCommitted("Time1"));
		assertEquals(1, resumed.getCommittedTimepoints());
		resumed.close();

		OpmZarrWriter reopened = new OpmZarrWriter(root);
		assertTrue(reopened.isTimePointCommitted("Time1"));
		assertFalse("interrupted/resumed output is not complete until normal finalization",
				new File(root, OpmZarrWriter.SUCCESS_FILE).exists());
		volume.close();
		projection.close();
	}

	@Test
	public void usesViewerFriendlyChunksForLargeXY() throws Exception {
		File root = new File(folder.getRoot(), "chunks.ome.zarr");
		OpmZarrWriter writer = new OpmZarrWriter(root);
		writer.createVolume(600, 520, 1, 1, 1);
		JsonArray chunks = json(new File(root, "s0/.zarray")).getAsJsonArray("chunks");
		assertEquals(Arrays.asList(1L, 1L, 1L, 512L, 512L), longs(chunks));
		ImagePlus large = volume(600, 520, 1, 0);
		writer.writeVolumeChannel(large, 0, 0);
		large.close();
		N5Reader reader = new N5ZarrReader(root.getAbsolutePath());
		try {
			DatasetAttributes attributes = reader.getDatasetAttributes("s0");
			DataBlock<?> edge = reader.readBlock("s0", attributes, 1, 1, 0, 0, 0);
			org.junit.Assert.assertNotNull("N5 reads an edge chunk padded to the Zarr chunk shape", edge);
			assertArrayEquals(new int[] { 512, 512, 1, 1, 1 }, edge.getSize());
			assertEquals((short) (519 * 600 + 599),
					((short[]) edge.getData())[7 * 512 + 87]);
			assertEquals(0, ((short[]) edge.getData())[7 * 512 + 88]);
		} finally {
			reader.close();
		}
	}

	private static void createAllProjectionArrays(
			OpmZarrWriter writer, int width, int height, int depth, int channels) throws IOException {
		for (String name : OpmZarrSession.PROJECTIONS) {
			char axis = name.charAt(name.length() - 1);
			int projectionWidth = axis == 'X' ? depth : width;
			int projectionHeight = axis == 'Y' ? depth : height;
			writer.createAppendableProjection(name, projectionWidth, projectionHeight, channels);
		}
	}

	private static void writeAllProjections(
			OpmZarrWriter writer, ImagePlus volume, int channel, int time) throws IOException {
		for (String name : OpmZarrSession.PROJECTIONS) {
			char axis = name.charAt(name.length() - 1);
			int width = axis == 'X' ? volume.getStackSize() : volume.getWidth();
			int height = axis == 'Y' ? volume.getStackSize() : volume.getHeight();
			ImagePlus projection = image(width, height, 200 + channel);
			writer.writeProjection(projection, name, channel, time);
			projection.close();
		}
	}

	private static ImagePlus volume(int width, int height, int depth, int offset) {
		ImageStack stack = new ImageStack(width, height);
		for (int z = 0; z < depth; z++) {
			short[] pixels = new short[width * height];
			for (int i = 0; i < pixels.length; i++) pixels[i] = (short) (offset + z * 20 + i);
			stack.addSlice(new ShortProcessor(width, height, pixels, null));
		}
		return new ImagePlus("volume", stack);
	}

	private static ImagePlus image(int width, int height, int offset) {
		short[] pixels = new short[width * height];
		for (int i = 0; i < pixels.length; i++) pixels[i] = (short) (offset + i);
		return new ImagePlus("projection", new ShortProcessor(width, height, pixels, null));
	}

	private static OpmProvenance sampleProvenance(String name) {
		OpmProvenance provenance = new OpmProvenance();
		provenance.datasetName = name;
		provenance.sourceFolder = "raw";
		provenance.xyPixelSizeUm = 0.1;
		provenance.zStepSizeUm = 0.4;
		provenance.opmAngleDegrees = 30;
		provenance.frameIntervalSeconds = 2.5;
		provenance.deskewedVoxelSizeUm = new double[] { 0.1, 0.2, 0.3 };
		provenance.deskewMatrix = Transform.identity();
		provenance.alignMatrix = new double[][] {
			{ 0.9999905126191354, -0.004355992621549795, 11.659000466098544 },
			{ 0.004355992621549795, 0.9999905126191354, -7.493068474056827 }
		};
		provenance.alignMatrixSource = "beads-align.csv";
		provenance.alignApplied = false;
		provenance.alignFlipHalf = BatchChannelOperation.FLIP_RIGHT;
		provenance.channelLabels = Arrays.asList("_Channel0001-left", "_Channel0001-right");
		return provenance.stampEnvironment(false);
	}

	private static void assertProjectionCalibration(JsonObject attrs, String name,
			String vertical, String horizontal, double verticalScale, double horizontalScale) {
		for (JsonElement item : attrs.getAsJsonObject("opm_projections").getAsJsonArray("datasets")) {
			JsonObject projection = item.getAsJsonObject();
			if (!name.equals(projection.get("name").getAsString())) continue;
			JsonArray axes = projection.getAsJsonArray("axes");
			assertEquals(vertical, axes.get(2).getAsJsonObject().get("name").getAsString());
			assertEquals(horizontal, axes.get(3).getAsJsonObject().get("name").getAsString());
			JsonArray scale = projection.getAsJsonArray("coordinateTransformations").get(0)
					.getAsJsonObject().getAsJsonArray("scale");
			assertEquals(verticalScale, scale.get(2).getAsDouble(), 0);
			assertEquals(horizontalScale, scale.get(3).getAsDouble(), 0);
			return;
		}
		fail("projection metadata missing for " + name);
	}

	private static JsonObject json(File file) throws IOException {
		return JsonParser.parseString(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
				.getAsJsonObject();
	}

	private static List<Long> longs(JsonArray array) {
		List<Long> result = new java.util.ArrayList<Long>();
		for (JsonElement value : array) result.add(Long.valueOf(value.getAsLong()));
		return result;
	}

	private static short[] chunkPixels(File file) throws IOException {
		GZIPInputStream input = new GZIPInputStream(new FileInputStream(file));
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		byte[] buffer = new byte[1024];
		try {
			int read;
			while ((read = input.read(buffer)) >= 0) bytes.write(buffer, 0, read);
		} finally {
			input.close();
		}
		ByteBuffer raw = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
		short[] pixels = new short[raw.remaining() / 2];
		for (int i = 0; i < pixels.length; i++) pixels[i] = raw.getShort();
		return pixels;
	}
}
