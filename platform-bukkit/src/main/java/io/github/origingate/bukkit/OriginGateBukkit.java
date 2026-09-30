package io.github.origingate.bukkit;

import io.github.origingate.core.*;
import io.github.origingate.core.lookup.LookupService;
import io.github.origingate.core.rules.*;
import io.github.origingate.presentation.MessagesRenderer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.net.InetAddress;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** Bukkit APIs shared by legacy CraftBukkit, Spigot, and Paper. */
public final class OriginGateBukkit extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final PendingAdmissions<Preflight> pending = new PendingAdmissions<>(512, Clock.systemUTC());
    private final DeferredAdmission<LookupService.Result> deferred = new DeferredAdmission<>(512, Clock.systemUTC());
    private final Map<Player, String> notices = new ConcurrentHashMap<>();
    private final Map<AsyncPlayerPreLoginEvent, Preflight> preflightEvents = new ConcurrentHashMap<>();
    private RuntimeController controller;
    private Commands commands;
    private volatile boolean stopping;

    @Override public void onEnable() {
        stopping = false;
        controller = new RuntimeController(getDataFolder().toPath(), new JulLog(getLogger()));
        controller.reload();
        commands = new Commands(new Commands.Platform() {
            @Override public OriginGateRuntime runtime() { return controller.runtime(); }
            @Override public Optional<LoginAttempt> onlinePlayer(String name) {
                Player player = getServer().getPlayerExact(name);
                OriginGateRuntime active = controller.runtime();
                return player == null || active == null || player.getAddress() == null ? Optional.empty()
                        : Optional.of(attempt(active, player, player.getAddress().getAddress()));
            }
            @Override public List<String> onlinePlayerNames() {
                List<String> names = new ArrayList<>();
                for (Player player : players()) names.add(player.getName());
                return names;
            }
            @Override public String reload() { return controller.reload(); }
        });
        getCommand("origingate").setExecutor(this);
        getCommand("origingate").setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimerAsynchronously(this, controller::cleanup, 1200, 72000);
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> { pending.expire(); deferred.expire(); }, 1200, 1200);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void preLogin(AsyncPlayerPreLoginEvent event) {
        OriginGateRuntime active = controller.runtime();
        if (stopping || active == null || event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        String key = key(event.getName(), event.getAddress());
        Preflight flight = new Preflight(active);
        if (!pending.put(key, flight)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "A connection check is already pending. Please reconnect shortly.");
            return;
        }
        preflightEvents.put(event, flight);
        LoginAttempt initial = new LoginAttempt(event.getName(), null, event.getAddress(), permission -> false);
        try {
            if (!active.gate().beforeLookup(initial).isPresent()) {
                flight.result = active.gate().lookups().lookup(event.getAddress().getHostAddress(), false)
                        .get(active.config().lookup().waitMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            flight.failure = ex;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void preLoginFinished(AsyncPlayerPreLoginEvent event) {
        Preflight flight = preflightEvents.remove(event);
        if (flight != null && event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED)
            pending.remove(key(event.getName(), event.getAddress()), flight);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void login(PlayerLoginEvent event) {
        if (stopping) return;
        Preflight flight = pending.take(key(event.getPlayer().getName(), event.getAddress()));
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) return;
        OriginGateRuntime active = flight == null ? controller.runtime() : flight.runtime;
        if (active == null) return;
        LoginAttempt attempt = attempt(active, event.getPlayer(), event.getAddress());
        Decision decision;
        if (flight == null && !active.gate().beforeLookup(attempt).isPresent()) {
            CompletableFuture<LookupService.Result> lookup = deferred.poll(key(attempt.username(), attempt.address()), active,
                    () -> io.github.origingate.core.util.Futures.timeout(
                            active.gate().lookups().lookup(attempt.address().getHostAddress(), false),
                            active.config().lookup().waitMillis(), TimeUnit.MILLISECONDS));
            if (!lookup.isDone()) {
                if (active.config().dryRun()) {
                    lookup.whenComplete((result, error) -> controller.report(active, attempt, active.gate().finish(attempt, result, error)));
                } else {
                    event.disallow(PlayerLoginEvent.Result.KICK_OTHER, "Your connection is being checked. Please reconnect in a moment.");
                }
                return;
            }
            try { decision = active.gate().finish(attempt, lookup.join(), null); }
            catch (CompletionException ex) { decision = active.gate().finish(attempt, null, ex); }
        } else {
            decision = active.gate().finish(attempt, flight == null ? null : flight.result, flight == null ? null : flight.failure);
        }
        final Decision reported = decision;
        background(() -> controller.report(active, attempt, reported));
        if (decision.dryRun()) return;
        if (decision.kicks()) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, render(active.messages().kick(decision.rule()), attempt, decision));
            alert(active, active.messages().alertDenied(), attempt, decision);
        } else if (decision.outcome() == Decision.Outcome.BYPASS) {
            if (!active.messages().bypassNotice().isEmpty())
                notices.put(event.getPlayer(), render(active.messages().bypassNotice(), attempt, decision));
            alert(active, active.messages().alertBypassed(), attempt, decision);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void loginFinished(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) notices.remove(event.getPlayer());
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        String notice = notices.remove(event.getPlayer());
        if (notice != null) event.getPlayer().sendMessage(notice);
    }
    @EventHandler public void quit(PlayerQuitEvent event) { notices.remove(event.getPlayer()); }

    private LoginAttempt attempt(OriginGateRuntime active, Player player, InetAddress address) {
        return new LoginAttempt(player.getName(), player.getUniqueId(), address, RuntimeController.permissions(active, player::hasPermission));
    }
    private static String key(String name, InetAddress address) { return name.toLowerCase(Locale.ROOT) + "/" + address.getHostAddress(); }
    private String render(String template, LoginAttempt attempt, Decision decision) {
        try { return MessagesRenderer.legacy(template, attempt, decision); }
        catch (RuntimeException ex) { getLogger().warning("Could not render OriginGate message: " + Text.message(ex)); return "You cannot join this server."; }
    }
    private void alert(OriginGateRuntime active, String template, LoginAttempt attempt, Decision decision) {
        if (template.isEmpty() || active.config().alertPermissions().isEmpty()) return;
        String message = render(template, attempt, decision);
        for (Player player : players()) {
            for (String permission : active.config().alertPermissions()) {
                if (player.hasPermission(permission)) { player.sendMessage(message); break; }
            }
        }
    }
    /** Bukkit changed this method's return type from an array to a collection. */
    private List<Player> players() {
        try {
            Object value = getServer().getClass().getMethod("getOnlinePlayers").invoke(getServer());
            if (value instanceof Player[]) return Arrays.asList((Player[]) value);
            List<Player> result = new ArrayList<>();
            for (Object player : (Collection<?>) value) result.add((Player) player);
            return result;
        } catch (ReflectiveOperationException ex) { throw new IllegalStateException("Cannot list online players", ex); }
    }
    private Commands.Sender sender(CommandSender sender) {
        Set<String> permissions = new HashSet<>();
        for (String node : Arrays.asList(Commands.CHECK, Commands.RELOAD, Commands.CACHE))
            if (sender.hasPermission(node)) permissions.add(node);
        return new Commands.Sender() {
            @Override public boolean hasPermission(String permission) { return permissions.contains(permission); }
            @Override public void reply(String line) {
                if (stopping) return;
                getServer().getScheduler().runTask(OriginGateBukkit.this, () -> sender.sendMessage(line));
            }
        };
    }
    private void background(Runnable task) {
        if (!stopping) getServer().getScheduler().runTaskAsynchronously(this, task);
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Commands.Sender source = sender(sender);
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) background(() -> commands.execute(args, source));
        else commands.execute(args, source);
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return commands.suggest(args, sender(sender));
    }
    @Override public void onDisable() {
        stopping = true;
        getServer().getScheduler().cancelTasks(this);
        pending.clear(); deferred.clear(); notices.clear(); preflightEvents.clear();
        if (controller != null) controller.closeAsync();
    }
    private static final class Preflight {
        final OriginGateRuntime runtime;
        volatile LookupService.Result result;
        volatile Throwable failure;
        Preflight(OriginGateRuntime runtime) { this.runtime = runtime; }
    }
}
