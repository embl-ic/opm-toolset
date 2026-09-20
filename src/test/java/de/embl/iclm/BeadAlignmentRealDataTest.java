package de.embl.iclm;

import static org.junit.Assert.assertNotNull;

import ij.process.ImageProcessor;
import ij.ImagePlus;
import ij.io.FileSaver;

import java.io.File;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.junit.Assume;
import org.junit.Test;

/** Opt-in benchmark for the supplied bead acquisition; excluded from an ordinary test run. */
public class BeadAlignmentRealDataTest {

	@Test
	public void compareSiftLogAndHybridOnSuppliedBeads() throws Exception {
		String folderName = System.getProperty("opm.bead.folder");
		Assume.assumeTrue(folderName != null && !folderName.trim().isEmpty());
		File folder = new File(folderName);
		File metadata = new File(folder, "ExperimentalParameters.txt");
		double[] geometry = IO.loadExperimentalParametersFromFile(metadata.getAbsolutePath());
		assertNotNull(geometry);
		java.util.List<File> files = BatchProcessingUtils.listTiffs(folder, "", false);
		Assume.assumeTrue(files.size() >= 2);

		LinkedHashMap<String, ImageProcessor> projections = new LinkedHashMap<String, ImageProcessor>();
		long projectionStart = System.nanoTime();
		for (int i = 0; i < 2; i++) {
			File file = files.get(i);
			Map<BeadAlignment.Side, ImageProcessor> pair = BeadAlignment.deskewedMax(file,
					EnumSet.of(BeadAlignment.Side.LEFT, BeadAlignment.Side.RIGHT),
					geometry[0], geometry[1], geometry[2]);
			int acquisition = BatchProcessingUtils.acquisitionChannel(file);
			if (acquisition < 1) acquisition = i + 1;
			projections.put(ChannelOperationSettings.sourceKey(acquisition, true), pair.get(BeadAlignment.Side.LEFT));
			projections.put(ChannelOperationSettings.sourceKey(acquisition, false), pair.get(BeadAlignment.Side.RIGHT));
		}
		System.out.printf(Locale.US, "BEAD_BENCH projection_ms=%.1f dimensions=%dx%d%n",
				(System.nanoTime() - projectionStart) / 1e6,
				projections.values().iterator().next().getWidth(),
				projections.values().iterator().next().getHeight());

		String referenceKey = projections.keySet().iterator().next();
		ImageProcessor reference = projections.get(referenceKey);
		File diagnostic = new File("target/bead-benchmark");
		diagnostic.mkdirs();
		for (Map.Entry<String, ImageProcessor> entry : projections.entrySet()) {
			ImageProcessor display = entry.getValue().duplicate();
			display.resetMinAndMax();
			new FileSaver(new ImagePlus(entry.getKey(), display.convertToByte(true))).saveAsPng(
					new File(diagnostic, entry.getKey().replaceAll("[^A-Za-z0-9_-]", "_") + ".png").getAbsolutePath());
		}
		for (Map.Entry<String, ImageProcessor> entry : projections.entrySet()) {
			if (entry.getKey().equals(referenceKey)) continue;
			ImageProcessor moving = entry.getValue();
			long start = System.nanoTime();
			BeadAlignment.Result sift = BeadAlignment.siftOnly(reference, moving);
			double siftMs = (System.nanoTime() - start) / 1e6;
			if (sift != null) sift = BeadAlignment.evaluate(reference, moving, sift.matrix, sift.method);
			start = System.nanoTime();
			BeadAlignment.Result log = BeadAlignment.logOnly(reference, moving);
			double logMs = (System.nanoTime() - start) / 1e6;
			start = System.nanoTime();
			BeadAlignment.Result hybrid = BeadAlignment.align(reference, moving);
			double hybridMs = (System.nanoTime() - start) / 1e6;
			print(entry.getKey(), sift, siftMs);
			print(entry.getKey(), log, logMs);
			print(entry.getKey(), hybrid, hybridMs);
		}
	}

	private static void print(String source, BeadAlignment.Result result, double millis) {
		if (result == null) {
			System.out.printf(Locale.US, "BEAD_BENCH source=%s method=failed time_ms=%.1f%n", source, millis);
			return;
		}
		double[][] m = result.matrix;
		double angle = Math.toDegrees(Math.atan2(m[1][0], m[0][0]));
		System.out.printf(Locale.US,
				"BEAD_BENCH source=%s method=%s time_ms=%.1f fixed=%d moving=%d matches=%d rms_px=%.4f angle_deg=%.5f tx=%.4f ty=%.4f%n",
				source, result.method.replace(' ', '_'), millis, result.referenceSpots.size(),
				result.movingSpots.size(), result.matches, result.rmsPixels, angle, m[0][2], m[1][2]);
	}
}
