package de.embl.iclm;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.gui.GUI;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij.gui.Overlay;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.gui.Toolbar;
import ij.io.SaveDialog;
import ij.plugin.PlugIn;
import ij.process.FloatPolygon;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import ij.process.LUT;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** File/folder-driven bead channel alignment with hidden, bead-optimised detection settings. */
public class ChannelAlignment implements PlugIn {

	@Override public void run(String arg) {
		Debug.commandStarted ( "Utilities > Channel Alignment" );
		if (java.awt.GraphicsEnvironment.isHeadless()) {
			IJ.error("Channel Alignment", "This command needs the Fiji desktop.");
			return;
		}
		SwingUtilities.invokeLater(new Runnable() {
			@Override public void run() { new Dialog().setVisible(true); }
		});
	}

	static final class FileRow {
		final File file;
		boolean selected;
		FileRow(File file, boolean selected) { this.file = file; this.selected = selected; }
		boolean metadata() { return file.getName().equalsIgnoreCase("ExperimentalParameters.txt"); }
	}

	static final class FilesModel extends AbstractTableModel {
		final List<FileRow> rows = new ArrayList<FileRow>();
		@Override public int getRowCount() { return rows.size(); }
		@Override public int getColumnCount() { return 2; }
		@Override public String getColumnName(int column) { return column == 0 ? "Use" : "File"; }
		@Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
		@Override public boolean isCellEditable(int row, int column) { return column == 0; }
		@Override public Object getValueAt(int row, int column) {
			return column == 0 ? Boolean.valueOf(rows.get(row).selected) : rows.get(row).file.getName();
		}
		@Override public void setValueAt(Object value, int row, int column) {
			if (column == 0) rows.get(row).selected = Boolean.TRUE.equals(value);
			fireTableCellUpdated(row, column);
		}
	}

	static final class SourceChoice {
		final File file;
		final BeadAlignment.Side side;
		final int fallbackAcquisition;
		SourceChoice(File file, BeadAlignment.Side side, int fallbackAcquisition) {
			this.file = file; this.side = side; this.fallbackAcquisition = fallbackAcquisition;
		}
		String key() {
			int acquisition = BatchProcessingUtils.acquisitionChannel(file);
			if (acquisition < 1) acquisition = fallbackAcquisition;
			return side == BeadAlignment.Side.WHOLE
					? ChannelOperationSettings.wholeSourceKey(acquisition)
					: ChannelOperationSettings.sourceKey(acquisition, side == BeadAlignment.Side.LEFT);
		}
		@Override public String toString() {
			return file.getName() + "-" + side.name().toLowerCase(Locale.ROOT);
		}
	}

	static final class Computation {
		final AlignmentMatrixSet matrices;
		final AlignmentMatrixSet computedMatrices;
		final LinkedHashMap<String, ImageProcessor> projections;
		final LinkedHashMap<String, BeadAlignment.Result> results;
		final LinkedHashMap<String, String> issues;
		final List<SourceChoice> sources;
		List<BeadAlignment.Spot> referenceSpots;
		/** After "remove all alignment" the transform before manual adjustment is the identity. */
		boolean alignmentRemoved;
		Computation(AlignmentMatrixSet matrices, AlignmentMatrixSet computedMatrices,
				LinkedHashMap<String, ImageProcessor> projections,
				LinkedHashMap<String, BeadAlignment.Result> results,
				LinkedHashMap<String, String> issues, List<SourceChoice> sources,
				List<BeadAlignment.Spot> referenceSpots) {
			this.matrices = matrices; this.computedMatrices = computedMatrices;
			this.projections = projections; this.results = results;
			this.issues = issues;
			this.sources = new ArrayList<SourceChoice>(sources);
			this.referenceSpots = referenceSpots;
		}
	}

	static final class Dialog extends JDialog {
		private static final long serialVersionUID = 1L;
		private static final int MAX_CHANNELS = BatchChannelOperation.MAX_OUTPUT_CHANNELS;
		private static final String[] COLOR_NAMES = {
			"Red", "Green", "Blue", "Cyan", "Magenta", "Yellow", "Gray"
		};
		/** Interest points in a single-channel window: one colour, because there is one channel. */
		private static final String WINDOW_POINT_COLOR = "Yellow";
		/** Where this dialog's settings live; see {@link #loadSettings}. */
		private static final String PREF = "opm.channelAlign.";
		/**
		 * Bumped whenever the shipped defaults change. A stored set written under a lower
		 * version is discarded rather than merged, so a new default reaches a user who has
		 * used the dialog before; from the next change onwards their own values are kept.
		 */
		private static final int SETTINGS_VERSION = 1;
		private final JTextField path = new JTextField(43);
		private final FilesModel files = new FilesModel();
		private final JTable table = new JTable(files);
		private final JPanel channelRows = new JPanel(new GridBagLayout());
		private final List<JComboBox<SourceChoice>> channelChoices = new ArrayList<JComboBox<SourceChoice>>();
		/** The source and flip each slot was left on, until its own files are back on screen. */
		private final String[] storedSources = new String[MAX_CHANNELS];
		private final boolean[] storedFlips = new boolean[MAX_CHANNELS];
		private int visibleChannels = 2;
		private final JButton fewer = new JButton("-");
		private final JButton more = new JButton("+");
		private final JCheckBox deskew = new JCheckBox("deskew image", true);
		private final JCheckBox createMaxZ = new JCheckBox("create max Z projection", true);
		private final JPanel displayRows = new JPanel(new GridBagLayout());
		private final JPanel detectionRows = new JPanel(new GridBagLayout());
		private final JPanel adjustmentRows = new JPanel(new GridBagLayout());
		private final List<JCheckBox> overlayChannels = new ArrayList<JCheckBox>();
		private final List<JCheckBox> detectionChannels = new ArrayList<JCheckBox>();
		private final List<JCheckBox> labelChannels = new ArrayList<JCheckBox>();
		private final List<JCheckBox> overlayPointChannels = new ArrayList<JCheckBox>();
		private final JPanel overlayPointRows = new JPanel(new GridBagLayout());
		private final List<JCheckBox> flipChannels = new ArrayList<JCheckBox>();
		private final List<JCheckBox> modifyChannels = new ArrayList<JCheckBox>();
		private final List<JComboBox<String>> channelColors = new ArrayList<JComboBox<String>>();
		private final List<JComboBox<String>> pointColors = new ArrayList<JComboBox<String>>();
		private final List<JButton> autoDetectionButtons = new ArrayList<JButton>();
		private final List<JSpinner> translateX = new ArrayList<JSpinner>();
		private final List<JSpinner> translateY = new ArrayList<JSpinner>();
		private final List<JSpinner> rotation = new ArrayList<JSpinner>();
		private final List<JButton> resetAdjustments = new ArrayList<JButton>();
		private boolean settingAdjustments;
		private boolean imageRefreshPending;
		private boolean imageRefreshEveryPlane;
		private final List<MouseAdapter> modifyMouseListeners = new ArrayList<MouseAdapter>();
		private final JButton generate = new JButton("generate/update channel images + overlay");
		private final JCheckBox manualSelection = new JCheckBox("manual draw interest points", false);
		private final JComboBox<String> manualDisplay = new JComboBox<String>(new String[] { "color", "grayscale" });
		private final JButton recompute = new JButton("recompute alignment with interest points");
		private final JButton removeAlignment = new JButton("remove all alignment transform");
		private final JComboBox<String> interpolation = new JComboBox<String>(Parameter.INTERPOLATION_OPTIONS);
		private final JCheckBox manual = new JCheckBox("manual transform adjustment", false);
		private final JButton save = new JButton("save alignment matrix");
		private final JButton ok = new JButton("OK");
		private final JButton cancel = new JButton("Cancel");
		private final JButton help = new JButton("Help");
		private static final double MAX_SCREEN_FRACTION = 0.9;
		private final List<Fold> folds = new ArrayList<Fold>();
		private JScrollPane scroll;
		/** Set once every section exists; until then there is nothing to fit. */
		private boolean built;
		private volatile boolean working;
		private final List<ImagePlus> channelPreviewImages = new ArrayList<ImagePlus>();
		private CompositeImage overlayPreviewImage;
		private Computation previewComputation;
		private boolean previewBilinear;
		private double[][] previewRanges;
		private final List<List<double[]>> manualPointSets = new ArrayList<List<double[]>>();
		private final List<List<double[]>> automaticPointSets = new ArrayList<List<double[]>>();
		private final List<String> manualPointKeys = new ArrayList<String>();
		private int manualPointChannel = -1;
		/** Whether the manual channel's points were on the overlay when it was installed. */
		private boolean manualPointsShown;
		private boolean manualEditing;
		private MouseWheelListener manualWheelListener;
		private MouseAdapter manualMouseListener;
		private int previousTool = -1;
		private boolean previousMultiPoint;
		private int previousOverlayMode = CompositeImage.COMPOSITE;

		Dialog() {
			super((Frame) null, "Align Channel of OPM Data", false);
			setDefaultCloseOperation(DISPOSE_ON_CLOSE);
			JPanel sections = new JPanel();
			sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
			sections.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
			folds.add(new Fold("Beads file", filePanel()));
			folds.add(new Fold("Channel setup", channelPanel()));
			folds.add(new Fold("Display", displayPanel()));
			folds.add(new Fold("Interest point detection", interestPointPanel()));
			folds.add(new Fold("Alignment", alignmentPanel()));
			for (Fold fold : folds) sections.add(fold);
			// At the top of the view: a window taller than the sections leaves space below them, not between.
			JPanel top = new JPanel(new BorderLayout());
			top.add(sections, BorderLayout.NORTH);
			scroll = new JScrollPane(top);
			scroll.setBorder(null);
			scroll.getVerticalScrollBar().setUnitIncrement(16);
			scroll.getHorizontalScrollBar().setUnitIncrement(16);
			scroll.getViewport().setBackground(Parameter.frameColor);
			// OK, Cancel and Help stay below the scrolling sections, always in reach.
			JPanel content = new JPanel(new BorderLayout());
			content.setBackground(Parameter.frameColor);
			content.add(scroll, BorderLayout.CENTER);
			content.add(buttonPanel(), BorderLayout.SOUTH);
			setContentPane(content);
			Debug.decorate(this, content);	// before refit()'s pack(); see Debug.decorate
			// Before the listeners: a restored combo fires an action, and none of them is wanted here.
			loadSettings();
			installActions();
			installDrop(getRootPane());
			installDrop(path);
			setMinimumSize(new Dimension(420, 240));
			built = true;
			refit();
			restoreStoredPath();
			setLocationRelativeTo(null);
			// Start where the work starts, not on the first fold heading.
			addWindowListener(new WindowAdapter() {
				@Override public void windowOpened(WindowEvent e) { path.requestFocusInWindow(); }
			});
		}

		/**
		 * Fit the window to its sections, but no taller than the screen allows: past that they
		 * scroll. Called when a section folds and when the channel rows change; the window keeps
		 * the corner the user put it at, moved only as far as it takes to stay on screen.
		 */
		private void refit() {
			if (!built) return;
			// Rows are added as channels are, so the style is applied to whatever is there now.
			blendIntoFrame(getContentPane());
			Parameter.applyFont(getContentPane(), Parameter.dialogFont());
			Point corner = isShowing() ? getLocation() : null;
			pack();
			Rectangle screen = GUI.getMaxWindowBounds(this);
			Dimension size = getSize();
			int limit = (int) (screen.height * MAX_SCREEN_FRACTION);
			if (size.height > limit) size = new Dimension(
					size.width + scroll.getVerticalScrollBar().getPreferredSize().width, limit);
			size.width = Math.min(size.width, screen.width);
			setSize(size);
			if (corner != null) setLocation(
					Math.max(screen.x, Math.min(corner.x, screen.x + screen.width - size.width)),
					Math.max(screen.y, Math.min(corner.y, screen.y + screen.height - size.height)));
		}

		/** The OPM dialog background everywhere but in the fields: panels and checkboxes let it through. */
		private static void blendIntoFrame(Container root) {
			for (Component child : root.getComponents()) {
				if (child instanceof JTable) continue;	// its checkbox renderer must stay opaque to show selection
				if (child instanceof JPanel || child instanceof JCheckBox) ((JComponent) child).setOpaque(false);
				if (child instanceof Container) blendIntoFrame((Container) child);
			}
		}

		/** A section whose body folds away under its heading, so the dialog fits a small screen. */
		final class Fold extends JPanel {
			private static final long serialVersionUID = 1L;
			private final String title;
			private final JComponent body;
			final JButton heading = new JButton();

			Fold(String title, JComponent body) {
				super(new BorderLayout());
				this.title = title;
				this.body = body;
				setBorder(BorderFactory.createCompoundBorder(
						BorderFactory.createMatteBorder(1, 0, 0, 0, Parameter.frameColor.darker()),
						BorderFactory.createEmptyBorder(2, 0, 6, 0)));
				body.setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 0));
				heading.setFont(Parameter.sectionFont());
				heading.setHorizontalAlignment(SwingConstants.LEFT);
				heading.setContentAreaFilled(false);
				heading.setBorderPainted(false);
				heading.setMargin(new Insets(2, 0, 2, 0));
				heading.setToolTipText("Fold or unfold this section.");
				heading.addActionListener(new ActionListener() {
					@Override public void actionPerformed(ActionEvent e) {
						setExpanded(!isExpanded());
						SectionFolds.storeFolded(getTitle(), Fold.this.title, !isExpanded());
					}
				});
				add(heading, BorderLayout.NORTH);
				add(body, BorderLayout.CENTER);
				// opens folded the way it was last left, as every sectioned dialog in the toolset does
				setExpanded(!SectionFolds.storedFolded(getTitle(), title));
			}

			boolean isExpanded() { return body.isVisible(); }

			void setExpanded(boolean expanded) {
				body.setVisible(expanded);
				// WGL4 triangles, which every desktop font carries.
				heading.setText((expanded ? "▼  " : "►  ") + title);
				refit();
			}
		}

		private JPanel filePanel() {
			JPanel panel = sectionBody();
			JPanel select = new JPanel(new BorderLayout(6, 0));
			JButton browse = new JButton("Browse");
			select.add(path, BorderLayout.CENTER);
			select.add(browse, BorderLayout.EAST);
			panel.add(select, BorderLayout.NORTH);
			table.setFillsViewportHeight(true);
			table.getColumnModel().getColumn(0).setMaxWidth(52);
			JScrollPane scroll = new JScrollPane(table);
			scroll.setPreferredSize(new Dimension(710, 150));
			panel.add(scroll, BorderLayout.CENTER);
			browse.addActionListener(new ActionListener() {
				@Override public void actionPerformed(ActionEvent e) { browse(); }
			});
			path.addActionListener(new ActionListener() {
				@Override public void actionPerformed(ActionEvent e) { loadPath(new File(path.getText().trim())); }
			});
			files.addTableModelListener(new TableModelListener() {
				@Override public void tableChanged(TableModelEvent e) { refreshChannelOptions(); }
			});
			return panel;
		}

		private JPanel channelPanel() {
			JPanel panel = sectionBody();
			panel.add(channelRows, BorderLayout.CENTER);
			for (int i = 0; i < MAX_CHANNELS; i++) channelChoices.add(new JComboBox<SourceChoice>());
			JPanel pair = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
			pair.add(fewer); pair.add(more);
			panel.add(pair, BorderLayout.SOUTH);
			rebuildChannelRows();
			return panel;
		}

		private JPanel displayPanel() {
			JPanel panel = sectionBody();
			JPanel form = new JPanel(new GridBagLayout());
			for (int i = 0; i < MAX_CHANNELS; i++) {
				JCheckBox inOverlay = new JCheckBox(ChannelOperationSettings.slotLabel(i + 1), true);
				inOverlay.setToolTipText("Show this channel in the multichannel overlay; its own window stays open.");
				JCheckBox flip = new JCheckBox("flip", false);
				flip.setToolTipText("Flip this channel horizontally in its own window. A view state: it changes "
						+ "no measured point and no saved matrix. The overlay is drawn in the first channel's "
						+ "frame, so flipping that one mirrors the whole overlay and flipping any other one is "
						+ "carried by its own alignment and leaves the overlay as it was.");
				JComboBox<String> color = colorChoice(i);
				color.setToolTipText("Channel LUT in its own image and the overlay.");
				overlayChannels.add(inOverlay);
				flipChannels.add(flip);
				channelColors.add(color);
			}
			GridBagConstraints c = constraints();
			form.add(deskew, c);
			c.gridx = 1; c.gridwidth = 3; form.add(createMaxZ, c);
			c.gridy++; c.gridx = 0; c.gridwidth = 4; c.fill = GridBagConstraints.NONE;	// rows at their own width, left aligned
			form.add(displayRows, c);
			c.gridy++; c.fill = GridBagConstraints.NONE;
			form.add(generate, c);
			panel.add(form, BorderLayout.WEST);
			rebuildSetupRows();
			return panel;
		}

		private JPanel interestPointPanel() {
			JPanel panel = sectionBody();
			JPanel form = new JPanel(new GridBagLayout());
			for (int i = 0; i < MAX_CHANNELS; i++) {
				JCheckBox detections = new JCheckBox(ChannelOperationSettings.slotLabel(i + 1), true);
				detections.setToolTipText("Show or hide this channel's interest points in its own image window.");
				/* One window shows one channel, so a marker there needs no channel colour: the
				 * same yellow everywhere reads against every LUT. In the overlay the channel is
				 * exactly what a marker has to say, so there it takes the channel's own colour. */
				JComboBox<String> color = colorChoice(WINDOW_POINT_COLOR);
				color.setToolTipText("Interest-point ROI color in this channel's own image window; "
						+ "in the overlay the points take the channel's display color.");
				JCheckBox label = new JCheckBox("label", false);
				label.setToolTipText("Number this channel's interest points, in its own window and in the overlay.");
				JCheckBox inOverlay = new JCheckBox(ChannelOperationSettings.slotLabel(i + 1), false);
				inOverlay.setToolTipText("Show or hide this channel's interest points in the multichannel overlay.");
				labelChannels.add(label);
				overlayPointChannels.add(inOverlay);
				JButton automatic = new JButton("auto detection");
				automatic.setToolTipText("Restore automatically detected interest points for this channel.");
				JCheckBox modify = new JCheckBox("modify", false);
				modify.setToolTipText("Make the multipoint ROI editable in this channel's own image window.");
				detectionChannels.add(detections);
				pointColors.add(color);
				autoDetectionButtons.add(automatic);
				modifyChannels.add(modify);
				modifyMouseListeners.add(null);
				manualPointSets.add(new ArrayList<double[]>());
				automaticPointSets.add(new ArrayList<double[]>());
			}
			GridBagConstraints c = constraints();
			c.gridwidth = 4; c.fill = GridBagConstraints.NONE;	// rows at their own width, left aligned
			form.add(detectionRows, c);
			c.gridy++;
			form.add(overlayPointRows, c);
			c.gridy++; c.gridwidth = 1; c.fill = GridBagConstraints.NONE;
			form.add(manualSelection, c);
			c.gridx = 1; form.add(new JLabel("overlay mode"), c);
			c.gridx = 2; c.gridwidth = 2; c.fill = GridBagConstraints.HORIZONTAL; form.add(manualDisplay, c);
			c.gridy++; c.gridx = 0; c.gridwidth = 4; c.fill = GridBagConstraints.NONE;
			form.add(new JLabel("Use the multipoint tool: wheel changes channel; drag points to refine; 3+ matching labels required."), c);
			panel.add(form, BorderLayout.WEST);
			rebuildSetupRows();
			manualDisplay.setEnabled(false);
			return panel;
		}

		private JPanel alignmentPanel() {
			JPanel panel = sectionBody();
			JPanel form = new JPanel(new GridBagLayout());
			for (int i = 0; i < MAX_CHANNELS; i++) {
				translateX.add(numberSpinner());
				translateY.add(numberSpinner());
				rotation.add(numberSpinner());
				resetAdjustments.add(new JButton("Reset"));
				if (i == 0) {
					resetAdjustments.get(i).setToolTipText("Zero this row: every other channel returns to its transform before manual adjustment.");
				} else {
					resetAdjustments.get(i).setToolTipText("Zero this row: the channel returns to its transform before manual adjustment.");
				}
			}
			GridBagConstraints c = constraints();
			c.gridwidth = 1; form.add(recompute, c);
			c.gridx = 1; c.gridwidth = 8; form.add(removeAlignment, c);
			c.gridy++; c.gridx = 0; c.gridwidth = 1; form.add(new JLabel("interpolation"), c);
			c.gridx = 1; c.gridwidth = 8; c.fill = GridBagConstraints.HORIZONTAL; form.add(interpolation, c);
			c.gridy++; c.gridx = 0; c.gridwidth = 9; c.fill = GridBagConstraints.NONE; form.add(manual, c);
			c.gridy++; form.add(adjustmentRows, c);	// at its own width, left aligned
			c.gridy++; c.fill = GridBagConstraints.NONE;
			form.add(new JLabel("Positive: X right, Y down, rotation counter-clockwise about the image centre."), c);
			c.gridy++; form.add(save, c);
			panel.add(form, BorderLayout.WEST);
			rebuildAdjustmentRows();
			setManualEnabled(false);
			return panel;
		}

		private JPanel buttonPanel() {
			JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
			panel.add(ok); panel.add(cancel); panel.add(help);
			return panel;
		}

		private void installActions() {
			fewer.addActionListener(new ActionListener() {
				@Override public void actionPerformed(ActionEvent e) {
					visibleChannels = Math.max(2, visibleChannels - 1); rebuildChannelRows();
				}
			});
			more.addActionListener(new ActionListener() {
				@Override public void actionPerformed(ActionEvent e) {
					visibleChannels = Math.min(MAX_CHANNELS, visibleChannels + 1); rebuildChannelRows();
				}
			});
			ChangeListener adjusted = new ChangeListener() {
				@Override public void stateChanged(ChangeEvent e) { scheduleImageTransformRefresh(false); }
			};
			for (int i = 0; i < MAX_CHANNELS; i++) {
				final int channel = i;
				translateX.get(i).addChangeListener(adjusted);
				translateY.get(i).addChangeListener(adjusted);
				rotation.get(i).addChangeListener(adjusted);
				resetAdjustments.get(i).addActionListener(new ActionListener() {
					@Override public void actionPerformed(ActionEvent e) { resetManualAdjustment(channel); }
				});
				overlayChannels.get(i).addActionListener(new ActionListener() {
					@Override public void actionPerformed(ActionEvent e) { updatePreviewOverlay(); }
				});
				detectionChannels.get(i).addActionListener(new ActionListener() {
					@Override public void actionPerformed(ActionEvent e) {
						captureModifiedChannelPoints(channel);
						updatePreviewDetections();
					}
				});
				overlayPointChannels.get(i).addActionListener(new ActionListener() {
					@Override public void actionPerformed(ActionEvent e) { showOverlayPoints(channel); }
				});
				labelChannels.get(i).addActionListener(new ActionListener() {
					@Override public void actionPerformed(ActionEvent e) { restylePoints(channel); }
				});
				flipChannels.get(i).addActionListener(new ActionListener() {
					@Override public void actionPerformed(ActionEvent e) { flipChanged(channel); }
				});
				channelColors.get(i).addActionListener(new ActionListener() {
					@Override public void actionPerformed(ActionEvent e) {
						applyChannelColors();
						restylePoints(channel);	// the overlay's markers wear the channel's colour
					}
				});
				pointColors.get(i).addActionListener(new ActionListener() {
					@Override public void actionPerformed(ActionEvent e) { restylePoints(channel); }
				});
				autoDetectionButtons.get(i).addActionListener(new ActionListener() {
					@Override public void actionPerformed(ActionEvent e) { restoreAutomaticPoints(channel); }
				});
				modifyChannels.get(i).addActionListener(new ActionListener() {
					@Override public void actionPerformed(ActionEvent e) {
						setChannelModification(channel, modifyChannels.get(channel).isSelected());
					}
				});
				channelChoices.get(i).addActionListener(new ActionListener() {
					@Override public void actionPerformed(ActionEvent e) {
						SourceChoice source = (SourceChoice) channelChoices.get(channel).getSelectedItem();
						if (source != null) flipChannels.get(channel).setSelected(defaultFlip(source.side));
					}
				});
			}
			manual.addActionListener(new ActionListener() {
				@Override public void actionPerformed(ActionEvent e) {
					updateManualEnabled();
					scheduleImageTransformRefresh(false);
				}
			});
			interpolation.addActionListener(new ActionListener() {
				@Override public void actionPerformed(ActionEvent e) {
					if (previewComputation == null) return;
					previewBilinear = Parameter.isBilinear((String) interpolation.getSelectedItem());
					scheduleImageTransformRefresh(true);
				}
			});
			manualSelection.addActionListener(new ActionListener() {
				@Override public void actionPerformed(ActionEvent e) { toggleManualSelection(); }
			});
			manualDisplay.addActionListener(new ActionListener() {
				@Override public void actionPerformed(ActionEvent e) { applyManualDisplayMode(); }
			});
			recompute.addActionListener(new ActionListener() {
				@Override public void actionPerformed(ActionEvent e) { recomputeFromManualPoints(); }
			});
			removeAlignment.addActionListener(new ActionListener() {
				@Override public void actionPerformed(ActionEvent e) { removeAllAlignment(); }
			});
			generate.addActionListener(new ActionListener() {
				@Override public void actionPerformed(ActionEvent e) { start(false, false, true); }
			});
			save.addActionListener(new ActionListener() {
				@Override public void actionPerformed(ActionEvent e) {
					captureAllModifiedChannelPoints();
					if (previewComputation != null) {
						storeActiveManualPoints();
						// A spinner committed by this click's focus change may not have redrawn yet.
						refreshImageTransforms(false);
						chooseSave(previewComputation.matrices);
					} else start(false, true, false);
				}
			});
			ok.addActionListener(new ActionListener() {
				@Override public void actionPerformed(ActionEvent e) { start(true, false, false); }
			});
			cancel.addActionListener(new ActionListener() {
				@Override public void actionPerformed(ActionEvent e) { closePreview(); dispose(); }
			});
			help.addActionListener(new ActionListener() {
				@Override public void actionPerformed(ActionEvent e) { showHelp(); }
			});
		}

		// ---- persistence ----------------------------------------------------------------

		/**
		 * Restore what the user left this dialog set to.
		 * <p>
		 * Everything but the per-channel manual X/Y/rotation rows survives between sessions,
		 * under {@link #PREF} keys. Those rows are measured against one acquisition's overlay,
		 * so carrying them to the next one would apply somebody else's nudge unseen.
		 * <p>
		 * A stored set written under an older {@link #SETTINGS_VERSION} is ignored outright
		 * rather than merged field by field, so a changed default reaches a user who has used
		 * the dialog before. Their next change is stored under the current version and is then
		 * kept through every later session.
		 * <p>
		 * Called before the listeners are installed: restoring a combo fires an action event
		 * whether or not the item changes, and none of those actions is wanted here.
		 */
		private void loadSettings() {
			if ((int) Prefs.get(PREF + "settingsVersion", 0) < SETTINGS_VERSION) return;
			path.setText(Prefs.get(PREF + "path", ""));
			visibleChannels = Math.max(2, Math.min(MAX_CHANNELS,
					(int) Prefs.get(PREF + "channels", visibleChannels)));
			deskew.setSelected(Prefs.get(PREF + "deskew", deskew.isSelected()));
			createMaxZ.setSelected(Prefs.get(PREF + "maxZ", createMaxZ.isSelected()));
			selectOption(interpolation, Prefs.get(PREF + "interpolation", null));
			selectOption(manualDisplay, Prefs.get(PREF + "manualDisplay", null));
			manual.setSelected(Prefs.get(PREF + "manualAdjust", manual.isSelected()));
			for (int i = 0; i < MAX_CHANNELS; i++) {
				String slot = PREF + "slot" + (i + 1) + ".";
				String source = Prefs.get(slot + "source", "");
				storedSources[i] = source.isEmpty() ? null : source;
				storedFlips[i] = Prefs.get(slot + "flip", defaultFlip(BeadAlignment.Side.LEFT));
				overlayChannels.get(i).setSelected(Prefs.get(slot + "inOverlay",
						overlayChannels.get(i).isSelected()));
				selectOption(channelColors.get(i), Prefs.get(slot + "lut", null));
				detectionChannels.get(i).setSelected(Prefs.get(slot + "points",
						detectionChannels.get(i).isSelected()));
				selectOption(pointColors.get(i), Prefs.get(slot + "pointColor", null));
				labelChannels.get(i).setSelected(Prefs.get(slot + "label",
						labelChannels.get(i).isSelected()));
				overlayPointChannels.get(i).setSelected(Prefs.get(slot + "overlayPoints",
						overlayPointChannels.get(i).isSelected()));
			}
			rebuildChannelRows();	// the restored channel count, through its own rebuild rules
		}

		/** Write the current settings back; called whenever the dialog is disposed or starts work. */
		private void storeSettings() {
			Prefs.set(PREF + "settingsVersion", SETTINGS_VERSION);
			Prefs.set(PREF + "path", path.getText().trim());
			Prefs.set(PREF + "channels", visibleChannels);
			Prefs.set(PREF + "deskew", deskew.isSelected());
			Prefs.set(PREF + "maxZ", createMaxZ.isSelected());
			Prefs.set(PREF + "interpolation", (String) interpolation.getSelectedItem());
			Prefs.set(PREF + "manualDisplay", (String) manualDisplay.getSelectedItem());
			Prefs.set(PREF + "manualAdjust", manual.isSelected());
			for (int i = 0; i < MAX_CHANNELS; i++) {
				String slot = PREF + "slot" + (i + 1) + ".";
				Object source = channelChoices.get(i).getSelectedItem();
				Prefs.set(slot + "source", source == null ? "" : source.toString());
				Prefs.set(slot + "flip", flipChannels.get(i).isSelected());
				Prefs.set(slot + "inOverlay", overlayChannels.get(i).isSelected());
				Prefs.set(slot + "lut", (String) channelColors.get(i).getSelectedItem());
				Prefs.set(slot + "points", detectionChannels.get(i).isSelected());
				Prefs.set(slot + "pointColor", (String) pointColors.get(i).getSelectedItem());
				Prefs.set(slot + "label", labelChannels.get(i).isSelected());
				Prefs.set(slot + "overlayPoints", overlayPointChannels.get(i).isSelected());
			}
		}

		/**
		 * Reopen the folder the dialog was last pointed at, and put the flips back on the slots
		 * that found their source again.
		 * <p>
		 * The flip follows the selected side - a right half arrives mirrored - so choosing a
		 * source sets it, which would undo a restored value. Restoring it here, after the files
		 * are back, keeps both rules: a slot that recovered its own source keeps what the user
		 * left it at, and a slot that landed on a different source takes that side's default.
		 * Nothing here is worth a warning dialog: an acquisition folder that has been moved or
		 * unmounted since the last session is ordinary, and the dialog opens on it empty.
		 */
		private void restoreStoredPath() {
			String stored = path.getText().trim();
			if (stored.isEmpty()) return;
			try {
				File file = new File(stored);
				if (!file.exists()) return;
				loadPath(file, false, true);
			} catch (Exception unreadable) {
				IJ.showStatus("Channel Alignment: could not reopen " + stored);
				return;
			}
			for (int i = 0; i < channelChoices.size(); i++) {
				Object chosen = channelChoices.get(i).getSelectedItem();
				if (chosen != null && chosen.toString().equals(storedSources[i]))
					flipChannels.get(i).setSelected(storedFlips[i]);
			}
		}

		/** Select {@code name} if the box offers it; leave the box alone if it does not. */
		private static void selectOption(JComboBox<String> box, String name) {
			if (name == null || name.isEmpty()) return;
			for (int i = 0; i < box.getItemCount(); i++)
				if (name.equals(box.getItemAt(i))) { box.setSelectedIndex(i); return; }
		}

		@Override public void dispose() {
			storeSettings();
			super.dispose();
		}

		private void browse() {
			JFileChooser chooser = new JFileChooser(path.getText().trim().isEmpty() ? null : new File(path.getText().trim()));
			chooser.setDialogTitle("Select a bead TIFF or its folder");
			chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
			chooser.setFileFilter(new FileNameExtensionFilter("TIFF image or folder", "tif", "tiff"));
			if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) loadPath(chooser.getSelectedFile());
		}

		private void loadPath(File selected) {
			loadPath(selected, true, false);
		}

		/**
		 * @param guessPreprocessing	: set deskew and max Z from what the files contain, which is
		 * 								  right for a folder the user has just chosen and wrong for
		 * 								  the one they left the dialog on with their own choices
		 * @param quiet					: report nothing; the dialog simply opens empty
		 */
		private void loadPath(File selected, boolean guessPreprocessing, boolean quiet) {
			if (selected == null || !selected.exists()) {
				if (!quiet) warn("The selected file or folder does not exist.");
				return;
			}
			File folder = selected.isDirectory() ? selected : selected.getParentFile();
			List<File> tiffs = selected.isDirectory()
					? BatchProcessingUtils.listTiffs(selected, "", false)
					: Collections.singletonList(selected);
			if (tiffs.isEmpty() || (!selected.isDirectory() && !isTiff(selected))) {
				if (!quiet) warn("No .tif or .tiff image was found.");
				return;
			}
			files.rows.clear();
			File metadata = new File(folder, "ExperimentalParameters.txt");
			if (metadata.isFile()) files.rows.add(new FileRow(metadata, true));
			if (guessPreprocessing) {
				boolean volume = false;
				for (File tiff : tiffs) {
					try { if (FastTiffReader.parse(tiff).depth() > 1) { volume = true; break; } }
					catch (Exception ignored) { volume = true; break; }
				}
				deskew.setSelected(metadata.isFile() && volume);
				createMaxZ.setSelected(volume);
			}
			for (File tiff : tiffs) files.rows.add(new FileRow(tiff, true));
			path.setText(selected.getAbsolutePath());
			files.fireTableDataChanged();
			refreshChannelOptions();
		}

		private void refreshChannelOptions() {
			List<SourceChoice> options = sourceOptions();
			for (int i = 0; i < channelChoices.size(); i++) {
				JComboBox<SourceChoice> box = channelChoices.get(i);
				// Nothing chosen yet: the slot returns to the source the last session left on it.
				String previous = box.getSelectedItem() == null
						? storedSources[i] : box.getSelectedItem().toString();
				box.removeAllItems();
				for (SourceChoice option : options) box.addItem(option);
				/* Defaults follow the shared channel layout: file 1 left/right, file 2
				 * left/right, ... .  Whole remains available but does not unexpectedly occupy
				 * the third slot merely because it is the third item in each file's menu. */
				int preferred = Math.min((i / 2) * 3 + (i % 2), Math.max(0, options.size() - 1));
				for (int j = 0; previous != null && j < box.getItemCount(); j++)
					if (previous.equals(box.getItemAt(j).toString())) preferred = j;
				if (box.getItemCount() > 0) box.setSelectedIndex(preferred);
			}
			rebuildAdjustmentRows();
		}

		private List<SourceChoice> sourceOptions() {
			List<SourceChoice> result = new ArrayList<SourceChoice>();
			int fallback = 0;
			for (FileRow row : files.rows) {
				if (!row.selected || row.metadata()) continue;
				fallback++;
				result.add(new SourceChoice(row.file, BeadAlignment.Side.LEFT, fallback));
				result.add(new SourceChoice(row.file, BeadAlignment.Side.RIGHT, fallback));
				result.add(new SourceChoice(row.file, BeadAlignment.Side.WHOLE, fallback));
			}
			return result;
		}

		private void rebuildChannelRows() {
			channelRows.removeAll();
			GridBagConstraints c = constraints();
			c.fill = GridBagConstraints.HORIZONTAL;
			c.weightx = 0; c.gridx = 0;
			for (int i = 0; i < visibleChannels; i++) {
				c.gridy = i; c.gridx = 0; c.weightx = 0;
				channelRows.add(new JLabel(ChannelOperationSettings.slotLabel(i + 1)), c);
				c.gridx = 1; c.weightx = 1;
				channelRows.add(channelChoices.get(i), c);
			}
			fewer.setEnabled(visibleChannels > 2);
			more.setEnabled(visibleChannels < MAX_CHANNELS);
			channelRows.revalidate(); channelRows.repaint(); refit();
			if (!overlayChannels.isEmpty()) rebuildSetupRows();
			rebuildAdjustmentRows();
		}

		private void rebuildSetupRows() {
			if (!detectionChannels.isEmpty()) {
				detectionRows.removeAll();
				GridBagConstraints c = constraints();
				for (int i = 0; i < visibleChannels; i++) {
					c.gridy = i; c.gridx = 0; c.weightx = 0; c.fill = GridBagConstraints.NONE;
					detectionRows.add(detectionChannels.get(i), c);
					c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL;
					detectionRows.add(pointColors.get(i), c);
					c.gridx = 2; c.fill = GridBagConstraints.NONE;
					detectionRows.add(labelChannels.get(i), c);
					c.gridx = 3;
					detectionRows.add(autoDetectionButtons.get(i), c);
					c.gridx = 4;
					detectionRows.add(modifyChannels.get(i), c);
				}
				detectionRows.revalidate();
				detectionRows.repaint();
				overlayPointRows.removeAll();
				c = constraints();
				overlayPointRows.add(new JLabel("display interest points in overlay"), c);
				for (int i = 0; i < visibleChannels; i++) {
					// Four to a line, so eight channels do not widen the dialog.
					c.gridy = i / 4; c.gridx = 1 + i % 4;
					overlayPointRows.add(overlayPointChannels.get(i), c);
				}
				overlayPointRows.revalidate();
				overlayPointRows.repaint();
			}
			if (!overlayChannels.isEmpty()) {
				displayRows.removeAll();
				GridBagConstraints c = constraints();
				for (int i = 0; i < visibleChannels; i++) {
					c.gridy = i; c.gridx = 0; c.weightx = 0; c.fill = GridBagConstraints.NONE;
					displayRows.add(overlayChannels.get(i), c);
					c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL;
					displayRows.add(channelColors.get(i), c);
					c.gridx = 2; c.fill = GridBagConstraints.NONE;
					displayRows.add(flipChannels.get(i), c);
				}
				displayRows.revalidate();
				displayRows.repaint();
			}
			refit();
		}

		private void rebuildAdjustmentRows() {
			if (translateX.isEmpty()) return;
			adjustmentRows.removeAll();
			GridBagConstraints c = constraints();
			for (int i = 0; i < visibleChannels; i++) {
				c.gridy = i; c.gridx = 0; c.fill = GridBagConstraints.NONE;
				adjustmentRows.add(new JLabel(ChannelOperationSettings.slotLabel(i + 1) + ": X"), c);
				c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; adjustmentRows.add(translateX.get(i), c);
				c.gridx = 2; c.fill = GridBagConstraints.NONE; adjustmentRows.add(new JLabel("px   Y"), c);
				c.gridx = 3; c.fill = GridBagConstraints.HORIZONTAL; adjustmentRows.add(translateY.get(i), c);
				c.gridx = 4; c.fill = GridBagConstraints.NONE; adjustmentRows.add(new JLabel("px   rotation"), c);
				c.gridx = 5; c.fill = GridBagConstraints.HORIZONTAL; adjustmentRows.add(rotation.get(i), c);
				c.gridx = 6; c.fill = GridBagConstraints.NONE; adjustmentRows.add(new JLabel("\u00b0"), c);
				c.gridx = 7; adjustmentRows.add(resetAdjustments.get(i), c);
			}
			adjustmentRows.revalidate();
			adjustmentRows.repaint();
			updateManualEnabled();
			refit();
		}

		private List<SourceChoice> selectedSources() {
			List<SourceChoice> selected = new ArrayList<SourceChoice>();
			for (int i = 0; i < visibleChannels; i++) {
				SourceChoice choice = (SourceChoice) channelChoices.get(i).getSelectedItem();
				if (choice != null) selected.add(choice);
			}
			return selected;
		}

		private File selectedMetadata() {
			for (FileRow row : files.rows) if (row.selected && row.metadata()) return row.file;
			return null;
		}

		private void start(final boolean closeAfter, final boolean saveAfter, final boolean displayAfter) {
			if (working) return;
			storeSettings();	// what was asked for, kept whether or not the run then succeeds
			final List<SourceChoice> sources = selectedSources();
			if (sources.size() < 2) { warn("Select at least two channel sources."); return; }
			java.util.HashSet<String> unique = new java.util.HashSet<String>();
			for (SourceChoice source : sources) if (!unique.add(source.key())) {
				warn("Each channel source can only be selected once."); return;
			}
			final File metadata = selectedMetadata();
			if (deskew.isSelected() && metadata == null) {
				warn("Select ExperimentalParameters.txt to deskew the raw bead volume."); return;
			}
			final boolean doDeskew = deskew.isSelected();
			final boolean doMaxZ = createMaxZ.isSelected();
			final boolean bilinear = Parameter.isBilinear((String) interpolation.getSelectedItem());
			final boolean useManual = manual.isSelected();
			final Map<String, double[]> manualValues = selectedManualValues(sources);
			working = true; setControlsEnabled(false); IJ.showStatus("Preparing bead projections...");
			new SwingWorker<Computation, Void>() {
				@Override protected Computation doInBackground() throws Exception {
					return compute(sources, metadata, doDeskew, doMaxZ, bilinear, useManual, manualValues);
				}
				@Override protected void done() {
					working = false; setControlsEnabled(true);
					try {
						Computation computation = get();
						report(computation);
						if (displayAfter) showPreview(computation, bilinear);
						if (saveAfter) chooseSave(computation.matrices);
						if (closeAfter) dispose();
					} catch (Throwable error) {
						Throwable cause = error.getCause() == null ? error : error.getCause();
						warn("Alignment failed:\n" + cause.getMessage());
						cause.printStackTrace();
					} finally { IJ.showStatus(""); }
				}
			}.execute();
		}

		private Computation compute(List<SourceChoice> sources, File metadata, boolean doDeskew,
				boolean doMaxZ, boolean bilinear, boolean useManual,
				Map<String, double[]> manualValues) throws Exception {
			double xy = 1, step = 0, angle = 0;
			if (doDeskew) {
				double[] values = IO.loadExperimentalParametersFromFile(metadata.getAbsolutePath());
				if (values == null) throw new IllegalArgumentException("Could not read " + metadata.getName());
				xy = values[0]; step = values[1]; angle = values[2];
			}
			LinkedHashMap<File, EnumSet<BeadAlignment.Side>> requested = new LinkedHashMap<File, EnumSet<BeadAlignment.Side>>();
			for (SourceChoice source : sources) {
				EnumSet<BeadAlignment.Side> sides = requested.get(source.file);
				if (sides == null) { sides = EnumSet.noneOf(BeadAlignment.Side.class); requested.put(source.file, sides); }
				sides.add(source.side);
			}
			Map<File, Map<BeadAlignment.Side, ImageProcessor>> byFile = new LinkedHashMap<File, Map<BeadAlignment.Side, ImageProcessor>>();
			for (Map.Entry<File, EnumSet<BeadAlignment.Side>> entry : requested.entrySet()) {
				if (!doMaxZ) {
					int depth = FastTiffReader.parse(entry.getKey()).depth();
					if (depth > 1) throw new IllegalArgumentException(entry.getKey().getName()
							+ " contains " + depth + " planes. Enable create max Z projection for a volume.");
				}
				IJ.showStatus("Projecting " + entry.getKey().getName());
				byFile.put(entry.getKey(), BeadAlignment.deskewedMax(entry.getKey(), entry.getValue(), xy, step, angle));
			}
			LinkedHashMap<String, ImageProcessor> projections = new LinkedHashMap<String, ImageProcessor>();
			for (SourceChoice source : sources) projections.put(source.key(), byFile.get(source.file).get(source.side));
			ImageProcessor reference = projections.get(sources.get(0).key());
			List<BeadAlignment.Spot> referenceSpots = BeadAlignment.detect(reference,
					BeadAlignment.recommendedSigma(reference));
			AlignmentMatrixSet set = new AlignmentMatrixSet(sources.get(0).key());
			AlignmentMatrixSet computed = new AlignmentMatrixSet(sources.get(0).key());
			LinkedHashMap<String, BeadAlignment.Result> results = new LinkedHashMap<String, BeadAlignment.Result>();
			LinkedHashMap<String, String> issues = new LinkedHashMap<String, String>();
			for (int i = 1; i < sources.size(); i++) {
				SourceChoice source = sources.get(i);
				ImageProcessor moving = projections.get(source.key());
				if (moving.getWidth() != reference.getWidth() || moving.getHeight() != reference.getHeight())
					throw new IllegalArgumentException("'whole' and half-width sources cannot be aligned together.");
				IJ.showStatus("Detecting interest points in " + source);
				BeadAlignment.Result result = BeadAlignment.align(reference, moving);
				double[][] matrix;
				if (result == null) {
					matrix = identity2d();
					issues.put(source.key(), "no reliable interest-point pairs; identity retained");
				} else matrix = result.matrix;
				computed.put(source.key(), matrix);
				set.put(source.key(), matrix);
				if (result != null) results.put(source.key(), new BeadAlignment.Result(matrix, result.referenceSpots,
						result.movingSpots, result.matches, result.rmsPixels, result.method));
			}
			if (useManual) {
				double[] referenceValues = manualValues.get(sources.get(0).key());
				for (int i = 1; i < sources.size(); i++) {
					SourceChoice source = sources.get(i);
					ImageProcessor moving = projections.get(source.key());
					set.put(source.key(), reframedManualAdjustment(
							computed.matrixFor(source.key()), referenceValues,
							manualValues.get(source.key()), moving.getWidth(), moving.getHeight()));
				}
			}
			return new Computation(set, computed, projections, results, issues, sources, referenceSpots);
		}

		private void showPreview(Computation computation, boolean bilinear) {
			closePreview();
			previewComputation = computation;
			previewBilinear = bilinear;
			prepareManualPointSets(computation);
			previewRanges = previewDisplayRanges(computation.sources, computation.projections);
			ImageProcessor reference = computation.projections.get(computation.matrices.reference());
			ImageStack stack = new ImageStack(reference.getWidth(), reference.getHeight());
			for (int channel = 0; channel < computation.sources.size(); channel++) {
				SourceChoice source = computation.sources.get(channel);
				ImagePlus individual = new ImagePlus(previewTitle(source), displayPlane(channel, false));
				channelPreviewImages.add(individual);
				individual.show();
				applyDisplayRange(individual, previewRanges[channel]);
				stack.addSlice(source.key(), displayPlane(channel, true));
			}
			ImagePlus base = new ImagePlus("beads-alignment-preview", stack);
			base.setDimensions(stack.size(), 1, 1); base.setOpenAsHyperStack(true);
			overlayPreviewImage = new CompositeImage(base, CompositeImage.COMPOSITE);
			overlayPreviewImage.setTitle("multichannel overlay");
			overlayPreviewImage.show();
			applyChannelColors();
			applyOverlayDisplayRanges();
			updatePreviewOverlay();
			updatePreviewDetections();
			for (int channel = 0; channel < computation.sources.size(); channel++)
				if (modifyChannels.get(channel).isSelected()) setChannelModification(channel, true);
			arrangePreviewWindows();
			if (manualSelection.isSelected()) beginManualSelection();
			// The spinners may have moved while this was computing; show what they say now.
			refreshImageTransforms(false);
		}

		private ImageProcessor displayPlane(int channel, boolean aligned) {
			SourceChoice source = previewComputation.sources.get(channel);
			ImageProcessor canonical = previewComputation.projections.get(source.key());
			ImageProcessor plane = projectionForDisplay(canonical, source.side,
					flipChannels.get(channel).isSelected());
			if (!aligned) return plane;
			double[][] matrix = overlayMatrix(channel, previewComputation.matrices);
			// The reference, and a channel with no transform at all, are already in the frame.
			if (sameMatrix(matrix, identity2d())) return plane;
			return SIFT.alignWithRigid2DMatrix(plane, matrix, previewBilinear);
		}

		/** Whether this channel's plane is drawn mirrored against the measured orientation. */
		private boolean displayFlip(int channel) {
			SourceChoice source = previewComputation.sources.get(channel);
			return needsDisplayFlip(source.side, flipChannels.get(channel).isSelected());
		}

		/**
		 * One channel's transform into the overlay, for its pixels and its points alike.
		 * <p>
		 * The overlay is drawn in the reference channel's <em>displayed</em> frame, so flipping
		 * the reference mirrors the whole overlay and leaves every alignment where it was. The
		 * stored matrices in {@code set} never see a check box.
		 */
		private double[][] overlayMatrix(int channel, AlignmentMatrixSet set) {
			SourceChoice source = previewComputation.sources.get(channel);
			double[][] canonical = set.isReference(source.key()) ? null : set.matrixFor(source.key());
			int width = previewComputation.projections.get(source.key()).getWidth();
			return ChannelAlignment.overlayMatrix(canonical, displayFlip(channel), displayFlip(0), width);
		}

		/**
		 * A flip of the reference re-renders every channel: the overlay is drawn in the
		 * reference's displayed frame, so mirroring it mirrors what every other channel's
		 * matrix has to land on. A flip of any other channel is that channel's own business.
		 */
		private void flipChanged(int channel) {
			captureModifiedChannelPoints(channel);
			if (channel != 0 || previewComputation == null) {
				updatePreviewChannel(channel);
				// Say it, rather than leave a control that looks as though it did nothing.
				if (validPreviewChannel(channel)) IJ.showStatus(ChannelOperationSettings.slotLabel(channel + 1)
						+ " flipped in its own window; its alignment carries it into the overlay's frame.");
				return;
			}
			captureAllModifiedChannelPoints();	// every channel is about to be redrawn, not one
			for (int i = 0; i < previewComputation.sources.size(); i++) updatePreviewChannel(i);
			IJ.showStatus("The overlay is drawn in the first channel's frame, so it is mirrored with it; "
					+ "no interest point and no saved matrix changed.");
		}

		private void updatePreviewChannel(int channel) {
			if (previewComputation == null || channel < 0
					|| channel >= previewComputation.sources.size()) return;
			if (channel < channelPreviewImages.size()) {
				ImagePlus individual = channelPreviewImages.get(channel);
				ImageProcessor old = individual.getProcessor();
				ImageProcessor fresh = displayPlane(channel, false);
				if (old != null) fresh.setMinAndMax(old.getMin(), old.getMax());
				individual.setProcessor(individual.getTitle(), fresh);
				applyChannelColor(channel);
				individual.updateAndDraw();
			}
			if (overlayPreviewImage != null) {
				replacePlane(overlayPreviewImage, channel + 1, displayPlane(channel, true));
				overlayPreviewImage.updateAllChannelsAndDraw();
			}
			updatePreviewDetections();
			if (manualEditing && manualPointChannel == channel) installManualPoints(channel);
		}

		private void updatePreviewOverlay() {
			if (overlayPreviewImage == null) return;
			overlayPreviewImage.setActiveChannels(activeChannels(overlayChannels,
					previewComputation.sources.size()));
			overlayPreviewImage.updateAllChannelsAndDraw();
			updatePreviewDetections();
		}

		private void applyChannelColors() {
			if (previewComputation == null) return;
			for (int channel = 0; channel < previewComputation.sources.size(); channel++)
				applyChannelColor(channel);
			if (overlayPreviewImage != null) {
				applyOverlayDisplayRanges();
				overlayPreviewImage.updateAllChannelsAndDraw();
			}
		}

		private void applyChannelColor(int channel) {
			if (channel < 0 || channel >= channelColors.size()) return;
			LUT lut = LUT.createLutFromColor(colorForName((String) channelColors.get(channel).getSelectedItem()));
			if (channel < channelPreviewImages.size()) {
				ImagePlus image = channelPreviewImages.get(channel);
				image.getProcessor().setLut(lut);
				image.updateAndDraw();
			}
			if (overlayPreviewImage != null && channel < overlayPreviewImage.getNChannels())
				overlayPreviewImage.setChannelLut(lut, channel + 1);
		}

		private void updatePreviewDetections() {
			if (previewComputation == null) return;
			for (int channel = 0; channel < previewComputation.sources.size(); channel++) {
				if (channel >= channelPreviewImages.size()) break;
				ImagePlus individual = channelPreviewImages.get(channel);
				PointRoi points = detectionChannels.get(channel).isSelected()
						? manualPointsRoi(channel, false) : null;
				boolean editable = modifyChannels.get(channel).isSelected()
						&& detectionChannels.get(channel).isSelected() && !manualEditing;
				if (editable) {
					individual.setOverlay((Overlay) null);
					individual.deleteRoi();
					if (points != null) individual.setRoi(points);
				} else {
					individual.deleteRoi();
					individual.setOverlay(points == null ? null : new Overlay(points));
				}
				individual.setHideOverlay(false);
				individual.updateAndDraw();
			}
			if (overlayPreviewImage == null) return;
			if (manualEditing) {
				overlayPreviewImage.setOverlay((Overlay) null);
				if (manualPointChannel >= 0
						&& !overlayPointChannels.get(manualPointChannel).isSelected())
					overlayPreviewImage.deleteRoi();
				overlayPreviewImage.updateAndDraw();
				return;
			}
			/* The overlay's own row decides, independently of the channel windows and of whether
			 * the channel's image is shown: one channel's points over another's beads is a check. */
			Overlay points = new Overlay();
			for (int channel = 0; channel < previewComputation.sources.size(); channel++) {
				if (!overlayPointChannels.get(channel).isSelected()) continue;
				PointRoi roi = manualPointsRoi(channel, true);
				/* Keep the ROI at channel position zero. In a composite hyperstack ImageJ only
				 * draws a channel-positioned ROI while that one channel is current, even though
				 * all active image channels are visible. We rebuild this overlay from the row
				 * selections, so the visible detections should all be position-independent. */
				if (roi != null) points.add(roi);
			}
			overlayPreviewImage.setOverlay(points.size() == 0 ? null : points);
			overlayPreviewImage.setHideOverlay(false);
			overlayPreviewImage.updateAndDraw();
		}

		private PointRoi manualPointsRoi(int channel, boolean aligned) {
			if (channel < 0 || channel >= manualPointSets.size()) return null;
			List<double[]> points = manualPointSets.get(channel);
			if (points.isEmpty()) return null;
			float[] xs = new float[points.size()], ys = new float[points.size()];
			for (int i = 0; i < points.size(); i++) {
				double[] displayed = pointForDisplay(channel, points.get(i), aligned);
				xs[i] = (float) displayed[0]; ys[i] = (float) displayed[1];
			}
			PointRoi roi = new PointRoi(xs, ys, points.size());
			configureManualRoi(roi, channel, aligned);
			return roi;
		}

		private void arrangePreviewWindows() {
			if (channelPreviewImages.isEmpty()) return;
			Rectangle screen = GUI.getMaxWindowBounds(this);
			int gap = 8;
			int rows = Math.max(1, (channelPreviewImages.size() + 1) / 2);
			int usableWidth = Math.max(1, screen.width - gap * 3);
			int channelAreaWidth = Math.max(360, (int) Math.round(usableWidth * 0.40));
			channelAreaWidth = Math.min(channelAreaWidth, Math.max(1, usableWidth - 360));
			int overlayWidth = Math.max(1, usableWidth - channelAreaWidth);
			int width = Math.max(1, (channelAreaWidth - gap) / 2);
			int overlayHeight = Math.max(1, screen.height - gap * 2);
			int height = Math.max(1, Math.min((overlayHeight - gap * (rows - 1)) / rows,
					Math.max(180, (int) Math.round(overlayHeight * 0.46))));
			for (int channel = 0; channel < channelPreviewImages.size(); channel++) {
				ImageWindow window = channelPreviewImages.get(channel).getWindow();
				if (window == null) continue;
				int column = channel % 2, row = channel / 2;
				window.setLocationAndSize(screen.x + gap + column * (width + gap),
						screen.y + gap + row * (height + gap), width, height);
			}
			ImageWindow overlay = overlayPreviewImage == null ? null : overlayPreviewImage.getWindow();
			if (overlay != null) overlay.setLocationAndSize(screen.x + gap + channelAreaWidth + gap,
					screen.y + gap, overlayWidth, overlayHeight);
		}

		private static String previewTitle(SourceChoice source) {
			String name = source.file.getName();
			String lower = name.toLowerCase(Locale.ROOT);
			if (lower.endsWith(".tiff")) name = name.substring(0, name.length() - 5);
			else if (lower.endsWith(".tif")) name = name.substring(0, name.length() - 4);
			return name + "-" + source.side.name().toLowerCase(Locale.ROOT);
		}

		private void applyOverlayDisplayRanges() {
			if (overlayPreviewImage == null || previewRanges == null) return;
			// Return to the channel being viewed: in manual drawing it is the one being edited.
			int current = overlayPreviewImage.getC();
			for (int channel = 0; channel < Math.min(previewRanges.length,
					overlayPreviewImage.getNChannels()); channel++) {
				overlayPreviewImage.setC(channel + 1);
				overlayPreviewImage.setDisplayRange(previewRanges[channel][0], previewRanges[channel][1]);
				overlayPreviewImage.updateChannelAndDraw();
			}
			overlayPreviewImage.setC(current);
			overlayPreviewImage.updateAllChannelsAndDraw();
		}

		private static void applyDisplayRange(ImagePlus image, double[] range) {
			if (image == null || range == null) return;
			image.setDisplayRange(range[0], range[1]);
			image.updateAndDraw();
		}

		private void prepareManualPointSets(Computation computation) {
			List<String> keys = new ArrayList<String>();
			for (SourceChoice source : computation.sources) keys.add(source.key());
			manualPointKeys.clear();
			manualPointKeys.addAll(keys);
			List<List<double[]>> detected = automaticCorrespondences(computation);
			for (int channel = 0; channel < manualPointSets.size(); channel++) {
				List<double[]> points = channel < detected.size()
						? detected.get(channel) : Collections.<double[]>emptyList();
				automaticPointSets.set(channel, copyPoints(points));
				manualPointSets.set(channel, copyPoints(points));
			}
		}

		private static List<List<double[]>> automaticCorrespondences(Computation computation) {
			List<List<double[]>> result = new ArrayList<List<double[]>>();
			for (int channel = 0; channel < computation.sources.size(); channel++)
				result.add(new ArrayList<double[]>());
			List<BeadAlignment.Spot> reference = computation.referenceSpots;
			if (reference == null || reference.isEmpty()) return result;
			List<boolean[]> used = new ArrayList<boolean[]>();
			boolean[] available = new boolean[computation.sources.size()];
			available[0] = true;
			used.add(new boolean[reference.size()]);
			for (int channel = 1; channel < computation.sources.size(); channel++) {
				BeadAlignment.Result aligned = computation.results.get(computation.sources.get(channel).key());
				available[channel] = aligned != null && aligned.movingSpots != null
						&& !aligned.movingSpots.isEmpty();
				used.add(new boolean[available[channel] ? aligned.movingSpots.size() : 0]);
			}
			for (BeadAlignment.Spot fixed : reference) {
				int[] matches = new int[computation.sources.size()];
				boolean complete = true;
				for (int channel = 1; channel < computation.sources.size(); channel++) {
					if (!available[channel]) continue;
					SourceChoice source = computation.sources.get(channel);
					BeadAlignment.Result aligned = computation.results.get(source.key());
					double[][] matrix = computation.computedMatrices.matrixFor(source.key());
					double best = 9.0;
					int bestIndex = -1;
					for (int i = 0; i < aligned.movingSpots.size(); i++) {
						if (used.get(channel)[i]) continue;
						BeadAlignment.Spot moving = aligned.movingSpots.get(i);
						double x = matrix[0][0] * moving.x + matrix[0][1] * moving.y + matrix[0][2];
						double y = matrix[1][0] * moving.x + matrix[1][1] * moving.y + matrix[1][2];
						double dx = x - fixed.x, dy = y - fixed.y, distance = dx * dx + dy * dy;
						if (distance < best) { best = distance; bestIndex = i; }
					}
					if (bestIndex < 0) { complete = false; break; }
					matches[channel] = bestIndex;
				}
				if (!complete) continue;
				result.get(0).add(new double[] { fixed.x, fixed.y });
				for (int channel = 1; channel < computation.sources.size(); channel++) {
					if (!available[channel]) continue;
					BeadAlignment.Result aligned = computation.results.get(computation.sources.get(channel).key());
					BeadAlignment.Spot moving = aligned.movingSpots.get(matches[channel]);
					result.get(channel).add(new double[] { moving.x, moving.y });
					used.get(channel)[matches[channel]] = true;
				}
			}
			return result;
		}

		private static List<double[]> copyPoints(List<double[]> points) {
			List<double[]> copy = new ArrayList<double[]>();
			if (points != null) for (double[] point : points) copy.add(point.clone());
			return copy;
		}

		private void restoreAutomaticPoints(int channel) {
			if (channel < 0 || channel >= automaticPointSets.size()) return;
			if (previewComputation == null) {
				warn("Generate the channel images first, then request auto detection for this channel.");
				return;
			}
			captureModifiedChannelPoints(channel);
			manualPointSets.set(channel, copyPoints(automaticPointSets.get(channel)));
			updatePreviewDetections();
			if (manualEditing && channel == manualPointChannel) installManualPoints(channel);
		}

		private void toggleManualSelection() {
			boolean selected = manualSelection.isSelected();
			manualDisplay.setEnabled(selected);
			if (!selected) {
				stopManualSelection(true);
				return;
			}
			if (overlayPreviewImage != null) beginManualSelection();
			else if (!working) {
				start(false, false, true);
			}
		}

		private void beginManualSelection() {
			if (manualEditing || overlayPreviewImage == null || overlayPreviewImage.getCanvas() == null) return;
			for (int channel = 0; channel < modifyChannels.size(); channel++) {
				if (!modifyChannels.get(channel).isSelected()) continue;
				captureModifiedChannelPoints(channel);
				modifyChannels.get(channel).setSelected(false);
				setChannelModification(channel, false);
			}
			manualEditing = true;
			previousOverlayMode = overlayPreviewImage.getMode();
			previousTool = Toolbar.getToolId();
			previousMultiPoint = Toolbar.getMultiPointMode();
			Toolbar toolbar = Toolbar.getInstance();
			if (toolbar != null) toolbar.setTool("multipoint");
			applyManualDisplayMode();

			manualWheelListener = new MouseWheelListener() {
				@Override public void mouseWheelMoved(MouseWheelEvent event) {
					if (!manualEditing || previewComputation == null || event.getWheelRotation() == 0) return;
					storeActiveManualPoints();
					int count = previewComputation.sources.size();
					int direction = event.getWheelRotation() > 0 ? 1 : -1;
					int next = (manualPointChannel + direction + count) % count;
					overlayPreviewImage.setC(next + 1);
					installManualPoints(next);
					event.consume();
				}
			};
			manualMouseListener = new MouseAdapter() {
				@Override public void mouseReleased(MouseEvent event) {
					SwingUtilities.invokeLater(new Runnable() {
						@Override public void run() { captureActiveManualPoints(); }
					});
				}
			};
			ImageCanvas canvas = overlayPreviewImage.getCanvas();
			canvas.addMouseWheelListener(manualWheelListener);
			canvas.addMouseListener(manualMouseListener);
			int channel = Math.max(0, Math.min(previewComputation.sources.size() - 1,
					overlayPreviewImage.getC() - 1));
			installManualPoints(channel);
			updatePreviewDetections();
		}

		private void stopManualSelection(boolean restoreDetections) {
			if (!manualEditing) return;
			storeActiveManualPoints();
			ImageCanvas canvas = overlayPreviewImage == null ? null : overlayPreviewImage.getCanvas();
			if (canvas != null && manualWheelListener != null) canvas.removeMouseWheelListener(manualWheelListener);
			if (canvas != null && manualMouseListener != null) canvas.removeMouseListener(manualMouseListener);
			manualWheelListener = null;
			manualMouseListener = null;
			if (overlayPreviewImage != null) {
				overlayPreviewImage.deleteRoi();
				overlayPreviewImage.setMode(previousOverlayMode);
				overlayPreviewImage.updateAllChannelsAndDraw();
			}
			Toolbar toolbar = Toolbar.getInstance();
			if (toolbar != null && previousTool >= 0) {
				if (previousTool == Toolbar.POINT)
					toolbar.setTool(previousMultiPoint ? "multipoint" : "point");
				else toolbar.setTool(previousTool);
			}
			manualEditing = false;
			manualPointChannel = -1;
			if (restoreDetections) updatePreviewDetections();
		}

		private void applyManualDisplayMode() {
			if (!manualEditing || overlayPreviewImage == null) return;
			String mode = (String) manualDisplay.getSelectedItem();
			overlayPreviewImage.setMode("grayscale".equals(mode)
					? CompositeImage.GRAYSCALE : CompositeImage.COLOR);
			overlayPreviewImage.updateAllChannelsAndDraw();
		}

		private void captureActiveManualPoints() {
			if (!manualEditing || overlayPreviewImage == null) return;
			Roi roi = overlayPreviewImage.getRoi();
			if (roi instanceof PointRoi) configureManualRoi((PointRoi) roi, manualPointChannel, true);
			storeActiveManualPoints();
			updatePreviewDetections();
		}

		private void storeActiveManualPoints() {
			if (!manualEditing || overlayPreviewImage == null || manualPointChannel < 0) return;
			/* Points hidden in the overlay were never put there to edit, so whatever ROI is there
			 * now - a click on the empty channel - must not replace them. */
			if (!manualPointsShown) return;
			List<double[]> canonical = new ArrayList<double[]>();
			Roi roi = overlayPreviewImage.getRoi();
			if (roi instanceof PointRoi) {
				FloatPolygon points = roi.getFloatPolygon();
				for (int i = 0; i < points.npoints; i++)
					canonical.add(pointForCanonical(manualPointChannel,
							new double[] { points.xpoints[i], points.ypoints[i] }));
			}
			manualPointSets.set(manualPointChannel, canonical);
		}

		private void installManualPoints(int channel) {
			if (!manualEditing || overlayPreviewImage == null) return;
			overlayPreviewImage.deleteRoi();
			manualPointChannel = channel;
			manualPointsShown = overlayPointChannels.get(channel).isSelected();
			overlayPreviewImage.setC(channel + 1);
			PointRoi points = manualPointsRoi(channel, true);
			if (points != null && manualPointsShown)
				overlayPreviewImage.setRoi(points);
			overlayPreviewImage.updateAndDraw();
			IJ.showStatus("Manual interest points: " + ChannelOperationSettings.slotLabel(channel + 1)
					+ " (mouse wheel changes channel)");
		}

		/**
		 * Style one channel's points for the view they are drawn in.
		 * <p>
		 * A channel's own window holds one channel, so its markers only have to be seen: they
		 * are all the same colour, {@link #WINDOW_POINT_COLOR} unless that row says otherwise.
		 * In the overlay the channel is the whole point of a marker, so there it takes that
		 * channel's display colour and matches the pixels it marks.
		 */
		private void configureManualRoi(PointRoi roi, int channel, boolean overlay) {
			JComboBox<String> color = overlay ? channelColors.get(channel) : pointColors.get(channel);
			stylePoints(roi, colorForName((String) color.getSelectedItem()),
					labelChannels.get(channel).isSelected());
		}

		/** A new color or label setting, applied without losing points being edited in either view. */
		private void restylePoints(int channel) {
			captureModifiedChannelPoints(channel);
			boolean drawing = manualEditing && manualPointChannel == channel;
			if (drawing) storeActiveManualPoints();
			updatePreviewDetections();
			if (drawing) installManualPoints(channel);
		}

		private void showOverlayPoints(int channel) {
			if (!(manualEditing && manualPointChannel == channel)) {
				updatePreviewDetections();
				return;
			}
			if (overlayPointChannels.get(channel).isSelected()) {
				installManualPoints(channel);
			} else {
				storeActiveManualPoints();	// the edits made so far, before they leave the screen
				manualPointsShown = false;
				updatePreviewDetections();
			}
		}

		private void setChannelModification(final int channel, boolean enabled) {
			if (channel < 0 || channel >= channelPreviewImages.size()) return;
			ImagePlus image = channelPreviewImages.get(channel);
			ImageCanvas canvas = image.getCanvas();
			if (!enabled) captureModifiedChannelPoints(channel);
			MouseAdapter old = modifyMouseListeners.get(channel);
			if (old != null && canvas != null) canvas.removeMouseListener(old);
			modifyMouseListeners.set(channel, null);
			if (enabled) {
				if (manualSelection.isSelected()) {
					manualSelection.setSelected(false);
					manualDisplay.setEnabled(false);
					stopManualSelection(true);
				}
				Toolbar toolbar = Toolbar.getInstance();
				if (toolbar != null) toolbar.setTool("multipoint");
				MouseAdapter listener = new MouseAdapter() {
					@Override public void mouseReleased(MouseEvent event) {
						SwingUtilities.invokeLater(new Runnable() {
							@Override public void run() {
								captureModifiedChannelPoints(channel);
								updatePreviewDetections();
							}
						});
					}
				};
				modifyMouseListeners.set(channel, listener);
				if (canvas != null) canvas.addMouseListener(listener);
			}
			updatePreviewDetections();
		}

		private void captureModifiedChannelPoints(int channel) {
			if (channel < 0 || channel >= channelPreviewImages.size()
					|| channel >= manualPointSets.size()) return;
			ImagePlus image = channelPreviewImages.get(channel);
			Roi roi = image.getRoi();
			if (!(roi instanceof PointRoi)) {
				/* A missing active ROI while an editor listener is installed means the user
				 * deleted the last point (possibly immediately before unchecking modify). */
				if (modifyChannels.get(channel).isSelected()
						|| modifyMouseListeners.get(channel) != null)
					manualPointSets.set(channel, new ArrayList<double[]>());
				return;
			}
			configureManualRoi((PointRoi) roi, channel, false);
			FloatPolygon points = roi.getFloatPolygon();
			List<double[]> canonical = new ArrayList<double[]>();
			for (int i = 0; i < points.npoints; i++)
				canonical.add(pointForCanonical(channel,
						new double[] { points.xpoints[i], points.ypoints[i] }, false));
			manualPointSets.set(channel, canonical);
		}

		private void captureAllModifiedChannelPoints() {
			for (int channel = 0; channel < modifyChannels.size(); channel++)
				if (modifyChannels.get(channel).isSelected()) captureModifiedChannelPoints(channel);
		}

		private double[] pointForDisplay(int channel, double[] canonical, boolean aligned) {
			SourceChoice source = previewComputation.sources.get(channel);
			ImageProcessor image = previewComputation.projections.get(source.key());
			// The same transform the pixels get, so a point cannot drift away from its bead.
			double[][] matrix = aligned ? overlayMatrix(channel, previewComputation.computedMatrices) : null;
			return displayedPoint(canonical[0], canonical[1], image.getWidth(),
					displayFlip(channel), matrix);
		}

		private double[] pointForCanonical(int channel, double[] displayed) {
			return pointForCanonical(channel, displayed, true);
		}

		private double[] pointForCanonical(int channel, double[] displayed, boolean aligned) {
			SourceChoice source = previewComputation.sources.get(channel);
			ImageProcessor image = previewComputation.projections.get(source.key());
			double[][] matrix = aligned ? overlayMatrix(channel, previewComputation.computedMatrices) : null;
			return canonicalPoint(displayed[0], displayed[1], image.getWidth(),
					displayFlip(channel), matrix);
		}

		private void recomputeFromManualPoints() {
			if (previewComputation == null) { warn("Open the channel windows + overlay preview first."); return; }
			captureAllModifiedChannelPoints();
			storeActiveManualPoints();
			List<double[]> reference = manualPointSets.get(0);
			if (reference.size() < 3) {
				warn("The reference channel needs at least 3 labelled interest points."); return;
			}
			List<ManualFit> fits = new ArrayList<ManualFit>();
			for (int channel = 1; channel < previewComputation.sources.size(); channel++) {
				int pairs = Math.min(reference.size(), manualPointSets.get(channel).size());
				if (pairs < 3) {
					warn(ChannelOperationSettings.slotLabel(channel + 1)
							+ " needs at least 3 points with matching labels."); return;
				}
				ManualFit fit = fitManualRigid(reference, manualPointSets.get(channel));
				if (fit == null) {
					warn("The manual points for " + ChannelOperationSettings.slotLabel(channel + 1)
							+ " do not define a stable rigid transform."); return;
				}
				fits.add(fit);
			}
			List<BeadAlignment.Spot> referenceSpots = manualSpots(reference);
			previewComputation.referenceSpots = referenceSpots;
			previewComputation.alignmentRemoved = false;
			zeroAdjustments(previewComputation.sources.size());
			for (int channel = 1; channel < previewComputation.sources.size(); channel++) {
				SourceChoice source = previewComputation.sources.get(channel);
				ManualFit fit = fits.get(channel - 1);
				previewComputation.computedMatrices.put(source.key(), fit.matrix);
				previewComputation.results.put(source.key(), new BeadAlignment.Result(fit.matrix,
						referenceSpots, manualSpots(manualPointSets.get(channel)), fit.matches,
						fit.rmsPixels, "manual labelled interest points"));
				previewComputation.issues.remove(source.key());
				IJ.log(String.format(Locale.US,
						"OPM Channel Alignment: %s recomputed from %d manual point pairs; RMS %.3f px",
						source.key(), fit.matches, fit.rmsPixels));
			}
			refreshImageTransforms(false);
			// The points are unchanged, but the new fit is what places them in the overlay.
			updatePreviewDetections();
			if (manualEditing) installManualPoints(Math.max(0, manualPointChannel));
			IJ.showStatus("Alignment recomputed from manual labelled interest points.");
		}

		private static List<BeadAlignment.Spot> manualSpots(List<double[]> points) {
			List<BeadAlignment.Spot> spots = new ArrayList<BeadAlignment.Spot>();
			for (double[] point : points) spots.add(new BeadAlignment.Spot(point[0], point[1], 1));
			return spots;
		}

		private void report(Computation computation) {
			IJ.log("OPM Channel Alignment: reference " + computation.matrices.reference());
			if (computation.referenceSpots == null || computation.referenceSpots.isEmpty())
				IJ.log("  " + computation.matrices.reference()
						+ ": no automatic interest points; channel kept for manual selection");
			for (int i = 1; i < computation.sources.size(); i++) {
				String key = computation.sources.get(i).key();
				BeadAlignment.Result result = computation.results.get(key);
				if (result == null) IJ.log("  " + key + ": " + computation.issues.get(key));
				else {
					IJ.log(String.format(Locale.US, "  %s: %s; %d interest-point pairs; RMS %.3f px",
							key, result.method, result.matches, result.rmsPixels));
					IO.displayMatrix(computation.matrices.matrixFor(key));
				}
			}
		}

		private void chooseSave(AlignmentMatrixSet matrices) {
			if (matrices == null) return;
			SaveDialog chooser = new SaveDialog("Save Alignment Matrix", "beads-alignment", ".csv");
			if (chooser.getFileName() == null) return;
			File file = new File(chooser.getDirectory(), chooser.getFileName());
			if (!matrices.save(file)) warn("Could not save the alignment matrix.");
		}

		private void setControlsEnabled(boolean enabled) {
			ok.setEnabled(enabled); save.setEnabled(enabled); fewer.setEnabled(enabled && visibleChannels > 2);
			more.setEnabled(enabled && visibleChannels < MAX_CHANNELS); cancel.setEnabled(enabled);
			generate.setEnabled(enabled); recompute.setEnabled(enabled); removeAlignment.setEnabled(enabled);
			if (enabled) updateManualEnabled();
			else for (JButton reset : resetAdjustments) reset.setEnabled(false);
		}

		private void closePreview() {
			stopManualSelection(false);
			for (int channel = 0; channel < channelPreviewImages.size(); channel++) {
				ImagePlus image = channelPreviewImages.get(channel);
				MouseAdapter listener = channel < modifyMouseListeners.size()
						? modifyMouseListeners.get(channel) : null;
				if (listener != null && image.getCanvas() != null) image.getCanvas().removeMouseListener(listener);
				if (channel < modifyMouseListeners.size()) modifyMouseListeners.set(channel, null);
				image.changes = false;
				image.close();
			}
			channelPreviewImages.clear();
			if (overlayPreviewImage != null) {
				overlayPreviewImage.changes = false;
				overlayPreviewImage.close();
				overlayPreviewImage = null;
			}
			previewComputation = null;
			previewRanges = null;
		}

		private void setManualEnabled(boolean enabled) {
			manual.setSelected(enabled);
			updateManualEnabled();
		}

		private void updateManualEnabled() {
			boolean enabled = manual.isSelected();
			for (int channel = 0; channel < translateX.size(); channel++) {
				boolean editable = enabled && channel < visibleChannels;
				translateX.get(channel).setEnabled(editable);
				translateY.get(channel).setEnabled(editable);
				rotation.get(channel).setEnabled(editable);
				resetAdjustments.get(channel).setEnabled(editable);
			}
		}

		/** Zeroing the row is the whole reset: its spinners' listeners redraw the overlay. */
		private void resetManualAdjustment(int channel) {
			setAdjustmentValues(channel, 0, 0, 0);
			if (validPreviewChannel(channel)) IJ.showStatus(ChannelOperationSettings.slotLabel(channel + 1)
					+ " manual adjustment reset; interest points were not changed.");
		}

		private void removeAllAlignment() {
			if (previewComputation == null || overlayPreviewImage == null) {
				warn("Generate the channel images and overlay before removing alignment.");
				return;
			}
			previewComputation.alignmentRemoved = true;
			zeroAdjustments(previewComputation.sources.size());
			refreshImageTransforms(false);
			IJ.showStatus("All image alignment transforms removed; display flips and interest points remain active.");
		}

		private boolean validPreviewChannel(int channel) {
			return previewComputation != null && overlayPreviewImage != null && channel >= 0
					&& channel < previewComputation.sources.size();
		}

		private void setAdjustmentValues(int channel, double x, double y, double angle) {
			if (channel < 0 || channel >= translateX.size()) return;
			translateX.get(channel).setValue(Double.valueOf(x));
			translateY.get(channel).setValue(Double.valueOf(y));
			rotation.get(channel).setValue(Double.valueOf(angle));
		}

		/** Zero the rows with one redraw for all of them, which the caller makes. */
		private void zeroAdjustments(int count) {
			settingAdjustments = true;
			try {
				for (int channel = 0; channel < count; channel++) setAdjustmentValues(channel, 0, 0, 0);
			} finally { settingAdjustments = false; }
		}

		private double[] adjustmentValues(int channel) {
			return new double[] { spinnerValue(translateX.get(channel)),
					spinnerValue(translateY.get(channel)), spinnerValue(rotation.get(channel)) };
		}

		/**
		 * The overlay follows the spinners as they change. Changes are coalesced into one redraw
		 * per pass of the event queue, so a fast wheel or a held arrow does not queue a warp for
		 * every step it passes through.
		 */
		private void scheduleImageTransformRefresh(boolean everyPlane) {
			if (settingAdjustments) return;
			imageRefreshEveryPlane |= everyPlane;
			if (imageRefreshPending) return;
			imageRefreshPending = true;
			SwingUtilities.invokeLater(new Runnable() {
				@Override public void run() {
					boolean every = imageRefreshEveryPlane;
					imageRefreshPending = false;
					imageRefreshEveryPlane = false;
					refreshImageTransforms(every);
				}
			});
		}

		/**
		 * Give every channel its transform before manual adjustment - the interest-point fit, or
		 * the identity after "remove all" - plus the spinner rows while manual adjustment is
		 * ticked, and re-warp the overlay planes whose matrix changed ({@code everyPlane} re-warps
		 * all of them, for a new interpolation). The saved matrices are these, so what is saved is
		 * what the overlay shows. Interest points are placed by the fit alone and do not move.
		 */
		private void refreshImageTransforms(boolean everyPlane) {
			if (previewComputation == null || overlayPreviewImage == null) return;
			boolean useManual = manual.isSelected();
			double[] referenceValues = useManual ? adjustmentValues(0) : null;
			boolean changed = false;
			long resampled = 0, transformed = 0;
			for (int channel = 1; channel < previewComputation.sources.size(); channel++) {
				String key = previewComputation.sources.get(channel).key();
				ImageProcessor image = previewComputation.projections.get(key);
				double[][] matrix = reframedManualAdjustment(baselineMatrix(key), referenceValues,
						useManual ? adjustmentValues(channel) : null, image.getWidth(), image.getHeight());
				if (!everyPlane && sameMatrix(matrix, previewComputation.matrices.matrixFor(key))) continue;
				previewComputation.matrices.put(key, matrix);
				ImageProcessor before = everyPlane
						? overlayPreviewImage.getImageStack().getProcessor(channel + 1).duplicate() : null;
				refreshAlignedChannelPlane(channel);
				if (before != null) {
					resampled += differingPixels(before,
							overlayPreviewImage.getImageStack().getProcessor(channel + 1));
					transformed += (long) before.getWidth() * before.getHeight();
				}
				changed = true;
			}
			if (changed) overlayPreviewImage.updateAllChannelsAndDraw();
			if (everyPlane) reportResampling(resampled, transformed);
		}

		/**
		 * Say what a new interpolation did to the overlay, because it is normally invisible and
		 * is easily taken for a control that does nothing.
		 * <p>
		 * It does re-warp every transformed plane and redraw the window. But nearest and bilinear
		 * can only differ where a sample falls between pixels: over a fit that is a whole-pixel
		 * translation with no rotation they are the same image but for the clamped border, and
		 * over a sub-pixel one they differ in how a bead is smoothed, never in where it sits.
		 * Measured on a 512 x 512 field of 300 synthetic beads: 36 pixels of 262144 differ for a
		 * (3, 0) shift, against 10.7% of them - by up to 692 of a 2441 peak - for a
		 * (3.4, -1.6) shift with a 0.26 degree rotation. A channel's own window is never warped,
		 * so it never changes at all.
		 */
		private void reportResampling(long resampled, long transformed) {
			String method = (String) interpolation.getSelectedItem();
			if (transformed == 0) {
				IJ.showStatus("Interpolation " + method + ": no transformed channel to resample.");
				return;
			}
			IJ.showStatus(String.format(Locale.US, "Interpolation %s: %.2f%% of the transformed "
					+ "overlay pixels changed%s", method, 100.0 * resampled / transformed,
					resampled == 0 ? " - this alignment samples on whole pixels" : ""));
		}

		private static long differingPixels(ImageProcessor before, ImageProcessor after) {
			long count = 0;
			for (int y = 0; y < before.getHeight(); y++) for (int x = 0; x < before.getWidth(); x++)
				if (before.getf(x, y) != after.getf(x, y)) count++;
			return count;
		}

		private double[][] baselineMatrix(String key) {
			double[][] computed = previewComputation.alignmentRemoved ? null
					: previewComputation.computedMatrices.matrixFor(key);
			return computed == null ? identity2d() : computed;
		}

		private void refreshAlignedChannelPlane(int channel) {
			if (overlayPreviewImage == null || channel < 0
					|| channel >= overlayPreviewImage.getStackSize()) return;
			replacePlane(overlayPreviewImage, channel + 1, displayPlane(channel, true));
		}

		private Map<String, double[]> selectedManualValues(List<SourceChoice> sources) {
			Map<String, double[]> result = new LinkedHashMap<String, double[]>();
			for (int channel = 0; channel < sources.size(); channel++) result.put(sources.get(channel).key(),
					new double[] { spinnerValue(translateX.get(channel)), spinnerValue(translateY.get(channel)),
							spinnerValue(rotation.get(channel)) });
			return result;
		}

		private void installDrop(Component component) {
			component.setDropTarget(null);
			if (component instanceof javax.swing.JComponent) ((javax.swing.JComponent) component).setTransferHandler(new TransferHandler() {
				private static final long serialVersionUID = 1L;
				@Override public boolean canImport(TransferSupport support) { return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor); }
				@Override public boolean importData(TransferSupport support) {
					try {
						Transferable transferable = support.getTransferable();
						@SuppressWarnings("unchecked") List<File> dropped = (List<File>) transferable.getTransferData(DataFlavor.javaFileListFlavor);
						if (!dropped.isEmpty()) { loadPath(dropped.get(0)); return true; }
					} catch (Exception ignored) { }
					return false;
				}
			});
		}

		/** One line per paragraph: the option pane wraps them, so the width is set in one place. */
		static final String HELP_TEXT =
				"Click a section heading to fold or unfold it; when the sections do not fit they scroll.\n\n"
				+ "Choose one TIFF or a folder, tick the files to use, then assign optical sources to output channels.\n\n"
				+ "Use deskew + max Z for raw volumes, max Z alone for already deskewed volumes, and neither for a "
				+ "2-D projection. A selected ExperimentalParameters.txt supplies deskew calibration. Right halves are "
				+ "mirrored before registration. Channel 1 is the reference; every later channel receives its own rigid "
				+ "matrix back to channel 1. A channel with no detected signal remains visible with an identity matrix.\n\n"
				+ "Generate/update opens one auto-contrasted window per channel plus a multichannel overlay. The Display "
				+ "channel checkbox affects only the overlay, and the LUT affects both. Flip is a view state: detection, "
				+ "the fit and every saved matrix stay in the measured orientation whatever is ticked. It mirrors that "
				+ "channel's own window; the overlay is drawn in the first channel's frame, so flipping the first "
				+ "channel mirrors the whole overlay with its alignment intact, and flipping any other one is carried "
				+ "by that channel's own matrix and leaves the overlay as it was.\n\n"
				+ "Interest points are translucent open circles, so the bead stays visible and overlapping channels "
				+ "blend. A channel row's checkbox shows its points in its own window, in the color that row chooses - "
				+ "one window holds one channel, so they are all yellow to begin with. The 'display interest points in "
				+ "overlay' row does the same for the overlay, where each channel's points take that channel's display "
				+ "color instead. 'label' numbers a channel's points in both.\n\n"
				+ "Auto detection restores this run's automatic labelled correspondences. Check modify to make that channel's "
				+ "multipoint ROI active in its own window for normal Fiji point add/delete/drag operations; uncheck it to lock "
				+ "the ROI back into the overlay. For overlay drawing, choose color or grayscale and click matching features "
				+ "in label order. The mouse wheel cycles channels. Supply at least three matching labels in every channel, "
				+ "then recompute alignment.\n\n"
				+ "Manual X/Y controls use pixels and rotation uses degrees. Positive X is right, positive Y is down, and "
				+ "positive rotation is counter-clockwise about the fixed geometric image centre, ((width-1)/2, "
				+ "(height-1)/2). Spinner buttons step by 0.1; the mouse wheel steps by 0.5. The overlay follows the "
				+ "spinners as they change; only image pixels move, never interest points. Reset zeros the row and "
				+ "returns the channel to its transform before manual adjustment: the interest-point fit, or none after "
				+ "remove all. Unticking manual adjustment sets the rows aside without losing them. Channel 1 is "
				+ "adjustable too: its correction is stored as the inverse change on every other channel so channel 1 "
				+ "remains the identity reference. Remove all alignment leaves only display flips.\n\n"
				+ "Interpolation re-warps the overlay as soon as it changes, but nearest and bilinear can only differ "
				+ "where a sample falls between pixels: a bead is smoothed differently, never moved, and an alignment "
				+ "that happens to be a whole-pixel shift gives the same image either way. The status line says what "
				+ "fraction of the overlay actually changed. A channel's own window is never warped.\n\n"
				+ "Every setting here except the manual X/Y/rotation rows is remembered for the next session, and the "
				+ "folder is reopened. Those rows are measured against one acquisition and start at zero.\n\n"
				+ "A two-channel Channel0001 left/right result is saved as the classic 2 x 3 CSV. Larger selections are "
				+ "saved in the tagged multi-matrix CSV understood by Deskew Batch and Deskew Live.";

		/** Characters per help line: about 1.4x the 100-112 the text was once wrapped at by hand. */
		static final int HELP_LINE_LENGTH = 150;

		/** The help, wrapped at word boundaries to {@code lineLength} characters. */
		static JOptionPane helpPane(final int lineLength) {
			return new JOptionPane(HELP_TEXT, JOptionPane.INFORMATION_MESSAGE) {
				private static final long serialVersionUID = 1L;
				@Override public int getMaxCharactersPerLineCount() { return lineLength; }
			};
		}

		private void showHelp() {
			JOptionPane pane = helpPane(HELP_LINE_LENGTH);
			// A large GUI scale on a small screen: fewer characters per line rather than off screen.
			int room = (int) (GUI.getMaxWindowBounds(this).width * 0.9);
			int wide = pane.getPreferredSize().width;
			if (wide > room) pane = helpPane(Math.max(60, HELP_LINE_LENGTH * room / wide));
			JDialog dialog = pane.createDialog(this, "Channel Alignment Help");
			dialog.setVisible(true);
			dialog.dispose();
		}

		private void warn(String message) { JOptionPane.showMessageDialog(this, message, "Channel Alignment", JOptionPane.WARNING_MESSAGE); }

		/** A section's contents; its heading is the {@link Fold} around it. */
		private static JPanel sectionBody() {
			return new JPanel(new BorderLayout(6, 6));
		}

		private static GridBagConstraints constraints() {
			GridBagConstraints c = new GridBagConstraints();
			c.gridx = 0; c.gridy = 0; c.anchor = GridBagConstraints.WEST;
			c.insets = new Insets(3, 5, 3, 5);
			return c;
		}

		private static JComboBox<String> colorChoice(int channel) {
			JComboBox<String> choice = new JComboBox<String>(COLOR_NAMES);
			choice.setSelectedIndex(channel % COLOR_NAMES.length);
			return choice;
		}

		private static JComboBox<String> colorChoice(String name) {
			JComboBox<String> choice = new JComboBox<String>(COLOR_NAMES);
			choice.setSelectedItem(name);
			return choice;
		}

		private static Color colorForName(String name) {
			if ("Green".equals(name)) return Color.GREEN;
			if ("Blue".equals(name)) return Color.BLUE;
			if ("Cyan".equals(name)) return Color.CYAN;
			if ("Magenta".equals(name)) return Color.MAGENTA;
			if ("Yellow".equals(name)) return Color.YELLOW;
			if ("Gray".equals(name)) return Color.WHITE;
			return Color.RED;
		}

		private static JSpinner numberSpinner() {
			final SpinnerNumberModel model = new SpinnerNumberModel(0.0, -10000.0, 10000.0, 0.1);
			final JSpinner spinner = new JSpinner(model);
			spinner.setEditor(new JSpinner.NumberEditor(spinner, "0.0"));
			spinner.setPreferredSize(new Dimension(78, spinner.getPreferredSize().height));
			spinner.addMouseWheelListener(new MouseWheelListener() {
				@Override public void mouseWheelMoved(MouseWheelEvent event) {
					double value = ((Number) model.getValue()).doubleValue()
							- Math.signum(event.getWheelRotation()) * 0.5;
					value = Math.max(((Number) model.getMinimum()).doubleValue(),
							Math.min(((Number) model.getMaximum()).doubleValue(), value));
					model.setValue(Double.valueOf(Math.rint(value * 10.0) / 10.0));
					event.consume();
				}
			});
			return spinner;
		}

		private static double spinnerValue(JSpinner spinner) {
			return ((Number) spinner.getValue()).doubleValue();
		}

		private static String activeChannels(List<JCheckBox> channels, int count) {
			StringBuilder active = new StringBuilder(count);
			for (int i = 0; i < count; i++) active.append(channels.get(i).isSelected() ? '1' : '0');
			return active.toString();
		}
		private static boolean isTiff(File file) {
			String name = file.getName().toLowerCase(Locale.ROOT);
			return name.endsWith(".tif") || name.endsWith(".tiff");
		}
	}

	/** Right halves arrive mirrored on the camera and are flipped onto the left by default. */
	static boolean defaultFlip(BeadAlignment.Side side) {
		return side == BeadAlignment.Side.RIGHT;
	}

	/** The streamed projection is already in the historic right-halves-mirrored convention. */
	static boolean needsDisplayFlip(BeadAlignment.Side side, boolean selectedFlip) {
		return selectedFlip != defaultFlip(side);
	}

	/** Make the displayed orientation without ever modifying the measured projection. */
	static ImageProcessor projectionForDisplay(ImageProcessor canonical, BeadAlignment.Side side,
			boolean selectedFlip) {
		ImageProcessor displayed = canonical.duplicate();
		if (needsDisplayFlip(side, selectedFlip)) displayed.flipHorizontal();
		return displayed;
	}

	/** The horizontal mirror of a plane {@code width} pixels wide, as a compact 2-D affine. */
	static double[][] mirrorMatrix2D(int width) {
		return new double[][] { { -1, 0, width - 1.0 }, { 0, 1, 0 } };
	}

	/**
	 * Carry a measured source-to-reference matrix into the frame the overlay is drawn in.
	 * <p>
	 * A display flip is a view state. Detection, the fit and every stored matrix stay in the
	 * canonical frame - right halves mirrored - whatever the check boxes say, so the matrix
	 * here is the measured one. But the overlay is drawn in the <em>reference channel's
	 * displayed</em> frame, and each channel's own plane is mirrored before it is warped, so the
	 * transform that actually places its pixels is
	 * {@code F_reference * M * F_source}, with F the mirror about [0, width - 1].
	 * <p>
	 * Two mirrors cancel, so this is still a rigid transform: mirroring both the reference and
	 * the source negates the X translation and the sense of the rotation, and leaves Y alone.
	 * Applying M itself to a mirrored plane - which is what this replaced - left the channel out
	 * by twice the matrix's X translation and twice its angle, silently, because with the shipped
	 * flips (left kept, right mirrored) every F is the identity and the two agree.
	 *
	 * @param canonical			: the measured source-to-reference matrix; null means the identity
	 * @param flipSource		: whether this channel is displayed mirrored
	 * @param flipReference		: whether the reference channel is displayed mirrored
	 * @param width				: the common width of the canonical projections
	 * <p>
	 * @return double[2][3]		: the transform from the displayed source plane to the overlay
	 */
	static double[][] overlayMatrix(double[][] canonical, boolean flipSource, boolean flipReference,
			int width) {
		double[][] matrix = canonical == null ? identity2d() : canonical;
		if (flipSource) matrix = compose2d(matrix, mirrorMatrix2D(width));
		if (flipReference) matrix = compose2d(mirrorMatrix2D(width), matrix);
		return AlignmentMatrixSet.copy(matrix);
	}

	/** Apply the same display flip and optional source-to-reference matrix to one bead point. */
	static double[] displayedPoint(double x, double y, int width, boolean flip,
			double[][] matrix) {
		if (flip) x = width - 1.0 - x;
		if (matrix == null) return new double[] { x, y };
		return new double[] {
			matrix[0][0] * x + matrix[0][1] * y + matrix[0][2],
			matrix[1][0] * x + matrix[1][1] * y + matrix[1][2]
		};
	}

	/** Undo the displayed source-to-reference transform and recover measured source coordinates. */
	static double[] canonicalPoint(double x, double y, int width, boolean flip,
			double[][] matrix) {
		if (matrix != null) {
			double[][] inverse = Transform.inverseAlignmentMatrix2D(matrix);
			double transformedX = inverse[0][0] * x + inverse[0][1] * y + inverse[0][2];
			double transformedY = inverse[1][0] * x + inverse[1][1] * y + inverse[1][2];
			x = transformedX; y = transformedY;
		}
		if (flip) x = width - 1.0 - x;
		return new double[] { x, y };
	}

	static double[][] identity2d() {
		return new double[][] { { 1, 0, 0 }, { 0, 1, 0 } };
	}

	/** Numerically equal; unlike Arrays.deepEquals, 0.0 and -0.0 are the same translation. */
	static boolean sameMatrix(double[][] a, double[][] b) {
		if (a == null || b == null) return a == b;
		for (int row = 0; row < 2; row++) for (int column = 0; column < 3; column++)
			if (a[row][column] != b[row][column]) return false;
		return true;
	}

	/**
	 * Write a new plane into an open stack by copying into the pixel array it already has.
	 * A CompositeImage draws each channel from a processor wrapping the array it was built with,
	 * refreshed only when Z or T changes, and ImagePlus.setSlice writes the current channel's
	 * array back into the stack when the channel changes. A plane swapped in with
	 * ImageStack.setProcessor is therefore never drawn, and can even be put back.
	 */
	static void replacePlane(ImagePlus image, int slice, ImageProcessor plane) {
		image.getImageStack().getProcessor(slice).insert(plane, 0, 0);
	}

	/** Opacity of the interest-point circles, so overlapping channels blend instead of hiding. */
	static final int POINT_ALPHA = 160;

	/**
	 * Interest points as translucent open circles. ImageJ's HYBRID marker paints an opaque white
	 * cross and black ring over the point whatever its color's alpha, which hid the bead it marked
	 * and any other channel's marker beneath it. A circle is drawn only in the ROI's own color, so
	 * the alpha applies to all of it, and its centre - the bead - is not painted at all.
	 */
	static void stylePoints(PointRoi roi, Color color, boolean labels) {
		if (roi == null) return;
		roi.setShowLabels(labels);
		roi.setPointType(PointRoi.CIRCLE);
		roi.setSize(3);
		roi.promptBeforeDeleting(Boolean.FALSE);
		roi.setStrokeColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), POINT_ALPHA));
	}

	/** Per-channel display ranges, with a blank half borrowing its partner's useful range. */
	static double[][] previewDisplayRanges(List<SourceChoice> sources,
			Map<String, ImageProcessor> projections) {
		double[][] ranges = new double[sources.size()][];
		for (int i = 0; i < sources.size(); i++)
			ranges[i] = automaticDisplayRange(projections.get(sources.get(i).key()));
		for (int i = 0; i < sources.size(); i++) {
			if (ranges[i] != null) continue;
			SourceChoice blank = sources.get(i);
			for (int j = 0; j < sources.size(); j++) {
				SourceChoice partner = sources.get(j);
				if (ranges[j] != null && blank.file.equals(partner.file)
						&& blank.side != partner.side && blank.side != BeadAlignment.Side.WHOLE
						&& partner.side != BeadAlignment.Side.WHOLE) {
					ranges[i] = ranges[j].clone();
					break;
				}
			}
		}
		double[] any = null;
		for (double[] range : ranges) if (range != null) { any = range; break; }
		if (any == null) any = new double[] { 0, 1 };
		for (int i = 0; i < ranges.length; i++) if (ranges[i] == null) ranges[i] = any.clone();
		return ranges;
	}

	/** Histogram-based range in the processor's real value units; null means no usable contrast. */
	static double[] automaticDisplayRange(ImageProcessor processor) {
		if (processor == null) return null;
		ImageStatistics stats = processor.getStats();
		if (!Double.isFinite(stats.min) || !Double.isFinite(stats.max) || stats.max <= stats.min)
			return null;
		int[] histogram = stats.histogram16 != null ? stats.histogram16 : stats.histogram;
		if (histogram == null || histogram.length == 0) return new double[] { stats.min, stats.max };
		int tail = Math.max(0, (int) Math.floor(stats.pixelCount * 0.00005));
		int low = 0, accumulated = 0;
		while (low < histogram.length - 1 && accumulated + histogram[low] <= tail)
			accumulated += histogram[low++];
		int high = histogram.length - 1;
		accumulated = 0;
		while (high > low && accumulated + histogram[high] <= tail)
			accumulated += histogram[high--];
		double minimum, maximum;
		if (stats.histogram16 != null) {
			minimum = low; maximum = high;
		} else if (stats.binSize > 0 && Double.isFinite(stats.histMin)) {
			minimum = stats.histMin + low * stats.binSize;
			maximum = stats.histMin + (high + 1) * stats.binSize;
		} else {
			minimum = stats.min; maximum = stats.max;
		}
		minimum = Math.max(stats.min, minimum);
		maximum = Math.min(stats.max, maximum);
		if (!(maximum > minimum)) { minimum = stats.min; maximum = stats.max; }
		return maximum > minimum ? new double[] { minimum, maximum } : null;
	}

	static final class ManualFit {
		final double[][] matrix;
		final int matches;
		final double rmsPixels;
		ManualFit(double[][] matrix, int matches, double rmsPixels) {
			this.matrix = matrix; this.matches = matches; this.rmsPixels = rmsPixels;
		}
	}

	/** Least-squares 2-D rigid fit using point indices as the labels shared by both channels. */
	static ManualFit fitManualRigid(List<double[]> reference, List<double[]> moving) {
		int count = Math.min(reference == null ? 0 : reference.size(), moving == null ? 0 : moving.size());
		if (count < 3) return null;
		double referenceX = 0, referenceY = 0, movingX = 0, movingY = 0;
		for (int i = 0; i < count; i++) {
			referenceX += reference.get(i)[0]; referenceY += reference.get(i)[1];
			movingX += moving.get(i)[0]; movingY += moving.get(i)[1];
		}
		referenceX /= count; referenceY /= count; movingX /= count; movingY /= count;
		double dot = 0, cross = 0, spread = 0, referenceSpread = 0;
		for (int i = 0; i < count; i++) {
			double mx = moving.get(i)[0] - movingX, my = moving.get(i)[1] - movingY;
			double rx = reference.get(i)[0] - referenceX, ry = reference.get(i)[1] - referenceY;
			dot += mx * rx + my * ry;
			cross += mx * ry - my * rx;
			spread += mx * mx + my * my;
			referenceSpread += rx * rx + ry * ry;
		}
		if (!(spread > 1e-9) || !(referenceSpread > 1e-9)
				|| (!Double.isFinite(dot) || !Double.isFinite(cross))) return null;
		double angle = Math.atan2(cross, dot), cosine = Math.cos(angle), sine = Math.sin(angle);
		double translateX = referenceX - cosine * movingX + sine * movingY;
		double translateY = referenceY - sine * movingX - cosine * movingY;
		double[][] matrix = { { cosine, -sine, translateX }, { sine, cosine, translateY } };
		double error = 0;
		for (int i = 0; i < count; i++) {
			double[] point = moving.get(i), target = reference.get(i);
			double x = matrix[0][0] * point[0] + matrix[0][1] * point[1] + matrix[0][2];
			double y = matrix[1][0] * point[0] + matrix[1][1] * point[1] + matrix[1][2];
			double dx = x - target[0], dy = y - target[1];
			error += dx * dx + dy * dy;
		}
		return new ManualFit(matrix, count, Math.sqrt(error / count));
	}

	/** Compose a user adjustment after an automatically fitted source-to-reference matrix. */
	static double[][] manualAdjustment(double[][] automatic, double[] values, int width, int height) {
		if (values == null || (values[0] == 0 && values[1] == 0 && values[2] == 0))
			return AlignmentMatrixSet.copy(automatic);
		/* Image coordinates grow downward, so negate the mathematical sine to make a
		 * positive UI angle appear counter-clockwise on screen. */
		double angle = Math.toRadians(values[2]), cosine = Math.cos(angle), sine = -Math.sin(angle);
		double cx = (width - 1) / 2.0, cy = (height - 1) / 2.0;
		double[][] adjustment = {
			{ cosine, -sine, cx - cosine * cx + sine * cy + values[0] },
			{ sine, cosine, cy - sine * cx - cosine * cy + values[1] },
			{ 0, 0, 1 }
		};
		double[][] original = {
			{ automatic[0][0], automatic[0][1], automatic[0][2] },
			{ automatic[1][0], automatic[1][1], automatic[1][2] },
			{ 0, 0, 1 }
		};
		double[][] result = new double[3][3];
		for (int row = 0; row < 3; row++) for (int column = 0; column < 3; column++)
			for (int i = 0; i < 3; i++) result[row][column] += adjustment[row][i] * original[i][column];
		return new double[][] { result[0], result[1] };
	}

	/**
	 * Express per-channel image corrections while retaining channel 1 as the stored identity
	 * reference. A correction entered for channel 1 is therefore applied inversely to every
	 * non-reference channel; it has the same relative visual effect without moving channel 1.
	 */
	static double[][] reframedManualAdjustment(double[][] computed, double[] referenceValues,
			double[] channelValues, int width, int height) {
		double[][] referenceCorrection = manualAdjustment(identity2d(), referenceValues, width, height);
		double[][] correctedChannel = manualAdjustment(computed, channelValues, width, height);
		return compose2d(Transform.inverseAlignmentMatrix2D(referenceCorrection), correctedChannel);
	}

	/** Compose compact 2-D affine transforms as {@code left * right}. */
	static double[][] compose2d(double[][] left, double[][] right) {
		double[][] l = {
			{ left[0][0], left[0][1], left[0][2] },
			{ left[1][0], left[1][1], left[1][2] },
			{ 0, 0, 1 }
		};
		double[][] r = {
			{ right[0][0], right[0][1], right[0][2] },
			{ right[1][0], right[1][1], right[1][2] },
			{ 0, 0, 1 }
		};
		double[][] result = new double[3][3];
		for (int row = 0; row < 3; row++) for (int column = 0; column < 3; column++)
			for (int i = 0; i < 3; i++) result[row][column] += l[row][i] * r[i][column];
		return new double[][] { result[0], result[1] };
	}
}
