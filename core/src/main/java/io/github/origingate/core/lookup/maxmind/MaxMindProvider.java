package io.github.origingate.core.lookup.maxmind;

import com.maxmind.db.Reader;
import io.github.origingate.core.Log;
import io.github.origingate.core.Text;
import io.github.origingate.core.lookup.IpInfo;
import io.github.origingate.core.lookup.LookupException;
import io.github.origingate.core.lookup.LookupProvider;
import io.github.origingate.core.net.Addresses;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads a MaxMind GeoLite2 Country or City file, for the country job only. The file is read into memory,
 * so it can be replaced on disk while in use (a memory-mapped file stays locked on Windows).
 */
public final class MaxMindProvider implements LookupProvider, AutoCloseable {
    /** MaxMind's license asks for updates within 30 days of a new release. */
    public static final Duration STALE_AFTER = Duration.ofDays(30);
    private final Path file;
    private final Clock clock;
    private final Log log;
    private final AtomicReference<Reader> reader = new AtomicReference<>();

    public MaxMindProvider(Path file, Clock clock, Log log) {
        this.file = file;
        this.clock = clock;
        this.log = log;
    }

    @Override public String name() { return "maxmind"; }

    /** Opens the file, or replaces the one in use. Returns false and keeps the current one when it cannot be read. */
    public boolean reload() {
        if (!Files.isRegularFile(file)) {
            log.warn("The MaxMind file " + file + " does not exist yet. MaxMind is skipped until it does.", null);
            return false;
        }
        try {
            Reader opened = new Reader(file.toFile(), Reader.FileMode.MEMORY);
            close(reader.getAndSet(opened));
            log.info("Loaded MaxMind " + opened.getMetadata().databaseType() + " built on "
                    + LocalDate.ofInstant(opened.getMetadata().buildTime(), ZoneOffset.UTC));
            return true;
        } catch (IOException | RuntimeException ex) {
            log.warn("Cannot read the MaxMind file " + file + ": " + Text.message(ex), null);
            return false;
        }
    }

    /** A warning when the file in use is older than {@link #STALE_AFTER}, for owners who update it themselves. */
    public Optional<String> staleWarning() {
        Reader current = reader.get();
        if (current == null) return Optional.empty();
        Instant built = current.getMetadata().buildTime();
        if (!built.isBefore(clock.instant().minus(STALE_AFTER))) return Optional.empty();
        return Optional.of("The MaxMind file " + file.getFileName() + " was built on " + LocalDate.ofInstant(built, ZoneOffset.UTC)
                + ". MaxMind's license asks for updates within 30 days of a new release. Set lookup.maxmind.account-id "
                + "and license-key to update it automatically.");
    }

    @Override public IpInfo lookup(String ip, Instant deadline) throws LookupException {
        Reader current = reader.get();
        if (current == null) throw new LookupException("the MaxMind file is not loaded");
        InetAddress address = Addresses.parse(ip).orElseThrow(() -> new LookupException("not an IP address: " + ip));
        Map<?, ?> record;
        try {
            record = current.get(address, Map.class);
        } catch (IOException | RuntimeException ex) {
            throw new LookupException("MaxMind lookup failed: " + Text.message(ex), ex);
        }
        if (record == null) throw new LookupException("the MaxMind file has no entry for " + ip);
        Map<?, ?> country = map(record, "country");
        if (country == null) country = map(record, "registered_country");
        return new IpInfo(ip, null, null, null, englishName(map(record, "city")), englishName(firstSubdivision(record)),
                englishName(country), text(country, "iso_code"), null, false, false, null, clock.instant());
    }

    private static Map<?, ?> firstSubdivision(Map<?, ?> record) {
        if (record.get("subdivisions") instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            return first;
        }
        return null;
    }

    private static Map<?, ?> map(Map<?, ?> parent, String key) {
        return parent != null && parent.get(key) instanceof Map<?, ?> value ? value : null;
    }

    private static String text(Map<?, ?> parent, String key) {
        return parent != null && parent.get(key) instanceof String value ? value : null;
    }

    private static String englishName(Map<?, ?> place) {
        return text(map(place, "names"), "en");
    }

    @Override public void close() {
        close(reader.getAndSet(null));
    }

    private static void close(Reader old) {
        if (old == null) return;
        try {
            old.close();
        } catch (IOException ignored) {
            // Nothing is left to release for an in-memory file.
        }
    }
}
