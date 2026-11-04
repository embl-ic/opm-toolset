package de.embl.iclm;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.Roi;
import ij.io.DirectoryChooser;
import ij.io.OpenDialog;
import ij.plugin.PlugIn;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.Timer;

/** Persistent, nonblocking raw-volume acquisition advisor. No hardware control. */
public class AliasingStepAdvisor implements PlugIn {
	AliasingAdvisorSettings settings;
	OpmDialog dialog;
	final Map<String, Component> controls = new LinkedHashMap<String, Component>();
	private final Map<String, String> displayed = new LinkedHashMap<String, String>();
	private final AliasingAdvisorView view = new AliasingAdvisorView();
	private final List<ImagePlus> sources = new ArrayList<ImagePlus>();
	private final List<String> metadataPaths = new ArrayList<String>();
	private Choice sourceChoice, metadataChoice;
	private TextArea summary;
	private javax.swing.JLabel metadataStatus;
	private File pendingMetadata;
	private boolean pendingPreserve;
	private Timer debounce;
	private ImagePlus source;
	private Shutdown.Operation operation;
	private AliasingQualitySweep.Run lastRun;
	private long revision, resultRevision = -1;
	private boolean closed, updating;
	private final boolean persist;

	public AliasingStepAdvisor() { this(AliasingAdvisorSettings.load(), true); }
	AliasingStepAdvisor(AliasingAdvisorSettings settings, boolean persist) { this.settings = settings; this.persist = persist; }
	@Override public void run(String arg) {
		Debug.commandStarted("Aliasing Resampling > Z-Step Advisor");
		try {
			buildDialog(WindowManager.getCurrentImage());
			EventQueue.invokeLater(() -> {
				updateEstimate(false);
				File sidecar = AliasingMetadata.discover(source), startup = startupMetadata(sidecar, settings.metadataPath);
				// the saved file keeps the manual overrides made over it
				if (startup != null) loadMetadata(startup, startup != sidecar);
			});
			dialog.showDialog();
			EventQueue.invokeLater(this::close);
		} catch (RuntimeException failure) { IJ.handleException(failure); }
	}

	OpmDialog buildDialog(ImagePlus initial) {
		dialog = new OpmDialog("Aliasing Resampling - Z-step and quality advisor");
		dialog.addMessage("Acquired scan step, reconstructed pitch and Cartesian Z voxel size are different.\n"
				+ "Models are conditional; digital subset tests do not simulate faster exposure motion.");
		summary = new TextArea("Ready", 4, 86, TextArea.SCROLLBARS_VERTICAL_ONLY); summary.setEditable(false);
		Panel strip = new Panel(new BorderLayout(3, 3)); strip.add(summary, BorderLayout.CENTER);
		Panel actions = new Panel(new FlowLayout(FlowLayout.LEFT, 4, 2));
		button(actions, "Update estimate", () -> updateEstimate(true));
		button(actions, "Test reference ROI", this::testReference);
		button(actions, "Preview selected step", this::preview);
		button(actions, "Export CSV + JSON", this::export);
		button(actions, "Stop", () -> { if (operation != null) { Shutdown.cancel(operation); status("Cancelling; waiting for worker cleanup..."); } });
		strip.add(actions, BorderLayout.SOUTH); dialog.addPanel(strip);
		Panel files = new Panel(new FlowLayout(FlowLayout.LEFT)); files.add(new Label("ExperimentalParameter(s).txt:"));
		metadataChoice = new Choice(); files.add(metadataChoice);
		button(files, "Browse", () -> { OpenDialog open = new OpenDialog("Acquisition metadata", null); if (open.getPath() != null) loadMetadata(new File(open.getPath()), false); });
		button(files, "Reload", () -> { if (!settings.metadataPath.isEmpty()) loadMetadata(new File(settings.metadataPath), false); else status("Choose acquisition metadata first"); });
		dialog.addPanel(files);
		metadataStatus = new javax.swing.JLabel("Manual values; verify source association"); dialog.addPanel(wrap(metadataStatus));
		metadataChoice.addItemListener(e -> {
			if (updating || e.getStateChange() != ItemEvent.SELECTED) return;
			revision++; // invalidate an older parse even if the worker is still finishing
			int i = metadataChoice.getSelectedIndex();
			if (i == 0) { settings.metadataPath = ""; settings.metadataHash = ""; setCheck("geometryConfirmed", false); edited("metadata"); metadataStatus.setText("Manual; verify geometry"); }
			else if (i == 1) { File file = AliasingMetadata.discover(source); if (file == null) status("No sidecar found beside the selected source or its parent"); else loadMetadata(file, false); }
			else loadMetadata(new File(metadataPaths.get(i)), false);
		});
		Parameter.addSection(dialog, "Input and geometry:");
		Panel sourcePanel = new Panel(new FlowLayout(FlowLayout.LEFT)); sourcePanel.add(new Label("Raw reference:")); sourceChoice = new Choice(); sourcePanel.add(sourceChoice);
		button(sourcePanel, "Refresh", () -> refreshSources(source));
		button(sourcePanel, "Use current ROI", this::useRoi); dialog.addPanel(sourcePanel);
		number("pixel", "Specimen camera pixel (um)"); number("angle", "OPM angle (degrees)"); number("step", "Dense reference scan step (um)"); number("extent", "Acquisition center span (um)");
		check("geometryConfirmed", "Geometry and original acquisition association verified");
		check("rawConfirmed", "Selected reference is unchanged, static RAW data (one C/T)");
		Parameter.addSection(dialog, "Reference ROI (zero-based):");
		for (String key : new String[] {"x", "y", "z", "width", "height", "depth"}) number(key, "ROI " + key);
		dialog.addMessage("A new source's default box sits in the left half of a frame wide enough for two (a dual-view\n"
				+ "camera is assumed). For a single view, draw an ROI on the structure and press Use current ROI.");
		Parameter.addSection(dialog, "Optics and evidence:");
		choice("optics", "Support source", "Illustrative NA / sheet Gaussian", "Effective Gaussian widths (approximate)", "Entered OTF support");
		number("na", "Effective detection NA"); number("wavelengthNm", "Emission wavelength (nm)"); number("refractiveIndex", "Refractive index"); number("sheetFwhm", "Sheet-normal intensity FWHM (um)");
		number("fwhmX", "Effective Gaussian FWHM X (um)"); number("fwhmY", "Effective Gaussian FWHM Y (um)"); number("fwhmZ", "Effective Gaussian FWHM Z (um)");
		number("kz", "Entered Kz (cycles/um)"); number("ky", "Entered Ky (cycles/um)"); check("supportMeasured", "Entered support measured with matched channel / geometry");
		number("threshold", "OTF amplitude threshold (fraction of DC)"); number("safety", "Fraction of conditional alias bound");
		dialog.addMessage("NA and sheet defaults are assumed. Widths do not identify a full 3D OTF.\nXYZ bead resolution and changed-motion PSF simulation: not assessed here.");
		Parameter.addSection(dialog, "Step range and reconstruction:");
		check("rangeFollowsStep", "Range follows reference step (1x to 4x)"); number("minimumStep", "Minimum acquired scan step (um)"); number("maximumStep", "Maximum acquired scan step (um)");
		number("refinement", "Reference refinement r (target = d0/r)"); check("paddingFollowsGeometry", "Padding minima follow geometry (16*d0, 32*p)");
		number("scanPadding", "Minimum physical scan padding (um)"); number("rowPadding", "Minimum camera-row padding (um)"); number("taper", "Cosine edge fraction");
		check("gpu", "Use experimental CLIJ2-FFT (other GPU operations must be idle)");
		number("previewFactor", "Preview subset factor m"); number("previewPhase", "Preview phase (0 to m-1)");
		Parameter.addSection(dialog, "Exposure and scan motion:");
		choice("scanMode", "Scan mode", "Continuous (assumed until verified)", "Step-and-hold during exposure", "Unknown / unsupported timing");
		number("exposureMs", "Baseline exposure (ms)"); check("measuredPeriod", "Use entered baseline frame period"); number("periodMs", "Baseline frame period (ms)");
		number("overheadMs", "Non-overlapping frame overhead (ms; 0 if unknown)"); number("flybackMs", "Volume flyback (ms; 0 if unknown)");
		check("fixedVelocity", "Keep scan velocity fixed (otherwise keep frame period fixed)"); check("preserveBlur", "Gate exposure to preserve baseline blur length"); check("timingVerified", "Scan trajectory / timing verified for this acquisition");
		number("relativeMotionFloor", "Minimum transfer relative to baseline"); number("baselineMotionFloor", "Minimum baseline transfer for a usable ratio");
		check("gateAbsoluteMotion", "Also require absolute motion-transfer floor"); number("absoluteMotionFloor", "Absolute motion-transfer floor");
		Parameter.addSection(dialog, "Quality criteria and background:");
		number("background", "Camera background (ADU)"); number("noiseSigma", "Background noise sigma (ADU)"); check("noiseKnown", "Background and noise estimate applicable / verified"); check("syntheticNoiseFree", "Reference is synthetic and noise-free (background still applies)");
		Panel background = new Panel(new FlowLayout(FlowLayout.LEFT)); button(background, "Estimate from selected image's background ROI", this::estimateBackground); dialog.addPanel(background);
		number("maskFraction", "Mask fraction of reference P99.5 residual"); number("sigmaGate", "Mask minimum background-noise sigma multiplier"); number("minimumSignal", "Minimum foreground voxels (also need 3 planes)");
		number("guard", "Boundary guard (original dense samples per axis)");
		number("maximumNrmse", "Acceptance: raw held-out NRMSE ceiling (pooled and every phase)");
		number("maximumIncrement", "Estimate only: report error increase over factor 2 up to");
		dialog.addMessage("All phases are tested on a common interior. Raw residuals are neither gain-fitted nor clipped, and\n"
				+ "noise only adds to them, so the raw ceiling accepts conservatively. The noise-corrected estimates\n"
				+ "subtract a fitted floor and truncate at zero; they are reported, never used to accept a step.\n"
				+ "Unknown background / geometry gives exploratory scores, not a tested recommendation.");
		Panel defaults = new Panel(new FlowLayout(FlowLayout.LEFT)); button(defaults, "Reset advisor defaults", () -> {
			if (busy()) return; settings = new AliasingAdvisorSettings(); revision++; fill(); refreshMetadata(); updateEstimate(false);
		}); dialog.addPanel(defaults);
		dialog.setOKLabel("Close"); dialog.hideCancelButton(); dialog.addHelp(Help.aliasingResampling);
		Parameter.styleDialog(dialog);
		debounce = new Timer(300, e -> updateEstimate(false)); debounce.setRepeats(false);
		sourceChoice.addItemListener(e -> {
			if (updating || e.getStateChange() != ItemEvent.SELECTED) return;
			selectSource(sources.get(sourceChoice.getSelectedIndex()), false);
			File metadata = AliasingMetadata.discover(source); if (metadata != null) loadMetadata(metadata, false);
		});
		refreshSources(initial); refreshMetadata(); fill();
		dialog.addWindowListener(new WindowAdapter() { @Override public void windowClosed(WindowEvent e) { close(); } });
		return dialog;
	}

	/**
	 * The metadata to load as the advisor opens. The open image's own acquisition wins over the file
	 * used last time; the saved file - returned as itself, not as the sidecar - only when it is that
	 * same sidecar or there is none.
	 */
	static File startupMetadata(File sidecar, String saved) {
		if (sidecar != null && !canonical(sidecar).equals(saved)) return sidecar;
		return saved.isEmpty() ? null : new File(saved);
	}
	private static String canonical(File file) {
		try { return file.getCanonicalPath(); } catch (java.io.IOException e) { return file.getAbsolutePath(); }
	}
	private static Panel wrap(Component component) { Panel p = new Panel(new FlowLayout(FlowLayout.LEFT)); p.add(component); return p; }
	private static void button(Panel panel, String label, Runnable action) {
		Button button = new Button(label); button.addActionListener(e -> action.run()); panel.add(button);
	}
	private Field field(String key) { try { return AliasingAdvisorSettings.class.getDeclaredField(key); } catch (Exception e) { throw new IllegalStateException(e); } }
	private void number(String key, String label) {
		try {
			Field field = field(key); dialog.addNumericField(label, ((Number)field.get(settings)).doubleValue(), field.getType() == int.class ? 0 : 4);
			TextField input = (TextField)dialog.getNumericFields().lastElement(); controls.put(key, input); input.setName(key);
			input.addTextListener(e -> { if (!input.getText().equals(displayed.get(key))) { displayed.put(key, input.getText()); edited(key); } });
		} catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
	}
	private void check(String key, String label) {
		try {
			dialog.addCheckbox(label, field(key).getBoolean(settings)); Checkbox input = (Checkbox)dialog.getCheckboxes().lastElement(); controls.put(key, input); input.setName(key);
			input.addItemListener(e -> edited(key));
		} catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
	}
	private void choice(String key, String label, String... choices) {
		try {
			dialog.addChoice(label, choices, choices[field(key).getInt(settings)]); Choice input = (Choice)dialog.getChoices().lastElement(); controls.put(key, input); input.setName(key);
			input.addItemListener(e -> { if (e.getStateChange() == ItemEvent.SELECTED) edited(key); });
		} catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
	}
	private void edited(String key) {
		if (updating || closed) return;
		if (!key.startsWith("preview")) revision++;
		if (key.equals("minimumStep") || key.equals("maximumStep")) setCheck("rangeFollowsStep", false);
		if (key.equals("scanPadding") || key.equals("rowPadding")) setCheck("paddingFollowsGeometry", false);
		if (key.equals("pixel") || key.equals("angle") || key.equals("step")) setCheck("geometryConfirmed", false);
		if (key.equals("background") || key.equals("noiseSigma")) setCheck("noiseKnown", false);
		settings.origins.put(key, "Manual"); settings.evidence.put(key, "User-entered; applicability must be verified");
		if (lastRun != null && resultRevision != revision) status("Inputs changed: previous results are stale; run the reference test again");
		if (debounce != null) debounce.restart();
	}
	private void setCheck(String key, boolean value) { Component c = controls.get(key); if (c instanceof Checkbox) ((Checkbox)c).setState(value); }
	AliasingAdvisorSettings read() {
		AliasingAdvisorSettings copy = settings.copy();
		for (Map.Entry<String, Component> entry : controls.entrySet()) {
			Field field = field(entry.getKey()); Component input = entry.getValue();
			try {
				if (input instanceof TextField) {
					double value; try { value = Double.parseDouble(((TextField)input).getText().trim()); }
					catch (NumberFormatException invalid) { throw new IllegalArgumentException("Complete the " + entry.getKey() + " field"); }
					if (field.getType() == int.class) field.setInt(copy, AliasingResampling.integer(value, entry.getKey())); else field.setDouble(copy, value);
				} else if (input instanceof Checkbox) field.setBoolean(copy, ((Checkbox)input).getState());
				else if (input instanceof Choice) field.setInt(copy, ((Choice)input).getSelectedIndex());
			} catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
		}
		copy.derived(); copy.validate(); return copy;
	}
	private void fill() {
		updating = true;
		try {
			for (Map.Entry<String, Component> entry : controls.entrySet()) {
				Object value = field(entry.getKey()).get(settings); Component input = entry.getValue();
				if (input instanceof TextField) { String text = value.toString(); displayed.put(entry.getKey(), text); ((TextField)input).setText(text); }
				else if (input instanceof Checkbox) ((Checkbox)input).setState((Boolean)value);
				else if (input instanceof Choice) ((Choice)input).select((Integer)value);
			}
			for (String key : new String[] {"na", "wavelengthNm", "refractiveIndex", "sheetFwhm"}) controls.get(key).setEnabled(settings.optics == 0);
			for (String key : new String[] {"fwhmX", "fwhmY", "fwhmZ"}) controls.get(key).setEnabled(settings.optics == 1);
			for (String key : new String[] {"kz", "ky", "supportMeasured"}) controls.get(key).setEnabled(settings.optics == 2);
		} catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
		finally { updating = false; }
	}
	private void refreshSources(ImagePlus selected) {
		updating = true; sources.clear(); sourceChoice.removeAll(); sources.add(null); sourceChoice.add("None (model only)");
		int[] ids = WindowManager.getIDList();
		if (ids != null) for (int id : ids) {
			ImagePlus image = WindowManager.getImage(id);
			if (image != null && image.getProperty(AliasingQualitySweep.OUTPUT) == null) { sources.add(image); sourceChoice.add(image.getTitle()); }
		}
		int selectedIndex = sources.indexOf(selected); sourceChoice.select(Math.max(0, selectedIndex)); updating = false;
		selectSource(sources.get(sourceChoice.getSelectedIndex()), true);
	}
	private void selectSource(ImagePlus image, boolean preserveMatchingRoi) {
		if (source == image && source != null) return;
		try { if (!controls.isEmpty()) settings = read(); } catch (RuntimeException invalidEditor) { /* retain last valid settings */ }
		source = image; revision++; settings.rawConfirmed = false; settings.geometryConfirmed = false;
		String path = sourcePath(image);
		if (image != null && (!preserveMatchingRoi || path.isEmpty() || !path.equals(settings.sourcePath)
				|| (long)settings.x + settings.width > image.getWidth() || (long)settings.y + settings.height > image.getHeight()
				|| (long)settings.z + settings.depth > image.getStackSize())) {
			settings.width = Math.min(256, image.getWidth()); settings.height = Math.min(128, image.getHeight()); settings.depth = Math.min(192, image.getStackSize());
			// A raw OPM frame is usually two camera halves side by side; a centred box would straddle
			// both channels and the gap between them, so centre it in the left half when it fits there.
			int across = image.getWidth() >= 2 * settings.width ? image.getWidth() / 2 : image.getWidth();
			settings.x = (across - settings.width) / 2; settings.y = (image.getHeight() - settings.height) / 2; settings.z = (image.getStackSize() - settings.depth) / 2;
		}
		settings.sourcePath = path; fill();
	}
	private static String sourcePath(ImagePlus image) {
		if (image == null || image.getOriginalFileInfo() == null || image.getOriginalFileInfo().directory == null) return "";
		return new File(image.getOriginalFileInfo().directory, image.getOriginalFileInfo().fileName == null ? image.getTitle() : image.getOriginalFileInfo().fileName).getAbsolutePath();
	}
	private void useRoi() {
		if (source == null || busy()) return;
		try {
			settings = read(); Roi roi = source.getRoi(); Rectangle bounds = roi == null ? new Rectangle(0, 0, source.getWidth(), source.getHeight()) : roi.getBounds();
			settings.x = bounds.x; settings.y = bounds.y; settings.width = bounds.width; settings.height = bounds.height;
			settings.z = Math.max(0, Math.min(source.getStackSize() - settings.depth, source.getCurrentSlice() - 1 - settings.depth / 2));
			revision++; fill(); updateEstimate(false);
		} catch (RuntimeException failure) { status(failure.getMessage()); }
	}
	private void refreshMetadata() {
		updating = true; metadataChoice.removeAll(); metadataPaths.clear();
		metadataChoice.add("Manual"); metadataPaths.add(""); metadataChoice.add("Selected image sidecar"); metadataPaths.add("");
		for (String path : settings.recentMetadata) {
			metadataPaths.add(path); File file = new File(path); String parent = file.getParentFile() == null ? "" : file.getParentFile().getName();
			if (parent.length() > 24) parent = parent.substring(0, 21) + "...";
			metadataChoice.add((metadataPaths.size() - 2) + ": " + parent + " / " + file.getName());
		}
		int selected = metadataPaths.indexOf(settings.metadataPath); metadataChoice.select(Math.max(0, selected)); updating = false;
	}
	private void loadMetadata(File file, boolean preserveUnchanged) {
		if (closed) return;
		if (operation != null) {
			revision++; pendingMetadata = file; pendingPreserve = preserveUnchanged; Shutdown.cancel(operation);
			status("Stopping current work, then loading selected metadata: " + file.getName()); return;
		}
		final long token = revision;
		final ImagePlus selected = source;
		AtomicReference<AliasingMetadata.Parsed> parsed = new AtomicReference<AliasingMetadata.Parsed>();
		start("Read acquisition metadata", () -> {
			try { parsed.set(AliasingMetadata.parse(file)); } catch (java.io.IOException failure) { throw new IllegalArgumentException(failure.getMessage(), failure); }
		}, done -> {
			if (closed) return;
			if (done.getFailure() != null) { settings.geometryConfirmed = false; setCheck("geometryConfirmed", false); status("Metadata unavailable: " + done.getFailure().getMessage()); return; }
			if (done.isCancelled()) return;
			if (token != revision || selected != source) { status("Metadata result discarded after edits/source change; press Reload"); return; }
			try {
				AliasingMetadata.Parsed result = parsed.get(); AliasingAdvisorSettings candidate = read();
				if (!(preserveUnchanged && result.path.equals(candidate.metadataPath) && result.hash.equals(candidate.metadataHash)))
					result.apply(candidate, selected == null ? -1 : selected.getStackSize());
				File associated = AliasingMetadata.discover(selected);
				if (associated == null || !associated.getAbsolutePath().equalsIgnoreCase(result.path)) candidate.geometryConfirmed = false;
				candidate.validate(); settings = candidate; revision++; fill(); refreshMetadata(); save();
				String message = result.path + " | fields: " + result.fields.keySet() + " " + result.warnings;
				metadataStatus.setText("Metadata: " + file.getName() + " — " + (settings.geometryConfirmed ? "plane count matches" : "verify geometry / association"));
				metadataStatus.setToolTipText(message);
				IJ.log("Z-Step Advisor: " + message);
				if (result.planes != null && selected != null && result.planes != selected.getStackSize()) IJ.log("Z-Step Advisor: plane count mismatch; raw reference pitch is unverified.");
				updateEstimate(false);
			} catch (RuntimeException failure) { status("Metadata patch rejected: " + failure.getMessage()); }
		});
	}
	void updateEstimate(boolean plots) {
		if (closed) return;
		try {
			settings = read(); updateDerivedEditors();
			AliasingStepModel.Support support = AliasingStepModel.support(settings);
			AliasingStepModel.Prediction p = AliasingStepModel.predict(settings, support, settings.maximumStep);
			String tested = lastRun == null ? "Reference: not tested" : resultRevision != revision ? "Reference results STALE after edits" : lastRun.recommendation();
			status(AliasingStepModel.range(settings, support) + "; " + support.description + "\nConditional alias cap: " + AliasingAdvisorView.fmt(support.aliasCap) + " um; threshold 0.10 cap: " + AliasingAdvisorView.fmt(support.sensitivityCap)
					+ "; Kz=" + AliasingAdvisorView.fmt(support.kz) + " cycles/um\nAt max step: " + p.planes + " planes, " + AliasingAdvisorView.fmt(p.seconds) + " s, " + AliasingAdvisorView.fmt(p.speedup)
					+ "x timing estimate; absolute/relative motion=" + AliasingAdvisorView.fmt(p.absoluteMotion) + "/" + AliasingAdvisorView.fmt(p.relativeMotion)
					+ "\n" + p.status + (settings.timingVerified ? "" : "; timing unverified") + ". " + tested);
			if (plots) { view.model(settings); save(); }
		} catch (RuntimeException failure) { status("Estimate stale: " + failure.getMessage()); }
	}
	private void testReference() {
		if (busy() || closed) return;
		try {
			settings = read(); AliasingStepModel.factors(settings); AliasingQualitySweep.preflight(settings);
			if (source == null) throw new IllegalArgumentException("Select an open raw reference volume");
			if (settings.gpu && !Shutdown.running().isEmpty()) throw new IllegalArgumentException("Use CPU or wait for other operations before an experimental GPU sweep");
			final ImagePlus selected = source; final AliasingAdvisorSettings frozen = settings.copy(); final long token = revision;
			if (lastRun != null) { lastRun.release(); lastRun = null; }
			AtomicReference<AliasingQualitySweep.Run> result = new AtomicReference<AliasingQualitySweep.Run>(); save();
			start("Aliasing reference sweep", () -> {
				AliasingQualitySweep.Run run = new AliasingQualitySweep.Run(AliasingQualitySweep.snapshot(selected, frozen), frozen); result.set(run);
				AliasingQualitySweep.execute(run, message -> EventQueue.invokeLater(() -> { if (!closed && token == revision) status(message + " — Stop requests cooperative cancellation"); }));
			}, done -> {
				AliasingQualitySweep.Run run = result.get();
				if (closed || token != revision) { if (run != null) run.release(); if (!closed) status("Discarded sweep after input edits; test again"); return; }
				if (run != null) { lastRun = run; resultRevision = token; view.show(run, this::selectFactor); status(run.recommendation() + "\n" + run.estimate()); }
				if (done.getFailure() != null) status("Reference test failed: " + done.getFailure().getMessage());
			});
		} catch (RuntimeException failure) { status(failure.getMessage()); }
	}
	private void selectFactor(int factor) {
		settings.previewFactor = factor; settings.previewPhase = 0;
		writeNumber("previewFactor", factor); writeNumber("previewPhase", 0);
	}
	private void writeNumber(String key, Number value) {
		String text = value.toString(); displayed.put(key, text); ((TextField)controls.get(key)).setText(text);
	}
	private void updateDerivedEditors() {
		if (settings.rangeFollowsStep) { writeNumber("minimumStep", settings.minimumStep); writeNumber("maximumStep", settings.maximumStep); }
		if (settings.paddingFollowsGeometry) { writeNumber("scanPadding", settings.scanPadding); writeNumber("rowPadding", settings.rowPadding); }
		for (String key : new String[] {"na", "wavelengthNm", "refractiveIndex", "sheetFwhm"}) controls.get(key).setEnabled(settings.optics == 0);
		for (String key : new String[] {"fwhmX", "fwhmY", "fwhmZ"}) controls.get(key).setEnabled(settings.optics == 1);
		for (String key : new String[] {"kz", "ky", "supportMeasured"}) controls.get(key).setEnabled(settings.optics == 2);
	}
	private void preview() {
		if (!currentResult() || busy()) return;
		try {
			settings = read(); final int m = settings.previewFactor, phase = settings.previewPhase; final long token = revision;
			AliasingQualitySweep.Run run = lastRun; AtomicReference<ImagePlus[]> images = new AtomicReference<ImagePlus[]>();
			start("Aliasing preview", () -> images.set(AliasingAdvisorView.preview(run, m, phase)), done -> {
				if (images.get() != null) {
					if (closed || token != revision || done.isCancelled()) for (ImagePlus image : images.get()) image.flush();
					else view.showPreview(images.get());
				}
				if (done.getFailure() != null && !closed) status(done.getFailure().getMessage());
			});
		} catch (RuntimeException failure) { status(failure.getMessage()); }
	}
	private void estimateBackground() {
		if (busy() || source == null) return;
		Roi roi = source.getRoi(); final Rectangle bounds = roi == null ? null : roi.getBounds();
		final ImagePlus selected = source; final long token = revision; AtomicReference<double[]> result = new AtomicReference<double[]>();
		final int plane = source.getCurrentSlice();
		start("Measure background ROI", () -> result.set(AliasingQualitySweep.background(selected, bounds, plane)), done -> {
			if (done.getFailure() != null) { status(done.getFailure().getMessage()); return; }
			if (closed || token != revision || done.isCancelled()) return;
			try {
				settings = read(); settings.background = result.get()[0]; settings.noiseSigma = result.get()[1]; settings.noiseKnown = true; settings.syntheticNoiseFree = false;
				settings.origins.put("background", "Selected background ROI " + bounds + "; plane (1-based)=" + plane);
				settings.origins.put("noiseSigma", "Gaussian-consistent MAD in selected background ROI; plane=" + plane);
				settings.evidence.put("background", "Estimated from selected background ROI"); settings.evidence.put("noiseSigma", "Estimated; Gaussian noise assumption");
				revision++; fill(); save(); updateEstimate(false);
			} catch (RuntimeException failure) { status(failure.getMessage()); }
		});
	}
	private void export() {
		if (!currentResult() || busy()) return;
		DirectoryChooser chooser = new DirectoryChooser("Export advisor CSV and JSON");
		if (!settings.exportDirectory.isEmpty()) DirectoryChooser.setDefaultDirectory(settings.exportDirectory);
		String folder = chooser.getDirectory(); if (folder == null) return;
		settings.exportDirectory = folder; save(); final AliasingQualitySweep.Run run = lastRun;
		start("Export advisor results", () -> { try { AliasingAdvisorView.export(run, Paths.get(folder)); } catch (java.io.IOException failure) { throw new IllegalStateException(failure); } },
				done -> { if (!closed) status(done.isCancelled() ? "Export cancelled; inspect destination for partial output" : done.getFailure() == null ? "Exported CSV and JSON to " + folder : "Export failed: " + done.getFailure().getMessage()); });
	}
	private boolean currentResult() {
		if (lastRun == null || resultRevision != revision) { status("Run a reference test with the current settings first"); return false; } return true;
	}
	private boolean busy() {
		if (operation != null) { status(operation.isCancelled() ? "Cancelling; wait for cleanup" : "An operation is running; Stop or wait for completion"); return true; } return false;
	}
	void start(String name, Runnable work, Consumer<Shutdown.Operation> completion) {
		if (closed || busy()) return;
		status(name + "...");
		operation = Shutdown.startCancellable(name, work, done -> EventQueue.invokeLater(() -> {
			operation = null;
			if (done.isCancelled()) status("Cancelled; worker cleanup finished");
			try { completion.accept(done); }
			finally {
				if (closed && lastRun != null) { lastRun.release(); lastRun = null; }
				if (!closed && pendingMetadata != null) { File file = pendingMetadata; boolean preserve = pendingPreserve; pendingMetadata = null; loadMetadata(file, preserve); }
			}
		}));
	}
	private void status(String message) { if (!closed && summary != null) summary.setText(message == null ? "Unknown error" : message); }
	private void save() { if (persist) try { settings.save(); } catch (RuntimeException failure) { IJ.log("Advisor preferences: " + failure.getMessage()); } }
	private void close() {
		if (closed) return;
		try { settings = read(); } catch (RuntimeException invalidEditor) { /* retain last fully valid snapshot */ }
		save(); closed = true; if (debounce != null) debounce.stop();
		if (operation != null) Shutdown.cancel(operation);
		else if (lastRun != null) { lastRun.release(); lastRun = null; }
	}
}
