package de.embl.iclm;

import java.io.File;

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
	static final String[] PARAMETER_SOURCES = { "ExperimentalParameters.txt in the folder", "manual" };
	static final String[] INTERPOLATIONS = { "trilinear", "nearest neighbour (as stored)" };

	@Override
	public void run(String arg) {
		Debug.commandStarted(MENU + " > Open Raw Acquisition");
		Settings settings = Settings.load();
		if (!dialog(settings)) return;
		settings.store();
		try {
			OnlineDeskewSession.open(settings);
		} catch (Exception e) {
			IJ.error(MENU, e.getMessage() == null ? e.toString() : e.getMessage());
		}
	}

	static boolean dialog(Settings s) {
		OpmDialog gd = new OpmDialog(TITLE);
		Parameter.styleDialog(gd);
		Parameter.addSection(gd, "Input setup:");
		gd.addDirectoryField("raw acquisition folder", s.folder, 40);
		gd.addNumericField("position", s.position, 0);
		gd.addNumericField("acquisition channel", s.channel, 0);
		Parameter.addSection(gd, "Deskew parameters:");
		gd.addChoice("parameters from", PARAMETER_SOURCES, s.parameterSource);
		gd.addNumericField("Z step (um)", s.dz, 4);
		gd.addNumericField("pixel size (um)", s.dxy, 4);
		gd.addNumericField("OPM angle (degrees)", s.angle, 2);
		Parameter.addSection(gd, "Channels:");
		gd.addChoice("flip", ChannelOperationSettings.FLIP_LABELS, ChannelOperationSettings.flipLabel(s.flip));
		gd.addFileField("align matrix (optional)", s.alignmentCsv, 40);
		Parameter.addSection(gd, "Display:");
		gd.addChoice("interpolation", INTERPOLATIONS, s.interpolation);
		gd.addNumericField("cache budget (MB)", s.budgetMb, 0);
		gd.addHelp(Help.onlineDeskew);
		gd.showDialog();
		if (gd.wasCanceled()) return false;
		s.folder = gd.getNextString().trim();
		s.position = (int) gd.getNextNumber();
		s.channel = (int) gd.getNextNumber();
		s.parameterSource = gd.getNextChoice();
		s.dz = gd.getNextNumber();
		s.dxy = gd.getNextNumber();
		s.angle = gd.getNextNumber();
		s.flip = ChannelOperationSettings.flipValue(gd.getNextChoice());
		s.alignmentCsv = gd.getNextString().trim();
		s.interpolation = gd.getNextChoice();
		s.budgetMb = gd.getNextNumber();
		return true;
	}

	/** What the dialog asks, remembered between uses. */
	static final class Settings {
		private static final String KEY = "opm.onlineDeskew.";

		String folder = "";
		int position = 1;
		int channel = 1;
		String parameterSource = PARAMETER_SOURCES[0];
		double dz = 0.265, dxy = 0.116, angle = 33.5;
		String flip = BatchChannelOperation.FLIP_RIGHT;
		String alignmentCsv = "";
		String interpolation = INTERPOLATIONS[0];
		/** Default: 40% of the maximum heap, at least 256 MB. */
		double budgetMb = Math.max(256, Math.round(0.4 * Runtime.getRuntime().maxMemory() / 1e6));

		static Settings load() {
			Settings s = new Settings();
			s.folder = Prefs.get(KEY + "folder", s.folder);
			s.position = (int) Prefs.get(KEY + "position", s.position);
			s.channel = (int) Prefs.get(KEY + "channel", s.channel);
			s.parameterSource = Prefs.get(KEY + "parameterSource", s.parameterSource);
			s.dz = Prefs.get(KEY + "dz", s.dz);
			s.dxy = Prefs.get(KEY + "dxy", s.dxy);
			s.angle = Prefs.get(KEY + "angle", s.angle);
			s.flip = Prefs.get(KEY + "flip", s.flip);
			s.alignmentCsv = Prefs.get(KEY + "alignmentCsv", s.alignmentCsv);
			s.interpolation = Prefs.get(KEY + "interpolation", s.interpolation);
			s.budgetMb = Prefs.get(KEY + "budgetMb", s.budgetMb);
			return s;
		}

		void store() {
			Prefs.set(KEY + "folder", folder);
			Prefs.set(KEY + "position", position);
			Prefs.set(KEY + "channel", channel);
			Prefs.set(KEY + "parameterSource", parameterSource);
			Prefs.set(KEY + "dz", dz);
			Prefs.set(KEY + "dxy", dxy);
			Prefs.set(KEY + "angle", angle);
			Prefs.set(KEY + "flip", flip);
			Prefs.set(KEY + "alignmentCsv", alignmentCsv);
			Prefs.set(KEY + "interpolation", interpolation);
			Prefs.set(KEY + "budgetMb", budgetMb);
		}

		File folder() {
			return new File(folder);
		}

		boolean parametersFromFile() {
			return PARAMETER_SOURCES[0].equals(parameterSource);
		}

		boolean flipLeft() {
			return BatchChannelOperation.FLIP_LEFT.equals(flip);
		}

		Interpolation interpolation() {
			return INTERPOLATIONS[1].equals(interpolation) ? Interpolation.NEARESTNEIGHBOR : Interpolation.NLINEAR;
		}

		long budgetBytes() {
			return (long) (Math.max(64, budgetMb) * 1e6);
		}
	}
}
