package io.github.origingate.core;

import io.github.origingate.core.config.OriginGateConfig;
import io.github.origingate.core.report.DecisionLine;
import io.github.origingate.core.rules.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/** Runtime lifecycle shared by server adapters. Reload is called on a worker thread. */
public final class RuntimeController implements AutoCloseable {
    private final Path directory;
    private final Log log;
    private final AtomicInteger droppedReports = new AtomicInteger();
    private final ThreadPoolExecutor reports = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), task -> {
                Thread thread = new Thread(task, "OriginGate Reports");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private volatile OriginGateRuntime runtime;
    private volatile boolean closed;
    public RuntimeController(Path directory, Log log) {
        this.directory = directory;
        this.log = log;
        reports.allowCoreThreadTimeOut(true);
    }
    public OriginGateRuntime runtime() { return runtime; }
    public synchronized String reload() {
        if (closed) return "OriginGate is stopping.";
        try {
            OriginGateRuntime.installDefaults(directory);
            OriginGateRuntime next = OriginGateRuntime.load(directory, log);
            if (closed) {
                next.close();
                return "OriginGate is stopping.";
            }
            OriginGateRuntime old = runtime;
            runtime = next;
            if (old != null) old.retire();
            logStarted(next, old == null ? "OriginGate is running" : "OriginGate reloaded");
            return "OriginGate reloaded." + (next.config().dryRun() ? " Dry run is on: nobody is kicked." : "");
        } catch (Exception ex) {
            if (runtime == null) {
                log.error("OriginGate could not start and is NOT checking connections: " + Text.message(ex), ex);
                log.error("Fix the problem, then run: origingate reload", null);
            } else {
                log.warn("OriginGate reload failed: " + Text.message(ex), null);
            }
            return "Reload failed, " + (runtime == null ? "OriginGate is not checking connections" : "the previous settings stay active")
                    + ": " + Text.message(ex);
        }
    }
    private void logStarted(OriginGateRuntime next, String prefix) {
        OriginGateConfig config = next.config();
        log.info(prefix + " with " + config.storage().type() + " storage" + (config.dryRun() ? " in DRY-RUN mode (nobody is kicked)" : "") + ".");
        log.info("Lookups: " + next.lookupSummary() + ".");
        if (config.lookup().uses("proxycheck") && config.lookup().proxycheck().apiKeys().isEmpty()) {
            log.warn("No proxycheck.io API keys are set; proxycheck.io allows 100 lookups per day without a key.", null);
        }
    }
    public void cleanup() {
        OriginGateRuntime active = runtime;
        if (active == null) return;
        try {
            OriginGateRuntime.Cleanup cleanup = active.deleteExpired();
            if (cleanup.rows() > 0 || cleanup.files() > 0) {
                log.info("Deleted " + cleanup.rows() + " expired lookups and " + cleanup.files() + " old log files");
            }
        } catch (Exception ex) {
            log.warn("Could not delete expired OriginGate data: " + Text.message(ex), null);
        }
    }
    /** Decision logging never blocks platform events or the shared lookup deadline thread. */
    public void report(OriginGateRuntime active, LoginAttempt attempt, Decision decision) {
        if (closed) return;
        if (!active.config().consoleLog().shows(decision) && (active.decisionFile() == null || decision.rule() == null)) return;
        try {
            reports.execute(() -> {
                int dropped = droppedReports.getAndSet(0);
                if (dropped > 0) log.warn("Skipped " + dropped + " connection log entries because logging could not keep up.", null);
                writeReport(active, attempt, decision);
            });
        } catch (RejectedExecutionException ex) {
            if (!closed) droppedReports.incrementAndGet();
        }
    }
    private void writeReport(OriginGateRuntime active, LoginAttempt attempt, Decision decision) {
        String line = DecisionLine.format(attempt, decision);
        if (active.config().consoleLog().shows(decision)) log.info(line);
        if (active.decisionFile() != null && decision.rule() != null) {
            try { active.decisionFile().write(line); } catch (Exception ex) { log.warn("Could not write OriginGate log", ex); }
        }
    }
    /** Snapshot on the platform's allowed thread, before asynchronous rule evaluation. */
    public static Predicate<String> permissions(OriginGateRuntime active, Predicate<String> source) {
        Set<String> names = new HashSet<>(active.config().bypass().permissions());
        names.addAll(active.config().rules().denyAddresses().bypassPermissions());
        names.addAll(active.config().rules().vpn().bypassPermissions());
        names.addAll(active.config().rules().proxy().bypassPermissions());
        names.addAll(active.config().rules().country().bypassPermissions());
        Set<String> granted = new HashSet<>();
        for (String name : names) if (source.test(name)) granted.add(name);
        return granted::contains;
    }
    /**
     * Stops taking logins at once, then waits up to {@code waitMillis} for the runtime to close and queued log lines
     * to be written. The JVM exit or plugin unload that follows would cut the close short. A reload in progress keeps
     * the close waiting, and is not waited for beyond the limit.
     */
    public void stop(long waitMillis) {
        closed = true;
        reports.shutdown();
        Thread closer = new Thread(this::close, "OriginGate Shutdown");
        closer.setDaemon(true);
        closer.start();
        try {
            closer.join(waitMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
    @Override public synchronized void close() {
        closed = true;
        reports.shutdown();
        OriginGateRuntime old = runtime;
        runtime = null;
        try {
            if (old != null) old.close();
        } finally {
            try {
                if (!reports.awaitTermination(5, TimeUnit.SECONDS)) reports.shutdownNow();
            } catch (InterruptedException ex) {
                reports.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
