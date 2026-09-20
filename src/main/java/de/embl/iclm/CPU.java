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
	 * <br>	The JVM maximum, which is what Fiji was started with, not the machine's memory.
	 * <p>
	 * @return	: maximum heap this JVM may grow to, in bytes
	 */
	public static long memory_size () {
		long maxMemory = IJ.maxMemory();
		if (0 == maxMemory) maxMemory = Runtime.getRuntime().maxMemory();
		return maxMemory/1024/1024; // return RAM size in MB
	}
	
	
	
	/**			Transform image volume on CPU, use TransformJ libarary
	 * 
	 * @param imp				: input ImagePlus, should be image stack
	 * @param transform_matrix	: 4 x 4 transformation matrix
	 * <p>
	 * @return					: transformed volume, sized by Transform.getTransformedDim so
	 * 						  	  that the CPU and GPU paths stay interchangeable
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
	 * @param parameter			: holds the transform matrix and the interpolation choice
	 * <p>
	 * @return					: transformed volume, or null when the transform failed
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static ImagePlus transform (
			ImagePlus imp,
			double[][] transform_matrix,
			boolean doVirtual
			) {
		if (null == imp || null == transform_matrix) return null;
		String name = Utils.getName(imp);
		ImagePlus imp_transform = null;
		
		if (doVirtual) {
			int[] dims = imp.getDimensions(true);
			long[] outputsize = Transform.getTransformedDim (dims, transform_matrix, true);
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
			imp_transform = transformedImg.imageplus();
			/* imagescience sizes its own bounding box by rounding the transformed extent, while
			 * Transform.getTransformedDim (which GPU.transform allocates from) takes the ceiling.
			 * The two therefore disagree by one voxel whenever the extent has a fractional part
			 * below 0.5, which made the GPU -> CPU fallback produce differently sized volumes.
			 * Fit the result to the shared canonical size so both paths stay interchangeable. */
			imp_transform = fitToSize ( imp_transform, Transform.getTransformedDim( imp.getDimensions(true), transform_matrix, false ) );
			imp_transform.setTitle(name + "-transformed");
		}
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


	/*	Orthogonal projections
	 *
	 *	X and Y are Z projections of a transposed volume, so all three axes share one
	 *	implementation and differ only in which transpose runs first. Result titles follow the
	 *	same "<name>-<type><axis>projection" pattern the GPU path produces, because save paths
	 *	are derived from the title and the two paths must be interchangeable.
	 */

	/**			Reduce the spelling of a projection type to the canonical name used in titles
	 *
	 * @param type	: requested projection type, in any accepted spelling
	 * <p>
	 * @return		: max, avg, min, sum, med or std; null when the type is not recognised
	 */
	private static String projectionType ( String type ) {
		if (null == type) return null;
		switch ( type.toLowerCase() ) {
		case "max":												return "max";
		case "avg": case "mean":								return "avg";
		case "min":												return "min";
		case "sum":												return "sum";
		case "med": case "median":								return "med";
		case "std": case "stdev": case "stddev":
		case "standarddeviation":								return "std";
		}
		return null;
	}

	/**			Name ImageJ's ZProjector knows this projection type by
	 *
	 * @param type	: canonical projection type
	 * <p>
	 * @return		: method argument for ZProjector.run, covering every slice
	 */
	private static String zProjectorMethod ( String type ) {
		switch (type) {
		case "med":		return "median all";
		case "std":		return "sd all";
		default:		return type + " all";	// max, avg, min and sum are named the same
		}
	}

	/**			Project one axis of an image stack away, on the CPU
	 *
	 * @param imp		: input ImagePlus, should be image stack
	 * @param type		: type of projection: max, avg, min, sum, med, std
	 * @param axis		: axis to project away: X, Y or Z
	 * <p>
	 * @return			: output ImagePlus, as 2D projection image; null when the type is unknown
	 */
	private static ImagePlus projection (
			ImagePlus imp,
			String type,
			String axis
			) {
		if (null == imp) return null;
		String kind = projectionType ( type );
		if (null == kind) {
			System.out.println(" unknown projection type: " + type);
			return null;
		}
		String name = Utils.getName(imp);
		ImagePlus imp_proj = "Z".equals(axis)
				? ZProjector.run ( imp, zProjectorMethod(kind) )		// ZProjector is already C/T aware
				: projectAcrossVolumes ( imp, kind, axis );
		if (null == imp_proj) return null;
		imp_proj.setTitle( name + "-" + kind + axis + "projection" );
		imp_proj.changes = false;
		return imp_proj;
	}

	/**			Project X or Y away, one Z volume at a time, keeping channels and frames apart
	 * <p>		X and Y have no direct projector, so the axis is transposed onto Z and then
	 * 			projected. The transpose is where multi-channel input used to go wrong:
	 * 			{@link #reorderAxes} treats {@code getStackSize()} as the Z extent, which for a
	 * 			four-channel 109-slice volume is 436. The result was a single-channel image 436
	 * 			pixels wide whose maximum had been taken across the channels as well as the
	 * 			spatial axis - wrong dimensions and wrong values, and only on the CPU fallback,
	 * 			so it appeared exactly when the GPU was unavailable.
	 * <p>		Each (channel, frame) is therefore transposed and projected as its own single
	 * 			channel volume, and the results are reassembled in ImageJ's XYCZT order. The
	 * 			GPU path reaches the same shape through {@code Partition.processHyperstack}.
	 *
	 * @param imp				: input volume or hyperstack
	 * @param kind				: canonical projection type
	 * @param axis				: "X" or "Y"
	 * <p>
	 * @return					: the projection, with the input's channel and frame count
	 */
	private static ImagePlus projectAcrossVolumes (
			ImagePlus imp,
			String kind,
			String axis
			) {
		int channels = Math.max ( 1, imp.getNChannels() );
		int slices = Math.max ( 1, imp.getNSlices() );
		int frames = Math.max ( 1, imp.getNFrames() );
		// a plain stack reports one channel and one frame, so this covers both shapes
		if (channels * slices * frames != imp.getStackSize()) {
			channels = 1;
			frames = 1;
			slices = imp.getStackSize();
		}

		ImageStack out = null;
		ImageStack in = imp.getStack();
		for (int t = 1; t <= frames; t++) {
			for (int c = 1; c <= channels; c++) {
				ImageStack volume = new ImageStack ( imp.getWidth(), imp.getHeight() );
				for (int z = 1; z <= slices; z++)
					volume.addSlice ( in.getProcessor ( imp.getStackIndex ( c, z, t ) ) );
				ImagePlus single = new ImagePlus ( "volume", volume );
				ImagePlus transposed = null;
				ImagePlus projected = null;
				try {
					transposed = transpose ( single, "X".equals(axis) ? "->ZYX" : "->XZY" );
					projected = ZProjector.run ( transposed, zProjectorMethod(kind) );
					if (null == projected) return null;
					if (null == out) out = new ImageStack ( projected.getWidth(), projected.getHeight() );
					out.addSlice ( projected.getProcessor() );
				} finally {
					BatchProcessingUtils.close ( transposed );
					BatchProcessingUtils.close ( projected );
					single.changes = false;
					single.close();
				}
			}
		}
		if (null == out) return null;

		ImagePlus result = new ImagePlus ( imp.getTitle(), out );
		result.setDimensions ( channels, 1, frames );
		result.setOpenAsHyperStack ( channels > 1 || frames > 1 );
		return result;
	}

	/**			Project the X axis away, on the CPU
	 *
	 * @param imp				: input ImagePlus, should be image stack
	 * @param type				: type of projection: max, avg, min, sum, med, std
	 * <p>
	 * @return imp_xProj		: output ImagePlus, as X projection (ZY) 2D image
	 */
	public static ImagePlus projection_x (
			ImagePlus imp,
			String type			// max, mean, min, sum, median, stdev
			) {
		return projection ( imp, type, "X" );
	}


	/**			Project the Y axis away, on the CPU
	 *
	 * @param imp				: input ImagePlus, should be image stack
	 * @param type				: type of projection: max, avg, min, sum, med, std
	 * <p>
	 * @return					: output ImagePlus, as Y projection (XZ) 2D image
	 */
	public static ImagePlus projection_y (
			ImagePlus imp,
			String type			// max, mean, min, sum, median, stdev
			) {
		return projection ( imp, type, "Y" );
	}


	/**			Project the Z axis away, on the CPU
	 *
	 * @param imp				: input ImagePlus, should be image stack
	 * @param type				: type of projection: max, avg, min, sum, med, std
	 * <p>
	 * @return					: output ImagePlus, as Z projection (XY) 2D image
	 */
	public static ImagePlus projection_z (
			ImagePlus imp,
			String type			// max, mean, min, sum, median, stdev
			) {
		return projection ( imp, type, "Z" );
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
		String name = Utils.getName(imp);
		/* Every case is an axis reorder, so hand the whole set to reorderAxes. The previous
		 * implementation drove the ImageJ "Reslice [/]..." menu command and then fetched the
		 * result out of the WindowManager, which only works with a GUI: headless, the command
		 * is not registered and there is no window to fetch, so the call returned silently
		 * wrong or null data. reorderAxes copies the voxels directly instead. */
		ImagePlus imp_permute = reorderAxes ( imp, permuteString );
		imp_permute.setTitle(name + "-(XYZ" + permuteString + ")");
		imp_permute.changes = false;
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
		return imp_flip;
	}
	
	/**		Scale a volume on the CPU
	 * <br>		The fallback for GPU.scale, with the same meaning for every factor.
	 *
	 * @param imp		: input volume
	 * @param scale_x	: factor along X; 2.0 means twice the original size
	 * @param scale_y	: factor along Y
	 * @param scale_z	: factor along Z
	 * <p>
	 * @return			: scaled volume, or null when the input was null
	 */
	public static ImagePlus scale (
			ImagePlus imp,
			double scale_x,	// 2.0 means 2 times the original
			double scale_y,
			double scale_z
			) {
		if (null == imp) return null;
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
		ImagePlus imp_process = null;
		/*
		 * process step here
		 */
		return imp_process;
	}
	
	
}
