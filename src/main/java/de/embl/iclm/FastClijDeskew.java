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
		public boolean saveDeskewZarr = false;
		public boolean saveIndividualMips = true;
		public boolean displayMipMovies = true;
		public boolean makeMipMovies = true;
		public boolean saveSeparate = true;
		public boolean overwrite = false;
		public int zarrTimepoints = 1;
		public int zarrChunkZ = 32;
		public int zarrChunkY = 256;
		public int zarrChunkX = 256;
		public String gpuNameHint = "RTX";
		public MinimalOmeZarrWriter zarrWriter = null;
		public File sharedZarrRoot = null;
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
		public double saveZarrSec;
		public double totalSec;
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
			if (maxX != null && maxXMovie == null) {
				maxXMovie = new ImageStack(maxX.getWidth(), maxX.getHeight());
				maxXMovieImp = new ImagePlus("-maxXprojection", maxXMovie);
			}
			if (maxY != null && maxYMovie == null) {
				maxYMovie = new ImageStack(maxY.getWidth(), maxY.getHeight());
				maxYMovieImp = new ImagePlus("-maxYprojection", maxYMovie);
			}
			if (maxZ != null && maxZMovie == null) {
				maxZMovie = new ImageStack(maxZ.getWidth(), maxZ.getHeight());
				maxZMovieImp = new ImagePlus("-maxZprojection", maxZMovie);
			}
			if (maxX != null) maxXMovie.addSlice(label, maxX.getProcessor().duplicate());
			if (maxY != null) maxYMovie.addSlice(label, maxY.getProcessor().duplicate());
			if (maxZ != null) maxZMovie.addSlice(label, maxZ.getProcessor().duplicate());
			frameCount++;
			if (display) {
				showMovie(maxXMovieImp, maxXMovie);
				showMovie(maxYMovieImp, maxYMovie);
				showMovie(maxZMovieImp, maxZMovie);
			}
		}

		public synchronized void save(File folder) {
			folder.mkdirs();
			if (maxXMovieImp != null) saveTiff(maxXMovieImp, new File(folder, "maxXprojection-timeLapse.tif"));
			if (maxYMovieImp != null) saveTiff(maxYMovieImp, new File(folder, "maxYprojection-timeLapse.tif"));
			if (maxZMovieImp != null) saveTiff(maxZMovieImp, new File(folder, "maxZprojection-timeLapse.tif"));
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
		result.name = baseName(file);

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
			mipXYGpu = clij2.create(new long[] { result.outputDims[0], result.outputDims[1] }, rawGpu.getNativeType());
			mipYZGpu = clij2.create(new long[] { result.outputDims[1], result.outputDims[2] }, rawGpu.getNativeType());
			mipXZGpu = clij2.create(new long[] { result.outputDims[0], result.outputDims[2] }, rawGpu.getNativeType());

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

			if (options.makeMipMovies && movies != null)
				movies.append(result.name,
						parameter.projX ? maxX : null,
						parameter.projY ? maxY : null,
						parameter.projZ ? maxZ : null,
						options.displayMipMovies);

			long tSaveMip = now();
			if (options.saveIndividualMips && parameter.doProjection) {
				if (parameter.projX) saveProjection(maxX, saveRoot, options.saveSeparate, "maxX", result.name);
				if (parameter.projY) saveProjection(maxY, saveRoot, options.saveSeparate, "maxY", result.name);
				if (parameter.projZ) saveProjection(maxZ, saveRoot, options.saveSeparate, "maxZ", result.name);
			}
			result.saveMipSec = secondsSince(tSaveMip);

			if (options.saveDeskewTiff || options.saveDeskewZarr) {
				long tPullVolume = now();
				deskewImp = clij2.pull(deskewGpu);
				deskewImp.setTitle(result.name + "-deskew");
				result.pullVolumeSec = secondsSince(tPullVolume);

				if (options.saveDeskewTiff) {
					long tSaveTiff = now();
					deskewDir.mkdirs();
					saveTiff(deskewImp, new File(deskewDir, result.name + "-DS.tif"));
					result.saveTiffSec = secondsSince(tSaveTiff);
				}
				if (options.saveDeskewZarr) {
					long tSaveZarr = now();
					File zarrRoot = new File(saveRoot, result.name + ".ome.zarr");
					MinimalOmeZarrWriter.writeFileResult(zarrRoot, deskewImp,
							parameter.projX ? maxX : null,
							parameter.projY ? maxY : null,
							parameter.projZ ? maxZ : null,
							parameter.xyPixelSize / 1000.0,
							options.zarrChunkZ, options.zarrChunkY, options.zarrChunkX);
					result.saveZarrSec = secondsSince(tSaveZarr);
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
				"parse %.3f, read %.3f, wrap %.3f, upload %.3f, deskew %.3f, mips %.3f, pullMips %.3f, pullDS %.3f, saveTIFF %.3f, saveZarr %.3f, total %.3f s",
				r.parseSec, r.readSec, r.wrapSec, r.uploadSec, r.deskewSec, r.mipSec, r.pullMipSec,
				r.pullVolumeSec, r.saveTiffSec, r.saveZarrSec, r.totalSec);
	}

	public static File resolveSaveRoot(File inputFile, Parameter parameter) {
		if (parameter.saveToSame || parameter.saveDir == null || parameter.saveDir.trim().isEmpty())
			return new File(inputFile.getParentFile(), "result");
		return new File(parameter.saveDir);
	}

	private static boolean outputExists(String baseName, File saveRoot, File deskewDir, Options options) {
		if (options.saveDeskewTiff && new File(deskewDir, baseName + "-DS.tif").exists()) return true;
		if (options.saveDeskewZarr && new File(saveRoot, baseName + ".ome.zarr").exists()) return true;
		return false;
	}

	private static void saveProjection(ImagePlus imp, File saveRoot, boolean saveSeparate, String projectionName,
			String baseName) {
		File dir = saveSeparate ? new File(saveRoot, projectionName) : saveRoot;
		saveTiff(imp, new File(dir, baseName + "-" + projectionName + "projection.tif"));
	}

	private static CLIJ2 getClij2(String nameHint) {
		if (nameHint == null || nameHint.trim().isEmpty()) return CLIJ2.getInstance();
		try {
			return CLIJ2.getInstance(nameHint);
		} catch (Throwable ignored) {
			return CLIJ2.getInstance();
		}
	}

	private static void saveTiff(ImagePlus imp, File outputFile) {
		outputFile.getParentFile().mkdirs();
		VolumeIO.saveTiff(imp, outputFile);
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
