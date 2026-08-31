// OPM CLIJ batch full-output proof-of-concept for Fiji/ImageJ Groovy.
//
// Goal:
//   Process all time points in the sample folder using the fast GPU workflow:
//     raw TIFF -> GPU affine deskew -> GPU MIPs -> save MIPs -> save deskewed TIFF stack
//
// It also appends the three MIP views into time-lapse stacks so the result
// matches the plugin's live-QC movie idea.

import ij.IJ
import ij.ImagePlus
import ij.ImageStack
import ij.io.FileSaver

import net.haesleinhuepf.clij.clearcl.ClearCLBuffer
import net.haesleinhuepf.clij2.CLIJ2
import net.imglib2.realtransform.AffineTransform3D

// -------------------------------------------------------------------------
// Configuration
// -------------------------------------------------------------------------

final String INPUT_DIR =
        'D:/Workspace/Microscopes/OPM/test_&_simulation/deskew_live/mitosis-test-timelapse_0'

final String OUTPUT_DIR =
        'D:/Workspace/Microscopes/OPM/test_&_simulation/deskew_live/mitosis-test-timelapse_0/poc_clij_batch_full_output'

final String FILE_NAME_CONTAINS = 'Channel0001'
final String FILE_EXTENSION = '.tiff'

final double XY_PIXEL_SIZE_UM = 0.116
final double Z_STEP_UM = 0.265
final double OPM_ANGLE_DEG = 25.0

// Set 0 for all files. Use a small number for a quick smoke test.
final int MAX_FILES = 0

final boolean SAVE_DESKEW_VOLUME = true
final boolean SAVE_INDIVIDUAL_MIPS = true
final boolean SAVE_MIP_MOVIES = true
final boolean DISPLAY_MIP_MOVIES = true
final boolean CLOSE_RAW_AFTER_UPLOAD = true

final String GPU_NAME_HINT = 'RTX'

// -------------------------------------------------------------------------
// Implementation
// -------------------------------------------------------------------------

class OpmClijBatchPoc {
    static long now() {
        return System.nanoTime()
    }

    static double secondsSince(long t0) {
        return (System.nanoTime() - t0) / 1.0e9d
    }

    static void log(String message) {
        IJ.log(message)
        println(message)
    }

    static double cosDeg(double degrees) {
        return Math.cos(Math.toRadians(degrees))
    }

    static double sinDeg(double degrees) {
        return Math.sin(Math.toRadians(degrees))
    }

    static double[][] identity4x4() {
        return [
                [1d, 0d, 0d, 0d],
                [0d, 1d, 0d, 0d],
                [0d, 0d, 1d, 0d],
                [0d, 0d, 0d, 1d]
        ] as double[][]
    }

    static double[][] deskewMatrix(double dzStepUm, double xyPixelSizeUm, double opmAngleDeg, double imageHeight) {
        double[][] matrix = identity4x4()
        matrix[1][1] = cosDeg(opmAngleDeg)
        matrix[1][2] = dzStepUm / xyPixelSizeUm
        matrix[2][1] = -sinDeg(opmAngleDeg)
        matrix[2][2] = Double.MIN_VALUE
        matrix[2][3] = imageHeight * sinDeg(opmAngleDeg)
        return matrix
    }

    static long[] transformedDimensions(long[] dims, double[][] matrix) {
        double w = dims[0]
        double h = dims[1]
        double d = dims[2]

        double m00 = matrix[0][0]; double m10 = matrix[1][0]; double m20 = matrix[2][0]
        double m01 = matrix[0][1]; double m11 = matrix[1][1]; double m21 = matrix[2][1]
        double m02 = matrix[0][2]; double m12 = matrix[1][2]; double m22 = matrix[2][2]
        double m03 = matrix[0][3]; double m13 = matrix[1][3]; double m23 = matrix[2][3]

        double[] xs = [
                m03, w * m00 + h * m01 + d * m02 + m03, w * m00 + m03,
                h * m01 + d * m02 + m03, h * m01 + m03, w * m00 + d * m02 + m03,
                d * m02 + m03, w * m00 + h * m01 + m03
        ] as double[]
        double[] ys = [
                m13, w * m10 + h * m11 + d * m12 + m13, w * m10 + m13,
                h * m11 + d * m12 + m13, h * m11 + m13, w * m10 + d * m12 + m13,
                d * m12 + m13, w * m10 + h * m11 + m13
        ] as double[]
        double[] zs = [
                m23, w * m20 + h * m21 + d * m22 + m23, w * m20 + m23,
                h * m21 + d * m22 + m23, h * m21 + m23, w * m20 + d * m22 + m23,
                d * m22 + m23, w * m20 + h * m21 + m23
        ] as double[]

        return [
                (long) Math.ceil(xs.max() - xs.min()),
                (long) Math.ceil(ys.max() - ys.min()),
                (long) Math.ceil(zs.max() - zs.min())
        ] as long[]
    }

    static double[][] autoCenter(long[] dims, double[][] matrix) {
        double w = dims[0]
        double h = dims[1]
        double d = dims[2]

        double m00 = matrix[0][0]; double m10 = matrix[1][0]; double m20 = matrix[2][0]
        double m01 = matrix[0][1]; double m11 = matrix[1][1]; double m21 = matrix[2][1]
        double m02 = matrix[0][2]; double m12 = matrix[1][2]; double m22 = matrix[2][2]
        double m03 = matrix[0][3]; double m13 = matrix[1][3]; double m23 = matrix[2][3]

        double[] xs = [
                m03, w * m00 + h * m01 + d * m02 + m03, w * m00 + m03,
                h * m01 + d * m02 + m03, h * m01 + m03, w * m00 + d * m02 + m03,
                d * m02 + m03, w * m00 + h * m01 + m03
        ] as double[]
        double[] ys = [
                m13, w * m10 + h * m11 + d * m12 + m13, w * m10 + m13,
                h * m11 + d * m12 + m13, h * m11 + m13, w * m10 + d * m12 + m13,
                d * m12 + m13, w * m10 + h * m11 + m13
        ] as double[]
        double[] zs = [
                m23, w * m20 + h * m21 + d * m22 + m23, w * m20 + m23,
                h * m21 + d * m22 + m23, h * m21 + m23, w * m20 + d * m22 + m23,
                d * m22 + m23, w * m20 + h * m21 + m23
        ] as double[]

        double[][] out = matrix.collect { row -> row.clone() } as double[][]
        double xMin = xs.min()
        double yMin = ys.min()
        double zMin = zs.min()
        if (xMin < 0) out[0][3] -= xMin
        if (yMin < 0) out[1][3] -= yMin
        if (zMin < 0) out[2][3] -= zMin
        return out
    }

    static AffineTransform3D toInverseAffineTransform3D(double[][] forwardMatrix) {
        AffineTransform3D transform = new AffineTransform3D()
        transform.set(forwardMatrix)
        return transform.inverse()
    }

    static void saveTiff(ImagePlus imp, File outputFile) {
        outputFile.parentFile.mkdirs()
        FileSaver saver = new FileSaver(imp)
        if (imp.getStackSize() > 1) {
            saver.saveAsTiffStack(outputFile.absolutePath)
        } else {
            saver.saveAsTiff(outputFile.absolutePath)
        }
    }

    static CLIJ2 getClij2(String nameHint) {
        if (nameHint == null || nameHint.trim().isEmpty()) {
            return CLIJ2.getInstance()
        }
        try {
            return CLIJ2.getInstance(nameHint)
        } catch (Throwable ignored) {
            log("Could not select GPU hint '" + nameHint + "'; using CLIJ2 default device.")
            return CLIJ2.getInstance()
        }
    }

    static String baseName(File file) {
        String name = file.name
        int dot = name.lastIndexOf('.')
        return dot > 0 ? name.substring(0, dot) : name
    }
}

long allStart = OpmClijBatchPoc.now()
File inputDir = new File(INPUT_DIR)
File outputDir = new File(OUTPUT_DIR)
File dsDir = new File(outputDir, 'DS')
File mipDir = new File(outputDir, 'MIPs')
File movieDir = new File(outputDir, 'MIP_movies')
dsDir.mkdirs()
mipDir.mkdirs()
movieDir.mkdirs()

File[] allFiles = inputDir.listFiles({ File f ->
    f.isFile() &&
            f.name.toLowerCase().endsWith(FILE_EXTENSION) &&
            f.name.contains(FILE_NAME_CONTAINS)
} as FileFilter)

if (allFiles == null || allFiles.length == 0) {
    throw new RuntimeException('No input TIFF files found in ' + INPUT_DIR)
}

List<File> files = allFiles.sort { it.name }
if (MAX_FILES > 0 && files.size() > MAX_FILES) {
    files = files.subList(0, MAX_FILES)
}

OpmClijBatchPoc.log('')
OpmClijBatchPoc.log('OPM CLIJ batch full-output POC')
OpmClijBatchPoc.log('Input folder: ' + INPUT_DIR)
OpmClijBatchPoc.log('Output folder: ' + OUTPUT_DIR)
OpmClijBatchPoc.log('Files: ' + files.size())
OpmClijBatchPoc.log(String.format(
        'Parameters: angle=%.4f deg, xy=%.6f um, dz=%.6f um',
        OPM_ANGLE_DEG, XY_PIXEL_SIZE_UM, Z_STEP_UM))

CLIJ2 clij2 = OpmClijBatchPoc.getClij2(GPU_NAME_HINT)
clij2.clear()
OpmClijBatchPoc.log(clij2.reportMemory())

ImageStack xyMovie = null
ImageStack yzMovie = null
ImageStack xzMovie = null
ImagePlus xyMovieImp = null
ImagePlus yzMovieImp = null
ImagePlus xzMovieImp = null

double sumOpen = 0d
double sumUpload = 0d
double sumDeskew = 0d
double sumMip = 0d
double sumPullMips = 0d
double sumSaveMips = 0d
double sumPullVolume = 0d
double sumSaveVolume = 0d
double sumPerFile = 0d

try {
    int index = 0
    for (File file : files) {
        index++
        long fileStart = OpmClijBatchPoc.now()
        String name = OpmClijBatchPoc.baseName(file)
        OpmClijBatchPoc.log('')
        OpmClijBatchPoc.log(String.format('Processing %d/%d: %s', index, files.size(), file.name))

        ImagePlus rawImp = null
        ClearCLBuffer rawGpu = null
        ClearCLBuffer deskewGpu = null
        ClearCLBuffer mipXYGpu = null
        ClearCLBuffer mipYZGpu = null
        ClearCLBuffer mipXZGpu = null

        try {
            long tOpen = OpmClijBatchPoc.now()
            rawImp = IJ.openImage(file.absolutePath)
            if (rawImp == null) {
                throw new RuntimeException('Could not open input TIFF: ' + file.absolutePath)
            }
            double openSec = OpmClijBatchPoc.secondsSince(tOpen)
            sumOpen += openSec

            int rawWidth = rawImp.getWidth()
            int rawHeight = rawImp.getHeight()
            int rawDepth = rawImp.getStackSize()
            long[] rawDims = [rawWidth as long, rawHeight as long, rawDepth as long] as long[]
            double[][] forwardMatrix = OpmClijBatchPoc.deskewMatrix(
                    Z_STEP_UM, XY_PIXEL_SIZE_UM, OPM_ANGLE_DEG, rawHeight)
            long[] outputDims = OpmClijBatchPoc.transformedDimensions(rawDims, forwardMatrix)
            double[][] centeredForwardMatrix = OpmClijBatchPoc.autoCenter(rawDims, forwardMatrix)
            AffineTransform3D inverseTransform = OpmClijBatchPoc.toInverseAffineTransform3D(centeredForwardMatrix)

            OpmClijBatchPoc.log(String.format(
                    'Raw %dx%dx%d -> deskew %dx%dx%d; open %.3f s',
                    rawWidth, rawHeight, rawDepth, outputDims[0], outputDims[1], outputDims[2], openSec))

            long tUpload = OpmClijBatchPoc.now()
            rawGpu = clij2.push(rawImp)
            double uploadSec = OpmClijBatchPoc.secondsSince(tUpload)
            sumUpload += uploadSec

            if (CLOSE_RAW_AFTER_UPLOAD) {
                rawImp.changes = false
                rawImp.close()
                rawImp.flush()
                rawImp = null
                System.gc()
            }

            deskewGpu = clij2.create(outputDims, rawGpu.getNativeType())
            mipXYGpu = clij2.create([outputDims[0], outputDims[1]] as long[], rawGpu.getNativeType())
            mipYZGpu = clij2.create([outputDims[1], outputDims[2]] as long[], rawGpu.getNativeType())
            mipXZGpu = clij2.create([outputDims[0], outputDims[2]] as long[], rawGpu.getNativeType())

            long tDeskew = OpmClijBatchPoc.now()
            clij2.affineTransform3D(rawGpu, deskewGpu, inverseTransform)
            double deskewSec = OpmClijBatchPoc.secondsSince(tDeskew)
            sumDeskew += deskewSec
            clij2.release(rawGpu)
            rawGpu = null

            long tMip = OpmClijBatchPoc.now()
            clij2.maximumZProjection(deskewGpu, mipXYGpu)
            clij2.maximumXProjection(deskewGpu, mipYZGpu)
            clij2.maximumYProjection(deskewGpu, mipXZGpu)
            double mipSec = OpmClijBatchPoc.secondsSince(tMip)
            sumMip += mipSec

            long tPullMips = OpmClijBatchPoc.now()
            ImagePlus mipXY = clij2.pull(mipXYGpu)
            ImagePlus mipYZ = clij2.pull(mipYZGpu)
            ImagePlus mipXZ = clij2.pull(mipXZGpu)
            mipXY.setTitle(name + '-MIP-XY')
            mipYZ.setTitle(name + '-MIP-YZ')
            mipXZ.setTitle(name + '-MIP-XZ')
            double pullMipsSec = OpmClijBatchPoc.secondsSince(tPullMips)
            sumPullMips += pullMipsSec

            if (xyMovie == null) {
                xyMovie = new ImageStack(mipXY.getWidth(), mipXY.getHeight())
                yzMovie = new ImageStack(mipYZ.getWidth(), mipYZ.getHeight())
                xzMovie = new ImageStack(mipXZ.getWidth(), mipXZ.getHeight())
                xyMovieImp = new ImagePlus('OPM CLIJ XY MIP movie', xyMovie)
                yzMovieImp = new ImagePlus('OPM CLIJ YZ MIP movie', yzMovie)
                xzMovieImp = new ImagePlus('OPM CLIJ XZ MIP movie', xzMovie)
                if (DISPLAY_MIP_MOVIES) {
                    xyMovieImp.show()
                    yzMovieImp.show()
                    xzMovieImp.show()
                }
            }

            xyMovie.addSlice(name, mipXY.getProcessor().duplicate())
            yzMovie.addSlice(name, mipYZ.getProcessor().duplicate())
            xzMovie.addSlice(name, mipXZ.getProcessor().duplicate())
            if (DISPLAY_MIP_MOVIES) {
                xyMovieImp.setStack(xyMovie)
                yzMovieImp.setStack(yzMovie)
                xzMovieImp.setStack(xzMovie)
                xyMovieImp.setSlice(index)
                yzMovieImp.setSlice(index)
                xzMovieImp.setSlice(index)
                xyMovieImp.updateAndDraw()
                yzMovieImp.updateAndDraw()
                xzMovieImp.updateAndDraw()
            }

            long tSaveMips = OpmClijBatchPoc.now()
            if (SAVE_INDIVIDUAL_MIPS) {
                OpmClijBatchPoc.saveTiff(mipXY, new File(mipDir, name + '-MIP-XY.tif'))
                OpmClijBatchPoc.saveTiff(mipYZ, new File(mipDir, name + '-MIP-YZ.tif'))
                OpmClijBatchPoc.saveTiff(mipXZ, new File(mipDir, name + '-MIP-XZ.tif'))
            }
            double saveMipsSec = OpmClijBatchPoc.secondsSince(tSaveMips)
            sumSaveMips += saveMipsSec

            mipXY.changes = false; mipXY.close(); mipXY.flush()
            mipYZ.changes = false; mipYZ.close(); mipYZ.flush()
            mipXZ.changes = false; mipXZ.close(); mipXZ.flush()

            double pullVolumeSec = 0d
            double saveVolumeSec = 0d
            if (SAVE_DESKEW_VOLUME) {
                long tPullVolume = OpmClijBatchPoc.now()
                ImagePlus deskewImp = clij2.pull(deskewGpu)
                deskewImp.setTitle(name + '-deskew')
                pullVolumeSec = OpmClijBatchPoc.secondsSince(tPullVolume)
                sumPullVolume += pullVolumeSec

                long tSaveVolume = OpmClijBatchPoc.now()
                OpmClijBatchPoc.saveTiff(deskewImp, new File(dsDir, name + '-DS.tif'))
                saveVolumeSec = OpmClijBatchPoc.secondsSince(tSaveVolume)
                sumSaveVolume += saveVolumeSec

                deskewImp.changes = false
                deskewImp.close()
                deskewImp.flush()
                System.gc()
            }

            double fileSec = OpmClijBatchPoc.secondsSince(fileStart)
            sumPerFile += fileSec
            OpmClijBatchPoc.log(String.format(
                    'Timing: open %.3f, upload %.3f, deskew %.3f, mips %.3f, pullMips %.3f, saveMips %.3f, pullDS %.3f, saveDS %.3f, total %.3f s',
                    openSec, uploadSec, deskewSec, mipSec, pullMipsSec, saveMipsSec, pullVolumeSec, saveVolumeSec, fileSec))
            IJ.showProgress(index, files.size())
        } finally {
            if (rawImp != null) {
                rawImp.changes = false
                rawImp.close()
                rawImp.flush()
            }
            if (rawGpu != null) clij2.release(rawGpu)
            if (deskewGpu != null) clij2.release(deskewGpu)
            if (mipXYGpu != null) clij2.release(mipXYGpu)
            if (mipYZGpu != null) clij2.release(mipYZGpu)
            if (mipXZGpu != null) clij2.release(mipXZGpu)
            System.gc()
        }
    }

    if (SAVE_MIP_MOVIES && xyMovie != null) {
        long tMovies = OpmClijBatchPoc.now()
        OpmClijBatchPoc.saveTiff(xyMovieImp, new File(movieDir, 'MIP-movie-XY.tif'))
        OpmClijBatchPoc.saveTiff(yzMovieImp, new File(movieDir, 'MIP-movie-YZ.tif'))
        OpmClijBatchPoc.saveTiff(xzMovieImp, new File(movieDir, 'MIP-movie-XZ.tif'))
        OpmClijBatchPoc.log(String.format('Saved MIP movies in %.3f s', OpmClijBatchPoc.secondsSince(tMovies)))
    }
} finally {
    if (clij2 != null) {
        clij2.clear()
    }
}

double totalSec = OpmClijBatchPoc.secondsSince(allStart)
int n = files.size()
OpmClijBatchPoc.log('')
OpmClijBatchPoc.log('Batch summary')
OpmClijBatchPoc.log(String.format('Files processed: %d', n))
OpmClijBatchPoc.log(String.format('Total elapsed: %.3f s', totalSec))
OpmClijBatchPoc.log(String.format('Mean per file: %.3f s', sumPerFile / n))
OpmClijBatchPoc.log(String.format(
        'Mean timing: open %.3f, upload %.3f, deskew %.3f, mips %.3f, pullMips %.3f, saveMips %.3f, pullDS %.3f, saveDS %.3f s',
        sumOpen / n, sumUpload / n, sumDeskew / n, sumMip / n, sumPullMips / n,
        sumSaveMips / n, sumPullVolume / n, sumSaveVolume / n))
OpmClijBatchPoc.log('Done.')
