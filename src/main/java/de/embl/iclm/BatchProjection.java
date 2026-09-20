package de.embl.iclm;

import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.ImagePlus;
import ij.plugin.PlugIn;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Batch projection generation, optionally deskewing raw OPM volumes first. */
public class BatchProjection implements PlugIn {
	private static final String DESKEWED = "already deskewed volume";
	private static final String RAW_OPM = "raw OPM volume (deskew before projection)";

	private Parameter parameter;
	private String inputType = DESKEWED;
	private boolean saveIndividual = true;
	private boolean saveMovies = true;
	private File inputFolder;

	private static final class MovieOutput {
		final BatchProcessingUtils.TimeLapseBuilder builder = new BatchProcessingUtils.TimeLapseBuilder();
		final File output;

		MovieOutput(File output) {
			this.output = output;
		}
	}

	@Override
	public void run(String arg) {
		Party.commandStarted ( "Batch Processing > Generate Projection Image" );
		parameter = new Parameter("batch_projection");
		parameter.tryGPU = true;
		parameter.displayResult = false;
		if (!parameter.projX && !parameter.projY && !parameter.projZ) parameter.projZ = true;
		if (!parameter.maxProj && !parameter.avgProj) parameter.maxProj = true;
		if (!showDialog()) return;

		inputFolder = new File(parameter.inputDir);
		if (!inputFolder.isDirectory()) {
			IJ.error("Batch Projection", "Input folder does not exist.");
			return;
		}
		List<String> axes = axes();
		List<String> types = types();
		if (axes.isEmpty() || types.isEmpty()) {
			IJ.error("Batch Projection", "Select at least one projection axis and one projection type.");
			return;
		}

		List<File> files = BatchProcessingUtils.listTiffs(inputFolder, parameter.keywords, parameter.recursive);
		if (!parameter.saveToSame && parameter.saveDir != null && !parameter.saveDir.trim().isEmpty())
			files = BatchProcessingUtils.excludeTree(files, new File(parameter.saveDir));
		List<File> volumeFiles = new ArrayList<File>();
		for (File file : files) {
			if (!BatchProcessingUtils.baseName(file).toLowerCase().contains("projection")) volumeFiles.add(file);
		}
		if (volumeFiles.isEmpty()) {
			IJ.error("Batch Projection", "No matching TIFF volume was found.");
			return;
		}

		parameter.storeParam();

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

	/**			Project every input volume, stopping cleanly when asked to
	 * <p>		The checkpoint is once per input file, which is the unit of work here: a file
	 * 			is opened, projected along every requested axis and closed again, and stopping
	 * 			between two of those leaves only whole projections on disk.
	 */
	private void process(List<File> volumeFiles, List<String> axes, List<String> types) {
		Map<String, MovieOutput> movies = new LinkedHashMap<String, MovieOutput>();
		boolean overwrite = "overwrite".equals(parameter.fileExistStr);
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
			try {
				input = VolumeIO.open(file.getAbsolutePath());
				if (input == null) throw new IllegalArgumentException("Could not open TIFF.");
				working = input;
				if (RAW_OPM.equals(inputType)) {
					working = Deskew.deskew_image(input, parameter);
					if (working == null) throw new IllegalStateException("Deskew failed.");
				}

				File saveRoot = BatchProcessingUtils.saveRootFor(file, inputFolder, parameter.saveDir,
						parameter.saveToSame, parameter.recursive);
				String base = BatchProcessingUtils.baseName(file) + (RAW_OPM.equals(inputType) ? "-deskewed" : "");
				for (String axis : axes) {
					for (String type : types) {
						ImagePlus projection = null;
						try {
							projection = Projection.projection(working, axis, type, parameter.tryGPU);
							if (projection == null) throw new IllegalStateException("Projection returned no image.");
							String operation = type + axis + "projection";
							String title = base + "-" + operation;
							projection.setTitle(title);
							projection.setCalibration(working.getCalibration());
							if (saveIndividual) {
								File folder = parameter.saveSeparate ? new File(saveRoot, type + axis) : saveRoot;
								BatchProcessingUtils.saveTiff(projection, new File(folder, title + ".tif"), overwrite);
							}
							if (parameter.makeTimeLapse) {
								String movieBase = BatchProcessingUtils.movieBaseName(file) +
										(RAW_OPM.equals(inputType) ? "-deskewed" : "");
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
				}
			} catch (Throwable t) {
				failures++;
				IJ.log("Batch Projection failed for " + file.getAbsolutePath() + ": " + t.getMessage());
				t.printStackTrace();
			} finally {
				if (working != input) BatchProcessingUtils.close(working);
				BatchProcessingUtils.close(input);
			}
		}

		/* Deliberately not written when the run was stopped. The individual projections on
		 * disk are each complete, but a time-lapse assembled from only the files that were
		 * reached is a movie that silently misses frames. */
		if (stopped && parameter.makeTimeLapse && saveMovies && !movies.isEmpty())
			IJ.log("Batch Projection: " + movies.size() + " projection movie(s) not written,"
					+ " because the run was stopped before every input was projected.");
		if (!stopped && parameter.makeTimeLapse && saveMovies) {
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

	private boolean showDialog() {
		GenericDialogPlus gd = new PartyDialogPlus("Batch Processing - Generate Projection Image");
		Parameter.styleDialog( gd );
		int length = 38;
		gd.addDirectoryField("input folder...", parameter.inputDir, length);
		gd.addStringField("file name contains (comma = AND)", parameter.keywords, length);
		gd.addCheckbox("recursive", parameter.recursive);
		gd.addChoice("input type", new String[] { DESKEWED, RAW_OPM }, inputType);
		gd.addMessage("Raw OPM geometry (used only when raw input is selected):");
		gd.addNumericField("XY pixel size", parameter.xyPixelSize, 1, 5, "nm");
		gd.addNumericField("Z step size", parameter.zStepSize, 1, 5, "nm");
		gd.addNumericField("OPM angle", parameter.opmAngle, 1, 5, "degree");
		gd.addMessage("create projection image(s):");
		gd.addCheckboxGroup(1, 3, new String[] { "along X", "along Y", "along Z" },
				new boolean[] { parameter.projX, parameter.projY, parameter.projZ });
		gd.addCheckboxGroup(1, 2, new String[] { "maximum", "mean" },
				new boolean[] { parameter.maxProj, parameter.avgProj });
		gd.addCheckbox("combine as time lapse", parameter.makeTimeLapse);
		gd.addCheckbox("use GPU when available", parameter.tryGPU);
		gd.addDirectoryField("save to...", parameter.saveDir, length);
		gd.addCheckbox("save result to the same (data) folder", parameter.saveToSame);
		gd.addCheckbox("save individual projection images", saveIndividual);
		gd.addCheckbox("save projection movies", saveMovies);
		gd.addCheckbox("separate results to sub-folders", parameter.saveSeparate);
		gd.addChoice("if result exists", new String[] { "skip", "overwrite" }, parameter.fileExistStr);
		gd.showDialog();
		if (gd.wasCanceled()) return false;

		parameter.inputDir = gd.getNextString();
		parameter.keywords = gd.getNextString();
		parameter.recursive = gd.getNextBoolean();
		inputType = gd.getNextChoice();
		parameter.xyPixelSize = gd.getNextNumber();
		parameter.zStepSize = gd.getNextNumber();
		parameter.opmAngle = gd.getNextNumber();
		parameter.projX = gd.getNextBoolean();
		parameter.projY = gd.getNextBoolean();
		parameter.projZ = gd.getNextBoolean();
		parameter.maxProj = gd.getNextBoolean();
		parameter.avgProj = gd.getNextBoolean();
		parameter.makeTimeLapse = gd.getNextBoolean();
		parameter.tryGPU = gd.getNextBoolean();
		parameter.saveDir = gd.getNextString();
		parameter.saveToSame = gd.getNextBoolean();
		saveIndividual = gd.getNextBoolean();
		saveMovies = gd.getNextBoolean();
		parameter.saveSeparate = gd.getNextBoolean();
		parameter.fileExistStr = gd.getNextChoice();
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
