package io.github.origingate.core.lookup;

import io.github.origingate.core.Log;
import io.github.origingate.core.TestSupport;
import io.github.origingate.core.TestSupport.FakeProvider;
import io.github.origingate.core.TestSupport.MutableClock;
import io.github.origingate.core.lookup.ProviderChain.Answer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static io.github.origingate.core.TestSupport.info;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderChainTest {
    private static final String IP = "192.0.2.10";
    private final MutableClock clock = new MutableClock(TestSupport.NOW);

    private ProviderChain chain(List<LookupProvider> countryFrom, List<LookupProvider> vpnFrom) {
        return new ProviderChain(countryFrom, vpnFrom, Duration.ofSeconds(5), clock, Log.NONE);
    }

    private static IpInfo countryOnly(String code) {
        return new IpInfo(IP, null, null, null, "Example City", "Example Region", "Example Country", code, "AS64501",
                false, false, null, TestSupport.NOW);
    }

    @Test void countryAndVpnFromDifferentProvidersAreMerged() throws Exception {
        FakeProvider maxmind = new FakeProvider("maxmind").answer(countryOnly("GB"));
        FakeProvider proxycheck = new FakeProvider("proxycheck").answer(new IpInfo(IP, "VPN Net", "VPN Org", "ExampleVPN",
                "Other City", "Other Region", "Netherlands", "NL", null, true, false, "VPN", TestSupport.NOW));
        Answer answer = chain(List.of(maxmind), List.of(proxycheck)).lookup(IP);
        IpInfo merged = answer.info();
        assertEquals("GB", merged.countryCode());
        assertEquals("Example Country", merged.country());
        assertEquals("Example City", merged.city());
        assertEquals("Example Region", merged.region());
        assertTrue(merged.vpn());
        assertFalse(merged.proxy());
        assertEquals("VPN Net", merged.provider());
        assertEquals("VPN Org", merged.organisation());
        assertEquals("ExampleVPN", merged.operatorName());
        assertEquals("VPN", merged.type());
        assertEquals("AS64501", merged.asn(), "taken from the country answer when the VPN answer has none");
        assertEquals("maxmind+proxycheck", answer.answeredBy().joined());
        assertEquals("maxmind (country), proxycheck (vpn)", answer.answeredBy().described());
    }

    @Test void providerInBothListsIsAskedOnce() throws Exception {
        FakeProvider proxycheck = new FakeProvider("proxycheck").answer(info(IP, "Netherlands", "NL", true, false));
        Answer answer = chain(List.of(proxycheck), List.of(proxycheck)).lookup(IP);
        assertEquals(1, proxycheck.calls.get());
        assertTrue(answer.info().vpn());
        assertEquals("proxycheck", answer.answeredBy().joined());
        assertEquals("proxycheck (country, vpn)", answer.answeredBy().described());
    }

    @Test void vpnListOrderWinsOverReuse() throws Exception {
        FakeProvider proxycheck = new FakeProvider("proxycheck").answer(info(IP, "Canada", "CA", false, false));
        FakeProvider iphub = new FakeProvider("iphub").answer(info(IP, "Canada", "CA", true, false));
        Answer answer = chain(List.of(proxycheck), List.of(iphub, proxycheck)).lookup(IP);
        assertEquals("iphub", answer.answeredBy().vpn());
        assertTrue(answer.info().vpn());
        assertEquals(1, proxycheck.calls.get());
        assertEquals(1, iphub.calls.get());
    }

    @Test void nextProviderIsAskedAfterAFailureOrNoCountry() throws Exception {
        FakeProvider down = new FakeProvider("down").fail(IP, new LookupException("HTTP 500"));
        FakeProvider empty = new FakeProvider("empty").answer(countryOnly(null));
        FakeProvider unknownCode = new FakeProvider("unknown").answer(countryOnly("ZZ"));
        FakeProvider good = new FakeProvider("good").answer(countryOnly("gb"));
        Answer answer = chain(List.of(down, empty, unknownCode, good), List.of()).lookup(IP);
        assertEquals("GB", answer.info().countryCode());
        assertEquals("good", answer.answeredBy().country());
    }

    @Test void onlyAnswersWithoutCountryIsANoCountryFailure() {
        FakeProvider empty = new FakeProvider("empty").answer(countryOnly(null));
        assertThrows(ProviderChain.NoCountryException.class, () -> chain(List.of(empty), List.of()).lookup(IP));
        FakeProvider down = new FakeProvider("down").fail(IP, new LookupException("HTTP 500"));
        LookupException failure = assertThrows(LookupException.class, () -> chain(List.of(down), List.of()).lookup(IP));
        assertFalse(failure instanceof ProviderChain.NoCountryException);
        assertTrue(failure.getMessage().contains("down: HTTP 500"), failure.getMessage());
    }

    @Test void noVpnAnswerFailsTheWholeLookup() {
        FakeProvider country = new FakeProvider("maxmind").answer(countryOnly("GB"));
        FakeProvider down = new FakeProvider("iphub").fail(IP, new LookupException("HTTP 500"));
        LookupException failure = assertThrows(LookupException.class,
                () -> chain(List.of(country), List.of(down)).lookup(IP));
        assertTrue(failure.getMessage().contains("VPN check"), failure.getMessage());
        assertTrue(failure.getMessage().contains("iphub: HTTP 500"), failure.getMessage());
    }

    @Test void emptyVpnListGivesNoFlags() throws Exception {
        FakeProvider country = new FakeProvider("maxmind").answer(info(IP, "Canada", "CA", true, true));
        ProviderChain chain = chain(List.of(country), List.of());
        Answer answer = chain.lookup(IP);
        assertFalse(answer.info().vpn());
        assertFalse(answer.info().proxy());
        assertNull(answer.answeredBy().vpn());
        assertEquals("maxmind", answer.answeredBy().joined());
        assertEquals("maxmind (country)", answer.answeredBy().described());
        assertEquals("country from maxmind | vpn from none", chain.describe());
    }

    @Test void refusingProviderIsSkippedForAMinute() throws Exception {
        FakeProvider refusing = new FakeProvider("iphub").fail(IP, new KeyRejectedException("HTTP 429"));
        FakeProvider backup = new FakeProvider("proxycheck").answer(info(IP, "Canada", "CA", false, false));
        ProviderChain chain = chain(List.of(backup), List.of(refusing, backup));
        assertEquals("proxycheck", chain.lookup(IP).answeredBy().vpn());
        chain.lookup(IP);
        assertEquals(1, refusing.calls.get());
        clock.advance(ProviderChain.REFUSED_PAUSE.plusSeconds(1));
        chain.lookup(IP);
        assertEquals(2, refusing.calls.get());
    }

    @Test void plainFailureDoesNotPauseTheProvider() throws Exception {
        FakeProvider flaky = new FakeProvider("proxycheck").fail(IP, new LookupException("HTTP 500"));
        FakeProvider backup = new FakeProvider("maxmind").answer(countryOnly("GB"));
        ProviderChain chain = chain(List.of(flaky, backup), List.of());
        chain.lookup(IP);
        chain.lookup(IP);
        assertEquals(2, flaky.calls.get());
    }

    @Test void noNewProviderIsAskedAfterTheTimeBudget() {
        FakeProvider slow = new FakeProvider("slow").fail(IP, new LookupException("timed out"));
        slow.onLookup = () -> clock.advance(Duration.ofSeconds(6));
        FakeProvider next = new FakeProvider("next").answer(countryOnly("GB"));
        LookupException failure = assertThrows(LookupException.class,
                () -> chain(List.of(slow, next), List.of()).lookup(IP));
        assertEquals(0, next.calls.get());
        assertTrue(failure.getMessage().contains("no time left to ask next"), failure.getMessage());
    }

    @Test void providerThatFailedIsNotAskedAgainForTheVpnCheck() throws Exception {
        FakeProvider flaky = new FakeProvider("proxycheck").fail(IP, new LookupException("timed out"));
        FakeProvider maxmind = new FakeProvider("maxmind").answer(countryOnly("GB"));
        FakeProvider iphub = new FakeProvider("iphub").answer(info(IP, "Canada", "CA", true, false));
        Answer answer = chain(List.of(flaky, maxmind), List.of(flaky, iphub)).lookup(IP);
        assertEquals(1, flaky.calls.get());
        assertEquals("iphub", answer.answeredBy().vpn());
    }

    @Test void describeListsBothJobsInOrder() {
        FakeProvider maxmind = new FakeProvider("maxmind");
        FakeProvider proxycheck = new FakeProvider("proxycheck");
        FakeProvider iphub = new FakeProvider("iphub");
        assertEquals("country from maxmind, proxycheck | vpn from proxycheck, iphub",
                chain(List.of(maxmind, proxycheck), List.of(proxycheck, iphub)).describe());
    }
    private ProviderChain watched(FakeProvider provider, TestSupport.RecordingAlerts alerts, LookupStats stats) {
        return new ProviderChain(List.of(provider), List.of(provider), Duration.ofSeconds(5), clock, Log.NONE, alerts, stats);
    }

    @Test void pausedProviderIsReportedOnceAndAgainWhenItAnswers() throws Exception {
        FakeProvider provider = new FakeProvider("proxycheck").fail(IP, new KeyRejectedException("status denied: daily limit"));
        TestSupport.RecordingAlerts alerts = new TestSupport.RecordingAlerts();
        LookupStats stats = new LookupStats(clock, java.time.ZoneOffset.UTC);
        ProviderChain chain = watched(provider, alerts, stats);
        assertThrows(LookupException.class, () -> chain.lookup(IP));
        assertEquals(1, alerts.problems.size());
        assertTrue(alerts.problems.get(0).contains("proxycheck refused the request (status denied: daily limit)"),
                alerts.problems.get(0));
        assertTrue(chain.status().get(0).startsWith("proxycheck: paused for 60 s after: status denied"), chain.status().toString());

        assertThrows(LookupException.class, () -> chain.lookup(IP));
        assertEquals(1, provider.calls.get(), "a paused provider is not asked");
        clock.advance(Duration.ofSeconds(61));
        assertThrows(LookupException.class, () -> chain.lookup(IP));
        assertEquals(2, provider.calls.get());
        assertEquals(1, alerts.problems.size(), "a provider that is still refused is not reported again");

        clock.advance(Duration.ofSeconds(61));
        provider.answer(info(IP, "Canada", "CA", false, false));
        chain.lookup(IP);
        assertEquals(List.of("proxycheck answers lookups again."), alerts.recoveries);
        assertEquals("proxycheck: ok, 3 requests today", chain.status().get(0));
        assertEquals(3, stats.requestsToday("proxycheck"));
    }

    @Test void failuresInARowAreReportedOnceFromTheThird() throws Exception {
        FakeProvider provider = new FakeProvider("ip-api").fail(IP, new LookupException("ip-api did not answer within 3500 ms"));
        TestSupport.RecordingAlerts alerts = new TestSupport.RecordingAlerts();
        ProviderChain chain = watched(provider, alerts, new LookupStats(clock, java.time.ZoneOffset.UTC));
        assertThrows(LookupException.class, () -> chain.lookup(IP));
        assertThrows(LookupException.class, () -> chain.lookup(IP));
        assertTrue(alerts.problems.isEmpty(), "two failures may be bad luck");
        assertTrue(chain.status().get(0).startsWith("ip-api: failing (2 in a row"), chain.status().toString());
        assertThrows(LookupException.class, () -> chain.lookup(IP));
        assertThrows(LookupException.class, () -> chain.lookup(IP));
        assertEquals(1, alerts.problems.size());
        assertTrue(alerts.problems.get(0).contains("ip-api failed 3 lookups in a row, last: ip-api did not answer within 3500 ms"),
                alerts.problems.get(0));
        assertEquals(4, provider.calls.get(), "timeouts and server errors do not pause the provider");

        provider.answer(info(IP, "Canada", "CA", false, false));
        chain.lookup(IP);
        assertEquals(1, alerts.recoveries.size());
        chain.lookup(IP);
        assertEquals(1, alerts.recoveries.size(), "a provider that was fine is not reported");
    }

    @Test void oneSuccessResetsTheFailureCount() throws Exception {
        FakeProvider provider = new FakeProvider("proxycheck").fail(IP, new LookupException("HTTP 502"))
                .answer(info("192.0.2.11", "Canada", "CA", false, false));
        TestSupport.RecordingAlerts alerts = new TestSupport.RecordingAlerts();
        ProviderChain chain = watched(provider, alerts, new LookupStats(clock, java.time.ZoneOffset.UTC));
        assertThrows(LookupException.class, () -> chain.lookup(IP));
        assertThrows(LookupException.class, () -> chain.lookup(IP));
        chain.lookup("192.0.2.11");
        assertThrows(LookupException.class, () -> chain.lookup(IP));
        assertThrows(LookupException.class, () -> chain.lookup(IP));
        assertTrue(alerts.problems.isEmpty());
        assertTrue(alerts.recoveries.isEmpty());
    }
}
