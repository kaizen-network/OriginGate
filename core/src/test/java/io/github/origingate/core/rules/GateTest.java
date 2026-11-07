package io.github.origingate.core.rules;

import io.github.origingate.core.Log;
import io.github.origingate.core.TestSupport;
import io.github.origingate.core.TestSupport.FakeProvider;
import io.github.origingate.core.TestSupport.FakeStorage;
import io.github.origingate.core.config.OriginGateConfig;
import io.github.origingate.core.lists.AccessLists;
import io.github.origingate.core.lists.ListEntry;
import io.github.origingate.core.lists.ListTable;
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
import java.time.Instant;
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

    private Gate gateWithLists(AccessLists lists, boolean namesUnverified, Object... changes) throws Exception {
        OriginGateConfig config = TestSupport.config(directory, changes);
        Clock clock = Clock.fixed(TestSupport.NOW, java.time.ZoneOffset.UTC);
        LookupService lookups = new LookupService(TestSupport.chain(provider), new FakeStorage(), new MemoryCache(100, Duration.ofDays(30), clock),
                Duration.ofDays(30), workers, clock, Log.NONE);
        return new Gate(config, lookups, Log.NONE, namesUnverified, lists);
    }

    private static AccessLists lists(ListEntry... entries) {
        Clock clock = Clock.fixed(TestSupport.NOW, java.time.ZoneOffset.UTC);
        AccessLists lists = AccessLists.load(ListTable.memory(), false, clock, Runnable::run, Log.NONE);
        for (ListEntry entry : entries) lists.add(entry).join();
        return lists;
    }

    private static ListEntry entry(ListEntry.Kind kind, String target, Instant expiresAt, String note) {
        return new ListEntry(kind, target, expiresAt, note, "console", TestSupport.NOW);
    }

    @Test void allowListLetsVpnInWithoutLookup() throws Exception {
        Gate gate = gateWithLists(lists(entry(ListEntry.Kind.ALLOW, "kai", null, "friend")), false);
        Decision decision = run(gate, player("Kai", VPN_IP));
        assertEquals(Outcome.ALLOW, decision.outcome());
        assertNull(decision.rule());
        assertEquals("player is on the allow list: friend", decision.note());
        assertEquals(0, provider.calls.get());
        Gate byAddress = gateWithLists(lists(entry(ListEntry.Kind.ALLOW, "203.0.113.0/24", null, null)), false);
        Decision viaAddress = run(byAddress, player("Alex", VPN_IP));
        assertEquals(Outcome.ALLOW, viaAddress.outcome());
        assertEquals("address is on the allow list (203.0.113.0/24)", viaAddress.note());
        assertEquals(0, provider.calls.get());
    }

    @Test void allowListNameGetsOfflineCaution() throws Exception {
        Decision byName = run(gateWithLists(lists(entry(ListEntry.Kind.ALLOW, "kai", null, null)), true), player("Kai", VPN_IP));
        assertEquals(Gate.UNVERIFIED_NAME, byName.caution());
        Decision byAddress = run(gateWithLists(lists(entry(ListEntry.Kind.ALLOW, "203.0.113.7", null, null)), true), player("Kai", VPN_IP));
        assertEquals(Outcome.ALLOW, byAddress.outcome());
        assertNull(byAddress.caution());
    }

    @Test void denyListKicksBeforeLookup() throws Exception {
        AccessLists lists = lists(entry(ListEntry.Kind.DENY, "198.51.100.0/24", null, "bots"));
        Decision decision = run(gateWithLists(lists, false), player("Kai", PROXY_IP, "origingate.bypass.proxy"));
        assertEquals(Outcome.DENY, decision.outcome());
        assertEquals(Rule.DENY_LIST, decision.rule());
        assertEquals("address is on the deny list (198.51.100.0/24): bots", decision.note());
        assertEquals(0, provider.calls.get());
        Decision dry = run(gateWithLists(lists, false, "dry-run", true), player("Kai", PROXY_IP));
        assertEquals(Outcome.DENY, dry.outcome());
        assertFalse(dry.kicks());
    }

    @Test void configBypassBeatsDenyList() throws Exception {
        Gate gate = gateWithLists(lists(entry(ListEntry.Kind.DENY, "kai", null, null)), false, "bypass.players", List.of("Kai"));
        assertEquals(Outcome.ALLOW, run(gate, player("Kai", VPN_IP)).outcome());
    }

    @Test void expiredDenyEntryIsIgnored() throws Exception {
        Gate gate = gateWithLists(lists(entry(ListEntry.Kind.DENY, "kai", TestSupport.NOW.minusSeconds(1), null)), false);
        Decision decision = run(gate, player("Kai", HOME_IP));
        assertEquals(Outcome.ALLOW, decision.outcome());
        assertNull(decision.rule());
    }

    private static Decision run(Gate gate, LoginAttempt attempt) throws Exception {
        return gate.check(attempt).get(15, TimeUnit.SECONDS);
    }

    @Test void permissionNamesCoverEveryCheckedPermission() throws Exception {
        Gate gate = gate("bypass.permissions", List.of("staff.bypass"), "rules.deny-addresses.bypass-permissions", List.of("staff.deny"));
        assertTrue(gate.permissionNames().containsAll(List.of("staff.bypass", "staff.deny",
                "origingate.bypass.vpn", "origingate.bypass.proxy", "origingate.bypass.country")), gate.permissionNames().toString());
    }

    @Test void finalGlobalBypassWinsOverPrefetchFailure() throws Exception {
        Gate gate = gate("lookup.on-lookup-failure", "deny", "bypass.permissions", List.of("origingate.bypass"));
        Decision decision = gate.finish(player("Kai", VPN_IP, "origingate.bypass"), null,
                new java.util.concurrent.TimeoutException());
        assertEquals(Outcome.ALLOW, decision.outcome());
    }

    @Test void finalRuleBypassUsesActualPlayerPermissions() throws Exception {
        Gate gate = gate();
        LookupService.Result lookup = new LookupService.Result(info(VPN_IP, "Netherlands", "NL", true, false),
                LookupService.Source.PROVIDER);
        assertEquals(Outcome.BYPASS, gate.finish(player("Kai", VPN_IP, "origingate.bypass.vpn"), lookup, null).outcome());
        assertEquals(Outcome.DENY, gate.finish(player("Kai", VPN_IP), lookup, null).outcome());
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
        Decision noBypass = run(gate, player("Steve", VPN_IP));
        assertEquals(Rule.VPN, noBypass.rule());
    }

    @Test void ruleBypassGetsACautionOnlyWhereNamesAreNotChecked() throws Exception {
        OriginGateConfig config = TestSupport.config(directory, "bypass.players", List.of("Steve"));
        Clock clock = Clock.fixed(TestSupport.NOW, java.time.ZoneOffset.UTC);
        LookupService lookups = new LookupService(TestSupport.chain(provider), new FakeStorage(),
                new MemoryCache(100, Duration.ofDays(30), clock), Duration.ofDays(30), workers, clock, Log.NONE);
        Gate offline = new Gate(config, lookups, Log.NONE, true);
        Decision bypassed = run(offline, player("Alex", VPN_IP, "origingate.bypass.vpn"));
        assertEquals(Outcome.BYPASS, bypassed.outcome());
        assertEquals(Gate.UNVERIFIED_NAME, bypassed.caution());
        assertEquals(Gate.UNVERIFIED_NAME, run(offline, player("Steve", VPN_IP)).caution(), "bypass.players too");
        assertNull(run(offline, player("Alex", VPN_IP)).caution(), "a kick needs no caution");
        assertNull(run(offline, player("Alex", HOME_IP)).caution(), "nor a normal join");
        assertNull(run(new Gate(config, lookups, Log.NONE, false), player("Alex", VPN_IP, "origingate.bypass.vpn")).caution());
    }

    @Test void vpnBypassDoesNotCoverTheProxyRule() throws Exception {
        Decision decision = run(gate(), player("Alex", PROXY_IP, "origingate.bypass.vpn"));
        assertEquals(Outcome.DENY, decision.outcome());
        assertEquals(Rule.PROXY, decision.rule());
        assertEquals(Outcome.BYPASS, run(gate(), player("Steve", PROXY_IP, "origingate.bypass.proxy")).outcome());
    }

    @Test void proxyFromAllowedCountryPassesToTheCountryRule() throws Exception {
        Gate gate = gate("rules.proxy.allowed-countries", List.of("de"),
                "rules.country.enabled", true, "rules.country.countries", List.of("CA"));
        Decision decision = run(gate, player("Alex", PROXY_IP));
        assertEquals(Rule.COUNTRY, decision.rule());
        assertEquals(Outcome.DENY, decision.outcome());
    }

    /** A gate whose lookups use the config's budget, as the runtime builds it. */
    private Gate gateWithBudget(Object... changes) throws Exception {
        OriginGateConfig config = TestSupport.config(directory, changes);
        Clock clock = Clock.fixed(TestSupport.NOW, java.time.ZoneOffset.UTC);
        LookupService lookups = new LookupService(TestSupport.chain(provider), new FakeStorage(),
                new MemoryCache(100, Duration.ofDays(30), clock), Duration.ofDays(30), workers, clock, Log.NONE,
                config.lookup().maxLookupsPerMinute(), new io.github.origingate.core.lookup.LookupStats(clock, java.time.ZoneOffset.UTC),
                io.github.origingate.core.Alerts.NONE, info -> Gate.match(config.rules(), info).isPresent());
        return new Gate(config, lookups, Log.NONE);
    }

    @Test void overBudgetFollowsOnLookupFailureUnlessOnBudgetExceededIsSet() throws Exception {
        Gate inherits = gateWithBudget("lookup.max-lookups-per-minute", 1);
        assertEquals(Outcome.ALLOW, run(inherits, player("Alex", HOME_IP)).outcome());
        Decision over = run(inherits, player("Steve", PROXY_IP));
        assertEquals(Rule.LOOKUP_FAILURE, over.rule());
        assertEquals(Outcome.ALLOW, over.outcome(), "an upgraded config sees no new kicks");
        assertTrue(over.note().contains("budget"), over.note());

        Gate denyAll = gateWithBudget("lookup.max-lookups-per-minute", 1, "lookup.on-lookup-failure", "deny");
        run(denyAll, player("Alex", HOME_IP));
        assertEquals(Outcome.DENY, run(denyAll, player("Steve", PROXY_IP)).outcome());

        Gate attackMode = gateWithBudget("lookup.max-lookups-per-minute", 1, "lookup.on-budget-exceeded", "deny");
        assertEquals(Outcome.ALLOW, run(attackMode, player("Alex", HOME_IP)).outcome());
        assertEquals(Outcome.ALLOW, run(attackMode, player("Alex", HOME_IP)).outcome(), "players seen before come from memory");
        Decision refused = run(attackMode, player("Steve", PROXY_IP));
        assertEquals(Rule.LOOKUP_FAILURE, refused.rule());
        assertEquals(Outcome.DENY, refused.outcome());

        provider.fail("192.0.2.99", new LookupException("provider down"));
        Gate otherFailures = gateWithBudget("lookup.max-lookups-per-minute", 5, "lookup.on-budget-exceeded", "deny");
        assertEquals(Outcome.ALLOW, run(otherFailures, player("Alex", "192.0.2.99")).outcome(),
                "other failures still follow on-lookup-failure");
    }

    @Test void onlyAnswersARuleActsOnAreSharedAcrossTheSlash64() throws Exception {
        String first = io.github.origingate.core.net.Addresses.text(java.net.InetAddress.getByName("2001:db8:5:6::1"));
        String neighbour = io.github.origingate.core.net.Addresses.text(java.net.InetAddress.getByName("2001:db8:5:6::2"));
        provider.answer(new IpInfo(first, "Example Cloud", "Example Cloud Ltd", null, null, null, "Canada", "CA", "AS64496",
                false, false, IpInfo.HOSTING, TestSupport.NOW));
        Gate hostingOff = gateWithBudget();
        assertEquals(Outcome.ALLOW, run(hostingOff, player("Alex", first)).outcome());
        assertEquals(Outcome.ALLOW, run(hostingOff, player("Steve", neighbour)).outcome(), "looked up on its own and failed open");
        assertEquals(Rule.LOOKUP_FAILURE, run(hostingOff, player("Steve", neighbour)).rule(), "the clean answer was not shared");

        Gate hostingOn = gateWithBudget("rules.hosting.enabled", true);
        int calls = provider.calls.get();
        assertEquals(Rule.HOSTING, run(hostingOn, player("Alex", first)).rule());
        Decision shared = run(hostingOn, player("Steve", neighbour));
        assertEquals(Rule.HOSTING, shared.rule());
        assertEquals(Outcome.DENY, shared.outcome());
        assertEquals(calls + 1, provider.calls.get(), "the neighbour was kicked from memory without a lookup");
    }

    @Test void slash64AnswerDecidesWhenTheOwnLookupCannotComplete() throws Exception {
        String flagged = io.github.origingate.core.net.Addresses.text(java.net.InetAddress.getByName("2001:db8:7:8::1"));
        String rotated = io.github.origingate.core.net.Addresses.text(java.net.InetAddress.getByName("2001:db8:7:8::99"));
        provider.answer(new IpInfo(flagged, "Example VPN", "Example VPN Ltd", null, null, null, "Canada", "CA", "AS64496",
                true, false, null, TestSupport.NOW));
        Gate gate = gateWithBudget();
        assertEquals(Rule.VPN, run(gate, player("Alex", flagged)).rule());
        // The Bukkit path when every slot is in use, and any other failure: the /64 answer replaces on-lookup-failure.
        Decision busy = gate.finish(player("Steve", rotated), null, new IllegalStateException("too many connections were being checked"));
        assertEquals(Rule.VPN, busy.rule());
        assertEquals(Outcome.DENY, busy.outcome());
        Decision timedOut = gate.finish(player("Steve", rotated), null, new java.util.concurrent.TimeoutException());
        assertEquals(Outcome.DENY, timedOut.outcome());
        assertEquals(Outcome.BYPASS, gate.finish(player("Kai", rotated, "origingate.bypass.vpn"), null, new IllegalStateException("busy")).outcome());
        Decision unrelated = gate.finish(player("Steve", HOME_IP), null, new IllegalStateException("busy"));
        assertEquals(Rule.LOOKUP_FAILURE, unrelated.rule(), "without a /64 answer, on-lookup-failure decides as before");
        assertEquals(Outcome.ALLOW, unrelated.outcome());
    }

    @Test void slash64AnswerDecidesWhenTheQueueIsFull() throws Exception {
        String flagged = io.github.origingate.core.net.Addresses.text(java.net.InetAddress.getByName("2001:db8:9:a::1"));
        String rotated = io.github.origingate.core.net.Addresses.text(java.net.InetAddress.getByName("2001:db8:9:a::2"));
        provider.answer(new IpInfo(flagged, "Example VPN", "Example VPN Ltd", null, null, null, "Canada", "CA", "AS64496",
                true, false, null, TestSupport.NOW));
        OriginGateConfig config = TestSupport.config(directory);
        Clock clock = Clock.fixed(TestSupport.NOW, java.time.ZoneOffset.UTC);
        java.util.concurrent.ThreadPoolExecutor tiny = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new java.util.concurrent.SynchronousQueue<>());
        try {
            LookupService lookups = new LookupService(TestSupport.chain(provider), new FakeStorage(),
                    new MemoryCache(100, Duration.ofDays(30), clock), Duration.ofDays(30), tiny, clock, Log.NONE, 0,
                    new io.github.origingate.core.lookup.LookupStats(clock, java.time.ZoneOffset.UTC),
                    io.github.origingate.core.Alerts.NONE, info -> Gate.match(config.rules(), info).isPresent());
            Gate gate = new Gate(config, lookups, Log.NONE);
            assertEquals(Rule.VPN, run(gate, player("Alex", flagged)).rule());
            provider.gate = new CountDownLatch(1);
            lookups.lookup("192.0.2.200", false);
            Decision full = run(gate, player("Steve", rotated));
            assertEquals(Rule.VPN, full.rule(), "the queue was full, so the /64 answer decided: " + full);
            assertEquals(Outcome.DENY, full.outcome());
        } finally {
            if (provider.gate != null) provider.gate.countDown();
            tiny.shutdownNow();
        }
    }

    private static final String HOSTING_IP = "198.51.100.40";

    private FakeProvider withHosting() {
        return provider.answer(new IpInfo(HOSTING_IP, "Example Cloud", "Example Cloud Ltd", null, "Example City", "Example Region",
                "Canada", "CA", "AS64496", false, false, IpInfo.HOSTING, TestSupport.NOW));
    }

    @Test void hostingRuleKicksHostingNetworksWhenTurnedOn() throws Exception {
        withHosting();
        assertEquals(Outcome.ALLOW, run(gate(), player("Alex", HOSTING_IP)).outcome(), "off by default");
        Gate gate = gate("rules.hosting.enabled", true);
        Decision decision = run(gate, player("Alex", HOSTING_IP));
        assertEquals(Outcome.DENY, decision.outcome());
        assertEquals(Rule.HOSTING, decision.rule());
        assertEquals(Outcome.BYPASS, run(gate, player("Steve", HOSTING_IP, "origingate.bypass.hosting")).outcome());
        assertEquals(Outcome.ALLOW, run(gate, player("Alex", HOME_IP)).outcome(), "residential networks pass");
        assertTrue(gate.permissionNames().contains("origingate.bypass.hosting"));
    }

    @Test void denyNetworksMatchesAsnOrOrganisationText() throws Exception {
        withHosting();
        Gate byAsn = gate("rules.deny-networks.enabled", true, "rules.deny-networks.asns", List.of("as64496"));
        Decision decision = run(byAsn, player("Alex", HOSTING_IP));
        assertEquals(Rule.DENY_NETWORKS, decision.rule());
        assertEquals(Outcome.DENY, decision.outcome());
        assertTrue(decision.note().contains("AS64496"), decision.note());
        assertEquals(Outcome.ALLOW, run(byAsn, player("Alex", HOME_IP)).outcome());

        Gate byName = gate("rules.deny-networks.enabled", true, "rules.deny-networks.organisations", List.of("EXAMPLE CLOUD"));
        assertEquals(Rule.DENY_NETWORKS, run(byName, player("Alex", HOSTING_IP)).rule());
        assertEquals(Outcome.BYPASS, run(byName, player("Steve", HOSTING_IP, "origingate.bypass.deny-networks")).outcome());
    }

    @Test void denyNetworksIsCheckedBeforeTheVpnRule() throws Exception {
        Gate gate = gate("rules.deny-networks.enabled", true, "rules.deny-networks.asns", List.of("AS64500"));
        Decision decision = run(gate, player("Alex", VPN_IP, "origingate.bypass.vpn"));
        assertEquals(Rule.DENY_NETWORKS, decision.rule());
        assertEquals(Outcome.DENY, decision.outcome(), "a VPN bypass does not cover a denied network");
    }

    private static final String SCHOOL_IP = "198.51.100.77";

    private void withSchool() {
        provider.answer(new IpInfo(SCHOOL_IP, "Example Net", "Example School District", null, "Example City", "Example Region",
                "Canada", "CA", "AS64999", false, true, "Residential", TestSupport.NOW));
    }

    @Test void allowNetworksLetsInFlaggedNetwork() throws Exception {
        Gate gate = gate("rules.allow-networks.enabled", true, "rules.allow-networks.asns", List.of("AS64500"));
        Decision decision = run(gate, player("Kai", VPN_IP));
        assertEquals(Outcome.ALLOW, decision.outcome());
        assertEquals(Rule.ALLOW_NETWORKS, decision.rule());
        assertEquals("network is on the allow list (AS64500)", decision.note());
    }

    @Test void allowNetworksIgnoresNames() throws Exception {
        withSchool();
        Gate gate = gate("rules.allow-networks.enabled", true, "rules.allow-networks.asns", List.of("AS64500"));
        Decision decision = run(gate, player("Kai", SCHOOL_IP));
        assertEquals(Outcome.DENY, decision.outcome());
        assertEquals(Rule.PROXY, decision.rule());
    }

    @Test void allowNetworksExemptsHostingToo() throws Exception {
        withHosting();
        Gate gate = gate("rules.hosting.enabled", true, "rules.allow-networks.enabled", true,
                "rules.allow-networks.asns", List.of("AS64496"));
        Decision decision = run(gate, player("Kai", HOSTING_IP));
        assertEquals(Outcome.ALLOW, decision.outcome());
        assertEquals(Rule.ALLOW_NETWORKS, decision.rule());
    }

    @Test void allowNetworksKeepsCountryAndDenyNetworks() throws Exception {
        Gate country = gate("rules.allow-networks.enabled", true, "rules.allow-networks.asns", List.of("AS64500"),
                "rules.country.enabled", true, "rules.country.countries", List.of("CA"));
        Decision byCountry = run(country, player("Kai", VPN_IP));
        assertEquals(Outcome.DENY, byCountry.outcome());
        assertEquals(Rule.COUNTRY, byCountry.rule());

        Gate denied = gate("rules.allow-networks.enabled", true, "rules.allow-networks.asns", List.of("AS64500"),
                "rules.deny-networks.enabled", true, "rules.deny-networks.asns", List.of("AS64500"));
        Decision byNetwork = run(denied, player("Kai", VPN_IP));
        assertEquals(Outcome.DENY, byNetwork.outcome());
        assertEquals(Rule.DENY_NETWORKS, byNetwork.rule());
    }

    @Test void cleanAnswerFromAllowedNetworkHasNoRule() throws Exception {
        Gate gate = gate("rules.allow-networks.enabled", true, "rules.allow-networks.asns", List.of("AS64500"));
        Decision decision = run(gate, player("Kai", HOME_IP));
        assertEquals(Outcome.ALLOW, decision.outcome());
        assertNull(decision.rule());
    }

    @Test void matchSkipsExemptedRules() throws Exception {
        OriginGateConfig config = TestSupport.config(directory, "rules.allow-networks.enabled", true,
                "rules.allow-networks.asns", List.of("AS64500"));
        assertFalse(Gate.match(config.rules(), info(VPN_IP, "Netherlands", "NL", true, true)).isPresent());
        OriginGateConfig plain = TestSupport.config(directory);
        assertTrue(Gate.match(plain.rules(), info(VPN_IP, "Netherlands", "NL", true, true)).isPresent());
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
        assertEquals(Outcome.BYPASS, run(deny, player("Steve", HOME_IP, "origingate.bypass.country")).outcome());
    }

    @Test void globalBypassSkipsTheLookup() throws Exception {
        Gate gate = gate("bypass.permissions", List.of("vip.pass"), "bypass.players",
                List.of("Noor", TestSupport.player("Sunny", VPN_IP).uuid().toString()), "bypass.addresses", List.of("203.0.113.0/24"));
        assertEquals(Outcome.ALLOW, run(gate, player("Alex", PROXY_IP, "vip.pass")).outcome());
        assertEquals(Outcome.ALLOW, run(gate, player("noor", PROXY_IP)).outcome());
        assertEquals(Outcome.ALLOW, run(gate, player("Sunny", PROXY_IP)).outcome());
        assertEquals(Outcome.ALLOW, run(gate, player("Ari", VPN_IP)).outcome());
        assertEquals(0, provider.calls.get());
        assertEquals(Outcome.DENY, run(gate, player("Zuri", PROXY_IP)).outcome());
    }

    @Test void denyAddressesAreCheckedBeforeAnyLookup() throws Exception {
        Gate gate = gate("rules.deny-addresses.enabled", true, "rules.deny-addresses.list", List.of("192.0.2.0/24"),
                "rules.deny-addresses.bypass-permissions", List.of("trusted"));
        Decision decision = run(gate, player("Alex", HOME_IP));
        assertEquals(Outcome.DENY, decision.outcome());
        assertEquals(Rule.DENY_ADDRESSES, decision.rule());
        assertEquals(Outcome.BYPASS, run(gate, player("Steve", HOME_IP, "trusted")).outcome());
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
