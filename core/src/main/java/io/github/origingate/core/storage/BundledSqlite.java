package io.github.origingate.core.storage;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Driver;
import java.sql.SQLException;

/** SQLite JNI names cannot be relocated, so isolate its classes and native resources instead. */
final class BundledSqlite {
    private BundledSqlite() { }
    static Driver driver() throws SQLException {
        try { return Holder.DRIVER; }
        catch (ExceptionInInitializerError ex) { throw new SQLException("Could not load bundled SQLite", ex.getCause()); }
    }
    private static final class Holder {
        static final Driver DRIVER = load();
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
