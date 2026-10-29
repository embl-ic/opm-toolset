package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.viewer.Interpolation;
import ij.ImagePlus;
import net.imglib2.RandomAccess;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.integer.UnsignedShortType;

/** Optional direct Batch NN parity: GPU is required because its CPU deskew fallback is linear. */
public class RawPlacementGpuTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void wholeSourcesMatchBatchDeskewAndTaggedRegistrationForEitherFlip() throws Exception {
		check(true);
	}

	@Test
	public void taggedHalvesMatchBatchInBothFlipFrames() throws Exception {
		check(false);
	}

	private void check(boolean whole) throws Exception {
		Assume.assumeTrue("Enable with -Dopm.test.gpu=true", Boolean.getBoolean("opm.test.gpu"));
		File first = RawMultiFileTest.write(folder.getRoot(), 1, 1), second = RawMultiFileTest.write(folder.getRoot(), 1, 2);
		String[] keys = whole ? new String[] { RawPlacementTest.W2, RawPlacementTest.W1 }
				: new String[] { RawPlacementTest.R2, RawPlacementTest.L1, RawPlacementTest.L2, RawPlacementTest.R1 };
		RawAcquisition.Snapshot snapshot = RawMultiFileTest.catalogue(folder.getRoot(), 1, 2).snapshot(1, new int[] { 1, 2 });
		RawPresence presence = new RawPresence(() -> snapshot);
		TiffRawVolumeStore store = new TiffRawVolumeStore(8L << 20, 512, 2, null);
		try {
			for (boolean registered : new boolean[] { false, true }) for (boolean flipLeft : new boolean[] { false, true }) {
				AlignmentMatrixSet matrices = null;
				if (registered) {
					matrices = new AlignmentMatrixSet(whole ? RawPlacementTest.W1 : RawPlacementTest.L1);
					matrices.put(whole ? RawPlacementTest.W2 : RawPlacementTest.R1, RawPlacementTest.rigid(0, -2, -1));
					if (!whole) {
						matrices.put(RawPlacementTest.L2, RawPlacementTest.rigid(0, 1, -2));
						matrices.put(RawPlacementTest.R2, RawPlacementTest.rigid(0, -1, 2));
					}
				}
				Parameter p = Parameter.scratch();
				p.tryGPU = true;
				p.deskewMatrix = RawPlacementTest.G.deskewMatrix(whole ? 32 : 16, 24, 16);
				p.alignmFile = "";
				p.alignMatrix = null;
				p.alignmentMatrices = matrices;
				ChannelOperationSettings settings = RawMultiFileTest.settings(folder.getRoot(), keys).channels;
				settings.flipHalf = flipLeft ? BatchChannelOperation.FLIP_LEFT : BatchChannelOperation.FLIP_RIGHT;
				settings.interpolate = false;
				ImagePlus batch = MultiChannelDeskew.deskewGroup(Arrays.asList(first, second), p, settings, "placement NN reference");
				assertNotNull(batch);
				long checked = 0, nonzero = 0;
				try {
					int border = registered ? 5 : 0;
					for (int c = 0; c < keys.length; c++) {
						int channel = ChannelOperationSettings.acquisitionChannelOf(keys[c]);
						RawSourceSpec spec = whole ? RawSourceSpec.whole(matrices, channel, 0)
								: RawSourceSpec.half(matrices, channel, keys[c].endsWith("-left"), flipLeft, 32, 0);
						RawPartSource<UnsignedShortType> source = new RawPartSource<UnsignedShortType>(spec, RawPlacementTest.G,
								store::blocking, new UnsignedShortType(), new long[] { 32, 24, 16 }, presence);
						AffineTransform3D toGrid = new AffineTransform3D(), unscale = new AffineTransform3D();
						source.getSourceTransform(0, 0, toGrid);
						unscale.scale(1 / RawPlacementTest.G.dxyUm);
						toGrid.preConcatenate(unscale);
						RandomAccess<UnsignedShortType> sampled = RealViews.affine(source.getInterpolatedSource(0, 0,
								Interpolation.NEARESTNEIGHBOR), toGrid).randomAccess();
						for (int z = 0; z < batch.getNSlices(); z++) for (int y = border; y < batch.getHeight() - border; y++)
							for (int x = border; x < batch.getWidth() - border; x++) {
								sampled.setPosition(new long[] { x, y, z });
								int expected = batch.getStack().getProcessor(batch.getStackIndex(c + 1, z + 1, 1)).get(x, y);
								assertEquals(keys[c] + " registered=" + registered + " flipLeft=" + flipLeft + " at " + x + "," + y + "," + z,
										expected, sampled.get().get());
								checked++;
								if (expected > 0) nonzero++;
							}
					}
					assertTrue(nonzero > checked / 4);
					System.out.println("PLACEMENT_GPU whole=" + whole + " registered=" + registered + " flipLeft=" + flipLeft
							+ " border=" + border + " equal=" + checked + " nonzero=" + nonzero);
				} finally { batch.close(); }
			}
		} finally { store.closeAsync().get(10, TimeUnit.SECONDS); }
	}
}
