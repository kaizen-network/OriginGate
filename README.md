# OriginGate

OriginGate is a Velocity plugin that checks where each player connects from. It can kick players who use a VPN or proxy, or who connect from a country you do not allow. The check runs during login, so a kicked player never reaches your backend servers.

- IP data from proxycheck.io, IPHub, ip-api.com, IPinfo, or a local MaxMind GeoLite2 file. You choose which, and can combine them.
- Lookups are saved (SQLite, or MySQL/MariaDB), so each IP is looked up at most once every 30 days by default.
- Every rule is optional and has its own bypass permissions.
- Dry-run mode shows what would happen without kicking anyone.

## Requirements

- Velocity 3.4.0 or newer, on Java 21 or newer.
- A proxycheck.io API key is optional. Without one, proxycheck.io allows 100 lookups per day; a free account raises that to 1,000.

## Quick start

1. Download `OriginGate-Velocity-<version>.jar` from [releases](https://github.com/kaizen-network/OriginGate/releases) and put it in the proxy's `plugins` folder.
2. Start the proxy. OriginGate creates `plugins/origingate/config.yml` and `messages.yml`.
3. In `config.yml`, add your API keys and turn on the rules you want. Start with `dry-run: true`.
4. Run `origingate reload` in the console.

## Documentation

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
