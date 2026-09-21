package de.embl.iclm;

import java.util.ArrayList;
import java.util.List;

import ij.gui.GenericDialog;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.plugin.PlugIn;
import net.haesleinhuepf.clij.CLIJ;
import net.haesleinhuepf.clijx.parallel.CLIJPoolOptions;

/** Interactive entry point for cache-based 3D CLIJ2-FFT deconvolution. */
public class CLIJ2FFTDeconvolution implements PlugIn {
	private static final int DEFAULT_CELL_XY = 256;
	private static final int DEFAULT_CELL_Z = 128;

	@Override
	public void run(String arg) {
		Debug.commandStarted ( "Deconvolution > CLIJ2-FFT deconv" );
		String[] titles = WindowManager.getImageTitles();
		if (titles == null || titles.length < 2) {
			IJ.error("CLIJ2-FFT deconv", "Open both a 3D input volume and a 3D PSF volume first.");
			return;
		}

		ImagePlus current = WindowManager.getCurrentImage();
		String inputTitle = current == null ? titles[0] : current.getTitle();
		String psfTitle = findPsfTitle(titles, inputTitle);
		ImagePlus defaultPsf = WindowManager.getImage(psfTitle);
		int[] cell = defaultCellSize(current);
		long[] overlap = defaultOverlap(defaultPsf);

		GenericDialog gd = new OpmDialog("CLIJ2-FFT cache deconvolution");
		Parameter.styleDialog( gd );
		gd.addImageChoice("input volume", inputTitle);
		gd.addImageChoice("PSF volume", psfTitle);
		gd.addNumericField("number of iterations", 10, 0);
		gd.addNumericField("regularization factor", 0.0, 5);
		gd.addMessage("Cache cell size (XYZ):");
		gd.addNumericField("cell X", cell[0], 0, 6, "pixel");
		gd.addNumericField("cell Y", cell[1], 0, 6, "pixel");
		gd.addNumericField("cell Z", cell[2], 0, 6, "pixel");
		gd.addMessage("Tile overlap (XYZ):");
		gd.addNumericField("overlap X", overlap[0], 0, 6, "pixel");
		gd.addNumericField("overlap Y", overlap[1], 0, 6, "pixel");
		gd.addNumericField("overlap Z", overlap[2], 0, 6, "pixel");
		gd.addCheckbox("non-circulant edge handling (experimental)", false);
		gd.addMessage("All workers in the configured CLIJ pool are used. Configure the pool before\n" +
				"the first run with Plugins > CLIJ > CLIJ Pool Options. Non-circulant mode is\n" +
				"automatically disabled for integrated Intel graphics to avoid a native crash.");
		gd.showDialog();
		if (gd.wasCanceled()) return;

		ImagePlus input = gd.getNextImage();
		ImagePlus psf = gd.getNextImage();
		int iterations = Math.max(1, (int) gd.getNextNumber());
		double regularization = Math.max(0.0, gd.getNextNumber());
		int[] requestedCell = {
				Math.max(1, (int) gd.getNextNumber()),
				Math.max(1, (int) gd.getNextNumber()),
				Math.max(1, (int) gd.getNextNumber())
		};
		long[] requestedOverlap = {
				Math.max(0, (long) gd.getNextNumber()),
				Math.max(0, (long) gd.getNextNumber()),
				Math.max(0, (long) gd.getNextNumber())
		};
		boolean nonCirculant = gd.getNextBoolean();

		try {
			ImagePlus result = Partition.tileDeconvolution(input, psf,
					"Richardson-Lucy (FFT)", requestedCell, requestedOverlap,
					iterations, regularization, nonCirculant);
			result.setTitle(Utils.getName(input) + "-CLIJ2-FFT-RL" + iterations);
			Utils.displayImage(result, result.getTitle());
		} catch (Throwable t) {
			IJ.handleException(t);
			IJ.error("CLIJ2-FFT deconv", t.getMessage() == null ? t.toString() : t.getMessage());
		}
	}

	static int[] defaultCellSize(ImagePlus image) {
		if (image == null) return new int[] { DEFAULT_CELL_XY, DEFAULT_CELL_XY, DEFAULT_CELL_Z };
		return new int[] {
				Math.max(1, Math.min(DEFAULT_CELL_XY, image.getWidth())),
				Math.max(1, Math.min(DEFAULT_CELL_XY, image.getHeight())),
				Math.max(1, Math.min(DEFAULT_CELL_Z, volumeDepth(image)))
		};
	}

	static long[] defaultOverlap(ImagePlus psf) {
		if (psf == null) return new long[] { 10, 10, 10 };
		return new long[] {
				Math.max(1, (psf.getWidth() + 1L) / 2L),
				Math.max(1, (psf.getHeight() + 1L) / 2L),
				Math.max(1, (volumeDepth(psf) + 1L) / 2L)
		};
	}

	static int[] sanitizeCellSize(ImagePlus image, int[] requested) {
		if (requested == null || requested.length != 3)
			throw new IllegalArgumentException("Cell size must contain X, Y and Z values.");
		int[] dimensions = { image.getWidth(), image.getHeight(), volumeDepth(image) };
		int[] result = new int[3];
		for (int d = 0; d < 3; d++) result[d] = Math.max(1, Math.min(requested[d], dimensions[d]));
		return result;
	}

	static long[] sanitizeOverlap(int[] cellSize, long[] requested) {
		if (cellSize == null || cellSize.length != 3 || requested == null || requested.length != 3)
			throw new IllegalArgumentException("Cell size and overlap must contain X, Y and Z values.");
		long[] result = new long[3];
		for (int d = 0; d < 3; d++) result[d] = Math.max(0, Math.min(requested[d], cellSize[d]));
		return result;
	}

	static void validateVolumes(ImagePlus input, ImagePlus psf) {
		if (input == null || psf == null)
			throw new IllegalArgumentException("Both input and PSF volumes are required.");
		validateSingleVolume(input, "Input");
		validateSingleVolume(psf, "PSF");
	}

	static boolean guardNonCirculant(boolean requested) {
		if (!requested) return false;
		List<String> riskyDevices = selectedIntegratedIntelDevices();
		if (riskyDevices.isEmpty()) return true;
		IJ.log("CLIJ2-FFT warning: non-circulant mode was disabled because the configured " +
				"CLIJ pool includes integrated Intel graphics: " + riskyDevices +
				". This avoids the known native meanOfAllPixels crash.");
		return false;
	}

	static boolean isIntegratedIntelDeviceName(String name) {
		if (name == null) return false;
		String lower = name.toLowerCase();
		if (!lower.contains("intel")) return false;
		return lower.contains("iris") || lower.contains("uhd") ||
				lower.contains("hd graphics") || lower.contains("integrated");
	}

	private static List<String> selectedIntegratedIntelDevices() {
		List<String> risky = new ArrayList<String>();
		try {
			List<String> names = CLIJ.getAvailableDeviceNames();
			int[] selected = CLIJPoolOptions.getDevices();
			for (int device : selected) {
				if (device >= 0 && device < names.size() && isIntegratedIntelDeviceName(names.get(device)))
					risky.add(names.get(device));
			}
		} catch (Throwable t) {
			IJ.log("CLIJ2-FFT could not inspect the configured GPU pool: " + t.getMessage());
		}
		return risky;
	}

	private static void validateSingleVolume(ImagePlus image, String label) {
		if (image.getNChannels() != 1 || image.getNFrames() != 1)
			throw new IllegalArgumentException(label +
					" must be one 3D volume (one channel and one time point). Split hyperstacks first.");
		if (volumeDepth(image) < 2)
			throw new IllegalArgumentException(label + " must be a 3D stack; CLIJ2-FFT does not support 2D input.");
	}

	private static int volumeDepth(ImagePlus image) {
		return image == null ? 1 : Math.max(image.getNSlices(), image.getStackSize());
	}

	private static String findPsfTitle(String[] titles, String inputTitle) {
		for (String title : titles) {
			if (!title.equals(inputTitle) && title.toLowerCase().contains("psf")) return title;
		}
		for (String title : titles) if (!title.equals(inputTitle)) return title;
		return titles[0];
	}
}
