package de.embl.iclm;

import static org.junit.Assert.*;
import static de.embl.iclm.OnlineDeskewLiveGuiTest.*;
import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.swing.JTextField;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Real Live2, CPU processing and the unmodified acquisition replay UI in one JVM, with tiny
 * synthetic raw volumes. Explicit gate: -Dopm.test.live2.script=.../tcpip_acquisition_replay.groovy,
 * -Dopm.test.gui=true, and Fiji's Groovy jars on the class path. Run with isolated preferences.
 */
public class OnlineDeskewLive2GuiTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o);
    }
    private static Object call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(o, args);
    }
    private static Object call(Object o, String name) throws Exception { return call(o, name, new Class<?>[0]); }
    private static int processed(Live2 live) {
        try { return ((Number) field(live, "processedCount")).intValue(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Test(timeout = 120000) public void replayCopiesIntoALive2RunAndItsAcceptedHintsPublishToTheFollowingViewer() throws Exception {
        String script = System.getProperty("opm.test.live2.script");
        Assume.assumeTrue("Enable the real Live2 replay explicitly", Boolean.getBoolean("opm.test.gui") && script != null);
        assertTrue(new File(script).isFile());
        File source = folder.newFolder("source"), destination = folder.newFolder("acquisition"), results = folder.newFolder("results");
        File metadata = new File(source, "ExperimentalParameters.txt");
        try (FileWriter w = new FileWriter(metadata)) {
            w.write("Interval: 5 Minute\nTilt angle: 33.5\num per galvo DU: 0.01325\nGalvo DUs per step: 20\n"
                    + "Pixel size at object /um: 0.116\nCamera ROI X0, X1, Y0, Y1: 0 64 0 40\n");
        }
        RawTiffFixture.write(new File(destination, name(1)), W, H, D, ROWS, D, values(1));
        for (int t = 2; t <= 3; t++) RawTiffFixture.write(new File(source, name(t)), W, H, D, ROWS, D, values(t));
        int port;
        try (ServerSocket free = new ServerSocket(0)) { port = free.getLocalPort(); }
        Set<String> notified = ConcurrentHashMap.newKeySet();
        int subscribers = RawFileNotifications.subscriberCount();
        Live2 live = null;
        OnlineDeskewSession viewer = null;
        Object replay = null;
        AutoCloseable loader = null;
        try (RawFileNotifications.Subscription observed = RawFileNotifications.subscribe(f -> notified.add(f.getAbsolutePath()))) {
            live = edt(() -> new Live2());
            Parameter p = (Parameter) field(live, "parameter");
            p.tryGPU = false; p.livePreview = false; p.displayResult = false;
            p.listenTcpIp = true; p.port = port; p.watchExplicitFolder = false; p.watchAnnouncedFolder = false; p.watchDir = "";
            p.outputFormat = Parameter.FORMAT_TIFF; p.saveDir = results.getAbsolutePath(); p.saveToSame = false;
            p.saveDeskewImage = true; p.saveProjectionViews = false; p.manualDeskewParameters = false;
            p.keywords = ""; p.excludeKeywords = ""; p.overwriteExist = true;
            p.channelStr = Parameter.CHANNEL_OPTIONS[0]; p.alignmFile = "";
            ChannelOperationSettings channels = (ChannelOperationSettings) field(live, "channels");
            channels.combineAcquisitionChannels = false;
            final Live2 owner = live;
            edt(() -> call(owner, "startLive"));
            assertEquals(Boolean.TRUE, field(live, "running"));
            OnlineDeskewView.Settings settings = new OnlineDeskewView.Settings();
            settings.folder = destination.getAbsolutePath(); settings.budgetMb = 64;
            settings.parameterSource = OnlineDeskewView.PARAMETER_SOURCES[1];
            viewer = OnlineDeskewSession.open(settings);
            final OnlineDeskewSession view = viewer;
            // A watcher alone cannot make this test pass within its deadline.
            RawAcquisition.quietMsForTest = 600000;
            edtRun(() -> { view.playback().setMode(RawPlayback.Mode.COMPLETED); view.setFollowLatest(true); });

            Class<?> groovy = Class.forName("groovy.lang.GroovyClassLoader");
            Object groovyLoader = groovy.getConstructor(ClassLoader.class).newInstance(getClass().getClassLoader());
            loader = (AutoCloseable) groovyLoader;
            groovy.getMethod("parseClass", File.class).invoke(groovyLoader, new File(script));
            Class<?> replayClass = ((ClassLoader) groovyLoader).loadClass("TcpPathSenderUi");
            replay = edt(() -> replayClass.getConstructor().newInstance());
            final Object sender = replay;
            edt(() -> {
                ((JTextField) field(sender, "folderField")).setText(source.getAbsolutePath());
                ((JTextField) field(sender, "destinationField")).setText(destination.getAbsolutePath());
                ((JTextField) field(sender, "portField")).setText(Integer.toString(port));
                ((JTextField) field(sender, "delayField")).setText("0.1");
                call(sender, "refreshFileList", new Class<?>[] { boolean.class, boolean.class }, false, true);
                call(sender, "showNonBlocking");
                return call(sender, "startSending");
            });
            await("Live2 accepted both copied files", 30000, () -> notified.contains(new File(destination, name(2)).getAbsolutePath())
                    && notified.contains(new File(destination, name(3)).getAbsolutePath()));
            await("both real CPU processing jobs finished", 30000, () -> processed(owner) == 2);
            await("newest frame presented by the viewer", 15000, () -> view.presenter().shownRequest() != null &&
                    view.presenter().shownRequest().entry.time.timeId == 3);
            edtRun(() -> assertEquals(3, view.snapshot().time(view.handle().getViewerPanel().state().getCurrentTimepoint()).timeId));
            assertEquals(0, ((Number) field(live, "failedCount")).intValue());
            try (java.util.stream.Stream<java.nio.file.Path> files = Files.walk(results.toPath())) {
                assertTrue("actual deskew TIFF output", files.anyMatch(f -> f.toString().endsWith(".tif") || f.toString().endsWith(".tiff")));
            }
            System.out.println("LIVE2_REPLAY_PASSED: original replay script; two accepted copies, two CPU deskew results, newest frame presented; quiet fallback disabled");
        } finally {
            if (replay != null) { final Object sender = replay; edt(() -> call(sender, "exitUi")); }
            if (viewer != null) { viewer.close(); assertTrue(viewer.closed().get(15, TimeUnit.SECONDS).drained); }
            if (live != null) { call(live, "stopLive"); final Live2 owner = live; edtRun(owner::dispose); }
            if (loader != null) loader.close();
            RawAcquisition.quietMsForTest = -1;
        }
        assertEquals("no subscriber left after close", subscribers, RawFileNotifications.subscriberCount());
    }
}
