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
public class Deskew implements ExtendedPlugInFilter, DialogListener {
	private Parameter parameter = null;
	//private Log log;
	private GenericDialog dialog;
	
	
	private ImagePlus imp = WindowManager.getCurrentImage();
	private Roi roi = null;
    private ImagePlus imp_downsample = null;
    private static double downsample_factor = 1.0;
    //private ImagePlus imp_deskew;
	
    
	static String parameterFile = Parameter.loadSettingMessage;
	
    private boolean impChanged = false;
    private boolean fileChanged = false;
    /* more class variables */
    //private boolean previewing;
    private boolean updatingDialog;
    //private int nPasses = 1;
    //private int pass;
    private int flags = DOES_8G|DOES_16|DOES_32|DOES_STACKS|STACK_REQUIRED|FINAL_PROCESSING;
    //private boolean calledAsPlugin;
    
    /*
    private int setupCall = 0;
    private int showDialogCall = 0;
    private int itemChangeCall = 0;
    private int setNPassCall = 0;
    private int runCall = 0;
    private int updateMatrixFileFieldCall = 0;
    private int cleanUpCall = 0;
    */
    private String uniqueID = null;
    
	@Override
	public int setup(String arg, ImagePlus imp) {
		//IJ.log("setup call: " + setupCall++);
		// TODO: potentially set up number of slices to be passed onto run(ip), that fit into GPU memory
		
		// TODO Auto-generated method stub
		if ( Utils.checkPluginWindowExist( "Deskew Image" ) ) return DONE;
		
		if (null == imp) return DONE;
		
		if (arg.equals("final")) {
            //imp.getProcessor().resetMinAndMax();
			 // setup final processing
			run(imp.getProcessor());
			return DONE;
        } else {
        	// supposed to be preview run
        	if ( null == uniqueID )
        		uniqueID = UUID.randomUUID().toString().substring(0, 8);
            return flags;
        }
	}
	
	@Override
	public int showDialog(ImagePlus imp, String command, PlugInFilterRunner pfr) {
		//IJ.log("showDialog call: " + showDialogCall++);
		parameter = new Parameter("image");
		imp.unlock(); //TODO: for unkonwn reason, imp locked at this point
		parameter.impInput = imp;
		parameter.tryGPU = true;
		parameter.autoPartition = true;
		parameter.displayResult = true;
		parameter.axisPartition = "X"; parameter.axisCombine = "X";
				//calledAsPlugin = true;
				//nSlices = imp.getNSlices();
				//setNPasses(1);
		dialog = parameter.deskew_image ("Deskew Image", pfr);
		dialog.addDialogListener(this);
        //previewing = true;
        updatingDialog = false;
		//gd.addHelp(IJ.URL2+"/docs/menus/process.html#background");
        dialog.showDialog();
        //previewing = false;
        if (dialog.wasOKed()) {		// proceed to deskew
        	//previewing = false;
        	setup ("final", imp);
        	cleanUp ();
            parameter.storeParam();
        }
        if (dialog.wasCanceled()) {	// exit dialog
        	cleanUp ();
        	//parameter.storeParam();
        	return DONE;
        }
        //IJ.register(this.getClass());       //protect static class variables (filter parameters) from garbage collection    
        //return IJ.setupDialog(imp, flags);
        return DONE;  //ask whether to process all slices of stack (if a stack)
	}
	
	@Override
	public boolean dialogItemChanged(GenericDialog gd, AWTEvent e) {
		//IJ.log("dialogItemChanged call: " + itemChangeCall++);
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
        //parameter.minProj = 		gd.getNextBoolean();
        //parameter.sumProj = 		gd.getNextBoolean();
        //parameter.medProj = 		gd.getNextBoolean();
        //parameter.stdProj = 		gd.getNextBoolean();
        // get previewing state
        //previewing = ((Checkbox) gd.getPreviewCheckbox()).getState();
       
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
	        // check whether parameter file changed
	        if ( file_now.equals( parameterFile ) ) {
	        	fileChanged = false;
	        } else {
	        	fileChanged = true;
	        	parameterFile = file_now;
	        }
	        // update projection parameters, calibrate image, update image window
	        imp.setCalibration ( Utils.createCalibration(parameter) );
	    	imp.updateAndRepaintWindow();
        }
        parameter.parseProjectionParameter();
    	//if (!previewing) imp.unlock();
        parameter.storeParam();
        return true;
	}
	
	@Override
	public void setNPasses(int nPasses) {
		//IJ.log("setNPasses call: " + setNPassCall++);
		//this.nPasses = nPasses;
		//pass = 0;
	}

	@Override
	public void run( ImageProcessor ip ) { // this imageprocessor may be unused
		//IJ.log("run call: " + runCall++);
		// if previewing, do deskew on downsampled image, and do projections
		// if not previewing
		
		
		
		if (dialog.isPreviewActive()) {
			//Parameter param = new Parameter("image");
			if (null == imp_downsample || impChanged) {
				//imp.setCalibration ( Utils.createCalibration(parameter) );
				imp_downsample = Utils.downSample ( imp );
				downsample_factor = (double)imp_downsample.getWidth() / (double)imp.getWidth();
			}
			//double factor = (double) imp_downsample.getWidth() / (double) imp.getWidth();
			impChanged = false;
			if (imp_downsample != imp) { // in case input image is small enough downsample is input
				parameter.impInput = imp_downsample;
				imp_downsample.setTitle ( "preview" );
				imp_downsample.hide();
			}
			
			boolean loadMatrixFromFile = false;
			if (fileChanged) loadMatrixFromFile = updateMatrixFileField();
			fileChanged = false;
			
			if ( !loadMatrixFromFile ) { //TODO: maybe unnecessary
				parameter.deskewMatrix = Transform.deskew (
						parameter.zStepSize, parameter.xyPixelSize,
						parameter.opmAngle,
						imp_downsample.getHeight());
			}
			
			//String name = uniqueID + "-preview";
			process ( parameter );
				
		} else {
			// final processing run();
			// close all preview windows
			//IJ.log("not previewing");
			cleanUp ();	// maybe unnecessary
			parameter.impInput = imp;
			// prepare log
			// timing the start
			//long start = System.currentTimeMillis();
			process ( parameter );
			
			saveSettings ( parameter );
			
			//IJ.run("Collect Garbage", "");
			// report runtime
			//float duration = System.currentTimeMillis() - start;
			//log.add("\n\tdeskew finished after %.3f seconds.\n", duration / 1000);
			//log.add("deskew image finish.");
			//log.close();
		}
	}

	
	/**
	 * 
	 * @return
	 */
	public boolean updateMatrixFileField () {
		//IJ.log("updateMatrixFileField call: " + updateMatrixFileFieldCall++);
		// file not change, do nothing
		//System.out.println("debug: fileChanged: " + fileChanged);
		if ( !fileChanged ) return false;
		// if matrix file field is not changed, do nothing
		//System.out.println("debug: parameterFile: " + parameterFile);
		
		// TODO: in the case field is default message: when to set it back to default?
		// if matrix file field is default value, do nothing
		if ( parameterFile.equals(Parameter.loadSettingMessage) ) return false;
		
		// check the setup file type, if unknown, do nothing
		String fileType = parameter.getSetupFileType( parameterFile );
		//System.out.println("debug: fileType: " + fileType);
		if (null == fileType) {
			parameterFile = Parameter.loadSettingMessage;
			( (TextField) dialog.getStringFields().elementAt(0) )
			.setText( Parameter.loadSettingMessage );
			return false;		// TODO:need to set to default
		}
		
		// get down sampled image
		if (null == imp_downsample) return false; // imp_downsample = Utils.downSample( imp );
		// load setup file, and update parameter(s)
		switch (fileType) {
		case "expParams":
			// try to load input parameter from file: xy pixel size, z step, angle
			double[] expParams = IO.loadExperimentalParametersFromFile ( parameterFile );
			if (null == expParams) {
				parameterFile = Parameter.loadSettingMessage;
				( (TextField) dialog.getStringFields().elementAt(0) )
				.setText( Parameter.loadSettingMessage );
				return false; // TODO:need to set to default
			}
			parameter.xyPixelSize	= expParams[0];
			parameter.zStepSize		= expParams[1];
			parameter.opmAngle		= expParams[2];
			//parameter.frameInterval = expParams[3];
			parameter.deskewMatrix = Transform.deskew ( 
					parameter.zStepSize, parameter.xyPixelSize, parameter.opmAngle, (double)imp_downsample.getHeight() );
			break;
		case "matrix":
			// try to load matrix from file
			double[][] deskew_matrix = IO.loadMatrixFromFile( parameterFile );
			if (null == deskew_matrix) {
				parameterFile = Parameter.loadSettingMessage;
				( (TextField) dialog.getStringFields().elementAt(0) )
				.setText( Parameter.loadSettingMessage );
				return false; // TODO:need to set to default
			}
			// try to calculate input parameter: z step, angle, and z-translate amount
			double[] matrix_inputParam = Transform.reverse_deskew ( deskew_matrix, (double)imp_downsample.getHeight() );
			if (null == matrix_inputParam) return false; // TODO:need to set to default
			parameter.zStepSize = matrix_inputParam[0] * parameter.xyPixelSize;
			parameter.opmAngle = matrix_inputParam[1];
			deskew_matrix[2][3] = matrix_inputParam[2];
			parameter.deskewMatrix = deskew_matrix;
			break;
		case "transform":
			// try to load set of transformation from file
			Map<String, Object> transformation = IO.loadTransformationFromFile ( parameterFile );
			if (null == transformation) {
				parameterFile = Parameter.loadSettingMessage;
				( (TextField) dialog.getStringFields().elementAt(0) )
				.setText( Parameter.loadSettingMessage );
				return false; // TODO:need to set to default
			}
			// TODO: compute transformation matrix from the transformations
			// TODO: update input parameters: z step size, angle, and parameter.matrix
			break;
		}

		// update parameter dialog
		updatingDialog = true;
		// disable previewing when updating the dialog GUI
		boolean previewRunning = dialog.isPreviewActive();
		if (previewRunning) {
			//System.out.println("354debug: previewRunning: " + previewRunning);
			dialog.previewRunning(false);
			dialog.getPreviewCheckbox().setState(false);
		}
		//System.out.println("358debug: previewRunning: " + previewRunning);
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
	
	
	
	/**			Close all preview image(s) and free up memory
	 * 
	 */
	public void cleanUp () {
		//IJ.log("cleanUp call: " + cleanUpCall++);
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
	 * 
	 * @param name
	 * @param parameter
	 * @param keepInput
	 */
	public static void process (
			String name,
			Parameter parameter,
			boolean keepInput,
			boolean loadFromFileFirst
			) {	//TODO: check if need a return statement
		// TODO: combine time lapse or not?
		// prepare log
		//log = new Log("OPM_deskew.log");
		//log.add(parameter);
		//log.add("deskew image start:");
		// timing the start
		long start = System.currentTimeMillis();
		
		//System.out.printf("    process begin: memory used: %d MB%n", ( IJ.currentMemory() ) / (1024*1024) );
		
		ImagePlus impInput = parameter.impInput;	// check here if a copy created
		if (null == impInput) return;
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
		
		//System.out.printf("    after impInput separate LR: memory used: %d MB%n", ( IJ.currentMemory() ) / (1024*1024) );
		
		
		ImagePlus[] impOutputs = new ImagePlus[2];
		
		//System.out.printf("    before deskew_image : memory used: %d MB%n", ( IJ.currentMemory() ) / (1024*1024) );
		
		// deskew 1st image in array with input matrix:		
		if ( loadFromFileFirst && null != parameter.deskewMatrix )	//
			impOutputs[0] = deskew_image ( impInputs[0], parameter.deskewMatrix, parameter.tryGPU );
		else
			impOutputs[0] = deskew_image ( impInputs[0], parameter );
		impOutputs[0].getImageStack().setSliceLabel( name + "-[left]", 1 );
		// close 1st input image, save RAM
		if ( !parameter.channelStr.equals("whole image") || !keepInput ) impInputs[0] = null; //impInputs[0].close(); 		
		
		
		//System.out.printf("    after deskew_image : memory used: %d MB%n", ( IJ.currentMemory() ) / (1024*1024) );
		
		
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
							//Transform.alignStackSIFT ( impOutputs[1], alignMatrix );
							SIFT.alignStackSIFT2 ( impOutputs[1], alignMatrix, true );
						}
					}
					//impOutputs[1].duplicate().show();
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
		prepareResults ( impOutputs, parameter );
		
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
		//System.gc();
		// report runtime
		float duration = System.currentTimeMillis() - start;
		System.out.printf("\n\tdeskew finished after %.3f seconds.\n", duration / 1000);
		//log.add("deskew image finish.");
		//log.close();
	}
	public void process (
			Parameter parameter
			) {
		String name = Utils.getName(parameter.impInput);
		process ( name, parameter, true, false );
	}
	
	
	
	public static void processFile (String path, Parameter parameter) {
		ImagePlus imp = VolumeIO.open(path);
		if (null == imp) {
			/* Unreadable or half-written file. This is the single entry point for batch,
			 * folder watching and TCP-IP, so an unguarded null here used to take the whole
			 * unattended run down with a NullPointerException in getName. Skip the file and
			 * let the run carry on. */
			IJ.log ( "OPM: could not open image, skipping: " + path );
			return;
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
		// batch processing in silent mode: donot keep input, and first try to load settings from file
		process ( name, parameter, false, true ); 
		parameter.saveDir = saveDir;
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
		//Log log = Log.getInstance();
		// timing the start
		//long start = System.currentTimeMillis();
		
		// get input image, crop if there is active ROI 
		ImagePlus imp = impInput;
		//Recorder.disableCommandRecording();
		if ( null != imp.getRoi() ) imp = new Duplicator().run( imp );	// no ROI, no copy
		
		String name = Utils.getName(impInput);
		// prepare input image, assign Z from T if stack formed in T
		int[] dims = imp.getDimensions(true); // XYCZT
		VolumeIO.normalize(imp);	// planes may arrive on the T axis; put them back on Z
		
		//log.add("\n\tdeskew data: %s\n", name);
		//log.add("\tinput data (%d-bit) dimension:\n\t%d * %d * %d pixels", imp.getBitDepth(), dims[0], dims[1], dims[3]);
		//log.add(" = %.1f MB.\n", imp.getSizeInBytes()/1024/1024);

		
		//	deskew of the input image
		long deskew_start = System.currentTimeMillis();
		//log.add("\n\tdeskew angle: %.1f°, Z-step size: %.1f nm, pixel size: %.1f nm.\n", parameter.opmAngle, parameter.zStepSize, parameter.xyPixelSize);
		ImagePlus imp_deskew = null;
		boolean GPUfailed = false;
		//System.out.printf("    before GPU.transform: memory used: %d MB%n%n", ( IJ.currentMemory() ) / (1024*1024) );
		if (tryGPU)
			imp_deskew = GPU.transform ( imp, deskew_matrix );
		if (null == imp_deskew) {
			imp_deskew = CPU.transform ( imp, deskew_matrix );
			GPUfailed = true;
		}
		Utils.collectGarbage();
		//System.out.printf("    after GPU.transform: memory used: %d MB%n%n", ( IJ.currentMemory() ) / (1024*1024) );
		// TODO: implement deskew of hyperstack
		//if (dims[2] * dims[4] > 1) {
		//	transformedImage = HyperStackConverter.toHyperStack(transformedImage, dims_out[2], dims_out[3], dims_out[4], "Composite");
		//}

		float deskew_duration = System.currentTimeMillis() - deskew_start;
		String processor = GPUfailed ? "CPU" : "GPU";
		float deskew_speed = (dims[0] * dims[1] * dims[3]) / deskew_duration / 1000;
		System.out.printf("\n\tdeskew on %s with ~ %.1fk pixels per ms.\n", processor, deskew_speed);
		
		//if ( null != imp.getRoi() ) //???
		//	imp.close();
		//IJ.run("Collect Garbage", "");
		
		// prepare the deskewed image
		//Utils.calibrateResult ( imp_deskew, impInput.getCalibration(), "deskew" );
		imp_deskew.setTitle(name + "-deskewed");
		imp_deskew.changes = false;

		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\tdeskew image finished after %.3f seconds.\n", duration / 1000);
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
		//parameter.tryGPU = true; parameter.autoPartition = true;
		//parameter.axis_partition = "X"; parameter.axis_combine = "X";
		return deskew_image ( imp, parameter.deskewMatrix, parameter.tryGPU );	
	}
	
	
	/**
	 * 
	 * @param imp_results
	 * @param parameter
	 */
	public static void prepareResults ( ImagePlus[] imp_results, Parameter parameter ) {
		//ImagePlus impInput = parameter.impInput;
		boolean overwrite = parameter.fileExistStr.equals("overwrite");
		Calibration cal = new Calibration();
		//if ( null != parameter.impInput ) 
		//	cal = parameter.impInput.getCalibration();
		//else 
		cal.pixelWidth = cal.pixelHeight = cal.pixelDepth = (parameter.xyPixelSize / 1000d);
		cal.setUnit ( "micron" );
		cal.frameInterval = parameter.frameInterval;
		cal.setTimeUnit("second");
		
		System.out.printf("    in prepareResults begin : memory used: %d MB%n", ( IJ.currentMemory() ) / (1024*1024) );
		
		for ( ImagePlus imp_result : imp_results ) {
			if ( null == imp_result ) continue;
			String name = Utils.getName ( imp_result );
			//Utils.calibrateResult ( imp_result, cal, "deskew" );
			imp_result.setCalibration ( cal );
			if ( parameter.displayResult ) {
				Utils.displayImage( imp_result );
			} else if (parameter.saveDeskewImage) {	// save result if not display
				
				String saveDir = parameter.saveDir;
				if ( parameter.saveSeparate )	saveDir += File.separator + "deskew";
				try {
					Files.createDirectories(Paths.get(saveDir));
				} catch ( Exception e) {
					System.out.println(e.getMessage());
					//log.add(e.getMessage());
					continue;
				}
				String savePath = VolumeIO.tiffPath ( saveDir + File.separator + name );
				if ( !new File(savePath ).exists() || overwrite)
					VolumeIO.saveTiff(imp_result, savePath);
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
	    				ImagePlus imp_project = Projection.projection (imp_result, axis, type, parameter.tryGPU);
	    				//Utils.calibrateResult ( imp_project, imp_result, "projection_"+axis );
	    				imp_project.setCalibration ( cal );
	    				if ( parameter.displayResult ) {
	    					Utils.displayImage( imp_project, projectImageName );
	    				} else { // save result if not display
	    					imp_project.setTitle( projectImageName );
	    					imp_project.getImageStack().setSliceLabel( projectImageName, 1 );
	        				// save projection images to disk
	        				String saveDir = parameter.saveDir;
	        				if ( parameter.saveSeparate )	saveDir += File.separator + type + axis;
	        				try {
	        					Files.createDirectories(Paths.get(saveDir));
	        				} catch ( Exception e) {
	        					System.out.println(e.getMessage());
	        					//log.add(e.getMessage());
	        					continue;
	        				}
	        				String savePath = VolumeIO.tiffPath ( saveDir + File.separator + projectImageName );
	        				if ( !new File(savePath ).exists() ||  overwrite)
	        					VolumeIO.saveTiff(imp_project, savePath);
	    				}
	    				
	    				System.out.printf("    after show/save projection : memory used: %d MB%n", ( IJ.currentMemory() ) / (1024*1024) );
	    				
	    				// create time lapse if requested
        				if ( parameter.makeTimeLapse ) {
        					String name_timeLapse = "-" + type + axis + "projection"; //"projection-timeLapse";
        					//name_timeLapse = Utils.getNameTimeLapse ( name + name_timeLapse );
        					ImagePlus imp_timeLapse = WindowManager.getImage ( name_timeLapse );
        					Partition.combineTimelapse ( imp_timeLapse, imp_project, name_timeLapse );
        					//imp_timeLapse.setCalibration ( cal );
        				}
        				
        				System.out.printf("    after show/save timeLapse : memory used: %d MB%n", ( IJ.currentMemory() ) / (1024*1024) );
        				
        				
        				if ( !parameter.displayResult ) imp_project.close();
	    			}// create projection image for current type ends
	    		}	 // create projection image for current axis ends
			}		 // create projection image for current image ends
	        
	        System.out.printf("    after projection : memory used: %d MB%n", ( IJ.currentMemory() ) / (1024*1024) );
	        
	        
	        if ( !parameter.displayResult ) imp_result.close();
		}			 // create projection image for each output image in array ends
		
	}
	
	
	
	
}
