package io.github.origingate.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.origingate.core.Commands;
import io.github.origingate.core.Log;
import io.github.origingate.core.Text;
import io.github.origingate.core.OriginGateRuntime;
import io.github.origingate.core.lookup.IpInfo;
import io.github.origingate.core.net.Addresses;
import io.github.origingate.core.report.DecisionFile;
import io.github.origingate.core.report.DecisionLine;
import io.github.origingate.core.rules.Decision;
import io.github.origingate.core.rules.LoginAttempt;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Plugin(id = "origingate", name = "OriginGate", version = OriginGateRuntime.VERSION,
        description = "Checks where a connection comes from (VPN, proxy, country) before it joins")
public final class OriginGateVelocity {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Log log;
    private final Map<UUID, Component> bypassNotices = new ConcurrentHashMap<>();
    private volatile OriginGateRuntime runtime;

    @Inject public OriginGateVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.log = new Log() {
            @Override public void info(String message) { logger.info(message); }
            @Override public void warn(String message, Throwable cause) {
                if (cause == null) logger.warn(message);
                else logger.warn(message, cause);
            }
            @Override public void debug(String message) { logger.info(message); }
        };
    }

    @Subscribe public void initialize(ProxyInitializeEvent event) {
        try {
            OriginGateRuntime.installDefaults(dataDirectory);
            runtime = OriginGateRuntime.load(dataDirectory, log);
            logStarted("OriginGate is running");
        } catch (Exception ex) {
            logger.error("OriginGate could not start and is NOT checking connections: {}", Text.message(ex), ex);
            logger.error("Fix the problem, then run: origingate reload");
        }
        var commands = new Commands(new Commands.Platform() {
            @Override public OriginGateRuntime runtime() { return runtime; }
            @Override public Optional<LoginAttempt> onlinePlayer(String name) {
                return proxy.getPlayer(name).flatMap(OriginGateVelocity.this::attempt);
            }
            @Override public List<String> onlinePlayerNames() {
                return proxy.getAllPlayers().stream().map(Player::getUsername).toList();
            }
            @Override public String reload() { return OriginGateVelocity.this.reload(); }
        });
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("origingate").plugin(this).build(),
                new OriginGateCommand(commands));
        proxy.getScheduler().buildTask(this, this::deleteExpired)
                .delay(Duration.ofMinutes(1)).repeat(Duration.ofHours(1)).schedule();
    }

    /**
     * Runs after LuckPerms: its user data is loaded during PermissionsSetupEvent, which Velocity fires
     * before LoginEvent, and its own LoginEvent handlers use FIRST and NORMAL order (higher runs first).
     */
    @Subscribe(priority = -100) public EventTask onLogin(LoginEvent event) {
        OriginGateRuntime active = runtime;
        if (active == null || !event.getResult().isAllowed()) return null;
        Optional<LoginAttempt> attempt = attempt(event.getPlayer());
        if (attempt.isEmpty()) {
            logger.warn("No IP address for {}, not checked", event.getPlayer().getUsername());
            return null;
        }
        return EventTask.resumeWhenComplete(active.gate().check(attempt.get())
                .thenAccept(decision -> apply(active, event, attempt.get(), decision))
                .exceptionally(ex -> {
                    logger.error("OriginGate check failed for {}", event.getPlayer().getUsername(), ex);
                    return null;
                }));
    }

    private void apply(OriginGateRuntime active, LoginEvent event, LoginAttempt attempt, Decision decision) {
        String line = DecisionLine.format(attempt, decision);
        if (active.config().consoleLog().shows(decision)) logger.info(line);
        DecisionFile file = active.decisionFile();
        if (file != null && decision.rule() != null) {
            try {
                file.write(line);
            } catch (IOException ex) {
                logger.warn("Could not write the OriginGate log file: {}", ex.getMessage());
            }
        }
        if (decision.dryRun()) return;
        var messages = active.messages();
        if (decision.kicks()) {
            Component reason;
            try {
                reason = render(messages.kick(decision.rule()), attempt, decision);
            } catch (RuntimeException ex) {
                // Never let a broken message turn a kick into a join.
                logger.warn("Could not build the kick message for {}: {}", decision.rule().id(), Text.message(ex));
                reason = Component.text("You cannot join this server.");
            }
            event.setResult(ResultedEvent.ComponentResult.denied(reason));
            alert(active, messages.alertDenied(), attempt, decision);
        } else if (decision.outcome() == Decision.Outcome.BYPASS) {
            if (!messages.bypassNotice().isEmpty()) {
                bypassNotices.put(attempt.uuid(), render(messages.bypassNotice(), attempt, decision));
            }
            alert(active, messages.alertBypassed(), attempt, decision);
        }
    }

    private void alert(OriginGateRuntime active, String template, LoginAttempt attempt, Decision decision) {
        if (template.isEmpty() || active.config().alertPermissions().isEmpty()) return;
        Component message;
        try {
            message = render(template, attempt, decision);
        } catch (RuntimeException ex) {
            logger.warn("Could not build the staff alert: {}", Text.message(ex));
            return;
        }
        for (Player player : proxy.getAllPlayers()) {
            for (String permission : active.config().alertPermissions()) {
                if (player.hasPermission(permission)) {
                    player.sendMessage(message);
                    break;
                }
            }
        }
    }

    /** Chat is not available during login, so the bypass notice waits for the first server. */
    @Subscribe public void onServerConnected(ServerPostConnectEvent event) {
        if (event.getPreviousServer() != null) return;
        Component notice = bypassNotices.remove(event.getPlayer().getUniqueId());
        if (notice != null) event.getPlayer().sendMessage(notice);
    }

    @Subscribe public void onDisconnect(DisconnectEvent event) {
        bypassNotices.remove(event.getPlayer().getUniqueId());
    }

    @Subscribe public void onShutdown(ProxyShutdownEvent event) {
        OriginGateRuntime active = runtime;
        runtime = null;
        if (active != null) active.close();
    }

    private synchronized String reload() {
        try {
            OriginGateRuntime.installDefaults(dataDirectory);
            OriginGateRuntime next = OriginGateRuntime.load(dataDirectory, log);
            OriginGateRuntime previous = runtime;
            runtime = next;
            if (previous != null) previous.retire();
            logStarted("OriginGate reloaded");
            return "OriginGate reloaded." + (next.config().dryRun() ? " Dry run is on: nobody is kicked." : "");
        } catch (Exception ex) {
            logger.warn("OriginGate reload failed: {}", Text.message(ex));
            return "Reload failed, " + (runtime == null ? "OriginGate is still not running" : "the previous settings stay active")
                    + ": " + Text.message(ex);
        }
    }

    private void deleteExpired() {
        OriginGateRuntime active = runtime;
        if (active == null) return;
        try {
            OriginGateRuntime.Cleanup cleanup = active.deleteExpired();
            if (cleanup.rows() > 0 || cleanup.files() > 0) {
                logger.info("Deleted {} expired lookups and {} old log files", cleanup.rows(), cleanup.files());
            }
        } catch (Exception ex) {
            logger.warn("Could not delete expired OriginGate data: {}", Text.message(ex));
        }
    }

    private void logStarted(String prefix) {
        var config = runtime.config();
        logger.info("{} with {} storage{}.", prefix, config.storage().type(), config.dryRun() ? " in DRY-RUN mode (nobody is kicked)" : "");
        logger.info("Lookups: {}.", runtime.lookupSummary());
        if (config.lookup().uses("proxycheck") && config.lookup().proxycheck().apiKeys().isEmpty()) {
            logger.warn("No proxycheck.io API keys are set; proxycheck.io allows 100 lookups per day without a key.");
        }
    }

    private Optional<LoginAttempt> attempt(Player player) {
        InetSocketAddress remote = player.getRemoteAddress();
        InetAddress address = remote == null ? null : remote.getAddress();
        if (address == null) return Optional.empty();
        return Optional.of(new LoginAttempt(player.getUsername(), player.getUniqueId(), address, player::hasPermission));
    }

    static Component render(String template, LoginAttempt attempt, Decision decision) {
        IpInfo info = decision.lookup() == null ? null : decision.lookup().info();
        TagResolver placeholders = TagResolver.resolver(
                Placeholder.unparsed("username", attempt.username()),
                Placeholder.unparsed("uuid", attempt.uuid() == null ? "-" : attempt.uuid().toString()),
                Placeholder.unparsed("ip", Addresses.text(attempt.address())),
                Placeholder.unparsed("rule", decision.rule() == null ? "-" : decision.rule().id()),
                Placeholder.unparsed("time", String.valueOf(Instant.now().getEpochSecond())),
                Placeholder.unparsed("provider", Text.dash(info == null ? null : info.provider())),
                Placeholder.unparsed("organisation", Text.dash(info == null ? null : info.displayOrganisation())),
                Placeholder.unparsed("country", Text.dash(info == null ? null : info.country())),
                Placeholder.unparsed("country_code", Text.dash(info == null ? null : info.countryCode())),
                Placeholder.unparsed("city", Text.dash(info == null ? null : info.city())),
                Placeholder.unparsed("region", Text.dash(info == null ? null : info.region())),
                Placeholder.unparsed("type", Text.dash(info == null ? null : info.type())));
        return MiniMessage.miniMessage().deserialize(template, placeholders);
    }
}
