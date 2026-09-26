package io.github.origingate.core.config;

import io.github.origingate.core.config.OriginGateConfig.AddressRule;
import io.github.origingate.core.config.OriginGateConfig.BasicRule;
import io.github.origingate.core.config.OriginGateConfig.Bypass;
import io.github.origingate.core.config.OriginGateConfig.CountryMode;
import io.github.origingate.core.config.OriginGateConfig.CountryRule;
import io.github.origingate.core.config.OriginGateConfig.FailureMode;
import io.github.origingate.core.config.OriginGateConfig.Lookup;
import io.github.origingate.core.config.OriginGateConfig.Mysql;
import io.github.origingate.core.config.OriginGateConfig.ProxyRule;
import io.github.origingate.core.config.OriginGateConfig.Rules;
import io.github.origingate.core.config.OriginGateConfig.Storage;
import io.github.origingate.core.net.AddressRange;
import io.github.origingate.core.rules.Countries;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ConfigLoader {
    private static final int MAX_LIST = 1000;

    private ConfigLoader() { }

    public static OriginGateConfig load(Path dataDirectory) throws ConfigException {
        Path root = dataDirectory.toAbsolutePath().normalize();
        YamlSection config = YamlSection.load(root.resolve("config.yml"));
        config.allowOnly("config-version", "dry-run", "console-log", "lookup", "storage", "bypass", "rules", "alerts", "log-file");
        config.integer("config-version", 1, 1);
        return new OriginGateConfig(config.bool("dry-run"), config.choice("console-log", OriginGateConfig.ConsoleLog.class),
                lookup(config.section("lookup")),
                storage(root, config.section("storage")), bypass(config.section("bypass")), rules(config.section("rules")),
                alerts(config.section("alerts")), config.bool("log-file"));
    }

    private static Lookup lookup(YamlSection lookup) throws ConfigException {
        lookup.allowOnly("skip-private-addresses", "on-lookup-failure", "wait-millis", "proxycheck");
        YamlSection proxycheck = lookup.section("proxycheck");
        proxycheck.allowOnly("base-url", "api-keys", "request-timeout-millis");
        String base = proxycheck.text("base-url", 8, 256);
        URI baseUrl;
        try {
            baseUrl = URI.create(base.endsWith("/") ? base : base + "/");
        } catch (IllegalArgumentException ex) {
            throw new ConfigException(proxycheck.key("base-url") + " is not a valid URL", ex);
        }
        if (!("https".equals(baseUrl.getScheme()) || "http".equals(baseUrl.getScheme())) || baseUrl.getHost() == null
                || baseUrl.getQuery() != null || baseUrl.getFragment() != null) {
            throw new ConfigException(proxycheck.key("base-url") + " must be an http or https URL without a query");
        }
        List<String> keys = proxycheck.list("api-keys", 32);
        for (String key : keys) {
            if (!key.matches("[A-Za-z0-9-]{1,64}")) throw new ConfigException(proxycheck.key("api-keys") + " contains an invalid key");
        }
        return new Lookup(lookup.bool("skip-private-addresses"), lookup.choice("on-lookup-failure", FailureMode.class),
                lookup.integer("wait-millis", 1000, 20000), baseUrl, keys,
                proxycheck.integer("request-timeout-millis", 500, 20000));
    }

    private static Storage storage(Path root, YamlSection storage) throws ConfigException {
        storage.allowOnly("type", "max-age-days", "memory-cache-size", "sqlite", "mysql");
        String type = storage.text("type", 1, 16).toLowerCase(Locale.ROOT);
        if (!type.equals("sqlite") && !type.equals("mysql")) throw new ConfigException(storage.key("type") + " must be sqlite or mysql");
        YamlSection sqlite = storage.section("sqlite");
        sqlite.allowOnly("file");
        Path sqliteFile = inside(root, sqlite.text("file", 1, 256), sqlite.key("file"));

        Mysql mysql = null;
        if (storage.has("mysql")) {
            YamlSection section = storage.section("mysql");
            section.allowOnly("host", "port", "database", "username", "password", "ssl-mode", "connect-timeout-millis",
                    "socket-timeout-millis");
            String host = section.text("host", 1, 255);
            if (!host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}|\\[[0-9A-Fa-f:]{2,45}]")) throw new ConfigException(section.key("host") + " is not a valid host");
            String database = section.text("database", 1, 64);
            if (!database.matches("[A-Za-z0-9_]{1,64}")) throw new ConfigException(section.key("database") + " may contain only letters, numbers, and _");
            String sslMode = section.text("ssl-mode", 1, 16);
            if (!Set.of("verify-full", "verify-ca", "disable").contains(sslMode)) {
                throw new ConfigException(section.key("ssl-mode") + " must be verify-full, verify-ca, or disable");
            }
            mysql = new Mysql(host, section.integer("port", 1, 65535), database, section.text("username", 1, 80),
                    section.text("password", 0, 1024), sslMode, section.integer("connect-timeout-millis", 100, 30000),
                    section.integer("socket-timeout-millis", 100, 30000));
        }
        if (type.equals("mysql") && mysql == null) throw new ConfigException("Missing setting: " + storage.key("mysql"));
        return new Storage(type, sqliteFile, type.equals("mysql") ? mysql : null,
                storage.integer("max-age-days", 1, 365), storage.integer("memory-cache-size", 100, 1_000_000));
    }

    private static Bypass bypass(YamlSection bypass) throws ConfigException {
        bypass.allowOnly("permissions", "players", "addresses");
        Set<String> players = new LinkedHashSet<>();
        for (String player : bypass.list("players", MAX_LIST)) players.add(player.toLowerCase(Locale.ROOT));
        return new Bypass(permissions(bypass, "permissions"), Set.copyOf(players), ranges(bypass, "addresses"));
    }

    private static Rules rules(YamlSection rules) throws ConfigException {
        rules.allowOnly("deny-addresses", "vpn", "proxy", "country");
        YamlSection deny = rules.section("deny-addresses");
        deny.allowOnly("enabled", "list", "bypass-permissions");
        YamlSection vpn = rules.section("vpn");
        vpn.allowOnly("enabled", "bypass-permissions");
        YamlSection proxy = rules.section("proxy");
        proxy.allowOnly("enabled", "allowed-countries", "bypass-permissions");
        YamlSection country = rules.section("country");
        country.allowOnly("enabled", "mode", "countries", "bypass-permissions");
        return new Rules(
                new AddressRule(deny.bool("enabled"), ranges(deny, "list"), permissions(deny, "bypass-permissions")),
                new BasicRule(vpn.bool("enabled"), permissions(vpn, "bypass-permissions")),
                new ProxyRule(proxy.bool("enabled"), countries(proxy, "allowed-countries"), permissions(proxy, "bypass-permissions")),
                new CountryRule(country.bool("enabled"), country.choice("mode", CountryMode.class),
                        countries(country, "countries"), permissions(country, "bypass-permissions")));
    }

    private static List<String> alerts(YamlSection alerts) throws ConfigException {
        alerts.allowOnly("permissions");
        return permissions(alerts, "permissions");
    }

    private static List<String> permissions(YamlSection section, String name) throws ConfigException {
        List<String> values = section.list(name, 100);
        for (String value : values) {
            if (!value.matches("[A-Za-z0-9_.*-]{1,128}")) throw new ConfigException(section.key(name) + " contains an invalid permission: " + value);
        }
        return values;
    }

    private static List<String> countries(YamlSection section, String name) throws ConfigException {
        List<String> result = new ArrayList<>();
        for (String value : section.list(name, 300)) {
            result.add(Countries.normalize(value).orElseThrow(() -> new ConfigException(
                    section.key(name) + " needs two-letter country codes such as US; not a known code: " + value)));
        }
        return List.copyOf(result);
    }

    private static List<AddressRange> ranges(YamlSection section, String name) throws ConfigException {
        List<AddressRange> result = new ArrayList<>();
        for (String value : section.list(name, MAX_LIST)) {
            try {
                result.add(AddressRange.parse(value));
            } catch (IllegalArgumentException ex) {
                throw new ConfigException(section.key(name) + ": " + ex.getMessage(), ex);
            }
        }
        return List.copyOf(result);
    }

    private static Path inside(Path root, String value, String key) throws ConfigException {
        Path configured;
        try {
            configured = Path.of(value);
        } catch (RuntimeException ex) {
            throw new ConfigException(key + " is not a valid path", ex);
        }
        if (configured.isAbsolute()) throw new ConfigException(key + " must be relative to the plugin folder");
        Path resolved = root.resolve(configured).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) throw new ConfigException(key + " must name a file inside the plugin folder");
        for (Path current = resolved; current != null && !current.equals(root); current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new ConfigException(key + " contains a symbolic link");
        }
        return resolved;
    }
}
