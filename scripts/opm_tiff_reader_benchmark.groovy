// OPM TIFF reader benchmark for Fiji/ImageJ Groovy.
//
// Benchmarks three input paths against the first sample TIFF:
//   1. ImageJ IJ.openImage()
//   2. Bio-Formats ImageReader.openBytes()
//   3. A narrow custom TIFF/BigTIFF strip reader for uncompressed 16-bit grayscale stacks
//
// The custom reader is intentionally targeted. It is not a general TIFF reader.
// It validates the TIFF layout and fails fast if compression/samples/bit-depth
// are not compatible with the OPM fast path.
//
// To run in Fiji:
//   Plugins > Scripting > Script Editor, language = Groovy, open this file,
//   then Run.

import ij.IJ
import ij.ImagePlus
import ij.ImageStack

import loci.formats.FormatTools
import loci.formats.ImageReader

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

final boolean RUN_IMAGEJ = false
final boolean RUN_BIO_FORMATS = false
final boolean RUN_CUSTOM_TIFF = true
final boolean RUN_CUSTOM_TIFF_PARALLEL = true

// Use 0 to benchmark useful thread counts automatically.
final int CUSTOM_TIFF_THREADS = 0

// Keep all planes in memory during each benchmark. This is closest to the
// current ImagePlus -> CLIJ upload workflow. Each reader is released before the
// next one starts.
final boolean STORE_FULL_VOLUME = true

// Later readers may benefit from Windows filesystem cache. For a fairer study,
// rerun the script with only one reader enabled at a time.

// -------------------------------------------------------------------------
// Shared utilities
// -------------------------------------------------------------------------

class TiffBench {
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

    static String mbps(long bytes, double seconds) {
        double mb = bytes / 1024.0d / 1024.0d
        return String.format('%.1f MiB/s', mb / Math.max(seconds, 1.0e-9d))
    }

    static int[] uniqueIndices(int n) {
        if (n <= 1) return [0] as int[]
        TreeSet<Integer> values = new TreeSet<Integer>()
        values.add(0)
        values.add((int) Math.floor(n / 4.0d))
        values.add((int) Math.floor(n / 2.0d))
        values.add((int) Math.floor(3.0d * n / 4.0d))
        values.add(n - 1)
        return values.collect { it as int } as int[]
    }

    static long mix(long checksum, int value, int x, int y, int z) {
        long v = (value & 0xffff) + 31L * x + 131L * y + 8191L * z
        return checksum * 1000003L + v
    }

    static long checksumImagePlus(ImagePlus imp) {
        int w = imp.getWidth()
        int h = imp.getHeight()
        int d = imp.getStackSize()
        int[] xs = uniqueIndices(w)
        int[] ys = uniqueIndices(h)
        int[] zs = uniqueIndices(d)
        ImageStack stack = imp.getStack()
        long checksum = 1469598103934665603L
        for (int z : zs) {
            Object pixels = stack.getProcessor(z + 1).getPixels()
            if (!(pixels instanceof short[])) {
                throw new RuntimeException('Expected 16-bit ImagePlus stack for checksum.')
            }
            short[] plane = (short[]) pixels
            for (int y : ys) {
                int row = y * w
                for (int x : xs) {
                    checksum = mix(checksum, plane[row + x] & 0xffff, x, y, z)
                }
            }
        }
        return checksum
    }

    static long checksumShortVolume(short[][] volume, int w, int h, int d) {
        int[] xs = uniqueIndices(w)
        int[] ys = uniqueIndices(h)
        int[] zs = uniqueIndices(d)
        long checksum = 1469598103934665603L
        for (int z : zs) {
            short[] plane = volume[z]
            for (int y : ys) {
                int row = y * w
                for (int x : xs) {
                    checksum = mix(checksum, plane[row + x] & 0xffff, x, y, z)
                }
            }
        }
        return checksum
    }

    static void closeAndFlush(ImagePlus imp) {
        if (imp != null) {
            imp.changes = false
            imp.close()
            imp.flush()
        }
    }

    static void gcPause() {
        System.gc()
        try {
            Thread.sleep(250)
        } catch (InterruptedException ignored) {
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
    static final int TYPE_SBYTE = 6
    static final int TYPE_UNDEFINED = 7
    static final int TYPE_SSHORT = 8
    static final int TYPE_SLONG = 9
    static final int TYPE_SRATIONAL = 10
    static final int TYPE_FLOAT = 11
    static final int TYPE_DOUBLE = 12
    static final int TYPE_LONG8 = 16
    static final int TYPE_SLONG8 = 17
    static final int TYPE_IFD8 = 18

    static class IfdResult {
        Map<Integer, long[]> tags
        long nextOffset
    }

    static ByteBuffer readAt(FileChannel channel, long offset, int length, ByteOrder order) {
        ByteBuffer buffer = ByteBuffer.allocate(length)
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, offset + buffer.position())
            if (n < 0) {
                throw new EOFException('Unexpected end of TIFF file.')
            }
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
            case TYPE_SBYTE:
            case TYPE_UNDEFINED:
                return 1
            case TYPE_SHORT:
            case TYPE_SSHORT:
                return 2
            case TYPE_LONG:
            case TYPE_SLONG:
            case TYPE_FLOAT:
                return 4
            case TYPE_RATIONAL:
            case TYPE_DOUBLE:
            case TYPE_SRATIONAL:
            case TYPE_LONG8:
            case TYPE_SLONG8:
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

    static short[][] readPixels(File file, FastTiffInfo info, boolean storeFullVolume) {
        int w = info.width
        int h = info.height
        int d = info.stripOffsets.size()
        int planeBytes = Math.multiplyExact(w * h, 2)
        int rowBytes = Math.multiplyExact(w, 2)
        byte[] rawPlane = new byte[planeBytes]
        short[][] volume = storeFullVolume ? new short[d][] : new short[1][]

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
                if (storeFullVolume) {
                    volume[z] = pixels
                } else if (z == d - 1) {
                    volume[0] = pixels
                }
            }
            return volume
        } finally {
            channel.close()
        }
    }

    static short[][] readPixelsParallel(File file, FastTiffInfo info, int numThreads, boolean storeFullVolume) {
        if (!storeFullVolume || numThreads <= 1) {
            return readPixels(file, info, storeFullVolume)
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
                if (zStart >= zEnd) {
                    continue
                }

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

            for (Future f : futures) {
                f.get()
            }
        } finally {
            executor.shutdownNow()
        }

        return volume
    }
}

// -------------------------------------------------------------------------
// Benchmarks
// -------------------------------------------------------------------------

File inputFile = new File(INPUT_TIFF)
if (!inputFile.isFile()) {
    throw new RuntimeException('Input file not found: ' + INPUT_TIFF)
}

long fileBytes = inputFile.length()
TiffBench.log('')
TiffBench.log('OPM TIFF reader benchmark')
TiffBench.log('Input: ' + INPUT_TIFF)
TiffBench.log(String.format('File size: %.3f GiB', fileBytes / 1024.0d / 1024.0d / 1024.0d))
TiffBench.log('Note: later benchmarks may benefit from OS file cache; rerun one reader at a time for stricter comparison.')

Long referenceChecksum = null

if (RUN_IMAGEJ) {
    TiffBench.log('')
    TiffBench.log('Benchmark 1: ImageJ IJ.openImage')
    ImagePlus imp = null
    long t0 = TiffBench.now()
    try {
        imp = IJ.openImage(INPUT_TIFF)
        if (imp == null) {
            throw new RuntimeException('IJ.openImage returned null.')
        }
        double sec = TiffBench.secondsSince(t0)
        long checksum = TiffBench.checksumImagePlus(imp)
        referenceChecksum = checksum
        TiffBench.log(String.format(
                'ImageJ result: %dx%dx%d, time %.3f s, throughput %s, checksum %d',
                imp.getWidth(), imp.getHeight(), imp.getStackSize(), sec, TiffBench.mbps(fileBytes, sec), checksum))
    } finally {
        TiffBench.closeAndFlush(imp)
        imp = null
        TiffBench.gcPause()
    }
}

if (RUN_BIO_FORMATS) {
    TiffBench.log('')
    TiffBench.log('Benchmark 2: Bio-Formats ImageReader.openBytes')
    ImageReader reader = null
    short[][] volume = null
    long tInit = TiffBench.now()
    try {
        reader = new ImageReader()
        reader.setId(INPUT_TIFF)
        double initSec = TiffBench.secondsSince(tInit)

        int w = reader.getSizeX()
        int h = reader.getSizeY()
        int d = reader.getImageCount()
        int pixelType = reader.getPixelType()
        int bytesPerPixel = FormatTools.getBytesPerPixel(pixelType)
        if (bytesPerPixel != 2) {
            throw new RuntimeException('Expected 16-bit Bio-Formats pixel type, got ' + FormatTools.getPixelTypeString(pixelType))
        }

        byte[] planeBytes = new byte[w * h * bytesPerPixel]
        volume = STORE_FULL_VOLUME ? new short[d][] : new short[1][]
        ByteOrder order = reader.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN

        long tRead = TiffBench.now()
        for (int z = 0; z < d; z++) {
            reader.openBytes(z, planeBytes)
            short[] pixels = new short[w * h]
            ByteBuffer.wrap(planeBytes).order(order).asShortBuffer().get(pixels)
            if (STORE_FULL_VOLUME) {
                volume[z] = pixels
            } else if (z == d - 1) {
                volume[0] = pixels
            }
        }
        double readSec = TiffBench.secondsSince(tRead)
        double totalSec = TiffBench.secondsSince(tInit)
        long checksum = STORE_FULL_VOLUME ? TiffBench.checksumShortVolume(volume, w, h, d) : 0L
        String checkText = STORE_FULL_VOLUME ? String.valueOf(checksum) : 'not computed'
        String matchText = referenceChecksum == null || !STORE_FULL_VOLUME ? 'n/a' :
                (checksum == referenceChecksum ? 'MATCH' : 'DIFF')

        TiffBench.log(String.format(
                'Bio-Formats result: %dx%dx%d, init %.3f s, read+convert %.3f s, total %.3f s, throughput %s, checksum %s, vs ImageJ %s',
                w, h, d, initSec, readSec, totalSec, TiffBench.mbps(fileBytes, readSec), checkText, matchText))
    } finally {
        if (reader != null) {
            reader.close()
        }
        volume = null
        TiffBench.gcPause()
    }
}

if (RUN_CUSTOM_TIFF) {
    TiffBench.log('')
    TiffBench.log('Benchmark 3: Custom targeted TIFF strip reader')
    FastTiffInfo info = null
    short[][] volume = null
    long tParse = TiffBench.now()
    try {
        info = FastTiffReader.parse(inputFile)
        double parseSec = TiffBench.secondsSince(tParse)
        TiffBench.log(String.format(
                'Custom parser metadata: %s, %s, %dx%dx%d, bits=%d, compression=%d, rowsPerStrip=%d',
                info.bigTiff ? 'BigTIFF' : 'classic TIFF',
                info.byteOrder == ByteOrder.LITTLE_ENDIAN ? 'little-endian' : 'big-endian',
                info.width, info.height, info.stripOffsets.size(),
                info.bitsPerSample, info.compression, info.rowsPerStrip))

        long tRead = TiffBench.now()
        volume = FastTiffReader.readPixels(inputFile, info, STORE_FULL_VOLUME)
        double readSec = TiffBench.secondsSince(tRead)
        double totalSec = TiffBench.secondsSince(tParse)
        long checksum = STORE_FULL_VOLUME ?
                TiffBench.checksumShortVolume(volume, info.width, info.height, info.stripOffsets.size()) : 0L
        String checkText = STORE_FULL_VOLUME ? String.valueOf(checksum) : 'not computed'
        String matchText = referenceChecksum == null || !STORE_FULL_VOLUME ? 'n/a' :
                (checksum == referenceChecksum ? 'MATCH' : 'DIFF')

        TiffBench.log(String.format(
                'Custom reader result: %dx%dx%d, parse %.3f s, read+convert %.3f s, total %.3f s, throughput %s, checksum %s, vs ImageJ %s',
                info.width, info.height, info.stripOffsets.size(),
                parseSec, readSec, totalSec, TiffBench.mbps(fileBytes, readSec), checkText, matchText))
    } finally {
        volume = null
        TiffBench.gcPause()
    }
}

if (RUN_CUSTOM_TIFF_PARALLEL) {
    TiffBench.log('')
    TiffBench.log('Benchmark 4: Custom targeted TIFF strip reader, parallel')
    FastTiffInfo info = null
    short[][] volume = null
    long tParse = TiffBench.now()
    try {
        info = FastTiffReader.parse(inputFile)
        double parseSec = TiffBench.secondsSince(tParse)

        int maxThreads = Math.max(1, Runtime.runtime.availableProcessors())
        List<Integer> threadCounts
        if (CUSTOM_TIFF_THREADS > 0) {
            threadCounts = [CUSTOM_TIFF_THREADS]
        } else {
            TreeSet<Integer> counts = new TreeSet<Integer>()
            [1, 2, 4, 8, 12, 16, maxThreads].each { int v ->
                if (v >= 1 && v <= maxThreads) {
                    counts.add(v)
                }
            }
            threadCounts = new ArrayList<Integer>(counts)
        }

        TiffBench.log(String.format(
                'Custom parser metadata: %s, %s, %dx%dx%d, bits=%d, compression=%d, rowsPerStrip=%d, parse %.3f s',
                info.bigTiff ? 'BigTIFF' : 'classic TIFF',
                info.byteOrder == ByteOrder.LITTLE_ENDIAN ? 'little-endian' : 'big-endian',
                info.width, info.height, info.stripOffsets.size(),
                info.bitsPerSample, info.compression, info.rowsPerStrip, parseSec))

        for (int threads : threadCounts) {
            volume = null
            TiffBench.gcPause()
            long tRead = TiffBench.now()
            volume = FastTiffReader.readPixelsParallel(inputFile, info, threads, STORE_FULL_VOLUME)
            double readSec = TiffBench.secondsSince(tRead)
            long checksum = STORE_FULL_VOLUME ?
                    TiffBench.checksumShortVolume(volume, info.width, info.height, info.stripOffsets.size()) : 0L
            String checkText = STORE_FULL_VOLUME ? String.valueOf(checksum) : 'not computed'
            String matchText = referenceChecksum == null || !STORE_FULL_VOLUME ? 'n/a' :
                    (checksum == referenceChecksum ? 'MATCH' : 'DIFF')

            TiffBench.log(String.format(
                    'Parallel custom result: threads=%d, read+convert %.3f s, total incl parse %.3f s, throughput %s, checksum %s, vs ImageJ %s',
                    threads, readSec, readSec + parseSec, TiffBench.mbps(fileBytes, readSec), checkText, matchText))
        }
    } finally {
        volume = null
        TiffBench.gcPause()
    }
}

TiffBench.log('')
TiffBench.log('Done.')
