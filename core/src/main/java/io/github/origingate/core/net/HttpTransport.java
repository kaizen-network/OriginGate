package io.github.origingate.core.net;

import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Blocking I/O for worker threads, with total deadlines and no automatic redirects. */
public final class HttpTransport implements AutoCloseable {
    public enum Redirect { NEVER }
    private final int connectMillis;
    private final Set<HttpURLConnection> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "OriginGate HTTP Deadline"); thread.setDaemon(true); return thread;
    });
    private HttpTransport(Duration connectTimeout) {
        connectMillis = (int) Math.min(Integer.MAX_VALUE, Math.max(1, connectTimeout.toMillis()));
        timer.setRemoveOnCancelPolicy(true);
        timer.setKeepAliveTime(1, TimeUnit.SECONDS);
        timer.allowCoreThreadTimeOut(true);
    }
    public static HttpTransport newHttpClient() { return newBuilder().build(); }
    public static Builder newBuilder() { return new Builder(); }
    public static final class Builder {
        private Duration timeout = Duration.ofSeconds(10);
        public Builder connectTimeout(Duration value) { timeout = value; return this; }
        public Builder followRedirects(Redirect ignored) { return this; }
        public HttpTransport build() { return new HttpTransport(timeout); }
    }
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (closed.get()) throw new IOException("HTTP transport is closed");
        final HttpURLConnection connection = (HttpURLConnection) request.uri.toURL().openConnection();
        final int wait = (int) Math.min(Integer.MAX_VALUE, Math.max(1, request.timeout.toMillis()));
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(wait);
        connection.setConnectTimeout(Math.min(connectMillis, wait));
        connection.setReadTimeout(wait);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(request.method);
        request.headers.forEach(connection::setRequestProperty);
        active.add(connection);
        final ScheduledFuture<?> cancellation;
        try {
            cancellation = timer.schedule(connection::disconnect, wait, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ex) {
            active.remove(connection); connection.disconnect(); throw new IOException("HTTP transport is closed", ex);
        }
        boolean transferred = false;
        try {
            int status = connection.getResponseCode();
            InputStream raw = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (raw == null) raw = new ByteArrayInputStream(new byte[0]);
            InputStream stream = new FilterInputStream(raw) {
                private boolean finished;
                private void remaining() throws IOException {
                    if (System.nanoTime() >= deadline) throw new HttpTimeoutException("HTTP request timed out");
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("HTTP request interrupted");
                    long millis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    connection.setReadTimeout((int) Math.max(1, Math.min(wait, millis)));
                }
                @Override public int read() throws IOException { remaining(); return super.read(); }
                @Override public int read(byte[] bytes, int off, int len) throws IOException { remaining(); return in.read(bytes, off, len); }
                @Override public void close() throws IOException {
                    if (finished) return;
                    finished = true;
                    cancellation.cancel(false);
                    active.remove(connection);
                    try { super.close(); } finally { connection.disconnect(); }
                }
            };
            T body;
            try { body = handler.read(stream); } catch (IOException | RuntimeException ex) { stream.close(); throw ex; }
            transferred = body instanceof InputStream;
            return new HttpResponse<>(status, body, connection.getHeaderFields());
        } catch (SocketTimeoutException ex) {
            throw new HttpTimeoutException("HTTP request timed out");
        } catch (IOException ex) {
            if (System.nanoTime() >= deadline) throw new HttpTimeoutException("HTTP request timed out");
            throw ex;
        } finally {
            if (!transferred) {
                cancellation.cancel(false); active.remove(connection); connection.disconnect();
            }
        }
    }
    public void shutdown() { close(); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        timer.shutdownNow();
        for (HttpURLConnection connection : active) connection.disconnect();
        active.clear();
    }
}
