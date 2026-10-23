package de.embl.iclm;

import ij.ImagePlus;
import ij.io.FileInfo;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/** Acquisition-local defaults with explicit provenance and a plane-count consistency check. */
final class AliasingMetadata {
    double pixel=.116, step=.265, angle=25, exposure=100;
    String note="Fallback defaults: 0.116 um pixel, 0.265 um step, 25 degrees, 100 ms exposure.\nVerify these values before use.";
    static AliasingMetadata from(ImagePlus image) {
        AliasingMetadata result=new AliasingMetadata();
        if(image==null)return result;
        FileInfo info=image.getOriginalFileInfo();
        if(info==null||info.directory==null)return result;
        File file=new File(info.directory,"ExperimentalParameters.txt");
        if(!file.isFile())return result;
        try {return read(file,image.getStackSize());}
        catch(IOException | IllegalArgumentException e) {result.note="Could not read acquisition metadata: "+e.getMessage()+". "+result.note;return result;}
    }
    static AliasingMetadata read(File file,int depth)throws IOException {
        AliasingMetadata r=new AliasingMetadata();Map<String,Double> values=new HashMap<String,Double>();
        for(String line:Files.readAllLines(file.toPath(),StandardCharsets.UTF_8)) {
            int colon=line.indexOf(':');if(colon<0)continue;
            try {values.put(line.substring(0,colon).trim(),Double.parseDouble(line.substring(colon+1).trim()));}
            catch(NumberFormatException ignored) { /* text metadata */ }
        }
        StringBuilder missing=new StringBuilder();
        r.pixel=value(values,"Pixel size at object /um",r.pixel,missing);
        r.angle=value(values,"Tilt angle",r.angle,missing);
        r.exposure=value(values,"Exposure time /ms",r.exposure,missing);
        Double count=values.get("Planes per volume");
        boolean mismatch=count!=null&&count.intValue()!=depth;
        if(mismatch) {
            r.note="Metadata plane count "+count.intValue()+" differs from input "+depth+".\nScan step is UNVERIFIED; enter the correct value.\n";
        } else {
            r.step=value(values,"Step size /um",r.step,missing);
            r.note="Acquisition metadata:\n"+file.getAbsolutePath()+"\n";
        }
        if(missing.length()>0)r.note+="Fallback used for: "+missing+".\n";
        r.note+="Exposure "+r.exposure+" ms.\nObjective NA, bead diameter and scan mode are not inferred.";
        return r;
    }
    private static double value(Map<String,Double> v,String key,double fallback,StringBuilder missing) {
        Double x=v.get(key);if(x!=null&&Double.isFinite(x)&&x>0)return x;
        if(missing.length()>0)missing.append(", ");missing.append(key);return fallback;
    }
}
