package io.github.origingate.core.lists;

import io.github.origingate.core.TestSupport;
import io.github.origingate.core.net.Addresses;
import io.github.origingate.core.storage.SqlIpStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessListsTest {
    private static final UUID KAI = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final InetAddress HOME = Addresses.parse("198.51.100.20").orElseThrow();
    @TempDir Path directory;

    private static ListEntry entry(ListEntry.Kind kind, String target, Instant expiresAt, Instant addedAt) {
        return new ListEntry(kind, target, expiresAt, null, "console", addedAt);
    }

    private static ListEntry entry(ListEntry.Kind kind, String target) {
        return entry(kind, target, null, TestSupport.NOW);
    }

    private static AccessLists load(ListTable table, TestSupport.MutableClock clock) {
        return AccessLists.load(table, true, clock, Runnable::run, new TestSupport.RecordingLog());
    }

    @Test void sharedTableAppearsOnSecondServer() throws Exception {
        Path file = directory.resolve("shared.db");
        TestSupport.MutableClock clock = new TestSupport.MutableClock(TestSupport.NOW);
        AccessLists first = load(SqlIpStorage.sqlite(file), clock);
        AccessLists second = load(SqlIpStorage.sqlite(file), clock);

        first.add(entry(ListEntry.Kind.DENY, "kai")).get();
        assertTrue(second.match("Kai", KAI, HOME).isEmpty());

        clock.advance(Duration.ofSeconds(31));
        // With a direct executor the re-read may finish inside this call or the next one.
        second.match("Kai", KAI, HOME);
        assertTrue(second.match("Kai", KAI, HOME).isPresent());
    }

    @Test void survivesReload() throws Exception {
        SqlIpStorage storage = SqlIpStorage.sqlite(directory.resolve("lists.db"));
        TestSupport.MutableClock clock = new TestSupport.MutableClock(TestSupport.NOW);
        load(storage, clock).add(entry(ListEntry.Kind.ALLOW, "kai")).get();
        AccessLists again = load(storage, clock);
        assertEquals(ListEntry.Kind.ALLOW, again.match("Kai", KAI, HOME).orElseThrow().kind());
    }

    @Test void expiresOnTime() throws Exception {
        TestSupport.MutableClock clock = new TestSupport.MutableClock(TestSupport.NOW);
        AccessLists lists = load(ListTable.memory(), clock);
        lists.add(entry(ListEntry.Kind.DENY, "kai", TestSupport.NOW.plus(Duration.ofHours(1)), TestSupport.NOW)).get();
        clock.advance(Duration.ofMinutes(59));
        assertTrue(lists.match("Kai", KAI, HOME).isPresent());
        assertEquals(1, lists.count(ListEntry.Kind.DENY));
        clock.advance(Duration.ofMinutes(1));
        assertTrue(lists.match("Kai", KAI, HOME).isEmpty());
        assertEquals(0, lists.count(ListEntry.Kind.DENY));
        assertTrue(lists.active(ListEntry.Kind.DENY).get().isEmpty());
    }

    @Test void playerEntryBeatsAddressEntry() throws Exception {
        AccessLists lists = AccessLists.empty();
        lists.add(entry(ListEntry.Kind.ALLOW, "198.51.100.0/24")).get();
        lists.add(entry(ListEntry.Kind.DENY, "kai")).get();
        assertEquals(ListEntry.Kind.DENY, lists.match("Kai", KAI, HOME).orElseThrow().kind());
        assertEquals(ListEntry.Kind.ALLOW, lists.match("Sam", UUID.randomUUID(), HOME).orElseThrow().kind());
    }

    @Test void allowWinsOverlappingRanges() throws Exception {
        AccessLists lists = AccessLists.empty();
        lists.add(entry(ListEntry.Kind.DENY, "198.51.100.20")).get();
        lists.add(entry(ListEntry.Kind.ALLOW, "198.51.100.0/24")).get();
        assertEquals("198.51.100.0/24", lists.match("Sam", UUID.randomUUID(), HOME).orElseThrow().target());
    }

    @Test void oneTargetLivesOnOneList() throws Exception {
        AccessLists lists = AccessLists.empty();
        lists.add(entry(ListEntry.Kind.ALLOW, "kai")).get();
        lists.add(entry(ListEntry.Kind.DENY, "kai")).get();
        assertEquals(0, lists.count(ListEntry.Kind.ALLOW));
        assertEquals(List.of("kai"), lists.targets(ListEntry.Kind.DENY));
        assertFalse(lists.remove(ListEntry.Kind.ALLOW, "kai").get());
        assertTrue(lists.remove(ListEntry.Kind.DENY, "kai").get());
        assertTrue(lists.match("Kai", KAI, HOME).isEmpty());
    }

    @Test void activeListsOldestFirst() throws Exception {
        AccessLists lists = AccessLists.empty();
        lists.add(entry(ListEntry.Kind.DENY, "sam", null, TestSupport.NOW.plusSeconds(10))).get();
        lists.add(entry(ListEntry.Kind.DENY, "kai", null, TestSupport.NOW)).get();
        lists.add(entry(ListEntry.Kind.ALLOW, "lee", null, TestSupport.NOW)).get();
        List<ListEntry> deny = lists.active(ListEntry.Kind.DENY).get();
        assertEquals("kai", deny.get(0).target());
        assertEquals("sam", deny.get(1).target());
        assertEquals(2, deny.size());
    }

    @Test void unreadableTableStartsEmptyAndRetries() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        ListEntry stored = entry(ListEntry.Kind.DENY, "kai");
        ListTable flaky = new ListTable() {
            @Override public List<ListEntry> all() throws SQLException {
                if (reads.getAndIncrement() == 0) throw new SQLException("down (test)");
                return List.of(stored);
            }
            @Override public void put(ListEntry entry) { }
            @Override public int remove(ListEntry.Kind kind, String target) { return 0; }
            @Override public int deleteExpired(Instant now) { return 0; }
        };
        TestSupport.MutableClock clock = new TestSupport.MutableClock(TestSupport.NOW);
        TestSupport.RecordingLog log = new TestSupport.RecordingLog();
        AccessLists lists = AccessLists.load(flaky, true, clock, Runnable::run, log);
        assertEquals(0, lists.count(ListEntry.Kind.DENY));
        assertEquals(1, log.warnings.size());
        lists.match("Kai", KAI, HOME);
        assertEquals(1, reads.get());

        clock.advance(Duration.ofSeconds(31));
        assertTrue(lists.match("Kai", KAI, HOME).isPresent());
        assertEquals(1, lists.count(ListEntry.Kind.DENY));
        assertEquals(1, log.warnings.size());
    }

    /** A table whose calls throw what the test says; {@code failAll}/{@code failPut} pick which. */
    private static final class BrokenTable implements ListTable {
        final AtomicInteger reads = new AtomicInteger();
        final ListTable inner = ListTable.memory();
        volatile Error allError;
        volatile Error putError;

        @Override public List<ListEntry> all() throws SQLException {
            reads.incrementAndGet();
            if (allError != null) throw allError;
            return inner.all();
        }
        @Override public void put(ListEntry entry) throws SQLException {
            if (putError != null) throw putError;
            inner.put(entry);
        }
        @Override public int remove(ListEntry.Kind kind, String target) throws SQLException { return inner.remove(kind, target); }
        @Override public int deleteExpired(Instant now) throws SQLException { return inner.deleteExpired(now); }
    }

    @Test void driverErrorOnLoadStartsEmptyAndRetries() throws Exception {
        BrokenTable table = new BrokenTable();
        table.inner.put(entry(ListEntry.Kind.DENY, "kai"));
        table.allError = new LinkageError("driver (test)");
        TestSupport.MutableClock clock = new TestSupport.MutableClock(TestSupport.NOW);
        TestSupport.RecordingLog log = new TestSupport.RecordingLog();
        AccessLists lists = AccessLists.load(table, true, clock, Runnable::run, log);
        assertEquals(0, lists.count(ListEntry.Kind.DENY));
        assertEquals(1, log.warnings.size());

        table.allError = null;
        clock.advance(Duration.ofSeconds(31));
        assertTrue(lists.match("Kai", KAI, HOME).isPresent());
    }

    @Test void failedRereadWaitsBeforeTryingAgain() {
        BrokenTable table = new BrokenTable();
        TestSupport.MutableClock clock = new TestSupport.MutableClock(TestSupport.NOW);
        AccessLists lists = AccessLists.load(table, true, clock, Runnable::run, new TestSupport.RecordingLog());
        table.allError = new java.util.ServiceConfigurationError("driver (test)");
        clock.advance(Duration.ofSeconds(31));
        int before = table.reads.get();
        for (int i = 0; i < 5; i++) lists.match("Kai", KAI, HOME);
        assertEquals(before + 1, table.reads.get());
        clock.advance(Duration.ofSeconds(31));
        lists.match("Kai", KAI, HOME);
        assertEquals(before + 2, table.reads.get());
    }

    @Test void driverErrorOnWriteCompletesExceptionally() {
        BrokenTable table = new BrokenTable();
        table.putError = new LinkageError("driver (test)");
        AccessLists lists = AccessLists.load(table, true, new TestSupport.MutableClock(TestSupport.NOW), Runnable::run, new TestSupport.RecordingLog());
        java.util.concurrent.CompletableFuture<Void> added = lists.add(entry(ListEntry.Kind.DENY, "kai"));
        assertTrue(added.isCompletedExceptionally());
    }

    @Test void savedEntryCompletesEvenWhenTheRereadFails() throws Exception {
        BrokenTable table = new BrokenTable();
        TestSupport.RecordingLog log = new TestSupport.RecordingLog();
        AccessLists lists = AccessLists.load(table, true, new TestSupport.MutableClock(TestSupport.NOW), Runnable::run, log);
        table.allError = new LinkageError("driver (test)");
        lists.add(entry(ListEntry.Kind.DENY, "kai")).get();
        assertTrue(lists.remove(ListEntry.Kind.DENY, "kai").get());
        assertEquals(1, log.warnings.size());
        assertEquals(0, lists.count(ListEntry.Kind.DENY));
    }

    @Test void memoryOnlyListsNeverReread() throws Exception {
        TestSupport.MutableClock clock = new TestSupport.MutableClock(TestSupport.NOW);
        AtomicInteger reads = new AtomicInteger();
        ListTable counting = new ListTable() {
            private final ListTable inner = ListTable.memory();
            @Override public List<ListEntry> all() throws SQLException {
                reads.incrementAndGet();
                return inner.all();
            }
            @Override public void put(ListEntry entry) throws SQLException { inner.put(entry); }
            @Override public int remove(ListEntry.Kind kind, String target) throws SQLException { return inner.remove(kind, target); }
            @Override public int deleteExpired(Instant now) throws SQLException { return inner.deleteExpired(now); }
        };
        AccessLists lists = AccessLists.load(counting, false, clock, Runnable::run, new TestSupport.RecordingLog());
        clock.advance(Duration.ofMinutes(5));
        lists.match("Kai", KAI, HOME);
        assertEquals(1, reads.get());
        assertFalse(lists.persistent());
    }

    @Test void refreshShowsAnotherServersEntryWithoutAnyEarlierMatch() throws Exception {
        Path file = directory.resolve("shared.db");
        TestSupport.MutableClock clock = new TestSupport.MutableClock(TestSupport.NOW);
        AccessLists first = load(SqlIpStorage.sqlite(file), clock);
        AccessLists second = load(SqlIpStorage.sqlite(file), clock);
        first.add(entry(ListEntry.Kind.DENY, "kai")).get();
        // A quiet server: time passes with no login, then the timer fires.
        clock.advance(Duration.ofSeconds(5));
        second.refresh();
        assertTrue(second.match("Kai", KAI, HOME).isPresent());
    }

    @Test void memoryListsKeepEntriesAcrossAReload() throws Exception {
        TestSupport.MutableClock clock = new TestSupport.MutableClock(TestSupport.NOW);
        AccessLists before = AccessLists.load(ListTable.memory(), false, clock, Runnable::run, new TestSupport.RecordingLog());
        before.add(entry(ListEntry.Kind.DENY, "kai")).get();
        AccessLists after = AccessLists.load(ListTable.memory(), false, clock, Runnable::run, new TestSupport.RecordingLog(), before);
        assertTrue(after.match("Kai", KAI, HOME).isPresent());
        after.add(entry(ListEntry.Kind.ALLOW, "sam")).get();
        assertEquals(2, after.targets(ListEntry.Kind.DENY).size() + after.targets(ListEntry.Kind.ALLOW).size());
    }

    @Test void unreadableTableKeepsThePreviousCopy() throws Exception {
        TestSupport.MutableClock clock = new TestSupport.MutableClock(TestSupport.NOW);
        BrokenTable good = new BrokenTable();
        AccessLists before = load(good, clock);
        before.add(entry(ListEntry.Kind.DENY, "kai")).get();
        BrokenTable down = new BrokenTable();
        down.allError = new LinkageError();
        TestSupport.RecordingLog log = new TestSupport.RecordingLog();
        AccessLists after = AccessLists.load(down, true, clock, Runnable::run, log, before);
        assertTrue(after.match("Kai", KAI, HOME).isPresent());
        assertEquals(1, log.warnings.size());
        assertTrue(log.warnings.get(0).contains("LinkageError") && log.warnings.get(0).contains("Using the entries from before the reload"), log.warnings.get(0));
        assertFalse(log.warnings.get(0).contains("null"));

        TestSupport.RecordingLog emptyLog = new TestSupport.RecordingLog();
        AccessLists fresh = AccessLists.load(down, true, clock, Runnable::run, emptyLog);
        assertEquals(0, fresh.count(ListEntry.Kind.DENY));
        assertTrue(emptyLog.warnings.get(0).contains("Entries do not apply until the database answers"), emptyLog.warnings.get(0));
        assertFalse(emptyLog.warnings.get(0).contains("out of date"));
    }

    @Test void databaseKnownToBeDownIsNotReadAtStartup() throws Exception {
        BrokenTable table = new BrokenTable();
        TestSupport.MutableClock clock = new TestSupport.MutableClock(TestSupport.NOW);
        AccessLists lists = AccessLists.load(table, true, clock, Runnable::run, new TestSupport.RecordingLog(), null, false);
        assertEquals(0, table.reads.get());
        table.inner.put(entry(ListEntry.Kind.DENY, "kai"));
        lists.refresh();
        assertTrue(lists.match("Kai", KAI, HOME).isPresent());
    }
}
