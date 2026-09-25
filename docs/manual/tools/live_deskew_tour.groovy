/*
 * Screenshot tour for the Live Deskew manual (docs/manual/live-deskew.html).
 *
 * Runs inside a separate Fiji, drives the real OPM Toolset windows and writes every
 * screenshot and data figure the manual uses. Start it with run_tour.ps1, which also backs
 * up and restores the Live Deskew preferences and compresses the images afterwards.
 *
 * Why windows are painted rather than grabbed from the screen: a component painted into an
 * image looks exactly as Fiji draws it, needs no free, unlocked or unobscured desktop, and
 * gives the same pixels on every run. The OS title bar is not part of that painting, so every
 * window gets the same neutral title strip instead (Shots.drawTitle).
 *
 * Highlights are placed from the live component geometry (Shots.rect), not from pixel
 * coordinates, so they follow the controls when a dialog's layout changes.
 *
 * Stages (OPM_TOUR_STAGES, comma separated, in this order):
 *   prepare  hard-link the example acquisitions into OPM_TOUR_ROOT and clear old results
 *   help     the Live Deskew help box with the manual link
 *   beads    Live Deskew of the bead acquisition, replayed over TCP/IP
 *   align    Channel Alignment on the beads, saving the alignment matrix
 *   sample   Live Deskew of the time lapse with that matrix, previews and viewer
 *
 * Nothing under the tutorial root is used by anything else; the raw TIFFs there are hard
 * links, so they cost no disk space and deleting them leaves the originals untouched.
 */

import de.embl.iclm.*
import ij.CompositeImage
import ij.IJ
import ij.ImagePlus
import ij.WindowManager
import ij.gui.GenericDialog
import ij.gui.HTMLDialog
import ij.gui.ImageCanvas
import ij.gui.ImageWindow
import ij.process.ImageProcessor
import ij.process.LUT

import java.awt.*
import java.awt.event.ActionEvent
import java.awt.image.BufferedImage
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.List               // java.awt.* would otherwise shadow it
import java.util.Map
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.swing.*

// ---- configuration ------------------------------------------------------------------------

class Cfg {
    static File repo, img, data, source, root, report
    static List<String> stages
    static int timepoints, port
    /** Link more time points without clearing what a previous run wrote, to exercise resuming. */
    static boolean keepResults

    static void init() {
        repo = new File(env('OPM_TOUR_REPO', 'D:/Git/OPM_toolset'))
        img = new File(repo, 'docs/manual/img/live-deskew')
        data = new File(repo, 'docs/manual/img/data')
        source = new File(env('OPM_TOUR_DATA', 'E:/OPM'))
        root = new File(env('OPM_TOUR_ROOT', 'E:/OPM/tutorial'))
        stages = env('OPM_TOUR_STAGES', 'prepare,help,beads,align,sample').split(',').collect { it.trim() }
        timepoints = env('OPM_TOUR_TIMEPOINTS', '6') as int
        port = env('OPM_TOUR_PORT', '5020') as int
        keepResults = env('OPM_TOUR_KEEP_RESULTS', '') as boolean
        report = new File(env('OPM_TOUR_REPORT',
                new File(System.getProperty('java.io.tmpdir'), 'opm-tour-report.txt').path))
        img.mkdirs()
        data.mkdirs()
        report.text = ''
    }

    static String env(String key, String fallback) {
        String value = System.getenv(key)
        return value ? value : fallback
    }

    static File beads() { new File(root, '4_beads_for_overlay_0') }
    static File sample() { new File(root, '3_timelapse_0') }
    static File beadMatrix() { new File(beads(), 'result/beads-alignment.csv') }
    static String helpClasses() { env('OPM_TOUR_HELP_CLASSES', '') }
}

class Say {
    static synchronized void say(String message) {
        String line = String.format('%tT  %s', new Date(), message)
        System.out.println(line)
        Cfg.report << line + '\n'
    }

    static void error(String what, Throwable failure) {
        StringWriter trace = new StringWriter()
        failure.printStackTrace(new PrintWriter(trace))
        say('ERROR ' + what + ': ' + trace)
    }
}

// ---- reflection: the tour drives private controls the way their own listeners do ---------

class R {
    static Field field(Class type, String name) {
        for (Class k = type; k != null; k = k.superclass) {
            try {
                Field f = k.getDeclaredField(name)
                f.accessible = true
                return f
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(type.name + '.' + name)
    }

    static Object get(Object target, String name) { field(target.getClass(), name).get(target) }
    static Object stat(Class type, String name) { field(type, name).get(null) }
    static void set(Object target, String name, Object value) { field(target.getClass(), name).set(target, value) }

    static Object call(Object target, String name, Object... args) {
        Class type = target instanceof Class ? (Class) target : target.getClass()
        Object self = target instanceof Class ? null : target
        for (Class k = type; k != null; k = k.superclass) {
            for (Method m : k.declaredMethods) {
                if (m.name != name || m.parameterCount != args.length || !fits(m.parameterTypes, args)) continue
                m.accessible = true
                try {
                    return m.invoke(self, args)
                } catch (InvocationTargetException failure) {
                    throw failure.cause
                }
            }
        }
        throw new NoSuchMethodException(type.name + '.' + name + '/' + args.length)
    }

    private static final Map<Class, Class> BOXES = [(boolean): Boolean, (int): Integer, (long): Long,
            (double): Double, (float): Float]

    private static boolean fits(Class[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            if (args[i] == null) { if (types[i].primitive) return false; continue }
            Class wanted = types[i].primitive ? BOXES[types[i]] : types[i]
            if (wanted == null || !wanted.isInstance(args[i])) return false
        }
        return true
    }
}

// ---- Swing/AWT helpers --------------------------------------------------------------------

class Ui {
    static Object edt(Closure task) {
        if (SwingUtilities.isEventDispatchThread()) return task.call()
        Object[] box = new Object[2]
        SwingUtilities.invokeAndWait({
            try { box[0] = task.call() } catch (Throwable failure) { box[1] = failure }
        } as Runnable)
        if (box[1] != null) throw (Throwable) box[1]
        return box[0]
    }

    static void later(Closure task) { SwingUtilities.invokeLater(task as Runnable) }

    static boolean waitFor(long millis, Closure condition) {
        long end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) {
            try { if (condition.call()) return true } catch (Throwable ignored) { }
            Thread.sleep(250)
        }
        return false
    }

    static void pause(long millis) { Thread.sleep(millis) }

    static String titleOf(Window w) {
        if (w instanceof Frame) return ((Frame) w).title
        if (w instanceof Dialog) return ((Dialog) w).title
        return ''
    }

    static Window window(String title, long millis) {
        Window found = null
        waitFor(millis) {
            found = Window.windows.find { it.displayable && it.visible && titleOf(it) == title }
            found != null
        }
        return found
    }

    static List<Component> all(Component root) {
        List<Component> out = []
        collect(root, out)
        return out
    }

    private static void collect(Component c, List<Component> out) {
        out << c
        if (c instanceof Container) for (Component child : ((Container) c).components) collect(child, out)
    }

    static Component text(Component root, String text) {
        return all(root).find {
            (it instanceof AbstractButton && ((AbstractButton) it).text?.trim() == text) ||
            (it instanceof JLabel && ((JLabel) it).text?.trim() == text)
        }
    }

    static void select(JComboBox box, Object value) {
        for (int i = 0; i < box.itemCount; i++)
            if (String.valueOf(box.getItemAt(i)) == String.valueOf(value)) { box.selectedIndex = i; return }
        throw new IllegalArgumentException('no item "' + value + '" in ' + box)
    }
}

// ---- screenshots ------------------------------------------------------------------------

class Cap {
    BufferedImage image
    Component root
    int dx, dy
    String title
}

class Shots {
    /* Rectangle.x and Point.x reach Groovy through getX(), which returns a double, so every
     * rectangle in this file is built through these two rather than from property arithmetic. */
    static Rectangle box(Number x, Number y, Number width, Number height) {
        new Rectangle((int) x, (int) y, (int) width, (int) height)
    }

    static Point point(Number x, Number y) { new Point((int) x, (int) y) }

    static BufferedImage sub(BufferedImage image, Rectangle r) {
        copy(image.getSubimage((int) r.x, (int) r.y, (int) r.width, (int) r.height))
    }

    static final Color RED = new Color(214, 40, 40)
    static final Color BORDER = new Color(150, 150, 150)
    static final int TITLE_H = 30
    static final int BADGE_R = 10
    private static Insets frameInsets, dialogInsets

    /** Native frame insets: the OS title bar and borders, which painting does not render well. */
    static Insets nativeInsets(Window w) {
        if (frameInsets == null) Ui.edt {
            Frame f = new Frame(); f.addNotify(); frameInsets = f.insets; f.dispose()
            Dialog d = new Dialog((Frame) null); d.addNotify(); dialogInsets = d.insets; d.dispose()
        }
        return w instanceof Dialog ? dialogInsets : frameInsets
    }

    static Cap capture(Window w) {
        return (Cap) Ui.edt {
            Cap cap = new Cap(title: Ui.titleOf(w))
            if (w instanceof RootPaneContainer) {
                Container pane = ((RootPaneContainer) w).contentPane
                pane.validate()
                cap.root = pane
                cap.image = paint(pane, pane.width, pane.height)
            } else {
                Insets n = nativeInsets(w)
                w.validate()
                BufferedImage whole = paint(w, w.width, w.height)
                cap.root = w
                cap.dx = -n.left
                cap.dy = -n.top
                cap.image = sub(whole, box(n.left, n.top,
                        w.width - n.left - n.right, w.height - n.top - n.bottom))
            }
            cap
        }
    }

    static BufferedImage paint(Component c, int width, int height) {
        BufferedImage image = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_RGB)
        Graphics2D g = image.createGraphics()
        g.color = c.background ?: Color.WHITE
        g.fillRect(0, 0, width, height)
        c.printAll(g)
        g.dispose()
        return image
    }

    static BufferedImage copy(BufferedImage source) {
        BufferedImage out = new BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        Graphics2D g = out.createGraphics()
        g.drawImage(source, 0, 0, null)
        g.dispose()
        return out
    }

    /** A component's rectangle in the capture's pixel coordinates. */
    static Rectangle rect(Cap cap, Component c) {
        return (Rectangle) Ui.edt {
            Point p = SwingUtilities.convertPoint(c, 0, 0, cap.root)
            box(p.x + cap.dx, p.y + cap.dy, c.width, c.height)
        }
    }

    static Rectangle union(Cap cap, Component... parts) {
        Rectangle out = null
        for (Component part : parts) {
            if (part == null) continue
            Rectangle r = rect(cap, part)
            out = out == null ? r : out.union(r)
        }
        return out
    }

    static Map mark(Rectangle r, String label, Map extra = [:]) { [rect: r, label: label] + extra }

    /**			Compose and write one screenshot
     *
     * @param opts	marks: [[rect:, label:, box: true, side: 'auto'|'right'|'left'|'topleft'|'gutter']]
     * 				crop: Rectangle in capture pixels; scale: double; title: boolean;
     * 				gutter: px of margin added on the left for 'gutter' badges
     */
    static File save(Cap cap, String name, Map opts = [:]) {
        BufferedImage source = cap.image
        List<Map> marks = ((List) (opts.marks ?: [])).findAll { it?.rect != null }.collect { new LinkedHashMap(it) }
        Rectangle crop = (Rectangle) opts.crop
        if (crop != null) {
            crop = crop.intersection(box(0, 0, source.width, source.height))
            source = sub(source, crop)
            marks.each { Rectangle r = (Rectangle) it.rect; it.rect = box(r.x - crop.x, r.y - crop.y, r.width, r.height) }
        }
        double scale = (opts.scale ?: 1.0d) as double
        if (Math.abs(scale - 1.0d) > 1e-6) {
            source = resize(source, scale)
            marks.each {
                Rectangle r = (Rectangle) it.rect
                it.rect = box(Math.round(r.x * scale), Math.round(r.y * scale),
                        Math.round(r.width * scale), Math.round(r.height * scale))
            }
        }
        boolean title = opts.containsKey('title') ? (boolean) opts.title : crop == null
        int top = title ? TITLE_H : 0
        /* Badges for rows that fill the window's width have nowhere to sit that does not cover
         * a label, so a margin can be added on either side and asked for with side: 'gutter'
         * (left) or 'gutter-right'. */
        int gutter = (opts.gutter ?: 0) as int
        int gutterRight = (opts.gutterRight ?: 0) as int
        BufferedImage out = new BufferedImage(source.width + gutter + gutterRight + 2,
                source.height + top + 2, BufferedImage.TYPE_INT_RGB)
        Graphics2D g = out.createGraphics()
        quality(g)
        g.color = BORDER
        g.fillRect(0, 0, out.width, out.height)
        g.color = Color.WHITE
        if (gutter > 0) g.fillRect(1, 1, gutter, out.height - 2)
        if (gutterRight > 0) g.fillRect(out.width - gutterRight - 1, 1, gutterRight, out.height - 2)
        if (title) drawTitle(g, 1, 1, source.width + gutter + gutterRight, (String) (opts.titleText ?: cap.title))
        g.drawImage(source, 1 + gutter, 1 + top, null)
        marks.each { ((Rectangle) it.rect).translate(1 + gutter, 1 + top) }
        drawMarks(g, marks, out.width, out.height, gutter, gutterRight)
        g.dispose()
        File file = new File((File) (opts.dir ?: Cfg.img), name + '.png')
        ImageIO.write(out, 'png', file)
        Say.say(String.format('shot  %-34s %4dx%-4d %4d KB', file.name, out.width, out.height, file.length() >> 10))
        return file
    }

    static void quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }

    static void drawTitle(Graphics2D g, int x, int y, int width, String text) {
        g.color = Color.WHITE
        g.fillRect(x, y, width, TITLE_H)
        g.color = new Color(222, 222, 222)
        g.drawLine(x, y + TITLE_H - 1, x + width, y + TITLE_H - 1)
        boolean buttons = width > 260
        g.font = new Font('Segoe UI', Font.PLAIN, 12)
        g.color = new Color(32, 32, 32)
        FontMetrics fm = g.fontMetrics
        String shown = text ?: ''
        int room = width - (buttons ? 3 * 46 : 0) - 24
        while (shown.length() > 3 && fm.stringWidth(shown + '\u2026') > room) shown = shown.substring(0, shown.length() - 1)
        if (shown != text) shown += '\u2026'
        g.drawString(shown, x + 12, y + (int) ((TITLE_H + fm.ascent - fm.descent) / 2))
        if (!buttons) return
        g.color = new Color(70, 70, 70)
        g.stroke = new BasicStroke(1f)
        int cy = y + (int) (TITLE_H / 2)
        int bx = x + width - 3 * 46
        g.drawLine(bx + 18, cy, bx + 28, cy)
        g.drawRect(bx + 46 + 18, cy - 5, 10, 10)
        int cx = bx + 92 + 23
        g.drawLine(cx - 5, cy - 5, cx + 5, cy + 5)
        g.drawLine(cx - 5, cy + 5, cx + 5, cy - 5)
    }

    static void drawMarks(Graphics2D g, List<Map> marks, int width, int height, int gutter, int gutterRight = 0) {
        marks.each { m ->
            if (m.box == false) return
            Rectangle r = new Rectangle((Rectangle) m.rect)
            r.grow(3, 3)
            g.stroke = new BasicStroke(2.5f)
            g.color = RED
            g.drawRoundRect((int) r.x, (int) r.y, (int) r.width, (int) r.height, 8, 8)
        }
        List<Point> taken = []
        marks.each { m ->
            if (!m.label) return
            Rectangle r = new Rectangle((Rectangle) m.rect)
            r.grow(3, 3)
            Point p
            int radius = BADGE_R
            if (m.side == 'gutter') {
                p = point(1 + (int) (gutter / 2), r.centerY)
                /* One badge per line of a text area, and the lines are about 13 px apart, so the
                 * full size circles would overlap into a chain. */
                radius = 6
            } else if (m.side == 'gutter-right') {
                p = point(width - 1 - (int) (gutterRight / 2), r.centerY)
            } else if (m.side == 'above') {
                p = point(r.centerX, r.y - BADGE_R - 1)
            } else {
                p = spot(r, (String) (m.side ?: 'auto'), taken, width, height)
            }
            taken << p
            badge(g, p, String.valueOf(m.label), radius)
        }
    }

    static Point spot(Rectangle r, String side, List<Point> taken, int width, int height) {
        int R = BADGE_R, gap = 5
        Map<String, Point> at = [
                right   : point(r.x + r.width + gap + R, r.centerY),
                left    : point(r.x - gap - R, r.centerY),
                topleft : point(r.x + 2, r.y + 2),
                topright: point(r.x + r.width - 2, r.y + 2),
                inside  : point(r.x + r.width - R - 4, r.centerY)]
        List<String> order = side == 'auto'
                ? ['right', 'left', 'topleft', 'topright', 'inside']
                : [side, 'right', 'left', 'topleft', 'topright', 'inside']
        for (String key : order) {
            Point p = at[key]
            if (p == null || p.x - R < 2 || p.y - R < 2 || p.x + R > width - 3 || p.y + R > height - 3) continue
            if (taken.any { it.distance(p) < 2 * R + 2 }) continue
            return p
        }
        Point p = at.topleft
        return point(Math.max(R + 2, Math.min(width - R - 3, (int) p.x)), Math.max(R + 2, Math.min(height - R - 3, (int) p.y)))
    }

    static void badge(Graphics2D g, Point p, String label, int radius = BADGE_R) {
        int R = radius, px = (int) p.x, py = (int) p.y
        g.color = Color.WHITE
        g.fillOval(px - R - 2, py - R - 2, 2 * R + 4, 2 * R + 4)
        g.color = RED
        g.fillOval(px - R, py - R, 2 * R, 2 * R)
        g.font = new Font('Segoe UI', Font.BOLD, R < 8 ? 10 : (label.length() > 1 ? 11 : 13))
        FontMetrics fm = g.fontMetrics
        g.color = Color.WHITE
        g.drawString(label, px - (int) (fm.stringWidth(label) / 2), py + (int) ((fm.ascent - fm.descent) / 2))
    }

    static BufferedImage resize(BufferedImage source, double scale) {
        int w = Math.max(1, (int) Math.round(source.width * scale))
        int h = Math.max(1, (int) Math.round(source.height * scale))
        BufferedImage current = source
        while (current.width / 2 >= w && current.height / 2 >= h)
            current = scaleTo(current, (int) (current.width / 2), (int) (current.height / 2))
        return scaleTo(current, w, h)
    }

    static BufferedImage scaleTo(BufferedImage source, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        Graphics2D g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(source, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    /** Line i of a text area, as wide as its text, in the capture's coordinates. */
    static Rectangle line(Cap cap, JTextArea area, int i) {
        return (Rectangle) Ui.edt {
            int start = area.getLineStartOffset(i), end = area.getLineEndOffset(i)
            String text = area.getText(start, end - start).replaceAll('\\s+$', '')
            Rectangle first = area.modelToView(start)
            int width = area.getFontMetrics(area.font).stringWidth(text)
            Rectangle base = rect(cap, area)
            box(base.x + first.x, base.y + first.y, Math.max(8, width), first.height)
        }
    }

    /** Press Cancel on a modal GenericDialog the tour opened for a screenshot. */
    static void cancel(Window w) {
        Ui.edt {
            GenericDialog gd = (GenericDialog) w
            gd.actionPerformed(new ActionEvent(gd.buttons[1], ActionEvent.ACTION_PERFORMED, 'Cancel'))
        }
    }
}

// ---- data figures -----------------------------------------------------------------------

class Figures {
    /* Display range for every figure and preview: the low end sits at the background level of a
     * mostly empty field, because a channel that carries no signal in this experiment would
     * otherwise have its noise stretched over the whole LUT and tint the picture. */
    static final double LOW = 0.45d
    static final double HIGH = 0.9995d

    /**
     * Give every channel its own display range, on the image itself.
     * <p>
     * Through the channel's own LUT, not {@code setDisplayRange}: that writes the range of
     * whichever channel is current and only the last one in a loop survives, which left the
     * channels that carry no signal here stretched over their noise and the whole composite
     * tinted. Measured with a two-channel composite in ImageJ 1.54p.
     */
    static void stretch(ImagePlus imp, int t, int z, double lowFraction, double highFraction) {
        Ui.edt {
            CompositeImage composite = imp instanceof CompositeImage ? (CompositeImage) imp : null
            for (int c = 1; c <= imp.NChannels; c++) {
                imp.setPosition(c, z, t)
                // getStack(), not the property: Groovy resolves imp.stack to ImagePlus.stack, a boolean
                ImageProcessor ip = imp.getStack().getProcessor(imp.getStackIndex(c, z, t))
                double[] range = displayRange(ip, highFraction)
                if (composite == null) {
                    imp.setDisplayRange(range[0], range[1])
                } else {
                    LUT lut = (LUT) composite.getChannelLut(c).clone()
                    lut.min = range[0]
                    lut.max = range[1]
                    composite.setChannelLut(lut, c)
                }
            }
            imp.setPosition(1, z, t)
            if (composite != null) composite.setMode(IJ.COMPOSITE)
            imp.updateAndDraw()
        }
    }

    /** Composite flattened at one position, after a percentile stretch of every channel. */
    static BufferedImage flatten(ImagePlus imp, int t, int z, double lowFraction, double highFraction) {
        stretch(imp, t, z, lowFraction, highFraction)
        return (BufferedImage) Ui.edt { imp.flatten().bufferedImage }
    }

    /**
     * Percentiles of the actual pixel values, from a sample of the plane.
     * <p>
     * Not from {@code getHistogram()}: a FloatProcessor - which is what a deskewed projection
     * is - answers that in 256 bins of its own range, so the numbers that came back were bin
     * indices and every stretch built from them clipped the image to white.
     */
    /**
     * A display range that puts the background at black: the noise floor, then a high percentile.
     * <p>
     * Percentiles alone do not work per channel here. A channel that carries no signal in this
     * experiment has a range of a few counts, so a low percentile stretches its <em>noise</em>
     * over the whole LUT and tints the whole composite - which is exactly what a purple
     * background is. The floor is therefore the median plus six median absolute deviations, and
     * empty pixels (the corners a deskewed volume leaves outside the shear) are left out of both
     * statistics, so a projection with big zero regions is measured on its data.
     */
    static double[] displayRange(ImageProcessor ip, double high) {
        float[] values = sample(ip, true)
        if (values.length < 16) values = sample(ip, false)
        if (values.length == 0) return [0d, 1d] as double[]
        Arrays.sort(values)
        double median = values[(int) (values.length / 2)]
        float[] deviations = new float[values.length]
        for (int i = 0; i < values.length; i++) deviations[i] = (float) Math.abs(values[i] - median)
        Arrays.sort(deviations)
        double mad = deviations[(int) (deviations.length / 2)]
        double lo = median + 6 * Math.max(mad, 0.5d)
        double hi = values[(int) Math.min(values.length - 1, Math.round(high * (values.length - 1)))]
        return [lo, Math.max(lo + 1e-3, hi)] as double[]
    }

    /** Every few pixels of a plane, optionally without the exact zeros. */
    static float[] sample(ImageProcessor ip, boolean skipZero) {
        int step = Math.max(1, (int) Math.sqrt(ip.width * (double) ip.height / 250000d))
        List<Float> kept = new ArrayList<Float>()
        for (int y = 0; y < ip.height; y += step) for (int x = 0; x < ip.width; x += step) {
            float v = ip.getf(x, y)
            if (!skipZero || v != 0f) kept.add(Float.valueOf(v))
        }
        float[] values = new float[kept.size()]
        for (int i = 0; i < values.length; i++) values[i] = kept.get(i).floatValue()
        return values
    }

    static double[] percentiles(ImageProcessor ip, double low, double high) {
        int step = Math.max(1, (int) Math.sqrt(ip.width * (double) ip.height / 250000d))
        List<Float> sample = new ArrayList<Float>()
        for (int y = 0; y < ip.height; y += step) for (int x = 0; x < ip.width; x += step)
            sample.add(Float.valueOf(ip.getf(x, y)))
        if (sample.isEmpty()) return [0d, 1d] as double[]
        float[] values = new float[sample.size()]
        for (int i = 0; i < values.length; i++) values[i] = sample.get(i).floatValue()
        Arrays.sort(values)
        double lo = values[(int) Math.min(values.length - 1, Math.max(0, Math.round(low * (values.length - 1))))]
        double hi = values[(int) Math.min(values.length - 1, Math.max(0, Math.round(high * (values.length - 1))))]
        return [lo, Math.max(lo + 1e-3, hi)] as double[]
    }

    static void scaleBar(BufferedImage image, double umPerPixel, int um) {
        Graphics2D g = image.createGraphics()
        Shots.quality(g)
        int length = (int) Math.round(um / umPerPixel)
        int thickness = Math.max(4, (int) (image.width / 160))
        int x = image.width - length - 14, y = image.height - 14 - thickness
        g.color = Color.WHITE
        g.fillRect(x, y, length, thickness)
        g.font = new Font('Segoe UI', Font.BOLD, 14)
        String label = um + ' \u00b5m'
        FontMetrics fm = g.fontMetrics
        g.drawString(label, x + (int) ((length - fm.stringWidth(label)) / 2), y - 5)
        g.dispose()
    }

    static void label(BufferedImage image, String text) {
        Graphics2D g = image.createGraphics()
        Shots.quality(g)
        g.font = new Font('Segoe UI', Font.BOLD, 15)
        g.color = Color.BLACK                       // readable over a bright field as well
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) g.drawString(text, 10 + dx, 22 + dy)
        g.color = Color.WHITE
        g.drawString(text, 10, 22)
        g.dispose()
    }

    static void jpeg(BufferedImage image, String name) {
        File file = new File(Cfg.data, name + '.jpg')
        file.delete()
        def writer = ImageIO.getImageWritersByFormatName('jpg').next()
        ImageWriteParam param = writer.defaultWriteParam
        param.compressionMode = ImageWriteParam.MODE_EXPLICIT
        param.compressionQuality = 0.88f
        def stream = ImageIO.createImageOutputStream(file)
        writer.output = stream
        writer.write(null, new IIOImage(image, null, null), param)
        stream.close()
        writer.dispose()
        Say.say(String.format('figure %-33s %4dx%-4d %4d KB', file.name, image.width, image.height, file.length() >> 10))
    }

    static BufferedImage crop(BufferedImage image, Rectangle r) {
        return Shots.sub(image, r.intersection(Shots.box(0, 0, image.width, image.height)))
    }

    /** Two greyscale planes as green and magenta, each stretched on its own. */
    static BufferedImage overlay(ImageProcessor green, ImageProcessor magenta) {
        double[] g = percentiles(green, 0.30, 0.9995), m = percentiles(magenta, 0.30, 0.9995)
        BufferedImage out = new BufferedImage(green.width, green.height, BufferedImage.TYPE_INT_RGB)
        for (int y = 0; y < green.height; y++) for (int x = 0; x < green.width; x++) {
            int gv = clamp((green.getf(x, y) - g[0]) / (g[1] - g[0]))
            int mv = clamp((magenta.getf(x, y) - m[0]) / (m[1] - m[0]))
            out.setRGB(x, y, (mv << 16) | (gv << 8) | mv)
        }
        return out
    }

    static int clamp(double v) { (int) Math.round(255 * Math.max(0d, Math.min(1d, v))) }
}

// ---- stage: prepare -----------------------------------------------------------------------

class Prepare {
    static void run() {
        String rootPath = Cfg.root.canonicalPath.toLowerCase()
        if (!rootPath.contains('tutorial')) throw new IllegalStateException('refusing to prepare ' + rootPath)
        folder(new File(Cfg.source, '4_beads_for_overlay_0'), Cfg.beads(), Integer.MAX_VALUE)
        folder(new File(Cfg.source, '3_timelapse_0'), Cfg.sample(), Cfg.timepoints)
    }

    static void folder(File source, File target, int timepoints) {
        target.mkdirs()
        if (!Cfg.keepResults) {
            deleteTree(new File(target, 'result'))
            target.listFiles().each { if (it.isFile() && !(it.name ==~ /(?i).*\.tiff?$/)) it.delete() }
        }
        Files.copy(new File(source, 'ExperimentalParameters.txt').toPath(),
                new File(target, 'ExperimentalParameters.txt').toPath(), StandardCopyOption.REPLACE_EXISTING)
        int linked = 0
        source.listFiles().findAll { it.isFile() && it.name ==~ /(?i).*_Time\d+_Channel\d+.*\.tiff?$/ }.each { File f ->
            int t = ((f.name =~ /_Time(\d+)_/)[0][1]) as int
            File link = new File(target, f.name)
            if (t <= timepoints) {
                if (!link.exists()) Files.createLink(link.toPath(), f.toPath())
                linked++
            } else if (link.exists()) link.delete()
        }
        Say.say("prepared ${target} with ${linked} linked TIFF(s)")
    }

    static void deleteTree(File tree) {
        if (!tree.exists()) return
        if (!tree.canonicalPath.toLowerCase().contains('tutorial')) throw new IllegalStateException('refusing to delete ' + tree)
        Files.walk(tree.toPath()).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
    }
}

// ---- Live Deskew --------------------------------------------------------------------------

class LiveTour {
    static Live2 live

    static void open() {
        if (live != null && live.displayable) return
        Ui.edt {
            live = new Live2()
            live.setLocation(30, 30)
        }
        Ui.pause(800)
    }

    static Parameter parameter() { (Parameter) R.get(live, 'parameter') }
    static Object channels() { R.get(live, 'channels') }
    static Object c(Object owner, String name) { R.get(owner, name) }
    static JTextArea status() { (JTextArea) R.get(live, 'status') }

    /** The setup dialog exactly as the setup button builds it, minus the modal wait. */
    static JDialog setupDialog() {
        return (JDialog) Ui.edt {
            Constructor ctor = LiveSetupDialog.getDeclaredConstructor(Frame, Parameter, ChannelOperationSettings)
            ctor.accessible = true
            ctor.newInstance(live, parameter(), channels())
        }
    }

    /** One example selection; everything not named is the same for beads and sample. */
    static void configure(JDialog d, Map s) {
        Ui.edt {
            R.set(d, 'advanced', true)
            c(d, 'chkListenTcp').selected = true
            c(d, 'portField').text = String.valueOf(Cfg.port)
            c(d, 'chkWatchAnnounced').selected = true
            c(d, 'chkWatchExplicit').selected = false
            c(d, 'watchDirField').text = ''
            c(d, 'chkRecursive').selected = false
            c(d, 'includeField').text = ''
            c(d, 'excludeField').text = ''
            c(d, 'chkManual').selected = false
            c(d, 'xyField').text = '116.0'
            c(d, 'zStepField').text = '265.0'
            c(d, 'angleField').text = '33.5'
            Ui.select((JComboBox) c(d, 'channelChoice'), s.channelOption)
            Ui.select((JComboBox) c(d, 'interpolationChoice'), R.stat(Parameter, 'INTERPOLATION_BILINEAR'))
            c(d, 'alignField').text = s.align ?: ''
            /* The tour photographs the manual channel setup, so the automatic one is off:
             * with it on, the combine tick and the slots below it are the listener's to fill
             * from the file names and are greyed while it reads them. */
            c(d, 'chkAutoCombine').selected = false
            c(d, 'chkAutoAssign').selected = false
            c(d, 'chkCombine').selected = true
            Ui.select((JComboBox) c(d, 'flipChoice'), 'flip right half onto left')
            List<JComboBox> slots = (List<JComboBox>) c(d, 'slotChoices')
            List<String> order = (List<String>) s.slots
            String skip = (String) R.stat(BatchChannelOperation, 'SKIP_CHANNEL')
            for (int i = 0; i < slots.size(); i++) Ui.select(slots[i], i < order.size() ? order[i] : skip)
            R.set(d, 'visibleSlots', Math.max(2, order.size()))
            c(d, 'chkProjX').selected = true
            c(d, 'chkProjY').selected = true
            c(d, 'chkProjZ').selected = true
            c(d, 'chkMax').selected = true
            c(d, 'chkAvg').selected = false
            c(d, 'chkPreviewProj').selected = true
            c(d, 'chkPreviewVolume').selected = (boolean) s.volumePreview
            c(d, 'saveDirField').text = ''
            c(d, 'chkSaveToSame').selected = true
            c(d, 'chkReproduceTree').selected = false
            Ui.select((JComboBox) c(d, 'formatChoice'), Parameter.FORMAT_ZARR)
            c(d, 'chkSaveVolume').selected = true
            c(d, 'chkSaveProjections').selected = true
            c(d, 'chkSeparate').selected = true
            Ui.select((JComboBox) c(d, 'existChoice'), 'skip')
            R.call(d, 'updateEnabledState')
            R.call(d, 'applyMode')
            d.pack()
        }
    }

    static void mode(JDialog d, boolean advanced) {
        Ui.edt {
            R.set(d, 'advanced', advanced)
            R.call(d, 'applyMode')
            d.pack()
        }
    }

    /** What OK does: store into the settings, then what Live2.setup does with them. */
    static void apply(JDialog d) {
        Ui.edt {
            if (!(boolean) R.call(d, 'store')) throw new IllegalStateException('the setup refused the example settings')
            R.call(live, 'applySettings')
            R.call(parameter(), 'storeParam')
            R.call(channels(), 'store')
            R.call(live, 'updateStatus')
            d.dispose()
        }
    }

    static void start() { Ui.edt { R.call(live, 'startLive') } }
    static void stop() { R.call(live, 'stopLive'); Ui.pause(500) }

    static Component row(JDialog d, String caption, Component field) {
        return field
    }

    static Rectangle captionRow(Cap cap, JDialog d, String caption, Component field) {
        Component label = Ui.text((Component) c(d, 'form'), caption)
        return Shots.union(cap, label, field)
    }

    /** A section of the form, from its heading to the next heading. */
    static Rectangle section(Cap cap, JDialog d, String heading, String next) {
        Container form = (Container) c(d, 'form')
        Rectangle h = Shots.rect(cap, Ui.text(form, heading + ':'))
        int bottom
        if (next != null) bottom = Shots.rect(cap, Ui.text(form, next + ':')).y - 2
        else { Rectangle f = Shots.rect(cap, form); bottom = f.y + f.height - 4 }
        return Shots.box(0, h.y - 6, cap.image.width, bottom - h.y + 6)
    }

    static List<Map> exampleMarks(Cap cap, JDialog d, boolean sample) {
        def m = { String name -> (Component) c(d, name) }
        List<JComboBox> slots = (List<JComboBox>) c(d, 'slotChoices')
        int shown = (int) R.get(d, 'visibleSlots')
        Rectangle slotBox = Shots.union(cap, *(slots.subList(0, shown).collect { it.parent == null ? it : it }))
        Component firstSlotLabel = Ui.text((Component) c(d, 'form'), ChannelOperationSettings.slotLabel(1))
        slotBox = slotBox.union(Shots.rect(cap, firstSlotLabel))
        List<Map> marks = []
        if (!sample) {
            marks << Shots.mark(Shots.union(cap, m('chkListenTcp'), m('portField')), '1')
            marks << Shots.mark(Shots.rect(cap, m('chkWatchAnnounced')), '2')
            marks << Shots.mark(Shots.union(cap, m('chkCombine')), '3')
            marks << Shots.mark(Shots.rect(cap, m('flipChoice')), '4')
            marks << Shots.mark(slotBox, '5', [pad: 5])
            marks << Shots.mark(Shots.union(cap, m('chkProjX'), m('chkProjZ'), m('chkMax'), m('chkAvg')), '6')
            marks << Shots.mark(Shots.union(cap, m('chkPreviewProj'), m('chkPreviewVolume')), '7')
            marks << Shots.mark(Shots.rect(cap, m('chkSaveToSame')), '8')
            marks << Shots.mark(Shots.rect(cap, m('formatChoice')), '9')
        } else {
            marks << Shots.mark(Shots.rect(cap, m('channelChoice')), '1')
            marks << Shots.mark(Shots.union(cap, m('alignField'), m('alignBrowse')), '2')
            marks << Shots.mark(slotBox, '3', [pad: 5])
            marks << Shots.mark(Shots.rect(cap, m('chkPreviewVolume')), '4')
        }
        return marks
    }

    static void setupShots(JDialog d) {
        def m = { String name -> (Component) c(d, name) }
        Cap cap = Shots.capture(d)
        Container form = (Container) c(d, 'form')

        Shots.save(cap, 'setup-input', [crop: section(cap, d, 'Input setup', 'Deskew parameters'),
                gutterRight: 30, marks: [
                Shots.mark(Shots.union(cap, m('chkListenTcp'), m('portField')), '1'),
                Shots.mark(Shots.rect(cap, m('chkWatchAnnounced')), '2'),
                Shots.mark(Shots.union(cap, m('chkWatchExplicit'), m('watchDirBrowse')), '3', [side: 'gutter-right']),
                Shots.mark(Shots.rect(cap, m('chkRecursive')), '4'),
                Shots.mark(Shots.union(cap, Ui.text(form, 'file name include (separate multiple by comma ",")'), m('includeField')), '5'),
                Shots.mark(Shots.union(cap, Ui.text(form, 'file name exclude (separate multiple by comma ",")'), m('excludeField')), '6')]])

        Shots.save(cap, 'setup-deskew', [crop: section(cap, d, 'Deskew parameters', 'Channels'),
                gutterRight: 30, marks: [
                Shots.mark(Shots.rect(cap, m('chkManual')), '1'),
                Shots.mark(captionRow(cap, d, 'XY pixel size', m('xyField').parent), '2'),
                Shots.mark(captionRow(cap, d, 'Z step size', m('zStepField').parent), '3'),
                Shots.mark(captionRow(cap, d, 'OPM angle', m('angleField').parent), '4')]])

        List<JComboBox> slots = (List<JComboBox>) c(d, 'slotChoices')
        int shown = (int) R.get(d, 'visibleSlots')
        Rectangle slotBox = Shots.union(cap, *slots.subList(0, shown))
        for (int i = 1; i <= shown; i++) slotBox = slotBox.union(Shots.rect(cap, Ui.text(form, ChannelOperationSettings.slotLabel(i))))
        Shots.save(cap, 'setup-channels', [crop: section(cap, d, 'Channels', 'Projection'),
                gutterRight: 30, marks: [
                Shots.mark(captionRow(cap, d, 'channel option', m('channelChoice')), '1', [side: 'gutter-right']),
                Shots.mark(Shots.union(cap, Ui.text(form, 'interpolation'), m('interpolationChoice')), '2'),
                Shots.mark(captionRow(cap, d, 'align matrix', m('alignBrowse')).union(Shots.rect(cap, m('alignField'))), '3', [side: 'right']),
                Shots.mark(Shots.rect(cap, m('chkCombine')), '4'),
                Shots.mark(captionRow(cap, d, 'flip', m('flipChoice')), '5'),
                Shots.mark(slotBox, '6'),
                Shots.mark(Shots.union(cap, m('slotFewer'), m('slotMore')), '7')]])

        Shots.save(cap, 'setup-output', [crop: section(cap, d, 'Projection', null),
                gutterRight: 30, marks: [
                Shots.mark(Shots.union(cap, m('chkProjX'), m('chkProjZ')), '1'),
                Shots.mark(Shots.union(cap, m('chkMax'), m('chkAvg')), '2'),
                Shots.mark(Shots.rect(cap, m('chkPreviewProj')), '3'),
                Shots.mark(Shots.rect(cap, m('chkPreviewVolume')), '4'),
                Shots.mark(captionRow(cap, d, 'save to', m('saveDirBrowse')).union(Shots.rect(cap, m('saveDirField'))), '5', [side: 'gutter-right']),
                Shots.mark(Shots.rect(cap, m('chkSaveToSame')), '6'),
                Shots.mark(Shots.rect(cap, m('chkReproduceTree')), '7'),
                Shots.mark(captionRow(cap, d, 'format', m('formatChoice')), '8'),
                Shots.mark(Shots.union(cap, m('chkSaveVolume'), m('chkSeparate')), '9'),
                Shots.mark(captionRow(cap, d, 'if result exists', m('existChoice')), '10')]])

        // the badges go above this row, so the crop keeps room for them
        Component buttons = m('okButton').parent
        Rectangle bar = Shots.rect(cap, buttons)
        Shots.save(cap, 'setup-buttons', [crop: Shots.box(bar.x, bar.y - 26, bar.width, bar.height + 28), marks: [
                Shots.mark(Shots.rect(cap, m('modeButton')), '1', [side: 'above']),
                Shots.mark(Shots.rect(cap, m('okButton')), '2', [side: 'above']),
                Shots.mark(Shots.rect(cap, m('cancelButton')), '3', [side: 'above']),
                Shots.mark(Shots.rect(cap, m('helpButton')), '4', [side: 'above'])]])
    }

    static Cap windowCap() { Shots.capture(live) }

    static void shotButtons(String name) {
        Cap cap = windowCap()
        Shots.save(cap, name, [marks: [
                Shots.mark(Shots.rect(cap, (Component) c(live, 'btnSetup')), '1', [side: 'topleft']),
                Shots.mark(Shots.rect(cap, (Component) c(live, 'btnStart')), '2', [side: 'topleft']),
                Shots.mark(Shots.rect(cap, (Component) c(live, 'btnStop')), '3', [side: 'topleft']),
                Shots.mark(Shots.rect(cap, (Component) c(live, 'btnExit')), '4', [side: 'topleft'])]])
    }

    static void shotStatus(String name, List<Integer> lines) {
        Cap cap = windowCap()
        JTextArea area = status()
        List<Map> marks = lines.collect { int i -> Shots.mark(Shots.line(cap, area, i), String.valueOf(i + 1), [side: 'gutter']) }
        Shots.save(cap, name, [marks: marks, gutter: lines ? 28 : 0])
        Say.say('status ' + name + ':\n' + Ui.edt { area.text })
    }

    static Map<String, Object> sessions() { (Map<String, Object>) R.get(live, 'zarrSessions') }

    /**
     * Time points committed for one acquisition, from the run or from the store.
     * <p>
     * The run's own session only exists once it has processed something, and a resumed run whose
     * results are all present processes nothing at all - so the store on disk is asked as well,
     * which is what makes a figures-only re-run possible.
     */
    static int committed(File acquisition) {
        int fromRun = (int) Ui.edt {
            int n = 0
            sessions().values().each { n += (int) R.call(it, 'getCommittedTimepoints') }
            n
        }
        return Math.max(fromRun, committedInStore(acquisition))
    }

    /** Distinct leading (time) indices of the chunk keys in the store's s0 array. */
    static int committedInStore(File acquisition) {
        File s0 = new File(acquisition, 'result/' + acquisition.name + '.ome.zarr/s0')
        String[] names = s0.list()
        if (names == null) return 0
        Set<String> times = new HashSet<String>()
        for (String name : names) if (name ==~ /\d+([.]\d+)+/) times.add(name.substring(0, name.indexOf('.')))
        return times.size()
    }

    static boolean idle() {
        return (boolean) Ui.edt {
            ((Collection) R.get(live, 'fileQueue')).isEmpty() && ((Collection) R.get(live, 'heldForMetadata')).isEmpty() &&
                    !R.get(live, 'currentFile')
        }
    }

    /** A volume is in flight right now. */
    static boolean processing() { (boolean) Ui.edt { !!R.get(live, 'currentFile') } }

    /** Volumes this run has finished; what the panel's "done" counts. */
    static int processedCount() { (int) Ui.edt { R.get(live, 'processedCount') } }
}

// ---- the replay script, the stand-in for the microscope --------------------------------------

class Replay {
    static Object ui

    static void send(File folder, double delaySeconds) {
        GroovyShell shell = new GroovyShell(Replay.classLoader)
        shell.parse(new File(Cfg.repo, 'scripts/tcpip_acquisition_replay.groovy'))
        Class type = shell.classLoader.loadClass('TcpPathSenderUi')
        Ui.edt {
            ui = type.getDeclaredConstructor().newInstance()
            ((JDialog) R.get(ui, 'dialog')).setModal(false)
            R.get(ui, 'folderField').text = folder.path.replace('\\', '/')
            R.call(ui, 'refreshFileList', false, true)
            ((JComboBox) R.get(ui, 'transferMode')).selectedIndex = 0
            R.get(ui, 'hostField').text = '127.0.0.1'
            R.get(ui, 'portField').text = String.valueOf(Cfg.port)
            R.get(ui, 'delayField').text = String.valueOf(delaySeconds)
            R.get(ui, 'watchFolderBox').selected = false
            R.call(ui, 'startSending')
        }
        Say.say('replay started for ' + folder)
    }

    static boolean waitDone(long millis) { Ui.waitFor(millis) { Ui.edt { R.get(ui, 'worker') == null } } }

    static void shot(String name) {
        JDialog d = (JDialog) R.get(ui, 'dialog')
        Cap cap = Shots.capture(d)
        def f = { String field -> (Component) R.get(ui, field) }
        Shots.save(cap, name, [marks: [
                Shots.mark(Shots.union(cap, f('folderField'), f('browseButton')), '1', [side: 'topleft']),
                Shots.mark(Shots.rect(cap, f('transferMode')), '2', [side: 'topleft']),
                Shots.mark(Shots.rect(cap, f('hostField').parent), '3', [side: 'topleft']),
                Shots.mark(Shots.rect(cap, f('fileList').parent.parent.parent), '4', [side: 'topleft']),
                Shots.mark(Shots.rect(cap, f('startButton')), '5', [side: 'topleft']),
                Shots.mark(Shots.rect(cap, f('logArea').parent.parent.parent), '6', [side: 'topleft'])]])
        Ui.edt { d.dispose() }
    }
}

// ---- images the viewer opened ----------------------------------------------------------------

class Views {
    static List<ImagePlus> images() {
        return (List<ImagePlus>) Ui.edt {
            int[] ids = WindowManager.IDList ?: new int[0]
            ids.collect { WindowManager.getImage(it) }.findAll { it != null }
        }
    }

    static ImagePlus find(String... parts) {
        return images().find { ImagePlus imp -> parts.every { imp.title.toLowerCase().contains(it.toLowerCase()) } }
    }

    static ImagePlus await(long millis, String... parts) {
        ImagePlus found = null
        Ui.waitFor(millis) { found = find(parts); found != null && found.window != null }
        if (found == null) Say.say('no view with ' + parts.toList() + '; open: ' + images()*.title)
        return found
    }

    static void closeMatching(String part) {
        images().findAll { it.title.contains(part) }.each { ImagePlus imp -> Ui.edt { imp.changes = false; imp.close() } }
    }

    static void zoom(ImagePlus imp, double magnification) {
        Ui.edt {
            ImageCanvas canvas = imp.canvas
            if (canvas == null) return
            int guard = 0
            while (canvas.magnification > magnification + 1e-3 && guard++ < 30)
                canvas.zoomOut((int) (canvas.width / 2), (int) (canvas.height / 2))
            imp.window.pack()
        }
        Ui.pause(400)
    }

    static OpmDataViewer viewer() { (OpmDataViewer) R.stat(OpmDataViewer, 'instance') }

    /**
     * @param contrast	stretch every channel over its own percentiles first, as a user would
     * 					with Image > Adjust > Brightness/Contrast. A projection of a bead field
     * 					is almost black at the display range a mid-stack plane suggests.
     */
    static void shotWindow(ImagePlus imp, String name, double magnification, int t, int z,
            boolean contrast = false) {
        if (imp == null) return
        Ui.edt { imp.setPosition(1, z > 0 ? z : imp.z, t > 0 ? t : imp.t) }
        if (contrast) Figures.stretch(imp, t > 0 ? t : imp.t, z > 0 ? z : imp.z, Figures.LOW, Figures.HIGH)
        zoom(imp, magnification)
        ImageWindow window = imp.window
        Cap cap = Shots.capture(window)
        List<Map> marks = []
        ['cSelector': 'C', 'zSelector': 'Z', 'tSelector': 'T'].each { String field, String label ->
            try {
                Component selector = (Component) R.get(window, field)
                if (selector != null && selector.visible) marks << Shots.mark(Shots.rect(cap, selector), label, [side: 'inside'])
            } catch (NoSuchFieldException ignored) { }
        }
        Shots.save(cap, name, [marks: marks])
    }

    static void shotViewer(String name) {
        OpmDataViewer v = viewer()
        if (v == null) { Say.say('no viewer to capture'); return }
        Ui.edt { v.toFront() }
        Cap cap = Shots.capture(v)
        def f = { String field -> (Component) R.get(v, field) }
        def b = { String label -> Ui.text(v, label) }
        Shots.save(cap, name, [gutterRight: 30, marks: [
                Shots.mark(Shots.union(cap, f('path'), b('Scan')), '1', [side: 'gutter-right']),
                Shots.mark(Shots.rect(cap, f('datasets')), '2', [side: 'gutter-right']),
                Shots.mark(Shots.union(cap, f('openMode'), f('virtual')), '3', [side: 'gutter-right']),
                Shots.mark(Shots.rect(cap, f('projections')), '4', [side: 'gutter-right']),
                Shots.mark(Shots.union(cap, f('selections'), (Component) R.get(v, 'channelSetupButton')), '5', [side: 'gutter-right']),
                Shots.mark(Shots.rect(cap, f('operations')), '6', [side: 'gutter-right']),
                Shots.mark(Shots.rect(cap, f('timeSlider').parent), '7', [side: 'gutter-right']),
                Shots.mark(Shots.rect(cap, f('regionLabel').parent), '8', [side: 'gutter-right']),
                Shots.mark(Shots.union(cap, b('Open'), b('Show info.')), '9', [side: 'topleft']),
                Shots.mark(Shots.union(cap, f('live'), f('pollSeconds')), '10', [side: 'topright']),
                Shots.mark(Shots.rect(cap, f('details').parent.parent), '11', [side: 'gutter-right'])]])
    }

    /** Open one of the viewer's modal dialogs, capture it and cancel it. */
    static void shotViewerDialog(String method, String title, String name) {
        OpmDataViewer v = viewer()
        if (v == null) return
        Ui.later { try { R.call(v, method) } catch (Throwable failure) { Say.error(method, failure) } }
        Window w = Ui.window(title, 20000)
        if (w == null) { Say.say('no dialog ' + title); return }
        Ui.pause(800)
        Shots.save(Shots.capture(w), name)
        Shots.cancel(w)
        Ui.pause(500)
    }
}

// ---- stages -----------------------------------------------------------------------------

class HelpStage {
    static void run() {
        String html = helpText()
        HTMLDialog dialog = (HTMLDialog) Ui.edt { new HTMLDialog('OPM Live Deskew', html, false) }
        Ui.pause(1200)
        Cap cap = Shots.capture(dialog)
        JEditorPane pane = (JEditorPane) R.get(dialog, 'editorPane')
        Rectangle link = (Rectangle) Ui.edt {
            String text = pane.document.getText(0, pane.document.length)
            int at = text.indexOf('https://')
            int end = text.indexOf('live-deskew.html', at) + 'live-deskew.html'.length()
            Rectangle a = pane.modelToView(at), b = pane.modelToView(end)
            Rectangle base = Shots.rect(cap, pane)
            Shots.box(base.x + a.x, base.y + a.y, b.x - a.x, a.height)
        }
        int bottom = Math.min((int) cap.image.height, (int) (link.y + link.height + 60))
        Shots.save(cap, 'help-link', [crop: Shots.box(0, 0, cap.image.width, bottom), title: true,
                titleText: 'OPM Live Deskew', marks: [Shots.mark(link, '1', [side: 'right'])]])
        Ui.edt { dialog.dispose() }
    }

    /** The help text of the source tree when a freshly compiled Help class is supplied. */
    static String helpText() {
        String classes = Cfg.helpClasses()
        if (classes) {
            URLClassLoader loader = new URLClassLoader([new File(classes).toURI().toURL()] as URL[], (ClassLoader) null)
            return (String) R.stat(loader.loadClass('de.embl.iclm.Help'), 'live')
        }
        return (String) R.stat(Class.forName('de.embl.iclm.Help'), 'live')
    }
}

class BeadsStage {
    static void run() {
        LiveTour.open()
        JDialog d = LiveTour.setupDialog()
        LiveTour.configure(d, [channelOption: 'fold by midline', align: '', volumePreview: false,
                slots: ['_Channel0001-left', '_Channel0002-right']])
        Cap cap = Shots.capture(d)
        Shots.save(cap, 'setup-beads', [marks: LiveTour.exampleMarks(cap, d, false)])
        LiveTour.apply(d)
        Ui.pause(600)
        LiveTour.shotButtons('live-window')
        LiveTour.start()
        Ui.pause(2000)
        LiveTour.shotStatus('status-waiting', [0, 1, 2, 3, 4, 5, 6, 7])

        Replay.send(Cfg.beads(), 2.0d)
        if (!Ui.waitFor(15 * 60 * 1000L) { LiveTour.committed(Cfg.beads()) >= 1 && LiveTour.idle() })
            Say.say('beads: timed out waiting for the time point')
        Replay.waitDone(120000)
        Replay.shot('replay-tool')
        LiveTour.shotStatus('status-beads-done', [])

        ImagePlus maxZ = Views.await(120000, '4_beads', 'maxZ')
        Ui.pause(1500)
        Views.shotWindow(maxZ, 'preview-beads-maxZ', 0.25d, 1, 1, true)
        LiveTour.stop()
        Say.say('open images: ' + Views.images()*.title)
    }
}

class AlignStage {
    static final String REFERENCE = '_Channel0001-left'
    static final String MOVING = '_Channel0002-right'

    static void run() {
        Class type = Class.forName('de.embl.iclm.ChannelAlignment$Dialog')
        JDialog d = (JDialog) Ui.edt {
            Constructor ctor = type.getDeclaredConstructor()
            ctor.accessible = true
            ctor.newInstance()
        }
        Ui.edt {
            R.call(d, 'loadPath', Cfg.beads())
            R.set(d, 'visibleChannels', 3)
            R.call(d, 'rebuildChannelRows')
            List<JComboBox> boxes = (List<JComboBox>) R.get(d, 'channelChoices')
            ['Channel0001_Frames_1_451.tiff-left', 'Channel0001_Frames_1_451.tiff-right',
             'Channel0002_Frames_1_451.tiff-right'].eachWithIndex { String wanted, int i ->
                JComboBox box = boxes[i]
                for (int j = 0; j < box.itemCount; j++) if (String.valueOf(box.getItemAt(j)).endsWith(wanted)) box.selectedIndex = j
            }
            R.get(d, 'deskew').selected = true
            R.get(d, 'preview').selected = true
            d.pack()
            d.setLocation(60, 60)
            d.visible = true
        }
        Ui.pause(1000)
        Cap cap = Shots.capture(d)
        def f = { String field -> (Component) R.get(d, field) }
        Shots.save(cap, 'channel-alignment', [gutterRight: 30, marks: [
                Shots.mark(Shots.rect(cap, f('path').parent), '1', [side: 'gutter-right']),
                Shots.mark(Shots.rect(cap, f('table').parent.parent), '2', [side: 'gutter-right']),
                Shots.mark(Shots.rect(cap, f('channelRows')), '3', [side: 'gutter-right']),
                Shots.mark(Shots.rect(cap, f('fewer').parent), '4', [side: 'gutter-right']),
                Shots.mark(Shots.rect(cap, f('deskew')), '5', [side: 'right']),
                Shots.mark(Shots.rect(cap, f('showDetections')), '6', [side: 'right']),
                Shots.mark(Shots.union(cap, f('manual'), f('rotation')), '7', [side: 'gutter-right']),
                Shots.mark(Shots.rect(cap, f('save')), '8', [side: 'right']),
                Shots.mark(Shots.rect(cap, f('preview')), '9', [side: 'right']),
                Shots.mark(Shots.union(cap, f('ok'), f('help')), '10', [side: 'left'])]])

        long started = System.currentTimeMillis()
        List sources = (List) Ui.edt { R.call(d, 'selectedSources') }
        File metadata = (File) Ui.edt { R.call(d, 'selectedMetadata') }
        Object computation = R.call(d, 'compute', sources, metadata, true, true, false, new LinkedHashMap())
        Say.say('alignment computed in ' + (System.currentTimeMillis() - started) + ' ms')
        int logStart = (IJ.log ?: '').length()
        Ui.edt {
            R.call(d, 'report', computation)
            R.call(d, 'showPreview', computation, true)
        }
        Say.say('alignment log:\n' + (IJ.log ?: '').substring(logStart))
        AlignmentMatrixSet matrices = (AlignmentMatrixSet) R.get(computation, 'matrices')
        Cfg.beadMatrix().parentFile.mkdirs()
        matrices.save(Cfg.beadMatrix())
        Say.say('saved ' + Cfg.beadMatrix() + ':\n' + Cfg.beadMatrix().text)

        Map<String, ImageProcessor> projections = (Map<String, ImageProcessor>) R.get(computation, 'projections')
        ImageProcessor reference = projections[REFERENCE]
        ImageProcessor moving = projections[MOVING]
        ImageProcessor aligned = SIFT.alignWithRigid2DMatrix(moving.duplicate(), matrices.matrixFor(MOVING), true)
        int size = 260
        Rectangle region = Shots.box(reference.width / 2 - size / 2, reference.height / 2 - size / 2, size, size)
        BufferedImage before = Shots.resize(Figures.crop(Figures.overlay(reference, moving), region), 2.0d)
        BufferedImage after = Shots.resize(Figures.crop(Figures.overlay(reference, aligned), region), 2.0d)
        Figures.label(before, 'before alignment')
        Figures.label(after, 'after alignment')
        Figures.scaleBar(after, 0.116d / 2, 5)
        BufferedImage pair = new BufferedImage(before.width * 2 + 10, before.height, BufferedImage.TYPE_INT_RGB)
        Graphics2D g = pair.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, pair.width, pair.height)
        g.drawImage(before, 0, 0, null)
        g.drawImage(after, before.width + 10, 0, null)
        g.dispose()
        Figures.jpeg(pair, 'beads-alignment-before-after')

        Ui.edt {
            R.call(d, 'closePreview')
            d.dispose()
        }
    }
}

class SampleStage {
    static void run() {
        LiveTour.open()
        Views.closeMatching('4_beads')
        if (!Cfg.beadMatrix().isFile()) Say.say('WARNING: no bead matrix at ' + Cfg.beadMatrix() + '; run the align stage first')

        JDialog d = LiveTour.setupDialog()
        LiveTour.configure(d, [channelOption: 'align with SIFT', align: Cfg.beadMatrix().path, volumePreview: true,
                slots: ['_Channel0001-left', '_Channel0001-right', '_Channel0002-right']])
        Cap cap = Shots.capture(d)
        Shots.save(cap, 'setup-sample', [marks: LiveTour.exampleMarks(cap, d, true)])
        LiveTour.setupShots(d)
        LiveTour.mode(d, false)
        Cap simple = Shots.capture(d)
        Shots.save(simple, 'setup-simple', [marks: [
                Shots.mark(Shots.rect(simple, (Component) R.get(d, 'modeButton')), '1', [side: 'left'])]])
        LiveTour.mode(d, true)
        LiveTour.apply(d)
        Ui.pause(500)

        LiveTour.start()
        Ui.pause(1500)
        Replay.send(Cfg.sample(), 3.0d)
        /* A busy panel: a time point finished (so the counters and the measured disk rate say
         * something), another in flight, and more queued behind it. */
        if (Ui.waitFor(6 * 60 * 1000L) { LiveTour.processedCount() >= 2 && LiveTour.processing() })
            LiveTour.shotStatus('status-running', [0, 1, 2, 3, 4, 5, 6, 7])
        else Say.say('sample: no busy status to capture (nothing left to process?); keeping the previous shot')

        ImagePlus maxZ = Views.await(10 * 60 * 1000L, '3_timelapse', 'maxZ')
        Ui.waitFor(10 * 60 * 1000L) { LiveTour.committed(Cfg.sample()) >= 2 }
        Ui.pause(3000)
        Views.shotViewer('viewer-live')

        if (!Ui.waitFor(40 * 60 * 1000L) { LiveTour.committed(Cfg.sample()) >= Cfg.timepoints && LiveTour.idle() })
            Say.say('sample: timed out waiting for ' + Cfg.timepoints + ' time points')
        Replay.waitDone(180000)
        Ui.pause(Views.viewer() == null ? 1000 : 12000)   // one live-update poll so every view has grown
        Say.say('open images: ' + Views.images()*.title)

        int t = Cfg.timepoints
        maxZ = Views.find('3_timelapse', 'maxZ')
        ImagePlus maxY = Views.find('3_timelapse', 'maxY')
        ImagePlus maxX = Views.find('3_timelapse', 'maxX')
        ImagePlus volume = Views.images().find { it.title.contains('3_timelapse') && !(it.title =~ /max[XYZ]/) }
        Views.shotWindow(maxZ, 'preview-maxZ', 0.25d, t, 1, true)
        Views.shotWindow(maxY, 'preview-maxY', 0.333d, t, 1, true)
        Views.shotWindow(maxX, 'preview-maxX', 0.333d, t, 1, true)
        if (volume != null) Views.shotWindow(volume, 'preview-volume', 0.25d, t, (int) (volume.NSlices / 2), true)

        Views.shotViewerDialog('configureChannelSetup', 'OME-Zarr viewer channel setup', 'viewer-channel-setup')
        Views.shotViewerDialog('chooseRegion', 'OME-Zarr region', 'viewer-region')

        figures(maxZ, maxY, maxX, t)
        LiveTour.stop()
        LiveTour.shotStatus('status-stopped', [])
    }

    static void figures(ImagePlus maxZ, ImagePlus maxY, ImagePlus maxX, int t) {
        if (maxZ == null) return
        double um = 0.116d
        BufferedImage top = Figures.flatten(maxZ, t, 1, Figures.LOW, Figures.HIGH)
        Say.say("maxZ ${top.width}x${top.height}")
        BufferedImage full = Shots.resize(top, 0.5d)
        Figures.scaleBar(full, um * 2, 20)
        Figures.jpeg(full, 'sample-maxZ-full')

        Rectangle box = brightest(maxZ, t, 640, 480)
        Say.say('orthogonal crop ' + box)
        BufferedImage z = Figures.crop(top, box)
        Figures.scaleBar(z, um, 10)
        BufferedImage y = maxY == null ? null : Figures.flatten(maxY, t, 1, Figures.LOW, Figures.HIGH)
        BufferedImage x = maxX == null ? null : Figures.flatten(maxX, t, 1, Figures.LOW, Figures.HIGH)
        if (y != null) Say.say("maxY ${y.width}x${y.height}")
        if (x != null) Say.say("maxX ${x.width}x${x.height}")
        BufferedImage yCrop = y == null ? null : Figures.crop(y, Shots.box(box.x, 0, box.width, y.height))
        BufferedImage xCrop = x == null ? null : Figures.crop(x, Shots.box(0, box.y, x.width, box.height))
        int gap = 8
        int width = z.width + (xCrop == null ? 0 : gap + xCrop.width)
        int height = z.height + (yCrop == null ? 0 : gap + yCrop.height)
        BufferedImage ortho = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        Graphics2D g = ortho.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)
        g.drawImage(z, 0, 0, null)
        if (xCrop != null) g.drawImage(xCrop, z.width + gap, 0, null)
        if (yCrop != null) g.drawImage(yCrop, 0, z.height + gap, null)
        g.dispose()
        Figures.jpeg(ortho, 'sample-orthogonal-views')
    }

    /**
     * A box of the given size over the busiest part of the field.
     * <p>
     * Found by pooling the brightest channel into coarse blocks and taking the densest block,
     * not by a centroid of the bright pixels: the cells here sit in a few clusters with empty
     * space between them, and their centroid is exactly that empty space.
     */
    static Rectangle brightest(ImagePlus imp, int t, int width, int height) {
        return (Rectangle) Ui.edt {
            double best = -1
            ImageProcessor chosen = null
            for (int c = 1; c <= imp.NChannels; c++) {
                ImageProcessor ip = imp.getStack().getProcessor(imp.getStackIndex(c, 1, t))
                double[] range = Figures.percentiles(ip, 0.5d, 0.9999d)
                if (range[1] - range[0] > best) { best = range[1] - range[0]; chosen = ip }
            }
            int block = 32
            double threshold = Figures.percentiles(chosen, 0.5d, 0.999d)[1]
            int blocksX = (int) Math.ceil(chosen.width / (double) block)
            int blocksY = (int) Math.ceil(chosen.height / (double) block)
            double[][] score = new double[blocksY][blocksX]
            for (int yy = 0; yy < chosen.height; yy += 2) for (int xx = 0; xx < chosen.width; xx += 2)
                if (chosen.getf(xx, yy) >= threshold) score[(int) (yy / block)][(int) (xx / block)]++
            // pool over the crop's own size, so the box is placed on the densest region of that size
            int spanX = Math.max(1, (int) (width / block)), spanY = Math.max(1, (int) (height / block))
            double bestSum = -1
            int bestX = 0, bestY = 0
            for (int by = 0; by + spanY <= blocksY; by++) for (int bx = 0; bx + spanX <= blocksX; bx++) {
                double sum = 0
                for (int j = 0; j < spanY; j++) for (int i = 0; i < spanX; i++) sum += score[by + j][bx + i]
                if (sum > bestSum) { bestSum = sum; bestX = bx; bestY = by }
            }
            int x0 = Math.max(0, Math.min(chosen.width - width, bestX * block))
            int y0 = Math.max(0, Math.min(chosen.height - height, bestY * block))
            Shots.box(x0, y0, width, height)
        }
    }
}

// ---- run --------------------------------------------------------------------------------

Cfg.init()
Say.say('tour stages ' + Cfg.stages + ', images to ' + Cfg.img)
Map<String, Closure> stages = [
        prepare: { Prepare.run() },
        help   : { HelpStage.run() },
        beads  : { BeadsStage.run() },
        align  : { AlignStage.run() },
        sample : { SampleStage.run() }]
for (String stage : Cfg.stages) {
    Closure body = stages[stage]
    if (body == null) { Say.say('unknown stage ' + stage); continue }
    Say.say('==== stage ' + stage)
    try {
        body.call()
    } catch (Throwable failure) {
        Say.error('stage ' + stage, failure)
    }
}
try {
    if (LiveTour.live != null && (boolean) R.get(LiveTour.live, 'running')) LiveTour.stop()
} catch (Throwable failure) {
    Say.error('final stop', failure)
}
Say.say('tour finished')
// Halt rather than quit: nothing of this Fiji session (window positions, recent commands) is
// worth saving over the user's own preferences, and Fiji's quit can wait on its own threads.
Runtime.runtime.halt(0)
