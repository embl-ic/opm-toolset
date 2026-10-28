package de.embl.iclm;

import static org.junit.Assert.*;
import static de.embl.iclm.OnlineDeskewLiveGuiTest.*;

import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.io.FileWriter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.swing.JCheckBox;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** M3-B: follow validated arrivals in both modes, independently of the movie clock. */
public class OnlineDeskewFollowLatestGuiTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private OnlineDeskewSession session;
    private int subscribers;

    @Before public void display() {
        Assume.assumeTrue("Enable with -Dopm.test.gui=true", Boolean.getBoolean("opm.test.gui"));
        RawAcquisition.eventThreadFileAccessForTest.set(0);
        subscribers = RawFileNotifications.subscriberCount();
    }

    @After public void close() throws Exception {
        if (session != null) {
            session.close();
            assertTrue(session.closed().get(15, TimeUnit.SECONDS).drained);
        }
        RawAcquisition.quietMsForTest = -1;
        OnlineDeskewSession.checkerHookForTest = null;
        RawFrameRenderer.rowHookForTest = null;
        if (session != null) assertEquals("close unsubscribes", subscribers, RawFileNotifications.subscriberCount());
        assertEquals("no catalogue IO on Swing", 0, RawAcquisition.eventThreadFileAccessForTest.get());
    }

    private File complete(int time) throws Exception {
        return RawTiffFixture.write(new File(folder.getRoot(), name(time)), W, H, D, ROWS, D, values(time));
    }

    private void open() throws Exception {
        try (FileWriter w = new FileWriter(new File(folder.getRoot(), "ExperimentalParameters.txt"))) {
            w.write("Interval: 5 Minute\nTilt angle: 33.5\num per galvo DU: 0.01325\nGalvo DUs per step: 20\n"
                    + "Pixel size at object /um: 0.116\nCamera ROI X0, X1, Y0, Y1: 0 64 0 40\n");
        }
        OnlineDeskewView.Settings s = new OnlineDeskewView.Settings();
        s.folder = folder.getRoot().getAbsolutePath();
        s.budgetMb = 64;
        session = OnlineDeskewSession.open(s);
    }

    private static JCheckBox findFollow(Component c) {
        if (c instanceof JCheckBox && "Follow latest".equals(((JCheckBox) c).getText())) return (JCheckBox) c;
        if (c instanceof Container) for (Component child : ((Container) c).getComponents()) {
            JCheckBox found = findFollow(child);
            if (found != null) return found;
        }
        return null;
    }

    private JCheckBox follow() {
        JCheckBox c = findFollow(session.frame());
        assertNotNull("Follow latest must be a visible control", c);
        return c;
    }

    @Test public void followLatestIsAnExplicitInitiallyOffControl() throws Exception {
        complete(1);
        open();
        edtRun(() -> assertFalse(follow().isSelected()));
    }

    @Test public void live2HasAnInProcessSubscriptionSeam() throws Exception {
        Class.forName("de.embl.iclm.RawFileNotifications");
    }

    private void waitOnSwing(String what, BooleanSupplier condition) throws Exception {
        await(what, 15000, () -> {
            try { return edt(() -> condition.getAsBoolean()); }
            catch (Exception ex) { throw new IllegalStateException(ex); }
        });
    }

    private int shown() { return session.snapshot().time(session.handle().getViewerPanel().state().getCurrentTimepoint()).timeId; }

    private int requested() {
        try {
            java.lang.reflect.Method m = OnlineDeskewSession.class.getDeclaredMethod("requestedTimepoint");
            m.setAccessible(true);
            return (Integer) m.invoke(session);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void shown(int id, RawPlayback.Mode mode) throws Exception {
        waitOnSwing("shown time " + id + " in " + mode, () -> shown() == id &&
                (mode != RawPlayback.Mode.COMPLETED || session.presenter().shownRequest() != null
                 && session.presenter().shownRequest().entry.time.timeId == id));
        edtRun(() -> assertTrue("programmatic movement retains Follow latest", follow().isSelected()));
    }

    @Test public void progressiveFollowsOnlyCompleteArrivalsAndDoesNotGoBackForLateFiles() throws Exception {
        growing(RawPlayback.Mode.PROGRESSIVE);
    }

    @Test public void completedFollowsThroughTheGateAndDoesNotGoBackForLateFiles() throws Exception {
        growing(RawPlayback.Mode.COMPLETED);
    }

    private void growing(RawPlayback.Mode mode) throws Exception {
        complete(1);
        complete(3);
        open();
        edtRun(() -> { session.playback().setMode(mode); follow().doClick(); });
        shown(3, mode);
        File fifth = new File(folder.getRoot(), name(5));
        RawTiffFixture.write(fifth, W, H, D, ROWS, 4, values(5));
        RawFileNotifications.accepted(fifth);
        await("underfilled arrival checked", 15000, () -> {
            RawAcquisition.Entry e = session.acquisition().entry(new RawAcquisition.TimeKey(1, 5), 1);
            return e != null && e.readiness() == RawAcquisition.Readiness.INCOMPLETE;
        });
        edtRun(() -> assertEquals("a hint cannot publish four of twelve planes", 3, shown()));
        complete(5);
        RawFileNotifications.accepted(fifth);
        shown(5, mode);
        complete(2);
        waitOnSwing("late insertion", () -> session.snapshot().size() == 4);
        shown(5, mode);
        edtRun(() -> assertFalse(session.playback().isPlaying()));
    }

    @Test public void manualSeekAndEndpointStepTurnFollowingOffVisiblyInBothModes() throws Exception {
        complete(1); complete(2); open();
        for (RawPlayback.Mode mode : RawPlayback.Mode.values()) {
            edtRun(() -> { session.playback().setMode(mode); session.setFollowLatest(true); });
            shown(2, mode);
            edtRun(() -> {
                session.handle().getViewerPanel().setTimepoint(0);
                assertFalse(follow().isSelected());
                assertFalse(session.playback().isPlaying());
            });
            edtRun(() -> session.setFollowLatest(true));
            shown(2, mode);
            edtRun(() -> { session.playback().step(1); assertFalse(follow().isSelected()); });
        }
        complete(3);
        waitOnSwing("arrival after manual release", () -> session.snapshot().size() == 3 && session.snapshot().entry(2).viewable());
        edtRun(() -> assertEquals("manual release holds the prior time", 2, shown()));
    }

    @Test public void playDisablesFollowAndFollowPausesCompletedPlaybackWhileLoopIsRetained() throws Exception {
        complete(1); complete(2); complete(3); open();
        edtRun(() -> {
            session.playback().setMode(RawPlayback.Mode.COMPLETED);
            session.playback().setLoop(true);
            session.setFollowLatest(true);
        });
        shown(3, RawPlayback.Mode.COMPLETED);
        edtRun(() -> { session.playback().play(); assertFalse(follow().isSelected()); });
        waitOnSwing("completed movie has presented", () -> session.playback().stats().presented >= 3);
        complete(4);
        waitOnSwing("arrival while movie plays", () -> session.snapshot().size() == 4 && session.snapshot().entry(3).viewable());
        edtRun(() -> {
            assertTrue(session.playback().isPlaying());
            assertFalse(follow().isSelected());
            follow().doClick();
            assertFalse(session.playback().isPlaying());
            assertFalse(session.playback().hasScheduledAdvance());
            assertTrue(session.playback().isLooping());
        });
        shown(4, RawPlayback.Mode.COMPLETED);
        complete(5);
        shown(5, RawPlayback.Mode.COMPLETED);
        edtRun(() -> {
            session.playback().setMode(RawPlayback.Mode.PROGRESSIVE);
            assertTrue(follow().isSelected());
            assertFalse(session.playback().isPlaying());
        });
        complete(6);
        shown(6, RawPlayback.Mode.PROGRESSIVE);
        edtRun(() -> session.playback().setMode(RawPlayback.Mode.COMPLETED));
        shown(6, RawPlayback.Mode.COMPLETED);
    }

    @Test public void fakeHintsBypassQuietButNotDepthAndDuplicatesDoNotRepublish() throws Exception {
        complete(1); open();
        RawAcquisition.quietMsForTest = 60000;
        edtRun(() -> follow().doClick());
        File second = complete(2);
        RawFileNotifications.accepted(new File(folder.getRoot(), "." + File.separator + second.getName()));
        shown(2, RawPlayback.Mode.PROGRESSIVE);
        RawAcquisition.Entry e = session.acquisition().entry(new RawAcquisition.TimeKey(1, 2), 1);
        RawAcquisition.Entry.Shown publication = e.shown();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), drained = new CountDownLatch(1);
        AtomicBoolean blockOnce = new AtomicBoolean(true);
        OnlineDeskewSession.checkerHookForTest = () -> {
            if (blockOnce.getAndSet(false)) {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            } else drained.countDown();
        };
        RawFileNotifications.accepted(second);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        long reads = RawAcquisition.attributeReadsForTest.get();
        try { for (int i = 0; i < 1000; i++) RawFileNotifications.accepted(second); }
        finally { release.countDown(); }
        assertTrue(drained.await(5, TimeUnit.SECONDS));
        assertSame("duplicate hints keep the publication", publication, e.shown());
        assertTrue("queued hints coalesce", RawAcquisition.attributeReadsForTest.get() - reads < 100);
        File unknownDepth = new File(folder.getRoot(), "g_Position0001_Time000003_Channel0001.tiff");
        RawTiffFixture.write(unknownDepth, W, H, D, ROWS, D, values(3));
        RawFileNotifications.accepted(unknownDepth);
        await("unverified depth", 15000, () -> {
            RawAcquisition.Entry x = session.acquisition().entry(new RawAcquisition.TimeKey(1, 3), 1);
            return x != null && x.readiness() == RawAcquisition.Readiness.DEPTH_UNVERIFIED;
        });
        edtRun(() -> assertEquals(2, shown()));
    }

    @Test public void aBurstSupersedesItsPendingTargetAndManualSeekWinsBeforePresentation() throws Exception {
        complete(1); complete(2); open();
        edtRun(() -> { session.playback().setMode(RawPlayback.Mode.COMPLETED); follow().doClick(); });
        shown(2, RawPlayback.Mode.COMPLETED);
        CountDownLatch drawing = new CountDownLatch(1), release = new CountDownLatch(1);
        RawFrameRenderer.rowHookForTest = y -> {
            drawing.countDown();
            try { release.await(15, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        try {
            RawFileNotifications.accepted(complete(3));
            assertTrue(drawing.await(10, TimeUnit.SECONDS));
            RawFileNotifications.accepted(complete(4));
            waitOnSwing("newest target replaces a drawing target", () -> requested() == 3);
            edtRun(() -> {
                assertEquals("pending frames have not moved the slider", 2, shown());
                session.handle().getViewerPanel().setTimepoint(0);
                assertFalse(follow().isSelected());
            });
        } finally { RawFrameRenderer.rowHookForTest = null; release.countDown(); }
        waitOnSwing("manual target presented", () -> session.presenter().shownRequest() != null &&
                session.presenter().shownRequest().entry.time.timeId == 1 && shown() == 1);
        RawFileNotifications.accepted(complete(5));
        waitOnSwing("fifth arrival checked", () -> session.snapshot().size() == 5 && session.snapshot().entry(4).viewable());
        edtRun(() -> { assertEquals(1, shown()); assertFalse(follow().isSelected()); });
    }

    @Test public void aReplacementOfTheLatestTimeIsPresentedAsANewPublication() throws Exception {
        complete(1); complete(2); open();
        edtRun(() -> { session.playback().setMode(RawPlayback.Mode.COMPLETED); follow().doClick(); });
        shown(2, RawPlayback.Mode.COMPLETED);
        RawAcquisition.Entry e = session.snapshot().entry(1);
        RawAcquisition.Entry.Shown old = e.shown();
        replace(e, 7);
        RawFileNotifications.accepted(e.file);
        waitOnSwing("new version presented", () -> session.presenter().shownRequest() != null &&
                session.presenter().shownRequest().publication != old &&
                session.presenter().shownRequest().publication == e.shown());
        shown(2, RawPlayback.Mode.COMPLETED);
    }

    @Test public void anotherFolderIsIgnoredAndCloseRemovesTheSubscription() throws Exception {
        complete(1); open();
        assertEquals(subscribers + 1, RawFileNotifications.subscriberCount());
        File other = folder.newFolder("another");
        RawFileNotifications.accepted(RawTiffFixture.write(new File(other, name(9)), W, H, D, ROWS, D, values(9)));
        edtRun(() -> assertEquals(1, session.snapshot().size()));
        session.close();
        assertEquals(subscribers, RawFileNotifications.subscriberCount());
        RawFileNotifications.accepted(complete(2));
        assertTrue(session.closed().get(15, TimeUnit.SECONDS).drained);
        assertEquals(1, session.snapshot().size());
    }
}
