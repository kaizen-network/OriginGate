package io.github.origingate.core.storage;

import io.github.origingate.core.Log;
import io.github.origingate.core.OriginGateRuntime;
import io.github.origingate.core.RuntimeController;
import io.github.origingate.core.TestSupport;
import io.github.origingate.core.rules.Decision;
import io.github.origingate.core.rules.Rule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs in its own JVM ({@code gradlew :core:sqliteMissingTest}, part of {@code check}) with an {@code os.arch} that no
 * SQLite native library exists for, so the real native lookup fails as it would on an unsupported system.
 */
@Tag("sqlite-missing")
class SqliteMissingTest {
    @TempDir Path directory;

    @Test void checksKeepRunningWithMemoryOnlyStorage() throws Exception {
        assertEquals("unsupported-probe", System.getProperty("os.arch"), "run through the sqliteMissingTest task");
        SqliteUnavailableException missing = assertThrows(SqliteUnavailableException.class,
                () -> SqlIpStorage.sqlite(directory.resolve("probe.db")));
        assertTrue(missing.getMessage().contains("Set storage.type to mysql"), missing.getMessage());

        TestSupport.writeConfig(directory, "rules.deny-addresses.enabled", true,
                "rules.deny-addresses.list", List.of("192.0.2.0/24"));
        List<String> lines = new CopyOnWriteArrayList<>();
        Log log = new Log() {
            @Override public void info(String message) { lines.add(message); }
            @Override public void warn(String message, Throwable cause) { lines.add(message); }
            @Override public void debug(String message) { }
        };
        try (RuntimeController controller = new RuntimeController(directory, log)) {
            controller.reload();
            OriginGateRuntime runtime = controller.runtime();
            assertNotNull(runtime, "a missing SQLite library must not stop the checks: " + lines);
            assertEquals("memory-only", runtime.storageName());
            assertTrue(lines.stream().anyMatch(line -> line.startsWith("SQLite cannot run on this system")
                    && line.contains("keeps checking connections")), lines.toString());
            assertTrue(lines.contains("OriginGate is running with memory-only storage."), lines.toString());
            assertTrue(runtime.status().stream().anyMatch(line -> line.contains("Storage: memory-only (SQLite cannot run")));
            Decision denied = runtime.gate().check(TestSupport.player("Alex", "192.0.2.7")).get(10, TimeUnit.SECONDS);
            assertEquals(Rule.DENY_ADDRESSES, denied.rule());
            assertEquals(Decision.Outcome.DENY, denied.outcome());
            assertTrue(runtime.clearCache("all").get(10, TimeUnit.SECONDS).contains("and 0 from storage"));
            assertTrue(controller.reload().startsWith("OriginGate reloaded."), "a reload fails the same way and keeps going");
            assertEquals("memory-only", controller.runtime().storageName());
        }
    }
}
