package de.embl.iclm;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.plugin.Duplicator;
import ij.plugin.PlugIn;
import ij.plugin.Zoom;
import ij.plugin.frame.RoiManager;

//import net.haesleinhuepf.clijx.CLIJx;
//import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
//import net.haesleinhuepf.clij2.CLIJ2;
//import net.haesleinhuepf.clijx.plugins.DeconvolveRichardsonLucyFFT;
//import net.haesleinhuepf.clijx.plugins.DeconvolveRLTVFFT;

public class Deconvolve implements PlugIn {
	private Parameter parameter = null;
	//private Log log;

	static final class PsfChannelInput {
		final String suffix;
		final ImagePlus image;
		final boolean owned;

		PsfChannelInput(String suffix, ImagePlus image, boolean owned) {
			this.suffix = suffix;
			this.image = image;
			this.owned = owned;
		}
	}

	static final class PsfChannelBeads {
		final String suffix;
		final Beads.PsfPreparation preparation;

		PsfChannelBeads(String suffix, Beads.PsfPreparation preparation) {
			this.suffix = suffix;
			this.preparation = preparation;
		}
	}
	
	@Override
	public void run(String arg) {
		switch (arg) {
		case "psf":
			makePSF ();
			break;
			
		case "deconv":		
			deconv ();	// Richardson-Lucy (Total Variation) Deconvolution
			break;
				
		default:
		}
	}
	
	public void deconv () {
		
		parameter = new Parameter("deconv");
		if ( !parameter.deconv_rlfft() ) return;
		
		parameter.tryGPU = true;
		parameter.autoPartition = true;
		String name = Utils.getName(parameter.impInput);
		VolumeIO.normalize(parameter.impInput);	// planes may arrive on the T axis; put them back on Z
		
		if (parameter.deconvMethod.equals("Richardson-Lucy (FFT)")) {
			name += "-deconvolved-RL" + String.valueOf(parameter.numIter);
		} else {
			name += "-deconvolved-RLTV" + String.valueOf(parameter.numIter);
		}
		
		int marginSize = parameter.impPSF.getWidth();
		
        ImagePlus imp_deconv = Partition.tileDeconvolution (
        		parameter.impInput, parameter.impPSF, parameter.deconvMethod,
    			marginSize, parameter.numIter,
    			parameter.regFactor // , boolean nonCirculant // seems non-circulant deconv result not ideal
    			);
		
		if ( null != imp_deconv ) {
			Utils.displayImage(imp_deconv, name);
		}
		System.gc();
	}
	
	
	public void makePSF () {
		parameter = new Parameter("psf");
		if ( !parameter.deconv_psf() ) return;
		parameter.tryGPU = true;
		parameter.autoPartition = true;

		if (!parameter.loadFromFile && parameter.impInput != null) {
			String name = Utils.getName(parameter.impInput);
			try {
				List<PsfChannelBeads> channels = preparePsfChannels(parameter);
				int displayed = 0;
				for (PsfChannelBeads channel : channels) {
					ImagePlus psf = combineAndClose(channel.preparation.getAccepted(), parameter.avgMethod);
					if (psf != null) {
						displayPsf(psf, "PSF-" + name + channel.suffix);
						displayed++;
					}
				}
				if (displayed == 0) IJ.error("Generate experimental PSF", "No bead candidate passed quality control.");
			} catch (IllegalArgumentException e) {
				IJ.error("Generate experimental PSF", e.getMessage());
			}
			return;
		}

		File[] beadFiles = parseBeadsPath(parameter.beadsPath, parameter.recursive);
		if (beadFiles == null || beadFiles.length == 0) return;
		String folderName = new File(parameter.beadsPath).getName();
		Map<String, List<ImagePlus>> groupedBeads = new LinkedHashMap<String, List<ImagePlus>>();
		boolean loadFromManager = parameter.loadFromManager;
		boolean addToManager = parameter.addToManager;
		parameter.loadFromManager = false;
		parameter.addToManager = false;
		try {
			for (File file : beadFiles) {
				ImagePlus input = null;
				try {
					input = VolumeIO.open(file.getAbsolutePath());
					if (input == null) throw new IllegalArgumentException("Could not open TIFF image.");
					parameter.impInput = input;
					String token = BatchProcessingUtils.acquisitionChannelToken(file);
					for (PsfChannelBeads channel : preparePsfChannels(parameter)) {
						if (channel.preparation.getAccepted().isEmpty()) continue;
						String group = token + channel.suffix;
						List<ImagePlus> beads = groupedBeads.get(group);
						if (beads == null) {
							beads = new ArrayList<ImagePlus>();
							groupedBeads.put(group, beads);
						}
						beads.addAll(channel.preparation.getAccepted());
					}
				} catch (Throwable t) {
					IJ.log("PSF generation failed for " + file.getAbsolutePath() + ": " + t.getMessage());
					t.printStackTrace();
				} finally {
					parameter.impInput = null;
					BatchProcessingUtils.close(input);
				}
			}
		} finally {
			parameter.loadFromManager = loadFromManager;
			parameter.addToManager = addToManager;
		}

		int displayed = 0;
		for (Map.Entry<String, List<ImagePlus>> entry : groupedBeads.entrySet()) {
			ImagePlus psf = combineAndClose(entry.getValue(), parameter.avgMethod);
			if (psf == null) continue;
			displayPsf(psf, "PSF-" + folderName + "-" + psfGroupLabel(entry.getKey()));
			displayed++;
		}
		if (displayed == 0)
			IJ.error("Generate experimental PSF", "No bead candidate passed quality control.");
	}
	
	/** Compatibility entry point for callers processing one already separated channel. */
	public ImagePlus makePSF_image (
			Parameter parameter
			) {
		Beads.PsfPreparation prepared = preparePsfImage(parameter);
		return combineAndClose(prepared.getAccepted(), parameter.avgMethod);
	}

	List<PsfChannelInput> splitPsfInput(Parameter parameter) {
		List<PsfChannelInput> inputs = new ArrayList<PsfChannelInput>();
		if (parameter == null || parameter.impInput == null) return inputs;
		if (!"mirrored left/right halves".equals(parameter.psfChannelLayout)) {
			inputs.add(new PsfChannelInput("", parameter.impInput, false));
			return inputs;
		}
		if (parameter.loadFromManager)
			throw new IllegalArgumentException("ROI Manager bead coordinates cannot be used with packed left/right PSF input. " +
					"Use automatic detection or split the two halves first.");
		ImagePlus[] halves = Partition.separateImageLeftRight(
				parameter.impInput, "left & right separately", true);
		if (halves == null || halves.length != 2)
			throw new IllegalArgumentException("Could not split the packed bead volume into left and right halves.");
		inputs.add(new PsfChannelInput("-left", halves[0], true));
		if (parameter.psfFlipRight) {
			for (int z = 1; z <= halves[1].getStackSize(); z++)
				halves[1].getStack().getProcessor(z).flipHorizontal();
			halves[1].setTitle(halves[1].getTitle() + "-flipped");
		}
		inputs.add(new PsfChannelInput(parameter.psfFlipRight ? "-right-flipped" : "-right", halves[1], true));
		return inputs;
	}

	List<PsfChannelBeads> preparePsfChannels(Parameter parameter) {
		List<PsfChannelBeads> result = new ArrayList<PsfChannelBeads>();
		ImagePlus original = parameter == null ? null : parameter.impInput;
		if (original == null) return result;
		List<PsfChannelInput> inputs = splitPsfInput(parameter);
		try {
			for (PsfChannelInput input : inputs) {
				parameter.impInput = input.image;
				Beads.PsfPreparation preparation = preparePsfImage(parameter);
				preparation.logSummary(Utils.getName(original) + input.suffix);
				result.add(new PsfChannelBeads(input.suffix, preparation));
			}
		} catch (RuntimeException e) {
			closePreparedChannels(result);
			throw e;
		} catch (Error e) {
			closePreparedChannels(result);
			throw e;
		} finally {
			parameter.impInput = original;
			for (PsfChannelInput input : inputs)
				if (input.owned) BatchProcessingUtils.close(input.image);
		}
		return result;
	}

	private void closePreparedChannels(List<PsfChannelBeads> channels) {
		for (PsfChannelBeads channel : channels) {
			for (ImagePlus bead : channel.preparation.getAccepted()) BatchProcessingUtils.close(bead);
			channel.preparation.getAccepted().clear();
		}
	}

	Beads.PsfPreparation preparePsfImage(Parameter parameter) {
		// check and prepare input image
		if (parameter == null || parameter.impInput == null) return new Beads.PsfPreparation();
		ImagePlus source = parameter.impInput;
		// correct dimension if needed
		VolumeIO.normalize(source);	// planes may arrive on the T axis; put them back on Z
		// create variable to store peak coordinates
		int[][] maxPoints = null;
		// in the case peaks are load directly from the ROI Manager
		if ( parameter.loadFromManager && null != RoiManager.getInstance() ) {
			maxPoints = Beads.pointFromManager();
			Beads.PsfPreparation prepared = Beads.preparePsfBeads(source, maxPoints,
					parameter.radiusXY, parameter.radiusXY, parameter.radiusZ, qualitySettings(parameter));
			applyPsfCalibration(prepared, psfCalibration(parameter, source, false));
			return prepared;
		}
		
		// parse ROI and Z range, crop Z if needed.
		ImagePlus imp_beads = source;
		boolean ownsBeadsImage = false;
		int roiOnsetX = 0; int roiOnsetY = 0; int roiOnsetZ = 0;
		Roi[] rois = parseROIoption ( imp_beads, parameter );
		if (null == rois) parameter.roiPath = Parameter.loadRoiMessage;
		Roi roiCrop = Utils.combineRois ( rois );
		imp_beads.setRoi (roiCrop);
		if ( null != roiCrop ) {
			roiOnsetX = roiCrop.getBounds().x;
			roiOnsetY = roiCrop.getBounds().y;
		}
		int[] zRange = parameter.parseZrange();	// 1-based
		roiOnsetZ = zRange[0] - 1;
		if ( null != roiCrop || zRange[0] > 1 || zRange[1] < imp_beads.getNSlices() ) {
			imp_beads = new Duplicator().run( imp_beads, zRange[0], zRange[1]);
			ownsBeadsImage = true;
		}
		
		// check whether to deskew the input image
		if ( parameter.imageType.equals("auto detection") ) {
			double zStepSize = Utils.guessZstepSize(imp_beads);
			if ( zStepSize < 66.25 || zStepSize > 318 ) {
				parameter.doDeskew = false;
			} else {
				parameter.doDeskew = true;
				parameter.xyPixelSize = 116; parameter.opmAngle = 25;
				parameter.zStepSize = 132.5 * Math.round( zStepSize / 132.5 );
			}
		} else {
			parameter.doDeskew = parameter.imageType.equals("OPM raw volume");
		}
		
		// deskew the beads image if necessary
		int radiusX = parameter.radiusXY; 
		int radiusY = parameter.radiusXY; 
		int radiusZ = parameter.radiusZ;
		ImagePlus imp_beads_deskew = null;
		try {
			if ( parameter.doDeskew ) {
				double dzsetp = parameter.zStepSize;
				double dxy = parameter.xyPixelSize;
				parameter.deskewMatrix = Transform.deskew (dzsetp, dxy, parameter.opmAngle, imp_beads.getHeight() );
				imp_beads_deskew = Deskew.deskew_image(imp_beads, parameter.deskewMatrix, parameter.tryGPU);
				int[][] maxPoints_deskewed = Beads.getMaxPoints(imp_beads_deskew, parameter.beadsCount);
				maxPoints = Transform.point_inverseDeskew(maxPoints_deskewed, parameter.deskewMatrix, imp_beads.getNSlices());
				radiusY = (int)(Math.ceil(Math.abs(radiusZ / parameter.deskewMatrix[2][1])));
				radiusZ = (int)(Math.ceil(Math.abs(radiusX - radiusY * parameter.deskewMatrix[1][1]) /
						parameter.deskewMatrix[1][2]));
			} else {
				maxPoints = Beads.getMaxPoints(imp_beads, parameter.beadsCount);
			}
			maxPoints = Utils.shiftRoi(maxPoints, roiOnsetX, roiOnsetY, roiOnsetZ);
			maxPoints = Utils.pointsWithinRois(maxPoints, rois);
			if (parameter.addToManager) Beads.pointToManager(maxPoints);
			Beads.PsfPreparation prepared = Beads.preparePsfBeads(source, maxPoints,
					radiusX, radiusY, radiusZ, qualitySettings(parameter));
			applyPsfCalibration(prepared, psfCalibration(parameter, source, parameter.doDeskew));
			return prepared;
		} finally {
			BatchProcessingUtils.close(imp_beads_deskew);
			if (ownsBeadsImage) BatchProcessingUtils.close(imp_beads);
		}
	}

	private Beads.PsfQualitySettings qualitySettings(Parameter parameter) {
		Beads.PsfQualitySettings settings = new Beads.PsfQualitySettings();
		settings.shellFraction = parameter.psfShellFraction;
		settings.minSnr = parameter.psfMinSnr;
		settings.minSbr = parameter.psfMinSbr;
		settings.maxCenterOffset = parameter.psfMaxCenterOffset;
		settings.saturationLevel = parameter.psfSaturationLevel;
		settings.rejectNeighbors = parameter.psfRejectNeighbors;
		return settings;
	}

	private Calibration psfCalibration(Parameter parameter, ImagePlus source, boolean rawVolume) {
		Calibration existing = source.getCalibration();
		if (existing != null && (existing.scaled() ||
				(existing.getUnit() != null && !"pixel".equalsIgnoreCase(existing.getUnit()))))
			return existing.copy();
		Calibration calibration = new Calibration();
		calibration.pixelWidth = parameter.xyPixelSize / 1000.0d;
		calibration.pixelHeight = parameter.xyPixelSize / 1000.0d;
		calibration.pixelDepth = rawVolume ? parameter.zStepSize / 1000.0d : parameter.xyPixelSize / 1000.0d;
		calibration.setUnit("micron");
		return calibration;
	}

	private void applyPsfCalibration(Beads.PsfPreparation prepared, Calibration calibration) {
		if (prepared == null || calibration == null) return;
		for (ImagePlus bead : prepared.getAccepted()) bead.setCalibration(calibration.copy());
	}

	private ImagePlus combineAndClose(List<ImagePlus> beads, String method) {
		if (beads == null || beads.isEmpty()) return null;
		try {
			return Beads.combineBeadsImage(beads, method);
		} finally {
			for (ImagePlus bead : beads) BatchProcessingUtils.close(bead);
			beads.clear();
		}
	}

	private void displayPsf(ImagePlus psf, String title) {
		Utils.displayImage(psf, title);
		IJ.run(psf, "Fire", "");
		double zoomFactor = 400.0d / Math.max(1, psf.getHeight());
		Zoom.set(psf, zoomFactor);
	}

	static String psfGroupLabel(String group) {
		if (group == null || group.isEmpty()) return "Channel0000";
		int start = 0;
		while (start < group.length() && (group.charAt(start) == '_' || group.charAt(start) == '-')) start++;
		return start == group.length() ? "Channel0000" : group.substring(start);
	}
	
	
	
	/**
	 * 
	 * @param path
	 * <p>
	 * @return
	 */
	public File[] parseBeadsPath (
			String path,
			boolean recursive
			) {
		if (null == path || "" == path) return null;
		File beadsFile = new File(path);
		if ( !beadsFile.exists() ) return null;
		if ( beadsFile.isDirectory() ) { // beads file is folder
			Collection<File> fileCollection = FileUtils.listFiles ( beadsFile, Parameter.extensions, recursive );
			File[] files = fileCollection.toArray ( new File[ fileCollection.size() ] );
			return files;
		} else {	// beads file is file
			return new File[] { beadsFile };
		}
	}
	
	
	public Roi[] parseROIoption (ImagePlus impInput, Parameter parameter) {
		if ( null==impInput ) return null;
		// first check if input image has active ROI
		if ( null != impInput.getRoi() )
			return Utils.splitRoi( impInput.getRoi() );
		// then check if a ROI file is provided for loading
		if ( !parameter.roiPath.equals(Parameter.loadRoiMessage) ) {
			Roi[] rois = IO.loadRoiFromFile ( parameter.roiPath );
			if (null == rois) parameter.roiPath = Parameter.loadRoiMessage;
			return rois;
		}
		return null;
	}
	

}
