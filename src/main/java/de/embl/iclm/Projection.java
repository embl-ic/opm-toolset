package de.embl.iclm;

import java.util.ArrayList;

import ij.IJ;
import ij.ImagePlus;
import ij.plugin.PlugIn;

public class Projection implements PlugIn {
	private Parameter parameter = null;
	//private Log log;
	
	@Override
	public void run(String arg) {
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
		
		//log = new Log("OPM_projection.log");
		//log.add(parameter);
		//log.add(" create projection image start:");
		
		// timing the start
		//long start = System.currentTimeMillis();
		
		// prepare input image stack
		ImagePlus imp = null;
		if ( null != parameter.impInput.getRoi() )
			imp = parameter.impInput.crop("stack");
		else
			imp = parameter.impInput;
		//imp.setTitle("Projection_input_imp_crop_from_"+name);
		int[] dims = imp.getDimensions(true); // dim: 0:X 1:Y 2:C 3:Z 4:T
		if (1 == dims[3]) imp.setDimensions(dims[2], dims[4], dims[3]);	// swap Z and T

		// create projection images
		for (String axis : axes) {
			for (String type : types) {
				//log.add("\tcreating %s %s projection image of %s", axis, type, name);
				ImagePlus imp_project = projection (imp, axis, type, parameter.tryGPU);
				imp_project.setTitle(name + "-" + type + axis + "projection");
				imp_project.show();
				IJ.run(imp_project, "Enhance Contrast", "saturated=0.35");
			}
		}
		
		// clean up
		//imp.close();
		IJ.run("Collect Garbage", "");
		System.gc();

		// report script runtime
		//float duration = System.currentTimeMillis() - start;
		//log.add("\n\tprojection of image stack takes %.3f seconds.\n", duration / 1000);
		//log.add("\tcreate projection finish.");
    	//log.close();
	}
	
	/**
	 * 
	 * @param imp
	 * @param axis
	 * @param type
	 * @param tryGPU
	 * @return
	 */
	public static ImagePlus projection (
			ImagePlus imp, 
			String axis, 
			String type,
			boolean tryGPU
			) {		
		//long start = System.currentTimeMillis();
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
		//float duration = System.currentTimeMillis() - start;
		//System.out.printf("\n\t%s %s projection data takes %.3f seconds.\n", type, axis, duration/1000);
		return imp_Proj;
	}
	
	

	
}
