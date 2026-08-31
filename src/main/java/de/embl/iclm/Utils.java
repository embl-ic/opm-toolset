package de.embl.iclm;

import java.awt.Color;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import fiji.analyze.directionality.Directionality_;
import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.ImageWindow;
import ij.gui.PointRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.Duplicator;
import ij.plugin.Zoom;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import ij.process.LUT;
import ij.process.ShortProcessor;

public class Utils {
	
	final static String[] LUTs = {"Grays", "Red", "Green", "Blue", "Cyan", "Magenta", "Yellow"};
	
	private static Color roiColorHide = new Color (0, 0, 0, 0);
	private static final int roiHandelSizeHide = 0;
	
	
	/*
	 * 	compute trigonometry functions
	 */
	
	public static double sin (double angle) {
		double sin_theta = Math.sin(angle * Math.PI / 180);
		return sin_theta;
	}
	
	public static double cos (double angle) {
		double cos_theta = Math.cos(angle * Math.PI / 180);
		return cos_theta;
	}
	
	public static double tan (double angle) {
		double tan_theta = Math.tan(angle * Math.PI / 180);
		return tan_theta;
	}
	
	public static double ctg (double angle) {
		double ctg_theta = 1 / tan(angle);
		return ctg_theta;
	}
	
	public static double arcsin (double value) {
		double theta = Math.asin(value) * 180 / Math.PI; // -90 ~ 90
		return theta;
	}
	
	public static double arccos (double value) {
		double theta = Math.acos(value) * 180 / Math.PI; // 0 ~ 180
		return theta;
	}
	
	public static double arctan (double value) {
		double theta = Math.atan(value) * 180 / Math.PI;
		return theta;
	}
	
	public static double arcctg (double value) {
		double theta = Math.atan(1/value) * 180 / Math.PI;
		return theta;
	}
	
	
	/**			Extract name without extension from image title
	 * 
	 * @param imp
	 * <p>
	 * @return
	 */
	public static String getName ( ImagePlus imp ) {
		if (null == imp) return "";
		String name = imp.getTitle();
		
		int slashIdx = name.lastIndexOf("/") + 1;
		int dotIdx = name.lastIndexOf(".");
		if (-1 == dotIdx) dotIdx = name.length();
		name = name.substring(slashIdx, dotIdx);
		
		return name;
	}
	
	
	/**			Extract name without extension from image title
	 * 
	 * @param imp
	 * <p>
	 * @return
	 */
	public static String getNameTimeLapse ( String name ) {
		if ((null == name) || (name.equals(""))) return "";
		/*
		int idx1 = name.indexOf("Time");
		if (-1 == idx1) idx1 = 0;	// in case "_Time00." is not in name
		int idx2 = name.indexOf("_", idx1); // find the next underscore "_" after "Time"
		if (-1 == idx2) idx2 = name.length() - 1;	// in case no "_" find after "Time"
		*/
		return name.replaceAll("Time\\d+", "Time");
	}
	
	
	/**				display a ImagePlus with specified name
	 * <br>			if there's already image with the name exists, replace image content
	 * @param imp
	 * @param name
	 */
	public static void displayImage ( ImagePlus imp, String name ) {
		/*
		 * image exist: update image;
		 * image not exist: display image;
		 * 
		 * image exist: keep window location, keep BC !!!
		 * image not exsit: auto BC (for each channel)
		 * 
		 * regardless: update Z focus slice, update composite mode (for 2C)
		 * composite - noComposite: udpate BC;
		 * noComposite - composite: update BC;
		 */
		if ( null == imp ) return;
		ImagePlus imp_display = WindowManager.getImage( name );
		
		boolean imageExist = (null != imp_display);
		boolean compositeMismatch = imageExist ? ( imp_display.isComposite() != imp.isComposite() ) : false;
		if ( !imageExist ) {	// image not exsit, create new
			imp_display = imp;
			imp_display.setTitle ( name );
			imp_display.setZ ( findFocusSlice ( imp_display ) );
			imp_display.show();
			updateBC (imp_display );
		} else {				// image exsit, update with imp
			double mag = imp_display.getCanvas().getMagnification();
			double posZrelative = (double)imp_display.getZ() / (double)imp_display.getNSlices();
			int posZnew = (int) Math.floor( imp.getNSlices() * posZrelative );
			if ( compositeMismatch ) {
				ImageWindow win = imp_display.getWindow();
			    Point location = (win!=null) ? win.getLocation() : null;
			    imp_display.hide();
			    if ( location!=null ) ImageWindow.setNextLocation ( location );
			    imp.setTitle( name );
			    imp.show();
			    updateBC( imp );
			    imp_display = imp;
			} else {
				updateBC ( imp_display, imp );
				imp_display.setImage( imp );
				imp_display.show();
			}
			Zoom.set(imp_display, mag);
			imp_display.setZ( posZnew );
		}
		imp_display.changes = false;
	}
	public static void displayImage ( ImagePlus imp ) {
		String name = imp.getTitle();
		displayImage ( imp, name );
	}
	
	
	public static void displayMatrix ( double[][] matrix ) {
		if (null == matrix) return;
		int nRow = matrix.length;
		if (0 == nRow) return;
		int nCol = matrix[0].length;
		if (0 == nCol) return;
		
		for (int i=0; i<nRow; i++) {
			String rowString = "";
			for (int j=0; j<nCol; j++) {
				rowString += " " + matrix[i][j];
			}
			System.out.println( rowString );
		}
	}
	
	
	public static void showMatrixAsTable (double[][] matrix, String name) {
		if (null == matrix) return;
		
		int nRows = matrix.length;
		int nCols = matrix[0].length;

		ResultsTable table = new ResultsTable( nRows );
		table.setPrecision(18);
		table.disableRowLabels();

		//String[] coor = ["X", "Y", "Z", "I"];
		
		for (int r=0; r<nRows; r++) {
			//table.setLabel(coor[r], r);
			for (int l=0; l<nCols; l++) {
				table.setValue(l, r, matrix[r][l] );
			}
		}
		table.show(name);
		IJ.log("When saving matrix from the Results table as csv, manually remove the column header (C1,C2,...) in the csv file!");	// IJ.showMessage
	}
	
	/**
	 * 
	 * @param source
	 * @param target
	 */
	public static void updateBC (ImagePlus source, ImagePlus target) {
		if (null == source) { updateBC (target); return; }
		boolean sourceComposite = source.isComposite();
		boolean targetComposite = target.isComposite();
		/*
		 * 	check composite mode:
		 *  both not composite: copy over min and max;
		 *  both composite: copy over each channel lut.min, lut.max;
		 *  target is composite, soure not: update target itself;
		 *  target is not composite, source is: update target itself;
		 */
		if ( !sourceComposite && !targetComposite ) {	// both not composite
			target.setDisplayRange ( source.getDisplayRangeMin(), source.getDisplayRangeMax() );
		} else if ( sourceComposite && targetComposite 
				&& source.getNChannels() == target.getNChannels() ) {	// both composite
			for (int c=0; c<source.getNChannels(); c++) {
				LUT lut = ((CompositeImage)source).getChannelLut( c+1 );
				target.setC( c+1 );
				target.setDisplayRange(lut.min, lut.max);
				target.updateChannelAndDraw();
			}
		} else if ( !targetComposite && sourceComposite ) {
			source.setDisplayMode(IJ.COMPOSITE);
		    ImageWindow win = target.getWindow();
		    Point location = (win!=null) ? win.getLocation() : null;
		    target.hide();
		    if ( location!=null ) source.getWindow().setLocation(location.x, location.y);
		    source.show();
		    target = source;
		} else {
			updateBC (target);
		}
		target.updateAndDraw();
	}
	
	/**
	 * 
	 * @param imp
	 */
	public static void updateBC (
			ImagePlus imp
			) { // need image already displayed
		if ( null == imp ) return;
		int numC = imp.getNChannels();
		
		for (int c=0; c<numC; c++) {
			imp.setC ( c+1 );
			ImageProcessor channelProcessor = imp.getProcessor();
			int[] MinMax = getMinAndMax ( channelProcessor, 0.35d );
			channelProcessor.setMinAndMax ( MinMax[0], MinMax[1] );
			imp.updateChannelAndDraw();
		}
		imp.setC( 1 );
		if ( numC > 1 ) imp.setDisplayMode ( IJ.COMPOSITE );
		else imp.setDisplayMode ( IJ.GRAYSCALE );
		imp.updateAndDraw();
	}
	

	
	
	/**
	 * 
	 * @param ip
	 * @param saturated
	 * @return
	 */
    public static int[] getMinAndMax(
    		ImageProcessor ip, 
    		double saturated
    		) {
        if (null == ip) return null;
    	int hmin, hmax;
        int threshold;
        ImageStatistics stats = ip.getStats();
        int[] histogram = stats.histogram;
        if ( stats.histogram16 != null && ip instanceof ShortProcessor )
            histogram = stats.histogram16;
        int hsize = histogram.length;
        if (saturated>0.0)
            threshold = (int)(stats.pixelCount*saturated/200.0);
        else
            threshold = 0;
        int i = -1;
        boolean found = false;
        int count = 0;
        int maxindex = hsize-1;
        do {
            i++;
            count += histogram[i];
            found = count>threshold;
        } while (!found && i<maxindex);
        hmin = i;
                
        i = hsize;
        count = 0;
        do {
            i--;
            count += histogram[i];
            found = count>threshold;
        } while (!found && i>0);
        hmax = i;
        int[] a = new int[2];
        a[0]=hmin; a[1]=hmax;
        return a;
    }
    
	
	/**
	 * 
	 * @param imp_result
	 * @param imp
	 */
	public static void calibrateResult (
			ImagePlus imp_result,
			ImagePlus imp
			) {
		if (null == imp) return;
		calibrateResult (imp_result, imp.getCalibration(), "" ); // copy over calibration 
	}
	/**
	 * 
	 * @param imp_result
	 * @param imp
	 * @param obj
	 */
	public static void calibrateResult (
			ImagePlus imp_result,
			ImagePlus imp,
			String obj
			) {
		if (null == imp) return;
		calibrateResult (imp_result, imp.getCalibration(), obj );
	}
	/**
	 * 
	 * @param imp_result
	 * @param cal
	 * @param obj
	 */
	public static void calibrateResult ( 
			ImagePlus imp_result,
			Calibration cal,
			String obj
			) {
		//int[] dims_out = imp_result.getDimensions(true); // 0:X 1:Y 2:C 3:Z 4:T
		//if (1 == dims_out[3]) 
		//	imp_result.setDimensions(dims_out[3], dims_out[2], dims_out[4]);	// num Z = 1, set C Z T
		if (null == imp_result) return;
		imp_result.setCalibration( cal );
		double xSize = cal.pixelWidth;
		double ySize = cal.pixelHeight;
		double zSize = cal.pixelDepth;
		
		switch ( obj.toLowerCase() ) {
		
			case "deskew":
				imp_result.getCalibration().pixelWidth = xSize;
				imp_result.getCalibration().pixelHeight = xSize;
				imp_result.getCalibration().pixelDepth = xSize;
				break;
				
			case "transform":
				// need a way to implement X,Y,Z voxel size based on transform matrix
				break;
			
			case "projection_x":	//ZY
				imp_result.getCalibration().pixelWidth = zSize;
				imp_result.getCalibration().pixelHeight = ySize;
				break;
				
			case "projection_y":	//XZ
				imp_result.getCalibration().pixelWidth = xSize;
				imp_result.getCalibration().pixelHeight = zSize;
				break;
				
			case "projection_z":	//XY
				imp_result.getCalibration().pixelWidth = xSize;
				imp_result.getCalibration().pixelHeight = ySize;
				break;
				
			case "->yzx":
				imp_result.getCalibration().pixelWidth = ySize;
				imp_result.getCalibration().pixelHeight = zSize;
				imp_result.getCalibration().pixelDepth = xSize;
				break;
			
			case "xz":		// swap 1st and 3rd dimension
			case "zx":
			case "->zyx":
				imp_result.getCalibration().pixelWidth = zSize;
				imp_result.getCalibration().pixelHeight = ySize;
				imp_result.getCalibration().pixelDepth = xSize;
				break;
				
			case "yz":		// swap 2nd and 3rd dimension
			case "zy":
			case "->xzy":
				imp_result.getCalibration().pixelWidth = xSize;
				imp_result.getCalibration().pixelHeight = zSize;
				imp_result.getCalibration().pixelDepth = ySize;
				break;
			
			case "->zxy":
				imp_result.getCalibration().pixelWidth = zSize;
				imp_result.getCalibration().pixelHeight = xSize;
				imp_result.getCalibration().pixelDepth = ySize;
				break;
				
			case "xy":		// swap 1st and 2nd dimension
			case "yx":
			case "->yxz":
				imp_result.getCalibration().pixelWidth = ySize;
				imp_result.getCalibration().pixelHeight = xSize;
				imp_result.getCalibration().pixelDepth = zSize;
				break;
				
			case "->xyz":	// simply copy over
				imp_result.getCalibration().pixelWidth = xSize;
				imp_result.getCalibration().pixelHeight = ySize;
				imp_result.getCalibration().pixelDepth = zSize;
				break;	
			
			default:		// simply copy over
		}
		
	}
	
	/**
	 * 
	 * @param roi
	 */
	public static void hideRoi ( Roi roi ) {
		roi.setStrokeColor	( roiColorHide );
		roi.setHandleSize	( roiHandelSizeHide );
	}
	
	
	public static Roi[] modifyRoi (
			Roi[] rois,
			String roiType
			) {
		if (null == rois || 0 == rois.length) return rois;
		Roi[] rois_new = new Roi[rois.length];
		for (int i=0; i<rois.length; i++) {
			switch (roiType) {
			case "box":		// create bounding box
				rois_new[i] = new Roi( rois[i].getBounds() );
				break;
				
			case "hull":	// create convex hull 2D
				rois_new[i] = new PolygonRoi ( rois[i].getConvexHull(), Roi.POLYGON );
				break;
				
			case "point":	// create centroid point
				double[] centroid = rois[i].getContourCentroid();
				rois_new[i] = new PointRoi ( centroid[0], centroid[1] );
				break;
				
			default:		// make a copy of the input ROI
				rois_new[i] = rois[i];
			}
		}
		return rois_new;
	}
	
	
	public static Roi autoRoi (ImagePlus imp) {
		if (null == imp) return null;
		int width = imp.getWidth(); int height = imp.getHeight();
		
		// make avg X projection, median filter, create selection, to bounding box, get Y onset
		/*
		ImagePlus imp_avgX = Projection.projection(imp, "X", "avg", true);
		ImagePlus imp_avgXmedian = GPU.median2D( imp_avgX, 5 );
		if ( null == imp_avgXmedian ) imp_avgXmedian = CPU.median2D( imp_avgX, 5 );
		imp_avgXmedian.getProcessor().setAutoThreshold(Method.Otsu, true, ImageProcessor.NO_LUT_UPDATE);
		Rectangle box = ThresholdToSelection.run(imp_avgXmedian).getBounds();
		int yOnset = box.y + box.height;
		*/
		int yOnset = 0;
		int yOffset = height - yOnset;
		int xOnset = width / 4;
		if ( width > 2000 ) xOnset = width/8;
		int xOffset = 3 * xOnset;
		//imp_avgX.close(); imp_avgXmedian.close();
		return new Roi( xOnset, yOnset, xOffset, yOffset );
	}
	
	
	public static int[][] shiftRoi (int[][] points, int xOnset, int yOnset, int zOnset) {
		if ( null == points || 0 == points.length ) return null;
		int[][] points_shift = new int[points.length][3];
		for (int i=0; i<points.length; i++) {
			points_shift[i][0] = points[i][0] + xOnset;
			points_shift[i][1] = points[i][1] + yOnset;
			points_shift[i][2] = points[i][2] + zOnset;
		}
		return points_shift;
	}
	
	
	public static Roi[] splitRoi ( Roi roi ) {
        if ( roi==null ) return null;
        List<Roi> roiList = new ArrayList<Roi>();
        if ( roi.getType() == Roi.COMPOSITE ) {
        	Roi[] rois = ((ShapeRoi)roi).getRois();
        	for (int i=0; i<rois.length; i++) {
        		if ( rois[i].isArea() )
        			roiList.add(rois[i]);
        	}
        	if (roiList.size() == 0) return null;
        	return roiList.toArray(new Roi[roiList.size()]);
        }
        if ( roi.isArea() )
        	return new Roi[] { roi };
        return null;
    }
	
	
	public static Roi combineRois ( Roi[] rois ) {
		if ( null == rois || 0 == rois.length ) return null;
		if ( 1 == rois.length ) return rois[0];
		ShapeRoi sroi = new ShapeRoi (rois[0]);
		for (int i=1; i<rois.length; i++) {
			sroi = sroi.or( new ShapeRoi (rois[i]) );
		}
		return sroi;
	}
	
	
	public static int[][] pointsWithinRois ( int[][] points, Roi[] rois ) {
		if ( null == points || 0 == points.length ) return null;
		if ( null == rois || 0 == rois.length ) return points;
		List<int[]> pointList = new ArrayList<int[]>();
		for (int i=0; i<points.length; i++) {
			for (Roi roi : rois) {
				if ( roi.contains(points[i][0], points[i][1]) ) {
					pointList.add(points[i]); break;
				}
			}
		}
		if ( 0 == pointList.size() ) return null;
		return pointList.toArray(new int[pointList.size()][3]);
	}
	
	
	/**			Set channel LUT (color) to image, as R,G,B,C,M,Y,K, and grays if there's more than 6 channels
	 * 
	 * @param imp
	 */
	public static void autoSetLUTs (
			ImagePlus imp
			) {
		if (null==imp || 1==imp.getNChannels() ) return;
		for (int c=1; c<=imp.getNChannels(); c++) {
			imp.setC(c);
			if (c <= 6) IJ.run(imp, LUTs[c], "");
			else IJ.run(imp, LUTs[0], "");
		}
	}

	public static double guessZstepSize (ImagePlus imp) {
		if ( null == imp ) return 0;
		// make max X projection image
		ImagePlus imp_xMax = Projection.projection (imp, "X", "max", true);
		int width = imp_xMax.getWidth(); int height = imp_xMax.getHeight();
		// check upper left and lower right corner if all 0
		int[] x_ul = {0, width, 0}; int[] y_ul = {0, 0, width};
		imp_xMax.setRoi( new PolygonRoi(x_ul, y_ul, 3, Roi.POLYGON) );
		double mean_upperLeft = imp_xMax.getProcessor().getStats().umean;
		if ( 0 == mean_upperLeft ) return 0;
		int[] x_lr = {0, width, width}; int[] y_lr = {height, height-width, height};
		imp_xMax.setRoi( new PolygonRoi(x_lr, y_lr, 3, Roi.POLYGON) );
		double mean_lowerRight = imp_xMax.getProcessor().getStats().umean;
		if ( 0 == mean_lowerRight ) return 0;
		// now check dominant direction of image to guess z step
		Directionality_ direc = new Directionality_();
		direc.setImagePlus( imp_xMax );
		direc.setMethod(Directionality_.AnalysisMethod.LOCAL_GRADIENT_ORIENTATION);
		//direc.setMethod(Directionality_.AnalysisMethod.FOURIER_COMPONENTS)
		direc.setBinNumber(180);
		direc.setBinStart(-90);		
		direc.computeHistograms();
		direc.fitHistograms();
		double[] result = direc.getFitAnalysis().get(0);
		//param = direc.getFitParameters()
		if ( 10 < (result[1] / Math.PI * 180) ) return 0;
		double featureAngle = result[0]/Math.PI*180;
		double zstep = 116d * cos(25) * Math.tan(featureAngle/180*Math.PI);
		return zstep;
	}
	
	
	public static int findFocusSlice ( ImagePlus imp ) {
		int[] dims = imp.getDimensions( true );
		int numC = dims[2];
		int numZ = dims[3];
		int numT = dims[4];
		int[] F4values = new int[ numC * numT ];
		for (int t=0; t<numT; t++) {
			for (int c=0; c<numC; c++) {
				int fSlice = 0;
				double maxValue = 0;
				for (int z=0; z<numZ; z++) {
					imp.setPositionWithoutUpdate(c+1, z+1, t+1);
					double value = calculateF4_vollath ( imp.getProcessor() );
					if (value >= maxValue) {
						fSlice = (z+1);
						maxValue = value;
					}
				}
				F4values[t*numC + c]  = fSlice;
			}
		}
		return findMedian (F4values);
	}
	
	public static int findMedian ( int[] data ) {
		Arrays.sort(data);
		int N = data.length;
		int N2 = N / (int)2;
		double median;
		if (N % 2 == 0)
		    median = (double) (data[N2] + data[N2 - 1])/2;
		else
		    median = (double) data[N2];
		return (int) Math.round( median );
	}
	
	public static double calculateF4_vollath ( ImageProcessor ip ) {
		ImageStatistics stats = ip.getStats();
		double mean = stats.umean;
		double stdDev = stats.stdDev;
		double F4s = Math.pow(stdDev/mean, 2);
		return F4s;
	}
	
	
	public static Calibration createCalibration ( Parameter parameter ) {
		Calibration cal = new Calibration();
		cal.pixelWidth = cal.pixelHeight = (parameter.xyPixelSize / 1000d);
		cal.pixelDepth = Math.abs (parameter.zStepSize * sin (parameter.opmAngle) / 1000d);
		cal.setUnit ( "micron" );
		return cal;
	}
	/**
	 * 
	 * @param imp
	 */
	public static ImagePlus downSample (ImagePlus imp) {
		String name = getName( imp );
		long maxImageSize = GPU.memory_size() / (long)8; // max image size in MB
		int[] dims = imp.getDimensions(true);
		VolumeIO.normalize(imp);	// planes may arrive on the T axis; put them back on Z
		dims = imp.getDimensions(true); 	// XYCZT
		Calibration cal = imp.getCalibration();	// image physical calibration
		Roi roi = imp.getRoi();			// only downsample selected region if there's active ROI
		if (null != roi) { 
			dims[0] = (int) roi.getFloatWidth();
			dims[1] = (int) roi.getFloatHeight();
		}
		long sizeAll = ((long)2 + dims[0]) * ((long)2 + dims[1]) * ((long)2 + dims[3]) - (long)8; 
		sizeAll *= (long)imp.getBytesPerPixel();
		double factor = Math.cbrt( 0.5d * (double)maxImageSize / (double)sizeAll );
		if (factor <=0 ) {
			System.out.println("ERROR on downsample: factor = " + factor);
			factor = 1.0d;
		}
		ImagePlus imp_downsample = null;
		if ( null != roi ) {
			ImagePlus imp_crop = new Duplicator().run(imp);	// imp.crop("stack");
			if (factor >= 1.0d) imp_downsample = imp_crop.duplicate();
			else imp_downsample = GPU.scale (imp_crop, factor, factor, factor);
			if (null == imp_downsample) imp_downsample = CPU.scale (imp_crop, factor, factor, factor);
			imp_crop.close();
		} else {
			if (factor >= 1.0d) imp_downsample = imp.duplicate();
			else imp_downsample = GPU.scale (imp, factor, factor, factor);
			if (null == imp_downsample) imp_downsample = CPU.scale (imp, factor, factor, factor);
		}
		cal.pixelWidth /= factor; cal.pixelHeight /= factor; cal.pixelDepth /= factor;
		imp_downsample.setCalibration( cal );
		imp_downsample.setTitle( name + "-downsample" );
		System.gc();
		return imp_downsample;
	}
	
	
	/**
	 * 
	 * @param imp
	 * @param targetChannels
	 * @param targetSlices
	 * @param targetFrames
	 * @param closeOldImp
	 * @param showNewImp
	 * @return
	 */
	public static CompositeImage reorderHyperstack(
			final ImagePlus imp,
			final int targetChannels,
			final int targetSlices,
			final int targetFrames,
			final boolean closeOldImp,
			final boolean showNewImp) {
		// dimensions of the input imageplus in order CZT
		final int[] dimensions = new int[] { imp.getNChannels(), imp.getNSlices(), imp.getNFrames() };
		// the new dimension assignments; 0->(c,z or t) 1->(c,z or t) 2->(c,z or t)
		final int[] newAssignment = new int[] { targetChannels, targetSlices, targetFrames };
		// we need a new stack
		final ImageStack stack = new ImageStack( imp.getWidth(), imp.getHeight() );
		// XYCZT is the order that ImageJ wants
		// so we arrange it like that.
		// However, we adjust the numbers to the new dimensions
		final int nChannelsNew = dimensions[ newAssignment[ 0 ] ];
		final int nSlicesNew = dimensions[ newAssignment[ 1 ] ];
		final int nFramesNew = dimensions[ newAssignment[ 2 ] ];
		// used to translate old -> new
		final int[] indexTmp = new int[ 3 ];
		for ( int t = 1; t <= nFramesNew; ++t ) {
			for ( int z = 1; z <= nSlicesNew; ++z ) {
				for ( int c = 1; c <= nChannelsNew; ++c ) {
					indexTmp[ newAssignment[ 0 ] ] = c; 
					indexTmp[ newAssignment[ 1 ] ] = z; 
					indexTmp[ newAssignment[ 2 ] ] = t; 
					//final int index = imp.getStackIndex( c, z, t );
					final int index = imp.getStackIndex( indexTmp[ 0 ], indexTmp[ 1 ], indexTmp[ 2 ] );
					final ImageProcessor ip = imp.getStack().getProcessor( index );
					stack.addSlice( imp.getStack().getSliceLabel( index ), ip );
				}
			}
		}
		final ImagePlus newImp = new ImagePlus( imp.getTitle(), stack );
		newImp.setDimensions( nChannelsNew, nSlicesNew, nFramesNew );
		newImp.setCalibration( imp.getCalibration() );
		final CompositeImage c = new CompositeImage( newImp, CompositeImage.COMPOSITE );
		if (targetChannels == 0) {	//if channels stay channels
			c.setLuts(imp.getLuts());
		}
		if ( closeOldImp )
			imp.close();
		if ( showNewImp )
			c.show(); 
		return c;
	}
	
	
	/**
	 * 
	 * @param imp
	 * @param channel
	 * @param timepoint
	 * @return
	 */
	public static ImagePlus getImageChunk (
			final ImagePlus imp,
			final int channel,
			final int timepoint ) {
		if ( imp.getNSlices() == 1 ) {
			return new ImagePlus( "", imp.getStack().getProcessor( imp.getStackIndex( channel, 1, timepoint ) ) );
		} else {
			final ImageStack stack = new ImageStack( imp.getWidth(), imp.getHeight() );
			for ( int z = 1; z <= imp.getNSlices(); ++z ) {
				final int index = imp.getStackIndex( channel, z, timepoint );
				final ImageProcessor ip = imp.getStack().getProcessor( index );
				stack.addSlice( imp.getStack().getSliceLabel( index ), ip );
			}
			return new ImagePlus( "", stack );
		}
	}
	
	
	public static boolean checkPluginWindowExist (String window_name) {
		String[] names = WindowManager.getNonImageTitles();
		for (String name : names) {
			if (name.equals( window_name )) {	// an instance found
				WindowManager.toFront( WindowManager.getWindow(name) );
				return true;
			}
		}
		return false;
	}
	
	
	
}
