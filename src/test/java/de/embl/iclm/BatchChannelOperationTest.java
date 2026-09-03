package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Assume;
import org.junit.Test;

public class BatchChannelOperationTest {
	private static final double[][] IDENTITY_2D = {
		{ 1.0, 0.0, 0.0 },
		{ 0.0, 1.0, 0.0 }
	};

	@Test
	public void mirroredInputsBecomeFourChannelMovie() {
		BatchChannelOperation operation = new BatchChannelOperation();
		ImagePlus acquisition1 = mirroredMovie(10);
		ImagePlus acquisition2 = mirroredMovie(100);
		BatchChannelOperation.PreparedChannels first = null;
		BatchChannelOperation.PreparedChannels second = null;
		ImagePlus combined = null;
		try {
			first = operation.prepare(acquisition1, "sample_Channel0001_projection-timeLapse.tif", IDENTITY_2D);
			second = operation.prepare(acquisition2, "sample_Channel0002_projection-timeLapse.tif", IDENTITY_2D);
			List<BatchChannelOperation.PreparedChannels> prepared =
					new ArrayList<BatchChannelOperation.PreparedChannels>();
			prepared.add(first);
			prepared.add(second);
			combined = operation.combine(prepared, "combined");

			assertEquals(3, combined.getWidth());
			assertEquals(2, combined.getHeight());
			assertEquals(4, combined.getNChannels());
			assertEquals(1, combined.getNSlices());
			assertEquals(2, combined.getNFrames());
			for (int frame = 1; frame <= 2; frame++) {
				assertSamePixels(combined, 1, 2, frame);
				assertSamePixels(combined, 3, 4, frame);
			}
		} finally {
			BatchProcessingUtils.close(combined);
			if (first != null) first.close();
			if (second != null) second.close();
			BatchProcessingUtils.close(acquisition1);
			BatchProcessingUtils.close(acquisition2);
		}
	}

	@Test
	public void copiedTimelapsePairProducesFour1600PixelChannels() {
		String dataRoot = System.getProperty("opm.test.data");
		Assume.assumeTrue(dataRoot != null && !dataRoot.trim().isEmpty());
		File folder = new File(dataRoot,
				"3_timelapse_0" + File.separator + "result" + File.separator + "maxZ");
		File channel1 = new File(folder,
				"3_timelapse_Position0001_Time000001_Channel0001_Frames_1_451-deskewed-maxZprojection.tif");
		File channel2 = new File(folder,
				"3_timelapse_Position0001_Time000001_Channel0002_Frames_1_451-deskewed-maxZprojection.tif");
		File matrixFile = new File(dataRoot,
				"4_beads_for_overlay_0" + File.separator + "result" + File.separator +
				"4_beads_for_overlay_Position0001_Time000001_Channel0001_Frames_1_451_align.csv");
		Assume.assumeTrue(channel1.isFile() && channel2.isFile() && matrixFile.isFile());

		ImagePlus input1 = IJ.openImage(channel1.getAbsolutePath());
		ImagePlus input2 = IJ.openImage(channel2.getAbsolutePath());
		BatchChannelOperation operation = new BatchChannelOperation();
		BatchChannelOperation.PreparedChannels first = null;
		BatchChannelOperation.PreparedChannels second = null;
		ImagePlus combined = null;
		try {
			first = operation.prepare(input1, channel1.getName(), IO.loadMatrixFromFile(matrixFile.getAbsolutePath()));
			second = operation.prepare(input2, channel2.getName(), IO.loadMatrixFromFile(matrixFile.getAbsolutePath()));
			List<BatchChannelOperation.PreparedChannels> prepared =
					new ArrayList<BatchChannelOperation.PreparedChannels>();
			prepared.add(first);
			prepared.add(second);
			combined = operation.combineSelected(prepared, Arrays.asList(channel1, channel2),
					"four-channel-check");
			assertEquals(1600, combined.getWidth());
			assertEquals(1448, combined.getHeight());
			assertEquals(4, combined.getNChannels());
			assertEquals(1, combined.getNSlices());
			assertEquals(1, combined.getNFrames());
		} finally {
			BatchProcessingUtils.close(combined);
			if (first != null) first.close();
			if (second != null) second.close();
			BatchProcessingUtils.close(input1);
			BatchProcessingUtils.close(input2);
		}
	}

	@Test
	public void selectedSourcesCanBeReorderedAndSkipped() {
		BatchChannelOperation operation = new BatchChannelOperation();
		operation.setChannelOrder(
				"_Channel0002-right", "_Channel0001-left",
				BatchChannelOperation.SKIP_CHANNEL, BatchChannelOperation.SKIP_CHANNEL,
				BatchChannelOperation.SKIP_CHANNEL, BatchChannelOperation.SKIP_CHANNEL);
		ImagePlus acquisition1 = mirroredMovie(10);
		ImagePlus acquisition2 = mirroredMovie(100);
		BatchChannelOperation.PreparedChannels first = null;
		BatchChannelOperation.PreparedChannels second = null;
		ImagePlus combined = null;
		try {
			first = operation.prepare(acquisition1, "sample_Channel0001_projection-timeLapse.tif", IDENTITY_2D);
			second = operation.prepare(acquisition2, "sample_Channel0002_projection-timeLapse.tif", IDENTITY_2D);
			List<BatchChannelOperation.PreparedChannels> prepared =
					Arrays.asList(first, second);
			List<File> files = Arrays.asList(
					new File("sample_Channel0001_projection-timeLapse.tif"),
					new File("sample_Channel0002_projection-timeLapse.tif"));
			combined = operation.combineSelected(prepared, files, "reordered");

			assertEquals(2, combined.getNChannels());
			assertEquals(1, combined.getNSlices());
			assertEquals(2, combined.getNFrames());
			assertEquals(100, firstPixel(combined, 1, 1));
			assertEquals(10, firstPixel(combined, 2, 1));
			assertEquals(120, firstPixel(combined, 1, 2));
			assertEquals(30, firstPixel(combined, 2, 2));
		} finally {
			BatchProcessingUtils.close(combined);
			if (first != null) first.close();
			if (second != null) second.close();
			BatchProcessingUtils.close(acquisition1);
			BatchProcessingUtils.close(acquisition2);
		}
	}

	@Test
	public void flipLeftKeepsTheRawRightHalfAsReference() {
		BatchChannelOperation operation = new BatchChannelOperation();
		operation.setFlipHalf(BatchChannelOperation.FLIP_LEFT);
		ImagePlus input = mirroredMovie(10);
		BatchChannelOperation.PreparedChannels prepared = null;
		try {
			prepared = operation.prepare(input, "sample_Channel0001_projection-timeLapse.tif", IDENTITY_2D);
			ImagePlus alignedLeft = prepared.images.get(0);
			ImagePlus unchangedRight = prepared.images.get(1);
			assertSamePixels(alignedLeft, 1, 1, unchangedRight, 1, 1);
			assertEquals(12, alignedLeft.getProcessor().get(0, 0));
			assertEquals(12, unchangedRight.getProcessor().get(0, 0));
			assertEquals(10, alignedLeft.getProcessor().get(2, 0));
			assertEquals(10, unchangedRight.getProcessor().get(2, 0));
		} finally {
			if (prepared != null) prepared.close();
			BatchProcessingUtils.close(input);
		}
	}

	@Test
	public void mirroredMatrixMapsFlippedLeftCoordinatesOntoRawRightCoordinates() {
		double angle = 0.08;
		double[][] rightFlippedToLeft = {
			{ Math.cos(angle), -Math.sin(angle), 11.25 },
			{ Math.sin(angle), Math.cos(angle), -4.75 }
		};
		int width = 1600;
		double[][] leftFlippedToRight =
				Transform.mirrorAlignmentMatrix2D(rightFlippedToLeft, width);
		double[] rightFlipped = { 231.5, 407.25 };
		double[] left = transformPoint(rightFlippedToLeft, rightFlipped);
		double[] leftFlipped = { width - 1.0 - left[0], left[1] };
		double[] expectedRawRight = { width - 1.0 - rightFlipped[0], rightFlipped[1] };
		double[] actualRawRight = transformPoint(leftFlippedToRight, leftFlipped);

		assertEquals(expectedRawRight[0], actualRawRight[0], 1e-9);
		assertEquals(expectedRawRight[1], actualRawRight[1], 1e-9);
	}

	@Test
	public void groupedDeskewUsesTheActualTiffHeightForItsZOrigin() {
		ImagePlus raw = mirroredMovie(10);
		Parameter parameter = new Parameter("batch");
		parameter.tryGPU = false;
		parameter.deskewMatrix = Transform.deskew(0.265, 0.116, 25.0, 800.0);
		ChannelOperationSettings settings = new ChannelOperationSettings();
		ImagePlus[] result = null;
		try {
			result = MultiChannelDeskew.deskewHalves(raw, parameter, settings, null);
			double expected = Math.abs(raw.getHeight() * -parameter.deskewMatrix[2][1]);
			assertEquals(expected, parameter.deskewMatrix[2][3], 1e-9);
		} finally {
			if (result != null)
				for (ImagePlus image : result) BatchProcessingUtils.close(image);
			BatchProcessingUtils.close(raw);
		}
	}

	@Test
	public void canonicalDeskewFeedsSharedTiffPlanesAndRuntimeAlignedChannels() {
		OpmTimepointProcessor.Result canonical = new OpmTimepointProcessor.Result();
		canonical.channels.add(channel("left", 1, 2, 3));
		canonical.channelLabels.add("_Channel0001-left");
		canonical.channels.add(channel("right", 10, 20, 30));
		canonical.channelLabels.add("_Channel0001-right");
		Parameter parameter = new Parameter("batch-output-test");
		parameter.tryGPU = false;
		parameter.alignMatrix = IDENTITY_2D;
		ChannelOperationSettings settings = new ChannelOperationSettings();
		settings.flipHalf = BatchChannelOperation.FLIP_RIGHT;
		settings.channelOrder[0] = "_Channel0001-left";
		settings.channelOrder[1] = "_Channel0001-right";
		for (int i = 2; i < settings.channelOrder.length; i++)
			settings.channelOrder[i] = BatchChannelOperation.SKIP_CHANNEL;

		MultiChannelDeskew.PreparedComposite prepared = null;
		try {
			prepared = MultiChannelDeskew.fromCanonical(canonical, parameter, settings, "shared");
			assertEquals(2, prepared.image.getNChannels());
			assertSame("the untouched TIFF channel must share its RAM pixels with Zarr",
					canonical.channels.get(0).getStack().getProcessor(1).getPixels(),
					prepared.image.getStack().getProcessor(1).getPixels());
			assertEquals(30, prepared.image.getStack().getProcessor(2).get(0, 0));
			assertEquals(20, prepared.image.getStack().getProcessor(2).get(1, 0));
			assertEquals(10, prepared.image.getStack().getProcessor(2).get(2, 0));
		} finally {
			if (prepared != null) prepared.close();
			canonical.close();
		}
	}

	private ImagePlus channel(String title, int... values) {
		short[] pixels = new short[values.length];
		for (int i = 0; i < values.length; i++) pixels[i] = (short) values[i];
		return new ImagePlus(title, new ShortProcessor(values.length, 1, pixels, null));
	}

	private ImagePlus mirroredMovie(int offset) {
		ImageStack stack = new ImageStack(6, 2);
		for (int frame = 0; frame < 2; frame++) {
			short[] pixels = new short[12];
			for (int y = 0; y < 2; y++) {
				for (int x = 0; x < 3; x++) {
					short value = (short) (offset + frame * 20 + y * 3 + x);
					pixels[y * 6 + x] = value;
					pixels[y * 6 + 5 - x] = value;
				}
			}
			stack.addSlice(new ShortProcessor(6, 2, pixels, null));
		}
		return new ImagePlus("mirrored", stack);
	}

	private void assertSamePixels(ImagePlus image, int channelA, int channelB, int frame) {
		int indexA = image.getStackIndex(channelA, 1, frame);
		int indexB = image.getStackIndex(channelB, 1, frame);
		short[] a = (short[]) image.getStack().getProcessor(indexA).getPixels();
		short[] b = (short[]) image.getStack().getProcessor(indexB).getPixels();
		assertEquals(a.length, b.length);
		for (int i = 0; i < a.length; i++) assertEquals(a[i], b[i]);
	}

	private void assertSamePixels(ImagePlus imageA, int channelA, int frameA,
			ImagePlus imageB, int channelB, int frameB) {
		int indexA = imageA.getStackIndex(channelA, 1, frameA);
		int indexB = imageB.getStackIndex(channelB, 1, frameB);
		short[] a = (short[]) imageA.getStack().getProcessor(indexA).getPixels();
		short[] b = (short[]) imageB.getStack().getProcessor(indexB).getPixels();
		assertEquals(a.length, b.length);
		for (int i = 0; i < a.length; i++) assertEquals(a[i], b[i]);
	}

	private double[] transformPoint(double[][] matrix, double[] point) {
		return new double[] {
			matrix[0][0] * point[0] + matrix[0][1] * point[1] + matrix[0][2],
			matrix[1][0] * point[0] + matrix[1][1] * point[1] + matrix[1][2]
		};
	}

	private int firstPixel(ImagePlus image, int channel, int frame) {
		int index = image.getStackIndex(channel, 1, frame);
		return image.getStack().getProcessor(index).get(0, 0);
	}
}
