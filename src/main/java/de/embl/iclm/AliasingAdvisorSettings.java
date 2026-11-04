package de.embl.iclm;

import com.google.gson.Gson;
import org.scijava.prefs.DefaultPrefService;
import java.util.LinkedHashMap;
import java.util.Map;

/** Advisor-only preferences. Run snapshots are copies, never the mutable global Parameter. */
final class AliasingAdvisorSettings {
	static final String PREFIX = "OPM-aliasing-advisor-";
	static final Gson JSON = new Gson();
	/** 2: the raw ceiling is again the only acceptance, and the noise-corrected scores are estimates. */
	static final int SCHEMA = 2;
	int schema = SCHEMA;
	double pixel = .116, angle = 25, step = .265, extent = 119.25;
	double minimumStep = .265, maximumStep = 1.06;
	boolean rangeFollowsStep = true, paddingFollowsGeometry = true;
	int refinement = 2;
	double background = 0, noiseSigma = 1, maskFraction = .1, sigmaGate = 5;
	boolean noiseKnown, syntheticNoiseFree, rawConfirmed, geometryConfirmed;
	int minimumSignal = 64, guard = 16;
	double scanPadding = 4.24, rowPadding = 3.712, taper = 0;
	boolean gpu;
	int optics = 0; // Gaussian detection/sheet, effective Gaussian widths, entered support
	double na = .8, wavelengthNm = 520, refractiveIndex = 1.333, sheetFwhm = 1;
	double fwhmX = .5, fwhmY = .5, fwhmZ = 1.1;
	double kz = .8, ky = 1.75, threshold = .05, safety = .8;
	boolean supportMeasured;
	int scanMode = 0; // continuous, step-and-hold, unknown
	double exposureMs = 100, periodMs = 100, overheadMs = 0, flybackMs = 0;
	boolean measuredPeriod, timingVerified, fixedVelocity, preserveBlur;
	double relativeMotionFloor = .8, absoluteMotionFloor = .8, baselineMotionFloor = .1;
	boolean gateAbsoluteMotion;
	/** Acceptance: raw held-out NRMSE ceiling, pooled and in every phase. */
	double maximumNrmse = .1;
	/** Estimate only: the increase in estimated error over factor 2 the estimate line reports up to. Never a gate. */
	double maximumIncrement = .05;
	int x = 0, y = 0, z = 0, width = 256, height = 128, depth = 192;
	int previewFactor = 2, previewPhase = 0;
	String sourcePath = "", metadataPath = "", metadataHash = "", exportDirectory = "";
	String[] recentMetadata = new String[0];
	Map<String, String> origins = new LinkedHashMap<String, String>();
	Map<String, String> evidence = new LinkedHashMap<String, String>();
	AliasingAdvisorSettings() { defaultProvenance(); }
	private void defaultProvenance() {
		for (java.lang.reflect.Field field : AliasingAdvisorSettings.class.getDeclaredFields()) {
			if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getName().equals("schema")) continue;
			if (field.getType().isPrimitive()) {
				origins.putIfAbsent(field.getName(), "Default"); evidence.putIfAbsent(field.getName(), "Assumed / screening setting");
			}
		}
	}

	AliasingAdvisorSettings copy() { return fromJson(JSON.toJson(this)); }
	static AliasingAdvisorSettings fromJson(String json) {
		AliasingAdvisorSettings s = JSON.fromJson(json, AliasingAdvisorSettings.class);
		if (s == null || s.schema < 1 || s.schema > SCHEMA) throw new IllegalArgumentException("Unsupported advisor settings");
		// Schema 1 gated on the raw ceiling, and schema 2 does again, so a saved ceiling keeps gating.
		// The interim schema-1 build's opt-in raw gate is an unknown field now and is dropped.
		s.schema = SCHEMA;
		if (s.origins == null) s.origins = new LinkedHashMap<String, String>();
		if (s.evidence == null) s.evidence = new LinkedHashMap<String, String>();
		if (s.recentMetadata == null) s.recentMetadata = new String[0];
		if (s.sourcePath == null) s.sourcePath = ""; if (s.metadataPath == null) s.metadataPath = "";
		if (s.metadataHash == null) s.metadataHash = ""; if (s.exportDirectory == null) s.exportDirectory = "";
		s.defaultProvenance();
		return s;
	}

	static AliasingAdvisorSettings load() {
		DefaultPrefService prefs = new DefaultPrefService();
		try {
			int count = prefs.getInt(AliasingAdvisorSettings.class, PREFIX + "parts", 0);
			if (count < 1 || count > 32) return new AliasingAdvisorSettings();
			StringBuilder json = new StringBuilder();
			for (int i = 0; i < count; i++) json.append(prefs.get(AliasingAdvisorSettings.class, PREFIX + i, ""));
			AliasingAdvisorSettings s = fromJson(json.toString());
			s.validate();
			return s;
		} catch (RuntimeException corrupt) { return new AliasingAdvisorSettings(); }
	}

	void save() {
		validate();
		String json = JSON.toJson(this);
		DefaultPrefService prefs = new DefaultPrefService();
		int parts = (json.length() + 1999) / 2000;
		if (parts > 32) throw new IllegalArgumentException("Advisor preference history is too large");
		for (int i = 0; i < parts; i++) prefs.put(AliasingAdvisorSettings.class, PREFIX + i,
				json.substring(i * 2000, Math.min(json.length(), (i + 1) * 2000)));
		prefs.put(AliasingAdvisorSettings.class, PREFIX + "parts", parts);
	}

	void derived() {
		if (rangeFollowsStep) { minimumStep = step; maximumStep = 4 * step; }
		if (paddingFollowsGeometry) { scanPadding = 16 * step; rowPadding = 32 * pixel; }
	}

	void validate() {
		positive(pixel, "Specimen pixel"); positive(step, "Reference step");
		range(angle, .0001, 89.899, "Angle"); positive(extent, "Scan extent");
		positive(minimumStep, "Minimum step"); positive(maximumStep, "Maximum step");
		if (maximumStep < minimumStep) throw new IllegalArgumentException("Maximum step must be >= minimum step");
		integer(refinement, 1, 64, "Reference refinement");
		finite(background, "Background"); range(noiseSigma, 0, 1e12, "Background noise");
		range(maskFraction, 0, .99, "Mask fraction"); range(sigmaGate, 1, 100, "Noise threshold");
		integer(minimumSignal, 1, Integer.MAX_VALUE, "Minimum signal voxels"); integer(guard, 0, 4096, "Guard");
		range(scanPadding, 0, 1e6, "Scan padding"); range(rowPadding, 0, 1e6, "Row padding");
		range(taper, 0, .999, "Taper"); integer(optics, 0, 2, "Optical model");
		positive(na, "NA"); positive(refractiveIndex, "Refractive index");
		if (na >= refractiveIndex) throw new IllegalArgumentException("Effective NA must be below refractive index");
		positive(wavelengthNm, "Emission wavelength"); positive(sheetFwhm, "Sheet thickness");
		positive(fwhmX, "PSF X"); positive(fwhmY, "PSF Y"); positive(fwhmZ, "PSF Z");
		positive(kz, "Kz"); positive(ky, "Ky"); range(threshold, .0001, .99, "OTF threshold");
		range(safety, .01, 1, "Alias safety fraction"); integer(scanMode, 0, 2, "Scan mode");
		positive(exposureMs, "Exposure"); positive(periodMs, "Frame period");
		range(overheadMs, 0, 1e9, "Overhead"); range(flybackMs, 0, 1e9, "Flyback");
		if (measuredPeriod && periodMs < exposureMs) throw new IllegalArgumentException("Frame period must be >= exposure");
		range(relativeMotionFloor, .001, 1, "Relative motion floor");
		range(absoluteMotionFloor, .001, 1, "Absolute motion floor");
		range(baselineMotionFloor, .001, 1, "Baseline motion floor");
		positive(maximumNrmse, "Raw held-out NRMSE ceiling");
		range(maximumIncrement, .001, 1, "Reported error increase over factor 2");
		integer(x, 0, Integer.MAX_VALUE, "ROI X"); integer(y, 0, Integer.MAX_VALUE, "ROI Y");
		integer(z, 0, Integer.MAX_VALUE, "ROI first plane");
		integer(width, 1, 16384, "ROI width"); integer(height, 1, 16384, "ROI height");
		integer(depth, 2, 1000000, "ROI depth"); integer(previewFactor, 1, 64, "Preview factor");
		integer(previewPhase, 0, 63, "Preview phase");
	}

	static void positive(double v, String label) {
		if (!Double.isFinite(v) || v <= 0) throw new IllegalArgumentException(label + " must be finite and positive");
	}
	static void finite(double v, String label) {
		if (!Double.isFinite(v)) throw new IllegalArgumentException(label + " must be finite");
	}
	static void range(double v, double lo, double hi, String label) {
		if (!Double.isFinite(v) || v < lo || v > hi) throw new IllegalArgumentException(label + " must be in " + lo + ".." + hi);
	}
	static void integer(int v, int lo, int hi, String label) { range(v, lo, hi, label); }
}
