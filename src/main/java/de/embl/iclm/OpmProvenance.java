package de.embl.iclm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Everything needed to know how a deskewed volume came to exist, stored beside it.
 *
 * <p>A deskewed volume on its own is not reproducible: the geometry that produced it, the
 * file it came from, and the matrices that were applied all live outside the pixels. This
 * record carries them, so a dataset opened months later can say what it is rather than
 * relying on a folder name.
 *
 * <p>The alignment matrix is deliberately <em>recorded and not applied</em>. The realistic
 * acquisition is a bead sample first, which gives near-identical signal in both channels and
 * so a trustworthy SIFT solution, followed by the biological time-lapse in the same optical
 * setup. Running SIFT on the biological data would be both slower and less reliable, so the
 * bead matrix travels with the biological dataset and is applied when the data is viewed.
 * That also means the stored pixels are resampled once, by the deskew, rather than twice.
 *
 * <p>Written into the OME-Zarr group's <code>.zattrs</code> under the key {@link #KEY}.
 * Readers that do not know about it ignore it, so the dataset stays a valid OME-Zarr.
 */
public class OpmProvenance {

	/** The .zattrs key this record lives under. */
	public static final String KEY = "opm";
	/** Version of this record's own schema, so a later reader can migrate it. */
	public static final String SCHEMA = "opm-provenance/2";

	// --- what was acquired -----------------------------------------------------------
	public String schema = SCHEMA;
	/** A deskewed OPM dataset, the normal case and the value a record without this field means. */
	public static final String CONTENT_DESKEWED = "deskewed";
	/** Acquisition pixels stored unchanged by a format conversion; no deskew was applied. */
	public static final String CONTENT_RAW = "raw";
	/**
	 * What the stored pixels are.
	 * <p>
	 * Defaults to {@link #CONTENT_DESKEWED}, so a dataset written before this field existed
	 * still reads as what it is. A store written by Format conversion says {@link #CONTENT_RAW}
	 * instead, which is the only way a reader can tell that its identity deskew matrix and its
	 * absent alignment are the truth rather than missing metadata.
	 */
	public String contentKind = CONTENT_DESKEWED;
	public String datasetName;
	/** Folder the raw acquisition files were read from. */
	public String sourceFolder;
	/** The raw files that went into this dataset, in channel order. */
	public List<String> sourceFiles = new ArrayList<String>();
	/** Stable source labels for the committed T axis, in order. */
	public List<String> timePointLabels = new ArrayList<String>();
	/** Elapsed acquisition time for every committed time point, in seconds. */
	public List<Double> timePointElapsedSeconds = new ArrayList<Double>();
	/** Where the acquisition metadata was read from, when there was one. */
	public String experimentalParametersFile;

	// --- the geometry that produced the deskew ---------------------------------------
	public double xyPixelSizeUm;
	public double zStepSizeUm;
	public double opmAngleDegrees;
	/** Seconds between time points, 0 when not a time-lapse. */
	public double frameIntervalSeconds;
	/**
	 * Sampling of the deskewed output grid in x/y/z. The affine is evaluated in camera-pixel
	 * coordinates, so all three output axes use {@link #xyPixelSizeUm}; the angle and stage step
	 * change the output bounds, not the spacing between output samples.
	 */
	public double[] deskewedVoxelSizeUm;

	// --- the transforms --------------------------------------------------------------
	/** The 4x4 affine actually applied to the raw volume. */
	public double[][] deskewMatrix;
	/** The 2x3 rigid transform to apply at view time; null when none is known. */
	public double[][] alignMatrix;
	/** Optional source-specific transforms, each mapping its labelled channel to alignReference. */
	public Map<String, double[][]> alignMatrices = new LinkedHashMap<String, double[][]>();
	/** Reference source for alignMatrices; its stored matrix is identity. */
	public String alignReference;
	/** Where that matrix came from - normally a bead acquisition, not this dataset. */
	public String alignMatrixSource;
	/** UTC timestamp of the source matrix file, or of the most recent metadata replacement. */
	public String alignMatrixModifiedUtc;
	/** Matrix convention: historic right-to-left, or the tagged set's common-reference convention. */
	public String alignMatrixConvention = "right-flipped-to-left";
	/** False means the pixels are unaligned and the matrix still has to be applied. */
	public boolean alignApplied = false;
	/** Which camera half the viewer should flip and align onto the untouched half. */
	public String alignFlipHalf;
	/** Default runtime resampling mode; a viewer may still let the user override it. */
	public boolean alignInterpolate = true;

	// --- how the channels are laid out -----------------------------------------------
	/** One label per channel of s0, in order, e.g. "_Channel0001-left". */
	public List<String> channelLabels = new ArrayList<String>();
	/**
	 * The channel option of the deskew run that wrote this dataset, one of
	 * {@link Parameter#CHANNEL_OPTIONS}; null for a dataset written before it was recorded.
	 * <p>
	 * Display metadata only. The stored halves are the same whatever it says; it tells a viewer
	 * which composition reproduces the result that run produced, see {@link DeskewChannelView}.
	 */
	public String deskewChannelOption;
	/** Whether that run combined matching {@code _Channel####} files into one result. */
	public boolean deskewCombineChannels;
	/** The output sources that run selected, in order, when it combined files. */
	public List<String> deskewChannelOrder = new ArrayList<String>();

	// --- who made it -----------------------------------------------------------------
	public String createdUtc;
	public String computerName;
	public String operatingSystem;
	public String javaVersion;
	public String pluginVersion;
	public String processor;			// "GPU: <device>" or "CPU"
	public String gpuDevice;
	public long gpuMaxAllocationMb;
	public int threadsUsed;

	/**			Fill in everything about the machine and the run that can be discovered here
	 *
	 * @param usedGpu			: whether the deskew actually ran on the GPU
	 * <p>
	 * @return					: this record, for chaining
	 */
	public OpmProvenance stampEnvironment (
			boolean usedGpu
			) {
		createdUtc = utcTimestamp(System.currentTimeMillis());
		if (alignMatrix != null && (alignMatrixModifiedUtc == null || alignMatrixModifiedUtc.trim().isEmpty()))
			alignMatrixModifiedUtc = createdUtc;

		computerName = hostName();
		operatingSystem = System.getProperty("os.name") + " " + System.getProperty("os.version");
		javaVersion = System.getProperty("java.version");
		pluginVersion = version();
		threadsUsed = Runtime.getRuntime().availableProcessors();

		if (usedGpu) {
			try {
				gpuDevice = net.haesleinhuepf.clij2.CLIJ2.getInstance().getGPUName();
				gpuMaxAllocationMb = GPU.memory_size() / (1024 * 1024);
				processor = "GPU: " + gpuDevice;
			} catch (Throwable t) {
				processor = "GPU (device name unavailable)";
			}
		} else {
			processor = "CPU";
		}
		return this;
	}

	/**
	 * Sampling of the deskewed result. {@link Transform#deskew} maps into a regular grid whose
	 * coordinate unit is one camera pixel, so x/y/z are all sampled at the camera XY pitch.
	 */
	public OpmProvenance computeDeskewedVoxelSize () {
		deskewedVoxelSizeUm = new double[] { xyPixelSizeUm, xyPixelSizeUm, xyPixelSizeUm };
		return this;
	}

	/**
	 * Recognise the anisotropic values written by OPM Toolset before the output-grid convention
	 * was corrected. This deliberately matches the exact old formula and schema, so unrelated
	 * OME-Zarr datasets with genuinely anisotropic sampling are left alone.
	 */
	public boolean hasLegacyDeskewedVoxelSize () {
		if (!SCHEMA.equals(schema) || deskewMatrix == null || deskewedVoxelSizeUm == null
				|| deskewedVoxelSizeUm.length < 3 || !(xyPixelSizeUm > 0)) return false;
		return near(deskewedVoxelSizeUm[0], xyPixelSizeUm)
				&& near(deskewedVoxelSizeUm[1], xyPixelSizeUm * Utils.cos(opmAngleDegrees))
				&& near(deskewedVoxelSizeUm[2], zStepSizeUm * Utils.sin(opmAngleDegrees));
	}

	/** Corrected viewer/writer spacing while retaining compatibility with existing metadata. */
	public double[] effectiveDeskewedVoxelSizeUm () {
		if (hasLegacyDeskewedVoxelSize())
			return new double[] { xyPixelSizeUm, xyPixelSizeUm, xyPixelSizeUm };
		if (deskewedVoxelSizeUm != null && deskewedVoxelSizeUm.length >= 3)
			return deskewedVoxelSizeUm.clone();
		return null;
	}

	/** ISO-8601 UTC timestamp shared by writer and viewer metadata. */
	public static String utcTimestamp(long epochMillis) {
		SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		iso.setTimeZone(TimeZone.getTimeZone("UTC"));
		return iso.format(new Date(epochMillis));
	}

	private static boolean near(double actual, double expected) {
		double scale = Math.max(1.0, Math.max(Math.abs(actual), Math.abs(expected)));
		return Math.abs(actual - expected) <= 1.0e-9 * scale;
	}

	/** As a Gson tree, ready to be attached to a group's attributes. */
	public JsonObject toJson () {
		Gson gson = new GsonBuilder().serializeSpecialFloatingPointValues().create();
		return JsonParser.parseString ( gson.toJson(this) ).getAsJsonObject();
	}

	/** Pretty JSON, for writing a sidecar file or for logging. */
	public String toPrettyJson () {
		return new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues()
				.create().toJson ( this );
	}

	/** Read a record back out of a group's attributes. */
	public static OpmProvenance fromJson (
			JsonObject json
			) {
		if (json == null) return null;
		return new Gson().fromJson ( json, OpmProvenance.class );
	}

	private static String hostName () {
		String name = System.getenv("COMPUTERNAME");
		if (name != null && !name.isEmpty()) return name;
		name = System.getenv("HOSTNAME");
		if (name != null && !name.isEmpty()) return name;
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (Throwable t) {
			return "unknown";
		}
	}

	/** The plugin version from the jar manifest, falling back to a constant. */
	private static String version () {
		try {
			String v = OpmProvenance.class.getPackage().getImplementationVersion();
			if (v != null && !v.isEmpty()) return "OPM_Toolset " + v;
		} catch (Throwable ignored) {
			// running from classes rather than a jar
		}
		return "OPM_Toolset 2.1.5";
	}
}
