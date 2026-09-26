package io.github.origingate.core.lookup;

import java.time.Instant;
import java.util.Objects;

/** What is known about one IP address. Text fields are null when unknown. */
public record IpInfo(String ip, String provider, String organisation, String operatorName, String city,
                     String region, String country, String countryCode, String asn, boolean vpn, boolean proxy,
                     String type, Instant checkedAt) {
    public IpInfo {
        Objects.requireNonNull(ip, "ip");
        Objects.requireNonNull(checkedAt, "checkedAt");
        provider = clean(provider);
        organisation = clean(organisation);
        operatorName = clean(operatorName);
        city = clean(city);
        region = clean(region);
        country = clean(country);
        countryCode = clean(countryCode);
        if (countryCode != null) countryCode = countryCode.toUpperCase(java.util.Locale.ROOT);
        asn = clean(asn);
        type = clean(type);
    }

    /** The VPN operator name when it looks meaningful, otherwise the network organisation. */
    public String displayOrganisation() {
        return operatorName != null && operatorName.length() > 3 ? operatorName : organisation;
    }

    /** Trims, removes control characters, limits length, and turns blanks or "-" into null. */
    static String clean(String value) {
        if (value == null) return null;
        StringBuilder result = new StringBuilder(Math.min(value.length(), 255));
        for (int i = 0; i < value.length() && result.length() < 255; i++) {
            char c = value.charAt(i);
            if (!Character.isISOControl(c)) result.append(c);
        }
        String text = result.toString().trim();
        return text.isEmpty() || text.equals("-") ? null : text;
    }
}
