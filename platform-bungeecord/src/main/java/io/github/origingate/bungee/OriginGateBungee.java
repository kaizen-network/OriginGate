package io.github.origingate.bungee;

import io.github.origingate.core.*;
import io.github.origingate.core.rules.*;
import io.github.origingate.presentation.MessagesRenderer;
import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.event.*;
import net.md_5.bungee.api.plugin.*;
import net.md_5.bungee.event.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.*;

/** Requires BungeeCord's asynchronous PostLoginEvent (1.21-R0.4 API or newer). */
public final class OriginGateBungee extends Plugin implements Listener {
    private final Map<ProxiedPlayer, LoginHold> holds = new ConcurrentHashMap<>();
    private final Set<ProxiedPlayer> admitted = ConcurrentHashMap.newKeySet();
    private final Map<ProxiedPlayer, net.md_5.bungee.api.config.ServerInfo> deferredRoutes = new ConcurrentHashMap<>();
    private final Map<ProxiedPlayer, Component> notices = new ConcurrentHashMap<>();
    private RuntimeController controller;
    private Commands commands;
    private volatile boolean stopping;
    @Override public void onEnable() {
        // On older proxies registerIntent does not exist, and every login would fail before admission.
        if (!AsyncEvent.class.isAssignableFrom(PostLoginEvent.class)) {
            getLogger().severe("OriginGate needs BungeeCord 1.21-R0.4 or newer (asynchronous PostLoginEvent) and is NOT checking connections.");
            return;
        }
        controller = new RuntimeController(getDataFolder().toPath(), new JulLog(getLogger()));
        controller.reload();
        commands = new Commands(new Commands.Platform() {
            @Override public OriginGateRuntime runtime() { return controller.runtime(); }
            @Override public Optional<LoginAttempt> onlinePlayer(String name) {
                ProxiedPlayer player = getProxy().getPlayer(name);
                OriginGateRuntime active = controller.runtime();
                InetAddress address = player == null ? null : address(player);
                return address == null || active == null ? Optional.empty() : Optional.of(attempt(active, player, address));
            }
            @Override public List<String> onlinePlayerNames() {
                List<String> names = new ArrayList<>();
                for (ProxiedPlayer player : getProxy().getPlayers()) names.add(player.getName());
                return names;
            }
            @Override public String reload() { return controller.reload(); }
        });
        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getPluginManager().registerCommand(this, new GateCommand());
        getProxy().getScheduler().schedule(this, controller::cleanup, 1, 60, TimeUnit.MINUTES);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void login(PostLoginEvent event) {
        OriginGateRuntime active = controller.runtime();
        ProxiedPlayer player = event.getPlayer();
        if (stopping) return;
        // An unchecked player is admitted, as on Velocity; connecting() would otherwise hold them with no server.
        if (active == null) { admitted.add(player); return; }
        InetAddress address = address(player);
        if (address == null) {
            getLogger().warning("No IP address for " + player.getName() + ", not checked");
            admitted.add(player);
            return;
        }
        LoginAttempt checked;
        CompletableFuture<Decision> check;
        try {
            checked = attempt(active, player, address);
            check = active.gate().check(checked);
        } catch (RuntimeException ex) {
            // An exception here would leave the player without a server, so it follows on-lookup-failure instead.
            getLogger().warning("OriginGate could not check " + player.getName() + ": " + Text.message(ex));
            checked = new LoginAttempt(player.getName(), player.getUniqueId(), address, permission -> false);
            check = CompletableFuture.completedFuture(active.gate().finish(checked, null, ex));
        }
        LoginAttempt attempt = checked;
        event.registerIntent(this);
        LoginHold hold = new LoginHold(() -> event.completeIntent(this));
        holds.put(player, hold);
        try {
            check.whenComplete((decision, error) -> {
                try {
                    if (stopping || !hold.isOpen() || !player.isConnected()) return;
                    Decision result = error == null ? decision : active.gate().finish(attempt, null, error);
                    controller.report(active, attempt, result);
                    if (result.dryRun() || !result.kicks()) admitted.add(player);
                    if (result.dryRun()) return;
                    if (result.kicks()) {
                        Component reason = render(active.messages().kick(result.rule()), attempt, result);
                        player.disconnect(reason == null ? TextComponent.fromLegacyText("You cannot join this server.") : legacy(reason, player));
                        alert(active, active.messages().alertDenied(), attempt, result);
                    } else if (result.outcome() == Decision.Outcome.BYPASS) {
                        Component notice = active.messages().bypassNotice().isEmpty() ? null : render(active.messages().bypassNotice(), attempt, result);
                        if (notice != null) notices.put(player, notice);
                        alert(active, active.messages().alertBypassed(), attempt, result);
                    }
                } finally {
                    // A player who left after the check above may already be past disconnected().
                    if (!player.isConnected()) { admitted.remove(player); notices.remove(player); }
                    holds.remove(player, hold);
                    hold.close();
                }
            });
        } catch (RuntimeException ex) {
            holds.remove(player, hold);
            try { player.disconnect(TextComponent.fromLegacyText("OriginGate could not check this connection.")); }
            finally { hold.close(); }
        }
    }
    /** A PostLogin intent does not hold another plugin's explicit connect() call. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void connecting(ServerConnectEvent event) {
        ProxiedPlayer player = event.getPlayer();
        if (event.isCancelled() || player.getServer() != null) return;
        if (stopping || (controller.runtime() != null && !admitted.contains(player))) {
            deferredRoutes.put(player, event.getTarget());
            event.setCancelled(true);
            return;
        }
        net.md_5.bungee.api.config.ServerInfo deferred = deferredRoutes.remove(player);
        if (deferred != null) event.setTarget(deferred);
    }
    @EventHandler public void connected(ServerConnectedEvent event) {
        Component notice = notices.remove(event.getPlayer());
        if (notice != null) event.getPlayer().sendMessage(legacy(notice, event.getPlayer()));
    }
    @EventHandler public void disconnected(PlayerDisconnectEvent event) {
        notices.remove(event.getPlayer());
        admitted.remove(event.getPlayer());
        deferredRoutes.remove(event.getPlayer());
        LoginHold hold = holds.remove(event.getPlayer());
        if (hold != null) hold.close();
    }
    /** Null for a connection without an IP address, such as a Unix domain socket listener. */
    private static InetAddress address(ProxiedPlayer player) {
        SocketAddress remote = player.getSocketAddress();
        return remote instanceof InetSocketAddress ? ((InetSocketAddress) remote).getAddress() : null;
    }
    private LoginAttempt attempt(OriginGateRuntime active, ProxiedPlayer player, InetAddress address) {
        return new LoginAttempt(player.getName(), player.getUniqueId(), address,
                RuntimeController.permissions(active, player::hasPermission));
    }
    /** Null when the template cannot be rendered. */
    private Component render(String template, LoginAttempt attempt, Decision decision) {
        try { return MessagesRenderer.render(template, attempt, decision); }
        catch (RuntimeException ex) { getLogger().warning("Could not render OriginGate message: " + Text.message(ex)); return null; }
    }
    /** BungeeCord sends hex colours as is, which clients before 1.16 (protocol 735) cannot show, so they get named colours. */
    private static BaseComponent[] legacy(Component message, ProxiedPlayer player) {
        return TextComponent.fromLegacyText(MessagesRenderer.legacy(message, player.getPendingConnection().getVersion() >= 735));
    }
    private void alert(OriginGateRuntime active, String template, LoginAttempt attempt, Decision decision) {
        if (template.isEmpty() || active.config().alertPermissions().isEmpty()) return;
        Component message = render(template, attempt, decision);
        if (message == null) return;
        for (ProxiedPlayer player : getProxy().getPlayers()) {
            for (String permission : active.config().alertPermissions()) {
                if (player.hasPermission(permission)) { player.sendMessage(legacy(message, player)); break; }
            }
        }
    }
    private Commands.Sender sender(CommandSender source) {
        return new Commands.Sender() {
            @Override public boolean hasPermission(String permission) { return source.hasPermission(permission); }
            @Override public void reply(String line) { source.sendMessage(new TextComponent(line)); }
        };
    }
    private final class GateCommand extends Command implements TabExecutor {
        GateCommand() { super("origingate"); }
        @Override public void execute(CommandSender source, String[] args) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload"))
                getProxy().getScheduler().runAsync(OriginGateBungee.this, () -> commands.execute(args, sender(source)));
            else commands.execute(args, sender(source));
        }
        @Override public boolean hasPermission(CommandSender source) { return Commands.canUse(sender(source)); }
        @Override public Iterable<String> onTabComplete(CommandSender source, String[] args) { return commands.suggest(args, sender(source)); }
    }
    @Override public void onDisable() {
        stopping = true;
        getProxy().getScheduler().cancel(this);
        holds.forEach((player, hold) -> {
            try { player.disconnect(TextComponent.fromLegacyText("OriginGate is stopping. Please reconnect.")); }
            finally { hold.close(); }
        });
        holds.clear(); notices.clear(); admitted.clear(); deferredRoutes.clear();
        if (controller != null) controller.stop(10_000);
    }
}
