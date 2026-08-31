package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Assume;
import org.junit.Test;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;

public class BeadsPsfTest {

	@Test
	public void measuredPsfSmokeTestWhenDatasetIsProvided() {
		String path = System.getProperty("opm.psf.smoke.file", "").trim();
		Assume.assumeTrue("Set -Dopm.psf.smoke.file to run the measured-data smoke test", !path.isEmpty());
		ImagePlus measured = IJ.openImage(path);
		assertNotNull(measured);
		int[] peak = maximumPosition(measured);
		int radiusX = Math.min(peak[0], measured.getWidth() - peak[0] - 1);
		int radiusY = Math.min(peak[1], measured.getHeight() - peak[1] - 1);
		int radiusZ = Math.min(peak[2], measured.getStackSize() - peak[2] - 1);
		assertTrue(radiusX > 0 && radiusY > 0 && radiusZ > 0);

		Beads.PsfPreparation prepared = Beads.preparePsfBeads(measured,
				new int[][] { peak }, radiusX, radiusY, radiusZ, new Beads.PsfQualitySettings());
		assertEquals(1, prepared.getAccepted().size());
		assertEquals(1.0d, voxelSum(prepared.getAccepted().get(0)), 1.0e-6d);
		assertEquals(32, prepared.getAccepted().get(0).getBitDepth());
		prepared.getAccepted().get(0).close();
		measured.close();
	}

	@Test
	public void backgroundCorrectsAndNormalizesAcceptedBead() {
		ImagePlus volume = constantFloatVolume("bead", 9, 9, 7, 100.0f);
		volume.getStack().getProcessor(4).setf(4, 4, 500.0f);
		volume.getStack().getProcessor(4).setf(3, 4, 180.0f);
		volume.getStack().getProcessor(4).setf(5, 4, 180.0f);
		Calibration calibration = new Calibration();
		calibration.pixelWidth = calibration.pixelHeight = 0.116d;
		calibration.pixelDepth = 0.1325d;
		calibration.setUnit("micron");
		volume.setCalibration(calibration);

		Beads.PsfQualitySettings settings = permissiveSettings();
		settings.minSnr = 5.0d;
		settings.minSbr = 1.5d;
		Beads.PsfPreparation prepared = Beads.preparePsfBeads(
				volume, new int[][] { { 4, 4, 3 } }, 3, 3, 2, settings);

		assertEquals(1, prepared.getCandidateCount());
		assertEquals(1, prepared.getAccepted().size());
		ImagePlus bead = prepared.getAccepted().get(0);
		assertEquals(32, bead.getBitDepth());
		assertEquals(0.116d, bead.getCalibration().pixelWidth, 1.0e-12d);
		assertEquals(0.1325d, bead.getCalibration().pixelDepth, 1.0e-12d);
		assertEquals(1.0d, voxelSum(bead), 1.0e-6d);
		assertEquals(0.0d, bead.getStack().getProcessor(1).getf(0, 0), 0.0d);
	}

	@Test
	public void reportsEdgeNeighborSaturationAndOffCenterRejections() {
		Beads.PsfQualitySettings settings = permissiveSettings();
		ImagePlus edgeVolume = constantFloatVolume("edge", 9, 9, 9, 10.0f);
		edgeVolume.getStack().getProcessor(5).setf(1, 4, 50.0f);
		Beads.PsfPreparation edge = Beads.preparePsfBeads(
				edgeVolume, new int[][] { { 1, 4, 4 } }, 2, 2, 2, settings);
		assertEquals(Integer.valueOf(1), edge.getRejected().get(Beads.REJECT_EDGE));

		ImagePlus neighborVolume = constantFloatVolume("neighbors", 9, 9, 9, 10.0f);
		neighborVolume.getStack().getProcessor(5).setf(4, 4, 50.0f);
		neighborVolume.getStack().getProcessor(5).setf(5, 4, 50.0f);
		settings.rejectNeighbors = true;
		Beads.PsfPreparation neighbors = Beads.preparePsfBeads(neighborVolume,
				new int[][] { { 4, 4, 4 }, { 5, 4, 4 } }, 2, 2, 2, settings);
		assertEquals(Integer.valueOf(2), neighbors.getRejected().get(Beads.REJECT_NEIGHBOR));

		ImagePlus saturatedVolume = constantShortVolume("saturated", 9, 9, 9, 10);
		saturatedVolume.getStack().getProcessor(5).set(4, 4, 65535);
		settings.rejectNeighbors = false;
		settings.saturationLevel = 0.0d;
		Beads.PsfPreparation saturated = Beads.preparePsfBeads(saturatedVolume,
				new int[][] { { 4, 4, 4 } }, 2, 2, 2, settings);
		assertEquals(Integer.valueOf(1), saturated.getRejected().get(Beads.REJECT_SATURATED));

		ImagePlus offCenterVolume = constantFloatVolume("off-center", 9, 9, 9, 10.0f);
		offCenterVolume.getStack().getProcessor(5).setf(6, 4, 100.0f);
		settings.saturationLevel = 1000.0d;
		settings.maxCenterOffset = 0.5d;
		Beads.PsfPreparation offCenter = Beads.preparePsfBeads(offCenterVolume,
				new int[][] { { 4, 4, 4 } }, 2, 2, 2, settings);
		assertEquals(Integer.valueOf(1), offCenter.getRejected().get(Beads.REJECT_OFF_CENTER));

		ImagePlus lowSnrVolume = constantFloatVolume("low-SNR", 9, 9, 9, 100.0f);
		for (int z = 2; z <= 6; z++) {
			for (int y = 2; y <= 6; y++) {
				for (int x = 2; x <= 6; x++)
					lowSnrVolume.getStack().getProcessor(z + 1).setf(x, y,
							99.0f + (x + y + z) % 3);
			}
		}
		lowSnrVolume.getStack().getProcessor(5).setf(4, 4, 102.0f);
		settings.minSnr = 5.0d;
		settings.minSbr = 0.0d;
		settings.maxCenterOffset = 2.0d;
		Beads.PsfPreparation lowSnr = Beads.preparePsfBeads(lowSnrVolume,
				new int[][] { { 4, 4, 4 } }, 2, 2, 2, settings);
		assertEquals(Integer.valueOf(1), lowSnr.getRejected().get(Beads.REJECT_LOW_SNR));

		ImagePlus lowSbrVolume = constantFloatVolume("low-SBR", 9, 9, 9, 100.0f);
		lowSbrVolume.getStack().getProcessor(5).setf(4, 4, 120.0f);
		settings.minSnr = 0.0d;
		settings.minSbr = 1.5d;
		Beads.PsfPreparation lowSbr = Beads.preparePsfBeads(lowSbrVolume,
				new int[][] { { 4, 4, 4 } }, 2, 2, 2, settings);
		assertEquals(Integer.valueOf(1), lowSbr.getRejected().get(Beads.REJECT_LOW_SBR));
	}

	@Test
	public void combinesIntoFloatUnitIntegralKernelAndAllBeadsHyperstack() {
		ImagePlus first = deltaKernel("first", 3, 3, 3, 1, 1, 1, 2.0f);
		ImagePlus second = deltaKernel("second", 3, 3, 3, 1, 1, 1, 8.0f);
		first.getCalibration().pixelWidth = 0.2d;
		first.getCalibration().setUnit("micron");

		ImagePlus mean = Beads.combineBeadsImage(Arrays.asList(first, second), "mean average");
		assertNotNull(mean);
		assertEquals(32, mean.getBitDepth());
		assertEquals(1.0d, voxelSum(mean), 1.0e-6d);
		assertEquals(0.2d, mean.getCalibration().pixelWidth, 0.0d);

		ImagePlus all = Beads.combineBeadsImage(Arrays.asList(first, second), "all beads");
		assertEquals(2, all.getNChannels());
		assertEquals(3, all.getNSlices());
		assertEquals(1.0d, channelSum(all, 1), 1.0e-6d);
		assertEquals(1.0d, channelSum(all, 2), 1.0e-6d);
	}

	@Test
	public void splitsAndFlipsPackedRightChannelWithoutChangingCalibration() {
		ImageStack stack = new ImageStack(6, 2);
		float[] pixels = new float[] {
				1, 2, 3, 10, 20, 30,
				1, 2, 3, 10, 20, 30 };
		stack.addSlice(new FloatProcessor(6, 2, pixels));
		ImagePlus packed = new ImagePlus("packed", stack);
		packed.getCalibration().pixelWidth = 0.116d;
		Parameter parameter = new Parameter("psf-unit-test");
		parameter.impInput = packed;
		parameter.psfChannelLayout = "mirrored left/right halves";
		parameter.psfFlipRight = true;

		List<Deconvolve.PsfChannelInput> channels = new Deconvolve().splitPsfInput(parameter);
		assertEquals(2, channels.size());
		assertEquals(1.0d, channels.get(0).image.getProcessor().getf(0, 0), 0.0d);
		assertEquals(30.0d, channels.get(1).image.getProcessor().getf(0, 0), 0.0d);
		assertEquals(10.0d, channels.get(1).image.getProcessor().getf(2, 0), 0.0d);
		assertEquals(0.116d, channels.get(1).image.getCalibration().pixelWidth, 0.0d);
		for (Deconvolve.PsfChannelInput channel : channels) channel.image.close();
	}

	private static Beads.PsfQualitySettings permissiveSettings() {
		Beads.PsfQualitySettings settings = new Beads.PsfQualitySettings();
		settings.shellFraction = 0.15d;
		settings.minSnr = 0.0d;
		settings.minSbr = 0.0d;
		settings.maxCenterOffset = 2.0d;
		settings.saturationLevel = 1000.0d;
		settings.rejectNeighbors = false;
		return settings;
	}

	private static ImagePlus constantFloatVolume(String title, int width, int height, int depth, float value) {
		ImageStack stack = new ImageStack(width, height);
		for (int z = 0; z < depth; z++) {
			float[] pixels = new float[width * height];
			Arrays.fill(pixels, value);
			stack.addSlice(new FloatProcessor(width, height, pixels));
		}
		return new ImagePlus(title, stack);
	}

	private static ImagePlus constantShortVolume(String title, int width, int height, int depth, int value) {
		ImageStack stack = new ImageStack(width, height);
		for (int z = 0; z < depth; z++) {
			short[] pixels = new short[width * height];
			Arrays.fill(pixels, (short)value);
			stack.addSlice(new ShortProcessor(width, height, pixels, null));
		}
		return new ImagePlus(title, stack);
	}

	private static ImagePlus deltaKernel(String title, int width, int height, int depth,
			int x, int y, int zCenter, float value) {
		ImagePlus image = constantFloatVolume(title, width, height, depth, 0.0f);
		image.getStack().getProcessor(zCenter + 1).setf(x, y, value);
		return image;
	}

	private static double voxelSum(ImagePlus image) {
		double sum = 0.0d;
		for (int z = 1; z <= image.getStackSize(); z++) {
			float[] pixels = (float[])image.getStack().getProcessor(z).convertToFloatProcessor().getPixels();
			for (float pixel : pixels) sum += pixel;
		}
		return sum;
	}

	private static double channelSum(ImagePlus image, int channel) {
		double sum = 0.0d;
		for (int z = 1; z <= image.getNSlices(); z++) {
			int index = image.getStackIndex(channel, z, 1);
			float[] pixels = (float[])image.getStack().getProcessor(index).getPixels();
			for (float pixel : pixels) sum += pixel;
		}
		return sum;
	}

	private static int[] maximumPosition(ImagePlus image) {
		float maximum = -Float.MAX_VALUE;
		int[] position = new int[3];
		for (int z = 0; z < image.getStackSize(); z++) {
			for (int y = 0; y < image.getHeight(); y++) {
				for (int x = 0; x < image.getWidth(); x++) {
					float value = image.getStack().getProcessor(z + 1).getf(x, y);
					if (value > maximum) {
						maximum = value;
						position[0] = x;
						position[1] = y;
						position[2] = z;
					}
				}
			}
		}
		return position;
	}
}
