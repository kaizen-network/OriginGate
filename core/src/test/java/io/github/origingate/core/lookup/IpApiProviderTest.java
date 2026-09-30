package io.github.origingate.core.lookup;

import io.github.origingate.core.TestServer;
import io.github.origingate.core.TestServer.Reply;
import io.github.origingate.core.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import io.github.origingate.core.net.HttpTransport;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpApiProviderTest {
    private TestServer server;

    @AfterEach void stop() {
        if (server != null) server.close();
    }

    static String read(String name) throws IOException {
        try (InputStream input = IpApiProviderTest.class.getResourceAsStream("/ip-api/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private IpApiProvider provider(String key, int status, String body) throws IOException {
        server = new TestServer(request -> Reply.text(status, body));
        return new IpApiProvider(HttpTransport.newHttpClient(), server.uri("/json/"), key, Duration.ofSeconds(3),
                Clock.fixed(TestSupport.NOW, ZoneOffset.UTC), "OriginGate/test");
    }

    @Test void parsesRealResponse() throws Exception {
        IpInfo info = IpApiProvider.parse("8.8.8.8", read("8.8.8.8.json"), TestSupport.NOW);
        assertEquals("US", info.countryCode());
        assertEquals("United States", info.country());
        assertEquals("Virginia", info.region());
        assertEquals("Ashburn", info.city());
        assertEquals("Google LLC", info.provider());
        assertEquals("Google Public DNS", info.organisation());
        assertEquals("AS15169", info.asn());
        assertTrue(info.vpn(), "proxy: true counts as a VPN");
        assertFalse(info.proxy());
    }

    @Test void failStatusIsAFailure() {
        LookupException failure = assertThrows(LookupException.class,
                () -> IpApiProvider.parse("10.0.0.1", read("private-range.json"), TestSupport.NOW));
        assertFalse(failure instanceof KeyRejectedException);
        assertTrue(failure.getMessage().contains("private range"), failure.getMessage());
    }

    @Test void freeRequestHasNoKey() throws Exception {
        IpApiProvider provider = provider("", 200, read("8.8.8.8.json"));
        assertEquals("ip-api", provider.name());
        provider.lookup("8.8.8.8", TestSupport.NO_DEADLINE);
        assertEquals("/json/8.8.8.8?fields=" + IpApiProvider.FIELDS, server.requests.get(0).uri().toString());
    }

    @Test void proRequestSendsTheKeyAndKeepsIpv6() throws Exception {
        provider("pro-key", 200, read("8.8.8.8.json")).lookup("2001:db8::1", TestSupport.NO_DEADLINE);
        URI uri = server.requests.get(0).uri();
        assertEquals("/json/2001:db8::1", uri.getPath());
        assertEquals("fields=" + IpApiProvider.FIELDS + "&key=pro-key", uri.getRawQuery());
    }

    @Test void refusedKeyIsAKeyRejection() throws Exception {
        IpApiProvider provider = provider("bad-key", 403,
                "{\"status\":\"fail\",\"message\":\"invalid/expired key, renew at https://members.ip-api.com/order\"}");
        KeyRejectedException refused = assertThrows(KeyRejectedException.class, () -> provider.lookup("8.8.8.8", TestSupport.NO_DEADLINE));
        assertTrue(refused.getMessage().contains("invalid/expired key"), refused.getMessage());
    }

    @Test void rateLimitIsAKeyRejection() throws Exception {
        IpApiProvider provider = provider("", 429, "");
        assertThrows(KeyRejectedException.class, () -> provider.lookup("8.8.8.8", TestSupport.NO_DEADLINE));
    }

    @Test void endpointsMatchTheDocs() {
        assertEquals("http://ip-api.com/json/", IpApiProvider.FREE_URL.toString());
        assertEquals("https://pro.ip-api.com/json/", IpApiProvider.PRO_URL.toString());
    }
}
