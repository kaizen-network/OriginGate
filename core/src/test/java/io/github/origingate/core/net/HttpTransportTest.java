package io.github.origingate.core.net;

import io.github.origingate.core.TestServer;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HttpTransportTest {
    @Test void returnsErrorBodyAndDoesNotFollowRedirects() throws Exception {
        try (TestServer server = new TestServer(r -> new TestServer.Reply(302, "redirect".getBytes(), Map.of("Location", "/other")));
             HttpTransport http = HttpTransport.newHttpClient()) {
            HttpResponse<String> result = http.send(HttpRequest.newBuilder(server.uri("/")).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(302, result.statusCode());
            assertEquals("redirect", result.body());
            assertEquals(1, server.requests.size());
        }
    }

    @Test void stalledResponseHonorsDeadline() throws Exception {
        try (TestServer server = new TestServer(r -> {
            try { Thread.sleep(450); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return TestServer.Reply.text(200, "late");
        }); HttpTransport http = HttpTransport.newHttpClient()) {
            assertThrows(IOException.class, () -> http.send(HttpRequest.newBuilder(server.uri("/")).timeout(Duration.ofMillis(50)).GET().build(), HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }
}
