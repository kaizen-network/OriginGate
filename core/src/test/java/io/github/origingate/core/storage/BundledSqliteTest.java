package io.github.origingate.core.storage;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundledSqliteTest {
    /** sqlite-jdbc's own exception class, which OriginGate cannot reference because SQLite is loaded in isolation. */
    static final class NativeLibraryNotFoundException extends Exception {
        NativeLibraryNotFoundException(String message) { super(message); }
    }

    @Test void missingNativeLibraryNamesTheSupportedSystemsAndMysql() {
        SQLException opening = new SQLException("Error opening connection",
                new NativeLibraryNotFoundException("No native library found for os.name=FreeBSD, os.arch=x86_64, paths=[]"));
        SQLException explained = BundledSqlite.explain(opening);
        assertTrue(explained instanceof SqliteUnavailableException, "the runtime falls back to memory-only storage on it");
        String message = explained.getMessage();
        assertTrue(message.startsWith("SQLite cannot run on this system ("), message);
        assertTrue(message.contains("Linux (x86_64 and aarch64, glibc and musl), Windows (x86_64 and aarch64), and macOS"), message);
        assertTrue(message.contains("Set storage.type to mysql to use MySQL or MariaDB instead."), message);
        assertSame(opening, explained.getCause());
        assertTrue(BundledSqlite.explain(new SQLException("load failed", new UnsatisfiedLinkError("libc too old")))
                .getMessage().startsWith("SQLite cannot run"));
    }

    @Test void otherErrorsAreUnchanged() {
        SQLException locked = new SQLException("database is locked");
        assertSame(locked, BundledSqlite.explain(locked));
    }
}
