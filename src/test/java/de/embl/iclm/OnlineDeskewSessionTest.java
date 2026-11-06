package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileWriter;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;
import net.imglib2.RandomAccess;
import net.imglib2.type.numeric.integer.UnsignedShortType;

/**
 * The session without its window: the order it checks a folder's files in, and an opening that
 * is cancelled. The window itself is {@link OnlineDeskewSessionGuiTest}.
 */
public class OnlineDeskewSessionTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@After
	public void noHookLeftBehind() {
		OnlineDeskewSession.openingForTest = null;
		Thread.interrupted();
	}

	private static int next(int shown, int count, Integer... pending) {
		final Set<Integer> left = new HashSet<Integer>(java.util.Arrays.asList(pending));
		return OnlineDeskewSession.nextToCheck(shown, count, left::contains);
	}

	@Test
	public void theShownTimePointIsCheckedBeforeEveryFileInFrontOfIt() {
		assertEquals(10, next(10, 50, 3, 4, 10, 11));
	}

	@Test
	public void thenTheOnesAfterItBecausePlaybackRunsForward() {
		assertEquals(11, next(10, 50, 3, 4, 11, 12));
		assertEquals(3, next(10, 50, 3, 4));		// and only then the ones before it
		assertEquals(0, next(49, 50, 0, 7));		// wrapping round past the last
	}

	@Test
	public void nothingLeftIsMinusOne() {
		assertEquals(-1, next(5, 50));
		assertEquals(-1, next(0, 0));
		assertEquals(2, next(-1, 3, 2));			// no time point shown yet counts as the first
	}

	@Test public void followingChecksNewestFirstThenBackwardsPastAnUnfinishedTail() {
		assertEquals(5, OnlineDeskewSession.nextToCheckNewestFirst(6, i -> true));
		assertEquals(4, OnlineDeskewSession.nextToCheckNewestFirst(6, i -> i != 5));
		assertEquals(3, OnlineDeskewSession.nextToCheckNewestFirst(6, i -> i == 0 || i == 3));
		assertEquals(-1, OnlineDeskewSession.nextToCheckNewestFirst(6, i -> false));
		assertEquals(-1, OnlineDeskewSession.nextToCheckNewestFirst(0, i -> true));
	}

	private OnlineDeskewView.Settings fixtureFolder() throws Exception {
		for (int t = 1; t <= 3; t++)
			RawTiffFixture.write(new File(folder.getRoot(), String.format("o_Position0001_Time%06d_Channel0001_Frames_1_9.tiff", t)),
					26, 17, 9, 8, 9, RawTiffFixture.RAMP);
		try (FileWriter w = new FileWriter(new File(folder.getRoot(), "ExperimentalParameters.txt"))) {
			w.write("Interval: 5 Minute\nTilt angle: 33.5\num per galvo DU: 0.01325\nGalvo DUs per step: 20\n"
					+ "Pixel size at object /um: 0.116\nCamera ROI X0, X1, Y0, Y1: 0 26 0 17\n");
		}
		OnlineDeskewView.Settings s = new OnlineDeskewView.Settings();
		s.folder = folder.getRoot().getAbsolutePath();
		s.budgetMb = 64;
		return s;
	}

	/** The opening refuses before any session is made, and its message says what to change. */
	private void refused(OnlineDeskewView.Settings s, String... says) throws Exception {
		final AtomicReference<OnlineDeskewSession> made = new AtomicReference<OnlineDeskewSession>();
		OnlineDeskewSession.openingForTest = made::set;
		try {
			OnlineDeskewSession.open(s);
			fail("opened " + s.folder);
		} catch (IllegalArgumentException expected) {
			for (String words : says)
				assertTrue(expected.getMessage(), expected.getMessage().contains(words));
			assertNull("no session was made", made.get());
		}
	}

	@Test
	public void eachOpeningRefusalSaysWhatToChange() throws Exception {
		OnlineDeskewView.Settings s = fixtureFolder();
		final String root = s.folder;
		s.folder = "  ";
		refused(s, "Choose the raw acquisition folder");
		s.folder = new File(root, "missing").getPath();
		refused(s, "Not a folder");
		s.folder = root;
		s.channel = 2;
		refused(s, "No files of the selected acquisition channels [2]", "Positions found: [1]", "channels found: [1]");
		s.channel = 1;
		s.alignChannels = true;
		s.alignmentCsv = new File(root, "nowhere.csv").getPath();
		refused(s, "No alignment matrix file at", "choose it again", "or untick \"align channel with alignment matrix CSV file\"");
		s.alignmentCsv = "";
		s.alignChannels = false;
		s.parameterSource = OnlineDeskewView.PARAMETER_SOURCES[1];
		s.dz = 0;
		refused(s, "scan step must be a positive");
		s.dz = 0.265;
		s.angle = 90;
		refused(s, "OPM angle");
		s.parameterSource = OnlineDeskewView.PARAMETER_SOURCES[0];
		assertTrue(new File(root, "ExperimentalParameters.txt").delete());
		refused(s, "No ExperimentalParameters.txt", "\"manual\"");
	}

	@Test public void anUntickedAlignmentIgnoresItsRememberedPathAndATickedEmptyPathIsRefused() throws Exception {
		OnlineDeskewView.Settings s = fixtureFolder();
		s.alignmentCsv = new File(folder.getRoot(), "missing.csv").getAbsolutePath();
		s.alignChannels = false;
		inspectOpening(s);
		s.alignChannels = true; s.alignmentCsv = "";
		refused(s, "Choose the alignment matrix CSV", "untick \"align channel with alignment matrix CSV file\"");
	}

	@Test public void anExternalParameterFileSuppliesGeometryAndTheFramesOneDepth() throws Exception {
		File raw = new File(folder.getRoot(), "external_Position0001_Time000001_Channel0001_Frames_1.tiff");
		RawTiffFixture.write(raw, 26, 17, 9, 8, 9, RawTiffFixture.RAMP);
		File chosen = new File(folder.newFolder("parameters"), "outside.txt");
		try (FileWriter w = new FileWriter(chosen)) {
			w.write("Interval: 5 Minute\nTilt angle: 33.5\num per galvo DU: 0.01325\nGalvo DUs per step: 20\nPixel size at object /um: 0.116\nPlanes per volume: 9\nCamera ROI X0, X1, Y0, Y1: 0 26 0 17\n");
		}
		OnlineDeskewView.Settings s = new OnlineDeskewView.Settings();
		s.folder = folder.getRoot().getAbsolutePath(); s.budgetMb = 64;
		s.parameterSource = OnlineDeskewView.PARAMETER_SOURCES[2]; s.parameterFile = chosen.getAbsolutePath();
		OnlineDeskewSession inspected = inspectOpening(s);
		assertEquals(chosen.getAbsolutePath(), inspected.geometry().origin);
		RawAcquisition.Entry entry = inspected.acquisition().snapshot(1, 1).entry(0);
		assertEquals(9, entry.expectedDepth);
		assertEquals(RawAcquisition.Readiness.COMPLETE, entry.readiness());
		s.parameterFile = new File(folder.getRoot(), "missing.txt").getAbsolutePath();
		refused(s, "No parameter file at", "choose it again or choose another source");
		s.parameterFile = " ";
		refused(s, "Choose the parameter file, or choose another source for \"parameters from\"");
	}

	/** Inspect after validation and setup, before any window is built; the normal cancellation path cleans up. */
	private OnlineDeskewSession inspectOpening(OnlineDeskewView.Settings settings) throws Exception {
		AtomicReference<OnlineDeskewSession> made = new AtomicReference<OnlineDeskewSession>();
		OnlineDeskewSession.openingForTest = session -> { made.set(session); throw new CancellationException("inspect opening"); };
		try { OnlineDeskewSession.open(settings); fail("inspection should cancel before the window"); }
		catch (CancellationException expected) { assertEquals("inspect opening", expected.getMessage()); }
		finally { OnlineDeskewSession.openingForTest = null; }
		assertTrue(made.get().closed().get(15, TimeUnit.SECONDS).drained);
		return made.get();
	}

	@Test
	public void aCompressedAcquisitionIsRefusedWithItsReason() throws Exception {
		ImageStack stack = new ImageStack(20, 10);
		for (int k = 0; k < 3; k++)
			stack.addSlice(new ShortProcessor(20, 10));
		FastTiffWriter.write(new ImagePlus("c", stack), new File(folder.getRoot(), "c_Position0001_Time000001_Channel0001_Frames_1_3.tif"));
		OnlineDeskewView.Settings s = new OnlineDeskewView.Settings();
		s.folder = folder.getRoot().getAbsolutePath();
		s.parameterSource = OnlineDeskewView.PARAMETER_SOURCES[1];
		s.budgetMb = 64;
		refused(s, "No selected file can be shown", "compressed");
	}

	@Test
	public void anOpeningCancelledBeforeItBeginsOpensNothing() throws Exception {
		OnlineDeskewView.Settings s = fixtureFolder();
		final AtomicReference<OnlineDeskewSession> made = new AtomicReference<OnlineDeskewSession>();
		OnlineDeskewSession.openingForTest = made::set;
		Thread.currentThread().interrupt();		// what Shutdown's cancel does to the opening thread
		try {
			OnlineDeskewSession.open(s);
			fail("a cancelled opening opened a view");
		} catch (CancellationException expected) {
			assertNull("no session was even made", made.get());
		}
	}

	/** Cancelled while the first planes are read: the session that existed is closed again, store and all. */
	@Test
	public void anOpeningCancelledWhileReadingClosesWhatItOpened() throws Exception {
		OnlineDeskewView.Settings s = fixtureFolder();
		final Thread opener = Thread.currentThread();
		final AtomicReference<OnlineDeskewSession> made = new AtomicReference<OnlineDeskewSession>();
		final AtomicBoolean cancelled = new AtomicBoolean();
		OnlineDeskewSession.openingForTest = session -> {
			made.set(session);
			// cancelled during the first read, once: reads still finishing must not interrupt the close
			session.store().setReadHook(cell -> {
				if (cancelled.compareAndSet(false, true)) opener.interrupt();
			});
		};
		try {
			OnlineDeskewSession.open(s);
			fail("a cancelled opening opened a view");
		} catch (CancellationException expected) {
			assertTrue(String.valueOf(expected.getCause()), expected.getCause() instanceof TiffRawVolumeStore.RawReadException);
		}
		Thread.interrupted();
		OnlineDeskewSession session = made.get();
		OnlineDeskewSession.CloseResult result = session.closed().get(15, TimeUnit.SECONDS);
		assertTrue(session.isClosing());
		assertNull("no window was built", session.frame());
		assertTrue("closed: " + result, result.store.drained);
		assertEquals(0, result.store.after.totalBytes());
		try {
			RawAcquisition.Entry first = session.acquisition().entry(session.times().get(0), 1);
			RandomAccess<UnsignedShortType> a = session.store().blocking(first.key(), first.layout()).randomAccess();
			a.setPosition(new long[] { 1, 1, 1 });
			a.get().get();
			fail("the store still read after the cancelled opening");
		} catch (IllegalStateException expected) {
			// closed
		}
	}
}
