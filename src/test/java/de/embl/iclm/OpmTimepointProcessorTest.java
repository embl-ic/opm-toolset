package de.embl.iclm;

import static org.junit.Assert.assertEquals;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Tests deterministic grouping and preservation of separate, unaligned camera halves. */
public class OpmTimepointProcessorTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void groupsNaturallyAndRejectsIncompleteTimepoints() throws Exception {
		File t2c2 = touch("sample_Time000002_Channel0002_Frames.tif", 6000);
		File t1c2 = touch("sample_Time000001_Channel0002_Frames.tif", 1000);
		File t3c1 = touch("sample_Time000003_Channel0001_Frames.tif", 9000);
		File t2c1 = touch("sample_Time000002_Channel0001_Frames.tif", 6000);
		File t1c1 = touch("sample_Time000001_Channel0001_Frames.tif", 1000);

		List<OpmTimepointProcessor.TimePoint> timePoints = OpmTimepointProcessor.completeTimePoints(
				Arrays.asList(t2c2, t1c2, t3c1, t2c1, t1c1), 10);

		assertEquals(2, timePoints.size());
		assertEquals("sample_Time000001_Channel####_Frames", timePoints.get(0).label);
		assertEquals("sample_Time000002_Channel####_Frames", timePoints.get(1).label);
		assertEquals(1, BatchProcessingUtils.acquisitionChannel(timePoints.get(0).files.get(0)));
		assertEquals(2, BatchProcessingUtils.acquisitionChannel(timePoints.get(0).files.get(1)));
		assertEquals(5.0, timePoints.get(1).elapsedSeconds, 0.0);
	}

	@Test
	public void reverseChannelArrivalIsIncompleteUntilEveryChannelExists() throws Exception {
		File channel2 = touch("sample_Time000001_Channel0002.tif", 1000);
		File channel1 = touch("sample_Time000001_Channel0001.tif", 1000);
		int[] expected = { 1, 2 };

		assertEquals(false, OpmTimepointProcessor.hasExactChannels(
				Arrays.asList(channel2), expected));
		assertEquals(true, OpmTimepointProcessor.hasExactChannels(
				Arrays.asList(channel2, channel1), expected));
	}

	@Test
	public void deskewsBothHalvesWithoutFlipOrAlignment() throws Exception {
		File rawFile = new File(folder.getRoot(), "sample_Time000001_Channel0001.tif");
		ImageStack stack = new ImageStack(4, 3);
		for (int z = 0; z < 2; z++) {
			short[] pixels = new short[12];
			for (int y = 0; y < 3; y++) {
				pixels[y * 4] = (short) (10 + z + y);
				pixels[y * 4 + 1] = (short) (20 + z + y);
				pixels[y * 4 + 2] = (short) (100 + z + y);
				pixels[y * 4 + 3] = (short) (200 + z + y);
			}
			stack.addSlice(new ShortProcessor(4, 3, pixels, null));
		}
		ImagePlus raw = new ImagePlus("raw", stack);
		boolean compression = VolumeIO.isCompressOutput();
		VolumeIO.setCompressOutput(false);
		try {
			org.junit.Assert.assertEquals(true, VolumeIO.saveTiff(raw, rawFile));
		} finally {
			VolumeIO.setCompressOutput(compression);
		}
		raw.close();

		OpmTimepointProcessor.TimePoint timePoint = new OpmTimepointProcessor.TimePoint(
				"Time1", Arrays.asList(rawFile), 0);
		OpmTimepointProcessor.Result result = OpmTimepointProcessor.process(
				timePoint, Transform.identity(), false);
		try {
			assertEquals(Arrays.asList("_Channel0001-left", "_Channel0001-right"), result.channelLabels);
			assertEquals(2, result.channels.size());
			assertEquals(10, result.channels.get(0).getStack().getProcessor(1).get(0, 0));
			assertEquals(20, result.channels.get(0).getStack().getProcessor(1).get(1, 0));
			assertEquals(100, result.channels.get(1).getStack().getProcessor(1).get(0, 0));
			assertEquals(200, result.channels.get(1).getStack().getProcessor(1).get(1, 0));
		} finally {
			result.close();
		}
	}

	private File touch(String name, long modified) throws IOException {
		File file = new File(folder.getRoot(), name);
		Files.write(file.toPath(), new byte[] { 1 });
		file.setLastModified(modified);
		return file;
	}
}
