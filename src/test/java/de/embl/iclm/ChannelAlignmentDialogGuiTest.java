package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.MouseWheelEvent;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.gui.GUI;
import ij.gui.Overlay;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

/**
 * The manual X/Y/rotation of Channel Alignment, driven through the dialog's own controls with the
 * overlay in a real window, and judged by what that window draws.
 * <p>
 * Needs a display, so it runs only with {@code -Dopm.test.gui=true}. The headless half is
 * {@link ChannelAlignmentPreviewTest}.
 */
public class ChannelAlignmentDialogGuiTest {

	private static final int WIDTH = 64, HEIGHT = 48, BEAD_X = 20, BEAD_Y = 24;
	/** The interest-point fit of channel 2: three pixels to the right. */
	private static final double FIT_X = 3;

	private ChannelAlignment.Dialog dialog;
	private String movingKey;

	@Before
	public void needsADisplay() {
		Assume.assumeTrue("Enable with -Dopm.test.gui=true", Boolean.getBoolean("opm.test.gui"));
		Assume.assumeFalse(GraphicsEnvironment.isHeadless());
	}

	@After
	public void close() throws Exception {
		if (dialog != null) edt(new Step() { public void run() throws Exception {
			call("closePreview");
			dialog.dispose();
		} });
	}

	@Test
	public void theSpinnersMoveTheChannelInTheOverlayAsTheyChange() throws Exception {
		openPreview();
		assertEquals("channel 1 is the reference", BEAD_X, peak(0));
		assertEquals("channel 2 starts at its fit", BEAD_X + 3, peak(1));

		manualAdjustment(true);
		setSpinner("translateX", 1, 1.0);
		assertEquals("X +1 moves channel 2 right, with no Apply", BEAD_X + 4, peak(1));
		assertEquals(FIT_X + 1, savedX(), 1e-12);
		setSpinner("translateY", 1, 2.0);
		assertEquals("Y +2 moves it down", BEAD_Y + 2, peakRow(1));
		assertEquals("its own window is never transformed", BEAD_X, ownWindowPeak(1));

		wheel("translateX", 1, -1);
		assertEquals("a wheel step up is +0.5", FIT_X + 1.5, savedX(), 1e-12);

		click("resetAdjustments", 1);
		assertEquals("Reset returns channel 2 to its fit", BEAD_X + 3, peak(1));
		assertEquals(BEAD_Y, peakRow(1));
		assertEquals(0.0, spinner("translateX", 1), 0.0);
		assertEquals(FIT_X, savedX(), 1e-12);
	}

	@Test
	public void theReferenceRowMovesEveryOtherChannelTheOtherWay() throws Exception {
		openPreview();
		manualAdjustment(true);
		setSpinner("translateX", 0, 1.0);
		assertEquals("channel 1 stays the identity reference", BEAD_X, peak(0));
		assertEquals(BEAD_X + 2, peak(1));
		assertEquals(FIT_X - 1, savedX(), 1e-12);
		click("resetAdjustments", 0);
		assertEquals(BEAD_X + 3, peak(1));
	}

	@Test
	public void untickingSetsTheRowsAsideWithoutLosingThem() throws Exception {
		openPreview();
		manualAdjustment(true);
		setSpinner("translateX", 1, 2.0);
		assertEquals(BEAD_X + 5, peak(1));
		manualAdjustment(false);
		assertEquals("unticked: the overlay and the saved matrix are the fit", BEAD_X + 3, peak(1));
		assertEquals(FIT_X, savedX(), 1e-12);
		assertEquals("the row is kept", 2.0, spinner("translateX", 1), 0.0);
		manualAdjustment(true);
		assertEquals(BEAD_X + 5, peak(1));
	}

	@Test
	public void afterRemoveAllResetReturnsToTheIdentityNotTheFit() throws Exception {
		openPreview();
		manualAdjustment(true);
		click("removeAlignment", -1);
		assertEquals("no transform at all", BEAD_X, peak(1));
		setSpinner("translateX", 1, 1.0);
		assertEquals(BEAD_X + 1, peak(1));
		click("resetAdjustments", 1);
		assertEquals("Reset goes back to what preceded the adjustment", BEAD_X, peak(1));
		assertEquals(0, savedX(), 1e-12);
	}

	@Test
	public void theChannelWindowsAndTheOverlayShowInterestPointsSeparately() throws Exception {
		openPreview();
		assertEquals(3, windowPoints(0)); assertEquals(3, windowPoints(1));
		assertEquals(3, overlayPoints(0)); assertEquals(3, overlayPoints(1));

		click("detectionChannels", 1);
		assertEquals("the channel row hides the window's points", 0, windowPoints(1));
		assertEquals("and not the overlay's", 3, overlayPoints(1));

		click("overlayPointChannels", 0);
		assertEquals("the overlay row hides the overlay's points", 0, overlayPoints(0));
		assertEquals("and not the window's", 3, windowPoints(0));

		click("overlayChannels", 1);
		assertEquals("a channel's image hidden in the overlay does not hide its points there", 3, overlayPoints(1));
	}

	@Test
	public void labelsAreSwitchedPerChannelInBothViews() throws Exception {
		openPreview();
		assertTrue(windowLabels(1)); assertTrue(overlayLabels(1));
		click("labelChannels", 1);
		assertFalse("the window's points lose their numbers", windowLabels(1));
		assertFalse("and so do the overlay's", overlayLabels(1));
		assertTrue("another channel keeps its own", overlayLabels(0));
	}

	@Test
	public void aClickOnHiddenPointsDuringManualDrawingDoesNotReplaceThem() throws Exception {
		openPreview();
		edt(new Step() { public void run() throws Exception { ((JCheckBox) field("manualSelection")).doClick(); } });
		assertEquals("drawing starts on channel 1 with its points", 3, activeOverlayPoints());

		click("overlayPointChannels", 0);
		assertEquals("hidden", 0, activeOverlayPoints());
		edt(new Step() { public void run() throws Exception {
			// What the multipoint tool leaves after a click on the empty overlay.
			((ImagePlus) field("overlayPreviewImage")).setRoi(new PointRoi(5, 5));
			Method capture = ChannelAlignment.Dialog.class.getDeclaredMethod("captureActiveManualPoints");
			capture.setAccessible(true);
			capture.invoke(dialog);
		} });
		assertEquals("the stray click is not kept", 0, activeOverlayPoints());

		click("overlayPointChannels", 0);
		assertEquals("shown again, with the points it had", 3, activeOverlayPoints());
	}

	@Test
	public void theDialogFitsTheScreenScrollsAndFoldsItsSections() throws Exception {
		edt(new Step() { @SuppressWarnings("unchecked") public void run() throws Exception {
			dialog = new ChannelAlignment.Dialog();
			dialog.setVisible(true);
			assertEquals("Align Channel of OPM Data", dialog.getTitle());
			assertEquals("the OPM dialog color", Parameter.frameColor, dialog.getContentPane().getBackground());

			JButton more = (JButton) field("more");
			for (int i = 0; i < 6; i++) more.doClick();
			dialog.validate();
			assertFalse("a row added after the dialog was built takes the color too",
					((List<JCheckBox>) field("overlayChannels")).get(7).isOpaque());
			Rectangle screen = GUI.getMaxWindowBounds(dialog);
			assertTrue("never taller than the screen allows", dialog.getHeight() <= (int) (screen.height * 0.9));
			JScrollPane scroll = (JScrollPane) field("scroll");
			boolean taller = scroll.getViewport().getView().getPreferredSize().height
					> scroll.getViewport().getExtentSize().height;
			assertEquals("the sections scroll exactly when they do not fit",
					taller, scroll.getVerticalScrollBar().isVisible());
			int fitted = dialog.getHeight();

			dialog.setSize(dialog.getWidth(), 300);
			dialog.validate();
			assertTrue("shrunk by hand, the rest is a scroll away", scroll.getVerticalScrollBar().isVisible());
			assertTrue("and OK stays on screen", ((JButton) field("ok")).isShowing());

			List<ChannelAlignment.Dialog.Fold> folds = (List<ChannelAlignment.Dialog.Fold>) field("folds");
			assertEquals(5, folds.size());
			for (ChannelAlignment.Dialog.Fold fold : folds) fold.heading.doClick();
			dialog.validate();
			JCheckBox manual = (JCheckBox) field("manual");
			assertFalse("a folded section's controls are hidden", manual.isShowing());
			assertFalse("headings alone need no scrolling", scroll.getVerticalScrollBar().isVisible());
			assertTrue("the window shrinks to them", dialog.getHeight() < fitted);
			assertTrue(folds.get(4).heading.getText().startsWith("►"));

			folds.get(4).heading.doClick();
			dialog.validate();
			assertTrue("unfolded, they are back", manual.isShowing());
			assertTrue(folds.get(4).heading.getText().startsWith("▼"));
		} });
	}

	private void openPreview() throws Exception {
		File first = new File("beads_Channel0001.tif"), second = new File("beads_Channel0002.tif");
		final List<ChannelAlignment.SourceChoice> sources = Arrays.asList(
				new ChannelAlignment.SourceChoice(first, BeadAlignment.Side.LEFT, 1),
				new ChannelAlignment.SourceChoice(second, BeadAlignment.Side.LEFT, 2));
		String referenceKey = sources.get(0).key();
		movingKey = sources.get(1).key();
		LinkedHashMap<String, ImageProcessor> projections = new LinkedHashMap<String, ImageProcessor>();
		projections.put(referenceKey, bead());
		projections.put(movingKey, bead());
		double[][] fit = { { 1, 0, FIT_X }, { 0, 1, 0 } };
		AlignmentMatrixSet matrices = new AlignmentMatrixSet(referenceKey);
		AlignmentMatrixSet computed = new AlignmentMatrixSet(referenceKey);
		matrices.put(movingKey, fit);
		computed.put(movingKey, fit);
		// Three correspondences: each moving spot lands on its reference spot through the fit.
		List<BeadAlignment.Spot> referenceSpots = Arrays.asList(new BeadAlignment.Spot(10, 10, 1),
				new BeadAlignment.Spot(40, 12, 1), new BeadAlignment.Spot(25, 36, 1));
		List<BeadAlignment.Spot> movingSpots = Arrays.asList(new BeadAlignment.Spot(10 - FIT_X, 10, 1),
				new BeadAlignment.Spot(40 - FIT_X, 12, 1), new BeadAlignment.Spot(25 - FIT_X, 36, 1));
		LinkedHashMap<String, BeadAlignment.Result> results = new LinkedHashMap<String, BeadAlignment.Result>();
		results.put(movingKey, new BeadAlignment.Result(fit, referenceSpots, movingSpots, 3, 0, "test"));
		final ChannelAlignment.Computation computation = new ChannelAlignment.Computation(matrices, computed,
				projections, results, new LinkedHashMap<String, String>(), sources, referenceSpots);
		edt(new Step() { public void run() throws Exception {
			dialog = new ChannelAlignment.Dialog();
			Method show = ChannelAlignment.Dialog.class.getDeclaredMethod("showPreview",
					ChannelAlignment.Computation.class, boolean.class);
			show.setAccessible(true);
			show.invoke(dialog, computation, true);
		} });
	}

	/** One bright pixel on a dim bead, so a whole-pixel shift is exact under either interpolation. */
	private static FloatProcessor bead() {
		FloatProcessor image = new FloatProcessor(WIDTH, HEIGHT);
		for (int y = -2; y <= 2; y++) for (int x = -2; x <= 2; x++)
			image.setf(BEAD_X + x, BEAD_Y + y, (float) (1000 * Math.exp(-(x * x + y * y) / 2.0)));
		return image;
	}

	/** Column of the brightest drawn pixel of one overlay channel: red is channel 1, green channel 2. */
	private int peak(int channel) throws Exception {
		return (Integer) drawnPeak(channel)[0];
	}

	private int peakRow(int channel) throws Exception {
		return (Integer) drawnPeak(channel)[1];
	}

	private Object[] drawnPeak(final int channel) throws Exception {
		final Object[] result = new Object[2];
		edt(new Step() { public void run() throws Exception {
			CompositeImage overlay = (CompositeImage) field("overlayPreviewImage");
			ColorProcessor drawn = new ColorProcessor(overlay.getImage());
			int brightest = -1;
			int[] rgb = new int[3];
			for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
				drawn.getPixel(x, y, rgb);
				if (rgb[channel] > brightest) { brightest = rgb[channel]; result[0] = x; result[1] = y; }
			}
		} });
		return result;
	}

	private int ownWindowPeak(final int channel) throws Exception {
		final int[] result = new int[1];
		edt(new Step() { public void run() throws Exception {
			@SuppressWarnings("unchecked")
			ImageProcessor own = ((List<ImagePlus>) field("channelPreviewImages")).get(channel).getProcessor();
			float brightest = -1;
			for (int x = 0; x < WIDTH; x++) if (own.getf(x, BEAD_Y) > brightest) {
				brightest = own.getf(x, BEAD_Y); result[0] = x;
			}
		} });
		return result[0];
	}

	/** Points drawn in one channel's own window, as an overlay or as the ROI being modified. */
	private int windowPoints(final int channel) throws Exception {
		final int[] result = new int[1];
		edt(new Step() { public void run() throws Exception {
			@SuppressWarnings("unchecked")
			ImagePlus own = ((List<ImagePlus>) field("channelPreviewImages")).get(channel);
			PointRoi points = windowRoi(own);
			result[0] = points == null ? 0 : points.size();
		} });
		return result[0];
	}

	private boolean windowLabels(final int channel) throws Exception {
		final boolean[] result = new boolean[1];
		edt(new Step() { public void run() throws Exception {
			@SuppressWarnings("unchecked")
			ImagePlus own = ((List<ImagePlus>) field("channelPreviewImages")).get(channel);
			result[0] = windowRoi(own).getShowLabels();
		} });
		return result[0];
	}

	private static PointRoi windowRoi(ImagePlus image) {
		if (image.getRoi() instanceof PointRoi) return (PointRoi) image.getRoi();
		Overlay overlay = image.getOverlay();
		return overlay == null || overlay.size() == 0 ? null : (PointRoi) overlay.get(0);
	}

	/** Points of one channel in the overlay, told apart by color: red is channel 1, green channel 2. */
	private int overlayPoints(int channel) throws Exception {
		PointRoi points = overlayRoi(channel);
		return points == null ? 0 : points.size();
	}

	private boolean overlayLabels(int channel) throws Exception {
		return overlayRoi(channel).getShowLabels();
	}

	private PointRoi overlayRoi(final int channel) throws Exception {
		final PointRoi[] result = new PointRoi[1];
		edt(new Step() { public void run() throws Exception {
			Overlay overlay = ((ImagePlus) field("overlayPreviewImage")).getOverlay();
			if (overlay == null) return;
			for (Roi roi : overlay.toArray()) {
				java.awt.Color color = roi.getStrokeColor();
				int mine = channel == 0 ? color.getRed() : color.getGreen();
				int other = channel == 0 ? color.getGreen() : color.getRed();
				if (mine > 0 && other == 0) result[0] = (PointRoi) roi;
			}
		} });
		return result[0];
	}

	/** Points of the editable ROI manual drawing puts on the overlay. */
	private int activeOverlayPoints() throws Exception {
		final int[] result = new int[1];
		edt(new Step() { public void run() throws Exception {
			Roi roi = ((ImagePlus) field("overlayPreviewImage")).getRoi();
			result[0] = roi instanceof PointRoi ? ((PointRoi) roi).size() : 0;
		} });
		return result[0];
	}

	private double savedX() throws Exception {
		final double[] result = new double[1];
		edt(new Step() { public void run() throws Exception {
			ChannelAlignment.Computation computation = (ChannelAlignment.Computation) field("previewComputation");
			result[0] = computation.matrices.matrixFor(movingKey)[0][2];
		} });
		return result[0];
	}

	private void manualAdjustment(final boolean on) throws Exception {
		edt(new Step() { public void run() throws Exception {
			JCheckBox manual = (JCheckBox) field("manual");
			if (manual.isSelected() != on) manual.doClick();
		} });
	}

	private void setSpinner(final String list, final int channel, final double value) throws Exception {
		edt(new Step() { public void run() throws Exception { spinnerAt(list, channel).setValue(value); } });
	}

	private double spinner(final String list, final int channel) throws Exception {
		final double[] result = new double[1];
		edt(new Step() { public void run() throws Exception {
			result[0] = ((Number) spinnerAt(list, channel).getValue()).doubleValue();
		} });
		return result[0];
	}

	private void wheel(final String list, final int channel, final int rotation) throws Exception {
		edt(new Step() { public void run() throws Exception {
			JSpinner spinner = spinnerAt(list, channel);
			spinner.dispatchEvent(new MouseWheelEvent(spinner, MouseWheelEvent.MOUSE_WHEEL,
					System.currentTimeMillis(), 0, 1, 1, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, rotation));
		} });
	}

	private void click(final String name, final int channel) throws Exception {
		edt(new Step() { public void run() throws Exception {
			Object button = field(name);
			if (channel >= 0) button = ((List<?>) button).get(channel);
			((AbstractButton) button).doClick();
		} });
	}

	@SuppressWarnings("unchecked")
	private JSpinner spinnerAt(String list, int channel) throws Exception {
		return ((List<JSpinner>) field(list)).get(channel);
	}

	private Object field(String name) throws Exception {
		Field field = ChannelAlignment.Dialog.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(dialog);
	}

	private void call(String name) throws Exception {
		Method method = ChannelAlignment.Dialog.class.getDeclaredMethod(name);
		method.setAccessible(true);
		method.invoke(dialog);
	}

	private interface Step { void run() throws Exception; }

	/** Run on the EDT, twice over: the second pass lets a refresh the first one queued finish. */
	private static void edt(final Step step) throws Exception {
		final Exception[] failure = new Exception[1];
		SwingUtilities.invokeAndWait(new Runnable() {
			@Override public void run() {
				try { step.run(); } catch (Exception e) { failure[0] = e; }
			}
		});
		SwingUtilities.invokeAndWait(new Runnable() { @Override public void run() { } });
		if (failure[0] != null) throw failure[0];
	}
}
