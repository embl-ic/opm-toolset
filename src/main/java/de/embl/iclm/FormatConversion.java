package de.embl.iclm;

import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.ImagePlus;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.apache.commons.io.FileUtils;

/**
 * Storage-format conversion for raw acquisition volumes: BigTIFF in, Deflate TIFF or
 * canonical OME-Zarr out.
 *
 * <p>The microscope writes every raw volume as an uncompressed BigTIFF. A deskewed volume
 * compresses well because the shear leaves most of its bounding box empty, but a <em>raw</em>
 * volume is solid data, and it is still worth converting: a raw acquisition folder is tens of
 * gigabytes of uncompressed strips that nothing but this plugin and Bio-Formats will open
 * quickly. Deflate TIFF is the same pixels in an ordinary multi-page TIFF that Fiji, Python
 * and MATLAB all read; OME-Zarr is the same pixels chunked, so a viewer can read a plane
 * without touching the rest.
 *
 * <p><b>Nothing here deskews.</b> This is a format change and only a format change: the
 * pixels that come out are the pixels that went in, and the OME-Zarr it writes records
 * {@code opm.contentKind = "raw"} so a reader cannot mistake it for a deskewed dataset.
 * {@link OmeZarrConverter} remains the path that deskews.
 *
 * <p>One command reads in the other direction: exporting a region of a canonical OME-Zarr as a
 * deflated TIFF. It does not deskew either, but it is the single path here whose output can
 * differ from its input pixel for pixel, because it offers the viewer's runtime views and a
 * flip or an alignment can be baked into what it writes. Left at stored channels it too is a
 * pure format change. It lives here rather than in the viewer because reading a region to look
 * at it and converting a dataset into another format are different jobs.
 *
 * <p>Neither direction ever holds the whole volume. Planes are pulled one at a time from
 * {@link FastTiffReader.PlaneReader} and pushed a batch or a slab at a time into
 * {@link FastTiffWriter} or {@link OmeZarrWriter}, which is the difference between converting
 * a 30 GB acquisition and failing to allocate one.
 *
 * <p>Registered four times: once under Batch Processing for a folder, once per target under
 * Utilities for a single file, and once for the OME-Zarr region export.
 */
public class FormatConversion implements PlugIn {

	public static final String TARGET_TIFF = "deflated TIFF";
	public static final String TARGET_ZARR = "OME-Zarr";
	public static final String TARGET_BOTH = "deflated TIFF + OME-Zarr";
	public static final String[] TARGETS = { TARGET_TIFF, TARGET_ZARR, TARGET_BOTH };

	/** Suffix that keeps a converted TIFF from colliding with its own source. */
	static final String TIFF_SUFFIX = "-deflated";

	private static final String PREF = "opm.formatConversion.";

	/** What one conversion run should do. */
	public static final class Options {
		public String target = TARGET_TIFF;
		public int deflateLevel = FastTiffWriter.DEFAULT_LEVEL;
		public boolean overwrite = false;
		/** Recorded in the OME-Zarr; 0 means "unknown", which is written as 1 µm. */
		public double xyPixelSizeUm = 0;
		public double zStepSizeUm = 0;

		public boolean wantsTiff() { return !TARGET_ZARR.equals(target); }
		public boolean wantsZarr() { return !TARGET_TIFF.equals(target); }
	}


	// ---- commands -------------------------------------------------------------------

	@Override
	public void run(String arg) {
		Party.commandStarted ( "Batch Processing > Format conversion" );
		String mode = arg == null ? "" : arg.trim().toLowerCase(Locale.ROOT);
		if ("tiff".equals(mode)) runSingle(TARGET_TIFF);
		else if ("zarr".equals(mode)) runSingle(TARGET_ZARR);
		else if ("zarr-region".equals(mode)) runZarrRegionExport();
		else runBatch();
	}

	/** Convert every matching volume under one folder. */
	private void runBatch() {
		GenericDialogPlus gd = new PartyDialogPlus("OPM Format Conversion");
		Parameter.styleDialog( gd );
		int length = 40;
		gd.addMessage("Convert raw acquisition volumes to a compressed storage format.\n"
				+ "No deskew is applied; only the file format changes.");
		gd.addDirectoryField("input folder", ij.Prefs.get(PREF + "input", ""), length);
		gd.addStringField("file name contains (separate multiple by \",\")",
				ij.Prefs.get(PREF + "include", ""), length);
		gd.addStringField("file name excludes", ij.Prefs.get(PREF + "exclude", ""), length);
		gd.addCheckbox("include sub-folders", ij.Prefs.get(PREF + "recursive", false));
		gd.addDirectoryField("save to", ij.Prefs.get(PREF + "output", ""), length);
		gd.addChoice("target format", TARGETS, ij.Prefs.get(PREF + "target", TARGET_TIFF));
		gd.addNumericField("Deflate level (0-9)",
				ij.Prefs.get(PREF + "level", FastTiffWriter.DEFAULT_LEVEL), 0);
		gd.addChoice("if result exists", new String[] { "skip", "overwrite" },
				ij.Prefs.get(PREF + "exists", "skip"));
		gd.showDialog();
		if (gd.wasCanceled()) return;

		final File input = new File(gd.getNextString().trim());
		final String include = gd.getNextString().trim();
		final String exclude = gd.getNextString().trim();
		final boolean recursive = gd.getNextBoolean();
		String outputText = gd.getNextString().trim();
		final Options options = new Options();
		options.target = gd.getNextChoice();
		options.deflateLevel = clampLevel((int) gd.getNextNumber());
		options.overwrite = "overwrite".equals(gd.getNextChoice());

		if (!input.isDirectory()) {
			IJ.error("OPM Format Conversion", "The input folder does not exist:\n" + input);
			return;
		}
		final File output = outputText.isEmpty() ? new File(input, "converted") : new File(outputText);

		ij.Prefs.set(PREF + "input", input.getAbsolutePath());
		ij.Prefs.set(PREF + "include", include);
		ij.Prefs.set(PREF + "exclude", exclude);
		ij.Prefs.set(PREF + "recursive", recursive);
		ij.Prefs.set(PREF + "output", output.getAbsolutePath());
		ij.Prefs.set(PREF + "target", options.target);
		ij.Prefs.set(PREF + "level", options.deflateLevel);
		ij.Prefs.set(PREF + "exists", options.overwrite ? "overwrite" : "skip");

		final List<File> files = BatchProcessingUtils.listTiffs(input, include, exclude, recursive);
		final List<File> inputs = BatchProcessingUtils.excludeTree(files, output);
		if (inputs.isEmpty()) {
			IJ.error("OPM Format Conversion", "No TIFF left to convert in:\n" + input.getAbsolutePath());
			return;
		}
		readGeometry(input, options);

		Shutdown.runCancellable("OPM Format Conversion", new Runnable() {
			@Override
			public void run() { convertAll(inputs, input, output, options, recursive); }
		});
	}

	/** Convert one chosen file to one chosen target. */
	private void runSingle(String target) {
		GenericDialogPlus gd = new PartyDialogPlus("OPM Convert to " + target);
		Parameter.styleDialog( gd );
		gd.addMessage("Convert one raw acquisition volume to " + target + ".\n"
				+ "No deskew is applied; only the file format changes.");
		gd.addFileField("input volume", ij.Prefs.get(PREF + "single", ""), 40);
		gd.addDirectoryField("save to", ij.Prefs.get(PREF + "output", ""), 40);
		if (TARGET_TIFF.equals(target))
			gd.addNumericField("Deflate level (0-9)",
					ij.Prefs.get(PREF + "level", FastTiffWriter.DEFAULT_LEVEL), 0);
		gd.addChoice("if result exists", new String[] { "skip", "overwrite" },
				ij.Prefs.get(PREF + "exists", "skip"));
		gd.showDialog();
		if (gd.wasCanceled()) return;

		final File input = new File(gd.getNextString().trim());
		String outputText = gd.getNextString().trim();
		final Options options = new Options();
		options.target = target;
		if (TARGET_TIFF.equals(target)) options.deflateLevel = clampLevel((int) gd.getNextNumber());
		options.overwrite = "overwrite".equals(gd.getNextChoice());

		if (!input.isFile()) {
			IJ.error("OPM Format Conversion", "The input volume does not exist:\n" + input);
			return;
		}
		final File output = outputText.isEmpty()
				? new File(input.getParentFile(), "converted") : new File(outputText);

		ij.Prefs.set(PREF + "single", input.getAbsolutePath());
		ij.Prefs.set(PREF + "output", output.getAbsolutePath());
		ij.Prefs.set(PREF + "level", options.deflateLevel);
		ij.Prefs.set(PREF + "exists", options.overwrite ? "overwrite" : "skip");

		readGeometry(input.getParentFile(), options);
		final List<File> one = new ArrayList<File>();
		one.add(input);
		Shutdown.runCancellable("OPM Format Conversion", new Runnable() {
			@Override
			public void run() { convertAll(one, input.getParentFile(), output, options, false); }
		});
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
	 * 			ROI drawn on an open view means and why the ROI button is offered. Zero width,
	 * 			height or last z means the whole extent of that axis.
	 */
	private void runZarrRegionExport() {
		final ChannelOperationSettings channels = new ChannelOperationSettings();
		channels.load();
		String[] selections = {
			OmeZarrView.SELECT_ALL, OmeZarrView.SELECT_CONFIGURED,
			OmeZarrView.SELECT_LEFT, OmeZarrView.SELECT_RIGHT
		};

		GenericDialogPlus gd = new PartyDialogPlus("OPM Export OME-Zarr region to TIFF");
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
		gd.addNumericField("Deflate level (0-9)",
				ij.Prefs.get(PREF + "level", FastTiffWriter.DEFAULT_LEVEL), 0);
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
		final int level = clampLevel((int) gd.getNextNumber());
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
		ij.Prefs.set(PREF + "level", level);

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

	private static void convertAll(List<File> inputs, File inputRoot, File outputRoot,
			Options options, boolean reproduceTree) {
		Log log = null;
		int done = 0, skipped = 0, failed = 0;
		long start = System.nanoTime();
		try {
			log = new Log("format-conversion", outputRoot.getAbsolutePath(), true);
			log.add("OPM Format Conversion: " + inputs.size() + " file(s), target " + options.target
					+ ", Deflate level " + options.deflateLevel
					+ ", " + (options.overwrite ? "overwrite" : "skip") + " existing"
					+ "; input " + inputRoot + "; output " + outputRoot);
			IJ.log("OPM Format Conversion started: " + inputs.size() + " file(s) to " + options.target + ".");

			int index = 0;
			for (File input : inputs) {
				if (Shutdown.stopping()) {
					log.add("stopped before " + input.getName() + ": " + Shutdown.reason());
					break;
				}
				IJ.showProgress(index++, inputs.size());
				File folder = reproduceTree
						? BatchProcessingUtils.saveRootFor(input, inputRoot, outputRoot.getAbsolutePath(), false, true)
						: outputRoot;
				try {
					int outcome = convertOne(input, folder, options, log);
					if (outcome > 0) done++;
					else skipped++;
				} catch (Throwable failure) {
					failed++;
					log.add("FAILED " + input.getAbsolutePath() + ": " + failure);
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
				long bytes = convertToDeflatedTiff(input, target, options.deflateLevel);
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
		VolumeSource source = VolumeSource.open(input);
		try {
			FastTiffWriter.Layout layout = new FastTiffWriter.Layout();
			layout.width = source.width;
			layout.height = source.height;
			layout.slices = source.depth;
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
