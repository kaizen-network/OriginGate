# Testing

## Unit tests

`.\gradlew.bat build` runs them. `core:test` covers:

- Config loading, bounds, unknown keys, paths, country codes, and redaction of passwords and keys
- Rule order and first match, every bypass path (global permission, player name, UUID, address, per-rule permission, several permissions), deny-addresses before lookup, private-address skip
- Country matching by code, including `XK`
- Lookup order (memory, storage, provider), one request per IP, expiry, the answer arriving before the save, the 60-second storage pause, the 5-minute pause after a result without country, a refresh never joining a normal lookup, full queue
- Lookup failure and timeout in both `on-lookup-failure` modes, dry-run, console log levels
- CIDR matching for IPv4 and IPv6, and literal-only IP parsing (no DNS)
- SQLite storage, including a provider name with `'`, setup once an unreachable database answers, and a setup problem failing at startup
- A retired runtime still answering lookups after a reload
- proxycheck.io parsing from saved responses in `core/src/test/resources/proxycheck/`, and a local stub server for key rotation, refused keys, server errors, and timeouts
- Commands and reload against a real runtime and a local stub server

`business-8.8.8.8.json` and `hosting-1.1.1.1.json` are real v3 responses for public DNS addresses, fetched without a key. The other fixtures are written by hand from the documented v3 format and use documentation address ranges.

`platform-velocity:test` checks the shaded JAR (descriptor, relocations, notices, no probe classes) and that placeholders render as plain text.

`platform-bukkit:test` checks pending connections, reconnect results, Java 8 bytecode, and duplicate-free packaging for Paper. `platform-bungeecord:test` checks one-time release of login holds and proxy packaging.

With JDK 8 installed, run `gradlew :platform-bukkit:legacyRuntimeProbe` to exercise the packaged MaxMind IPv4/IPv6 reader and SQLite on Java 8. Optional `-PlegacyServerJar=<absolute-path-to-craftbukkit.jar>` places an old server first on the classpath to verify SQLite isolation.

## Bukkit and BungeeCord connection probes

`tools/run_platform_probe.py` copies a supplied server JAR into an isolated `.run/integration-<platform>` folder, binds to loopback, installs test-only permission/routing plugins, and checks allow/deny, bypasses, failures, dry-run, reload, and timeout behavior. It stops its own server afterwards. Use a disposable local server JAR, Python 3.11+, and JDK 25's `javac` on PATH. Select the server's Java runtime with `--java`.

```powershell
python tools/run_platform_probe.py bukkit --server path/to/craftbukkit.jar --bukkit-api path/to/bukkit-1.7.2-R0.3.jar --java path/to/java8/bin/java
python tools/run_platform_probe.py bungee --server path/to/BungeeCord.jar --java path/to/java21/bin/java
python tools/run_platform_probe.py paper --server path/to/paper.jar --bukkit-api path/to/bukkit-1.7.2-R0.3.jar --modules path/to/node_modules --client-version 1.21.8 --java path/to/java21/bin/java
```

Paper and newer Spigot tests use a local `minecraft-protocol` Node dependency through `--modules`. Choose a compatible `--client-version`. Paper 1.21.7 and 1.21.8 share a protocol. For simultaneous test servers, choose distinct `--port` and `--run-name` values. The Bungee probe also uses the next port for a dummy backend and checks that another plugin cannot route a denied player there.

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

Set up `.run/velocity` (ignored by Git) once: put a Velocity JAR at `velocity.jar`, disable bStats with `plugins/bStats/config.txt` containing `enabled=false`, and use this `velocity.toml` (the same as ConsentGate's test proxy):

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

The runner refuses a proxy not bound to `127.0.0.1:25590`. It replaces the JARs in `.run/velocity/plugins`, writes a probe `config.yml` with `skip-private-addresses: false`, starts the proxy, runs the checks, and stops it. A small Python client logs in with Java protocol 772 (1.21.8), and a listener on port 25591 stands in for the backend. Bypass permissions come from the test-only permission plugin (`plugins/origingate-probe-permissions/permissions.txt`, one `name permission` per line).

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

### ConsentGate compatibility run

```powershell
python tools/run_velocity_probe.py --consentgate path\to\ConsentGate-Velocity-0.2.0.jar --packetevents path\to\packetevents.jar
```

This also installs ConsentGate with a probe document, then checks that an allowed player still gets the consent dialog without backend contact, and that a VPN player is kicked at login and never reaches ConsentGate. The ConsentGate JARs are removed from the test proxy afterwards.
