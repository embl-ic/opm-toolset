package de.embl.iclm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
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
	/** Voxel size of the deskewed result, z = zStep * sin(angle), y = xy * cos(angle). */
	public double[] deskewedVoxelSizeUm;

	// --- the transforms --------------------------------------------------------------
	/** The 4x4 affine actually applied to the raw volume. */
	public double[][] deskewMatrix;
	/** The 2x3 rigid transform to apply at view time; null when none is known. */
	public double[][] alignMatrix;
	/** Where that matrix came from - normally a bead acquisition, not this dataset. */
	public String alignMatrixSource;
	/** Matrix convention used by existing SIFT CSVs; a left-side view derives F*inverse(M)*F. */
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
		SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		iso.setTimeZone(TimeZone.getTimeZone("UTC"));
		createdUtc = iso.format(new Date());

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

	/** Voxel size of the deskewed result, from the acquisition geometry. */
	public OpmProvenance computeDeskewedVoxelSize () {
		double y = xyPixelSizeUm * Utils.cos ( opmAngleDegrees );
		double z = zStepSizeUm * Utils.sin ( opmAngleDegrees );
		deskewedVoxelSizeUm = new double[] { xyPixelSizeUm, y, z };
		return this;
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
