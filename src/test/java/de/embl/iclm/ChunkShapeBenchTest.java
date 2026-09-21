package de.embl.iclm;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.zarr.N5ZarrReader;
import org.junit.Assume;
import org.junit.Test;

/**
 * Would a different chunk shape make the viewer faster?
 *
 * <p>Re-chunks one time point of one channel of a real acquisition several ways and measures the
 * two workloads that matter: scrolling whole planes (the live preview and any uncropped view) and
 * materialising a small box over a stretch of Z (a hand-drawn ROI in a virtual stack).
 *
 * <p>Enable with {@code -Dopm.test.zarr=<store>} and {@code -Dopm.chunkbench.out=<scratch folder>};
 * it writes about 1.4 GB and deletes each variant as it finishes with it. The absolute times are
 * optimistic because each variant is read straight after being written and is therefore in the OS
 * page cache, but every variant is measured the same way and gzip inflation - which dominates - is
 * not cached, so the comparison holds.
 */
public class ChunkShapeBenchTest {

	/** The hand-drawn ROI in the question: 200 x 200 across half the Z range. */
	private static final int ROI = 200;
	/** What an aligned view actually asks for: the box plus ALIGNED_MARGIN and the rotation spread. */
	private static final int ALIGNED_ROI = 206;
	private static final int SCROLL_PLANES = 40;

	@Test
	public void compareChunkShapes() throws Exception {
		String source = System.getProperty("opm.test.zarr", "").trim();
		String scratch = System.getProperty("opm.chunkbench.out", "").trim();
		Assume.assumeTrue(!source.isEmpty() && !scratch.isEmpty());
		File root = new File(source);
		Assume.assumeTrue(root.isDirectory());

		OmeZarrDataset dataset = OmeZarrDataset.read(root);
		long[] dims = dataset.getVolumeDimensions();     // x, y, z, c, t
		final int width = (int) dims[0], height = (int) dims[1], depth = (int) dims[2];
		System.out.printf("source %dx%dx%d, %d channels, %d time points%n",
				width, height, depth, dims[3], dims[4]);

		int[][] shapes = {
			{ 512, 512, 1 },      // what the writer emits today
			{ 256, 256, 1 },
			{ 128, 128, 1 },
			{ width, height, 1 }, // one chunk per plane
			{ 512, 512, 4 },
			{ 256, 256, 8 },
			{ 128, 128, 16 },
		};

		System.out.println();
		System.out.printf("%-18s %8s %9s | %10s %8s | %10s %8s %8s | %9s %10s%n",
				"chunk x,y,z", "MB", "write_s",
				"scroll_ms", "chunks", "roi_ms", "chunks", "uniq", "roi_MB_inf", "roi_par_ms");
		for (int[] shape : shapes) {
			File variant = new File(scratch, "chunk-" + shape[0] + "x" + shape[1] + "x" + shape[2]);
			delete(variant);
			long writeStart = System.nanoTime();
			write(root, variant, width, height, depth, shape);
			double writeSeconds = (System.nanoTime() - writeStart) / 1e9;
			double megabytes = sizeOnDisk(variant) / 1024.0 / 1024.0;

			N5Reader reader = new N5ZarrReader(variant.getAbsolutePath());
			try {
				DatasetAttributes attributes = reader.getDatasetAttributes("s0");
				int[] block = attributes.getBlockSize();

				// Scrolling: whole planes, one at a time, as a virtual stack does.
				Counter scroll = new Counter();
				int firstZ = depth / 2 - SCROLL_PLANES / 2;
				long start = System.nanoTime();
				for (int z = firstZ; z < firstZ + SCROLL_PLANES; z++)
					read(reader, attributes, block, 0, 0, width, height, z, scroll);
				double scrollMs = (System.nanoTime() - start) / 1e6;

				// The ROI: one box, every plane of half the Z range.
				Counter roi = new Counter();
				int x0 = (width - ALIGNED_ROI) / 2, y0 = (height - ALIGNED_ROI) / 2;
				int fromZ = depth / 4, toZ = fromZ + depth / 2;
				double roiMs = Double.MAX_VALUE;
				for (int pass = 0; pass < 3; pass++) {
					Counter each = new Counter();
					start = System.nanoTime();
					for (int z = fromZ; z < toZ; z++)
						read(reader, attributes, block, x0, y0, ALIGNED_ROI, ALIGNED_ROI, z, each);
					roiMs = Math.min(roiMs, (System.nanoTime() - start) / 1e6);
					roi = each;
				}

				double inflatedMb = roi.blocks * (double) block[0] * block[1] * block[2] * 2 / 1048576.0;
				// The same ROI, with the planes spread over a pool: no format change at all.
				double parallelMs = parallelRoi(variant, block, x0, y0, fromZ, toZ);
				System.out.printf("%-18s %8.0f %9.1f | %10.0f %8d | %10.0f %8d %8d | %9.0f %10.0f%n",
						shape[0] + "," + shape[1] + "," + shape[2], megabytes, writeSeconds,
						scrollMs, scroll.blocks, roiMs, roi.blocks, roi.unique.size(), inflatedMb,
						parallelMs);
			} finally {
				reader.close();
				delete(variant);
			}
		}
		System.out.println();
		System.out.println("scroll = " + SCROLL_PLANES + " whole planes; roi = " + ALIGNED_ROI + "^2 box over "
				+ (depth / 2) + " planes, one channel. uniq = distinct chunks, i.e. what a");
		System.out.println("decompressed-block cache could reduce the inflations to.");
	}

	private static final class Counter {
		long blocks;
		final Set<String> unique = new HashSet<String>();
	}

	/**
	 * The same ROI read with one reader per worker and the Z planes shared out between them.
	 * <p>
	 * Every chunk is an independent gzip stream, so this is embarrassingly parallel and needs no
	 * change to the stored format. One {@link N5ZarrReader} per thread because the real reader
	 * serialises on itself.
	 */
	private static double parallelRoi(File variant, final int[] block, final int x0, final int y0,
			int fromZ, int toZ) throws Exception {
		final int threads = Math.min(8, Runtime.getRuntime().availableProcessors());
		java.util.concurrent.ExecutorService pool =
				java.util.concurrent.Executors.newFixedThreadPool(threads);
		// Built before the clock starts: opening a reader parses .zarray and is not read cost.
		final N5Reader[] readers = new N5Reader[threads];
		final DatasetAttributes[] attributes = new DatasetAttributes[threads];
		for (int i = 0; i < threads; i++) {
			readers[i] = new N5ZarrReader(variant.getAbsolutePath());
			attributes[i] = readers[i].getDatasetAttributes("s0");
		}
		try {
			java.util.List<java.util.concurrent.Callable<Void>> work =
					new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
			for (int worker = 0; worker < threads; worker++) {
				final int first = fromZ + worker, step = threads, last = toZ, own = worker;
				work.add(new java.util.concurrent.Callable<Void>() {
					@Override public Void call() {
						Counter ignored = new Counter();
						for (int z = first; z < last; z += step)
							read(readers[own], attributes[own], block,
									x0, y0, ALIGNED_ROI, ALIGNED_ROI, z, ignored);
						return null;
					}
				});
			}
			double best = Double.MAX_VALUE;
			for (int pass = 0; pass < 3; pass++) {
				long start = System.nanoTime();
				for (java.util.concurrent.Future<Void> future : pool.invokeAll(work)) future.get();
				best = Math.min(best, (System.nanoTime() - start) / 1e6);
			}
			return best;
		} finally {
			for (N5Reader reader : readers) reader.close();
			pool.shutdown();
		}
	}

	/** The chunk loop of OmeZarrPlaneReader, instrumented. */
	private static void read(N5Reader reader, DatasetAttributes attributes, int[] block,
			int x, int y, int w, int h, int z, Counter counter) {
		long firstX = x / block[0], lastX = (x + w - 1) / block[0];
		long firstY = y / block[1], lastY = (y + h - 1) / block[1];
		long gz = z / block[2];
		for (long gy = firstY; gy <= lastY; gy++) for (long gx = firstX; gx <= lastX; gx++) {
			DataBlock<?> data = reader.readBlock("s0", attributes, new long[] { gx, gy, gz, 0, 0 });
			counter.blocks++;
			counter.unique.add(gx + "," + gy + "," + gz);
			if (data == null) continue;
			short[] raw = (short[]) data.getData();
			if (raw.length == 0) throw new IllegalStateException("empty block");
		}
	}

	/**
	 * Copy time point 0, channel 0 of the source into a fresh array with this chunk shape.
	 * <p>
	 * Written as Zarr v2 directly, the way {@link OmeZarrWriter} does - one gzip stream per
	 * chunk file named {@code t.c.z.y.x} - rather than through N5's writer, which in this
	 * version reaches for a class N5 4 no longer has. Chunks are full size and zero padded at
	 * the edges, again as the real writer does, so the on-disk figures are comparable.
	 */
	private static void write(File source, File target, int width, int height, int depth, int[] shape)
			throws Exception {
		N5Reader in = new N5ZarrReader(source.getAbsolutePath());
		try {
			DatasetAttributes inAttributes = in.getDatasetAttributes("s0");
			int[] inBlock = inAttributes.getBlockSize();
			File array = new File(target, "s0");
			array.mkdirs();
			writeText(new File(target, ".zgroup"), "{\"zarr_format\": 2}\n");
			writeText(new File(array, ".zarray"), String.format(
					"{\"zarr_format\":2,\"shape\":[1,1,%d,%d,%d],\"chunks\":[1,1,%d,%d,%d],"
					+ "\"dtype\":\"<u2\",\"compressor\":{\"id\":\"gzip\",\"level\":1},"
					+ "\"fill_value\":0,\"order\":\"C\",\"filters\":null,"
					+ "\"dimension_separator\":\".\"}%n",
					depth, height, width, shape[2], shape[1], shape[0]));

			short[] slab = new short[width * height * shape[2]];
			for (int z0 = 0; z0 < depth; z0 += shape[2]) {
				int planes = Math.min(shape[2], depth - z0);
				Arrays.fill(slab, (short) 0);
				for (int i = 0; i < planes; i++)
					readWholePlane(in, inAttributes, inBlock, width, height, z0 + i,
							slab, i * width * height);
				for (int gy = 0; gy * shape[1] < height; gy++) {
					for (int gx = 0; gx * shape[0] < width; gx++) {
						ByteBuffer buffer = ByteBuffer
								.allocate(shape[0] * shape[1] * shape[2] * 2)
								.order(ByteOrder.LITTLE_ENDIAN);
						for (int pz = 0; pz < shape[2]; pz++)
							for (int by = 0; by < shape[1]; by++)
								for (int bx = 0; bx < shape[0]; bx++) {
									int x = gx * shape[0] + bx, y = gy * shape[1] + by;
									buffer.putShort(pz < planes && x < width && y < height
											? slab[pz * width * height + y * width + x] : 0);
								}
						writeChunk(new File(array, "0.0." + (z0 / shape[2]) + "." + gy + "." + gx),
								buffer.array());
					}
				}
			}
		} finally {
			in.close();
		}
	}

	private static void writeChunk(File target, byte[] raw) throws java.io.IOException {
		GZIPOutputStream gzip = new GZIPOutputStream(new java.io.BufferedOutputStream(
				new java.io.FileOutputStream(target), 1 << 16), 1 << 16) {
			{ def.setLevel(1); }
		};
		try { gzip.write(raw); gzip.finish(); } finally { gzip.close(); }
	}

	private static void writeText(File target, String text) throws java.io.IOException {
		java.io.Writer writer = new java.io.OutputStreamWriter(
				new java.io.FileOutputStream(target), "UTF-8");
		try { writer.write(text); } finally { writer.close(); }
	}

	/** One whole plane of the source, into {@code into} at {@code offset}. */
	private static void readWholePlane(N5Reader reader, DatasetAttributes attributes, int[] block,
			int width, int height, int z, short[] into, int offset) {
		for (long gy = 0; gy * block[1] < height; gy++) {
			for (long gx = 0; gx * block[0] < width; gx++) {
				DataBlock<?> data = reader.readBlock("s0", attributes,
						new long[] { gx, gy, z / block[2], 0, 0 });
				if (data == null) continue;
				short[] raw = (short[]) data.getData();
				int[] size = data.getSize();
				int originX = (int) (gx * block[0]), originY = (int) (gy * block[1]);
				int localZ = z % block[2];
				for (int by = 0; by < size[1] && originY + by < height; by++) {
					int from = (localZ * size[1] + by) * size[0];
					int run = Math.min(size[0], width - originX);
					System.arraycopy(raw, from, into, offset + (originY + by) * width + originX, run);
				}
			}
		}
	}

	private static long sizeOnDisk(File folder) {
		long total = 0;
		File[] children = folder.listFiles();
		if (children == null) return folder.length();
		for (File child : children) total += child.isDirectory() ? sizeOnDisk(child) : child.length();
		return total;
	}

	private static void delete(File folder) {
		File[] children = folder.listFiles();
		if (children != null) for (File child : children) delete(child);
		folder.delete();
	}
}
