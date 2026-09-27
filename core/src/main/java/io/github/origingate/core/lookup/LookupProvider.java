package io.github.origingate.core.lookup;

/** Looks up one IP address from an external service or file. Called from a worker thread. */
public interface LookupProvider {
    /** The name used in {@code country-from}, {@code vpn-from}, and logs, for example "proxycheck". */
    String name();

    IpInfo lookup(String ip) throws LookupException;
}
