package de.embl.iclm;

import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.zarr.N5ZarrReader;

/** Reads one XY plane at a time from a canonical OPM Zarr v2 array. */
final class OpmZarrPlaneReader implements Closeable {

	private static final int MAX_CACHED_PLANES = 8;

	private final N5Reader reader;
	private final String datasetPath;
	private final DatasetAttributes attributes;
	private final long[] dimensions;
	private final int[] blockSize;
	private final Map<PlaneKey, short[]> cache = new LinkedHashMap<PlaneKey, short[]>(
			MAX_CACHED_PLANES + 1, 0.75f, true) {
		private static final long serialVersionUID = 1L;
		@Override
		protected boolean removeEldestEntry(Map.Entry<PlaneKey, short[]> eldest) {
			return size() > MAX_CACHED_PLANES;
		}
	};

	OpmZarrPlaneReader(OpmZarrDataset dataset, String datasetPath) {
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

	int width() { return (int) dimensions[0]; }
	int height() { return (int) dimensions[1]; }
	long[] dimensions() { return dimensions.clone(); }

	/**
	 * Read an XY plane. Fixed coordinates correspond to dimensions 2..n in native N5 order:
	 * volume {@code z,c,t}; projection {@code c,t}.
	 */
	synchronized ImageProcessor readPlane(long... fixedCoordinates) {
		if (fixedCoordinates == null) fixedCoordinates = new long[0];
		if (fixedCoordinates.length != dimensions.length - 2)
			throw new IllegalArgumentException("Expected " + (dimensions.length - 2)
					+ " fixed coordinates for " + datasetPath + ", got " + fixedCoordinates.length);
		for (int d = 0; d < fixedCoordinates.length; d++)
			if (fixedCoordinates[d] < 0 || fixedCoordinates[d] >= dimensions[d + 2])
				throw new IllegalArgumentException("Coordinate " + fixedCoordinates[d]
						+ " is outside dimension " + (d + 2) + " of " + datasetPath);

		PlaneKey key = new PlaneKey(fixedCoordinates);
		short[] pixels = cache.get(key);
		if (pixels == null) {
			pixels = readPixels(fixedCoordinates);
			cache.put(key, pixels);
		}
		// ImageJ tools may modify the returned processor. Do not let them corrupt the cache.
		return new ShortProcessor(width(), height(), pixels.clone(), null);
	}

	private short[] readPixels(long[] fixed) {
		int width = width();
		int height = height();
		short[] pixels = new short[width * height];
		long[] grid = new long[dimensions.length];
		int[] local = new int[dimensions.length];
		for (int d = 2; d < dimensions.length; d++) {
			long coordinate = fixed[d - 2];
			grid[d] = coordinate / blockSize[d];
			local[d] = (int) (coordinate % blockSize[d]);
		}

		long xBlocks = (dimensions[0] + blockSize[0] - 1) / blockSize[0];
		long yBlocks = (dimensions[1] + blockSize[1] - 1) / blockSize[1];
		for (long gy = 0; gy < yBlocks; gy++) {
			grid[1] = gy;
			for (long gx = 0; gx < xBlocks; gx++) {
				grid[0] = gx;
				DataBlock<?> dataBlock = reader.readBlock(datasetPath, attributes, grid);
				if (dataBlock == null) continue; // absent chunks in a partial dataset are background
				Object raw = dataBlock.getData();
				if (!(raw instanceof short[]))
					throw new IllegalStateException("Expected uint16 block data in " + datasetPath);
				copyBlock((short[]) raw, dataBlock.getSize(), blockSize, local, gx, gy, pixels, width, height);
			}
		}
		return pixels;
	}

	private static void copyBlock(short[] block, int[] size, int[] nominalBlockSize, int[] local,
			long gx, long gy, short[] target, int width, int height) {
		int originX = (int) (gx * nominalBlockSize[0]);
		int originY = (int) (gy * nominalBlockSize[1]);
		int copyWidth = Math.max(0, Math.min(size[0], width - originX));
		int copyHeight = Math.max(0, Math.min(size[1], height - originY));
		if (copyWidth == 0 || copyHeight == 0) return;

		int higherOffset = 0;
		int stride = size[0] * size[1];
		for (int d = 2; d < size.length; d++) {
			if (local[d] >= size[d]) return;
			higherOffset += local[d] * stride;
			stride *= size[d];
		}
		for (int y = 0; y < copyHeight; y++) {
			int sourceOffset = higherOffset + y * size[0];
			int targetOffset = (originY + y) * width + originX;
			System.arraycopy(block, sourceOffset, target, targetOffset, copyWidth);
		}
	}

	@Override
	public synchronized void close() throws IOException {
		cache.clear();
		reader.close();
	}

	private static final class PlaneKey {
		private final long[] coordinates;
		private final int hash;
		PlaneKey(long[] coordinates) {
			this.coordinates = coordinates.clone();
			this.hash = java.util.Arrays.hashCode(this.coordinates);
		}
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object other) {
			return other instanceof PlaneKey &&
					java.util.Arrays.equals(coordinates, ((PlaneKey) other).coordinates);
		}
	}
}
