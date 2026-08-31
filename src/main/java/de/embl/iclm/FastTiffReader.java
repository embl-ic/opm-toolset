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

/**
 * Fast path TIFF reader for the OPM acquisition files:
 * uncompressed, 16-bit, single-channel TIFF/BigTIFF stacks stored as strips.
 */
public class FastTiffReader {
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
			if (info.bitsPerSample != 16 || info.samplesPerPixel != 1 || info.compression != 1) {
				throw new IOException(String.format(
						"Fast reader supports only uncompressed 16-bit grayscale. Found bits=%d samples=%d compression=%d",
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
	 * Compatibility overload. Thread selection is deliberately automatic so all
	 * logical processors are used (capped by the number of TIFF planes).
	 */
	public static short[][] readPixelsParallel(final File file, final Info info, int numThreads) throws Exception {
		return readPixelsParallel(file, info);
	}

	public static short[][] readPixelsParallel(final File file, final Info info) throws Exception {
		final int w = info.width;
		final int h = info.height;
		final int d = info.depth();
		final int planeBytes = Math.multiplyExact(w * h, 2);
		final int rowBytes = Math.multiplyExact(w, 2);
		final short[][] volume = new short[d][];

		int threads = Math.max(1, Math.min(logicalProcessorCount(), d));
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		try {
			List<Future<Void>> futures = new ArrayList<Future<Void>>();
			for (int threadIndex = 0; threadIndex < threads; threadIndex++) {
				final int zStart = (int) Math.floor((double) d * threadIndex / threads);
				final int zEnd = (int) Math.floor((double) d * (threadIndex + 1) / threads);
				futures.add(executor.submit(new Callable<Void>() {
					@Override
					public Void call() throws Exception {
						byte[] rawPlane = new byte[planeBytes];
						FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
						try {
							for (int z = zStart; z < zEnd; z++) {
								long[] offsets = info.stripOffsets.get(z);
								long[] counts = info.stripByteCounts.get(z);
								if (offsets.length != counts.length)
									throw new IOException("Strip offset/count mismatch at plane " + z);
								for (int s = 0; s < offsets.length; s++) {
									int byteCount = (int) counts[s];
									int dstOffset = (int) Math.min((long) s * info.rowsPerStrip * rowBytes, planeBytes);
									ByteBuffer dst = ByteBuffer.wrap(rawPlane, dstOffset, byteCount);
									readFully(channel, dst, offsets[s]);
								}
								short[] plane = new short[w * h];
								ByteBuffer.wrap(rawPlane).order(info.byteOrder).asShortBuffer().get(plane);
								volume[z] = plane;
							}
						} finally {
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

	private static String baseName(File file) {
		String name = file.getName();
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}
}
