// OPM fast TIFF + tiled CLIJ + OME-Zarr proof-of-concept for Fiji/ImageJ Groovy.
//
// Purpose:
//   Test tiled GPU deskewing to avoid the full 3.9 GB deskewed GPU buffer.
//
// Pipeline per time point:
//   fast targeted raw TIFF read -> ImageStack wrapper -> upload raw once ->
//   for each output Z slab:
//     CLIJ affine deskew into slab buffer -> GPU slab MIPs -> pull slab ->
//     write slab as OME-Zarr chunks -> release slab
//   pull final XY MIP, keep XY/YZ/XZ MIP movies in Fiji
//
// Output:
//   Single-resolution OME-Zarr v0.4 / Zarr v2 array: T,C,Z,Y,X, uint16,
//   uncompressed, no pyramid.

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
        'D:/Workspace/Microscopes/OPM/test_&_simulation/deskew_live/mitosis-test-timelapse_0/poc_fast_tiff_clij_tiled_zarr'

final String FILE_NAME_CONTAINS = 'Channel0001'
final String FILE_EXTENSION = '.tiff'

final double XY_PIXEL_SIZE_UM = 0.116
final double Z_STEP_UM = 0.265
final double OPM_ANGLE_DEG = 25.0

// 0 means all matching files.
final int MAX_FILES = 0

// Use the same value as ZARR_CHUNK_Z so slabs map cleanly to Zarr chunks.
final int OUTPUT_SLAB_Z = 64
final int ZARR_CHUNK_Z = 64
final int ZARR_CHUNK_Y = 1024
final int ZARR_CHUNK_X = 1024

final boolean SAVE_INDIVIDUAL_MIPS = true
final boolean SAVE_MIP_MOVIES = true
final boolean DISPLAY_MIP_MOVIES = true
final boolean CLOSE_RAW_AFTER_UPLOAD = true

final String GPU_NAME_HINT = 'RTX'

// 1 keeps the old single-thread reader. 4-8 is usually the useful range on NVMe.
final int FAST_TIFF_THREADS = 8

// -------------------------------------------------------------------------
// Shared utilities
// -------------------------------------------------------------------------

class TiledPoc {
    static long now() { System.nanoTime() }
    static double secondsSince(long t0) { (System.nanoTime() - t0) / 1.0e9d }
    static void log(String message) { IJ.log(message); println(message) }
    static double cosDeg(double degrees) { Math.cos(Math.toRadians(degrees)) }
    static double sinDeg(double degrees) { Math.sin(Math.toRadians(degrees)) }

    static double[][] identity4x4() {
        [
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
        double w = dims[0], h = dims[1], d = dims[2]
        double m00 = matrix[0][0]; double m10 = matrix[1][0]; double m20 = matrix[2][0]
        double m01 = matrix[0][1]; double m11 = matrix[1][1]; double m21 = matrix[2][1]
        double m02 = matrix[0][2]; double m12 = matrix[1][2]; double m22 = matrix[2][2]
        double m03 = matrix[0][3]; double m13 = matrix[1][3]; double m23 = matrix[2][3]

        double[] xs = [m03, w*m00+h*m01+d*m02+m03, w*m00+m03, h*m01+d*m02+m03,
                       h*m01+m03, w*m00+d*m02+m03, d*m02+m03, w*m00+h*m01+m03] as double[]
        double[] ys = [m13, w*m10+h*m11+d*m12+m13, w*m10+m13, h*m11+d*m12+m13,
                       h*m11+m13, w*m10+d*m12+m13, d*m12+m13, w*m10+h*m11+m13] as double[]
        double[] zs = [m23, w*m20+h*m21+d*m22+m23, w*m20+m23, h*m21+d*m22+m23,
                       h*m21+m23, w*m20+d*m22+m23, d*m22+m23, w*m20+h*m21+m23] as double[]
        [(long)Math.ceil(xs.max()-xs.min()), (long)Math.ceil(ys.max()-ys.min()), (long)Math.ceil(zs.max()-zs.min())] as long[]
    }

    static double[][] autoCenter(long[] dims, double[][] matrix) {
        double w = dims[0], h = dims[1], d = dims[2]
        double m00 = matrix[0][0]; double m10 = matrix[1][0]; double m20 = matrix[2][0]
        double m01 = matrix[0][1]; double m11 = matrix[1][1]; double m21 = matrix[2][1]
        double m02 = matrix[0][2]; double m12 = matrix[1][2]; double m22 = matrix[2][2]
        double m03 = matrix[0][3]; double m13 = matrix[1][3]; double m23 = matrix[2][3]
        double[] xs = [m03, w*m00+h*m01+d*m02+m03, w*m00+m03, h*m01+d*m02+m03,
                       h*m01+m03, w*m00+d*m02+m03, d*m02+m03, w*m00+h*m01+m03] as double[]
        double[] ys = [m13, w*m10+h*m11+d*m12+m13, w*m10+m13, h*m11+d*m12+m13,
                       h*m11+m13, w*m10+d*m12+m13, d*m12+m13, w*m10+h*m11+m13] as double[]
        double[] zs = [m23, w*m20+h*m21+d*m22+m23, w*m20+m23, h*m21+d*m22+m23,
                       h*m21+m23, w*m20+d*m22+m23, d*m22+m23, w*m20+h*m21+m23] as double[]
        double[][] out = matrix.collect { row -> row.clone() } as double[][]
        double xMin = xs.min(), yMin = ys.min(), zMin = zs.min()
        if (xMin < 0) out[0][3] -= xMin
        if (yMin < 0) out[1][3] -= yMin
        if (zMin < 0) out[2][3] -= zMin
        return out
    }

    static AffineTransform3D toInverseAffineTransform3D(double[][] forwardMatrix) {
        AffineTransform3D transform = new AffineTransform3D()
        transform.set(forwardMatrix)
        transform.inverse()
    }

    static AffineTransform3D shiftedInverseForOutputZ(AffineTransform3D inverseTransform, int globalZOffset) {
        AffineTransform3D local = inverseTransform.copy()
        local.set(inverseTransform.get(0, 3) + inverseTransform.get(0, 2) * globalZOffset, 0, 3)
        local.set(inverseTransform.get(1, 3) + inverseTransform.get(1, 2) * globalZOffset, 1, 3)
        local.set(inverseTransform.get(2, 3) + inverseTransform.get(2, 2) * globalZOffset, 2, 3)
        return local
    }

    static ImagePlus wrapShortVolume(String title, short[][] volume, int width, int height) {
        ImageStack stack = new ImageStack(width, height)
        for (int z = 0; z < volume.length; z++) {
            stack.addSlice(null, new ShortProcessor(width, height, volume[z], null))
        }
        new ImagePlus(title, stack)
    }

    static ImagePlus makeShortImage(String title, int width, int height, short[] pixels) {
        new ImagePlus(title, new ShortProcessor(width, height, pixels, null))
    }

    static void saveTiff(ImagePlus imp, File outputFile) {
        outputFile.parentFile.mkdirs()
        FileSaver saver = new FileSaver(imp)
        if (imp.getStackSize() > 1) saver.saveAsTiffStack(outputFile.absolutePath)
        else saver.saveAsTiff(outputFile.absolutePath)
    }

    static CLIJ2 getClij2(String nameHint) {
        if (nameHint == null || nameHint.trim().isEmpty()) return CLIJ2.getInstance()
        try { return CLIJ2.getInstance(nameHint) }
        catch (Throwable ignored) {
            log("Could not select GPU hint '" + nameHint + "'; using CLIJ2 default device.")
            return CLIJ2.getInstance()
        }
    }

    static String baseName(File file) {
        String name = file.name
        int dot = name.lastIndexOf('.')
        dot > 0 ? name.substring(0, dot) : name
    }

    static void copySlabProjectionIntoFull(short[] full, int fullWidth, int zOffset, ImagePlus slabProjection) {
        int slabWidth = slabProjection.getWidth()
        int slabHeight = slabProjection.getHeight()
        short[] slabPixels = (short[]) slabProjection.getProcessor().getPixels()
        for (int row = 0; row < slabHeight; row++) {
            System.arraycopy(slabPixels, row * slabWidth, full, (zOffset + row) * fullWidth, slabWidth)
        }
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

    static class IfdResult { Map<Integer, long[]> tags; long nextOffset }

    static ByteBuffer readAt(FileChannel channel, long offset, int length, ByteOrder order) {
        ByteBuffer buffer = ByteBuffer.allocate(length)
        long filePosition = offset
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, filePosition)
            if (n < 0) throw new EOFException('Unexpected end of TIFF file.')
            filePosition += n
        }
        buffer.flip(); buffer.order(order); buffer
    }

    static void readFully(FileChannel channel, ByteBuffer buffer, long offset) {
        long filePosition = offset
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, filePosition)
            if (n < 0) throw new EOFException('Unexpected end of TIFF file while reading pixels.')
            filePosition += n
        }
    }

    static int uShort(ByteBuffer buffer) { buffer.getShort() & 0xffff }
    static long uInt(ByteBuffer buffer) { buffer.getInt() & 0xffffffffL }

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
            buffer = ByteBuffer.wrap(embedded, 0, embedded.length); buffer.order(order)
        } else {
            buffer = readAt(channel, valueOffset, bytes, order)
        }
        long[] out = new long[n]
        for (int i = 0; i < n; i++) {
            switch (type) {
                case TYPE_BYTE:
                case TYPE_UNDEFINED:
                    out[i] = buffer.get() & 0xffL; break
                case TYPE_SHORT:
                    out[i] = buffer.getShort() & 0xffffL; break
                case TYPE_LONG:
                    out[i] = buffer.getInt() & 0xffffffffL; break
                case TYPE_LONG8:
                case TYPE_IFD8:
                    out[i] = buffer.getLong(); break
                default:
                    throw new RuntimeException('Unsupported numeric TIFF field type: ' + type)
            }
        }
        out
    }

    static IfdResult readIfd(FileChannel channel, ByteOrder order, boolean bigTiff, long ifdOffset) {
        int countBytes = bigTiff ? 8 : 2
        ByteBuffer countBuffer = readAt(channel, ifdOffset, countBytes, order)
        long entryCount = bigTiff ? countBuffer.getLong() : uShort(countBuffer)
        if (entryCount < 0 || entryCount > 4096) throw new RuntimeException('Unexpected TIFF IFD entry count: ' + entryCount)

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
            if (tag == TAG_IMAGE_WIDTH || tag == TAG_IMAGE_LENGTH || tag == TAG_BITS_PER_SAMPLE ||
                    tag == TAG_COMPRESSION || tag == TAG_STRIP_OFFSETS || tag == TAG_SAMPLES_PER_PIXEL ||
                    tag == TAG_ROWS_PER_STRIP || tag == TAG_STRIP_BYTE_COUNTS) {
                tags.put(tag, readValues(channel, order, type, count, valueOffset, embedded))
            }
        }
        IfdResult result = new IfdResult()
        result.tags = tags
        result.nextOffset = bigTiff ? entries.getLong() : uInt(entries)
        result
    }

    static long firstOrDefault(Map<Integer, long[]> tags, int tag, long fallback) {
        long[] value = tags.get(tag)
        value == null || value.length == 0 ? fallback : value[0]
    }

    static FastTiffInfo parse(File file) {
        FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
        try {
            ByteBuffer header = ByteBuffer.allocate(16)
            readFully(channel, header, 0); header.flip()
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
                bigTiff = false; ifdOffset = header.getInt() & 0xffffffffL
            } else if (magic == 43) {
                bigTiff = true
                int offsetSize = header.getShort() & 0xffff
                int zero = header.getShort() & 0xffff
                if (offsetSize != 8 || zero != 0) throw new RuntimeException('Unsupported BigTIFF header.')
                ifdOffset = header.getLong()
            } else throw new RuntimeException('Unsupported TIFF magic number: ' + magic)

            FastTiffInfo info = new FastTiffInfo()
            info.bigTiff = bigTiff; info.byteOrder = order
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
                if (offsets == null || counts == null) throw new RuntimeException('Missing strip offsets/byte counts at IFD ' + planeIndex)
                if (planeIndex == 0) {
                    info.width = width; info.height = height; info.bitsPerSample = bits
                    info.compression = compression; info.samplesPerPixel = samples; info.rowsPerStrip = rowsPerStrip
                } else if (width != info.width || height != info.height || bits != info.bitsPerSample ||
                        compression != info.compression || samples != info.samplesPerPixel) {
                    throw new RuntimeException('TIFF plane layout changes at IFD ' + planeIndex)
                }
                info.stripOffsets.add(offsets); info.stripByteCounts.add(counts)
                planeIndex++; ifdOffset = ifd.nextOffset
            }
            if (info.stripOffsets.isEmpty()) throw new RuntimeException('No image planes found in TIFF.')
            if (info.bitsPerSample != 16 || info.samplesPerPixel != 1 || info.compression != 1) {
                throw new RuntimeException(String.format(
                        'Fast reader supports only uncompressed 16-bit grayscale. Found bits=%d samples=%d compression=%d',
                        info.bitsPerSample, info.samplesPerPixel, info.compression))
            }
            info
        } finally { channel.close() }
    }

    static short[][] readPixels(File file, FastTiffInfo info) {
        int w = info.width, h = info.height, d = info.stripOffsets.size()
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
            volume
        } finally { channel.close() }
    }

    static short[][] readPixelsParallel(File file, FastTiffInfo info, int numThreads) {
        if (numThreads <= 1) return readPixels(file, info)
        int w = info.width, h = info.height, d = info.stripOffsets.size()
        int planeBytes = Math.multiplyExact(w * h, 2)
        int rowBytes = Math.multiplyExact(w, 2)
        short[][] volume = new short[d][]
        int tasks = Math.max(1, Math.min(numThreads, d))
        int planesPerTask = (int)Math.ceil(d / (double)tasks)
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
                            if (offsets.length != counts.length) throw new RuntimeException('Strip offset/count mismatch at plane ' + z)
                            for (int s = 0; s < offsets.length; s++) {
                                int byteCount = (int)counts[s]
                                int dstOffset = (int)Math.min((long)s * info.rowsPerStrip * rowBytes, planeBytes)
                                ByteBuffer dst = ByteBuffer.wrap(rawPlane, dstOffset, byteCount)
                                readFully(channel, dst, offsets[s])
                            }
                            short[] pixels = new short[w * h]
                            ByteBuffer.wrap(rawPlane).order(info.byteOrder).asShortBuffer().get(pixels)
                            volume[z] = pixels
                        }
                    } finally { channel.close() }
                    return null
                } as java.util.concurrent.Callable))
            }
            for (Future f : futures) f.get()
        } finally {
            executor.shutdownNow()
        }
        volume
    }
}

// -------------------------------------------------------------------------
// Minimal OME-Zarr v0.4 / Zarr v2 writer
// -------------------------------------------------------------------------

class TiledOmeZarrWriter {
    File root
    File arrayDir
    int sizeT, sizeZ, sizeY, sizeX, chunkZ, chunkY, chunkX
    double pixelSizeUm

    TiledOmeZarrWriter(File root, int sizeT, int sizeZ, int sizeY, int sizeX,
                       int chunkZ, int chunkY, int chunkX, double pixelSizeUm) {
        this.root = root; this.arrayDir = new File(root, '0')
        this.sizeT = sizeT; this.sizeZ = sizeZ; this.sizeY = sizeY; this.sizeX = sizeX
        this.chunkZ = chunkZ; this.chunkY = chunkY; this.chunkX = chunkX; this.pixelSizeUm = pixelSizeUm
    }

    void initialize() {
        root.mkdirs(); arrayDir.mkdirs()
        writeJson(new File(root, '.zgroup'), [zarr_format: 2])
        writeJson(new File(root, '.zattrs'), [
                multiscales: [[
                        version : '0.4',
                        name    : 'OPM deskewed tiled',
                        axes    : [[name:'t', type:'time'], [name:'c', type:'channel'],
                                   [name:'z', type:'space', unit:'micrometer'],
                                   [name:'y', type:'space', unit:'micrometer'],
                                   [name:'x', type:'space', unit:'micrometer']],
                        datasets: [[path:'0', coordinateTransformations:[[type:'scale',
                                scale:[1.0d, 1.0d, pixelSizeUm, pixelSizeUm, pixelSizeUm]]]]]
                ]],
                omero: [channels: [[label:'Channel0001', color:'FFFFFF', active:true, window:[start:0, end:65535]]]]
        ])
        writeJson(new File(arrayDir, '.zarray'), [
                zarr_format: 2,
                shape: [sizeT, 1, sizeZ, sizeY, sizeX],
                chunks: [1, 1, chunkZ, chunkY, chunkX],
                dtype: '<u2',
                compressor: null,
                fill_value: 0,
                order: 'C',
                filters: null,
                dimension_separator: '.'
        ])
        writeJson(new File(arrayDir, '.zattrs'), [_ARRAY_DIMENSIONS: ['t','c','z','y','x']])
    }

    static void writeJson(File file, Object value) {
        file.parentFile?.mkdirs()
        file.write(JsonOutput.prettyPrint(JsonOutput.toJson(value)), 'UTF-8')
    }

    void writeAlignedZSlab(ImagePlus slabImp, int tIndex, int zOffset) {
        if (zOffset % chunkZ != 0) throw new RuntimeException('zOffset must align to chunkZ.')
        int slabDepth = slabImp.getStackSize()
        if (slabImp.getWidth() != sizeX || slabImp.getHeight() != sizeY) {
            throw new RuntimeException('Slab XY size does not match OME-Zarr target.')
        }
        ImageStack stack = slabImp.getStack()
        for (int localZ0 = 0; localZ0 < slabDepth; localZ0 += chunkZ) {
            int globalZ0 = zOffset + localZ0
            int zc = Math.min(chunkZ, Math.min(sizeZ - globalZ0, slabDepth - localZ0))
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
                        short[] plane = (short[]) stack.getProcessor(localZ0 + dz + 1).getPixels()
                        for (int dy = 0; dy < chunkY; dy++) {
                            if (dy < yc) {
                                int row = (y0 + dy) * sizeX + x0
                                for (int dx = 0; dx < chunkX; dx++) out.putShort(dx < xc ? plane[row + dx] : (short)0)
                            } else {
                                out.position(out.position() + chunkX * 2)
                            }
                        }
                    }
                    File chunk = new File(arrayDir, String.format('%d.0.%d.%d.%d',
                            tIndex, (int)(globalZ0 / chunkZ), (int)(y0 / chunkY), (int)(x0 / chunkX)))
                    Files.write(chunk.toPath(), bytes)
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// Batch pipeline
// -------------------------------------------------------------------------

long allStart = TiledPoc.now()
File inputDir = new File(INPUT_DIR)
File outputDir = new File(OUTPUT_DIR)
File mipDir = new File(outputDir, 'MIPs')
File movieDir = new File(outputDir, 'MIP_movies')
File zarrRoot = new File(outputDir, 'DS_ome_zarr/deskew_tiled.ome.zarr')
mipDir.mkdirs(); movieDir.mkdirs()

File[] allFiles = inputDir.listFiles({ File f ->
    f.isFile() && f.name.toLowerCase().endsWith(FILE_EXTENSION) && f.name.contains(FILE_NAME_CONTAINS)
} as FileFilter)
if (allFiles == null || allFiles.length == 0) throw new RuntimeException('No input TIFF files found in ' + INPUT_DIR)
List<File> files = allFiles.sort { it.name }
if (MAX_FILES > 0 && files.size() > MAX_FILES) files = files.subList(0, MAX_FILES)

FastTiffInfo firstInfo = FastTiffReader.parse(files[0])
long[] firstRawDims = [firstInfo.width as long, firstInfo.height as long, firstInfo.stripOffsets.size() as long] as long[]
double[][] firstForwardMatrix = TiledPoc.deskewMatrix(Z_STEP_UM, XY_PIXEL_SIZE_UM, OPM_ANGLE_DEG, firstInfo.height)
long[] outputDims = TiledPoc.transformedDimensions(firstRawDims, firstForwardMatrix)

TiledOmeZarrWriter zarrWriter = new TiledOmeZarrWriter(
        zarrRoot, files.size(), (int)outputDims[2], (int)outputDims[1], (int)outputDims[0],
        ZARR_CHUNK_Z, ZARR_CHUNK_Y, ZARR_CHUNK_X, XY_PIXEL_SIZE_UM)
zarrWriter.initialize()

TiledPoc.log('')
TiledPoc.log('OPM fast TIFF + tiled CLIJ + OME-Zarr POC')
TiledPoc.log('Input folder: ' + INPUT_DIR)
TiledPoc.log('Output folder: ' + OUTPUT_DIR)
TiledPoc.log('Files: ' + files.size())
TiledPoc.log(String.format('Raw first file: %dx%dx%d', firstInfo.width, firstInfo.height, firstInfo.stripOffsets.size()))
TiledPoc.log(String.format('Deskew output: %dx%dx%d', outputDims[0], outputDims[1], outputDims[2]))
TiledPoc.log(String.format('Output Z slab=%d, Zarr chunks=[1,1,%d,%d,%d]', OUTPUT_SLAB_Z, ZARR_CHUNK_Z, ZARR_CHUNK_Y, ZARR_CHUNK_X))
TiledPoc.log(String.format('Fast TIFF reader threads: %d', FAST_TIFF_THREADS))

CLIJ2 clij2 = TiledPoc.getClij2(GPU_NAME_HINT)
clij2.clear()
TiledPoc.log(clij2.reportMemory())

ImageStack xyMovie = null
ImageStack yzMovie = null
ImageStack xzMovie = null
ImagePlus xyMovieImp = null
ImagePlus yzMovieImp = null
ImagePlus xzMovieImp = null

double sumParse = 0d, sumRead = 0d, sumWrap = 0d, sumUpload = 0d
double sumDeskew = 0d, sumMip = 0d, sumPullSlab = 0d, sumWriteZarr = 0d
double sumPullMips = 0d, sumSaveMips = 0d, sumPerFile = 0d

try {
    int index = 0
    for (File file : files) {
        index++
        long fileStart = TiledPoc.now()
        String name = TiledPoc.baseName(file)
        TiledPoc.log('')
        TiledPoc.log(String.format('Processing %d/%d: %s', index, files.size(), file.name))

        short[][] rawVolume = null
        ImagePlus rawImp = null
        ClearCLBuffer rawGpu = null
        ClearCLBuffer globalMipXYGpu = null

        short[] fullMipYZ = new short[(int)(outputDims[1] * outputDims[2])]
        short[] fullMipXZ = new short[(int)(outputDims[0] * outputDims[2])]
        double fileDeskewSlabs = 0d
        double fileMipSlabs = 0d
        double filePullSlabs = 0d
        double fileWriteZarr = 0d

        try {
            long tParse = TiledPoc.now()
            FastTiffInfo info = FastTiffReader.parse(file)
            double parseSec = TiledPoc.secondsSince(tParse); sumParse += parseSec

            long tRead = TiledPoc.now()
            rawVolume = FastTiffReader.readPixelsParallel(file, info, FAST_TIFF_THREADS)
            double readSec = TiledPoc.secondsSince(tRead); sumRead += readSec

            long tWrap = TiledPoc.now()
            rawImp = TiledPoc.wrapShortVolume(name + '-raw-fast', rawVolume, info.width, info.height)
            double wrapSec = TiledPoc.secondsSince(tWrap); sumWrap += wrapSec

            long[] rawDims = [info.width as long, info.height as long, info.stripOffsets.size() as long] as long[]
            double[][] forwardMatrix = TiledPoc.deskewMatrix(Z_STEP_UM, XY_PIXEL_SIZE_UM, OPM_ANGLE_DEG, info.height)
            double[][] centeredForwardMatrix = TiledPoc.autoCenter(rawDims, forwardMatrix)
            AffineTransform3D inverseTransform = TiledPoc.toInverseAffineTransform3D(centeredForwardMatrix)

            long tUpload = TiledPoc.now()
            rawGpu = clij2.push(rawImp)
            double uploadSec = TiledPoc.secondsSince(tUpload); sumUpload += uploadSec

            if (CLOSE_RAW_AFTER_UPLOAD) {
                rawImp.changes = false; rawImp.close(); rawImp.flush()
                rawImp = null; rawVolume = null; System.gc()
            }

            globalMipXYGpu = clij2.create([outputDims[0], outputDims[1]] as long[], rawGpu.getNativeType())
            clij2.set(globalMipXYGpu, 0d)

            for (int z0 = 0; z0 < outputDims[2]; z0 += OUTPUT_SLAB_Z) {
                int slabD = Math.min(OUTPUT_SLAB_Z, (int)outputDims[2] - z0)
                ClearCLBuffer slabGpu = null
                ClearCLBuffer slabXYGpu = null
                ClearCLBuffer slabYZGpu = null
                ClearCLBuffer slabXZGpu = null
                ClearCLBuffer tmpXYGpu = null
                try {
                    slabGpu = clij2.create([outputDims[0], outputDims[1], slabD] as long[], rawGpu.getNativeType())
                    slabXYGpu = clij2.create([outputDims[0], outputDims[1]] as long[], rawGpu.getNativeType())
                    slabYZGpu = clij2.create([outputDims[1], slabD] as long[], rawGpu.getNativeType())
                    slabXZGpu = clij2.create([outputDims[0], slabD] as long[], rawGpu.getNativeType())

                    long tDeskew = TiledPoc.now()
                    clij2.affineTransform3D(rawGpu, slabGpu, TiledPoc.shiftedInverseForOutputZ(inverseTransform, z0))
                    double deskewSlabSec = TiledPoc.secondsSince(tDeskew)
                    fileDeskewSlabs += deskewSlabSec
                    sumDeskew += deskewSlabSec

                    long tMip = TiledPoc.now()
                    clij2.maximumZProjection(slabGpu, slabXYGpu)
                    tmpXYGpu = clij2.create([outputDims[0], outputDims[1]] as long[], rawGpu.getNativeType())
                    clij2.maximumImages(globalMipXYGpu, slabXYGpu, tmpXYGpu)
                    clij2.release(globalMipXYGpu)
                    globalMipXYGpu = tmpXYGpu
                    tmpXYGpu = null
                    clij2.maximumXProjection(slabGpu, slabYZGpu)
                    clij2.maximumYProjection(slabGpu, slabXZGpu)
                    double mipSlabSec = TiledPoc.secondsSince(tMip)
                    fileMipSlabs += mipSlabSec
                    sumMip += mipSlabSec

                    long tPullSlab = TiledPoc.now()
                    ImagePlus slabImp = clij2.pull(slabGpu)
                    ImagePlus slabYZ = clij2.pull(slabYZGpu)
                    ImagePlus slabXZ = clij2.pull(slabXZGpu)
                    double pullSlabSec = TiledPoc.secondsSince(tPullSlab)
                    filePullSlabs += pullSlabSec
                    sumPullSlab += pullSlabSec

                    TiledPoc.copySlabProjectionIntoFull(fullMipYZ, (int)outputDims[1], z0, slabYZ)
                    TiledPoc.copySlabProjectionIntoFull(fullMipXZ, (int)outputDims[0], z0, slabXZ)
                    slabYZ.changes = false; slabYZ.close(); slabYZ.flush()
                    slabXZ.changes = false; slabXZ.close(); slabXZ.flush()

                    long tWrite = TiledPoc.now()
                    zarrWriter.writeAlignedZSlab(slabImp, index - 1, z0)
                    double writeZarrSec = TiledPoc.secondsSince(tWrite)
                    fileWriteZarr += writeZarrSec
                    sumWriteZarr += writeZarrSec
                    slabImp.changes = false; slabImp.close(); slabImp.flush()
                } finally {
                    if (slabGpu != null) clij2.release(slabGpu)
                    if (slabXYGpu != null) clij2.release(slabXYGpu)
                    if (slabYZGpu != null) clij2.release(slabYZGpu)
                    if (slabXZGpu != null) clij2.release(slabXZGpu)
                    if (tmpXYGpu != null) clij2.release(tmpXYGpu)
                    System.gc()
                }
            }

            clij2.release(rawGpu); rawGpu = null

            long tPullMips = TiledPoc.now()
            ImagePlus mipXY = clij2.pull(globalMipXYGpu)
            ImagePlus mipYZ = TiledPoc.makeShortImage(name + '-MIP-YZ', (int)outputDims[1], (int)outputDims[2], fullMipYZ)
            ImagePlus mipXZ = TiledPoc.makeShortImage(name + '-MIP-XZ', (int)outputDims[0], (int)outputDims[2], fullMipXZ)
            mipXY.setTitle(name + '-MIP-XY')
            sumPullMips += TiledPoc.secondsSince(tPullMips)

            if (xyMovie == null) {
                xyMovie = new ImageStack(mipXY.getWidth(), mipXY.getHeight())
                yzMovie = new ImageStack(mipYZ.getWidth(), mipYZ.getHeight())
                xzMovie = new ImageStack(mipXZ.getWidth(), mipXZ.getHeight())
                xyMovie.addSlice(name, mipXY.getProcessor().duplicate())
                yzMovie.addSlice(name, mipYZ.getProcessor().duplicate())
                xzMovie.addSlice(name, mipXZ.getProcessor().duplicate())
                xyMovieImp = new ImagePlus('OPM tiled XY MIP movie', xyMovie)
                yzMovieImp = new ImagePlus('OPM tiled YZ MIP movie', yzMovie)
                xzMovieImp = new ImagePlus('OPM tiled XZ MIP movie', xzMovie)
                if (DISPLAY_MIP_MOVIES) { xyMovieImp.show(); yzMovieImp.show(); xzMovieImp.show() }
            } else {
                xyMovie.addSlice(name, mipXY.getProcessor().duplicate())
                yzMovie.addSlice(name, mipYZ.getProcessor().duplicate())
                xzMovie.addSlice(name, mipXZ.getProcessor().duplicate())
                if (DISPLAY_MIP_MOVIES) {
                    xyMovieImp.setStack(xyMovie); yzMovieImp.setStack(yzMovie); xzMovieImp.setStack(xzMovie)
                    xyMovieImp.setSlice(index); yzMovieImp.setSlice(index); xzMovieImp.setSlice(index)
                    xyMovieImp.updateAndDraw(); yzMovieImp.updateAndDraw(); xzMovieImp.updateAndDraw()
                }
            }

            long tSaveMips = TiledPoc.now()
            if (SAVE_INDIVIDUAL_MIPS) {
                TiledPoc.saveTiff(mipXY, new File(mipDir, name + '-MIP-XY.tif'))
                TiledPoc.saveTiff(mipYZ, new File(mipDir, name + '-MIP-YZ.tif'))
                TiledPoc.saveTiff(mipXZ, new File(mipDir, name + '-MIP-XZ.tif'))
            }
            double saveMipsSec = TiledPoc.secondsSince(tSaveMips); sumSaveMips += saveMipsSec

            mipXY.changes = false; mipXY.close(); mipXY.flush()
            mipYZ.changes = false; mipYZ.close(); mipYZ.flush()
            mipXZ.changes = false; mipXZ.close(); mipXZ.flush()

            double fileSec = TiledPoc.secondsSince(fileStart); sumPerFile += fileSec
            TiledPoc.log(String.format(
                    'Timing: parse %.3f, read %.3f, wrap %.3f, upload %.3f, deskewSlabs %.3f, mipSlabs %.3f, pullSlabs %.3f, writeZarr %.3f, saveMips %.3f, total %.3f s',
                    parseSec, readSec, wrapSec, uploadSec,
                    fileDeskewSlabs, fileMipSlabs, filePullSlabs, fileWriteZarr, saveMipsSec, fileSec))
            IJ.showProgress(index, files.size())
        } finally {
            if (rawImp != null) { rawImp.changes = false; rawImp.close(); rawImp.flush() }
            rawVolume = null
            if (rawGpu != null) clij2.release(rawGpu)
            if (globalMipXYGpu != null) clij2.release(globalMipXYGpu)
            System.gc()
        }
    }

    if (SAVE_MIP_MOVIES && xyMovie != null) {
        long tMovies = TiledPoc.now()
        TiledPoc.saveTiff(xyMovieImp, new File(movieDir, 'MIP-movie-XY.tif'))
        TiledPoc.saveTiff(yzMovieImp, new File(movieDir, 'MIP-movie-YZ.tif'))
        TiledPoc.saveTiff(xzMovieImp, new File(movieDir, 'MIP-movie-XZ.tif'))
        TiledPoc.log(String.format('Saved MIP movies in %.3f s', TiledPoc.secondsSince(tMovies)))
    }
} finally {
    if (clij2 != null) clij2.clear()
}

double totalSec = TiledPoc.secondsSince(allStart)
int n = files.size()
TiledPoc.log('')
TiledPoc.log('Batch summary')
TiledPoc.log(String.format('Files processed: %d', n))
TiledPoc.log(String.format('Total elapsed: %.3f s', totalSec))
TiledPoc.log(String.format('Mean per file: %.3f s', sumPerFile / n))
TiledPoc.log(String.format(
        'Mean timing: parse %.3f, read %.3f, wrap %.3f, upload %.3f, deskewSlabs %.3f, mipSlabs %.3f, pullSlabs %.3f, writeZarr %.3f, pullMips %.3f, saveMips %.3f s',
        sumParse/n, sumRead/n, sumWrap/n, sumUpload/n, sumDeskew/n, sumMip/n,
        sumPullSlab/n, sumWriteZarr/n, sumPullMips/n, sumSaveMips/n))
TiledPoc.log('Done.')
