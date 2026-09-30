package io.github.origingate.bukkit;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DeferredAdmissionTest {
    @Test void reconnectReusesPendingLookupWithoutWaiting() {
        DeferredAdmission<String> admission = new DeferredAdmission<>(2, Clock.systemUTC());
        Object runtime = new Object();
        CompletableFuture<String> lookup = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        assertSame(lookup, admission.poll("connection", runtime, () -> { calls.incrementAndGet(); return lookup; }));
        assertSame(lookup, admission.poll("connection", runtime, () -> { fail("duplicate lookup"); return null; }));
        lookup.complete("result");
        assertEquals("result", admission.poll("connection", runtime, () -> null).join());
        assertEquals(1, calls.get());
    }

    @Test void reloadDoesNotReusePreviousSettingsAndFailureSurvivesUntilReconnect() {
        DeferredAdmission<String> admission = new DeferredAdmission<>(2, Clock.systemUTC());
        CompletableFuture<String> stale = new CompletableFuture<>();
        admission.poll("connection", new Object(), () -> stale);
        Object current = new Object();
        CompletableFuture<String> failure = new CompletableFuture<>();
        assertSame(failure, admission.poll("connection", current, () -> failure));
        failure.completeExceptionally(new IllegalStateException("provider failed"));
        assertSame(failure, admission.poll("connection", current, () -> null));
    }
}
