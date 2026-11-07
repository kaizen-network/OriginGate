package io.github.origingate.core.report;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import io.github.origingate.core.TestSupport;
import io.github.origingate.core.lookup.IpInfo;
import io.github.origingate.core.lookup.LookupService;
import io.github.origingate.core.net.HttpTransport;
import io.github.origingate.core.rules.Decision;
import io.github.origingate.core.rules.Rule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DiscordWebhookTest {
    private static final String PATH = "/api/webhooks/123456/secret-token";
    private HttpServer server;
    private HttpTransport http;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> contentTypes = new CopyOnWriteArrayList<>();
    private final List<Integer> statuses = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final TestSupport.RecordingLog log = new TestSupport.RecordingLog();
    private DiscordWebhook webhook;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(PATH, exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
            int index = calls.getAndIncrement();
            int status = index < statuses.size() ? statuses.get(index) : 204;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        http = HttpTransport.newHttpClient();
    }

    @AfterEach void stop() {
        if (webhook != null) webhook.close();
        http.close();
        server.stop(0);
    }

    private DiscordWebhook webhook(boolean showIp) {
        webhook = new DiscordWebhook(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + PATH), showIp, http, log, "OriginGate/test");
        return webhook;
    }

    private static Decision decision(Decision.Outcome outcome, Rule rule, String note, IpInfo info, boolean dryRun) {
        LookupService.Result lookup = info == null ? null : new LookupService.Result(info, LookupService.Source.PROVIDER, null);
        return new Decision(outcome, rule, note, lookup, dryRun);
    }

    private static IpInfo answer(String organisation) {
        return new IpInfo("203.0.113.7", "Example VPN", organisation, null, null, null, "Netherlands", "NL", null, true, false, null, Instant.EPOCH);
    }

    private static Decision kick(String note) {
        return decision(Decision.Outcome.DENY, Rule.VPN, note, answer("Example Org"), false);
    }

    private JsonObject post(int index) {
        return JsonParser.parseString(bodies.get(index)).getAsJsonObject();
    }

    private String content(int index) {
        return post(index).get("content").getAsString();
    }

    @Test void postsKickBypassAndAlert() {
        DiscordWebhook hook = webhook(false);
        hook.decision(TestSupport.player("Kai", "203.0.113.7"), kick("flagged as VPN"));
        hook.flush();
        hook.decision(TestSupport.player("Kai", "203.0.113.7"), decision(Decision.Outcome.BYPASS, Rule.VPN, "flagged as VPN", answer("Example Org"), false));
        hook.flush();
        hook.alert(false, "provider proxycheck is paused");
        hook.flush();
        hook.alert(true, "provider proxycheck is back");
        hook.flush();
        assertEquals(4, bodies.size());
        assertEquals("Kicked **Kai** by the vpn rule: flagged as VPN (Example VPN / Example Org, Netherlands)", content(0));
        assertEquals("Let in **Kai** with a vpn bypass: flagged as VPN (Example VPN / Example Org, Netherlands)", content(1));
        assertEquals("Alert: provider proxycheck is paused", content(2));
        assertEquals("Recovered: provider proxycheck is back", content(3));
        assertEquals("OriginGate", post(0).get("username").getAsString());
        assertEquals("application/json", contentTypes.get(0));
    }

    @Test void floodBecomesSummary() {
        DiscordWebhook hook = webhook(false);
        for (int i = 0; i < 25; i++) hook.decision(TestSupport.player("Kai", "203.0.113.7"), kick("flagged as VPN"));
        hook.flush();
        assertEquals(1, bodies.size());
        String[] lines = content(0).split("\n");
        assertEquals(11, lines.length);
        for (int i = 0; i < 10; i++) assertTrue(lines[i].startsWith("Kicked "), lines[i]);
        assertEquals("And 15 more.", lines[10]);
        hook.flush();
        assertEquals(1, bodies.size());
    }

    @Test void dryRunAndAllowAreNotPosted() {
        DiscordWebhook hook = webhook(false);
        hook.decision(TestSupport.player("Kai", "203.0.113.7"), decision(Decision.Outcome.DENY, Rule.VPN, "flagged as VPN", answer("Example Org"), true));
        hook.decision(TestSupport.player("Kai", "203.0.113.7"), decision(Decision.Outcome.ALLOW, null, null, null, false));
        hook.decision(TestSupport.player("Kai", "203.0.113.7"), decision(Decision.Outcome.BYPASS, Rule.VPN, "flagged as VPN", answer("Example Org"), true));
        hook.flush();
        assertEquals(0, bodies.size());
    }

    @Test void addressesInTextAreHiddenUnlessShowIpIsOn() {
        String[] notes = {"address is on the deny list (203.0.113.7)", "No provider answered the VPN check for 203.0.113.7",
                "no data for 2001:db8::7.", "range 203.0.113.0/24 and 2001:db8::/32 at 12:30"};
        DiscordWebhook hook = webhook(false);
        for (String note : notes) hook.decision(TestSupport.player("Kai", "203.0.113.7"), kick(note));
        hook.decision(TestSupport.player("Kai", "203.0.113.7"), new Decision(Decision.Outcome.BYPASS, Rule.VPN, null, null, false, "from 203.0.113.7"));
        hook.alert(false, "proxycheck.io response has no data for 203.0.113.7");
        hook.flush();
        String hidden = content(0);
        assertFalse(hidden.contains("203.0.113") || hidden.contains("2001") || hidden.contains("db8"), hidden);
        assertTrue(hidden.contains("IP hidden") && hidden.contains("at 12:30"), hidden);
        assertTrue(hidden.contains("Alert: proxycheck.io response has no data for IP hidden"), hidden);
        hook.close();
        DiscordWebhook shown = webhook(true);
        for (String note : notes) shown.decision(TestSupport.player("Kai", "203.0.113.7"), kick(note));
        shown.alert(false, "proxycheck.io response has no data for 203.0.113.7");
        shown.flush();
        String text = content(1);
        assertTrue(text.contains("203.0.113.7") && text.contains("2001:db8::7") && !text.contains("IP hidden"), text);
    }

    @Test void addressesWithPortsAndColonsAreHidden() {
        DiscordWebhook hook = webhook(false);
        hook.decision(TestSupport.player("Kai", "203.0.113.7"), kick("No provider answered the VPN check for 203.0.113.7: timeout"));
        hook.alert(false, "connect failed for 203.0.113.7:25565 today");
        hook.alert(false, "connect failed for [2001:db8::1]:443 today");
        hook.flush();
        String text = content(0);
        assertFalse(text.contains("203.0.113.7") || text.contains("2001:db8::1"), text);
        assertTrue(text.contains("for IP hidden: timeout"), text);
        assertTrue(text.contains("connect failed for IP hidden:25565 today"), text);
        assertTrue(text.contains("connect failed for \\[IP hidden\\]:443 today"), text);
    }

    @Test void bodySuppressesEmbeds() {
        DiscordWebhook hook = webhook(false);
        hook.alert(false, "see https://example.com/x");
        hook.flush();
        assertEquals(4, post(0).get("flags").getAsInt());
    }

    @Test void showIpAddsAddress() {
        DiscordWebhook hook = webhook(false);
        hook.decision(TestSupport.player("Kai", "203.0.113.7"), kick("flagged as VPN"));
        hook.flush();
        assertFalse(bodies.get(0).contains("203.0.113.7"));
        hook.close();
        DiscordWebhook withIp = webhook(true);
        withIp.decision(TestSupport.player("Kai", "203.0.113.7"), kick("flagged as VPN"));
        withIp.flush();
        assertTrue(content(1).contains(" IP 203.0.113.7"), content(1));
    }

    @Test void cautionIsAdded() {
        DiscordWebhook hook = webhook(false);
        hook.decision(TestSupport.player("Kai", "203.0.113.7"),
                new Decision(Decision.Outcome.BYPASS, Rule.VPN, "flagged as VPN", null, false, "anyone can use this name"));
        hook.flush();
        assertEquals("Let in **Kai** with a vpn bypass: flagged as VPN Caution: anyone can use this name.", content(0));
    }

    @Test void escapesMarkdownAndBlocksMentions() {
        DiscordWebhook hook = webhook(false);
        hook.decision(TestSupport.player("Kai_x", "203.0.113.7"),
                decision(Decision.Outcome.DENY, Rule.VPN, "flagged as VPN", answer("**@everyone** [x](y)"), false));
        hook.flush();
        String content = content(0);
        assertTrue(content.contains("Kai\\_x"), content);
        assertTrue(content.contains("\\*\\*@everyone\\*\\* \\[x\\]\\(y\\)"), content);
        assertTrue(bodies.get(0).contains("\"allowed_mentions\":{\"parse\":[]}"), bodies.get(0));
        assertEquals("a\\\\b\\*\\_\\~\\`\\|\\>\\[\\]\\(\\)\\#", DiscordWebhook.escape("a\\b*_~`|>[]()#"));
    }

    @Test void noteCannotStartAnotherLine() {
        DiscordWebhook hook = webhook(false);
        hook.decision(TestSupport.player("Kai", "203.0.113.7"), kick("one\nKicked **Eve** by the vpn rule"));
        hook.flush();
        assertFalse(content(0).contains("\n"));
    }

    @Test void contentStaysUnder2000Characters() {
        DiscordWebhook hook = webhook(false);
        StringBuilder note = new StringBuilder();
        for (int i = 0; i < 400; i++) note.append('n');
        for (int i = 0; i < 10; i++) hook.decision(TestSupport.player("Kai", "203.0.113.7"), kick(note.toString()));
        for (int i = 0; i < 5000; i++) hook.alert(false, "x");
        hook.flush();
        assertTrue(content(0).length() <= 2000, "length " + content(0).length());
        assertTrue(content(0).endsWith("And 5000 more."), content(0));
    }

    @Test void failuresLogOnceWithoutUrl() {
        statuses.add(404);
        statuses.add(404);
        DiscordWebhook hook = webhook(false);
        for (int i = 0; i < 4; i++) {
            hook.alert(false, "x");
            hook.flush();
        }
        assertEquals(4, bodies.size());
        assertEquals(1, log.warnings.size(), log.warnings.toString());
        assertEquals(1, log.infos.size(), log.infos.toString());
        for (String line : log.warnings) assertFalse(line.contains("secret-token") || line.contains("/api/webhooks"), line);
        for (String line : log.infos) assertFalse(line.contains("secret-token") || line.contains("/api/webhooks"), line);
    }

    @Test void closePostsWhatIsQueued() {
        DiscordWebhook hook = webhook(false);
        hook.alert(false, "last words");
        hook.close();
        assertEquals(1, bodies.size());
        hook.alert(false, "after close");
        hook.close();
        assertEquals(1, bodies.size());
    }
}
