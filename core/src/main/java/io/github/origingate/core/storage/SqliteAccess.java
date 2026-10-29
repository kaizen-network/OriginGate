package io.github.origingate.core.storage;

import java.sql.Driver;
import java.sql.DriverManager;
import java.util.Enumeration;

/** Loaded with SQLite so DriverManager's caller visibility check permits deregistration. */
public final class SqliteAccess {
    private SqliteAccess() { }
    public static Driver create() throws Exception {
        Driver driver = new org.sqlite.JDBC();
        Enumeration<Driver> registered = DriverManager.getDrivers();
        while (registered.hasMoreElements()) {
            Driver candidate = registered.nextElement();
            if (candidate.getClass().getClassLoader() == SqliteAccess.class.getClassLoader()) DriverManager.deregisterDriver(candidate);
        }
        return driver;
    }
}
