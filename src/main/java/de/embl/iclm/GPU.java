package de.embl.iclm;

import ij.IJ;
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
	 * 
	 * @return
	 */
	public static long memory_size () {
		CLIJ2 clij2 = CLIJ2.getInstance();
		long gpuMemoryByte = clij2.getCLIJ().getClearCLContext().getDevice().getMaxMemoryAllocationSizeInBytes();
		return gpuMemoryByte;	// return GPU memory size in Byte
	}
	
	//public static long maxImageSize_GPU () {
	//	return memory_size()/2;		// allow max image size as 1/2 of GPU memory
	//}
	
	public static boolean checkImageSize ( ImagePlus imp ) {
		return checkImageSize (imp, 1.0);
	}
	public static boolean checkImageSize ( ImagePlus imp, double factor ) {
		double imageSizeMB = imp.getSizeInBytes();
		return (imageSizeMB*factor <= memory_size()); //maxImageSize_GPU());
	}
	
	
	/**
	 * 
	 * @param imp
	 * @param transform_matrix
	 * @param axisPartition
	 * @param combineReverse
	 * @return
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
		
		//System.out.println("debug: GPU transform 202 name: "+name);
		//System.out.println("debug: GPU transform 203 unit: "+imp.getCalibration().getUnit() );
		//System.out.println("debug: GPU transform reverse: " + combineReverse);
		
		//System.out.printf("    GPU transform begin: memory used: %d MB%n", ( IJ.currentMemory() ) / (1024*1024) );
		
		// TODO: check if hyperstack case works !!!
		if ( imp.isHyperStack() ) {
			//System.out.println("debug: GPU transform hyperstack: ");
			Parameter tempParam = Parameter.getInstance();	// parameter local to Transform class
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
			
			
			//System.out.printf("    before parition: memory used: %d MB%n", ( IJ.currentMemory() ) / (1024*1024) );
			
			//ImagePlus[] imp_parts = Partition.partition ( imp, axisPartition, numPartition );
			int[][] idx_parts = Partition.partition_roi ( imp, axisPartition, numPartition );
			
			
			//System.out.printf("    after input partition: memory used: %d MB%n", ( IJ.currentMemory() ) / (1024*1024) );
			
			//
			//ImagePlus imp_partin = null;
			ImagePlus[] imp_transforms = new ImagePlus[numPartition];
			
			//System.out.printf("    before parition iter: memory used: %d MB%n", ( IJ.currentMemory() ) / (1024*1024) );
			//long tStart = System.currentTimeMillis();
					
			for (int i=0; i<numPartition; i++) {
				
				//long iterTStart = System.currentTimeMillis();
				//long memStart = IJ.currentMemory();
				//System.out.printf("   parition %d before transform: memory used: %d MB%n", i, ( IJ.currentMemory() ) / (1024*1024) );
				
				
				//imp_transforms[i] = transform (imp_parts[i], transform_matrix, axisPartition, combineReverse);
				//imp_partin = Partition.partition_imp (imp, idx_parts[i]);
				imp_transforms[i] = transform (Partition.partition_imp (imp, idx_parts[i]), transform_matrix, axisPartition, combineReverse);
				
				//System.out.printf("   parition %d after transform: memory used: %d MB%n", i, ( IJ.currentMemory() ) / (1024*1024) );
				//IJ.log("imp input dim:" + imp_parts[i].getWidth() + ", " + imp_parts[i].getHeight()  + ", " + imp_parts[i].getNSlices());
				//IJ.log("imp output dim:" + imp_transforms[i].getWidth() + ", " + imp_transforms[i].getHeight()  + ", " + imp_transforms[i].getNSlices());
				
				if (null == imp_transforms[i]) return null;
				//imp_parts[i].close(); // TODO: check don't close input active image!!!
				//imp_partin = null;
				//IJ.run("Collect Garbage", "");
				
				
				//long iterTEnd = System.currentTimeMillis() - iterTStart;
				//long memEnd = IJ.currentMemory() - memStart;
				//System.out.printf("   parition %d takes: %.3f second%n", i, (float)iterTEnd / 1000 );
				//System.out.printf("   parition %d add memory: %d MB%n%n", i, memEnd / (1024*1024) );
			}
			
			//long tEnd = System.currentTimeMillis() - tStart;
			//System.out.printf("    parition iter in total takes: %d second%n", tEnd / 1000 );
			//System.out.printf("    after parition iter: memory used: %d MB%n%n", ( IJ.currentMemory() ) / (1024*1024) );
			
			
			//System.out.printf("    before output combine: memory used: %d MB%n", ( IJ.currentMemory() ) / (1024*1024) );
			
			ImageStack stack_transform = Partition.combine ( imp_transforms, axisPartition, combineReverse );
			for (int i=0; i<imp_transforms.length; i++) { imp_transforms[i].close(); };
			
			//IJ.run("Collect Garbage", "");
			
			//System.out.printf("    after output combine: memory used: %d MB%n", ( IJ.currentMemory() ) / (1024*1024) );
			
			
			if (null == stack_transform) return null;
			imp_transform = new ImagePlus(name + "-transformed", stack_transform);
			//Utils.calibrateResult ( imp_transform, imp, "deskew" );
			//IJ.run("Collect Garbage", "");
			return imp_transform;
		}

		// try affine transform input image volume on GPU
		try {
			CLIJ2 clij2 = CLIJ2.getInstance();
			ClearCLBuffer source = clij2.push(imp);
			//System.out.printf("\ndebug: GPU transform 246 source dim:%d, %d, %d\n",
			//		source.getDimensions()[0],
			//		source.getDimensions()[1],
			//		source.getDimensions()[2] );
			// double check here for compatibility of transform to input image dimension, re-zero if necessary
			if (combineReverse) {	//TODO: check this!!!???
				int dim = 0; // default parition dimension is X axis;
				if (axisPartition.toLowerCase().equals("y")) dim = 1;
				if (axisPartition.toLowerCase().equals("z")) dim = 2;
				transform_matrix[dim][3] = -transform_matrix[dim][0] * source.getDimensions()[dim];
				outputsize = Transform.getTransformedDim ( dims, transform_matrix, true );
				transform = Transform.raw_to_imglib2( transform_matrix );
			}
			
			ClearCLBuffer destination = clij2.create(outputsize, source.getNativeType());
			//System.out.printf("\ndebug: GPU transform 271 destination dim:%d, %d, %d\n",
			//		destination.getDimensions()[0],
			//		destination.getDimensions()[1],
			//		destination.getDimensions()[2] );
			// apply transform with CLIJ2
			clij2.affineTransform3D(source, destination, transform.inverse());
			clij2.release(source);
			imp_transform = clij2.pull(destination);
			clij2.clear();
			imp_transform.setTitle(name + "-transformed");
			imp_transform.changes = false;
		} catch (Exception e){
			System.out.println( e.getMessage() );
			CLIJ2.getInstance().clear();
		}
		Utils.calibrateResult ( imp_transform, imp, "deskew" );
		return imp_transform;
	}
	/**
	 * 
	 * @param imp
	 * @param transform_matrix
	 * <p>
	 * @return
	 */
	public static ImagePlus transform (
			ImagePlus imp,
			double[][] transform_matrix
			) {		// by default, transform image volume with auto partition along X axis
		String partitionAxis = Transform.getTransformPartitionAxis( transform_matrix );
		//System.out.printf("\n\ndebug: imp:%s, axis:%s\n\n", imp.getTitle(), partitionAxis);
		if (null == partitionAxis) // partition axis cannot be decided automatically
			return transform ( imp, transform_matrix, "X", false );	//TODO: need to check this
		String axis = partitionAxis.substring(0, 1);
		boolean reverse = partitionAxis.endsWith("-");
		return transform ( imp, transform_matrix, axis, reverse );
	}
	

	/**	TODO: here
	 * 
	 * @param imp				: input ImagePlus, should be image stack
	 * @param type				: type of projection: max, mean, min, sum, (not implemented): median, standard deviation 
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
		ImagePlus imp_xProj = null;
		int[] dims = imp.getDimensions(true);
		//TODO: check in case map return null result
		//TODO: check for hyperstack order other than CZT
		// in case input imp is hyperstack, process hyperstack as Map
		if (dims[2] > 1 || dims[4] >1) {
			Parameter tempParam = Parameter.getInstance();
			tempParam.projType = type;		// parameter local to Projection class
			imp_xProj = Partition.processHyperstack (imp, tempParam, "projection_x", true);
			imp_xProj.setTitle(name + "-" + type + "Xprojection");
			//float duration = System.currentTimeMillis() - start;
			//log.add("\n\t%s X project hyperstack data on GPU takes %.3f seconds.\n", type, duration/1000);
			Utils.calibrateResult ( imp_xProj, imp, "projection_x" );
			return imp_xProj;
		}

		// in case input imp size is too big, parition image along Y axis automatically
		if ( !checkImageSize(imp) ) {
			int numPartition = Partition.guessNumPartition(dims, new long[] {dims[3], dims[1], 1}, imp.getBytesPerPixel());
			//ImagePlus[] imp_parts = Partition.partition ( imp, "Z", numPartition );
			int[][] idx_parts = Partition.partition_roi ( imp, "Z", numPartition );
			//ImagePlus imp_partin = null;
			
			ImagePlus[] imp_xProjs = new ImagePlus[numPartition];
			
			
			for (int i=0; i<numPartition; i++) {
				long memStart = IJ.currentMemory();
				//imp_xProjs[i] = projection_x (imp_parts[i], type);
				//if (null == imp_xProjs[i]) return null;
				//imp_partin = Partition.partition_imp (imp, idx_parts[i]);
				imp_xProjs[i] = projection_x (Partition.partition_imp (imp, idx_parts[i]), type);
				if (null == imp_xProjs[i]) return null;
				//imp_partin = null;
				//IJ.run("Collect Garbage", "");
				
				long memEnd = IJ.currentMemory() - memStart;
				System.out.printf("   parition %d add memory: %d MB%n%n", i, memEnd / (1024*1024) );
				
			}
			ImageStack stack_xProj = Partition.combine ( imp_xProjs, "X", false );
			for (int i=0; i<imp_xProjs.length; i++) { imp_xProjs[i].close(); };
			//IJ.run("Collect Garbage", "");

			if (null == stack_xProj) return null;
			imp_xProj = new ImagePlus(name + "-" + type + "Xprojection", stack_xProj);
			//float duration = System.currentTimeMillis() - start;
			//log.add("\n\t%s Z project on partitions of data on GPU takes %.3f seconds.\n", type, duration/1000);
			Utils.calibrateResult ( imp_xProj, imp, "projection_x" );
			return imp_xProj;
		}
		
		// try GPU X Projection
		try {
			CLIJ2 clij2 = CLIJ2.getInstance();
			ClearCLBuffer source = clij2.push(imp);
			long[] outputsize_x = {dims[3], dims[1]};	// Z - Y
			long[] outputsize_zyx = new long[]{dims[3], dims[1], dims[0]}; // for med and std case: transpose to ZYX first
			ClearCLBuffer destination_zyx = null;
			ClearCLBuffer destination_xProj = null;
			
			switch (type.toLowerCase()) {
			case "max":
				destination_xProj = clij2.create(outputsize_x, source.getNativeType());
				clij2.maximumXProjection(source, destination_xProj);
				break;
			case "avg":
			case "mean":
				type = "avg";
				destination_xProj = clij2.create(outputsize_x, source.getNativeType());
				clij2.meanXProjection(source, destination_xProj);
				break;
			case "min":
				destination_xProj = clij2.create(outputsize_x, source.getNativeType());
				clij2.minimumXProjection(source, destination_xProj);
				break;
			case "sum":
				destination_xProj = clij2.create(outputsize_x, NativeTypeEnum.Float);
				clij2.sumXProjection(source, destination_xProj);
				break;
			case "med":	// not implemented in CLIJ, transpose to ZYX and do the Z(now X) projection
			case "median":
				destination_zyx = clij2.create(outputsize_zyx, source.getNativeType());
				clij2.transposeXZ(source, destination_zyx);
				if (source.getDimensions()[0] > 1000) {
					ImagePlus imp_zyx = clij2.pull(destination_zyx);
					clij2.clear();
					imp_xProj = projection_medianZ (imp_zyx);
					imp_zyx.close();
					imp_xProj.setTitle(name + "-medXprojection");
					imp_xProj.changes = false;
					Utils.calibrateResult ( imp_xProj, imp, "projection_x" );
					return imp_xProj;
				}
				clij2.release(source);
				destination_xProj = clij2.create(outputsize_x, destination_zyx.getNativeType());
				clij2.medianZProjection(destination_zyx, destination_xProj);
				clij2.release(destination_zyx);
				break;
			case "std":	// not implemented in CLIJ, transpose to ZYX and do the Z(now X) projection
			case "stdev":
			case "stddev":
			case "standarddeviation":
				destination_zyx = clij2.create(outputsize_zyx, source.getNativeType());
				clij2.transposeXZ(source, destination_zyx);
				clij2.release(source);
				destination_xProj = clij2.create(outputsize_x, destination_zyx.getNativeType());
				clij2.standardDeviationZProjection(destination_zyx, destination_xProj);
				clij2.release(destination_zyx);
				break;
			}
			imp_xProj = clij2.pull(destination_xProj);
			clij2.release(destination_xProj);
			clij2.clear();
			//log.add(clij2.reportMemory());
			imp_xProj.setTitle(name + "-" + type + "Xprojection");
			imp_xProj.changes = false;
		} catch (Exception e){
			//log.add(e.getMessage());
			CLIJ2.getInstance().clear();
			//log.add(" Failed attempt " + type + "X projection with GPU!");	
		}
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\t%s X project data on GPU takes %.3f seconds.\n", type, duration/1000);
		Utils.calibrateResult ( imp_xProj, imp, "projection_x" );
		return imp_xProj;
	}
	
	
	/**
	 * 
	 * @param imp				: input ImagePlus, should be image stack
	 * @param type				: type of projection: max, mean, min, sum, (not implemented): median, standard deviation 
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
		ImagePlus imp_yProj = null;
		int[] dims = imp.getDimensions(true);
		
		// in case input imp is hyperstack, process hyperstack as Map
		if (dims[2] > 1 || dims[4] >1) {
			Parameter tempParam = Parameter.getInstance();
			tempParam.projType = type;		// parameter local to Projection class
			imp_yProj = Partition.processHyperstack (imp, tempParam, "projection_y", true);
			imp_yProj.setTitle(name + "-" + type + "Yprojection");
			//float duration = System.currentTimeMillis() - start;
			//log.add("\n\t%s Y project hyperstack data on GPU takes %.3f seconds.\n", type, duration/1000);
			Utils.calibrateResult ( imp_yProj, imp, "projection_y" );
			return imp_yProj;
		}
		
		// in case input imp size is too big, parition image along X axis automatically
		if ( !checkImageSize(imp) ) {
			int numPartition = Partition.guessNumPartition(dims, new long[] {dims[0], dims[3], 1}, imp.getBytesPerPixel());
			//ImagePlus[] imp_parts = Partition.partition ( imp, "X", numPartition );
			int[][] idx_parts = Partition.partition_roi ( imp, "X", numPartition );
			//ImagePlus imp_partin = null;
			
			ImagePlus[] imp_yProjs = new ImagePlus[numPartition];
			
			for (int i=0; i<numPartition; i++) {
				long memStart = IJ.currentMemory();
				//imp_yProjs[i] = projection_y (imp_parts[i], type);
				//if (null == imp_yProjs[i]) return null;
				//imp_partin = Partition.partition_imp (imp, idx_parts[i]);
				imp_yProjs[i] = projection_y (Partition.partition_imp (imp, idx_parts[i]), type);
				if (null == imp_yProjs[i]) return null;
				//imp_partin = null;
				//IJ.run("Collect Garbage", "");	
				long memEnd = IJ.currentMemory() - memStart;
				System.out.printf("   parition %d add memory: %d MB%n%n", i, memEnd / (1024*1024) );
			}
			ImageStack stack_yProj = Partition.combine ( imp_yProjs, "X", false );
			for (int i=0; i<imp_yProjs.length; i++) { imp_yProjs[i].close(); };
			//IJ.run("Collect Garbage", "");
			
			if (null == stack_yProj) return null;
			imp_yProj = new ImagePlus(name + "-" + type + "Yprojection", stack_yProj);
			//float duration = System.currentTimeMillis() - start;
			//log.add("\n\t%s Z project on partitions of data on GPU takes %.3f seconds.\n", type, duration/1000);
			Utils.calibrateResult ( imp_yProj, imp, "projection_y" );
			return imp_yProj;
		}
		
		// try GPU Y Projection
		try {
			CLIJ2 clij2 = CLIJ2.getInstance();
			ClearCLBuffer source = clij2.push(imp);
			long[] outputsize_y = {dims[0], dims[3]};	// X - Z
			long[] outputsize_xzy = new long[]{dims[0], dims[3], dims[1]};  // for med and std case: transpose to XZY first
			ClearCLBuffer destination_xzy = null;
			ClearCLBuffer destination_yProj = null;
			
			switch (type.toLowerCase()) {
			case "max":
				destination_yProj = clij2.create(outputsize_y, source.getNativeType());
				clij2.maximumYProjection(source, destination_yProj);
				break;
			case "avg":
			case "mean":
				type = "avg";
				destination_yProj = clij2.create(outputsize_y, source.getNativeType());
				clij2.meanYProjection(source, destination_yProj);
				break;
			case "min":
				destination_yProj = clij2.create(outputsize_y, source.getNativeType());
				clij2.minimumYProjection(source, destination_yProj);
				break;
			case "sum":
				destination_yProj = clij2.create(outputsize_y, NativeTypeEnum.Float);
				clij2.sumYProjection(source, destination_yProj);
				break;
			case "med":	// not implemented in CLIJ, transpose to XZY and do the Z(now Y) projection
			case "median":
				destination_xzy = clij2.create(outputsize_xzy, source.getNativeType());
				clij2.transposeYZ(source, destination_xzy);
				if (source.getDimensions()[1] > 1000) {
					ImagePlus imp_xzy = clij2.pull(destination_xzy);
					clij2.clear();
					imp_yProj = projection_medianZ (imp_xzy);
					imp_xzy.close();
					imp_yProj.setTitle(name + "-medYprojection");
					imp_yProj.changes = false;
					Utils.calibrateResult ( imp_yProj, imp, "projection_y" );
					return imp_yProj;
				}
				clij2.release(source);
				destination_yProj = clij2.create(outputsize_y, destination_xzy.getNativeType());
				clij2.medianZProjection(destination_xzy, destination_yProj);
				clij2.release(destination_xzy);
				break;
			case "std":	// not implemented in CLIJ, transpose to XZY and do the Z(now Y) projection
			case "stdev":
			case "stddev":
			case "standarddeviation":
				destination_xzy = clij2.create(outputsize_xzy, source.getNativeType());
				clij2.transposeYZ(source, destination_xzy);
				clij2.release(source);
				destination_yProj = clij2.create(outputsize_y, destination_xzy.getNativeType());
				clij2.standardDeviationZProjection(destination_xzy, destination_yProj);
				clij2.release(destination_xzy);
				break;
			}
			imp_yProj = clij2.pull(destination_yProj);
			clij2.release(destination_yProj);
			clij2.clear();
			//log.add(clij2.reportMemory());
			imp_yProj.setTitle(name + "-" + type + "Yprojection");
			imp_yProj.changes = false;
		} catch (Exception e){
			//log.add(e.getMessage());
			CLIJ2.getInstance().clear();
			//log.add(" Failed attempt " + type + "Y projection with GPU!");	
		}
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\t%s Y project data on GPU takes %.3f seconds.\n", type, duration/1000);
		Utils.calibrateResult ( imp_yProj, imp, "projection_y" );
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
		String name = Utils.getName(imp);
		ImagePlus imp_zProj = null;
		int[] dims = imp.getDimensions(true);
		
		// in case input imp is hyperstack, process hyperstack as Map
		if ( imp.isHyperStack() ) {	// input is hyperstack //TODO: check isHyperStack function properly?
			Parameter tempParam = Parameter.getInstance();
			tempParam.projType = type;		// parameter local to Projection class
			imp_zProj = Partition.processHyperstack (imp, tempParam, "projection_z", true);
			imp_zProj.setTitle(name + "-" + type + "Zprojection");
			//float duration = System.currentTimeMillis() - start;
			//log.add("\n\t%s Z project hyperstack data on GPU takes %.3f seconds.\n", type, duration/1000);
			Utils.calibrateResult ( imp_zProj, imp, "projection_z" );
			return imp_zProj;
		}
		
		// in case input imp size is too big, parition image along X axis automatically
		if ( !checkImageSize(imp) ) {
			int numPartition = Partition.guessNumPartition(dims, new long[] {dims[0], dims[1], 1}, imp.getBytesPerPixel());
			
			//ImagePlus[] imp_parts = Partition.partition ( imp, "X", numPartition );
			int[][] idx_parts = Partition.partition_roi ( imp, "X", numPartition );
			//ImagePlus imp_partin = null;

			ImagePlus[] imp_zProjs = new ImagePlus[numPartition];
			
			for (int i=0; i<numPartition; i++) {
				long memStart = IJ.currentMemory();
				
				//imp_zProjs[i] = projection_z (imp_parts[i], type);
				//if (null == imp_zProjs[i]) return null;
				//imp_partin = Partition.partition_imp (imp, idx_parts[i]);
				imp_zProjs[i] = projection_z (Partition.partition_imp (imp, idx_parts[i]), type);
				if (null == imp_zProjs[i]) return null;
				//imp_partin = null;
				//IJ.run("Collect Garbage", "");	
				long memEnd = IJ.currentMemory() - memStart;
				System.out.printf("   parition %d add memory: %d MB%n%n", i, memEnd / (1024*1024) );
			}
			ImageStack stack_zProj = Partition.combine ( imp_zProjs, "X", false );
			for (int i=0; i<imp_zProjs.length; i++) { imp_zProjs[i].close(); };
			//IJ.run("Collect Garbage", "");
			
			if (null == stack_zProj) return null;
			imp_zProj = new ImagePlus(name + "-" + type + "Zprojection", stack_zProj);
			//float duration = System.currentTimeMillis() - start;
			//log.add("\n\t%s Z project on partitions of data on GPU takes %.3f seconds.\n", type, duration/1000);
			Utils.calibrateResult ( imp_zProj, imp, "projection_z" );
			return imp_zProj;
		}
		
		// try GPU Z projection
		try {
			CLIJ2 clij2 = CLIJ2.getInstance();
			ClearCLBuffer source = clij2.push(imp);
			long[] outputsize_z = {dims[0], dims[1]};	// X - Y
			ClearCLBuffer destination_zProj = clij2.create(outputsize_z, source.getNativeType());
			switch (type.toLowerCase()) {
			case "max":
				clij2.maximumZProjection(source, destination_zProj);
				break;
			case "avg":
			case "mean":
				type = "avg";
				clij2.meanZProjection(source, destination_zProj);
				break;
			case "min":
				clij2.minimumZProjection(source, destination_zProj);
				break;
			case "sum":
				destination_zProj = clij2.create(outputsize_z, NativeTypeEnum.Float);
				clij2.sumZProjection(source, destination_zProj);
				break;
			case "med":
			case "median":
				if (source.getDimensions()[2] > 1000) {
					clij2.release(source);
					return projection_medianZ ( imp );
				}
				clij2.medianZProjection(source, destination_zProj);
				break;
			case "std":
			case "stdev":
			case "stddev":
			case "standarddeviation":
				clij2.standardDeviationZProjection(source, destination_zProj);
				break;
			}
			imp_zProj = clij2.pull(destination_zProj);
			clij2.release(destination_zProj);
			clij2.clear();
			//log.add(clij2.reportMemory());
			imp_zProj.setTitle(name + "-" + type + "Zprojection");
			imp_zProj.changes = false;
		} catch (Exception e){
			//log.add(e.getMessage());
			CLIJ2.getInstance().clear();
			//log.add(" Failed attempt " + type + "Z projection with GPU!");	
		}
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\t%s Z project data on GPU takes %.3f seconds.\n", type, duration/1000);
		Utils.calibrateResult ( imp_zProj, imp, "projection_z" );
		return imp_zProj;
	}
	
	/**			Custom written median Z projection to account for stack with more than 1000 slices.
	 * 
	 * @param imp
	 * @return
	 */
	public static ImagePlus projection_medianZ (
			ImagePlus imp
			) {
		if (null == imp) return null;
		int numZ = imp.getNSlices();
		if (numZ < 1000) return projection_z ( imp, "med" );
		//Log log = Log.getInstance();
		//long start = System.currentTimeMillis();
		String name = Utils.getName(imp);
		ImagePlus imp_zMedProj = null;
		int[] dims = imp.getDimensions(true);
		long[] outputsize_z = {dims[0], dims[1]};	// X - Y
		
		// in case input imp is hyperstack, process hyperstack as Map
		if ( imp.isHyperStack() ) {	// input is hyperstack //TODO: check isHyperStack function properly?
			Parameter tempParam = Parameter.getInstance();
			tempParam.projType = "med";		// parameter local to Projection class
			imp_zMedProj = Partition.processHyperstack (imp, tempParam, "projection_z", true);
			imp_zMedProj.setTitle(name + "-medZprojection");
			//float duration = System.currentTimeMillis() - start;
			//log.add("\n\t%s Z project hyperstack data on GPU takes %.3f seconds.\n", type, duration/1000);
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
			//float duration = System.currentTimeMillis() - start;
			//log.add("\n\t%s Z project on partitions of data on GPU takes %.3f seconds.\n", type, duration/1000);
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
			//clij2.release(source);
			//clij2.release(destination_zAvg);
			clij2.clear();
			ImagePlus imp_zAvg = ImagesToStack.run( imp_zAvgs );
			imp_zMedProj = projection_z (imp_zAvg, "med");
			//log.add(clij2.reportMemory());
			imp_zMedProj.setTitle(name + "-medZprojection");
			imp_zMedProj.changes = false;	
		} catch (Exception e){
			//log.add(e.getMessage());
			CLIJ2.getInstance().clear();
			//log.add(" Failed attempt " + type + "Z projection with GPU!");	
		}
		Utils.calibrateResult ( imp_zMedProj, imp, "projection_z" );
		//float duration = System.currentTimeMillis() - start;
		//System.out.printf("\n\tmedian (avg)Z project data on GPU takes %.3f seconds.\n", duration/1000);
		//log.add("\n\t%s Z project data on GPU takes %.3f seconds.\n", type, duration/1000);
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
		//Log log = Log.getInstance();
		//long start = System.currentTimeMillis();
		String name = Utils.getName(imp);	
		ImagePlus imp_permute = null;
		int[] dims = imp.getDimensions(true);		// XY CZT
		
		// in case input imp is hyperstack, process hyperstack as Map
		if (dims[2] > 1 || dims[4] >1) {
			Parameter tempParam = Parameter.getInstance();
			tempParam.permuteStr = permuteString;		// TODO: check this maybe unneccessary
			imp_permute = Partition.processHyperstack (imp, tempParam, "permute", true);
			imp_permute.setTitle("GPU_permute_imp");
			//float duration = System.currentTimeMillis() - start;
			//log.add("\n\tpermute hyperstack data on GPU takes %.3f seconds.\n", duration/1000);
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
			//float duration = System.currentTimeMillis() - start;
			//log.add("\n\t%s Z project on partitions of data on GPU takes %.3f seconds.\n", type, duration/1000);
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
			//log.add(clij2.reportMemory());
			imp_permute.setTitle(name + "-(XYZ" + permuteString + ")");
			imp_permute.changes = false;
		} catch (Exception e){
			//log.add(e.getMessage());
			//log.add(" Failed attempt permute data (XYZ" + permuteString + ") with GPU!");	
		}
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\tpermute data on GPU takes %.3f seconds.\n", duration/1000);
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
		//Log log = Log.getInstance();
		//long start = System.currentTimeMillis();
		String name = Utils.getName(imp);	
		ImagePlus imp_transpose = null;
		int[] dims = imp.getDimensions(true);
		
		// in case input imp is hyperstack, process hyperstack as Map
		if (dims[2] > 1 || dims[4] >1) {
			Parameter tempParam = Parameter.getInstance();
			tempParam.permuteStr = tranposeString;		// TODO: check this maybe unneccessary
			imp_transpose = Partition.processHyperstack (imp, tempParam, "transpose", true);
			imp_transpose.setTitle(name + "-(XYZ" + tranposeString + ")");
			//float duration = System.currentTimeMillis() - start;
			//log.add("\n\ttranspose hyperstack data on GPU takes %.3f seconds.\n", duration/1000);
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
			//float duration = System.currentTimeMillis() - start;
			//log.add("\n\t%s Z project on partitions of data on GPU takes %.3f seconds.\n", type, duration/1000);
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
			//log.add(clij2.reportMemory());
			imp_transpose.setTitle(name + "-(XYZ" + tranposeString + ")");
			imp_transpose.changes = false;
		} catch (Exception e){
			//log.add(e.getMessage());
			//log.add(" Failed attempt transpose data (XYZ" + tranposeString + ") with GPU!");	
		}
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\ttranspose data on GPU takes %.3f seconds.\n", duration/1000);
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
		//Log log = Log.getInstance();
		//long start = System.currentTimeMillis();
		String name = Utils.getName(imp);
		String filpString = "->XYZ";
		if (flip_x) filpString = filpString.replace("X", "X'");
		if (flip_y) filpString = filpString.replace("Y", "Y'");
		if (flip_z) filpString = filpString.replace("Z", "Z'");
		
		ImagePlus imp_flip = null;
		int[] dims = imp.getDimensions(true);
		
		// in case input imp is hyperstack, process hyperstack as Map
		if (dims[2] > 1 || dims[4] >1) {	// input is hyperstack
			Parameter tempParam = Parameter.getInstance();
			tempParam.flipX = flip_x; tempParam.flipY = flip_y; tempParam.flipZ = flip_z;	//  TODO: check this maybe unneccessary
			imp_flip = Partition.processHyperstack (imp, tempParam, "flip", true);
			imp_flip.setTitle(name + "-(XYZ" + filpString + ")");
			//float duration = System.currentTimeMillis() - start;
			//log.add("\n\tflip X:%b, Y:%b, Z:%b, of hyperstack data on GPU takes %.3f seconds.\n", flip_x, flip_y, flip_z, duration/1000);
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
			//float duration = System.currentTimeMillis() - start;
			//log.add("\n\t%s Z project on partitions of data on GPU takes %.3f seconds.\n", type, duration/1000);
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
			//log.add(clij2.reportMemory());
			imp_flip.setTitle(name + "-(XYZ" + filpString + ")");
			imp_flip.changes = false;
		} catch (Exception e){
			//log.add(e.getMessage());
			//log.add(" Failed attempt flip data with GPU!");	
		}
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\tflip X:%b, Y:%b, Z:%b, of data on GPU takes %.3f seconds.\n", flip_x, flip_y, flip_z, duration/1000);
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
			//float duration = System.currentTimeMillis() - start;
			//log.add("\n\t%s Z project on partitions of data on GPU takes %.3f seconds.\n", type, duration/1000);
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
			//log.add(clij2.reportMemory());
			imp_scale.setTitle(name + "-rescaled");
			imp_scale.changes = false;
		} catch (Exception e){
			//log.add(e.getMessage());
			//log.add(" Failed attempt flip data with GPU!");	
		}
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\tflip X:%b, Y:%b, Z:%b, of data on GPU takes %.3f seconds.\n", flip_x, flip_y, flip_z, duration/1000);
		return imp_scale;
	}
	
	
	public static ImagePlus median2D (	// check hyperstack and too large case
			ImagePlus imp,
			int radius
			) {
		if (null == imp) return null;
		//Log log = Log.getInstance();
		//long start = System.currentTimeMillis();
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
			//log.add(e.getMessage());
			CLIJ2.getInstance().clear();
			//log.add(" Failed attempt " + type + "Z projection with GPU!");	
		}
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\t%s Z project data on GPU takes %.3f seconds.\n", type, duration/1000);
		Utils.calibrateResult ( imp_median, imp, "" );
		return imp_median;
	}
	
	
	
	public static ImagePlus process (
			ImagePlus imp
			) {
		if (null == imp) return null;
		//Log log = Log.getInstance();
		//long start = System.currentTimeMillis();
		String name = Utils.getName(imp);
		ImagePlus imp_process = null;
		int[] dims = imp.getDimensions(true);
		
		// in case input imp is hyperstack, process hyperstack as Map
		if ( imp.isHyperStack() ) {	// input is hyperstack //TODO: check isHyperStack function properly?
			Parameter tempParam = Parameter.getInstance();
			imp_process = Partition.processHyperstack (imp, tempParam, "process", true);
			imp_process.setTitle(name + "-" + "processed");
			//float duration = System.currentTimeMillis() - start;
			//log.add("\n\t%s Z project hyperstack data on GPU takes %.3f seconds.\n", type, duration/1000);
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
			//float duration = System.currentTimeMillis() - start;
			//log.add("\n\t%s Z project on partitions of data on GPU takes %.3f seconds.\n", type, duration/1000);
			Utils.calibrateResult ( imp_process, imp, "process" );
			return imp_process;
		}
		
		// try GPU Z projection
		try {
			CLIJ2 clij2 = CLIJ2.getInstance();
			//ClearCLBuffer source = clij2.push(imp);
			long[] outputsize = {dims[0], dims[1], dims[3]};
			//ClearCLBuffer destination = clij2.create(outputsize, source.getNativeType());
			//clij2.process(source, destination);
			//clij2.release(source);
			//imp_process = clij2.pull(destination);
			//clij2.release(destination);
			//clij2.clear();
			//log.add(clij2.reportMemory());
			imp_process.setTitle(name + "-" + "processed");
			imp_process.changes = false;
		} catch (Exception e){
			//log.add(e.getMessage());
			CLIJ2.getInstance().clear();
			//log.add(" Failed attempt " + type + "Z projection with GPU!");	
		}
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\t%s Z project data on GPU takes %.3f seconds.\n", type, duration/1000);
		Utils.calibrateResult ( imp_process, imp, "process" );
		return imp_process;
	}

}
