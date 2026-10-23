package de.embl.iclm;

import static org.junit.Assert.*;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Assume;
import org.junit.Test;

public class AliasingReconstructionTest {
    static ImagePlus gaussian(int width,int height,int depth,double angle) {
        ImageStack stack=new ImageStack(width,height);
        double theta=Math.toRadians(angle),a=.265/.116;
        for(int z=0;z<depth;z++) {
            float[] pixels=new float[width*height];
            for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
                double labY=a*(z-(depth-1)/2.)+Math.cos(theta)*(y-(height-1)/2.);
                double labZ=-Math.sin(theta)*(y-(height-1)/2.);
                pixels[y*width+x]=(float)(100+(800+200*Math.cos(2*Math.PI*x/width))*Math.exp(-.5*(labY*labY/4+labZ*labZ/16)));
            }
            stack.addSlice(new FloatProcessor(width,height,pixels));
        }
        ImagePlus image=new ImagePlus("analytic tilted bead",stack);
        image.getCalibration().pixelWidth=.116;image.getCalibration().pixelHeight=.116;image.getCalibration().pixelDepth=.265;
        image.getCalibration().setUnit("um");return image;
    }
    @Test public void recoversAnalyticTiltedPsfAndPreservesIntensityForEveryPhase() {
        ImagePlus dense=gaussian(16,96,97,33.5);
        for(int phase=0;phase<2;phase++) {
            ImagePlus sparse=AliasingReconstruction.decimate(dense,2,phase);
            AliasingReconstruction.Settings s=new AliasingReconstruction.Settings();s.background=100;
            ImagePlus recovered=AliasingReconstruction.reconstruct(sparse,s);
            double[] error=AliasingResampling.errors(dense,recovered,phase,100,4);
            assertTrue("relative error="+error[0],error[0]<.015);
            assertEquals(.265,recovered.getCalibration().pixelDepth,1e-12);
            assertEquals((sparse.getStackSize()-1)*2+1,recovered.getStackSize());
            assertEquals(100,dense.getStack().getProcessor(1).getf(0,0),1e-5);
        }
    }
    @Test public void maskPreservesHermitianSymmetryOnNyquistRows() {
        AliasingReconstruction.Settings s=new AliasingReconstruction.Settings();s.taper=.2;
        float[] w=AliasingReconstruction.weights(64,32,s);
        for(int z=0;z<64;z++)for(int y=0;y<32;y++)assertEquals(w[z*32+y],w[((64-z)%64)*32+(32-y)%32],0);
        assertEquals(1,w[0],0);
    }
    @Test public void backgroundsAndPhysicalOriginsArePreserved() {
        ImagePlus dense=gaussian(8,16,21,25);
        dense.getCalibration().zOrigin=3;
        ImagePlus sparse=AliasingReconstruction.decimate(dense,3,1);
        assertEquals(2./3,sparse.getCalibration().zOrigin,1e-12);
        for(int z=1;z<=sparse.getStackSize();z++)java.util.Arrays.fill((float[])sparse.getStack().getPixels(z),100);
        AliasingReconstruction.Settings s=new AliasingReconstruction.Settings();s.factor=3;s.acquiredStepUm=.795;s.background=100;
        ImagePlus rec=AliasingReconstruction.reconstruct(sparse,s);
        assertEquals(2,rec.getCalibration().zOrigin,1e-12);
        for(int z=1;z<=rec.getStackSize();z++)for(float v:(float[])rec.getStack().getPixels(z))assertEquals(100,v,1e-6);
    }
    @Test public void rejectsFractionalFactorsAndInvalidGeometry() {
        try {AliasingResampling.integer(2.1,"factor");fail();}catch(IllegalArgumentException expected) { }
        AliasingReconstruction.Settings s=new AliasingReconstruction.Settings();s.angleDegrees=90;
        try {s.validate();fail();}catch(IllegalArgumentException expected) { }
        s.angleDegrees=25;s.acquiredStepUm=Double.NaN;
        try {s.validate();fail();}catch(IllegalArgumentException expected) { }
    }
    @Test public void rejectsHyperstacksRatherThanCombiningChannels() {
        ImagePlus v=gaussian(8,16,20,25);v.setDimensions(2,10,1);
        try {AliasingReconstruction.reconstruct(v,new AliasingReconstruction.Settings());fail();}catch(IllegalArgumentException expected) { }
    }
    @Test public void rejectsNonfinitePixels() {
        ImagePlus v=gaussian(8,16,20,25);v.getStack().getProcessor(1).setf(0,Float.NaN);
        try {AliasingReconstruction.reconstruct(v,new AliasingReconstruction.Settings());fail();}catch(IllegalArgumentException expected) { }
    }
    @Test public void factorOnePreservesArbitrarySignedImage() {
        ImagePlus v=gaussian(8,16,20,25);v.getStack().getProcessor(5).setf(40,-23);
        AliasingReconstruction.Settings s=new AliasingReconstruction.Settings();s.factor=1;
        ImagePlus r=AliasingReconstruction.reconstruct(v,s);
        for(int z=1;z<=20;z++)for(int i=0;i<128;i++)assertEquals(v.getStack().getProcessor(z).getf(i),r.getStack().getProcessor(z).getf(i),.001);
    }
    @Test public void metadataMismatchDoesNotAssignSparseStepToDenseStack()throws Exception {
        File file=File.createTempFile("aliasing-metadata-",".txt");
        try {
            Files.write(file.toPath(),("Tilt angle: 25\nStep size /um: 2\nPixel size at object /um: 0.116\nPlanes per volume: 91\nExposure time /ms: 100\n").getBytes(StandardCharsets.UTF_8));
            assertEquals(2,AliasingMetadata.read(file,91).step,0);
            assertTrue(AliasingMetadata.read(file,901).note.contains("UNVERIFIED"));
            assertEquals(.265,AliasingMetadata.read(file,901).step,0);
        }finally{Files.delete(file.toPath());}
    }
    @Test public void clijMatchesCpuIncludingAbsoluteIntensity() {
        Assume.assumeTrue(Boolean.getBoolean("opm.test.gpu"));
        System.out.println("Aliasing GPU devices: "+net.haesleinhuepf.clij.CLIJ.getAvailableDeviceNames());
        System.out.println("Aliasing selected GPU: "+net.haesleinhuepf.clij2.CLIJ2.getInstance().getGPUName());
        ImagePlus sparse=AliasingReconstruction.decimate(gaussian(35,96,97,33.5),2,0);
        AliasingReconstruction.Settings s=new AliasingReconstruction.Settings();s.background=100;
        ImagePlus cpu=AliasingReconstruction.reconstruct(sparse,s);s.gpu=true;
        ImagePlus gpu=AliasingReconstruction.reconstruct(sparse,s);
        double max=0;
        for(int z=1;z<=cpu.getStackSize();z++)for(int i=0;i<cpu.getWidth()*cpu.getHeight();i++)
            max=Math.max(max,Math.abs(cpu.getStack().getProcessor(z).getf(i)-gpu.getStack().getProcessor(z).getf(i)));
        System.out.println("Aliasing CPU/GPU maximum absolute error: "+max);
        assertTrue("CPU/GPU max error "+max,max<.01);
        if(System.getProperty("opm.test.aliasing.width")!=null)
            sparse=AliasingReconstruction.decimate(gaussian(Integer.getInteger("opm.test.aliasing.width",35),96,97,33.5),2,0);
        for(int repeat=0;repeat<3;repeat++)for(boolean useGpu:new boolean[]{false,true}) {
            s.gpu=useGpu;long start=System.nanoTime();
            ImagePlus r=AliasingReconstruction.reconstruct(sparse,s);r.close();
            System.out.println("ALIAS_TIMING,"+(useGpu?"CLIJ2-FFT":"JTransforms")+","+repeat+","+(System.nanoTime()-start)/1e9);
        }
    }
}
