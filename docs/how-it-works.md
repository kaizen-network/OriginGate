---
title: How it works
description: When the check runs, the order of checks, lookups and caching, failures, dry-run, and logging.
order: 7
---

# How it works

## When the check runs

OriginGate checks each player during Velocity's `LoginEvent`, without blocking the proxy's network threads. The login is held while the check runs. A kicked player is disconnected before Velocity connects them to any backend server.

BungeeCord uses an asynchronous `PostLoginEvent` hold and guards early backend connection requests: when another plugin connects the player to a server while the check runs, that request is cancelled (its callback sees `EVENT_CANCEL`), and its server is used once the player is admitted. Bukkit/Spigot/Paper fetches IP data during asynchronous pre-login, then applies the final decision and permission checks at `PlayerLoginEvent`, before world admission. Old offline-mode CraftBukkit may require a reconnect; see [compatibility](compatibility.md#platforms).

On Velocity, the check runs after permission plugins such as LuckPerms have loaded the player, so bypass permissions work. If another plugin already denied the login (a ban plugin, for example), OriginGate does nothing and makes no lookup. See [compatibility](compatibility.md) for permission timing on the other platforms.

## Order of checks

1. Global bypass: `bypass.players` (name or UUID), `bypass.addresses`, `bypass.permissions`.
2. Allow list entry (`origingate allow`): lets the player in with no lookup. A name entry gets the same offline-mode caution as `bypass.players`.
3. Deny list entry (`origingate deny`): the `deny-list` rule. No bypass permission applies.
4. `deny-addresses`, when enabled.
5. Private address skip (`lookup.skip-private-addresses`).
6. `accounts-per-ip`, when enabled: kicks the player when `max` players are already online from exactly the same address. No bypass permission applies; put players who share an address on the allow list. It counts players on this server or proxy only, so on a network install OriginGate on the proxy. Shared addresses are common (families, schools, mobile carriers), so keep it off or set it generously. Two logins at the same moment can both get in.
7. Lookup, waiting at most `lookup.wait-millis`.
8. `deny-networks`, `allow-networks` (skips `vpn`, `proxy`, and `hosting` for a network on its list), `vpn`, `proxy`, `hosting`, `country`, when enabled.

The first rule that matches decides: kick, or let in through that rule's bypass permission. Later rules are not checked. For example, a player with the VPN bypass who uses a VPN from a country outside the allowlist is let in, because the vpn rule matched first.

A `bypass.addresses` entry wins over a `deny-addresses` entry, and over the lists. Within the lists, an entry for the player's name or UUID comes before an entry for their address. When two address entries match, the allow entry wins. Expired entries are ignored at once and deleted by the hourly cleanup. Logins read the lists from memory only, so they never wait on the database.

## Lookups

1. Memory cache (bounded; the least recently used entries are dropped first).
2. OriginGate's table, for lookups younger than `max-age-days`.
3. The [lookup providers](providers.md).

- Only one request per IP runs at a time. Other logins from the same IP wait for it. `origingate check <ip> refresh` always makes its own request.
- Answers are kept per address, and an address's own answer in memory or storage always comes first. An IPv6 answer that a rule acts on (deny-networks, VPN, proxy, hosting, or a country your country rule turns away) is also kept for the address's whole /64, so the next address in that /64 is turned away from memory without a new lookup. This means a player can be kicked because another address in their /64 was flagged, without their own address being looked up; OriginGate 1.0.0 would have looked it up on its own. Home and mobile IPv6 users get at least a /64 and can switch addresses inside it at will. A clean answer is never shared this way, because some hosts split one /64 between many customers, and one customer's clean address must not let a neighbour in. If the address's own lookup cannot complete (queue full, timeout, budget used up, dropped, or providers failing), an answer kept for its /64 decides instead of `on-lookup-failure` or `on-budget-exceeded`, so a flood cannot help a VPN user who switches addresses inside a flagged /64. While storage cannot be read (for 60 seconds after a storage error), the address is looked up first, since it may have its own saved answer; the /64 answer is then only that fallback. Teredo addresses (`2001::/32`) and the well-known NAT64 prefixes (`64:ff9b::/96`, `64:ff9b:1::/48`) are never shared, since many unrelated users share one /64 there. NAT64 prefixes that a network picks for itself cannot be recognized.
- Lookups run on 4 worker threads with a queue of 256. When the queue is full, the lookup fails and `on-lookup-failure` applies.
- A lookup still waiting in the queue when every login that asked for it has stopped waiting (after `wait-millis`) is dropped without asking a provider while the queue is backed up (when it is picked up, at least as many lookups wait behind it as there are workers, which is 4), so a burst of logins does not spend quota on answers nobody uses. Without a backlog it still runs, as in 1.0.0, so the player's next join is answered from the cache. Lookups that Bukkit starts in the background with nobody waiting are treated the same way.
- With `max-lookups-per-minute`, provider lookups over that many in 60 seconds are not sent, and the login follows `on-budget-exceeded`. Answers from memory and storage do not count.
- A new result goes to the waiting login first and is saved afterwards, so a slow database does not hold the login.
- When a storage read or save fails, storage is skipped for 60 seconds and lookups go straight to the providers. A dead database does not use up `wait-millis` on every login.
- When no provider returns a country code, the lookup counts as failed and is not saved, since country rules need it. That IP is not asked again for 5 minutes, so a player reconnecting in a loop cannot drain your API quota. `refresh` and `cache clear` skip this pause. The pause is per address, not per /64: a paused lookup fails, and with `on-lookup-failure: allow` that would let the rest of the /64 in unchecked.

## Failures and dry-run

- `on-lookup-failure: allow` lets the player in when the lookup fails or times out. `deny` kicks them with the `lookup-failure` message.
- A login over `max-lookups-per-minute` follows `on-budget-exceeded`, or `on-lookup-failure` when that is not set.
- On reload, logins that already started finish with the previous settings.
- `dry-run: true` runs everything and logs `WOULD-DENY` instead of kicking. Bypass notices and the staff alerts for kicks and bypasses are not sent in dry-run. Lookup problem and recovery alerts are, since a failing provider matters while you test.

## Alerts and status

Staff with an alert permission get one chat message when lookups start failing, and one when they work again, so a long outage does not repeat itself:

- a provider is paused after refusing a request (HTTP 401, 403, or 429, or a `denied` status, such as a used-up daily quota)
- a provider fails 3 lookups in a row (timeouts or server errors). It is still asked on every lookup
- an API key is refused while the provider's other keys still work
- `max-lookups-per-minute` is reached. Its recovery is sent after a full minute without a login over the limit, so a steady flood sends one message, not one per freed slot

The same messages are logged to the console as `Alert: ...`. A reload keeps this state: an outage that goes on is not announced again, and its recovery still arrives. `origingate status` shows the counters, the budget, and each provider's state at any time.

## Logging

Each decision is one line, for example:

```
DENY rule=vpn player=Alex uuid=... ip=... provider="..." organisation="..." country="..." country_code=.. city="..." type="..." vpn=yes proxy=no source=proxycheck note="flagged as VPN"
```

Labels: `ALLOW`, `BYPASS`, `DENY`, `WOULD-DENY`. `source` is `memory`, `storage`, or the providers that answered, such as `maxmind+proxycheck`. Where names are not checked, a `BYPASS` line (a rule's bypass permission) gets `caution="anyone can claim this name in offline mode"`; see [offline mode and proxies](configuration.md#offline-mode-and-proxies).

`console-log` sets which lines reach the console. Each level includes the ones before it:

| Level | Shows |
| --- | --- |
| `none` | No login lines. Startup, reload, and errors still show |
| `kicks` | `DENY` and `WOULD-DENY` |
| `matches` (default) | Every login where a rule matched or the lookup failed: kicks, bypasses, and lookup failures that let the player in |
| `all` | Every login |
| `debug` | Every login, plus each step of the check |

`log-file: true` writes the same lines as `matches` to `plugins/origingate/logs/<date>.log`, whatever the console level is. Files older than `log-file-keep-days` are deleted.

Decision logs use a limited background queue so slow writes do not delay connection checks. If that queue fills, for example during a bot flood, excess entries are skipped and a warning with the number skipped is logged when writing resumes.
