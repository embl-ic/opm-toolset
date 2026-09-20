package de.embl.iclm;

import java.util.ArrayList;

import ij.IJ;
import ij.ImagePlus;
import ij.plugin.PlugIn;

public class Projection implements PlugIn {
	private Parameter parameter = null;
	
	@Override
	public void run(String arg) {
		Party.commandStarted ( "Utilities > Projection" );
		// get parameter of stack axis projection of active image
		parameter = new Parameter("projection");
		parameter.impInput = IJ.getImage();
		if ( !parameter.axis_projection() ) return;
		if ( null == parameter.impInput ) return;
		parameter.tryGPU = true;
		parameter.autoPartition = true;
		String name = Utils.getName(parameter.impInput);
		
		// prepare projection axis string list
		ArrayList<String> axes = new ArrayList<String>();
		if (parameter.projX) axes.add("X");
		if (parameter.projY) axes.add("Y");
		if (parameter.projZ) axes.add("Z");
		if (0 == axes.size()) return;
		// prepare projection type string list
		ArrayList<String> types = new ArrayList<String>();
		if (parameter.maxProj)	types.add("max");
		if (parameter.avgProj)	types.add("avg");
		if (parameter.minProj)	types.add("min");
		if (parameter.sumProj)	types.add("sum");
		if (parameter.medProj)	types.add("med");
		if (parameter.stdProj)	types.add("std");
		if (0 == types.size()) return;
		
		
		// timing the start
		
		// prepare input image stack
		ImagePlus imp = null;
		if ( null != parameter.impInput.getRoi() )
			imp = parameter.impInput.crop("stack");
		else
			imp = parameter.impInput;
		VolumeIO.normalize(imp);	// planes may arrive on the T axis; put them back on Z

		// create projection images
		for (String axis : axes) {
			for (String type : types) {
				ImagePlus imp_project = projection (imp, axis, type, parameter.tryGPU);
				imp_project.setTitle(name + "-" + type + axis + "projection");
				imp_project.show();
				IJ.run(imp_project, "Enhance Contrast", "saturated=0.35");
			}
		}
		
		// clean up
		Utils.collectGarbage();
		System.gc();

		// report script runtime
	}
	
	/**		Project one axis away, GPU first and CPU second
	 * <br>		The single dispatch point for projections: it tries the GPU when asked and falls
	 * <br>		back to the CPU whenever that returns null, so both paths stay reachable.
	 *
	 * @param imp		: input volume
	 * @param axis		: axis to project away: X, Y or Z
	 * @param type		: max, avg, min, sum, med or std
	 * @param tryGPU	: attempt the GPU path first
	 * <p>
	 * @return			: 2D projection image, or null when both paths failed
	 */
	public static ImagePlus projection (
			ImagePlus imp, 
			String axis, 
			String type,
			boolean tryGPU
			) {		
		ImagePlus imp_Proj = null;
		switch(axis.toLowerCase()) {
		case "x":
			if (tryGPU) imp_Proj = GPU.projection_x (imp, type);
			if (null == imp_Proj) imp_Proj = CPU.projection_x (imp , type);
			break;
		case "y":
			if (tryGPU) imp_Proj = GPU.projection_y (imp, type);
			if (null == imp_Proj) imp_Proj = CPU.projection_y (imp , type);
			break;
		case "z":
			if (tryGPU) imp_Proj = GPU.projection_z (imp, type);
			if (null == imp_Proj) imp_Proj = CPU.projection_z (imp , type);
			break;
		}
		imp_Proj.changes = false;
		return imp_Proj;
	}
	
	

	
}
