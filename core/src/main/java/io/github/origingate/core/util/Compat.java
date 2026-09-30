package io.github.origingate.core.util;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Small Java 8 equivalents for immutable values and bounded stream operations. */
public final class Compat {
    private Compat() { }
    @SafeVarargs public static <T> List<T> list(T... values) { return listCopy(Arrays.asList(values)); }
    public static <T> List<T> listCopy(Collection<? extends T> values) {
        ArrayList<T> copy = new ArrayList<>(values);
        for (T value : copy) Objects.requireNonNull(value);
        return Collections.unmodifiableList(copy);
    }
    @SafeVarargs public static <T> Set<T> set(T... values) { return setCopy(Arrays.asList(values)); }
    public static <T> Set<T> setCopy(Collection<? extends T> values) {
        LinkedHashSet<T> copy = new LinkedHashSet<>(values);
        for (T value : copy) Objects.requireNonNull(value);
        return Collections.unmodifiableSet(copy);
    }
    public static <K,V> Map<K,V> map() { return Collections.emptyMap(); }
    public static <K,V> Map<K,V> map(K key, V value) {
        return Collections.singletonMap(Objects.requireNonNull(key), Objects.requireNonNull(value));
    }
    public static <K,V> Map<K,V> mapCopy(Map<? extends K,? extends V> values) {
        Map<K,V> copy = new LinkedHashMap<>();
        values.forEach((k,v) -> copy.put(Objects.requireNonNull(k), Objects.requireNonNull(v)));
        return Collections.unmodifiableMap(copy);
    }
    public static String encode(String value) {
        try { return java.net.URLEncoder.encode(value, "UTF-8"); }
        catch (UnsupportedEncodingException ex) { throw new AssertionError(ex); }
    }
    public static LocalDate date(Instant value, ZoneId zone) { return value.atZone(zone).toLocalDate(); }
    public static void writeString(Path path, String value, Charset charset, OpenOption... options) throws IOException {
        Files.write(path, value.getBytes(charset), options);
    }
    public static boolean blank(String value) {
        return value.codePoints().allMatch(Character::isWhitespace);
    }
    public static int read(InputStream input, byte[] bytes, int offset, int length) throws IOException {
        int total = 0;
        while (total < length) {
            int count = input.read(bytes, offset + total, length - total);
            if (count < 0) break;
            total += count;
        }
        return total;
    }
    public static void skip(InputStream input, long length) throws IOException {
        while (length > 0) {
            long count = input.skip(length);
            if (count == 0) {
                if (input.read() < 0) throw new EOFException("Archive ended while skipping an entry");
                count = 1;
            }
            length -= count;
        }
    }
}
