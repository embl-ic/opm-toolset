package de.embl.iclm;

import static org.junit.Assert.*;
import ij.ImagePlus;
import ij.VirtualStack;
import ij.io.FileInfo;
import ij.process.ImageProcessor;
import java.io.File;
import org.junit.Assume;
import org.junit.Test;

/** Optional bounded production-data integration; no files written beside the acquisition. */
public class AliasingAdvisorDataTest {
	@Test public void realBeadRoiProducesAuditableAllPhaseRows() throws Exception {
		String path = System.getProperty("opm.test.aliasing.data"); Assume.assumeTrue("Set opm.test.aliasing.data to a raw bead TIFF", path != null);
		File file = new File(path); assertTrue(file.isFile()); Shutdown.begin();
		long size = file.length(), modified = file.lastModified();
		try (FastTiffReader.PlaneReader reader = new FastTiffReader.PlaneReader(file)) {
			VirtualStack stack = new VirtualStack(reader.width(), reader.height(), null, "") {
				@Override public int getSize() { return reader.depth(); }
				@Override public ImageProcessor getProcessor(int n) {
					try { return reader.readPlane(n - 1); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
				}
			};
			ImagePlus source = new ImagePlus(file.getName(), stack);
			FileInfo info = new FileInfo(); info.directory = file.getParent() + File.separator; info.fileName = file.getName(); source.setFileInfo(info);
			AliasingAdvisorSettings s = new AliasingAdvisorSettings();
			AliasingMetadata.parse(new File(file.getParentFile(), "ExperimentalParameters.txt")).apply(s, reader.depth());
			s.width = 32; s.height = 64; s.depth = 65; s.guard = 8; s.minimumSignal = 16;
			int middle = reader.depth() / 2; ImageProcessor plane = reader.readPlane(middle);
			int bestX = reader.width()/2, bestY = reader.height()/2; float best = -1;
			for (int y=40; y<reader.height()-40; y++) for (int x=20; x<reader.width()-20; x++) {
				float v=plane.getf(x,y); if (v>best && v<65535) { best=v; bestX=x; bestY=y; }
			}
			s.x=bestX-s.width/2; s.y=bestY-s.height/2; s.z=middle-s.depth/2; s.rawConfirmed=true;
			s.sourcePath=file.getAbsolutePath(); source.setSlice(s.z+1);
			double[] noise=AliasingQualitySweep.background(source,new java.awt.Rectangle(s.x,s.y,s.width,s.height));
			s.background=noise[0]; s.noiseSigma=noise[1];
			// Automatically chosen ROI is not an independently verified background calibration.
			s.noiseKnown=false;
			AliasingQualitySweep.Run run = new AliasingQualitySweep.Run(AliasingQualitySweep.snapshot(source,s),s);
			try {
				AliasingQualitySweep.execute(run,null); assertTrue(run.complete); assertEquals(18,run.rows.size());
				assertFalse(run.recommendation().startsWith("Tested"));
				for (AliasingQualitySweep.Aggregate a:run.aggregates) {
					assertEquals(a.factor,a.phases);
					System.out.println("Advisor production ROI: factor="+a.factor+", "+a.method+", NRMSE="+a.heldout.nrmse()+", "+a.verdict);
				}
				AliasingAdvisorView.export(run,new File(".tmp/advisor-verification/real-data").toPath());
			} finally { run.release(); source.flush(); }
		}
		assertEquals(size,file.length()); assertEquals(modified,file.lastModified());
	}
}
