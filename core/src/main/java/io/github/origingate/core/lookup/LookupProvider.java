package io.github.origingate.core.lookup;

/** Looks up one IP address from an external service. Called from a worker thread. */
public interface LookupProvider {
    IpInfo lookup(String ip) throws LookupException;
}
