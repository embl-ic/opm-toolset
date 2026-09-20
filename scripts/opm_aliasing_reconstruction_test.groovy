// Fiji/Groovy proof of concept for:
// McFadden et al., "Increasing the acquisition speed in oblique plane
// microscopy via aliasing", Biomed. Opt. Express 16, 1742-1753 (2025).
// https://pubmed.ncbi.nlm.nih.gov/39763995/
// https://github.com/AdvancedImagingUTSW/OPM-Alias
//
// The experiment starts with a critically sampled raw OPM stack, keeps every
// Nth acquisition plane, inserts zero planes back onto the original grid,
// deskews, applies the paper's post-deskew+rotation Fourier-space top-hat mask,
// and inverse transforms.  OPM Toolset's deskew matrix is already a combined,
// non-orthogonal affine transform.  The default therefore maps the authors'
// diagonal top-hat into the final kY-kZ coordinates; an authors-timing-script
// final-Z band is retained as an optional comparison.  The
// untouched deskewed ROI is the ground truth.  Raw-Z linear interpolation is
// included as a deliberately strong conventional baseline.
//
// Requirements in Fiji:
//   * OPM Toolset installed (for its streaming BigTIFF PlaneReader)
//   * JTransforms, which is already a transitive Fiji/OPM Toolset dependency
//
// Run from Plugins > Scripting > Script Editor with Language = Groovy.
// Coordinates below are zero based.  Defaults select a cell in the supplied
// E:/OPM/3_timelapse_0 time-point 1, channel 1 stack and use only a small ROI.

#@ File(label="Raw OPM TIFF", style="file", required=false) inputTiff
#@ File(label="Output directory", style="directory", required=false) outputDirectory
#@ String(label="Artificial undersampling factors", value="2,3,4") factorsText
#@ Integer(label="Raw X start (0 based)", value=1800, min=0) rawXStart
#@ Integer(label="Raw X width", value=320, min=8) rawXWidth
#@ Integer(label="Raw Y start (0 based)", value=128, min=0) rawYStart
#@ Integer(label="Raw Y height", value=256, min=8) rawYHeight
#@ Integer(label="Raw Z start (0 based)", value=184, min=0) rawZStart
#@ Integer(label="Raw Z depth", value=224, min=8) rawZDepth
#@ Double(label="Camera pixel size (micrometers)", value=0.116, min=0.000001) xyPixelSizeUm
#@ Double(label="Acquisition Z step (micrometers)", value=0.265, min=0.000001) zStepUm
#@ Double(label="OPM angle (degrees)", value=25.0, min=0.01, max=89.99) opmAngleDeg
#@ Integer(label="Deskewed FFT Y size (0 = full)", value=640, min=0) fftHeight
#@ Integer(label="Deskewed FFT Z size (0 = full)", value=96, min=0) fftDepth
#@ String(label="Fourier mask geometry", choices={"diagonal", "final Z"}, value="diagonal") maskGeometry
#@ Double(label="Mask cosine edge width (bins; 0 = paper top-hat)", value=0.0, min=0.0) maskEdgeBins
#@ Boolean(label="Save reconstructed 32-bit volumes", value=false) saveFullVolumes
#@ Boolean(label="Measure beads and empirical OTF", value=false) analyzeBeads
#@ Integer(label="Maximum beads to measure", value=24, min=1) maximumBeads
#@ Integer(label="Bead crop XY radius (pixels)", value=12, min=4) beadRadiusXY
#@ Integer(label="Bead crop Z radius (pixels)", value=12, min=4) beadRadiusZ
#@ Double(label="Exposure per acquired plane (ms; 0 = omit time)", value=0.0, min=0.0) exposureTimeMs
#@ Double(label="Conservative FWHM-change limit (%)", value=5.0, min=0.1) fwhmChangeLimitPercent

import de.embl.iclm.FastTiffReader
import edu.emory.mathcs.jtransforms.fft.FloatFFT_3D
import groovy.transform.CompileStatic
import ij.IJ
import ij.ImagePlus
import ij.ImageStack
import ij.io.FileSaver
import ij.process.ByteProcessor
import ij.process.FloatProcessor
import ij.process.ImageProcessor

import java.awt.Color
import java.awt.Font
import java.util.Locale

@CompileStatic
final class AliasingVolume {
    final int width
    final int height
    final int depth
    final float[] pixels

    AliasingVolume(int width, int height, int depth) {
        if (width < 1 || height < 1 || depth < 1) {
            throw new IllegalArgumentException('Volume dimensions must be positive.')
        }
        long count = (long) width * (long) height * (long) depth
        if (count > Integer.MAX_VALUE) {
            throw new IllegalArgumentException('ROI is too large for one Java float array: ' + count + ' voxels.')
        }
        this.width = width
        this.height = height
        this.depth = depth
        this.pixels = new float[(int) count]
    }

    int index(int x, int y, int z) {
        return (z * height + y) * width + x
    }

    float get(int x, int y, int z) {
        return pixels[index(x, y, z)]
    }
}

@CompileStatic
final class AliasingMetrics {
    String region
    long count
    double rmse
    double nrmse
    double psnr
    double pearson

    String csv(String factor, String method) {
        return String.format(Locale.US, '%s,%s,%s,%d,%.8g,%.8g,%.8g,%.8g',
                factor, method, region, count, rmse, nrmse, psnr, pearson)
    }
}

@CompileStatic
final class AliasingBeadMeasurement {
    int bead
    int x
    int y
    int z
    double background
    double snr
    double fwhmXUm
    double fwhmYUm
    double fwhmZUm
    double shiftUm
    double shapeNrmse
    double shapePearson

    String csv(int factor, String method) {
        return String.format(Locale.US,
                '%d,%s,%d,%d,%d,%d,%.8g,%.8g,%.8g,%.8g,%.8g,%.8g,%.8g,%.8g',
                factor, method, bead, x, y, z, background, snr,
                fwhmXUm, fwhmYUm, fwhmZUm, shiftUm, shapeNrmse, shapePearson)
    }
}

@CompileStatic
final class AliasingReconstruction {
    static final int MODE_TRUTH = 0
    static final int MODE_ZERO_COMB = 1
    static final int MODE_LINEAR = 2

    static void log(String message) {
        IJ.log(message)
        println(message)
    }

    static AliasingVolume readRawRoi(File file, int x0, int width, int y0, int height,
                                     int z0, int depth) {
        FastTiffReader.Info info = FastTiffReader.parse(file)
        if (x0 < 0 || width < 1 || x0 + width > info.width ||
                y0 < 0 || height < 1 || y0 + height > info.height ||
                z0 < 0 || depth < 1 || z0 + depth > info.depth()) {
            throw new IllegalArgumentException(String.format(Locale.US,
                    'ROI x=%d+%d y=%d+%d z=%d+%d is outside TIFF %dx%dx%d.',
                    x0, width, y0, height, z0, depth,
                    info.width, info.height, info.depth()))
        }

        log(String.format(Locale.US,
                'TIFF: %dx%dx%d, %d bit, compression=%d, BigTIFF=%s',
                info.width, info.height, info.depth(), info.bitsPerSample,
                info.compression, Boolean.toString(info.bigTiff)))
        log(String.format(Locale.US, 'Reading raw ROI x=%d+%d y=%d+%d z=%d+%d ...',
                x0, width, y0, height, z0, depth))

        AliasingVolume result = new AliasingVolume(width, height, depth)
        FastTiffReader.PlaneReader reader = new FastTiffReader.PlaneReader(file, info)
        try {
            for (int z = 0; z < depth; z++) {
                short[] rows = reader.readRows(z0 + z, y0, height)
                for (int y = 0; y < height; y++) {
                    int source = y * info.width + x0
                    int target = (z * height + y) * width
                    for (int x = 0; x < width; x++) {
                        result.pixels[target + x] = (float) (rows[source + x] & 0xffff)
                    }
                }
                if ((z & 31) == 0) IJ.showProgress(z, depth)
            }
        } finally {
            reader.close()
            IJ.showProgress(1.0d)
        }
        return result
    }

    static int[] deskewedShape(AliasingVolume raw, double xy, double dz, double angleDeg) {
        double theta = Math.toRadians(angleDeg)
        int height = (int) Math.ceil(raw.height * Math.cos(theta) + raw.depth * dz / xy)
        int depth = (int) Math.ceil(raw.height * Math.sin(theta))
        return new int[] { raw.width, height, depth }
    }

    static AliasingVolume deskew(AliasingVolume raw, double xy, double dz, double angleDeg,
                                  int targetHeight, int targetDepth, int mode, int factor) {
        int[] full = deskewedShape(raw, xy, dz, angleDeg)
        int outHeight = targetHeight <= 0 ? full[1] : Math.min(targetHeight, full[1])
        int outDepth = targetDepth <= 0 ? full[2] : Math.min(targetDepth, full[2])
        int cropY = Math.max(0, (full[1] - outHeight).intdiv(2))
        int cropZ = Math.max(0, (full[2] - outDepth).intdiv(2))
        AliasingVolume out = new AliasingVolume(raw.width, outHeight, outDepth)

        double theta = Math.toRadians(angleDeg)
        double sinTheta = Math.sin(theta)
        double cosTheta = Math.cos(theta)
        double dzOverDxy = dz / xy
        int lastKept = (raw.depth - 1).intdiv(factor) * factor

        for (int oz = 0; oz < outDepth; oz++) {
            int zOut = oz + cropZ
            double yIn = (raw.height * sinTheta - zOut) / sinTheta
            int y0 = (int) Math.floor(yIn)
            int y1 = y0 + 1
            float ty = (float) (yIn - y0)
            if (y0 < 0 || y1 >= raw.height) continue

            for (int oy = 0; oy < outHeight; oy++) {
                int yOut = oy + cropY
                double zIn = (yOut - cosTheta * yIn) / dzOverDxy
                if (zIn < 0.0d || zIn > raw.depth - 1.0d) continue
                int outBase = (oz * outHeight + oy) * raw.width

                if (mode == MODE_LINEAR) {
                    int za = Math.min(lastKept, Math.max(0, ((int) Math.floor(zIn / factor)) * factor))
                    int zb = Math.min(lastKept, za + factor)
                    float tz = zb == za ? 0.0f : (float) ((zIn - za) / (zb - za))
                    tz = Math.max(0.0f, Math.min(1.0f, tz))
                    copyBilinearLine(raw, out.pixels, outBase, y0, y1, ty, za, zb, tz, false, factor)
                } else {
                    int z0 = (int) Math.floor(zIn)
                    int z1 = Math.min(raw.depth - 1, z0 + 1)
                    float tz = (float) (zIn - z0)
                    copyBilinearLine(raw, out.pixels, outBase, y0, y1, ty, z0, z1, tz,
                            mode == MODE_ZERO_COMB, factor)
                }
            }
            if ((oz & 15) == 0) IJ.showProgress(oz, outDepth)
        }
        IJ.showProgress(1.0d)
        return out
    }

    private static void copyBilinearLine(AliasingVolume raw, float[] target, int targetBase,
                                          int y0, int y1, float ty, int z0, int z1, float tz,
                                          boolean zeroMissing, int factor) {
        boolean useZ0 = !zeroMissing || z0 % factor == 0
        boolean useZ1 = !zeroMissing || z1 % factor == 0
        int base00 = (z0 * raw.height + y0) * raw.width
        int base10 = (z0 * raw.height + y1) * raw.width
        int base01 = (z1 * raw.height + y0) * raw.width
        int base11 = (z1 * raw.height + y1) * raw.width
        for (int x = 0; x < raw.width; x++) {
            float a = 0.0f
            float b = 0.0f
            if (useZ0) {
                float v00 = raw.pixels[base00 + x]
                float v10 = raw.pixels[base10 + x]
                a = v00 + (v10 - v00) * ty
            }
            if (useZ1) {
                float v01 = raw.pixels[base01 + x]
                float v11 = raw.pixels[base11 + x]
                b = v01 + (v11 - v01) * ty
            }
            target[targetBase + x] = a + (b - a) * tz
        }
    }

    static AliasingVolume scaledCopy(AliasingVolume source, float scale) {
        AliasingVolume out = new AliasingVolume(source.width, source.height, source.depth)
        for (int i = 0; i < source.pixels.length; i++) out.pixels[i] = source.pixels[i] * scale
        return out
    }

    static AliasingVolume fourierReconstruct(AliasingVolume combDeskewed, int factor,
                                              double xyPixelSize, double acquisitionStep,
                                              double angleDeg, String maskGeometry,
                                              double edgeBins, File spectrumDirectory) {
        int w = combDeskewed.width
        int h = combDeskewed.height
        int d = combDeskewed.depth
        long complexCount = 2L * w * h * d
        if (complexCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException('FFT ROI is too large for JTransforms: ' + complexCount + ' floats.')
        }
        float[] spectrum = new float[(int) complexCount]
        for (int i = 0; i < combDeskewed.pixels.length; i++) spectrum[2 * i] = combDeskewed.pixels[i]

        log(String.format(Locale.US, '3-D complex FFT: %dx%dx%d (%.1f MiB work array) ...',
                w, h, d, complexCount * 4.0d / 1048576.0d))
        FloatFFT_3D fft = new FloatFFT_3D(d, h, w)
        fft.complexForward(spectrum)

        float[] before = spectrumMipX(spectrum, w, h, d)
        boolean diagonal = !'final Z'.equalsIgnoreCase(maskGeometry)
        double theta = Math.toRadians(angleDeg)
        double sinTheta = Math.abs(Math.sin(theta))
        double cosTheta = Math.cos(theta)
        // Sampling every Nth raw acquisition plane shifts a Fourier copy by
        // Delta k = (1/aN, cot(theta)/aN), a=dz/dxy, after Transform.deskew.
        // Its unit normal is (sin(theta), cos(theta)); the central copy's
        // Voronoi slab therefore ends at half the replica spacing.  Multiplying
        // normalized cycles/pixel by d expresses the distance in output-Z bins
        // so maskEdgeBins retains its intuitive meaning.
        double halfBand = diagonal ?
                (xyPixelSize / acquisitionStep) * d /
                        (2.0d * factor * Math.max(1.0e-12d, sinTheta)) :
                (xyPixelSize / acquisitionStep) * d / (2.0d * factor)

        for (int z = 0; z < d; z++) {
            double kz = signedFrequency(z, d) / d
            for (int y = 0; y < h; y++) {
                double distance
                if (diagonal) {
                    double ky = signedFrequency(y, h) / h
                    distance = Math.abs(sinTheta * ky + cosTheta * kz) * d
                } else {
                    distance = Math.abs(kz) * d
                }
                float weight = maskWeight(distance, halfBand, edgeBins)
                if (weight == 1.0f) continue
                int base = 2 * (z * h + y) * w
                for (int x = 0; x < w; x++) {
                    spectrum[base + 2 * x] *= weight
                    spectrum[base + 2 * x + 1] *= weight
                }
            }
        }
        float[] after = spectrumMipX(spectrum, w, h, d)
        saveSpectrumComparison(before, after, h, d, spectrumDirectory)

        fft.complexInverse(spectrum, true)
        AliasingVolume result = new AliasingVolume(w, h, d)
        // A 1/N duty-cycle comb leaves every spectral copy with 1/N amplitude.
        for (int i = 0; i < result.pixels.length; i++) result.pixels[i] = spectrum[2 * i] * factor
        return result
    }

    static double signedFrequency(int index, int size) {
        return index <= size / 2 ? index : index - size
    }

    static float maskWeight(double distance, double halfBand, double edgeBins) {
        if (edgeBins <= 0.0d) return distance < halfBand ? 1.0f : 0.0f
        double inner = Math.max(0.0d, halfBand - edgeBins)
        if (distance <= inner) return 1.0f
        if (distance >= halfBand) return 0.0f
        double phase = (distance - inner) / Math.max(1.0e-12d, halfBand - inner)
        return (float) (0.5d * (1.0d + Math.cos(Math.PI * phase)))
    }

    static float[] spectrumMipX(float[] spectrum, int w, int h, int d) {
        float[] mip = new float[h * d]
        for (int z = 0; z < d; z++) {
            for (int y = 0; y < h; y++) {
                float maxPower = 0.0f
                int base = 2 * (z * h + y) * w
                for (int x = 0; x < w; x++) {
                    float re = spectrum[base + 2 * x]
                    float im = spectrum[base + 2 * x + 1]
                    float power = re * re + im * im
                    if (power > maxPower) maxPower = power
                }
                int shiftedY = (y + h.intdiv(2)) % h
                int shiftedZ = (z + d.intdiv(2)) % d
                mip[shiftedZ * h + shiftedY] = (float) Math.log10(1.0d + Math.sqrt(maxPower))
            }
        }
        return mip
    }

    static void saveSpectrumComparison(float[] before, float[] after, int h, int d, File directory) {
        directory.mkdirs()
        float max = 0.0f
        for (float v : before) if (v > max) max = v
        for (float v : after) if (v > max) max = v
        float[] pixels = new float[(h * 2 + 8) * d]
        int montageWidth = h * 2 + 8
        for (int z = 0; z < d; z++) {
            System.arraycopy(before, z * h, pixels, z * montageWidth, h)
            System.arraycopy(after, z * h, pixels, z * montageWidth + h + 8, h)
        }
        FloatProcessor fp = new FloatProcessor(montageWidth, d, pixels)
        fp.setMinAndMax(0.0d, max)
        saveLabelledPng(fp, new File(directory, 'spectrum_before_after.png'),
                new String[] { 'comb FFT', 'masked FFT' }, new int[] { 4, h + 12 })
    }

    static AliasingMetrics metrics(AliasingVolume reference, AliasingVolume estimate,
                                    String region, double threshold) {
        if (reference.pixels.length != estimate.pixels.length) {
            throw new IllegalArgumentException('Metric volume sizes differ.')
        }
        double sumRef = 0.0d
        double sumEst = 0.0d
        double sumRef2 = 0.0d
        double sumEst2 = 0.0d
        double sumCross = 0.0d
        double sumSquaredError = 0.0d
        double min = Double.POSITIVE_INFINITY
        double max = Double.NEGATIVE_INFINITY
        long n = 0L
        boolean foreground = region == 'signal'

        for (int i = 0; i < reference.pixels.length; i++) {
            double a = reference.pixels[i]
            if (foreground && a < threshold) continue
            double b = estimate.pixels[i]
            double e = b - a
            sumRef += a
            sumEst += b
            sumRef2 += a * a
            sumEst2 += b * b
            sumCross += a * b
            sumSquaredError += e * e
            if (a < min) min = a
            if (a > max) max = a
            n++
        }

        AliasingMetrics result = new AliasingMetrics()
        result.region = region
        result.count = n
        if (n == 0L) {
            result.rmse = result.nrmse = result.psnr = result.pearson = Double.NaN
            return result
        }
        result.rmse = Math.sqrt(sumSquaredError / n)
        result.nrmse = result.rmse / Math.max(1.0e-12d, Math.sqrt(sumRef2 / n))
        double range = Math.max(1.0e-12d, max - min)
        result.psnr = 20.0d * Math.log10(range / Math.max(1.0e-12d, result.rmse))
        double covariance = sumCross - sumRef * sumEst / n
        double varianceRef = sumRef2 - sumRef * sumRef / n
        double varianceEst = sumEst2 - sumEst * sumEst / n
        result.pearson = covariance / Math.sqrt(Math.max(1.0e-24d, varianceRef * varianceEst))
        return result
    }

    static double maximum(AliasingVolume volume) {
        double max = Double.NEGATIVE_INFINITY
        for (float v : volume.pixels) if (v > max) max = v
        return max
    }

    static float[] maxProjection(AliasingVolume volume, String axis) {
        if (axis == 'X') {
            float[] out = new float[volume.height * volume.depth]
            for (int z = 0; z < volume.depth; z++) {
                for (int y = 0; y < volume.height; y++) {
                    float max = Float.NEGATIVE_INFINITY
                    int base = (z * volume.height + y) * volume.width
                    for (int x = 0; x < volume.width; x++) if (volume.pixels[base + x] > max) max = volume.pixels[base + x]
                    out[z * volume.height + y] = max
                }
            }
            return out
        }
        if (axis == 'Z') {
            float[] out = new float[volume.width * volume.height]
            Arrays.fill(out, Float.NEGATIVE_INFINITY)
            for (int z = 0; z < volume.depth; z++) {
                int base = z * volume.width * volume.height
                for (int i = 0; i < out.length; i++) if (volume.pixels[base + i] > out[i]) out[i] = volume.pixels[base + i]
            }
            return out
        }
        throw new IllegalArgumentException('Projection axis must be X or Z.')
    }

    static void saveComparison(AliasingVolume truth, AliasingVolume zeroComb,
                               AliasingVolume linear, AliasingVolume fourier,
                               String axis, File file) {
        float[] a = maxProjection(truth, axis)
        float[] b = maxProjection(zeroComb, axis)
        float[] c = maxProjection(linear, axis)
        float[] d = maxProjection(fourier, axis)
        int panelWidth = axis == 'X' ? truth.height : truth.width
        int panelHeight = axis == 'X' ? truth.depth : truth.height
        int gap = 8
        int width = panelWidth * 4 + gap * 3
        float[] pixels = new float[width * panelHeight]
        for (int y = 0; y < panelHeight; y++) {
            int source = y * panelWidth
            int target = y * width
            System.arraycopy(a, source, pixels, target, panelWidth)
            System.arraycopy(b, source, pixels, target + panelWidth + gap, panelWidth)
            System.arraycopy(c, source, pixels, target + 2 * (panelWidth + gap), panelWidth)
            System.arraycopy(d, source, pixels, target + 3 * (panelWidth + gap), panelWidth)
        }
        FloatProcessor fp = new FloatProcessor(width, panelHeight, pixels)
        double high = percentile(a, 99.8d)
        fp.setMinAndMax(0.0d, Math.max(1.0d, high))
        saveLabelledPng(fp, file,
                new String[] { 'truth', 'zero comb', 'linear', 'Fourier' },
                new int[] { 4, panelWidth + gap + 4, 2 * (panelWidth + gap) + 4,
                        3 * (panelWidth + gap) + 4 })
    }

    static void saveBeadCloseup(AliasingVolume truth, AliasingVolume linear,
                                AliasingVolume fourier, int[] truthPoint,
                                int radiusXY, int radiusZ, File file) {
        AliasingVolume[] volumes = new AliasingVolume[] { truth, linear, fourier }
        String[] labels = new String[] { 'truth', 'linear', 'Fourier' }
        int panelWidth = 2 * radiusXY + 1
        int panelHeight = 2 * radiusZ + 1
        int gap = 4
        int montageWidth = volumes.length * panelWidth + (volumes.length - 1) * gap
        float[] pixels = new float[montageWidth * panelHeight]
        for (int panel = 0; panel < volumes.length; panel++) {
            AliasingVolume volume = volumes[panel]
            int[] center = refinePeak(volume, truthPoint, 6, 6, 6)
            double background = shellMedian(volume, center, radiusXY, radiusXY, radiusZ)
            float[] projection = new float[panelWidth * panelHeight]
            float maximum = 0.0f
            for (int z = -radiusZ; z <= radiusZ; z++) {
                for (int y = -radiusXY; y <= radiusXY; y++) {
                    float value = Float.NEGATIVE_INFINITY
                    for (int x = -radiusXY; x <= radiusXY; x++) {
                        value = Math.max(value, (float) (volume.get(
                                center[0] + x, center[1] + y, center[2] + z) - background))
                    }
                    value = Math.max(0.0f, value)
                    projection[(z + radiusZ) * panelWidth + y + radiusXY] = value
                    maximum = Math.max(maximum, value)
                }
            }
            maximum = Math.max(1.0e-12f, maximum)
            int xOffset = panel * (panelWidth + gap)
            for (int z = 0; z < panelHeight; z++) {
                for (int y = 0; y < panelWidth; y++) {
                    pixels[z * montageWidth + xOffset + y] =
                            (float) (projection[z * panelWidth + y] / maximum)
                }
            }
        }
        FloatProcessor fp = new FloatProcessor(montageWidth, panelHeight, pixels)
        fp.setMinAndMax(0.0d, 1.0d)
        FloatProcessor enlarged = (FloatProcessor) fp.resize(montageWidth * 4, panelHeight * 4, false)
        int[] labelX = new int[labels.length]
        for (int i = 0; i < labels.length; i++) labelX[i] = 4 * i * (panelWidth + gap) + 4
        saveLabelledPng(enlarged, file, labels, labelX)
    }

    static double percentile(float[] values, double percentile) {
        float[] copy = values.clone()
        Arrays.sort(copy)
        int index = (int) Math.round((copy.length - 1) * percentile / 100.0d)
        return copy[Math.max(0, Math.min(copy.length - 1, index))]
    }

    static void saveLabelledPng(FloatProcessor fp, File file, String[] labels, int[] xPositions) {
        file.parentFile.mkdirs()
        ImageProcessor byteImage = fp.convertToByte(true)
        byteImage.setColor(Color.WHITE)
        byteImage.setFont(new Font('SansSerif', Font.BOLD, 12))
        for (int i = 0; i < labels.length; i++) {
            byteImage.drawString(labels[i], xPositions[i], 14)
        }
        new FileSaver(new ImagePlus(file.name, byteImage)).saveAsPng(file.absolutePath)
    }

    static List<int[]> detectBeads(AliasingVolume volume, int maximumCount,
                                   int radiusXY, int radiusZ) {
        float[] mip = maxProjection(volume, 'Z')
        float[] smooth = smooth2d(mip, volume.width, volume.height, 2)
        double background = percentile(smooth, 50.0d)
        float[] deviations = new float[smooth.length]
        for (int i = 0; i < smooth.length; i++) {
            deviations[i] = (float) Math.abs(smooth[i] - background)
        }
        double sigma = 1.4826d * percentile(deviations, 50.0d)
        double threshold = Math.max(background + 8.0d * sigma, percentile(smooth, 99.0d))
        int suppressX = Math.max(6, radiusXY)
        int suppressY = Math.max(6, radiusXY)
        List<int[]> candidates = new ArrayList<int[]>()

        for (int bead = 0; bead < maximumCount * 3; bead++) {
            float best = Float.NEGATIVE_INFINITY
            int bestX = -1
            int bestY = -1
            for (int y = radiusXY; y < volume.height - radiusXY; y++) {
                int base = y * volume.width
                for (int x = radiusXY; x < volume.width - radiusXY; x++) {
                    float value = smooth[base + x]
                    if (value > best) {
                        best = value
                        bestX = x
                        bestY = y
                    }
                }
            }
            if (bestX < 0 || best < threshold) break
            int[] point = refinePeak(volume, new int[] { bestX, bestY, volume.depth.intdiv(2) },
                    3, 3, volume.depth)
            if (point[2] >= radiusZ && point[2] + radiusZ < volume.depth) {
                AliasingBeadMeasurement quality = beadMeasurement(
                        volume, volume, point, radiusXY, radiusZ, 1.0d, candidates.size() + 1)
                if (Double.isFinite(quality.fwhmXUm) && Double.isFinite(quality.fwhmYUm) &&
                        Double.isFinite(quality.fwhmZUm) && quality.snr >= 6.0d) {
                    candidates.add(point)
                    if (candidates.size() >= maximumCount) break
                }
            }
            for (int y = Math.max(0, bestY - suppressY);
                 y <= Math.min(volume.height - 1, bestY + suppressY); y++) {
                int base = y * volume.width
                for (int x = Math.max(0, bestX - suppressX);
                     x <= Math.min(volume.width - 1, bestX + suppressX); x++) {
                    double dx = (x - bestX) / (double) suppressX
                    double dy = (y - bestY) / (double) suppressY
                    if (dx * dx + dy * dy <= 1.0d) smooth[base + x] = Float.NEGATIVE_INFINITY
                }
            }
        }

        // Reject crops containing another accepted peak; otherwise a neighbouring bead
        // biases both the PSF width and its OTF.
        List<int[]> isolated = new ArrayList<int[]>()
        for (int i = 0; i < candidates.size(); i++) {
            int[] a = candidates.get(i)
            boolean neighbor = false
            for (int j = 0; j < candidates.size(); j++) {
                if (i == j) continue
                int[] b = candidates.get(j)
                double dx = (a[0] - b[0]) / (double) (2 * radiusXY)
                double dy = (a[1] - b[1]) / (double) (2 * radiusXY)
                double dz = (a[2] - b[2]) / (double) (2 * radiusZ)
                if (dx * dx + dy * dy + dz * dz <= 1.0d) {
                    neighbor = true
                    break
                }
            }
            if (!neighbor) isolated.add(a)
        }
        log(String.format(Locale.US,
                'Bead detection: background=%.2f, robust sigma=%.2f, threshold=%.2f; %d isolated beads.',
                background, sigma, threshold, isolated.size()))
        return isolated
    }

    static float[] smooth2d(float[] input, int width, int height, int passes) {
        float[] a = input.clone()
        float[] b = new float[input.length]
        for (int pass = 0; pass < passes; pass++) {
            for (int y = 0; y < height; y++) {
                int base = y * width
                for (int x = 0; x < width; x++) {
                    float left = a[base + Math.max(0, x - 1)]
                    float center = a[base + x]
                    float right = a[base + Math.min(width - 1, x + 1)]
                    b[base + x] = 0.25f * left + 0.5f * center + 0.25f * right
                }
            }
            for (int y = 0; y < height; y++) {
                int ym = Math.max(0, y - 1)
                int yp = Math.min(height - 1, y + 1)
                for (int x = 0; x < width; x++) {
                    a[y * width + x] = 0.25f * b[ym * width + x] +
                            0.5f * b[y * width + x] + 0.25f * b[yp * width + x]
                }
            }
        }
        return a
    }

    static int[] refinePeak(AliasingVolume volume, int[] center,
                            int searchX, int searchY, int searchZ) {
        int x0 = Math.max(0, center[0] - searchX)
        int x1 = Math.min(volume.width - 1, center[0] + searchX)
        int y0 = Math.max(0, center[1] - searchY)
        int y1 = Math.min(volume.height - 1, center[1] + searchY)
        int z0 = searchZ >= volume.depth ? 0 : Math.max(0, center[2] - searchZ)
        int z1 = searchZ >= volume.depth ? volume.depth - 1 :
                Math.min(volume.depth - 1, center[2] + searchZ)
        float best = Float.NEGATIVE_INFINITY
        int bx = center[0]
        int by = center[1]
        int bz = Math.max(0, Math.min(volume.depth - 1, center[2]))
        for (int z = z0; z <= z1; z++) {
            for (int y = y0; y <= y1; y++) {
                int base = (z * volume.height + y) * volume.width
                for (int x = x0; x <= x1; x++) {
                    float value = volume.pixels[base + x]
                    if (value > best) {
                        best = value
                        bx = x
                        by = y
                        bz = z
                    }
                }
            }
        }
        return new int[] { bx, by, bz }
    }

    static AliasingBeadMeasurement beadMeasurement(AliasingVolume reference,
                                                     AliasingVolume estimate,
                                                     int[] referencePoint,
                                                     int radiusXY, int radiusZ,
                                                     double pixelUm, int beadIndex) {
        int search = Math.max(3, Math.min(8, radiusXY.intdiv(2)))
        int[] peak = refinePeak(estimate, referencePoint, search, search, search)
        double background = shellMedian(estimate, peak, radiusXY, radiusXY, radiusZ)
        double noise = shellMad(estimate, peak, radiusXY, radiusXY, radiusZ, background)
        double peakSignal = estimate.get(peak[0], peak[1], peak[2]) - background

        AliasingBeadMeasurement result = new AliasingBeadMeasurement()
        result.bead = beadIndex
        result.x = peak[0]
        result.y = peak[1]
        result.z = peak[2]
        result.background = background
        result.snr = peakSignal / Math.max(1.0e-12d, 1.4826d * noise)
        result.fwhmXUm = fwhm(lineProfile(estimate, peak, 0, radiusXY, background)) * pixelUm
        result.fwhmYUm = fwhm(lineProfile(estimate, peak, 1, radiusXY, background)) * pixelUm
        result.fwhmZUm = fwhm(lineProfile(estimate, peak, 2, radiusZ, background)) * pixelUm
        double dx = peak[0] - referencePoint[0]
        double dy = peak[1] - referencePoint[1]
        double dz = peak[2] - referencePoint[2]
        result.shiftUm = Math.sqrt(dx * dx + dy * dy + dz * dz) * pixelUm
        double[] shape = shapeMetrics(reference, estimate, referencePoint, peak, radiusXY, radiusZ)
        result.shapeNrmse = shape[0]
        result.shapePearson = shape[1]
        return result
    }

    static double shellMedian(AliasingVolume volume, int[] center,
                              int radiusX, int radiusY, int radiusZ) {
        float[] values = shellValues(volume, center, radiusX, radiusY, radiusZ)
        return percentile(values, 50.0d)
    }

    static double shellMad(AliasingVolume volume, int[] center,
                           int radiusX, int radiusY, int radiusZ, double median) {
        float[] values = shellValues(volume, center, radiusX, radiusY, radiusZ)
        for (int i = 0; i < values.length; i++) values[i] = (float) Math.abs(values[i] - median)
        return percentile(values, 50.0d)
    }

    static float[] shellValues(AliasingVolume volume, int[] center,
                               int radiusX, int radiusY, int radiusZ) {
        int nx = 2 * radiusX + 1
        int ny = 2 * radiusY + 1
        int nz = 2 * radiusZ + 1
        int count = 2 * nx * ny + 2 * Math.max(0, nz - 2) * nx +
                2 * Math.max(0, nz - 2) * Math.max(0, ny - 2)
        float[] values = new float[count]
        int index = 0
        for (int z = -radiusZ; z <= radiusZ; z++) {
            for (int y = -radiusY; y <= radiusY; y++) {
                for (int x = -radiusX; x <= radiusX; x++) {
                    if (Math.abs(x) != radiusX && Math.abs(y) != radiusY && Math.abs(z) != radiusZ) continue
                    values[index++] = volume.get(center[0] + x, center[1] + y, center[2] + z)
                }
            }
        }
        return index == values.length ? values : Arrays.copyOf(values, index)
    }

    static double[] lineProfile(AliasingVolume volume, int[] center, int axis,
                                int radius, double background) {
        double[] profile = new double[2 * radius + 1]
        for (int offset = -radius; offset <= radius; offset++) {
            double sum = 0.0d
            int count = 0
            for (int a = -1; a <= 1; a++) {
                for (int b = -1; b <= 1; b++) {
                    int x = center[0]
                    int y = center[1]
                    int z = center[2]
                    if (axis == 0) { x += offset; y += a; z += b }
                    else if (axis == 1) { x += a; y += offset; z += b }
                    else { x += a; y += b; z += offset }
                    sum += volume.get(x, y, z) - background
                    count++
                }
            }
            profile[offset + radius] = sum / count
        }
        double[] smooth = profile.clone()
        for (int i = 1; i < profile.length - 1; i++) {
            smooth[i] = 0.25d * profile[i - 1] + 0.5d * profile[i] + 0.25d * profile[i + 1]
        }
        return smooth
    }

    static double fwhm(double[] profile) {
        int peak = 0
        for (int i = 1; i < profile.length; i++) if (profile[i] > profile[peak]) peak = i
        double half = 0.5d * profile[peak]
        int left = peak
        while (left > 0 && profile[left] > half) left--
        int right = peak
        while (right < profile.length - 1 && profile[right] > half) right++
        if (left == 0 || right == profile.length - 1 || right <= left) return Double.NaN
        double leftCross = crossing(left, profile[left], left + 1, profile[left + 1], half)
        double rightCross = crossing(right - 1, profile[right - 1], right, profile[right], half)
        return rightCross - leftCross
    }

    static double crossing(int x0, double y0, int x1, double y1, double target) {
        if (Math.abs(y1 - y0) < 1.0e-12d) return 0.5d * (x0 + x1)
        return x0 + (target - y0) * (x1 - x0) / (y1 - y0)
    }

    static double[] shapeMetrics(AliasingVolume reference, AliasingVolume estimate,
                                 int[] centerA, int[] centerB, int radiusXY, int radiusZ) {
        double backgroundA = shellMedian(reference, centerA, radiusXY, radiusXY, radiusZ)
        double backgroundB = shellMedian(estimate, centerB, radiusXY, radiusXY, radiusZ)
        double peak = reference.get(centerA[0], centerA[1], centerA[2]) - backgroundA
        double threshold = 0.10d * peak
        double sumAB = 0.0d
        double sumB2 = 0.0d
        for (int z = -radiusZ; z <= radiusZ; z++) {
            for (int y = -radiusXY; y <= radiusXY; y++) {
                for (int x = -radiusXY; x <= radiusXY; x++) {
                    double a = reference.get(centerA[0] + x, centerA[1] + y, centerA[2] + z) - backgroundA
                    if (a < threshold) continue
                    double b = estimate.get(centerB[0] + x, centerB[1] + y, centerB[2] + z) - backgroundB
                    sumAB += a * b
                    sumB2 += b * b
                }
            }
        }
        double scale = sumAB / Math.max(1.0e-24d, sumB2)
        double sumA = 0.0d
        double sumB = 0.0d
        double sumA2 = 0.0d
        double sumScaledB2 = 0.0d
        double sumCross = 0.0d
        double sumError2 = 0.0d
        int count = 0
        for (int z = -radiusZ; z <= radiusZ; z++) {
            for (int y = -radiusXY; y <= radiusXY; y++) {
                for (int x = -radiusXY; x <= radiusXY; x++) {
                    double a = reference.get(centerA[0] + x, centerA[1] + y, centerA[2] + z) - backgroundA
                    if (a < threshold) continue
                    double b = scale * (estimate.get(centerB[0] + x, centerB[1] + y, centerB[2] + z) - backgroundB)
                    double error = b - a
                    sumA += a
                    sumB += b
                    sumA2 += a * a
                    sumScaledB2 += b * b
                    sumCross += a * b
                    sumError2 += error * error
                    count++
                }
            }
        }
        if (count < 2) return new double[] { Double.NaN, Double.NaN }
        double nrmse = Math.sqrt(sumError2 / count) /
                Math.max(1.0e-12d, Math.sqrt(sumA2 / count))
        double covariance = sumCross - sumA * sumB / count
        double varianceA = sumA2 - sumA * sumA / count
        double varianceB = sumScaledB2 - sumB * sumB / count
        double correlation = covariance / Math.sqrt(Math.max(1.0e-24d, varianceA * varianceB))
        return new double[] { nrmse, correlation }
    }

    static void saveEmpiricalOtf(AliasingVolume truth, List<int[]> points,
                                 int radiusXY, int radiusZ, double pixelUm,
                                 double angleDeg, File output) {
        if (points.isEmpty()) return
        int cropW = 2 * radiusXY + 1
        int cropH = cropW
        int cropD = 2 * radiusZ + 1
        int pad = 1
        int requested = 4 * Math.max(cropW, Math.max(cropH, cropD))
        while (pad < requested) pad *= 2
        pad = Math.min(256, pad)
        long voxels = (long) pad * pad * pad
        if (2L * voxels > Integer.MAX_VALUE) throw new IllegalArgumentException('OTF padding is too large.')
        float[] averagePsf = new float[(int) voxels]
        int used = 0
        for (int[] original : points) {
            int[] point = refinePeak(truth, original, 3, 3, 3)
            double background = shellMedian(truth, point, radiusXY, radiusXY, radiusZ)
            double total = 0.0d
            for (int z = -radiusZ; z <= radiusZ; z++) {
                for (int y = -radiusXY; y <= radiusXY; y++) {
                    for (int x = -radiusXY; x <= radiusXY; x++) {
                        total += Math.max(0.0d, truth.get(point[0] + x, point[1] + y, point[2] + z) - background)
                    }
                }
            }
            if (total <= 0.0d) continue
            for (int z = -radiusZ; z <= radiusZ; z++) {
                int pz = (z + pad) % pad
                for (int y = -radiusXY; y <= radiusXY; y++) {
                    int py = (y + pad) % pad
                    for (int x = -radiusXY; x <= radiusXY; x++) {
                        int px = (x + pad) % pad
                        float signal = (float) (Math.max(0.0d,
                                truth.get(point[0] + x, point[1] + y, point[2] + z) - background) / total)
                        averagePsf[(pz * pad + py) * pad + px] += signal
                    }
                }
            }
            used++
        }
        if (used == 0) return
        for (int i = 0; i < averagePsf.length; i++) averagePsf[i] = (float) (averagePsf[i] / used)

        float[] spectrum = new float[(int) (2L * voxels)]
        for (int i = 0; i < averagePsf.length; i++) spectrum[2 * i] = averagePsf[i]
        FloatFFT_3D fft = new FloatFFT_3D(pad, pad, pad)
        fft.complexForward(spectrum)
        float dc = Math.max(1.0e-12f, (float) Math.hypot(spectrum[0], spectrum[1]))
        float[] cross = new float[pad * pad]
        double sumYY = 0.0d
        double sumZZ = 0.0d
        double sumYZ = 0.0d
        double sumWeight = 0.0d
        double[] supportThresholds = new double[] { 0.02d, 0.05d, 0.10d }
        double[] supportY = new double[supportThresholds.length]
        double[] supportZ = new double[supportThresholds.length]
        double[] supportNormal = new double[supportThresholds.length]
        double sinTheta = Math.abs(Math.sin(Math.toRadians(angleDeg)))
        double cosTheta = Math.cos(Math.toRadians(angleDeg))
        for (int z = 0; z < pad; z++) {
            double fz = signedFrequency(z, pad) / (pad * pixelUm)
            for (int y = 0; y < pad; y++) {
                double fy = signedFrequency(y, pad) / (pad * pixelUm)
                int index = 2 * ((z * pad + y) * pad)
                double magnitude = Math.hypot(spectrum[index], spectrum[index + 1]) / dc
                int shiftedY = (y + pad.intdiv(2)) % pad
                int shiftedZ = (z + pad.intdiv(2)) % pad
                cross[shiftedZ * pad + shiftedY] = (float) Math.log10(1.0d + 999.0d * magnitude)
                for (int thresholdIndex = 0; thresholdIndex < supportThresholds.length; thresholdIndex++) {
                    if (magnitude >= supportThresholds[thresholdIndex]) {
                        supportY[thresholdIndex] = Math.max(supportY[thresholdIndex], Math.abs(fy))
                        supportZ[thresholdIndex] = Math.max(supportZ[thresholdIndex], Math.abs(fz))
                        supportNormal[thresholdIndex] = Math.max(supportNormal[thresholdIndex],
                                Math.abs(sinTheta * fy + cosTheta * fz))
                    }
                }
                if (magnitude >= 0.05d) {
                    double weight = magnitude * magnitude
                    sumYY += weight * fy * fy
                    sumZZ += weight * fz * fz
                    sumYZ += weight * fy * fz
                    sumWeight += weight
                }
            }
        }
        FloatProcessor fp = new FloatProcessor(pad, pad, cross)
        fp.setMinAndMax(0.0d, 3.0d)
        saveLabelledPng(fp, new File(output, 'empirical_otf_yz_log.png'),
                new String[] { 'empirical OTF: kY horizontal, kZ vertical (log)' }, new int[] { 4 })

        PrintWriter profile = new PrintWriter(new File(output, 'empirical_otf_profiles.csv'), 'UTF-8')
        try {
            profile.println('axis,index,signed_frequency_cycles_per_um,central_mtf')
            for (int axis = 0; axis < 3; axis++) {
                String name = axis == 0 ? 'X' : (axis == 1 ? 'Y' : 'Z')
                for (int i = 0; i < pad; i++) {
                    int x = axis == 0 ? i : 0
                    int y = axis == 1 ? i : 0
                    int z = axis == 2 ? i : 0
                    int index = 2 * ((z * pad + y) * pad + x)
                    double magnitude = Math.hypot(spectrum[index], spectrum[index + 1]) / dc
                    double frequency = signedFrequency(i, pad) / (pad * pixelUm)
                    profile.println(String.format(Locale.US, '%s,%d,%.9g,%.9g', name, i, frequency, magnitude))
                }
            }
        } finally {
            profile.close()
        }

        double trace = (sumYY + sumZZ) / Math.max(1.0e-24d, sumWeight)
        double difference = (sumYY - sumZZ) / Math.max(1.0e-24d, sumWeight)
        double crossMoment = sumYZ / Math.max(1.0e-24d, sumWeight)
        double root = Math.sqrt(Math.max(0.0d, 0.25d * difference * difference + crossMoment * crossMoment))
        double eigenMajor = 0.5d * trace + root
        double eigenMinor = 0.5d * trace - root
        double tilt = 0.5d * Math.toDegrees(Math.atan2(2.0d * crossMoment, difference))
        PrintWriter summary = new PrintWriter(new File(output, 'empirical_otf_summary.txt'), 'UTF-8')
        try {
            summary.println('accepted_beads=' + used)
            summary.println('fft_padding=' + pad)
            summary.println('voxel_um=' + String.format(Locale.US, '%.9g', pixelUm))
            for (int i = 0; i < supportThresholds.length; i++) {
                int percent = (int) Math.round(100.0d * supportThresholds[i])
                summary.println('support_half_width_ky_at_' + percent + 'pct_cycles_per_um=' +
                        String.format(Locale.US, '%.9g', supportY[i]))
                summary.println('support_half_width_kz_at_' + percent + 'pct_cycles_per_um=' +
                        String.format(Locale.US, '%.9g', supportZ[i]))
                summary.println('support_half_width_mask_normal_at_' + percent + 'pct_cycles_per_um=' +
                        String.format(Locale.US, '%.9g', supportNormal[i]))
            }
            summary.println('weighted_principal_tilt_deg_from_ky=' + String.format(Locale.US, '%.9g', tilt))
            summary.println('weighted_major_rms_frequency=' + String.format(Locale.US, '%.9g', Math.sqrt(Math.max(0.0d, eigenMajor))))
            summary.println('weighted_minor_rms_frequency=' + String.format(Locale.US, '%.9g', Math.sqrt(Math.max(0.0d, eigenMinor))))
        } finally {
            summary.close()
        }
    }

    static double medianFinite(List<Double> values) {
        List<Double> finite = new ArrayList<Double>()
        for (Double value : values) if (value != null && Double.isFinite(value.doubleValue())) finite.add(value)
        if (finite.isEmpty()) return Double.NaN
        Collections.sort(finite)
        int middle = finite.size().intdiv(2)
        return finite.size() % 2 == 0 ? 0.5d * (finite.get(middle - 1) + finite.get(middle)) : finite.get(middle)
    }

    static void saveFloatVolume(AliasingVolume volume, File file) {
        file.parentFile.mkdirs()
        ImageStack stack = new ImageStack(volume.width, volume.height)
        int plane = volume.width * volume.height
        for (int z = 0; z < volume.depth; z++) {
            float[] pixels = new float[plane]
            System.arraycopy(volume.pixels, z * plane, pixels, 0, plane)
            stack.addSlice(new FloatProcessor(volume.width, volume.height, pixels))
        }
        new FileSaver(new ImagePlus(file.name, stack)).saveAsTiffStack(file.absolutePath)
    }
}

Map<String, Object> vars = (Map<String, Object>) binding.variables
File input = vars.containsKey('inputTiff') && vars.get('inputTiff') != null ?
        (File) vars.get('inputTiff') :
        new File('E:/OPM/3_timelapse_0/3_timelapse_Position0001_Time000001_Channel0001_Frames_1_451.tiff')
File output = vars.containsKey('outputDirectory') && vars.get('outputDirectory') != null ?
        (File) vars.get('outputDirectory') : new File('D:/Git/OPM_toolset/output/opm_aliasing_poc')
String factorsValue = vars.containsKey('factorsText') ? vars.get('factorsText').toString() : '2,3,4'
int x0 = vars.containsKey('rawXStart') ? ((Number) vars.get('rawXStart')).intValue() : 1800
int nx = vars.containsKey('rawXWidth') ? ((Number) vars.get('rawXWidth')).intValue() : 320
int y0 = vars.containsKey('rawYStart') ? ((Number) vars.get('rawYStart')).intValue() : 128
int ny = vars.containsKey('rawYHeight') ? ((Number) vars.get('rawYHeight')).intValue() : 256
int z0 = vars.containsKey('rawZStart') ? ((Number) vars.get('rawZStart')).intValue() : 184
int nz = vars.containsKey('rawZDepth') ? ((Number) vars.get('rawZDepth')).intValue() : 224
double xy = vars.containsKey('xyPixelSizeUm') ? ((Number) vars.get('xyPixelSizeUm')).doubleValue() : 0.116d
double dz = vars.containsKey('zStepUm') ? ((Number) vars.get('zStepUm')).doubleValue() : 0.265d
double angle = vars.containsKey('opmAngleDeg') ? ((Number) vars.get('opmAngleDeg')).doubleValue() : 25.0d
int targetH = vars.containsKey('fftHeight') ? ((Number) vars.get('fftHeight')).intValue() : 640
int targetD = vars.containsKey('fftDepth') ? ((Number) vars.get('fftDepth')).intValue() : 96
String maskGeometryValue = vars.containsKey('maskGeometry') ? vars.get('maskGeometry').toString() : 'diagonal'
double edgeBins = vars.containsKey('maskEdgeBins') ? ((Number) vars.get('maskEdgeBins')).doubleValue() : 0.0d
boolean saveVolumes = vars.containsKey('saveFullVolumes') && ((Boolean) vars.get('saveFullVolumes')).booleanValue()
boolean doBeadAnalysis = vars.containsKey('analyzeBeads') && ((Boolean) vars.get('analyzeBeads')).booleanValue()
int maxBeads = vars.containsKey('maximumBeads') ? ((Number) vars.get('maximumBeads')).intValue() : 24
int radiusXY = vars.containsKey('beadRadiusXY') ? ((Number) vars.get('beadRadiusXY')).intValue() : 12
int radiusZ = vars.containsKey('beadRadiusZ') ? ((Number) vars.get('beadRadiusZ')).intValue() : 12
double exposureMs = vars.containsKey('exposureTimeMs') ? ((Number) vars.get('exposureTimeMs')).doubleValue() : 0.0d
double fwhmLimit = vars.containsKey('fwhmChangeLimitPercent') ?
        ((Number) vars.get('fwhmChangeLimitPercent')).doubleValue() : 5.0d

// A command-line validation profile used by this repository's reproducibility test.
// It deliberately lives behind a JVM property so the Script Editor defaults above
// remain the smaller biological ROI.  Run ImageJ with
// -Dopm.alias.profile=beads to exercise the supplied calibration stack without
// having to encode Windows drive-letter paths in SciJava's --run expression.
if ('beads'.equalsIgnoreCase(System.getProperty('opm.alias.profile', ''))) {
    input = new File('E:/OPM/4_beads_for_overlay_0/4_beads_for_overlay_Position0001_Time000001_Channel0001_Frames_1_451.tiff')
    output = new File('D:/Git/OPM_toolset/output/opm_aliasing_beads_report')
    factorsValue = '2,3,4,5,6'
    x0 = 800
    nx = 600
    y0 = 280
    ny = 128
    z0 = 0
    nz = 192
    xy = 0.116d
    dz = 0.265d
    angle = 33.5d
    targetH = 0
    targetD = 0
    maskGeometryValue = 'diagonal'
    edgeBins = 0.0d
    saveVolumes = false
    doBeadAnalysis = true
    maxBeads = 24
    radiusXY = 12
    radiusZ = 12
    exposureMs = 100.0d
    fwhmLimit = 5.0d
}

List<Integer> factors = new ArrayList<Integer>()
for (String token : factorsValue.split('[,; ]+')) {
    if (token.trim().isEmpty()) continue
    int value = Integer.parseInt(token.trim())
    if (value < 2) throw new IllegalArgumentException('Every undersampling factor must be >= 2.')
    factors.add(value)
}
if (factors.isEmpty()) throw new IllegalArgumentException('No undersampling factors were supplied.')
if (!input.isFile()) throw new FileNotFoundException('Input TIFF not found: ' + input.absolutePath)
output.mkdirs()

long started = System.nanoTime()
AliasingReconstruction.log('')
AliasingReconstruction.log('OPM Fourier aliasing reconstruction test')
AliasingReconstruction.log('Input: ' + input.absolutePath)
AliasingReconstruction.log('Output: ' + output.absolutePath)
AliasingReconstruction.log(String.format(Locale.US,
        'Geometry: xy=%.6f um, dz=%.6f um, angle=%.3f deg; factors=%s',
        xy, dz, angle, factors.toString()))

int acquisitionPlanes = FastTiffReader.parse(input).depth()
long rawReadStarted = System.nanoTime()
AliasingVolume raw = AliasingReconstruction.readRawRoi(input, x0, nx, y0, ny, z0, nz)
double rawReadSeconds = (System.nanoTime() - rawReadStarted) / 1.0e9d
int[] fullShape = AliasingReconstruction.deskewedShape(raw, xy, dz, angle)
AliasingReconstruction.log(String.format(Locale.US,
        'Full deskewed ROI geometry=%dx%dx%d; FFT crop request=%dx%d.',
        fullShape[0], fullShape[1], fullShape[2], targetH, targetD))

AliasingReconstruction.log('Deskewing untouched ground truth ...')
long truthDeskewStarted = System.nanoTime()
AliasingVolume truth = AliasingReconstruction.deskew(raw, xy, dz, angle,
        targetH, targetD, AliasingReconstruction.MODE_TRUTH, 1)
double truthDeskewSeconds = (System.nanoTime() - truthDeskewStarted) / 1.0e9d
double signalThreshold = 0.10d * AliasingReconstruction.maximum(truth)

List<int[]> beadPoints = new ArrayList<int[]>()
List<AliasingBeadMeasurement> truthBeads = new ArrayList<AliasingBeadMeasurement>()
PrintWriter beadWriter = null
PrintWriter speedWriter = null
PrintWriter timingWriter = new PrintWriter(new File(output, 'timings.csv'), 'UTF-8')
timingWriter.println('factor,raw_roi_read_seconds,truth_deskew_seconds,zero_comb_deskew_seconds,linear_deskew_seconds,fourier_fft_mask_ifft_seconds,metrics_plots_seconds,total_factor_seconds')
if (doBeadAnalysis) {
    beadPoints = AliasingReconstruction.detectBeads(truth, maxBeads, radiusXY, radiusZ)
    if (beadPoints.isEmpty()) {
        throw new IllegalArgumentException('Bead analysis was requested, but no isolated bead passed detection and SNR filters.')
    }
    beadWriter = new PrintWriter(new File(output, 'bead_metrics.csv'), 'UTF-8')
    beadWriter.println('factor,method,bead,x,y,z,background,snr,fwhm_x_um,fwhm_y_um,fwhm_z_um,peak_shift_um,shape_nrmse,shape_pearson')
    for (int i = 0; i < beadPoints.size(); i++) {
        AliasingBeadMeasurement measurement = AliasingReconstruction.beadMeasurement(
                truth, truth, beadPoints.get(i), radiusXY, radiusZ, xy, i + 1)
        truthBeads.add(measurement)
        beadWriter.println(measurement.csv(1, 'truth'))
    }
    beadWriter.flush()
    AliasingReconstruction.saveEmpiricalOtf(truth, beadPoints, radiusXY, radiusZ, xy, angle, output)
    speedWriter = new PrintWriter(new File(output, 'acquisition_speed_quality.csv'), 'UTF-8')
    speedWriter.println('factor,method,new_z_step_um,kept_planes,total_planes,ideal_speed_gain,mask_half_width_normal_cycles_per_um,exposure_only_seconds,median_fwhm_x_um,median_fwhm_y_um,median_fwhm_z_um,fwhm_x_change_percent,fwhm_y_change_percent,fwhm_z_change_percent,worst_abs_fwhm_change_percent,median_shape_nrmse,median_shape_pearson,median_peak_shift_um,passes_fwhm_limit')
}

double truthFwhmX = doBeadAnalysis ? AliasingReconstruction.medianFinite(
        truthBeads.collect { AliasingBeadMeasurement b -> b.fwhmXUm } as List<Double>) : Double.NaN
double truthFwhmY = doBeadAnalysis ? AliasingReconstruction.medianFinite(
        truthBeads.collect { AliasingBeadMeasurement b -> b.fwhmYUm } as List<Double>) : Double.NaN
double truthFwhmZ = doBeadAnalysis ? AliasingReconstruction.medianFinite(
        truthBeads.collect { AliasingBeadMeasurement b -> b.fwhmZUm } as List<Double>) : Double.NaN

File metricsFile = new File(output, 'metrics.csv')
PrintWriter metricsWriter = new PrintWriter(metricsFile, 'UTF-8')
metricsWriter.println('factor,method,region,voxels,rmse,nrmse,psnr_db,pearson')
try {
    for (int factor : factors) {
        long factorStarted = System.nanoTime()
        File factorDir = new File(output, 'factor_' + factor)
        factorDir.mkdirs()
        AliasingReconstruction.log('')
        AliasingReconstruction.log(factor + 'x artificial undersampling')
        AliasingReconstruction.log('Deskewing zero-interleaved raw planes ...')
        long stageStarted = System.nanoTime()
        AliasingVolume comb = AliasingReconstruction.deskew(raw, xy, dz, angle,
                truth.height, truth.depth, AliasingReconstruction.MODE_ZERO_COMB, factor)
        AliasingVolume scaledComb = AliasingReconstruction.scaledCopy(comb, (float) factor)
        double zeroCombDeskewSeconds = (System.nanoTime() - stageStarted) / 1.0e9d

        AliasingReconstruction.log('Deskewing raw-Z linear interpolation baseline ...')
        stageStarted = System.nanoTime()
        AliasingVolume linear = AliasingReconstruction.deskew(raw, xy, dz, angle,
                truth.height, truth.depth, AliasingReconstruction.MODE_LINEAR, factor)
        double linearDeskewSeconds = (System.nanoTime() - stageStarted) / 1.0e9d

        AliasingReconstruction.log('Applying post-deskew central Fourier band and inverse FFT ...')
        stageStarted = System.nanoTime()
        AliasingVolume reconstructed = AliasingReconstruction.fourierReconstruct(
                comb, factor, xy, dz, angle, maskGeometryValue, edgeBins, factorDir)
        double fourierSeconds = (System.nanoTime() - stageStarted) / 1.0e9d
        comb = null

        long metricsAndPlotsStarted = System.nanoTime()

        for (String method : new String[] { 'zero_comb', 'linear', 'fourier' }) {
            AliasingVolume estimate = method == 'zero_comb' ? scaledComb :
                    (method == 'linear' ? linear : reconstructed)
            for (String region : new String[] { 'all', 'signal' }) {
                AliasingMetrics m = AliasingReconstruction.metrics(truth, estimate, region, signalThreshold)
                metricsWriter.println(m.csv(Integer.toString(factor), method))
                AliasingReconstruction.log(String.format(Locale.US,
                        '%dx %-9s %-6s: NRMSE=%.4f PSNR=%.2f dB Pearson=%.4f (n=%d)',
                        factor, method, region, m.nrmse, m.psnr, m.pearson, m.count))
            }
            if (doBeadAnalysis) {
                List<AliasingBeadMeasurement> measurements = new ArrayList<AliasingBeadMeasurement>()
                for (int i = 0; i < beadPoints.size(); i++) {
                    AliasingBeadMeasurement measurement = AliasingReconstruction.beadMeasurement(
                            truth, estimate, beadPoints.get(i), radiusXY, radiusZ, xy, i + 1)
                    measurements.add(measurement)
                    beadWriter.println(measurement.csv(factor, method))
                }
                double fwhmX = AliasingReconstruction.medianFinite(
                        measurements.collect { AliasingBeadMeasurement b -> b.fwhmXUm } as List<Double>)
                double fwhmY = AliasingReconstruction.medianFinite(
                        measurements.collect { AliasingBeadMeasurement b -> b.fwhmYUm } as List<Double>)
                double fwhmZ = AliasingReconstruction.medianFinite(
                        measurements.collect { AliasingBeadMeasurement b -> b.fwhmZUm } as List<Double>)
                double changeX = 100.0d * (fwhmX / truthFwhmX - 1.0d)
                double changeY = 100.0d * (fwhmY / truthFwhmY - 1.0d)
                double changeZ = 100.0d * (fwhmZ / truthFwhmZ - 1.0d)
                double worstChange = Math.max(Math.abs(changeX), Math.max(Math.abs(changeY), Math.abs(changeZ)))
                double shapeNrmse = AliasingReconstruction.medianFinite(
                        measurements.collect { AliasingBeadMeasurement b -> b.shapeNrmse } as List<Double>)
                double shapePearson = AliasingReconstruction.medianFinite(
                        measurements.collect { AliasingBeadMeasurement b -> b.shapePearson } as List<Double>)
                double shift = AliasingReconstruction.medianFinite(
                        measurements.collect { AliasingBeadMeasurement b -> b.shiftUm } as List<Double>)
                int keptPlanes = (acquisitionPlanes - 1).intdiv(factor) + 1
                double speedGain = acquisitionPlanes / (double) keptPlanes
                double maskHalfWidthKz = 'final Z'.equalsIgnoreCase(maskGeometryValue) ?
                        1.0d / (2.0d * factor * dz) :
                        1.0d / (2.0d * factor * dz * Math.abs(Math.sin(Math.toRadians(angle))))
                // Sum of the camera exposure setting over retained planes. This is not
                // measured wall time, and equals illumination-on time only when the
                // excitation is gated for exactly the camera exposure interval.
                double exposureSeconds = exposureMs > 0.0d ? keptPlanes * exposureMs / 1000.0d : Double.NaN
                boolean passes = worstChange <= fwhmLimit
                speedWriter.println(String.format(Locale.US,
                        '%d,%s,%.9g,%d,%d,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%s',
                        factor, method, factor * dz, keptPlanes, acquisitionPlanes, speedGain, maskHalfWidthKz, exposureSeconds,
                        fwhmX, fwhmY, fwhmZ, changeX, changeY, changeZ, worstChange,
                        shapeNrmse, shapePearson, shift, Boolean.toString(passes)))
                AliasingReconstruction.log(String.format(Locale.US,
                        '%dx %-7s beads: FWHM=(%.3f, %.3f, %.3f) um; changes=(%+.1f%%, %+.1f%%, %+.1f%%); shape NRMSE=%.3f.',
                        factor, method, fwhmX, fwhmY, fwhmZ, changeX, changeY, changeZ, shapeNrmse))
            }
        }
        metricsWriter.flush()
        if (beadWriter != null) beadWriter.flush()
        if (speedWriter != null) speedWriter.flush()

        AliasingReconstruction.saveComparison(truth, scaledComb, linear, reconstructed,
                'X', new File(factorDir, 'comparison_maxX.png'))
        AliasingReconstruction.saveComparison(truth, scaledComb, linear, reconstructed,
                'Z', new File(factorDir, 'comparison_maxZ.png'))
        if (doBeadAnalysis) {
            AliasingReconstruction.saveBeadCloseup(truth, linear, reconstructed,
                    beadPoints.get(0), radiusXY, radiusZ,
                    new File(factorDir, 'bead_1_closeup_maxX.png'))
        }
        if (saveVolumes) {
            AliasingReconstruction.saveFloatVolume(linear, new File(factorDir, 'linear_baseline.tif'))
            AliasingReconstruction.saveFloatVolume(reconstructed, new File(factorDir, 'fourier_reconstruction.tif'))
        }
        double elapsed = (System.nanoTime() - factorStarted) / 1.0e9d
        double metricsAndPlotsSeconds = (System.nanoTime() - metricsAndPlotsStarted) / 1.0e9d
        timingWriter.println(String.format(Locale.US,
                '%d,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g',
                factor, rawReadSeconds, truthDeskewSeconds, zeroCombDeskewSeconds,
                linearDeskewSeconds, fourierSeconds, metricsAndPlotsSeconds, elapsed))
        timingWriter.flush()
        AliasingReconstruction.log(String.format(Locale.US, '%dx finished in %.2f s.', factor, elapsed))
        scaledComb = null
        linear = null
        reconstructed = null
        System.gc()
    }
} finally {
    metricsWriter.close()
    if (beadWriter != null) beadWriter.close()
    if (speedWriter != null) speedWriter.close()
    timingWriter.close()
}

if (saveVolumes) {
    AliasingReconstruction.saveFloatVolume(truth, new File(output, 'ground_truth.tif'))
}
PrintWriter runWriter = new PrintWriter(new File(output, 'run.txt'), 'UTF-8')
try {
    runWriter.println('input=' + input.absolutePath)
    runWriter.println(String.format(Locale.US, 'raw_roi_zero_based=x:%d+%d,y:%d+%d,z:%d+%d', x0, nx, y0, ny, z0, nz))
    runWriter.println(String.format(Locale.US, 'xy_pixel_um=%.9g', xy))
    runWriter.println(String.format(Locale.US, 'z_step_um=%.9g', dz))
    runWriter.println(String.format(Locale.US, 'opm_angle_deg=%.9g', angle))
    runWriter.println('mask_geometry=' + maskGeometryValue)
    runWriter.println(String.format(Locale.US, 'mask_edge_bins=%.9g', edgeBins))
    runWriter.println('factors=' + factors.toString())
    runWriter.println(String.format(Locale.US, 'deskewed_fft_roi=%dx%dx%d', truth.width, truth.height, truth.depth))
    runWriter.println('acquisition_planes=' + acquisitionPlanes)
    runWriter.println(String.format(Locale.US, 'signal_metric_threshold=%.9g', signalThreshold))
    runWriter.println('bead_analysis=' + Boolean.toString(doBeadAnalysis))
    if (doBeadAnalysis) {
        runWriter.println('accepted_beads=' + beadPoints.size())
        runWriter.println(String.format(Locale.US, 'truth_median_fwhm_um=%.9g,%.9g,%.9g',
                truthFwhmX, truthFwhmY, truthFwhmZ))
        runWriter.println(String.format(Locale.US, 'fwhm_change_limit_percent=%.9g', fwhmLimit))
        runWriter.println(String.format(Locale.US, 'exposure_time_ms=%.9g', exposureMs))
    }
} finally {
    runWriter.close()
}

double totalSeconds = (System.nanoTime() - started) / 1.0e9d
AliasingReconstruction.log('Metrics: ' + metricsFile.absolutePath)
AliasingReconstruction.log(String.format(Locale.US, 'All factors completed in %.2f s.', totalSeconds))
IJ.showStatus('OPM Fourier aliasing test complete')
