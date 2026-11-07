---
title: Overview
description: OriginGate kicks VPN, proxy, and out-of-country players at login on Bukkit, Spigot, Paper, BungeeCord, and Velocity.
order: 1
---

# OriginGate

OriginGate checks where each player connects from on Bukkit/Spigot/Paper, BungeeCord, and Velocity. It can kick players who use a VPN or proxy, or who connect from a country you do not allow. Checks finish before world admission or the first backend connection.

## Features

- IP data from proxycheck.io, IPHub, ip-api.com, IPinfo, or a local MaxMind GeoLite2 file. You choose which, and can combine them. See [providers](providers.md).
- Lookups are saved (SQLite, or MySQL/MariaDB), so each IP is looked up at most once every 30 days by default.
- Every rule is optional. Most have their own bypass permissions; the allow list and deny list, with commands to manage them, cover the rest.
- Dry-run mode shows what would happen without kicking anyone.
- One console line per check, plus optional daily log files.
- Optional Discord webhook for kicks and bypasses.
- Staff alerts in chat when a lookup provider stops answering, and `origingate status` to see how lookups are doing.

## What gets checked

First, without looking anything up:

1. **Bypass list:** players, permissions, or IP addresses in `bypass` are let in right away.
2. **Allow list:** a player, address, or range added with `origingate allow add` is let in with no lookup.
3. **deny-list rule:** a player, address, or range added with `origingate deny add` is kicked. It has no bypass permissions.
4. **deny-addresses rule:** IP addresses or ranges you list in `config.yml` are kicked.
5. **Private addresses** (LAN, localhost) are let in, since there is nothing to look up.
6. **accounts-per-ip rule:** the address already has `max` players online. It has no bypass permissions; use the allow list.

Then the IP is looked up (from memory, storage, or the providers), and the rules are checked in this order:

7. **deny-networks rule:** the IP's network (AS number or organisation) is on your list.
8. **allow-networks rule:** the IP's AS number is on your list. This lets in a network that the vpn, proxy, or hosting rule would kick. It is an allow rule, with no bypass permissions.
9. **vpn rule:** the IP belongs to a VPN.
10. **proxy rule:** the IP is a proxy, from a country not in `allowed-countries`.
11. **hosting rule:** the IP is on a hosting or datacenter network.
12. **country rule:** the country is not on your allowlist (or is on your denylist).

The first rule that matches decides. A player with one of that rule's bypass permissions is let in and gets a short chat message. Everyone else is kicked with that rule's message from `messages.yml`.

If the lookup fails or takes longer than `wait-millis` (2.5 seconds in a new config), `on-lookup-failure` decides: `allow` (default) or `deny`.

See [how it works](how-it-works.md) for details.

## Start here

- [Installation](installation.md)
- [Rollout checklist](rollout.md)
- [Configuration](configuration.md)
- [Commands and permissions](commands.md)
