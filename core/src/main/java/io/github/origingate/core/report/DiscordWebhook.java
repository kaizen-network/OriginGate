package io.github.origingate.core.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.origingate.core.Log;
import io.github.origingate.core.Text;
import io.github.origingate.core.lookup.IpInfo;
import io.github.origingate.core.net.Addresses;
import io.github.origingate.core.net.HttpRequest;
import io.github.origingate.core.net.HttpResponse;
import io.github.origingate.core.net.HttpTransport;
import io.github.origingate.core.rules.Decision;
import io.github.origingate.core.rules.LoginAttempt;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Posts kicks, bypasses, and lookup alerts to a Discord webhook. Callers only queue a line. A daemon thread posts the
 * queue every 5 seconds as one message, so a flood of kicks becomes a short summary. A failed post never reaches a
 * login: it is caught, logged once without the URL (a secret), and its lines are dropped.
 */
public final class DiscordWebhook implements AutoCloseable {
    private static final int INTERVAL_SECONDS = 5;
    private static final int SHOWN_LINES = 10;
    private static final int MAX_QUEUED = 10_000;
    /** 10 lines of at most this many characters, plus the "And n more." line, stay under Discord's 2000. */
    private static final int MAX_LINE = 190;
    private static final String SPECIAL = "\\*_~`|>[]()#";
    /** A run of address characters with an optional /prefix; each hit is confirmed with {@link Addresses#parse}. */
    private static final Pattern ADDRESS = Pattern.compile("(?<![0-9A-Za-z:.])[0-9A-Fa-f:.]{2,45}(?:/[0-9]{1,3})?(?![0-9A-Za-z:])");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final URI url;
    private final boolean showIp;
    private final HttpTransport http;
    private final Log log;
    private final String userAgent;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "OriginGate Discord");
        thread.setDaemon(true);
        return thread;
    });
    /** Guards {@link #queue}, {@link #overflow}, and {@link #closed}. */
    private final Object lock = new Object();
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private int overflow;
    private boolean closed;
    /** One post at a time. Also guards {@link #failing}. */
    private final Object sending = new Object();
    private boolean failing;

    public DiscordWebhook(URI url, boolean showIp, HttpTransport http, Log log, String userAgent) {
        this.url = url;
        this.showIp = showIp;
        this.http = http;
        this.log = log;
        this.userAgent = userAgent;
        timer.scheduleWithFixedDelay(this::flushQuietly, INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** Queues a kick or a bypass. Dry-run decisions and allowed logins are not posted. */
    public void decision(LoginAttempt attempt, Decision decision) {
        try {
            if (decision.rule() == null || decision.dryRun()) return;
            String start;
            if (decision.kicks()) start = "Kicked **" + escape(attempt.username()) + "** by the " + decision.rule().id() + " rule";
            else if (decision.outcome() == Decision.Outcome.BYPASS) start = "Let in **" + escape(attempt.username()) + "** with a " + decision.rule().id() + " bypass";
            else return;
            StringBuilder line = new StringBuilder(start);
            if (decision.note() != null) line.append(": ").append(text(decision.note()));
            if (decision.lookup() != null) {
                IpInfo info = decision.lookup().info();
                line.append(" (").append(escape(Text.dash(info.provider()))).append(" / ")
                        .append(escape(Text.dash(info.displayOrganisation()))).append(", ")
                        .append(escape(Text.dash(info.country()))).append(')');
            }
            if (showIp) line.append(" IP ").append(Addresses.text(attempt.address()));
            if (decision.caution() != null) line.append(" Caution: ").append(text(decision.caution())).append('.');
            enqueue(line.toString());
        } catch (RuntimeException ex) {
            // A webhook problem must never reach a login.
        }
    }

    /** Queues a lookup problem or its recovery, the same messages that staff see in chat. */
    public void alert(boolean recovered, String message) {
        try {
            enqueue((recovered ? "Recovered: " : "Alert: ") + text(message));
        } catch (RuntimeException ex) {
            // A webhook problem must never reach a login.
        }
    }

    /** Free text for a post: IP addresses become "IP hidden" unless show-ip is on, then Discord's markdown is escaped. */
    private String text(String free) {
        return escape(showIp ? free : hideAddresses(free));
    }

    static String hideAddresses(String text) {
        Matcher matcher = ADDRESS.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String found = matcher.group();
            int slash = found.indexOf('/');
            String address = slash < 0 ? found : found.substring(0, slash);
            boolean hit = Addresses.parse(address).isPresent();
            String dots = "";
            if (!hit && slash < 0 && address.endsWith(".")) {
                // A full stop that ends a sentence.
                String bare = address.substring(0, address.length() - 1);
                hit = Addresses.parse(bare).isPresent();
                dots = ".";
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(hit ? "IP hidden" + dots : found));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private void enqueue(String line) {
        String cut = line.length() <= MAX_LINE ? line : line.substring(0, safeCut(line, MAX_LINE - 3)) + "...";
        synchronized (lock) {
            if (closed) return;
            if (queue.size() >= MAX_QUEUED) overflow++;
            else queue.add(cut);
        }
    }

    private static int safeCut(String text, int index) {
        return index > 0 && Character.isHighSurrogate(text.charAt(index - 1)) ? index - 1 : index;
    }

    /** Backslash-escapes Discord's markdown characters and replaces line breaks and other control characters. */
    static String escape(String text) {
        StringBuilder result = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c)) result.append(' ');
            else {
                if (SPECIAL.indexOf(c) >= 0) result.append('\\');
                result.append(c);
            }
        }
        return result.toString();
    }

    private void flushQuietly() {
        try {
            flush(Duration.ofSeconds(10));
        } catch (RuntimeException ex) {
            // The timer thread must survive anything.
        }
    }

    /** Posts everything queued as one message. Lines beyond the first 10 are only counted. */
    void flush() {
        flush(Duration.ofSeconds(10));
    }

    private void flush(Duration timeout) {
        synchronized (sending) {
            List<String> lines = new ArrayList<>();
            int more;
            synchronized (lock) {
                more = overflow;
                overflow = 0;
                while (!queue.isEmpty()) {
                    String line = queue.poll();
                    if (lines.size() < SHOWN_LINES) lines.add(line);
                    else more++;
                }
            }
            if (lines.isEmpty()) return;
            if (more > 0) lines.add("And " + more + " more.");
            post(String.join("\n", lines), timeout);
        }
    }

    private void post(String content, Duration timeout) {
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        JsonObject body = new JsonObject();
        body.addProperty("username", "OriginGate");
        body.addProperty("content", content);
        body.add("allowed_mentions", mentions);
        // 4 = suppress embeds, so a URL in a note or organisation gets no link preview.
        body.addProperty("flags", 4);
        String problem = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(url).timeout(timeout)
                    .header("Content-Type", "application/json").header("User-Agent", userAgent)
                    .POST(GSON.toJson(body).getBytes(StandardCharsets.UTF_8)).build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status < 200 || status > 299) problem = "Discord answered with status " + status;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            problem = "the post was interrupted";
        } catch (Exception ex) {
            // Only the exception type: some messages carry the host or the full address.
            problem = ex.getClass().getSimpleName();
        }
        if (problem != null) {
            if (!failing) log.warn("Could not post to the Discord webhook: " + problem + ". Later failures are not logged until a post works.", null);
            failing = true;
        } else if (failing) {
            failing = false;
            log.info("The Discord webhook works again.");
        }
    }

    /** Posts what is queued once, then stops the thread. Call before closing the HTTP transport. */
    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
        }
        timer.shutdown();
        try {
            timer.awaitTermination(15, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        try {
            flush(Duration.ofSeconds(5));
        } catch (RuntimeException ex) {
            // Closing must finish.
        }
    }
}
