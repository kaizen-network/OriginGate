package io.github.origingate.core.rules;

import io.github.origingate.core.Log;
import io.github.origingate.core.TestSupport;
import io.github.origingate.core.TestSupport.FakeProvider;
import io.github.origingate.core.TestSupport.FakeStorage;
import io.github.origingate.core.config.OriginGateConfig;
import io.github.origingate.core.lookup.IpInfo;
import io.github.origingate.core.lookup.LookupException;
import io.github.origingate.core.lookup.LookupService;
import io.github.origingate.core.lookup.MemoryCache;
import io.github.origingate.core.rules.Decision.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.github.origingate.core.TestSupport.info;
import static io.github.origingate.core.TestSupport.player;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GateTest {
    private static final String VPN_IP = "203.0.113.7";
    private static final String PROXY_IP = "198.51.100.9";
    private static final String HOME_IP = "192.0.2.10";

    @TempDir Path directory;
    private final FakeProvider provider = new FakeProvider()
            .answer(info(VPN_IP, "Netherlands", "NL", true, true))
            .answer(info(PROXY_IP, "Germany", "DE", false, true))
            .answer(info(HOME_IP, "Canada", "CA", false, false));
    private final ExecutorService workers = Executors.newFixedThreadPool(4);

    @AfterEach void stop() {
        if (provider.gate != null) provider.gate.countDown();
        workers.shutdownNow();
    }

    private Gate gate(Object... changes) throws Exception {
        OriginGateConfig config = TestSupport.config(directory, changes);
        Clock clock = Clock.fixed(TestSupport.NOW, java.time.ZoneOffset.UTC);
        LookupService lookups = new LookupService(TestSupport.chain(provider), new FakeStorage(), new MemoryCache(100, Duration.ofDays(30), clock),
                Duration.ofDays(30), workers, clock, Log.NONE);
        return new Gate(config, lookups, Log.NONE);
    }

    private static Decision run(Gate gate, LoginAttempt attempt) throws Exception {
        return gate.check(attempt).get(15, TimeUnit.SECONDS);
    }

    @Test void finalGlobalBypassWinsOverPrefetchFailure() throws Exception {
        Gate gate = gate("lookup.on-lookup-failure", "deny", "bypass.permissions", List.of("origingate.bypass"));
        Decision decision = gate.finish(player("FixtureUser", VPN_IP, "origingate.bypass"), null,
                new java.util.concurrent.TimeoutException());
        assertEquals(Outcome.ALLOW, decision.outcome());
    }

    @Test void finalRuleBypassUsesActualPlayerPermissions() throws Exception {
        Gate gate = gate();
        LookupService.Result lookup = new LookupService.Result(info(VPN_IP, "Netherlands", "NL", true, false),
                LookupService.Source.PROVIDER);
        assertEquals(Outcome.BYPASS, gate.finish(player("FixtureUser", VPN_IP, "origingate.bypass.vpn"), lookup, null).outcome());
        assertEquals(Outcome.DENY, gate.finish(player("FixtureUser", VPN_IP), lookup, null).outcome());
    }

    @Test void vpnIsKickedWithoutBypass() throws Exception {
        Decision decision = run(gate(), player("Alex", VPN_IP));
        assertEquals(Outcome.DENY, decision.outcome());
        assertEquals(Rule.VPN, decision.rule());
        assertTrue(decision.kicks());
    }

    @Test void anyOneOfSeveralRuleBypassPermissionsIsEnough() throws Exception {
        Gate gate = gate("rules.vpn.bypass-permissions", List.of("staff.one", "staff.two"));
        Decision decision = run(gate, player("Alex", VPN_IP, "staff.two"));
        assertEquals(Outcome.BYPASS, decision.outcome());
        assertEquals(Rule.VPN, decision.rule());
        assertFalse(decision.kicks());
    }

    @Test void firstMatchingRuleDecidesEvenWhenBypassed() throws Exception {
        // VPN and proxy from a country outside the allowlist: the VPN rule matches first and is bypassed.
        Gate gate = gate("rules.country.enabled", true, "rules.country.countries", List.of("CA"));
        Decision decision = run(gate, player("Alex", VPN_IP, "origingate.bypass.vpn"));
        assertEquals(Outcome.BYPASS, decision.outcome());
        assertEquals(Rule.VPN, decision.rule());
        Decision noBypass = run(gate, player("Blake", VPN_IP));
        assertEquals(Rule.VPN, noBypass.rule());
    }

    @Test void vpnBypassDoesNotCoverTheProxyRule() throws Exception {
        Decision decision = run(gate(), player("Alex", PROXY_IP, "origingate.bypass.vpn"));
        assertEquals(Outcome.DENY, decision.outcome());
        assertEquals(Rule.PROXY, decision.rule());
        assertEquals(Outcome.BYPASS, run(gate(), player("Blake", PROXY_IP, "origingate.bypass.proxy")).outcome());
    }

    @Test void proxyFromAllowedCountryPassesToTheCountryRule() throws Exception {
        Gate gate = gate("rules.proxy.allowed-countries", List.of("de"),
                "rules.country.enabled", true, "rules.country.countries", List.of("CA"));
        Decision decision = run(gate, player("Alex", PROXY_IP));
        assertEquals(Rule.COUNTRY, decision.rule());
        assertEquals(Outcome.DENY, decision.outcome());
    }

    @Test void disabledRulesAreSkipped() throws Exception {
        Gate gate = gate("rules.vpn.enabled", false, "rules.proxy.enabled", false);
        Decision decision = run(gate, player("Alex", VPN_IP));
        assertEquals(Outcome.ALLOW, decision.outcome());
        assertNull(decision.rule());
    }

    @Test void countryAllowlistAndDenylistUseCodes() throws Exception {
        Gate allow = gate("rules.country.enabled", true, "rules.country.countries", List.of("CA", "MX"));
        assertEquals(Outcome.ALLOW, run(allow, player("Alex", HOME_IP)).outcome());
        Gate deny = gate("rules.country.enabled", true, "rules.country.mode", "denylist", "rules.country.countries", List.of("CA"));
        Decision decision = run(deny, player("Alex", HOME_IP));
        assertEquals(Rule.COUNTRY, decision.rule());
        assertEquals(Outcome.DENY, decision.outcome());
        assertEquals(Outcome.BYPASS, run(deny, player("Blake", HOME_IP, "origingate.bypass.country")).outcome());
    }

    @Test void globalBypassSkipsTheLookup() throws Exception {
        Gate gate = gate("bypass.permissions", List.of("vip.pass"), "bypass.players",
                List.of("Casey", TestSupport.player("Drew", VPN_IP).uuid().toString()), "bypass.addresses", List.of("203.0.113.0/24"));
        assertEquals(Outcome.ALLOW, run(gate, player("Alex", PROXY_IP, "vip.pass")).outcome());
        assertEquals(Outcome.ALLOW, run(gate, player("casey", PROXY_IP)).outcome());
        assertEquals(Outcome.ALLOW, run(gate, player("Drew", PROXY_IP)).outcome());
        assertEquals(Outcome.ALLOW, run(gate, player("Emery", VPN_IP)).outcome());
        assertEquals(0, provider.calls.get());
        assertEquals(Outcome.DENY, run(gate, player("Frankie", PROXY_IP)).outcome());
    }

    @Test void denyAddressesAreCheckedBeforeAnyLookup() throws Exception {
        Gate gate = gate("rules.deny-addresses.enabled", true, "rules.deny-addresses.list", List.of("192.0.2.0/24"),
                "rules.deny-addresses.bypass-permissions", List.of("trusted"));
        Decision decision = run(gate, player("Alex", HOME_IP));
        assertEquals(Outcome.DENY, decision.outcome());
        assertEquals(Rule.DENY_ADDRESSES, decision.rule());
        assertEquals(Outcome.BYPASS, run(gate, player("Blake", HOME_IP, "trusted")).outcome());
        assertEquals(0, provider.calls.get());
    }

    @Test void bypassAddressWinsOverDenyAddress() throws Exception {
        Gate gate = gate("bypass.addresses", List.of(HOME_IP), "rules.deny-addresses.enabled", true,
                "rules.deny-addresses.list", List.of("192.0.2.0/24"));
        assertEquals(Outcome.ALLOW, run(gate, player("Alex", HOME_IP)).outcome());
    }

    @Test void privateAddressesSkipTheLookupOnlyWhenConfigured() throws Exception {
        provider.answer(info("192.168.1.5", "Canada", "CA", true, false));
        Decision skipped = run(gate(), player("Alex", "192.168.1.5"));
        assertEquals(Outcome.ALLOW, skipped.outcome());
        assertEquals(0, provider.calls.get());
        Decision checked = run(gate("lookup.skip-private-addresses", false), player("Alex", "192.168.1.5"));
        assertEquals(Rule.VPN, checked.rule());
        assertEquals(1, provider.calls.get());
    }

    @Test void lookupFailureFollowsTheConfiguredMode() throws Exception {
        provider.fail("192.0.2.99", new LookupException("provider down"));
        Decision allowed = run(gate(), player("Alex", "192.0.2.99"));
        assertEquals(Outcome.ALLOW, allowed.outcome());
        assertEquals(Rule.LOOKUP_FAILURE, allowed.rule());
        assertTrue(allowed.note().contains("provider down"));
        Decision denied = run(gate("lookup.on-lookup-failure", "deny"), player("Alex", "192.0.2.99"));
        assertEquals(Outcome.DENY, denied.outcome());
        assertEquals(Rule.LOOKUP_FAILURE, denied.rule());
        assertTrue(denied.kicks());
    }

    @Test void slowLookupTimesOutAfterWaitMillis() throws Exception {
        provider.gate = new CountDownLatch(1);
        Gate gate = gate("lookup.wait-millis", 1000, "lookup.on-lookup-failure", "deny");
        long start = System.nanoTime();
        Decision decision = run(gate, player("Alex", HOME_IP));
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertEquals(Rule.LOOKUP_FAILURE, decision.rule());
        assertEquals(Outcome.DENY, decision.outcome());
        assertTrue(decision.note().contains("longer than 1000 ms"), decision.note());
        assertTrue(millis >= 900 && millis < 5000, "took " + millis + " ms");
    }

    @Test void dryRunLogsTheDenyButDoesNotKick() throws Exception {
        Decision decision = run(gate("dry-run", true), player("Alex", VPN_IP));
        assertEquals(Outcome.DENY, decision.outcome());
        assertTrue(decision.dryRun());
        assertFalse(decision.kicks());
        assertEquals("WOULD-DENY", io.github.origingate.core.report.DecisionLine.label(decision));
    }
}
