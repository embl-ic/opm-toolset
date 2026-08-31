package de.embl.iclm;


import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.ArrayUtils;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.plugin.Concatenator;
import ij.plugin.Duplicator;
import ij.plugin.HyperStackConverter;
import ij.plugin.RGBStackMerge;
import ij.plugin.frame.Recorder;
import ij.process.ImageProcessor;
import net.haesleinhuepf.clijx.imglib2cache.Clij2RichardsonLucyImglib2Cache;
import net.haesleinhuepf.clijx.imglib2cache.Lazy;
import net.haesleinhuepf.clijx.parallel.CLIJxPool;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;


public class Partition {
	
	
	
	
	/**
	 * 
	 * @param dim_source
	 * @param dim_destination
	 * @param bytesPerPixel
	 * @return
	 */
	public static int guessNumPartition (
			long[] dim_source,
			long[] dim_destination,
			int bytesPerPixel
			) {
		double source_pixelCount = 1.0d;
		for (int i=0; i<dim_source.length; i++) { source_pixelCount *= dim_source[i]; }
		double destination_pixelCount = 1.0d;
		for (int i=0; i<dim_destination.length; i++) { destination_pixelCount *= dim_destination[i]; }
		//TODO: check for hyperstack cases: pixel count
		//IJ.log("source pixel count: " + source_pixelCount);
		//IJ.log("destination pixel count: " + destination_pixelCount);
		
		double totalSizeByte = (source_pixelCount + destination_pixelCount) * (double)bytesPerPixel;
		double maxImageSizeByte = GPU.memory_size()/2; // maxImageSize_GPU();
		
		//IJ.log("totalSizeByte: " + totalSizeByte);
		//IJ.log("maxImageSizeByte: (gpu memory/2) " + maxImageSizeByte);
		
		int numPartition = (int)Math.ceil( totalSizeByte / maxImageSizeByte );
		
		//IJ.log("numPartition: " + numPartition);
		
		numPartition = Math.max( numPartition, 1 );
		return numPartition;
	}
	public static int guessNumPartition (
			int[] dim_source,
			int[] dim_destination,
			int bytePerPixel
			) {
		long[] dim_in = Arrays.stream(dim_source).asLongStream().toArray();
		long[] dim_out = Arrays.stream(dim_destination).asLongStream().toArray();
		return guessNumPartition ( dim_in, dim_out, bytePerPixel );
	}
	public static int guessNumPartition (
			int[] dim_source,
			long[] dim_destination,
			int bytePerPixel
			) {
		long[] dim_in = Arrays.stream(dim_source).asLongStream().toArray();
		return guessNumPartition ( dim_in, dim_destination, bytePerPixel );
	}
	public static int guessNumPartition (
			long[] dim_source,
			int[] dim_destination,
			int bytePerPixel
			) {
		long[] dim_out = Arrays.stream(dim_destination).asLongStream().toArray();
		return guessNumPartition ( dim_source, dim_out, bytePerPixel );
	}
	

	
	/**			Partition image into parts as ImagePlus array
	 * 
	 * @param imp
	 * @param alongAxis
	 * @param numPartition
	 * @param guessNumParts
	 * <p>
	 * @return ImagePlus[]
	 */
	public static ImagePlus[] partition (
			ImagePlus imp,
			String alongAxis,
			int numPartition
			//boolean guessNumParts
			) {
		if (null == imp) return null;
		
		//Log log = Log.getInstance();
		//long start = System.currentTimeMillis();
		String name = Utils.getName ( imp );
		// get number of partitions from image size and GPU memory size
		//double gpuMemoryByte = GPU.memory_size();
		//double maxImageSizeMB = gpuMemoryByte/2;//GPU.maxImageSize_GPU();
		//double imageSizeMB = imp.getSizeInBytes()/1024/1024;
		//if (guessNumParts) 	numPartition = (int)Math.ceil( imageSizeMB / maxImageSizeMB );
		numPartition = (int)Math.max(1, numPartition);
		
		
		
		//TODO: check here to remove unnecessary copy of data!!!
		if (numPartition == 1) 	return new ImagePlus[]{ imp.duplicate() }; // TODO: check this!!!
		//log.add("\n\tGPU capacity: %.1f MB (ideal image size: ~%.1f MB).\n", gpuMemoryByte, maxImageSizeMB);
		//log.add("\tdata size: %.1f MB.\n", imageSizeMB);
		//log.add("\tPartition data into %d parts along %s axis to fit into GPU memory.\n", numPartition, alongAxis);
		// get image dimension, and prepare partition ImagePlus array
		int width = imp.getWidth();
		int height = imp.getHeight();
		int depth = imp.getImageStackSize();
		ImagePlus[] imp_parts = new ImagePlus[numPartition];
		int idx1=0; int idx2 = 0;
		// populate partition array with cropped part of image
		switch (alongAxis.toLowerCase()) {
		case "x":	// partition along X axis, use ImageJ crop stack with active ROI, e.g.: imp.crop("stack");
			double width_partition = (double) width / (double) numPartition;
			idx1 = 0; idx2 = 0;
			for (int i=0; i<numPartition; i++) {	// ROI position is 0-based
				idx1 = idx2;
				idx2 = (int) ((1+i) * width_partition);
				idx2 = Math.min(width, idx2);
				Roi roi = new Roi(idx1, 0, idx2-idx1, height);
				Utils.hideRoi ( roi );
				imp.setRoi( roi, false );
				//Recorder.disableCommandRecording();
				imp_parts[i] = new Duplicator().run(imp); //imp.crop("stack");
				imp_parts[i].setTitle( "partX" + (i+1) + "_" + name );
				imp.deleteRoi();
			}
			break;
			
		case "y":	// partition along Y axis, use ImageJ crop stack with active ROI, e.g.: imp.crop("stack");
			double height_partition = (double) height / (double) numPartition;
			idx1 = 0; idx2 = 0;
			for (int i=0; i<numPartition; i++) {	// ROI position is 0-based
				idx1 = idx2;
				idx2 = (int) ((1+i) * height_partition);
				idx2 = Math.min(height, idx2);
				Roi roi = new Roi(0, idx1, width, idx2-idx1);
				Utils.hideRoi ( roi );
				imp.setRoi( roi, false );
				//Recorder.disableCommandRecording();
				imp_parts[i] = new Duplicator().run(imp); // imp.crop("stack");
				imp_parts[i].setTitle( "partY" + (i+1) + "_" + name );
				imp.deleteRoi();
			}
			break;
			
		case "z":	// partition along Z axis, use ImageJ crop slice selection, e.g.: imp.crop("1-20"); 
			double depth_partition = (double) depth / (double) numPartition;
			idx1 = 0; idx2 = 0;
			for (int i=0; i<numPartition; i++) {	// slice index is 1-based
				idx1 = 1 + idx2;
				idx2 = (int) (1 + (1+i) * depth_partition);
				idx2 = Math.min(depth, idx2);
				//Recorder.disableCommandRecording();
				imp_parts[i] = new Duplicator().run(imp, idx1, idx2); //imp.crop(""+idx1+"-"+idx2);
				imp_parts[i].setTitle( "partZ" + (i+1) + "_" + name );
			}	
			break;
		}
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\tPartition data takes %.3f seconds.\n\n", duration/1000);
		return imp_parts;
	}
	
	
	
	/**			Partition image into parts as ROI and crop index
	 * 
	 * @param imp
	 * @param alongAxis
	 * @param numPartition
	 * @param guessNumParts
	 * <p>
	 * @return int[][]		: 6 elements int array: x0, y0, width, height, firstSlice, lastSlice
	 */
	public static int[][] partition_roi (
			ImagePlus imp,
			String alongAxis,
			int numPartition
			//boolean guessNumParts
			) {
		if (null == imp) return null;
		
		//Log log = Log.getInstance();
		//long start = System.currentTimeMillis();

		// get number of partitions from image size and GPU memory size
		//double gpuMemoryByte = GPU.memory_size();
		//double maxImageSizeMB = gpuMemoryByte/2;//GPU.maxImageSize_GPU();
		//double imageSizeMB = imp.getSizeInBytes()/1024/1024;
		//if (guessNumParts) 	numPartition = (int)Math.ceil( imageSizeMB / maxImageSizeMB );
		numPartition = (int)Math.max(1, numPartition);
		
		//TODO: check here to remove unnecessary copy of data!!!
		if (numPartition == 1) 	return null; // TODO: check this!!!
		//log.add("\n\tGPU capacity: %.1f MB (ideal image size: ~%.1f MB).\n", gpuMemoryByte, maxImageSizeMB);
		//log.add("\tdata size: %.1f MB.\n", imageSizeMB);
		//log.add("\tPartition data into %d parts along %s axis to fit into GPU memory.\n", numPartition, alongAxis);
		// initiate ROI and crop dimension array
		int[][] partitions = new int[numPartition][6];
		// get image dimension, and prepare partition ImagePlus array
		int width = imp.getWidth();
		int height = imp.getHeight();
		int depth = imp.getImageStackSize();
		int idx1=0; int idx2 = 0;
		// populate partition array with cropped part of image
		switch (alongAxis.toLowerCase()) {
		case "x":	// partition along X axis, use ImageJ crop stack with active ROI, e.g.: imp.crop("stack");
			double width_partition = (double) width / (double) numPartition;
			idx1 = 0; idx2 = 0;
			for (int i=0; i<numPartition; i++) {	// ROI position is 0-based
				idx1 = idx2;
				idx2 = (int) ((1+i) * width_partition);
				idx2 = Math.min(width, idx2);
				partitions[i] = new int[]{idx1, 0, idx2-idx1, height, 1, depth};
			}
			break;
			
		case "y":	// partition along Y axis, use ImageJ crop stack with active ROI, e.g.: imp.crop("stack");
			double height_partition = (double) height / (double) numPartition;
			idx1 = 0; idx2 = 0;
			for (int i=0; i<numPartition; i++) {	// ROI position is 0-based
				idx1 = idx2;
				idx2 = (int) ((1+i) * height_partition);
				idx2 = Math.min(height, idx2);
				partitions[i] = new int[]{0, idx1, width, idx2-idx1, 1, depth};
			}
			break;
			
		case "z":	// partition along Z axis, use ImageJ crop slice selection, e.g.: imp.crop("1-20"); 
			double depth_partition = (double) depth / (double) numPartition;
			idx1 = 0; idx2 = 0;
			for (int i=0; i<numPartition; i++) {	// slice index is 1-based
				idx1 = 1 + idx2;
				idx2 = (int) (1 + (1+i) * depth_partition);
				idx2 = Math.min(depth, idx2);
				partitions[i] = new int[]{0, 0, width, height, idx1, idx2};
			}	
			break;
		}
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\tPartition data takes %.3f seconds.\n\n", duration/1000);
		return partitions;
	}
	
	
	public static ImagePlus partition_imp (
			ImagePlus imp,
			int[] partition_roi
			) {
		if (null == partition_roi)
			return imp;
		Roi roi = new Roi(partition_roi[0], partition_roi[1], partition_roi[2], partition_roi[3]);
		imp.setRoi( roi, false );
		Utils.hideRoi ( roi );
		ImagePlus imp_part = new Duplicator().run(imp, partition_roi[4], partition_roi[5]); //imp.crop(""+idx1+"-"+idx2);
		imp_part.setTitle( "part_" + imp.getTitle() );
		imp.deleteRoi();
		return imp_part;
	}
	
	
	
	/**			Combine Image parts into a Image stack
	 * 
	 * @param imp_parts
	 * @param alongAxis
	 * @param backwards
	 * <p>
	 * @return ImageStack
	 */
	public static ImageStack combine (
			ImagePlus[] imp_parts,
			String alongAxis,
			boolean backwards
			) {
		if (null == imp_parts || 0 == imp_parts.length) return null;
		for (int i=0; i<imp_parts.length; i++) { 
			//IJ.log("iter: " + i); 
			//int[] dim = imp_parts[i].getDimensions();
			//IJ.log("  dim: " + dim[0] + ", " + dim[1] + ", " + dim[2] + ", " + dim[3]  + ", " + dim[4]);
			
			
			if (null == imp_parts[i]) return null; 
			
		}
		//Log log = Log.getInstance();
		//long start = System.currentTimeMillis();
		// reverse image order if requested
		if (backwards) ArrayUtils.reverse(imp_parts);
		// if combine along Z axis, use ImageJ stack Concatenator
		if (alongAxis.toLowerCase().equals("z")) {
			ImagePlus imp_combine = new Concatenator().concatenate(imp_parts, false);
			//float duration = System.currentTimeMillis() - start;
			//log.add("\n\tCombine data takes %.3f seconds.\n", duration/1000);
			//for (int i=0; i<imp_parts.length; i++) { imp_parts[i].close(); };
			//IJ.run("Collect Garbage", "");
			return imp_combine.getImageStack();
		}
		// check combine along X or Y (ImageJ StackCombiner is two-fold slower)
		boolean combineHorizontal = alongAxis.toLowerCase().equals("x");
		// get combine stack dimension
		int[] dimParts = getPartsDimensions ( imp_parts );	// max(x, y, z), total(x, y, z)
		int[] dimCombine = new int[]{dimParts[0], dimParts[1], dimParts[2]};
		if (combineHorizontal)	dimCombine[0] = dimParts[3];	// total width;
		else 					dimCombine[1] = dimParts[4];	// total height;
		// prepare combine stack parameters
		ImageProcessor ip = imp_parts[0].getStack().getProcessor(1);
		ImageStack stack = new ImageStack(dimCombine[0], dimCombine[1]);
		ImageProcessor ip_combine;
		// iterate through Z slices, combine image for each slice
		for (int z=1; z<=dimCombine[2]; z++) {
            ip_combine = ip.createProcessor(dimCombine[0], dimCombine[1]);
            int posx = 0;	int posy = 0;
			for (int i=0; i<imp_parts.length; i++) {
				if (imp_parts[i].getImageStackSize() < z) continue;
				if (combineHorizontal) {
					ip_combine.insert(imp_parts[i].getStack().getProcessor(z), posx, 0);
					posx += imp_parts[i].getWidth();
				} else {
					ip_combine.insert(imp_parts[i].getStack().getProcessor(z), 0, posy);
					posy += imp_parts[i].getHeight();
				}
        	}
	        stack.addSlice(null, ip_combine);
		}
		//for (int i=0; i<imp_parts.length; i++) { imp_parts[i].close(); };
		//IJ.run("Collect Garbage", "");
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\tCombine data takes %.3f seconds.\n", duration/1000);
		return stack;
	}
	
	
	/**			Extract max and sum image dimensions of ImagePlus array
	 * 
	 * @param imp_parts
	 * <p>
	 * @return
	 */
	public static int[] getPartsDimensions (
			ImagePlus[] imp_parts
			) {
		if (null == imp_parts || 0 == imp_parts.length) return null;
		int totalWidth = 0; 	int maxWidth = 0;
		int totalHeight = 0; 	int maxHeight = 0;
		int totalDepth = 0; 	int maxDepth = 0;
		for (ImagePlus imp : imp_parts) {
			int width = imp.getWidth();
			int height = imp.getHeight();
			int depth = imp.getImageStackSize();
			totalWidth += width;	maxWidth = Math.max(maxWidth, width);
			totalHeight += height;	maxHeight = Math.max(maxHeight, height);
			totalDepth += depth;	maxDepth = Math.max(maxDepth, depth);	
		}
		return new int[] {maxWidth, maxHeight, maxDepth, totalWidth, totalHeight, totalDepth};
	}
	
	
	/**			Separate image into equal-sized left and right part
	 * 
	 * @param imp					: input image, width not neccessarily need to be even: if odd, the 1-pixel central line is duplicated
	 * @param channelString			: the way to separate and combine as the multi-channel image
	 * <p>
	 * @return
	 */
	public static ImagePlus[] separateImageLeftRight (
			ImagePlus imp, 
			String channelString,
			boolean keepInput
			) {
		if ( null == imp ) return null;
		String side = channelString.split(" ")[1]; // extracted side String: image, left, right, by, with, or & (both)
		if (side.equals("image")) return new ImagePlus[] { imp }; // TODO: check if close imp
		
		String name = Utils.getName(imp);
		int[] dims = imp.getDimensions(true);
		int width = (int) Math.ceil(dims[0]/2); // if image width is odd: the midline is duplicated in both 
		// TODO: implement code for the case that input is already have multiple channel
		//if (dims[2] > 1 || dims[4] >1) {
		
		ImagePlus imp_left = null;
		if ( !side.equals("right") ) { // need left part
			// create ROIs corresponding to the left half of the image
			Roi roiL = new Roi(0, 0, width, dims[1]);
			Utils.hideRoi ( roiL );
			imp.setRoi( roiL, false );
			//Recorder.disableCommandRecording();
			imp_left = new Duplicator().run(imp); // imp.crop("stack");
			imp.deleteRoi();
			imp_left.setTitle(name + "-left");
		}
		if ( side.equals("left") ) {
			if ( !keepInput ) imp.close();	// save RAM usage
			return new ImagePlus[] { imp_left };
		}
		
		// create ROIs corresponding to the right half of the image
		Roi roiR = new Roi(dims[0]-width, 0, width, dims[1]);
		Utils.hideRoi ( roiR );
		imp.setRoi( roiR, false );
		//Recorder.disableCommandRecording();
		ImagePlus imp_right = new Duplicator().run(imp); // imp.crop("stack");
		imp.deleteRoi();
		imp_right.setTitle(name + "-right");
		if ( side.equals("right") ) {
			if ( !keepInput ) imp.close();	// save RAM usage
			return new ImagePlus[] {imp_right};
		}
		if ( !keepInput ) imp.close();		// save RAM usage
		// the only left case should be "&" (both sides need to be returned)
		return new ImagePlus[] { imp_left, imp_right };	
	}
	public static ImagePlus[] separateImageLeftRight (
			ImagePlus imp, 
			String channelString
			) {	// by default, keep the input image
		return separateImageLeftRight ( imp, channelString, true );
	}
	
	
	/**				Separate the left and right part of image (stack)
	 * <br>			and return image array as indicated by channel string
	 * 
	 * @param imp					: input image stack, or 2D image
	 * @param channelString			: the way to separate and combine as the multi-channel image
	 * <p>
	 * @return ImagePlus[] 			: return image array, that as prepared multi-channel image
	 * <br>							  It has only 1 element, except in the case left and right 
	 * <br>							  sides of the image are needed, but separately. i.e.: {left, right}
	 */
	

	
	/**				Combine image (stack) into multi-channel image (hyper)stack
	 * 
	 * @param imp_channels		: array of images, as different channels to be combined (normally two images)
	 * <p>
	 * @return ImagePlus		: combined multi-channel image (hyper)stack
	 */
	public static ImagePlus combineChannel (
			ImagePlus[] imp_channels
			) {
		if (null == imp_channels) return null;
		int nChannels = imp_channels.length;
		if (1 == nChannels) return imp_channels[0];
		// combine the two channel image together as RGB composite image (hyper)stack
		int[] dims = imp_channels[0].getDimensions(true);
		ImageStack[] stk_channels = new ImageStack[ nChannels ];
		for (int i=0; i<nChannels; i++) { stk_channels[i] = imp_channels[i].getStack(); }
		ImagePlus imp_combined = new RGBStackMerge().createComposite( 0,0,0, stk_channels, false );
		Utils.autoSetLUTs ( imp_combined );
		imp_combined = HyperStackConverter.toHyperStack(imp_combined, nChannels*dims[2], dims[3], dims[4]);
		imp_combined.changes = false;
		return imp_combined;
	}

	
	/**			Combine images together as a time-lapse movie. Input image should be with the same dimension(s)
	 * 
	 * @param imp_timeLapse
	 * @param imp_newFrame
	 * @param title
	 */
	public static void combineTimelapse (
			ImagePlus imp_timeLapse,
			ImagePlus imp_newFrame,
			String title
			) {
		if (null == imp_newFrame || 0 == imp_newFrame.getNFrames())  return;// imp_timeLapse;
		if (null == imp_timeLapse || 0 == imp_timeLapse.getStackSize()) {
			imp_timeLapse = imp_newFrame.duplicate();
		} else {
			// by default, concatenator combine stack as time lapse
			//ImagePlus imp_concatenate = new Concatenator().concatenate(imp_timeLapse, imp_newFrame, false);
			imp_timeLapse = new Concatenator().concatenate(imp_timeLapse, imp_newFrame, false);
			//imp_timeLapse.setImage(imp_concatenate);
		}
		Utils.calibrateResult ( imp_timeLapse, imp_newFrame );
		Utils.displayImage ( imp_timeLapse, title );
		//imp_timeLapse.setTitle( title );
		//imp_timeLapse.show();
		//imp_timeLapse.updateAndRepaintWindow();
	}
	
	
	/**
	 * 
	 * @param impInput
	 * <p>
	 * @return
	 */
	public static Map<String, ImagePlus> toMap (
			ImagePlus impInput
			) {
		return toMap (impInput, 3);
	}
	/**
	 * 
	 * @param impInput
	 * @param dimension
	 * @param order
	 * <p>
	 * @return
	 */
	public static Map<String, ImagePlus> toMap (
			ImagePlus impInput,
			int dimension	// ImagePlus dimension in Map
			) {
		if (null == impInput) return null;
		Map<String, ImagePlus> impMapOutput = new HashMap<String, ImagePlus>();
		// re-arrange ImagePlus dimension to xyCZT
		//impInput = Hyperstack_rearranger.reorderHyperstack(impInput, "CZT", false, false);
		ImageStack stack = impInput.getStack();
		// get dimension size
		int[] dims = impInput.getDimensions();
		int numC = dims[2]; int numZ = dims[3]; int numT = dims[4];
		// iterate through T, C and Z, to store ImagePlus 2D or 3D into Map
		for (int t=0; t<numT; t++) {
			String pos = "";
			for (int c=0; c<numC; c++) {
				switch (dimension) {
				// TODO: unify 2D case into 3D (2024.04.18, no case to process 2D images
				case 2:		// 2D case: XY
					for (int z=0; z< numZ; z++) {
						pos = "c" + c + "z" + z + "t" + t;
						int idx = impInput.getStackIndex(c+1, z+1, t+1);
						ImagePlus slice = new ImagePlus(stack.getSliceLabel(idx), stack.getProcessor(idx));
						impMapOutput.put(pos, slice);
					}
					break;
				
				case 3:		// 3D case: XYZ
					pos = "c" + c + "t" + t;
					impMapOutput.put(pos, Utils.getImageChunk(impInput, c+1, t+1));
					break;
					
				}
			}
		}
		return impMapOutput;
	}
	
	/**
	 * 
	 * @param impMapInput
	 * @param title
	 * <p>
	 * @return
	 */
	public static ImagePlus toHyperstack (
			Map<String, ImagePlus> impMapInput,
			String title
			) {
		if (null == impMapInput || impMapInput.isEmpty()) return null;
		ImagePlus impOutput;
		int[] dims = getMapDimensions(impMapInput);
		int numC = dims[2]; int numZ = dims[3]; int numT = dims[4];
		ImageStack stack = new ImageStack(dims[0], dims[1], numC*numZ*numT);		

		for (Map.Entry<String, ImagePlus> entry : impMapInput.entrySet()) {
			String label = entry.getKey();
			ImagePlus imp = entry.getValue();
			int[] pos = getPosCZT (label);
			for (int posZ=0; posZ<imp.getStack().size(); posZ++) {
				// compute new hyperstack position
				// XYCZT: posC posZ posT: t1z1c1, t1z1c2, t1z2c1, t1z2c2, t2z1c1
				int posStack = 1 + pos[2] *numC*numZ + posZ * numC + pos[0];
				stack.setProcessor(imp.getStack().getProcessor(posZ+1), posStack);
			}
		}
		impOutput = new ImagePlus(title, stack);
		impOutput = HyperStackConverter.toHyperStack(impOutput, numC, numZ, numT);
		return impOutput;
	}
	
	/**
	 * 
	 * @param impMapInput
	 * @param parameter
	 * @param func
	 * <p>
	 * @return
	 */
	public static Map<String, ImagePlus> processMap (
			Map<String, ImagePlus> impMapInput, 
			Parameter parameter,
			String func,
			boolean tryGPU
			) {
		if (null == impMapInput || 0 == impMapInput.size()) return null;
		Map<String, ImagePlus> impMapOutput = new HashMap<String, ImagePlus>();
		for (Map.Entry<String, ImagePlus> entry : impMapInput.entrySet()) {
			ImagePlus imp_out = null;
            switch (func) {	// a ImagePlus in, ImagePlus out function
            case "transform":
            	if (tryGPU) imp_out = GPU.transform (entry.getValue(), parameter.deskewMatrix);
            	if (null == imp_out) imp_out = CPU.transform (entry.getValue(), parameter.deskewMatrix);
            	break;
            
            //case "projection":
            //	if (tryGPU) imp_out = GPU.projection (entry.getValue(), parameter.projAxis, parameter.projType);
            //	if (null == imp_out) imp_out = CPU.projection (entry.getValue(), parameter.projAxis, parameter.projType);
            //	break;
            
            case "projection_x":
            	if (tryGPU) imp_out = GPU.projection_x (entry.getValue(), parameter.projType);
            	if (null == imp_out) imp_out = CPU.projection_x (entry.getValue(), parameter.projType);
            	break;
            	
            case "projection_y":
            	if (tryGPU) imp_out = GPU.projection_y (entry.getValue(), parameter.projType);
            	if (null == imp_out) imp_out = CPU.projection_y (entry.getValue(), parameter.projType);
            	break;
            	
            case "projection_z":
            	if (tryGPU) imp_out = GPU.projection_z (entry.getValue(), parameter.projType);
            	if (null == imp_out) imp_out = CPU.projection_z (entry.getValue(), parameter.projType);
            	break;
            	
            case "permute":
            	if (tryGPU) imp_out = GPU.permute (entry.getValue(), parameter.permuteStr);
            	if (null == imp_out) imp_out = CPU.permute (entry.getValue(), parameter.permuteStr);
            	break;
            	
            case "transpose":
            	if (tryGPU) imp_out = GPU.transpose (entry.getValue(), parameter.permuteStr);
            	if (null == imp_out) imp_out = CPU.transpose (entry.getValue(), parameter.permuteStr);
            	break;
            	
            case "flip":
            	if (tryGPU) imp_out = GPU.flip (entry.getValue(), parameter.flipX, parameter.flipY, parameter.flipZ);
            	if (null == imp_out) imp_out = CPU.flip (entry.getValue(), parameter.flipX, parameter.flipY, parameter.flipZ);
            	break;
            	
            //case "fold_x":	// TODO: implement for the case input is already multi-channel
            //	if (tryGPU) imp_out = GPU.fold_x (entry.getValue());
            //	if (null == imp_out) imp_out = CPU.fold_x (entry.getValue());
            //	break;
            	
            default:
            	imp_out = entry.getValue();		// function not defined, simply return input ImagePlus	
            }
            impMapOutput.put(entry.getKey(), imp_out);
		}
		return impMapOutput;
	}

	/**
	 * 
	 * @param impInput
	 * @param parameter
	 * @param func
	 * <p>
	 * @return
	 */
	public static ImagePlus processHyperstack (
			ImagePlus impInput, 
			Parameter parameter,
			String func,
			boolean tryGPU
			) {
		Map<String, ImagePlus> mapInput = Partition.toMap (impInput);
		Map<String, ImagePlus> mapOutput = processMap (mapInput, parameter, func, tryGPU);
		ImagePlus impOutput = toHyperstack(mapOutput, impInput.getTitle() + "-" + func);
		impOutput.changes = false;
		return impOutput;
	}
	
	/**
	 * 
	 * @param impMapInput
	 * @return
	 */
	public static int[] getMapDimensions (
			Map<String, ImagePlus> impMapInput
			) {
		if (null == impMapInput || impMapInput.isEmpty()) return null;
		int mapSize = impMapInput.size();
		int[] dims = impMapInput.entrySet().stream().findFirst().get().getValue().getDimensions(true);
		
		int numC = 0; int numZ = 0; int numT = 0;
		for (String key : impMapInput.keySet()) {
			int[] pos = getPosCZT(key);
			numC = Math.max(numC, pos[0]);
			numZ = Math.max(numZ, pos[1]);
			numT = Math.max(numT, pos[2]);
		}
		numC += 1; numZ += 1; numT += 1;
		if (mapSize != numC*numZ*numT) {
			//Log.getInstance().add(
			System.out.printf(
					"dimension mismatch:"
					+ "\n\timage dim: %d, %d, %d, %d, %d"
					+ "\n\tnumC: %d, numZ: %d, numT: %d, as extraceted from Map keyset"
					+ "\n\tMap size: %d\n",
					dims[0], dims[1], dims[2], dims[3], dims[4], numC, numZ, numT, mapSize);
		}
		return new int[] {dims[0], dims[1], numC, dims[3], numT};
	}
	
	
	/**
	 * 
	 * @param key
	 * <p>
	 * @return
	 */
	public static int[] getPosCZT (
			String key
			) {
		int posC = 0; int posZ = 0; int posT = 0;
		try {
			int idxT = key.lastIndexOf("t");
			if (-1 == idxT)
				idxT = key.length();
			else
				posT = Integer.valueOf(key.substring(idxT+1, key.length()));
				
			int idxZ = key.lastIndexOf("z");
			if (-1 == idxZ)
				idxZ = idxT;
			else
				posZ = Integer.valueOf(key.substring(idxZ+1, idxT));
			
			int idxC = key.lastIndexOf("c");
			if (-1 != idxC)
				posC = Integer.valueOf(key.substring(idxC+1, idxZ));

		} catch (Exception e) { // NumberFormatException  when number not found
			System.out.println( e.getMessage() );
			//Log.getInstance().add(e.getMessage());
		}		
		return new int[] {posC, posZ, posT};
	}
	
	
	/**			Deconvolution of a 3D image with the released CLIJ2-FFT ImgLib2 cache builder.
	 * <br>		Cells are evaluated in parallel through the configured {@link CLIJxPool}.
	 * 
	 * @param imp
	 * @param psf
	 * @param convMethod
	 * @param marginSize overlap in each direction; retained for API compatibility
	 * @param numIteration
	 * @param regFactor
	 * <p>
	 * @return
	 */
	public static ImagePlus tileDeconvolution (
			ImagePlus imp,
			ImagePlus psf,
			String convMethod,
			int marginSize,
			int numIteration,
			double regFactor// , boolean nonCirculant // seems non-circulant deconv result not ideal
			) {
		int[] cellSize = CLIJ2FFTDeconvolution.defaultCellSize(imp);
		long overlap = Math.max(0, marginSize);
		return tileDeconvolution(imp, psf, convMethod, cellSize,
				new long[] { overlap, overlap, overlap }, numIteration, regFactor, false);
	}

	/**
	 * Cache-based CLIJ2-FFT deconvolution with explicit XYZ cell and overlap sizes.
	 * The method name is retained because regularized and unregularized RL are both
	 * provided by the released builder via {@code regularizationFactor}.
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static ImagePlus tileDeconvolution(
			ImagePlus imp,
			ImagePlus psf,
			String convMethod,
			int[] requestedCellSize,
			long[] requestedOverlap,
			int numIteration,
			double regFactor,
			boolean nonCirculant) {
		CLIJ2FFTDeconvolution.validateVolumes(imp, psf);
		int[] cellSize = CLIJ2FFTDeconvolution.sanitizeCellSize(imp, requestedCellSize);
		long[] overlap = CLIJ2FFTDeconvolution.sanitizeOverlap(cellSize, requestedOverlap);
		boolean safeNonCirculant = CLIJ2FFTDeconvolution.guardNonCirculant(nonCirculant);

		RandomAccessibleInterval<? extends RealType<?>> source = ImageJFunctions.wrapReal(imp);
		RandomAccessibleInterval<? extends RealType<?>> kernel = ImageJFunctions.wrapReal(psf);
		CLIJxPool pool = CLIJxPool.getInstance();
		IJ.log("CLIJ2-FFT cache deconvolution using " + pool.nInstances() + " pool worker(s):\n" +
				pool.getDetails());
		IJ.log("Cell size XYZ: " + Arrays.toString(cellSize) +
				"; overlap XYZ: " + Arrays.toString(overlap) +
				"; non-circulant: " + safeNonCirculant);

		Clij2RichardsonLucyImglib2Cache operation =
				Clij2RichardsonLucyImglib2Cache.builder()
						.rai(source)
						.psf(kernel)
						.overlap(overlap)
						.regularizationFactor((float) Math.max(0.0, regFactor))
						.numberOfIterations(Math.max(1, numIteration))
						.nonCirculant(safeNonCirculant)
						.useGPUPool(pool)
						.build();

		CachedCellImg<FloatType, ?> deconvolved = Lazy.generate(
				source, cellSize, new FloatType(),
				AccessFlags.setOf(AccessFlags.VOLATILE), operation);
		long cellCount = deconvolved.getCells().size();
		operation.setUpStatus(null, (int) Math.min(Integer.MAX_VALUE, cellCount));
		long start = System.currentTimeMillis();
		IJ.showStatus("CLIJ2-FFT: deconvolving cached cells...");
		deconvolved.getCells().parallelStream().forEach(cell -> cell.getData());
		IJ.showProgress(1.0);
		IJ.log("CLIJ2-FFT cache deconvolution completed in " +
				((System.currentTimeMillis() - start) / 1000.0) + " seconds.");

		ImagePlus result = ImageJFunctions.wrapFloat(deconvolved,
				Utils.getName(imp) + "-deconvolved");
		result.setCalibration(imp.getCalibration().copy());
		// Do not invoke clij2fftWrapper.cleanup(): released multi-GPU FFT cleanup is
		// deliberately deferred because the native cleanup routine is not thread-safe.
		return result;
	}
	

}
