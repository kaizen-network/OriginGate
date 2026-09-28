---
title: How it works
description: When the check runs, the order of checks, lookups and caching, failures, dry-run, and logging.
order: 7
---

# How it works

## When the check runs

OriginGate checks each player during Velocity's `LoginEvent`, without blocking the proxy's network threads. The login is held while the check runs. A kicked player is disconnected before Velocity connects them to any backend server.

The check runs after permission plugins such as LuckPerms have loaded the player, so bypass permissions work. If another plugin already denied the login (a ban plugin, for example), OriginGate does nothing and makes no lookup.

## Order of checks

1. Global bypass: `bypass.players` (name or UUID), `bypass.addresses`, `bypass.permissions`.
2. `deny-addresses`, when enabled.
3. Private address skip (`lookup.skip-private-addresses`).
4. Lookup, waiting at most `lookup.wait-millis`.
5. `vpn`, `proxy`, `country`, when enabled.

The first rule that matches decides: kick, or let in through that rule's bypass permission. Later rules are not checked. For example, a player with the VPN bypass who uses a VPN from a country outside the allowlist is let in, because the vpn rule matched first.

A `bypass.addresses` entry wins over a `deny-addresses` entry.

## Lookups

1. Memory cache (bounded; the least recently used entries are dropped first).
2. OriginGate's table, for lookups younger than `max-age-days`.
3. The [lookup providers](providers.md).

- Only one request per IP runs at a time. Other logins from the same IP wait for it. `origingate check <ip> refresh` always makes its own request.
- Lookups run on 4 worker threads with a queue of 256. When the queue is full, the lookup fails and `on-lookup-failure` applies.
- A new result goes to the waiting login first and is saved afterwards, so a slow database does not hold the login.
- When a storage read or save fails, storage is skipped for 60 seconds and lookups go straight to the providers. A dead database does not use up `wait-millis` on every login.
- When no provider returns a country code, the lookup counts as failed and is not saved, since country rules need it. That IP is not asked again for 5 minutes, so a player reconnecting in a loop cannot drain your API quota. `refresh` and `cache clear` skip this pause.

## Failures and dry-run

- `on-lookup-failure: allow` lets the player in when the lookup fails or times out. `deny` kicks them with the `lookup-failure` message.
- On reload, logins that already started finish with the previous settings.
- `dry-run: true` runs everything and logs `WOULD-DENY` instead of kicking. No chat notices or staff alerts are sent in dry-run.

## Logging

Each decision is one line, for example:

```
DENY rule=vpn player=Alex uuid=... ip=... provider="..." organisation="..." country="..." country_code=.. city="..." type="..." vpn=yes proxy=no source=proxycheck note="flagged as VPN"
```

Labels: `ALLOW`, `BYPASS`, `DENY`, `WOULD-DENY`. `source` is `memory`, `storage`, or the providers that answered, such as `maxmind+proxycheck`.

`console-log` sets which lines reach the console. Each level includes the ones before it:

| Level | Shows |
| --- | --- |
| `none` | No login lines. Startup, reload, and errors still show |
| `kicks` | `DENY` and `WOULD-DENY` |
| `matches` (default) | Every login where a rule matched or the lookup failed: kicks, bypasses, and lookup failures that let the player in |
| `all` | Every login |
| `debug` | Every login, plus each step of the check |

`log-file: true` writes the same lines as `matches` to `plugins/origingate/logs/<date>.log`, whatever the console level is. Files older than `log-file-keep-days` are deleted.
