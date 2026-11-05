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
    private static Log recording(List<String> lines) {
        return new Log() {
            @Override public void info(String message) { lines.add(message); }
            @Override public void warn(String message, Throwable cause) { lines.add(message); }
            @Override public void debug(String message) { }
        };
    }

    @Test void offlineModeWarningFollowsThePlatformModeOnStartAndReload() throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        OfflineModeWarning.Mode[] mode = {OfflineModeWarning.Mode.OFFLINE};
        TestSupport.writeConfig(directory);
        try (RuntimeController controller = new RuntimeController(directory, recording(lines), () -> mode[0],
                RuntimeController.StaffAlerts.NONE)) {
            controller.reload();
            assertTrue(lines.stream().noneMatch(line -> line.contains("offline mode")), "the default config has no owner-set bypass: " + lines);
            TestSupport.writeConfig(directory, "bypass.players", List.of("ExampleStaff"));
            controller.reload();
            assertTrue(lines.stream().anyMatch(line -> line.startsWith("This server or proxy runs in offline mode")), lines.toString());
            lines.clear();
            mode[0] = OfflineModeWarning.Mode.MODERN_FORWARDING;
            controller.reload();
            assertTrue(lines.stream().noneMatch(line -> line.contains("offline mode")), lines.toString());
            TestSupport.writeConfig(directory);
            mode[0] = OfflineModeWarning.Mode.LEGACY_FORWARDING;
            controller.reload();
            assertTrue(lines.stream().anyMatch(line -> line.startsWith("BungeeCord IP forwarding")), lines.toString());
        }
    }

    @Test void reloadDuringAnOutageKeepsTheAlertState() throws Exception {
        java.util.concurrent.atomic.AtomicInteger status = new java.util.concurrent.atomic.AtomicInteger(429);
        List<String> sent = new CopyOnWriteArrayList<>();
        try (TestServer server = new TestServer(request -> status.get() == 200
                ? TestServer.Reply.text(200, readFixture())
                : TestServer.Reply.text(429, "{\"status\":\"denied\",\"message\":\"test limit\"}"))) {
            TestSupport.writeConfig(directory, "lookup.proxycheck.base-url", server.uri("/v3/").toString(),
                    "lookup.skip-private-addresses", false);
            try (RuntimeController controller = new RuntimeController(directory, Log.NONE,
                    () -> OfflineModeWarning.Mode.ONLINE, (active, template, message) -> sent.add(message))) {
                controller.reload();
                check(controller, "192.0.2.1");
                assertEquals(1, sent.size(), sent.toString());
                controller.reload();
                check(controller, "192.0.2.2");
                assertEquals(1, sent.size(), "an outage that goes on is not announced again after a reload: " + sent);
                // The new runtime paused the provider again; one more reload starts without that pause.
                controller.reload();
                status.set(200);
                check(controller, "8.8.8.8");
                assertEquals(2, sent.size(), sent.toString());
                assertEquals("proxycheck answers lookups again.", sent.get(1), "the recovery is still sent after the reload");
            }
        }
    }

    private static String readFixture() {
        try (java.io.InputStream input = RuntimeControllerTest.class.getResourceAsStream("/proxycheck/business-8.8.8.8.json")) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private static void check(RuntimeController controller, String ip) throws Exception {
        controller.runtime().gate().check(new LoginAttempt("Alex", null, InetAddress.getByName(ip), name -> false))
                .get(10, TimeUnit.SECONDS);
    }

    @Test void bypassesGetACautionWhereNamesAreNotChecked() throws Exception {
        TestSupport.writeConfig(directory, "bypass.permissions", List.of("example.staff"));
        LoginAttempt staff = new LoginAttempt("Alex", null, InetAddress.getByName("192.0.2.1"), "example.staff"::equals);
        try (RuntimeController offline = new RuntimeController(directory, Log.NONE, () -> OfflineModeWarning.Mode.OFFLINE,
                RuntimeController.StaffAlerts.NONE)) {
            offline.reload();
            Decision decision = offline.runtime().gate().beforeLookup(staff).orElseThrow();
            assertEquals(Decision.Outcome.ALLOW, decision.outcome());
            assertEquals(io.github.origingate.core.rules.Gate.UNVERIFIED_NAME, decision.caution());
            assertTrue(io.github.origingate.core.report.DecisionLine.format(staff, decision)
                    .endsWith("caution=\"anyone can claim this name in offline mode\""));
        }
        try (RuntimeController online = new RuntimeController(directory, Log.NONE, () -> OfflineModeWarning.Mode.ONLINE,
                RuntimeController.StaffAlerts.NONE)) {
            online.reload();
            assertNull(online.runtime().gate().beforeLookup(staff).orElseThrow().caution());
        }
    }

    @Test void pausedProviderAlertsStaffOnce() throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        List<String> sent = new CopyOnWriteArrayList<>();
        try (TestServer server = new TestServer(request -> TestServer.Reply.text(429, "{\"status\":\"denied\",\"message\":\"test limit\"}"))) {
            TestSupport.writeConfig(directory, "lookup.proxycheck.base-url", server.uri("/v3/").toString(),
                    "lookup.skip-private-addresses", false);
            try (RuntimeController controller = new RuntimeController(directory, recording(lines),
                    () -> OfflineModeWarning.Mode.ONLINE, (active, template, message) -> sent.add(template + " | " + message))) {
                controller.reload();
                OriginGateRuntime active = controller.runtime();
                for (String ip : List.of("192.0.2.1", "192.0.2.2", "192.0.2.3")) {
                    Decision decision = active.gate().check(new LoginAttempt("Alex", null, InetAddress.getByName(ip), name -> false))
                            .get(10, TimeUnit.SECONDS);
                    assertEquals(Rule.LOOKUP_FAILURE, decision.rule());
                    assertEquals(Decision.Outcome.ALLOW, decision.outcome(), "the default on-lookup-failure still lets players in");
                }
                assertEquals(1, sent.size(), sent.toString());
                assertTrue(sent.get(0).startsWith(active.messages().alertLookupProblem() + " | proxycheck refused the request"), sent.get(0));
                assertEquals(1, server.requests.size(), "the paused provider is not asked again");
                assertTrue(lines.stream().anyMatch(line -> line.startsWith("Alert: proxycheck refused the request")), lines.toString());
                assertTrue(active.status().stream().anyMatch(line -> line.contains("proxycheck: paused for")), active.status().toString());
            }
        }
    }
}
