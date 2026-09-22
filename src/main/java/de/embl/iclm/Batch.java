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
	/** Whether each sub-folder's results go to the same sub-folder of {@link #saveFolder}. */
	private boolean mirrorTree = false;
	private boolean overwrite = false;
	private String[] inputFileList = new String[0];
	/** Complete raw input list for acquisition-level Zarr, independent of TIFF skip rules. */
	private List<File> zarrInputFiles = new ArrayList<File>();
	
	private Log log;
	
	/** Multi-channel selection, shared with Channel Operation and Live Processing. */
	private final ChannelOperationSettings channels = new ChannelOperationSettings();

	@Override
	public void run(String arg) {
		Debug.commandStarted ( "Batch Processing > Deskew" );
		parameter = new Parameter("batch");
		channels.load();
		if ( !parameter.deskew_batch() ) return;
		/* The dialog wrote the channel selection back to the shared preferences, so re-read
		 * it here rather than keeping the copy made before it was shown. */
		channels.load();
		if ( channels.combineAcquisitionChannels ) {
			String problem = channels.selectionProblem();
			if (problem != null) { IJ.error("Deskew Batch", problem); return; }
		}
		
		if ( !prepareFiles() ) return;
		
		parameter.parseDeskewParameterBatch();
		parameter.parseProjectionParameter();
		/* The format dropdown is what decides saveDeskewZarr and, for a Zarr-only run, clears
		 * doProjection: an OME-Zarr dataset already carries all six projections, so a tree of
		 * projection TIFFs beside it is the same pixels written twice. It has to run before
		 * parseAlignParameter, which keeps the alignment matrix when the output is canonical
		 * OME-Zarr and cannot know that before the format has been applied. */
		parameter.applyOutputFormat();
		parameter.parseAlignParameter();
		configureFileOutput(parameter);
		parameter.storeParam();
		
		log = new Log(parameter);
		log.add(parameter);
		log.add("OPM batch processing start:");

		/* The heavy work runs on a daemon thread and this plugin thread only waits for it.
		 * ImageJ's Executer thread is not a daemon, and Fiji quits by disposing its window
		 * and waiting for the remaining non-daemon threads, so a batch that ran here
		 * directly kept the process alive after Fiji had visibly closed. */
		boolean completed = Shutdown.runCancellable ( "OPM Deskew Batch", new Runnable() {
			@Override
			public void run () { processFiles(); }
		} );

		log.add ( completed ? "OPM batch processing finish."
				: "OPM batch processing stopped early: " + Shutdown.reason() + "." );
    	log.close();
	}
	
	/**			Resolve the input folder, the result folder and the list of files to process
	 * <br>		Every way of finding nothing to do is reported to the user: a batch that quietly
	 * <br>		closes its dialog and does nothing is indistinguishable from one that crashed.
	 * <p>
	 * @return	: true when there is at least one file to process
	 */
	public boolean prepareFiles () {
		// check input folder path
		if (null == parameter) return false;
		if (parameter.inputDir.isEmpty()) {
			IJ.error("Deskew Batch", "No input folder was selected.");
			return false;
		}
		inputFolder = new File(parameter.inputDir).getAbsoluteFile();
		if (!inputFolder.exists() || !inputFolder.isDirectory()) {
			IJ.error("Deskew Batch", "The input folder does not exist:\n" + inputFolder.getAbsolutePath());
			return false;
		}
		parameter.inputDir = inputFolder.toPath().normalize().toString();
		// get file list match request from input folder
		if (!parameter.keywords.isEmpty()) {
    		keywords = parameter.keywords.split(",");
    		for (int i=0; i<keywords.length; i++) {
    			keywords[i] = keywords[i].replaceAll("\\s+",""); // remove spaces
        	}
    	}
		// check save folder path
		try {
			saveFolder = resolveSaveFolder(parameter, inputFolder);
			mirrorTree = mirrorsTree(parameter);
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
		if (inputFileList.length == 0 && zarrInputFiles.isEmpty()) {
			IJ.error("Deskew Batch", "No TIFF file left to process in:\n" + inputFolder.getAbsolutePath()
					+ (parameter.keywords.isEmpty() ? "" : "\nfile name must contain: " + parameter.keywords)
					+ (overwrite ? "" : "\nAlready processed files are skipped; choose \"overwrite\" to redo them."));
			return false;
		}
		return true;
	}

	/**			Whether the input folder's sub-folder tree is reproduced under the result folder
	 * <p>		Only a run that includes sub-folders has a tree to reproduce. Then "reproduce input
	 * 			folder structure" decides it - ticked, each sub-folder's results go to the same
	 * 			sub-folder under the result folder; unticked, every result goes into the result
	 * 			folder itself. Saving to the same (data) folder always reproduces it: that result
	 * 			folder is inside the input, and the checkbox is greyed there.
	 * <p>		It used to be decided nowhere. The checkbox was stored and never read; the single-file
	 * 			TIFF path reproduced the tree whenever sub-folders were included, and the channel
	 * 			group, dual-output and OME-Zarr paths never did - OME-Zarr went further and wrote every
	 * 			sub-folder into one store, where a second acquisition's time points share the first's
	 * 			labels and were skipped as already committed.
	 */
	static boolean mirrorsTree(Parameter parameter) {
		return parameter.recursive && (parameter.saveToSame || parameter.reproduceInputTree);
	}

	/** The folder one input file's results go to, by {@link #mirrorsTree}. */
	private File resultFolderFor(File inputFile) {
		return mirrorTree ? BatchProcessingUtils.mirroredUnder(inputFile, inputFolder, saveFolder) : saveFolder;
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
	


	/**			Deskew each timepoint's acquisition channels together into one result
	 * <p>		Files are grouped by their _ChannelNNNN token, every camera half is deskewed,
	 * 			the selected half is flipped and aligned onto the other, and the sources the
	 * 			user picked are written as the channels of a single volume.
	 */
	private void processChannelGroups (LivePreview preview) {
		List<File> files = new ArrayList<File>();
		for (String path : inputFileList) files.add ( new File(path) );
		Map<String, List<File>> groups = channels.group ( files );
		IJ.log ( "OPM Deskew Batch: " + files.size() + " file(s) in " + groups.size() + " acquisition group(s)." );

		int done = 0, failed = 0, index = 0;
		for (Map.Entry<String, List<File>> entry : groups.entrySet()) {
			if ( Shutdown.stopping() ) { logStopped ( "acquisition group" ); break; }
			List<File> group = entry.getValue();
			IJ.showProgress ( index++, groups.size() );
			String outputName = BatchProcessingUtils.channelGroupOutputName ( group ) + "-deskewed";
			File resultFolder = resultFolderFor ( group.get(0) );
			File savePath = BatchTiffOutput.volumeFile ( resultFolder, parameter.saveSeparate, outputName );
			if ( !overwrite && VolumeIO.isCompleteTiff(savePath) ) {
				IJ.log ( "OPM Deskew Batch skip existing result: " + savePath );
				done++;
				continue;
			}
			if (preview != null) preview.update ( resultFolder );
			ImagePlus combined = null;
			BatchTiffOutput tiff = null;
			String savedDir = parameter.saveDir;
			try {
				combined = MultiChannelDeskew.deskewGroup ( group, parameter, channels, outputName );
				if (combined == null) { failed++; continue; }
				/* The same writer the dual-output path uses, so the projections go through
				 * ProjectionBatch: one upload per channel. Deskew.prepareResults pushed the
				 * whole multi-channel composite once per axis, which for two camera halves of
				 * a production volume is 2.6 GB - past OpenCL's single-allocation limit on an
				 * 8 GB card - so every projection failed on the GPU, fell back to the CPU, and
				 * left the context unable to allocate the next group's deskew either. */
				parameter.impInput = null;
				parameter.saveDir = resultFolder.getAbsolutePath();
				tiff = BatchTiffOutput.prepare ( combined, parameter );
				tiff.write ( parameter );
				done++;
			} catch (Throwable t) {
				failed++;
				IJ.log ( "OPM Deskew Batch failed for " + outputName + ": " + t );
			} finally {
				parameter.saveDir = savedDir;		// one group's folder must not leak into the next
				if (tiff != null) tiff.close();
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
		long start = System.currentTimeMillis();
		// in case only left or right side requested
		
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
		/* A Zarr-only run has no TIFF phase at all. It used to run one anyway - the writes
		 * were suppressed further down, but the deskew itself was repeated for every file,
		 * roughly doubling the cost of a large acquisition for no output. */
		if (parameter.savesZarr() && !parameter.savesTiff()) {
			logPhase ( "OPM Deskew Batch: OME-Zarr only, no TIFF phase." );
			writeOmeZarr();
			return;
		}
		boolean zarrAfterTiff = parameter.saveDeskewZarr && parameter.saveDeskewImage;
		if (parameter.saveDeskewZarr && !zarrAfterTiff) {
			logPhase ( "OPM Deskew Batch starting OME-Zarr phase." );
			writeOmeZarr();
		}

		/* A TIFF-only run has a preview too: the OPM Data Viewer reads the result folders as
		 * they fill, with TiffCompletionCheck keeping a half-written file out of the view. It
		 * is raised once, before the first result exists, because the viewer's own live update
		 * is what picks the results up - and a folder with nothing in it yet simply shows
		 * nothing until there is. */
		LivePreview tiffPreview = parameter.livePreview && !parameter.savesZarr()
				? LivePreview.of(parameter, channels, true)
				: null;
		if (tiffPreview != null && !tiffPreview.wanted()) tiffPreview = null;

		// with acquisition channels combined, one timepoint's files are processed together
		if ( channels.combineAcquisitionChannels ) {
			if (parameter.saveDeskewImage)
				logPhase ( "OPM Deskew Batch starting TIFF phase." );
			processChannelGroups(tiffPreview);
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
			if ( Shutdown.stopping() ) { logStopped ( "input file" ); break; }
			System.out.printf("\n\tprocessing file:\n\t%s\n", path);
			
			long start_file = System.currentTimeMillis();

			/* The result folder is decided here, by mirrorsTree, and handed over in saveDir.
			 * processFile would otherwise reproduce the tree itself whenever sub-folders are
			 * included, by a string replace on the input path, whatever the checkbox said. */
			File resultFolder = resultFolderFor ( new File(path) );
			if (tiffPreview != null) tiffPreview.update ( resultFolder );
			String savedDir = parameter.saveDir;
			boolean savedRecursive = parameter.recursive;
			try {
				parameter.saveDir = resultFolder.getAbsolutePath();
				parameter.recursive = false;
				Deskew.processFile ( path, parameter) ;
			} finally {
				parameter.saveDir = savedDir;		// one file's folder must not leak into the next
				parameter.recursive = savedRecursive;
			}
			
			/*
			ImagePlus imp = VolumeIO.open(path);
		
			if (parameter.channelStr.equals("align with SIFT matrix") && !parameter.alignmFile.isEmpty() ){
				parameter.alignMatrix = IO.loadMatrixFromFile(parameter.alignmFile);
			}
			
			if (doHalf)
				imp = (Partition.separateImageLeftRight(imp, parameter.channelStr)) [0];
			
			if (parameter.doDeskew) {
				ImagePlus imp_deskew = Deskew.deskew_image ( imp, parameter );
						//imp, parameter.xyPixelSize, parameter.zStepSize, parameter.opmAngle,
				String saveDir = saveFolder.getAbsolutePath();
				if (parameter.saveSeparate)	saveDir += File.separator + "deskew";
				try {
					Files.createDirectories(Paths.get(saveDir));
				} catch (IOException e) {
					System.out.println(e.getMessage());
					continue;
				}
				String savePath = VolumeIO.tiffPath ( saveDir + File.separator + imp_deskew.getTitle() );
				if (!new File(savePath).exists() || overwrite)
					VolumeIO.saveTiff(imp_deskew, savePath);
				
				imp.setImage(imp_deskew);
				imp.setTitle(imp_deskew.getTitle());
			}
			
			// get channel image
			ImagePlus[] imp_channel = new ImagePlus[]{imp};
			if (!doHalf) {
				imp_channel = Partition.separateImageLeftRight (
						imp, parameter.channelStr );
			}
			
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
		    					continue;
		    				}
		    				
		    				String savePath = VolumeIO.tiffPath ( saveDir + File.separator + imp_project.getTitle() );
		    				if (!new File(savePath).exists() ||  overwrite)
		    					VolumeIO.saveTiff(imp_project, savePath);
		    			}	// projection type loop
		    		}		// projection axis loop
				}			// channel image loop	
			}
			*/
			float duration_file = System.currentTimeMillis() - start_file;
			System.out.printf("\n\tprocessing file finished after %.3f seconds.\n", duration_file / 1000);
		} 					// file loop
		if (zarrAfterTiff) {
			logPhase ( "OPM Deskew Batch TIFF phase finished; starting OME-Zarr phase." );
			writeOmeZarr();
		}
		float duration = System.currentTimeMillis() - start;
		System.out.printf("\n\tBatch processing files finished after %.3f seconds.\n", duration / 1000);
		System.gc();
		return;
	}

	/**
	 * One deskew pass per timepoint, followed by two independent writer tasks.
	 * TIFF sees the selected runtime-aligned composite; Zarr sees the canonical halves.
	 */
	private void processDualOutputs() {
		/* The same preview the live listener uses, over the same store: virtual views opened
		 * through OmeZarrView and grown in place as time points commit. Batch writes the
		 * dataset exactly as Live does, so there is nothing here to invent. */
		LivePreview preview = parameter.livePreview
				? LivePreview.of(parameter, channels, !parameter.savesZarr())
				: null;
		if (preview != null && !preview.wanted()) preview = null;
		for (Map.Entry<File, List<File>> acquisition : BatchProcessingUtils.byFolder(zarrInputFiles).entrySet()) {
			if (Shutdown.stopping()) { logStopped("acquisition folder"); break; }
			processDualOutputs(acquisition.getKey(), acquisition.getValue(), preview);
		}
	}

	/**			One acquisition folder: its own store, and its TIFF results beside it
	 * <p>		Both go to the folder's result folder ({@link #resultFolderFor}); the store is named
	 * 			after the acquisition folder. Without sub-folders this is the whole run, as before.
	 */
	private void processDualOutputs(File folder, List<File> files, LivePreview preview) {
		File resultFolder = resultFolderFor(files.get(0));
		File root = OmeZarrConverter.defaultRoot(resultFolder, folder);
		OmeZarrConverter.Conversion conversion = null;
		ExecutorService tiffWriter = Executors.newSingleThreadExecutor(writerThread("OPM-TIFF-writer"));
		ExecutorService zarrWriter = Executors.newSingleThreadExecutor(writerThread("OPM-Zarr-writer"));
		int tiffDone = 0, tiffFailed = 0, zarrDone = 0;
		boolean zarrHealthy = true;
		String savedDir = parameter.saveDir;
		try {
			/* BatchTiffOutput reads its folder from saveDir - needsWrite here, write on the TIFF
			 * writer thread. Every time point waits for that writer before the next one starts,
			 * so the folder cannot change under a write in flight. */
			parameter.saveDir = resultFolder.getAbsolutePath();
			OmeZarrConverter.Options options = OmeZarrConverter.optionsFromParameter(
					parameter, channels, folder);
			conversion = OmeZarrConverter.openConversion(folder, files, root, options);
			final OmeZarrSession dualSession = conversion.session;
			logPhase("OPM Deskew Batch dual-output pipeline: " + conversion.timePoints.size()
					+ " timepoint(s), one deskew pass, parallel TIFF/Zarr writers.");

			int index = 0;
			for (OpmTimepointProcessor.TimePoint timePoint : conversion.timePoints) {
				if (Shutdown.stopping()) { logStopped("time point"); break; }
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
				OmeZarrSession.PreparedTimePoint zarr = null;
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
						zarr = OmeZarrSession.prepare(canonical, parameter.tryGPU, options.writeProjections);
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
					final OmeZarrSession.PreparedTimePoint zarrTask = zarr;
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
				// after the time point is committed, so the views only ever read whole ones
				if (preview != null && zarrHealthy) preview.update(root);
			}
			if (zarrHealthy && !Shutdown.stopping()) {
				conversion.session.markComplete();
				logPhase("OPM Deskew Batch completed canonical OME-Zarr: " + root.getAbsolutePath());
			}
			IJ.showProgress(1.0);
			logPhase("OPM Deskew Batch dual-output finished: TIFF " + tiffDone + " written, "
					+ tiffFailed + " failed; Zarr " + zarrDone + " newly committed.");
		} catch (Throwable failure) {
			logPhase("OPM Deskew Batch dual-output setup failed for " + folder.getAbsolutePath() + ": " + failure);
		} finally {
			shutdown(tiffWriter);
			shutdown(zarrWriter);
			if (conversion != null) conversion.close();
			parameter.saveDir = savedDir;		// once the writers are done with it
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
		return Shutdown.daemonThreads(name);
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

	/**			Say why a loop unwound early, once, in both records
	 * <p>		A batch that stops between units of work is a normal outcome - Fiji quitting or
	 * 			Escape - and must be distinguishable in the log from one that simply ran out
	 * 			of files.
	 */
	private void logStopped(String unit) {
		logPhase("OPM Deskew Batch stopped before the next " + unit + ": " + Shutdown.reason() + ".");
	}

	/** Report phase transitions in both the Fiji Log window and the persistent batch log. */
	private void logPhase(String message) {
		IJ.log(message);
		if (log != null) log.add(message);
	}

	/**			Write the same canonical, unaligned L/R dataset used by Live and the standalone converter
	 * <p>		One dataset per acquisition folder, named after it and written to that folder's
	 * 			result folder ({@link #resultFolderFor}). Without sub-folders that is the one folder
	 * 			and the one store it always was.
	 */
	private void writeOmeZarr() {
		for (Map.Entry<File, List<File>> acquisition : BatchProcessingUtils.byFolder(zarrInputFiles).entrySet()) {
			if (Shutdown.stopping()) { logStopped("acquisition folder"); break; }
			File folder = acquisition.getKey();
			File root = OmeZarrConverter.defaultRoot(resultFolderFor(acquisition.getValue().get(0)), folder);
			try {
				OmeZarrConverter.Options options = OmeZarrConverter.optionsFromParameter(
						parameter, channels, folder);
				OmeZarrConverter.convertFiles(folder, acquisition.getValue(), root, options);
				logPhase("OPM Deskew Batch wrote canonical OME-Zarr: " + root.getAbsolutePath());
			} catch (Throwable failure) {
				logPhase("OPM Deskew Batch OME-Zarr failed for " + folder.getAbsolutePath() + ": " + failure);
			}
		}
	}


	/**		List the files this run should process, skipping the ones already done
	 * <br>		A result of the same name in the save folder counts as done, which is what lets
	 * <br>		an interrupted batch be restarted without redoing the volumes it finished.
	 *
	 * @param inputFolder	: folder to search
	 * @param extensions	: accepted file extensions
	 * @param keywords		: substrings that must all appear in the file name
	 * @param saveFolder	: result folder, consulted to find already processed files
	 * @param recursive		: also search sub folders
	 * @param overwrite		: process every match, even one that already has a result
	 * <p>
	 * @return				: absolute paths still to process, sorted
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
    	// cross check input and output file list, for a list of processed file
    	List<String> processedFileList = new ArrayList<String>();
    	Iterator<String> iter = inputFileList.iterator();
    	while (iter.hasNext()) {
    		String filePath = iter.next();
    		String fileName = FilenameUtils.getBaseName(filePath);
    		
    		for (String outputFile : outputFileList) {
    			if (outputFile.contains(fileName)) { // match found
    				
    				processedFileList.add(filePath);
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
	 * @param parentDir		: folder to search
	 * @param extensions	: accepted file extensions
	 * @param keywords		: substrings that must all appear in the file name
	 * @param recursive		: also search sub folders
	 * <p>
	 * @return				: absolute paths of every matching file, sorted by name
	 */
	/**			The digits of a path as one number, for ordering an acquisition by time point
	 * <p>		A path with no digits at all used to throw NumberFormatException out of the
	 * 			comparator. That was unreachable only because the keyword filter happened to
	 * 			exclude such files; with an empty filter now accepting everything, a name like
	 * 			"reference.tif" reaches the sort and must not take the run down.
	 *
	 * @param path				: file path
	 * <p>
	 * @return					: the last 18 digits of the path as a number, or 0 when it has none
	 */
	static long trailingDigits (
			String path
			) {
		if (null == path) return 0;
		String digits = path.replaceAll("[^0-9]", "");
		if (digits.isEmpty()) return 0;
		if (digits.length() > 18) digits = digits.substring(digits.length() - 18);
		try {
			return Long.parseLong ( digits );
		} catch (NumberFormatException unusable) {
			return 0;
		}
	}

	public static String[] getFileList (
			File parentDir, 
			String[] extensions,
			String[] keywords,
			boolean recursive
			) {
		if (null == parentDir) return new String[0];
		Collection<File> files = FileUtils.listFiles(parentDir, extensions, recursive);
		List<String> fileList = new ArrayList<String>();
		/* No keyword means no filter, which is what the empty "file name contains" field is
		 * asking for. The loop below can never match an empty array, so the default Deskew
		 * Batch configuration used to discover zero files and report "no TIFF left to
		 * process". BatchProcessingUtils.matchesKeywords already treats empty as "accept
		 * everything", and the two discovery paths have to agree or a Zarr run and a TIFF run
		 * of the same folder see different files. */
		boolean unfiltered = (null == keywords) || 0 == keywords.length;
		for (File file : files) {
			if (unfiltered) {
				fileList.add(file.getAbsolutePath());
				continue;
			}
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
			long d1 = trailingDigits ( s1 );
			long d2 = trailingDigits ( s2 );
			if (d1 != d2) return d1 < d2 ? -1 : 1;
			return s1.compareToIgnoreCase ( s2 );	// same number, or none: keep it stable
		    }
		});
		return fileList.toArray(new String[fileList.size()]);
	}
    
	
}
