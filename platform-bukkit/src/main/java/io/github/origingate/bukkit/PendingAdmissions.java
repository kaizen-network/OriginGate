package io.github.origingate.bukkit;

import java.time.Clock;
import java.util.*;

/**
 * Pre-login results waiting for PlayerLoginEvent. A login only takes a finished result, so an overlapping attempt
 * cannot hand it one that is still running. Dropped connections never log in: their results expire, and the oldest
 * finished result makes room when all slots are used.
 */
final class PendingAdmissions<T> {
    private static final long RETAIN_MILLIS = 120_000;
    private final int capacity;
    private final Clock clock;
    private final List<Entry<T>> entries = new ArrayList<>();
    PendingAdmissions(int capacity, Clock clock) { this.capacity = capacity; this.clock = clock; }
    /** False only when every slot holds a check that is still running. */
    synchronized boolean put(String key, T value) {
        expire();
        if (entries.size() >= capacity) {
            int oldest = firstFinished();
            if (oldest < 0) return false;
            entries.remove(oldest);
        }
        entries.add(new Entry<>(key, value, clock.millis()));
        return true;
    }
    synchronized void finish(String key, T value) {
        for (Entry<T> entry : entries) if (entry.value == value && entry.key.equals(key)) entry.finished = true;
    }
    /** The newest finished result for this key. */
    synchronized T take(String key) {
        expire();
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry<T> entry = entries.get(i);
            if (entry.finished && entry.key.equals(key)) return entries.remove(i).value;
        }
        return null;
    }
    synchronized void remove(String key, T expected) { entries.removeIf(e -> e.value == expected && e.key.equals(key)); }
    synchronized void expire() { entries.removeIf(e -> clock.millis() - e.created > RETAIN_MILLIS); }
    synchronized void clear() { entries.clear(); }
    private int firstFinished() {
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).finished) return i;
        return -1;
    }
    private static final class Entry<T> {
        final String key; final T value; final long created;
        boolean finished;
        Entry(String key, T value, long created) { this.key = key; this.value = value; this.created = created; }
    }
}
