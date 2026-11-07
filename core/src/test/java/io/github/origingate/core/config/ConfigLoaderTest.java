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
        assertEquals(List.of("proxycheck"), config.lookup().countryFrom());
        assertEquals(List.of("proxycheck"), config.lookup().vpnFrom());
        assertEquals("https://proxycheck.io/v3/", config.lookup().proxycheck().baseUrl().toString());
        assertTrue(config.lookup().proxycheck().apiKeys().isEmpty());
        assertFalse(config.lookup().ipApi().pro());
        assertFalse(config.lookup().maxmind().autoUpdate());
        assertEquals(directory.resolve("data/GeoLite2-Country.mmdb").toAbsolutePath().normalize(), config.lookup().maxmind().file());
        assertEquals("sqlite", config.storage().type());
        assertEquals(30, config.storage().maxAgeDays());
        assertEquals(30, config.storage().keepDays());
        assertEquals(30, config.logFileKeepDays());
        assertFalse(config.rules().country().enabled());
        assertFalse(config.rules().denyAddresses().enabled());
        assertTrue(config.rules().vpn().enabled());
        assertEquals(List.of("origingate.bypass.vpn"), config.rules().vpn().bypassPermissions());
        assertTrue(config.bypass().permissions().isEmpty());
        assertEquals(directory.resolve("data/origingate.db").toAbsolutePath().normalize(), config.storage().sqliteFile());
    }

    @Test void newSettingsDefaultToOffForNewInstalls() throws Exception {
        OriginGateConfig config = TestSupport.config(directory);
        assertEquals(2500, config.lookup().waitMillis());
        assertEquals(0, config.lookup().maxLookupsPerMinute());
        assertEquals(OriginGateConfig.FailureMode.ALLOW, config.lookup().onBudgetExceeded());
        assertEquals(OriginGateConfig.FailureMode.DENY,
                TestSupport.config(directory, "lookup.on-lookup-failure", "deny").lookup().onBudgetExceeded(),
                "without the setting, over the budget follows on-lookup-failure");
        assertEquals(OriginGateConfig.FailureMode.DENY,
                TestSupport.config(directory, "lookup.on-budget-exceeded", "deny").lookup().onBudgetExceeded());
        assertEquals(OriginGateConfig.FailureMode.ALLOW, TestSupport.config(directory, "lookup.on-lookup-failure", "deny",
                "lookup.on-budget-exceeded", "allow").lookup().onBudgetExceeded());
        assertFalse(config.rules().hosting().enabled());
        assertEquals(List.of("origingate.bypass.hosting"), config.rules().hosting().bypassPermissions());
        assertFalse(config.rules().denyNetworks().enabled());
        assertEquals(List.of("origingate.bypass.deny-networks"), config.rules().denyNetworks().bypassPermissions());
    }

    /** The files a 1.0.0 install wrote, unchanged, must load and mean exactly what they meant in 1.0.0. */
    @Test void unchanged100FilesLoadAsBefore() throws Exception {
        TestSupport.copyResource("/upgrade/config-1.0.0.yml", directory.resolve("config.yml"));
        TestSupport.copyResource("/upgrade/messages-1.0.0.yml", directory.resolve("messages.yml"));
        OriginGateConfig legacy = ConfigLoader.load(directory);
        assertEquals(5000, legacy.lookup().waitMillis(), "an existing install keeps its wait-millis");
        assertEquals(0, legacy.lookup().maxLookupsPerMinute());
        assertFalse(legacy.rules().hosting().enabled());
        assertTrue(legacy.rules().hosting().bypassPermissions().isEmpty());
        assertFalse(legacy.rules().denyNetworks().enabled());
        assertTrue(legacy.rules().denyNetworks().asns().isEmpty());

        OriginGateConfig current = TestSupport.config(directory, "lookup.wait-millis", 5000,
                "lookup.max-lookups-per-minute", TestSupport.REMOVE, "rules.hosting", TestSupport.REMOVE,
                "rules.deny-networks", TestSupport.REMOVE);
        assertEquals(current, legacy, "apart from the new defaults, the 1.0.0 file reads the same as the current one");

        TestSupport.copyResource("/upgrade/messages-1.0.0.yml", directory.resolve("messages.yml"));
        Messages messages = Messages.load(directory);
        assertTrue(messages.kick(Rule.HOSTING).contains("hosting or datacenter networks"), "missing kicks use the bundled text");
        assertTrue(messages.kick(Rule.DENY_NETWORKS).contains("Connections from your network are not allowed"));
        assertTrue(messages.kick(Rule.VPN).contains("VPN connections are not allowed"));
        assertTrue(messages.alertLookupProblem().contains("<message>"));
        assertTrue(messages.alertLookupRecovered().contains("<message>"));
    }

    @Test void newRulesAreChecked() throws Exception {
        assertTrue(failure("rules.hosting.enabled", true, "lookup.vpn-from", List.of("iphub"),
                "lookup.iphub.api-keys", List.of("example-key")).contains("rules.hosting needs proxycheck or ip-api"));
        assertTrue(TestSupport.config(directory, "rules.hosting.enabled", true, "lookup.vpn-from", List.of("ip-api"))
                .rules().hosting().enabled());
        assertTrue(failure("rules.deny-networks.enabled", true, "lookup.country-from", List.of("maxmind"),
                "lookup.vpn-from", List.of(), "rules.vpn.enabled", false, "rules.proxy.enabled", false)
                .contains("rules.deny-networks needs a provider other than maxmind"));
        assertTrue(failure("rules.deny-networks.asns", List.of("AS-12")).contains("needs AS numbers"));
        assertTrue(failure("rules.deny-networks.organisations", List.of("ab")).contains("at least 3 characters"));
        OriginGateConfig.NetworkRule networks = TestSupport.config(directory, "rules.deny-networks.asns", List.of("64496", "as64511"),
                "rules.deny-networks.organisations", List.of("Example Hosting")).rules().denyNetworks();
        assertEquals(List.of("AS64496", "AS64511"), networks.asns());
        assertEquals(List.of("example hosting"), networks.organisations());
        assertTrue(failure("lookup.max-lookups-per-minute", -1).contains("from 0 to 100000"));
        assertTrue(failure("rules.hosting.typo", true).contains("Unknown setting: rules.hosting.typo"));
    }

    @Test void allowNetworksNeedsNetworkData() throws Exception {
        assertTrue(failure("rules.allow-networks.enabled", true, "lookup.country-from", List.of("maxmind"),
                "lookup.vpn-from", List.of(), "rules.vpn.enabled", false, "rules.proxy.enabled", false)
                .contains("rules.allow-networks"));
    }

    @Test void allowNetworksRejectsOrganisations() throws Exception {
        assertTrue(failure("rules.allow-networks.organisations", List.of("Example School"))
                .contains("Unknown setting: rules.allow-networks.organisations"));
    }

    @Test void oldConfigTurnsNewFeaturesOff() throws Exception {
        TestSupport.copyResource("/upgrade/config-1.0.0.yml", directory.resolve("config.yml"));
        TestSupport.copyResource("/upgrade/messages-1.0.0.yml", directory.resolve("messages.yml"));
        OriginGateConfig legacy = ConfigLoader.load(directory);
        assertFalse(legacy.rules().allowNetworks().enabled());
        Messages messages = Messages.load(directory);
        assertTrue(messages.kick(io.github.origingate.core.rules.Rule.DENY_LIST).startsWith("<red>You are not allowed on this server.</red>"));
    }

    @Test void olderConfigWithoutProviderSettingsUsesProxycheck() throws Exception {
        OriginGateConfig config = TestSupport.config(directory, "lookup.country-from", TestSupport.REMOVE,
                "lookup.vpn-from", TestSupport.REMOVE, "lookup.iphub", TestSupport.REMOVE, "lookup.ip-api", TestSupport.REMOVE,
                "lookup.ipinfo", TestSupport.REMOVE, "lookup.maxmind", TestSupport.REMOVE);
        assertEquals(List.of("proxycheck"), config.lookup().countryFrom());
        assertEquals(List.of("proxycheck"), config.lookup().vpnFrom());
        assertNull(config.lookup().iphub());
        assertNull(config.lookup().maxmind());
        assertEquals(3500, config.lookup().longestRequestMillis());
    }

    @Test void providerNamesAreChecked() throws Exception {
        OriginGateConfig config = TestSupport.config(directory, "lookup.country-from", List.of("MaxMind", "proxycheck"),
                "lookup.vpn-from", List.of("proxycheck", "ip-api"));
        assertEquals(List.of("maxmind", "proxycheck"), config.lookup().countryFrom());
        assertEquals(List.of("maxmind", "proxycheck", "ip-api"), config.lookup().inUse());
        assertTrue(failure("lookup.country-from", List.of("geoip")).startsWith("lookup.country-from has an unknown provider: geoip"));
        assertEquals("lookup.vpn-from cannot use maxmind: its free data has no VPN check",
                failure("lookup.vpn-from", List.of("maxmind")));
        assertEquals("lookup.vpn-from cannot use ipinfo: its free data has no VPN check",
                failure("lookup.vpn-from", List.of("ipinfo")));
        assertEquals("lookup.country-from lists proxycheck twice", failure("lookup.country-from", List.of("proxycheck", "proxycheck")));
        assertEquals("lookup.country-from needs at least one provider", failure("lookup.country-from", List.of()));
    }

    @Test void emptyVpnListNeedsTheVpnAndProxyRulesOff() throws Exception {
        assertEquals("lookup.vpn-from is empty, so rules.vpn must be disabled", failure("lookup.vpn-from", List.of()));
        assertEquals("lookup.vpn-from is empty, so rules.proxy must be disabled",
                failure("lookup.vpn-from", List.of(), "rules.vpn.enabled", false));
        OriginGateConfig config = TestSupport.config(directory, "lookup.country-from", List.of("maxmind"), "lookup.vpn-from", List.of(),
                "rules.vpn.enabled", false, "rules.proxy.enabled", false);
        assertTrue(config.lookup().vpnFrom().isEmpty());
        assertEquals(0, config.lookup().longestRequestMillis());
    }

    @Test void listedProvidersNeedTheirSettings() throws Exception {
        assertEquals("lookup.iphub.api-keys needs at least one key to use IPHub", failure("lookup.vpn-from", List.of("iphub")));
        assertEquals("lookup.ipinfo.token must be set to use IPinfo", failure("lookup.country-from", List.of("ipinfo")));
        assertEquals("Missing setting: lookup.iphub",
                failure("lookup.vpn-from", List.of("iphub"), "lookup.iphub", TestSupport.REMOVE));
        assertEquals("Unknown setting: lookup.iphub.typo", failure("lookup.iphub.typo", true));
        OriginGateConfig config = TestSupport.config(directory, "lookup.country-from", List.of("iphub"),
                "lookup.vpn-from", List.of("iphub"), "lookup.iphub.api-keys", List.of("abc+/="),
                "lookup.iphub.request-timeout-millis", 2000);
        assertEquals(List.of("abc+/="), config.lookup().iphub().apiKeys());
        assertEquals(2000, config.lookup().longestRequestMillis());
    }

    @Test void maxmindSettingsAreChecked() throws Exception {
        assertEquals("lookup.maxmind.edition must be GeoLite2-Country or GeoLite2-City",
                failure("lookup.maxmind.edition", "GeoLite2-ASN"));
        assertTrue(failure("lookup.maxmind.account-id", 123456).contains("must both be set"));
        assertTrue(failure("lookup.maxmind.license-key", "abc_123").contains("must both be set"));
        assertTrue(failure("lookup.maxmind.file", "../outside.mmdb").contains("inside the plugin folder"));
        OriginGateConfig config = TestSupport.config(directory, "lookup.maxmind.account-id", 123456,
                "lookup.maxmind.license-key", "abc_123", "lookup.maxmind.edition", "GeoLite2-City");
        assertTrue(config.lookup().maxmind().autoUpdate());
        assertEquals("GeoLite2-City", config.lookup().maxmind().edition());
    }

    @Test void secretsAreNotPrinted() throws Exception {
        OriginGateConfig config = TestSupport.config(directory,
                "lookup.proxycheck.api-keys", List.of("secret-proxycheck"),
                "lookup.iphub.api-keys", List.of("secret-iphub"),
                "lookup.ip-api.api-key", "secret-ipapi",
                "lookup.ipinfo.token", "secret-ipinfo",
                "lookup.maxmind.account-id", 123456, "lookup.maxmind.license-key", "secret_maxmind");
        String text = config.lookup().toString();
        assertFalse(text.contains("secret"), text);
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

    @Test void keepDaysMayEqualMaxAgeDays() throws Exception {
        assertEquals(60, TestSupport.config(directory, "storage.max-age-days", 60, "storage.keep-days", 60).storage().keepDays());
    }

    @Test void numbersAreBounded() {
        assertTrue(failure("lookup.wait-millis", 999).contains("lookup.wait-millis must be a whole number from 1000 to 20000"));
        assertTrue(failure("lookup.wait-millis", 20001).contains("lookup.wait-millis"));
        assertTrue(failure("storage.max-age-days", 0).contains("storage.max-age-days"));
        assertTrue(failure("storage.keep-days", 29).contains("storage.keep-days must be 0 or at least max-age-days (30)"));
        assertTrue(failure("log-file-keep-days", -1).contains("log-file-keep-days"));
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
        for (Rule rule : Rule.values()) if (rule.kicks()) assertFalse(messages.kick(rule).isBlank());
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
