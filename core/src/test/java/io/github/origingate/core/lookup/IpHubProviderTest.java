package io.github.origingate.core.lookup;

import io.github.origingate.core.Log;
import io.github.origingate.core.TestServer;
import io.github.origingate.core.TestServer.Reply;
import io.github.origingate.core.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpHubProviderTest {
    private TestServer server;

    @AfterEach void stop() {
        if (server != null) server.close();
    }

    static String read(String name) throws IOException {
        try (InputStream input = IpHubProviderTest.class.getResourceAsStream("/iphub/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Answers the hosting fixture, or the status set for the key in the X-Key header. */
    private IpHubProvider provider(List<String> keys, Map<String, Integer> keyStatus) throws IOException {
        String body = read("hosting-8.8.8.8.json");
        server = new TestServer(request -> {
            String key = request.headers().getFirst("X-Key");
            int status = key == null ? 200 : keyStatus.getOrDefault(key, 200);
            return Reply.text(status, status == 200 ? body : "{}");
        });
        return new IpHubProvider(HttpClient.newHttpClient(), server.uri("/ip/"), keys, Duration.ofSeconds(3),
                Clock.fixed(TestSupport.NOW, ZoneOffset.UTC), Log.NONE, "OriginGate/test");
    }

    @Test void blockOneIsAVpn() throws Exception {
        IpInfo info = IpHubProvider.parse("8.8.8.8", read("hosting-8.8.8.8.json"), TestSupport.NOW);
        assertEquals("US", info.countryCode());
        assertEquals("United States", info.country());
        assertEquals("GOOGLE", info.provider());
        assertEquals("AS15169", info.asn());
        assertTrue(info.vpn());
        assertFalse(info.proxy());
        assertEquals(TestSupport.NOW, info.checkedAt());
    }

    @Test void blockZeroAndTwoAreNotVpns() throws Exception {
        String residential = read("residential-192.0.2.10.json");
        assertFalse(IpHubProvider.parse("192.0.2.10", residential, TestSupport.NOW).vpn());
        assertFalse(IpHubProvider.parse("192.0.2.10", residential.replace("\"block\":0", "\"block\":2"), TestSupport.NOW).vpn());
    }

    @Test void oddResponsesFail() {
        assertThrows(LookupException.class, () -> IpHubProvider.parse("8.8.8.8", "not json", TestSupport.NOW));
        assertThrows(LookupException.class, () -> IpHubProvider.parse("8.8.8.8", "[]", TestSupport.NOW));
        assertThrows(LookupException.class, () -> IpHubProvider.parse("8.8.8.8", "{\"ip\":\"8.8.8.8\"}", TestSupport.NOW));
        assertThrows(LookupException.class,
                () -> IpHubProvider.parse("8.8.8.8", "{\"a\":\"" + "x".repeat(300_000) + "\"}", TestSupport.NOW));
    }

    @Test void sendsKeyInHeaderAndRotatesKeys() throws Exception {
        IpHubProvider provider = provider(List.of("key-one", "key-two"), Map.of());
        assertEquals("iphub", provider.name());
        provider.lookup("8.8.8.8");
        provider.lookup("8.8.8.8");
        assertEquals("/ip/8.8.8.8", server.requests.get(0).uri().toString());
        assertEquals(List.of("key-one", "key-two"),
                server.requests.stream().map(request -> request.headers().getFirst("X-Key")).toList());
    }

    @Test void ipv6AddressReachesTheServerUnchanged() throws Exception {
        provider(List.of("key-one"), Map.of()).lookup("2001:db8::1");
        assertEquals("/ip/2001:db8::1", server.requests.get(0).uri().getPath());
    }

    @Test void rateLimitedKeyIsFollowedByTheNextKey() throws Exception {
        IpHubProvider provider = provider(List.of("key-one", "key-two"), Map.of("key-one", 429));
        assertTrue(provider.lookup("8.8.8.8").vpn());
        assertEquals(2, server.requests.size());
    }

    @Test void allKeysRefusedIsAKeyRejection() throws Exception {
        IpHubProvider provider = provider(List.of("key-one", "key-two"), Map.of("key-one", 403, "key-two", 401));
        assertThrows(KeyRejectedException.class, () -> provider.lookup("8.8.8.8"));
    }

    @Test void serverErrorIsAPlainFailure() throws Exception {
        IpHubProvider provider = provider(List.of("key-one"), Map.of("key-one", 500));
        LookupException failure = assertThrows(LookupException.class, () -> provider.lookup("8.8.8.8"));
        assertFalse(failure instanceof KeyRejectedException);
    }
}
