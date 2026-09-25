package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * The names of the first two files decide the layout, and the layout decides the slots.
 *
 * <p>Every case here is an acquisition this microscope actually produces: one file per time
 * point, several files per time point, a single time point that is never followed by another,
 * and the one arrangement that says neither.
 */
public class LiveChannelLayoutTest {

	private static final long T0 = 1000000L;

	private static File file(String time, String channel) {
		return new File("E:/OPM/run", "acq_Time" + time + "_Channel" + channel + ".tif");
	}

	@Test
	public void aRepeatedChannelAtALaterTimeIsOneFilePerTimePoint() {
		LiveChannelLayout layout = new LiveChannelLayout();
		assertNull("the first file decides nothing on its own",
				layout.observe(file("000001", "0001"), T0));
		LiveChannelLayout.Decision decided = layout.observe(file("000002", "0001"), T0 + 500);
		assertEquals(LiveChannelLayout.Kind.SINGLE_FILE, decided.kind);
		assertEquals(1, decided.filesPerTimePoint());
		assertFalse(decided.combines());
	}

	@Test
	public void aSecondChannelOfOneTimePointWaitsForTheTimeToMoveOn() {
		LiveChannelLayout layout = new LiveChannelLayout();
		assertNull(layout.observe(file("000001", "0001"), T0));
		assertNull("two channels of one time point could still be a third",
				layout.observe(file("000001", "0002"), T0 + 200));
		LiveChannelLayout.Decision decided = layout.observe(file("000002", "0001"), T0 + 900);
		assertEquals(LiveChannelLayout.Kind.MULTI_FILE, decided.kind);
		assertEquals(2, decided.filesPerTimePoint());
		assertArrayEquals(new int[] { 1, 2 }, decided.acquisitionChannels);
		assertTrue(decided.combines());
	}

	@Test
	public void threeFilesPerTimePointAreCountedAsThree() {
		LiveChannelLayout layout = new LiveChannelLayout();
		layout.observe(file("000001", "0001"), T0);
		layout.observe(file("000001", "0002"), T0 + 100);
		assertNull(layout.observe(file("000001", "0003"), T0 + 200));
		LiveChannelLayout.Decision decided = layout.observe(file("000002", "0001"), T0 + 800);
		assertArrayEquals(new int[] { 1, 2, 3 }, decided.acquisitionChannels);
	}

	@Test
	public void aNameWithNoChannelTokenDecidesOnTheFirstFile() {
		LiveChannelLayout layout = new LiveChannelLayout();
		LiveChannelLayout.Decision decided =
				layout.observe(new File("E:/OPM/run", "acq_Time000001.tif"), T0);
		assertEquals(LiveChannelLayout.Kind.SINGLE_FILE, decided.kind);
	}

	@Test
	public void bothTokensChangingAtOnceIsLeftToTheConfiguredSetup() {
		LiveChannelLayout layout = new LiveChannelLayout();
		layout.observe(file("000001", "0001"), T0);
		LiveChannelLayout.Decision decided = layout.observe(file("000002", "0002"), T0 + 500);
		assertEquals(LiveChannelLayout.Kind.AMBIGUOUS, decided.kind);
	}

	@Test
	public void theSameFileAnnouncedTwiceDecidesNothing() {
		LiveChannelLayout layout = new LiveChannelLayout();
		layout.observe(file("000001", "0001"), T0);
		assertNull(layout.observe(file("000001", "0001"), T0 + 100));
		assertNull(layout.decision());
	}

	@Test
	public void anAcquisitionOfOneFileIsDecidedWhenItGoesQuiet() {
		LiveChannelLayout layout = new LiveChannelLayout();
		layout.observe(file("000001", "0001"), T0);
		assertNull("not yet", layout.decideIfQuiet(T0 + LiveChannelLayout.QUIET_MS - 1));
		LiveChannelLayout.Decision decided = layout.decideIfQuiet(T0 + LiveChannelLayout.QUIET_MS);
		assertEquals(LiveChannelLayout.Kind.SINGLE_FILE, decided.kind);
	}

	@Test
	public void oneTimePointOfTwoFilesIsCombinedWhenItGoesQuiet() {
		LiveChannelLayout layout = new LiveChannelLayout();
		layout.observe(file("000001", "0001"), T0);
		layout.observe(file("000001", "0002"), T0 + 100);
		LiveChannelLayout.Decision decided =
				layout.decideIfQuiet(T0 + 100 + LiveChannelLayout.QUIET_MS);
		assertEquals(LiveChannelLayout.Kind.MULTI_FILE, decided.kind);
		assertArrayEquals(new int[] { 1, 2 }, decided.acquisitionChannels);
	}

	@Test
	public void theQuietPeriodIsMeasuredFromTheLastFileNotTheFirst() {
		LiveChannelLayout layout = new LiveChannelLayout();
		layout.observe(file("000001", "0001"), T0);
		layout.observe(file("000001", "0002"), T0 + LiveChannelLayout.QUIET_MS);
		assertNull(layout.decideIfQuiet(T0 + LiveChannelLayout.QUIET_MS + 1));
	}


	// ---- assignment -----------------------------------------------------------------

	@Test
	public void eachChannelOptionSaysWhatOneAcquisitionChannelContributes() {
		int[] two = { 1, 2 };
		assertEquals(Arrays.asList("_Channel0001-whole", "_Channel0002-whole"),
				LiveChannelLayout.outputSources("whole image", two));
		assertEquals(Arrays.asList("_Channel0001-left", "_Channel0001-right",
						"_Channel0002-left", "_Channel0002-right"),
				LiveChannelLayout.outputSources("fold by midline", two));
		assertEquals("the SIFT option is the folded one with a matrix",
				LiveChannelLayout.outputSources("fold by midline", two),
				LiveChannelLayout.outputSources("align with SIFT", two));
		assertEquals(Arrays.asList("_Channel0001-left", "_Channel0001-right",
						"_Channel0002-left", "_Channel0002-right"),
				LiveChannelLayout.outputSources("left & right separately", two));
		assertEquals(Arrays.asList("_Channel0001-left", "_Channel0002-left"),
				LiveChannelLayout.outputSources("only left", two));
		assertEquals(Arrays.asList("_Channel0001-right", "_Channel0002-right"),
				LiveChannelLayout.outputSources("only right", two));
	}

	@Test
	public void fourAcquisitionChannelsFoldedFillEveryOutputSlotAndNoMore() {
		List<String> sources =
				LiveChannelLayout.outputSources("fold by midline", new int[] { 1, 2, 3, 4 });
		assertEquals(BatchChannelOperation.MAX_OUTPUT_CHANNELS, sources.size());
		assertEquals("_Channel0004-right", sources.get(sources.size() - 1));
	}

	@Test
	public void aCombinedLayoutFillsTheSlotsTheFlipAndTheCombineTick() {
		ChannelOperationSettings channels = new ChannelOperationSettings();
		channels.combineAcquisitionChannels = false;
		channels.flipHalf = BatchChannelOperation.FLIP_LEFT;
		LiveChannelLayout.Decision decided = new LiveChannelLayout.Decision(
				LiveChannelLayout.Kind.MULTI_FILE, new int[] { 1, 2 }, "because");
		LiveChannelLayout.apply(channels, "fold by midline", decided);
		assertTrue(channels.combineAcquisitionChannels);
		assertEquals(BatchChannelOperation.FLIP_RIGHT, channels.flipHalf);
		assertEquals("_Channel0001-left", channels.channelOrder[0]);
		assertEquals("_Channel0001-right", channels.channelOrder[1]);
		assertEquals("_Channel0002-left", channels.channelOrder[2]);
		assertEquals("_Channel0002-right", channels.channelOrder[3]);
		assertEquals(BatchChannelOperation.SKIP_CHANNEL, channels.channelOrder[4]);
		/* What a live time point then waits for, which is also the set the canonical OME-Zarr
		 * writer expects: both announced files, and nothing beyond them. */
		assertArrayEquals(new int[] { 1, 2 }, channels.requiredAcquisitionChannels(1));
	}

	@Test
	public void aSingleFileLayoutClearsCombiningAndStillDescribesItsOwnChannel() {
		ChannelOperationSettings channels = new ChannelOperationSettings();
		channels.combineAcquisitionChannels = true;
		LiveChannelLayout.Decision decided = new LiveChannelLayout.Decision(
				LiveChannelLayout.Kind.SINGLE_FILE, new int[] { 1 }, "because");
		LiveChannelLayout.apply(channels, "whole image", decided);
		assertFalse(channels.combineAcquisitionChannels);
		assertEquals("_Channel0001-whole", channels.channelOrder[0]);
		assertEquals(BatchChannelOperation.SKIP_CHANNEL, channels.channelOrder[1]);
		assertArrayEquals(new int[] { 1 }, channels.requiredAcquisitionChannels(1));
	}

	@Test
	public void anAmbiguousLayoutChangesNothing() {
		ChannelOperationSettings channels = new ChannelOperationSettings();
		channels.combineAcquisitionChannels = true;
		String[] before = channels.channelOrder.clone();
		LiveChannelLayout.apply(channels, "fold by midline", new LiveChannelLayout.Decision(
				LiveChannelLayout.Kind.AMBIGUOUS, new int[] { 1 }, "because"));
		assertTrue(channels.combineAcquisitionChannels);
		assertArrayEquals(before, channels.channelOrder);
	}
}
