package de.embl.iclm;
import java.io.*;
import java.net.*;
//import java.nio.file.Paths;
//import java.util.ListIterator;

import ij.IJ;
import ij.plugin.PlugIn;


public class TCPIP implements PlugIn {
	private Parameter parameter = null;
	private int port			= 5020;
	//private double xyPixelSize	= 116.0d;
	//private double zStepSize	= 132.5d;
	//private double opmAngle		= 25.0d;
	//File saveFolder				= null;

    
    public void main(String[] args) throws IOException {
    	/*
        Socket socket = new Socket("localhost", port);
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        out.writeUTF("Hello Server");
        socket.close();
        */
    }

	@Override
	public void run(String arg) {

		parameter = new Parameter("TCPIP");
		if ( !parameter.tcpip() ) return;
		
		boolean metadata_received = false;
		// check save folder path
		File saveFolder = new File(parameter.saveDir);
		if ( parameter.saveDir.equals("") || null == saveFolder ) {
			//parameter.saveDir = parameter.inputDir + File.separator + "result";
			parameter.saveToSame = true;
		}
		// port = parameter.port;
		
		
		ServerSocket serverSocket;
		try {
			serverSocket = new ServerSocket( port );
			IJ.log( "Start listening on port: " + port);
			
			int count = 0;
			
			while (true) {
				
				System.out.println("iter count: " + count++);
				
                Socket socket = serverSocket.accept();
                
                System.out.println("Connection received from: " + socket.getInetAddress());

                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String filePath = reader.readLine();
                IJ.log ( "Received file path: " + filePath );
                
                
                
                if ( metadata_received ) {
                	
                	if ( filePath.endsWith(".tiff") || filePath.endsWith(".tif") ) {
                		File tif_file = new File(filePath);
                		if ( !tif_file.exists() ) {
                			System.out.println("tif File not exist");
                			continue;
                		}
                		
                		if (parameter.saveToSame)
                			parameter.saveDir = tif_file.getParentFile().toString() + File.separator + "result";
                		
                		processNewFile ( filePath, parameter );
                		
                	} else {
                		System.out.println("Received file is not TIF image.");
                	}
                	
                } else {
                	
                	if ( filePath.endsWith("ExperimentalParameters.txt") ) {
                    	//int idx = filePath.indexOf( "ExperimentalParameters.txt" );
                    	//String metaPath = filePath.substring(0, idx) + "ExperimentalParameters.txt";
                    	if ( !(new File(filePath)).exists() ) {
                    		System.out.println("txt File not exist.");
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
                
                	
                // Parse and process the file path here
                //System.out.printf("\n\tProcessing file:%s with:\n", filePath);
                //System.out.printf("\n\t:xy pixel size: %f, z step size: %f, opm angle: %f", 
                //	parameter.xyPixelSize, parameter.zStepSize, parameter.opmAngle);
                //System.out.printf("\n\tand save result to: %s\n", parameter.saveDir);

                //
            }
			//socket.close();
		} catch ( Exception e ) {
			// TODO Auto-generated catch block
			System.out.println(e.getMessage()) ; //e.printStackTrace();
		}

		
	}
	
	public void processNewFile( String path, Parameter parameter) {
    	File saveFolder = new File(parameter.saveDir);
    	if ( !saveFolder.exists() ) saveFolder.mkdirs();

        try {
        	Deskew.processFile ( path, parameter);
			System.gc();
        } catch (Exception e) {
        	System.out.println( e.getMessage() );
        }
	}

	
}
