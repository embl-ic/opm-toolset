package de.embl.iclm;

import java.awt.AWTEvent;
import java.awt.Scrollbar;
import java.awt.TextField;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.plugin.Duplicator;
import ij.plugin.filter.ExtendedPlugInFilter;
import ij.plugin.filter.PlugInFilterRunner;
import ij.process.ImageProcessor;


/** 		|	                                          
 *  		|	                                        
 *  		|	                                      
 *  		|	                                    /
 *  		|	                                  /
 *  		|	                                /
 *  		|	                              /
 *  		|	                            /
 *  		|	                          /
 *  		|	                        /
 *  		|	                      /
 *  	   Y -	                    /
 *  	    | 	                  /
 *  	 (Y axis)	            /
 *  		|	              /
 *  	   Y +	            /
 *  		|	          /
 * 			|	        /
 * 			|         /
 * 			|       / 
 * 			|     / 
 * 			|   / 
 * 			| / ) θ : 
 * 			+------------------(Z -)--(Z axis)--(Z +)------------> > > >  (stage move direction)
 * 			 
 * 			 - 	deskewed z voxel size = z step size * sinθ
 * 			 	deskewed y shift = z step size * cosθ
 * 			 
 * 			 - 	In raw volume, if moving from z- to z+, feature moving down (y+ to y-), 
 * 			   	then imaging angle like above;
 * 			   	If feature moving up (y- to y+), then imaging angle is 180 - θ.
 * 			   
 * 			 - 	If reslice raw volume (XY-Z stack) from left, resulting volume is YZ-X stack.
 * 			 	on 2D slice (YZ view), the feature profile form angle as 90-θ from horizontal axis.
 * 			 	
 * 			 	If reslice from top, resulting volume is XZ-Y stack.
 * 			 	on 2D slice (XZ view), the feature profile form angle as ? from horizontal axis.
 * 			 	
 * 			 -	The physical calibration of raw (not yet deskewed) volume would be xySize, zSize.
 * 			 	After deskewing, the pixel height (ySize) should be scaled by a factor of cosθ.
 * 
 * 
 * 			 
 */
/**		Why this is an ExtendedPlugInFilter
 * <p>	Only so that ImageJ hands over a PlugInFilterRunner: GenericDialog.addPreviewCheckbox
 * <br>	needs one, and there is no other way to obtain it. The filter contract itself is not
 * <br>	used to do the work, because a deskew is a whole-volume affine transform, not a
 * <br>	per-slice filter that ImageJ could iterate and assemble.
 * <p>	So the division is:
 * <br>		setup(arg, imp)		- accept or refuse the image, nothing else
 * <br>		showDialog(...)		- build the dialog, and on OK do the final processing here,
 * <br>							  then return DONE so the runner does not process anything
 * <br>		run(ip)				- preview only, driven by the runner; ip is deliberately unused
 * <p>	Earlier versions returned DONE from showDialog and then re-entered setup with the
 * <br>	argument "final" to trigger run(ip) by hand. That looked like the FINAL_PROCESSING
 * <br>	protocol but was not: the runner had already stopped, so the flag and the argument only
 * <br>	obscured which method actually did the work.
 */
public class Deskew implements ExtendedPlugInFilter, DialogListener {
	private Parameter parameter = null;
	private GenericDialog dialog;


	private ImagePlus imp = WindowManager.getCurrentImage();
	private Roi roi = null;
    private ImagePlus imp_downsample = null;
    private static double downsample_factor = 1.0;

	static String parameterFile = Parameter.loadSettingMessage;

    private boolean impChanged = false;
    private boolean fileChanged = false;
    private boolean updatingDialog;
    /* No FINAL_PROCESSING: the final pass is run from showDialog, not by the filter runner. */
    private int flags = DOES_8G|DOES_16|DOES_32|DOES_STACKS|STACK_REQUIRED;
    private String uniqueID = null;

	/**			Accept the active image, or refuse the command
	 * <br>		Refuses when a Deskew Image dialog is already open, so two dialogs cannot fight
	 * <br>		over the same preview windows and the same persisted settings.
	 *
	 * @param arg	: plugin argument from plugins.config; unused by this command
	 * @param imp	: active image
	 * <p>
	 * @return		: the supported image types, or DONE to cancel the command
	 */
	@Override
	public int setup(String arg, ImagePlus imp) {
		Party.commandStarted ( "Deskew Image" );
		if ( Utils.checkPluginWindowExist( "Deskew Image" ) ) return DONE;
		if (null == imp) return DONE;
		if ( null == uniqueID )
			uniqueID = UUID.randomUUID().toString().substring(0, 8);
		return flags;
	}

	/**			Build the deskew dialog, and deskew the volume when the user accepts it
	 *
	 * @param imp		: active image
	 * @param command	: menu command name
	 * @param pfr		: filter runner, needed by the dialog's preview checkbox
	 * <p>
	 * @return			: always DONE; the work is done here, not by the filter runner
	 */
	@Override
	public int showDialog(ImagePlus imp, String command, PlugInFilterRunner pfr) {
		parameter = new Parameter("image");
		/* The runner locks the image for the duration of the filter. This command displays and
		 * duplicates that same image itself, so the lock has to come off first. */
		imp.unlock();
		parameter.impInput = imp;
		parameter.tryGPU = true;
		parameter.autoPartition = true;
		parameter.displayResult = true;
		parameter.axisPartition = "X"; parameter.axisCombine = "X";
		dialog = parameter.deskew_image ("Deskew Image", pfr);
		dialog.addDialogListener(this);
        updatingDialog = false;
        dialog.showDialog();
        if (dialog.wasOKed()) {		// proceed to deskew the whole volume
        	processFinal ( imp );
        	cleanUp ();
            parameter.storeParam();
        }
        if (dialog.wasCanceled()) {	// exit dialog
        	cleanUp ();
        }
        return DONE;
	}
	
	@Override
	public boolean dialogItemChanged(GenericDialog gd, AWTEvent e) {
		// do not load and parse parameter while GUI updating
		if (updatingDialog) return true;
		// get the image current dialog supposed to processing on
		ImagePlus imp_now = 		gd.getNextImage();
		// get deskew parameters
		parameter.xyPixelSize = 	gd.getNextNumber();
		parameter.zStepSize = 		gd.getNextNumber();
        parameter.opmAngle = 		gd.getNextNumber();
        String file_now = 			gd.getNextString();
        parameter.doInverse = 		gd.getNextBoolean();
        parameter.channelStr = 		gd.getNextChoice();
        parameter.alignmFile = 		gd.getNextString();
        parameter.projX = 			gd.getNextBoolean();
        parameter.projY = 			gd.getNextBoolean();
        parameter.projZ = 			gd.getNextBoolean();
		parameter.maxProj = 		gd.getNextBoolean();
		parameter.avgProj = 		gd.getNextBoolean();
		// Loading a settings file is part of accepting the dialog, not a preview-only action.
		if ( file_now.equals( parameterFile ) ) {
			fileChanged = false;
		} else {
			fileChanged = true;
			parameterFile = file_now;
		}
		if (fileChanged && !gd.isPreviewActive()) {
			/* updateMatrixFileField needs an image height, but no downsample exists when Preview
			 * is off. Borrow the full image for parsing and leave preview state untouched. */
			ImagePlus previewInput = imp_downsample;
			try {
				imp_downsample = imp_now;
				updateMatrixFileField();
			} finally {
				imp_downsample = previewInput;
			}
			fileChanged = false;
		}
		// get previewing state
       
        if (gd.isPreviewActive()) {
	        // check whether image or active ROI changed
	        if ( !imp_now.equals( imp ) ) {	// image not equal, copy image, changed
	        	impChanged = true;
	        	imp = imp_now;
	        } else {						// image equal, now check ROI
	        	Roi roiNow = imp.getRoi();
	        	if ( null == roiNow && null == roi ) {	// no ROI before, and no ROI now, no change
	        		impChanged = false; 
	        	} else if ( null == roi ) {		// no ROI before, create ROI, changed
	        		roi = new Roi ( roiNow.getBounds() );
	        		imp.setRoi( roi, true );
	        		impChanged = true;
	        	} else if ( null == roiNow ) {	// no ROI now, delete ROI, changed
	        		roi = null;
	        		impChanged = true;
	        	} else if ( roiNow.getBounds().equals( roi.getBounds() ) ) {
	        		impChanged = false;
	        	} else { 						// ROI not equal, copy new ROI to roi
	        		roi = new Roi ( roiNow.getBounds() );
	        		imp.setRoi( roi, true );
	        		impChanged = true;
	        	}
	        }
	        // update projection parameters, calibrate image, update image window
	        imp.setCalibration ( Utils.createCalibration(parameter) );
	    	imp.updateAndRepaintWindow();
        }
        parameter.parseProjectionParameter();
        parameter.storeParam();
        return true;
	}
	
	/**			Number of passes the filter runner intends to make
	 * <br>		Nothing to do: this command deskews the whole volume in one operation and does
	 * <br>		not report per-slice progress through the runner.
	 *
	 * @param nPasses	: pass count from the filter runner, unused
	 */
	@Override
	public void setNPasses(int nPasses) {
	}

	/**			Deskew the downsampled preview
	 * <p>		Called by the filter runner whenever the preview needs refreshing. The
	 * <br>		ImageProcessor argument is deliberately unused: a deskew is an affine transform
	 * <br>		of the whole volume, so the input is taken from parameter.impInput instead of
	 * <br>		from the single slice the runner offers.
	 *
	 * @param ip	: slice offered by the filter runner; unused, see above
	 */
	@Override
	public void run( ImageProcessor ip ) {
		if ( !dialog.isPreviewActive() ) return;	// the final pass is run from showDialog

		if (null == imp_downsample || impChanged) {
			imp_downsample = Utils.downSample ( imp );
			downsample_factor = (double)imp_downsample.getWidth() / (double)imp.getWidth();
		}
		impChanged = false;
		if (imp_downsample != imp) { // in case input image is small enough downsample is input
			parameter.impInput = imp_downsample;
			imp_downsample.setTitle ( "preview" );
			imp_downsample.hide();
		}

		boolean loadMatrixFromFile = false;
		if (fileChanged) loadMatrixFromFile = updateMatrixFileField();
		fileChanged = false;

		// a matrix loaded from file already carries the geometry; otherwise build it from the dialog
		if ( !loadMatrixFromFile ) {
			parameter.deskewMatrix = Transform.deskew (
					parameter.zStepSize, parameter.xyPixelSize,
					parameter.opmAngle,
					imp_downsample.getHeight());
		}

		process ( parameter );
	}

	/**			Deskew the full resolution volume, once the user accepts the dialog
	 * <br>		Called directly from showDialog rather than through the filter runner, which has
	 * <br>		already been told DONE by then.
	 *
	 * @param imp	: the image the dialog was opened on
	 */
	private void processFinal ( ImagePlus imp ) {
		cleanUp ();				// close the preview windows before the real result appears
		parameter.impInput = imp;
		process ( parameter );
		saveSettings ( parameter );
	}


	/**			Load a settings file named in the dialog and update the dialog from it
	 * <br>		Accepts an ExperimentalParameters.txt, a saved deskew matrix, or a saved list of
	 * <br>		transformations; an unreadable file resets the field to its prompt text.
	 * <p>
	 * @return	: true when a matrix was loaded, so the caller does not rebuild one from the fields
	 */
	public boolean updateMatrixFileField () {
		// file not change, do nothing
		if ( !fileChanged ) return false;
		// if matrix file field is not changed, do nothing
		
		// TODO: in the case field is default message: when to set it back to default?
		// if matrix file field is default value, do nothing
		if ( parameterFile.equals(Parameter.loadSettingMessage) ) return false;
		
		// check the setup file type, if unknown, do nothing
		String fileType = parameter.getSetupFileType( parameterFile );
		if (null == fileType) {
			parameterFile = Parameter.loadSettingMessage;
			( (TextField) dialog.getStringFields().elementAt(0) )
			.setText( Parameter.loadSettingMessage );
			return false;		// TODO:need to set to default
		}
		
		// get down sampled image
		if (null == imp_downsample) return false; // imp_downsample = Utils.downSample( imp );
		if (!loadSettingsFile(parameter, parameterFile, imp_downsample, fileType)) {
			parameterFile = Parameter.loadSettingMessage;
			( (TextField) dialog.getStringFields().elementAt(0) )
			.setText( Parameter.loadSettingMessage );
			return false;
		}

		// update parameter dialog
		updatingDialog = true;
		// disable previewing when updating the dialog GUI
		boolean previewRunning = dialog.isPreviewActive();
		if (previewRunning) {
			dialog.previewRunning(false);
			dialog.getPreviewCheckbox().setState(false);
		}
		if ( fileType.equals("expParams") ) {
			// update xy pixel size field
			( (TextField) dialog.getNumericFields().elementAt(0) )
				.setText( ""+IJ.d2s(parameter.xyPixelSize, 1) );
		}
  		// update z-step slider and number field
		( (Scrollbar) dialog.getSliders().elementAt(0) )
			.setValue( (int)Math.round( 10*parameter.zStepSize ) );
		( (TextField) dialog.getNumericFields().elementAt(1) )
			.setText( ""+IJ.d2s(parameter.zStepSize, 1) );
        // update opm angle slider and number field
        ( (Scrollbar) dialog.getSliders().elementAt(1) )
			.setValue( (int)Math.round( 10*parameter.opmAngle ) );
        ( (TextField) dialog.getNumericFields().elementAt(2) )
			.setText( ""+IJ.d2s(parameter.opmAngle, 1) );
        if (previewRunning) {
        	dialog.previewRunning(true);
        	dialog.getPreviewCheckbox().setState(true);
        }
        // update parameter dialog finish, refresh GUI
        dialog.repaint();
        updatingDialog = false;
        // successfully loaded matrix file and updated dialog GUI
		return true;
	}

	/**			Load one recognised settings file without depending on Preview being active
	 * <p>		Separated from the dialog repaint so the no-preview acceptance path can be
	 * 			tested headlessly with the same parser used by the interactive command.
	 */
	static boolean loadSettingsFile(Parameter parameter, String path,
			ImagePlus reference, String fileType) {
		if (parameter == null || reference == null || fileType == null) return false;
		if (fileType.equals("expParams")) {
			double[] expParams = IO.loadExperimentalParametersFromFile(path);
			if (expParams == null) return false;
			parameter.xyPixelSize = expParams[0];
			parameter.zStepSize = expParams[1];
			parameter.opmAngle = expParams[2];
			parameter.deskewMatrix = Transform.deskew(parameter.zStepSize,
					parameter.xyPixelSize, parameter.opmAngle, reference.getHeight());
			return true;
		}
		if (fileType.equals("matrix")) {
			double[][] matrix = IO.loadMatrixFromFile(path);
			if (matrix == null) return false;
			double[] values = Transform.reverse_deskew(matrix, reference.getHeight());
			if (values == null) return false;
			parameter.zStepSize = values[0] * parameter.xyPixelSize;
			parameter.opmAngle = values[1];
			matrix[2][3] = values[2];
			parameter.deskewMatrix = matrix;
			return true;
		}
		if (fileType.equals("transform")) {
			Map<String, Object> transformation = IO.loadTransformationFromFile(path);
			// Transformation-list application remains a separate TODO; preserve its validation.
			return transformation != null;
		}
		return false;
	}
	
	
	
	/**			Close all preview image(s) and free up memory
	 * 
	 */
	public void cleanUp () {
		if (null != imp_downsample) imp_downsample.close();
		// close all preview images
		String[] titles = WindowManager.getImageTitles();
		for (String title : titles) {
			if (title.contains( "preview" ))	//uniqueID )) 
				WindowManager.getImage(title).close();
		}
		Utils.collectGarbage();
	}
	
	
	public static void saveSettings ( Parameter parameter ) {
		if (!parameter.saveDeskewMatrix && !parameter.saveAlignMatrix) return;
		// check if save folder exists
		File saveFolder = new File(parameter.saveDir);
        if ( !saveFolder.exists() ) {
        	System.out.printf("\n\tsave folder does not exist!\n\t%s\n", parameter.saveDir);
        	return;
        }
        // update image name parts in save path of deskew and align matrix
        String imageName = Utils.getName( parameter.impInput );
        // try to save deskew matrix to csv file
        if ( parameter.saveDeskewMatrix && null != parameter.deskewMatrix ) {
        	String fileNameDeskew = parameter.fileNameDeskew.replace("<image name>", imageName);
            String deskewMatrixPath = parameter.saveDir + File.separator + fileNameDeskew;
            IO.saveMatrixToFile(parameter.deskewMatrix, deskewMatrixPath);
        }
        // try to save align matrix to csv file
        if ( parameter.saveAlignMatrix && null != parameter.alignMatrix ) {
        	String fileNameAlign = parameter.fileNameAlign.replace("<image name>", imageName);
            String alignMatrixPath = parameter.saveDir + File.separator + fileNameAlign;
            IO.saveMatrixToFile(parameter.alignMatrix, alignMatrixPath);
        }
	}
	

	/** The unified function portal to process (deskew, projection, etc...) input image
	 *  for deskew live image, deskew batch, deskew folder watch, deskew TCP/IP listener
	 * <p>	Every entry point comes through here, which is what keeps the interactive command,
	 * <br>	the batch run and the two live paths producing identical pixels. The steps are:
	 * <br>		separate the camera halves, deskew each one, flip and align the right half if
	 * <br>		asked, combine them into one hyperstack, then display, save and project.
	 *
	 * @param name				: base name for the result images and the files written from them
	 * @param parameter			: all processing settings, including the deskew matrix
	 * @param keepInput			: leave the input image open; false lets the input be released
	 * 							  as soon as it has been deskewed, which matters in batch
	 * @param loadFromFileFirst	: use the matrices already loaded into parameter rather than
	 * 							  recomputing them from the dialog fields
	 * <p>
	 * @return					: true when every requested result was displayed or written
	 */
	public static boolean process (
			String name,
			Parameter parameter,
			boolean keepInput,
			boolean loadFromFileFirst
			) {
		// TODO: combine time lapse or not?
		// prepare log
		// timing the start
		long start = System.currentTimeMillis();
		
		
		ImagePlus impInput = parameter.impInput;	// check here if a copy created
		if (null == impInput) return false;
		VolumeIO.normalize(impInput);	// planes may arrive on the T axis; put them back on Z
		
		/**
		 * separate image into channel: 6 cases:
		 * 1, whole image:					1 image
		 * 2, left only:					1 image
		 * 3, right only:					1 image
		 * 4, left + right separate:		2 image
		 * 5, left + right flipped:			2 image
		 * 6, left + right filp+aligned:	2 image
		 */
		ImagePlus[] impInputs = Partition.separateImageLeftRight ( impInput, parameter.channelStr, keepInput );
		
		
		
		ImagePlus[] impOutputs = new ImagePlus[2];
		
		
		// deskew 1st image in array with input matrix:		
		if ( loadFromFileFirst && null != parameter.deskewMatrix )	//
			impOutputs[0] = deskew_image ( impInputs[0], parameter.deskewMatrix, parameter.tryGPU );
		else
			impOutputs[0] = deskew_image ( impInputs[0], parameter );
		impOutputs[0].getImageStack().setSliceLabel( name + "-[left]", 1 );
		// close 1st input image, save RAM
		if ( !parameter.channelStr.equals("whole image") || !keepInput ) impInputs[0] = null; //impInputs[0].close(); 		
		
		
		
		
		// 2nd image exist: 1: R,	2: R-flip,	3: R-align
		if ( 2 == impInputs.length ) {
			impOutputs[0].setTitle ( name + "-[left]" );
			// L + R case:
			if ( parameter.channelStr.equals("left & right separately") ) {
				// deskew the right side of the image with the same transformation matrix
				impOutputs[1] = deskew_image ( impInputs[1], parameter.deskewMatrix, parameter.tryGPU );
				// close 2nd input image
				impInputs[1].close();		
			} else {
			// R-flip or R-align case: 
				double[][] matrix_deskew = Transform.matrix_flipX ( impInputs[1], parameter.deskewMatrix );
				// flip and deskew simutaneously the right side of the image
				impOutputs[1] = deskew_image ( impInputs[1], matrix_deskew, parameter.tryGPU );
				// close 2nd input image
				impInputs[1].close();
				// for the scope of the following operation, mark the flip with []
				impOutputs[1].setTitle ( name + "-[right]" );
			// R-align case:
				if ( parameter.channelStr.equals("align with SIFT") ) {
				// apply rigid 2D transform to the flipped and deskewed right side of the image	
					// check if a pre-loaded alignment matrix exist
					if ( loadFromFileFirst && null != parameter.alignMatrix ) {
						SIFT.alignStackSIFT2 ( impOutputs[1], parameter.alignMatrix, true );
					} else {
						// try to load rigid 2D matrix from file
						double[][] alignMatrix = IO.loadMatrixFromFile ( parameter.alignmFile );
						// in case of previewing, apply down sample factor to translations
						if ( name.contains("preview") && null != alignMatrix ) {
							alignMatrix[0][2] *= downsample_factor;
							alignMatrix[1][2] *= downsample_factor;
						}
						// failed to load align matrix from file, 
						if (null == alignMatrix) {
							// restore loading message as file path	
							parameter.alignmFile = Parameter.loadAlignMessage;
						// compute new SIFT align matrix
							alignMatrix = SIFT.trySIFTalignment ( impOutputs[0], impOutputs[1], parameter.displayResult );
						}
						// check if a valid alignment matrix 2D present (loaded or computed) at this point
						if ( null != alignMatrix ) {
							// update align matrix to parameter in active image deskew, for saving to file purposes
							if ( !name.contains("preview") && parameter.displayResult ) parameter.alignMatrix = alignMatrix;
						// align the whole stack with align matrix
							SIFT.alignStackSIFT2 ( impOutputs[1], alignMatrix, true );
						}
					}
					// if load or computation failed, it's keep as the flip case
				} // SIFT align ends here, if SIFT failed, it's simply flip right side over
				// combine transformed right side image as 2nd channel, to formed a 2-channel hyperstack
				impOutputs[0] = Partition.combineChannel ( impOutputs );
				impOutputs[0].setTitle( name + "-deskewed" );
				/* The right half is now the second channel of impOutputs[0]. Clear the slot as
				 * well as closing it: prepareResults walks the whole array, and a closed
				 * ImagePlus still reports its old stack size while its pixels are gone, so
				 * it was writing a junk "-[right]" file and failing on every save. */
				if ( null != impOutputs[1] ) { impOutputs[1].close(); impOutputs[1] = null; }
			}	// R-flip or R-align case end:
		}		// 2nd image case end. ( 1: R,	2: R-flip,	3: R-align )
		
		System.out.printf("    after treat channel image : memory used: %d MB%n", ( IJ.currentMemory() ) / (1024*1024) );
		
		// TODO: check this; // displayResult, 
		boolean success = prepareResults ( impOutputs, parameter );
		
		System.out.printf("    after prepareResults : memory used: %d MB%n", ( IJ.currentMemory() ) / (1024*1024) );
		/*
		// display result of requested channel(s) and make projection image(s)
		for (ImagePlus impOutput : impOutputs) {
			if (null == impOutput) continue;
			Utils.calibrateResult ( impOutput, impInput.getCalibration(), "deskew" );
			if (parameter.displayResult) Utils.displayImage( impOutput ); //TODO: check display function
			// create projection image(s)
	        if ( parameter.doProjection && !impOutput.getStack().isVirtual() ) {
	    		// create projection images
	    		for (String axis : parameter.projAxes) {
	    			for (String type : parameter.projTypes) {
	    				String projectImageName = Utils.getName(impOutput) + "-" + type + axis + "projection";
	    				ImagePlus imp_project = Projection.projection (impOutput, axis, type, parameter.tryGPU);
	    				if (parameter.displayResult)
	    					Utils.displayImage( imp_project, projectImageName );
	    				else
	    					imp_project.setTitle( projectImageName );
	    				
	    			}// create projection image for current type ends
	    		}	 // create projection image for current axis ends
			}		 // create projection image for current image ends
		}			 // create projection image for each output image in array ends
		*/
		Utils.collectGarbage();
		// report runtime
		float duration = System.currentTimeMillis() - start;
		System.out.printf("\n\tdeskew finished after %.3f seconds.\n", duration / 1000);
		return success;
	}
	public void process (
			Parameter parameter
			) {
		String name = Utils.getName(parameter.impInput);
		process ( name, parameter, true, false );
	}
	
	
	
	public static boolean processFile (String path, Parameter parameter) {
		ImagePlus imp = VolumeIO.open(path);
		if (null == imp) {
			/* Unreadable or half-written file. This is the single entry point for batch,
			 * folder watching and TCP-IP, so an unguarded null here used to take the whole
			 * unattended run down with a NullPointerException in getName. Skip the file and
			 * let the run carry on. */
			IJ.log ( "OPM: could not open image, skipping: " + path );
			return false;
		}
		parameter.impInput = imp;
		String name = Utils.getName ( imp );
		parameter.doInverse = false;
		parameter.displayResult = false;
		parameter.updateDeskewMatrix();
		// in case input files are recursively loaded, recreate input directory structures
		String saveDir = parameter.saveDir;
		if (parameter.recursive) {
			String imageDir = new File(path).getParentFile().getAbsolutePath();
			parameter.saveDir = imageDir.replace(parameter.inputDir, parameter.saveDir);
		}
		try {
			// batch processing in silent mode: do not keep input, and first try loaded settings
			return process ( name, parameter, false, true );
		} finally {
			parameter.saveDir = saveDir;
		}
	}
	
	
	/**			deskew a image as ImagePlus
	 *  <br>	could be an active image in ImageJ
	 *  <br>	or from opening an image file on disk
	 * 
	 * @param impInput			: input image, keep it untouched throughout the processing
	 * @param parameter			: input parameter, stores 
	 * <p>
	 * @return imp_deskewed		: deskewed image, need to fix for imglib2 Virtual stack problem
	 */
	public static ImagePlus deskew_image (	//TODO: adding channel-handling, projection and time-lapse options
			ImagePlus impInput,
			double[][] deskew_matrix,	// TODO: maybe update the translate Z option
			boolean tryGPU
			) {
		if (null == impInput) return null;
		
		// prepare log
		// timing the start
		
		// get input image, crop if there is active ROI 
		ImagePlus imp = impInput;
		if ( null != imp.getRoi() ) imp = new Duplicator().run( imp );	// no ROI, no copy
		
		String name = Utils.getName(impInput);
		// prepare input image, assign Z from T if stack formed in T
		int[] dims = imp.getDimensions(true); // XYCZT
		VolumeIO.normalize(imp);	// planes may arrive on the T axis; put them back on Z
		

		
		//	deskew of the input image
		long deskew_start = System.currentTimeMillis();
		ImagePlus imp_deskew = null;
		boolean GPUfailed = false;
		if (tryGPU)
			imp_deskew = GPU.transform ( imp, deskew_matrix );
		if (null == imp_deskew) {
			imp_deskew = CPU.transform ( imp, deskew_matrix );
			GPUfailed = true;
		}
		Utils.collectGarbage();
		// TODO: implement deskew of hyperstack

		float deskew_duration = System.currentTimeMillis() - deskew_start;
		String processor = GPUfailed ? "CPU" : "GPU";
		float deskew_speed = (dims[0] * dims[1] * dims[3]) / deskew_duration / 1000;
		System.out.printf("\n\tdeskew on %s with ~ %.1fk pixels per ms.\n", processor, deskew_speed);
		
		
		// prepare the deskewed image
		imp_deskew.setTitle(name + "-deskewed");
		imp_deskew.changes = false;

		return imp_deskew;
	}
	public static ImagePlus deskew_image (
			ImagePlus imp,
			double[][] deskew_matrix
			) {
		return deskew_image ( imp, deskew_matrix, true );
	}
	public static ImagePlus deskew_image (
			ImagePlus imp,
			Parameter parameter	// only need xy pixel size, z step size, opm angle, if inverse here
			) {
		double dzsetp = parameter.zStepSize;
		double dxy = parameter.xyPixelSize;			// α
		double[][] deskew_matrix = Transform.deskew (dzsetp, dxy, parameter.opmAngle, imp.getHeight() );
		if (parameter.doInverse) deskew_matrix = Transform.inverse(deskew_matrix);
		parameter.deskewMatrix = deskew_matrix;
		return deskew_image ( imp, parameter.deskewMatrix, parameter.tryGPU );	
	}
	
	
	/**		Calibrate, then display or save each deskewed volume and its projections
	 * <br>		The one place where a result leaves the processing path: it applies the isotropic
	 * <br>		calibration, either shows the volume or writes it to disk, makes the requested
	 * <br>		projections, and appends them to a time-lapse when one is being collected.
	 *
	 * @param imp_results	: deskewed volumes; null entries are skipped
	 * @param parameter		: holds the output folder, the projection choices and displayResult
	 */
	public static boolean prepareResults ( ImagePlus[] imp_results, Parameter parameter ) {
		boolean success = true;
		boolean overwrite = parameter.fileExistStr.equals("overwrite");
		Calibration cal = new Calibration();
		cal.pixelWidth = cal.pixelHeight = cal.pixelDepth = (parameter.xyPixelSize / 1000d);
		cal.setUnit ( "micron" );
		cal.frameInterval = parameter.frameInterval;
		cal.setTimeUnit("second");
		
		System.out.printf("    in prepareResults begin : memory used: %d MB%n", ( IJ.currentMemory() ) / (1024*1024) );
		
		for ( ImagePlus imp_result : imp_results ) {
			if ( null == imp_result ) continue;
			String name = Utils.getName ( imp_result );
			imp_result.setCalibration ( cal );
			if ( parameter.displayResult ) {
				Utils.displayImage( imp_result );
			} else if (parameter.saveDeskewImage && parameter.savesTiff()) {	// save result if not display
				
				String saveDir = parameter.saveDir;
				if ( parameter.saveSeparate )	saveDir += File.separator + "deskew";
				boolean folderReady = true;
				try {
					Files.createDirectories(Paths.get(saveDir));
				} catch ( Exception e) {
					IJ.log("OPM: could not create deskew result folder " + saveDir + ": " + e.getMessage());
					success = false;
					folderReady = false;
				}
				String savePath = VolumeIO.tiffPath ( saveDir + File.separator + name );
				if ( folderReady && (overwrite || !VolumeIO.isCompleteTiff(new File(savePath))) ) {
					if (!VolumeIO.saveTiff(imp_result, savePath)) {
						IJ.log("OPM: failed to save deskew TIFF: " + savePath);
						success = false;
					}
				}
			} else {
				// neither display, nor save the deskew image
				System.out.println("deskewed image will be neither displayed, nor saved...");
			}
			
			System.out.printf("    after display/save imp_result : memory used: %d MB%n", ( IJ.currentMemory() ) / (1024*1024) );
			
			// create projection image(s)
			if ( parameter.doProjection && !imp_result.getStack().isVirtual() ) {
				// create projection images
				for ( String axis : parameter.projAxes ) {
					for ( String type : parameter.projTypes ) {
						String projectImageName = name + "-" + type + axis + "projection";
						ImagePlus imp_project = null;
						try {
							imp_project = Projection.projection (imp_result, axis, type, parameter.tryGPU);
							if (imp_project == null) throw new IllegalStateException("projection returned no image");
							imp_project.setCalibration ( cal );
							if ( parameter.displayResult ) {
								Utils.displayImage( imp_project, projectImageName );
							} else { // save result if not display
								imp_project.setTitle( projectImageName );
								imp_project.getImageStack().setSliceLabel( projectImageName, 1 );
								// save projection images to disk
								String saveDir = parameter.saveDir;
								if ( parameter.saveSeparate ) saveDir += File.separator + type + axis;
								boolean projectionFolderReady = true;
								try {
									Files.createDirectories(Paths.get(saveDir));
								} catch ( Exception e) {
									IJ.log("OPM: could not create projection result folder " + saveDir + ": " + e.getMessage());
									success = false;
									projectionFolderReady = false;
								}
								String savePath = VolumeIO.tiffPath ( saveDir + File.separator + projectImageName );
								if ( projectionFolderReady && (overwrite || !VolumeIO.isCompleteTiff(new File(savePath))) ) {
									if (!VolumeIO.saveTiff(imp_project, savePath)) {
										IJ.log("OPM: failed to save projection TIFF: " + savePath);
										success = false;
									}
								}
							}

							System.out.printf("    after show/save projection : memory used: %d MB%n", ( IJ.currentMemory() ) / (1024*1024) );

							// create time lapse if requested
							if ( parameter.makeTimeLapse ) {
								String name_timeLapse = "-" + type + axis + "projection"; //"projection-timeLapse";
								ImagePlus imp_timeLapse = WindowManager.getImage ( name_timeLapse );
								Partition.combineTimelapse ( imp_timeLapse, imp_project, name_timeLapse );
							}

							System.out.printf("    after show/save timeLapse : memory used: %d MB%n", ( IJ.currentMemory() ) / (1024*1024) );
						} catch (Throwable failure) {
							success = false;
							IJ.log("OPM: failed to prepare " + projectImageName + ": " + failure.getMessage());
						} finally {
							if ( !parameter.displayResult && imp_project != null ) imp_project.close();
						}
					}// create projection image for current type ends
				} // create projection image for current axis ends
			}		 // create projection image for current image ends
	        
	        System.out.printf("    after projection : memory used: %d MB%n", ( IJ.currentMemory() ) / (1024*1024) );
	        
	        
	        if ( !parameter.displayResult ) imp_result.close();
		}			 // create projection image for each output image in array ends
		return success;
	}
	
	
	
	
}
