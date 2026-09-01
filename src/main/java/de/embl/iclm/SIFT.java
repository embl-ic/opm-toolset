package de.embl.iclm;


import java.awt.Component;
import java.awt.AWTEvent;
import java.awt.Checkbox;
//import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Scrollbar;
import java.awt.TextField;
//import java.io.File;
import java.text.DecimalFormat;
//import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Vector;

//import fiji.stacks.Hyperstack_rearranger;
//import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.gui.Overlay;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.io.SaveDialog;
import ij.plugin.ChannelSplitter;
import ij.plugin.ContrastEnhancer;
import ij.plugin.Duplicator;
//import ij.plugin.HyperStackConverter;
//import ij.plugin.PlugIn;
import ij.plugin.RGBStackMerge;
import ij.plugin.RoiScaler;
import ij.plugin.filter.ExtendedPlugInFilter;
import ij.plugin.filter.PlugInFilterRunner;
import ij.process.FloatPolygon;
import ij.process.ImageProcessor;




// import mpicbg.ij.SIFT; conflict with de.embl.iclm.SIFT class name, use explicit import in code
import mpicbg.ij.util.Util;
import mpicbg.ij.FeatureTransform;
import mpicbg.ij.InverseTransformMapping;
//import mpicbg.ij.Mapping;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;

import mpicbg.models.AbstractAffineModel2D;
//import mpicbg.models.AbstractModel;
import mpicbg.models.AffineModel2D;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;





/**
 * Mostly copied from:
 * 
 * https://imagej.net/plugins/feature-extraction
 * 
 * 
 * 
 * 
 * 
 * 
 * ImageJ plugins that process an image may implement this interface. In addition to the features of PlugInFilter, it is better suited for filters that have a dialog asking for the options or filter parameters. It also offers support for preview, for a smooth progress bar when processing stacks and for calling back the PlugInFilterRunner (needed, e.g., to get the slice number when processing a stack in parallel threads).
The sequence of calls to an ExtendedPlugInFilter is the following:

- setup(arg, imp): The filter should return its flags.

- showDialog(imp, command, pfr): The filter should display the dialog asking for parameters (if any) and do all operations needed to prepare for processing the individual image(s) (E.g., slices of a stack). For preview, a separate thread may call setNPasses(nPasses) and run(ip) while the dialog is displayed. The filter should return its flags.

- setNPasses(nPasses): Informs the filter of the number of calls of run(ip) that will follow.

- run(ip): Processing of the image(s). With the CONVERT_TO_FLOAT flag, this method will be called for each color channel of an RGB image. With DOES_STACKS, it will be called for each slice of a stack.

- setup("final", imp): called only if flag FINAL_PROCESSING has been specified.

Flag DONE stops this sequence of calls.



 * 
 * @author ziqiang.huang@embl.de
 *
 */


public class SIFT implements ExtendedPlugInFilter, DialogListener {

	//private Log log;
	//private final String[] colorString = {"Red", "Green", "Blue", "Cyan", "Magenta", "Yellow", "Grays"};
	final static private DecimalFormat decimalFormat = new DecimalFormat();
	//final static private DecimalFormatSymbols decimalFormatSymbols = new DecimalFormatSymbols();
	
	//private Parameter parameter = null;
	public Param siftpar = null;
	//private Log log;
	private GenericDialog dialog;
	//private ImagePlus imp = null;
	//private Roi roi = null;

    //private ImagePlus imp_deskew;
	//private ImagePlus imp_preview = null;
    
	
    private boolean image_updated = false;
    private boolean feature_param_updated = false;
    private boolean feature_filter_updated = false;
    //private boolean feature_updated = false;
    private boolean align_image_updated = false;
    private boolean compute_align_matrix = false;
    
    private ImagePlus imp;			// input image, could be stack; in case of stack, take current slice
    private Roi roi;				// active ROI, or null if no ROI
    private int slice;
    private ImageProcessor[] ip_LR;	// separated image processor of the left and right (flipped) channel
    private mpicbg.ij.SIFT ijSIFT;	// MPI-CBG SIFT object
    private List< PointMatch > candidate;	// SIFT candidate points, filtered or not filtered
    private ImagePlus imp_check;	// preview alignment check image
    //private AbstractAffineModel2D<?> model;	// transform model computed from the matched candidate points, restrict to be rigid 2D
    

    //private boolean fileChanged = false;
    /* more class variables */
    //private boolean previewing;
    //private boolean dialog_updating = false;
    
    //private int nPasses = 1;
    //private int pass;                        // Current pass
    private int flags = DOES_8G|DOES_16|DOES_32;	//|FINAL_PROCESSING;

    private String uniqueID = null;
    
    
    // private int setupCall = 0;
    // private int showDialogCall = 0;
    // private int itemChangeCall = 0;
    // private int setNPassCall = 0;
    // private int runCall = 0;
    // private int updateMatrixFileFieldCall = 0;
    // private int cleanUpCall = 0;
    
    
    // class to handle mpicbg.ij.SIFT parameters
    class Param {
    	
    	public Parameter parameter = new Parameter("sift");
    	//private Log log;
    	//private GenericDialog dialog;
    	
    	
    	// input image related
    	//public ImagePlus impInput = null;	// image to work on
    	
    	public String imageName = "";
    	public int size = 1024;
    	public Roi roi = null;
    	//public ImagePlus imp_preview = null;
    	
		final public FloatArray2DSIFT.Param sift = new FloatArray2DSIFT.Param();
		/*
		public double initialSigma  = 1.6;		// initial gaussian blur
		public int steps = 3;					// steps per scale octave
		public int minOctaveSize = size / 8;	// minimum image size
		public int maxOctaveSize = size;		// maximum image size

		public int fdSize = 4;					// feature descriptor size
		public int fdBins = 8;					// feature descriptor orientation bins
		*/
		
		public float rod =  0.92f;				// closest/next closest neighbour distance ratio
		//public boolean useGeometricConsensusFilter = true;	// Geometric Consensus Filter with RANSAC
		public float maxEpsilon = 25;			// maximal allowed alignment error in px, suggested to be 10% of image size
		public float minInlierRatio = 0.05f;	// inlier/candidates ratio
		public int minInlierNum = 7;			// minimal absolute number of inliers

		public boolean show_sift_points = false;
		
		public boolean load_alignMatrix = false;
		public String alignmFile = Parameter.loadAlignMessage;
		public boolean interpolate = true;		// interpolation image when transform and align
		
		//public int preview_index = 0;			// preview options

		public double[][] alignMatrix	= null;
		
		// update parameter values with selected image
		public void setImage (ImagePlus imp) {
			parameter.impInput = imp;			// update image
			imageName = Utils.getName( imp );	// udpate image name
			roi = imp.getRoi();					// reset ROI
			slice = imp.getSlice();				// update current slice
			size = Math.min(imp.getWidth()/2, imp.getHeight());	// update image size
			
			// update SIFT parameters that associated with image
			sift.initialSigma = 1.60f;
			sift.steps = 3;
			sift.minOctaveSize = size / 4;
			sift.maxOctaveSize = size;
			sift.fdSize = 4;
			sift.fdBins = 16;
			maxEpsilon = size / 20;
    	}
	}
    
    
    
    
	@Override
	public int setup(String arg, ImagePlus imp) {
		//IJ.log("setup call: " + setupCall++);
		// get image to work with, this run once at beginning of command
		//this.imp = imp;
		//parameter = new Parameter("sift");
		//parameter.impInput = imp;
		// initialize sift parameters, that related to the image size
		
		if ( Utils.checkPluginWindowExist( "Align Channel with SIFT" ) ) return DONE;
		
		if (null == imp) return DONE;
		
		this.imp = imp;
		
		if (null == siftpar) {
			siftpar = new Param();
			siftpar.setImage(imp);
		}
		
		if (arg.equals("final")) {
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
		
		imp.unlock(); //TODO: for unkonwn reason, imp locked at this point

		//parameter.impInput = imp;

		dialog = siftpar.parameter.sift_alignment (siftpar, pfr);
		dialog.addDialogListener(this);
		//previewing = true;
		dialog.showDialog();
		//previewing = false;
		
        //previewing = true;
        //dialog_updating = false;
		//gd.addHelp(IJ.URL2+"/docs/menus/process.html#background");
       
        
        //previewing = false;
        if (dialog.wasOKed()) {		// proceed to deskew
        	//previewing = false;
        	setup ("final", imp);
        	cleanUp ();
            //parameter.storeParam();
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
		
		if (null == e) return true;	// event data is empty, ignore

		//if (dialog_updating) return true;	// do not track dialog item change while GUI updating
		
		
		
		
		
		// get the image current dialog supposed to processing on
		imp = gd.getNextImage();		// update selected image
		slice = (int) gd.getNextNumber();
		imp.unlock();
		imp.setSlice(slice);
		imp.unlock();
		//imp.updateAndRepaintWindow();
		siftpar.parameter.impInput = imp;
		
		
		
		siftpar.sift.initialSigma  = (float) gd.getNextNumber();
		siftpar.sift.steps = ( int )gd.getNextNumber();
		siftpar.sift.minOctaveSize = ( int )gd.getNextNumber();
		siftpar.sift.maxOctaveSize = ( int )gd.getNextNumber();

		siftpar.sift.fdSize = ( int )gd.getNextNumber();
		siftpar.sift.fdBins = ( int )gd.getNextNumber();
		siftpar.rod = ( float )gd.getNextNumber();

		//siftpar.useGeometricConsensusFilter = gd.getNextBoolean();
		siftpar.maxEpsilon = ( float )gd.getNextNumber();
		siftpar.minInlierRatio = ( float )gd.getNextNumber();
		siftpar.minInlierNum = ( int )gd.getNextNumber();
		
		siftpar.show_sift_points = gd.getNextBoolean();
		
		siftpar.load_alignMatrix = gd.getNextBoolean();
		siftpar.parameter.alignmFile = 		gd.getNextString();
		siftpar.interpolate = gd.getNextBoolean();
		
		
		// parse relative parameter change to processing flow update
		image_updated = false; 
		feature_param_updated = false; 
		feature_filter_updated = false;
		align_image_updated = false;
		
		//boolean preview_button_clicked = false;
		boolean image_changed = false;
		boolean load_button_clicked = false;
		String source_name =  ( (Component) e.getSource() ).getName();
		switch (source_name) {
			case "choice_0":		// image choice, image updated
				image_changed = true;
				image_updated = true;
				break;
			case "number_field_0":	// z slider value, image updated (because 2D)
				image_updated = true;
				break;
			case "number_field_1":	// siftpar.sift.initialSigma, feature_param_updated
				feature_param_updated = true;
				break;
			case "number_field_2":	// siftpar.sift.steps, feature_param_updated
				feature_param_updated = true;
				break;
			case "number_field_3":	// siftpar.sift.minOctaveSize, feature_param_updated
				feature_param_updated = true;
				break;
			case "number_field_4":	// siftpar.sift.maxOctaveSize, feature_param_updated
				feature_param_updated = true;
				break;
			case "number_field_5":	// siftpar.sift.fdSize, feature_param_updated
				feature_param_updated = true;
				break;
			case "number_field_6":	// siftpar.sift.fdBins, feature_param_updated
				feature_param_updated = true;
				break;
			case "number_field_7":	// siftpar.rod, feature_param_updated
				feature_param_updated = true;
				break;
			//case "check_box_0":		// siftpar.useGeometricConsensusFilter, feature_filter_updated
			//	feature_filter_updated = true;
			//	break;	
			case "number_field_8":	// siftpar.maxEpsilon, feature_filter_updated
				feature_filter_updated = true;
				break;
			case "number_field_9":	// siftpar.minInlierRatio, feature_filter_updated
				feature_filter_updated = true;
				break;
			case "number_field_10":	// siftpar.minInlierNum, feature_filter_updated
				feature_filter_updated = true;
				break;
			case "check_box_0":		// sift.show_sift_points, view_option_updated
				 
				break;
			case "check_box_1":		// sift.load_alignMatrix, align_image_updated (matrix, check differ)
				align_image_updated = true; 
				load_button_clicked = true;
				break;	
			case "text_field_0":	// align matrix file field, align_image_updated (matrix, check differ)
				align_image_updated = true;
				break;
			case "check_box_2":		// siftpar.interpolate, align_image_updated
				align_image_updated = true;
				break;
			case "check_box_3":		// preview checkbox, toggle preview mode
				//preview_button_clicked = true; // regenerate SIFT points, and align image preview
				if (dialog.isPreviewActive()) image_updated = true;
				break;
		}
		
		//dialog item changed during preview, stop preview first, save time
		//if ( !preview_button_clicked && gd.isPreviewActive() ) {
		//	IJ.log("debug sift 391: previewing, and preview check box unchecked, stop previewing.");
		//	dialog.previewRunning(false);
		//	dialog.getPreviewCheckbox().setState(false);
		//	return true;	
		//}								
				
		// check whether ROI updated
		imp.unlock();
		if ( null == imp.getRoi() ) {
			if (null != roi) image_updated = true;
		} else {
			if ( !imp.getRoi().equals(roi) ) image_updated = true; // ROI not equal, re-prepare image
		}
		imp.unlock();
//		IJ.log("debug dialogitemChanged: image_updated: " + image_updated);
//		IJ.log("debug dialogitemChanged: feature_param_updated: " + feature_param_updated);
//		IJ.log("debug dialogitemChanged: feature_filter_updated: " + feature_filter_updated);
//		IJ.log("debug dialogitemChanged: align_image_updated: " + align_image_updated);
		// if image_updated, run prepare image, and get_sift_candidate, filter_feature_candidate

		// if feature_param_updated, recreate ijSIFT, run prepare_sift, and get_sift_candidate, filter_feature_candidate
		
		// if feature_filter_updated, run filter_feature_candidate

		// if align_image_updated, recompute or load alignment matrix, apply to image
		
		
		
		//if (image_updated || feature_param_updated || feature_filter_updated) feature_updated = true;
		
		
		
		// update inter-related dialog items: image and slider max, load align matrix and sift params
		
		//dialog_updating = false;
		if ( image_changed ) {
			//dialog_updating = true;
			dialog.previewRunning(false);
			dialog.getPreviewCheckbox().setState(false);
			// udpate slice slider to be the same as stack size of the selected image
			( (Scrollbar) dialog.getSliders().elementAt(0) )
				.setMaximum( imp.getImageStackSize() );
			( (TextField) dialog.getNumericFields().elementAt(0) )
				.setText( "1" );
			imp.setSlice(1);
			dialog.repaint();
			//dialog_updating = false;
		}
	
		
		
		
		( (Checkbox) dialog.getCheckboxes().elementAt(0) )
			.setEnabled( !siftpar.load_alignMatrix );
		for (int i=0; i<dialog.getNumericFields().size(); i++) {
			( (TextField) dialog.getNumericFields().elementAt(i) )
				.setEnabled( !siftpar.load_alignMatrix );
		}
	

		if (load_button_clicked) {
			//dialog_updating = true;
			dialog.previewRunning(false);
			dialog.getPreviewCheckbox().setState(false);
			if ( !siftpar.load_alignMatrix ) {	// disable sift parameter
				( (TextField) dialog.getStringFields().elementAt(0) )
					.setText( Parameter.loadSettingMessage );
				siftpar.parameter.alignmFile = Parameter.loadSettingMessage;
			} else {
				( (Checkbox) dialog.getCheckboxes().elementAt(0) )
				.setState( false );
				siftpar.show_sift_points = false;
			}
			//dialog_updating = false;
		}
		
		
		
		
		
		// previewing, check whether dialog item change will affect preview
        //if ( gd.isPreviewActive() ) {
	        
        //}

    	//if (!previewing) imp.unlock();
        //parameter.storeParam();
        return true;
	}



	

	@Override
	public void setNPasses(int nPasses) {
		// keep default, only 1 pass to run method.
	}
	
	
	
	
	@Override
	public void run(ImageProcessor ip) {
		//IJ.log("run call: " + runCall++);
		
		
		//if ( dialog_updating ) return;
		
		if ( dialog.isPreviewActive() ) {

			// 1, check whether image or active ROI changed
			if ( image_updated || null == ip_LR ) {
				// get current selected image, and selected slice
				ip_LR = prepare_image ();
				image_updated = false;
				feature_param_updated = true;
				feature_filter_updated = true;
				align_image_updated = true;
			}
			
			compute_align_matrix = !siftpar.load_alignMatrix;
			if ( siftpar.load_alignMatrix ) {
				double[][] matrix = IO.loadMatrixFromFile( siftpar.parameter.alignmFile );
				if (null == matrix) {	// fail to load alignment matrix from file
					
					( (Checkbox) dialog.getCheckboxes().elementAt(1) )
					.setState( false );
					( (TextField) dialog.getStringFields().elementAt(0) )
					.setText( Parameter.loadSettingMessage );
					
					compute_align_matrix = true;
				} else {
					siftpar.alignMatrix = matrix;
					System.out.println("\n SIFT align matrix loaded as:");
					describe_align_matrix ( siftpar.alignMatrix );
				}
			}
			
			if ( compute_align_matrix ) {
				
				if ( feature_param_updated || null == ijSIFT ) {
					ijSIFT = prepare_sift(siftpar);
					candidate = get_sift_candidates (ip_LR[0], ip_LR[1], ijSIFT, siftpar);
					feature_param_updated = false;
					feature_filter_updated = true;
					align_image_updated = true;
				}
				
				if ( feature_filter_updated || null == candidate ) {
					candidate = filter_feature_candidate (candidate, siftpar);
					sift_candidate_to_overlay (candidate);
					feature_filter_updated = false;
					align_image_updated = true;
				}
			}
			
			imp.setHideOverlay( !siftpar.show_sift_points );
			
			if ( align_image_updated || null == imp_check ) {
				apply_matrix_to_image (ip_LR, siftpar.alignMatrix, siftpar.interpolate);
				align_image_updated = false;
			}


		} else {	// final processing run();
			// check image
			if (null == imp) return;
			
			// close all preview windows
			if (null != imp_check && imp.getStackSize() == 1) {	// if there's preview alignment check image, rename and keep it, close other temp images
				imp_check.setTitle( imp.getTitle() + "-alignment-check" );
				Utils.showMatrixAsTable (siftpar.alignMatrix, imp.getTitle()+"-alignment-matrix");
				cleanUp ();
				return;
			}
			
			cleanUp ();		// maybe unnecessary
			
			// check whether a readily usable alignment matrix exist
			if (null == siftpar.alignMatrix) {	// no alignment matrix, load, or recompute
				compute_align_matrix = !siftpar.load_alignMatrix;
				if ( siftpar.load_alignMatrix ) {
					double[][] matrix = IO.loadMatrixFromFile( siftpar.parameter.alignmFile );
					if (null == matrix) {	// fail to load alignment matrix from file
						compute_align_matrix = true;
					} else {
						siftpar.alignMatrix = matrix;
						System.out.println("\n SIFT align matrix loaded as:");
						describe_align_matrix ( siftpar.alignMatrix );
					}
				}
				
				if ( compute_align_matrix ) {
					ip_LR = prepare_image ();
					ijSIFT = prepare_sift(siftpar);
					candidate = get_sift_candidates (ip_LR[0], ip_LR[1], ijSIFT, siftpar);
					candidate = filter_feature_candidate (candidate, siftpar);
				}
			}
			
			imp.setHideOverlay( !siftpar.show_sift_points );
			
			if (imp.getStackSize() == 1)
				apply_matrix_to_image (ip_LR, siftpar.alignMatrix, siftpar.interpolate);
			else
				apply_matrix_to_stack (prepare_stack(imp), siftpar.alignMatrix, siftpar.interpolate);
			
			imp_check.setTitle( imp.getTitle() + "-alignment-check");
			
			Utils.showMatrixAsTable (siftpar.alignMatrix, imp.getTitle()+"-alignment-matrix");
			
			
			Utils.collectGarbage();
			
			// report runtime
			//float duration = System.currentTimeMillis() - start;
			//log.add("\n\tdeskew finished after %.3f seconds.\n", duration / 1000);
			//log.add("deskew image finish.");
			//log.close();
		}
	
	}
	
	
	/**
	 * 		Interactive Dialog Member Functions
	 */

		/**			Close all preview image(s) and free up memory
		 * 
		 */
		public void cleanUp () {
			//IJ.log("cleanUp call: " + cleanUpCall++);
			// close all preview images
			String[] titles = WindowManager.getImageTitles();
			for (String title : titles) {
				if (title.contains( "preview" ))	//uniqueID )) 
					WindowManager.getImage(title).close();
			}
			Utils.collectGarbage();
		}
	
		public void describe_align_matrix (double[][] matrix) {
			IO.displayMatrix( matrix );
			double angle = Utils.arcsin( matrix[1][0] );
			System.out.println(" the above 2D rigid transform can be interperated as:");
			System.out.printf("\n\trotation (clockwise) in XY plane for %5.3f degree,", angle);
			System.out.printf("\n\tshift (right) in X axis for %5.3f pixels,", matrix[0][2]);
			System.out.printf("\n\tshift (down) in Y axis for %5.3f pixels.\n", matrix[1][2]);
		}
		
		
		public static boolean save_align_matrix (SIFT.Param siftpar) {
			
			if ( null == siftpar.alignMatrix )
				return false;
				
			// try to save align matrix to csv file
			//if ( !parameter.saveAlignMatrix || null == parameter.alignMatrix )
			//	return false;
			
			SaveDialog sd = new SaveDialog("Save Alignment Matrix", siftpar.imageName + "_align", ".csv");
	        String file = sd.getFileName();
	        if (file==null)
	            return false;
	        String alignMatrixPath = sd.getDirectory() + file;
	
	        if (!alignMatrixPath.endsWith(".csv")) alignMatrixPath += ".csv";
	
	        return IO.saveMatrixToFile(siftpar.alignMatrix, alignMatrixPath);
		}
		


	
	
	/**
	 *		SIFT Feature Extraction and 2D Rigid Body Transform Functions
	 */
		
		/**
		 * 
		 * @param imp
		 * @param slice
		 * @param percentage_saturated
		 * @return
		 */
		private ImageProcessor[] prepare_image () {
			
			if (null != roi) {
				Roi roi_mirror = RoiScaler.scale(roi, -1, 1, true);
				int w = imp.getWidth();
				Rectangle bounds = roi.getBounds();
				roi_mirror.setLocation(w-bounds.x-bounds.width, bounds.y);
				ShapeRoi sp1 = new ShapeRoi (roi );
				ShapeRoi sp_all = sp1.or( new ShapeRoi(roi_mirror) );
				roi = sp_all;
				imp.setRoi( roi );
			}
			
			imp.unlock();
			imp.setSlice(slice);
			
			//ImageProcessor ip = imp.getStack().getProcessor(slice);
        	ImagePlus imp_slice = imp.crop("whole-slice");	// ignore ROI
        	ImagePlus[] imp_LR = new ImagePlus[2];
        	imp_LR = Partition.separateImageLeftRight (imp_slice, "left & right separately", false);
        	IJ.run(imp_LR[1], "Flip Horizontally", "");
        	ImageProcessor ip_l = imp_LR[0].getProcessor();
        	ImageProcessor ip_r = imp_LR[1].getProcessor();
			new ContrastEnhancer().stretchHistogram(ip_l, 0.35); // 0.35 by default
			new ContrastEnhancer().stretchHistogram(ip_r, 0.35);
			return new ImageProcessor[] {ip_l, ip_r};
			
		}
		
		private ImagePlus[] prepare_stack (ImagePlus imp_stack) {
			if (null == imp_stack) return null;
			Roi roi = imp_stack.getRoi();
			imp_stack.deleteRoi();
			ImagePlus[] imp_LR = Partition.separateImageLeftRight (imp_stack, "left & right separately", true);
			IJ.run(imp_LR[1], "Flip Horizontally", "stack");
			imp_stack.setRoi( roi );
        	return imp_LR;
		}
		
		/**
		 * s
		 * @param p
		 * @return
		 */
		private mpicbg.ij.SIFT prepare_sift (Param p) {
			FloatArray2DSIFT sift = new FloatArray2DSIFT( p.sift );
			mpicbg.ij.SIFT ijSIFT = new mpicbg.ij.SIFT( sift );
			return ijSIFT;
		}
		/**
		 * ss
		 * @param ip1
		 * @param ip2
		 * @param ijSIFT
		 * @param p
		 * @return
		 */
		private List< PointMatch > get_sift_candidates (ImageProcessor ip1, ImageProcessor ip2, mpicbg.ij.SIFT ijSIFT, Param p) {
			long start_time = System.currentTimeMillis();
			
			List< Feature > fs1 = new ArrayList< Feature >();
			ijSIFT.extractFeatures( ip1, fs1 );
			
			System.out.println( "\n extract SIFT feature on left took " + ( System.currentTimeMillis() - start_time ) + "ms." );
			System.out.println( "\t" + fs1.size() + " features extracted." );
			start_time = System.currentTimeMillis();
			
			List< Feature > fs2 = new ArrayList< Feature >();
			ijSIFT.extractFeatures( ip2, fs2 );
			
			System.out.println( "\n extract SIFT feature on right took " + ( System.currentTimeMillis() - start_time ) + "ms." );
			System.out.println( "\t" + fs2.size() + " features extracted." );
			start_time = System.currentTimeMillis();
			
			List< PointMatch > candidates;
			candidates = FloatArray2DSIFT.createMatches( fs2, fs1, 1.5f, null, Float.MAX_VALUE, p.rod );
			//FeatureTransform.matchFeatures( fs2, fs1, candidates, p.rod );
			
			System.out.println( "\n identify correspondence candidates took " + ( System.currentTimeMillis() - start_time ) + "ms." );
			System.out.println( "\t" + candidates.size() + " correspondence candidates identified." );
			
			return candidates;
		}
		
		/**
		 * s
		 * @param candidates
		 * @param p
		 * @return
		 */
		private List< PointMatch > filter_feature_candidate (List< PointMatch > candidates, Param p) {
			
			if (null == candidate || 0 == candidate.size()) {
				System.out.println(" No SIFT candidate to filter!");
				p.alignMatrix = null;
				return null;
			}
			long start_time = System.currentTimeMillis();

			AbstractAffineModel2D<?> model = new RigidModel2D();
			ArrayList<PointMatch> inliers = new ArrayList< PointMatch >();
			boolean modelFound;
			try {
				modelFound = model.filterRansac(
						candidates,
						inliers,
						1000,
						p.maxEpsilon,
						p.minInlierRatio,
						p.minInlierNum );
			} catch ( final NotEnoughDataPointsException e ) {
				modelFound = false;
			}
			
			//int num_inliers = (null == inliers) ? 0 : inliers.size();
			if (modelFound) {
				//PointMatch.apply( inliers, model );
				p.alignMatrix = new double[2][3];
				model.toMatrix(p.alignMatrix);
			} else {
				System.out.println( "\n Not enough data after filter, adjust filter parameters!" );
				p.alignMatrix = null;
				return null;
			}


			System.out.println( "\n filter candidates by geometric consensus took " + ( System.currentTimeMillis() - start_time ) + "ms." );
			System.out.println( "\t" + inliers.size() + " correspondence candidates left after filter." );
			
			System.out.println("\n SIFT align matrix computed as:");
			describe_align_matrix ( p.alignMatrix );
			
			return inliers;
		}
		
			/**
			 * 
			 * @param candidate
			 * @return
			 */
			private void sift_candidate_to_overlay (List< PointMatch > candidate) {
				if (null == candidate || 0 == candidate.size()) {
					System.out.println(" No SIFT candidate to display!");
					return;
				}
				// convert SIFT candidate to ROI, for the two images respectively
				final ArrayList< Point > p1 = new ArrayList< Point >();
				final ArrayList< Point > p2 = new ArrayList< Point >();
				PointMatch.sourcePoints( candidate, p1 );
				PointMatch.targetPoints( candidate, p2 );
				Roi Roi_1 = Util.pointsToPointRoi( p1 );
				Roi Roi_2 = Util.pointsToPointRoi( p2 );
	        	// apply offset to point ROIs, flip the right side back to original image coordinate
				int width = imp.getWidth();
				int offest = (null==roi) ? 0 : roi.getBounds().x;
	        	FloatPolygon fpl = Roi_1.getContainedFloatPoints();
	        	fpl.translate(offest, 0);
	        	FloatPolygon fpr = Roi_2.getContainedFloatPoints();
	        	FloatPolygon fpr_flip = new FloatPolygon();
	        	for (int i=0; i<fpl.npoints; i++) {
	        		fpr_flip.addPoint(width-fpr.xpoints[i]-offest, fpr.ypoints[i]);
	        	}
	        	// create overlay from the left and right ROI, apply RED and GREEN color, and combine
	        	Roi roi_L = new PointRoi( fpl );
	        	Roi roi_R = new PointRoi( fpr_flip );
	        	roi_L.setStrokeColor( java.awt.Color.RED );
	        	roi_R.setStrokeColor( java.awt.Color.GREEN );
	        	Overlay overlay = new Overlay( roi_L );
	        	overlay.add( new Overlay( roi_R ) );
	        	imp.setOverlay( null );
	        	imp.setOverlay( overlay );
	        	imp.setHideOverlay( false );	// by default, don't display the SIFT points overlay
	        	// debug, display ROI on the left and right side cropped image
	        	//ImagePlus imp_c1 = new ImagePlus("C1", ip_LR[0]);
	        	//ImagePlus imp_c2 = new ImagePlus("C2", ip_LR[1]);
	        	//imp_c1.show(); imp_c1.setRoi( Roi_1 );
	        	//imp_c2.show(); imp_c2.setRoi( Roi_2 );
			}

			
		// apply align to image
		private void apply_matrix_to_image (ImageProcessor[] ip_LR, double[][] matrix, boolean interpolate) {
			if (null == ip_LR || null == matrix) {
				System.out.println(" No valid alignment matrix to apply!");
				return;
			}
			ImagePlus imp_c1 = new ImagePlus("C1", ip_LR[0]);
        	ImagePlus imp_c2 = new ImagePlus("C2", ip_LR[1]);
			ImageProcessor ip_aligned = alignWithRigid2DMatrix (ip_LR[1], matrix, interpolate);
			//ImageProcessor ip_c3 = alignWithRigid2DMatrix (ip_c2, align_matrix, true);
			ImagePlus imp_c3 = new ImagePlus("C2-aligned", ip_aligned);
			
			ImagePlus imp_check_now = RGBStackMerge.mergeChannels (new ImagePlus[]{ imp_c1, imp_c2, imp_c3 }, false);
			if (null == imp_check) 	imp_check = imp_check_now;	
			else 					imp_check.setImage(imp_check_now);
			imp_check.setTitle( "preview-alignment-check" );
			imp_check.show();
			
			//Utils.displayImage(imp_check, "preview-alignment-check");
			
			imp_check.getImageStack().setSliceLabel( "left", 1 );
			imp_check.getImageStack().setSliceLabel( "right", 2 );
			imp_check.getImageStack().setSliceLabel( "right-SIFT-aligned", 3 );
			imp_check.setActiveChannels("101");
			IJ.run("Channels Tool...");
		}
		
		
		// apply align to stack
		private void apply_matrix_to_stack (ImagePlus[] imp_LR, double[][] matrix, boolean interpolate) {
			if (null == imp_LR || null == matrix ) {
				System.out.println(" No valid alignment matrix to apply!");
				return;
			}
			ImagePlus imp_R_aligned = imp_LR[1].duplicate();
			imp_R_aligned.setTitle( imp.getTitle() + "-right-aligned" );
			alignStackSIFT2 (imp_R_aligned, matrix, interpolate);
			imp_check = Partition.combineChannel ( new ImagePlus[] {imp_LR[0], imp_LR[1], imp_R_aligned} );
			imp_check.show();
			Utils.updateBC( imp_check );
			imp_check.setActiveChannels("101");
			IJ.run("Channels Tool...");
		}
		

		/**			Compute a rigid transformation matrix
		 * <br>		based on SIFT features of two image (ImageProcessor)
		 * 
		 * @param ip1				: reference image as Imageprocessor
		 * @param ip2				: to be aligned image as Imageprocessor
		 * <p>
		 * @return double[2][3]		: 2 x 3 matrix representing the 2D rigid transform
		 */
		public static void extractSIFTPoints (
				ImageProcessor ip1,
				ImageProcessor ip2,
				Param p
				) {
			if (null == ip1 || null == ip2) return;
			long start_time = System.currentTimeMillis();
			// feature extraction depends on display contrast of image...
			new ContrastEnhancer().stretchHistogram(ip1, 0.35);
			new ContrastEnhancer().stretchHistogram(ip2, 0.35);
			//new ImagePlus("ip1", ip1.duplicate()).show();
			//new ImagePlus("ip2", ip2.duplicate()).show();
			// use 1% width as smallest image size, and scale up to image width as maximum
			
			System.out.println( " adjust Brightnesss and contrast took " + ( System.currentTimeMillis() - start_time ) + "ms." );
			start_time = System.currentTimeMillis();
			
			//minOctave = 256;
			List< Feature > fs1 = new ArrayList< Feature >();
			List< Feature > fs2 = new ArrayList< Feature >();
			
			//FloatArray2DSIFT.Param siftParam = new FloatArray2DSIFT.Param();
			//siftParam.initialSigma = 1.60f;
			//siftParam.steps = 8;
			//siftParam.minOctaveSize = minOctave;
			//siftParam.maxOctaveSize = minOctave * 8;
			//siftParam.fdSize = 4;
			//siftParam.fdBins = 8;
			//final float rod = 0.92f;
			//final float maxEpsilon = 25f;
			//final float minInlierRatio = 0.05f;
			//final int minNumInliers = 7;
			
			//System.out.println("siftParam.minOctaveSize: " + siftParam.minOctaveSize);
			//System.out.println("siftParam.maxOctaveSize: " + siftParam.maxOctaveSize);
			
			final FloatArray2DSIFT sift = new FloatArray2DSIFT( p.sift );
			final mpicbg.ij.SIFT ijSIFT = new mpicbg.ij.SIFT( sift );
			
			System.out.println( " make mpicbg.if.SIFT object took " + ( System.currentTimeMillis() - start_time ) + "ms." );
			start_time = System.currentTimeMillis();
			
			
			// extract SIFT features
			ijSIFT.extractFeatures( ip1, fs1 );		// !!! TIME CONSUMING
			
			System.out.println( " extract SIFT feature on left took " + ( System.currentTimeMillis() - start_time ) + "ms." );
			System.out.println( fs1.size() + " features extracted." );
			
			start_time = System.currentTimeMillis();
			
			ijSIFT.extractFeatures( ip2, fs2 );		// !!! TIME CONSUMING
			
			System.out.println( " extract SIFT feature on right took " + ( System.currentTimeMillis() - start_time ) + "ms." );
			System.out.println( fs2.size() + " features extracted." );
			
			start_time = System.currentTimeMillis();
			System.out.println( "Identifying correspondence candidates using brute force ..." );
			
			final List< PointMatch > candidates = new ArrayList< PointMatch >();
			FeatureTransform.matchFeatures( fs2, fs1, candidates, p.rod );			// !!! TIME CONSUMING
			
			System.out.println( " took " + ( System.currentTimeMillis() - start_time ) + "ms." );
			
			
			final ArrayList< Point > p1 = new ArrayList< Point >();
			final ArrayList< Point > p2 = new ArrayList< Point >();
			final List< PointMatch > inliers;
			
			AbstractAffineModel2D<?> model = new RigidModel2D();
			boolean modelFound = false;
			

			System.out.println( candidates.size() + " potentially corresponding features identified." );
			start_time = System.currentTimeMillis();
			System.out.println( "Filtering correspondence candidates by geometric consensus ..." );
			inliers = new ArrayList< PointMatch >();

			try {
				modelFound = model.filterRansac(
						candidates,
						inliers,
						1000,
						p.maxEpsilon,
						p.minInlierRatio,
						p.minInlierNum );
			} catch ( final NotEnoughDataPointsException e ) {
				modelFound = false;
			}
				
			System.out.println( " took " + ( System.currentTimeMillis() - start_time ) + "ms." );
			
			if ( modelFound ) {
				PointMatch.apply( inliers, model );
				
				System.out.println( inliers.size() + " corresponding features with an average displacement of " + decimalFormat.format( PointMatch.meanDistance( inliers ) ) + "px identified." );
				System.out.println( "Estimated transformation model: " + model );
			} else {
				System.out.println( "No correspondences found." );
				return;
			}
			
			

			
			
			if ( inliers.size() == 0 ) return;
			
			double[][] align_matrix = new double[2][3];
			model.toMatrix(align_matrix);
			
			
			System.out.println("\n\tSIFT align matrix computed as:");
			IO.displayMatrix(align_matrix);
			double angle = Utils.arcsin(align_matrix[1][0]);
			System.out.println("\tthe above 2D rigid transform can be interperated as:");
			System.out.printf("\n\trotation (clockwise) in XY plane for %5.3f degree,", angle);
			System.out.printf("\n\tshift (right) in X axis for %5.3f pixels,", align_matrix[0][2]);
			System.out.printf("\n\tshift (down) in Y axis for %5.3f pixels.\n", align_matrix[1][2]);
			
			
			PointMatch.sourcePoints( inliers, p1 );
			PointMatch.targetPoints( inliers, p2 );
			Roi Roi_1 = Util.pointsToPointRoi( p1 );
			Roi Roi_2 = Util.pointsToPointRoi( p2 );
			
			
			Roi_1.setStrokeColor( java.awt.Color.RED );
        	Overlay overlay_1 = new Overlay( Roi_1 );
        	Roi_2.setStrokeColor( java.awt.Color.GREEN );
        	Overlay overlay_2 = new Overlay( Roi_2 );
        	
        	ImagePlus imp_c1 = new ImagePlus("C1", ip1);
        	ImagePlus imp_c2 = new ImagePlus("C2", ip2);
        	
        	imp_c1.show(); imp_c1.setOverlay( overlay_1 );
        	imp_c2.show(); imp_c2.setOverlay( overlay_2 );
			
				//Roi_1.setColor( java.awt.Color.RED );
				//Roi_2.setColor( java.awt.Color.GREEN );
			start_time = System.currentTimeMillis();
			System.out.println( "transform image with computed align matrix ..." );
			
			ImageProcessor ip_aligned = alignWithRigid2DMatrix (ip2, align_matrix, p.interpolate);
			
			
				//ImageProcessor ip_c3 = alignWithRigid2DMatrix (ip_c2, align_matrix, true);
				ImagePlus imp_c3 = new ImagePlus("C2-aligned", ip_aligned);
				ImagePlus imp_check = RGBStackMerge.mergeChannels (new ImagePlus[]{ imp_c1, imp_c2, imp_c3 }, true);	
				Utils.displayImage(imp_check, "-alignmentCheck");
				imp_check.getImageStack().setSliceLabel( "left", 1 );
				imp_check.getImageStack().setSliceLabel( "right", 2 );
				imp_check.getImageStack().setSliceLabel( "right-SIFT-aligned", 3 );
				//imp_check.setActiveChannels("101");
			
			
			
			//new ImagePlus( "right-aligned", ip_aligned).show();
			
			System.out.println( " took " + ( System.currentTimeMillis() - start_time ) + "ms." );
			
		}
		
		
		/**			Compute a rigid transformation matrix
		 * <br>		based on SIFT features of two image (ImageProcessor)
		 * 
		 * @param ip1				: reference image as Imageprocessor
		 * @param ip2				: to be aligned image as Imageprocessor
		 * <p>
		 * @return double[2][3]		: 2 x 3 matrix representing the 2D rigid transform
		 */
		public static double[][] computeAlignMatrix (
				ImageProcessor ip1,
				ImageProcessor ip2,
				boolean debug
				) {
			if (null == ip1 || null == ip2) return null;
			long start = System.currentTimeMillis();
			// feature extraction depends on display contrast of image...
			new ContrastEnhancer().stretchHistogram(ip1, 0.35);
			new ContrastEnhancer().stretchHistogram(ip2, 0.35);
			//new ImagePlus("ip1", ip1.duplicate()).show();
			//new ImagePlus("ip2", ip2.duplicate()).show();
			// use 1% width as smallest image size, and scale up to image width as maximum
			int minOctave = (int) Math.round( (double)ip1.getWidth() / 12.5d );
			//minOctave = 256;
			List< Feature > fs1 = new ArrayList< Feature >();
			List< Feature > fs2 = new ArrayList< Feature >();
			FloatArray2DSIFT.Param siftParam = new FloatArray2DSIFT.Param();
			siftParam.initialSigma = 1.60f;
			siftParam.steps = 8;
			siftParam.minOctaveSize = minOctave;
			siftParam.maxOctaveSize = minOctave * 8;
			siftParam.fdSize = 4;
			siftParam.fdBins = 8;
			final float rod = 0.92f;
			final float maxEpsilon = 25f;
			final float minInlierRatio = 0.05f;
			final int minNumInliers = 7;
			
			System.out.println("siftParam.minOctaveSize: " + siftParam.minOctaveSize);
			System.out.println("siftParam.maxOctaveSize: " + siftParam.maxOctaveSize);
			
			final FloatArray2DSIFT sift = new FloatArray2DSIFT( siftParam );
			final mpicbg.ij.SIFT ijSIFT = new mpicbg.ij.SIFT( sift );
			// extract SIFT features
			ijSIFT.extractFeatures( ip1, fs1 );
			ijSIFT.extractFeatures( ip2, fs2 );
			// create mapping candidates
			final Vector< PointMatch > candidates = FloatArray2DSIFT.createMatches( 
					fs2, fs1, 1.5f, null, Float.MAX_VALUE, rod );
			// calculate rigid transformation: rotation θ, translation: tx, ty
			//  X:	1	0	0    |	cos(θ)	-sin(θ)		tx
			//  Y:	0	1	0	 |	sin(θ)	 cos(θ)		ty
			//  1:	0	0	1	 |	  0			0		1
			if ( debug ) {
				System.out.printf( "\n\tSIFT took %d ms.", System.currentTimeMillis()-start );
				System.out.printf( "\n\tfeature extracted:\n\ttarget: %d\n\tsource: %d", fs1.size(), fs2.size() );
				System.out.printf( "\n\t%d potentially corresponding features identified", candidates.size() );
			}
			double[][] matrix = null;
			final Vector< PointMatch > inliers = new Vector< PointMatch >();
			AbstractAffineModel2D<?> model = new RigidModel2D();
			boolean modelFound = false;
			try {
				matrix = new double[2][3];
				modelFound = model.filterRansac( candidates, inliers,
						1000, maxEpsilon, minInlierRatio, minNumInliers );	
			} catch ( Exception e ) {
				modelFound = false;
				System.out.println( e.getMessage() );
			}
			if (modelFound) {
				model.toMatrix(matrix);
				if ( debug ) {
					System.out.printf( "\n\t%d filtered corresponding features as inliner", inliers.size()  );
					System.out.println( "\n\tEstimated transformation model:\n\t" + model );
				}
			}
			return matrix;
		}
		/**
		 * 
		 * @param ip1
		 * @param ip2
		 * <p>
		 * @return
		 */
		public static double[][] computeAlignMatrix (
				ImageProcessor ip1,
				ImageProcessor ip2
				) {
			return computeAlignMatrix ( ip1, ip2, true );
		}
		
		
		public static ImageProcessor alignWithRigid2DMatrix (
				ImageProcessor ip_original,
				double[][] rigid2d_matrix,
				boolean interpolate
				) {
			final ImageProcessor ip_aligned = ip_original.createProcessor( 
					ip_original.getWidth(), ip_original.getHeight() );
			ip_aligned.setMinAndMax( ip_original.getMin(), ip_original.getMax() );
			ip_aligned.setInterpolationMethod( ImageProcessor.BILINEAR );
			
			AffineModel2D model = new AffineModel2D();
			double m00 = rigid2d_matrix[0][0]; double m01 = rigid2d_matrix[0][1]; double m02 = rigid2d_matrix[0][2];
			double m10 = rigid2d_matrix[1][0]; double m11 = rigid2d_matrix[1][1]; double m12 = rigid2d_matrix[1][2];
			model.set(m00, m10, m01, m11, m02, m12);
			final InverseTransformMapping<AbstractAffineModel2D<?>> mapping = new InverseTransformMapping< AbstractAffineModel2D< ? > >( model );			
	
			if ( interpolate )
				mapping.mapInterpolated( ip_original, ip_aligned );
			else
				mapping.map( ip_original, ip_aligned );
			
			return ip_aligned;
		}
		
		
		public static double[][] trySIFTalignment (
				ImagePlus imp_ch1,
				ImagePlus imp_ch2,
				boolean display
				) {
			if (null == imp_ch1 || null == imp_ch2) return null;
			ImagePlus impZ_ch1 = null;	ImagePlus impZ_ch2 = null;
			if ( 1 == imp_ch1.getNSlices() )	impZ_ch1 = imp_ch1.duplicate();
			else impZ_ch1 = Projection.projection ( imp_ch1, "Z", "max", true );
			if ( 1 == imp_ch2.getNSlices() )	impZ_ch2 = imp_ch2.duplicate();
			else impZ_ch2 = Projection.projection ( imp_ch2, "Z", "max", true );
			ImageProcessor ip_c1 = impZ_ch1.getProcessor();
			ImageProcessor ip_c2 = impZ_ch2.getProcessor();
			// compute alignment transformation (rigid 2D) matrix
			double[][] align_matrix = computeAlignMatrix ( ip_c1, ip_c2, true );
			
			if ( !checkAlignMatrix (align_matrix) ) 
				IJ.log(" SIFT align matrix maybe wrong! check Fiji Console for more details.");
			if ( 0 == align_matrix[0][0] || 0 == align_matrix[1][1]) return null;
			
			System.out.println("\n\tSIFT align matrix computed as:");
			IO.displayMatrix(align_matrix);
			double angle = Utils.arcsin(align_matrix[1][0]);
			System.out.println("\tthe above 2D rigid transform can be interperated as:");
			System.out.printf("\n\trotation (clockwise) in XY plane for %5.3f degree,", angle);
			System.out.printf("\n\tshift (right) in X axis for %5.3f pixels,", align_matrix[0][2]);
			System.out.printf("\n\tshift (down) in Y axis for %5.3f pixels.\n", align_matrix[1][2]);
			
			if ( display ) {
				ImageProcessor ip_c3 = alignWithRigid2DMatrix (ip_c2, align_matrix, true);
				ImagePlus impZ_ch3 = new ImagePlus("C2-aligned", ip_c3);
				ImagePlus imp_check = RGBStackMerge.mergeChannels (new ImagePlus[]{ impZ_ch1, impZ_ch2, impZ_ch3 }, false);	
				Utils.displayImage(imp_check, imp_ch1.getTitle() + "-alignmentCheck");
				imp_check.getImageStack().setSliceLabel( "left", 1 );
				imp_check.getImageStack().setSliceLabel( "right", 2 );
				imp_check.getImageStack().setSliceLabel( "right-SIFT-aligned", 3 );
				imp_check.setActiveChannels("101");
			}
			impZ_ch1.close();	impZ_ch2.close();
			return align_matrix;	
		}
		
		/**
		 * 
		 * @param imp
		 * @param display
		 * @return
		 */
		public static double[][] trySIFTalignment (
				ImagePlus imp,
				boolean display
				) {
			if ( !imp.isComposite() || !imp.hasImageStack() || imp.getNChannels()!=2 || imp.getNFrames()!=1) 
				return null;
			ImagePlus impZ = imp;
			String name = Utils.getName ( imp );
			
			int numZ = imp.getNSlices();
			if (numZ > 1) impZ = Projection.projection ( imp, "Z", "max", true );  //ZProjector.run(imp, "max all");
			ImageStack stackZ = impZ.getStack();
			// update stack with aligned slices
			//ImageStack stack = imp.getStack();
			
			// reference channel is 1st channel, align 2nd channel to 1st channel
			ImageProcessor ip_c1 = stackZ.getProcessor( 1 );
			ImageProcessor ip_c2 = stackZ.getProcessor( 2 );
			
			// compute alignment transformation (rigid 2D) matrix
			double[][] align_matrix = computeAlignMatrix ( ip_c1, ip_c2 );
			
			// update imp and imp_maxZ with current computed SIFT alignment
			ip_c2 = alignWithRigid2DMatrix (ip_c2, align_matrix, true);
			stackZ.setProcessor( ip_c2, 2);
			if ( display ) Utils.displayImage(impZ, name+"-alignmentCheck");
			//for (int z=0; z<numZ; z++) {
			//	int idx = imp.getStackIndex( 2, z+1, 1);
			//	ImageProcessor ip_aligned = alignWithRigid2DMatrix (stack.getProcessor(idx), align_matrix, true);
			//	stack.setProcessor( ip_aligned, idx );
			//}
			//imp.setStack( stack );
			return align_matrix;
		}
		
		
		/**
		 * 
		 * @param matrix
		 * @param maxRotationAngle
		 * @param maxXtranslation
		 * @param maxYtranslation
		 * @return
		 */
		public static boolean checkAlignMatrix (
				double[][] matrix, 
				double maxRotationAngle,
				double maxXtranslation,
				double maxYtranslation
				) {
			if (null == matrix) return false;
			double cos_theta = Utils.cos ( maxRotationAngle );
			double sin_theta = Utils.sin ( maxRotationAngle );
	
			if ( matrix[0][0] < cos_theta || matrix[1][1] < cos_theta ) return false;
			if ( matrix[0][1] * matrix[1][0] > 0 ) return false;
			if ( Math.abs( matrix[0][1] ) > sin_theta || 
				 Math.abs( matrix[1][0] ) > sin_theta ) return false;
			if ( Math.abs( matrix[0][2] ) > maxXtranslation ||
				 Math.abs( matrix[1][2] ) > maxYtranslation ) return false;
			
			return true;
		}
		public static boolean checkAlignMatrix (
				double[][] matrix
				) {	// default allowed max angle, and x, y shift is 5 degree, 20 pixel, and 10 pixel
			return checkAlignMatrix ( matrix, 0.50d, 50.0d, 10.0d );	// 5% difference of 5 degree rotation, 3200 x 600 XY frame size
		}
		
		/**
		 * 
		 * @param imp
		 * @param refrenceChannel
		 * @param interpolate
		 */
		public void alignChannelSIFT (
				ImagePlus imp,
				int refrenceChannel,
				boolean interpolate
				) {
			if ( !imp.isComposite() || !imp.hasImageStack() || imp.getNChannels()<=1 ) return;
			
			int numC = imp.getNChannels();
			int numZ = imp.getNSlices();
			int numT = imp.getNFrames();
	
			refrenceChannel = Math.max(refrenceChannel, 1);
			refrenceChannel = Math.min(refrenceChannel, numC);
	
			ImagePlus impZ = imp;
			if (numZ > 1) impZ = Projection.projection ( imp, "Z", "max", true );  //ZProjector.run(imp, "max all");
			ImageStack stackZ = impZ.getStack();
	
			// update stack with aligned slices
			ImageStack stack = imp.getStack();
			for (int t=0; t<numT; t++) {
				// need a reference slice for every T frame
				ImageProcessor ip_reference = stackZ.getProcessor( impZ.getStackIndex( refrenceChannel, 1, t+1) );
				for (int c=0; c<numC; c++) {
					// keep reference channel
					if (c+1 == refrenceChannel) continue;
					int idxZ = impZ.getStackIndex( c+1, 1, t+1);
					// compute alignment transformation (rigid 2D) matrix
					double[][] align_matrix = computeAlignMatrix ( ip_reference, stackZ.getProcessor(idxZ) );
					// update Z and T slices of the current channel
					for (int z=0; z<numZ; z++) {
						int idx = imp.getStackIndex( c+1, z+1, t+1);
						ImageProcessor ip_aligned = alignWithRigid2DMatrix (stack.getProcessor(idx), align_matrix, interpolate);
						stack.setProcessor( ip_aligned, idx );
					}	
				}
			}
			imp.setStack( stack );
		}
		
		
		public static void alignStackSIFT (
				ImagePlus imp,
				double[][] matrix) {
			if (null == imp || null == matrix) return;
			String title = imp.getTitle();
			
			System.out.println("align matrix (2D?):");
			Utils.displayMatrix ( matrix );
			
			if ( 4 != matrix.length )
				matrix = Transform.convertTo3Dtransform ( matrix );
			
			System.out.println("align matrix (3D?):");
			Utils.displayMatrix ( matrix );
			
			int w = imp.getWidth(); int h = imp.getHeight();
			
			//ImagePlus imp_align = GPU.transform ( imp, matrix, "Z", false );
			ImagePlus imp_align = CPU.transform ( imp, matrix, false );
			
			//imp_align.duplicate().show();
			if (imp_align.getWidth() != w || imp_align.getHeight() != h) {
				imp_align.setRoi( new Roi (0, 0, w, h) );		//TODO: assume c2 is always bigger than c1
				imp_align = new Duplicator().run( imp_align ); 	// imp_align.crop("stack");
			}
			imp.setImage( imp_align );
			imp.setTitle( title );
		}
		
		public static void alignStackSIFT2 (
				ImagePlus imp,
				double[][] matrix,
				boolean interpolate) {
			/*
			 * alignWithRigid2DMatrix (
				ImageProcessor ip_original,
				double[][] rigid2d_matrix,
				boolean interpolate
				)
			 */
			if (null == imp || null == matrix) return;
			String title = imp.getTitle();
			int w = imp.getWidth(); int h = imp.getHeight();
			
			ImageStack stack = imp.getImageStack();
			ImageStack stack_aligned = new ImageStack(w, h, stack.size());
			
			for (int i=0; i<stack.size(); i++) {
				ImageProcessor ip = stack.getProcessor(i+1);
				ImageProcessor ip_aligned = alignWithRigid2DMatrix ( ip, matrix, interpolate );
				stack_aligned.setProcessor(ip_aligned, i+1);
			}
			
			imp.setStack( stack_aligned );
			imp.setTitle( title );
			
		}
		
		/**
		 * 
		 * @param imp
		 * @param matrix
		 */
		public static void align2ndChannelwithMatrix (
				ImagePlus imp,
				double[][] matrix
				) {
			if (null == imp || null == matrix || 2 != imp.getNChannels()) return;
			String title = imp.getTitle();
			ImagePlus[] imp_channels = ChannelSplitter.split ( imp );
			imp_channels[1] = GPU.transform ( imp_channels[1], matrix, "Z", false );
			int w1 = imp_channels[0].getWidth(); int h1 = imp_channels[0].getHeight();
			int w2 = imp_channels[1].getWidth(); int h2 = imp_channels[1].getHeight();
			if (w1 != w2 || h1 != h2) {
				imp_channels[1].setRoi( new Roi (0, 0, w1, h1) );	//TODO: assume c2 is always bigger than c1
				imp_channels[1] = new Duplicator().run( imp_channels[1] ); // imp_channels[1].crop("stack");
			}
			imp.setImage ( Partition.combineChannel ( imp_channels ) );
			imp.setTitle( title );
		}

	
	
}
