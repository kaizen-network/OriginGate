package io.github.origingate.core;

import io.github.origingate.core.rules.Decision;
import io.github.origingate.core.rules.LoginAttempt;
import io.github.origingate.core.rules.Rule;
import io.github.origingate.core.util.Futures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeControllerTest {
    @TempDir Path directory;

    @Test void busyDecisionFileDoesNotBlockReportingCallerEvenUnderLoad() throws Exception {
        try (RuntimeController controller = new RuntimeController(directory, Log.NONE)) {
            controller.reload();
            OriginGateRuntime active = controller.runtime();
            assertNotNull(active);
            LoginAttempt attempt = attempt();
            Decision decision = new Decision(Decision.Outcome.DENY, Rule.DENY_ADDRESSES, "blocked", null, false);
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                synchronized (active.decisionFile()) {
                    Future<?> returned = caller.submit(() -> {
                        for (int i = 0; i < 1024; i++) controller.report(active, attempt, decision);
                    });
                    assertDoesNotThrow(() -> returned.get(1, TimeUnit.SECONDS),
                            "Reporting must not wait for a busy log file, even when its queue fills");
                }
            } finally {
                caller.shutdown();
                assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
            }
            assertDecisionLogged();
        }
    }

    @Test void busyDecisionFileDoesNotDelayAnotherLookupDeadline() throws Exception {
        try (RuntimeController controller = new RuntimeController(directory, Log.NONE)) {
            controller.reload();
            OriginGateRuntime active = controller.runtime();
            assertNotNull(active);
            LoginAttempt attempt = attempt();
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch secondFinished = new CountDownLatch(1);
            synchronized (active.decisionFile()) {
                Futures.timeout(new CompletableFuture<>(), 100, TimeUnit.MILLISECONDS).whenComplete((result, error) -> {
                    firstStarted.countDown();
                    controller.report(active, attempt, active.gate().finish(attempt, null, error));
                });
                assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
                Futures.timeout(new CompletableFuture<>(), 25, TimeUnit.MILLISECONDS)
                        .whenComplete((result, error) -> secondFinished.countDown());
                assertTrue(secondFinished.await(500, TimeUnit.MILLISECONDS),
                        "A blocked log write must not delay another lookup's timeout");
            }
            assertDecisionLogged();
        }
    }

    private LoginAttempt attempt() throws Exception {
        return new LoginAttempt("Efe", null, InetAddress.getByName("203.0.113.12"), permission -> false);
    }

    private void assertDecisionLogged() {
        Path file = directory.resolve("logs").resolve(LocalDate.now() + ".log");
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            while (!Files.exists(file) || !Files.readString(file).contains("player=Efe")) Thread.sleep(10);
        });
    }

    @Test void stopDoesNotWaitPastItsLimitForReloadMonitorAndPreventsAnotherReload() throws Exception {
        RuntimeController controller = new RuntimeController(directory, Log.NONE);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread reload = new Thread(() -> {
            synchronized (controller) {
                locked.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
        });
        reload.start();
        assertTrue(locked.await(2, TimeUnit.SECONDS));
        try { assertTimeoutPreemptively(Duration.ofMillis(1000), () -> controller.stop(200)); }
        finally { release.countDown(); reload.join(2000); }
        assertEquals("OriginGate is stopping.", controller.reload());
        assertNull(controller.runtime());
    }

    @Test void stopClosesRuntimeAndWritesQueuedLogLinesBeforeReturning() throws Exception {
        RuntimeController controller = new RuntimeController(directory, Log.NONE);
        controller.reload();
        OriginGateRuntime active = controller.runtime();
        assertNotNull(active);
        controller.report(active, attempt(), new Decision(Decision.Outcome.DENY, Rule.DENY_ADDRESSES, "blocked", null, false));
        controller.stop(5000);
        assertNull(controller.runtime());
        assertTrue(Files.readString(directory.resolve("logs").resolve(LocalDate.now() + ".log")).contains("player=Efe"),
                "A queued line must be written before the plugin is unloaded");
    }

    @Test void stopAlsoClosesRuntimeReplacedByRecentReload() throws Exception {
        RuntimeController controller = new RuntimeController(directory, Log.NONE);
        controller.reload();
        OriginGateRuntime replaced = controller.runtime();
        controller.reload();
        assertFalse(replaced.isClosed(), "a replaced runtime keeps serving logins that started with it");
        controller.stop(5000);
        assertTrue(replaced.isClosed(), "stopping must not leave it to a background thread that may never finish");
    }

    @Test void startupLogNamesDryRunAndMissingProxycheckKey() throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        Log log = new Log() {
            @Override public void info(String message) { lines.add(message); }
            @Override public void warn(String message, Throwable cause) { lines.add(message); }
            @Override public void debug(String message) { }
        };
        OriginGateRuntime.installDefaults(directory);
        Path config = directory.resolve("config.yml");
        Files.writeString(config, Files.readString(config).replace("dry-run: false", "dry-run: true"));
        try (RuntimeController controller = new RuntimeController(directory, log)) {
            controller.reload();
            assertTrue(lines.contains("OriginGate is running with sqlite storage in DRY-RUN mode (nobody is kicked)."), lines.toString());
            assertTrue(lines.stream().anyMatch(line -> line.startsWith("No proxycheck.io API keys are set")), lines.toString());
            Files.writeString(config, "config-version: 999\n");
            controller.reload();
            assertTrue(lines.stream().anyMatch(line -> line.startsWith("OriginGate reload failed: ")), lines.toString());
        }
        lines.clear();
        try (RuntimeController controller = new RuntimeController(directory, log)) {
            controller.reload();
            assertTrue(lines.stream().anyMatch(line -> line.startsWith("OriginGate could not start and is NOT checking connections")), lines.toString());
        }
    }
}
