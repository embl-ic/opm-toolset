package de.embl.iclm;

import java.io.Closeable;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

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

    private final String LOC_KEY = "OPMwatcher.loc";
    private FolderWatcher instance;
    
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
	
    private boolean watching = false;
    private WatchServiceMonitor watcher = null;
    private FileListMonitor monitor = null;
    
    private Parameter parameter = null;
    private File watchedFolder = null;
    private String metadataName = "ExperimentalParameters.txt";
    private boolean metadata_received = false;
    private String[] extensions = {"tif", "tiff"};
    private String[] keywords = new String[0];
    
    private String[] fileList = new String[0];
    /* Written by the watch thread, read and drained by the monitor thread, so both must be
     * thread safe and neither may ever be reassigned: a synchronized wrapper protects the list
     * it wraps, not the field holding it, and swapping the field would silently drop the
     * protection. Iterate newFileList over a snapshot, never over the live list. */
    private final List<String> newFileList = Collections.synchronizedList( new ArrayList<String>() );
    private List<String> processedFileList = new ArrayList<String>();
    private File saveFolder = null;
    
    
    /* Filled by the watch thread on every create/modify event and drained by the monitor
     * thread, so it must be a concurrent map: a plain HashMap shared this way can corrupt on
     * resize and throws ConcurrentModificationException while the monitor iterates it. */
    private final Map<Path, Long> fileSizeLog = new ConcurrentHashMap<Path, Long>();
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
        
        
        
    }

    
    // main class implement WatchService Runner
    public class WatchServiceMonitor implements Runnable, Closeable {
    	private volatile boolean terminate = false;
    	/* Guard the start() handshake: settled says the watch thread has reported back at all,
    	 * registered says what it reported. Both are only read and written inside synchronized. */
    	private boolean settled = false;
    	private boolean registered = false;
        private Path watchFolderPath;
        private Thread watcherThread;

        public WatchServiceMonitor( Path folderPath) {
            this.watchFolderPath = folderPath;
        }

        @Override
        public void close() throws IOException {
            try {
                stop();
            } catch ( InterruptedException e ) {
            }
        }

        public void join() throws InterruptedException {
            watcherThread.join();
        }

        /**			Watch the folder until stopped
         * <br>	Registration happens first and is announced to start(), which is waiting for it;
         * <br>	the loop then drains one WatchKey at a time and resets it once per key, not once
         * <br>	per event. A key that cannot be reset is no longer valid, so the loop ends: taking
         * <br>	from the service again would block forever on a watch that no longer exists.
         */
        @Override
        public void run() {
            try (WatchService service = FileSystems.getDefault().newWatchService()) {
                watchFolderPath.register( service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
                announceRegistration ( true );
                while(!terminate) {
                    if ( watcherThread.isInterrupted() ) break;
                    WatchKey key = null;
                    try {
                        key = service.take();
                    } catch ( InterruptedException e ) {
                    	Thread.currentThread().interrupt();	// keep the flag for the caller
                        break;
                    }

                    for ( WatchEvent<?> eventUnknown : key.pollEvents() ) {
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

                        // record the growing size, so the monitor thread can tell when writing stopped
                        if ( kind == ENTRY_CREATE || kind == ENTRY_MODIFY ) {
                        	/* The file may already be gone again, or still be locked by the
                        	 * acquisition software. That is an ordinary condition here, not a
                        	 * reason to abandon the watch, so it is caught per event. */
                        	try {
                        		fileSizeLog.put( path, Files.size(path) );
                        	} catch ( IOException e ) {
                        		System.out.println("watcher : size unavailable for " + path + " (" + e + ")");
                        		continue;
                        	}
                        	if ( kind == ENTRY_CREATE ) fileList = getFileList();
                        }
                    }
                    updateWatchStatus();
                    // one reset per key, after its events are drained; an invalid key ends the watch
                    if ( !key.reset() ) {
                    	System.out.println("watcher : watch key no longer valid, stopping watch.");
                    	break;
                    }
                }
            } catch ( IOException e ) {
            	/* Registration or the service itself failed. start() may still be waiting to be
            	 * told the watch is up, so release it before leaving. */
            	System.out.println("watcher : watch service failed: " + e);
            	log.add("watcher : watch service failed: %s", String.valueOf(e));
            } finally {
            	announceRegistration ( false );
            }
            updateWatchStatus();
        }

        /**			Release start(), which blocks until the watch is either up or known to have failed
         *
         * @param registered	: true when the folder is registered and events will arrive
         */
        private void announceRegistration ( boolean registered ) {
        	synchronized ( this ) {
        		this.settled = true;
        		this.registered = registered;
        		this.notifyAll();
        	}
        }

        /**			Start the watch thread and return only once the folder is actually registered
         * <p>		The wait is guarded by a flag and wrapped in a loop. Without the flag the
         * 			notification is lost whenever registration wins the race against this wait,
         * 			and the caller blocks forever; without the loop a spurious wakeup would let
         * 			the caller continue before any event can be delivered.
         *
         * @throws InterruptedException	: if the caller is interrupted while waiting
         */
        public void start() throws InterruptedException {
            watcherThread = Shutdown.daemon ( this, "watcher-thread" );
            watcherThread.start();
            synchronized ( this ) {
                while ( !settled ) this.wait();
            }
            if ( !registered ) System.out.println("watcher : folder could not be watched.");
        }

        /**			Stop the watch thread and wait for it to leave its loop
         *
         * @throws InterruptedException	: if the caller is interrupted while joining
         */
        public void stop() throws InterruptedException {
        	terminate = true;			// leave the loop even if take() returns before the interrupt
            watcherThread.interrupt();
            watcherThread.join();
            watcherThread = null;
        }
    }	// main class implement WatchService Runner finish
    
    /**		Decide when a file the watcher reported has finished being written
     * <br>	A file whose size did not change between two polls is treated as complete and moved
     * <br>	from the size log onto the new file list, which the same loop then processes.
     */
    public class FileListMonitor implements Runnable {
    	private volatile boolean terminate = false;
    	private Thread monitorThread;

    	/** Start polling the size log every half second. */
    	public void start() {
			monitorThread = Shutdown.daemon(this, "monitor-thread");
			monitorThread.start();
		}
		/**			Stop polling and wait for the loop to end
		 *
		 * @throws InterruptedException	: if the caller is interrupted while joining
		 */
		public void stop() throws InterruptedException {
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
                        Path path = entry.getKey();
                        long currentSize;
                        try {
                        	currentSize = Files.size(path);
                        } catch ( IOException e ) {	// file vanished again: drop it, keep watching
                        	iter.remove();
                        	continue;
                        }
                        // size stopped growing: the acquisition has finished writing this file
                        if (currentSize != 0 && currentSize == entry.getValue()) {
                        	String newFilePath = path.toString();
                        	if (!newFileList.contains(newFilePath))
                        		newFileList.add(newFilePath);
                    		iter.remove();
                        } else {
                        	entry.setValue( currentSize );	// still growing: remember the new size
                        }
                    }
					// filter in place; never reassign the field, that would drop the synchronization
                    filterFileList( newFileList );
				} catch ( InterruptedException e ) {
					Thread.currentThread().interrupt();	// keep the flag, then leave the loop
					break;
				} catch (Exception e) {
					System.out.println("monitor : " + e);
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
    
    /**		Refresh the status panel
     * <br>	Reads:
     * <br>		watcher: running (stopped)
     * <br>		folder: path
     * <br>		file info: 0 new file / 199 files (processed)
     */
    public void updateWatchStatus () {
    	statusString = " watcher: " + (watching ? "running" : "stopped")
    			+ "\n folder: " + parameter.watchDir
    			+ "\n file info: "
    			+ newFileList.size() + " new file / "
    			+ fileList.length + " files ("
    			+ processedFileList.size() + " processed)";
    	setStatusText( statusString );
    }

    /**			Write the status panel from any thread
     * <p>		The watch and monitor threads both report progress, and Swing components may only
     * 			be touched on the event dispatch thread, so the update is handed over when it is
     * 			called from anywhere else.
     *
     * @param text	: text to show in the status area
     */
    private void setStatusText ( final String text ) {
    	if ( SwingUtilities.isEventDispatchThread() ) {
    		watcherStatus.setText( text );
    		return;
    	}
    	SwingUtilities.invokeLater( new Runnable() {
			@Override
			public void run() { watcherStatus.setText( text ); }
		});
    }
    
    /**		Choose the folder to watch, and decide what to do with the files already in it
     * <br>	Files present before the watch starts are queued only when "process old" is set;
     * <br>	otherwise the pending list is emptied so that the watch begins from now on.
     */
    public void setup_folder () {
    	if (!parameter.watcher_setupWatch()) return;

    	/* The watched folder is only known now, so the log starts in the temporary
    	 * directory and moves here once there is a folder to belong to. */
    	parameter.logPath = Log.prepareLogPath( parameter.watchDir, "OPM_watcher.log" );
	    log.setPath( parameter.logPath );
		log.add(parameter);

    	watchedFolder = new File(parameter.watchDir);
    	keywords = new String[0];
    	if ( !parameter.keywords.isEmpty() ) {
    		keywords = parameter.keywords.split(",");
    		for (int i=0; i<keywords.length; i++) {
    			keywords[i] = keywords[i].replaceAll("\\s+","");
        	}
    	}
    	fileList = getFileList();
    	// clear, never reassign: other threads hold the same list through the same field
    	newFileList.clear();
    	if (parameter.processOld) {
    		for (int i=0; i<fileList.length; i++) {
    			if ( !newFileList.contains(fileList[i]) )
    				newFileList.add(fileList[i]);
    		}
    	}
    	updateWatchStatus();
    }

    // set up processing parameter (once valid new image file detected)
    public void setup_processing () {
    	if ( !parameter.watcher_setupProcessing() ) return;
    	if ( parameter.saveToSame || parameter.saveDir.isEmpty() || parameter.saveDir.equals(parameter.watchDir) )
    		parameter.saveDir = parameter.watchDir + File.separator + "result";
    	
    	log.add(parameter);
    	
    	saveFolder = new File(parameter.saveDir);
    	if (!saveFolder.exists()) saveFolder.mkdirs();
    	
		parameter.tryGPU = true;
		parameter.autoPartition = true;
		parameter.displayResult = false;
    	
    	parameter.parseProjectionParameter();
    	
    	parameter.storeParam();
    	
    	
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
    
    /**			Drop every path that is not an image this watcher was asked to process
     * <br>		Filters in place, so the caller keeps whatever list implementation it passed in
     * <br>		and never has to reassign a field that other threads share.
     *
     * @param fileList	: list of absolute file paths, modified in place
     * <p>
     * @return			: the same list, without the entries that failed extension or keyword
     */
    public List<String> filterFileList(List<String> fileList) {
    	return filterFileList ( fileList, extensions, keywords );
    }

    /**			Drop every path that is not an image matching these extensions and keywords
     * <br>		Static and given its criteria explicitly, so it can be tested without opening
     * <br>		the watcher window.
     *
     * @param fileList		: list of absolute file paths, modified in place
     * @param extensions	: accepted file extensions, compared case insensitively
     * @param keywords		: substrings that must all appear in the file name
     * <p>
     * @return				: the same list, without the entries that failed
     */
    static List<String> filterFileList ( List<String> fileList, String[] extensions, String[] keywords ) {
    	if (null == fileList || fileList.isEmpty()) return fileList;
    	/* Decide on a snapshot and remove afterwards: the list may be a synchronized wrapper
    	 * that another thread appends to, and an iterator over it would not survive that. */
    	List<String> reject = new ArrayList<String>();
    	for ( String path : new ArrayList<String>( fileList ) ) {
    		if ( !keepFile( path, extensions, keywords ) ) reject.add( path );
        }
    	fileList.removeAll( reject );
    	return fileList;
    }

    /**			Test one path against the watcher's extension and keyword criteria
     *
     * @param path			: absolute file path
     * @param extensions	: accepted file extensions, compared case insensitively
     * @param keywords		: substrings that must all appear in the file name
     * <p>
     * @return				: true when the file should be processed
     */
    static boolean keepFile ( String path, String[] extensions, String[] keywords ) {
    	if ( null == path ) return false;
		boolean extensionMatched = false;
		for (String ext : extensions) {
			if ( path.toLowerCase().endsWith( ext.toLowerCase() ) ) { extensionMatched = true; break; }
		}
		if ( !extensionMatched ) return false;
		String name = Paths.get(path).getFileName().toString();
		for (String keyword : keywords) {
			if ( !name.contains(keyword) ) return false;
		}
		return true;
    }

    /**
     * 
     */
    /**		Deskew every file the monitor has declared complete
     * <br>	Work is done over a snapshot of the pending list and each path is removed from the
     * <br>	shared list once it has been dealt with, so the watch thread can keep appending new
     * <br>	files while this runs. A file that fails is reported and dropped, not retried
     * <br>	forever, so one unreadable volume cannot stall a running acquisition.
     */
    public void processNewFile() {

    	if ( newFileList.isEmpty() ) return;
    	if (!metadata_received) {
    		System.out.println("Experiment Parameter file not received. skip processing");
    		return;
    	}
    	if ( null == saveFolder || parameter.saveDir.isEmpty() || parameter.saveDir.equals(parameter.watchDir) ) {
	    	parameter.saveDir = parameter.watchDir + File.separator + "result";
	    	saveFolder = new File(parameter.saveDir);
	    	if (!saveFolder.exists()) saveFolder.mkdirs();
	    	parameter.recursive = false;
    	}
    	// snapshot: this loop is long running and the watch thread appends to the same list
    	for ( String path : new ArrayList<String>( newFileList ) ) {
            log.add("processing file: %s", path);
            IJ.log("processing file: " + path);
            try {
            	setStatusText( statusString + "\n processing:\n" + Paths.get(path).getFileName().toString() );

            	Deskew.processFile ( path, parameter);

				System.gc();

	    		processedFileList.add(path);
            } catch (Exception e) {
            	log.add("failed to process %s : %s", path, String.valueOf(e));
            	IJ.log("failed to process " + path + " : " + e);
            	System.out.println( "failed to process " + path + " : " + e );
            } finally {
            	newFileList.remove( path );	// dealt with, successfully or not
            	updateWatchStatus();
            }
        }
    }
    
    
    
}