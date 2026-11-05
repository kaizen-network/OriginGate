package io.github.origingate.core;

import io.github.origingate.core.OfflineModeWarning.Mode;
import io.github.origingate.core.config.OriginGateConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineModeWarningTest {
    @TempDir Path directory;

    private OriginGateConfig noBypasses() throws Exception {
        return TestSupport.config(directory, "rules.vpn.bypass-permissions", List.of(), "rules.proxy.bypass-permissions", List.of(),
                "rules.country.bypass-permissions", List.of(), "rules.hosting.bypass-permissions", List.of(),
                "rules.deny-networks.bypass-permissions", List.of());
    }

    @Test void onlineModeAndModernForwardingNeverWarn() throws Exception {
        OriginGateConfig config = TestSupport.config(directory, "bypass.players", List.of("ExampleStaff"));
        assertFalse(OfflineModeWarning.check(Mode.ONLINE, config).isPresent());
        assertFalse(OfflineModeWarning.check(Mode.MODERN_FORWARDING, config).isPresent());
    }

    @Test void offlineModeWithBypassesWarns() throws Exception {
        String warning = OfflineModeWarning.check(Mode.OFFLINE,
                TestSupport.config(directory, "bypass.players", List.of("ExampleStaff"))).orElseThrow();
        assertTrue(warning.contains("offline mode"), warning);
        assertTrue(warning.contains("bypass.players"), warning);
        assertTrue(warning.contains("origingate.bypass.vpn"), "the default per-rule permissions count too: " + warning);
        assertTrue(warning.contains("Only bypass.addresses holds up"), warning);
    }

    @Test void offlineModeWithOnlyAddressBypassesIsQuiet() throws Exception {
        assertFalse(OfflineModeWarning.check(Mode.OFFLINE, noBypasses()).isPresent());
        OriginGateConfig addresses = TestSupport.config(directory, "bypass.addresses", List.of("192.0.2.0/24"),
                "rules.vpn.bypass-permissions", List.of(), "rules.proxy.bypass-permissions", List.of(),
                "rules.country.bypass-permissions", List.of(), "rules.hosting.bypass-permissions", List.of(),
                "rules.deny-networks.bypass-permissions", List.of());
        assertFalse(OfflineModeWarning.check(Mode.OFFLINE, addresses).isPresent());
    }

    @Test void bungeeCordForwardingIsNotTreatedAsSafe() throws Exception {
        String warning = OfflineModeWarning.check(Mode.LEGACY_FORWARDING, TestSupport.config(directory)).orElseThrow();
        assertTrue(warning.contains("BungeeCord IP forwarding"), warning);
        assertTrue(warning.contains("reach this server's port directly can fake"), warning);
        assertTrue(warning.contains("Velocity modern forwarding"), warning);
    }

    @Test void unknownSetupWarnsWithTheCondition() throws Exception {
        String warning = OfflineModeWarning.check(Mode.UNKNOWN, TestSupport.config(directory)).orElseThrow();
        assertTrue(warning.contains("could not tell"), warning);
    }
}
