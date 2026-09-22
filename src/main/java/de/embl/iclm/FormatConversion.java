package de.embl.iclm;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.measure.Calibration;
import ij.plugin.ChannelSplitter;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;

import java.awt.AWTEvent;
import java.awt.Choice;
import java.awt.Label;
import java.awt.TextField;
import java.awt.event.TextEvent;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.apache.commons.io.FileUtils;

/**
 * Storage-format conversion: the pixels that come out are the pixels that went in.
 *
 * <p>The input is a folder found the way the OPM Data Viewer finds one ({@link DataFolder}) -
 * OME-Zarr datasets and deskewed TIFF results - plus every other TIFF volume under it, which in
 * practice are the raw acquisition BigTIFFs the microscope writes uncompressed. One TIFF can be
 * named instead of a folder, which is why the two single-file commands this used to sit beside
 * are gone. What each converts to:
 *
 * <ul>
 * <li>a TIFF volume that is not yet deflated: deflated TIFF, OME-Zarr, or both;</li>
 * <li>a deflated TIFF: OME-Zarr only - deflated to deflated would only copy it, so a
 * deflated-TIFF target skips it, and says so while the dialog is still open;</li>
 * <li>a deskewed TIFF result: one OME-Zarr over all of its time points, or its frames
 * deflated where they are not yet, keeping the names and layout the viewer reads;</li>
 * <li>an OME-Zarr dataset: deflated TIFF, one volume per time point with its projections, in
 * the layout the viewer reads as a TIFF result.</li>
 * </ul>
 *
 * <p><b>Nothing here deskews, flips or aligns.</b> A raw volume's OME-Zarr records
 * {@code opm.contentKind = "raw"} so nothing mistakes it for a deskew; an OME-Zarr made from a
 * TIFF result holds that result's channels as they are, with {@code alignApplied} set, because
 * whatever flip or alignment it had is already in its pixels. {@link OmeZarrConverter} remains
 * the path that deskews, and Channel Operation the one that re-aligns.
 *
 * <p>Deflate runs at level 1 and is not offered: this data compresses about as well at any
 * level, and a slower one only costs time.
 *
 * <p>Raw volumes are never held whole. Planes are pulled one at a time from
 * {@link FastTiffReader.PlaneReader} and pushed a batch or a slab at a time into
 * {@link FastTiffWriter} or {@link OmeZarrWriter}, which is the difference between converting
 * a 30 GB acquisition and failing to allocate one. A result's time point is opened whole, as the
 * deskew that wrote it already held it, because its projections are computed from it.
 *
 * <p>One command here reads in the other direction and is not a pure format change: exporting a
 * region of a canonical OME-Zarr as a deflated TIFF can bake the viewer's runtime views into
 * what it writes. Left at stored channels it too only changes the format.
 */
public class FormatConversion implements PlugIn {

	public static final String TARGET_TIFF = "deflated TIFF";
	public static final String TARGET_ZARR = "OME-Zarr";
	public static final String TARGET_BOTH = "deflated TIFF + OME-Zarr";
	public static final String[] TARGETS = { TARGET_TIFF, TARGET_ZARR, TARGET_BOTH };

	/** Suffix that keeps a converted TIFF from colliding with its own source. */
	static final String TIFF_SUFFIX = "-deflated";

	private static final String TITLE = "Batch Processing - Format Conversion";
	private static final String PREF = "opm.formatConversion.";

	/** What one conversion run should do. */
	public static final class Options {
		public String target = TARGET_TIFF;
		/** Always {@link FastTiffWriter#DEFAULT_LEVEL}; no dialog offers another any more. */
		public int deflateLevel = FastTiffWriter.DEFAULT_LEVEL;
		public boolean overwrite = false;
		public boolean tryGpu = true;
		/** Recorded in the OME-Zarr; 0 means "unknown", which is written as 1 µm. */
		public double xyPixelSizeUm = 0;
		public double zStepSizeUm = 0;

		public boolean wantsTiff() { return !TARGET_ZARR.equals(target); }
		public boolean wantsZarr() { return !TARGET_TIFF.equals(target); }

		/**
		 * The same options for one input, with a deflated TIFF target dropped when the input is
		 * already deflated; null when nothing is left to do for it.
		 */
		Options forInput(boolean alreadyDeflated) {
			if (!alreadyDeflated || !wantsTiff()) return this;
			if (!wantsZarr()) return null;
			Options zarrOnly = copy();
			zarrOnly.target = TARGET_ZARR;
			return zarrOnly;
		}

		Options copy() {
			Options copy = new Options();
			copy.target = target;
			copy.deflateLevel = deflateLevel;
			copy.overwrite = overwrite;
			copy.tryGpu = tryGpu;
			copy.xyPixelSizeUm = xyPixelSizeUm;
			copy.zStepSizeUm = zStepSizeUm;
			return copy;
		}
	}


	// ---- commands -------------------------------------------------------------------

	@Override
	public void run(String arg) {
		Debug.commandStarted ( "Batch Processing > Format Conversion" );
		String mode = arg == null ? "" : arg.trim().toLowerCase(Locale.ROOT);
		if ("zarr-region".equals(mode)) runZarrRegionExport();
		// "tiff" and "zarr" were the two single-file Utilities commands; a folder or a file
		// named in the one dialog covers both, so they open it with their target chosen
		else if ("tiff".equals(mode)) runBatch(TARGET_TIFF);
		else if ("zarr".equals(mode)) runBatch(TARGET_ZARR);
		else runBatch(null);
	}

	/**
	 * Convert what a folder holds - or one named TIFF - to the chosen format.
	 * <p>
	 * The folder is scanned while the dialog is open, and the dialog says what it found and
	 * what the chosen target will skip, so a choice that would convert nothing is visible
	 * before OK rather than as an empty log afterwards.
	 */
	private void runBatch(String presetTarget) {
		final OpmDialog gd = new OpmDialog(TITLE);
		Parameter.styleDialog( gd );
		final int length = 55, inset = 95, section = 20;

		gd.setInsets(0, 15, 5);
		Parameter.addSection(gd, "Input setup:");
		gd.addDirectoryField("data folder", ij.Prefs.get(PREF + "input", ""), length);
		final TextField folderField = Parameter.lastStringOrNumber(gd.getStringFields());
		gd.setInsets(0, inset, 0);
		gd.addMessage("Scanning for TIFF volumes, TIFF results and OME-Zarr datasets...          ");
		final Label summary = (Label) gd.getMessage();
		gd.setInsets(0, inset, 0);
		gd.addCheckbox("include sub-folders", ij.Prefs.get(PREF + "recursive", false));
		gd.addStringField("file name include", ij.Prefs.get(PREF + "include", ""), length);
		gd.addStringField("file name exclude", ij.Prefs.get(PREF + "exclude", ""), length);

		gd.setInsets(section, 15, 5);
		Parameter.addSection(gd, "Output setup:");
		gd.addDirectoryField("save to", ij.Prefs.get(PREF + "output", ""), length);
		gd.addChoice("target format", TARGETS,
				presetTarget != null ? presetTarget : ij.Prefs.get(PREF + "target", TARGET_TIFF));
		final Choice targetChoice = (Choice) gd.getChoices().lastElement();
		gd.setInsets(0, inset, 0);
		gd.addMessage("                                                                          ");
		final Label gate = (Label) gd.getMessage();
		gd.addChoice("if result exists", new String[] { "skip", "overwrite" },
				ij.Prefs.get(PREF + "exists", "skip"));
		gd.setInsets(5, inset, 0);
		gd.addMessage("An empty 'save to' writes into a 'converted' folder inside the input.\n"
				+ "Nothing is deskewed, flipped or aligned; Deflate always runs at level 1.");

		final DataFolder[] last = { null };
		final Runnable describe = new Runnable() {
			@Override public void run() {
				gate.setText(gateText(last[0], targetChoice.getSelectedItem()));
				if (gd.isShowing() && (gate.getPreferredSize().width > gate.getWidth()
						|| summary.getPreferredSize().width > summary.getWidth())) gd.pack();
			}
		};
		final DataFolder.Watch watch = new DataFolder.Watch(new DataFolder.Scan() {
			@Override public DataFolder scan(File selection) {
				return DataFolder.scan(selection, null, true);
			}
		}, new DataFolder.Show() {
			@Override public void show(DataFolder found) {
				last[0] = found;
				summary.setText(found == null ? "Choose a data folder, or one TIFF file." : found.summary());
				describe.run();
			}
		});
		gd.addDialogListener(new DialogListener() {
			@Override public boolean dialogItemChanged(GenericDialog dialog, AWTEvent event) {
				if (event instanceof TextEvent && event.getSource() == folderField)
					watch.request(folderField.getText());
				describe.run();
				return true;
			}
		});
		watch.request(folderField.getText());

		gd.addHelp(Help.formatConversion);
		gd.showDialog();
		if (gd.wasCanceled()) return;

		final File input = new File(gd.getNextString().trim());
		final boolean recursive = gd.getNextBoolean();
		final String include = gd.getNextString().trim();
		final String exclude = gd.getNextString().trim();
		String outputText = gd.getNextString().trim();
		final Options options = new Options();
		options.target = gd.getNextChoice();
		options.overwrite = "overwrite".equals(gd.getNextChoice());

		if (!input.exists()) {
			IJ.error(TITLE, "The data folder does not exist:\n" + input);
			return;
		}
		File inputFolder = input.isDirectory() ? input : input.getParentFile();
		final File output = outputText.isEmpty()
				? new File(inputFolder, "converted") : new File(outputText);

		ij.Prefs.set(PREF + "input", input.getAbsolutePath());
		ij.Prefs.set(PREF + "include", include);
		ij.Prefs.set(PREF + "exclude", exclude);
		ij.Prefs.set(PREF + "recursive", recursive);
		ij.Prefs.set(PREF + "output", output.getAbsolutePath());
		ij.Prefs.set(PREF + "target", options.target);
		ij.Prefs.set(PREF + "exists", options.overwrite ? "overwrite" : "skip");

		DataFolder.Filter filter = new DataFolder.Filter();
		filter.include = include;
		filter.exclude = exclude;
		filter.recursive = recursive;
		filter.outputRoot = output;
		final DataFolder found = DataFolder.scan(input, filter, true);
		String nothing = nothingToDo(found, options);
		if (nothing != null) {
			IJ.error(TITLE, nothing);
			return;
		}
		readGeometry(inputFolder, options);

		Shutdown.runCancellable("OPM Format Conversion", new Runnable() {
			@Override
			public void run() { convertFolder(found, output, options, recursive); }
		});
	}

	/** What the chosen target will pass over, for the line under it; empty when nothing is. */
	static String gateText(DataFolder found, String target) {
		if (found == null) return "";
		Options options = new Options();
		options.target = target;
		List<String> skipped = new ArrayList<String>();
		int deflated = found.deflatedLooseCount();
		if (options.wantsTiff() && deflated > 0)
			skipped.add(deflated + " already deflated TIFF" + (deflated == 1 ? "" : "s")
					+ (options.wantsZarr() ? " go to OME-Zarr only" : " are skipped"));
		if (!options.wantsTiff() && !found.zarr.isEmpty())
			skipped.add(found.zarr.size() + " OME-Zarr dataset" + (found.zarr.size() == 1 ? " is" : "s are")
					+ " already OME-Zarr");
		String nothing = nothingToDo(found, options);
		if (nothing != null) return nothing.replace('\n', ' ');
		return skipped.isEmpty() ? "" : join(skipped) + ".";
	}

	/**
	 * Why this target would convert nothing here, or null when it converts something.
	 * <p>
	 * The one gate on a combination: a deflated TIFF to deflated TIFF. Everything else a folder
	 * can hold converts to at least one of the targets.
	 */
	static String nothingToDo(DataFolder found, Options options) {
		if (found == null || found.isEmpty())
			return "No TIFF volume, TIFF result or OME-Zarr dataset was found.";
		boolean something = false;
		for (DataFolder.LooseTiff tiff : found.loose)
			something |= options.forInput(tiff.deflated) != null;
		if (options.wantsZarr()) something |= !found.results.isEmpty();
		if (options.wantsTiff()) something |= !found.zarr.isEmpty() || hasUndeflatedFrames(found);
		if (something) return null;
		return "Everything here is already deflated TIFF, and deflated to deflated would only\n"
				+ "copy it. Choose OME-Zarr as the target to convert it.";
	}

	private static boolean hasUndeflatedFrames(DataFolder found) {
		for (TiffResultDataset result : found.results)
			for (String view : DataFolder.views(result))
				for (TiffResultDataset.Frame frame : result.getView(view).getFrames())
					if (!isDeflated(frame.file)) return true;
		return false;
	}

	static boolean isDeflated(File file) {
		try { return FastTiffReader.isDeflate(FastTiffReader.firstPlaneCompression(file)); }
		catch (Throwable unreadable) { return false; }
	}

	private static String join(List<String> parts) {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < parts.size(); i++) text.append(i == 0 ? "" : "; ").append(parts.get(i));
		return text.toString();
	}


	/**			Write part of a canonical OME-Zarr out as one deflated TIFF
	 * <p>		The only path here that reads OME-Zarr rather than writing it, and the only one
	 * 			whose output can differ from its input pixel for pixel: the runtime view offered
	 * 			below is the viewer's, so a flip or an alignment can be baked into what is
	 * 			written. Leave it at stored channels for a pure format change.
	 * <p>		It never opens the volume. {@link OmeZarrView#exportRegionToTiff} renders the
	 * 			region's planes one at a time straight into the writer, so exporting a box out
	 * 			of a 5-D dataset costs the box and not the dataset.
	 * <p>		The region is given in the coordinates of the <em>view</em>, which is what an
	 * 			ROI drawn on an open view means and why the ROI button is offered. The dialog is
	 * 			non-blocking, so the box can be drawn on a view while it is open. Zero width,
	 * 			height or last z means the whole extent of that axis.
	 */
	private void runZarrRegionExport() {
		final ChannelOperationSettings channels = new ChannelOperationSettings();
		channels.load();
		String[] selections = {
			OmeZarrView.SELECT_ALL, OmeZarrView.SELECT_CONFIGURED,
			OmeZarrView.SELECT_LEFT, OmeZarrView.SELECT_RIGHT
		};

		GenericDialog gd = new OpmDialog("OPM Export OME-Zarr region to TIFF");
		Parameter.styleDialog( gd );
		gd.addMessage("Write a region of a canonical OME-Zarr dataset as one deflated TIFF.\n"
				+ "No deskew is applied. The volume is never opened; planes are streamed.");
		gd.addDirectoryField("OME-Zarr dataset", ij.Prefs.get(PREF + "zarr", ""), 40);
		gd.addChoice("channels / side", selections,
				ij.Prefs.get(PREF + "zarrChannels", OmeZarrView.SELECT_ALL));
		gd.addChoice("runtime view", operationNames(),
				ij.Prefs.get(PREF + "zarrView", OmeZarrView.Operation.STORED_CHANNELS.toString()));
		gd.addMessage("Region, in the coordinates the chosen view produces.\n"
				+ "Zero width, height or last z means the whole of that axis.");
		gd.addNumericField("x", 0, 0);
		gd.addNumericField("y", 0, 0);
		gd.addNumericField("width", 0, 0);
		gd.addNumericField("height", 0, 0);
		gd.addNumericField("first z", 1, 0);
		gd.addNumericField("last z", 0, 0);
		OmeZarrRoi.addUpdateButton(gd, 0, 0);
		gd.addNumericField("first time point", 1, 0);
		gd.addNumericField("time points", 1, 0);
		gd.addFileField("save as", ij.Prefs.get(PREF + "zarrTarget", ""), 46);
		gd.showDialog();
		if (gd.wasCanceled()) return;

		final File root = new File(gd.getNextString().trim());
		final String selection = gd.getNextChoice();
		final OmeZarrView.Operation operation = operationNamed(gd.getNextChoice());
		OmeZarrView.Bounds asked = new OmeZarrView.Bounds();
		asked.x = (int) gd.getNextNumber();
		asked.y = (int) gd.getNextNumber();
		asked.width = (int) gd.getNextNumber();
		asked.height = (int) gd.getNextNumber();
		asked.zStart = (int) gd.getNextNumber() - 1;
		asked.zEnd = (int) gd.getNextNumber();
		final int askedFirstT = (int) gd.getNextNumber();
		final int askedFrames = (int) gd.getNextNumber();
		final int level = FastTiffWriter.DEFAULT_LEVEL;
		String targetText = gd.getNextString().trim();

		if (!root.isDirectory()) {
			IJ.error("OPM Format Conversion", "The OME-Zarr dataset does not exist:\n" + root);
			return;
		}
		if (targetText.isEmpty()) {
			IJ.error("OPM Format Conversion", "Choose a destination TIFF.");
			return;
		}

		final OmeZarrDataset dataset;
		try {
			dataset = OmeZarrDataset.read(root);
		} catch (Throwable unreadable) {
			IJ.error("OPM Format Conversion", "Not a readable OME-Zarr dataset:\n" + root
					+ "\n\n" + unreadable);
			return;
		}

		final OmeZarrView.Options options = new OmeZarrView.Options();
		options.operation = operation;
		options.interpolate = channels.interpolate;
		options.flipHalf = channels.flipHalf;
		options.tryGpu = ij.Prefs.get("opm.zarrViewer.tryGpu", true);
		options.requestedChannels.addAll(
				OmeZarrView.channelsForSelection(dataset, selection, channels));

		/* Clamped against the extent this very view produces, which is the only thing that
		 * knows a side-by-side view is twice as wide as the array it reads. */
		try {
			int[] extent = OmeZarrView.viewExtent(dataset, options);
			OmeZarrView.Bounds clamped = asked.clampedTo(extent[0], extent[1], extent[2]);
			options.bounds = clamped.covers(extent[0], extent[1], extent[2]) ? null : clamped;
		} catch (Throwable unusable) {
			IJ.error("OPM Format Conversion", "This dataset cannot produce that view:\n" + unusable);
			return;
		}

		final int committed = dataset.getTimepointCount();
		final int firstT = Math.max(1, Math.min(committed, askedFirstT)) - 1;
		final int frames = Math.max(1, Math.min(committed - firstT, askedFrames));
		final File target = new File(VolumeIO.tiffPath(targetText));

		ij.Prefs.set(PREF + "zarr", root.getAbsolutePath());
		ij.Prefs.set(PREF + "zarrChannels", selection);
		ij.Prefs.set(PREF + "zarrView", operation.toString());
		ij.Prefs.set(PREF + "zarrTarget", target.getAbsolutePath());

		Shutdown.runCancellable("OPM Export OME-Zarr region", new Runnable() {
			@Override public void run() {
				long start = System.nanoTime();
				try {
					String written = OmeZarrView.exportRegionToTiff(
							dataset, options, firstT, frames, target, level);
					IJ.log("OPM OME-Zarr region export: " + written + " in "
							+ IJ.d2s((System.nanoTime() - start) / 1e9, 1) + " s");
				} catch (Throwable failure) {
					IJ.log("OPM OME-Zarr region export failed: " + failure);
					failure.printStackTrace();
					IJ.error("OPM Format Conversion", String.valueOf(failure));
				}
			}
		});
	}

	private static String[] operationNames() {
		OmeZarrView.Operation[] operations = OmeZarrView.Operation.values();
		String[] names = new String[operations.length];
		for (int i = 0; i < operations.length; i++) names[i] = operations[i].toString();
		return names;
	}

	private static OmeZarrView.Operation operationNamed(String name) {
		for (OmeZarrView.Operation operation : OmeZarrView.Operation.values())
			if (operation.toString().equals(name)) return operation;
		return OmeZarrView.Operation.STORED_CHANNELS;
	}

	// ---- the run --------------------------------------------------------------------

	/**
	 * Convert everything a scan found, one unit at a time, stopping cleanly when asked to.
	 * <p>
	 * A unit is one loose TIFF, one TIFF result, or one OME-Zarr dataset - each finished and
	 * closed before the next is started, so a stopped run leaves whole outputs only.
	 */
	static void convertFolder(DataFolder found, File outputRoot, Options options, boolean reproduceTree) {
		Log log = null;
		int done = 0, skipped = 0, failed = 0;
		int units = found.loose.size() + found.results.size() + found.zarr.size();
		long start = System.nanoTime();
		try {
			log = new Log("format-conversion", outputRoot.getAbsolutePath(), true);
			log.add("OPM Format Conversion: " + found.summary() + " Target " + options.target
					+ ", Deflate level " + options.deflateLevel
					+ ", " + (options.overwrite ? "overwrite" : "skip") + " existing"
					+ "; input " + found.root + "; output " + outputRoot);
			IJ.log("OPM Format Conversion started: " + found.summary() + " Target " + options.target + ".");

			int index = 0;
			for (DataFolder.LooseTiff tiff : found.loose) {
				if (stopped(log, tiff.file.getName())) return;
				IJ.showProgress(index++, units);
				Options forThis = options.forInput(tiff.deflated);
				if (forThis == null) {
					log.add("skip (already deflated) " + tiff.file.getAbsolutePath());
					skipped++;
					continue;
				}
				File folder = reproduceTree && found.singleFile == null
						? BatchProcessingUtils.saveRootFor(tiff.file, found.root, outputRoot.getAbsolutePath(), false, true)
						: outputRoot;
				try {
					if (convertOne(tiff.file, folder, forThis, log) > 0) done++;
					else skipped++;
				} catch (Throwable failure) {
					failed++;
					log.add("FAILED " + tiff.file.getAbsolutePath() + ": " + failure);
					failure.printStackTrace();
				}
			}
			for (TiffResultDataset result : found.results) {
				if (stopped(log, result.getDisplayName())) return;
				IJ.showProgress(index++, units);
				try {
					int written = 0;
					if (options.wantsZarr()) written += convertResultToOmeZarr(result, outputRoot, options, log);
					if (options.wantsTiff()) written += deflateResult(result, found.root, outputRoot, options, log);
					if (written > 0) done++;
					else skipped++;
				} catch (Throwable failure) {
					failed++;
					log.add("FAILED " + result.getDisplayName() + ": " + failure);
					failure.printStackTrace();
				}
			}
			for (OmeZarrDataset dataset : found.zarr) {
				if (stopped(log, dataset.getDisplayName())) return;
				IJ.showProgress(index++, units);
				if (!options.wantsTiff()) {
					log.add("skip (already OME-Zarr) " + dataset.getRoot());
					skipped++;
					continue;
				}
				try {
					if (convertZarrToTiff(dataset, outputRoot, options, log) > 0) done++;
					else skipped++;
				} catch (Throwable failure) {
					failed++;
					log.add("FAILED " + dataset.getRoot() + ": " + failure);
					failure.printStackTrace();
				}
			}
			IJ.showProgress(1.0);
			String summary = done + " converted, " + skipped + " skipped, " + failed + " failed in "
					+ IJ.d2s((System.nanoTime() - start) / 1e9, 1) + " s";
			log.add("OPM Format Conversion finished: " + summary);
			IJ.log("OPM Format Conversion finished: " + summary + ". Details in " + log.getPath());
		} catch (Throwable failure) {
			IJ.log("OPM Format Conversion failed: " + failure);
			failure.printStackTrace();
		} finally {
			if (log != null) log.close();
		}
	}

	private static boolean stopped(Log log, String next) {
		if (!Shutdown.stopping()) return false;
		log.add("stopped before " + next + ": " + Shutdown.reason());
		IJ.log("OPM Format Conversion stopped: " + Shutdown.reason() + ".");
		return true;
	}

	/**
	 * A TIFF result as one OME-Zarr over its time points, its channels as they are.
	 * <p>
	 * A pure format change. The channels are stored exactly as the result holds them - already
	 * flipped and aligned where its run did that - so the store records {@code alignApplied} and
	 * the viewer shows them as stored. The six projections the viewer contract carries are
	 * computed from each time point's volume; a result without a volume is not a dataset an
	 * OME-Zarr can hold, and says so.
	 *
	 * @return	the number of time points written
	 */
	static int convertResultToOmeZarr(TiffResultDataset result, File outputRoot, Options options,
			Log log) throws IOException {
		if (!result.hasVolume()) {
			if (log != null) log.add("skip (no deskewed volume to store) " + result.getDisplayName());
			return 0;
		}
		TiffResultDataset.View volume = result.getView(TiffResultDataset.VOLUME);
		TiffResultDataset.Layout layout = volume.getLayout();
		File root = new File(outputRoot, datasetName(result.getDisplayName()) + ".ome.zarr");
		if (root.exists() && options.overwrite) FileUtils.deleteDirectory(root);

		List<String> labels = new ArrayList<String>();
		for (int c = 1; c <= layout.channels; c++) labels.add("C" + c);
		List<ProjectionBatch.Request> requests = new ArrayList<ProjectionBatch.Request>();
		for (String name : OmeZarrSession.PROJECTIONS)
			requests.add(new ProjectionBatch.Request(name.substring(name.length() - 1),
					name.startsWith("mean") ? "avg" : "max"));

		OmeZarrWriter writer = new OmeZarrWriter(root);
		int written = 0;
		try {
			writer.createAppendableVolume(layout.width, layout.height, layout.slices, layout.channels);
			for (int i = 0; i < OmeZarrSession.PROJECTIONS.size(); i++) {
				char axis = requests.get(i).axis;
				writer.createAppendableProjection(OmeZarrSession.PROJECTIONS.get(i),
						axis == 'X' ? layout.slices : layout.width,
						axis == 'Y' ? layout.slices : layout.height, layout.channels);
			}
			writer.markWriting();
			writer.writeMetadata(resultProvenance(result, root, layout, labels), OmeZarrSession.PROJECTIONS);
			List<TiffResultDataset.Frame> frames = volume.getFrames();
			for (int t = 0; t < frames.size(); t++) {
				if (Shutdown.stopping()) break;
				File file = frames.get(t).file;
				String label = BatchProcessingUtils.baseName(file);
				if (writer.isTimePointCommitted(label)) continue;
				ImagePlus image = VolumeIO.open(file.getAbsolutePath());
				if (image == null) throw new IOException("Could not open " + file);
				try {
					if (image.getBitDepth() != 16)
						throw new IOException(file.getName() + " is " + image.getBitDepth()
								+ "-bit; an OME-Zarr stores 16-bit only.");
					ImagePlus[] channels = image.getNChannels() > 1
							? ChannelSplitter.split(image) : new ImagePlus[] { image };
					int time = writer.getCommittedTimepoints();
					for (int c = 0; c < channels.length; c++) {
						writer.writeVolumeChannel(channels[c], c, time);
						List<ImagePlus> mips = ProjectionBatch.compute(channels[c], requests, options.tryGpu);
						try {
							for (int p = 0; p < mips.size(); p++)
								writer.writeProjection(mips.get(p), OmeZarrSession.PROJECTIONS.get(p), c, time);
						} finally {
							for (ImagePlus mip : mips) BatchProcessingUtils.close(mip);
						}
						if (channels[c] != image) BatchProcessingUtils.close(channels[c]);
					}
					writer.commitTimePoint(time, label, Arrays.asList(file.getName()),
							t * Math.max(0, layout.frameInterval));
					written++;
				} finally {
					BatchProcessingUtils.close(image);
				}
			}
			if (!Shutdown.stopping()) writer.markComplete();
		} finally {
			writer.close();
		}
		if (log != null) log.add("OME-Zarr " + result.getDisplayName() + " -> " + root.getName()
				+ ": " + written + " time point(s)");
		return written;
	}

	private static OpmProvenance resultProvenance(TiffResultDataset result, File root,
			TiffResultDataset.Layout layout, List<String> labels) {
		OpmProvenance provenance = new OpmProvenance();
		provenance.contentKind = OpmProvenance.CONTENT_DESKEWED;
		provenance.datasetName = root.getName().replaceAll("(?i)[.]ome[.]zarr$", "");
		provenance.sourceFolder = result.getRoot().getAbsolutePath();
		double pixel = positiveOrOne(layout.pixelWidth);
		provenance.xyPixelSizeUm = pixel;
		provenance.deskewedVoxelSizeUm = new double[] {
			pixel, positiveOrOne(layout.pixelHeight), positiveOrOne(layout.pixelDepth) };
		provenance.frameIntervalSeconds = Math.max(0, layout.frameInterval);
		provenance.deskewMatrix = Transform.identity();
		// whatever flip and alignment this result had are already in its pixels
		provenance.alignApplied = true;
		provenance.channelLabels.addAll(labels);
		return provenance.stampEnvironment(false);
	}

	/**
	 * The frames of a TIFF result that are not yet deflated, deflated under the same names.
	 * <p>
	 * Each keeps its path below the scanned folder, so the output is the same result in the same
	 * layout - still a dataset the viewer reads. A frame that is already deflated is not copied.
	 *
	 * @return	the number of frames written
	 */
	static int deflateResult(TiffResultDataset result, File scannedRoot, File outputRoot,
			Options options, Log log) throws IOException {
		int written = 0;
		for (String view : DataFolder.views(result)) {
			for (TiffResultDataset.Frame frame : result.getView(view).getFrames()) {
				if (Shutdown.stopping()) return written;
				if (isDeflated(frame.file)) continue;
				File target = new File(outputRoot, relative(frame.file, scannedRoot));
				if (target.isFile() && !options.overwrite) continue;
				File parent = target.getParentFile();
				if (parent != null) parent.mkdirs();
				deflateKeepingLayout(frame.file, target, options.deflateLevel);
				written++;
			}
		}
		if (log != null && written > 0)
			log.add("TIFF " + result.getDisplayName() + ": " + written + " frame(s) deflated");
		return written;
	}

	/** Deflate one result file, keeping its channels, slices and calibration. */
	static void deflateKeepingLayout(File input, File output, int level) throws IOException {
		TiffResultDataset.Layout read = TiffResultDataset.Layout.read(input);
		final FastTiffReader.PlaneReader reader = new FastTiffReader.PlaneReader(input);
		try {
			FastTiffWriter.Layout layout = new FastTiffWriter.Layout();
			layout.width = read.width;
			layout.height = read.height;
			layout.channels = read.channels;
			layout.slices = read.slices;
			layout.unit = read.unit == null ? "" : read.unit;
			layout.pixelWidth = read.pixelWidth;
			layout.pixelHeight = read.pixelHeight;
			layout.pixelDepth = read.pixelDepth;
			layout.frameInterval = read.frameInterval;
			FastTiffWriter.write(new FastTiffWriter.PlaneSource() {
				@Override public ImageProcessor plane(int index) throws IOException {
					return reader.readPlane(index);
				}
			}, layout, output, clampLevel(level));
		} finally {
			reader.close();
		}
	}

	/**
	 * An OME-Zarr dataset as deflated TIFF, one file per time point per view.
	 * <p>
	 * The stored channels, untransformed, in the layout {@link TiffResultDataset} reads: the
	 * volume as {@code <time point>-deskewed.tif} under {@code deskew/} and each projection
	 * beside it under its own folder. A store of raw pixels - what this command writes for a raw
	 * volume - has no projections and is not a deskew, so it comes back as
	 * {@code <name>-deflated.tif}, the name a converted raw volume always takes.
	 *
	 * @return	the number of files written
	 */
	static int convertZarrToTiff(OmeZarrDataset dataset, File outputRoot, Options options, Log log)
			throws IOException {
		boolean raw = dataset.getProvenance() != null
				&& OpmProvenance.CONTENT_RAW.equals(dataset.getProvenance().contentKind);
		OmeZarrView.Options stored = new OmeZarrView.Options();
		stored.operation = OmeZarrView.Operation.STORED_CHANNELS;
		stored.requestedChannels.addAll(dataset.getChannelLabels());
		stored.tryGpu = options.tryGpu;
		List<String> labels = dataset.getTimePointLabels();
		int written = 0;
		for (int t = 0; t < dataset.getTimepointCount(); t++) {
			if (Shutdown.stopping()) break;
			String label = t < labels.size() && labels.get(t) != null && !labels.get(t).isEmpty()
					? labels.get(t) : String.format(Locale.US, "%s_Time%06d", dataset.getDisplayName(), t + 1);
			if (raw) {
				File target = new File(outputRoot, label + TIFF_SUFFIX + ".tif");
				if (target.isFile() && !options.overwrite) continue;
				OmeZarrView.exportRegionToTiff(dataset, stored, t, 1, target, options.deflateLevel);
				written++;
				continue;
			}
			String name = (label.matches("(?i).*_Time\\d+.*") ? label
					: String.format(Locale.US, "%s_Time%06d", label, t + 1)) + "-deskewed";
			File volume = BatchTiffOutput.volumeFile(outputRoot, true, name);
			if (options.overwrite || !VolumeIO.isCompleteTiff(volume)) {
				volume.getParentFile().mkdirs();
				OmeZarrView.exportRegionToTiff(dataset, stored, t, 1, volume, options.deflateLevel);
				written++;
			}
			for (String projection : dataset.getAvailableProjections()) {
				String type = projection.startsWith("mean") ? "avg" : projection.substring(0, projection.length() - 1);
				String axis = projection.substring(projection.length() - 1);
				File target = BatchTiffOutput.projectionFile(outputRoot, true, name, type, axis);
				if (!options.overwrite && VolumeIO.isCompleteTiff(target)) continue;
				ImagePlus planes = projectionPlanes(dataset, projection, t, name + "-" + type + axis + "projection");
				try {
					BatchTiffOutput.write(planes, target, true, "projection TIFF");
					written++;
				} finally {
					BatchProcessingUtils.close(planes);
				}
			}
		}
		if (log != null) log.add("TIFF " + dataset.getDisplayName() + ": " + written + " file(s)");
		return written;
	}

	/** One stored projection at one time point, every channel, calibrated. */
	private static ImagePlus projectionPlanes(OmeZarrDataset dataset, String projection, int time,
			String title) throws IOException {
		long[] shape = dataset.getProjectionDimensions(projection);
		OmeZarrPlaneReader reader = new OmeZarrPlaneReader(dataset, "projections/" + projection);
		try {
			int channels = Math.max(1, dataset.getChannelCount());
			ImageStack stack = null;
			for (int c = 0; c < channels; c++) {
				ImageProcessor plane = reader.readPlane(c, time);
				if (stack == null) stack = new ImageStack(plane.getWidth(), plane.getHeight());
				stack.addSlice(dataset.getChannelLabels().size() > c ? dataset.getChannelLabels().get(c) : null, plane);
			}
			ImagePlus image = new ImagePlus(title, stack);
			image.setDimensions(channels, 1, 1);
			if (channels > 1) image.setOpenAsHyperStack(true);
			double[] pixel = dataset.getProjectionPixelSizeUm(projection);
			Calibration calibration = new Calibration();
			calibration.pixelWidth = pixel[0];
			calibration.pixelHeight = pixel[1];
			calibration.setUnit("micron");
			image.setCalibration(calibration);
			if (shape.length == 0) throw new IOException("No projection " + projection);
			return image;
		} finally {
			reader.close();
		}
	}

	/** A dataset name: the display name without its time token and trailing separators. */
	static String datasetName(String displayName) {
		return displayName.replaceAll("(?i)_Time(?=$|[-_.])", "").replaceAll("[-_]+$", "");
	}

	private static String relative(File file, File root) {
		String path = file.getAbsolutePath();
		String base = root == null ? "" : root.getAbsolutePath();
		if (!base.isEmpty() && path.startsWith(base))
			return path.substring(base.length()).replaceAll("^[\\\\/]+", "");
		return file.getName();
	}

	/**			Convert one input to every requested target
	 *
	 * @return					: the number of outputs actually written; 0 means all were skipped
	 */
	static int convertOne(File input, File outputFolder, Options options, Log log) throws IOException {
		int written = 0;
		if (options.wantsTiff()) {
			File target = tiffOutput(input, outputFolder);
			if (target.isFile() && !options.overwrite) {
				if (log != null) log.add("skip (exists) " + target.getAbsolutePath());
			} else {
				long start = System.nanoTime();
				long bytes = convertToDeflatedTiff(input, target, options.deflateLevel, options);
				written++;
				if (log != null) log.add(report("TIFF", input, target, bytes,
						(System.nanoTime() - start) / 1e9));
			}
		}
		if (options.wantsZarr()) {
			File target = zarrOutput(input, outputFolder);
			if (target.isDirectory() && !options.overwrite) {
				if (log != null) log.add("skip (exists) " + target.getAbsolutePath());
			} else {
				long start = System.nanoTime();
				long bytes = convertToOmeZarr(input, target, options);
				written++;
				if (log != null) log.add(report("OME-Zarr", input, target, bytes,
						(System.nanoTime() - start) / 1e9));
			}
		}
		return written;
	}

	private static String report(String kind, File input, File output, long outputBytes, double seconds) {
		long inputBytes = input.length();
		double ratio = inputBytes > 0 ? (double) outputBytes / inputBytes : 0;
		return String.format(Locale.US, "%s %s -> %s: %.1f MB from %.1f MB (%.2fx) in %.3f s",
				kind, input.getName(), output.getName(), outputBytes / 1048576.0,
				inputBytes / 1048576.0, ratio, seconds);
	}


	// ---- the conversions ------------------------------------------------------------

	/** Where the Deflate TIFF for one input goes; never the input's own name in its own folder. */
	static File tiffOutput(File input, File outputFolder) {
		return new File(outputFolder, BatchProcessingUtils.baseName(input) + TIFF_SUFFIX + ".tif");
	}

	/** Where the OME-Zarr for one input goes. */
	static File zarrOutput(File input, File outputFolder) {
		return new File(outputFolder, BatchProcessingUtils.baseName(input) + ".ome.zarr");
	}

	/**			Rewrite one volume as a Deflate-compressed TIFF, one plane at a time
	 *
	 * @return					: size of the written file in bytes
	 */
	static long convertToDeflatedTiff(File input, File output, int level) throws IOException {
		return convertToDeflatedTiff(input, output, level, null);
	}

	/**			The same, calibrated with the acquisition's own geometry when it is known
	 * <p>		A raw volume carries the camera pixel size in X and Y and the stage step in Z -
	 * <br>		the same numbers {@link #convertToOmeZarr} records in a store's provenance. Without
	 * <br>		them the TIFF says nothing, and ImageJ then reports one micron per pixel.
	 */
	static long convertToDeflatedTiff(File input, File output, int level, Options options)
			throws IOException {
		VolumeSource source = VolumeSource.open(input);
		try {
			FastTiffWriter.Layout layout = new FastTiffWriter.Layout();
			layout.width = source.width;
			layout.height = source.height;
			layout.slices = source.depth;
			if (options != null && options.xyPixelSizeUm > 0) {
				layout.unit = "micron";
				layout.pixelWidth = options.xyPixelSizeUm;
				layout.pixelHeight = options.xyPixelSizeUm;
				// the raw stage step; a raw volume is not deskewed, so Z is not the XY pitch
				layout.pixelDepth = options.zStepSizeUm > 0 ? options.zStepSizeUm : 1;
			}
			final VolumeSource planes = source;
			FastTiffWriter.write(new FastTiffWriter.PlaneSource() {
				@Override public ImageProcessor plane(int index) throws IOException {
					return planes.plane(index);
				}
			}, layout, output, clampLevel(level));
			return output.length();
		} finally {
			source.close();
		}
	}

	/**			Rewrite one volume as a single-time-point canonical OME-Zarr, a Z slab at a time
	 * <p>		The dataset is marked {@code contentKind = "raw"}: these are the acquisition's
	 * 			own pixels, not a deskew result, and the viewer's flip and align operations
	 * 			have nothing to act on.
	 *
	 * @return					: total size of the written store in bytes
	 */
	static long convertToOmeZarr(File input, File root, Options options) throws IOException {
		if (root.exists() && options.overwrite) FileUtils.deleteDirectory(root);
		VolumeSource source = VolumeSource.open(input);
		OmeZarrWriter writer = null;
		try {
			writer = new OmeZarrWriter(root);
			writer.createAppendableVolume(source.width, source.height, source.depth, 1);
			writer.markWriting();
			writer.writeMetadata(rawProvenance(input, root, source, options), new ArrayList<String>());
			final VolumeSource planes = source;
			writer.writeVolumeChannel(new OmeZarrWriter.VolumePlanes() {
				@Override public ImageProcessor plane(int z) throws IOException { return planes.plane(z); }
			}, source.width, source.height, source.depth, 0, 0);
			writer.commitTimePoint(0, BatchProcessingUtils.baseName(input),
					Arrays.asList(input.getName()), 0);
			writer.markComplete();
			return FileUtils.sizeOfDirectory(root);
		} finally {
			if (writer != null) writer.close();
			source.close();
		}
	}

	/** A record that says plainly this store holds raw pixels, so nothing reads it as a deskew. */
	private static OpmProvenance rawProvenance(
			File input, File root, VolumeSource source, Options options) {
		OpmProvenance provenance = new OpmProvenance();
		provenance.contentKind = OpmProvenance.CONTENT_RAW;
		provenance.datasetName = root.getName().replaceAll("(?i)[.]ome[.]zarr$", "");
		provenance.sourceFolder = input.getParent();
		provenance.sourceFiles.add(input.getName());
		provenance.xyPixelSizeUm = positiveOrOne(options.xyPixelSizeUm);
		provenance.zStepSizeUm = positiveOrOne(options.zStepSizeUm);
		provenance.opmAngleDegrees = 0;
		provenance.deskewMatrix = Transform.identity();
		// raw voxels keep their acquired spacing; there is no deskew to make them isotropic
		provenance.deskewedVoxelSizeUm = new double[] {
			provenance.xyPixelSizeUm, provenance.xyPixelSizeUm, provenance.zStepSizeUm };
		provenance.alignApplied = false;
		provenance.channelLabels.add(source.name);
		return provenance.stampEnvironment(false);
	}

	private static double positiveOrOne(double value) {
		return value > 0 ? value : 1.0;
	}

	/** Take the acquisition's own geometry when it is sitting beside the data. */
	private static void readGeometry(File folder, Options options) {
		if (folder == null) return;
		File metadata = new File(folder, "ExperimentalParameters.txt");
		if (!metadata.isFile()) return;
		double[] parameters = IO.loadExperimentalParametersFromFile(metadata.getAbsolutePath());
		if (parameters == null) return;
		options.xyPixelSizeUm = parameters[0] / 1000.0;
		options.zStepSizeUm = parameters[1] / 1000.0;
	}

	static int clampLevel(int level) {
		return Math.max(0, Math.min(9, level));
	}


	/**
	 * One volume opened for streaming, whichever reader can handle it.
	 *
	 * <p>{@link FastTiffReader} is tried first and covers both the raw BigTIFF the microscope
	 * writes and the Deflate TIFF this class writes. Anything else falls back to opening the
	 * whole volume through {@link VolumeIO} - correct, but no longer streaming, which is why
	 * it is the fallback rather than the default.
	 */
	static final class VolumeSource implements Closeable {
		final int width;
		final int height;
		final int depth;
		final String name;
		private final FastTiffReader.PlaneReader streaming;
		private final ImagePlus loaded;

		private VolumeSource(String name, FastTiffReader.PlaneReader streaming, ImagePlus loaded,
				int width, int height, int depth) {
			this.name = name;
			this.streaming = streaming;
			this.loaded = loaded;
			this.width = width;
			this.height = height;
			this.depth = depth;
		}

		static VolumeSource open(File input) throws IOException {
			if (input == null || !input.isFile())
				throw new IOException("Input volume does not exist: " + input);
			String name = BatchProcessingUtils.baseName(input);
			try {
				FastTiffReader.PlaneReader reader = new FastTiffReader.PlaneReader(input);
				return new VolumeSource(name, reader, null,
						reader.width(), reader.height(), reader.depth());
			} catch (Throwable notFastPath) {
				// not a plain 16-bit strip TIFF; let ImageJ/Bio-Formats try
			}
			ImagePlus imp = VolumeIO.open(input.getAbsolutePath());
			if (imp == null) throw new IOException("Could not open " + input);
			if (imp.getBitDepth() != 16) {
				BatchProcessingUtils.close(imp);
				throw new IOException("Only 16-bit volumes can be converted; " + input.getName()
						+ " is " + imp.getBitDepth() + "-bit.");
			}
			return new VolumeSource(name, null, imp,
					imp.getWidth(), imp.getHeight(), imp.getStackSize());
		}

		/** Whether planes are pulled from disk on demand rather than held in RAM. */
		boolean isStreaming() { return streaming != null; }

		ImageProcessor plane(int index) throws IOException {
			if (streaming != null) return streaming.readPlane(index);
			return loaded.getStack().getProcessor(index + 1);
		}

		@Override
		public void close() {
			if (streaming != null) try { streaming.close(); } catch (IOException ignored) { }
			if (loaded != null) BatchProcessingUtils.close(loaded);
		}
	}
}
