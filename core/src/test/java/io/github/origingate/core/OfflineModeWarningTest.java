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

    @Test void onlineModeAndModernForwardingNeverWarn() throws Exception {
        OriginGateConfig config = TestSupport.config(directory, "bypass.players", List.of("ExampleStaff"));
        assertFalse(OfflineModeWarning.check(Mode.ONLINE, config).isPresent());
        assertFalse(OfflineModeWarning.check(Mode.MODERN_FORWARDING, config).isPresent());
    }

    @Test void theShippedPerRulePermissionsAloneDoNotWarn() throws Exception {
        OriginGateConfig defaults = TestSupport.config(directory);
        assertFalse(defaults.rules().vpn().bypassPermissions().isEmpty(), "the default config has per-rule permissions");
        assertFalse(OfflineModeWarning.check(Mode.OFFLINE, defaults).isPresent());
        assertFalse(OfflineModeWarning.check(Mode.UNKNOWN, defaults).isPresent());
        assertFalse(OfflineModeWarning.check(Mode.OFFLINE,
                TestSupport.config(directory, "bypass.addresses", List.of("192.0.2.0/24"))).isPresent());
    }

    @Test void ownerSetNameOrPermissionBypassesWarnInOfflineMode() throws Exception {
        String players = OfflineModeWarning.check(Mode.OFFLINE,
                TestSupport.config(directory, "bypass.players", List.of("ExampleStaff"))).orElseThrow();
        assertTrue(players.startsWith("This server or proxy runs in offline mode"), players);
        assertTrue(players.contains("bypass.players follows the name"), players);
        assertTrue(players.contains("Only bypass.addresses holds up"), players);
        assertFalse(players.contains("origingate.bypass.vpn"), players);
        String permissions = OfflineModeWarning.check(Mode.UNKNOWN,
                TestSupport.config(directory, "bypass.permissions", List.of("example.staff"))).orElseThrow();
        assertTrue(permissions.contains("could not tell"), permissions);
        assertTrue(permissions.contains("bypass.permissions follow the name"), permissions);
    }

    @Test void bungeeCordForwardingWarnsEvenWithoutBypasses() throws Exception {
        String plain = OfflineModeWarning.check(Mode.LEGACY_FORWARDING, TestSupport.config(directory)).orElseThrow();
        assertTrue(plain.startsWith("BungeeCord IP forwarding (bungeecord: true) is on"), plain);
        assertTrue(plain.contains("fake their name, UUID, and IP address"), plain);
        assertFalse(plain.contains("bypass."), plain);
        String withBypasses = OfflineModeWarning.check(Mode.LEGACY_FORWARDING, TestSupport.config(directory,
                "bypass.players", List.of("ExampleStaff"), "bypass.permissions", List.of("example.staff"))).orElseThrow();
        assertTrue(withBypasses.contains("bypass.players and bypass.permissions follow the name"), withBypasses);
    }
}
