package io.github.origingate.bukkit;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PendingAdmissionsTest {
    @Test void overlappingAttemptsCannotReplaceFirstResult() {
        PendingAdmissions<String> pending = new PendingAdmissions<>(2, Clock.systemUTC());
        assertTrue(pending.put("same", "first"));
        assertFalse(pending.put("same", "second"));
        assertEquals("first", pending.take("same"));
        assertNull(pending.take("same"));
    }
    @Test void capacityIsBoundedAndCompletionFreesSlot() {
        PendingAdmissions<String> pending = new PendingAdmissions<>(1, Clock.systemUTC());
        assertTrue(pending.put("one", "first"));
        assertFalse(pending.put("two", "second"));
        assertEquals("first", pending.take("one"));
        assertTrue(pending.put("two", "second"));
    }
}
