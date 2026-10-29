package de.embl.iclm;

import com.google.gson.*;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Plot;
import ij.gui.PlotWindow;
import ij.process.FloatProcessor;
import java.awt.BorderLayout;
import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Result windows never become an implicit input to the advisor. */
final class AliasingAdvisorView {
	private JFrame frame;
	private PlotWindow qualityPlot, modelPlot;
	private final List<ImagePlus> previews = new ArrayList<ImagePlus>();
	void show(AliasingQualitySweep.Run run, IntConsumer selectFactor) {
		if (frame != null) frame.dispose();
		AliasingQualitySweep.NoiseModel.Fit fit = run.noise.used();
		DefaultTableModel tableModel = new DefaultTableModel(new String[] {"Factor", "Step um", "Method", "Phases", "Held-out NRMSE (raw)",
				"Worst phase (raw)", "Verdict", "Excess (est.)", "Excess, band check", "Worst-phase excess", "Increase over factor 2 (est.)",
				"Noise share (est.)", "Acquired NRMSE", "Bias", "Correlation"}, 0) {
			@Override public boolean isCellEditable(int row, int column) { return false; }
		};
		for (AliasingQualitySweep.Aggregate a : run.aggregates) tableModel.addRow(new Object[] {
			a.factor, fmt(a.factor * run.settings.step), a.method, a.phases + "/" + a.factor, fmt(a.heldout.nrmse()), fmt(a.worstPhase), a.verdict,
			fmt(a.excess), fmt(a.excessCheck), fmt(a.worstExcess), fmt(a.increase), fmt(a.heldout.noiseShare(fit)),
			fmt(a.acquired.nrmse()), fmt(a.heldout.bias()), fmt(a.heldout.correlation())});
		JTable table = new JTable(tableModel);
		table.getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting() && table.getSelectedRow() >= 0)
				selectFactor.accept(((Number)tableModel.getValueAt(table.convertRowIndexToModel(table.getSelectedRow()), 0)).intValue());
		});
		JPanel panel = new JPanel(new BorderLayout(5, 5));
		panel.add(new JLabel("<html>" + html(run.recommendation()) + "<br>" + html(run.estimate()) + "</html>"), BorderLayout.NORTH);
		panel.add(new JScrollPane(table), BorderLayout.CENTER);
		panel.add(new JLabel("<html>Select a row, then Preview. " + html(noiseNote(run)) + " Optical XYZ resolution is not measured.</html>"), BorderLayout.SOUTH);
		frame = new JFrame("Aliasing advisor — " + run.sourceTitle + " — " + run.status);
		frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE); frame.setContentPane(panel);
		Debug.Theme.decorate(frame, panel); frame.setSize(1320, 400); frame.setLocationByPlatform(true); frame.setVisible(true);
		if (qualityPlot != null) qualityPlot.close();
		Plot plot = new Plot("Aliasing held-out reference agreement", "Acquired scan step (um)", "Normalised held-out error");
		StringBuilder legend = new StringBuilder();
		String raw = run.settings.syntheticNoiseFree ? " - raw (structural, noise-free reference)" : " - raw (acceptance; includes noise)";
		Object[][] series = { {"Fourier", "raw", Color.BLUE}, {"Linear", "raw", Color.RED}, {"Fourier", "excess", Color.GRAY} };
		for (Object[] item : series) {
			if (item[1].equals("excess") && run.settings.syntheticNoiseFree) continue;
			List<Double> xs = new ArrayList<Double>(), ys = new ArrayList<Double>();
			for (AliasingQualitySweep.Aggregate a : run.aggregates) {
				double y = item[1].equals("excess") ? a.excess : a.heldout.nrmse();
				if (a.method.equals(item[0]) && a.phases == a.factor && Double.isFinite(y)) { xs.add(a.factor * run.settings.step); ys.add(y); }
			}
			if (xs.isEmpty()) continue;
			plot.setColor((Color) item[2]); plot.addPoints(array(xs), array(ys), Plot.CONNECTED_CIRCLES);
			legend.append(legend.length() == 0 ? "" : "\n").append(item[0]).append(item[1].equals("excess") ? " - noise-corrected excess (estimate)" : raw);
		}
		plot.setColor(Color.BLACK); plot.setLineWidth(1);
		plot.drawDottedLine(run.settings.step, run.settings.maximumNrmse, run.settings.maximumStep * 1.05, run.settings.maximumNrmse, 2);
		if (legend.length() > 0) plot.addLegend(legend.toString());
		qualityPlot = plot.show();
	}
	void model(AliasingAdvisorSettings s) {
		if (modelPlot != null) modelPlot.close();
		AliasingStepModel.Support support = AliasingStepModel.support(s);
		double[] x = new double[81], absolute = new double[81], relative = new double[81];
		for (int i = 0; i < x.length; i++) {
			x[i] = s.minimumStep + (s.maximumStep - s.minimumStep) * i / 80;
			AliasingStepModel.Prediction p = AliasingStepModel.predict(s, support, x[i]);
			absolute[i] = p.absoluteMotion; relative[i] = p.relativeMotion;
		}
		Plot plot = new Plot("Aliasing conditional motion estimate", "Acquired scan step (um)", "Motion transfer (model)");
		plot.setColor(Color.BLUE); plot.addPoints(x, absolute, Plot.LINE);
		plot.setColor(Color.RED); plot.addPoints(x, relative, Plot.LINE);
		plot.addLegend("Absolute at Ky\nMinimum relative to baseline over 0..Ky"); modelPlot = plot.show();
		ij.measure.ResultsTable table = new ij.measure.ResultsTable();
		for (double step : x) {
			AliasingStepModel.Prediction p = AliasingStepModel.predict(s, support, step); table.incrementCounter();
			table.addValue("Step_um", step); table.addValue("Plane_normal_spacing_um", p.normalSpacing); table.addValue("Raw_target_um", p.finePitch);
			table.addValue("Planes", p.planes); table.addValue("Center_span_um", p.span); table.addValue("Overshoot_um", p.overshoot);
			table.addValue("Exposure_ms", p.exposureMs); table.addValue("Frame_period_ms", p.framePeriodMs);
			table.addValue("Volume_seconds_estimate", p.seconds); table.addValue("Speedup_estimate", p.speedup);
			table.addValue("Absolute_motion_MTF", p.absoluteMotion); table.addValue("Relative_motion_MTF", p.relativeMotion);
			table.addValue("Status", p.status + (s.timingVerified ? "" : "; timing unverified"));
		}
		table.show("Aliasing conditional acquisition estimates");
	}
	/** What the raw error holds for this reference, and what the estimates rest on. */
	static String noiseNote(AliasingQualitySweep.Run run) {
		if (run.settings.syntheticNoiseFree) return "Noise-free reference: the raw NRMSE is the structural error.";
		return "Raw NRMSE also holds the reference's and the reconstruction's noise, so a Pass is conservative. Excess, increase and "
				+ "noise share are estimates, not acceptances (" + run.noise.status + ").";
	}
	private static String html(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
	private static double[] array(List<Double> values) { double[] a = new double[values.size()]; for (int i = 0; i < a.length; i++) a[i] = values.get(i); return a; }
	static String fmt(double x) { return Double.isFinite(x) ? String.format(Locale.ROOT, "%.5g", x) : "N/A"; }

	/** Camera-Y/scan sections put all methods at the same position without window-link races. */
	static ImagePlus[] preview(AliasingQualitySweep.Run run, int m, int phase) {
		AliasingAdvisorSettings s = run.settings;
		if (phase < 0 || phase >= m || m * s.refinement > 64) throw new IllegalArgumentException("Invalid preview phase/factor");
		boolean tested = false;
		for (AliasingQualitySweep.Aggregate a : run.aggregates) if (a.factor == m && a.phases == m) tested = true;
		if (!tested) throw new IllegalArgumentException("Select a completed tested factor");
		ImagePlus sparse = AliasingReconstruction.decimate(run.snapshot.image, m, phase), fourier = null, linear = null;
		try {
			fourier = AliasingReconstruction.reconstruct(sparse, AliasingQualitySweep.core(s, m));
			linear = AliasingReconstruction.interpolate(sparse, m * s.refinement);
			int depth = fourier.getStackSize(), w = s.width, h = s.height;
			ImageStack compare = new ImageStack(3 * w, h), errors = new ImageStack(w, h);
			float[] section = new float[3 * h * depth], errorSection = new float[h * depth];
			double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, maxError = 0;
			for (int k = 0; k < depth; k++) {
				AliasingReconstruction.checkCancelled();
				int denseLo = phase + k / s.refinement, denseHi = Math.min(denseLo + 1, s.depth - 1);
				float[] a = (float[]) run.snapshot.image.getStack().getPixels(denseLo + 1);
				float[] b = (float[]) run.snapshot.image.getStack().getPixels(denseHi + 1);
				float[] f = (float[]) fourier.getStack().getPixels(k + 1), l = (float[]) linear.getStack().getPixels(k + 1);
				float fraction = (k % s.refinement) / (float) s.refinement;
				float[] panel = new float[3 * w * h], error = new float[w * h];
				for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
					int i = y * w + x;
					float reference = a[i] * (1 - fraction) + b[i] * fraction;
					panel[y * 3 * w + x] = reference; panel[y * 3 * w + w + x] = l[i]; panel[y * 3 * w + 2 * w + x] = f[i];
					error[i] = k % s.refinement == 0 ? f[i] - a[i] : Float.NaN;
					min = Math.min(min, Math.min(reference, Math.min(l[i], f[i]))); max = Math.max(max, Math.max(reference, Math.max(l[i], f[i])));
					if (Float.isFinite(error[i])) maxError = Math.max(maxError, Math.abs(error[i]));
					if (x == w / 2) {
						section[k * 3 * h + y] = reference; section[k * 3 * h + h + y] = l[i]; section[k * 3 * h + 2 * h + y] = f[i];
						errorSection[k * h + y] = error[i];
					}
				}
				compare.addSlice(new FloatProcessor(3 * w, h, panel)); errors.addSlice(new FloatProcessor(w, h, error));
			}
			String tag = " m=" + m + " phase=" + phase + " D=" + fmt(m * s.step) + " um";
			ImagePlus[] out = {new ImagePlus("Reference | Linear | Fourier" + tag, compare),
				new ImagePlus("Signed error at dense planes (inserted=NaN)" + tag, errors),
				new ImagePlus("Camera-Y / scan: Reference | Linear | Fourier" + tag, new FloatProcessor(3 * h, depth, section)),
				new ImagePlus("Camera-Y / scan signed error (inserted=NaN)" + tag, new FloatProcessor(h, depth, errorSection))};
			for (int i = 0; i < out.length; i++) {
				out[i].setProperty(AliasingQualitySweep.OUTPUT, "preview");
				out[i].setProperty("Info", "Source SHA256=" + run.snapshotSha256 + "; " + tag + "; target=" + s.step / s.refinement
						+ " um. Reference inserted planes are linearly displayed, not independent truth. XYZ resolution not measured.");
				out[i].setDisplayRange(i % 2 == 0 ? min : -maxError, i % 2 == 0 ? max : maxError);
				if (i < 2) out[i].setCalibration(fourier.getCalibration().copy());
			}
			return out;
		} finally { sparse.flush(); if (fourier != null) fourier.flush(); if (linear != null) linear.flush(); }
	}
	void showPreview(ImagePlus[] images) {
		for (ImagePlus previous : previews) { previous.changes = false; previous.close(); previous.flush(); }
		previews.clear();
		for (ImagePlus image : images) { previews.add(image); image.show(); }
	}
	static void export(AliasingQualitySweep.Run run, Path folder) throws java.io.IOException {
		Files.createDirectories(folder);
		String stem = "aliasing-advisor-" + java.time.Instant.now().toString().replace(':', '-');
		AliasingQualitySweep.NoiseModel.Fit fit = run.noise.used(), check = run.noise.usedCheck();
		StringBuilder csv = new StringBuilder("factor,step_um,phase,method,heldout_nrmse,heldout_excess_estimate,heldout_excess_band_check,"
				+ "noise_floor_share_estimate,acquired_nrmse,bias,correlation,heldout_voxels,seconds,padding,fft_rows,fft_scan\n");
		for (AliasingQualitySweep.Row r : run.rows) {
			AliasingReconstruction.checkCancelled();
			csv.append(r.factor).append(',').append(r.stepUm).append(',').append(r.phase).append(',').append(r.method).append(',')
				.append(number(r.heldout.nrmse())).append(',').append(number(r.heldout.excess(fit))).append(',').append(number(r.heldout.excess(check))).append(',')
				.append(number(r.heldout.noiseShare(fit))).append(',')
				.append(number(r.acquired.nrmse())).append(',').append(number(r.heldout.bias())).append(',')
				.append(number(r.heldout.correlation())).append(',').append(r.heldout.count).append(',').append(r.seconds).append(',')
				.append(r.padding).append(',').append(r.fftRows).append(',').append(r.fftScan).append('\n');
		}
		StringBuilder factors = new StringBuilder("factor,step_um,method,phases,heldout_nrmse,worst_phase_nrmse,ceiling,verdict,excess_estimate,"
				+ "excess_band_check,worst_phase_excess_estimate,increase_over_factor2_estimate,noise_floor_share_estimate,acquired_nrmse,bias,correlation\n");
		for (AliasingQualitySweep.Aggregate a : run.aggregates)
			factors.append(a.factor).append(',').append(a.factor * run.settings.step).append(',').append(a.method).append(',').append(a.phases).append(',')
				.append(number(a.heldout.nrmse())).append(',').append(number(a.worstPhase)).append(',').append(run.settings.maximumNrmse).append(',')
				.append('"').append(a.verdict.replace("\"", "\"\"")).append('"').append(',')
				.append(number(a.excess)).append(',').append(number(a.excessCheck)).append(',').append(number(a.worstExcess)).append(',')
				.append(number(a.increase)).append(',').append(number(a.heldout.noiseShare(fit))).append(',').append(number(a.acquired.nrmse())).append(',')
				.append(number(a.heldout.bias())).append(',').append(number(a.heldout.correlation())).append('\n');
		JsonObject manifest = new GsonBuilder().serializeSpecialFloatingPointValues().create().toJsonTree(run).getAsJsonObject();
		manifest.addProperty("recommendation", run.recommendation());
		manifest.addProperty("estimate", run.estimate());
		manifest.addProperty("metrics", "Pooled SSE / fixed background-subtracted reference energy; all-phase common domain; signal mask from the "
				+ "smoothed reference; raw residuals neither gain-fitted nor clipped. Verdict: the criterion field. Estimates, never verdicts: the "
				+ "estimator field; increase = sqrt(max(0, excess^2 - excess(factor 2)^2)), an increase in estimated error energy, not all added "
				+ "error. XYZ resolution not assessed");
		manifest.addProperty("gpuCoverage", run.settings.gpu ? "Experimental existing CLIJ2-FFT backend; concurrent GPU jobs not validated" : "CPU JTransforms");
		JsonArray predictions = new JsonArray(); AliasingStepModel.Support support = AliasingStepModel.support(run.settings);
		Gson permissive = new GsonBuilder().serializeSpecialFloatingPointValues().create();
		manifest.add("support", permissive.toJsonTree(support));
		for (int m : AliasingStepModel.factors(run.settings)) predictions.add(permissive.toJsonTree(AliasingStepModel.predict(run.settings, support, m * run.settings.step)));
		manifest.add("conditionalPredictions", predictions); sanitize(manifest);
		Path json = folder.resolve(stem + ".json"), data = folder.resolve(stem + ".csv"), summary = folder.resolve(stem + "-factors.csv");
		Files.write(data, csv.toString().getBytes(StandardCharsets.UTF_8));
		Files.write(summary, factors.toString().getBytes(StandardCharsets.UTF_8));
		Files.write(json, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(manifest).getBytes(StandardCharsets.UTF_8));
	}
	private static String number(double value) { return Double.isFinite(value) ? Double.toString(value) : ""; }
	private static void sanitize(JsonElement value) {
		if (value.isJsonObject()) for (Map.Entry<String, JsonElement> e : value.getAsJsonObject().entrySet()) {
			if (nonfinite(e.getValue())) e.setValue(JsonNull.INSTANCE); else sanitize(e.getValue());
		} else if (value.isJsonArray()) {
			JsonArray a = value.getAsJsonArray();
			for (int i = 0; i < a.size(); i++) if (nonfinite(a.get(i))) a.set(i, JsonNull.INSTANCE); else sanitize(a.get(i));
		}
	}
	private static boolean nonfinite(JsonElement e) { return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() && !Double.isFinite(e.getAsDouble()); }
}
