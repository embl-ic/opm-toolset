package de.embl.iclm;

import java.awt.*;
import java.awt.event.*;


import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;

import ij.plugin.frame.PlugInFrame;

import ij.*;
import ij.gui.*;


public class FolderWatcher2 extends PlugInFrame {
	
	private FolderWatcher folderWatcher;
	private static FolderWatcher2 instance;
	private Log log;
	
	private static final long serialVersionUID = 1L;
    private final String LOC_KEY = "OPMwatcher.loc";
    private String statusString = "";
 
    
    private final Color panelColor = Parameter.frameColor;
    private final Dimension textAreaMax = new Dimension(400, 300);
    private final Dimension panelMax = new Dimension(500, 200);
    private final Dimension panelMin = new Dimension(380, 100);
	
    private final JTextArea watcherStatus = new JTextArea();
    private final JButton btnChooseFolder = new JButton("choose folder");
    private final JButton btnSetup = new JButton("setup processing");
    private final JToggleButton btnToggleWatch = new JToggleButton("start watcher");
    private final JButton btnExit = new JButton("exit");
	
    
    private Parameter parameter = null;
    private String metadataName = "ExperimentalParameters.txt";
    private boolean metadata_received = false;
    private String[] extensions = {"tif", "tiff"};
    private String[] keywords = new String[0];
    
 // Constructor
    public FolderWatcher2() {
    	super("OPM Folder Watcher"); // Call to super class constructor
    	if ( null != instance ) {
            WindowManager.toFront(instance);
            return;
        }
        instance = this;
        
        folderWatcher = FolderWatcher.getInstance();
        
        WindowManager.addWindow(this);
        setLayout(new BoxLayout(instance, BoxLayout.Y_AXIS));
        setBackground(panelColor);
        parameter = new Parameter("watcher");
        log = new Log("OPM_watcher.log");
        log.add("OPM folder watch starts:");
        IJ.log("OPM folder watch starts:");
        
        // content panel:  Text Area + Buttons
        JPanel contentPanel = new JPanel();
		contentPanel.setBackground(panelColor);
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));

		// watcher status as Text Area
        watcherStatus.setText(statusString);
        watcherStatus.setMaximumSize(textAreaMax);
        watcherStatus.setEditable(false);
        watcherStatus.setBackground(panelColor);
        contentPanel.add(watcherStatus);
		watcherStatus.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        // buttons implementing folder watcher setup and controller
        JPanel buttonPanel = new JPanel();
		buttonPanel.add(btnChooseFolder);
		buttonPanel.add(btnSetup);
		buttonPanel.add(btnToggleWatch);
		buttonPanel.add(btnExit);
		buttonPanel.setBackground(panelColor);
		buttonPanel.setMaximumSize(panelMax);
		buttonPanel.setMinimumSize(panelMin);
		contentPanel.add(buttonPanel);
		buttonPanel.setAlignmentX(Component.CENTER_ALIGNMENT);

		// configure refresh button
		btnChooseFolder.addActionListener(new ActionListener() { 
		  @Override 
		  public void actionPerformed(ActionEvent ae) { setup_folder(); }
		});
		// configure refresh button
		btnSetup.addActionListener(new ActionListener() { 
		  @Override 
		  public void actionPerformed(ActionEvent ae) { setup_processing(); }
		});
		// configure refresh button
		btnToggleWatch.addActionListener(new ActionListener() { 
		  @Override 
		  public void actionPerformed(ActionEvent ae) { toggleWatch(); }
		});
		// configure refresh button
		btnExit.addActionListener(new ActionListener() { 
		  @Override 
		  public void actionPerformed(ActionEvent ae) { close(); }
		});
		
		
		// add content panel to plugin frame, and configure appearance
		contentPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
		add(contentPanel);
        updateWatchStatus();
        setResizable(false);
        pack();
        setSize(450, 150);
        setVisible(true);
        Point loc = Prefs.getLocation(LOC_KEY);
        if (loc!=null)
            setLocation(loc);
        else
            GUI.centerOnImageJScreen(this);

    }

    //set up folder being watched
    public void setup_folder () {
    	
    }
    
    //set up folder being watched
    public void setup_processing () {
    	
    }
    
    //set up folder being watched
    public void toggleWatch () {
    	folderWatcher.running = !folderWatcher.running; // start/stop watching
    	if (folderWatcher.running) {
    		try {
                folderWatcher.startWatching(); // Start watching the folder
                btnToggleWatch.setText("stop listening");
        		btnToggleWatch.setSelected(true);  
            } catch (IOException ex) {
                ex.printStackTrace(); // Handle exceptions as appropriate
                btnToggleWatch.setText("start listening");
        		btnToggleWatch.setSelected(false);
            }

    	} else {
    		try {
                folderWatcher.stopWatching(); // Stop watching
            } catch (IOException ex) {
                ex.printStackTrace(); // Handle exceptions as appropriate
            }
    		btnToggleWatch.setText("start listening");
    		btnToggleWatch.setSelected(false);
    	}
    	updateWatchStatus();	
    }
    
    //set up folder being watched
    public void close () {
    	super.close();
        instance = null;
        Prefs.saveLocation(LOC_KEY, getLocation());
        folderWatcher.running = false;
        IJ.log("OPM folder watch closed.");
    }
    
    //set up folder being watched
    public void updateWatchStatus () {
    	/*
    	 *  watcher: running (stopped)
    	 *  folder path:
    	 *  file info: 0 new file / 199 files (processed)
    	 *  processing: file
    	 */
    	statusString = " watcher: " + (folderWatcher.running ? "running" : "stopped");
	statusString += "\n folder: " + parameter.watchDir;
	statusString += "\n file info: "
			+ folderWatcher.fileQueue.size() + " queued file(s)";
	watcherStatus.setText(statusString);
    }
    
    
    
    public static class FolderWatcher  { // Replace with your superclass
        private static FolderWatcher instance;
        private Path folderPath;
        private final ConcurrentLinkedQueue<Path> fileQueue;
        private final ScheduledExecutorService executorService;
        private WatchService watchService;
        private volatile boolean running;

        // Constructor
        public FolderWatcher() {
            super(); // Call to super class constructor
            this.fileQueue = new ConcurrentLinkedQueue<>();
            this.executorService = Executors.newScheduledThreadPool(1, Shutdown.daemonThreads("OPM-watch2"));
            this.running = false;
        }

        // Method to get the singleton instance
        public static synchronized FolderWatcher getInstance() {
            if (instance == null) {
                instance = new FolderWatcher();
            }
            return instance;
        }

        public void setFolderPath(String path) {
            this.folderPath = Paths.get(path);
        }

        public void startWatching() throws IOException {
            if (folderPath == null || !Files.isDirectory(folderPath)) {
                throw new IllegalArgumentException("Invalid folder path.");
            }

            if (running) {
                // Optionally, bring the UI to the front if it's already running
                bringUIToFront();
                return; // Exit if already running
            }

            watchService = FileSystems.getDefault().newWatchService();
            folderPath.register(watchService, 
                                StandardWatchEventKinds.ENTRY_CREATE, 
                                StandardWatchEventKinds.ENTRY_MODIFY);

            running = true;

            Shutdown.daemon(new FileProcessor(), "OPM-watch2-processor").start();

            Shutdown.daemon(() -> {
                while (running) {
                    try {
                        WatchKey key = watchService.take();
                        for (WatchEvent<?> event : key.pollEvents()) {
                            WatchEvent.Kind<?> kind = event.kind();
                            Path filePath = folderPath.resolve((Path) event.context());

                            if (kind == StandardWatchEventKinds.ENTRY_CREATE ||
                                kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                                scheduleFileForProcessing(filePath);
                            }
                        }
                        key.reset();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break; // Exit the thread on interruption
                    }
                }
            }, "OPM-watch2-events").start();
        }

        private void scheduleFileForProcessing(Path filePath) {
            executorService.schedule(() -> {
                long previousSize = -1;
                long currentSize = filePath.toFile().length();

                while (currentSize != previousSize) {
                    previousSize = currentSize;
                    try {
                        Thread.sleep(1000); // Check every second
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    currentSize = filePath.toFile().length();
                }

                fileQueue.offer(filePath);
                System.out.println("File added to queue: " + filePath);
            }, 5000, TimeUnit.MILLISECONDS); // Initial delay of 5000 milliseconds
        }

        public void stopWatching() throws IOException {
            running = false; // Stop the thread
            executorService.shutdownNow(); // Stop the executor service
            if (watchService != null) {
                watchService.close(); // Close the watch service
            }
        }

        private void bringUIToFront() {
            // Implement the code to bring the UI window to the front
            System.out.println("Bringing the UI to front for the existing FolderWatcher instance.");
        }

        private class FileProcessor implements Runnable {
            @Override
            public void run() {
                while (running) {
                    Path fileToProcess = fileQueue.poll();
                    if (fileToProcess != null) {
                        processFile(fileToProcess);
                    }

                    try {
                        Thread.sleep(100); // Delay before checking the queue again
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            private void processFile(Path file) {
                // Implement your file processing logic here
                System.out.println("Processing file: " + file);
            }
        }
    }
}
