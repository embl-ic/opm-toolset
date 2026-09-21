package de.embl.iclm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * The party theme has to reach every command and every dialog, or it reaches none of them.
 *
 * <p>A theme that half the toolset follows looks like a bug rather than an easter egg, and the
 * two ways it goes missing are both invisible until somebody is looking at the right window at
 * the right hour: a command that never calls {@link Party#commandStarted} is never counted and
 * never rolls, and a dialog built as a plain {@code GenericDialog} cannot paint a rim because an
 * AWT dialog fills its own background in {@code paint} and offers nothing else to hang a
 * decoration off.
 *
 * <p>Both are checked against the real definitions - {@code plugins.config} for the commands,
 * the sources for the dialogs - rather than a list kept by hand, so a command or a dialog added
 * later is covered the day it is added.
 */
public class PartyCoverageTest {

	private static final String MENU = "Plugins>OPM Toolset";

	private static final File SOURCES = new File ( "src/main/java/de/embl/iclm" );

	/** The two classes whose whole purpose is to be the party-capable dialog. */
	private static final List<String> DIALOG_SUBCLASSES =
			Arrays.asList ( "PartyDialog.java", "PartyDialogPlus.java" );

	@Test
	public void everyMenuCommandTellsThePartyThemeThatItStarted () throws Exception {
		List<String> commands = menuClasses();
		assertTrue ( "the known commands should all be found, got " + commands,
				commands.size() >= 15 );

		List<String> silent = new ArrayList<String>();
		for (String className : commands)
			if (!classReferences ( className, "commandStarted" )) silent.add ( className );
		assertTrue ( "these commands never call Party.commandStarted, so they are missing from"
				+ " the 20-30% budget and can never bring the theme on: " + silent, silent.isEmpty() );
	}

	/**
	 * The environment report is the silent off switch, so its name has to be the one Party knows.
	 *
	 * <p>It passes {@link Party#ENVIRONMENT_REPORT} itself rather than a literal, which is what
	 * makes this impossible to get wrong - this test only pins that it stays that way.
	 */
	@Test
	public void theEnvironmentReportIsStillTheSessionSwitch () throws Exception {
		String source = source ( "Debug.java" );
		assertTrue ( "Debug should pass Party.ENVIRONMENT_REPORT, not a literal",
				source.contains ( "Party.commandStarted ( Party.ENVIRONMENT_REPORT )" ) );
		assertTrue ( "and it must be the first thing it does, before the report is built",
				source.indexOf ( "Party.commandStarted" )
						< source.indexOf ( "OPM Toolset environment report" ) );
	}

	@Test
	public void everyDialogIsOneThatCanWearTheTheme () throws Exception {
		List<String> plain = new ArrayList<String>();
		Pattern bare = Pattern.compile (
				"new\\s+(GenericDialogPlus|NonBlockingGenericDialog|GenericDialog)\\s*\\(" );
		for (File file : sources()) {
			if (DIALOG_SUBCLASSES.contains ( file.getName() )) continue;
			Matcher found = bare.matcher ( withoutComments ( read ( file ) ) );
			while (found.find()) plain.add ( file.getName() + ": new " + found.group ( 1 ) );
		}
		assertTrue ( "these dialogs are built from a class that cannot paint a rim; use"
				+ " PartyDialog (non-blocking) or PartyDialogPlus instead: " + plain,
				plain.isEmpty() );
	}

	/** And the toolset really does build them: the swap above must not have emptied the set. */
	@Test
	public void theToolsetStillBuildsDialogs () throws Exception {
		int dialogs = 0;
		Pattern party = Pattern.compile ( "new\\s+PartyDialog(Plus)?\\s*\\(" );
		for (File file : sources()) {
			Matcher found = party.matcher ( withoutComments ( read ( file ) ) );
			while (found.find()) dialogs++;
		}
		assertTrue ( "only " + dialogs + " party-capable dialogs found; the toolset has more"
				+ " than that, so something has stopped matching", dialogs >= 25 );
	}

	/**
	 * Every Swing window of the toolset hands itself to {@link Party#decorate}.
	 *
	 * <p>A {@code GenericDialog} gets the theme by being a {@link PartyDialog}; a Swing window
	 * gets it by being decorated, which is also what reserves the margin the rim needs. Left
	 * out, the window is the one blue thing on a pink desktop.
	 */
	@Test
	public void everySwingWindowIsDecorated () throws Exception {
		List<String> windows = Arrays.asList ( "Live2.java", "OpmDataViewer.java",
				"LiveSetupDialog.java", "ChannelAlignment.java", "TerminateBatch.java" );
		List<String> undecorated = new ArrayList<String>();
		for (String name : windows)
			if (!read ( new File ( SOURCES, name ) ).contains ( "Party.decorate" ))
				undecorated.add ( name );
		assertTrue ( "these windows never reserve the rim or follow the theme: " + undecorated,
				undecorated.isEmpty() );
	}


	// ---- helpers ---------------------------------------------------------------------

	/** Every class registered anywhere under Plugins>OPM Toolset, read from the real menu. */
	private static List<String> menuClasses () throws Exception {
		String config = resource ( "/plugins.config" );
		List<String> classes = new ArrayList<String>();
		for (String line : config.split ( "\r?\n" )) {
			String trimmed = line.trim();
			if (trimmed.startsWith ( "#" ) || !trimmed.startsWith ( MENU )) continue;
			int comma = trimmed.lastIndexOf ( ',' );
			if (comma < 0) continue;
			String target = trimmed.substring ( comma + 1 ).trim();
			int bracket = target.indexOf ( '(' );		// strip a ("arg") suffix
			if (bracket > 0) target = target.substring ( 0, bracket ).trim();
			if (!target.isEmpty() && !classes.contains ( target )) classes.add ( target );
		}
		return classes;
	}

	/** Whether the compiled class names this method anywhere in its constant pool. */
	private static boolean classReferences (String className, String method) throws Exception {
		byte[] bytes = resourceBytes ( "/" + className.replace ( '.', '/' ) + ".class" );
		assertNotNull ( "compiled class missing: " + className, bytes );
		return new String ( bytes, "ISO-8859-1" ).contains ( method );
	}

	private static List<File> sources () {
		assertTrue ( "run from the project directory: " + SOURCES.getAbsolutePath(),
				SOURCES.isDirectory() );
		List<File> java = new ArrayList<File>();
		File[] children = SOURCES.listFiles();
		if (children != null) for (File child : children)
			if (child.getName().endsWith ( ".java" )) java.add ( child );
		assertFalse ( "no sources found", java.isEmpty() );
		return java;
	}

	private static String source (String name) throws Exception {
		return read ( new File ( SOURCES, name ) );
	}

	/** Block comments only: the archive in {@code Debug} keeps whole dialogs commented out. */
	private static String withoutComments (String java) {
		return java.replaceAll ( "(?s)/\\*.*?\\*/", "" ).replaceAll ( "(?m)//.*$", "" );
	}

	private static String read (File file) throws Exception {
		InputStream in = new FileInputStream ( file );
		try {
			return new String ( drain ( in ), "UTF-8" );
		} finally {
			in.close();
		}
	}

	private static String resource (String path) throws Exception {
		return new String ( resourceBytes ( path ), "UTF-8" );
	}

	private static byte[] resourceBytes (String path) throws Exception {
		InputStream in = PartyCoverageTest.class.getResourceAsStream ( path );
		assertNotNull ( "resource missing: " + path, in );
		try {
			return drain ( in );
		} finally {
			in.close();
		}
	}

	private static byte[] drain (InputStream in) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		for (int read; ( read = in.read ( buffer ) ) > 0; ) out.write ( buffer, 0, read );
		return out.toByteArray();
	}
}
