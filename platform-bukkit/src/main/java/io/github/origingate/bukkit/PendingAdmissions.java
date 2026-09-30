package io.github.origingate.bukkit;

import java.time.Clock;
import java.util.*;

/** One outstanding login per key. Duplicates cannot replace an earlier result. */
final class PendingAdmissions<T> {
    private static final long RETAIN_MILLIS = 120_000;
    private final int capacity;
    private final Clock clock;
    private final Map<String, Entry<T>> values = new HashMap<>();
    PendingAdmissions(int capacity, Clock clock) { this.capacity = capacity; this.clock = clock; }
    synchronized boolean put(String key, T value) {
        expire();
        if (values.size() >= capacity || values.containsKey(key)) return false;
        values.put(key, new Entry<>(value, clock.millis()));
        return true;
    }
    synchronized T take(String key) {
        Entry<T> entry = values.remove(key);
        return entry == null || clock.millis() - entry.created > RETAIN_MILLIS ? null : entry.value;
    }
    synchronized void remove(String key, T expected) {
        Entry<T> entry = values.get(key);
        if (entry != null && entry.value == expected) values.remove(key);
    }
    synchronized void expire() { values.values().removeIf(e -> clock.millis() - e.created > RETAIN_MILLIS); }
    synchronized void clear() { values.clear(); }
    private static final class Entry<T> {
        final T value; final long created;
        Entry(T value, long created) { this.value = value; this.created = created; }
    }
}
