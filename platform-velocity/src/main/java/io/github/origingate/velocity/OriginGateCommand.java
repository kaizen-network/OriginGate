package io.github.origingate.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import io.github.origingate.core.Commands;
import net.kyori.adventure.text.Component;

import java.util.List;

final class OriginGateCommand implements SimpleCommand {
    private final Commands commands;

    OriginGateCommand(Commands commands) {
        this.commands = commands;
    }

    @Override public void execute(Invocation invocation) {
        commands.execute(invocation.arguments(), sender(invocation.source()));
    }

    @Override public boolean hasPermission(Invocation invocation) {
        return Commands.canUse(sender(invocation.source()));
    }

    @Override public List<String> suggest(Invocation invocation) {
        return commands.suggest(invocation.arguments(), sender(invocation.source()));
    }

    private static Commands.Sender sender(CommandSource source) {
        return new Commands.Sender() {
            @Override public boolean hasPermission(String permission) { return source.hasPermission(permission); }
            @Override public void reply(String line) { source.sendMessage(Component.text(line)); }
            @Override public String name() { return source instanceof Player player ? player.getUsername() : "console"; }
        };
    }
}
