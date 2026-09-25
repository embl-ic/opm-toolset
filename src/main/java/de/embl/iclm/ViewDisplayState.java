package de.embl.iclm;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import ij.process.LUT;

/**
 * How a view looked at the moment something was made from it, and how to put that on the copy.
 *
 * <p>Materialising a region opens a new window built from the file, which ImageJ contrasts from
 * scratch: a 4-channel overlay the user had spent a minute adjusting came out grey, composite
 * where they had chosen colour, showing plane 1 of time point 1. Everything they had set up to
 * decide <em>which</em> region to cut was gone from the cut itself, which is precisely when it
 * is wanted (user's request, 2026-09-25).
 *
 * <p>What is carried is the display, never the pixels: the composite mode, each channel's colour
 * and display range, which channels are ticked, and the C/Z/T position. The crop is smaller than
 * the view in every axis, so the position is carried through the same offsets the region was cut
 * with and clamped into what is left - a Z the crop does not contain becomes its nearest plane
 * rather than plane 1.
 *
 * <p>LUTs are copied the way ImageJ's own {@code Duplicator} copies them, through
 * {@link ImagePlus#getLuts()} and {@link CompositeImage#setLuts}: a {@code LUT} carries its own
 * {@code min} and {@code max}, so the colour and the range travel together and cannot be applied
 * in the wrong order - which is the trap {@code OmeZarrView.asComposite} documents for the
 * two-argument {@code setChannelLut}.
 */
final class ViewDisplayState {

	/**
	 * Image properties worth carrying onto a crop, named rather than swept up.
	 * <p>
	 * A fixed list because only some of them are still true of the copy: the root and the view
	 * are, the channel labels are once the channel range has been applied to them, and the
	 * origin is <em>not</em> - the crop starts somewhere else and stamps its own. Whatever the
	 * open path has already written is left alone for that reason.
	 */
	private static final String[] CARRIED = {
		"opm.channelLabels", "opm.zarrRoot", "opm.tiffRoot", "opm.view", "opm.contentKind",
		"opm.approximateOverlay"
	};

	/** {@link CompositeImage#getMode()}, or -1 when the source was not a composite. */
	private final int mode;
	/** One per source channel, each carrying its colour table and its display range. */
	private final LUT[] luts;
	/** The composite's channel check boxes, or null. */
	private final boolean[] active;
	/** Where the source was positioned, 1 based. */
	private final int channel;
	private final int slice;
	private final int frame;

	private ViewDisplayState(int mode, LUT[] luts, boolean[] active, int channel, int slice, int frame) {
		this.mode = mode;
		this.luts = luts;
		this.active = active;
		this.channel = channel;
		this.slice = slice;
		this.frame = frame;
	}

	/**			Take down what a view is showing, without disturbing it
	 *
	 * @param source			: the window the region is being cut from
	 * <p>
	 * @return					: the state, or null when there is nothing to take
	 */
	static ViewDisplayState capture (
			ImagePlus source
			) {
		if (source == null) return null;
		LUT[] luts = null;
		try { luts = source.getLuts(); }
		catch (Throwable unavailable) { luts = null; }		// a stack ImageJ cannot describe
		int mode = -1;
		boolean[] active = null;
		if (source instanceof CompositeImage) {
			CompositeImage composite = (CompositeImage) source;
			mode = composite.getMode();
			boolean[] shown = composite.getActiveChannels();
			if (shown != null) active = shown.clone();		// the array is the composite's own
		}
		return new ViewDisplayState(mode, luts, active,
				source.getChannel(), source.getSlice(), source.getFrame());
	}

	/**			Put that state on a crop of the view it was taken from
	 * <p>		The offsets are the region's own, zero based, exactly as they were handed to the
	 * 			reader - so channel {@code i} of the crop is channel {@code firstChannel + i} of
	 * 			the view, and the position follows it. Call it once the image is showing: a
	 * 			composite builds its channel processors when it is first drawn, and
	 * 			{@code setLuts} reaches them only then.
	 *
	 * @param target			: the materialised image
	 * @param firstChannel		: index of the crop's first channel in the source view
	 * @param firstSlice		: index of the crop's first Z plane in the source view
	 * @param firstFrame		: index of the crop's first time point in the source view
	 */
	void applyTo (
			ImagePlus target,
			int firstChannel,
			int firstSlice,
			int firstFrame
			) {
		if (target == null) return;
		int channels = target.getNChannels();
		if (luts != null && luts.length > 0) {
			if (target instanceof CompositeImage) {
				LUT[] wanted = new LUT[channels];
				for (int i = 0; i < channels; i++) wanted[i] = lutFor(firstChannel + i);
				CompositeImage composite = (CompositeImage) target;
				composite.setLuts(wanted);
				if (mode >= 0) composite.setMode(mode);
				boolean[] shown = composite.getActiveChannels();
				if (active != null && shown != null)
					for (int i = 0; i < shown.length && i < channels; i++) {
						int source = firstChannel + i;
						if (source >= 0 && source < active.length) shown[i] = active[source];
					}
			} else {
				/* One channel, so one table: set the colours on the stack as well as on the
				 * processor, or only the plane currently shown would take them. */
				LUT lut = lutFor(firstChannel);
				if (lut != null) {
					ImageProcessor processor = target.getProcessor();
					if (processor != null) processor.setColorModel(lut);
					if (target.getStack() != null) target.getStack().setColorModel(lut);
					target.setDisplayRange(lut.min, lut.max);
				}
			}
		}
		target.setPosition(
				clamp(channel - firstChannel, channels),
				clamp(slice - firstSlice, target.getNSlices()),
				clamp(frame - firstFrame, target.getNFrames()));
		target.updateAndDraw();
	}

	/** The source channel's table, or the nearest one it has; never null once luts exist. */
	private LUT lutFor (int sourceChannel) {
		if (luts == null || luts.length == 0) return null;
		int index = sourceChannel < 0 ? 0 : sourceChannel >= luts.length ? luts.length - 1 : sourceChannel;
		return luts[index];
	}

	private static int clamp (int position, int available) {
		if (available < 1) return 1;
		return position < 1 ? 1 : position > available ? available : position;
	}

	/**			Carry the view's own metadata onto the crop, and say what the crop is
	 * <p>		Only what the open path has not already written: a materialised view is opened
	 * 			through the same routine as any other and stamps its own root, origin and
	 * 			channel labels, which are the crop's rather than the view's. The rest - the
	 * 			provenance text, the content kind, an approximate-overlay warning - describes
	 * 			the pixels and is as true of a part of them as of all of them.
	 * <p>		It lands in {@code Info}, which is what ImageJ writes into a TIFF's own metadata
	 * 			when the image is saved, so the note survives the window it was made in. The
	 * 			display ranges and channel colours applied above are written there too.
	 *
	 * @param source			: the view the region was cut from
	 * @param target			: the materialised image
	 * @param note				: what was cut, in the view's coordinates
	 */
	static void carryMetadata (
			ImagePlus source,
			ImagePlus target,
			String note
			) {
		if (target == null) return;
		if (source != null)
			for (String key : CARRIED) {
				Object value = source.getProperty(key);
				if (value instanceof String && target.getProperty(key) == null)
					target.setProperty(key, value);
			}
		Object existing = target.getProperty("Info");
		String info = existing instanceof String ? (String) existing : null;
		if (info == null && source != null) {
			Object inherited = source.getProperty("Info");
			if (inherited instanceof String) info = (String) inherited;
		}
		if (note == null || note.isEmpty()) {
			if (info != null) target.setProperty("Info", info);
			return;
		}
		target.setProperty("Info", info == null || info.isEmpty() ? note : info + "\n\n" + note);
	}
}
