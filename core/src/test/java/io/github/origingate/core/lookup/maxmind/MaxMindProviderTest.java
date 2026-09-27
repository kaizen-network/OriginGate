package io.github.origingate.core.lookup.maxmind;

import io.github.origingate.core.Log;
import io.github.origingate.core.TestSupport;
import io.github.origingate.core.lookup.IpInfo;
import io.github.origingate.core.lookup.LookupException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaxMindProviderTest {
    @TempDir Path directory;
    private MaxMindProvider provider;

    @AfterEach void stop() {
        if (provider != null) provider.close();
    }

    private MaxMindProvider open(String resource, Instant now) throws IOException {
        Path file = directory.resolve("GeoLite2.mmdb");
        TestSupport.copyResource("/maxmind/" + resource, file);
        provider = new MaxMindProvider(file, Clock.fixed(now, ZoneOffset.UTC), Log.NONE);
        assertTrue(provider.reload());
        return provider;
    }

    @Test void countryFileGivesCountryOnly() throws Exception {
        IpInfo info = open("GeoLite2-Country-Test.mmdb", TestSupport.NOW).lookup("81.2.69.160");
        assertEquals("maxmind", provider.name());
        assertEquals("GB", info.countryCode());
        assertEquals("United Kingdom", info.country());
        assertNull(info.city());
        assertFalse(info.vpn());
        assertEquals(TestSupport.NOW, info.checkedAt());
        assertEquals("JP", provider.lookup("2001:218::").countryCode());
    }

    @Test void cityFileAddsCityAndRegion() throws Exception {
        open("GeoLite2-City-Test.mmdb", TestSupport.NOW);
        IpInfo london = provider.lookup("81.2.69.160");
        assertEquals("London", london.city());
        assertEquals("England", london.region());
        IpInfo milton = provider.lookup("216.160.83.56");
        assertEquals("US", milton.countryCode());
        assertEquals("Milton", milton.city());
        assertEquals("Washington", milton.region());
    }

    @Test void ipWithoutEntryFails() throws Exception {
        open("GeoLite2-Country-Test.mmdb", TestSupport.NOW);
        LookupException failure = assertThrows(LookupException.class, () -> provider.lookup("8.8.8.8"));
        assertTrue(failure.getMessage().contains("no entry"), failure.getMessage());
    }

    @Test void missingFileIsSkippedNotFatal() {
        provider = new MaxMindProvider(directory.resolve("missing.mmdb"), Clock.systemUTC(), Log.NONE);
        assertFalse(provider.reload());
        LookupException failure = assertThrows(LookupException.class, () -> provider.lookup("81.2.69.160"));
        assertTrue(failure.getMessage().contains("not loaded"), failure.getMessage());
    }

    @Test void brokenFileKeepsTheCurrentOne() throws Exception {
        open("GeoLite2-Country-Test.mmdb", TestSupport.NOW);
        Files.writeString(directory.resolve("GeoLite2.mmdb"), "not a database");
        assertFalse(provider.reload());
        assertEquals("GB", provider.lookup("81.2.69.160").countryCode());
    }

    @Test void oldFileGetsAWarning() throws Exception {
        String warning = open("GeoLite2-Country-Test.mmdb", TestSupport.NOW).staleWarning().orElseThrow();
        assertTrue(warning.contains("was built on 2026-02-04"), warning);
        provider.close();
        assertTrue(open("GeoLite2-Country-Test.mmdb", Instant.parse("2026-02-10T00:00:00Z")).staleWarning().isEmpty());
    }
}
