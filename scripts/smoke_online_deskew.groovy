/*
 * Smoke test of Plugins > OPM Toolset > Online Deskew View (BDV) > Open Raw Acquisition..., run inside
 * a Fiji by scripts/smoke-online-deskew.ps1. It drives the real menu command with macro options,
 * waits for the raw strips to load, paints the window into PNGs (no screen grab), jumps along the
 * time axis, turns to a side view, and closes the window with its close button while timing the
 * event thread. It then opens the view again and quits Fiji for real, recording how the session
 * closed under the quit. The JVM is halted at the end; the PowerShell script restores the
 * preferences that the quit writes.
 *
 * Environment: OPM_SMOKE_RAW (acquisition folder), OPM_SMOKE_CSV (alignment CSV, may be empty),
 * OPM_SMOKE_OUT (report folder), OPM_SMOKE_QUIT ("exit": quit as launched, ImageJ ending the JVM;
 * "stay": quit with exitWhenQuitting cleared, as imagej-legacy leaves it, so the JVM has to end by
 * itself; "none": no quit).
 */
import ij.IJ
import ij.WindowManager
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

File out = new File(System.getenv('OPM_SMOKE_OUT') ?: System.getProperty('java.io.tmpdir'))
out.mkdirs()
File report = new File(out, 'smoke_report.txt')
report.text = ''
def log = { String line -> synchronized (report) { report << line + '\n' }; System.out.println(line) }
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
def sessionClass = Class.forName('de.embl.iclm.OnlineDeskewSession')
def latest = { def f = sessionClass.getDeclaredField('latest'); f.accessible = true; f.get(null) }
String raw = System.getenv('OPM_SMOKE_RAW'), csv = System.getenv('OPM_SMOKE_CSV') ?: ''
String options = "raw=[${raw}] position=1 acquisition=1 parameters=[ExperimentalParameters.txt in the folder] " +
        "z=0.265 pixel=0.116 opm=33.5 flip=[flip right half onto left] align=[${csv}] interpolation=trilinear cache=4000"
def open = { String what ->
    def before = latest()
    long start = System.nanoTime()
    IJ.run('Open Raw Acquisition...', options)
    def session = latest()
    if (session == null || session.is(before)) throw new IllegalStateException('the command opened no session')
    log(String.format('%s: opened in %.2f s', what, (System.nanoTime() - start) / 1e9))
    session
}
/** How long the event thread takes to run something posted to it now. */
def heartbeat = {
    CountDownLatch beat = new CountDownLatch(1)
    long start = System.nanoTime()
    SwingUtilities.invokeLater { beat.countDown() }
    beat.await(10, TimeUnit.SECONDS) ? (System.nanoTime() - start) / 1e6 : -1
}
boolean halted = false
try {
    log("java ${System.getProperty('java.version')}, ImageJ ${IJ.getVersion()}")
    log("plugin from ${Class.forName('de.embl.iclm.OnlineDeskewView').protectionDomain.codeSource.location}")
    boolean control = System.getenv('OPM_SMOKE_CONTROL') == 'true'		// quit without ever opening the view
    log("bigdataviewer from ${Class.forName('bdv.viewer.Source').protectionDomain.codeSource.location}")
    def session = control ? null : open('first opening')
    if (control) log('control run: the view is never opened')
    if (!control) {
    def handle = call(session, 'handle'), frame = call(session, 'frame'), store = call(session, 'store')
    def viewer = handle.viewerPanel
    log("window: ${frame.title}, ${frame.class.name}, sources ${viewer.state().sources.size()}, time points ${viewer.state().numTimepoints}")
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
    log(String.format('time index %d present %.2f s after the jump', jump, (System.nanoTime() - jumpStart) / 1e9))
    settle("time index ${jump}, top-down view")
    capture('02_timepoint10_xy.png')

    // the side view of the deskewed volume: a quarter turn about the screen's X axis, about its centre
    def side = viewer.state().viewerTransform.copy()
    double cx = viewer.displayComponent.width / 2.0, cy = viewer.displayComponent.height / 2.0
    side.translate(-cx, -cy, 0)
    side.rotate(0, -Math.PI / 2)
    side.translate(cx, cy, 0)
    SwingUtilities.invokeAndWait { viewer.state().viewerTransform = side }
    settle("time index ${jump}, side view (XZ, looking along Y)")
    capture('03_timepoint10_side_view.png')
    log('status: ' + call(session, 'statusText').replace('\n', ' | '))

    // the close button, as the window system sends it, timed on the event thread
    double dispatch = 0
    SwingUtilities.invokeAndWait {
        long t = System.nanoTime()
        frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING))
        dispatch = (System.nanoTime() - t) / 1e6
    }
    double beat = heartbeat()
    def closed = call(session, 'closed').get(20, TimeUnit.SECONDS)
    log(String.format('close button: returned in %.1f ms, event thread free after %.1f ms; %s', dispatch, beat, closed))
    log("window gone: ${!frame.displayable}; still in Fiji's Window menu: ${WindowManager.nonImageWindows.contains(frame)}")

    }
    String quitMode = System.getenv('OPM_SMOKE_QUIT') ?: 'exit'
    if (quitMode == 'none') {
        log('SMOKE PASSED (quit stage skipped)')
        return
    }
    // open again, then quit Fiji for real
    def again = control ? null : open('second opening')
    Thread.sleep(2000)
    if (!control) log('before quit: ' + call(call(again, 'store'), 'stats'))
    long quitStart = System.nanoTime()
    def elapsed = { (System.nanoTime() - quitStart) / 1e9 }
    // however the JVM ends, say what state the session had reached, and who ended it
    Runtime.runtime.addShutdownHook(new Thread({
        double ending = elapsed()
        if (again == null) {
            log(String.format('JVM ending %.2f s after the quit (control run)', ending))
            return
        }
        def future = call(again, 'closed')
        boolean disposed = !call(again, 'frame').displayable, closing = call(again, 'isClosing')
        try {
            future.get(4, TimeUnit.SECONDS)		// the session's own exit hook gives its close a bounded time
        } catch (Throwable notInTime) {
            // reported below
        }
        log(String.format('JVM ending %.2f s after the quit: window disposed %s, session closing %s; close finished %s%s',
                ending, disposed, closing, future.isDone(), future.isDone() ? ': ' + future.get() : ''))
        Thread.allStackTraces.each { t, stack ->
            if (stack.any { it.className in ['java.lang.Shutdown', 'java.lang.Runtime', 'java.lang.System'] && it.methodName == 'exit' })
                log("System.exit called on thread '${t.name}' from ${stack.find { !it.className.startsWith('java.') }}")
        }
        log(future.isDone() && future.get().drained ? 'SMOKE PASSED' : 'SMOKE FAILED: the session had not closed when the JVM ended')
    }, 'smoke-exit-report'))
    // a daemon watch: the quit's progress, and what still holds the JVM if it has not ended after 20 s
    Thread watch = new Thread({
        while (elapsed() < 20) {
            def ij = IJ.instance
            if (again != null || elapsed() < 1)
                log(String.format('  %.1f s: ImageJ %s; our window displayable %s; session closing %s, closed %s',
                        elapsed(), ij == null ? 'gone' : (ij.quitting() ? 'quitting' : 'running'),
                        again?.with { call(it, 'frame').displayable }, again?.with { call(it, 'isClosing') },
                        again?.with { call(it, 'closed').isDone() }))
            Thread.sleep(again == null ? 100 : 500)
        }
        def holders = Thread.allStackTraces.findAll { t, st -> t.alive && !t.daemon && t != Thread.currentThread() }
        def windows = java.awt.Window.windows.findAll { it.displayable }.collect {
            "${it.class.name} '${it instanceof java.awt.Frame ? it.title : (it instanceof java.awt.Dialog ? it.title : '')}' visible ${it.visible}"
        }
        // Fiji itself may not end the JVM here (see a -Control run); what matters is that nothing of ours holds it
        def oursThreads = Thread.allStackTraces.keySet().findAll { it.alive && it.name.startsWith('OPM-') }*.name
        def oursWindows = java.awt.Window.windows.findAll { it.displayable && it.class.name.contains('OnlineDeskew') }
        log("the JVM is still running 20 s after the quit; displayable windows ${windows}; non-daemon threads ${holders.keySet()*.name}")
        holders.each { t, st -> log("  non-daemon thread '${t.name}':\n    " + st.take(8).join('\n    ')) }
        if (again == null) {
            log('CONTROL: this is what holds the JVM without the view ever opened')
            Runtime.runtime.halt(0)
        }
        boolean closedCleanly = call(again, 'closed').isDone() && call(again, 'closed').get().drained
        if (closedCleanly && oursThreads.isEmpty() && oursWindows.isEmpty()) {
            log('SMOKE PASSED: the view closed with the quit and holds nothing; the JVM is held only by the above')
            Runtime.runtime.halt(0)
        }
        log("SMOKE FAILED: closed cleanly ${closedCleanly}; our threads ${oursThreads}; our windows ${oursWindows*.class*.name}")
        Runtime.runtime.halt(1)
    }, 'smoke-quit-watch')
    watch.daemon = true
    watch.start()
    if (quitMode == 'stay') {
        IJ.instance.exitWhenQuitting(false)		// as imagej-legacy leaves it: the JVM must end by itself
        log('quitting Fiji; ImageJ will not call System.exit, so the JVM has to end on its own')
    } else log('quitting Fiji; ImageJ calls System.exit at the end of its quit')
    halted = true
    IJ.run('Quit')
    // the script thread ends here, so that it holds nothing open
} catch (Throwable failure) {
    StringWriter trace = new StringWriter()
    failure.printStackTrace(new PrintWriter(trace))
    log('SMOKE FAILED: ' + trace)
} finally {
    if (!halted) Runtime.runtime.halt(0)
}
