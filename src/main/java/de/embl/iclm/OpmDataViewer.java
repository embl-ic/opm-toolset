package de.embl.iclm;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.GUI;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij.gui.YesNoCancelDialog;
import ij.process.LUT;

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
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.BorderLayout;
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
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fiji window for projection movies, virtual volumes, runtime alignment and 5D assembly,
 * over a result that may still be being written, in either format this toolset produces.
 *
 * <p>Deskew Batch and Live Processing write canonical OME-Zarr, deflated TIFF, or both. The
 * two are listed together and opened the same way, because almost every question this window
 * asks - which region, which channels, which Z, which time points, and whether to follow a run
 * still in progress - is a question about which planes to read, and neither format answers it
 * differently. An OME-Zarr always stores unflipped camera halves and an alignment matrix. A
 * composed TIFF normally opens exactly as written, but separate whole-width
 * {@code _ChannelNNNN} TIFF series may also be split, flipped, aligned and overlaid lazily.
 * That alternative is local to one viewer instance and never rewrites the source files.
 *
 * <p>This was two commands. The live viewer was a copy of this one with the region controls
 * removed and a watch added, which meant every fix had to be made twice and the two drifted
 * apart in what they could open. Live update is now a checkbox here.
 *
 * <p>Every virtual view this window opens, and every live-preview window, is this window's:
 * there is one per dataset and view - one volume, one maxZ, one maxY and so on - and opening
 * another of the same replaces it where it stood. Changing {@code Channels / side}, the
 * {@code Runtime view} or the channel setup rebuilds those windows with the new composition,
 * so a run still growing them never goes on appending to a composition the controls no longer
 * describe. The region is different: it describes the <em>next</em> Open, as before. Closing
 * this window closes its views. A materialised view is a snapshot in memory, possibly with
 * work on it, and is never replaced, rebuilt or closed from here.
 *
	 * <p>The one materialising shortcut here is deliberately view-oriented: turn the active ROI
	 * into a bounded in-memory hyperstack. Format conversion and persistent exports remain with
	 * the other conversions under {@link FormatConversion}.
 */
public class OpmDataViewer extends PlugInFrame {

	private static final long serialVersionUID = 1L;
	private static final String TITLE = "OPM Data Viewer";
	private static final String PATH_KEY = "opm.zarrViewer.path";
	private static final String GPU_KEY = "opm.zarrViewer.tryGpu";
	private static final String LOC_KEY = "opm.zarrViewer.loc";
	private static final String POLL_KEY = "opm.zarrViewer.pollSeconds";
	/** What the last region export chose, so the next one starts where that one left off. */
	private static final String EXPORT_FORMAT_KEY = "opm.zarrViewer.exportFormat";
	private static final String EXPORT_FOLDER_KEY = "opm.zarrViewer.exportFolder";
	private static final int DEFAULT_POLL_SECONDS = 5;

	/**
	 * What Open builds, in the order the control offers it.
	 * <p>
	 * An enum rather than the label strings the dispatch used to compare. The labels are a
	 * display decision and have been changed once already; while they were the control flow,
	 * renaming one silently re-routed five comparisons spread over the file.
	 */
	enum OpenMode {
		VOLUME_ALL("volume all timepoints"),
		VOLUME_SINGLE("volume single timepoint"),
		PROJECTION("projection movie");

		private final String label;
		OpenMode(String label) { this.label = label; }
		@Override public String toString() { return label; }
	}

	/**
	 * One openable result, in whichever format it was written.
	 *
	 * <p>The two formats are not two viewers. A canonical OME-Zarr stores unflipped halves and
	 * a matrix, so the flip, the alignment and the channel selection are still open questions
	 * at view time. A deflated TIFF usually holds finished pixels, while a set of separate
	 * whole-width channel series can still be interpreted virtually. Everything else - which
	 * region, which channels, which Z, which time points, and
	 * following a run that is still writing - is the same question of *which planes to read*,
	 * and is answered the same way here.
	 *
	 * <p>Exactly one of the two references is set. The format is part of the label because a
	 * folder can hold both, and which one is open decides what half the controls can do.
	 */
	static final class Entry {
		final OmeZarrDataset zarr;
		/** The series the entry is anchored on: the lowest acquisition channel of its family. */
		final TiffResultDataset tiff;
		/**
		 * Every {@code _ChannelNNNN} series of one acquisition, ascending; one member for a
		 * result that is not part of a family.
		 * <p>
		 * One acquisition writing two files per time point leaves two TIFF series in one folder,
		 * and listing them as two datasets asked the user to open them one at a time - which is
		 * also two windows of the same kind fighting over one slot in the view registry. They
		 * are the channels of one acquisition, so they are one entry, and the composition
		 * controls say which of their halves come out (user's request, 2026-09-24).
		 */
		final List<TiffResultDataset> series;

		Entry(OmeZarrDataset zarr) {
			this.zarr = zarr;
			this.tiff = null;
			this.series = Collections.emptyList();
		}

		Entry(TiffResultDataset tiff) { this(Collections.singletonList(tiff)); }

		Entry(List<TiffResultDataset> family) {
			this.zarr = null;
			this.series = Collections.unmodifiableList(new ArrayList<TiffResultDataset>(family));
			this.tiff = this.series.get(0);
		}

		boolean isTiff() { return tiff != null; }
		boolean isFamily() { return series.size() > 1; }
		File root() { return zarr != null ? zarr.getRoot() : tiff.getRoot(); }

		String displayName() {
			if (zarr != null) return zarr.getDisplayName();
			return isFamily() ? TIFF_CHANNEL_TOKEN.matcher(tiff.getDisplayName())
					.replaceFirst("_Channel####") : tiff.getDisplayName();
		}

		/** The shortest of the family, since only a time point every series has can be shown. */
		int timepointCount() {
			if (zarr != null) return zarr.getTimepointCount();
			int count = Integer.MAX_VALUE;
			for (TiffResultDataset dataset : series) count = Math.min(count, dataset.getTimepointCount());
			return count == Integer.MAX_VALUE ? 0 : count;
		}

		List<String> projections() {
			return zarr != null ? zarr.getAvailableProjections() : tiff.getAvailableProjections();
		}

		@Override public String toString() {
			if (!isTiff()) return displayName() + "   [OME-Zarr]";
			return displayName() + (isFamily()
					? "   [TIFF, " + series.size() + " channel series]" : "   [TIFF]");
		}
	}

	/** The window reserved for an acquisition's automatic live preview; menu windows are independent. */
	private static OpmDataViewer liveInstance;

	private final JTextField path = new JTextField(46);
	private final JComboBox<Entry> datasets = new JComboBox<Entry>();
	private final JComboBox<String> projections = new JComboBox<String>();
	private final JComboBox<String> selections = new JComboBox<String>();
	private final JComboBox<OmeZarrView.Operation> operations =
			new JComboBox<OmeZarrView.Operation>(OmeZarrView.Operation.values());
	private final JComboBox<OpenMode> openMode = new JComboBox<OpenMode>(OpenMode.values());
	private final JCheckBox virtual = new JCheckBox("Virtual", true);
	private final JSpinner timepoint = new JSpinner(new SpinnerNumberModel(1, 1, 1, 1));
	private final JSlider timeSlider = new JSlider(1, 1, 1);
	private final JLabel timeFirst = new JLabel("1");
	private final JLabel timeLast = new JLabel("1");
	/** Set in the channel setup dialog; both only affect the runtime channel transform. */
	private boolean tryGpu = true;
	/** Format and folder of the last region export; see {@link #exportRegion}. */
	private String exportFormat = Parameter.FORMAT_TIFF;
	private String exportFolder = "";
	private final JTextArea details = new JTextArea(9, 72);
	private final JLabel status = new JLabel("Choose a dataset or a parent folder.");
	private final ChannelOperationSettings configuredChannels = new ChannelOperationSettings();
	/** TIFF virtual setups belong to this viewer window and are never written to the TIFFs or Prefs. */
	private final Map<String, TiffVirtualSetup> tiffChannelSetups =
			new LinkedHashMap<String, TiffVirtualSetup>();
	private volatile boolean busy;
	private boolean syncingTimepoint;
	/** Sub-volume the next view opened is restricted to; null means the whole volume. */
	private OmeZarrView.Bounds region;
	private final JLabel regionLabel = new JLabel("whole volume");
	/** Held so the greying rule can reach it; the row is built before the rule first runs. */
	private JButton channelSetupButton;
	/** One wheel notch is worth ten time points; the spinner arrows still step one. */
	private static final int WHEEL_TIMEPOINTS = 10;

	// --- live watching -------------------------------------------------------------
	private final JCheckBox live = new JCheckBox("Live update", false);
	private final JSpinner pollSeconds = new JSpinner(
			new SpinnerNumberModel(DEFAULT_POLL_SECONDS, 1, 120, 1));
	private volatile Thread watcher;
	private volatile boolean watching;
	private volatile long seenAttrsModified;
	private volatile int seenCommitted;
	/** The views this window answers for: grown, rebuilt and closed with it. See {@link ViewRegistry}. */
	private final ViewRegistry views = new ViewRegistry();
	/**
	 * Non-zero while this class itself is setting the composition controls - filling them for a
	 * dataset, or applying a run's layout. Their listeners would otherwise take each of those
	 * steps for the user changing the composition and rebuild every open view for it.
	 */
	private int composingControls;
	/** A rebuild asked for while the window was busy, waiting for it to finish. */
	private boolean recomposeQueued;
	/** Whether any request folded into the queued rebuild had to rebuild regardless. */
	private boolean recomposeForced;
	private volatile boolean suppressDatasetChanged;
	/** Set by {@link #showLive}; consumed by the next scan that finishes. */
	private LiveRequest liveRequest;
	private int liveRetries;
	/** Re-scans spent getting a live request onto its own folder; see {@link #applyLiveRequest}. */
	private int liveRescans;
	/** Roughly five minutes at the default poll interval, then the window stops waiting. */
	private static final int MAX_LIVE_RETRIES = 60;
	/** A live request may redirect the scan this few times before it is given up on. */
	private static final int MAX_LIVE_RESCANS = 3;
	/** Guards the cleanup, which can be reached twice; see {@link #release()}. */
	private final java.util.concurrent.atomic.AtomicBoolean released =
			new java.util.concurrent.atomic.AtomicBoolean(false);

	public OpmDataViewer() {
		this(true);
		/* Only this constructor is the menu command. The other one is how a live run raises
		 * the window for its preview, which is not a command the user started. */
		Debug.commandStarted ( "OPM Data Viewer" );
	}

	/**
	 * @param scanRememberedFolder	: false when the window is being raised for a live preview.
	 * 								  Scanning the folder browsed last time would occupy the window
	 * 								  with somebody else's datasets - possibly a long walk - while
	 * 								  the run's own folder waited behind it.
	 */
	private OpmDataViewer(boolean scanRememberedFolder) {
		super(TITLE);
		WindowManager.addWindow(this);
		configuredChannels.load();
		path.setText(Prefs.get(PATH_KEY, ""));
		tryGpu = Prefs.get(GPU_KEY, true);
		exportFormat = Parameter.isOutputFormat(Prefs.get(EXPORT_FORMAT_KEY, Parameter.FORMAT_TIFF))
				? Prefs.get(EXPORT_FORMAT_KEY, Parameter.FORMAT_TIFF) : Parameter.FORMAT_TIFF;
		exportFolder = Prefs.get(EXPORT_FOLDER_KEY, "");
		pollSeconds.setValue(Integer.valueOf(clampPoll(
				(int) Prefs.get(POLL_KEY, DEFAULT_POLL_SECONDS))));
		buildWindow();
		if (scanRememberedFolder && !path.getText().trim().isEmpty()) scan();
	}

	private void buildWindow() {
		setBackground(Parameter.frameColor);
		JPanel content = new JPanel(new BorderLayout(8, 8));
		content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		content.setBackground(Parameter.frameColor);

		JPanel pathRow = new JPanel(new BorderLayout(5, 0));
		pathRow.setBackground(Parameter.frameColor);
		path.setToolTipText("An .ome.zarr folder, a folder containing several, or a TIFF "
				+ "result folder - either with deskew/maxZ/... sub-folders or with every view "
				+ "in one folder. Drop one here from the file manager.");
		enableDatasetDrop();
		pathRow.add(path, BorderLayout.CENTER);
		JPanel pathButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		pathButtons.setBackground(Parameter.frameColor);
		JButton browse = new JButton("Browse...");
		JButton scan = new JButton("Scan");
		pathButtons.add(browse);
		pathButtons.add(scan);
		pathRow.add(pathButtons, BorderLayout.EAST);
		content.add(pathRow, BorderLayout.NORTH);

		JPanel controls = new JPanel(new GridBagLayout());
		controls.setBackground(Parameter.frameColor);
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(3, 3, 3, 3);
		c.anchor = GridBagConstraints.WEST;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 0;
		addRow(controls, c, 0, "Dataset", datasets, null);
		virtual.setBackground(Parameter.frameColor);
		addRow(controls, c, 1, "Open as", openMode, virtual);
		addRow(controls, c, 2, "Projection", projections, null);
		JButton order = new JButton("Channel setup...");
		channelSetupButton = order;
		selections.setRenderer(new WrittenCompositionRenderer(false));
		operations.setRenderer(new WrittenCompositionRenderer(true));
		addRow(controls, c, 3, "Channels / side", selections, order);
		addRow(controls, c, 4, "Runtime view", operations, null);
		JPanel timeRow = new JPanel(new BorderLayout(6, 0));
		timeRow.setBackground(Parameter.frameColor);
		timeSlider.setBackground(Parameter.frameColor);
		timeSlider.setPaintTicks(true);
		timeFirst.setBackground(Parameter.frameColor);
		timeLast.setBackground(Parameter.frameColor);
		JPanel timeEnd = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		timeEnd.setBackground(Parameter.frameColor);
		timeEnd.add(timeLast);
		timeEnd.add(timepoint);
		timeRow.add(timeFirst, BorderLayout.WEST);
		timeRow.add(timeSlider, BorderLayout.CENTER);
		timeRow.add(timeEnd, BorderLayout.EAST);
		linkTimepointControls();
		addRow(controls, c, 5, "Timepoint", timeRow, null);

		JPanel regionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
		regionRow.setBackground(Parameter.frameColor);
		JButton regionButton = new JButton("Set region...");
		JButton regionReset = new JButton("Whole volume");
		JButton materialiseRoi = new JButton("Materialise with ROI...");
		JButton exportRegion = new JButton("Export...");
		exportRegion.setToolTipText("Write this region straight to disk as TIFF, OME-Zarr or both, "
				+ "without opening it as an image first.");
		regionLabel.setBackground(Parameter.frameColor);
		regionRow.add(regionButton);
		regionRow.add(regionReset);
		regionRow.add(regionLabel);
		regionRow.add(materialiseRoi);
		regionRow.add(exportRegion);
		addRow(controls, c, 6, "Region", regionRow, null);

		JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
		actionRow.setBackground(Parameter.frameColor);
		JButton open = new JButton("Open");
		JButton provenance = new JButton("Show info.");
		actionRow.add(open);
		actionRow.add(provenance);
		live.setBackground(Parameter.frameColor);
		live.setToolTipText("Follow the dataset while it is still being written, and extend "
				+ "every virtual view this window opened as time points are committed.");
		JLabel liveHeading = new JLabel("     Live");
		liveHeading.setBackground(Parameter.frameColor);
		JLabel pollLabel = new JLabel("source file check every");
		pollLabel.setBackground(Parameter.frameColor);
		actionRow.add(liveHeading);
		actionRow.add(live);
		actionRow.add(pollLabel);
		actionRow.add(pollSeconds);
		actionRow.add(new JLabel("s"));
		c.gridx = 1; c.gridy = 7; c.gridwidth = 2; c.weightx = 1;
		controls.add(actionRow, c);

		details.setEditable(false);
		details.setBackground(Parameter.frameColor);
		details.setLineWrap(true);
		details.setWrapStyleWord(true);
		JScrollPane detailScroll = new JScrollPane(details);
		detailScroll.setBorder(BorderFactory.createTitledBorder("Dataset metadata"));
		detailScroll.setPreferredSize(new Dimension(760, 190));
		detailScroll.setMinimumSize(new Dimension(320, 80));

		/* BorderLayout, not BoxLayout: BoxLayout hands out the height by preferred size, so
		 * shrinking the window let the controls take everything and the metadata panel
		 * disappeared entirely. Here the controls keep their height and the panel absorbs the
		 * rest, however the window is resized. */
		JPanel middle = new JPanel(new BorderLayout(0, 6));
		middle.setBackground(Parameter.frameColor);
		middle.add(controls, BorderLayout.NORTH);
		middle.add(detailScroll, BorderLayout.CENTER);
		content.add(middle, BorderLayout.CENTER);
		status.setBackground(Parameter.frameColor);
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
		/* The open views follow the composition controls; see recomposeOpenViews. */
		ActionListener composition = new ActionListener() {
			@Override public void actionPerformed(ActionEvent e) {
				if (composingControls == 0) recomposeOpenViews();
			}
		};
		selections.addActionListener(composition);
		operations.addActionListener(composition);
		/* The projection chosen decides which view the composition applies to, and an X or Y
		 * projection of a TIFF cannot carry a half split - so the controls grey with it. */
		projections.addActionListener(new ActionListener() {
			@Override public void actionPerformed(ActionEvent e) {
				if (composingControls == 0) updateEnabledControls();
			}
		});
		openMode.addActionListener(new ActionListener() {
			@Override public void actionPerformed(ActionEvent e) {
				defaultToVirtual();
				updateEnabledControls();
			}
		});
		live.addActionListener(new ActionListener() {
			@Override public void actionPerformed(ActionEvent e) {
				if (live.isSelected()) startWatching(); else stopWatching();
			}
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
		regionButton.addActionListener(new ActionListener() {
			@Override public void actionPerformed(ActionEvent e) { chooseRegion(); }
		});
		regionReset.addActionListener(new ActionListener() {
			@Override public void actionPerformed(ActionEvent e) {
				region = null;
				updateRegionLabel();
				status.setText("Region cleared; views opened from now on cover the whole volume.");
			}
		});
		materialiseRoi.addActionListener(new ActionListener() {
			@Override public void actionPerformed(ActionEvent e) { materialiseActiveRoi(); }
		});
		exportRegion.addActionListener(new ActionListener() {
			@Override public void actionPerformed(ActionEvent e) { exportRegion(); }
		});

		/* Fiji does not call close() when it quits, so the cleanup has to hang off the one
		 * event that always arrives. See release(). */
		addWindowListener(new WindowAdapter() {
			@Override public void windowClosed(WindowEvent event) { release(); }
		});

		Debug.decorate ( this, content );	// before pack(); see Debug.decorate

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

	/**
	 * Accept a dataset dragged from the file manager onto the path field or the summary.
	 * <p>
	 * Whatever is dropped is resolved to its enclosing {@code .ome.zarr} first, so dropping a
	 * chunk, an {@code s0} folder or {@code .zattrs} works and, more usefully, never starts a
	 * recursive walk through a few hundred thousand chunk files that would find nothing.
	 */
	private void enableDatasetDrop() {
		DropTargetAdapter listener = new DropTargetAdapter() {
			@Override
			public void drop(DropTargetDropEvent event) {
				try {
					event.acceptDrop(DnDConstants.ACTION_COPY);
					File dropped = firstDroppedFile(event.getTransferable());
					event.dropComplete(dropped != null);
					if (dropped == null) return;
					File folder = OmeZarrDataset.resolveDatasetFolder(dropped);
					if (folder == null) return;
					path.setText(folder.getAbsolutePath());
					scan();
				} catch (Throwable failure) {
					event.dropComplete(false);
					status.setText("Could not read the dropped item.");
					IJ.log(TITLE + ": could not read the dropped item: " + failure);
				}
			}
		};
		new DropTarget(path, DnDConstants.ACTION_COPY, listener, true);
		new DropTarget(details, DnDConstants.ACTION_COPY, listener, true);
	}

	/** The first real file in a drop, from either the file-list or the uri-list flavour. */
	private static File firstDroppedFile(Transferable payload) throws Exception {
		if (payload.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
			Object transferred = payload.getTransferData(DataFlavor.javaFileListFlavor);
			if (transferred instanceof List) {
				for (Object item : (List<?>) transferred)
					if (item instanceof File) return (File) item;
			}
			return null;
		}
		// Linux and some macOS sources offer a newline separated URI list instead.
		DataFlavor uriList = new DataFlavor("text/uri-list;class=java.lang.String");
		if (!payload.isDataFlavorSupported(uriList)) return null;
		for (String line : String.valueOf(payload.getTransferData(uriList)).split("\\r?\\n")) {
			String uri = line.trim();
			if (uri.isEmpty() || uri.startsWith("#")) continue;
			return new File(new java.net.URI(uri));
		}
		return null;
	}

	/**
	 * Keep the time slider and the spinner showing one value, and give both a wheel step.
	 * <p>
	 * A single time point is a long way through a 500 frame acquisition, so the wheel moves ten
	 * at a time; the spinner arrows still step one. The guard stops each control's change event
	 * from driving the other back and forth.
	 */
	private void linkTimepointControls() {
		timeSlider.addChangeListener(new ChangeListener() {
			@Override public void stateChanged(ChangeEvent event) {
				if (syncingTimepoint) return;
				syncingTimepoint = true;
				try { timepoint.setValue(Integer.valueOf(timeSlider.getValue())); }
				finally { syncingTimepoint = false; }
			}
		});
		timepoint.addChangeListener(new ChangeListener() {
			@Override public void stateChanged(ChangeEvent event) {
				if (syncingTimepoint) return;
				syncingTimepoint = true;
				try { timeSlider.setValue(((Number) timepoint.getValue()).intValue()); }
				finally { syncingTimepoint = false; }
			}
		});
		MouseWheelListener wheel = new MouseWheelListener() {
			@Override public void mouseWheelMoved(MouseWheelEvent event) {
				if (!timeSlider.isEnabled()) return;
				stepTimepoint(event.getWheelRotation() * WHEEL_TIMEPOINTS);
				event.consume();
			}
		};
		timeSlider.addMouseWheelListener(wheel);
		timepoint.addMouseWheelListener(wheel);
	}

	private void stepTimepoint(int by) {
		int maximum = timeSlider.getMaximum();
		int wanted = Math.max(1, Math.min(maximum, timeSlider.getValue() + by));
		timeSlider.setValue(wanted);
	}

	/**
	 * Re-scale the time controls to a dataset, keeping the position where it still exists.
	 * <p>
	 * Tick spacing is chosen so a long acquisition gets roughly ten labelled divisions rather
	 * than several hundred unreadable ones.
	 */
	private void setTimepointRange(int timepoints) {
		int maximum = Math.max(1, timepoints);
		int wanted = Math.min(Math.max(1, ((Number) timepoint.getValue()).intValue()), maximum);
		// A 1, 2, 5, 10, 20, 50 ... progression, so the labels are round numbers.
		final int[] mantissa = { 1, 2, 5 };
		int major = 1;
		for (int step = 1; maximum / major > 10; step++)
			major = mantissa[step % 3] * (int) Math.pow(10, step / 3);
		syncingTimepoint = true;
		try {
			timepoint.setModel(new SpinnerNumberModel(wanted, 1, maximum, 1));
			timeSlider.setMinimum(1);
			timeSlider.setMaximum(maximum);
			timeSlider.setMajorTickSpacing(major);
			timeSlider.setMinorTickSpacing(Math.max(1, major / 5));
			timeSlider.setValue(wanted);
			timeLast.setText(String.valueOf(maximum));
			timeSlider.setEnabled(maximum > 1);
		} finally {
			syncingTimepoint = false;
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
				/* Both formats, from the one folder. A run that wrote TIFF and OME-Zarr
				 * together lists both, and which is opened is then the user's choice rather
				 * than ours. */
				final List<Entry> found = new ArrayList<Entry>();
				int zarrCount = 0;
				int tiffCount = 0;
				try {
					for (OmeZarrDataset dataset : OmeZarrDataset.discover(selection)) {
						found.add(new Entry(dataset));
						zarrCount++;
					}
				} catch (Throwable unreadable) {
					IJ.log("OPM viewer: OME-Zarr scan failed: " + unreadable);
				}
				try {
					List<TiffResultDataset> results = TiffResultDataset.discover(selection);
					tiffCount = results.size();
					found.addAll(groupTiffFamilies(results));
				} catch (Throwable unreadable) {
					IJ.log("OPM viewer: TIFF scan failed: " + unreadable);
				}
				final int zarrFound = zarrCount;
				final int tiffFound = tiffCount;
				SwingUtilities.invokeLater(new Runnable() {
					@Override public void run() {
						datasets.removeAllItems();
						for (Entry entry : found) datasets.addItem(entry);
						busy = false;
						double seconds = (System.nanoTime() - start) / 1e9;
						/* After datasetChanged, not before: it can write to the status line
						 * itself now, and the result of the scan is the more useful message. */
						datasetChanged();
						status.setText("Found " + zarrFound + " OME-Zarr and " + tiffFound
								+ " TIFF result(s) in " + IJ.d2s(seconds, 3)
								+ " s; metadata only, no pixels opened.");
						/* Last, so a live request can replace that message with its own: it
						 * may be waiting for a run that has not written anything yet. The
						 * folder that was actually scanned goes with it - it is not always
						 * the one the request asked for. */
						applyLiveRequest(selection);
					}
				});
			}
		}, "OPM-Zarr-discovery");
		worker.setDaemon(true);
		worker.start();
	}

	private void datasetChanged() {
		/* Reached both when the user picks another dataset and when a growth tick swaps a
		 * freshly read descriptor into the same slot. The second must not tear down the
		 * watch that caused it, nor reset the controls the user is looking at. */
		if (suppressDatasetChanged) return;
		/* Unticked as well as stopped: a ticked box over a watch that is no longer running
		 * said the window was following a dataset it had stopped following. */
		if (watching) { stopWatching(); live.setSelected(false); }
		defaultToVirtual();
		Entry entry = selectedEntry();
		composingControls++;
		try {
			projections.removeAllItems();
			selections.removeAllItems();
			selections.addItem(OmeZarrView.SELECT_CONFIGURED);
			/* The box belongs to the extent it was drawn against. Carrying it to another dataset
			 * would silently clamp it to a different one, which is worse than asking for it
			 * again; the status line says so rather than letting the control change itself. */
			boolean hadRegion = region != null;
			region = null;
			updateRegionLabel();
			if (hadRegion) status.setText("Region cleared: it belonged to the previous dataset.");
			if (entry == null) { details.setText(""); updateEnabledControls(); return; }
			for (String projection : entry.projections()) projections.addItem(projection);
			if (!entry.isTiff()) {
				for (String selection : OmeZarrView.selectionOptions(entry.zarr))
					selections.addItem(selection);
				/* A store holds unflipped halves whatever its run wrote, so without this a
				 * whole-image acquisition opened as two halves laid over each other. */
				DeskewChannelView layout = DeskewChannelView.of(entry.zarr.getProvenance());
				if (layout != null) {
					applyChannelView(layout);
					status.setText((hadRegion ? "Region cleared: it belonged to the previous dataset.  " : "")
							+ "Channels set to how this dataset was deskewed: " + layout.describedAs + ".");
				}
			} else if (tiffComposable(entry)) {
				for (String selection : OmeZarrView.selectionOptions(tiffChannelLabels(entry.series)))
					selections.addItem(selection);
				/* As written is the starting point, and for a family that is each series as one
				 * full-width channel: the halves side by side are the stored plane, so this
				 * costs nothing and shows the acquisition's channels together. Every other view
				 * is one control away. */
				selections.setSelectedItem(OmeZarrView.SELECT_ALL);
				operations.setSelectedItem(OmeZarrView.Operation.SIDE_BY_SIDE);
				describeTiffComposition(entry, hadRegion);
			}
			setTimepointRange(tiffPairedTimepoints(entry));
			details.setText(entry.isTiff() ? summary(entry) : summary(entry.zarr));
			details.setCaretPosition(0);
			updateEnabledControls();
		} finally {
			composingControls--;
		}
	}

	/**
	 * Say what a TIFF family will open as, and fall back to one series when it cannot combine.
	 * <p>
	 * Two series of one acquisition normally share their extent and calibration exactly, being
	 * two files of one time point; when they do not - a changed camera ROI between runs writing
	 * into one folder - the honest thing is to open the anchor alone and say so, rather than
	 * offer a composition whose Open fails.
	 */
	private void describeTiffComposition(Entry entry, boolean hadRegion) {
		String lead = hadRegion ? "Region cleared: it belonged to the previous dataset.  " : "";
		String problem = tiffCompositionProblem(entry);
		if (problem == null) {
			if (entry.isFamily())
				status.setText(lead + "Listed as one acquisition: " + entry.series.size()
						+ " _Channel#### series, opening as written. Channels / side and Runtime"
						+ " view split and transform them as they are read.");
			return;
		}
		selections.setSelectedItem(ChannelOperationSettings.sourceKey(
				acquisitionChannel(entry.tiff), true).replace("-left", ""));
		status.setText(lead + "Showing " + entry.tiff.getDisplayName() + " alone: " + problem);
	}

	/** Why this family's series cannot share one image, or null when they can. */
	private String tiffCompositionProblem(Entry entry) {
		if (!entry.isFamily()) return null;
		try {
			TiffResultView.outputChannelLabels(entry.tiff, selectedViewKey(), tiffOptions());
			return null;
		} catch (Throwable incompatible) {
			return incompatible.getMessage() == null
					? incompatible.getClass().getSimpleName() : incompatible.getMessage();
		}
	}

	/**
	 * How many time points the selected entry can actually show.
	 * <p>
	 * For a family that is the time points every selected series has: a volume paired from a
	 * time point one channel has not written yet would be one channel of somebody else's.
	 */
	private int tiffPairedTimepoints(Entry entry) {
		if (entry == null) return 0;
		if (!entry.isTiff()) return entry.timepointCount();
		try {
			return TiffResultView.availableFrameCount(entry.tiff, selectedViewKey(), tiffOptions());
		} catch (Throwable unreadable) {
			return entry.timepointCount();
		}
	}

	/**			Set the composition controls to a deskew run's layout
	 * <p>		The flip side, interpolation and slot order go into this window's channel setup
	 * 			in memory only. They reach the shared preferences only if the user then accepts
	 * 			{@code Channel setup...}, and even then only for the slots that dialog shows.
	 */
	private void applyChannelView(DeskewChannelView layout) {
		if (layout == null) return;
		composingControls++;
		try {
			if (!layout.channelOrder.isEmpty()) {
				String[] order = configuredChannels.channelOrder;
				for (int i = 0; i < order.length; i++)
					order[i] = i < layout.channelOrder.size()
							? layout.channelOrder.get(i) : BatchChannelOperation.SKIP_CHANNEL;
			}
			configuredChannels.flipHalf = layout.flipHalf;
			configuredChannels.interpolate = layout.interpolate;
			selections.setSelectedItem(layout.selection);
			operations.setSelectedItem(layout.operation);
		} finally {
			composingControls--;
		}
	}

	/**
	 * What a TIFF result can tell about itself, which is less than an OME-Zarr and is the point.
	 *
	 * <p>There is no provenance record, no alignment matrix and no stored channel identity: the
	 * file holds finished pixels and an ImageJ description. Saying so is more useful than
	 * leaving the panel that reports those things empty.
	 */
	/** The summary panel's line break; the JTextArea it fills is not platform dependent. */
	private static final String BREAK = "\n";

	/** One acquisition's summary: every _Channel#### series it was listed as, in order. */
	static String summary(Entry entry) {
		if (!entry.isFamily()) return summary(entry.tiff);
		StringBuilder text = new StringBuilder("one acquisition listed as ")
				.append(entry.series.size()).append(" _Channel#### series").append(BREAK).append(BREAK);
		for (TiffResultDataset dataset : entry.series) text.append(summary(dataset)).append(BREAK);
		return text.toString();
	}

	static String summary(TiffResultDataset dataset) {
		StringBuilder text = new StringBuilder();
		text.append(dataset.getRoot() == null ? "" : dataset.getRoot().getAbsolutePath());
		text.append("\n");
		text.append("name = ").append(dataset.getDisplayName()).append("\n");
		text.append("format = deflated TIFF, one file per time point per view\n");
		for (String key : allViews(dataset)) {
			TiffResultDataset.View view = dataset.getView(key);
			text.append(key).append(" = ").append(view.frameCount()).append(" time point(s)");
			try {
				TiffResultDataset.Layout layout = view.getLayout();
				text.append(", ").append(layout.width).append("x").append(layout.height)
						.append(", C=").append(layout.channels).append(", Z=").append(layout.slices)
						.append(", voxel ").append(IJ.d2s(layout.pixelWidth, 4)).append("/")
						.append(IJ.d2s(layout.pixelHeight, 4)).append("/")
						.append(IJ.d2s(layout.pixelDepth, 4)).append(" ").append(layout.unit);
			} catch (Throwable unreadable) {
				text.append(", layout unreadable: ").append(unreadable);
			}
			text.append("\n");
		}
		text.append("runtime channel representation = as written by default; matching single-channel "
				+ "_ChannelNNNN whole-width series can be split, flipped and rigidly aligned in memory\n");
		text.append("region, channel/Z/time ranges and live growth = supported\n");
		return text.toString();
	}
	/** The volume first, then the projections, which is the order they are offered in. */
	private static List<String> allViews(TiffResultDataset dataset) {
		List<String> keys = new ArrayList<String>();
		if (dataset.hasVolume()) keys.add(TiffResultDataset.VOLUME);
		keys.addAll(dataset.getAvailableProjections());
		return keys;
	}

	static String summary(OmeZarrDataset dataset) {
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
					.append(", frame interval = ").append(dataset.frameIntervalSeconds()).append(" s\n");
			if (provenance.alignMatrices != null && !provenance.alignMatrices.isEmpty()) {
				text.append("alignment reference = ").append(provenance.alignReference).append('\n');
				text.append("source-specific alignment matrices = ")
						.append(provenance.alignMatrices.keySet()).append('\n');
				text.append("alignment matrix source = ")
						.append(blank(provenance.alignMatrixSource) ? "not recorded" : provenance.alignMatrixSource)
						.append('\n');
			} else if (provenance.alignMatrix == null) {
				text.append("alignment matrix = not present\n");
			} else {
				text.append("alignment matrix (2x3) = ")
						.append(java.util.Arrays.deepToString(provenance.alignMatrix)).append('\n');
				text.append("alignment matrix source = ")
						.append(blank(provenance.alignMatrixSource) ? "not recorded" : provenance.alignMatrixSource)
						.append('\n');
				text.append("alignment matrix modified UTC = ").append(alignmentModifiedUtc(provenance))
						.append('\n');
			}
			text.append("alignment applied to stored pixels = ").append(provenance.alignApplied)
					.append(", default transformed side = ").append(provenance.alignFlipHalf).append('\n');
			text.append("alignment convention = ").append(provenance.alignMatrixConvention).append('\n');
		}
		File attrs = new File(dataset.getRoot(), ".zattrs");
		if (attrs.isFile()) text.append("dataset metadata modified UTC = ")
				.append(OpmProvenance.utcTimestamp(attrs.lastModified())).append('\n');
		if (!dataset.getWarnings().isEmpty()) text.append("warnings = ").append(dataset.getWarnings());
		return text.toString();
	}

	private static String alignmentModifiedUtc(OpmProvenance provenance) {
		if (!blank(provenance.alignMatrixModifiedUtc)) return provenance.alignMatrixModifiedUtc;
		if (!blank(provenance.alignMatrixSource)) {
			File source = new File(provenance.alignMatrixSource);
			if (source.isFile()) return OpmProvenance.utcTimestamp(source.lastModified()) + " (source file)";
		}
		return "not recorded";
	}

	private static boolean blank(String value) {
		return value == null || value.trim().isEmpty();
	}

	/**			Return to Virtual whenever the thing being described changes
	 * <p>		Virtual is the safe default and materialising is the exception: it is the one
	 * 			open that can ask for more memory than the machine has. A cleared box carried
	 * 			from one view to the next means a decision taken about a single time point of
	 * 			one dataset silently applies to a whole 5-D acquisition of another, which is
	 * 			the case that hurts. So changing the open mode, the dataset, or the folder puts
	 * 			it back on.
	 * <p>		Said out loud in the status line. A control that changes itself with no
	 * 			explanation is worse than one that does not change at all.
	 */
	private void defaultToVirtual() {
		if (virtual.isSelected()) return;
		virtual.setSelected(true);
		status.setText("Virtual re-enabled for the new view; untick it again to materialise.");
	}

	private void updateEnabledControls() {
		OpenMode mode = (OpenMode) openMode.getSelectedItem();
		projections.setEnabled(mode == OpenMode.PROJECTION);
		boolean single = mode == OpenMode.VOLUME_SINGLE;
		timepoint.setEnabled(single);
		timeSlider.setEnabled(single && timeSlider.getMaximum() > 1);
		timeFirst.setEnabled(single);
		timeLast.setEnabled(single);

		/* Both formats drive the same two controls now. A TIFF series is a whole camera width
		 * whose halves are split at read time, so the only TIFF that cannot answer these
		 * questions is one whose halves are already in its pixels - or a view that has
		 * collapsed the axis they lie along. */
		Entry entry = selectedEntry();
		boolean tiffComposable = tiffComposable(entry);
		boolean viewTakesIt = !tiffComposable || tiffCompositionApplies(selectedViewKey());
		boolean composable = entry != null && (!entry.isTiff() || (tiffComposable && viewTakesIt));
		selections.setEnabled(composable);
		operations.setEnabled(composable);
		if (channelSetupButton != null) channelSetupButton.setEnabled(composable || tiffComposable);
		String reason = entry == null || !entry.isTiff() ? null : !tiffComposable
				? "This TIFF holds its channels in its pixels; it opens as written."
				: !viewTakesIt
						? "An X or Y projection has collapsed the axis the camera halves lie along;"
								+ " it opens as written. The volume and the Z projections compose."
						: "The halves of these _ChannelNNNN series are split and transformed as"
								+ " they are read; the files on disk are never changed.";
		selections.setToolTipText(reason);
		operations.setToolTipText(reason);
		if (channelSetupButton != null) channelSetupButton.setToolTipText(reason);
		// the renderers read the selected entry; a combo that is only greyed would not repaint
		selections.repaint();
		operations.repaint();
	}

	/**
	 * What the descriptive composition controls show while a TIFF result is selected.
	 * <p>
	 * A TIFF result opens with its composition as written unless its setup button defines a
	 * virtual overlay. The two combo boxes stay greyed - but a greyed box still shows an item,
	 * and it used to be whatever the box last held for an
	 * OME-Zarr, normally the first: a whole-image TIFF then read "stored halves as separate
	 * channels" over a full-width image. The box now says what the TIFF holds - the run's own
	 * layout where a live run said so, see {@link #tiffLayouts} - and otherwise only that it is
	 * already applied.
	 */
	private final class WrittenCompositionRenderer extends javax.swing.DefaultListCellRenderer {
		private static final long serialVersionUID = 1L;
		private final boolean runtimeView;

		WrittenCompositionRenderer(boolean runtimeView) { this.runtimeView = runtimeView; }

		@Override
		public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list,
				Object value, int index, boolean isSelected, boolean cellHasFocus) {
			java.awt.Component shown = super.getListCellRendererComponent(
					list, value, index, isSelected, cellHasFocus);
			Entry entry = selectedEntry();
			/* Index -1 is the box itself, not a row of its popup. A composable TIFF now
			 * answers these two controls for real, so only one that cannot - its channels
			 * already in its pixels, or a projection that collapsed the split axis - is
			 * described instead of read. */
			if (index < 0 && entry != null && entry.isTiff()
					&& !(tiffComposable(entry) && tiffCompositionApplies(selectedViewKey())))
				setText(writtenComposition(tiffLayoutOf(entry), runtimeView));
			return shown;
		}
	}

	/** The text of {@link WrittenCompositionRenderer}, apart from the window so it can be tested. */
	static String writtenComposition(DeskewChannelView layout, boolean runtimeView) {
		if (layout == null) return runtimeView ? "applied when the TIFF was written" : "as written in the TIFF";
		return runtimeView ? layout.operation + " (in the TIFF pixels)" : "as written: " + layout.describedAs;
	}

	/**
	 * The channel layout of TIFF results a live run has told this window about, by result folder.
	 * A TIFF records none of its own, so a result opened any other way is described generically.
	 */
	private final java.util.Map<File, DeskewChannelView> tiffLayouts =
			new java.util.LinkedHashMap<File, DeskewChannelView>();

	private DeskewChannelView tiffLayoutOf(Entry entry) {
		for (java.util.Map.Entry<File, DeskewChannelView> known : tiffLayouts.entrySet())
			if (sameFolder(known.getKey(), entry.root())) return known.getValue();
		return null;
	}

	private static final Pattern TIFF_CHANNEL_TOKEN = Pattern.compile("(?i)_Channel(\\d+)");

	/**
	 * One window-local, non-persistent description of how a TIFF family's halves are treated.
	 * <p>
	 * What is shown is {@code Channels / side} and {@code Runtime view}, exactly as for a store.
	 * This holds what those two cannot say: the slot order behind
	 * {@code configured channel order}, which side the flip mirrors, how a transformed pixel is
	 * sampled, and the optional alignment CSV. It belongs to this window, is never written to
	 * the TIFFs or to the preferences, and two viewers can therefore show one acquisition two
	 * ways at once.
	 */
	static final class TiffVirtualSetup {
		String family;
		String flipHalf = BatchChannelOperation.FLIP_RIGHT;
		boolean interpolate = true;
		String alignmentFile = "";
		final List<String> order = new ArrayList<String>();

		List<String> selectedSources() {
			List<String> selected = new ArrayList<String>();
			for (String source : order)
				if (!BatchChannelOperation.SKIP_CHANNEL.equals(source)) selected.add(source);
			return selected;
		}
	}

	/**			One entry per acquisition, not per {@code _ChannelNNNN} file series
	 * <p>		Series belong together when their folder and their name match once the channel
	 * 			token is replaced, which is the same rule the virtual overlay uses to find its
	 * 			sources. Discovery order is kept, a family taking the place of its first member,
	 * 			and within it the series are ordered by acquisition channel - so the anchor is
	 * 			always {@code _Channel0001} where there is one.
	 * <p>		A result with no channel token, and one already holding several stored channels,
	 * 			is its own entry as before: there is nothing to group it with.
	 *
	 * @param results			: everything the TIFF discovery returned, in its own order
	 * <p>
	 * @return					: the entries to list
	 */
	static List<Entry> groupTiffFamilies(List<TiffResultDataset> results) {
		Map<String, List<TiffResultDataset>> families = new LinkedHashMap<String, List<TiffResultDataset>>();
		List<Entry> entries = new ArrayList<Entry>();
		List<String> order = new ArrayList<String>();
		for (TiffResultDataset dataset : results) {
			if (acquisitionChannel(dataset) <= 0) {
				entries.add(new Entry(dataset));
				order.add(null);
				continue;
			}
			String family = tiffFamily(dataset);
			List<TiffResultDataset> members = families.get(family);
			if (members == null) {
				members = new ArrayList<TiffResultDataset>();
				families.put(family, members);
				entries.add(null);					// the family's place in discovery order
				order.add(family);
			}
			members.add(dataset);
		}
		for (List<TiffResultDataset> members : families.values())
			Collections.sort(members, new Comparator<TiffResultDataset>() {
				@Override public int compare(TiffResultDataset a, TiffResultDataset b) {
					return Integer.compare(acquisitionChannel(a), acquisitionChannel(b));
				}
			});
		List<Entry> listed = new ArrayList<Entry>();
		for (int i = 0; i < entries.size(); i++)
			listed.add(entries.get(i) != null ? entries.get(i) : new Entry(families.get(order.get(i))));
		return listed;
	}

	private static int acquisitionChannel(TiffResultDataset dataset) {
		if (dataset == null) return -1;
		Matcher token = TIFF_CHANNEL_TOKEN.matcher(dataset.getDisplayName());
		if (!token.find()) return -1;
		try { return Integer.parseInt(token.group(1)); }
		catch (NumberFormatException unreadable) { return -1; }
	}

	private static String tiffFamily(TiffResultDataset dataset) {
		if (dataset == null) return "";
		String root = dataset.getRoot() == null ? "" : dataset.getRoot().getAbsolutePath();
		return (root + '|' + TIFF_CHANNEL_TOKEN.matcher(dataset.getDisplayName())
				.replaceFirst("_Channel####")).toLowerCase(Locale.ROOT);
	}

	private TiffVirtualSetup tiffSetupOf(TiffResultDataset dataset) {
		return tiffChannelSetups.get(tiffFamily(dataset));
	}

	/**
	 * Whether this TIFF result's channels can still be decided in the viewer.
	 * <p>
	 * Two conditions, and both are about what the files hold rather than about what the user
	 * wants. The name has to carry a {@code _ChannelNNNN}, which is what says the series is one
	 * acquisition channel of an acquisition; and the file has to hold exactly one stored
	 * channel, because a result already composed into a hyperstack has its halves flipped,
	 * aligned and ordered in its pixels, and there is no unambiguous way back.
	 * <p>
	 * Read from the layout the discovery already parsed, so this costs no IO.
	 */
	private boolean tiffComposable(Entry entry) {
		if (entry == null || !entry.isTiff() || acquisitionChannel(entry.tiff) <= 0) return false;
		for (TiffResultDataset dataset : entry.series) {
			TiffResultDataset.View view = dataset.getView(TiffResultDataset.VOLUME);
			if (view == null) view = firstView(dataset);
			if (view == null) return false;
			try {
				if (view.getLayout().channels != 1) return false;
			} catch (IOException unreadable) {
				return false;
			}
		}
		return true;
	}

	private static TiffResultDataset.View firstView(TiffResultDataset dataset) {
		for (String key : dataset.getAvailableProjections()) {
			TiffResultDataset.View view = dataset.getView(key);
			if (view != null) return view;
		}
		return null;
	}

	/** The acquisition-channel series of this one's family, as the listed entry holds them. */
	private List<TiffResultDataset> relatedTiffSeries(TiffResultDataset selected) {
		String family = tiffFamily(selected);
		for (int i = 0; i < datasets.getItemCount(); i++) {
			Entry candidate = datasets.getItemAt(i);
			if (candidate != null && candidate.isTiff() && family.equals(tiffFamily(candidate.tiff)))
				return candidate.series;
		}
		return Collections.singletonList(selected);
	}

	/** The optical halves a family can offer, acquisition channel first, left before right. */
	static List<String> tiffChannelLabels(List<TiffResultDataset> series) {
		List<String> labels = new ArrayList<String>();
		for (TiffResultDataset dataset : series) {
			int channel = acquisitionChannel(dataset);
			if (channel <= 0) continue;
			labels.add(ChannelOperationSettings.sourceKey(channel, true));
			labels.add(ChannelOperationSettings.sourceKey(channel, false));
		}
		return labels;
	}

	private static List<String> tiffSourceChoices(List<TiffResultDataset> series) {
		List<String> choices = new ArrayList<String>();
		for (TiffResultDataset dataset : series) {
			int channel = acquisitionChannel(dataset);
			choices.add(ChannelOperationSettings.sourceKey(channel, true));
			choices.add(ChannelOperationSettings.sourceKey(channel, false));
		}
		for (TiffResultDataset dataset : series)
			choices.add(ChannelOperationSettings.wholeSourceKey(acquisitionChannel(dataset)));
		choices.add(BatchChannelOperation.SKIP_CHANNEL);
		return choices;
	}

	private static TiffResultDataset seriesForSource(List<TiffResultDataset> series, String source) {
		int wanted = ChannelOperationSettings.acquisitionChannelOf(source);
		for (TiffResultDataset dataset : series)
			if (acquisitionChannel(dataset) == wanted) return dataset;
		return null;
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
		Entry entry = selectedEntry();
		if (entry != null && entry.isTiff()) {
			configureTiffChannelSetup(entry.tiff);
			return;
		}
		OmeZarrDataset dataset = selectedDataset();
		if (dataset == null) { IJ.showMessage(TITLE, "Select a dataset first."); return; }
		List<String> labels = dataset.getChannelLabels();
		if (labels.isEmpty()) { IJ.showMessage(TITLE, "This dataset reports no channels."); return; }

		int slots = OmeZarrView.channelSetupSlots(dataset);
		String[] sources = OmeZarrView.channelSetupSources(dataset);
		String[] defaults = OmeZarrView.channelSetupDefaults(dataset, configuredChannels);

		OpmProvenance provenance = dataset.getProvenance();
		String storedMatrixSource = provenance == null || provenance.alignMatrixSource == null
				? "" : provenance.alignMatrixSource;

		GenericDialogPlus dialog = new OpmDialogPlus("OME-Zarr viewer channel setup");
		Parameter.styleDialog( dialog );
		dialog.addMessage(dataset.getDisplayName() + " stores " + labels.size()
				+ (labels.size() == 1 ? " channel: " : " channels: ") + labels);
		List<String> unselected = OmeZarrView.channelsNotSelected(dataset, defaults);
		if (!unselected.isEmpty())
			dialog.addMessage("Not selected by the current setup: " + unselected);
		dialog.addChoice("flip side from metadata override",
				new String[] { BatchChannelOperation.FLIP_RIGHT, BatchChannelOperation.FLIP_LEFT },
				configuredChannels.flipHalf);
		for (int i = 0; i < slots; i++)
			dialog.addChoice("output channel " + (i + 1), sources, defaults[i]);
		dialog.addChoice("interpolation", Parameter.INTERPOLATION_OPTIONS,
				Parameter.interpolationChoice(configuredChannels.interpolate));
		dialog.addCheckbox("try GPU, fall back to CPU", tryGpu);
		dialog.addFileField("alignment matrix metadata override", storedMatrixSource, 44);
		dialog.showDialog();
		if (dialog.wasCanceled()) return;

		configuredChannels.flipHalf = dialog.getNextChoice();
		for (int i = 0; i < slots; i++) configuredChannels.channelOrder[i] = dialog.getNextChoice();
		configuredChannels.interpolate = Parameter.isBilinear(dialog.getNextChoice());
		tryGpu = dialog.getNextBoolean();
		Prefs.set(GPU_KEY, tryGpu);
		/* Only what this dialog showed is written back, onto what the preferences already hold.
		 * The in-memory setup may carry a dataset's recorded layout in the slots it did not show,
		 * and storing those would narrow what Deskew Batch writes for a wider acquisition. */
		ChannelOperationSettings shared = new ChannelOperationSettings();
		shared.load();
		shared.flipHalf = configuredChannels.flipHalf;
		shared.interpolate = configuredChannels.interpolate;
		for (int i = 0; i < slots; i++) shared.channelOrder[i] = configuredChannels.channelOrder[i];
		shared.store();
		boolean matrixReplaced = applyAlignmentOverride(dataset, storedMatrixSource, dialog.getNextString());
		composingControls++;
		try {
			selections.setSelectedItem(OmeZarrView.SELECT_CONFIGURED);
		} finally {
			composingControls--;
		}
		recomposeOpenViews(matrixReplaced);
	}

	/**
	 * Configure a display-only channel overlay over sibling {@code _ChannelNNNN} TIFF series.
	 * Nothing here is persisted and no TIFF metadata or pixels are changed, which lets two
	 * viewer windows show the same live acquisition with different interpretations.
	 */
	private void configureTiffChannelSetup(TiffResultDataset selected) {
		List<TiffResultDataset> series = relatedTiffSeries(selected);
		if (series.isEmpty()) {
			IJ.showMessage(TITLE, "No matching _ChannelNNNN TIFF series was found.");
			return;
		}
		List<String> choices = tiffSourceChoices(series);
		String[] offered = choices.toArray(new String[choices.size()]);
		String family = tiffFamily(selected);
		TiffVirtualSetup before = tiffChannelSetups.get(family);
		TiffVirtualSetup start = before == null ? new TiffVirtualSetup() : before;
		start.family = family;
		if (start.order.isEmpty()) {
			for (String source : choices)
				if (!BatchChannelOperation.SKIP_CHANNEL.equals(source)
						&& !ChannelOperationSettings.isWholeSource(source)) start.order.add(source);
		}
		int slots = Math.min(BatchChannelOperation.MAX_OUTPUT_CHANNELS,
				Math.max(1, 2 * series.size()));
		while (start.order.size() < slots) start.order.add(BatchChannelOperation.SKIP_CHANNEL);

		GenericDialogPlus dialog = new OpmDialogPlus("TIFF viewer channel setup");
		Parameter.styleDialog(dialog);
		dialog.addMessage("Display-only setup for " + series.size() + " matching TIFF series.\n"
				+ "Left/right crops, mirroring and alignment are evaluated while planes are read.\n"
				+ "The files on disk are not changed, and this setup belongs only to this viewer window.\n"
				+ "What comes out is chosen in Channels / side and Runtime view. This is the order\n"
				+ "\"configured channel order\" uses, and the flip, sampling and matrix they apply.");
		dialog.addChoice("flip", ChannelOperationSettings.FLIP_LABELS,
				ChannelOperationSettings.flipLabel(start.flipHalf));
		for (int i = 0; i < slots; i++) {
			String initial = choices.contains(start.order.get(i))
					? start.order.get(i) : BatchChannelOperation.SKIP_CHANNEL;
			dialog.addChoice("output channel " + (i + 1), offered, initial);
		}
		dialog.addChoice("interpolation", Parameter.INTERPOLATION_OPTIONS,
				Parameter.interpolationChoice(start.interpolate));
		dialog.addFileField("alignment matrix (optional, display only)", start.alignmentFile, 44);
		dialog.showDialog();
		if (dialog.wasCanceled()) return;

		TiffVirtualSetup setup = new TiffVirtualSetup();
		setup.family = family;
		setup.flipHalf = ChannelOperationSettings.flipValue(dialog.getNextChoice());
		for (int i = 0; i < slots; i++) setup.order.add(dialog.getNextChoice());
		setup.interpolate = Parameter.isBilinear(dialog.getNextChoice());
		setup.alignmentFile = dialog.getNextString().trim();
		{
			List<String> selectedSources = setup.selectedSources();
			if (selectedSources.isEmpty()) {
				IJ.showMessage(TITLE, "Select at least one TIFF output channel source.");
				return;
			}
			boolean whole = false, half = false;
			for (String source : selectedSources) {
				whole |= ChannelOperationSettings.isWholeSource(source);
				half |= !ChannelOperationSettings.isWholeSource(source);
			}
			if (whole && half) {
				IJ.showMessage(TITLE, "Whole-width and half-width TIFF sources cannot share one overlay.");
				return;
			}
			if (!setup.alignmentFile.isEmpty()
					&& AlignmentMatrixSet.load(setup.alignmentFile) == null) {
				IJ.showMessage(TITLE, "Could not read the alignment matrix:\n" + setup.alignmentFile);
				return;
			}
		}
		tiffChannelSetups.put(family, setup);
		try { setTimepointRange(TiffResultView.availableFrameCount(
				selected, selectedTiffView(), tiffOptions())); }
		catch (Throwable unreadable) { /* Open reports the detailed incompatibility. */ }
		selections.repaint();
		operations.repaint();
		recomposeOpenViews(true);
		if (live.isSelected()) startWatching();
	}

	/**
	 * Write a recomputed rigid alignment into the dataset's metadata, on confirmation.
	 * <p>
	 * Because the canonical format keeps the halves unaligned and applies the matrix at read
	 * time, replacing it is a metadata edit: no pixel is rewritten and every view picks up the
	 * new numbers as soon as the descriptor is re-read. Nothing is written when the field is
	 * left as it was found, or when the file resolves to the matrix already stored.
	 *
	 * @return					: whether the matrix in the metadata was replaced
	 */
	private boolean applyAlignmentOverride(OmeZarrDataset dataset, String before, String after) {
		String path = after == null ? "" : after.trim();
		if (path.isEmpty() || path.equals(before == null ? "" : before.trim())) return false;
		File csv = new File(path);
		if (!csv.isFile()) {
			IJ.showMessage(TITLE, "Alignment matrix file not found:\n" + csv);
			return false;
		}
		double[][] matrix = IO.loadMatrixFromFile(csv.getAbsolutePath());
		if (!OmeZarrDataset.isAlignmentMatrix(matrix)) {
			IJ.showMessage(TITLE, "Not a 2 x 3 rigid alignment matrix:\n" + csv
					+ "\n\nTwo rows of three comma separated values are required.");
			return false;
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
		if (!confirm.yesPressed()) return false;
		try {
			OmeZarrDataset.writeAlignMatrix(dataset.getRoot(), matrix, csv.getAbsolutePath());
			IJ.log("OPM viewer: alignment metadata of " + dataset.getDisplayName()
					+ " replaced from " + csv.getAbsolutePath());
			reloadSelectedDataset();
			return true;
		} catch (Throwable error) {
			showError(error);
			return false;
		}
	}

	/**
	 * Re-read the selected descriptor in place so later views use the metadata just written.
	 * <p>
	 * The same dataset, so not a dataset change: going through {@link #datasetChanged} stopped
	 * a live watch and reset the controls the user had just set, for a metadata edit.
	 */
	private void reloadSelectedDataset() {
		int index = datasets.getSelectedIndex();
		OmeZarrDataset current = selectedDataset();
		if (index < 0 || current == null) return;
		OmeZarrDataset reloaded = OmeZarrDataset.read(current.getRoot());
		suppressDatasetChanged = true;
		try {
			datasets.removeItemAt(index);
			datasets.insertItemAt(new Entry(reloaded), index);
			datasets.setSelectedIndex(index);
		} finally {
			suppressDatasetChanged = false;
		}
		details.setText(summary(reloaded));
		details.setCaretPosition(0);
	}

	/**			Build the requested view, after confirming anything that has to be allocated
	 * <p>		A materialised whole time-lapse is the one open that can ask for more memory
	 * 			than the machine has, so it alone is offered a shorter stretch first.
	 */
	private void openSelected() {
		if (busy) return;
		Entry entry = selectedEntry();
		if (entry == null) { IJ.showMessage(TITLE, "Select a dataset first."); return; }
		if (entry.isTiff()) { openSelectedTiff(entry.tiff); return; }
		final OmeZarrDataset dataset = entry.zarr;
		final OpenMode mode = (OpenMode) openMode.getSelectedItem();
		final String projection = String.valueOf(projections.getSelectedItem());
		final OmeZarrView.Options options = options(dataset);
		final boolean openVirtual = virtual.isSelected();

		int wantedFirst = mode == OpenMode.VOLUME_SINGLE
				? ((Number) timepoint.getValue()).intValue() - 1 : 0;
		int wantedCount = mode == OpenMode.VOLUME_SINGLE ? 1 : dataset.getTimepointCount();
		if (!openVirtual) {
			int[] confirmed = confirmMaterialise(
					dataset, mode, projection, options, wantedFirst, wantedCount);
			if (confirmed == null) return;
			wantedFirst = confirmed[0];
			wantedCount = confirmed[1];
		}
		final int firstT = wantedFirst;
		final int frames = wantedCount;

		busy = true;
		status.setText("Opening " + dataset.getDisplayName() + "...");
		Thread worker = new Thread(new Runnable() {
			@Override public void run() {
				try {
					final long start = System.nanoTime();
					final ImagePlus image = openView(
							dataset, mode, projection, openVirtual, firstT, frames, options);
					SwingUtilities.invokeLater(new Runnable() {
						@Override public void run() {
							busy = false;
							if (released.get()) { discard(image); return; }
							String placed;
							if (openVirtual) {
								placed = present(new ManagedView(image, dataset.getRoot(),
										kindOf(mode, projection), mode, firstT, true, false, options))
										? "  Replaced the " + kindOf(mode, projection) + " view already open."
										: "";
							} else {
								image.show();
								placed = refitIfOpenedAtMinimumZoom(image)
										? "  ImageJ opened it at its minimum zoom; re-fitted to the screen." : "";
							}
							status.setText("Opened in " + IJ.d2s((System.nanoTime() - start) / 1e9, 3) + " s."
									+ (openVirtual ? "" : "  Materialised views cannot follow new time points.")
									+ placed);
						}
					});
				} catch (final Throwable error) {
					SwingUtilities.invokeLater(new Runnable() {
						@Override public void run() {
							if (released.get()) return;
							busy = false;
							showError(error);
						}
					});
				}
			}
		}, "OPM-Zarr-open");
		worker.setDaemon(true);
		worker.start();
	}

	/**
	 * Open a view of a TIFF result, confirming the allocation when it is not virtual.
	 *
	 * <p>The same three modes as an OME-Zarr, read the same way: the volume over every time
	 * point, the volume at one, or a projection movie. It reads stored channels directly unless
	 * this viewer has a display-only virtual source map.
	 */
	private void openSelectedTiff(final TiffResultDataset dataset) {
		final OpenMode mode = (OpenMode) openMode.getSelectedItem();
		final String viewKey = selectedTiffView();
		final TiffResultDataset.View view = dataset.getView(viewKey);
		if (view == null || view.frameCount() < 1) {
			IJ.showMessage(TITLE, "This result has no " + viewKey + " written yet.");
			return;
		}
		final TiffResultView.Options options = tiffOptions();
		final boolean openVirtual = virtual.isSelected();
		final int available;
		try { available = TiffResultView.availableFrameCount(dataset, viewKey, options); }
		catch (Throwable error) { showError(error); return; }
		if (available < 1) {
			IJ.showMessage(TITLE, "No time point is complete across the selected TIFF sources yet.");
			return;
		}

		int wantedFirst = mode == OpenMode.VOLUME_SINGLE
				? Math.min(available - 1, ((Number) timepoint.getValue()).intValue() - 1) : 0;
		int wantedCount = mode == OpenMode.VOLUME_SINGLE ? 1 : available;
		if (!openVirtual) {
			int[] confirmed = confirmMaterialiseTiff(
					dataset, viewKey, mode, options, wantedFirst, wantedCount, available);
			if (confirmed == null) return;
			wantedFirst = confirmed[0];
			wantedCount = confirmed[1];
		}
		final int firstT = wantedFirst;
		final int frames = wantedCount;

		busy = true;
		status.setText("Opening " + dataset.getDisplayName() + " [" + viewKey + "]...");
		Thread worker = Shutdown.daemon(new Runnable() {
			@Override public void run() {
				try {
					final long start = System.nanoTime();
					final ImagePlus image = openVirtual
							? TiffResultView.openVirtual(dataset, viewKey, options,
									mode == OpenMode.VOLUME_SINGLE ? firstT : -1)
							: TiffResultView.openMaterialised(dataset, viewKey, options, firstT, frames);
					SwingUtilities.invokeLater(new Runnable() {
						@Override public void run() {
							busy = false;
							if (released.get()) { discard(image); return; }
							String placed;
							String kind = TiffResultDataset.VOLUME.equals(viewKey) ? VOLUME_KIND : viewKey;
							if (openVirtual) {
								placed = present(ManagedView.tiff(image, dataset.getRoot(), kind, mode,
										firstT, true, false, options))
										? "  Replaced the " + kind + " view already open." : "";
							} else {
								image.show();
								placed = refitIfOpenedAtMinimumZoom(image)
										? "  ImageJ opened it at its minimum zoom; re-fitted to the screen." : "";
							}
							status.setText("Opened in "
									+ IJ.d2s((System.nanoTime() - start) / 1e9, 3) + " s."
									+ (openVirtual ? "" : "  Materialised views cannot follow new time points.")
									+ placed);
						}
					});
				} catch (final Throwable error) {
					SwingUtilities.invokeLater(new Runnable() {
						@Override public void run() {
							if (released.get()) return;
							busy = false;
							showError(error);
						}
					});
				}
			}
		}, "OPM-tiff-open");
		worker.start();
	}

	/** The range and the size confirmation, in the same order and for the same reason as Zarr. */
	private int[] confirmMaterialiseTiff(TiffResultDataset dataset, String viewKey, OpenMode mode,
			TiffResultView.Options options, int firstT, int frames, int available) {
		int fromT = firstT;
		int count = frames;
		long perTimepoint;
		try { perTimepoint = TiffResultView.estimateMaterialisedBytes(dataset, viewKey, options); }
		catch (Throwable error) { showError(error); return null; }

		if (mode != OpenMode.VOLUME_SINGLE && available > 1) {
			GenericDialogPlus range = new OpmDialogPlus("Materialise TIFF result");
			Parameter.styleDialog( range );
			range.addMessage(available + " time point(s) written, about "
					+ IJ.d2s(perTimepoint / 1048576.0, 1) + " MB each with the current region"
					+ " and channel range;" + "\nabout "
					+ IJ.d2s(perTimepoint / 1048576.0 * available, 1) + " MB for all of them.\n\n"
					+ "Open only part of the time-lapse by shortening this range.\n"
					+ (region == null
							? "Region: the whole view. Set one to open less of each time point."
							: "Region: " + region + "."));
			range.addNumericField("first time point", fromT + 1, 0);
			range.addNumericField("time points", count, 0);
			range.showDialog();
			if (range.wasCanceled()) return null;
			fromT = Math.max(1, Math.min(available, (int) range.getNextNumber())) - 1;
			count = Math.max(1, Math.min(available - fromT, (int) range.getNextNumber()));
		}

		YesNoCancelDialog confirm = new YesNoCancelDialog(this, "Materialise TIFF result",
				"Allocate approximately " + IJ.d2s(perTimepoint * (double) count / 1048576.0, 1)
				+ " MB for the pixel stack?\n" + count + " time point(s) will be read; only the"
				+ " selected channels, Z planes and rows are decoded.");
		if (!confirm.yesPressed()) return null;
		return new int[] { fromT, count };
	}

	/**
	 * The region and channel range for a TIFF view.
	 *
	 * <p>By default this is the stored TIFF layout. When this viewer has a TIFF virtual setup,
	 * the options instead name the sibling file series and horizontal parts that become output
	 * channels. The setup remains in memory and the files remain untouched.
	 */
	/**			What the composition controls ask of the selected TIFF result
	 * <p>		The same two controls as an OME-Zarr, meaning the same things: {@code Channels /
	 * 			side} picks the optical sources and their order, {@code Runtime view} says what
	 * 			is done to them. A TIFF series is a whole camera width rather than a pair of
	 * 			stored halves, so the mapping is the store's read backwards - side by side is
	 * 			the width exactly as written, and every other view splits it at read time.
	 * <p>		Returns no virtual channels at all - the stored planes, untouched - when the
	 * 			result is what the controls describe anyway, and when the selected view cannot
	 * 			carry a half split. That is not only a short cut: it is what lets an X or Y
	 * 			projection open at all, and what keeps the plain case free of a plane copy.
	 */
	private TiffResultView.Options tiffOptions() {
		TiffResultView.Options result = new TiffResultView.Options();
		result.virtual = virtual.isSelected();
		result.bounds = region == null ? null : region.copy();
		Entry entry = selectedEntry();
		if (entry == null || !entry.isTiff() || !tiffComposable(entry)) return result;
		TiffVirtualSetup setup = tiffSetupFor(entry);
		result.interpolate = setup.interpolate;
		result.virtualChannels.addAll(tiffVirtualChannels(entry.series,
				String.valueOf(selections.getSelectedItem()),
				(OmeZarrView.Operation) operations.getSelectedItem(), setup));
		if (isAsWritten(result.virtualChannels, entry) || !tiffCompositionApplies(selectedViewKey()))
			result.virtualChannels.clear();
		return result;
	}

	/**			The output channels one composition asks for, in order
	 * <p>		The whole mapping between the two controls and the lazy reader, as a function of
	 * 			its arguments so it can be checked against real series without a window:
	 * <ul>
	 * <li>	{@code side by side} - one full-width channel per series, which is the stored plane,
	 * 		since the deskew shear acts in Y and Z only;
	 * <li>	{@code stored halves} - the halves as the camera saw them, no mirror, no matrix;
	 * <li>	{@code flip only} - each half mirrored onto the side the setup names;
	 * <li>	{@code flip + align left/right} - the same, with the setup's matrix, the operation
	 * 		naming the side as it does for a store.
	 * </ul>
	 *
	 * @param series			: the family's series, ascending by acquisition channel
	 * @param selection			: the {@code Channels / side} item
	 * @param operation			: the {@code Runtime view} item
	 * @param setup				: this window's setup for the family
	 */
	static List<TiffResultView.VirtualChannel> tiffVirtualChannels(List<TiffResultDataset> series,
			String selection, OmeZarrView.Operation operation, TiffVirtualSetup setup) {
		List<TiffResultView.VirtualChannel> channels = new ArrayList<TiffResultView.VirtualChannel>();
		List<String> wanted = OmeZarrView.channelsForSelection(
				tiffChannelLabels(series), selection, configuredOrderOf(setup));
		boolean sideBySide = operation == OmeZarrView.Operation.SIDE_BY_SIDE;
		boolean transform = operation != OmeZarrView.Operation.STORED_CHANNELS;
		boolean aligned = operation == OmeZarrView.Operation.FLIP_ALIGN_LEFT
				|| operation == OmeZarrView.Operation.FLIP_ALIGN_RIGHT;
		boolean flipLeft = operation == OmeZarrView.Operation.FLIP_ALIGN_LEFT
				|| (operation == OmeZarrView.Operation.FLIP_ONLY
						&& BatchChannelOperation.FLIP_LEFT.equals(setup.flipHalf));
		AlignmentMatrixSet alignment = !aligned || setup.alignmentFile == null
				|| setup.alignmentFile.trim().isEmpty() ? null : AlignmentMatrixSet.load(setup.alignmentFile);

		java.util.Set<Integer> wholeSeen = new java.util.LinkedHashSet<Integer>();
		for (String source : wanted) {
			TiffResultDataset dataset = seriesForSource(series, source);
			if (dataset == null) continue;
			if (sideBySide) {
				Integer channel = Integer.valueOf(acquisitionChannel(dataset));
				if (!wholeSeen.add(channel)) continue;
				channels.add(new TiffResultView.VirtualChannel(dataset,
						ChannelOperationSettings.wholeSourceKey(channel.intValue()),
						TiffResultView.HorizontalPart.WHOLE, null, false, true));
				continue;
			}
			TiffResultView.HorizontalPart part = ChannelOperationSettings.isWholeSource(source)
					? TiffResultView.HorizontalPart.WHOLE
					: source.endsWith("-right") ? TiffResultView.HorizontalPart.RIGHT
							: TiffResultView.HorizontalPart.LEFT;
			channels.add(new TiffResultView.VirtualChannel(
					dataset, source, part, alignment, flipLeft, transform));
		}
		return channels;
	}

	/**
	 * Whether these channels are the anchor series read exactly as it is on disk.
	 * <p>
	 * A one-series family shown side by side is the stored plane, and going through the virtual
	 * path for it would copy every plane to no end - and would refuse the X and Y projections,
	 * which a stored read serves perfectly well.
	 */
	private static boolean isAsWritten(List<TiffResultView.VirtualChannel> channels, Entry entry) {
		if (channels.size() != 1) return false;
		TiffResultView.VirtualChannel only = channels.get(0);
		return only.dataset == entry.tiff && only.part == TiffResultView.HorizontalPart.WHOLE
				&& only.alignment == null;
	}

	/**
	 * Whether a half split means anything for this view.
	 * <p>
	 * A Z projection keeps X and Y, so it splits and transforms exactly as the volume does. An X
	 * projection has collapsed the very axis the halves lie along, and a Y projection has
	 * collapsed the plane the 2-D alignment lives in. Those open as written instead of failing.
	 */
	static boolean tiffCompositionApplies(String viewKey) {
		if (viewKey == null) return false;
		return TiffResultDataset.VOLUME.equals(viewKey)
				|| viewKey.toLowerCase(Locale.ROOT).endsWith("z");
	}

	/** This family's setup, or the plain one it starts with: every half, right flipped. */
	private TiffVirtualSetup tiffSetupFor(Entry entry) {
		TiffVirtualSetup stored = tiffSetupOf(entry.tiff);
		if (stored != null) return stored;
		TiffVirtualSetup setup = new TiffVirtualSetup();
		setup.family = tiffFamily(entry.tiff);
		setup.order.addAll(tiffChannelLabels(entry.series));
		return setup;
	}

	/**
	 * Take a live run's own layout as this family's TIFF setup.
	 * <p>
	 * The two combo boxes carry the selection and the operation; the slot order, the flip side
	 * and the sampling live in the window's setup for this family, so a run that flips left, or
	 * orders its channels its own way, is previewed the way it is being written.
	 */
	private void adoptTiffSetup(Entry entry, DeskewChannelView layout) {
		TiffVirtualSetup setup = tiffSetupFor(entry);
		if (!layout.channelOrder.isEmpty()) {
			setup.order.clear();
			setup.order.addAll(layout.channelOrder);
		}
		setup.flipHalf = layout.flipHalf;
		setup.interpolate = layout.interpolate;
		tiffChannelSetups.put(setup.family, setup);
	}

	/** The setup's slot order in the shared dialect, so one selection rule serves both formats. */
	private static ChannelOperationSettings configuredOrderOf(TiffVirtualSetup setup) {
		ChannelOperationSettings order = new ChannelOperationSettings();
		for (int slot = 0; slot < order.channelOrder.length; slot++)
			order.channelOrder[slot] = slot < setup.order.size()
					? setup.order.get(slot) : BatchChannelOperation.SKIP_CHANNEL;
		return order;
	}

	/** Build one view from an explicit request, so the same call serves Open and a refresh. */
	private ImagePlus openView(OmeZarrDataset dataset, OpenMode mode, String projection,
			boolean openVirtual, int firstT, int frames, OmeZarrView.Options options) {
		if (mode == OpenMode.PROJECTION)
			return openVirtual
					? OmeZarrView.openVirtualProjectionMovie(dataset, projection, options)
					: OmeZarrView.openMaterializedProjectionMovie(dataset, projection, options);
		if (openVirtual)
			return OmeZarrView.openVirtualVolume(dataset, options,
					mode == OpenMode.VOLUME_ALL ? -1 : firstT);
		return OmeZarrView.openMaterializedVolume(dataset, options, firstT, frames);
	}

	/**			Confirm the allocation, and for a whole time-lapse offer a shorter range first
	 * <p>		The range is asked before the size is confirmed, not after: a figure quoted for
	 * 			every committed time point is not a confirmation of the ten that are then
	 * 			opened. Only a materialised {@code volume all timepoints} is offered the
	 * 			choice, because a projection movie is one plane per C/T and always spans the
	 * 			run, and a single time point is already the smallest thing there is.
	 *
	 * @return					: {firstTimepoint, frames} to open, or null when cancelled
	 */
	private int[] confirmMaterialise(OmeZarrDataset dataset, OpenMode mode, String projection,
			OmeZarrView.Options options, int firstT, int frames) {
		int committed = dataset.getTimepointCount();
		int fromT = firstT;
		int count = frames;
		long perTimepoint = 0;
		if (mode != OpenMode.PROJECTION) {
			try { perTimepoint = OmeZarrView.estimateMaterializedBytes(dataset, options, 0); }
			catch (RuntimeException error) { showError(error); return null; }
		}

		if (mode == OpenMode.VOLUME_ALL && committed > 1) {
			GenericDialogPlus range = new OpmDialogPlus("Materialise OME-Zarr");
			Parameter.styleDialog( range );
			range.addMessage(committed + " committed time points, about "
					+ IJ.d2s(perTimepoint / 1048576.0, 1) + " MB each with the current region"
					+ " and channel selection;\nabout "
					+ IJ.d2s(perTimepoint / 1048576.0 * committed, 1) + " MB for all of them.\n\n"
					+ "Open only part of the time-lapse by shortening this range.\n"
					+ (region == null
							? "Region: the whole volume. Set one to open less of each time point."
							: "Region: " + region + "."));
			range.addNumericField("first time point", fromT + 1, 0);
			range.addNumericField("time points", count, 0);
			range.showDialog();
			if (range.wasCanceled()) return null;
			fromT = Math.max(1, Math.min(committed, (int) range.getNextNumber())) - 1;
			count = Math.max(1, Math.min(committed - fromT, (int) range.getNextNumber()));
		}

		long bytes;
		try {
			bytes = mode == OpenMode.PROJECTION
					? OmeZarrView.estimateMaterializedProjectionBytes(dataset, projection, options)
					: perTimepoint * count;
		} catch (RuntimeException error) { showError(error); return null; }

		YesNoCancelDialog confirm = new YesNoCancelDialog(this, "Materialise OME-Zarr",
				"Allocate approximately " + IJ.d2s(bytes / (1024.0 * 1024.0), 1)
				+ " MB for the pixel stack?\n"
				+ (mode == OpenMode.PROJECTION
						? "Every projection C/T plane will be loaded before the movie opens."
						: count + " time point(s) will be loaded. The GPU path transforms each"
								+ " complete Z volume in one call; CPU remains available."));
		if (!confirm.yesPressed()) return null;
		return new int[] { fromT, count };
	}

	private OmeZarrView.Options options(OmeZarrDataset dataset) {
		OmeZarrView.Options result = new OmeZarrView.Options();
		result.operation = (OmeZarrView.Operation) operations.getSelectedItem();
		result.interpolate = configuredChannels.interpolate;
		result.tryGpu = tryGpu;
		result.flipHalf = configuredChannels.flipHalf;
		String selection = String.valueOf(selections.getSelectedItem());
		result.requestedChannels.addAll(OmeZarrView.channelsForSelection(dataset, selection, configuredChannels));
		/* A copy, not the field. Every view keeps the options it was opened with, so a
		 * region set afterwards cannot reach back into a window already on screen. */
		result.bounds = region == null ? null : region.copy();
		return result;
	}

	/**			Choose the sub-volume the views opened from now on are restricted to
	 * <p>		Offered in the coordinates of the view rather than of the store, because that
	 * 			is what an ROI drawn on an open view means, and because a side-by-side view is
	 * 			twice as wide as the array it reads.
	 * <p>		Changing it affects nothing that is already open. Every view holds the copy of
	 * 			the options it was built from, so this is a property of the next Open rather
	 * 			than a setting the open windows follow.
	 */
	private void chooseRegion() {
		Entry entry = selectedEntry();
		if (entry == null) { IJ.showMessage(TITLE, "Select a dataset first."); return; }
		int fullWidth, fullHeight, fullDepth;
		try {
			/* The extent of the view the region will restrict, which for a TIFF result is the
			 * stored extent and for an OME-Zarr is whatever the runtime view produces - of the
			 * volume, or of the projection movie when that is what the controls describe. A
			 * projection is measured in its own plane, which is not the volume's. */
			String projection = selectedProjectionKey();
			int[] extent = entry.isTiff()
					? TiffResultView.viewExtent(entry.tiff, selectedTiffView(), tiffOptions())
					: projection == null ? viewExtent(entry.zarr)
							: projectionExtent(entry.zarr, projection);
			fullWidth = extent[0];
			fullHeight = extent[1];
			fullDepth = extent[2];
		} catch (Throwable error) { showError(error); return; }

		OmeZarrView.Bounds start = region != null ? region.copy()
				: OmeZarrView.Bounds.full(fullWidth, fullHeight, fullDepth);

		GenericDialogPlus gd = new OpmDialogPlus(
				entry.isTiff() ? "TIFF result region" : "OME-Zarr region");
		Parameter.styleDialog( gd );
		gd.addMessage("View extent: " + fullWidth + " x " + fullHeight + " x " + fullDepth
				+ " (x, y, z) in the coordinates this view produces.\n"
				+ "Z is 1 based here and inclusive at both ends.\n"
				+ "Applies to views opened from now on; windows already open keep theirs.");
		gd.addNumericField("x", start.x, 0);
		gd.addNumericField("y", start.y, 0);
		gd.addNumericField("width", start.width, 0);
		gd.addNumericField("height", start.height, 0);
		gd.addNumericField("first z", start.zStart + 1, 0);
		gd.addNumericField("last z", start.zEnd, 0);
		OmeZarrRoi.addUpdateButton(gd, entry.root(), 0);
		gd.showDialog();
		if (gd.wasCanceled()) return;

		OmeZarrView.Bounds chosen = new OmeZarrView.Bounds();
		chosen.x = (int) gd.getNextNumber();
		chosen.y = (int) gd.getNextNumber();
		chosen.width = (int) gd.getNextNumber();
		chosen.height = (int) gd.getNextNumber();
		chosen.zStart = (int) gd.getNextNumber() - 1;
		chosen.zEnd = (int) gd.getNextNumber();

		OmeZarrView.Bounds clamped = chosen.clampedTo(fullWidth, fullHeight, fullDepth);
		region = clamped.covers(fullWidth, fullHeight, fullDepth) ? null : clamped;
		updateRegionLabel();
		status.setText(region == null
				? "Region covers the whole volume; views opened from now on are unrestricted."
				: "Region set to " + region + "; it applies to the next view opened.");
	}

	/**			Write the chosen region straight to disk, without opening it as an image
	 * <p>		The region, the channel range and the time range of the materialise dialogs, plus a
	 * <br>		format and a folder - and then no image window at all: {@link RegionExport} reads one
	 * <br>		plane of the view, writes it and lets it go. Materialising first costs the whole
	 * <br>		region in memory and then a save; this is bounded by the disk instead.
	 * <p>		The box starts at the region this window's <b>Set region...</b> and <b>Whole
	 * <br>		volume</b> left, and <b>Use active ROI</b> fills it from an ROI drawn on a view, so
	 * <br>		the row's controls choose what is exported here as much as what the next view shows.
	 * <p>		What is written is what the window would show: the runtime view of a store - flip,
	 * <br>		alignment, side by side - or the TIFF result's own pixels, both already composed. The
	 * <br>		exported store therefore records its alignment as applied and carries no matrix.
	 */
	private void exportRegion() {
		if (busy) return;
		final Entry entry = selectedEntry();
		if (entry == null) { IJ.showMessage(TITLE, "Select a dataset first."); return; }
		/* Whatever the controls describe, which is what is on screen: the volume, or one
		 * projection movie. The export used to take the volume from an OME-Zarr however the
		 * window was set, so exporting a maxZ movie wrote the whole volume instead. */
		final String viewKey = selectedViewKey();
		final String projection = selectedProjectionKey();

		final int[] extent;
		final List<String> labels;
		final int timepoints;
		try {
			if (entry.isTiff()) {
				TiffResultDataset.View view = entry.tiff.getView(viewKey);
				if (view == null || view.frameCount() < 1) {
					IJ.showMessage(TITLE, "This result has no " + viewKey + " written yet.");
					return;
				}
				TiffResultView.Options tiff = tiffOptions();
				int[] measured = TiffResultView.viewExtent(entry.tiff, viewKey, tiff);
				extent = new int[] { measured[0], measured[1], measured[2] };
				labels = TiffResultView.outputChannelLabels(entry.tiff, viewKey, tiff);
				timepoints = TiffResultView.availableFrameCount(entry.tiff, viewKey, tiff);
			} else if (projection == null) {
				extent = viewExtent(entry.zarr);
				labels = OmeZarrView.outputChannelLabels(entry.zarr, options(entry.zarr));
				timepoints = entry.zarr.getTimepointCount();
			} else {
				/* Measured by the renderer, as the volume's is. Reading the array here took its
				 * last two entries for height and width; OmeZarrDataset reverses Zarr's order, so
				 * those are the channel and time counts - a 4-channel, 50 time point acquisition
				 * exported as a 50 x 4 image whatever the region said. */
				int[] measured = OmeZarrView.projectionViewExtent(entry.zarr, projection, options(entry.zarr));
				// a projection has collapsed one axis already: one plane per channel and time point
				extent = new int[] { measured[0], measured[1], 1 };
				labels = OmeZarrView.outputChannelLabels(entry.zarr, options(entry.zarr));
				timepoints = measured[2];
			}
		} catch (Throwable error) { showError(error); return; }
		if (timepoints < 1) { IJ.showMessage(TITLE, "This dataset has no time point written yet."); return; }

		Rectangle roi = OmeZarrRoi.activeRegion(entry.root());
		OmeZarrView.Bounds start = defaultExportBox(roi, region, extent);
		String boxFrom = roi != null && roi.width > 0 && roi.height > 0 ? "the active ROI"
				: region != null ? "the region this window has set" : "the whole view";
		// a single time point view exports that one, unless the range is widened here
		boolean singleTimepoint = openMode.getSelectedItem() == OpenMode.VOLUME_SINGLE;
		int firstShown = singleTimepoint
				? Math.min(timepoints, ((Number) timepoint.getValue()).intValue()) : 1;
		int lastShown = singleTimepoint ? firstShown : timepoints;
		String suggestedName = exportName(entry, viewKey);
		GenericDialogPlus gd = new OpmDialogPlus("Export region");
		Parameter.styleDialog(gd);
		gd.addMessage("Source: " + entry.root().getName() + " (" + viewKey + ")"
				+ "\nView extent: " + extent[0] + " x " + extent[1] + " x " + extent[2]
				+ " (x, y, z), " + labels.size() + " channel(s), " + timepoints + " time point(s)."
				+ "\nChannels: " + labels
				+ (projection == null ? "" : "\nA projection has one plane per channel and time"
						+ " point, so z is 1 here; x and y still crop it.")
				+ "\nThe box below is " + boxFrom + "."
				+ "\nRanges are 1 based and inclusive; the full extent exports the whole view."
				+ "\nNothing is opened as an image: each plane is read, written and released."
				+ "\nOne file per format: a deflated TIFF hyperstack, an OME-Zarr dataset, or both.");
		gd.addNumericField("x", start.x, 0);
		gd.addNumericField("y", start.y, 0);
		gd.addNumericField("width", start.width, 0);
		gd.addNumericField("height", start.height, 0);
		gd.addNumericField("first z", start.zStart + 1, 0);
		gd.addNumericField("last z", start.zEnd, 0);
		OmeZarrRoi.addUpdateButton(gd, entry.root(), 0);
		gd.addNumericField("first channel", 1, 0);
		gd.addNumericField("last channel", labels.size(), 0);
		gd.addNumericField("first timepoint", firstShown, 0);
		gd.addNumericField("last timepoint", lastShown, 0);
		gd.addChoice("format", Parameter.OUTPUT_FORMATS, exportFormat);
		gd.addStringField("file name", suggestedName, 34);
		gd.addDirectoryField("save to", exportFolder, 34);
		gd.addCheckbox("overwrite an existing export", false);
		gd.showDialog();
		if (gd.wasCanceled()) return;

		OmeZarrView.Bounds chosen = new OmeZarrView.Bounds();
		chosen.x = (int) gd.getNextNumber();
		chosen.y = (int) gd.getNextNumber();
		chosen.width = (int) gd.getNextNumber();
		chosen.height = (int) gd.getNextNumber();
		chosen.zStart = (int) gd.getNextNumber() - 1;
		chosen.zEnd = (int) gd.getNextNumber();
		final int[] channelRange;
		final int[] timeRange;
		try {
			channelRange = inclusiveRange(gd.getNextNumber(), gd.getNextNumber(), labels.size(), "channel");
			timeRange = inclusiveRange(gd.getNextNumber(), gd.getNextNumber(), timepoints, "timepoint");
		} catch (IllegalArgumentException error) {
			IJ.showMessage(TITLE, error.getMessage());
			return;
		}
		final String format = gd.getNextChoice();
		final String name = gd.getNextString().trim();
		final String folder = gd.getNextString().trim();
		final boolean overwrite = gd.getNextBoolean();
		if (name.isEmpty()) { IJ.showMessage(TITLE, "Give the export a file name."); return; }
		if (folder.isEmpty()) { IJ.showMessage(TITLE, "Choose a folder to save into."); return; }
		exportFormat = format;
		exportFolder = folder;
		Prefs.set(EXPORT_FORMAT_KEY, format);
		Prefs.set(EXPORT_FOLDER_KEY, folder);

		final OmeZarrView.Bounds box = chosen.clampedTo(extent[0], extent[1], extent[2]);
		final RegionExport.Request request = new RegionExport.Request();
		request.name = name;
		request.folder = new File(folder);
		request.overwrite = overwrite;
		request.writeTiff = !Parameter.FORMAT_ZARR.equals(format);
		request.writeZarr = !Parameter.FORMAT_TIFF.equals(format);
		request.firstTimepoint = timeRange[0];
		request.frames = timeRange[1];
		request.source = entry.root();
		request.region = box.covers(extent[0], extent[1], extent[2]) ? "whole volume" : box.toString();
		for (int c = 0; c < channelRange[1]; c++) request.channelLabels.add(labels.get(channelRange[0] + c));
		startExport(entry, viewKey, projection, box, channelRange, request);
	}

	/** Read the region on a worker thread, so the viewer and every open view stay usable. */
	private void startExport(final Entry entry, final String viewKey, final String projection,
			final OmeZarrView.Bounds box, final int[] channelRange,
			final RegionExport.Request request) {
		busy = true;
		status.setText("Exporting " + request.region + " to " + request.folder.getAbsolutePath() + "...");
		Thread worker = Shutdown.daemon(new Runnable() {
			@Override public void run() {
				final long started = System.nanoTime();
				Closeable source = null;
				String outcome;
				try {
					if (entry.isTiff()) {
						TiffResultView.Options options = tiffOptions();
						options.bounds = box;
						options.firstOutputChannel = channelRange[0];
						options.outputChannelCount = channelRange[1];
						TiffResultView.RegionPlanes planes =
								TiffResultView.regionPlanes(entry.tiff, viewKey, options);
						source = planes;
						request.planes = planes;
						request.width = planes.width();
						request.height = planes.height();
						request.depth = planes.depth();
						request.channels = planes.channels();
						request.pixelSizeUm = planes.pixelSizeUm();
						request.voxelDepthUm = planes.voxelDepthUm();
						request.frameIntervalSeconds = planes.frameIntervalSeconds();
					} else {
						OmeZarrView.Options options = options(entry.zarr);
						options.bounds = box;
						options.firstOutputChannel = channelRange[0];
						options.outputChannelCount = channelRange[1];
						OmeZarrView.RegionPlanes planes =
								OmeZarrView.regionPlanes(entry.zarr, options, projection);
						source = planes;
						request.planes = planes;
						request.width = planes.width();
						request.height = planes.height();
						request.depth = planes.depth();
						request.channels = planes.channels();
						double[] voxel = entry.zarr.voxelSizeUm();
						request.pixelSizeUm = voxel != null && voxel.length > 0 && voxel[0] > 0 ? voxel[0] : 1;
						// a projection has no Z left to space out; its one plane keeps the XY pitch
						request.voxelDepthUm = planes.isProjection() ? request.pixelSizeUm
								: voxel != null && voxel.length > 2 && voxel[2] > 0 ? voxel[2] : 1;
						request.frameIntervalSeconds = entry.zarr.frameIntervalSeconds();
					}
					outcome = RegionExport.run(request);
				} catch (final Throwable error) {
					SwingUtilities.invokeLater(new Runnable() {
						@Override public void run() {
							if (released.get()) return;
							busy = false;
							showError(error);
						}
					});
					return;
				} finally {
					if (source != null) try { source.close(); } catch (IOException ignored) { }
				}
				final String message = outcome + " in "
						+ IJ.d2s((System.nanoTime() - started) / 1e9, 3) + " s";
				IJ.log("OPM Data Viewer " + message);
				SwingUtilities.invokeLater(new Runnable() {
					@Override public void run() {
						if (released.get()) return;
						busy = false;
						status.setText(message);
					}
				});
			}
		}, "OPM-region-export");
		worker.start();
	}

	/**			The box an export starts from: the ROI, else the region set, else the whole view
	 * <p>		An ROI drawn on a view is the most explicit thing the user can be doing, so it wins;
	 * <br>		failing that, whatever <b>Set region...</b> left, which <b>Whole volume</b> clears.
	 * <br>		With neither, the whole XY extent - never a part of it, which is what an export
	 * <br>		clamped to a wrongly measured extent used to give.
	 * <p>		Package-private and static because it is the rule, and rules are worth a test.
	 *
	 * @param roi		: the active ROI in this view's coordinates, or null
	 * @param region	: the region this window has set, or null for the whole view
	 * @param extent	: {width, height, depth} of the view being exported
	 */
	/** A projection movie's extent as {width, height, 1}: it has one plane per channel and time point. */
	private int[] projectionExtent(OmeZarrDataset dataset, String projection) {
		int[] measured = OmeZarrView.projectionViewExtent(dataset, projection, options(dataset));
		return new int[] { measured[0], measured[1], 1 };
	}

	static OmeZarrView.Bounds defaultExportBox(Rectangle roi, OmeZarrView.Bounds region, int[] extent) {
		OmeZarrView.Bounds box;
		if (roi != null && roi.width > 0 && roi.height > 0)
			box = new OmeZarrView.Bounds(roi.x, roi.y, roi.width, roi.height, 0, extent[2]);
		else if (region != null) box = region.copy();
		else box = OmeZarrView.Bounds.full(extent[0], extent[1], extent[2]);
		return box.clampedTo(extent[0], extent[1], extent[2]);
	}

	/** A name that says what the export is: the dataset, the view where there is one, and "region". */
	private static String exportName(Entry entry, String viewKey) {
		String base = entry.root().getName().replaceAll("(?i)[.]ome[.]zarr$", "");
		return base + (viewKey == null || TiffResultDataset.VOLUME.equals(viewKey) ? "" : "-" + viewKey)
				+ "-region";
	}

	/**
	 * Materialise the active ROI directly from intersecting Zarr chunks.
	 *
	 * <p>This deliberately does not duplicate an already-open virtual stack. ImageJ's generic
	 * duplicate path can only ask a {@code VirtualStack} for complete XY planes, so it reads the
	 * complete dataset plane by plane and crops afterwards. Supplying {@link OmeZarrView.Bounds}
	 * before materialisation lets {@link OmeZarrPlaneReader} fetch only chunks touching the ROI.
	 */
	private void materialiseActiveRoi() {
		if (busy) return;
		Entry entry = selectedEntry();
		if (entry == null) { IJ.showMessage(TITLE, "Select a dataset first."); return; }
		if (entry.isTiff()) { materialiseActiveRoiFromTiff(entry.tiff); return; }
		final OmeZarrDataset dataset = entry.zarr;

		Rectangle active = OmeZarrRoi.activeRegion(dataset.getRoot());
		if (active == null || active.width < 1 || active.height < 1) {
			IJ.showMessage(TITLE, "Draw an ROI on a view of the selected OME-Zarr dataset first.");
			return;
		}

		final OmeZarrView.Options requested = options(dataset);
		final int[] extent;
		final List<String> outputLabels;
		try {
			extent = OmeZarrView.viewExtent(dataset, requested);
			outputLabels = OmeZarrView.outputChannelLabels(dataset, requested);
		} catch (RuntimeException error) { showError(error); return; }

		Rectangle clipped = active.intersection(new Rectangle(0, 0, extent[0], extent[1]));
		if (clipped.width < 1 || clipped.height < 1) {
			IJ.showMessage(TITLE, "The active ROI does not overlap the current volume view.");
			return;
		}

		int committed = dataset.getTimepointCount();
		GenericDialogPlus dialog = new OpmDialogPlus("Materialise OME-Zarr ROI");
		Parameter.styleDialog(dialog);
		dialog.addMessage("Active ROI bounding box: x=" + clipped.x + ", y=" + clipped.y
				+ ", width=" + clipped.width + ", height=" + clipped.height + ".\n"
				+ "The ROI is interpreted in the currently selected Runtime view coordinates.\n"
				+ "Output channels: " + outputLabels + ".\n"
				+ "C, Z and T ranges are 1 based and inclusive.");
		dialog.addNumericField("first channel", 1, 0);
		dialog.addNumericField("last channel", outputLabels.size(), 0);
		dialog.addNumericField("first z", 1, 0);
		dialog.addNumericField("last z", extent[2], 0);
		dialog.addNumericField("first timepoint", 1, 0);
		dialog.addNumericField("last timepoint", committed, 0);
		dialog.showDialog();
		if (dialog.wasCanceled()) return;

		final int[] channelRange;
		final int[] zRange;
		final int[] timeRange;
		try {
			channelRange = inclusiveRange(dialog.getNextNumber(), dialog.getNextNumber(),
					outputLabels.size(), "channel");
			zRange = inclusiveRange(dialog.getNextNumber(), dialog.getNextNumber(),
					extent[2], "z");
			timeRange = inclusiveRange(dialog.getNextNumber(), dialog.getNextNumber(),
					committed, "timepoint");
		} catch (IllegalArgumentException error) {
			IJ.showMessage(TITLE, error.getMessage());
			return;
		}

		requested.firstOutputChannel = channelRange[0];
		requested.outputChannelCount = channelRange[1];
		requested.bounds = new OmeZarrView.Bounds(clipped.x, clipped.y,
				clipped.width, clipped.height, zRange[0], zRange[0] + zRange[1]);

		long perTimepoint;
		try {
			perTimepoint = OmeZarrView.estimateMaterializedBytes(dataset, requested, timeRange[0]);
		} catch (RuntimeException error) { showError(error); return; }
		long bytes = saturatedMultiply(perTimepoint, timeRange[1]);
		YesNoCancelDialog confirm = new YesNoCancelDialog(this, "Materialise OME-Zarr ROI",
				"Materialise " + clipped.width + "x" + clipped.height + ", C="
				+ channelRange[1] + ", Z=" + zRange[1] + ", T=" + timeRange[1]
				+ "?\nApproximately " + IJ.d2s(bytes / 1048576.0, 1)
				+ " MB of pixel memory will be allocated.\n"
				+ "Only Zarr chunks intersecting the ROI will be read.");
		if (!confirm.yesPressed()) return;

		startRoiMaterialization(dataset, requested, timeRange[0], timeRange[1]);
	}

	/**
	 * The same shortcut over a TIFF result: the active ROI plus a C, Z and T range.
	 *
	 * <p>What it avoids is what it avoids for OME-Zarr - ImageJ's generic duplicate, which can
	 * only ask a {@code VirtualStack} for whole planes and so reads the entire dataset and
	 * crops afterwards. Here the saving is in planes rather than in chunks: only the selected
	 * channels, Z planes and time points are opened at all, and within a plane only the rows
	 * the ROI covers are inflated.
	 */
	private void materialiseActiveRoiFromTiff(final TiffResultDataset dataset) {
		final String viewKey = selectedTiffView();
		final TiffResultDataset.View view = dataset.getView(viewKey);
		if (view == null || view.frameCount() < 1) {
			IJ.showMessage(TITLE, "This result has no " + viewKey + " written yet.");
			return;
		}
		Rectangle active = OmeZarrRoi.activeRegion(dataset.getRoot());
		if (active == null || active.width < 1 || active.height < 1) {
			IJ.showMessage(TITLE, "Draw an ROI on a view of the selected TIFF result first.");
			return;
		}

		final TiffResultView.Options base = tiffOptions();
		final int[] extent;
		final List<String> outputLabels;
		final int available;
		try {
			extent = TiffResultView.viewExtent(dataset, viewKey, base);
			outputLabels = TiffResultView.outputChannelLabels(dataset, viewKey, base);
			available = TiffResultView.availableFrameCount(dataset, viewKey, base);
		}
		catch (Throwable error) { showError(error); return; }
		if (available < 1) {
			IJ.showMessage(TITLE, "No time point is complete across the selected TIFF sources yet.");
			return;
		}

		Rectangle clipped = active.intersection(new Rectangle(0, 0, extent[0], extent[1]));
		if (clipped.width < 1 || clipped.height < 1) {
			IJ.showMessage(TITLE, "The active ROI does not overlap the current view.");
			return;
		}

		GenericDialogPlus dialog = new OpmDialogPlus("Materialise TIFF ROI");
		Parameter.styleDialog(dialog);
		dialog.addMessage("Active ROI bounding box: x=" + clipped.x + ", y=" + clipped.y
				+ ", width=" + clipped.width + ", height=" + clipped.height + ".\n"
				+ "View: " + viewKey + ", C=" + outputLabels.size() + ", Z=" + extent[2]
				+ ", " + available + " time point(s).\n"
				+ "Output channels: " + outputLabels + ".\n"
				+ "C, Z and T ranges are 1 based and inclusive.");
		dialog.addNumericField("first channel", 1, 0);
		dialog.addNumericField("last channel", outputLabels.size(), 0);
		dialog.addNumericField("first z", 1, 0);
		dialog.addNumericField("last z", extent[2], 0);
		dialog.addNumericField("first timepoint", 1, 0);
		dialog.addNumericField("last timepoint", available, 0);
		dialog.showDialog();
		if (dialog.wasCanceled()) return;

		final int[] channelRange;
		final int[] zRange;
		final int[] timeRange;
		try {
			channelRange = inclusiveRange(dialog.getNextNumber(), dialog.getNextNumber(),
					outputLabels.size(), "channel");
			zRange = inclusiveRange(dialog.getNextNumber(), dialog.getNextNumber(),
					extent[2], "z");
			timeRange = inclusiveRange(dialog.getNextNumber(), dialog.getNextNumber(),
					available, "timepoint");
		} catch (IllegalArgumentException error) {
			IJ.showMessage(TITLE, error.getMessage());
			return;
		}

		final TiffResultView.Options requested = base.copy();
		requested.firstOutputChannel = channelRange[0];
		requested.outputChannelCount = channelRange[1];
		requested.bounds = new OmeZarrView.Bounds(clipped.x, clipped.y,
				clipped.width, clipped.height, zRange[0], zRange[0] + zRange[1]);

		long bytes;
		try {
			bytes = saturatedMultiply(
					TiffResultView.estimateMaterialisedBytes(dataset, viewKey, requested),
					timeRange[1]);
		} catch (Throwable error) { showError(error); return; }
		YesNoCancelDialog confirm = new YesNoCancelDialog(this, "Materialise TIFF ROI",
				"Materialise " + clipped.width + "x" + clipped.height + ", C=" + channelRange[1]
				+ ", Z=" + zRange[1] + ", T=" + timeRange[1] + "?\nApproximately "
				+ IJ.d2s(bytes / 1048576.0, 1) + " MB of pixel memory will be allocated.\n"
				+ "Only the selected planes are opened, and only the ROI rows are inflated.");
		if (!confirm.yesPressed()) return;

		final int firstTimepoint = timeRange[0];
		final int frames = timeRange[1];
		busy = true;
		status.setText("Materialising ROI from the selected TIFF planes...");
		Thread worker = Shutdown.daemon(new Runnable() {
			@Override public void run() {
				try {
					final long started = System.nanoTime();
					final ImagePlus result = TiffResultView.openMaterialised(
							dataset, viewKey, requested, firstTimepoint, frames);
					SwingUtilities.invokeLater(new Runnable() {
						@Override public void run() {
							if (released.get()) { result.changes = false; result.close(); return; }
							result.show();
							refitIfOpenedAtMinimumZoom(result);
							busy = false;
							status.setText("Materialised ROI in "
									+ IJ.d2s((System.nanoTime() - started) / 1e9, 3) + " s.");
						}
					});
				} catch (final Throwable error) {
					SwingUtilities.invokeLater(new Runnable() {
						@Override public void run() {
							if (released.get()) return;
							busy = false;
							showError(error);
						}
					});
				}
			}
		}, "OPM-tiff-materialise-ROI");
		worker.start();
	}

	/** Start a bounded-region read without blocking the viewer or any open image window. */
	private void startRoiMaterialization(final OmeZarrDataset dataset,
			final OmeZarrView.Options requested, final int firstTimepoint, final int frames) {
		busy = true;
		status.setText("Materialising ROI from intersecting OME-Zarr chunks...");
		Thread worker = Shutdown.daemon(new Runnable() {
			@Override public void run() {
				try {
					final long started = System.nanoTime();
					final ImagePlus result = OmeZarrView.openMaterializedVolume(
							dataset, requested, firstTimepoint, frames);
					SwingUtilities.invokeLater(new Runnable() {
						@Override public void run() {
							if (released.get()) {
								result.changes = false;
								result.close();
								return;
							}
							result.show();
							refitIfOpenedAtMinimumZoom(result);
							busy = false;
							status.setText("Materialised ROI in "
									+ IJ.d2s((System.nanoTime() - started) / 1e9, 3) + " s.");
						}
					});
				} catch (final Throwable error) {
					SwingUtilities.invokeLater(new Runnable() {
						@Override public void run() {
							if (released.get()) return;
							busy = false;
							showError(error);
						}
					});
				}
			}
		}, "OPM-Zarr-materialise-ROI");
		worker.start();
	}

	/** Convert two 1-based inclusive dialog values to {zero-based first, count}. */
	private static int[] inclusiveRange(double askedFirst, double askedLast,
			int available, String axis) {
		if (Double.isNaN(askedFirst) || Double.isInfinite(askedFirst)
				|| Double.isNaN(askedLast) || Double.isInfinite(askedLast)
				|| askedFirst != Math.rint(askedFirst) || askedLast != Math.rint(askedLast))
			throw new IllegalArgumentException("The " + axis + " range must contain whole numbers.");
		int first = (int) askedFirst;
		int last = (int) askedLast;
		if (first < 1 || last < first || last > available)
			throw new IllegalArgumentException("The " + axis + " range must be within 1-"
					+ available + " and first must not exceed last.");
		return new int[] { first - 1, last - first + 1 };
	}

	private static long saturatedMultiply(long left, long right) {
		if (left <= 0 || right <= 0) return 0;
		return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
	}

	/**			The view's own output extent, which the region is measured against
	 * <p>		Opened and closed immediately: only the numbers are wanted, and asking the
	 * 			renderer is the only way to account for a side-by-side view being twice as wide
	 * 			as the stored array.
	 */
	private int[] viewExtent(OmeZarrDataset dataset) {
		OmeZarrView.Options probe = options(dataset);
		probe.bounds = null;
		return OmeZarrView.viewExtent(dataset, probe);
	}

	private void updateRegionLabel() {
		regionLabel.setText(region == null ? "whole volume" : region.toString());
	}

	/**
	 * Poll the dataset's own commit marker for growth.
	 * <p>
	 * Only {@code .zattrs} is watched, by modification time, and only re-parsed when that
	 * changes. A recursive WatchService over the chunk tree is the obvious alternative and the
	 * wrong one: a single production dataset here holds over 645,000 chunk files, and none of
	 * them is safe to read until the commit marker says so.
	 * <p>
	 * What makes this safe is a property of the writer, not of this class. A time point is
	 * counted in {@code opm_write_state.committedTimepoints} only after every one of its chunks
	 * has been verified present, and {@code .zattrs} is replaced through a temporary file and
	 * an atomic rename. A reader that never looks past {@code committedTimepoints} therefore
	 * cannot observe a half-written time point, however busy the writer is.
	 */
	/** What a deskew run asked this window to show, held until the scan that finds it. */
	private static final class LiveRequest {
		final File root;
		final boolean preferTiff;
		final boolean projection;
		final boolean volume;
		final boolean projectionVirtual;
		/** How the run composes its channels; null leaves the dataset's recorded layout. */
		final DeskewChannelView channelView;
		/** The projections the run writes, one window each; see {@link LivePreview#choose(List, List)}. */
		final List<String> projections;

		LiveRequest(File root, boolean preferTiff, boolean projection, boolean volume,
				boolean projectionVirtual, DeskewChannelView channelView, List<String> projections) {
			this.root = root;
			this.preferTiff = preferTiff;
			this.projection = projection;
			this.volume = volume;
			this.projectionVirtual = projectionVirtual;
			this.channelView = channelView;
			this.projections = projections;
		}
	}

	/**
	 * Show a run that is being written, in this window, as that run's live preview.
	 *
	 * <p>Deskew Batch and Live Processing used to open preview windows of their own. They were
	 * built by the same calls this viewer uses, which is why they could be replaced by this: a
	 * preview is now literally the viewer pointed at the output with live update on, so there
	 * is one set of windows, one growth path, and controls over them while the run continues.
	 * Following the run, extending the views, and giving up on a view the user closed are all
	 * already this window's behaviour.
	 *
	 * <p>Which format is previewed is the caller's decision, because the caller is what chose
	 * the output format: a TIFF-only run has no store to read, and a run writing OME-Zarr is
	 * previewed from it whether or not it is also writing TIFF, since the store commits one
	 * time point at a time.
	 *
	 * @param root				: the OME-Zarr root, or the folder the TIFF results are under
	 * @param preferTiff		: read the TIFF results rather than a store, for a TIFF-only run
	 * @param projection		: open a projection movie
	 * @param volume			: open the 5-D volume
	 * @param projectionVirtual	: open the projection movie virtually, so it can grow
	 * @param channelView		: how the run composes its channels, applied to an OME-Zarr
	 * 							  preview so it shows the run's result; null for the dataset's own
	 * @param projections		: the projections the run writes, each opened in a window of its own
	 */
	public static void showLive(final File root, final boolean preferTiff,
			final boolean projection, final boolean volume, final boolean projectionVirtual,
			final DeskewChannelView channelView, final List<String> projections) {
		if (root == null || (!projection && !volume)) return;
		if (IJ.getInstance() == null) return;		// headless: there is no window to raise
		SwingUtilities.invokeLater(new Runnable() {
			@Override public void run() {
				OpmDataViewer viewer = liveInstance;
				/* A frame on its way out still holds the live instance until its windowClosed
				 * arrives; constructing now would find it, dispose itself and hand the request
				 * to a dead window. */
				if (viewer != null && (!viewer.isDisplayable() || viewer.released.get())) {
					liveInstance = null;
					viewer = null;
				}
				if (viewer == null) {
					viewer = new OpmDataViewer(false);
					liveInstance = viewer;
				}
				if (viewer.getState() == Frame.ICONIFIED) viewer.setState(Frame.NORMAL);
				viewer.setVisible(true);
				WindowManager.toFront(viewer);
				viewer.requestLive(new LiveRequest(root, preferTiff, projection, volume,
						projectionVirtual, channelView, projections));
			}
		});
	}

	private void requestLive(LiveRequest request) {
		liveRequest = request;
		liveRescans = 0;
		liveRetries = 0;
		path.setText(request.root.getAbsolutePath());
		status.setText("Preparing a live preview of " + request.root.getName() + "...");
		scanFor(request);
	}

	/**
	 * Scan for a live request now, or as soon as the window is free to.
	 * <p>
	 * {@link #scan} returns without doing anything while the window is busy, and a live request
	 * is only ever applied when a scan finishes. A run raising its preview while the user was
	 * opening a view therefore used to be dropped outright, with nothing left to retry it.
	 */
	private void scanFor(final LiveRequest request) {
		if (released.get() || liveRequest != request) return;
		if (!busy) { scan(); return; }
		javax.swing.Timer retry = new javax.swing.Timer(250, new ActionListener() {
			@Override public void actionPerformed(ActionEvent e) { scanFor(request); }
		});
		retry.setRepeats(false);
		retry.start();
	}

	/**
	 * Select what the run asked for, switch live update on, and open the requested views.
	 *
	 * <p>The views are opened here rather than through Open because Open guards itself with
	 * the busy flag, and two of them in a row would silently drop the second.
	 */
	private void applyLiveRequest(File scanned) {
		final LiveRequest request = liveRequest;
		if (request == null) return;
		/*			The scan that has just finished is not always this request's own
		 * <p>		This window scans the folder it remembers as soon as it is opened, and
		 * 			scan() refuses a second scan while one is running. A run that raises the
		 * 			preview therefore had its scan dropped, and what has just been found is the
		 * 			previously browsed folder's datasets. Selecting from those showed somebody
		 * 			else's results as the live preview - silently, because everything about the
		 * 			window otherwise looked right, including the path. */
		if (!sameFolder(scanned, request.root)) {
			if (++liveRescans > MAX_LIVE_RESCANS) {
				liveRequest = null;
				liveRescans = 0;
				status.setText("Could not point the live preview at " + request.root + ".");
				return;
			}
			path.setText(request.root.getAbsolutePath());
			scanFor(request);
			return;
		}
		liveRequest = null;
		liveRescans = 0;
		/*			The run's own dataset, not simply the first of its format
		 * <p>		Several acquisitions can share one result folder, and their names sort in
		 * 			no order that says which is being written. The one with the newest file is
		 * 			the one the run is producing. */
		Entry chosen = null;
		long newest = Long.MIN_VALUE;
		for (int i = 0; i < datasets.getItemCount(); i++) {
			Entry entry = datasets.getItemAt(i);
			if (entry.isTiff() != request.preferTiff) continue;
			long written = lastWritten(entry);
			if (chosen == null || written > newest) { chosen = entry; newest = written; }
		}
		if (chosen == null && datasets.getItemCount() > 0) chosen = datasets.getItemAt(0);
		if (chosen == null) { waitForFirstResult(request); return; }
		liveRetries = 0;
		datasets.setSelectedItem(chosen);

		/* The run's own composition, not the store's. This used to force stored channels,
		 * which for a whole-image run laid the two unflipped halves over each other - a result
		 * the run never wrote. The concern behind it, a rough-overlay warning per time point,
		 * no longer applies: views grow in place rather than being rebuilt per time point, and
		 * the preview's projection is maxZ, which the XY alignment carries exactly. Set through
		 * the controls, so what the window says is what the views are. A TIFF result has its
		 * composition in its pixels and needs nothing. */
		if (request.channelView != null) {
			if (chosen.isTiff() && !tiffComposable(chosen)) {
				/* Nothing to set - this TIFF has its channels in its pixels - but the greyed
				 * controls can say how, instead of showing a choice that does not apply. */
				tiffLayouts.put(chosen.root(), request.channelView);
				updateEnabledControls();
			} else {
				if (chosen.isTiff()) adoptTiffSetup(chosen, request.channelView);
				applyChannelView(request.channelView);
				status.setText("Channels set to how this run is deskewing: "
						+ request.channelView.describedAs + ".");
			}
		}
		live.setSelected(true);
		startWatching();
		openLiveViews(chosen, request);
	}

	/** Whether two paths name the same folder, resolving links and relative segments. */
	private static boolean sameFolder(File a, File b) {
		if (a == null || b == null) return false;
		try {
			return a.getCanonicalFile().equals(b.getCanonicalFile());
		} catch (java.io.IOException unresolved) {
			return a.getAbsoluteFile().equals(b.getAbsoluteFile());
		}
	}

	/**			When this dataset was last written to
	 * <p>		An OME-Zarr says so in its commit marker; a TIFF result in the newest file of
	 * 			any of its views, since a run interrupted between the volume and its
	 * 			projections leaves them at different lengths.
	 *
	 * @return					: the modification time, or {@link Long#MIN_VALUE} if unknown
	 */
	private static long lastWritten(Entry entry) {
		if (entry == null) return Long.MIN_VALUE;
		if (!entry.isTiff()) {
			File attrs = new File(entry.zarr.getRoot(), ".zattrs");
			return attrs.isFile() ? attrs.lastModified() : Long.MIN_VALUE;
		}
		long newest = Long.MIN_VALUE;
		for (String key : allViews(entry.tiff)) {
			TiffResultDataset.View view = entry.tiff.getView(key);
			if (view == null || view.frameCount() < 1) continue;
			long modified = view.frame(view.frameCount() - 1).file.lastModified();
			if (modified > newest) newest = modified;
		}
		return newest;
	}

	/**
	 * Keep looking until the run writes something, then show it.
	 *
	 * <p>A preview is arranged before the first result exists - Batch raises it as the TIFF
	 * phase starts, and Live as the first file arrives - so the first scan legitimately finds
	 * nothing. Re-scanning at the poll interval is what turns that into a preview that appears
	 * when the run does, rather than one that never appears at all.
	 *
	 * <p>Bounded, because a run that fails before writing anything must not leave this window
	 * scanning a folder for the rest of the session. The user can still point it at the folder
	 * by hand, which is what the message says.
	 */
	private void waitForFirstResult(final LiveRequest request) {
		if (++liveRetries > MAX_LIVE_RETRIES) {
			liveRetries = 0;
			status.setText("Nothing has been written under " + request.root
					+ " yet; stopped waiting. Press Scan when there is.");
			return;
		}
		liveRequest = request;
		status.setText("Waiting for the first result under " + request.root.getName()
				+ " (" + liveRetries + "/" + MAX_LIVE_RETRIES + ")...");
		javax.swing.Timer retry = new javax.swing.Timer(pollInterval() * 1000, new ActionListener() {
			@Override public void actionPerformed(ActionEvent e) { scanFor(request); }
		});
		retry.setRepeats(false);
		retry.start();
	}

	/** Build the requested views on one worker, so they arrive in order and neither is dropped. */
	private void openLiveViews(final Entry entry, final LiveRequest request) {
		/* Read on this thread, before the worker starts: the controls are Swing's, and the
		 * channel view has only just been applied to them. */
		final OmeZarrView.Options zarrOptions = entry.isTiff() ? null : options(entry.zarr);
		final TiffResultView.Options tiffOptions = entry.isTiff() ? tiffOptions() : null;
		/* Every projection the run writes, not one: see LivePreview.projections. */
		final List<String> projectionKeys = request.projection
				? LivePreview.choose(entry.projections(), request.projections)
				: java.util.Collections.<String>emptyList();
		busy = true;
		status.setText("Opening live view(s) of " + entry.displayName() + "...");
		Thread worker = Shutdown.daemon(new Runnable() {
			@Override public void run() {
				final List<ManagedView> opened = new ArrayList<ManagedView>();
				for (String projectionKey : projectionKeys) {
					ImagePlus image = openLiveView(entry, projectionKey, request.projectionVirtual,
							zarrOptions, tiffOptions);
					if (image != null) opened.add(entry.isTiff()
							? ManagedView.tiff(image, entry.root(), projectionKey, OpenMode.PROJECTION,
									-1, request.projectionVirtual, true, tiffOptions)
							: new ManagedView(image, entry.root(), projectionKey,
									OpenMode.PROJECTION, -1, request.projectionVirtual, true, zarrOptions));
				}
				if (request.volume) {
					ImagePlus image = openLiveView(entry, null, true, zarrOptions, tiffOptions);
					if (image != null) opened.add(entry.isTiff()
							? ManagedView.tiff(image, entry.root(), VOLUME_KIND, OpenMode.VOLUME_ALL,
									-1, true, true, tiffOptions)
							: new ManagedView(image, entry.root(), VOLUME_KIND,
									OpenMode.VOLUME_ALL, -1, true, true, zarrOptions));
				}
				SwingUtilities.invokeLater(new Runnable() {
					@Override public void run() {
						busy = false;
						if (released.get()) {
							for (ManagedView view : opened) discard(view.image);
							return;
						}
						int shown = 0;
						for (ManagedView view : opened) {
							present(view);
							shown++;
						}
						status.setText(shown + " live view(s) open; following "
								+ entry.displayName() + " every " + pollInterval() + " s.");
					}
				});
			}
		}, "OPM-live-preview-open");
		worker.start();
	}

	/**
	 * One live view: a projection movie when a projection key is given, else the volume.
	 *
	 * <p>Always virtual for the volume. A materialised 5-D view of a run in progress would load
	 * everything written so far and then stop following it, and would compete for memory with
	 * the deskew that is producing it.
	 */
	private static ImagePlus openLiveView(Entry entry, String projectionKey, boolean openVirtual,
			OmeZarrView.Options zarrOptions, TiffResultView.Options tiffOptions) {
		try {
			if (entry.isTiff()) {
				String key = projectionKey == null ? TiffResultDataset.VOLUME : projectionKey;
				if (entry.tiff.getView(key) == null) return null;
				return openVirtual
						? TiffResultView.openVirtual(entry.tiff, key, tiffOptions, -1)
						: TiffResultView.openMaterialised(entry.tiff, key, tiffOptions, 0,
								entry.tiff.getView(key).frameCount());
			}
			if (projectionKey != null)
				return openVirtual
						? OmeZarrView.openVirtualProjectionMovie(entry.zarr, projectionKey, zarrOptions)
						: OmeZarrView.openMaterializedProjectionMovie(entry.zarr, projectionKey, zarrOptions);
			return OmeZarrView.openVirtualVolume(entry.zarr, zarrOptions, -1);
		} catch (Throwable failure) {
			IJ.log("OPM live preview could not open "
					+ (projectionKey == null ? "the volume" : projectionKey) + " of "
					+ entry.displayName() + ": " + failure);
			return null;
		}
	}

	private void startWatching() {
		stopWatching();
		Entry entry = selectedEntry();
		if (entry == null) {
			live.setSelected(false);
			IJ.showMessage(TITLE, "Select a dataset before switching live update on.");
			return;
		}
		if (entry.isTiff()) { startWatchingTiff(entry.tiff); return; }
		final OmeZarrDataset dataset = entry.zarr;
		final File attrs = new File(dataset.getRoot(), ".zattrs");
		if (!attrs.isFile()) {
			live.setSelected(false);
			IJ.showMessage(TITLE, "This dataset has no .zattrs to watch:\n" + dataset.getRoot());
			return;
		}
		Prefs.set(POLL_KEY, pollInterval());
		seenAttrsModified = attrs.lastModified();
		seenCommitted = dataset.getTimepointCount();
		watching = true;
		watcher = new Thread(new Runnable() {
			@Override public void run() {
				while (watching) {
					try {
						Thread.sleep(pollInterval() * 1000L);
					} catch (InterruptedException stopped) {
						return;
					}
					if (!watching) return;
					try {
						long modified = attrs.lastModified();
						if (modified == seenAttrsModified) continue;
						seenAttrsModified = modified;
						final OmeZarrDataset fresh = OmeZarrDataset.read(dataset.getRoot());
						final int committed = fresh.getTimepointCount();
						if (committed <= seenCommitted) continue;
						final int added = committed - seenCommitted;
						seenCommitted = committed;
						SwingUtilities.invokeLater(new Runnable() {
							@Override public void run() { datasetGrew(fresh, added); }
						});
					} catch (Throwable transientFailure) {
						// A writer mid-rename is normal; try again on the next tick.
						IJ.log("OME-Zarr live: " + transientFailure);
					}
				}
			}
		}, "OME-Zarr-live-watch");
		watcher.setDaemon(true);
		watcher.start();
		status.setText("Watching " + dataset.getDisplayName() + " (" + seenCommitted
				+ " committed) for new time points, every " + pollInterval() + " s.");
	}

	/**
	 * Follow a TIFF result that is still being written.
	 *
	 * <p>There is no manifest to watch here as there is for OME-Zarr, so the folder itself is
	 * the signal: a re-scan finds the files that have appeared. What makes that safe is
	 * {@link TiffCompletionCheck}, which the dataset applies before accepting a file - a TIFF
	 * halfway through being written has an IFD chain or strip offsets that do not reach its own
	 * end, and is passed over until it does. A file is therefore never read while the deskew
	 * is still writing it, which is the whole hazard of reading a run in progress.
	 */
	private void startWatchingTiff(final TiffResultDataset dataset) {
		Prefs.set(POLL_KEY, pollInterval());
		final String viewKey = selectedTiffView();
		final TiffResultView.Options watchOptions = tiffOptions();
		try { seenCommitted = TiffResultView.availableFrameCount(dataset, viewKey, watchOptions); }
		catch (IOException unreadable) { seenCommitted = dataset.getTimepointCount(); }
		final Entry watched = selectedEntry();
		final String family = tiffFamily(dataset);
		/* How many series this acquisition was listed with. A second acquisition channel's
		 * first file arrives after the scan that listed the first one, and refreshing a
		 * dataset only ever finds more files of that same series - a series that did not
		 * exist yet is found by discovery alone. Without this the run's own preview would
		 * follow one channel for the whole acquisition. */
		final int[] seriesListed = { watched == null ? 1 : watched.series.size() };
		watching = true;
		watcher = Shutdown.daemon(new Runnable() {
			@Override public void run() {
				while (watching) {
					try {
						Thread.sleep(pollInterval() * 1000L);
					} catch (InterruptedException stopped) { return; }
					if (!watching) return;
					try {
						if (acquisitionChannel(dataset) > 0) {
							int found = countFamilySeries(dataset.getRoot(), family);
							if (found > seriesListed[0]) {
								seriesListed[0] = found;
								/* Cleared before the hand-over so the flag is never true over a
								 * thread that has stopped; the re-listing starts a fresh watch. */
								watching = false;
								SwingUtilities.invokeLater(new Runnable() {
									@Override public void run() { adoptGrownTiffFamily(dataset, family); }
								});
								return;
							}
						}
						final int added = TiffResultView.refreshSources(dataset, viewKey, watchOptions);
						final int reached = TiffResultView.availableFrameCount(
								dataset, viewKey, watchOptions);
						if (added <= 0 && reached <= seenCommitted) continue;
						seenCommitted = reached;
						SwingUtilities.invokeLater(new Runnable() {
							@Override public void run() { tiffGrew(dataset, added, reached); }
						});
					} catch (Throwable transientFailure) {
						// a writer mid-rename is normal; try again on the next tick
						IJ.log("OPM TIFF live: " + transientFailure);
					}
				}
			}
		}, "OPM-tiff-live-watch");
		watcher.start();
		status.setText("Watching " + dataset.getDisplayName() + " (" + seenCommitted
				+ " time point(s)) for new files, every " + pollInterval() + " s.");
	}

	/** Extend every virtual TIFF view this window opened, and refresh what the panel says. */
	private void tiffGrew(TiffResultDataset dataset, int added, int reached) {
		setTimepointRange(reached);
		details.setText(summary(dataset));
		details.setCaretPosition(0);
		String message = "+" + added + " file(s), " + reached + " time point(s)";
		IJ.log("OPM TIFF live: " + dataset.getDisplayName() + " " + message);

		int grown = 0;
		int closed = views.prune();
		/* This dataset's views only. Another dataset's view grown from this descriptor would
		 * take on time points that are not its own. The watcher has refreshed the folder for
		 * this tick already, so the views need not walk it again each. */
		for (ManagedView view : views.of(dataset.getRoot()))
			if (view.virtual && TiffResultView.growVirtualView(view.image, dataset, false) > 0) grown++;
		status.setText(message + "; " + grown + " open view(s) extended"
				+ (closed > 0 ? ", " + closed + " closed since" : "") + ".");
	}

	private void stopWatching() {
		watching = false;
		Thread running = watcher;
		watcher = null;
		if (running != null) running.interrupt();
	}

	/** How many series of one family the folder holds now; discovery is the only thing that sees a new one. */
	private static int countFamilySeries(File root, String family) {
		int found = 0;
		for (TiffResultDataset candidate : TiffResultDataset.discover(root))
			if (family.equals(tiffFamily(candidate))) found++;
		return found;
	}

	/**
	 * Re-list an acquisition that has gained an acquisition-channel series, without stopping.
	 * <p>
	 * The entry is replaced in place, as a growth tick replaces a store's descriptor, so the
	 * watch and the ticked box survive; the composition controls gain the new halves, and the
	 * open views are rebuilt through the same path a channel change takes - which restarts the
	 * watch on the fuller family.
	 */
	private void adoptGrownTiffFamily(TiffResultDataset dataset, String family) {
		int index = datasets.getSelectedIndex();
		if (index < 0 || released.get()) return;
		List<TiffResultDataset> members = new ArrayList<TiffResultDataset>();
		for (TiffResultDataset candidate : TiffResultDataset.discover(dataset.getRoot()))
			if (family.equals(tiffFamily(candidate))) members.add(candidate);
		if (members.size() < 2) { if (live.isSelected()) startWatching(); return; }
		Collections.sort(members, new Comparator<TiffResultDataset>() {
			@Override public int compare(TiffResultDataset a, TiffResultDataset b) {
				return Integer.compare(acquisitionChannel(a), acquisitionChannel(b));
			}
		});
		Entry grown = new Entry(members);
		Object selection = selections.getSelectedItem();
		suppressDatasetChanged = true;
		composingControls++;
		try {
			datasets.removeItemAt(index);
			datasets.insertItemAt(grown, index);
			datasets.setSelectedIndex(index);
			selections.removeAllItems();
			selections.addItem(OmeZarrView.SELECT_CONFIGURED);
			for (String option : OmeZarrView.selectionOptions(tiffChannelLabels(grown.series)))
				selections.addItem(option);
			selections.setSelectedItem(selection == null ? OmeZarrView.SELECT_ALL : selection);
			if (selections.getSelectedItem() == null) selections.setSelectedItem(OmeZarrView.SELECT_ALL);
		} finally {
			composingControls--;
			suppressDatasetChanged = false;
		}
		details.setText(summary(grown));
		details.setCaretPosition(0);
		status.setText("_Channel#### series " + members.size()
				+ " joined this acquisition; it is listed as one and the views follow it.");
		updateEnabledControls();
		recomposeOpenViews(true);
		if (live.isSelected()) startWatching();
	}

	/** Reflect newly committed time points in the controls, the summary and the open views. */
	private void datasetGrew(OmeZarrDataset fresh, int added) {
		int index = datasets.getSelectedIndex();
		if (index >= 0) {
			suppressDatasetChanged = true;
			try {
				datasets.removeItemAt(index);
				datasets.insertItemAt(new Entry(fresh), index);
				datasets.setSelectedIndex(index);
			} finally {
				suppressDatasetChanged = false;
			}
		}
		setTimepointRange(fresh.getTimepointCount());
		details.setText(summary(fresh));
		details.setCaretPosition(0);
		String message = "+" + added + " time point(s), " + fresh.getTimepointCount() + " committed";
		IJ.log("OME-Zarr live: " + fresh.getDisplayName() + " " + message);

		int grown = 0;
		int closed = views.prune();
		for (ManagedView view : views.of(fresh.getRoot()))
			if (view.virtual && OmeZarrView.growVirtualView(view.image, fresh) > 0) grown++;
		status.setText(message + "; " + grown + " open view(s) extended"
				+ (closed > 0 ? ", " + closed + " closed since" : "") + ".");
	}

	// ---- the views this window owns -----------------------------------------------------

	/** The registry kind of every volume view, single time point or all of them. */
	static final String VOLUME_KIND = "volume";

	/** What a view is one of: the volume, or the projection it shows. */
	static String kindOf(OpenMode mode, String projection) {
		return mode == OpenMode.PROJECTION && projection != null ? projection : VOLUME_KIND;
	}

	/**			Put a freshly built view on screen as this window's view of its kind
	 * <p>		The view it supersedes - same dataset, same kind - is closed once the new one is
	 * 			up, and the new one takes its place: its bounds when the image is the same size,
	 * 			so a rebuild does not move or re-zoom anything; its corner otherwise, since a
	 * 			different size fitted into the old bounds would be magnified to fill them. The
	 * 			C, Z and T position comes across where the new view has it, and so does the
	 * 			contrast when the channels are the same channels.
	 * <p>		Then it is grown to what is committed now. It was built on a worker from the
	 * 			descriptor of that moment, and a time point committed in between would otherwise
	 * 			wait for the next one to be noticed.
	 *
	 * @return					: whether an open view was replaced
	 */
	private boolean present(ManagedView view) {
		ManagedView previous = views.find(view.root, view.kind);
		ImagePlus old = previous == null || previous.image.getWindow() == null ? null : previous.image;
		view.live |= previous != null && previous.live && old != null;
		if (view.live && !view.image.getTitle().startsWith(LIVE_PREFIX))
			view.image.setTitle(LIVE_PREFIX + view.image.getTitle());
		view.image.show();
		if (old == null || !takeOverWindow(view.image, old)) refitIfOpenedAtMinimumZoom(view.image);
		views.put(view);
		if (old != null && old != view.image) discard(old);
		catchUp(view);
		return old != null;
	}

	private static final String LIVE_PREFIX = "LIVE ";

	/** Grow a view just presented to what its dataset has committed by now. */
	private void catchUp(ManagedView view) {
		if (!view.virtual) return;
		Entry entry = selectedEntry();
		if (entry == null || !ViewRegistry.sameRoot(entry.root(), view.root)) return;
		try {
			if (entry.isTiff()) TiffResultView.growVirtualView(view.image, entry.tiff, false);
			else OmeZarrView.growVirtualView(view.image, entry.zarr);
		} catch (Throwable unreadable) {
			// the next watch tick tries again; a view that has not caught up yet is not broken
		}
	}

	/**			Give a new window the place, position and contrast of the one it replaces
	 *
	 * @return					: false when the old window was gone and nothing was taken over
	 */
	private static boolean takeOverWindow(ImagePlus fresh, ImagePlus old) {
		ImageWindow oldWindow = old.getWindow();
		ImageWindow newWindow = fresh.getWindow();
		if (oldWindow == null || newWindow == null) return false;
		Rectangle bounds = oldWindow.getBounds();
		if (fresh.getWidth() == old.getWidth() && fresh.getHeight() == old.getHeight())
			newWindow.setLocationAndSize(bounds.x, bounds.y, bounds.width, bounds.height);
		else
			newWindow.setLocation(bounds.x, bounds.y);
		fresh.setPosition(Math.min(old.getC(), fresh.getNChannels()),
				Math.min(old.getZ(), fresh.getNSlices()), Math.min(old.getT(), fresh.getNFrames()));
		carryContrast(old, fresh);
		return true;
	}

	/**
	 * Keep the display ranges the user set, when the rebuilt view shows the same channels.
	 * <p>
	 * Only then: a range tuned for a left half means nothing on a side-by-side whole width, and
	 * a new view contrasts itself from a mid-stack plane anyway. The labels are the ones every
	 * OME-Zarr view is stamped with, so a change of selection or layout is always seen.
	 */
	static void carryContrast(ImagePlus old, ImagePlus fresh) {
		Object before = old.getProperty("opm.channelLabels");
		if (before == null || !before.equals(fresh.getProperty("opm.channelLabels"))
				|| old.getNChannels() != fresh.getNChannels()) return;
		if (old instanceof CompositeImage && fresh instanceof CompositeImage) {
			CompositeImage from = (CompositeImage) old;
			CompositeImage to = (CompositeImage) fresh;
			to.setMode(from.getMode());
			/* Whole tables, through setLuts, not min and max written into the live LUTs: on a
			 * window already showing, setChannelLut is what also reaches the channel
			 * processors being drawn. getLuts hands back copies carrying colour and range. */
			LUT[] tables = from.getLuts();
			if (tables != null && tables.length >= to.getNChannels()) to.setLuts(tables);
			boolean[] wanted = from.getActiveChannels();
			boolean[] active = to.getActiveChannels();
			if (wanted != null && active != null)
				System.arraycopy(wanted, 0, active, 0, Math.min(wanted.length, active.length));
			to.updateAllChannelsAndDraw();
		} else if (!(old instanceof CompositeImage) && !(fresh instanceof CompositeImage)) {
			fresh.setDisplayRange(old.getDisplayRangeMin(), old.getDisplayRangeMax());
			fresh.updateAndDraw();
		}
	}

	/**
	 * Close a view, releasing its reader whether or not it was ever shown.
	 * <p>
	 * {@code ImagePlus.close()} notifies the image listeners only through the window it closes.
	 * A view built and never shown has none, so its renderer's close listener would never run
	 * and the store's reader would stay open; {@code flush()} is what notifies in that case.
	 */
	private static void discard(ImagePlus image) {
		if (image == null) return;
		image.changes = false;
		if (image.getWindow() != null) image.close();
		else image.flush();
	}

	/**			Rebuild the open views of the selected dataset with the composition now chosen
	 * <p>		Reached when {@code Channels / side} or {@code Runtime view} changes, and when
	 * 			{@code Channel setup...} is accepted. A live view keeps growing whatever it was
	 * 			built with, so leaving it alone after a change meant new time points went on
	 * 			arriving in a composition the controls no longer described.
	 * <p>		Virtual views only; each is opened afresh as the same kind, time point and region
	 * 			and put where the old one was. A materialised view would mean reading everything
	 * 			again, so it stays as it was and the status line says so. A region survives
	 * 			unless the view's width changes meaning - side by side is twice as wide as a half
	 * 			- in which case the view is rebuilt whole rather than cropped at the wrong place.
	 * 			A view the new composition cannot show, maxX side by side for one, is left open
	 * 			as it was, with the reason.
	 * <p>		TIFF virtual views are rebuilt by the TIFF-specific branch when their window-local
	 * 			source map changes; materialised snapshots remain as loaded.
	 */
	private void recomposeOpenViews() {
		recomposeOpenViews(false);
	}

	/**
	 * @param force				: rebuild even views already composed this way - after the
	 * 							  alignment matrix in the metadata was replaced, which changes
	 * 							  their pixels without changing any control
	 */
	private void recomposeOpenViews(final boolean force) {
		if (released.get()) return;
		Entry entry = selectedEntry();
		if (entry == null) return;
		if (entry.isTiff()) { recomposeOpenTiffViews(entry.tiff, force); return; }
		if (busy) { queueRecompose(force); return; }
		final OmeZarrDataset dataset = entry.zarr;
		final OmeZarrView.Options chosen = options(dataset);
		final String wantedComposition = compositionOf(chosen);
		views.prune();
		final List<ManagedView> targets = new ArrayList<ManagedView>();
		int snapshots = 0;
		for (ManagedView view : views.of(entry.root())) {
			if (!force && wantedComposition.equals(view.composition)) continue;
			if (view.virtual) targets.add(view);
			else snapshots++;
		}
		if (targets.isEmpty()) {
			if (snapshots > 0) status.setText(snapshots + " materialised view(s) keep the channels"
					+ " they were loaded with; Open again to see the new setup.");
			return;
		}

		final int materialised = snapshots;
		busy = true;
		status.setText("Rebuilding " + targets.size() + " open view(s) as "
				+ chosen.operation + "...");
		Thread worker = Shutdown.daemon(new Runnable() {
			@Override public void run() {
				final List<ManagedView[]> rebuilt = new ArrayList<ManagedView[]>();
				final List<String> refused = new ArrayList<String>();
				int regionsDropped = 0;
				for (ManagedView target : targets) {
					OmeZarrView.Options wanted = chosen.copy();
					boolean widthKeepsMeaning = (target.operation == OmeZarrView.Operation.SIDE_BY_SIDE)
							== (wanted.operation == OmeZarrView.Operation.SIDE_BY_SIDE);
					wanted.bounds = target.bounds != null && widthKeepsMeaning ? target.bounds.copy() : null;
					if (target.bounds != null && !widthKeepsMeaning) regionsDropped++;
					try {
						ImagePlus image = openView(dataset, target.mode,
								target.mode == OpenMode.PROJECTION ? target.kind : null, true,
								Math.max(0, target.timepoint), 1, wanted);
						rebuilt.add(new ManagedView[] { target, new ManagedView(image, target.root,
								target.kind, target.mode, target.timepoint, true, target.live, wanted) });
					} catch (Throwable failure) {
						refused.add(target.kind + " (" + (failure.getMessage() == null
								? failure.getClass().getSimpleName() : failure.getMessage()) + ")");
					}
				}
				final int dropped = regionsDropped;
				SwingUtilities.invokeLater(new Runnable() {
					@Override public void run() {
						busy = false;
						int replaced = 0;
						for (ManagedView[] pair : rebuilt) {
							/* Closed while it was being rebuilt: the user no longer wants it,
							 * and neither is its replacement wanted. */
							if (released.get() || pair[0].image.getWindow() == null) {
								discard(pair[1].image);
								continue;
							}
							present(pair[1]);
							replaced++;
						}
						if (released.get()) return;
						StringBuilder message = new StringBuilder();
						message.append(replaced).append(" open view(s) rebuilt as ").append(chosen.operation).append('.');
						if (dropped > 0) message.append("  ").append(dropped)
								.append(" region(s) dropped: the view's width no longer means the same.");
						if (materialised > 0) message.append("  ").append(materialised)
								.append(" materialised view(s) left as loaded.");
						if (!refused.isEmpty()) {
							message.append("  Left as they were: ").append(refused).append('.');
							IJ.log("OPM data viewer: could not rebuild " + refused);
						}
						status.setText(message.toString());
					}
				});
			}
		}, "OPM-viewer-recompose");
		worker.start();
	}

	/** Rebuild this window's virtual TIFF views after its display-only source map changes. */
	private void recomposeOpenTiffViews(final TiffResultDataset dataset, final boolean force) {
		if (busy) { queueRecompose(force); return; }
		final TiffResultView.Options chosen = tiffOptions();
		final String wantedComposition = TiffResultView.compositionOf(chosen);
		views.prune();
		final List<ManagedView> targets = new ArrayList<ManagedView>();
		int snapshots = 0;
		for (ManagedView view : views.of(dataset.getRoot())) {
			if (!force && wantedComposition.equals(view.composition)) continue;
			if (view.virtual) targets.add(view); else snapshots++;
		}
		if (targets.isEmpty()) {
			if (snapshots > 0) status.setText(snapshots + " materialised view(s) keep the TIFF"
					+ " channels they were loaded with; Open again to see the new setup.");
			return;
		}

		final int materialised = snapshots;
		busy = true;
		status.setText("Rebuilding " + targets.size() + " TIFF view(s) with the new virtual channels...");
		Thread worker = Shutdown.daemon(new Runnable() {
			@Override public void run() {
				final List<ManagedView[]> rebuilt = new ArrayList<ManagedView[]>();
				final List<String> refused = new ArrayList<String>();
				for (ManagedView target : targets) {
					TiffResultView.Options wanted = chosen.copy();
					/* A full-width ROI does not have the same coordinates after splitting to a half.
					 * Rebuilding whole is unambiguous; the user can set a new region afterwards. */
					wanted.bounds = target.bounds == null || target.composition.equals(wantedComposition)
							? target.bounds == null ? null : target.bounds.copy() : null;
					String viewKey = VOLUME_KIND.equals(target.kind)
							? TiffResultDataset.VOLUME : target.kind;
					try {
						ImagePlus image = TiffResultView.openVirtual(dataset, viewKey, wanted,
								target.mode == OpenMode.VOLUME_SINGLE ? Math.max(0, target.timepoint) : -1);
						rebuilt.add(new ManagedView[] { target, ManagedView.tiff(image, target.root,
								target.kind, target.mode, target.timepoint, true, target.live, wanted) });
					} catch (Throwable failure) {
						refused.add(target.kind + " (" + (failure.getMessage() == null
								? failure.getClass().getSimpleName() : failure.getMessage()) + ")");
					}
				}
				SwingUtilities.invokeLater(new Runnable() {
					@Override public void run() {
						busy = false;
						int replaced = 0;
						for (ManagedView[] pair : rebuilt) {
							if (released.get() || pair[0].image.getWindow() == null) {
								discard(pair[1].image);
								continue;
							}
							present(pair[1]);
							replaced++;
						}
						if (released.get()) return;
						String message = replaced + " TIFF view(s) rebuilt; source files unchanged.";
						if (materialised > 0) message += "  " + materialised
								+ " materialised view(s) left as loaded.";
						if (!refused.isEmpty()) {
							message += "  Left as they were: " + refused + ".";
							IJ.log("OPM data viewer: could not rebuild TIFF views " + refused);
						}
						/* The watch holds the options it started with - which sources to refresh
						 * and how many time points they have paired - so a composition it does
						 * not know about would grow the new views from the old sources, or not
						 * at all. Restarting re-reads both from what is selected now. */
						if (watching) { startWatching(); message += "  Live update following the new channels."; }
						status.setText(message);
					}
				});
			}
		}, "OPM-tiff-viewer-recompose");
		worker.start();
	}

	/**
	 * Try again once the window is free. One wait at a time: whatever the controls say when it
	 * fires is what gets built, so several changes made during a long open become one rebuild.
	 */
	private void queueRecompose(boolean force) {
		recomposeForced |= force;
		if (recomposeQueued) return;
		recomposeQueued = true;
		javax.swing.Timer retry = new javax.swing.Timer(250, new ActionListener() {
			@Override public void actionPerformed(ActionEvent e) {
				recomposeQueued = false;
				boolean forced = recomposeForced;
				recomposeForced = false;
				recomposeOpenViews(forced);
			}
		});
		retry.setRepeats(false);
		retry.start();
	}

	/**
	 * A view this window opened and still answers for.
	 * <p>
	 * Everything needed to build it again is kept with it: which dataset and kind, how it was
	 * opened, and the runtime view and region it was built with - the last two so a rebuild can
	 * tell whether the region still means what it did.
	 */
	static final class ManagedView {
		final ImagePlus image;
		final File root;
		/** {@link #VOLUME_KIND}, or the projection key. */
		final String kind;
		final OpenMode mode;
		/** Zero based, for a single-time-point volume; ignored otherwise. */
		final int timepoint;
		/** A materialised view is only ever closed with the window, never rebuilt or grown. */
		final boolean virtual;
		/** Opened as a run's live preview; titled so, and a replacement stays so. */
		boolean live;
		/** The runtime view it was built with; null for a TIFF result. */
		final OmeZarrView.Operation operation;
		final TiffResultView.Options tiffOptions;
		final OmeZarrView.Bounds bounds;
		/** Everything but the region it was composed from; see {@link #compositionOf}. */
		final String composition;

		/**
		 * @param options			: what an OME-Zarr view was built from; null for a TIFF result
		 */
		ManagedView(ImagePlus image, File root, String kind, OpenMode mode, int timepoint,
				boolean virtual, boolean live, OmeZarrView.Options options) {
			this(image, root, kind, mode, timepoint, virtual, live, options, null);
		}

		private ManagedView(ImagePlus image, File root, String kind, OpenMode mode, int timepoint,
				boolean virtual, boolean live, OmeZarrView.Options options,
				TiffResultView.Options tiffOptions) {
			this.image = image;
			this.root = root;
			this.kind = kind;
			this.mode = mode;
			this.timepoint = timepoint;
			this.virtual = virtual;
			this.live = live;
			this.operation = options == null ? null : options.operation;
			this.tiffOptions = tiffOptions == null ? null : tiffOptions.copy();
			this.bounds = options != null && options.bounds != null ? options.bounds.copy()
					: tiffOptions != null && tiffOptions.bounds != null ? tiffOptions.bounds.copy() : null;
			this.composition = tiffOptions == null
					? compositionOf(options) : TiffResultView.compositionOf(tiffOptions);
		}

		static ManagedView tiff(ImagePlus image, File root, String kind, OpenMode mode,
				int timepoint, boolean virtual, boolean live, TiffResultView.Options options) {
			return new ManagedView(image, root, kind, mode, timepoint, virtual, live, null, options);
		}
	}

	/**
	 * What decides a view's pixels apart from its region, as one comparable value.
	 * <p>
	 * A combo box reports re-selecting the item it already shows, and {@code Channel setup...}
	 * can be accepted unchanged; a view already composed this way is left alone rather than
	 * rebuilt - and flashed - for nothing.
	 */
	static String compositionOf(OmeZarrView.Options options) {
		if (options == null) return "";
		return options.operation + "|" + options.requestedChannels + "|" + options.flipHalf
				+ "|" + options.interpolate + "|" + options.tryGpu
				+ "|" + options.firstOutputChannel + "|" + options.outputChannelCount;
	}

	/**
	 * The views a viewer answers for: at most one per dataset and kind.
	 * <p>
	 * One per <em>dataset</em> rather than one per kind outright, so two acquisitions can still
	 * be compared side by side. Kept apart from the window so the rule can be tested headless.
	 */
	static final class ViewRegistry {
		private final List<ManagedView> entries = new ArrayList<ManagedView>();

		/**			Record a view, dropping the one of the same dataset and kind
		 *
		 * @return					: the entry it replaced, or null
		 */
		synchronized ManagedView put(ManagedView view) {
			ManagedView previous = find(view.root, view.kind);
			if (previous != null) entries.remove(previous);
			entries.add(view);
			return previous;
		}

		synchronized ManagedView find(File root, String kind) {
			for (ManagedView entry : entries)
				if (entry.kind.equals(kind) && sameRoot(entry.root, root)) return entry;
			return null;
		}

		/** This dataset's views, as a copy that may be walked while the registry changes. */
		synchronized List<ManagedView> of(File root) {
			List<ManagedView> found = new ArrayList<ManagedView>();
			for (ManagedView entry : entries) if (sameRoot(entry.root, root)) found.add(entry);
			return found;
		}

		/** Forget the views whose windows the user has closed; only shown views are recorded. */
		synchronized int prune() {
			int removed = 0;
			for (java.util.Iterator<ManagedView> each = entries.iterator(); each.hasNext();) {
				if (each.next().image.getWindow() != null) continue;
				each.remove();
				removed++;
			}
			return removed;
		}

		/** Every view, emptying the registry. */
		synchronized List<ManagedView> clear() {
			List<ManagedView> all = new ArrayList<ManagedView>(entries);
			entries.clear();
			return all;
		}

		synchronized int size() { return entries.size(); }

		static boolean sameRoot(File a, File b) {
			return sameFolder(a, b);
		}
	}

	/**			Undo ImageJ opening a view at its smallest possible zoom
	 * <p>		1.4% is not an arbitrary number. It is {@code ImageCanvas.zoomLevels[0]}, 1/72,
	 * 			the floor of the zoom-out loop in {@code ImageWindow.setLocationAndSize}:
	 * <pre>    while (xbase + width*mag &gt; screenWidth || ybase + height*mag &gt;= screenHeight)</pre>
	 * 			{@code xbase} and {@code ybase} are <em>static</em> fields shared by every image
	 * 			window in the session - ImageJ's window cascade. Once one of them sits at the
	 * 			screen edge the condition stops depending on {@code mag} at all, so the loop
	 * 			runs all the way to the floor no matter how small the image is.
	 * <p>		Why this is seen on maxX and not on maxY or maxZ: ImageJ's only mid-session
	 * 			reset of that shared base is {@code width > maxWindow.width/2}. maxX is the
	 * 			narrow projection - its width is Z, 212 px here, against 1600 for the other two
	 * 			- so it is the one view that never triggers the reset and always inherits
	 * 			whatever base the previous window left behind. That is also why it is
	 * 			intermittent: it depends on what was opened before it.
	 * <p>		Only the collapsed case is touched. Any other magnification ImageJ chooses is
	 * 			left alone, and so is the floor when the floor is right - an image too large for
	 * 			even 1/72 to fit should open at 1/72. Placing every window ourselves would be
	 * 			the alternative and is not worth it for a cosmetic fault.
	 *
	 * @return					: whether the window had to be re-fitted
	 */
	private static boolean refitIfOpenedAtMinimumZoom(ImagePlus image) {
		ImageWindow window = image.getWindow();
		ImageCanvas canvas = window == null ? null : window.getCanvas();
		if (canvas == null) return false;
		// Asking for the level below zero returns the lowest level there is, whatever it is.
		double floor = ImageCanvas.getLowerZoomLevel(0);
		if (canvas.getMagnification() > floor * 1.001) return false;

		Rectangle screen = GUI.getMaxWindowBounds(window);
		if (image.getWidth() * floor > screen.width || image.getHeight() * floor > screen.height)
			return false;	// genuinely too large for anything larger; ImageJ is right

		/* setLocationAndSize fits the canvas into the bounds given and then packs the window
		 * back down to it, so the magnification follows from the size rather than being
		 * chosen here. Four fifths of the work area leaves room for the Fiji main window. */
		window.setLocationAndSize(screen.x + 20, screen.y + 20,
				Math.min(image.getWidth(), screen.width * 4 / 5),
				Math.min(image.getHeight(), screen.height * 4 / 5)
						+ window.getInsets().top + window.getSliderHeight());
		return true;
	}

	private void showProvenance() {
		Entry entry = selectedEntry();
		if (entry == null) return;
		/* A TIFF result carries no provenance record - the summary is everything there is to
		 * say about it, and saying so beats logging an empty heading. */
		String text = entry.isTiff() ? summary(entry.tiff)
				: entry.zarr.getProvenance() == null
						? summary(entry.zarr) : entry.zarr.getProvenance().toPrettyJson();
		IJ.log("OPM data viewer: " + entry.root().getAbsolutePath() + "\n" + text);
		IJ.showStatus("Dataset information written to the Fiji Log window.");
	}

	private void showError(Throwable error) {
		String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
		status.setText("Open failed: " + message);
		IJ.log("OPM data viewer failed: " + message);
		error.printStackTrace();
		IJ.showMessage(TITLE, message);
	}

	private Entry selectedEntry() {
		Object selected = datasets.getSelectedItem();
		return selected instanceof Entry ? (Entry) selected : null;
	}

	/** The TIFF result currently selected, or null when the selection is an OME-Zarr. */
	private TiffResultDataset selectedTiff() {
		Entry entry = selectedEntry();
		return entry == null ? null : entry.tiff;
	}

	/** Which view of a TIFF result the Open controls are pointing at. */
	private String selectedTiffView() {
		return selectedViewKey();
	}

	/**			The view the controls describe: the volume, or the chosen projection
	 * <p>		{@code Open as} decides it, and the projection list names which one. Both formats
	 * <br>		use the same answer - a TIFF result's view folder and an OME-Zarr's
	 * <br>		{@code projections/<key>} are the same view under the same name - so what is opened,
	 * <br>		materialised and exported all follow the one selection.
	 * <p>
	 * @return	: {@link TiffResultDataset#VOLUME}, or a projection key such as {@code maxZ}
	 */
	String selectedViewKey() {
		OpenMode mode = (OpenMode) openMode.getSelectedItem();
		if (mode != OpenMode.PROJECTION) return TiffResultDataset.VOLUME;
		Object projection = projections.getSelectedItem();
		return projection == null ? TiffResultDataset.VOLUME : String.valueOf(projection);
	}

	/** The projection the controls name, or null when they describe the volume. */
	private String selectedProjectionKey() {
		String key = selectedViewKey();
		return TiffResultDataset.VOLUME.equals(key) ? null : key;
	}

	/** The OME-Zarr currently selected, or null when the selection is a TIFF result. */
	private OmeZarrDataset selectedDataset() {
		Entry entry = selectedEntry();
		return entry == null ? null : entry.zarr;
	}

	@Override
	public void close() {
		release();
		super.close();
	}

	/**			Give up what this window owns, however it is being taken down
	 * <p>		Quitting Fiji does not call {@code close()}. Its legacy layer disposes the
	 * 			non-image windows in {@code IJ1Helper.disposeNonImageWindows}, which calls
	 * 			{@code close()} only on a {@code PlugInDialog} and plain {@code dispose()} on
	 * 			everything else - and {@code dispose()} fires {@code windowClosed}, never
	 * 			{@code windowClosing}, so a {@code PlugInFrame}'s {@code close()} is skipped
	 * 			entirely. This viewer is a {@code PlugInFrame}.
	 * <p>		Two things went wrong because of that. The path, window location and poll
	 * 			interval were lost on every quit, and the live-preview reference stayed set on a
	 * 			disposed frame. Menu-created viewers are deliberately independent, so two different
	 * 			TIFF representations can be kept open at the same time.
	 * <p>		Idempotent, because the ordinary close path reaches it twice: once directly and
	 * 			once through the {@code windowClosed} that {@code dispose()} then fires.
	 */
	private void release() {
		if (!released.compareAndSet(false, true)) return;
		stopWatching();
		Prefs.saveLocation(LOC_KEY, getLocation());
		Prefs.set(PATH_KEY, path.getText().trim());
		Prefs.set(POLL_KEY, pollInterval());
		if (liveInstance == this) liveInstance = null;
		/* The views go with the window. A live preview left behind kept a reader open on a
		 * store nothing was following any more, and was no longer growing. Materialised
		 * snapshots were never recorded, so they stay. On quit Fiji has closed the image
		 * windows already, and closing one twice does nothing. */
		for (ManagedView view : views.clear()) {
			try {
				discard(view.image);
			} catch (Throwable ignored) {
				// one window refusing to close must not keep the others open
			}
		}
	}

	private int pollInterval() { return clampPoll(((Number) pollSeconds.getValue()).intValue()); }

	private static int clampPoll(int seconds) { return Math.max(1, Math.min(120, seconds)); }
}
