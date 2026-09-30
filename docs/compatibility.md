---
title: Compatibility
description: Tested server and proxy versions, login behavior, and known limits.
order: 10
---

# Compatibility

## Tested setups

| Setup | Result |
| --- | --- |
| CraftBukkit 1.7.2-R0.3 build 3020, Java 8 | Local offline-mode logins pass allow/deny, global permission bypass, dry-run, failure policies, reload, and timeout checks. The first uncached check requires a reconnect. |
| Spigot 1.8.8, git-Spigot-e4d4710-e1ebe52, Java 8 | The same local login checks pass using the normal asynchronous login flow. |
| Spigot 1.12.2, git-Spigot-642f6d2-6103339, Java 8 | The same local login checks pass using the normal asynchronous login flow. |
| Paper 1.21.7 build 32, Java 21 | Local login checks pass allow/deny before world entry, global permission bypass, dry-run, failure policies, reload, and timeout. |
| BungeeCord build 2100, Java 21 | Local login checks pass; denied players never reach the test backend. Includes permission bypass, failure policies, dry-run, reload, and timeout. |
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

Use the Bukkit JAR for Bukkit, Spigot, and Paper. It compiles against the Bukkit 1.7.2 API and targets Java 8. This does not mean every historical server build has been tested. Folia is not supported. BungeeCord needs the asynchronous `PostLoginEvent` available in API 1.21-R0.4 or newer; historical BungeeCord builds are not supported.

On Bukkit, lookup work runs during asynchronous pre-login. UUID and permission bypasses are checked again at `PlayerLoginEvent`, so a lookup may already have happened before those bypasses are known. Configure permission plugins to supply permissions by that event. OriginGate preserves earlier plugin denials.

Unpatched CraftBukkit 1.7.2 does not fire asynchronous pre-login in offline mode. In that setup, an uncached lookup runs in the background and the player receives a reconnect prompt before entering the world. Their next attempt uses the completed result, including the configured lookup-failure policy. Dry-run does not require the reconnect. Spigot/Paper builds that fire asynchronous pre-login use the normal single-login flow.

The new adapters have local permission-fixture tests. Live LuckPerms and Geyser/Floodgate combinations on these adapters have not yet been verified. The plugin compatibility results above were established on Velocity.

Recent Paper versions warn that plugins listening to `PlayerLoginEvent` make the reconfiguration API unavailable. OriginGate uses that event for final permission checks, so plugins that require Paper reconfiguration need separate compatibility testing.

MaxMind uses reader 4.2.0 on Java 17 or newer and reader 2.1.0 on Java 8 through 16. The older reader lacks later decoder fixes. Use genuine MaxMind database files, and prefer Java 17 or newer when your server supports it.
