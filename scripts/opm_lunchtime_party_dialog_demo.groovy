/*
 * Lunch-time party theme for OPM Toolset dialogs - a standalone demo.
 *
 * Between 12:00 and 13:30 local time the dialog turns from the toolset's light blue
 * (Parameter.frameColor) to pink-purple, and a rainbow tube with white glints and
 * twinkles runs counter-clockwise round its rim.
 *
 * Run it from Fiji: File > New > Script..., Language > Groovy, paste or open this file,
 * press Run. It needs nothing but Fiji. None of the fields are connected to anything;
 * OK only writes what was entered to the Log.
 *
 * The "theme" choice at the bottom of the dialog switches between
 *   auto          the real trigger: party from 12:00 to 13:30, office blue otherwise.
 *                 The clock is read twice a second, so a dialog left open across 12:00
 *                 or 13:30 changes on its own.
 *   party now     always on, so the effect can be seen at any hour (the demo's default)
 *   office blue   the toolset's usual look
 *
 * How it works, since this is what the toolset itself would need:
 *   - GenericDialog.getInsets() already adds a 10 px margin round every dialog, and the
 *     rim is painted into that margin (plus 3 px here), so no field moves.
 *   - An AWT dialog paints its own background only in paint(), so the rim needs a
 *     GenericDialog subclass. A javax.swing.Timer repaints just the margin at ~30 fps;
 *     update() is overridden so the repaint does not clear the dialog first (no flicker).
 *   - The rim is computed per pixel (arc position round a rounded rectangle, and
 *     position across the tube) once per window size; each frame is then a table lookup.
 */

import groovy.transform.CompileStatic
import ij.IJ
import ij.Prefs
import ij.gui.GenericDialog
import ij.gui.NonBlockingGenericDialog

import javax.swing.Timer
import javax.swing.UIManager
import java.awt.AWTEvent
import java.awt.BasicStroke
import java.awt.Button
import java.awt.Checkbox
import java.awt.Choice
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.Label
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Scrollbar
import java.awt.TextComponent
import java.awt.TextField
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.ItemListener
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** What the demo opens with. PartyTheme.AUTO obeys the clock from the first frame. */
String START_MODE = PartyTheme.PARTY_NOW


// ------------------------------------------------------------------------------------------
// When to party, and in which colours
// ------------------------------------------------------------------------------------------

@CompileStatic
class PartyTheme {
	static final String AUTO      = "auto (12:00 - 13:30 local time)"
	static final String PARTY_NOW = "party now"
	static final String OFFICE    = "office blue"
	static final String[] MODES   = [AUTO, PARTY_NOW, OFFICE] as String[]

	static final LocalTime START = LocalTime.of(12, 0)
	static final LocalTime END   = LocalTime.of(13, 30)

	/** Parameter.frameColor - the toolset's usual light blue, HSB 210 deg / 0.20 / 1.0. */
	static final Color OFFICE_BACKGROUND = new Color(204, 229, 255)
	/** The same pale lightness, turned round the colour wheel to pink-purple (298 deg). */
	static final Color PARTY_BACKGROUND  = Color.getHSBColor((float) (298d / 360d), 0.24f, 1.0f)
	static final Color PARTY_HEADING     = new Color(150, 20, 140)
	static final Color OFFICE_HEADING    = Color.BLACK

	static boolean inWindow (LocalTime now) {
		return !now.isBefore(START) && now.isBefore(END)
	}

	static boolean active (String mode, LocalTime now) {
		if (PARTY_NOW == mode) return true
		if (OFFICE == mode) return false
		return inWindow(now)
	}

	static String describe (String mode, LocalTime now) {
		String clock = now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
		if (PARTY_NOW == mode) return clock + "   party mode forced on"
		if (OFFICE == mode)    return clock + "   office blue forced on"
		if (inWindow(now))     return clock + "   party until " + END
		long minutes = Duration.between(now, START).toMinutes()
		if (minutes < 0) minutes += 24 * 60
		return clock + "   office blue, party in " + Math.floorDiv(minutes, 60L) + " h " +
				Math.floorMod(minutes, 60L) + " min"
	}
}


// ------------------------------------------------------------------------------------------
// The rim: a rainbow tube round a rounded rectangle, running counter-clockwise
// ------------------------------------------------------------------------------------------

@CompileStatic
class RainbowRim {
	static final double RIM               = 9d    // tube thickness, px
	static final double CORNER            = 12d   // radius of the tube's centre line at a corner
	static final double CYCLES            = 2d    // rainbows round the rim at once
	static final double LAP_SECONDS       = 6d    // one rainbow travels once round in this time
	static final double GLINT_LAP_SECONDS = 2.4d  // the white glints lap faster
	static final int    GLINTS            = 2
	static final double GLINT_SIGMA       = 16d   // px
	static final double SPARKLES_PER_S    = 7d
	static final double SPARKLE_LIFE_S    = 0.7d

	private final int[] rainbow = new int[1024]
	private final Random random = new Random()
	private final List<double[]> sparkles = new ArrayList<double[]>()   // x, y, born, size

	private int width = -1          // client area, window units
	private int height = -1
	private int margin = -1
	private double scale = -1d      // device pixels per window unit (HiDPI)
	private int background = 0

	private BufferedImage image     // client area in device pixels; only the margins are shown
	private int[] pixels
	private int imageWidth
	private int imageHeight
	private int imageMargin
	private double perimeter

	// one entry per pixel of the tube
	private int count
	private int[] index
	private double[] arc            // 0..1 round the rim, counter-clockwise from the top
	private double[] tone           // tube shading across its width: darker edges
	private double[] shine          // a specular stripe along the inner side of the tube
	private double[] cover          // anti-aliasing coverage at both edges

	private double lastSeconds = -1d

	RainbowRim () {
		for (int i = 0; i < rainbow.length; i++)
			rainbow[i] = Color.HSBtoRGB((float) (i / (double) rainbow.length), 0.80f, 1.0f)
	}

	/** Forget the layout, so the next frame re-lays it out (new background colour). */
	void reset () {
		width = -1
	}

	void layout (int w, int h, int m, double s, Color bg) {
		if (w == width && h == height && m == margin && s == scale && bg.getRGB() == background)
			return
		width = w; height = h; margin = m; scale = s; background = bg.getRGB()
		imageWidth = Math.max(1, (int) Math.ceil(w * s))
		imageHeight = Math.max(1, (int) Math.ceil(h * s))
		imageMargin = Math.max(1, (int) Math.round(m * s))
		image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB)
		pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData()
		Arrays.fill(pixels, background)
		sparkles.clear()

		double half = RIM * s / 2d
		double r = CORNER * s
		// the tube's centre line, in device pixels
		double cx0 = half
		double cy0 = half
		double cx1 = imageWidth - half
		double cy1 = imageHeight - half
		double ix0 = cx0 + r
		double ix1 = cx1 - r
		double iy0 = cy0 + r
		double iy1 = cy1 - r
		double lh = ix1 - ix0
		double lv = iy1 - iy0
		double lc = Math.PI * r / 2d
		perimeter = 2d * lh + 2d * lv + 4d * lc

		int capacity = 2 * imageMargin * (imageWidth + imageHeight)
		index = new int[capacity]; arc = new double[capacity]; tone = new double[capacity]
		shine = new double[capacity]; cover = new double[capacity]
		count = 0

		for (int y = 0; y < imageHeight; y++) {
			boolean fullRow = y < imageMargin || y >= imageHeight - imageMargin
			for (int x = 0; x < imageWidth; x++) {
				if (!fullRow && x == imageMargin && imageWidth - imageMargin > x)
					x = imageWidth - imageMargin   // skip the inside
				double px = x + 0.5d
				double py = y + 0.5d
				double dx = px < ix0 ? px - ix0 : (px > ix1 ? px - ix1 : 0d)
				double dy = py < iy0 ? py - iy0 : (py > iy1 ? py - iy1 : 0d)
				double dist     // from the centre line, positive towards the window edge
				double along    // arc length, counter-clockwise: top edge leftwards first
				if (dx != 0d && dy != 0d) {
					dist = Math.hypot(dx, dy) - r
					double ax = Math.abs(dx)
					double ay = Math.abs(dy)
					double quarter = Math.PI / 2d
					if (dx < 0d && dy < 0d)      along = lh + lc * Math.atan2(ax, ay) / quarter
					else if (dx < 0d)            along = lh + lc + lv + lc * Math.atan2(ay, ax) / quarter
					else if (dy > 0d)            along = 2d * lh + 2d * lc + lv + lc * Math.atan2(ax, ay) / quarter
					else                         along = 2d * lh + 3d * lc + 2d * lv + lc * Math.atan2(ay, ax) / quarter
				} else {
					boolean horizontal
					if (dx != 0d) horizontal = false
					else if (dy != 0d) horizontal = true
					else horizontal = Math.min(py - cy0, cy1 - py) <= Math.min(px - cx0, cx1 - px)
					if (horizontal) {
						if (py - cy0 <= cy1 - py) { dist = cy0 - py; along = ix1 - px }                        // top
						else                      { dist = py - cy1; along = lh + 2d * lc + lv + (px - ix0) }  // bottom
					} else {
						if (px - cx0 <= cx1 - px) { dist = cx0 - px; along = lh + lc + (py - iy0) }                     // left
						else                      { dist = px - cx1; along = 2d * lh + 3d * lc + lv + (iy1 - py) }      // right
					}
				}
				double coverage = Math.min(1d, half + 0.5d - Math.abs(dist))
				if (coverage <= 0d) continue
				double across = Math.max(-1d, Math.min(1d, dist / half))  // -1 inner edge, +1 outer edge
				index[count] = y * imageWidth + x
				arc[count] = along / perimeter
				tone[count] = 0.74d + 0.26d * Math.cos(across * Math.PI / 2d)
				double stripe = (across + 0.38d) / 0.26d
				shine[count] = 0.6d * Math.exp(-stripe * stripe)
				cover[count] = coverage
				count++
			}
		}
	}

	/** Draw the frame for this moment into the image. */
	void render (double seconds) {
		double dt = lastSeconds < 0d ? 0d : Math.max(0d, seconds - lastSeconds)
		lastSeconds = seconds
		clearMargins()

		double lap = seconds / LAP_SECONDS
		double glintLap = seconds / GLINT_LAP_SECONDS
		double[] glints = new double[GLINTS]
		for (int i = 0; i < GLINTS; i++) {
			double g = glintLap + i / (double) GLINTS
			glints[i] = g - Math.floor(g)
		}
		double sigma = GLINT_SIGMA * scale
		double reach = 3d * sigma / perimeter
		int bgR = (background >> 16) & 255
		int bgG = (background >> 8) & 255
		int bgB = background & 255

		for (int k = 0; k < count; k++) {
			double a = arc[k]
			// the pattern moves towards larger arc, which runs counter-clockwise on screen
			double hue = CYCLES * (a - lap)
			hue -= Math.floor(hue)
			int rgb = rainbow[Math.min(rainbow.length - 1, (int) (hue * rainbow.length))]

			double glint = 0d
			for (int i = 0; i < GLINTS; i++) {
				double d = Math.abs(a - glints[i])
				if (d > 0.5d) d = 1d - d
				if (d < reach) {
					double u = d * perimeter / sigma
					glint += Math.exp(-u * u)
				}
			}
			double white = Math.min(1d, shine[k] + 0.9d * Math.min(1d, glint))
			double t = tone[k]
			double c = cover[k]
			int red   = mix((rgb >> 16) & 255, t, white, c, bgR)
			int green = mix((rgb >> 8) & 255, t, white, c, bgG)
			int blue  = mix(rgb & 255, t, white, c, bgB)
			pixels[index[k]] = (red << 16) | (green << 8) | blue
		}
		drawSparkles(seconds, dt)
	}

	private static int mix (int channel, double tone, double white, double cover, int bg) {
		double v = channel * tone
		v += (255d - v) * white
		v = bg + (v - bg) * cover
		return (int) Math.max(0d, Math.min(255d, v + 0.5d))
	}

	private void clearMargins () {
		int w = imageWidth
		int h = imageHeight
		int m = Math.min(imageMargin, Math.min(w, h))
		for (int y = 0; y < h; y++) {
			int row = y * w
			if (y < m || y >= h - m) Arrays.fill(pixels, row, row + w, background)
			else {
				Arrays.fill(pixels, row, row + m, background)
				Arrays.fill(pixels, row + w - m, row + w, background)
			}
		}
	}

	private void drawSparkles (double seconds, double dt) {
		double expected = SPARKLES_PER_S * dt
		while (count > 0 && random.nextDouble() < expected) {
			expected -= 1d
			for (int tries = 0; tries < 12; tries++) {
				int k = random.nextInt(count)
				if (cover[k] < 1d || tone[k] < 0.9d) continue   // somewhere near the tube's middle
				double x = index[k] % imageWidth + 0.5d
				double y = Math.floorDiv(index[k], imageWidth) + 0.5d
				sparkles.add([x, y, seconds, 0.7d + 0.6d * random.nextDouble()] as double[])
				break
			}
		}
		if (sparkles.isEmpty()) return
		Graphics2D g = image.createGraphics()
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
			Iterator<double[]> it = sparkles.iterator()
			while (it.hasNext()) {
				double[] s = it.next()
				double age = (seconds - s[2]) / SPARKLE_LIFE_S
				if (age >= 1d || age < 0d) { it.remove(); continue }
				double glow = Math.sin(Math.PI * age)
				double len = (2.5d + 5d * glow) * s[3] * scale
				double x = s[0]
				double y = s[1]
				g.setColor(new Color(255, 255, 255, (int) (240 * glow)))
				g.setStroke(new BasicStroke((float) (1.3d * scale)))
				g.draw(new Line2D.Double(x - len, y, x + len, y))
				g.draw(new Line2D.Double(x, y - len, x, y + len))
				double d = len * 0.4d
				g.setStroke(new BasicStroke((float) (0.8d * scale)))
				g.draw(new Line2D.Double(x - d, y - d, x + d, y + d))
				g.draw(new Line2D.Double(x - d, y + d, x + d, y - d))
				double dot = 1.6d * scale * (0.5d + glow)
				g.fill(new Ellipse2D.Double(x - dot, y - dot, 2d * dot, 2d * dot))
			}
		} finally {
			g.dispose()
		}
	}

	/** Copy the four margin strips onto the window; the inside of the image is never shown. */
	void paintOnto (Graphics g, int ox, int oy) {
		int w = width
		int h = height
		int m = margin
		int iw = imageWidth
		int ih = imageHeight
		int im = imageMargin
		g.drawImage(image, ox, oy, ox + w, oy + m, 0, 0, iw, im, null)
		g.drawImage(image, ox, oy + h - m, ox + w, oy + h, 0, ih - im, iw, ih, null)
		g.drawImage(image, ox, oy + m, ox + m, oy + h - m, 0, im, im, ih - im, null)
		g.drawImage(image, ox + w - m, oy + m, ox + w, oy + h - m, iw - im, im, iw, ih - im, null)
	}
}


// ------------------------------------------------------------------------------------------
// The dialog: an ordinary NonBlockingGenericDialog that paints the rim in its own margin
// ------------------------------------------------------------------------------------------

@CompileStatic
class PartyDialog extends NonBlockingGenericDialog {
	/** GenericDialog.getInsets() adds this much round the frame's own insets (ij 1.54p). */
	static final int GENERIC_DIALOG_MARGIN = 10
	/** A little more room, so the tube does not touch the fields. */
	static final int EXTRA = 3

	final RainbowRim rim = new RainbowRim()
	final List<Component> headings = new ArrayList<Component>()
	Component clockLabel
	String mode = PartyTheme.PARTY_NOW
	boolean party = false

	private Timer timer
	private final long started = System.nanoTime()
	private int ticks = 0

	PartyDialog (String title) {
		super(title)
	}

	@Override
	Insets getInsets () {
		Insets i = super.getInsets()
		return new Insets(i.top + EXTRA, i.left + EXTRA, i.bottom + EXTRA, i.right + EXTRA)
	}

	/** The window's client area, in window coordinates: the frame's insets, without GenericDialog's margin. */
	Rectangle clientArea () {
		Insets i = super.getInsets()
		int left = i.left - GENERIC_DIALOG_MARGIN
		int top = i.top - GENERIC_DIALOG_MARGIN
		int right = i.right - GENERIC_DIALOG_MARGIN
		int bottom = i.bottom - GENERIC_DIALOG_MARGIN
		return new Rectangle(left, top, getWidth() - left - right, getHeight() - top - bottom)
	}

	@Override
	void paint (Graphics g) {
		super.paint(g)
		if (!party) return
		Rectangle c = clientArea()
		int w = (int) c.getWidth()      // Groovy reads c.width through the double getter
		int h = (int) c.getHeight()
		if (w <= 0 || h <= 0) return
		double scale = 1d
		if (g instanceof Graphics2D) scale = Math.max(1d, ((Graphics2D) g).getTransform().getScaleX())
		rim.layout(w, h, GENERIC_DIALOG_MARGIN + EXTRA, scale, getBackground())
		rim.render((System.nanoTime() - started) / 1.0e9d)
		rim.paintOnto(g, (int) c.getX(), (int) c.getY())
	}

	/** A heavyweight window clears itself before paint(); the rim repaints 30 times a second, so it must not. */
	@Override
	void update (Graphics g) {
		if (party) paint(g)
		else super.update(g)
	}

	@Override
	void showDialog () {
		checkClock()
		timer = new Timer(33, new ActionListener() {
			@Override
			void actionPerformed (ActionEvent e) {
				tick()
			}
		})
		timer.start()
		super.showDialog()     // a NonBlockingGenericDialog waits here until OK or Cancel
	}

	@Override
	void dispose () {
		if (timer != null) timer.stop()
		super.dispose()
	}

	void tick () {
		if (ticks++ % 15 == 0) checkClock()
		if (party && isShowing()) {
			Rectangle c = clientArea()
			repaint((int) c.getX(), (int) c.getY(), (int) c.getWidth(), (int) c.getHeight())   // update() leaves the inside alone
		}
	}

	void setMode (String newMode) {
		mode = newMode
		checkClock()
	}

	void checkClock () {
		LocalTime now = LocalTime.now()
		if (clockLabel instanceof Label) ((Label) clockLabel).setText(PartyTheme.describe(mode, now))
		boolean wanted = PartyTheme.active(mode, now)
		if (wanted != party) applyTheme(wanted)
	}

	void applyTheme (boolean on) {
		party = on
		Color bg = on ? PartyTheme.PARTY_BACKGROUND : PartyTheme.OFFICE_BACKGROUND
		setBackground(bg)
		recolour(this, bg)
		for (Component heading : headings)
			heading.setForeground(on ? PartyTheme.PARTY_HEADING : PartyTheme.OFFICE_HEADING)
		rim.reset()
		repaint()
	}

	/** Labels, checkboxes and panels take the background; entry fields and buttons keep their own. */
	private static void recolour (Container parent, Color bg) {
		for (Component c : parent.getComponents()) {
			if (c instanceof TextComponent || c instanceof Choice || c instanceof Button ||
					c instanceof java.awt.List || c instanceof Scrollbar) continue
			c.setBackground(bg)
			c.repaint()
			if (c instanceof Container) recolour((Container) c, bg)
		}
	}
}


// ------------------------------------------------------------------------------------------
// A dialog shaped like Deskew Batch Processing, wired to nothing
// ------------------------------------------------------------------------------------------

/** Parameter.dialogFont(): the look and feel's label font, at ImageJ's GUI scale. */
Font dialogFont() {
	Font font = null
	try { font = UIManager.getFont("Label.font") } catch (Exception ignored) { }
	if (font == null) font = new Font("SansSerif", Font.PLAIN, 12)
	double scale = Prefs.getGuiScale()
	if (scale != 1.0d && scale > 0d) font = font.deriveFont((float) (font.getSize() * scale))
	return font
}

/** Parameter.addSection(): a bold heading, remembered so the theme can colour it. */
void section(PartyDialog gd, String text) {
	gd.addMessage(text)
	Component label = gd.getMessage()
	if (label != null) {
		Font base = dialogFont()
		label.setFont(base.deriveFont(base.getStyle() | Font.BOLD))
		gd.headings.add(label)
	}
}

PartyDialog gd = new PartyDialog("Deskew Batch Processing")
gd.setBackground(PartyTheme.OFFICE_BACKGROUND)
Font font = dialogFont()
gd.setFont(font)
if (gd.getFont() == null || gd.getFont().getSize() != font.getSize()) gd.setFont(font)

int fieldWidth = 45
int checkboxInset = 95
int sectionInset = 20

gd.setInsets(0, 15, 5)
section(gd, "Input setup:")
gd.addDirectoryField("input folder", "E:\\OPM\\3_timelapse_0", fieldWidth)
gd.setInsets(0, checkboxInset, 0)
gd.addCheckbox("recursively check sub-folders", false)

gd.setInsets(sectionInset, 15, 5)
section(gd, "Deskew parameters:")
gd.setInsets(0, checkboxInset, 0)
gd.addCheckbox("overwrite with manual input", true)
Checkbox manual = (Checkbox) gd.getCheckboxes().lastElement()
gd.addNumericField("XY pixel size", 116.0, 1, 5, "nm")
gd.addNumericField("Z step size", 132.5, 1, 5, "nm")
gd.addNumericField("OPM angle", 25.0, 1, 5, "degree")
List<TextField> geometry = new ArrayList<TextField>(gd.getNumericFields())
gd.addFileField("", "          load settings from file", fieldWidth)

gd.setInsets(sectionInset, 15, 5)
section(gd, "Channels:")
gd.addChoice("channel option", ["whole image", "fold by midline", "align with SIFT", "only left",
		"only right", "left & right separately"] as String[], "fold by midline")
gd.addChoice("interpolation", ["bilinear", "nearest neighbour"] as String[], "bilinear")
gd.addFileField("align matrix", "          load alignment matrix from file", fieldWidth)

gd.setInsets(sectionInset, 15, 5)
section(gd, "Projection:")
gd.setInsets(0, checkboxInset, 0)
gd.addCheckboxGroup(1, 3, ["along X", "along Y", "along Z"] as String[], [true, true, true] as boolean[])
gd.setInsets(0, checkboxInset, 0)
gd.addCheckboxGroup(1, 2, ["maximum", "mean"] as String[], [true, false] as boolean[])

gd.setInsets(sectionInset, 15, 5)
section(gd, "Output setup:")
gd.addDirectoryField("save to", "E:\\OPM\\3_timelapse_0\\result", fieldWidth)
gd.setInsets(0, checkboxInset, 0)
gd.addCheckbox("save result to the same (data) folder", false)
gd.addChoice("format", ["save as TIFF", "save as OME-Zarr", "save both"] as String[], "save as OME-Zarr")
gd.addChoice("if result exists", ["skip", "overwrite"] as String[], "skip")

gd.setInsets(sectionInset, 15, 5)
section(gd, "Lunch-time party (demo only):")
gd.addChoice("theme", PartyTheme.MODES, START_MODE)
Choice themeChoice = (Choice) gd.getChoices().lastElement()
gd.setInsets(0, checkboxInset, 0)
// long enough for the longest status line, which is written into it twice a second
gd.addMessage(PartyTheme.describe(PartyTheme.AUTO, LocalTime.of(13, 31)) + "          ")
gd.clockLabel = gd.getMessage()

// the same greying as the real dialog: typed geometry only counts when it overrides the file
Closure refreshManual = { ->
	for (TextField field : geometry) field.setEnabled(manual.getState())
}
manual.addItemListener({ e -> refreshManual() } as ItemListener)
refreshManual()

gd.mode = START_MODE
themeChoice.addItemListener({ e -> gd.setMode(themeChoice.getSelectedItem()) } as ItemListener)

gd.showDialog()

if (gd.wasCanceled()) {
	IJ.log("Lunch-time party demo: cancelled.")
	return
}
IJ.log("Lunch-time party demo - nothing is processed, this is what was entered:")
IJ.log("  input folder       " + gd.getNextString())
IJ.log("  sub-folders        " + gd.getNextBoolean())
IJ.log("  manual geometry    " + gd.getNextBoolean())
IJ.log("  XY pixel size      " + gd.getNextNumber() + " nm")
IJ.log("  Z step size        " + gd.getNextNumber() + " nm")
IJ.log("  OPM angle          " + gd.getNextNumber() + " degree")
IJ.log("  parameter file     " + gd.getNextString().trim())
IJ.log("  channel option     " + gd.getNextChoice())
IJ.log("  interpolation      " + gd.getNextChoice())
IJ.log("  align matrix       " + gd.getNextString().trim())
IJ.log("  projection X/Y/Z   " + gd.getNextBoolean() + " / " + gd.getNextBoolean() + " / " + gd.getNextBoolean())
IJ.log("  maximum / mean     " + gd.getNextBoolean() + " / " + gd.getNextBoolean())
IJ.log("  save to            " + gd.getNextString())
IJ.log("  same folder        " + gd.getNextBoolean())
IJ.log("  format             " + gd.getNextChoice())
IJ.log("  if result exists   " + gd.getNextChoice())
IJ.log("  theme              " + gd.getNextChoice())
