package de.embl.iclm;

import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.ImagePlus;
import ij.plugin.PlugIn;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Batch Richardson-Lucy deconvolution and experimental PSF generation. */
public class BatchDeconvolution implements PlugIn {
	private static final String DECONVOLVE = "deconvolve volumes with a PSF";
	private static final String MAKE_PSF = "generate averaged PSF from bead volumes";

	private Parameter parameter;
	private String operation = DECONVOLVE;
	private String psfPath = "";
	private File inputFolder;

	@Override
	public void run(String arg) {
		Party.commandStarted ( "Batch Processing > Deconvolution" );
		parameter = new Parameter("batch_deconvolution");
		parameter.tryGPU = true;
		parameter.autoPartition = true;
		parameter.displayResult = false;
		parameter.loadFromManager = false;
		parameter.addToManager = false;
		if (!showDialog()) return;

		inputFolder = new File(parameter.inputDir);
		if (!inputFolder.isDirectory()) {
			IJ.error("Batch Deconvolution", "Input folder does not exist.");
			return;
		}
		List<File> files = BatchProcessingUtils.listTiffs(inputFolder, parameter.keywords, parameter.recursive);
		if (!parameter.saveToSame && parameter.saveDir != null && !parameter.saveDir.trim().isEmpty())
			files = BatchProcessingUtils.excludeTree(files, new File(parameter.saveDir));
		if (files.isEmpty()) {
			IJ.error("Batch Deconvolution", "No matching TIFF volume was found.");
			return;
		}

		parameter.storeParam();
		if (DECONVOLVE.equals(operation)) {
			deconvolve(files);	// registers its own run once the PSF has been opened
			return;
		}
		final List<File> beadFiles = files;
		boolean finished = Shutdown.runCancellable("OPM Batch PSF from Beads", new Runnable() {
			@Override public void run() { makePsf(beadFiles); }
		});
		if (!finished)
			IJ.log("Batch PSF generation stopped early: " + Shutdown.reason() + ".");
	}

	private void deconvolve(List<File> files) {
		File psfFile = new File(psfPath);
		if (!psfFile.isFile()) {
			IJ.error("Batch Deconvolution", "Select a valid PSF TIFF file.");
			return;
		}
		ImagePlus psf = VolumeIO.open(psfFile.getAbsolutePath());
		if (psf == null) {
			IJ.error("Batch Deconvolution", "Could not open the PSF image.");
			return;
		}
		parameter.impPSF = psf;

		/* On a daemon thread, so ImageJ's non-daemon Executer thread cannot hold the JVM open
		 * after Fiji has closed, and so Batch Processing > Terminate can list and stop it. */
		final List<File> volumes = files;
		final ImagePlus pointSpread = psf;
		final File pointSpreadFile = psfFile;
		boolean finished = Shutdown.runCancellable("OPM Batch Deconvolution", new Runnable() {
			@Override public void run() { process(volumes, pointSpread, pointSpreadFile); }
		});
		if (!finished)
			IJ.log("Batch Deconvolution stopped early: " + Shutdown.reason() + ".");
	}

	/**			Deconvolve every input volume, stopping cleanly when asked to
	 * <p>		The checkpoint is once per volume. A single deconvolution is many iterations
	 * 			over a whole stack and cannot be interrupted part way without throwing the
	 * 			result away, so the honest place to stop is between two of them.
	 */
	private void process(List<File> files, ImagePlus psf, File psfFile) {
		boolean overwrite = "overwrite".equals(parameter.fileExistStr);
		int failures = 0;
		int completed = 0;
		boolean stopped = false;
		try {
			for (int i = 0; i < files.size(); i++) {
				if (Shutdown.stopping()) {
					stopped = true;
					IJ.log("Batch Deconvolution stopping after " + i + " of " + files.size()
							+ " volume(s): " + Shutdown.reason() + ".");
					break;
				}
				File file = files.get(i);
				if (sameFile(file, psfFile)) continue;
				String algorithm = parameter.deconvMethod.contains("Total Variation") ? "RLTV" : "RL";
				String title = BatchProcessingUtils.baseName(file) + "-deconvolved-" + algorithm + parameter.numIter;
				File saveRoot = BatchProcessingUtils.saveRootFor(file, inputFolder, parameter.saveDir,
						parameter.saveToSame, parameter.recursive);
				File folder = parameter.saveSeparate ? new File(saveRoot, "deconvolution") : saveRoot;
				File outputFile = new File(folder, title + ".tif");
				if (outputFile.exists() && !overwrite) {
					IJ.log("Batch Deconvolution skip existing result: " + outputFile.getAbsolutePath());
					completed++;
					continue;
				}
				ImagePlus input = null;
				ImagePlus output = null;
				try {
					IJ.showStatus("Batch deconvolution: " + file.getName());
					input = VolumeIO.open(file.getAbsolutePath());
					if (input == null) throw new IllegalArgumentException("Could not open TIFF.");
					output = Partition.tileDeconvolution(input, psf, parameter.deconvMethod,
							psf.getWidth(), parameter.numIter, parameter.regFactor);
					if (output == null) throw new IllegalStateException("Deconvolution returned no image.");
					output.setTitle(title);
					output.setCalibration(input.getCalibration());
					if (!BatchProcessingUtils.saveTiff(output, outputFile, overwrite))
						throw new IllegalStateException("Output exists or could not be saved.");
					completed++;
				} catch (Throwable t) {
					failures++;
					IJ.log("Batch Deconvolution failed for " + file.getAbsolutePath() + ": " + t.getMessage());
					t.printStackTrace();
				} finally {
					BatchProcessingUtils.close(output);
					BatchProcessingUtils.close(input);
				}
				IJ.showProgress(i + 1, files.size());
			}
		} finally {
			BatchProcessingUtils.close(psf);
			parameter.impPSF = null;
		}
		IJ.log("Batch Deconvolution " + (stopped ? "stopped" : "finished") + ": "
				+ completed + " volume(s), " + failures + " failure(s).");
	}

	/**			Collect beads from every input and combine them into one PSF per channel
	 * <p>		The checkpoint is once per input file, during collection. The combine step
	 * 			afterwards is deliberately left to finish: by then the beads are already in
	 * 			memory, it is short, and stopping there would throw away all the reading.
	 */
	private void makePsf(List<File> files) {
		Map<String, List<ImagePlus>> groupedBeads = new LinkedHashMap<String, List<ImagePlus>>();
		Deconvolve processor = new Deconvolve();
		int failures = 0;
		for (int i = 0; i < files.size(); i++) {
			if (Shutdown.stopping()) {
				IJ.log("Batch PSF generation stopping after " + i + " of " + files.size()
						+ " input(s): " + Shutdown.reason() + ".");
				break;
			}
			File file = files.get(i);
			ImagePlus input = null;
			try {
				IJ.showStatus("Generate PSF: " + file.getName());
				input = VolumeIO.open(file.getAbsolutePath());
				if (input == null) throw new IllegalArgumentException("Could not open TIFF.");
				parameter.impInput = input;
				String token = BatchProcessingUtils.acquisitionChannelToken(file);
				for (Deconvolve.PsfChannelBeads channel : processor.preparePsfChannels(parameter)) {
					if (channel.preparation.getAccepted().isEmpty()) continue;
					String group = token + channel.suffix;
					List<ImagePlus> beads = groupedBeads.get(group);
					if (beads == null) {
						beads = new ArrayList<ImagePlus>();
						groupedBeads.put(group, beads);
					}
					beads.addAll(channel.preparation.getAccepted());
				}
			} catch (Throwable t) {
				failures++;
				IJ.log("PSF generation failed for " + file.getAbsolutePath() + ": " + t.getMessage());
				t.printStackTrace();
			} finally {
				parameter.impInput = null;
				BatchProcessingUtils.close(input);
			}
			IJ.showProgress(i + 1, files.size());
		}
		if (groupedBeads.isEmpty()) {
			IJ.error("Batch Deconvolution", "No PSF could be generated from the selected bead volumes.");
			return;
		}

		File first = files.get(0);
		File saveRoot = BatchProcessingUtils.saveRootFor(first, inputFolder, parameter.saveDir,
				parameter.saveToSame, parameter.recursive);
		File folder = parameter.saveSeparate ? new File(saveRoot, "PSF") : saveRoot;
		boolean overwrite = "overwrite".equals(parameter.fileExistStr);
		int saved = 0;
		int accepted = 0;
		int outputFailures = 0;
		for (Map.Entry<String, List<ImagePlus>> entry : groupedBeads.entrySet()) {
			List<ImagePlus> beads = entry.getValue();
			accepted += beads.size();
			ImagePlus psf = null;
			try {
				psf = Beads.combineBeadsImage(beads, parameter.avgMethod);
				if (psf == null) continue;
				String title = "PSF-" + Deconvolve.psfGroupLabel(entry.getKey());
				psf.setTitle(title);
				File output = new File(folder, title + ".tif");
				if (output.exists() && !overwrite) {
					IJ.log("Batch PSF generation skip existing result: " + output.getAbsolutePath());
					saved++;
					continue;
				}
				if (!BatchProcessingUtils.saveTiff(psf, output, overwrite))
					throw new IllegalStateException("PSF output exists or could not be saved: " + title);
				saved++;
			} catch (Throwable t) {
				outputFailures++;
				IJ.log("Batch PSF output failed for " + entry.getKey() + ": " + t.getMessage());
				t.printStackTrace();
			} finally {
				for (ImagePlus bead : beads) BatchProcessingUtils.close(bead);
				beads.clear();
				BatchProcessingUtils.close(psf);
			}
		}
		IJ.log("Batch PSF generation finished: " + saved + " channel PSF(s) from " + accepted +
				" accepted bead(s), " + failures + " input failure(s), " + outputFailures + " output failure(s).");
		if (saved == 0) IJ.error("Batch Deconvolution", "No channel PSF could be saved.");
	}

	private boolean showDialog() {
		GenericDialogPlus gd = new PartyDialogPlus("Batch Processing - Deconvolution");
		Parameter.styleDialog( gd );
		int length = 40;
		gd.addChoice("operation", new String[] { DECONVOLVE, MAKE_PSF }, operation);
		gd.addDirectoryField("input folder...", parameter.inputDir, length);
		gd.addStringField("file name contains (comma-separated)", parameter.keywords, length);
		gd.addCheckbox("recursive", parameter.recursive);
		gd.addMessage("Deconvolution settings:");
		gd.addFileField("PSF TIFF", psfPath, length);
		gd.addChoice("method", new String[] { "Richardson-Lucy (FFT)",
				"Richardson-Lucy Total Variation" }, parameter.deconvMethod);
		gd.addNumericField("number of iterations", parameter.numIter, 0);
		gd.addNumericField("regularization factor", parameter.regFactor, 5);
		gd.addMessage("Experimental PSF settings:");
		gd.addChoice("bead volume type", new String[] { "auto detection", "OPM raw volume", "deskewed volume" },
				parameter.imageType);
		gd.addChoice("channel layout", Parameter.PSF_CHANNEL_LAYOUTS, parameter.psfChannelLayout);
		gd.addCheckbox("flip the mirrored right half", parameter.psfFlipRight);
		gd.addNumericField("XY radius", parameter.radiusXY, 0, 5, "pixel");
		gd.addNumericField("Z radius", parameter.radiusZ, 0, 5, "pixel");
		gd.addNumericField("approximate bead count", parameter.beadsCount, 0);
		gd.addChoice("PSF result as", new String[] { "median average", "mean average", "all beads" },
				parameter.avgMethod);
		gd.addNumericField("background shell fraction", parameter.psfShellFraction, 2);
		gd.addNumericField("minimum peak SNR", parameter.psfMinSnr, 2);
		gd.addNumericField("minimum peak/background ratio", parameter.psfMinSbr, 2);
		gd.addNumericField("maximum normalized center offset", parameter.psfMaxCenterOffset, 2);
		gd.addNumericField("saturation level (0 = native maximum)", parameter.psfSaturationLevel, 1);
		gd.addCheckbox("reject candidates with a nearby bead", parameter.psfRejectNeighbors);
		gd.addNumericField("raw XY pixel size", parameter.xyPixelSize, 1, 5, "nm");
		gd.addNumericField("raw Z step size", parameter.zStepSize, 1, 5, "nm");
		gd.addNumericField("raw OPM angle", parameter.opmAngle, 1, 5, "degree");
		gd.addDirectoryField("save to...", parameter.saveDir, length);
		gd.addCheckbox("save result to the same (data) folder", parameter.saveToSame);
		gd.addCheckbox("separate results to sub-folders", parameter.saveSeparate);
		gd.addChoice("if result exists", new String[] { "skip", "overwrite" }, parameter.fileExistStr);
		gd.showDialog();
		if (gd.wasCanceled()) return false;

		operation = gd.getNextChoice();
		parameter.inputDir = gd.getNextString();
		parameter.keywords = gd.getNextString();
		parameter.recursive = gd.getNextBoolean();
		psfPath = gd.getNextString();
		parameter.deconvMethod = gd.getNextChoice();
		parameter.numIter = Math.max(1, (int) gd.getNextNumber());
		parameter.regFactor = Math.max(0.0, gd.getNextNumber());
		parameter.imageType = gd.getNextChoice();
		parameter.psfChannelLayout = gd.getNextChoice();
		parameter.psfFlipRight = gd.getNextBoolean();
		parameter.radiusXY = Math.max(1, (int) gd.getNextNumber());
		parameter.radiusZ = Math.max(1, (int) gd.getNextNumber());
		parameter.beadsCount = Math.max(1, (int) gd.getNextNumber());
		parameter.avgMethod = gd.getNextChoice();
		parameter.psfShellFraction = Math.max(0.01d, Math.min(0.49d, gd.getNextNumber()));
		parameter.psfMinSnr = Math.max(0.0d, gd.getNextNumber());
		parameter.psfMinSbr = Math.max(0.0d, gd.getNextNumber());
		parameter.psfMaxCenterOffset = Math.max(0.0d, gd.getNextNumber());
		parameter.psfSaturationLevel = Math.max(0.0d, gd.getNextNumber());
		parameter.psfRejectNeighbors = gd.getNextBoolean();
		parameter.xyPixelSize = gd.getNextNumber();
		parameter.zStepSize = gd.getNextNumber();
		parameter.opmAngle = gd.getNextNumber();
		parameter.saveDir = gd.getNextString();
		parameter.saveToSame = gd.getNextBoolean();
		parameter.saveSeparate = gd.getNextBoolean();
		parameter.fileExistStr = gd.getNextChoice();
		return true;
	}

	private boolean sameFile(File a, File b) {
		try {
			return a.getCanonicalFile().equals(b.getCanonicalFile());
		} catch (IOException e) {
			return a.getAbsoluteFile().equals(b.getAbsoluteFile());
		}
	}
}
