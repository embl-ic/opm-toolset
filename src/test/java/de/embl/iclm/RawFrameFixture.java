package de.embl.iclm;

import static org.junit.Assert.assertEquals;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import bdv.viewer.Interpolation;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * Three time points of a small two-half acquisition, with what the completed-frame tests ask of
 * them: views, frame requests, and each request's frame drawn independently from a store with
 * room for everything.
 */
final class RawFrameFixture implements AutoCloseable {

	static final int W = 64, H = 40, D = 20, ROWS = 8;
	/** Rows of a frame below which its label and pending line may be drawn. */
	static final int LABEL_ROWS = 44;
	static final RawGeometry GEOMETRY = new RawGeometry(0.265, 0.116, 33.5, "test");
	static final RawSourceSpec[] SPECS = { RawSourceSpec.half(null, 1, true, false, W, 0x00ff00),
			RawSourceSpec.half(null, 1, false, false, W, 0xff00ff) };

	final RawAcquisition acquisition;
	final List<RawAcquisition.TimeKey> times;
	final long cell;
	final TiffRawVolumeStore reference;
	final RawFrameRenderer referenceRenderer = new RawFrameRenderer(2);

	RawFrameFixture(File folder) throws Exception {
		this(written(folder), null);
	}

	private static File written(File folder) throws Exception {
		for (int t = 0; t < 3; t++) {
			final int base = 600 + 900 * t;
			RawTiffFixture.write(new File(folder, String.format("s_Time%06d_Channel0001_Frames_1_%d.tiff", t + 1, D)), W, H, D, ROWS, D,
					(x, y, k) -> base + (x * 7 + y * 13 + k * 29) % 500);
		}
		return folder;
	}

	/** Over three time points of this geometry that the caller wrote, with values of its own. */
	static RawFrameFixture over(File folder) throws Exception {
		return new RawFrameFixture(folder, null);
	}

	private RawFrameFixture(File folder, Void scanned) throws Exception {
		acquisition = RawAcquisition.scan(folder);
		times = acquisition.times(1, 1);
		assertEquals(3, times.size());
		for (int t = 0; t < 3; t++) assertEquals(RawAcquisition.Readiness.COMPLETE, entry(t).validate());
		cell = TiffRawVolumeStore.cellBytes(entry(0).layout());
		reference = new TiffRawVolumeStore(64L << 20, cell, 2, null);
	}

	RawAcquisition.Entry entry(int t) {
		return acquisition.entry(times.get(t), 1);
	}

	long[] dims() {
		return entry(0).layout().dimensions();
	}

	/**
	 * Down the deskewed Z at a fraction of the depth, both halves filling the screen above its label
	 * rows, turned by {@code angle} about the screen normal.
	 */
	static AffineTransform3D view(long[] dims, double depthFraction, double angle, int screenW, int screenH) {
		double[] min = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
		double[] max = { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
		double[] corner = new double[3], world = new double[3];
		for (RawSourceSpec spec : SPECS) {
			AffineTransform3D toWorld = GEOMETRY.sourceToWorld(dims, spec, null);
			for (int c = 0; c < 8; c++) {
				corner[0] = (c & 1) == 0 ? 0 : spec.cropWidth(dims[0]) - 1;
				corner[1] = (c & 2) == 0 ? 0 : dims[1] - 1;
				corner[2] = (c & 4) == 0 ? 0 : dims[2] - 1;
				toWorld.apply(corner, world);
				for (int d = 0; d < 3; d++) {
					min[d] = Math.min(min[d], world[d]);
					max[d] = Math.max(max[d], world[d]);
				}
			}
		}
		int imageH = screenH > 2 * LABEL_ROWS ? screenH - LABEL_ROWS : screenH;
		AffineTransform3D view = new AffineTransform3D();
		view.translate(-0.5 * (min[0] + max[0]), -0.5 * (min[1] + max[1]), -(min[2] + depthFraction * (max[2] - min[2])));
		view.rotate(2, angle);
		view.scale(0.9 * Math.min(screenW, imageH) / Math.max(max[0] - min[0], max[1] - min[1]));
		view.translate(0.5 * screenW, 0.5 * imageH, 0);
		return view;
	}

	/** Both halves, each placed by the geometry, displayed from {@code min} to {@code max} in its own colour. */
	RawFrameRequest request(long revision, int t, AffineTransform3D view, Interpolation method, double min, double max, int w, int h) {
		RawAcquisition.Entry e = entry(t);
		List<RawFrameRequest.Layer> layers = new ArrayList<RawFrameRequest.Layer>();
		for (RawSourceSpec spec : SPECS)
			layers.add(new RawFrameRequest.Layer(spec, GEOMETRY.sourceToWorld(e.layout().dimensions(), spec, null), min, max,
					0xff000000 | spec.rgb));
		return new RawFrameRequest(revision, t, e, view, w, h, method, layers);
	}

	/** The request's frame, drawn independently from a store with room for everything. */
	BufferedImage referenceImage(RawFrameRequest r) throws Exception {
		return referenceRenderer.render(r, reference::blocking, () -> false);
	}

	/** The request's frame as the presenter paints it, over black: its first {@code rows} rows. */
	int[] referenceOnBlack(RawFrameRequest r, int rows) throws Exception {
		BufferedImage image = referenceImage(r);
		BufferedImage screen = new BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = screen.createGraphics();
		g.setColor(Color.BLACK);
		g.fillRect(0, 0, r.width, r.height);
		g.drawImage(image, 0, 0, null);
		g.dispose();
		return screen.getRGB(0, 0, r.width, rows, null, 0, r.width);
	}

	/** A digest of an image's first rows: what a test keeps instead of the pixels. */
	static int rowsHash(BufferedImage image, int rows) {
		return Arrays.hashCode(image.getRGB(0, 0, image.getWidth(), rows, null, 0, image.getWidth()));
	}

	static void touch(RawAcquisition.Entry e) {
		try {
			Files.setLastModifiedTime(e.file.toPath(), FileTime.fromMillis(e.key().modified + 5000));
		} catch (java.io.IOException x) {
			throw new IllegalStateException(x);
		}
	}

	@Override
	public void close() throws Exception {
		referenceRenderer.close();
		reference.closeAsync().get(10, TimeUnit.SECONDS);
	}
}
