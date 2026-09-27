package io.github.origingate.core;

import com.sun.net.httpserver.HttpServer;
import io.github.origingate.core.lookup.IpInfo;
import io.github.origingate.core.rules.LoginAttempt;
import io.github.origingate.core.storage.SqlIpStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Loads a real runtime (SQLite, HTTP provider) against a local stub server and drives the commands. */
class RuntimeCommandsTest {
    @TempDir Path directory;
    private HttpServer server;
    private final AtomicInteger apiCalls = new AtomicInteger();
    private OriginGateRuntime runtime;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/", exchange -> {
            apiCalls.incrementAndGet();
            byte[] body;
            try (InputStream input = getClass().getResourceAsStream("/proxycheck/business-8.8.8.8.json")) {
                body = input.readAllBytes();
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
    }

    @AfterEach void stop() {
        if (runtime != null) runtime.close();
        server.stop(0);
    }

    private OriginGateRuntime load(Object... changes) throws Exception {
        Object[] all = new Object[changes.length + 2];
        all[0] = "lookup.proxycheck.base-url";
        all[1] = "http://127.0.0.1:" + server.getAddress().getPort() + "/v3/";
        System.arraycopy(changes, 0, all, 2, changes.length);
        TestSupport.writeConfig(directory, all);
        if (runtime != null) runtime.close();
        runtime = OriginGateRuntime.load(directory, Log.NONE);
        return runtime;
    }

    private final class Replies implements Commands.Sender {
        final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        final Set<String> permissions;

        Replies(String... permissions) { this.permissions = Set.of(permissions); }

        @Override public boolean hasPermission(String permission) { return permissions.contains(permission); }

        @Override public void reply(String line) { lines.add(line); }

        String waitFor(String text) throws InterruptedException {
            List<String> seen = new ArrayList<>();
            while (true) {
                String line = lines.poll(10, TimeUnit.SECONDS);
                assertNotNull(line, "no reply containing " + text + "; saw " + seen);
                seen.add(line);
                if (line.contains(text)) return line;
            }
        }
    }

    private Commands commands() {
        return new Commands(new Commands.Platform() {
            @Override public OriginGateRuntime runtime() { return runtime; }
            @Override public Optional<LoginAttempt> onlinePlayer(String name) {
                return name.equals("Alex") ? Optional.of(TestSupport.player("Alex", "8.8.8.8", "origingate.bypass.country")) : Optional.empty();
            }
            @Override public List<String> onlinePlayerNames() { return List.of("Alex"); }
            @Override public String reload() { return "reloaded"; }
        });
    }

    @Test void installDefaultsKeepsExistingFiles() throws Exception {
        Path fresh = directory.resolve("fresh");
        OriginGateRuntime.installDefaults(fresh);
        assertTrue(Files.exists(fresh.resolve("config.yml")));
        Files.writeString(fresh.resolve("config.yml"), "edited");
        OriginGateRuntime.installDefaults(fresh);
        assertEquals("edited", Files.readString(fresh.resolve("config.yml")));
    }

    @Test void checkShowsDataAndResult() throws Exception {
        load("rules.country.enabled", true, "rules.country.countries", List.of("CA"));
        Replies sender = new Replies(Commands.CHECK);
        commands().execute(new String[] {"check", "8.8.8.8"}, sender);
        sender.waitFor("Provider: Google LLC");
        String result = sender.waitFor("Result");
        assertTrue(result.contains("for a player without bypass permissions: kicked by the country rule"), result);
        // An online player with the country bypass permission.
        commands().execute(new String[] {"check", "Alex"}, sender);
        sender.waitFor("Source: memory");
        assertTrue(sender.waitFor("Result").contains("allowed through a bypass by the country rule"));
        commands().execute(new String[] {"check", "8.8.8.8", "refresh"}, sender);
        sender.waitFor("Source: provider");
        assertEquals(2, apiCalls.get());
    }

    @Test void checkRejectsBadTargetsAndSkipsPrivateAddresses() throws Exception {
        load();
        Replies sender = new Replies(Commands.CHECK);
        commands().execute(new String[] {"check", "nobody"}, sender);
        sender.waitFor("is not an online player or an IP address");
        commands().execute(new String[] {"check", "192.168.0.10"}, sender);
        sender.waitFor("is a private address");
        commands().execute(new String[] {"check"}, sender);
        sender.waitFor("Usage: /origingate check");
        assertEquals(0, apiCalls.get());
    }

    @Test void permissionsAreChecked() throws Exception {
        load();
        Replies nobody = new Replies();
        assertTrue(!Commands.canUse(nobody));
        commands().execute(new String[] {"reload"}, nobody);
        nobody.waitFor("You do not have permission");
        Replies reloader = new Replies(Commands.RELOAD);
        commands().execute(new String[] {"reload"}, reloader);
        reloader.waitFor("reloaded");
        assertEquals(List.of("reload"), commands().suggest(new String[] {""}, reloader));
        assertEquals(List.of("Alex"), commands().suggest(new String[] {"check", "a"}, new Replies(Commands.CHECK)));
    }

    @Test void cacheClearRemovesMemoryAndStorage() throws Exception {
        load();
        Replies sender = new Replies(Commands.CHECK, Commands.CACHE);
        commands().execute(new String[] {"check", "8.8.8.8"}, sender);
        sender.waitFor("Result");
        awaitStoredRows(1);
        commands().execute(new String[] {"cache", "clear", "8.8.8.8"}, sender);
        assertTrue(sender.waitFor("Cleared").contains("from memory and 1 storage rows"));
        commands().execute(new String[] {"check", "8.8.8.8"}, sender);
        sender.waitFor("Source: provider");
        awaitStoredRows(1);
        commands().execute(new String[] {"cache", "clear", "all"}, sender);
        sender.waitFor("Cleared 1 lookups from memory and 1 from storage");
        commands().execute(new String[] {"cache", "clear", "not-an-ip"}, sender);
        sender.waitFor("is not an IP address");
    }

    /** Saves happen after the lookup answers, so wait for the row before clearing. */
    private void awaitStoredRows(int expected) throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("data/origingate.db");
        for (int i = 0; i < 100; i++) {
            try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM origingate_ip_cache")) {
                if (rows.next() && rows.getInt(1) == expected) return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("storage never had " + expected + " rows");
    }

    @Test void retiredRuntimeStillAnswersDuringTheGracePeriod() throws Exception {
        OriginGateRuntime old = load();
        runtime = null;
        old.retire();
        assertEquals("Google LLC", old.gate().lookups().lookup("8.8.8.8", false).get(10, TimeUnit.SECONDS).info().provider());
        // Closing now waits for the save, so the temp folder can be deleted; the delayed close is harmless.
        old.close();
    }

    @Test void expiredDataIsDeletedUnlessKeptForever() throws Exception {
        load("storage.keep-days", 0, "log-file-keep-days", 0);
        Instant old = Instant.now().minus(Duration.ofDays(400));
        try (SqlIpStorage storage = SqlIpStorage.sqlite(directory.resolve("data/origingate.db"))) {
            storage.save(new IpInfo("192.0.2.1", "Example Net", "Example Org", null, "Example City", "Example Region",
                    "Canada", "CA", "AS64500", false, false, "Residential", old));
        }
        Path logs = Files.createDirectories(directory.resolve("logs"));
        Files.writeString(logs.resolve(LocalDate.now().minusDays(400) + ".log"), "old\n");

        assertEquals(new OriginGateRuntime.Cleanup(0, 0), runtime.deleteExpired());
        load();
        assertEquals(new OriginGateRuntime.Cleanup(1, 1), runtime.deleteExpired());
    }

    @Test void commandsExplainWhenNotRunning() throws Exception {
        Replies sender = new Replies(Commands.CHECK);
        commands().execute(new String[] {"check", "8.8.8.8"}, sender);
        sender.waitFor("OriginGate is not running");
    }
}
