package io.github.origingate.core.util;

import java.util.concurrent.*;

/** Per-caller deadlines never cancel a shared lookup. Idle timer threads expire. */
public final class Futures {
    private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "OriginGate Deadlines");
        thread.setDaemon(true);
        return thread;
    });
    static {
        TIMER.setRemoveOnCancelPolicy(true);
        TIMER.setKeepAliveTime(1, TimeUnit.SECONDS);
        TIMER.allowCoreThreadTimeOut(true);
    }
    private Futures() { }
    public static <T> CompletableFuture<T> copy(CompletableFuture<T> source) {
        return source.thenApply(value -> value);
    }
    public static <T> CompletableFuture<T> timeout(CompletableFuture<T> source, long time, TimeUnit unit) {
        CompletableFuture<T> result = copy(source);
        if (result.isDone()) return result;
        ScheduledFuture<?> deadline = TIMER.schedule(() -> result.completeExceptionally(new TimeoutException()), time, unit);
        result.whenComplete((value, error) -> deadline.cancel(false));
        return result;
    }
}
