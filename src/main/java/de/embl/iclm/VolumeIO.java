package de.embl.iclm;

import java.io.File;

import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;

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


	/**
	 * Whether volumes are written with Deflate compression. Deskewing leaves between a
	 * third and three quarters of the output bounding box empty, and those zeros cost
	 * almost nothing once compressed: a full deskewed volume drops from 3964 MB to 530 MB,
	 * a fifth of the raw acquisition file it came from, and reads back faster than the
	 * uncompressed version because there is so much less to move off disk.
	 */
	private static volatile boolean compressOutput = true;

	/** Whether {@link #saveTiff} compresses. */
	public static boolean isCompressOutput () {
		return compressOutput;
	}

	/** Turn output compression on or off for every save that goes through {@link #saveTiff}. */
	public static void setCompressOutput (
			boolean compress
			) {
		compressOutput = compress;
	}


	/**			Whether an image still has pixels that can be read
	 * <p>		A closed or flushed ImagePlus keeps reporting its dimensions after its stack
	 * 			has gone, so asking it for a processor is the only reliable test.
	 *
	 * @param imp				: image to test, may be null
	 * <p>
	 * @return					: true when the first plane can actually be fetched
	 */
	public static boolean isReadable (
			ImagePlus imp
			) {
		if (null == imp || imp.getStackSize() < 1) return false;
		try {
			return null != imp.getStack() && null != imp.getStack().getProcessor(1);
		} catch (Throwable t) {
			return false;
		}
	}


	/**			Give a save path a TIFF extension if it has none
	 * <p>		Result paths are built from image titles, which carry no extension, and
	 * 			{@link ij.IJ#saveAs} used to append one on the way past. The writers here do
	 * 			not, so the extension is added explicitly - and callers should use this for
	 * 			their "does the result already exist" check too, or the check tests one name
	 * 			while the file is written under another.
	 *
	 * @param path				: destination path, with or without an extension
	 * <p>
	 * @return					: the path ending in .tif, or unchanged if it already ends in .tif or .tiff
	 */
	public static String tiffPath (
			String path
			) {
		if (null == path) return null;
		String trimmed = path.trim();
		if (trimmed.isEmpty()) return trimmed;
		String lower = trimmed.toLowerCase ( java.util.Locale.ROOT );
		if (lower.endsWith(".tif") || lower.endsWith(".tiff")) return trimmed;
		return trimmed + ".tif";
	}
	public static File tiffPath (
			File file
			) {
		return file == null ? null : new File ( tiffPath(file.getPath()) );
	}


	/**			Save a volume as TIFF, compressed when that is possible
	 * <p>		Uses {@link FastTiffWriter} for 16-bit single-channel stacks, which writes an
	 * 			ordinary multi-page TIFF with one Deflate strip per plane. Anything else, or
	 * 			any failure, falls back to ImageJ's own writer so a save never fails just
	 * 			because compression was unavailable.
	 *
	 * @param imp				: image to write
	 * @param path				: destination path
	 * <p>
	 * @return					: true if the file was written
	 */
	public static boolean saveTiff (
			ImagePlus imp,
			String path
			) {
		if (null == imp || null == path || path.trim().isEmpty()) return false;
		if (!isReadable(imp)) {
			/* A closed ImagePlus still reports its old stack size while its pixels are gone.
			 * ImageJ's own FileSaver throws a NullPointerException on one, so refuse here
			 * rather than letting a dead image take down a batch or live run. */
			IJ.log ( "OPM: nothing to save, the image has no readable pixels: " + path );
			return false;
		}
		File file = new File ( tiffPath(path) );
		if (compressOutput && FastTiffWriter.canWrite(imp)) {
			try {
				FastTiffWriter.write ( imp, file );
				return true;
			} catch (Throwable t) {
				IJ.log ( "OPM: compressed save failed, writing uncompressed instead: " + t.getMessage() );
			}
		}
		FileSaver saver = new FileSaver ( imp );
		return imp.getStackSize() > 1 ? saver.saveAsTiffStack ( file.getAbsolutePath() )
		                              : saver.saveAsTiff ( file.getAbsolutePath() );
	}
	public static boolean saveTiff (
			ImagePlus imp,
			File file
			) {
		return saveTiff ( imp, file == null ? null : file.getAbsolutePath() );
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
