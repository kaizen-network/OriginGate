package io.github.origingate.core.lookup;

import io.github.origingate.core.Log;
import io.github.origingate.core.Text;
import io.github.origingate.core.rules.Countries;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers one IP from the configured providers: the country job from {@code country-from}, then the VPN job
 * from {@code vpn-from}, each asking providers in order until one answers. A provider that already answered
 * in this lookup is not asked again. A provider that refuses its key or rate-limits is skipped for
 * {@link #REFUSED_PAUSE}. No new provider is asked once the time budget has passed.
 */
public final class ProviderChain {
    public static final Duration REFUSED_PAUSE = Duration.ofSeconds(60);

    /** Thrown when providers answered but none had a country code. */
    public static final class NoCountryException extends LookupException {
        public NoCountryException(String message) { super(message); }
    }

    public record Answer(IpInfo info, AnsweredBy answeredBy) { }

    private record Named(String name, IpInfo info) { }

    private final List<LookupProvider> countryFrom;
    private final List<LookupProvider> vpnFrom;
    private final Duration budget;
    private final Clock clock;
    private final Log log;
    private final Map<String, Instant> pausedUntil = new ConcurrentHashMap<>();

    /** {@code budget} is the longest time one lookup may spend; no new provider is asked after it. */
    public ProviderChain(List<LookupProvider> countryFrom, List<LookupProvider> vpnFrom, Duration budget, Clock clock, Log log) {
        if (countryFrom.isEmpty()) throw new IllegalArgumentException("countryFrom needs at least one provider");
        this.countryFrom = List.copyOf(countryFrom);
        this.vpnFrom = List.copyOf(vpnFrom);
        this.budget = budget;
        this.clock = clock;
        this.log = log;
    }

    public Answer lookup(String ip) throws LookupException {
        Instant deadline = clock.instant().plus(budget);
        Map<String, IpInfo> answers = new HashMap<>();
        Set<String> failed = new HashSet<>();
        List<String> problems = new ArrayList<>();
        Named country = first(ip, countryFrom, true, answers, failed, problems, deadline);
        if (country == null) {
            String message = "No provider returned a country for " + ip + ": " + String.join("; ", problems);
            // Every provider that answered had no country, so asking again soon would likely give the same.
            if (!answers.isEmpty()) throw new NoCountryException(message);
            throw new LookupException(message);
        }
        if (vpnFrom.isEmpty()) return new Answer(merge(ip, country.info(), null), new AnsweredBy(country.name(), null));
        Named vpn = first(ip, vpnFrom, false, answers, failed, problems, deadline);
        if (vpn == null) {
            throw new LookupException("No provider answered the VPN check for " + ip + ": " + String.join("; ", problems));
        }
        return new Answer(merge(ip, country.info(), vpn.info()), new AnsweredBy(country.name(), vpn.name()));
    }

    /**
     * The first provider in {@code providers} with a usable answer, or null. Failures are added to {@code problems},
     * and a provider that already failed in this lookup is not asked again.
     */
    private Named first(String ip, List<LookupProvider> providers, boolean needsCountry, Map<String, IpInfo> answers,
                        Set<String> failed, List<String> problems, Instant deadline) {
        for (LookupProvider provider : providers) {
            String name = provider.name();
            if (failed.contains(name)) continue;
            IpInfo info = answers.get(name);
            if (info == null) {
                Instant now = clock.instant();
                Instant paused = pausedUntil.get(name);
                if (paused != null && now.isBefore(paused)) {
                    problems.add(name + " is skipped for now after a refused key or rate limit");
                    continue;
                }
                if (!now.isBefore(deadline)) {
                    problems.add("no time left to ask " + name);
                    return null;
                }
                log.debug("Asking " + name + " about " + ip);
                try {
                    info = provider.lookup(ip);
                } catch (KeyRejectedException ex) {
                    pausedUntil.put(name, clock.instant().plus(REFUSED_PAUSE));
                    log.warn(name + " refused the request (" + ex.getMessage() + "), skipping it for "
                            + REFUSED_PAUSE.toSeconds() + " seconds", null);
                    problems.add(name + ": " + ex.getMessage());
                    failed.add(name);
                    continue;
                } catch (LookupException | RuntimeException ex) {
                    problems.add(name + ": " + Text.message(ex));
                    failed.add(name);
                    continue;
                }
                answers.put(name, info);
            }
            if (needsCountry && Countries.normalize(info.countryCode()).isEmpty()) {
                problems.add(name + " had no country");
                continue;
            }
            return new Named(name, info);
        }
        return null;
    }

    /** Country fields from the country answer; flags, type, and operator from the VPN answer; network fields from either. */
    static IpInfo merge(String ip, IpInfo country, IpInfo vpn) {
        String code = Countries.normalize(country.countryCode()).orElseThrow();
        if (vpn == null) {
            return new IpInfo(ip, country.provider(), country.organisation(), null, country.city(), country.region(),
                    country.country(), code, country.asn(), false, false, null, country.checkedAt());
        }
        return new IpInfo(ip, or(vpn.provider(), country.provider()), or(vpn.organisation(), country.organisation()),
                vpn.operatorName(), country.city(), country.region(), country.country(), code,
                or(vpn.asn(), country.asn()), vpn.vpn(), vpn.proxy(), vpn.type(), country.checkedAt());
    }

    private static String or(String first, String second) {
        return first != null ? first : second;
    }

    /** For the startup line: "country from maxmind, proxycheck | vpn from proxycheck, iphub". */
    public String describe() {
        return "country from " + names(countryFrom) + " | vpn from " + (vpnFrom.isEmpty() ? "none" : names(vpnFrom));
    }

    private static String names(List<LookupProvider> providers) {
        return String.join(", ", providers.stream().map(LookupProvider::name).toList());
    }
}
