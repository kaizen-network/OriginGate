package io.github.origingate.core.lookup;

import io.github.origingate.core.Log;
import io.github.origingate.core.TestSupport;
import io.github.origingate.core.TestSupport.FakeProvider;
import io.github.origingate.core.TestSupport.FakeStorage;
import io.github.origingate.core.TestSupport.MutableClock;
import io.github.origingate.core.lookup.LookupService.Result;
import io.github.origingate.core.lookup.LookupService.Source;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static io.github.origingate.core.TestSupport.info;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LookupServiceTest {
    private static final String IP = "192.0.2.10";
    private final MutableClock clock = new MutableClock(TestSupport.NOW);
    private final FakeProvider provider = new FakeProvider().answer(info(IP, "Canada", "CA", false, false));
    private final FakeStorage storage = new FakeStorage();
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private final LookupService service = new LookupService(TestSupport.chain(provider), storage,
            new MemoryCache(100, Duration.ofDays(30), clock), Duration.ofDays(30), workers, clock, Log.NONE);

    @AfterEach void stop() {
        if (provider.gate != null) provider.gate.countDown();
        workers.shutdownNow();
    }

    private Result get(boolean refresh) throws Exception {
        return service.lookup(IP, refresh).get(10, TimeUnit.SECONDS);
    }

    private void awaitSaved(String ip) throws InterruptedException {
        for (int i = 0; i < 100 && !storage.rows.containsKey(ip); i++) Thread.sleep(20);
        assertTrue(storage.rows.containsKey(ip), "not saved");
    }

    @Test void freshResultNamesWhoAnswered() throws Exception {
        assertEquals("fake (country, vpn)", get(false).answeredBy().described());
        assertNull(get(false).answeredBy(), "memory results have no provider names");
    }

    @Test void resultWithoutVpnCheckIsNotSaved() throws Exception {
        ExecutorService single = Executors.newSingleThreadExecutor();
        LookupService countryOnly = new LookupService(
                new ProviderChain(List.of(provider), List.of(), Duration.ofSeconds(20), clock, Log.NONE), storage,
                new MemoryCache(100, Duration.ofDays(30), clock), Duration.ofDays(30), single, clock, Log.NONE);
        assertEquals(Source.PROVIDER, countryOnly.lookup(IP, false).get(10, TimeUnit.SECONDS).source());
        single.shutdown();
        assertTrue(single.awaitTermination(10, TimeUnit.SECONDS));
        assertFalse(storage.rows.containsKey(IP), "a later config with VPN checks must not reuse it");
        assertEquals(Source.MEMORY, countryOnly.lookup(IP, false).get(10, TimeUnit.SECONDS).source());
    }

    @Test void providerResultIsSavedAndThenServedFromMemory() throws Exception {
        assertEquals(Source.PROVIDER, get(false).source());
        awaitSaved(IP);
        assertEquals(Source.MEMORY, get(false).source());
        assertEquals(1, provider.calls.get());
    }

    @Test void loginGetsTheAnswerBeforeTheSaveFinishes() throws Exception {
        storage.saveGate = new CountDownLatch(1);
        try {
            assertEquals(Source.PROVIDER, get(false).source());
            assertFalse(storage.rows.containsKey(IP));
        } finally {
            storage.saveGate.countDown();
        }
        awaitSaved(IP);
    }

    @Test void storageIsSkippedForAWhileAfterAFailure() throws Exception {
        provider.answer(info("192.0.2.11", "Canada", "CA", false, false));
        storage.broken = true;
        assertEquals(Source.PROVIDER, get(false).source());
        int calls = storage.calls.get();
        assertEquals(1, calls, "one failed read, then the save is skipped");
        service.lookup("192.0.2.11", false).get(10, TimeUnit.SECONDS);
        assertEquals(calls, storage.calls.get(), "storage is not tried during the pause");
        storage.broken = false;
        clock.advance(LookupService.STORAGE_PAUSE.plusSeconds(1));
        service.cache().clear();
        service.lookup("192.0.2.11", false).get(10, TimeUnit.SECONDS);
        assertTrue(storage.calls.get() > calls, "storage is tried again after the pause");
    }

    @Test void noCountryResultIsNotAskedAgainForAWhile() throws Exception {
        provider.answer(new IpInfo(IP, "Example", null, null, null, null, null, null, null, false, false, null, TestSupport.NOW));
        assertThrows(ExecutionException.class, () -> get(false));
        ExecutionException again = assertThrows(ExecutionException.class, () -> get(false));
        assertTrue(again.getCause().getMessage().contains("not asking again yet"));
        assertEquals(1, provider.calls.get());
        assertThrows(ExecutionException.class, () -> get(true));
        assertEquals(2, provider.calls.get(), "refresh always asks");
        clock.advance(LookupService.NO_COUNTRY_PAUSE.plusSeconds(1));
        provider.answer(info(IP, "Canada", "CA", false, false));
        assertEquals(Source.PROVIDER, get(false).source());
        assertEquals(3, provider.calls.get());
    }

    @Test void refreshDoesNotJoinARunningNormalLookup() throws Exception {
        provider.gate = new CountDownLatch(1);
        CompletableFuture<Result> normal = service.lookup(IP, false);
        CompletableFuture<Result> refresh = service.lookup(IP, true);
        // Both must reach the provider before either finishes; otherwise the normal lookup could read the saved refresh.
        for (int i = 0; i < 500 && provider.calls.get() < 2; i++) Thread.sleep(10);
        assertEquals(2, provider.calls.get(), "the refresh must make its own request");
        provider.gate.countDown();
        normal.get(10, TimeUnit.SECONDS);
        assertEquals(Source.PROVIDER, refresh.get(10, TimeUnit.SECONDS).source());
    }

    @Test void storageComesBeforeTheProvider() throws Exception {
        storage.rows.put(IP, info(IP, "Canada", "CA", false, false));
        assertEquals(Source.STORAGE, get(false).source());
        assertEquals(0, provider.calls.get());
    }

    @Test void rowsOlderThanMaxAgeAreLookedUpAgain() throws Exception {
        storage.rows.put(IP, info(IP, "Canada", "CA", false, false));
        clock.advance(Duration.ofDays(31));
        assertEquals(Source.PROVIDER, get(false).source());
        assertEquals(1, provider.calls.get());
    }

    @Test void concurrentLookupsForOneIpShareOneRequest() throws Exception {
        provider.gate = new CountDownLatch(1);
        List<CompletableFuture<Result>> waiting = new ArrayList<>();
        for (int i = 0; i < 10; i++) waiting.add(service.lookup(IP, false));
        provider.gate.countDown();
        for (CompletableFuture<Result> future : waiting) assertEquals("Canada", future.get(10, TimeUnit.SECONDS).info().country());
        assertEquals(1, provider.calls.get());
        // A new lookup after the first finished is answered from memory.
        assertEquals(Source.MEMORY, get(false).source());
    }

    @Test void resultWithCountryNameButNoCodeFails() {
        provider.answer(info(IP, "Canada", null, false, false));
        assertThrows(ExecutionException.class, () -> get(false));
        assertTrue(storage.rows.isEmpty());
    }

    @Test void resultWithoutCountryFailsAndIsNotSaved() {
        provider.answer(new IpInfo(IP, "Example", null, null, null, null, null, null, null, false, false, null, TestSupport.NOW));
        ExecutionException failure = assertThrows(ExecutionException.class, () -> get(false));
        assertInstanceOf(LookupException.class, failure.getCause());
        assertTrue(storage.rows.isEmpty());
        assertEquals(0, service.cache().size());
    }

    @Test void storageFailuresAreSkipped() throws Exception {
        storage.broken = true;
        assertEquals(Source.PROVIDER, get(false).source());
        assertEquals(Source.MEMORY, get(false).source());
    }

    @Test void refreshSkipsMemoryAndStorage() throws Exception {
        get(false);
        assertEquals(Source.PROVIDER, get(true).source());
        assertEquals(2, provider.calls.get());
    }

    @Test void fullQueueFailsTheLookup() throws Exception {
        provider.gate = new CountDownLatch(1);
        ThreadPoolExecutor tiny = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
        try {
            LookupService small = new LookupService(TestSupport.chain(provider), storage, new MemoryCache(100, Duration.ofDays(30), clock),
                    Duration.ofDays(30), tiny, clock, Log.NONE);
            provider.answer(info("192.0.2.11", "Canada", "CA", false, false));
            provider.answer(info("192.0.2.12", "Canada", "CA", false, false));
            small.lookup(IP, false);
            small.lookup("192.0.2.11", false);
            CompletableFuture<Result> rejected = small.lookup("192.0.2.12", false);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> rejected.get(5, TimeUnit.SECONDS));
            assertInstanceOf(LookupException.class, failure.getCause());
        } finally {
            provider.gate.countDown();
            tiny.shutdownNow();
        }
    }
}
