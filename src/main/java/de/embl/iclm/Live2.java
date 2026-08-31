package de.embl.iclm;

import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class Live2 extends PlugInFrame {
	private static final long serialVersionUID = 1L;
	private static final String LOC_KEY = "OPMlive2.loc";
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
	private volatile boolean metadataReceived = false;
	private volatile String currentFile = "";
	private volatile int processedCount = 0;
	private volatile int failedCount = 0;

	private WatchService watchService;
	private Thread watchThread;
	private Thread tcpThread;
	private Thread workerThread;
	private ServerSocket serverSocket;
	private final BlockingQueue<File> fileQueue = new LinkedBlockingQueue<File>();
	private final Set<String> queuedOrProcessed = Collections.synchronizedSet(new HashSet<String>());
	private final FastClijDeskew.MipMovieSink movies = new FastClijDeskew.MipMovieSink();

	public Live2() {
		super("OPM Deskew Live2");
		if (instance != null) {
			WindowManager.toFront(instance);
			return;
		}
		instance = this;
		WindowManager.addWindow(this);
		parameter = new Parameter("live2");
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
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(panelColor);

		JPanel content = new JPanel();
		content.setBackground(panelColor);
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

		status.setEditable(false);
		status.setBackground(panelColor);
		status.setMaximumSize(new Dimension(520, 220));
		content.add(status);
		status.setAlignmentX(Component.CENTER_ALIGNMENT);

		JPanel buttons = new JPanel();
		buttons.setBackground(panelColor);
		buttons.add(btnSetup);
		buttons.add(btnStart);
		buttons.add(btnStop);
		buttons.add(btnExit);
		content.add(buttons);
		buttons.setAlignmentX(Component.CENTER_ALIGNMENT);

		btnSetup.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				setup();
			}
		});
		btnStart.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				startLive();
			}
		});
		btnStop.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				stopLive();
			}
		});
		btnExit.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				close();
			}
		});

		add(content);
		setResizable(false);
		pack();
		setSize(560, 210);
		Point loc = Prefs.getLocation(LOC_KEY);
		if (loc != null) setLocation(loc);
		else setLocationRelativeTo(null);
		setVisible(true);
	}

	private void setup() {
		GenericDialogPlus gd = new GenericDialogPlus("OPM Deskew Live2 Setup");
		int length = 35;
		gd.addNumericField("TCP/IP port", parameter.port, 0);
		gd.addDirectoryField("watch folder...", parameter.watchDir, length);
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
		updateStatus();
	}

	private synchronized void startLive() {
		if (running) return;
		File folder = new File(parameter.watchDir);
		if (!folder.exists() || !folder.isDirectory()) {
			IJ.showMessage("OPM Deskew Live2", "Please set a valid watch folder first.");
			return;
		}
		running = true;
		startWorker();
		startWatcher(folder.toPath());
		startTcpListener();
		if (parameter.processOld) enqueueExisting(folder);
		updateStatus();
	}

	private synchronized void stopLive() {
		running = false;
		try {
			if (watchService != null) watchService.close();
		} catch (IOException ignored) { }
		try {
			if (serverSocket != null) serverSocket.close();
		} catch (IOException ignored) { }
		if (workerThread != null) workerThread.interrupt();
		updateStatus();
	}

	private void startWorker() {
		workerThread = new Thread(new Runnable() {
			@Override
			public void run() {
				while (running) {
					try {
						File file = fileQueue.poll(500, TimeUnit.MILLISECONDS);
						if (file == null) continue;
						waitUntilStable(file);
						process(file);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						break;
					}
				}
			}
		}, "OPM-Live2-worker");
		workerThread.start();
	}

	private void startWatcher(final Path folder) {
		watchThread = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					watchService = FileSystems.getDefault().newWatchService();
					folder.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
					while (running) {
						WatchKey key = watchService.take();
						for (WatchEvent<?> event : key.pollEvents()) {
							Path relative = (Path) event.context();
							File file = folder.resolve(relative).toFile();
							if (isTiff(file)) enqueue(file);
							else if (file.getName().equals("ExperimentalParameters.txt")) loadMetadata(file);
						}
						key.reset();
					}
				} catch (ClosedWatchServiceException ignored) {
				} catch (Throwable t) {
					IJ.log("Deskew Live2 folder watcher stopped: " + t.getMessage());
				}
			}
		}, "OPM-Live2-folder-watch");
		watchThread.start();
	}

	private void startTcpListener() {
		tcpThread = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					serverSocket = new ServerSocket(parameter.port);
					while (running) {
						final Socket socket = serverSocket.accept();
						new Thread(new Runnable() {
							@Override
							public void run() {
								handleTcpClient(socket);
							}
						}, "OPM-Live2-tcp-client").start();
					}
				} catch (IOException e) {
					if (running) IJ.log("Deskew Live2 TCP/IP listener stopped: " + e.getMessage());
				}
			}
		}, "OPM-Live2-tcp");
		tcpThread.start();
	}

	private void handleTcpClient(Socket socket) {
		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			String line;
			while (running && (line = reader.readLine()) != null) {
				if (line.toLowerCase().startsWith("stop")) {
					IJ.log("Deskew Live2 STOP signal received.");
					continue;
				}
				File file = new File(line.trim());
				if (file.getName().equals("ExperimentalParameters.txt")) loadMetadata(file);
				else if (isTiff(file)) enqueue(file);
			}
		} catch (IOException e) {
			if (running) IJ.log("Deskew Live2 TCP/IP client error: " + e.getMessage());
		} finally {
			try {
				socket.close();
			} catch (IOException ignored) { }
		}
	}

	private void process(File file) {
		currentFile = file.getAbsolutePath();
		updateStatus();
		try {
			if (!metadataReceived) {
				File metadata = new File(file.getParentFile(), "ExperimentalParameters.txt");
				if (metadata.exists()) loadMetadata(metadata);
			}
			FastClijDeskew.Options options = FastClijDeskew.optionsFromParameter(parameter);
			options.saveDeskewTiff = saveDeskewTiff;
			options.saveDeskewZarr = saveDeskewZarr;
			options.zarrTimepoints = 1;
			options.makeMipMovies = parameter.makeTimeLapse;
			options.displayMipMovies = true;

			if (FastClijDeskew.canUseFastPath(parameter)) {
				try {
					FastClijDeskew.Result result = FastClijDeskew.processFile(file, parameter, options, movies, 0);
					IJ.log("Deskew Live2 timing: " + FastClijDeskew.formatTiming(result));
				} catch (Throwable fastError) {
					IJ.log("Deskew Live2 fast path failed; falling back to original processor: " + fastError.getMessage());
					if (parameter.saveToSame)
						parameter.saveDir = file.getParentFile().getAbsolutePath() + File.separator + "result";
					Deskew.processFile(file.getAbsolutePath(), parameter);
				}
			} else {
				if (parameter.saveToSame)
					parameter.saveDir = file.getParentFile().getAbsolutePath() + File.separator + "result";
				Deskew.processFile(file.getAbsolutePath(), parameter);
			}
			processedCount++;
		} catch (Throwable t) {
			failedCount++;
			IJ.log("Deskew Live2 failed for " + file.getAbsolutePath() + ": " + t.getMessage());
			t.printStackTrace();
		} finally {
			currentFile = "";
			updateStatus();
			System.gc();
		}
	}

	private void loadMetadata(File file) {
		if (!file.exists()) return;
		parameter.deskewmFile = file.getAbsolutePath();
		parameter.parseDeskewParameterLive();
		metadataReceived = parameter.deskewMatrix != null;
		updateStatus();
	}

	private void enqueueExisting(File folder) {
		File[] files = folder.listFiles();
		if (files == null) return;
		for (File file : files)
			if (isTiff(file)) enqueue(file);
	}

	private void enqueue(File file) {
		if (file == null || !file.exists() || !isTiff(file)) return;
		String key = file.getAbsolutePath().toLowerCase();
		if (!parameter.overwriteExist && queuedOrProcessed.contains(key)) return;
		queuedOrProcessed.add(key);
		fileQueue.offer(file);
		updateStatus();
	}

	private void waitUntilStable(File file) throws InterruptedException {
		long previousSize = -1;
		long currentSize = file.length();
		long wait = Math.max(250, parameter.maxWait);
		while (running && currentSize != previousSize) {
			previousSize = currentSize;
			Thread.sleep(wait);
			currentSize = file.length();
		}
	}

	private boolean isTiff(File file) {
		if (file == null || !file.isFile()) return false;
		String name = file.getName().toLowerCase();
		if (!name.endsWith(".tif") && !name.endsWith(".tiff")) return false;
		if (parameter.keywords == null || parameter.keywords.trim().isEmpty()) return true;
		String[] parts = parameter.keywords.split(",");
		for (String part : parts) {
			String keyword = part.trim();
			if (keyword.length() > 0 && file.getName().contains(keyword)) return true;
		}
		return false;
	}

	private void updateStatus() {
		StringBuilder sb = new StringBuilder();
		sb.append(" Live2: ").append(running ? "running" : "stopped");
		sb.append("\n folder: ").append(parameter.watchDir == null ? "" : parameter.watchDir);
		sb.append("\n TCP/IP port: ").append(parameter.port);
		sb.append("\n metadata: ").append(metadataReceived ? "received" : "using setup values");
		sb.append("\n parameters: xy=").append(IJ.d2s(parameter.xyPixelSize, 1))
				.append(" nm, dz=").append(IJ.d2s(parameter.zStepSize, 1))
				.append(" nm, angle=").append(IJ.d2s(parameter.opmAngle, 1));
		sb.append("\n queue/processed/failed: ").append(fileQueue.size()).append("/")
				.append(processedCount).append("/").append(failedCount);
		sb.append("\n processing: ").append(currentFile == null ? "" : currentFile);
		status.setText(sb.toString());
	}

	@Override
	public void close() {
		stopLive();
		Prefs.saveLocation(LOC_KEY, getLocation());
		instance = null;
		super.close();
	}
}
