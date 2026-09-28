![OriginGate: VPN, proxy, and country checks at login for Velocity](https://kaizenmc.id/api/software/assets/origingate/docs/images/banner.webp)

Kick players who connect through a VPN or proxy, or from a country you do not allow. The check runs during login on your Velocity proxy, so a kicked player never reaches your backend servers.

## Features

- Rules for VPNs, proxies (with allowed countries), country allowlists or denylists, and IP addresses or ranges you list.
- Every rule has its own bypass permissions, plus a global bypass by player, IP, or permission.
- IP data from proxycheck.io, IPHub, ip-api.com, IPinfo, or a local MaxMind GeoLite2 file. Combine them, with fallback to the next one.
- Each IP is looked up at most once every 30 days by default, saved in SQLite or MySQL/MariaDB.
- Dry-run mode shows who would be kicked, without kicking anyone.
- One console line per check, daily log files, and staff alerts in chat.
- Works with LuckPerms, ban plugins, Geyser, and [ConsentGate](https://modrinth.com/plugin/consentgate).

## Requirements

- Velocity 3.4.0 or newer, on Java 21 or newer.
- A proxycheck.io API key is optional. Without one, proxycheck.io allows 100 lookups per day; a free account raises that to 1,000.

## Quick start

1. Put the JAR in the proxy's `plugins` folder and start it.
2. In `plugins/origingate/config.yml`, add your API keys, turn on the rules you want, and start with `dry-run: true`.
3. Run `origingate reload`.
4. Watch the `WOULD-DENY` lines for a few days, then set `dry-run: false`.

## Links

- [Documentation](https://kaizenmc.id/software/origingate)
- [Rollout checklist](https://kaizenmc.id/software/origingate/rollout)
- [Changelog](https://kaizenmc.id/software/origingate/changelog)
- [Source code](https://github.com/kaizen-network/OriginGate)

Made by [Kaizen Network](https://kaizenmc.id). Licensed under GPL-3.0-only.
