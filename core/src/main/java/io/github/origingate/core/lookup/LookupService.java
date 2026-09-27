package io.github.origingate.core.lookup;

import io.github.origingate.core.Log;
import io.github.origingate.core.Text;
import io.github.origingate.core.storage.IpStorage;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Finds IP data in this order: memory, storage, providers. Only one lookup per IP
 * runs at a time; other callers share its result. Storage errors are logged and skipped, never fatal,
 * and after one, storage is left alone for {@link #STORAGE_PAUSE} so a dead database does not slow logins.
 */
public final class LookupService {
    public static final Duration STORAGE_PAUSE = Duration.ofSeconds(60);
    public static final Duration NO_COUNTRY_PAUSE = Duration.ofMinutes(5);
    private static final int MAX_NO_COUNTRY = 10_000;

    public enum Source { MEMORY, STORAGE, PROVIDER }

    /** {@code answeredBy} names the providers for a fresh lookup, and is null for memory and storage. */
    public record Result(IpInfo info, Source source, AnsweredBy answeredBy) {
        public Result(IpInfo info, Source source) { this(info, source, null); }
    }

    private final ProviderChain providers;
    private final IpStorage storage;
    private final MemoryCache cache;
    private final Duration maxAge;
    private final Executor executor;
    private final Clock clock;
    private final Log log;
    private final Map<String, CompletableFuture<Result>> inFlight = new ConcurrentHashMap<>();
    /** IPs whose last provider answer had no country, with the time they may be asked again. */
    private final Map<String, Instant> noCountry = new LinkedHashMap<>(16, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
            return size() > MAX_NO_COUNTRY;
        }
    };
    private volatile Instant storagePausedUntil = Instant.MIN;

    public LookupService(ProviderChain providers, IpStorage storage, MemoryCache cache, Duration maxAge,
                         Executor executor, Clock clock, Log log) {
        this.providers = providers;
        this.storage = storage;
        this.cache = cache;
        this.maxAge = maxAge;
        this.executor = executor;
        this.clock = clock;
        this.log = log;
    }

    /**
     * Looks up {@code ip}. With {@code refresh}, memory and storage are skipped and the provider is asked.
     * The returned future completes exceptionally with a {@link LookupException} on failure.
     */
    public CompletableFuture<Result> lookup(String ip, boolean refresh) {
        if (!refresh) {
            Optional<IpInfo> cached = cache.get(ip);
            if (cached.isPresent()) return CompletableFuture.completedFuture(new Result(cached.get(), Source.MEMORY));
        }
        // A refresh never joins a normal lookup, which may answer from storage.
        String key = refresh ? "refresh " + ip : ip;
        CompletableFuture<Result> created = new CompletableFuture<>();
        CompletableFuture<Result> existing = inFlight.putIfAbsent(key, created);
        if (existing != null) {
            log.debug("Lookup for " + ip + " is already running, waiting for it");
            return existing.copy();
        }
        try {
            executor.execute(() -> {
                Result result = null;
                Throwable failure = null;
                try {
                    result = load(ip, refresh);
                } catch (Throwable ex) {
                    failure = ex;
                }
                // Removed before completing, so a lookup that starts after this one finished never joins it.
                inFlight.remove(key, created);
                if (failure == null) created.complete(result);
                else created.completeExceptionally(failure);
                // Saved after the waiting login already has its answer.
                if (result != null && result.source() == Source.PROVIDER) save(result.info());
            });
        } catch (RejectedExecutionException ex) {
            inFlight.remove(key, created);
            created.completeExceptionally(new LookupException("Too many lookups are waiting", ex));
        }
        return created.copy();
    }

    private Result load(String ip, boolean refresh) throws LookupException {
        Instant now = clock.instant();
        if (!refresh) {
            Optional<IpInfo> stored = read(ip, now.minus(maxAge));
            if (stored.isPresent()) return remember(stored.get(), Source.STORAGE, null);
            Instant retryAt;
            synchronized (noCountry) {
                retryAt = noCountry.get(ip);
            }
            if (retryAt != null && now.isBefore(retryAt)) {
                throw new LookupException("No provider had a country for " + ip + " a moment ago; not asking again yet");
            }
        }
        log.debug("Asking the lookup providers about " + ip);
        ProviderChain.Answer answer;
        try {
            answer = providers.lookup(ip);
        } catch (ProviderChain.NoCountryException ex) {
            // Country rules cannot work without a country code, so this counts as a failed lookup and is not saved.
            synchronized (noCountry) {
                noCountry.put(ip, now.plus(NO_COUNTRY_PAUSE));
            }
            throw ex;
        }
        synchronized (noCountry) {
            noCountry.remove(ip);
        }
        return remember(answer.info(), Source.PROVIDER, answer.answeredBy());
    }

    private Optional<IpInfo> read(String ip, Instant notBefore) {
        if (storagePaused()) return Optional.empty();
        try {
            return storage.find(ip, notBefore);
        } catch (SQLException | RuntimeException ex) {
            pauseStorage("read", ip, ex);
            return Optional.empty();
        }
    }

    private void save(IpInfo info) {
        if (storagePaused()) return;
        try {
            storage.save(info);
        } catch (SQLException | RuntimeException ex) {
            pauseStorage("save", info.ip(), ex);
        }
    }

    private boolean storagePaused() {
        return clock.instant().isBefore(storagePausedUntil);
    }

    private void pauseStorage(String action, String ip, Exception ex) {
        storagePausedUntil = clock.instant().plus(STORAGE_PAUSE);
        log.warn("Could not " + action + " storage for " + ip + ", skipping storage for "
                + STORAGE_PAUSE.toSeconds() + " seconds: " + Text.message(ex), null);
    }

    private Result remember(IpInfo info, Source source, AnsweredBy answeredBy) {
        cache.put(info);
        log.debug("Found " + info.ip() + " in " + source.name().toLowerCase(java.util.Locale.ROOT));
        return new Result(info, source, answeredBy);
    }

    /** Forgets one IP in memory. Returns true when it was cached. */
    public boolean forget(String ip) {
        synchronized (noCountry) {
            noCountry.remove(ip);
        }
        return cache.remove(ip);
    }

    /** Forgets every IP in memory. Returns how many were cached. */
    public int forgetAll() {
        synchronized (noCountry) {
            noCountry.clear();
        }
        return cache.clear();
    }

    public MemoryCache cache() {
        return cache;
    }
}
