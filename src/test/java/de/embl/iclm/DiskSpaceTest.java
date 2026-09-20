package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests for the free-space forecast the live listener shows.
 *
 * <p>Free space itself is a property of the machine and cannot be asserted on, so what is
 * pinned here is the arithmetic and the edges around it: a rate is only claimed once there is
 * enough of a span to mean anything, a volume that is not shrinking never produces a
 * countdown, two folders on the same disk are one volume rather than two, and an output folder
 * that has not been created yet is still measurable.
 */
public class DiskSpaceTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final long GB = 1024L * 1024 * 1024;


	/** The Live panel names a folder per line; each has to find the volume it sits on. */
	@Test
	public void aPathFindsTheVolumeItLivesOn() throws Exception {
		DiskSpace disk = new DiskSpace();
		File input = folder.newFolder("acquisition");
		File output = new File(folder.getRoot(), "results/not/created/yet");
		assertEquals("nothing tracked yet", null, disk.reportFor(input));

		disk.watch(input);
		DiskSpace.Report forInput = disk.reportFor(input);
		DiskSpace.Report forOutput = disk.reportFor(output);
		assertNotNull(forInput);
		assertNotNull("an uncreated folder on a tracked disk is found through its ancestor", forOutput);
		assertEquals("one disk, one volume", forInput.volume, forOutput.volume);
	}

	@Test
	public void aFolderThatDoesNotExistYetIsStillMeasurable() {
		File missing = new File(folder.getRoot(), "results/deskew/not/created/yet");
		assertFalse(missing.exists());

		File probe = DiskSpace.nearestExisting(missing);
		assertNotNull("an ancestor on the same volume must be found", probe);
		assertTrue(probe.exists());
		assertTrue(missing.getAbsolutePath().startsWith(probe.getAbsolutePath()));
	}

	@Test
	public void twoFoldersOnOneDiskAreOneVolume() throws Exception {
		File first = folder.newFolder("input");
		File second = folder.newFolder("output");
		assertEquals(DiskSpace.volumeName(first), DiskSpace.volumeName(second));

		DiskSpace disk = new DiskSpace();
		disk.watch(first);
		disk.watch(second);
		assertEquals("one disk, one line", 1, disk.reports().size());
	}

	@Test
	public void freeSpaceIsReportedBeforeAnyRateIsKnown() throws Exception {
		DiskSpace disk = new DiskSpace();
		disk.watch(folder.getRoot());
		List<DiskSpace.Report> reports = disk.reports();
		assertEquals(1, reports.size());

		DiskSpace.Report report = reports.get(0);
		assertTrue("the volume has some free space", report.freeBytes > 0);
		assertFalse("one sample cannot be a trend", report.hasRate());
		assertTrue(Double.isInfinite(report.hoursRemaining));
		assertTrue(report.format(), report.format().contains("rate not measured yet"));
	}

	/** A sample taken immediately after the first says nothing; it must not invent a rate. */
	@Test
	public void aShortSpanDoesNotProduceAForecast() throws Exception {
		DiskSpace disk = new DiskSpace();
		disk.watch(folder.getRoot());
		disk.sample();
		assertFalse(disk.reports().get(0).hasRate());
		assertNotNull(disk.worst());
	}

	@Test
	public void resetForgetsThePreviousRun() throws Exception {
		DiskSpace disk = new DiskSpace();
		disk.watch(folder.getRoot());
		assertEquals(1, disk.reports().size());
		disk.reset();
		assertEquals(0, disk.reports().size());
		assertEquals("not measured", disk.summary());
	}


	// ---- the arithmetic, on synthetic numbers ---------------------------------------

	@Test
	public void aCountdownIsFreeSpaceOverTheMeasuredRate() {
		// 60 GB free, consumed at 1 GB per minute: an hour left
		DiskSpace.Report report = report(60 * GB, 500 * GB, GB / 60.0);
		assertEquals(1.0, report.hoursRemaining, 1e-6);
		assertTrue(report.hasRate());
		assertTrue(report.format(), report.format().contains("1.00 GB/min"));
	}

	@Test
	public void aVolumeThatIsNotShrinkingNeverRunsOut() {
		DiskSpace.Report report = report(10 * GB, 500 * GB, 0);
		assertTrue(Double.isInfinite(report.hoursRemaining));
		assertFalse(report.hasRate());
	}

	@Test
	public void theWarningLevelsMatchTheHoursLeft() {
		assertEquals(DiskSpace.Level.OK, report(500 * GB, 1000 * GB, GB / 3600.0).level());
		// 30 GB at 1 GB per 10 min is five hours: more than the four-hour warning needs
		assertEquals(DiskSpace.Level.OK, report(30 * GB, 1000 * GB, GB / 600.0).level());
		// 18 GB at the same rate is three hours: not enough for four, so warn
		assertEquals(DiskSpace.Level.WARNING, report(18 * GB, 1000 * GB, GB / 600.0).level());
		// the same 30 GB at six times the rate is under an hour
		assertEquals(DiskSpace.Level.CRITICAL, report(30 * GB, 1000 * GB, GB / 100.0).level());
	}

	/** Almost no space left is critical whatever the rate says, including no rate at all. */
	@Test
	public void almostNoSpaceIsCriticalEvenWithoutARate() {
		DiskSpace.Report report = report(2 * GB, 500 * GB, 0);
		assertEquals(DiskSpace.Level.CRITICAL, report.level());
		assertTrue(report.format(), report.format().contains("DISK ALMOST FULL"));
	}

	@Test
	public void remainingTimeIsPhrasedAtTheScaleThatMatters() {
		assertEquals("45 min", DiskSpace.formatHours(0.75));
		assertEquals("1.0 h", DiskSpace.formatHours(1.0));
		assertEquals("30.0 h", DiskSpace.formatHours(30.0));
		assertEquals("3 days", DiskSpace.formatHours(72.0));
		assertEquals("not at this rate", DiskSpace.formatHours(Double.POSITIVE_INFINITY));
	}

	private static DiskSpace.Report report(long free, long total, double bytesPerSecond) {
		return new DiskSpace.Report("T:\\", free, total, bytesPerSecond);
	}
}
