package io.github.origingate.core.storage;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Driver;
import java.sql.SQLException;

/** SQLite JNI names cannot be relocated, so isolate its classes and native resources instead. */
final class BundledSqlite {
    /**
     * The systems whose SQLite native library the plugin JARs include. The build leaves out the others to keep every
     * JAR under 10 MB; keep this in step with the excludes in build.gradle.kts and with docs/compatibility.md.
     */
    static final String SUPPORTED = "Linux (x86_64 and aarch64, glibc and musl), Windows (x86_64 and aarch64), "
            + "and macOS (x86_64 and aarch64)";

    private BundledSqlite() { }

    /** A clear error when SQLite has no native library for this system; other errors are returned unchanged. */
    static SQLException explain(SQLException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = String.valueOf(cause.getMessage());
            if (cause.getClass().getSimpleName().equals("NativeLibraryNotFoundException") || cause instanceof UnsatisfiedLinkError
                    || message.contains("No native library found")) {
                return new SQLException("SQLite cannot run on this system (" + System.getProperty("os.name") + ", "
                        + System.getProperty("os.arch") + "). OriginGate includes SQLite for " + SUPPORTED
                        + ". Set storage.type to mysql to use MySQL or MariaDB instead.", error);
            }
        }
        return error;
    }
    static Driver driver() throws SQLException {
        // A failed initializer would only be reported once; later calls would throw NoClassDefFoundError instead.
        if (Holder.DRIVER == null) throw new SQLException("Could not load bundled SQLite", Holder.FAILURE);
        return Holder.DRIVER;
    }
    private static final class Holder {
        static final Driver DRIVER;
        static final Throwable FAILURE;
        static {
            Driver driver = null;
            Throwable failure = null;
            try { driver = load(); } catch (RuntimeException | LinkageError ex) { failure = ex; }
            DRIVER = driver;
            FAILURE = failure;
        }
        private static Driver load() {
            try {
                URL location = BundledSqlite.class.getProtectionDomain().getCodeSource().getLocation();
                if (!Files.isRegularFile(Paths.get(location.toURI()))) return new org.sqlite.JDBC();
                ClassLoader loader = new URLClassLoader(new URL[] { location }, BundledSqlite.class.getClassLoader()) {
                    @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                        if (!name.startsWith("org.sqlite.") && !name.equals(SqliteAccess.class.getName())) return super.loadClass(name, resolve);
                        Class<?> type = findLoadedClass(name);
                        if (type == null) type = findClass(name);
                        if (resolve) resolveClass(type);
                        return type;
                    }
                    @Override public URL getResource(String name) {
                        if (name.startsWith("org/sqlite/") || name.startsWith("META-INF/maven/org.xerial/sqlite-jdbc/")) {
                            URL resource = findResource(name);
                            if (resource != null) return resource;
                        }
                        return super.getResource(name);
                    }
                };
                return (Driver) Class.forName(SqliteAccess.class.getName(), true, loader).getMethod("create").invoke(null);
            } catch (Exception ex) { throw new IllegalStateException("Could not isolate bundled SQLite", ex); }
        }
    }
}
