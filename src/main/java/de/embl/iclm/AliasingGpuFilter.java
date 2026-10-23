package de.embl.iclm;

import java.nio.FloatBuffer;
import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
import net.haesleinhuepf.clij.coremem.enums.NativeTypeEnum;
import net.haesleinhuepf.clij2.CLIJ2;
import net.haesleinhuepf.clijx.plugins.ForwardFFT;
import net.haesleinhuepf.clijx.plugins.InverseFFT;

/** Batches independent Y/scan sections in a 3D FFT; the mask is constant along kX. */
final class AliasingGpuFilter implements AutoCloseable {
    private final CLIJ2 clij;
    private ClearCLBuffer input, spectrum, filtered, output, mask;
    private final float[] real;
    AliasingGpuFilter(int nz,int ny,int batch,float[] weights) {
        clij=CLIJ2.getInstance();
        real=new float[nz*ny*batch];
        try {
            long[] realShape=batch==1?new long[]{ny,nz}:new long[]{ny,nz,batch};
            input=clij.create(realShape,NativeTypeEnum.Float);
            output=clij.create(realShape,NativeTypeEnum.Float);
            int packedWidth=2*(ny/2+1);
            long[] packedShape=batch==1?new long[]{packedWidth,nz}:new long[]{packedWidth,nz,batch};
            spectrum=clij.create(packedShape,NativeTypeEnum.Float);
            filtered=clij.create(packedShape,NativeTypeEnum.Float);
            float[] packed=new float[packedWidth*nz*batch];
            for(int b=0;b<batch;b++)for(int z=0;z<nz;z++)for(int y=0;y<=ny/2;y++) {
                packed[(b*nz+z)*packedWidth+2*y]=weights[z*ny+y];
                packed[(b*nz+z)*packedWidth+2*y+1]=weights[z*ny+y];
            }
            mask=clij.create(packedShape,NativeTypeEnum.Float);
            mask.readFrom(FloatBuffer.wrap(packed),true);
        } catch (RuntimeException | LinkageError e) { close();throw e; }
    }
    void filter(float[] complex) {
        for(int i=0;i<real.length;i++) real[i]=complex[2*i];
        input.readFrom(FloatBuffer.wrap(real),true);
        if (!ForwardFFT.runFFT(clij,input,spectrum)) throw new IllegalStateException("CLIJ forward FFT failed.");
        clij.multiplyImages(spectrum,mask,filtered);
        if (!InverseFFT.runInverseFFT(clij,filtered,output)) throw new IllegalStateException("CLIJ inverse FFT failed.");
        output.writeTo(FloatBuffer.wrap(real),true);
        for(int i=0;i<real.length;i++) { complex[2*i]=real[i];complex[2*i+1]=0; }
    }
    @Override public void close() {
        for(ClearCLBuffer b:new ClearCLBuffer[]{mask,output,filtered,spectrum,input}) if(b!=null)b.close();
        mask=null;output=null;filtered=null;spectrum=null;input=null;
    }
}
