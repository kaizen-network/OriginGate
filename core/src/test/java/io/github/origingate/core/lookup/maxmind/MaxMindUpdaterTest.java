package io.github.origingate.core.lookup.maxmind;

import io.github.origingate.core.Log;
import io.github.origingate.core.TestServer;
import io.github.origingate.core.TestServer.Reply;
import io.github.origingate.core.TestServer.Request;
import io.github.origingate.core.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaxMindUpdaterTest {
    private static final String AUTH = "Basic " + Base64.getEncoder()
            .encodeToString("123456:test-key".getBytes(StandardCharsets.UTF_8));
    @TempDir Path directory;
    private TestServer server;
    private MaxMindProvider provider;
    private volatile Instant released = Instant.parse("2026-09-22T10:00:00Z");
    private volatile byte[] archive;
    private volatile int status = 200;
    private final AtomicInteger downloads = new AtomicInteger();

    @BeforeEach void start() throws IOException {
        archive = archive("GeoLite2-Country-Test.mmdb");
        server = new TestServer(this::answer);
        provider = new MaxMindProvider(file(), Clock.fixed(TestSupport.NOW, ZoneOffset.UTC), Log.NONE);
    }

    @AfterEach void stop() {
        server.close();
        provider.close();
    }

    private Path file() {
        return directory.resolve("data/GeoLite2-Country.mmdb");
    }

    private static byte[] archive(String resource) throws IOException {
        byte[] database;
        try (InputStream input = MaxMindUpdaterTest.class.getResourceAsStream("/maxmind/" + resource)) {
            database = input.readAllBytes();
        }
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("GeoLite2-Country_20260922/", new byte[0]);
        entries.put("GeoLite2-Country_20260922/GeoLite2-Country.mmdb", database);
        return TarGzTest.tarGz(entries);
    }

    /** Like MaxMind: the permalink checks Basic auth and redirects to a storage host that must not get the credentials. */
    private Reply answer(Request request) {
        String path = request.uri().getPath();
        if (path.equals("/geoip/databases/GeoLite2-Country/download") || path.equals("/geoip/databases/GeoLite2-City/download")) {
            if (!AUTH.equals(request.headers().getFirst("Authorization"))) return Reply.text(401, "");
            if (status != 200) return Reply.text(status, "");
            return new Reply(302, new byte[0], Map.of("Location", "/bucket/GeoLite2-Country.tar.gz?signature=test"));
        }
        if (path.equals("/bucket/GeoLite2-Country.tar.gz")) {
            if (request.headers().getFirst("Authorization") != null) return Reply.text(400, "credentials reached storage");
            if (request.method().equals("GET")) downloads.incrementAndGet();
            String modified = DateTimeFormatter.RFC_1123_DATE_TIME.format(released.atZone(ZoneOffset.UTC));
            return new Reply(200, archive, Map.of("Last-Modified", modified));
        }
        return Reply.text(404, "");
    }

    private MaxMindUpdater updater(String licenseKey) {
        return updater("GeoLite2-Country", licenseKey);
    }

    private MaxMindUpdater updater(String edition, String licenseKey) {
        return new MaxMindUpdater(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                server.uri("/geoip/databases/"), edition, 123456, licenseKey, provider, file(), Log.NONE,
                "OriginGate/test");
    }

    private List<Path> filesInDataFolder() throws IOException {
        try (Stream<Path> files = Files.list(file().getParent())) {
            return files.toList();
        }
    }

    private String recordedRelease() throws IOException {
        return Files.readString(file().resolveSibling("GeoLite2-Country.mmdb.release"));
    }

    @Test void firstRunDownloadsAndLoadsTheFile() throws Exception {
        assertEquals(MaxMindUpdater.Outcome.DOWNLOADED, updater("test-key").update());
        assertEquals("GB", provider.lookup("81.2.69.160", TestSupport.NO_DEADLINE).countryCode());
        assertTrue(recordedRelease().contains("released=" + released), recordedRelease());
        assertEquals(1, downloads.get());
    }

    @Test void fileNotDownloadedByTheUpdaterIsReplaced() throws Exception {
        // An old database placed by hand, with a file time newer than MaxMind's latest release.
        TestSupport.copyResource("/maxmind/GeoLite2-Country-Test.mmdb", file());
        Files.setLastModifiedTime(file(), FileTime.from(released.plus(Duration.ofDays(3))));
        assertTrue(provider.reload());
        assertEquals(MaxMindUpdater.Outcome.DOWNLOADED, updater("test-key").update());
        assertEquals(1, downloads.get());
    }

    @Test void sameReleaseIsNotDownloadedAgain() throws Exception {
        MaxMindUpdater updater = updater("test-key");
        updater.update();
        assertEquals(MaxMindUpdater.Outcome.UP_TO_DATE, updater.update());
        assertEquals(1, downloads.get());
    }

    @Test void editionChangeIsDownloadedRightAway() throws Exception {
        updater("test-key").update();
        // Only lookup.maxmind.edition changed; the file path still holds the Country file and its release record.
        archive = archive("GeoLite2-City-Test.mmdb");
        assertEquals(MaxMindUpdater.Outcome.DOWNLOADED, updater("GeoLite2-City", "test-key").update());
        assertEquals("London", provider.lookup("81.2.69.160", TestSupport.NO_DEADLINE).city());
        assertEquals(2, downloads.get());
    }

    @Test void newerReleaseReplacesTheFile() throws Exception {
        MaxMindUpdater updater = updater("test-key");
        updater.update();
        released = released.plus(Duration.ofDays(7));
        assertEquals(MaxMindUpdater.Outcome.DOWNLOADED, updater.update());
        assertEquals(2, downloads.get());
        assertTrue(recordedRelease().contains("released=" + released), recordedRelease());
    }

    @Test void wrongLicenseKeyFails() {
        IOException failure = assertThrows(IOException.class, () -> updater("wrong-key").update());
        assertTrue(failure.getMessage().contains("401"), failure.getMessage());
        assertFalse(Files.exists(file()));
    }

    @Test void rateLimitFails() {
        status = 429;
        IOException failure = assertThrows(IOException.class, () -> updater("test-key").update());
        assertTrue(failure.getMessage().contains("429"), failure.getMessage());
    }

    @Test void brokenArchiveKeepsTheCurrentFileAndLeavesNoTemporaryFiles() throws Exception {
        MaxMindUpdater updater = updater("test-key");
        updater.update();
        released = released.plus(Duration.ofDays(1));
        archive = "not a gzip archive".getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class, updater::update);
        assertEquals("GB", provider.lookup("81.2.69.160", TestSupport.NO_DEADLINE).countryCode());
        assertTrue(Files.exists(file()));
        assertTrue(filesInDataFolder().stream().noneMatch(path -> path.toString().endsWith(".download")), filesInDataFolder().toString());
    }

    @Test void wrongEditionIsRefused() throws Exception {
        archive = archive("GeoLite2-City-Test.mmdb");
        IOException failure = assertThrows(IOException.class, () -> updater("test-key").update());
        assertTrue(failure.getMessage().contains("instead of GeoLite2-Country"), failure.getMessage());
        assertFalse(Files.exists(file()));
        assertEquals(List.of(), filesInDataFolder());
    }
}
