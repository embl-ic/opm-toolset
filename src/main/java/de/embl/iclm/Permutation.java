package de.embl.iclm;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.plugin.Duplicator;
import ij.plugin.HyperStackConverter;
import ij.plugin.PlugIn;
import ij.plugin.RGBStackMerge;

public class Permutation implements PlugIn {
	private Parameter parameter = null;
	
	@Override
	public void run(String arg) {
		Debug.commandStarted ( "Utilities > Permutation" );
		// get parameter of stack volume permutation of active image
		parameter = new Parameter("permute");
		parameter.impInput = IJ.getImage();
		if ( !parameter.axis_permutation() ) return;
		if ( null == parameter.impInput ) return;
		
		
		// timing the start
		//TODO: check for duplciated image, and not cleared memory
		// prepare input image stack
		ImagePlus imp = new Duplicator().run( parameter.impInput );
		String name = Utils.getName(parameter.impInput);
		imp.setTitle(name);
		imp.hide();
		int[] dims = imp.getDimensions(true); // dim: 0:X 1:Y 2:C 3:Z 4:T
		if (1 == dims[3]) {		// num Z = 1
			dims[3] = dims[4];	// swap Z and T
			dims[4] = 1;
		}
		
		// check whether to flip axis
		if (parameter.flipX || parameter.flipY || parameter.flipZ) {
			ImagePlus imp_flip = null;
			if (parameter.tryGPU)
				imp_flip = GPU.flip(imp, parameter.flipX, parameter.flipY, parameter.flipZ);
			if (null == imp_flip) 	// GPU failed, flip image on CPU  
				imp_flip = CPU.flip(imp, parameter.flipX, parameter.flipY, parameter.flipZ);
			imp.setImage(imp_flip);
		}
		
		// check whether to fold X axis
		if (parameter.foldX) {	// skip the neccessity of assigning temporary variable imp_fold
			ImagePlus imp_fold = null;
			if (parameter.tryGPU)
				imp_fold = fold_x (imp, true);
			if (null == imp_fold)
				imp_fold = fold_x (imp, false);
			imp.setImage(imp_fold);
		}

		// perform permutation of axis
		ImagePlus imp_permute = null;
		if (parameter.tryGPU)
			imp_permute = GPU.permute (imp, parameter.permuteStr);
		if (null == imp_permute)	// GPU failed, permute image on CPU
			imp_permute = CPU.permute (imp, parameter.permuteStr);
		
		// set title and display transposed image stack
		String permutation = parameter.permuteStr;
		if (parameter.foldX) permutation = permutation.replace("X", "[X]");
		if (parameter.flipX) permutation = permutation.replace("X", "X'");
		if (parameter.flipY) permutation = permutation.replace("Y", "Y'");
		if (parameter.flipZ) permutation = permutation.replace("Z", "Z'");
		
		Utils.displayImage( imp_permute, name + permutation );
		
		
		// clean up
		imp.close();
		Utils.collectGarbage();

		// report script runtime
	}
	
	
	/**				Flip (reverse) the Z slices in a stack, also work with hyperstack
	 * <br>			ImageJ's own Flip Z reverses the whole stack, which scrambles a hyperstack
	 * <br>			because it does not know where one Z series ends and the next begins.
	 *
	 * @param imp	: input image in XY-CZT order, reversed along Z in place
	 */
	public static void flip_z ( //	ImageJ Flip Z (stack_reverser) do not work with hyperstack
			ImagePlus imp	// input ImagePlus need to be in order XY-CZT
			) {
		int[] dims = imp.getDimensions(true);
		int numZ = dims[3];
		int numSlices = imp.getStackSize();
		ImageStack stack = imp.getStack();
		ImageStack stack_new = new ImageStack(dims[0], dims[1], numSlices);
		for(int idx = 1; idx <= numSlices; idx++){
			int[] pos = imp.convertIndexToPosition(idx);	// 1 based slice index
			int posC = pos[0]; int posZ = pos[1]; int posT = pos[2];	
			int pos_new	= imp.getStackIndex(posC, (numZ+1-posZ), posT);
			stack_new.setProcessor(stack.getProcessor(idx), pos_new);
		}
		stack = null;
		imp.setStack(null, stack_new);
		imp.updateAndDraw();
	}

	
	/**
	 * 
	 * @param imp				: input ImagePlus, could be 2D or 3D, but should have only 1 channel
	 * @param tryGPU			: attempt the GPU path first; the CPU path runs when it returns null
	 * <p>
	 * @return imp_fold			: output ImagePlus, as X axis folded image stack:
	 * 											    The right half of the image will be filpped and
	 * 												combined as the 2nd channel of the image hyperstack.
	 */
	public static ImagePlus fold_x (
			ImagePlus imp,
			boolean tryGPU
			) {
		if (null == imp) return null;
		String name = Utils.getName(imp);
		int[] dims = imp.getDimensions(true);
		// TODO: implement code for the case that  input is already have multiple channel

		int width = (int) Math.ceil(dims[0]/2);	// if image width is odd: the midline is duplicated in both 
		// create ROIs corresponding to left and right half of the image
		Roi roiL = new Roi(0, 0, width, dims[1]);
		Roi roiR = new Roi(dims[0]-width, 0, width, dims[1]);
		imp.setRoi(roiL, false);
		ImagePlus imp_left = new Duplicator().run(imp);
		imp_left.setTitle(name + "-left");
		imp.setRoi(roiR, false);
		ImagePlus imp_right = new Duplicator().run(imp);
		imp_right.setTitle(name + "-right");
		// flip the right side and merge onto the left as 2nd channel
		ImagePlus imp_flip = null;
		if (tryGPU)
			imp_flip = GPU.flip(imp_right, true, false, false);
		if (null == imp_flip) 	// GPU failed, flip image on CPU  
			imp_flip = CPU.flip(imp_right, true, false, false);
		imp_right.close();
		
		//TODO: check if for the case more than 2 channel works
		ImageStack[] combined = {imp_left.getStack(), imp_flip.getStack()};
		ImagePlus imp_fold = new RGBStackMerge().createComposite( 0,0,0, combined, false );
		Utils.autoSetLUTs ( imp_fold );
		imp_fold = HyperStackConverter.toHyperStack(imp_fold, 2*dims[2], dims[3], dims[4]);
		
		
		imp_fold.setTitle(name + "-xFold");
		imp_fold.changes = false;
		return imp_fold;
	}

	/*
	public static ImagePlus fold_x (
			ImagePlus[] imp_LR,
			double[][] align_matrix,
			boolean tryGPU
			) {
		if (null == imp_LR || 2 != imp_LR.length) return null;
		ImagePlus imp_left = imp_LR[0]; ImagePlus imp_right = imp_LR[1];
		int dims_l[] = imp_left.getDimensions(true);
		int dims_r[] = imp_right.getDimensions(true);
		if (dims_l[0]!=dims_r[0] || dims_l[1]!=dims_r[1] || dims_l[0]!=dims_r[0]) return null;
		if (imp_left.getNSlices() != imp_right.getNSlices()) return null;
		// flip the right side and merge onto the left as 2nd channel
		ImagePlus imp_flip = null;
		if (null != align_matrix) {
			Transform.flipAndAlign ( imp_right, align_matrix, tryGPU );
		} else {
			if (tryGPU)
				imp_flip = GPU.flip(imp_right, true, false, false);
			if (null == imp_flip) 	// GPU failed, flip image on CPU  
				imp_flip = CPU.flip(imp_right, true, false, false);
		}
		// combine the two channel image together as RGB composite image (hyper)stack
		ImageStack[] combined = {imp_left.getStack(), imp_flip.getStack()};
		ImagePlus imp_fold = new RGBStackMerge().createComposite( 0,0,0, combined, false );
		Utils.autoSetLUTs ( imp_fold );
		imp_fold = HyperStackConverter.toHyperStack(imp_fold, 2*dims_l[2], dims_l[3], dims_l[4]);
		imp_fold.changes = false;
		return imp_fold;
	}
	*/
	
	
}
