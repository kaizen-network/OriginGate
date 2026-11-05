package io.github.origingate.core;

import io.github.origingate.core.config.OriginGateConfig;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Warns when name, UUID, or permission bypasses are set on a server or proxy where anyone can claim any name.
 * In offline mode the UUID comes from the name, and permission plugins load permissions by that UUID before any
 * login plugin runs, so only {@code bypass.addresses} holds up.
 */
public final class OfflineModeWarning {
    /** How the server or proxy OriginGate runs on learns who a player is. */
    public enum Mode {
        /** Online mode: names and UUIDs are checked with Mojang. */
        ONLINE,
        /** A backend behind Velocity modern forwarding from an online-mode proxy: the forwarded data is signed. */
        MODERN_FORWARDING,
        /** A backend behind BungeeCord IP forwarding: anyone who reaches this server's port directly can fake it. */
        LEGACY_FORWARDING,
        /** Offline mode without forwarding, or behind an offline-mode proxy: anyone can use any name. */
        OFFLINE,
        /** Offline mode, but whether a proxy forwards checked identities could not be found out. */
        UNKNOWN
    }

    private OfflineModeWarning() { }

    public static Optional<String> check(Mode mode, OriginGateConfig config) {
        if (mode == Mode.ONLINE || mode == Mode.MODERN_FORWARDING) return Optional.empty();
        boolean players = !config.bypass().players().isEmpty();
        Set<String> permissions = new LinkedHashSet<>(config.bypass().permissions());
        for (List<String> rule : config.rules().bypassPermissions()) permissions.addAll(rule);
        if (!players && permissions.isEmpty()) return Optional.empty();
        String bypasses = (players ? "player name and UUID bypasses (bypass.players)" : "")
                + (players && !permissions.isEmpty() ? " and " : "")
                + (permissions.isEmpty() ? "" : "permission bypasses (" + String.join(", ", permissions) + ")");
        switch (mode) {
            case LEGACY_FORWARDING:
                return Optional.of("This server gets player names, UUIDs, and IP addresses through BungeeCord IP forwarding "
                        + "(bungeecord: true), which anyone who can reach this server's port directly can fake. Then the "
                        + bypasses + " can be claimed by anyone, and the IP checks see a made-up address. This is only safe "
                        + "while this port accepts connections from the proxy alone. Prefer OriginGate on the proxy, or "
                        + "Velocity modern forwarding.");
            case UNKNOWN:
                return Optional.of("This server runs in offline mode, and OriginGate could not tell whether a proxy "
                        + "forwards checked player identities. Unless it sits behind an online-mode Velocity proxy with modern "
                        + "forwarding, anyone can join with any name, so the " + bypasses + " can be claimed by anyone who "
                        + "picks a name that has them. Only bypass.addresses holds up in offline mode.");
            default:
                return Optional.of("This server or proxy runs in offline mode, so anyone can join with any name. The " + bypasses
                        + " follow that name, so anyone who picks a name that has one skips those checks. Only "
                        + "bypass.addresses holds up in offline mode. Remove the others, or use online mode.");
        }
    }
}
