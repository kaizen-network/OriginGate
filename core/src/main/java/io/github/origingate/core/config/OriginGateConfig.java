package io.github.origingate.core.config;

import io.github.origingate.core.net.AddressRange;
import io.github.origingate.core.rules.Decision;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record OriginGateConfig(boolean dryRun, ConsoleLog consoleLog, Lookup lookup, Storage storage, Bypass bypass,
                               Rules rules, List<String> alertPermissions, boolean logFile, int logFileKeepDays) {

    /** How much the console shows per login. Each level includes the ones before it. */
    public enum ConsoleLog {
        NONE, KICKS, MATCHES, ALL, DEBUG;

        public boolean shows(Decision decision) {
            return switch (this) {
                case NONE -> false;
                case KICKS -> decision.outcome() == Decision.Outcome.DENY;
                case MATCHES -> decision.rule() != null;
                case ALL, DEBUG -> true;
            };
        }
    }

    public enum FailureMode { ALLOW, DENY }

    public enum CountryMode { ALLOWLIST, DENYLIST }

    /**
     * {@code countryFrom} and {@code vpnFrom} hold provider names in the order they are asked. A provider's settings
     * are null when its block is missing and no list names it.
     */
    public record Lookup(boolean skipPrivateAddresses, FailureMode onFailure, int waitMillis, List<String> countryFrom,
                         List<String> vpnFrom, ProxyCheck proxycheck, IpHub iphub, IpApi ipApi, IpInfoLite ipinfo,
                         MaxMind maxmind) {
        public static final List<String> PROVIDERS = List.of("proxycheck", "iphub", "ip-api", "ipinfo", "maxmind");
        /** Providers whose free data has a VPN check. */
        public static final List<String> VPN_PROVIDERS = List.of("proxycheck", "iphub", "ip-api");

        /** Every provider in either list, each once, country-from first. */
        public List<String> inUse() {
            Set<String> names = new LinkedHashSet<>(countryFrom);
            names.addAll(vpnFrom);
            return List.copyOf(names);
        }

        public boolean uses(String name) {
            return countryFrom.contains(name) || vpnFrom.contains(name);
        }

        /** The longest request timeout among the web providers in use, or 0 when none is used. */
        public int longestRequestMillis() {
            int longest = 0;
            if (uses("proxycheck")) longest = Math.max(longest, proxycheck.requestTimeoutMillis());
            if (uses("iphub")) longest = Math.max(longest, iphub.requestTimeoutMillis());
            if (uses("ip-api")) longest = Math.max(longest, ipApi.requestTimeoutMillis());
            if (uses("ipinfo")) longest = Math.max(longest, ipinfo.requestTimeoutMillis());
            return longest;
        }
    }

    public record ProxyCheck(URI baseUrl, List<String> apiKeys, int requestTimeoutMillis) {
        @Override public String toString() {
            return "ProxyCheck[baseUrl=" + baseUrl + ", apiKeys=" + apiKeys.size() + " configured]";
        }
    }

    public record IpHub(List<String> apiKeys, int requestTimeoutMillis) {
        @Override public String toString() { return "IpHub[apiKeys=" + apiKeys.size() + " configured]"; }
    }

    /** An empty {@code apiKey} means the free endpoint. */
    public record IpApi(String apiKey, int requestTimeoutMillis) {
        public boolean pro() { return !apiKey.isEmpty(); }

        @Override public String toString() { return "IpApi[" + (pro() ? "pro" : "free") + "]"; }
    }

    public record IpInfoLite(String token, int requestTimeoutMillis) {
        @Override public String toString() { return "IpInfoLite[token " + (token.isEmpty() ? "not set" : "set") + "]"; }
    }

    /** {@code accountId} 0 with an empty {@code licenseKey} means the owner places the file. */
    public record MaxMind(Path file, String edition, int accountId, String licenseKey) {
        public static final List<String> EDITIONS = List.of("GeoLite2-Country", "GeoLite2-City");

        public boolean autoUpdate() { return accountId != 0; }

        @Override public String toString() {
            return "MaxMind[file=" + file + ", edition=" + edition + ", autoUpdate=" + autoUpdate() + "]";
        }
    }

    /** {@code type} is "sqlite" or "mysql". {@code mysql} is null for SQLite. {@code keepDays} 0 means never delete. */
    public record Storage(String type, Path sqliteFile, Mysql mysql, int maxAgeDays, int keepDays,
                          int memoryCacheSize) { }

    public record Mysql(String host, int port, String database, String username, String password, String sslMode,
                        int connectTimeoutMillis, int socketTimeoutMillis) {
        @Override public String toString() { return "Mysql[connection details redacted]"; }
    }

    /** {@code players} holds lower-case names and UUID strings. */
    public record Bypass(List<String> permissions, Set<String> players, List<AddressRange> addresses) { }

    public record Rules(AddressRule denyAddresses, BasicRule vpn, ProxyRule proxy, CountryRule country) { }

    public record AddressRule(boolean enabled, List<AddressRange> list, List<String> bypassPermissions) { }

    public record BasicRule(boolean enabled, List<String> bypassPermissions) { }

    /** Proxies from {@code allowedCountries} (ISO codes) are not blocked. */
    public record ProxyRule(boolean enabled, List<String> allowedCountries, List<String> bypassPermissions) { }

    public record CountryRule(boolean enabled, CountryMode mode, List<String> countries,
                              List<String> bypassPermissions) { }
}
