package de.embl.iclm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Every long-running Batch Processing command has to be terminable.
 *
 * <p>{@code Batch Processing > Terminate...} can only list what registered itself, and a
 * command registers by running its work through {@link Shutdown#runCancellable}. A command that
 * forgets is not merely absent from the list - it is unstoppable, which is the state all of
 * these were in: no progress, no controls, and no way out but killing Fiji.
 *
 * <p>Checked against {@code plugins.config} rather than a hand-written list, so a command added
 * to the menu later is covered the day it is added rather than the day someone remembers.
 *
 * <p>Read from the compiled class rather than the source: what matters is that the call is
 * really there in the artefact that ships, and a constant-pool entry is the cheapest honest
 * evidence of that.
 */
public class BatchTerminationCoverageTest {

	private static final String MENU = "Plugins>OPM Toolset>Batch Processing,";

	/** The dialog that does the terminating is in this menu but is not itself a long run. */
	private static final List<String> EXEMPT =
			Arrays.asList("de.embl.iclm.TerminateBatch");

	@Test
	public void everyBatchProcessingCommandRegistersItselfAsCancellable() throws Exception {
		List<String> commands = batchProcessingClasses();
		assertFalse("plugins.config should list Batch Processing commands", commands.isEmpty());
		assertTrue("the known commands should all be found, got " + commands,
				commands.size() >= 5);

		List<String> unstoppable = new ArrayList<String>();
		for (String className : commands) {
			if (EXEMPT.contains(className)) continue;
			if (!classReferences(className, "runCancellable")) unstoppable.add(className);
		}
		assertTrue("these Batch Processing commands cannot be terminated, because they never"
				+ " run their work through Shutdown.runCancellable: " + unstoppable,
				unstoppable.isEmpty());
	}

	@Test
	public void everyCancellableCommandAlsoChecksWhetherItShouldStop() throws Exception {
		/* Registering without checkpoints would be worse than not registering: Terminate would
		 * list the run and appear to stop it while it carried on to the end. */
		List<String> deaf = new ArrayList<String>();
		for (String className : batchProcessingClasses()) {
			if (EXEMPT.contains(className)) continue;
			if (!classReferences(className, "stopping")) deaf.add(className);
		}
		assertTrue("these commands register but never poll Shutdown.stopping(), so terminating"
				+ " them would not actually stop anything: " + deaf, deaf.isEmpty());
	}


	// ---- helpers ---------------------------------------------------------------------

	/** The classes registered under Batch Processing, read from the real menu definition. */
	private static List<String> batchProcessingClasses() throws Exception {
		String config = resource("/plugins.config");
		List<String> classes = new ArrayList<String>();
		for (String line : config.split("\r?\n")) {
			String trimmed = line.trim();
			if (trimmed.startsWith("#") || !trimmed.startsWith(MENU)) continue;
			int comma = trimmed.lastIndexOf(',');
			if (comma < 0) continue;
			String target = trimmed.substring(comma + 1).trim();
			int bracket = target.indexOf('(');	// strip a ("arg") suffix
			if (bracket > 0) target = target.substring(0, bracket).trim();
			if (!target.isEmpty() && !classes.contains(target)) classes.add(target);
		}
		return classes;
	}

	/** Whether the compiled class names this method anywhere in its constant pool. */
	private static boolean classReferences(String className, String method) throws Exception {
		byte[] bytes = resourceBytes("/" + className.replace('.', '/') + ".class");
		assertNotNull("compiled class missing: " + className, bytes);
		return new String(bytes, "ISO-8859-1").contains(method);
	}

	private static String resource(String path) throws Exception {
		return new String(resourceBytes(path), "UTF-8");
	}

	private static byte[] resourceBytes(String path) throws Exception {
		InputStream in = BatchTerminationCoverageTest.class.getResourceAsStream(path);
		assertNotNull("resource missing: " + path, in);
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			for (int read; (read = in.read(buffer)) > 0; ) out.write(buffer, 0, read);
			return out.toByteArray();
		} finally {
			in.close();
		}
	}
}
