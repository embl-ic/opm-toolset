package de.embl.iclm;

import java.io.File;
import java.awt.Choice;
import java.awt.Checkbox;
import java.awt.TextField;
import java.awt.SystemColor;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;

import bdv.viewer.Interpolation;
import ij.IJ;
import ij.Prefs;
import ij.plugin.PlugIn;

/**
 * {@code Plugins > OPM Toolset > Online Deskew View (BDV) > Open Raw Acquisition...}
 *
 * <p>Opens a folder of raw OPM acquisition volumes in BigDataViewer and shows them deskewed - and,
 * with an alignment matrix, channel-aligned - at display time. The raw files are read as they are
 * and never rewritten: the deskew, the mirror of one camera half and the 2-D alignment are one
 * transform per displayed half, applied while the view is drawn (joint plan, DECISIONS D6).
 *
 * <p>The dialog asks only what the folder cannot tell: which position and acquisition channel to
 * show, where the deskew geometry comes from, which half is mirrored and the optional alignment
 * matrix. Everything else - time points, plane counts, camera size - is read from the files.
 */
public class OnlineDeskewView implements PlugIn {

	static final String MENU = "Online Deskew View (BDV)";
	static final String TITLE = MENU + " - Open Raw Acquisition";
	static final String[] PARAMETER_SOURCES = { "ExperimentalParameters.txt in the folder", "manual", "from file input" };
	static final String[] INTERPOLATIONS = { "trilinear", "nearest neighbour (as stored)" };

	@Override
	public void run(String arg) {
		Debug.commandStarted(MENU + " > Open Raw Acquisition");
		final Settings settings = Settings.load();
		if (!dialog(settings)) return;
		settings.store();
		// a cancellable operation: Escape, Batch Processing > Terminate... and Fiji's quit stop the opening
		final Exception[] failure = new Exception[1];
		Shutdown.runCancellable(MENU + ": opening " + settings.folder().getName(), new Runnable() {
			@Override public void run() {
				try {
					OnlineDeskewSession.open(settings);
				} catch (CancellationException cancelled) {
					IJ.log(MENU + ": " + cancelled.getMessage());
				} catch (Exception e) {
					failure[0] = e;
				}
			}
		});
		if (failure[0] != null)
			IJ.error(MENU, failure[0].getMessage() == null ? failure[0].toString() : failure[0].getMessage());
	}

	static boolean dialog(Settings s) {
		OpmDialog gd = new OpmDialog(TITLE);
		Parameter.styleDialog(gd);
		Parameter.addSection(gd, "Input setup:");
		gd.addDirectoryField("raw acquisition folder", s.folder, 40);
		Parameter.addSection(gd, "Deskew parameters:");
		gd.addChoice("parameters from", PARAMETER_SOURCES, s.parameterSource);
		Choice parameterSource = (Choice) gd.getChoices().lastElement();
		gd.addNumericField("Z step (um)", s.dz, 4);
		TextField dz = (TextField) gd.getNumericFields().lastElement();
		gd.addNumericField("pixel size (um)", s.dxy, 4);
		TextField dxy = (TextField) gd.getNumericFields().lastElement();
		gd.addNumericField("OPM angle (degrees)", s.angle, 2);
		TextField angle = (TextField) gd.getNumericFields().lastElement();
		gd.addFileField("parameter file", s.parameterFile, 40);
		TextField parameterFile = (TextField) gd.getStringFields().lastElement();
		Parameter.addSection(gd, "Channels:");
		gd.addNumericField("acquisition channel", s.channel, 0);
		TextField acquisition = (TextField) gd.getNumericFields().lastElement();
		s.channels.flipHalf = s.flip;
		ChannelOperationSettings.SlotRows slots = s.channels.addToDialog(gd, 120);
		gd.addMessage("When combining, only the selected files are required.\nWhole-width sources keep both halves in their acquired positions.");
		gd.addCheckbox("align channel with alignment matrix CSV file", s.alignChannels);
		Checkbox align = (Checkbox) gd.getCheckboxes().lastElement();
		gd.addFileField("align matrix", s.alignmentCsv, 40);
		TextField matrix = (TextField) gd.getStringFields().lastElement();
		SetupRule rule = new SetupRule(parameterSource, new TextField[] { dz, dxy, angle },
				parameterFile, align, matrix, acquisition, slots);
		gd.addDialogListener((dialog, event) -> { rule.refresh(); return true; });
		Parameter.addSection(gd, "Display:");
		gd.addChoice("interpolation", INTERPOLATIONS, s.interpolation);
		gd.addNumericField("cache budget (MB)", s.budgetMb, 0);
		gd.addHelp(Help.onlineDeskew);
		gd.showDialog();
		if (gd.wasCanceled()) return false;
		s.folder = gd.getNextString().trim();
		s.parameterSource = gd.getNextChoice();
		s.dz = gd.getNextNumber();
		s.dxy = gd.getNextNumber();
		s.angle = gd.getNextNumber();
		s.parameterFile = gd.getNextString().trim();
		s.channel = (int) gd.getNextNumber();
		s.channels.readFrom(gd);
		s.flip = s.channels.flipHalf;
		s.alignChannels = gd.getNextBoolean();
		s.alignmentCsv = gd.getNextString().trim();
		s.interpolation = gd.getNextChoice();
		s.budgetMb = gd.getNextNumber();
		// a mistyped number reads as NaN; refuse it rather than remember it or open with it
		if (gd.invalidNumber()) {
			IJ.error(TITLE, gd.getErrorMessage());
			return false;
		}
		return true;
	}

	/** Captured controls, shared by dialog listeners and explicit path arrivals. */
	static final class SetupRule {
		final Choice source;
		final TextField[] manual;
		final Checkbox align;
		final TextField acquisition;
		final ChannelOperationSettings.SlotRows slots;
		final PathArrival parameters, matrix;

		SetupRule(Choice source, TextField[] manual, TextField parameters, Checkbox align,
				TextField matrix, TextField acquisition, ChannelOperationSettings.SlotRows slots) {
			this.source = source; this.manual = manual; this.align = align;
			this.acquisition = acquisition; this.slots = slots;
			this.parameters = new PathArrival(parameters, true, () -> source.select(PARAMETER_SOURCES[2]));
			this.matrix = new PathArrival(matrix, false, () -> align.setState(true));
			source.addItemListener(e -> refresh());
			align.addItemListener(e -> refresh());
			slots.combine.addItemListener(e -> refresh());
			parameters.addTextListener(e -> refresh());
			matrix.addTextListener(e -> refresh());
			refresh();
		}

		void refresh() {
			parameters.changed(); matrix.changed();
			boolean typed = PARAMETER_SOURCES[1].equals(source.getSelectedItem());
			for (TextField field : manual) field.setEnabled(typed);
			parameters.editable(PARAMETER_SOURCES[2].equals(source.getSelectedItem()));
			matrix.editable(align.getState());
			boolean combine = slots.combine.getState();
			acquisition.setEnabled(!combine);
			int visible = 0;
			for (ChannelOperationSettings.SlotRow row : slots.rows) {
				if (row.choice == null) continue;
				row.choice.setEnabled(combine);
				if (row.choice.isVisible()) visible++;
			}
			if (slots.fewer != null) slots.fewer.setEnabled(combine && visible > 1);
			if (slots.more != null) slots.more.setEnabled(combine && visible < slots.rows.size());
		}

		final class PathArrival {
			final TextField field;
			final boolean textOnly;
			final Runnable select;
			final java.awt.Color background, foreground;
			String seen;

			PathArrival(TextField field, boolean textOnly, Runnable select) {
				this.field = field; this.textOnly = textOnly; this.select = select;
				background = field.getBackground(); foreground = field.getForeground();
				seen = field.getText().trim(); // restored values must not select anything
				field.setDropTarget(null);
				new DropTarget(field, new DropTargetAdapter() {
					@Override public void drop(DropTargetDropEvent event) {
						if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) { refuse(); event.rejectDrop(); return; }
						event.acceptDrop(DnDConstants.ACTION_COPY);
						boolean accepted = false;
						try {
							List<?> files = (List<?>) event.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
							if (files.size() == 1 && files.get(0) instanceof File)
								accepted = arrived(((File) files.get(0)).getAbsolutePath());
							else refuse();
						} catch (Exception unsupported) { refuse(); }
						event.dropComplete(accepted);
					}
				});
			}

			boolean arrived(String path) {
				File file = new File(path);
				if (!file.isFile() || textOnly && !file.getName().toLowerCase(Locale.ROOT).endsWith(".txt")) {
					refuse(); return false;
				}
				field.setText(path);
				seen = path.trim();
				select.run(); // setText does not reliably emit a TextEvent on every platform
				refresh();
				return true;
			}

			void changed() {
				String now = field.getText().trim();
				if (!now.equals(seen)) {
					seen = now;
					if (!now.isEmpty()) select.run();
				}
			}

			void editable(boolean enabled) {
				field.setEditable(enabled);
				field.setBackground(enabled ? background : SystemColor.control);
				field.setForeground(enabled ? foreground : SystemColor.textInactiveText);
			}

			void refuse() { IJ.showStatus(textOnly ? "Drop one regular .txt parameter file." : "Drop one file for the alignment matrix."); }
		}
	}

	/** What the dialog asks, remembered between uses. */
	static final class Settings {
		private static final String KEY = "opm.onlineDeskew.";

		String folder = "";
		int position = 1;
		int channel = 1;
		final ChannelOperationSettings channels = new ChannelOperationSettings();
		String parameterSource = PARAMETER_SOURCES[0];
		String parameterFile = "";
		boolean alignChannels;
		double dz = 0.265, dxy = 0.116, angle = 33.5;
		String flip = BatchChannelOperation.FLIP_RIGHT;
		String alignmentCsv = "";
		String interpolation = INTERPOLATIONS[0];
		/** Default: 40% of the maximum heap, at least 256 MB. */
		double budgetMb = Math.max(256, Math.round(0.4 * Runtime.getRuntime().maxMemory() / 1e6));
		/**
		 * Show a file of unverified depth - its name states no plane count - that arrives while the folder is followed. Off by
		 * default (C2.3): such a file can be a volume caught mid-write. One already there when the folder is opened is shown.
		 */
		boolean showUnverifiedArrivals;

		static Settings load() {
			Settings s = new Settings();
			s.folder = Prefs.get(KEY + "folder", s.folder);
			s.position = (int) Prefs.get(KEY + "position", s.position);
			s.channel = (int) Prefs.get(KEY + "channel", s.channel);
			s.parameterSource = Prefs.get(KEY + "parameterSource", s.parameterSource);
			if (!java.util.Arrays.asList(PARAMETER_SOURCES).contains(s.parameterSource)) s.parameterSource = PARAMETER_SOURCES[0];
			s.parameterFile = Prefs.get(KEY + "parameterFile", s.parameterFile);
			s.dz = Prefs.get(KEY + "dz", s.dz);
			s.dxy = Prefs.get(KEY + "dxy", s.dxy);
			s.angle = Prefs.get(KEY + "angle", s.angle);
			s.flip = Prefs.get(KEY + "flip", s.flip);
			s.channels.flipHalf = s.flip;
			s.channels.combineAcquisitionChannels = Prefs.get(KEY + "combine", false);
			for (int i = 0; i < s.channels.channelOrder.length; i++)
				s.channels.channelOrder[i] = Prefs.get(KEY + "slot." + i, s.channels.channelOrder[i]);
			s.alignmentCsv = Prefs.get(KEY + "alignmentCsv", s.alignmentCsv);
			s.alignChannels = Prefs.get(KEY + "alignChannels", !s.alignmentCsv.trim().isEmpty());
			s.interpolation = Prefs.get(KEY + "interpolation", s.interpolation);
			s.budgetMb = Prefs.get(KEY + "budgetMb", s.budgetMb);
			return s;
		}

		/**
		 * The position the view switched to: the next opening starts there if its folder has it. The dialog no longer asks;
		 * the view's Position box switches (user's request, 2026-11-06).
		 */
		void rememberPosition(int shown) {
			position = shown;
			Prefs.set(KEY + "position", shown);
		}

		void store() {
			Prefs.set(KEY + "folder", folder);
			Prefs.set(KEY + "position", position);
			Prefs.set(KEY + "channel", channel);
			Prefs.set(KEY + "parameterSource", parameterSource);
			Prefs.set(KEY + "parameterFile", parameterFile);
			Prefs.set(KEY + "alignChannels", alignChannels);
			Prefs.set(KEY + "dz", dz);
			Prefs.set(KEY + "dxy", dxy);
			Prefs.set(KEY + "angle", angle);
			Prefs.set(KEY + "flip", channels.combineAcquisitionChannels ? channels.flipHalf : flip);
			Prefs.set(KEY + "combine", channels.combineAcquisitionChannels);
			for (int i = 0; i < channels.channelOrder.length; i++) Prefs.set(KEY + "slot." + i, channels.channelOrder[i]);
			Prefs.set(KEY + "alignmentCsv", alignmentCsv);
			Prefs.set(KEY + "interpolation", interpolation);
			Prefs.set(KEY + "budgetMb", budgetMb);
		}

		/** Shared slot dialect and ordering, with the viewer's selected-files-only policy (C2.5). */
		java.util.List<String> sourceKeys() {
			if (!channels.combineAcquisitionChannels) {
				if (channel < 1) throw new IllegalArgumentException("Acquisition channel must be positive");
				return java.util.Arrays.asList(ChannelOperationSettings.sourceKey(channel, true),
						ChannelOperationSettings.sourceKey(channel, false));
			}
			java.util.List<String> keys = new java.util.ArrayList<String>();
			for (String key : channels.channelOrder) {
				if (BatchChannelOperation.SKIP_CHANNEL.equals(key)) continue;
				int c = ChannelOperationSettings.acquisitionChannelOf(key);
				if (c < 1 || (!ChannelOperationSettings.sourceKey(c, true).equals(key)
						&& !ChannelOperationSettings.sourceKey(c, false).equals(key)
						&& !ChannelOperationSettings.wholeSourceKey(c).equals(key)))
					throw new IllegalArgumentException("Select a source (_ChannelNNNN-whole/left/right): " + key);
				keys.add(key);
			}
			if (keys.isEmpty()) throw new IllegalArgumentException("Select at least one output channel source");
			return keys;
		}

		int[] requiredChannels() {
			java.util.Set<Integer> selected = new java.util.TreeSet<Integer>();
			for (String key : sourceKeys()) selected.add(ChannelOperationSettings.acquisitionChannelOf(key));
			int[] result = new int[selected.size()];
			int i = 0;
			for (int c : selected) result[i++] = c;
			return result;
		}

		File folder() {
			return new File(folder);
		}

		File parameterFile() {
			if (PARAMETER_SOURCES[0].equals(parameterSource)) return new File(folder(), "ExperimentalParameters.txt");
			return PARAMETER_SOURCES[2].equals(parameterSource) ? new File(parameterFile) : null;
		}

		boolean parametersFromFile() { return parameterFile() != null; }

		boolean flipLeft() {
			return BatchChannelOperation.FLIP_LEFT.equals(channels.combineAcquisitionChannels ? channels.flipHalf : flip);
		}

		Interpolation interpolation() {
			return INTERPOLATIONS[1].equals(interpolation) ? Interpolation.NEARESTNEIGHBOR : Interpolation.NLINEAR;
		}

		long budgetBytes() {
			return (long) (Math.max(64, budgetMb) * 1e6);
		}
	}
}
