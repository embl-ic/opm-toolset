package de.embl.iclm;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pixel-free description of one OPM OME-Zarr dataset.
 *
 * <p>Discovery deliberately reads only the small JSON metadata files. A live writer may be
 * between two atomic metadata updates, an older dataset may have no {@code opm} block, and a
 * damaged child dataset must not prevent its siblings from being listed. All three cases are
 * represented by a descriptor with warnings instead of aborting the scan.
 */
public final class OpmZarrDataset {

	public static final String[] KNOWN_PROJECTIONS = {
		"maxX", "maxY", "maxZ", "meanX", "meanY", "meanZ"
	};

	private final File root;
	private final String displayName;
	private final OpmProvenance provenance;
	private final long[] volumeDimensions;
	private final Map<String, long[]> projectionDimensions;
	private final Map<String, double[]> projectionPixelSizes;
	private final List<String> channelLabels;
	private final List<String> timePointLabels;
	private final List<String> warnings;
	private final int committedTimepoints;
	private final boolean complete;

	private OpmZarrDataset(
			File root,
			String displayName,
			OpmProvenance provenance,
			long[] volumeDimensions,
			Map<String, long[]> projectionDimensions,
			Map<String, double[]> projectionPixelSizes,
			List<String> channelLabels,
			List<String> timePointLabels,
			List<String> warnings,
			int committedTimepoints,
			boolean complete) {
		this.root = root;
		this.displayName = displayName;
		this.provenance = provenance;
		this.volumeDimensions = volumeDimensions;
		this.projectionDimensions = projectionDimensions;
		this.projectionPixelSizes = projectionPixelSizes;
		this.channelLabels = channelLabels;
		this.timePointLabels = timePointLabels;
		this.warnings = warnings;
		this.committedTimepoints = committedTimepoints;
		this.complete = complete;
	}

	/** Find one dataset or all nested datasets, without opening an N5 reader. */
	public static List<OpmZarrDataset> discover(File selection) {
		List<OpmZarrDataset> result = new ArrayList<OpmZarrDataset>();
		if (selection == null || !selection.exists()) return result;
		discoverInto(selection, result);
		Collections.sort(result, new Comparator<OpmZarrDataset>() {
			@Override
			public int compare(OpmZarrDataset a, OpmZarrDataset b) {
				int byAcquisition = naturalCompare(a.displayName, b.displayName);
				return byAcquisition != 0 ? byAcquisition
						: naturalCompare(a.root.getAbsolutePath(), b.root.getAbsolutePath());
			}
		});
		return result;
	}

	private static void discoverInto(File candidate, List<OpmZarrDataset> result) {
		if (!candidate.isDirectory()) return;
		if (looksLikeDataset(candidate)) {
			result.add(read(candidate));
			return; // .ome.zarr is one unit; do not mistake its arrays for child datasets
		}
		File[] children = candidate.listFiles();
		if (children == null) return;
		Arrays.sort(children, new Comparator<File>() {
			@Override
			public int compare(File a, File b) {
				return naturalCompare(a.getName(), b.getName());
			}
		});
		for (File child : children) if (child.isDirectory()) discoverInto(child, result);
	}

	private static boolean looksLikeDataset(File directory) {
		String lower = directory.getName().toLowerCase();
		return lower.endsWith(".ome.zarr") ||
				(new File(directory, ".zgroup").isFile() &&
				 (new File(directory, ".zattrs").isFile() || new File(directory, "s0/.zarray").isFile()));
	}

	/** Read a descriptor and retain recoverable metadata errors as warnings. */
	public static OpmZarrDataset read(File root) {
		List<String> warnings = new ArrayList<String>();
		JsonObject attrs = readObject(new File(root, ".zattrs"), warnings, "root attributes");
		OpmProvenance provenance = null;
		if (attrs != null && object(attrs, OpmProvenance.KEY) != null) {
			try {
				provenance = OpmProvenance.fromJson(object(attrs, OpmProvenance.KEY));
			} catch (RuntimeException e) {
				warnings.add("Could not parse opm provenance: " + safeMessage(e));
			}
		}
		if (provenance == null) warnings.add("No usable opm provenance block; using metadata fallbacks.");

		long[] volume = readZarrDimensions(new File(root, "s0/.zarray"), warnings, "s0");
		JsonObject state = attrs == null ? null : object(attrs, OpmZarrWriter.WRITE_STATE_KEY);
		int shapeTimepoints = volume.length == 5 ? positiveInt(volume[4]) : 0;
		int committed = shapeTimepoints;
		boolean complete = new File(root, OpmZarrWriter.SUCCESS_FILE).isFile();
		if (state != null) {
			Integer stateCommitted = integer(state, "committedTimepoints");
			if (stateCommitted != null) committed = Math.max(0, Math.min(shapeTimepoints, stateCommitted.intValue()));
			String status = string(state, "status");
			complete = "complete".equals(status) && new File(root, OpmZarrWriter.SUCCESS_FILE).isFile();
		}
		if (volume.length == 5) volume[4] = committed;

		Set<String> projectionNames = new LinkedHashSet<String>();
		Map<String, double[]> projectionPixelSizes = new LinkedHashMap<String, double[]>();
		JsonObject projectionIndex = attrs == null ? null : object(attrs, "opm_projections");
		if (projectionIndex != null) {
			JsonArray available = array(projectionIndex, "available");
			if (available != null) for (JsonElement element : available)
				if (element != null && element.isJsonPrimitive()) projectionNames.add(element.getAsString());
			JsonArray datasets = array(projectionIndex, "datasets");
			if (datasets != null) for (JsonElement element : datasets) {
				if (!element.isJsonObject()) continue;
				JsonObject item = element.getAsJsonObject();
				String name = string(item, "name");
				double[] scale = scale(item);
				if (name != null && scale != null && scale.length >= 4)
					projectionPixelSizes.put(name, new double[] { scale[3], scale[2] });
			}
		}
		for (String known : KNOWN_PROJECTIONS)
			if (new File(root, "projections/" + known + "/.zarray").isFile()) projectionNames.add(known);

		Map<String, long[]> projections = new LinkedHashMap<String, long[]>();
		for (String name : projectionNames) {
			long[] dimensions = readZarrDimensions(new File(root, "projections/" + name + "/.zarray"),
					warnings, "projection " + name);
			if (dimensions.length == 4) {
				dimensions[3] = Math.min(dimensions[3], committed);
				projections.put(name, dimensions);
			}
		}

		List<String> labels = channelLabels(attrs, provenance, volume.length == 5 ? positiveInt(volume[3]) : -1);
		List<String> timeLabels = timeLabels(state, provenance, committed);
		String display = provenance != null && notBlank(provenance.datasetName)
				? provenance.datasetName : root.getName();
		return new OpmZarrDataset(root.getAbsoluteFile(), display, provenance, volume,
				projections, projectionPixelSizes, labels, timeLabels, warnings, committed, complete);
	}

	private static long[] readZarrDimensions(File file, List<String> warnings, String label) {
		JsonObject json = readObject(file, warnings, label + " array metadata");
		JsonArray shape = json == null ? null : array(json, "shape");
		if (shape == null) return new long[0];
		try {
			long[] dimensions = new long[shape.size()];
			// Zarr C order is [t,c,z,y,x] or [t,c,y,x]; N5 presents it reversed.
			for (int i = 0; i < dimensions.length; i++)
				dimensions[i] = Math.max(0, shape.get(dimensions.length - 1 - i).getAsLong());
			return dimensions;
		} catch (RuntimeException e) {
			warnings.add("Invalid shape for " + label + ": " + safeMessage(e));
			return new long[0];
		}
	}

	private static JsonObject readObject(File file, List<String> warnings, String label) {
		if (!file.isFile()) {
			warnings.add("Missing " + label + ": " + file.getName());
			return null;
		}
		try {
			String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
			JsonElement parsed = JsonParser.parseString(text);
			if (!parsed.isJsonObject()) throw new IllegalArgumentException("JSON root is not an object");
			return parsed.getAsJsonObject();
		} catch (IOException e) {
			warnings.add("Could not read " + label + ": " + safeMessage(e));
		} catch (RuntimeException e) {
			warnings.add("Could not parse " + label + ": " + safeMessage(e));
		}
		return null;
	}

	private static List<String> channelLabels(JsonObject attrs, OpmProvenance provenance, int count) {
		List<String> result = new ArrayList<String>();
		if (provenance != null && provenance.channelLabels != null) result.addAll(provenance.channelLabels);
		if (result.isEmpty() && attrs != null) {
			JsonObject omero = object(attrs, "omero");
			JsonArray channels = omero == null ? null : array(omero, "channels");
			if (channels != null) for (JsonElement channel : channels) {
				String label = channel.isJsonObject() ? string(channel.getAsJsonObject(), "label") : null;
				if (notBlank(label)) result.add(label);
			}
		}
		while (result.size() < count) result.add(String.format("channel-%d", result.size() + 1));
		if (result.size() > count && count >= 0) result = new ArrayList<String>(result.subList(0, count));
		return result;
	}

	private static List<String> timeLabels(JsonObject state, OpmProvenance provenance, int count) {
		List<String> result = new ArrayList<String>();
		JsonArray stateLabels = state == null ? null : array(state, "timePointLabels");
		if (stateLabels != null) for (JsonElement label : stateLabels) result.add(label.getAsString());
		if (result.isEmpty() && provenance != null && provenance.timePointLabels != null)
			result.addAll(provenance.timePointLabels);
		while (result.size() < count) result.add(String.format("t%06d", result.size()));
		if (result.size() > count) result = new ArrayList<String>(result.subList(0, count));
		return result;
	}

	private static double[] scale(JsonObject dataset) {
		JsonArray transforms = array(dataset, "coordinateTransformations");
		if (transforms == null) return null;
		for (JsonElement element : transforms) {
			if (!element.isJsonObject()) continue;
			JsonObject transform = element.getAsJsonObject();
			if (!"scale".equals(string(transform, "type"))) continue;
			JsonArray values = array(transform, "scale");
			if (values == null) continue;
			double[] scale = new double[values.size()];
			for (int i = 0; i < scale.length; i++) scale[i] = values.get(i).getAsDouble();
			return scale;
		}
		return null;
	}

	private static JsonObject object(JsonObject parent, String name) {
		JsonElement value = parent == null ? null : parent.get(name);
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
	}

	private static JsonArray array(JsonObject parent, String name) {
		JsonElement value = parent == null ? null : parent.get(name);
		return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
	}

	private static String string(JsonObject parent, String name) {
		JsonElement value = parent == null ? null : parent.get(name);
		try { return value != null && value.isJsonPrimitive() ? value.getAsString() : null; }
		catch (RuntimeException ignored) { return null; }
	}

	private static Integer integer(JsonObject parent, String name) {
		JsonElement value = parent == null ? null : parent.get(name);
		try { return value != null && value.isJsonPrimitive() ? Integer.valueOf(value.getAsInt()) : null; }
		catch (RuntimeException ignored) { return null; }
	}

	private static int positiveInt(long value) {
		return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
	}

	private static boolean notBlank(String value) {
		return value != null && !value.trim().isEmpty();
	}

	private static String safeMessage(Throwable error) {
		return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
	}

	private static int naturalCompare(String first, String second) {
		int a = 0, b = 0;
		while (a < first.length() && b < second.length()) {
			char ca = first.charAt(a), cb = second.charAt(b);
			if (Character.isDigit(ca) && Character.isDigit(cb)) {
				int ae = a, be = b;
				while (ae < first.length() && Character.isDigit(first.charAt(ae))) ae++;
				while (be < second.length() && Character.isDigit(second.charAt(be))) be++;
				String an = first.substring(a, ae), bn = second.substring(b, be);
				int az = 0, bz = 0;
				while (az + 1 < an.length() && an.charAt(az) == '0') az++;
				while (bz + 1 < bn.length() && bn.charAt(bz) == '0') bz++;
				String at = an.substring(az), bt = bn.substring(bz);
				if (at.length() != bt.length()) return at.length() < bt.length() ? -1 : 1;
				int number = at.compareTo(bt);
				if (number != 0) return number;
				a = ae; b = be;
				continue;
			}
			int compared = Character.toLowerCase(ca) - Character.toLowerCase(cb);
			if (compared != 0) return compared;
			a++; b++;
		}
		return first.length() - second.length();
	}

	/** Whether a loaded CSV has the two rows of three the runtime alignment needs. */
	public static boolean isAlignmentMatrix(double[][] matrix) {
		return matrix != null && matrix.length >= 2
				&& matrix[0] != null && matrix[0].length >= 3
				&& matrix[1] != null && matrix[1].length >= 3;
	}

	/**
	 * Replace the rigid alignment recorded in a dataset's {@code .zattrs}, changing nothing else.
	 * <p>
	 * The canonical format stores unaligned halves and carries the alignment as metadata, so a
	 * recomputed matrix is a metadata edit rather than a reprocessing run: no pixel is touched
	 * and every view re-derives itself from the new numbers. Only {@code opm.alignMatrix} and
	 * {@code opm.alignMatrixSource} are rewritten, leaving the multiscale, omero, projection and
	 * write-state blocks exactly as the writer left them.
	 * <p>
	 * The first overwrite copies the untouched file to {@code .zattrs.original} and later ones
	 * leave that copy alone, so the acquisition's own alignment stays recoverable however many
	 * times a matrix is tried. The replacement itself goes through a temporary file so an
	 * interrupted write cannot leave a half-written {@code .zattrs} behind.
	 *
	 * @param root					: the {@code .ome.zarr} directory
	 * @param matrix				: 2 x 3 rigid alignment, as {@link IO#loadMatrixFromFile} returns
	 * @param source				: recorded as {@code opm.alignMatrixSource} for provenance
	 */
	public static void writeAlignMatrix(File root, double[][] matrix, String source) throws IOException {
		if (root == null || !root.isDirectory())
			throw new IOException("Not an OME-Zarr directory: " + root);
		if (!isAlignmentMatrix(matrix))
			throw new IOException("A 2 x 3 rigid alignment matrix is required.");
		File attrsFile = new File(root, ".zattrs");
		if (!attrsFile.isFile()) throw new IOException("This dataset has no .zattrs: " + root);

		JsonElement parsed = JsonParser.parseString(
				new String(Files.readAllBytes(attrsFile.toPath()), StandardCharsets.UTF_8));
		if (!parsed.isJsonObject()) throw new IOException("The .zattrs root is not a JSON object: " + attrsFile);
		JsonObject attrs = parsed.getAsJsonObject();
		JsonObject opm = object(attrs, OpmProvenance.KEY);
		if (opm == null)
			throw new IOException("This dataset has no opm provenance block to update: " + attrsFile);

		JsonArray rows = new JsonArray();
		for (int r = 0; r < 2; r++) {
			JsonArray row = new JsonArray();
			for (int c = 0; c < 3; c++) row.add(Double.valueOf(matrix[r][c]));
			rows.add(row);
		}
		opm.add("alignMatrix", rows);
		if (source != null && !source.trim().isEmpty()) opm.addProperty("alignMatrixSource", source.trim());

		File original = new File(root, ".zattrs.original");
		if (!original.exists()) Files.copy(attrsFile.toPath(), original.toPath());
		Path temporary = Files.createTempFile(root.toPath(), ".zattrs.", ".partial");
		Files.write(temporary, (new GsonBuilder().setPrettyPrinting().create().toJson(attrs) + '\n')
				.getBytes(StandardCharsets.UTF_8));
		try {
			Files.move(temporary, attrsFile.toPath(),
					StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temporary, attrsFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public File getRoot() { return root; }
	public String getDisplayName() { return displayName; }
	public OpmProvenance getProvenance() { return provenance; }
	public long[] getVolumeDimensions() { return volumeDimensions.clone(); }
	public int getWidth() { return volumeDimensions.length == 5 ? positiveInt(volumeDimensions[0]) : 0; }
	public int getHeight() { return volumeDimensions.length == 5 ? positiveInt(volumeDimensions[1]) : 0; }
	public int getDepth() { return volumeDimensions.length == 5 ? positiveInt(volumeDimensions[2]) : 0; }
	public int getChannelCount() { return volumeDimensions.length == 5 ? positiveInt(volumeDimensions[3]) : 0; }
	public int getTimepointCount() { return committedTimepoints; }
	public boolean hasVolume() { return getWidth() > 0 && getHeight() > 0 && getDepth() > 0 && getChannelCount() > 0; }
	public boolean isComplete() { return complete; }
	public List<String> getChannelLabels() { return Collections.unmodifiableList(channelLabels); }
	public List<String> getTimePointLabels() { return Collections.unmodifiableList(timePointLabels); }
	public List<String> getWarnings() { return Collections.unmodifiableList(warnings); }
	public List<String> getAvailableProjections() { return new ArrayList<String>(projectionDimensions.keySet()); }
	public boolean hasProjection(String name) { return projectionDimensions.containsKey(name); }
	public long[] getProjectionDimensions(String name) {
		long[] dimensions = projectionDimensions.get(name);
		return dimensions == null ? new long[0] : dimensions.clone();
	}
	/** ImageJ pixel width/height in micrometres for a projection. */
	public double[] getProjectionPixelSizeUm(String name) {
		double[] scale = projectionPixelSizes.get(name);
		if (scale != null) return scale.clone();
		double[] voxel = voxelSizeUm();
		char axis = name == null || name.isEmpty() ? 'Z' : Character.toUpperCase(name.charAt(name.length() - 1));
		if (axis == 'X') return new double[] { voxel[2], voxel[1] };
		if (axis == 'Y') return new double[] { voxel[0], voxel[2] };
		return new double[] { voxel[0], voxel[1] };
	}
	public double[] voxelSizeUm() {
		if (provenance != null && provenance.deskewedVoxelSizeUm != null && provenance.deskewedVoxelSizeUm.length >= 3)
			return provenance.deskewedVoxelSizeUm.clone();
		return new double[] { 1, 1, 1 };
	}
	public double frameIntervalSeconds() {
		return provenance == null ? 0 : Math.max(0, provenance.frameIntervalSeconds);
	}
	public String absoluteDatasetPath(String relative) {
		return new File(root, relative.replace('/', File.separatorChar)).getAbsolutePath();
	}

	@Override
	public String toString() {
		String state = complete ? "complete" : "live/partial";
		return displayName + "  [T=" + getTimepointCount() + ", C=" + getChannelCount() + ", " + state + "]";
	}
}
