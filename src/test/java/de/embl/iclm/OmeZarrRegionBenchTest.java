package de.embl.iclm;

import java.io.File;

import org.junit.Assume;
import org.junit.Test;

/**
 * Where the time goes when a region of a real acquisition is read.
 *
 * <p>Not an assertion of speed - a timing threshold on someone else's disk is a test that fails
 * for the wrong reason. It prints the split between chunk count and cost per chunk, which is
 * the only thing that says whether a slow region read is the reader's fault or simply a great
 * many planes. Enable with {@code -Dopm.test.zarr=<dataset>}.
 */
public class OmeZarrRegionBenchTest {

	@Test
	public void reportWhereRegionReadingSpendsItsTime() {
		String configured = System.getProperty("opm.test.zarr", "").trim();
		Assume.assumeTrue("Set -Dopm.test.zarr to run the region benchmark.", !configured.isEmpty());
		File root = new File(configured);
		Assume.assumeTrue(root.isDirectory());

		OmeZarrDataset dataset = OmeZarrDataset.read(root);
		long[] dimensions = dataset.getVolumeDimensions();
		System.out.println("dims [x,y,z,c,t] = " + java.util.Arrays.toString(dimensions));
		System.out.println("channels = " + dataset.getChannelLabels());
		OpmProvenance provenance = dataset.getProvenance();
		System.out.println("alignApplied = " + (provenance == null ? "?" : provenance.alignApplied)
				+ ", alignMatrix = "
				+ (provenance == null ? "?" : java.util.Arrays.deepToString(provenance.alignMatrix)));

		OmeZarrPlaneReader reader = new OmeZarrPlaneReader(dataset, "s0");
		try {
			reader.readPlane(0, 0, 0);
			long start = System.nanoTime();
			for (int z = 1; z <= 20; z++) reader.readPlane(z, 0, 0);
			long whole = System.nanoTime() - start;
			long wholeBlocks = reader.blocksRead();

			OmeZarrPlaneReader.Rect window = new OmeZarrPlaneReader.Rect(700, 600, 64, 64);
			reader.readPlane(window, 0, 0, 0);
			long before = reader.blocksRead();
			start = System.nanoTime();
			for (int z = 1; z <= 20; z++) reader.readPlane(window, z, 0, 0);
			long boxed = System.nanoTime() - start;
			long boxedBlocks = reader.blocksRead() - before;

			report("20 whole planes", whole, wholeBlocks);
			report("20 boxed planes", boxed, boxedBlocks);
		} finally {
			try { reader.close(); } catch (java.io.IOException ignored) { }
		}

		int depth = (int) dimensions[2];

		/* Chunks are one Z plane thick, so once the box is inside a single chunk column the
		 * cost is one chunk per plane per channel and Z is what is left to pay for. */
		OmeZarrView.Options shallow = new OmeZarrView.Options();
		shallow.tryGpu = false;
		shallow.operation = OmeZarrView.Operation.FLIP_ALIGN_RIGHT;
		shallow.bounds = new OmeZarrView.Bounds(700, 600, 64, 64, depth / 2 - 20, depth / 2 + 20);
		long shallowStart = System.nanoTime();
		long shallowBlocks = OmeZarrView.blocksReadForRegion(dataset, shallow, 0);
		report("materialise 64x64x40, 1 T, flip + align right onto left",
				System.nanoTime() - shallowStart, shallowBlocks);

		OmeZarrView.Bounds box = new OmeZarrView.Bounds(700, 600, 64, 64, 0, depth);
		for (OmeZarrView.Operation operation : OmeZarrView.Operation.values()) {
			OmeZarrView.Options options = new OmeZarrView.Options();
			options.tryGpu = false;
			options.operation = operation;
			options.bounds = box.copy();
			long start = System.nanoTime();
			long blocks = OmeZarrView.blocksReadForRegion(dataset, options, 0);
			report("materialise 64x64x" + depth + ", 1 T, " + operation,
					System.nanoTime() - start, blocks);
		}
	}

	private static void report(String what, long nanos, long blocks) {
		System.out.println(String.format("%-64s %8.1f ms  %7d chunks  %6.2f ms/chunk",
				what, nanos / 1e6, blocks, blocks == 0 ? 0 : nanos / 1e6 / blocks));
	}
}
