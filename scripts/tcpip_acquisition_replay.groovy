/*
 * Replay an OPM acquisition to Live Deskew over TCP/IP.
 *
 * Run Plugins > OPM Toolset > Live Processing > Live Deskew first and enable
 * its TCP/IP listener. This script can send source paths directly, or recreate
 * an acquisition by copying/moving the selected source files into a destination
 * folder. In a transfer mode the destination file appears under its final name
 * and grows as bytes are written. Its path is announced only after the output is
 * closed, forced to disk, and verified to have the source size.
 *
 * Paths are sent one per line, in this order:
 *
 *   1. ExperimentalParameters.txt, if it is present and selected
 *   2. selected .tif/.tiff files ordered by Position, Time, then Channel
 *   3. optionally, TIFFs that appear in the folder after Start
 *
 * Sending happens on a worker thread, so Start and Stop never freeze the UI.
 * Stop cancels only the current send; the window remains reusable. Exit stops
 * any active send, closes the window, and lets the Groovy script finish.
 * For strictly paced processing, turn off Live Deskew's "also watch the folder
 * from the announced file path" option; otherwise it may backfill the folder.
 */

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.ActionListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Matcher
import java.util.regex.Pattern
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.DefaultListModel
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import javax.swing.border.EmptyBorder


enum TransferMode {
    SEND_SOURCE("Send source paths only", false, false),
    COPY("Copy to destination (keep source)", true, false),
    MOVE("Move to destination (delete source after verification)", true, true)

    final String label
    final boolean transfers
    final boolean deletesSource

    TransferMode(String label, boolean transfers, boolean deletesSource) {
        this.label = label
        this.transfers = transfers
        this.deletesSource = deletesSource
    }

    @Override
    String toString() { return label }
}


class TcpPathSenderUi {
    private static final String METADATA_NAME = "ExperimentalParameters.txt"
    private static final List<String> METADATA_ALIASES = [
        METADATA_NAME,
        "ExperimentParameter.txt",
        "ExperimentParameters.txt"
    ].asImmutable()
    private static final int CONNECT_TIMEOUT_MS = 5000
    private static final long WATCH_POLL_MS = 500
    private static final int COPY_BUFFER_BYTES = 4 * 1024 * 1024
    private static final Pattern ACQUISITION_FILE = Pattern.compile(
        '(?i).*?_Position(\\d+)_Time(\\d+)_Channel(\\d+).*?\\.tiff?$')

    private final JDialog dialog
    private final JTextField folderField = new JTextField("E:/OPM/3_timelapse_0", 34)
    private final JTextField destinationField = new JTextField(
        "E:/OPM/3_timelapse_0_replay", 34)
    private final JTextField hostField = new JTextField("127.0.0.1", 12)
    private final JTextField portField = new JTextField("5020", 6)
    private final JTextField delayField = new JTextField("10.0", 6)
    private final JButton browseButton = new JButton("Browse...")
    private final JButton destinationBrowseButton = new JButton("Browse...")
    private final JComboBox<TransferMode> transferMode =
        new JComboBox<TransferMode>(TransferMode.values())
    private final JButton refreshButton = new JButton("Refresh")
    private final JButton selectAllButton = new JButton("Select all")
    private final JButton clearSelectionButton = new JButton("Clear")
    private final JCheckBox watchFolderBox = new JCheckBox(
        "Keep watching; send TIFFs created after Start")
    private final DefaultListModel<String> fileListModel = new DefaultListModel<String>()
    private final JList<String> fileList = new JList<String>(fileListModel)
    private final JLabel selectionLabel = new JLabel("No files loaded")
    private final JButton startButton = new JButton("Start")
    private final JButton stopButton = new JButton("Stop")
    private final JButton exitButton = new JButton("Exit")
    private final JLabel statusLabel = new JLabel("Idle")
    private final JTextArea logArea = new JTextArea(15, 72)

    private final AtomicBoolean cancelRequested = new AtomicBoolean(false)
    private final AtomicBoolean exiting = new AtomicBoolean(false)
    private final Object ioLock = new Object()
    private volatile Thread worker
    private volatile Socket socket
    private volatile PrintWriter writer
    private String listedFolderPath

    TcpPathSenderUi() {
        transferMode.setSelectedItem(TransferMode.COPY)
        dialog = new JDialog((Frame) null, "OPM TCP/IP Acquisition Replay", true)
        buildUi()
    }

    void showModal() {
        dialog.setLocationByPlatform(true)
        dialog.setVisible(true)
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8))
        root.setBorder(new EmptyBorder(10, 10, 10, 10))

        JPanel settings = new JPanel(new GridBagLayout())
        GridBagConstraints label = new GridBagConstraints()
        label.gridx = 0
        label.anchor = GridBagConstraints.LINE_END
        label.insets = new Insets(3, 3, 3, 7)

        GridBagConstraints field = new GridBagConstraints()
        field.gridx = 1
        field.weightx = 1.0
        field.fill = GridBagConstraints.HORIZONTAL
        field.insets = new Insets(3, 3, 3, 3)

        GridBagConstraints extra = new GridBagConstraints()
        extra.gridx = 2
        extra.anchor = GridBagConstraints.LINE_START
        extra.insets = new Insets(3, 3, 3, 3)

        label.gridy = field.gridy = extra.gridy = 0
        settings.add(new JLabel("Source folder:"), label)
        settings.add(folderField, field)
        settings.add(browseButton, extra)

        label.gridy = field.gridy = extra.gridy = 1
        settings.add(new JLabel("Destination folder:"), label)
        settings.add(destinationField, field)
        settings.add(destinationBrowseButton, extra)

        label.gridy = field.gridy = extra.gridy = 2
        settings.add(new JLabel("Replay mode:"), label)
        settings.add(transferMode, field)

        label.gridy = field.gridy = extra.gridy = 3
        settings.add(new JLabel("TCP/IP:"), label)
        JPanel networkPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0))
        networkPanel.add(hostField)
        networkPanel.add(new JLabel("Port:"))
        networkPanel.add(portField)
        networkPanel.add(new JLabel("Delay between TIFFs (s):"))
        networkPanel.add(delayField)
        settings.add(networkPanel, field)

        root.add(settings, BorderLayout.NORTH)

        fileList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        fileList.setVisibleRowCount(9)
        fileList.addListSelectionListener({ updateSelectionLabel() })
        JScrollPane fileScrollPane = new JScrollPane(fileList)

        JPanel fileButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0))
        fileButtons.add(refreshButton)
        fileButtons.add(selectAllButton)
        fileButtons.add(clearSelectionButton)
        fileButtons.add(selectionLabel)

        JPanel filePanel = new JPanel(new BorderLayout(5, 5))
        filePanel.setBorder(BorderFactory.createTitledBorder(
            "Files to send (Ctrl/Shift-click to choose a shortlist)"))
        filePanel.add(fileScrollPane, BorderLayout.CENTER)
        filePanel.add(fileButtons, BorderLayout.SOUTH)

        logArea.setEditable(false)
        logArea.setLineWrap(false)
        JScrollPane logScrollPane = new JScrollPane(logArea)
        JPanel logPanel = new JPanel(new BorderLayout())
        logPanel.setBorder(BorderFactory.createTitledBorder("Transmission log"))
        logPanel.add(logScrollPane, BorderLayout.CENTER)

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, filePanel, logPanel)
        splitPane.setResizeWeight(0.5d)
        splitPane.setPreferredSize(new Dimension(800, 450))
        root.add(splitPane, BorderLayout.CENTER)

        JPanel controls = new JPanel(new BorderLayout())
        JPanel statePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
        statePanel.add(watchFolderBox)
        statePanel.add(statusLabel)
        controls.add(statePanel, BorderLayout.WEST)
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0))
        buttons.add(startButton)
        buttons.add(stopButton)
        buttons.add(exitButton)
        controls.add(buttons, BorderLayout.EAST)
        root.add(controls, BorderLayout.SOUTH)

        stopButton.setEnabled(false)
        browseButton.addActionListener({ chooseFolder() } as ActionListener)
        destinationBrowseButton.addActionListener({ chooseDestinationFolder() } as ActionListener)
        transferMode.addActionListener({ updateTransferControls() } as ActionListener)
        refreshButton.addActionListener({ refreshFileList(true, false) } as ActionListener)
        selectAllButton.addActionListener({ selectAllTiffs() } as ActionListener)
        clearSelectionButton.addActionListener({ fileList.clearSelection() } as ActionListener)
        startButton.addActionListener({ startSending() } as ActionListener)
        stopButton.addActionListener({ requestStop() } as ActionListener)
        exitButton.addActionListener({ exitUi() } as ActionListener)

        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE)
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            void windowClosing(WindowEvent event) {
                TcpPathSenderUi.this.exitUi()
            }
        })
        dialog.setContentPane(root)
        dialog.pack()
        dialog.setMinimumSize(dialog.getSize())

        appendLog("Ready. A supported parameter file, when present, can be selected or unselected.")
        appendLog("Copy mode is selected by default; destination files grow under their final names.")
        appendLog("A path is announced only after its destination file is flushed and size-verified.")
        appendLog("Start Live Deskew with TCP/IP listening enabled before clicking Start.")
        appendLog("For paced processing, disable 'also watch the folder from the announced file path'.")
        appendLog("Select existing files for a one-pass shortlist. Enable Keep watching for future TIFFs.")
        updateTransferControls()
        refreshFileList(false, true)
    }

    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser(folderField.getText().trim())
        chooser.setDialogTitle("Choose the acquisition folder")
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY)
        chooser.setAcceptAllFileFilterUsed(false)
        if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
            folderField.setText(chooser.getSelectedFile().getAbsolutePath())
            refreshFileList(false, true)
        }
    }

    private void chooseDestinationFolder() {
        JFileChooser chooser = new JFileChooser(destinationField.getText().trim())
        chooser.setDialogTitle("Choose the replay destination folder")
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY)
        chooser.setAcceptAllFileFilterUsed(false)
        if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION)
            destinationField.setText(chooser.getSelectedFile().getAbsolutePath())
    }

    private void updateTransferControls() {
        TransferMode selected = (TransferMode) transferMode.getSelectedItem()
        boolean enabled = selected != null && selected.transfers &&
            (worker == null || !worker.isAlive())
        destinationField.setEnabled(enabled)
        destinationBrowseButton.setEnabled(enabled)
    }

    private void refreshFileList(boolean preserveSelection, boolean selectAll) {
        Set<String> previouslySelected = preserveSelection
            ? new LinkedHashSet<String>(fileList.getSelectedValuesList())
            : Collections.emptySet()
        File folder = new File(folderField.getText().trim())
        if (!folder.isDirectory()) {
            fileListModel.clear()
            listedFolderPath = null
            updateSelectionLabel()
            if (preserveSelection) showError("Choose an existing acquisition folder first.")
            return
        }

        List<File> selectableFiles = discoverSelectableFiles(folder)
        fileListModel.clear()
        selectableFiles.each { fileListModel.addElement(it.getName()) }
        listedFolderPath = pathKey(folder)

        if (selectAll && !fileListModel.isEmpty()) {
            fileList.setSelectionInterval(0, fileListModel.getSize() - 1)
        } else if (!previouslySelected.isEmpty()) {
            List<Integer> selectedIndices = []
            for (int index = 0; index < fileListModel.getSize(); index++) {
                if (previouslySelected.contains(fileListModel.getElementAt(index)))
                    selectedIndices.add(index)
            }
            fileList.setSelectedIndices(selectedIndices.collect { it as int } as int[])
        }
        updateSelectionLabel()
    }

    private void selectAllTiffs() {
        if (!fileListModel.isEmpty())
            fileList.setSelectionInterval(0, fileListModel.getSize() - 1)
    }

    private void updateSelectionLabel() {
        selectionLabel.setText("${fileList.getSelectedIndices().length} of " +
            "${fileListModel.getSize()} files selected")
    }

    private static List<File> discoverSelectableFiles(File folder) {
        File[] children = folder.listFiles()
        if (children == null) return []
        File metadata = findMetadata(children)
        List<File> files = []
        if (metadata != null) files.add(metadata)
        files.addAll(discoverTiffs(folder))
        return files
    }

    private static File findMetadata(File[] children) {
        for (String acceptedName : METADATA_ALIASES) {
            File match = children.find {
                it.isFile() && it.getName().equalsIgnoreCase(acceptedName)
            }
            if (match != null) return match
        }
        return null
    }

    private static List<File> discoverTiffs(File folder) {
        File[] children = folder.listFiles()
        if (children == null) return []
        List<File> tiffs = children.findAll { isTiff(it) } as List<File>
        tiffs.sort { File left, File right ->
            compareAcquisitionFiles(left, right)
        }
        return tiffs
    }

    private static boolean isTiff(File file) {
        if (file == null || !file.isFile()) return false
        String name = file.getName().toLowerCase(Locale.ROOT)
        return name.endsWith(".tif") || name.endsWith(".tiff")
    }

    private void startSending() {
        if (worker != null && worker.isAlive()) return

        File folder = new File(folderField.getText().trim())
        if (!folder.isDirectory()) {
            showError("Choose an existing acquisition folder.")
            return
        }

        File[] children = folder.listFiles()
        if (children == null) {
            showError("The acquisition folder cannot be read.")
            return
        }

        if (listedFolderPath != pathKey(folder)) {
            refreshFileList(false, true)
            showError("The TIFF list has been refreshed for this folder.\n" +
                "Review the selection, then click Start again.")
            return
        }

        File metadata = findMetadata(children)
        List<File> allTiffs = discoverTiffs(folder)
        Map<String, File> selectableByName = [:]
        if (metadata != null) selectableByName.put(metadata.getName(), metadata)
        allTiffs.each { selectableByName.put(it.getName(), it) }
        List<String> selectedNames = fileList.getSelectedValuesList()
        List<String> unavailable = selectedNames.findAll { !selectableByName.containsKey(it) }
        if (!unavailable.isEmpty()) {
            refreshFileList(true, false)
            showError("Some selected files are no longer available. The list was refreshed.")
            return
        }
        boolean sendMetadata = metadata != null && selectedNames.contains(metadata.getName())
        List<File> selectedTiffs = selectedNames.findAll {
            selectableByName.get(it) != metadata
        }.collect { selectableByName.get(it) }
        selectedTiffs.sort { File left, File right ->
            compareAcquisitionFiles(left, right)
        }
        boolean keepWatching = watchFolderBox.isSelected()
        if (!sendMetadata && selectedTiffs.isEmpty() && !keepWatching) {
            showError("Select at least one file, or enable Keep watching to wait for new TIFFs.")
            return
        }

        String host = hostField.getText().trim()
        if (host.isEmpty()) {
            showError("Enter a TCP/IP host name or address.")
            return
        }

        int port
        double delaySeconds
        try {
            port = Integer.parseInt(portField.getText().trim())
            if (port < 1 || port > 65535) throw new NumberFormatException()
        } catch (NumberFormatException ignored) {
            showError("The port must be a whole number from 1 to 65535.")
            return
        }
        try {
            delaySeconds = Double.parseDouble(delayField.getText().trim())
            if (Double.isNaN(delaySeconds) || Double.isInfinite(delaySeconds) || delaySeconds < 0.0)
                throw new NumberFormatException()
        } catch (NumberFormatException ignored) {
            showError("The delay must be a non-negative number of seconds.")
            return
        }

        TransferMode mode = (TransferMode) transferMode.getSelectedItem()
        if (mode == null) mode = TransferMode.COPY
        File destination = null
        if (mode.transfers) {
            String destinationText = destinationField.getText().trim()
            if (destinationText.isEmpty()) {
                showError("Choose a destination folder for copy or move replay mode.")
                return
            }
            destination = new File(destinationText)
            try {
                Files.createDirectories(destination.toPath())
            } catch (Exception failure) {
                showError("The destination folder cannot be created:\n" + messageOf(failure))
                return
            }
            if (!destination.isDirectory()) {
                showError("The destination path is not a folder.")
                return
            }
            if (pathKey(folder) == pathKey(destination)) {
                showError("Source and destination folders must be different.")
                return
            }

            List<File> conflicts = []
            if (sendMetadata) {
                File target = destinationFile(destination, metadata, true)
                if (target.exists()) conflicts.add(target)
            }
            for (File selected : selectedTiffs) {
                File target = destinationFile(destination, selected, false)
                if (target.exists()) conflicts.add(target)
            }
            if (!conflicts.isEmpty()) {
                String examples = conflicts.take(5).collect { it.getName() }.join("\n")
                showError("The destination already contains ${conflicts.size()} selected " +
                    "file(s). Choose an empty replay folder or remove them first:\n" + examples)
                return
            }
        }

        if (mode.deletesSource) {
            int selectedCount = selectedTiffs.size() + (sendMetadata ? 1 : 0)
            String future = keepWatching
                ? "\nAny future TIFFs detected while watching will also be deleted after transfer."
                : ""
            int answer = JOptionPane.showConfirmDialog(dialog,
                "Move replay will delete ${selectedCount} selected source file(s) only after " +
                "each destination copy has been verified.${future}\n\nContinue?",
                "Confirm move replay", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
            if (answer != JOptionPane.YES_OPTION) return
        }

        long delayMillis = Math.round(delaySeconds * 1000.0d)
        cancelRequested.set(false)
        setRunning(true)
        appendLog("")
        appendLog("Queued " + (sendMetadata ? metadata.getName() : "no parameter file") +
            " followed by ${selectedTiffs.size()} selected TIFF(s).")
        appendLog("Replay mode: ${mode}.")
        if (destination != null)
            appendLog("Destination: ${destination.getAbsolutePath()}")
        if (keepWatching) {
            appendLog("Live mode: TIFFs already present but not selected will be ignored; " +
                "new TIFFs will be sent until Stop.")
        }

        final File folderToWatch = folder
        final File metadataToSend = sendMetadata ? metadata : null
        final List<File> tiffsToSend = new ArrayList<File>(selectedTiffs)
        final Set<String> pathsPresentAtStart = new HashSet<String>(
            allTiffs.collect { pathKey(it) })
        final String targetHost = host
        final int targetPort = port
        final long pauseMillis = delayMillis
        final boolean watchForNewTiffs = keepWatching
        final TransferMode replayMode = mode
        final File replayDestination = destination
        Thread nextWorker = new Thread({
            sendFiles(folderToWatch, metadataToSend, tiffsToSend, pathsPresentAtStart,
                targetHost, targetPort, pauseMillis, watchForNewTiffs,
                replayMode, replayDestination)
        } as Runnable, "OPM-TCP-path-sender")
        nextWorker.setDaemon(true)
        worker = nextWorker
        nextWorker.start()
    }

    private void sendFiles(File folder, File metadata, List<File> initialTiffs,
                           Set<String> pathsPresentAtStart, String host, int port,
                           long delayMillis, boolean watchForNewTiffs,
                           TransferMode mode, File destination) {
        boolean completed = false
        int sentTiffCount = 0
        try {
            Socket connected = new Socket()
            synchronized (ioLock) {
                if (cancelRequested.get()) return
                socket = connected
            }
            connected.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            connected.setTcpNoDelay(true)

            synchronized (ioLock) {
                if (cancelRequested.get()) return
                writer = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(connected.getOutputStream(), "UTF-8")), true)
            }
            appendLog("Connected to ${host}:${port}")

            if (metadata != null) {
                File announced = prepareForAnnouncement(metadata, true, mode, destination)
                sendPath(announced, "Parameters")
                if (!initialTiffs.isEmpty() && !pause(delayMillis)) return
            }

            for (int index = 0; index < initialTiffs.size(); index++) {
                if (cancelRequested.get()) return
                File announced = prepareForAnnouncement(
                    initialTiffs.get(index), false, mode, destination)
                sendPath(announced,
                    "Selected TIFF ${index + 1}/${initialTiffs.size()}")
                sentTiffCount++
                if (index + 1 < initialTiffs.size() && !pause(delayMillis)) return
            }

            if (watchForNewTiffs) {
                Set<String> seenPaths = new HashSet<String>(pathsPresentAtStart)
                appendLog("Watching ${folder.getAbsolutePath()} for newly appearing TIFFs...")
                onEdt {
                    if (!cancelRequested.get() && !exiting.get()) statusLabel.setText("Watching...")
                }

                while (!cancelRequested.get()) {
                    if (!pause(WATCH_POLL_MS)) return
                    List<File> newTiffs = discoverTiffs(folder).findAll {
                        seenPaths.add(pathKey(it))
                    }
                    for (int index = 0; index < newTiffs.size(); index++) {
                        if (cancelRequested.get()) return
                        File announced = prepareForAnnouncement(
                            newTiffs.get(index), false, mode, destination)
                        sendPath(announced, "New TIFF ${sentTiffCount + 1}")
                        sentTiffCount++
                        if (index + 1 < newTiffs.size() && !pause(delayMillis)) return
                    }
                }
                return
            }

            sendLine("STOP")
            completed = true
            appendLog("Completed: ${sentTiffCount} TIFF path(s) sent. The UI is still ready.")
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt()
        } catch (SocketException failure) {
            if (!cancelRequested.get()) appendLog("TCP/IP error: ${messageOf(failure)}")
        } catch (Exception failure) {
            if (!cancelRequested.get()) appendLog("Error: ${messageOf(failure)}")
        } finally {
            closeConnection()
            if (cancelRequested.get() && !exiting.get()) appendLog("Stopped by user.")
            final Thread finishedWorker = Thread.currentThread()
            onEdt {
                if (worker == finishedWorker) worker = null
                if (!exiting.get()) {
                    setRunning(false)
                    if (mode.deletesSource) refreshFileList(false, true)
                }
                if (!completed && !cancelRequested.get() && !exiting.get())
                    statusLabel.setText("Error")
            }
        }
    }

    /** Return the source itself, or a verified destination copy ready to announce. */
    private File prepareForAnnouncement(File source, boolean metadata,
                                        TransferMode mode, File destination)
            throws IOException, InterruptedException {
        if (!mode.transfers) return source
        if (destination == null)
            throw new IOException("No replay destination was configured.")
        return transferToDestination(source, destinationFile(destination, source, metadata), mode)
    }

    /**
     * Stream one file under its final destination name so a watcher observes real growth.
     * The returned file is complete: its channel was forced to storage and its byte count
     * matches the source. A cancelled or failed partial copy is removed. Existing destination
     * files are never overwritten.
     */
    private File transferToDestination(File source, File target, TransferMode mode)
            throws IOException, InterruptedException {
        if (source == null || !source.isFile())
            throw new IOException("Source file is unavailable: ${source}")

        long expectedBytes = source.length()
        if (expectedBytes < 0L)
            throw new IOException("Cannot determine source size: ${source}")
        appendLog("${mode == TransferMode.MOVE ? 'Moving' : 'Copying'} " +
            "${source.getName()} (${formatBytes(expectedBytes)})...")
        onEdt {
            if (!cancelRequested.get() && !exiting.get())
                statusLabel.setText("Transferring ${source.getName()}...")
        }

        FileChannel input = null
        FileChannel output = null
        boolean targetCreated = false
        boolean verified = false
        long transferred = 0L
        try {
            input = FileChannel.open(source.toPath(), StandardOpenOption.READ)
            output = FileChannel.open(target.toPath(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            targetCreated = true
            ByteBuffer buffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES)
            while (true) {
                if (cancelRequested.get() || Thread.currentThread().isInterrupted())
                    throw new InterruptedException("Transfer cancelled")
                int count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                transferred += count
                buffer.flip()
                while (buffer.hasRemaining()) output.write(buffer)
                buffer.clear()
            }
            output.force(true)
            output.close()
            output = null

            if (source.length() != expectedBytes)
                throw new IOException("Source changed size while it was being copied: " +
                    source.getName())
            if (transferred != expectedBytes || target.length() != expectedBytes)
                throw new IOException("Destination size verification failed for " +
                    target.getName() + ": expected ${expectedBytes}, wrote ${transferred}, " +
                    "found ${target.length()} bytes.")
            verified = true
        } catch (FileAlreadyExistsException conflict) {
            throw new IOException("Destination file already exists: ${target}", conflict)
        } finally {
            if (output != null) try { output.close() } catch (IOException ignored) { }
            if (input != null) try { input.close() } catch (IOException ignored) { }
            if (targetCreated && !verified) {
                try { Files.deleteIfExists(target.toPath()) }
                catch (IOException cleanupFailure) {
                    appendLog("Could not remove incomplete destination ${target}: " +
                        messageOf(cleanupFailure))
                }
            }
        }

        if (mode.deletesSource) {
            if (cancelRequested.get() || Thread.currentThread().isInterrupted())
                throw new InterruptedException("Transfer cancelled before source deletion")
            Files.delete(source.toPath())
        }
        appendLog("Transfer complete and verified: ${target.getAbsolutePath()}")
        return target
    }

    /** Live Deskew currently recognises the canonical plural metadata filename. */
    private static File destinationFile(File destination, File source, boolean metadata) {
        return new File(destination, metadata ? METADATA_NAME : source.getName())
    }

    private void sendPath(File file, String description) throws IOException {
        String path = file.getAbsolutePath().replace('\\', '/')
        sendLine(path)
        appendLog("${description}: ${path}")
    }

    private void sendLine(String line) throws IOException {
        synchronized (ioLock) {
            if (cancelRequested.get()) throw new InterruptedException("Send cancelled")
            if (writer == null) throw new IOException("The TCP/IP connection is not open.")
            writer.println(line)
            if (writer.checkError()) throw new IOException("The TCP/IP connection was closed.")
        }
    }

    private boolean pause(long millis) throws InterruptedException {
        if (millis <= 0) return !cancelRequested.get()
        Thread.sleep(millis)
        return !cancelRequested.get()
    }

    private void requestStop() {
        Thread active = worker
        if (active == null || !active.isAlive()) return

        cancelRequested.set(true)
        statusLabel.setText("Stopping...")
        appendLog("Stop requested.")
        synchronized (ioLock) {
            if (writer != null) {
                writer.println("STOP")
                writer.flush()
            }
            closeSocketOnly()
        }
        active.interrupt()
    }

    private void exitUi() {
        if (!exiting.compareAndSet(false, true)) return
        requestStop()
        dialog.dispose()
    }

    private void closeConnection() {
        synchronized (ioLock) {
            if (writer != null) {
                writer.close()
                writer = null
            }
            closeSocketOnly()
        }
    }

    private void closeSocketOnly() {
        if (socket != null) {
            try {
                socket.close()
            } catch (IOException ignored) {
                // Nothing else to do while stopping or cleaning up.
            }
            socket = null
        }
    }

    private void setRunning(boolean running) {
        startButton.setEnabled(!running)
        stopButton.setEnabled(running)
        browseButton.setEnabled(!running)
        refreshButton.setEnabled(!running)
        selectAllButton.setEnabled(!running)
        clearSelectionButton.setEnabled(!running)
        fileList.setEnabled(!running)
        watchFolderBox.setEnabled(!running)
        folderField.setEnabled(!running)
        transferMode.setEnabled(!running)
        destinationField.setEnabled(false)
        destinationBrowseButton.setEnabled(false)
        hostField.setEnabled(!running)
        portField.setEnabled(!running)
        delayField.setEnabled(!running)
        statusLabel.setText(running ? "Sending..." : "Idle")
        if (!running) updateTransferControls()
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(dialog, message, "Cannot start",
            JOptionPane.ERROR_MESSAGE)
    }

    private void appendLog(String message) {
        onEdt {
            logArea.append(message + System.lineSeparator())
            logArea.setCaretPosition(logArea.getDocument().getLength())
        }
    }

    private static String messageOf(Throwable failure) {
        String message = failure.getMessage()
        return (message == null || message.trim().isEmpty())
            ? failure.getClass().getSimpleName() : message
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return "${bytes} B"
        if (bytes < 1024L * 1024L)
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0d)
        if (bytes < 1024L * 1024L * 1024L)
            return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0d * 1024.0d))
        return String.format(Locale.ROOT, "%.2f GiB",
            bytes / (1024.0d * 1024.0d * 1024.0d))
    }

    private static void onEdt(Closure task) {
        if (SwingUtilities.isEventDispatchThread()) task.call()
        else SwingUtilities.invokeLater(task as Runnable)
    }

    private static String pathKey(File file) {
        try {
            return file.getCanonicalPath().toLowerCase(Locale.ROOT)
        } catch (IOException ignored) {
            return file.getAbsoluteFile().toPath().normalize().toString()
                .toLowerCase(Locale.ROOT)
        }
    }

    /**
     * OPM acquisition order is Position, then Time, then Channel. Thus a two-channel
     * acquisition sends T1/C1, T1/C2, T2/C1, T2/C2. Names outside this convention
     * retain deterministic natural numeric ordering after conventional names.
     */
    private static int compareAcquisitionFiles(File left, File right) {
        long[] leftCoordinates = acquisitionCoordinates(left.getName())
        long[] rightCoordinates = acquisitionCoordinates(right.getName())
        if (leftCoordinates != null && rightCoordinates != null) {
            for (int axis = 0; axis < leftCoordinates.length; axis++) {
                int order = Long.compare(leftCoordinates[axis], rightCoordinates[axis])
                if (order != 0) return order
            }
        } else if (leftCoordinates != null) {
            return -1
        } else if (rightCoordinates != null) {
            return 1
        }
        return compareNaturally(left.getName(), right.getName())
    }

    private static long[] acquisitionCoordinates(String name) {
        Matcher match = ACQUISITION_FILE.matcher(name)
        if (!match.matches()) return null
        try {
            return [
                Long.parseLong(match.group(1)),
                Long.parseLong(match.group(2)),
                Long.parseLong(match.group(3))
            ] as long[]
        } catch (NumberFormatException tooLarge) {
            return null
        }
    }

    /** Compare digit runs as integers, so file2.tif sorts before file10.tif. */
    private static int compareNaturally(String left, String right) {
        int leftIndex = 0
        int rightIndex = 0
        while (leftIndex < left.length() && rightIndex < right.length()) {
            char leftChar = left.charAt(leftIndex)
            char rightChar = right.charAt(rightIndex)

            if (Character.isDigit(leftChar) && Character.isDigit(rightChar)) {
                int leftEnd = leftIndex
                int rightEnd = rightIndex
                while (leftEnd < left.length() && Character.isDigit(left.charAt(leftEnd))) leftEnd++
                while (rightEnd < right.length() && Character.isDigit(right.charAt(rightEnd))) rightEnd++

                int leftSignificant = leftIndex
                int rightSignificant = rightIndex
                while (leftSignificant < leftEnd - 1 && left.charAt(leftSignificant) == '0') leftSignificant++
                while (rightSignificant < rightEnd - 1 && right.charAt(rightSignificant) == '0') rightSignificant++

                int leftDigits = leftEnd - leftSignificant
                int rightDigits = rightEnd - rightSignificant
                if (leftDigits != rightDigits) return Integer.compare(leftDigits, rightDigits)

                for (int offset = 0; offset < leftDigits; offset++) {
                    char leftDigit = left.charAt(leftSignificant + offset)
                    char rightDigit = right.charAt(rightSignificant + offset)
                    if (leftDigit != rightDigit) return Character.compare(leftDigit, rightDigit)
                }

                int leftRunLength = leftEnd - leftIndex
                int rightRunLength = rightEnd - rightIndex
                if (leftRunLength != rightRunLength)
                    return Integer.compare(leftRunLength, rightRunLength)

                leftIndex = leftEnd
                rightIndex = rightEnd
                continue
            }

            char leftFolded = Character.toLowerCase(leftChar)
            char rightFolded = Character.toLowerCase(rightChar)
            if (leftFolded != rightFolded) return Character.compare(leftFolded, rightFolded)
            leftIndex++
            rightIndex++
        }

        int lengthOrder = Integer.compare(left.length(), right.length())
        return lengthOrder != 0 ? lengthOrder : left.compareTo(right)
    }
}


// A modal dialog keeps the Fiji script alive while still running a responsive Swing event loop.
def launch = {
    new TcpPathSenderUi().showModal()
}
if (SwingUtilities.isEventDispatchThread()) launch.call()
else SwingUtilities.invokeAndWait(launch as Runnable)
