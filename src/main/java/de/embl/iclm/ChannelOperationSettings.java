package de.embl.iclm;

import fiji.util.gui.GenericDialogPlus;
import ij.Prefs;
import ij.gui.GenericDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The multi-channel settings shared by Channel Operation, Deskew Batch and Live Processing.
 *
 * <p>The microscope writes one file per acquisition channel - {@code _Channel0001},
 * {@code _Channel0002} - and each of those files carries a mirrored pair of camera halves.
 * A two-file acquisition therefore holds four optical channels, and which of them end up in
 * the result, and in what order, is a decision the user makes once.
 *
 * <p>Channel Operation already asked that question. Deskew Batch and Live Processing did
 * not: they treated every file on its own, so the channels of one timepoint came out as
 * separate results with no way to say how they should be arranged. This class holds the
 * answer in one place, reading and writing the same preference keys Channel Operation
 * already uses, so a setting made in one command is the setting the others see.
 *
 * @see BatchChannelOperation for the standalone command these settings came from
 */
public class ChannelOperationSettings {

	/** Prefix of the preference keys, shared with Channel Operation. */
	private static final String PREF = "opm.batchChannel.";

	/** Group the matching {@code _ChannelNNNN} files of one timepoint into a single result. */
	public boolean combineAcquisitionChannels = false;
	/** Which camera half is flipped onto the other; the other half is left untouched. */
	public String flipHalf = BatchChannelOperation.FLIP_RIGHT;
	/** Bilinear interpolation when applying the 2D alignment. */
	public boolean interpolate = true;
	/** Source feeding each output channel, in order; {@code SKIP_CHANNEL} leaves one out. */
	public final String[] channelOrder = {
		BatchChannelOperation.CHANNEL_SOURCE_OPTIONS[0], BatchChannelOperation.CHANNEL_SOURCE_OPTIONS[1],
		BatchChannelOperation.CHANNEL_SOURCE_OPTIONS[2], BatchChannelOperation.CHANNEL_SOURCE_OPTIONS[3],
		BatchChannelOperation.SKIP_CHANNEL, BatchChannelOperation.SKIP_CHANNEL
	};

	/** Whether the left half is the one flipped onto the right. */
	public boolean isFlipLeft () {
		return BatchChannelOperation.FLIP_LEFT.equals ( flipHalf );
	}

	/** How many output channels are actually selected. */
	public int selectedCount () {
		int count = 0;
		for (String selected : channelOrder)
			if (!BatchChannelOperation.SKIP_CHANNEL.equals(selected)) count++;
		return count;
	}

	/**			The key a given camera half of a given acquisition channel is known by
	 * <p>		Matches the labels offered in the dialog, so a selection maps straight onto
	 * 			the image it names.
	 *
	 * @param acquisitionChannel	: the number from the file's _ChannelNNNN token, 1 based
	 * @param left					: true for the left half, false for the right
	 * <p>
	 * @return						: for example "_Channel0002-right"
	 */
	public static String sourceKey (
			int acquisitionChannel,
			boolean left
			) {
		return String.format ( Locale.US, "_Channel%04d-%s", acquisitionChannel, left ? "left" : "right" );
	}


	/**			Group files so the acquisition channels of one timepoint travel together
	 * <p>		When combining is off every file is its own group, which is the behaviour
	 * 			Deskew Batch and Live Processing had before.
	 *
	 * @param files				: the files to group
	 * <p>
	 * @return					: groups in discovery order, each sorted by acquisition channel
	 */
	public Map<String, List<File>> group (
			List<File> files
			) {
		Map<String, List<File>> groups = new LinkedHashMap<String, List<File>>();
		if (files == null) return groups;
		for (File file : files) {
			String key = combineAcquisitionChannels
					? BatchProcessingUtils.channelGroupKey(file) : file.getAbsolutePath();
			List<File> group = groups.get(key);
			if (group == null) {
				group = new ArrayList<File>();
				groups.put(key, group);
			}
			group.add(file);
		}
		for (List<File> group : groups.values()) sortByAcquisitionChannel ( group );
		return groups;
	}

	static void sortByAcquisitionChannel (
			List<File> group
			) {
		java.util.Collections.sort ( group, new java.util.Comparator<File>() {
			@Override
			public int compare(File a, File b) {
				int ca = BatchProcessingUtils.acquisitionChannel(a);
				int cb = BatchProcessingUtils.acquisitionChannel(b);
				if (ca != cb) return ca < cb ? -1 : 1;
				return a.getName().compareToIgnoreCase(b.getName());
			}
		});
	}


	// ---- dialog ---------------------------------------------------------------------

	/**			Add the multi-channel controls to a dialog
	 * <p>		Read them back with {@link #readFrom} in the same order.
	 */
	public void addToDialog (
			GenericDialogPlus gd
			) {
		gd.addMessage("Multi-channel acquisition (one file per _ChannelNNNN):");
		gd.addCheckbox("combine matching _ChannelNNNN files", combineAcquisitionChannels);
		gd.addChoice("flip and align", new String[] {
				BatchChannelOperation.FLIP_RIGHT, BatchChannelOperation.FLIP_LEFT }, flipHalf);
		for (int i = 0; i < channelOrder.length; i++)
			gd.addChoice(ordinal(i + 1) + " output channel",
					BatchChannelOperation.CHANNEL_SOURCE_OPTIONS, channelOrder[i]);
		gd.addCheckbox("bilinear interpolation", interpolate);
	}

	/** Read back what {@link #addToDialog} added, in the same order. */
	public void readFrom (
			GenericDialog gd
			) {
		combineAcquisitionChannels = gd.getNextBoolean();
		flipHalf = gd.getNextChoice();
		for (int i = 0; i < channelOrder.length; i++) channelOrder[i] = gd.getNextChoice();
		interpolate = gd.getNextBoolean();
	}


	// ---- persistence ----------------------------------------------------------------

	/** Load from the same preference keys Channel Operation uses. */
	public void load () {
		combineAcquisitionChannels = Prefs.get ( PREF + "combineInDeskew", combineAcquisitionChannels );
		String storedFlip = Prefs.get ( PREF + "flipHalf", flipHalf );
		if (BatchChannelOperation.FLIP_RIGHT.equals(storedFlip) || BatchChannelOperation.FLIP_LEFT.equals(storedFlip))
			flipHalf = storedFlip;
		interpolate = Prefs.get ( PREF + "interpolate", interpolate );
		for (int i = 0; i < channelOrder.length; i++) {
			String stored = Prefs.get ( PREF + "output" + (i + 1), channelOrder[i] );
			if (isSourceOption(stored)) channelOrder[i] = stored;
		}
	}

	/** Store to the same preference keys Channel Operation uses. */
	public void store () {
		Prefs.set ( PREF + "combineInDeskew", combineAcquisitionChannels );
		Prefs.set ( PREF + "flipHalf", flipHalf );
		Prefs.set ( PREF + "interpolate", interpolate );
		for (int i = 0; i < channelOrder.length; i++)
			Prefs.set ( PREF + "output" + (i + 1), channelOrder[i] );
	}

	static boolean isSourceOption (
			String value
			) {
		for (String option : BatchChannelOperation.CHANNEL_SOURCE_OPTIONS)
			if (option.equals(value)) return true;
		return false;
	}

	private static String ordinal (int number) {
		switch (number) {
		case 1: return "1st";
		case 2: return "2nd";
		case 3: return "3rd";
		default: return number + "th";
		}
	}
}
