package io.github.origingate.core.rules;

import java.net.InetAddress;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/** A player connection being checked. {@code uuid} may be null for an IP-only check. */
public record LoginAttempt(String username, UUID uuid, InetAddress address, Predicate<String> permissions) {
    public LoginAttempt {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(permissions, "permissions");
    }

    /** An IP-only check: no player, no permissions. */
    public static LoginAttempt address(InetAddress address) {
        return new LoginAttempt("-", null, address, permission -> false);
    }

    public boolean hasAny(Iterable<String> permissionList) {
        for (String permission : permissionList) if (permissions.test(permission)) return true;
        return false;
    }
}
