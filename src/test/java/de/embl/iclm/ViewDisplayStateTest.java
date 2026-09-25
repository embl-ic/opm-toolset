package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Color;

import org.junit.Test;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.LUT;
import ij.process.ShortProcessor;

/**
 * What a materialised region takes from the view it was cut out of.
 *
 * <p>The point of the feature is that the window the user set up - the colours, the contrast
 * they adjusted per channel, composite or not, the plane and time point they had reached - is
 * the window the cut comes out looking like. The crop is smaller in every axis, so the position
 * has to be carried through the same offsets the region was cut with, and a position the crop
 * does not contain has to land on its nearest plane rather than silently on the first.
 */
public class ViewDisplayStateTest {

	private static final int WIDTH = 4;
	private static final int HEIGHT = 3;

	@Test
	public void eachChannelKeepsItsColourAndContrastThroughAChannelRange() {
		CompositeImage source = composite(4, 6, 5);
		source.setLuts(new LUT[] {
			ranged(Color.RED, 10, 100), ranged(Color.GREEN, 20, 200),
			ranged(Color.BLUE, 30, 300), ranged(Color.WHITE, 40, 400) });
		source.setMode(CompositeImage.COLOR);
		boolean[] shown = source.getActiveChannels();
		shown[0] = true; shown[1] = false; shown[2] = true; shown[3] = true;
		source.setPosition(3, 4, 2);

		ViewDisplayState state = ViewDisplayState.capture(source);
		// the crop takes source channels 2-3, Z 3-5 and time points 2-3
		CompositeImage target = composite(2, 3, 2);
		state.applyTo(target, 1, 2, 1);

		LUT[] carried = target.getLuts();
		assertEquals("source channel 2's range", 20.0, carried[0].min, 1e-9);
		assertEquals(200.0, carried[0].max, 1e-9);
		assertEquals("source channel 3's range", 30.0, carried[1].min, 1e-9);
		assertEquals(300.0, carried[1].max, 1e-9);
		assertEquals("green is carried, not re-derived",
				Color.GREEN.getRGB(), colourOf(carried[0]));
		assertEquals(Color.BLUE.getRGB(), colourOf(carried[1]));
		assertEquals(CompositeImage.COLOR, target.getMode());
		assertFalse("channel 2 was unticked in the source", target.getActiveChannels()[0]);
		assertTrue(target.getActiveChannels()[1]);
	}

	@Test
	public void thePositionFollowsTheCropRatherThanStartingOver() {
		CompositeImage source = composite(4, 6, 5);
		source.setPosition(3, 4, 2);
		ViewDisplayState state = ViewDisplayState.capture(source);

		CompositeImage target = composite(2, 3, 2);
		state.applyTo(target, 1, 2, 1);
		assertEquals("channel 3 of the view is channel 2 of the crop", 2, target.getChannel());
		assertEquals("Z 4 of the view is Z 2 of a crop starting at 3", 2, target.getSlice());
		assertEquals("time point 2 of the view is the crop's first", 1, target.getFrame());
	}

	@Test
	public void aPositionTheCropDoesNotContainLandsOnItsNearestPlane() {
		CompositeImage source = composite(2, 8, 4);
		source.setPosition(1, 8, 4);
		ViewDisplayState state = ViewDisplayState.capture(source);

		CompositeImage target = composite(2, 3, 2);
		state.applyTo(target, 0, 0, 0);			// a crop of the first planes only
		assertEquals("clamped to the last plane there is, not reset to the first",
				3, target.getSlice());
		assertEquals(2, target.getFrame());

		CompositeImage before = composite(2, 3, 2);
		state.applyTo(before, 0, 7, 3);			// a crop starting after the source position
		assertEquals(1, before.getSlice());
		assertEquals(1, before.getFrame());
	}

	@Test
	public void aSingleChannelViewCarriesItsRangeAndItsTable() {
		ImagePlus source = plain(4, 1);
		source.setDisplayRange(15, 250);
		source.getProcessor().setColorModel(ranged(Color.MAGENTA, 15, 250));
		ViewDisplayState state = ViewDisplayState.capture(source);

		ImagePlus target = plain(2, 1);
		state.applyTo(target, 0, 1, 0);
		assertEquals(15.0, target.getDisplayRangeMin(), 1e-9);
		assertEquals(250.0, target.getDisplayRangeMax(), 1e-9);
	}

	/**
	 * The crop keeps what the open path wrote about itself and gains what is still true of it.
	 *
	 * <p>The origin is the crop's own - it starts somewhere else - and must not be taken from
	 * the view, or an ROI drawn on the crop would be applied twice over.
	 */
	@Test
	public void theCropKeepsItsOwnStampsAndGainsTheViewsDescription() {
		ImagePlus source = plain(2, 1);
		source.setProperty("Info", "OME-Zarr: E:/OPM/run.ome.zarr\nChannels: [a, b]");
		source.setProperty("opm.zarrRoot", "E:/OPM/run.ome.zarr");
		source.setProperty("opm.viewOrigin", "0,0");
		source.setProperty("opm.contentKind", "deskewed");

		ImagePlus target = plain(2, 1);
		target.setProperty("Info", "OME-Zarr: E:/OPM/run.ome.zarr\nChannels: [b]");
		target.setProperty("opm.viewOrigin", "120,64");

		ViewDisplayState.carryMetadata(source, target, "OPM materialised region\nz = 3-5 of 6");

		assertEquals("the crop's own origin is not overwritten",
				"120,64", target.getProperty("opm.viewOrigin"));
		assertEquals("still true of a part of the pixels",
				"deskewed", target.getProperty("opm.contentKind"));
		assertEquals("E:/OPM/run.ome.zarr", target.getProperty("opm.zarrRoot"));
		String info = (String) target.getProperty("Info");
		assertTrue("keeps what the open path wrote: " + info, info.contains("Channels: [b]"));
		assertTrue("and says what was cut: " + info, info.contains("z = 3-5 of 6"));
	}

	@Test
	public void aViewWithNothingToTakeIsNotAFailure() {
		assertNull(ViewDisplayState.capture(null));
		ImagePlus target = plain(2, 1);
		ViewDisplayState.carryMetadata(null, target, "note");
		assertEquals("note", target.getProperty("Info"));
	}

	// ---- helpers --------------------------------------------------------------------

	private static CompositeImage composite(int channels, int slices, int frames) {
		ImageStack stack = new ImageStack(WIDTH, HEIGHT);
		for (int i = 0; i < channels * slices * frames; i++)
			stack.addSlice(new ShortProcessor(WIDTH, HEIGHT, new short[WIDTH * HEIGHT], null));
		ImagePlus base = new ImagePlus("view", stack);
		base.setDimensions(channels, slices, frames);
		base.setOpenAsHyperStack(true);
		return new CompositeImage(base, CompositeImage.COMPOSITE);
	}

	private static ImagePlus plain(int slices, int frames) {
		ImageStack stack = new ImageStack(WIDTH, HEIGHT);
		for (int i = 0; i < slices * frames; i++)
			stack.addSlice(new ShortProcessor(WIDTH, HEIGHT, new short[WIDTH * HEIGHT], null));
		ImagePlus image = new ImagePlus("view", stack);
		image.setDimensions(1, slices, frames);
		return image;
	}

	/** A colour table with a display range of its own, which is what travels with it. */
	private static LUT ranged(Color colour, double min, double max) {
		LUT lut = LUT.createLutFromColor(colour);
		lut.min = min;
		lut.max = max;
		return lut;
	}

	/** The brightest entry of a table, which for a single-colour ramp is the colour itself. */
	private static int colourOf(LUT lut) {
		return new Color(lut.getRed(255), lut.getGreen(255), lut.getBlue(255)).getRGB();
	}
}
