package de.embl.iclm;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.GZIPOutputStream;

/**
 * Direct Zarr-v2 writer for the canonical OPM OME-Zarr layout.
 *
 * <p>The primary array is {@code s0 [t,c,z,y,x]}. Each acquisition-channel file contributes
 * two stored channels (left and right camera halves). The halves have been deskewed, but are
 * deliberately neither mirrored nor channel-aligned. The XY rigid alignment is kept in the
 * {@link OpmProvenance} record and can be changed or applied by a viewer without duplicating
 * the pixels.
 *
 * <p>The writer also implements a small commit protocol for live acquisition. Chunks for the
 * next time point are written while the public array shape still ends at the previous one.
 * After every required volume and projection chunk exists, all array shapes are advanced and
 * {@code opm_write_state.committedTimepoints} is updated last. On restart that committed count
 * is authoritative, so a half-written time point is invisible and can simply be overwritten.
 */
public class OpmZarrWriter {

	/** Chunk edge in X and Y; Z, C and T are always one. */
	public static final int CHUNK_XY = 512;
	/** Fast compression suitable for live acquisition. */
	public static final int GZIP_LEVEL = 1;
	public static final String WRITE_STATE_KEY = "opm_write_state";
	public static final String SUCCESS_FILE = "_SUCCESS";

	private final File root;
	private final Map<String, long[]> shapes = new LinkedHashMap<String, long[]>();
	private final Map<String, int[]> chunks = new LinkedHashMap<String, int[]>();
	private final List<String> committedLabels = new ArrayList<String>();
	private final List<Double> committedElapsedSeconds = new ArrayList<Double>();
	private final List<String> projectionNames = new ArrayList<String>();

	private OpmProvenance provenance;
	private int committedTimepoints;
	private boolean complete;

	public OpmZarrWriter(File zarrRoot) throws IOException {
		if (zarrRoot == null) throw new IllegalArgumentException("The OME-Zarr root is null.");
		root = zarrRoot;
		if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Could not create " + root);
		File group = new File(root, ".zgroup");
		if (!group.isFile()) writeText(group, "{\"zarr_format\": 2}\n");
		loadDatasets(root, "");
		loadRootMetadata();
		recoverPublishedShapes();
	}

	public void close() { }

	public File getRoot() { return root; }

	public synchronized int getCommittedTimepoints() { return committedTimepoints; }

	public synchronized List<String> getCommittedTimePointLabels() {
		return new ArrayList<String>(committedLabels);
	}

	public synchronized OpmProvenance getProvenance() { return provenance; }

	public synchronized boolean isComplete() { return complete; }

	public synchronized boolean isTimePointCommitted(String label) {
		return label != null && committedLabels.contains(label);
	}

	/** Whether an array exists in this group. */
	public synchronized boolean hasDataset(String path) { return shapes.containsKey(path); }

	/** Create a fixed-size volume. Prefer {@link #createAppendableVolume} for recoverable jobs. */
	public synchronized void createVolume(
			int sizeX, int sizeY, int sizeZ, int channels, int timePoints) throws IOException {
		createDataset("s0", new long[] { timePoints, channels, sizeZ, sizeY, sizeX },
				new int[] { 1, 1, 1, Math.min(CHUNK_XY, sizeY), Math.min(CHUNK_XY, sizeX) });
	}

	/** Create or validate a volume whose visible T length is the committed count. */
	public synchronized void createAppendableVolume(
			int sizeX, int sizeY, int sizeZ, int channels) throws IOException {
		createVolume(sizeX, sizeY, sizeZ, channels, committedTimepoints);
	}

	/** Create a fixed-size 4-D projection array. */
	public synchronized void createProjection(
			String name, int sizeX, int sizeY, int channels, int timePoints) throws IOException {
		File group = new File(root, "projections");
		if (!group.isDirectory() && !group.mkdirs()) throw new IOException("Could not create " + group);
		File zgroup = new File(group, ".zgroup");
		if (!zgroup.isFile()) writeText(zgroup, "{\"zarr_format\": 2}\n");
		createDataset("projections/" + name,
				new long[] { timePoints, channels, sizeY, sizeX },
				new int[] { 1, 1, Math.min(CHUNK_XY, sizeY), Math.min(CHUNK_XY, sizeX) });
	}

	/** Create or validate a projection whose visible T length is the committed count. */
	public synchronized void createAppendableProjection(
			String name, int sizeX, int sizeY, int channels) throws IOException {
		createProjection(name, sizeX, sizeY, channels, committedTimepoints);
	}

	private void createDataset(String path, long[] shape, int[] chunk) throws IOException {
		if (shape.length != chunk.length) throw new IllegalArgumentException("Shape/chunk rank differs for " + path);
		if (shapes.containsKey(path)) {
			validateArrayDefinition(path, shape, chunk);
			return;
		}
		File dir = new File(root, path.replace('/', File.separatorChar));
		if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create " + dir);
		shapes.put(path, shape.clone());
		chunks.put(path, chunk.clone());
		writeArrayMetadata(path);
	}

	private void validateArrayDefinition(String path, long[] expectedShape, int[] expectedChunk) throws IOException {
		long[] actualShape = shapes.get(path);
		int[] actualChunk = chunks.get(path);
		if (!Arrays.equals(actualShape, expectedShape) || !Arrays.equals(actualChunk, expectedChunk))
			throw new IOException("Existing OME-Zarr array " + path + " is incompatible. shape="
					+ Arrays.toString(actualShape) + ", chunks=" + Arrays.toString(actualChunk)
					+ "; expected shape=" + Arrays.toString(expectedShape)
					+ ", chunks=" + Arrays.toString(expectedChunk));
	}

	/** Write one deskewed half into a staged or already-sized T position. */
	public synchronized void writeVolumeChannel(ImagePlus imp, int channel, int timePoint) throws IOException {
		long[] shape = requireDataset("s0", 5);
		validateTimeAndChannel("s0", shape, channel, timePoint);
		validateUint16(imp, "volume");
		int w = imp.getWidth(), h = imp.getHeight(), d = imp.getStackSize();
		if (w != shape[4] || h != shape[3] || d != shape[2])
			throw new IOException("Volume dimensions changed at t=" + timePoint + ", c=" + channel
					+ ": got " + w + "x" + h + "x" + d + ", expected "
					+ shape[4] + "x" + shape[3] + "x" + shape[2]);
		final int[] chunk = chunks.get("s0");
		/* Every chunk is an independent gzip stream written to its own file, so the whole
		 * volume can go out in parallel. Serially this dominated the write: one time point of
		 * four 1600x1448x276 channels took 71.9 s against 6.2 s for the equivalent TIFF,
		 * because each of the 3312 chunks per channel is a separate compress, temp-file
		 * create and atomic rename. Processors are fetched up front because ImageStack is
		 * not safe to read from several threads. */
		final ImageProcessor[] planes = new ImageProcessor[d];
		for (int z = 0; z < d; z++) planes[z] = imp.getStack().getProcessor(z + 1);

		final int width = w, height = h;
		final int cTime = timePoint, cChannel = channel;
		runInParallel(d, new PlaneTask() {
			@Override
			public void run(int z) throws IOException {
				writePlaneChunks("s0", planes[z], width, height, chunk[4], chunk[3],
						new long[] { cTime, cChannel, z, 0, 0 }, 3);
			}
		});
	}

	/** One unit of parallel write work: everything belonging to a single Z plane. */
	private interface PlaneTask {
		void run(int plane) throws IOException;
	}

	/**			Run one task per plane across all logical processors
	 * <p>		Falls back to running inline for a single plane, where a pool would cost more
	 * 			than it saves. The first failure is rethrown; the rest are suppressed onto it
	 * 			so a partial write reports what actually went wrong.
	 */
	private void runInParallel(int planes, final PlaneTask task) throws IOException {
		if (planes <= 1) {
			if (planes == 1) task.run(0);
			return;
		}
		int threads = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), planes));
		java.util.concurrent.ExecutorService pool =
				java.util.concurrent.Executors.newFixedThreadPool(threads);
		try {
			List<java.util.concurrent.Future<Void>> futures =
					new ArrayList<java.util.concurrent.Future<Void>>(planes);
			for (int z = 0; z < planes; z++) {
				final int plane = z;
				futures.add(pool.submit(new java.util.concurrent.Callable<Void>() {
					@Override
					public Void call() throws Exception {
						task.run(plane);
						return null;
					}
				}));
			}
			IOException failure = null;
			for (java.util.concurrent.Future<Void> future : futures) {
				try {
					future.get();
				} catch (Exception e) {
					Throwable cause = e.getCause() == null ? e : e.getCause();
					if (failure == null)
						failure = cause instanceof IOException
								? (IOException) cause
								: new IOException("Writing OME-Zarr chunks failed: " + cause, cause);
					else failure.addSuppressed(cause);
				}
			}
			if (failure != null) throw failure;
		} finally {
			pool.shutdownNow();
		}
	}

	/** Write one 2-D projection into a staged or already-sized T position. */
	public synchronized void writeProjection(
			ImagePlus imp, String name, int channel, int timePoint) throws IOException {
		String path = "projections/" + name;
		long[] shape = requireDataset(path, 4);
		validateTimeAndChannel(path, shape, channel, timePoint);
		validateUint16(imp, "projection " + name);
		if (imp.getWidth() != shape[3] || imp.getHeight() != shape[2])
			throw new IOException("Projection dimensions changed for " + name + " at t=" + timePoint
					+ ", c=" + channel + ": got " + imp.getWidth() + "x" + imp.getHeight()
					+ ", expected " + shape[3] + "x" + shape[2]);
		int[] chunk = chunks.get(path);
		writePlaneChunks(path, imp.getProcessor(), imp.getWidth(), imp.getHeight(),
				chunk[3], chunk[2], new long[] { timePoint, channel, 0, 0 }, 2);
	}

	private void validateTimeAndChannel(String path, long[] shape, int channel, int timePoint) {
		if (channel < 0 || channel >= shape[1])
			throw new IndexOutOfBoundsException("Channel " + channel + " outside " + path + " C=" + shape[1]);
		// Exactly one unpublished position may be staged. Gaps would make recovery ambiguous.
		if (timePoint < 0 || timePoint > committedTimepoints)
			throw new IndexOutOfBoundsException("Time point " + timePoint
					+ " is not the committed or next staged position " + committedTimepoints);
	}

	private static void validateUint16(ImagePlus image, String description) throws IOException {
		if (image == null || image.getBitDepth() != 16)
			throw new IOException("OME-Zarr " + description + " must be a readable uint16 image.");
	}

	/**
	 * Publish a fully written time point.
	 *
	 * <p>Every expected chunk is checked first. Array shapes are then advanced and the root
	 * commit count is written last. A failure before that final update is recovered by using
	 * the old commit count when the writer is reopened.
	 */
	public synchronized void commitTimePoint(
			int timePoint, String label, List<String> sourceFiles, double elapsedSeconds) throws IOException {
		if (timePoint != committedTimepoints)
			throw new IOException("Time points must commit sequentially: expected "
					+ committedTimepoints + ", got " + timePoint);
		verifyTimePointChunks(timePoint);
		for (String path : new ArrayList<String>(shapes.keySet())) resizeTimeAxis(path, timePoint + 1L);

		committedTimepoints++;
		committedLabels.add(label == null ? String.format("t%06d", timePoint) : label);
		committedElapsedSeconds.add(Double.valueOf(elapsedSeconds));
		if (provenance != null) {
			provenance.timePointLabels = new ArrayList<String>(committedLabels);
			provenance.timePointElapsedSeconds = new ArrayList<Double>(committedElapsedSeconds);
			if (sourceFiles != null) for (String source : sourceFiles)
				if (source != null && !provenance.sourceFiles.contains(source)) provenance.sourceFiles.add(source);
			writeRootAttributes();
		}
	}

	/** Set metadata and the projection index. Safe to call again while appending. */
	public synchronized void writeMetadata(
			OpmProvenance newProvenance, List<String> newProjectionNames) throws IOException {
		if (newProvenance == null) throw new IllegalArgumentException("OPM provenance is required.");
		provenance = newProvenance;
		provenance.timePointLabels = new ArrayList<String>(committedLabels);
		provenance.timePointElapsedSeconds = new ArrayList<Double>(committedElapsedSeconds);
		projectionNames.clear();
		if (newProjectionNames != null) projectionNames.addAll(newProjectionNames);
		writeRootAttributes();
	}

	/** Reopen a completed dataset for append and remove its success marker. */
	public synchronized void markWriting() throws IOException {
		complete = false;
		Files.deleteIfExists(new File(root, SUCCESS_FILE).toPath());
		if (provenance != null) writeRootAttributes();
	}

	/** Mark a normally closed dataset complete and create its success marker. */
	public synchronized void markComplete() throws IOException {
		complete = true;
		if (provenance != null) writeRootAttributes();
		JsonObject success = new JsonObject();
		success.addProperty("status", "complete");
		success.addProperty("committedTimepoints", committedTimepoints);
		success.addProperty("completedUtc", utcNow());
		writeText(new File(root, SUCCESS_FILE),
				new GsonBuilder().setPrettyPrinting().create().toJson(success) + "\n");
	}

	private long[] requireDataset(String path, int rank) {
		long[] shape = shapes.get(path);
		if (shape == null) throw new IllegalStateException("OME-Zarr array has not been created: " + path);
		if (shape.length != rank) throw new IllegalStateException("Unexpected rank for " + path);
		return shape;
	}

	private void writePlaneChunks(
			String path, ImageProcessor ip, int w, int h, int chunkX, int chunkY,
			long[] index, int yAxis) throws IOException {
		int chunkPixels = chunkX * chunkY;
		for (int y0 = 0; y0 < h; y0 += chunkY) {
			for (int x0 = 0; x0 < w; x0 += chunkX) {
				ByteBuffer buffer = ByteBuffer.allocate(chunkPixels * 2).order(ByteOrder.LITTLE_ENDIAN);
				for (int dy = 0; dy < chunkY; dy++) {
					int y = y0 + dy;
					for (int dx = 0; dx < chunkX; dx++) {
						int x = x0 + dx;
						buffer.putShort((x < w && y < h) ? (short) ip.get(x, y) : 0);
					}
				}
				index[yAxis] = y0 / chunkY;
				index[yAxis + 1] = x0 / chunkX;
				writeChunk(path, index, buffer.array());
			}
		}
	}

	private void writeChunk(String path, long[] index, byte[] raw) throws IOException {
		StringBuilder name = new StringBuilder();
		for (int i = 0; i < index.length; i++) {
			if (i > 0) name.append('.');
			name.append(index[i]);
		}
		File directory = new File(root, path.replace('/', File.separatorChar));
		File target = new File(directory, name.toString());
		Path temporary = Files.createTempFile(directory.toPath(), "." + name + ".", ".partial");
		GZIPOutputStream gzip = new GZIPOutputStream(
				new BufferedOutputStream(new FileOutputStream(temporary.toFile()), 1 << 16), 1 << 16) {
			{ def.setLevel(GZIP_LEVEL); }
		};
		try {
			gzip.write(raw);
			gzip.finish();
		} finally {
			gzip.close();
		}
		moveReplace(temporary, target.toPath());
	}

	private void verifyTimePointChunks(int timePoint) throws IOException {
		if (!shapes.containsKey("s0")) throw new IOException("The s0 volume array is missing.");
		for (String path : shapes.keySet()) {
			long[] shape = shapes.get(path);
			long[] index = new long[shape.length];
			index[0] = timePoint;
			verifyChunkAxis(path, shape, chunks.get(path), index, 1);
		}
	}

	private void verifyChunkAxis(
			String path, long[] shape, int[] chunk, long[] index, int axis) throws IOException {
		if (axis == shape.length) {
			File file = new File(new File(root, path.replace('/', File.separatorChar)), chunkName(index));
			if (!file.isFile() || file.length() == 0) throw new IOException("Missing staged Zarr chunk " + file);
			return;
		}
		long count = (shape[axis] + chunk[axis] - 1L) / chunk[axis];
		for (long i = 0; i < count; i++) {
			index[axis] = i;
			verifyChunkAxis(path, shape, chunk, index, axis + 1);
		}
	}

	private static String chunkName(long[] index) {
		StringBuilder name = new StringBuilder();
		for (int i = 0; i < index.length; i++) {
			if (i > 0) name.append('.');
			name.append(index[i]);
		}
		return name.toString();
	}

	private void resizeTimeAxis(String path, long timePoints) throws IOException {
		long[] shape = shapes.get(path);
		if (shape[0] == timePoints) return;
		shape[0] = timePoints;
		writeArrayMetadata(path);
	}

	private void writeArrayMetadata(String path) throws IOException {
		long[] shape = shapes.get(path);
		int[] chunk = chunks.get(path);
		JsonObject array = new JsonObject();
		array.addProperty("zarr_format", 2);
		array.add("shape", json(shape));
		array.add("chunks", json(chunk));
		array.addProperty("dtype", "<u2");
		JsonObject compressor = new JsonObject();
		compressor.addProperty("id", "gzip");
		compressor.addProperty("level", GZIP_LEVEL);
		array.add("compressor", compressor);
		array.addProperty("fill_value", 0);
		array.addProperty("order", "C");
		// Zarr v2 requires the filters key even when no filters are configured. A Java
		// null may be omitted by Gson, which makes n5-zarr reject the entire array.
		array.add("filters", JsonNull.INSTANCE);
		array.addProperty("dimension_separator", ".");
		File dir = new File(root, path.replace('/', File.separatorChar));
		writeText(new File(dir, ".zarray"),
				new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(array) + "\n");
	}

	private void writeRootAttributes() throws IOException {
		double[] voxel = provenance.deskewedVoxelSizeUm;
		if (voxel == null || voxel.length < 3)
			throw new IOException("Deskewed voxel size is missing from OPM provenance.");
		double timeScale = provenance.frameIntervalSeconds > 0 ? provenance.frameIntervalSeconds : 1.0;

		JsonObject multiscale = new JsonObject();
		multiscale.addProperty("version", "0.4");
		multiscale.addProperty("name", provenance.datasetName);
		multiscale.add("axes", volumeAxes());
		JsonObject dataset = new JsonObject();
		dataset.addProperty("path", "s0");
		dataset.add("coordinateTransformations", scale(new double[] {
				timeScale, 1, voxel[2], voxel[1], voxel[0] }));
		JsonArray datasets = new JsonArray();
		datasets.add(dataset);
		multiscale.add("datasets", datasets);
		JsonArray multiscales = new JsonArray();
		multiscales.add(multiscale);

		JsonObject attrs = new JsonObject();
		attrs.add("multiscales", multiscales);
		attrs.add("omero", omero(provenance.channelLabels));
		attrs.add(OpmProvenance.KEY, provenance.toJson());
		attrs.add("opm_projections", projectionIndex(timeScale, voxel));

		JsonObject state = new JsonObject();
		state.addProperty("status", complete ? "complete" : "writing");
		state.addProperty("committedTimepoints", committedTimepoints);
		state.add("timePointLabels", strings(committedLabels));
		state.add("timePointElapsedSeconds", doubles(committedElapsedSeconds));
		state.addProperty("updatedUtc", utcNow());
		attrs.add(WRITE_STATE_KEY, state);

		writeText(new File(root, ".zattrs"),
				new GsonBuilder().setPrettyPrinting().create().toJson(attrs) + "\n");
	}

	private JsonObject projectionIndex(double timeScale, double[] voxel) {
		JsonObject index = new JsonObject();
		index.add("available", strings(projectionNames));
		JsonArray datasets = new JsonArray();
		for (String name : projectionNames) {
			JsonObject projection = new JsonObject();
			projection.addProperty("name", name);
			projection.addProperty("path", "projections/" + name);
			projection.add("axes", projectionAxes(name));
			projection.add("coordinateTransformations", projectionScale(name, timeScale, voxel));
			datasets.add(projection);
		}
		index.add("datasets", datasets);
		return index;
	}

	private static JsonArray volumeAxes() {
		JsonArray axes = new JsonArray();
		axes.add(axis("t", "time", "second"));
		axes.add(axis("c", "channel", null));
		axes.add(axis("z", "space", "micrometer"));
		axes.add(axis("y", "space", "micrometer"));
		axes.add(axis("x", "space", "micrometer"));
		return axes;
	}

	private static JsonArray projectionAxes(String name) {
		String projected = name.substring(name.length() - 1).toLowerCase();
		String vertical = "y", horizontal = "x";
		if ("x".equals(projected)) { vertical = "y"; horizontal = "z"; }
		else if ("y".equals(projected)) { vertical = "z"; horizontal = "x"; }
		JsonArray axes = new JsonArray();
		axes.add(axis("t", "time", "second"));
		axes.add(axis("c", "channel", null));
		axes.add(axis(vertical, "space", "micrometer"));
		axes.add(axis(horizontal, "space", "micrometer"));
		return axes;
	}

	private static JsonArray projectionScale(String name, double timeScale, double[] voxel) {
		String projected = name.substring(name.length() - 1).toLowerCase();
		double vertical = voxel[1], horizontal = voxel[0];
		if ("x".equals(projected)) { vertical = voxel[1]; horizontal = voxel[2]; }
		else if ("y".equals(projected)) { vertical = voxel[2]; horizontal = voxel[0]; }
		return scale(new double[] { timeScale, 1, vertical, horizontal });
	}

	private static JsonObject axis(String name, String type, String unit) {
		JsonObject axis = new JsonObject();
		axis.addProperty("name", name);
		axis.addProperty("type", type);
		if (unit != null) axis.addProperty("unit", unit);
		return axis;
	}

	private static JsonArray scale(double[] values) {
		JsonObject transform = new JsonObject();
		transform.addProperty("type", "scale");
		transform.add("scale", json(values));
		JsonArray list = new JsonArray();
		list.add(transform);
		return list;
	}

	private static JsonObject omero(List<String> labels) {
		String[] colors = { "FF0000", "00FF00", "0000FF", "FFFF00", "FF00FF", "00FFFF" };
		JsonArray channels = new JsonArray();
		List<String> names = labels == null ? new ArrayList<String>() : labels;
		for (int i = 0; i < names.size(); i++) {
			JsonObject channel = new JsonObject();
			channel.addProperty("label", names.get(i));
			channel.addProperty("color", colors[i % colors.length]);
			channel.addProperty("active", true);
			JsonObject window = new JsonObject();
			window.addProperty("start", 0);
			window.addProperty("end", 65535);
			window.addProperty("min", 0);
			window.addProperty("max", 65535);
			channel.add("window", window);
			channels.add(channel);
		}
		JsonObject omero = new JsonObject();
		omero.add("channels", channels);
		return omero;
	}

	private void loadDatasets(File directory, String relative) throws IOException {
		File arrayFile = new File(directory, ".zarray");
		if (arrayFile.isFile() && !relative.isEmpty()) {
			JsonObject json = readJson(arrayFile);
			shapes.put(relative, longs(json.getAsJsonArray("shape")));
			chunks.put(relative, ints(json.getAsJsonArray("chunks")));
			return;
		}
		File[] children = directory.listFiles();
		if (children == null) return;
		Arrays.sort(children);
		for (File child : children) if (child.isDirectory()) {
			String childPath = relative.isEmpty() ? child.getName() : relative + "/" + child.getName();
			loadDatasets(child, childPath);
		}
	}

	private void loadRootMetadata() throws IOException {
		File attrsFile = new File(root, ".zattrs");
		if (!attrsFile.isFile()) return;
		JsonObject attrs = readJson(attrsFile);
		if (attrs.has(OpmProvenance.KEY) && attrs.get(OpmProvenance.KEY).isJsonObject())
			provenance = OpmProvenance.fromJson(attrs.getAsJsonObject(OpmProvenance.KEY));
		JsonObject projections = attrs.has("opm_projections") && attrs.get("opm_projections").isJsonObject()
				? attrs.getAsJsonObject("opm_projections") : null;
		if (projections != null && projections.has("available"))
			for (JsonElement value : projections.getAsJsonArray("available")) projectionNames.add(value.getAsString());

		JsonObject state = attrs.has(WRITE_STATE_KEY) && attrs.get(WRITE_STATE_KEY).isJsonObject()
				? attrs.getAsJsonObject(WRITE_STATE_KEY) : null;
		if (state != null) {
			committedTimepoints = state.has("committedTimepoints")
					? Math.max(0, state.get("committedTimepoints").getAsInt()) : 0;
			complete = state.has("status") && "complete".equals(state.get("status").getAsString());
			if (state.has("timePointLabels"))
				for (JsonElement value : state.getAsJsonArray("timePointLabels")) committedLabels.add(value.getAsString());
			if (state.has("timePointElapsedSeconds"))
				for (JsonElement value : state.getAsJsonArray("timePointElapsedSeconds"))
					committedElapsedSeconds.add(Double.valueOf(value.getAsDouble()));
		} else if (shapes.containsKey("s0")) {
			committedTimepoints = (int) Math.min(Integer.MAX_VALUE, shapes.get("s0")[0]);
			complete = true;
			if (provenance != null && provenance.timePointLabels != null)
				committedLabels.addAll(provenance.timePointLabels);
		}
		while (committedLabels.size() < committedTimepoints)
			committedLabels.add(String.format("t%06d", committedLabels.size()));
		while (committedElapsedSeconds.size() < committedTimepoints) {
			double interval = provenance == null ? 0 : provenance.frameIntervalSeconds;
			committedElapsedSeconds.add(Double.valueOf(committedElapsedSeconds.size() * interval));
		}
		if (committedLabels.size() > committedTimepoints)
			committedLabels.subList(committedTimepoints, committedLabels.size()).clear();
		if (committedElapsedSeconds.size() > committedTimepoints)
			committedElapsedSeconds.subList(committedTimepoints, committedElapsedSeconds.size()).clear();
	}

	/** Normalize arrays after an interrupted commit; staged chunks remain outside the shape. */
	private void recoverPublishedShapes() throws IOException {
		if (!new File(root, ".zattrs").isFile()) return;
		for (String path : shapes.keySet()) {
			long[] shape = shapes.get(path);
			if (shape.length > 0 && shape[0] != committedTimepoints) resizeTimeAxis(path, committedTimepoints);
		}
	}

	private static JsonObject readJson(File file) throws IOException {
		String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
		return JsonParser.parseString(text).getAsJsonObject();
	}

	private static long[] longs(JsonArray array) {
		long[] values = new long[array.size()];
		for (int i = 0; i < values.length; i++) values[i] = array.get(i).getAsLong();
		return values;
	}

	private static int[] ints(JsonArray array) {
		int[] values = new int[array.size()];
		for (int i = 0; i < values.length; i++) values[i] = array.get(i).getAsInt();
		return values;
	}

	private static JsonArray json(long[] values) {
		JsonArray array = new JsonArray();
		for (long value : values) array.add(new JsonPrimitive(value));
		return array;
	}

	private static JsonArray json(int[] values) {
		JsonArray array = new JsonArray();
		for (int value : values) array.add(new JsonPrimitive(value));
		return array;
	}

	private static JsonArray json(double[] values) {
		JsonArray array = new JsonArray();
		for (double value : values) array.add(new JsonPrimitive(value));
		return array;
	}

	private static JsonArray strings(List<String> values) {
		JsonArray array = new JsonArray();
		if (values != null) for (String value : values) array.add(new JsonPrimitive(value));
		return array;
	}

	private static JsonArray doubles(List<Double> values) {
		JsonArray array = new JsonArray();
		if (values != null) for (Double value : values) array.add(new JsonPrimitive(value));
		return array;
	}

	private static String utcNow() {
		SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		iso.setTimeZone(TimeZone.getTimeZone("UTC"));
		return iso.format(new Date());
	}

	private static void writeText(File file, String text) throws IOException {
		File parent = file.getParentFile();
		if (parent != null && !parent.isDirectory() && !parent.mkdirs())
			throw new IOException("Could not create " + parent);
		Path temporary = Files.createTempFile(parent.toPath(), "." + file.getName() + ".", ".partial");
		Files.write(temporary, text.getBytes(StandardCharsets.UTF_8));
		moveReplace(temporary, file.toPath());
	}

	private static void moveReplace(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
