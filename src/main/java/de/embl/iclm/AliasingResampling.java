package de.embl.iclm;

import edu.emory.mathcs.jtransforms.fft.FloatFFT_2D;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.measure.ResultsTable;
import ij.plugin.PlugIn;
import ij.process.FloatProcessor;
import java.util.concurrent.CancellationException;

/** Fiji entry points for raw-volume aliasing reconstruction and acquisition validation. */
public class AliasingResampling implements PlugIn {
    private static final String HELP="https://github.com/embl-ic/opm-toolset/blob/main/docs/manual/aliasing-resampling.md";

    @Override public void run(String arg) {
        Debug.commandStarted("Aliasing Resampling > "+arg);
        try {
            if("plan".equals(arg)) { plan();return; }
            ImagePlus input=WindowManager.getCurrentImage();
            AliasingReconstruction.validateVolume(input);
            IJ.resetEscape();
            if("subset".equals(arg)) { subset(input);return; }
            if("otf".equals(arg)) { otf(input);return; }
            boolean benchmark="benchmark".equals(arg);
            AliasingMetadata metadata=AliasingMetadata.from(input);
            GenericDialog gd=new OpmDialog(benchmark?"Aliasing Resampling - Test dense raw volume":"Aliasing Resampling - Reconstruct raw volume");
            gd.addMessage("Input: "+input.getTitle()+"\nRaw oblique volume, before deskew or deconvolution.\n"+
                (benchmark?"Tests factors 2 through the selected maximum, all slice offsets.":
                "Input must already be sparsely acquired. Factor = acquired step / target step."));
            gd.addMessage(metadata.note);
            gd.addNumericField("Camera pixel at object (um)",metadata.pixel,4);
            gd.addNumericField(benchmark?"Dense scan step (um)":"Acquired scan step (um)",metadata.step,4);
            gd.addNumericField("OPM angle (degrees)",metadata.angle,2);
            gd.addNumericField(benchmark?"Maximum subset factor":"Integer reconstruction factor",2,0);
            gd.addNumericField("Camera background (intensity units)",0,2);
            gd.addNumericField("Padding (fine-grid pixels)",32,0);
            gd.addNumericField("Cosine edge fraction (0 = top hat)",0,2);
            gd.addChoice("FFT backend",new String[]{"CPU (JTransforms)","GPU (CLIJ2-FFT, experimental)"},"CPU (JTransforms)");
            if(!benchmark) gd.addCheckbox("Also show deskewed volume",false);
            gd.addMessage("Recovery is conditional on non-overlapping optical spectra.\n"+
                "Output remains a raw grid; use acquired step / factor for deskew.\n"+
                "Use a representative ROI for tests. Escape cancels; edges need a guard region.");
            gd.addHelp(HELP);gd.showDialog();if(gd.wasCanceled())return;
            AliasingReconstruction.Settings s=new AliasingReconstruction.Settings();
            s.pixelUm=gd.getNextNumber();s.acquiredStepUm=gd.getNextNumber();s.angleDegrees=gd.getNextNumber();
            s.factor=integer(gd.getNextNumber(),"Factor");s.background=gd.getNextNumber();
            s.padding=integer(gd.getNextNumber(),"Padding");s.taper=gd.getNextNumber();s.gpu=gd.getNextChoiceIndex()==1;
            s.validate();
            if(benchmark) { benchmark(input,s);return; }
            boolean deskew=gd.getNextBoolean();
            long start=System.nanoTime();
            ImagePlus result=AliasingReconstruction.reconstruct(input,s);result.show();
            IJ.log("Aliasing Resampling: "+(System.nanoTime()-start)/1e9+" s; "+result.getStackSize()+
                " raw planes, scan step "+s.fineStepUm()+" um. Inspect boundary ringing and validate the PSF.");
            if(deskew) {
                ImagePlus lab=CPU.transform(result,Transform.deskew(s.fineStepUm(),s.pixelUm,s.angleDegrees,result.getHeight()),false);
                if(lab==null)throw new IllegalStateException("Deskew failed; the recovered raw volume is still available.");
                lab.setTitle(input.getShortTitle()+"-alias-recovered-deskewed");
                lab.getCalibration().pixelWidth=s.pixelUm;lab.getCalibration().pixelHeight=s.pixelUm;lab.getCalibration().pixelDepth=s.pixelUm;
                lab.getCalibration().setUnit("um");lab.show();
            }
        } catch(CancellationException e) { IJ.showStatus(e.getMessage()); }
        catch(Exception | LinkageError e) { IJ.handleException(e);IJ.error("Aliasing Resampling",e.getMessage()==null?e.toString():e.getMessage()); }
    }

    static int integer(double value,String label) {
        if(!Double.isFinite(value)||value!=Math.rint(value)||value<0||value>Integer.MAX_VALUE)
            throw new IllegalArgumentException(label+" must be a non-negative integer.");
        return (int)value;
    }

    private void subset(ImagePlus input) {
        GenericDialog gd=new OpmDialog("Aliasing Resampling - Create raw slice subset");
        gd.addNumericField("Keep every Nth plane",2,0);gd.addNumericField("First-plane offset (0 based)",0,0);
        gd.addHelp(HELP);gd.showDialog();if(gd.wasCanceled())return;
        AliasingReconstruction.decimate(input,integer(gd.getNextNumber(),"Factor"),integer(gd.getNextNumber(),"Offset")).show();
    }

    private void benchmark(ImagePlus dense,AliasingReconstruction.Settings s) {
        int maximum=s.factor;
        if(maximum<2 || maximum>16 || dense.getStackSize()<2*maximum)
            throw new IllegalArgumentException("Choose maximum factor 2-16 and a stack with at least twice that many planes.");
        double denseStep=s.acquiredStepUm;
        ResultsTable table=new ResultsTable();
        for(int m=2;m<=maximum;m++)for(int phase=0;phase<m;phase++) {
            AliasingReconstruction.checkCancelled();
            ImagePlus sparse=AliasingReconstruction.decimate(dense,m,phase);
            s.factor=m;s.acquiredStepUm=denseStep*m;
            for(int method=0;method<2;method++) {
                long start=System.nanoTime();
                ImagePlus rec=method==0?AliasingReconstruction.interpolate(sparse,m):AliasingReconstruction.reconstruct(sparse,s);
                double seconds=(System.nanoTime()-start)/1e9;
                try {
                    double[] values=errors(dense,rec,phase,s.background,Math.max(4,2*m));
                    table.incrementCounter();table.addValue("factor",m);table.addValue("phase",phase);
                    table.addValue("method",method==0?"linear":"fourier");table.addValue("step_um",denseStep*m);
                    table.addValue("NRMSE",values[0]);table.addValue("bias",values[1]);table.addValue("voxels",values[2]);
                    table.addValue("seconds",seconds);
                } finally {rec.close();}
            }
            sparse.close();table.show("Aliasing dense-reference comparison");
        }
        IJ.log("Aliasing benchmark: NRMSE = RMS(error)/RMS(reference-background), guarded raw-volume interior. " +
            "A dense measured volume is a noisy reference, not optical ground truth. Save the Results table; validate PSF widths separately.");
    }

    static double[] errors(ImagePlus dense,ImagePlus result,int phase,double background,int guard) {
        int w=result.getWidth(),h=result.getHeight(),d=result.getStackSize();
        if(w<=2*guard||h<=2*guard||d<=2*guard)throw new IllegalArgumentException("ROI too small for benchmark boundary guard.");
        double error=0,power=0,bias=0;long count=0;
        for(int z=guard;z<d-guard;z++) {
            FloatProcessor a=dense.getStack().getProcessor(z+phase+1).convertToFloatProcessor();
            FloatProcessor b=result.getStack().getProcessor(z+1).convertToFloatProcessor();
            for(int y=guard;y<h-guard;y++)for(int x=guard;x<w-guard;x++) {
                double t=a.getf(x,y)-background,delta=b.getf(x,y)-a.getf(x,y);
                error+=delta*delta;power+=t*t;bias+=delta;count++;
            }
        }
        return new double[]{Math.sqrt(error/Math.max(power,1e-30)),bias/count,count};
    }

    private void otf(ImagePlus input) {
        GenericDialog gd=new OpmDialog("Aliasing Resampling - Inspect raw bead PSF spectrum");
        AliasingMetadata metadata=AliasingMetadata.from(input);
        gd.addMessage("Use a tight, isolated bead ROI from a dense RAW volume.\nThe displayed section is kX=0 (sum over X), not the full 3D OTF.\nBead diameter, background and noise limit the support estimate.");
        gd.addMessage(metadata.note);
        gd.addNumericField("Camera pixel at object (um)",metadata.pixel,4);gd.addNumericField("Dense scan step (um)",metadata.step,4);
        gd.addNumericField("OPM angle (degrees)",metadata.angle,2);gd.addNumericField("Background intensity",0,2);
        gd.addNumericField("Relative amplitude threshold",.10,3);gd.addHelp(HELP);gd.showDialog();if(gd.wasCanceled())return;
        AliasingReconstruction.Settings s=new AliasingReconstruction.Settings();s.factor=1;
        s.pixelUm=gd.getNextNumber();s.acquiredStepUm=gd.getNextNumber();s.angleDegrees=gd.getNextNumber();s.background=gd.getNextNumber();s.validate();
        double threshold=gd.getNextNumber();if(!Double.isFinite(threshold)||threshold<=0||threshold>=1)throw new IllegalArgumentException("Threshold must be between 0 and 1.");
        int ny=AliasingReconstruction.paddedSize(input.getHeight(),32),nz=AliasingReconstruction.paddedSize(input.getStackSize(),32);
        if(2L*ny*nz>Integer.MAX_VALUE || 12L*ny*nz>Runtime.getRuntime().maxMemory()/2)
            throw new IllegalArgumentException("Use a smaller isolated bead ROI for OTF inspection.");
        float[] a=new float[2*ny*nz];
        for(int z=0;z<input.getStackSize();z++)for(int y=0;y<input.getHeight();y++) {
            double sum=0;for(int x=0;x<input.getWidth();x++)sum+=input.getStack().getProcessor(z+1).getf(x,y)-s.background;
            a[2*(z*ny+y)]=(float)sum;
        }
        new FloatFFT_2D(nz,ny).complexForward(a);double dc=Math.abs(a[0]);
        if(dc<=0)throw new IllegalArgumentException("PSF has no positive integrated signal above background.");
        float[] display=new float[nz*ny];double qMax=0;
        for(int z=0;z<nz;z++)for(int y=0;y<ny;y++) {
            int i=z*ny+y;double amplitude=Math.hypot(a[2*i],a[2*i+1])/dc;
            display[((z+nz/2)%nz)*ny+(y+ny/2)%ny]=(float)Math.log10(Math.max(amplitude,1e-6));
            if(amplitude>=threshold)qMax=Math.max(qMax,Math.abs(AliasingReconstruction.frequency(z,nz)-s.slope()*AliasingReconstruction.frequency(y,ny)));
        }
        new ImagePlus("Raw PSF kX=0 spectrum (log10 amplitude/DC)",new FloatProcessor(ny,nz,display)).show();
        IJ.log("PSF spectrum: threshold="+threshold+", q support="+qMax+" cycles/fine scan sample; slab factor bound="+
            (qMax>0?.5/qMax:Double.NaN)+". Section-only diagnostic; inspect multiple beads and 3D support before choosing a step.");
    }

    private void plan() {
        GenericDialog gd=new OpmDialog("Aliasing Resampling - Acquisition planner");
        AliasingMetadata metadata=AliasingMetadata.from(WindowManager.getCurrentImage());
        gd.addMessage("Conditional axial-slab bound for this toolset's geometry.\nSupply measured axial OTF support; a generic image spectrum is not an OTF.");
        gd.addMessage(metadata.note);
        gd.addNumericField("OPM angle (degrees)",metadata.angle,2);
        gd.addNumericField("Measured axial OTF half support (cycles/um)",1,3);
        gd.addNumericField("Safety fraction of support bound",.8,2);
        gd.addNumericField("Desired scan step (um)",.530,4);
        gd.addNumericField("Scan extent (um)",119.25,2);
        gd.addNumericField("Exposure (ms)",metadata.exposure,2);gd.addNumericField("Readout/settle overhead per plane (ms)",0,2);
        gd.addNumericField("Flyback per volume (ms)",0,2);
        gd.addCheckbox("Galvo moves continuously during exposure",true);
        gd.addNumericField("Measured frame period (ms; 0 = exposure + overhead)",0,2);
        gd.addHelp(HELP);gd.showDialog();if(gd.wasCanceled())return;
        double angle=gd.getNextNumber(),kz=gd.getNextNumber(),safety=gd.getNextNumber(),step=gd.getNextNumber(),extent=gd.getNextNumber();
        double exposure=gd.getNextNumber(),overhead=gd.getNextNumber(),flyback=gd.getNextNumber();
        boolean continuous=gd.getNextBoolean();double framePeriod=gd.getNextNumber();
        for(double v:new double[]{angle,kz,safety,step,extent,exposure,overhead,flyback})if(!Double.isFinite(v))throw new IllegalArgumentException("All planner values must be finite.");
        if(angle<=0||angle>=89.9||kz<=0||safety<=0||safety>1||step<=0||extent<=0||exposure<=0||overhead<0||flyback<0)throw new IllegalArgumentException("Invalid planning values.");
        if(!Double.isFinite(framePeriod)||framePeriod<0||(framePeriod>0&&framePeriod<exposure))throw new IllegalArgumentException("Frame period must be zero or at least the exposure time.");
        if(framePeriod==0)framePeriod=exposure+overhead;
        double bound=1/(2*kz*Math.tan(Math.toRadians(angle)));
        long planes=(long)Math.ceil(extent/step)+1;
        ResultsTable table=new ResultsTable();table.incrementCounter();table.addValue("slab_bound_um",bound);
        table.addValue("conservative_step_um",bound*safety);table.addValue("requested_step_um",step);
        table.addValue("within_bound",step<=bound*safety?"yes (conditional)":"no");table.addValue("planes",planes);
        table.addValue("volume_seconds",(planes*framePeriod+flyback)/1000);
        double blur=continuous?step*exposure/framePeriod:0;
        table.addValue("continuous_blur_length_um",blur);
        table.addValue("motion_OTF_first_zero_cycles_per_um",blur>0?1/blur:Double.NaN);
        table.addValue("plane_normal_spacing_um",step*Math.sin(Math.toRadians(angle)));
        table.show("Aliasing acquisition plan");
        IJ.log("Exposure is a user input, not inferred from aliasing. The uniform continuous-scan model uses L=step*exposure/framePeriod along laboratory Y. " +
            "The motion OTF sinc has its first zero at 1/L. Compare this with measured Y bandwidth; the axial alias bound does not protect against motion blur. " +
            "Use a measured frame period when readout and exposure overlap.");
    }
}
