/*
 * Smoke test of Plugins > OPM Toolset > Online Deskew View (BDV) > Open Raw Acquisition..., run inside
 * a Fiji by scripts/smoke-online-deskew.ps1. It drives the real menu command with macro options,
 * waits for the raw strips to load, paints the BigDataViewer window into PNGs (no screen grab),
 * steps the time slider, turns to a side view, then closes the window the way Fiji's quit does
 * (dispose) and records how the session closed. The JVM is halted at the end so this Fiji never
 * writes its preferences.
 *
 * Environment: OPM_SMOKE_RAW (acquisition folder), OPM_SMOKE_CSV (alignment CSV, may be empty),
 * OPM_SMOKE_OUT (report folder).
 */
import ij.IJ
import ij.WindowManager
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import java.awt.image.BufferedImage
import java.util.concurrent.TimeUnit

File out = new File(System.getenv('OPM_SMOKE_OUT') ?: System.getProperty('java.io.tmpdir'))
out.mkdirs()
File report = new File(out, 'smoke_report.txt')
report.text = ''
def log = { String line -> report << line + '\n'; System.out.println(line) }
def call = { Object target, String name ->
    def m = target.getClass().getDeclaredMethod(name)
    m.accessible = true
    m.invoke(target)
}
def field = { Object target, String name ->
    def f = target.getClass().getDeclaredField(name)
    f.accessible = true
    f.get(target)
}

try {
    String raw = System.getenv('OPM_SMOKE_RAW'), csv = System.getenv('OPM_SMOKE_CSV') ?: ''
    def viewClass = Class.forName('de.embl.iclm.OnlineDeskewView')
    log("java ${System.getProperty('java.version')}, ImageJ ${IJ.getVersion()}")
    log("plugin from ${viewClass.protectionDomain.codeSource.location}")
    log("bigdataviewer from ${Class.forName('bdv.viewer.Source').protectionDomain.codeSource.location}")
    String options = "raw=[${raw}] position=1 acquisition=1 parameters=[ExperimentalParameters.txt in the folder] " +
            "z=0.265 pixel=0.116 opm=33.5 flip=[flip right half onto left] align=[${csv}] interpolation=trilinear cache=4000"
    long start = System.nanoTime()
    IJ.run('Open Raw Acquisition...', options)
    def latest = Class.forName('de.embl.iclm.OnlineDeskewSession').getDeclaredField('latest')
    latest.accessible = true
    def session = latest.get(null)
    if (session == null) throw new IllegalStateException('the command opened no session')
    log(String.format('opened in %.2f s', (System.nanoTime() - start) / 1e9))
    def handle = call(session, 'handle'), frame = call(session, 'frame'), store = call(session, 'store')
    def viewer = handle.viewerPanel
    log("window: ${frame.title}, sources ${viewer.state().sources.size()}, time points ${viewer.state().numTimepoints}")
    log("in Fiji's Window menu: ${WindowManager.nonImageWindows.contains(frame)}")

    // wait until no read is running and nothing new was loaded for a second
    def settle = { String what ->
        long t0 = System.nanoTime(), last = -1, quietSince = System.nanoTime()
        while ((System.nanoTime() - t0) < 60e9) {
            def s = call(store, 'stats')
            long loads = field(s, 'loads'), inFlight = field(s, 'inFlight')
            if (loads != last || inFlight > 0) { last = loads; quietSince = System.nanoTime() }
            else if ((System.nanoTime() - quietSince) > 1e9) break
            Thread.sleep(100)
        }
        Thread.sleep(500)		// one more repaint with every cell valid
        log(String.format('%s: settled in %.2f s; %s', what, (System.nanoTime() - t0) / 1e9 - 1.5, call(store, 'stats')))
    }
    def capture = { String name ->
        BufferedImage image = new BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
        SwingUtilities.invokeAndWait { def g = image.createGraphics(); frame.printAll(g); g.dispose() }
        ImageIO.write(image, 'png', new File(out, name))
        log("painted ${name}")
    }

    settle('first time point, top-down view')
    capture('01_first_timepoint_xy.png')
    log('status: ' + call(session, 'statusText').replace('\n', ' | '))

    // a jump past files not checked yet: the shown one is checked first
    int jump = Math.min(10, viewer.state().numTimepoints - 1)
    def left = viewer.state().sources[0].spimSource
    long jumpStart = System.nanoTime()
    SwingUtilities.invokeAndWait { viewer.setTimepoint(jump) }
    while (!left.isPresent(jump) && (System.nanoTime() - jumpStart) < 30e9) Thread.sleep(10)
    log(String.format('time index %d present %.2f s after the jump (checked before the files in front of it)',
            jump, (System.nanoTime() - jumpStart) / 1e9))
    settle("time index ${jump}, top-down view")
    capture('02_timepoint10_xy.png')
    log('status: ' + call(session, 'statusText').replace('\n', ' | '))

    // BigDataViewer's own Shift+Y (align XZ) aligns to the current source's axes, which here are sheared
    def before = viewer.state().viewerTransform
    SwingUtilities.invokeAndWait { viewer.align(bdv.viewer.AbstractViewerPanel.AlignPlane.XZ) }
    Thread.sleep(1000)		// the turn is animated
    log("align(XZ) viewer transform: before ${before}; after ${viewer.state().viewerTransform}")
    // the side view of the deskewed volume: a quarter turn about the screen's X axis, about its centre
    def side = before.copy()
    double cx = viewer.displayComponent.width / 2.0, cy = viewer.displayComponent.height / 2.0
    side.translate(-cx, -cy, 0)
    side.rotate(0, -Math.PI / 2)
    side.translate(cx, cy, 0)
    SwingUtilities.invokeAndWait { viewer.state().viewerTransform = side }
    settle("time index ${jump}, side view (XZ, looking along Y)")
    capture('03_timepoint10_side_view.png')

    long closeStart = System.nanoTime()
    SwingUtilities.invokeAndWait { frame.dispose() }
    def result = call(session, 'closed').get(20, TimeUnit.SECONDS)
    log(String.format('closed by dispose (as on Fiji quit): %s, %.2f s after dispose', result, (System.nanoTime() - closeStart) / 1e9))
    log("still in Fiji's Window menu: ${WindowManager.nonImageWindows.contains(frame)}")
    log('SMOKE PASSED')
} catch (Throwable failure) {
    StringWriter trace = new StringWriter()
    failure.printStackTrace(new PrintWriter(trace))
    log('SMOKE FAILED: ' + trace)
} finally {
    Runtime.runtime.halt(0)
}
