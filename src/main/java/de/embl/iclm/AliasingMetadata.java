package de.embl.iclm;

import ij.ImagePlus;
import ij.io.FileInfo;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

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
		Map<String, Double> values = new HashMap<String, Double>();
		for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
			int colon = line.indexOf(':');
			if (colon < 0) continue;
			try {
				values.put(line.substring(0, colon).trim(), Double.parseDouble(line.substring(colon + 1).trim()));
			} catch (NumberFormatException textMetadata) {
				// a text value, such as the camera mode; nothing here reads it
			}
		}
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
			if (galvo != null && steps != null && galvo * steps > 0)
				result.step = galvo * steps;
			else
				result.step = value(values, "Step size /um", result.step, missing);
			result.note = "Acquisition metadata:\n" + file.getAbsolutePath() + "\n";
		}
		if (missing.length() > 0) result.note += "Fallback used for: " + missing + ".\n";
		result.note += "Exposure " + result.exposure + " ms.\nObjective NA, bead diameter and scan mode are not inferred.";
		return result;
	}

	private static double value (Map<String, Double> values, String key, double fallback, StringBuilder missing) {
		Double value = values.get(key);
		if (value != null && Double.isFinite(value) && value > 0) return value;
		if (missing.length() > 0) missing.append(", ");
		missing.append(key);
		return fallback;
	}
}
