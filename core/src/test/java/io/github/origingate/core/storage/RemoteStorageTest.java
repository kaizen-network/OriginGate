package io.github.origingate.core.storage;

import io.github.origingate.core.TestSupport;
import io.github.origingate.core.config.OriginGateConfig;
import io.github.origingate.core.lookup.IpInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in: {@code gradlew :core:remoteDatabaseTest} with OG_TEST_DB_ALLOW_WRITES=true and a dedicated
 * database whose name starts with origingate_test_. Also needs OG_TEST_DB_HOST, OG_TEST_DB_PORT,
 * OG_TEST_DB_USERNAME, OG_TEST_DB_PASSWORD, and optionally OG_TEST_DB_SSL_MODE (default disable).
 */
@Tag("remote-database")
class RemoteStorageTest {
    private OriginGateConfig.Mysql config;

    @BeforeEach void settings() throws SQLException {
        if (!"true".equals(System.getenv("OG_TEST_DB_ALLOW_WRITES"))) throw new IllegalStateException("OG_TEST_DB_ALLOW_WRITES must be true");
        String database = System.getenv("OG_TEST_DB_DATABASE");
        if (database == null || !database.startsWith("origingate_test_")) throw new IllegalStateException("Database must start with origingate_test_");
        config = new OriginGateConfig.Mysql(env("OG_TEST_DB_HOST", "127.0.0.1"), Integer.parseInt(env("OG_TEST_DB_PORT", "3306")),
                database, env("OG_TEST_DB_USERNAME", "root"), env("OG_TEST_DB_PASSWORD", ""), env("OG_TEST_DB_SSL_MODE", "disable"), 3000, 5000);
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + SqlIpStorage.TABLE);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private Connection open() throws SQLException {
        var properties = new java.util.Properties();
        properties.setProperty("user", config.username());
        properties.setProperty("password", config.password());
        properties.setProperty("sslMode", config.sslMode());
        return new org.mariadb.jdbc.Driver().connect("jdbc:mariadb://" + config.host() + ":" + config.port() + "/" + config.database(), properties);
    }

    @Test void createsTableSavesAndReadsWithQuotes() throws SQLException {
        SqlIpStorage storage = SqlIpStorage.mysql(config);
        IpInfo info = new IpInfo("192.0.2.1", "O'Brien Networks", "Org", "Some VPN", "Toronto", "Toronto", "Canada", "CA",
                "AS64500", true, false, "Hosting", TestSupport.NOW);
        storage.save(info);
        assertEquals(info, storage.find("192.0.2.1", Instant.EPOCH).orElseThrow());
        assertEquals(1, storage.deleteOlderThan(TestSupport.NOW.plusSeconds(1)));
        SqlIpStorage.mysql(config); // A second start keeps the existing table.
    }

    @Test void twoProxiesWritingTheSameIpDoNotFail() throws Exception {
        SqlIpStorage first = SqlIpStorage.mysql(config);
        SqlIpStorage second = SqlIpStorage.mysql(config);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> writes = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                SqlIpStorage target = i % 2 == 0 ? first : second;
                Instant at = TestSupport.NOW.plusSeconds(i);
                writes.add(pool.submit(() -> {
                    target.save(new IpInfo("192.0.2.2", "P", null, null, null, null, "Canada", "CA", null, false, false, null, at));
                    return null;
                }));
            }
            for (Future<?> write : writes) write.get();
        } finally {
            pool.shutdown();
        }
        assertTrue(first.find("192.0.2.2", Instant.EPOCH).isPresent());
        try (Connection connection = open(); Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + SqlIpStorage.TABLE)) {
            rows.next();
            assertEquals(1, rows.getInt(1));
        }
    }
}
