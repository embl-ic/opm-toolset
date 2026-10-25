package de.embl.iclm;

import java.io.File;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * Where every plane of a raw acquisition TIFF sits in its file, for the Online Deskew View's
 * direct reads (joint contract C3.1).
 *
 * <p>The fast path needs an uncompressed, single-sample, 16-bit TIFF whose every plane is one
 * contiguous block: then any row of any plane is one positioned read at
 * {@code planeOffset[k] + y * width * 2}, nothing has to be decoded, and a strip-shaped cell is a
 * single read. The camera's BigTIFFs are like that (8-row strips, planes and their IFDs
 * interleaved). The planes do not need a constant stride; each plane's start is taken from its own
 * strip table and validated.
 *
 * <p>Anything else - a compressed or non-contiguous file - is described by
 * {@link #unsupportedReason} instead of being guessed at (C3.3). The view then says why it cannot
 * show the file rather than showing wrong pixels.
 */
final class RawTiffLayout {

	final int width, height, depth;
	final ByteOrder order;
	/** Rows per TIFF strip; the natural cell height. */
	final int rowsPerStrip;
	/** Byte offset of the first pixel of each plane; null when unsupported. */
	private final long[] planeOffset;
	/** Null when the fast path applies, else why it does not. */
	final String unsupportedReason;

	private RawTiffLayout(int width, int height, int depth, ByteOrder order, int rowsPerStrip,
			long[] planeOffset, String unsupportedReason) {
		this.width = width;
		this.height = height;
		this.depth = depth;
		this.order = order;
		this.rowsPerStrip = rowsPerStrip;
		this.planeOffset = planeOffset;
		this.unsupportedReason = unsupportedReason;
	}

	/**
	 * Read and validate the layout from the file's IFDs (no pixel is read).
	 *
	 * @throws IOException when the TIFF structure itself cannot be read
	 */
	static RawTiffLayout read(File file) throws IOException {
		return of(FastTiffReader.parse(file));
	}

	static RawTiffLayout of(FastTiffReader.Info info) {
		int depth = info.depth();
		int rows = (int) Math.max(1, Math.min(info.rowsPerStrip <= 0 ? info.height : info.rowsPerStrip, info.height));
		String reason = null;
		if (info.compression != FastTiffReader.COMPRESSION_NONE)
			reason = "compressed (TIFF compression " + info.compression + ")";
		else if (info.bitsPerSample != 16)
			reason = info.bitsPerSample + "-bit samples, not 16-bit";
		else if (info.samplesPerPixel > 1)
			reason = info.samplesPerPixel + " samples per pixel";
		else if (depth < 1)
			reason = "no planes";
		long[] offsets = null;
		if (reason == null) {
			offsets = new long[depth];
			long planeBytes = 2L * info.width * info.height;
			for (int k = 0; k < depth && reason == null; k++) {
				long[] starts = info.stripOffsets.get(k);
				long[] counts = info.stripByteCounts.get(k);
				long expected = starts[0], total = 0;
				for (int s = 0; s < starts.length; s++) {
					if (starts[s] != expected) {
						reason = String.format(Locale.US, "plane %d is not one contiguous block", k);
						break;
					}
					expected += counts[s];
					total += counts[s];
				}
				if (reason == null && total != planeBytes)
					reason = String.format(Locale.US, "plane %d holds %d bytes, not %d", k, total, planeBytes);
				offsets[k] = starts[0];
			}
		}
		return new RawTiffLayout(info.width, info.height, depth, info.byteOrder, rows,
				reason == null ? offsets : null, reason);
	}

	boolean supported() {
		return unsupportedReason == null;
	}

	/** File offset of pixel (x, y) of plane k. */
	long offset(int x, int y, int k) {
		return planeOffset[k] + 2L * ((long) y * width + x);
	}

	long[] dimensions() {
		return new long[] { width, height, depth };
	}

	@Override
	public String toString() {
		return String.format(Locale.US, "%d x %d x %d, %d rows per strip%s", width, height, depth, rowsPerStrip,
				supported() ? "" : ", unsupported: " + unsupportedReason);
	}
}
