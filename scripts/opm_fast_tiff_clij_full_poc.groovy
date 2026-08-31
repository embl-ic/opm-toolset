// OPM fast TIFF + CLIJ full-output proof-of-concept for Fiji/ImageJ Groovy.
//
// Pipeline:
//   custom targeted TIFF reader -> ImageStack wrapper -> CLIJ upload ->
//   GPU affine deskew -> GPU MIPs -> save MIPs -> save deskewed TIFF stack
//
// This combines the best-performing pieces from the earlier POCs:
//   - custom BigTIFF/strip reader for fast input
//   - keep deskew and MIPs on GPU
//   - pull only final products
//
// To run in Fiji:
//   Plugins > Scripting > Script Editor, language = Groovy, open this file,
//   then Run.

import ij.IJ
import ij.ImagePlus
import ij.ImageStack
import ij.io.FileSaver
import ij.process.ShortProcessor

import net.haesleinhuepf.clij.clearcl.ClearCLBuffer
import net.haesleinhuepf.clij2.CLIJ2
import net.imglib2.realtransform.AffineTransform3D

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import java.util.concurrent.Future

// -------------------------------------------------------------------------
// Configuration
// -------------------------------------------------------------------------

final String INPUT_TIFF =
        'D:/Workspace/Microscopes/OPM/test_&_simulation/deskew_live/mitosis-test-timelapse_0/mitosis-test-timelapse_Position0001_Time000001_Channel0001_Frames1_521.tiff'

final String OUTPUT_DIR =
        'D:/Workspace/Microscopes/OPM/test_&_simulation/deskew_live/mitosis-test-timelapse_0/poc_fast_tiff_clij_full'

final double XY_PIXEL_SIZE_UM = 0.116
final double Z_STEP_UM = 0.265
final double OPM_ANGLE_DEG = 25.0

final boolean DISPLAY_MIPS = true
final boolean SAVE_MIPS = true
final boolean SAVE_DESKEW_VOLUME = true
final boolean CLOSE_RAW_AFTER_UPLOAD = true

final String GPU_NAME_HINT = 'RTX'

// 1 keeps the old single-thread reader. 4-8 is usually the useful range on NVMe.
final int FAST_TIFF_THREADS = 8

// -------------------------------------------------------------------------
// Shared utilities
// -------------------------------------------------------------------------

class OpmFastClijPoc {
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

    static ImagePlus wrapShortVolume(String title, short[][] volume, int width, int height) {
        ImageStack stack = new ImageStack(width, height)
        for (int z = 0; z < volume.length; z++) {
            stack.addSlice(null, new ShortProcessor(width, height, volume[z], null))
        }
        return new ImagePlus(title, stack)
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

// -------------------------------------------------------------------------
// Custom targeted TIFF reader
// -------------------------------------------------------------------------

class FastTiffInfo {
    boolean bigTiff
    ByteOrder byteOrder
    int width
    int height
    int bitsPerSample
    int compression
    int samplesPerPixel
    long rowsPerStrip
    List<long[]> stripOffsets = []
    List<long[]> stripByteCounts = []
}

class FastTiffReader {
    static final int TAG_IMAGE_WIDTH = 256
    static final int TAG_IMAGE_LENGTH = 257
    static final int TAG_BITS_PER_SAMPLE = 258
    static final int TAG_COMPRESSION = 259
    static final int TAG_STRIP_OFFSETS = 273
    static final int TAG_SAMPLES_PER_PIXEL = 277
    static final int TAG_ROWS_PER_STRIP = 278
    static final int TAG_STRIP_BYTE_COUNTS = 279

    static final int TYPE_BYTE = 1
    static final int TYPE_ASCII = 2
    static final int TYPE_SHORT = 3
    static final int TYPE_LONG = 4
    static final int TYPE_RATIONAL = 5
    static final int TYPE_UNDEFINED = 7
    static final int TYPE_LONG8 = 16
    static final int TYPE_IFD8 = 18

    static class IfdResult {
        Map<Integer, long[]> tags
        long nextOffset
    }

    static ByteBuffer readAt(FileChannel channel, long offset, int length, ByteOrder order) {
        ByteBuffer buffer = ByteBuffer.allocate(length)
        long filePosition = offset
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, filePosition)
            if (n < 0) {
                throw new EOFException('Unexpected end of TIFF file.')
            }
            filePosition += n
        }
        buffer.flip()
        buffer.order(order)
        return buffer
    }

    static void readFully(FileChannel channel, ByteBuffer buffer, long offset) {
        long filePosition = offset
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, filePosition)
            if (n < 0) {
                throw new EOFException('Unexpected end of TIFF file while reading pixels.')
            }
            filePosition += n
        }
    }

    static int uShort(ByteBuffer buffer) {
        return buffer.getShort() & 0xffff
    }

    static long uInt(ByteBuffer buffer) {
        return buffer.getInt() & 0xffffffffL
    }

    static int typeSize(int type) {
        switch (type) {
            case TYPE_BYTE:
            case TYPE_ASCII:
            case TYPE_UNDEFINED:
                return 1
            case TYPE_SHORT:
                return 2
            case TYPE_LONG:
                return 4
            case TYPE_RATIONAL:
            case TYPE_LONG8:
            case TYPE_IFD8:
                return 8
            default:
                throw new RuntimeException('Unsupported TIFF field type: ' + type)
        }
    }

    static long[] readValues(
            FileChannel channel,
            ByteOrder order,
            int type,
            long count,
            long valueOffset,
            byte[] embedded) {

        if (count > Integer.MAX_VALUE) {
            throw new RuntimeException('TIFF field count too large: ' + count)
        }
        int n = (int) count
        int bytes = Math.multiplyExact(typeSize(type), n)
        ByteBuffer buffer
        if (bytes <= embedded.length) {
            buffer = ByteBuffer.wrap(embedded, 0, embedded.length)
            buffer.order(order)
        } else {
            buffer = readAt(channel, valueOffset, bytes, order)
        }

        long[] out = new long[n]
        for (int i = 0; i < n; i++) {
            switch (type) {
                case TYPE_BYTE:
                case TYPE_UNDEFINED:
                    out[i] = buffer.get() & 0xffL
                    break
                case TYPE_SHORT:
                    out[i] = buffer.getShort() & 0xffffL
                    break
                case TYPE_LONG:
                    out[i] = buffer.getInt() & 0xffffffffL
                    break
                case TYPE_LONG8:
                case TYPE_IFD8:
                    out[i] = buffer.getLong()
                    break
                default:
                    throw new RuntimeException('Unsupported numeric TIFF field type in fast reader: ' + type)
            }
        }
        return out
    }

    static IfdResult readIfd(FileChannel channel, ByteOrder order, boolean bigTiff, long ifdOffset) {
        int countBytes = bigTiff ? 8 : 2
        ByteBuffer countBuffer = readAt(channel, ifdOffset, countBytes, order)
        long entryCount = bigTiff ? countBuffer.getLong() : uShort(countBuffer)
        if (entryCount < 0 || entryCount > 4096) {
            throw new RuntimeException('Unexpected TIFF IFD entry count: ' + entryCount)
        }

        int entrySize = bigTiff ? 20 : 12
        int nextBytes = bigTiff ? 8 : 4
        ByteBuffer entries = readAt(channel, ifdOffset + countBytes, (int) entryCount * entrySize + nextBytes, order)
        Map<Integer, long[]> tags = new HashMap<Integer, long[]>()

        for (int i = 0; i < (int) entryCount; i++) {
            int tag = uShort(entries)
            int type = uShort(entries)
            long count = bigTiff ? entries.getLong() : uInt(entries)
            int embeddedSize = bigTiff ? 8 : 4
            byte[] embedded = new byte[embeddedSize]
            long valueOffset

            int valuePosition = entries.position()
            if (bigTiff) {
                valueOffset = entries.getLong()
            } else {
                valueOffset = uInt(entries)
            }
            entries.position(valuePosition)
            entries.get(embedded)

            if (tag == TAG_IMAGE_WIDTH ||
                    tag == TAG_IMAGE_LENGTH ||
                    tag == TAG_BITS_PER_SAMPLE ||
                    tag == TAG_COMPRESSION ||
                    tag == TAG_STRIP_OFFSETS ||
                    tag == TAG_SAMPLES_PER_PIXEL ||
                    tag == TAG_ROWS_PER_STRIP ||
                    tag == TAG_STRIP_BYTE_COUNTS) {
                tags.put(tag, readValues(channel, order, type, count, valueOffset, embedded))
            }
        }

        long nextOffset = bigTiff ? entries.getLong() : uInt(entries)
        IfdResult result = new IfdResult()
        result.tags = tags
        result.nextOffset = nextOffset
        return result
    }

    static long firstOrDefault(Map<Integer, long[]> tags, int tag, long fallback) {
        long[] value = tags.get(tag)
        return value == null || value.length == 0 ? fallback : value[0]
    }

    static FastTiffInfo parse(File file) {
        FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
        try {
            ByteBuffer header = ByteBuffer.allocate(16)
            readFully(channel, header, 0)
            header.flip()

            int b0 = header.get() & 0xff
            int b1 = header.get() & 0xff
            ByteOrder order
            if (b0 == 0x49 && b1 == 0x49) {
                order = ByteOrder.LITTLE_ENDIAN
            } else if (b0 == 0x4d && b1 == 0x4d) {
                order = ByteOrder.BIG_ENDIAN
            } else {
                throw new RuntimeException('Not a TIFF header.')
            }

            header.order(order)
            int magic = header.getShort() & 0xffff
            boolean bigTiff
            long ifdOffset
            if (magic == 42) {
                bigTiff = false
                ifdOffset = header.getInt() & 0xffffffffL
            } else if (magic == 43) {
                bigTiff = true
                int offsetSize = header.getShort() & 0xffff
                int zero = header.getShort() & 0xffff
                if (offsetSize != 8 || zero != 0) {
                    throw new RuntimeException('Unsupported BigTIFF header.')
                }
                ifdOffset = header.getLong()
            } else {
                throw new RuntimeException('Unsupported TIFF magic number: ' + magic)
            }

            FastTiffInfo info = new FastTiffInfo()
            info.bigTiff = bigTiff
            info.byteOrder = order

            int planeIndex = 0
            while (ifdOffset > 0 && ifdOffset < channel.size()) {
                IfdResult ifd = readIfd(channel, order, bigTiff, ifdOffset)
                Map<Integer, long[]> tags = ifd.tags

                int width = (int) firstOrDefault(tags, TAG_IMAGE_WIDTH, -1)
                int height = (int) firstOrDefault(tags, TAG_IMAGE_LENGTH, -1)
                if (width <= 0 || height <= 0) {
                    break
                }

                int bits = (int) firstOrDefault(tags, TAG_BITS_PER_SAMPLE, 0)
                int compression = (int) firstOrDefault(tags, TAG_COMPRESSION, 1)
                int samples = (int) firstOrDefault(tags, TAG_SAMPLES_PER_PIXEL, 1)
                long rowsPerStrip = firstOrDefault(tags, TAG_ROWS_PER_STRIP, height)
                long[] offsets = tags.get(TAG_STRIP_OFFSETS)
                long[] counts = tags.get(TAG_STRIP_BYTE_COUNTS)
                if (offsets == null || counts == null) {
                    throw new RuntimeException('Missing strip offsets/byte counts at IFD ' + planeIndex)
                }

                if (planeIndex == 0) {
                    info.width = width
                    info.height = height
                    info.bitsPerSample = bits
                    info.compression = compression
                    info.samplesPerPixel = samples
                    info.rowsPerStrip = rowsPerStrip
                } else if (width != info.width || height != info.height ||
                        bits != info.bitsPerSample || compression != info.compression ||
                        samples != info.samplesPerPixel) {
                    throw new RuntimeException('TIFF plane layout changes at IFD ' + planeIndex)
                }

                info.stripOffsets.add(offsets)
                info.stripByteCounts.add(counts)
                planeIndex++
                ifdOffset = ifd.nextOffset
            }

            if (info.stripOffsets.isEmpty()) {
                throw new RuntimeException('No image planes found in TIFF.')
            }
            if (info.bitsPerSample != 16 || info.samplesPerPixel != 1 || info.compression != 1) {
                throw new RuntimeException(String.format(
                        'Fast reader supports only uncompressed 16-bit grayscale. Found bits=%d samples=%d compression=%d',
                        info.bitsPerSample, info.samplesPerPixel, info.compression))
            }
            return info
        } finally {
            channel.close()
        }
    }

    static short[][] readPixels(File file, FastTiffInfo info) {
        int w = info.width
        int h = info.height
        int d = info.stripOffsets.size()
        int planeBytes = Math.multiplyExact(w * h, 2)
        int rowBytes = Math.multiplyExact(w, 2)
        byte[] rawPlane = new byte[planeBytes]
        short[][] volume = new short[d][]

        FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
        try {
            for (int z = 0; z < d; z++) {
                long[] offsets = info.stripOffsets.get(z)
                long[] counts = info.stripByteCounts.get(z)
                if (offsets.length != counts.length) {
                    throw new RuntimeException('Strip offset/count mismatch at plane ' + z)
                }

                for (int s = 0; s < offsets.length; s++) {
                    int byteCount = (int) counts[s]
                    int dstOffset = (int) Math.min((long) s * info.rowsPerStrip * rowBytes, planeBytes)
                    ByteBuffer dst = ByteBuffer.wrap(rawPlane, dstOffset, byteCount)
                    readFully(channel, dst, offsets[s])
                }

                short[] pixels = new short[w * h]
                ByteBuffer.wrap(rawPlane).order(info.byteOrder).asShortBuffer().get(pixels)
                volume[z] = pixels
            }
            return volume
        } finally {
            channel.close()
        }
    }

    static short[][] readPixelsParallel(File file, FastTiffInfo info, int numThreads) {
        if (numThreads <= 1) {
            return readPixels(file, info)
        }

        int w = info.width
        int h = info.height
        int d = info.stripOffsets.size()
        int planeBytes = Math.multiplyExact(w * h, 2)
        int rowBytes = Math.multiplyExact(w, 2)
        short[][] volume = new short[d][]

        int tasks = Math.max(1, Math.min(numThreads, d))
        int planesPerTask = (int) Math.ceil(d / (double) tasks)
        def executor = Executors.newFixedThreadPool(tasks)
        List<Future> futures = []

        try {
            for (int task = 0; task < tasks; task++) {
                final int zStart = task * planesPerTask
                final int zEnd = Math.min(d, zStart + planesPerTask)
                if (zStart >= zEnd) continue

                futures.add(executor.submit({
                    byte[] rawPlane = new byte[planeBytes]
                    FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
                    try {
                        for (int z = zStart; z < zEnd; z++) {
                            long[] offsets = info.stripOffsets.get(z)
                            long[] counts = info.stripByteCounts.get(z)
                            if (offsets.length != counts.length) {
                                throw new RuntimeException('Strip offset/count mismatch at plane ' + z)
                            }
                            for (int s = 0; s < offsets.length; s++) {
                                int byteCount = (int) counts[s]
                                int dstOffset = (int) Math.min((long) s * info.rowsPerStrip * rowBytes, planeBytes)
                                ByteBuffer dst = ByteBuffer.wrap(rawPlane, dstOffset, byteCount)
                                readFully(channel, dst, offsets[s])
                            }
                            short[] pixels = new short[w * h]
                            ByteBuffer.wrap(rawPlane).order(info.byteOrder).asShortBuffer().get(pixels)
                            volume[z] = pixels
                        }
                    } finally {
                        channel.close()
                    }
                    return null
                } as java.util.concurrent.Callable))
            }
            for (Future f : futures) f.get()
        } finally {
            executor.shutdownNow()
        }
        return volume
    }
}

// -------------------------------------------------------------------------
// Pipeline
// -------------------------------------------------------------------------

long allStart = OpmFastClijPoc.now()
new File(OUTPUT_DIR).mkdirs()

OpmFastClijPoc.log('')
OpmFastClijPoc.log('OPM fast TIFF + CLIJ full-output POC')
OpmFastClijPoc.log('Input: ' + INPUT_TIFF)
OpmFastClijPoc.log('Output: ' + OUTPUT_DIR)
OpmFastClijPoc.log(String.format(
        'Parameters: angle=%.4f deg, xy=%.6f um, dz=%.6f um',
        OPM_ANGLE_DEG, XY_PIXEL_SIZE_UM, Z_STEP_UM))

File inputFile = new File(INPUT_TIFF)
if (!inputFile.isFile()) {
    throw new RuntimeException('Input file not found: ' + INPUT_TIFF)
}

long tParse = OpmFastClijPoc.now()
FastTiffInfo tiffInfo = FastTiffReader.parse(inputFile)
double parseSec = OpmFastClijPoc.secondsSince(tParse)
OpmFastClijPoc.log(String.format(
        'Fast TIFF metadata: %s, %s, %dx%dx%d, rowsPerStrip=%d, parse %.3f s',
        tiffInfo.bigTiff ? 'BigTIFF' : 'classic TIFF',
        tiffInfo.byteOrder == ByteOrder.LITTLE_ENDIAN ? 'little-endian' : 'big-endian',
        tiffInfo.width, tiffInfo.height, tiffInfo.stripOffsets.size(),
        tiffInfo.rowsPerStrip, parseSec))
OpmFastClijPoc.log(String.format('Fast TIFF reader threads: %d', FAST_TIFF_THREADS))

long tRead = OpmFastClijPoc.now()
short[][] rawVolume = FastTiffReader.readPixelsParallel(inputFile, tiffInfo, FAST_TIFF_THREADS)
double readSec = OpmFastClijPoc.secondsSince(tRead)
OpmFastClijPoc.log(String.format('Fast TIFF read+convert finished in %.3f s', readSec))

long tWrap = OpmFastClijPoc.now()
ImagePlus rawImp = OpmFastClijPoc.wrapShortVolume('OPM fast TIFF raw volume', rawVolume, tiffInfo.width, tiffInfo.height)
double wrapSec = OpmFastClijPoc.secondsSince(tWrap)
OpmFastClijPoc.log(String.format('Wrapped fast TIFF arrays as ImagePlus in %.3f s', wrapSec))

long[] rawDims = [tiffInfo.width as long, tiffInfo.height as long, tiffInfo.stripOffsets.size() as long] as long[]
double[][] forwardMatrix = OpmFastClijPoc.deskewMatrix(
        Z_STEP_UM, XY_PIXEL_SIZE_UM, OPM_ANGLE_DEG, tiffInfo.height)
long[] outputDims = OpmFastClijPoc.transformedDimensions(rawDims, forwardMatrix)
double[][] centeredForwardMatrix = OpmFastClijPoc.autoCenter(rawDims, forwardMatrix)
AffineTransform3D inverseTransform = OpmFastClijPoc.toInverseAffineTransform3D(centeredForwardMatrix)
OpmFastClijPoc.log(String.format(
        'Deskewed GPU volume geometry: width=%d, height=%d, depth=%d',
        outputDims[0], outputDims[1], outputDims[2]))

CLIJ2 clij2 = null
ClearCLBuffer rawGpu = null
ClearCLBuffer deskewGpu = null
ClearCLBuffer mipXYGpu = null
ClearCLBuffer mipYZGpu = null
ClearCLBuffer mipXZGpu = null

try {
    long tGpuInit = OpmFastClijPoc.now()
    clij2 = OpmFastClijPoc.getClij2(GPU_NAME_HINT)
    clij2.clear()
    OpmFastClijPoc.log(String.format('CLIJ2 initialized in %.3f s', OpmFastClijPoc.secondsSince(tGpuInit)))
    OpmFastClijPoc.log(clij2.reportMemory())

    long tUpload = OpmFastClijPoc.now()
    rawGpu = clij2.push(rawImp)
    double uploadSec = OpmFastClijPoc.secondsSince(tUpload)
    OpmFastClijPoc.log(String.format('Uploaded fast-read stack to GPU in %.3f s', uploadSec))

    if (CLOSE_RAW_AFTER_UPLOAD) {
        long tCloseRaw = OpmFastClijPoc.now()
        rawImp.changes = false
        rawImp.close()
        rawImp.flush()
        rawImp = null
        rawVolume = null
        System.gc()
        OpmFastClijPoc.log(String.format(
                'Released raw host volume after upload in %.3f s',
                OpmFastClijPoc.secondsSince(tCloseRaw)))
    }

    long tAlloc = OpmFastClijPoc.now()
    deskewGpu = clij2.create(outputDims, rawGpu.getNativeType())
    mipXYGpu = clij2.create([outputDims[0], outputDims[1]] as long[], rawGpu.getNativeType())
    mipYZGpu = clij2.create([outputDims[1], outputDims[2]] as long[], rawGpu.getNativeType())
    mipXZGpu = clij2.create([outputDims[0], outputDims[2]] as long[], rawGpu.getNativeType())
    OpmFastClijPoc.log(String.format(
            'Allocated deskew volume and three MIPs on GPU in %.3f s',
            OpmFastClijPoc.secondsSince(tAlloc)))

    long tDeskew = OpmFastClijPoc.now()
    clij2.affineTransform3D(rawGpu, deskewGpu, inverseTransform)
    double deskewSec = OpmFastClijPoc.secondsSince(tDeskew)
    OpmFastClijPoc.log(String.format('GPU affine deskew finished in %.3f s', deskewSec))
    clij2.release(rawGpu)
    rawGpu = null

    long tMip = OpmFastClijPoc.now()
    clij2.maximumZProjection(deskewGpu, mipXYGpu)
    clij2.maximumXProjection(deskewGpu, mipYZGpu)
    clij2.maximumYProjection(deskewGpu, mipXZGpu)
    double mipSec = OpmFastClijPoc.secondsSince(tMip)
    OpmFastClijPoc.log(String.format('GPU max projections XY/YZ/XZ finished in %.3f s', mipSec))

    long tPullMips = OpmFastClijPoc.now()
    ImagePlus mipXY = clij2.pull(mipXYGpu)
    ImagePlus mipYZ = clij2.pull(mipYZGpu)
    ImagePlus mipXZ = clij2.pull(mipXZGpu)
    mipXY.setTitle('OPM fast TIFF CLIJ MIP XY')
    mipYZ.setTitle('OPM fast TIFF CLIJ MIP YZ')
    mipXZ.setTitle('OPM fast TIFF CLIJ MIP XZ')
    OpmFastClijPoc.log(String.format(
            'Pulled three MIPs from GPU in %.3f s',
            OpmFastClijPoc.secondsSince(tPullMips)))

    long tSaveMips = OpmFastClijPoc.now()
    if (DISPLAY_MIPS) {
        mipXY.show()
        mipYZ.show()
        mipXZ.show()
    }
    if (SAVE_MIPS) {
        OpmFastClijPoc.saveTiff(mipXY, new File(OUTPUT_DIR, 'fast_clij_mip_xy.tif'))
        OpmFastClijPoc.saveTiff(mipYZ, new File(OUTPUT_DIR, 'fast_clij_mip_yz.tif'))
        OpmFastClijPoc.saveTiff(mipXZ, new File(OUTPUT_DIR, 'fast_clij_mip_xz.tif'))
    }
    double saveMipsSec = OpmFastClijPoc.secondsSince(tSaveMips)
    OpmFastClijPoc.log(String.format('Display/save MIPs finished in %.3f s', saveMipsSec))
    OpmFastClijPoc.log(String.format(
            'Live-QC elapsed time through saved MIPs: %.3f s',
            OpmFastClijPoc.secondsSince(allStart)))

    if (SAVE_DESKEW_VOLUME) {
        double estimatedDeskewGiB = outputDims[0] * (double) outputDims[1] * (double) outputDims[2] * 2.0d / 1024.0d / 1024.0d / 1024.0d
        OpmFastClijPoc.log(String.format(
                'Full deskewed volume output is estimated at %.3f GiB before TIFF metadata.',
                estimatedDeskewGiB))

        long tPullVolume = OpmFastClijPoc.now()
        ImagePlus deskewImp = clij2.pull(deskewGpu)
        deskewImp.setTitle('OPM fast TIFF CLIJ deskewed volume')
        double pullVolumeSec = OpmFastClijPoc.secondsSince(tPullVolume)
        OpmFastClijPoc.log(String.format('Pulled deskewed volume from GPU in %.3f s', pullVolumeSec))

        long tSaveVolume = OpmFastClijPoc.now()
        OpmFastClijPoc.saveTiff(deskewImp, new File(OUTPUT_DIR, 'fast_clij_deskew_volume.tif'))
        double saveVolumeSec = OpmFastClijPoc.secondsSince(tSaveVolume)
        OpmFastClijPoc.log(String.format('Saved deskewed TIFF stack in %.3f s', saveVolumeSec))
        OpmFastClijPoc.log(String.format(
                'Full-output elapsed time through saved deskewed TIFF stack: %.3f s',
                OpmFastClijPoc.secondsSince(allStart)))

        deskewImp.changes = false
        deskewImp.close()
        deskewImp.flush()
        System.gc()
    }

    OpmFastClijPoc.log(String.format(
            'Summary components: parse %.3f, read %.3f, wrap %.3f, upload %.3f, deskew %.3f, mips %.3f, saveMips %.3f s',
            parseSec, readSec, wrapSec, uploadSec, deskewSec, mipSec, saveMipsSec))
    OpmFastClijPoc.log(clij2.reportMemory())
} finally {
    if (rawImp != null) {
        rawImp.changes = false
        rawImp.close()
        rawImp.flush()
    }
    rawVolume = null
    if (clij2 != null) {
        if (rawGpu != null) clij2.release(rawGpu)
        if (deskewGpu != null) clij2.release(deskewGpu)
        if (mipXYGpu != null) clij2.release(mipXYGpu)
        if (mipYZGpu != null) clij2.release(mipYZGpu)
        if (mipXZGpu != null) clij2.release(mipXZGpu)
        clij2.clear()
    }
}

OpmFastClijPoc.log(String.format('Total elapsed time: %.3f s', OpmFastClijPoc.secondsSince(allStart)))
OpmFastClijPoc.log('Done.')
