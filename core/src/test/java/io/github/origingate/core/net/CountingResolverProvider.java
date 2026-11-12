package io.github.origingate.core.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.stream.Stream;

/**
 * Test-only resolver, registered in META-INF/services, that counts name lookups made on each thread and passes them to
 * the built-in resolver. Names ending in {@code .invalid} are refused without a query, so tests can check the counter.
 */
public final class CountingResolverProvider extends InetAddressResolverProvider {
    private static final ThreadLocal<int[]> LOOKUPS = ThreadLocal.withInitial(() -> new int[1]);

    /** Name lookups made on the calling thread since the last reset. */
    static int lookups() { return LOOKUPS.get()[0]; }

    static void reset() { LOOKUPS.get()[0] = 0; }

    @Override public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
                LOOKUPS.get()[0]++;
                if (host.endsWith(".invalid")) throw new UnknownHostException(host);
                return builtin.lookupByName(host, policy);
            }

            @Override public String lookupByAddress(byte[] address) throws UnknownHostException {
                return builtin.lookupByAddress(address);
            }
        };
    }

    @Override public String name() { return "OriginGate test counting resolver"; }
}
