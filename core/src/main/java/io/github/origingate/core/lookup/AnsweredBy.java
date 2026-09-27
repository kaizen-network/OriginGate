package io.github.origingate.core.lookup;

/** Which providers answered a fresh lookup. {@code vpn} is null when {@code vpn-from} is empty. */
public record AnsweredBy(String country, String vpn) {
    /** For log lines: "maxmind+proxycheck", or one name when one provider answered both jobs. */
    public String joined() {
        return vpn == null || vpn.equals(country) ? country : country + "+" + vpn;
    }

    /** For the check command: "maxmind (country), proxycheck (vpn)". */
    public String described() {
        if (vpn == null) return country + " (country)";
        if (vpn.equals(country)) return country + " (country, vpn)";
        return country + " (country), " + vpn + " (vpn)";
    }
}
