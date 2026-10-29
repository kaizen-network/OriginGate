package io.github.origingate.core.rules;

import io.github.origingate.core.lookup.IpInfo;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Country matching by ISO 3166-1 alpha-2 code. */
public final class Countries {
    /** Codes in use that Java's list leaves out (XK is Kosovo). */
    private static final Set<String> EXTRA_CODES = io.github.origingate.core.util.Compat.set("XK");
    private static final Set<String> CODES = codes();

    private Countries() { }

    private static Set<String> codes() {
        Set<String> codes = new HashSet<>(io.github.origingate.core.util.Compat.set(Locale.getISOCountries()));
        codes.addAll(EXTRA_CODES);
        return io.github.origingate.core.util.Compat.setCopy(codes);
    }

    /** Returns the upper-case code, or empty when it is not a known country code. */
    public static Optional<String> normalize(String code) {
        if (code == null) return Optional.empty();
        String upper = code.trim().toUpperCase(Locale.ROOT);
        return CODES.contains(upper) ? Optional.of(upper) : Optional.empty();
    }

    public static boolean matchesAny(IpInfo info, Collection<String> codes) {
        for (String code : codes) {
            if (info.countryCode() != null && info.countryCode().equalsIgnoreCase(code)) return true;
        }
        return false;
    }
}
