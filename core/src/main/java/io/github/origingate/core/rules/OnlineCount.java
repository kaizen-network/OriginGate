package io.github.origingate.core.rules;

import java.net.InetAddress;
import java.util.UUID;

/** Counts the players online from one address. Read on login threads, so it must not block. */
@FunctionalInterface
public interface OnlineCount {
    /** Players online from exactly {@code address}, not counting {@code except} (may be null). */
    int sameAddress(InetAddress address, UUID except);

    OnlineCount NONE = (address, except) -> 0;
}
