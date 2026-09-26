package io.github.origingate.core.lookup;

import io.github.origingate.core.TestSupport;
import io.github.origingate.core.TestSupport.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.github.origingate.core.TestSupport.info;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryCacheTest {
    @Test void entriesExpireAfterMaxAgeFromCheckTime() {
        MutableClock clock = new MutableClock(TestSupport.NOW);
        MemoryCache cache = new MemoryCache(10, Duration.ofDays(30), clock);
        cache.put(info("192.0.2.1", "Canada", "CA", false, false));
        clock.advance(Duration.ofDays(29));
        assertTrue(cache.get("192.0.2.1").isPresent());
        clock.advance(Duration.ofDays(1));
        assertTrue(cache.get("192.0.2.1").isEmpty());
        assertEquals(0, cache.size());
    }

    @Test void leastRecentlyUsedEntryIsDroppedWhenFull() {
        MemoryCache cache = new MemoryCache(2, Duration.ofDays(30), new MutableClock(TestSupport.NOW));
        cache.put(info("192.0.2.1", "Canada", "CA", false, false));
        cache.put(info("192.0.2.2", "Canada", "CA", false, false));
        cache.get("192.0.2.1");
        cache.put(info("192.0.2.3", "Canada", "CA", false, false));
        assertTrue(cache.get("192.0.2.1").isPresent());
        assertTrue(cache.get("192.0.2.2").isEmpty());
        assertTrue(cache.get("192.0.2.3").isPresent());
    }
}
