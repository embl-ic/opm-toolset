package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

/**
 * Tests for the multi-channel settings now shared by Channel Operation, Deskew Batch and
 * Live Processing.
 *
 * <p>The microscope writes one file per acquisition channel and each file carries a
 * mirrored pair of camera halves, so a two-file timepoint holds four optical channels.
 * Getting the grouping or the output ordering wrong produces a plausible-looking result
 * with the channels in the wrong places, which is exactly the kind of mistake that is not
 * visible in a file listing.
 */
public class ChannelOperationSettingsTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	/** Files of one timepoint travel together; different timepoints do not. */
	@Test
	public void coversFourAcquisitionFilesAsEightCameraHalves() {
		/* The canonical Zarr writer stores every half of every file it finds, so the dialogs
		 * are what caps an acquisition. Four files at two halves each is that cap. */
		assertEquals(4, BatchChannelOperation.MAX_ACQUISITION_CHANNELS);
		assertEquals(8, BatchChannelOperation.MAX_OUTPUT_CHANNELS);
		assertEquals(9, BatchChannelOperation.CHANNEL_SOURCE_OPTIONS.length);
		assertEquals(BatchChannelOperation.SKIP_CHANNEL,
				BatchChannelOperation.CHANNEL_SOURCE_OPTIONS[8]);
		for (int acquisition = 1; acquisition <= 4; acquisition++) {
			assertEquals(ChannelOperationSettings.sourceKey(acquisition, true),
					BatchChannelOperation.CHANNEL_SOURCE_OPTIONS[2 * acquisition - 2]);
			assertEquals(ChannelOperationSettings.sourceKey(acquisition, false),
					BatchChannelOperation.CHANNEL_SOURCE_OPTIONS[2 * acquisition - 1]);
		}

		ChannelOperationSettings settings = new ChannelOperationSettings();
		assertEquals(8, settings.channelOrder.length);
		/* The widened slots default to skips, so an existing two-file setup is unchanged. */
		assertEquals(4, settings.selectedCount());
		for (int i = 4; i < settings.channelOrder.length; i++)
			assertEquals(BatchChannelOperation.SKIP_CHANNEL, settings.channelOrder[i]);

		settings.channelOrder[4] = ChannelOperationSettings.sourceKey(3, true);
		settings.channelOrder[7] = ChannelOperationSettings.sourceKey(4, false);
		assertEquals(6, settings.selectedCount());
	}

	@Test
	public void groupsAcquisitionChannelsOfTheSameTimepoint() throws IOException {
		File t1c1 = touch("sample_Time000001_Channel0001_Frames_1_451.tiff");
		File t1c2 = touch("sample_Time000001_Channel0002_Frames_1_451.tiff");
		File t2c1 = touch("sample_Time000002_Channel0001_Frames_1_451.tiff");
		File t2c2 = touch("sample_Time000002_Channel0002_Frames_1_451.tiff");

		ChannelOperationSettings settings = new ChannelOperationSettings();
		settings.combineAcquisitionChannels = true;
		Map<String, List<File>> groups = settings.group(files(t1c1, t1c2, t2c1, t2c2));

		assertEquals("one group per timepoint", 2, groups.size());
		for (List<File> group : groups.values()) {
			assertEquals("both acquisition channels in the group", 2, group.size());
			assertEquals("sorted by acquisition channel",
					1, BatchProcessingUtils.acquisitionChannel(group.get(0)));
			assertEquals(2, BatchProcessingUtils.acquisitionChannel(group.get(1)));
		}
	}

	/** With combining off, every file stays on its own - the previous behaviour. */
	@Test
	public void leavesFilesAloneWhenCombiningIsOff() throws IOException {
		List<File> files = files(
				touch("sample_Time000001_Channel0001.tiff"),
				touch("sample_Time000001_Channel0002.tiff"));

		ChannelOperationSettings settings = new ChannelOperationSettings();
		settings.combineAcquisitionChannels = false;

		assertEquals("no grouping", 2, settings.group(files).size());
	}

	/** Source keys have to match the labels the dialog offers, or selections resolve to nothing. */
	@Test
	public void sourceKeysMatchTheDialogLabels() {
		assertEquals("_Channel0001-left", ChannelOperationSettings.sourceKey(1, true));
		assertEquals("_Channel0001-right", ChannelOperationSettings.sourceKey(1, false));
		assertEquals("_Channel0002-left", ChannelOperationSettings.sourceKey(2, true));
		assertEquals("_Channel0003-right", ChannelOperationSettings.sourceKey(3, false));

		for (String option : BatchChannelOperation.CHANNEL_SOURCE_OPTIONS) {
			if (BatchChannelOperation.SKIP_CHANNEL.equals(option)) continue;
			assertTrue("dialog offers a key nothing can produce: " + option,
					ChannelOperationSettings.isSourceOption(option));
		}
		assertTrue(ChannelOperationSettings.isSourceOption(ChannelOperationSettings.sourceKey(1, true)));
		assertFalse(ChannelOperationSettings.isSourceOption("_Channel0009-left"));
	}

	/** The default selection is four channels from two files, matching Channel Operation. */
	@Test
	public void defaultSelectionTakesFourChannelsFromTwoFiles() {
		ChannelOperationSettings settings = new ChannelOperationSettings();
		assertEquals(4, settings.selectedCount());
		assertEquals(ChannelOperationSettings.sourceKey(1, true), settings.channelOrder[0]);
		assertEquals(ChannelOperationSettings.sourceKey(1, false), settings.channelOrder[1]);
		assertEquals(ChannelOperationSettings.sourceKey(2, true), settings.channelOrder[2]);
		assertEquals(ChannelOperationSettings.sourceKey(2, false), settings.channelOrder[3]);
		assertFalse("flip right is the default", settings.isFlipLeft());
	}

	/** Skipping a source removes it from the count and from the output. */
	@Test
	public void skipRemovesAnOutputChannel() {
		ChannelOperationSettings settings = new ChannelOperationSettings();
		settings.channelOrder[1] = BatchChannelOperation.SKIP_CHANNEL;
		settings.channelOrder[3] = BatchChannelOperation.SKIP_CHANNEL;
		assertEquals(2, settings.selectedCount());
	}

	/** Combining must interleave in ImageJ's XYCZT order, or channels come out scrambled. */
	@Test
	public void combineInterleavesChannelsInImageJOrder() {
		ImagePlus first = constant(8, 6, 3, 111);
		ImagePlus second = constant(8, 6, 3, 222);
		ImagePlus third = constant(8, 6, 3, 333);

		ImagePlus combined = MultiChannelDeskew.combine(list(first, second, third), "combined");

		assertNotNull(combined);
		assertEquals("three channels", 3, combined.getNChannels());
		assertEquals("slices preserved", 3, combined.getNSlices());
		assertEquals("every plane present", 9, combined.getStackSize());
		for (int z = 1; z <= 3; z++) {
			assertEquals("channel 1 at z=" + z, 111f, planeValue(combined, 1, z), 0f);
			assertEquals("channel 2 at z=" + z, 222f, planeValue(combined, 2, z), 0f);
			assertEquals("channel 3 at z=" + z, 333f, planeValue(combined, 3, z), 0f);
		}
	}

	/** A single selected source is returned as-is rather than wrapped in a one-channel stack. */
	@Test
	public void combineOfOneChannelIsThatChannel() {
		ImagePlus only = constant(6, 4, 2, 7);
		ImagePlus combined = MultiChannelDeskew.combine(list(only), "single");
		assertEquals(1, combined.getNChannels());
		assertEquals(2, combined.getStackSize());
	}

	/** Mismatched channel sizes must be reported, not silently interleaved into nonsense. */
	@Test
	public void combineRejectsMismatchedChannels() {
		try {
			MultiChannelDeskew.combine(list(constant(8, 6, 3, 1), constant(8, 5, 3, 2)), "bad");
			org.junit.Assert.fail("differing XY sizes should be refused");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("XY"));
		}
		try {
			MultiChannelDeskew.combine(list(constant(8, 6, 3, 1), constant(8, 6, 4, 2)), "bad");
			org.junit.Assert.fail("differing Z sizes should be refused");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("Z/T"));
		}
	}

	/** A group with no usable files must produce nothing rather than a broken result. */
	@Test
	public void deskewGroupHandlesEmptyAndUnreadableInput() {
		ChannelOperationSettings settings = new ChannelOperationSettings();
		assertEquals(null, MultiChannelDeskew.deskewGroup(null, null, settings, "x"));
		assertEquals(null, MultiChannelDeskew.deskewGroup(new ArrayList<File>(), null, settings, "x"));
	}

	// ---- helpers -------------------------------------------------------------------

	private File touch(String name) throws IOException {
		File file = folder.newFile(name);
		OutputStream out = new FileOutputStream(file);
		try {
			out.write(new byte[8]);
		} finally {
			out.close();
		}
		return file;
	}

	private static List<File> files(File... items) {
		List<File> out = new ArrayList<File>();
		for (File f : items) out.add(f);
		return out;
	}

	private static List<ImagePlus> list(ImagePlus... items) {
		List<ImagePlus> out = new ArrayList<ImagePlus>();
		for (ImagePlus i : items) out.add(i);
		return out;
	}

	/** A stack whose every voxel carries the same value, so channel identity is unambiguous. */
	private static ImagePlus constant(int w, int h, int d, int value) {
		ImageStack stack = new ImageStack(w, h);
		for (int z = 0; z < d; z++) {
			ShortProcessor slice = new ShortProcessor(w, h);
			for (int i = 0; i < w * h; i++) slice.set(i, value);
			stack.addSlice(slice);
		}
		ImagePlus imp = new ImagePlus("c" + value, stack);
		imp.setDimensions(1, d, 1);
		return imp;
	}

	private static float planeValue(ImagePlus imp, int channel, int slice) {
		ImageProcessor ip = imp.getStack().getProcessor(imp.getStackIndex(channel, slice, 1));
		return ip.getf(0);
	}
}
