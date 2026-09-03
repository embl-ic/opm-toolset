package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
public class OpmZarrDatasetTest {

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

		List<OpmZarrDataset> datasets = OpmZarrDataset.discover(tree);
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

		OpmZarrDataset dataset = OpmZarrDataset.read(root);
		assertEquals(1, dataset.getTimepointCount());
		assertEquals(Arrays.asList("_Channel0001-left", "_Channel0001-right"), dataset.getChannelLabels());
		assertNull(dataset.getProvenance().alignMatrix);
		assertEquals(0.1, dataset.voxelSizeUm()[0], 0);
		assertTrue(dataset.isComplete());
	}

	private static void writeSmallDataset(File root, OpmProvenance provenance) throws Exception {
		OpmZarrWriter writer = new OpmZarrWriter(root);
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
		OpmZarrWriter writer = new OpmZarrWriter(root);
		writer.createAppendableVolume(4, 2, 2, 2);
		writer.createAppendableProjection("maxZ", 4, 2, 2);
		writer.writeMetadata(sampleProvenance("override", true), Collections.singletonList("maxZ"));

		String before = new String(Files.readAllBytes(new File(root, ".zattrs").toPath()),
				StandardCharsets.UTF_8);
		OpmZarrDataset original = OpmZarrDataset.read(root);
		assertEquals(0.0, original.getProvenance().alignMatrix[0][2], 0);

		OpmZarrDataset.writeAlignMatrix(root,
				new double[][] { { 0.99, -0.04, 11.5 }, { 0.04, 0.99, -7.5 } }, "recomputed.csv");

		OpmZarrDataset updated = OpmZarrDataset.read(root);
		assertEquals(11.5, updated.getProvenance().alignMatrix[0][2], 1e-9);
		assertEquals(-7.5, updated.getProvenance().alignMatrix[1][2], 1e-9);
		assertEquals("recomputed.csv", updated.getProvenance().alignMatrixSource);
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
		OpmZarrDataset.writeAlignMatrix(root, new double[][] { { 1, 0, 3 }, { 0, 1, 4 } }, "second.csv");
		assertEquals(before, new String(Files.readAllBytes(pristine.toPath()), StandardCharsets.UTF_8));
		assertEquals(3.0, OpmZarrDataset.read(root).getProvenance().alignMatrix[0][2], 1e-9);
	}

	@Test
	public void rejectsAMatrixThatIsNotTwoRowsOfThree() throws Exception {
		assertFalse(OpmZarrDataset.isAlignmentMatrix(null));
		assertFalse(OpmZarrDataset.isAlignmentMatrix(new double[][] { { 1, 0, 0 } }));
		assertFalse(OpmZarrDataset.isAlignmentMatrix(new double[][] { { 1, 0 }, { 0, 1 } }));
		assertTrue(OpmZarrDataset.isAlignmentMatrix(new double[][] { { 1, 0, 0 }, { 0, 1, 0 } }));

		File root = folder.newFolder("bad.ome.zarr");
		OpmZarrWriter writer = new OpmZarrWriter(root);
		writer.createAppendableVolume(4, 2, 2, 2);
		writer.writeMetadata(sampleProvenance("bad", true), Collections.<String>emptyList());
		try {
			OpmZarrDataset.writeAlignMatrix(root, new double[][] { { 1, 0 }, { 0, 1 } }, "x.csv");
			fail("a 2 x 2 matrix should be refused");
		} catch (IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("2 x 3"));
		}
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
