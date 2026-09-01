package de.embl.iclm;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.plugin.PlugIn;
import fiji.util.gui.GenericDialogPlus;

import java.io.File;

public class Batch2 implements PlugIn {
	private Parameter parameter;

	private boolean saveDeskewTiff = true;
	private boolean saveMipMovies = true;
	private String[] inputFileList = new String[0];
	private java.util.List<File> zarrInputFiles = new java.util.ArrayList<File>();
	private File inputFolder;
	private File saveFolder;
	private String[] keywords = new String[0];
	private boolean overwrite;

	@Override
	public void run(String arg) {
		parameter = new Parameter("batch2");
		parameter.tryGPU = true;
		parameter.autoPartition = true;
		parameter.displayResult = false;
		parameter.projX = true;
		parameter.projY = true;
		parameter.projZ = true;
		parameter.maxProj = true;

		if (!showDialog()) return;
		if (!prepareFiles()) return;

		parameter.parseDeskewParameterBatch();
		parameter.parseAlignParameter();
		parameter.parseProjectionParameter();
		parameter.storeParam();

		closeProjectionWindows();

		Log log = new Log(parameter);
		log.add(parameter);
		log.add("OPM Deskew Batch2 processing start:");
		long start = System.currentTimeMillis();
		if (parameter.saveDeskewZarr) {
			try {
				ChannelOperationSettings channelSettings = new ChannelOperationSettings();
				channelSettings.load();
				OpmZarrConverter.Options zarrOptions = OpmZarrConverter.optionsFromParameter(
						parameter, channelSettings, inputFolder);
				OpmZarrConverter.convertFiles(inputFolder, zarrInputFiles,
						OpmZarrConverter.defaultRoot(saveFolder, inputFolder), zarrOptions);
			} catch (Throwable failure) {
				IJ.log("Deskew Batch2 canonical OME-Zarr failed: " + failure);
			}
		}

		FastClijDeskew.Options options = FastClijDeskew.optionsFromParameter(parameter);
		options.saveDeskewTiff = saveDeskewTiff;
		options.makeMipMovies = parameter.makeTimeLapse;
		options.displayMipMovies = parameter.displayTimeLapse;

		FastClijDeskew.MipMovieSink movies = new FastClijDeskew.MipMovieSink();

		for (int i = 0; i < inputFileList.length; i++) {
			String path = inputFileList[i];
			File file = new File(path);
			IJ.log(String.format("Deskew Batch2 processing %d/%d: %s", i + 1, inputFileList.length, path));
			try {
				if (FastClijDeskew.canUseFastPath(parameter)) {
					try {
						FastClijDeskew.Result result = FastClijDeskew.processFile(file, parameter, options, movies, i);
						String timing = "Deskew Batch2 timing: " + FastClijDeskew.formatTiming(result);
						IJ.log(timing);
						log.add(timing);
					} catch (Throwable fastError) {
						String message = "Deskew Batch2 fast path failed; falling back to original processor: " + fastError.getMessage();
						IJ.log(message);
						log.add(message);
						Deskew.processFile(path, parameter);
					}
				} else {
					IJ.log("Deskew Batch2 fast path not available for this channel/projection mode; using original processor.");
					Deskew.processFile(path, parameter);
				}
			} catch (Throwable t) {
				String message = "Deskew Batch2 failed for " + path + ": " + t.getMessage();
				IJ.log(message);
				log.add(message);
				t.printStackTrace();
			}
			IJ.showProgress(i + 1, inputFileList.length);
			System.gc();
		}

		if (parameter.makeTimeLapse && saveMipMovies) {
			File movieDir = parameter.saveSeparate ? new File(saveFolder, "MIP_movies") : saveFolder;
			long movieStart = System.currentTimeMillis();
			movies.save(movieDir);
			String timing = String.format("Deskew Batch2 saved MIP movies in %.3f seconds.",
					(System.currentTimeMillis() - movieStart) / 1000.0);
			IJ.log(timing);
			log.add(timing);
		}

		double elapsed = (System.currentTimeMillis() - start) / 1000.0;
		String summary = String.format("OPM Deskew Batch2 processing finish after %.3f seconds.", elapsed);
		IJ.log(summary);
		log.add(summary);
		log.close();
	}

	private boolean showDialog() {
		GenericDialogPlus gd = new GenericDialogPlus("Deskew Batch2 Processing");
		int length = 35;
		gd.addDirectoryField("input folder...", parameter.inputDir, length);
		gd.addStringField("file name contains(separate multiple by \",\")", parameter.keywords, length);
		gd.addCheckbox("recursive", parameter.recursive);
		gd.addCheckbox("deskew image", parameter.doDeskew);
		gd.addNumericField("XY pixel size", parameter.xyPixelSize, 1, 5, "nm");
		gd.addNumericField("Z step size", parameter.zStepSize, 1, 5, "nm");
		gd.addNumericField("OPM angle", parameter.opmAngle, 1, 5, "degree");
		gd.addFileField("", parameter.deskewmFile, length);
		gd.addChoice("channel option", new String[] {
				"whole image", "fold by midline", "align with SIFT",
				"only left", "only right", "left & right separately" }, parameter.channelStr);
		gd.addFileField("align matrix", parameter.alignmFile, length);
		gd.addMessage("create projection image(s):");
		gd.addCheckboxGroup(1, 3, new String[] { "along_X      ", "along_Y      ", "along_Z      " },
				new boolean[] { parameter.projX, parameter.projY, parameter.projZ });
		gd.addCheckboxGroup(1, 2, new String[] { "maximum", "mean" },
				new boolean[] { parameter.maxProj, parameter.avgProj });
		gd.addCheckbox("combine as time lapse", parameter.makeTimeLapse);
		gd.addDirectoryField("save to...", parameter.saveDir, length);
		gd.addCheckbox("save result to the same (data) folder", parameter.saveToSame);
		gd.addCheckbox("save deskew image as TIFF stack", parameter.saveDeskewImage);
		gd.addCheckbox("save acquisition as OME-Zarr", parameter.saveDeskewZarr);
		gd.addCheckbox("save MIP movies", saveMipMovies);
		gd.addCheckbox("separate results to sub-folders", parameter.saveSeparate);
		gd.addChoice("if result exist", new String[] { "skip", "overwrite" }, parameter.fileExistStr);
		gd.showDialog();
		if (gd.wasCanceled()) return false;

		parameter.inputDir = gd.getNextString();
		parameter.keywords = gd.getNextString();
		parameter.recursive = gd.getNextBoolean();
		parameter.doDeskew = gd.getNextBoolean();
		parameter.xyPixelSize = gd.getNextNumber();
		parameter.zStepSize = gd.getNextNumber();
		parameter.opmAngle = gd.getNextNumber();
		parameter.deskewmFile = gd.getNextString();
		parameter.channelStr = gd.getNextChoice();
		parameter.alignmFile = gd.getNextString();
		parameter.projX = gd.getNextBoolean();
		parameter.projY = gd.getNextBoolean();
		parameter.projZ = gd.getNextBoolean();
		parameter.maxProj = gd.getNextBoolean();
		parameter.avgProj = gd.getNextBoolean();
		parameter.makeTimeLapse = gd.getNextBoolean();
		parameter.saveDir = gd.getNextString();
		parameter.saveToSame = gd.getNextBoolean();
		parameter.saveDeskewImage = gd.getNextBoolean();
		saveDeskewTiff = parameter.saveDeskewImage;
		parameter.saveDeskewZarr = gd.getNextBoolean();
		saveMipMovies = gd.getNextBoolean();
		parameter.saveSeparate = gd.getNextBoolean();
		parameter.fileExistStr = gd.getNextChoice();
		parameter.displayResult = false;
		if (parameter.saveToSame) {
			parameter.saveDir = "";
			parameter.recursive = false;
		}
		return true;
	}

	private boolean prepareFiles() {
		if (parameter.inputDir == null || parameter.inputDir.equals("")) return false;
		inputFolder = new File(parameter.inputDir);
		if (!inputFolder.exists() || !inputFolder.isDirectory()) return false;
		if (parameter.keywords != null && !parameter.keywords.equals("")) {
			keywords = parameter.keywords.split(",");
			for (int i = 0; i < keywords.length; i++)
				keywords[i] = keywords[i].replaceAll("\\s+", "");
		}

		saveFolder = new File(parameter.saveDir);
		if (parameter.saveToSame || parameter.saveDir == null || parameter.saveDir.equals("") || saveFolder == null) {
			parameter.saveDir = parameter.inputDir;
			if (!parameter.saveDir.endsWith(File.separator)) parameter.saveDir += File.separator;
			parameter.saveDir += "result";
			saveFolder = new File(parameter.saveDir);
		}
		if (!saveFolder.exists()) saveFolder.mkdirs();

		overwrite = parameter.fileExistStr.equals("overwrite");
		zarrInputFiles.clear();
		if (parameter.saveDeskewZarr) {
			zarrInputFiles = BatchProcessingUtils.listTiffs(
					inputFolder, parameter.keywords, parameter.recursive);
			zarrInputFiles = BatchProcessingUtils.excludeTree(zarrInputFiles, saveFolder);
		}
		inputFileList = new Batch().getInputFileList(inputFolder, Parameter.extensions, keywords,
				saveFolder, parameter.recursive, overwrite);
		return inputFileList.length != 0 || !zarrInputFiles.isEmpty();
	}

	private void closeProjectionWindows() {
		int[] ids = WindowManager.getIDList();
		if (ids == null) return;
		for (int id : ids) {
			ImagePlus imp = WindowManager.getImage(id);
			if (imp != null && imp.getTitle().endsWith("projection")) imp.close();
		}
	}
}
