package io.github.origingate.core.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.origingate.core.TestServer;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    @Test void bodyThatStallsJustBeforeDeadlineStillHonorsIt() throws Exception {
        try (SlowServer server = new SlowServer(450, 3000); HttpTransport http = HttpTransport.newHttpClient()) {
            long start = System.nanoTime();
            assertThrows(HttpTimeoutException.class, () -> http.send(server.request(500), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
            long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertTrue(elapsed < 850, "A read that starts before the deadline must not extend it, took " + elapsed + " ms");
        }
    }

    @Test void streamedBodyHasNoTotalDeadline() throws Exception {
        try (SlowServer server = new SlowServer(200, 200, 200, 200); HttpTransport http = HttpTransport.newHttpClient()) {
            HttpResponse<InputStream> response = http.send(server.request(400), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                assertEquals("xxxx", new String(body.readAllBytes(), StandardCharsets.US_ASCII),
                        "Like a large download, a slow but steady stream must not be cut off by the request timeout");
            }
        }
    }

    @Test void completedRequestsReuseTheConnection() throws Exception {
        try (SlowServer server = new SlowServer(0); HttpTransport http = HttpTransport.newHttpClient()) {
            for (int i = 0; i < 2; i++) {
                assertEquals("x", http.send(server.request(2000), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body());
            }
            assertEquals(2, server.clients.size());
            assertEquals(server.clients.get(0), server.clients.get(1), "Each lookup must not need a new connection");
        }
    }

    @Test void closeDoesNotWaitForABlockedRead() throws Exception {
        try (SlowServer server = new SlowServer(0, 3000)) {
            HttpTransport http = HttpTransport.newHttpClient();
            InputStream body = http.send(server.request(5000), HttpResponse.BodyHandlers.ofInputStream()).body();
            assertEquals('x', body.read());
            Thread reader = new Thread(() -> { try { body.read(); } catch (IOException ignored) { } });
            reader.start();
            Thread.sleep(200);
            long start = System.nanoTime();
            http.close();
            long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertTrue(elapsed < 1000, "Closing must not wait for a stalled download, took " + elapsed + " ms");
            reader.join(5000);
        }
    }

    /** Sends one byte after each delay, as a chunked body, and records each request's client address. */
    private static final class SlowServer implements AutoCloseable {
        final List<InetSocketAddress> clients = new CopyOnWriteArrayList<>();
        private final ExecutorService threads = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task); thread.setDaemon(true); return thread;
        });
        private final HttpServer server;
        SlowServer(long... delays) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> answer(exchange, delays));
            server.setExecutor(threads);
            server.start();
        }
        private void answer(HttpExchange exchange, long[] delays) throws IOException {
            clients.add(exchange.getRemoteAddress());
            exchange.getRequestBody().close();
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream body = exchange.getResponseBody()) {
                for (long delay : delays) {
                    Thread.sleep(delay);
                    body.write('x');
                    body.flush();
                }
            } catch (IOException | InterruptedException ignored) {
                // The client gave up.
            }
            exchange.close();
        }
        HttpRequest request(long timeoutMillis) {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            return HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeoutMillis)).GET().build();
        }
        @Override public void close() {
            server.stop(0);
            threads.shutdownNow();
        }
    }
}
