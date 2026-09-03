package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;

/** Regression coverage for Batch projection movies made from SIFT composite results. */
public class PartitionTimelapseTest {

	@Test
	public void appendsCompositeFramesInXycztOrderWithoutConsumingInputs() {
		ImagePlus first = frame(4, 3, 11, 12);
		ImagePlus second = frame(4, 3, 21, 22);

		ImagePlus movie = Partition.appendTimelapse(first, second, "movie");

		assertTrue(movie.isComposite());
		assertEquals(2, movie.getNChannels());
		assertEquals(1, movie.getNSlices());
		assertEquals(2, movie.getNFrames());
		assertEquals(4, movie.getStackSize());
		assertEquals(11, pixel(movie, 1, 1));
		assertEquals(12, pixel(movie, 2, 1));
		assertEquals(21, pixel(movie, 1, 2));
		assertEquals(22, pixel(movie, 2, 2));

		assertEquals(2, first.getStackSize());
		assertEquals(2, second.getStackSize());
		assertNotSame(first.getStack().getProcessor(1), movie.getStack().getProcessor(1));
	}

	@Test
	public void padsDifferentXySizesInsteadOfOpeningConcatenatorErrorDialog() {
		ImagePlus first = frame(3, 2, 101, 102);
		ImagePlus second = frame(5, 4, 201, 202);

		ImagePlus movie = Partition.appendTimelapse(first, second, "padded");

		assertEquals(5, movie.getWidth());
		assertEquals(4, movie.getHeight());
		assertEquals(2, movie.getNFrames());
		assertEquals(101, movie.getStack().getProcessor(movie.getStackIndex(1, 1, 1)).get(0, 0));
		assertEquals(0, movie.getStack().getProcessor(movie.getStackIndex(1, 1, 1)).get(4, 3));
		assertEquals(201, movie.getStack().getProcessor(movie.getStackIndex(1, 1, 2)).get(4, 3));
	}

	@Test
	public void rejectsAChangedChannelCountWithoutCallingGuiCode() {
		ImagePlus composite = frame(3, 2, 1, 2);
		ImageStack stack = new ImageStack(3, 2);
		stack.addSlice(new ShortProcessor(3, 2));
		ImagePlus singleChannel = new ImagePlus("single", stack);

		try {
			Partition.appendTimelapse(composite, singleChannel, "bad");
			fail("Expected incompatible C/Z dimensions to be rejected");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("C/Z dimensions changed"));
		}
	}

	@Test
	public void promotesMixedCpuGpuGrayscaleTypesInsteadOfDroppingAFrame() {
		ImageStack shortStack = new ImageStack(3, 2);
		shortStack.addSlice(filled(3, 2, 1234));
		ImagePlus shortFrame = new ImagePlus("short", shortStack);
		ImageStack floatStack = new ImageStack(3, 2);
		FloatProcessor floating = new FloatProcessor(3, 2);
		floating.setf(0, 0, 12.5f);
		floatStack.addSlice(floating);
		ImagePlus floatFrame = new ImagePlus("float", floatStack);

		ImagePlus movie = Partition.appendTimelapse(shortFrame, floatFrame, "mixed");

		assertEquals(32, movie.getBitDepth());
		assertEquals(2, movie.getNFrames());
		assertEquals(1234.0, movie.getStack().getProcessor(movie.getStackIndex(1, 1, 1)).getf(0, 0), 0.0);
		assertEquals(12.5, movie.getStack().getProcessor(movie.getStackIndex(1, 1, 2)).getf(0, 0), 0.0);
	}

	private static ImagePlus frame(int width, int height, int channel1, int channel2) {
		ImageStack stack = new ImageStack(width, height);
		stack.addSlice("c1", filled(width, height, channel1));
		stack.addSlice("c2", filled(width, height, channel2));
		ImagePlus image = new ImagePlus("frame", stack);
		image.setDimensions(2, 1, 1);
		image.setOpenAsHyperStack(true);
		return new CompositeImage(image, CompositeImage.COMPOSITE);
	}

	private static ShortProcessor filled(int width, int height, int value) {
		ShortProcessor processor = new ShortProcessor(width, height);
		for (int y = 0; y < height; y++)
			for (int x = 0; x < width; x++) processor.set(x, y, value);
		return processor;
	}

	private static int pixel(ImagePlus image, int channel, int frame) {
		return image.getStack().getProcessor(image.getStackIndex(channel, 1, frame)).get(0, 0);
	}
}
