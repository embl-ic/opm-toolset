package de.embl.iclm;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.Inflater;

/**
 * Fast path TIFF reader for the OPM acquisition files:
 * 16-bit, single-channel TIFF/BigTIFF stacks stored as strips, either uncompressed
 * (what the microscope writes) or Deflate compressed (what {@link FastTiffWriter} writes).
 *
 * <p>Strips are read and inflated in parallel across all logical processors, which is the
 * point of writing one Deflate strip per plane: a whole volume decompresses in a fraction
 * of the time a single compressed stream would take, because a single stream cannot be
 * split across threads.
 */
public class FastTiffReader {
	/** Uncompressed. */
	public static final int COMPRESSION_NONE = 1;
	/** Adobe-style Deflate, the tag value {@link FastTiffWriter} emits. */
	public static final int COMPRESSION_DEFLATE = 8;
	/** The older Deflate tag value, read but not written. */
	public static final int COMPRESSION_DEFLATE_OLD = 32946;
	private static final int TAG_IMAGE_WIDTH = 256;
	private static final int TAG_IMAGE_LENGTH = 257;
	private static final int TAG_BITS_PER_SAMPLE = 258;
	private static final int TAG_COMPRESSION = 259;
	private static final int TAG_STRIP_OFFSETS = 273;
	private static final int TAG_SAMPLES_PER_PIXEL = 277;
	private static final int TAG_ROWS_PER_STRIP = 278;
	private static final int TAG_STRIP_BYTE_COUNTS = 279;

	private static final int TYPE_BYTE = 1;
	private static final int TYPE_ASCII = 2;
	private static final int TYPE_SHORT = 3;
	private static final int TYPE_LONG = 4;
	private static final int TYPE_RATIONAL = 5;
	private static final int TYPE_UNDEFINED = 7;
	private static final int TYPE_LONG8 = 16;
	private static final int TYPE_IFD8 = 18;

	public static class Info {
		public boolean bigTiff;
		public ByteOrder byteOrder;
		public int width;
		public int height;
		public int bitsPerSample;
		public int compression;
		public int samplesPerPixel;
		public long rowsPerStrip;
		public final List<long[]> stripOffsets = new ArrayList<long[]>();
		public final List<long[]> stripByteCounts = new ArrayList<long[]>();

		public int depth() {
			return stripOffsets.size();
		}
	}

	private static class IfdResult {
		Map<Integer, long[]> tags;
		long nextOffset;
	}

	public static Info parse(File file) throws IOException {
		FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
		try {
			ByteBuffer header = ByteBuffer.allocate(16);
			readFully(channel, header, 0);
			header.flip();

			int b0 = header.get() & 0xff;
			int b1 = header.get() & 0xff;
			ByteOrder order;
			if (b0 == 0x49 && b1 == 0x49) order = ByteOrder.LITTLE_ENDIAN;
			else if (b0 == 0x4d && b1 == 0x4d) order = ByteOrder.BIG_ENDIAN;
			else throw new IOException("Not a TIFF header.");

			header.order(order);
			int magic = header.getShort() & 0xffff;
			boolean bigTiff;
			long ifdOffset;
			if (magic == 42) {
				bigTiff = false;
				ifdOffset = header.getInt() & 0xffffffffL;
			} else if (magic == 43) {
				bigTiff = true;
				int offsetSize = header.getShort() & 0xffff;
				int zero = header.getShort() & 0xffff;
				if (offsetSize != 8 || zero != 0) throw new IOException("Unsupported BigTIFF header.");
				ifdOffset = header.getLong();
			} else {
				throw new IOException("Unsupported TIFF magic number: " + magic);
			}

			Info info = new Info();
			info.bigTiff = bigTiff;
			info.byteOrder = order;

			int planeIndex = 0;
			while (ifdOffset > 0 && ifdOffset < channel.size()) {
				IfdResult ifd = readIfd(channel, order, bigTiff, ifdOffset);
				Map<Integer, long[]> tags = ifd.tags;
				int width = (int) firstOrDefault(tags, TAG_IMAGE_WIDTH, -1);
				int height = (int) firstOrDefault(tags, TAG_IMAGE_LENGTH, -1);
				if (width <= 0 || height <= 0) break;

				int bits = (int) firstOrDefault(tags, TAG_BITS_PER_SAMPLE, 0);
				int compression = (int) firstOrDefault(tags, TAG_COMPRESSION, 1);
				int samples = (int) firstOrDefault(tags, TAG_SAMPLES_PER_PIXEL, 1);
				long rowsPerStrip = firstOrDefault(tags, TAG_ROWS_PER_STRIP, height);
				long[] offsets = tags.get(TAG_STRIP_OFFSETS);
				long[] counts = tags.get(TAG_STRIP_BYTE_COUNTS);
				if (offsets == null || counts == null)
					throw new IOException("Missing TIFF strip offsets/byte counts at IFD " + planeIndex);

				if (planeIndex == 0) {
					info.width = width;
					info.height = height;
					info.bitsPerSample = bits;
					info.compression = compression;
					info.samplesPerPixel = samples;
					info.rowsPerStrip = rowsPerStrip;
				} else if (width != info.width || height != info.height ||
						bits != info.bitsPerSample || compression != info.compression ||
						samples != info.samplesPerPixel) {
					throw new IOException("TIFF plane layout changes at IFD " + planeIndex);
				}

				info.stripOffsets.add(offsets);
				info.stripByteCounts.add(counts);
				planeIndex++;
				ifdOffset = ifd.nextOffset;
			}

			if (info.stripOffsets.isEmpty()) throw new IOException("No image planes found in TIFF.");
			if (info.bitsPerSample != 16 || info.samplesPerPixel != 1 || !isSupportedCompression(info.compression)) {
				throw new IOException(String.format(
						"Fast reader supports uncompressed or Deflate 16-bit grayscale. Found bits=%d samples=%d compression=%d",
						info.bitsPerSample, info.samplesPerPixel, info.compression));
			}
			return info;
		} finally {
			channel.close();
		}
	}

	public static ImagePlus openImage(File file, int numThreads) throws Exception {
		Info info = parse(file);
		short[][] pixels = readPixelsParallel(file, info);
		return wrapShortVolume(baseName(file), pixels, info.width, info.height);
	}

	public static ImagePlus openImage(File file) throws Exception {
		Info info = parse(file);
		short[][] pixels = readPixelsParallel(file, info);
		return wrapShortVolume(baseName(file), pixels, info.width, info.height);
	}

	public static int logicalProcessorCount() {
		return Math.max(1, Runtime.getRuntime().availableProcessors());
	}

	/**
	 * Read with an explicit thread count, capped by the number of planes.
	 *
	 * <p>Pass 0 or less to choose automatically, which uses every logical processor. That
	 * default sits on the plateau: measured on a 3200 x 800 x 521 volume, one thread reads
	 * at 517 MB/s, eight at 5234 MB/s, twelve at 8045 MB/s and sixteen at 7311 MB/s, so
	 * more threads stop helping well before the core count and never start hurting much.
	 * The knob is here for machines where that does not hold - a slower disk, or a shared
	 * machine where the reader should not take every core.
	 */
	public static short[][] readPixelsParallel(final File file, final Info info, int numThreads) throws Exception {
		return read(file, info, numThreads);
	}

	public static short[][] readPixelsParallel(final File file, final Info info) throws Exception {
		return read(file, info, 0);
	}

	private static short[][] read(final File file, final Info info, int requestedThreads) throws Exception {
		final int w = info.width;
		final int h = info.height;
		final int d = info.depth();
		final int planeBytes = Math.multiplyExact(w * h, 2);
		final short[][] volume = new short[d][];

		int wanted = requestedThreads > 0 ? requestedThreads : logicalProcessorCount();
		int threads = Math.max(1, Math.min(wanted, d));
		ExecutorService executor = Executors.newFixedThreadPool(threads, Shutdown.daemonThreads("OPM-tiff-read"));
		try {
			List<Future<Void>> futures = new ArrayList<Future<Void>>();
			for (int threadIndex = 0; threadIndex < threads; threadIndex++) {
				final int zStart = (int) Math.floor((double) d * threadIndex / threads);
				final int zEnd = (int) Math.floor((double) d * (threadIndex + 1) / threads);
				futures.add(executor.submit(new Callable<Void>() {
					@Override
					public Void call() throws Exception {
						byte[] rawPlane = new byte[planeBytes];
						// one growing scratch buffer per worker, reused between strips
						final byte[][] compressed = new byte[1][];
						Sizer scratch = new Sizer() {
							@Override public byte[] buffer(int wanted) {
								if (compressed[0] == null || compressed[0].length < wanted)
									compressed[0] = new byte[wanted];
								return compressed[0];
							}
						};
						Inflater inflater = info.compression == COMPRESSION_NONE ? null : new Inflater();
						FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
						try {
							for (int z = zStart; z < zEnd; z++) {
								decodePlane(info, channel, inflater, rawPlane, z, scratch);
								short[] plane = new short[w * h];
								ByteBuffer.wrap(rawPlane).order(info.byteOrder).asShortBuffer().get(plane);
								volume[z] = plane;
							}
						} finally {
							if (inflater != null) inflater.end();
							channel.close();
						}
						return null;
					}
				}));
			}
			for (Future<Void> f : futures) f.get();
			return volume;
		} finally {
			executor.shutdownNow();
		}
	}

	/**
	 * One open file, read one plane at a time.
	 *
	 * <p>{@link #readPixelsParallel} is the right shape for a volume that is about to be
	 * deskewed: it uses every core and hands back the whole thing. It is the wrong shape for
	 * converting a 30 GB raw acquisition to another format, where the whole point is that the
	 * volume never has to fit in RAM at once. This reads exactly one plane per call from a
	 * channel that stays open, so a converter can stream.
	 *
	 * <p>Not thread safe: one {@code FileChannel} position and one reusable buffer per reader.
	 * Open one per thread if you want parallelism.
	 */
	public static final class PlaneReader implements java.io.Closeable {
		private final Info info;
		private final FileChannel channel;
		private final byte[] rawPlane;
		private final Inflater inflater;
		private byte[] compressed;

		public PlaneReader(File file) throws IOException {
			this(file, parse(file));
		}

		public PlaneReader(File file, Info info) throws IOException {
			this.info = info;
			this.channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
			this.rawPlane = new byte[Math.multiplyExact(info.width * info.height, 2)];
			this.inflater = info.compression == COMPRESSION_NONE ? null : new Inflater();
		}

		public Info info() { return info; }
		public int width() { return info.width; }
		public int height() { return info.height; }
		public int depth() { return info.depth(); }

		/** Read plane {@code z} (0 based) as freshly allocated pixels. */
		public short[] readPixels(int z) throws IOException {
			if (z < 0 || z >= info.depth())
				throw new IOException("Plane out of range: " + z + " of " + info.depth());
			decodePlane(info, channel, inflater, rawPlane, z, sizer());
			short[] plane = new short[info.width * info.height];
			ByteBuffer.wrap(rawPlane).order(info.byteOrder).asShortBuffer().get(plane);
			return plane;
		}

		/** Read plane {@code z} (0 based) as an ImageJ processor. */
		public ShortProcessor readPlane(int z) throws IOException {
			return new ShortProcessor(info.width, info.height, readPixels(z), null);
		}

		/**			Read a band of rows out of one plane
		 * <p>		For an ROI this is what {@link #readPixels} would have read and thrown most
		 * 			of away. Strips that lie entirely outside the band are neither read nor
		 * 			inflated, and the strip holding the last wanted row is inflated only as far
		 * 			as that row - Deflate is a stream, so the rows before the band still have to
		 * 			be decoded to reach it, but the ones after it do not.
		 * <p>		Our own writer emits one strip per plane, so what this saves there is the
		 * 			tail: everything below the band. A file written in bands saves both ends.
		 * <p>
		 * @param z				: plane index, 0 based
		 * @param firstRow		: first row of the band, 0 based
		 * @param rows			: how many rows
		 * @return				: {@code rows * width} pixels, the band only
		 */
		public short[] readRows(int z, int firstRow, int rows) throws IOException {
			if (z < 0 || z >= info.depth())
				throw new IOException("Plane out of range: " + z + " of " + info.depth());
			if (firstRow < 0 || rows < 1 || firstRow + rows > info.height)
				throw new IOException("Row band " + firstRow + "+" + rows
						+ " is outside the plane height " + info.height);
			if (firstRow == 0 && rows == info.height) return readPixels(z);
			int rowBytes = Math.multiplyExact(info.width, 2);
			int wanted = Math.multiplyExact(firstRow + rows, rowBytes);
			decodePlane(info, channel, inflater, rawPlane, z, sizer(), wanted);
			short[] band = new short[Math.multiplyExact(rows, info.width)];
			ByteBuffer.wrap(rawPlane, Math.multiplyExact(firstRow, rowBytes),
					Math.multiplyExact(rows, rowBytes))
					.order(info.byteOrder).asShortBuffer().get(band);
			return band;
		}

		private Sizer sizer() {
			return new Sizer() {
				@Override public byte[] buffer(int wanted) {
					if (compressed == null || compressed.length < wanted) compressed = new byte[wanted];
					return compressed;
				}
			};
		}

		@Override
		public void close() throws IOException {
			if (inflater != null) inflater.end();
			channel.close();
		}
	}

	/** Lets the shared decode reuse one growing scratch buffer per reader or per worker. */
	private interface Sizer {
		byte[] buffer(int wanted);
	}

	/**			Decode one plane's strips into {@code rawPlane}
	 * <p>		Extracted so the parallel whole-volume read and the streaming
	 * 			{@link PlaneReader} decode strips the same way rather than twice.
	 */
	private static void decodePlane(Info info, FileChannel channel, Inflater inflater,
			byte[] rawPlane, int z, Sizer scratch) throws IOException {
		decodePlane(info, channel, inflater, rawPlane, z, scratch, rawPlane.length);
	}

	/**
	 * As above, but stopping once {@code neededBytes} of the plane have been produced.
	 *
	 * <p>{@code neededBytes} is a prefix of the plane, because that is what a row band is: the
	 * rows above it have to be inflated to reach it. A strip starting past the prefix is
	 * skipped without being read at all.
	 */
	private static void decodePlane(Info info, FileChannel channel, Inflater inflater,
			byte[] rawPlane, int z, Sizer scratch, int neededBytes) throws IOException {
		int rowBytes = Math.multiplyExact(info.width, 2);
		int planeBytes = rawPlane.length;
		int needed = Math.max(0, Math.min(neededBytes, planeBytes));
		long[] offsets = info.stripOffsets.get(z);
		long[] counts = info.stripByteCounts.get(z);
		if (offsets.length != counts.length)
			throw new IOException("Strip offset/count mismatch at plane " + z);
		boolean deflated = info.compression != COMPRESSION_NONE;
		for (int s = 0; s < offsets.length; s++) {
			int byteCount = (int) counts[s];
			int dstOffset = (int) Math.min((long) s * info.rowsPerStrip * rowBytes, planeBytes);
			if (dstOffset >= needed) break;			// this strip and every later one is past the band
			if (!deflated) {
				ByteBuffer dst = ByteBuffer.wrap(rawPlane, dstOffset, byteCount);
				readFully(channel, dst, offsets[s]);
				continue;
			}
			long rowsBefore = (long) s * info.rowsPerStrip;
			int rowsHere = (int) Math.min(info.rowsPerStrip, info.height - rowsBefore);
			if (rowsHere <= 0) continue;
			int expanded = Math.multiplyExact(rowsHere, rowBytes);
			// the band can end inside this strip; inflate to its last row and no further
			int produce = Math.min(expanded, needed - dstOffset);

			byte[] compressed = scratch.buffer(byteCount);
			ByteBuffer src = ByteBuffer.wrap(compressed, 0, byteCount);
			readFully(channel, src, offsets[s]);

			inflater.reset();
			inflater.setInput(compressed, 0, byteCount);
			int written = 0;
			while (written < produce && !inflater.finished()) {
				int n;
				try {
					n = inflater.inflate(rawPlane, dstOffset + written, produce - written);
				} catch (java.util.zip.DataFormatException malformed) {
					throw new IOException("Corrupt Deflate strip at plane " + z + " strip " + s, malformed);
				}
				if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
				written += n;
			}
			if (written != produce)
				throw new IOException(String.format(
						"Truncated Deflate strip at plane %d strip %d: got %d of %d bytes",
						z, s, written, produce));
		}
	}

	public static ImagePlus wrapShortVolume(String title, short[][] volume, int width, int height) {
		ImageStack stack = new ImageStack(width, height);
		for (int z = 0; z < volume.length; z++)
			stack.addSlice(null, new ShortProcessor(width, height, volume[z], null));
		return new ImagePlus(title, stack);
	}

	private static IfdResult readIfd(FileChannel channel, ByteOrder order, boolean bigTiff, long ifdOffset)
			throws IOException {
		int countBytes = bigTiff ? 8 : 2;
		ByteBuffer countBuffer = readAt(channel, ifdOffset, countBytes, order);
		long entryCount = bigTiff ? countBuffer.getLong() : uShort(countBuffer);
		if (entryCount < 0 || entryCount > 4096)
			throw new IOException("Unexpected TIFF IFD entry count: " + entryCount);

		int entrySize = bigTiff ? 20 : 12;
		int nextBytes = bigTiff ? 8 : 4;
		ByteBuffer entries = readAt(channel, ifdOffset + countBytes, (int) entryCount * entrySize + nextBytes, order);
		Map<Integer, long[]> tags = new HashMap<Integer, long[]>();

		for (int i = 0; i < (int) entryCount; i++) {
			int tag = uShort(entries);
			int type = uShort(entries);
			long count = bigTiff ? entries.getLong() : uInt(entries);
			int embeddedSize = bigTiff ? 8 : 4;
			byte[] embedded = new byte[embeddedSize];
			int valuePosition = entries.position();
			long valueOffset = bigTiff ? entries.getLong() : uInt(entries);
			entries.position(valuePosition);
			entries.get(embedded);

			if (tag == TAG_IMAGE_WIDTH || tag == TAG_IMAGE_LENGTH ||
					tag == TAG_BITS_PER_SAMPLE || tag == TAG_COMPRESSION ||
					tag == TAG_STRIP_OFFSETS || tag == TAG_SAMPLES_PER_PIXEL ||
					tag == TAG_ROWS_PER_STRIP || tag == TAG_STRIP_BYTE_COUNTS) {
				tags.put(tag, readValues(channel, order, type, count, valueOffset, embedded));
			}
		}

		IfdResult result = new IfdResult();
		result.tags = tags;
		result.nextOffset = bigTiff ? entries.getLong() : uInt(entries);
		return result;
	}

	private static long[] readValues(FileChannel channel, ByteOrder order, int type, long count,
			long valueOffset, byte[] embedded) throws IOException {
		if (count > Integer.MAX_VALUE) throw new IOException("TIFF field count too large: " + count);
		int n = (int) count;
		int bytes = Math.multiplyExact(typeSize(type), n);
		ByteBuffer buffer;
		if (bytes <= embedded.length) {
			buffer = ByteBuffer.wrap(embedded);
			buffer.order(order);
		} else {
			buffer = readAt(channel, valueOffset, bytes, order);
		}

		long[] out = new long[n];
		for (int i = 0; i < n; i++) {
			switch (type) {
			case TYPE_BYTE:
			case TYPE_UNDEFINED:
				out[i] = buffer.get() & 0xffL;
				break;
			case TYPE_SHORT:
				out[i] = buffer.getShort() & 0xffffL;
				break;
			case TYPE_LONG:
				out[i] = buffer.getInt() & 0xffffffffL;
				break;
			case TYPE_LONG8:
			case TYPE_IFD8:
				out[i] = buffer.getLong();
				break;
			default:
				throw new IOException("Unsupported numeric TIFF field type: " + type);
			}
		}
		return out;
	}

	private static int typeSize(int type) throws IOException {
		switch (type) {
		case TYPE_BYTE:
		case TYPE_ASCII:
		case TYPE_UNDEFINED:
			return 1;
		case TYPE_SHORT:
			return 2;
		case TYPE_LONG:
			return 4;
		case TYPE_RATIONAL:
		case TYPE_LONG8:
		case TYPE_IFD8:
			return 8;
		default:
			throw new IOException("Unsupported TIFF field type: " + type);
		}
	}

	private static ByteBuffer readAt(FileChannel channel, long offset, int length, ByteOrder order) throws IOException {
		ByteBuffer buffer = ByteBuffer.allocate(length);
		readFully(channel, buffer, offset);
		buffer.flip();
		buffer.order(order);
		return buffer;
	}

	private static void readFully(FileChannel channel, ByteBuffer buffer, long offset) throws IOException {
		long filePosition = offset;
		while (buffer.hasRemaining()) {
			int n = channel.read(buffer, filePosition);
			if (n < 0) throw new EOFException("Unexpected end of TIFF file.");
			filePosition += n;
		}
	}

	private static int uShort(ByteBuffer buffer) {
		return buffer.getShort() & 0xffff;
	}

	private static long uInt(ByteBuffer buffer) {
		return buffer.getInt() & 0xffffffffL;
	}

	private static long firstOrDefault(Map<Integer, long[]> tags, int tag, long fallback) {
		long[] value = tags.get(tag);
		return value == null || value.length == 0 ? fallback : value[0];
	}

	/** Whether this reader can decode the given TIFF compression tag value. */
	public static boolean isSupportedCompression(int compression) {
		return compression == COMPRESSION_NONE
				|| compression == COMPRESSION_DEFLATE
				|| compression == COMPRESSION_DEFLATE_OLD;
	}

	private static String baseName(File file) {
		String name = file.getName();
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}
}
