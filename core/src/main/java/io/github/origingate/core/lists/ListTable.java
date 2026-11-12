package io.github.origingate.core.lists;

import io.github.origingate.core.util.Compat;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Where list entries are kept. One entry per target: {@link #put} replaces any entry with the same target. */
public interface ListTable {
    List<ListEntry> all() throws SQLException;

    void put(ListEntry entry) throws SQLException;

    /** Removes the entry for {@code target} when it is on the {@code kind} list. Returns how many rows went. */
    int remove(ListEntry.Kind kind, String target) throws SQLException;

    /** Deletes entries that expired at or before {@code now}. */
    int deleteExpired(Instant now) throws SQLException;

    /** Entries that live only in memory, for when no database can be used. */
    static ListTable memory() {
        return new ListTable() {
            private final Map<String, ListEntry> rows = new LinkedHashMap<>();

            @Override public synchronized List<ListEntry> all() { return Compat.listCopy(rows.values()); }

            @Override public synchronized void put(ListEntry entry) { rows.put(entry.target(), entry); }

            @Override public synchronized int remove(ListEntry.Kind kind, String target) {
                ListEntry found = rows.get(target);
                if (found == null || found.kind() != kind) return 0;
                rows.remove(target);
                return 1;
            }

            @Override public synchronized int deleteExpired(Instant now) {
                List<String> expired = new ArrayList<>();
                for (ListEntry entry : rows.values()) if (!entry.activeAt(now)) expired.add(entry.target());
                for (String target : expired) rows.remove(target);
                return expired.size();
            }
        };
    }
}
