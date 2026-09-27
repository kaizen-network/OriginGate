package io.github.origingate.core.lookup.maxmind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TarGzTest {
    @TempDir Path directory;

    /** A .tar.gz with the given entries in order. Names ending in "/" are directories. */
    static byte[] tarGz(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                byte[] header = new byte[512];
                put(header, 0, entry.getKey());
                put(header, 100, "0000644");
                put(header, 124, String.format("%011o", entry.getValue().length));
                header[156] = (byte) (entry.getKey().endsWith("/") ? '5' : '0');
                put(header, 257, "ustar");
                put(header, 263, "00");
                gzip.write(header);
                gzip.write(entry.getValue());
                gzip.write(new byte[(512 - entry.getValue().length % 512) % 512]);
            }
            gzip.write(new byte[1024]);
        }
        return bytes.toByteArray();
    }

    private static void put(byte[] header, int offset, String text) {
        byte[] value = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(value, 0, header, offset, value.length);
    }

    private static Map<String, byte[]> archive(byte[] database) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("GeoLite2-Country_20260922/", new byte[0]);
        entries.put("GeoLite2-Country_20260922/LICENSE.txt", "license text".getBytes(StandardCharsets.US_ASCII));
        entries.put("GeoLite2-Country_20260922/GeoLite2-Country.mmdb", database);
        return entries;
    }

    @Test void extractsTheDatabaseAfterOtherEntries() throws Exception {
        byte[] database = new byte[1300];
        Arrays.fill(database, (byte) 7);
        Path target = directory.resolve("out.mmdb");
        TarGz.extract(new ByteArrayInputStream(tarGz(archive(database))), ".mmdb", target, 10_000);
        assertArrayEquals(database, Files.readAllBytes(target));
    }

    @Test void missingEntryFails() throws Exception {
        byte[] archive = tarGz(Map.of("README.txt", new byte[10]));
        IOException failure = assertThrows(IOException.class,
                () -> TarGz.extract(new ByteArrayInputStream(archive), ".mmdb", directory.resolve("out.mmdb"), 10_000));
        assertTrue(failure.getMessage().contains("no .mmdb"), failure.getMessage());
    }

    @Test void oversizedEntryFails() throws Exception {
        byte[] archive = tarGz(archive(new byte[2000]));
        assertThrows(IOException.class,
                () -> TarGz.extract(new ByteArrayInputStream(archive), ".mmdb", directory.resolve("out.mmdb"), 1000));
    }

    @Test void truncatedArchiveFails() throws Exception {
        byte[] full = tarGz(archive(new byte[5000]));
        byte[] cut = Arrays.copyOf(full, full.length / 2);
        assertThrows(IOException.class,
                () -> TarGz.extract(new ByteArrayInputStream(cut), ".mmdb", directory.resolve("out.mmdb"), 10_000));
    }
}
