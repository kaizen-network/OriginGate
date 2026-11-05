![OriginGate: VPN, proxy, and country checks at login](https://kaizenmc.id/api/software/assets/origingate/docs/images/banner.webp)

Kick players who connect through a VPN or proxy, or from a country you do not allow. OriginGate runs on Bukkit, Spigot, Paper, Purpur, BungeeCord, Waterfall, and Velocity, and the check finishes before a player enters the world or reaches a backend server.

## Features

- Rules for VPNs, proxies (with allowed countries), hosting and datacenter networks, country allowlists or denylists, networks by AS number or name, and IP addresses or ranges you list.
- Every rule has its own bypass permissions, plus a global bypass by player, IP, or permission.
- IP data from proxycheck.io, IPHub, ip-api.com, IPinfo, or a local MaxMind GeoLite2 file. Combine them, with fallback to the next one.
- Each IP is looked up at most once every 30 days by default, saved in SQLite or MySQL/MariaDB.
- Dry-run mode shows who would be kicked, without kicking anyone.
- One console line per check, daily log files, and staff alerts in chat, including when a lookup provider stops answering. `origingate status` shows how lookups are doing.
- A lookups-per-minute limit keeps bot floods from using up your provider's daily quota.
- On Velocity, works with LuckPerms, ban plugins, Geyser, and [ConsentGate](https://modrinth.com/plugin/consentgate).

## Requirements

Each platform has its own file. Download the one for yours.

- Bukkit, Spigot, Paper, or Purpur: Minecraft 1.7.2 or newer, on Java 8 or newer. Folia is not supported.
- SQLite storage runs on Linux (x86_64 and aarch64, glibc and musl), Windows (x86_64 and aarch64), and macOS (x86_64 and aarch64). Elsewhere, use MySQL or MariaDB.
- BungeeCord or Waterfall: API 1.21-R0.4 or newer, on Java 8 or newer. Current BungeeCord and Waterfall builds need Java 17 or newer.
- Velocity 3.4.0 or newer, on Java 21 or newer.
- A proxycheck.io API key is optional. Without one, proxycheck.io allows 100 lookups per day; a free account raises that to 1,000.

See [compatibility](https://kaizenmc.id/software/origingate/compatibility) for tested builds and limits.

## Quick start

1. Put the JAR in the `plugins` folder and start the server or proxy.
2. In `config.yml` (in `plugins/OriginGate` on Bukkit and BungeeCord, or `plugins/origingate` on Velocity), add your API keys, turn on the rules you want, and start with `dry-run: true`.
3. Run `origingate reload`.
4. Watch the `WOULD-DENY` lines for a few days, then set `dry-run: false`.

OriginGate sends anonymous usage statistics to bStats, with no player names or IP addresses. See [what is sent and how to turn it off](https://kaizenmc.id/software/origingate/storage-and-privacy#bstats).

## Links

- [Documentation](https://kaizenmc.id/software/origingate)
- [Rollout checklist](https://kaizenmc.id/software/origingate/rollout)
- [Changelog](https://kaizenmc.id/software/origingate/changelog)
- [Source code](https://github.com/kaizen-network/OriginGate)

Made by [Kaizen Network](https://kaizenmc.id). Licensed under GPL-3.0-only.
