package io.github.origingate.core.lookup;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.origingate.core.Log;
import io.github.origingate.core.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyCheckProviderTest {
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> userAgents = new CopyOnWriteArrayList<>();
    /** HTTP status per key; missing keys answer 200 with the fixture. */
    private final Map<String, Integer> keyStatus = new ConcurrentHashMap<>();
    private volatile String fixture = "business-8.8.8.8.json";
    private volatile int delayMillis;

    @AfterEach void stop() {
        if (server != null) server.stop(0);
    }

    static String read(String name) throws IOException {
        try (InputStream input = ProxyCheckProviderTest.class.getResourceAsStream("/proxycheck/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private ProxyCheckProvider provider(List<String> keys, int timeoutMillis) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/", this::handle);
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v3/");
        return new ProxyCheckProvider(HttpClient.newHttpClient(), base, keys, Duration.ofMillis(timeoutMillis),
                Clock.fixed(TestSupport.NOW, ZoneOffset.UTC), Log.NONE, "OriginGate/test");
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestURI().toString());
        userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
        String query = exchange.getRequestURI().getQuery();
        String key = query == null ? null : query.replace("key=", "");
        int status = key == null ? 200 : keyStatus.getOrDefault(key, 200);
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        String body = status == 200 ? read(fixture) : "{\"status\":\"denied\",\"message\":\"test refusal\"}";
        if (status == -1) {
            status = 200;
            body = read("denied.json");
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        } catch (IOException ignored) {
            // The client gave up (timeout test).
        }
    }

    @Test void parsesRealBusinessResponse() throws Exception {
        IpInfo info = ProxyCheckProvider.parse("8.8.8.8", read("business-8.8.8.8.json"), TestSupport.NOW);
        assertEquals("Google LLC", info.provider());
        assertEquals("Level 3", info.organisation());
        assertNull(info.operatorName());
        assertEquals("Level 3", info.displayOrganisation());
        assertEquals("United States", info.country());
        assertEquals("US", info.countryCode());
        assertEquals("California", info.region());
        assertEquals("Mountain View", info.city());
        assertEquals("AS15169", info.asn());
        assertEquals("Business", info.type());
        assertFalse(info.vpn());
        assertFalse(info.proxy());
        assertEquals(TestSupport.NOW, info.checkedAt());
    }

    @Test void parsesHostingResponseWithNullHistory() throws Exception {
        IpInfo info = ProxyCheckProvider.parse("1.1.1.1", read("hosting-1.1.1.1.json"), TestSupport.NOW);
        assertEquals("Cloudflare, Inc.", info.provider());
        assertEquals("AU", info.countryCode());
        assertEquals("Hosting", info.type());
        assertFalse(info.vpn());
    }

    @Test void parsesVpnWithOperator() throws Exception {
        IpInfo info = ProxyCheckProvider.parse("203.0.113.7", read("vpn-203.0.113.7.json"), TestSupport.NOW);
        assertTrue(info.vpn());
        assertTrue(info.vpn());
        assertEquals("IVPN", info.operatorName());
        assertEquals("IVPN", info.displayOrganisation());
        assertEquals("NL", info.countryCode());
    }

    @Test void parsesProxy() throws Exception {
        IpInfo info = ProxyCheckProvider.parse("198.51.100.9", read("proxy-198.51.100.9.json"), TestSupport.NOW);
        assertTrue(info.proxy());
        assertFalse(info.vpn());
        assertEquals("DE", info.countryCode());
    }

    @Test void findsIpv6EntryWrittenDifferently() throws Exception {
        IpInfo info = ProxyCheckProvider.parse("2001:db8:0:0:0:0:0:1", read("ipv6-short-key.json"), TestSupport.NOW);
        assertEquals("JP", info.countryCode());
        assertEquals("2001:db8:0:0:0:0:0:1", info.ip());
    }

    @Test void deniedAndErrorStatusesFail() throws Exception {
        assertThrows(KeyRejectedException.class,
                () -> ProxyCheckProvider.parse("8.8.8.8", read("denied.json"), TestSupport.NOW));
        LookupException error = assertThrows(LookupException.class,
                () -> ProxyCheckProvider.parse("8.8.8.8", read("error.json"), TestSupport.NOW));
        assertFalse(error instanceof KeyRejectedException);
        assertTrue(error.getMessage().contains("No valid IP addresses supplied."));
        assertThrows(LookupException.class, () -> ProxyCheckProvider.parse("8.8.8.8", "not json", TestSupport.NOW));
        assertThrows(LookupException.class, () -> ProxyCheckProvider.parse("9.9.9.9", read("business-8.8.8.8.json").replace("\"8.8.8.8\"", "\"x\"").replace("\"detections\"", "\"other\""), TestSupport.NOW));
    }

    @Test void sendsKeyAsSeparateParameterAndRotatesKeys() throws Exception {
        ProxyCheckProvider provider = provider(List.of("key-one", "key-two"), 3000);
        provider.lookup("8.8.8.8");
        provider.lookup("8.8.8.8");
        provider.lookup("8.8.8.8");
        assertEquals(List.of("/v3/8.8.8.8?key=key-one", "/v3/8.8.8.8?key=key-two", "/v3/8.8.8.8?key=key-one"), requests);
        assertEquals("OriginGate/test", userAgents.get(0));
    }

    @Test void noKeysSendsNoKeyParameter() throws Exception {
        provider(List.of(), 3000).lookup("8.8.8.8");
        assertEquals(List.of("/v3/8.8.8.8"), requests);
    }

    @Test void rateLimitedKeyIsFollowedByOneTryWithTheNextKey() throws Exception {
        keyStatus.put("key-one", 429);
        ProxyCheckProvider provider = provider(List.of("key-one", "key-two", "key-three"), 3000);
        assertEquals("Google LLC", provider.lookup("8.8.8.8").provider());
        assertEquals(List.of("/v3/8.8.8.8?key=key-one", "/v3/8.8.8.8?key=key-two"), requests);
    }

    @Test void deniedStatusInBodyAlsoMovesToTheNextKey() throws Exception {
        keyStatus.put("key-one", -1);
        ProxyCheckProvider provider = provider(List.of("key-one", "key-two"), 3000);
        assertEquals("Google LLC", provider.lookup("8.8.8.8").provider());
        assertEquals(2, requests.size());
    }

    @Test void refusedKeysAreFollowedByTheNextWorkingKey() throws Exception {
        keyStatus.put("key-one", 401);
        keyStatus.put("key-two", 403);
        ProxyCheckProvider provider = provider(List.of("key-one", "key-two", "key-three"), 3000);
        assertEquals("Google LLC", provider.lookup("8.8.8.8").provider());
        assertEquals(List.of("/v3/8.8.8.8?key=key-one", "/v3/8.8.8.8?key=key-two", "/v3/8.8.8.8?key=key-three"), requests);
    }

    @Test void allKeysRefusedIsAKeyRejection() throws Exception {
        keyStatus.put("key-one", 401);
        keyStatus.put("key-two", 403);
        ProxyCheckProvider provider = provider(List.of("key-one", "key-two"), 3000);
        assertThrows(KeyRejectedException.class, () -> provider.lookup("8.8.8.8"));
        assertThrows(KeyRejectedException.class, () -> provider.lookup("8.8.8.8"));
        assertEquals(2, requests.size(), "refused keys are not tried again for a while");
    }

    @Test void serverErrorIsNotRetried() throws Exception {
        keyStatus.put("key-one", 500);
        ProxyCheckProvider provider = provider(List.of("key-one", "key-two"), 3000);
        LookupException failure = assertThrows(LookupException.class, () -> provider.lookup("8.8.8.8"));
        assertFalse(failure instanceof KeyRejectedException);
        assertEquals(1, requests.size());
    }

    @Test void slowAnswerTimesOut() throws Exception {
        delayMillis = 2000;
        ProxyCheckProvider provider = provider(List.of(), 500);
        LookupException failure = assertThrows(LookupException.class, () -> provider.lookup("8.8.8.8"));
        assertTrue(failure.getMessage().contains("did not answer within 500 ms"), failure.getMessage());
    }

    @Test void nameIsProxycheck() throws Exception {
        assertEquals("proxycheck", provider(List.of(), 3000).name());
    }

    @Test void arrayOrOversizedBodyIsRefused() {
        assertThrows(LookupException.class, () -> ProxyCheckProvider.parse("8.8.8.8", "[]", TestSupport.NOW));
        assertThrows(LookupException.class,
                () -> ProxyCheckProvider.parse("8.8.8.8", "{\"a\":\"" + "x".repeat(300_000) + "\"}", TestSupport.NOW));
    }
}
