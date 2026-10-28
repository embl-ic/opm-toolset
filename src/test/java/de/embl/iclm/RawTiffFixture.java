package de.embl.iclm;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

/**
 * Writes small raw acquisition BigTIFFs laid out the way the OPM camera software writes them, for
 * the Online Deskew View's tests: uncompressed little-endian 16-bit, each plane's pixels followed
 * by its strip tables and its IFD, in strips of {@code rowsPerStrip} rows. The real files are
 * 3200 x 500 x 451 with 8-row strips; these are a few kilobytes.
 *
 * <p>{@code writtenPlanes < depth} writes a volume caught mid-acquisition: the first planes and
 * their IFDs, the last IFD's next pointer terminated - a file that is structurally valid but
 * short, which is exactly what a quiet, valid-looking file in a live folder can be.
 */
final class RawTiffFixture {

	interface Pixels {
		int at(int x, int y, int k);
	}

	/** A value that differs per voxel and fits 16 bits. */
	static final Pixels RAMP = (x, y, k) -> (x * 7 + y * 131 + k * 1009) & 0xffff;

	private RawTiffFixture() {}

	static File write(File file, int width, int height, int depth, int rowsPerStrip, int writtenPlanes, Pixels pixels)
			throws IOException {
		int strips = (height + rowsPerStrip - 1) / rowsPerStrip;
		final int tags = 9;
		try (FileChannel out = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
				StandardOpenOption.TRUNCATE_EXISTING)) {
			ByteBuffer header = le(16);
			header.put((byte) 'I').put((byte) 'I').putShort((short) 43).putShort((short) 8).putShort((short) 0).putLong(0);
			write(out, header, 0);
			long position = 16, previousNext = 8;		// where the pointer to the next IFD is written
			for (int k = 0; k < writtenPlanes; k++) {
				long data = position;
				ByteBuffer plane = le(2 * width * height);
				for (int y = 0; y < height; y++)
					for (int x = 0; x < width; x++) plane.putShort((short) pixels.at(x, y, k));
				write(out, plane, data);
				position += plane.capacity();

				long offsetsAt = 0, countsAt = 0;
				if (strips > 1) {
					offsetsAt = position;
					ByteBuffer offsets = le(8 * strips), counts = le(8 * strips);
					for (int s = 0; s < strips; s++) {
						int rows = Math.min(rowsPerStrip, height - s * rowsPerStrip);
						offsets.putLong(data + 2L * s * rowsPerStrip * width);
						counts.putLong(2L * rows * width);
					}
					write(out, offsets, offsetsAt);
					countsAt = offsetsAt + offsets.capacity();
					write(out, counts, countsAt);
					position = countsAt + counts.capacity();
				}

				long ifd = position;
				ByteBuffer entries = le(8 + 20 * tags + 8);
				entries.putLong(tags);
				entry(entries, 256, 4, 1, width);
				entry(entries, 257, 4, 1, height);
				entry(entries, 258, 3, 1, 16);
				entry(entries, 259, 3, 1, 1);
				entry(entries, 262, 3, 1, 1);
				entry(entries, 273, 16, strips, strips > 1 ? offsetsAt : data);
				entry(entries, 277, 3, 1, 1);
				entry(entries, 278, 4, 1, rowsPerStrip);
				entry(entries, 279, 16, strips, strips > 1 ? countsAt : 2L * width * height);
				entries.putLong(0);		// the next IFD, patched when one follows
				write(out, entries, ifd);
				position = ifd + entries.capacity();

				ByteBuffer pointer = le(8);
				pointer.putLong(ifd);
				write(out, pointer, previousNext);
				previousNext = ifd + 8 + 20 * tags;
			}
		}
		return file;
	}

	/**
	 * A test producer: one raw BigTIFF written plane by plane under its final name, as the camera software writes it (C2.4).
	 * After every {@link #write} the file is a valid TIFF of the planes written so far - each plane's pixels, strip tables and
	 * IFD are written before the previous IFD's next pointer is patched to it - so a reader at any moment sees a volume caught
	 * mid-acquisition, never a broken one. {@link #close()} ends it, complete or not; an aborted volume is one closed early.
	 */
	static final class Producer implements AutoCloseable {
		private final FileChannel out;
		private final int width, height, rowsPerStrip, strips;
		private final Pixels pixels;
		private long position = 16, previousNext = 8;
		private int written;

		Producer(File file, int width, int height, int rowsPerStrip, Pixels pixels) throws IOException {
			this.width = width;
			this.height = height;
			this.rowsPerStrip = rowsPerStrip;
			this.strips = (height + rowsPerStrip - 1) / rowsPerStrip;
			this.pixels = pixels;
			this.out = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
					StandardOpenOption.TRUNCATE_EXISTING);
			ByteBuffer header = le(16);
			header.put((byte) 'I').put((byte) 'I').putShort((short) 43).putShort((short) 8).putShort((short) 0).putLong(0);
			RawTiffFixture.write(out, header, 0);
		}

		/** Append {@code planes} more planes. */
		Producer write(int planes) throws IOException {
			final int tags = 9;
			for (int p = 0; p < planes; p++, written++) {
				int k = written;
				long data = position;
				ByteBuffer plane = le(2 * width * height);
				for (int y = 0; y < height; y++)
					for (int x = 0; x < width; x++) plane.putShort((short) pixels.at(x, y, k));
				RawTiffFixture.write(out, plane, data);
				position += plane.capacity();
				long offsetsAt = 0, countsAt = 0;
				if (strips > 1) {
					offsetsAt = position;
					ByteBuffer offsets = le(8 * strips), counts = le(8 * strips);
					for (int s = 0; s < strips; s++) {
						int rows = Math.min(rowsPerStrip, height - s * rowsPerStrip);
						offsets.putLong(data + 2L * s * rowsPerStrip * width);
						counts.putLong(2L * rows * width);
					}
					RawTiffFixture.write(out, offsets, offsetsAt);
					countsAt = offsetsAt + offsets.capacity();
					RawTiffFixture.write(out, counts, countsAt);
					position = countsAt + counts.capacity();
				}
				long ifd = position;
				ByteBuffer entries = le(8 + 20 * tags + 8);
				entries.putLong(tags);
				entry(entries, 256, 4, 1, width);
				entry(entries, 257, 4, 1, height);
				entry(entries, 258, 3, 1, 16);
				entry(entries, 259, 3, 1, 1);
				entry(entries, 262, 3, 1, 1);
				entry(entries, 273, 16, strips, strips > 1 ? offsetsAt : data);
				entry(entries, 277, 3, 1, 1);
				entry(entries, 278, 4, 1, rowsPerStrip);
				entry(entries, 279, 16, strips, strips > 1 ? countsAt : 2L * width * height);
				entries.putLong(0);
				RawTiffFixture.write(out, entries, ifd);
				position = ifd + entries.capacity();
				ByteBuffer pointer = le(8);
				pointer.putLong(ifd);
				RawTiffFixture.write(out, pointer, previousNext);		// last: the plane is now part of the file
				previousNext = ifd + 8 + 20 * tags;
			}
			out.force(false);
			return this;
		}

		int written() {
			return written;
		}

		@Override public void close() throws IOException {
			out.close();
		}
	}

	private static void entry(ByteBuffer b, int tag, int type, long count, long value) {
		b.putShort((short) tag).putShort((short) type).putLong(count);
		int at = b.position();
		if (type == 3) b.putShort((short) value);
		else if (type == 4) b.putInt((int) value);
		else b.putLong(value);
		b.position(at + 8);
	}

	private static ByteBuffer le(int bytes) {
		return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
	}

	private static void write(FileChannel out, ByteBuffer b, long position) throws IOException {
		b.flip();
		while (b.hasRemaining()) position += out.write(b, position);
	}
}
