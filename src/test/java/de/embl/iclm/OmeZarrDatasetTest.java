package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Pixel-free discovery and tolerant metadata tests. */
public class OmeZarrDatasetTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void discoversNestedDatasetsNaturallyAndToleratesHalfWrittenOne() throws Exception {
		File tree = folder.newFolder("tree");
		File ten = new File(tree, "10_acquisition.ome.zarr");
		File two = new File(new File(tree, "nested"), "2_acquisition.ome.zarr");
		writeSmallDataset(ten, sampleProvenance("10_acquisition", true));
		writeSmallDataset(two, sampleProvenance("2_acquisition", false));

		File partial = new File(tree, "20_live.ome.zarr");
		assertTrue(partial.mkdirs());
		Files.write(new File(partial, ".zgroup").toPath(),
				"{\"zarr_format\":2}\n".getBytes(StandardCharsets.UTF_8));
		Files.write(new File(partial, ".zattrs").toPath(),
				"{ interrupted".getBytes(StandardCharsets.UTF_8));

		List<OmeZarrDataset> datasets = OmeZarrDataset.discover(tree);
		assertEquals(3, datasets.size());
		assertEquals("2_acquisition", datasets.get(0).getDisplayName());
		assertEquals("10_acquisition", datasets.get(1).getDisplayName());
		assertEquals("20_live.ome.zarr", datasets.get(2).getDisplayName());
		assertFalse(datasets.get(2).getWarnings().isEmpty());
		assertFalse(datasets.get(2).hasVolume());
	}

	@Test
	public void readsProvenanceWithoutAlignmentMatrixAndDoesNotOpenPixels() throws Exception {
		File root = new File(folder.getRoot(), "no-align.ome.zarr");
		OpmProvenance provenance = sampleProvenance("no-align", false);
		writeSmallDataset(root, provenance);

		OmeZarrDataset dataset = OmeZarrDataset.read(root);
		assertEquals(1, dataset.getTimepointCount());
		assertEquals(Arrays.asList("_Channel0001-left", "_Channel0001-right"), dataset.getChannelLabels());
		assertNull(dataset.getProvenance().alignMatrix);
		assertEquals(0.1, dataset.voxelSizeUm()[0], 0);
		assertTrue(dataset.isComplete());
	}

	private static void writeSmallDataset(File root, OpmProvenance provenance) throws Exception {
		OmeZarrWriter writer = new OmeZarrWriter(root);
		writer.createAppendableVolume(2, 2, 1, 2);
		writer.writeMetadata(provenance, Collections.<String>emptyList());
		writer.writeVolumeChannel(volume(1), 0, 0);
		writer.writeVolumeChannel(volume(10), 1, 0);
		writer.commitTimePoint(0, "Time000001", Collections.singletonList("raw.tif"), 0);
		writer.markComplete();
	}

	private static ImagePlus volume(int offset) {
		short[] pixels = { (short) offset, (short) (offset + 1), (short) (offset + 2), (short) (offset + 3) };
		ImageStack stack = new ImageStack(2, 2);
		stack.addSlice(new ShortProcessor(2, 2, pixels, null));
		return new ImagePlus("volume", stack);
	}

	@Test
	public void replacesTheAlignmentMatrixWithoutTouchingAnythingElse() throws Exception {
		File root = folder.newFolder("override.ome.zarr");
		OmeZarrWriter writer = new OmeZarrWriter(root);
		writer.createAppendableVolume(4, 2, 2, 2);
		writer.createAppendableProjection("maxZ", 4, 2, 2);
		writer.writeMetadata(sampleProvenance("override", true), Collections.singletonList("maxZ"));

		String before = new String(Files.readAllBytes(new File(root, ".zattrs").toPath()),
				StandardCharsets.UTF_8);
		OmeZarrDataset original = OmeZarrDataset.read(root);
		assertEquals(0.0, original.getProvenance().alignMatrix[0][2], 0);

		OmeZarrDataset.writeAlignMatrix(root,
				new double[][] { { 0.99, -0.04, 11.5 }, { 0.04, 0.99, -7.5 } }, "recomputed.csv");

		OmeZarrDataset updated = OmeZarrDataset.read(root);
		assertEquals(11.5, updated.getProvenance().alignMatrix[0][2], 1e-9);
		assertEquals(-7.5, updated.getProvenance().alignMatrix[1][2], 1e-9);
		assertEquals("recomputed.csv", updated.getProvenance().alignMatrixSource);
		assertNotNull(updated.getProvenance().alignMatrixModifiedUtc);
		assertTrue(updated.getProvenance().alignMatrixModifiedUtc.endsWith("Z"));
		String summary = OpmDataViewer.summary(updated);
		assertTrue(summary, summary.contains("alignment matrix (2x3) = [[0.99, -0.04, 11.5]"));
		assertTrue(summary, summary.contains("alignment matrix modified UTC = "));
		assertTrue(summary, summary.contains("alignment applied to stored pixels = false"));
		/* A metadata edit only: the arrays, their scales and the channel list stay as written. */
		assertEquals(original.getChannelLabels(), updated.getChannelLabels());
		assertArrayEquals(original.getVolumeDimensions(), updated.getVolumeDimensions());
		assertArrayEquals(original.voxelSizeUm(), updated.voxelSizeUm(), 0);
		assertTrue(updated.hasProjection("maxZ"));
		assertEquals(original.getTimepointCount(), updated.getTimepointCount());

		/* The acquisition's own alignment stays recoverable however many matrices are tried. */
		File pristine = new File(root, ".zattrs.original");
		assertTrue(pristine.isFile());
		assertEquals(before, new String(Files.readAllBytes(pristine.toPath()), StandardCharsets.UTF_8));
		OmeZarrDataset.writeAlignMatrix(root, new double[][] { { 1, 0, 3 }, { 0, 1, 4 } }, "second.csv");
		assertEquals(before, new String(Files.readAllBytes(pristine.toPath()), StandardCharsets.UTF_8));
		assertEquals(3.0, OmeZarrDataset.read(root).getProvenance().alignMatrix[0][2], 1e-9);
	}

	@Test
	public void rejectsAMatrixThatIsNotTwoRowsOfThree() throws Exception {
		assertFalse(OmeZarrDataset.isAlignmentMatrix(null));
		assertFalse(OmeZarrDataset.isAlignmentMatrix(new double[][] { { 1, 0, 0 } }));
		assertFalse(OmeZarrDataset.isAlignmentMatrix(new double[][] { { 1, 0 }, { 0, 1 } }));
		assertTrue(OmeZarrDataset.isAlignmentMatrix(new double[][] { { 1, 0, 0 }, { 0, 1, 0 } }));

		File root = folder.newFolder("bad.ome.zarr");
		OmeZarrWriter writer = new OmeZarrWriter(root);
		writer.createAppendableVolume(4, 2, 2, 2);
		writer.writeMetadata(sampleProvenance("bad", true), Collections.<String>emptyList());
		try {
			OmeZarrDataset.writeAlignMatrix(root, new double[][] { { 1, 0 }, { 0, 1 } }, "x.csv");
			fail("a 2 x 2 matrix should be refused");
		} catch (IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("2 x 3"));
		}
	}

	@Test
	public void correctsLegacyVolumeAndProjectionCalibrationWhenViewing() throws Exception {
		File root = folder.newFolder("legacy-view.ome.zarr");
		OpmProvenance provenance = new OpmProvenance();
		provenance.datasetName = "legacy-view";
		provenance.xyPixelSizeUm = 0.116;
		provenance.zStepSizeUm = 0.265;
		provenance.opmAngleDegrees = 25;
		provenance.frameIntervalSeconds = 30;
		provenance.deskewMatrix = Transform.deskew(0.265, 0.116, 25, 500);
		provenance.computeDeskewedVoxelSize();
		OmeZarrWriter writer = new OmeZarrWriter(root);
		writer.createAppendableVolume(2, 2, 2, 1);
		writer.createAppendableProjection("maxX", 2, 2, 1);
		writer.writeMetadata(provenance, Collections.singletonList("maxX"));

		// Reproduce the exact metadata written before the output-grid correction.
		com.google.gson.JsonObject attrs = com.google.gson.JsonParser.parseString(new String(
				Files.readAllBytes(new File(root, ".zattrs").toPath()), StandardCharsets.UTF_8))
				.getAsJsonObject();
		double legacyY = 0.116 * Math.cos(Math.toRadians(25));
		double legacyZ = 0.265 * Math.sin(Math.toRadians(25));
		com.google.gson.JsonArray legacyVoxel = new com.google.gson.JsonArray();
		legacyVoxel.add(0.116); legacyVoxel.add(legacyY); legacyVoxel.add(legacyZ);
		attrs.getAsJsonObject(OpmProvenance.KEY).add("deskewedVoxelSizeUm", legacyVoxel);
		com.google.gson.JsonArray projectionScale = attrs.getAsJsonObject("opm_projections")
				.getAsJsonArray("datasets").get(0).getAsJsonObject()
				.getAsJsonArray("coordinateTransformations").get(0).getAsJsonObject()
				.getAsJsonArray("scale");
		projectionScale.set(2, new com.google.gson.JsonPrimitive(legacyY));
		projectionScale.set(3, new com.google.gson.JsonPrimitive(legacyZ));
		Files.write(new File(root, ".zattrs").toPath(),
				(new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(attrs) + "\n")
						.getBytes(StandardCharsets.UTF_8));

		OmeZarrDataset dataset = OmeZarrDataset.read(root);
		assertArrayEquals(new double[] { 0.116, 0.116, 0.116 }, dataset.voxelSizeUm(), 0);
		assertArrayEquals(new double[] { 0.116, 0.116 }, dataset.getProjectionPixelSizeUm("maxX"), 0);
		assertFalse(dataset.getWarnings().isEmpty());
		String summary = OpmDataViewer.summary(dataset);
		assertTrue(summary, summary.contains(
				"voxel µm = [0.116, 0.116, 0.116], frame interval = 30.0 s"));
	}

	@Test
	public void resolvesAnythingInsideADatasetToTheDatasetItself() throws Exception {
		File root = folder.newFolder("drop.ome.zarr");
		OmeZarrWriter writer = new OmeZarrWriter(root);
		writer.createAppendableVolume(4, 2, 2, 2);
		writer.writeMetadata(sampleProvenance("drop", true), Collections.<String>emptyList());

		/* Dropping a chunk, an array folder or .zattrs all mean the dataset. Resolving up
		 * also keeps discover() out of an s0 folder, where walking hundreds of thousands of
		 * chunk files costs tens of seconds and finds nothing. */
		assertEquals(root, OmeZarrDataset.resolveDatasetFolder(root));
		assertEquals(root, OmeZarrDataset.resolveDatasetFolder(new File(root, ".zattrs")));
		assertEquals(root, OmeZarrDataset.resolveDatasetFolder(new File(root, "s0")));
		assertEquals(root, OmeZarrDataset.resolveDatasetFolder(new File(new File(root, "s0"), "0.0.0.0.0")));

		/* A parent folder is left alone, so it still scans for every dataset beneath it. */
		File parent = root.getParentFile();
		assertEquals(parent, OmeZarrDataset.resolveDatasetFolder(parent));
		assertNull(OmeZarrDataset.resolveDatasetFolder(null));
	}

		static OpmProvenance sampleProvenance(String name, boolean alignment) {
		OpmProvenance provenance = new OpmProvenance();
		provenance.datasetName = name;
		provenance.channelLabels = Arrays.asList("_Channel0001-left", "_Channel0001-right");
		provenance.deskewedVoxelSizeUm = new double[] { 0.1, 0.2, 0.3 };
		provenance.frameIntervalSeconds = 2.5;
		provenance.alignFlipHalf = BatchChannelOperation.FLIP_RIGHT;
		provenance.alignInterpolate = true;
		if (alignment) provenance.alignMatrix = new double[][] { { 1, 0, 0 }, { 0, 1, 0 } };
		return provenance;
	}
}
