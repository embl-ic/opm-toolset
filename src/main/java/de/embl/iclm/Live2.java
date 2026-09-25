package de.embl.iclm;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.plugin.frame.PlugInFrame;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.Color;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/**
 * Live OPM processing driven by a TCP/IP listener and a folder watcher at the same time.
 *
 * <p>The acquisition announces files over TCP when it can; the folder watcher is the safety
 * net for when it cannot, or is not running yet. Both feed one queue keyed by canonical
 * path, so a file announced twice is processed once, and a single worker drains it.
 *
 * <p><b>Parameters come first.</b> A deskew is meaningless without the acquisition geometry,
 * so the listener is explicitly a two-phase machine: until it knows the XY pixel size, the Z
 * step and the OPM angle it queues volumes and processes none of them. The geometry arrives
 * from {@code ExperimentalParameters.txt} - announced over TCP, found in the watched folder,
 * or found beside the first announced volume - or, when the acquisition does not write one,
 * from the values typed into the setup under "overwrite with manual input". Either source
 * releases the whole backlog at once; the status panel always says which one is in force, so
 * a run can never be silently deskewed with the previous acquisition's numbers.
 *
 * <p>Three situations that used to be three different problems are one mechanism here:
 * resuming after an interruption, retrying a volume that failed, and joining an acquisition
 * that is already under way. All three are answered by {@link ProcessedManifest}: enumerate
 * what is on disk, ask the manifest - which lives in the output folder - what is missing,
 * queue the difference.
 *
 * <p>Failure handling is deliberate rather than incidental. The TCP port is bound before the
 * window claims to be running, so a port clash is refused instead of leaving the watcher
 * running while TCP is silently dead. Files are marked done only <em>after</em> they
 * succeed, so a transient error costs a retry and not a missing timepoint. The stability
 * wait is bounded, so a file that keeps growing goes back to the end of the queue instead of
 * blocking every other file behind it. Metadata is re-read when the acquisition folder
 * changes, so a second acquisition cannot inherit the first one's geometry.
 *
 * <p>The Fiji Log window gets two lines, one when the listener starts and one when it stops.
 * Everything else - every file, every duration, every skip - goes to the console and to
 * {@code OPM_live2.log} in the output folder, because a live run produces one entry per
 * volume and a Log window full of them is a Log window nobody reads.
 */
public class Live2 extends PlugInFrame {
	private static final long serialVersionUID = 1L;
	private static final String LOC_KEY = "OPMlive2.loc";
	/** How often file size and modification time are sampled while acquisition is writing. */
	static final long FILE_POLL_MS = 250;
	/** Minimum continuous time with no observed content change before the TIFF check may pass. */
	static final long FILE_QUIET_MS = 1000;
	/** Longest one queue turn may wait; timeout requeues the file, it does not discard it. */
	static final long FILE_READINESS_TIMEOUT_MS = 60000;
	/** A processing failure gets two retries, each after this non-blocking backoff. */
	static final long PROCESS_RETRY_DELAY_MS = 5000;
	static final int MAX_PROCESS_ATTEMPTS = 3;
	/** Lines in the status panel; see {@link #updateStatus}. */
	private static final int STATUS_LINES = 8;
	/** Kept free at the end of a panel line, so a fitted path never touches the edge. */
	private static final int STATUS_SLACK_PIXELS = 12;
	/** A path is never cut shorter than this, whatever the panel's width. */
	private static final int MIN_PATH_CHARS = 16;
	private static final String METADATA_NAME = "ExperimentalParameters.txt";
	private static Live2 instance;

	/** Where the deskew geometry in force came from. */
	private enum MetadataSource {
		/** Nothing usable yet: volumes are queued and held, not processed. */
		WAITING,
		/** Read from an acquisition's own {@value Live2#METADATA_NAME}. */
		FILE,
		/** Typed into the setup, overriding any file. */
		MANUAL
	}

	private final Color panelColor = Parameter.frameColor;
	private final JTextArea status = new JTextArea();
	private final JButton btnSetup = new JButton("setup");
	private final JButton btnStart = new JButton("start");
	private final JButton btnStop = new JButton("stop");
	private final JButton btnExit = new JButton("exit");

	private Parameter parameter;
	private volatile boolean running = false;
	private volatile String currentFile = "";
	private volatile int processedCount = 0;
	private volatile int failedCount = 0;
	private volatile int skippedCount = 0;
	private volatile boolean tcpListening = false;

	private WatchService watchService;
	private Thread watchThread;
	private Thread tcpThread;
	private Thread workerThread;
	private ServerSocket serverSocket;
	private ProcessedManifest manifest;
	private Log liveLog;
	/** Always-virtual views of the dataset being written, or null when preview is off. */
	private volatile LivePreview preview;
	/** Free space on every volume this run reads from or writes to, and how fast it is going. */
	private final DiskSpace disk = new DiskSpace();
	/** Highest disk warning already announced, so one filling disk is not one line per volume. */
	private volatile DiskSpace.Level announcedDiskLevel = DiskSpace.Level.OK;
	/** One append/resume writer per acquisition folder (recursive watching may see several). */
	private final Map<String, OmeZarrSession> zarrSessions = new HashMap<String, OmeZarrSession>();

	private final DelayQueue<PendingFile> fileQueue = new DelayQueue<PendingFile>();
	/** Volumes that arrived before the geometry did; released in order once it is known. */
	private final List<File> heldForMetadata = Collections.synchronizedList(new ArrayList<File>());
	/** Files already reported as waiting for their acquisition-channel siblings, to say it once. */
	private final Set<String> waitingForGroup = Collections.synchronizedSet(new HashSet<String>());
	/** Reads this acquisition's channel layout off its file names; replaced at every start. */
	private volatile LiveChannelLayout channelLayout = new LiveChannelLayout();
	/** What the names decided, or null while they have not, or while nothing is reading them. */
	private volatile LiveChannelLayout.Decision channelDecision;
	/** Volumes that arrived before the layout was known; released in arrival order once it is. */
	private final List<File> heldForLayout = Collections.synchronizedList(new ArrayList<File>());
	/** Files already reported as held for the layout, so it is said once each. */
	private final Set<String> waitingForLayout = Collections.synchronizedSet(new HashSet<String>());
	/** Acquisition folders already warned about stale geometry, so a held queue stays readable. */
	private final Set<String> warnedMissingMetadataFolders =
			Collections.synchronizedSet(new HashSet<String>());
	/**
	 * Files this run has already produced a result for, whatever the overwrite setting says.
	 * <p>
	 * "Overwrite" is about results a <em>previous</em> run left on disk. Without this, a
	 * timepoint whose channels are combined was deskewed once per member file: the first file
	 * processed the whole group, and then every sibling arrived at the front of the queue,
	 * found the manifest check disabled by overwrite, and deskewed and rewrote exactly the
	 * same composite again. Correct output, twice the GPU time.
	 */
	private final Set<String> completedThisRun = Collections.synchronizedSet(new HashSet<String>());
	/** Files queued or in flight this session; entries are removed again when one fails. */
	private final Set<String> inFlight = Collections.synchronizedSet(new HashSet<String>());
	/** Last observed content activity, shared by WatchService events and the polling gate. */
	private final TiffReadinessGate readiness = new TiffReadinessGate();
	/** Processing failures and backoff are keyed by timepoint, so a sibling cannot bypass them. */
	private final Map<String, Integer> processAttempts =
			new ConcurrentHashMap<String, Integer>();
	private final Map<String, Long> retryNotBefore =
			new ConcurrentHashMap<String, Long>();
	/** Terminally failed timepoints remain here for the life of the run and are never requeued. */
	private final Map<String, File> failedQueue =
			new ConcurrentHashMap<String, File>();
	/** Directories currently registered with the watch service, so subfolders can be added. */
	private final Map<WatchKey, Path> watchedDirs = Collections.synchronizedMap(new HashMap<WatchKey, Path>());
	/** Acquisition folders already registered and backfilled, so TCP cannot re-scan on every line. */
	private final Set<String> adoptedFolders = Collections.synchronizedSet(new LinkedHashSet<String>());
	/** Open client sockets, so stopping actually closes them instead of leaking threads. */
	private final Set<Socket> clients = Collections.synchronizedSet(new HashSet<Socket>());
	/**
	 * Kept so the fast path keeps its argument, but never filled any more.
	 * <p>
	 * The MIP movie window was the projection preview for a TIFF-only run. The OPM Data Viewer
	 * reads the TIFF results directly now, so the preview is the same virtual window a store
	 * gets, and collecting a materialised movie beside it would only compete for the heap.
	 */
	private final FastClijDeskew.MipMovieSink movies = new FastClijDeskew.MipMovieSink();
	/** Multi-channel selection, shared with Channel Operation and Deskew Batch. */
	private final ChannelOperationSettings channels = new ChannelOperationSettings();

	private volatile MetadataSource metadataSource = MetadataSource.WAITING;
	/** Absolute path of the loaded parameter file, or null in manual/waiting mode. */
	private volatile String metadataFile = null;
	/** Folder whose parameter file is loaded, so a second acquisition triggers a re-read. */
	private String metadataFolder = null;
	/**
	 * First adopted acquisition folder: where a TCP-only run keeps its log and processed record,
	 * and how far up {@link #isOwnOutput} looks. Output folders no longer depend on it.
	 */
	private volatile File primaryRoot = null;
	/** A file name that stands for "a volume in this folder" when asking where its results go. */
	private static final String REPRESENTATIVE_VOLUME = ".opm-raw-volume.tif";
	/**
	 * The result folder as configured when this run started, or null between runs.
	 *
	 * <p>The processing paths put one file's result folder into {@code parameter.saveDir} for the
	 * length of a call - Deskew.processFile, BatchTiffOutput and the fast path all read their folder
	 * from there - while the watcher thread and the status panel ask where results go. A flat
	 * result folder made that harmless; a reproduced tree does not, because a reader that took the
	 * per-file folder for the configured one would mirror the tree a second time under it. The
	 * setup cannot change the output settings of a running listener ({@link LiveState}), so the
	 * copy stays true for the whole run.
	 */
	private volatile String runSaveDir = null;
	private final Object metadataLock = new Object();
	/** Error text from the single processing worker's most recent attempt. */
	private volatile String lastProcessError = "";

	private enum ProcessOutcome { DONE, SKIPPED, DEFERRED, FAILED }

	/** One file with a future-ready time, allowing retries without sleeping the worker. */
	private static final class PendingFile implements Delayed {
		final File file;
		final long readyAtNanos;

		PendingFile(File file, long delayMs) {
			this.file = file;
			this.readyAtNanos = System.nanoTime()
					+ TimeUnit.MILLISECONDS.toNanos(Math.max(0, delayMs));
		}

		@Override public long getDelay(TimeUnit unit) {
			return unit.convert(readyAtNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
		}

		@Override public int compareTo(Delayed other) {
			if (other == this) return 0;
			long difference = readyAtNanos - ((PendingFile) other).readyAtNanos;
			return difference < 0 ? -1 : difference > 0 ? 1 : 0;
		}
	}

	public Live2() {
		super("OPM Deskew Live");
		Debug.commandStarted ( "Deskew Live" );
		if (instance != null) {
			WindowManager.toFront(instance);
			dispose();							// this window is a duplicate; do not leak it
			return;
		}
		instance = this;
		WindowManager.addWindow(this);
		parameter = new Parameter("live2");
		channels.load();
		setDefaults();
		buildFrame();
		updateStatus();
	}

	private void setDefaults() {
		parameter.tryGPU = true;
		parameter.autoPartition = true;
		parameter.displayResult = false;
		// resuming is not optional here: an interrupted live run must pick up what is on disk
		parameter.processOld = true;
	}

	private void buildFrame() {
		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(panelColor);

		status.setEditable(false);
		// sized by its lines rather than a fixed pixel height, so the frame follows the font
		status.setRows(STATUS_LINES);
		status.setBackground(panelColor);
		status.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(status);

		JPanel buttons = new JPanel();
		buttons.setBackground(panelColor);
		buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
		buttons.add(btnSetup);
		buttons.add(btnStart);
		buttons.add(btnStop);
		buttons.add(btnExit);
		content.add(buttons);

		btnSetup.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) { setup(); }
		});
		btnStart.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) { startLive(); }
		});
		btnStop.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) { stopLive(); }
		});
		btnExit.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) { close(); }
		});

		/*			Quitting Fiji never calls close() on a PlugInFrame
		 * 			IJ1Helper.disposeNonImageWindows calls close() only on a PlugInDialog and
		 * 			plain dispose() on everything else, and dispose() fires windowClosed rather
		 * 			than windowClosing. Without this the static instance stayed set on a
		 * 			disposed frame, so re-opening Live Deskew afterwards found a non-null
		 * 			instance and silently did nothing.
		 *
		 * 			Deliberately NOT stopLive() here. That joins the acquisition worker for up
		 * 			to 30 s, and windowClosed arrives on the EDT during the quit, so it would
		 * 			freeze Fiji for half a minute. A run abandoned this way leaves its OME-Zarr
		 * 			at the last committed time point, which the resume path already handles;
		 * 			stop the listener first if you want the session finished cleanly. */
		addWindowListener(new java.awt.event.WindowAdapter() {
			@Override public void windowClosed(java.awt.event.WindowEvent event) {
				Prefs.saveLocation(LOC_KEY, getLocation());
				instance = null;
			}
		});

		/* Before pack(): the rim is drawn into padding reserved on the content panel, and a
		 * frame packed before that padding exists comes up with the status panel a line short
		 * of what STATUS_LINES asked for. */
		Debug.decorate ( this, content );

		add(content);
		setResizable(false);
		pack();
		setSize(Math.max(760, getWidth()), getHeight());
		Point loc = Prefs.getLocation(LOC_KEY);
		if (loc != null) setLocation(loc);
		else setLocationRelativeTo(null);
		setVisible(true);
	}

	/**			Open the setup, in simple mode unless advanced was left on last time
	 * <p>		The setup may also be opened while the listener is running, but only to change
	 * 			the deskew geometry: that is the one thing a waiting run needs to be able to
	 * 			supply without being restarted. Everything else - the port, the folders, the
	 * 			output format - would leave the running threads describing something they are
	 * 			not doing, so it is refused.
	 */
	private void setup() {
		if (running) { setupWhileRunning(); return; }
		if (!LiveSetupDialog.show(this, parameter, channels)) return;
		applySettings();
		parameter.storeParam();
		channels.store();
		updateStatus();
	}

	/**			Derive the processing switches from what the dialog stored
	 * <p>		Order matters here. applyOutputFormat is what turns the format dropdown into
	 * 			saveDeskewZarr, and parseAlignParameter keeps the alignment matrix when the
	 * 			output is canonical OME-Zarr - which it cannot know before the format has been
	 * 			applied. Running them the other way round silently dropped the matrix.
	 */
	private void applySettings() {
		parameter.parseProjectionParameter();
		parameter.applyOutputFormat();
		parameter.parseAlignParameter();
	}

	/**			Accept only the deskew geometry from a setup opened mid-run
	 * <p>		A waiting run has to be able to be told the geometry by hand without being
	 * 			restarted - that is the whole point of the manual override. Everything else is
	 * 			snapshotted and put back afterwards: changing the port, the folders, the
	 * 			filters or the output format under the running threads would leave the panel
	 * 			describing something the listener is not doing, and would change where results
	 * 			are written half way through an acquisition.
	 */
	private void setupWhileRunning() {
		LiveState before = new LiveState(parameter, channels);
		if (!LiveSetupDialog.show(this, parameter, channels)) {
			before.restore(parameter, channels);
			return;
		}
		boolean geometryChanged = before.manual != parameter.manualDeskewParameters
				|| before.xyPixelSize != parameter.xyPixelSize
				|| before.zStepSize != parameter.zStepSize
				|| before.opmAngle != parameter.opmAngle;
		boolean somethingElseChanged = before.differsBeyondGeometry(parameter, channels);

		boolean manual = parameter.manualDeskewParameters;
		double xy = parameter.xyPixelSize, dz = parameter.zStepSize, angle = parameter.opmAngle;
		before.restore(parameter, channels);
		/* The snapshot was taken before the dialog opened, and a layout decided while it was
		 * open is in neither it nor the dialog. Putting it back is the difference between the
		 * run carrying on as it was and it silently reverting to the previous acquisition's
		 * channel setup. */
		reapplyChannelDecision();
		parameter.manualDeskewParameters = manual;
		parameter.xyPixelSize = xy;
		parameter.zStepSize = dz;
		parameter.opmAngle = angle;
		parameter.storeParam();

		if (somethingElseChanged)
			IJ.showMessage("OPM Deskew Live",
					"Only the deskew parameters were applied.\n\n"
					+ "The port, folders, filters, channels and output format\n"
					+ "take effect after the listener is stopped and started again.");
		if (geometryChanged || manual) applyManualMetadata();
		updateStatus();
	}

	/** Everything the setup dialog writes, so a mid-run edit can be limited to the geometry. */
	private static final class LiveState {
		private final boolean manual;
		private final double xyPixelSize, zStepSize, opmAngle;
		private final int port, maxWait;
		private final boolean listenTcp, watchAnnounced, watchExplicit, recursive, reproduceTree;
		private final boolean saveToSame, saveVolume, saveSeparate, projX, projY, projZ;
		private final boolean maxProj, avgProj, timeLapse, overwrite, combine, interpolate;
		private final boolean preview, previewProjection, previewVolume;
		private final String watchDir, keywords, excludeKeywords, channelStr, alignmFile;
		private final String saveDir, outputFormat, fileExistStr, flipHalf;
		private final String[] channelOrder;

		LiveState(Parameter p, ChannelOperationSettings c) {
			manual = p.manualDeskewParameters;
			xyPixelSize = p.xyPixelSize; zStepSize = p.zStepSize; opmAngle = p.opmAngle;
			port = p.port; maxWait = p.maxWait;
			listenTcp = p.listenTcpIp;
			watchAnnounced = p.watchAnnouncedFolder; watchExplicit = p.watchExplicitFolder;
			recursive = p.recursive; reproduceTree = p.reproduceInputTree;
			saveToSame = p.saveToSame; saveVolume = p.saveDeskewImage; saveSeparate = p.saveSeparate;
			projX = p.projX; projY = p.projY; projZ = p.projZ;
			maxProj = p.maxProj; avgProj = p.avgProj; timeLapse = p.makeTimeLapse;
			overwrite = p.overwriteExist;
			preview = p.livePreview; previewProjection = p.livePreviewProjection;
			previewVolume = p.livePreviewVolume;
			watchDir = p.watchDir; keywords = p.keywords; excludeKeywords = p.excludeKeywords;
			channelStr = p.channelStr; alignmFile = p.alignmFile; saveDir = p.saveDir;
			outputFormat = p.outputFormat; fileExistStr = p.fileExistStr;
			combine = c.combineAcquisitionChannels; interpolate = c.interpolate; flipHalf = c.flipHalf;
			channelOrder = c.channelOrder.clone();
		}

		void restore(Parameter p, ChannelOperationSettings c) {
			p.manualDeskewParameters = manual;
			p.xyPixelSize = xyPixelSize; p.zStepSize = zStepSize; p.opmAngle = opmAngle;
			p.port = port; p.maxWait = maxWait;
			p.listenTcpIp = listenTcp;
			p.watchAnnouncedFolder = watchAnnounced; p.watchExplicitFolder = watchExplicit;
			p.recursive = recursive; p.reproduceInputTree = reproduceTree;
			p.saveToSame = saveToSame; p.saveDeskewImage = saveVolume; p.saveSeparate = saveSeparate;
			p.projX = projX; p.projY = projY; p.projZ = projZ;
			p.maxProj = maxProj; p.avgProj = avgProj; p.makeTimeLapse = timeLapse;
			p.overwriteExist = overwrite;
			p.livePreview = preview; p.livePreviewProjection = previewProjection;
			p.livePreviewVolume = previewVolume;
			p.watchDir = watchDir; p.keywords = keywords; p.excludeKeywords = excludeKeywords;
			p.channelStr = channelStr; p.alignmFile = alignmFile; p.saveDir = saveDir;
			p.outputFormat = outputFormat; p.fileExistStr = fileExistStr;
			c.combineAcquisitionChannels = combine; c.interpolate = interpolate; c.flipHalf = flipHalf;
			System.arraycopy(channelOrder, 0, c.channelOrder, 0, channelOrder.length);
		}

		boolean differsBeyondGeometry(Parameter p, ChannelOperationSettings c) {
			return port != p.port || maxWait != p.maxWait || listenTcp != p.listenTcpIp
					|| watchAnnounced != p.watchAnnouncedFolder || watchExplicit != p.watchExplicitFolder
					|| recursive != p.recursive || reproduceTree != p.reproduceInputTree
					|| saveToSame != p.saveToSame || saveVolume != p.saveDeskewImage
					|| saveSeparate != p.saveSeparate || projX != p.projX || projY != p.projY
					|| projZ != p.projZ || maxProj != p.maxProj || avgProj != p.avgProj
					|| timeLapse != p.makeTimeLapse || overwrite != p.overwriteExist
					|| preview != p.livePreview || previewProjection != p.livePreviewProjection
					|| previewVolume != p.livePreviewVolume
					|| !equal(watchDir, p.watchDir) || !equal(keywords, p.keywords)
					|| !equal(excludeKeywords, p.excludeKeywords) || !equal(channelStr, p.channelStr)
					|| !equal(alignmFile, p.alignmFile) || !equal(saveDir, p.saveDir)
					|| !equal(outputFormat, p.outputFormat) || !equal(fileExistStr, p.fileExistStr)
					|| combine != c.combineAcquisitionChannels || interpolate != c.interpolate
					|| !equal(flipHalf, c.flipHalf)
					|| !java.util.Arrays.equals(channelOrder, c.channelOrder);
		}

		private static boolean equal(String a, String b) { return a == null ? b == null : a.equals(b); }
	}

	private synchronized void startLive() {
		if (running) return;
		applySettings();
		runSaveDir = parameter.saveDir;			// before anything asks where results go

		File explicit = explicitWatchFolder();
		if (!hasDiscoverySource(explicit, parameter.listenTcpIp, parameter.watchAnnouncedFolder)) {
			IJ.showMessage("OPM Deskew Live",
					"No live input is enabled.\n\nEnable TCP/IP listening or choose a valid watch folder.");
			return;
		}
		if (workerThread != null && workerThread.isAlive()) {
			// the previous run is still finishing a volume; a second worker would drive
			// CLIJ from two threads at once
			IJ.showMessage("OPM Deskew Live", "The previous run is still finishing. Try again in a moment.");
			return;
		}

		serverSocket = null;
		tcpListening = false;
		if (parameter.listenTcpIp) {
			try {
				serverSocket = new ServerSocket(parameter.port);
				tcpListening = true;
			} catch (IOException e) {
				String message = "Could not listen on port " + parameter.port + ":\n" + e.getMessage()
						+ "\n\nAnother listener may already be using it.";
				if (explicit == null) {
					IJ.showMessage("OPM Deskew Live", message + " Nothing has been started.");
					updateStatus();
					return;
				}
				IJ.showMessage("OPM Deskew Live",
						message + "\n\nFolder watching will continue without TCP/IP.");
			}
		}

		fileQueue.clear();
		heldForMetadata.clear();
		inFlight.clear();
		readiness.reset();
		processAttempts.clear();
		retryNotBefore.clear();
		failedQueue.clear();
		waitingForGroup.clear();
		/* A new run is a new acquisition: the layout is read again from its own file names,
		 * never inherited from the one before. */
		channelLayout = new LiveChannelLayout();
		channelDecision = null;
		heldForLayout.clear();
		waitingForLayout.clear();
		warnedMissingMetadataFolders.clear();
		completedThisRun.clear();
		adoptedFolders.clear();
		processedCount = failedCount = skippedCount = 0;
		primaryRoot = explicit;
		metadataSource = MetadataSource.WAITING;
		metadataFile = null;
		metadataFolder = null;

		openLog(explicit);
		manifest = new ProcessedManifest(manifestFolder(explicit));
		disk.reset();
		announcedDiskLevel = DiskSpace.Level.OK;
		if (explicit != null) disk.watch(explicit);
		if (!parameter.saveToSame && !configuredSaveDir().isEmpty()) disk.watch(new File(configuredSaveDir()));
		/* A TIFF-only run has a preview too now: the viewer reads the result folders, which
		 * is why this is no longer gated on writing a store. */
		preview = parameter.livePreview
				? LivePreview.of(parameter, channels, !parameter.savesZarr())
				: null;
		note("started; TCP/IP " + (tcpListening ? "port " + parameter.port : "off")
				+ "; format " + parameter.outputFormat
				+ "; watch " + (explicit != null ? explicit.getAbsolutePath()
						: parameter.watchAnnouncedFolder ? "announced folders" : "off")
				+ (parameter.recursive ? " (with sub-folders)" : "")
				+ "; manifest " + manifest.getFile().getAbsolutePath()
				+ " (" + manifest.doneCount() + " already recorded)"
				+ "; expecting acquisition channels " + java.util.Arrays.toString(requiredAcquisitionChannels()));
		note("disk at start: " + disk.summary());
		if (liveLog != null) liveLog.add(parameter);

		boolean needsFolderWatcher = explicit != null
				|| (parameter.listenTcpIp && parameter.watchAnnouncedFolder);
		if (needsFolderWatcher && !openWatchService()) {
			try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
			serverSocket = null;
			tcpListening = false;
			updateStatus();
			return;
		}
		running = true;
		startWorker();
		if (needsFolderWatcher) startWatcher();
		if (tcpListening) startTcpListener();

		// manual values, when chosen, are in force before the first file even arrives
		if (parameter.manualDeskewParameters) applyManualMetadata();
		if (explicit != null) adoptFolder(explicit, "the configured watch folder");

		IJ.log("OPM Deskew Live started: "
				+ (tcpListening ? "listening on TCP/IP port " + parameter.port : "TCP/IP listener off")
				+ (explicit != null ? ", monitoring " + explicit.getAbsolutePath()
						: parameter.watchAnnouncedFolder
								? ", monitoring the folder each announced file path points into"
								: ", accepting announced paths without folder monitoring")
				+ ".");
		updateStatus();
	}

	private synchronized void stopLive() {
		if (!running && workerThread == null) return;
		boolean wasRunning = running;
		running = false;
		tcpListening = false;

		try { if (watchService != null) watchService.close(); } catch (IOException ignored) { }
		try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
		watchedDirs.clear();

		// close client sockets so their reader threads leave readLine() instead of hanging
		synchronized (clients) {
			for (Socket socket : new ArrayList<Socket>(clients)) {
				try { socket.close(); } catch (IOException ignored) { }
			}
			clients.clear();
		}

		if (workerThread != null) {
			workerThread.interrupt();
			try {
				// wait for the volume in flight, so a later start cannot open a second worker
				workerThread.join(TimeUnit.SECONDS.toMillis(30));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (workerThread.isAlive())
				note("still finishing a volume; it will stop when that completes.");
			else
				workerThread = null;
		}
		watchThread = null;
		tcpThread = null;
		serverSocket = null;
		watchService = null;

		if (workerThread == null) finishZarrSessions(true);
		// a volume still finishing still has its folder in parameter.saveDir; keep the copy until it is done
		if (workerThread == null) runSaveDir = null;
		// the preview windows stay open; the user was watching them, they are not ours to close
		if (preview != null) { preview.close(); preview = null; }
		try {
			disk.sample();
			note("disk at stop: " + disk.summary());
		} catch (Throwable ignored) {
			// a disk that cannot be measured must not stop the listener from stopping
		}
		if (wasRunning)
			IJ.log("OPM Deskew Live stopped: " + processedCount + " processed, " + failedCount
					+ " failed, " + skippedCount + " already processed.");
		note("stopped; processed " + processedCount + ", failed " + failedCount
				+ ", already processed " + skippedCount);
		closeLog();
		updateStatus();
	}

	/** Mark normally stopped live datasets complete; interrupted processes remain resumable. */
	private void finishZarrSessions(boolean markComplete) {
		for (OmeZarrSession session : new ArrayList<OmeZarrSession>(zarrSessions.values())) {
			try {
				if (markComplete) session.markComplete();
			} catch (Throwable failure) {
				note("could not finalize " + session.getRoot() + ": " + failure);
			} finally {
				session.close();
			}
		}
		zarrSessions.clear();
	}


	// ---- threads --------------------------------------------------------------------

	private void startWorker() {
		workerThread = Shutdown.daemon(new Runnable() {
			@Override
			public void run() {
				while (running && !Shutdown.stopping()) {
					File file = null;
					try {
						PendingFile pending = fileQueue.poll(500, TimeUnit.MILLISECONDS);
						if (pending == null) continue;
						file = pending.file;
						String attemptKey = attemptKey(file);
						if (failedQueue.containsKey(attemptKey)) {
							release(file);
							continue;
						}
						Long retryAt = retryNotBefore.get(attemptKey);
						long retryDelay = retryAt == null ? 0 : retryAt.longValue() - System.currentTimeMillis();
						if (retryDelay > 0) {
							offer(file, retryDelay);
							continue;
						}
						if (!metadataReady()) {
							// nothing can be deskewed yet; park it rather than spin on it
							heldForMetadata.add(file);
							updateStatus();
							continue;
						}
						if (!waitUntilStable(file)) {
							// still being written: let other files through and try again later
							offer(file, FILE_POLL_MS);
							continue;
						}
						ProcessOutcome outcome = process(file);
						if (outcome == ProcessOutcome.FAILED) retryOrFail(file);
						else if (outcome == ProcessOutcome.DONE || outcome == ProcessOutcome.SKIPPED) {
							processAttempts.remove(attemptKey);
							retryNotBefore.remove(attemptKey);
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						if (file != null) release(file);
						break;
					} catch (Throwable t) {
						if (file != null) {
							lastProcessError = String.valueOf(t);
							retryOrFail(file);
						}
						note("worker error: " + t);
					}
				}
				finishZarrSessions(true);
			}
		}, "OPM-Live-worker");
		workerThread.start();
	}

	/** Queue without blocking the sole processing worker during a quiet period or retry wait. */
	private void offer(File file, long delayMs) {
		if (file != null) fileQueue.offer(new PendingFile(file, delayMs));
	}

	/**
	 * Back off after attempts one and two; after attempt three retain the timepoint as failed.
	 * Siblings share one key, so an already queued channel cannot bypass the five-second delay.
	 */
	private void retryOrFail(File file) {
		String group = attemptKey(file);
		int attempt = processAttempts.containsKey(group)
				? processAttempts.get(group).intValue() + 1 : 1;
		if (attempt < MAX_PROCESS_ATTEMPTS && running) {
			processAttempts.put(group, Integer.valueOf(attempt));
			long retryAt = System.currentTimeMillis() + PROCESS_RETRY_DELAY_MS;
			retryNotBefore.put(group, Long.valueOf(retryAt));
			note("attempt " + attempt + "/" + MAX_PROCESS_ATTEMPTS + " failed for "
					+ file.getName() + ": " + lastProcessError
					+ "; retrying after " + (PROCESS_RETRY_DELAY_MS / 1000) + " s");
			offer(file, PROCESS_RETRY_DELAY_MS);
			updateStatus();
			return;
		}

		processAttempts.put(group, Integer.valueOf(MAX_PROCESS_ATTEMPTS));
		retryNotBefore.remove(group);
		failedQueue.put(group, file);
		failedCount++;
		List<File> failedFiles = parameter.savesZarr() || channels.combineAcquisitionChannels
				? siblingsOf(file) : Collections.singletonList(file);
		for (File failed : failedFiles) {
			if (manifest != null) manifest.markFailed(failed, parameter.outputFormat);
			release(failed);
		}
		String message = "gave up after " + MAX_PROCESS_ATTEMPTS + " attempts: "
				+ file.getAbsolutePath() + " (" + lastProcessError + ")";
		note(message);
		IJ.log("OPM Deskew Live " + message);
		updateStatus();
	}

	/**			Create the watch service before any folder can be handed to it
	 * <p>		It used to be created inside the watcher thread, which meant the folder adopted
	 * 			immediately after the thread started raced the assignment and was silently never
	 * 			registered - the acquisition then only saw files TCP announced.
	 *
	 * @return					: false when no watch service could be opened at all
	 */
	private boolean openWatchService() {
		try {
			watchService = FileSystems.getDefault().newWatchService();
			return true;
		} catch (IOException failure) {
			watchService = null;
			IJ.showMessage("OPM Deskew Live",
					"Could not start watching the file system:\n" + failure.getMessage());
			return false;
		}
	}

	private void startWatcher() {
		watchThread = Shutdown.daemon(new Runnable() {
			@Override
			public void run() {
				WatchService service = watchService;
				try {
					while (running) {
						WatchKey key = service.take();
						Path dir = watchedDirs.get(key);
						if (dir == null) { key.reset(); continue; }
						for (WatchEvent<?> event : key.pollEvents()) {
							if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
								// events were dropped; rescan so nothing is missed
								backfill(dir.toFile());
								continue;
							}
							File file = dir.resolve((Path) event.context()).toFile();
							if (file.isDirectory()) {
								if (parameter.recursive) registerTree(service, file.toPath());
								continue;
							}
							if (isMetadata(file)) loadMetadata(file);
							else if (isTiff(file)) enqueue(file);
						}
						if (!key.reset()) watchedDirs.remove(key);
					}
				} catch (ClosedWatchServiceException ignored) {
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				} catch (Throwable t) {
					note("folder watcher stopped: " + t);
				}
			}
		}, "OPM-Live-folder-watch");
		watchThread.start();
	}

	/** Register a folder and, when recursive watching is on, everything under it. */
	private void registerTree(WatchService service, Path root) {
		if (service == null) return;
		Deque<Path> pending = new ArrayDeque<Path>();
		pending.push(root);
		while (!pending.isEmpty()) {
			Path dir = pending.pop();
			/* Watching the result tree produces an event for every TIFF we write and can turn
			 * one raw volume into an unbounded result-of-result loop. */
			if (isOwnOutput(dir.toFile())) continue;
			try {
				WatchKey key = dir.register(service,
						StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
				watchedDirs.put(key, dir);
			} catch (IOException e) {
				note("could not watch " + dir + ": " + e.getMessage());
				continue;
			}
			if (!parameter.recursive) return;
			File[] children = dir.toFile().listFiles();
			if (children == null) continue;
			for (File child : children) if (child.isDirectory()) pending.push(child.toPath());
		}
	}

	private void startTcpListener() {
		tcpThread = Shutdown.daemon(new Runnable() {
			@Override
			public void run() {
				ServerSocket server = serverSocket;			// bound in startLive
				try {
					while (running && server != null && !server.isClosed()) {
						final Socket socket = server.accept();
						clients.add(socket);
						Shutdown.daemon(new Runnable() {
							@Override
							public void run() { handleTcpClient(socket); }
						}, "OPM-Live-tcp-client").start();
					}
				} catch (IOException e) {
					if (running) {
						tcpListening = false;
						note("TCP/IP listener stopped: " + e.getMessage());
						updateStatus();
					}
				}
			}
		}, "OPM-Live-tcp");
		tcpThread.start();
	}

	/**			Read announced paths from one client
	 * <p>		Every announced path teaches the listener something about the acquisition, not
	 * 			just which file to process: the folder it sits in is where the parameter file
	 * 			will be, and where the volumes already on disk are. So the folder is adopted -
	 * 			watched, searched for {@value #METADATA_NAME}, and backfilled - the first time
	 * 			it is seen, which is what lets a run join an acquisition already under way.
	 */
	private void handleTcpClient(Socket socket) {
		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			String line;
			while (running && (line = reader.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty()) continue;
				if (line.toLowerCase(Locale.ROOT).startsWith("stop")) {
					note("STOP received over TCP/IP.");
					continue;
				}
				File announced = new File(line);
				File folder = announced.getParentFile();
				if (folder != null && folder.isDirectory())
					adoptFolder(folder, "the announced path " + announced.getName());
				if (isMetadata(announced)) loadMetadata(announced);
				else if (isTiff(announced)) enqueue(announced);
				else note("ignored an announced path it cannot use: " + line);
			}
		} catch (IOException e) {
			if (running) note("TCP/IP client error: " + e.getMessage());
		} finally {
			clients.remove(socket);
			try { socket.close(); } catch (IOException ignored) { }
		}
	}


	// ---- folders and metadata -------------------------------------------------------

	/** The explicitly configured watch folder, or null when it is unset or unusable. */
	private File explicitWatchFolder() {
		if (!parameter.watchExplicitFolder) return null;
		String path = parameter.watchDir == null ? "" : parameter.watchDir.trim();
		if (path.isEmpty()) return null;
		File folder = new File(path);
		return folder.isDirectory() ? folder : null;
	}

	/** TCP-only and folder-only are both complete discovery sources. */
	static boolean hasDiscoverySource(File explicit, boolean listenTcp, boolean watchAnnounced) {
		return explicit != null || listenTcp;
	}

	/**			Take one acquisition folder into the run
	 * <p>		Registered with the watch service, searched for the parameter file, and
	 * 			enumerated so that anything already on disk and not yet recorded as done is
	 * 			queued. Idempotent: TCP announces many paths from the same folder.
	 *
	 * @param folder			: the acquisition folder
	 * @param why				: how the listener came to know about it, for the log
	 */
	private void adoptFolder(File folder, String why) {
		if (folder == null || !folder.isDirectory()) return;
		String key;
		try {
			key = folder.getCanonicalPath().toLowerCase(Locale.ROOT);
		} catch (IOException unresolved) {
			key = folder.getAbsolutePath().toLowerCase(Locale.ROOT);
		}
		if (!adoptedFolders.add(key)) return;

		if (primaryRoot == null) {
			primaryRoot = folder;
			relocateLog(folder);
			relocateManifest(folder);
		}
		// the raw files keep arriving here, so this volume is filling too, not only the output
		disk.watch(folder);
		boolean watch = parameter.watchAnnouncedFolder || folder.equals(explicitWatchFolder());
		if (watch) {
			registerTree(watchService, folder.toPath());
			note("watching " + folder.getAbsolutePath() + " (from " + why + ")");
		}

		/* Even a folder this run does not watch is searched for the parameter file: an
		 * announced path is often the first thing that tells the listener where the
		 * acquisition's geometry can be read from. */
		File found = findMetadata(folder);
		if (found != null) loadMetadata(found);
		// only enumerate what is on disk for a folder this run is actually following
		if (watch) backfill(folder);
		updateStatus();
	}

	/** The acquisition's own parameter file in this folder, or in one of its sub-folders. */
	private File findMetadata(File folder) {
		File direct = new File(folder, METADATA_NAME);
		if (direct.isFile()) return direct;
		if (!parameter.recursive) return null;
		File[] children = folder.listFiles();
		if (children == null) return null;
		for (File child : children) {
			if (!child.isDirectory()) continue;
			File nested = new File(child, METADATA_NAME);
			if (nested.isFile()) return nested;
		}
		return null;
	}

	/** Whether the deskew geometry is known, from either source. */
	private boolean metadataReady() {
		return metadataSource != MetadataSource.WAITING;
	}

	/**			Load the parameter file beside this volume, or refuse geometry from another folder
	 * <p>		A recursive or TCP-driven run may encounter several acquisitions. Geometry read
	 * 			from one of them is never a usable default for another: if the new folder has no
	 * 			parameter file, its volumes wait until one arrives or manual values are supplied.
	 *
	 * @param file				: volume about to be processed
	 * <p>
	 * @return					: true when the geometry in force belongs to this volume
	 */
	private boolean ensureMetadataFor(File file) {
		if (metadataSource == MetadataSource.MANUAL) return true;
		File folder = file.getParentFile();
		if (folder == null) return false;
		String key = folder.getAbsolutePath();
		synchronized (metadataLock) {
			if (metadataSource == MetadataSource.FILE && metadataIsFor(file, metadataFile)) {
				metadataFolder = key;
				return true;
			}
			File metadata = new File(folder, METADATA_NAME);
			if (metadata.isFile()) {
				applyMetadata(metadata);
				if (metadataSource == MetadataSource.FILE
						&& metadata.getAbsolutePath().equals(metadataFile)) {
					metadataFolder = key;
					warnedMissingMetadataFolders.remove(key(folder));
					return true;
				}
			} else if (metadataFolder == null) {
				note("no " + METADATA_NAME + " beside " + file.getName()
						+ "; using the parameters already in force.");
				metadataFolder = key;		// only warn once per folder
			} else if (warnedMissingMetadataFolders.add(key(folder))) {
				File source = metadataFile == null ? new File(metadataFolder) : new File(metadataFile).getParentFile();
				note("hold " + file.getName() + ": no " + METADATA_NAME + " in "
						+ folder.getAbsolutePath() + ", and the geometry in force came from "
						+ (source == null ? metadataFolder : source.getAbsolutePath()));
			}
			return false;
		}
	}

	/** Package-visible because the acquisition-folder boundary is a headless unit-test seam. */
	static boolean metadataIsFor(File volume, String loadedMetadataFile) {
		if (volume == null || loadedMetadataFile == null) return false;
		File volumeFolder = volume.getParentFile();
		File metadataFolder = new File(loadedMetadataFile).getParentFile();
		if (volumeFolder == null || metadataFolder == null) return false;
		try {
			return volumeFolder.getCanonicalFile().equals(metadataFolder.getCanonicalFile());
		} catch (IOException unresolved) {
			return volumeFolder.getAbsoluteFile().equals(metadataFolder.getAbsoluteFile());
		}
	}

	private void loadMetadata(File file) {
		if (file == null || !file.isFile()) return;
		if (metadataSource == MetadataSource.MANUAL) {
			note("ignoring " + file.getAbsolutePath() + ": manual deskew parameters are in force.");
			return;
		}
		synchronized (metadataLock) {
			applyMetadata(file);
			File folder = file.getParentFile();
			metadataFolder = folder == null ? null : folder.getAbsolutePath();
		}
	}

	/** Caller must hold metadataLock: Parameter parsing reaches through shared state. */
	private void applyMetadata(File file) {
		parameter.deskewmFile = file.getAbsolutePath();
		parameter.parseDeskewParameterLive();
		if (parameter.deskewMatrix == null) {
			note("could not read deskew parameters from " + file.getAbsolutePath() + "; still waiting.");
			return;
		}
		metadataFile = file.getAbsolutePath();
		metadataSource = MetadataSource.FILE;
		note(String.format(Locale.US,
				"deskew parameters from %s: xy=%.1f nm, dz=%.1f nm, angle=%.1f deg, interval=%.3f s",
				file.getAbsolutePath(), parameter.xyPixelSize, parameter.zStepSize,
				parameter.opmAngle, parameter.frameInterval));
		releaseHeldFiles("the parameter file arrived");
		updateStatus();
	}

	/**			Put the values typed into the setup in force, overriding any parameter file
	 * <p>		Height is deliberately left at zero: it only sets the Z translation, which
	 * 			{@link Parameter#updateDeskewMatrix} recomputes per file from the height of the
	 * 			TIFF actually being processed. Taking it from the dialog would bake the wrong
	 * 			camera ROI into every volume of an acquisition that used a different one.
	 */
	private void applyManualMetadata() {
		synchronized (metadataLock) {
			if (!parameter.manualDeskewParameters) {
				// manual mode was switched off again: fall back to whatever a file supplied
				metadataSource = metadataFile == null ? MetadataSource.WAITING : MetadataSource.FILE;
				updateStatus();
				return;
			}
			parameter.deskewMatrix = Transform.deskew(
					parameter.zStepSize, parameter.xyPixelSize, parameter.opmAngle, 0 );
			metadataSource = MetadataSource.MANUAL;
			metadataFile = null;
			note(String.format(Locale.US,
					"deskew parameters from manual input: xy=%.1f nm, dz=%.1f nm, angle=%.1f deg",
					parameter.xyPixelSize, parameter.zStepSize, parameter.opmAngle));
			releaseHeldFiles("manual parameters were entered");
			updateStatus();
		}
	}

	/** Put everything that was waiting for the geometry back on the queue, in arrival order. */
	private void releaseHeldFiles(String why) {
		List<File> released;
		synchronized (heldForMetadata) {
			if (heldForMetadata.isEmpty()) return;
			released = new ArrayList<File>(heldForMetadata);
			heldForMetadata.clear();
		}
		for (File file : released) offer(file, 0);
		note("released " + released.size() + " held volume(s) because " + why + ".");
	}


	// ---- processing -----------------------------------------------------------------

	/** Process one queued file and leave retry policy to the worker. */
	private ProcessOutcome process(File file) {
		currentFile = file.getAbsolutePath();
		updateStatus();
		long started = System.currentTimeMillis();
		boolean ok = false;
		boolean deferred = false;
		boolean skipped = false;
		boolean writesSucceeded = true;
		ProcessOutcome outcome = ProcessOutcome.FAILED;
		lastProcessError = "processing did not complete";
		List<File> processedFiles = new ArrayList<File>();
		processedFiles.add(file);
		try {
			if (completedThisRun.contains(completionKey(file))) {
				skippedCount++;
				skipped = ok = true;
				outcome = ProcessOutcome.SKIPPED;
				note("skip   " + stamp(started) + "  " + file.getName()
						+ " (its acquisition-channel group was already processed in this run)");
				return outcome;
			}
			if (!parameter.overwriteExist && manifest != null
					&& manifest.isDone(file, parameter.outputFormat)) {
				skippedCount++;
				skipped = ok = true;
				outcome = ProcessOutcome.SKIPPED;
				note("skip   " + stamp(started) + "  " + file.getName()
						+ " (already recorded as processed)");
				/* A resumed run may skip every volume it is handed for a long while. What those
				 * volumes produced is on disk, so the preview can show it now rather than only
				 * once the first new time point has been processed. */
				previewResultsOf(file);
				return outcome;
			}
			// a new acquisition folder means new geometry; never inherit the previous one's
			if (!ensureMetadataFor(file)) {
				deferred = true;
				outcome = ProcessOutcome.DEFERRED;
				heldForMetadata.add(file);
				updateStatus();
				return outcome;
			}
			// and the file names decide the channel layout before the first of them is deskewed
			if (!channelLayoutDecided(file)) {
				deferred = true;
				outcome = ProcessOutcome.DEFERRED;
				return outcome;
			}
			note("start  " + stamp(started) + "  " + file.getName());

			List<File> group = null;
			if (parameter.savesZarr()) {
				group = readyChannelGroup(file, true);
				if (group == null) {
					deferred = true;
					outcome = ProcessOutcome.DEFERRED;
					return outcome;
				}
				processCanonicalZarr(file, group);
			}

			if (parameter.savesTiff()) {
				if (channels.combineAcquisitionChannels) {
					if (group == null) group = readyChannelGroup(file, false);
					if (group == null) {
						deferred = true;
						outcome = ProcessOutcome.DEFERRED;
						return outcome;
					}
					processedFiles = group;
					writesSucceeded = processChannelGroup(file, group);
				} else {
					writesSucceeded = processSingleFile(file);
				}
			} else if (group != null) {
				processedFiles = group;
			}
			if (!writesSucceeded) {
				// keep a specific reason when the writer gave one
				if ("processing did not complete".equals(lastProcessError))
					lastProcessError = "one or more requested results could not be written";
				return ProcessOutcome.FAILED;
			}
			ok = true;
			outcome = ProcessOutcome.DONE;
			processedCount++;
			previewResultsOf(file);
		} catch (Throwable t) {
			lastProcessError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
			note("attempt failed for " + file.getAbsolutePath() + ": " + t);
			t.printStackTrace();
		} finally {
			// A merely incomplete channel group is neither failed nor done; it stays queued.
			if (!deferred && ok && manifest != null) {
				for (File processed : processedFiles) {
					manifest.markDone(processed, parameter.outputFormat);
				}
			}
			// every member of a group the run has just produced, so no sibling redoes it
			if (!deferred && ok && !skipped)
				for (File processed : processedFiles) completedThisRun.add(completionKey(processed));
			if (!deferred && ok) {
				for (File processed : processedFiles) release(processed);
				// a skipped file already said so; do not follow it with a 0.000 s "done"
				if (!skipped) {
					long finished = System.currentTimeMillis();
					note((ok ? "done   " : "failed ") + stamp(finished) + "  " + file.getName()
							+ String.format(Locale.US, "  (%.3f s in total)", (finished - started) / 1000.0));
					checkDiskSpace(file);
				}
			}
			currentFile = "";
			updateStatus();
		}
		return outcome;
	}

	/**			Re-measure the disks and say something when the headroom gets short
	 * <p>		The rate is measured, not predicted: free space is sampled after every finished
	 * 			volume, and its slope already accounts for the raw files still arriving, the
	 * 			results being written, and anything else on the machine. Two consequences worth
	 * 			knowing - the first minute of a run has no forecast, and a run that pauses
	 * 			reports a longer remaining time than the acquisition will actually allow.
	 * <p>		A crossing into WARNING or CRITICAL reaches the Fiji Log window. That is the one
	 * 			exception to keeping the Log window to start and stop: a disk that fills ends
	 * 			the acquisition, and a line in a file nobody has open is not a warning.
	 *
	 * @param file				: the volume just finished, whose output folder is also tracked
	 */
	private void checkDiskSpace(File file) {
		try {
			disk.watch(outputFolderFor(file));
			disk.sample();
		} catch (Throwable unreadable) {
			return;					// a disk that cannot be measured must not stop processing
		}
		DiskSpace.Report worst = disk.worst();
		if (worst == null) return;
		note("disk   " + disk.summary());
		DiskSpace.Level level = worst.level();
		if (level.ordinal() <= announcedDiskLevel.ordinal()) return;
		announcedDiskLevel = level;
		IJ.log("OPM Deskew Live " + (level == DiskSpace.Level.CRITICAL ? "DISK ALMOST FULL" : "disk space low")
				+ ": " + worst.format()
				+ (level == DiskSpace.Level.CRITICAL
						? "\nProcessing will fail when the volume fills. Free space or change the output folder."
						: ""));
	}

	/** One file on its own: the CLIJ fast path when it applies, the shared portal otherwise. */
	private boolean processSingleFile(File file) {
		FastClijDeskew.Options options = FastClijDeskew.optionsFromParameter(parameter);
		options.saveDeskewTiff = parameter.saveDeskewImage;
		/* No MIP movie window any more. It used to be the projection preview for a TIFF-only
		 * run, because there was no store for a virtual view to read; the OPM Data Viewer now
		 * reads the TIFF results themselves, so showing the movie too would put a materialised
		 * window on top of the virtual one - the very thing that made the preview look as
		 * though it were always materialised when a store was present. */
		options.makeMipMovies = false;
		options.displayMipMovies = false;

		if (FastClijDeskew.canUseFastPath(parameter)) {
			/* The fast path takes its folder from saveDir (FastClijDeskew.resolveSaveRoot), so it
			 * is handed this file's, as processWithFallback and the channel-group path are. It
			 * used to get the configured folder itself and ignored a reproduced input tree, while
			 * the preview, the disk watch and the own-output test all looked in the tree. */
			String savedDir = parameter.saveDir;
			try {
				parameter.saveDir = outputFolderFor(file).getAbsolutePath();
				FastClijDeskew.Result result = FastClijDeskew.processFile(file, parameter, options, movies, 0);
				note("timing " + file.getName() + ": " + FastClijDeskew.formatTiming(result));
				/* Two questions, both needed. allWritten is what the writer itself reports, and
				 * catches a save that failed; the file check catches a path the fast path never
				 * attempted at all, which its own return value cannot describe. */
				if (result.allWritten && fastPathOutputsExist(file, result, options)) return true;
				note("fast path left one or more requested files unwritten; trying the shared path");
			} catch (Throwable fastError) {
				note("fast path failed, falling back: " + fastError.getMessage());
			} finally {
				parameter.saveDir = savedDir;		// do not let one file's folder leak into the next
			}
		}
		return processWithFallback(file);
	}

	/**			Point the live preview at what this file's time point has put on disk
	 * <p>		Raised only once a result exists, never before: the viewer takes a dataset's views
	 * 			from the first scan that finds it, so a scan landing between the volume and its
	 * 			projections being written opened one without the other, and a scan before
	 * 			anything was written had to be retried against a bounded budget. It used to be
	 * 			raised from the single-file path only, so a TIFF run combining acquisition
	 * 			channels - the ordinary multi-channel setup - never showed a preview at all.
	 * <p>		The format rule is LivePreview's: a run writing OME-Zarr previews the store,
	 * 			which is only raised here once it has a commit marker, and a TIFF-only run
	 * 			previews its result folder. {@link LivePreview#update} acts once per root.
	 */
	private void previewResultsOf(File file) {
		LivePreview watching = preview;			// stopLive may clear the field under us
		if (watching == null || file == null) return;
		File output = outputFolderFor(file);
		if (!parameter.savesZarr()) {
			watching.update(output);
			return;
		}
		File root = OmeZarrConverter.defaultRoot(output, file.getParentFile());
		if (new File(root, ".zattrs").isFile()) watching.update(root);
	}

	/** FastClijDeskew predates failure propagation, so verify the files it was asked to write. */
	private boolean fastPathOutputsExist(File input, FastClijDeskew.Result result,
			FastClijDeskew.Options options) {
		File root = FastClijDeskew.resolveSaveRoot(input, parameter);
		File deskew = options.saveSeparate ? new File(root, "deskew") : root;
		boolean ok = !options.saveDeskewTiff
				|| FastClijDeskew.volumeFile(deskew, result.name).isFile();
		if (options.saveIndividualMips && parameter.doProjection) {
			if (parameter.projX) ok &= fastProjectionExists(root, options, result.name, "maxX");
			if (parameter.projY) ok &= fastProjectionExists(root, options, result.name, "maxY");
			if (parameter.projZ) ok &= fastProjectionExists(root, options, result.name, "maxZ");
		}
		return ok;
	}

	private boolean fastProjectionExists(File root, FastClijDeskew.Options options,
			String name, String projection) {
		File folder = options.saveSeparate ? new File(root, projection) : root;
		return new File(folder, name + "-" + projection + "projection.tif").isFile();
	}

	/**			Deskew one timepoint's acquisition channels together
	 * <p>		A file arriving on its own is not enough when channels are combined: the other
	 * 			{@code _ChannelNNNN} files of the same timepoint have to be on disk too. Until
	 * 			they are, the file goes back on the queue rather than being processed alone or
	 * 			marked done - the acquisition writes them seconds apart, and a half-built
	 * 			group must not become a one-channel result.
	 *
	 * @param file				: the file that arrived
	 * @param group				: every acquisition-channel file of the same timepoint
	 */
	private boolean processChannelGroup(File file, List<File> group) {
		String outputName = BatchProcessingUtils.channelGroupOutputName(group) + "-deskewed";
		ImagePlus combined = null;
		BatchTiffOutput tiff = null;
		boolean success = false;
		long deskewStart = System.nanoTime();
		try {
			combined = MultiChannelDeskew.deskewGroup(group, parameter, channels, outputName);
			if (combined == null) throw new IllegalStateException("No selected channel was produced for " + outputName);
			double deskewSec = (System.nanoTime() - deskewStart) / 1.0e9;
			long writeStart = System.nanoTime();
			String savedDir = parameter.saveDir;
			try {
				parameter.saveDir = outputFolderFor(file).getAbsolutePath();
				parameter.impInput = null;
				/* The projections go through ProjectionBatch, as Deskew Batch's do: one upload
				 * per channel. Deskew.prepareResults pushed the whole multi-channel hyperstack
				 * once per axis; for two camera halves of a production volume that is 2.6 GB,
				 * over OpenCL's single-allocation limit on an 8 GB card, so every projection
				 * failed on the GPU first, fell back to the CPU, and left the context unable to
				 * allocate even the next time point's deskew - which then ran on the CPU too.
				 * Measured: 20 s deskew and 50 s in total for the first time point, 89 s and
				 * 147 s for the second. */
				tiff = BatchTiffOutput.prepare(combined, parameter);
				tiff.write(parameter);
				success = true;
			} finally {
				parameter.saveDir = savedDir;
			}
			note(String.format(Locale.US,
					"TIFF   %s: %d acquisition channel(s), deskew %.3f, save %.3f s",
					outputName, Integer.valueOf(group.size()), Double.valueOf(deskewSec),
					Double.valueOf((System.nanoTime() - writeStart) / 1.0e9)));
		} catch (IOException failure) {
			lastProcessError = failure.getMessage();
			note("TIFF   " + outputName + " failed: " + failure.getMessage());
		} finally {
			if (tiff != null) tiff.close();
			if (combined != null) { combined.changes = false; combined.close(); }
			Utils.collectGarbage();
		}
		return success;
	}

	/**			Hold this file until the acquisition's channel layout is known
	 * <p>		Whether a time point is one file or several is the one thing about a live run
	 * 			that cannot be got wrong quietly: combining a single-file acquisition waits for
	 * 			a sibling that never arrives, and not combining a multi-file one writes every
	 * 			channel as a result of its own. The names say which, so with
	 * 			<i>automatic combine</i> ticked nothing is deskewed until they have said it.
	 * <p>		Held files go back on the queue rather than being processed out of turn: the
	 * 			canonical OME-Zarr writer appends time points in the order they are handed to
	 * 			it, so releasing them in arrival order is not a nicety. The one that comes back
	 * 			every second is also what re-evaluates the quiet period, for an acquisition
	 * 			whose second file never arrives at all.
	 *
	 * @param file				: the file the worker has just taken off the queue
	 * <p>
	 * @return					: true when it may be processed now
	 */
	private boolean channelLayoutDecided(File file) {
		if (!channels.autoCombineChannels || channelDecision != null) return true;
		long now = System.currentTimeMillis();
		LiveChannelLayout layout = channelLayout;
		LiveChannelLayout.Decision decided = layout.observe(file, now);
		if (decided == null) decided = layout.decideIfQuiet(now);
		synchronized (heldForLayout) {
			if (!heldForLayout.contains(file)) heldForLayout.add(file);
		}
		if (decided == null) {
			if (waitingForLayout.add(key(file)))
				note("hold   " + file.getName()
						+ ": reading the channel layout from the announced file names");
			offer(file, FILE_QUIET_MS);
			updateStatus();
			return false;
		}
		applyChannelDecision(decided);
		releaseLayoutBacklog();
		return false;			// this file is in the backlog and comes straight back
	}

	/**			Put a decided layout in force, and on the panel and in the log
	 * <p>		With <i>auto channel assignment</i> the slots and the flip are written too, so
	 * 			the greyed rows of the setup describe this acquisition rather than the last one.
	 * 			They are stored, because the setup dialog reads the shared settings when it
	 * 			opens - a decision the user cannot see is a decision they cannot check.
	 */
	private void applyChannelDecision(LiveChannelLayout.Decision decided) {
		channelDecision = decided;
		if (decided.kind == LiveChannelLayout.Kind.AMBIGUOUS) {
			note("channels: " + decided.reason + "; the configured setup is used unchanged ("
					+ (channels.combineAcquisitionChannels
							? "combining matching _Channel#### files" : "one file per time point") + ")");
			updateStatus();
			return;
		}
		if (channels.autoChannelAssignment) {
			String assigned = LiveChannelLayout.apply(channels, parameter.channelStr, decided);
			note("channels: " + decided.reason + "; " + assigned);
		} else {
			channels.combineAcquisitionChannels = decided.combines();
			note("channels: " + decided.reason + "; "
					+ (decided.combines() ? "combining" : "one file per time point")
					+ ", output channels as configured");
			int configured = requiredAcquisitionChannels().length;
			if (configured != decided.acquisitionChannels.length)
				note("channels: the configured output slots name " + configured
						+ " acquisition channel(s) while the acquisition announced "
						+ decided.acquisitionChannels.length
						+ "; auto channel assignment would have matched them");
		}
		/* A fallback only, for a selection that names no channel at all; the slots answer
		 * first. It is what a Zarr run's expected channel set falls back to. */
		parameter.zarrExpectedAcquisitionChannels = decided.acquisitionChannels.length;
		/* The two paths differ in one thing, and the decision is what chooses between them:
		 * a single file measures its own SIFT matrix when none is loaded (Deskew.process),
		 * while a combined group only ever loads one (MultiChannelDeskew.alignmentSet). Said
		 * once, here, rather than left as an alignment that quietly became a bare flip. */
		if (decided.combines() && parameter.channelStr != null
				&& parameter.channelStr.startsWith("align with SIFT")
				&& (parameter.alignmFile == null || !new File(parameter.alignmFile).isFile()))
			note("channels: \"align with SIFT\" with combined files needs an align matrix file;"
					+ " without one the halves are flipped but not aligned");
		/* And combining always mirrors one half onto the other's frame - that is what putting
		 * several files into one result means. For the two options that kept both halves as
		 * acquired, the pixels therefore come out the other way round than they did file by
		 * file, which is worth one line rather than a puzzled look at the result. */
		if (decided.combines() && ("only right".equals(parameter.channelStr)
				|| "left & right separately".equals(parameter.channelStr)))
			note("channels: combined files always mirror the "
					+ (channels.isFlipLeft() ? "left" : "right") + " half onto the other's frame;"
					+ " \"" + parameter.channelStr + "\" left it as acquired while one file was"
					+ " one time point");
		channels.store();
		/* Start built the preview from the previous run's channel settings, because these ones
		 * did not exist yet. Nothing has been raised: the preview waits for a result on disk. */
		LivePreview watching = preview;
		if (watching != null) watching.composeAs(DeskewChannelView.of(parameter, channels));
		updateStatus();
	}

	/** Write a decision already taken back over settings that have just been restored. */
	private void reapplyChannelDecision() {
		LiveChannelLayout.Decision decided = channelDecision;
		if (decided == null || !channels.autoCombineChannels) return;
		if (decided.kind == LiveChannelLayout.Kind.AMBIGUOUS) return;
		if (channels.autoChannelAssignment)
			LiveChannelLayout.apply(channels, parameter.channelStr, decided);
		else channels.combineAcquisitionChannels = decided.combines();
	}

	/** Put everything held for the layout back on the queue, in arrival order. */
	private void releaseLayoutBacklog() {
		List<File> released;
		synchronized (heldForLayout) {
			if (heldForLayout.isEmpty()) return;
			released = new ArrayList<File>(heldForLayout);
			heldForLayout.clear();
		}
		waitingForLayout.clear();
		/* A millisecond apart rather than all at zero: the queue orders by ready time, and
		 * equal times leave the order to the heap. */
		long delay = 0;
		for (File file : released) offer(file, delay++);
		note("released " + released.size() + " volume(s) held while the channel layout was read.");
	}

	/** Wait for the complete configured acquisition-channel set, then stabilize every member. */
	private List<File> readyChannelGroup(File file, boolean exactForZarr) {
		List<File> group = siblingsOf(file);
		int[] wanted = requiredAcquisitionChannels();
		for (int channel : wanted) if (findAcquisitionChannel(group, channel) == null) {
			// said once per file: the retry loop would otherwise report it every second
			if (waitingForGroup.add(key(file)))
				note("hold   " + file.getName() + ": waiting for acquisition channel "
						+ channel + " of " + java.util.Arrays.toString(wanted));
			offer(file, FILE_QUIET_MS);
			return null;
		}
		waitingForGroup.remove(key(file));
		if (exactForZarr && !OpmTimepointProcessor.hasExactChannels(group, wanted))
			throw new IllegalStateException("OME-Zarr time point " + OpmTimepointProcessor.timeLabel(group)
					+ " has channels other than the configured exact set " + java.util.Arrays.toString(wanted));
		for (File member : group) if (!waitUntilStableQuietly(member)) {
			offer(file, FILE_POLL_MS);
			return null;
		}
		return group;
	}

	/** Append the raw group's unmirrored, unaligned halves to its acquisition-level dataset. */
	private void processCanonicalZarr(File file, List<File> group) throws Exception {
		OmeZarrSession session = zarrSessionFor(file, group);
		double elapsed = session.getCommittedTimepoints() * Math.max(0, parameter.frameInterval);
		OpmTimepointProcessor.TimePoint timePoint = new OpmTimepointProcessor.TimePoint(
				OpmTimepointProcessor.timeLabel(group), group, elapsed);
		OmeZarrSession.Timing timing = new OmeZarrSession.Timing();
		boolean appended = session.append(timePoint, timing);
		note("Zarr   " + (appended ? "committed " : "already had ") + timePoint.label
				+ " in " + session.getRoot().getAbsolutePath()
				+ (appended ? "; " + timing.format() : ""));
		// only a committed time point is safe to read, which is exactly what has just happened
		LivePreview watching = preview;			// stopLive may clear the field under us
		if (appended && watching != null) watching.update(session.getRoot());
	}

	private OmeZarrSession zarrSessionFor(File file, List<File> group) throws Exception {
		File inputFolder = file.getParentFile();
		File saveRoot = outputFolderFor(file);
		File zarrRoot = OmeZarrConverter.defaultRoot(saveRoot, inputFolder);
		String key = zarrRoot.getCanonicalPath().toLowerCase(Locale.ROOT);
		OmeZarrSession session = zarrSessions.get(key);
		if (session != null) return session;

		if (parameter.overwriteExist && zarrRoot.exists())
			org.apache.commons.io.FileUtils.deleteDirectory(zarrRoot);
		OmeZarrConverter.Options options = OmeZarrConverter.optionsFromParameter(parameter, channels, inputFolder);
		// the camera height is in the TIFF metadata; the pixels are not needed to know it
		double[][] matrix = Transform.deskew(options.zStepSizeUm, options.xyPixelSizeUm,
				options.opmAngleDegrees, VolumeIO.height(group.get(0)));
		OpmProvenance provenance = OmeZarrConverter.provenance(inputFolder, zarrRoot, options, matrix);
		session = new OmeZarrSession(zarrRoot, inputFolder, provenance, matrix,
				options.tryGPU, options.writeProjections);
		zarrSessions.put(key, session);
		note("Zarr   opened " + zarrRoot.getAbsolutePath()
				+ " (" + session.getCommittedTimepoints() + " time point(s) already committed)");
		return session;
	}

	/** Every file in the same acquisition-channel group as this one, sorted by channel. */
	private List<File> siblingsOf(File file) {
		List<File> group = new ArrayList<File>();
		File folder = file.getParentFile();
		String key = BatchProcessingUtils.channelGroupKey(file);
		File[] neighbours = folder == null ? null : folder.listFiles();
		if (neighbours != null) {
			for (File candidate : neighbours) {
				if (candidate.isFile() && isTiff(candidate)
						&& key.equals(BatchProcessingUtils.channelGroupKey(candidate)))
					group.add(candidate);
			}
		}
		if (group.isEmpty()) group.add(file);
		ChannelOperationSettings.sortByAcquisitionChannel(group);
		return group;
	}

	/**			The acquisition channel numbers a complete time point must supply
	 * <p>		Derived from the output slots the user configured rather than from a separate
	 * 			count field: selecting {@code _Channel0002-left} already says the acquisition
	 * 			writes at least two files per time point. The set is filled in sequentially up
	 * 			to the highest one named, because the canonical OME-Zarr writer stores every
	 * 			half of every file it finds and rejects a time point whose channel set differs
	 * 			from the expected one.
	 */
	int[] requiredAcquisitionChannels() {
		return channels.requiredAcquisitionChannels(parameter.zarrExpectedAcquisitionChannels);
	}

	private File findAcquisitionChannel(List<File> group, int channel) {
		for (File candidate : group) {
			int number = BatchProcessingUtils.acquisitionChannel(candidate);
			if (number == channel) return candidate;
		}
		return null;
	}

	private boolean waitUntilStableQuietly(File file) {
		try {
			return waitUntilStable(file);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private boolean processWithFallback(File file) {
		String savedDir = parameter.saveDir;
		boolean savedRecursive = parameter.recursive;
		try {
			parameter.saveDir = outputFolderFor(file).getAbsolutePath();
			// the folder structure is already resolved above; do not let processFile redo it
			parameter.recursive = false;
			return Deskew.processFile(file.getAbsolutePath(), parameter);
		} finally {
			parameter.saveDir = savedDir;		// do not let one file's folder leak into the next
			parameter.recursive = savedRecursive;
		}
	}

	/**			Where one input file's results go
	 * <p>		Three layouts, decided by the file's own path and the output settings only:
	 * <ul>
	 * <li>		"save result to the same (data) folder", or no result folder given:
	 * 			{@code <input folder>\result};
	 * <li>		a result folder given: that folder itself, for every acquisition alike;
	 * <li>		a result folder given and "reproduce input folder structure": the input folder's
	 * 			whole path under it, less its drive, then {@code result} -
	 * 			{@code E:\OPM\3_timelapse_0\x.tiff} into {@code I:\...\New folder} is
	 * 			{@code I:\...\New folder\OPM\3_timelapse_0\result}
	 * 			({@link BatchProcessingUtils#mirroredResultFolder}).
	 * </ul>
	 * <p>		The mirror used to be taken relative to {@link #primaryRoot}, the first folder a
	 * 			TCP path announced: that acquisition landed straight in the result folder, a
	 * 			second one in the same session landed there too, mixed with it, and where results
	 * 			went depended on which acquisition happened to be announced first. It is now the
	 * 			same whether a path arrives over TCP/IP or from a watched folder, in this session
	 * 			or a later one, which is also what lets a resumed run find its own results.
	 * 			Before that, the mirror was tied to recursive watching, which meant switching on
	 * 			sub-folder watching silently changed where results were written.
	 */
	private File outputFolderFor(File file) {
		String configured = configuredSaveDir();
		if (parameter.saveToSame || configured.isEmpty()) return new File(file.getParentFile(), "result");
		File root = new File(configured);
		return parameter.reproduceInputTree ? BatchProcessingUtils.mirroredResultFolder(file, root) : root;
	}

	/** The result folder of an acquisition folder's files, by the same rule as {@link #outputFolderFor}. */
	private File resultFolderOf(File acquisitionFolder) {
		return outputFolderFor(new File(acquisitionFolder, REPRESENTATIVE_VOLUME));
	}

	/** Whether results go under the configured folder with the input tree reproduced below it. */
	private boolean mirrorsInputTree() {
		return parameter.reproduceInputTree && !parameter.saveToSame && !configuredSaveDir().isEmpty();
	}

	/**
	 * The result folder the user configured, trimmed; empty when none. During a run, the copy
	 * taken at its start ({@link #runSaveDir}), never a per-file folder a processing path has put
	 * in {@code parameter.saveDir} for the length of one call.
	 */
	private String configuredSaveDir() {
		String run = runSaveDir;
		String dir = run != null ? run : parameter.saveDir;
		return dir == null ? "" : dir.trim();
	}

	/**			Whether a discovered path belongs to the result tree this run writes
	 * <p>		The acquisition folder is an ancestor of an output path, not necessarily its
	 * 			immediate parent. Trying every ancestor matters for save-to-same, whose
	 * 			{@code result} folder is itself below the recursively watched tree.
	 */
	private boolean isOwnOutput(File file) {
		if (file == null) return false;
		String configured = configuredSaveDir();
		if (!configured.isEmpty() && sameOrInside(file, new File(configured))) return true;

		/* Walk up only as far as the folder this run was pointed at. Walking to the drive root
		 * asked whether the file sat in <any ancestor>/result, which is true for every raw
		 * volume in an acquisition folder that happens to be called "result": pointing the
		 * watcher at one made the run discover nothing at all, and say nothing about it. */
		/* With no root known - which a started run always has - the walk stays unbounded:
		 * excluding this run's own results matters more than the folder-named-result case. */
		File stop = watchRoot();
		File acquisition = file.getParentFile();
		while (acquisition != null) {
			if (sameOrInside(file, resultFolderOf(acquisition))) return true;
			if (stop != null && sameFolder(acquisition, stop)) break;	// the watched root was the last one to test
			acquisition = acquisition.getParentFile();
		}
		return false;
	}

	/** Whether two paths name the same folder, resolving links and relative segments. */
	private static boolean sameFolder(File a, File b) {
		if (a == null || b == null) return false;
		try {
			return a.getCanonicalFile().equals(b.getCanonicalFile());
		} catch (IOException unresolved) {
			return a.getAbsoluteFile().equals(b.getAbsoluteFile());
		}
	}

	/** The folder this run was pointed at, which bounds how far {@link #isOwnOutput} looks up. */
	private File watchRoot() {
		File explicit = explicitWatchFolder();
		if (explicit != null) return explicit;
		return primaryRoot;
	}

	private static boolean sameOrInside(File file, File directory) {
		if (file == null || directory == null) return false;
		try {
			if (file.getCanonicalFile().equals(directory.getCanonicalFile())) return true;
		} catch (IOException unresolved) {
			if (file.getAbsoluteFile().equals(directory.getAbsoluteFile())) return true;
		}
		return BatchProcessingUtils.isInside(file, directory);
	}


	// ---- discovery ------------------------------------------------------------------

	/** Queue everything on disk that the manifest does not already account for. */
	private void backfill(File folder) {
		List<File> found = new ArrayList<File>();
		collectTiffs(folder, parameter.recursive, parameter.keywords, parameter.excludeKeywords,
				found, 0, new FileExclusion() {
					@Override
					public boolean excludes(File candidate) { return isOwnOutput(candidate); }
				});
		int queued = 0;
		for (File file : found) if (enqueue(file)) queued++;
		if (!found.isEmpty())
			note("backfill " + folder.getAbsolutePath() + ": " + found.size()
					+ " file(s) on disk, " + queued + " queued.");
	}

	static void collectTiffs(File folder, boolean recursive, String keywords, List<File> out, int depth) {
		collectTiffs(folder, recursive, keywords, null, out, depth);
	}

	/**			Gather the TIFFs under a folder that match the name filters
	 * <p>		Static and package-private so the recursion can be tested directly: every
	 * 			acquisition in the sample tree writes into a per-run sub-folder, so a watcher
	 * 			that only looks at the top level silently sees nothing at all - which is
	 * 			exactly what the previous flat listFiles() did.
	 *
	 * @param folder			: folder to search
	 * @param recursive			: whether to descend into sub-folders
	 * @param keywords			: comma separated name filters; empty accepts every TIFF
	 * @param excluded			: comma separated name filters; a match is never picked up
	 * @param out				: collected files are appended here
	 * @param depth				: current recursion depth, to bound pathological trees
	 */
	static void collectTiffs(File folder, boolean recursive, String keywords, String excluded,
			List<File> out, int depth) {
		collectTiffs(folder, recursive, keywords, excluded, out, depth, null);
	}

	/** A discovery-time exclusion, kept small so the recursive walk remains directly testable. */
	interface FileExclusion {
		boolean excludes(File candidate);
	}

	static void collectTiffs(File folder, boolean recursive, String keywords, String excluded,
			List<File> out, int depth, FileExclusion exclusion) {
		if (folder == null || out == null || depth > 8) return;
		File[] children = folder.listFiles();
		if (children == null) return;
		java.util.Arrays.sort(children);
		for (File child : children) {
			if (exclusion != null && exclusion.excludes(child)) continue;
			if (child.isDirectory()) {
				if (recursive) collectTiffs(child, recursive, keywords, excluded,
						out, depth + 1, exclusion);
			} else if (isTiffNamed(child, keywords, excluded)) {
				out.add(child);
			}
		}
	}

	static boolean isTiffNamed(File file, String keywords) {
		return isTiffNamed(file, keywords, null);
	}

	/**			Whether a file is a TIFF this listener should pick up
	 *
	 * @param file				: candidate file
	 * @param keywords			: comma separated name filters; empty or null accepts every TIFF
	 * @param excluded			: comma separated name filters; a match is rejected outright
	 * <p>
	 * @return					: true when the file is a readable TIFF matching the filters
	 */
	static boolean isTiffNamed(File file, String keywords, String excluded) {
		if (file == null || !file.isFile()) return false;
		String name = file.getName().toLowerCase(Locale.ROOT);
		if (!name.endsWith(".tif") && !name.endsWith(".tiff")) return false;
		return BatchProcessingUtils.accepts(file.getName(), keywords, excluded);
	}

	/**			Queue one file unless it is already done or already in flight
	 *
	 * @return					: true if the file was added to the queue
	 */
	private boolean enqueue(File file) {
		if (file == null || !file.isFile() || !isTiff(file) || isOwnOutput(file)) return false;
		observeActivity(file);
		if (failedQueue.containsKey(attemptKey(file))) return false;
		String key = file.getAbsolutePath().toLowerCase(Locale.ROOT);
		// de-duplicate always: ENTRY_MODIFY fires repeatedly while a large file is written,
		// and "overwrite" is about existing results on disk, not about queuing twice
		if (!inFlight.add(key)) return false;
		if (!parameter.overwriteExist && manifest != null
				&& manifest.isDone(file, parameter.outputFormat)) {
			inFlight.remove(key);
			skippedCount++;
			return false;
		}
		offer(file, 0);
		updateStatus();
		return true;
	}

	/** Let a file be queued again, after a failure or an interrupt. */
	private void release(File file) {
		if (file == null) return;
		inFlight.remove(key(file));
		waitingForGroup.remove(key(file));
		readiness.forget(file);
	}

	/** The case-insensitive key a file is tracked by, on a case-insensitive file system. */
	private static String key(File file) {
		return file.getAbsolutePath().toLowerCase(Locale.ROOT);
	}

	/** A retry belongs to the configured timepoint when multiple files feed one result. */
	private String attemptKey(File file) {
		String input = parameter.savesZarr() || channels.combineAcquisitionChannels
				? BatchProcessingUtils.channelGroupKey(file) : file.getAbsolutePath();
		return input.toLowerCase(Locale.ROOT) + "\t" + parameter.outputFormat;
	}

	/** A run may finish TIFF and OME-Zarr independently for the same unchanged input. */
	private String completionKey(File file) {
		return key(file) + "\t" + parameter.outputFormat;
	}

	/** Record a create/modify/announcement as content activity, including same-size writes. */
	private void observeActivity(File file) {
		readiness.observe(file);
	}

	/**
	 * Require one continuous quiet second, then validate the TIFF's metadata and data ranges.
	 *
	 * <p>The 60-second bound is a maximum for one queue turn, not the quiet-period minimum and
	 * not a reason to discard a file. A timeout simply lets other files run before this one is
	 * checked again.
	 */
	private boolean waitUntilStable(File file) throws InterruptedException {
		return readiness.await(file, new TiffReadinessGate.Running() {
			@Override public boolean get() {
				return running;
			}
		}, FILE_POLL_MS, FILE_QUIET_MS, FILE_READINESS_TIMEOUT_MS);
	}

	/* Writing the collected projection movies out as time-lapse TIFFs was removed with the
	 * "combine as time lapse" checkbox that drove it. An OME-Zarr dataset already carries all
	 * six projections per time point, and for a TIFF run the projections written beside the
	 * volume are a few Fiji operations away from a movie. The MIP movies are not collected at
	 * all any more either - see processSingleFile and the note on the sink field. */

	private boolean isMetadata(File file) {
		return file != null && METADATA_NAME.equalsIgnoreCase(file.getName());
	}

	private boolean isTiff(File file) {
		return isTiffNamed(file, parameter.keywords, parameter.excludeKeywords);
	}


	// ---- logging --------------------------------------------------------------------

	/**			Open the run's own log beside the results
	 * <p>		The Fiji Log window is not the place for one line per volume, so every detail
	 * 			goes here and to the console instead. The file is opened even when the output
	 * 			folder is not known yet - it lands in the temporary directory and is moved to
	 * 			the acquisition's own folder by {@link #relocateLog} as soon as one is adopted.
	 */
	private void openLog(File explicit) {
		closeLog();
		File folder = logFolder(explicit);
		try {
			parameter.logPath = folder == null ? "" : folder.getAbsolutePath();
			liveLog = new Log(parameter);
		} catch (Throwable failure) {
			liveLog = null;
			IJ.log("OPM Deskew Live could not open its log file: " + failure.getMessage());
		}
	}

	/** Move the processed record into the acquisition's own output folder once one is known. */
	private void relocateManifest(File adopted) {
		File folder = manifestFolder(adopted);
		File current = manifest == null ? null : manifest.getFile().getParentFile();
		if (current != null && current.equals(folder)) return;
		manifest = new ProcessedManifest(folder);
		note("processed record: " + manifest.getFile().getAbsolutePath()
				+ " (" + manifest.doneCount() + " already recorded)");
	}

	private void relocateLog(File adopted) {
		if (liveLog == null || adopted == null) return;
		File folder = logFolder(adopted);
		if (folder == null) return;
		try {
			liveLog.setPath(folder.getAbsolutePath());
			note("log continues in " + liveLog.getPath());
		} catch (Throwable failure) {
			note("could not move the log to " + folder + ": " + failure.getMessage());
		}
	}

	/**
	 * The folder the acquisition's results are written to, so the log sits with them: the
	 * configured folder, {@code result} beside the data, or the reproduced tree's {@code result}.
	 */
	private File logFolder(File acquisitionFolder) {
		if (acquisitionFolder != null) return resultFolderOf(acquisitionFolder);
		if (!parameter.saveToSame && !configuredSaveDir().isEmpty()) return new File(configuredSaveDir());
		return null;
	}

	private void closeLog() {
		if (liveLog == null) return;
		try { liveLog.close(); } catch (Throwable ignored) { }
		liveLog = null;
	}

	/** One detail line: the log file and the console, never the Fiji Log window. */
	private void note(String message) {
		Log log = liveLog;
		if (log != null) log.add("OPM Deskew Live " + message);
		else System.out.println("OPM Deskew Live " + message);
	}

	private static String stamp(long millis) {
		return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(millis));
	}


	// ---- status ---------------------------------------------------------------------

	/**			Redraw the panel, on the event thread wherever it is called from
	 * <p>		The worker, the watcher and the TCP threads all report progress, and Swing
	 * 			components must only be touched from the event dispatch thread.
	 */
	private void updateStatus() {
		if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
			javax.swing.SwingUtilities.invokeLater(new Runnable() {
				@Override
				public void run() { updateStatus(); }
			});
			return;
		}
		StringBuilder sb = new StringBuilder();
		sb.append(" Live: ").append(running ? "running" : "stopped");
		sb.append("   TCP/IP: ").append(describeTcp(running, parameter.listenTcpIp,
				tcpListening, parameter.port));

		File explicit = explicitWatchFolder();
		boolean monitorAnnounced = parameter.listenTcpIp && parameter.watchAnnouncedFolder;
		File watched = explicit != null ? explicit : monitorAnnounced ? primaryRoot : null;
		boolean monitored = running && (explicit != null || monitorAnnounced);
		String folderLead = " folder (" + (monitored ? "monitored" : "not monitored") + "): ";
		String folderTail = parameter.recursive ? "  (+ sub-folders)" : "";
		sb.append("\n").append(folderLead);
		if (watched != null) sb.append(fit(folderLead, watched.getAbsolutePath(), folderTail));
		else if (monitorAnnounced) sb.append("waiting for an announced file path");
		else if (parameter.listenTcpIp) sb.append("TCP/IP file path only");
		else sb.append(fit(folderLead, parameter.watchDir == null ? "" : parameter.watchDir, folderTail));
		sb.append(folderTail);

		if (metadataSource == MetadataSource.FILE && metadataFile != null) {
			String metadataLead = " metadata: from " + METADATA_NAME + " in ";
			sb.append("\n").append(metadataLead)
					.append(fit(metadataLead, String.valueOf(new File(metadataFile).getParent()), ""));
		} else {
			sb.append("\n metadata: ").append(describeMetadata());
		}
		sb.append("\n parameters: ").append(describeParameters());

		/* One line per end of the run, each naming its folder and the disk under it. Raw data
		 * and results are routinely on different disks - a local acquisition SSD and a network
		 * share - and "which one is filling" is the question the line has to answer. */
		File input = inputRoot();
		String inputTail = "  -  disk " + describeDisk(input);
		sb.append("\n input:  ").append(input != null ? fit(" input:  ", input.getAbsolutePath(), inputTail)
				: running ? "waiting for the first file path" : "set when the first file path arrives");
		sb.append(inputTail);
		File output = outputRoot();
		String outputTail = "  -  disk " + describeDisk(output);
		sb.append("\n output: ").append(output != null ? fit(" output: ", output.getAbsolutePath(), outputTail)
				: "a \"result\" folder beside the data");
		sb.append(outputTail);

		int held = heldForMetadata.size() + heldForLayout.size();
		sb.append("\n queue ").append(fileQueue.size() + held);
		if (held > 0) sb.append(" (").append(held).append(" held)");
		sb.append(", done ").append(processedCount)
				.append("  failed ").append(failedCount)
				.append("  already processed ").append(skippedCount);
		String layout = describeChannelLayout();
		if (layout != null) sb.append("   channels: ").append(layout);
		sb.append("\n processing: ").append(fit(" processing: ", currentFile == null ? "" : currentFile, ""));
		status.setText(sb.toString());
		btnStart.setEnabled(!running);
		btnStop.setEnabled(running);
	}

	/**			A path that fits on its panel line, shortened in the middle when it does not
	 * <p>		The frame is a fixed width, so a long network path ran off its right edge and took
	 * 			the disk figures after it along. Measured with the panel's own font, against its
	 * 			own width, so the cut follows the GUI scale instead of a character count. The log
	 * 			keeps every path whole.
	 *
	 * @param lead				: the text before the path on its line
	 * @param path				: the path
	 * @param tail				: the text after the path on its line
	 * <p>
	 * @return					: the path, or its shortest readable form that fits
	 */
	private String fit(String lead, String path, String tail) {
		if (path == null || path.isEmpty()) return path == null ? "" : path;
		java.awt.Insets insets = status.getInsets();
		int available = status.getWidth() - insets.left - insets.right - STATUS_SLACK_PIXELS;
		if (available <= 0) return path;			// not laid out yet: nothing to measure against
		java.awt.FontMetrics metrics = status.getFontMetrics(status.getFont());
		if (metrics.stringWidth(lead + path + tail) <= available) return path;
		String best = shortenMiddle(path, MIN_PATH_CHARS);
		int low = MIN_PATH_CHARS + 1, high = path.length() - 1;
		while (low <= high) {
			int length = (low + high) >>> 1;
			String candidate = shortenMiddle(path, length);
			if (metrics.stringWidth(lead + candidate + tail) <= available) {
				best = candidate;
				low = length + 1;
			} else {
				high = length - 1;
			}
		}
		return best;
	}

	/**			A path no longer than this many characters, with its middle replaced by "..."
	 * <p>		The two ends are what identify a location: the drive or network share it is on,
	 * 			and the last folders - the acquisition, {@code result}, the file. The middle is
	 * 			what repeats from one acquisition to the next. So the cut keeps the root whole,
	 * 			keeps whole trailing folders first and whole leading folders after that, and
	 * 			only cuts through a name when not even the root and the last name fit.
	 *
	 * @param path				: a Windows, UNC or Unix path
	 * @param maxChars			: the length the result may not exceed
	 * <p>
	 * @return					: the path unchanged when it fits, otherwise its shortened form
	 */
	static String shortenMiddle(String path, int maxChars) {
		final String dots = "...";
		if (path == null || path.length() <= maxChars) return path;
		int max = Math.max(maxChars, dots.length() + 2);
		char separator = path.indexOf('\\') >= 0 ? '\\' : '/';
		int head = rootLength(path, separator);
		int tail = path.lastIndexOf(separator);
		if (tail >= head && tail > 0 && head + dots.length() + (path.length() - tail) <= max) {
			boolean grew = true;
			while (grew) {
				grew = false;
				int previous = path.lastIndexOf(separator, tail - 1);
				if (previous >= head && head + dots.length() + (path.length() - previous) <= max) {
					tail = previous;					// a whole trailing folder more
					grew = true;
					continue;
				}
				int next = path.indexOf(separator, head);
				if (next >= 0 && next + 1 <= tail && next + 1 + dots.length() + (path.length() - tail) <= max) {
					head = next + 1;					// a whole leading folder more
					grew = true;
				}
			}
			if (head < tail) return path.substring(0, head) + dots + path.substring(tail);
		}
		int keep = max - dots.length();
		int front = keep / 2;
		return path.substring(0, front) + dots + path.substring(path.length() - (keep - front));
	}

	/** Characters of a path's root including its separator: {@code E:\}, {@code \\server\share\}, {@code /}. */
	private static int rootLength(String path, char separator) {
		if (path.length() >= 3 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':'
				&& (path.charAt(2) == '\\' || path.charAt(2) == '/')) return 3;
		if (path.startsWith("\\\\") || path.startsWith("//")) {
			int server = path.indexOf(separator, 2);
			int share = server < 0 ? -1 : path.indexOf(separator, server + 1);
			return share < 0 ? 0 : share + 1;
		}
		return path.startsWith("/") ? 1 : 0;
	}

	/**			The geometry in force, or that there is none yet
	 * <p>		Values typed into the setup are only the geometry when "overwrite with manual
	 * 			input" says so. Otherwise they are whatever a previous session left behind, and
	 * 			showing them before this acquisition's parameter file has been read presented a
	 * 			stale geometry as though it were the one about to be applied.
	 */
	/**			What the automatic channel layout is doing, or null when nothing is reading it
	 * <p>		Short enough to sit at the end of the counters line: the panel is eight lines by
	 * 			design, and a ninth would cost the frame a row it does not have.
	 */
	private String describeChannelLayout() {
		if (!channels.autoCombineChannels) return null;
		LiveChannelLayout.Decision decided = channelDecision;
		if (decided == null)
			return running ? "reading the file names (" + channelLayout.observedCount() + ")"
					: "read from the file names";
		if (decided.kind == LiveChannelLayout.Kind.AMBIGUOUS) return "as configured";
		return decided.combines()
				? decided.acquisitionChannels.length + " files per time point, combined"
				: "one file per time point";
	}

	private String describeParameters() {
		boolean known = parameter.manualDeskewParameters || metadataSource != MetadataSource.WAITING;
		if (!known) return "waiting for metadata";
		return "xy = " + IJ.d2s(parameter.xyPixelSize, 1)
				+ " nm, dz = " + IJ.d2s(parameter.zStepSize, 1)
				+ " nm, angle = " + IJ.d2s(parameter.opmAngle, 1) + "°"
				+ (parameter.manualDeskewParameters ? "  (manual)" : "");
	}

	/** The folder raw data is read from: the configured watch folder, or the first one announced. */
	private File inputRoot() {
		File explicit = explicitWatchFolder();
		return explicit != null ? explicit : primaryRoot;
	}

	/**			The root the results are written under, or null while it is not known yet
	 * <p>		With "save to the same (data) folder" it is the {@code result} folder beside the
	 * 			acquisition, which is only known once the acquisition folder is. A reproduced
	 * 			input tree still sits under the configured folder, which is root enough.
	 */
	private File outputRoot() {
		File input = inputRoot();
		/* With the input tree reproduced, the folder this acquisition's results actually go to,
		 * which is what the panel should say, and not only the folder it hangs off. */
		if (mirrorsInputTree() && input != null) return resultFolderOf(input);
		if (!parameter.saveToSame && !configuredSaveDir().isEmpty()) return new File(configuredSaveDir());
		return input == null ? null : new File(input, "result");
	}

	/** Free space and forecast for the disk a folder is on, as far as this run has measured it. */
	private String describeDisk(File folder) {
		if (!running) return "not measured";
		DiskSpace.Report report = folder == null ? null : disk.reportFor(folder);
		return report == null ? "not measured yet" : report.format();
	}

	static String describeTcp(boolean running, boolean configured, boolean listening, int port) {
		if (listening) return "port " + port + " (listening)";
		if (running && configured) return "unavailable (folder-only)";
		if (configured) return "stopped (port " + port + " configured)";
		return "disabled";
	}

	private String describeMetadata() {
		switch (metadataSource) {
		case MANUAL:
			return "from manual input";
		case FILE:
			return "from " + METADATA_NAME + " in "
					+ (metadataFile == null ? "the acquisition folder"
							: String.valueOf(new File(metadataFile).getParent()));
		default:
			return running
					? "waiting for " + METADATA_NAME + " (or for manual input in the setup)"
					: "not loaded yet";
		}
	}

	@Override
	public void close() {
		stopLive();
		Prefs.saveLocation(LOC_KEY, getLocation());
		instance = null;
		super.close();
	}

	/** Files still waiting, for tests and for reporting. */
	int queueSize() {
		return fileQueue.size() + heldForMetadata.size();
	}

	/**			Where the processed manifest lives
	 * <p>		Beside the configured output when there is one, otherwise inside the watched
	 * 			folder, so the record sits with the results it describes. With the input tree
	 * 			reproduced that is the acquisition's own {@code result} folder under it, which a
	 * 			later session over the same acquisition works out identically and so finds.
	 */
	private File manifestFolder (
			File watchFolder
			) {
		if (mirrorsInputTree() && watchFolder != null) return resultFolderOf ( watchFolder );
		if (!parameter.saveToSame && !configuredSaveDir().isEmpty()) return new File ( configuredSaveDir() );
		if (watchFolder != null) return watchFolder;
		return new File ( System.getProperty ( "java.io.tmpdir", "." ) );
	}
}
