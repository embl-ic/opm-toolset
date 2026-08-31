package de.embl.iclm;

import java.io.File;

import ij.IJ;
import ij.ImagePlus;

/**
 * One place to load an OPM volume and one place to normalise its axes.
 *
 * <p>The microscope writes every raw acquisition file as BigTIFF. Plain ImageJ cannot read
 * BigTIFF at all; inside Fiji, Bio-Formats reads it but reports the planes of a single
 * volume on the <em>time</em> axis, so a 78 x 66 x 21 volume arrives as
 * {@code nSlices = 1, nFrames = 21}. Everything downstream treats a volume as an XY-Z
 * stack, so the two axes have to be swapped back before any geometry is applied.
 *
 * <p>That swap used to be repeated at four call sites with three different guards, and was
 * missing at two more. It lives here now, in {@link #normalize}, which is applied both to
 * volumes opened from disk and to whatever image the user had selected in Fiji when they
 * opened a dialog - the two ways a volume enters the plugin.
 *
 * @see FastTiffReader for the direct BigTIFF reader used when ImageJ cannot open a file
 */
public class VolumeIO {

	/**			Open an image volume from disk, with its axes normalised
	 * <p>		ImageJ (with Bio-Formats, inside Fiji) is tried first, because it accepts the
	 * 			widest range of inputs: raw BigTIFF, the classic TIFFs written back by the
	 * 			plugin, hyperstacks and everything else the batch commands are pointed at.
	 * 			{@link FastTiffReader} is the fallback, which is what makes raw OPM files
	 * 			readable in a plain ImageJ or a headless JVM that has no Bio-Formats.
	 *
	 * @param path				: path of the image file
	 * <p>
	 * @return					: the volume as an XY-Z stack, or null if neither reader could open it
	 */
	public static ImagePlus open (
			String path
			) {
		if (null == path || path.trim().isEmpty()) return null;
		ImagePlus imp = null;
		try {
			imp = IJ.openImage ( path );
		} catch (Throwable t) {
			imp = null;								// fall through to the direct reader
		}
		if (null == imp) imp = openFast ( path );	// plain ImageJ, or a file Bio-Formats refused
		return normalize ( imp );
	}


	/**			Open a raw OPM volume with the direct BigTIFF reader, bypassing ImageJ
	 * <p>		Only uncompressed single-channel 16-bit strip TIFF is supported, which is what
	 * 			the acquisition writes. Anything else makes {@link FastTiffReader} throw, and
	 * 			this returns null so a caller can fall back.
	 *
	 * @param path				: path of the image file
	 * <p>
	 * @return					: the volume as an XY-Z stack, or null if the file is not in the fast-path shape
	 */
	public static ImagePlus openFast (
			String path
			) {
		if (null == path || path.trim().isEmpty()) return null;
		File file = new File ( path );
		if (!file.isFile()) return null;
		try {
			FastTiffReader.Info info = FastTiffReader.parse ( file );
			String name = file.getName();
			int dot = name.lastIndexOf('.');
			if (dot > 0) name = name.substring(0, dot);
			return FastTiffReader.wrapShortVolume (
					name, FastTiffReader.readPixelsParallel(file, info), info.width, info.height );
		} catch (Throwable t) {
			return null;							// not a file this reader handles
		}
	}


	/**			Put a volume's planes back on the Z axis
	 * <p>		A stack whose planes were reported as time points - one Z slice and more than
	 * 			one frame - is a single volume that a reader put on the wrong axis. Swap Z and
	 * 			T so the rest of the plugin sees the XY-Z stack it expects. A genuine
	 * 			time-lapse, which has real Z depth, is left alone, and so is a single plane.
	 * <p>		The image is modified in place and returned, so this can be applied to the
	 * 			user's active image as well as to one just read from disk.
	 *
	 * @param imp				: input ImagePlus, or null
	 * <p>
	 * @return					: the same ImagePlus, with Z and T swapped if that was needed
	 */
	public static ImagePlus normalize (
			ImagePlus imp
			) {
		if (null == imp) return null;
		int[] dims = imp.getDimensions(true);		// XYCZT
		if (1 == dims[3] && dims[4] > 1) imp.setDimensions ( dims[2], dims[4], dims[3] );
		return imp;
	}


	/**			Whether a volume's planes are currently on the time axis
	 * <p>		Useful for logging and for tests; {@link #normalize} is the operation itself.
	 *
	 * @param imp				: input ImagePlus, or null
	 * <p>
	 * @return					: true when normalize would swap this image's Z and T axes
	 */
	public static boolean needsAxisSwap (
			ImagePlus imp
			) {
		if (null == imp) return false;
		int[] dims = imp.getDimensions(true);		// XYCZT
		return 1 == dims[3] && dims[4] > 1;
	}
}
