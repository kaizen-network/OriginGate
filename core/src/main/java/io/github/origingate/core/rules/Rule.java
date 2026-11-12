package io.github.origingate.core.rules;

/** Rules in the order they are checked, plus the lookup-failure outcome. */
public enum Rule {
    DENY_LIST("deny-list"),
    DENY_ADDRESSES("deny-addresses"),
    ACCOUNTS_PER_IP("accounts-per-ip"),
    DENY_NETWORKS("deny-networks"),
    ALLOW_NETWORKS("allow-networks"),
    VPN("vpn"),
    PROXY("proxy"),
    HOSTING("hosting"),
    COUNTRY("country"),
    LOOKUP_FAILURE("lookup-failure");

    private final String id;

    Rule(String id) { this.id = id; }

    /** The name used in config.yml, messages.yml, and logs. */
    public String id() { return id; }

    /** Rules added after 1.0.0, whose config section and kick message may be missing from older files. */
    public boolean addedLater() { return this == DENY_LIST || this == ACCOUNTS_PER_IP || this == DENY_NETWORKS || this == HOSTING; }

    /** False for a rule that only lets players in, so it has no kick message. */
    public boolean kicks() { return this != ALLOW_NETWORKS; }
}
