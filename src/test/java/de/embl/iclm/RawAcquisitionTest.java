package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;

/**
 * The Online Deskew View's view of a raw acquisition folder: its files' layout (contract C3.1),
 * their place in time and channel (C2.1), and whether each can be shown (C2.2 - C2.5).
 *
 * <p>The readiness tests use files laid out the way the camera software writes them. The one that
 * matters most is the file cut after two of its five planes with its IFD chain terminated: a TIFF
 * reader accepts it as complete, and only the plane count promised by its name tells it apart.
 */
public class RawAcquisitionTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private File raw(String name, int depth, int written) throws IOException {
		return RawTiffFixture.write(new File(folder.getRoot(), name), 26, 17, depth, 8, written, RawTiffFixture.RAMP);
	}

	@Test
	public void aCameraStyleBigTiffHasTheFastLayout() throws IOException {
		RawTiffLayout layout = RawTiffLayout.read(raw("a_Frames_1_5.tiff", 5, 5));
		assertTrue(layout.unsupportedReason, layout.supported());
		assertEquals(26, layout.width);
		assertEquals(17, layout.height);
		assertEquals(5, layout.depth);
		assertEquals(8, layout.rowsPerStrip);
		assertEquals(ByteOrder.LITTLE_ENDIAN, layout.order);
		// plane k starts after k planes of 26 x 17 pixels, their two 3-strip tables and their IFDs
		assertEquals(16, layout.offset(0, 0, 0));
		assertEquals(16 + 2 * (26 * 1 + 3), layout.offset(3, 1, 0));
	}

	@Test
	public void aCompressedTiffIsRefusedWithItsReason() throws IOException {
		ImageStack stack = new ImageStack(20, 10);
		for (int k = 0; k < 3; k++) stack.addSlice(new ShortProcessor(20, 10));
		File deflated = new File(folder.getRoot(), "deflated_Frames_1_3.tif");
		FastTiffWriter.write(new ImagePlus("d", stack), deflated);
		RawTiffLayout layout = RawTiffLayout.read(deflated);
		assertFalse(layout.supported());
		assertTrue(layout.unsupportedReason, layout.unsupportedReason.contains("compressed"));
	}

	@Test
	public void timePointsAreNumericAndChannelsAndPositionsAreSeparate() throws IOException {
		for (int t : new int[] { 1, 2, 10 })
			for (int c : new int[] { 1, 2 })
				raw(String.format("acq_Position0001_Time%06d_Channel%04d_Frames_1_5.tiff", t, c), 5, 5);
		raw("acq_Position0002_Time000001_Channel0001_Frames_1_5.tiff", 5, 5);
		RawAcquisition acquisition = RawAcquisition.scan(folder.getRoot());
		assertTrue(acquisition.timesFromNames);
		assertEquals(7, acquisition.fileCount());
		assertEquals("[1, 2]", acquisition.positions().toString());
		assertEquals("[1, 2]", acquisition.channels().toString());
		List<RawAcquisition.TimeKey> times = acquisition.times(1, 2);
		assertEquals(3, times.size());
		assertEquals(1, times.get(0).timeId);
		assertEquals(2, times.get(1).timeId);
		assertEquals("Time10 follows Time2", 10, times.get(2).timeId);
		assertEquals(1, acquisition.times(2, 1).size());
		assertEquals(0, acquisition.times(2, 2).size());
		assertEquals(2, acquisition.entry(times.get(1), 2).channel);
	}

	@Test
	public void readinessComesFromStructureLayoutAndThePlaneCountInTheName() throws IOException {
		raw("x_Time000001_Channel0001_Frames_1_5.tiff", 5, 5);				// complete
		raw("x_Time000002_Channel0001_Frames_1_5.tiff", 5, 2);				// cut after 2 of 5 planes
		raw("x_Time000003_Channel0001.tiff", 5, 5);							// no plane count in the name
		raw("x_Time000004_Channel0001_Frames_1_3.tiff", 5, 5);				// more planes than promised
		try (FileWriter w = new FileWriter(new File(folder.getRoot(), "x_Time000005_Channel0001_Frames_1_5.tiff"))) {
			w.write("not a tiff");
		}
		RawAcquisition acquisition = RawAcquisition.scan(folder.getRoot());
		List<RawAcquisition.TimeKey> times = acquisition.times(1, 1);
		RawAcquisition.Readiness[] expected = { RawAcquisition.Readiness.COMPLETE, RawAcquisition.Readiness.INCOMPLETE,
				RawAcquisition.Readiness.DEPTH_UNVERIFIED, RawAcquisition.Readiness.FAILED, RawAcquisition.Readiness.INCOMPLETE };
		for (int i = 0; i < times.size(); i++) {
			RawAcquisition.Entry e = acquisition.entry(times.get(i), 1);
			assertEquals(e.file.getName(), RawAcquisition.Readiness.PENDING, e.readiness());
			assertEquals(e.file.getName() + ": " + e.detail(), expected[i], e.validate());
		}
		RawAcquisition.Entry cut = acquisition.entry(times.get(1), 1);
		assertEquals("2 of 5 planes", cut.detail());
		assertFalse(cut.viewable());
		assertNull("only a showable file gets a layout", cut.layout());
		assertTrue(acquisition.entry(times.get(0), 1).viewable());
		assertTrue("unverified depth can be opened by hand", acquisition.entry(times.get(2), 1).viewable());
		Map<RawAcquisition.Readiness, Integer> census = acquisition.census(times, 1);
		assertEquals(1, (int) census.get(RawAcquisition.Readiness.COMPLETE));
		assertEquals(2, (int) census.get(RawAcquisition.Readiness.INCOMPLETE));
		assertEquals(0, (int) census.get(RawAcquisition.Readiness.PENDING));
	}

	@Test
	public void aFileThatChangesIsADifferentVersion() throws IOException, InterruptedException {
		File f = raw("v_Time000001_Channel0001_Frames_1_5.tiff", 5, 5);
		RawAcquisition acquisition = RawAcquisition.scan(folder.getRoot());
		RawAcquisition.Entry e = acquisition.entry(acquisition.times(1, 1).get(0), 1);
		e.validate();
		RawAcquisition.FileKey key = e.key();
		assertTrue(key.current());
		try (FileWriter w = new FileWriter(f, true)) {
			w.write("appended");
		}
		assertFalse("a changed file is no longer the validated version", key.current());
	}

	@Test
	public void aFolderWithoutTimeTokensIsOneTimePointPerFileInNameOrder() throws IOException {
		raw("ArgoLight-3DRings_volume_000001_frames_1_5.tiff", 5, 5);
		raw("ArgoLight-3DRings_volume_000000_frames_1_5.tiff", 5, 5);
		RawAcquisition acquisition = RawAcquisition.scan(folder.getRoot());
		assertFalse(acquisition.timesFromNames);
		List<RawAcquisition.TimeKey> times = acquisition.times(1, 1);
		assertEquals(2, times.size());
		assertTrue(acquisition.entry(times.get(0), 1).file.getName().contains("000000"));
		assertEquals("lower-case _frames_ still states the depth", RawAcquisition.Readiness.COMPLETE,
				acquisition.entry(times.get(0), 1).validate());
	}

	@Test
	public void filesWithoutATimeTokenAmongTimedOnesAreNotPlaced() throws IOException {
		raw("y_Time000001_Channel0001_Frames_1_5.tiff", 5, 5);
		raw("y_preview.tiff", 5, 5);
		RawAcquisition acquisition = RawAcquisition.scan(folder.getRoot());
		assertEquals(1, acquisition.fileCount());
		assertEquals(1, acquisition.unplaced);
	}
}
