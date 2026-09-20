/*
 * Growing deskewed-volume TIFF VirtualStack simulation for Fiji.
 *
 * Purpose
 * -------
 * Exercise a TIFF-backed X,Y,C,Z,T deskewed-volume preview independently of live
 * processing. The script indexes existing deskew TIFFs, opens with the first two
 * timepoints so Fiji creates separate C, Z and T controls immediately, and publishes
 * one additional timepoint every five seconds. Source TIFFs are never modified.
 *
 * Default source
 * --------------
 *   E:\OPM\3_timelapse_0\result\deskew
 *
 * Requirements
 * ------------
 * Run in Fiji's Script Editor with language "Groovy" and a current OPM_Toolset JAR
 * installed. Each requested C/Z plane is inflated on demand with
 * de.embl.iclm.FastTiffReader.PlaneReader; the complete 5D image is never loaded.
 *
 * Controls
 * --------
 * Start: seed T=1..2 immediately, then add one T every 5 seconds; resumes after Stop.
 *        Growth is passive and never changes the displayed C, Z or T position.
 * Stop:  pause without closing or invalidating the virtual volume.
 * Reset: stop, close this script's volume, clear cache, and re-index on next Start.
 * Materialise ROI: use the active ROI plus requested C/Z/T ranges to build an optimized crop.
 * Exit:  close the volume and control window, then finish script execution.
 */

import de.embl.iclm.FastTiffReader
import de.embl.iclm.Shutdown

import ij.CompositeImage
import ij.IJ
import ij.ImagePlus
import ij.ImageStack
import ij.VirtualStack
import ij.WindowManager
import ij.gui.GenericDialog
import ij.gui.ImageWindow
import ij.gui.StackWindow
import ij.gui.YesNoCancelDialog
import ij.io.FileInfo
import ij.io.TiffDecoder
import ij.measure.Calibration
import ij.process.ImageProcessor
import ij.process.LUT
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
import java.awt.Rectangle
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.lang.reflect.Field
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Matcher
import java.util.regex.Pattern

final int SIMULATED_INTERVAL_MS = 500
final long SHARED_CACHE_BYTES = 256L * 1024L * 1024L
final String DEFAULT_DESKEW_FOLDER = 'E:\\OPM\\3_timelapse_0\\result\\deskew'
final double DEFAULT_VOXEL_SIZE_UM = 0.116d

/** C/Z layout and calibration stored in the first deskew TIFF. */
class DeskewVolumeLayout {
    final int width
    final int height
    final int channels
    final int slices
    final double pixelWidth
    final double pixelHeight
    final double pixelDepth
    final double frameInterval
    final String unit

    DeskewVolumeLayout(int width, int height, int channels, int slices,
            double pixelWidth, double pixelHeight, double pixelDepth,
            double frameInterval, String unit) {
        this.width = width
        this.height = height
        this.channels = channels
        this.slices = slices
        this.pixelWidth = pixelWidth
        this.pixelHeight = pixelHeight
        this.pixelDepth = pixelDepth
        this.frameInterval = frameInterval
        this.unit = unit
    }

    /** Read ImageJ's description from the first TIFF; fall back to one channel if absent. */
    static DeskewVolumeLayout read(File file, FastTiffReader.Info info,
            double fallbackVoxelSize, double fallbackFrameInterval) {
        FileInfo[] fileInfo = new TiffDecoder(
                file.parent + File.separator, file.name).getTiffInfo()
        String description = fileInfo != null && fileInfo.length > 0 ?
                fileInfo[0].description : null

        int channels = positiveInteger(description, 'channels', 1)
        int frames = positiveInteger(description, 'frames', 1)
        int slices = positiveInteger(description, 'slices', -1)
        if (frames != 1) {
            throw new IOException('Expected one timepoint per deskew TIFF, but metadata says ' +
                    'frames=' + frames + ': ' + file)
        }
        if (slices < 1) {
            if (info.depth() % channels != 0) {
                throw new IOException('Cannot infer Z slices from ' + info.depth() +
                        ' planes and C=' + channels + ': ' + file)
            }
            slices = (int) (info.depth() / channels)
        }
        if (channels * slices != info.depth()) {
            throw new IOException('TIFF metadata declares C=' + channels + ', Z=' + slices +
                    ', T=1, but the file contains ' + info.depth() + ' planes: ' + file)
        }

        String unit = textValue(description, 'unit', 'micron')
        double spacing = positiveDouble(description, 'spacing', fallbackVoxelSize)
        double interval = positiveDouble(description, 'finterval', fallbackFrameInterval)

        // These OPM TIFFs store Z spacing in the ImageJ description but may omit TIFF X/Y
        // resolution tags. Use the known deskew voxel size for missing X/Y calibration.
        double pixelWidth = fallbackVoxelSize
        double pixelHeight = fallbackVoxelSize
        if (fileInfo != null && fileInfo.length > 0 && fileInfo[0].unit != null) {
            if (fileInfo[0].pixelWidth > 0.0d) pixelWidth = fileInfo[0].pixelWidth
            if (fileInfo[0].pixelHeight > 0.0d) pixelHeight = fileInfo[0].pixelHeight
        }
        return new DeskewVolumeLayout(info.width, info.height, channels, slices,
                pixelWidth, pixelHeight, spacing, interval, unit)
    }

    private static int positiveInteger(String description, String name, int fallback) {
        String value = metadataValue(description, name)
        if (value == null) return fallback
        try {
            int parsed = Integer.parseInt(value)
            return parsed > 0 ? parsed : fallback
        } catch (NumberFormatException ignored) {
            return fallback
        }
    }

    private static double positiveDouble(String description, String name, double fallback) {
        String value = metadataValue(description, name)
        if (value == null) return fallback
        try {
            double parsed = Double.parseDouble(value)
            return parsed > 0.0d && !Double.isInfinite(parsed) && !Double.isNaN(parsed) ?
                    parsed : fallback
        } catch (NumberFormatException ignored) {
            return fallback
        }
    }

    private static String textValue(String description, String name, String fallback) {
        String value = metadataValue(description, name)
        return value == null || value.trim().isEmpty() ? fallback : value.trim()
    }

    private static String metadataValue(String description, String name) {
        if (description == null) return null
        Pattern pattern = Pattern.compile('(?mi)^' + Pattern.quote(name) + '=([^\\r\\n]+)')
        Matcher matcher = pattern.matcher(description)
        return matcher.find() ? matcher.group(1).trim() : null
    }
}

/** One complete deskew TIFF and the information needed to read any of its planes. */
class DeskewTimepoint {
    final File file
    final FastTiffReader.Info info
    final long timeNumber

    DeskewTimepoint(File file, long timeNumber) {
        this.file = file
        this.timeNumber = timeNumber
        this.info = FastTiffReader.parse(file)
        if (info.bitsPerSample != 16 || info.samplesPerPixel != 1) {
            throw new IOException('Expected 16-bit grayscale TIFF planes: ' + file)
        }
        if (info.depth() < 1) throw new IOException('No TIFF planes in ' + file)
    }
}

/** Shared byte-bounded LRU cache of decompressed C/Z planes. */
class DeskewPlaneCache {
    private final long budget
    private long used = 0L
    private final LinkedHashMap<String, short[]> planes =
            new LinkedHashMap<String, short[]>(32, 0.75f, true)

    DeskewPlaneCache(long budget) {
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
 * Growing C-fastest, then Z, then T virtual volume.
 *
 * Each source TIFF contains one timepoint in ImageJ stack order. A request for stack index
 * i maps to file plane ((Z-1)*C + C-1) in exactly one timepoint TIFF.
 */
class GrowingDeskewVolumeStack extends VirtualStack {
    final DeskewVolumeLayout layout
    final DeskewPlaneCache cache
    private final List<DeskewTimepoint> timepoints = new ArrayList<DeskewTimepoint>()
    private final Set<String> reportedReadErrors = new HashSet<String>()

    GrowingDeskewVolumeStack(DeskewTimepoint first, DeskewVolumeLayout layout,
            DeskewPlaneCache cache) {
        super(layout.width, layout.height)
        this.layout = layout
        this.cache = cache
        validate(first)
        setBitDepth(16)
        timepoints.add(first)
    }

    synchronized void validate(DeskewTimepoint timepoint) {
        FastTiffReader.Info info = timepoint.info
        if (info.width != layout.width || info.height != layout.height ||
                info.depth() != layout.channels * layout.slices) {
            throw new IOException('Deskew layout changed at ' + timepoint.file.name +
                    ': got ' + info.width + 'x' + info.height + ' with ' + info.depth() +
                    ' planes; expected ' + layout.width + 'x' + layout.height + ', C=' +
                    layout.channels + ', Z=' + layout.slices + '.')
        }
    }

    synchronized void append(DeskewTimepoint timepoint) {
        validate(timepoint)
        timepoints.add(timepoint)
    }

    synchronized int frameCount() {
        return timepoints.size()
    }

    synchronized List<DeskewTimepoint> snapshotTimepoints() {
        return new ArrayList<DeskewTimepoint>(timepoints)
    }

    @Override synchronized int size() {
        return getSize()
    }

    @Override synchronized int getSize() {
        return layout.channels * layout.slices * timepoints.size()
    }

    @Override int getBitDepth() {
        return 16
    }

    @Override ImageProcessor getProcessor(int index) {
        DeskewTimepoint timepoint
        int channel
        int z
        int filePlane
        synchronized (this) {
            if (index < 1 || index > getSize()) {
                throw new IllegalArgumentException('Deskew plane out of range: ' + index)
            }
            int zero = index - 1
            int planesPerTimepoint = layout.channels * layout.slices
            int timeIndex = (int) (zero / planesPerTimepoint)
            filePlane = zero % planesPerTimepoint
            channel = filePlane % layout.channels
            z = (int) (filePlane / layout.channels)
            timepoint = timepoints.get(timeIndex)
        }

        String key = timepoint.file.canonicalPath.toLowerCase(Locale.ROOT) + '#' + filePlane
        short[] pixels = cache.get(key)
        if (pixels == null) {
            FastTiffReader.PlaneReader reader = null
            try {
                reader = new FastTiffReader.PlaneReader(timepoint.file, timepoint.info)
                pixels = reader.readPixels(filePlane)
                cache.put(key, pixels)
            } catch (Throwable failure) {
                synchronized (reportedReadErrors) {
                    if (reportedReadErrors.add(key)) {
                        IJ.log('Deskew virtual preview could not read ' + timepoint.file +
                                ', C=' + (channel + 1) + ', Z=' + (z + 1) + ': ' +
                                failure.message)
                    }
                }
                return new ShortProcessor(layout.width, layout.height)
            } finally {
                if (reader != null) {
                    try { reader.close() } catch (IOException ignored) { }
                }
            }
        }
        return new ShortProcessor(layout.width, layout.height,
                Arrays.copyOf(pixels, pixels.length), null)
    }

    @Override String getSliceLabel(int index) {
        DeskewTimepoint timepoint
        int channel
        int z
        synchronized (this) {
            if (index < 1 || index > getSize()) return null
            int zero = index - 1
            int planesPerTimepoint = layout.channels * layout.slices
            timepoint = timepoints.get((int) (zero / planesPerTimepoint))
            int filePlane = zero % planesPerTimepoint
            channel = filePlane % layout.channels
            z = (int) (filePlane / layout.channels)
        }
        return 'channel ' + (channel + 1) + ', Z=' + (z + 1) + '/' + layout.slices +
                ', Time' + String.format(Locale.ROOT, '%06d', timepoint.timeNumber)
    }

    @Override String getFileName(int index) {
        synchronized (this) {
            if (index < 1 || index > getSize()) return null
            int planesPerTimepoint = layout.channels * layout.slices
            int timeIndex = (int) ((index - 1) / planesPerTimepoint)
            return timepoints.get(timeIndex).file.name
        }
    }
}

/** Control window and one growing 5D deskew volume. All mutations occur on the EDT. */
class DeskewVolumePreviewSimulation {
    static final Pattern TIME_PATTERN = Pattern.compile('(?i)_Time(\\d+)')

    final int intervalMs
    final long cacheBytes
    final double fallbackVoxelSize
    final CountDownLatch finished = new CountDownLatch(1)
    final DeskewPlaneCache cache

    JFrame frame
    JTextField folderField
    JButton browseButton
    JButton startButton
    JButton stopButton
    JButton resetButton
    JButton materializeButton
    JButton exitButton
    JTextArea logArea
    JLabel statusLabel
    Timer timer

    final Map<Long, File> indexed = new TreeMap<Long, File>()
    List<Long> timeline = new ArrayList<Long>()
    GrowingDeskewVolumeStack stack
    ImagePlus image
    DeskewVolumeLayout layout
    int nextTimepoint = 0
    String indexedFolder = null
    boolean exiting = false
    volatile boolean materializing = false
    volatile int materializationToken = 0
    volatile Thread materializationWorker
    volatile ExecutorService materializationPool

    DeskewVolumePreviewSimulation(String defaultFolder, int intervalMs, long cacheBytes,
            double fallbackVoxelSize) {
        this.intervalMs = intervalMs
        this.cacheBytes = cacheBytes
        this.fallbackVoxelSize = fallbackVoxelSize
        this.cache = new DeskewPlaneCache(cacheBytes)
        buildUi(defaultFolder)
    }

    void buildUi(String defaultFolder) {
        frame = new JFrame('Growing deskewed-volume TIFF preview simulation')
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.layout = new BorderLayout(6, 6)

        JPanel settings = new JPanel(new GridBagLayout())
        settings.border = BorderFactory.createEmptyBorder(8, 8, 0, 8)
        GridBagConstraints c = new GridBagConstraints()
        c.insets = new Insets(2, 3, 2, 3)
        c.gridy = 0
        c.gridx = 0
        c.anchor = GridBagConstraints.WEST
        settings.add(new JLabel('Deskew TIFF folder:'), c)
        folderField = new JTextField(defaultFolder, 48)
        c.gridx = 1
        c.weightx = 1.0d
        c.fill = GridBagConstraints.HORIZONTAL
        settings.add(folderField, c)
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
                'Passive 5D preview: growth never changes the displayed C, Z or T.'), c)
        frame.add(settings, BorderLayout.NORTH)

        logArea = new JTextArea(12, 84)
        logArea.editable = false
        logArea.lineWrap = false
        logArea.text = 'Press Start to index deskew TIFFs. Source files are never modified.\n'
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
        materializeButton = new JButton('Materialise ROI...')
        exitButton = new JButton('Exit')
        stopButton.enabled = false
        materializeButton.enabled = false
        buttons.add(startButton)
        buttons.add(stopButton)
        buttons.add(resetButton)
        buttons.add(materializeButton)
        buttons.add(exitButton)
        bottom.add(buttons, BorderLayout.SOUTH)
        frame.add(bottom, BorderLayout.SOUTH)

        timer = new Timer(intervalMs, { publishNext() } as java.awt.event.ActionListener)
        timer.repeats = true

        browseButton.addActionListener({ chooseFolder() } as java.awt.event.ActionListener)
        startButton.addActionListener({ startSimulation() } as java.awt.event.ActionListener)
        stopButton.addActionListener({ stopSimulation('Paused') } as java.awt.event.ActionListener)
        resetButton.addActionListener({ resetSimulation() } as java.awt.event.ActionListener)
        materializeButton.addActionListener(
                { materializeActiveRoi() } as java.awt.event.ActionListener)
        exitButton.addActionListener({ exitSimulation() } as java.awt.event.ActionListener)
        final DeskewVolumePreviewSimulation owner = this
        frame.addWindowListener(new WindowAdapter() {
            @Override void windowClosing(WindowEvent event) {
                owner.exitSimulation()
            }
        })

        frame.pack()
        frame.minimumSize = new Dimension(780, 390)
        frame.setLocationByPlatform(true)
        frame.visible = true
    }

    void chooseFolder() {
        JFileChooser chooser = new JFileChooser(new File(folderField.text.trim()))
        chooser.dialogTitle = 'Select folder containing deskew TIFFs'
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            folderField.text = chooser.selectedFile.absolutePath
        }
    }

    void startSimulation() {
        try {
            String folder = new File(folderField.text.trim()).canonicalPath
            if (indexedFolder == null) indexFolder(new File(folder))
            else if (!indexedFolder.equalsIgnoreCase(folder)) {
                throw new IllegalStateException('Press Reset before changing the deskew folder.')
            }
            if (timeline.isEmpty()) throw new IllegalStateException('No timepoints found.')
            if (nextTimepoint >= timeline.size()) {
                updateControls(false, 'Complete: ' + nextTimepoint + ' timepoints')
                return
            }
            updateControls(true, 'Running')
            if (nextTimepoint == 0 && timeline.size() >= 2) publishInitialPair()
            else if (nextTimepoint == 0) publishNext()
            if (nextTimepoint < timeline.size()) timer.start()
        } catch (Throwable failure) {
            stopSimulation('Stopped after error')
            showError(failure)
        }
    }

    void stopSimulation(String state) {
        timer.stop()
        updateControls(false, state + ': ' + nextTimepoint + '/' + timeline.size())
        appendLog(state + ' at ' + nextTimepoint + '/' + timeline.size() + '.')
    }

    void resetSimulation() {
        timer.stop()
        cancelMaterialization()
        closeImage()
        cache.clear()
        indexed.clear()
        timeline = new ArrayList<Long>()
        stack = null
        layout = null
        nextTimepoint = 0
        indexedFolder = null
        folderField.enabled = true
        browseButton.enabled = true
        updateControls(false, 'Reset; press Start to re-index')
        appendLog('Reset. Virtual volume and cache were closed; source files were untouched.')
    }

    void exitSimulation() {
        if (exiting) return
        exiting = true
        timer.stop()
        cancelMaterialization()
        closeImage()
        cache.clear()
        frame.dispose()
        finished.countDown()
    }

    void indexFolder(File folder) {
        if (!folder.isDirectory()) throw new IOException('Deskew folder does not exist: ' + folder)
        indexed.clear()
        File[] files = folder.listFiles()
        if (files != null) for (File file : files) {
            if (!file.isFile() || !(file.name.toLowerCase(Locale.ROOT).endsWith('.tif') ||
                    file.name.toLowerCase(Locale.ROOT).endsWith('.tiff'))) continue
            Long time = timeNumber(file)
            if (time == null) continue
            File duplicate = indexed.put(time, file)
            if (duplicate != null) throw new IOException('Two deskew TIFFs claim Time' + time +
                    ': ' + duplicate.name + ', ' + file.name)
        }
        if (indexed.isEmpty()) throw new IOException('No _TimeNNNN deskew TIFFs in ' + folder)
        timeline = new ArrayList<Long>(indexed.keySet())
        indexedFolder = folder.canonicalPath
        folderField.enabled = false
        browseButton.enabled = false
        appendLog('Indexed ' + timeline.size() + ' deskew TIFF timepoints in ' + folder)
    }

    /** Open with two real frames so Fiji creates C, Z and T selectors from the outset. */
    void publishInitialPair() {
        long firstTime = timeline.get(0)
        long secondTime = timeline.get(1)
        DeskewTimepoint first = new DeskewTimepoint(indexed.get(firstTime), firstTime)
        layout = DeskewVolumeLayout.read(first.file, first.info,
                fallbackVoxelSize, intervalMs / 1000.0d)
        DeskewTimepoint second = new DeskewTimepoint(indexed.get(secondTime), secondTime)
        stack = new GrowingDeskewVolumeStack(first, layout, cache)
        stack.append(second)
        image = openImage(stack)
        nextTimepoint = 2
        appendLog('Seeded Time' + String.format(Locale.ROOT, '%06d', firstTime) +
                ' and Time' + String.format(Locale.ROOT, '%06d', secondTime) + ' as C=' +
                layout.channels + ', Z=' + layout.slices + ', T=2. Current view stays at T=1.')
        updateRunningStatus()
    }

    void publishNext() {
        if (nextTimepoint >= timeline.size()) {
            timer.stop()
            updateControls(false, 'Complete: ' + nextTimepoint + ' timepoints')
            appendLog('Complete.')
            return
        }
        try {
            long time = timeline.get(nextTimepoint)
            DeskewTimepoint prepared = new DeskewTimepoint(indexed.get(time), time)
            if (stack == null) {
                layout = DeskewVolumeLayout.read(prepared.file, prepared.info,
                        fallbackVoxelSize, intervalMs / 1000.0d)
                stack = new GrowingDeskewVolumeStack(prepared, layout, cache)
                image = openImage(stack)
            } else {
                stack.validate(prepared)
                growImage(image, stack, prepared)
            }
            nextTimepoint++
            appendLog('Published Time' + String.format(Locale.ROOT, '%06d', time) +
                    ' as C=' + layout.channels + ', Z=' + layout.slices + ', T=' +
                    nextTimepoint + '; cache ' + cacheSummary() + '.')
            updateRunningStatus()
            if (nextTimepoint >= timeline.size()) {
                timer.stop()
                updateControls(false, 'Complete: ' + nextTimepoint + ' timepoints')
                appendLog('Complete.')
            }
        } catch (Throwable failure) {
            stopSimulation('Stopped after error')
            showError(failure)
        }
    }

    ImagePlus openImage(GrowingDeskewVolumeStack stack) {
        ImagePlus base = new ImagePlus('TIFF virtual deskew volume', stack)
        base.setDimensions(layout.channels, layout.slices, stack.frameCount())
        base.setOpenAsHyperStack(true)
        ImagePlus opened = layout.channels > 1 ?
                new CompositeImage(base, CompositeImage.COMPOSITE) : base
        opened.title = 'SIMULATED LIVE TIFF DESKEW VOLUME'
        Calibration calibration = new Calibration()
        calibration.pixelWidth = layout.pixelWidth
        calibration.pixelHeight = layout.pixelHeight
        calibration.pixelDepth = layout.pixelDepth
        calibration.frameInterval = layout.frameInterval
        calibration.setUnit(layout.unit)
        calibration.setTimeUnit('second')
        opened.calibration = calibration
        opened.changes = false
        opened.show()
        if (opened instanceof CompositeImage) ((CompositeImage) opened).resetDisplayRanges()
        assertHyperstackDimensions(opened, layout.channels, layout.slices, stack.frameCount())
        appendLog('Opened 5D hyperstack: X=' + layout.width + ', Y=' + layout.height +
                ', C=' + layout.channels + ', Z=' + layout.slices + ', T=' +
                stack.frameCount() + ', virtual=' + stack.isVirtual() + '.')
        return opened
    }

    /** Add one TIFF timepoint while preserving the user's current C/Z/T position. */
    void growImage(ImagePlus image, GrowingDeskewVolumeStack stack,
            DeskewTimepoint timepoint) {
        if (image == null) return
        int frames = stack.frameCount() + 1
        setImageDimensionsRaw(image, layout.channels, layout.slices, frames)
        stack.append(timepoint)
        if (image.window == null) {
            image.setStack(stack, layout.channels, layout.slices, frames)
        } else if (!extendTimeAxisInPlace(image, frames)) {
            image.setStack(stack, layout.channels, layout.slices, frames)
        }
        image.setOpenAsHyperStack(true)
        // Deliberately no setPosition(), setC(), setZ(), setT() or updateAndDraw().
        assertHyperstackDimensions(image, layout.channels, layout.slices, frames)
    }

    /** Set C/Z/T without verifyDimensions observing a half-published stack. */
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
        int[] dimensions = image.getDimensions()
        if (dimensions[2] != channels || dimensions[3] != slices ||
                dimensions[4] != frames ||
                image.getStackSize() != channels * slices * frames ||
                !image.getStack().isVirtual()) {
            throw new IllegalStateException('Fiji dimension mismatch: got C=' + dimensions[2] +
                    ', Z=' + dimensions[3] + ', T=' + dimensions[4] + ', stack=' +
                    image.getStackSize() + '; expected C=' + channels + ', Z=' + slices +
                    ', T=' + frames + ' and a virtual stack.')
        }
    }

    /** Extend only StackWindow's T range; identical principle to the OME-Zarr viewer. */
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

    /**
     * Capture the active ROI and ask which part of C/Z/T should become an ordinary stack.
     *
     * The published timepoint list is snapshotted before the dialog opens. Live simulation can
     * continue adding later T positions, but the materialization remains a consistent request.
     */
    void materializeActiveRoi() {
        if (materializing) return
        if (image == null || stack == null || layout == null) {
            IJ.showMessage('Materialise TIFF ROI', 'Start the virtual deskew preview first.')
            return
        }
        ImagePlus active = WindowManager.getCurrentImage()
        if (active == null || active != image || active.roi == null) {
            IJ.showMessage('Materialise TIFF ROI',
                    'Draw an ROI on the simulated live TIFF deskew volume first.')
            return
        }
        Rectangle requestedBox = active.roi.bounds
        Rectangle box = requestedBox.intersection(
                new Rectangle(0, 0, layout.width, layout.height))
        if (box.width < 1 || box.height < 1) {
            IJ.showMessage('Materialise TIFF ROI', 'The active ROI does not overlap the volume.')
            return
        }

        List<DeskewTimepoint> available = stack.snapshotTimepoints()
        int published = available.size()
        GenericDialog dialog = new GenericDialog('Materialise TIFF ROI')
        dialog.addMessage('Active ROI bounding box: x=' + box.x + ', y=' + box.y +
                ', width=' + box.width + ', height=' + box.height + '.\n' +
                'C, Z and T ranges are 1 based and inclusive.\n' +
                'The source preview remains virtual and its displayed position will not move.')
        dialog.addNumericField('first channel', 1, 0)
        dialog.addNumericField('last channel', layout.channels, 0)
        dialog.addNumericField('first z', 1, 0)
        dialog.addNumericField('last z', layout.slices, 0)
        dialog.addNumericField('first timepoint', 1, 0)
        dialog.addNumericField('last timepoint', published, 0)
        dialog.showDialog()
        if (dialog.wasCanceled()) return

        int[] channelRange
        int[] zRange
        int[] timeRange
        try {
            channelRange = inclusiveRange(dialog.getNextNumber(), dialog.getNextNumber(),
                    layout.channels, 'channel')
            zRange = inclusiveRange(dialog.getNextNumber(), dialog.getNextNumber(),
                    layout.slices, 'z')
            timeRange = inclusiveRange(dialog.getNextNumber(), dialog.getNextNumber(),
                    published, 'timepoint')
        } catch (IllegalArgumentException failure) {
            IJ.showMessage('Materialise TIFF ROI', failure.message)
            return
        }

        long bytes = saturatedMultiply(2L, (long) box.width, (long) box.height,
                channelRange[1], zRange[1], timeRange[1])
        YesNoCancelDialog confirm = new YesNoCancelDialog(frame, 'Materialise TIFF ROI',
                'Materialise ' + box.width + 'x' + box.height + ', C=' + channelRange[1] +
                ', Z=' + zRange[1] + ', T=' + timeRange[1] + '?\nApproximately ' +
                String.format(Locale.ROOT, '%.1f', bytes / 1048576.0d) +
                ' MB of pixel memory will be allocated.\n' +
                'TIFF planes will be decoded in parallel and cropped immediately.')
        if (!confirm.yesPressed()) return

        List<DeskewTimepoint> selectedTimes = new ArrayList<DeskewTimepoint>(
                available.subList(timeRange[0], timeRange[0] + timeRange[1]))
        Calibration outputCalibration = image.calibration == null ?
                new Calibration() : image.calibration.copy()
        outputCalibration.xOrigin -= box.x
        outputCalibration.yOrigin -= box.y
        outputCalibration.zOrigin -= zRange[0]
        List<LUT> selectedLuts = new ArrayList<LUT>()
        if (image instanceof CompositeImage) {
            CompositeImage composite = (CompositeImage) image
            for (int c = 0; c < channelRange[1]; c++) {
                selectedLuts.add(composite.getChannelLut(channelRange[0] + c + 1))
            }
        }

        startMaterialization(selectedTimes, box, channelRange[0], channelRange[1],
                zRange[0], zRange[1], outputCalibration, selectedLuts)
    }

    /** Decode source planes concurrently and keep only the requested rectangle. */
    void startMaterialization(final List<DeskewTimepoint> selectedTimes,
            final Rectangle box, final int firstChannel, final int channels,
            final int firstZ, final int slices, final Calibration calibration,
            final List<LUT> selectedLuts) {
        final DeskewVolumeLayout sourceLayout = layout
        final String sourceFolder = indexedFolder
        final int token = ++materializationToken
        materializing = true
        materializeButton.enabled = false
        cache.clear() // release preview planes before allocating the materialized result
        final long started = System.nanoTime()
        statusLabel.text = 'Materialising TIFF ROI...'
        appendLog('Materialising ROI ' + box.width + 'x' + box.height + ', C=' + channels +
                ', Z=' + slices + ', T=' + selectedTimes.size() + ' with bounded parallel reads.')

        materializationWorker = Shutdown.daemon({
            try {
                final ImagePlus result = buildMaterializedRoi(selectedTimes, box,
                        firstChannel, channels, firstZ, slices, calibration,
                        sourceLayout, sourceFolder)
                SwingUtilities.invokeLater({
                    if (token != materializationToken || exiting) {
                        result.changes = false
                        result.close()
                        return
                    }
                    if (result instanceof CompositeImage && !selectedLuts.isEmpty()) {
                        CompositeImage composite = (CompositeImage) result
                        for (int c = 0; c < selectedLuts.size(); c++) {
                            composite.setChannelLut(selectedLuts.get(c), c + 1)
                        }
                    }
                    result.show()
                    materializing = false
                    materializationWorker = null
                    materializeButton.enabled = stack != null
                    double seconds = (System.nanoTime() - started) / 1.0e9d
                    statusLabel.text = 'Materialised TIFF ROI in ' +
                            String.format(Locale.ROOT, '%.3f s', seconds)
                    appendLog('Materialised ROI in ' +
                            String.format(Locale.ROOT, '%.3f s.', seconds))
                } as Runnable)
            } catch (CancellationException ignored) {
                SwingUtilities.invokeLater({
                    if (token != materializationToken) return
                    materializing = false
                    materializationWorker = null
                    materializeButton.enabled = stack != null
                    statusLabel.text = 'TIFF ROI materialization cancelled'
                    appendLog('TIFF ROI materialization cancelled.')
                } as Runnable)
            } catch (final Throwable failure) {
                SwingUtilities.invokeLater({
                    if (token != materializationToken) return
                    materializing = false
                    materializationWorker = null
                    materializeButton.enabled = stack != null
                    showError(failure)
                } as Runnable)
            }
        } as Runnable, 'TIFF-ROI-materializer')
        materializationWorker.start()
    }

    ImagePlus buildMaterializedRoi(List<DeskewTimepoint> selectedTimes,
            Rectangle box, int firstChannel, int channels, int firstZ, int slices,
            Calibration calibration, DeskewVolumeLayout sourceLayout, String sourceFolder) {
        final int roiX = (int) box.x
        final int roiY = (int) box.y
        final int roiWidth = (int) box.width
        final int roiHeight = (int) box.height
        int planesPerTimepoint = Math.multiplyExact(channels, slices)
        int totalPlanes = Math.multiplyExact(planesPerTimepoint, selectedTimes.size())
        short[][] output = new short[totalPlanes][]
        int workerCount = Math.max(1, Math.min(12,
                Math.min(Runtime.getRuntime().availableProcessors(), totalPlanes)))
        int lanesPerTimepoint = Math.max(1, Math.min(planesPerTimepoint,
                (int) Math.ceil(workerCount / (double) selectedTimes.size())))
        ExecutorService pool = Executors.newFixedThreadPool(workerCount,
                Shutdown.daemonThreads('TIFF-ROI-plane'))
        materializationPool = pool
        AtomicInteger completed = new AtomicInteger()
        List<Future<Void>> jobs = new ArrayList<Future<Void>>()
        try {
            for (int localT = 0; localT < selectedTimes.size(); localT++) {
                final int timeIndex = localT
                final DeskewTimepoint source = selectedTimes.get(localT)
                for (int lane = 0; lane < lanesPerTimepoint; lane++) {
                    final int assignedLane = lane
                    jobs.add(pool.submit({
                        if (Thread.currentThread().isInterrupted()) {
                            throw new CancellationException('Interrupted')
                        }
                        FastTiffReader.PlaneReader reader =
                                new FastTiffReader.PlaneReader(source.file, source.info)
                        try {
                            for (int localPlane = assignedLane;
                                    localPlane < planesPerTimepoint;
                                    localPlane += lanesPerTimepoint) {
                                if (Thread.currentThread().isInterrupted()) {
                                    throw new CancellationException('Interrupted')
                                }
                                int localChannel = localPlane % channels
                                int localZ = (int) (localPlane / channels)
                                int sourcePlane = (firstZ + localZ) * sourceLayout.channels +
                                        firstChannel + localChannel
                                short[] full = reader.readPixels(sourcePlane)
                                short[] cropped = new short[roiWidth * roiHeight]
                                for (int row = 0; row < roiHeight; row++) {
                                    System.arraycopy(full,
                                            (roiY + row) * sourceLayout.width + roiX,
                                            cropped, row * roiWidth, roiWidth)
                                }
                                int outputIndex = timeIndex * planesPerTimepoint + localPlane
                                output[outputIndex] = cropped
                                int done = completed.incrementAndGet()
                                if ((done & 31) == 0 || done == totalPlanes) {
                                    IJ.showProgress(done, totalPlanes)
                                    IJ.showStatus('Materialising TIFF ROI: ' + done + '/' +
                                            totalPlanes + ' planes')
                                }
                            }
                        } finally {
                            reader.close()
                        }
                        return null
                    } as Callable<Void>))
                }
            }
            for (Future<Void> job : jobs) {
                try {
                    job.get()
                } catch (ExecutionException failure) {
                    Throwable cause = failure.cause
                    if (cause instanceof CancellationException) throw cause
                    throw new IOException('Failed while materialising TIFF ROI: ' +
                            (cause.message == null ? cause.class.simpleName : cause.message), cause)
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt()
                    throw new CancellationException('Interrupted')
                }
            }
        } finally {
            pool.shutdownNow()
            if (materializationPool == pool) materializationPool = null
            IJ.showProgress(1.0d)
        }

        if (Thread.currentThread().isInterrupted()) throw new CancellationException('Interrupted')
        ImageStack materialized = new ImageStack(roiWidth, roiHeight)
        for (int t = 0; t < selectedTimes.size(); t++) {
            for (int z = 0; z < slices; z++) {
                for (int c = 0; c < channels; c++) {
                    int index = t * planesPerTimepoint + z * channels + c
                    String label = 'C=' + (firstChannel + c + 1) + ', Z=' +
                            (firstZ + z + 1) + ', Time' + String.format(Locale.ROOT, '%06d',
                            selectedTimes.get(t).timeNumber)
                    materialized.addSlice(label,
                            new ShortProcessor(roiWidth, roiHeight, output[index], null))
                }
            }
        }
        ImagePlus base = new ImagePlus('MATERIALISED TIFF ROI', materialized)
        base.setDimensions(channels, slices, selectedTimes.size())
        base.setOpenAsHyperStack(channels > 1 || slices > 1 || selectedTimes.size() > 1)
        ImagePlus result = channels > 1 ?
                new CompositeImage(base, CompositeImage.COMPOSITE) : base
        result.calibration = calibration
        result.setProperty('Info', 'Source: ' + sourceFolder + '\nROI: x=' + roiX +
                ', y=' + roiY + ', width=' + roiWidth + ', height=' + roiHeight +
                '\nC=' + (firstChannel + 1) + '-' + (firstChannel + channels) +
                ', Z=' + (firstZ + 1) + '-' + (firstZ + slices) +
                ', T source files=' + selectedTimes.first().timeNumber + '-' +
                selectedTimes.last().timeNumber)
        result.changes = false
        return result
    }

    void cancelMaterialization() {
        materializationToken++
        Thread worker = materializationWorker
        if (worker != null) worker.interrupt()
        ExecutorService pool = materializationPool
        if (pool != null) pool.shutdownNow()
        materializationWorker = null
        materializationPool = null
        materializing = false
        if (materializeButton != null) materializeButton.enabled = false
    }

    /** Convert two 1-based inclusive dialog values to {zero-based first, count}. */
    static int[] inclusiveRange(double askedFirst, double askedLast,
            int available, String axis) {
        if (Double.isNaN(askedFirst) || Double.isInfinite(askedFirst) ||
                Double.isNaN(askedLast) || Double.isInfinite(askedLast) ||
                askedFirst != Math.rint(askedFirst) || askedLast != Math.rint(askedLast)) {
            throw new IllegalArgumentException(
                    'The ' + axis + ' range must contain whole numbers.')
        }
        int first = (int) askedFirst
        int last = (int) askedLast
        if (first < 1 || last < first || last > available) {
            throw new IllegalArgumentException('The ' + axis + ' range must be within 1-' +
                    available + ' and first must not exceed last.')
        }
        return [first - 1, last - first + 1] as int[]
    }

    static long saturatedMultiply(long... values) {
        long result = 1L
        for (long value : values) {
            if (value != 0L && result > Long.MAX_VALUE / value) return Long.MAX_VALUE
            result *= value
        }
        return result
    }

    void closeImage() {
        if (image == null) return
        image.changes = false
        image.close()
        image.flush()
        image = null
    }

    void updateControls(boolean running, String status) {
        startButton.enabled = !running &&
                (indexedFolder == null || nextTimepoint < timeline.size())
        stopButton.enabled = running
        resetButton.enabled = true
        materializeButton.enabled = stack != null && !materializing
        statusLabel.text = status
    }

    void updateRunningStatus() {
        materializeButton.enabled = stack != null && !materializing
        statusLabel.text = 'Running 5D stack: C=' + layout.channels + ', Z=' + layout.slices +
                ', T=' + nextTimepoint + '/' + timeline.size() + '; cache ' + cacheSummary()
    }

    String cacheSummary() {
        return String.format(Locale.ROOT, '%.1f/%.0f MiB (%d planes)',
                cache.usedBytes() / 1048576.0d, cacheBytes / 1048576.0d, cache.planeCount())
    }

    void appendLog(String message) {
        logArea.append(message + '\n')
        logArea.caretPosition = logArea.document.length
        println('[Deskew virtual preview simulation] ' + message)
    }

    void showError(Throwable failure) {
        String message = failure.message == null ? failure.class.simpleName : failure.message
        appendLog('ERROR: ' + message)
        IJ.log('Deskew virtual preview simulation: ' + message)
        IJ.showMessage('Growing deskewed-volume TIFF preview', message)
    }

    static Long timeNumber(File file) {
        Matcher matcher = TIME_PATTERN.matcher(file.name)
        return matcher.find() ? Long.valueOf(matcher.group(1)) : null
    }
}

DeskewVolumePreviewSimulation simulation
if (SwingUtilities.isEventDispatchThread()) {
    simulation = new DeskewVolumePreviewSimulation(DEFAULT_DESKEW_FOLDER,
            SIMULATED_INTERVAL_MS, SHARED_CACHE_BYTES, DEFAULT_VOXEL_SIZE_UM)
} else {
    final DeskewVolumePreviewSimulation[] holder = new DeskewVolumePreviewSimulation[1]
    SwingUtilities.invokeAndWait({
        holder[0] = new DeskewVolumePreviewSimulation(DEFAULT_DESKEW_FOLDER,
                SIMULATED_INTERVAL_MS, SHARED_CACHE_BYTES, DEFAULT_VOXEL_SIZE_UM)
    } as Runnable)
    simulation = holder[0]
    // Keep the Script Editor run alive until Exit while Swing continues independently.
    simulation.finished.await()
}
