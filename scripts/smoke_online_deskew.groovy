/*
 * Smoke test of Plugins > OPM Toolset > Online Deskew View (BDV) > Open Raw Acquisition..., run inside
 * a Fiji by scripts/smoke-online-deskew.ps1. It drives the real menu command with macro options,
 * waits for the raw strips to load, paints the window into PNGs (no screen grab), jumps along the
 * time axis, turns to a side view, plays six time points of it in completed-frame mode - each
 * acknowledged only once the canvas has painted it, with its own time label - and plays the
 * acquisition through the toolbar. In completed mode it then pans, zooms, rotates, switches the
 * interpolation, hides a source and narrows a display range, each by hand and each presenting a
 * frame of the edited view; back in progressive mode, a slider move during playback must pause it
 * and keep the chosen time point (M2-Q, Q5). Given files to add, the folder then grows (M3-A): they are
 * copied in chunk by chunk, as an acquisition writes them, while progressive playback loops, and each
 * joins the time axis once complete; the last is an older time number, which renumbers the axis while
 * the time point shown stays the one shown; the new time points are then presented as completed frames
 * labelled by the axis as it stands. It closes the window with its close button while timing the event
 * thread, then opens the view again and quits Fiji for real, recording how the session closed under
 * the quit.
 *
 * Before anything else it checks that this Fiji is isolated from the user's preferences - its own
 * user.home, Java preferences in memory (see smoke-online-deskew.ps1) - and stops if not: the
 * quit writes preferences, and they must not reach those of another Fiji of the same user.
 *
 * Environment: OPM_SMOKE_RAW (acquisition folder), OPM_SMOKE_CSV (alignment CSV, may be empty),
 * OPM_SMOKE_OUT (report folder), OPM_SMOKE_QUIT ("exit": quit as launched, ImageJ ending the JVM;
 * "stay": quit with exitWhenQuitting cleared, as imagej-legacy leaves it, so the JVM has to end by
 * itself; "none": no quit), OPM_SMOKE_CONTROL ("true": never open the view), OPM_SMOKE_HOME (the
 * run's home), OPM_SMOKE_RUN_ID (a marker written into the isolated preferences), OPM_SMOKE_GROW_FILES
 * (files to copy into the acquisition folder while the view is open, separated by ';', in that order,
 * the last an older time number; empty: the folder does not grow).
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
String candidate = System.getenv('OPM_SMOKE_CANDIDATE') ?: ''
if (candidate) {
    File loaded = new File(sessionClass.protectionDomain.codeSource.location.toURI())
    if (loaded.canonicalFile != new File(candidate).canonicalFile) throw new IllegalStateException('Wrong candidate loaded: ' + loaded)
    log('candidate loaded: ' + loaded.canonicalPath)
}
def latest = { def f = sessionClass.getDeclaredField('latest'); f.accessible = true; f.get(null) }
String raw = System.getenv('OPM_SMOKE_RAW'), csv = System.getenv('OPM_SMOKE_CSV') ?: ''
boolean twoFile = System.getenv('OPM_SMOKE_TWO_FILE') == 'true'
String options = "raw=[${raw}] position=1 acquisition=1 parameters=[ExperimentalParameters.txt in the folder] " +
        "z=0.265 pixel=0.116 opm=33.5 flip=[flip right half onto left] align=[${csv}] interpolation=trilinear cache=4000"
if (twoFile) options += ' combine 1st=[_Channel0002-right] 2nd=[_Channel0001-left] 3rd=[_Channel0002-left] 4th=[_Channel0001-right]'
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

    // isolation first: this Fiji's preferences must be its own
    String home = System.getenv('OPM_SMOKE_HOME'), runId = System.getenv('OPM_SMOKE_RUN_ID')
    def javaPrefs = java.util.prefs.Preferences.userRoot()
    String ijPrefsDir = ij.Prefs.getPrefsDir()
    log("isolation: user.home ${System.getProperty('user.home')}; ImageJ preferences in ${ijPrefsDir}; Java preferences ${javaPrefs.class.name}")
    String homePath = home == null ? null : new File(home).canonicalPath
    boolean isolated = homePath != null && new File(System.getProperty('user.home')).canonicalPath == homePath &&
            new File(ijPrefsDir).canonicalPath.startsWith(homePath) && javaPrefs.class.name.startsWith('opm.smoke.')
    if (!isolated) {
        log("SMOKE STOPPED: this Fiji is not isolated from the user's preferences; nothing was opened")
        Runtime.runtime.halt(2)
    }
    javaPrefs.node('opm-smoke-isolation').put('run', runId)		// reaches the registry only if isolation failed
    ij.Prefs.set('opm.smoke.run', runId)							// in the isolated IJ_Prefs.txt, once Fiji quits
    log('isolation: in effect')
    def session = control ? null : open('first opening')
    if (control) log('control run: the view is never opened')
    if (!control) {
    def handle = call(session, 'handle'), frame = call(session, 'frame'), store = call(session, 'store')
    def viewer = handle.viewerPanel
    log("window: ${frame.title}, ${frame.class.name}, sources ${viewer.state().sources.size()}, time points ${viewer.state().numTimepoints}")
    log("in Fiji's Window menu: ${WindowManager.nonImageWindows.contains(frame)}")
    if (twoFile) {
        def keys = viewer.state().sources.collect { field(call(it.spimSource, 'spec'), 'key') }
        if (keys != ['_Channel0002-right', '_Channel0001-left', '_Channel0002-left', '_Channel0001-right'])
            throw new IllegalStateException('two-file source slots/order: ' + keys)
        log('two-file sources: ' + keys)
    }

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

    // Exercise the actual toolbar buttons, preserving the world anchor, zoom and source transforms.
    def findButton
    findButton = { java.awt.Container parent, String text ->
        for (def c : parent.components) {
            if (c instanceof javax.swing.JButton && c.text == text) return c
            if (c instanceof java.awt.Container) { def found = findButton(c, text); if (found != null) return found }
        }
        null
    }
    def pressWorld = { String plane ->
        def future = null
        SwingUtilities.invokeAndWait {
            double[] centre = [viewer.displayComponent.width / 2.0, viewer.displayComponent.height / 2.0, 0] as double[]
            double[] anchor = new double[3], screen = new double[3]
            def before = viewer.state().viewerTransform.copy()
            before.inverse().apply(centre, anchor)
            double scale = Math.hypot(Math.hypot(before.get(0, 0), before.get(0, 1)), before.get(0, 2))
            def button = findButton(frame, 'World ' + plane)
            if (button == null || !button.enabled) throw new IllegalStateException('No enabled World ' + plane + ' button')
            button.doClick(0)
            def after = viewer.state().viewerTransform
            after.apply(anchor, screen)
            for (int d = 0; d < 3; d++) if (Math.abs(screen[d] - centre[d]) > 1e-7) throw new IllegalStateException('World anchor moved')
            def axes = [XY: [[1,0,0],[0,1,0],[0,0,1]], XZ: [[1,0,0],[0,0,1],[0,-1,0]], YZ: [[0,1,0],[0,0,1],[1,0,0]]][plane]
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++)
                if (Math.abs(after.get(i,j) - scale * axes[i][j]) > 1e-7 * scale) throw new IllegalStateException('Wrong world orientation/zoom')
        }
        // World actions coalesce their capture on the next EDT turn.
        SwingUtilities.invokeAndWait { future = call(session, 'navigationResult') }
        log('World ' + plane + ': toolbar applied, centre and zoom preserved')
        future
    }
    for (String plane : ['XY', 'YZ', 'XZ']) {
        pressWorld(plane)
        settle("time index ${jump}, World ${plane}")
        capture("03_world_${plane.toLowerCase()}.png")
    }
    settle("time index ${jump}, side view (XZ, looking along Y)")
    capture('03_timepoint10_side_view.png')
    log('status: ' + call(session, 'statusText').replace('\n', ' | '))

    // completed-frame playback (M2 spike): the side view at six consecutive time points, each
    // acknowledged only once the canvas has painted it
    def player = null
    SwingUtilities.invokeAndWait { player = call(session, 'strictPlayer') }
    def presenter = call(session, 'presenter')
    def ask = sessionClass.getDeclaredMethod('requestStrictFrame', int)
    ask.accessible = true
    def onEventThread = { Object target, String name -> def v = null; SwingUtilities.invokeAndWait { v = call(target, name) }; v }
    int strictTimes = Math.min(6, viewer.state().numTimepoints)
    for (int t = 0; t < strictTimes; t++) {
        def future = null
        SwingUtilities.invokeAndWait { future = ask.invoke(session, t) }
        def result = future.get(60, TimeUnit.SECONDS)
        def request = field(result, 'request')
        String label = onEventThread(presenter, 'label')
        log("strict playback: ${result}; label '${label}'")
        def presented = field(result, 'presented')
        if (field(result, 'outcome').name() != 'PRESENTED' || !field(presented, 'request').is(request) || field(request, 'timeIndex') != t
                || field(result, 'coverageMisses') != 0)
            throw new IllegalStateException("strict playback: time index ${t} was not presented as asked: ${result}")
        if (!label.startsWith("t = ${t + 1} ") || !label.contains("paint ${t + 1}"))
            throw new IllegalStateException("strict playback: the label '${label}' is not time index ${t}, paint ${t + 1}")
    }
    capture('04_strict_playback_side_view.png')
    for (String plane : ['XY', 'XZ', 'YZ']) {
        def result = pressWorld(plane).get(60, TimeUnit.SECONDS)
        if (field(result, 'outcome').name() != 'PRESENTED' || field(result, 'coverageMisses') != 0
                || field(field(result, 'request'), 'timeIndex') != strictTimes - 1)
            throw new IllegalStateException('World action did not present the requested time: ' + result)
        log('completed World ' + plane + ': ' + result)
        capture("05_completed_world_${plane.toLowerCase()}.png")
    }
    log('strict playback: ' + call(player, 'summary') + '; ' + call(store, 'stats'))
    SwingUtilities.invokeAndWait { call(session, 'stopStrictPlayback') }
    long staged = field(call(store, 'stats'), 'stagedBytes')
    if (staged != 0) throw new IllegalStateException("strict playback: ${staged} bytes of staged frames left after it stopped")
    log('strict playback stopped: no staged frame left')

    // completed-frame playback through the toolbar (M2): World XY, the mode box set to completed
    // frames, Play at the default rate from the time shown to the last; every time point presented
    // complete, none skipped, and the status giving what the run delivered
    pressWorld('XY')
    settle("time index ${strictTimes - 1}, World XY")
    def modeClass = Class.forName('de.embl.iclm.RawPlayback$Mode')
    def modeBox = field(session, 'playbackMode'), playButton = findButton(frame, 'Play')
    def entered = null
    SwingUtilities.invokeAndWait {
        modeBox.selectedItem = Enum.valueOf(modeClass, 'COMPLETED')
        entered = call(session, 'navigationResult')
    }
    def shownFirst = entered.get(60, TimeUnit.SECONDS)
    if (field(shownFirst, 'outcome').name() != 'PRESENTED')
        throw new IllegalStateException('completed frames: the time shown was not presented on entering the mode: ' + shownFirst)
    int from = field(field(shownFirst, 'request'), 'timeIndex'), lastTime = viewer.state().numTimepoints - 1
    long playStart = System.nanoTime()
    SwingUtilities.invokeAndWait { playButton.doClick(0) }
    String playText = ''
    while ((System.nanoTime() - playStart) < 300e9) {
        SwingUtilities.invokeAndWait { playText = call(session, 'playbackText') }
        if (!playText.contains('playing')) break
        Thread.sleep(200)
    }
    log(String.format('completed playback, time index %d to %d, %.1f s: %s', from, lastTime, (System.nanoTime() - playStart) / 1e9, playText))
    if (!playText.contains('Stopped at the last time point') || !playText.contains("${lastTime - from} presented at".toString())
            || playText.contains('skipped'))
        throw new IllegalStateException('completed playback did not present every time point to the last: ' + playText)
    capture('06_completed_playback_xy.png')
    def playing = null
    SwingUtilities.invokeAndWait { playing = call(session, 'strictPlayer') }
    log('completed playback: ' + call(playing, 'summary'))

    // manual edits in completed mode (M2-Q, Q5): each presents a frame of the edited view - the
    // presented request equal, field by field, to a fresh capture of the state - before the next
    def frameRequest = sessionClass.getDeclaredMethod('frameRequest', int)
    frameRequest.accessible = true
    def sameCapture = { a, b ->
        if (field(a, 'timeIndex') != field(b, 'timeIndex') || field(a, 'width') != field(b, 'width') || field(a, 'height') != field(b, 'height')
                || field(a, 'interpolation') != field(b, 'interpolation')
                || !Arrays.equals(field(a, 'viewer').rowPackedCopy as double[], field(b, 'viewer').rowPackedCopy as double[])) return false
        if (field(a, 'publications') != field(b, 'publications')) return false
        def la = field(a, 'layers'), lb = field(b, 'layers')
        if (la.size() != lb.size()) return false
        for (int i = 0; i < la.size(); i++) {
            def x = la[i], y = lb[i]
            if (!field(x, 'spec').is(field(y, 'spec')) || field(x, 'argb') != field(y, 'argb') || field(x, 'min') != field(y, 'min')
                    || field(x, 'max') != field(y, 'max')
                    || !Arrays.equals(field(x, 'sourceToWorld').rowPackedCopy as double[], field(y, 'sourceToWorld').rowPackedCopy as double[]))
                return false
        }
        true
    }
    def aboutCentre = { Closure change ->
        def t = viewer.state().viewerTransform
        double cx = viewer.displayComponent.width / 2.0, cy = viewer.displayComponent.height / 2.0
        t.translate(-cx, -cy, 0)
        change(t)
        t.translate(cx, cy, 0)
        viewer.state().viewerTransform = t
    }
    def setup0 = handle.converterSetups.getConverterSetup(viewer.state().sources[0])
    def edits = [
        pan          : { def t = viewer.state().viewerTransform; t.set(t.get(0, 3) + 40, 0, 3); viewer.state().viewerTransform = t },
        zoom         : { aboutCentre { it.scale(1.25) } },
        rotate       : { aboutCentre { it.rotate(1, Math.toRadians(20)) } },
        interpolation: { viewer.state().interpolation = viewer.state().interpolation == bdv.viewer.Interpolation.NLINEAR ?
                                 bdv.viewer.Interpolation.NEARESTNEIGHBOR : bdv.viewer.Interpolation.NLINEAR },
        source       : { viewer.state().setSourceActive(viewer.state().sources[1], false) },
        range        : { setup0.setDisplayRange(setup0.displayRangeMin, setup0.displayRangeMin + 0.7 * (setup0.displayRangeMax - setup0.displayRangeMin)) },
    ]
    edits.each { String name, Closure edit ->
        SwingUtilities.invokeAndWait { edit() }
        SwingUtilities.invokeAndWait { }		// the coalesced capture runs on the next turn
        def pendingEdit = null
        SwingUtilities.invokeAndWait { pendingEdit = call(session, 'navigationResult') }
        def result = pendingEdit.get(120, TimeUnit.SECONDS)
        def request = field(result, 'request')
        def fresh = null
        SwingUtilities.invokeAndWait { fresh = frameRequest.invoke(session, field(request, 'timeIndex')) }
        if (field(result, 'outcome').name() != 'PRESENTED' || field(result, 'coverageMisses') != 0 || !sameCapture(request, fresh))
            throw new IllegalStateException("manual edit ${name}: not presented as the edited view: ${result}")
        log("manual edit ${name}: presented the edited view; ${result}")
        capture("07_edit_${name}.png")
    }
    SwingUtilities.invokeAndWait { viewer.state().setSourceActive(viewer.state().sources[1], true) }

    SwingUtilities.invokeAndWait { modeBox.selectedItem = Enum.valueOf(modeClass, 'PROGRESSIVE') }
    staged = field(call(store, 'stats'), 'stagedBytes')
    if (staged != 0) throw new IllegalStateException("completed playback: ${staged} bytes of staged frames left after it stopped")
    log('back to progressive drawing, no staged frame left; ' + call(store, 'stats'))

    // a seek by hand during progressive playback pauses it, and the chosen time point stays (M2-C2, Q5)
    def findSlider
    findSlider = { java.awt.Container parent, int max ->
        for (def c : parent.components) {
            if (c instanceof javax.swing.JSlider && c.maximum == max) return c
            if (c instanceof java.awt.Container) { def found = findSlider(c, max); if (found != null) return found }
        }
        null
    }
    def slider = findSlider(viewer, viewer.state().numTimepoints - 1)
    if (slider == null) throw new IllegalStateException('no time slider')
    def playbackObject = call(session, 'playback')
    def playbackClass = Class.forName('de.embl.iclm.RawPlayback')
    def setLoop = playbackClass.getDeclaredMethod('setLoop', boolean), setFps = playbackClass.getDeclaredMethod('setTargetFps', double)
    def isPlaying = playbackClass.getDeclaredMethod('isPlaying')
    [setLoop, setFps, isPlaying]*.accessible = true
    SwingUtilities.invokeAndWait {
        setLoop.invoke(playbackObject, true)
        setFps.invoke(playbackObject, 5d)
        playButton.doClick(0)
    }
    Thread.sleep(1500)
    int chosen = (viewer.state().currentTimepoint + (int) (viewer.state().numTimepoints / 2)) % viewer.state().numTimepoints
    boolean stillPlaying = true
    SwingUtilities.invokeAndWait { slider.value = chosen; stillPlaying = isPlaying.invoke(playbackObject) }
    Thread.sleep(600)		// three periods at 5 fps
    String seekText = ''
    boolean playingAfter = true
    SwingUtilities.invokeAndWait { seekText = call(session, 'playbackText'); playingAfter = isPlaying.invoke(playbackObject) }
    if (stillPlaying || playingAfter || viewer.state().currentTimepoint != chosen)
        throw new IllegalStateException("progressive seek: playing ${stillPlaying}, now at ${viewer.state().currentTimepoint}, chose ${chosen}: ${seekText}")
    log("progressive seek: paused at the chosen time index ${chosen}; ${seekText}")
    settle("time index ${chosen}, progressive, after the seek")
    capture('08_progressive_seek.png')

    // the folder grows while the view is open (M3-A)
    String growFiles = System.getenv('OPM_SMOKE_GROW_FILES') ?: ''
    if (!growFiles.isEmpty()) {
        def snapshotNow = { call(session, 'snapshot') }
        def idsNow = { field(snapshotNow(), 'times').collect { field(it, 'timeId') } }
        def allShown = {
            def snapshot = snapshotNow()
            def groupCapture = snapshot.class.getDeclaredMethod('capture', int)
            groupCapture.accessible = true
            (0..<call(snapshot, 'size')).every { call(groupCapture.invoke(snapshot, it as int), 'complete') }
        }
        def awaitTrue = { String what, double seconds, Closure test ->
            long t0 = System.nanoTime()
            while (!test()) {
                if ((System.nanoTime() - t0) > seconds * 1e9) throw new IllegalStateException("growing folder: not within ${seconds} s: ${what}")
                Thread.sleep(100)
            }
            (System.nanoTime() - t0) / 1e9
        }
        // copy a file as an acquisition writes it: in chunks, at a bounded rate
        def grow = { File source ->
            File to = new File(raw, source.name)
            def input = java.nio.channels.FileChannel.open(source.toPath(), java.nio.file.StandardOpenOption.READ)
            def output = java.nio.channels.FileChannel.open(to.toPath(), java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)
            long t0 = System.nanoTime()
            try {
                def buffer = java.nio.ByteBuffer.allocateDirect(16 << 20)
                long done = 0, size = input.size()
                while (done < size) {
                    buffer.clear()
                    if (input.read(buffer, done) < 0) throw new IOException('short read of ' + source)
                    buffer.flip()
                    while (buffer.hasRemaining()) done += output.write(buffer, done)
                    Thread.sleep(50)
                }
            } finally {
                input.close()
                output.close()
            }
            log(String.format('growing folder: %s copied as it grew in %.1f s', source.name, (System.nanoTime() - t0) / 1e9))
        }
        def growTime = { File source ->
            grow(source)
            if (twoFile) {
                // Let the first file publish alone, and assert that it does not make a partial time available.
                int id = (source.name =~ /_Time(\d+)_/)[0][1].toInteger()
                awaitTrue('first file discovered', 120) { idsNow().contains(id) }
                def snapshot = snapshotNow()
                def groupCapture = snapshot.class.getDeclaredMethod('capture', int)
                groupCapture.accessible = true
                def group = groupCapture.invoke(snapshot, idsNow().indexOf(id) as int)
                if (call(group, 'complete') || !call(group, 'problem').contains('_Channel0002: missing file'))
                    throw new IllegalStateException('one file made a two-file time available: ' + call(group, 'problem'))
                grow(new File(source.parentFile, source.name.replace('_Channel0001_', '_Channel0002_')))
            }
        }
        def playMethod = playbackClass.getDeclaredMethod('play'), pauseMethod = playbackClass.getDeclaredMethod('pause')
        [playMethod, pauseMethod]*.accessible = true
        List<File> adding = growFiles.split(';').collect { new File(it) }
        File late = adding.remove(adding.size() - 1)
        List<Integer> startIds = idsNow()
        log("growing folder: ${startIds.size()} time points ${startIds}; adding ${adding*.name}, then the older ${late.name}")

        // later time points, while progressive playback loops
        SwingUtilities.invokeAndWait { playMethod.invoke(playbackObject) }
        // The first arrival keeps the M3-A movie check. Subsequent arrivals exercise M3-B.
        growTime(adding[0])
        awaitTrue('first movie arrival published', 120) { viewer.state().numTimepoints == startIds.size() + 1 && allShown() }
        boolean movieContinued = false
        SwingUtilities.invokeAndWait { movieContinued = isPlaying.invoke(playbackObject) }
        if (!movieContinued) throw new IllegalStateException('growing folder: first arrival stopped movie playback')
        def follow = field(session, 'followBox')
        SwingUtilities.invokeAndWait { follow.doClick() }
        if (!follow.selected || isPlaying.invoke(playbackObject)) throw new IllegalStateException('follow-latest did not pause Play')
        adding.drop(1).eachWithIndex { File source, int i ->
            boolean completed = i > 0
            SwingUtilities.invokeAndWait { modeBox.selectedIndex = completed ? 1 : 0 }
            growTime(source)
            int wantedId = (source.name =~ /_Time(\d+)_/)[0][1].toInteger()
            awaitTrue("follow ${completed ? 'completed' : 'progressive'} Time ${wantedId}", 120) {
                def request = completed ? call(call(session, 'presenter'), 'shownRequest') : null
                idsNow()[viewer.state().currentTimepoint] == wantedId &&
                    (!completed || request != null && field(field(field(request, 'entry'), 'time'), 'timeId') == wantedId)
            }
            if (!follow.selected || isPlaying.invoke(playbackObject)) throw new IllegalStateException('automatic follow changed its control state')
            log("follow-latest: ${completed ? 'completed' : 'progressive'} Time ${wantedId} shown; Play paused")
            capture("09_follow_${completed ? 'completed' : 'progressive'}.png")
        }
        int grown = startIds.size() + adding.size()
        double waited = awaitTrue("${grown} time points, every one shown", 120) { viewer.state().numTimepoints == grown && idsNow().size() == grown && allShown() }
        boolean playingThrough = true
        String growText = ''
        SwingUtilities.invokeAndWait { playingThrough = isPlaying.invoke(playbackObject); growText = call(session, 'playbackText') }
        if (playingThrough || !follow.selected)
            throw new IllegalStateException('growing folder: Follow latest lost control: ' + growText)
        if (findSlider(viewer, grown - 1) == null) throw new IllegalStateException('growing folder: the time slider did not grow to ' + grown)
        log(String.format('growing folder: %d time points, all shown %.1f s after the last byte; Follow latest owns navigation: %s', grown, waited, growText))

        // an older time number, late: the axis is renumbered under the paused view, at its last time point, and the
        // time point shown stays the one shown, one index further on
        SwingUtilities.invokeAndWait { modeBox.selectedIndex = 0; pauseMethod.invoke(playbackObject); viewer.setTimepoint(grown - 1) }
        int shownIndex = viewer.state().currentTimepoint
        int shownId = idsNow()[shownIndex]
        def renumberedBefore = call(session, 'renumberings')
        growTime(late)
        waited = awaitTrue("${grown + 1} time points, every one shown", 120) { viewer.state().numTimepoints == grown + 1 && allShown() }
        List<Integer> endIds = idsNow()
        if (endIds != endIds.sort(false) || endIds.unique(false).size() != endIds.size())
            throw new IllegalStateException('growing folder: the time axis is not each time point once, in order: ' + endIds)
        int nowIndex = viewer.state().currentTimepoint
        if (endIds[nowIndex] != shownId || nowIndex != shownIndex + 1)
            throw new IllegalStateException("growing folder: Time ${shownId} was shown at index ${shownIndex}; after the late time point Time ${endIds[nowIndex]} is, at ${nowIndex}")
        if (call(session, 'renumberings') == renumberedBefore)
            throw new IllegalStateException('growing folder: the late time point did not renumber the axis')
        if (findSlider(viewer, grown) == null) throw new IllegalStateException('growing folder: the time slider did not grow to ' + (grown + 1))
        log(String.format('growing folder: the late time point renumbered the axis to %s, %.1f s after its last byte; Time %d still shown, at index %d (was %d)',
                endIds, waited, shownId, nowIndex, shownIndex))
        settle("time index ${nowIndex}, after the folder grew")
        capture('09_grown_folder.png')
        log('status: ' + call(session, 'statusText').replace('\n', ' | '))
        if (!follow.selected) throw new IllegalStateException('late insertion disabled follow-latest')
        SwingUtilities.invokeAndWait { viewer.setTimepoint(0) }
        if (follow.selected) throw new IllegalStateException('manual seek did not turn Follow latest off visibly')
        log('follow-latest: passed; both modes, movie handover, late insertion, manual release')

        // the new time points as completed frames, each labelled by the axis as it stands
        def newIndices = (0..<endIds.size()).findAll { !startIds.contains(endIds[it]) }
        def presenterNow = null		// completed frames start again: their presenter is a new one
        SwingUtilities.invokeAndWait { call(session, 'strictPlayer'); presenterNow = call(session, 'presenter') }
        for (int t : newIndices) {
            def future = null
            SwingUtilities.invokeAndWait { future = ask.invoke(session, t) }
            def result = future.get(60, TimeUnit.SECONDS)
            def request = field(result, 'request')
            String label = onEventThread(presenterNow, 'label')
            String time = field(snapshotNow(), 'times')[t].toString()
            if (field(result, 'outcome').name() != 'PRESENTED' || field(request, 'timeIndex') != t || field(result, 'coverageMisses') != 0
                    || !label.startsWith("t = ${t + 1} ${time}"))
                throw new IllegalStateException("growing folder: time index ${t} (${time}) was not presented as asked: ${result}; label '${label}'")
            log("growing folder: completed frame of the new ${time}: ${result}; label '${label}'")
        }
        capture('10_grown_folder_completed.png')
        SwingUtilities.invokeAndWait { call(session, 'stopStrictPlayback') }
        if (twoFile) {
            def request = call(call(session, 'presenter'), 'shownRequest')
            def inputs = field(request, 'inputs')
            if (inputs.size() != 2 || !inputs.every { field(field(it, 'entry'), 'time') == field(request, 'time') })
                throw new IllegalStateException('completed frame mixes acquisition files or times')
            log('two-file acquisition: passed; four source slots, missing partners withheld, both files at one completed time')
        }
        log('growing folder: passed; ' + call(store, 'stats'))
    }

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
