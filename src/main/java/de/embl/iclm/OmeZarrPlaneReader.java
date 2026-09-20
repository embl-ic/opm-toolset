package de.embl.iclm;

import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.io.Closeable;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.zarr.N5ZarrReader;

/**
 * Reads one XY plane, or one rectangle of one XY plane, from a canonical OPM Zarr v2 array.
 *
 * <p>The rectangle is the point. A plane is stored as a grid of 512 x 512 chunks, each its own
 * gzip stream, so reading a small box out of the middle of a large plane should cost the one or
 * two chunks it touches. Reading every chunk of the plane and throwing most of it away is what
 * made browsing a cropped view of a production dataset slow: a 64 x 64 box in a 2048 x 1024
 * plane decompressed eight chunks and copied two whole planes to produce 8 KB.
 *
 * <p>Callers ask for a window in this array's own coordinates. Turning a region of a
 * <em>view</em> into one is {@link OmeZarrView}'s job, and it only does so where the mapping is
 * exact - a plain read, a side-by-side half, a mirrored half - because pushing the crop through
 * a rotation would quietly take the region from the wrong place.
 */
final class OmeZarrPlaneReader implements Closeable {

	/**
	 * Cache budget in bytes rather than a plane count.
	 * <p>
	 * Eight planes was the budget when every read was a whole plane. Once a read can be a
	 * 64 x 64 box, eight of them is 64 KB and a browse through a cropped volume caches almost
	 * nothing; measured in bytes the same budget holds thousands of them.
	 */
	private static final long CACHE_BYTES = 64L * 1024 * 1024;

	private final N5Reader reader;
	private final String datasetPath;
	private final DatasetAttributes attributes;
	private long[] dimensions;
	private final int[] blockSize;
	/** Access ordered, so the first entry is always the least recently used. */
	private final Map<PlaneKey, short[]> cache =
			new LinkedHashMap<PlaneKey, short[]>(16, 0.75f, true);
	private long cachedBytes;
	/** Chunks actually decompressed, so a test can show the region read only what it needed. */
	private long blocksRead;

	OmeZarrPlaneReader(OmeZarrDataset dataset, String datasetPath) {
		this.reader = new N5ZarrReader(dataset.getRoot().getAbsolutePath());
		this.datasetPath = datasetPath;
		this.attributes = reader.getDatasetAttributes(datasetPath);
		if (attributes == null) throw new IllegalArgumentException("Missing Zarr array: " + datasetPath);
		if (attributes.getDataType() != DataType.UINT16)
			throw new IllegalArgumentException("Only uint16 OPM arrays are supported, got "
					+ attributes.getDataType() + " for " + datasetPath);
		this.dimensions = attributes.getDimensions().clone();
		this.blockSize = attributes.getBlockSize().clone();
		if (dimensions.length < 2) throw new IllegalArgumentException("Array is not at least XY: " + datasetPath);
		if (dimensions[0] > Integer.MAX_VALUE || dimensions[1] > Integer.MAX_VALUE)
			throw new IllegalArgumentException("Array plane is too large for ImageJ 1.x: " + datasetPath);
	}

	/** A rectangle of one plane, in this array's own X/Y coordinates. */
	static final class Rect {
		final int x;
		final int y;
		final int width;
		final int height;

		Rect(int x, int y, int width, int height) {
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
		}

		@Override public boolean equals(Object other) {
			if (!(other instanceof Rect)) return false;
			Rect that = (Rect) other;
			return x == that.x && y == that.y && width == that.width && height == that.height;
		}

		@Override public int hashCode() {
			return ((x * 31 + y) * 31 + width) * 31 + height;
		}

		@Override public String toString() {
			return "x=" + x + ", y=" + y + ", w=" + width + ", h=" + height;
		}
	}

	/**
	 * Re-read the array extent, which grows while an acquisition is still being written.
	 * <p>
	 * Only the time axis can change: a committed time point is never rewritten, so cached
	 * planes stay valid and are deliberately kept.
	 *
	 * @return					: the time extent now published in .zarray
	 */
	synchronized int refreshTimepoints() {
		DatasetAttributes current = reader.getDatasetAttributes(datasetPath);
		if (current == null) return timepoints();
		long[] updated = current.getDimensions();
		if (updated.length == dimensions.length) dimensions = updated.clone();
		return timepoints();
	}

	/** Committed length of the last axis, which is T for both volumes and projections. */
	synchronized int timepoints() {
		return (int) Math.min(Integer.MAX_VALUE, dimensions[dimensions.length - 1]);
	}

	int width() { return (int) dimensions[0]; }
	int height() { return (int) dimensions[1]; }
	long[] dimensions() { return dimensions.clone(); }

	/** How many chunks this reader has decompressed; for tests, not for behaviour. */
	synchronized long blocksRead() { return blocksRead; }

	/**
	 * Read a whole XY plane. Fixed coordinates correspond to dimensions 2..n in native N5
	 * order: volume {@code z,c,t}; projection {@code c,t}.
	 */
	ImageProcessor readPlane(long... fixedCoordinates) {
		return readPlane(null, fixedCoordinates);
	}

	/**			Read one rectangle of one XY plane
	 * <p>		Only the chunks the rectangle actually intersects are fetched and
	 * 			decompressed. Absent chunks stay background, exactly as for a whole plane, so a
	 * 			region of a partially written dataset behaves the way the whole of one does.
	 *
	 * @param window			: the rectangle wanted, or null for the whole plane; clamped
	 * 							  into the plane rather than refused
	 * @param fixedCoordinates	: dimensions 2..n, in native N5 order
	 */
	synchronized ImageProcessor readPlane(Rect window, long... fixedCoordinates) {
		if (fixedCoordinates == null) fixedCoordinates = new long[0];
		if (fixedCoordinates.length != dimensions.length - 2)
			throw new IllegalArgumentException("Expected " + (dimensions.length - 2)
					+ " fixed coordinates for " + datasetPath + ", got " + fixedCoordinates.length);
		for (int d = 0; d < fixedCoordinates.length; d++)
			if (fixedCoordinates[d] < 0 || fixedCoordinates[d] >= dimensions[d + 2])
				throw new IllegalArgumentException("Coordinate " + fixedCoordinates[d]
						+ " is outside dimension " + (d + 2) + " of " + datasetPath);

		Rect clamped = clamp(window);
		PlaneKey key = new PlaneKey(fixedCoordinates, clamped);
		short[] pixels = cache.get(key);
		if (pixels == null) {
			pixels = readPixels(fixedCoordinates, clamped);
			cache.put(key, pixels);
			cachedBytes += pixels.length * 2L;
			evictWhileOverBudget();
		}
		// ImageJ tools may modify the returned processor. Do not let them corrupt the cache.
		return new ShortProcessor(clamped.width, clamped.height, pixels.clone(), null);
	}

	/** Bring a requested window inside the plane; null means the whole of it. */
	private Rect clamp(Rect window) {
		int planeWidth = width();
		int planeHeight = height();
		if (window == null) return new Rect(0, 0, planeWidth, planeHeight);
		int x = Math.max(0, Math.min(window.x, Math.max(0, planeWidth - 1)));
		int y = Math.max(0, Math.min(window.y, Math.max(0, planeHeight - 1)));
		int w = Math.max(1, Math.min(window.width <= 0 ? planeWidth : window.width, planeWidth - x));
		int h = Math.max(1, Math.min(window.height <= 0 ? planeHeight : window.height, planeHeight - y));
		return new Rect(x, y, w, h);
	}

	/** Drop least recently used entries until the cache is inside its byte budget. */
	private void evictWhileOverBudget() {
		Iterator<Map.Entry<PlaneKey, short[]>> entries = cache.entrySet().iterator();
		while (cachedBytes > CACHE_BYTES && cache.size() > 1 && entries.hasNext()) {
			Map.Entry<PlaneKey, short[]> eldest = entries.next();
			cachedBytes -= eldest.getValue().length * 2L;
			entries.remove();
		}
	}

	private short[] readPixels(long[] fixed, Rect window) {
		short[] pixels = new short[window.width * window.height];
		long[] grid = new long[dimensions.length];
		int[] local = new int[dimensions.length];
		for (int d = 2; d < dimensions.length; d++) {
			long coordinate = fixed[d - 2];
			grid[d] = coordinate / blockSize[d];
			local[d] = (int) (coordinate % blockSize[d]);
		}

		/* Only the chunks the window touches. The whole-plane case falls out of this as the
		 * window that covers every chunk, so there is one loop and not two. */
		long firstX = window.x / blockSize[0];
		long lastX = (window.x + window.width - 1) / blockSize[0];
		long firstY = window.y / blockSize[1];
		long lastY = (window.y + window.height - 1) / blockSize[1];
		for (long gy = firstY; gy <= lastY; gy++) {
			grid[1] = gy;
			for (long gx = firstX; gx <= lastX; gx++) {
				grid[0] = gx;
				DataBlock<?> dataBlock = reader.readBlock(datasetPath, attributes, grid);
				blocksRead++;
				if (dataBlock == null) continue; // absent chunks in a partial dataset are background
				Object raw = dataBlock.getData();
				if (!(raw instanceof short[]))
					throw new IllegalStateException("Expected uint16 block data in " + datasetPath);
				copyBlock((short[]) raw, dataBlock.getSize(), blockSize, local, gx, gy, pixels, window);
			}
		}
		return pixels;
	}

	/** Copy the part of one chunk that falls inside the window, into window coordinates. */
	private static void copyBlock(short[] block, int[] size, int[] nominalBlockSize, int[] local,
			long gx, long gy, short[] target, Rect window) {
		int originX = (int) (gx * nominalBlockSize[0]);
		int originY = (int) (gy * nominalBlockSize[1]);
		int fromX = Math.max(window.x, originX);
		int toX = Math.min(window.x + window.width, originX + size[0]);
		int fromY = Math.max(window.y, originY);
		int toY = Math.min(window.y + window.height, originY + size[1]);
		if (toX <= fromX || toY <= fromY) return;

		int higherOffset = 0;
		int stride = size[0] * size[1];
		for (int d = 2; d < size.length; d++) {
			if (local[d] >= size[d]) return;
			higherOffset += local[d] * stride;
			stride *= size[d];
		}
		int run = toX - fromX;
		for (int y = fromY; y < toY; y++) {
			int sourceOffset = higherOffset + (y - originY) * size[0] + (fromX - originX);
			int targetOffset = (y - window.y) * window.width + (fromX - window.x);
			System.arraycopy(block, sourceOffset, target, targetOffset, run);
		}
	}

	@Override
	public synchronized void close() throws IOException {
		cache.clear();
		cachedBytes = 0;
		reader.close();
	}

	private static final class PlaneKey {
		private final long[] coordinates;
		private final Rect window;
		private final int hash;
		PlaneKey(long[] coordinates, Rect window) {
			this.coordinates = coordinates.clone();
			this.window = window;
			/* The window is part of the identity. Without it a cached box would be handed back
			 * as though it were the whole plane the next caller asked for. */
			this.hash = java.util.Arrays.hashCode(this.coordinates) * 31 + window.hashCode();
		}
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object other) {
			return other instanceof PlaneKey
					&& java.util.Arrays.equals(coordinates, ((PlaneKey) other).coordinates)
					&& window.equals(((PlaneKey) other).window);
		}
	}
}
