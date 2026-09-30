---
title: Installation
description: Choose your platform, install OriginGate, and turn on your first rules.
order: 2
---

# Installation

## Requirements

| Platform | Requirement | JAR |
| --- | --- | --- |
| Bukkit / Spigot / Paper | Minecraft 1.7.2 or newer, Java 8 or newer (also meet your server's Java requirement) | `OriginGate-Bukkit-<version>.jar` |
| BungeeCord | 1.21-R0.4 API or newer with asynchronous `PostLoginEvent`, Java 11 or newer | `OriginGate-BungeeCord-<version>.jar` |
| Velocity | 3.4.0 or newer, Java 21 or newer | `OriginGate-Velocity-<version>.jar` |

See [compatibility](compatibility.md) for tested builds and the reconnect requirement on old offline-mode CraftBukkit.

A proxycheck.io API key is optional. Without one, proxycheck.io allows 100 lookups per day; a free account raises that to 1,000. Other providers are optional; see [providers](providers.md).

SQLite and MySQL/MariaDB support are bundled. On a proxy network, install at the proxy entry point. If installed on a backend instead, configure trusted IP forwarding so OriginGate sees the player's actual IP.

## Install

1. Choose the JAR above for your platform. Use a matching [release](https://github.com/kaizen-network/OriginGate/releases), or [build from source](contributing/development.md). The new Bukkit and BungeeCord builds have not been released yet.
2. Put it in the server or proxy's `plugins` folder and start it. Settings appear in `plugins/OriginGate` on Bukkit/BungeeCord, or `plugins/origingate` on Velocity.
3. In `config.yml`, add your API keys and turn on the rules you want. Setting `dry-run: true` for the first days is a good idea.
4. Run `origingate reload` in the console.

Each check prints one line in the console, for example:

```
DENY rule=vpn player=ExamplePlayer ip=203.0.113.7 provider="Example Hosting" country="Netherlands" ... note="flagged as VPN"
```

`console-log` in `config.yml` sets how many of these lines you see. See [logging](how-it-works.md#logging).

Before kicking real players, follow the [rollout checklist](rollout.md).

## Updating

Stop the server or proxy, replace the JAR, and start it again. Your `config.yml` and `messages.yml` are kept, so new settings are not added to them automatically. Compare with the [default config](https://github.com/kaizen-network/OriginGate/blob/main/core/src/main/resources/config.yml) and [default messages](https://github.com/kaizen-network/OriginGate/blob/main/core/src/main/resources/messages.yml), and check the [changelog](https://github.com/kaizen-network/OriginGate/blob/main/CHANGELOG.md).
