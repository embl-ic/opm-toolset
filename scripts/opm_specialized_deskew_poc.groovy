// OPM specialized deskew proof-of-concept for Fiji/ImageJ Groovy.
//
// Goal:
//   Test the "specialized deskew + fused MIP" principle on the first sample
//   TIFF without using the generic 3D affine path. This is intentionally a
//   CPU/reference timing harness first: it uses Fiji for TIFF IO, precomputes
//   the OPM Y/Z inverse map once, processes independent X columns in parallel,
//   and fuses all three max projections into the same pass.
//
// To run in Fiji:
//   Plugins > Scripting > Script Editor, language = Groovy, open this file,
//   then Run.
//
// Notes:
//   - X_WIDTH_LIMIT defaults to 128 columns so the first test finishes quickly.
//     Set X_WIDTH_LIMIT = 0 for the full volume.
//   - SAVE_DESKEW_VOLUME is off by default because a full deskewed stack can be
//     several GB in memory. The fast QC path is the fused MIP output.
//   - This script is the algorithmic reference for a later custom GPU kernel:
//     one kernel can use the same map and update per-slab projections.

import ij.IJ
import ij.ImagePlus
import ij.ImageStack
import ij.io.FileSaver
import ij.process.ShortProcessor

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

// -------------------------------------------------------------------------
// Configuration
// -------------------------------------------------------------------------

final String INPUT_TIFF =
        'D:/Workspace/Microscopes/OPM/test_&_simulation/deskew_live/mitosis-test-timelapse_0/mitosis-test-timelapse_Position0001_Time000001_Channel0001_Frames1_521.tiff'

final String OUTPUT_DIR =
        'D:/Workspace/Microscopes/OPM/test_&_simulation/deskew_live/mitosis-test-timelapse_0/poc_specialized_deskew'

final double XY_PIXEL_SIZE_UM = 0.116
final double Z_STEP_UM = 0.265
final double OPM_ANGLE_DEG = 25.0

// 0 means process the full X width. A smaller value is useful for first timing.
final int X_START = 0
final int X_WIDTH_LIMIT = 0

final boolean SAVE_MIPS = true
final boolean DISPLAY_MIPS = true
final boolean SAVE_DESKEW_VOLUME = false

// Keep one core free by default for Fiji/Windows responsiveness.
final int NUM_THREADS = Math.max(1, Runtime.runtime.availableProcessors() - 1)

// -------------------------------------------------------------------------
// Implementation
// -------------------------------------------------------------------------

class OpmDeskewMapEntry {
    boolean valid
    int y0
    int y1
    int z0
    int z1
    float wy
    float wz
}

class OpmDeskewPoc {
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

    static int ceilPositive(double value) {
        return (int) Math.ceil(value)
    }

    static OpmDeskewMapEntry[] buildInverseMap(
            int rawHeight,
            int rawDepth,
            int outHeight,
            int outDepth,
            double xyPixelSizeUm,
            double zStepUm,
            double angleDeg) {

        final double theta = Math.toRadians(angleDeg)
        final double cosTheta = Math.cos(theta)
        final double sinTheta = Math.sin(theta)
        final double dzOverDxy = zStepUm / xyPixelSizeUm

        OpmDeskewMapEntry[] map = new OpmDeskewMapEntry[outHeight * outDepth]

        for (int zOut = 0; zOut < outDepth; zOut++) {
            // Inverse of zOut = -sin(theta) * yIn + rawHeight * sin(theta).
            double yIn = (rawHeight * sinTheta - zOut) / sinTheta
            int y0 = (int) Math.floor(yIn)
            int y1 = y0 + 1
            float wy = (float) (yIn - y0)
            boolean yValid = y0 >= 0 && y1 < rawHeight

            for (int yOut = 0; yOut < outHeight; yOut++) {
                OpmDeskewMapEntry e = new OpmDeskewMapEntry()

                if (yValid) {
                    // Inverse of yOut = cos(theta) * yIn + (dz/dxy) * zIn.
                    double zIn = (yOut - cosTheta * yIn) / dzOverDxy
                    int z0 = (int) Math.floor(zIn)
                    int z1 = z0 + 1

                    if (z0 >= 0 && z1 < rawDepth) {
                        e.valid = true
                        e.y0 = y0
                        e.y1 = y1
                        e.z0 = z0
                        e.z1 = z1
                        e.wy = wy
                        e.wz = (float) (zIn - z0)
                    }
                }

                map[zOut * outHeight + yOut] = e
            }
        }

        return map
    }

    static int sampleLinearYZ(short[][] rawSlices, int rawWidth, int x, OpmDeskewMapEntry e) {
        if (!e.valid) {
            return 0
        }

        int p00 = rawSlices[e.z0][e.y0 * rawWidth + x] & 0xffff
        int p10 = rawSlices[e.z0][e.y1 * rawWidth + x] & 0xffff
        int p01 = rawSlices[e.z1][e.y0 * rawWidth + x] & 0xffff
        int p11 = rawSlices[e.z1][e.y1 * rawWidth + x] & 0xffff

        float a = p00 + (p10 - p00) * e.wy
        float b = p01 + (p11 - p01) * e.wy
        return Math.round(a + (b - a) * e.wz)
    }

    static short[] toShortPixels(int[] pixels) {
        short[] out = new short[pixels.length]
        for (int i = 0; i < pixels.length; i++) {
            out[i] = (short) Math.min(65535, pixels[i])
        }
        return out
    }

    static ImagePlus makeShortImage(String title, int width, int height, int[] pixels) {
        return new ImagePlus(title, new ShortProcessor(width, height, toShortPixels(pixels), null))
    }

    static void saveTiff(ImagePlus imp, File outputFile) {
        outputFile.parentFile.mkdirs()
        new FileSaver(imp).saveAsTiff(outputFile.absolutePath)
    }
}

long allStart = OpmDeskewPoc.now()
new File(OUTPUT_DIR).mkdirs()

OpmDeskewPoc.log('')
OpmDeskewPoc.log('OPM specialized deskew POC')
OpmDeskewPoc.log('Input: ' + INPUT_TIFF)
OpmDeskewPoc.log('Output: ' + OUTPUT_DIR)
OpmDeskewPoc.log(String.format(
        'Parameters: angle=%.4f deg, xy=%.6f um, dz=%.6f um',
        OPM_ANGLE_DEG, XY_PIXEL_SIZE_UM, Z_STEP_UM))

long tOpen = OpmDeskewPoc.now()
ImagePlus rawImp = IJ.openImage(INPUT_TIFF)
if (rawImp == null) {
    throw new RuntimeException('Could not open input TIFF: ' + INPUT_TIFF)
}

int rawWidth = rawImp.width
int rawHeight = rawImp.height
int rawDepth = rawImp.stackSize
OpmDeskewPoc.log(String.format(
        'Opened raw stack: width=%d, height=%d, depth=%d in %.3f s',
        rawWidth, rawHeight, rawDepth, OpmDeskewPoc.secondsSince(tOpen)))

long tCache = OpmDeskewPoc.now()
short[][] rawSlices = new short[rawDepth][]
ImageStack rawStack = rawImp.getStack()
for (int z = 0; z < rawDepth; z++) {
    Object pix = rawStack.getProcessor(z + 1).pixels
    if (!(pix instanceof short[])) {
        throw new RuntimeException('Expected 16-bit TIFF slices; slice ' + (z + 1) + ' was ' + pix.getClass())
    }
    rawSlices[z] = (short[]) pix
}
OpmDeskewPoc.log(String.format('Cached raw slice pointers in %.3f s', OpmDeskewPoc.secondsSince(tCache)))

double theta = Math.toRadians(OPM_ANGLE_DEG)
double cosTheta = Math.cos(theta)
double sinTheta = Math.sin(theta)
double dzOverDxy = Z_STEP_UM / XY_PIXEL_SIZE_UM

int outWidth = rawWidth
int outHeight = OpmDeskewPoc.ceilPositive(rawHeight * cosTheta + rawDepth * dzOverDxy)
int outDepth = OpmDeskewPoc.ceilPositive(rawHeight * sinTheta)

int xEndRequested = X_WIDTH_LIMIT <= 0 ? rawWidth : Math.min(rawWidth, X_START + X_WIDTH_LIMIT)
int xStart = Math.max(0, Math.min(rawWidth, X_START))
int xEnd = Math.max(xStart, xEndRequested)
int xCount = xEnd - xStart

OpmDeskewPoc.log(String.format(
        'Deskewed geometry: width=%d, height=%d, depth=%d',
        outWidth, outHeight, outDepth))
OpmDeskewPoc.log(String.format(
        'Processing X range [%d, %d), columns=%d of %d, threads=%d',
        xStart, xEnd, xCount, rawWidth, NUM_THREADS))

long tMap = OpmDeskewPoc.now()
OpmDeskewMapEntry[] map = OpmDeskewPoc.buildInverseMap(
        rawHeight, rawDepth, outHeight, outDepth,
        XY_PIXEL_SIZE_UM, Z_STEP_UM, OPM_ANGLE_DEG)
OpmDeskewPoc.log(String.format(
        'Precomputed Y/Z inverse map (%d entries) in %.3f s',
        map.length, OpmDeskewPoc.secondsSince(tMap)))

int[] mipXY = new int[outWidth * outHeight]
int[] mipXZ = new int[outWidth * outDepth]
int[] mipYZ = new int[outHeight * outDepth]

ImageStack deskewStack = null
if (SAVE_DESKEW_VOLUME) {
    OpmDeskewPoc.log('Allocating deskew output stack; this can require several GB for full-width processing.')
    deskewStack = new ImageStack(outWidth, outHeight)
    for (int z = 0; z < outDepth; z++) {
        deskewStack.addSlice(new ShortProcessor(outWidth, outHeight))
    }
}

long tDeskew = OpmDeskewPoc.now()
def executor = Executors.newFixedThreadPool(NUM_THREADS)
List<Future> futures = []
int chunk = Math.max(1, (int) Math.ceil(xCount / (double) NUM_THREADS))

for (int taskX0 = xStart; taskX0 < xEnd; taskX0 += chunk) {
    final int localX0 = taskX0
    final int localX1 = Math.min(xEnd, taskX0 + chunk)
    futures.add(executor.submit({
        int[] localMipYZ = new int[outHeight * outDepth]

        for (int x = localX0; x < localX1; x++) {
            for (int zOut = 0; zOut < outDepth; zOut++) {
                short[] deskewPlane = null
                if (deskewStack != null) {
                    deskewPlane = (short[]) deskewStack.getProcessor(zOut + 1).pixels
                }

                int mapBase = zOut * outHeight
                int mipXZIndex = zOut * outWidth + x
                int mipYZBase = zOut * outHeight

                for (int yOut = 0; yOut < outHeight; yOut++) {
                    int v = OpmDeskewPoc.sampleLinearYZ(rawSlices, rawWidth, x, map[mapBase + yOut])

                    int xyIndex = yOut * outWidth + x
                    if (v > mipXY[xyIndex]) {
                        mipXY[xyIndex] = v
                    }

                    if (v > mipXZ[mipXZIndex]) {
                        mipXZ[mipXZIndex] = v
                    }

                    int yzIndex = mipYZBase + yOut
                    if (v > localMipYZ[yzIndex]) {
                        localMipYZ[yzIndex] = v
                    }

                    if (deskewPlane != null) {
                        deskewPlane[yOut * outWidth + x] = (short) v
                    }
                }
            }
        }

        synchronized (mipYZ) {
            for (int i = 0; i < mipYZ.length; i++) {
                if (localMipYZ[i] > mipYZ[i]) {
                    mipYZ[i] = localMipYZ[i]
                }
            }
        }

        return null
    } as Callable))
}

for (Future f : futures) {
    f.get()
}
executor.shutdown()

double deskewSeconds = OpmDeskewPoc.secondsSince(tDeskew)
double processedGVoxels = (xCount * (double) outHeight * (double) outDepth) / 1.0e9d
double fullWidthEstimate = xCount > 0 ? deskewSeconds * rawWidth / (double) xCount : 0.0d

OpmDeskewPoc.log(String.format(
        'Specialized deskew + fused MIPs finished in %.3f s for %.3f G output samples',
        deskewSeconds, processedGVoxels))
OpmDeskewPoc.log(String.format(
        'Throughput: %.3f M output samples/s',
        processedGVoxels * 1000.0d / Math.max(1.0e-9d, deskewSeconds)))
if (xCount < rawWidth) {
    OpmDeskewPoc.log(String.format(
            'Linear full-width estimate from this X slab: %.3f s',
            fullWidthEstimate))
}

long tOutput = OpmDeskewPoc.now()
ImagePlus mipXYImp = OpmDeskewPoc.makeShortImage('OPM POC MIP XY', outWidth, outHeight, mipXY)
ImagePlus mipXZImp = OpmDeskewPoc.makeShortImage('OPM POC MIP XZ', outWidth, outDepth, mipXZ)
ImagePlus mipYZImp = OpmDeskewPoc.makeShortImage('OPM POC MIP YZ', outHeight, outDepth, mipYZ)

if (DISPLAY_MIPS) {
    mipXYImp.show()
    mipXZImp.show()
    mipYZImp.show()
}

if (SAVE_MIPS) {
    OpmDeskewPoc.saveTiff(mipXYImp, new File(OUTPUT_DIR, 'poc_mip_xy.tif'))
    OpmDeskewPoc.saveTiff(mipXZImp, new File(OUTPUT_DIR, 'poc_mip_xz.tif'))
    OpmDeskewPoc.saveTiff(mipYZImp, new File(OUTPUT_DIR, 'poc_mip_yz.tif'))
}

if (SAVE_DESKEW_VOLUME && deskewStack != null) {
    ImagePlus deskewImp = new ImagePlus('OPM POC deskewed volume', deskewStack)
    OpmDeskewPoc.saveTiff(deskewImp, new File(OUTPUT_DIR, 'poc_deskew_volume.tif'))
}

OpmDeskewPoc.log(String.format('Display/save output finished in %.3f s', OpmDeskewPoc.secondsSince(tOutput)))
OpmDeskewPoc.log(String.format('Total elapsed time: %.3f s', OpmDeskewPoc.secondsSince(allStart)))
OpmDeskewPoc.log('Done.')
