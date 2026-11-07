package io.github.origingate.core.lists;

import io.github.origingate.core.Log;
import io.github.origingate.core.util.Compat;

import java.net.InetAddress;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The allow and deny lists, kept in memory so a login never waits on the database. The copy is read again in the
 * background when it is older than {@link #REFRESH}, so entries added on another server that shares the database
 * apply within about that time. Commands on this server re-read at once.
 */
public final class AccessLists {
    public static final Duration REFRESH = Duration.ofSeconds(30);

    private final ListTable table;
    private final boolean persistent;
    private final Clock clock;
    private final Executor executor;
    private final Log log;
    private final AtomicBoolean reading = new AtomicBoolean();
    private final AtomicLong started = new AtomicLong();
    private final Object apply = new Object();
    private long applied;
    private volatile boolean failing;
    private volatile List<ListEntry> entries = Compat.list();
    private volatile Instant readAt;

    private AccessLists(ListTable table, boolean persistent, Clock clock, Executor executor, Log log) {
        this.table = table;
        this.persistent = persistent;
        this.clock = clock;
        this.executor = executor;
        this.log = log;
        this.readAt = clock.instant();
    }

    /** Reads the table now. When it cannot be read, starts empty, logs one warning, and tries again when stale. */
    public static AccessLists load(ListTable table, boolean persistent, Clock clock, Executor executor, Log log) {
        AccessLists lists = new AccessLists(table, persistent, clock, executor, log);
        lists.readNow();
        return lists;
    }

    /** Lists that live in memory only. */
    public static AccessLists empty() {
        return new AccessLists(ListTable.memory(), false, Clock.systemUTC(), Runnable::run, Log.NONE);
    }

    public boolean persistent() { return persistent; }

    /** The entry that decides this login, if any. Never blocks. A player entry beats an address entry; allow beats deny. */
    public Optional<ListEntry> match(String username, UUID uuid, InetAddress address) {
        refreshIfStale();
        Instant now = clock.instant();
        ListEntry best = null;
        int bestRank = -1;
        for (ListEntry entry : entries) {
            if (!entry.activeAt(now)) continue;
            boolean hit = entry.isAddress() ? entry.matchesAddress(address) : entry.matchesPlayer(username, uuid);
            if (!hit) continue;
            int rank = (entry.isAddress() ? 0 : 2) + (entry.kind() == ListEntry.Kind.ALLOW ? 1 : 0);
            if (rank > bestRank) {
                best = entry;
                bestRank = rank;
            }
        }
        return Optional.ofNullable(best);
    }

    public CompletableFuture<Void> add(ListEntry entry) {
        return submit(() -> {
            table.put(entry);
            readOrThrow();
            return null;
        });
    }

    /** Completes with true when an entry was removed. */
    public CompletableFuture<Boolean> remove(ListEntry.Kind kind, String target) {
        return submit(() -> {
            int removed = table.remove(kind, target);
            readOrThrow();
            return removed > 0;
        });
    }

    /** A fresh read of the unexpired entries of one list, oldest first. */
    public CompletableFuture<List<ListEntry>> active(ListEntry.Kind kind) {
        return submit(() -> {
            Instant now = clock.instant();
            List<ListEntry> result = new ArrayList<>();
            for (ListEntry entry : readOrThrow()) if (entry.kind() == kind && entry.activeAt(now)) result.add(entry);
            result.sort(Comparator.comparing(ListEntry::addedAt));
            return result;
        });
    }

    public int deleteExpired() throws SQLException {
        return table.deleteExpired(clock.instant());
    }

    public int count(ListEntry.Kind kind) {
        Instant now = clock.instant();
        int count = 0;
        for (ListEntry entry : entries) if (entry.kind() == kind && entry.activeAt(now)) count++;
        return count;
    }

    public List<String> targets(ListEntry.Kind kind) {
        Instant now = clock.instant();
        List<String> targets = new ArrayList<>();
        for (ListEntry entry : entries) if (entry.kind() == kind && entry.activeAt(now)) targets.add(entry.target());
        return targets;
    }

    private void refreshIfStale() {
        if (!persistent || clock.instant().isBefore(readAt.plus(REFRESH))) return;
        if (!reading.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    readNow();
                } finally {
                    reading.set(false);
                }
            });
        } catch (RejectedExecutionException ex) {
            reading.set(false);
        }
    }

    /** Reads the table. A failure is logged once until a read works again, and the next try waits for the next refresh. */
    private void readNow() {
        try {
            readOrThrow();
            failing = false;
        } catch (SQLException ex) {
            readAt = clock.instant();
            if (!failing) log.warn("The allow and deny lists could not be read, so they may be out of date: " + ex.getMessage(), null);
            failing = true;
        }
    }

    private List<ListEntry> readOrThrow() throws SQLException {
        long order = started.incrementAndGet();
        Instant at = clock.instant();
        List<ListEntry> read = Compat.listCopy(table.all());
        // A slow read that started before a newer one finished must not replace the newer copy.
        synchronized (apply) {
            if (order > applied) {
                applied = order;
                entries = read;
                readAt = at;
            }
        }
        return read;
    }

    private <T> CompletableFuture<T> submit(SqlTask<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(task.run());
                } catch (SQLException | RuntimeException ex) {
                    future.completeExceptionally(ex);
                }
            });
        } catch (RejectedExecutionException ex) {
            future.completeExceptionally(ex);
        }
        return future;
    }

    private interface SqlTask<T> { T run() throws SQLException; }
}
