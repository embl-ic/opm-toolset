package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * A deskew run's channel settings, translated into the composition the viewer opens a store with.
 *
 * <p>The store always holds unflipped halves. What these pin is that the view built from them is
 * the run's result: a whole-image run is not two halves laid over each other, which is what a
 * live preview showed while it forced stored channels.
 */
public class DeskewChannelViewTest {

	private static final String SKIP = BatchChannelOperation.SKIP_CHANNEL;
	private static final List<String> NONE = Collections.<String>emptyList();

	@Test
	public void aWholeImageRunIsShownAsItsFullWidthNotAsTwoHalves() {
		DeskewChannelView view = DeskewChannelView.of("whole image", false, NONE,
				BatchChannelOperation.FLIP_LEFT, true);
		assertEquals(OmeZarrView.Operation.SIDE_BY_SIDE, view.operation);
		assertEquals(OmeZarrView.SELECT_ALL, view.selection);
		assertTrue(view.channelOrder.isEmpty());
	}

	/** Each single-file option, as Deskew.process writes it. */
	@Test
	public void everySingleFileOptionMapsToTheViewThatReproducesIt() {
		assertView(OmeZarrView.Operation.FLIP_ONLY, OmeZarrView.SELECT_ALL, "fold by midline");
		assertView(OmeZarrView.Operation.FLIP_ALIGN_RIGHT, OmeZarrView.SELECT_ALL, "align with SIFT");
		assertView(OmeZarrView.Operation.FLIP_ALIGN_RIGHT, OmeZarrView.SELECT_ALL, "align with SIFT matrix");
		assertView(OmeZarrView.Operation.STORED_CHANNELS, OmeZarrView.SELECT_LEFT, "only left");
		assertView(OmeZarrView.Operation.STORED_CHANNELS, OmeZarrView.SELECT_RIGHT, "only right");
		assertView(OmeZarrView.Operation.STORED_CHANNELS, OmeZarrView.SELECT_ALL, "left & right separately");
		assertNull("an option this toolset does not write leaves the controls alone",
				DeskewChannelView.of("something else", false, NONE, null, true));
		assertNull(DeskewChannelView.of(null, false, NONE, null, true));
	}

	/**
	 * Deskew.process mirrors the right half of a single file whatever flip the combined-channel
	 * settings name - that control is greyed while files are not combined - so the view must not
	 * take a stale left flip from them.
	 */
	@Test
	public void aSingleFileFoldFlipsTheRightHalfWhateverTheCombinedSettingsSay() {
		DeskewChannelView view = DeskewChannelView.of("fold by midline", false, NONE,
				BatchChannelOperation.FLIP_LEFT, true);
		assertEquals(BatchChannelOperation.FLIP_RIGHT, view.flipHalf);
	}

	@Test
	public void combinedHalvesKeepTheirSlotOrderAndTheChosenFlip() {
		List<String> slots = Arrays.asList("_Channel0002-right", SKIP, "_Channel0001-left");
		DeskewChannelView right = DeskewChannelView.of("whole image", true, slots,
				BatchChannelOperation.FLIP_RIGHT, false);
		assertEquals("the channel option is not what a combined run follows",
				OmeZarrView.Operation.FLIP_ALIGN_RIGHT, right.operation);
		assertEquals(OmeZarrView.SELECT_CONFIGURED, right.selection);
		assertEquals(Arrays.asList("_Channel0002-right", "_Channel0001-left"), right.channelOrder);
		assertEquals(false, right.interpolate);

		DeskewChannelView left = DeskewChannelView.of(null, true, slots,
				BatchChannelOperation.FLIP_LEFT, true);
		assertEquals(OmeZarrView.Operation.FLIP_ALIGN_LEFT, left.operation);
		assertEquals(BatchChannelOperation.FLIP_LEFT, left.flipHalf);
	}

	@Test
	public void combinedWholeWidthsAreShownSideBySideInSlotOrder() {
		DeskewChannelView view = DeskewChannelView.of("fold by midline", true,
				Arrays.asList(ChannelOperationSettings.wholeSourceKey(2),
						ChannelOperationSettings.wholeSourceKey(1)),
				BatchChannelOperation.FLIP_RIGHT, true);
		assertEquals(OmeZarrView.Operation.SIDE_BY_SIDE, view.operation);
		assertEquals(OmeZarrView.SELECT_CONFIGURED, view.selection);
		assertEquals(Arrays.asList("_Channel0002-whole", "_Channel0001-whole"), view.channelOrder);
	}

	/** Combining with every slot skipped writes nothing combined; the channel option decides. */
	@Test
	public void combiningWithNothingSelectedFallsBackToTheChannelOption() {
		DeskewChannelView view = DeskewChannelView.of("whole image", true,
				Arrays.asList(SKIP, SKIP), BatchChannelOperation.FLIP_RIGHT, true);
		assertEquals(OmeZarrView.Operation.SIDE_BY_SIDE, view.operation);
	}

	/** What a run records is what a later session opens with; older stores record nothing. */
	@Test
	public void theLayoutIsRecordedInProvenanceAndReadBack() {
		OmeZarrConverter.Options options = new OmeZarrConverter.Options();
		options.channelOption = "whole image";
		OpmProvenance written = OmeZarrConverter.provenance(
				new File("source"), new File("run.ome.zarr"), options, Transform.identity());
		OpmProvenance read = OpmProvenance.fromJson(written.toJson());
		assertEquals("whole image", read.deskewChannelOption);

		DeskewChannelView view = DeskewChannelView.of(read);
		assertNotNull(view);
		assertEquals(OmeZarrView.Operation.SIDE_BY_SIDE, view.operation);

		OmeZarrConverter.Options combined = new OmeZarrConverter.Options();
		combined.combineChannels = true;
		combined.channelOrder.addAll(Arrays.asList("_Channel0001-right", "_Channel0001-left"));
		OpmProvenance again = OpmProvenance.fromJson(OmeZarrConverter.provenance(
				new File("source"), new File("run.ome.zarr"), combined, Transform.identity()).toJson());
		assertEquals(Arrays.asList("_Channel0001-right", "_Channel0001-left"),
				DeskewChannelView.of(again).channelOrder);
	}

	@Test
	public void aStoreWithNoRecordedLayoutOrRawContentHasNoRunView() {
		assertNull(DeskewChannelView.of((OpmProvenance) null));
		assertNull("written before the layout was recorded", DeskewChannelView.of(new OpmProvenance()));
		OpmProvenance raw = new OpmProvenance();
		raw.contentKind = OpmProvenance.CONTENT_RAW;
		raw.deskewChannelOption = "whole image";
		assertNull("converted raw data was never composed by a deskew run", DeskewChannelView.of(raw));
	}

	private static void assertView(OmeZarrView.Operation operation, String selection, String option) {
		DeskewChannelView view = DeskewChannelView.of(option, false, NONE,
				BatchChannelOperation.FLIP_RIGHT, true);
		assertNotNull(option, view);
		assertEquals(option, operation, view.operation);
		assertEquals(option, selection, view.selection);
	}
}
