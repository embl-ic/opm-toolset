package de.embl.iclm;

import ij.ImagePlus;
import ij.io.FileInfo;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.security.MessageDigest;

/**
 * Acquisition-local defaults for the Aliasing Resampling dialogs, with where each came from.
 *
 * <p>Read from the ExperimentalParameters.txt beside the image when ImageJ still knows where the
 * image was opened from. The step is the one the deskew uses ({@link IO#loadExperimentalParametersFromFile}:
 * um per galvo DU times Galvo DUs per step), with "Step size /um" as the fallback, so the
 * reconstruction and the deskew that follows cannot read two different pitches from one file.
 *
 * <p>A file describing a different number of planes than the image holds is not trusted for the
 * step: a subset, or a dense scan beside a sparse one, would otherwise be given a pitch that
 * belongs to another stack. The note says so, and the fallback step stays in the field.
 */
final class AliasingMetadata {

	double pixel = 0.116;
	double step = 0.265;
	double angle = 25;
	double exposure = 100;
	String note = "Fallback defaults: 0.116 um pixel, 0.265 um step, 25 degrees, 100 ms exposure.\n"
			+ "Verify these values before use.";

	static AliasingMetadata from (ImagePlus image) {
		AliasingMetadata result = new AliasingMetadata();
		if (image == null) return result;
		FileInfo info = image.getOriginalFileInfo();
		if (info == null || info.directory == null) return result;
		File file = new File(info.directory, "ExperimentalParameters.txt");
		if (!file.isFile()) return result;
		try {
			return read(file, image.getStackSize());
		} catch (IOException | IllegalArgumentException e) {
			result.note = "Could not read acquisition metadata: " + e.getMessage() + ". " + result.note;
			return result;
		}
	}

	static AliasingMetadata read (File file, int depth) throws IOException {
		AliasingMetadata result = new AliasingMetadata();
		Map<String, Double> values = readValues(file);
		StringBuilder missing = new StringBuilder();
		result.pixel = value(values, "Pixel size at object /um", result.pixel, missing);
		result.angle = value(values, "Tilt angle", result.angle, missing);
		result.exposure = value(values, "Exposure time /ms", result.exposure, missing);
		Double count = values.get("Planes per volume");
		if (count != null && count.intValue() != depth) {
			result.note = "Metadata plane count " + count.intValue() + " differs from input " + depth + ".\n"
					+ "Scan step is UNVERIFIED; enter the correct value.\n";
		} else {
			Double galvo = values.get("um per galvo DU");
			Double steps = values.get("Galvo DUs per step");
			if (galvo != null && steps != null && Double.isFinite(galvo * steps) && galvo * steps > 0)
				result.step = galvo * steps;
			else result.step = value(values, "Step size /um", result.step, missing);
			result.note = "Acquisition metadata:\n" + file.getAbsolutePath() + "\n";
		}
		if (missing.length() > 0) result.note += "Fallback used for: " + missing + ".\n";
		result.note += "Exposure " + result.exposure + " ms.\nObjective NA, bead diameter and scan mode are not inferred.";
		return result;
	}

	private static Map<String, Double> readValues(File file) throws IOException {
		if (Files.size(file.toPath()) > 1024 * 1024) throw new IOException("Metadata text exceeds 1 MiB");
		return readValues(Files.readAllBytes(file.toPath()));
	}
	private static Map<String, Double> readValues(byte[] bytes) {
		Map<String, Double> values = new LinkedHashMap<String, Double>();
		for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\r?\\n")) {
			int colon = line.indexOf(':');
			if (colon < 0) continue;
			try {
				values.put(line.substring(0, colon).replace("\ufeff", "").trim(), Double.parseDouble(line.substring(colon + 1).trim()));
			} catch (NumberFormatException textMetadata) {
				// a text value, such as the camera mode; nothing here reads it
			}
		}
		return values;
	}

	/** Sparse overwrite patch: defaults never masquerade as fields found in a file. */
	static final class Parsed {
		String path, hash;
		Integer planes;
		final Map<String, Double> fields = new LinkedHashMap<String, Double>();
		final List<String> warnings = new ArrayList<String>();
		void put(Map<String, Double> values, String key, String field) {
			Double v = values.get(key);
			if (v == null) return;
			if (!Double.isFinite(v) || v <= 0 || (field.equals("angle") && v >= 89.9)) warnings.add("Invalid " + key);
			else fields.put(field, v);
		}
		void apply(AliasingAdvisorSettings s, int originalDepth) {
			s.geometryConfirmed = false;
			s.timingVerified = false;
			s.supportMeasured = false;
			s.noiseKnown = false;
			// A recorded period switches the measured-period mode on; its absence says nothing about the
			// user's timing policy, so it leaves the mode as it was (the period is marked unverified below).
			if (fields.containsKey("periodMs")) s.measuredPeriod = true;
			for (String field : new String[] {"pixel", "angle", "step", "exposureMs", "na", "refractiveIndex", "wavelengthNm", "periodMs"})
				s.evidence.put(field, "Unverified for selected acquisition");
			for (Map.Entry<String, Double> field : fields.entrySet()) {
				try { AliasingAdvisorSettings.class.getDeclaredField(field.getKey()).setDouble(s, field.getValue()); }
				catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
				s.origins.put(field.getKey(), "Metadata: " + path);
				s.evidence.put(field.getKey(), "Recorded; source association must be verified");
			}
			if (planes != null && planes == originalDepth && fields.containsKey("step")
					&& fields.containsKey("pixel") && fields.containsKey("angle")) s.geometryConfirmed = true;
			if (planes != null && fields.containsKey("step")) s.extent = (planes - 1) * s.step;
			s.metadataPath = path; s.metadataHash = hash; s.derived();
			List<String> recent = new ArrayList<String>(); recent.add(path);
			for (String p : s.recentMetadata) if (!recent.contains(p) && recent.size() < 5) recent.add(p);
			s.recentMetadata = recent.toArray(new String[recent.size()]);
		}
	}

	static Parsed parse(File file) throws IOException {
		if (Files.size(file.toPath()) > 1024 * 1024) throw new IOException("Metadata text exceeds 1 MiB");
		byte[] bytes = Files.readAllBytes(file.toPath());
		Map<String, Double> v = readValues(bytes);
		Parsed p = new Parsed(); p.path = file.getCanonicalPath();
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
			StringBuilder hex = new StringBuilder(); for (byte b : digest) hex.append(String.format("%02x", b & 255));
			p.hash = hex.toString();
		} catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
		p.put(v, "Pixel size at object /um", "pixel");
		if (!p.fields.containsKey("pixel") && valid(v.get("Pixel size /um")) && valid(v.get("System magnification"))) {
			double pitch = v.get("Pixel size /um") / v.get("System magnification");
			if (valid(pitch)) p.fields.put("pixel", pitch); else p.warnings.add("Invalid camera-pixel / magnification ratio");
		}
		p.put(v, "Tilt angle", "angle"); p.put(v, "Exposure time /ms", "exposureMs");
		p.put(v, "Step size /um", "step");
		Double galvo = v.get("um per galvo DU"), increment = v.get("Galvo DUs per step");
		if (galvo != null && increment != null && valid(galvo * increment)) {
			if (p.fields.containsKey("step") && Math.abs(p.fields.get("step") - galvo * increment) > 1e-6)
				p.warnings.add("Galvo product overrides the conflicting explicit step");
			p.fields.put("step", galvo * increment);
		}
		Double planes = v.get("Planes per volume");
		if (valid(planes) && planes == Math.rint(planes) && planes >= 2 && planes <= Integer.MAX_VALUE) p.planes = planes.intValue();
		else if (planes != null) p.warnings.add("Invalid plane count");
		p.put(v, "Effective detection NA", "na"); p.put(v, "Refractive index", "refractiveIndex");
		p.put(v, "Emission wavelength /nm", "wavelengthNm"); p.put(v, "Frame period /ms", "periodMs");
		if (p.fields.isEmpty()) throw new IOException("No recognized acquisition fields in " + file.getName());
		return p;
	}
	private static boolean valid(Double v) { return v != null && Double.isFinite(v) && v > 0; }
	static File discover(ImagePlus image) {
		if (image == null || image.getOriginalFileInfo() == null || image.getOriginalFileInfo().directory == null) return null;
		File folder = new File(image.getOriginalFileInfo().directory);
		for (int level = 0; level < 2 && folder != null; level++, folder = folder.getParentFile())
			for (String name : new String[] {"ExperimentalParameters.txt", "ExperimentalParameter.txt"}) {
				File file = new File(folder, name); if (file.isFile()) return file;
			}
		return null;
	}

	private static double value (Map<String, Double> values, String key, double fallback, StringBuilder missing) {
		Double value = values.get(key);
		if (value != null && Double.isFinite(value) && value > 0) return value;
		if (missing.length() > 0) missing.append(", ");
		missing.append(key);
		return fallback;
	}
}
