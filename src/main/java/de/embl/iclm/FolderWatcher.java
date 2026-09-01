package de.embl.iclm;

import java.io.Closeable;
//import java.awt.LayoutManager;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;

//import ij.IJ;
//import ij.ImagePlus;
//import ij.WindowManager;
//import ij.gui.GenericDialog;
import ij.plugin.frame.PlugInFrame;

import ij.*;
import ij.gui.*;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.awt.*;
import java.awt.event.*;

public class FolderWatcher extends PlugInFrame {

	private static final long serialVersionUID = 1L;
	//private static final double scale = Prefs.getGuiScale();
    //private static final int width = (int)(300*scale);
    //private static final int height = (int)(100*scale);

    private final String LOC_KEY = "OPMwatcher.loc";
    private FolderWatcher instance;
    //private Image image;
    
    //private static String dirWatchPath = "";
    private String statusString = "";
    //private static int numFiles = 0;
    //private static String[] displayedfiles = {};
    //private static long processWait = 5000;
    
    private final Color panelColor = new Color(204, 229, 255);
    private final Dimension textAreaMax = new Dimension(400, 300);
    private final Dimension panelMax = new Dimension(500, 200);
    private final Dimension panelMin = new Dimension(380, 100);
	
    private final JTextArea watcherStatus = new JTextArea();
    private final JButton btnChooseFolder = new JButton("choose folder");
    private final JButton btnSetup = new JButton("setup processing");
    private final JToggleButton btnToggleWatch = new JToggleButton("start watcher");
    private final JButton btnExit = new JButton("exit");
	
    private boolean watching = false;
    //private static watcherRunner watcher;
    private WatchServiceMonitor watcher = null;
    private FileListMonitor monitor = null;
    
    private Parameter parameter = null;
    private File watchedFolder = null;
    private String metadataName = "ExperimentalParameters.txt";
    private boolean metadata_received = false;
    private String[] extensions = {"tif", "tiff"};
    private String[] keywords = new String[0];
    
    private String[] fileList = new String[0];
    private List<String> nFileList = new ArrayList<String>();
    private List<String> newFileList = Collections.synchronizedList(nFileList);
    private List<String> processedFileList = new ArrayList<String>();
    private File saveFolder = null;
    
    //private ArrayList<String[]> projectionList = new ArrayList<String[]>();
    //private ArrayList<String> axes = null;
    //private ArrayList<String> types = null;
    //private File saveFolder = null;
    
    private Map<Path, Long> fileSizeLog = new HashMap<Path, Long>();
    //private long maxWait = (long) (10 * 1000); // maximum wait time after file creation is 10 seconds
    private Log log;
    
    public FolderWatcher() {
        super("OPM Folder Watcher");
        if (instance!=null) {
            WindowManager.toFront(instance);
            return;
        }
        instance = this;
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
        
        
        //Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
        //watcherThread.setPriority(Thread.MIN_PRIORITY);
        //watcher = new watcherRunner();
        //watcher.start();
        
    }

    
    // main class implement WatchService Runner
    public class WatchServiceMonitor implements Runnable, Closeable {
        //private Destination destination;
    	private volatile boolean terminate = false;
    	//private int priority = Thread.MAX_PRIORITY;
    	//private long refreshrate = 1000;
        private Path watchFolderPath;
        private Thread watcherThread;
        
        public WatchServiceMonitor( Path folderPath) {//, Destination destination ) {
            this.watchFolderPath = folderPath;
            //this.destination = destination;
        }

        @Override
        public void close() throws IOException {
            try {
                stop();
            } catch ( InterruptedException e ) {
                //log.warn( "request to stop failed, guess its time to stop being polite!" );
            }
        }

        public void join() throws InterruptedException {
            watcherThread.join();
        }

        @Override
        public void run() {
	        //Map<Path, Long> fileCreationTime = new HashMap<Path, Long>();
	        //Long maxWait = (long) (10 * 1000); // maximum wait time after last file modify is 10 seconds.
            try (WatchService service = FileSystems.getDefault().newWatchService()) {
                //if ( log.isTraceEnabled() ) log.trace( "registering create watcher on " + hotFolder.toAbsolutePath().toString() );
                watchFolderPath.register( service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
                //if ( log.isDebugEnabled() ) log.debug( "watcher registration complete for " + hotFolder.toAbsolutePath().toString() );
                synchronized ( this ) {
                    this.notifyAll();
                }
                while(!terminate) {
                    if ( watcherThread.isInterrupted() ) break;
                    WatchKey key = null;
                    try {
                        //log.trace( "waiting for create event" );
                        key = service.take();
                        //log.trace( "got an event, process it" );
                    } catch ( InterruptedException e ) {
                        //log.trace( "interruped, must be time to shut down..." );
                        break;
                    }

                    for ( WatchEvent<?> eventUnknown : key.pollEvents() ) {
                    	updateWatchStatus(); // not sure this is the right location for this
                        WatchEvent.Kind<?> kind = eventUnknown.kind();
                        
                        if ( kind == OVERFLOW ) continue;

                        @SuppressWarnings( "unchecked" )
                        WatchEvent<Path> eventPath = (WatchEvent<Path>) eventUnknown;
                        Path path = watchFolderPath.resolve( eventPath.context() );
                        System.out.println("File " + kind.toString() +  ": " + path);
                        
                        if (path.getFileName().toString().equals(metadataName)) {
                        	System.out.println("meta data found:");
                        	parameter.deskewmFile = path.toString();
                        	parameter.parseDeskewParameterLive();
                        	if (null != parameter.deskewMatrix)
                        		metadata_received = true;
                        }
                        
                        
                        if ( kind == ENTRY_CREATE ) {	// file being created, maybe incomplete
                        	fileSizeLog.put(path, Files.size(path));    // HERE!!!
	                        fileList = getFileList(); //???
                        }
                        
                        if ( kind == ENTRY_MODIFY ) { // wait for maxWait after 2nd modify to process file
                        	fileSizeLog.put(path, Files.size(path));	
                        }
                        
                        /*
                        Iterator<Entry<Path, Long>> iter = fileCreationTime.entrySet().iterator();
                        while (iter.hasNext()) {
                            Entry<Path, Long> entry = iter.next();
                            long lapse = System.currentTimeMillis() - entry.getValue();
                            if (lapse > maxWait) {
                            	String newFilePath = entry.getKey().toString();
                        		newFileList.add(newFilePath);
                        		iter.remove();
                            }
                        }
                        */
                   
                        if (! key.reset()) {
                            break;
                        }
                        
                    }
                    // process new file CODE BLOCK
                    //processNewFile_dummy();
                }
            } catch ( IOException e ) {
                //log.error( ioe.getMessage(), ioe );
            }
            updateWatchStatus();
            //log.debug( "existing run loop" );
        }

        public void start() throws InterruptedException {
            //log.trace( "starting monitor" );
            watcherThread = new Thread( this );
            watcherThread.start();
            watcherThread.setName("watcher-thread");
            synchronized ( this ) {
                this.wait();
            }
            //log.trace( "monitor started" );
        }

        public void stop() throws InterruptedException {
            //log.trace( "stopping monitor" );
            watcherThread.interrupt();
            watcherThread.join();
            watcherThread = null;
            //log.trace( "monitor stopped" );
        }
    }	// main class implement WatchService Runner finish
    
    public class FileListMonitor implements Runnable {
    	private volatile boolean terminate = false;
    	private Thread monitorThread;
    	//private int priority = Thread.MAX_PRIORITY;
    	//private long refreshrate = 1000;
    	
    	public void start() {
			//IJ.log("monitor start reached.");
			monitorThread = new Thread(this);
			monitorThread.start();
			monitorThread.setName("monitor-thread");
		}
		public void stop() throws InterruptedException {
			//IJ.log("monitor stop reached.");
			terminate = true;
			monitorThread.interrupt();
			monitorThread.join();
			monitorThread = null;
		}

		@Override
		public void run() {
			while( !terminate ) {
				try {
					Thread.sleep(500);
					Iterator<Entry<Path, Long>> iter = fileSizeLog.entrySet().iterator();
					while (iter.hasNext()) {
                        Entry<Path, Long> entry = iter.next();
                        Path path = Paths.get( entry.getKey().toString() );
                        long currentSize = Files.size(path);
                        
                        long size_diff = currentSize - entry.getValue();
                        
                        if (currentSize != 0 && size_diff == 0) {
                        	String newFilePath = entry.getKey().toString();
                        	if (!newFileList.contains(newFilePath))
                        		newFileList.add(newFilePath);
                    		iter.remove();
                        }
                    }
                    newFileList = filterFileList(newFileList);
				} catch (Exception e) {
					System.out.println("monitor : " + e.getMessage());
				}
				updateWatchStatus();
                processNewFile();
                updateWatchStatus();
			}
		}
    	
    }
    
    /**
     * 	functions directly associated with GUI component
     * 		- update status text
     * 		- set up folder being watched
     * 		- set up processing parameter (once valid new image file detected)
     * 		- toggle start / stop of the watcher service
     * 		- stop watcher if any, and exit
     */
    
    // update status text
    public void updateWatchStatus () {
    	/*
    	 *  watcher: running (stopped)
    	 *  folder path:
    	 *  file info: 0 new file / 199 files (processed)
    	 *  processing: file
    	 */
    	statusString = " watcher: " + (watching ? "running" : "stopped");
    	statusString += "\n folder: " + parameter.watchDir;
    	statusString += "\n file info: " 
    			+ newFileList.size() + " new file / " 
    			+ fileList.length + " files ("
    			+ processedFileList.size() + " processed)";    	
    	watcherStatus.setText(statusString);
    }
    
    //set up folder being watched
    public void setup_folder () {
    	if (!parameter.watcher_setupWatch()) return;
    	
    	//Log.prepareLogPath( parameter );
	    //log.setPath( parameter.logPath );
		log.add(parameter);
		
    	watchedFolder = new File(parameter.watchDir);
    	if (null == watchedFolder) System.out.println("\n\tfolder not set for watching!");
    	if ("" != parameter.keywords) {
    		keywords = parameter.keywords.split(",");
    		for (int i=0; i<keywords.length; i++) {
    			keywords[i] = keywords[i].replaceAll("\\s+","");
        	}
    	}
    	fileList = getFileList();
    	if (parameter.processOld) {
    		//newFileList = Collections.synchronizedList( Arrays.asList(fileList) );
    		for (int i=0; i<fileList.length; i++) {
    			if ( !newFileList.contains(fileList[i]) )
    				newFileList.add(fileList[i]);
    		}
    	} else {
    		newFileList = new ArrayList<String>();//Collections.synchronizedList( new ArrayList<String>() );
    	}
    	updateWatchStatus();
    }
    
    // set up processing parameter (once valid new image file detected)
    public void setup_processing () {
    	if ( !parameter.watcher_setupProcessing() ) return;
    	if ( parameter.saveToSame || parameter.saveDir == "" || parameter.saveDir.equals(parameter.watchDir) )
    		parameter.saveDir = parameter.watchDir + File.separator + "result";
    	
    	log.add(parameter);
    	
    	saveFolder = new File(parameter.saveDir);
    	if (!saveFolder.exists()) saveFolder.mkdirs();
    	
		parameter.tryGPU = true;
		parameter.autoPartition = true;
		parameter.displayResult = false;
    	
    	//parameter.parseDeskewParameterBatch();
    	parameter.parseProjectionParameter();
    	//parameter.parseAlignParameter();
    	
    	parameter.storeParam();
    	
    	/*
    	if ( parameter.projX || parameter.projY || parameter.projZ ) {
        	// prepare projection axis string list
    		axes = new ArrayList<String>();
    		if (parameter.projX) axes.add("X");
    		if (parameter.projY) axes.add("Y");
    		if (parameter.projZ) axes.add("Z");
    	}
    	if ( parameter.maxProj || parameter.avgProj || parameter.minProj ||
    		 parameter.sumProj || parameter.medProj || parameter.stdProj ) {
    		// prepare projection type string list
    		types = new ArrayList<String>();
    		if (parameter.maxProj)	types.add("max");
    		if (parameter.avgProj)	types.add("avg");
    		if (parameter.minProj)	types.add("min");
    		if (parameter.sumProj)	types.add("sum");
    		if (parameter.medProj)	types.add("med");
    		if (parameter.stdProj)	types.add("std");
    	}
		*/
    	
    	updateWatchStatus();
    }
    
    // toggle start / stop of the watcher service
    public void toggleWatch() {
    	if (null == watchedFolder) watchedFolder = new File(parameter.watchDir);
    	if (null == watchedFolder) {
    		 System.out.println("\n\tfolder not set for watching!");
    		return;
    	}
    	
    	watching = !watching; // start/stop watching
    	if (watching) {
    		watcher = new WatchServiceMonitor(watchedFolder.toPath());
    		monitor = new FileListMonitor();
            try {
				watcher.start();
				monitor.start();
			} catch (InterruptedException e) {
				System.out.println(e.getMessage());
				watching = false;
				return;
			}
    		btnToggleWatch.setText("stop watcher");
    		btnToggleWatch.setSelected(true);
    	} else {
    		if (null != watcher) {
				try {
					watcher.stop();
					monitor.stop();
				} catch (InterruptedException e) {
					System.out.println(e.getMessage());
				}
    		}
    		watcher = null;
    		monitor = null;
    		btnToggleWatch.setText("start watcher");
    		btnToggleWatch.setSelected(false);
    	}
    	//updateWatchStatus();
    }
    
    // stop watcher if any, and exit
    public void close() {
        super.close();
        instance = null;
        Prefs.saveLocation(LOC_KEY, getLocation());
        watching = false;
        log.add("OPM folder watch finish.");
        log.close();
        IJ.log("OPM folder watch closed.");
    }
    
    
    /*
     * 	functions support the watcher service, and processing
     * 		- public String[] getFileList(): get filtered file path in watched folder into string array, need folder setup done first
     * 		- pulbic String[] filterFileList(List<String> fileList): filter file path list with extensions, and keywords
     * 		- public void processNewFile():
     * 		- 
     */
    
    // get filtered file path in watched folder into string array, need folder setup done first
    public String[] getFileList() {
    	String[] fileList = new String[0];
    	if (null == watchedFolder) return fileList;
    	// get a list of file in the watched folder, meets criterion
    	fileList = watchedFolder.list(new FilenameFilter() {
		    public boolean accept(File directory, String fileName) {
		    	boolean extPass = false;
		    	for (int i=0; i<extensions.length; i++) {
		    		extPass = extPass || fileName.toLowerCase().endsWith(extensions[i]);
		    	}
		    	if (!extPass) return false;
		    	for (int i=0; i<keywords.length; i++) {
		    		if (!fileName.contains(keywords[i])) return false;
		    	}
		    	return true;
	    	}
	    });
    	for (int i=0; i<fileList.length; i++) {
    		fileList[i] = watchedFolder.getAbsolutePath() + File.separator + fileList[i];
    	}
    	return fileList;
    }
    
    // filter file path list with extensions, and keywords
    public List<String> filterFileList(List<String> fileList) {
    	List<String> filteredList = new ArrayList<String>();
    	if (null == fileList || 0 == fileList.size())
    		return filteredList;
    	ListIterator<String> iter = fileList.listIterator();
    	while (iter.hasNext()) {
    		String path = iter.next();
    		// 1st check extension
    		boolean pass = true;
    		for (String ext : extensions) {
    			if (path.endsWith(ext)) {	// match found
    				pass = false;
    				break;
    			}
    		}
    		if (pass) { iter.remove(); continue; }
    		// 2nd check keywords
    		String name = Paths.get(path).getFileName().toString();
    		for (String keyword : keywords) {
    			if (!name.contains(keyword)) {	// keyword not found in file name
    				iter.remove();
    				continue;
    			}
    		}
        }
    	return fileList;
    }

    /**
     * 
     */
    public void processNewFile() {
    	
    	if ( 0 == newFileList.size() ) return;
    	if (!metadata_received) {
    		System.out.println("Experiment Parameter file not received. skip processing");
    		return;
    	}
    	if ( null == saveFolder || parameter.saveDir.equals(parameter.watchDir) || parameter.saveDir == "" ) {
	    	parameter.saveDir = parameter.watchDir + File.separator + "result";
	    	saveFolder = new File(parameter.saveDir);
	    	if (!saveFolder.exists()) saveFolder.mkdirs();
	    	parameter.recursive = false;
    	}
    	if (null == saveFolder) return;
    	// iterate through new file list
    	ListIterator<String> iter = newFileList.listIterator();
    	while (iter.hasNext()) {
    		String path = iter.next();
            log.add("processing file: %s", path);
            IJ.log("processing file: " + path);
            try {
            	watcherStatus.setText(statusString + "\n processing:\n" + Paths.get(path).getFileName().toString());
            	
            	Deskew.processFile ( path, parameter);
            	
            	/*
            	ImagePlus imp = VolumeIO.open(path);            	
	    		// add processing step here
	            ImagePlus imp_deskewed = Deskew.deskew_image( imp, parameter );
				String saveFile_deskew = parameter.saveDir + File.separator + imp_deskewed.getTitle();
				if (parameter.saveSeparate) {
					String deskew_dir = parameter.saveDir + File.separator + "deskew";
					Files.createDirectories(Paths.get(deskew_dir));
					saveFile_deskew = deskew_dir + File.separator + imp_deskewed.getTitle();
				}
				if (!new File(saveFile_deskew).exists() || parameter.fileExistStr.equals("overwrite"))
					VolumeIO.saveTiff(imp_deskewed, saveFile_deskew);
				log.add(" deskewed image saved to: %s", saveFile_deskew);
				String name = Utils.getName(imp_deskewed);
				if (axes != null && types != null) {
					for (String axis : axes) {
		    			for (String type : types) {
		    				ImagePlus imp_Proj = Projection.projection (imp_deskewed, axis, type, parameter.tryGPU);
		    				imp_Proj.setTitle(name + "-" + type + axis + "projection");
		    				
		    				if (parameter.makeTimeLapse) {
		    					String name_timeLapse = type+axis+"projection"+"-timeLapse";
		    					ImagePlus imp_timeLapse = WindowManager.getImage(name_timeLapse);
			    				Partition.combineTimelapse(imp_timeLapse, imp_Proj, name_timeLapse);
		    				}
		    				
		    				String saveFile_proj = parameter.saveDir + File.separator + imp_Proj.getTitle();
							if (parameter.saveSeparate) {
								String proj_dir = parameter.saveDir + File.separator + type + axis.toLowerCase();
								try {
									Files.createDirectories(Paths.get(proj_dir));
								} catch (IOException e) {
									System.out.println( e.getMessage() );
								}
								saveFile_proj = proj_dir + File.separator + imp_Proj.getTitle();
							}
							if (!new File(saveFile_proj).exists() || parameter.fileExistStr.equals("overwrite"))
								VolumeIO.saveTiff(imp_Proj, saveFile_proj);
							imp_Proj.close();
							log.add(" projection image saved to: %s", saveFile_proj);
		    			}
		    		}
				}
				
				// clean up
				imp.close();
				imp_deskewed.close();
				*/
				
				//IJ.run("Collect Garbage", "");
				System.gc();
				
				
				
	    		processedFileList.add(path);
            } catch (Exception e) {
            	log.add(e.getMessage());
            	System.out.println( e.getMessage() );
            }
            iter.remove();
            updateWatchStatus();
            //watcherStatus.setText(statusString);
        }
    	//updateWatchStatus();
    }
    
    
    
}