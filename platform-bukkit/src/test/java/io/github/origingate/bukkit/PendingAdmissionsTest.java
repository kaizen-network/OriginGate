package io.github.origingate.bukkit;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PendingAdmissionsTest {
    @Test void loginOnlyTakesFinishedResult() {
        PendingAdmissions<String> pending = new PendingAdmissions<>(2, Clock.systemUTC());
        assertTrue(pending.put("same", "first"));
        assertTrue(pending.put("same", "second"));
        assertNull(pending.take("same"), "a running check must not be handed to a login");
        pending.finish("same", "first");
        assertEquals("first", pending.take("same"));
        assertNull(pending.take("same"));
    }
    @Test void droppedConnectionDoesNotBlockRetry() {
        PendingAdmissions<String> pending = new PendingAdmissions<>(2, Clock.systemUTC());
        assertTrue(pending.put("player", "dropped"));
        pending.finish("player", "dropped");
        assertTrue(pending.put("player", "retry"));
        pending.finish("player", "retry");
        assertEquals("retry", pending.take("player"));
    }
    @Test void fullSlotsEvictOldestFinishedResult() {
        PendingAdmissions<String> pending = new PendingAdmissions<>(2, Clock.systemUTC());
        assertTrue(pending.put("one", "first"));
        assertTrue(pending.put("two", "second"));
        assertFalse(pending.put("three", "third"), "every slot holds a running check");
        pending.finish("two", "second");
        pending.finish("one", "first");
        assertTrue(pending.put("three", "third"));
        assertNull(pending.take("one"));
        assertEquals("second", pending.take("two"));
    }
    @Test void removedAndExpiredResultsAreNotTaken() {
        TestClock clock = new TestClock();
        PendingAdmissions<String> pending = new PendingAdmissions<>(2, clock);
        assertTrue(pending.put("denied", "first"));
        pending.remove("denied", "first");
        pending.finish("denied", "first");
        assertNull(pending.take("denied"));
        assertTrue(pending.put("old", "second"));
        pending.finish("old", "second");
        clock.millis += 120_001;
        assertNull(pending.take("old"));
    }
    private static final class TestClock extends Clock {
        long millis;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    }
}
