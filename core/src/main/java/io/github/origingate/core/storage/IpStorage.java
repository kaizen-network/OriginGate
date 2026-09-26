package io.github.origingate.core.storage;

import io.github.origingate.core.lookup.IpInfo;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/** Saved lookups. Every method blocks, so call them from a worker thread. */
public interface IpStorage extends AutoCloseable {
    /** A row checked at or after {@code notBefore}. */
    Optional<IpInfo> find(String ip, Instant notBefore) throws SQLException;

    /** Inserts or replaces the row for this IP. */
    void save(IpInfo info) throws SQLException;

    /** Deletes rows checked before {@code cutoff}. */
    int deleteOlderThan(Instant cutoff) throws SQLException;

    int delete(String ip) throws SQLException;

    int deleteAll() throws SQLException;

    @Override default void close() { }
}
