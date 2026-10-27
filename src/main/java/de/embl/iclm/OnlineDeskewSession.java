package de.embl.iclm;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

import org.scijava.ui.CloseConfirmable;

import bdv.cache.SharedQueue;
import bdv.tools.brightness.ConverterSetup;
import bdv.util.BdvFunctions;
import bdv.util.BdvHandle;
import bdv.util.BdvHandlePanel;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.viewer.Interpolation;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.SynchronizedViewerState;
import bdv.viewer.TimePointListener;
import bdv.viewer.ViewerPanel;
import bdv.viewer.ViewerStateChange;
import bdv.viewer.ViewerStateChangeListener;
import ij.IJ;
import ij.ImageJ;
import ij.WindowManager;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.display.ColorConverter;
import net.imglib2.display.RealARGBColorConverter;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.volatiles.VolatileUnsignedShortType;
import net.imglib2.view.Views;

/**
 * One open Online Deskew View: the window and everything it uses (joint contracts C7.1 - C7.3).
 * The session owns the catalogue, the two camera-half sources, the fetch queue, the raw volume
 * store, the status card, its timer and listeners, and the worker that checks the folder's files.
 *
 * <p><b>The window is the session's own.</b> BigDataViewer's viewer is embedded through its
 * {@link BdvHandlePanel} in a frame this class builds, rather than BigDataViewer's own frame: that
 * frame stops its renderer from {@code windowClosing}, on the event thread, by joining the
 * painter thread - which holds the whole user interface for as long as the renderer takes to stop
 * (measured by agent-B: 3 s with the painter held). Here every way of closing - the close button,
 * code, Fiji's quit - reaches {@link #close()}. Fiji's quit disposes the window only because it is
 * {@link CloseConfirmable}, and should a quit not do so, the worker closes the view once ImageJ's
 * main window is gone: a window left open would keep the JVM running. {@link #close()} marks the session
 * terminal and detaches the timer and listeners at once, and does every blocking step on a daemon
 * thread: stopping the renderer, the fetch queue, the checking worker and the store, each within a
 * deadline (C7.2). {@link #closed()} reports how each step went.
 *
 * <p><b>A file that changes is withheld.</b> The store reports a file it finds changed when it
 * reads; the worker also re-checks the shown time point's file about once a second and whenever
 * the shown time point changes. Either way the time point is marked changed, is no longer drawn,
 * and the status card says why (C2.6).
 *
 * <p><b>Completed-frame playback draws over the viewer</b> (joint decision D2): {@link #strictPlayer()}
 * puts a {@link RawPresenter} on BigDataViewer's canvas and hides BigDataViewer's own sources under
 * it; {@link #frameRequest(int)} freezes what the view shows into a request for it. While it runs,
 * the files watched for changes are those of the frames shown, waiting and in preparation - not
 * BigDataViewer's time point - and BigDataViewer's slider follows each frame as it is presented.
 *
 * <p><b>Opening can be cancelled.</b> It runs as a {@link Shutdown} operation from the command:
 * Escape, {@code Batch Processing > Terminate...} or Fiji's quit stop it between files, or while
 * the first planes are read, and whatever it had opened is closed again.
 */
final class OnlineDeskewSession {

	/** The session opened last; for tests and the smoke test, not for program logic. */
	static volatile OnlineDeskewSession latest;
	/** Test seam: run by the checking worker at the top of each round, before it asks what is shown. */
	static volatile Runnable checkerHookForTest;
	/** Test seam: called with each session as soon as it exists, before its first planes are read. */
	static volatile Consumer<OnlineDeskewSession> openingForTest;
	/** How often the shown time point's file is checked for changes, at most. */
	static final long GENERATION_CHECK_MS = 1000;
	/** How long closing waits for the renderer and for the checking worker to stop. */
	static final long STOP_DEADLINE_MS = 3000;
	/** How long a JVM that is ending waits for an open view to finish closing. */
	static final long EXIT_WAIT_MS = 2000;

	private final OnlineDeskewView.Settings settings;
	private final RawAcquisition acquisition;
	private final List<RawAcquisition.TimeKey> times;
	private final RawGeometry geometry;
	private final RawSourceSpec[] specs;
	private final SharedQueue queue;
	private final TiffRawVolumeStore store;
	private final int firstIndex;
	private final String budgetNote;
	private final AtomicBoolean closing = new AtomicBoolean();
	/** Opened inside a running ImageJ, whose quit therefore has to close this view. */
	private final boolean underImageJ = IJ.getInstance() != null;
	private final CompletableFuture<CloseResult> closed = new CompletableFuture<CloseResult>();
	private final Object checkerSignal = new Object();
	/** Counted down once the event thread has detached the window's listeners and actions. */
	private final java.util.concurrent.CountDownLatch detached = new java.util.concurrent.CountDownLatch(1);
	/**
	 * Run if the JVM ends with the view open - ImageJ's quit ends it with {@code System.exit}, and a
	 * normal end does not wait for daemon threads: starts the close and gives it a short, bounded
	 * time to finish, so that it is reported rather than cut off. The view holds only read handles,
	 * which end with the process either way.
	 */
	private final Thread exitHook = new Thread(this::closeOnExit, "OPM-online-deskew-exit");

	private volatile BdvHandlePanel handle;
	private volatile JFrame frame;
	private volatile Thread checker;
	private volatile RawPresence presence;
	/** Completed-frame playback, once started, and its presenter. Set on the event thread. */
	private volatile RawStrictPlayer player;
	private volatile RawPresenter presenter;
	/** Every player started, so that closing can wait for what each of them started to finish. */
	private final List<RawStrictPlayer> players = new java.util.concurrent.CopyOnWriteArrayList<RawStrictPlayer>();
	/** The slider is being moved to a presented frame, not by the user; on the event thread. */
	private boolean followingPresentation;
	/**
	 * The program is moving BigDataViewer's time point - a progressive move or step - or registering
	 * the listener that BigDataViewer at once tells the current time point; on the event thread.
	 */
	private boolean movingTime;
	/** Reject reentrant navigation from futures completed during mode teardown. EDT only. */
	private boolean stoppingPlayback;
	private final AtomicLong revisions = new AtomicLong();
	private JTextArea status;
	private Timer statusTimer;
	private TimePointListener timeListener;
	private ComponentAdapter canvasListener;
	private ViewerStateChangeListener viewListener;
	private ConverterSetup.SetupChangeListener setupListener;
	/** Listeners can run under the viewer state lock on any thread. No EDT wait or file IO there. */
	private final AtomicBoolean viewRefreshQueued = new AtomicBoolean();
	private final AtomicLong viewInvalidated = new AtomicLong();
	private volatile RawFrameRequest lastSubmitted;
	/** At most one submitted request; navigation holds the one replaceable pending target. EDT only. */
	private Navigation inFlight;
	private final Map<RawWorldView, Action> worldActions = new java.util.EnumMap<RawWorldView, Action>(RawWorldView.class);
	private JLabel navigationStatus;
	/** Play, pause, step and loop, in either mode (C6.1); on the event thread. */
	private RawPlayback playback;
	private javax.swing.JComboBox<RawPlayback.Mode> playbackMode;
	private JButton playButton;
	private javax.swing.JCheckBox loopBox;
	private javax.swing.JSpinner fpsSpinner;
	private JLabel playbackStatus;
	private final List<java.awt.Component> playbackControls = new java.util.ArrayList<java.awt.Component>();
	/** Latest requested time/view, distinct from the time last painted. Published to the checker. */
	private volatile Navigation navigation;
	private String navigationNote = "";

	private static final class Navigation {
		final long revision;
		final int time;
		final RawStrictPlayer player;
		final CompletableFuture<RawStrictPlayer.Result> result = new CompletableFuture<RawStrictPlayer.Result>();
		boolean submitted; // event thread only
		RawFrameRequest request;
		int width, height;

		Navigation(long revision, int time, RawStrictPlayer player) {
			this.revision = revision;
			this.time = time;
			this.player = player;
		}
	}

	private OnlineDeskewSession(OnlineDeskewView.Settings settings, RawAcquisition acquisition,
			List<RawAcquisition.TimeKey> times, RawGeometry geometry, RawSourceSpec[] specs, int firstIndex,
			RawTiffLayout layout) {
		this.settings = settings;
		this.acquisition = acquisition;
		this.times = times;
		this.geometry = geometry;
		this.specs = specs;
		this.firstIndex = firstIndex;
		int cores = Runtime.getRuntime().availableProcessors();
		int ioThreads = Math.max(2, Math.min(8, cores / 2));
		long cell = TiffRawVolumeStore.cellBytes(layout);
		long minimum = TiffRawVolumeStore.minimumBudget(cell, ioThreads);
		long budget = Math.max(settings.budgetBytes(), minimum);
		this.budgetNote = budget > settings.budgetBytes() ? String.format(Locale.US,
				"cache budget raised to %.0f MB, the least that holds %d reads and %d cells", budget / 1e6, 3 * ioThreads,
				TiffRawVolumeStore.MIN_RESIDENT_CELLS) : null;
		this.queue = new SharedQueue(Math.max(1, Math.min(8, cores - 1)), 1);
		this.store = new TiffRawVolumeStore(budget, cell, ioThreads, queue);
		this.store.setStaleListener(this::fileChanged);
	}

	/**
	 * Scan the folder, resolve the geometry, check the first time point, and open the view.
	 * Polls {@link Shutdown#stopping()} between steps; a cancelled opening closes what it opened.
	 *
	 * @throws CancellationException		when the opening was cancelled
	 * @throws IllegalArgumentException	when the folder holds nothing this view can show
	 */
	static OnlineDeskewSession open(OnlineDeskewView.Settings s) throws Exception {
		File folder = s.folder();
		if (!folder.isDirectory()) throw new IllegalArgumentException("Not a folder: " + folder);
		IJ.showStatus(OnlineDeskewView.MENU + ": reading " + folder.getName());
		cancelIfStopping();
		RawAcquisition acquisition = RawAcquisition.scan(folder);
		if (acquisition.fileCount() == 0)
			throw new IllegalArgumentException("No TIFF files can be placed in " + folder
					+ (acquisition.problems().isEmpty() ? "" : ":\n" + String.join("\n", acquisition.problems())));
		for (String problem : acquisition.problems()) System.out.println(OnlineDeskewView.MENU + ": " + problem);
		List<RawAcquisition.TimeKey> times = acquisition.times(s.position, s.channel);
		if (times.isEmpty())
			throw new IllegalArgumentException(String.format(Locale.US,
					"No files of position %d, acquisition channel %d in %s.%nPositions found: %s; channels found: %s",
					s.position, s.channel, folder, acquisition.positions(), acquisition.channels()));

		RawGeometry geometry;
		if (s.parametersFromFile()) {
			File parameters = new File(folder, "ExperimentalParameters.txt");
			if (!parameters.isFile())
				throw new IllegalArgumentException("No ExperimentalParameters.txt in " + folder
						+ "; choose \"manual\" and type the deskew parameters.");
			geometry = RawGeometry.fromExperimentalParameters(parameters);
		} else geometry = new RawGeometry(s.dz, s.dxy, s.angle, "manual");

		// the first time point that can be shown opens the view; the rest are checked behind it
		int first = -1;
		RawAcquisition.Entry entry = null;
		for (int i = 0; i < times.size() && first < 0; i++) {
			cancelIfStopping();
			entry = acquisition.entry(times.get(i), s.channel);
			entry.validate();
			if (entry.viewable()) first = i;
		}
		if (first < 0)
			throw new IllegalArgumentException("No file of channel " + s.channel + " can be shown; the first one is "
					+ acquisition.entry(times.get(0), s.channel).detail());

		AlignmentMatrixSet alignments = null;
		if (!s.alignmentCsv.isEmpty()) {
			alignments = AlignmentMatrixSet.load(s.alignmentCsv);
			if (alignments == null) throw new IllegalArgumentException("Cannot read the alignment matrix " + s.alignmentCsv);
		}
		RawTiffLayout layout = entry.layout();
		RawSourceSpec[] specs = {
			RawSourceSpec.half(alignments, s.channel, true, s.flipLeft(), layout.width, 0x00ff00),
			RawSourceSpec.half(alignments, s.channel, false, s.flipLeft(), layout.width, 0xff00ff)
		};
		final OnlineDeskewSession session = new OnlineDeskewSession(s, acquisition, times, geometry, specs, first, layout);
		try {
			Consumer<OnlineDeskewSession> hook = openingForTest;
			if (hook != null) hook.accept(session);
			session.show();
			cancelIfStopping();
		} catch (Exception | Error e) {
			if (e instanceof InterruptedException) Thread.currentThread().interrupt();
			// decided before closing; the close then runs without the interrupt, which AWT would
			// otherwise catch and report while it starts, and the interrupt is put back after
			boolean cancelled = !(e instanceof CancellationException) && Shutdown.stopping();
			String reason = cancelled ? Shutdown.reason() : null;
			boolean interrupted = Thread.interrupted();
			session.close();
			if (interrupted) Thread.currentThread().interrupt();
			// a read or a wait cut short by the cancellation is the cancellation, not a failure of its own
			if (cancelled) {
				CancellationException c = new CancellationException("Opening was cancelled because " + reason);
				c.initCause(e);
				throw c;
			}
			throw e;
		}
		session.startChecker();
		latest = session;
		return session;
	}

	private static void cancelIfStopping() {
		if (Shutdown.stopping())
			throw new CancellationException("Opening was cancelled because " + Shutdown.reason());
	}

	private void show() throws Exception {
		final RawAcquisition.Entry entry = acquisition.entry(times.get(firstIndex), settings.channel);
		final long[] referenceDims = entry.layout().dimensions();
		final double[][] ranges = new double[specs.length][];
		for (int i = 0; i < specs.length; i++) {
			cancelIfStopping();
			ranges[i] = displayRange(RawSampling.crop(store.blocking(entry.key(), entry.layout()), specs[i]));
		}
		final Exception[] failure = new Exception[1];
		SwingUtilities.invokeAndWait(new Runnable() {
			@Override public void run() {
				try {
					if (!closing.get()) showOnEventThread(referenceDims, ranges);
				} catch (Exception e) {
					failure[0] = e;
				}
			}
		});
		if (failure[0] != null) throw failure[0];
	}

	/**
	 * The view's window. Fiji's quit disposes only ImageJ's own windows and those that are
	 * {@link CloseConfirmable} (imagej-legacy's {@code interceptCloseAllWindows}); any other window
	 * stays open and, since Fiji does not end the JVM on its quit, keeps it running with no ImageJ
	 * left. Agreeing to the close lets the quit dispose this window, which closes the session.
	 */
	private static final class ViewerWindow extends JFrame implements CloseConfirmable {
		private static final long serialVersionUID = 1L;

		ViewerWindow(String title) {
			super(title);
		}

		@Override public boolean confirmClose() {
			return true;		// nothing to save: the raw files are only read
		}
	}

	private void showOnEventThread(long[] referenceDims, double[][] ranges) {
		JFrame window = new ViewerWindow(OnlineDeskewView.MENU + " - " + settings.folder().getName());
		frame = window;
		BdvHandlePanel panel = new BdvHandlePanel(window, BdvOptions.options().preferredSize(800, 600));
		handle = panel;
		// one presence for every source, so that both halves appear and go in the same frame
		presence = new RawPresence(acquisition, times, settings.channel);
		for (int i = 0; i < specs.length; i++) {
			RawPartSource<UnsignedShortType> blocking = new RawPartSource<UnsignedShortType>(acquisition, times, specs[i],
					geometry, store::blocking, new UnsignedShortType(), referenceDims, presence);
			RawPartSource<VolatileUnsignedShortType> volatileSource = new RawPartSource<VolatileUnsignedShortType>(
					acquisition, times, specs[i], geometry, store::volatileView, new VolatileUnsignedShortType(), referenceDims,
					presence);
			SourceAndConverter<VolatileUnsignedShortType> volatileSoc = new SourceAndConverter<VolatileUnsignedShortType>(
					volatileSource, RealARGBColorConverter.create(new VolatileUnsignedShortType(), ranges[i][0], ranges[i][1]));
			SourceAndConverter<UnsignedShortType> soc = new SourceAndConverter<UnsignedShortType>(blocking,
					RealARGBColorConverter.create(new UnsignedShortType(), ranges[i][0], ranges[i][1]), volatileSoc);
			BdvStackSource<?> shown = BdvFunctions.show(soc, times.size(), BdvOptions.options().addTo(panel));
			shown.setColor(new ARGBType(0xff000000 | specs[i].rgb));
			shown.setDisplayRange(ranges[i][0], ranges[i][1]);
		}
		panel.getCacheControls().addCacheControl(queue);
		panel.getCacheControls().addCacheControl(presence);		// refreshed at the start of every frame
		ViewerPanel viewer = panel.getViewerPanel();
		viewer.state().setInterpolation(settings.interpolation());
		viewer.setTimepoint(firstIndex);

		status = new JTextArea(11, 30);
		status.setEditable(false);
		status.setLineWrap(true);
		status.setWrapStyleWord(true);
		status.setOpaque(false);
		status.setForeground(Color.DARK_GRAY);
		panel.getCardPanel().addCard("opm-online-deskew", "Online Deskew", status, true);
		timeListener = new TimePointListener() {
			@Override public void timePointChanged(int timePointIndex) {
				// A move by the user pauses playback in either mode, and in completed mode asks for that
				// time point. The program's own moves - a progressive move or step, the slider following
				// a presented frame - are neither. Both flags hold only because BigDataViewer reports a
				// change inside the setTimepoint that made it: OnlineDeskewManualSeekGuiTest pins that.
				if (!movingTime && !followingPresentation && !closing.get() && playback != null) {
					playback.movedByHand();
					if (player != null) requestStrictFrame(timePointIndex);
				}
				updateStatus();
				wakeChecker();		// check the newly shown file now, not at the next tick
			}
		};
		movingTime = true;		// BigDataViewer reports the current time point to a new listener at once
		try {
			viewer.addTimePointListener(timeListener);
		} finally {
			movingTime = false;
		}
		statusTimer = new Timer(1000, e -> updateStatus());
		statusTimer.start();
		updateStatus();

		JPanel content = new JPanel(new BorderLayout());
		playback = new RawPlayback(playbackHost(), RawPlayback.SWING, System::nanoTime);
		JPanel toolbars = new JPanel(new java.awt.GridLayout(2, 1));
		toolbars.add(worldToolbar());
		toolbars.add(playbackToolbar());
		content.add(toolbars, BorderLayout.NORTH);
		content.add(panel.getSplitPanel(), BorderLayout.CENTER);
		canvasListener = new ComponentAdapter() {
			@Override public void componentResized(ComponentEvent e) {
				viewChanged();
			}
		};
		viewer.getDisplayComponent().addComponentListener(canvasListener);
		viewListener = change -> {
			if (change != ViewerStateChange.CURRENT_TIMEPOINT_CHANGED && change != ViewerStateChange.NUM_TIMEPOINTS_CHANGED
					&& change != ViewerStateChange.GROUP_NAME_CHANGED) viewChanged();
		};
		setupListener = setup -> viewChanged();
		viewer.state().changeListeners().add(viewListener);
		panel.getConverterSetups().listeners().add(setupListener);
		window.setContentPane(content);
		Debug.decorate(window, content);	// before pack(); see Debug.decorate
		window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		window.addWindowListener(new WindowAdapter() {
			// the close button: start closing at once; the frame then disposes itself
			@Override public void windowClosing(WindowEvent e) {
				close();
			}

			// code and Fiji's quit dispose without windowClosing
			@Override public void windowClosed(WindowEvent e) {
				close();
			}
		});
		window.pack();
		window.setLocationRelativeTo(IJ.getInstance());
		window.setVisible(true);
		// listed in Fiji's Window menu, and disposed - hence closed - when Fiji quits
		WindowManager.addWindow(window);
		SwingUtilities.invokeLater(this::fitToView);
	}

	/** Look down the deskewed Z at mid depth, the union of both halves filling the window. */
	private void fitToView() {
		ViewerPanel viewer = viewerPanel();
		if (closing.get() || viewer == null) return;
		RawAcquisition.Entry entry = acquisition.entry(times.get(firstIndex), settings.channel);
		RawTiffLayout layout = entry.layout();
		if (layout == null) return;		// withheld meanwhile
		long[] dims = layout.dimensions();
		double[] min = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
		double[] max = { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
		double[] corner = new double[3], world = new double[3];
		for (RawSourceSpec spec : specs) {
			AffineTransform3D toWorld = geometry.sourceToWorld(dims, spec, null);
			long w = spec.cropWidth(dims[0]);
			for (int c = 0; c < 8; c++) {
				corner[0] = (c & 1) == 0 ? 0 : w - 1;
				corner[1] = (c & 2) == 0 ? 0 : dims[1] - 1;
				corner[2] = (c & 4) == 0 ? 0 : dims[2] - 1;
				toWorld.apply(corner, world);
				for (int d = 0; d < 3; d++) {
					min[d] = Math.min(min[d], world[d]);
					max[d] = Math.max(max[d], world[d]);
				}
			}
		}
		int width = Math.max(1, viewer.getDisplay().getWidth()), height = Math.max(1, viewer.getDisplay().getHeight());
		double scale = 0.95 * Math.min(width / (max[0] - min[0]), height / (max[1] - min[1]));
		AffineTransform3D view = new AffineTransform3D();
		view.translate(-0.5 * (min[0] + max[0]), -0.5 * (min[1] + max[1]), -0.5 * (min[2] + max[2]));
		view.scale(scale);
		view.translate(0.5 * width, 0.5 * height, 0);
		viewer.state().setViewerTransform(view);
	}

	private JToolBar worldToolbar() {
		JToolBar toolbar = new JToolBar();
		toolbar.setFloatable(false);
		for (RawWorldView plane : RawWorldView.values()) {
			Action action = new AbstractAction(plane.label) {
				@Override public void actionPerformed(ActionEvent event) { orientWorld(plane); }
			};
			action.putValue(Action.SHORT_DESCRIPTION, "Show the " + plane.name() + " world plane through the view centre, keeping zoom");
			worldActions.put(plane, action);
			JButton button = toolbar.add(action);
			button.setFocusable(false);
		}
		toolbar.addSeparator();
		navigationStatus = new JLabel(" ");
		toolbar.add(navigationStatus);
		return toolbar;
	}

	/** The playback row: mode, step back, play or pause, step on, loop, target rate, and what playback is doing. */
	private JToolBar playbackToolbar() {
		JToolBar toolbar = new JToolBar();
		toolbar.setFloatable(false);
		playbackMode = new javax.swing.JComboBox<RawPlayback.Mode>(RawPlayback.Mode.values());
		playbackMode.setToolTipText("Progressive: BigDataViewer refines each frame as data arrives. "
				+ "Completed frames: each time point is shown only once it is complete; playback never skips one to keep the rate.");
		playbackMode.setMaximumSize(playbackMode.getPreferredSize());
		playbackMode.addActionListener(e -> {
			RawPlayback.Mode chosen = (RawPlayback.Mode) playbackMode.getSelectedItem();
			if (chosen != null && chosen != playback.mode()) playback.setMode(chosen);
		});
		JButton back = new JButton("< Step");
		back.setToolTipText("One time point back, paused");
		back.addActionListener(e -> playback.step(-1));
		playButton = new JButton("Play");
		playButton.addActionListener(e -> {
			if (playback.isPlaying()) playback.pause();
			else playback.play();
		});
		JButton on = new JButton("Step >");
		on.setToolTipText("One time point on, paused");
		on.addActionListener(e -> playback.step(1));
		loopBox = new javax.swing.JCheckBox("Loop");
		loopBox.setOpaque(false);
		loopBox.addActionListener(e -> playback.setLoop(loopBox.isSelected()));
		fpsSpinner = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(10, 1, 60, 1));
		fpsSpinner.setToolTipText("Target frames per second; completed frames may arrive more slowly, never skipped");
		fpsSpinner.setMaximumSize(fpsSpinner.getPreferredSize());
		fpsSpinner.addChangeListener(e -> playback.setTargetFps(((Number) fpsSpinner.getValue()).doubleValue()));
		JLabel fpsLabel = new JLabel(" fps ");
		for (java.awt.Component c : new java.awt.Component[] { playbackMode, back, playButton, on, loopBox, fpsSpinner }) {
			c.setFocusable(false);
			playbackControls.add(c);
		}
		toolbar.add(playbackMode);
		toolbar.add(back);
		toolbar.add(playButton);
		toolbar.add(on);
		toolbar.add(loopBox);
		toolbar.add(fpsLabel);
		toolbar.add(fpsSpinner);
		toolbar.addSeparator();
		playbackStatus = new JLabel(" ");
		toolbar.add(playbackStatus);
		return toolbar;
	}

	/** What playback needs of this view. */
	private RawPlayback.Host playbackHost() {
		return new RawPlayback.Host() {
			@Override public int timeCount() { return times.size(); }
			@Override public int currentTime() { return shownTimepoint(); }
			@Override public int requestedTime() { return requestedTimepoint(); }
			@Override public void showTime(int t) {
				ViewerPanel viewer = viewerPanel();
				if (viewer == null || closing.get()) return;
				movingTime = true;		// playback's own move: not the user's
				try {
					viewer.setTimepoint(t);
				} finally {
					movingTime = false;
				}
			}
			@Override public CompletableFuture<RawStrictPlayer.Result> requestFrame(int t) { return requestStrictFrame(t); }
			@Override public void prefetch(int t) { prefetchStrictFrame(t); }
			@Override public boolean startCompleted() { return strictPlayer() != null; }
			@Override public void stopCompleted() { stopStrictPlayback(); }
			@Override public long placeholdersHandedOut() { return store.placeholdersHandedOut(); }
			@Override public double presentedFps() {
				RawPresenter shown = presenter;
				return shown == null ? 0 : shown.fps();
			}
			@Override public void note(String text) {
				if (playbackStatus == null) return;
				playbackStatus.setText(text);
				playbackStatus.setToolTipText(text);
				playButton.setText(playback.isPlaying() ? "Pause" : "Play");
				if (playbackMode.getSelectedItem() != playback.mode()) playbackMode.setSelectedItem(playback.mode());
				loopBox.setSelected(playback.isLooping());
			}
		};
	}

	/** Change only the camera, on the EDT. Completed mode reissues the requested time at this view. */
	void orientWorld(RawWorldView plane) {
		requireEventThread();
		ViewerPanel viewer = viewerPanel();
		if (closing.get() || viewer == null) return;
		java.awt.Component canvas = viewer.getDisplayComponent();
		try {
			AffineTransform3D view = plane.orient(viewer.state().getViewerTransform(), canvas.getWidth(), canvas.getHeight());
			viewer.state().setViewerTransform(view);
			navigationNote(plane.label);
			viewer.requestRepaint();
		} catch (IllegalArgumentException ex) {
			navigationNote("Cannot orient view: " + ex.getMessage());
		}
	}

	/**
	 * Invalidate before a pending paint can acknowledge the old state. The player uses an atomic
	 * revision and the presenter guards acceptance/commit with its own monitor, so supersede is
	 * safe on this listener's thread. Capture here is read-only, never validates a file.
	 */
	private void viewChanged() {
		RawStrictPlayer p = player;
		Navigation n = navigation;
		if (p == null || n == null || closing.get()) return;
		if (!viewRefreshQueued.get()) {
			RawFrameRequest current = captureFrame(n.time, 0, false);
			if (sameView(current, lastSubmitted) && viewInvalidated.get() <= lastSubmitted.revision) return;
		}
		long revision = revisions.incrementAndGet();
		viewInvalidated.accumulateAndGet(revision, Math::max);
		p.supersede(revision);
		queueViewRefresh();
	}

	private void queueViewRefresh() {
		if (viewRefreshQueued.compareAndSet(false, true)) SwingUtilities.invokeLater(this::refreshView);
	}

	/** One capture for a burst, with a revision allocated before it so a concurrent edit wins. */
	private void refreshView() {
		viewRefreshQueued.set(false);
		Navigation n = navigation;
		RawStrictPlayer p = player;
		if (n == null || p == null || closing.get()) return;
		// A navigation asked after this edit already captures its view, even if it is not shown yet.
		if (n.revision > viewInvalidated.get()) return;
		long revision = revisions.incrementAndGet();
		RawFrameRequest captured = captureFrame(n.time, revision, false);
		if (viewInvalidated.get() > revision) {
			queueViewRefresh();
			return;
		}
		// If an edit returned to the shown state there is nothing to redraw. An equal request
		// cancelled before its first presentation must instead be reissued: it cannot be revived.
		if (sameView(captured, lastSubmitted) && presenter.shownRequest() == lastSubmitted && n.result.isDone()) return;
		replaceNavigation(n.time, p, revision, captured);
	}

	/** Equality of everything a captured frame samples; revisions themselves are intentionally excluded. */
	static boolean sameView(RawFrameRequest a, RawFrameRequest b) {
		if (a == null || b == null || a.timeIndex != b.timeIndex || !a.key.equals(b.key) || a.width != b.width || a.height != b.height
				|| a.interpolation != b.interpolation || !Arrays.equals(a.viewer.getRowPackedCopy(), b.viewer.getRowPackedCopy())
				|| a.layers.size() != b.layers.size()) return false;
		for (int i = 0; i < a.layers.size(); i++) {
			RawFrameRequest.Layer x = a.layers.get(i), y = b.layers.get(i);
			if (x.spec != y.spec || x.argb != y.argb || Double.compare(x.min, y.min) != 0 || Double.compare(x.max, y.max) != 0
					|| !Arrays.equals(x.sourceToWorld.getRowPackedCopy(), y.sourceToWorld.getRowPackedCopy())) return false;
		}
		return true;
	}

	private void navigationNote(String note) {
		navigationNote = note;
		if (navigationStatus != null) {
			navigationStatus.setText(note.isEmpty() ? " " : note);
			navigationStatus.setToolTipText(note);
		}
	}

	private static void requireEventThread() {
		if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Navigation is for the event thread");
	}

	/* ---------------------------------------------------------------- the checking worker */

	private void startChecker() {
		Thread t = Shutdown.daemon(this::checkFiles, "OPM-online-deskew-check");
		checker = t;
		t.start();
		try {
			Runtime.getRuntime().addShutdownHook(exitHook);
		} catch (IllegalStateException alreadyEnding) {
			close();
		}
	}

	private void closeOnExit() {
		close();
		try {
			CloseResult result = closed.get(EXIT_WAIT_MS, TimeUnit.MILLISECONDS);
			System.out.println(OnlineDeskewView.MENU + " closed as the JVM ended: " + result);
		} catch (Exception e) {
			System.out.println(OnlineDeskewView.MENU + ": the JVM ended before the view finished closing (" + e + ")");
		}
	}

	private void wakeChecker() {
		synchronized (checkerSignal) {
			checkerSignal.notifyAll();
		}
	}

	/**
	 * The session's worker: checks files that have not been checked yet, the shown one first
	 * (checking a camera file walks its IFD chain, about 10 ms each since the chain is read a block
	 * at a time, so a jump on the slider waits for at most the one check already running), and
	 * re-checks the shown file's version - once a second, and whenever the shown time point changes.
	 */
	private void checkFiles() {
		Set<RawAcquisition.FileKey> lastWatched = Collections.emptySet();
		long nextGenerationCheck = 0;
		while (!closing.get()) {
			if (underImageJ && imageJGone()) {
				// a quit that did not dispose this window: close it, or it would keep the JVM alive
				close();
				return;
			}
			Runnable hook = checkerHookForTest;
			if (hook != null) hook.run();
			int shown = shownTimepoint();
			Set<RawAcquisition.FileKey> watched = watchedKeys(shown);
			long now = System.currentTimeMillis();
			if (!watched.equals(lastWatched) || now >= nextGenerationCheck) {
				for (RawAcquisition.FileKey key : watched) store.checkGeneration(key);
				lastWatched = watched;
				nextGenerationCheck = now + GENERATION_CHECK_MS;
			}
			Navigation desired = navigation;
			int i = nextToCheck(desired == null ? shown : desired.time, times.size(),
					k -> acquisition.entry(times.get(k), settings.channel).readiness() == RawAcquisition.Readiness.PENDING);
			if (i >= 0) {
				acquisition.entry(times.get(i), settings.channel).validate();
				SwingUtilities.invokeLater(this::resumeNavigation);
				if (i == shownTimepoint()) repaint();
				continue;
			}
			synchronized (checkerSignal) {
				if (closing.get()) return;
				try {
					checkerSignal.wait(GENERATION_CHECK_MS);
				} catch (InterruptedException e) {
					return;
				}
			}
		}
	}

	/**
	 * ImageJ's main window is gone: the quit has passed the point where it can be cancelled. Not
	 * {@code quitting()} alone, which is set before a quit asks to save images and may yet be
	 * cancelled.
	 */
	private static boolean imageJGone() {
		ImageJ ij = IJ.getInstance();
		return ij == null || !ij.isDisplayable();
	}

	/**
	 * BigDataViewer's viewer panel, or null before the window exists or once closing has stopped
	 * the viewer - {@code BdvHandle.close()} lets go of it. The checker and late Swing callbacks run
	 * while a close is under way, so they ask here rather than assume a viewer.
	 */
	private ViewerPanel viewerPanel() {
		BdvHandle h = handle;
		return h == null ? null : h.getViewerPanel();
	}

	private int shownTimepoint() {
		ViewerPanel viewer = viewerPanel();
		return viewer == null ? firstIndex : viewer.state().getCurrentTimepoint();
	}

	/**
	 * The file versions whose changes matter now, checked by {@link #checkFiles} - a changed one is
	 * withheld by {@link #fileChanged}. In completed-frame playback, those of the frames shown,
	 * waiting for paint and in preparation, whatever BigDataViewer's time point says; otherwise that
	 * of BigDataViewer's time point {@code shown}.
	 */
	private Set<RawAcquisition.FileKey> watchedKeys(int shown) {
		RawStrictPlayer p = player;
		if (p != null) return p.watchedKeys();
		if (shown < 0 || shown >= times.size()) return Collections.emptySet();
		RawAcquisition.Entry e = acquisition.entry(times.get(shown), settings.channel);
		return e != null && e.viewable() && e.key() != null ? Collections.singleton(e.key())
				: Collections.<RawAcquisition.FileKey> emptySet();
	}

	/** The store found a file changed: withhold its time point and say so. Any thread. */
	private void fileChanged(RawAcquisition.FileKey key, String why) {
		RawAcquisition.Entry e = acquisition.markChanged(key, why);
		RawStrictPlayer p = player;
		if (p != null) p.withdraw(key);
		if (e == null) return;
		System.out.println(OnlineDeskewView.MENU + ": " + key + " " + e.detail());
		repaint();
		SwingUtilities.invokeLater(this::updateStatus);
	}

	private void repaint() {
		ViewerPanel viewer = viewerPanel();
		if (viewer != null && !closing.get()) viewer.requestRepaint();
	}

	/**
	 * The time point to check next: the shown one, then those after it - playback runs forward -
	 * then those before it. -1 when none is left.
	 */
	static int nextToCheck(int shown, int count, IntPredicate pending) {
		for (int k = 0; k < count; k++) {
			int i = (Math.max(0, shown) + k) % count;
			if (pending.test(i)) return i;
		}
		return -1;
	}

	private void updateStatus() {
		ViewerPanel viewer = viewerPanel();
		if (status == null || closing.get() || viewer == null) return;
		int t = viewer.state().getCurrentTimepoint();
		RawAcquisition.Entry e = t >= 0 && t < times.size() ? acquisition.entry(times.get(t), settings.channel) : null;
		Map<RawAcquisition.Readiness, Integer> census = acquisition.census(times, settings.channel);
		StringBuilder text = new StringBuilder();
		text.append(settings.folder().getAbsolutePath()).append('\n');
		text.append("Deskew: ").append(geometry).append('\n');
		text.append(String.format(Locale.US, "Position %d, acquisition channel %d: %s (green), %s (magenta)%n",
				settings.position, settings.channel, specs[0], specs[1]));
		if (e != null)
			text.append(String.format(Locale.US, "%s - %d of %d - %s: %s%n", e.time, t + 1, times.size(),
					e.readiness().name().toLowerCase(Locale.US).replace('_', ' '),
					e.readiness() == RawAcquisition.Readiness.PENDING ? "being checked" : e.detail()));
		text.append(String.format(Locale.US,
				"Files: %d complete, %d depth unverified, %d incomplete, %d failed, %d changed, %d not checked yet%n",
				census.get(RawAcquisition.Readiness.COMPLETE), census.get(RawAcquisition.Readiness.DEPTH_UNVERIFIED),
				census.get(RawAcquisition.Readiness.INCOMPLETE), census.get(RawAcquisition.Readiness.FAILED),
				census.get(RawAcquisition.Readiness.CHANGED), census.get(RawAcquisition.Readiness.PENDING)));
		List<String> problems = acquisition.problems();
		if (!problems.isEmpty())
			text.append(String.format(Locale.US, "Names: %d problem%s, e.g. %s (all in the console)%n", problems.size(),
					problems.size() == 1 ? "" : "s", problems.get(0)));
		text.append("Cache: ").append(store.stats());
		if (budgetNote != null) text.append("\n(").append(budgetNote).append(')');
		status.setText(text.toString());
	}

	/* ---------------------------------------------------------------- completed-frame playback */

	/**
	 * Completed-frame playback over this view, started on first use; on the event thread. From then
	 * on the canvas shows only frames the player presents, and BigDataViewer's own sources draw -
	 * and read - nothing under them. Null once the view is closing.
	 */
	RawStrictPlayer strictPlayer() {
		if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("strictPlayer() is for the event thread");
		ViewerPanel viewer = viewerPanel();
		if (closing.get() || stoppingPlayback || viewer == null) return null;
		if (player == null) {
			RawPresenter shown = new RawPresenter(viewer.getDisplayComponent());
			RawStrictPlayer p = new RawStrictPlayer(store,
					new RawFrameRenderer(Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1))), shown);
			p.setPresentedListener(presented -> followPresented(p, shown, presented));
			viewer.getDisplay().overlays().add(shown);
			presenter = shown;
			player = p;
			players.add(p);
			presence.setHidden(true);
			viewer.requestRepaint();
			wakeChecker();		// the files to watch are now the player's
		}
		return player;
	}

	/**
	 * A frame was presented: the slider and the status card follow it, and the checker turns to its
	 * file at once. The slider moves by the program here - {@link #followingPresentation()} says so
	 * while it does, so that it is never taken for a request.
	 */
	private void followPresented(RawStrictPlayer owner, RawPresenter shown, RawPresenter.Presented presented) {
		SwingUtilities.invokeLater(() -> {
			ViewerPanel viewer = viewerPanel();
			if (closing.get() || viewer == null || player != owner || presenter != shown || shown.shownRequest() != presented.request)
				return;
			followingPresentation = true;
			try {
				viewer.setTimepoint(presented.request.timeIndex);
			} finally {
				followingPresentation = false;
			}
			wakeChecker();
		});
	}

	/**
	 * Shared entry for completed-frame navigation and future playback controls; EDT only. The
	 * target is retained while validation/preparation waits, independently of the Presented slider.
	 * File validation belongs to the existing checker. A failure before a frame can be captured
	 * completes exceptionally; a submitted frame keeps RawStrictPlayer's ordinary Result contract.
	 */
	CompletableFuture<RawStrictPlayer.Result> requestStrictFrame(int time) {
		requireEventThread();
		if (time < 0 || time >= times.size()) throw new IllegalArgumentException("No time index " + time);
		RawStrictPlayer p = strictPlayer();
		if (p == null) {
			CompletableFuture<RawStrictPlayer.Result> stopped = new CompletableFuture<RawStrictPlayer.Result>();
			stopped.completeExceptionally(new CancellationException("Completed-frame playback is stopping"));
			return stopped;
		}
		return replaceNavigation(time, p, revisions.incrementAndGet(), null);
	}

	private CompletableFuture<RawStrictPlayer.Result> replaceNavigation(int time, RawStrictPlayer p, long revision,
			RawFrameRequest captured) {
		Navigation old = navigation;
		Navigation next = new Navigation(revision, time, p);
		next.request = captured;
		navigation = next;
		if (playback != null) playback.navigationStarted(time);
		p.supersede(next.revision); // even if the target cannot yet be frozen into a frame
		cancelUnsubmitted(old);
		resumeNavigation();
		wakeChecker();
		return next.result;
	}

	/** Resume only the latest request, after background validation. Never validates a file here. */
	private void resumeNavigation() {
		requireEventThread();
		Navigation n = navigation;
		if (n == null || n.submitted || n.result.isDone() || closing.get() || player != n.player) return;
		if (viewInvalidated.get() > n.revision) {
			queueViewRefresh();
			return;
		}
		RawAcquisition.Entry e = acquisition.entry(times.get(n.time), settings.channel);
		if (e.readiness() == RawAcquisition.Readiness.PENDING) {
			navigationNote("Checking time " + (n.time + 1));
			return;
		}
		if (!e.viewable()) {
			String why = "Time " + (n.time + 1) + " unavailable: " + e.detail();
			navigationNote(why);
			IllegalStateException unavailable = new IllegalStateException(why);
			n.result.completeExceptionally(unavailable);
			navigationEnded(n.time, null, unavailable);
			return;
		}
		if (inFlight != null) return; // one job in the player, one latest target here
		RawFrameRequest request;
		try {
			request = n.request == null ? frameRequest(n.time, n.revision) : n.request;
		} catch (RuntimeException failure) {
			navigationNote("Cannot prepare time " + (n.time + 1) + ": " + failure.getMessage());
			n.result.completeExceptionally(failure);
			navigationEnded(n.time, null, failure);
			return;
		}
		if (request == null) {
			navigationNote("Waiting for the canvas to have a size");
			return;
		}
		n.submitted = true;
		n.request = request;
		lastSubmitted = request;
		inFlight = n;
		n.width = request.width;
		n.height = request.height;
		navigationNote("Preparing time " + (n.time + 1));
		n.player.request(request).whenComplete((result, failure) -> {
			if (failure == null) n.result.complete(result);
			else n.result.completeExceptionally(failure);
			Runnable ended = () -> {
				if (inFlight == n) inFlight = null;
				if (closing.get()) return;
				if (navigation != n || player != n.player) {
					resumeNavigation();
					return;
				}
				if (viewInvalidated.get() > n.revision) {
					// An off-thread edit can invalidate between commit and this callback. The paint
					// still happened: playback must hear of it even if a change-and-revert needs no redraw.
					if (failure == null && result.outcome == RawStrictPlayer.Outcome.PRESENTED)
						navigationEnded(n.time, result, null);
					queueViewRefresh();
					return;
				}
				navigationNote(failure != null ? "Frame failed: " + failure.getMessage()
						: result.outcome == RawStrictPlayer.Outcome.PRESENTED ? "" : "Frame not shown: " + result.outcome);
				navigationEnded(n.time, result, failure);
			};
			// A paint completes on Swing: record that movie presentation before the next input
			// event can replace its navigation. Worker failures still marshal to the EDT.
			if (SwingUtilities.isEventDispatchThread()) ended.run();
			else SwingUtilities.invokeLater(ended);
		});
		// Submission cancels a different time's prefetch. Reissue only now, including when a
		// redraw waited behind another job; it stays ahead of the prefetch on the driver.
		if (playback != null) playback.viewChanged();
	}

	/** The latest navigation ended - presented, not shown, or unavailable before capture: playback decides what comes next. */
	private void navigationEnded(int time, RawStrictPlayer.Result result, Throwable failure) {
		if (playback != null) playback.navigationEnded(time, result, failure);
	}

	/**
	 * Prepare the cells of time point {@code t} at the current view, ahead of its request; on the
	 * event thread. Only a time point already checked - no file IO here - and only while completed
	 * frames are shown. The player drops it if the budget has no room.
	 */
	void prefetchStrictFrame(int t) {
		requireEventThread();
		RawStrictPlayer p = player;
		if (p == null || closing.get() || t < 0 || t >= times.size()) return;
		RawAcquisition.Entry e = acquisition.entry(times.get(t), settings.channel);
		if (e == null || e.readiness() == RawAcquisition.Readiness.PENDING || !e.viewable()) return;
		RawFrameRequest ahead = frameRequest(t, 0);
		if (ahead != null) p.prefetch(ahead);
	}

	private static void cancelUnsubmitted(Navigation n) {
		if (n != null && !n.submitted) n.result.completeExceptionally(new CancellationException("Navigation superseded or stopped"));
	}

	private int requestedTimepoint() {
		Navigation n = navigation;
		return n != null && n.player == player ? n.time : shownTimepoint();
	}

	/** Whether the slider is being moved to a presented frame by the program, not by the user; on the event thread. */
	boolean followingPresentation() {
		return followingPresentation;
	}

	/**
	 * Back to BigDataViewer's own drawing; on the event thread. What the player started finishes
	 * on its own, releasing what it holds; closing the view waits for it.
	 */
	void stopStrictPlayback() {
		if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("stopStrictPlayback() is for the event thread");
		RawStrictPlayer p = player;
		if (p == null) return;
		RawPresenter shown = presenter;
		Navigation previous = navigation;
		stoppingPlayback = true;
		navigation = null;
		player = null;
		presenter = null;
		try {
			navigationNote("");
			p.close();		// the presenter lets go of every frame
			ViewerPanel viewer = viewerPanel();
			if (viewer != null) viewer.getDisplay().overlays().remove(shown);
			presence.setHidden(false);
			if (viewer != null) viewer.requestRepaint();
			cancelUnsubmitted(previous);
			wakeChecker();
		} finally {
			stoppingPlayback = false;
		}
	}

	/**
	 * What the view shows now, at time index {@code t}, frozen into a request with a new revision:
	 * the viewer transform, the canvas size, the interpolation, and each visible half with its own
	 * transform and its converter's range and colour - what BigDataViewer would draw. Null when that
	 * time point cannot be shown, or the canvas has no size yet. Any thread; a time point no thread
	 * has checked yet is checked first, except on the event thread, which does no file IO and gets
	 * null for it.
	 */
	RawFrameRequest frameRequest(int t) {
		return frameRequest(t, revisions.incrementAndGet());
	}

	private RawFrameRequest frameRequest(int t, long revision) {
		return captureFrame(t, revision, !SwingUtilities.isEventDispatchThread());
	}

	private RawFrameRequest captureFrame(int t, long revision, boolean validate) {
		if (t < 0 || t >= times.size() || handle == null) return null;
		RawAcquisition.Entry e = acquisition.entry(times.get(t), settings.channel);
		if (e == null) return null;
		if (e.readiness() == RawAcquisition.Readiness.PENDING && validate) e.validate();
		if (!e.viewable()) return null;
		ViewerPanel viewer = viewerPanel();
		if (viewer == null) return null;
		SynchronizedViewerState state = viewer.state();
		AffineTransform3D view = new AffineTransform3D();
		Interpolation interpolation;
		List<RawFrameRequest.Layer> layers = new ArrayList<RawFrameRequest.Layer>();
		synchronized (state) {		// one consistent snapshot of the viewer
			state.getViewerTransform(view);
			interpolation = state.getInterpolation();
			for (SourceAndConverter<?> soc : state.getSources()) {
				if (!state.isSourceVisible(soc) || !(soc.getSpimSource() instanceof RawPartSource)) continue;
				RawPartSource<?> source = (RawPartSource<?>) soc.getSpimSource();
				ColorConverter converter = (ColorConverter) soc.getConverter();
				AffineTransform3D toWorld = new AffineTransform3D();
				source.getSourceTransform(t, 0, toWorld);
				layers.add(new RawFrameRequest.Layer(source.spec(), toWorld, converter.getMin(), converter.getMax(),
						converter.getColor().get()));
			}
		}
		java.awt.Component canvas = viewer.getDisplayComponent();
		if (canvas.getWidth() <= 0 || canvas.getHeight() <= 0) return null;		// not laid out yet
		return new RawFrameRequest(revision, t, e, view, canvas.getWidth(), canvas.getHeight(), interpolation,
				layers);
	}

	/* ---------------------------------------------------------------- closing */

	/**
	 * Close everything, once, from any thread. What touches Swing is detached at once on the
	 * event thread; every step that can block - the renderer, the fetch queue, the worker, the
	 * store - runs on a daemon thread within a deadline. Calling it again does nothing.
	 */
	void close() {
		if (!closing.compareAndSet(false, true)) return;
		final long start = System.nanoTime();
		Runnable detach = new Runnable() {
			@Override public void run() {
				try {
					if (playback != null) playback.close();
					for (java.awt.Component c : playbackControls) c.setEnabled(false);
					stopStrictPlayback();		// nothing more is prepared or shown
					if (statusTimer != null) statusTimer.stop();
					ViewerPanel viewer = viewerPanel();
					if (viewer != null && timeListener != null) viewer.removeTimePointListener(timeListener);
					if (viewer != null && canvasListener != null) viewer.getDisplayComponent().removeComponentListener(canvasListener);
					if (viewer != null && viewListener != null) {
						// BDV notifies its plain listener list under this monitor, including off the EDT.
						synchronized (viewer.state()) { viewer.state().changeListeners().remove(viewListener); }
					}
					BdvHandlePanel h = handle;
					// ConverterSetups has no common notification monitor. Remove normally; closing
					// makes any callback already being delivered a no-op.
					if (h != null && setupListener != null) h.getConverterSetups().listeners().remove(setupListener);
					for (Action action : worldActions.values()) action.setEnabled(false);
					if (frame != null) WindowManager.removeWindow(frame);
				} finally {
					detached.countDown();
				}
			}
		};
		if (SwingUtilities.isEventDispatchThread()) detach.run();
		else SwingUtilities.invokeLater(detach);
		wakeChecker();
		Shutdown.daemon(new Runnable() {
			@Override public void run() {
				closeResources(start);
			}
		}, "OPM-online-deskew-close").start();
	}

	/** Off the event thread: stop the renderer, the queue, the worker and the store, each bounded. */
	private void closeResources(long start) {
		// the event thread detaches the window's listeners and actions first: the viewer they hang
		// on is stopped below, and stopping lets go of it; a blocked event thread is waited for only so long
		boolean uiDetached;
		try {
			uiDetached = detached.await(STOP_DEADLINE_MS, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			uiDetached = false;
		}
		// 0. completed-frame playback: nothing more is shown, and whatever was being drawn finishes
		long tp = System.nanoTime();
		boolean playbackStopped = true;
		for (RawStrictPlayer p : players) {
			p.close();		// at once, whether or not the event thread has detached it yet
			playbackStopped &= p.awaitStopped(Math.max(0, STOP_DEADLINE_MS - (System.nanoTime() - tp) / 1000000));
		}
		long playbackMillis = (System.nanoTime() - tp) / 1000000;
		// 1. the renderer: BigDataViewer's stop() joins its painter thread, so it gets a thread and a deadline
		long t0 = System.nanoTime();
		boolean viewerStopped = true;
		final BdvHandlePanel h = handle;
		if (h != null) {
			Thread stopper = Shutdown.daemon(new Runnable() {
				@Override public void run() {
					try {
						h.close();
					} catch (RuntimeException e) {
						System.out.println(OnlineDeskewView.MENU + ": stopping the viewer failed: " + e);
					}
				}
			}, "OPM-online-deskew-stop-viewer");
			stopper.start();
			viewerStopped = join(stopper, STOP_DEADLINE_MS);
		}
		long viewerMillis = (System.nanoTime() - t0) / 1000000;
		// 2. the fetch queue: no new reads are asked for
		queue.shutdown();
		// 3. the worker; a file check in progress finishes on its own
		boolean checkerStopped = checker == null || join(checker, STOP_DEADLINE_MS);
		// 4. the store: drains its reads within its own deadline and refuses everything after
		RawVolumeStore.CloseResult storeResult;
		try {
			storeResult = store.closeAsync().get(TiffRawVolumeStore.CLOSE_DEADLINE_MS + 2000, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			storeResult = new RawVolumeStore.CloseResult(false, -1, store.stats().inFlight, store.stats());
		}
		// 5. a window still showing - closed by code - goes too
		final JFrame f = frame;
		if (f != null) SwingUtilities.invokeLater(f::dispose);
		CloseResult result = new CloseResult(uiDetached, playbackStopped, playbackMillis, viewerStopped, viewerMillis, checkerStopped,
				storeResult, (System.nanoTime() - start) / 1000000);
		System.out.println(OnlineDeskewView.MENU + " " + settings.folder().getName() + ": " + result);
		try {
			Runtime.getRuntime().removeShutdownHook(exitHook);
		} catch (IllegalStateException ending) {
			// the JVM is ending and the hook is what waits for this close
		}
		closed.complete(result);
	}

	private static boolean join(Thread t, long millis) {
		try {
			t.join(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return !t.isAlive();
	}

	/** How a session closed: every step, and whether each finished within its deadline. */
	static final class CloseResult {
		/** The event thread detached the window's listeners and actions before the viewer was stopped. */
		final boolean uiDetached;
		/** Completed-frame playback: every frame being prepared or drawn had finished. */
		final boolean playbackStopped;
		final long playbackMillis;
		final boolean viewerStopped;
		final long viewerMillis;
		final boolean checkerStopped;
		final RawVolumeStore.CloseResult store;
		final long millis;
		/** Every step finished within its deadline. */
		final boolean drained;

		CloseResult(boolean uiDetached, boolean playbackStopped, long playbackMillis, boolean viewerStopped, long viewerMillis,
				boolean checkerStopped, RawVolumeStore.CloseResult store, long millis) {
			this.uiDetached = uiDetached;
			this.playbackStopped = playbackStopped;
			this.playbackMillis = playbackMillis;
			this.viewerStopped = viewerStopped;
			this.viewerMillis = viewerMillis;
			this.checkerStopped = checkerStopped;
			this.store = store;
			this.millis = millis;
			this.drained = uiDetached && playbackStopped && viewerStopped && checkerStopped && store.drained;
		}

		@Override public String toString() {
			return String.format(Locale.US, "closed in %d ms: %s%sviewer %s in %d ms, file checks %s, store %s", millis,
					uiDetached ? "" : "window NOT detached by the event thread in time, ",
					playbackMillis > 0 || !playbackStopped
							? String.format(Locale.US, "completed-frame playback %s in %d ms, ", playbackStopped ? "stopped" : "NOT stopped",
									playbackMillis)
							: "",
					viewerStopped ? "stopped" : "NOT stopped", viewerMillis, checkerStopped ? "stopped" : "NOT stopped", store);
		}
	}

	/** Display range from a middle plane of a part: the 0.1 and 99.95 percentiles. */
	static double[] displayRange(RandomAccessibleInterval<UnsignedShortType> part) {
		long z = part.min(2) + part.dimension(2) / 2;
		RandomAccessibleInterval<UnsignedShortType> plane = Views.hyperSlice(part, 2, z);
		int[] values = new int[(int) (plane.dimension(0) * plane.dimension(1))];
		int n = 0;
		Cursor<UnsignedShortType> c = Views.flatIterable(plane).cursor();
		while (c.hasNext()) values[n++] = c.next().get();
		Arrays.sort(values, 0, n);
		double low = values[(int) (0.001 * (n - 1))], high = values[(int) (0.9995 * (n - 1))];
		return new double[] { low, high > low ? high : low + 1 };
	}

	// for tests and the smoke test

	CompletableFuture<CloseResult> closed() { return closed; }
	BdvHandle handle() { return handle; }
	JFrame frame() { return frame; }
	TiffRawVolumeStore store() { return store; }
	RawAcquisition acquisition() { return acquisition; }
	List<RawAcquisition.TimeKey> times() { return times; }
	RawSourceSpec[] specs() { return specs.clone(); }
	RawGeometry geometry() { return geometry; }
	boolean isClosing() { return closing.get(); }
	Thread checker() { return checker; }
	RawStrictPlayer player() { return player; }
	RawPresenter presenter() { return presenter; }
	String statusText() { return status == null ? "" : status.getText(); }
	Action worldAction(RawWorldView plane) { return worldActions.get(plane); }
	RawPlayback playback() { return playback; }
	List<RawStrictPlayer> playersForTest() { return players; }
	String playbackText() { return playbackStatus == null ? "" : playbackStatus.getText(); }
	CompletableFuture<RawStrictPlayer.Result> navigationResult() { return navigation == null ? null : navigation.result; }
	String navigationNote() { return navigationNote; }
}
