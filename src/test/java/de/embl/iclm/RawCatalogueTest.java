package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * M3-A's catalogue, without a viewer (joint contracts C2.1 - C2.3, C2.6): a folder followed by looks at the whole of it and by
 * hints about single files, with a test producer writing BigTIFFs that grow plane by plane. Publication needs a quiet or
 * announced version, a valid structure and the plane count the name promises; an underfilled file stays pending as {@code k of
 * n}; a late older time number takes its place in order; a replaced file is withdrawn and republished, a deleted one is
 * unavailable; a missed or a repeated event is repaired; an idle folder costs nothing but a look; an aborted volume stalls
 * nothing else; and a look at a thousand files reads each one's attributes once and opens none. Nothing here is timed.
 */
public class RawCatalogueTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	static final int W = 32, H = 20, D = 8, ROWS = 8;

	@Before
	public void quickly() {
		RawAcquisition.quietMsForTest = 150;
	}

	@After
	public void reset() {
		RawAcquisition.quietMsForTest = -1;
		RawAcquisition.lookHookForTest = null;
	}

	private File file(int time) {
		return new File(folder.getRoot(), String.format(Locale.US, "c_Position0001_Time%06d_Channel0001_Frames_1_%d.tiff", time, D));
	}

	private File complete(int time) throws Exception {
		return RawTiffFixture.write(file(time), W, H, D, ROWS, D, RawTiffFixture.RAMP);
	}

	private static RawAcquisition.Entry entry(RawAcquisition a, int time) {
		return a.entry(new RawAcquisition.TimeKey(1, time), 1);
	}

	/** Every file with something due checked now, as the view's worker does - a file not quiet yet is left for later. */
	private static int checkDue(RawAcquisition a) {
		int checked = 0;
		long now = System.currentTimeMillis();
		for (RawAcquisition.Entry e : a.snapshot(1, 1).entries)
			if (e.recheck(now) != null) checked++;
		return checked;
	}

	private static void untilQuiet() throws InterruptedException {
		Thread.sleep(RawAcquisition.quietMs() + 50);
	}

	private static List<Integer> ids(RawAcquisition.Snapshot s) {
		Integer[] ids = new Integer[s.size()];
		for (int i = 0; i < ids.length; i++) ids[i] = s.time(i).timeId;
		return Arrays.asList(ids);
	}

	@Test
	public void anUnderfilledFileStaysPendingAsKOfN() throws Exception {
		complete(1);
		RawAcquisition a = RawAcquisition.scan(folder.getRoot());
		try (RawTiffFixture.Producer p = new RawTiffFixture.Producer(file(2), W, H, ROWS, RawTiffFixture.RAMP)) {
			p.write(3);
			assertEquals(1, a.reconcile().added);
			RawAcquisition.Entry second = entry(a, 2);
			assertTrue("an arrival", second.arrived);
			assertFalse("not quiet yet: not checked", second.due(System.currentTimeMillis()));
			assertTrue("being written", second.beingWritten(System.currentTimeMillis()));
			assertTrue(second.describe(), second.describe().startsWith("being written ("));
			assertFalse("a scanned file is only waiting for its check", entry(a, 1).beingWritten(System.currentTimeMillis()));
			assertEquals("being checked", entry(a, 1).describe());
			untilQuiet();
			assertFalse("quiet: waiting for its check, no longer being written", second.beingWritten(System.currentTimeMillis()));
			assertEquals(2, checkDue(a));		// the scanned file at once, the arrival once quiet
			assertEquals(RawAcquisition.Readiness.INCOMPLETE, second.readiness());
			assertEquals("3 of 8 planes", second.detail());
			assertEquals("pending: 3 of 8 planes", second.describe());
			assertFalse(second.viewable());
			assertEquals("on the axis, not shown", Arrays.asList(1, 2), ids(a.snapshot(1, 1)));
			assertEquals("nothing new on disk, nothing to check", 0, checkDue(a));
			p.write(1);
			a.hint(file(2));
			assertTrue(second.describe(), second.describe().startsWith("pending: 3 of 8 planes; a new version being written ("));
			untilQuiet();
			assertEquals(second.describe(), "pending: 3 of 8 planes; a new version waiting to be checked", second.describe());
			assertEquals(1, checkDue(a));
			assertEquals("pending: 4 of 8 planes", second.describe());
		}
	}

	@Test
	public void aGrowingFileIsPublishedOnceWhenComplete() throws Exception {
		complete(1);
		RawAcquisition a = RawAcquisition.scan(folder.getRoot());
		checkDue(a);
		try (RawTiffFixture.Producer p = new RawTiffFixture.Producer(file(2), W, H, ROWS, RawTiffFixture.RAMP)) {
			a.reconcile();
			RawAcquisition.Entry second = entry(a, 2);
			while (p.written() < D) {
				p.write(2);
				a.hint(file(2));		// what the folder watch would say
				assertFalse("growing: not checked while it is being written", second.due(System.currentTimeMillis()));
				untilQuiet();
				checkDue(a);
				if (p.written() < D) {
					assertEquals(RawAcquisition.Readiness.INCOMPLETE, second.readiness());
					assertEquals(p.written() + " of " + D + " planes", second.detail());
				}
			}
		}
		assertEquals(RawAcquisition.Readiness.COMPLETE, second(a).readiness());
		assertTrue(second(a).viewable());
		assertEquals("one check per quiet version", D / 2, second(a).checks());
		assertEquals("published once, never republished", 0, second(a).republished());
	}

	private static RawAcquisition.Entry second(RawAcquisition a) {
		return entry(a, 2);
	}

	@Test
	public void aCompleteArrivalWaitsToBeQuietUnlessItsProducerSaysItIsFinished() throws Exception {
		complete(1);
		RawAcquisition a = RawAcquisition.scan(folder.getRoot());
		RawAcquisition.quietMsForTest = 60000;
		assertTrue("a file there when the folder was opened is checked at once", entry(a, 1).due(System.currentTimeMillis()));
		complete(2);
		a.reconcile();
		RawAcquisition.Entry second = entry(a, 2);
		assertFalse(second.due(System.currentTimeMillis()));
		assertTrue(a.announce(file(2)));
		assertTrue("announced: checked without waiting", second.due(System.currentTimeMillis()));
		assertEquals(2, checkDue(a));
		assertEquals(RawAcquisition.Readiness.COMPLETE, second.readiness());
	}

	@Test
	public void aLateOlderTimePointIsInsertedInOrder() throws Exception {
		complete(2);
		complete(3);
		RawAcquisition a = RawAcquisition.scan(folder.getRoot());
		RawAcquisition.Snapshot before = a.snapshot(1, 1);
		assertEquals(Arrays.asList(2, 3), ids(before));
		complete(4);
		a.reconcile();
		RawAcquisition.Snapshot grown = a.snapshot(1, 1);
		assertTrue("a later time point only extends the axis", grown.extendsFrom(before));
		complete(1);
		a.hint(file(1));
		RawAcquisition.Snapshot after = a.snapshot(1, 1);
		assertEquals(Arrays.asList(1, 2, 3, 4), ids(after));
		assertFalse("an earlier one renumbers it", after.extendsFrom(grown));
		RawAcquisition.TimeKey shown = before.time(1);
		assertEquals(1, before.indexOf(shown));
		assertEquals("the same time point, a new index", 2, after.indexOf(shown));
		assertTrue(after.version > before.version);
		assertEquals("the snapshot held stays as it was", Arrays.asList(2, 3), ids(before));
	}

	@Test
	public void aReplacedFileIsWithdrawnCheckedAgainAndRepublished() throws Exception {
		complete(1);
		RawAcquisition a = RawAcquisition.scan(folder.getRoot());
		checkDue(a);
		RawAcquisition.Entry e = entry(a, 1);
		RawAcquisition.FileKey shown = e.key();
		assertNotNull(shown);
		RawTiffFixture.write(file(1), W, H, D, ROWS, D, (x, y, k) -> 7 * x + y + 3 * k);
		Files.setLastModifiedTime(file(1).toPath(), FileTime.fromMillis(shown.modified + 5000));
		assertTrue(a.reconcile().changed > 0);
		assertTrue("still shown until the store withdraws it", e.viewable());
		assertFalse("a shown file is withdrawn first, never checked under the view", e.due(System.currentTimeMillis() + 60000));
		assertEquals(e, a.markChanged(shown, "rewritten"));		// what the store reports
		assertEquals(RawAcquisition.Readiness.CHANGED, e.readiness());
		assertFalse(e.viewable());
		untilQuiet();
		assertEquals(1, checkDue(a));
		assertEquals(RawAcquisition.Readiness.COMPLETE, e.readiness());
		assertNotNull(e.key());
		assertFalse("the new version", shown.equals(e.key()));
		assertEquals(1, e.republished());
		assertNull("a report about the old version changes nothing now", a.markChanged(shown, "late"));
	}

	@Test
	public void aDeletedFileBecomesUnavailableWithItsStatusAndComesBack() throws Exception {
		complete(1);
		complete(2);
		RawAcquisition a = RawAcquisition.scan(folder.getRoot());
		checkDue(a);
		RawAcquisition.Entry e = entry(a, 2);
		Files.delete(file(2).toPath());
		assertEquals(1, a.reconcile().removed);
		assertEquals(RawAcquisition.Readiness.REMOVED, e.readiness());
		assertFalse(e.viewable());
		assertEquals("removed: deleted", e.describe());
		assertEquals("the time point stays, unavailable", Arrays.asList(1, 2), ids(a.snapshot(1, 1)));
		assertEquals(0, a.reconcile().removed);
		complete(2);
		a.hint(file(2));
		untilQuiet();
		assertEquals(1, checkDue(a));
		assertTrue("written again, shown again", e.viewable());
	}

	@Test
	public void aMissedOrARepeatedEventIsRepaired() throws Exception {
		complete(1);
		RawAcquisition a = RawAcquisition.scan(folder.getRoot());
		complete(2);
		complete(3);
		// the watch named the second file three times and missed the third
		assertEquals(1, a.hint(file(2)).added);
		assertEquals(0, a.hint(file(2)).added);
		assertEquals(0, a.hint(file(2)).changed);
		assertEquals(Arrays.asList(1, 2), ids(a.snapshot(1, 1)));
		RawAcquisition.Change look = a.reconcile();
		assertEquals("the look finds what the watch missed", 1, look.added);
		assertEquals(Arrays.asList(1, 2, 3), ids(a.snapshot(1, 1)));
		assertEquals("each file once", 3, a.fileCount());
		// a hint about something that is not a file of this folder changes nothing
		assertFalse(a.hint(new File(folder.getRoot(), "notes.txt")).any());
		assertFalse(a.hint(new File(folder.newFolder("elsewhere"), file(4).getName())).any());
	}

	@Test
	public void anIdleFolderCostsALookAndNothingElse() throws Exception {
		complete(1);
		complete(2);
		RawAcquisition a = RawAcquisition.scan(folder.getRoot());
		checkDue(a);
		long version = a.version();
		int checks = entry(a, 1).checks() + entry(a, 2).checks();
		for (int i = 0; i < 5; i++) {
			assertFalse(a.reconcile().any());
			assertEquals(0, checkDue(a));
		}
		assertEquals("nothing changed, nothing published", version, a.version());
		assertEquals("no file opened again", checks, entry(a, 1).checks() + entry(a, 2).checks());
	}

	@Test
	public void anAbortedVolumeStaysPendingAndStallsNothingElse() throws Exception {
		try (RawTiffFixture.Producer aborted = new RawTiffFixture.Producer(file(1), W, H, ROWS, RawTiffFixture.RAMP)) {
			aborted.write(2);
		}
		complete(2);
		RawAcquisition a = RawAcquisition.scan(folder.getRoot());
		checkDue(a);
		complete(3);
		a.reconcile();
		untilQuiet();
		checkDue(a);
		assertEquals(RawAcquisition.Readiness.INCOMPLETE, entry(a, 1).readiness());
		assertEquals("2 of 8 planes", entry(a, 1).detail());
		assertTrue(entry(a, 2).viewable());
		assertTrue("published past the aborted one", entry(a, 3).viewable());
		for (int i = 0; i < 3; i++) {
			a.reconcile();
			assertEquals("never checked again while it does not change", 0, checkDue(a));
		}
	}

	@Test
	public void aDepthUnverifiedArrivalIsNotShownUnlessAllowed() throws Exception {
		File scanned = RawTiffFixture.write(new File(folder.getRoot(), "u_Time000001_Channel0001.tiff"), W, H, D, ROWS, D, RawTiffFixture.RAMP);
		RawAcquisition a = RawAcquisition.scan(folder.getRoot());
		RawTiffFixture.write(new File(folder.getRoot(), "u_Time000002_Channel0001.tiff"), W, H, D, ROWS, D, RawTiffFixture.RAMP);
		a.reconcile();
		untilQuiet();
		checkDue(a);
		assertEquals(scanned, entry(a, 1).file);
		assertEquals(RawAcquisition.Readiness.DEPTH_UNVERIFIED, entry(a, 1).readiness());
		assertTrue("opened by hand: shown, with its status", entry(a, 1).viewable());
		assertEquals(RawAcquisition.Readiness.DEPTH_UNVERIFIED, entry(a, 2).readiness());
		assertFalse("arrived while followed: not published by default", entry(a, 2).viewable());
		assertTrue(entry(a, 2).detail(), entry(a, 2).detail().contains("not shown"));

		RawAcquisition allowing = RawAcquisition.scan(folder.newFolder("allowing"));
		allowing.setShowUnverifiedArrivals(true);
		RawTiffFixture.write(new File(allowing.folder, "u_Time000003_Channel0001.tiff"), W, H, D, ROWS, D, RawTiffFixture.RAMP);
		allowing.reconcile();
		untilQuiet();
		checkDue(allowing);
		assertTrue("allowed: shown", entry(allowing, 3).viewable());
	}

	@Test
	public void aLookAtAThousandFilesReadsEachOnceAndOpensNone() throws Exception {
		for (int t = 1; t <= 1000; t++) Files.createFile(file(t).toPath());		// listed and placed; never opened here
		RawAcquisition a = RawAcquisition.scan(folder.getRoot());
		assertEquals(1000, a.fileCount());
		long reads = RawAcquisition.attributeReadsForTest.get();
		long start = System.nanoTime();
		RawAcquisition.Change look = a.reconcile();
		long micros = (System.nanoTime() - start) / 1000;
		assertEquals(1000, look.looked);
		assertFalse(look.any());
		// one counted read of each known file's attributes; the listing's own regular-file check is not counted (M3-A-N1)
		assertEquals("one counted attribute read per known file", 1000, RawAcquisition.attributeReadsForTest.get() - reads);
		int checks = 0;
		for (RawAcquisition.Entry e : a.snapshot(1, 1).entries) checks += e.checks();
		assertEquals("no file opened by a look", 0, checks);
		System.out.printf(Locale.US, "a look at 1000 files: %.1f ms, %d counted attribute reads (and the listing's checks)%n",
				micros / 1000.0, 1000);
		// a look stopped part way leaves the rest for the next one
		final int[] reached = new int[1];
		RawAcquisition.lookHookForTest = () -> reached[0]++;
		assertEquals(100, a.reconcile(() -> reached[0] >= 100).looked);
	}

	/* ---------------------------------------------------------------- M3-A-F3: conflicts over a slot are settled from the files */

	private File named(String prefix, int time) {
		return new File(folder.getRoot(), String.format(Locale.US, "%s_Position0001_Time%06d_Channel0001_Frames_1_%d.tiff", prefix, time, D));
	}

	private static File write(File f) throws Exception {
		return RawTiffFixture.write(f, W, H, D, ROWS, D, RawTiffFixture.RAMP);
	}

	/** Look and check {@code rounds} times, as the view's worker does, past the quiet time each time. */
	private static void settle(RawAcquisition a, boolean look, int rounds) throws InterruptedException {
		for (int i = 0; i < rounds; i++) {
			if (look) a.reconcile();
			untilQuiet();
			checkDue(a);
		}
	}

	/** B's first M3-A-F3 probe, ported: the replacement's hint arrives before the old file's deletion is seen. */
	@Test
	public void bProbeAReplacementDiscoveredBeforeItsDeleteHintIsRecovered() throws Exception {
		RawAcquisition.quietMsForTest = 0;
		File old = write(named("old", 1));
		RawAcquisition a = RawAcquisition.scan(folder.getRoot());
		checkDue(a);
		assertTrue(entry(a, 1).viewable());
		Files.delete(old.toPath());
		File replacement = write(named("new", 1));
		a.hint(replacement);		// watch hints may come in another order: the deletion is not seen yet
		a.hint(old);
		settle(a, true, 3);
		RawAcquisition.Entry e = entry(a, 1);
		assertEquals("the sole file left holds the slot", replacement, e.file);
		assertTrue("and is published: " + e.describe(), e.viewable());
		assertTrue("nothing is left unplaced: " + a.problems(), a.unplaced == 0 && a.problems().isEmpty());
	}

	/** B's second M3-A-F3 probe, ported: a duplicate that goes leaves the original shown again. */
	@Test
	public void bProbeRemovingATemporaryDuplicateRestoresTheSurvivingFile() throws Exception {
		RawAcquisition.quietMsForTest = 0;
		File original = write(named("original", 1));
		RawAcquisition a = RawAcquisition.scan(folder.getRoot());
		checkDue(a);
		File duplicate = write(named("duplicate", 1));
		a.hint(duplicate);
		assertFalse("an ambiguous slot is not shown", entry(a, 1).viewable());
		Files.delete(duplicate.toPath());
		a.hint(duplicate);
		settle(a, true, 3);
		assertEquals(original, entry(a, 1).file);
		assertTrue("a conflict that is over does not keep a time point away: " + entry(a, 1).describe(), entry(a, 1).viewable());
	}

	/**
	 * Either file may be the one left, and either the folder watch's hints or the looks alone find it: while both are there
	 * the slot is not shown, whatever is checked; once one is left it is shown, as a new publication.
	 */
	private void oneOfTwoLeft(boolean holderLeaves, boolean hintsOnly) throws Exception {
		File holder = write(named("a", 1));
		RawAcquisition a = RawAcquisition.scan(folder.getRoot());
		checkDue(a);
		RawAcquisition.Entry.Shown first = entry(a, 1).shown();
		assertNotNull(first);
		File rival = write(named("b", 1));
		if (hintsOnly) a.hint(rival);
		settle(a, !hintsOnly, 2);
		RawAcquisition.Entry held = entry(a, 1);
		assertEquals(holder, held.file);
		assertEquals(RawAcquisition.Readiness.FAILED, held.readiness());
		assertTrue(held.detail(), held.detail().contains("ambiguous") && held.detail().contains(holder.getName())
				&& held.detail().contains(rival.getName()));
		assertFalse("both there: not shown", held.viewable());
		assertEquals(1, a.unplaced);
		File leaving = holderLeaves ? holder : rival, staying = holderLeaves ? rival : holder;
		Files.delete(leaving.toPath());
		if (hintsOnly) a.hint(leaving);
		settle(a, !hintsOnly, 2);
		RawAcquisition.Entry e = entry(a, 1);
		assertEquals("the file left holds the slot", staying, e.file);
		assertTrue("shown again: " + e.describe(), e.viewable());
		assertTrue("a new publication, not the first one back", e.shown() != first);
		assertEquals(Arrays.asList(1), ids(a.snapshot(1, 1)));
		assertTrue("nothing unplaced, no problem listed: " + a.problems(), a.unplaced == 0 && a.problems().isEmpty());
	}

	@Test
	public void theHolderLeftAfterARivalWentIsShownAgainFromLooks() throws Exception {
		oneOfTwoLeft(false, false);
	}

	@Test
	public void theHolderLeftAfterARivalWentIsShownAgainFromHints() throws Exception {
		oneOfTwoLeft(false, true);
	}

	@Test
	public void theRivalLeftAfterTheHolderWentTakesTheSlotFromLooks() throws Exception {
		oneOfTwoLeft(true, false);
	}

	@Test
	public void theRivalLeftAfterTheHolderWentTakesTheSlotFromHints() throws Exception {
		oneOfTwoLeft(true, true);
	}

	/** Three claims: the slot stays away until two of them have gone, whichever order they go in. */
	@Test
	public void aSlotClaimedThreeWaysIsShownOnlyWhenOneClaimIsLeft() throws Exception {
		File a1 = write(named("a", 1));
		RawAcquisition a = RawAcquisition.scan(folder.getRoot());
		checkDue(a);
		File b1 = write(named("b", 1)), c1 = write(named("c", 1));
		settle(a, true, 2);
		assertFalse(entry(a, 1).viewable());
		assertEquals(2, a.unplaced);
		Files.delete(a1.toPath());		// the holder goes: b takes the slot, and c still rivals it
		settle(a, true, 2);
		assertEquals(b1, entry(a, 1).file);
		assertFalse("two files still claim it: " + entry(a, 1).describe(), entry(a, 1).viewable());
		assertTrue(entry(a, 1).detail(), entry(a, 1).detail().contains(c1.getName()));
		Files.delete(c1.toPath());
		settle(a, true, 2);
		assertEquals(b1, entry(a, 1).file);
		assertTrue(entry(a, 1).describe(), entry(a, 1).viewable());
	}

	/* ---------------------------------------------------------------- M3-A-F2: the gate is told first */

	/** What the gate saw: each publication, and whether its entry still showed it when the gate was told. */
	private static final class Gate {
		final List<RawAcquisition.Entry.Shown> told = Collections.synchronizedList(new ArrayList<RawAcquisition.Entry.Shown>());
		final List<String> late = Collections.synchronizedList(new ArrayList<String>());

		void watch(RawAcquisition a, int time) {
			a.setGate(publication -> {
				told.add(publication);
				RawAcquisition.Entry e = entry(a, time);
				if (e.shown() != publication) late.add("told after the catalogue took it away: " + e.describe());
			});
		}
	}

	/**
	 * Every way the catalogue takes a publication away - a deletion seen by a hint or by a look, a rival for its slot, a
	 * change the store reports - tells the gate first, while the entry still shows it, and once.
	 */
	@Test
	public void everyPublicationTakenAwayIsRevokedAtTheGateFirst() throws Exception {
		for (String how : new String[] { "hinted deletion", "looked-at deletion", "rival", "change" }) {
			for (File f : folder.getRoot().listFiles()) Files.delete(f.toPath());
			File f = write(named("g", 1));
			RawAcquisition a = RawAcquisition.scan(folder.getRoot());
			checkDue(a);
			RawAcquisition.Entry e = entry(a, 1);
			RawAcquisition.Entry.Shown publication = e.shown();
			assertNotNull(publication);
			Gate gate = new Gate();
			gate.watch(a, 1);
			switch (how) {
			case "hinted deletion": Files.delete(f.toPath()); a.hint(f); break;
			case "looked-at deletion": Files.delete(f.toPath()); a.reconcile(); break;
			case "rival": a.hint(write(named("h", 1))); break;
			default: assertTrue(a.markChanged(publication.key, "test") != null);
			}
			assertFalse(how + ": taken away", entry(a, 1).viewable());
			assertEquals(how + ": the gate told of that publication, once", Arrays.asList(publication), gate.told);
			assertTrue(how + ": " + gate.late, gate.late.isEmpty());
		}
	}

	/** A file published again after its conflict is a new publication: the gate's record of the old one does not refuse it. */
	@Test
	public void aRepublicationIsANewPublicationTheGateDoesNotKnow() throws Exception {
		write(named("a", 1));
		RawAcquisition a = RawAcquisition.scan(folder.getRoot());
		checkDue(a);
		Gate gate = new Gate();
		gate.watch(a, 1);
		RawAcquisition.Entry.Shown before = entry(a, 1).shown();
		File rival = write(named("b", 1));
		a.hint(rival);
		Files.delete(rival.toPath());
		a.hint(rival);
		settle(a, false, 1);
		RawAcquisition.Entry.Shown after = entry(a, 1).shown();
		assertNotNull("shown again", after);
		assertTrue("the same file version", before.key.equals(after.key));
		assertTrue("a new publication", after != before);
		assertEquals(Arrays.asList(before), gate.told);
	}
}
