package de.embl.iclm;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;

import de.embl.iclm.FolderWatcher.FileListMonitor;
import de.embl.iclm.FolderWatcher.WatchServiceMonitor;
import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.GUI;
import ij.plugin.PlugIn;
import ij.plugin.frame.PlugInFrame;


public class TCPIP2  extends PlugInFrame {
	
	private Parameter parameter = null;
	
    
	private static final long serialVersionUID = 1L;

    private final String LOC_KEY = "OPMlistener.loc";
    private TCPIP2 instance;
    
    private String filePath = "";
    private String statusString = "";
    
    private final Color panelColor = Parameter.frameColor;
    private final Dimension textAreaMax = new Dimension(400, 300);
    private final Dimension panelMax = new Dimension(500, 200);
    private final Dimension panelMin = new Dimension(380, 100);
	
    private final JTextArea listenerStatus = new JTextArea();
    private final JButton btnSetup = new JButton("setup processing");
    private final JToggleButton btnToggleListener = new JToggleButton("start listening");
    private final JButton btnExit = new JButton("exit");
	

    
    private String metadataName = "ExperimentalParameters.txt";
    private boolean metadata_received = false;
    
    
    private static final int PORT = 5020;
    private ServerSocket serverSocket;
    private boolean listening = false;
    
    private boolean closeAllTimeLapse = false;
    
    public TCPIP2() {
        super("OPM TCP/IP Listener");
        if (instance!=null) {
            WindowManager.toFront(instance);
            return;
        }
        instance = this;
        WindowManager.addWindow(this);
        setLayout(new BoxLayout(instance, BoxLayout.Y_AXIS));
        setBackground(panelColor);
        parameter = new Parameter("TCPIP");
        // default parameters: use GPU, auto-parition data, do not display the deskewed volume
        parameter.tryGPU = true;
		parameter.autoPartition = true;
		parameter.displayResult = false;
        
        // 2025.07.29 hot fix: restore default parameters:
        parameter.channelStr =		"whole image";
        parameter.alignmFile =		"";
        parameter.projX = 			true;
        parameter.projY = 			true;
        parameter.projZ = 			true;
        parameter.maxProj = 		true;
        parameter.avgProj = 		false;
        parameter.makeTimeLapse = 	true;
        parameter.saveDir = 		"";
        parameter.saveToSame =		true;
        parameter.saveDeskewImage =	true;
        parameter.saveSeparate = 	true;
    	parameter.parseProjectionParameter();

        
        // content panel:  Text Area + Buttons
        JPanel contentPanel = new JPanel();
		contentPanel.setBackground(panelColor);
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));

		// watcher status as Text Area
        listenerStatus.setText(statusString);
        listenerStatus.setMaximumSize(textAreaMax);
        listenerStatus.setEditable(false);
        listenerStatus.setBackground(panelColor);
        contentPanel.add(listenerStatus);
		listenerStatus.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        // buttons implementing folder watcher setup and controller
        JPanel buttonPanel = new JPanel();
		buttonPanel.add(btnSetup);
		buttonPanel.add(btnToggleListener);
		buttonPanel.add(btnExit);
		buttonPanel.setBackground(panelColor);
		buttonPanel.setMaximumSize(panelMax);
		buttonPanel.setMinimumSize(panelMin);
		contentPanel.add(buttonPanel);
		buttonPanel.setAlignmentX(Component.CENTER_ALIGNMENT);

		// configure refresh button
		// configure refresh button
		btnSetup.addActionListener(new ActionListener() { 
		  @Override 
		  public void actionPerformed(ActionEvent ae) { setup_processing(); }
		});
		// configure refresh button
		btnToggleListener.addActionListener(new ActionListener() { 
		  @Override 
		  public void actionPerformed(ActionEvent ae) { toggleListener(); }
		});
		// configure refresh button
		btnExit.addActionListener(new ActionListener() { 
		  @Override 
		  public void actionPerformed(ActionEvent ae) { close(); }
		});
		
		
		// add content panel to plugin frame, and configure appearance
		contentPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
		add(contentPanel);
        updateListenerStatus();
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
    
    
    class ClientHandler implements Runnable {
        private Socket clientSocket;
        public ClientHandler(Socket socket) {
            this.clientSocket = socket;
        }
        @Override
        public void run() {
        	
        	metadata_received = false;
        	
            try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {
                
                while ((filePath = in.readLine()) != null) {
                	
                	if ( filePath.toLowerCase().startsWith("stop") ) {
                		System.out.println("STOP signal received.");
                		metadata_received = false;
                		closeAllTimeLapse = true;
                		updateListenerStatus ();
                		Utils.collectGarbage();
                		System.gc();
                		continue;
                	}
                	
                	
                    System.out.println("Received file path: " + filePath);

                    if ( metadata_received ) {
                    	
                    	if ( filePath.endsWith(".tiff") || filePath.endsWith(".tif") ) {
                    		File tif_file = new File(filePath);
                    		if ( !tif_file.exists() ) {
                    			System.out.println("tif File not exist");
                    			continue;
                    		}
                    		
                    		if ( closeAllTimeLapse )
                    			closeTimelapseImages();
                    		
                    		if (parameter.saveToSame)
                    			parameter.saveDir = tif_file.getParentFile().toString() + File.separator + "result";
                    		
                    		
                    		System.out.println("ready to deskew tif file: " + filePath);
                    		
                    		processNewFile ( filePath, parameter );
                    		
                    		
                    	} else {
                    		System.out.println("Received file is not TIF image.");
                    	}
                    	
                    	
                    } else {
                    	
                    	System.out.println("Experiment Parameters not yet received.");
                    	
                    	if ( filePath.endsWith(metadataName) ) {
                        	if ( !(new File(filePath)).exists() ) {
                        		System.out.println(" ExperimentalParameters.txt file not found at provided file path.");
                        	} else {
    	                    	parameter.deskewmFile = filePath;
    	                    	parameter.parseDeskewParameterLive();
                        	}
                        	
                        	if (null != parameter.deskewMatrix) {
                        		metadata_received = true;
                        		System.out.println("deskew parameter parsed correctly.");
                        	} else {
                        		System.out.println("cannot properly read parameter from ExperimentalParameters.txt file!");
                        	}
                        	
                    	}
                    		
                    }
                    updateListenerStatus ();    
                }
            } catch (IOException e) {
                System.err.println("Error handling client: " + e.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    System.err.println("Error closing client socket: " + e.getMessage());
                }
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
    public void updateListenerStatus () {
    	/*
    	 *  watcher: running (stopped)
    	 *  folder path:
    	 *  file info: 0 new file / 199 files (processed)
    	 *  processing: file
    	 */
    	statusString = " TCP/IP listener: " + (listening ? "running" : "stopped");
    	statusString += "\n experiment parameter file received: " + (metadata_received ? "yes" : "no");
    	statusString += "\n processing file:\n" + (null==filePath ? "" : filePath);
    	listenerStatus.setText(statusString);
    }
    
    // set up processing parameter (once valid new image file detected)
    public void setup_processing () {
    	
    	if ( !parameter.tcpip() ) return;
		// check save folder path
    	
		
    	
    	parameter.storeParam();
    	
    	
    	updateListenerStatus(); // not necessary?
    }
    
    
    // toggle start / stop of the watcher service
    public void toggleListener() {
    	
    	listening = !listening; // start/stop watching
    	if (listening) {
    		startListening();
    		btnToggleListener.setText("stop listening");
    		btnToggleListener.setSelected(true);
    		updateListenerStatus ();
    	} else {
    		stopListening();
    		btnToggleListener.setText("start listening");
    		btnToggleListener.setSelected(false);
    		updateListenerStatus ();
    	}
    }
    
    
    // Method to start listening
    public void startListening() {
        listening = true;
        Shutdown.daemon(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                System.out.println("Start listening on port " + PORT);

                while (listening) {
                    // Accept a new client connection
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("Connection received from: " + clientSocket.getInetAddress());
                    // Handle the client in a separate thread
                    Shutdown.daemon(new ClientHandler(clientSocket), "OPM-tcpip2-client").start();
                }
            } catch (IOException e) {
                if (listening) {
                    System.err.println("Error in server: " + e.getMessage());
                }
            }
        }, "OPM-tcpip2-listener").start();
    }

    // Method to stop listening
    public void stopListening() {
        listening = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
                System.out.println("Stopped listening on port " + PORT);
            } catch (IOException e) {
                System.err.println("Error closing server socket: " + e.getMessage());
            }
        }
    }

    
    
	
	public void processNewFile( String path, Parameter parameter) {
    	
		File saveFolder = new File(parameter.saveDir);
    	if ( !saveFolder.exists() ) saveFolder.mkdirs();
    	parameter.recursive = false;
    	
        try {
        	Deskew.processFile ( path, parameter);
			System.gc();
        } catch (Exception e) {
        	System.out.println( e.getMessage() );
        }
	}
	
	public void closeTimelapseImages() {
		String[] titles = WindowManager.getImageTitles();
		for (String title : titles) {
			if (title.endsWith( "projection" ))	//uniqueID )) 
				WindowManager.getImage(title).close();
		}
		closeAllTimeLapse = false;
		Utils.collectGarbage();
	}
	
	public void close() {
        super.close();
        instance = null;
        Prefs.saveLocation(LOC_KEY, getLocation());
        listening = false;
        IJ.log("OPM TCP/IP listener closed.");
    }

	
}

