package de.embl.iclm;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
import net.haesleinhuepf.clij2.CLIJ2;
import net.imglib2.realtransform.AffineTransform3D;

import java.io.File;
import java.util.Locale;

public class FastClijDeskew {
	public static class Options {
		public boolean saveDeskewTiff = true;
		public boolean saveIndividualMips = true;
		public boolean displayMipMovies = true;
		public boolean makeMipMovies = true;
		public boolean saveSeparate = true;
		public boolean overwrite = false;
		public String gpuNameHint = "RTX";
	}

	public static class Result {
		public String name;
		public long[] rawDims;
		public long[] outputDims;
		public double parseSec;
		public double readSec;
		public double wrapSec;
		public double uploadSec;
		public double deskewSec;
		public double mipSec;
		public double pullMipSec;
		public double saveMipSec;
		public double pullVolumeSec;
		public double saveTiffSec;
		public double totalSec;
		/** False when any requested TIFF or projection could not be written. */
		public boolean allWritten = true;
	}

	public static class MipMovieSink {
		private ImageStack maxXMovie;
		private ImageStack maxYMovie;
		private ImageStack maxZMovie;
		private ImagePlus maxXMovieImp;
		private ImagePlus maxYMovieImp;
		private ImagePlus maxZMovieImp;
		private int frameCount = 0;

		public synchronized void append(String label, ImagePlus maxX, ImagePlus maxY, ImagePlus maxZ, boolean display) {
			/* The first frame has to be in the stack before the ImagePlus is built.
			 * ImageJ's ImagePlus(String, ImageStack) rejects an empty stack outright, so
			 * constructing it first threw IllegalArgumentException("Stack is empty") on the
			 * very first append - which Live caught as "fast path failed", abandoning the
			 * CLIJ path on every file for as long as MIP movies were switched on. */
			if (maxX != null) {
				if (maxXMovie == null) maxXMovie = new ImageStack(maxX.getWidth(), maxX.getHeight());
				maxXMovie.addSlice(label, maxX.getProcessor().duplicate());
				if (maxXMovieImp == null) maxXMovieImp = new ImagePlus("-maxXprojection", maxXMovie);
			}
			if (maxY != null) {
				if (maxYMovie == null) maxYMovie = new ImageStack(maxY.getWidth(), maxY.getHeight());
				maxYMovie.addSlice(label, maxY.getProcessor().duplicate());
				if (maxYMovieImp == null) maxYMovieImp = new ImagePlus("-maxYprojection", maxYMovie);
			}
			if (maxZ != null) {
				if (maxZMovie == null) maxZMovie = new ImageStack(maxZ.getWidth(), maxZ.getHeight());
				maxZMovie.addSlice(label, maxZ.getProcessor().duplicate());
				if (maxZMovieImp == null) maxZMovieImp = new ImagePlus("-maxZprojection", maxZMovie);
			}
			frameCount++;
			if (display) {
				showMovie(maxXMovieImp, maxXMovie);
				showMovie(maxYMovieImp, maxYMovie);
				showMovie(maxZMovieImp, maxZMovie);
			}
		}

		/** @return true when every movie that exists was written. */
		public synchronized boolean save(File folder) {
			folder.mkdirs();
			boolean ok = true;
			if (maxXMovieImp != null) ok &= saveTiff(maxXMovieImp, new File(folder, "maxXprojection-timeLapse.tif"));
			if (maxYMovieImp != null) ok &= saveTiff(maxYMovieImp, new File(folder, "maxYprojection-timeLapse.tif"));
			if (maxZMovieImp != null) ok &= saveTiff(maxZMovieImp, new File(folder, "maxZprojection-timeLapse.tif"));
			return ok;
		}

		public synchronized int getFrameCount() {
			return frameCount;
		}

		private void showMovie(ImagePlus imp, ImageStack stack) {
			if (imp == null) return;
			if (!imp.isVisible()) imp.show();
			imp.setStack(stack);
			imp.setSlice(frameCount);
			imp.updateAndDraw();
		}
	}

	public static Result processFile(File file, Parameter parameter, Options options, MipMovieSink movies,
			int timepointIndex) throws Exception {
		long totalStart = now();
		Result result = new Result();
		/* The same name every other deskew route gives its result, so a user sees one kind of
		 * file for one kind of result whichever path produced it: <raw>-deskewed.tif for the
		 * volume, <raw>-deskewed-maxZprojection.tif for a projection. This path used to write
		 * <raw>-DS.tif and <raw>-maxZprojection.tif, which the TIFF viewer did not recognise
		 * as a volume at all. */
		result.name = baseName(file) + "-deskewed";

		File saveRoot = resolveSaveRoot(file, parameter);
		File deskewDir = options.saveSeparate ? new File(saveRoot, "deskew") : saveRoot;
		File movieDir = options.saveSeparate ? new File(saveRoot, "MIP_movies") : saveRoot;

		if (!options.overwrite && outputExists(result.name, saveRoot, deskewDir, options)) {
			IJ.log("Deskew Batch2 skip existing result: " + file.getAbsolutePath());
			return result;
		}

		CLIJ2 clij2 = getClij2(options.gpuNameHint);
		ImagePlus rawImp = null;
		ImagePlus deskewImp = null;
		ClearCLBuffer rawGpu = null;
		ClearCLBuffer deskewGpu = null;
		ClearCLBuffer mipXYGpu = null;
		ClearCLBuffer mipYZGpu = null;
		ClearCLBuffer mipXZGpu = null;
		try {
			long tParse = now();
			FastTiffReader.Info info = FastTiffReader.parse(file);
			result.parseSec = secondsSince(tParse);

			long tRead = now();
			short[][] rawVolume = FastTiffReader.readPixelsParallel(file, info);
			result.readSec = secondsSince(tRead);

			long tWrap = now();
			rawImp = FastTiffReader.wrapShortVolume(result.name + "-raw-fast", rawVolume, info.width, info.height);
			rawVolume = null;
			result.wrapSec = secondsSince(tWrap);

			result.rawDims = new long[] { info.width, info.height, info.depth() };
			double[][] forwardMatrix = Transform.deskew(
					parameter.zStepSize, parameter.xyPixelSize, parameter.opmAngle, info.height);
			result.outputDims = Transform.getTransformedDim(result.rawDims, forwardMatrix, false);
			double[][] centeredMatrix = Transform.autoCenter(result.rawDims, Transform.copy(forwardMatrix));
			AffineTransform3D inverseTransform = Transform.raw_to_imglib2(centeredMatrix).inverse();

			long tUpload = now();
			rawGpu = clij2.push(rawImp);
			result.uploadSec = secondsSince(tUpload);
			rawImp.changes = false;
			rawImp.close();
			rawImp.flush();
			rawImp = null;
			System.gc();

			deskewGpu = clij2.create(result.outputDims, rawGpu.getNativeType());
			/* Sized by the rule every projection path shares. The X buffer was {height, depth};
			 * CLIJ2 writes (z, y) and iterates over the destination, so maxX projected only the
			 * first depth rows and every saved maxX was wrong. */
			mipXYGpu = clij2.create(ProjectionBatch.outputDimensions(result.outputDims, 'Z'), rawGpu.getNativeType());
			mipYZGpu = clij2.create(ProjectionBatch.outputDimensions(result.outputDims, 'X'), rawGpu.getNativeType());
			mipXZGpu = clij2.create(ProjectionBatch.outputDimensions(result.outputDims, 'Y'), rawGpu.getNativeType());

			long tDeskew = now();
			clij2.affineTransform3D(rawGpu, deskewGpu, inverseTransform);
			result.deskewSec = secondsSince(tDeskew);
			clij2.release(rawGpu);
			rawGpu = null;

			long tMip = now();
			clij2.maximumZProjection(deskewGpu, mipXYGpu);
			clij2.maximumXProjection(deskewGpu, mipYZGpu);
			clij2.maximumYProjection(deskewGpu, mipXZGpu);
			result.mipSec = secondsSince(tMip);

			long tPullMip = now();
			ImagePlus maxZ = clij2.pull(mipXYGpu);
			ImagePlus maxX = clij2.pull(mipYZGpu);
			ImagePlus maxY = clij2.pull(mipXZGpu);
			result.pullMipSec = secondsSince(tPullMip);
			maxX.setTitle(result.name + "-maxXprojection");
			maxY.setTitle(result.name + "-maxYprojection");
			maxZ.setTitle(result.name + "-maxZprojection");
			/* An image pulled off the GPU knows nothing about the sample, so it is calibrated
			 * here - the same isotropic calibration BatchTiffOutput gives the shared path, since
			 * the deskew affine is evaluated on a grid measured in camera pixels. Without it
			 * every result of this path read back as one unit per pixel. */
			calibrate(maxX, parameter);
			calibrate(maxY, parameter);
			calibrate(maxZ, parameter);

			if (options.makeMipMovies && movies != null)
				movies.append(result.name,
						parameter.projX ? maxX : null,
						parameter.projY ? maxY : null,
						parameter.projZ ? maxZ : null,
						options.displayMipMovies);

			long tSaveMip = now();
			if (options.saveIndividualMips && parameter.doProjection) {
				if (parameter.projX) result.allWritten &= saveProjection(maxX, saveRoot, options.saveSeparate, "maxX", result.name);
				if (parameter.projY) result.allWritten &= saveProjection(maxY, saveRoot, options.saveSeparate, "maxY", result.name);
				if (parameter.projZ) result.allWritten &= saveProjection(maxZ, saveRoot, options.saveSeparate, "maxZ", result.name);
			}
			result.saveMipSec = secondsSince(tSaveMip);

			if (options.saveDeskewTiff) {
				long tPullVolume = now();
				deskewImp = clij2.pull(deskewGpu);
				deskewImp.setTitle(result.name);
				calibrate(deskewImp, parameter);
				result.pullVolumeSec = secondsSince(tPullVolume);

				if (options.saveDeskewTiff) {
					long tSaveTiff = now();
					deskewDir.mkdirs();
					result.allWritten &= saveTiff(deskewImp, volumeFile(deskewDir, result.name));
					result.saveTiffSec = secondsSince(tSaveTiff);
				}
			}
			close(maxX);
			close(maxY);
			close(maxZ);
			result.totalSec = secondsSince(totalStart);
			return result;
		} finally {
			close(rawImp);
			close(deskewImp);
			if (rawGpu != null) clij2.release(rawGpu);
			if (deskewGpu != null) clij2.release(deskewGpu);
			if (mipXYGpu != null) clij2.release(mipXYGpu);
			if (mipYZGpu != null) clij2.release(mipYZGpu);
			if (mipXZGpu != null) clij2.release(mipXZGpu);
			System.gc();
		}
	}

	/**			Calibrate a result of this path, as the shared path calibrates its own
	 * <p>		Isotropic at the camera pixel size: the deskew affine is evaluated on a grid
	 * <br>		measured in camera pixels, so the deskewed volume is isotropic and every
	 * <br>		projection of it is too. The same numbers {@code BatchTiffOutput.calibration} uses.
	 */
	private static void calibrate(ImagePlus imp, Parameter parameter) {
		if (imp == null || parameter == null) return;
		if (!(parameter.xyPixelSize > 0)) return;
		imp.setCalibration(BatchTiffOutput.calibrationFor(parameter));
	}

	public static Options optionsFromParameter(Parameter parameter) {
		Options options = new Options();
		options.saveDeskewTiff = parameter.saveDeskewImage;
		options.saveSeparate = parameter.saveSeparate;
		options.overwrite = parameter.fileExistStr != null && parameter.fileExistStr.equals("overwrite");
		options.makeMipMovies = parameter.makeTimeLapse;
		options.displayMipMovies = parameter.displayTimeLapse;
		options.saveIndividualMips = true;
		return options;
	}

	public static boolean canUseFastPath(Parameter parameter) {
		return parameter != null &&
				(parameter.channelStr == null || parameter.channelStr.equals("whole image")) &&
				!parameter.avgProj && !parameter.minProj && !parameter.sumProj &&
				!parameter.medProj && !parameter.stdProj;
	}

	public static String formatTiming(Result r) {
		return String.format(Locale.US,
				"parse %.3f, read %.3f, wrap %.3f, upload %.3f, deskew %.3f, mips %.3f, pullMips %.3f, pullVolume %.3f, saveTIFF %.3f, total %.3f s",
				r.parseSec, r.readSec, r.wrapSec, r.uploadSec, r.deskewSec, r.mipSec, r.pullMipSec,
				r.pullVolumeSec, r.saveTiffSec, r.totalSec);
	}

	public static File resolveSaveRoot(File inputFile, Parameter parameter) {
		if (parameter.saveToSame || parameter.saveDir == null || parameter.saveDir.trim().isEmpty())
			return new File(inputFile.getParentFile(), "result");
		return new File(parameter.saveDir);
	}

	private static boolean outputExists(String resultName, File saveRoot, File deskewDir, Options options) {
		if (options.saveDeskewTiff && VolumeIO.isCompleteTiff(volumeFile(deskewDir, resultName))) return true;
		return false;
	}

	/** Where the deskewed volume of a result named {@code <raw>-deskewed} is written. */
	static File volumeFile(File deskewDir, String resultName) {
		return new File(deskewDir, VolumeIO.tiffPath(resultName));
	}

	private static boolean saveProjection(ImagePlus imp, File saveRoot, boolean saveSeparate, String projectionName,
			String baseName) {
		File dir = saveSeparate ? new File(saveRoot, projectionName) : saveRoot;
		return saveTiff(imp, new File(dir, baseName + "-" + projectionName + "projection.tif"));
	}

	private static CLIJ2 getClij2(String nameHint) {
		if (nameHint == null || nameHint.trim().isEmpty()) return CLIJ2.getInstance();
		try {
			return CLIJ2.getInstance(nameHint);
		} catch (Throwable ignored) {
			return CLIJ2.getInstance();
		}
	}

	/**			Write one TIFF and say whether it actually landed
	 * <p>		The Boolean used to be discarded here, so a full disk or a read-only folder was
	 * 			indistinguishable from a successful save to every caller. Live could only work
	 * 			around that by re-checking the files afterwards.
	 *
	 * @return					: true when the file was written
	 */
	private static boolean saveTiff(ImagePlus imp, File outputFile) {
		outputFile.getParentFile().mkdirs();
		boolean written = VolumeIO.saveTiff(imp, outputFile);
		if (!written) IJ.log("OPM: failed to write " + outputFile.getAbsolutePath());
		return written;
	}

	private static void close(ImagePlus imp) {
		if (imp == null) return;
		imp.changes = false;
		imp.close();
		imp.flush();
	}

	private static String baseName(File file) {
		String name = file.getName();
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	private static long now() {
		return System.nanoTime();
	}

	private static double secondsSince(long t0) {
		return (System.nanoTime() - t0) / 1.0e9;
	}
}
