![OriginGate: VPN, proxy, and country checks at login](docs/images/banner.webp)

# OriginGate

OriginGate checks where each player connects from on Bukkit/Spigot/Paper, BungeeCord, and Velocity. It can kick players who use a VPN or proxy, or who connect from a country you do not allow. Checks finish before world admission or the first backend connection.

- IP data from proxycheck.io, IPHub, ip-api.com, IPinfo, or a local MaxMind GeoLite2 file. You choose which, and can combine them.
- Lookups are saved (SQLite, or MySQL/MariaDB), so each IP is looked up at most once every 30 days by default.
- Every rule is optional and has its own bypass permissions.
- Dry-run mode shows what would happen without kicking anyone.

## Requirements

| Platform | Requirement | JAR |
| --- | --- | --- |
| Bukkit / Spigot / Paper | Minecraft 1.7.2 or newer, Java 8 or newer (also meet your server's Java requirement) | `OriginGate-Bukkit-<version>.jar` |
| BungeeCord | 1.21-R0.4 API or newer with asynchronous `PostLoginEvent`, Java 11 or newer | `OriginGate-BungeeCord-<version>.jar` |
| Velocity | 3.4.0 or newer, Java 21 or newer | `OriginGate-Velocity-<version>.jar` |

See [compatibility](docs/compatibility.md) for tested builds and the reconnect requirement on old offline-mode CraftBukkit.

A proxycheck.io API key is optional. Without one, proxycheck.io allows 100 lookups per day; a free account raises that to 1,000.

## Quick start

1. Choose the JAR for your platform and put it in its `plugins` folder. Bukkit and BungeeCord builds are available from source until their first release.
2. Start the server or proxy. OriginGate creates `config.yml` and `messages.yml` in `plugins/OriginGate` (Bukkit/BungeeCord) or `plugins/origingate` (Velocity).
3. In `config.yml`, add your API keys and turn on the rules you want. Start with `dry-run: true`.
4. Run `origingate reload` in the console.

## Documentation

Read the docs on the web at [kaizenmc.id/software/origingate](https://kaizenmc.id/software/origingate), or here on GitHub:

- [Overview](docs/index.md)
- [Installation](docs/installation.md)
- [Rollout checklist](docs/rollout.md)
- [Configuration](docs/configuration.md)
- [Messages](docs/messages.md)
- [Lookup providers](docs/providers.md)
- [How it works](docs/how-it-works.md)
- [Commands and permissions](docs/commands.md)
- [Storage and privacy](docs/storage-and-privacy.md)
- [Compatibility](docs/compatibility.md)
- [Changelog](CHANGELOG.md)

Working on OriginGate itself? See [contributing](docs/contributing/README.md).

## License

GPL-3.0-only. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
