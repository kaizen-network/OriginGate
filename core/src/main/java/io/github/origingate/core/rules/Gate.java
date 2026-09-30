package io.github.origingate.core.rules;

import io.github.origingate.core.Log;
import io.github.origingate.core.Text;
import io.github.origingate.core.config.OriginGateConfig;
import io.github.origingate.core.config.OriginGateConfig.CountryMode;
import io.github.origingate.core.config.OriginGateConfig.FailureMode;
import io.github.origingate.core.lookup.IpInfo;
import io.github.origingate.core.lookup.LookupService;
import io.github.origingate.core.net.AddressRange;
import io.github.origingate.core.net.Addresses;
import io.github.origingate.core.rules.Decision.Outcome;

import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Decides whether a connection may join. Order: global bypass, deny-addresses, private-address skip,
 * lookup, then the vpn, proxy, and country rules. The first rule that matches decides.
 */
public final class Gate {
    private final OriginGateConfig config;
    private final LookupService lookups;
    private final Log log;

    public Gate(OriginGateConfig config, LookupService lookups, Log log) {
        this.config = config;
        this.lookups = lookups;
        this.log = log;
    }

    /** Runs every check. The future never completes exceptionally; failures follow {@code on-lookup-failure}. */
    public CompletableFuture<Decision> check(LoginAttempt attempt) {
        Optional<Decision> early;
        try {
            early = beforeLookup(attempt);
        } catch (RuntimeException ex) {
            return CompletableFuture.completedFuture(failed(attempt, ex));
        }
        if (early.isPresent()) return CompletableFuture.completedFuture(early.get());
        String ip = Addresses.text(attempt.address());
        CompletableFuture<LookupService.Result> lookup;
        try {
            lookup = lookups.lookup(ip, false);
        } catch (RuntimeException ex) {
            return CompletableFuture.completedFuture(failed(attempt, ex));
        }
        return io.github.origingate.core.util.Futures.timeout(lookup, config.lookup().waitMillis(), TimeUnit.MILLISECONDS)
                .handle((result, error) -> finish(attempt, result, error));
    }

    /** Final identity and permissions are checked even when an earlier lookup failed. */
    public Decision finish(LoginAttempt attempt, LookupService.Result result, Throwable error) {
        try {
            Optional<Decision> early = beforeLookup(attempt);
            if (early.isPresent()) return early.get();
            if (error != null) return failed(attempt, error);
            if (result == null) return failed(attempt, new IllegalStateException("No lookup result"));
            return afterLookup(attempt, result);
        } catch (RuntimeException ex) {
            return failed(attempt, ex);
        }
    }

    /** Checks that need no lookup. Empty means a lookup is needed. */
    public Optional<Decision> beforeLookup(LoginAttempt attempt) {
        InetAddress address = attempt.address();
        OriginGateConfig.Bypass bypass = config.bypass();
        if (bypass.players().contains(attempt.username().toLowerCase(Locale.ROOT))
                || (attempt.uuid() != null && bypass.players().contains(attempt.uuid().toString()))) {
            return Optional.of(allow("player is on the bypass list", null));
        }
        if (contains(bypass.addresses(), address)) return Optional.of(allow("address is on the bypass list", null));
        if (attempt.hasAny(bypass.permissions())) return Optional.of(allow("player has a global bypass permission", null));

        OriginGateConfig.AddressRule deny = config.rules().denyAddresses();
        if (deny.enabled() && contains(deny.list(), address)) {
            return Optional.of(matched(attempt, Rule.DENY_ADDRESSES, deny.bypassPermissions(), null, "address is on the deny list"));
        }
        if (config.lookup().skipPrivateAddresses() && Addresses.isPrivate(address)) {
            return Optional.of(allow("private address, lookup skipped", null));
        }
        return Optional.empty();
    }

    /** Applies the vpn, proxy, and country rules to a finished lookup. */
    public Decision afterLookup(LoginAttempt attempt, LookupService.Result result) {
        IpInfo info = result.info();
        OriginGateConfig.Rules rules = config.rules();
        log.debug("Is " + info.ip() + " a VPN? " + yesNo(info.vpn()));
        if (rules.vpn().enabled() && info.vpn()) {
            return matched(attempt, Rule.VPN, rules.vpn().bypassPermissions(), result, "flagged as VPN");
        }
        log.debug("Is " + info.ip() + " a proxy? " + yesNo(info.proxy()));
        if (rules.proxy().enabled() && info.proxy() && !Countries.matchesAny(info, rules.proxy().allowedCountries())) {
            return matched(attempt, Rule.PROXY, rules.proxy().bypassPermissions(), result, "flagged as proxy");
        }
        OriginGateConfig.CountryRule country = rules.country();
        if (country.enabled()) {
            boolean listed = Countries.matchesAny(info, country.countries());
            log.debug("Is " + info.ip() + " in the country list? " + yesNo(listed));
            if (country.mode() == CountryMode.ALLOWLIST ? !listed : listed) {
                return matched(attempt, Rule.COUNTRY, country.bypassPermissions(), result,
                        country.mode() == CountryMode.ALLOWLIST ? "country not on the allowlist" : "country on the denylist");
            }
        }
        return allow("no rule matched", result);
    }

    private Decision matched(LoginAttempt attempt, Rule rule, List<String> bypassPermissions,
                             LookupService.Result result, String note) {
        boolean bypassed = attempt.hasAny(bypassPermissions);
        log.debug("Rule " + rule.id() + " matched (" + note + "). Bypass permission? " + yesNo(bypassed));
        return new Decision(bypassed ? Outcome.BYPASS : Outcome.DENY, rule, note, result, config.dryRun());
    }

    private Decision allow(String note, LookupService.Result result) {
        return new Decision(Outcome.ALLOW, null, note, result, config.dryRun());
    }

    private Decision failed(LoginAttempt attempt, Throwable error) {
        String reason = Text.unwrap(error) instanceof TimeoutException
                ? "lookup took longer than " + config.lookup().waitMillis() + " ms"
                : "lookup failed: " + Text.message(error);
        boolean deny = config.lookup().onFailure() == FailureMode.DENY;
        log.debug("Lookup for " + attempt.username() + " failed (" + reason + "), on-lookup-failure is " + (deny ? "deny" : "allow"));
        return new Decision(deny ? Outcome.DENY : Outcome.ALLOW, Rule.LOOKUP_FAILURE, reason, null, config.dryRun());
    }

    private static boolean contains(List<AddressRange> ranges, InetAddress address) {
        for (AddressRange range : ranges) if (range.contains(address)) return true;
        return false;
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    public OriginGateConfig config() {
        return config;
    }

    public LookupService lookups() {
        return lookups;
    }
}
