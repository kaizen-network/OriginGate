# Changelog

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
