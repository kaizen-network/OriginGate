package io.github.origingate.core.lookup;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.origingate.core.Log;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * proxycheck.io v3 API. Format reference: https://proxycheck.io/api/
 * Keys are used in turn. A rejected or rate-limited key is followed by one try with the next key.
 */
public final class ProxyCheckProvider implements LookupProvider {
    private static final int MAX_BODY_CHARS = 256 * 1024;
    private final HttpClient http;
    private final String baseUrl;
    private final List<String> keys;
    private final Duration requestTimeout;
    private final Clock clock;
    private final Log log;
    private final String userAgent;
    private final AtomicInteger next = new AtomicInteger();

    public ProxyCheckProvider(HttpClient http, URI baseUrl, List<String> keys, Duration requestTimeout, Clock clock,
                              Log log, String userAgent) {
        this.http = http;
        String base = baseUrl.toString();
        this.baseUrl = base.endsWith("/") ? base : base + "/";
        this.keys = List.copyOf(keys);
        this.requestTimeout = requestTimeout;
        this.clock = clock;
        this.log = log;
        this.userAgent = userAgent;
    }

    /** Thrown when the API refuses a key: invalid, out of queries, or rate-limited. */
    public static final class KeyRejectedException extends LookupException {
        public KeyRejectedException(String message) { super(message); }
    }

    @Override public IpInfo lookup(String ip) throws LookupException {
        if (keys.isEmpty()) return request(ip, null);
        int first = Math.floorMod(next.getAndIncrement(), keys.size());
        try {
            return request(ip, keys.get(first));
        } catch (KeyRejectedException ex) {
            if (keys.size() == 1) throw ex;
            int second = (first + 1) % keys.size();
            log.warn("proxycheck.io refused API key " + (first + 1) + " (" + ex.getMessage() + "), trying key " + (second + 1), null);
            return request(ip, keys.get(second));
        }
    }

    private IpInfo request(String ip, String key) throws LookupException {
        String url = baseUrl + ip + (key == null ? "" : "?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException ex) {
            throw new LookupException("proxycheck.io did not answer within " + requestTimeout.toMillis() + " ms", ex);
        } catch (IOException ex) {
            throw new LookupException("proxycheck.io request failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LookupException("proxycheck.io request was interrupted", ex);
        }
        int code = response.statusCode();
        String body = response.body();
        if (code == 401 || code == 403 || code == 429) throw new KeyRejectedException("HTTP " + code + message(body));
        if (code != 200) throw new LookupException("proxycheck.io answered HTTP " + code + message(body));
        return parse(ip, body, clock.instant());
    }

    /** Parses a v3 response for {@code ip}. Public for fixture tests. */
    public static IpInfo parse(String ip, String body, Instant checkedAt) throws LookupException {
        JsonObject root;
        try {
            if (body == null || body.length() > MAX_BODY_CHARS) throw new LookupException("proxycheck.io sent an empty or oversized response");
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException ex) {
            throw new LookupException("proxycheck.io sent a response that is not valid JSON", ex);
        }
        String status = text(root, "status");
        if ("denied".equals(status)) throw new KeyRejectedException("status denied" + message(root));
        if (!"ok".equals(status) && !"warning".equals(status)) {
            throw new LookupException("proxycheck.io answered status " + status + message(root));
        }
        JsonObject entry = entry(root, ip);
        if (entry == null) throw new LookupException("proxycheck.io response has no data for " + ip);
        JsonObject network = object(entry, "network");
        JsonObject location = object(entry, "location");
        JsonObject detections = object(entry, "detections");
        JsonObject operator = object(entry, "operator");
        return new IpInfo(ip, text(network, "provider"), text(network, "organisation"), text(operator, "name"),
                text(location, "city_name"), text(location, "region_name"), text(location, "country_name"),
                text(location, "country_code"), text(network, "asn"), bool(detections, "vpn"),
                bool(detections, "proxy"), text(network, "type"), checkedAt);
    }

    /** The object for this IP. The API may print IPv6 addresses differently, so fall back to the only entry. */
    private static JsonObject entry(JsonObject root, String ip) {
        JsonObject exact = object(root, ip);
        if (exact != null) return exact;
        JsonObject found = null;
        for (Map.Entry<String, JsonElement> member : root.entrySet()) {
            if (member.getValue().isJsonObject() && member.getValue().getAsJsonObject().has("detections")) {
                if (found != null) return null;
                found = member.getValue().getAsJsonObject();
            }
        }
        return found;
    }

    private static JsonObject object(JsonObject parent, String name) {
        if (parent == null) return null;
        JsonElement value = parent.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String text(JsonObject parent, String name) {
        if (parent == null) return null;
        JsonElement value = parent.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static boolean bool(JsonObject parent, String name) {
        if (parent == null) return false;
        JsonElement value = parent.get(name);
        if (value == null || !value.isJsonPrimitive()) return false;
        if (value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        return "yes".equalsIgnoreCase(value.getAsString()) || "true".equalsIgnoreCase(value.getAsString());
    }

    private static String message(JsonObject root) {
        String message = text(root, "message");
        return message == null ? "" : ": " + IpInfo.clean(message);
    }

    private static String message(String body) {
        try {
            return message(JsonParser.parseString(body).getAsJsonObject());
        } catch (RuntimeException ex) {
            return "";
        }
    }
}
