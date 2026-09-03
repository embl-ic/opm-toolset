package de.embl.iclm;

import static org.junit.Assert.assertEquals;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class ProjectionBatchTest {

	@Test
	public void computesAllSixHyperstackProjectionsInOneBoundedBatch() {
		ImagePlus input = twoChannelVolume();
		List<ProjectionBatch.Request> requests = Arrays.asList(
				new ProjectionBatch.Request("X", "max"),
				new ProjectionBatch.Request("X", "avg"),
				new ProjectionBatch.Request("Y", "max"),
				new ProjectionBatch.Request("Y", "avg"),
				new ProjectionBatch.Request("Z", "max"),
				new ProjectionBatch.Request("Z", "avg"));
		List<ImagePlus> result = ProjectionBatch.compute(input, requests, false);
		try {
			assertEquals(6, result.size());
			for (ImagePlus projection : result) assertEquals(2, projection.getNChannels());

			assertDimensions(result.get(0), 2, 2); // Z by Y
			assertPixel(result.get(0), 1, 0, 0, 3);  // C1, Z1, max over X
			assertPixel(result.get(0), 1, 1, 1, 12); // C1, Z2/Y2
			assertPixel(result.get(1), 1, 0, 0, 2);  // mean 1,2,3

			assertDimensions(result.get(2), 3, 2); // X by Z
			assertPixel(result.get(2), 1, 0, 0, 4);  // max over Y
			assertPixel(result.get(3), 1, 0, 0, 3);  // rounded mean of 1 and 4

			assertDimensions(result.get(4), 3, 2); // X by Y
			assertPixel(result.get(4), 1, 0, 0, 7);  // max over Z
			assertPixel(result.get(5), 1, 0, 0, 4);  // mean of 1 and 7

			// The second hyperstack channel must remain independent and correctly ordered.
			assertPixel(result.get(4), 2, 0, 0, 107);
		} finally {
			for (ImagePlus projection : result) BatchProcessingUtils.close(projection);
			BatchProcessingUtils.close(input);
		}
	}

	private static ImagePlus twoChannelVolume() {
		ImageStack stack = new ImageStack(3, 2);
		for (int z = 0; z < 2; z++) for (int channel = 0; channel < 2; channel++) {
			short[] pixels = new short[6];
			int offset = channel * 100 + z * 6;
			for (int pixel = 0; pixel < pixels.length; pixel++)
				pixels[pixel] = (short) (offset + pixel + 1);
			stack.addSlice(new ShortProcessor(3, 2, pixels, null));
		}
		ImagePlus image = new ImagePlus("input", stack);
		image.setDimensions(2, 2, 1);
		image.setOpenAsHyperStack(true);
		return image;
	}

	private static void assertDimensions(ImagePlus image, int width, int height) {
		assertEquals(width, image.getWidth());
		assertEquals(height, image.getHeight());
	}

	private static void assertPixel(
			ImagePlus image, int channel, int x, int y, int expected) {
		int index = image.getStackIndex(channel, 1, 1);
		assertEquals(expected, image.getStack().getProcessor(index).get(x, y));
	}
}
