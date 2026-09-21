package de.embl.iclm;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;

import java.awt.AWTEvent;
import java.awt.Checkbox;
import java.awt.Choice;
import java.awt.TextField;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Batch projections of a folder of volumes, deskewing raw OPM volumes first where asked.
 *
 * <p>The dialog is the Batch Deskew form cut down to what a projection needs, in the same
 * sections and words: <i>Input setup</i>, <i>Deskew parameters</i> (for raw input only),
 * <i>Channels</i>, <i>Projection</i>, <i>Output setup</i>.
 *
 * <p><b>Channels</b> are the Deskew channel options, applied to the deskewed volume: the camera
 * halves sit side by side in a whole-width volume, raw or deskewed, because the deskew shear
 * acts in Y and Z only. So {@code fold by midline} mirrors the right half onto the left,
 * {@code align with SIFT} does that and aligns it - with a matrix file, or measured from the
 * volume by the same engine Deskew uses - and the {@code only} and {@code separately} options
 * project one half or each half. A volume that already has its channels is projected per channel
 * as it is.
 *
 * <p><b>Formats</b> are what this command exists to write: one deflated TIFF per volume per
 * projection, a time-lapse movie per projection across the whole folder, or both. OME-Zarr is
 * deliberately not offered: a dataset stores its volume, and Batch Deskew already writes one that
 * carries all six projections.
 */
public class BatchProjection implements PlugIn {
	private static final String TITLE = "Batch Processing - Generate Projection Image";
	private static final String DESKEWED = "already deskewed volume";
	private static final String RAW_OPM = "raw OPM volume (deskew before projection)";

	static final String SAVE_IMAGES = "projection images";
	static final String SAVE_MOVIES = "projection movies (time lapse)";
	static final String SAVE_BOTH = "images and movies";
	static final String[] SAVE_FORMATS = { SAVE_IMAGES, SAVE_MOVIES, SAVE_BOTH };
	private static final String PREF = "opm.batchProjection.";

	private Parameter parameter;
	private String inputType = DESKEWED;
	private String saveFormat = SAVE_IMAGES;
	private boolean interpolate = true;
	private File inputFolder;

	private static final class MovieOutput {
		final BatchProcessingUtils.TimeLapseBuilder builder = new BatchProcessingUtils.TimeLapseBuilder();
		final File output;

		MovieOutput(File output) {
			this.output = output;
		}
	}

	/** One volume to project, and the suffix its projections carry after the file's own name. */
	static final class Part {
		final ImagePlus image;
		final String suffix;

		Part(ImagePlus image, String suffix) {
			this.image = image;
			this.suffix = suffix;
		}
	}

	@Override
	public void run(String arg) {
		Party.commandStarted ( "Batch Processing > Generate Projection Image" );
		parameter = new Parameter("batch_projection");
		parameter.displayResult = false;
		if (!parameter.projX && !parameter.projY && !parameter.projZ) parameter.projZ = true;
		if (!parameter.maxProj && !parameter.avgProj) parameter.maxProj = true;
		inputType = ij.Prefs.get(PREF + "inputType", DESKEWED);
		saveFormat = ij.Prefs.get(PREF + "format", SAVE_IMAGES);
		interpolate = ij.Prefs.get(PREF + "interpolate", true);
		if (!showDialog()) return;

		inputFolder = new File(parameter.inputDir);
		if (!inputFolder.isDirectory()) {
			IJ.error(TITLE, "Input folder does not exist.");
			return;
		}
		List<String> axes = axes();
		List<String> types = types();
		if (axes.isEmpty() || types.isEmpty()) {
			IJ.error(TITLE, "Select at least one projection axis and one projection type.");
			return;
		}
		if (RAW_OPM.equals(inputType) && !resolveGeometry()) return;

		List<File> files = BatchProcessingUtils.listTiffs(inputFolder, parameter.keywords,
				parameter.excludeKeywords, parameter.recursive);
		if (!parameter.saveToSame && parameter.saveDir != null && !parameter.saveDir.trim().isEmpty())
			files = BatchProcessingUtils.excludeTree(files, new File(parameter.saveDir));
		List<File> volumeFiles = new ArrayList<File>();
		for (File file : files) {
			if (!BatchProcessingUtils.baseName(file).toLowerCase().contains("projection")) volumeFiles.add(file);
		}
		if (volumeFiles.isEmpty()) {
			IJ.error(TITLE, "No matching TIFF volume was found.");
			return;
		}

		parameter.storeParam();
		ij.Prefs.set(PREF + "inputType", inputType);
		ij.Prefs.set(PREF + "format", saveFormat);
		ij.Prefs.set(PREF + "interpolate", interpolate);

		/* The work runs on a daemon thread and this plugin thread only waits for it, exactly
		 * as Deskew Batch does. Two things follow: ImageJ's Executer thread is not a daemon,
		 * so a run left here would hold the JVM open after Fiji had closed; and the run is
		 * registered with Shutdown, so Batch Processing > Terminate can list and stop it. */
		final List<File> volumes = volumeFiles;
		final List<String> projectionAxes = axes;
		final List<String> projectionTypes = types;
		boolean completed = Shutdown.runCancellable("OPM Batch Projection", new Runnable() {
			@Override public void run() { process(volumes, projectionAxes, projectionTypes); }
		});
		if (!completed)
			IJ.log("Batch Projection stopped early: " + Shutdown.reason() + ".");
	}

	/**
	 * The deskew geometry for raw input: typed in, or read from the parameter file.
	 * <p>
	 * The same rule Batch Deskew applies, so the two commands deskew a folder identically.
	 */
	private boolean resolveGeometry() {
		if (parameter.manualDeskewParameters) return true;
		File file = parameter.deskewmFile == null ? null : new File(parameter.deskewmFile.trim());
		if (file == null || !file.isFile()) {
			IJ.error(TITLE, "Raw input needs its deskew geometry: choose ExperimentalParameters.txt,"
					+ "\nor tick 'overwrite with manual input' and type it in.");
			return false;
		}
		double[] values = IO.loadExperimentalParametersFromFile(file.getAbsolutePath());
		if (values == null) {
			IJ.error(TITLE, "Could not read the deskew geometry from:\n" + file);
			return false;
		}
		parameter.xyPixelSize = values[0];
		parameter.zStepSize = values[1];
		parameter.opmAngle = values[2];
		return true;
	}

	/**			Project every input volume, stopping cleanly when asked to
	 * <p>		The checkpoint is once per input file, which is the unit of work here: a file
	 * 			is opened, projected along every requested axis and closed again, and stopping
	 * 			between two of those leaves only whole projections on disk.
	 */
	private void process(List<File> volumeFiles, List<String> axes, List<String> types) {
		Map<String, MovieOutput> movies = new LinkedHashMap<String, MovieOutput>();
		boolean overwrite = "overwrite".equals(parameter.fileExistStr);
		boolean saveImages = !SAVE_MOVIES.equals(saveFormat);
		boolean makeMovies = !SAVE_IMAGES.equals(saveFormat);
		double[][] alignment = parameter.channelStr.startsWith("align with SIFT")
				? IO.loadMatrixFromFile(parameter.alignmFile) : null;
		int failures = 0;
		boolean stopped = false;
		for (int i = 0; i < volumeFiles.size(); i++) {
			if (Shutdown.stopping()) {
				stopped = true;
				IJ.log("Batch Projection stopping after " + i + " of " + volumeFiles.size()
						+ " file(s): " + Shutdown.reason() + ".");
				break;
			}
			File file = volumeFiles.get(i);
			IJ.showStatus("Batch projection: " + file.getName());
			IJ.showProgress(i, volumeFiles.size());
			ImagePlus input = null;
			ImagePlus working = null;
			List<Part> parts = new ArrayList<Part>();
			try {
				input = VolumeIO.open(file.getAbsolutePath());
				if (input == null) throw new IllegalArgumentException("Could not open TIFF.");
				working = input;
				if (RAW_OPM.equals(inputType)) {
					working = Deskew.deskew_image(input, parameter);
					if (working == null) throw new IllegalStateException("Deskew failed.");
				}
				parts = channelParts(working, parameter.channelStr, alignment, interpolate);

				File saveRoot = BatchProcessingUtils.saveRootFor(file, inputFolder, parameter.saveDir,
						parameter.saveToSame, parameter.recursive);
				String base = BatchProcessingUtils.baseName(file) + (RAW_OPM.equals(inputType) ? "-deskewed" : "");
				for (Part part : parts) for (String axis : axes) for (String type : types) {
					ImagePlus projection = null;
					try {
						projection = Projection.projection(part.image, axis, type, parameter.tryGPU);
						if (projection == null) throw new IllegalStateException("Projection returned no image.");
						String operation = type + axis + "projection";
						String title = base + part.suffix + "-" + operation;
						projection.setTitle(title);
						projection.setCalibration(working.getCalibration());
						if (saveImages) {
							File folder = parameter.saveSeparate ? new File(saveRoot, type + axis) : saveRoot;
							BatchProcessingUtils.saveTiff(projection, new File(folder, title + ".tif"), overwrite);
						}
						if (makeMovies) {
							String movieBase = BatchProcessingUtils.movieBaseName(file) +
									(RAW_OPM.equals(inputType) ? "-deskewed" : "") + part.suffix;
							File folder = parameter.saveSeparate ? new File(saveRoot, "projection-movies") : saveRoot;
							File output = new File(folder, movieBase + "-" + operation + "-timeLapse.tif");
							String key = output.getAbsolutePath();
							MovieOutput movie = movies.get(key);
							if (movie == null) {
								movie = new MovieOutput(output);
								movies.put(key, movie);
							}
							movie.builder.append(projection, BatchProcessingUtils.baseName(file));
						}
					} finally {
						BatchProcessingUtils.close(projection);
					}
				}
			} catch (Throwable t) {
				failures++;
				IJ.log("Batch Projection failed for " + file.getAbsolutePath() + ": " + t.getMessage());
				t.printStackTrace();
			} finally {
				for (Part part : parts)
					if (part.image != working && part.image != input) BatchProcessingUtils.close(part.image);
				if (working != input) BatchProcessingUtils.close(working);
				BatchProcessingUtils.close(input);
			}
		}

		/* Deliberately not written when the run was stopped. The individual projections on
		 * disk are each complete, but a time-lapse assembled from only the files that were
		 * reached is a movie that silently misses frames. */
		if (stopped && makeMovies && !movies.isEmpty())
			IJ.log("Batch Projection: " + movies.size() + " projection movie(s) not written,"
					+ " because the run was stopped before every input was projected.");
		if (!stopped && makeMovies) {
			for (MovieOutput movie : movies.values()) {
				ImagePlus image = movie.builder.build(BatchProcessingUtils.baseName(movie.output));
				try {
					BatchProcessingUtils.saveTiff(image, movie.output, overwrite);
				} finally {
					BatchProcessingUtils.close(image);
				}
			}
		}
		IJ.showProgress(1.0);
		IJ.log("Batch Projection " + (stopped ? "stopped" : "finished") + ": "
				+ volumeFiles.size() + " input file(s), " + failures + " failure(s).");
	}

	/**
	 * What a channel option makes of one deskewed volume, before it is projected.
	 * <p>
	 * The camera halves of a whole width sit side by side whether the volume is raw or
	 * deskewed - the shear acts in Y and Z only - so the Deskew options apply here unchanged. A
	 * volume that already has channels was composed when it was written and is projected as it
	 * is; so is any volume under {@code whole image}.
	 *
	 * @param alignment		: a 2 x 3 right-onto-left matrix for {@code align with SIFT}, or null
	 * 						  to measure one from the volume, as Deskew does
	 */
	static List<Part> channelParts(ImagePlus volume, String option, double[][] alignment,
			boolean interpolate) {
		List<Part> parts = new ArrayList<Part>();
		if (option == null || "whole image".equals(option) || volume.getNChannels() > 1
				|| volume.getWidth() < 2) {
			if (option != null && !"whole image".equals(option) && volume.getNChannels() > 1)
				IJ.log("Batch Projection: " + volume.getTitle() + " already has its channels,"
						+ " so it is projected as it is rather than split by '" + option + "'.");
			parts.add(new Part(volume, ""));
			return parts;
		}
		int halfWidth = (volume.getWidth() + 1) / 2;
		ImagePlus left = half(volume, 0, halfWidth, false, "-left");
		ImagePlus right = half(volume, volume.getWidth() - halfWidth, halfWidth,
				"fold by midline".equals(option) || option.startsWith("align with SIFT"), "-right");
		if ("only left".equals(option)) {
			BatchProcessingUtils.close(right);
			parts.add(new Part(left, "-left"));
		} else if ("only right".equals(option)) {
			BatchProcessingUtils.close(left);
			parts.add(new Part(right, "-right"));
		} else if ("left & right separately".equals(option)) {
			parts.add(new Part(left, "-left"));
			parts.add(new Part(right, "-right"));
		} else {
			if (option.startsWith("align with SIFT")) {
				double[][] matrix = alignment;
				if (matrix == null) matrix = SIFT.trySIFTalignment(left, right, false);
				if (matrix != null) SIFT.alignStackSIFT2(right, matrix, interpolate);
				else IJ.log("Batch Projection: no alignment could be measured for "
						+ volume.getTitle() + "; its right half is flipped only.");
			}
			ImagePlus composite = Partition.combineChannel(new ImagePlus[] { left, right });
			composite.setCalibration(volume.getCalibration().copy());
			BatchProcessingUtils.close(left);
			BatchProcessingUtils.close(right);
			parts.add(new Part(composite, ""));
		}
		return parts;
	}

	/** One half of every plane, mirrored when asked, calibrated as the volume. */
	private static ImagePlus half(ImagePlus volume, int x, int width, boolean mirror, String suffix) {
		ImageStack stack = new ImageStack(width, volume.getHeight());
		for (int index = 1; index <= volume.getStackSize(); index++) {
			ImageProcessor plane = volume.getStack().getProcessor(index);
			plane.setRoi(x, 0, width, volume.getHeight());
			ImageProcessor cropped = plane.crop();
			plane.resetRoi();
			if (mirror) cropped.flipHorizontal();
			stack.addSlice(volume.getStack().getSliceLabel(index), cropped);
		}
		ImagePlus half = new ImagePlus(volume.getTitle() + suffix, stack);
		half.setDimensions(1, Math.max(1, volume.getNSlices()), Math.max(1, volume.getNFrames()));
		half.setCalibration(volume.getCalibration().copy());
		return half;
	}

	private boolean showDialog() {
		final PartyDialog gd = new PartyDialog(TITLE);
		Parameter.styleDialog( gd );
		final int length = 55, inset = 95, section = 20;

		gd.setInsets(0, 15, 5);
		Parameter.addSection(gd, "Input setup:");
		gd.addDirectoryField("input folder", parameter.inputDir, length);
		gd.setInsets(0, inset, 0);
		gd.addCheckbox("include sub-folders", parameter.recursive);
		gd.addStringField("file name include", parameter.keywords, length);
		gd.addStringField("file name exclude", parameter.excludeKeywords, length);
		gd.addChoice("input type", new String[] { DESKEWED, RAW_OPM }, inputType);
		final Choice typeChoice = (Choice) gd.getChoices().lastElement();

		gd.setInsets(section, 15, 5);
		Parameter.addSection(gd, "Deskew parameters:");
		gd.setInsets(0, inset, 0);
		gd.addCheckbox("overwrite with manual input", parameter.manualDeskewParameters);
		final Checkbox chkManual = Parameter.lastCheckbox(gd);
		gd.addNumericField("XY pixel size", parameter.xyPixelSize, 1, 5, "nm");
		final TextField xyField = Parameter.lastStringOrNumber(gd.getNumericFields());
		gd.addNumericField("Z step size", parameter.zStepSize, 1, 5, "nm");
		final TextField zField = Parameter.lastStringOrNumber(gd.getNumericFields());
		gd.addNumericField("OPM angle", parameter.opmAngle, 1, 5, "degree");
		final TextField angleField = Parameter.lastStringOrNumber(gd.getNumericFields());
		gd.addFileField("parameter file", parameter.deskewmFile, length);
		final TextField parameterFileField = Parameter.lastStringOrNumber(gd.getStringFields());

		gd.setInsets(section, 15, 5);
		Parameter.addSection(gd, "Channels:");
		gd.addChoice("channel option", Parameter.CHANNEL_OPTIONS, parameter.channelStr);
		final Choice optionChoice = (Choice) gd.getChoices().lastElement();
		gd.addChoice("interpolation", Parameter.INTERPOLATION_OPTIONS,
				Parameter.interpolationChoice(interpolate));
		final Choice interpolationChoice = (Choice) gd.getChoices().lastElement();
		gd.addFileField("align matrix", parameter.alignmFile, length);
		final TextField alignField = Parameter.lastStringOrNumber(gd.getStringFields());

		gd.setInsets(section, 15, 5);
		Parameter.addSection(gd, "Projection:");
		gd.setInsets(0, inset, 0);
		gd.addCheckboxGroup(1, 3, new String[] { "along X", "along Y", "along Z" },
				new boolean[] { parameter.projX, parameter.projY, parameter.projZ });
		gd.setInsets(0, inset, 0);
		gd.addCheckboxGroup(1, 2, new String[] { "maximum", "mean" },
				new boolean[] { parameter.maxProj, parameter.avgProj });

		gd.setInsets(section, 15, 5);
		Parameter.addSection(gd, "Output setup:");
		gd.addDirectoryField("save to", parameter.saveDir, length);
		final TextField saveDirField = Parameter.lastStringOrNumber(gd.getStringFields());
		gd.setInsets(0, inset, 0);
		gd.addCheckbox("save result to the same (data) folder", parameter.saveToSame);
		final Checkbox chkToSame = Parameter.lastCheckbox(gd);
		gd.addChoice("format", SAVE_FORMATS, saveFormat);
		gd.setInsets(0, inset, 0);
		gd.addCheckbox("separate results to sub-folders", parameter.saveSeparate);
		gd.addChoice("if result exists", new String[] { "skip", "overwrite" }, parameter.fileExistStr);
		gd.setInsets(0, inset, 0);
		gd.addCheckbox("use GPU when available", parameter.tryGPU);

		/* Grey out what another choice makes meaningless rather than ignore it silently: the
		 * geometry belongs to raw input only, the matrix and the interpolation to alignment
		 * only, and the result folder to a run that does not write beside its data. */
		final Runnable refresh = new Runnable() {
			@Override public void run() {
				boolean raw = RAW_OPM.equals(typeChoice.getSelectedItem());
				boolean manual = chkManual.getState();
				Parameter.enable(chkManual, raw);
				Parameter.enable(xyField, raw && manual);
				Parameter.enable(zField, raw && manual);
				Parameter.enable(angleField, raw && manual);
				Parameter.enable(parameterFileField, raw && !manual);
				boolean align = optionChoice.getSelectedItem().startsWith("align with SIFT");
				Parameter.enable(alignField, align);
				Parameter.enable(interpolationChoice, align);
				Parameter.enable(saveDirField, !chkToSame.getState());
			}
		};
		gd.addDialogListener(new DialogListener() {
			@Override public boolean dialogItemChanged(GenericDialog dialog, AWTEvent event) {
				refresh.run();
				return true;
			}
		});
		refresh.run();

		gd.addHelp(Help.projectionBatch);
		gd.showDialog();
		if (gd.wasCanceled()) return false;

		parameter.inputDir = gd.getNextString();
		parameter.recursive = gd.getNextBoolean();
		parameter.keywords = gd.getNextString();
		parameter.excludeKeywords = gd.getNextString();
		inputType = gd.getNextChoice();
		parameter.manualDeskewParameters = gd.getNextBoolean();
		parameter.xyPixelSize = gd.getNextNumber();
		parameter.zStepSize = gd.getNextNumber();
		parameter.opmAngle = gd.getNextNumber();
		parameter.deskewmFile = gd.getNextString();
		parameter.channelStr = gd.getNextChoice();
		interpolate = Parameter.isBilinear(gd.getNextChoice());
		parameter.alignmFile = gd.getNextString();
		parameter.projX = gd.getNextBoolean();
		parameter.projY = gd.getNextBoolean();
		parameter.projZ = gd.getNextBoolean();
		parameter.maxProj = gd.getNextBoolean();
		parameter.avgProj = gd.getNextBoolean();
		parameter.saveDir = gd.getNextString();
		parameter.saveToSame = gd.getNextBoolean();
		saveFormat = gd.getNextChoice();
		parameter.saveSeparate = gd.getNextBoolean();
		parameter.fileExistStr = gd.getNextChoice();
		parameter.tryGPU = gd.getNextBoolean();
		// the movies are the time lapse; nothing else of the old combine setting is left to ask
		parameter.makeTimeLapse = !SAVE_IMAGES.equals(saveFormat);
		return true;
	}

	private List<String> axes() {
		List<String> result = new ArrayList<String>();
		if (parameter.projX) result.add("X");
		if (parameter.projY) result.add("Y");
		if (parameter.projZ) result.add("Z");
		return result;
	}

	private List<String> types() {
		List<String> result = new ArrayList<String>();
		if (parameter.maxProj) result.add("max");
		if (parameter.avgProj) result.add("avg");
		return result;
	}
}
