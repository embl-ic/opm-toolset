package de.embl.iclm;

import java.io.File;
import java.lang.reflect.Field;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.LogManager;

import ij.IJ;

public class Log {
	// Log class parameters
	protected static Logger logger;
	
	private static final String defaultName = "OPM_";
	protected String logName = defaultName;
	
	//System.getProperty("java.io.tmpdir") + File.separator + "OPM.log";
	private static final String defaultPath = IJ.getDir("imagej") + "OPM.log"; //ImageJ getDir return with File.separator in the end
	protected String logPath = defaultPath;
	
	protected static File logFile = null;
	protected static Log instance;
	
	
	/**
	 * 
	 * @param logName
	 * @param logPath
	 */
	public Log (String name, String path, boolean syncFijiConsole) {
		// get current instantiated object
		instance = this;
		
		// parse logger name, add "OPM" prefix
		if (null == name || name.equals("")) name = defaultName;
		if (!name.startsWith("OPM_")) name = "OPM_" + name;
		logName = name;
		
		logger = Logger.getLogger(logName);
		
		// parse and prepare log file path
		if (!name.endsWith(".log")) name += ".log";	// add .log extension to name for the log file path
		if (null == path || path.equals("")) path = IJ.getDir("imagej") + name;
		
		// check file associated with path
		if (!path.endsWith(".log")) {	// path is not yet a log file path, assume to be a directory
			if (!path.endsWith(File.separator)) path += File.separator;
			path += name;
		}	// else path is already appear to be a log file path
		logPath = path;	// now logPath is supposed to be the full path of log file
		
		try {	// try to create the log file
			logFile = new File(logPath);
			if (logFile.createNewFile())
				System.out.println(" log file created: " + logFile.getAbsolutePath());
			else
				System.out.println(" log file already exists: " + logFile.getAbsolutePath());
			
			FileHandler handler = new FileHandler(logPath, true);
			logger.addHandler(handler);
	        SimpleFormatter formatter = new SimpleFormatter();
	        handler.setFormatter(formatter); 
	        //logger.setUseParentHandlers(syncFijiConsole);
	        if (syncFijiConsole) {
		        logger.setUseParentHandlers(false);
		        logger.addHandler(new ConsoleHandler() {
		            {setOutputStream(System.out);}
		        });
	        }
	    } catch (Exception e) {
	    	System.out.println("debug log 79");
	    	System.out.println(e.getMessage());
	    	System.out.println("debug log 81");
	    }
		
	}
	public Log (String name, String path) {
		new Log ( name, path, true );	// by default synchronize Log info to Fiji console
	}
	
	/**
	 * 
	 * @param logName
	 */
	public Log (String logName) {
		//instance = this;
		new Log(this.logName, this.logPath);
	}
	
	/**
	 * 
	 * @param logFile
	 */
	public Log (File logFile) {
		if (null==logFile || !logFile.exists() && !logFile.isFile())
			return;
		new Log(this.logName, logFile.getAbsolutePath());
	}
	
	/**
	 * 
	 * @param parameter
	 */
	public Log (Parameter parameter) {
		new Log (parameter.obj, parameter.logPath);
	}
	
	public Log () {
		new Log(this.logName, this.logPath);
	}
	
	public void close () {
		for (Handler handler : logger.getHandlers()) {
			handler.flush();
			handler.close();
			logger.removeHandler(handler);
		}
	}
	
	/**
	 * 
	 * @return
	 */
	public static Log getInstance () {
		if (null == instance)
			return new Log();
		else
			return (Log) instance;
	}
	
	
	
	
	/**
	 * 
	 * @param name
	 */
	public void setName (String name) {
		logName = name;
		logger = Logger.getLogger(logName);
	}
	public String getName () {
		if ( null != getInstance() ) return this.logName;
		else return null;
	}
	
	
	/**
	 * 
	 * @param path
	 */
	public void setPath (String path) {
		if (!path.endsWith(".log")) path += ".log";
		logPath = path;
		try {	// try to create the log file
			logFile = new File(path);
			if (logFile.createNewFile())
				System.out.println(" log file created: " + logFile.getAbsolutePath());
			else
				System.out.println(" log file already exists: " + logFile.getAbsolutePath());
			FileHandler handler = new FileHandler(path);
			logger.addHandler(handler);
	        SimpleFormatter formatter = new SimpleFormatter();  
	        handler.setFormatter(formatter);
	        logger.setUseParentHandlers(false);
	    } catch (Exception e) {
	    	System.out.println(e.getMessage());
	    }
	}
	public String getPath () {
		if ( null != getInstance() ) return this.logPath;
		else return null;
	}
	
	
	/**
	 * 
	 * @param parameter
	 */
	public static void prepareLogPath (
			Parameter parameter
			) {
		parameter.logPath = prepareLogPath ( parameter.logPath, "_" + parameter.obj );
	}
	/**
	 * 
	 * @param logPath
	 * @return
	 */
	public static String prepareLogPath (
			String logPath
			) {
		return prepareLogPath (logPath, "");
	}
	/**
	 * 
	 * @param logPath
	 * @param name
	 * @return
	 */
	public static String prepareLogPath (
			String logPath, 
			String name
			) {
		String path = logPath;
		if (null == path || "" == path) path = defaultPath; // log path is empty
		try {	// file could be folder, file, or not exist (file case)
			File logDir = new File( path );
			if ( logDir.isDirectory() ) path += File.separator + "OPM" + name + ".log";
			if ( !path.endsWith( ".log" ) ) path += ".log";
			// now path should be the log file path
			logDir = new File( path );
			// file not exist, and create file failed
			if ( logDir.createNewFile() ) { } // file either created, or already exist
		} catch ( Exception e ) {
			path = defaultPath;
			System.out.println( e.getMessage() );
		}
		return path;
	}
	
	/**
	 * 
	 * @param info
	 */
	public void add (String infoString) {
		logger.info(infoString);
	}
	
	/**
	 * 
	 * @param infoString
	 * @param args
	 */
	public void add (String infoString, Object... args) {
		logger.info(String.format(infoString, args));
	}

	/**
	 * 
	 * @param parameter
	 */
	public void add (Parameter parameter) {
		if (null == parameter) {
			logger.info("Parameter : null");
			return;
		}
		String infoString = "Parameter : " + parameter.obj;
		for (Field f : parameter.getClass().getFields()) {
		    String name = f.getName();
		    String value = "null";
		    try {
				value = (f.get(parameter)).toString();
			} catch (Exception e) {
				value = e.getMessage();
				//continue;
			}
		    infoString += "\n " + name + " : " + value;
		}
		//TODO logger is null at this location
		logger.info(infoString);
	}
	
	/**
	 * 
	 * @param infoList
	 */
	public void add (String[] infoList) {
		String infoString = "String[]{ ";
		for (String info : infoList) {
			infoString += info + ", ";
		}
		infoString = infoString.substring(0, infoString.length()-2) + " }";
		logger.info(infoString);
		//this.logger.info(infoString);
	}
	
}