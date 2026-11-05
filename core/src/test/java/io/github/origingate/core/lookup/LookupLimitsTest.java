package io.github.origingate.core.lookup;

import io.github.origingate.core.Log;
import io.github.origingate.core.TestSupport;
import io.github.origingate.core.TestSupport.FakeProvider;
import io.github.origingate.core.TestSupport.FakeStorage;
import io.github.origingate.core.TestSupport.MutableClock;
import io.github.origingate.core.TestSupport.RecordingAlerts;
import io.github.origingate.core.lookup.LookupService.Result;
import io.github.origingate.core.lookup.LookupService.Source;
import io.github.origingate.core.net.Addresses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.github.origingate.core.TestSupport.info;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** IPv6 /64 sharing of flagged answers, dropping lookups nobody waits for, and the lookups-per-minute budget. */
class LookupLimitsTest {
    /** Two addresses in 2001:db8:1:2::/64 and one outside it, in the canonical form the platforms pass in. */
    private static final String FIRST = text("2001:db8:1:2::10");
    private static final String SECOND = text("2001:db8:1:2:ffff::20");
    private static final String OTHER_NETWORK = text("2001:db8:1:3::10");
    private final MutableClock clock = new MutableClock(TestSupport.NOW);
    private final FakeProvider provider = new FakeProvider();
    private final FakeStorage storage = new FakeStorage();
    private final RecordingAlerts alerts = new RecordingAlerts();
    private final LookupStats stats = new LookupStats(clock, ZoneOffset.UTC);
    private ExecutorService workers = Executors.newFixedThreadPool(4);

    @AfterEach void stop() {
        if (provider.gate != null) provider.gate.countDown();
        workers.shutdownNow();
    }

    private static String text(String ip) {
        return Addresses.text(Addresses.parse(ip).orElseThrow());
    }

    /** The rule stand-in: answers flagged as VPN are the ones a rule acts on. */
    private LookupService service(int perMinute) {
        ProviderChain chain = new ProviderChain(List.of(provider), List.of(provider), Duration.ofSeconds(20), clock, Log.NONE,
                alerts, stats);
        return new LookupService(chain, storage, new MemoryCache(100, Duration.ofDays(30), clock),
                Duration.ofDays(30), workers, clock, Log.NONE, perMinute, stats, alerts, IpInfo::vpn);
    }

    private static Result get(CompletableFuture<Result> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private static Throwable failure(CompletableFuture<Result> future) {
        return assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS)).getCause();
    }

    @Test void sharedNetworkIsTheSlash64ForIpv6Only() {
        assertEquals("2001:db8:1:2:0:0:0:0/64", Addresses.sharedNetwork(FIRST));
        assertEquals(Addresses.sharedNetwork(FIRST), Addresses.sharedNetwork(SECOND));
        assertNull(Addresses.sharedNetwork("192.0.2.10"));
        assertNull(Addresses.sharedNetwork("not an address"));
        // Teredo packs the Teredo server's IPv4 address (here 192.0.2.45) into the /64, so its clients share one.
        assertNull(Addresses.sharedNetwork(text("2001:0:c000:22d:8000:1234:3fff:fdd2")));
        assertNull(Addresses.sharedNetwork(text("2001::1")));
        assertEquals("2001:1:0:0:0:0:0:0/64", Addresses.sharedNetwork(text("2001:1::5")), "only 2001::/32 is Teredo");
        // NAT64 well-known prefixes map IPv4 (here 192.0.2.45) into a few /64s.
        assertNull(Addresses.sharedNetwork(text("64:ff9b::c000:22d")));
        assertNull(Addresses.sharedNetwork(text("64:ff9b:1:abcd::c000:22d")));
        assertEquals("64:ff9b:2:0:0:0:0:0/64", Addresses.sharedNetwork(text("64:ff9b:2::1")), "only the two well-known prefixes");
    }

    @Test void unreadableStorageAsksTheProviderBeforeUsingTheSlash64Answer() throws Exception {
        provider.answer(info(FIRST, "Canada", "CA", true, false)).answer(info(SECOND, "Canada", "CA", false, false));
        LookupService service = service(0);
        assertTrue(get(service.lookup(FIRST, false)).info().vpn());
        storage.broken = true;
        Result own = get(service.lookup(SECOND, false));
        assertEquals(Source.PROVIDER, own.source(), "storage may hold this address's own row, so the provider is asked");
        assertFalse(own.info().vpn());
        String third = text("2001:db8:1:2::33");
        failure(service.lookup(third, false));
        assertTrue(service.networkFallback(third).orElseThrow().info().vpn(), "when that lookup fails, the /64 answer is the fallback");
        assertEquals(third, service.networkFallback(third).orElseThrow().info().ip());
        assertTrue(service.networkFallback("192.0.2.1").isEmpty());
    }

    @Test void lookupWithoutAWaiterIsDroppedOnlyWhileBackedUp() throws Exception {
        oneWorker();
        for (int i = 1; i <= 4; i++) provider.answer(info("192.0.2." + i, "Canada", "CA", false, false));
        provider.gate = new CountDownLatch(1);
        LookupService service = service(0);
        CompletableFuture<Result> running = service.lookup("192.0.2.1", false, Duration.ofSeconds(5));
        CompletableFuture<Result> background = service.lookup("192.0.2.2", false, Duration.ZERO);
        CompletableFuture<Result> waiting = service.lookup("192.0.2.3", false, Duration.ofSeconds(30));
        CompletableFuture<Result> lastBackground = service.lookup("192.0.2.4", false, Duration.ZERO);
        provider.gate.countDown();
        get(running);
        assertInstanceOf(LookupService.SkippedException.class, failure(background), "two lookups waited behind it");
        assertEquals("192.0.2.3", get(waiting).info().ip());
        assertEquals("192.0.2.4", get(lastBackground).info().ip(), "nothing waited behind it, so it ran and filled the cache");
        assertEquals(3, provider.calls.get());
    }

    @Test void budgetStateOutlivesAReload() throws Exception {
        for (int i = 1; i <= 5; i++) provider.answer(info("192.0.2." + i, "Canada", "CA", false, false));
        LookupService before = service(1);
        get(before.lookup("192.0.2.1", false));
        failure(before.lookup("192.0.2.2", false));
        assertEquals(1, alerts.problems.size());
        clock.advance(Duration.ofSeconds(30));
        LookupService reloaded = service(1);
        get(reloaded.lookup("192.0.2.3", false));
        assertTrue(alerts.recoveries.isEmpty(), "the new runtime's empty budget is not a quiet minute: " + alerts.recoveries);
        clock.advance(Duration.ofSeconds(61));
        get(reloaded.lookup("192.0.2.4", false));
        assertEquals(1, alerts.recoveries.size());
        assertTrue(alerts.recoveries.get(0).contains("1 new addresses were not checked"), alerts.recoveries.get(0));
    }

    @Test void ownStoredRowComesBeforeTheSlash64Answer() throws Exception {
        storage.rows.put(SECOND, info(SECOND, "Canada", "CA", false, false));
        provider.answer(info(FIRST, "Canada", "CA", true, false));
        LookupService service = service(0);
        assertTrue(get(service.lookup(FIRST, false)).info().vpn());
        Result own = get(service.lookup(SECOND, false));
        assertEquals(Source.STORAGE, own.source(), "after a restart, the address's own saved row decides");
        assertFalse(own.info().vpn());
        assertEquals(1, provider.calls.get());
        assertTrue(get(service.lookup(text("2001:db8:1:2::42"), false)).info().vpn(), "a neighbour without a row gets the /64 answer");
    }

    @Test void flaggedAddressBlocksItsSlash64NeighbourWithoutANewLookup() throws Exception {
        provider.answer(info(FIRST, "Canada", "CA", true, false));
        LookupService service = service(0);
        assertEquals(Source.PROVIDER, get(service.lookup(FIRST, false)).source());
        Result neighbour = get(service.lookup(SECOND, false, Duration.ofSeconds(5)));
        assertEquals(Source.MEMORY, neighbour.source());
        assertEquals(SECOND, neighbour.info().ip(), "the neighbour gets its own address back");
        assertTrue(neighbour.info().vpn());
        assertEquals(1, provider.calls.get(), "no new lookup for the neighbour");

        provider.answer(info(OTHER_NETWORK, "Canada", "CA", true, false));
        assertEquals(Source.PROVIDER, get(service.lookup(OTHER_NETWORK, false)).source());
        assertEquals(2, provider.calls.get(), "another /64 is looked up on its own");
    }

    @Test void cleanAddressDoesNotClearItsNeighbour() throws Exception {
        provider.answer(info(FIRST, "Canada", "CA", false, false)).answer(info(SECOND, "Canada", "CA", true, false));
        LookupService service = service(0);
        assertFalse(get(service.lookup(FIRST, false)).info().vpn());
        Result neighbour = get(service.lookup(SECOND, false));
        assertEquals(Source.PROVIDER, neighbour.source(), "a clean answer is never used for another address");
        assertTrue(neighbour.info().vpn());
        assertEquals(2, provider.calls.get());
        assertFalse(get(service.lookup(FIRST, false)).info().vpn(), "an address's own answer comes before its /64");
        assertTrue(get(service.lookup(text("2001:db8:1:2::77"), false)).info().vpn(), "the flagged answer covers the rest of the /64");
        assertEquals(2, provider.calls.get());
    }

    @Test void neighboursLookedUpAtOnceEachGetTheirOwnLookup() throws Exception {
        provider.answer(info(FIRST, "Canada", "CA", false, false)).answer(info(SECOND, "Canada", "CA", false, false));
        provider.gate = new CountDownLatch(1);
        LookupService service = service(0);
        CompletableFuture<Result> first = service.lookup(FIRST, false, Duration.ofSeconds(5));
        CompletableFuture<Result> second = service.lookup(SECOND, false, Duration.ofSeconds(5));
        provider.gate.countDown();
        assertEquals(FIRST, get(first).info().ip());
        assertEquals(SECOND, get(second).info().ip());
        assertEquals(2, provider.calls.get());
    }

    @Test void storageKeepsOneRowPerAddress() throws Exception {
        provider.answer(info(FIRST, "Canada", "CA", true, false)).answer(info(SECOND, "Canada", "CA", true, false));
        LookupService service = service(0);
        get(service.lookup(FIRST, false));
        for (int i = 0; i < 100 && !storage.rows.containsKey(FIRST); i++) Thread.sleep(20);
        assertEquals(List.of(FIRST), List.copyOf(storage.rows.keySet()));
        assertTrue(service.forget(SECOND), "forgetting a neighbour forgets the answer kept for its /64");
        assertEquals(Source.PROVIDER, get(service.lookup(SECOND, false)).source());
        assertEquals(Source.MEMORY, get(service.lookup(FIRST, false)).source(), "the address's own answer stays");
    }

    @Test void noCountryPauseStaysPerAddress() throws Exception {
        provider.answer(new IpInfo(FIRST, null, null, null, null, null, null, null, null, false, false, null, TestSupport.NOW))
                .answer(info(SECOND, "Canada", "CA", true, false));
        LookupService service = service(0);
        assertInstanceOf(ProviderChain.NoCountryException.class, failure(service.lookup(FIRST, false)));
        assertTrue(failure(service.lookup(FIRST, false)).getMessage().contains("not asking again yet"));
        assertTrue(get(service.lookup(SECOND, false)).info().vpn(), "a paused neighbour would be let in unchecked");
        assertEquals(2, provider.calls.get());
    }

    /** One worker, so the queue counts as backed up when at least one other lookup waits behind the one picked up. */
    private void oneWorker() {
        workers.shutdownNow();
        workers = Executors.newFixedThreadPool(1);
    }

    @Test void staleLookupIsDroppedOnlyWhileTheQueueIsBackedUp() throws Exception {
        oneWorker();
        for (int i = 1; i <= 3; i++) provider.answer(info("192.0.2." + i, "Canada", "CA", false, false));
        provider.gate = new CountDownLatch(1);
        LookupService service = service(0);
        CompletableFuture<Result> running = service.lookup("192.0.2.1", false, Duration.ofSeconds(5));
        CompletableFuture<Result> backedUp = service.lookup("192.0.2.2", false, Duration.ofSeconds(2));
        CompletableFuture<Result> last = service.lookup("192.0.2.3", false, Duration.ofSeconds(2));
        clock.advance(Duration.ofSeconds(3));
        provider.gate.countDown();
        get(running);
        assertInstanceOf(LookupService.SkippedException.class, failure(backedUp), "another lookup waited behind it");
        assertEquals("192.0.2.3", get(last).info().ip(), "nothing waited behind it, so it ran although its login gave up");
        assertEquals(2, provider.calls.get());
        assertEquals(1, stats.dropped());
        assertEquals(0, stats.failed());

        provider.gate = null;
        assertEquals(Source.PROVIDER, get(service.lookup("192.0.2.2", false, Duration.ofSeconds(2))).source(),
                "a new login starts a new lookup");
        assertEquals(Source.MEMORY, get(service.lookup("192.0.2.3", false)).source(), "the late answer filled the cache");
    }

    @Test void queuedLookupIsKeptWhileAnyCallerStillWaits() throws Exception {
        oneWorker();
        for (int i = 1; i <= 3; i++) provider.answer(info("192.0.2." + i, "Canada", "CA", false, false));
        provider.gate = new CountDownLatch(1);
        LookupService service = service(0);
        CompletableFuture<Result> running = service.lookup("192.0.2.1", false, Duration.ofSeconds(5));
        CompletableFuture<Result> expired = service.lookup("192.0.2.2", false, Duration.ofSeconds(1));
        CompletableFuture<Result> command = service.lookup("192.0.2.2", false);
        CompletableFuture<Result> behind = service.lookup("192.0.2.3", false, Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(3));
        provider.gate.countDown();
        get(running);
        assertEquals("192.0.2.2", get(expired).info().ip());
        assertEquals("192.0.2.2", get(command).info().ip(), "a caller without a time limit keeps it, even with a backlog");
        get(behind);
        assertEquals(3, provider.calls.get());
    }

    @Test void budgetLimitsProviderLookupsAndAlertsOnce() throws Exception {
        for (int i = 1; i <= 5; i++) provider.answer(info("192.0.2." + i, "Canada", "CA", false, false));
        LookupService service = service(2);
        get(service.lookup("192.0.2.1", false));
        get(service.lookup("192.0.2.2", false));
        Throwable over = failure(service.lookup("192.0.2.3", false));
        assertInstanceOf(LookupService.BudgetExceededException.class, over);
        assertTrue(over.getMessage().contains("budget of 2 per minute"), over.getMessage());
        failure(service.lookup("192.0.2.4", false));
        assertEquals(2, provider.calls.get());
        assertEquals(1, alerts.problems.size(), alerts.problems.toString());
        assertEquals(2, stats.overBudget());
        assertEquals(2, service.budgetUsed());

        assertEquals(Source.MEMORY, get(service.lookup("192.0.2.1", false)).source(), "memory answers do not use the budget");
        assertEquals(Source.PROVIDER, get(service.lookup("192.0.2.5", true)).source(), "a refresh from a command is not limited");

        clock.advance(Duration.ofSeconds(61));
        assertEquals(Source.PROVIDER, get(service.lookup("192.0.2.3", false)).source());
        assertEquals(1, alerts.recoveries.size());
        assertTrue(alerts.recoveries.get(0).contains("2 new addresses were not checked"), alerts.recoveries.get(0));
        assertEquals(1, alerts.problems.size());
    }

    @Test void steadyFloodSendsOneBudgetAlertAndRecoversAfterAQuietMinute() throws Exception {
        for (int i = 1; i <= 200; i++) provider.answer(info("192.0.2." + i, "Canada", "CA", false, false));
        LookupService service = service(2);
        int over = 0;
        // 6 new addresses a minute for 5 minutes against a limit of 2: a slot frees now and then, the flood goes on.
        for (int i = 1; i <= 30; i++) {
            CompletableFuture<Result> lookup = service.lookup("192.0.2." + i, false);
            try {
                lookup.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException ex) {
                over++;
            }
            clock.advance(Duration.ofSeconds(10));
        }
        assertTrue(over > 10, over + " over the budget");
        assertTrue(provider.calls.get() > 2, "some slots freed during the flood");
        assertEquals(1, alerts.problems.size(), alerts.problems.toString());
        assertTrue(alerts.recoveries.isEmpty(), "no recovery while logins are still over the budget: " + alerts.recoveries);

        clock.advance(Duration.ofSeconds(61));
        get(service.lookup("192.0.2.100", false));
        assertEquals(1, alerts.recoveries.size());
        assertTrue(alerts.recoveries.get(0).contains(over + " new addresses were not checked"), alerts.recoveries.get(0));
    }

    @Test void zeroMeansNoBudget() throws Exception {
        for (int i = 1; i <= 50; i++) provider.answer(info("192.0.2." + i, "Canada", "CA", false, false));
        LookupService service = service(0);
        for (int i = 1; i <= 50; i++) get(service.lookup("192.0.2." + i, false));
        assertEquals(50, provider.calls.get());
        assertTrue(alerts.problems.isEmpty());
    }

    @Test void statsCountSourcesAndHitRate() throws Exception {
        provider.answer(info("192.0.2.1", "Canada", "CA", false, false));
        LookupService service = service(0);
        assertEquals(-1, stats.hitRatePercent());
        get(service.lookup("192.0.2.1", false));
        get(service.lookup("192.0.2.1", false));
        get(service.lookup("192.0.2.1", false));
        failure(service.lookup("192.0.2.200", false));
        assertEquals(2, stats.memory());
        assertEquals(1, stats.provider());
        assertEquals(1, stats.failed());
        assertEquals(50, stats.hitRatePercent());
        assertEquals(2, stats.requestsToday("fake"));
    }
}
