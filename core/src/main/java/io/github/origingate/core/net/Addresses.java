package io.github.origingate.core.net;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** IP literal parsing that never performs a DNS lookup. */
public final class Addresses {
    private static final Pattern IPV4 = Pattern.compile(
            "((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
    // Unique local IPv6 (fc00::/7), which InetAddress does not treat as site-local.
    private static final List<AddressRange> EXTRA_PRIVATE = List.of(AddressRange.parse("fc00::/7"));

    private Addresses() { }

    /** Parses an IPv4 or IPv6 literal. Host names are rejected instead of resolved. */
    public static Optional<InetAddress> parse(String text) {
        if (text == null) return Optional.empty();
        String value = text.trim();
        boolean v4 = IPV4.matcher(value).matches();
        boolean v6 = !v4 && value.indexOf(':') >= 0 && IPV6.matcher(value).matches();
        if (!v4 && !v6) return Optional.empty();
        try {
            // A string that is already a literal is only validated, never resolved.
            return Optional.of(InetAddress.getByName(value));
        } catch (UnknownHostException | SecurityException ex) {
            return Optional.empty();
        }
    }

    /** Canonical text form used as the cache and storage key. */
    public static String text(InetAddress address) {
        String value = address.getHostAddress();
        int scope = value.indexOf('%');
        return scope < 0 ? value : value.substring(0, scope);
    }

    /** Loopback, private, link-local, unique local, or unspecified addresses. */
    public static boolean isPrivate(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address.isAnyLocalAddress()) return true;
        if (address instanceof Inet6Address) {
            for (AddressRange range : EXTRA_PRIVATE) if (range.contains(address)) return true;
        }
        return false;
    }
}
