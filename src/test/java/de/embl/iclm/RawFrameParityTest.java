package de.embl.iclm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import bdv.viewer.BasicViewerState;
import bdv.viewer.DisplayMode;
import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.display.RealARGBColorConverter;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.view.Views;

/**
 * The staged frame against BigDataViewer's own renderer, judged by an independent oracle (joint
 * contract C6.3; agent-B's S6).
 *
 * <p>For every pixel the oracle works out, from the raw voxels and nothing either renderer
 * computes, the colours the pixel may legally have:
 * <ul>
 * <li><b>nearest neighbour</b>: the voxel ImgLib2 rounds the sample position to - or, where a
 *     coordinate lies within {@link #DELTA} of half way between two voxels, either of the two,
 *     since two floating-point paths to the same position may round it either way;</li>
 * <li><b>trilinear</b>: the exact interpolated value, widened by the most the position's
 *     floating-point uncertainty ({@link #DELTA}) can move it, and every integer that interval can
 *     round to - so two candidates only where the value lies within a hair of a rounding threshold;</li>
 * <li>each half's candidates through that half's own colour converter, and every combination of
 *     the halves summed per channel and clamped, as BigDataViewer accumulates them;</li>
 * <li>culling, a declared policy: the staged frame draws a half only inside its box on screen.
 *     BigDataViewer culls by tiles, so outside a half's box it may also draw that half - which
 *     there samples nothing but the zero extension. Inside a half's box both must draw it.</li>
 * </ul>
 * Both renderers must give a legal colour in every pixel. Where the legal colour is one, both give
 * exactly it. The oracle itself is shown to reject a one-level error away from a tie.
 */
public class RawFrameParityTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	/** How far, in voxels, two floating-point paths to one sample position may land apart: far more than they do. */
	private static final double DELTA = 1e-9;
	private static final int SW = 96, SH = 120;

	private RawFrameFixture f;

	@Before
	public void fixture() throws Exception {
		f = new RawFrameFixture(folder.newFolder());
	}

	@After
	public void close() throws Exception {
		f.close();
	}

	/* ---------------------------------------------------------------- the oracle */

	/** What the oracle allows a pixel: per half, whether its box holds the pixel and the colours it may draw there. */
	private static final class Pixel {
		final boolean[] inBox;
		final List<Set<Integer>> colours = new ArrayList<Set<Integer>>();

		Pixel(int layers) {
			inBox = new boolean[layers];
		}

		/** The legal RGB colours: for the staged frame, or for BigDataViewer, which may draw a half outside its box too. */
		Set<Integer> legal(boolean bigDataViewer) {
			List<int[]> sums = new ArrayList<int[]>();
			sums.add(new int[4]);
			for (int i = 0; i < inBox.length; i++) {
				List<int[]> options = new ArrayList<int[]>();
				if (!inBox[i]) options.add(new int[4]);
				if (inBox[i] || bigDataViewer)
					for (int c : colours.get(i))
						options.add(new int[] { ARGBType.alpha(c), ARGBType.red(c), ARGBType.green(c), ARGBType.blue(c) });
				List<int[]> next = new ArrayList<int[]>();
				for (int[] s : sums)
					for (int[] o : options) next.add(new int[] { s[0] + o[0], s[1] + o[1], s[2] + o[2], s[3] + o[3] });
				sums = next;
			}
			Set<Integer> rgb = new HashSet<Integer>();
			for (int[] s : sums) rgb.add(ARGBType.rgba(Math.min(s[1], 255), Math.min(s[2], 255), Math.min(s[3], 255), 0) & 0xffffff);
			return rgb;
		}
	}

	private static Pixel oracle(TiffRawVolumeStore store, RawFrameRequest r, int x, int y) {
		Pixel px = new Pixel(r.layers.size());
		for (int i = 0; i < r.layers.size(); i++) {
			RawFrameRequest.Layer l = r.layers.get(i);
			px.inBox[i] = inBox(r, l, x, y);
			px.colours.add(colours(store, r, l, x, y));
		}
		return px;
	}

	/** Reuse the independent raw-voxel oracle when checking pixels actually painted by the GUI. */
	static void assertLegalPixel(TiffRawVolumeStore reference, RawFrameRequest request, int x, int y, int argb) {
		Set<Integer> legal = oracle(reference, request, x, y).legal(false);
		assertTrue("pixel (" + x + "," + y + ") at " + request + ": " + Integer.toHexString(argb)
				+ " not in " + hex(legal), legal.contains(argb & 0xffffff));
	}

	/** The box rule, from its definition: the half's voxels widened by the interpolation's reach, mapped to the screen. */
	private static boolean inBox(RawFrameRequest r, RawFrameRequest.Layer l, int x, int y) {
		long[] dims = r.layout.dimensions();
		double e = r.interpolation == Interpolation.NEARESTNEIGHBOR ? 0.5 : 1.0;
		double[] lo = { -e, -e, -e }, hi = { l.spec.cropWidth(dims[0]) - 1 + e, dims[1] - 1 + e, dims[2] - 1 + e };
		AffineTransform3D t = r.sourceToScreen(l);
		double[] min = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE }, max = { -Double.MAX_VALUE, -Double.MAX_VALUE,
				-Double.MAX_VALUE };
		double[] corner = new double[3], screen = new double[3];
		for (int c = 0; c < 8; c++) {
			for (int d = 0; d < 3; d++) corner[d] = (c >> d & 1) == 0 ? lo[d] : hi[d];
			t.apply(corner, screen);
			for (int d = 0; d < 3; d++) {
				min[d] = Math.min(min[d], screen[d]);
				max[d] = Math.max(max[d], screen[d]);
			}
		}
		if (max[2] < 0 || min[2] > 0 || max[0] < 0 || min[0] > r.width - 1 || max[1] < 0 || min[1] > r.height - 1) return false;
		return x >= Math.floor(min[0]) && x <= Math.ceil(max[0]) && y >= Math.floor(min[1]) && y <= Math.ceil(max[1]);
	}

	/** The ARGB colours one half may draw at screen pixel (x, y), from its raw voxels. */
	private static Set<Integer> colours(TiffRawVolumeStore store, RawFrameRequest r, RawFrameRequest.Layer l, int x, int y) {
		double[] p = new double[3];
		r.sourceToScreen(l).inverse().apply(new double[] { x, y, 0 }, p);
		RandomAccessibleInterval<UnsignedShortType> part = RawSampling.crop(store.blocking(r.key, r.layout), l.spec);
		RandomAccess<UnsignedShortType> raw = Views.extendZero(part).randomAccess();
		Set<Long> values = new TreeSet<Long>();
		if (r.interpolation == Interpolation.NEARESTNEIGHBOR) {
			long[][] choices = new long[3][];
			for (int d = 0; d < 3; d++) {
				double c = p[d], fl = Math.floor(c);
				choices[d] = Math.abs(c - fl - 0.5) < DELTA ? new long[] { (long) fl, (long) fl + 1 }
						: new long[] { c < 0 ? (long) (c - 0.5) : (long) (c + 0.5) };		// ImgLib2's Round
			}
			for (long a : choices[0])
				for (long b : choices[1])
					for (long c : choices[2]) {
						raw.setPosition(new long[] { a, b, c });
						values.add((long) raw.get().get());
					}
		} else {
			long[] base = new long[3];
			double[] w = new double[3];
			for (int d = 0; d < 3; d++) {
				base[d] = (long) Math.floor(p[d]);
				w[d] = p[d] - base[d];
			}
			double[] v = new double[8];
			double t = 0;
			for (int c = 0; c < 8; c++) {
				double weight = 1;
				long[] at = new long[3];
				for (int d = 0; d < 3; d++) {
					boolean up = (c >> d & 1) == 1;
					at[d] = base[d] + (up ? 1 : 0);
					weight *= up ? w[d] : 1 - w[d];
				}
				raw.setPosition(at);
				v[c] = raw.get().get();
				t += weight * v[c];
			}
			double slope = 0;		// how far the value can move per voxel of position, summed over the axes
			for (int d = 0; d < 3; d++) {
				double most = 0;
				for (int c = 0; c < 8; c++) most = Math.max(most, Math.abs(v[c] - v[c ^ (1 << d)]));
				slope += most;
			}
			double eps = DELTA * slope + 1e-9 * Math.max(1, Math.abs(t));
			double lo = Math.max(0, Math.min(65535, t - eps)), hi = Math.max(0, Math.min(65535, t + eps));
			for (long k = (long) (lo + 0.5); k <= (long) (hi + 0.5); k++) values.add(k);		// ImgLib2's round, for values >= 0
		}
		RealARGBColorConverter<UnsignedShortType> convert = RealARGBColorConverter.create(new UnsignedShortType(), l.min, l.max);
		convert.setColor(new ARGBType(l.argb));
		Set<Integer> colours = new HashSet<Integer>();
		ARGBType argb = new ARGBType();
		for (long value : values) {
			convert.convert(new UnsignedShortType((int) value), argb);
			colours.add(argb.get());
		}
		return colours;
	}

	/* ---------------------------------------------------------------- BigDataViewer's own frame */

	/** A source placed by a transform of the test's choosing, everything else its own. */
	private static final class Placed<T> implements Source<T> {
		private final Source<T> source;
		private final AffineTransform3D toWorld;

		Placed(Source<T> source, AffineTransform3D toWorld) {
			this.source = source;
			this.toWorld = toWorld.copy();
		}

		@Override public boolean isPresent(int t) { return source.isPresent(t); }
		@Override public RandomAccessibleInterval<T> getSource(int t, int level) { return source.getSource(t, level); }
		@Override public RealRandomAccessible<T> getInterpolatedSource(int t, int level, Interpolation method) {
			return source.getInterpolatedSource(t, level, method);
		}
		@Override public void getSourceTransform(int t, int level, AffineTransform3D transform) { transform.set(toWorld); }
		@Override public T getType() { return source.getType(); }
		@Override public String getName() { return source.getName(); }
		@Override public VoxelDimensions getVoxelDimensions() { return source.getVoxelDimensions(); }
		@Override public int getNumMipmapLevels() { return 1; }
	}

	/** BigDataViewer's own renderer over the request's halves, each placed by the request's own transform. */
	private int[] bigDataViewersFrame(RawFrameRequest r) {
		BasicViewerState state = new BasicViewerState();
		for (RawFrameRequest.Layer l : r.layers) {
			RawPartSource<UnsignedShortType> source = new RawPartSource<UnsignedShortType>(f.acquisition, f.times, l.spec,
					RawFrameFixture.GEOMETRY, f.reference::blocking, new UnsignedShortType(), r.layout.dimensions());
			RealARGBColorConverter<UnsignedShortType> c = RealARGBColorConverter.create(new UnsignedShortType(), l.min, l.max);
			c.setColor(new ARGBType(l.argb));
			SourceAndConverter<UnsignedShortType> soc = new SourceAndConverter<UnsignedShortType>(
					new Placed<UnsignedShortType>(source, l.sourceToWorld), c);
			state.addSource(soc);
			state.setSourceActive(soc, true);
		}
		state.setDisplayMode(DisplayMode.FUSED);
		state.setNumTimepoints(f.times.size());
		state.setCurrentTimepoint(r.timeIndex);
		state.setInterpolation(r.interpolation);
		state.setViewerTransform(r.viewer);
		return BdvTestRenderer.paint(state, r.width, r.height).getRGB(0, 0, r.width, r.height, null, 0, r.width);
	}

	private int[] staged(RawFrameRequest r) throws Exception {
		return f.referenceImage(r).getRGB(0, 0, r.width, r.height, null, 0, r.width);
	}

	/** Counts: pixels, those with more than one legal colour, those where the two renderers differ, those where BigDataViewer drew an excluded half. */
	private long[] judge(RawFrameRequest r, String what) throws Exception {
		int[] bdv = bigDataViewersFrame(r), staged = staged(r);
		long[] counts = new long[4];
		for (int y = 0; y < r.height; y++)
			for (int x = 0; x < r.width; x++) {
				int p = y * r.width + x, s = staged[p] & 0xffffff, b = bdv[p] & 0xffffff;
				Pixel px = oracle(f.reference, r, x, y);
				Set<Integer> legalStaged = px.legal(false), legalBdv = px.legal(true);
				if (!legalStaged.contains(s))
					fail(String.format("%s, pixel (%d, %d): the staged frame drew %06x; legal %s", what, x, y, s, hex(legalStaged)));
				if (!legalBdv.contains(b))
					fail(String.format("%s, pixel (%d, %d): BigDataViewer drew %06x; legal %s", what, x, y, b, hex(legalBdv)));
				counts[0]++;
				if (legalStaged.size() > 1) counts[1]++;
				if (s != b) counts[2]++;
				if (s != b && !legalStaged.contains(b)) counts[3]++;		// only what BigDataViewer's tiles add
			}
		return counts;
	}

	private static String hex(Set<Integer> colours) {
		List<String> out = new ArrayList<String>();
		for (int c : colours) out.add(String.format("%06x", c));
		return out.toString();
	}

	/* ---------------------------------------------------------------- tests */

	@Test
	public void worldPlanesKeepParityForBothDeskewSignsAndRegisteredHalves() throws Exception {
		long pixels = 0;
		for (double angle : new double[] { 33.5, -33.5 }) {
			RawGeometry geometry = new RawGeometry(0.265, 0.116, angle, "test");
			double theta = 0.08;
			AlignmentMatrixSet alignment = AlignmentMatrixSet.legacy(new double[][] {
				{ Math.cos(theta), -Math.sin(theta), 2 }, { Math.sin(theta), Math.cos(theta), -1 } });
			List<RawFrameRequest.Layer> layers = new ArrayList<RawFrameRequest.Layer>();
			for (boolean left : new boolean[] { true, false }) {
				RawSourceSpec spec = RawSourceSpec.half(alignment, 1, left, false, RawFrameFixture.W, left ? 0x00ff00 : 0xff00ff);
				layers.add(new RawFrameRequest.Layer(spec, geometry.sourceToWorld(f.dims(), spec, null), 0, 2500, 0xff000000 | spec.rgb));
			}
			// An interior raw point in the left half anchors all three world planes.
			double[] centre = new double[3];
			layers.get(0).sourceToWorld.apply(new double[] { 14.2, 19.1, 9.3 }, centre);
			AffineTransform3D initial = new AffineTransform3D();
			initial.translate(-centre[0], -centre[1], -centre[2]); initial.scale(16); initial.translate(SW / 2.0, SH / 2.0, 0);
			for (RawWorldView plane : RawWorldView.values())
				for (Interpolation interpolation : Interpolation.values()) {
					RawFrameRequest r = new RawFrameRequest(1, 0, f.entry(0), plane.orient(initial, SW, SH), SW, SH, interpolation, layers);
					pixels += judge(r, plane + ", deskew " + angle + ", " + interpolation)[0];
				}
		}
		System.out.println("World-plane parity: " + pixels + " pixels checked against raw-voxel oracle and BDV");
	}

	/**
	 * Both interpolations, a straight and a turned view, two time points, display minimums 0 and
	 * 300: every pixel of both renderers is a legal colour, and they differ only where the oracle
	 * allows more than one.
	 */
	@Test
	public void everyPixelOfBothRenderersIsALegalColour() throws Exception {
		long[] dims = f.dims();
		long[] total = new long[4];
		int frames = 0;
		for (Interpolation method : Interpolation.values())
			for (double angle : new double[] { 0, 0.37 })
				for (int t = 0; t < 2; t++)
					for (double min : new double[] { 0, 300 }) {
						RawFrameRequest r = f.request(1, t, RawFrameFixture.view(dims, 0.45, angle, SW, SH), method, min, 2500, SW, SH);
						long[] c = judge(r, String.format("%s, angle %.2f, t %d, minimum %.0f", method, angle, t, min));
						for (int i = 0; i < 4; i++) total[i] += c[i];
						frames++;
					}
		System.out.printf("parity: %d frames, %d pixels; %d have more than one legal colour; the renderers differ in %d, every one of them "
				+ "among those; BigDataViewer's tiles added an excluded half in %d%n", frames, total[0], total[1], total[2], total[3]);
		assertEquals(16, frames);
		assertEquals("at a minimum of 0 or more an excluded half adds nothing", 0, total[3]);
		assertTrue("the ambiguous pixels are rare: " + total[1], total[1] * 100 < total[0]);
	}

	/**
	 * A negative display minimum draws the zero extension grey, so culling shows - with the halves
	 * overlapping as placed, and with them pulled apart and the view turned. Inside each half's box
	 * both renderers draw that half; only BigDataViewer may add an excluded half's zero, and the
	 * other half's contribution is never touched.
	 */
	@Test
	public void underANegativeMinimumOnlyAnExcludedHalfsZeroExtensionMayDiffer() throws Exception {
		long[] dims = f.dims();
		long[] total = new long[4];
		for (Interpolation method : Interpolation.values())
			for (double angle : new double[] { 0, 0.37 }) {
				RawFrameRequest overlapping = f.request(1, 0, RawFrameFixture.view(dims, 0.45, angle, SW, SH), method, -1000, 2500, SW, SH);
				long[] c = judge(overlapping, String.format("overlapping, %s, angle %.2f", method, angle));
				for (int i = 0; i < 4; i++) total[i] += c[i];
				RawFrameRequest apart = pulledApart(overlapping, angle);
				c = judge(apart, String.format("pulled apart, %s, angle %.2f", method, angle));
				for (int i = 0; i < 4; i++) total[i] += c[i];
			}
		System.out.printf("negative minimum: %d pixels; %d with more than one legal colour; the renderers differ in %d, of which "
				+ "BigDataViewer's tiles added an excluded half in %d%n", total[0], total[1], total[2], total[3]);
	}

	/** The right half moved off the left one in world X, and the view widened and turned so both boxes are on screen apart. */
	private RawFrameRequest pulledApart(RawFrameRequest r, double angle) {
		List<RawFrameRequest.Layer> layers = new ArrayList<RawFrameRequest.Layer>();
		RawFrameRequest.Layer left = r.layers.get(0), right = r.layers.get(1);
		layers.add(left);
		AffineTransform3D shifted = right.sourceToWorld.copy();
		shifted.translate(RawFrameFixture.W * 0.116 * 0.7, 0, 0);
		layers.add(new RawFrameRequest.Layer(right.spec, shifted, right.min, right.max, right.argb));
		AffineTransform3D view = r.viewer.copy();
		view.translate(-0.5 * SW, -0.5 * (SH - RawFrameFixture.LABEL_ROWS), 0);
		view.scale(0.6);
		view.rotate(2, 0.2 + angle);
		view.translate(0.5 * SW, 0.5 * (SH - RawFrameFixture.LABEL_ROWS), 0);
		return new RawFrameRequest(r.revision, r.timeIndex, r.entry, view, r.width, r.height, r.interpolation, layers);
	}

	/**
	 * The oracle rejects a one-level error where there is no tie: agent-B's constant 1000 at
	 * integer coordinates (exactly 85 in each channel); a pixel of a real frame with one legal
	 * colour; and a tie in one half, which does not excuse a difference in a channel only the other
	 * half draws.
	 */
	@Test
	public void theOracleRejectsAOneLevelErrorAwayFromATie() throws Exception {
		File constant = folder.newFolder();
		for (int t = 1; t <= 2; t++)
			RawTiffFixture.write(new File(constant, "s_Time" + t + "_Channel1_Frames_1_6.tiff"), RawFrameFixture.W, RawFrameFixture.H, 6, 8,
					6, (x, y, z) -> 1000);
		RawAcquisition acquisition = RawAcquisition.scan(constant);
		RawAcquisition.Entry e = acquisition.entry(acquisition.times(1, 1).get(0), 1);
		assertEquals(RawAcquisition.Readiness.COMPLETE, e.validate());
		TiffRawVolumeStore store = new TiffRawVolumeStore(16L << 20, f.cell, 1, null);
		try {
			AffineTransform3D identity = new AffineTransform3D();
			RawFrameRequest flat = new RawFrameRequest(1, 0, e, identity, SW, SH, Interpolation.NLINEAR, Arrays.asList(
					new RawFrameRequest.Layer(RawFrameFixture.SPECS[0], identity, 0, 3000, 0xff00ff00),
					new RawFrameRequest.Layer(RawFrameFixture.SPECS[1], identity, 0, 3000, 0xffff00ff)));
			Set<Integer> legal = oracle(store, flat, 10, 10).legal(false);
			assertEquals("exactly 85 in every channel", new HashSet<Integer>(Arrays.asList(0x555555)), legal);
			assertFalse("one level more in blue is not a tie", legal.contains(0x555556));
		} finally {
			store.closeAsync().get(10, TimeUnit.SECONDS);
		}

		// a real frame: an interior pixel with one legal colour, its blue one level off
		RawFrameRequest r = f.request(1, 0, RawFrameFixture.view(f.dims(), 0.45, 0.37, SW, SH), Interpolation.NLINEAR, 0, 2500, SW, SH);
		int[] staged = staged(r);
		boolean found = false;
		for (int y = SH / 3; y < SH / 2 && !found; y++)
			for (int x = SW / 3; x < 2 * SW / 3 && !found; x++) {
				Pixel px = oracle(f.reference, r, x, y);
				Set<Integer> legalHere = px.legal(false);
				int s = staged[y * SW + x] & 0xffffff;
				if (legalHere.size() != 1 || (s & 0xff) == 0 || (s & 0xff) == 0xff) continue;
				assertTrue(legalHere.contains(s));
				assertFalse("a one-level error in an interior pixel is rejected", legalHere.contains(s + 1));
				found = true;
			}
		assertTrue("an interior pixel with one legal colour", found);

		// nearest neighbour: a tie in one half allows that half's two values, never another value of the other half's channel
		RawFrameRequest n = f.request(1, 0, RawFrameFixture.view(f.dims(), 0.45, 0, SW, SH), Interpolation.NEARESTNEIGHBOR, 0, 2500, SW,
				SH);
		boolean tied = false;
		for (int y = 0; y < SH && !tied; y++)
			for (int x = 0; x < SW && !tied; x++) {
				Pixel px = oracle(f.reference, n, x, y);
				boolean leftTied = px.colours.get(0).size() > 1, rightTied = px.colours.get(1).size() > 1;
				if (!(px.inBox[0] && px.inBox[1]) || leftTied == rightTied) continue;
				Set<Integer> legalHere = px.legal(false);
				assertTrue("the tie allows more than one colour", legalHere.size() > 1);
				int shift = leftTied ? 16 : 8;		// red is drawn by the right (magenta) half alone, green by the left
				assertEquals("the untied half's own channel is fixed", 1, channel(legalHere, shift).size());
				int any = legalHere.iterator().next();
				assertFalse("a tie in one half does not excuse a one-level difference in the other's",
						legalHere.contains(any ^ (1 << shift)));
				tied = true;
			}
		assertTrue("a pixel where only one half is at a tie", tied);
	}

	private static Set<Integer> channel(Set<Integer> colours, int shift) {
		Set<Integer> values = new HashSet<Integer>();
		for (int c : colours) values.add((c >> shift) & 0xff);
		return values;
	}
}
