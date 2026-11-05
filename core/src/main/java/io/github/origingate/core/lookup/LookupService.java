package io.github.origingate.core.lookup;

import io.github.origingate.core.Alerts;
import io.github.origingate.core.Log;
import io.github.origingate.core.Text;
import io.github.origingate.core.net.Addresses;
import io.github.origingate.core.storage.IpStorage;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Finds IP data in this order: memory, storage, providers. Only one lookup per address runs at a time; other callers
 * share its result. Answers are kept per address. An IPv6 answer that a rule would act on (see {@code sharedAcrossNetwork})
 * is also kept for its whole /64, so the next address in that /64 gets it from memory: home and mobile users can switch
 * addresses inside their /64, but hosts may split one /64 between customers, so a clean answer is never shared.
 * A queued lookup whose callers have all stopped waiting is dropped before any provider is asked. Storage errors are
 * logged and skipped, never fatal, and after one, storage is left alone for {@link #STORAGE_PAUSE} so a dead database
 * does not slow logins.
 */
public final class LookupService {
    public static final Duration STORAGE_PAUSE = Duration.ofSeconds(60);
    public static final Duration NO_COUNTRY_PAUSE = Duration.ofMinutes(5);
    private static final int MAX_NO_COUNTRY = 10_000;

    public enum Source { MEMORY, STORAGE, PROVIDER }

    /** {@code answeredBy} names the providers for a fresh lookup, and is null for memory and storage. */
    public static final class Result {
        private final IpInfo info;
        private final Source source;
        private final AnsweredBy answeredBy;

        public Result(IpInfo info, Source source, AnsweredBy answeredBy) {
            this.info = info;
            this.source = source;
            this.answeredBy = answeredBy;
        }

        public IpInfo info() { return info; }
        public Source source() { return source; }
        public AnsweredBy answeredBy() { return answeredBy; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Result)) return false;
            Result that = (Result) other;
            return java.util.Objects.equals(info, that.info)
                && java.util.Objects.equals(source, that.source)
                && java.util.Objects.equals(answeredBy, that.answeredBy);
        }
        @Override public int hashCode() { return java.util.Objects.hash(info, source, answeredBy); }
        @Override public String toString() { return "Result[" + "info=" + info + ", " + "source=" + source + ", " + "answeredBy=" + answeredBy + "]"; }

        public Result(IpInfo info, Source source) { this(info, source, null); }
    }

    /** A lookup that was skipped on purpose and not counted as failed: dropped after its callers stopped waiting. */
    public static class SkippedException extends LookupException {
        SkippedException(String message) { super(message); }
    }

    /** A login lookup over {@code max-lookups-per-minute}. It follows {@code on-budget-exceeded}. */
    public static final class BudgetExceededException extends SkippedException {
        BudgetExceededException(String message) { super(message); }
    }

    /** A queued or running lookup, shared by every caller for the same key. */
    private static final class Flight {
        final CompletableFuture<Result> future = new CompletableFuture<>();
        /** The latest time a caller still waits. Guarded by this. */
        private Instant deadline;
        /** True once a caller waits without a limit. Guarded by this. */
        private boolean unlimited;
        /** True once the flight was dropped; nobody may join it then. Guarded by this. */
        private boolean dropped;

        Flight(Instant deadline) { join(deadline); }

        /** Adds a caller that waits until {@code until} (null: without limit). False when the flight was dropped. */
        synchronized boolean join(Instant until) {
            if (dropped) return false;
            if (until == null) unlimited = true;
            else if (deadline == null || until.isAfter(deadline)) deadline = until;
            return true;
        }

        /** Marks the flight dropped when every caller has stopped waiting by {@code now}. */
        synchronized boolean dropIfExpired(Instant now) {
            if (unlimited || now.isBefore(deadline)) return false;
            dropped = true;
            return true;
        }
    }

    private final ProviderChain providers;
    private final IpStorage storage;
    private final MemoryCache cache;
    private final Duration maxAge;
    private final Executor executor;
    private final Clock clock;
    private final Log log;
    private final LookupBudget budget;
    private final LookupStats stats;
    private final Alerts alerts;
    private final Predicate<IpInfo> sharedAcrossNetwork;
    private final AtomicBoolean budgetUsedUp = new AtomicBoolean();
    private final AtomicLong skippedByBudget = new AtomicLong();
    private final Map<String, Flight> inFlight = new ConcurrentHashMap<>();
    /**
     * Addresses whose last provider answer had no country, with the time they may be asked again. Kept per address:
     * a paused lookup fails, and with on-lookup-failure: allow a pause for a whole /64 would let its neighbours in unchecked.
     */
    private final Map<String, Instant> noCountry = new LinkedHashMap<String, Instant>(16, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
            return size() > MAX_NO_COUNTRY;
        }
    };
    private volatile Instant storagePausedUntil = Instant.MIN;

    public LookupService(ProviderChain providers, IpStorage storage, MemoryCache cache, Duration maxAge,
                         Executor executor, Clock clock, Log log) {
        this(providers, storage, cache, maxAge, executor, clock, log, 0, new LookupStats(clock, ZoneId.systemDefault()),
                Alerts.NONE, info -> false);
    }

    /**
     * {@code maxLookupsPerMinute} limits provider lookups; 0 means no limit. {@code sharedAcrossNetwork} tells which
     * IPv6 answers are also kept for their whole /64: those a rule would act on.
     */
    public LookupService(ProviderChain providers, IpStorage storage, MemoryCache cache, Duration maxAge,
                         Executor executor, Clock clock, Log log, int maxLookupsPerMinute, LookupStats stats,
                         Alerts alerts, Predicate<IpInfo> sharedAcrossNetwork) {
        this.providers = providers;
        this.storage = storage;
        this.cache = cache;
        this.maxAge = maxAge;
        this.executor = executor;
        this.clock = clock;
        this.log = log;
        this.budget = new LookupBudget(maxLookupsPerMinute);
        this.stats = stats;
        this.alerts = alerts;
        this.sharedAcrossNetwork = sharedAcrossNetwork;
    }

    /** Looks up {@code ip} for a caller that waits without a time limit, such as a command. */
    public CompletableFuture<Result> lookup(String ip, boolean refresh) {
        return lookup(ip, refresh, null);
    }

    /**
     * Looks up {@code ip}. With {@code refresh}, memory and storage are skipped and the provider is asked.
     * {@code wait} is how long the caller waits for the answer, or null for no limit. The returned future completes
     * exceptionally with a {@link LookupException} on failure.
     */
    public CompletableFuture<Result> lookup(String ip, boolean refresh, Duration wait) {
        if (!refresh) {
            Optional<IpInfo> cached = remembered(ip);
            if (cached.isPresent()) {
                stats.answered(Source.MEMORY);
                return CompletableFuture.completedFuture(new Result(cached.get().withIp(ip), Source.MEMORY));
            }
        }
        Instant deadline = wait == null ? null : clock.instant().plus(wait);
        // A refresh never joins a normal lookup, which may answer from storage.
        String flightKey = refresh ? "refresh " + ip : ip;
        while (true) {
            Flight existing = inFlight.get(flightKey);
            if (existing != null) {
                if (existing.join(deadline)) {
                    log.debug("Lookup for " + ip + " is already running, waiting for it");
                    return io.github.origingate.core.util.Futures.copy(existing.future);
                }
                inFlight.remove(flightKey, existing);
                continue;
            }
            Flight created = new Flight(deadline);
            if (inFlight.putIfAbsent(flightKey, created) != null) continue;
            start(created, flightKey, ip, refresh);
            // A copy, so a caller's own timeout never cancels the shared lookup.
            return io.github.origingate.core.util.Futures.copy(created.future);
        }
    }

    /** The answer for this address, or else one a rule acts on for its IPv6 /64. */
    private Optional<IpInfo> remembered(String ip) {
        Optional<IpInfo> exact = cache.get(ip);
        if (exact.isPresent()) return exact;
        String network = Addresses.sharedNetwork(ip);
        return network == null ? Optional.empty() : cache.get(network);
    }

    private void start(Flight flight, String flightKey, String ip, boolean refresh) {
        try {
            executor.execute(() -> run(flight, flightKey, ip, refresh));
        } catch (RejectedExecutionException ex) {
            inFlight.remove(flightKey, flight);
            stats.countFailed();
            flight.future.completeExceptionally(new LookupException("Too many lookups are waiting", ex));
        }
    }

    private void run(Flight flight, String flightKey, String ip, boolean refresh) {
        Result result = null;
        Throwable failure = null;
        if (flight.dropIfExpired(clock.instant())) {
            stats.countDropped();
            log.debug("Dropped the lookup for " + ip + ": every login waiting for it stopped waiting before a worker was free");
            failure = new SkippedException("dropped: no login was waiting for it any more");
        } else {
            try {
                result = load(ip, refresh);
            } catch (Throwable ex) {
                failure = ex;
                if (!(ex instanceof SkippedException)) stats.countFailed();
            }
        }
        // Removed before completing, so a lookup that starts after this one finished never joins it.
        inFlight.remove(flightKey, flight);
        if (failure == null) {
            stats.answered(result.source());
            flight.future.complete(result);
        } else {
            flight.future.completeExceptionally(failure);
        }
        // Saved after the waiting login already has its answer. Without a VPN check the flags are not real, so
        // the result stays in memory only: a later config with VPN checks must not reuse it from storage.
        if (result != null && result.source() == Source.PROVIDER && providers.checksVpn()) save(result.info());
    }

    private Result load(String ip, boolean refresh) throws LookupException {
        Instant now = clock.instant();
        if (!refresh) {
            // A neighbour's answer that a rule acts on may have arrived while this lookup was queued.
            Optional<IpInfo> cached = remembered(ip);
            if (cached.isPresent()) return new Result(cached.get().withIp(ip), Source.MEMORY);
            Optional<IpInfo> stored = read(ip, now.minus(maxAge));
            if (stored.isPresent()) return remember(stored.get(), Source.STORAGE, null);
            Instant retryAt;
            synchronized (noCountry) {
                retryAt = noCountry.get(ip);
            }
            if (retryAt != null && now.isBefore(retryAt)) {
                throw new LookupException("No provider had a country for " + ip + " a moment ago; not asking again yet");
            }
            takeBudget(now);
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

    /** A refresh from a command is not limited; logins over the budget fail and follow on-budget-exceeded. */
    private void takeBudget(Instant now) throws SkippedException {
        if (budget.take(now)) {
            if (budgetUsedUp.compareAndSet(true, false)) {
                alerts.recovered("The lookup budget has room again. " + skippedByBudget.getAndSet(0)
                        + " new addresses were not checked while it was used up.");
            }
            return;
        }
        stats.countOverBudget();
        skippedByBudget.incrementAndGet();
        if (budgetUsedUp.compareAndSet(false, true)) {
            alerts.problem("The lookup budget of " + budget.perMinute() + " lookups per minute is used up. New addresses "
                    + "follow on-lookup-failure until it has room again.");
        }
        throw new BudgetExceededException("the lookup budget of " + budget.perMinute() + " per minute is used up");
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
                + STORAGE_PAUSE.getSeconds() + " seconds: " + Text.message(ex), null);
    }

    private Result remember(IpInfo info, Source source, AnsweredBy answeredBy) {
        cache.put(info.ip(), info);
        String network = Addresses.sharedNetwork(info.ip());
        if (network != null && sharedAcrossNetwork.test(info)) cache.put(network, info);
        log.debug("Found " + info.ip() + " in " + source.name().toLowerCase(java.util.Locale.ROOT));
        return new Result(info, source, answeredBy);
    }

    /** Forgets one IP in memory, and for IPv6 any answer kept for its /64. Returns true when something was cached. */
    public boolean forget(String ip) {
        synchronized (noCountry) {
            noCountry.remove(ip);
        }
        String network = Addresses.sharedNetwork(ip);
        boolean exact = cache.remove(ip);
        return (network != null && cache.remove(network)) || exact;
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

    /** The limit per minute, or 0 when there is none. */
    public int budgetPerMinute() {
        return budget.perMinute();
    }

    /** Provider lookups started in the last minute, counted only while a limit is set. */
    public int budgetUsed() {
        return budget.used(clock.instant());
    }

    public ProviderChain providers() {
        return providers;
    }
}
