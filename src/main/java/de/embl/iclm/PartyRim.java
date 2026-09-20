package de.embl.iclm;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**		A rainbow tube running counter-clockwise round the rim of a window
 * <p>	The moving part of {@link Party}: a rounded-rectangle tube of rainbow, shaded across its
 * <br>	width, with white glints lapping it faster than the rainbow and short-lived sparkles on
 * <br>	its crown. It is drawn into a margin the window already has, so no field moves when it
 * <br>	appears and none moves back when it goes.
 *
 * <p>	<b>The layout is computed once per window size and every frame is then a table lookup.</b>
 * <br>	{@link #layout} walks the margin pixel by pixel and records, for each pixel of the tube,
 * <br>	where it sits round the rim (0..1, counter-clockwise from the top), how far across the
 * <br>	tube it is - which gives the shading and the specular stripe - and its anti-aliasing
 * <br>	coverage at the two edges. {@link #render} then only turns a phase into a colour, which
 * <br>	is what makes 30 frames a second affordable beside a deskew.
 *
 * <p>	The image is kept in <b>device</b> pixels, so the tube is as smooth on a HiDPI screen as
 * <br>	on an ordinary one; {@link #paintOnto} scales the four margin strips back to window
 * <br>	units. The inside of the image is never drawn and never copied out, so nothing of the
 * <br>	dialog underneath is covered.
 *
 * <p>	Not thread safe, and not meant to be: every call comes from the event dispatch thread.
 *
 * @author ziqiang.huang@embl.de
 */
final class PartyRim {

	/** Tube thickness, in window units. A window needs this much margin to spare. */
	static final double RIM = 9d;
	/** Radius of the tube's centre line where it turns a corner, in window units. */
	private static final double CORNER = 12d;
	/** Rainbows visible round the rim at once. */
	private static final double CYCLES = 2d;
	/** Seconds for one rainbow to travel once round the rim. */
	private static final double LAP_SECONDS = 6d;
	/** The white glints lap faster than the rainbow, which is what reads as a polished tube. */
	private static final double GLINT_LAP_SECONDS = 2.4d;
	private static final int GLINTS = 2;
	/** Width of one glint along the tube, in device pixels. */
	private static final double GLINT_SIGMA = 16d;
	private static final double SPARKLES_PER_S = 7d;
	private static final double SPARKLE_LIFE_S = 0.7d;

	private final int[] rainbow = new int[1024];
	private final Random random = new Random();
	/** x, y, born, size - in device pixels and seconds. */
	private final List<double[]> sparkles = new ArrayList<double[]>();

	private int width = -1;			// client area, window units
	private int height = -1;
	private int margin = -1;
	private double scale = -1d;		// device pixels per window unit (HiDPI)
	private int background;

	private BufferedImage image;	// client area in device pixels; only the margins are shown
	private int[] pixels;
	private int imageWidth;
	private int imageHeight;
	private int imageMargin;
	private double perimeter;

	// one entry per pixel of the tube
	private int count;
	private int[] index;
	private double[] arc;			// 0..1 round the rim, counter-clockwise from the top
	private double[] tone;			// shading across the tube's width: darker at both edges
	private double[] shine;			// a specular stripe along the inner side of the tube
	private double[] cover;			// anti-aliasing coverage at the two edges

	private double lastSeconds = -1d;

	PartyRim () {
		for (int i = 0; i < rainbow.length; i++)
			rainbow[i] = Color.HSBtoRGB ( (float) ( i / (double) rainbow.length ), 0.80f, 1.0f );
	}

	/** Forget the layout, so the next frame lays it out again - after a background change. */
	void reset () {
		width = -1;
	}

	/**			Lay the tube out for this window size, or return at once when nothing changed
	 *
	 * @param w		: client width, window units
	 * @param h		: client height, window units
	 * @param m		: margin the tube is drawn into, window units
	 * @param s		: device pixels per window unit
	 * @param bg	: what the margin is filled with around the tube
	 */
	void layout (int w, int h, int m, double s, Color bg) {
		if (w == width && h == height && m == margin && s == scale && bg.getRGB() == background)
			return;
		width = w; height = h; margin = m; scale = s; background = bg.getRGB();
		imageWidth = Math.max ( 1, (int) Math.ceil ( w * s ) );
		imageHeight = Math.max ( 1, (int) Math.ceil ( h * s ) );
		imageMargin = Math.max ( 1, (int) Math.round ( m * s ) );
		image = new BufferedImage ( imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB );
		pixels = ( (DataBufferInt) image.getRaster().getDataBuffer() ).getData();
		Arrays.fill ( pixels, background );
		sparkles.clear();

		double half = RIM * s / 2d;
		double r = CORNER * s;
		// the tube's centre line, in device pixels
		double cx0 = half;
		double cy0 = half;
		double cx1 = imageWidth - half;
		double cy1 = imageHeight - half;
		double ix0 = cx0 + r;
		double ix1 = cx1 - r;
		double iy0 = cy0 + r;
		double iy1 = cy1 - r;
		double lh = ix1 - ix0;			// straight run along the top and the bottom
		double lv = iy1 - iy0;			// straight run down the sides
		double lc = Math.PI * r / 2d;	// one corner
		perimeter = 2d * lh + 2d * lv + 4d * lc;

		int capacity = 2 * imageMargin * ( imageWidth + imageHeight );
		index = new int[capacity]; arc = new double[capacity]; tone = new double[capacity];
		shine = new double[capacity]; cover = new double[capacity];
		count = 0;

		for (int y = 0; y < imageHeight; y++) {
			boolean fullRow = y < imageMargin || y >= imageHeight - imageMargin;
			for (int x = 0; x < imageWidth; x++) {
				if (!fullRow && x == imageMargin && imageWidth - imageMargin > x)
					x = imageWidth - imageMargin;	// the inside is never drawn
				double px = x + 0.5d;
				double py = y + 0.5d;
				double dx = px < ix0 ? px - ix0 : ( px > ix1 ? px - ix1 : 0d );
				double dy = py < iy0 ? py - iy0 : ( py > iy1 ? py - iy1 : 0d );
				double dist;	// from the centre line, positive towards the window edge
				double along;	// arc length, counter-clockwise: along the top edge leftwards first
				if (dx != 0d && dy != 0d) {
					dist = Math.hypot ( dx, dy ) - r;
					double ax = Math.abs ( dx );
					double ay = Math.abs ( dy );
					double quarter = Math.PI / 2d;
					if (dx < 0d && dy < 0d)	along = lh + lc * Math.atan2 ( ax, ay ) / quarter;
					else if (dx < 0d)		along = lh + lc + lv + lc * Math.atan2 ( ay, ax ) / quarter;
					else if (dy > 0d)		along = 2d * lh + 2d * lc + lv + lc * Math.atan2 ( ax, ay ) / quarter;
					else					along = 2d * lh + 3d * lc + 2d * lv + lc * Math.atan2 ( ay, ax ) / quarter;
				} else {
					boolean horizontal;
					if (dx != 0d) horizontal = false;
					else if (dy != 0d) horizontal = true;
					else horizontal = Math.min ( py - cy0, cy1 - py ) <= Math.min ( px - cx0, cx1 - px );
					if (horizontal) {
						if (py - cy0 <= cy1 - py) { dist = cy0 - py; along = ix1 - px; }							// top
						else					  { dist = py - cy1; along = lh + 2d * lc + lv + ( px - ix0 ); }	// bottom
					} else {
						if (px - cx0 <= cx1 - px) { dist = cx0 - px; along = lh + lc + ( py - iy0 ); }				// left
						else					  { dist = px - cx1; along = 2d * lh + 3d * lc + lv + ( iy1 - py ); }// right
					}
				}
				double coverage = Math.min ( 1d, half + 0.5d - Math.abs ( dist ) );
				if (coverage <= 0d) continue;
				double across = Math.max ( -1d, Math.min ( 1d, dist / half ) );	// -1 inner edge, +1 outer
				index[count] = y * imageWidth + x;
				arc[count] = along / perimeter;
				tone[count] = 0.74d + 0.26d * Math.cos ( across * Math.PI / 2d );
				double stripe = ( across + 0.38d ) / 0.26d;
				shine[count] = 0.6d * Math.exp ( -stripe * stripe );
				cover[count] = coverage;
				count++;
			}
		}
	}

	/** Draw the frame for this moment into the image. */
	void render (double seconds) {
		if (image == null) return;
		double dt = lastSeconds < 0d ? 0d : Math.max ( 0d, seconds - lastSeconds );
		lastSeconds = seconds;
		clearMargins();

		double lap = seconds / LAP_SECONDS;
		double glintLap = seconds / GLINT_LAP_SECONDS;
		double[] glints = new double[GLINTS];
		for (int i = 0; i < GLINTS; i++) {
			double g = glintLap + i / (double) GLINTS;
			glints[i] = g - Math.floor ( g );
		}
		double sigma = GLINT_SIGMA * scale;
		double reach = 3d * sigma / perimeter;
		int bgR = ( background >> 16 ) & 255;
		int bgG = ( background >> 8 ) & 255;
		int bgB = background & 255;

		for (int k = 0; k < count; k++) {
			double a = arc[k];
			// the pattern moves towards larger arc, which runs counter-clockwise on screen
			double hue = CYCLES * ( a - lap );
			hue -= Math.floor ( hue );
			int rgb = rainbow[ Math.min ( rainbow.length - 1, (int) ( hue * rainbow.length ) ) ];

			double glint = 0d;
			for (int i = 0; i < GLINTS; i++) {
				double d = Math.abs ( a - glints[i] );
				if (d > 0.5d) d = 1d - d;
				if (d < reach) {
					double u = d * perimeter / sigma;
					glint += Math.exp ( -u * u );
				}
			}
			double white = Math.min ( 1d, shine[k] + 0.9d * Math.min ( 1d, glint ) );
			double t = tone[k];
			double c = cover[k];
			pixels[index[k]] = ( mix ( ( rgb >> 16 ) & 255, t, white, c, bgR ) << 16 )
					| ( mix ( ( rgb >> 8 ) & 255, t, white, c, bgG ) << 8 )
					| mix ( rgb & 255, t, white, c, bgB );
		}
		drawSparkles ( seconds, dt );
	}

	private static int mix (int channel, double tone, double white, double cover, int bg) {
		double v = channel * tone;
		v += ( 255d - v ) * white;
		v = bg + ( v - bg ) * cover;
		return (int) Math.max ( 0d, Math.min ( 255d, v + 0.5d ) );
	}

	private void clearMargins () {
		int w = imageWidth;
		int h = imageHeight;
		int m = Math.min ( imageMargin, Math.min ( w, h ) );
		for (int y = 0; y < h; y++) {
			int row = y * w;
			if (y < m || y >= h - m) Arrays.fill ( pixels, row, row + w, background );
			else {
				Arrays.fill ( pixels, row, row + m, background );
				Arrays.fill ( pixels, row + w - m, row + w, background );
			}
		}
	}

	/** Short-lived four-pointed glints, born near the crown of the tube and fading out. */
	private void drawSparkles (double seconds, double dt) {
		double expected = SPARKLES_PER_S * dt;
		while (count > 0 && random.nextDouble() < expected) {
			expected -= 1d;
			for (int tries = 0; tries < 12; tries++) {
				int k = random.nextInt ( count );
				if (cover[k] < 1d || tone[k] < 0.9d) continue;	// somewhere near the tube's middle
				double x = index[k] % imageWidth + 0.5d;
				double y = Math.floorDiv ( index[k], imageWidth ) + 0.5d;
				sparkles.add ( new double[] { x, y, seconds, 0.7d + 0.6d * random.nextDouble() } );
				break;
			}
		}
		if (sparkles.isEmpty()) return;
		Graphics2D g = image.createGraphics();
		try {
			g.setRenderingHint ( RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON );
			Iterator<double[]> it = sparkles.iterator();
			while (it.hasNext()) {
				double[] s = it.next();
				double age = ( seconds - s[2] ) / SPARKLE_LIFE_S;
				if (age >= 1d || age < 0d) { it.remove(); continue; }
				double glow = Math.sin ( Math.PI * age );
				double len = ( 2.5d + 5d * glow ) * s[3] * scale;
				double x = s[0];
				double y = s[1];
				g.setColor ( new Color ( 255, 255, 255, (int) ( 240 * glow ) ) );
				g.setStroke ( new BasicStroke ( (float) ( 1.3d * scale ) ) );
				g.draw ( new Line2D.Double ( x - len, y, x + len, y ) );
				g.draw ( new Line2D.Double ( x, y - len, x, y + len ) );
				double d = len * 0.4d;
				g.setStroke ( new BasicStroke ( (float) ( 0.8d * scale ) ) );
				g.draw ( new Line2D.Double ( x - d, y - d, x + d, y + d ) );
				g.draw ( new Line2D.Double ( x - d, y + d, x + d, y - d ) );
				double dot = 1.6d * scale * ( 0.5d + glow );
				g.fill ( new Ellipse2D.Double ( x - dot, y - dot, 2d * dot, 2d * dot ) );
			}
		} finally {
			g.dispose();
		}
	}

	/** Copy the four margin strips onto the window; the inside of the image is never shown. */
	void paintOnto (Graphics g, int ox, int oy) {
		if (image == null) return;
		int w = width;
		int h = height;
		int m = margin;
		int iw = imageWidth;
		int ih = imageHeight;
		int im = imageMargin;
		g.drawImage ( image, ox, oy, ox + w, oy + m, 0, 0, iw, im, null );
		g.drawImage ( image, ox, oy + h - m, ox + w, oy + h, 0, ih - im, iw, ih, null );
		g.drawImage ( image, ox, oy + m, ox + m, oy + h - m, 0, im, im, ih - im, null );
		g.drawImage ( image, ox + w - m, oy + m, ox + w, oy + h - m, iw - im, im, iw, ih - im, null );
	}
}
