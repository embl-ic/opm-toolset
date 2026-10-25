package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.junit.Assume;
import org.junit.Test;

import bdv.viewer.Interpolation;
import ij.process.ImageProcessor;
import net.imglib2.RandomAccess;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.integer.UnsignedShortType;

/**
 * The Online Deskew View on real acquisitions, end to end through the folder catalogue, the raw
 * store and the BigDataViewer sources. Skipped unless the data are named:
 * <pre>
 * mvn test -Dtest=OnlineDeskewRealDataTest -Dopm.test.jvm.args=-Xmx6g
 *     -Dopm.test.data=E:\OPM\3_timelapse_0
 *     -Dopm.test.zarr=E:\OPM\3_timelapse_0\result_ome-zarr_v2.1.6\3_timelapse_0.ome.zarr
 *     -Dopm.test.beads=E:\OPM\4_beads_for_overlay_0
 * </pre>
 *
 * <ul>
 * <li><b>Parity</b>: nearest neighbour through the view reproduces the stored canonical deskew of
 *     the same raw file, every pixel of a whole plane, both camera halves. The geometry is the
 *     store's own (its provenance says 25 degrees, though the raw folder's parameter file says
 *     33.5): the comparison is only fair with the geometry the reference was made with.</li>
 * <li><b>Bead placement</b>: the bead acquisition's two halves, the right one mirrored and aligned
 *     with the acquisition's own tagged matrix file, coincide - the cross-correlation of their
 *     deskewed maximum projections peaks at zero shift - and folding them without the matrix does
 *     not. That separates registration from sampling: it checks where the beads land, not how
 *     the pixels are interpolated.</li>
 * </ul>
 */
public class OnlineDeskewRealDataTest {

	@Test
	public void nearestThroughTheViewIsTheStoredDeskew() throws Exception {
		String data = System.getProperty("opm.test.data", ""), zarr = System.getProperty("opm.test.zarr", "");
		Assume.assumeTrue("Set -Dopm.test.data and -Dopm.test.zarr", !data.isEmpty() && !zarr.isEmpty());
		OmeZarrDataset dataset = OmeZarrDataset.read(new File(zarr));
		OpmProvenance p = dataset.getProvenance();
		RawGeometry geometry = new RawGeometry(p.zStepSizeUm, p.xyPixelSizeUm, p.opmAngleDegrees, zarr);

		RawAcquisition acquisition = RawAcquisition.scan(new File(data));
		List<RawAcquisition.TimeKey> times = acquisition.times(1, 1);
		RawAcquisition.Entry entry = acquisition.entry(times.get(0), 1);
		assertEquals(entry.detail(), RawAcquisition.Readiness.COMPLETE, entry.validate());
		TiffRawVolumeStore store = new TiffRawVolumeStore(512L << 20, TiffRawVolumeStore.cellBytes(entry.layout()), 4, null);
		OmeZarrPlaneReader reader = new OmeZarrPlaneReader(dataset, "s0");
		try {
			long[] dims = reader.dimensions();		// x, y, z, c, t
			int w = (int) dims[0], h = (int) dims[1], z = 106;
			for (int channel = 0; channel < 2; channel++) {
				boolean left = channel == 0;
				// the store keeps both halves unmirrored, so the placement must not mirror either
				RawSourceSpec spec = RawSourceSpec.half(null, 1, left, !left, entry.layout().width, 0);
				RawPartSource<UnsignedShortType> source = new RawPartSource<UnsignedShortType>(acquisition, times, spec,
						geometry, store::blocking, new UnsignedShortType(), entry.layout().dimensions());
				RandomAccess<UnsignedShortType> view = onGrid(source, geometry, Interpolation.NEARESTNEIGHBOR);
				ImageProcessor stored = reader.readPlane(z, channel, 0);
				long differ = 0;
				for (int y = 0; y < h; y++)
					for (int x = 0; x < w; x++) {
						view.setPosition(new long[] { x, y, z });
						if (view.get().get() != stored.get(x, y)) differ++;
					}
				assertEquals(spec.key + ": pixels that differ from the stored deskew", 0, differ);
			}
		} finally {
			reader.close();
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	public void beadsOfTheTwoAlignedHalvesCoincide() throws Exception {
		String beads = System.getProperty("opm.test.beads", "");
		Assume.assumeTrue("Set -Dopm.test.beads", !beads.isEmpty());
		File folder = new File(beads);
		RawGeometry geometry = RawGeometry.fromExperimentalParameters(new File(folder, "ExperimentalParameters.txt"));
		AlignmentMatrixSet alignments = AlignmentMatrixSet.load(new File(folder, "beads-alignment.csv").getPath());
		RawAcquisition acquisition = RawAcquisition.scan(folder);
		List<RawAcquisition.TimeKey> times = acquisition.times(1, 1);
		RawAcquisition.Entry entry = acquisition.entry(times.get(0), 1);
		assertEquals(entry.detail(), RawAcquisition.Readiness.COMPLETE, entry.validate());
		TiffRawVolumeStore store = new TiffRawVolumeStore(1600L << 20, TiffRawVolumeStore.cellBytes(entry.layout()), 6, null);
		try {
			int width = entry.layout().width;
			RawSourceSpec left = RawSourceSpec.half(alignments, 1, true, false, width, 0);
			RawSourceSpec aligned = RawSourceSpec.half(alignments, 1, false, false, width, 0);
			RawSourceSpec folded = RawSourceSpec.half(null, 1, false, false, width, 0);
			assertTrue(aligned.mirror && aligned.matrix != null);
			long[] grid = geometry.deskewedGrid(width / 2, entry.layout().height, entry.layout().depth);
			float[][] mipLeft = maxProjection(acquisition, times, left, geometry, store, grid);
			float[][] mipAligned = maxProjection(acquisition, times, aligned, geometry, store, grid);
			float[][] mipFolded = maxProjection(acquisition, times, folded, geometry, store, grid);
			int[] alignedPeak = correlationPeak(mipLeft, mipAligned, 8);
			int[] foldedPeak = correlationPeak(mipLeft, mipFolded, 8);
			System.out.printf(Locale.US, "bead maxZ correlation peak (2x-downsampled grid): aligned %d,%d; folded only %d,%d%n",
					alignedPeak[0], alignedPeak[1], foldedPeak[0], foldedPeak[1]);
			assertEquals("aligned halves coincide in X", 0, alignedPeak[0]);
			assertEquals("aligned halves coincide in Y", 0, alignedPeak[1]);
			assertNotEquals("without the matrix they do not", 0, Math.abs(foldedPeak[0]) + Math.abs(foldedPeak[1]));
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
	}

	/** The view of one source in deskewed grid units: world divided by the pixel size. */
	private static RandomAccess<UnsignedShortType> onGrid(RawPartSource<UnsignedShortType> source, RawGeometry geometry,
			Interpolation method) {
		AffineTransform3D toGrid = new AffineTransform3D();
		source.getSourceTransform(0, 0, toGrid);
		AffineTransform3D unscale = new AffineTransform3D();
		unscale.scale(1 / geometry.dxyUm);
		toGrid.preConcatenate(unscale);
		return RealViews.affine(source.getInterpolatedSource(0, 0, method), toGrid).randomAccess();
	}

	/** Maximum along the deskewed Z of every second grid pixel in X and Y, every second plane. */
	private static float[][] maxProjection(RawAcquisition acquisition, List<RawAcquisition.TimeKey> times, RawSourceSpec spec,
			RawGeometry geometry, TiffRawVolumeStore store, long[] grid) {
		RawAcquisition.Entry entry = acquisition.entry(times.get(0), 1);
		RawPartSource<UnsignedShortType> source = new RawPartSource<UnsignedShortType>(acquisition, times, spec, geometry,
				store::blocking, new UnsignedShortType(), entry.layout().dimensions());
		RandomAccess<UnsignedShortType> view = onGrid(source, geometry, Interpolation.NEARESTNEIGHBOR);
		int nx = (int) (grid[0] / 2), ny = (int) (grid[1] / 2);
		float[][] mip = new float[ny][nx];
		for (long z = 0; z < grid[2]; z += 2)
			for (int y = 0; y < ny; y++)
				for (int x = 0; x < nx; x++) {
					view.setPosition(new long[] { 2L * x, 2L * y, z });
					mip[y][x] = Math.max(mip[y][x], view.get().get());
				}
		return mip;
	}

	/** Integer shift of {@code b} against {@code a} with the highest normalised correlation. */
	private static int[] correlationPeak(float[][] a, float[][] b, int range) {
		int ny = a.length, nx = a[0].length;
		double best = -Double.MAX_VALUE;
		int[] at = { 0, 0 };
		for (int dy = -range; dy <= range; dy++)
			for (int dx = -range; dx <= range; dx++) {
				double sab = 0, saa = 0, sbb = 0, sa = 0, sb = 0;
				long n = 0;
				for (int y = range; y < ny - range; y++)
					for (int x = range; x < nx - range; x++) {
						double va = a[y][x], vb = b[y + dy][x + dx];
						sab += va * vb; saa += va * va; sbb += vb * vb; sa += va; sb += vb; n++;
					}
				double cov = sab / n - sa / n * sb / n;
				double r = cov / Math.sqrt((saa / n - sa / n * sa / n) * (sbb / n - sb / n * sb / n));
				if (r > best) {
					best = r;
					at[0] = dx;
					at[1] = dy;
				}
			}
		return at;
	}
}
