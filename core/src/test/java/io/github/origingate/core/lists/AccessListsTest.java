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
}
