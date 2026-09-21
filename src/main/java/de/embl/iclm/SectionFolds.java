package de.embl.iclm;

import java.awt.Button;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Label;
import java.awt.LayoutManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.JButton;
import javax.swing.SwingConstants;

import ij.IJ;
import ij.Prefs;
import ij.gui.GUI;
import ij.gui.GenericDialog;

/**		The sections of a {@link GenericDialog}, folded and unfolded under their headings
 * <p>	Every bold heading {@link Parameter#addSection} adds becomes a fold, the way the sections of
 * <br>	{@code Align Channel of OPM Data} are: {@code "▼  Input setup:"} open, {@code "►  Input setup:"}
 * <br>	folded, a click on the heading to switch. A long dialog - Batch Processing - Deskew is the
 * <br>	one that prompted it - can then be brought within a small screen, where before its lower
 * <br>	rows, and with them OK, were simply off the bottom and out of reach: a {@code GenericDialog}
 * <br>	does not scroll.
 *
 * <p>	<b>A section is the components added after its heading and before the next one.</b> A
 * <br>	{@code GenericDialog} adds every row straight to itself, in order, so that is read off the
 * <br>	dialog once it is built - {@link OpmDialog} and {@link OpmDialogPlus} call {@link #install}
 * <br>	from {@code setup()}, which {@code showDialog()} runs after its {@code pack()} and before the
 * <br>	window is shown. The row of OK / Cancel / Help belongs to no section, nor does anything above
 * <br>	the first heading. Nothing about the dialog's values changes: a folded field is still read by
 * <br>	{@code getNextNumber()} and friends, and in a macro {@code setup()} is never reached at all.
 *
 * <p>	<b>The heading is a Swing button in the label's place.</b> {@code addSection} adds an AWT
 * <br>	{@code Label}, and on Windows a native label draws in a font with no triangle in it - the
 * <br>	markers came out as empty boxes. {@link #install} therefore puts a flat {@link JButton}, styled
 * <br>	as the Channel Alignment dialog's fold headings, in the label's grid cell with the label's own
 * <br>	constraints, font and text, so it sits exactly where the label did and Java2D draws the
 * <br>	triangle as it does there. It takes no keyboard focus, so a dialog still opens with the
 * <br>	focus in its first field rather than on a heading.
 *
 * <p>	<b>It fits itself to the screen.</b> Whenever the dialog is packed - built, a section opened,
 * <br>	a channel row added - and comes out taller than the usable height of its screen, the largest
 * <br>	open section is folded, then the next, until it fits. The section just opened and the one
 * <br>	holding the focus are never the ones folded; somebody who opens a section wants that one.
 *
 * <p>	<b>A dialog opens folded the way it was last left.</b> After every click on a heading the
 * <br>	state of all its sections is stored ({@code opm.fold.<dialog>.<section>}), including any the
 * <br>	click folded to make room, and it is applied before the fit when the dialog next opens. So
 * <br>	a user on a small screen who folds what they never change stops having the fit choose for
 * <br>	them. What the fit folds on its own as a dialog opens is not stored. A heading that says
 * <br>	{@code ►} is all the record anybody needs to see; a section never seen before starts open.
 *
 * <p>	<b>A dialog's own hidden rows stay hidden.</b> Some rows are hidden by the dialog itself - the
 * <br>	channel slots beyond the ones in use - so a fold records what each member was showing and an
 * <br>	unfold puts back exactly that, rather than showing everything. A member the dialog shows while
 * <br>	its section is folded is noticed at the next {@code pack()} and folded away with the rest.
 *
 * @author ziqiang.huang@embl.de
 */
final class SectionFolds {

	/** The component name {@link Parameter#addSection} gives a heading, so it can be found again. */
	static final String HEADING = "opm-section-heading";
	/** Where a dialog's fold arrangement is kept, per dialog title and section title. */
	private static final String PREF = "opm.fold.";
	/** Open and folded markers - WGL4 triangles, which Java2D finds in every desktop font. */
	static final String OPEN = "▼  ";
	static final String FOLDED = "►  ";

	private final GenericDialog dialog;
	private final List<Section> sections = new ArrayList<Section>();
	/** Set while this class packs the dialog itself, so its own {@code pack()} does not refit. */
	private boolean fitting;
	/** The section the user has just opened, which the refit that follows must not fold again. */
	private Section opening;

	private SectionFolds(GenericDialog dialog) {
		this.dialog = dialog;
	}

	/**			Turn a built dialog's headings into folds, and fit it to its screen
	 *
	 * @param dialog	: packed but not yet shown - what {@code setup()} is handed
	 * <p>
	 * @return			: its folds, or null when it has no section headings
	 */
	static SectionFolds install(GenericDialog dialog) {
		SectionFolds folds = new SectionFolds(dialog);
		Component buttons = buttonRow(dialog);
		LayoutManager layout = dialog.getLayout();
		if (!(layout instanceof GridBagLayout)) return null;
		Section current = null;
		for (Component child : dialog.getComponents()) {
			if (child == buttons) break;
			if (child instanceof Label && HEADING.equals(child.getName())) {
				current = folds.new Section((Label) child);
				folds.sections.add(current);
			} else if (current != null) {
				current.members.add(child);
			}
		}
		if (folds.sections.isEmpty()) return null;
		for (Section section : folds.sections) {
			section.replaceLabel((GridBagLayout) layout);
			if (storedFolded(dialog.getTitle(), section.title)) section.setExpanded(false);
		}
		dialog.pack();		// the heading's size is not quite the label's
		folds.fit();
		return folds;
	}

	/** The panel {@code showDialog()} puts OK and Cancel in, the last thing it adds. */
	private static Component buttonRow(GenericDialog dialog) {
		Button[] buttons = dialog.getButtons();
		return buttons == null || buttons[0] == null ? null : buttons[0].getParent();
	}

	/** Whether a section was folded when its dialog was last left; false for one never seen. */
	static boolean storedFolded(String dialog, String section) {
		return Prefs.get(key(dialog, section), false);
	}

	static void storeFolded(String dialog, String section, boolean folded) {
		Prefs.set(key(dialog, section), folded);
	}

	private static String key(String dialog, String section) {
		return PREF + slug(dialog) + "." + slug(section);
	}

	/** Letters and digits only, so a title is a safe preference key. */
	private static String slug(String text) {
		return String.valueOf(text).replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
	}

	/** A heading's own text, without the fold marker in front of it. */
	static String title(String text) {
		if (text == null) return null;
		if (text.startsWith(OPEN)) return text.substring(OPEN.length());
		if (text.startsWith(FOLDED)) return text.substring(FOLDED.length());
		return text;
	}

	/**			The dialog has been packed: keep folded sections folded, and keep it on its screen
	 * <p>		Called from the dialog's own {@code pack()}, whoever called that.
	 */
	void packed() {
		if (fitting) return;
		fit();
	}

	/** The sections, in the order they appear. For the tests, and nothing else. */
	List<Section> sections() {
		return sections;
	}

	/** Fold the largest open sections until the dialog fits its screen, then keep it on it. */
	private void fit() {
		Rectangle screen = screen();
		if (screen == null || screen.height <= 0) return;	// headless, or nothing to measure against
		fitTo(screen.height);
		if (dialog.isShowing()) keepOnScreen(screen);
	}

	/**			Fold open sections, largest first, until the dialog is no taller than {@code limit}
	 * <p>		Package-private so a test can ask for a screen smaller than the one it runs on.
	 */
	void fitTo(int limit) {
		fitting = true;
		try {
			boolean strays = false;
			for (Section section : sections) strays |= section.refold();
			if (strays) dialog.pack();
			Set<Section> keep = new HashSet<Section>();
			if (opening != null) keep.add(opening);
			Section focused = sectionOf(dialog.getFocusOwner());
			if (focused != null) keep.add(focused);
			while (dialog.getHeight() > limit) {
				Section largest = null;
				for (Section section : sections)
					if (section.expanded && !keep.contains(section)
							&& (largest == null || section.extent() > largest.extent()))
						largest = section;
				if (largest == null || largest.extent() <= 0) break;
				largest.setExpanded(false);
				dialog.pack();
			}
		} finally {
			fitting = false;
		}
	}

	private Rectangle screen() {
		try {
			// before it is shown, the screen it is about to be centred on, which is ImageJ's
			return GUI.getMaxWindowBounds(dialog.isShowing() ? dialog : IJ.getInstance());
		} catch (RuntimeException unavailable) {
			return null;
		}
	}

	/** Move the dialog only as far as it takes for the whole of it to be on its screen. */
	private void keepOnScreen(Rectangle screen) {
		Point at = dialog.getLocation();
		int x = Math.max(screen.x, Math.min(at.x, screen.x + screen.width - dialog.getWidth()));
		int y = Math.max(screen.y, Math.min(at.y, screen.y + screen.height - dialog.getHeight()));
		if (x != at.x || y != at.y) dialog.setLocation(x, y);
	}

	/** The section a component sits in, or null when it is in none. */
	Section sectionOf(Component component) {
		for (Component c = component; c != null && c != dialog; c = c.getParent())
			for (Section section : sections)
				if (section.heading == c || section.members.contains(c)) return section;
		return null;
	}

	/** Open or fold a section as a click on its heading does, refit, and remember the result. */
	void toggle(Section section) {
		section.setExpanded(!section.expanded);
		opening = section.expanded ? section : null;
		try {
			dialog.pack();		// the dialog's pack() calls packed(), which refits
		} finally {
			opening = null;
		}
		for (Section each : sections) storeFolded(dialog.getTitle(), each.title, !each.expanded);
	}


	/** One heading and the rows under it. */
	final class Section {
		final JButton heading = new JButton();
		final String title;
		final List<Component> members = new ArrayList<Component>();
		/** The label {@code addSection} added, until the heading takes its place. */
		private Label label;
		/** What each member was showing when the section folded; only while it is folded. */
		private final Map<Component, Boolean> shown = new IdentityHashMap<Component, Boolean>();
		private boolean expanded = true;

		Section(Label label) {
			this.label = label;
			this.title = title(label.getText());
			// the Channel Alignment dialog's fold heading: bold text that folds, not a button
			heading.setFont(label.getFont());
			heading.setHorizontalAlignment(SwingConstants.LEFT);
			heading.setContentAreaFilled(false);
			heading.setBorderPainted(false);
			heading.setFocusPainted(false);
			heading.setOpaque(false);
			heading.setFocusable(false);
			heading.setMargin(new Insets(2, 0, 2, 0));
			heading.setToolTipText("Fold or unfold this section.");
			heading.setText(OPEN + title);
			heading.addActionListener(new ActionListener() {
				@Override public void actionPerformed(ActionEvent e) { toggle(Section.this); }
			});
		}

		/** Put the heading in the label's grid cell, with the label's constraints. */
		void replaceLabel(GridBagLayout layout) {
			if (label == null) return;
			GridBagConstraints at = layout.getConstraints(label);
			int index = 0;
			while (index < dialog.getComponentCount() && dialog.getComponent(index) != label) index++;
			dialog.remove(label);
			dialog.add(heading, at, index);
			label = null;
			/* its colour follows the window's, as the label's did; remembered from the button's
			 * own default, which is what the label had before any theme touched it */
			Debug.rememberHeading(heading);
		}

		boolean isExpanded() {
			return expanded;
		}

		void setExpanded(boolean open) {
			if (open == expanded) return;
			expanded = open;
			if (open) {
				for (Component member : members) {
					Boolean was = shown.get(member);
					if (was != null) member.setVisible(was);
				}
				shown.clear();
			} else {
				for (Component member : members) {
					shown.put(member, member.isVisible());
					member.setVisible(false);
				}
			}
			heading.setText((open ? OPEN : FOLDED) + title);
		}

		/** Fold away a member the dialog has shown since this section folded; true if there was one. */
		boolean refold() {
			if (expanded) return false;
			boolean any = false;
			for (Component member : members)
				if (member.isVisible()) {
					shown.put(member, true);
					member.setVisible(false);
					any = true;
				}
			return any;
		}

		/** How much height the section takes below its heading as laid out now; 0 when folded. */
		int extent() {
			if (!expanded) return 0;
			int top = heading.getY() + heading.getHeight();
			int bottom = top;
			for (Component member : members)
				if (member.isVisible()) bottom = Math.max(bottom, member.getY() + member.getHeight());
			return bottom - top;
		}
	}
}
