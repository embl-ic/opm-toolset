package de.embl.iclm;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assume;
import org.junit.Test;

import ij.ImagePlus;

/**
 * What a TIFF virtual overlay costs per plane, measured rather than assumed.
 *
 * <p>The overlay is evaluated while planes are read, so the question a user asks - "can I scroll
 * this, and does a live preview keep up?" - is answered in milliseconds per plane. Each step of
 * the representation is timed on its own against the same files: reading a whole plane as
 * written, taking one half of it, mirroring that half, and rigidly transforming it.
 *
 * <p>Enable with {@code -Dopm.test.data=<TIFF result root>}; it asserts nothing about time, since
 * a threshold on somebody else's disk fails for the wrong reason. It prints a table.
 */
public class TiffVirtualChannelBenchTest {

	/** A real 0.26 degree bead alignment, so the transform path is the one production uses. */
	private static final double[][] ROTATION = {
		{ 0.9999897, -0.0045379, 3.4 }, { 0.0045379, 0.9999897, -1.6 } };

	@Test
	public void reportVirtualChannelCost() throws Exception {
		String root = System.getProperty("opm.test.data");
		Assume.assumeTrue("Set -Dopm.test.data to a TIFF result root", root != null);
		File folder = new File(root);
		Assume.assumeTrue(folder.isDirectory());

		List<TiffResultDataset> series = new ArrayList<TiffResultDataset>();
		for (TiffResultDataset dataset : TiffResultDataset.discover(folder))
			if (dataset.getDisplayName().matches("(?i).*_Channel\\d+.*")) series.add(dataset);
		Assume.assumeTrue("Needs at least two _ChannelNNNN series", series.size() >= 2);
		TiffResultDataset first = series.get(0);
		TiffResultDataset second = series.get(1);
		TiffResultDataset.Layout layout = first.getView(TiffResultDataset.VOLUME).getLayout();
		System.out.println("BENCH source " + folder + ": " + series.size() + " series, "
				+ layout.width + "x" + layout.height + "x" + layout.slices + ", "
				+ first.getView(TiffResultDataset.VOLUME).frameCount() + " time point(s)");

		int planes = Math.min(60, layout.slices);
		System.out.println("BENCH " + planes + " distinct Z planes per measurement, cold cache each time");
		System.out.printf("BENCH %-46s %10s %10s%n", "representation", "ms/plane", "MB/s");

		time("as written (whole width, one channel)", asWritten(first), planes, layout.width, layout.height);
		time("virtual: left half, no transform",
				overlay(first, second, false, null, true), planes, layout.width / 2, layout.height);
		time("virtual: 4 channels, mirrored right halves",
				overlay(first, second, true, null, true), planes, layout.width / 2, layout.height);
		time("virtual: 4 channels, mirrored + rigid (bilinear)",
				overlay(first, second, true, AlignmentMatrixSet.legacy(ROTATION), true),
				planes, layout.width / 2, layout.height);
		time("virtual: 4 channels, mirrored + rigid (nearest)",
				overlay(first, second, true, AlignmentMatrixSet.legacy(ROTATION), false),
				planes, layout.width / 2, layout.height);
	}

	/** Read {@code planes} distinct planes of the view and report the per-plane cost. */
	private static void time(String what, TiffResultView.Options options, int planes,
			int width, int height) throws Exception {
		List<TiffResultDataset> series = new ArrayList<TiffResultDataset>();
		for (TiffResultDataset dataset : TiffResultDataset.discover(
				new File(System.getProperty("opm.test.data"))))
			if (dataset.getDisplayName().matches("(?i).*_Channel\\d+.*")) series.add(dataset);
		ImagePlus view = TiffResultView.openVirtual(series.get(0), TiffResultDataset.VOLUME, options, 0);
		try {
			int channels = view.getNChannels();
			long start = System.nanoTime();
			int read = 0;
			for (int z = 1; z <= planes && z <= view.getNSlices(); z++)
				for (int c = 1; c <= channels; c++) {
					view.getStack().getProcessor(view.getStackIndex(c, z, 1));
					read++;
				}
			double seconds = (System.nanoTime() - start) / 1e9;
			double msPerPlane = 1000.0 * seconds / read;
			double mb = read * 2.0 * width * height / 1048576.0;
			System.out.printf("BENCH %-46s %10.2f %10.1f%n", what, msPerPlane, mb / seconds);
			assertTrue(read > 0);
		} finally {
			view.changes = false;
			view.flush();
		}
	}

	private static TiffResultView.Options asWritten(TiffResultDataset dataset) {
		return new TiffResultView.Options();
	}

	/** One or four virtual channels over the two series, with or without a matrix. */
	private static TiffResultView.Options overlay(TiffResultDataset first, TiffResultDataset second,
			boolean allFour, AlignmentMatrixSet alignment, boolean interpolate) {
		TiffResultView.Options options = new TiffResultView.Options();
		options.interpolate = interpolate;
		options.virtualChannels.add(new TiffResultView.VirtualChannel(first,
				ChannelOperationSettings.sourceKey(1, true), TiffResultView.HorizontalPart.LEFT,
				alignment, false));
		if (allFour) {
			options.virtualChannels.add(new TiffResultView.VirtualChannel(first,
					ChannelOperationSettings.sourceKey(1, false), TiffResultView.HorizontalPart.RIGHT,
					alignment, false));
			options.virtualChannels.add(new TiffResultView.VirtualChannel(second,
					ChannelOperationSettings.sourceKey(2, true), TiffResultView.HorizontalPart.LEFT,
					alignment, false));
			options.virtualChannels.add(new TiffResultView.VirtualChannel(second,
					ChannelOperationSettings.sourceKey(2, false), TiffResultView.HorizontalPart.RIGHT,
					alignment, false));
		}
		return options;
	}
}
