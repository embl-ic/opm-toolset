package de.embl.iclm;

import ij.ImagePlus;
import ij.ImageStack;
import ij.plugin.Duplicator;
import ij.plugin.ImagesToStack;
import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
import net.haesleinhuepf.clij.coremem.enums.NativeTypeEnum;
import net.haesleinhuepf.clij2.CLIJ2;
import net.imglib2.realtransform.AffineTransform3D;

public class GPU {
	
	/**		return GPU memory in byte
	 * <br>	This is the largest single allocation the device accepts, not its total memory,
	 * <br>	which is the limit that actually decides whether a volume has to be partitioned.
	 * <p>
	 * @return	: maximum single allocation of the current OpenCL device, in bytes
	 */
	public static long memory_size () {
		CLIJ2 clij2 = CLIJ2.getInstance();
		long gpuMemoryByte = clij2.getCLIJ().getClearCLContext().getDevice().getMaxMemoryAllocationSizeInBytes();
		return gpuMemoryByte;	// return GPU memory size in Byte
	}
	
	
	public static boolean checkImageSize ( ImagePlus imp ) {
		return checkImageSize (imp, 1.0);
	}
	public static boolean checkImageSize ( ImagePlus imp, double factor ) {
		double imageSizeMB = imp.getSizeInBytes();
		return (imageSizeMB*factor <= memory_size()); //maxImageSize_GPU());
	}
	
	
	/**		Affine transform a volume on the GPU, partitioning it when it does not fit
	 * <br>		Hyperstacks are handed to Partition.processHyperstack; a volume too large for one
	 * <br>		device allocation is cut along axisPartition, transformed part by part and
	 * <br>		concatenated again.
	 * <p>		Note that transform_matrix is modified in place when combineReverse is set, so
	 * <br>		callers that reuse a matrix should pass a Transform.copy of it.
	 *
	 * @param imp				: input volume
	 * @param transform_matrix	: 4 x 4 transformation matrix
	 * @param axisPartition		: axis to cut along when the volume has to be partitioned
	 * @param combineReverse	: parts are concatenated in reverse order along that axis
	 * <p>
	 * @return					: transformed volume, or null when the GPU path failed
	 */
	public static ImagePlus transform (
			ImagePlus imp,
			double[][] transform_matrix,	// !!! this transform here don't allow Translation !!!
			String axisPartition,
			boolean combineReverse
			) {
		if (null == imp || null == transform_matrix) return null;
		
		
		int[] dims = imp.getDimensions(true);
		String name = Utils.getName(imp);
		ImagePlus imp_transform = null;
		
		
		
		// TODO: check if hyperstack case works !!!
		if ( imp.isHyperStack() ) {
			/* The matrix has to be carried explicitly. This used to pass the shared static
			 * Parameter, whose deskewMatrix is whatever the last dialog computed - so a
			 * hyperstack transformed with a caller-supplied matrix (a mirrored one, say)
			 * silently used the dialog's matrix instead. */
			Parameter tempParam = Parameter.scratch();
			tempParam.deskewMatrix = transform_matrix;
			imp_transform = Partition.processHyperstack (imp, tempParam, "transform", true);
			imp_transform.setTitle(name + "-transformed");
			Utils.calibrateResult ( imp_transform, imp, "deskew" );	// this may not apply to all transformation cases
			return imp_transform;
		}
		
		// transform non-hyperstack image stack
		long[] outputsize = Transform.getTransformedDim (dims, transform_matrix, true);
		AffineTransform3D transform = Transform.raw_to_imglib2( transform_matrix );//parameter.matrix );
		
		// TODO: investigate for the case no axis is possible for partition!!!
		// TODO: transform matrix also need to update with partition dimension
		// in case input imp size is too big, parition image along proper axis automatically
		if ( !checkImageSize(imp, 2.0) ) {
			int numPartition = Partition.guessNumPartition(dims, outputsize, imp.getBytesPerPixel());
			
			
			
			int[][] idx_parts = Partition.partition_roi ( imp, axisPartition, numPartition );
			
			
			
			ImagePlus[] imp_transforms = new ImagePlus[numPartition];
			
					
			for (int i=0; i<numPartition; i++) {
				
				
				
				imp_transforms[i] = transform (Partition.partition_imp (imp, idx_parts[i]), transform_matrix, axisPartition, combineReverse);
				
				
				if (null == imp_transforms[i]) return null;
				//imp_parts[i].close(); // TODO: check don't close input active image!!!
				
				
			}
			
			
			
			
			ImageStack stack_transform = Partition.combine ( imp_transforms, axisPartition, combineReverse );
			for (int i=0; i<imp_transforms.length; i++) { imp_transforms[i].close(); };
			
			
			
			
			if (null == stack_transform) return null;
			imp_transform = new ImagePlus(name + "-transformed", stack_transform);
			return imp_transform;
		}

		// try affine transform input image volume on GPU
		CLIJ2 clij2 = CLIJ2.getInstance();
		ClearCLBuffer source = null;
		ClearCLBuffer destination = null;
		try {
			source = clij2.push(imp);
			/* When the parts of a partitioned volume are recombined in reverse, the translation
			 * of this part has to be re-zeroed against the part's own width. */
			if (combineReverse) {
				int dim = 0; // default parition dimension is X axis;
				if (axisPartition.toLowerCase().equals("y")) dim = 1;
				if (axisPartition.toLowerCase().equals("z")) dim = 2;
				transform_matrix[dim][3] = -transform_matrix[dim][0] * source.getDimensions()[dim];
				outputsize = Transform.getTransformedDim ( dims, transform_matrix, true );
				transform = Transform.raw_to_imglib2( transform_matrix );
			}

			destination = clij2.create(outputsize, source.getNativeType());
			// apply transform with CLIJ2
			clij2.affineTransform3D(source, destination, transform.inverse());
			imp_transform = clij2.pull(destination);
			imp_transform.setTitle(name + "-transformed");
			imp_transform.changes = false;
		} catch (Exception e){
			/* Report it. The caller reads a null result as "GPU unavailable" and quietly falls
			 * back to the CPU, so without this line a real failure is invisible. */
			System.out.println(" failed attempt transform with GPU: " + e);
			imp_transform = null;
			clij2.clear();		// last resort reset after a failure of unknown extent
		} finally {
			/* Release exactly what this call allocated, rather than clearing the whole CLIJ
			 * context: the folder watcher and the TCP listener can be transforming a volume
			 * while an interactive command holds buffers of its own. */
			if (null != source)			clij2.release(source);
			if (null != destination)	clij2.release(destination);
		}
		Utils.calibrateResult ( imp_transform, imp, "deskew" );
		return imp_transform;
	}
	/**		Affine transform a volume on the GPU, choosing the partition axis automatically
	 * <br>		The axis comes from the matrix itself: only an axis the transformation does not
	 * <br>		mix with the others can be cut. X is assumed when no axis qualifies.
	 *
	 * @param imp				: input volume
	 * @param transform_matrix	: 4 x 4 transformation matrix
	 * <p>
	 * @return					: transformed volume, or null when the GPU path failed
	 */
	public static ImagePlus transform (
			ImagePlus imp,
			double[][] transform_matrix
			) {		// by default, transform image volume with auto partition along X axis
		String partitionAxis = Transform.getTransformPartitionAxis( transform_matrix );
		if (null == partitionAxis) // partition axis cannot be decided automatically
			return transform ( imp, transform_matrix, "X", false );	//TODO: need to check this
		String axis = partitionAxis.substring(0, 1);
		boolean reverse = partitionAxis.endsWith("-");
		return transform ( imp, transform_matrix, axis, reverse );
	}

	/*	Orthogonal projections
	 *
	 *	The X, Y and Z projections used to be three copies of the same method, differing only in
	 *	the output size, the axis to partition along, and which CLIJ2 primitive to call. They are
	 *	now one implementation, projection(imp, type, axis), with the per-axis facts collected in
	 *	the small helpers below; projection_x/_y/_z remain as the entry points every caller uses.
	 */

	/** Above this many slices the CLIJ median kernel is replaced by the CPU implementation. */
	private static final int MEDIAN_SLICE_LIMIT = 1000;

	/**			Reduce the spelling of a projection type to one canonical name
	 * <br>		The canonical set is max, avg, min, sum, med and std, which is what
	 * <br		Parameter.parseProjectionParameter() produces.
	 * <br>		Accepts the synonyms the dialogs and scripts have used over time, so that the
	 * <br>		result image is named the same way whichever spelling arrived.
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

	/**			Size of the 2D image left after projecting one axis away
	 *
	 * @param dims	: input dimensions in ImageJ XYCZT order
	 * @param axis	: axis being projected away: X, Y or Z
	 * <p>
	 * @return		: width and height of the projection, in CLIJ order
	 */
	private static long[] projectionOutputSize ( int[] dims, String axis ) {
		switch (axis) {
		case "X":	return new long[] { dims[3], dims[1] };	// Z - Y
		case "Y":	return new long[] { dims[0], dims[3] };	// X - Z
		default:	return new long[] { dims[0], dims[1] };	// X - Y
		}
	}

	/**			Volume size after transposing the projected axis onto Z
	 * <br>		CLIJ2 only implements median and standard deviation along Z, so an X or Y
	 * <br>		projection of those types is done by transposing first.
	 *
	 * @param dims	: input dimensions in ImageJ XYCZT order
	 * @param axis	: axis being projected away: X or Y
	 * <p>
	 * @return		: transposed volume size, in CLIJ order
	 */
	private static long[] projectionTransposedSize ( int[] dims, String axis ) {
		if ("X".equals(axis)) return new long[] { dims[3], dims[1], dims[0] };	// ZYX
		return new long[] { dims[0], dims[3], dims[1] };							// XZY
	}

	/**			Axis a volume may be cut along when the projection does not fit on the GPU
	 * <br>		Never the axis being projected away: the parts have to be projected
	 * <br>		independently and concatenated, which only works across a surviving axis.
	 *
	 * @param axis	: axis being projected away: X, Y or Z
	 * <p>
	 * @return		: axis to partition the input along
	 */
	private static String projectionPartitionAxis ( String axis ) {
		return "X".equals(axis) ? "Z" : "X";
	}

	/**			Run the CLIJ2 projection primitive for one type and axis
	 *
	 * @param clij2			: current CLIJ2 instance
	 * @param type			: canonical projection type: max, avg, min or sum
	 * @param axis			: axis being projected away: X, Y or Z
	 * @param source		: input volume on the GPU
	 * @param destination	: 2D result buffer on the GPU
	 */
	private static void projectionPrimitive (
			CLIJ2 clij2,
			String type,
			String axis,
			ClearCLBuffer source,
			ClearCLBuffer destination
			) {
		switch (type) {
		case "max":
			if ("X".equals(axis))		clij2.maximumXProjection(source, destination);
			else if ("Y".equals(axis))	clij2.maximumYProjection(source, destination);
			else						clij2.maximumZProjection(source, destination);
			break;
		case "avg":
			if ("X".equals(axis))		clij2.meanXProjection(source, destination);
			else if ("Y".equals(axis))	clij2.meanYProjection(source, destination);
			else						clij2.meanZProjection(source, destination);
			break;
		case "min":
			if ("X".equals(axis))		clij2.minimumXProjection(source, destination);
			else if ("Y".equals(axis))	clij2.minimumYProjection(source, destination);
			else						clij2.minimumZProjection(source, destination);
			break;
		case "sum":
			if ("X".equals(axis))		clij2.sumXProjection(source, destination);
			else if ("Y".equals(axis))	clij2.sumYProjection(source, destination);
			else						clij2.sumZProjection(source, destination);
			break;
		}
	}

	/**			Project one axis of an image stack away, on the GPU
	 * <br>		Hyperstacks are handled slice group by slice group, volumes too large for the
	 * <br>		device are cut along a surviving axis and recombined, and everything else is
	 * <br>		pushed to the GPU once.
	 * <p>		Every buffer this method creates is released in its own finally block, so a
	 * <br>		failure part way through cannot leak GPU memory and no other command's buffers
	 * <br>		are disturbed; the whole-context clear() is kept only as a reset after an error.
	 *
	 * @param imp		: input ImagePlus, should be image stack
	 * @param type		: type of projection: max, avg, min, sum, med, std
	 * @param axis		: axis to project away: X, Y or Z
	 * <p>
	 * @return			: output ImagePlus, as 2D projection image; null if GPU process failed
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
		String operation = "projection_" + axis.toLowerCase();
		String title = name + "-" + kind + axis + "projection";
		int[] dims = imp.getDimensions(true);	// XYCZT
		ImagePlus imp_proj = null;

		// in case input imp is hyperstack, process one channel/frame at a time as a Map
		if (dims[2] > 1 || dims[4] > 1) {
			Parameter tempParam = Parameter.scratch();
			tempParam.projType = kind;		// an argument for processMap, not a user setting
			imp_proj = Partition.processHyperstack (imp, tempParam, operation, true);
			imp_proj.setTitle( title );
			Utils.calibrateResult ( imp_proj, imp, operation );
			return imp_proj;
		}

		// in case input imp size is too big, partition the volume along a surviving axis
		if ( !checkImageSize(imp) ) {
			long[] outputSize = projectionOutputSize ( dims, axis );
			int numPartition = Partition.guessNumPartition (
					dims, new long[] { outputSize[0], outputSize[1], 1 }, imp.getBytesPerPixel() );
			int[][] idx_parts = Partition.partition_roi ( imp, projectionPartitionAxis(axis), numPartition );
			ImagePlus[] imp_projs = new ImagePlus[numPartition];
			for (int i=0; i<numPartition; i++) {
				imp_projs[i] = projection ( Partition.partition_imp (imp, idx_parts[i]), kind, axis );
				if (null == imp_projs[i]) return null;
			}
			ImageStack stack_proj = Partition.combine ( imp_projs, "X", false );
			for (int i=0; i<imp_projs.length; i++) { imp_projs[i].close(); }
			if (null == stack_proj) return null;
			imp_proj = new ImagePlus( title, stack_proj );
			Utils.calibrateResult ( imp_proj, imp, operation );
			return imp_proj;
		}

		// project the whole volume on the GPU
		CLIJ2 clij2 = CLIJ2.getInstance();
		ClearCLBuffer source = null;
		ClearCLBuffer transposed = null;		// only for median / standard deviation of X or Y
		ClearCLBuffer destination = null;
		try {
			source = clij2.push(imp);
			long[] outputSize = projectionOutputSize ( dims, axis );

			if ( "med".equals(kind) || "std".equals(kind) ) {
				/* CLIJ2 implements median and standard deviation along Z only, so an X or Y
				 * projection of those types transposes that axis onto Z first. */
				ClearCLBuffer projected = source;
				if ( !"Z".equals(axis) ) {
					transposed = clij2.create ( projectionTransposedSize(dims, axis), source.getNativeType() );
					if ("X".equals(axis))	clij2.transposeXZ(source, transposed);
					else					clij2.transposeYZ(source, transposed);
					projected = transposed;
				}
				// the CLIJ median kernel does not scale to deep stacks: fall back to the CPU one
				if ( "med".equals(kind) && projected.getDimensions()[2] > MEDIAN_SLICE_LIMIT ) {
					ImagePlus imp_deep = "Z".equals(axis) ? imp : clij2.pull( projected );
					imp_proj = projection_medianZ ( imp_deep );
					if ( imp_deep != imp ) imp_deep.close();
					if (null != imp_proj) {
						imp_proj.setTitle( title );
						imp_proj.changes = false;
					}
					Utils.calibrateResult ( imp_proj, imp, operation );
					return imp_proj;
				}
				destination = clij2.create ( outputSize, projected.getNativeType() );
				if ("med".equals(kind))	clij2.medianZProjection(projected, destination);
				else					clij2.standardDeviationZProjection(projected, destination);
			} else {
				// sum can overflow the input type, so it always accumulates in float
				destination = clij2.create ( outputSize,
						"sum".equals(kind) ? NativeTypeEnum.Float : source.getNativeType() );
				projectionPrimitive ( clij2, kind, axis, source, destination );
			}

			imp_proj = clij2.pull(destination);
			imp_proj.setTitle( title );
			imp_proj.changes = false;
		} catch (Exception e) {
			/* Report it: the caller only sees null, which it reads as "no GPU", so a silent
			 * catch here makes a real failure look like a machine without OpenCL. */
			System.out.println(" failed attempt " + kind + axis + " projection with GPU: " + e);
			imp_proj = null;
			clij2.clear();		// last resort reset after a failure of unknown extent
		} finally {
			if (null != source)			clij2.release(source);
			if (null != transposed)		clij2.release(transposed);
			if (null != destination)	clij2.release(destination);
		}
		Utils.calibrateResult ( imp_proj, imp, operation );
		return imp_proj;
	}

	/**			Project the X axis away, on the GPU
	 *
	 * @param imp				: input ImagePlus, should be image stack
	 * @param type				: type of projection: max, avg, min, sum, med, std
	 * <p>
	 * @return imp_xProj		: output ImagePlus, as X projection (ZY) 2D image; null if GPU process failed
	 */
	public static ImagePlus projection_x (
			ImagePlus imp,
			String type			// max, mean, min, sum, median, stdev
			) {
		return projection ( imp, type, "X" );
	}

	/**			Project the Y axis away, on the GPU
	 *
	 * @param imp				: input ImagePlus, should be image stack
	 * @param type				: type of projection: max, avg, min, sum, med, std
	 * <p>
	 * @return					: output ImagePlus, as Y projection (XZ) 2D image; null if GPU process failed
	 */
	public static ImagePlus projection_y (
			ImagePlus imp,
			String type			// max, mean, min, sum, median, stdev
			) {
		return projection ( imp, type, "Y" );
	}

	/**			Project the Z axis away, on the GPU
	 *
	 * @param imp				: input ImagePlus, should be image stack
	 * @param type				: type of projection: max, avg, min, sum, med, std
	 * <p>
	 * @return					: output ImagePlus, as Z projection (XY) 2D image; null if GPU process failed
	 */
	public static ImagePlus projection_z (
			ImagePlus imp,
			String type			// max, mean, min, sum, median, stdev
			) {
		return projection ( imp, type, "Z" );
	}

	
	/**			Custom written median Z projection to account for stack with more than 1000 slices.
	 * <br>		The CLIJ median kernel holds one value per slice per thread, so it stops scaling
	 * <br>		well past roughly a thousand slices; this walks the stack on the CPU instead.
	 *
	 * @param imp	: input volume
	 * <p>
	 * @return		: 2D median projection along Z, or null when the input was null
	 */
	public static ImagePlus projection_medianZ (
			ImagePlus imp
			) {
		if (null == imp) return null;
		int numZ = imp.getNSlices();
		if (numZ < 1000) return projection_z ( imp, "med" );
		String name = Utils.getName(imp);
		ImagePlus imp_zMedProj = null;
		int[] dims = imp.getDimensions(true);
		long[] outputsize_z = {dims[0], dims[1]};	// X - Y
		
		// in case input imp is hyperstack, process hyperstack as Map
		if ( imp.isHyperStack() ) {	// input is hyperstack //TODO: check isHyperStack function properly?
			Parameter tempParam = Parameter.scratch();
			tempParam.projType = "med";		// an argument for processMap, not a user setting
			imp_zMedProj = Partition.processHyperstack (imp, tempParam, "projection_z", true);
			imp_zMedProj.setTitle(name + "-medZprojection");
			Utils.calibrateResult ( imp_zMedProj, imp, "projection_z" );
			return imp_zMedProj;
		}
				
		// in case input imp size is too big, parition image along X axis automatically
		if ( !checkImageSize(imp) ) {
			int numPartition = Partition.guessNumPartition(dims, new long[] {dims[0], dims[1], 1}, imp.getBytesPerPixel());
			ImagePlus[] imp_parts = Partition.partition ( imp, "X", numPartition );
			ImagePlus[] imp_zProjs = new ImagePlus[imp_parts.length];
			for (int i=0; i<imp_parts.length; i++) {
				imp_zProjs[i] = projection_medianZ (imp_parts[i]);
				if (null == imp_zProjs[i]) return null;
			}
			ImageStack stack_zProj = Partition.combine ( imp_zProjs, "X", false );
			if (null == stack_zProj) return null;
			imp_zMedProj = new ImagePlus(name + "-medZprojection", stack_zProj);
			Utils.calibrateResult ( imp_zMedProj, imp, "projection_z" );
			return imp_zMedProj;
		}
		
		// try GPU median Z projection on averaged Z substacks
		try {
			CLIJ2 clij2 = CLIJ2.getInstance();
			ClearCLBuffer source = clij2.push( imp );
			ClearCLBuffer destination_zAvg = clij2.create( outputsize_z, source.getNativeType() );
			// create Z avg-substack that contains less than 1000 slices
			int step_avg = (int) Math.ceil( (double)numZ / (double)1000 );
			int numZ_avg = (int) Math.ceil( (double)numZ / (double)step_avg );
			ImagePlus[] imp_zAvgs = new ImagePlus[numZ_avg];
			int zBegin = 0;  int zEnd = 0;
			for (int i=0; i<numZ_avg; i++) {			
				zBegin = 1 + (i * step_avg);
				zEnd = ( i + 1 ) * step_avg;
				if (zBegin == numZ) {
					imp_zAvgs[i] =  new Duplicator().run(imp, numZ, numZ);
					break;
				}
				if (zEnd > numZ) zEnd = numZ;
				clij2.meanZProjectionBounded(source, destination_zAvg, zBegin, zEnd);
				imp_zAvgs[i] = clij2.pull(destination_zAvg);
			}
			clij2.clear();
			ImagePlus imp_zAvg = ImagesToStack.run( imp_zAvgs );
			imp_zMedProj = projection_z (imp_zAvg, "med");
			imp_zMedProj.setTitle(name + "-medZprojection");
			imp_zMedProj.changes = false;	
		} catch (Exception e){
			CLIJ2.getInstance().clear();
		}
		Utils.calibrateResult ( imp_zMedProj, imp, "projection_z" );
		return imp_zMedProj;	
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
		ImagePlus imp_permute = null;
		int[] dims = imp.getDimensions(true);		// XY CZT
		
		// in case input imp is hyperstack, process hyperstack as Map
		if (dims[2] > 1 || dims[4] >1) {
			Parameter tempParam = Parameter.scratch();
			tempParam.permuteStr = permuteString;
			imp_permute = Partition.processHyperstack (imp, tempParam, "permute", true);
			imp_permute.setTitle("GPU_permute_imp");
			Utils.calibrateResult ( imp_permute, imp, permuteString );
			return imp_permute;
		}
		
		// in case input imp size is too big, and only for YZX case, parition image along X axis and then combine along Z
		if ( !checkImageSize(imp) && permuteString.equals("->YZX")) {
			int numPartition = Partition.guessNumPartition(dims, dims, imp.getBytesPerPixel());
			ImagePlus[] imp_parts = Partition.partition ( imp, "X", numPartition );
			ImagePlus[] imp_permutes = new ImagePlus[imp_parts.length];
			for (int i=0; i<imp_parts.length; i++) {
				imp_permutes[i] = permute (imp_parts[i], permuteString);
				if (null == imp_permutes[i]) return null;
			}
			ImageStack stack_permute = Partition.combine ( imp_permutes, "Z", false );
			if (null == stack_permute) return null;
			imp_permute = new ImagePlus(name + "-(XYZ" + permuteString + ")", stack_permute);
			Utils.calibrateResult ( imp_permute, imp, permuteString );
			return imp_permute;
		}
		
		// try GPU permutation
		long[] outputsize = {dims[0], dims[1], dims[3]};	// XY CZT
		try {
			CLIJ2 clij2 = CLIJ2.getInstance();
			
			switch (permuteString) {		// "->YZX", "->ZYX", "->XZY", "->ZXY", "->YXZ", "->XYZ"
			case "->YZX":	// XYZ -> YZX (axis 123 -> 231 );  reslice from left
				ClearCLBuffer source = clij2.push(imp);
				outputsize = new long[]{dims[1], dims[3], dims[0]};
				ClearCLBuffer destination_yzx = clij2.create(outputsize, source.getNativeType());
				clij2.resliceLeft(source, destination_yzx);		// xyz to yzx
				clij2.release(source);
				imp_permute = clij2.pull(destination_yzx);
				clij2.release(destination_yzx);
				clij2.clear();
				break;
				
			case "->XYZ":	// XYZ -> XYZ (axis 123 -> 123 );   no permutation
				imp_permute = imp.duplicate();					// xyz to xyz (no permutation) // somehow faster than GPU copy
				break;
				
			case "->YXZ":	// XYZ -> YXZ (axis 123 -> 213 );  // xyz to yxz	transpose xy
			case "->ZYX":	// XYZ -> ZYX (axis 123 -> 321 );  // xyz to zyx	transpose xz
			case "->XZY":	// XYZ -> XZY (axis 123 -> 132 );  // xyz to xzy	transpose yz
				imp_permute = transpose(imp, permuteString);
				break;
					
			case "->ZXY":	// XYZ -> ZXY (axis 123 -> 312 )
				ImagePlus imp_xzy = transpose ( imp, "yz" );		// xyz to xzy
				imp_permute = transpose ( imp_xzy, "xy" );		// xzy to zxy
				imp_xzy.close();
				break;
	
			}
			imp_permute.setTitle(name + "-(XYZ" + permuteString + ")");
			imp_permute.changes = false;
		} catch (Exception e){
		}
		Utils.calibrateResult ( imp_permute, imp, permuteString );
		return imp_permute;
	}
	

	
	/**
	 * 
	 * @param imp				: input ImagePlus, should be image stack
	 * @param tranposeString	: String to indicate the type of transpose:
	 * <br>						  "xy",	  	"xz",	 	"yz"
	 * <br>						  "->YXZ", 	"->ZYX", 	"->XZY"
	 * <p>
	 * @return					: output ImagePlus, as X-Y transposed image stack; null if GPU process failed
	 */
	public static ImagePlus transpose (
			ImagePlus imp,
			String tranposeString
			) {
		if (null == imp) return null;
		String name = Utils.getName(imp);	
		ImagePlus imp_transpose = null;
		int[] dims = imp.getDimensions(true);
		
		// in case input imp is hyperstack, process hyperstack as Map
		if (dims[2] > 1 || dims[4] >1) {
			Parameter tempParam = Parameter.scratch();
			tempParam.permuteStr = tranposeString;
			imp_transpose = Partition.processHyperstack (imp, tempParam, "transpose", true);
			imp_transpose.setTitle(name + "-(XYZ" + tranposeString + ")");
			Utils.calibrateResult ( imp_transpose, imp, tranposeString );
			return imp_transpose;	
		}
		
		// in case input imp size is too big, parition image along corresponding axis automatically
		if ( !checkImageSize(imp) ) {
			int numPartition = Partition.guessNumPartition(dims, dims, imp.getBytesPerPixel());
			ImagePlus[] imp_parts;  ImagePlus[] imp_transposes;
			ImageStack stack_transpose = null;
			
			switch (tranposeString.toLowerCase()) {	//"->YXZ", "->ZYX", "->XZY"
			case "xy":		// swap 1st and 2nd dimension
			case "yx":
			case "->yxz":
				imp_parts = Partition.partition ( imp, "X", numPartition );
				imp_transposes = new ImagePlus[imp_parts.length];
				for (int i=0; i<imp_parts.length; i++) {
					imp_transposes[i] = transpose (imp_parts[i], tranposeString);
					if (null == imp_transposes[i]) return null;
				}
				stack_transpose = Partition.combine ( imp_transposes, "Y", false );
				if (null == stack_transpose) return null;
				break;
				
			case "xz":		// swap 1st and 3rd dimension
			case "zx":
			case "->zyx":	// XYZ -> ZYX (axis 123 -> 321 )
				imp_parts = Partition.partition ( imp, "Y", numPartition );
				imp_transposes = new ImagePlus[imp_parts.length];
				for (int i=0; i<imp_parts.length; i++) {
					imp_transposes[i] = transpose (imp_parts[i], tranposeString);
					if (null == imp_transposes[i]) return null;
				}
				stack_transpose = Partition.combine ( imp_transposes, "Y", false );
				if (null == stack_transpose) return null;
				break;
				
			case "yz":		// swap 2nd and 3rd dimension
			case "zy":
			case "->xzy":	// XYZ -> XZY (axis 123 -> 132 )
				imp_parts = Partition.partition ( imp, "X", numPartition );
				imp_transposes = new ImagePlus[imp_parts.length];
				for (int i=0; i<imp_parts.length; i++) {
					imp_transposes[i] = transpose (imp_parts[i], tranposeString);
					if (null == imp_transposes[i]) return null;
				}
				stack_transpose = Partition.combine ( imp_transposes, "X", false );
				if (null == stack_transpose) return null;
				break;	
			}
			imp_transpose = new ImagePlus(name + "-(XYZ" + tranposeString + ")", stack_transpose);
			Utils.calibrateResult ( imp_transpose, imp, tranposeString );
			return imp_transpose;
		}
		
		// try GPU transpose
		long[] outputsize = {dims[0], dims[1], dims[3]};
		try {
			CLIJ2 clij2 = CLIJ2.getInstance();
			ClearCLBuffer source = clij2.push(imp);
			
			switch (tranposeString.toLowerCase()) {	//"->YXZ", "->ZYX", "->XZY"
			case "xy":		// swap 1st and 2nd dimension
			case "yx":
			case "->yxz":	// XYZ -> YXZ ( axis 123 -> 213 )
				outputsize = new long[]{dims[1], dims[0], dims[3]};
				ClearCLBuffer destination_yxz = clij2.create(outputsize, source.getNativeType());
				clij2.transposeXY(source, destination_yxz);	// xyz to yxz
				clij2.release(source);
				imp_transpose = clij2.pull(destination_yxz);
				clij2.release(destination_yxz);
				break;
				
			case "xz":		// swap 1st and 3rd dimension
			case "zx":
			case "->zyx":	// XYZ -> ZYX (axis 123 -> 321 )
				outputsize = new long[]{dims[3], dims[1], dims[0]};
				ClearCLBuffer destination_zyx = clij2.create(outputsize, source.getNativeType());
				clij2.transposeXZ(source, destination_zyx);	// xyz to zyx
				clij2.release(source);
				imp_transpose = clij2.pull(destination_zyx);
				clij2.release(destination_zyx);
				break;
				
			case "yz":		// swap 2nd and 3rd dimension
			case "zy":
			case "->xzy":	// XYZ -> XZY (axis 123 -> 132 )
				outputsize = new long[]{dims[0], dims[3], dims[1]};
				ClearCLBuffer destination_xzy = clij2.create(outputsize, source.getNativeType());
				clij2.transposeYZ(source, destination_xzy);	// xyz to xzy
				clij2.release(source);
				imp_transpose = clij2.pull(destination_xzy);
				clij2.release(destination_xzy);
				break;	
			}
			clij2.clear();
			imp_transpose.setTitle(name + "-(XYZ" + tranposeString + ")");
			imp_transpose.changes = false;
		} catch (Exception e){
		}
		return imp_transpose;
	}
	

	
	/**
	 * 
	 * @param imp				: input ImagePlus, should be image stack
	 * @param flip_x			: whether to flip X axis
	 * @param flip_y			: whether to flip Y axis
	 * @param flip_z			: whether to flip Z axis
	 * <p>
	 * @return					: output ImagePlus, as the axis flipped image stack; null if GPU process failed
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
		
		ImagePlus imp_flip = null;
		int[] dims = imp.getDimensions(true);
		
		// in case input imp is hyperstack, process hyperstack as Map
		if (dims[2] > 1 || dims[4] >1) {	// input is hyperstack
			Parameter tempParam = Parameter.scratch();
			tempParam.flipX = flip_x; tempParam.flipY = flip_y; tempParam.flipZ = flip_z;
			imp_flip = Partition.processHyperstack (imp, tempParam, "flip", true);
			imp_flip.setTitle(name + "-(XYZ" + filpString + ")");
			return imp_flip;	
		}
		
		// in case input imp size is too big, parition image along X axis automatically
		if ( !checkImageSize(imp) ) {
			int numPartition = Partition.guessNumPartition(dims, dims, imp.getBytesPerPixel());
			ImagePlus[] imp_parts = Partition.partition ( imp, "X", numPartition );
			ImagePlus[] imp_flips = new ImagePlus[imp_parts.length];
			for (int i=0; i<imp_parts.length; i++) {
				imp_flips[i] = flip (imp_parts[i], flip_x, flip_y, flip_z);
				if (null == imp_flips[i]) return null;
			}
			ImageStack stack_flip = Partition.combine ( imp_flips, "X", flip_x );	// if flip X axis, combine parts in reverse order
			if (null == stack_flip) return null;
			imp_flip = new ImagePlus(name + "-(XYZ" + filpString + ")", stack_flip);
			return imp_flip;
		}
		
		// try GPU flip 3D
		try {
			CLIJ2 clij2 = CLIJ2.getInstance();
			ClearCLBuffer source = clij2.push(imp);
			ClearCLBuffer destination_flip = clij2.create(source);
			clij2.flip3D(source, destination_flip, flip_x, flip_y, flip_z);
			clij2.release(source);
			imp_flip = clij2.pull(destination_flip);
			clij2.release(destination_flip);
			clij2.clear();
			imp_flip.setTitle(name + "-(XYZ" + filpString + ")");
			imp_flip.changes = false;
		} catch (Exception e){
		}
		return imp_flip;
	}
	
	
	/**		Scale a volume on the GPU
	 * <br>		Used for the preview downsample and for isotropic resampling; a negative factor
	 * <br>		mirrors that axis.
	 *
	 * @param imp		: input volume
	 * @param scale_x	: factor along X; 2.0 means twice the original size
	 * @param scale_y	: factor along Y
	 * @param scale_z	: factor along Z
	 * <p>
	 * @return			: scaled volume, or null when the GPU path failed
	 */
	public static ImagePlus scale (
			ImagePlus imp,
			double scale_x,	// 2.0 means 2 times the original
			double scale_y,
			double scale_z
			) {
		if (null == imp) return null;
		String name = Utils.getName(imp);
		
		ImagePlus imp_scale = null;
		int[] dims = imp.getDimensions(true);
		long size_x = (long) (dims[0] * scale_x);
		long size_y = (long) (dims[1] * scale_x);
		long size_z = (long) (dims[3] * scale_x);
		long[] outputsize = {size_x, size_y, size_z};
		// in case input imp size is too big, parition image along X axis automatically
		if ( !checkImageSize(imp) ) {
			int numPartition = Partition.guessNumPartition(dims, outputsize, imp.getBytesPerPixel());
			ImagePlus[] imp_parts = Partition.partition ( imp, "X", numPartition );
			ImagePlus[] imp_scales = new ImagePlus[imp_parts.length];
			for (int i=0; i<imp_parts.length; i++) {
				imp_scales[i] = scale (imp_parts[i], scale_x, scale_y, scale_z);
				if (null == imp_scales[i]) return null;
			}
			ImageStack stack_scale = Partition.combine ( imp_scales, "X", false );	// if flip X axis, combine parts in reverse order
			if (null == stack_scale) return null;
			imp_scale = new ImagePlus(name + "-rescaled", stack_scale);
			return imp_scale;
		}
		
		// try GPU scale 3D
		try {
			CLIJ2 clij2 = CLIJ2.getInstance();
			ClearCLBuffer source = clij2.push(imp);
			outputsize[0] = (long) (imp.getWidth() * scale_x);
			ClearCLBuffer destination_scale = clij2.create(outputsize, source.getNativeType());
			clij2.scale3D(source, destination_scale, scale_x, scale_y, scale_z, false);
			clij2.release(source);
			imp_scale = clij2.pull(destination_scale);
			clij2.release(destination_scale);
			clij2.clear();
			imp_scale.setTitle(name + "-rescaled");
			imp_scale.changes = false;
		} catch (Exception e){
		}
		return imp_scale;
	}
	
	
	public static ImagePlus median2D (	// check hyperstack and too large case
			ImagePlus imp,
			int radius
			) {
		if (null == imp) return null;
		String name = Utils.getName(imp);
		ImagePlus imp_median = null;
		
		// in case input imp is hyperstack, process hyperstack as Map
		if ( imp.isHyperStack() ) {	} // input is hyperstack //TODO: check isHyperStack function properly?
		
		// in case input imp size is too big, parition image along X axis automatically
		if ( !checkImageSize(imp) ) { }
		
		try {
			CLIJ2 clij2 = CLIJ2.getInstance();
			ClearCLBuffer source = clij2.push(imp);
			ClearCLBuffer destination = clij2.create(source);
			clij2.median2DBox(source, destination, radius, radius);
			clij2.release(source);
			imp_median = clij2.pull(destination);
			clij2.release(destination);
			clij2.clear();
			imp_median.setTitle(name + "-median");
			imp_median.changes = false;
		} catch (Exception e){
			CLIJ2.getInstance().clear();
		}
		Utils.calibrateResult ( imp_median, imp, "" );
		return imp_median;
	}
	
	
	
	public static ImagePlus process (
			ImagePlus imp
			) {
		if (null == imp) return null;
		String name = Utils.getName(imp);
		ImagePlus imp_process = null;
		int[] dims = imp.getDimensions(true);
		
		// in case input imp is hyperstack, process hyperstack as Map
		if ( imp.isHyperStack() ) {	// input is hyperstack //TODO: check isHyperStack function properly?
			Parameter tempParam = Parameter.scratch();
			imp_process = Partition.processHyperstack (imp, tempParam, "process", true);
			imp_process.setTitle(name + "-" + "processed");
			Utils.calibrateResult ( imp_process, imp, "process" );
			return imp_process;
		}
		
		// in case input imp size is too big, parition image along X axis automatically
		if ( !checkImageSize(imp) ) {
			int numPartition = Partition.guessNumPartition(dims, dims, imp.getBytesPerPixel());
			ImagePlus[] imp_parts = Partition.partition ( imp, "X", numPartition );
			ImagePlus[] imp_outputs = new ImagePlus[imp_parts.length];
			for (int i=0; i<imp_parts.length; i++) {
				imp_outputs[i] = process ( imp_parts[i] );
				if (null == imp_outputs[i]) return null;
			}
			ImageStack stack_process = Partition.combine ( imp_outputs, "X", false );
			if (null == stack_process) return null;
			imp_process = new ImagePlus(name + "-" + "process");
			Utils.calibrateResult ( imp_process, imp, "process" );
			return imp_process;
		}
		
		// try GPU Z projection
		try {
			CLIJ2 clij2 = CLIJ2.getInstance();
			long[] outputsize = {dims[0], dims[1], dims[3]};
			imp_process.setTitle(name + "-" + "processed");
			imp_process.changes = false;
		} catch (Exception e){
			CLIJ2.getInstance().clear();
		}
		Utils.calibrateResult ( imp_process, imp, "process" );
		return imp_process;
	}

}
