package de.embl.iclm;

import static org.junit.Assert.*;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PositionZarrTest {
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	@Test public void namesKeepExactPositionDigitsAndUnpositionedNamesStayUnchanged() {
		File output = new File("output");
		File input = new File("acquisition");
		assertEquals(new File(output, "acquisition_Position0001.ome.zarr"),
				OmeZarrConverter.rootFor(output, input, new File("s_Position0001_Time001.tiff")));
		assertEquals("acquisition_Position000.ome.zarr",
				OmeZarrConverter.rootFor(output, input, new File("s_Position000P_Time000TTT.tiff")).getName());
		assertEquals(new File(output, "acquisition.ome.zarr"),
				OmeZarrConverter.rootFor(output, input, new File("s_Time001.tiff")));
	}

	@Test public void batchZarrOnlyWritesTwoIndependentPositionStores() throws Exception { batch(false); }
	@Test public void batchDualOutputWritesTwoIndependentPositionStores() throws Exception { batch(true); }

	private void batch(boolean dual) throws Exception {
		File input = temporary.newFolder("acquisition");
		File output = temporary.newFolder("results");
		List<File> files = fixture(input);
		File legacy = new File(output, "acquisition.ome.zarr");
		Files.createDirectories(legacy.toPath());
		File sentinel = new File(legacy, "keep");
		Files.write(sentinel.toPath(), new byte[] { 7 });
		Batch batch = new Batch();
		Parameter p = parameter(input, output);
		p.outputFormat = dual ? Parameter.FORMAT_BOTH : Parameter.FORMAT_ZARR;
		p.saveDeskewZarr = true;
		p.saveDeskewImage = dual;
		set(batch, "parameter", p); set(batch, "inputFolder", input); set(batch, "saveFolder", output);
		set(batch, "zarrInputFiles", files);
		ChannelOperationSettings channels = (ChannelOperationSettings) get(batch, "channels");
		channels.combineAcquisitionChannels = true;
		channels.channelOrder[0] = "_Channel0001-left";
		channels.channelOrder[1] = "_Channel0002-left";
		for (int c = 2; c < channels.channelOrder.length; c++) channels.channelOrder[c] = BatchChannelOperation.SKIP_CHANNEL;
		batch.processFiles();
		assertStores(output);
		assertArrayEquals(new byte[] { 7 }, Files.readAllBytes(sentinel.toPath()));
		if (dual) {
			List<TiffResultDataset> found = TiffResultDataset.discover(output);
			assertEquals(2, found.size());
			for (TiffResultDataset dataset : found) assertEquals(2, dataset.getView(TiffResultDataset.VOLUME).frameCount());
		}
	}

	@Test public void liveInterleavedPositionsWriteTwoIndependentStores() throws Exception {
		Assume.assumeTrue("Live2 is a window; enable with -Dopm.test.gui=true", Boolean.getBoolean("opm.test.gui"));
		File input = temporary.newFolder("acquisition");
		File output = temporary.newFolder("results");
		List<File> files = fixture(input);
		Live2 live = new Live2();
		try {
			set(live, "parameter", parameter(input, output));
			Method process = Live2.class.getDeclaredMethod("processCanonicalZarr", File.class, List.class);
			process.setAccessible(true);
			// Interleave positions at each time, with reverse channel arrival within a group.
			for (int t = 1; t <= 2; t++) for (int position = 2; position >= 1; position--) {
				List<File> group = new ArrayList<File>();
				for (int c = 2; c >= 1; c--) for (File file : files)
					if (file.getName().contains(String.format("Position%04d_Time%06d_Channel%04d", position, t, c))) group.add(file);
				process.invoke(live, group.get(0), group);
			}
			assertEquals(2, ((java.util.Map<?, ?>) get(live, "zarrSessions")).size());
			assertStores(output);
		} finally { live.close(); }
	}

	@Test public void previewClaimsOnlyTheFirstPositionOfEachOutputFolder() throws Exception {
		LivePreview preview = new LivePreview(true, false, true, false);
		File output = temporary.getRoot();
		assertTrue(preview.claimRoot(new File(output, "acquisition_Position0001.ome.zarr")));
		assertFalse(preview.claimRoot(new File(output, "acquisition_Position0002.ome.zarr")));
		assertTrue(preview.claimRoot(new File(new File(output, "other"), "acquisition_Position0001.ome.zarr")));
		preview.close();
		assertTrue(preview.claimRoot(new File(output, "acquisition_Position0002.ome.zarr")));
	}

	@Test public void legacyStoreNoticeIsEmittedOnceAndOnlyForPositionedRuns() throws Exception {
		File output = temporary.getRoot();
		File input = new File("acquisition");
		Files.createDirectories(new File(output, "acquisition.ome.zarr").toPath());
		java.util.Set<File> announced = new java.util.HashSet<File>();
		assertNotNull(OmeZarrConverter.legacyStoreNotice(new File(output, "acquisition_Position0001.ome.zarr"), input, announced));
		assertNull(OmeZarrConverter.legacyStoreNotice(new File(output, "acquisition_Position0002.ome.zarr"), input, announced));
		assertNull(OmeZarrConverter.legacyStoreNotice(new File(output, "acquisition.ome.zarr"), input, announced));
	}

	static List<File> fixture(File input) throws Exception {
		List<File> files = new ArrayList<File>();
		for (int t = 1; t <= 2; t++) for (int p = 1; p <= 2; p++) for (int c = 1; c <= 2; c++) {
			final int offset = 1000 * p + 100 * t + 10 * c;
			File file = new File(input, String.format("sample_Position%04d_Time%06d_Channel%04d.tiff", p, t, c));
			RawTiffFixture.write(file, 8, 6, 3, 2, 3, (x, y, k) -> offset + x + y + k);
			files.add(file);
		}
		return files;
	}

	private static void assertStores(File output) throws Exception {
		for (int position = 1; position <= 2; position++) {
			File root = new File(output, String.format("acquisition_Position%04d.ome.zarr", position));
			assertTrue(root.isDirectory());
			OmeZarrDataset dataset = OmeZarrDataset.read(root);
			assertEquals(2, dataset.getTimepointCount());
			assertEquals(4, dataset.getChannelLabels().size());
			assertEquals(4, dataset.getProvenance().sourceFiles.size());
			for (String source : dataset.getProvenance().sourceFiles)
				assertTrue("store must contain its own position only: " + source, source.contains(String.format("Position%04d", position)));
		}
	}

	private static Parameter parameter(File input, File output) {
		Parameter p = new Parameter("position-zarr-test");
		p.inputDir = input.getAbsolutePath(); p.saveDir = output.getAbsolutePath();
		p.saveToSame = false; p.reproduceInputTree = false; p.recursive = false;
		p.tryGPU = false; p.livePreview = false; p.makeTimeLapse = false; p.displayResult = false;
		p.xyPixelSize = 100; p.zStepSize = 100; p.opmAngle = 90; p.frameInterval = 1;
		p.doProjection = false; p.fileExistStr = "overwrite";
		p.alignMatrix = new double[][] { { 1, 0, 0 }, { 0, 1, 0 } };
		p.outputFormat = Parameter.FORMAT_ZARR; p.saveDeskewZarr = true; p.saveDeskewImage = false;
		return p;
	}

	static Object get(Object object, String name) throws Exception {
		Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
	}
	static void set(Object object, String name, Object value) throws Exception {
		Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
	}
}
