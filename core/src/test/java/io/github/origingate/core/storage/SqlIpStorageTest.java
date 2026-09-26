package io.github.origingate.core.storage;

import io.github.origingate.core.TestSupport;
import io.github.origingate.core.lookup.IpInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlIpStorageTest {
    @TempDir Path directory;

    private static IpInfo row(String ip, Instant checkedAt) {
        return new IpInfo(ip, "O'Brien Networks", "Example \"Org\"", "Example VPN", "Toronto", "Toronto", "Canada",
                "CA", "AS64500", true, false, "Hosting", checkedAt);
    }

    private static IpInfo read(SqlIpStorage storage, String ip, Instant notBefore) throws SQLException {
        return storage.find(ip, notBefore).orElseThrow();
    }

    @Test void savesAndReadsEveryField() throws SQLException {
        SqlIpStorage storage = SqlIpStorage.sqlite(directory.resolve("data/test.db"));
        assertTrue(storage.ready());
        storage.save(row("192.0.2.1", TestSupport.NOW));
        IpInfo read = read(storage, "192.0.2.1", TestSupport.NOW.minus(Duration.ofDays(1)));
        assertEquals(row("192.0.2.1", TestSupport.NOW), read);
        assertEquals("O'Brien Networks", read.provider());
    }

    @Test void nullsStayNull() throws SQLException {
        SqlIpStorage storage = SqlIpStorage.sqlite(directory.resolve("test.db"));
        storage.save(new IpInfo("192.0.2.2", null, null, null, null, null, "Canada", null, null, false, true, null, TestSupport.NOW));
        IpInfo read = read(storage, "192.0.2.2", Instant.EPOCH);
        assertNull(read.provider());
        assertNull(read.countryCode());
        assertTrue(read.proxy());
    }

    @Test void oldRowsAreIgnoredAndDeleted() throws SQLException {
        SqlIpStorage storage = SqlIpStorage.sqlite(directory.resolve("test.db"));
        storage.save(row("192.0.2.1", TestSupport.NOW.minus(Duration.ofDays(40))));
        storage.save(row("192.0.2.2", TestSupport.NOW));
        Instant cutoff = TestSupport.NOW.minus(Duration.ofDays(30));
        assertTrue(storage.find("192.0.2.1", cutoff).isEmpty());
        assertEquals(1, storage.deleteOlderThan(cutoff));
        assertTrue(storage.find("192.0.2.1", Instant.EPOCH).isEmpty());
        assertTrue(storage.find("192.0.2.2", cutoff).isPresent());
    }

    @Test void saveReplacesTheExistingRow() throws SQLException {
        SqlIpStorage storage = SqlIpStorage.sqlite(directory.resolve("test.db"));
        storage.save(row("192.0.2.1", TestSupport.NOW.minus(Duration.ofDays(1))));
        IpInfo newer = new IpInfo("192.0.2.1", "New", null, null, null, null, "Mexico", "MX", null, false, false, null, TestSupport.NOW);
        storage.save(newer);
        assertEquals(newer, read(storage, "192.0.2.1", Instant.EPOCH));
    }

    @Test void deleteOneAndAll() throws SQLException {
        SqlIpStorage storage = SqlIpStorage.sqlite(directory.resolve("test.db"));
        storage.save(row("192.0.2.1", TestSupport.NOW));
        storage.save(row("192.0.2.2", TestSupport.NOW));
        assertEquals(1, storage.delete("192.0.2.1"));
        assertEquals(0, storage.delete("192.0.2.1"));
        assertEquals(1, storage.deleteAll());
    }

    @Test void longValuesAreShortened() throws SQLException {
        SqlIpStorage storage = SqlIpStorage.sqlite(directory.resolve("test.db"));
        String type = "x".repeat(200);
        storage.save(new IpInfo("192.0.2.3", null, null, null, null, null, "Canada", "CA", null, false, false, type, TestSupport.NOW));
        assertEquals(64, read(storage, "192.0.2.3", Instant.EPOCH).type().length());
    }

    @Test void reachableDatabaseWithASetupProblemFailsAtStartup() {
        SQLException failure = assertThrows(SQLException.class, () -> SqlIpStorage.open(() -> {
            // A connection that works but refuses the table setup, like a user without CREATE permission.
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("readonly.db"));
            connection.close();
            return connection;
        }, false, true));
        assertTrue(failure.getMessage() != null);
    }

    @Test void unreachableDatabaseIsSetUpOnceItAnswers() throws SQLException {
        Path file = directory.resolve("later.db");
        AtomicBoolean up = new AtomicBoolean(false);
        SqlIpStorage storage = SqlIpStorage.open(() -> {
            if (!up.get()) throw new SQLException("connection refused (test)");
            return DriverManager.getConnection("jdbc:sqlite:" + file);
        }, false, true);
        assertFalse(storage.ready());
        assertThrows(SQLException.class, () -> storage.find("192.0.2.1", Instant.EPOCH));
        up.set(true);
        storage.save(row("192.0.2.1", TestSupport.NOW));
        assertTrue(storage.ready());
        assertTrue(storage.find("192.0.2.1", Instant.EPOCH).isPresent());
    }

    @Test void unreachableDatabaseFailsWhenNotAllowed() {
        assertThrows(SQLException.class, () -> SqlIpStorage.open(() -> {
            throw new SQLException("connection refused (test)");
        }, false, false));
    }
}
