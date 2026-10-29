package io.github.origingate.core.lookup.maxmind;

import java.io.*;
import java.lang.reflect.*;
import java.net.InetAddress;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Isolates reader versions so modern runtimes retain the current decoder. */
final class MaxMindDatabase implements AutoCloseable {
    private final Object reader;
    private final Method lookup;
    private final Method close;
    private final Instant built;
    private final String type;

    @SuppressWarnings({"unchecked", "rawtypes"})
    MaxMindDatabase(Path file) throws IOException {
        if (Files.size(file) > 512L * 1024 * 1024) throw new IOException("MaxMind file is larger than 512 MiB");
        String version = System.getProperty("java.specification.version");
        int major = Integer.parseInt(version.startsWith("1.") ? version.substring(2) : version);
        boolean modern = major >= 17;
        String prefix = "io.github.origingate.internal.maxmind" + (modern ? "17" : "8");
        Object opened = null;
        try {
            Class<?> readerType = Class.forName(prefix + ".Reader");
            Class<? extends Enum> mode = (Class<? extends Enum>) Class.forName(prefix + ".Reader$FileMode");
            opened = readerType.getConstructor(File.class, mode).newInstance(file.toFile(), Enum.valueOf(mode, "MEMORY"));
            Object metadata = readerType.getMethod("getMetadata").invoke(opened);
            Object date = metadata.getClass().getMethod(modern ? "buildTime" : "getBuildDate").invoke(metadata);
            built = modern ? (Instant) date : ((Date) date).toInstant();
            type = (String) metadata.getClass().getMethod(modern ? "databaseType" : "getDatabaseType").invoke(metadata);
            lookup = readerType.getMethod("get", InetAddress.class, Class.class);
            close = readerType.getMethod("close");
            reader = opened;
        } catch (ReflectiveOperationException | LinkageError ex) {
            if (opened instanceof Closeable) try { ((Closeable) opened).close(); } catch (IOException ignored) { }
            throw failure(ex);
        }
    }
    Instant buildTime() { return built; }
    String databaseType() { return type; }
    Map<?, ?> get(InetAddress address) throws IOException {
        try { return (Map<?, ?>) lookup.invoke(reader, address, Map.class); }
        catch (ReflectiveOperationException ex) { throw failure(ex); }
    }
    @Override public void close() throws IOException {
        try { close.invoke(reader); }
        catch (ReflectiveOperationException ex) { throw failure(ex); }
    }
    private static IOException failure(Throwable ex) {
        Throwable cause = ex instanceof InvocationTargetException ? ((InvocationTargetException) ex).getCause() : ex;
        return cause instanceof IOException ? (IOException) cause : new IOException("Cannot use MaxMind reader: " + cause, cause);
    }
}
