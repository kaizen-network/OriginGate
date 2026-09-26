package io.github.origingate.core.rules;

/** Rules in the order they are checked, plus the lookup-failure outcome. */
public enum Rule {
    DENY_ADDRESSES("deny-addresses"),
    VPN("vpn"),
    PROXY("proxy"),
    COUNTRY("country"),
    LOOKUP_FAILURE("lookup-failure");

    private final String id;

    Rule(String id) { this.id = id; }

    /** The name used in config.yml, messages.yml, and logs. */
    public String id() { return id; }
}
