package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.Assume;
import org.junit.Test;

/**
 * The manual's built pages are current against their sources, carry no build marker, and every local link and fragment in
 * them resolves: {@code docs/manual/tools/build_manual.py --check}. Needs Python 3 on the PATH, and is skipped without it.
 */
public class ManualBuildTest {

	@Test
	public void theBuiltManualIsCurrentAndItsLinksResolve() throws Exception {
		File script = new File("docs/manual/tools/build_manual.py");
		Assume.assumeTrue("run from the source tree", script.isFile());
		String python = python();
		Assume.assumeTrue("needs Python 3 on the PATH", python != null);
		Process p = new ProcessBuilder(python, "-I", script.getPath(), "--check").redirectErrorStream(true).start();
		String out = read(p.getInputStream());
		assertTrue("the check did not finish", p.waitFor(120, TimeUnit.SECONDS));
		assertEquals(out, 0, p.exitValue());
	}

	private static String python() {
		for (String name : new String[] { "python3", "python" }) {
			try {
				Process p = new ProcessBuilder(name, "--version").redirectErrorStream(true).start();
				String version = read(p.getInputStream());
				if (p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0 && version.startsWith("Python 3")) return name;
			} catch (IOException | InterruptedException absent) {
				// the next name, or none
			}
		}
		return null;
	}

	private static String read(InputStream in) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		for (int n; (n = in.read(buffer)) > 0;)
			bytes.write(buffer, 0, n);
		return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
	}
}
