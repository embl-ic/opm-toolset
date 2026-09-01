package de.embl.iclm;

import ij.ImagePlus;
import ij.ImageStack;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * @deprecated This was a non-NGFF per-file prototype. New code must use
 * {@link OpmZarrConverter} or {@link OpmZarrSession}.
 */
@Deprecated
public class MinimalOmeZarrWriter {
	private final File root;
	private final File arrayDir;
	private final int sizeT;
	private final int sizeZ;
	private final int sizeY;
	private final int sizeX;
	private final int chunkZ;
	private final int chunkY;
	private final int chunkX;
	private final double pixelSizeUm;

	public MinimalOmeZarrWriter(File root, int sizeT, int sizeZ, int sizeY, int sizeX,
			int chunkZ, int chunkY, int chunkX, double pixelSizeUm) {
		this.root = root;
		this.arrayDir = new File(root, "0");
		this.sizeT = sizeT;
		this.sizeZ = sizeZ;
		this.sizeY = sizeY;
		this.sizeX = sizeX;
		this.chunkZ = chunkZ;
		this.chunkY = chunkY;
		this.chunkX = chunkX;
		this.pixelSizeUm = pixelSizeUm;
	}

	public void initialize() throws IOException {
		root.mkdirs();
		arrayDir.mkdirs();
		writeText(new File(root, ".zgroup"), "{\n  \"zarr_format\": 2\n}\n");
		writeText(new File(root, ".zattrs"),
				"{\n" +
				"  \"multiscales\": [{\n" +
				"    \"version\": \"0.4\",\n" +
				"    \"name\": \"OPM deskewed\",\n" +
				"    \"axes\": [\n" +
				"      {\"name\":\"t\", \"type\":\"time\"},\n" +
				"      {\"name\":\"c\", \"type\":\"channel\"},\n" +
				"      {\"name\":\"z\", \"type\":\"space\", \"unit\":\"micrometer\"},\n" +
				"      {\"name\":\"y\", \"type\":\"space\", \"unit\":\"micrometer\"},\n" +
				"      {\"name\":\"x\", \"type\":\"space\", \"unit\":\"micrometer\"}\n" +
				"    ],\n" +
				"    \"datasets\": [{\"path\":\"0\", \"coordinateTransformations\":[{\"type\":\"scale\", \"scale\":[1.0,1.0," +
				pixelSizeUm + "," + pixelSizeUm + "," + pixelSizeUm + "]}]}]\n" +
				"  }],\n" +
				"  \"omero\": {\"channels\": [{\"label\":\"Channel0001\", \"color\":\"FFFFFF\", \"active\":true, \"window\":{\"start\":0, \"end\":65535}}]}\n" +
				"}\n");
		writeText(new File(arrayDir, ".zarray"),
				"{\n" +
				"  \"zarr_format\": 2,\n" +
				"  \"shape\": [" + sizeT + ",1," + sizeZ + "," + sizeY + "," + sizeX + "],\n" +
				"  \"chunks\": [1,1," + chunkZ + "," + chunkY + "," + chunkX + "],\n" +
				"  \"dtype\": \"<u2\",\n" +
				"  \"compressor\": null,\n" +
				"  \"fill_value\": 0,\n" +
				"  \"order\": \"C\",\n" +
				"  \"filters\": null,\n" +
				"  \"dimension_separator\": \".\"\n" +
				"}\n");
		writeText(new File(arrayDir, ".zattrs"),
				"{\n  \"_ARRAY_DIMENSIONS\": [\"t\", \"c\", \"z\", \"y\", \"x\"]\n}\n");
	}

	public void writeTimepoint(ImagePlus imp, int tIndex) throws IOException {
		if (imp.getWidth() != sizeX || imp.getHeight() != sizeY || imp.getStackSize() != sizeZ) {
			throw new IOException(String.format("OME-Zarr writer expected %dx%dx%d, got %dx%dx%d",
					sizeX, sizeY, sizeZ, imp.getWidth(), imp.getHeight(), imp.getStackSize()));
		}
		ImageStack stack = imp.getStack();
		for (int z0 = 0; z0 < sizeZ; z0 += chunkZ) {
			int zc = Math.min(chunkZ, sizeZ - z0);
			for (int y0 = 0; y0 < sizeY; y0 += chunkY) {
				int yc = Math.min(chunkY, sizeY - y0);
				for (int x0 = 0; x0 < sizeX; x0 += chunkX) {
					int xc = Math.min(chunkX, sizeX - x0);
					byte[] bytes = new byte[chunkZ * chunkY * chunkX * 2];
					ByteBuffer out = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

					for (int dz = 0; dz < chunkZ; dz++) {
						if (dz >= zc) {
							out.position((dz + 1) * chunkY * chunkX * 2);
							continue;
						}
						short[] plane = (short[]) stack.getProcessor(z0 + dz + 1).getPixels();
						for (int dy = 0; dy < chunkY; dy++) {
							if (dy < yc) {
								int row = (y0 + dy) * sizeX + x0;
								for (int dx = 0; dx < chunkX; dx++)
									out.putShort(dx < xc ? plane[row + dx] : (short) 0);
							} else {
								out.position(out.position() + chunkX * 2);
							}
						}
					}

					File chunk = new File(arrayDir, String.format("%d.0.%d.%d.%d",
							tIndex, z0 / chunkZ, y0 / chunkY, x0 / chunkX));
					FileOutputStream fos = new FileOutputStream(chunk);
					try {
						fos.write(bytes);
					} finally {
						fos.close();
					}
				}
			}
		}
	}

	public static void writeFileResult(File root, ImagePlus deskew, ImagePlus maxX, ImagePlus maxY, ImagePlus maxZ,
			double pixelSizeUm, int chunkZ, int chunkY, int chunkX) throws IOException {
		root.mkdirs();
		writeText(new File(root, ".zgroup"), "{\n  \"zarr_format\": 2\n}\n");
		writeText(new File(root, ".zattrs"),
				"{\n" +
				"  \"opm_toolset\": \"Deskew Batch2\",\n" +
				"  \"contents\": [\"deskew\", \"maxX\", \"maxY\", \"maxZ\"],\n" +
				"  \"note\": \"Single-resolution per-file OPM result bundle\"\n" +
				"}\n");
		if (deskew != null)
			writeArray(root, "deskew", deskew, pixelSizeUm, pixelSizeUm, pixelSizeUm, chunkZ, chunkY, chunkX);
		if (maxX != null)
			writeArray(root, "maxX", maxX, pixelSizeUm, pixelSizeUm, pixelSizeUm, 1, chunkY, chunkX);
		if (maxY != null)
			writeArray(root, "maxY", maxY, pixelSizeUm, pixelSizeUm, pixelSizeUm, 1, chunkY, chunkX);
		if (maxZ != null)
			writeArray(root, "maxZ", maxZ, pixelSizeUm, pixelSizeUm, pixelSizeUm, 1, chunkY, chunkX);
	}

	private static void writeArray(File root, String name, ImagePlus imp, double pixelZ, double pixelY, double pixelX,
			int chunkZ, int chunkY, int chunkX) throws IOException {
		File arrayDir = new File(root, name);
		arrayDir.mkdirs();
		int sizeZ = imp.getStackSize();
		int sizeY = imp.getHeight();
		int sizeX = imp.getWidth();
		int zChunk = Math.max(1, Math.min(chunkZ, sizeZ));
		int yChunk = Math.max(1, Math.min(chunkY, sizeY));
		int xChunk = Math.max(1, Math.min(chunkX, sizeX));

		writeText(new File(arrayDir, ".zarray"),
				"{\n" +
				"  \"zarr_format\": 2,\n" +
				"  \"shape\": [" + sizeZ + "," + sizeY + "," + sizeX + "],\n" +
				"  \"chunks\": [" + zChunk + "," + yChunk + "," + xChunk + "],\n" +
				"  \"dtype\": \"<u2\",\n" +
				"  \"compressor\": null,\n" +
				"  \"fill_value\": 0,\n" +
				"  \"order\": \"C\",\n" +
				"  \"filters\": null,\n" +
				"  \"dimension_separator\": \".\"\n" +
				"}\n");
		writeText(new File(arrayDir, ".zattrs"),
				"{\n" +
				"  \"_ARRAY_DIMENSIONS\": [\"z\", \"y\", \"x\"],\n" +
				"  \"pixel_size_um\": [" + pixelZ + "," + pixelY + "," + pixelX + "]\n" +
				"}\n");

		ImageStack stack = imp.getStack();
		for (int z0 = 0; z0 < sizeZ; z0 += zChunk) {
			int zc = Math.min(zChunk, sizeZ - z0);
			for (int y0 = 0; y0 < sizeY; y0 += yChunk) {
				int yc = Math.min(yChunk, sizeY - y0);
				for (int x0 = 0; x0 < sizeX; x0 += xChunk) {
					int xc = Math.min(xChunk, sizeX - x0);
					byte[] bytes = new byte[zChunk * yChunk * xChunk * 2];
					ByteBuffer out = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
					for (int dz = 0; dz < zChunk; dz++) {
						if (dz >= zc) {
							out.position((dz + 1) * yChunk * xChunk * 2);
							continue;
						}
						short[] plane = (short[]) stack.getProcessor(z0 + dz + 1).getPixels();
						for (int dy = 0; dy < yChunk; dy++) {
							if (dy < yc) {
								int row = (y0 + dy) * sizeX + x0;
								for (int dx = 0; dx < xChunk; dx++)
									out.putShort(dx < xc ? plane[row + dx] : (short) 0);
							} else {
								out.position(out.position() + xChunk * 2);
							}
						}
					}
					File chunk = new File(arrayDir, String.format("%d.%d.%d", z0 / zChunk, y0 / yChunk, x0 / xChunk));
					FileOutputStream fos = new FileOutputStream(chunk);
					try {
						fos.write(bytes);
					} finally {
						fos.close();
					}
				}
			}
		}
	}

	private static void writeText(File file, String text) throws IOException {
		File parent = file.getParentFile();
		if (parent != null) parent.mkdirs();
		BufferedWriter writer = new BufferedWriter(new FileWriter(file, false));
		try {
			writer.write(text);
		} finally {
			writer.close();
		}
	}
}
