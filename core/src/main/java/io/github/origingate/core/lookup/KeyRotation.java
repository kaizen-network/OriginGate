package io.github.origingate.core.lookup;

import io.github.origingate.core.Log;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Uses keys in turn. A refused key is followed by one try with the next key.
 * With no keys, one request is sent without a key.
 */
final class KeyRotation {
    interface Request {
        IpInfo send(String key) throws LookupException;
    }

    private final List<String> keys;
    private final String label;
    private final Log log;
    private final AtomicInteger next = new AtomicInteger();

    KeyRotation(List<String> keys, String label, Log log) {
        this.keys = List.copyOf(keys);
        this.label = label;
        this.log = log;
    }

    IpInfo lookup(Request request) throws LookupException {
        if (keys.isEmpty()) return request.send(null);
        int first = Math.floorMod(next.getAndIncrement(), keys.size());
        try {
            return request.send(keys.get(first));
        } catch (KeyRejectedException ex) {
            if (keys.size() == 1) throw ex;
            int second = (first + 1) % keys.size();
            log.warn(label + " refused API key " + (first + 1) + " (" + ex.getMessage() + "), trying key " + (second + 1), null);
            return request.send(keys.get(second));
        }
    }
}
