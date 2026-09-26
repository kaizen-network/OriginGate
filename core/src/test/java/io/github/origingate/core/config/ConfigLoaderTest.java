package io.github.origingate.core.config;

import io.github.origingate.core.TestSupport;
import io.github.origingate.core.rules.Decision;
import io.github.origingate.core.rules.Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {
    @TempDir Path directory;

    private String failure(Object... changes) {
        return assertThrows(ConfigException.class, () -> TestSupport.config(directory, changes)).getMessage();
    }

    @Test void defaultsAreGenericAndSafe() throws Exception {
        OriginGateConfig config = TestSupport.config(directory);
        assertFalse(config.dryRun());
        assertTrue(config.lookup().skipPrivateAddresses());
        assertEquals(OriginGateConfig.FailureMode.ALLOW, config.lookup().onFailure());
        assertEquals("https://proxycheck.io/v3/", config.lookup().baseUrl().toString());
        assertTrue(config.lookup().apiKeys().isEmpty());
        assertEquals("sqlite", config.storage().type());
        assertEquals(30, config.storage().maxAgeDays());
        assertFalse(config.rules().country().enabled());
        assertFalse(config.rules().denyAddresses().enabled());
        assertTrue(config.rules().vpn().enabled());
        assertEquals(List.of("origingate.bypass.vpn"), config.rules().vpn().bypassPermissions());
        assertTrue(config.bypass().permissions().isEmpty());
        assertEquals(directory.resolve("data/origingate.db").toAbsolutePath().normalize(), config.storage().sqliteFile());
    }

    @Test void consoleLogLevels() throws Exception {
        assertEquals(OriginGateConfig.ConsoleLog.MATCHES, TestSupport.config(directory).consoleLog());
        assertEquals(OriginGateConfig.ConsoleLog.DEBUG, TestSupport.config(directory, "console-log", "Debug").consoleLog());
        assertEquals(OriginGateConfig.ConsoleLog.NONE, TestSupport.config(directory, "console-log", "none").consoleLog());
        assertTrue(failure("console-log", "verbose").contains("none, kicks, matches, all, debug"));
        // A bare off is read by YAML as false; the error still lists the options.
        assertEquals("console-log must be one of none, kicks, matches, all, debug", failure("console-log", false));
        assertEquals("Unknown setting: debug", failure("debug", true));

        Decision allow = new Decision(Decision.Outcome.ALLOW, null, "no rule matched", null, false);
        Decision failedAllow = new Decision(Decision.Outcome.ALLOW, Rule.LOOKUP_FAILURE, "timeout", null, false);
        Decision bypass = new Decision(Decision.Outcome.BYPASS, Rule.VPN, null, null, false);
        Decision wouldDeny = new Decision(Decision.Outcome.DENY, Rule.VPN, null, null, true);
        List<Decision> all = List.of(allow, failedAllow, bypass, wouldDeny);
        assertEquals(List.of(), shown(OriginGateConfig.ConsoleLog.NONE, all));
        assertEquals(List.of(wouldDeny), shown(OriginGateConfig.ConsoleLog.KICKS, all));
        assertEquals(List.of(failedAllow, bypass, wouldDeny), shown(OriginGateConfig.ConsoleLog.MATCHES, all));
        assertEquals(all, shown(OriginGateConfig.ConsoleLog.ALL, all));
        assertEquals(all, shown(OriginGateConfig.ConsoleLog.DEBUG, all));
    }

    private static List<Decision> shown(OriginGateConfig.ConsoleLog level, List<Decision> decisions) {
        return decisions.stream().filter(level::shows).toList();
    }

    @Test void countryCodesAreNormalizedAndChecked() throws Exception {
        OriginGateConfig config = TestSupport.config(directory, "rules.country.countries", List.of("ca", " Mx "));
        assertEquals(List.of("CA", "MX"), config.rules().country().countries());
        assertTrue(failure("rules.country.countries", List.of("Canada")).contains("rules.country.countries"));
        assertTrue(failure("rules.proxy.allowed-countries", List.of("XX")).contains("not a known code: XX"));
    }

    @Test void unknownAndMissingSettingsNameTheirPath() throws Exception {
        assertEquals("Unknown setting: rules.vpn.typo", failure("rules.vpn.typo", true));
        Path file = TestSupport.writeConfig(directory);
        String text = Files.readString(file.resolve("config.yml")).replace("dry-run: false\n", "");
        Files.writeString(file.resolve("config.yml"), text);
        assertEquals("Missing setting: dry-run", assertThrows(ConfigException.class, () -> ConfigLoader.load(directory)).getMessage());
    }

    @Test void numbersAreBounded() {
        assertTrue(failure("lookup.wait-millis", 999).contains("lookup.wait-millis must be a whole number from 1000 to 20000"));
        assertTrue(failure("lookup.wait-millis", 20001).contains("lookup.wait-millis"));
        assertTrue(failure("storage.max-age-days", 0).contains("storage.max-age-days"));
        assertTrue(failure("lookup.proxycheck.request-timeout-millis", 100).contains("request-timeout-millis"));
        assertTrue(failure("dry-run", "yes").contains("dry-run must be true or false"));
    }

    @Test void addressesAndPermissionsAreValidated() throws Exception {
        assertTrue(failure("bypass.addresses", List.of("example.com")).contains("bypass.addresses"));
        assertTrue(failure("rules.deny-addresses.list", List.of("192.0.2.0/33")).contains("rules.deny-addresses.list"));
        assertTrue(failure("rules.vpn.bypass-permissions", List.of("has space")).contains("invalid permission"));
        assertTrue(failure("bypass.players", "Alex").contains("must be a list"));
        OriginGateConfig config = TestSupport.config(directory, "bypass.addresses", List.of("2001:db8::/32", "192.0.2.1"),
                "bypass.players", List.of("Alex"));
        assertEquals(2, config.bypass().addresses().size());
        assertTrue(config.bypass().players().contains("alex"));
    }

    @Test void lookupSettingsAreValidated() {
        assertTrue(failure("lookup.on-lookup-failure", "kick").contains("allow, deny"));
        assertTrue(failure("lookup.proxycheck.base-url", "ftp://example.com/").contains("base-url"));
        assertTrue(failure("lookup.proxycheck.base-url", "https://example.com/v3/?x=1").contains("base-url"));
        assertTrue(failure("lookup.proxycheck.api-keys", List.of("bad key")).contains("api-keys"));
    }

    @Test void storageSettingsAreValidated() throws Exception {
        assertTrue(failure("storage.type", "postgres").contains("sqlite or mysql"));
        assertTrue(failure("storage.sqlite.file", "../outside.db").contains("inside the plugin folder"));
        assertTrue(failure("storage.mysql.database", "bad-name").contains("storage.mysql.database"));
        OriginGateConfig mysql = TestSupport.config(directory, "storage.type", "mysql", "storage.mysql.password", "secret-value");
        assertFalse(mysql.storage().mysql().toString().contains("secret-value"));
        assertNull(TestSupport.config(directory).storage().mysql(), "mysql settings are only kept for mysql storage");
        assertTrue(failure("storage.mysql.legacy-table", "ip").contains("Unknown setting"));
    }

    @Test void messagesLoadAndMustBeComplete() throws Exception {
        TestSupport.writeConfig(directory);
        Messages messages = Messages.load(directory);
        for (Rule rule : Rule.values()) assertFalse(messages.kick(rule).isBlank());
        assertFalse(messages.kick(Rule.VPN).contains("<ip>"), "default kick screens do not show the IP");
        String text = Files.readString(directory.resolve("messages.yml"));
        Files.writeString(directory.resolve("messages.yml"), text.replace("  country: |-", "  countri: |-"));
        assertTrue(assertThrows(ConfigException.class, () -> Messages.load(directory)).getMessage().contains("kick.countri"));
    }

    @Test void apiKeysAreNotPrintedByToString() throws Exception {
        OriginGateConfig config = TestSupport.config(directory, "lookup.proxycheck.api-keys", List.of("abc123-secret"));
        assertFalse(config.toString().contains("abc123-secret"));
    }
}
