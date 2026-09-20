package de.embl.iclm;
import java.io.*;
import java.net.*;

import ij.IJ;
import ij.plugin.PlugIn;


public class TCPIP implements PlugIn {
	private Parameter parameter = null;
	private int port			= 5020;

    
    public void main(String[] args) throws IOException {
    }

	@Override
	public void run(String arg) {

		parameter = new Parameter("TCPIP");
		if ( !parameter.tcpip() ) return;
		
		boolean metadata_received = false;
		// check save folder path
		File saveFolder = new File(parameter.saveDir);
		if ( parameter.saveDir.equals("") || null == saveFolder ) {
			parameter.saveToSame = true;
		}
		
		
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

            }
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
