package io.github.origingate.core.lookup;

import io.github.origingate.core.TestSupport;
import io.github.origingate.core.TestSupport.RecordingLog;
import io.github.origingate.core.config.OriginGateConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProvidersTest {
    @TempDir Path directory;
    private final RecordingLog log = new RecordingLog();
    private Providers providers;

    @AfterEach void stop() {
        if (providers != null) providers.close();
    }

    private Providers start(Object... changes) throws Exception {
        OriginGateConfig config = TestSupport.config(directory, changes);
        providers = Providers.start(config.lookup(), HttpClient.newHttpClient(), Clock.fixed(TestSupport.NOW, ZoneOffset.UTC),
                log, "OriginGate/test");
        return providers;
    }

    @Test void defaultConfigUsesProxycheckForBoth() throws Exception {
        assertEquals("country from proxycheck | vpn from proxycheck", start().chain().describe());
        assertTrue(log.warnings.isEmpty(), log.warnings.toString());
    }

    @Test void listsKeepTheirOrder() throws Exception {
        Providers started = start("lookup.country-from", List.of("maxmind", "proxycheck"),
                "lookup.vpn-from", List.of("proxycheck", "iphub"), "lookup.iphub.api-keys", List.of("key-one"));
        assertEquals("country from maxmind, proxycheck | vpn from proxycheck, iphub", started.chain().describe());
    }

    @Test void placedMaxMindFileIsUsedAndAnOldOneIsReported() throws Exception {
        TestSupport.copyResource("/maxmind/GeoLite2-Country-Test.mmdb", directory.resolve("data/GeoLite2-Country.mmdb"));
        Providers started = start("lookup.country-from", List.of("maxmind"), "lookup.vpn-from", List.of(),
                "rules.vpn.enabled", false, "rules.proxy.enabled", false);
        ProviderChain.Answer answer = started.chain().lookup("81.2.69.160");
        assertEquals("GB", answer.info().countryCode());
        assertEquals("maxmind (country)", answer.answeredBy().described());
        assertTrue(log.warnings.stream().anyMatch(warning -> warning.contains("was built on 2026-02-04")), log.warnings.toString());
    }

    @Test void missingMaxMindFileIsReportedNotFatal() throws Exception {
        start("lookup.country-from", List.of("maxmind", "proxycheck"));
        assertTrue(log.warnings.stream().anyMatch(warning -> warning.contains("does not exist yet")), log.warnings.toString());
    }

    @Test void freeIpApiIsReported() throws Exception {
        start("lookup.vpn-from", List.of("ip-api"));
        assertTrue(log.warnings.stream().anyMatch(warning -> warning.contains("plain HTTP")), log.warnings.toString());
    }
}
