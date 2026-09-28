# Changelog

## 0.1.0 (unreleased)

First release for Velocity.

### Added

- Login-time checks on Velocity: players are kicked before reaching any backend server.
- Rules: `deny-addresses`, `vpn`, `proxy` (with allowed countries), and `country` (allowlist or denylist), each with its own bypass permissions.
- Global bypass by player name, UUID, IP address or range, or permission.
- Lookup providers: proxycheck.io, IPHub, ip-api.com, IPinfo, and a local MaxMind GeoLite2 file with automatic download and updates. Providers can be combined, with fallback to the next one.
- Saved lookups in SQLite or MySQL/MariaDB, plus a memory cache.
- Dry-run mode, console logging levels, daily log files, and staff alerts.
- Commands: `check`, `reload`, and `cache clear`.
