package de.embl.iclm;

import static org.junit.Assert.*;

import org.junit.Test;

import net.imglib2.realtransform.AffineTransform3D;

/** The arithmetic behind the Online Deskew View's {@code Deskew parameters...}; headless. */
public class OnlineDeskewGeometryEditTest {

	@Test public void anUneditedFieldKeepsTheExactValueItWasOfferedWith() {
		// a parameter file's 0.26512 um is offered as 0.2651: a plain OK must not turn that into an edit
		assertEquals(0.26512, OnlineDeskewSession.fieldValue("0.2651", "0.2651", 0.26512), 0);
		assertEquals("spaces are no edit", 0.26512, OnlineDeskewSession.fieldValue(" 0.2651 ", "0.2651", 0.26512), 0);
		assertEquals(0.2651, OnlineDeskewSession.fieldValue("0.26510", "0.2651", 0.26512), 0);
		assertEquals(26, OnlineDeskewSession.fieldValue("26", "33.50", 33.5), 0);
		assertTrue(Double.isNaN(OnlineDeskewSession.fieldValue("2x", "33.50", 33.5)));
		assertTrue(Double.isNaN(OnlineDeskewSession.fieldValue("", "33.50", 33.5)));
	}

	@Test public void theShownValuesAskForNothingAndRefusedValuesSayWhy() {
		RawGeometry shown = new RawGeometry(0.265, 0.116, 33.5, "ExperimentalParameters.txt");
		assertNull(OnlineDeskewSession.requested(shown, 0.265, 0.116, 33.5));
		RawGeometry steep = OnlineDeskewSession.requested(shown, 0.265, 0.116, 26);
		assertEquals(26, steep.angleDeg, 0);
		assertEquals(0.265, steep.dzUm, 0);
		assertEquals(OnlineDeskewSession.EDITED_ORIGIN, steep.origin);
		double[][] refused = { { 0, 0.116, 26 }, { 0.265, -0.1, 26 }, { 0.265, 0.116, 0 }, { 0.265, 0.116, 90 },
				{ 0.265, 0.116, Double.NaN }, { Double.POSITIVE_INFINITY, 0.116, 26 } };
		for (double[] v : refused)
			try {
				OnlineDeskewSession.requested(shown, v[0], v[1], v[2]);
				fail("accepted " + java.util.Arrays.toString(v));
			} catch (IllegalArgumentException expected) {
				assertFalse(expected.getMessage().isEmpty());
			}
	}

	@Test public void anchoringOnlyMovesTheCameraAndKeepsTheCentredRawPointCentred() {
		RawGeometry from = new RawGeometry(0.265, 0.116, 33.5, "a"), to = new RawGeometry(0.265, 0.116, 26, "b");
		long[] dims = { 64, 40, 20 };
		for (RawSourceSpec spec : new RawSourceSpec[] { RawSourceSpec.whole(null, 1, 0xffffff),
				RawSourceSpec.half(null, 1, false, false, 64, 0xff00ff), RawSourceSpec.half(null, 1, true, true, 64, 0x00ff00) }) {
			AffineTransform3D before = from.sourceToWorld(dims, spec, null), after = to.sourceToWorld(dims, spec, null);
			AffineTransform3D view = new AffineTransform3D();
			view.rotate(0, 0.4);
			view.rotate(2, -0.3);
			view.scale(3.5);
			view.translate(120, -40, 7);
			double width = 560, height = 377;
			AffineTransform3D original = view.copy(), b = before.copy(), a = after.copy();
			AffineTransform3D moved = OnlineDeskewSession.anchored(view, before, after, width, height);
			assertArrayEquals("arguments untouched", original.getRowPackedCopy(), view.getRowPackedCopy(), 0);
			assertArrayEquals(b.getRowPackedCopy(), before.getRowPackedCopy(), 0);
			assertArrayEquals(a.getRowPackedCopy(), after.getRowPackedCopy(), 0);
			for (int r = 0; r < 3; r++)
				for (int c = 0; c < 3; c++) assertEquals("neither turned nor scaled", view.get(r, c), moved.get(r, c), 0);
			double[] centre = { width / 2, height / 2, 0 }, world = new double[3], raw = new double[3], screen = new double[3];
			view.applyInverse(world, centre);
			before.applyInverse(raw, world);
			after.apply(raw, world);
			moved.apply(world, screen);
			assertArrayEquals(spec.key, centre, screen, 1e-9);
			assertFalse("a real move", java.util.Arrays.equals(view.getRowPackedCopy(), moved.getRowPackedCopy()));
		}
		// the same geometry moves nothing
		AffineTransform3D view = new AffineTransform3D();
		view.scale(2);
		AffineTransform3D same = from.sourceToWorld(dims, RawSourceSpec.whole(null, 1, 0), null);
		assertArrayEquals(view.getRowPackedCopy(), OnlineDeskewSession.anchored(view, same, same, 500, 400).getRowPackedCopy(), 1e-9);
	}
}
