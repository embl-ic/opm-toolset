package de.embl.iclm;

import fiji.util.gui.GenericDialogPlus;
import ij.Prefs;
import ij.gui.GenericDialog;

import java.awt.Button;
import java.awt.Choice;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Label;
import java.awt.Panel;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
	/** One-time layout reset: two visible defaults and combination disabled. */
	private static final int DESKEW_LAYOUT_VERSION = 2;

	/** Group the matching {@code _ChannelNNNN} files of one timepoint into a single result. */
	public boolean combineAcquisitionChannels = false;
	/**
	 * Let a live acquisition's own file names decide whether its channels are combined.
	 * <p>
	 * Live Processing only: a batch input is a folder that is already complete, so nothing has
	 * to be inferred from the order files arrive in. When this is on,
	 * {@link LiveChannelLayout} reads the layout off the first time point and writes it into
	 * {@link #combineAcquisitionChannels} - which then reports what the run decided rather than
	 * asking the user to know it in advance.
	 */
	public boolean autoCombineChannels = true;
	/**
	 * Fill the output slots from the decided layout and the channel option, rather than by hand.
	 * <p>
	 * Acquisition channel first, left half before right, the right half flipped: the arrangement
	 * a rig that writes one file per channel has. Unticked, the slots below are the user's and
	 * only the combining itself is decided.
	 */
	public boolean autoChannelAssignment = true;
	/** Which camera half is flipped onto the other; the other half is left untouched. */
	public String flipHalf = BatchChannelOperation.FLIP_RIGHT;
	/** Bilinear interpolation when applying the 2D alignment. */
	public boolean interpolate = true;
	/** Source feeding each output channel, in order; {@code SKIP_CHANNEL} leaves one out. */
	public final String[] channelOrder = defaultChannelOrder();

	/**
	 * The two halves of one acquisition channel, which is what a single-file rig produces.
	 * <p>
	 * Two, not four: the dialogs show a row per filled slot, and starting at four asked every
	 * user of a one-file acquisition about two channels that are not there. The rest of the
	 * array exists so a three- or four-file acquisition can reach a TIFF result - those slots
	 * stay skipped, so an existing configuration is unaffected and the extra preference keys
	 * simply read back as skips.
	 */
	static String[] defaultChannelOrder () {
		String[] order = new String[BatchChannelOperation.MAX_OUTPUT_CHANNELS];
		for (int i = 0; i < order.length; i++)
			order[i] = i < 2 ? defaultSourceFor ( i ) : BatchChannelOperation.SKIP_CHANNEL;
		return order;
	}

	/**
	 * What a newly revealed slot should offer before anyone chooses for it.
	 * <p>
	 * {@code CHANNEL_SOURCE_OPTIONS} is already built acquisition-first and left before right,
	 * so slot n is simply option n: 0001-left, 0001-right, 0002-left, 0002-right.
	 */
	static String defaultSourceFor (int slot) {
		String[] options = BatchChannelOperation.CHANNEL_SOURCE_OPTIONS;
		// the last option is "skip", which is never a useful default for a slot just added
		return slot >= 0 && slot < options.length - 1
				? options[slot] : BatchChannelOperation.SKIP_CHANNEL;
	}

	/** Whether the left half is the one flipped onto the right. */
	public boolean isFlipLeft () {
		return BatchChannelOperation.FLIP_LEFT.equals ( flipHalf );
	}

	/**			Why this selection cannot produce a result, or null when it can
	 * <p>		A full camera width is twice as wide as a half, and the channels of one
	 * 			hyperstack must share their XY size, so the two kinds cannot be mixed. Caught
	 * 			here, where the user can still change the selection: reaching
	 * 			{@link MultiChannelDeskew#combine} with a mixed set fails per time point,
	 * 			half way through an acquisition, with a message about pixel dimensions.
	 *
	 * @return					: a message to show the user, or null when the selection is usable
	 */
	public String selectionProblem () {
		if (selectedCount() == 0) return "Select at least one output channel source.";
		boolean whole = false, half = false;
		for (String selected : channelOrder) {
			if (BatchChannelOperation.SKIP_CHANNEL.equals(selected)) continue;
			if (isWholeSource(selected)) whole = true;
			else half = true;
		}
		if (whole && half)
			return "Whole-width and half-width sources cannot share one result.\n\n"
					+ "A full camera width is twice as wide as a half, and the channels of\n"
					+ "one image must have the same size. Select either whole widths or halves.";
		return null;
	}

	/** How many output channels are actually selected. */
	public int selectedCount () {
		int count = 0;
		for (String selected : channelOrder)
			if (!BatchChannelOperation.SKIP_CHANNEL.equals(selected)) count++;
		return count;
	}

	/**
	 * Acquisition files a complete live timepoint must contain, derived from its channel setup.
	 *
	 * <p>If any selected source names Channel0002, the timepoint waits for Channel0001 and
	 * Channel0002; naming Channel0004 waits for all four. This avoids a separate file-count
	 * field that could disagree with the actual output selection.
	 */
	public int[] requiredAcquisitionChannels (int fallbackCount) {
		int highest = 0;
		for (String source : channelOrder) {
			if (BatchChannelOperation.SKIP_CHANNEL.equals(source)) continue;
			highest = Math.max(highest, acquisitionChannelOf(source));
		}
		if (highest <= 0) highest = Math.max(1, fallbackCount);
		Set<Integer> wanted = new TreeSet<Integer>();
		for (int channel = 1; channel <= highest; channel++) wanted.add(Integer.valueOf(channel));
		int[] result = new int[wanted.size()];
		int next = 0;
		for (Integer channel : wanted) result[next++] = channel.intValue();
		return result;
	}

	/**
	 * Sources a deskew-time selection may draw on: every camera half, every un-split full
	 * width, and a skip.
	 * <p>
	 * Deskew Batch and Live Processing deskew the raw file themselves, so they can produce the
	 * full camera width as well as the two halves; Channel Operation, which works on a deskewed
	 * whole-width result, can too, since the shear leaves the width as it was. The entries live
	 * here rather than in {@link BatchChannelOperation#CHANNEL_SOURCE_OPTIONS} to keep that
	 * array to the halves. The canonical OME-Zarr format is defined in terms of halves and is
	 * deliberately unaffected: a whole-width selection reaches the TIFF result only.
	 */
	static final String[] DESKEW_SOURCE_OPTIONS = buildDeskewSourceOptions();

	private static String[] buildDeskewSourceOptions () {
		/* Built from the same rule as BatchChannelOperation.CHANNEL_SOURCE_OPTIONS rather than
		 * from that array. Reading it here would be a circular class initialisation: that array
		 * is itself built by calling sourceKey below, so whichever of the two classes is touched
		 * first would see the other half-initialised. The counts and the skip label are compile
		 * time constants, so naming them costs nothing and starts no initialisation. */
		final int acquisitions = BatchChannelOperation.MAX_ACQUISITION_CHANNELS;
		String[] options = new String[3 * acquisitions + 1];
		int next = 0;
		// the halves keep their positions, so a stored selection still means what it did
		for (int acquisition = 1; acquisition <= acquisitions; acquisition++) {
			options[next++] = sourceKey ( acquisition, true );
			options[next++] = sourceKey ( acquisition, false );
		}
		for (int acquisition = 1; acquisition <= acquisitions; acquisition++)
			options[next++] = wholeSourceKey ( acquisition );
		options[next] = BatchChannelOperation.SKIP_CHANNEL;
		return options;
	}

	/** The key the un-split full camera width of one acquisition channel is known by. */
	public static String wholeSourceKey (
			int acquisitionChannel
			) {
		return String.format ( Locale.US, "_Channel%04d-whole", acquisitionChannel );
	}

	/** Whether a source key names the un-split full camera width rather than one half. */
	public static boolean isWholeSource (
			String source
			) {
		return source != null && source.endsWith ( "-whole" );
	}

	/**			The acquisition channel number a source key refers to
	 *
	 * @param source			: a key such as "_Channel0002-right" or "_Channel0002-whole"
	 * <p>
	 * @return					: the channel number, or -1 when the key carries none
	 */
	public static int acquisitionChannelOf (
			String source
			) {
		if (source == null) return -1;
		int marker = source.indexOf ( "_Channel" );
		if (marker < 0 || source.length() < marker + 12) return -1;
		try {
			return Integer.parseInt ( source.substring ( marker + 8, marker + 12 ) );
		} catch (NumberFormatException unparsable) {
			return -1;
		}
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

	/**			Add the multi-channel controls with their {@code [-] [+]} already wired
	 * <p>		The form every batch dialog shares, so the list behaves the same wherever it
	 * 			appears: as many rows as are in use and never fewer than two, {@code [-]} skips
	 * 			the row it hides so a hidden row cannot still feed an output channel, and
	 * 			{@code [+]} offers the next source in acquisition-then-side order. Read back with
	 * 			{@link #readFrom}, exactly as for the four-argument form.
	 *
	 * @param buttonIndent	: left inset for the {@code [-] [+]} row
	 */
	public SlotRows addToDialog (
			final GenericDialog gd,
			int buttonIndent
			) {
		/* The listeners need the block that the call creating them returns, so the reference
		 * is handed over afterwards. */
		final List<SlotRows> built = new ArrayList<SlotRows>(1);
		final int[] visible = { slotsInUse() };
		ActionListener fewer = new ActionListener() {
			@Override public void actionPerformed (ActionEvent e) {
				if (built.isEmpty() || visible[0] <= 1) return;
				SlotRows slots = built.get(0);
				select(slots.rows.get(visible[0] - 1).choice, BatchChannelOperation.SKIP_CHANNEL);
				visible[0]--;
				showSlots(slots, visible[0]);
				gd.pack();
			}
		};
		ActionListener more = new ActionListener() {
			@Override public void actionPerformed (ActionEvent e) {
				if (built.isEmpty()) return;
				SlotRows slots = built.get(0);
				if (visible[0] >= slots.rows.size()) return;
				select(slots.rows.get(visible[0]).choice, defaultSourceFor(visible[0]));
				visible[0]++;
				showSlots(slots, visible[0]);
				gd.pack();
			}
		};
		SlotRows slots = addToDialog(gd, fewer, more, buttonIndent);
		built.add(slots);
		showSlots(slots, visible[0]);
		return slots;
	}

	/** Select a value in an AWT choice, ignoring one the choice does not offer. */
	private static void select (Component component, String value) {
		if (!(component instanceof Choice) || value == null) return;
		Choice choice = (Choice) component;
		for (int i = 0; i < choice.getItemCount(); i++)
			if (value.equals(choice.getItem(i))) { choice.select(i); return; }
	}

	/**			Add the multi-channel controls to a dialog
	 * <p>		Read them back with {@link #readFrom} in the same order.
	 * <p>
	 * @param buttonIndent	: left inset for the {@code [-] [+]} row, so it lines up under the
	 * 						  slot choices rather than against the edge of the dialog
	 */
	public SlotRows addToDialog (
			GenericDialog gd,
			ActionListener fewer,
			ActionListener more,
			int buttonIndent
			) {
		gd.addCheckbox("combine matching _Channel#### files", combineAcquisitionChannels);
		gd.addChoice("flip", FLIP_LABELS, flipLabel(flipHalf));
		SlotRows slots = new SlotRows();
		for (int i = 0; i < channelOrder.length; i++) {
			gd.addChoice(slotLabel(i + 1), DESKEW_SOURCE_OPTIONS, channelOrder[i]);
			SlotRow slot = new SlotRow();
			int count = gd.getComponentCount();
			slot.choice = count > 0 ? gd.getComponent(count - 1) : null;
			Component caption = count > 1 ? gd.getComponent(count - 2) : null;
			slot.label = caption instanceof Label ? caption : null;
			slots.rows.add(slot);
		}
		if (fewer != null && more != null) {
			/* One pair, on a row of its own under the list, and in one panel rather than two
			 * grid cells: GenericDialog.addButton gives each button a cell two columns wide,
			 * which for a second button on the same row means columns the rest of the dialog
			 * does not have. The key listener is what addButton would have added, and is what
			 * lets Escape and Enter still reach the dialog from a focused button. */
			final Component sample = slots.rows.isEmpty() ? null : slots.rows.get(0).choice;
			/* The panel is as wide as a slot dropdown and holds two equally weighted cells, so
			 * each button sits at the centre of its half - a quarter and three quarters of the
			 * dropdown. The width can only be asked for once the peers exist, which is why it
			 * is answered at layout time rather than measured here. */
			Panel pair = new Panel(new GridBagLayout()) {
				private static final long serialVersionUID = 1L;
				@Override public Dimension getPreferredSize() {
					Dimension natural = super.getPreferredSize();
					if (sample == null) return natural;
					return new Dimension(Math.max(natural.width, sample.getPreferredSize().width),
							natural.height);
				}
			};
			Button less = new Button("-");
			Button plus = new Button("+");
			less.addActionListener(fewer);
			plus.addActionListener(more);
			less.addKeyListener(gd);
			plus.addKeyListener(gd);
			GridBagConstraints half = new GridBagConstraints();
			half.gridy = 0;
			half.weightx = 1.0d;
			half.anchor = GridBagConstraints.CENTER;
			half.gridx = 0;
			pair.add(less, half);
			half.gridx = 1;
			pair.add(plus, half);
			gd.setInsets(0, buttonIndent, 0);
			gd.addPanel(pair);
			slots.fewer = less;
			slots.more = plus;
		}
		return slots;
	}

	/**
	 * One output-channel row: its caption and its choice.
	 * <p>
	 * A GenericDialog adds a labelled choice as two components in order, so the caption is the
	 * one before the control. Keeping the caption with the row is what lets it be hidden
	 * convincingly - GridBagLayout then gives the row no height at all.
	 */
	public static final class SlotRow {
		Component label;
		Component choice;
	}

	/**
	 * The output-channel block in a dialog: the slot rows, and the one pair of buttons that
	 * lengthens and shortens the list.
	 * <p>
	 * The pair used to sit on the last visible row and travel down the list with it. One row
	 * of its own below the list stays where the hand left it, and says plainly that it acts on
	 * the list rather than on the row it happens to share.
	 */
	public static final class SlotRows {
		public final List<SlotRow> rows = new ArrayList<SlotRow>();
		Component fewer;
		Component more;
	}

	/**			Show the first {@code visible} slots, and grey out what the pair cannot do
	 * <p>		The buttons keep their row at every length, so each is disabled at its end of
	 * 			the range rather than disappearing: a button that vanishes as you reach for it
	 * 			is worse than one that is plainly spent.
	 */
	static void showSlots (SlotRows slots, int visible) {
		for (int i = 0; i < slots.rows.size(); i++) {
			SlotRow slot = slots.rows.get(i);
			boolean shown = i < visible;
			if (slot.label != null) slot.label.setVisible(shown);
			if (slot.choice != null) slot.choice.setVisible(shown);
		}
		if (slots.fewer != null) slots.fewer.setEnabled(visible > 1);
		if (slots.more != null) slots.more.setEnabled(visible < slots.rows.size());
	}

	/** How many slots are actually in use, never fewer than two. */
	public int slotsInUse () {
		int used = 2;
		for (int i = 0; i < channelOrder.length; i++)
			if (!BatchChannelOperation.SKIP_CHANNEL.equals(channelOrder[i])) used = i + 1;
		return Math.max(2, Math.min(used, channelOrder.length));
	}

	/**
	 * Read back what {@link #addToDialog} added, in the same order.
	 * <p>
	 * Interpolation is no longer part of this block - the deskew dialogs put it beside the
	 * channel option, where it reads as what it is: how a transformed pixel is sampled.
	 */
	public void readFrom (
			GenericDialog gd
			) {
		combineAcquisitionChannels = gd.getNextBoolean();
		flipHalf = flipValue(gd.getNextChoice());
		for (int i = 0; i < channelOrder.length; i++) channelOrder[i] = gd.getNextChoice();
	}

	/**
	 * What the dialogs show for a flip half, against what is stored for it.
	 * <p>
	 * The two are deliberately different strings. {@link BatchChannelOperation#FLIP_RIGHT} and
	 * {@code FLIP_LEFT} are written into every OME-Zarr's {@code opm.alignFlipHalf} and
	 * compared by {@link OmeZarrView} when it applies a runtime flip, so renaming them would
	 * make every dataset already on disk stop flipping - silently, because the comparison
	 * simply stops matching. The label is a display decision; the value is a format.
	 */
	static final String[] FLIP_LABELS = {
		"flip right half onto left", "flip left half onto right"
	};

	static String flipLabel (String value) {
		return BatchChannelOperation.FLIP_LEFT.equals(value) ? FLIP_LABELS[1] : FLIP_LABELS[0];
	}

	static String flipValue (String label) {
		return FLIP_LABELS[1].equals(label)
				? BatchChannelOperation.FLIP_LEFT : BatchChannelOperation.FLIP_RIGHT;
	}


	// ---- persistence ----------------------------------------------------------------

	/** Load from the same preference keys Channel Operation uses. */
	public void load () {
		int version = (int) Prefs.get ( PREF + "deskewLayoutVersion", 0 );
		if (version < DESKEW_LAYOUT_VERSION) {
			resetDeskewLayout();
			store();
			return;
		}
		combineAcquisitionChannels = Prefs.get ( PREF + "combineInDeskew", combineAcquisitionChannels );
		autoCombineChannels = Prefs.get ( PREF + "autoCombineInLive", autoCombineChannels );
		autoChannelAssignment = Prefs.get ( PREF + "autoChannelAssignment", autoChannelAssignment );
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
		Prefs.set ( PREF + "deskewLayoutVersion", DESKEW_LAYOUT_VERSION );
		Prefs.set ( PREF + "combineInDeskew", combineAcquisitionChannels );
		Prefs.set ( PREF + "autoCombineInLive", autoCombineChannels );
		Prefs.set ( PREF + "autoChannelAssignment", autoChannelAssignment );
		Prefs.set ( PREF + "flipHalf", flipHalf );
		Prefs.set ( PREF + "interpolate", interpolate );
		for (int i = 0; i < channelOrder.length; i++)
			Prefs.set ( PREF + "output" + (i + 1), channelOrder[i] );
	}

	private void resetDeskewLayout () {
		combineAcquisitionChannels = false;
		autoCombineChannels = true;
		autoChannelAssignment = true;
		flipHalf = BatchChannelOperation.FLIP_RIGHT;
		interpolate = true;
		String[] defaults = defaultChannelOrder();
		System.arraycopy ( defaults, 0, channelOrder, 0, channelOrder.length );
	}

	static boolean isSourceOption (
			String value
			) {
		for (String option : DESKEW_SOURCE_OPTIONS)
			if (option.equals(value)) return true;
		return false;
	}

	/** Ordinal labels for the output slots, shared with the live setup dialog. */
	static String slotLabel (int number) {
		return ordinal ( number ) + " channel";
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
