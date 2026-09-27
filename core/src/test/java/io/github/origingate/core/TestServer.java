package io.github.origingate.core;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** A local HTTP server for provider tests. Records each request and answers with the handler's reply. */
public final class TestServer implements AutoCloseable {
    public record Request(String method, URI uri, Headers headers) { }

    public record Reply(int status, byte[] body, Map<String, String> headers) {
        public static Reply text(int status, String body) {
            return new Reply(status, body.getBytes(StandardCharsets.UTF_8), Map.of());
        }
    }

    public final List<Request> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    public TestServer(Function<Request, Reply> handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> answer(exchange, handler));
        server.start();
    }

    private void answer(HttpExchange exchange, Function<Request, Reply> handler) throws IOException {
        Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRequestHeaders());
        requests.add(request);
        Reply reply = handler.apply(request);
        reply.headers().forEach(exchange.getResponseHeaders()::add);
        boolean empty = request.method().equals("HEAD") || reply.body().length == 0;
        exchange.sendResponseHeaders(reply.status(), empty ? -1 : reply.body().length);
        if (!empty) {
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(reply.body());
            } catch (IOException ignored) {
                // The client gave up (timeout tests).
            }
        }
        exchange.close();
    }

    public URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    @Override public void close() { server.stop(0); }
}
