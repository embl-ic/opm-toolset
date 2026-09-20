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
	 * <p>		16-bit stacks, including multi-channel hyperstacks - the two-channel results
	 * 			that "fold by midline" and "align with SIFT" produce compress just as well as
	 * 			single-channel volumes, and there is no reason to leave them uncompressed.
	 * 			Callers should fall back to ImageJ's own writer for anything else rather than
	 * 			guessing.
	 *
	 * @param imp				: image to test, may be null
	 * <p>
	 * @return					: true when {@link #write} will accept this image
	 */
	public static boolean canWrite (
			ImagePlus imp
			) {
		return imp != null && imp.getBitDepth() == 16 && VolumeIO.isReadable(imp);
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
			throw new IOException("FastTiffWriter handles readable 16-bit stacks only; got "
					+ (imp == null ? "null" : imp.getBitDepth() + "-bit, " + imp.getStackSize() + " slices"));
		write ( planeSource(imp), Layout.of(imp), file, level );
	}


	/**
	 * Where the planes of a streamed write come from.
	 *
	 * <p>Indexed 0..{@code planeCount()-1} in ImageJ's XYCZT order, the same order
	 * {@link ImagePlus#getStack()} uses, so a plane index maps onto (c, z, t) the way the
	 * ImageJ description block written here declares.
	 */
	public interface PlaneSource {
		/** One 16-bit plane; may be freshly allocated, and is not retained after use. */
		ImageProcessor plane (int index) throws IOException;
	}

	/**
	 * The shape and calibration a streamed write needs, without an ImagePlus to ask.
	 *
	 * <p>An ROI exported from a virtual 5-D view has no ImagePlus of its own - materialising
	 * one is exactly what the export exists to avoid - so the few numbers the TIFF header and
	 * the ImageJ description need are passed instead of the image.
	 */
	public static final class Layout {
		public int width;
		public int height;
		public int channels = 1;
		public int slices = 1;
		public int frames = 1;
		public String unit = "";
		public double pixelDepth = 1;
		public double frameInterval = 0;

		public int planeCount () {
			return Math.max(1, channels) * Math.max(1, slices) * Math.max(1, frames);
		}

		/** The layout of an existing image, for the ImagePlus entry points. */
		public static Layout of (ImagePlus imp) {
			Layout layout = new Layout();
			layout.width = imp.getWidth();
			layout.height = imp.getHeight();
			layout.channels = Math.max(1, imp.getNChannels());
			layout.slices = Math.max(1, imp.getNSlices());
			layout.frames = Math.max(1, imp.getNFrames());
			Calibration cal = imp.getCalibration();
			if (cal != null) {
				layout.unit = cal.getUnit() == null ? "" : cal.getUnit();
				layout.pixelDepth = cal.pixelDepth;
				layout.frameInterval = cal.frameInterval;
			}
			// a stack whose C*Z*T does not account for every slice is one long Z run
			if (layout.planeCount() != imp.getStackSize()) {
				layout.channels = 1;
				layout.frames = 1;
				layout.slices = imp.getStackSize();
			}
			return layout;
		}
	}

	private static PlaneSource planeSource (final ImagePlus imp) {
		final ImageStack stack = imp.getStack();
		return new PlaneSource() {
			@Override public ImageProcessor plane (int index) {
				return stack.getProcessor(index + 1);
			}
		};
	}


	/**			Write a Deflate-compressed TIFF from planes pulled one at a time
	 * <p>		The plane offsets can only be computed once every plane's compressed length is
	 * 			known, so the compressed bytes are all held - bounded by the 4 GB classic TIFF
	 * 			limit checked below. The <em>uncompressed</em> planes are not: they are pulled
	 * 			and released a batch at a time, which is what lets a region of a virtual volume
	 * 			be exported without ever materialising the volume.
	 *
	 * @param source			: supplies plane 0..{@code layout.planeCount()-1} on demand
	 * @param layout			: dimensions and calibration for the header
	 * @param file				: destination file
	 * @param level				: Deflate level, 0 (store) to 9
	 * <p>
	 * @throws IOException		: if the layout is unusable, or reading or writing fails
	 */
	public static void write (
			PlaneSource source,
			Layout layout,
			File file,
			int level
			) throws IOException {
		if (source == null || layout == null) throw new IOException("A plane source and a layout are required.");
		if (layout.width < 1 || layout.height < 1 || layout.planeCount() < 1)
			throw new IOException("Nothing to write: " + layout.width + "x" + layout.height
					+ "x" + layout.planeCount());
		if (level < 0 || level > 9) throw new IOException("Deflate level out of range: " + level);

		final int w = layout.width, h = layout.height, d = layout.planeCount();
		byte[][] planes = compressPlanes ( source, layout, level );

		// strips are laid out immediately after the header, then the chained IFDs follow
		long[] stripOffset = new long[d];
		long cursor = HEADER_BYTES;
		for (int z = 0; z < d; z++) { stripOffset[z] = cursor; cursor += planes[z].length; }
		long dataEnd = cursor;

		byte[] description = imageJDescription(layout, d).getBytes("US-ASCII");
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
	 * <p>		Planes are pulled a batch at a time rather than all at once. For an ImagePlus
	 * 			that changes nothing, since its pixels are already in RAM; for a virtual or
	 * 			file-backed source it is the difference between holding one batch of
	 * 			uncompressed planes and holding the whole volume.
	 */
	private static byte[][] compressPlanes (
			final PlaneSource source,
			final Layout layout,
			final int level
			) throws IOException {
		final int w = layout.width, h = layout.height, d = layout.planeCount();
		final byte[][] planes = new byte[d][];

		final int threads = Math.max(1, Math.min(FastTiffReader.logicalProcessorCount(), d));
		// enough work to keep every core busy, few enough planes to bound the uncompressed RAM
		final int batch = Math.min(d, Math.max(threads * 4, 8));
		ExecutorService pool = Executors.newFixedThreadPool(threads, Shutdown.daemonThreads("OPM-tiff-write"));
		try {
			final short[][] pixels = new short[batch][];
			for (int base = 0; base < d; base += batch) {
				final int here = Math.min(batch, d - base);
				for (int i = 0; i < here; i++) {
					ImageProcessor ip = source.plane(base + i);
					if (ip == null) throw new IOException("Plane " + (base + i) + " is not available.");
					Object raw = ip.getPixels();
					if (!(raw instanceof short[]))
						throw new IOException("FastTiffWriter needs 16-bit planes; plane "
								+ (base + i) + " is " + ip.getBitDepth() + "-bit.");
					if (ip.getWidth() != w || ip.getHeight() != h)
						throw new IOException("Plane " + (base + i) + " is " + ip.getWidth() + "x"
								+ ip.getHeight() + ", expected " + w + "x" + h);
					pixels[i] = (short[]) raw;
				}
				final int offset = base;
				List<Future<Void>> futures = new ArrayList<Future<Void>>();
				for (int t = 0; t < threads; t++) {
					final int from = (int) Math.floor((double) here * t / threads);
					final int to = (int) Math.floor((double) here * (t + 1) / threads);
					if (from >= to) continue;
					futures.add(pool.submit(new Callable<Void>() {
						@Override
						public Void call() {
							Deflater deflater = new Deflater(level);
							byte[] plain = new byte[w * h * 2];
							byte[] buffer = new byte[1 << 16];
							try {
								for (int i = from; i < to; i++) {
									short[] px = pixels[i];
									for (int p = 0; p < px.length; p++) {
										plain[2 * p] = (byte) px[p];					// little-endian, matching the "II" header
										plain[2 * p + 1] = (byte) (px[p] >>> 8);
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
									planes[offset + i] = sink.toByteArray();
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
				// release this batch's uncompressed pixels before the next one is pulled
				java.util.Arrays.fill(pixels, null);
			}
		} finally {
			pool.shutdownNow();
		}
		return planes;
	}


	/** The ImageJ metadata block, so the file reopens as a calibrated stack rather than pages. */
	private static String imageJDescription (
			Layout layout,
			int planeCount
			) {
		int channels = Math.max(1, layout.channels);
		int frames = Math.max(1, layout.frames);
		StringBuilder sb = new StringBuilder("ImageJ=1.54f\n");
		sb.append("images=").append(planeCount).append('\n');
		/* Planes go out in ImageJ's XYCZT order, so declaring channels and frames here is
		 * what lets a two-channel deskew result - what "fold by midline" and "align with
		 * SIFT" produce - reopen as a composite hyperstack rather than one long stack. */
		if (channels > 1) sb.append("channels=").append(channels).append('\n');
		if (layout.slices > 1) sb.append("slices=").append(layout.slices).append('\n');
		if (frames > 1) sb.append("frames=").append(frames).append('\n');
		if (channels > 1 || frames > 1) sb.append("hyperstack=true\n");
		if (channels > 1) sb.append("mode=composite\n");
		if (layout.unit != null && !layout.unit.isEmpty())
			sb.append("unit=").append(layout.unit).append('\n');
		if (layout.pixelDepth != 0 && layout.pixelDepth != 1)
			sb.append("spacing=").append(layout.pixelDepth).append('\n');
		if (layout.frameInterval != 0)
			sb.append("finterval=").append(layout.frameInterval).append('\n');
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
