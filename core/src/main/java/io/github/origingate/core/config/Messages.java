package io.github.origingate.core.config;

import io.github.origingate.core.rules.Rule;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/** MiniMessage templates from messages.yml. Rendering is left to the platform. */
public final class Messages {
    private final Map<Rule, String> kicks;
    private final String bypassNotice;
    private final String alertDenied;
    private final String alertBypassed;

    public Messages(Map<Rule, String> kicks, String bypassNotice, String alertDenied, String alertBypassed) {
        kicks = io.github.origingate.core.util.Compat.mapCopy(kicks);
        this.kicks = kicks;
        this.bypassNotice = bypassNotice;
        this.alertDenied = alertDenied;
        this.alertBypassed = alertBypassed;
    }

    public Map<Rule, String> kicks() { return kicks; }
    public String bypassNotice() { return bypassNotice; }
    public String alertDenied() { return alertDenied; }
    public String alertBypassed() { return alertBypassed; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Messages)) return false;
        Messages that = (Messages) other;
        return java.util.Objects.equals(kicks, that.kicks)
                && java.util.Objects.equals(bypassNotice, that.bypassNotice)
                && java.util.Objects.equals(alertDenied, that.alertDenied)
                && java.util.Objects.equals(alertBypassed, that.alertBypassed);
    }
    @Override public int hashCode() { return java.util.Objects.hash(kicks, bypassNotice, alertDenied, alertBypassed); }
    @Override public String toString() { return "Messages[" + "kicks=" + kicks + ", " + "bypassNotice=" + bypassNotice + ", " + "alertDenied=" + alertDenied + ", " + "alertBypassed=" + alertBypassed + "]"; }



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
