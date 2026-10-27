package de.embl.iclm;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.Set;

/**
 * A small, metadata-only test for whether a TIFF is structurally complete on disk.
 *
 * <p>The check deliberately does not decode pixels. It follows the classic-TIFF or BigTIFF
 * IFD chain, verifies every entry and out-of-line value against EOF, and verifies the ranges
 * named by StripOffsets/StripByteCounts or TileOffsets/TileByteCounts. That catches the
 * common live-acquisition failures (a truncated IFD, an offset not written yet, and pixel data
 * extending beyond EOF) without allocating an image or competing with the acquisition for
 * CPU and RAM. A preceding quiet-period check remains necessary: a perfectly valid one-page
 * TIFF may still be a writer's temporary state before it appends another page.
 *
 * <p>The structure is read a block at a time. A camera BigTIFF has hundreds of IFDs, each with
 * strip tables of hundreds of values; read a byte per call, checking one such file took about
 * 0.3 s, almost all of it spent in calls, and the Online Deskew View checks its files one after
 * another (M2-Q-F1). What is checked, and against which length, is unchanged.
 */
final class TiffCompletionCheck {

	/** Bytes per read of the structure; 0 for the default. Tests shrink it so that values straddle blocks. */
	static volatile int blockBytesForTest;
	/** Reads of the file made by checks so far, for tests: a check reads blocks, not bytes. */
	static final java.util.concurrent.atomic.AtomicLong readsForTest = new java.util.concurrent.atomic.AtomicLong();
	private static final int BLOCK_BYTES = 1 << 16;

	private static final int CLASSIC_MAGIC = 42;
	private static final int BIG_TIFF_MAGIC = 43;
	private static final int MAX_IFDS = 1000000;
	private static final long MAX_IFD_ENTRIES = 1000000L;

	private static final int TAG_IMAGE_WIDTH = 256;
	private static final int TAG_IMAGE_LENGTH = 257;
	private static final int TAG_STRIP_OFFSETS = 273;
	private static final int TAG_STRIP_BYTE_COUNTS = 279;
	private static final int TAG_TILE_OFFSETS = 324;
	private static final int TAG_TILE_BYTE_COUNTS = 325;

	private TiffCompletionCheck() { }

	/** Return false for an incomplete, malformed, locked, missing, or non-TIFF file. */
	static boolean isReady(File file) {
		if (file == null || !file.isFile() || file.length() < 8) return false;
		RandomAccessFile input = null;
		try {
			input = new RandomAccessFile(file, "r");
			return inspect(input);
		} catch (IOException incompleteOrLocked) {
			return false;
		} catch (RuntimeException malformed) {
			return false;
		} finally {
			if (input != null) try { input.close(); } catch (IOException ignored) { }
		}
	}

	private static boolean inspect(RandomAccessFile input) throws IOException {
		long length = input.length();
		input.seek(0);
		int b0 = input.readUnsignedByte();
		int b1 = input.readUnsignedByte();
		boolean little;
		if (b0 == 'I' && b1 == 'I') little = true;
		else if (b0 == 'M' && b1 == 'M') little = false;
		else return false;

		Reader reader = new Reader(input, little, length);
		int magic = reader.u16(2);
		final boolean big;
		final int countBytes;
		final int entryBytes;
		final int inlineBytes;
		long ifdOffset;
		if (magic == CLASSIC_MAGIC) {
			big = false;
			countBytes = 2;
			entryBytes = 12;
			inlineBytes = 4;
			ifdOffset = reader.u32(4);
		} else if (magic == BIG_TIFF_MAGIC) {
			if (length < 16 || reader.u16(4) != 8 || reader.u16(6) != 0) return false;
			big = true;
			countBytes = 8;
			entryBytes = 20;
			inlineBytes = 8;
			ifdOffset = reader.u64(8);
		} else return false;

		if (ifdOffset == 0) return false;
		Set<Long> visited = new HashSet<Long>();
		int ifdCount = 0;
		while (ifdOffset != 0) {
			if (++ifdCount > MAX_IFDS || !visited.add(Long.valueOf(ifdOffset))) return false;
			if (!reader.range(ifdOffset, countBytes)) return false;
			long entries = big ? reader.u64(ifdOffset) : reader.u16(ifdOffset);
			if (entries < 1 || entries > MAX_IFD_ENTRIES) return false;
			long entriesStart = safeAdd(ifdOffset, countBytes);
			long entriesSize = safeMultiply(entries, entryBytes);
			long nextPosition = safeAdd(entriesStart, entriesSize);
			if (!reader.range(nextPosition, big ? 8 : 4)) return false;

			Entry stripOffsets = null, stripCounts = null, tileOffsets = null, tileCounts = null;
			boolean hasWidth = false, hasLength = false;
			for (long index = 0; index < entries; index++) {
				long position = safeAdd(entriesStart, safeMultiply(index, entryBytes));
				int tag = reader.u16(position);
				int type = reader.u16(position + 2);
				long count = big ? reader.u64(position + 4) : reader.u32(position + 4);
				int typeBytes = typeBytes(type);
				if (count < 1 || typeBytes == 0) return false;
				long valueBytes = safeMultiply(count, typeBytes);
				long valuePosition = position + (big ? 12 : 8);
				long dataPosition = valueBytes <= inlineBytes
						? valuePosition : (big ? reader.u64(valuePosition) : reader.u32(valuePosition));
				if (!reader.range(dataPosition, valueBytes)) return false;

				Entry entry = new Entry(type, count, dataPosition);
				if (tag == TAG_IMAGE_WIDTH) hasWidth = true;
				else if (tag == TAG_IMAGE_LENGTH) hasLength = true;
				else if (tag == TAG_STRIP_OFFSETS) stripOffsets = entry;
				else if (tag == TAG_STRIP_BYTE_COUNTS) stripCounts = entry;
				else if (tag == TAG_TILE_OFFSETS) tileOffsets = entry;
				else if (tag == TAG_TILE_BYTE_COUNTS) tileCounts = entry;
			}

			if (!hasWidth || !hasLength) return false;
			if ((stripOffsets == null) != (stripCounts == null)
					|| (tileOffsets == null) != (tileCounts == null)) return false;
			boolean hasBlocks = false;
			if (stripOffsets != null) {
				if (!validateBlocks(reader, stripOffsets, stripCounts)) return false;
				hasBlocks = true;
			}
			if (tileOffsets != null) {
				if (!validateBlocks(reader, tileOffsets, tileCounts)) return false;
				hasBlocks = true;
			}
			if (!hasBlocks) return false;
			ifdOffset = big ? reader.u64(nextPosition) : reader.u32(nextPosition);
			if (ifdOffset != 0 && !reader.range(ifdOffset, countBytes)) return false;
		}
		return ifdCount > 0;
	}

	/** True only for a complete matching offsets/counts pair whose blocks all end by EOF. */
	private static boolean validateBlocks(Reader reader, Entry offsets, Entry counts) throws IOException {
		if (offsets == null || counts == null || offsets.count != counts.count) return false;
		for (long index = 0; index < offsets.count; index++) {
			long offset = integerValue(reader, offsets, index);
			long bytes = integerValue(reader, counts, index);
			if (offset <= 0 || bytes <= 0 || !reader.range(offset, bytes)) return false;
		}
		return true;
	}

	/** TIFF integer field types that are legal for strip/tile offsets and byte counts. */
	private static long integerValue(Reader reader, Entry entry, long index) throws IOException {
		long position;
		switch (entry.type) {
		case 1: // BYTE
		case 6: // SBYTE (accepted only while non-negative)
			position = safeAdd(entry.position, index);
			return reader.u8(position);
		case 3: // SHORT
		case 8: // SSHORT
			position = safeAdd(entry.position, safeMultiply(index, 2));
			return reader.u16(position);
		case 4: // LONG
		case 9: // SLONG
		case 13: // IFD
			position = safeAdd(entry.position, safeMultiply(index, 4));
			return reader.u32(position);
		case 16: // LONG8
		case 17: // SLONG8
		case 18: // IFD8
			position = safeAdd(entry.position, safeMultiply(index, 8));
			return reader.u64(position);
		default:
			throw new IOException("TIFF block offset/count is not an integer");
		}
	}

	private static int typeBytes(int type) {
		switch (type) {
		case 1: case 2: case 6: case 7: return 1;
		case 3: case 8: return 2;
		case 4: case 9: case 11: case 13: return 4;
		case 5: case 10: case 12: case 16: case 17: case 18: return 8;
		default: return 0;
		}
	}

	private static long safeAdd(long a, long b) throws IOException {
		if (a < 0 || b < 0 || a > Long.MAX_VALUE - b) throw new IOException("TIFF offset overflow");
		return a + b;
	}

	private static long safeMultiply(long a, long b) throws IOException {
		if (a < 0 || b < 0 || (a != 0 && b > Long.MAX_VALUE / a))
			throw new IOException("TIFF size overflow");
		return a * b;
	}

	private static final class Entry {
		final int type;
		final long count;
		final long position;

		Entry(int type, long count, long position) {
			this.type = type;
			this.count = count;
			this.position = position;
		}
	}

	/**
	 * Endian-aware bounded primitive reads over the one open file handle, through two cached blocks:
	 * an IFD and its strip tables usually share one, and the chain moves on block by block. Every read
	 * is checked against the length taken when the file was opened, as before; a block is read only up
	 * to that length, and a file that has since become shorter fails the read.
	 */
	private static final class Reader {
		private final RandomAccessFile input;
		private final boolean little;
		private final long length;
		private final int blockBytes;
		private final byte[][] blocks = new byte[2][];
		private final long[] blockStart = { -1, -1 };
		private final int[] blockFilled = new int[2];
		private int replace;

		Reader(RandomAccessFile input, boolean little, long length) {
			this.input = input;
			this.little = little;
			this.length = length;
			int test = blockBytesForTest;
			this.blockBytes = test > 0 ? test : BLOCK_BYTES;
		}

		boolean range(long offset, long bytes) {
			return offset >= 0 && bytes >= 0 && offset <= length && bytes <= length - offset;
		}

		/** The byte at {@code offset}, already checked to lie before {@code length}. */
		private int at(long offset) throws IOException {
			for (int i = 0; i < 2; i++)
				if (blockStart[i] >= 0 && offset >= blockStart[i] && offset < blockStart[i] + blockFilled[i])
					return blocks[i][(int) (offset - blockStart[i])] & 0xff;
			int slot = replace;
			replace ^= 1;
			long start = offset - offset % blockBytes;
			int size = (int) Math.min(blockBytes, length - start);
			if (blocks[slot] == null) blocks[slot] = new byte[blockBytes];
			blockStart[slot] = -1;		// not valid until it has been read whole
			input.seek(start);
			input.readFully(blocks[slot], 0, size);		// EOF if the file is shorter than it was
			readsForTest.incrementAndGet();
			blockStart[slot] = start;
			blockFilled[slot] = size;
			return blocks[slot][(int) (offset - start)] & 0xff;
		}

		int u8(long offset) throws IOException {
			if (!range(offset, 1)) throw new IOException("Unexpected TIFF EOF");
			return at(offset);
		}

		int u16(long offset) throws IOException {
			if (!range(offset, 2)) throw new IOException("Unexpected TIFF EOF");
			int a = at(offset), b = at(offset + 1);
			return little ? a | (b << 8) : (a << 8) | b;
		}

		long u32(long offset) throws IOException {
			if (!range(offset, 4)) throw new IOException("Unexpected TIFF EOF");
			long a = at(offset), b = at(offset + 1);
			long c = at(offset + 2), d = at(offset + 3);
			return little ? a | (b << 8) | (c << 16) | (d << 24)
					: (a << 24) | (b << 16) | (c << 8) | d;
		}

		long u64(long offset) throws IOException {
			if (!range(offset, 8)) throw new IOException("Unexpected TIFF EOF");
			long value = 0;
			if (little) {
				for (int index = 0; index < 8; index++)
					value |= ((long) at(offset + index)) << (8 * index);
			} else {
				for (int index = 0; index < 8; index++)
					value = (value << 8) | at(offset + index);
			}
			if (value < 0) throw new IOException("TIFF unsigned value exceeds supported file size");
			return value;
		}
	}
}
