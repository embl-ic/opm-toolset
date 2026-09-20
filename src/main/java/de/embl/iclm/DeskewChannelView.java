package de.embl.iclm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * How a deskew run laid out its channels, in the OPM Data Viewer's own terms.
 *
 * <p>A canonical OME-Zarr stores every camera half unflipped and unaligned, whatever the run
 * that wrote it was set to produce. The TIFF result of that same run is already composed: a
 * "whole image" run writes one full-width image per acquisition channel, "fold by midline" a
 * pair with the right half mirrored, a combined run the slots in the order chosen. The viewer
 * used to open every store with its halves as separate, unflipped channels unless told
 * otherwise, and a live preview forced exactly that. A whole-image acquisition then looked as
 * though its right half had been folded over its left - a result no deskew path in this
 * toolset writes.
 *
 * <p>This is the translation from a run's channel settings to the viewer's two composition
 * controls, {@code Channels / side} and {@code Runtime view}, chosen so the view reproduces the
 * run's result. It is built from the live settings for a preview and from
 * {@link OpmProvenance} for a dataset opened later, so both arrive at the same view.
 *
 * <p>Whole widths need no transform: the deskew shear acts in Y and Z only, so deskewing the full
 * camera width and placing the two deskewed halves side by side give the same pixels.
 */
final class DeskewChannelView {

	final OmeZarrView.Operation operation;
	/** One of the viewer's {@code OmeZarrView.SELECT_*} choices. */
	final String selection;
	/** The output order for {@link OmeZarrView#SELECT_CONFIGURED}; empty for any other selection. */
	final List<String> channelOrder;
	/** The half a flip acts on; a {@link BatchChannelOperation} flip constant. */
	final String flipHalf;
	final boolean interpolate;
	/** The run's own setting, for the status line. */
	final String describedAs;

	private DeskewChannelView(OmeZarrView.Operation operation, String selection,
			List<String> channelOrder, String flipHalf, boolean interpolate, String describedAs) {
		this.operation = operation;
		this.selection = selection;
		this.channelOrder = Collections.unmodifiableList(new ArrayList<String>(channelOrder));
		this.flipHalf = flipHalf;
		this.interpolate = interpolate;
		this.describedAs = describedAs;
	}

	/** The view of what a Live or Batch run with these settings is writing. */
	static DeskewChannelView of(Parameter parameter, ChannelOperationSettings channels) {
		if (parameter == null) return null;
		List<String> order = new ArrayList<String>();
		if (channels != null) order.addAll(selectedSources(channels.channelOrder));
		return of(parameter.channelStr, channels != null && channels.combineAcquisitionChannels,
				order, channels == null ? BatchChannelOperation.FLIP_RIGHT : channels.flipHalf,
				channels == null || channels.interpolate);
	}

	/**			The view the run that wrote this dataset asked for
	 *
	 * @return					: null for a dataset written before the layout was recorded, or
	 * 							  for converted raw data, which no deskew run composed
	 */
	static DeskewChannelView of(OpmProvenance provenance) {
		if (provenance == null || OpmProvenance.CONTENT_RAW.equals(provenance.contentKind)) return null;
		if (provenance.deskewChannelOption == null && !provenance.deskewCombineChannels) return null;
		return of(provenance.deskewChannelOption, provenance.deskewCombineChannels,
				provenance.deskewChannelOrder, provenance.alignFlipHalf, provenance.alignInterpolate);
	}

	/**			The translation itself
	 *
	 * @param channelOption		: one of {@link Parameter#CHANNEL_OPTIONS}
	 * @param combine			: whether matching {@code _Channel####} files were combined
	 * @param order				: the selected output sources in order, used when combining
	 * @param flipHalf			: the half a combined run flips
	 * @param interpolate		: bilinear sampling for a transformed half
	 * <p>
	 * @return					: null when the option is not one this toolset writes
	 */
	static DeskewChannelView of(String channelOption, boolean combine, List<String> order,
			String flipHalf, boolean interpolate) {
		List<String> selected = selectedSources(order == null ? null : order.toArray(new String[0]));
		if (combine && !selected.isEmpty()) {
			/* A combined run takes its channels from the slots, in slot order. Whole widths and
			 * halves cannot share a result (ChannelOperationSettings.selectionProblem), so one
			 * whole source means they all are. */
			boolean whole = false;
			for (String source : selected) whole |= ChannelOperationSettings.isWholeSource(source);
			if (whole)
				return new DeskewChannelView(OmeZarrView.Operation.SIDE_BY_SIDE,
						OmeZarrView.SELECT_CONFIGURED, selected, BatchChannelOperation.FLIP_RIGHT,
						interpolate, "combined whole widths " + selected);
			/* MultiChannelDeskew flips the chosen half of every selected source and aligns it
			 * when there is a matrix; the viewer's flip + align views do the same, and a store
			 * with no matrix leaves them a flip. */
			boolean left = BatchChannelOperation.FLIP_LEFT.equals(flipHalf);
			return new DeskewChannelView(left ? OmeZarrView.Operation.FLIP_ALIGN_LEFT
					: OmeZarrView.Operation.FLIP_ALIGN_RIGHT, OmeZarrView.SELECT_CONFIGURED, selected,
					left ? BatchChannelOperation.FLIP_LEFT : BatchChannelOperation.FLIP_RIGHT,
					interpolate, "combined " + selected);
		}
		if (channelOption == null) return null;
		List<String> none = Collections.<String>emptyList();
		/* Deskew.process splits a single file itself and always mirrors the right half, whatever
		 * flip the combined-channel settings name - which is why the side is fixed here. */
		String right = BatchChannelOperation.FLIP_RIGHT;
		if (channelOption.equals("whole image"))
			return new DeskewChannelView(OmeZarrView.Operation.SIDE_BY_SIDE,
					OmeZarrView.SELECT_ALL, none, right, interpolate, channelOption);
		if (channelOption.equals("fold by midline"))
			return new DeskewChannelView(OmeZarrView.Operation.FLIP_ONLY,
					OmeZarrView.SELECT_ALL, none, right, interpolate, channelOption);
		// "align with SIFT", and the older "align with SIFT matrix" some settings still carry
		if (channelOption.startsWith("align with SIFT"))
			return new DeskewChannelView(OmeZarrView.Operation.FLIP_ALIGN_RIGHT,
					OmeZarrView.SELECT_ALL, none, right, interpolate, channelOption);
		if (channelOption.equals("only left"))
			return new DeskewChannelView(OmeZarrView.Operation.STORED_CHANNELS,
					OmeZarrView.SELECT_LEFT, none, right, interpolate, channelOption);
		if (channelOption.equals("only right"))
			return new DeskewChannelView(OmeZarrView.Operation.STORED_CHANNELS,
					OmeZarrView.SELECT_RIGHT, none, right, interpolate, channelOption);
		if (channelOption.equals("left & right separately"))
			return new DeskewChannelView(OmeZarrView.Operation.STORED_CHANNELS,
					OmeZarrView.SELECT_ALL, none, right, interpolate, channelOption);
		return null;
	}

	/** The sources a slot array actually asks for, skips removed, order kept. */
	static List<String> selectedSources(String[] slots) {
		List<String> selected = new ArrayList<String>();
		if (slots == null) return selected;
		for (String source : slots)
			if (source != null && !BatchChannelOperation.SKIP_CHANNEL.equals(source)) selected.add(source);
		return selected;
	}

	@Override
	public String toString() {
		return describedAs + " -> " + operation + ", " + selection
				+ (channelOrder.isEmpty() ? "" : " " + channelOrder);
	}
}
