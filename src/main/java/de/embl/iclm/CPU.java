package de.embl.iclm;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.plugin.ZProjector;
import ij.process.ImageProcessor;

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
			/* imagescience sizes its own bounding box by rounding the transformed extent, while
			 * Transform.getTransformedDim (which GPU.transform allocates from) takes the ceiling.
			 * The two therefore disagree by one voxel whenever the extent has a fractional part
			 * below 0.5, which made the GPU -> CPU fallback produce differently sized volumes.
			 * Fit the result to the shared canonical size so both paths stay interchangeable. */
			imp_transform = fitToSize ( imp_transform, Transform.getTransformedDim( imp.getDimensions(true), transform_matrix, false ) );
			imp_transform.setTitle(name + "-transformed");
		}
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\ttransform data on CPU takes %.3f seconds.\n", duration/1000);
		return imp_transform;
	}


	/**			Pad or crop a volume to an exact size, anchored at the origin
	 * <p>		Used to bring a transform result onto the canonical output dimensions
	 * 			returned by {@link Transform#getTransformedDim}, so that the CPU and GPU
	 * 			transform paths produce interchangeable volumes. Padding is added at the
	 * 			far edge and filled with zero; cropping removes from the far edge.
	 *
	 * @param imp				: input ImagePlus
	 * @param target			: wanted {width, height, depth}; null or short arrays are ignored
	 * <p>
	 * @return					: imp itself when it already has the wanted size, a resized copy otherwise
	 */
	public static ImagePlus fitToSize (
			ImagePlus imp,
			long[] target
			) {
		if (null == imp || null == target || target.length < 3) return imp;
		int w = (int) target[0], h = (int) target[1], d = (int) target[2];
		if (w <= 0 || h <= 0 || d <= 0) return imp;
		int wIn = imp.getWidth(), hIn = imp.getHeight(), dIn = imp.getStackSize();
		if (w == wIn && h == hIn && d == dIn) return imp;			// already the wanted size

		ImageStack stack_in = imp.getStack();
		ImageStack stack_out = new ImageStack (w, h);
		for (int z = 1; z <= d; z++) {
			ImageProcessor ip_out = stack_in.getProcessor(1).createProcessor(w, h);	// zero filled
			if (z <= dIn) ip_out.insert ( stack_in.getProcessor(z), 0, 0 );			// clips on its own
			stack_out.addSlice ( z <= dIn ? stack_in.getSliceLabel(z) : null, ip_out );
		}
		ImagePlus imp_out = new ImagePlus (imp.getTitle(), stack_out);
		imp_out.setCalibration ( imp.getCalibration() );
		imp_out.changes = false;
		return imp_out;
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
		ImagePlus transposed = transpose(imp, "->ZYX");
		ImagePlus imp_xProj;
		try {
			imp_xProj = projection_z ( transposed, type );		// ZY image
		} finally {
			BatchProcessingUtils.close(transposed);
		}
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
		ImagePlus transposed = transpose(imp, "->XZY");
		ImagePlus imp_yProj;
		try {
			imp_yProj = projection_z ( transposed, type );		// XZ image
		} finally {
			BatchProcessingUtils.close(transposed);
		}
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
		String name = Utils.getName(imp);
		/* Every case is an axis reorder, so hand the whole set to reorderAxes. The previous
		 * implementation drove the ImageJ "Reslice [/]..." menu command and then fetched the
		 * result out of the WindowManager, which only works with a GUI: headless, the command
		 * is not registered and there is no window to fetch, so the call returned silently
		 * wrong or null data. reorderAxes copies the voxels directly instead. */
		ImagePlus imp_permute = reorderAxes ( imp, permuteString );
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
		String name = Utils.getName(imp);			// get image name without extension
		String order;
		switch (tranposeString.toLowerCase()) {	//"->YXZ", "->ZYX", "->XZY"
		case "xy": case "yx": case "->yxz":	// XYZ -> YXZ ( axis 123 -> 213 )	swap 1st and 2nd
			order = "->YXZ"; break;
		case "xz": case "zx": case "->zyx":	// XYZ -> ZYX ( axis 123 -> 321 )	swap 1st and 3rd
			order = "->ZYX"; break;
		case "yz": case "zy": case "->xzy":	// XYZ -> XZY ( axis 123 -> 132 )	swap 2nd and 3rd
			order = "->XZY"; break;
		default:
			order = "->XYZ"; break;			// unrecognised: leave the volume alone
		}
		ImagePlus imp_transpose = reorderAxes ( imp, order );
		imp_transpose.setTitle(name + "-(XYZ" + tranposeString + ")");
		imp_transpose.changes = false;
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\transpose data on CPU takes %.3f seconds.\n", duration/1000);
		return imp_transpose;
	}


	/**			Reorder the three volume axes by copying voxels
	 * <p>		The order string names the input axis that becomes the output width, height
	 * 			and depth, in that order: "->ZYX" builds a volume whose slices are ZY images
	 * 			stacked along X. This replaces the previous route through the ImageJ
	 * 			"Reslice [/]..." command, which needs a GUI and therefore failed headless.
	 *
	 * @param imp				: input ImagePlus, an XYZ image stack
	 * @param order				: "->XYZ", "->YXZ", "->ZYX", "->XZY", "->YZX" or "->ZXY"
	 * <p>
	 * @return					: a new ImagePlus with the axes reordered; a duplicate for "->XYZ"
	 */
	public static ImagePlus reorderAxes (
			ImagePlus imp,
			String order
			) {
		if (null == imp) return null;
		int[] axis = parseAxisOrder ( order );
		if (null == axis) return imp.duplicate();				// unrecognised order: no change

		int[] n = { imp.getWidth(), imp.getHeight(), imp.getStackSize() };	// input X, Y, Z extent
		if (axis[0] == 0 && axis[1] == 1 && axis[2] == 2) return imp.duplicate();	// identity

		// cache the source processors once; per-voxel getProcessor() would dominate the cost
		ImageStack stack_in = imp.getStack();
		ImageProcessor[] src = new ImageProcessor[ n[2] ];
		for (int z = 0; z < n[2]; z++) src[z] = stack_in.getProcessor(z + 1);

		int wOut = n[ axis[0] ], hOut = n[ axis[1] ], dOut = n[ axis[2] ];
		ImageStack stack_out = new ImageStack (wOut, hOut);
		int[] coord = new int[3];
		for (int c = 0; c < dOut; c++) {
			ImageProcessor ip_out = src[0].createProcessor (wOut, hOut);
			coord[ axis[2] ] = c;
			for (int b = 0; b < hOut; b++) {
				coord[ axis[1] ] = b;
				for (int a = 0; a < wOut; a++) {
					coord[ axis[0] ] = a;
					ip_out.setf ( a, b, src[ coord[2] ].getf( coord[0], coord[1] ) );
				}
			}
			stack_out.addSlice (ip_out);
		}
		ImagePlus imp_out = new ImagePlus (imp.getTitle(), stack_out);
		imp_out.changes = false;
		return imp_out;
	}


	/**			Map an order string such as "->ZYX" to input axis indices (X=0, Y=1, Z=2)
	 *
	 * @param order				: order string, with or without the leading "->"
	 * <p>
	 * @return					: three input axis indices, or null if the string is not a permutation of XYZ
	 */
	private static int[] parseAxisOrder (
			String order
			) {
		if (null == order) return null;
		String s = order.trim().toUpperCase();
		if (s.startsWith("->")) s = s.substring(2);
		if (s.length() != 3) return null;
		int[] axis = new int[3];
		boolean[] seen = new boolean[3];
		for (int i = 0; i < 3; i++) {
			int k;
			switch (s.charAt(i)) {
				case 'X': k = 0; break;
				case 'Y': k = 1; break;
				case 'Z': k = 2; break;
				default: return null;
			}
			if (seen[k]) return null;			// a repeated axis is not a permutation
			seen[k] = true;
			axis[i] = k;
		}
		return axis;
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
		int newWidth = Math.max (1, (int) (imp.getWidth() * scale_x));
		int newHeigth = Math.max (1, (int) (imp.getHeight() * scale_y));
		ImagePlus imp_downxy = imp.resize(newWidth, newHeigth, "bilinear");
		/* Z is resampled here rather than through IJ.run(.., "Reslice Z", ..) plus
		 * WindowManager.getImage("Resliced"): that route needs a GUI, and headless it
		 * left imp_scale null and threw a NullPointerException on the next line. */
		ImagePlus imp_scale = scaleZ ( imp_downxy, scale_z );
		if (imp_scale != imp_downxy) { imp_downxy.changes = false; imp_downxy.close(); }
		imp_scale.setCalibration(null);
		imp_scale.setTitle(name + "-rescaled");
		imp_scale.changes = false;
		//IJ.run("Collect Garbage", "");
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\flip data on CPU takes %.3f seconds.\n", duration/1000);
		return imp_scale;
	}


	/**			Resample a stack along Z by linear interpolation between neighbouring slices
	 *
	 * @param imp				: input ImagePlus, an image stack
	 * @param scale_z			: 2.0 doubles the slice count, 0.5 halves it
	 * <p>
	 * @return					: imp itself when no resampling is needed, a resampled copy otherwise
	 */
	public static ImagePlus scaleZ (
			ImagePlus imp,
			double scale_z
			) {
		if (null == imp) return null;
		int dIn = imp.getStackSize();
		int dOut = Math.max (1, (int) Math.round( dIn * scale_z ));
		if (dOut == dIn) return imp;

		ImageStack stack_in = imp.getStack();
		int w = imp.getWidth(), h = imp.getHeight();
		ImageStack stack_out = new ImageStack (w, h);
		for (int k = 0; k < dOut; k++) {
			// map output slice centre back onto the input slice grid
			double src = (dOut == 1) ? 0 : (double) k * (dIn - 1) / (dOut - 1);
			int z0 = (int) Math.floor(src);
			int z1 = Math.min (z0 + 1, dIn - 1);
			double f = src - z0;
			ImageProcessor ip0 = stack_in.getProcessor(z0 + 1);
			ImageProcessor ip_out = ip0.createProcessor (w, h);
			if (z0 == z1 || f == 0) {
				ip_out.insert (ip0, 0, 0);
			} else {
				ImageProcessor ip1 = stack_in.getProcessor(z1 + 1);
				for (int i = 0; i < ip_out.getPixelCount(); i++)
					ip_out.setf ( i, (float)( ip0.getf(i) * (1 - f) + ip1.getf(i) * f ) );
			}
			stack_out.addSlice (ip_out);
		}
		ImagePlus imp_out = new ImagePlus (imp.getTitle(), stack_out);
		imp_out.setCalibration ( imp.getCalibration() );
		imp_out.changes = false;
		return imp_out;
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
