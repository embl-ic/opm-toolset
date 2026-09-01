package de.embl.iclm;

import fiji.util.gui.GenericDialogPlus;
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
import java.awt.Dimension;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Live OPM processing driven by a TCP/IP listener and a folder watcher at the same time.
 *
 * <p>The acquisition announces files over TCP when it can; the folder watcher is the safety
 * net for when it cannot, or is not running yet. Both feed one queue keyed by canonical
 * path, so a file announced twice is processed once, and a single worker drains it.
 *
 * <p>Three situations that used to be three different problems are one mechanism here:
 * resuming after an interruption, retrying a volume that failed, and joining an acquisition
 * that is already under way. All three are answered by {@link ProcessedManifest}: enumerate
 * what is on disk, ask the manifest what is missing, queue the difference.
 *
 * <p>Failure handling is deliberate rather than incidental. The TCP port is bound before the
 * window claims to be running, so a port clash is refused instead of leaving the watcher
 * running while TCP is silently dead. Files are marked done only <em>after</em> they
 * succeed, so a transient error costs a retry and not a missing timepoint. The stability
 * wait is bounded, so a file that keeps growing goes back to the end of the queue instead of
 * blocking every other file behind it. Metadata is re-read when the acquisition folder
 * changes, so a second acquisition cannot inherit the first one's geometry.
 */
public class Live2 extends PlugInFrame {
	private static final long serialVersionUID = 1L;
	private static final String LOC_KEY = "OPMlive2.loc";
	/** Longest total time to wait for a file to stop growing before requeuing it. */
	private static final long STABILITY_TIMEOUT_MS = 60000;
	/** Upper bound on MIP movie frames held in memory, so a long run cannot exhaust the heap. */
	private static final int MAX_MOVIE_FRAMES = 2000;
	private static Live2 instance;

	private final Color panelColor = new Color(204, 229, 255);
	private final JTextArea status = new JTextArea();
	private final JButton btnSetup = new JButton("setup");
	private final JButton btnStart = new JButton("start");
	private final JButton btnStop = new JButton("stop");
	private final JButton btnExit = new JButton("exit");

	private Parameter parameter;
	private boolean saveDeskewTiff = true;
	private boolean saveDeskewZarr = false;
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

	private final BlockingQueue<File> fileQueue = new LinkedBlockingQueue<File>();
	/** Files queued or in flight this session; entries are removed again when one fails. */
	private final Set<String> inFlight = Collections.synchronizedSet(new HashSet<String>());
	/** Directories currently registered with the watch service, so subfolders can be added. */
	private final Map<WatchKey, Path> watchedDirs = Collections.synchronizedMap(new HashMap<WatchKey, Path>());
	/** Open client sockets, so stopping actually closes them instead of leaking threads. */
	private final Set<Socket> clients = Collections.synchronizedSet(new HashSet<Socket>());
	private final FastClijDeskew.MipMovieSink movies = new FastClijDeskew.MipMovieSink();
	/** Multi-channel selection, shared with Channel Operation and Deskew Batch. */
	private final ChannelOperationSettings channels = new ChannelOperationSettings();
	/** Acquisition folder whose ExperimentalParameters.txt is currently loaded. */
	private String metadataFolder = null;
	private final Object metadataLock = new Object();

	public Live2() {
		super("OPM Deskew Live");
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
		parameter.channelStr = "whole image";
		parameter.projX = true;
		parameter.projY = true;
		parameter.projZ = true;
		parameter.maxProj = true;
		parameter.avgProj = false;
		parameter.makeTimeLapse = true;
		parameter.saveToSame = true;
		parameter.saveDeskewImage = true;
		parameter.saveSeparate = true;
		saveDeskewTiff = parameter.saveDeskewImage;
	}

	private void buildFrame() {
		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(panelColor);

		status.setEditable(false);
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

		add(content);
		setResizable(false);
		pack();
		setSize(560, 230);
		Point loc = Prefs.getLocation(LOC_KEY);
		if (loc != null) setLocation(loc);
		else setLocationRelativeTo(null);
		setVisible(true);
	}

	private void setup() {
		if (running) {
			// changing the folder or port under the running threads would leave the panel
			// describing something the listener is not actually doing
			IJ.showMessage("OPM Deskew Live", "Stop the listener before changing the setup.");
			return;
		}
		GenericDialogPlus gd = new GenericDialogPlus("OPM Deskew Live Setup");
		int length = 35;
		gd.addNumericField("TCP/IP port", parameter.port, 0);
		gd.addDirectoryField("watch folder...", parameter.watchDir, length);
		gd.addCheckbox("include sub-folders", parameter.recursive);
		gd.addStringField("file name contains(separate multiple by \",\")", parameter.keywords, length);
		gd.addNumericField("XY pixel size", parameter.xyPixelSize, 1, 5, "nm");
		gd.addNumericField("Z step size", parameter.zStepSize, 1, 5, "nm");
		gd.addNumericField("OPM angle", parameter.opmAngle, 1, 5, "degree");
		gd.addChoice("channel option", new String[] {
				"whole image", "fold by midline", "align with SIFT",
				"only left", "only right", "left & right separately" }, parameter.channelStr);
		gd.addFileField("align matrix", parameter.alignmFile, length);
		gd.addMessage("create projection image(s):");
		gd.addCheckboxGroup(1, 3, new String[] { "along_X      ", "along_Y      ", "along_Z      " },
				new boolean[] { parameter.projX, parameter.projY, parameter.projZ });
		gd.addCheckboxGroup(1, 2, new String[] { "maximum", "mean" },
				new boolean[] { parameter.maxProj, parameter.avgProj });
		gd.addCheckbox("combine as time lapse", parameter.makeTimeLapse);
		channels.addToDialog(gd);
		gd.addDirectoryField("save to...", parameter.saveDir, length);
		gd.addCheckbox("save result to the same (data) folder", parameter.saveToSame);
		gd.addCheckbox("save deskew image as TIFF stack", saveDeskewTiff);
		gd.addCheckbox("save deskew image as OME-Zarr", saveDeskewZarr);
		gd.addCheckbox("separate results to sub-folders", parameter.saveSeparate);
		gd.addMessage("existing files in same folder:");
		gd.addCheckbox("also process existing files", parameter.processOld);
		gd.addChoice("if result already exists", new String[] { "skip", "overwrite" }, parameter.fileExistStr);
		gd.addNumericField("max file writing delay", parameter.maxWait, 0, 5, "millisecond");
		gd.showDialog();
		if (gd.wasCanceled()) return;

		parameter.port = (int) gd.getNextNumber();
		parameter.watchDir = gd.getNextString();
		parameter.recursive = gd.getNextBoolean();
		parameter.keywords = gd.getNextString();
		parameter.xyPixelSize = gd.getNextNumber();
		parameter.zStepSize = gd.getNextNumber();
		parameter.opmAngle = gd.getNextNumber();
		parameter.channelStr = gd.getNextChoice();
		parameter.alignmFile = gd.getNextString();
		parameter.projX = gd.getNextBoolean();
		parameter.projY = gd.getNextBoolean();
		parameter.projZ = gd.getNextBoolean();
		parameter.maxProj = gd.getNextBoolean();
		parameter.avgProj = gd.getNextBoolean();
		parameter.makeTimeLapse = gd.getNextBoolean();
		channels.readFrom(gd);
		parameter.saveDir = gd.getNextString();
		parameter.saveToSame = gd.getNextBoolean();
		saveDeskewTiff = gd.getNextBoolean();
		parameter.saveDeskewImage = saveDeskewTiff;
		saveDeskewZarr = gd.getNextBoolean();
		parameter.saveSeparate = gd.getNextBoolean();
		parameter.processOld = gd.getNextBoolean();
		parameter.fileExistStr = gd.getNextChoice();
		parameter.overwriteExist = parameter.fileExistStr.equals("overwrite");
		parameter.maxWait = Math.max(0, (int) gd.getNextNumber());
		parameter.parseProjectionParameter();
		parameter.parseAlignParameter();
		parameter.storeParam();
		channels.store();
		updateStatus();
	}

	private synchronized void startLive() {
		if (running) return;
		File folder = new File(parameter.watchDir == null ? "" : parameter.watchDir);
		if (!folder.isDirectory()) {
			IJ.showMessage("OPM Deskew Live", "Please set a valid watch folder first.");
			return;
		}
		if (workerThread != null && workerThread.isAlive()) {
			// the previous run is still finishing a volume; a second worker would drive
			// CLIJ from two threads at once
			IJ.showMessage("OPM Deskew Live", "The previous run is still finishing. Try again in a moment.");
			return;
		}

		// bind the port up front: a clash must refuse the start, not leave TCP silently dead
		try {
			serverSocket = new ServerSocket(parameter.port);
			tcpListening = true;
		} catch (IOException e) {
			serverSocket = null;
			tcpListening = false;
			IJ.showMessage("OPM Deskew Live",
					"Could not listen on port " + parameter.port + ":\n" + e.getMessage()
					+ "\n\nAnother listener may already be using it. Nothing has been started.");
			updateStatus();
			return;
		}

		manifest = new ProcessedManifest(manifestFolder(folder));
		fileQueue.clear();
		inFlight.clear();
		running = true;
		startWorker();
		startWatcher(folder.toPath());
		startTcpListener();
		if (parameter.processOld) backfill(folder);
		IJ.log("OPM Deskew Live started. Watching " + folder.getAbsolutePath()
				+ (parameter.recursive ? " (with sub-folders)" : "")
				+ ", TCP port " + parameter.port
				+ ", manifest " + manifest.getFile().getAbsolutePath()
				+ " (" + manifest.doneCount() + " already recorded)");
		updateStatus();
	}

	private synchronized void stopLive() {
		if (!running && workerThread == null) return;
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
				IJ.log("OPM Deskew Live: still finishing a volume; it will stop when that completes.");
			else
				workerThread = null;
		}
		watchThread = null;
		tcpThread = null;
		serverSocket = null;
		watchService = null;

		saveMovies();
		updateStatus();
	}

	private void startWorker() {
		workerThread = new Thread(new Runnable() {
			@Override
			public void run() {
				while (running) {
					File file = null;
					try {
						file = fileQueue.poll(500, TimeUnit.MILLISECONDS);
						if (file == null) continue;
						if (!waitUntilStable(file)) {
							// still being written: let other files through and try again later
							fileQueue.offer(file);
							Thread.sleep(250);
							continue;
						}
						process(file);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						if (file != null) release(file);
						break;
					} catch (Throwable t) {
						failedCount++;
						if (file != null) {
							if (manifest != null) manifest.markFailed(file);
							release(file);				// allow a retry rather than losing it
						}
						IJ.log("OPM Deskew Live worker error: " + t);
					}
				}
			}
		}, "OPM-Live-worker");
		workerThread.start();
	}

	private void startWatcher(final Path root) {
		watchThread = new Thread(new Runnable() {
			@Override
			public void run() {
				WatchService service = null;
				try {
					service = FileSystems.getDefault().newWatchService();
					watchService = service;
					registerTree(service, root);
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
					IJ.log("OPM Deskew Live folder watcher stopped: " + t);
				}
			}
		}, "OPM-Live-folder-watch");
		watchThread.start();
	}

	/** Register a folder and, when recursive watching is on, everything under it. */
	private void registerTree(WatchService service, Path root) {
		Deque<Path> pending = new ArrayDeque<Path>();
		pending.push(root);
		while (!pending.isEmpty()) {
			Path dir = pending.pop();
			try {
				WatchKey key = dir.register(service,
						StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
				watchedDirs.put(key, dir);
			} catch (IOException e) {
				IJ.log("OPM Deskew Live could not watch " + dir + ": " + e.getMessage());
				continue;
			}
			if (!parameter.recursive) return;
			File[] children = dir.toFile().listFiles();
			if (children == null) continue;
			for (File child : children) if (child.isDirectory()) pending.push(child.toPath());
		}
	}

	private void startTcpListener() {
		tcpThread = new Thread(new Runnable() {
			@Override
			public void run() {
				ServerSocket server = serverSocket;			// bound in startLive
				try {
					while (running && server != null && !server.isClosed()) {
						final Socket socket = server.accept();
						clients.add(socket);
						new Thread(new Runnable() {
							@Override
							public void run() { handleTcpClient(socket); }
						}, "OPM-Live-tcp-client").start();
					}
				} catch (IOException e) {
					if (running) {
						tcpListening = false;
						IJ.log("OPM Deskew Live TCP/IP listener stopped: " + e.getMessage());
						updateStatus();
					}
				}
			}
		}, "OPM-Live-tcp");
		tcpThread.start();
	}

	private void handleTcpClient(Socket socket) {
		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			String line;
			while (running && (line = reader.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty()) continue;
				if (line.toLowerCase(Locale.ROOT).startsWith("stop")) {
					IJ.log("OPM Deskew Live: STOP received over TCP/IP.");
					continue;
				}
				File file = new File(line);
				if (isMetadata(file)) loadMetadata(file);
				else if (isTiff(file)) enqueue(file);
				else IJ.log("OPM Deskew Live ignored an announced path it cannot use: " + line);
			}
		} catch (IOException e) {
			if (running) IJ.log("OPM Deskew Live TCP/IP client error: " + e.getMessage());
		} finally {
			clients.remove(socket);
			try { socket.close(); } catch (IOException ignored) { }
		}
	}

	private void process(File file) {
		currentFile = file.getAbsolutePath();
		updateStatus();
		boolean ok = false;
		try {
			// a new acquisition folder means new geometry; never inherit the previous one's
			ensureMetadataFor(file);

			if (channels.combineAcquisitionChannels) {
				ok = processChannelGroup(file);
				if (ok) processedCount++;
				return;
			}

			FastClijDeskew.Options options = FastClijDeskew.optionsFromParameter(parameter);
			options.saveDeskewTiff = saveDeskewTiff;
			options.saveDeskewZarr = saveDeskewZarr;
			options.zarrTimepoints = 1;
			options.makeMipMovies = parameter.makeTimeLapse && movies.getFrameCount() < MAX_MOVIE_FRAMES;
			options.displayMipMovies = true;

			if (FastClijDeskew.canUseFastPath(parameter)) {
				try {
					FastClijDeskew.Result result = FastClijDeskew.processFile(file, parameter, options, movies, 0);
					IJ.log("OPM Deskew Live timing: " + FastClijDeskew.formatTiming(result));
				} catch (Throwable fastError) {
					IJ.log("OPM Deskew Live fast path failed, falling back: " + fastError.getMessage());
					processWithFallback(file);
				}
			} else {
				processWithFallback(file);
			}
			ok = true;
			processedCount++;
		} catch (Throwable t) {
			failedCount++;
			IJ.log("OPM Deskew Live failed for " + file.getAbsolutePath() + ": " + t);
		} finally {
			// mark done only on success, so a transient failure costs a retry not a timepoint
			if (manifest != null) {
				if (ok) manifest.markDone(file);
				else manifest.markFailed(file);
			}
			if (!ok) release(file);
			currentFile = "";
			updateStatus();
		}
	}

	/**			Deskew one timepoint's acquisition channels together
	 * <p>		A file arriving on its own is not enough when channels are combined: the other
	 * 			{@code _ChannelNNNN} files of the same timepoint have to be on disk too. Until
	 * 			they are, the file goes back on the queue rather than being processed alone or
	 * 			marked done - the acquisition writes them seconds apart, and a half-built
	 * 			group must not become a one-channel result.
	 *
	 * @param file				: the file that arrived
	 * <p>
	 * @return					: true when the whole group was processed
	 */
	private boolean processChannelGroup(File file) {
		List<File> group = siblingsOf(file);
		int[] wanted = requiredAcquisitionChannels();
		for (int channel : wanted) {
			if (findAcquisitionChannel(group, channel) == null) {
				// the rest of the timepoint has not landed yet; wait for it
				fileQueue.offer(file);
				return false;
			}
		}
		for (File member : group) {
			if (!waitUntilStableQuietly(member)) { fileQueue.offer(file); return false; }
		}

		String outputName = BatchProcessingUtils.channelGroupOutputName(group) + "-deskewed";
		ImagePlus combined = null;
		try {
			combined = MultiChannelDeskew.deskewGroup(group, parameter, channels, outputName);
			if (combined == null) return false;
			String savedDir = parameter.saveDir;
			try {
				if (parameter.saveToSame)
					parameter.saveDir = file.getParentFile().getAbsolutePath() + File.separator + "result";
				parameter.impInput = null;
				Deskew.prepareResults(new ImagePlus[] { combined }, parameter);
			} finally {
				parameter.saveDir = savedDir;
			}
			// the whole group is done, so none of its files should come round again
			if (manifest != null) for (File member : group) manifest.markDone(member);
			for (File member : group) inFlight.add(member.getAbsolutePath().toLowerCase(Locale.ROOT));
			IJ.log("OPM Deskew Live combined " + group.size() + " acquisition channel(s) into " + outputName);
			return true;
		} finally {
			if (combined != null) { combined.changes = false; combined.close(); }
			Utils.collectGarbage();
		}
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

	/** The acquisition channel numbers the selected output sources actually need. */
	private int[] requiredAcquisitionChannels() {
		Set<Integer> wanted = new java.util.TreeSet<Integer>();
		for (String source : channels.channelOrder) {
			if (BatchChannelOperation.SKIP_CHANNEL.equals(source)) continue;
			int underscore = source.indexOf("_Channel");
			if (underscore < 0) continue;
			try {
				wanted.add(Integer.valueOf(source.substring(underscore + 8, underscore + 12)));
			} catch (Exception ignored) {
				// an unparsable label simply imposes no requirement
			}
		}
		int[] out = new int[wanted.size()];
		int i = 0;
		for (Integer value : wanted) out[i++] = value.intValue();
		return out;
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


	private void processWithFallback(File file) {
		String savedDir = parameter.saveDir;
		try {
			if (parameter.saveToSame)
				parameter.saveDir = file.getParentFile().getAbsolutePath() + File.separator + "result";
			Deskew.processFile(file.getAbsolutePath(), parameter);
		} finally {
			parameter.saveDir = savedDir;		// do not let one file's folder leak into the next
		}
	}

	/** Load ExperimentalParameters.txt for this file's folder if it is not already loaded. */
	private void ensureMetadataFor(File file) {
		File folder = file.getParentFile();
		if (folder == null) return;
		String key = folder.getAbsolutePath();
		synchronized (metadataLock) {
			if (key.equals(metadataFolder)) return;
			File metadata = new File(folder, "ExperimentalParameters.txt");
			if (metadata.isFile()) {
				applyMetadata(metadata);
				metadataFolder = key;
			} else if (metadataFolder == null) {
				IJ.log("OPM Deskew Live: no ExperimentalParameters.txt beside " + file.getName()
						+ "; using the values from setup.");
				metadataFolder = key;		// only warn once per folder
			}
		}
	}

	private void loadMetadata(File file) {
		if (file == null || !file.isFile()) return;
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
		IJ.log(String.format(Locale.US,
				"OPM Deskew Live read %s: xy=%.1f nm, dz=%.1f nm, angle=%.1f deg",
				file.getAbsolutePath(), parameter.xyPixelSize, parameter.zStepSize, parameter.opmAngle));
		updateStatus();
	}

	/** Queue everything on disk that the manifest does not already account for. */
	private void backfill(File folder) {
		List<File> found = new ArrayList<File>();
		collect(folder, found, 0);
		int queued = 0;
		for (File file : found) if (enqueue(file)) queued++;
		if (!found.isEmpty())
			IJ.log("OPM Deskew Live backfill: " + found.size() + " file(s) on disk, " + queued + " queued.");
	}

	private void collect(File folder, List<File> out, int depth) {
		collectTiffs(folder, parameter.recursive, parameter.keywords, out, depth);
	}

	/**			Gather the TIFFs under a folder that match the keyword filter
	 * <p>		Static and package-private so the recursion can be tested directly: every
	 * 			acquisition in the sample tree writes into a per-run sub-folder, so a watcher
	 * 			that only looks at the top level silently sees nothing at all - which is
	 * 			exactly what the previous flat listFiles() did.
	 *
	 * @param folder			: folder to search
	 * @param recursive			: whether to descend into sub-folders
	 * @param keywords			: comma separated name filters; empty accepts every TIFF
	 * @param out				: collected files are appended here
	 * @param depth				: current recursion depth, to bound pathological trees
	 */
	static void collectTiffs(File folder, boolean recursive, String keywords, List<File> out, int depth) {
		if (folder == null || out == null || depth > 8) return;
		File[] children = folder.listFiles();
		if (children == null) return;
		for (File child : children) {
			if (child.isDirectory()) {
				if (recursive) collectTiffs(child, recursive, keywords, out, depth + 1);
			} else if (isTiffNamed(child, keywords)) {
				out.add(child);
			}
		}
	}

	/**			Whether a file is a TIFF this listener should pick up
	 *
	 * @param file				: candidate file
	 * @param keywords			: comma separated name filters; empty or null accepts every TIFF
	 * <p>
	 * @return					: true when the file is a readable TIFF matching the filter
	 */
	static boolean isTiffNamed(File file, String keywords) {
		if (file == null || !file.isFile()) return false;
		String name = file.getName().toLowerCase(Locale.ROOT);
		if (!name.endsWith(".tif") && !name.endsWith(".tiff")) return false;
		if (keywords == null || keywords.trim().isEmpty()) return true;
		boolean hasKeyword = false;
		for (String part : keywords.split(",")) {
			String keyword = part.trim();
			if (keyword.isEmpty()) continue;
			hasKeyword = true;
			if (file.getName().contains(keyword)) return true;
		}
		// a field holding only separators is not a filter that excludes everything;
		// silently queuing nothing is the failure mode this listener is meant to avoid
		return !hasKeyword;
	}

	/**			Queue one file unless it is already done or already in flight
	 *
	 * @return					: true if the file was added to the queue
	 */
	private boolean enqueue(File file) {
		if (file == null || !file.isFile() || !isTiff(file)) return false;
		String key = file.getAbsolutePath().toLowerCase(Locale.ROOT);
		// de-duplicate always: ENTRY_MODIFY fires repeatedly while a large file is written,
		// and "overwrite" is about existing results on disk, not about queuing twice
		if (!inFlight.add(key)) return false;
		if (!parameter.overwriteExist && manifest != null && manifest.isDone(file)) {
			inFlight.remove(key);
			skippedCount++;
			return false;
		}
		fileQueue.offer(file);
		updateStatus();
		return true;
	}

	/** Let a file be queued again, after a failure or an interrupt. */
	private void release(File file) {
		if (file != null) inFlight.remove(file.getAbsolutePath().toLowerCase(Locale.ROOT));
	}

	/**			Wait for a file to stop growing, but not for ever
	 * <p>		A file that is still being written after the timeout goes back on the queue
	 * 			rather than holding the single worker - otherwise one long write stalls every
	 * 			other volume behind it.
	 *
	 * @return					: true when the size settled, false when it timed out
	 */
	private boolean waitUntilStable(File file) throws InterruptedException {
		long poll = Math.max(250, parameter.maxWait);
		long deadline = System.currentTimeMillis() + STABILITY_TIMEOUT_MS;
		long previousSize = -1;
		long currentSize = file.length();
		while (running && currentSize != previousSize) {
			if (System.currentTimeMillis() > deadline) return false;
			previousSize = currentSize;
			Thread.sleep(poll);
			currentSize = file.length();
		}
		return running;
	}

	private void saveMovies() {
		if (!parameter.makeTimeLapse || movies.getFrameCount() == 0) return;
		try {
			File root = new File(parameter.watchDir);
			File folder = parameter.saveToSame || parameter.saveDir == null || parameter.saveDir.trim().isEmpty()
					? new File(root, "result") : new File(parameter.saveDir);
			if (parameter.saveSeparate) folder = new File(folder, "MIP_movies");
			movies.save(folder);
			IJ.log("OPM Deskew Live saved " + movies.getFrameCount()
					+ " time-lapse frame(s) to " + folder.getAbsolutePath());
		} catch (Throwable t) {
			IJ.log("OPM Deskew Live could not save the time-lapse movies: " + t.getMessage());
		}
	}

	private boolean isMetadata(File file) {
		return file != null && "ExperimentalParameters.txt".equalsIgnoreCase(file.getName());
	}

	private boolean isTiff(File file) {
		return isTiffNamed(file, parameter.keywords);
	}

	private void updateStatus() {
		StringBuilder sb = new StringBuilder();
		sb.append(" Live: ").append(running ? "running" : "stopped");
		sb.append("   TCP/IP port ").append(parameter.port)
				.append(running ? (tcpListening ? " (listening)" : " (NOT listening)") : "");
		sb.append("\n folder: ").append(parameter.watchDir == null ? "" : parameter.watchDir);
		if (parameter.recursive) sb.append("  (+ sub-folders)");
		sb.append("\n metadata: ").append(metadataFolder == null ? "using setup values" : metadataFolder);
		sb.append("\n parameters: xy=").append(IJ.d2s(parameter.xyPixelSize, 1))
				.append(" nm, dz=").append(IJ.d2s(parameter.zStepSize, 1))
				.append(" nm, angle=").append(IJ.d2s(parameter.opmAngle, 1));
		sb.append("\n queue ").append(fileQueue.size())
				.append("  done ").append(processedCount)
				.append("  failed ").append(failedCount)
				.append("  already processed ").append(skippedCount);
		sb.append("\n processing: ").append(currentFile == null ? "" : currentFile);
		status.setText(sb.toString());
		btnSetup.setEnabled(!running);
		btnStart.setEnabled(!running);
		btnStop.setEnabled(running);
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
		return fileQueue.size();
	}

	/**			Where the processed manifest lives
	 * <p>		Beside the configured output when there is one, otherwise inside the watched
	 * 			folder, so the record sits with the results it describes.
	 */
	private File manifestFolder (
			File watchFolder
			) {
		if (!parameter.saveToSame && parameter.saveDir != null && !parameter.saveDir.trim().isEmpty())
			return new File ( parameter.saveDir );
		return watchFolder;
	}
}
