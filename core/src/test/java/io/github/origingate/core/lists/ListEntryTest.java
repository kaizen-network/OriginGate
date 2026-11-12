package io.github.origingate.core.lists;

import io.github.origingate.core.TestSupport;
import io.github.origingate.core.net.Addresses;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListEntryTest {
    private static final UUID KAI = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    private static ListEntry entry(String target, Instant expiresAt) {
        return new ListEntry(ListEntry.Kind.DENY, target, expiresAt, null, "console", TestSupport.NOW);
    }

    private static InetAddress ip(String text) {
        return Addresses.parse(text).orElseThrow();
    }

    @Test void normalizesTargets() {
        assertEquals("kai", ListEntry.normalize("Kai"));
        assertEquals("203.0.113.7", ListEntry.normalize("203.0.113.7"));
        assertEquals("2001:db8:0:0:0:0:0:1", ListEntry.normalize("2001:DB8::1"));
        assertEquals("198.51.100.0/24", ListEntry.normalize("198.51.100.0/24"));
        assertEquals("203.0.113.7", ListEntry.normalize("203.0.113.7/32"));
        assertEquals("123e4567-e89b-12d3-a456-426614174000", ListEntry.normalize("123E4567-E89B-12D3-A456-426614174000"));
        assertEquals("123e4567-e89b-12d3-a456-426614174000", ListEntry.normalize("123E4567E89B12D3A456426614174000"));
        assertEquals("198.51.100.0/24", ListEntry.normalize("198.51.100.77/24"));
        assertEquals("2001:db8:0:0:0:0:0:0/32", ListEntry.normalize("2001:db8::1/32"));
        assertEquals(".steve", ListEntry.normalize(".Steve"));
        assertThrows(IllegalArgumentException.class, () -> ListEntry.normalize("203.0.113.256"));
        assertThrows(IllegalArgumentException.class, () -> ListEntry.normalize("010.0.0.1"));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> ListEntry.normalize("two words"));
        assertEquals("two words is not a player name, UUID, IP address, or CIDR range", refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ListEntry.normalize("a".repeat(33)));
        assertThrows(IllegalArgumentException.class, () -> ListEntry.normalize("203.0.113.7/33"));
        assertThrows(IllegalArgumentException.class, () -> ListEntry.normalize(""));
    }

    @Test void matching() {
        ListEntry name = entry("kai", null);
        assertTrue(name.matchesPlayer("KAI", UUID.randomUUID()));
        assertFalse(name.matchesPlayer("Sam", KAI));
        assertFalse(name.matchesAddress(ip("203.0.113.7")));
        assertFalse(name.isAddress());

        ListEntry byUuid = entry(KAI.toString(), null);
        assertTrue(byUuid.matchesPlayer("Sam", KAI));
        assertFalse(byUuid.matchesPlayer("Sam", UUID.randomUUID()));

        ListEntry range = entry("198.51.100.0/24", null);
        assertTrue(range.isAddress());
        assertTrue(range.matchesAddress(ip("198.51.100.200")));
        assertFalse(range.matchesAddress(ip("198.51.101.1")));
        assertFalse(range.matchesPlayer("198.51.100.0/24", KAI));

        Instant end = TestSupport.NOW.plus(Duration.ofHours(1));
        ListEntry timed = entry("kai", end);
        assertTrue(timed.activeAt(end.minusSeconds(1)));
        assertFalse(timed.activeAt(end));
        assertFalse(timed.activeAt(end.plusSeconds(1)));
        assertTrue(name.activeAt(Instant.MAX.minusSeconds(1)));
    }
}
