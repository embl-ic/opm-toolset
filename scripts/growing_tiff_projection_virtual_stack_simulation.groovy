/*
 * Growing TIFF projection VirtualStack simulation for Fiji.
 *
 * Purpose
 * -------
 * Exercise a TIFF-backed CxT virtual preview independently of live deskewing. The script
 * indexes already-written maxX, maxY and maxZ TIFFs, opens with the first two common time
 * points so Fiji displays separate C and T sliders immediately, and then publishes one more
 * every five seconds. It never copies or changes the source TIFFs.
 *
 * Default source
 * --------------
 *   E:\OPM\3_timelapse_0\result\maxX
 *   E:\OPM\3_timelapse_0\result\maxY
 *   E:\OPM\3_timelapse_0\result\maxZ
 *
 * Requirements
 * ------------
 * Run this in Fiji's Script Editor with language "Groovy" and a current OPM_Toolset JAR
 * installed. The script intentionally reuses de.embl.iclm.FastTiffReader.PlaneReader: each
 * requested channel is one independent Deflate TIFF plane and no complete movie is loaded.
 *
 * Controls
 * --------
 * Start: first two T positions immediately, then one T every 5 seconds; resumes after Stop.
 *         Growth is passive: the displayed C/T position never advances automatically.
 * Stop:  pause without closing or invalidating the virtual views.
 * Reset: stop, close only this script's views, clear cache, and re-index on next Start.
 * Exit:  close this script's views and control window, then finish script execution.
 */

import de.embl.iclm.FastTiffReader

import ij.CompositeImage
import ij.IJ
import ij.ImagePlus
import ij.VirtualStack
import ij.gui.ImageWindow
import ij.gui.StackWindow
import ij.measure.Calibration
import ij.process.ImageProcessor
import ij.process.ShortProcessor

import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.Timer

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.lang.reflect.Field
import java.util.concurrent.CountDownLatch
import java.util.regex.Matcher
import java.util.regex.Pattern

final int SIMULATED_INTERVAL_MS = 5000
final long SHARED_CACHE_BYTES = 128L * 1024L * 1024L
final String DEFAULT_RESULT_FOLDER = 'E:\\OPM\\3_timelapse_0\\result'
final double DEFAULT_PIXEL_SIZE_UM = 0.116d

/** One complete projection TIFF and the metadata needed to read any of its channel planes. */
class ProjectionFrame {
    final File file
    final FastTiffReader.Info info
    final long timeNumber

    ProjectionFrame(File file, long timeNumber) {
        this.file = file
        this.timeNumber = timeNumber
        this.info = FastTiffReader.parse(file)
        if (info.bitsPerSample != 16 || info.samplesPerPixel != 1) {
            throw new IOException('Expected 16-bit grayscale TIFF planes: ' + file)
        }
        if (info.depth() < 1) throw new IOException('No TIFF planes in ' + file)
    }
}

/** Shared byte-bounded cache, so three preview windows do not each reserve a separate cache. */
class ProjectionPlaneCache {
    private final long budget
    private long used = 0L
    private final LinkedHashMap<String, short[]> planes =
            new LinkedHashMap<String, short[]>(16, 0.75f, true)

    ProjectionPlaneCache(long budget) {
        this.budget = Math.max(1L, budget)
    }

    synchronized short[] get(String key) {
        return planes.get(key)
    }

    synchronized void put(String key, short[] pixels) {
        if (pixels == null) return
        short[] previous = planes.put(key, pixels)
        if (previous != null) used -= 2L * previous.length
        used += 2L * pixels.length
        Iterator<Map.Entry<String, short[]>> oldest = planes.entrySet().iterator()
        while (used > budget && planes.size() > 1 && oldest.hasNext()) {
            Map.Entry<String, short[]> entry = oldest.next()
            used -= 2L * entry.value.length
            oldest.remove()
        }
    }

    synchronized long usedBytes() {
        return used
    }

    synchronized int planeCount() {
        return planes.size()
    }

    synchronized void clear() {
        planes.clear()
        used = 0L
    }
}

/**
 * A C-fastest, then T, projection movie backed by one multi-page TIFF per time point.
 *
 * The list grows in place. getProcessor() opens only the selected TIFF and inflates only the
 * requested channel plane. Returned pixels are cloned so an ImageJ command cannot corrupt the
 * cached copy.
 */
class GrowingProjectionTiffStack extends VirtualStack {
    final String projection
    final int width
    final int height
    final int channels
    final ProjectionPlaneCache cache
    private final List<ProjectionFrame> frames = new ArrayList<ProjectionFrame>()
    private final Set<String> reportedReadErrors = new HashSet<String>()

    GrowingProjectionTiffStack(String projection, ProjectionFrame first,
            ProjectionPlaneCache cache) {
        super(first.info.width, first.info.height)
        this.projection = projection
        this.width = first.info.width
        this.height = first.info.height
        this.channels = first.info.depth()
        this.cache = cache
        setBitDepth(16)
        frames.add(first)
    }

    synchronized void validate(ProjectionFrame frame) {
        if (frame.info.width != width || frame.info.height != height ||
                frame.info.depth() != channels) {
            throw new IOException(projection + ' layout changed at ' + frame.file.name +
                    ': got ' + frame.info.width + 'x' + frame.info.height + 'x' +
                    frame.info.depth() + ', expected ' + width + 'x' + height + 'x' + channels)
        }
    }

    synchronized void append(ProjectionFrame frame) {
        validate(frame)
        frames.add(frame)
    }

    synchronized int frameCount() {
        return frames.size()
    }

    @Override synchronized int size() {
        return getSize()
    }

    @Override synchronized int getSize() {
        return channels * frames.size()
    }

    @Override int getBitDepth() {
        return 16
    }

    @Override ImageProcessor getProcessor(int index) {
        ProjectionFrame frame
        int channel
        synchronized (this) {
            if (index < 1 || index > getSize()) {
                throw new IllegalArgumentException('Projection plane out of range: ' + index)
            }
            int zero = index - 1
            channel = zero % channels
            int timepoint = (int) (zero / channels)
            frame = frames.get(timepoint)
        }

        String key = frame.file.canonicalPath.toLowerCase(Locale.ROOT) + '#' + channel
        short[] pixels = cache.get(key)
        if (pixels == null) {
            FastTiffReader.PlaneReader reader = null
            try {
                reader = new FastTiffReader.PlaneReader(frame.file, frame.info)
                pixels = reader.readPixels(channel)
                cache.put(key, pixels)
            } catch (Throwable failure) {
                synchronized (reportedReadErrors) {
                    if (reportedReadErrors.add(key)) {
                        IJ.log('TIFF virtual preview could not read ' + frame.file +
                                ', channel ' + (channel + 1) + ': ' + failure.message)
                    }
                }
                return new ShortProcessor(width, height)
            } finally {
                if (reader != null) {
                    try { reader.close() } catch (IOException ignored) { }
                }
            }
        }
        return new ShortProcessor(width, height, Arrays.copyOf(pixels, pixels.length), null)
    }

    @Override String getSliceLabel(int index) {
        ProjectionFrame frame
        int channel
        synchronized (this) {
            if (index < 1 || index > getSize()) return null
            int zero = index - 1
            channel = zero % channels
            frame = frames.get((int) (zero / channels))
        }
        return projection + ', channel ' + (channel + 1) + ', Time' +
                String.format(Locale.ROOT, '%06d', frame.timeNumber)
    }

    @Override String getFileName(int index) {
        synchronized (this) {
            if (index < 1 || index > getSize()) return null
            int timepoint = (int) ((index - 1) / channels)
            return frames.get(timepoint).file.name
        }
    }
}

/** The control window and the three growing virtual images. All mutations occur on the EDT. */
class ProjectionPreviewSimulation {
    static final List<String> PROJECTIONS = ['maxX', 'maxY', 'maxZ']
    static final Pattern TIME_PATTERN = Pattern.compile('(?i)_Time(\\d+)')

    final int intervalMs
    final long cacheBytes
    final double pixelSizeUm
    final CountDownLatch finished = new CountDownLatch(1)
    final ProjectionPlaneCache cache

    JFrame frame
    JTextField rootField
    JButton browseButton
    JButton startButton
    JButton stopButton
    JButton resetButton
    JButton exitButton
    JTextArea logArea
    JLabel statusLabel
    Timer timer

    final Map<String, Map<Long, File>> indexed = new LinkedHashMap<String, Map<Long, File>>()
    final Map<String, GrowingProjectionTiffStack> stacks =
            new LinkedHashMap<String, GrowingProjectionTiffStack>()
    final Map<String, ImagePlus> images = new LinkedHashMap<String, ImagePlus>()
    List<Long> commonTimes = new ArrayList<Long>()
    int nextTimepoint = 0
    String indexedRoot = null
    boolean exiting = false

    ProjectionPreviewSimulation(String defaultRoot, int intervalMs, long cacheBytes,
            double pixelSizeUm) {
        this.intervalMs = intervalMs
        this.cacheBytes = cacheBytes
        this.pixelSizeUm = pixelSizeUm
        this.cache = new ProjectionPlaneCache(cacheBytes)
        buildUi(defaultRoot)
    }

    void buildUi(String defaultRoot) {
        frame = new JFrame('Growing TIFF projection preview simulation')
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.layout = new BorderLayout(6, 6)

        JPanel settings = new JPanel(new GridBagLayout())
        settings.border = BorderFactory.createEmptyBorder(8, 8, 0, 8)
        GridBagConstraints c = new GridBagConstraints()
        c.insets = new Insets(2, 3, 2, 3)
        c.gridy = 0
        c.gridx = 0
        c.anchor = GridBagConstraints.WEST
        settings.add(new JLabel('Result folder:'), c)
        rootField = new JTextField(defaultRoot, 48)
        c.gridx = 1
        c.weightx = 1.0d
        c.fill = GridBagConstraints.HORIZONTAL
        settings.add(rootField, c)
        browseButton = new JButton('Browse...')
        c.gridx = 2
        c.weightx = 0.0d
        c.fill = GridBagConstraints.NONE
        settings.add(browseButton, c)

        c.gridy = 1
        c.gridx = 0
        c.gridwidth = 3
        settings.add(new JLabel(
                'Simulation: seed T=1..2 immediately, then add one T every 5 seconds.'), c)

        c.gridy = 2
        settings.add(new JLabel(
                'Passive preview: new frames extend the T slider without changing the displayed plane.'), c)
        frame.add(settings, BorderLayout.NORTH)

        logArea = new JTextArea(12, 82)
        logArea.editable = false
        logArea.lineWrap = false
        logArea.text = 'Press Start to index maxX, maxY and maxZ. Source TIFFs are never modified.\n'
        JScrollPane scroll = new JScrollPane(logArea)
        scroll.border = BorderFactory.createTitledBorder('Simulation log')
        frame.add(scroll, BorderLayout.CENTER)

        JPanel bottom = new JPanel(new BorderLayout())
        bottom.border = BorderFactory.createEmptyBorder(0, 8, 8, 8)
        statusLabel = new JLabel('Stopped')
        bottom.add(statusLabel, BorderLayout.NORTH)
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT))
        startButton = new JButton('Start')
        stopButton = new JButton('Stop')
        resetButton = new JButton('Reset')
        exitButton = new JButton('Exit')
        stopButton.enabled = false
        buttons.add(startButton)
        buttons.add(stopButton)
        buttons.add(resetButton)
        buttons.add(exitButton)
        bottom.add(buttons, BorderLayout.SOUTH)
        frame.add(bottom, BorderLayout.SOUTH)

        timer = new Timer(intervalMs, { publishNext() } as java.awt.event.ActionListener)
        timer.repeats = true

        browseButton.addActionListener({ chooseRoot() } as java.awt.event.ActionListener)
        startButton.addActionListener({ startSimulation() } as java.awt.event.ActionListener)
        stopButton.addActionListener({ stopSimulation('Paused') } as java.awt.event.ActionListener)
        resetButton.addActionListener({ resetSimulation() } as java.awt.event.ActionListener)
        exitButton.addActionListener({ exitSimulation() } as java.awt.event.ActionListener)
        final ProjectionPreviewSimulation owner = this
        frame.addWindowListener(new WindowAdapter() {
            @Override void windowClosing(WindowEvent event) {
                owner.exitSimulation()
            }
        })

        frame.pack()
        frame.minimumSize = new Dimension(760, 390)
        frame.setLocationByPlatform(true)
        frame.visible = true
    }

    void chooseRoot() {
        JFileChooser chooser = new JFileChooser(new File(rootField.text.trim()))
        chooser.dialogTitle = 'Select folder containing maxX, maxY and maxZ'
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            rootField.text = chooser.selectedFile.absolutePath
        }
    }

    void startSimulation() {
        try {
            String root = new File(rootField.text.trim()).canonicalPath
            if (indexedRoot == null) indexRoot(new File(root))
            else if (!indexedRoot.equalsIgnoreCase(root)) {
                throw new IllegalStateException('Press Reset before changing the result folder.')
            }
            if (commonTimes.isEmpty()) throw new IllegalStateException('No common time points found.')
            if (nextTimepoint >= commonTimes.size()) {
                updateControls(false, 'Complete: ' + nextTimepoint + ' time points')
                return
            }
            updateControls(true, 'Running')
            if (nextTimepoint == 0 && commonTimes.size() >= 2) publishInitialPair()
            else if (nextTimepoint == 0) publishNext()
            if (nextTimepoint < commonTimes.size()) timer.start()
        } catch (Throwable failure) {
            stopSimulation('Stopped after error')
            showError(failure)
        }
    }

    void stopSimulation(String state) {
        timer.stop()
        updateControls(false, state + ': ' + nextTimepoint + '/' + commonTimes.size())
        appendLog(state + ' at ' + nextTimepoint + '/' + commonTimes.size() + '.')
    }

    void resetSimulation() {
        timer.stop()
        closeImages()
        cache.clear()
        indexed.clear()
        stacks.clear()
        commonTimes = new ArrayList<Long>()
        nextTimepoint = 0
        indexedRoot = null
        rootField.enabled = true
        browseButton.enabled = true
        updateControls(false, 'Reset; press Start to re-index')
        appendLog('Reset. Virtual views and cache were closed; source files were untouched.')
    }

    void exitSimulation() {
        if (exiting) return
        exiting = true
        timer.stop()
        closeImages()
        cache.clear()
        frame.dispose()
        finished.countDown()
    }

    void indexRoot(File root) {
        if (!root.isDirectory()) throw new IOException('Result folder does not exist: ' + root)
        indexed.clear()
        Set<Long> common = null
        for (String projection : PROJECTIONS) {
            File folder = new File(root, projection)
            if (!folder.isDirectory()) throw new IOException('Missing projection folder: ' + folder)
            Map<Long, File> byTime = new TreeMap<Long, File>()
            File[] files = folder.listFiles()
            if (files != null) for (File file : files) {
                if (!file.isFile() || !(file.name.toLowerCase(Locale.ROOT).endsWith('.tif') ||
                        file.name.toLowerCase(Locale.ROOT).endsWith('.tiff'))) continue
                Long time = timeNumber(file)
                if (time == null) continue
                File duplicate = byTime.put(time, file)
                if (duplicate != null) throw new IOException('Two ' + projection +
                        ' TIFFs claim Time' + time + ': ' + duplicate.name + ', ' + file.name)
            }
            if (byTime.isEmpty()) throw new IOException('No _TimeNNNN TIFFs in ' + folder)
            indexed.put(projection, byTime)
            if (common == null) common = new TreeSet<Long>(byTime.keySet())
            else common.retainAll(byTime.keySet())
            appendLog('Indexed ' + byTime.size() + ' ' + projection + ' TIFFs in ' + folder)
        }
        commonTimes = common == null ? new ArrayList<Long>() : new ArrayList<Long>(common)
        indexedRoot = root.canonicalPath
        rootField.enabled = false
        browseButton.enabled = false
        appendLog('Common simulation timeline: ' + commonTimes.size() + ' time points.')
    }

    /** Open with two real frames, so Fiji creates both C and T selectors from the outset. */
    void publishInitialPair() {
        long firstTime = commonTimes.get(0)
        long secondTime = commonTimes.get(1)
        for (String projection : PROJECTIONS) {
            ProjectionFrame first = new ProjectionFrame(
                    indexed.get(projection).get(firstTime), firstTime)
            ProjectionFrame second = new ProjectionFrame(
                    indexed.get(projection).get(secondTime), secondTime)
            GrowingProjectionTiffStack stack = new GrowingProjectionTiffStack(
                    projection, first, cache)
            stack.append(second)
            stacks.put(projection, stack)
            images.put(projection, openImage(projection, stack))
        }
        nextTimepoint = 2
        appendLog('Seeded Time' + String.format(Locale.ROOT, '%06d', firstTime) +
                ' and Time' + String.format(Locale.ROOT, '%06d', secondTime) +
                ' as C=' + stacks.get('maxZ').channels + ', Z=1, T=2. Current view stays at T=1.')
        statusLabel.text = 'Running hyperstacks: C=' + stacks.get('maxZ').channels +
                ', Z=1, T=2/' + commonTimes.size() + '; shared cache ' + cacheSummary()
    }

    void publishNext() {
        if (nextTimepoint >= commonTimes.size()) {
            timer.stop()
            updateControls(false, 'Complete: ' + nextTimepoint + ' time points')
            appendLog('Complete.')
            return
        }
        try {
            long time = commonTimes.get(nextTimepoint)
            Map<String, ProjectionFrame> prepared = new LinkedHashMap<String, ProjectionFrame>()
            for (String projection : PROJECTIONS) {
                prepared.put(projection, new ProjectionFrame(indexed.get(projection).get(time), time))
            }

            if (stacks.isEmpty()) {
                for (String projection : PROJECTIONS) {
                    GrowingProjectionTiffStack stack = new GrowingProjectionTiffStack(
                            projection, prepared.get(projection), cache)
                    stacks.put(projection, stack)
                    images.put(projection, openImage(projection, stack))
                }
            } else {
                // Validate all axes before changing any one of them, keeping their T axes equal.
                for (String projection : PROJECTIONS) {
                    stacks.get(projection).validate(prepared.get(projection))
                }
                for (String projection : PROJECTIONS) {
                    GrowingProjectionTiffStack stack = stacks.get(projection)
                    growImage(images.get(projection), stack, prepared.get(projection))
                }
            }

            nextTimepoint++
            appendLog('Published Time' + String.format(Locale.ROOT, '%06d', time) +
                    ' as C=' + stacks.get('maxZ').channels + ', Z=1, T=' + nextTimepoint +
                    '; cache ' + cacheSummary() + '.')
            statusLabel.text = 'Running hyperstacks: C=' + stacks.get('maxZ').channels +
                    ', Z=1, T=' + nextTimepoint + '/' + commonTimes.size() +
                    '; shared cache ' + cacheSummary()
            if (nextTimepoint >= commonTimes.size()) {
                timer.stop()
                updateControls(false, 'Complete: ' + nextTimepoint + ' time points')
                appendLog('Complete.')
            }
        } catch (Throwable failure) {
            stopSimulation('Stopped after error')
            showError(failure)
        }
    }

    ImagePlus openImage(String projection, GrowingProjectionTiffStack stack) {
        ImagePlus base = new ImagePlus('TIFF virtual ' + projection, stack)
        base.setDimensions(stack.channels, 1, stack.frameCount())
        base.setOpenAsHyperStack(stack.channels > 1)
        ImagePlus image = stack.channels > 1 ?
                new CompositeImage(base, CompositeImage.COMPOSITE) : base
        image.title = 'SIMULATED LIVE TIFF ' + projection
        Calibration calibration = new Calibration()
        calibration.pixelWidth = pixelSizeUm
        calibration.pixelHeight = pixelSizeUm
        calibration.frameInterval = intervalMs / 1000.0d
        calibration.setUnit('micron')
        calibration.setTimeUnit('second')
        image.calibration = calibration
        image.changes = false
        image.show()
        if (image instanceof CompositeImage) ((CompositeImage) image).resetDisplayRanges()
        assertHyperstackDimensions(image, stack.channels, 1, stack.frameCount())
        appendLog('Opened ' + projection + ' hyperstack: C=' + stack.channels +
                ', Z=1, T=' + stack.frameCount() + ', virtual=' + stack.isVirtual() + '.')
        return image
    }

    /**
     * Publish one frame without giving ImageJ a chance to reinterpret it as extra channels.
     *
     * ImagePlus.verifyDimensions() has a special case for C>1,Z=1,T=1: if the backing stack
     * becomes longer before nFrames changes, it silently sets C=stackSize. Set the three raw
     * dimension fields first, append immediately on the same EDT turn, and only then update
     * the displayed StackWindow. At the 1->2 transition Fiji has no T selector yet, so it is
     * rebuilt once; later transitions stretch the existing selector in place.
     */
    void growImage(ImagePlus image, GrowingProjectionTiffStack stack, ProjectionFrame frame) {
        if (image == null) return
        int oldFrames = stack.frameCount()
        int frames = oldFrames + 1
        setImageDimensionsRaw(image, stack.channels, 1, frames)
        stack.append(frame)
        if (image.window == null) {
            image.setStack(stack, stack.channels, 1, frames)
        } else if (!extendTimeAxisInPlace(image, frames)) {
            // At T=1 Fiji creates no time selector. The T=2 transition may rebuild once;
            // from then on the selector exists and subsequent growth stays in place.
            image.setStack(stack, stack.channels, 1, frames)
        }
        image.setOpenAsHyperStack(stack.channels > 1 || frames > 1)
        // Deliberately no setPosition(), setT(), setSlice() or updateAndDraw(): publishing a
        // frame changes only the T extent. The user decides when to read it by dragging T.
        assertHyperstackDimensions(image, stack.channels, 1, frames)
    }

    /** Set C/Z/T without calling verifyDimensions while the stack is between old and new T. */
    static void setImageDimensionsRaw(ImagePlus image, int channels, int slices, int frames) {
        Field imageChannels = ImagePlus.class.getDeclaredField('nChannels')
        Field imageSlices = ImagePlus.class.getDeclaredField('nSlices')
        Field imageFrames = ImagePlus.class.getDeclaredField('nFrames')
        imageChannels.accessible = true
        imageSlices.accessible = true
        imageFrames.accessible = true
        imageChannels.setInt(image, channels)
        imageSlices.setInt(image, slices)
        imageFrames.setInt(image, frames)
    }

    static void assertHyperstackDimensions(
            ImagePlus image, int channels, int slices, int frames) {
        int[] dimensions = image.getDimensions() // X,Y,C,Z,T; safe after the atomic publication
        if (dimensions[2] != channels || dimensions[3] != slices || dimensions[4] != frames ||
                image.getStackSize() != channels * slices * frames ||
                !image.getStack().isVirtual()) {
            throw new IllegalStateException('Fiji dimension mismatch: got C=' + dimensions[2] +
                    ', Z=' + dimensions[3] + ', T=' + dimensions[4] + ', stack=' +
                    image.getStackSize() + '; expected C=' + channels + ', Z=' + slices +
                    ', T=' + frames + ' and a virtual stack.')
        }
    }

    /** Same targeted mechanism used by the OME-Zarr viewer to avoid rebuilding StackWindow. */
    static boolean extendTimeAxisInPlace(ImagePlus image, int frames) {
        ImageWindow window = image.window
        if (!(window instanceof StackWindow)) return false
        try {
            Field imageFrames = ImagePlus.class.getDeclaredField('nFrames')
            imageFrames.accessible = true
            Field windowFrames = StackWindow.class.getDeclaredField('nFrames')
            windowFrames.accessible = true
            Field selectorField = StackWindow.class.getDeclaredField('tSelector')
            selectorField.accessible = true
            Object selector = selectorField.get(window)
            if (selector == null) return false

            imageFrames.setInt(image, frames)
            windowFrames.setInt(window, frames)
            selector.class.getMethod('setMaximum', Integer.TYPE).invoke(selector, frames + 1)
            try {
                selector.class.getMethod('setBlockIncrement', Integer.TYPE).invoke(
                        selector, Math.max(1, (int) (frames / 10)))
            } catch (Throwable ignored) { }
            ((java.awt.Component) selector).repaint()
            window.repaint()
            return true
        } catch (Throwable unsupported) {
            return false
        }
    }

    void closeImages() {
        for (ImagePlus image : images.values()) {
            if (image == null) continue
            image.changes = false
            image.close()
            image.flush()
        }
        images.clear()
    }

    void updateControls(boolean running, String status) {
        startButton.enabled = !running && nextTimepoint < commonTimes.size()
        stopButton.enabled = running
        resetButton.enabled = true
        statusLabel.text = status
    }

    String cacheSummary() {
        return String.format(Locale.ROOT, '%.1f/%.0f MiB (%d planes)',
                cache.usedBytes() / 1048576.0d, cacheBytes / 1048576.0d, cache.planeCount())
    }

    void appendLog(String message) {
        logArea.append(message + '\n')
        logArea.caretPosition = logArea.document.length
        println('[TIFF virtual preview simulation] ' + message)
    }

    void showError(Throwable failure) {
        String message = failure.message == null ? failure.class.simpleName : failure.message
        appendLog('ERROR: ' + message)
        IJ.log('TIFF virtual preview simulation: ' + message)
        IJ.showMessage('Growing TIFF projection preview', message)
    }

    static Long timeNumber(File file) {
        Matcher matcher = TIME_PATTERN.matcher(file.name)
        return matcher.find() ? Long.valueOf(matcher.group(1)) : null
    }
}

ProjectionPreviewSimulation simulation
if (SwingUtilities.isEventDispatchThread()) {
    simulation = new ProjectionPreviewSimulation(DEFAULT_RESULT_FOLDER,
            SIMULATED_INTERVAL_MS, SHARED_CACHE_BYTES, DEFAULT_PIXEL_SIZE_UM)
} else {
    final ProjectionPreviewSimulation[] holder = new ProjectionPreviewSimulation[1]
    SwingUtilities.invokeAndWait({
        holder[0] = new ProjectionPreviewSimulation(DEFAULT_RESULT_FOLDER,
                SIMULATED_INTERVAL_MS, SHARED_CACHE_BYTES, DEFAULT_PIXEL_SIZE_UM)
    } as Runnable)
    simulation = holder[0]
    // Keep the Script Editor run alive until Exit, while Swing continues on its own thread.
    simulation.finished.await()
}
