package io.github.origingate.core.lookup.maxmind;

import com.maxmind.db.Reader;
import io.github.origingate.core.Log;
import io.github.origingate.core.Text;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * Downloads the MaxMind file with the owner's account ID and license key, and replaces it when MaxMind has a
 * newer release. Format reference: https://dev.maxmind.com/geoip/updating-databases/
 * A HEAD request reads Last-Modified (MaxMind says HEAD does not count toward the download limit). The file's
 * modified time is set to that date, so the next check needs no other state. MaxMind redirects to a storage host;
 * the redirect is followed by hand so the credentials are sent only to MaxMind.
 */
public final class MaxMindUpdater {
    public static final URI DOWNLOAD_URL = URI.create("https://download.maxmind.com/geoip/databases/");
    public static final Duration CHECK_EVERY = Duration.ofHours(24);
    static final long MAX_FILE_BYTES = 512L * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    public enum Outcome { DOWNLOADED, UP_TO_DATE }

    private final HttpClient http;
    private final URI url;
    private final String edition;
    private final String authorization;
    private final MaxMindProvider provider;
    private final Path file;
    private final Log log;
    private final String userAgent;

    public MaxMindUpdater(HttpClient http, URI baseUrl, String edition, int accountId, String licenseKey,
                          MaxMindProvider provider, Path file, Log log, String userAgent) {
        this.http = http;
        String base = baseUrl.toString();
        this.url = URI.create((base.endsWith("/") ? base : base + "/") + edition + "/download?suffix=tar.gz");
        this.edition = edition;
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString((accountId + ":" + licenseKey).getBytes(StandardCharsets.UTF_8));
        this.provider = provider;
        this.file = file;
        this.log = log;
        this.userAgent = userAgent;
    }

    /** For the scheduler: checks once and logs a failure instead of throwing. */
    public void runSafely() {
        try {
            update();
        } catch (IOException | RuntimeException ex) {
            log.warn("Could not update the MaxMind file, the current one stays in use: " + Text.message(ex), null);
        }
    }

    /** Checks once and downloads when MaxMind has a newer release than the file on disk. Blocks. */
    public Outcome update() throws IOException {
        Instant released = lastModified(send("HEAD", HttpResponse.BodyHandlers.discarding()));
        if (Files.isRegularFile(file) && !Files.getLastModifiedTime(file).toInstant().isBefore(released)) {
            return Outcome.UP_TO_DATE;
        }
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".download");
        try {
            HttpResponse<InputStream> download = send("GET", HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = download.body()) {
                TarGz.extract(body, ".mmdb", temp, MAX_FILE_BYTES);
            }
            check(temp);
            Files.setLastModifiedTime(temp, FileTime.from(released));
            move(temp, file);
        } finally {
            Files.deleteIfExists(temp);
        }
        provider.reload();
        log.info("Downloaded MaxMind " + edition + " released " + released);
        return Outcome.DOWNLOADED;
    }

    /** Sends to MaxMind with credentials, then follows one redirect without them. */
    private <T> HttpResponse<T> send(String method, HttpResponse.BodyHandler<T> handler) throws IOException {
        HttpResponse<T> response = request(url, method, handler, true);
        int code = response.statusCode();
        if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
            discard(response);
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IOException("MaxMind sent a redirect without a Location"));
            response = request(url.resolve(location), method, handler, false);
            code = response.statusCode();
        }
        if (code == 200) return response;
        discard(response);
        if (code == 401) throw new IOException("MaxMind refused the account ID or license key (HTTP 401)");
        if (code == 429) throw new IOException("MaxMind limits downloads right now (HTTP 429)");
        throw new IOException("MaxMind answered HTTP " + code);
    }

    private <T> HttpResponse<T> request(URI uri, String method, HttpResponse.BodyHandler<T> handler, boolean withCredentials)
            throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("User-Agent", userAgent)
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (withCredentials) request.header("Authorization", authorization);
        try {
            return http.send(request.build(), handler);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("The MaxMind request was interrupted", ex);
        }
    }

    private static void discard(HttpResponse<?> response) throws IOException {
        if (response.body() instanceof InputStream body) body.close();
    }

    private static Instant lastModified(HttpResponse<?> response) throws IOException {
        String value = response.headers().firstValue("Last-Modified")
                .orElseThrow(() -> new IOException("MaxMind sent no Last-Modified date"));
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ex) {
            throw new IOException("MaxMind sent an unreadable Last-Modified date: " + value, ex);
        }
    }

    /** Opens the new file once, and checks it is the configured edition. */
    private void check(Path candidate) throws IOException {
        try (Reader reader = new Reader(candidate.toFile(), Reader.FileMode.MEMORY)) {
            String type = reader.getMetadata().databaseType();
            if (!edition.equals(type)) throw new IOException("MaxMind sent a " + type + " file instead of " + edition);
        } catch (RuntimeException ex) {
            throw new IOException("The downloaded MaxMind file cannot be read: " + Text.message(ex), ex);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
