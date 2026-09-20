package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * What a flip half is called on screen, against what is written for it.
 *
 * <p>The dialogs show "flip right half onto left", which says what actually happens. The value
 * behind it is still {@code "flip right half"}, and it has to stay that way: it is written into
 * every OME-Zarr as {@code opm.alignFlipHalf} and compared by {@link OmeZarrView} when a
 * runtime flip is applied. Renaming the constant would make every dataset already on disk stop
 * flipping - and silently, because a comparison that stops matching raises nothing.
 */
public class FlipLabelTest {

	@Test
	public void theStoredValuesAreTheOnesAlreadyWrittenIntoDatasets() {
		/* These two strings are a file format. If this test fails, an existing OME-Zarr has
		 * just stopped being read correctly - fix the code, not the test. */
		assertEquals("flip right half", BatchChannelOperation.FLIP_RIGHT);
		assertEquals("flip left half", BatchChannelOperation.FLIP_LEFT);
	}

	@Test
	public void labelsAreForReadingAndAreNotTheStoredValues() {
		assertEquals("flip right half onto left", ChannelOperationSettings.FLIP_LABELS[0]);
		assertEquals("flip left half onto right", ChannelOperationSettings.FLIP_LABELS[1]);
		assertNotEquals(BatchChannelOperation.FLIP_RIGHT, ChannelOperationSettings.FLIP_LABELS[0]);
		assertNotEquals(BatchChannelOperation.FLIP_LEFT, ChannelOperationSettings.FLIP_LABELS[1]);
	}

	@Test
	public void everyValueSurvivesTheRoundTripThroughItsLabel() {
		for (String value : new String[] {
				BatchChannelOperation.FLIP_RIGHT, BatchChannelOperation.FLIP_LEFT }) {
			String label = ChannelOperationSettings.flipLabel(value);
			assertEquals(value, ChannelOperationSettings.flipValue(label));
		}
		for (String label : ChannelOperationSettings.FLIP_LABELS) {
			String value = ChannelOperationSettings.flipValue(label);
			assertEquals(label, ChannelOperationSettings.flipLabel(value));
		}
	}

	@Test
	public void anythingUnrecognisedFallsBackToTheDefaultSideRatherThanNull() {
		// a stored value from a future version, or a label that has since been reworded
		assertEquals(BatchChannelOperation.FLIP_RIGHT, ChannelOperationSettings.flipValue("nonsense"));
		assertEquals(ChannelOperationSettings.FLIP_LABELS[0], ChannelOperationSettings.flipLabel(null));
	}

	@Test
	public void aSlotIsLabelledAsAChannel() {
		assertEquals("1st channel", ChannelOperationSettings.slotLabel(1));
		assertEquals("2nd channel", ChannelOperationSettings.slotLabel(2));
	}

	@Test
	public void aFreshOrderIsTheTwoHalvesOfTheFirstAcquisitionChannel() {
		/* Two, not four: a one-file rig was being asked about two channels it does not have.
		 * The order is acquisition-first, left before right, which is also what [+] offers. */
		String[] order = ChannelOperationSettings.defaultChannelOrder();
		assertEquals("_Channel0001-left", order[0]);
		assertEquals("_Channel0001-right", order[1]);
		assertEquals(BatchChannelOperation.SKIP_CHANNEL, order[2]);
		assertEquals(BatchChannelOperation.SKIP_CHANNEL, order[3]);
	}

	@Test
	public void addingSlotsWalksTheAcquisitionChannelsInOrder() {
		assertEquals("_Channel0001-left", ChannelOperationSettings.defaultSourceFor(0));
		assertEquals("_Channel0001-right", ChannelOperationSettings.defaultSourceFor(1));
		assertEquals("_Channel0002-left", ChannelOperationSettings.defaultSourceFor(2));
		assertEquals("_Channel0002-right", ChannelOperationSettings.defaultSourceFor(3));
		// past the end, and below it, "skip" rather than an exception or a wrapped value
		assertEquals(BatchChannelOperation.SKIP_CHANNEL,
				ChannelOperationSettings.defaultSourceFor(-1));
		assertEquals(BatchChannelOperation.SKIP_CHANNEL,
				ChannelOperationSettings.defaultSourceFor(999));
	}

	@Test
	public void twoSlotsAreShownUntilMoreAreActuallyUsed() {
		ChannelOperationSettings settings = new ChannelOperationSettings();
		for (int i = 0; i < settings.channelOrder.length; i++)
			settings.channelOrder[i] = BatchChannelOperation.SKIP_CHANNEL;
		assertEquals("never fewer than two", 2, settings.slotsInUse());

		settings.channelOrder[3] = ChannelOperationSettings.sourceKey(2, true);
		assertEquals("as many as are in use", 4, settings.slotsInUse());
	}
}
