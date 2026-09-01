package de.embl.iclm;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.ImageWindow;
import ij.gui.Roi;
import ij.plugin.PlugIn;
import ij.plugin.ZProjector;
import ij.process.ImageProcessor;
import ij.process.LUT;
import inra.ijpb.data.border.BorderManager3D;
import inra.ijpb.data.border.ConstantBorder3D;
import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
import net.haesleinhuepf.clij2.CLIJ2;
import net.imglib2.realtransform.AffineTransform3D;

public class debug implements PlugIn {
	Parameter parameter;
	
	
	@Override
	public void run(String arg) {
		System.out.println("not yet implemented.");
		parameter = new Parameter("debug");
		parameter.debug();
	}
	
	
	
// TODO: GPU
		
	
		public static ImagePlus transform ( Parameter parameter ) {
				/*ImagePlus imp, 
				AffineTransform3D transform,	// not inversed
				boolean autoPartitionData, 
				int numPartition,
				boolean doInverse
				) {*/
			Log log = Log.getInstance();
			if (null == parameter.impInput) return null;
			if (parameter.doInverse) parameter.deskewMatrix = Transform.inverse(parameter.deskewMatrix);
			long start = System.currentTimeMillis();
			ImagePlus imp_transform = null;
			AffineTransform3D transform = Transform.raw_to_imglib2(parameter.deskewMatrix);
			// calculate output dimension of transformed volume
			int[] dims = parameter.impInput.getDimensions(true);
			// if input image is hyperstack, put each xyz stack into Map and process each stack and then combine them
			if (dims[2]*dims[4] > 1) {
				Map<String, ImagePlus> map_input = Partition.toMap (parameter.impInput);
				Map<String, ImagePlus> map_transform = transform (map_input, transform,	parameter.autoPartition, parameter.numPartition, parameter.doInverse);
				imp_transform = Partition.toHyperstack(map_transform, parameter.impInput.getTitle() + "-deskwed");
				imp_transform.changes = false;
				//float duration = System.currentTimeMillis() - start;
				//log.add("\n\ttransform data on GPU takes %.3f seconds.\n", duration/1000);
				return imp_transform;
			}
			// transform non-hyperstack image stack
			double m11 = transform.get(1,1); double m12 = transform.get(1,2);
			double m21 = transform.get(2,1); double m22 = transform.get(2,2);
			long newYdim = (long)Math.round( Math.abs( dims[1] * m11 + dims[3] * m12 )); // h * cos0 + d * α
			long newZdim = (long)Math.round( Math.abs( dims[1] * m21 + dims[3] * m22 ));	// h * sin0
			long[] outputsize = {dims[0], newYdim, newZdim};
			double outputsize_MB = outputsize[0] * outputsize[1] * outputsize[2] * parameter.impInput.getBytesPerPixel() /1024/1024;
			log.add("\n\tGPU transform volume dimension calculated as:\n\t%d * %d * %d pixels = %.1f MB.\n", outputsize[0], outputsize[1], outputsize[2], outputsize_MB);
			// to correct transform with clij, transform need to be inversed
			transform = Transform.inverse(transform);
			try {	
				// serializing GPU processing to horizontally partitioned image volume
				ImagePlus[] imp_parts = Partition.partition (parameter.impInput, "X", parameter.numPartition);
				ImagePlus[] imp_deskewed = new ImagePlus[imp_parts.length];
				// processing on GPU
				long start_GPU = System.currentTimeMillis();
				CLIJ2 clij2 = CLIJ2.getInstance();
				for (int i=0; i<imp_parts.length; i++) {
					long start_part = System.currentTimeMillis();
					ClearCLBuffer source = clij2.push(imp_parts[i]);
					// adjust output width to input width
					outputsize[0] = imp_parts[i].getWidth();
					ClearCLBuffer destination = clij2.create(outputsize, source.getNativeType());
					// apply transform with CLIJ2
					clij2.affineTransform3D(source, destination, transform);
					//clij2.release(source);
					imp_deskewed[i] = clij2.pull(destination);
					//clij2.release(destination);
					clij2.clear();
					imp_parts[i].close();	
					//IJ.run("Collect Garbage", "");
					float time_part = System.currentTimeMillis() - start_part;
					log.add("\tGPU transform on partition %d takes %.3f seconds.\n", i+1, time_part/1000);
				}
				float time_processing = System.currentTimeMillis() - start_GPU;
				log.add("\n\tGPU transform in total takes %.3f seconds.\n", time_processing/1000);
				// combine partition horizontally together
				ImageStack stack_deskewed = Partition.combine (imp_deskewed, "X", false);
				for (int i=0; i<imp_parts.length; i++) {
					imp_deskewed[i].close();
				}
				imp_transform = new ImagePlus(parameter.impInput.getTitle() + "-deskwed", stack_deskewed);
				imp_transform.changes = false;
				log.add(clij2.reportMemory());
				Utils.collectGarbage(); // probably slow things down, but better for memory management
			} catch ( Exception e ) {
				log.add(e.getMessage());
				log.add(" Failed attempt transform with GPU!");	
			}
			float duration = System.currentTimeMillis() - start;
			log.add("\n\ttransform data on GPU takes %.3f seconds.\n", duration/1000);
			return imp_transform;
		}
		
		/**
		 * 
		 * @param impMapInput
		 * @param transform
		 * @param autoPartitionData
		 * @param numPartition
		 * @param doInverse
		 * @return
		 */
		public static Map<String, ImagePlus> transform (
				Map<String, ImagePlus> impMapInput, 
				AffineTransform3D transform,	// not inversed
				boolean autoPartitionData, 
				int numPartition,
				boolean doInverse
				) {
			if (null == impMapInput || 0 == impMapInput.size()) return null;
			Map<String, ImagePlus> impMapOutput = new HashMap<String, ImagePlus>();
			for (Map.Entry<String, ImagePlus> entry : impMapInput.entrySet()) {
				ImagePlus imp_transform = GPU.transform (entry.getValue(), null);
				impMapOutput.put(entry.getKey(), imp_transform);
			}
			return impMapOutput;
		}
		
		/**
		 * 
		 * @param impMapInput
		 * @param axis
		 * @param type
		 * @return
		 */
		public static Map<String, ImagePlus> projection (
				Map<String, ImagePlus> impMapInput, 
				String axis, 
				String type
				) {
			if (null == impMapInput || 0 == impMapInput.size()) return null;
			Map<String, ImagePlus> impMapOutput = new HashMap<String, ImagePlus>();
			for (Map.Entry<String, ImagePlus> entry : impMapInput.entrySet()) {
				//need todo catch the exception when GPU processing failed, switch onto CPU processing
				ImagePlus imp_project = null;
				switch (axis.toLowerCase()) {
				case "x":
					imp_project = GPU.projection_x (entry.getValue(), type);
					break;
				case "y":
					imp_project = GPU.projection_y (entry.getValue(), type);
					break;
				case "z":
					imp_project = GPU.projection_z (entry.getValue(), type);
					break;
				}
				impMapOutput.put(entry.getKey(), imp_project);
			}
			return impMapOutput;
		}
		
		/**
		 * 
		 * @param impMapInput
		 * @param permuteString
		 * <p>
		 * @return
		 */
		public static Map<String, ImagePlus> permute (
				Map<String, ImagePlus> impMapInput, // image stack in the order XYZ
				String permuteString
				) {
			if (null == impMapInput || 0 == impMapInput.size()) return null;
			Map<String, ImagePlus> impMapOutput = new HashMap<String, ImagePlus>();
			for (Map.Entry<String, ImagePlus> entry : impMapInput.entrySet()) {
				ImagePlus imp_permute = GPU.permute (entry.getValue(), permuteString);
				impMapOutput.put(entry.getKey(), imp_permute);
			}
			return impMapOutput;
		}
		
		/**
		 * 
		 * @param imp
		 * @param tranposeString
		 * <p>
		 * @return
		 */
		public static Map<String, ImagePlus> transpose (
				Map<String, ImagePlus> impMapInput,
				String tranposeString
				) {
			if (null == impMapInput || 0 == impMapInput.size()) return null;
			Map<String, ImagePlus> impMapOutput = new HashMap<String, ImagePlus>();
			for (Map.Entry<String, ImagePlus> entry : impMapInput.entrySet()) {
				ImagePlus imp_transpose = GPU.transpose (entry.getValue(), tranposeString);
				impMapOutput.put(entry.getKey(), imp_transpose);
			}
			return impMapOutput;
		}
		
		/**
		 * 
		 * @param impMapInput
		 * @param flip_x
		 * @param flip_y
		 * @param flip_z
		 * <p>
		 * @return
		 */
		public static Map<String, ImagePlus> flip (
				Map<String, ImagePlus> impMapInput, 
				boolean flip_x,
				boolean flip_y,
				boolean flip_z
				) {
			if (null == impMapInput || 0 == impMapInput.size()) return null;
			Map<String, ImagePlus> impMapOutput = new HashMap<String, ImagePlus>();
			for (Map.Entry<String, ImagePlus> entry : impMapInput.entrySet()) {
				ImagePlus imp_flip = GPU.flip (entry.getValue(), flip_x, flip_y, flip_z);
				impMapOutput.put(entry.getKey(), imp_flip);
			}
			return impMapOutput;
		}
		
		/** need todo: input is already multi-channel image
		 * 
		 * @param impMapInput
		 * <p>
		 * @return
		 */
		public static Map<String, ImagePlus> fold_x (
				Map<String, ImagePlus> impMapInput
				) {
			if (null == impMapInput || 0 == impMapInput.size()) return null;
			Map<String, ImagePlus> impMapOutput = new HashMap<String, ImagePlus>();
			for (Map.Entry<String, ImagePlus> entry : impMapInput.entrySet()) {
				ImagePlus imp_fold = Permutation.fold_x (entry.getValue(), true);
				impMapOutput.put(entry.getKey(), imp_fold);
			}
			return impMapOutput;
		}
		
		/**		Copy ImagePlus on GPU
		 * <br>	slower than ImagePlus.duplicate() for unknown reason. Not recommended to use.
		 * 
		 * @param imp				: input ImagePlus, should be image stack
		 * <p>
		 * @return					: output ImagePlus, as a copy of the input image stack; null if GPU process failed
		 */
		public static ImagePlus copy (
				ImagePlus imp
				) {
			Log log = Log.getInstance();
			long start = System.currentTimeMillis();
			ImagePlus imp_copy = null;
			try {
				CLIJ2 clij2 = CLIJ2.getInstance();
				ClearCLBuffer source = clij2.push(imp);
				ClearCLBuffer destination_copy = clij2.create(source);
				clij2.copy(source, destination_copy);
				imp_copy = clij2.pull(destination_copy);
				clij2.release(source);
				clij2.release(destination_copy);
				clij2.clear();
				log.add(clij2.reportMemory());
				imp_copy.changes = false;
			} catch (Exception e){
				log.add(e.getMessage());
				log.add(" Failed attempt copy data with GPU!");	
			}
			float duration = System.currentTimeMillis() - start;
			log.add("\n\tcopy of data on GPU takes %.3f seconds.\n", duration/1000);
			return imp_copy;
		}
		
		// need todo: implement tile and un-tile of GPU processing of large image
		public static void tile (
				ImagePlus imp
				) {
			
			//CLIJ2 clij2 = CLIJ2.getInstance();

			//for (int z=0; z<numZ; z++) {
			//	for (int y=0; y<numTile; z++) {
			//		for (int x=0; x<numTile; x++) {
						//ClearCLBuffer gpuImg = clij2.pushTile(imp, x, y, z, tileWidth, tileHeight, tileDepth, margin, margin, margin);
						//tempOut = clij2.create(gpuImg.getDimensions(), NativeTypeEnum.Float);
						
						//DeconvolveRichardsonLucyFFT.deconvolveRichardsonLucyFFT(clij2, gpuImg, gpuPSF, tempOut, 100, 0.0, False);
				
						//clijx.pullTile(deconvolved, tempOut, x, y, z, tileWidth, tileHeight, tileDepth, margin, margin, margin);
			
			//		}
			//	}
			//}
			
			/*
			for x in range(numTilesXY):
				for y in range(numTilesXY):
					for z in range(numTilesZ):
				
						print str(x)+' '+str(y) + ' ' + str(z)
						
						gpuImg = clij2.pushTile(img, x, y, z, tileWidth, tileHeight, tileDepth, margin, margin, margin);
						tempOut = clij2.create(gpuImg.getDimensions(), NativeTypeEnum.Float);
						
						DeconvolveRichardsonLucyFFT.deconvolveRichardsonLucyFFT(clij2, gpuImg, gpuPSF, tempOut, 100, 0.0, False);
				
						clijx.pullTile(deconvolved, tempOut, x, y, z, tileWidth, tileHeight, tileDepth, margin, margin, margin);
			*/
			
			/*
			ClearCLBuffer source = clij2.push(imp);
			outputsize = new long[]{dims[1], dims[3], dims[0]};
			ClearCLBuffer destination_yzx = clij2.create(outputsize, source.getNativeType());
			clij2.resliceLeft(source, destination_yzx);	// xyz to yzx
			clij2.release(source);
			imp_permute = clij2.pull(destination_yzx);
			clij2.release(destination_yzx);
			clij2.clear();
			
			
			
			CLIJ2 clij2 = CLIJ2.getInstance();
			ClearCLBuffer source = clij2.push(imp);
			ImagePlus imp_out = null;
			clij2.pullTile(imp_out, source, 0, 0, 0, 0, 0, 0, 0, 0, 0);
			*/
				//clij2.pushTile(ClearCLBuffer image, 
				//	Integer tileIndexX, Integer tileIndexY, Integer tileIndexZ, 
				//	Integer width, Integer height, Integer depth, 
				//	Integer marginWidth, Integer marginHeight, 
				//	Integer image0)
				/*
				 * 1: ImagePlus 2: clear buffer
				 * 3: tile index x
				 * 4: tile index y
				 * 5: tile index z
				 * 6: width
				 * 7: height
				 * 8: depth
				 * 9: margin width
				 * 10: margin height
				 * 11: ? margin depth ?
				 */
			}
		
		
		
// TODO: Projection
		
		/**
		 * 
		 * @param imp				: input ImagePlus, should be image stack
		 * @param type				: type of projection: max, mean, min, sum, med, std 
		 * 
		 * @return imp_xProj		: output ImagePlus, as X projection 2D image
		 */
		public static ImagePlus projection_x (
				ImagePlus imp, 
				String type,
				boolean tryGPU
				) {
			//long start = System.currentTimeMillis();
			ImagePlus imp_xProj = null;
			if (tryGPU) {
				imp_xProj = GPU.projection_x (imp, type);
				if (null != imp_xProj) return imp_xProj;
			}
			
			ImagePlus imp_zyx = null;
			if (tryGPU) {
				imp_zyx = GPU.permute (imp, "->ZYX");
			}
			if (null == imp_zyx)
				imp_zyx = CPU.permute(imp, "->ZYX");
			
			if (type.toLowerCase().equals("mean")) type = "avg";
			imp_xProj = projection_z (imp_zyx, type, tryGPU);
			imp_xProj.setTitle(imp.getTitle() + " -" + type + "X projection");
			imp_xProj.changes = false;
			//float duration = System.currentTimeMillis() - start;
			//System.out.printf("\n\t%s X projection data takes %.3f seconds.\n", type, duration/1000);
			return imp_xProj;
		}
		
		
		/**
		 * 
		 * @param imp				: input ImagePlus, should be image stack
		 * @param type				: type of projection: max, mean, min, sum, med, std
		 * 
		 * @return imp_yProj		: output ImagePlus, as Y projection 2D image
		 */
		public static ImagePlus projection_y (
				ImagePlus imp, 
				String type,
				boolean tryGPU
				) {
			//long start = System.currentTimeMillis();
			ImagePlus imp_yProj = null;
			if (tryGPU) {
				imp_yProj = GPU.projection_y (imp, type);
				if (null != imp_yProj) return imp_yProj;
			}
			
			ImagePlus imp_xzy = null;
			if (tryGPU) {
				imp_xzy = GPU.transpose (imp, "->XZY");
			}
			if (null == imp_xzy)
				imp_xzy = CPU.transpose(imp, "->XZY");
			
			if (type.toLowerCase().equals("mean")) type = "avg";
			imp_yProj = projection_z (imp_xzy, type, tryGPU);
			imp_yProj.setTitle(imp.getTitle() + " -" + type + "Y projection");
			imp_yProj.changes = false;
			//float duration = System.currentTimeMillis() - start;
			//System.out.printf("\n\t%s Y projection data takes %.3f seconds.\n", type, duration/1000);
			return imp_yProj;
		}
		
		/**
		 * 
		 * @param imp				: input ImagePlus, should be image stack
		 * @param type				: type of projection: max, mean, min, sum, med, std
		 * 
		 * @return imp_zProj		: output ImagePlus, as Z projection 2D image
		 */
		public static ImagePlus projection_z (
				ImagePlus imp, 
				String type,
				boolean tryGPU
				) {
			//long start = System.currentTimeMillis();
			ImagePlus imp_zProj = null;
			if (tryGPU) {
				imp_zProj = GPU.projection_z (imp, type);
				if (null != imp_zProj) return imp_zProj;
			}
			
			String typeString = type;
			if (type.toLowerCase().equals("mean")) {typeString = "avg"; type = "avg";};
			if (type.toLowerCase().equals("med")) typeString = "median";
			if (type.toLowerCase().equals("std")) typeString = "sd";
			typeString += " all";	// this will take care of hyperstack cases
			
			imp_zProj = ZProjector.run(imp, typeString);
			imp_zProj.setTitle(imp.getTitle() + " -" + type + "Z projection");
			imp_zProj.changes = false;
			//float duration = System.currentTimeMillis() - start;
			//System.out.printf("\n\t%s Z projection data takes %.3f seconds.\n", type, duration/1000);
			return imp_zProj;
		}


// TODO: parition
		
		
		
		/**			Horizontally partition stack to fit into GPU memory
		 * 
		 * @param imp
		 * @param guessNumParts
		 * @param numPartition
		 * <p>
		 * @return
		 */
		public static ImagePlus[] partitionHorizontally (ImagePlus imp, boolean guessNumParts, int numPartition) {
			Log log = Log.getInstance();
			long start = System.currentTimeMillis();
			CLIJ2 clij2 = CLIJ2.getInstance();
			//double gpuMemoryByte = clij2.getCLIJ().getGPUMemoryInBytes();
			double gpuMemoryByte = clij2.getCLIJ().getClearCLContext().getDevice().getMaxMemoryAllocationSizeInBytes();
			double maxImageSizeByte = gpuMemoryByte / 4;
			double imageSizeByte = imp.getSizeInBytes();
			if (guessNumParts) {
				numPartition = (int)Math.ceil( imageSizeByte / maxImageSizeByte );
			} else {
				numPartition = (int)Math.max(1, numPartition);
			}
			if (numPartition <= 1) {
				return new ImagePlus[]{imp};
			}
			log.add("\n\tGPU capacity: %.1f MB (ideal image size: ~%.1f MB).\n", gpuMemoryByte/1024/1024, maxImageSizeByte/1024/1024);
			log.add("\tdata size: %.1f MB.\n", imageSizeByte/1024/1024);
			log.add("\tPartition data into %d columns to fit into GPU memory.\n", numPartition);
			
			int[] dims = imp.getDimensions();
			int width_image = dims[0];
			int width_partition = (int) Math.ceil( (double)dims[0] / (double)numPartition );
			ImagePlus[] imp_parts = new ImagePlus[numPartition];
			
			
			boolean lastPart = false;
			//imp.getWindow().setVisible(false);
			for (int i=0; i<numPartition; i++) {
				Roi roi;
				if (width_partition * (i+1) >= dims[0]) {	// last parition
					lastPart = true;
					roi = new Roi(i*width_partition, 0, dims[0] - (i*width_partition) + 1, dims[1]);
				} else {
					roi = new Roi(i*width_partition, 0, width_partition, dims[1]);
				}
				imp.setRoi(roi, false);
				//imp_parts[i] = duplicator.crop(imp);
				imp_parts[i] = imp.crop("stack");
				imp.deleteRoi();
				if (lastPart) {
					log.add("\tOriginal image data width: %d pixels.\n", width_image);
					log.add("\tPartition width: %d * %d + %d pixels.\n", width_partition, (numPartition-1), imp_parts[i].getWidth());
					break;
				}
			}
			//imp.getWindow().setVisible(true);
			float duration = System.currentTimeMillis() - start;
			log.add("\n\tPartition data takes %.3f seconds.\n\n", duration/1000);
			return imp_parts;
		}
		
		
		/**			Horizontally combine stack together
		 * 
		 * @param imp_parts
		 * <p>
		 * @return
		 */
		public static ImageStack combineHorizontally (
				ImagePlus[] imp_parts
				) {
			Log log = Log.getInstance();
			long start = System.currentTimeMillis();
			int numParts = imp_parts.length;
			ImagePlus imp_1 = imp_parts[0];
			ImagePlus imp_last = imp_parts[numParts-1];
			int[] dims = imp_1.getDimensions();
			int width = dims[0]; int height = dims[1]; int depth = dims[3];
			int totalWidth = width * (numParts-1) + imp_last.getWidth();
			
	        ImageStack stack = new ImageStack(totalWidth, height);
	        ImageProcessor ip = imp_1.getStack().getProcessor(1);
	        ImageProcessor ip_combine;
	        	
			for (int z=1; z<=depth; z++) {
	            ip_combine = ip.createProcessor(totalWidth, height);
				for (int i=0; i<numParts-1; i++) {
		            ip_combine.insert(imp_parts[i].getStack().getProcessor(z), i*width, 0);
	        	}
		        ip_combine.insert(imp_last.getStack().getProcessor(z), width*(numParts-1), 0);
		        stack.addSlice(null, ip_combine);
			}
			float duration = System.currentTimeMillis() - start;
			log.add("\n\tCombine data takes %.3f seconds.\n", duration/1000);
			return stack;
		}
		
		/**			Vertically combine stack together
		 * 
		 * @param imp_parts
		 * <p>
		 * @return
		 */
		public static ImageStack combineVertically (
				ImagePlus[] imp_parts
				) {
			Log log = Log.getInstance();
			long start = System.currentTimeMillis();
			int numParts = imp_parts.length;
			ImagePlus imp_1 = imp_parts[0];
			ImagePlus imp_last = imp_parts[numParts-1];
			int[] dims = imp_1.getDimensions();
			int width = dims[0]; int height = dims[1]; int depth = dims[3];
			int totalWidth = width * (numParts-1) + imp_last.getWidth();
			
	        ImageStack stack = new ImageStack(totalWidth, height);
	        ImageProcessor ip = imp_1.getStack().getProcessor(1);
	        ImageProcessor ip_combine;
	        	
			for (int z=1; z<=depth; z++) {
	            ip_combine = ip.createProcessor(totalWidth, height);
				for (int i=0; i<numParts-1; i++) {
		            ip_combine.insert(imp_parts[i].getStack().getProcessor(z), i*width, 0);
	        	}
		        ip_combine.insert(imp_last.getStack().getProcessor(z), 0, width*(numParts-1));
		        stack.addSlice(null, ip_combine);
			}
			float duration = System.currentTimeMillis() - start;
			log.add("\n\tCombine data takes %.3f seconds.\n", duration/1000);
			return stack;
		}
		
		
		public static ImageStack combineLongitudinally () {
			return null;
		}
		
		/*
		public static ImagePlus[] separateChannel (
				ImagePlus imp,
				String channelString,
				double[][] alignmatrix
				) {
			if (null == imp) return null;
			String name = Utils.getName(imp);
			
			
			if (channelString.equals("whole image")) return new ImagePlus[] {imp};
			//Log log = Log.getInstance();
			String name = Utils.getName(imp);
			int[] dims = imp.getDimensions(true);
			// TODO: implement code for the case that input is already have multiple channel
			//if (dims[2] > 1 || dims[4] >1) {
			int width = (int) Math.ceil(dims[0]/2);	// if image width is odd: the midline is duplicated in both 
			
			// create ROIs corresponding to the left half of the image
			Roi roiL = new Roi(0, 0, width, dims[1]);
			Utils.hideRoi ( roiL );
			imp.setRoi( roiL, false );
			ImagePlus imp_left = imp.crop("stack");
			imp.deleteRoi();
			imp_left.setTitle(name + "-left");
			if (channelString.equals("only left"))
				return new ImagePlus[] {imp_left};
			
			// create ROIs corresponding to the right half of the image
			Roi roiR = new Roi(dims[0]-width, 0, width, dims[1]);
			Utils.hideRoi ( roiR );
			imp.setRoi( roiR, false );
			ImagePlus imp_right = imp.crop("stack");
			imp.deleteRoi();
			imp_right.setTitle(name + "-right");
			if (channelString.equals("only right"))
				return new ImagePlus[] {imp_right};
			
			// create image array, for following cases:

			// in the case both left and right sides requested
			if (channelString.equals("left & right separately"))
				return new ImagePlus[] {imp_left, imp_right};
			
			
			ImagePlus[] imp_LR = separateImageLeftRight (imp, channelString ); // fold and align case not yet covered here
			
			// flip the right side and merge onto the left as 2nd channel
			if (channelString.equals("fold by midline")) {
				ImagePlus imp_fold = Permutation.fold_x ( imp, true );
				imp_fold.setTitle( name + "-xFold" );
				return new ImagePlus[] { imp_fold };
			}
			
			// flip the right side and align with left side with alignment matrix (2D rigid)
			if (channelString.equals("align with SIFT matrix")) {
				ImagePlus imp_fold = Permutation.fold_x ( imp, true );
				imp_fold.setTitle( name + "-xFoldAligned" );
				return new ImagePlus[] { imp_fold };
			}
			
			// whole image, only left, only right, or both
			return imp_LR;
		}
		*/
		
		/**		Compute the base 2 logarithm of a double value.
		 * 
		 * @param N
		 * <p>
		 * @return
		 */
		public static double log2( double N ) {
			return (Math.log(N) / Math.log(2.0d));
	    }
		
		/**		Compute the base 8 logarithm of a double value.
		 * 
		 * @param N
		 * <p>
		 * @return
		 */
		public static double log8(double N) {
			return (Math.log(N) / Math.log(8.0d));
	    }
		
		/**
		 * 
		 * @param imp
		 */
		public static void padImage (
				ImagePlus imp
				) {
			int[] dims = imp.getDimensions(true);
			int padWidth 	= 8 - Math.floorMod(dims[0], 8);
			int padHeight 	= 8 - Math.floorMod(dims[1], 8);
			int padDepth 	= 8 - Math.floorMod(dims[3], 8);
			BorderManager3D bm = new ConstantBorder3D(imp.getImageStack(), 0);
			ImageStack stack = bm.addBorders(
					imp.getImageStack(), 0, padWidth, 0, padHeight, 0, padDepth);
			imp.setStack(stack);
		}

		
// TODO: deskew
				/**			Deskew a image as ImagePlus
				 *  <br>	could be an active image in ImageJ
				 *  <br>	or from opening an image file on disk
				 * 
				 * @param impInput			: input image, keep it untouched throughout the processing
				 * @param parameter			: input OPM image stack XY pixel size, in nm
				 * @param zStepSize				: input OPM image stack Z slice physical distance, in nm (as galvo step * DU) 
				 * @param opmAngle				: OPM angle, in degree
				 * @param doInverse				: whether to perform inverse transform, for debug
				 * @param doVirtual				: result as virtual stack, from imglib2 transformation, for fast visualziation or debug
				 * @param doGPU					: whether to perform the affine transform on GPU
				 * @param autoPartition			: whether to automatically calculate the data partition based on available graphic memory and input image size
				 * @param numPartition			: number of partition for GPU processing, if set manually
				 * <p>
				 * @return imp_deskewed			: deskewed image, need to fix for imglib2 Virtual stack problem
				 */
				
				//@Override
				public void deskew_run (String arg) {
					// get parameter of deskew on the active image
					Parameter parameter = new Parameter("image");
					parameter.impInput = IJ.getImage();
					if ( !deskew_image() ) return;
					if ( null == parameter.impInput ) return;
					
					// prepare log
					Log log = new Log("OPM_deskew.log");
					log.add(parameter);
					log.add("deskew image start:");
					
					// timing the start
					long start = System.currentTimeMillis();
					
					// in case only left or right side requested
					boolean doHalf = parameter.channelStr.equals("left only") || parameter.channelStr.equals("right only");
					ImagePlus impInput = parameter.impInput;
					if (doHalf) impInput = (Partition.separateImageLeftRight(impInput, parameter.channelStr)) [0];
					
					// get deskewed image
					ImagePlus imp_deskew = Deskew.deskew_image ( impInput, parameter );
					// get channel image
					ImagePlus[] imp_deskew_channel = new ImagePlus[]{imp_deskew};
					if (!doHalf) imp_deskew_channel = Partition.separateImageLeftRight (
							imp_deskew, parameter.channelStr ) ;
					
					// display result of requested channel(s)
					for (ImagePlus imp_channel : imp_deskew_channel) {
						// display transformed stack
						imp_channel.show();
						imp_channel.setDisplayRange(parameter.impInput.getDisplayRangeMin(), parameter.impInput.getDisplayRangeMax());
						imp_channel.setZ((int)Math.round(imp_channel.getNSlices()/2));
						
						// display projection image(s)
				        if ( (parameter.projX || parameter.projY || parameter.projZ) && !imp_deskew.getStack().isVirtual() ) {
				        	// prepare projection axis string list
				    		ArrayList<String> axes = new ArrayList<String>();
				    		if (parameter.projX) axes.add("X");
				    		if (parameter.projY) axes.add("Y");
				    		if (parameter.projZ) axes.add("Z");
				    		//if (0 == axes.size()) ;
				    		// prepare projection type string list
				    		ArrayList<String> types = new ArrayList<String>();
				    		if (parameter.maxProj)	types.add("max");
				    		if (parameter.avgProj)	types.add("avg");
				    		if (parameter.minProj)	types.add("min");
				    		if (parameter.sumProj)	types.add("sum");
				    		if (parameter.medProj)	types.add("med");
				    		if (parameter.stdProj)	types.add("std");	
				    		//if (0 == types.size()) return;
				    		// create projection images
				    		for (String axis : axes) {
				    			for (String type : types) {
				    				ImagePlus imp_project = Projection.projection (imp_channel, axis, type, parameter.tryGPU);
				    				imp_project.setTitle(imp_channel.getTitle() + "-" + type + axis + " projection");
				    				imp_project.show();
				    				IJ.run(imp_project, "Enhance Contrast", "saturated=0.35");
				    			}
				    		}
						}
					}
					
					// report runtime
					float duration = System.currentTimeMillis() - start;
					log.add("\n\tdeskew finished after %.3f seconds.\n", duration / 1000);
					log.add("deskew image finish.");
					log.close();
				}
				
		
// TODO: Parameter
				
		/** 		generic constructor for Parameter class
		 * 
		 * @param obj
		 */
		public void Parameter(String obj) {
			/*
			instance = this;
			this.obj = obj;
			
			// make use of scijava parameter persistence storage	
			DefaultPrefService prefs = new DefaultPrefService();
			
			xyPixelSize =	prefs.getDouble(Double.class, 		"OPM-"+obj+"-xyPixelSize", 		xyPixelSize);
			zStepSize =		prefs.getDouble(Double.class, 		"OPM-"+obj+"-zStepSize", 			zStepSize);
			opmAngle =		prefs.getDouble(Double.class, 		"OPM-"+obj+"-opmAngle", 			opmAngle);
			
			doInverse =		prefs.getBoolean(Boolean.class, 	"OPM-"+obj+"-doInverse", 			doInverse);
			channelString = prefs.get(String.class, 			"OPM-"+obj+"-channelString", 		channelString);
			projX =			prefs.getBoolean(Boolean.class, 	"OPM-"+obj+"-projX", 				projX);
			projY = 		prefs.getBoolean(Boolean.class, 	"OPM-"+obj+"-projY", 				projY);
			projZ = 		prefs.getBoolean(Boolean.class, 	"OPM-"+obj+"-projZ", 				projZ);
			maxProj = 		prefs.getBoolean(Boolean.class, 	"OPM-"+obj+"-maxProj", 			maxProj);
			avgProj = 		prefs.getBoolean(Boolean.class, 	"OPM-"+obj+"-avgProj", 			avgProj);
			minProj = 		prefs.getBoolean(Boolean.class, 	"OPM-"+obj+"-minProj", 			minProj);
			sumProj = 		prefs.getBoolean(Boolean.class, 	"OPM-"+obj+"-sumProj", 			sumProj);
			medProj =		prefs.getBoolean(Boolean.class, 	"OPM-"+obj+"-medProj", 			medProj);
			stdProj =		prefs.getBoolean(Boolean.class, 	"OPM-"+obj+"-stdProj", 			stdProj);
			
			inputDir = 		prefs.get(String.class, 			"OPM-batch-inputDir", 			inputDir);
			keywords = 		prefs.get(String.class, 			"OPM-batch-keywords", 			keywords);
			doDeskew = 		prefs.getBoolean(Boolean.class, 	"OPM-batch-doDeskew", 			doDeskew);	
			fileExistString=prefs.get(String.class, 			"OPM-batch-fileExistString", 	fileExistString);
			
			saveDir = 		prefs.get(String.class, 			"OPM-batch-saveDir", 			saveDir);
			saveSeparate = 	prefs.getBoolean(Boolean.class, 	"OPM-batch-saveSeparate", 		saveSeparate);
			
			logPath = 		prefs.get(String.class, 			"OPM-watcher-watchLog", 		logPath);
			
			watchDir = 		prefs.get(String.class, 			"OPM-watcher-watchDir", 		watchDir);
			keywords = 		prefs.get(String.class, 			"OPM-watcher-keywords", 		keywords);
			processOld = 	prefs.getBoolean(Boolean.class, 	"OPM-watcher-processOld", 		processOld);
			overwriteExist =prefs.getBoolean(Boolean.class, 	"OPM-watcher-overwriteExist", 	overwriteExist);
			maxWait = 		prefs.getInt(Integer.class, 		"OPM-watcher-maxWait", 			maxWait);
			
			//doVirtual =	prefs.getBoolean(Boolean.class, 	"OPM-"+obj+"-doVirtual", 			doVirtual);
					//tryGPU =		prefs.getBoolean(Boolean.class, 	"OPM-"+obj+"-tryGPU", 			tryGPU);
			
			flipX = 		prefs.getBoolean(Boolean.class, 	"OPM-permute-flipX", 			flipX);
			flipY = 		prefs.getBoolean(Boolean.class, 	"OPM-permute-flipY", 			flipY);
			flipZ = 		prefs.getBoolean(Boolean.class, 	"OPM-permute-flipZ", 			flipZ);
			foldX = 		prefs.getBoolean(Boolean.class, 	"OPM-permute-foldX", 			foldX);
			permuteString = prefs.get(String.class, 			"OPM-permute-permuteString",	permuteString);
			tryGPU = 		prefs.getBoolean(Boolean.class, 	"OPM-permute-tryGPU", 			tryGPU);
			
			
			doStepTransform = 	prefs.getBoolean(Boolean.class, "OPM-debug-doStepTransform", 	doStepTransform);
			
			doVirtual = 		prefs.getBoolean(Boolean.class, "OPM-debug-doVirtual", 			doVirtual);
			tryGPU = 			prefs.getBoolean(Boolean.class, "OPM-debug-tryGPU", 			tryGPU);
			autoPartitionData = prefs.getBoolean(Boolean.class, "OPM-debug-autoPartitionData", 	autoPartitionData);
			numPartition = 		prefs.getInt(Integer.class, 	"OPM-debug-numPartition", 		numPartition);
			
			
			switch (obj.toLowerCase()) {
			
			case "image":
				xyPixelSize =	prefs.getDouble(Double.class, 		"OPM-image-xyPixelSize", 		xyPixelSize);
				zStepSize =		prefs.getDouble(Double.class, 		"OPM-image-zStepSize", 			zStepSize);
				opmAngle =		prefs.getDouble(Double.class, 		"OPM-image-opmAngle", 			opmAngle);
				//doVirtual =	prefs.getBoolean(Boolean.class, 	"OPM-image-doVirtual", 			doVirtual);
				//tryGPU =		prefs.getBoolean(Boolean.class, 	"OPM-image-tryGPU", 			tryGPU);
				doInverse =		prefs.getBoolean(Boolean.class, 	"OPM-image-doInverse", 			doInverse);
				channelString = prefs.get(String.class, 			"OPM-image-channelString", 		channelString);
				projX =			prefs.getBoolean(Boolean.class, 	"OPM-image-projX", 				projX);
				projY = 		prefs.getBoolean(Boolean.class, 	"OPM-image-projY", 				projY);
				projZ = 		prefs.getBoolean(Boolean.class, 	"OPM-image-projZ", 				projZ);
				maxProj = 		prefs.getBoolean(Boolean.class, 	"OPM-image-maxProj", 			maxProj);
				avgProj = 		prefs.getBoolean(Boolean.class, 	"OPM-image-avgProj", 			avgProj);
				minProj = 		prefs.getBoolean(Boolean.class, 	"OPM-image-minProj", 			minProj);
				sumProj = 		prefs.getBoolean(Boolean.class, 	"OPM-image-sumProj", 			sumProj);
				medProj =		prefs.getBoolean(Boolean.class, 	"OPM-image-medProj", 			medProj);
				stdProj =		prefs.getBoolean(Boolean.class, 	"OPM-image-stdProj", 			stdProj);
				break;
			
				
			case "batch":
				inputDir = 		prefs.get(String.class, 			"OPM-batch-inputDir", 			inputDir);
				keywords = 		prefs.get(String.class, 			"OPM-batch-keywords", 			keywords);
				doDeskew = 		prefs.getBoolean(Boolean.class, 	"OPM-batch-doDeskew", 			doDeskew);	
				fileExistString=prefs.get(String.class, 			"OPM-batch-fileExistString", 	fileExistString);
				//overwriteExist =prefs.getBoolean(Boolean.class, 	"OPM-batch-overwriteExist", 	overwriteExist);
				xyPixelSize = 	prefs.getDouble(Double.class, 		"OPM-batch-xyPixelSize", 		xyPixelSize);
				zStepSize = 	prefs.getDouble(Double.class, 		"OPM-batch-zStepSize", 			zStepSize);
				opmAngle = 		prefs.getDouble(Double.class, 		"OPM-batch-opmAngle", 			opmAngle);
				channelString = prefs.get(String.class, 			"OPM-batch-channelString", 		channelString);
				projX = 		prefs.getBoolean(Boolean.class, 	"OPM-batch-projX", 				projX);
				projY = 		prefs.getBoolean(Boolean.class, 	"OPM-batch-projY", 				projY);
				projZ = 		prefs.getBoolean(Boolean.class, 	"OPM-batch-projZ", 				projZ);
				maxProj = 		prefs.getBoolean(Boolean.class, 	"OPM-batch-maxProj", 			maxProj);
				avgProj = 		prefs.getBoolean(Boolean.class, 	"OPM-batch-avgProj", 			avgProj);
				minProj = 		prefs.getBoolean(Boolean.class, 	"OPM-batch-minProj", 			minProj);
				sumProj = 		prefs.getBoolean(Boolean.class, 	"OPM-batch-sumProj", 			sumProj);
				medProj = 		prefs.getBoolean(Boolean.class, 	"OPM-batch-medProj", 			medProj);
				stdProj = 		prefs.getBoolean(Boolean.class, 	"OPM-batch-stdProj", 			stdProj);
				makeTimeLapse = prefs.getBoolean(Boolean.class, 	"OPM-batch-makeTimeLapse", 		makeTimeLapse);
				saveDir = 		prefs.get(String.class, 			"OPM-batch-saveDir", 			saveDir);
				saveSeparate = 	prefs.getBoolean(Boolean.class, 	"OPM-batch-saveSeparate", 		saveSeparate);
				//saveLog = 		prefs.getBoolean(Boolean.class, 	"OPM-batch-saveLog", 			saveLog);	
				break;
			
				
			case "watcher":
				watchDir = 		prefs.get(String.class, 			"OPM-watcher-watchDir", 		watchDir);
				keywords = 		prefs.get(String.class, 			"OPM-watcher-keywords", 		keywords);
				processOld = 	prefs.getBoolean(Boolean.class, 	"OPM-watcher-processOld", 		processOld);
				overwriteExist =prefs.getBoolean(Boolean.class, 	"OPM-watcher-overwriteExist", 	overwriteExist);
				maxWait = 		prefs.getInt(Integer.class, 		"OPM-watcher-maxWait", 			maxWait);
				logPath = 		prefs.get(String.class, 			"OPM-watcher-watchLog", 		logPath);
				xyPixelSize =	prefs.getDouble(Double.class, 		"OPM-watcher-xyPixelSize", 		xyPixelSize);
				zStepSize = 	prefs.getDouble(Double.class, 		"OPM-watcher-zStepSize", 		zStepSize);
				opmAngle =	 	prefs.getDouble(Double.class, 		"OPM-watcher-opmAngle", 		opmAngle);
				//tryGPU = 		prefs.getBoolean(Boolean.class, 	"OPM-watcher-tryGPU", 			tryGPU);
				channelString = prefs.get(String.class, 			"OPM-watcher-channelString", 	channelString);
				projX = 		prefs.getBoolean(Boolean.class, 	"OPM-watcher-projX", 			projX);
				projY = 		prefs.getBoolean(Boolean.class, 	"OPM-watcher-projY", 			projY);
				projZ = 		prefs.getBoolean(Boolean.class, 	"OPM-watcher-projZ", 			projZ);
				maxProj = 		prefs.getBoolean(Boolean.class, 	"OPM-watcher-maxProj", 			maxProj);
				avgProj = 		prefs.getBoolean(Boolean.class, 	"OPM-watcher-avgProj", 			avgProj);
				minProj = 		prefs.getBoolean(Boolean.class, 	"OPM-watcher-minProj", 			minProj);
				sumProj = 		prefs.getBoolean(Boolean.class, 	"OPM-watcher-sumProj", 			sumProj);
				medProj = 		prefs.getBoolean(Boolean.class, 	"OPM-watcher-medProj", 			medProj);
				stdProj = 		prefs.getBoolean(Boolean.class, 	"OPM-watcher-stdProj", 			stdProj);
				makeTimeLapse = prefs.getBoolean(Boolean.class, 	"OPM-watcher-makeTimeLapse", 	makeTimeLapse);
				saveDir = 		prefs.get(String.class, 			"OPM-watcher-saveDir", 			saveDir);
				saveSeparate = 	prefs.getBoolean(Boolean.class, 	"OPM-watcher-saveSeparate", 	saveSeparate);
				break;
			
			case "transform":
				saveDir = 		prefs.get(String.class, 			"OPM-transform-saveDir", 		saveDir);
				break;
				
			case "permutation":
				flipX = 		prefs.getBoolean(Boolean.class, 	"OPM-permute-flipX", 			flipX);
				flipY = 		prefs.getBoolean(Boolean.class, 	"OPM-permute-flipY", 			flipY);
				flipZ = 		prefs.getBoolean(Boolean.class, 	"OPM-permute-flipZ", 			flipZ);
				foldX = 		prefs.getBoolean(Boolean.class, 	"OPM-permute-foldX", 			foldX);
				permuteString = prefs.get(String.class, 			"OPM-permute-permuteString",	permuteString);
				tryGPU = 		prefs.getBoolean(Boolean.class, 	"OPM-permute-tryGPU", 			tryGPU);
				break;
			
				
			case "projection":
				projX = 		prefs.getBoolean(Boolean.class, 	"OPM-project-projX", 			projX);
				projY = 		prefs.getBoolean(Boolean.class, 	"OPM-project-projY", 			projY);
				projZ = 		prefs.getBoolean(Boolean.class, 	"OPM-project-projZ", 			projZ);
				maxProj =		prefs.getBoolean(Boolean.class,		"OPM-project-maxProj", 			maxProj);
				avgProj = 		prefs.getBoolean(Boolean.class, 	"OPM-project-avgProj", 			avgProj);
				minProj = 		prefs.getBoolean(Boolean.class, 	"OPM-project-minProj", 			minProj);
				sumProj = 		prefs.getBoolean(Boolean.class, 	"OPM-project-sumProj", 			sumProj);
				medProj = 		prefs.getBoolean(Boolean.class, 	"OPM-project-medProj", 			medProj);
				stdProj = 		prefs.getBoolean(Boolean.class, 	"OPM-project-stdProj", 			stdProj);
				tryGPU = 		prefs.getBoolean(Boolean.class, 	"OPM-project-tryGPU", 			tryGPU);
				break;
			
				
			case "debug":
				// debug Input
				inputDir = 			prefs.get(String.class, 		"OPM-debug-inputDir", 			inputDir);
				watchDir = 			prefs.get(String.class, 		"OPM-debug-watchDir", 			watchDir);
				keywords = 			prefs.get(String.class, 		"OPM-debug-keywords", 			keywords);
				maxWait = 			prefs.getInt(Integer.class, 	"OPM-debug-maxWait", 			maxWait);
				// debug Processing
				xyPixelSize = 		prefs.getDouble(Double.class, 	"OPM-debug-xyPixelSize", 		xyPixelSize);
				zStepSize = 		prefs.getDouble(Double.class, 	"OPM-debug-zStepSize", 			zStepSize);
				opmAngle = 			prefs.getDouble(Double.class, 	"OPM-debug-opmAngle", 			opmAngle);
				doVirtual = 		prefs.getBoolean(Boolean.class, "OPM-debug-doVirtual", 			doVirtual);
				tryGPU = 			prefs.getBoolean(Boolean.class, "OPM-debug-tryGPU", 			tryGPU);
				autoPartitionData = prefs.getBoolean(Boolean.class, "OPM-debug-autoPartitionData", 	autoPartitionData);
				numPartition = 		prefs.getInt(Integer.class, 	"OPM-debug-numPartition", 		numPartition);
				channelString = 	prefs.get(String.class, 		"OPM-debug-channelString", 		channelString);
				foldX = 			prefs.getBoolean(Boolean.class, "OPM-debug-foldX", 				foldX);
				projX = 			prefs.getBoolean(Boolean.class, "OPM-debug-projX", 				projX);
				projY = 			prefs.getBoolean(Boolean.class, "OPM-debug-projY", 				projY);
				projZ = 			prefs.getBoolean(Boolean.class, "OPM-debug-projZ", 				projZ);
				maxProj = 			prefs.getBoolean(Boolean.class, "OPM-debug-maxProj", 			maxProj);
				avgProj = 			prefs.getBoolean(Boolean.class, "OPM-debug-avgProj", 			avgProj);
				minProj = 			prefs.getBoolean(Boolean.class, "OPM-debug-minProj", 			minProj);
				sumProj = 			prefs.getBoolean(Boolean.class, "OPM-debug-sumProj", 			sumProj);
				medProj = 			prefs.getBoolean(Boolean.class, "OPM-debug-medProj", 			medProj);
				stdProj = 			prefs.getBoolean(Boolean.class, "OPM-debug-stdProj", 			stdProj);
				makeTimeLapse =	  	prefs.getBoolean(Boolean.class, "OPM-debug-makeTimeLapse", 		makeTimeLapse);
				doInverse =			prefs.getBoolean(Boolean.class, "OPM-debug-doInverse", 			doInverse);
				doStepTransform = 	prefs.getBoolean(Boolean.class, "OPM-debug-doStepTransform", 	doStepTransform);
				// debug Output
				saveDir = 			prefs.get(String.class, 		"OPM-debug-saveDir", 			saveDir);
				saveSeparate = 		prefs.getBoolean(Boolean.class, "OPM-debug-saveSeparate", 		saveSeparate);
				processOld =		prefs.getBoolean(Boolean.class, "OPM-debug-processOld", 		processOld);
				overwriteExist =	prefs.getBoolean(Boolean.class, "OPM-debug-overwriteExist", 	overwriteExist);
				logPath = 			prefs.get(String.class, 		"OPM-debug-watchLog", 			logPath);
				//saveLog = 			prefs.getBoolean(Boolean.class,	"OPM-debug-batchLog", 			saveLog);	
				break;
			
				
			default:	// no match case, TODO: consider reset all parameters?
			}
			*/
		}
		
		/**				Create parameter dialog for Deskew Image command
		 * 
		 * @return
		 */
		public boolean deskew_image() {
			// create parameter dialog
			/*
			GenericDialogPlus gd = new GenericDialogPlus("Deskew Image");
			gd.setBackground( frameColor );
			gd.addImageChoice("select active image", this.impInput.getTitle());
			gd.addNumericField("XY pixel size", xyPixelSize, 1, 5, "nm");
			gd.addNumericField("Z step size", zStepSize, 1, 5, "nm");
			gd.addNumericField("OPM angle", opmAngle, 1, 5, "°");
			gd.addFileField("", matrixMessage);
			//gd.addCheckbox("(V)irtual stack", doVirtual);
			//gd.addCheckbox("GPU processing", tryGPU);
			//gd.addMessage("\timage channel option");
			gd.addChoice("channel option", channelOptions, channelString);
			gd.addMessage("\tshow projection image(s):");
			String[] label_axis = {"along X", "along Y", "along Z"};
			boolean[] state_axis = {projX, projY, projZ};				
			gd.addCheckboxGroup(1, 3, label_axis, state_axis);		
			String[] label_type = {"maximum", "mean", "minimum", "sum", "median", "standard deviation"};
			boolean[] state_type = {maxProj, avgProj, minProj, sumProj, medProj, stdProj};				
			
			gd.addCheckboxGroup(2, 3, label_type, state_type);
			
			gd.showDialog();

			if (gd.wasCanceled()) return false;
	        
	        impInput = 		gd.getNextImage();
	        xyPixelSize = 	gd.getNextNumber();
	        zStepSize = 	gd.getNextNumber();
	        opmAngle = 		gd.getNextNumber();
	        parameterFile = 	gd.getNextString();
	        //doVirtual = 	gd.getNextBoolean();
	        //tryGPU = 		gd.getNextBoolean();
	        channelString = gd.getNextChoice();
	        projX = 		gd.getNextBoolean();
	        projY = 		gd.getNextBoolean();
	        projZ = 		gd.getNextBoolean();
	        maxProj = 		gd.getNextBoolean();
	        avgProj = 		gd.getNextBoolean();
	        minProj = 		gd.getNextBoolean();
	        sumProj = 		gd.getNextBoolean();
	        medProj = 		gd.getNextBoolean();
	        stdProj = 		gd.getNextBoolean();
	        //remove file extension from image title 
	        int dotIdx = impInput.getTitle().lastIndexOf(".");
			if (-1 != dotIdx) impInput.setTitle(impInput.getTitle().substring(0, dotIdx));
	        // store parameter values
	        DefaultPrefService prefs = new DefaultPrefService();
			prefs.put(Double.class,  	"OPM-image-xyPixelSize",   	xyPixelSize);
			prefs.put(Double.class,  	"OPM-image-zStepSize",     	zStepSize);
			prefs.put(Double.class,  	"OPM-image-opmAngle",      	opmAngle);
			//prefs.put(Boolean.class, 	"OPM-image-doVirtual",     	doVirtual);
			//prefs.put(Boolean.class, 	"OPM-image-tryGPU",        	tryGPU);
			prefs.put(String.class,  	"OPM-image-channelString", 	channelString);
			prefs.put(Boolean.class, 	"OPM-image-projX",         	projX);
			prefs.put(Boolean.class, 	"OPM-image-projY",         	projY);
			prefs.put(Boolean.class, 	"OPM-image-projZ",         	projZ);
			prefs.put(Boolean.class, 	"OPM-image-maxProj",       	maxProj);
			prefs.put(Boolean.class, 	"OPM-image-avgProj",       	avgProj);
			prefs.put(Boolean.class, 	"OPM-image-minProj",       	minProj);
			prefs.put(Boolean.class, 	"OPM-image-sumProj",       	sumProj);
			prefs.put(Boolean.class, 	"OPM-image-medProj",       	medProj);
			prefs.put(Boolean.class, 	"OPM-image-stdProj",       	stdProj);
			*/
			return true;
		}
		
// TODO: IO
		/**
		 * 
		 * @param filePath
		 * @param imageHeight
		 * @return
		 */
		public static double[] loadSettingsFromFile (
				String filePath,
				double imageHeight
				) {
			if (filePath.endsWith("ExperimentalParameters.txt")) {
				double[] params = IO.loadExperimentalParametersFromFile( filePath ); //xyPixelSize, zStepSize, opmAngle
				double dxy = params[0];
				double dz = params[1];
				double angle = params[2];
				if (dxy==Double.NaN || dz==Double.NaN || angle==Double.NaN ) return null;
				return new double[] { dz/dxy, angle, imageHeight*Utils.sin(angle) };	
			}
			
			if (filePath.endsWith(".csv")) {
				
				if (filePath.toLowerCase().contains("matrix")) {
					double[][] deskew_matrix = IO.loadMatrixFromFile( filePath );
			    	if (null == deskew_matrix) return null;
					// try to calculate input parameter: z step, angle, and z-translate amount
					return Transform.reverse_deskew ( deskew_matrix, imageHeight );
				} 
				
				//if (filePath.toLowerCase().contains("transform")) {
				//	
				//}
			}
			return null;	
		}
		
//TODO:	Utils
		
		public static void displayImage ( ImagePlus imp, String name ) {
			/*
			 * image exist: update image;
			 * image not exist: display image;
			 * 
			 * image exist: keep window location, keep BC
			 * image not exsit: auto BC (for each channel)
			 * 
			 * regardless: update Z focus slice, update composite mode (for 2C)
			 * composite - noComposite: udpate BC;
			 * noComposite - composite: update BC;
			 */
			if ( null == imp ) return;
			double min = Double.NaN; double max = Double.NaN;
			int numC = imp.getNChannels();
			LUT[] luts = new LUT[numC];
			
			ImagePlus imp_display = WindowManager.getImage( name );
			boolean imageExist = (null != imp_display);
			if ( imageExist ) {
				if ( imp_display.isComposite() ) {
					for (int c=0; c<numC; c++) {
						luts[c] = ((CompositeImage)imp_display).getChannelLut( c+1 );
					}
	            //img2.setDisplayRange(lut.min, lut.max);
	            //img2.updateAndDraw();
				} else {
					min = imp_display.getDisplayRangeMin();
					max = imp_display.getDisplayRangeMax();
				}
				
				imp_display.setImage( imp );

			} else {
				
				imp_display = imp;
				imp_display.setTitle ( name );
				imp_display.show();
			}
			
			//if (imp_display.getNSlices() > 1) imp_display.setZ ( findFocusSlice (imp_display) );
			int numZ = imp_display.getNSlices();
			if (numZ > 1) imp_display.setZ ( Utils.findFocusSlice(imp_display) );
			
			//int numC = imp_display.getNChannels();
			if (numC > 1) {
				if ( !imp_display.isComposite() ) {
					CompositeImage ci = new CompositeImage(imp_display, CompositeImage.COMPOSITE);
					if ( imp.getBitDepth() != 8 ) {
				        ci.reset();
				        ci.resetDisplayRanges();
				    }
				    ImageWindow win = imp.getWindow();
				    Point location = (win!=null) ? win.getLocation() : null;
				    imp_display.hide();
				    if ( location!=null )  ImageWindow.setNextLocation ( location );
				    ci.show();
				    imp_display = ci;
				} else {
					//imp_display = CompositeConverter.makeComposite( imp_display );
					imp_display.setDisplayMode(IJ.COMPOSITE);
					for (int c=0; c<numC; c++) {
						imp_display.setC ( c+1 );
						//ImageProcessor channelProcessor = ( (CompositeImage) imp_display).getProcessor( c+1 );
						if (imageExist) { 
							imp_display.setDisplayRange(luts[c].min, luts[c].max);
						} else {
							ImageProcessor channelProcessor = imp_display.getProcessor();
							int[] MinMax = Utils.getMinAndMax ( channelProcessor, 0.35d );
							channelProcessor.setMinAndMax ( MinMax[0], MinMax[1] );
						}
						imp_display.updateChannelAndDraw();
					}
					imp_display.setC ( 1 );
				}
			} else {
				//new ContrastEnhancer().stretchHistogram(imp_display.getProcessor(), 0.35d);
				ImageProcessor ip = imp_display.getProcessor();
				if ( !imageExist ) {
					int[] MinMax = Utils.getMinAndMax ( ip, 0.35d );
					min = MinMax[0]; max = MinMax[1];
				} 
				ip.setMinAndMax(min, max);
				imp_display.updateAndDraw(); //imp_display.updateAndRepaintWindow();
			}
			
			imp_display.changes = false;
		}
}
