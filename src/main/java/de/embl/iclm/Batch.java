package de.embl.iclm;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.plugin.PlugIn;

public class Batch implements PlugIn {
	private Parameter parameter = null;

	private String[] keywords = new String[0];
	private File inputFolder = null;
	private File saveFolder = null;
	private boolean overwrite = false;
	//private String logPath = "";
	private String[] inputFileList = new String[0];
	
	private Log log;
	
	@Override
	public void run(String arg) {
		parameter = new Parameter("batch");
		if ( !parameter.deskew_batch() ) return;
		
		if ( !prepareFiles() ) return;
		
		parameter.parseDeskewParameterBatch();
		parameter.parseAlignParameter();
		parameter.parseProjectionParameter();
		parameter.storeParam();
		
		log = new Log(parameter);
		log.add(parameter);
		log.add("OPM batch processing start:");
    	
    	processFiles();
		
    	log.add("OPM batch processing finish.");
    	log.close();
	}
	
	/**
	 * 
	 * @return
	 */
	public boolean prepareFiles () {
		// check input folder path
		if (null == parameter) return false;
		if ("" == parameter.inputDir) return false;
		inputFolder = new File(parameter.inputDir);
		if (!inputFolder.exists() || !inputFolder.isDirectory()) return false;
		// get file list match request from input folder
		if ("" != parameter.keywords) {
    		keywords = parameter.keywords.split(",");
    		for (int i=0; i<keywords.length; i++) {
    			keywords[i] = keywords[i].replaceAll("\\s+",""); // remove spaces
        	}
    	}
		// check save folder path
		saveFolder = new File(parameter.saveDir);
		if ( parameter.saveToSame || null == saveFolder ) {
			parameter.saveDir = parameter.inputDir;
			if (!parameter.saveDir.endsWith(File.separator)) parameter.saveDir += File.separator;
			parameter.saveDir += "result";
			saveFolder = new File(parameter.saveDir);
		}
		if ( !saveFolder.exists() ) saveFolder.mkdirs();
		// prepare log path
		//Log.prepareLogPath( parameter );
		
		//logPath = saveFolder.getAbsolutePath() + File.separator + "OPM_batch.log";
		// get input file list, check potential processed files from save folder
		overwrite = parameter.fileExistStr.equals("overwrite");
		inputFileList = getInputFileList (
			inputFolder, Parameter.extensions, keywords, saveFolder, parameter.recursive, overwrite );
		return (0 != inputFileList.length);
	}
	
	/**
	 * 
	 */
	public void processFiles () {
		//Log log = Log.getInstance();
		long start = System.currentTimeMillis();
		// in case only left or right side requested
		//boolean doHalf = parameter.channelStr.equals("left only") || parameter.channelStr.equals("right only");
		
		// close all previous open projections image window (including time lapse)
		int[] id_list = WindowManager.getIDList();
		if (null != id_list && 0 != id_list.length) {
			for (int id : WindowManager.getIDList() ) {
				ImagePlus imp = WindowManager.getImage(id);
				if ( imp.getTitle().endsWith("projection") )
					imp.close();
			}
			IJ.run("Collect Garbage", "");
		}
		// parse how to make projection images from parameters
		// prepare projection axis string list
		/*
		ArrayList<String> axes = new ArrayList<String>();
		if (parameter.projX) axes.add("X");
		if (parameter.projY) axes.add("Y");
		if (parameter.projZ) axes.add("Z");
		// prepare projection type string list
		ArrayList<String> types = new ArrayList<String>();
		if (parameter.maxProj)	types.add("max");
		if (parameter.avgProj)	types.add("avg");
		if (parameter.minProj)	types.add("min");
		if (parameter.sumProj)	types.add("sum");
		if (parameter.medProj)	types.add("med");
		if (parameter.stdProj)	types.add("std");
		boolean doProjection = (0 != axes.size() && 0 != types.size());
		*/
		//parameter.parseProjectionParameter();
		
		// loop through input file list, process each file
		for (String path : inputFileList) {
			System.out.printf("\n\tprocessing file:\n\t%s\n", path);
			
			long start_file = System.currentTimeMillis();
			
			Deskew.processFile ( path, parameter) ;
			
			/*
			ImagePlus imp = VolumeIO.open(path);
		
			if (parameter.channelStr.equals("align with SIFT matrix") && parameter.alignmFile != "" ){
				parameter.alignMatrix = IO.loadMatrixFromFile(parameter.alignmFile);
			}
			
			if (doHalf)
				imp = (Partition.separateImageLeftRight(imp, parameter.channelStr)) [0];
			
			if (parameter.doDeskew) {
				ImagePlus imp_deskew = Deskew.deskew_image ( imp, parameter );
						//imp, parameter.xyPixelSize, parameter.zStepSize, parameter.opmAngle,
						//false, false, true, true, parameter.numPartition);
				String saveDir = saveFolder.getAbsolutePath();
				if (parameter.saveSeparate)	saveDir += File.separator + "deskew";
				try {
					Files.createDirectories(Paths.get(saveDir));
				} catch (IOException e) {
					System.out.println(e.getMessage());
					//log.add(e.getMessage());
					continue;
				}
				String savePath = saveDir + File.separator + imp_deskew.getTitle();
				if (!new File(savePath).exists() || overwrite)
					VolumeIO.saveTiff(imp_deskew, savePath);
				
				//log.add("deskewed: " + imp.getTitle()); 
				imp.setImage(imp_deskew);
				imp.setTitle(imp_deskew.getTitle());
			}
			
			// get channel image
			ImagePlus[] imp_channel = new ImagePlus[]{imp};
			if (!doHalf) {
				imp_channel = Partition.separateImageLeftRight (
						imp, parameter.channelStr );
			}
			//log.add("channel operation: " + parameter.channelStr);
			
			// create projection image(s) if requested
			if (doProjection) {
				// process for each channel image
				for (ImagePlus imp_c : imp_channel) {
					String name = Utils.getName(imp_c);
					// process for each projection axis 
		    		for (String axis : axes) {
		    			// process for each projection type
		    			for (String type : types) {
		    				ImagePlus imp_project = Projection.projection (imp_c, axis, type, true);
		    				imp_project.setTitle(name + "-" + type + axis + "projection");
		    				// create time lapse if requested
		    				if (parameter.makeTimeLapse) {
		    					String name_timeLapse = type + axis + "projection-timeLapse";
		    					int idx = name.lastIndexOf("-");
		    					if (-1 != idx) name_timeLapse = name.substring(idx, name.length()) + "-" + type + axis + "projection";
		    					ImagePlus imp_timeLapse = WindowManager.getImage(name_timeLapse);
		    					Partition.combineTimelapse ( imp_timeLapse, imp_project, name_timeLapse );		
		    				}
		    				// save projection images to disk
		    				String saveDir = saveFolder.getAbsolutePath();
		    				if (parameter.saveSeparate)	saveDir += File.separator + type + axis;
		    				try {
		    					Files.createDirectories(Paths.get(saveDir));
		    				} catch (IOException e) {
		    					System.out.println(e.getMessage());
		    					//log.add(e.getMessage());
		    					continue;
		    				}
		    				
		    				String savePath = saveDir + File.separator + imp_project.getTitle();
		    				if (!new File(savePath).exists() ||  overwrite)
		    					VolumeIO.saveTiff(imp_project, savePath);
		    				//log.add("projection image created: " + imp_project.getTitle()); 
		    			}	// projection type loop
		    		}		// projection axis loop
				}			// channel image loop	
			}
			//if ( (double)IJ.currentMemory()/(double)IJ.maxMemory() > 0.9d )
			*/
			//System.gc();
			float duration_file = System.currentTimeMillis() - start_file;
			System.out.printf("\n\tprocessing file finished after %.3f seconds.\n", duration_file / 1000);
			//log.add("\n\tprocessing file finished after %.3f seconds.\n", duration_file / 1000);
		} 					// file loop
		float duration = System.currentTimeMillis() - start;
		System.out.printf("\n\tBatch processing files finished after %.3f seconds.\n", duration / 1000);
		//log.add("\n\batch processing files finished after %.3f seconds.\n", duration / 1000);
		System.gc();
		return;
	}


	/**
	 * 
	 * @param inputFolder
	 * @param keywords
	 * @param saveFolder
	 * @param overwrite
	 * <p>
	 * @return
	 */
	public String[] getInputFileList (
			File inputFolder,
			String[] extensions,
			String[] keywords,
			File saveFolder,
			boolean recursive,
			boolean overwrite
			) {
    	if (null == inputFolder) return new String[0];
    	
    	// get file list match request from input folder
    	String[] inputFileArray = getFileList (inputFolder, extensions, keywords, recursive);
    	
    	// check if overwrite
    	if (overwrite) return inputFileArray;
    	
    	// check potential processed files from save folder
    	String[] outputFileArray = getFileList (saveFolder, extensions, keywords, true);
    	if (0 == outputFileArray.length) return inputFileArray;
    	List<String> outputFileList = new LinkedList<String>(Arrays.asList(outputFileArray));
    	List<String> inputFileList  = new LinkedList<String>(Arrays.asList(inputFileArray));
    	
    	// remove input files from output file list (when input and output folders are the same)
    	outputFileList.removeAll(inputFileList); 
    	//System.out.println("outputFileList(0)" + outputFileList.get(0));
    	// cross check input and output file list, for a list of processed file
    	List<String> processedFileList = new ArrayList<String>();
    	Iterator<String> iter = inputFileList.iterator();
    	while (iter.hasNext()) {
    		String filePath = iter.next();
    		/*
    		int slashIdx = fileName.lastIndexOf(File.separator) + 1;
    		int dotIdx = fileName.lastIndexOf(".");
    		if (-1 == dotIdx) dotIdx = fileName.length();
    		fileName = fileName.substring(slashIdx, dotIdx);
    		*/
    		String fileName = FilenameUtils.getBaseName(filePath);
    		//System.out.println("inputfile: " + fileName);
    		
    		for (String outputFile : outputFileList) {
    			if (outputFile.contains(fileName)) { // match found
    				
    				//System.out.println("outputfile: " + outputFile);
    				processedFileList.add(filePath);
    				//iter.remove();
    			}
    		}
    	}
    	if (null != processedFileList)
    		inputFileList.removeAll(processedFileList);
    	inputFileArray = inputFileList.toArray(new String[inputFileList.size()]);
		return inputFileArray;
	}
	
	
	/**		get files full path, which match criterion inside a parent directory
	 * 
	 * @param parentDir
	 * @param extensions
	 * @param keywords
	 * @param recursive
	 * <p>
	 * @return
	 */
	public static String[] getFileList (
			File parentDir, 
			String[] extensions,
			String[] keywords,
			boolean recursive
			) {
		if (null == parentDir) return new String[0];
		Collection<File> files = FileUtils.listFiles(parentDir, extensions, recursive);
		List<String> fileList = new ArrayList<String>();
		for (File file : files) {
			String name = file.getName();
			for (String keyword : keywords) {
				if (name.contains(keyword)) {
					fileList.add(file.getAbsolutePath());
					break;
				}	
			}
		}
		Collections.sort(fileList, new Comparator<String>() {
		    @Override
		    public int compare(String s1, String s2) {
		    	s1 = s1.replaceAll("[^0-9]", "");
		    	if ( s1.length()>18 ) s1 = s1.substring(s1.length()-18, s1.length());
		    	s2 = s2.replaceAll("[^0-9]", "");
		    	if ( s2.length()>18 ) s2 = s2.substring(s2.length()-18, s2.length());
		    	Long d1 = Long.valueOf ( s1 );
		    	Long d2 = Long.valueOf ( s2 );
		        return d1.compareTo(d2);
		    }
		});
		return fileList.toArray(new String[fileList.size()]);
	}
    
	
}
