package io.github.origingate.core.config;

import io.github.origingate.core.net.AddressRange;
import io.github.origingate.core.rules.Decision;

import java.net.URI;
import java.nio.file.Path;
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

    public record Lookup(boolean skipPrivateAddresses, FailureMode onFailure, int waitMillis, URI baseUrl,
                         List<String> apiKeys, int requestTimeoutMillis) {
        @Override public String toString() {
            return "Lookup[baseUrl=" + baseUrl + ", apiKeys=" + apiKeys.size() + " configured]";
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
