// OPM fast TIFF + CLIJ batch comparison POC for Fiji/ImageJ Groovy.
//
// Pipeline per time point:
//   fast targeted raw TIFF read -> ImageStack wrapper -> CLIJ upload ->
//   GPU affine deskew -> GPU MIPs -> append MIP movies -> save deskew output
//
// Deskew output modes:
//   1. TIFF stack per time point
//   2. Single-resolution OME-Zarr v0.4 / Zarr v2 array: T,C,Z,Y,X
//
// Notes:
//   - OME-Zarr writer is intentionally minimal: no pyramid, no compression.
//   - The custom TIFF reader supports the narrow fast path:
//     uncompressed 16-bit grayscale TIFF/BigTIFF with strips.
//   - Run TIFF and OME-Zarr modes separately for clean timing comparisons.

import groovy.json.JsonOutput

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
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import java.util.concurrent.Future

// -------------------------------------------------------------------------
// Configuration
// -------------------------------------------------------------------------

final String INPUT_DIR =
        'D:/Workspace/Microscopes/OPM/test_&_simulation/deskew_live/mitosis-test-timelapse_0'

final String OUTPUT_DIR =
        'D:/Workspace/Microscopes/OPM/test_&_simulation/deskew_live/mitosis-test-timelapse_0/poc_fast_tiff_clij_batch_compare'

final String FILE_NAME_CONTAINS = 'Channel0001'
final String FILE_EXTENSION = '.tiff'

final double XY_PIXEL_SIZE_UM = 0.116
final double Z_STEP_UM = 0.265
final double OPM_ANGLE_DEG = 25.0

// 0 means all matching files.
final int MAX_FILES = 0

// Run TIFF and OME-Zarr in separate Fiji sessions for the cleanest timing.
final boolean SAVE_DESKEW_TIFF = true
final boolean SAVE_DESKEW_OME_ZARR = false

final boolean SAVE_INDIVIDUAL_MIPS = true
final boolean SAVE_MIP_MOVIES = true
final boolean DISPLAY_MIP_MOVIES = true
final boolean CLOSE_RAW_AFTER_UPLOAD = true

// OME-Zarr chunking for array shape [T,C,Z,Y,X].
final int ZARR_CHUNK_Z = 32
final int ZARR_CHUNK_Y = 256
final int ZARR_CHUNK_X = 256

final String GPU_NAME_HINT = 'RTX'

// 1 keeps the old single-thread reader. 4-8 is usually the useful range on NVMe.
final int FAST_TIFF_THREADS = 8

// -------------------------------------------------------------------------
// Shared utilities
// -------------------------------------------------------------------------

class BatchPoc {
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

    static String baseName(File file) {
        String name = file.name
        int dot = name.lastIndexOf('.')
        return dot > 0 ? name.substring(0, dot) : name
    }
}

// -------------------------------------------------------------------------
// Fast targeted TIFF reader
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
            if (n < 0) throw new EOFException('Unexpected end of TIFF file.')
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
            if (n < 0) throw new EOFException('Unexpected end of TIFF file while reading pixels.')
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

    static long[] readValues(FileChannel channel, ByteOrder order, int type, long count, long valueOffset, byte[] embedded) {
        if (count > Integer.MAX_VALUE) throw new RuntimeException('TIFF field count too large: ' + count)
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
                    throw new RuntimeException('Unsupported numeric TIFF field type: ' + type)
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
            if (bigTiff) valueOffset = entries.getLong()
            else valueOffset = uInt(entries)
            entries.position(valuePosition)
            entries.get(embedded)

            if (tag == TAG_IMAGE_WIDTH || tag == TAG_IMAGE_LENGTH ||
                    tag == TAG_BITS_PER_SAMPLE || tag == TAG_COMPRESSION ||
                    tag == TAG_STRIP_OFFSETS || tag == TAG_SAMPLES_PER_PIXEL ||
                    tag == TAG_ROWS_PER_STRIP || tag == TAG_STRIP_BYTE_COUNTS) {
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
            if (b0 == 0x49 && b1 == 0x49) order = ByteOrder.LITTLE_ENDIAN
            else if (b0 == 0x4d && b1 == 0x4d) order = ByteOrder.BIG_ENDIAN
            else throw new RuntimeException('Not a TIFF header.')

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
                if (offsetSize != 8 || zero != 0) throw new RuntimeException('Unsupported BigTIFF header.')
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
                if (width <= 0 || height <= 0) break

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

            if (info.stripOffsets.isEmpty()) throw new RuntimeException('No image planes found in TIFF.')
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
                if (offsets.length != counts.length) throw new RuntimeException('Strip offset/count mismatch at plane ' + z)

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
// Minimal OME-Zarr v0.4 / Zarr v2 writer
// -------------------------------------------------------------------------

class MinimalOmeZarrWriter {
    File root
    File arrayDir
    int sizeT
    int sizeZ
    int sizeY
    int sizeX
    int chunkZ
    int chunkY
    int chunkX
    double pixelSizeUm

    MinimalOmeZarrWriter(File root, int sizeT, int sizeZ, int sizeY, int sizeX,
                         int chunkZ, int chunkY, int chunkX, double pixelSizeUm) {
        this.root = root
        this.arrayDir = new File(root, '0')
        this.sizeT = sizeT
        this.sizeZ = sizeZ
        this.sizeY = sizeY
        this.sizeX = sizeX
        this.chunkZ = chunkZ
        this.chunkY = chunkY
        this.chunkX = chunkX
        this.pixelSizeUm = pixelSizeUm
    }

    void initialize() {
        root.mkdirs()
        arrayDir.mkdirs()

        writeJson(new File(root, '.zgroup'), [zarr_format: 2])
        writeJson(new File(root, '.zattrs'), [
                multiscales: [[
                        version : '0.4',
                        name    : 'OPM deskewed',
                        axes    : [
                                [name: 't', type: 'time'],
                                [name: 'c', type: 'channel'],
                                [name: 'z', type: 'space', unit: 'micrometer'],
                                [name: 'y', type: 'space', unit: 'micrometer'],
                                [name: 'x', type: 'space', unit: 'micrometer']
                        ],
                        datasets: [[
                                path                    : '0',
                                coordinateTransformations: [[
                                        type : 'scale',
                                        scale: [1.0d, 1.0d, pixelSizeUm, pixelSizeUm, pixelSizeUm]
                                ]]
                        ]]
                ]],
                omero      : [
                        channels: [[
                                label : 'Channel0001',
                                color : 'FFFFFF',
                                active: true,
                                window: [start: 0, end: 65535]
                        ]]
                ]
        ])
        writeJson(new File(arrayDir, '.zarray'), [
                zarr_format        : 2,
                shape              : [sizeT, 1, sizeZ, sizeY, sizeX],
                chunks             : [1, 1, chunkZ, chunkY, chunkX],
                dtype              : '<u2',
                compressor         : null,
                fill_value         : 0,
                order              : 'C',
                filters            : null,
                dimension_separator: '.'
        ])
        writeJson(new File(arrayDir, '.zattrs'), [
                _ARRAY_DIMENSIONS: ['t', 'c', 'z', 'y', 'x']
        ])
    }

    static void writeJson(File file, Object value) {
        file.parentFile?.mkdirs()
        file.write(JsonOutput.prettyPrint(JsonOutput.toJson(value)), 'UTF-8')
    }

    void writeTimepoint(ImagePlus imp, int tIndex) {
        if (imp.getWidth() != sizeX || imp.getHeight() != sizeY || imp.getStackSize() != sizeZ) {
            throw new RuntimeException(String.format(
                    'OME-Zarr writer expected %dx%dx%d, got %dx%dx%d',
                    sizeX, sizeY, sizeZ, imp.getWidth(), imp.getHeight(), imp.getStackSize()))
        }

        ImageStack stack = imp.getStack()
        for (int z0 = 0; z0 < sizeZ; z0 += chunkZ) {
            int zc = Math.min(chunkZ, sizeZ - z0)
            for (int y0 = 0; y0 < sizeY; y0 += chunkY) {
                int yc = Math.min(chunkY, sizeY - y0)
                for (int x0 = 0; x0 < sizeX; x0 += chunkX) {
                    int xc = Math.min(chunkX, sizeX - x0)
                    byte[] bytes = new byte[chunkZ * chunkY * chunkX * 2]
                    ByteBuffer out = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

                    for (int dz = 0; dz < chunkZ; dz++) {
                        if (dz >= zc) {
                            out.position((dz + 1) * chunkY * chunkX * 2)
                            continue
                        }
                        short[] plane = (short[]) stack.getProcessor(z0 + dz + 1).getPixels()
                        for (int dy = 0; dy < chunkY; dy++) {
                            if (dy < yc) {
                                int row = (y0 + dy) * sizeX + x0
                                for (int dx = 0; dx < chunkX; dx++) {
                                    out.putShort(dx < xc ? plane[row + dx] : (short) 0)
                                }
                            } else {
                                out.position(out.position() + chunkX * 2)
                            }
                        }
                    }

                    File chunk = new File(arrayDir, String.format('%d.0.%d.%d.%d',
                            tIndex, (int) (z0 / chunkZ), (int) (y0 / chunkY), (int) (x0 / chunkX)))
                    Files.write(chunk.toPath(), bytes)
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// Batch pipeline
// -------------------------------------------------------------------------

long allStart = BatchPoc.now()
File inputDir = new File(INPUT_DIR)
File outputDir = new File(OUTPUT_DIR)
File dsTiffDir = new File(outputDir, 'DS_tiff')
File mipDir = new File(outputDir, 'MIPs')
File movieDir = new File(outputDir, 'MIP_movies')
File zarrRoot = new File(outputDir, 'DS_ome_zarr/deskew.ome.zarr')
dsTiffDir.mkdirs()
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

BatchPoc.log('')
BatchPoc.log('OPM fast TIFF + CLIJ batch comparison POC')
BatchPoc.log('Input folder: ' + INPUT_DIR)
BatchPoc.log('Output folder: ' + OUTPUT_DIR)
BatchPoc.log('Files: ' + files.size())
BatchPoc.log(String.format(
        'Save modes: TIFF=%b, OME-Zarr=%b',
        SAVE_DESKEW_TIFF, SAVE_DESKEW_OME_ZARR))
BatchPoc.log(String.format(
        'Parameters: angle=%.4f deg, xy=%.6f um, dz=%.6f um',
        OPM_ANGLE_DEG, XY_PIXEL_SIZE_UM, Z_STEP_UM))
BatchPoc.log(String.format('Fast TIFF reader threads: %d', FAST_TIFF_THREADS))

FastTiffInfo firstInfo = FastTiffReader.parse(files[0])
long[] firstRawDims = [firstInfo.width as long, firstInfo.height as long, firstInfo.stripOffsets.size() as long] as long[]
double[][] firstForwardMatrix = BatchPoc.deskewMatrix(Z_STEP_UM, XY_PIXEL_SIZE_UM, OPM_ANGLE_DEG, firstInfo.height)
long[] firstOutputDims = BatchPoc.transformedDimensions(firstRawDims, firstForwardMatrix)

MinimalOmeZarrWriter zarrWriter = null
if (SAVE_DESKEW_OME_ZARR) {
    zarrWriter = new MinimalOmeZarrWriter(
            zarrRoot,
            files.size(),
            (int) firstOutputDims[2],
            (int) firstOutputDims[1],
            (int) firstOutputDims[0],
            ZARR_CHUNK_Z,
            ZARR_CHUNK_Y,
            ZARR_CHUNK_X,
            XY_PIXEL_SIZE_UM)
    zarrWriter.initialize()
    BatchPoc.log('Initialized OME-Zarr output: ' + zarrRoot.absolutePath)
}

CLIJ2 clij2 = BatchPoc.getClij2(GPU_NAME_HINT)
clij2.clear()
BatchPoc.log(clij2.reportMemory())

ImageStack xyMovie = null
ImageStack yzMovie = null
ImageStack xzMovie = null
ImagePlus xyMovieImp = null
ImagePlus yzMovieImp = null
ImagePlus xzMovieImp = null

double sumParse = 0d
double sumRead = 0d
double sumWrap = 0d
double sumUpload = 0d
double sumDeskew = 0d
double sumMip = 0d
double sumPullMips = 0d
double sumSaveMips = 0d
double sumPullVolume = 0d
double sumSaveTiff = 0d
double sumSaveZarr = 0d
double sumPerFile = 0d

try {
    int index = 0
    for (File file : files) {
        index++
        long fileStart = BatchPoc.now()
        String name = BatchPoc.baseName(file)
        BatchPoc.log('')
        BatchPoc.log(String.format('Processing %d/%d: %s', index, files.size(), file.name))

        short[][] rawVolume = null
        ImagePlus rawImp = null
        ImagePlus deskewImp = null
        ClearCLBuffer rawGpu = null
        ClearCLBuffer deskewGpu = null
        ClearCLBuffer mipXYGpu = null
        ClearCLBuffer mipYZGpu = null
        ClearCLBuffer mipXZGpu = null

        try {
            long tParse = BatchPoc.now()
            FastTiffInfo info = FastTiffReader.parse(file)
            double parseSec = BatchPoc.secondsSince(tParse)
            sumParse += parseSec

            long tRead = BatchPoc.now()
            rawVolume = FastTiffReader.readPixelsParallel(file, info, FAST_TIFF_THREADS)
            double readSec = BatchPoc.secondsSince(tRead)
            sumRead += readSec

            long tWrap = BatchPoc.now()
            rawImp = BatchPoc.wrapShortVolume(name + '-raw-fast', rawVolume, info.width, info.height)
            double wrapSec = BatchPoc.secondsSince(tWrap)
            sumWrap += wrapSec

            long[] rawDims = [info.width as long, info.height as long, info.stripOffsets.size() as long] as long[]
            double[][] forwardMatrix = BatchPoc.deskewMatrix(Z_STEP_UM, XY_PIXEL_SIZE_UM, OPM_ANGLE_DEG, info.height)
            long[] outputDims = BatchPoc.transformedDimensions(rawDims, forwardMatrix)
            double[][] centeredForwardMatrix = BatchPoc.autoCenter(rawDims, forwardMatrix)
            AffineTransform3D inverseTransform = BatchPoc.toInverseAffineTransform3D(centeredForwardMatrix)

            BatchPoc.log(String.format(
                    'Raw %dx%dx%d -> deskew %dx%dx%d; parse %.3f, read %.3f, wrap %.3f s',
                    info.width, info.height, info.stripOffsets.size(),
                    outputDims[0], outputDims[1], outputDims[2],
                    parseSec, readSec, wrapSec))

            long tUpload = BatchPoc.now()
            rawGpu = clij2.push(rawImp)
            double uploadSec = BatchPoc.secondsSince(tUpload)
            sumUpload += uploadSec

            if (CLOSE_RAW_AFTER_UPLOAD) {
                rawImp.changes = false
                rawImp.close()
                rawImp.flush()
                rawImp = null
                rawVolume = null
                System.gc()
            }

            deskewGpu = clij2.create(outputDims, rawGpu.getNativeType())
            mipXYGpu = clij2.create([outputDims[0], outputDims[1]] as long[], rawGpu.getNativeType())
            mipYZGpu = clij2.create([outputDims[1], outputDims[2]] as long[], rawGpu.getNativeType())
            mipXZGpu = clij2.create([outputDims[0], outputDims[2]] as long[], rawGpu.getNativeType())

            long tDeskew = BatchPoc.now()
            clij2.affineTransform3D(rawGpu, deskewGpu, inverseTransform)
            double deskewSec = BatchPoc.secondsSince(tDeskew)
            sumDeskew += deskewSec
            clij2.release(rawGpu)
            rawGpu = null

            long tMip = BatchPoc.now()
            clij2.maximumZProjection(deskewGpu, mipXYGpu)
            clij2.maximumXProjection(deskewGpu, mipYZGpu)
            clij2.maximumYProjection(deskewGpu, mipXZGpu)
            double mipSec = BatchPoc.secondsSince(tMip)
            sumMip += mipSec

            long tPullMips = BatchPoc.now()
            ImagePlus mipXY = clij2.pull(mipXYGpu)
            ImagePlus mipYZ = clij2.pull(mipYZGpu)
            ImagePlus mipXZ = clij2.pull(mipXZGpu)
            mipXY.setTitle(name + '-MIP-XY')
            mipYZ.setTitle(name + '-MIP-YZ')
            mipXZ.setTitle(name + '-MIP-XZ')
            double pullMipsSec = BatchPoc.secondsSince(tPullMips)
            sumPullMips += pullMipsSec

            if (xyMovie == null) {
                xyMovie = new ImageStack(mipXY.getWidth(), mipXY.getHeight())
                yzMovie = new ImageStack(mipYZ.getWidth(), mipYZ.getHeight())
                xzMovie = new ImageStack(mipXZ.getWidth(), mipXZ.getHeight())

                xyMovie.addSlice(name, mipXY.getProcessor().duplicate())
                yzMovie.addSlice(name, mipYZ.getProcessor().duplicate())
                xzMovie.addSlice(name, mipXZ.getProcessor().duplicate())

                xyMovieImp = new ImagePlus('OPM XY MIP movie', xyMovie)
                yzMovieImp = new ImagePlus('OPM YZ MIP movie', yzMovie)
                xzMovieImp = new ImagePlus('OPM XZ MIP movie', xzMovie)
                if (DISPLAY_MIP_MOVIES) {
                    xyMovieImp.show()
                    yzMovieImp.show()
                    xzMovieImp.show()
                }
            } else {
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
            }

            long tSaveMips = BatchPoc.now()
            if (SAVE_INDIVIDUAL_MIPS) {
                BatchPoc.saveTiff(mipXY, new File(mipDir, name + '-MIP-XY.tif'))
                BatchPoc.saveTiff(mipYZ, new File(mipDir, name + '-MIP-YZ.tif'))
                BatchPoc.saveTiff(mipXZ, new File(mipDir, name + '-MIP-XZ.tif'))
            }
            double saveMipsSec = BatchPoc.secondsSince(tSaveMips)
            sumSaveMips += saveMipsSec

            mipXY.changes = false; mipXY.close(); mipXY.flush()
            mipYZ.changes = false; mipYZ.close(); mipYZ.flush()
            mipXZ.changes = false; mipXZ.close(); mipXZ.flush()

            if (SAVE_DESKEW_TIFF || SAVE_DESKEW_OME_ZARR) {
                long tPullVolume = BatchPoc.now()
                deskewImp = clij2.pull(deskewGpu)
                deskewImp.setTitle(name + '-deskew')
                double pullVolumeSec = BatchPoc.secondsSince(tPullVolume)
                sumPullVolume += pullVolumeSec

                double saveTiffSec = 0d
                if (SAVE_DESKEW_TIFF) {
                    long tSaveTiff = BatchPoc.now()
                    BatchPoc.saveTiff(deskewImp, new File(dsTiffDir, name + '-DS.tif'))
                    saveTiffSec = BatchPoc.secondsSince(tSaveTiff)
                    sumSaveTiff += saveTiffSec
                }

                double saveZarrSec = 0d
                if (SAVE_DESKEW_OME_ZARR) {
                    long tSaveZarr = BatchPoc.now()
                    zarrWriter.writeTimepoint(deskewImp, index - 1)
                    saveZarrSec = BatchPoc.secondsSince(tSaveZarr)
                    sumSaveZarr += saveZarrSec
                }

                deskewImp.changes = false
                deskewImp.close()
                deskewImp.flush()
                deskewImp = null

                BatchPoc.log(String.format(
                        'Output save timing: pullDS %.3f, saveTIFF %.3f, saveZarr %.3f s',
                        pullVolumeSec, saveTiffSec, saveZarrSec))
            }

            double fileSec = BatchPoc.secondsSince(fileStart)
            sumPerFile += fileSec
            BatchPoc.log(String.format(
                    'Timing: parse %.3f, read %.3f, wrap %.3f, upload %.3f, deskew %.3f, mips %.3f, pullMips %.3f, saveMips %.3f, total %.3f s',
                    parseSec, readSec, wrapSec, uploadSec, deskewSec, mipSec, pullMipsSec, saveMipsSec, fileSec))
            IJ.showProgress(index, files.size())
        } finally {
            if (rawImp != null) {
                rawImp.changes = false
                rawImp.close()
                rawImp.flush()
            }
            rawVolume = null
            if (deskewImp != null) {
                deskewImp.changes = false
                deskewImp.close()
                deskewImp.flush()
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
        long tMovies = BatchPoc.now()
        BatchPoc.saveTiff(xyMovieImp, new File(movieDir, 'MIP-movie-XY.tif'))
        BatchPoc.saveTiff(yzMovieImp, new File(movieDir, 'MIP-movie-YZ.tif'))
        BatchPoc.saveTiff(xzMovieImp, new File(movieDir, 'MIP-movie-XZ.tif'))
        BatchPoc.log(String.format('Saved MIP movies in %.3f s', BatchPoc.secondsSince(tMovies)))
    }
} finally {
    if (clij2 != null) {
        clij2.clear()
    }
}

double totalSec = BatchPoc.secondsSince(allStart)
int n = files.size()
BatchPoc.log('')
BatchPoc.log('Batch summary')
BatchPoc.log(String.format('Files processed: %d', n))
BatchPoc.log(String.format('Total elapsed: %.3f s', totalSec))
BatchPoc.log(String.format('Mean per file: %.3f s', sumPerFile / n))
BatchPoc.log(String.format(
        'Mean timing: parse %.3f, read %.3f, wrap %.3f, upload %.3f, deskew %.3f, mips %.3f, pullMips %.3f, saveMips %.3f, pullDS %.3f, saveTIFF %.3f, saveZarr %.3f s',
        sumParse / n, sumRead / n, sumWrap / n, sumUpload / n, sumDeskew / n, sumMip / n,
        sumPullMips / n, sumSaveMips / n, sumPullVolume / n, sumSaveTiff / n, sumSaveZarr / n))
BatchPoc.log('Done.')
