package de.embl.iclm;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ImageProcessor;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.Deflater;

/**
 * Writes a 16-bit grayscale stack as a standard TIFF with one Deflate strip per plane,
 * compressing the planes in parallel.
 *
 * <p>Deskewing shears a box into a parallelepiped, so a large part of the output bounding
 * box is empty by construction - between a third and three quarters of it, depending on how
 * the scan length compares with the camera height. Those zeros cost almost nothing once
 * they are compressed.
 *
 * <p>Measured on a full 3200 x 1916 x 339 deskewed volume (3964 MB uncompressed, from a
 * 2545 MB raw acquisition file):
 *
 * <pre>
 *   uncompressed TIFF        3964 MB    1.56x the raw file
 *   Deflate, one stream       530 MB    0.21x        13.6 s write   12.5 s read
 *   Deflate, per plane        530 MB    0.21x         1.4 s write    0.6 s read
 * </pre>
 *
 * <p>Splitting by plane costs nothing in size and buys an order of magnitude in time,
 * because planes compress and decompress on separate threads; a single Deflate stream
 * cannot be split. Level 1 is used by default: level 9 is only 8% smaller and 22 times
 * slower to write.
 *
 * <p>The output is an ordinary multi-page TIFF. ImageJ, Fiji, Bio-Formats, Python's
 * tifffile and MATLAB all read it - unlike ImageJ's <code>.zip</code>, which achieves the
 * same ratio but only ImageJ opens. {@link FastTiffReader} reads it back with the planes
 * inflated in parallel.
 *
 * @see FastTiffReader
 */
public class FastTiffWriter {

	/** Deflate level 1: within 8% of the best ratio these volumes reach, many times faster. */
	public static final int DEFAULT_LEVEL = 1;

	private static final int TAG_IMAGE_WIDTH = 256;
	private static final int TAG_IMAGE_LENGTH = 257;
	private static final int TAG_BITS_PER_SAMPLE = 258;
	private static final int TAG_COMPRESSION = 259;
	private static final int TAG_PHOTOMETRIC = 262;
	private static final int TAG_IMAGE_DESCRIPTION = 270;
	private static final int TAG_STRIP_OFFSETS = 273;
	private static final int TAG_SAMPLES_PER_PIXEL = 277;
	private static final int TAG_ROWS_PER_STRIP = 278;
	private static final int TAG_STRIP_BYTE_COUNTS = 279;
	private static final int TAG_SAMPLE_FORMAT = 339;

	private static final int TYPE_ASCII = 2;
	private static final int TYPE_SHORT = 3;
	private static final int TYPE_LONG = 4;

	private static final int HEADER_BYTES = 8;
	private static final long CLASSIC_TIFF_LIMIT = 0xFFFFFFFFL;

	/**			Whether this writer can handle the given image
	 * <p>		16-bit single-channel stacks only, which is what the deskew, projection and
	 * 			deconvolution outputs are. Callers should fall back to ImageJ's own writer
	 * 			for anything else rather than guessing.
	 *
	 * @param imp				: image to test, may be null
	 * <p>
	 * @return					: true when {@link #write} will accept this image
	 */
	public static boolean canWrite (
			ImagePlus imp
			) {
		return imp != null && imp.getBitDepth() == 16 && imp.getNChannels() == 1 && imp.getStackSize() >= 1;
	}


	/**			Write a 16-bit stack as a Deflate-compressed TIFF at the default level
	 *
	 * @param imp				: 16-bit single-channel image or stack
	 * @param file				: destination file
	 * <p>
	 * @throws IOException		: if the image is unsupported, or writing fails
	 */
	public static void write (
			ImagePlus imp,
			File file
			) throws IOException {
		write ( imp, file, DEFAULT_LEVEL );
	}


	/**			Write a 16-bit stack as a Deflate-compressed TIFF
	 *
	 * @param imp				: 16-bit single-channel image or stack
	 * @param file				: destination file
	 * @param level				: Deflate level, 0 (store) to 9; 1 is the useful default
	 * <p>
	 * @throws IOException		: if the image is unsupported, or writing fails
	 */
	public static void write (
			ImagePlus imp,
			File file,
			int level
			) throws IOException {
		if (!canWrite(imp))
			throw new IOException("FastTiffWriter handles 16-bit single-channel stacks only; got "
					+ (imp == null ? "null" : imp.getBitDepth() + "-bit, " + imp.getNChannels() + " channels"));
		if (level < 0 || level > 9) throw new IOException("Deflate level out of range: " + level);

		final int w = imp.getWidth(), h = imp.getHeight(), d = imp.getStackSize();
		byte[][] planes = compressPlanes ( imp, level );

		// strips are laid out immediately after the header, then the chained IFDs follow
		long[] stripOffset = new long[d];
		long cursor = HEADER_BYTES;
		for (int z = 0; z < d; z++) { stripOffset[z] = cursor; cursor += planes[z].length; }
		long dataEnd = cursor;

		byte[] description = imageJDescription(imp).getBytes("US-ASCII");
		// the description only rides on the first IFD, so only that one carries an out-of-line value
		int entriesFirst = 11, entriesRest = 10;
		long ifdFirstBytes = ifdBytes(entriesFirst) + pad2(description.length);
		long ifdRestBytes = ifdBytes(entriesRest);
		long totalBytes = dataEnd + ifdFirstBytes + (d - 1) * ifdRestBytes;
		if (totalBytes > CLASSIC_TIFF_LIMIT)
			throw new IOException("Compressed volume exceeds the 4 GB classic TIFF limit ("
					+ totalBytes + " bytes). Save this one uncompressed, or split it.");

		File parent = file.getParentFile();
		if (parent != null && !parent.isDirectory() && !parent.mkdirs())
			throw new IOException("Could not create output folder: " + parent);

		OutputStream out = new BufferedOutputStream(new FileOutputStream(file), 1 << 22);
		try {
			// --- header: little-endian classic TIFF, first IFD sits after the pixel data ---
			out.write(new byte[] { 'I', 'I' });
			writeShort(out, 42);
			writeInt(out, dataEnd);

			for (int z = 0; z < d; z++) out.write(planes[z]);

			long ifdOffset = dataEnd;
			for (int z = 0; z < d; z++) {
				boolean first = (z == 0);
				int entries = first ? entriesFirst : entriesRest;
				long thisIfdBytes = first ? ifdFirstBytes : ifdRestBytes;
				long nextIfd = (z == d - 1) ? 0 : ifdOffset + thisIfdBytes;
				// out-of-line values sit right after the entries and the next-IFD pointer
				long descriptionOffset = ifdOffset + ifdBytes(entries);

				writeShort(out, entries);
				// tags must appear in ascending order
				writeEntry(out, TAG_IMAGE_WIDTH, TYPE_LONG, 1, w);
				writeEntry(out, TAG_IMAGE_LENGTH, TYPE_LONG, 1, h);
				writeEntry(out, TAG_BITS_PER_SAMPLE, TYPE_SHORT, 1, 16);
				writeEntry(out, TAG_COMPRESSION, TYPE_SHORT, 1, FastTiffReader.COMPRESSION_DEFLATE);
				writeEntry(out, TAG_PHOTOMETRIC, TYPE_SHORT, 1, 1);				// black is zero
				if (first) writeEntry(out, TAG_IMAGE_DESCRIPTION, TYPE_ASCII, description.length, descriptionOffset);
				writeEntry(out, TAG_STRIP_OFFSETS, TYPE_LONG, 1, stripOffset[z]);
				writeEntry(out, TAG_SAMPLES_PER_PIXEL, TYPE_SHORT, 1, 1);
				writeEntry(out, TAG_ROWS_PER_STRIP, TYPE_LONG, 1, h);			// one strip per plane
				writeEntry(out, TAG_STRIP_BYTE_COUNTS, TYPE_LONG, 1, planes[z].length);
				writeEntry(out, TAG_SAMPLE_FORMAT, TYPE_SHORT, 1, 1);			// unsigned integer
				writeInt(out, nextIfd);
				if (first) {
					out.write(description);
					if ((description.length & 1) == 1) out.write(0);				// values start on even offsets
				}
				ifdOffset += thisIfdBytes;
			}
		} finally {
			out.close();
		}
	}


	/**			Deflate every plane, in parallel, into its own byte array
	 * <p>		One Deflate stream per plane is what lets {@link FastTiffReader} inflate the
	 * 			volume on all cores. Pixels are written little-endian to match the "II"
	 * 			header this writer emits.
	 */
	private static byte[][] compressPlanes (
			final ImagePlus imp,
			final int level
			) throws IOException {
		final int w = imp.getWidth(), h = imp.getHeight(), d = imp.getStackSize();
		final ImageStack stack = imp.getStack();
		final byte[][] planes = new byte[d][];
		final short[][] pixels = new short[d][];
		for (int z = 0; z < d; z++) {
			ImageProcessor ip = stack.getProcessor(z + 1);
			pixels[z] = (short[]) ip.getPixels();
		}

		int threads = Math.max(1, Math.min(FastTiffReader.logicalProcessorCount(), d));
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<Void>> futures = new ArrayList<Future<Void>>();
			for (int t = 0; t < threads; t++) {
				final int zStart = (int) Math.floor((double) d * t / threads);
				final int zEnd = (int) Math.floor((double) d * (t + 1) / threads);
				futures.add(pool.submit(new Callable<Void>() {
					@Override
					public Void call() {
						Deflater deflater = new Deflater(level);
						byte[] plain = new byte[w * h * 2];
						byte[] buffer = new byte[1 << 16];
						try {
							for (int z = zStart; z < zEnd; z++) {
								short[] px = pixels[z];
								for (int i = 0; i < px.length; i++) {
									plain[2 * i] = (byte) px[i];					// little-endian, matching the "II" header
									plain[2 * i + 1] = (byte) (px[i] >>> 8);
								}
								deflater.reset();
								deflater.setInput(plain);
								deflater.finish();
								java.io.ByteArrayOutputStream sink =
										new java.io.ByteArrayOutputStream(plain.length / 4 + 64);
								while (!deflater.finished()) {
									int n = deflater.deflate(buffer);
									sink.write(buffer, 0, n);
								}
								planes[z] = sink.toByteArray();
							}
						} finally {
							deflater.end();
						}
						return null;
					}
				}));
			}
			for (Future<Void> f : futures) {
				try {
					f.get();
				} catch (Exception e) {
					throw new IOException("Compressing TIFF planes failed: " + e.getMessage(), e);
				}
			}
		} finally {
			pool.shutdownNow();
		}
		return planes;
	}


	/** The ImageJ metadata block, so the file reopens as a calibrated stack rather than pages. */
	private static String imageJDescription (
			ImagePlus imp
			) {
		StringBuilder sb = new StringBuilder("ImageJ=1.54f\n");
		sb.append("images=").append(imp.getStackSize()).append('\n');
		if (imp.getNSlices() > 1) sb.append("slices=").append(imp.getNSlices()).append('\n');
		if (imp.getNFrames() > 1) sb.append("frames=").append(imp.getNFrames()).append('\n');
		Calibration cal = imp.getCalibration();
		if (cal != null) {
			if (cal.getUnit() != null && !cal.getUnit().isEmpty())
				sb.append("unit=").append(cal.getUnit()).append('\n');
			if (cal.pixelDepth != 0 && cal.pixelDepth != 1)
				sb.append("spacing=").append(cal.pixelDepth).append('\n');
			if (cal.frameInterval != 0)
				sb.append("finterval=").append(cal.frameInterval).append('\n');
		}
		sb.append('\0');					// ASCII fields are NUL terminated
		return sb.toString();
	}

	private static long ifdBytes (int entries) {
		return 2L + 12L * entries + 4L;		// entry count, entries, next-IFD pointer
	}

	private static int pad2 (int n) {
		return (n & 1) == 1 ? n + 1 : n;
	}

	private static void writeEntry (OutputStream out, int tag, int type, long count, long value)
			throws IOException {
		writeShort(out, tag);
		writeShort(out, type);
		writeInt(out, count);
		if (type == TYPE_SHORT) {			// a SHORT value is left-aligned in the 4-byte field
			writeShort(out, (int) value);
			writeShort(out, 0);
		} else {
			writeInt(out, value);
		}
	}

	private static void writeShort (OutputStream out, int v) throws IOException {
		out.write(v & 0xff);
		out.write((v >>> 8) & 0xff);
	}

	private static void writeInt (OutputStream out, long v) throws IOException {
		out.write((int) (v & 0xff));
		out.write((int) ((v >>> 8) & 0xff));
		out.write((int) ((v >>> 16) & 0xff));
		out.write((int) ((v >>> 24) & 0xff));
	}
}
