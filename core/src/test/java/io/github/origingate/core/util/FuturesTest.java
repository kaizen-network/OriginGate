package io.github.origingate.core.util;

import org.junit.jupiter.api.Test;
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
    @Test void cancelledCopyDoesNotCancelSource() throws Exception {
        CompletableFuture<String> source = new CompletableFuture<>();
        Futures.copy(source).cancel(true);
        assertFalse(source.isDone());
        source.complete("ok");
        assertEquals("ok", source.get());
    }
}
