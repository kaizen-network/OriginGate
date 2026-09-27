package io.github.origingate.core;

import io.github.origingate.core.config.ConfigException;
import io.github.origingate.core.config.ConfigLoader;
import io.github.origingate.core.config.Messages;
import io.github.origingate.core.config.OriginGateConfig;
import io.github.origingate.core.lookup.LookupService;
import io.github.origingate.core.lookup.MemoryCache;
import io.github.origingate.core.lookup.ProviderChain;
import io.github.origingate.core.lookup.ProxyCheckProvider;
import io.github.origingate.core.report.DecisionFile;
import io.github.origingate.core.rules.Gate;
import io.github.origingate.core.storage.IpStorage;
import io.github.origingate.core.storage.SqlIpStorage;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Everything built from one config: storage, lookups, rules, and the decision log. Replaced on reload. */
public final class OriginGateRuntime implements AutoCloseable {
    public static final String VERSION = BuildInfo.VERSION;
    private static final int WORKERS = 4;
    private static final int QUEUE = 256;

    private final OriginGateConfig config;
    private final Messages messages;
    private final IpStorage storage;
    private final HttpClient http;
    private final ThreadPoolExecutor workers;
    private final Gate gate;
    private final DecisionFile decisionFile;
    private final Clock clock;

    private OriginGateRuntime(OriginGateConfig config, Messages messages, IpStorage storage, HttpClient http,
                              ThreadPoolExecutor workers, Gate gate, DecisionFile decisionFile, Clock clock) {
        this.config = config;
        this.messages = messages;
        this.storage = storage;
        this.http = http;
        this.workers = workers;
        this.gate = gate;
        this.decisionFile = decisionFile;
        this.clock = clock;
    }

    /** Copies the default config.yml and messages.yml when they do not exist yet. */
    public static void installDefaults(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        for (String name : new String[] {"config.yml", "messages.yml"}) {
            Path target = dataDirectory.resolve(name);
            if (Files.exists(target)) continue;
            try (InputStream input = OriginGateRuntime.class.getResourceAsStream("/" + name)) {
                if (input == null) throw new IOException("Missing bundled " + name);
                Files.copy(input, target);
            }
        }
    }

    public static OriginGateRuntime load(Path dataDirectory, Log baseLog) throws ConfigException, SQLException {
        OriginGateConfig config = ConfigLoader.load(dataDirectory);
        Messages messages = Messages.load(dataDirectory);
        Log log = Log.withDebug(baseLog, config.consoleLog() == OriginGateConfig.ConsoleLog.DEBUG);
        Clock clock = Clock.systemDefaultZone();
        OriginGateConfig.Storage settings = config.storage();
        SqlIpStorage storage = settings.type().equals("mysql")
                ? SqlIpStorage.mysql(settings.mysql())
                : SqlIpStorage.sqlite(settings.sqliteFile());
        if (!storage.ready()) {
            log.warn("The database cannot be reached. OriginGate keeps checking connections without saved lookups "
                    + "and sets up the table once the database answers.", null);
        }
        Duration requestTimeout = Duration.ofMillis(config.lookup().proxycheck().requestTimeoutMillis());
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        ThreadPoolExecutor workers = new ThreadPoolExecutor(WORKERS, WORKERS, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE), threads(), new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
        Duration maxAge = Duration.ofDays(settings.maxAgeDays());
        ProxyCheckProvider provider = new ProxyCheckProvider(http, config.lookup().proxycheck().baseUrl(), config.lookup().proxycheck().apiKeys(),
                requestTimeout, clock, log, "OriginGate/" + VERSION);
        ProviderChain chain = new ProviderChain(List.of(provider), List.of(provider),
                Duration.ofMillis(config.lookup().waitMillis()), clock, log);
        LookupService lookups = new LookupService(chain, storage, new MemoryCache(settings.memoryCacheSize(), maxAge, clock),
                maxAge, workers, clock, log);
        DecisionFile decisionFile = config.logFile() ? new DecisionFile(dataDirectory.resolve("logs"), clock) : null;
        return new OriginGateRuntime(config, messages, storage, http, workers, new Gate(config, lookups, log),
                decisionFile, clock);
    }

    private static java.util.concurrent.ThreadFactory threads() {
        AtomicInteger count = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "OriginGate Worker #" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public record Cleanup(int rows, int files) { }

    /** Deletes stored lookups older than {@code keep-days} and log files older than {@code log-file-keep-days}. Blocks. */
    public Cleanup deleteExpired() throws SQLException, IOException {
        int keepDays = config.storage().keepDays();
        int rows = keepDays == 0 ? 0 : storage.deleteOlderThan(clock.instant().minus(Duration.ofDays(keepDays)));
        int logDays = config.logFileKeepDays();
        int files = decisionFile == null || logDays == 0 ? 0 : decisionFile.deleteOlderThan(logDays);
        return new Cleanup(rows, files);
    }

    /** Forgets one IP, or every IP with "all", in memory and in OriginGate's own table. */
    public CompletableFuture<String> clearCache(String target) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (target.equalsIgnoreCase("all")) {
                    int memory = gate.lookups().forgetAll();
                    int rows = storage.deleteAll();
                    return "Cleared " + memory + " lookups from memory and " + rows + " from storage.";
                }
                boolean memory = gate.lookups().forget(target);
                int rows = storage.delete(target);
                return "Cleared " + target + " from " + (memory ? "memory and " : "") + rows + " storage rows.";
            } catch (SQLException ex) {
                throw new java.util.concurrent.CompletionException(ex);
            }
        }, workers);
    }

    public OriginGateConfig config() { return config; }

    public Messages messages() { return messages; }

    public Gate gate() { return gate; }

    /** Null when {@code log-file: false}. */
    public DecisionFile decisionFile() { return decisionFile; }

    /**
     * For reload: keeps this runtime working long enough for logins that already started with it
     * (up to {@code wait-millis} plus two requests), then closes it in the background.
     */
    public void retire() {
        long graceMillis = config.lookup().waitMillis() + 2L * config.lookup().longestRequestMillis();
        Thread closer = new Thread(() -> {
            try {
                Thread.sleep(graceMillis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            close();
        }, "OriginGate Retire");
        closer.setDaemon(true);
        closer.start();
    }

    /** Stops taking lookups, lets queued ones finish, then closes the HTTP client. */
    @Override public void close() {
        workers.shutdown();
        try {
            // The HTTP client refuses new requests once shut down, so wait for queued lookups first.
            workers.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        http.shutdown();
        storage.close();
    }
}
