// OPM CLIJ MIP-only proof-of-concept for Fiji/ImageJ Groovy.
//
// Goal:
//   Benchmark the practical live-QC path:
//     1. Open one raw OPM TIFF volume.
//     2. Upload raw volume to GPU once.
//     3. Deskew with CLIJ2.affineTransform3D, keeping the result on GPU.
//     4. Compute X/Y/Z max projections on GPU.
//     5. Pull and save the three 2D MIPs.
//     6. Optionally pull and save the full deskewed TIFF stack.
//
// Timing is reported separately for live-QC outputs and full-volume output so
// they can be judged independently.
//
// To run in Fiji:
//   Plugins > Scripting > Script Editor, language = Groovy, open this file,
//   then Run.

import ij.IJ
import ij.ImagePlus
import ij.io.FileSaver

import net.haesleinhuepf.clij.clearcl.ClearCLBuffer
import net.haesleinhuepf.clij2.CLIJ2
import net.imglib2.realtransform.AffineTransform3D

// -------------------------------------------------------------------------
// Configuration
// -------------------------------------------------------------------------

final String INPUT_TIFF =
        'D:/Workspace/Microscopes/OPM/test_&_simulation/deskew_live/mitosis-test-timelapse_0/mitosis-test-timelapse_Position0001_Time000001_Channel0001_Frames1_521.tiff'

final String OUTPUT_DIR =
        'D:/Workspace/Microscopes/OPM/test_&_simulation/deskew_live/mitosis-test-timelapse_0/poc_clij_mip_only'

final double XY_PIXEL_SIZE_UM = 0.116
final double Z_STEP_UM = 0.265
final double OPM_ANGLE_DEG = 25.0

final boolean DISPLAY_MIPS = true
final boolean SAVE_MIPS = true
final boolean SAVE_DESKEW_VOLUME = true
final boolean CLOSE_RAW_AFTER_UPLOAD = true

// Prefer the same GPU family you used in the plugin if present. Use null to
// let CLIJ choose the default device.
final String GPU_NAME_HINT = 'RTX'

// -------------------------------------------------------------------------
// Implementation
// -------------------------------------------------------------------------

class OpmClijMipOnlyPoc {
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
        double cosTheta = cosDeg(opmAngleDeg)
        double sinTheta = sinDeg(opmAngleDeg)

        double[][] matrix = identity4x4()
        matrix[1][1] = cosTheta
        matrix[1][2] = dzStepUm / xyPixelSizeUm
        matrix[2][1] = -sinTheta
        matrix[2][2] = Double.MIN_VALUE
        matrix[2][3] = imageHeight * sinTheta
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
                m03,
                w * m00 + h * m01 + d * m02 + m03,
                w * m00 + m03,
                h * m01 + d * m02 + m03,
                h * m01 + m03,
                w * m00 + d * m02 + m03,
                d * m02 + m03,
                w * m00 + h * m01 + m03
        ] as double[]
        double[] ys = [
                m13,
                w * m10 + h * m11 + d * m12 + m13,
                w * m10 + m13,
                h * m11 + d * m12 + m13,
                h * m11 + m13,
                w * m10 + d * m12 + m13,
                d * m12 + m13,
                w * m10 + h * m11 + m13
        ] as double[]
        double[] zs = [
                m23,
                w * m20 + h * m21 + d * m22 + m23,
                w * m20 + m23,
                h * m21 + d * m22 + m23,
                h * m21 + m23,
                w * m20 + d * m22 + m23,
                d * m22 + m23,
                w * m20 + h * m21 + m23
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
}

long allStart = OpmClijMipOnlyPoc.now()
new File(OUTPUT_DIR).mkdirs()

OpmClijMipOnlyPoc.log('')
OpmClijMipOnlyPoc.log('OPM CLIJ MIP-only POC')
OpmClijMipOnlyPoc.log('Input: ' + INPUT_TIFF)
OpmClijMipOnlyPoc.log('Output: ' + OUTPUT_DIR)
OpmClijMipOnlyPoc.log(String.format(
        'Parameters: angle=%.4f deg, xy=%.6f um, dz=%.6f um',
        OPM_ANGLE_DEG, XY_PIXEL_SIZE_UM, Z_STEP_UM))

long tOpen = OpmClijMipOnlyPoc.now()
ImagePlus rawImp = IJ.openImage(INPUT_TIFF)
if (rawImp == null) {
    throw new RuntimeException('Could not open input TIFF: ' + INPUT_TIFF)
}
int rawWidth = rawImp.getWidth()
int rawHeight = rawImp.getHeight()
int rawDepth = rawImp.getStackSize()
OpmClijMipOnlyPoc.log(String.format(
        'Opened raw stack: width=%d, height=%d, depth=%d in %.3f s',
        rawWidth, rawHeight, rawDepth, OpmClijMipOnlyPoc.secondsSince(tOpen)))

long[] rawDims = [rawWidth as long, rawHeight as long, rawDepth as long] as long[]
double[][] forwardMatrix = OpmClijMipOnlyPoc.deskewMatrix(
        Z_STEP_UM, XY_PIXEL_SIZE_UM, OPM_ANGLE_DEG, rawHeight)
long[] outputDims = OpmClijMipOnlyPoc.transformedDimensions(rawDims, forwardMatrix)
double[][] centeredForwardMatrix = OpmClijMipOnlyPoc.autoCenter(rawDims, forwardMatrix)
AffineTransform3D inverseTransform = OpmClijMipOnlyPoc.toInverseAffineTransform3D(centeredForwardMatrix)

OpmClijMipOnlyPoc.log(String.format(
        'Deskewed GPU volume geometry: width=%d, height=%d, depth=%d',
        outputDims[0], outputDims[1], outputDims[2]))

CLIJ2 clij2 = null
ClearCLBuffer rawGpu = null
ClearCLBuffer deskewGpu = null
ClearCLBuffer mipXYGpu = null
ClearCLBuffer mipYZGpu = null
ClearCLBuffer mipXZGpu = null

try {
    long tGpuInit = OpmClijMipOnlyPoc.now()
    clij2 = OpmClijMipOnlyPoc.getClij2(GPU_NAME_HINT)
    clij2.clear()
    OpmClijMipOnlyPoc.log(String.format('CLIJ2 initialized in %.3f s', OpmClijMipOnlyPoc.secondsSince(tGpuInit)))
    OpmClijMipOnlyPoc.log(clij2.reportMemory())

    long tUpload = OpmClijMipOnlyPoc.now()
    rawGpu = clij2.push(rawImp)
    OpmClijMipOnlyPoc.log(String.format(
            'Uploaded raw stack to GPU in %.3f s',
            OpmClijMipOnlyPoc.secondsSince(tUpload)))
    if (CLOSE_RAW_AFTER_UPLOAD) {
        long tCloseRaw = OpmClijMipOnlyPoc.now()
        rawImp.changes = false
        rawImp.close()
        rawImp.flush()
        rawImp = null
        System.gc()
        OpmClijMipOnlyPoc.log(String.format(
                'Closed raw ImagePlus after upload in %.3f s',
                OpmClijMipOnlyPoc.secondsSince(tCloseRaw)))
    }

    long tAlloc = OpmClijMipOnlyPoc.now()
    deskewGpu = clij2.create(outputDims, rawGpu.getNativeType())
    mipXYGpu = clij2.create([outputDims[0], outputDims[1]] as long[], rawGpu.getNativeType())
    mipYZGpu = clij2.create([outputDims[1], outputDims[2]] as long[], rawGpu.getNativeType())
    mipXZGpu = clij2.create([outputDims[0], outputDims[2]] as long[], rawGpu.getNativeType())
    OpmClijMipOnlyPoc.log(String.format(
            'Allocated deskew volume and three MIPs on GPU in %.3f s',
            OpmClijMipOnlyPoc.secondsSince(tAlloc)))

    long tDeskew = OpmClijMipOnlyPoc.now()
    clij2.affineTransform3D(rawGpu, deskewGpu, inverseTransform)
    OpmClijMipOnlyPoc.log(String.format(
            'GPU affine deskew finished in %.3f s',
            OpmClijMipOnlyPoc.secondsSince(tDeskew)))
    clij2.release(rawGpu)
    rawGpu = null

    long tMip = OpmClijMipOnlyPoc.now()
    clij2.maximumZProjection(deskewGpu, mipXYGpu)
    clij2.maximumXProjection(deskewGpu, mipYZGpu)
    clij2.maximumYProjection(deskewGpu, mipXZGpu)
    OpmClijMipOnlyPoc.log(String.format(
            'GPU max projections XY/YZ/XZ finished in %.3f s',
            OpmClijMipOnlyPoc.secondsSince(tMip)))

    long tPull = OpmClijMipOnlyPoc.now()
    ImagePlus mipXY = clij2.pull(mipXYGpu)
    ImagePlus mipYZ = clij2.pull(mipYZGpu)
    ImagePlus mipXZ = clij2.pull(mipXZGpu)
    mipXY.setTitle('OPM CLIJ MIP-only XY')
    mipYZ.setTitle('OPM CLIJ MIP-only YZ')
    mipXZ.setTitle('OPM CLIJ MIP-only XZ')
    OpmClijMipOnlyPoc.log(String.format(
            'Pulled three MIPs from GPU in %.3f s',
            OpmClijMipOnlyPoc.secondsSince(tPull)))

    long tOutput = OpmClijMipOnlyPoc.now()
    if (DISPLAY_MIPS) {
        mipXY.show()
        mipYZ.show()
        mipXZ.show()
    }
    if (SAVE_MIPS) {
        OpmClijMipOnlyPoc.saveTiff(mipXY, new File(OUTPUT_DIR, 'clij_mip_xy.tif'))
        OpmClijMipOnlyPoc.saveTiff(mipYZ, new File(OUTPUT_DIR, 'clij_mip_yz.tif'))
        OpmClijMipOnlyPoc.saveTiff(mipXZ, new File(OUTPUT_DIR, 'clij_mip_xz.tif'))
    }
    OpmClijMipOnlyPoc.log(String.format(
            'Display/save output finished in %.3f s',
            OpmClijMipOnlyPoc.secondsSince(tOutput)))
    OpmClijMipOnlyPoc.log(String.format(
            'Live-QC elapsed time through saved MIPs: %.3f s',
            OpmClijMipOnlyPoc.secondsSince(allStart)))

    if (SAVE_DESKEW_VOLUME) {
        double estimatedDeskewGiB = outputDims[0] * (double) outputDims[1] * (double) outputDims[2] * 2.0d / 1024.0d / 1024.0d / 1024.0d
        OpmClijMipOnlyPoc.log(String.format(
                'Full deskewed volume output is estimated at %.3f GiB before TIFF metadata.',
                estimatedDeskewGiB))

        long tPullVolume = OpmClijMipOnlyPoc.now()
        ImagePlus deskewImp = clij2.pull(deskewGpu)
        deskewImp.setTitle('OPM CLIJ deskewed volume')
        OpmClijMipOnlyPoc.log(String.format(
                'Pulled deskewed volume from GPU in %.3f s',
                OpmClijMipOnlyPoc.secondsSince(tPullVolume)))

        long tSaveVolume = OpmClijMipOnlyPoc.now()
        OpmClijMipOnlyPoc.saveTiff(deskewImp, new File(OUTPUT_DIR, 'clij_deskew_volume.tif'))
        OpmClijMipOnlyPoc.log(String.format(
                'Saved deskewed TIFF stack in %.3f s',
                OpmClijMipOnlyPoc.secondsSince(tSaveVolume)))
        OpmClijMipOnlyPoc.log(String.format(
                'Full-output elapsed time through saved deskewed TIFF stack: %.3f s',
                OpmClijMipOnlyPoc.secondsSince(allStart)))

        deskewImp.changes = false
        deskewImp.close()
        deskewImp.flush()
        System.gc()
    }

    OpmClijMipOnlyPoc.log(clij2.reportMemory())
} finally {
    if (clij2 != null) {
        if (rawGpu != null) clij2.release(rawGpu)
        if (deskewGpu != null) clij2.release(deskewGpu)
        if (mipXYGpu != null) clij2.release(mipXYGpu)
        if (mipYZGpu != null) clij2.release(mipYZGpu)
        if (mipXZGpu != null) clij2.release(mipXZGpu)
        clij2.clear()
    }
}

OpmClijMipOnlyPoc.log(String.format('Total elapsed time: %.3f s', OpmClijMipOnlyPoc.secondsSince(allStart)))
OpmClijMipOnlyPoc.log('Done.')
