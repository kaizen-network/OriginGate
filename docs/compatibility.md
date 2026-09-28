---
title: Compatibility
description: Tested Velocity versions, plugins that work alongside OriginGate, and known limits.
order: 10
---

# Compatibility

## Tested setups

| Setup | Result |
| --- | --- |
| Velocity 3.4.0 build 563, Java 25 | All automated login checks pass: allowed join, VPN and proxy kicks, bypass, country rules, deny-addresses, dry-run, lookup failures, reload, API key rotation, commands, and log files |
| Velocity-CTD 4.2.1 with LuckPerms and ConsentGate 0.2.0, real proxycheck.io API | Real Java joins: allowed, VPN let in with a bypass permission, VPN kicked without it, lookup reused from storage |

MySQL and MariaDB storage has an automated test, but it has not yet been run against a real MySQL or MariaDB server. SQLite is fully tested.

## Other plugins

| Plugin | Notes |
| --- | --- |
| LuckPerms | Loads permissions before OriginGate's check, so bypass permissions work |
| Ban and login plugins | Plugins that deny at `LoginEvent` with a higher priority run first. OriginGate skips a login that is already denied. |
| [ConsentGate](https://github.com/kaizen-network/ConsentGate) | Works together. A player kicked by OriginGate never reaches the consent screen, and no consent record is written. Players who are let in see the consent screen as usual. The two plugins share no code, data folder, or tables. |
| Geyser and Floodgate | Bedrock players must show their own IP address, not Geyser's local address. See the [rollout checklist](rollout.md#check-the-ip-address). |
| Proxy protection services | The real player IP must be passed (PROXY protocol or the service's plugin) before `LoginEvent`. See the [rollout checklist](rollout.md#check-the-ip-address). |

## Platforms

OriginGate runs on Velocity only. Paper and BungeeCord are not supported.
