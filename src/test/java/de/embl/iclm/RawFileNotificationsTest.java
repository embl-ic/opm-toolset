package de.embl.iclm;

import static org.junit.Assert.*;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** The publisher is independent of Swing, filesystem access and network services. */
public class RawFileNotificationsTest {
    @Test public void acceptedPathsReachSubscribersWithoutBeingOpenedAndBadSubscribersAreIsolated() {
        AtomicInteger received = new AtomicInteger();
        File absent = new File("missing/Position0001_Time000001_Channel0001_Frames_1_12.tiff");
        try (RawFileNotifications.Subscription bad = RawFileNotifications.subscribe(f -> { throw new IllegalStateException("test"); });
             RawFileNotifications.Subscription good = RawFileNotifications.subscribe(f -> {
                 assertSame(absent, f); received.incrementAndGet();
             })) {
            RawFileNotifications.accepted(absent);
            assertEquals(1, received.get());
        }
        RawFileNotifications.accepted(absent);
        assertEquals(1, received.get());
    }

    @Test public void aPublisherWithAnOlderListCannotCallAnUnsubscribedViewer() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger received = new AtomicInteger();
        try (RawFileNotifications.Subscription first = RawFileNotifications.subscribe(f -> {
                 entered.countDown();
                 try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                 catch (InterruptedException e) { throw new IllegalStateException(e); }
             });
             RawFileNotifications.Subscription last = RawFileNotifications.subscribe(f -> received.incrementAndGet())) {
            Thread producer = new Thread(() -> RawFileNotifications.accepted(new File("test")));
            producer.start();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                last.close();
            } finally { release.countDown(); }
            producer.join(5000);
            assertFalse(producer.isAlive());
            assertEquals(0, received.get());
        }
    }

    @Test public void subscriptionCloseIsIdempotentAndNothingIsReplayedToNewSubscribers() {
        int before = RawFileNotifications.subscriberCount();
        RawFileNotifications.accepted(new File("before"));
        AtomicInteger received = new AtomicInteger();
        RawFileNotifications.Subscription s = RawFileNotifications.subscribe(f -> received.incrementAndGet());
        assertEquals(0, received.get());
        assertEquals(before + 1, RawFileNotifications.subscriberCount());
        s.close(); s.close();
        assertEquals(before, RawFileNotifications.subscriberCount());
    }
}
