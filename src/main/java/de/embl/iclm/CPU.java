package de.embl.iclm;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.plugin.ZProjector;

import imagescience.image.Image;
import imagescience.transform.Affine;

import net.imglib2.RandomAccessible;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

public class CPU {
	
	/**		return RAM size in byte
	 * 
	 * @return
	 */
	public static long memory_size () {
		long maxMemory = IJ.maxMemory();
		if (0 == maxMemory) maxMemory = Runtime.getRuntime().maxMemory();
		return maxMemory/1024/1024; // return RAM size in MB
	}
	
	
	
	/**			Transform image volume on CPU, use TransformJ libarary
	 * 
	 * @param imp				: input ImagePlus, should be image stack
	 * @param transform_matrix	: input Parameter, stores transform matrix, 
	 * <p>
	 * @return
	 */
	public static ImagePlus transform (
			ImagePlus imp,
			double[][] transform_matrix
			) {
		return transform ( imp, transform_matrix, false );
	}
	/**			Transform image volume on CPU
	 * 
	 * @param imp				: input ImagePlus, should be image stack
	 * @param parameter			: input Parameter, stores transform matrix, 
	 * <p>
	 * @return
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static ImagePlus transform (
			ImagePlus imp,
			double[][] transform_matrix,
			boolean doVirtual
			) {
		if (null == imp || null == transform_matrix) return null;
		//Log log = Log.getInstance();
		//long start = System.currentTimeMillis();
		String name = Utils.getName(imp);
		ImagePlus imp_transform = null;
		
		if (doVirtual) {
			int[] dims = imp.getDimensions(true);
			//double m11 = transform_matrix[1][1]; double m12 = transform_matrix[1][2];
			//double m21 = transform_matrix[2][1]; double m22 = transform_matrix[2][2];
			//long newYdim = (long)Math.round( Math.abs( dims[1] * m11 + dims[3] * m12 )); 	// TODO: check if necessary
			//long newZdim = (long)Math.round( Math.abs( dims[1] * m21 + dims[3] * m22 ));
			//long[] outputsize = {dims[0], newYdim, newZdim};
			//double outputsize_MB = outputsize[0] * outputsize[1] * outputsize[2] * imp.getBytesPerPixel() /1024/1024;
			long[] outputsize = Transform.getTransformedDim (dims, transform_matrix, true);
			//System.out.printf("\timglib2 transform volume dimension calculated as:\n\t%d * %d * %d pixels = %.1f MB.\n", outputsize[0], outputsize[1], outputsize[2], outputsize_MB);
			Img<RealType> image = ImageJFunctions.wrapReal(imp);
			// extend the image with zeroes
			RandomAccessible<RealType> extended = Views.extendZero(image);
			// decide how we want the image to be interpolated
			RealRandomAccessible field = Views.interpolate(extended, new NLinearInterpolatorFactory());
			// Albert Cardona's code to recompute the bounding box
			long[] minC = {1, 1, 1};
			// shearing happens here
			RandomAccessible<RealType> sheared = RealViews.affine(field, Transform.raw_to_imglib2(transform_matrix));
			// apply the recomputed bounding box to the sheared image
			IntervalView<RealType> bounded = Views.interval(sheared, minC, outputsize);
			// convert the result to an ImageJ dataset
			imp_transform = ImageJFunctions.wrapUnsignedShort ( bounded, name + "-transformed" ); 
		} else {
			/* TranformJ (imagescience) Affine Transform function
			 * static field interpolation: 
			 * 				The interpolation scheme to be used. Must be equal to one of the static fields of this class.
			 * 
			 * boolean adjust: 
			 * 				If true, the size of the output image is adjusted to fit the entire affine transformed image; 
			 * 				If false, the size of the output image will be equal to that of the input image.
			 * 
			 * boolean resample: 
			 * 				If true, the output image is resampled isotropically, 
			 * 					using as sampling interval the smallest of the x-, y-, and z-aspect sizes of the image; 
			 * 				If false, the output image is sampled on the same grid as the input image.
			 * 
			 * boolean antialias: 
			 * 				If true, the method attempts to reduce the "stair-casing" effect at the transitions from image to background.
			 */
			Image transformedImg = new Affine().run(Image.wrap(imp), new imagescience.transform.Transform(transform_matrix), Affine.LINEAR, true, false, true);
			//long[] outputsize = {transformedImg.dimensions().x, transformedImg.dimensions().y, transformedImg.dimensions().z};
			//double outputsize_MB = outputsize[0] * outputsize[1] * outputsize[2] * imp.getBytesPerPixel() /1024/1024;
			//log.add("\tTransformJ transform volume dimension extracted as:\n\t%d * %d * %d pixels = %.1f MB.\n", outputsize[0], outputsize[1], outputsize[2], outputsize_MB);
			imp_transform = transformedImg.imageplus();
			imp_transform.setTitle(name + "-transformed");
		}
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\ttransform data on CPU takes %.3f seconds.\n", duration/1000);
		return imp_transform;
	}
	
	
	/**	TODO: here
	 * 
	 * @param imp				: input ImagePlus, should be image stack
	 * @param type				: type of projection: max, mean, min, sum, median, standard deviation 
	 * <p>
	 * @return imp_xProj		: output ImagePlus, as X projection (ZY) 2D image; null if GPU process failed
	 */
	public static ImagePlus projection_x (
			ImagePlus imp,
			String type			// max, mean, min, sum, median, stdev
			) {
		if (null == imp) return null;
		//Log log = Log.getInstance();
		//long start = System.currentTimeMillis();
		String name = Utils.getName(imp);
		ImagePlus imp_xProj = projection_z ( transpose(imp, "->ZYX"), type );		// ZY image
		imp_xProj.setTitle(name + " -" + type + "Xprojection");
		imp_xProj.changes = false;
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\t%s X project data on CPU takes %.3f seconds.\n", type, duration/1000);
		return imp_xProj;
	}
	
	
	/**
	 * 
	 * @param imp				: input ImagePlus, should be image stack
	 * @param type				: type of projection: max, mean, min, sum, median, standard deviation 
	 * <p>
	 * @return					: output ImagePlus, as Y projection (XZ) 2D image; null if GPU process failed
	 */
	public static ImagePlus projection_y (
			ImagePlus imp, 
			String type			// max, mean, min, sum, median, stdev
			) {
		if (null == imp) return null;
		//Log log = Log.getInstance();
		//long start = System.currentTimeMillis();
		String name = Utils.getName(imp);
		ImagePlus imp_yProj = projection_z ( transpose(imp, "->XZY"), type );		// XZ image
		imp_yProj.setTitle(name + " -" + type + "Yprojection");
		imp_yProj.changes = false;
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\t%s Y project data on CPU takes %.3f seconds.\n", type, duration/1000);
		return imp_yProj;
	}
	
	
	/**
	 * 
	 * @param imp				: input ImagePlus, should be image stack
	 * @param type				: type of projection: max, mean, min, sum, median, standard deviation 
	 * <p>
	 * @return					: output ImagePlus, as Z projection (XY) 2D image; null if GPU process failed
	 */
	public static ImagePlus projection_z (
			ImagePlus imp, 
			String type			// max, mean, min, sum, median, stdev
			) {
		if (null == imp) return null;
		//Log log = Log.getInstance();
		//long start = System.currentTimeMillis();
		ImagePlus imp_zProj = null;
		String typeString = type;
		if (type.toLowerCase().equals("mean")) {typeString = "avg"; type = "avg";};
		if (type.toLowerCase().equals("med")) typeString = "median";
		if (type.toLowerCase().equals("std")) typeString = "sd";
		typeString += " all";	// TODO: this will take care of hyperstack cases
		imp_zProj = ZProjector.run(imp, typeString);
		imp_zProj.setTitle(imp.getTitle() + "-" + type + "Zprojection");
		imp_zProj.changes = false;
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\t%s Z project data on CPU takes %.3f seconds.\n", type, duration/1000);
		return imp_zProj;
	}
	
	
	/**
	 * 
	 * @param imp				: input ImagePlus, should be image stack
	 * @param permuteString		: String to indicate the type of permutation
	 * <p>
	 * @return					: output ImagePlus, as the permuted image stack; null if GPU process failed
	 */
	public static ImagePlus permute (
			ImagePlus imp, // image stack in the order XYZ
			String permuteString
			) {
		if (null == imp) return null;
		//Log log = Log.getInstance();
		//long start = System.currentTimeMillis();
		ImagePlus imp_permute = imp.duplicate();	// make a copy of image stack, ignore selection
		String name = Utils.getName(imp);
		imp_permute.setTitle("CPU_permute_imp"); 	// rename temp ImagePlus for debug
		
		switch (permuteString) {
		case "->YZX":	// XYZ -> YZX (axis 123 -> 231 );  reslice from left
			IJ.run(imp_permute, "Reslice [/]...", "output=1.000 start=Left avoid");
			ImagePlus imp_reslice_yzx = WindowManager.getImage("Reslice of " + imp_permute.getTitle());
			imp_permute.setImage( imp_reslice_yzx );
			imp_reslice_yzx.close();
			//IJ.run("Collect Garbage", "");
			break;
		
		case "->XYZ":	// XYZ -> XYZ (axis 123 -> 123 );   no permutation
			break;
			
		case "->YXZ":	// XYZ -> YXZ (axis 123 -> 213 );  // xyz to yxz	transpose xy
		case "->ZYX":	// XYZ -> ZYX (axis 123 -> 321 );  // xyz to zyx	transpose xz
		case "->XZY":	// XYZ -> XZY (axis 123 -> 132 );  // xyz to xzy	transpose yz
			imp_permute = transpose(imp_permute, permuteString);	// xyz to zyx
			break;
			
		case "->ZXY":	// XYZ -> ZXY (axis 123 -> 312 )
			ImagePlus imp_xzy = transpose(imp_permute, "yz");		// xyz to xzy
			imp_permute = transpose(imp_xzy, "xy");		// xzy to zxy
			imp_xzy.close();
			break;

		}
		imp_permute.setTitle(name + "-(XYZ" + permuteString + ")");
		imp_permute.changes = false;
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\tpermute data on CPU takes %.3f seconds.\n", duration/1000);
		return imp_permute;
	}
	
	
	/**
	 * 
	 * @param imp				: input ImagePlus, should be image stack
	 * @param tranposeString	: String to indicate the type of transpose:
	 * <br>						  "xy",	  	"xz",	 	"yz"
	 * <br>						  "->YXZ", 	"->ZYX", 	"->XZY"
	 * <p>
	 * @return					: output ImagePlus, as X-Y transposed image stack
	 */
	public static ImagePlus transpose (
			ImagePlus imp,
			String tranposeString
			) {
		if (null == imp) return null;
		//Log log = Log.getInstance();
		//long start = System.currentTimeMillis();
		ImagePlus imp_transpose = imp.duplicate();		// make a copy of image stack, ignore selection
		String name = Utils.getName(imp);			// get image name without extension
		imp_transpose.setTitle("CPU_transpose_imp"); 	// rename temp ImagePlus for debug
		
		switch (tranposeString.toLowerCase()) {	//"->YXZ", "->ZYX", "->XZY"
		case "xy":		// swap 1st and 2nd dimension
		case "yx":
		case "->yxz":	// XYZ -> YXZ ( axis 123 -> 213 )
			IJ.run(imp_transpose, "Rotate 90 Degrees Left", "");
			IJ.run(imp_transpose, "Flip Vertically", "stack");
			break;
			
		case "xz":		// swap 1st and 3rd dimension
		case "zx":
		case "->zyx":	// XYZ -> ZYX ( axis 123 -> 321 )
			IJ.run(imp_transpose, "Reslice [/]...", "output=1.000 start=Left rotate avoid");
			ImagePlus imp_reslice_zyx = WindowManager.getImage("Reslice of " + imp_transpose.getTitle());
			imp_transpose.setImage( imp_reslice_zyx );
			imp_reslice_zyx.close();
			//IJ.run("Collect Garbage", "");
			break;
			
		case "yz":		// swap 2nd and 3rd dimension
		case "zy":
		case "->xzy":	// XYZ -> XZY ( axis 123 -> 132 )
			IJ.run(imp_transpose, "Reslice [/]...", "output=1.000 start=Top avoid");
			ImagePlus imp_reslice_xzy = WindowManager.getImage("Reslice of " + imp_transpose.getTitle());
			imp_transpose.setImage( imp_reslice_xzy );
			imp_reslice_xzy.close();
			//IJ.run("Collect Garbage", "");
			break;			
		}
		imp_transpose.setTitle(name + "-(XYZ" + tranposeString + ")");
		imp_transpose.changes = false;
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\transpose data on CPU takes %.3f seconds.\n", duration/1000);	
		return imp_transpose;
	}
	
	/**
	 * 
	 * @param imp				: input ImagePlus, should be image stack
	 * @param flip_x			: whether to flip X axis
	 * @param flip_y			: whether to flip Y axis
	 * @param flip_z			: whether to flip Z axis
	 * <p>
	 * @return					: output ImagePlus, as the axis flipped image stack
	 */
	public static ImagePlus flip (
			ImagePlus imp,
			boolean flip_x,
			boolean flip_y,
			boolean flip_z
			) {
		if (null == imp) return null;
		//Log log = Log.getInstance();
		//long start = System.currentTimeMillis();
		String name = Utils.getName(imp);
		String filpString = "->XYZ";
		if (flip_x) filpString = filpString.replace("X", "X'");
		if (flip_y) filpString = filpString.replace("Y", "Y'");
		if (flip_z) filpString = filpString.replace("Z", "Z'");
		
		ImagePlus imp_flip = imp.duplicate();		// make a copy of image stack, ignore selection
		if (flip_x) IJ.run(imp_flip, "Flip Horizontally", "stack");
		if (flip_y) IJ.run(imp_flip, "Flip Vertically", "stack");
		if (flip_z) Permutation.flip_z(imp_flip); 	// IJ.run(imp_flip, "Flip Z", "");	// ImageJ Flip Z (stack_reverser) do not work with hyperstack
		imp_flip.setTitle(name + "-(XYZ" + filpString + ")");
		imp_flip.changes = false;
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\flip data on CPU takes %.3f seconds.\n", duration/1000);
		return imp_flip;
	}
	
	/**
	 * 
	 * @param imp
	 * @param scale_x
	 * @param scale_y
	 * @param scale_z
	 * <p>
	 * @return
	 */
	public static ImagePlus scale (
			ImagePlus imp,
			double scale_x,	// 2.0 means 2 times the original
			double scale_y,
			double scale_z
			) {
		if (null == imp) return null;
		//Log log = Log.getInstance();
		//long start = System.currentTimeMillis();
		String name = Utils.getName(imp);
		int newWidth = (int) (imp.getWidth() * scale_x);
		int newHeigth = (int) (imp.getHeight() * scale_y);
		ImagePlus imp_downxy = imp.resize(newWidth, newHeigth, "bilinear");
		double newDepth = imp.getCalibration().pixelDepth * scale_z;
		IJ.run(imp_downxy, "Reslice Z", "new=" + newDepth);
		imp_downxy.close();
		ImagePlus imp_scale = WindowManager.getImage("Resliced");
		imp_scale.setCalibration(null);
		imp_scale.setTitle(name + "-rescaled");
		imp_scale.changes = false;
		//IJ.run("Collect Garbage", "");
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\flip data on CPU takes %.3f seconds.\n", duration/1000);
		return imp_scale;
	}
	
	
	public static ImagePlus median2D (	// check hyperstack and too large case
			ImagePlus imp,
			int radius
			) {
		if ( null==imp ) return null;
		String name = Utils.getName(imp);
		ImagePlus imp_median = imp.duplicate();
		IJ.run(imp_median, "Median...", "radius="+radius);
		imp_median.setTitle(name + "-median");
		imp_median.changes = false;
		return imp_median;
	}
	
	
	public static ImagePlus process (
			ImagePlus imp
			) {
		if (null == imp) return null;
		//Log log = Log.getInstance();
		//long start = System.currentTimeMillis();
		//String name = Utils.getName(imp);
		ImagePlus imp_process = null;
		/*
		 * process step here
		 */
		//imp_process.setTitle(name + "-processed");
		//imp_process.changes = false;
		//IJ.run("Collect Garbage", "");
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\flip data on CPU takes %.3f seconds.\n", duration/1000);
		return imp_process;
	}
	
	
}
