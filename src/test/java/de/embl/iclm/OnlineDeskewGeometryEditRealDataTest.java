package de.embl.iclm;

import static org.junit.Assert.*;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;

import org.junit.Assume;
import org.junit.Test;

import bdv.viewer.Interpolation;
import bdv.viewer.SourceAndConverter;
import ij.process.ImageProcessor;
import net.imglib2.RandomAccess;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.integer.UnsignedShortType;

/**
 * Changing the deskew parameters of an open Online Deskew View, on a real acquisition. The raw folder's parameter file says
 * 33.5 degrees; its OME-Zarr result was deskewed with the 25 degrees its provenance records. A view opened from the folder
 * and then given the store's geometry in place must draw exactly the stored deskew - the parity
 * {@link OnlineDeskewRealDataTest} shows for a source made at that geometry from the start - and did not before the edit.
 * Completed-frame redraws after each edit are timed. Skipped unless the data are named:
 * <pre>
 * mvn test -Dtest=OnlineDeskewGeometryEditRealDataTest -Dopm.test.gui=true -Dopm.test.jvm.args=-Xmx8g
 *     -Dopm.test.data=E:\OPM\3_timelapse_0
 *     -Dopm.test.zarr=E:\OPM\3_timelapse_0\result_ome-zarr_v2.1.6\3_timelapse_0.ome.zarr
 * </pre>
 */
public class OnlineDeskewGeometryEditRealDataTest {

	private static <T> T edt(Callable<T> work) throws Exception {
		final Object[] result = new Object[1];
		final Throwable[] failure = new Throwable[1];
		SwingUtilities.invokeAndWait(() -> {
			try { result[0] = work.call(); } catch (Throwable t) { failure[0] = t; }
		});
		if (failure[0] != null) throw new AssertionError(failure[0]);
		@SuppressWarnings("unchecked") T out = (T) result[0];
		return out;
	}

	@Test public void anOpenViewGivenTheStoresGeometryDrawsTheStoredDeskew() throws Exception {
		String data = System.getProperty("opm.test.data", ""), zarr = System.getProperty("opm.test.zarr", "");
		Assume.assumeTrue("Set -Dopm.test.gui=true, -Dopm.test.data and -Dopm.test.zarr",
				Boolean.getBoolean("opm.test.gui") && !data.isEmpty() && !zarr.isEmpty());
		OmeZarrDataset dataset = OmeZarrDataset.read(new File(zarr));
		OpmProvenance p = dataset.getProvenance();
		RawGeometry stored = new RawGeometry(p.zStepSizeUm, p.xyPixelSizeUm, p.opmAngleDegrees, OnlineDeskewSession.EDITED_ORIGIN);

		double remembered = ij.Prefs.get("opm.onlineDeskew.position", 1);
		OnlineDeskewView.Settings settings = new OnlineDeskewView.Settings();
		settings.folder = data;
		settings.position = 1;
		settings.channel = 1;
		settings.parameterSource = OnlineDeskewView.PARAMETER_SOURCES[0];
		settings.budgetMb = 1600;
		OnlineDeskewSession session = OnlineDeskewSession.open(settings);
		OmeZarrPlaneReader reader = new OmeZarrPlaneReader(dataset, "s0");
		try {
			RawGeometry opened = session.geometry();
			System.out.println("opened with " + opened + "; the store was deskewed with " + stored);
			Assume.assumeFalse("the store and the folder agree; nothing to edit", opened.angleDeg == stored.angleDeg
					&& opened.dzUm == stored.dzUm && opened.dxyUm == stored.dxyUm);
			edt(() -> { session.handle().getViewerPanel().setTimepoint(0); return null; });
			ImageProcessor plane = reader.readPlane(106, 0, 0);		// the left camera half, as stored
			long before = differing(left(session), opened, plane, 4);
			System.out.println("before the edit, of every 16th pixel of plane 106, differing from the stored deskew: " + before);
			assertTrue("the opened view is not the stored deskew: " + before + " of every 16th pixel differ", before > 0);

			assertTrue(edt(() -> session.setGeometry(stored)));
			long after = differing(left(session), stored, plane, 1);
			System.out.println("after the edit, pixels of plane 106 that differ from the stored deskew: " + after);
			assertEquals("the edited view is the stored deskew", 0, after);

			// completed frames: how long a redraw takes after an edit, on this machine, at this canvas
			edt(() -> { session.playback().setMode(RawPlayback.Mode.COMPLETED); return null; });
			RawStrictPlayer.Result first = latest(session);
			assertEquals(first.toString(), RawStrictPlayer.Outcome.PRESENTED, first.outcome);
			for (RawGeometry g : new RawGeometry[] { opened, stored, opened }) {
				long start = System.nanoTime();
				assertTrue(edt(() -> session.setGeometry(g)));
				RawStrictPlayer.Result r = latest(session);
				long millis = (System.nanoTime() - start) / 1000000;
				assertEquals(r.toString(), RawStrictPlayer.Outcome.PRESENTED, r.outcome);
				assertEquals(0, r.coverageMisses);
				System.out.printf(Locale.US, "geometry edit to %.4g deg, completed frame %d x %d, time index %d: presented in %d ms (%s)%n",
						g.angleDeg, r.request.width, r.request.height, r.request.timeIndex, millis, r);
			}
		} finally {
			reader.close();
			session.close();
			assertTrue(session.closed().get(15, TimeUnit.SECONDS).drained);
			ij.Prefs.set("opm.onlineDeskew.position", remembered);
		}
	}

	/** The view's left-half source - the stored left half is never mirrored either - as BigDataViewer has it now. */
	@SuppressWarnings("unchecked")
	private static RawPartSource<UnsignedShortType> left(OnlineDeskewSession session) throws Exception {
		return edt(() -> {
			for (SourceAndConverter<?> soc : session.handle().getViewerPanel().state().getSources()) {
				RawPartSource<?> source = (RawPartSource<?>) soc.getSpimSource();
				if (source.spec().key.equals(ChannelOperationSettings.sourceKey(1, true))) return (RawPartSource<UnsignedShortType>) source;
			}
			throw new AssertionError("no left half");
		});
	}

	/** Pixels of every {@code step}-th row and column of a stored plane that nearest neighbour through the view does not give. */
	private static long differing(RawPartSource<UnsignedShortType> source, RawGeometry geometry, ImageProcessor stored, int step) {
		AffineTransform3D toGrid = new AffineTransform3D();
		source.getSourceTransform(0, 0, toGrid);
		AffineTransform3D unscale = new AffineTransform3D();
		unscale.scale(1 / geometry.dxyUm);
		toGrid.preConcatenate(unscale);
		RandomAccess<UnsignedShortType> view = RealViews.affine(source.getInterpolatedSource(0, 0, Interpolation.NEARESTNEIGHBOR),
				toGrid).randomAccess();
		long differ = 0;
		for (int y = 0; y < stored.getHeight(); y += step)
			for (int x = 0; x < stored.getWidth(); x += step) {
				view.setPosition(new long[] { x, y, 106 });
				if (view.get().get() != stored.get(x, y)) differ++;
			}
		return differ;
	}

	private static RawStrictPlayer.Result latest(OnlineDeskewSession session) throws Exception {
		edt(() -> null);
		CompletableFuture<RawStrictPlayer.Result> f = edt(session::navigationResult);
		return f.get(120, TimeUnit.SECONDS);
	}
}
