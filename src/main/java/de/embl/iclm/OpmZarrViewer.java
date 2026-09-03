package de.embl.iclm;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.YesNoCancelDialog;

import fiji.util.gui.GenericDialogPlus;
import ij.plugin.frame.PlugInFrame;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.List;

/** Fiji window for projection movies, virtual volumes, runtime alignment and 5D assembly. */
public class OpmZarrViewer extends PlugInFrame {

	private static final long serialVersionUID = 1L;
	private static final String TITLE = "OPM OME-Zarr Viewer";
	private static final String PATH_KEY = "opm.zarrViewer.path";
	private static final String LOC_KEY = "opm.zarrViewer.loc";
	private static final String[] OPEN_MODES = {
		"projection movie", "virtual single timepoint", "virtual 5D",
		"materialised single timepoint", "materialised 5D"
	};
	private static OpmZarrViewer instance;

	private final JTextField path = new JTextField(46);
	private final JComboBox<OpmZarrDataset> datasets = new JComboBox<OpmZarrDataset>();
	private final JComboBox<String> projections = new JComboBox<String>();
	private final JComboBox<String> selections = new JComboBox<String>();
	private final JComboBox<OpmZarrView.Operation> operations =
			new JComboBox<OpmZarrView.Operation>(OpmZarrView.Operation.values());
	private final JComboBox<String> openMode = new JComboBox<String>(OPEN_MODES);
	private final JSpinner timepoint = new JSpinner(new SpinnerNumberModel(1, 1, 1, 1));
	private final JCheckBox interpolate = new JCheckBox("bilinear interpolation", true);
	private final JCheckBox tryGpu = new JCheckBox("try GPU, fall back to CPU", true);
	private final JTextArea details = new JTextArea(9, 72);
	private final JLabel status = new JLabel("Choose a dataset or a parent folder.");
	private final ChannelOperationSettings configuredChannels = new ChannelOperationSettings();
	private volatile boolean busy;

	public OpmZarrViewer() {
		super(TITLE);
		if (instance != null) {
			WindowManager.toFront(instance);
			dispose();
			return;
		}
		instance = this;
		WindowManager.addWindow(this);
		configuredChannels.load();
		path.setText(Prefs.get(PATH_KEY, ""));
		buildWindow();
		if (!path.getText().trim().isEmpty()) scan();
	}

	private void buildWindow() {
		JPanel content = new JPanel(new BorderLayout(8, 8));
		content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		JPanel pathRow = new JPanel(new BorderLayout(5, 0));
		pathRow.add(path, BorderLayout.CENTER);
		JPanel pathButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		JButton browse = new JButton("Browse...");
		JButton scan = new JButton("Scan");
		pathButtons.add(browse);
		pathButtons.add(scan);
		pathRow.add(pathButtons, BorderLayout.EAST);
		content.add(pathRow, BorderLayout.NORTH);

		JPanel controls = new JPanel(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(3, 3, 3, 3);
		c.anchor = GridBagConstraints.WEST;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 0;
		addRow(controls, c, 0, "Dataset", datasets, null);
		addRow(controls, c, 1, "Open as", openMode, null);
		addRow(controls, c, 2, "Projection", projections, null);
		JButton order = new JButton("Channel setup...");
		addRow(controls, c, 3, "Channels / side", selections, order);
		addRow(controls, c, 4, "Runtime view", operations, null);
		addRow(controls, c, 5, "Timepoint", timepoint, null);

		JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		options.add(interpolate);
		options.add(tryGpu);
		c.gridx = 1; c.gridy = 6; c.gridwidth = 2; c.weightx = 1;
		controls.add(options, c);

		JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
		JButton open = new JButton("Open");
		JButton provenance = new JButton("Show provenance");
		actionRow.add(open);
		actionRow.add(provenance);
		c.gridy = 7;
		controls.add(actionRow, c);

		details.setEditable(false);
		details.setLineWrap(true);
		details.setWrapStyleWord(true);
		JScrollPane detailScroll = new JScrollPane(details);
		detailScroll.setBorder(BorderFactory.createTitledBorder("Dataset metadata"));
		detailScroll.setPreferredSize(new Dimension(760, 190));

		JPanel middle = new JPanel();
		middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));
		middle.add(controls);
		middle.add(detailScroll);
		content.add(middle, BorderLayout.CENTER);
		content.add(status, BorderLayout.SOUTH);

		browse.addActionListener(new ActionListener() {
			@Override public void actionPerformed(ActionEvent e) { browse(); }
		});
		scan.addActionListener(new ActionListener() {
			@Override public void actionPerformed(ActionEvent e) { scan(); }
		});
		datasets.addActionListener(new ActionListener() {
			@Override public void actionPerformed(ActionEvent e) { datasetChanged(); }
		});
		openMode.addActionListener(new ActionListener() {
			@Override public void actionPerformed(ActionEvent e) { updateEnabledControls(); }
		});
		order.addActionListener(new ActionListener() {
			@Override public void actionPerformed(ActionEvent e) { configureChannelSetup(); }
		});
		open.addActionListener(new ActionListener() {
			@Override public void actionPerformed(ActionEvent e) { openSelected(); }
		});
		provenance.addActionListener(new ActionListener() {
			@Override public void actionPerformed(ActionEvent e) { showProvenance(); }
		});

		add(content);
		pack();
		setResizable(true);
		Point location = Prefs.getLocation(LOC_KEY);
		if (location == null) setLocationRelativeTo(null); else setLocation(location);
		setVisible(true);
		updateEnabledControls();
	}

	private static void addRow(JPanel panel, GridBagConstraints c, int row,
			String label, java.awt.Component value, java.awt.Component extra) {
		c.gridy = row; c.gridx = 0; c.gridwidth = 1; c.weightx = 0;
		panel.add(new JLabel(label + ":"), c);
		c.gridx = 1; c.weightx = 1;
		panel.add(value, c);
		if (extra != null) {
			c.gridx = 2; c.weightx = 0;
			panel.add(extra, c);
		}
	}

	private void browse() {
		JFileChooser chooser = new JFileChooser(path.getText().trim());
		chooser.setDialogTitle("Choose an .ome.zarr dataset or a parent folder");
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
			path.setText(chooser.getSelectedFile().getAbsolutePath());
			scan();
		}
	}

	private void scan() {
		if (busy) return;
		final File selection = new File(path.getText().trim());
		Prefs.set(PATH_KEY, selection.getAbsolutePath());
		busy = true;
		status.setText("Scanning metadata...");
		Thread worker = new Thread(new Runnable() {
			@Override public void run() {
				final long start = System.nanoTime();
				final List<OpmZarrDataset> found = OpmZarrDataset.discover(selection);
				SwingUtilities.invokeLater(new Runnable() {
					@Override public void run() {
						datasets.removeAllItems();
						for (OpmZarrDataset dataset : found) datasets.addItem(dataset);
						busy = false;
						double seconds = (System.nanoTime() - start) / 1e9;
						status.setText("Found " + found.size() + " dataset(s) in "
								+ IJ.d2s(seconds, 3) + " s; metadata only, no pixels opened.");
						datasetChanged();
					}
				});
			}
		}, "OPM-Zarr-discovery");
		worker.setDaemon(true);
		worker.start();
	}

	private void datasetChanged() {
		OpmZarrDataset dataset = selectedDataset();
		projections.removeAllItems();
		selections.removeAllItems();
		selections.addItem(OpmZarrView.SELECT_CONFIGURED);
		if (dataset == null) { details.setText(""); return; }
		for (String projection : dataset.getAvailableProjections()) projections.addItem(projection);
		for (String selection : OpmZarrView.selectionOptions(dataset)) selections.addItem(selection);
		int maximum = Math.max(1, dataset.getTimepointCount());
		timepoint.setModel(new SpinnerNumberModel(1, 1, maximum, 1));
		details.setText(summary(dataset));
		details.setCaretPosition(0);
		updateEnabledControls();
	}

	private String summary(OpmZarrDataset dataset) {
		StringBuilder text = new StringBuilder();
		text.append(dataset.getRoot().getAbsolutePath()).append('\n');
		long[] dimensions = dataset.getVolumeDimensions();
		text.append("s0 [x,y,z,c,t] = ").append(java.util.Arrays.toString(dimensions)).append('\n');
		text.append("channels = ").append(dataset.getChannelLabels()).append('\n');
		text.append("projections = ").append(dataset.getAvailableProjections()).append('\n');
		text.append("state = ").append(dataset.isComplete() ? "complete" : "live/partial")
				.append(", committed T = ").append(dataset.getTimepointCount()).append('\n');
		OpmProvenance provenance = dataset.getProvenance();
		if (provenance != null) {
			text.append("voxel µm = ").append(java.util.Arrays.toString(dataset.voxelSizeUm()))
					.append(", frame interval = ").append(provenance.frameIntervalSeconds).append(" s\n");
			text.append("alignment = ").append(provenance.alignMatrix == null ? "not present" : "present")
					.append(", applied = ").append(provenance.alignApplied)
					.append(", default side = ").append(provenance.alignFlipHalf).append('\n');
		}
		if (!dataset.getWarnings().isEmpty()) text.append("warnings = ").append(dataset.getWarnings());
		return text.toString();
	}

	private void updateEnabledControls() {
		boolean projection = "projection movie".equals(openMode.getSelectedItem());
		projections.setEnabled(projection);
		String mode = String.valueOf(openMode.getSelectedItem());
		timepoint.setEnabled(mode.contains("single timepoint"));
	}

	/**
	 * Channel setup for whichever dataset is loaded, sized to what that dataset actually stores.
	 * <p>
	 * The dialog used to draw a fixed six slots offering a fixed three acquisition channels,
	 * which was wrong in both directions: a two-file dataset was asked about channels it does
	 * not have, and a four-file one had no way to reach its last two halves. Slots and options
	 * now both come from the loaded dataset's own channel labels.
	 * <p>
	 * Only the slots shown are written back. The rest of the shared Channel Operation order is
	 * left untouched, so configuring a narrow dataset here cannot quietly narrow what Deskew
	 * Batch will write for a wider one.
	 */
	private void configureChannelSetup() {
		OpmZarrDataset dataset = selectedDataset();
		if (dataset == null) { IJ.showMessage(TITLE, "Select a dataset first."); return; }
		List<String> labels = dataset.getChannelLabels();
		if (labels.isEmpty()) { IJ.showMessage(TITLE, "This dataset reports no channels."); return; }

		int slots = OpmZarrView.channelSetupSlots(dataset);
		String[] sources = OpmZarrView.channelSetupSources(dataset);
		String[] defaults = OpmZarrView.channelSetupDefaults(dataset, configuredChannels);

		OpmProvenance provenance = dataset.getProvenance();
		String storedMatrixSource = provenance == null || provenance.alignMatrixSource == null
				? "" : provenance.alignMatrixSource;

		GenericDialogPlus dialog = new GenericDialogPlus("OME-Zarr viewer channel setup");
		dialog.addMessage(dataset.getDisplayName() + " stores " + labels.size()
				+ (labels.size() == 1 ? " channel: " : " channels: ") + labels);
		List<String> unselected = OpmZarrView.channelsNotSelected(dataset, defaults);
		if (!unselected.isEmpty())
			dialog.addMessage("Not selected by the current setup: " + unselected);
		dialog.addChoice("flip side from metadata override",
				new String[] { BatchChannelOperation.FLIP_RIGHT, BatchChannelOperation.FLIP_LEFT },
				configuredChannels.flipHalf);
		for (int i = 0; i < slots; i++)
			dialog.addChoice("output channel " + (i + 1), sources, defaults[i]);
		dialog.addCheckbox("bilinear interpolation", interpolate.isSelected());
		dialog.addFileField("alignment matrix metadata override", storedMatrixSource, 44);
		dialog.showDialog();
		if (dialog.wasCanceled()) return;

		configuredChannels.flipHalf = dialog.getNextChoice();
		for (int i = 0; i < slots; i++) configuredChannels.channelOrder[i] = dialog.getNextChoice();
		configuredChannels.interpolate = dialog.getNextBoolean();
		interpolate.setSelected(configuredChannels.interpolate);
		configuredChannels.store();
		applyAlignmentOverride(dataset, storedMatrixSource, dialog.getNextString());
		selections.setSelectedItem(OpmZarrView.SELECT_CONFIGURED);
	}

	/**
	 * Write a recomputed rigid alignment into the dataset's metadata, on confirmation.
	 * <p>
	 * Because the canonical format keeps the halves unaligned and applies the matrix at read
	 * time, replacing it is a metadata edit: no pixel is rewritten and every view picks up the
	 * new numbers as soon as the descriptor is re-read. Nothing is written when the field is
	 * left as it was found, or when the file resolves to the matrix already stored.
	 */
	private void applyAlignmentOverride(OpmZarrDataset dataset, String before, String after) {
		String path = after == null ? "" : after.trim();
		if (path.isEmpty() || path.equals(before == null ? "" : before.trim())) return;
		File csv = new File(path);
		if (!csv.isFile()) {
			IJ.showMessage(TITLE, "Alignment matrix file not found:\n" + csv);
			return;
		}
		double[][] matrix = IO.loadMatrixFromFile(csv.getAbsolutePath());
		if (!OpmZarrDataset.isAlignmentMatrix(matrix)) {
			IJ.showMessage(TITLE, "Not a 2 x 3 rigid alignment matrix:\n" + csv
					+ "\n\nTwo rows of three comma separated values are required.");
			return;
		}
		OpmProvenance provenance = dataset.getProvenance();
		if (provenance != null && provenance.alignApplied)
			IJ.log("OPM viewer: this dataset records opm.alignApplied = true, so its runtime views"
					+ " will not use the replaced matrix.");
		YesNoCancelDialog confirm = new YesNoCancelDialog(this, "Overwrite alignment metadata",
				"Write this matrix into\n" + new File(dataset.getRoot(), ".zattrs") + "\n\n"
				+ "  [" + matrix[0][0] + ", " + matrix[0][1] + ", " + matrix[0][2] + "]\n"
				+ "  [" + matrix[1][0] + ", " + matrix[1][1] + ", " + matrix[1][2] + "]\n\n"
				+ "No pixel data is changed. The untouched .zattrs is kept\n"
				+ "as .zattrs.original the first time it is replaced.");
		if (!confirm.yesPressed()) return;
		try {
			OpmZarrDataset.writeAlignMatrix(dataset.getRoot(), matrix, csv.getAbsolutePath());
			IJ.log("OPM viewer: alignment metadata of " + dataset.getDisplayName()
					+ " replaced from " + csv.getAbsolutePath());
			reloadSelectedDataset();
		} catch (Throwable error) {
			showError(error);
		}
	}

	/** Re-read the selected descriptor in place so later views use the metadata just written. */
	private void reloadSelectedDataset() {
		int index = datasets.getSelectedIndex();
		OpmZarrDataset current = selectedDataset();
		if (index < 0 || current == null) return;
		OpmZarrDataset reloaded = OpmZarrDataset.read(current.getRoot());
		datasets.removeItemAt(index);
		datasets.insertItemAt(reloaded, index);
		datasets.setSelectedIndex(index);
	}

	private void openSelected() {
		if (busy) return;
		final OpmZarrDataset dataset = selectedDataset();
		if (dataset == null) { IJ.showMessage(TITLE, "Select a dataset first."); return; }
		final String mode = String.valueOf(openMode.getSelectedItem());
		final String projection = String.valueOf(projections.getSelectedItem());
		final int selectedTimepoint = ((Number) timepoint.getValue()).intValue() - 1;
		final OpmZarrView.Options options = options(dataset);

		if (mode.startsWith("materialised")) {
			int requestedT = mode.endsWith("5D") ? -1 : selectedTimepoint;
			long bytes;
			try { bytes = OpmZarrView.estimateMaterializedBytes(dataset, options, requestedT); }
			catch (RuntimeException error) { showError(error); return; }
			double mb = bytes / (1024.0 * 1024.0);
			YesNoCancelDialog confirm = new YesNoCancelDialog(this, "Materialise OME-Zarr",
					"Allocate approximately " + IJ.d2s(mb, 1) + " MB for the pixel stack?\n"
					+ "The GPU path transforms each complete Z volume in one call; CPU remains available.");
			if (!confirm.yesPressed()) return;
		}

		busy = true;
		status.setText("Opening " + dataset.getDisplayName() + "...");
		Thread worker = new Thread(new Runnable() {
			@Override public void run() {
				try {
					final long start = System.nanoTime();
					final ImagePlus image;
					if ("projection movie".equals(mode))
						image = OpmZarrView.openProjectionMovie(dataset, projection, options);
					else if ("virtual single timepoint".equals(mode))
						image = OpmZarrView.openVirtualVolume(dataset, options, selectedTimepoint);
					else if ("virtual 5D".equals(mode))
						image = OpmZarrView.openVirtualVolume(dataset, options, -1);
					else if ("materialised single timepoint".equals(mode))
						image = OpmZarrView.openMaterializedVolume(dataset, options, selectedTimepoint);
					else image = OpmZarrView.openMaterializedVolume(dataset, options, -1);
					SwingUtilities.invokeLater(new Runnable() {
						@Override public void run() {
							image.show();
							busy = false;
							status.setText("Opened in " + IJ.d2s((System.nanoTime() - start) / 1e9, 3) + " s.");
						}
					});
				} catch (final Throwable error) {
					SwingUtilities.invokeLater(new Runnable() {
						@Override public void run() { busy = false; showError(error); }
					});
				}
			}
		}, "OPM-Zarr-open");
		worker.setDaemon(true);
		worker.start();
	}

	private OpmZarrView.Options options(OpmZarrDataset dataset) {
		OpmZarrView.Options result = new OpmZarrView.Options();
		result.operation = (OpmZarrView.Operation) operations.getSelectedItem();
		result.interpolate = interpolate.isSelected();
		result.tryGpu = tryGpu.isSelected();
		result.flipHalf = configuredChannels.flipHalf;
		String selection = String.valueOf(selections.getSelectedItem());
		result.requestedChannels.addAll(OpmZarrView.channelsForSelection(dataset, selection, configuredChannels));
		return result;
	}

	private void showProvenance() {
		OpmZarrDataset dataset = selectedDataset();
		if (dataset == null) return;
		String text = dataset.getProvenance() == null ? summary(dataset) : dataset.getProvenance().toPrettyJson();
		IJ.log("OPM OME-Zarr provenance — " + dataset.getRoot().getAbsolutePath() + "\n" + text);
		IJ.showStatus("Provenance written to the Fiji Log window.");
	}

	private void showError(Throwable error) {
		String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
		status.setText("Open failed: " + message);
		IJ.log("OPM OME-Zarr Viewer failed: " + message);
		error.printStackTrace();
		IJ.showMessage(TITLE, message);
	}

	private OpmZarrDataset selectedDataset() {
		return (OpmZarrDataset) datasets.getSelectedItem();
	}

	@Override
	public void close() {
		Prefs.saveLocation(LOC_KEY, getLocation());
		Prefs.set(PATH_KEY, path.getText().trim());
		instance = null;
		super.close();
	}
}
