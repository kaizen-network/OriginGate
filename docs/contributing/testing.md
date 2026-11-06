# Testing

## Unit tests

`.\gradlew.bat build` runs them. `core:test` covers:

- Config loading, bounds, unknown keys, paths, country codes, and redaction of passwords and keys
- The unchanged 1.0.0 `config.yml` and `messages.yml` (in `core/src/test/resources/upgrade/`) loading and reading the same as the current defaults apart from the new settings
- Rule order and first match, every bypass path (global permission, player name, UUID, address, per-rule permission, several permissions), deny-addresses before lookup, private-address skip, deny-networks by ASN and organisation text before the vpn rule, and the hosting rule
- Country matching by code, including `XK`
- Lookup order (memory, storage, provider), one request per IP, expiry, the answer arriving before the save, the 60-second storage pause, the 5-minute pause after a result without country, a refresh never joining a normal lookup, full queue
- Lookup failure and timeout in both `on-lookup-failure` modes, dry-run, console log levels
- IPv6: a flagged answer turning away its /64 neighbour without a lookup, a clean answer never shared, neighbours looked up at once getting their own lookups, the no-country pause per address
- Dropping a queued lookup once its login stopped waiting, but only while the queue is backed up, keeping it while any caller still waits, the lookups-per-minute budget with one alert and one recovery, `on-budget-exceeded` and its fallback to `on-lookup-failure`, and the status counters
- Provider health: one alert on a pause and none while it stays paused, one after 3 failures in a row, one recovery, refused API keys, and the `status` lines
- The offline-mode warning for each server mode (only owner-set `bypass.players` and `bypass.permissions` count; BungeeCord IP forwarding always gets one line), the caution on name and permission bypasses, and a real runtime sending one staff alert when a stub provider answers 429
- The SQLite error message on a system without a bundled native library
- CIDR matching for IPv4 and IPv6, and literal-only IP parsing (no DNS)
- SQLite storage, including a provider name with `'`, setup once an unreachable database answers, and a setup problem failing at startup
- A retired runtime still answering lookups after a reload
- proxycheck.io parsing from saved responses in `core/src/test/resources/proxycheck/`, and a local stub server for key rotation, refused keys, server errors, and timeouts
- Commands and reload against a real runtime and a local stub server

`business-8.8.8.8.json` and `hosting-1.1.1.1.json` are real v3 responses for public DNS addresses, fetched without a key. The other fixtures are written by hand from the documented v3 format and use documentation address ranges.

`platform-velocity:test` checks the shaded JAR (descriptor, relocations, notices, no probe classes), that placeholders render as plain text, that the database drivers read their service files from memory, and that the MariaDB driver and the bundled defaults still work after the JAR is deleted (skipped on Windows, which cannot delete an open file).

`platform-bukkit:test` checks pending connections, reconnect results, Java 8 bytecode, and duplicate-free packaging for Paper. `platform-bungeecord:test` checks one-time release of login holds and proxy packaging. All three artifact tests check that only the 8 listed SQLite native libraries are packed and that the JAR is under 10 MB.

With JDK 8 installed, run `gradlew :platform-bukkit:legacyRuntimeProbe` to exercise the packaged MaxMind IPv4/IPv6 reader and SQLite on Java 8. Optional `-PlegacyServerJar=<absolute-path-to-craftbukkit.jar>` places an old server first on the classpath to verify SQLite isolation.

## Bukkit and BungeeCord connection probes

`tools/run_platform_probe.py` copies a supplied server JAR into an isolated `.run/integration-<platform>` folder, binds to loopback, installs test-only permission/routing plugins, turns off bStats in `plugins/bStats/config.yml` (and opts out of Spigot's `plugins/PluginMetrics`), and checks allow/deny, bypasses, failures, dry-run, reload, and timeout behavior. BungeeCord sends its own statistics to `mcstats.spigotmc.org` with no setting to turn that off, so the probe starts it with `https.proxyHost` pointing at a local socket that drops every connection. It stops its own server afterwards. Use a disposable local server JAR, Python 3.11+, and JDK 25's `javac` on PATH. Select the server's Java runtime with `--java`.

```powershell
python tools/run_platform_probe.py bukkit --server path/to/craftbukkit.jar --bukkit-api path/to/bukkit-1.7.2-R0.3.jar --java path/to/java8/bin/java
python tools/run_platform_probe.py bungee --server path/to/BungeeCord.jar --java path/to/java21/bin/java
python tools/run_platform_probe.py paper --server path/to/paper.jar --bukkit-api path/to/bukkit-1.7.2-R0.3.jar --modules path/to/node_modules --client-version 1.21.8 --java path/to/java21/bin/java
```

Paper and newer Spigot tests use a local `minecraft-protocol` Node dependency through `--modules`. Choose a compatible `--client-version`. Paper 1.21.7 and 1.21.8 share a protocol. For simultaneous test servers, choose distinct `--port` and `--run-name` values. The Bungee probe also uses the next port for a dummy backend and checks that another plugin cannot route a denied player there.

Both probes also check the 1.1.0 changes: the offline-mode warning for the probe's `bypass.permissions` and the caution on a used bypass; the unchanged 1.0.0 `config.yml` and `messages.yml` loading; the hosting rule; the lookup budget with `on-lookup-failure` and `on-budget-exceeded`; one alert after 3 failures in a row and one recovery; one alert for a provider paused by HTTP 429, no request while it is paused, and one recovery after the 60-second pause; `origingate status`; and 12 logins at once from 12 loopback addresses (`127.0.0.10` to `127.0.0.21`): 4 lookups run, the next 4 are picked up while 4 or more wait behind them and are dropped without a request, and the last 4 still run. On Paper, a second client (`tools/platform_staff_client.cjs`) stays online with `origingate.alerts` and must receive the pause and recovery alerts in chat, and the server is restarted twice: behind Velocity modern forwarding there is no warning, behind BungeeCord IP forwarding there is one. The pause check waits 61 seconds.

## Docs check

```powershell
python tools/check_docs.py
python -m unittest discover -s tools -p "test_*.py"
```

`check_docs.py` checks the pages the website renders: front matter (`title`, `description`, `order`) on every page outside `contributing/`, relative links, images, and `#anchors` that resolve, and the changelog heading format. CI runs both.

## Remote database test

Opt-in, never part of `build`. Use a dedicated, disposable database whose name starts with `origingate_test_`; the test drops and creates tables in it.

```powershell
$env:OG_TEST_DB_ALLOW_WRITES = "true"
$env:OG_TEST_DB_DATABASE = "origingate_test_local"
$env:OG_TEST_DB_HOST = "127.0.0.1"; $env:OG_TEST_DB_PORT = "3306"
$env:OG_TEST_DB_USERNAME = "..."; $env:OG_TEST_DB_PASSWORD = "..."
$env:OG_TEST_DB_SSL_MODE = "disable"
.\gradlew.bat :core:remoteDatabaseTest
```

It checks table creation, a quoted provider name, and 200 concurrent writes to one IP from two storage instances.

## Loopback probe

`tools/run_velocity_probe.py` runs the built JAR on a real local Velocity with a stub lookup server. It needs Python 3.11+ and Java on PATH.

Set up `.run/velocity` (ignored by Git) once: put a Velocity JAR at `velocity.jar` and use this `velocity.toml` (the same as ConsentGate's test proxy):

```toml
config-version = "2.7"
bind = "127.0.0.1:25590"
online-mode = false
force-key-authentication = false
player-info-forwarding-mode = "NONE"
ping-passthrough = "DISABLED"
enable-player-address-logging = false

[servers]
backend = "127.0.0.1:25591"
try = ["backend"]

[forced-hosts]

[advanced]
compression-threshold = -1
login-ratelimit = 0
read-timeout = 30000

[query]
enabled = false
```

Then:

```powershell
.\gradlew.bat build :platform-velocity:probeJar
python tools/run_velocity_probe.py
```

`--port` sets another proxy port (the backend listener uses the next one); `velocity.toml` must match. The runner refuses a proxy not bound to `127.0.0.1:25590` (or the `--port` given). It replaces the JARs in `.run/velocity/plugins`, writes a probe `config.yml` with `skip-private-addresses: false`, turns off bStats in `plugins/bStats/config.txt`, starts the proxy, runs the checks, and stops it. A small Python client logs in with Java protocol 772 (1.21.8), and a listener on port 25591 stands in for the backend. Bypass permissions come from the test-only permission plugin (`plugins/origingate-probe-permissions/permissions.txt`, one `name permission` per line).

Checks:

1. A clean address joins and reaches the backend.
2. A VPN is kicked at login with its message, which does not show the IP.
3. The login is held while the lookup runs, with no backend contact before the decision.
4. The VPN bypass permission lets the player in.
5. A proxy is kicked.
6. The country allowlist kicks another country and lets a listed one in.
7. deny-addresses kicks without an API call.
8. dry-run lets a VPN in and logs `WOULD-DENY`.
9. A lookup failure with `allow` lets the player in.
10. A lookup failure and a timeout with `deny` kick the player.
11. An invalid reload is refused and the previous settings stay active.
12. A valid reload applies changed messages.
13. API keys are sent as a proper `key` parameter and used in turn.
14. The check command works, and kicks are written to the daily log file.
15. `console-log: kicks` prints a kick but not a normal join.
16. The shipped per-rule bypass permissions cause no offline-mode warning, `bypass.players` does, and a used bypass gets the caution.
17. The unchanged 1.0.0 `config.yml` and `messages.yml` load, and a private address joins without a lookup.
18. The hosting rule kicks a stubbed `Hosting` network and lets a residential one in.
19. Over `max-lookups-per-minute`, a login follows `on-lookup-failure`, or `on-budget-exceeded: deny` when set, with one alert.
20. 3 failed lookups in a row send one alert, and the next answer one recovery alert.
21. A provider refused with HTTP 429 sends one alert, is not asked while paused, and sends one recovery alert after the 60-second pause.
22. 12 logins at once from 12 loopback addresses: 8 lookups run and 4, picked up while the queue was backed up, are dropped.

`--no-sqlite` instead starts the proxy with an `os.arch` no SQLite native library exists for, and checks that logins are still checked with memory-only storage, also after a reload.

### ConsentGate compatibility run

```powershell
python tools/run_velocity_probe.py --consentgate path\to\ConsentGate-Velocity-0.2.0.jar --packetevents path\to\packetevents.jar
```

This also installs ConsentGate with a probe document, then checks that an allowed player still gets the consent dialog without backend contact, and that a VPN player is kicked at login and never reaches ConsentGate. The ConsentGate JARs are removed from the test proxy afterwards.
