package de.embl.iclm;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;

/**
 * The Live Deskew setup, in two depths.
 *
 * <p>A live acquisition is set up under time pressure, usually by someone who wants to change
 * two things: which channels come out and where the results go. The previous single flat
 * dialog put those next to the TCP port, the folder-watch filters and the interpolation
 * kernel - settings that are decided once per microscope, not once per session. Simple mode
 * shows only the first kind; one button reveals the rest, and the values behind it are never
 * discarded. They are hidden, not reset.
 *
 * <p>Fields that only mean something when another box is ticked are disabled rather than
 * silently ignored: an explicit watch folder while the folder comes from the announced path,
 * the manual geometry while it is being read from {@code ExperimentalParameters.txt}, the
 * result folder while results go beside the data, the TIFF layout options while the format is
 * OME-Zarr, which carries its projections inside the dataset instead of in sub-folders.
 *
 * @see Live2 for the listener these settings drive
 */
public class LiveSetupDialog extends JDialog {

	private static final long serialVersionUID = 1L;
	private static final int FIELD_COLUMNS = 28;
	/**
	 * How much of the work area the form may take before it starts scrolling.
	 * <p>
	 * Measured against the screen rather than a fixed 640: advanced mode is a good deal taller
	 * than simple mode, and a constant that fits one monitor truncates the form on another.
	 */
	private static final double MAX_SCREEN_FRACTION = 0.9;

	private final Parameter parameter;
	private final ChannelOperationSettings channels;

	private final JPanel form = new JPanel ( new GridBagLayout() );
	private int gridY = 0;
	/** Rows shown only in advanced mode; an invisible component takes no space in GridBagLayout. */
	private final List<Component[]> advancedRows = new ArrayList<Component[]>();
	/** The sections, each folding its rows away under its heading; see {@link Section}. */
	private final List<Section> sections = new ArrayList<Section>();
	private Section currentSection;

	private boolean advanced;
	private boolean accepted = false;

	// I/O setup
	private final JCheckBox chkListenTcp      = new JCheckBox ( "listen to TCP/IP port" );
	private final JTextField portField        = new JTextField ( 8 );
	private final JCheckBox chkWatchAnnounced = new JCheckBox ( "also watch the folder from the announced file path" );
	private final JCheckBox chkWatchExplicit  = new JCheckBox ( "watch folder" );
	private final JTextField watchDirField    = new JTextField ( FIELD_COLUMNS );
	private final JButton watchDirBrowse      = new JButton ( "Browse..." );
	private final JCheckBox chkRecursive      = new JCheckBox ( "recursively check sub-folders" );
	private final JTextField includeField     = new JTextField ( FIELD_COLUMNS );
	private final JTextField excludeField     = new JTextField ( FIELD_COLUMNS );

	// deskew geometry
	private final JCheckBox chkManual         = new JCheckBox ( "overwrite with manual input" );
	private final JTextField xyField          = new JTextField ( 8 );
	private final JTextField zStepField       = new JTextField ( 8 );
	private final JTextField angleField       = new JTextField ( 8 );

	// channels
	private final JComboBox<String> channelChoice =
			new JComboBox<String> ( Parameter.CHANNEL_OPTIONS );
	private final JTextField alignField       = new JTextField ( FIELD_COLUMNS );
	private final JButton alignBrowse         = new JButton ( "Browse..." );
	private final JCheckBox chkAutoCombine    =
			new JCheckBox ( "automatic combine matching _Channel#### files" );
	private final JCheckBox chkAutoAssign     = new JCheckBox ( "auto channel assignment" );
	private final JCheckBox chkCombine        = new JCheckBox ( "combine matching _Channel#### files" );
	private final JComboBox<String> flipChoice =
			new JComboBox<String> ( ChannelOperationSettings.FLIP_LABELS );
	private final List<JComboBox<String>> slotChoices = new ArrayList<JComboBox<String>>();
	private final List<Component[]> slotRows  = new ArrayList<Component[]>();
	/** One pair per slot; only the last visible row shows its own, see {@link #applyMode}. */
	/* One pair for the list, on a row of its own below it - not one pair per row travelling
	 * down with the last of them. */
	private final JButton slotFewer           = new JButton ( "-" );
	private final JButton slotMore            = new JButton ( "+" );
	private Component[] flipRow;
	private final JComboBox<String> interpolationChoice =
			new JComboBox<String> ( Parameter.INTERPOLATION_OPTIONS );
	private int visibleSlots = 2;

	// projection
	private final JCheckBox chkProjX          = new JCheckBox ( "along X" );
	private final JCheckBox chkProjY          = new JCheckBox ( "along Y" );
	private final JCheckBox chkProjZ          = new JCheckBox ( "along Z" );
	private final JCheckBox chkMax            = new JCheckBox ( "maximum" );
	private final JCheckBox chkAvg            = new JCheckBox ( "mean" );

	// preview, shown in the Output section
	/* Both previews are always virtual, so it is stated in the label rather than asked - the
	 * same wording the batch dialog uses. A preview that follows a run has to read planes as
	 * it needs them; a materialised one loads everything up front and stops following. */
	private final JCheckBox chkPreviewProj    = new JCheckBox ( "show live projection view (virtual)" );
	private final JCheckBox chkPreviewVolume  =
			new JCheckBox ( "show live deskewed volume (virtual)" );

	// output
	private final JTextField saveDirField     = new JTextField ( FIELD_COLUMNS );
	private final JButton saveDirBrowse       = new JButton ( "Browse..." );
	private final JCheckBox chkSaveToSame     = new JCheckBox ( "save result to the same (data) folder" );
	private final JCheckBox chkReproduceTree  = new JCheckBox ( "reproduce input folder structure" );
	private final JComboBox<String> formatChoice =
			new JComboBox<String> ( Parameter.OUTPUT_FORMATS );
	private final JCheckBox chkSaveVolume     = new JCheckBox ( "save deskew volume" );
	private final JCheckBox chkSaveProjections = new JCheckBox ( "save projection views" );
	private final JCheckBox chkSeparate       = new JCheckBox ( "separate results to sub-folders" );
	private final JComboBox<String> existChoice =
			new JComboBox<String> ( new String[] { "skip", "overwrite" } );

	private final JButton modeButton          = new JButton();
	private final JButton helpButton          = new JButton ( "Help" );
	private final JButton okButton            = new JButton ( "OK" );
	private final JButton cancelButton        = new JButton ( "Cancel" );

	/**			Show the setup and write the result back into the shared settings
	 *
	 * @param owner				: the live window, so the dialog stays in front of it
	 * @param parameter			: read for the current values, written on OK
	 * @param channels			: multi-channel selection, shared with Batch and Channel Operation
	 * <p>
	 * @return					: true when the user accepted; nothing is changed on cancel
	 */
	public static boolean show (
			Frame owner,
			Parameter parameter,
			ChannelOperationSettings channels
			) {
		LiveSetupDialog dialog = new LiveSetupDialog ( owner, parameter, channels );
		dialog.setVisible ( true );
		boolean accepted = dialog.accepted;
		dialog.dispose();
		return accepted;
	}

	private LiveSetupDialog (
			Frame owner,
			Parameter parameter,
			ChannelOperationSettings channels
			) {
		super ( owner, "OPM Deskew Live - setup", true );
		this.parameter = parameter;
		this.channels = channels;
		this.advanced = parameter.liveAdvancedMode;

		build();
		load();
		updateEnabledState();
		applyMode();

		setDefaultCloseOperation ( DISPOSE_ON_CLOSE );
		setLocationRelativeTo ( owner );
	}


	// ---- layout ---------------------------------------------------------------------

	private void build () {
		form.setBackground ( Parameter.frameColor );
		form.setBorder ( BorderFactory.createEmptyBorder ( 8, 10, 8, 10 ) );

		/* Every checkbox sits in the field column, left aligned, including the two that used
		 * to act as their row's caption. A checkbox right-aligned in the label column reads as
		 * a ragged edge against the fields under it, which is what made this form look untidy
		 * beside the batch one. */
		header ( true, "Input setup" );
		row ( true, null, flow ( chkListenTcp, label ( "  port" ), portField ) );
		row ( true, null, chkWatchAnnounced );
		row ( true, null, flow ( chkWatchExplicit, watchDirField, watchDirBrowse ) );
		row ( true, null, chkRecursive );
		row ( true, null, label ( "file name include (separate multiple by comma \",\")" ) );
		row ( true, null, includeField );
		row ( true, null, label ( "file name exclude (separate multiple by comma \",\")" ) );
		row ( true, null, excludeField );

		header ( true, "Deskew parameters" );
		row ( true, null, chkManual );
		row ( true, "XY pixel size", flow ( xyField, label ( "nm" ) ) );
		row ( true, "Z step size", flow ( zStepField, label ( "nm" ) ) );
		row ( true, "OPM angle", flow ( angleField, label ( "degree" ) ) );

		header ( false, "Channels" );
		row ( false, "channel option",
				flow ( channelChoice, label ( "   interpolation" ), interpolationChoice ) );
		row ( false, "align matrix", flow ( alignField, alignBrowse ) );
		/* Three rows, in the order the decision is made: whether the names are read at all,
		 * whether the slots are filled from them, and then what is in force - which the run
		 * writes back, so the box below says what this acquisition turned out to be rather
		 * than what was last ticked. */
		row ( true, null, chkAutoCombine );
		row ( true, null, chkAutoAssign );
		row ( true, null, chkCombine );
		flipRow = row ( true, "flip", flipChoice );
		for (int slot = 0; slot < BatchChannelOperation.MAX_OUTPUT_CHANNELS; slot++) {
			JComboBox<String> choice =
					new JComboBox<String> ( ChannelOperationSettings.DESKEW_SOURCE_OPTIONS );
			slotChoices.add ( choice );
			slotRows.add ( row ( true, ChannelOperationSettings.slotLabel ( slot + 1 ),
					choice ) );
		}
		slotFewer.addActionListener ( new ActionListener() {
			@Override public void actionPerformed (ActionEvent e) { showFewerSlots(); }
		} );
		slotMore.addActionListener ( new ActionListener() {
			@Override public void actionPerformed (ActionEvent e) { showMoreSlots(); }
		} );
		/* Advanced only, like the slots it acts on, so the generic advancedRows pass in
		 * applyMode is all the showing and hiding this row needs. */
		row ( true, null, flow ( slotFewer, slotMore ) );

		header ( false, "Projection" );
		row ( false, null, flow ( chkProjX, chkProjY, chkProjZ ) );
		row ( false, null, flow ( chkMax, chkAvg ) );

		/* The preview belongs to the output, not to a section of its own: what it can show
		 * depends entirely on the format chosen two rows above it. */
		header ( false, "Output setup" );
		row ( false, null, chkPreviewProj );
		row ( false, null, chkPreviewVolume );
		row ( false, "save to", flow ( saveDirField, saveDirBrowse ) );
		row ( false, null, chkSaveToSame );
		row ( true, null, chkReproduceTree );
		row ( false, "format", formatChoice );
		row ( false, null, chkSaveVolume );
		row ( false, null, chkSaveProjections );
		row ( false, null, chkSeparate );
		row ( true, "if result exists", existChoice );

		JScrollPane scroll = new JScrollPane ( form );
		scroll.setBorder ( null );
		scroll.getVerticalScrollBar().setUnitIncrement ( 16 );

		JPanel buttons = new JPanel ( new FlowLayout ( FlowLayout.RIGHT, 6, 6 ) );
		buttons.setBackground ( Parameter.frameColor );
		buttons.add ( modeButton );
		buttons.add ( label ( "     " ) );
		buttons.add ( okButton );
		buttons.add ( cancelButton );
		buttons.add ( helpButton );

		JPanel content = new JPanel ( new BorderLayout() );
		content.setBackground ( Parameter.frameColor );
		content.add ( scroll, BorderLayout.CENTER );
		content.add ( buttons, BorderLayout.SOUTH );
		setContentPane ( content );
		Debug.decorate ( this, content );	// before the first pack(); see Debug.decorate

		/* One font for this dialog and for the GenericDialog ones, at whatever ImageJ's GUI
		 * scale is. The look and feel has given every component here a font already, so it has
		 * to be set on each of them; the section headings keep their bold. */
		Parameter.applyFont ( content, Parameter.dialogFont() );

		helpButton.addActionListener ( new ActionListener() {
			@Override public void actionPerformed (ActionEvent e) {
				// the same HTML viewer GenericDialog.addHelp opens, so help reads alike
				new ij.gui.HTMLDialog ( LiveSetupDialog.this, "OPM Live Deskew", Help.live );
			}
		});

		wireListeners();
	}

	/**			A section title spanning both columns, in the face the batch dialogs use
	 * <p>		It is also the section's fold: a click folds the rows under it away, or brings them
	 * <br>		back, as in the Align Channel of OPM Data dialog and every batch dialog.
	 */
	private void header (boolean advancedOnly, String text) {
		final Section section = new Section ( text + ":" );
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = gridY++;
		c.gridwidth = 2;
		c.anchor = GridBagConstraints.WEST;
		c.insets = new Insets ( 10, 0, 2, 4 );
		form.add ( section.title, c );
		if (advancedOnly) advancedRows.add ( new Component[] { section.title } );
		sections.add ( section );
		currentSection = section;
	}

	/**			A heading and the rows under it
	 * <p>		Folding is one more reason for a row to be hidden, applied last in
	 * <br>		{@link #applyMode}, after simple mode and the unused channel slots have had their say:
	 * <br>		an unfolded section shows exactly the rows the mode would have shown, no more. The
	 * <br>		setup opens folded the way it was last left, kept as the batch dialogs keep theirs
	 * <br>		({@link SectionFolds#storedFolded}). It scrolls, so nothing is folded to fit.
	 */
	private final class Section {
		final String text;
		final JButton title = new JButton();
		final List<Component[]> rows = new ArrayList<Component[]>();
		boolean expanded;

		Section (String text) {
			this.text = text;
			this.expanded = !SectionFolds.storedFolded ( getTitle(), text );
			// styled as the Channel Alignment dialog's folds: a bold heading, not a button
			title.setFont ( Parameter.sectionFont() );
			title.setHorizontalAlignment ( SwingConstants.LEFT );
			title.setContentAreaFilled ( false );
			title.setBorderPainted ( false );
			title.setOpaque ( false );
			title.setMargin ( new Insets ( 2, 0, 2, 0 ) );
			title.setToolTipText ( "Fold or unfold this section." );
			title.addActionListener ( new ActionListener() {
				@Override public void actionPerformed (ActionEvent e) {
					expanded = !expanded;
					SectionFolds.storeFolded ( getTitle(), text, !expanded );
					applyMode();
				}
			} );
			label();
		}

		/** The heading's text, with the fold marker in front of it. */
		void label () {
			title.setText ( ( expanded ? SectionFolds.OPEN : SectionFolds.FOLDED ) + text );
		}
	}

	/**			One row of the form
	 *
	 * @param advancedOnly		: hide the row in simple mode
	 * @param label				: null, a caption string, or a checkbox acting as the caption
	 * @param field				: the row's control, or a {@link #flow} of controls
	 * <p>
	 * @return					: every component on the row, so it can be hidden as a unit
	 */
	private Component[] row (boolean advancedOnly, Object label, Component field) {
		Component left = null;
		if (label instanceof Component) left = (Component) label;
		else if (label != null) {
			JLabel caption = label ( String.valueOf ( label ) );
			caption.setHorizontalAlignment ( SwingConstants.RIGHT );
			left = caption;
		}
		int y = gridY++;
		if (left != null) {
			if (left instanceof JCheckBox) ((JCheckBox) left).setOpaque ( false );
			GridBagConstraints c = new GridBagConstraints();
			c.gridx = 0;
			c.gridy = y;
			c.anchor = GridBagConstraints.EAST;
			c.insets = new Insets ( 2, 0, 2, 6 );
			form.add ( left, c );
		}
		if (field instanceof JCheckBox) ((JCheckBox) field).setOpaque ( false );
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 1;
		c.gridy = y;
		c.anchor = GridBagConstraints.WEST;
		c.insets = new Insets ( 2, 0, 2, 0 );
		form.add ( field, c );

		Component[] members = left == null
				? new Component[] { field } : new Component[] { left, field };
		if (advancedOnly) advancedRows.add ( members );
		if (currentSection != null) currentSection.rows.add ( members );
		return members;
	}

	private static void setRowVisible (Component[] row, boolean visible) {
		for (Component member : row) member.setVisible ( visible );
	}

	private static JLabel label (String text) {
		JLabel label = new JLabel ( text );
		label.setOpaque ( false );
		return label;
	}

	private static JPanel flow (Component... members) {
		JPanel panel = new JPanel ( new FlowLayout ( FlowLayout.LEFT, 4, 0 ) );
		panel.setOpaque ( false );
		for (Component member : members) {
			if (member instanceof JCheckBox) ((JCheckBox) member).setOpaque ( false );
			panel.add ( member );
		}
		return panel;
	}


	// ---- behaviour ------------------------------------------------------------------

	private void wireListeners () {
		ActionListener refresh = new ActionListener() {
			@Override
			public void actionPerformed (ActionEvent e) { updateEnabledState(); }
		};
		chkWatchExplicit.addActionListener ( new ActionListener() {
			@Override public void actionPerformed (ActionEvent e) {
				if (chkWatchExplicit.isSelected()) {
					chkListenTcp.setSelected ( false );
					chkWatchAnnounced.setSelected ( false );
				}
				updateEnabledState();
			}
		} );
		chkWatchAnnounced.addActionListener ( refresh );
		chkListenTcp.addActionListener ( new ActionListener() {
			@Override public void actionPerformed (ActionEvent e) {
				if (chkListenTcp.isSelected()) {
					chkWatchExplicit.setSelected ( false );
					chkRecursive.setSelected ( false );
				}
				updateEnabledState();
			}
		} );
		chkManual.addActionListener ( refresh );
		chkSaveToSame.addActionListener ( refresh );
		chkAutoCombine.addActionListener ( refresh );
		chkAutoAssign.addActionListener ( refresh );
		chkCombine.addActionListener ( refresh );
		formatChoice.addActionListener ( refresh );
		chkPreviewProj.addActionListener ( refresh );
		chkPreviewVolume.addActionListener ( refresh );

		watchDirBrowse.addActionListener ( new ActionListener() {
			@Override
			public void actionPerformed (ActionEvent e) { chooseDirectory ( watchDirField, "Folder to watch" ); }
		} );
		saveDirBrowse.addActionListener ( new ActionListener() {
			@Override
			public void actionPerformed (ActionEvent e) { chooseDirectory ( saveDirField, "Result folder" ); }
		} );
		alignBrowse.addActionListener ( new ActionListener() {
			@Override
			public void actionPerformed (ActionEvent e) { chooseFile ( alignField, "Alignment matrix" ); }
		} );

		modeButton.addActionListener ( new ActionListener() {
			@Override
			public void actionPerformed (ActionEvent e) {
				advanced = !advanced;
				applyMode();
			}
		} );
		okButton.addActionListener ( new ActionListener() {
			@Override
			public void actionPerformed (ActionEvent e) {
				if (!store()) return;
				accepted = true;
				setVisible ( false );
			}
		} );
		cancelButton.addActionListener ( new ActionListener() {
			@Override
			public void actionPerformed (ActionEvent e) { setVisible ( false ); }
		} );
	}

	/**			Show or hide the rows, then re-fit the window around what is left
	 * <p>		Every row is shown first and then hidden for each reason it has to be: simple mode,
	 * <br>		a channel slot not in use, a folded section. That order is what lets a section fold
	 * <br>		and unfold without knowing about the other two.
	 */
	private void applyMode () {
		Point previousPosition = getLocation();
		boolean preservePosition = isShowing();
		for (Section section : sections)
			for (Component[] row : section.rows) setRowVisible ( row, true );
		for (Component[] row : advancedRows) setRowVisible ( row, advanced );
		for (int slot = 0; slot < slotRows.size(); slot++)
			setRowVisible ( slotRows.get ( slot ), advanced && slot < visibleSlots );
		for (Section section : sections) {
			section.label();
			if (!section.expanded) for (Component[] row : section.rows) setRowVisible ( row, false );
		}
		modeButton.setText ( advanced ? "simple mode" : "advanced mode" );

		/* Re-pack to the content every time the mode changes, so switching to advanced grows
		 * the window to show what it just revealed instead of hiding it behind a scroll bar. */
		form.revalidate();
		pack();
		Dimension size = getSize();
		int limit = (int) ( ij.gui.GUI.getMaxWindowBounds ( this ).height * MAX_SCREEN_FRACTION );
		if (size.height > limit) setSize ( size.width + 24, limit );
		if (preservePosition) setLocation ( previousPosition );
	}

	/**			Grey out every field whose value would currently be ignored
	 * <p>		A disabled field is honest about what the run will do. An enabled field that is
	 * 			quietly not read is how a live session ends up writing somewhere other than
	 * 			where the dialog appeared to say.
	 */
	private void updateEnabledState () {
		/* Resolve an old persisted state that had both sources selected. TCP wins only for this
		 * migration case; actively selecting the folder checkbox already deselects TCP above. */
		if (chkWatchExplicit.isSelected() && chkListenTcp.isSelected())
			chkWatchExplicit.setSelected ( false );
		boolean explicitFolder = chkWatchExplicit.isSelected();
		boolean tcp = chkListenTcp.isSelected();
		if (!tcp) chkWatchAnnounced.setSelected ( false );
		if (tcp) chkRecursive.setSelected ( false );

		chkListenTcp.setEnabled ( !explicitFolder );
		portField.setEnabled ( tcp );
		chkWatchAnnounced.setEnabled ( tcp );
		chkWatchExplicit.setEnabled ( !tcp );
		watchDirField.setEnabled ( explicitFolder );
		watchDirBrowse.setEnabled ( explicitFolder );
		chkRecursive.setEnabled ( explicitFolder );

		boolean manual = chkManual.isSelected();
		xyField.setEnabled ( manual );
		zStepField.setEnabled ( manual );
		angleField.setEnabled ( manual );

		/* The tick the run reads is chkCombine either way. While the names are being read it
		 * is an indicator: the listener writes its decision into it, so a greyed box is the
		 * answer this acquisition gave rather than a control quietly not read. */
		boolean auto = chkAutoCombine.isSelected();
		boolean assign = auto && chkAutoAssign.isSelected();
		chkAutoAssign.setEnabled ( auto );
		chkCombine.setEnabled ( !auto );
		boolean combine = chkCombine.isSelected();
		boolean manualSlots = combine && !assign;
		setEnabled ( flipRow, manualSlots );
		/* Interpolation stays live whether or not the files are combined: it is how a
		 * transformed pixel is sampled, and the 2-D rigid alignment that needs it happens for
		 * a single file with a left and a right half just as much as for a combined set. */
		for (Component[] slot : slotRows) setEnabled ( slot, manualSlots );
		/* The pair belongs to the list: pointless when the files are not being combined, and
		 * each half is pointless at its own end of the range. Greying is all of it - the row
		 * stays where it is at every length. */
		slotFewer.setEnabled ( manualSlots && visibleSlots > 1 );
		slotMore.setEnabled ( manualSlots && visibleSlots < slotRows.size() );

		boolean explicitSave = !chkSaveToSame.isSelected();
		saveDirField.setEnabled ( explicitSave );
		saveDirBrowse.setEnabled ( explicitSave );
		// the tree is reproduced under the "save to" folder; beside the data there is nothing to reproduce
		chkReproduceTree.setEnabled ( explicitSave );

		// OME-Zarr carries its volume and all six projections inside the dataset; the TIFF
		// layout options describe a directory tree that a Zarr-only run does not produce
		boolean tiff = !Parameter.FORMAT_ZARR.equals ( formatChoice.getSelectedItem() );
		chkSeparate.setEnabled ( tiff );
		chkSaveVolume.setEnabled ( tiff );

		chkSaveProjections.setEnabled ( tiff );
		/* Both previews work for either format now. The OPM Data Viewer reads an OME-Zarr
		 * store plane by plane where there is one, and the deflated TIFF results themselves
		 * where there is not; in both cases the view takes on new time points as they land. */
		chkPreviewVolume.setEnabled ( true );
	}

	private static void setEnabled (Component[] members, boolean enabled) {
		if (members == null) return;
		for (Component member : members) setEnabled ( member, enabled );
	}

	private static void setEnabled (Component member, boolean enabled) {
		member.setEnabled ( enabled );
		if (member instanceof java.awt.Container)
			for (Component child : ((java.awt.Container) member).getComponents())
				setEnabled ( child, enabled );
	}

	/** Reveal the next slot, offering the source that naturally follows the ones shown. */
	private void showMoreSlots () {
		if (visibleSlots >= slotRows.size()) return;
		select ( slotChoices.get ( visibleSlots ),
				ChannelOperationSettings.defaultSourceFor ( visibleSlots ) );
		visibleSlots++;
		updateEnabledState();
		applyMode();
	}

	/**
	 * Drop the last slot.
	 * <p>
	 * Its choice is set to "skip" rather than left as it was, so what is written back matches
	 * the list the user is looking at - a hidden row still feeding an output channel is the
	 * kind of thing that is only noticed in the result.
	 */
	private void showFewerSlots () {
		if (visibleSlots <= 1) return;
		select ( slotChoices.get ( visibleSlots - 1 ), BatchChannelOperation.SKIP_CHANNEL );
		visibleSlots--;
		updateEnabledState();
		applyMode();
	}

	private void chooseDirectory (JTextField target, String title) {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle ( title );
		chooser.setFileSelectionMode ( JFileChooser.DIRECTORIES_ONLY );
		String current = target.getText().trim();
		if (!current.isEmpty()) chooser.setCurrentDirectory ( new File ( current ) );
		if (chooser.showOpenDialog ( this ) == JFileChooser.APPROVE_OPTION)
			target.setText ( chooser.getSelectedFile().getAbsolutePath() );
	}

	private void chooseFile (JTextField target, String title) {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle ( title );
		chooser.setFileSelectionMode ( JFileChooser.FILES_ONLY );
		File start = new File ( target.getText().trim() );
		if (start.isFile()) chooser.setSelectedFile ( start );
		else if (start.isDirectory()) chooser.setCurrentDirectory ( start );
		if (chooser.showOpenDialog ( this ) == JFileChooser.APPROVE_OPTION)
			target.setText ( chooser.getSelectedFile().getAbsolutePath() );
	}


	// ---- values ---------------------------------------------------------------------

	private void load () {
		chkListenTcp.setSelected ( parameter.listenTcpIp );
		portField.setText ( String.valueOf ( parameter.port ) );
		chkWatchAnnounced.setSelected ( parameter.watchAnnouncedFolder );
		chkWatchExplicit.setSelected ( parameter.watchExplicitFolder );
		watchDirField.setText ( parameter.watchDir == null ? "" : parameter.watchDir );
		chkRecursive.setSelected ( parameter.recursive );
		includeField.setText ( parameter.keywords == null ? "" : parameter.keywords );
		excludeField.setText ( parameter.excludeKeywords == null ? "" : parameter.excludeKeywords );

		chkManual.setSelected ( parameter.manualDeskewParameters );
		xyField.setText ( trim ( parameter.xyPixelSize ) );
		zStepField.setText ( trim ( parameter.zStepSize ) );
		angleField.setText ( trim ( parameter.opmAngle ) );

		select ( channelChoice, parameter.channelStr );
		alignField.setText ( parameter.alignmFile == null ? "" : parameter.alignmFile );
		chkAutoCombine.setSelected ( channels.autoCombineChannels );
		chkAutoAssign.setSelected ( channels.autoChannelAssignment );
		chkCombine.setSelected ( channels.combineAcquisitionChannels );
		select ( flipChoice, ChannelOperationSettings.flipLabel ( channels.flipHalf ) );
		visibleSlots = 2;
		for (int slot = 0; slot < slotChoices.size(); slot++) {
			select ( slotChoices.get ( slot ), channels.channelOrder[slot] );
			// never hide a slot that is actually feeding an output channel
			if (!BatchChannelOperation.SKIP_CHANNEL.equals ( channels.channelOrder[slot] ))
				visibleSlots = Math.max ( visibleSlots, slot + 1 );
		}
		select ( interpolationChoice, Parameter.interpolationChoice ( channels.interpolate ) );

		chkProjX.setSelected ( parameter.projX );
		chkProjY.setSelected ( parameter.projY );
		chkProjZ.setSelected ( parameter.projZ );
		chkMax.setSelected ( parameter.maxProj );
		chkAvg.setSelected ( parameter.avgProj );
		chkPreviewProj.setSelected ( parameter.livePreviewProjection );
		chkPreviewVolume.setSelected ( parameter.livePreviewVolume );
		chkSaveProjections.setSelected ( parameter.saveProjectionViews );

		saveDirField.setText ( parameter.saveDir == null ? "" : parameter.saveDir );
		chkSaveToSame.setSelected ( parameter.saveToSame );
		chkReproduceTree.setSelected ( parameter.reproduceInputTree );
		select ( formatChoice, Parameter.isOutputFormat ( parameter.outputFormat )
				? parameter.outputFormat : Parameter.FORMAT_TIFF );
		chkSaveVolume.setSelected ( parameter.saveDeskewImage );
		chkSeparate.setSelected ( parameter.saveSeparate );
		select ( existChoice, parameter.overwriteExist ? "overwrite" : "skip" );
	}

	/**			Copy the dialog back into the shared settings
	 * <p>		Refuses rather than silently correcting the mistakes that would otherwise
	 * 			produce a run that quietly does nothing: nothing watched, no output channel
	 * 			selected while combining, and no result folder.
	 *
	 * @return					: false when the dialog should stay open
	 */
	private boolean store () {
		int port = (int) number ( portField, parameter.port );
		if (chkListenTcp.isSelected() && (port < 1 || port > 65535)) {
			warn ( "The TCP/IP port must be between 1 and 65535." );
			return false;
		}
		parameter.listenTcpIp = chkListenTcp.isSelected();
		parameter.port = port;
		parameter.watchAnnouncedFolder = chkWatchAnnounced.isSelected();
		parameter.watchExplicitFolder = chkWatchExplicit.isSelected();
		parameter.watchDir = watchDirField.getText().trim();
		parameter.recursive = chkRecursive.isSelected();
		parameter.keywords = includeField.getText().trim();
		parameter.excludeKeywords = excludeField.getText().trim();

		if (!parameter.listenTcpIp
				&& (!parameter.watchExplicitFolder || parameter.watchDir.isEmpty())) {
			warn ( "No live input is enabled.\n\nEnable TCP/IP listening or tick \"watch folder\" and choose one." );
			return false;
		}

		parameter.manualDeskewParameters = chkManual.isSelected();
		parameter.xyPixelSize = number ( xyField, parameter.xyPixelSize );
		parameter.zStepSize = number ( zStepField, parameter.zStepSize );
		parameter.opmAngle = number ( angleField, parameter.opmAngle );

		parameter.channelStr = (String) channelChoice.getSelectedItem();
		parameter.alignmFile = alignField.getText().trim();
		channels.autoCombineChannels = chkAutoCombine.isSelected();
		channels.autoChannelAssignment = chkAutoAssign.isSelected();
		channels.combineAcquisitionChannels = chkCombine.isSelected();
		channels.flipHalf = ChannelOperationSettings.flipValue (
				(String) flipChoice.getSelectedItem() );
		for (int slot = 0; slot < slotChoices.size(); slot++)
			channels.channelOrder[slot] = slot < visibleSlots
					? (String) slotChoices.get ( slot ).getSelectedItem()
					: BatchChannelOperation.SKIP_CHANNEL;
		channels.interpolate = Parameter.isBilinear ( (String) interpolationChoice.getSelectedItem() );
		boolean slotsAreTheUsers =
				!(channels.autoCombineChannels && channels.autoChannelAssignment);
		if (channels.combineAcquisitionChannels && slotsAreTheUsers) {
			String problem = channels.selectionProblem();
			if (problem != null) {
				warn ( problem );
				return false;
			}
		}

		parameter.projX = chkProjX.isSelected();
		parameter.projY = chkProjY.isSelected();
		parameter.projZ = chkProjZ.isSelected();
		parameter.maxProj = chkMax.isSelected();
		parameter.avgProj = chkAvg.isSelected();
		/* No longer offered: projections are previewed through the OME-Zarr viewer, and the
		 * ones written to disk are a few Fiji operations away from a time-lapse. */
		parameter.makeTimeLapse = false;
		parameter.saveProjectionViews = chkSaveProjections.isSelected();
		parameter.previewVirtual = true;	// no longer asked; see chkPreviewProj
		parameter.livePreviewProjection = chkPreviewProj.isSelected();
		parameter.livePreviewVolume = chkPreviewVolume.isSelected();
		parameter.livePreview =
				parameter.livePreviewProjection || parameter.livePreviewVolume;

		parameter.saveDir = saveDirField.getText().trim();
		parameter.saveToSame = chkSaveToSame.isSelected();
		parameter.reproduceInputTree = chkReproduceTree.isSelected();
		parameter.outputFormat = (String) formatChoice.getSelectedItem();
		parameter.saveDeskewImage = chkSaveVolume.isSelected();
		parameter.saveSeparate = chkSeparate.isSelected();
		parameter.fileExistStr = (String) existChoice.getSelectedItem();
		parameter.overwriteExist = "overwrite".equals ( parameter.fileExistStr );

		if (!parameter.saveToSame && parameter.saveDir.isEmpty()) {
			warn ( "Choose a result folder, or tick \"save to same (data) folder\"." );
			return false;
		}

		parameter.liveAdvancedMode = advanced;
		return true;
	}

	private void warn (String message) {
		JOptionPane.showMessageDialog ( this, message, "OPM Deskew Live", JOptionPane.WARNING_MESSAGE );
	}

	private static void select (JComboBox<String> box, String value) {
		if (value == null) return;
		for (int i = 0; i < box.getItemCount(); i++)
			if (value.equals ( box.getItemAt ( i ) )) { box.setSelectedIndex ( i ); return; }
	}

	private static double number (JTextField field, double fallback) {
		try {
			return Double.parseDouble ( field.getText().trim() );
		} catch (NumberFormatException notANumber) {
			return fallback;			// keep the previous value rather than zeroing the field
		}
	}

	/** A number without the trailing zeroes a plain toString would show. */
	/** One decimal always shown: 116.0 reads as a measurement, 116 reads as a count. */
	/**
	 * Trailing zeros trimmed, but never past the first decimal.
	 * <p>
	 * A geometry field reads as a measurement when it says 116.0 and as a count when it says
	 * 116, and these three are measurements. 0.2615 keeps its digits; 265 gains one.
	 */
	private static String trim (double value) {
		String text = String.format ( Locale.US, "%.4f", value );
		while (text.endsWith ( "0" ) && !text.endsWith ( ".0" ))
			text = text.substring ( 0, text.length() - 1 );
		return text;
	}
}
