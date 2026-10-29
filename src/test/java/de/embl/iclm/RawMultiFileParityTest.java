package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.viewer.Interpolation;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;
import net.imglib2.display.RealARGBColorConverter;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedShortType;

/**
 * M4-A's NN reference: the batch split, CLIJ buffer kernel's NN arithmetic on the CPU, the batch NN XY registration,
 * and the batch composite in slot order. Batch's CPU fallback itself is linear (CPU.transform), so it cannot be an NN
 * oracle at general OPM angles; a separate diagnostic below records that difference without changing its policy.
 */
public class RawMultiFileParityTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final RawGeometry G = RawMultiFileTest.GEOMETRY;
	private static final String[] KEYS = { "_Channel0002-right", "_Channel0001-left", "_Channel0002-left", "_Channel0001-right" };
	private static final int[] COLOURS = { 0xff00ff00, 0xffff00ff, 0xff00ffff, 0xffffff00 };
	// Integer translation makes the two resampling stages comparable. Negative offsets also avoid mpicbg's
	// legacy NN cast rounding a negative out-of-bounds coordinate toward pixel zero (recorded in the report).
	private static final double[][] SHIFT = { { 1, 0, -2 }, { 0, 1, -1 } };

	/** The stored buffer kernel: inverse map output centres, floor, zero outside the input. No viewer transform is used. */
	private static ImagePlus nearest(ImagePlus input, double[][] matrix) {
		matrix = Transform.autoCenter(new long[] { input.getWidth(), input.getHeight(), input.getNSlices() }, matrix);
		long[] grid = Transform.getTransformedDim(new long[] { input.getWidth(), input.getHeight(), input.getNSlices() }, matrix, false);
		AffineTransform3D inverse = Transform.raw_to_imglib2(matrix).inverse();
		ImageStack stack = new ImageStack((int) grid[0], (int) grid[1]);
		double[] out = new double[3], raw = new double[3];
		for (int z = 0; z < grid[2]; z++) {
			ShortProcessor plane = new ShortProcessor((int) grid[0], (int) grid[1]);
			for (int y = 0; y < grid[1]; y++) for (int x = 0; x < grid[0]; x++) {
				out[0] = x + 0.5;
				out[1] = y + 0.5;
				out[2] = z + 0.5;
				inverse.apply(out, raw);
				if (raw[0] >= 0 && raw[0] < input.getWidth() && raw[1] >= 0 && raw[1] < input.getHeight()
						&& raw[2] >= 0 && raw[2] < input.getNSlices())
					plane.set(x, y, input.getStack().getProcessor(1 + (int) Math.floor(raw[2])).get((int) Math.floor(raw[0]), (int) Math.floor(raw[1])));
			}
			stack.addSlice(plane);
		}
		return new ImagePlus("nearest reference", stack);
	}

	private ImagePlus reference(boolean registered) throws Exception {
		List<ImagePlus> ordered = new ArrayList<ImagePlus>();
		try {
			for (String key : KEYS) {
				int c = ChannelOperationSettings.acquisitionChannelOf(key);
				File file = new File(folder.getRoot(), String.format("m_Position0001_Time000001_Channel%04d_Frames_1_%d.tiff", c, RawMultiFileTest.D));
				ImagePlus raw = VolumeIO.open(file.getAbsolutePath());
				assertNotNull(raw);
				ImagePlus[] halves = Partition.separateImageLeftRight(raw, "left & right separately");
				try {
					boolean right = key.endsWith("-right");
					ImagePlus part = halves[right ? 1 : 0];
					double[][] m = G.deskewMatrix(part.getWidth(), part.getHeight(), part.getNSlices());
					ImagePlus output = nearest(part, right ? Transform.matrix_flipX(part, m) : m);
					if (right && registered) SIFT.alignStackSIFT2(output, SHIFT, false);
					ordered.add(output);
				} finally {
					for (ImagePlus half : halves) half.close();
					raw.close();
				}
			}
			return MultiChannelDeskew.combine(ordered, "batch NN composite");
		} finally { for (ImagePlus image : ordered) image.close(); }
	}

	private void files() throws Exception {
		RawMultiFileTest.write(folder.getRoot(), 1, 1);
		RawMultiFileTest.write(folder.getRoot(), 1, 2);
	}

	@Test
	public void nearestDeskewMatchesTheTwoFileBatchComposite() throws Exception {
		check(false);
	}

	@Test
	public void nearestRegisteredMatchesTheTwoFileBatchComposite() throws Exception {
		check(true);
	}

	private void check(boolean registered) throws Exception {
		files();
		RawAcquisition.Snapshot s = RawMultiFileTest.catalogue(folder.getRoot(), 1, 2).snapshot(1, new int[] { 1, 2 });
		ImagePlus batch = reference(registered);
		assertEquals(4, batch.getNChannels());
		List<RawFrameRequest.Input> inputs = new ArrayList<RawFrameRequest.Input>();
		for (RawAcquisition.Entry e : s.entries) inputs.add(new RawFrameRequest.Input(e, e.shown()));
		List<RawFrameRequest.Layer> layers = new ArrayList<RawFrameRequest.Layer>();
		List<RealARGBColorConverter<UnsignedShortType>> converters = new ArrayList<RealARGBColorConverter<UnsignedShortType>>();
		for (int i = 0; i < KEYS.length; i++) {
			RawSourceSpec spec = RawSourceSpec.half(registered ? AlignmentMatrixSet.legacy(SHIFT) : null,
					ChannelOperationSettings.acquisitionChannelOf(KEYS[i]), KEYS[i].endsWith("-left"), false, RawMultiFileTest.W, COLOURS[i]);
			layers.add(new RawFrameRequest.Layer(spec, G.sourceToWorld(s.entry(0).layout().dimensions(), spec, null), 0, 65535, COLOURS[i]));
			RealARGBColorConverter<UnsignedShortType> converter = RealARGBColorConverter.create(new UnsignedShortType(), 0, 65535);
			converter.setColor(new ARGBType(COLOURS[i]));
			converters.add(converter);
		}
		TiffRawVolumeStore store = new TiffRawVolumeStore(8L << 20, 512, 2, null);
		long checked = 0, coloured = 0;
		try (RawFrameRenderer renderer = new RawFrameRenderer(2)) {
			for (int z = 0; z < batch.getNSlices(); z++) {
				AffineTransform3D camera = new AffineTransform3D();
				camera.scale(1 / G.dxyUm);
				camera.translate(0, 0, -z);
				RawFrameRequest r = new RawFrameRequest(z + 1, 0, inputs, camera, batch.getWidth(), batch.getHeight(), Interpolation.NEARESTNEIGHBOR, layers);
				try (TiffRawVolumeStore.Ready ready = store.prepare(r.revision, RawStrictPlayer.wants(r), () -> false).get(10, TimeUnit.SECONDS)) {
					BufferedImage image = renderer.render(r, ready, () -> false);
					for (int y = 0; y < batch.getHeight(); y++) for (int x = 0; x < batch.getWidth(); x++) {
						int red = 0, green = 0, blue = 0;
						for (int c = 0; c < KEYS.length; c++) {
							int value = batch.getStack().getProcessor(batch.getStackIndex(c + 1, z + 1, 1)).get(x, y);
							ARGBType rgb = new ARGBType();
							converters.get(c).convert(new UnsignedShortType(value), rgb);
							red += ARGBType.red(rgb.get());
							green += ARGBType.green(rgb.get());
							blue += ARGBType.blue(rgb.get());
						}
						int expected = ARGBType.rgba(Math.min(255, red), Math.min(255, green), Math.min(255, blue), 255);
						assertEquals("registered=" + registered + " at " + x + "," + y + "," + z, expected & 0xffffff, image.getRGB(x, y) & 0xffffff);
						checked++;
						if ((expected & 0xffffff) != 0) coloured++;
					}
					assertEquals(0, ready.misses());
				}
			}
		} finally {
			batch.close();
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}
		assertTrue("nontrivial data in composite", coloured > checked / 4);
		System.out.println("multi-file NN composite, registered=" + registered + ": " + checked + " pixels, " + coloured + " coloured, all equal");
	}

	@Test
	public void theSuggestedBatchCpuReferenceIsLinearNotNearestNeighbour() throws Exception {
		files();
		Parameter p = Parameter.scratch();
		p.tryGPU = false;
		p.deskewMatrix = G.deskewMatrix(RawMultiFileTest.W / 2, RawMultiFileTest.H, RawMultiFileTest.D);
		p.alignmFile = "";
		p.alignMatrix = null;
		p.alignmentMatrices = null;
		ChannelOperationSettings settings = RawMultiFileTest.settings(folder.getRoot(), KEYS).channels;
		settings.interpolate = false;
		ImagePlus batch = MultiChannelDeskew.deskewGroup(Arrays.asList(folder.getRoot().listFiles()), p, settings, "CPU reference");
		ImagePlus nn = reference(false);
		try {
			assertNotNull(batch);
			assertEquals(nn.getStackSize(), batch.getStackSize());
			long changed = 0, count = 0;
			for (int z = 1; z <= nn.getStackSize(); z++)
				for (int y = 0; y < nn.getHeight(); y++) for (int x = 0; x < nn.getWidth(); x++) {
					if (nn.getStack().getProcessor(z).get(x, y) != batch.getStack().getProcessor(z).get(x, y)) changed++;
					count++;
				}
			System.out.println("Batch CPU fallback versus NN reference: " + changed + " / " + count + " voxels differ (CPU.transform uses Affine.LINEAR)");
			assertTrue("the suggested CPU fallback must not be claimed as an exact NN oracle", changed > 0);
		} finally { if (batch != null) batch.close(); nn.close(); }
	}
}
