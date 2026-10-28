package io.github.origingate.core.util;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class FuturesTest {
    @Test void timeoutDoesNotPoisonOtherCaller() throws Exception {
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> other = Futures.copy(source);
        CompletableFuture<String> timed = Futures.timeout(source, 10, TimeUnit.MILLISECONDS);
        assertThrows(ExecutionException.class, () -> timed.get(2, TimeUnit.SECONDS));
        assertFalse(source.isDone());
        source.complete("ok");
        assertEquals("ok", other.get());
    }
    @Test void slowDependentDoesNotDelayAnotherTimeout() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        try {
            Futures.timeout(new CompletableFuture<String>(), 100, TimeUnit.MILLISECONDS).whenComplete((value, error) -> {
                blocked.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            });
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            CompletableFuture<String> other = Futures.timeout(new CompletableFuture<>(), 10, TimeUnit.MILLISECONDS);
            assertThrows(ExecutionException.class, () -> other.get(1, TimeUnit.SECONDS),
                    "A blocked dependent of one timeout must not hold up another");
        } finally {
            release.countDown();
        }
    }
    @Test void burstOfTimeoutsSharesAFewThreads() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch busy = new CountDownLatch(4);
        List<CompletableFuture<Object>> timeouts = new ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) {
                timeouts.add(Futures.timeout(new CompletableFuture<>(), 50, TimeUnit.MILLISECONDS).handle((value, error) -> {
                    busy.countDown();
                    try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                    return null;
                }));
            }
            assertTrue(busy.await(2, TimeUnit.SECONDS));
            Thread.sleep(200);
            long threads = Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().equals("OriginGate Timeouts")).count();
            assertTrue(threads <= 4, "A burst of timeouts must not start a thread each, saw " + threads);
        } finally {
            release.countDown();
        }
        for (CompletableFuture<Object> timeout : timeouts) timeout.get(5, TimeUnit.SECONDS);
    }
    @Test void cancelledCopyDoesNotCancelSource() throws Exception {
        CompletableFuture<String> source = new CompletableFuture<>();
        Futures.copy(source).cancel(true);
        assertFalse(source.isDone());
        source.complete("ok");
        assertEquals("ok", source.get());
    }
}
