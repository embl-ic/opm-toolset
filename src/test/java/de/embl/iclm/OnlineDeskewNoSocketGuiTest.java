package de.embl.iclm;

import static org.junit.Assert.*;
import static de.embl.iclm.OnlineDeskewLiveGuiTest.*;
import java.io.File;
import java.security.Permission;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Java 8 enforcement: a standalone viewer and fake hint producer cannot listen or connect. */
@SuppressWarnings("removal")
public class OnlineDeskewNoSocketGuiTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void openingFollowingAndClosingNeverOpensASocket() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("opm.test.gui"));
        Assume.assumeTrue("Java 8 enforcement; later runtimes disable SecurityManager", System.getProperty("java.specification.version").equals("1.8"));
        SecurityManager previous = System.getSecurityManager();
        AtomicInteger sockets = new AtomicInteger();
        OnlineDeskewSession session = null;
        System.setSecurityManager(new SecurityManager() {
            @Override public void checkPermission(Permission p) {}
            @Override public void checkListen(int port) { sockets.incrementAndGet(); throw new SecurityException("viewer listen " + port); }
            @Override public void checkConnect(String host, int port) { sockets.incrementAndGet(); throw new SecurityException("viewer connect " + host); }
        });
        try {
            RawTiffFixture.write(new File(folder.getRoot(), name(1)), W, H, D, ROWS, D, values(1));
            OnlineDeskewView.Settings s = new OnlineDeskewView.Settings();
            s.folder = folder.getRoot().getAbsolutePath();
            s.parameterSource = OnlineDeskewView.PARAMETER_SOURCES[1];
            s.budgetMb = 64;
            session = OnlineDeskewSession.open(s);
            final OnlineDeskewSession view = session;
            edtRun(() -> view.setFollowLatest(true));
            RawFileNotifications.accepted(RawTiffFixture.write(new File(folder.getRoot(), name(2)), W, H, D, ROWS, D, values(2)));
            await("hint published", 10000, () -> view.snapshot().size() == 2 && view.snapshot().entry(1).viewable());
        } finally {
            if (session != null) { session.close(); assertTrue(session.closed().get(15, TimeUnit.SECONDS).drained); }
            System.setSecurityManager(previous);
        }
        assertEquals("no socket operation even attempted", 0, sockets.get());
    }
}
