package de.embl.iclm;

import java.io.File;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-process hints from Live2 after its existing readiness gate accepts a file (C2.7).
 * No history, executor or socket is owned here. Subscribers only enqueue a hint; they must not
 * read files, wait for Swing or do processing on the producer's thread. A hint is never proof
 * of completion: each viewer discovers and validates the file under its own publication policy.
 */
final class RawFileNotifications {
    private static final CopyOnWriteArrayList<Subscription> listeners = new CopyOnWriteArrayList<Subscription>();

    private RawFileNotifications() {}

    static Subscription subscribe(Consumer<File> listener) {
        if (listener == null) throw new NullPointerException("listener");
        Subscription subscription = new Subscription(listener);
        listeners.add(subscription);
        return subscription;
    }

    static void accepted(File file) {
        if (file == null) return;
        for (Subscription subscription : listeners) subscription.deliver(file);
    }

    static int subscriberCount() { return listeners.size(); }

    static final class Subscription implements AutoCloseable {
        private Consumer<File> listener;

        private Subscription(Consumer<File> listener) { this.listener = listener; }

        private synchronized void deliver(File file) {
            if (listener == null) return;
            try {
                listener.accept(file);
            } catch (RuntimeException failure) {
                // A viewer cannot change Live2's acceptance, retry or processing outcome.
                System.err.println("Online Deskew View file hint: " + failure);
            }
        }

        /** Once this returns, even a publisher holding an older subscriber list cannot call us. */
        @Override public synchronized void close() {
            listener = null;
            listeners.remove(this);
        }
    }
}
