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
 * @see FastTiffReader for the direct BigTIFF reader, which every raw acquisition file goes to first
 */
public class VolumeIO {

	/**			Open an image volume from disk, with its axes normalised
	 * <p>		<b>A BigTIFF goes to {@link FastTiffReader} first.</b> BigTIFF is what the
	 * 			acquisition hardware writes, and nothing this plugin writes is one: every result
	 * 			is a classic TIFF, because {@link FastTiffWriter} refuses anything past 4 GB.
	 * 			So the header alone says which reader a file wants. The fast reader was built
	 * 			for exactly this format, and ImageJ is the wrong first choice for it twice over:
	 * 			plain ImageJ cannot read BigTIFF and says so in a modal error dialog - which
	 * 			stalls an unattended live run until someone dismisses it - and Fiji detours
	 * 			through Bio-Formats, which reads it correctly but more slowly and puts the
	 * 			planes on the time axis.
	 * <p>		Every other file - the classic TIFFs written back by the plugin, hyperstacks,
	 * 			whatever a batch command is pointed at - goes to ImageJ first, because it keeps
	 * 			the channels, slices and calibration those carry in their description.
	 * 			Either way the other reader is the fallback.
	 *
	 * @param path				: path of the image file
	 * <p>
	 * @return					: the volume as an XY-Z stack, or null if neither reader could open it
	 */
	public static ImagePlus open (
			String path
			) {
		if (null == path || path.trim().isEmpty()) return null;
		ImagePlus imp = isBigTiff ( new File(path) ) ? openFast ( path ) : null;
		if (null == imp) {
			try {
				imp = IJ.openImage ( path );
			} catch (Throwable t) {
				imp = null;							// fall through to the direct reader
			}
		}
		if (null == imp) imp = openFast ( path );	// plain ImageJ, or a file Bio-Formats refused
		return normalize ( imp );
	}


	/**			Whether a file carries the BigTIFF header, read from its first four bytes
	 *
	 * @param file				: any file
	 * <p>
	 * @return					: true for a BigTIFF in either byte order; false for anything else,
	 * 							  including a file that is missing or too short to tell
	 */
	static boolean isBigTiff (
			File file
			) {
		if (null == file || !file.isFile() || file.length() < 8) return false;
		java.io.RandomAccessFile input = null;
		try {
			input = new java.io.RandomAccessFile ( file, "r" );
			int b0 = input.read(), b1 = input.read(), b2 = input.read(), b3 = input.read();
			if (b0 == 'I' && b1 == 'I') return b2 == 43 && b3 == 0;
			if (b0 == 'M' && b1 == 'M') return b2 == 0 && b3 == 43;
			return false;
		} catch (java.io.IOException unreadable) {
			return false;
		} finally {
			if (input != null) try { input.close(); } catch (java.io.IOException ignored) { }
		}
	}


	/**			The height of a volume, read from its TIFF metadata without opening its pixels
	 * <p>		The deskew matrix needs the camera height of the file actually being processed,
	 * 			and nothing else from it. Reading a whole 1.4 GB raw volume to learn one number
	 * 			is what setting up an OME-Zarr session used to cost, once per acquisition.
	 * 			The IFD chain holds it; only a file the fast reader cannot parse is opened.
	 *
	 * @param file				: an image file
	 * <p>
	 * @return					: its height in pixels
	 * @throws IllegalStateException	: when neither route can read it
	 */
	public static int height (
			File file
			) {
		try {
			return FastTiffReader.parse ( file ).height;
		} catch (Throwable notFastPath) {
			ImagePlus imp = open ( file.getAbsolutePath() );
			if (null == imp) throw new IllegalStateException ( "Could not read " + file );
			try {
				return imp.getHeight();
			} finally {
				imp.changes = false;
				imp.close();
			}
		}
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


	/**			Whether a result already on disk may be kept by a "skip existing" rule
	 * <p>		Existence alone is not enough. Results are written straight to their final name,
	 * 			so a run killed part way through a write leaves a file of that name whose IFD
	 * 			chain runs past its own end. Skipping it kept the truncated file for good: the
	 * 			manifest does not record the time point, so a resumed run processed it again and
	 * 			then declined to write the result, and the preview, which refuses such a file,
	 * 			silently lacked that time point.
	 *
	 * @param file				: a result path
	 * @return					: true only for a present, structurally complete TIFF
	 */
	static boolean isCompleteTiff (
			File file
			) {
		return file != null && file.isFile() && TiffCompletionCheck.isReady ( file );
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
