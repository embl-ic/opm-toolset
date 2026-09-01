#@ File   (label = "raw OPM volume (.tiff)", style = "file")           rawFile
#@ File   (label = "output folder",          style = "directory")      outDir
#@ Double (label = "XY pixel size (um)",     value = 0.116)            xyPix
#@ Double (label = "Z step size (um)",       value = 0.265)            zStep
#@ Double (label = "OPM angle (degrees)",    value = 25.0)             angle
#@ Integer(label = "Deflate level (0-9)",    value = 1)                level

/*
 * Deskew one raw OPM volume, write it both uncompressed and Deflate compressed,
 * read both back and compare - so the size and time saving can be seen on your
 * own data rather than taken on trust.
 *
 * Needs an OPM_Toolset jar that contains FastTiffWriter. Reading a Deflate TIFF
 * needs nothing special: ImageJ decodes it natively.
 *
 * Typical acquisition settings, from ExperimentalParameters.txt:
 *   "Pixel size at object /um"  -> XY pixel size
 *   "Step size /um"             -> Z step size
 *   "Tilt angle"                -> OPM angle
 */

import de.embl.iclm.CPU
import de.embl.iclm.FastTiffWriter
import de.embl.iclm.GPU
import de.embl.iclm.Transform
import de.embl.iclm.VolumeIO
import ij.IJ
import ij.io.FileSaver

def mb = { long b -> b < 1048576 ? String.format("%.0f KB", b / 1024.0)
                                 : String.format("%.1f MB", b / 1048576.0) }

def raw = VolumeIO.openFast(rawFile.getAbsolutePath())
if (raw == null) raw = VolumeIO.open(rawFile.getAbsolutePath())
if (raw == null) throw new RuntimeException("Could not read " + rawFile)
IJ.log("raw          ${raw.getWidth()} x ${raw.getHeight()} x ${raw.getStackSize()}  (${mb(rawFile.length())} on disk)")

def matrix = Transform.deskew(zStep, xyPix, angle, raw.getHeight())
long t = System.nanoTime()
def deskewed = GPU.transform(raw, Transform.copy(matrix))
if (deskewed == null) deskewed = CPU.transform(raw, Transform.copy(matrix))
IJ.log(String.format("deskew       %d x %d x %d   %.2f s",
        deskewed.getWidth(), deskewed.getHeight(), deskewed.getStackSize(), (System.nanoTime() - t) / 1e9))

// calibrate the result: Y is foreshortened by cos(theta), Z spacing is the step times sin(theta)
def cal = deskewed.getCalibration()
cal.setUnit("micron")
cal.pixelWidth = xyPix
cal.pixelHeight = xyPix * Math.cos(Math.toRadians(angle))
cal.pixelDepth = zStep * Math.sin(Math.toRadians(angle))

// how much of the deskewed bounding box is empty, and what the geometry predicts
long zero = 0, total = 0
for (int z = 1; z <= deskewed.getStackSize(); z++) {
    def ip = deskewed.getStack().getProcessor(z)
    for (int i = 0; i < ip.getPixelCount(); i++) { total++; if (ip.getf(i) == 0f) zero++ }
}
double scan = raw.getStackSize() * (zStep / xyPix)
double camera = raw.getHeight() * Math.cos(Math.toRadians(angle))
IJ.log(String.format("empty        %.1f%% measured, %.1f%% predicted by s/(s+c)",
        100.0 * zero / total, 100.0 * (1.0 - scan / (scan + camera))))

def base = rawFile.getName().replaceAll("[.]tiff?\$", "")
def plain = new File(outDir, base + "-deskewed-UNCOMPRESSED.tif")
def deflated = new File(outDir, base + "-deskewed-DEFLATE-L" + level + ".tif")

t = System.nanoTime()
new FileSaver(deskewed).saveAsTiffStack(plain.getAbsolutePath())
double plainWrite = (System.nanoTime() - t) / 1e9
t = System.nanoTime()
FastTiffWriter.write(deskewed, deflated, level)
double deflateWrite = (System.nanoTime() - t) / 1e9

t = System.nanoTime(); def backFast = VolumeIO.openFast(deflated.getAbsolutePath())
double fastRead = (System.nanoTime() - t) / 1e9
t = System.nanoTime(); def backIJ = IJ.openImage(deflated.getAbsolutePath())
double ijRead = (System.nanoTime() - t) / 1e9

// compare every voxel against what was written
def identical = { a, b ->
    if (b == null) return false
    if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight() || a.getStackSize() != b.getStackSize())
        return false
    for (int z = 1; z <= a.getStackSize(); z++) {
        def pa = a.getStack().getProcessor(z), pb = b.getStack().getProcessor(z)
        for (int i = 0; i < pa.getPixelCount(); i++) if (pa.get(i) != pb.get(i)) return false
    }
    true
}

IJ.log("")
IJ.log(String.format("uncompressed %-10s  write %.2f s", mb(plain.length()), plainWrite))
IJ.log(String.format("Deflate L%d   %-10s  write %.2f s   %.0f%% smaller, %.2fx the raw file",
        level, mb(deflated.length()), deflateWrite,
        100.0 * (1.0 - (double) deflated.length() / plain.length()),
        (double) deflated.length() / rawFile.length()))
IJ.log(String.format("read back    %.2f s FastTiffReader, %.2f s IJ.openImage", fastRead, ijRead))
IJ.log("lossless     " + (identical(deskewed, backFast) && identical(deskewed, backIJ)
        ? "yes, both readers match what was written" : "NO - values differ, please report this"))

deskewed.show()
