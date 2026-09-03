package de.embl.iclm;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import ij.IJ;

/**
 * A per-operation {@code .log} file recording what an OPM command did and with which settings.
 *
 * <p>The file belongs to the operation, not to the installation: a Deskew Batch run logs into
 * its own result folder, a watcher into the folder it watches. That is the whole point of it -
 * when a run crashes or a result turns out wrong months later, the record sits next to the
 * data it describes. Nothing is ever written into the Fiji directory; when no operation
 * directory can be determined the log falls back to the system temporary directory, which is
 * disposable by definition and never pollutes an installation.
 *
 * <p>One file is opened once, however many {@code Log} objects name it. {@code FileHandler}
 * takes an exclusive {@code .lck} on the file it opens and quietly rolls to {@code .log.1} when
 * it cannot, so a second handler on the same path does not share the file - it splits the
 * record in two. Handlers are therefore shared by canonical path and reference counted, and
 * the file is closed and its lock released when the last user closes.
 */
public class Log {

	private static final String defaultName = "OPM_";

	/** Distinguishes the underlying JDK loggers so instances never share handler lists. */
	private static final AtomicLong SEQUENCE = new AtomicLong();

	/** One open handler per log file, shared by every Log naming it. */
	private static final Map<String, FileHandler> OPEN_FILES = new HashMap<String, FileHandler>();
	private static final Map<String, Integer> OPEN_FILE_USERS = new HashMap<String, Integer>();

	protected static Log instance;

	private final Logger logger;
	protected String logName = defaultName;
	protected String logPath;
	protected File logFile;
	private FileHandler fileHandler;
	private String fileHandlerKey;

	/**			Open a log file for one operation
	 *
	 * @param name				: operation name; an "OPM_" prefix is added when missing
	 * @param path				: log file, or a directory to put the log in; empty means temp
	 * @param syncFijiConsole	: also echo every entry to the console
	 */
	public Log (String name, String path, boolean syncFijiConsole) {
		logName = normalizeName ( name );
		/* A unique JDK logger per instance. Logger.getLogger caches by name, so reusing one
		 * would hand back a logger that already carries the previous instance's handlers. */
		logger = Logger.getLogger ( logName + "#" + SEQUENCE.incrementAndGet() );
		logger.setUseParentHandlers ( false );	// the file is the record; never spill to stderr

		logPath = prepareLogPath ( path, fileNameFor ( logName ) );
		attach ( logPath );

		if (syncFijiConsole) {
			ConsoleHandler console = new ConsoleHandler() {
				{ setOutputStream ( System.out ); }
			};
			console.setFormatter ( new SimpleFormatter() );
			logger.addHandler ( console );
		}
		instance = this;
	}

	/** Open a log file for one operation, echoing entries to the console. */
	public Log (String name, String path) {
		this ( name, path, true );
	}

	/** Open a log named after one operation, in the temporary directory. */
	public Log (String name) {
		this ( name, null, true );
	}

	/** Open this exact log file, creating it and its parent directories when missing. */
	public Log (File logFile) {
		this ( defaultName, logFile == null ? null : logFile.getAbsolutePath(), true );
	}

	/** Open a log for this operation, in the folder the operation writes its results to. */
	public Log (Parameter parameter) {
		this ( parameter == null ? defaultName : parameter.obj,
				operationDirectory ( parameter ), true );
	}

	/** Open a default log in the temporary directory. */
	public Log () {
		this ( defaultName, null, true );
	}

	/**			Flush and detach this log's handlers
	 * <p>		Only this instance's handlers are touched, and the log file is closed only when
	 * 			no other Log is still using it, so one command finishing cannot silently close
	 * 			the log another command is still writing to.
	 */
	public void close () {
		for (Handler handler : logger.getHandlers()) {
			handler.flush();
			logger.removeHandler ( handler );
			// A console handler wraps System.out, which must outlive this log.
			if (!(handler instanceof ConsoleHandler) && !(handler instanceof FileHandler)) handler.close();
		}
		detach();
	}

	/** The most recently opened log, or a new temporary-directory one when none was opened. */
	public static Log getInstance () {
		if (null == instance) return new Log();
		return instance;
	}

	/** Rename the operation this log describes; the file it writes to does not move. */
	public void setName (String name) {
		logName = normalizeName ( name );
	}

	public String getName () {
		return logName;
	}

	/**			Move this log to another file
	 * <p>		The previous file is released first. Opening the new handler while the old one
	 * 			still held the lock is what used to leave a {@code .log.1} beside the log and
	 * 			split the record across two files.
	 */
	public void setPath (String path) {
		String resolved = prepareLogPath ( path, fileNameFor ( logName ) );
		if (resolved.equals ( logPath ) && fileHandler != null) return;
		detach();
		logPath = resolved;
		attach ( logPath );
	}

	public String getPath () {
		return logPath;
	}

	/** Point {@code parameter.logPath} at a log inside the folder that operation works in. */
	public static void prepareLogPath (
			Parameter parameter
			) {
		if (parameter == null) return;
		parameter.logPath = prepareLogPath ( operationDirectory ( parameter ), "OPM_" + parameter.obj + ".log" );
	}

	/** Resolve a log file path, defaulting its name to {@code OPM.log}. */
	public static String prepareLogPath (
			String logPath
			) {
		return prepareLogPath ( logPath, "OPM.log" );
	}

	/**			Resolve a directory or file path to a usable log file path
	 * <p>		A path already ending in {@code .log} is the file. Anything else is treated as
	 * 			the directory to put {@code fileName} in, whether or not it exists yet - the
	 * 			previous version asked {@link File#isDirectory}, which is false for a result
	 * 			folder that has not been created, and so wrote {@code <folder>.log} beside the
	 * 			folder instead of a log inside it.
	 * <p>		Missing parent directories are created. Nothing here falls back to the ImageJ
	 * 			installation directory; an unusable path falls back to the temporary directory.
	 *
	 * @param logPath			: log file, or the directory to put the log in
	 * @param fileName			: file name to use when {@code logPath} names a directory
	 * <p>
	 * @return					: an absolute log file path whose parent directory exists
	 */
	public static String prepareLogPath (
			String logPath,
			String fileName
			) {
		String name = (fileName == null || fileName.trim().isEmpty()) ? "OPM.log" : fileName.trim();
		if (!name.toLowerCase().endsWith ( ".log" )) name += ".log";

		String path = logPath == null ? "" : logPath.trim();
		File file;
		if (path.isEmpty()) file = new File ( temporaryDirectory(), name );
		else if (path.toLowerCase().endsWith ( ".log" )) file = new File ( path );
		else file = new File ( path, name );

		try {
			File parent = file.getAbsoluteFile().getParentFile();
			if (parent != null && !parent.isDirectory() && !parent.mkdirs())
				throw new IOException ( "Could not create the log directory " + parent );
			file.getAbsoluteFile().createNewFile();	// already existing is success
			return file.getAbsolutePath();
		} catch (Exception unusable) {
			IJ.log ( "OPM log: cannot write to " + file + " (" + unusable.getMessage()
					+ "); using the temporary directory instead." );
			return new File ( temporaryDirectory(), name ).getAbsolutePath();
		}
	}

	/**			The folder an operation works in, used when no explicit log path was given
	 * <p>		An explicit log path wins, then the folder results are written to, then the
	 * 			input or watched folder. Only when none of those is known does the log go to
	 * 			the temporary directory.
	 */
	private static String operationDirectory (
			Parameter parameter
			) {
		if (parameter == null) return null;
		String[] candidates = { parameter.logPath, parameter.saveDir, parameter.inputDir, parameter.watchDir };
		for (String candidate : candidates)
			if (candidate != null && !candidate.trim().isEmpty()) return candidate.trim();
		return null;
	}

	private static String temporaryDirectory () {
		String temporary = System.getProperty ( "java.io.tmpdir" );
		return temporary == null || temporary.trim().isEmpty() ? "." : temporary;
	}

	private static String normalizeName (String name) {
		String result = (name == null || name.trim().isEmpty()) ? defaultName : name.trim();
		if (result.toLowerCase().endsWith ( ".log" )) result = result.substring ( 0, result.length() - 4 );
		return result.startsWith ( "OPM_" ) ? result : "OPM_" + result;
	}

	private static String fileNameFor (String name) {
		return name + ".log";
	}

	private void attach (String path) {
		try {
			logFile = new File ( path );
			fileHandlerKey = logFile.getCanonicalPath();
			fileHandler = acquire ( fileHandlerKey );
			logger.addHandler ( fileHandler );
		} catch (Exception failure) {
			// Logging must never take down the operation it is recording.
			IJ.log ( "OPM log: could not open " + path + ": " + failure.getMessage() );
			fileHandler = null;
			fileHandlerKey = null;
		}
	}

	private void detach () {
		if (fileHandler != null) {
			fileHandler.flush();
			logger.removeHandler ( fileHandler );
		}
		if (fileHandlerKey != null) release ( fileHandlerKey );
		fileHandler = null;
		fileHandlerKey = null;
	}

	private static synchronized FileHandler acquire (String key) throws IOException {
		FileHandler handler = OPEN_FILES.get ( key );
		if (handler == null) {
			handler = new FileHandler ( key, true );	// append: a log is a running record
			handler.setFormatter ( new SimpleFormatter() );
			OPEN_FILES.put ( key, handler );
			OPEN_FILE_USERS.put ( key, Integer.valueOf ( 0 ) );
		}
		OPEN_FILE_USERS.put ( key, Integer.valueOf ( OPEN_FILE_USERS.get ( key ).intValue() + 1 ) );
		return handler;
	}

	private static synchronized void release (String key) {
		Integer users = OPEN_FILE_USERS.get ( key );
		if (users == null) return;
		if (users.intValue() > 1) {
			OPEN_FILE_USERS.put ( key, Integer.valueOf ( users.intValue() - 1 ) );
			return;
		}
		OPEN_FILE_USERS.remove ( key );
		FileHandler handler = OPEN_FILES.remove ( key );
		if (handler != null) {	// closing releases the .lck file beside the log
			handler.flush();
			handler.close();
		}
	}

	/** Record one line. */
	public void add (String infoString) {
		logger.info ( infoString );
	}

	/** Record one formatted line. */
	public void add (String infoString, Object... args) {
		logger.info ( String.format ( infoString, args ) );
	}

	/**			Record every setting an operation ran with
	 * <p>		Declared fields, not public ones: the deskew and alignment matrices are
	 * 			{@code protected}, so a public-only walk silently omitted the two things most
	 * 			needed to explain a result. Arrays are printed by value rather than as the
	 * 			identity hash {@code toString} gives them.
	 */
	public void add (Parameter parameter) {
		if (null == parameter) {
			logger.info ( "Parameter : null" );
			return;
		}
		StringBuilder info = new StringBuilder ( "Parameter : " ).append ( parameter.obj );
		for (Field field : sortedFields ( parameter.getClass() )) {
			if (Modifier.isStatic ( field.getModifiers() ) || field.isSynthetic()) continue;
			info.append ( "\n " ).append ( field.getName() ).append ( " : " );
			try {
				field.setAccessible ( true );
				info.append ( describe ( field.get ( parameter ) ) );
			} catch (Throwable unreadable) {
				info.append ( "<unreadable: " ).append ( unreadable.getClass().getSimpleName() ).append ( ">" );
			}
		}
		logger.info ( info.toString() );
	}

	/** Record a list of strings. */
	public void add (String[] infoList) {
		logger.info ( infoList == null ? "String[] : null" : Arrays.toString ( infoList ) );
	}

	private static List<Field> sortedFields (Class<?> type) {
		List<Field> fields = new ArrayList<Field>();
		for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass())
			fields.addAll ( Arrays.asList ( current.getDeclaredFields() ) );
		return fields;
	}

	/** Arrays by value; an ImagePlus or ROI by its own toString; null as "null". */
	private static String describe (Object value) {
		if (value == null) return "null";
		if (value instanceof Object[]) return Arrays.deepToString ( (Object[]) value );
		if (value instanceof double[]) return Arrays.toString ( (double[]) value );
		if (value instanceof float[]) return Arrays.toString ( (float[]) value );
		if (value instanceof int[]) return Arrays.toString ( (int[]) value );
		if (value instanceof long[]) return Arrays.toString ( (long[]) value );
		if (value instanceof short[]) return Arrays.toString ( (short[]) value );
		if (value instanceof byte[]) return "byte[" + ((byte[]) value).length + "]";
		if (value instanceof boolean[]) return Arrays.toString ( (boolean[]) value );
		if (value instanceof char[]) return Arrays.toString ( (char[]) value );
		return String.valueOf ( value );
	}
}
