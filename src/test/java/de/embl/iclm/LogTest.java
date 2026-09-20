package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import ij.IJ;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The log has to end up beside the data it describes.
 *
 * <p>Every one of these covers a way the previous version put it somewhere else: in the Fiji
 * installation directory, in whatever folder Fiji happened to be launched from, or beside the
 * result folder rather than inside it.
 */
public class LogTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static String read(File file) throws Exception {
		List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
		StringBuilder text = new StringBuilder();
		for (String line : lines) text.append(line).append('\n');
		return text.toString();
	}

	/**
	 * Opening a log must not close the process's standard streams.
	 *
	 * <p>It did. The console echo was a {@code ConsoleHandler} redirected with
	 * {@code setOutputStream(System.out)}, and {@code StreamHandler.setOutputStream} flushes and
	 * closes the stream already attached - which {@code ConsoleHandler}'s constructor had set to
	 * {@code System.err}. In Fiji that stream is SciJava's console stream: once closed, its parent
	 * is null, {@code DefaultConsoleService.dispose} throws on quit, and Fiji stays open with its
	 * main window up. Reproduced by starting and stopping Live Deskew; every command opening a
	 * {@code Log} did the same.
	 */
	@Test
	public void openingALogLeavesTheStandardStreamsOpen() throws Exception {
		java.io.PrintStream originalErr = System.err;
		java.io.PrintStream originalOut = System.out;
		final boolean[] closed = new boolean[2];
		java.io.ByteArrayOutputStream echoed = new java.io.ByteArrayOutputStream();
		System.setErr(new java.io.PrintStream(new java.io.ByteArrayOutputStream()) {
			@Override public void close() { closed[0] = true; super.close(); }
		});
		System.setOut(new java.io.PrintStream(echoed, true) {
			@Override public void close() { closed[1] = true; super.close(); }
		});
		try {
			Log log = new Log("streams", folder.getRoot().getAbsolutePath(), true);
			log.add("echoed to the console");
			log.close();
			assertFalse("System.err was closed", closed[0]);
			assertFalse("System.out was closed", closed[1]);
			assertTrue("the echo still reaches the console",
					new String(echoed.toByteArray(), StandardCharsets.UTF_8).contains("echoed to the console"));
		} finally {
			System.setErr(originalErr);
			System.setOut(originalOut);
		}
	}

	@Test
	public void logsIntoTheOperationFolderAndNeverIntoTheImageJDirectory() throws Exception {
		File results = new File(folder.getRoot(), "results");
		Log log = new Log("batch", results.getAbsolutePath());
		try {
			log.add("processing started");
			File written = new File(log.getPath());
			assertEquals(results.getCanonicalFile(), written.getParentFile().getCanonicalFile());
			assertTrue(written.isFile());
			assertTrue(read(written).contains("processing started"));

			String imageJDirectory = IJ.getDir("imagej");
			assertFalse("a log must never be written into the ImageJ installation directory",
					written.getCanonicalPath().startsWith(new File(imageJDirectory).getCanonicalPath()));
		} finally {
			log.close();
		}
	}

	@Test
	public void resolvesADirectoryThatDoesNotExistYetIntoAFileInsideIt() throws Exception {
		/* isDirectory() is false for a result folder the run has not created yet, which is how
		 * the log used to end up as a sibling file called "<folder>.log". */
		File notYet = new File(folder.getRoot(), "not-created-yet");
		assertFalse(notYet.exists());

		File resolved = new File(Log.prepareLogPath(notYet.getAbsolutePath(), "OPM_batch.log"));
		assertEquals("OPM_batch.log", resolved.getName());
		assertEquals(notYet.getCanonicalFile(), resolved.getParentFile().getCanonicalFile());
		assertTrue("the parent directory must be created", notYet.isDirectory());
		assertTrue(resolved.isFile());
	}

	@Test
	public void anEmptyPathFallsBackToTheTemporaryDirectoryHoweverItWasBuilt() throws Exception {
		/* Parameter.logPath comes back from SciJava preferences, so it is never the interned
		 * literal the old "" == path test compared against; that path produced a file called
		 * ".log" in whatever directory Fiji was launched from. */
		File temporary = new File(System.getProperty("java.io.tmpdir")).getCanonicalFile();
		for (String empty : new String[] { "", new String(""), "   ", null }) {
			File resolved = new File(Log.prepareLogPath(empty, "OPM_probe.log")).getCanonicalFile();
			assertEquals("for path " + (empty == null ? "null" : "\"" + empty + "\""),
					temporary, resolved.getParentFile());
		}
		assertFalse("no stray \".log\" in the working directory",
				new File(System.getProperty("user.dir"), ".log").isFile());
	}

	@Test
	public void theObjectDescribesTheFileItActuallyWritesTo() throws Exception {
		/* Every delegating constructor used to call new Log(...) instead of this(...), leaving
		 * the caller holding an object whose name and path described a different file. */
		File wanted = new File(folder.getRoot(), "explicit");
		Log log = new Log("explicit-run", wanted.getAbsolutePath());
		try {
			assertEquals("OPM_explicit-run", log.getName());
			assertEquals(new File(wanted, "OPM_explicit-run.log").getCanonicalPath(),
					new File(log.getPath()).getCanonicalPath());
			assertSame(log, Log.getInstance());

			log.add("through the object the caller holds");
			assertTrue(read(new File(log.getPath())).contains("through the object"));
		} finally {
			log.close();
		}
	}

	@Test
	public void aNameArgumentIsHonouredRatherThanDiscarded() throws Exception {
		Log log = new Log("OPM_watcher.log");
		try {
			assertEquals("OPM_watcher", log.getName());
			assertEquals("OPM_watcher.log", new File(log.getPath()).getName());
			assertNotEquals("OPM.log", new File(log.getPath()).getName());
		} finally {
			log.close();
		}
	}

	@Test
	public void twoLogsOnOnePathShareOneFileInsteadOfSplittingIt() throws Exception {
		/* A second FileHandler cannot take the .lck and silently rolls to "<name>.log.1", so
		 * the record used to end up split across two files. */
		File target = new File(folder.getRoot(), "shared");
		Log first = new Log("shared", target.getAbsolutePath());
		Log second = new Log("shared", target.getAbsolutePath());
		File file = new File(first.getPath());
		try {
			first.add("from the first log");
			second.add("from the second log");
			assertFalse("a rolled-over second file means the record was split",
					new File(file.getParentFile(), file.getName() + ".1").exists());
			String text = read(file);
			assertTrue(text, text.contains("from the first log"));
			assertTrue(text, text.contains("from the second log"));
		} finally {
			first.close();
			second.close();
		}
	}

	@Test
	public void closingReleasesTheLockAndAReopenedLogAppends() throws Exception {
		File target = new File(folder.getRoot(), "reopened");
		Log first = new Log("reopened", target.getAbsolutePath());
		first.add("first run");
		File file = new File(first.getPath());
		first.close();
		assertFalse("the .lck must be released on close",
				new File(file.getParentFile(), file.getName() + ".lck").exists());

		Log second = new Log("reopened", target.getAbsolutePath());
		try {
			second.add("second run");
			String text = read(file);
			assertTrue("an earlier run must survive", text.contains("first run"));
			assertTrue(text.contains("second run"));
		} finally {
			second.close();
		}
	}

	@Test
	public void movingTheLogKeepsWhatWasAlreadyWrittenAndOpensNoSecondFile() throws Exception {
		File first = new File(folder.getRoot(), "before");
		File second = new File(folder.getRoot(), "after");
		Log log = new Log("moving", first.getAbsolutePath());
		try {
			log.add("written before the move");
			File before = new File(log.getPath());
			log.setPath(second.getAbsolutePath());
			log.add("written after the move");
			File after = new File(log.getPath());

			assertNotEquals(before.getCanonicalPath(), after.getCanonicalPath());
			assertEquals(second.getCanonicalFile(), after.getParentFile().getCanonicalFile());
			assertTrue(read(before).contains("written before the move"));
			assertFalse(read(before).contains("written after the move"));
			assertTrue(read(after).contains("written after the move"));
			assertFalse(new File(before.getParentFile(), before.getName() + ".1").exists());
			assertFalse("the abandoned file must not stay locked",
					new File(before.getParentFile(), before.getName() + ".lck").exists());
		} finally {
			log.close();
		}
	}

	@Test
	public void recordsTheMatricesThatExplainWhatTheOperationDid() throws Exception {
		/* getFields() is public-only, and deskewMatrix and alignMatrix are protected, so the
		 * two things most needed to reconstruct a result were the two silently omitted. */
		Parameter parameter = new Parameter("log-test");
		parameter.xyPixelSize = 0.116;
		parameter.alignMatrix = new double[][] { { 1, 0, 11.6 }, { 0, 1, -7.5 } };

		Log log = new Log("params", folder.getRoot().getAbsolutePath());
		String text;
		try {
			log.add(parameter);
		} finally {
			log.close();
		}
		text = read(new File(log.getPath()));
		assertTrue(text, text.contains("xyPixelSize : 0.116"));
		assertTrue(text, text.contains("alignMatrix : [[1.0, 0.0, 11.6], [0.0, 1.0, -7.5]]"));
		assertTrue(text, text.contains("deskewMatrix : [["));
	}

	@Test
	public void acceptsALogFileThatDoesNotExistYet() throws Exception {
		/* The File constructor returned early for anything that did not already exist, which
		 * is every brand new log. */
		File target = new File(new File(folder.getRoot(), "fresh"), "OPM_fresh.log");
		assertFalse(target.exists());
		Log log = new Log(target);
		try {
			log.add("a new log file");
			assertTrue(target.isFile());
			assertTrue(read(target).contains("a new log file"));
		} finally {
			log.close();
		}
	}
}
