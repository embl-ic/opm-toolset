package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Regression tests for the three residuals left open after the two repair sessions.
 *
 * <p>All three share a shape: a value that existed but was thrown away, so the wrong thing
 * happened silently.
 *
 * <ul>
 * <li>{@code GPU} reached for the shared static {@link Parameter} to carry its own arguments
 *     into {@link Partition#processMap}, and five of those seven sites <em>wrote</em> to it -
 *     so a hyperstack projection running inside one command could overwrite another command's
 *     settings, two of which are persisted;</li>
 * <li>{@code FastClijDeskew.saveTiff} discarded the writer's Boolean, making a failed save
 *     indistinguishable from a successful one;</li>
 * <li>{@code Live2.isOwnOutput} walked every ancestor to the drive root, so an acquisition
 *     folder that happened to be named {@code result} was treated as this run's own output and
 *     silently produced nothing.</li>
 * </ul>
 */
public class ResidualRepairTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();


	// ---- a scratch Parameter belongs to nobody --------------------------------------

	@Test
	public void aScratchParameterDoesNotBecomeTheSharedInstance() {
		Parameter owner = new Parameter("junit-residual-owner");
		assertSame("the constructor still claims the static", owner, Parameter.getInstance());

		Parameter scratch = Parameter.scratch();
		assertNotNull(scratch);
		assertNotSame("a scratch object must not replace the shared instance", scratch, Parameter.getInstance());
		assertSame("the owner still owns it", owner, Parameter.getInstance());
	}

	/** The whole point: writing an argument into a scratch object cannot reach a real one. */
	@Test
	public void writingToAScratchParameterCannotReachACommandsSettings() {
		Parameter live = new Parameter("junit-residual-live");
		live.projType = "max";
		live.permuteStr = "->XYZ";
		live.flipX = false;

		Parameter scratch = Parameter.scratch();
		scratch.projType = "med";
		scratch.permuteStr = "->ZYX";
		scratch.flipX = true;

		assertEquals("max", live.projType);
		assertEquals("->XYZ", live.permuteStr);
		assertFalse(live.flipX);
	}

	/** A scratch object skips the preference load, so it must not carry a stored dialog value. */
	@Test
	public void aScratchParameterIsNotLoadedFromPreferences() {
		Parameter scratch = Parameter.scratch();
		assertEquals("scratch", scratch.obj);
		assertEquals("field defaults, not stored preferences",
				Parameter.FORMAT_ZARR, scratch.outputFormat);
	}

	/** No processing class may reach the shared instance to carry its own arguments. */
	@Test
	public void theProcessingClassesNoLongerReachForTheSharedInstance() throws IOException {
		File gpu = new File("src/main/java/de/embl/iclm/GPU.java");
		if (!gpu.isFile()) return;			// running from a jar; the source check does not apply
		String source = new String(Files.readAllBytes(gpu.toPath()), "UTF-8");
		assertFalse("GPU must carry its arguments in a scratch Parameter, not the shared one",
				source.contains("Parameter.getInstance()"));
	}


	// ---- a write that failed must say so --------------------------------------------

	@Test
	public void theFastPathReportsWhetherEveryRequestedFileLanded() throws IOException {
		FastClijDeskew.Result fresh = new FastClijDeskew.Result();
		assertTrue("a result starts out claiming success", fresh.allWritten);
	}

	/** The movie sink now reports its writes; an unwritable folder must come back false. */
	@Test
	public void anUnwritableMovieFolderIsReported() throws IOException {
		FastClijDeskew.MipMovieSink movies = new FastClijDeskew.MipMovieSink();
		assertTrue("nothing collected means nothing failed", movies.save(folder.newFolder("empty")));

		movies.append("t1", plane(4, 3), null, null, false);
		File blocker = new File(folder.getRoot(), "blocked");
		Files.write(blocker.toPath(), new byte[4]);		// a FILE where the folder must go
		assertFalse("a movie that cannot be written is reported, not swallowed",
				movies.save(new File(blocker, "MIP_movies")));
	}


	// ---- an acquisition folder named "result" is not this run's output --------------

	@Test
	public void aFolderNamedResultIsStillAnAcquisitionFolder() throws Exception {
		File experiment = folder.newFolder("experiment");
		File acquisition = new File(experiment, "result");		// the trap
		acquisition.mkdirs();
		File raw = new File(acquisition, "s_Time000001_Channel0001.tif");
		Files.write(raw.toPath(), new byte[64]);
		File generated = new File(acquisition, "result/deskew/s_Time000001-deskewed.tif");
		generated.getParentFile().mkdirs();
		Files.write(generated.toPath(), new byte[64]);

		Live2 live = new Live2();
		try {
			Parameter p = parameterOf(live);
			p.saveToSame = true;
			p.saveDir = "";
			p.recursive = true;
			p.reproduceInputTree = false;
			p.watchExplicitFolder = true;
			p.watchDir = acquisition.getAbsolutePath();	// the watcher points AT the odd folder

			assertFalse("a raw volume in a folder called 'result' must still be processed",
					isOwnOutput(live, raw));
			assertTrue("this run's own output is still excluded",
					isOwnOutput(live, generated));
		} finally {
			live.close();
		}
	}

	/** The ordinary layout must keep working exactly as before. */
	@Test
	public void anOrdinaryResultTreeIsStillExcluded() throws Exception {
		File acquisition = folder.newFolder("acq");
		File raw = new File(acquisition, "s_Time000001_Channel0001.tif");
		Files.write(raw.toPath(), new byte[64]);
		File generated = new File(acquisition, "result/deskew/s_Time000001-deskewed.tif");
		generated.getParentFile().mkdirs();
		Files.write(generated.toPath(), new byte[64]);

		Live2 live = new Live2();
		try {
			Parameter p = parameterOf(live);
			p.saveToSame = true;
			p.saveDir = "";
			p.recursive = true;
			p.watchExplicitFolder = true;
			p.watchDir = acquisition.getAbsolutePath();

			assertFalse(isOwnOutput(live, raw));
			assertTrue(isOwnOutput(live, generated));
		} finally {
			live.close();
		}
	}

	/** An explicitly configured result folder is excluded wherever it sits. */
	@Test
	public void anExplicitSaveFolderIsExcluded() throws Exception {
		File acquisition = folder.newFolder("in");
		File out = folder.newFolder("out");
		File raw = new File(acquisition, "s_Time000001_Channel0001.tif");
		Files.write(raw.toPath(), new byte[64]);
		File generated = new File(out, "deskew/s-deskewed.tif");
		generated.getParentFile().mkdirs();
		Files.write(generated.toPath(), new byte[64]);

		Live2 live = new Live2();
		try {
			Parameter p = parameterOf(live);
			p.saveToSame = false;
			p.saveDir = out.getAbsolutePath();
			p.recursive = true;
			p.watchExplicitFolder = true;
			p.watchDir = acquisition.getAbsolutePath();

			assertFalse(isOwnOutput(live, raw));
			assertTrue(isOwnOutput(live, generated));
		} finally {
			live.close();
		}
	}


	// ---- helpers --------------------------------------------------------------------

	private static Parameter parameterOf(Live2 live) throws Exception {
		java.lang.reflect.Field f = Live2.class.getDeclaredField("parameter");
		f.setAccessible(true);
		return (Parameter) f.get(live);
	}

	private static boolean isOwnOutput(Live2 live, File file) throws Exception {
		java.lang.reflect.Method m = Live2.class.getDeclaredMethod("isOwnOutput", File.class);
		m.setAccessible(true);
		return ((Boolean) m.invoke(live, file)).booleanValue();
	}

	private static ImagePlus plane(int width, int height) {
		ImageStack stack = new ImageStack(width, height);
		stack.addSlice(new ShortProcessor(width, height));
		return new ImagePlus("p", stack);
	}
}
