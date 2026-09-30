package io.github.origingate.bukkit;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Reconnect fallback for servers that omit async pre-login in offline mode. */
final class DeferredAdmission<T> {
    private final int capacity;
    private final Clock clock;
    private final Map<String, Entry<T>> entries = new HashMap<>();
    DeferredAdmission(int capacity, Clock clock) { this.capacity = capacity; this.clock = clock; }
    synchronized CompletableFuture<T> poll(String key, Object runtime, Supplier<CompletableFuture<T>> start) {
        expire();
        Entry<T> entry = entries.get(key);
        if (entry != null && entry.runtime == runtime) {
            if (entry.future.isDone()) entries.remove(key);
            return entry.future;
        }
        entries.remove(key);
        if (entries.size() >= capacity) {
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("Too many connection checks are pending"));
            return failed;
        }
        CompletableFuture<T> future = start.get();
        if (!future.isDone()) entries.put(key, new Entry<>(runtime, future, clock.millis()));
        return future;
    }
    synchronized void expire() { entries.values().removeIf(e -> clock.millis() - e.created > 120_000); }
    synchronized void clear() { entries.clear(); }
    private static final class Entry<T> {
        final Object runtime;
        final CompletableFuture<T> future;
        final long created;
        Entry(Object runtime, CompletableFuture<T> future, long created) {
            this.runtime = runtime; this.future = future; this.created = created;
        }
    }
}
