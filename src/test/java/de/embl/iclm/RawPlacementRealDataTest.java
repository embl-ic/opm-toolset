package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.junit.Assume;
import org.junit.Test;

import bdv.viewer.Interpolation;
import ij.process.FloatProcessor;
import net.imglib2.RandomAccess;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.integer.UnsignedShortType;

/** Registration on measured beads is reported separately from synthetic interpolation parity. */
public class RawPlacementRealDataTest {

	@Test
	public void measuredTaggedHalvesCoincideInBothFlipFramesAndWholeLandmarksKeepTheirPlacement() throws Exception {
		String path = System.getProperty("opm.test.beads", "");
		Assume.assumeTrue("Set -Dopm.test.beads", !path.isEmpty());
		File folder = new File(path);
		RawGeometry geometry = RawGeometry.fromExperimentalParameters(new File(folder, "ExperimentalParameters.txt"));
		AlignmentMatrixSet matrices = AlignmentMatrixSet.load(new File(folder, "beads-alignment.csv").getPath());
		assertNotNull(matrices);
		RawAcquisition acquisition = RawAcquisition.scan(folder);
		RawAcquisition.Snapshot snapshot = acquisition.snapshot(1, new int[] { 1, 2 });
		for (RawAcquisition.Entry entry : snapshot.entries) assertEquals(RawAcquisition.Readiness.COMPLETE, entry.validate());
		RawTiffLayout layout = snapshot.entry(0, 1).layout();
		TiffRawVolumeStore store = new TiffRawVolumeStore(1600L << 20, TiffRawVolumeStore.cellBytes(layout), 4, null);
		try {
			long[] grid = geometry.deskewedGrid(layout.width / 2, layout.height, layout.depth);
			for (boolean flipLeft : new boolean[] { false, true }) {
				String anchor = flipLeft ? RawPlacementTest.R1 : RawPlacementTest.L1;
				FloatProcessor fixed = projection(acquisition, snapshot.times, half(matrices, anchor, flipLeft, layout.width), geometry, store, grid);
				for (String key : new String[] { flipLeft ? RawPlacementTest.L1 : RawPlacementTest.R1, RawPlacementTest.R2 }) {
					assertNotNull("a measured matrix must exist for " + key, matrices.matrixFor(key));
					FloatProcessor moving = projection(acquisition, snapshot.times, half(matrices, key, flipLeft, layout.width), geometry, store, grid);
					int[] peak = peak(fixed, moving, 8);
					BeadAlignment.Result score = BeadAlignment.evaluate(fixed, moving, AlignmentMatrixSet.identity2d(), "viewer placed beads");
					assertNotNull(score);
					System.out.printf(Locale.US, "PLACEMENT_BEADS flipLeft=%s source=%s anchor=%s peak_px=%d,%d matches=%d rms_px=%.5f (original grid)%n",
							flipLeft, key, anchor, 2 * peak[0], 2 * peak[1], score.matches, 2 * score.rmsPixels);
					assertEquals("registration X", 0, peak[0]);
					assertEquals("registration Y", 0, peak[1]);
					assertTrue("enough real landmarks to score registration", score.matches >= 10);
				}
			}
			// The supplied set has no measured whole-file matrix. Validate a known fractional whole-source placement on
			// actual bead landmarks; this is coordinate accuracy, not a new measured whole-file alignment.
			for (int channel : new int[] { 1, 2 }) {
				RawTiffLayout own = snapshot.entry(0, channel).layout();
				long[] wholeGrid = geometry.deskewedGrid(own.width, own.height, own.depth);
				RawSourceSpec plain = RawSourceSpec.whole(null, channel, 0);
				FloatProcessor image = projection(acquisition, snapshot.times, plain, geometry, store, wholeGrid);
				List<BeadAlignment.Spot> spots = BeadAlignment.detect(image, 2);
				assertTrue(spots.size() >= 10);
				double[][] matrix = RawPlacementTest.rigid(0.4, 3.25, -1.75);
				AlignmentMatrixSet known = new AlignmentMatrixSet(plain.key);
				known.put(plain.key, matrix);
				RawSourceSpec moved = RawSourceSpec.whole(known, channel, 0);
				AffineTransform3D before = geometry.sourceToWorld(own.dimensions(), plain, null);
				AffineTransform3D after = geometry.sourceToWorld(own.dimensions(), moved, null);
				double square = 0;
				for (BeadAlignment.Spot spot : spots) {
					double x = 2 * spot.x, y = 2 * spot.y;
					double[] raw = new double[3], actual = new double[3];
					before.inverse().apply(new double[] { x * geometry.dxyUm, y * geometry.dxyUm, wholeGrid[2] * geometry.dxyUm / 2 }, raw);
					after.apply(raw, actual);
					double dx = actual[0] / geometry.dxyUm - (matrix[0][0] * x + matrix[0][1] * y + matrix[0][2]);
					double dy = actual[1] / geometry.dxyUm - (matrix[1][0] * x + matrix[1][1] * y + matrix[1][2]);
					square += dx * dx + dy * dy;
				}
				double rms = Math.sqrt(square / spots.size());
				System.out.printf(Locale.US, "PLACEMENT_WHOLE source=%s actual_bead_landmarks=%d known_transform_rms_px=%.9g; no measured whole-file registration supplied%n",
						plain.key, spots.size(), rms);
				assertTrue("known whole placement preserves landmark coordinates", rms < 1e-8);
			}
		} finally { store.closeAsync().get(15, TimeUnit.SECONDS); }
	}

	private static RawSourceSpec half(AlignmentMatrixSet set, String key, boolean flipLeft, int width) {
		return RawSourceSpec.half(set, ChannelOperationSettings.acquisitionChannelOf(key), key.endsWith("-left"), flipLeft, width, 0);
	}

	private static FloatProcessor projection(RawAcquisition acquisition, List<RawAcquisition.TimeKey> times, RawSourceSpec spec,
			RawGeometry geometry, TiffRawVolumeStore store, long[] grid) {
		RawAcquisition.Entry entry = acquisition.entry(times.get(0), spec.acquisitionChannel);
		RawPartSource<UnsignedShortType> source = new RawPartSource<UnsignedShortType>(acquisition, times, spec, geometry,
				store::blocking, new UnsignedShortType(), entry.layout().dimensions());
		AffineTransform3D toGrid = new AffineTransform3D(), unscale = new AffineTransform3D();
		source.getSourceTransform(0, 0, toGrid);
		unscale.scale(1 / geometry.dxyUm);
		toGrid.preConcatenate(unscale);
		RandomAccess<UnsignedShortType> view = RealViews.affine(source.getInterpolatedSource(0, 0, Interpolation.NEARESTNEIGHBOR), toGrid).randomAccess();
		int width = (int) grid[0] / 2, height = (int) grid[1] / 2;
		FloatProcessor image = new FloatProcessor(width, height);
		float[] values = (float[]) image.getPixels();
		for (int z = 0; z < grid[2]; z += 2) {
			if (Shutdown.stopping()) throw new java.util.concurrent.CancellationException("Bead validation stopped");
			for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
				view.setPosition(new long[] { 2L * x, 2L * y, z });
				int i = y * width + x;
				values[i] = Math.max(values[i], view.get().get());
			}
		}
		return image;
	}

	private static int[] peak(FloatProcessor a, FloatProcessor b, int range) {
		double best = -Double.MAX_VALUE;
		int[] at = { 0, 0 };
		for (int dy = -range; dy <= range; dy++) for (int dx = -range; dx <= range; dx++) {
			double ab = 0, aa = 0, bb = 0, sa = 0, sb = 0;
			long n = 0;
			for (int y = range; y < a.getHeight() - range; y++) for (int x = range; x < a.getWidth() - range; x++) {
				double va = a.getf(x, y), vb = b.getf(x + dx, y + dy);
				ab += va * vb;
				aa += va * va;
				bb += vb * vb;
				sa += va;
				sb += vb;
				n++;
			}
			double score = (ab - sa * sb / n) / Math.sqrt((aa - sa * sa / n) * (bb - sb * sb / n));
			if (score > best) { best = score; at[0] = dx; at[1] = dy; }
		}
		return at;
	}
}
