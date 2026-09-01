package de.embl.iclm;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Writes an OPM dataset as OME-Zarr: the deskewed volume, its projections, and the record
 * of how it was made.
 *
 * <p>The layout is one group per acquisition, holding
 *
 * <pre>
 *   .zattrs          multiscales + omero, plus the "opm" provenance record
 *   s0               the deskewed volume, [t, c, z, y, x]
 *   projections/
 *     maxX maxY maxZ
 *     meanX meanY meanZ
 * </pre>
 *
 * <p>Two deliberate choices about the data. The channels are stored <strong>unaligned</strong>
 * - the rigid transform measured on a bead acquisition is recorded in the provenance and
 * applied when the data is viewed, so the pixels are resampled once by the deskew rather than
 * twice, and the alignment stays revisable without rewriting anything.
 *
 * <p>And chunks are <strong>thin in Z and wide in XY</strong>. A viewer scrolls through Z, so
 * one displayed plane should cost a handful of chunk reads rather than hundreds: measured on
 * an earlier prototype with 32 x 128 x 128 chunks, an uncached slice of a 3200-wide volume
 * took 845 ms against 26 ms cached, because one plane touched some 375 compressed chunks.
 *
 * <p>Zarr v2 is written directly here rather than through n5-zarr. n5-zarr 2.0.0's writer
 * reaches for {@code org.janelia.saalfeldlab.n5.DefaultBlockReader}, which exists in n5 3.5.1
 * and was removed in n5 4.0.0 - and 4.0.0 is what Fiji ships, so that writer throws inside
 * Fiji whatever version this project pins. Reading is unaffected and still uses n5.
 *
 * @see OpmProvenance for what is recorded
 */
public class OpmZarrWriter {

	/** Chunk edge in X and Y; Z is always one plane. */
	public static final int CHUNK_XY = 512;
	/** gzip level 1, for the same reason the TIFF writer uses Deflate 1: almost all the ratio, a fraction of the time. */
	private static final int GZIP_LEVEL = 1;

	private final File root;
	private final Map<String, long[]> shapes = new LinkedHashMap<String, long[]>();
	private final Map<String, int[]> chunks = new LinkedHashMap<String, int[]>();

	public OpmZarrWriter (
			File zarrRoot
			) throws IOException {
		this.root = zarrRoot;
		if (!root.isDirectory() && !root.mkdirs())
			throw new IOException ( "Could not create " + root );
		writeText ( new File(root, ".zgroup"), "{\"zarr_format\": 2}" );
	}

	public void close () { }

	/** Whether a dataset has been created here yet. */
	public boolean hasDataset (
			String path
			) {
		return shapes.containsKey ( path );
	}


	/**			Create the volume dataset, sized for the whole time-lapse
	 *
	 * @param sizeX				: deskewed width
	 * @param sizeY				: deskewed height
	 * @param sizeZ				: deskewed depth
	 * @param channels			: one per camera half per acquisition channel
	 * @param timePoints		: number of time points
	 */
	public void createVolume (
			int sizeX, int sizeY, int sizeZ, int channels, int timePoints
			) throws IOException {
		createDataset ( "s0",
				new long[] { timePoints, channels, sizeZ, sizeY, sizeX },
				new int[] { 1, 1, 1, Math.min(CHUNK_XY, sizeY), Math.min(CHUNK_XY, sizeX) } );
	}

	/** Create a 4D projection dataset, [t, c, y, x]. */
	public void createProjection (
			String name, int sizeX, int sizeY, int channels, int timePoints
			) throws IOException {
		new File ( root, "projections" ).mkdirs();
		writeText ( new File(root, "projections/.zgroup"), "{\"zarr_format\": 2}" );
		createDataset ( "projections/" + name,
				new long[] { timePoints, channels, sizeY, sizeX },
				new int[] { 1, 1, Math.min(CHUNK_XY, sizeY), Math.min(CHUNK_XY, sizeX) } );
	}

	private void createDataset (
			String path, long[] shape, int[] chunk
			) throws IOException {
		File dir = new File ( root, path );
		if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException ( "Could not create " + dir );
		shapes.put ( path, shape );
		chunks.put ( path, chunk );

		StringBuilder sb = new StringBuilder("{\n");
		sb.append("  \"zarr_format\": 2,\n");
		sb.append("  \"shape\": ").append(json(shape)).append(",\n");
		sb.append("  \"chunks\": ").append(json(chunk)).append(",\n");
		sb.append("  \"dtype\": \"<u2\",\n");
		sb.append("  \"compressor\": {\"id\": \"gzip\", \"level\": ").append(GZIP_LEVEL).append("},\n");
		sb.append("  \"fill_value\": 0,\n");
		sb.append("  \"order\": \"C\",\n");
		sb.append("  \"filters\": null,\n");
		sb.append("  \"dimension_separator\": \".\"\n}\n");
		writeText ( new File(dir, ".zarray"), sb.toString() );
	}


	/**			Write one deskewed half as one channel of one time point
	 * <p>		Written plane by plane and chunk by chunk, so peak memory stays at one chunk
	 * 			rather than one volume.
	 *
	 * @param imp				: the deskewed half, an XY-Z stack
	 * @param channel			: channel index, 0 based
	 * @param timePoint			: time point index, 0 based
	 */
	public void writeVolumeChannel (
			ImagePlus imp,
			int channel,
			int timePoint
			) throws IOException {
		String path = "s0";
		int[] chunk = chunks.get ( path );
		int w = imp.getWidth(), h = imp.getHeight(), d = imp.getStackSize();
		for (int z = 0; z < d; z++) {
			ImageProcessor ip = imp.getStack().getProcessor ( z + 1 );
			writePlaneChunks ( path, ip, w, h, chunk[4], chunk[3],
					new long[] { timePoint, channel, z, 0, 0 }, 3 );
		}
	}

	/** Write one 2D projection as one channel of one time point. */
	public void writeProjection (
			ImagePlus imp,
			String name,
			int channel,
			int timePoint
			) throws IOException {
		String path = "projections/" + name;
		int[] chunk = chunks.get ( path );
		writePlaneChunks ( path, imp.getProcessor(), imp.getWidth(), imp.getHeight(),
				chunk[3], chunk[2], new long[] { timePoint, channel, 0, 0 }, 2 );
	}

	/**			Tile one plane into chunks and write each one
	 *
	 * @param index				: the chunk index, with the y and x entries filled in here
	 * @param yAxis				: position of the y axis in index, x is the next one
	 */
	private void writePlaneChunks (
			String path, ImageProcessor ip, int w, int h, int chunkX, int chunkY,
			long[] index, int yAxis
			) throws IOException {
		int chunkPixels = chunkX * chunkY;
		for (int y0 = 0; y0 < h; y0 += chunkY) {
			for (int x0 = 0; x0 < w; x0 += chunkX) {
				ByteBuffer buffer = ByteBuffer.allocate ( chunkPixels * 2 ).order ( ByteOrder.LITTLE_ENDIAN );
				for (int dy = 0; dy < chunkY; dy++) {
					int y = y0 + dy;
					for (int dx = 0; dx < chunkX; dx++) {
						int x = x0 + dx;
						// chunks at the edge are padded with the fill value, as zarr requires
						buffer.putShort ( (x < w && y < h) ? (short) ip.get(x, y) : 0 );
					}
				}
				index[yAxis] = y0 / chunkY;
				index[yAxis + 1] = x0 / chunkX;
				writeChunk ( path, index, buffer.array() );
			}
		}
	}

	private void writeChunk (
			String path, long[] index, byte[] raw
			) throws IOException {
		StringBuilder name = new StringBuilder();
		for (int i = 0; i < index.length; i++) {
			if (i > 0) name.append('.');
			name.append(index[i]);
		}
		File file = new File ( new File(root, path), name.toString() );
		// the anonymous subclass is the only way to set the deflate level on a GZIPOutputStream
		GZIPOutputStream gz = new GZIPOutputStream (
				new BufferedOutputStream ( new FileOutputStream(file), 1 << 16 ), 1 << 16 ) {
			{ def.setLevel ( GZIP_LEVEL ); }
		};
		try {
			gz.write ( raw );
			gz.finish();
		} finally {
			gz.close();
		}
	}


	/**			Write the OME-NGFF metadata and the provenance record
	 * <p>		The multiscales block is standard 0.4, which every reader understands. The
	 * 			provenance goes in its own key, which they ignore.
	 */
	public void writeMetadata (
			OpmProvenance provenance,
			List<String> projectionNames
			) throws IOException {
		double[] voxel = provenance.deskewedVoxelSizeUm;

		JsonObject multiscale = new JsonObject();
		multiscale.addProperty ( "version", "0.4" );
		multiscale.addProperty ( "name", provenance.datasetName );
		multiscale.add ( "axes", axes() );
		JsonObject dataset = new JsonObject();
		dataset.addProperty ( "path", "s0" );
		dataset.add ( "coordinateTransformations", scale ( new double[] {
				Math.max ( 1.0, provenance.frameIntervalSeconds ), 1, voxel[2], voxel[1], voxel[0] } ) );
		JsonArray datasets = new JsonArray();
		datasets.add ( dataset );
		multiscale.add ( "datasets", datasets );
		JsonArray multiscales = new JsonArray();
		multiscales.add ( multiscale );

		JsonObject attrs = new JsonObject();
		attrs.add ( "multiscales", multiscales );
		attrs.add ( "omero", omero ( provenance.channelLabels ) );
		attrs.add ( OpmProvenance.KEY, provenance.toJson() );

		JsonArray names = new JsonArray();
		for (String n : projectionNames) names.add ( new JsonPrimitive(n) );
		JsonObject index = new JsonObject();
		index.add ( "available", names );
		attrs.add ( "opm_projections", index );

		writeText ( new File(root, ".zattrs"),
				new GsonBuilder().setPrettyPrinting().create().toJson ( attrs ) );
	}

	private static JsonArray axes () {
		JsonArray axes = new JsonArray();
		axes.add ( axis("t", "time", "second") );
		axes.add ( axis("c", "channel", null) );
		axes.add ( axis("z", "space", "micrometer") );
		axes.add ( axis("y", "space", "micrometer") );
		axes.add ( axis("x", "space", "micrometer") );
		return axes;
	}

	private static JsonObject axis (String name, String type, String unit) {
		JsonObject a = new JsonObject();
		a.addProperty ( "name", name );
		a.addProperty ( "type", type );
		if (unit != null) a.addProperty ( "unit", unit );
		return a;
	}

	private static JsonArray scale (double[] values) {
		JsonArray scale = new JsonArray();
		for (double v : values) scale.add ( new JsonPrimitive(v) );
		JsonObject transform = new JsonObject();
		transform.addProperty ( "type", "scale" );
		transform.add ( "scale", scale );
		JsonArray list = new JsonArray();
		list.add ( transform );
		return list;
	}

	private static JsonObject omero (List<String> labels) {
		String[] colors = { "FF0000", "00FF00", "0000FF", "FFFF00", "FF00FF", "00FFFF" };
		JsonArray channels = new JsonArray();
		List<String> names = labels == null ? new ArrayList<String>() : labels;
		for (int i = 0; i < names.size(); i++) {
			JsonObject c = new JsonObject();
			c.addProperty ( "label", names.get(i) );
			c.addProperty ( "color", colors[i % colors.length] );
			c.addProperty ( "active", true );
			JsonObject window = new JsonObject();
			window.addProperty ( "start", 0 );
			window.addProperty ( "end", 65535 );
			c.add ( "window", window );
			channels.add ( c );
		}
		JsonObject omero = new JsonObject();
		omero.add ( "channels", channels );
		return omero;
	}

	private static String json (long[] values) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < values.length; i++) { if (i > 0) sb.append(", "); sb.append(values[i]); }
		return sb.append(']').toString();
	}

	private static String json (int[] values) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < values.length; i++) { if (i > 0) sb.append(", "); sb.append(values[i]); }
		return sb.append(']').toString();
	}

	private static void writeText (File file, String text) throws IOException {
		Writer writer = new OutputStreamWriter ( new FileOutputStream(file), "UTF-8" );
		try {
			writer.write ( text );
		} finally {
			writer.close();
		}
	}
}
