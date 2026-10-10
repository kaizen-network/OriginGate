package io.github.origingate.bungee;

import io.github.origingate.core.OriginGateRuntime;
import io.github.origingate.core.RuntimeController;
import io.github.origingate.core.config.ConfigLoader;
import io.github.origingate.core.config.Messages;
import io.github.origingate.core.rules.Decision;
import io.github.origingate.core.rules.Gate;
import io.github.origingate.core.rules.LoginAttempt;
import io.github.origingate.core.rules.Rule;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConnectionRoutingTest {
    @TempDir Path directory;
    private OriginGateBungee plugin;
    private final RuntimeController controller = mock(RuntimeController.class);
    private final OriginGateRuntime runtime = mock(OriginGateRuntime.class);
    private final Gate gate = mock(Gate.class);
    private final ProxiedPlayer player = mock(ProxiedPlayer.class);
    private final ServerInfo lobby = mock(ServerInfo.class);
    private final ServerInfo intended = mock(ServerInfo.class);
    private final CompletableFuture<Decision> decision = new CompletableFuture<>();
    private final AtomicBoolean connected = new AtomicBoolean(true);
    private final AtomicInteger releases = new AtomicInteger();
    private final List<ServerInfo> backendAttempts = new ArrayList<>();
    private final Map<ProxiedPlayer, LoginHold> holds = new ConcurrentHashMap<>();
    private final Set<ProxiedPlayer> admitted = ConcurrentHashMap.newKeySet();
    private final Map<ProxiedPlayer, ServerInfo> routes = new ConcurrentHashMap<>();
    private final Map<ProxiedPlayer, String> notices = new ConcurrentHashMap<>();

    @BeforeEach void setUp() throws Exception {
        // Plugin's constructor requires Bungee's PluginClassloader. Only bypass construction:
        // event handlers, LoginHold, API events, and all connection state remain real.
        plugin = mock(OriginGateBungee.class, CALLS_REAL_METHODS);
        field("controller", controller);
        field("holds", holds);
        field("admitted", admitted);
        field("deferredRoutes", routes);
        field("notices", notices);
        OriginGateRuntime.installDefaults(directory);
        Path config = directory.resolve("config.yml");
        Files.writeString(config, Files.readString(config).replace("[\"origingate.alerts\"]", "[]"));
        when(runtime.config()).thenReturn(ConfigLoader.load(directory));
        when(runtime.messages()).thenReturn(Messages.load(directory));
        when(runtime.gate()).thenReturn(gate);
        when(controller.runtime()).thenReturn(runtime);
        when(gate.check(any(LoginAttempt.class))).thenReturn(decision);
        when(player.getName()).thenReturn("RoutingTest");
        when(player.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        when(player.getAddress()).thenReturn(new InetSocketAddress("203.0.113.7", 25565));
        when(player.isConnected()).thenAnswer(call -> connected.get());
        doAnswer(call -> { connected.set(false); return null; }).when(player).disconnect(any(BaseComponent[].class));
    }

    @Test void deniedPlayerCannotReachBackendBeforeOrAfterDecision() {
        login();
        assertTrue(connect(intended).isCancelled());
        assertEquals(0, releases.get());
        assertTrue(backendAttempts.isEmpty());

        decision.complete(result(Decision.Outcome.DENY));

        verify(player).disconnect(any(BaseComponent[].class));
        assertFalse(admitted.contains(player));
        assertEquals(1, releases.get());
        assertTrue(connect(lobby).isCancelled());
        assertTrue(backendAttempts.isEmpty());
        disconnect();
        assertNoState();
    }

    @ParameterizedTest
    @EnumSource(value = Decision.Outcome.class, names = {"ALLOW", "BYPASS"})
    void approvedPlayerResumesAtIntendedDestinationExactlyOnce(Decision.Outcome outcome) {
        login();
        assertTrue(connect(intended).isCancelled());
        assertTrue(backendAttempts.isEmpty());
        assertEquals(0, releases.get());

        decision.complete(result(outcome));

        assertEquals(1, releases.get());
        assertEquals(List.of(intended), backendAttempts);
        assertTrue(holds.isEmpty());
        assertTrue(routes.isEmpty());
        assertSame(lobby, connect(lobby).getTarget(), "A consumed deferred route must not override a later request");
        verify(player, never()).disconnect(any(BaseComponent[].class));
    }

    @Test void latestPendingDestinationWins() {
        login();
        assertTrue(connect(lobby).isCancelled());
        assertTrue(connect(intended).isCancelled());
        decision.complete(result(Decision.Outcome.ALLOW));
        assertEquals(List.of(intended), backendAttempts);
    }

    @Test void alreadyCancelledEventDoesNotReplaceDeferredDestination() {
        login();
        assertTrue(connect(intended).isCancelled());
        ServerConnectEvent cancelled = event(lobby);
        cancelled.setCancelled(true);
        plugin.connecting(cancelled);
        assertTrue(cancelled.isCancelled());
        decision.complete(result(Decision.Outcome.ALLOW));
        assertEquals(List.of(intended), backendAttempts);
    }

    @Test void dryRunDenialStillRestoresDestination() {
        login();
        assertTrue(connect(intended).isCancelled());
        decision.complete(new Decision(Decision.Outcome.DENY, Rule.VPN, "test", null, true));
        assertEquals(List.of(intended), backendAttempts);
        verify(player, never()).disconnect(any(BaseComponent[].class));
    }

    @ParameterizedTest
    @EnumSource(value = Decision.Outcome.class, names = {"ALLOW", "BYPASS", "DENY"})
    void disconnectReleasesHoldAndLateDecisionCannotRestoreState(Decision.Outcome outcome) {
        login();
        assertTrue(connect(intended).isCancelled());
        LoginHold hold = holds.get(player);
        assertNotNull(hold);
        assertTrue(hold.isOpen());

        disconnect();

        assertFalse(hold.isOpen());
        assertEquals(1, releases.get());
        assertNoState();
        decision.complete(result(outcome));
        assertEquals(1, releases.get());
        assertNoState();
        assertTrue(backendAttempts.isEmpty());
        verify(controller, never()).report(any(OriginGateRuntime.class), any(LoginAttempt.class), any(Decision.class));
        verify(player, never()).disconnect(any(BaseComponent[].class));
    }

    @Test void disconnectClearsAdmissionAndUndeliveredBypassNotice() {
        login();
        assertTrue(connect(intended).isCancelled());
        decision.complete(result(Decision.Outcome.BYPASS));
        assertTrue(admitted.contains(player));
        assertTrue(notices.containsKey(player));

        disconnect();

        assertNoState();
        plugin.connected(new ServerConnectedEvent(player, mock(Server.class)));
        verify(player, never()).sendMessage(any(BaseComponent[].class));
        assertEquals(1, releases.get());
    }

    @Test void existingBackendConnectionIsNotHeldOrRetargeted() {
        when(player.getServer()).thenReturn(mock(Server.class));
        ServerConnectEvent event = connect(intended);
        assertFalse(event.isCancelled());
        assertSame(intended, event.getTarget());
        assertTrue(routes.isEmpty());
    }

    private void login() {
        PostLoginEvent login = new PostLoginEvent(player, lobby, (event, error) -> {
            releases.incrementAndGet();
            // Like Bungee's continuation, do not start a backend connection for a closed client.
            if (player.isConnected()) connect(event.getTarget());
        });
        plugin.login(login);
        login.postCall();
        assertEquals(0, releases.get(), "The real PostLogin intent must remain registered while the check is pending");
    }

    private ServerConnectEvent event(ServerInfo target) {
        return new ServerConnectEvent(player, target, ServerConnectEvent.Reason.PLUGIN, null);
    }

    private ServerConnectEvent connect(ServerInfo target) {
        ServerConnectEvent event = event(target);
        plugin.connecting(event);
        if (!event.isCancelled()) backendAttempts.add(event.getTarget());
        return event;
    }

    private void disconnect() {
        connected.set(false);
        plugin.disconnected(new PlayerDisconnectEvent(player));
    }

    private void assertNoState() {
        assertTrue(holds.isEmpty(), "No pending login hold");
        assertTrue(routes.isEmpty(), "No deferred destination");
        assertTrue(admitted.isEmpty(), "No admission retained for the disconnected player");
        assertTrue(notices.isEmpty(), "No queued notice retained for the disconnected player");
    }

    private static Decision result(Decision.Outcome outcome) {
        return new Decision(outcome, outcome == Decision.Outcome.ALLOW ? null : Rule.VPN, "test", null, false);
    }

    private void field(String name, Object value) throws Exception {
        Field field = OriginGateBungee.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }
}
