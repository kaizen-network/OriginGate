---
title: Overview
description: OriginGate kicks VPN, proxy, and out-of-country players at login on Velocity, before they reach your servers.
order: 1
---

# OriginGate

OriginGate is a Velocity plugin that checks where each player connects from. It can kick players who use a VPN or proxy, or who connect from a country you do not allow. The check runs during login, so a kicked player never reaches your backend servers.

## Features

- IP data from proxycheck.io, IPHub, ip-api.com, IPinfo, or a local MaxMind GeoLite2 file. You choose which, and can combine them. See [providers](providers.md).
- Lookups are saved (SQLite, or MySQL/MariaDB), so each IP is looked up at most once every 30 days by default.
- Every rule is optional and has its own bypass permissions.
- Dry-run mode shows what would happen without kicking anyone.
- One console line per check, plus optional daily log files.

## What gets checked

First, without looking anything up:

1. **Bypass list:** players, permissions, or IP addresses in `bypass` are let in right away.
2. **deny-addresses rule:** IP addresses or ranges you list are kicked.
3. **Private addresses** (LAN, localhost) are let in, since there is nothing to look up.

Then the IP is looked up (from memory, storage, or the providers), and the rules are checked in this order:

4. **vpn rule:** the IP belongs to a VPN.
5. **proxy rule:** the IP is a proxy, from a country not in `allowed-countries`.
6. **country rule:** the country is not on your allowlist (or is on your denylist).

The first rule that matches decides. A player with one of that rule's bypass permissions is let in and gets a short chat message. Everyone else is kicked with that rule's message from `messages.yml`.

If the lookup fails or takes longer than `wait-millis` (5 seconds by default), `on-lookup-failure` decides: `allow` (default) or `deny`.

See [how it works](how-it-works.md) for details.

## Start here

- [Installation](installation.md)
- [Rollout checklist](rollout.md)
- [Configuration](configuration.md)
- [Commands and permissions](commands.md)
