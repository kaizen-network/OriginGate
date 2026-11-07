package io.github.origingate.core.lists;

import io.github.origingate.core.net.AddressRange;
import io.github.origingate.core.net.Addresses;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** One allow or deny list entry: a player name, a UUID, an IP address, or a CIDR range. */
public final class ListEntry {
    public enum Kind {
        ALLOW("allow"), DENY("deny");

        private final String id;

        Kind(String id) { this.id = id; }

        public String id() { return id; }

        /** The kind stored under this id, or null. */
        public static Kind ofId(String id) {
            for (Kind kind : values()) if (kind.id.equals(id)) return kind;
            return null;
        }
    }

    private static final Pattern UUID_TEXT = Pattern.compile("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.*-]{1,32}");

    private final Kind kind;
    private final String target;
    private final Instant expiresAt;
    private final String note;
    private final String addedBy;
    private final Instant addedAt;
    private final AddressRange range;

    /** The target is in the form {@link #normalize} returns. A null {@code expiresAt} means never; a null note means none. */
    public ListEntry(Kind kind, String target, Instant expiresAt, String note, String addedBy, Instant addedAt) {
        this.kind = Objects.requireNonNull(kind);
        this.target = Objects.requireNonNull(target);
        this.expiresAt = expiresAt;
        this.note = note;
        this.addedBy = Objects.requireNonNull(addedBy);
        this.addedAt = Objects.requireNonNull(addedAt);
        AddressRange parsed;
        try {
            parsed = AddressRange.parse(target);
        } catch (IllegalArgumentException ex) {
            parsed = null;
        }
        this.range = parsed;
    }

    public Kind kind() { return kind; }

    public String target() { return target; }

    public Instant expiresAt() { return expiresAt; }

    public String note() { return note; }

    public String addedBy() { return addedBy; }

    public Instant addedAt() { return addedAt; }

    public boolean activeAt(Instant now) { return expiresAt == null || now.isBefore(expiresAt); }

    /** True for an IP address or a CIDR range, false for a name or a UUID. */
    public boolean isAddress() { return range != null; }

    public boolean matchesPlayer(String username, UUID uuid) {
        if (range != null) return false;
        return (username != null && target.equals(username.toLowerCase(Locale.ROOT)))
                || (uuid != null && target.equals(uuid.toString()));
    }

    public boolean matchesAddress(InetAddress address) {
        return range != null && address != null && range.contains(address);
    }

    /**
     * The stored form of a target: an address (with {@code /prefix} only when shorter than the whole address),
     * else a UUID, else a player name, in lower case.
     * @throws IllegalArgumentException when the input is none of these
     */
    public static String normalize(String input) {
        String value = input == null ? "" : input.trim();
        int slash = value.indexOf('/');
        String host = slash < 0 ? value : value.substring(0, slash);
        InetAddress address = Addresses.parse(host).orElse(null);
        if (address != null) {
            try {
                AddressRange.parse(value);
            } catch (IllegalArgumentException ex) {
                throw invalid(input);
            }
            String text = Addresses.text(address);
            if (slash >= 0) {
                int prefix = Integer.parseInt(value.substring(slash + 1));
                if (prefix < address.getAddress().length * 8) text += "/" + prefix;
            }
            return text;
        }
        if (UUID_TEXT.matcher(value).matches() || NAME.matcher(value).matches()) return value.toLowerCase(Locale.ROOT);
        throw invalid(input);
    }

    private static IllegalArgumentException invalid(String input) {
        return new IllegalArgumentException(input + " is not a player name, UUID, IP address, or CIDR range");
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ListEntry)) return false;
        ListEntry that = (ListEntry) other;
        return kind == that.kind && target.equals(that.target) && Objects.equals(expiresAt, that.expiresAt)
                && Objects.equals(note, that.note) && addedBy.equals(that.addedBy) && addedAt.equals(that.addedAt);
    }

    @Override public int hashCode() { return Objects.hash(kind, target, expiresAt, note, addedBy, addedAt); }

    @Override public String toString() {
        return "ListEntry{" + kind.id() + " " + target + ", expiresAt=" + expiresAt + ", note=" + note
                + ", addedBy=" + addedBy + ", addedAt=" + addedAt + "}";
    }
}
