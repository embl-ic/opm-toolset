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
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

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
		OnlineDeskewSession.openingForTest = session -> {
			made.set(session);
			session.store().setReadHook(cell -> opener.interrupt());		// cancelled during the first read
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
			session.store().blocking(null, null);
			fail("the store still served after the cancelled opening");
		} catch (IllegalStateException expected) {
			// closed
		}
	}
}
