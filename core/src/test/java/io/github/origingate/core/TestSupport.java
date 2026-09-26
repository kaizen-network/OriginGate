package io.github.origingate.core;

import io.github.origingate.core.config.ConfigException;
import io.github.origingate.core.config.ConfigLoader;
import io.github.origingate.core.config.OriginGateConfig;
import io.github.origingate.core.lookup.IpInfo;
import io.github.origingate.core.lookup.LookupException;
import io.github.origingate.core.lookup.LookupProvider;
import io.github.origingate.core.net.Addresses;
import io.github.origingate.core.rules.LoginAttempt;
import io.github.origingate.core.storage.IpStorage;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class TestSupport {
    public static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");

    private TestSupport() { }

    /**
     * Writes the bundled config.yml and messages.yml to {@code directory}, with settings changed by
     * dotted path, for example {@code "rules.country.enabled", true}.
     */
    @SuppressWarnings("unchecked")
    public static Path writeConfig(Path directory, Object... changes) throws IOException {
        Files.createDirectories(directory);
        Map<String, Object> root;
        try (InputStream input = TestSupport.class.getResourceAsStream("/config.yml")) {
            root = new Yaml().load(input);
        }
        for (int i = 0; i < changes.length; i += 2) {
            String[] path = ((String) changes[i]).split("\\.");
            Map<String, Object> section = root;
            for (int p = 0; p < path.length - 1; p++) {
                Object next = section.get(path[p]);
                if (!(next instanceof Map)) throw new IllegalArgumentException("No section " + changes[i]);
                section = (Map<String, Object>) next;
            }
            section.put(path[path.length - 1], changes[i + 1]);
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Files.writeString(directory.resolve("config.yml"), new Yaml(options).dump(root), StandardCharsets.UTF_8);
        try (InputStream input = TestSupport.class.getResourceAsStream("/messages.yml")) {
            Files.write(directory.resolve("messages.yml"), input.readAllBytes());
        }
        return directory;
    }

    public static OriginGateConfig config(Path directory, Object... changes) throws IOException, ConfigException {
        return ConfigLoader.load(writeConfig(directory, changes));
    }

    public static IpInfo info(String ip, String country, String code, boolean vpn, boolean proxy) {
        return new IpInfo(ip, "Example Net", "Example Org", null, "Example City", "Example Region", country, code,
                "AS64500", vpn, proxy, "Residential", NOW);
    }

    public static LoginAttempt player(String name, String ip, String... permissions) {
        Set<String> granted = Set.of(permissions);
        return new LoginAttempt(name, UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)),
                Addresses.parse(ip).orElseThrow(), granted::contains);
    }

    /** A clock that tests can move forward. */
    public static final class MutableClock extends Clock {
        private volatile Instant now;

        public MutableClock(Instant now) { this.now = now; }

        public void advance(Duration duration) { now = now.plus(duration); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return now; }
    }

    /** Returns canned results per IP, counts calls, and can hold calls until released. */
    public static final class FakeProvider implements LookupProvider {
        public final Map<String, Object> results = new ConcurrentHashMap<>();
        public final AtomicInteger calls = new AtomicInteger();
        public volatile CountDownLatch gate;

        public FakeProvider answer(IpInfo info) {
            results.put(info.ip(), info);
            return this;
        }

        public FakeProvider fail(String ip, LookupException failure) {
            results.put(ip, failure);
            return this;
        }

        @Override public IpInfo lookup(String ip) throws LookupException {
            calls.incrementAndGet();
            CountDownLatch waitFor = gate;
            if (waitFor != null) {
                try {
                    if (!waitFor.await(10, TimeUnit.SECONDS)) throw new LookupException("test gate never opened");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new LookupException("interrupted", ex);
                }
            }
            Object result = results.get(ip);
            if (result instanceof LookupException failure) throw failure;
            if (result == null) throw new LookupException("no canned answer for " + ip);
            return (IpInfo) result;
        }
    }

    /** In-memory storage. Set {@code broken} to make every call fail, or {@code saveGate} to hold saves. */
    public static final class FakeStorage implements IpStorage {
        public final Map<String, IpInfo> rows = new ConcurrentHashMap<>();
        public final AtomicInteger calls = new AtomicInteger();
        public volatile boolean broken;
        public volatile CountDownLatch saveGate;

        @Override public Optional<IpInfo> find(String ip, Instant notBefore) throws SQLException {
            check();
            return Optional.ofNullable(rows.get(ip)).filter(row -> !row.checkedAt().isBefore(notBefore));
        }

        @Override public void save(IpInfo info) throws SQLException {
            check();
            CountDownLatch waitFor = saveGate;
            if (waitFor != null) {
                try {
                    waitFor.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            rows.put(info.ip(), info);
        }

        @Override public int deleteOlderThan(Instant cutoff) throws SQLException {
            check();
            List<String> old = rows.values().stream().filter(row -> row.checkedAt().isBefore(cutoff)).map(IpInfo::ip).toList();
            old.forEach(rows::remove);
            return old.size();
        }

        @Override public int delete(String ip) throws SQLException {
            check();
            return rows.remove(ip) == null ? 0 : 1;
        }

        @Override public int deleteAll() throws SQLException {
            check();
            int size = rows.size();
            rows.clear();
            return size;
        }

        private void check() throws SQLException {
            calls.incrementAndGet();
            if (broken) throw new SQLException("storage is down (test)");
        }
    }
}
