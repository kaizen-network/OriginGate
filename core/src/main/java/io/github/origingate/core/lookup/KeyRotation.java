package io.github.origingate.core.lookup;

import io.github.origingate.core.Log;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Uses keys in turn. A refused key is skipped for {@link ProviderChain#REFUSED_PAUSE} and followed by one try with
 * the next key. {@link KeyRejectedException} is thrown only when every key has been refused, so the chain pauses the
 * provider only then. With no keys, one request is sent without a key.
 */
final class KeyRotation {
    interface Request {
        IpInfo send(String key) throws LookupException;
    }

    private final List<String> keys;
    private final String label;
    private final Clock clock;
    private final Log log;
    private final AtomicInteger next = new AtomicInteger();
    private final Map<Integer, Instant> refusedUntil = new ConcurrentHashMap<>();

    KeyRotation(List<String> keys, String label, Clock clock, Log log) {
        this.keys = List.copyOf(keys);
        this.label = label;
        this.clock = clock;
        this.log = log;
    }

    IpInfo lookup(Request request) throws LookupException {
        if (keys.isEmpty()) return request.send(null);
        int first = usable(Math.floorMod(next.getAndIncrement(), keys.size()));
        if (first < 0) throw new KeyRejectedException("all " + keys.size() + " API keys were refused in the last minute");
        try {
            return request.send(keys.get(first));
        } catch (KeyRejectedException ex) {
            refuse(first);
            int second = usable((first + 1) % keys.size());
            if (second < 0) throw ex;
            log.warn(label + " refused API key " + (first + 1) + " (" + ex.getMessage() + "), trying key " + (second + 1), null);
            try {
                return request.send(keys.get(second));
            } catch (KeyRejectedException again) {
                refuse(second);
                if (usable(second) < 0) throw again;
                // Other keys still work, so this is a failed lookup, not a reason to pause the provider.
                throw new LookupException(label + " refused API keys " + (first + 1) + " and " + (second + 1) + ": "
                        + again.getMessage());
            }
        }
    }

    /** The first key from {@code start} on that is not paused, or -1 when all are. */
    private int usable(int start) {
        Instant now = clock.instant();
        for (int i = 0; i < keys.size(); i++) {
            int index = (start + i) % keys.size();
            Instant until = refusedUntil.get(index);
            if (until == null || !now.isBefore(until)) return index;
        }
        return -1;
    }

    private void refuse(int index) {
        refusedUntil.put(index, clock.instant().plus(ProviderChain.REFUSED_PAUSE));
    }
}
