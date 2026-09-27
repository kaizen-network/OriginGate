package io.github.origingate.core.lookup;

import io.github.origingate.core.TestServer;
import io.github.origingate.core.TestServer.Reply;
import io.github.origingate.core.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IpInfoProviderTest {
    private TestServer server;

    @AfterEach void stop() {
        if (server != null) server.close();
    }

    static String read(String name) throws IOException {
        try (InputStream input = IpInfoProviderTest.class.getResourceAsStream("/ipinfo/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private IpInfoProvider provider(int status, String body) throws IOException {
        server = new TestServer(request -> Reply.text(status, body));
        return new IpInfoProvider(HttpClient.newHttpClient(), server.uri("/lite/"), "test-token", Duration.ofSeconds(3),
                Clock.fixed(TestSupport.NOW, ZoneOffset.UTC), "OriginGate/test");
    }

    @Test void parsesLiteResponse() throws Exception {
        IpInfo info = IpInfoProvider.parse("8.8.8.8", read("8.8.8.8.json"), TestSupport.NOW);
        assertEquals("US", info.countryCode());
        assertEquals("United States", info.country());
        assertEquals("AS15169", info.asn());
        assertEquals("Google LLC", info.provider());
        assertFalse(info.vpn());
    }

    @Test void bogonHasNoCountry() throws Exception {
        assertNull(IpInfoProvider.parse("10.0.0.1", read("bogon-10.0.0.1.json"), TestSupport.NOW).countryCode());
    }

    @Test void sendsTokenAndKeepsIpv6() throws Exception {
        IpInfoProvider provider = provider(200, read("8.8.8.8.json"));
        assertEquals("ipinfo", provider.name());
        provider.lookup("2001:db8::1");
        URI uri = server.requests.get(0).uri();
        assertEquals("/lite/2001:db8::1", uri.getPath());
        assertEquals("token=test-token", uri.getRawQuery());
    }

    @Test void refusedTokenAndRateLimitAreKeyRejections() throws Exception {
        IpInfoProvider refused = provider(403, "{}");
        assertThrows(KeyRejectedException.class, () -> refused.lookup("8.8.8.8"));
        server.close();
        IpInfoProvider limited = provider(429, "{}");
        assertThrows(KeyRejectedException.class, () -> limited.lookup("8.8.8.8"));
    }

    @Test void endpointMatchesTheDocs() {
        assertEquals("https://api.ipinfo.io/lite/", IpInfoProvider.BASE_URL.toString());
    }
}
