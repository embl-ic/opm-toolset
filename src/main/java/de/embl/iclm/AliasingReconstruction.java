package de.embl.iclm;

import edu.emory.mathcs.jtransforms.fft.FloatFFT_2D;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import java.util.concurrent.CancellationException;

/**
 * Comb upsampling and tilted Fourier-support selection for raw OPM volumes.
 *
 * <p>Adapted from McFadden, Manton and Fiolka, doi:10.1101/2024.12.25.630337.
 * For this toolset's affine Y=p*cos(theta)*u+d*v, Z=-p*sin(theta)*u+constant,
 * the axial support mask pulls back to |fv - d/(p*cos(theta))*fu| < 1/(2*m).
 * d is the RECONSTRUCTED scan pitch, m the integer upsampling factor.
 * Filtering on the raw grid avoids interpolating a sparse zero comb. It is
 * mathematically equivalent before discretization to an axial slab after
 * deskew/rotation, not a literal port of the authors' PetaKit5D code.
 *
 * <p>The mask does not depend on kX: independent Y/scan FFTs are equivalent to
 * a full 3D FFT and use bounded scratch memory. Output remains RAW geometry.
 * The caller must subsequently deskew using acquiredStep/factor. Recoverable
 * bandwidth must be established from a dense PSF/reference; this is not generic
 * missing-plane recovery. Negative ringing is deliberately retained.
 */
public final class AliasingReconstruction {
    private AliasingReconstruction() { }

    public static final class Settings {
        public double pixelUm = 0.116;
        public double acquiredStepUm = 0.530;
        public double angleDegrees = 33.5;
        public int factor = 2;
        public int padding = 32;
        public double background = 0;
        /** Fraction of the half-band occupied by a cosine transition; 0 = paper top hat. */
        public double taper = 0;
        public boolean gpu = false;

        public void validate() {
            if (!Double.isFinite(pixelUm) || pixelUm <= 0 ||
                !Double.isFinite(acquiredStepUm) || acquiredStepUm <= 0 ||
                !Double.isFinite(angleDegrees) || angleDegrees <= 0 || angleDegrees >= 89.9 ||
                factor < 1 || factor > 64 || padding < 0 || padding > 4096 ||
                !Double.isFinite(background) || !Double.isFinite(taper) || taper < 0 || taper >= 1)
                throw new IllegalArgumentException("Use positive pitches, 0 < angle < 89.9, integer factor 1-64, padding 0-4096 and 0 <= taper < 1.");
        }

        public double fineStepUm() { return acquiredStepUm / factor; }
        public double slope() { return fineStepUm() / (pixelUm * Math.cos(Math.toRadians(angleDegrees))); }
    }

    public static void validateVolume(ImagePlus input) {
        if (input == null || input.getNChannels() != 1 || input.getNFrames() != 1 || input.getStackSize() < 2)
            throw new IllegalArgumentException("Use one raw 3D volume, one channel and one time point. Split hyperstacks first.");
        if (input.getBitDepth() == 24)
            throw new IllegalArgumentException("RGB data are not raw scalar intensities. Use an 8, 16 or 32-bit stack.");
    }

    static int paddedSize(int extent, int padding) {
        long needed = (long) extent + 2L * padding;
        int size = 2;
        while (size < needed && size < (1 << 29)) size <<= 1;
        if (size < needed) throw new IllegalArgumentException("FFT dimension is too large.");
        return size;
    }

    static double frequency(int index, int size) {
        return (index < (size + 1) / 2 ? index : index - size) / (double) size;
    }

    private static float weight(double q, int factor, double taper) {
        double edge = 0.5 / factor;
        if (q >= edge) return 0;
        if (taper == 0 || q <= edge * (1 - taper)) return 1;
        return (float) (0.5 + 0.5 * Math.cos(Math.PI * (q - edge * (1 - taper)) / (edge * taper)));
    }

    static float[] weights(int nz, int ny, Settings settings) {
        if ((long) nz * ny > Integer.MAX_VALUE / 2)
            throw new IllegalArgumentException("Y/scan FFT exceeds the Java array limit; crop a smaller ROI.");
        float[] result = new float[nz * ny];
        if(settings.factor==1) {java.util.Arrays.fill(result,1);return result;}
        for (int z = 0; z < nz; z++) for (int y = 0; y < ny; y++) {
            double q = Math.abs(frequency(z, nz) - settings.slope() * frequency(y, ny));
            double conjugate = Math.abs(frequency((nz-z)%nz, nz) - settings.slope() * frequency((ny-y)%ny, ny));
            // Nyquist rows/columns are self-conjugate; enforce a real-valued result.
            result[z*ny+y] = Math.min(weight(q, settings.factor, settings.taper), weight(conjugate, settings.factor, settings.taper));
        }
        return result;
    }

    public static ImagePlus reconstruct(ImagePlus sparse, Settings settings) {
        validateVolume(sparse);
        settings.validate();
        int w = sparse.getWidth(), h = sparse.getHeight(), n = sparse.getStackSize();
        long span = (n-1L)*settings.factor+1;
        if (span > Integer.MAX_VALUE || (long)w*h > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Output dimensions exceed ImageJ limits.");
        int depth = (int)span;
        int nz = paddedSize(depth,settings.padding), ny = paddedSize(h,settings.padding);
        int batch=settings.gpu?Math.max(1,Math.min(Math.min(16,w),(int)(8388608L/((long)nz*ny)))):1;
        long estimate = 4L*w*h*depth + 4L*w*h*n + 24L*nz*ny*batch;
        long available = Runtime.getRuntime().maxMemory() - (Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
        if (estimate > available * 0.8)
            throw new IllegalArgumentException("Reconstruction needs approximately " + (estimate/1048576) +
                " MiB of additional working memory. Increase Fiji memory or use a smaller raw ROI.");
        float[] mask = weights(nz,ny,settings);
        float[][] source = new float[n][];
        for (int z=0;z<n;z++) {
            source[z] = (float[])sparse.getStack().getProcessor(z+1).convertToFloatProcessor().getPixels();
            for (float value : source[z]) if (!Float.isFinite(value))
                throw new IllegalArgumentException("Input contains NaN or infinite intensities.");
        }
        ImageStack stack = new ImageStack(w,h);
        float[][] output = new float[depth][];
        for (int z=0;z<depth;z++) { output[z]=new float[w*h]; stack.addSlice(new FloatProcessor(w,h,output[z])); }
        FloatFFT_2D fft = settings.gpu ? null : new FloatFFT_2D(nz,ny);
        AliasingGpuFilter gpu = settings.gpu ? new AliasingGpuFilter(nz,ny,batch,mask) : null;
        float[] work = new float[2*nz*ny*batch];
        try {
            for (int x=0;x<w;x+=batch) {
                checkCancelled();
                java.util.Arrays.fill(work,0);
                for(int b=0;b<Math.min(batch,w-x);b++)for (int z=0;z<n;z++) for (int y=0;y<h;y++)
                    work[2*((b*nz+settings.padding+z*settings.factor)*ny+settings.padding+y)] =
                        (float)(source[z][y*w+x+b]-settings.background);
                if (gpu != null) gpu.filter(work);
                else {
                    fft.complexForward(work);
                    for (int i=0;i<mask.length;i++) { work[2*i]*=mask[i];work[2*i+1]*=mask[i]; }
                    fft.complexInverse(work,true);
                }
                for(int b=0;b<Math.min(batch,w-x);b++)for (int z=0;z<depth;z++) for (int y=0;y<h;y++)
                    output[z][y*w+x+b] = (float)(work[2*((b*nz+z+settings.padding)*ny+y+settings.padding)]*settings.factor+settings.background);
                IJ.showProgress(Math.min(x+batch,w),w);
            }
        } finally { if (gpu != null) gpu.close(); }
        ImagePlus result = new ImagePlus(sparse.getShortTitle()+"-alias-recovered-raw",stack);
        Calibration cal = sparse.getCalibration().copy();
        cal.pixelWidth=settings.pixelUm;cal.pixelHeight=settings.pixelUm;cal.pixelDepth=settings.fineStepUm();
        cal.zOrigin *= settings.factor;
        cal.setUnit("um");result.setCalibration(cal);
        result.setProperty("Info", "OPM aliasing reconstruction; RAW oblique grid, not Cartesian Z.\n"+
            "source="+sparse.getTitle()+"\nfactor="+settings.factor+"\nacquired_step_um="+settings.acquiredStepUm+
            "\nreconstructed_step_um="+settings.fineStepUm()+"\npixel_um="+settings.pixelUm+
            "\nangle_deg="+settings.angleDegrees+"\nbackground="+settings.background+"\ntaper="+settings.taper+
            "\npadding="+settings.padding+"\nbackend="+(settings.gpu?"CLIJ2-FFT":"JTransforms")+
            "\nmask=abs(f_scan - fine_step/(pixel*cos(angle))*f_camera_y) < 1/(2*factor)\n"+
            "No tail extrapolation or negative-value clipping. Validate boundaries and PSF before quantitative use.");
        result.setProperty("opm.aliasing.raw",Boolean.TRUE);
        return result;
    }

    public static ImagePlus decimate(ImagePlus dense, int factor, int phase) {
        validateVolume(dense);
        if (factor<1 || phase<0 || phase>=factor || phase+factor>=dense.getStackSize())
            throw new IllegalArgumentException("Factor/phase must retain at least two planes.");
        ImageStack stack=new ImageStack(dense.getWidth(),dense.getHeight());
        for(int z=phase;z<dense.getStackSize();z+=factor) stack.addSlice(dense.getStack().getProcessor(z+1).duplicate());
        ImagePlus result=new ImagePlus(dense.getShortTitle()+"-every-"+factor+"-phase-"+phase,stack);
        Calibration cal=dense.getCalibration().copy();cal.pixelDepth*=factor;cal.zOrigin=(cal.zOrigin-phase)/factor;
        result.setCalibration(cal);
        result.setProperty("Info","Even raw-plane subset; factor="+factor+"; zero-based phase="+phase+"; source="+dense.getTitle());
        return result;
    }

    public static ImagePlus interpolate(ImagePlus sparse,int factor) {
        validateVolume(sparse);
        if (factor<1 || factor>64) throw new IllegalArgumentException("Factor must be 1-64.");
        int depth=(sparse.getStackSize()-1)*factor+1;
        ImageStack stack=new ImageStack(sparse.getWidth(),sparse.getHeight());
        for(int z=0;z<depth;z++) {
            int lo=z/factor,hi=Math.min(lo+1,sparse.getStackSize()-1);
            float[] a=(float[])sparse.getStack().getProcessor(lo+1).convertToFloatProcessor().getPixels();
            float[] b=(float[])sparse.getStack().getProcessor(hi+1).convertToFloatProcessor().getPixels();
            float t=(z%factor)/(float)factor;
            float[] v=new float[a.length];for(int i=0;i<v.length;i++)v[i]=a[i]*(1-t)+b[i]*t;
            stack.addSlice(new FloatProcessor(sparse.getWidth(),sparse.getHeight(),v));
        }
        ImagePlus result=new ImagePlus("Raw-Z linear interpolation",stack);
        Calibration cal=sparse.getCalibration().copy();cal.pixelDepth/=factor;cal.zOrigin*=factor;result.setCalibration(cal);
        return result;
    }

    static void checkCancelled() {
        if (Thread.currentThread().isInterrupted() || IJ.escapePressed())
            throw new CancellationException("Aliasing reconstruction cancelled.");
    }
}
