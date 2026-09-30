package io.github.origingate.core;

import io.github.origingate.core.report.DecisionLine;
import io.github.origingate.core.rules.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

/** Runtime lifecycle shared by server adapters. Reload is called on a worker thread. */
public final class RuntimeController implements AutoCloseable {
    private final Path directory;
    private final Log log;
    private volatile OriginGateRuntime runtime;
    private volatile boolean closed;
    public RuntimeController(Path directory, Log log) { this.directory = directory; this.log = log; }
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
            log.info("OriginGate is running. Lookups: " + next.lookupSummary());
            return "OriginGate reloaded." + (next.config().dryRun() ? " Dry run is on: nobody is kicked." : "");
        } catch (Exception ex) {
            log.warn("OriginGate reload failed: " + Text.message(ex), ex);
            return "Reload failed, " + (runtime == null ? "OriginGate is not checking connections" : "the previous settings stay active")
                    + ": " + Text.message(ex);
        }
    }
    public void cleanup() {
        OriginGateRuntime active = runtime;
        if (active == null) return;
        try { active.deleteExpired(); } catch (Exception ex) { log.warn("OriginGate cleanup failed", ex); }
    }
    public void report(OriginGateRuntime active, LoginAttempt attempt, Decision decision) {
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
    /** Marks stopping immediately; platform event threads never wait for reload or I/O cleanup. */
    public void closeAsync() {
        closed = true;
        Thread closer = new Thread(this::close, "OriginGate Shutdown");
        closer.setDaemon(true);
        closer.start();
    }
    @Override public synchronized void close() {
        closed = true;
        OriginGateRuntime old = runtime;
        runtime = null;
        if (old != null) old.close();
    }
}
