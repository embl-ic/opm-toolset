package de.embl.iclm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Structural completion checks used before Live Deskew opens a newly written TIFF. */
public class TiffCompletionCheckTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void acceptsTheDeflateTiffWrittenByTheLiveOutputPath() throws IOException {
		File file = new File(folder.getRoot(), "deflate-stack.tif");
		ImageStack stack = new ImageStack(5, 4);
		stack.addSlice(new ShortProcessor(5, 4));
		stack.addSlice(new ShortProcessor(5, 4));
		FastTiffWriter.write(new ImagePlus("stack", stack), file);

		assertTrue(TiffCompletionCheck.isReady(file));
	}

	@Test
	public void rejectsPixelDataThatHasNotReachedEofYet() throws IOException {
		File file = write("truncated-pixels.tif", classicTiff());
		RandomAccessFile cut = new RandomAccessFile(file, "rw");
		try {
			cut.setLength(file.length() - 1);
		} finally {
			cut.close();
		}

		assertFalse(TiffCompletionCheck.isReady(file));
	}

	@Test
	public void rejectsATruncatedOrMissingIfd() throws IOException {
		byte[] bytes = classicTiff();
		File truncated = write("truncated-ifd.tif", bytes, 30);
		assertFalse(TiffCompletionCheck.isReady(truncated));

		ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 5000);
		File missing = write("missing-ifd.tif", bytes);
		assertFalse(TiffCompletionCheck.isReady(missing));
	}

	@Test
	public void rejectsAnInvalidNextIfdOffset() throws IOException {
		byte[] bytes = classicTiff();
		// 8-byte header + 2-byte count + five 12-byte entries
		ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(70, 9000);
		assertFalse(TiffCompletionCheck.isReady(write("bad-next-ifd.tif", bytes)));
	}

	@Test
	public void acceptsAStructurallyCompleteBigTiff() throws IOException {
		assertTrue(TiffCompletionCheck.isReady(write("complete.btf.tiff", bigTiff())));
	}

	/**
	 * The structure is read a block at a time (M2-Q-F1). Whatever the block size - down to single
	 * bytes, so that every value straddles a block - a camera-style BigTIFF with interleaved IFDs and
	 * multi-strip planes is accepted whole, and refused when cut short anywhere.
	 */
	@Test
	public void blocksOfAnySizeAcceptTheWholeFileAndRefuseItCutAnywhere() throws IOException {
		int[] blocks = { 1, 3, 7, 64, 4096, 0 };
		int[][] shapes = { { 64, 40, 20, 8 }, { 33, 17, 9, 5 }, { 128, 24, 6, 24 } };		// width, height, planes, rows per strip
		try {
			for (int[] s : shapes) {
				File whole = RawTiffFixture.write(new File(folder.getRoot(), String.format("cam_%d_%d_%d_%d.tiff", s[0], s[1], s[2], s[3])),
						s[0], s[1], s[2], s[3], s[2], (x, y, k) -> 100 + x + y + k);
				byte[] bytes = java.nio.file.Files.readAllBytes(whole.toPath());
				int step = Math.max(1, bytes.length / 120);
				for (int block : blocks) {
					TiffCompletionCheck.blockBytesForTest = block;
					assertTrue(whole.getName() + ", blocks of " + block, TiffCompletionCheck.isReady(whole));
					for (int cut = 1; cut < bytes.length; cut += step) {
						File shorter = write("cut.tiff", bytes, bytes.length - cut);
						assertFalse(whole.getName() + " cut by " + cut + " bytes, blocks of " + block, TiffCompletionCheck.isReady(shorter));
					}
				}
			}
		} finally {
			TiffCompletionCheck.blockBytesForTest = 0;
		}
	}

	/**
	 * A check reads its file a block at a time: a 20-plane camera-style file of about 100 kB, with
	 * 20 IFDs and 100 strips, takes a handful of reads, not one per byte of its structure (M2-Q-F1).
	 * Counted, not timed.
	 */
	@Test
	public void aCheckReadsBlocksNotBytes() throws IOException {
		File file = RawTiffFixture.write(new File(folder.getRoot(), "count.tiff"), 64, 40, 20, 8, 20, (x, y, k) -> x + y + k);
		long before = TiffCompletionCheck.readsForTest.get();
		assertTrue(TiffCompletionCheck.isReady(file));
		long reads = TiffCompletionCheck.readsForTest.get() - before;
		long blocks = (file.length() + (1 << 16) - 1) >> 16;
		assertTrue(reads + " reads for " + file.length() + " bytes", reads >= 1 && reads <= 2 * blocks + 2);
	}

	/** Optional real-data probe: -Dopm.test.liveTiff=path, without decoding its pixels. */
	@Test
	public void checksAProvidedAcquisitionTiffWithoutReadingTheVolume() {
		String path = System.getProperty("opm.test.liveTiff", "").trim();
		assumeTrue("no real acquisition TIFF supplied", !path.isEmpty());
		File file = new File(path);
		assumeTrue("provided acquisition TIFF is missing", file.isFile());

		long started = System.nanoTime();
		assertTrue(TiffCompletionCheck.isReady(file));
		long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
		assertTrue("metadata-only check took " + elapsedMs + " ms", elapsedMs < 5000);
	}

	private File write(String name, byte[] bytes) throws IOException {
		return write(name, bytes, bytes.length);
	}

	private File write(String name, byte[] bytes, int length) throws IOException {
		File file = new File(folder.getRoot(), name);
		FileOutputStream output = new FileOutputStream(file);
		try {
			output.write(bytes, 0, length);
		} finally {
			output.close();
		}
		return file;
	}

	/** One 1x1 classic TIFF with five entries and one byte of uncompressed pixel data. */
	private static byte[] classicTiff() {
		final int pixel = 74;
		ByteBuffer out = ByteBuffer.allocate(pixel + 1).order(ByteOrder.LITTLE_ENDIAN);
		out.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
		out.putShort((short) 5);
		entry(out, 256, 4, 1, 1);       // ImageWidth
		entry(out, 257, 4, 1, 1);       // ImageLength
		entry(out, 273, 4, 1, pixel);   // StripOffsets
		entry(out, 278, 4, 1, 1);       // RowsPerStrip
		entry(out, 279, 4, 1, 1);       // StripByteCounts
		out.putInt(0);                   // next IFD
		out.put((byte) 7);
		return out.array();
	}

	private static void entry(ByteBuffer out, int tag, int type, int count, int value) {
		out.putShort((short) tag).putShort((short) type).putInt(count).putInt(value);
	}

	/** The equivalent minimal BigTIFF, using LONG8 for its strip location and count. */
	private static byte[] bigTiff() {
		final int pixel = 132;
		ByteBuffer out = ByteBuffer.allocate(pixel + 1).order(ByteOrder.LITTLE_ENDIAN);
		out.put((byte) 'I').put((byte) 'I').putShort((short) 43);
		out.putShort((short) 8).putShort((short) 0).putLong(16);
		out.putLong(5);
		bigEntry(out, 256, 4, 1, 1);
		bigEntry(out, 257, 4, 1, 1);
		bigEntry(out, 273, 16, 1, pixel);
		bigEntry(out, 278, 4, 1, 1);
		bigEntry(out, 279, 16, 1, 1);
		out.putLong(0);
		out.put((byte) 11);
		return out.array();
	}

	private static void bigEntry(ByteBuffer out, int tag, int type, long count, long value) {
		out.putShort((short) tag).putShort((short) type).putLong(count).putLong(value);
	}
}
