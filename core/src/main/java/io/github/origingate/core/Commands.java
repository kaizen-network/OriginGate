package io.github.origingate.core;

import io.github.origingate.core.lists.ListEntry;
import io.github.origingate.core.lookup.IpInfo;
import io.github.origingate.core.lookup.LookupService;
import io.github.origingate.core.net.Addresses;
import io.github.origingate.core.report.DecisionLine;
import io.github.origingate.core.rules.Decision;
import io.github.origingate.core.rules.LoginAttempt;
import io.github.origingate.core.rules.Rule;

import java.net.InetAddress;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code /origingate check|status|reload|cache clear|allow|deny}, independent of the platform. */
public final class Commands {
    public static final String CHECK = "origingate.command.check";
    public static final String RELOAD = "origingate.command.reload";
    public static final String CACHE = "origingate.command.cache";
    public static final String STATUS = "origingate.command.status";
    public static final String ALLOW = "origingate.command.allow";
    public static final String DENY = "origingate.command.deny";
    /** Every command permission, for platforms that look them up before running a command elsewhere. */
    public static final List<String> PERMISSIONS = io.github.origingate.core.util.Compat.list(CHECK, STATUS, RELOAD, CACHE, ALLOW, DENY);
    private static final DateTimeFormatter LIST_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");
    private static final Pattern DURATION = Pattern.compile("([0-9]+)([smhdw])");
    private static final int PAGE_SIZE = 10;
    private static final int MAX_NOTE = 255;
    private static final long MAX_SECONDS = 3650L * 86400;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    public interface Sender {
        boolean hasPermission(String permission);

        /** May be called from any thread. */
        void reply(String line);

        /** Who ran the command, for the list entries they add. */
        default String name() { return "console"; }
    }

    public interface Platform {
        /** Null when OriginGate failed to start. */
        OriginGateRuntime runtime();

        /** An online player's connection, found by name. */
        Optional<LoginAttempt> onlinePlayer(String name);

        List<String> onlinePlayerNames();

        /** Loads the files again. Returns the message to show. */
        String reload();

        /** Disconnects every online player the test matches, with the kick message of the decision's rule. Called from a worker thread. */
        default void kick(Predicate<LoginAttempt> who, Decision decision) { }
    }

    private final Platform platform;

    public Commands(Platform platform) {
        this.platform = platform;
    }

    public static boolean canUse(Sender sender) {
        for (String permission : PERMISSIONS) if (sender.hasPermission(permission)) return true;
        return false;
    }

    public void execute(String[] args, Sender sender) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "check": {
                if (!allowed(sender, CHECK)) return;
                if (args.length < 2 || args.length > 3 || (args.length == 3 && !args[2].equalsIgnoreCase("refresh"))) {
                    sender.reply("Usage: /origingate check <player|ip> [refresh]");
                    return;
                }
                check(args[1], args.length == 3, sender);
                break;
            }
            case "status": {
                if (!allowed(sender, STATUS)) return;
                OriginGateRuntime runtime = running(sender);
                if (runtime == null) return;
                sender.reply("OriginGate " + OriginGateRuntime.VERSION + " status:");
                for (String line : runtime.status()) sender.reply("  " + line);
                break;
            }
            case "reload": {
                if (!allowed(sender, RELOAD)) return;
                sender.reply(platform.reload());
                break;
            }
            case "cache": {
                if (!allowed(sender, CACHE)) return;
                if (args.length != 3 || !args[1].equalsIgnoreCase("clear")) {
                    sender.reply("Usage: /origingate cache clear <ip|all>");
                    return;
                }
                clear(args[2], sender);
                break;
            }
            case "allow":
            case "deny": {
                ListEntry.Kind kind = kindOf(sub);
                if (!allowed(sender, permission(kind))) return;
                list(kind, args, sender);
                break;
            }
            default: usage(sender);
        }
    }

    private static ListEntry.Kind kindOf(String word) {
        return word.equalsIgnoreCase("allow") ? ListEntry.Kind.ALLOW : word.equalsIgnoreCase("deny") ? ListEntry.Kind.DENY : null;
    }

    private static String permission(ListEntry.Kind kind) { return kind == ListEntry.Kind.ALLOW ? ALLOW : DENY; }

    private static String listUsage(ListEntry.Kind kind) {
        return "Usage: /origingate " + kind.id() + " add <player|ip|cidr> [duration] [note], remove <player|ip|cidr>, list [page]";
    }

    private void list(ListEntry.Kind kind, String[] args, Sender sender) {
        String action = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        int page = 1;
        boolean valid;
        switch (action) {
            case "add": valid = args.length >= 3; break;
            case "remove": valid = args.length == 3; break;
            case "list":
                valid = args.length <= 3;
                if (valid && args.length == 3) {
                    try {
                        page = Integer.parseInt(args[2]);
                    } catch (NumberFormatException ex) {
                        page = 0;
                    }
                    valid = page >= 1;
                }
                break;
            default: valid = false;
        }
        if (!valid) {
            sender.reply(listUsage(kind));
            return;
        }
        OriginGateRuntime runtime = running(sender);
        if (runtime == null) return;
        if (action.equals("add")) add(runtime, kind, args, sender);
        else if (action.equals("remove")) remove(runtime, kind, args[2], sender);
        else show(runtime, kind, page, sender);
    }

    private void add(OriginGateRuntime runtime, ListEntry.Kind kind, String[] args, Sender sender) {
        String target;
        try {
            target = ListEntry.normalize(args[2]);
        } catch (IllegalArgumentException ex) {
            sender.reply(ex.getMessage());
            return;
        }
        Instant now = Instant.now();
        Instant expires = null;
        int noteFrom = 3;
        if (args.length > 3) {
            String word = args[3].toLowerCase(Locale.ROOT);
            Matcher duration = DURATION.matcher(word);
            if (word.equals("never")) {
                noteFrom = 4;
            } else if (duration.matches()) {
                long seconds = seconds(duration);
                if (seconds < 1 || seconds > MAX_SECONDS) {
                    sender.reply("Durations go from 1s to 3650d, or never.");
                    return;
                }
                expires = now.plusSeconds(seconds);
                noteFrom = 4;
            }
        }
        String note = String.join(" ", Arrays.copyOfRange(args, noteFrom, args.length));
        if (note.length() > MAX_NOTE) {
            sender.reply("Notes can be up to " + MAX_NOTE + " characters.");
            return;
        }
        ListEntry entry = new ListEntry(kind, target, expires, note.isEmpty() ? null : note, sender.name(), now);
        runtime.lists().add(entry).whenComplete((done, error) -> {
            if (error != null) {
                sender.reply("Could not change the " + kind.id() + " list: " + Text.message(error));
                return;
            }
            boolean dryRun = runtime.config().dryRun();
            if (kind == ListEntry.Kind.DENY && !dryRun) {
                // Exactly the players the login check would now turn away by the deny list (bypasses and allow entries spare the rest).
                try {
                    platform.kick(attempt -> {
                        try {
                            Optional<Decision> verdict = runtime.gate().beforeLookup(attempt);
                            return verdict.isPresent() && verdict.get().outcome() == Decision.Outcome.DENY && verdict.get().rule() == Rule.DENY_LIST;
                        } catch (RuntimeException ex) {
                            return false;
                        }
                    }, new Decision(Decision.Outcome.DENY, Rule.DENY_LIST, entry.note(), null, false));
                } catch (RuntimeException ex) {
                    // The entry is saved, so the reply below still goes out.
                }
            }
            String text = "Added " + target + " to the " + kind.id() + " list, " + expiry(entry.expiresAt())
                    + (entry.note() == null ? "" : ", note: " + entry.note()) + ".";
            if (!runtime.lists().persistent()) text += " Kept in memory only until a restart.";
            if (kind == ListEntry.Kind.DENY && dryRun) text += " Dry run is on: nobody online is disconnected.";
            sender.reply(text);
        });
    }

    /** Seconds in a duration such as 7d, or -1 when the number is too big to be in range. */
    private static long seconds(Matcher duration) {
        String digits = duration.group(1);
        if (digits.length() > 10) return -1;
        long unit;
        switch (duration.group(2)) {
            case "s": unit = 1; break;
            case "m": unit = 60; break;
            case "h": unit = 3600; break;
            case "d": unit = 86400; break;
            default: unit = 604800;
        }
        return Long.parseLong(digits) * unit;
    }

    private static String expiry(Instant expires) {
        return expires == null ? "never expires" : "expires " + LIST_TIME.format(expires.atZone(ZoneId.systemDefault()));
    }

    private void remove(OriginGateRuntime runtime, ListEntry.Kind kind, String input, Sender sender) {
        String target;
        try {
            target = ListEntry.normalize(input);
        } catch (IllegalArgumentException ex) {
            sender.reply(ex.getMessage());
            return;
        }
        runtime.lists().remove(kind, target).whenComplete((removed, error) -> {
            if (error != null) sender.reply("Could not change the " + kind.id() + " list: " + Text.message(error));
            else sender.reply(removed ? "Removed " + target + " from the " + kind.id() + " list." : target + " is not on the " + kind.id() + " list.");
        });
    }

    private void show(OriginGateRuntime runtime, ListEntry.Kind kind, int page, Sender sender) {
        runtime.lists().active(kind).whenComplete((entries, error) -> {
            if (error != null) {
                sender.reply("Could not read the " + kind.id() + " list: " + Text.message(error));
                return;
            }
            if (entries.isEmpty()) {
                sender.reply("The " + kind.id() + " list is empty.");
                return;
            }
            int pages = (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE;
            if (page > pages) {
                sender.reply("There is no page " + page + ". The " + kind.id() + " list has " + pages + (pages == 1 ? " page." : " pages."));
                return;
            }
            sender.reply((kind == ListEntry.Kind.ALLOW ? "Allow" : "Deny") + " list, page " + page + " of " + pages + " (" + entries.size() + " entries):");
            for (ListEntry entry : entries.subList((page - 1) * PAGE_SIZE, Math.min(entries.size(), page * PAGE_SIZE))) {
                sender.reply("  " + entry.target() + " (by " + entry.addedBy() + ", " + expiry(entry.expiresAt())
                        + (entry.note() == null ? "" : ", note: " + entry.note()) + ")");
            }
        });
    }

    public List<String> suggest(String[] args, Sender sender) {
        List<String> options = new ArrayList<>();
        if (args.length <= 1) {
            if (sender.hasPermission(CHECK)) options.add("check");
            if (sender.hasPermission(STATUS)) options.add("status");
            if (sender.hasPermission(RELOAD)) options.add("reload");
            if (sender.hasPermission(CACHE)) options.add("cache");
            if (sender.hasPermission(ALLOW)) options.add("allow");
            if (sender.hasPermission(DENY)) options.add("deny");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("check") && sender.hasPermission(CHECK)) {
            options.addAll(platform.onlinePlayerNames());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("check") && sender.hasPermission(CHECK)) {
            options.add("refresh");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("cache") && sender.hasPermission(CACHE)) {
            options.add("clear");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("cache") && sender.hasPermission(CACHE)) {
            options.add("all");
        } else if (kindOf(args[0]) != null && sender.hasPermission(permission(kindOf(args[0])))) {
            suggestList(kindOf(args[0]), args, options);
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(prefix));
        return options;
    }

    private void suggestList(ListEntry.Kind kind, String[] args, List<String> options) {
        if (args.length == 2) {
            options.addAll(Arrays.asList("add", "remove", "list"));
        } else if (args.length == 3 && args[1].equalsIgnoreCase("add")) {
            options.addAll(platform.onlinePlayerNames());
        } else if (args.length == 3 && args[1].equalsIgnoreCase("remove") && platform.runtime() != null) {
            options.addAll(platform.runtime().lists().targets(kind));
        }
    }

    private void check(String target, boolean refresh, Sender sender) {
        OriginGateRuntime runtime = running(sender);
        if (runtime == null) return;
        Optional<LoginAttempt> player = platform.onlinePlayer(target);
        LoginAttempt attempt;
        if (player.isPresent()) {
            attempt = player.get();
        } else {
            Optional<InetAddress> address = Addresses.parse(target);
            if (!address.isPresent()) {
                sender.reply(target + " is not an online player or an IP address.");
                return;
            }
            attempt = LoginAttempt.address(address.get());
        }
        String ip = Addresses.text(attempt.address());
        String who = player.isPresent() ? attempt.username() + " (" + ip + ")" : ip;
        if (runtime.config().lookup().skipPrivateAddresses() && Addresses.isPrivate(attempt.address())) {
            sender.reply(who + " is a private address. Lookups are skipped (skip-private-addresses).");
            runtime.gate().beforeLookup(attempt).ifPresent(decision -> sender.reply("Result: " + result(decision, player.isPresent())));
            return;
        }
        sender.reply("Looking up " + who + (refresh ? " from the provider..." : "..."));
        CompletableFuture<LookupService.Result> lookup = runtime.gate().lookups().lookup(ip, refresh);
        io.github.origingate.core.util.Futures.timeout(lookup, 30, TimeUnit.SECONDS).whenComplete((result, error) -> {
            if (error != null) {
                sender.reply("Lookup failed for " + ip + ": " + Text.message(error));
                return;
            }
            IpInfo info = result.info();
            sender.reply("OriginGate check for " + who + ":");
            sender.reply("  Source: " + result.source().name().toLowerCase(Locale.ROOT) + ", checked "
                    + TIME.format(info.checkedAt().atZone(ZoneId.systemDefault())));
            if (result.answeredBy() != null) sender.reply("  Answered by: " + result.answeredBy().described());
            sender.reply("  Provider: " + Text.dash(info.provider()) + " | Organisation: " + Text.dash(info.organisation())
                    + " | Operator: " + Text.dash(info.operatorName()));
            sender.reply("  ASN: " + Text.dash(info.asn()) + " | Type: " + Text.dash(info.type()));
            sender.reply("  Location: " + Text.dash(info.city()) + ", " + Text.dash(info.region()) + ", " + Text.dash(info.country())
                    + " (" + Text.dash(info.countryCode()) + ")");
            sender.reply("  VPN: " + (info.vpn() ? "yes" : "no") + " | Proxy: " + (info.proxy() ? "yes" : "no"));
            Decision decision;
            try {
                decision = runtime.gate().beforeLookup(attempt).orElseGet(() -> runtime.gate().afterLookup(attempt, result));
            } catch (RuntimeException ex) {
                sender.reply("  Result: could not be worked out: " + ex.getMessage());
                return;
            }
            sender.reply("  Result" + (player.isPresent() ? "" : " for a player without bypass permissions") + ": "
                    + result(decision, player.isPresent()));
        });
    }

    private static String result(Decision decision, boolean player) {
        String rule = decision.rule() == null ? "" : " by the " + decision.rule().id() + " rule";
        String line;
        switch (decision.outcome()) {
            case ALLOW: line = "allowed"; break;
            case BYPASS: line = "allowed through a bypass" + rule; break;
            case DENY: line = "kicked" + rule; break;
            default: throw new AssertionError(decision.outcome());
        }
        if (decision.outcome() == Decision.Outcome.DENY && decision.dryRun()) line += " (dry run: would kick, lets in)";
        return line + (decision.note() == null ? "" : " (" + decision.note() + ")") + " [" + DecisionLine.label(decision) + "]";
    }

    private void clear(String target, Sender sender) {
        OriginGateRuntime runtime = running(sender);
        if (runtime == null) return;
        String key;
        if (target.equalsIgnoreCase("all")) {
            key = "all";
        } else {
            Optional<InetAddress> address = Addresses.parse(target);
            if (!address.isPresent()) {
                sender.reply(target + " is not an IP address. Use an IP or all.");
                return;
            }
            key = Addresses.text(address.get());
        }
        runtime.clearCache(key).whenComplete((message, error) -> {
            if (error == null) sender.reply(message);
            else sender.reply("Could not clear the cache: " + Text.message(error));
        });
    }

    private OriginGateRuntime running(Sender sender) {
        OriginGateRuntime runtime = platform.runtime();
        if (runtime == null) sender.reply("OriginGate is not running. Fix the error in the console, then run /origingate reload.");
        return runtime;
    }

    private static boolean allowed(Sender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        sender.reply("You do not have permission to use this command.");
        return false;
    }

    private static void usage(Sender sender) {
        sender.reply("OriginGate " + OriginGateRuntime.VERSION + " commands:");
        if (sender.hasPermission(CHECK)) sender.reply("  /origingate check <player|ip> [refresh]");
        if (sender.hasPermission(STATUS)) sender.reply("  /origingate status");
        if (sender.hasPermission(RELOAD)) sender.reply("  /origingate reload");
        if (sender.hasPermission(CACHE)) sender.reply("  /origingate cache clear <ip|all>");
        if (sender.hasPermission(ALLOW)) sender.reply("  " + listUsage(ListEntry.Kind.ALLOW).substring("Usage: ".length()));
        if (sender.hasPermission(DENY)) sender.reply("  " + listUsage(ListEntry.Kind.DENY).substring("Usage: ".length()));
    }
}
