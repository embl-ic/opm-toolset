package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Focused headless tests for the live-processing audit repairs. */
public class LiveAuditRepairTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	/**
	 * The Live panel is a fixed width, so a long path is cut in the middle: the drive or share
	 * and the last folders identify a location, and they are the two ends.
	 */
	@Test
	public void aLongPathIsShortenedInTheMiddle() {
		String local = "E:\\OPM\\acquisitions_2026\\V1_flipleft_three_both-acq";
		assertEquals("a path that fits is left alone", local, Live2.shortenMiddle(local, local.length()));
		assertEquals("whole folders kept from both ends",
				"E:\\OPM\\...\\V1_flipleft_three_both-acq", Live2.shortenMiddle(local, 40));

		String share = "\\\\iclm\\microscopy\\OPM\\2026\\September\\run_17\\result";
		String cut = Live2.shortenMiddle(share, 40);
		assertEquals("the share stays whole and trailing folders come first",
				"\\\\iclm\\microscopy\\OPM\\...\\run_17\\result", cut);
		assertTrue(cut.length() <= 40);

		assertEquals("/data/.../2026/acquisition_0/result",
				Live2.shortenMiddle("/data/opm/users/zhuang/2026/acquisition_0/result", 36));

		String oneLongName = "E:\\" + "a_very_long_acquisition_name_that_alone_exceeds_the_budget";
		String squeezed = Live2.shortenMiddle(oneLongName, 30);
		assertEquals("a name too long for any folder cut is cut through, to the budget", 30, squeezed.length());
		assertTrue(squeezed.startsWith("E:\\") && squeezed.contains("...") && squeezed.endsWith("budget"));

		for (int budget = 5; budget < share.length(); budget++)
			assertTrue("never longer than asked, at " + budget,
					Live2.shortenMiddle(share, budget).length() <= Math.max(budget, 5));
	}

	@Test
	public void fileGeometryOnlyAppliesToItsOwnAcquisitionFolder() throws IOException {
		File acquisitionA = folder.newFolder("acquisition-a");
		File acquisitionB = folder.newFolder("acquisition-b");
		File metadataA = touch(new File(acquisitionA, "ExperimentalParameters.txt"));
		File volumeA = touch(new File(acquisitionA, "a.tif"));
		File volumeB = touch(new File(acquisitionB, "b.tif"));

		assertTrue(Live2.metadataIsFor(volumeA, metadataA.getAbsolutePath()));
		assertFalse("folder B must wait rather than inherit folder A's geometry",
				Live2.metadataIsFor(volumeB, metadataA.getAbsolutePath()));
	}

	@Test
	public void recursiveBackfillExcludesTheSaveToSameResultTree() throws IOException {
		final File acquisition = folder.newFolder("acquisition");
		File first = touch(new File(acquisition, "raw-1.tif"));
		File second = touch(new File(acquisition, "raw-2.tif"));
		final File result = new File(acquisition, "result");
		touch(new File(result, "deskew/generated-deskewed.tif"));

		List<File> found = new ArrayList<File>();
		Live2.collectTiffs(acquisition, true, "", "", found, 0, new Live2.FileExclusion() {
			@Override
			public boolean excludes(File candidate) {
				return candidate.equals(result) || BatchProcessingUtils.isInside(candidate, result);
			}
		});

		assertEquals(2, found.size());
		assertTrue(found.contains(first));
		assertTrue(found.contains(second));
	}

	@Test
	public void anUncreatableOutputFolderMakesTheDeskewWriteFail() throws IOException {
		File regularFile = touch(new File(folder.getRoot(), "not-a-folder"));
		Parameter parameter = new Parameter("junit-live-write-failure");
		parameter.displayResult = false;
		parameter.saveDeskewImage = true;
		parameter.outputFormat = Parameter.FORMAT_TIFF;
		parameter.fileExistStr = "overwrite";
		parameter.saveSeparate = false;
		parameter.doProjection = false;
		parameter.saveDir = new File(regularFile, "result").getAbsolutePath();
		ImagePlus result = new ImagePlus("write-failure-deskewed", new ByteProcessor(4, 4));

		assertFalse("a logged folder error must propagate back to the live manifest",
				Deskew.prepareResults(new ImagePlus[] { result }, parameter));
	}

	@Test
	public void anExplicitFolderIsACompleteSourceWithTcpDisabled() throws IOException {
		File watch = folder.newFolder("folder-only");
		assertTrue(Live2.hasDiscoverySource(watch, false, false));
		assertFalse("announced folders cannot arrive while TCP/IP itself is disabled",
				Live2.hasDiscoverySource(null, false, true));
		assertTrue(Live2.hasDiscoverySource(null, true, true));
		assertTrue("TCP/IP paths alone are a complete live input mode",
				Live2.hasDiscoverySource(null, true, false));
		assertEquals("disabled", Live2.describeTcp(true, false, false, 5020));
		assertEquals("unavailable (folder-only)", Live2.describeTcp(true, true, false, 5020));
	}

	@Test
	public void experimentalParametersLoadWithoutAPreviewImage() throws IOException {
		File metadata = write(new File(folder.getRoot(), "ExperimentalParameters.txt"),
				"Tilt angle: 25\n"
				+ "um per galvo DU: 0.265\n"
				+ "Galvo DUs per step: 1\n"
				+ "Pixel size at object /um: 0.116\n"
				+ "Interval: 30 s\n"
				+ "Camera ROI X0, X1, Y0, Y1: 0 50 0 40\n");
		Parameter parameter = new Parameter("junit-deskew-no-preview");
		ImagePlus fullImage = new ImagePlus("full", new ByteProcessor(50, 40));

		assertTrue(Deskew.loadSettingsFile(parameter, metadata.getAbsolutePath(),
				fullImage, "expParams"));
		assertEquals(116.0, parameter.xyPixelSize, 0.0);
		assertEquals(265.0, parameter.zStepSize, 0.0);
		assertEquals(25.0, parameter.opmAngle, 0.0);
	}

	private static File touch(File file) throws IOException {
		File parent = file.getParentFile();
		if (parent != null) parent.mkdirs();
		OutputStream out = new FileOutputStream(file);
		try {
			out.write(1);
		} finally {
			out.close();
		}
		return file;
	}

	private static File write(File file, String text) throws IOException {
		File parent = file.getParentFile();
		if (parent != null) parent.mkdirs();
		OutputStream out = new FileOutputStream(file);
		try {
			out.write(text.getBytes("UTF-8"));
		} finally {
			out.close();
		}
		return file;
	}
}
