package io.github.origingate.bungee;

import io.github.origingate.core.*;
import io.github.origingate.core.rules.*;
import io.github.origingate.presentation.MessagesRenderer;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.event.*;
import net.md_5.bungee.api.plugin.*;
import net.md_5.bungee.event.*;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.*;

/** Requires BungeeCord's asynchronous PostLoginEvent (1.21-R0.4 API or newer). */
public final class OriginGateBungee extends Plugin implements Listener {
    private final Map<ProxiedPlayer, LoginHold> holds = new ConcurrentHashMap<>();
    private final Set<ProxiedPlayer> admitted = ConcurrentHashMap.newKeySet();
    private final Map<ProxiedPlayer, net.md_5.bungee.api.config.ServerInfo> deferredRoutes = new ConcurrentHashMap<>();
    private final Map<ProxiedPlayer, String> notices = new ConcurrentHashMap<>();
    private RuntimeController controller;
    private Commands commands;
    private volatile boolean stopping;
    @Override public void onEnable() {
        controller = new RuntimeController(getDataFolder().toPath(), new JulLog(getLogger()));
        controller.reload();
        commands = new Commands(new Commands.Platform() {
            @Override public OriginGateRuntime runtime() { return controller.runtime(); }
            @Override public Optional<LoginAttempt> onlinePlayer(String name) {
                ProxiedPlayer player = getProxy().getPlayer(name);
                OriginGateRuntime active = controller.runtime();
                return player == null || active == null ? Optional.empty() : Optional.of(attempt(active, player));
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
        if (stopping || active == null) return;
        ProxiedPlayer player = event.getPlayer();
        LoginAttempt attempt;
        try { attempt = attempt(active, player); }
        catch (RuntimeException ex) { getLogger().warning("No IP address for " + player.getName()); return; }
        event.registerIntent(this);
        LoginHold hold = new LoginHold(() -> event.completeIntent(this));
        holds.put(player, hold);
        try {
            active.gate().check(attempt).whenComplete((decision, error) -> {
                try {
                    if (stopping || !hold.isOpen() || !player.isConnected()) return;
                    Decision result = error == null ? decision : active.gate().finish(attempt, null, error);
                    controller.report(active, attempt, result);
                    if (result.dryRun() || !result.kicks()) admitted.add(player);
                    if (result.dryRun()) return;
                    if (result.kicks()) {
                        player.disconnect(TextComponent.fromLegacyText(render(active.messages().kick(result.rule()), attempt, result)));
                        alert(active, active.messages().alertDenied(), attempt, result);
                    } else if (result.outcome() == Decision.Outcome.BYPASS) {
                        if (!active.messages().bypassNotice().isEmpty())
                            notices.put(player, render(active.messages().bypassNotice(), attempt, result));
                        alert(active, active.messages().alertBypassed(), attempt, result);
                    }
                } finally { holds.remove(player, hold); hold.close(); }
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
        String notice = notices.remove(event.getPlayer());
        if (notice != null) event.getPlayer().sendMessage(TextComponent.fromLegacyText(notice));
    }
    @EventHandler public void disconnected(PlayerDisconnectEvent event) {
        notices.remove(event.getPlayer());
        admitted.remove(event.getPlayer());
        deferredRoutes.remove(event.getPlayer());
        LoginHold hold = holds.remove(event.getPlayer());
        if (hold != null) hold.close();
    }
    private LoginAttempt attempt(OriginGateRuntime active, ProxiedPlayer player) {
        InetAddress address = player.getAddress().getAddress();
        return new LoginAttempt(player.getName(), player.getUniqueId(), address,
                RuntimeController.permissions(active, player::hasPermission));
    }
    private String render(String template, LoginAttempt attempt, Decision decision) {
        try { return MessagesRenderer.legacy(template, attempt, decision); }
        catch (RuntimeException ex) { getLogger().warning("Could not render OriginGate message: " + Text.message(ex)); return "You cannot join this server."; }
    }
    private void alert(OriginGateRuntime active, String template, LoginAttempt attempt, Decision decision) {
        if (template.isEmpty() || active.config().alertPermissions().isEmpty()) return;
        String message = render(template, attempt, decision);
        for (ProxiedPlayer player : getProxy().getPlayers()) {
            for (String permission : active.config().alertPermissions()) {
                if (player.hasPermission(permission)) { player.sendMessage(TextComponent.fromLegacyText(message)); break; }
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
        if (controller != null) controller.closeAsync();
    }
}
