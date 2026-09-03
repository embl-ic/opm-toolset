package de.embl.iclm;

import fiji.util.gui.GenericDialogPlus;
import java.util.Map;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.plugin.PlugIn;

public class Batch implements PlugIn {
	private Parameter parameter = null;

	private String[] keywords = new String[0];
	private File inputFolder = null;
	private File saveFolder = null;
	private boolean overwrite = false;
	//private String logPath = "";
	private String[] inputFileList = new String[0];
	/** Complete raw input list for acquisition-level Zarr, independent of TIFF skip rules. */
	private List<File> zarrInputFiles = new ArrayList<File>();
	
	private Log log;
	
	/** Multi-channel selection, shared with Channel Operation and Live Processing. */
	private final ChannelOperationSettings channels = new ChannelOperationSettings();

	@Override
	public void run(String arg) {
		parameter = new Parameter("batch");
		channels.load();
		if ( !parameter.deskew_batch() ) return;
		if ( !askChannelSettings() ) return;
		
		if ( !prepareFiles() ) return;
		
		parameter.parseDeskewParameterBatch();
		parameter.parseAlignParameter();
		parameter.parseProjectionParameter();
		configureFileOutput(parameter);
		parameter.storeParam();
		
		log = new Log(parameter);
		log.add(parameter);
		log.add("OPM batch processing start:");
    	
    	processFiles();
		
    	log.add("OPM batch processing finish.");
    	log.close();
	}
	
	/**
	 * 
	 * @return
	 */
	public boolean prepareFiles () {
		// check input folder path
		if (null == parameter) return false;
		if ("" == parameter.inputDir) return false;
		inputFolder = new File(parameter.inputDir).getAbsoluteFile();
		if (!inputFolder.exists() || !inputFolder.isDirectory()) return false;
		parameter.inputDir = inputFolder.toPath().normalize().toString();
		// get file list match request from input folder
		if ("" != parameter.keywords) {
    		keywords = parameter.keywords.split(",");
    		for (int i=0; i<keywords.length; i++) {
    			keywords[i] = keywords[i].replaceAll("\\s+",""); // remove spaces
        	}
    	}
		// check save folder path
		try {
			saveFolder = resolveSaveFolder(parameter, inputFolder);
			Files.createDirectories(saveFolder.toPath());
		} catch (IOException invalidOutput) {
			IJ.error("Deskew Batch", "Could not create the result folder:\n" + invalidOutput.getMessage());
			return false;
		} catch (IllegalArgumentException invalidOutput) {
			IJ.error("Deskew Batch", invalidOutput.getMessage());
			return false;
		}
		/* The log belongs beside the results it describes. resolveSaveFolder does not write
		 * back to parameter.saveDir, so name the folder explicitly rather than leaving the
		 * log to fall back to somewhere outside this run. */
		parameter.logPath = Log.prepareLogPath(
				saveFolder.getAbsolutePath(), "OPM_" + parameter.obj + ".log" );
		// get input file list, check potential processed files from save folder
		overwrite = parameter.fileExistStr.equals("overwrite");
		zarrInputFiles.clear();
		if (parameter.saveDeskewZarr) {
			zarrInputFiles = BatchProcessingUtils.listTiffs(
					inputFolder, parameter.keywords, parameter.recursive);
			zarrInputFiles = BatchProcessingUtils.excludeTree(zarrInputFiles, saveFolder);
		}
		inputFileList = getInputFileList (
			inputFolder, Parameter.extensions, keywords, saveFolder, parameter.recursive, overwrite );
		return inputFileList.length != 0 || !zarrInputFiles.isEmpty();
	}

	/** Every Batch branch writes files; only the projection movie remains displayed. */
	static void configureFileOutput(Parameter parameter) {
		if (parameter != null) parameter.displayResult = false;
	}

	/** Resolve one canonical native-separator output path without string concatenation. */
	static File resolveSaveFolder(Parameter parameter, File inputFolder) throws IOException {
		if (parameter == null || inputFolder == null)
			throw new IllegalArgumentException("A valid input folder is required.");
		File requested;
		if (parameter.saveToSame) {
			requested = new File(inputFolder, "result");
		} else {
			String path = parameter.saveDir == null ? "" : parameter.saveDir.trim();
			if (path.isEmpty())
				throw new IllegalArgumentException("Choose a result folder or enable save to the data folder.");
			requested = new File(path);
		}
		File normalized = requested.toPath().toAbsolutePath().normalize().toFile();
		parameter.saveDir = normalized.getPath();
		return normalized;
	}
	
	/**			Ask how the acquisition channels of one timepoint should be combined
	 * <p>		Offered as a second dialog rather than crowded into the deskew dialog, and
	 * 			skipped entirely by anyone who leaves the combine box unticked.
	 *
	 * @return					: false if the user cancelled
	 */
	private boolean askChannelSettings () {
		GenericDialogPlus gd = new GenericDialogPlus("Deskew Batch - acquisition channels");
		gd.addMessage("Each _ChannelNNNN file supplies a left and a right camera half.\n"
				+ "Leave the box unticked to deskew every file on its own, as before.");
		channels.addToDialog ( gd );
		gd.showDialog();
		if (gd.wasCanceled()) return false;
		channels.readFrom ( gd );
		if (channels.combineAcquisitionChannels && channels.selectedCount() == 0) {
			IJ.error("Deskew Batch", "Select at least one output channel source.");
			return false;
		}
		channels.store();
		return true;
	}


	/**			Deskew each timepoint's acquisition channels together into one result
	 * <p>		Files are grouped by their _ChannelNNNN token, every camera half is deskewed,
	 * 			the selected half is flipped and aligned onto the other, and the sources the
	 * 			user picked are written as the channels of a single volume.
	 */
	private void processChannelGroups () {
		List<File> files = new ArrayList<File>();
		for (String path : inputFileList) files.add ( new File(path) );
		Map<String, List<File>> groups = channels.group ( files );
		IJ.log ( "OPM Deskew Batch: " + files.size() + " file(s) in " + groups.size() + " acquisition group(s)." );

		int done = 0, failed = 0, index = 0;
		for (Map.Entry<String, List<File>> entry : groups.entrySet()) {
			List<File> group = entry.getValue();
			IJ.showProgress ( index++, groups.size() );
			String outputName = BatchProcessingUtils.channelGroupOutputName ( group ) + "-deskewed";
			String saveDir = parameter.saveDir;
			if ( parameter.saveSeparate ) saveDir += File.separator + "deskew";
			String savePath = VolumeIO.tiffPath ( saveDir + File.separator + outputName );
			if ( new File(savePath).exists() && !overwrite ) {
				IJ.log ( "OPM Deskew Batch skip existing result: " + savePath );
				done++;
				continue;
			}
			ImagePlus combined = null;
			try {
				combined = MultiChannelDeskew.deskewGroup ( group, parameter, channels, outputName );
				if (combined == null) { failed++; continue; }
				// hand the combined volume to the shared result path, so saving, projections
				// and time-lapse behave exactly as they do for a single-file deskew
				parameter.impInput = null;
				Deskew.prepareResults ( new ImagePlus[] { combined }, parameter );
				done++;
			} catch (Throwable t) {
				failed++;
				IJ.log ( "OPM Deskew Batch failed for " + outputName + ": " + t );
			} finally {
				if (combined != null) { combined.changes = false; combined.close(); }
				Utils.collectGarbage();
			}
		}
		IJ.showProgress ( 1.0 );
		IJ.log ( "OPM Deskew Batch finished: " + done + " group(s), " + failed + " failure(s)." );
	}


	/**
	 *
	 */
	public void processFiles () {
		//Log log = Log.getInstance();
		long start = System.currentTimeMillis();
		// in case only left or right side requested
		//boolean doHalf = parameter.channelStr.equals("left only") || parameter.channelStr.equals("right only");
		
		// close all previous open projections image window (including time lapse)
		int[] id_list = WindowManager.getIDList();
		if (null != id_list && 0 != id_list.length) {
			for (int id : WindowManager.getIDList() ) {
				ImagePlus imp = WindowManager.getImage(id);
				if ( imp.getTitle().endsWith("projection") )
					imp.close();
			}
			Utils.collectGarbage();
		}
		// parse how to make projection images from parameters
		// prepare projection axis string list
		/*
		ArrayList<String> axes = new ArrayList<String>();
		if (parameter.projX) axes.add("X");
		if (parameter.projY) axes.add("Y");
		if (parameter.projZ) axes.add("Z");
		// prepare projection type string list
		ArrayList<String> types = new ArrayList<String>();
		if (parameter.maxProj)	types.add("max");
		if (parameter.avgProj)	types.add("avg");
		if (parameter.minProj)	types.add("min");
		if (parameter.sumProj)	types.add("sum");
		if (parameter.medProj)	types.add("med");
		if (parameter.stdProj)	types.add("std");
		boolean doProjection = (0 != axes.size() && 0 != types.size());
		*/
		//parameter.parseProjectionParameter();

		// Both formats share one canonical deskew and use independent disk-writer threads.
		if (parameter.saveDeskewImage && parameter.saveDeskewZarr
				&& channels.combineAcquisitionChannels) {
			processDualOutputs();
			return;
		}
		
		/* OME-Zarr is one acquisition-level dataset, independent of the TIFF
		 * display/channel mode. When both outputs are requested, write TIFF first:
		 * Zarr conversion can take a long time (and may be resumed after interruption),
		 * whereas the old Zarr-first order prevented the TIFF phase from ever starting
		 * when Fiji was interrupted or ran out of memory during Zarr conversion. */
		boolean zarrAfterTiff = parameter.saveDeskewZarr && parameter.saveDeskewImage;
		if (parameter.saveDeskewZarr && !zarrAfterTiff) {
			logPhase ( "OPM Deskew Batch starting OME-Zarr phase." );
			writeOmeZarr();
		}

		// with acquisition channels combined, one timepoint's files are processed together
		if ( channels.combineAcquisitionChannels ) {
			if (parameter.saveDeskewImage)
				logPhase ( "OPM Deskew Batch starting TIFF phase." );
			processChannelGroups();
			if (zarrAfterTiff) {
				logPhase ( "OPM Deskew Batch TIFF phase finished; starting OME-Zarr phase." );
				writeOmeZarr();
			}
			return;
		}
		if (parameter.saveDeskewImage)
			logPhase ( "OPM Deskew Batch starting TIFF phase." );
		// loop through input file list, process each file
		for (String path : inputFileList) {
			System.out.printf("\n\tprocessing file:\n\t%s\n", path);
			
			long start_file = System.currentTimeMillis();
			
			Deskew.processFile ( path, parameter) ;
			
			/*
			ImagePlus imp = VolumeIO.open(path);
		
			if (parameter.channelStr.equals("align with SIFT matrix") && parameter.alignmFile != "" ){
				parameter.alignMatrix = IO.loadMatrixFromFile(parameter.alignmFile);
			}
			
			if (doHalf)
				imp = (Partition.separateImageLeftRight(imp, parameter.channelStr)) [0];
			
			if (parameter.doDeskew) {
				ImagePlus imp_deskew = Deskew.deskew_image ( imp, parameter );
						//imp, parameter.xyPixelSize, parameter.zStepSize, parameter.opmAngle,
						//false, false, true, true, parameter.numPartition);
				String saveDir = saveFolder.getAbsolutePath();
				if (parameter.saveSeparate)	saveDir += File.separator + "deskew";
				try {
					Files.createDirectories(Paths.get(saveDir));
				} catch (IOException e) {
					System.out.println(e.getMessage());
					//log.add(e.getMessage());
					continue;
				}
				String savePath = VolumeIO.tiffPath ( saveDir + File.separator + imp_deskew.getTitle() );
				if (!new File(savePath).exists() || overwrite)
					VolumeIO.saveTiff(imp_deskew, savePath);
				
				//log.add("deskewed: " + imp.getTitle()); 
				imp.setImage(imp_deskew);
				imp.setTitle(imp_deskew.getTitle());
			}
			
			// get channel image
			ImagePlus[] imp_channel = new ImagePlus[]{imp};
			if (!doHalf) {
				imp_channel = Partition.separateImageLeftRight (
						imp, parameter.channelStr );
			}
			//log.add("channel operation: " + parameter.channelStr);
			
			// create projection image(s) if requested
			if (doProjection) {
				// process for each channel image
				for (ImagePlus imp_c : imp_channel) {
					String name = Utils.getName(imp_c);
					// process for each projection axis 
		    		for (String axis : axes) {
		    			// process for each projection type
		    			for (String type : types) {
		    				ImagePlus imp_project = Projection.projection (imp_c, axis, type, true);
		    				imp_project.setTitle(name + "-" + type + axis + "projection");
		    				// create time lapse if requested
		    				if (parameter.makeTimeLapse) {
		    					String name_timeLapse = type + axis + "projection-timeLapse";
		    					int idx = name.lastIndexOf("-");
		    					if (-1 != idx) name_timeLapse = name.substring(idx, name.length()) + "-" + type + axis + "projection";
		    					ImagePlus imp_timeLapse = WindowManager.getImage(name_timeLapse);
		    					Partition.combineTimelapse ( imp_timeLapse, imp_project, name_timeLapse );		
		    				}
		    				// save projection images to disk
		    				String saveDir = saveFolder.getAbsolutePath();
		    				if (parameter.saveSeparate)	saveDir += File.separator + type + axis;
		    				try {
		    					Files.createDirectories(Paths.get(saveDir));
		    				} catch (IOException e) {
		    					System.out.println(e.getMessage());
		    					//log.add(e.getMessage());
		    					continue;
		    				}
		    				
		    				String savePath = VolumeIO.tiffPath ( saveDir + File.separator + imp_project.getTitle() );
		    				if (!new File(savePath).exists() ||  overwrite)
		    					VolumeIO.saveTiff(imp_project, savePath);
		    				//log.add("projection image created: " + imp_project.getTitle()); 
		    			}	// projection type loop
		    		}		// projection axis loop
				}			// channel image loop	
			}
			//if ( (double)IJ.currentMemory()/(double)IJ.maxMemory() > 0.9d )
			*/
			//System.gc();
			float duration_file = System.currentTimeMillis() - start_file;
			System.out.printf("\n\tprocessing file finished after %.3f seconds.\n", duration_file / 1000);
			//log.add("\n\tprocessing file finished after %.3f seconds.\n", duration_file / 1000);
		} 					// file loop
		if (zarrAfterTiff) {
			logPhase ( "OPM Deskew Batch TIFF phase finished; starting OME-Zarr phase." );
			writeOmeZarr();
		}
		float duration = System.currentTimeMillis() - start;
		System.out.printf("\n\tBatch processing files finished after %.3f seconds.\n", duration / 1000);
		//log.add("\n\batch processing files finished after %.3f seconds.\n", duration / 1000);
		System.gc();
		return;
	}

	/**
	 * One deskew pass per timepoint, followed by two independent writer tasks.
	 * TIFF sees the selected runtime-aligned composite; Zarr sees the canonical halves.
	 */
	private void processDualOutputs() {
		File root = OpmZarrConverter.defaultRoot(saveFolder, inputFolder);
		OpmZarrConverter.Conversion conversion = null;
		ExecutorService tiffWriter = Executors.newSingleThreadExecutor(writerThread("OPM-TIFF-writer"));
		ExecutorService zarrWriter = Executors.newSingleThreadExecutor(writerThread("OPM-Zarr-writer"));
		int tiffDone = 0, tiffFailed = 0, zarrDone = 0;
		boolean zarrHealthy = true;
		try {
			OpmZarrConverter.Options options = OpmZarrConverter.optionsFromParameter(
					parameter, channels, inputFolder);
			conversion = OpmZarrConverter.openConversion(inputFolder, zarrInputFiles, root, options);
			final OpmZarrSession dualSession = conversion.session;
			logPhase("OPM Deskew Batch dual-output pipeline: " + conversion.timePoints.size()
					+ " timepoint(s), one deskew pass, parallel TIFF/Zarr writers.");

			int index = 0;
			for (OpmTimepointProcessor.TimePoint timePoint : conversion.timePoints) {
				index++;
				IJ.showProgress(index - 1, conversion.timePoints.size());
				String outputName = BatchProcessingUtils.channelGroupOutputName(timePoint.files)
						+ "-deskewed";
				boolean tiffNeeded = BatchTiffOutput.needsWrite(parameter, outputName);
				boolean alreadyCommitted = conversion.session.isCommitted(timePoint.label);
				boolean zarrNeeded = zarrHealthy && !alreadyCommitted;
				if (!tiffNeeded && !zarrNeeded) {
					IJ.log("OPM dual output: all requested outputs already exist, skipped before deskew: "
							+ timePoint.label + " (" + index + "/" + conversion.timePoints.size() + ")");
					continue;
				}
				IJ.log("OPM dual output: processing " + timePoint.label + " (" + index + "/"
						+ conversion.timePoints.size() + "; TIFF=" + tiffNeeded + ", Zarr=" + zarrNeeded + ")");
				OpmTimepointProcessor.Result canonical = null;
				MultiChannelDeskew.PreparedComposite composite = null;
				BatchTiffOutput tiff = null;
				OpmZarrSession.PreparedTimePoint zarr = null;
				try {
					canonical = OpmTimepointProcessor.process(
							timePoint, conversion.deskewMatrix, parameter.tryGPU);

					Throwable tiffPreparationFailure = null;
					if (tiffNeeded) try {
						composite = MultiChannelDeskew.fromCanonical(
								canonical, parameter, channels, outputName);
						if (composite == null) throw new IOException("No TIFF channels were selected.");
						tiff = BatchTiffOutput.prepare(composite.image, parameter);
					} catch (Throwable failure) {
						tiffPreparationFailure = failure;
					}

					Throwable zarrPreparationFailure = null;
					if (zarrNeeded) try {
						zarr = OpmZarrSession.prepare(canonical, parameter.tryGPU, options.writeProjections);
					} catch (Throwable failure) {
						zarrPreparationFailure = failure;
					}

					final OpmTimepointProcessor.TimePoint sourceTimePoint = timePoint;
					final BatchTiffOutput tiffTask = tiff;
					Future<Void> tiffFuture = tiffTask == null ? null : tiffWriter.submit(new Callable<Void>() {
						@Override public Void call() throws Exception {
							IJ.log("OPM dual output: TIFF writer started " + sourceTimePoint.label
									+ " [" + Thread.currentThread().getName() + "]");
							tiffTask.write(parameter);
							IJ.log("OPM dual output: TIFF writer finished " + sourceTimePoint.label
									+ " [" + Thread.currentThread().getName() + "]");
							return null;
						}
					});
					final OpmZarrSession.PreparedTimePoint zarrTask = zarr;
					Future<Boolean> zarrFuture = zarrTask == null ? null : zarrWriter.submit(new Callable<Boolean>() {
						@Override public Boolean call() throws Exception {
							IJ.log("OPM dual output: Zarr writer started " + sourceTimePoint.label
									+ " [" + Thread.currentThread().getName() + "]");
							boolean appended = dualSession.appendPrepared(sourceTimePoint, zarrTask);
							IJ.log("OPM dual output: Zarr writer finished " + sourceTimePoint.label
									+ " [" + Thread.currentThread().getName() + "]");
							return Boolean.valueOf(appended);
						}
					});

					Throwable tiffFailure = tiffPreparationFailure != null
							? tiffPreparationFailure : await(tiffFuture);
					Throwable zarrFailure = zarrPreparationFailure != null
							? zarrPreparationFailure : await(zarrFuture);
					if (tiffNeeded) {
						if (tiffFailure == null) tiffDone++;
						else {
							tiffFailed++;
							logPhase("OPM dual output TIFF failed for " + timePoint.label + ": " + tiffFailure);
						}
					}
					if (alreadyCommitted) {
						IJ.log("OPM dual output Zarr already committed, skipped: " + timePoint.label);
					} else if (zarrFailure == null && zarrFuture != null) {
						zarrDone++;
					} else if (zarrHealthy) {
						zarrHealthy = false;
						logPhase("OPM dual output Zarr disabled after failure at "
								+ timePoint.label + ": " + zarrFailure);
					}
				} catch (Throwable processingFailure) {
					if (tiffNeeded) tiffFailed++;
					if (zarrNeeded) zarrHealthy = false;
					logPhase("OPM dual output processing failed for " + timePoint.label
							+ ": " + processingFailure);
				} finally {
					if (tiff != null) tiff.close();
					if (composite != null) composite.close();
					if (zarr != null) zarr.close();
					if (canonical != null) canonical.close();
					Utils.collectGarbage();
				}
			}
			if (zarrHealthy) {
				conversion.session.markComplete();
				logPhase("OPM Deskew Batch completed canonical OME-Zarr: " + root.getAbsolutePath());
			}
			IJ.showProgress(1.0);
			logPhase("OPM Deskew Batch dual-output finished: TIFF " + tiffDone + " written, "
					+ tiffFailed + " failed; Zarr " + zarrDone + " newly committed.");
		} catch (Throwable failure) {
			logPhase("OPM Deskew Batch dual-output setup failed: " + failure);
		} finally {
			shutdown(tiffWriter);
			shutdown(zarrWriter);
			if (conversion != null) conversion.close();
		}
	}

	private static Throwable await(Future<?> future) {
		if (future == null) return null;
		try {
			future.get();
			return null;
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return interrupted;
		} catch (ExecutionException failed) {
			return failed.getCause() == null ? failed : failed.getCause();
		}
	}

	private static ThreadFactory writerThread(final String name) {
		return new ThreadFactory() {
			@Override public Thread newThread(Runnable work) {
				Thread thread = new Thread(work, name);
				thread.setDaemon(true);
				return thread;
			}
		};
	}

	private static void shutdown(ExecutorService executor) {
		executor.shutdown();
		try {
			if (!executor.awaitTermination(30, TimeUnit.SECONDS)) executor.shutdownNow();
		} catch (InterruptedException interrupted) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	/** Report phase transitions in both the Fiji Log window and the persistent batch log. */
	private void logPhase(String message) {
		IJ.log(message);
		if (log != null) log.add(message);
	}

	/** Write the same canonical, unaligned L/R dataset used by Live and the standalone converter. */
	private void writeOmeZarr() {
		File root = OpmZarrConverter.defaultRoot(saveFolder, inputFolder);
		try {
			OpmZarrConverter.Options options = OpmZarrConverter.optionsFromParameter(
					parameter, channels, inputFolder);
			OpmZarrConverter.convertFiles(inputFolder, zarrInputFiles, root, options);
			String message = "OPM Deskew Batch wrote canonical OME-Zarr: " + root.getAbsolutePath();
			IJ.log(message);
			if (log != null) log.add(message);
		} catch (Throwable failure) {
			String message = "OPM Deskew Batch OME-Zarr failed: " + failure;
			IJ.log(message);
			if (log != null) log.add(message);
		}
	}


	/**
	 * 
	 * @param inputFolder
	 * @param keywords
	 * @param saveFolder
	 * @param overwrite
	 * <p>
	 * @return
	 */
	public String[] getInputFileList (
			File inputFolder,
			String[] extensions,
			String[] keywords,
			File saveFolder,
			boolean recursive,
			boolean overwrite
			) {
    	if (null == inputFolder) return new String[0];
    	
    	// get file list match request from input folder
    	String[] inputFileArray = getFileList (inputFolder, extensions, keywords, recursive);
    	
    	// check if overwrite
    	if (overwrite) return inputFileArray;
    	
    	// check potential processed files from save folder
    	String[] outputFileArray = getFileList (saveFolder, extensions, keywords, true);
    	if (0 == outputFileArray.length) return inputFileArray;
    	List<String> outputFileList = new LinkedList<String>(Arrays.asList(outputFileArray));
    	List<String> inputFileList  = new LinkedList<String>(Arrays.asList(inputFileArray));
    	
    	// remove input files from output file list (when input and output folders are the same)
    	outputFileList.removeAll(inputFileList); 
    	//System.out.println("outputFileList(0)" + outputFileList.get(0));
    	// cross check input and output file list, for a list of processed file
    	List<String> processedFileList = new ArrayList<String>();
    	Iterator<String> iter = inputFileList.iterator();
    	while (iter.hasNext()) {
    		String filePath = iter.next();
    		/*
    		int slashIdx = fileName.lastIndexOf(File.separator) + 1;
    		int dotIdx = fileName.lastIndexOf(".");
    		if (-1 == dotIdx) dotIdx = fileName.length();
    		fileName = fileName.substring(slashIdx, dotIdx);
    		*/
    		String fileName = FilenameUtils.getBaseName(filePath);
    		//System.out.println("inputfile: " + fileName);
    		
    		for (String outputFile : outputFileList) {
    			if (outputFile.contains(fileName)) { // match found
    				
    				//System.out.println("outputfile: " + outputFile);
    				processedFileList.add(filePath);
    				//iter.remove();
    			}
    		}
    	}
    	if (null != processedFileList)
    		inputFileList.removeAll(processedFileList);
    	inputFileArray = inputFileList.toArray(new String[inputFileList.size()]);
		return inputFileArray;
	}
	
	
	/**		get files full path, which match criterion inside a parent directory
	 * 
	 * @param parentDir
	 * @param extensions
	 * @param keywords
	 * @param recursive
	 * <p>
	 * @return
	 */
	public static String[] getFileList (
			File parentDir, 
			String[] extensions,
			String[] keywords,
			boolean recursive
			) {
		if (null == parentDir) return new String[0];
		Collection<File> files = FileUtils.listFiles(parentDir, extensions, recursive);
		List<String> fileList = new ArrayList<String>();
		for (File file : files) {
			String name = file.getName();
			for (String keyword : keywords) {
				if (name.contains(keyword)) {
					fileList.add(file.getAbsolutePath());
					break;
				}	
			}
		}
		Collections.sort(fileList, new Comparator<String>() {
		    @Override
		    public int compare(String s1, String s2) {
		    	s1 = s1.replaceAll("[^0-9]", "");
		    	if ( s1.length()>18 ) s1 = s1.substring(s1.length()-18, s1.length());
		    	s2 = s2.replaceAll("[^0-9]", "");
		    	if ( s2.length()>18 ) s2 = s2.substring(s2.length()-18, s2.length());
		    	Long d1 = Long.valueOf ( s1 );
		    	Long d2 = Long.valueOf ( s2 );
		        return d1.compareTo(d2);
		    }
		});
		return fileList.toArray(new String[fileList.size()]);
	}
    
	
}
