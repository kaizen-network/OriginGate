package io.github.origingate.core.config;

import io.github.origingate.core.rules.Rule;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/** MiniMessage templates from messages.yml. Rendering is left to the platform. */
public record Messages(Map<Rule, String> kicks, String bypassNotice, String alertDenied, String alertBypassed) {
    public Messages {
        kicks = Map.copyOf(kicks);
    }

    public String kick(Rule rule) {
        return kicks.get(rule);
    }

    public static Messages load(Path dataDirectory) throws ConfigException {
        YamlSection messages = YamlSection.load(dataDirectory.resolve("messages.yml"));
        messages.allowOnly("kick", "bypass-notice", "alerts");
        YamlSection kick = messages.section("kick");
        Map<Rule, String> kicks = new EnumMap<>(Rule.class);
        String[] names = new String[Rule.values().length];
        for (Rule rule : Rule.values()) names[rule.ordinal()] = rule.id();
        kick.allowOnly(names);
        for (Rule rule : Rule.values()) kicks.put(rule, kick.text(rule.id(), 1, 4096));
        YamlSection alerts = messages.section("alerts");
        alerts.allowOnly("denied", "bypassed");
        return new Messages(kicks, messages.text("bypass-notice", 0, 1024), alerts.text("denied", 0, 1024),
                alerts.text("bypassed", 0, 1024));
    }
}
