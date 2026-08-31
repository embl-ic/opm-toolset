package de.embl.iclm;

import org.scijava.prefs.DefaultPrefService;
import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.NonBlockingGenericDialog;
import ij.gui.Roi;
import ij.io.OpenDialog;
import ij.io.SaveDialog;
import ij.plugin.filter.PlugInFilterRunner;

import java.awt.Checkbox;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Vector;

public class Parameter {
	//	current object instantce
	protected static Parameter instance;
	//	fixed value parameter for all calling object
	protected static final double zSizePerGalvoDU		= 13.25d;
	protected static final String loadSettingMessage	= "          " + "load settings from file";
	protected static final String loadAlignMessage 		= "          " + "load alignment matrix from file";
	protected static final String loadRoiMessage		= "          " + "load ROI from file";
	protected static final String[] extensions			= {"tif", "tiff"};
	
	//	fixed value variables to be used only within Parameter class
	private final Color frameColor = new Color (204, 229, 255);

	private final String[] channelOptions 	= {"whole image", "fold by midline", "align with SIFT", "only left", "only right", "left & right separately"};
	private final String[] fileExistOptions = {"skip", "overwrite"};
	private final String[] typeChoices 		= {"translate","scale","rotate","shear_X", "shear_Y", "shear_Z"};
	private final String[] axisChoices 		= {"X", "Y", "Z"};
	private final String[] permuteOptions 	= {"->YZX", "->ZYX", "->XZY", "->ZXY", "->YXZ", "->XYZ"};
	private final String[] imageTypes		= {"auto detection", "OPM raw volume", "deskewed volume"};
	//private final String[] roiOptions		= {"No ROI", "Auto", "Draw", "Load"};
	private final String[] psfAvgMethod 	= {"median average", "mean average", "all beads"};
	public static final String[] PSF_CHANNEL_LAYOUTS = {"single channel image", "mirrored left/right halves"};
	private final String[] deconvMethodChoices = {"Richardson-Lucy (FFT)", "Richardson-Lucy Total Variation"};
	
	
	//	parameter call object
	public  String obj = "";	
	
	//	parameters for deskew active image
	public ImagePlus impInput			= null;
	public double xyPixelSize			= 116.0d;
	public double zStepSize				= 132.5d;
	public double opmAngle				= 25.0d;
	public double frameInterval			= 0.0d;
	public String deskewmFile 			= loadSettingMessage;
	protected double[][] deskewMatrix	= Transform.identity();
	public String channelStr			= channelOptions[0];
	public String alignmFile			= loadAlignMessage;
	protected double[][] alignMatrix	= null;
	protected boolean displayResult		= true;		// by default display result image(s), and no display for batch, and folder watch
	protected boolean doProjection		= true;		// whether to make projection images (hidden for user, and based on projection options)
	public String saveDir				= "";
	public boolean saveToSame			= false;
	public boolean saveDeskewImage		= true;
	protected boolean saveDeskewMatrix	= false;
	protected boolean saveAlignMatrix	= false;
	protected String fileNameDeskew		= "<image name>_deskew.csv";
	protected String fileNameAlign		= "<image name>_align.csv";
	
	// parameters for deconvolution
	
	public Roi roiInput 				= null;
	public ImagePlus impPSF 			= null;
    public String deconvMethod 			= deconvMethodChoices[0];
    public int numIter 					= 10;
    public double regFactor 			= 0.0d;
    //public boolean nonCirclulant		= false;
	public boolean loadFromFile			= false;
	public String beadsPath 			= "";
	public String imageType				= imageTypes[0];
	public boolean loadFromManager		= false;
	public int radiusXY 				= 17;
	public int radiusZ 					= 35;
	public int beadsCount				= 100;
	public boolean addToManager			= false;
	public String avgMethod 			= psfAvgMethod[1];
	public String psfChannelLayout		= PSF_CHANNEL_LAYOUTS[0];
	public boolean psfFlipRight			= true;
	public double psfShellFraction		= 0.15d;
	public double psfMinSnr				= 5.0d;
	public double psfMinSbr				= 1.5d;
	public double psfMaxCenterOffset	= 0.5d;
	public double psfSaturationLevel	= 0.0d;
	public boolean psfRejectNeighbors	= true;
	//public boolean extendBorder 		= false;
	//public String roiOption 			= roiOptions[0];
	public String roiPath				= loadRoiMessage;
	public String zRangeStr				= "1-end";
	
	
	//	parameters for batch processing
	public String inputDir				= "";
	public String keywords				= "";
	public boolean recursive			= false;
	protected boolean doDeskew			= true;
	protected boolean makeTimeLapse		= false;
	protected boolean displayTimeLapse	= true;		// by default, always display time-lapse if makeTimeLapse is enabled
	public boolean saveSeparate			= false;
	public String fileExistStr			= fileExistOptions[0];
	public String logPath				= "";
	
	//	parameters for folder watch
	public String watchDir				= "";
	public int maxWait					= 1000;
	public boolean processOld			= false;
	public boolean overwriteExist		= false;
	
	// parameters for TCP-IP client
	public int port						= 5020;
	
	//	parameters for volume transformation	//TODO: try preview
	protected int nTransform			= 0;
 	protected List<Boolean> apply		= new ArrayList<Boolean>();
 	protected List<String> type			= new ArrayList<String>();
 	protected List<String> axis			= new ArrayList<String>();
 	protected List<Double> value		= new ArrayList<Double>();
 	protected List<Boolean> display		= new ArrayList<Boolean>();
 	protected String axisPartition		= "";
 	protected String axisCombine		= "";
	
	//	parameters for axis permutation			//TODO: try preview
 	protected boolean flipX				= false;
 	protected boolean flipY				= false;
 	protected boolean flipZ				= false;
 	protected boolean foldX				= false;
 	protected String permuteStr			= permuteOptions[5];
	
	//	parameters for axis projection			//TODO: try preview
 	public boolean projX				= false;
 	public boolean projY				= false;
 	public boolean projZ				= false;
 	public boolean maxProj				= false;
 	public boolean avgProj				= false;
 	public boolean minProj				= false;
 	public boolean sumProj				= false;
 	public boolean medProj				= false;
 	public boolean stdProj				= false;
	protected List<String> projAxes		= new ArrayList<String>();
	protected List<String> projTypes	= new ArrayList<String>();
	protected String projAxis			= null;		// parameter local to Projection class ?
	protected String projType			= null;		// parameter local to Projection class ?

	
	//	parameters for debugging
	protected boolean doInverse			= false;	// whether to perform inverse transform
	protected boolean doVirtual			= false;	// imglib2 transform result as Virtual stack
	public boolean tryGPU				= true;		// do processing on GPU
	protected boolean stepTransform		= false;	// do transformation step by step: shear, scale, (translate), rotate, (translate)
	protected boolean autoPartition		= true;		// whether to automatically partition data
	protected int numPartition			= 8;		// in case of manual setup, the number of data partitions
	
	

	
	
	
	/** 		generic constructor for Parameter class
	 * 
	 * @param obj
	 */
	Parameter(String obj) {
		instance = this;
		this.obj = obj;
		// make use of scijava parameter persistence storage	
		DefaultPrefService prefs = new DefaultPrefService();
		// parameters for deskew image
		xyPixelSize =	prefs.getDouble(Double.class, 		"OPM-"+ obj +"-xyPixelSize", 	xyPixelSize);
		zStepSize =		prefs.getDouble(Double.class, 		"OPM-"+ obj +"-zStepSize", 		zStepSize);
		opmAngle =		prefs.getDouble(Double.class, 		"OPM-"+ obj +"-opmAngle", 		opmAngle);
		deskewmFile =	prefs.get(String.class, 			"OPM-"+ obj +"-deskewmFile", 	deskewmFile);
		doInverse =		prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-doInverse", 		doInverse);
		channelStr = 	prefs.get(String.class, 			"OPM-"+ obj +"-channelStr", 	channelStr);
		alignmFile =  	prefs.get(String.class, 			"OPM-"+ obj +"-alignmFile", 	alignmFile);
		projX =			prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-projX", 			projX);
		projY = 		prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-projY", 			projY);
		projZ = 		prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-projZ", 			projZ);
		maxProj = 		prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-maxProj", 		maxProj);
		avgProj = 		prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-avgProj", 		avgProj);
		minProj = 		prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-minProj", 		minProj);
		sumProj = 		prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-sumProj", 		sumProj);
		medProj =		prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-medProj", 		medProj);
		stdProj =		prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-stdProj", 		stdProj);
	saveDeskewImage = 	prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-saveDeskewImage",	saveDeskewImage);
	saveDeskewMatrix =	prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-saveDeskewMatrix",	saveDeskewMatrix);
	saveAlignMatrix =	prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-saveAlignMatrix",	saveAlignMatrix);
		// parameters for deconvolution
	    deconvMethod =	prefs.get(String.class, 			"OPM-"+ obj +"-deconvMethod", 	deconvMethod);
	    numIter =		prefs.getInt(Integer.class,			"OPM-"+ obj +"-numIter", 		numIter);
	    regFactor =		prefs.getDouble(Double.class, 		"OPM-"+ obj +"-regFactor", 		regFactor);
	    //nonCirclulant =	prefs.getBoolean(Boolean.class,		"OPM-"+ obj +"-nonCirclulant", 	nonCirclulant);
	    loadFromFile =	prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-loadFromFile", 	loadFromFile);		
        beadsPath = 	prefs.get(String.class, 			"OPM-"+ obj +"-beadsPath", 		beadsPath);
        imageType = 	prefs.get(String.class, 			"OPM-"+ obj +"-imageType", 		imageType);
        loadFromManager=prefs.getBoolean(Boolean.class,		"OPM-"+ obj +"-loadFromManager",loadFromManager); 
        //roiOption = 	prefs.get(String.class, 			"OPM-"+ obj +"-roiOption", 		roiOption);
        radiusXY = 		prefs.getInt(Integer.class, 		"OPM-"+ obj +"-radiusXY", 		radiusXY);
        radiusZ = 		prefs.getInt(Integer.class, 		"OPM-"+ obj +"-radiusZ", 		radiusZ);
        beadsCount =	prefs.getInt(Integer.class,			"OPM-"+ obj +"-beadsCount", 	beadsCount);
        addToManager =	prefs.getBoolean(Boolean.class,		"OPM-"+ obj +"-addToManager", 	addToManager);
        avgMethod = 	prefs.get(String.class, 			"OPM-"+ obj +"-avgMethod", 		avgMethod);
		psfChannelLayout = prefs.get(String.class, "OPM-"+ obj +"-psfChannelLayout", psfChannelLayout);
		psfFlipRight = prefs.getBoolean(Boolean.class, "OPM-"+ obj +"-psfFlipRight", psfFlipRight);
		psfShellFraction = prefs.getDouble(Double.class, "OPM-"+ obj +"-psfShellFraction", psfShellFraction);
		psfMinSnr = prefs.getDouble(Double.class, "OPM-"+ obj +"-psfMinSnr", psfMinSnr);
		psfMinSbr = prefs.getDouble(Double.class, "OPM-"+ obj +"-psfMinSbr", psfMinSbr);
		psfMaxCenterOffset = prefs.getDouble(Double.class, "OPM-"+ obj +"-psfMaxCenterOffset", psfMaxCenterOffset);
		psfSaturationLevel = prefs.getDouble(Double.class, "OPM-"+ obj +"-psfSaturationLevel", psfSaturationLevel);
		psfRejectNeighbors = prefs.getBoolean(Boolean.class, "OPM-"+ obj +"-psfRejectNeighbors", psfRejectNeighbors);
        roiPath   = 	prefs.get(String.class, 			"OPM-"+ obj +"-roiPath", 		roiPath);
        //extendBorder =	prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-extendBorder", 	extendBorder);
		// parameters for batch processing
		inputDir = 		prefs.get(String.class, 			"OPM-"+ obj +"-inputDir", 		inputDir);
		keywords = 		prefs.get(String.class, 			"OPM-"+ obj +"-keywords", 		keywords);
		recursive = 	prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-recursive", 		recursive);
		doDeskew = 		prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-doDeskew", 		doDeskew);
		makeTimeLapse = prefs.getBoolean(Boolean.class,		"OPM-"+ obj +"-makeTimeLapse", 	makeTimeLapse);
		fileExistStr =	prefs.get(String.class, 			"OPM-"+ obj +"-fileExistStr", 	fileExistStr);
		saveDir = 		prefs.get(String.class, 			"OPM-"+ obj +"-saveDir", 		saveDir);
		saveToSame =	prefs.getBoolean(Boolean.class,		"OPM-"+ obj +"-saveToSame", 	saveToSame);
		saveSeparate = 	prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-saveSeparate", 	saveSeparate);
		logPath = 		prefs.get(String.class, 			"OPM-"+ obj +"-logPath", 		logPath);
		// parameters for folder watcher
		watchDir = 		prefs.get(String.class, 			"OPM-"+ obj +"-watchDir", 		watchDir);
		processOld = 	prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-processOld", 	processOld);	//TODO: move to batch processing
		overwriteExist =prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-overwriteExist", overwriteExist);//TODO: move to batch processing
		maxWait = 		prefs.getInt(Integer.class, 		"OPM-"+ obj +"-maxWait", 		maxWait);
		// parameters for TCP-IP listener
		port =			prefs.getInt(Integer.class, 		"OPM-"+ obj +"-port", 			port);
		// parameters for permutation
		flipX = 		prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-flipX", 			flipX);
		flipY = 		prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-flipY", 			flipY);
		flipZ = 		prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-flipZ", 			flipZ);
		foldX = 		prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-foldX", 			foldX);
		permuteStr = 	prefs.get(String.class, 			"OPM-"+ obj +"-permuteStr",		permuteStr);
		// parameters by default hidden to user, only accessed for debugging purposes
		doVirtual = 	prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-doVirtual", 		doVirtual);
		stepTransform = prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-stepTransform",	stepTransform);
		tryGPU = 		prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-tryGPU", 		tryGPU);
		autoPartition = prefs.getBoolean(Boolean.class, 	"OPM-"+ obj +"-autoPartition", 	autoPartition);
		numPartition = 	prefs.getInt(Integer.class, 		"OPM-"+ obj +"-numPartition", 	numPartition);
	}
	
	public void storeParam () {
		// make use of scijava parameter persistence storage
		DefaultPrefService prefs = new DefaultPrefService();
		String obj = this.obj;
		// parameters for deskew image
		prefs.put(Double.class,  	"OPM-"+ obj +"-xyPixelSize",   	xyPixelSize);
		prefs.put(Double.class,  	"OPM-"+ obj +"-zStepSize",     	zStepSize);
		prefs.put(Double.class,  	"OPM-"+ obj +"-opmAngle",      	opmAngle);
		prefs.put(String.class, 	"OPM-"+ obj +"-deskewmFile", 	deskewmFile);
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-doInverse",     	doInverse);
		prefs.put(String.class,  	"OPM-"+ obj +"-channelStr", 	channelStr);
		prefs.put(String.class, 	"OPM-"+ obj +"-alignmFile", 	alignmFile);
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-projX",         	projX);
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-projY",         	projY);
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-projZ",         	projZ);
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-maxProj",       	maxProj);
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-avgProj",       	avgProj);
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-minProj",       	minProj);
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-sumProj",       	sumProj);
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-medProj",       	medProj);
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-stdProj",       	stdProj);
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-saveDeskewImage",	saveDeskewImage);
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-saveDeskewMatrix",	saveDeskewMatrix);
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-saveAlignMatrix",	saveAlignMatrix);
		// parameters for deconvolution
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-loadFromFile", 	loadFromFile);
		prefs.put(String.class, 	"OPM-"+ obj +"-beadsPath", 		beadsPath);
		prefs.put(String.class, 	"OPM-"+ obj +"-imageType", 		imageType);
		prefs.put(Boolean.class,	"OPM-"+ obj +"-loadFromManager",loadFromManager); 
        prefs.put(Integer.class, 	"OPM-"+ obj +"-radiusXY", 		radiusXY);
        prefs.put(Integer.class, 	"OPM-"+ obj +"-radiusZ", 		radiusZ);
        prefs.put(Integer.class,	"OPM-"+ obj +"-beadsCount", 	beadsCount);
        prefs.put(Boolean.class,	"OPM-"+ obj +"-addToManager", 	addToManager);
        prefs.put(String.class, 	"OPM-"+ obj +"-avgMethod", 		avgMethod);
		prefs.put(String.class, "OPM-"+ obj +"-psfChannelLayout", psfChannelLayout);
		prefs.put(Boolean.class, "OPM-"+ obj +"-psfFlipRight", psfFlipRight);
		prefs.put(Double.class, "OPM-"+ obj +"-psfShellFraction", psfShellFraction);
		prefs.put(Double.class, "OPM-"+ obj +"-psfMinSnr", psfMinSnr);
		prefs.put(Double.class, "OPM-"+ obj +"-psfMinSbr", psfMinSbr);
		prefs.put(Double.class, "OPM-"+ obj +"-psfMaxCenterOffset", psfMaxCenterOffset);
		prefs.put(Double.class, "OPM-"+ obj +"-psfSaturationLevel", psfSaturationLevel);
		prefs.put(Boolean.class, "OPM-"+ obj +"-psfRejectNeighbors", psfRejectNeighbors);
        prefs.put(String.class, 	"OPM-"+ obj +"-roiPath", 		roiPath);
        //prefs.put(Boolean.class, 	"OPM-"+ obj +"-extendBorder", 	extendBorder);
        prefs.put(String.class, 	"OPM-"+ obj +"-deconvMethod", 	deconvMethod);
	    prefs.put(Integer.class,	"OPM-"+ obj +"-numIter", 		numIter);
	    prefs.put(Double.class, 	"OPM-"+ obj +"-regFactor", 		regFactor);
	  //prefs.put(Boolean.class,	"OPM-"+ obj +"-nonCirclulant", 	nonCirclulant);
		// parameters for batch processing
		prefs.put(String.class, 	"OPM-"+ obj +"-inputDir", 		inputDir);
		prefs.put(String.class, 	"OPM-"+ obj +"-keywords", 		keywords);
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-recursive", 		recursive);
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-doDeskew", 		doDeskew);
		prefs.put(Boolean.class,	"OPM-"+ obj +"-makeTimeLapse", 	makeTimeLapse);
		prefs.put(String.class, 	"OPM-"+ obj +"-fileExistStr", 	fileExistStr);
		prefs.put(String.class, 	"OPM-"+ obj +"-saveDir", 		saveDir);
		prefs.put(Boolean.class,	"OPM-"+ obj +"-saveToSame", 	saveToSame);
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-saveSeparate", 	saveSeparate);
		prefs.put(String.class, 	"OPM-"+ obj +"-logPath", 		logPath);
		// parameters for folder watcher
		prefs.put(String.class, 	"OPM-"+ obj +"-watchDir", 		watchDir);
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-processOld", 	processOld);	//TODO: move to batch processing
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-overwriteExist", overwriteExist);//TODO: move to batch processing
		prefs.put(Integer.class, 	"OPM-"+ obj +"-maxWait", 		maxWait);
		// parameters for TCP-IP listener
		prefs.put(Integer.class, 	"OPM-"+ obj +"-port", 			port);
		// parameters for permutation
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-flipX", 			flipX);
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-flipY", 			flipY);
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-flipZ", 			flipZ);
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-foldX", 			foldX);
		prefs.put(String.class, 	"OPM-"+ obj +"-permuteStr",		permuteStr);
		// parameters by default hidden to user, only accessed for debugging purposes
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-doVirtual", 		doVirtual);
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-stepTransform",	stepTransform);
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-tryGPU", 		tryGPU);
		prefs.put(Boolean.class, 	"OPM-"+ obj +"-autoPartition", 	autoPartition);
		prefs.put(Integer.class, 	"OPM-"+ obj +"-numPartition", 	numPartition);	
	}
	
	
	/**			return current Parameter object
	 * <p>		create new empty Parameter object if null exist
	 * 
	 * @return
	 */
	public static Parameter getInstance () {
		if (null == instance)
			return new Parameter(""); // TODO check if this is correct?
		else
			return (Parameter) instance;
	}
	
	
	/**				Create non-modal parameter dialog for Deskew + obj + command
	 * 
	 * @param command
	 * @param pfr
	 * <p>
	 * @return
	 */
	public GenericDialog deskew_image ( String command, PlugInFilterRunner pfr ) {
		// create non-modal parameter dialog with preview functionality
		GenericDialog gd = new NonBlockingGenericDialog(command);
		//gd.enableYesNoCancel("OK", "save setting");
		gd.setBackground( frameColor );
		int length_string_field = 35;
		int left_inset_checkbox = 119;
		int top_inset_section = 20;
		
        gd.addImageChoice("select active image", this.impInput.getTitle());
        
        gd.setInsets(top_inset_section, 0, 5);
        gd.addNumericField("XY pixel size", xyPixelSize, 1, 5, "nm");
        gd.addSlider("Z step size (nm)", 0, 530, zStepSize, 0.1);
        gd.addSlider("OPM angle (°)", -90, 90, opmAngle, 0.1);
        if (null == deskewmFile || "" == deskewmFile) deskewmFile = loadSettingMessage;
        gd.addFileField("", deskewmFile, length_string_field);
        
        gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("inverse transform", doInverse);
		
		gd.setInsets(top_inset_section, 0, 10);
		gd.addChoice("channel option", channelOptions, channelStr);
		gd.setInsets(0, 0, 0);
		if (null == alignmFile || "" == alignmFile) alignmFile = loadAlignMessage;
		gd.addFileField("align matrix", alignmFile, length_string_field);
		
		gd.setInsets(top_inset_section, 15, 0);
		gd.addMessage("show projection image(s):");
		String[] label_axis = {"along_X      ", "along_Y      ", "along_Z      "};
		boolean[] state_axis = {projX, projY, projZ};
		gd.setInsets(0, left_inset_checkbox, 5);
		gd.addCheckboxGroup(1, 3, label_axis, state_axis);
		
		String[] label_type = {"maximum", "mean"};//, "minimum", "sum", "median", "standard deviation"};
		boolean[] state_type = {maxProj, avgProj};//, minProj, sumProj, medProj, stdProj};
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckboxGroup(1, 2, label_type, state_type);
		
		gd.setInsets(top_inset_section, left_inset_checkbox, 10);
		gd.addButton("save deskew setting", new ActionListener() { 
			public void actionPerformed(ActionEvent e) { deskew_saveSetting (); }
		});
		
		gd.setInsets(top_inset_section, left_inset_checkbox, 0);
        gd.addPreviewCheckbox( pfr );
        
		gd.addHelp(Help.deskew);

        return gd;
	}
	/**			save transformation(s) to a csv file
	 * 
	 * @param parameter
	 * @param dialog
	 */
	public void deskew_saveSetting () {
		GenericDialog gd = new GenericDialogPlus ("Save Deskew Setting");
		gd.setBackground( frameColor );
		int length_string_field = 35;
		int left_inset_checkbox = 87;
		
		gd.addDirectoryField("save to folder", saveDir, 30+length_string_field);
		gd.setInsets(10, left_inset_checkbox, 5);
		gd.addCheckbox("save deskew matrix (affine 3D)", saveDeskewMatrix);
		gd.addToSameRow();
		gd.addStringField("", fileNameDeskew, length_string_field);
		gd.setInsets(5, left_inset_checkbox, 10);
		gd.addCheckbox("save alignment matrix (rigid 2D)", saveAlignMatrix);
		gd.addToSameRow();
		gd.addStringField("", fileNameAlign, length_string_field);
        gd.showDialog();
        if (gd.wasCanceled()) return;
        saveDir = gd.getNextString();
        saveDeskewMatrix = gd.getNextBoolean();
        fileNameDeskew = gd.getNextString();
        saveAlignMatrix = gd.getNextBoolean();
        fileNameAlign = gd.getNextString();
        // parse save path
        File saveFolder = new File(saveDir);
        if ( !saveFolder.exists() ) return;
        if ( !fileNameDeskew.endsWith(".csv") ) fileNameDeskew += ".csv";
        if ( !fileNameAlign.endsWith(".csv") ) fileNameAlign += ".csv";
        storeParam (); 	// this here only saves saveDir
	}
	
	
	/**				Create parameter dialog for Batch Processing command
	 * 
	 * @return
	 */
	public boolean deskew_batch () {
		// create parameter dialog
		NonBlockingGenericDialog gd = new NonBlockingGenericDialog("Deskew Batch Processing");
		gd.setBackground( frameColor );
		int length_string_field = 35;
		int left_inset_checkbox = 95;
		int top_inset_section = 20;
		
		gd.addDirectoryField("input folder...", inputDir, length_string_field);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addMessage("file name contains(separate mulitple by  \",\")");
		gd.addStringField("", keywords, length_string_field);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("including subfolder(s)", recursive);
		
		// deskew, projection, geometry
		//TODO: add flexible batch processing options: by invoking 2nd dialog:?
		// deskew, transform, projection, geometry, combine channel? form time lapse
		//gd.setInsets(10, 95, 5);
		//gd.addCheckbox("deskew image", doDeskew);
		gd.setInsets(top_inset_section, 0, 5);
		gd.addNumericField("XY pixel size", xyPixelSize, 1, 5, "nm");
		gd.addNumericField("Z step size", zStepSize, 1, 5, "nm");
		gd.addNumericField("OPM angle", opmAngle, 1, 5, "°");
		gd.addFileField("", deskewmFile, length_string_field);
		
		gd.setInsets(top_inset_section, 0, 5);
		gd.addChoice("channel option", channelOptions, channelStr);
		gd.addFileField("align matrix", alignmFile, 35);
		
		gd.setInsets(top_inset_section, 0, 5);
		gd.addMessage("\tcreate projection image(s):");
		String[] label_axis = {"along_X         ", "along_Y         ", "along_Z         "};
		boolean[] state_axis = {projX, projY, projZ};
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckboxGroup(1, 3, label_axis, state_axis);
		String[] label_type = {"maximum", "mean"};//, "minimum", "sum", "median", "standard deviation"};
		boolean[] state_type = {maxProj, avgProj};//, minProj, sumProj, medProj, stdProj};
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckboxGroup(1, 2, label_type, state_type);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("combine as time lapse", makeTimeLapse);
		
		gd.setInsets(top_inset_section, 0, 5);
		gd.addDirectoryField("save to...", saveDir, length_string_field);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("save result to the same (data) folder", saveToSame);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("save deskew image", saveDeskewImage);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("separate results to sub-folders", saveSeparate);
		gd.addChoice("if result exist", fileExistOptions, fileExistStr);
		gd.addHelp(Help.batch);
		//gd.addCheckbox("overwrite exist results", overwriteExist);
		//gd.addCheckbox("save log", saveLog);
		//gd.addDirectoryOrFileField("log file path", logPath);
		gd.showDialog();
        if (gd.wasCanceled()) return false;
        inputDir = 			gd.getNextString();
        keywords = 			gd.getNextString();
        recursive = 		gd.getNextBoolean();
        //doDeskew = 			gd.getNextBoolean();
        xyPixelSize = 		gd.getNextNumber();
        zStepSize = 		gd.getNextNumber();
        opmAngle = 			gd.getNextNumber();
        deskewmFile = 		gd.getNextString();
        channelStr =	 	gd.getNextChoice();
        alignmFile =		gd.getNextString();
        projX = 			gd.getNextBoolean();
        projY = 			gd.getNextBoolean();
        projZ = 			gd.getNextBoolean();
        maxProj = 			gd.getNextBoolean();
        avgProj = 			gd.getNextBoolean();
        //minProj = 			gd.getNextBoolean();
        //sumProj = 			gd.getNextBoolean();
        //medProj = 			gd.getNextBoolean();
        //stdProj = 			gd.getNextBoolean();
        makeTimeLapse = 	gd.getNextBoolean();
        saveDir = 			gd.getNextString();
        saveToSame =		gd.getNextBoolean();
        saveDeskewImage =	gd.getNextBoolean();
        saveSeparate = 		gd.getNextBoolean();
        //overwriteExist = 	gd.getNextBoolean();
        fileExistStr = 		gd.getNextChoice();
        //logPath = 			gd.getNextString();
        // store parameter values
        if (saveToSame)	{saveDir = ""; recursive = false;}
        storeParam ();
		return true;
	}
	
	
	/**				Create parameter dialog for generate PSF from Beads image(s)
	 * 
	 * @return
	 */
	public boolean deconv_psf () {
		// create parameter dialog
		NonBlockingGenericDialog gd = new NonBlockingGenericDialog("Generate experimental PSF from Beads Stack");
		gd.setBackground( frameColor );
		
		gd.setInsets(0, 55, 5);
		gd.addMessage("Beads image setup:", new Font("Dialog", Font.BOLD, 12));
		impInput = WindowManager.getCurrentImage();
		if ( null != impInput ) {
			gd.setInsets(0, 91, 0);
			gd.addMessage("select active image");
			gd.addImageChoice("", impInput.getTitle());
			gd.setInsets(0, 93, 0);
			gd.addCheckbox("or load from file", loadFromFile);
		}
		gd.addDirectoryField("folder path", beadsPath, 20);
		gd.setInsets(0, 93, 5);
		gd.addCheckbox("including subfolder(s)", recursive);
		gd.addChoice("image type", imageTypes, imageType);
		gd.addChoice("channel layout", PSF_CHANNEL_LAYOUTS, psfChannelLayout);
		gd.addCheckbox("flip the mirrored right half", psfFlipRight);
		gd.setInsets(5, 90, 10);
		gd.addButton("prepare beads image", new ActionListener() { 
			public void actionPerformed(ActionEvent e) { deconv_prepareBeadsImage (instance, gd); }
		});
		if ( null != impInput ) {
			gd.setInsets(15, 40, 5);
			gd.addCheckbox("Load beads center position from ROI Manager", loadFromManager);
		}
		
		gd.setInsets(25, 55, 5);
		gd.addMessage("PSF image setup:", new Font("Dialog", Font.BOLD, 12));
		gd.addNumericField("XY radius", radiusXY, 0, 5, "pixel");
		gd.addNumericField("Z radius", radiusZ, 0, 5, "pixel");
		gd.addNumericField("~ beads count", beadsCount, 0, 5, "");
		if ( null != impInput ) {
			gd.setInsets(0, 93, 0);
			gd.addCheckbox("point to ROI Manager", addToManager);
		}
		gd.setInsets(0, 0, 25);
		gd.addChoice("result as", psfAvgMethod, avgMethod);
		gd.setInsets(0, 55, 5);
		gd.addMessage("Bead quality control:", new Font("Dialog", Font.BOLD, 12));
		gd.addNumericField("background shell fraction", psfShellFraction, 2);
		gd.addNumericField("minimum peak SNR", psfMinSnr, 2);
		gd.addNumericField("minimum peak/background ratio", psfMinSbr, 2);
		gd.addNumericField("maximum normalized center offset", psfMaxCenterOffset, 2);
		gd.addNumericField("saturation level (0 = native maximum)", psfSaturationLevel, 1);
		gd.addCheckbox("reject candidates with a nearby bead", psfRejectNeighbors);
		//gd.addCheckbox("extend PSF border", extendBorder);
		gd.addHelp(Help.PSF);
		gd.showDialog();
        if (gd.wasCanceled()) return false;
        
        if ( null != impInput ) {
        	impInput = 		gd.getNextImage();
        	loadFromFile =	gd.getNextBoolean();
        }
        beadsPath = 	gd.getNextString();
        recursive =		gd.getNextBoolean();
        imageType = 	gd.getNextChoice();
		psfChannelLayout = gd.getNextChoice();
		psfFlipRight = gd.getNextBoolean();
        if ( null != impInput ) {
        	loadFromManager = gd.getNextBoolean();
		}
        radiusXY = 		(int) gd.getNextNumber();
        radiusZ = 		(int) gd.getNextNumber();
        beadsCount =	(int) gd.getNextNumber();
        if ( null != impInput ) {
        	addToManager =	gd.getNextBoolean();
        }
        avgMethod = 	gd.getNextChoice();
		psfShellFraction = Math.max(0.01d, Math.min(0.49d, gd.getNextNumber()));
		psfMinSnr = Math.max(0.0d, gd.getNextNumber());
		psfMinSbr = Math.max(0.0d, gd.getNextNumber());
		psfMaxCenterOffset = Math.max(0.0d, gd.getNextNumber());
		psfSaturationLevel = Math.max(0.0d, gd.getNextNumber());
		psfRejectNeighbors = gd.getNextBoolean();
        //extendBorder = 	gd.getNextBoolean();
        // store parameter values
        storeParam ();
		return true;
	}
	
	
	public void deconv_prepareBeadsImage ( Parameter parameter, GenericDialog dialog ) {
		this.obj = parameter.obj;
		GenericDialogPlus gd = new GenericDialogPlus ("Beads Image Preparation");
		gd.setBackground( frameColor );

		gd.setInsets(0, 55, 5);
		gd.addMessage("deskew parameters:", new Font("Dialog", Font.BOLD, 12));
		
		gd.setInsets(5, 35, 0);
		gd.addNumericField("XY pixel size", xyPixelSize, 1, 5, "nm");
		gd.setInsets(5, 35, 0);
		gd.addNumericField("Z step size", zStepSize, 1, 5, "nm");
		gd.setInsets(5, 35, 0);
		gd.addNumericField("OPM angle", opmAngle, 1, 3, "°");
		
		gd.setInsets(25, 55, 5);
		gd.addMessage("ROI options:", new Font("Dialog", Font.BOLD, 12));
		gd.setInsets(0, 80, 0);
		gd.addMessage("ROI from input image if exist");
		//if (null == roiPath || "" == roiPath) 
			roiPath = loadRoiMessage;
		gd.addFileField("or", roiPath, 20);
		gd.addStringField("Z range", zRangeStr, 5);
		
		gd.addHelp(Help.PSF_prepare);
		gd.showDialog();
		if (gd.wasCanceled()) return;
		
		xyPixelSize =	gd.getNextNumber();
        zStepSize = 	gd.getNextNumber();
        opmAngle = 		gd.getNextNumber();
        roiPath =		gd.getNextString();
        zRangeStr =		gd.getNextString();
        
        storeParam ();
	}
	
	
	/**				Create parameter dialog for generate PSF from Beads image(s)
	 * 
	 * @return
	 */
	public boolean deconv_rlfft () {
		// create parameter dialog
		String[] titles = WindowManager.getImageTitles();
		if (null == titles || titles.length <= 1) {
			IJ.error("Need both the input image and PSF image open in Fiji to perform deconvolution.");
			return false;
		}
		String imp_title = WindowManager.getCurrentImage().getTitle();
		String PSF_title = "";
		for (int i=0; i<titles.length; i++) {
			if (titles[i].toLowerCase().contains("psf")) {
				PSF_title = titles[i];
				break;
			}
		}
		NonBlockingGenericDialog gd = new NonBlockingGenericDialog("Deconvolution of OPM data");
		gd.setBackground( frameColor );
		gd.addImageChoice("input", imp_title);
		gd.addImageChoice("PSF", PSF_title);
		gd.addChoice("method", deconvMethodChoices, deconvMethod);
		gd.addNumericField("number of iterations", numIter);
		gd.addSlider("regularization factor", 0.00, 5e-3, regFactor, 1e-4);
		//gd.addNumericField("regularization factor", 0.0, 3);
		//gd.addCheckbox("non circulant", nonCirclulant);
		gd.addHelp(Help.deconv);
		gd.showDialog();
        if (gd.wasCanceled()) return false;
        impInput = 			gd.getNextImage();
        impPSF = 			gd.getNextImage();
        deconvMethod = 		gd.getNextChoice();
        numIter = 	  (int) gd.getNextNumber();
        regFactor =			gd.getNextNumber();
        //boolean nonCirclulant = gd.getNextBoolean();
        // store parameter values
        storeParam ();
		return true;
	}
	
	
	/**				Create parameter dialog for Folder Watcher watcher setup command
	 * 
	 * @return
	 */
	public boolean watcher_setupWatch () {
		// create parameter dialog
		GenericDialogPlus gd = new GenericDialogPlus("OPM Folder Watcher");
		gd.setBackground( frameColor );
		int length_string_field = 35;
		int left_inset_checkbox = 248;
		
		gd.addDirectoryField("watch folder...", watchDir, length_string_field);
		gd.addStringField("file name contains(separate mulitple by \",\")", keywords, length_string_field);
		gd.setInsets(0, left_inset_checkbox, 5);
		gd.addCheckbox("also process existing files in folder", processOld);
		gd.setInsets(0, left_inset_checkbox, 5);
		gd.addCheckbox("overwrite exist results", overwriteExist);
		gd.addNumericField("max file writing delay", maxWait, 0, 5, "millisecond");
		gd.addHelp(Help.watch_folder);
		//gd.addDirectoryOrFileField("log file path", logPath);
		gd.showDialog();
        if (gd.wasCanceled()) return false;
        watchDir = 			gd.getNextString();
        keywords = 			gd.getNextString();
        processOld = 		gd.getNextBoolean();    
        overwriteExist = 	gd.getNextBoolean();
        maxWait =     (int) gd.getNextNumber();
        //logPath = 			gd.getNextString();
        // store parameter values
        storeParam ();
		return true;
	}
	
	
	/**				Create parameter dialog for Folder Watcher processing setup command
	 * 
	 * @return
	 */
	public boolean watcher_setupProcessing () {
		// create parameter dialog
		GenericDialogPlus gd = new GenericDialogPlus("OPM Processing Setup");
		gd.setBackground( frameColor );
		int length_string_field = 35;
		int left_inset_checkbox = 95;
		int top_inset_section = 20;
		
		
		/*
		gd.addNumericField("XY pixel size", xyPixelSize, 1, 5, "nm");
		gd.addNumericField("Z step size", zStepSize, 1, 5, "nm");
		gd.addNumericField("OPM angle", opmAngle, 1, 5, "°");
		gd.addFileField("", deskewmFile, 35);
		
		gd.addChoice("channel option", channelOptions, channelStr);
		gd.addFileField("align matrix", alignmFile, 35);
		*/
		
		gd.addChoice("channel option", channelOptions, channelStr);
		gd.addFileField("align matrix", alignmFile, length_string_field);
		
		gd.setInsets(top_inset_section, 0, 0);
		gd.addMessage("\tcreate projection image(s):");
		String[] label_axis = {"along_X", "along_Y", "along_Z"};
		boolean[] state_axis = {projX, projY, projZ};
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckboxGroup(1, 3, label_axis, state_axis);		
		String[] label_type = {"maximum", "mean"};//, "minimum", "sum", "median", "standard deviation"};
		boolean[] state_type = {maxProj, avgProj};//, minProj, sumProj, medProj, stdProj};				
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckboxGroup(1, 2, label_type, state_type);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("combine as time lapse", makeTimeLapse);
		
		gd.setInsets(top_inset_section, 0, 5);
		gd.addDirectoryField("save to...", saveDir, length_string_field);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("save result to the same (data) folder", saveToSame);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("save deskew image", saveDeskewImage);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("separate results to sub-folders", saveSeparate);
		gd.addHelp(Help.watch_process);
		gd.showDialog();
        if (gd.wasCanceled()) return false;
        /*
        xyPixelSize = 	gd.getNextNumber();
        zStepSize = 	gd.getNextNumber();
        opmAngle = 		gd.getNextNumber();
        deskewmFile =	gd.getNextString();
        channelStr = 	gd.getNextChoice();
        alignmFile =	gd.getNextString();
        */
        
        channelStr =	 	gd.getNextChoice();
        alignmFile =		gd.getNextString();
        
        projX = 		gd.getNextBoolean();
        projY = 		gd.getNextBoolean();
        projZ = 		gd.getNextBoolean();
        maxProj = 		gd.getNextBoolean();
        avgProj = 		gd.getNextBoolean();
        /*
        minProj = 		gd.getNextBoolean();
        sumProj = 		gd.getNextBoolean();
        medProj = 		gd.getNextBoolean();
        stdProj = 		gd.getNextBoolean();
        */
        makeTimeLapse = gd.getNextBoolean();
        saveDir = 		gd.getNextString();
        saveToSame =	gd.getNextBoolean();
        saveDeskewImage=gd.getNextBoolean();
        saveSeparate = 	gd.getNextBoolean();
        
        // store parameter values
        if (saveToSame)	saveDir = "";
        storeParam ();
		return true;
	}
	
	
	public boolean tcpip () {
		// create parameter dialog
		GenericDialogPlus gd = new GenericDialogPlus("OPM TCP-IP Listener");
		gd.setBackground( frameColor );
		int length_string_field = 35;
		int left_inset_checkbox = 95;
		int top_inset_section = 20;
		/*
		gd.addNumericField("TCP IP port:", port, 0, 5, "");
		
		gd.addNumericField("XY pixel size", xyPixelSize, 1, 5, "nm");
		gd.addNumericField("Z step size", zStepSize, 1, 5, "nm");
		gd.addNumericField("OPM angle", opmAngle, 1, 5, "°");
		gd.addFileField("", deskewmFile, 35);
		
		gd.addChoice("channel option", channelOptions, channelStr);
		gd.addFileField("align matrix", alignmFile, 35);
		*/
		
		/*
		gd.addChoice("channel option", channelOptions, channelStr);
		gd.addFileField("align matrix", alignmFile, length_string_field);
		
		gd.setInsets(top_inset_section, 0, 5);
		gd.addMessage("\tcreate projection image(s):");
		String[] label_axis = {"along_X", "along_Y", "along_Z"};
		boolean[] state_axis = {projX, projY, projZ};
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckboxGroup(1, 3, label_axis, state_axis);		
		String[] label_type = {"maximum", "mean"};
		boolean[] state_type = {maxProj, avgProj};				
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckboxGroup(1, 2, label_type, state_type);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("combine as time lapse", makeTimeLapse);
		
		gd.setInsets(top_inset_section, 0, 5);
		gd.addDirectoryField("save to...", saveDir, length_string_field);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("save result to the same (data) folder", saveToSame);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("save deskew image", saveDeskewImage);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("separate results to sub-folders", saveSeparate);
		*/
		
		gd.addChoice("channel option", channelOptions, channelStr);
		gd.addFileField("align matrix", "", length_string_field);
		
		gd.setInsets(top_inset_section, 0, 5);
		gd.addMessage("\tcreate projection image(s):");
		String[] label_axis = {"along_X", "along_Y", "along_Z"};
		boolean[] state_axis = {true, true, true};
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckboxGroup(1, 3, label_axis, state_axis);		
		String[] label_type = {"maximum", "mean"};
		boolean[] state_type = {true, avgProj};				
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckboxGroup(1, 2, label_type, state_type);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("combine as time lapse", true);
		
		gd.setInsets(top_inset_section, 0, 5);
		gd.addDirectoryField("save to...", "", length_string_field);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("save result to the same (data) folder", true);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("save deskew image", true);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("separate results to sub-folders", true);
		
		gd.addHelp(Help.tcpip);
		gd.showDialog();
        if (gd.wasCanceled()) return false;
        /*
        port =			(int) gd.getNextNumber();
        xyPixelSize = 	gd.getNextNumber();
        zStepSize = 	gd.getNextNumber();
        opmAngle = 		gd.getNextNumber();
        deskewmFile =	gd.getNextString();
        channelStr = 	gd.getNextChoice();
        alignmFile =	gd.getNextString();
        */
        
        channelStr =	gd.getNextChoice();
        alignmFile =	gd.getNextString();
        
        projX = 		gd.getNextBoolean();
        projY = 		gd.getNextBoolean();
        projZ = 		gd.getNextBoolean();
        maxProj = 		gd.getNextBoolean();
        avgProj = 		gd.getNextBoolean();
        /*
        minProj = 		gd.getNextBoolean();
        sumProj = 		gd.getNextBoolean();
        medProj = 		gd.getNextBoolean();
        stdProj = 		gd.getNextBoolean();
        */
        minProj = 		false;
        sumProj = 		false;
        medProj = 		false;
        stdProj = 		false;
        
        makeTimeLapse = gd.getNextBoolean();
        saveDir = 		gd.getNextString();
        saveToSame =	gd.getNextBoolean();
        saveDeskewImage=gd.getNextBoolean();
        saveSeparate = 	gd.getNextBoolean();
        
        // store parameter values
        if (saveToSame)	saveDir = "";
        File saveFolder = new File(saveDir);
		if ( saveDir.equals("") || null == saveFolder ) saveToSame = true;
    	
		//tryGPU = true;
		//autoPartition = true;
		//displayResult = false;
    	
		parseDeskewParameterLive();
    	parseProjectionParameter();
    	parseAlignParameter();

        //storeParam ();
		return true;
	}


			
	/**			Create parameter dialog for Axis Projection command
	 * 	
	 * @return
	 */
	public boolean axis_projection () {
		// create parameter dialog
		NonBlockingGenericDialog gd = new NonBlockingGenericDialog("Create Projection Image");
		gd.setBackground( frameColor );
		gd.addImageChoice("select active image", this.impInput.getTitle());
		String[] label_axis = {"along_X", "along_Y", "along_Z"};
		boolean[] state_axis = {projX, projY, projZ};				
		gd.addCheckboxGroup(1, 3, label_axis, state_axis);		
		String[] label_type = {"maximum", "mean", "minimum", "sum", "median", "standard deviation"};
		boolean[] state_type = {maxProj, avgProj, minProj, sumProj, medProj, stdProj};				
		gd.addCheckboxGroup(2, 3, label_type, state_type);
		gd.addCheckbox("try GPU processing", tryGPU);
		gd.addHelp(Help.projection);
		gd.showDialog();
        if (gd.wasCanceled()) return false;
        impInput = 	gd.getNextImage();
        projX = 	gd.getNextBoolean();
        projY = 	gd.getNextBoolean();
        projZ = 	gd.getNextBoolean();
        maxProj = 	gd.getNextBoolean();
        avgProj = 	gd.getNextBoolean();
        minProj = 	gd.getNextBoolean();
        sumProj = 	gd.getNextBoolean();
        medProj = 	gd.getNextBoolean();
        stdProj = 	gd.getNextBoolean();
        tryGPU = 	gd.getNextBoolean();
        // store parameter values
        storeParam ();
		return true;
	}
	
	
	/**			Create parameter dialog for Axis Permutation command
	 * 
	 * @return
	 */
	public boolean axis_permutation () {
		// create parameter dialog
		NonBlockingGenericDialog gd = new NonBlockingGenericDialog("Permutate Stack Axis");
		gd.setBackground( frameColor );
		gd.addImageChoice("select active image", this.impInput.getTitle());
		String[] label_flip = {"flip X", "flip Y", "flip Z", "fold X"};
		boolean[] state_flip = {flipX, flipY, flipZ, foldX};				
		gd.addCheckboxGroup(1, 4, label_flip, state_flip);
		gd.addChoice("Permutation:  XYZ", permuteOptions, permuteStr);
		gd.addCheckbox("try GPU processing", tryGPU);
		gd.addHelp(Help.permutation);
		gd.showDialog();
        if (gd.wasCanceled()) return false;
        impInput = 		gd.getNextImage();
        flipX = 		gd.getNextBoolean();
        flipY = 		gd.getNextBoolean();
        flipZ = 		gd.getNextBoolean();
        foldX = 		gd.getNextBoolean();
        permuteStr = gd.getNextChoice();
        tryGPU = 		gd.getNextBoolean();
        // store parameter values
        storeParam ();
		return true;
	}
	
	
	
	/**			Create parameter dialog for SIFT Alignment command
	 * 
	 * @return
	 */
	public GenericDialog sift_alignment ( SIFT.Param siftparam, PlugInFilterRunner pfr ) {
		// create non-modal parameter dialog with preview functionality
		GenericDialog gd = new NonBlockingGenericDialog("Align Channel with SIFT");
		//gd.enableYesNoCancel("OK", "save setting");
		gd.setBackground( frameColor );
		int length_string_field = 35;
		int left_inset_checkbox = 140;
		int top_inset_section = 20;
		 
		
		gd.addMessage( "Select Active Image:", new Font("Dialog", Font.BOLD, 12) );
		ImagePlus impInput = siftparam.parameter.impInput;
        gd.addImageChoice("", impInput.getTitle());
        gd.addSlider("slice", 1, impInput.getImageStackSize(), impInput.getSlice(), 1);

		gd.setInsets(top_inset_section, 15, 5);
		//gd.addMessage("SIFT parameters:");
		//[] featureExtractOptions = {"SIFT", "SURF", "MOPS", "ORB"};
		gd.addMessage( "Scale Invariant Interest Point Detector:", new Font("Dialog", Font.BOLD, 12) );
		gd.addNumericField( "initial_gaussian_blur:", siftparam.sift.initialSigma, 2, 6, "px" );
		gd.addNumericField( "steps_per_scale_octave:", siftparam.sift.steps, 0 );
		gd.addNumericField( "min_image_size:", siftparam.sift.minOctaveSize, 0, 6, "px" );
		gd.addNumericField( "max_image_size:", siftparam.sift.maxOctaveSize, 0, 6, "px" );
		
		gd.addMessage( "Feature Descriptor:" );
		gd.addNumericField( "descriptor size:", siftparam.sift.fdSize, 0 );
		gd.addNumericField( "orientation bins:", siftparam.sift.fdBins, 0 );
		gd.addNumericField( "closest ratio:", siftparam.rod, 2 );
		
		
		gd.setInsets(top_inset_section, 15, 5);
		gd.addMessage( "Geometric Consensus Filter:", new Font("Dialog", Font.BOLD, 12) );
		//gd.setInsets(0, left_inset_checkbox, 0);
		//gd.addCheckbox( "filter matches by geometric consensus", siftparam.useGeometricConsensusFilter );
		gd.addNumericField( "max_alignment_error:", siftparam.maxEpsilon, 2, 6, "px" );
		gd.addNumericField( "min_inlier_ratio:", siftparam.minInlierRatio, 2 );
		gd.addNumericField( "min_inlier_number:", siftparam.minInlierNum, 0 );
		
		gd.setInsets(top_inset_section, 15, 5);
		gd.addMessage( "show SIFT points on image:", new Font("Dialog", Font.BOLD, 12) );
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox( "", siftparam.show_sift_points );
		
		
		gd.setInsets(top_inset_section, 15, 5);
		gd.addMessage( "Alignment of Channel Image:", new Font("Dialog", Font.BOLD, 12) );
		gd.setInsets(0, left_inset_checkbox, -5);
		gd.addCheckbox("load alignment matrix from file", siftparam.load_alignMatrix);
		gd.addFileField("align matrix", loadAlignMessage, length_string_field);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addButton("save alignment matrix", new ActionListener() { 
			public void actionPerformed(ActionEvent e) { SIFT.save_align_matrix (siftparam); }
		});
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox( "interpolate (right-channel) image", siftparam.interpolate );
		//gd.addCheckbox( "show_info", true );
		//gd.addCheckbox( "show_transformation_matrix", true );
		//String[] preview_options = {"SIFT points", "aligned image", "both"};
		
		//gd.addChoice("preview option", SIFT.preview_options, SIFT.preview_options[siftparam.preview_index]);
		//gd.setInsets(top_inset_section, left_inset_checkbox, 10);

		
		gd.setInsets(top_inset_section*2, left_inset_checkbox, 0);
        gd.addPreviewCheckbox( pfr );
        
		gd.addHelp(Help.sift);
		
		Vector<Component> choices = gd.getChoices(); 				// 0 : image choice
		Vector<Component> number_fields = gd.getNumericFields();	// 0 - 10
		Vector<Component> check_boxes = gd.getCheckboxes();			// 0 - 3
		Vector<Component> text_fields = gd.getStringFields();		// 0 : matrix file field
		for (int i=0; i<choices.size(); i++) {
			((Component) choices.get(i)).setName( "choice_" + i );
		}
		for (int i=0; i<number_fields.size(); i++) {
			((Component) number_fields.get(i)).setName( "number_field_" + i );
		}
		
		for (int i=0; i<check_boxes.size(); i++) {
			((Component) check_boxes.get(i)).setName( "check_box_" + i );
		}
		
		for (int i=0; i<text_fields.size(); i++) {
			((Component) text_fields.get(i)).setName( "text_field_" + i );
		}
		
		
        return gd;
	}
	
	/*
	private boolean save_align_matrix (SIFT.Param siftparam) {
		
		if ( null == siftparam.alignMatrix )
			return false;
			
		String imageName = Utils.getName( siftparam.imp );
		// try to save align matrix to csv file
		//if ( !parameter.saveAlignMatrix || null == parameter.alignMatrix )
		//	return false;
		
		SaveDialog sd = new SaveDialog("Save Alignment Matrix", imageName + "_align", ".csv");
        String file = sd.getFileName();
        if (file==null)
            return false;
        String alignMatrixPath = sd.getDirectory() + file;

        if (!alignMatrixPath.endsWith(".csv")) alignMatrixPath += ".csv";

        return IO.saveMatrixToFile(siftparam.alignMatrix, alignMatrixPath);
	}
	*/
	
	
	/**				Create parameter dialog for Volume Transformation command
	 * 
	 * 			define as a class to implement  
	 */
	public boolean volume_transform () {//extends Parameter{
		// initialize volume transform specific parameters
		nTransform = 0;
		apply = new ArrayList<Boolean>();
		type = new ArrayList<String>();
		axis = new ArrayList<String>();
		value = new ArrayList<Double>();
		display = new ArrayList<Boolean>();
		// create user dialog
		GenericDialogPlus gd = new GenericDialogPlus("Transform Volume");
			gd.setBackground( frameColor );
			gd.setInsets(0, 10, 0);
			gd.addButton("+", new ActionListener() {
				public void actionPerformed(ActionEvent e) { addTransformation (instance, gd); }
			});
			gd.addToSameRow();		gd.addCheckbox("inverse", false);
			gd.addToSameRow(); 		gd.addButton("load", new ActionListener() { 
				public void actionPerformed(ActionEvent e) { load (gd); }
			});
			gd.addToSameRow(); 		gd.addButton("save", new ActionListener() { 
				public void actionPerformed(ActionEvent e) { save (instance, gd); }
			});
			gd.addToSameRow();		gd.addButton("save matrix", new ActionListener() { 
				public void actionPerformed(ActionEvent e) { saveMatrix (instance, gd); }
			});
			gd.addToSameRow();		gd.addImageChoice("", this.impInput.getTitle());
			gd.setInsets(20, 10, -10);
			gd.addMessage("apply      transform                         axis                 value");
			gd.enableYesNoCancel("Apply", "Apply in Reverse Order");
			gd.addHelp(Help.transform);
			gd.showDialog();
			if ( gd.wasCanceled() ) return false;
			if ( nTransform == 0 ) 	return false;
			doInverse = gd.getNextBoolean();
			impInput = gd.getNextImage();
			for (int i=0; i<nTransform; i++) {
				apply.add( gd.getNextBoolean() );
				type.add( gd.getNextChoice() );
				axis.add( gd.getNextChoice() );
				value.add( gd.getNextNumber() );
				display.add( gd.getNextBoolean() );
			}
			return true;
		}
	
		// member functions of volume transform parameter dialog
	
			/**			update transform parameter based on current dialog entries
			 * 
			 * @param parameter
			 * @param dialog
			 */
			public void updateTransformParameter (
					Parameter parameter, 
					GenericDialogPlus dialog
					) {
				parameter.doInverse = dialog.getNextBoolean();
				parameter.impInput = dialog.getNextImage();
				for (int i=0; i<parameter.nTransform; i++) {
					parameter.apply.add( dialog.getNextBoolean() );
					parameter.type.add( dialog.getNextChoice() );
					parameter.axis.add( dialog.getNextChoice() );
					parameter.value.add( dialog.getNextNumber() );
					parameter.display.add( dialog.getNextBoolean() );
				}
				dialog.resetCounters();
				
				List<double[][]> matrix_list = new ArrayList<double[][]>();
				for (int i=0; i<parameter.nTransform; i++) {
					if (!parameter.apply.get(i)) continue;
					double[][] matrix = Transform.parseTransformation (
							parameter.type.get(i), parameter.axis.get(i), parameter.value.get(i) );
					matrix_list.add(matrix);
				}
				parameter.deskewMatrix = Transform.affine ( matrix_list );
				if ( parameter.doInverse ) parameter.deskewMatrix = Transform.inverse( parameter.deskewMatrix );
			}
			
			/**			add a new transformation entry to dialog
			 * 
			 * @param dialog
			 * @param apply
			 * @param type
			 * @param axis
			 * @param value
			 * @param display
			 */
			public void addTransformation(
					GenericDialogPlus dialog,
					boolean apply,
					String type,
					String axis,
					double value,
					boolean display
					) {
		   	 	dialog.addCheckbox("", apply);
		   	 	dialog.addToSameRow();
		   	 	dialog.addChoice("", typeChoices, type);
		   	 	dialog.addToSameRow();
		   	 	dialog.addChoice("  along", axisChoices, axis);
		   	 	dialog.addToSameRow();
		   	 	dialog.addNumericField("for", value, 3);
		   	 	dialog.addToSameRow();
		   	 	dialog.addCheckbox("show result", display);
		   	 	dialog.showDialog();
			}
			
			/**			add a new transformation entry to dialog, with default parameters
			 * 
			 * @param parameter
			 * @param dialog
			 */
			public void addTransformation(
					Parameter parameter, 
					GenericDialogPlus dialog
					) {
				if (parameter.nTransform != 0) {
					int nBox = dialog.getCheckboxes().size();
					Checkbox lastCheckbox = (Checkbox) dialog.getCheckboxes().elementAt(nBox-1);
					lastCheckbox.setState(false);
				}
				addTransformation ( dialog, true, typeChoices[0], axisChoices[0], 0.0, true );
		   	 	parameter.nTransform++;
			}
			
			/**			save transformation(s) to a csv file
			 * 
			 * @param parameter
			 * @param dialog
			 */
			public void save (Parameter parameter, GenericDialogPlus dialog) {
				updateTransformParameter( parameter, dialog );
				
				DefaultPrefService prefs = new DefaultPrefService();
				saveDir = prefs.get(String.class, "OPM-transform-saveDir", saveDir);
				if (null == saveDir) saveDir = IJ.getDir("home");
				SaveDialog sd = new SaveDialog(
						"save transformation(s)", saveDir + File.separator + "transformation.csv", ".csv");
				String csvPath = sd.getDirectory() + File.separator + sd.getFileName();
				if ( !IO.saveTransformationToFile ( parameter, csvPath ) ) return;
				prefs.put(String.class, "OPM-transform-saveDir", sd.getDirectory());
			}
			
			/**			load transformation(s) from a csv file
			 * <p>		append to the end of the current dialog
			 * 
			 * @param parameter
			 * @param dialog
			 */
			@SuppressWarnings("unchecked")
			public void load ( GenericDialogPlus dialog ) {
				
				DefaultPrefService prefs = new DefaultPrefService();
				saveDir = prefs.get(String.class, "OPM-transform-saveDir", saveDir);
				if (null == saveDir) saveDir = IJ.getDir("home");
				OpenDialog od = new OpenDialog(
						"load transformation(s)", saveDir, "transformation.csv");
				String csvPath = od.getDirectory() + File.separator + od.getFileName();
				// load transform from file into parameter
				Map<String, Object> tranformMap = IO.loadTransformationFromFile ( csvPath );
				if ( null == tranformMap ) return;
				prefs.put(String.class, "OPM-transform-saveDir", od.getDirectory());
				// update transform to dialog
				List<Boolean> apply = (List<Boolean>) tranformMap.get("apply");
			 	List<String> type = (List<String>) tranformMap.get("type");
			 	List<String> axis = (List<String>) tranformMap.get("axis");
			 	List<Double> value = (List<Double>) tranformMap.get("value");
			 	List<Boolean> display = (List<Boolean>) tranformMap.get("display");
				for (int i=0; i<apply.size(); i++) {
					addTransformation( dialog, apply.get(i), type.get(i), axis.get(i), value.get(i), display.get(i) );
					nTransform++;
				}
			}
			
			/**			save current final matrix to a csv file
			 * 
			 * @param parameter
			 * @param dialog
			 */
			public void saveMatrix (Parameter parameter, GenericDialogPlus dialog) {
				updateTransformParameter( parameter, dialog );
				
				if (parameter.doInverse) parameter.deskewMatrix = Transform.inverse ( parameter.deskewMatrix );
				
				DefaultPrefService prefs = new DefaultPrefService();
				saveDir = prefs.get(String.class, "OPM-transform-saveDir", saveDir);
				if (null == saveDir) saveDir = IJ.getDir("home");
				SaveDialog sd = new SaveDialog(
						"save (combined) transform matrix", saveDir + File.separator + "matrix.csv", ".csv");
				String csvPath = sd.getDirectory() + File.separator + sd.getFileName();
				if ( !IO.saveMatrixToFile ( parameter.deskewMatrix, csvPath ) ) return;
				prefs.put(String.class, "OPM-transform-saveDir", sd.getDirectory());
		
			}
			
			
			public void loadMatrix (Parameter parameter, GenericDialog dialog) {
				// TODO: add checkbox that disable either the param fields, or load matrix file fields
			}
	
			

	
	protected void parseDeskewParameter () {
		Parameter parameter = getInstance();
		// check the setup file type, if unknown, do nothing
		if ( null == parameter.impInput ) { deskewmFile = loadSettingMessage; return; }
		double height = (double) parameter.impInput.getHeight();
		String fileType = getSetupFileType( parameter.deskewmFile );
		if (null == fileType) { deskewmFile = loadSettingMessage; return; }
		// load setup file, and update parameter(s)
		switch (fileType) {
		case "expParams":
			// try to load input parameter from file: xy pixel size, z step, angle, frame interval
			double[] expParams = IO.loadExperimentalParametersFromFile ( parameter.deskewmFile );
			if (null == expParams) { deskewmFile = loadSettingMessage; return; }
			parameter.xyPixelSize	= expParams[0];
			parameter.zStepSize		= expParams[1];
			parameter.opmAngle		= expParams[2];
			parameter.frameInterval = expParams[3];
			parameter.deskewMatrix = Transform.deskew ( parameter.zStepSize, parameter.xyPixelSize, parameter.opmAngle, height );
			break;
		case "matrix":
			// try to load matrix from file
			double[][] deskew_matrix = IO.loadMatrixFromFile( parameter.deskewmFile );
			if (null == deskew_matrix) { deskewmFile = loadSettingMessage; return; }
			// try to calculate input parameter: z step, angle, and z-translate amount
			double[] matrix_inputParam = Transform.reverse_deskew ( deskew_matrix, height );
			if (null == matrix_inputParam) { deskewmFile = loadSettingMessage; return; }
			parameter.zStepSize = matrix_inputParam[0] * parameter.xyPixelSize;
			parameter.opmAngle = matrix_inputParam[1];
			deskew_matrix[2][3] = matrix_inputParam[2];
			parameter.deskewMatrix = deskew_matrix;
			break;
		case "transform":
			// try to load set of transformation from file
			Map<String, Object> transformation = IO.loadTransformationFromFile ( parameter.deskewmFile );
			if (null == transformation) { deskewmFile = loadSettingMessage; return; }
			// TODO: compute transformation matrix from the transformations
			// TODO: update input parameters: z step size, angle, and parameter.matrix
			break;
		}
	}
	
	
	protected void updateDeskewMatrix () {
		Parameter parameter = getInstance();
		// check the setup file type, if unknown, do nothing
		if ( null == parameter.impInput || null == parameter.deskewMatrix ) return;
		double imageHeight = parameter.impInput.getHeight();
		double sin_theta = -parameter.deskewMatrix[2][1];
		double translate_z = Math.abs ( imageHeight * sin_theta );	// h * sin0
		parameter.deskewMatrix[2][3] = translate_z;
	}
	
	protected void updateAlignMatrix () {
		
	}
	
	protected void parseDeskewParameterBatch () {
		Parameter parameter = getInstance();
		// check the setup file type, if unknown, do nothing
		String fileType = getSetupFileType( parameter.deskewmFile );
		parameter.deskewMatrix = null;
		if (null != fileType) {
			// load setup file, and update parameter(s)
			switch (fileType) {
			case "expParams":
				// try to load input parameter from file: xy pixel size, z step, angle, frame interval
				double[] expParams = IO.loadExperimentalParametersFromFile ( parameter.deskewmFile );
				if (null == expParams) break;
				parameter.xyPixelSize	= expParams[0];
				parameter.zStepSize		= expParams[1];
				parameter.opmAngle		= expParams[2];
				parameter.frameInterval = expParams[3];
				double imageHeight 		= expParams[5];
				parameter.deskewMatrix = Transform.deskew ( parameter.zStepSize, parameter.xyPixelSize, parameter.opmAngle, imageHeight );
				break;
			case "matrix":
				// try to load matrix from file
				double[][] deskew_matrix = IO.loadMatrixFromFile( parameter.deskewmFile );
				if (null == deskew_matrix) break;
				parameter.deskewMatrix = deskew_matrix;
				break;
			}
		}
		if ( null == parameter.deskewMatrix ) parameter.deskewmFile = loadSettingMessage;
	}
	
	protected void parseDeskewParameterLive () {
		Parameter parameter = getInstance();
		// parameters directly loaded from metadata file
		parameter.deskewMatrix = null;
		// try to load input parameter from file: xy pixel size, z step, angle, frame interval
		double[] expParams = IO.loadExperimentalParametersFromFile ( parameter.deskewmFile );
		if (null == expParams) return;
		parameter.xyPixelSize	= expParams[0];
		parameter.zStepSize		= expParams[1];
		parameter.opmAngle		= expParams[2];
		parameter.frameInterval = expParams[3];
		double imageHeight 		= expParams[5];
		parameter.deskewMatrix = Transform.deskew ( parameter.zStepSize, parameter.xyPixelSize, parameter.opmAngle, imageHeight );
		IO.displayMatrix(parameter.deskewMatrix);
	}
	
	
	/**
	 * 
	 * @param filePath
	 * @return
	 */
	protected String getSetupFileType ( String filePath ) {
		File file = new File( filePath );
		if (null == file || !file.exists()) return null;
		String name = file.getName();
		if ( name.equals("ExperimentalParameters.txt") ) return "expParams";
		if ( name.toLowerCase().contains("matrix") ) return "matrix";
		if ( name.toLowerCase().contains("transform") ) return "transform";
		return null;
	}
	
	
	protected void parseProjectionParameter () {
		Parameter parameter = getInstance();
		// prepare projection axis string list
		parameter.projAxes = new ArrayList<String>();
		if (parameter.projX) projAxes.add("X");
		if (parameter.projY) projAxes.add("Y");
		if (parameter.projZ) projAxes.add("Z");
		// prepare projection type string list
		parameter.projTypes = new ArrayList<String>();
		if (parameter.maxProj)	parameter.projTypes.add("max");
		if (parameter.avgProj)	parameter.projTypes.add("avg");
		if (parameter.minProj)	parameter.projTypes.add("min");
		if (parameter.sumProj)	parameter.projTypes.add("sum");
		if (parameter.medProj)	parameter.projTypes.add("med");
		if (parameter.stdProj)	parameter.projTypes.add("std");
		// check if neccessary to create projection image(s)
		if ( 0 == parameter.projAxes.size() || 0 == parameter.projTypes.size() )
			parameter.doProjection = false;
		else
			parameter.doProjection = true;
	}
	
	
	// parse alignment matrix?
	protected void parseAlignParameter () {
		Parameter parameter = getInstance();
		if ( !parameter.channelStr.equals("align with SIFT") ) {
			parameter.alignmFile = loadAlignMessage;
			parameter.alignMatrix = null;
			return;
		}
		parameter.alignMatrix = IO.loadMatrixFromFile ( parameter.alignmFile );
		if ( null == parameter.alignMatrix ) parameter.alignmFile = loadAlignMessage;
		return;
	}
	
	
	protected int[] parseZrange () {
		Parameter parameter = getInstance();
		if (null == parameter.impInput) { parameter.zRangeStr = "1-end"; return null; }
		int numZ = parameter.impInput.getNSlices();
		if (1==numZ) numZ = parameter.impInput.getNFrames();
		String[] parts = parameter.zRangeStr.split("-");
		if ( parts.length != 2 ) { parameter.zRangeStr = "1-end"; return new int[]{1, numZ}; }
		int z1 = 1; int z2 = numZ;
		try {
			z1 = Integer.valueOf( parts[0] );
			if ( !parts[1].equals("end") ) z2 = Integer.valueOf( parts[1] );
		} catch ( Exception e ) {	// number foramt exception
			System.out.println( e.getMessage() );
		}
		z1 = Math.max(1, z1); z1 = Math.min(numZ, z1);
		z2 = Math.max(1, z2); z2 = Math.min(numZ, z2);
		if ( z1 >= z2 ) { int temp = z1; z1 = z2; z2 = temp; }
		if ( z2 == numZ ) parameter.zRangeStr = "" + z1 + "-end";
		else parameter.zRangeStr = "" + z1 + "-" + z2;
		return new int[] {z1, z2};
	}
	
	
	public void debug () {
		// create non-modal parameter dialog with preview functionality
		GenericDialog gd = new NonBlockingGenericDialog("OPM plugin debug");
		gd.setBackground( frameColor );
        
		impInput = WindowManager.getCurrentImage();
		if (null != impInput) {
			gd.addImageChoice("select active image", impInput.getTitle());
			gd.addCheckbox("or load from file", false);
			gd.addImageChoice("PSF", impInput.getTitle());
		}
		gd.addDirectoryField("input folder...", inputDir, 35);
		gd.addMessage("file name contains(separate mulitple by \",\")");
		gd.addStringField("", keywords, 35);
		// deskew, projection, geometry
		//TODO: add flexible batch processing options: by invoking 2nd dialog:?
		// deskew, transform, projection, geometry, combine channel? form time lapse
		gd.addCheckbox("deskew image", doDeskew);
		
		
        gd.addNumericField("XY pixel size", xyPixelSize, 1, 5, "nm");
		gd.addNumericField("Z step size", zStepSize, 1, 5, "nm");
		gd.addNumericField("OPM angle", opmAngle, 1, 5, "°");
		
		
        gd.addFileField("", loadSettingMessage, 35);
        
		gd.addCheckbox("inverse transform", doInverse);

		gd.addChoice("channel option", channelOptions, channelStr);

		if (null == alignmFile || "" == alignmFile) alignmFile = loadAlignMessage;
		gd.addFileField("align matrix", alignmFile, 35);
		
		gd.addMessage("show projection image(s):");
		String[] label_axis = {"along X      ", "along Y      ", "along Z      "};
		boolean[] state_axis = {projX, projY, projZ};
		gd.addCheckboxGroup(1, 3, label_axis, state_axis);
		
		String[] label_type = {"maximum", "mean", "minimum", "sum", "median", "standard deviation"};
		boolean[] state_type = {maxProj, avgProj, minProj, sumProj, medProj, stdProj};
		gd.addCheckboxGroup(2, 3, label_type, state_type);
		
		gd.addCheckbox("combine as time lapse", makeTimeLapse);
		//gd.addButton("save deskew setting", new ActionListener() { 
		//	public void actionPerformed(ActionEvent e) { saveDeskewSetting (instance, gd); }
		//});
		
		gd.addDirectoryField("save to...", saveDir, 35);
		gd.addCheckbox("save deskew matrix (affine 3D)", saveDeskewMatrix);
		gd.addToSameRow();
		gd.addStringField("", "<image name>_deskew.csv", 30);
		gd.addCheckbox("save alignment matrix (rigid 2D)", saveAlignMatrix);
		gd.addToSameRow();
		gd.addStringField("", "<image name>_align.csv", 30);
		gd.addCheckbox("separate results to sub-folders", saveSeparate);
		gd.addChoice("if result exist", fileExistOptions, fileExistStr);
		
		
		gd.addNumericField("PSF lateral (XY) size", 2 * radiusXY, 0, 5, "pixel");
		gd.addNumericField("PSF axial (Z) size", 2 * radiusZ, 0, 5, "pixel");
		gd.addNumericField("rough estimation of beads number", beadsCount);
		gd.addCheckbox("point to ROI Manager", addToManager);
		gd.addChoice("result as:", psfAvgMethod, avgMethod);
		//gd.addCheckbox("extend PSF border", extendBorder);
		
		
		//
		gd.addChoice("deconv with", deconvMethodChoices, deconvMethod);
		gd.addNumericField("num iterations", numIter);
		gd.addSlider("regularization factor", 0.00, 5e-3, regFactor, 1e-4);
		
		gd.addCheckbox("also process existing files in folder", processOld);
		gd.addCheckbox("overwrite exist results", overwriteExist);
		gd.addNumericField("max file writing delay", maxWait, 0, 5, "millisecond");
		
		String[] label_flip = {"flip X", "flip Y", "flip Z", "fold X"};
		boolean[] state_flip = {flipX, flipY, flipZ, foldX};				
		gd.addCheckboxGroup(1, 4, label_flip, state_flip);
		gd.addChoice("Permutation:  XYZ", permuteOptions, permuteStr);
		
		
		
		
		gd.addButton("+", new ActionListener() {
			public void actionPerformed(ActionEvent e) {  }
		});
		gd.addToSameRow();		gd.addCheckbox("inverse", false);
		gd.addToSameRow(); 		gd.addButton("load", new ActionListener() { 
			public void actionPerformed(ActionEvent e) {  }
		});
		gd.addToSameRow(); 		gd.addButton("save", new ActionListener() { 
			public void actionPerformed(ActionEvent e) {  }
		});
		gd.addToSameRow();		gd.addButton("save matrix", new ActionListener() { 
			public void actionPerformed(ActionEvent e) {  }
		});	
		gd.addMessage("apply      transform                         axis                 value");
		
		gd.addCheckbox("transform step by step", stepTransform);
		gd.addCheckbox("try GPU processing", tryGPU);
		gd.addCheckbox("auto partition data", autoPartition);
		gd.addNumericField("number of partitions", numPartition);
		
		gd.enableYesNoCancel("Apply", "Apply in Reverse Order");
		
		gd.addHelp(Help.debug);
		
		gd.showDialog();
		if (gd.wasCanceled()) return;
		
		
	}
	
	
}
