# Changelog

## 1.2.0 (unreleased)

### Upgrading from 1.1.x

Replace the JAR and restart. Your `config.yml` and `messages.yml` keep working unchanged and behave as before: every new setting is optional and off when missing, and missing new messages use the bundled text. The new `origingate_list` table is created automatically. To use the new features, copy the whole new section from the [default config](https://github.com/kaizen-network/OriginGate/blob/main/core/src/main/resources/config.yml): a `discord` or `accounts-per-ip` section with a missing inner key is refused.

### Added

- `allow-networks` rule: lets in networks you list by AS number when the `vpn`, `proxy`, or `hosting` rule would kick them, for example a school or mobile carrier that a provider wrongly flags. `deny-networks` and `country` still apply. Off by default.
- Storage for allow and deny list entries (table `origingate_list`, created automatically). Entries can expire, carry a note, and are kept in memory, so logins never wait on the database.
- The allow and deny lists are checked on every login before any lookup. A deny list entry kicks with the new `deny-list` rule and message (`kick.deny-list`, bundled text is used when missing). An allow list entry lets the player in with no lookup. `origingate status` shows the entry counts. The hourly cleanup deletes expired entries.
- Commands `origingate allow` and `origingate deny` with `add <player|ip|cidr> [duration] [note]`, `remove`, and `list [page]`, with separate permissions `origingate.command.allow` and `origingate.command.deny`. `deny add` disconnects matching players who are online on that server.
- `accounts-per-ip` rule: kicks a player when `max` players (1 to 100, default 3) are already online from exactly the same address, with the new `accounts-per-ip` message (`kick.accounts-per-ip`, bundled text is used when missing). It counts players on this server or proxy only, has no bypass permissions (use the allow list), and is off by default.
- Discord webhook: set `discord.webhook-url` to post kicks, bypasses, and lookup problems and recoveries to a Discord channel. Posts within 5 seconds are combined, up to 10 lines each, then `And <n> more.`. Player and network text is escaped and posts never ping anyone. IP addresses are left out unless `discord.show-ip` is `true`. The URL must be https, is never logged, and the webhook is off while it is empty. `origingate status` shows `Discord webhook: on` or `off`.

## 1.1.1 (2026-11-07)

### Fixed

- Renaming, deleting, or replacing the OriginGate JAR while the server runs no longer breaks lookups. The MySQL/MariaDB driver opened the JAR again for every new database connection, so once the file was gone every lookup failed, logins followed `on-lookup-failure` without a check, and `origingate reload` and `origingate cache clear` failed. The database drivers now load what they need at startup, and the default `config.yml` and `messages.yml` are kept in memory, so lookups, storage, and reload keep working until the next restart. Switching `storage.type` to `sqlite` with a reload while the JAR is gone still needs a restart; until then lookups are kept in memory only.
- A database driver error that is not a normal SQL error now pauses storage for 60 seconds and asks the lookup providers, like other storage errors, instead of failing the whole lookup. A reload that hits one now fails with a message and keeps the previous settings.
- The bundled MariaDB driver is no longer added to Java's shared driver list, where another plugin could pick it up and fail along with it.

## 1.1.0 (2026-11-06)

### Upgrading from 1.0.0

Replace the JAR and restart. Your `config.yml` and `messages.yml` keep working unchanged and behave as before: every new setting is optional and off when missing, and missing new messages use the bundled text. One exception: with SQLite storage on a system outside Linux (x86_64 and aarch64, glibc and musl), Windows (x86_64 and aarch64), and macOS (x86_64 and aarch64) (for example 32-bit, ARMv7, or FreeBSD), lookups are no longer saved, only kept in memory until a restart, and an error at startup says so; switch to MySQL or MariaDB there. To use the new features, copy the new settings from the [default config](https://github.com/kaizen-network/OriginGate/blob/main/core/src/main/resources/config.yml). On an offline-mode server, a new startup warning appears if you set `bypass.players` or `bypass.permissions`, and a backend behind BungeeCord IP forwarding gets one startup line; see [offline mode and proxies](docs/configuration.md#offline-mode-and-proxies). One change can kick a player that 1.0.0 let in: on IPv6, an address whose /64 neighbour was flagged is now turned away from memory without its own lookup. Saved ip-api lookups have no hosting flag until they are looked up again; run `origingate cache clear all` if you turn on the hosting rule with ip-api.

### Added

- `hosting` rule: kicks hosting and datacenter networks, where many VPNs and bots run without being flagged. Uses proxycheck.io's network type, or ip-api's `hosting` flag, which is now requested. Off by default. See [providers](docs/providers.md#hosting-networks).
- `deny-networks` rule: kicks networks you list by AS number or by text in the provider, organisation, or VPN operator name. Off by default.
- `lookup.max-lookups-per-minute`: a limit on provider lookups, so a bot flood cannot use up a provider's daily quota. Off by default; turn it on with `on-budget-exceeded: deny` when bot waves use up your lookups. Logins over it follow the new optional `lookup.on-budget-exceeded` (`allow` or `deny`), or `on-lookup-failure` when that is not set. See [flood protection](docs/configuration.md#flood-protection).
- Staff with `origingate.alerts` get one chat message when a lookup provider is paused (a refused key, a rate limit, or a used-up daily quota), fails 3 lookups in a row, or has one of several API keys refused, and when the lookup budget is reached, plus one message when that is over (for the budget, after a full minute without a login over it). A reload keeps this state. New `alerts.lookup-problem` and `alerts.lookup-recovered` in `messages.yml`.
- `origingate status` (permission `origingate.command.status`): lookups since start by source, the cache hit rate, failures, dropped lookups, the budget, and each provider's state, requests today, and paused API keys.
- Offline-mode notices where anyone can claim any name: a warning at startup and reload when you set `bypass.players` or `bypass.permissions` (the shipped per-rule bypass permissions do not count), and `caution="anyone can claim this name in offline mode"` on the log line and staff alert whenever a rule's bypass permission lets someone in (global bypasses are covered by the startup warning). A backend behind BungeeCord IP forwarding gets one startup line, since a reachable backend port lets anyone fake their address. Velocity modern forwarding from an online-mode proxy counts as safe.

### Changed

- New installs wait at most 2500 ms for a lookup (`wait-millis`), down from 5000. New installs only: an existing `config.yml` keeps its value.
- A lookup still queued when every login waiting for it has stopped waiting is dropped without asking a provider, so a burst of logins no longer spends quota on answers nobody uses. This happens only while the queue is backed up, also for lookups Bukkit starts in the background with nobody waiting; in normal traffic the late lookup still runs and fills the cache, as in 1.0.0.
- An IPv6 answer that a rule acts on now also covers the rest of its /64, so a player switching addresses inside a flagged /64 is turned away from memory without a new lookup. This can kick a player whose own address was never looked up, which 1.0.0 would have looked up on its own. Clean answers stay per address, since hosts may split one /64 between customers, and an address's own saved answer always comes before its /64's. When an address's own lookup cannot complete (queue full, timeout, budget, providers failing), the /64 answer decides instead of `on-lookup-failure`. Teredo and well-known NAT64 addresses are never shared.
- The plugin JARs include SQLite only for Linux (x86_64 and aarch64, glibc and musl), Windows (x86_64 and aarch64), and macOS (x86_64 and aarch64), which takes every JAR from about 14 MB to under 8 MB, below Hangar's 10 MB limit per file. On any other system, OriginGate keeps checking connections with lookups kept in memory only, and logs an error at startup that names the supported systems and points to MySQL or MariaDB. See [compatibility](docs/compatibility.md#sqlite).
- Releases on Hangar: the three JARs together are over Hangar's 15 MB limit per upload, so the largest is listed as a link to its GitHub release download and the others are uploaded.
- MySQL and MariaDB storage is tested against a real MariaDB 11.4 server, and GitHub Actions runs that test against a MariaDB container.
- The Modrinth listing names Purpur for the Bukkit JAR and Waterfall for the BungeeCord JAR.

### Fixed

- A SQLite file that cannot be opened (damaged, locked, or in a folder that cannot be written) no longer leaves OriginGate running without checks: it keeps checking connections with lookups kept in memory only, logs an error, and tries the file again on `origingate reload`. A mistake in `config.yml` still stops the checks until it is fixed, as before.
- Velocity: a `bypass-notice` that cannot be rendered no longer stops the staff alert for that bypass.

## 1.0.0 (2026-11-05)

### Added

- Anonymous usage statistics through [bStats](https://bstats.org) on Bukkit, BungeeCord, and Velocity. No player names, UUIDs, or IP addresses are sent. See [storage and privacy](docs/storage-and-privacy.md#bstats) for what is sent and how to turn it off.
- Releases are also published on [Hangar](https://hangar.papermc.io): the Bukkit JAR for Paper, the BungeeCord JAR for Waterfall, and the Velocity JAR.
- Waterfall 1.21 build 615 is tested with the BungeeCord JAR. See [compatibility](docs/compatibility.md).

### Changed

- Corrected the BungeeCord JAR's Java requirement: the plugin needs Java 8 or newer, and current BungeeCord and Waterfall builds themselves need Java 17 or newer. Earlier notes said Java 11.

## 0.2.0 (2026-10-29)

Adds Bukkit, Spigot, Paper, and BungeeCord. See [compatibility](docs/compatibility.md) for tested builds and limits.

### Added

- Bukkit, Spigot, and Paper support (`OriginGate-Bukkit-<version>.jar`), for Minecraft 1.7.2 or newer on Java 8 or newer. Lookups run during asynchronous pre-login, and players are kicked before they enter the world. Old offline-mode CraftBukkit asks for a reconnect on the first uncached check.
- BungeeCord support (`OriginGate-BungeeCord-<version>.jar`), for API 1.21-R0.4 or newer on Java 11 or newer. Players are kicked before their first backend connection. If another plugin sends a player to a server during the check, that server is used once the player is admitted.

### Changed

- Decision lines and daily log files are written in the background, so slow writes no longer delay logins. If writes fall behind, for example during a bot flood, excess lines are skipped and the number skipped is logged.
- The Velocity JAR uses the proxy's Adventure library instead of bundling its own.

## 0.1.0 (2026-09-28)

First release for Velocity.

### Added

- Login-time checks on Velocity: players are kicked before reaching any backend server.
- Rules: `deny-addresses`, `vpn`, `proxy` (with allowed countries), and `country` (allowlist or denylist), each with its own bypass permissions.
- Global bypass by player name, UUID, IP address or range, or permission.
- Lookup providers: proxycheck.io, IPHub, ip-api.com, IPinfo, and a local MaxMind GeoLite2 file with automatic download and updates. Providers can be combined, with fallback to the next one.
- Saved lookups in SQLite or MySQL/MariaDB, plus a memory cache.
- Dry-run mode, console logging levels, daily log files, and staff alerts.
- Commands: `check`, `reload`, and `cache clear`.
