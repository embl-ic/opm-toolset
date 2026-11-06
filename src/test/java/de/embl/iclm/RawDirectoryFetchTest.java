package de.embl.iclm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The parallel fetch of a raw file's directories before it is checked: where it predicts them, and that it never decides
 * anything the check would not.
 */
public class RawDirectoryFetchTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	/** A camera-style file: every directory is found from the first two, at the offsets the chain itself names. */
	@Test
	public void aCameraStyleFilesDirectoriesArePredictedWhereTheChainHasThem() throws IOException {
		File file = RawTiffFixture.write(new File(folder.getRoot(), "a.tiff"), 26, 17, 5, 8, 5, RawTiffFixture.RAMP);
		long[] predicted = RawDirectoryFetch.predicted(file);
		assertEquals(5, predicted.length);
		assertArrayEquals(chain(file), predicted);
	}

	/** Not a TIFF, one directory, nothing at all, no file: nothing is predicted and nothing is thrown. */
	@Test
	public void whatItCannotPredictIsLeftToTheWalk() throws IOException {
		File text = folder.newFile("t.tiff");
		try (FileWriter w = new FileWriter(text)) {
			w.write("not a TIFF at all");
		}
		File one = RawTiffFixture.write(new File(folder.getRoot(), "one.tiff"), 26, 17, 1, 8, 1, RawTiffFixture.RAMP);
		File empty = folder.newFile("e.tiff");
		File missing = new File(folder.getRoot(), "missing.tiff");
		for (File f : new File[] { text, one, empty, missing })
			assertEquals(f.getName(), 0, RawDirectoryFetch.predicted(f).length);
		RawDirectoryFetch.fetch(Arrays.asList(text, one, empty, missing));
	}

	/** A fetch that is no longer wanted stops, and is not counted as done: the next one fetches again. */
	@Test
	public void aCancelledFetchStopsAndIsNotCountedAsDone() throws IOException {
		File file = RawTiffFixture.write(new File(folder.getRoot(), "x.tiff"), 26, 17, 5, 8, 5, RawTiffFixture.RAMP);
		assertEquals(false, RawDirectoryFetch.fetch(Collections.singletonList(file), () -> true));
		assertEquals(true, RawDirectoryFetch.fetch(Collections.singletonList(file), () -> false));
		assertEquals("and fetched once is fresh", true, RawDirectoryFetch.fetch(Collections.singletonList(file), () -> true));
	}

	/** A volume caught mid-write is incomplete with its directories fetched first, as without: the fetch decides nothing. */
	@Test
	public void aFetchDecidesNothing() throws IOException {
		File cut = RawTiffFixture.write(new File(folder.getRoot(), "c_Time000001_Channel0001_Frames_1_9.tiff"), 26, 17, 9, 8, 4,
				RawTiffFixture.RAMP);
		RawDirectoryFetch.fetch(Collections.singletonList(cut));
		RawAcquisition acquisition = RawAcquisition.scan(folder.getRoot());
		RawAcquisition.Entry entry = acquisition.entry(acquisition.times(1, 1).get(0), 1);
		assertEquals(entry.detail(), RawAcquisition.Readiness.INCOMPLETE, entry.validate());
		assertEquals("4 of 9 planes", entry.detail());
	}

	/** The directory offsets a BigTIFF's chain names, from its header on. */
	private static long[] chain(File file) throws IOException {
		List<Long> offsets = new ArrayList<Long>();
		try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
			byte[] buffer = new byte[8];
			in.seek(8);
			in.readFully(buffer);
			long ifd = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).getLong();
			while (ifd != 0) {
				offsets.add(ifd);
				in.seek(ifd);
				in.readFully(buffer);
				long entries = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).getLong();
				in.seek(ifd + 8 + 20 * entries);
				in.readFully(buffer);
				ifd = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).getLong();
			}
		}
		long[] result = new long[offsets.size()];
		for (int i = 0; i < result.length; i++) result[i] = offsets.get(i);
		return result;
	}
}
