# Development

## Build

Use JDK 25 to run the Gradle wrapper. Core, shared messages, Bukkit, and BungeeCord sources target Java 8 bytecode; Velocity targets Java 21. BungeeCord itself requires Java 11 or newer. Server smoke tests also need their corresponding Java runtimes.

```powershell
.\gradlew.bat build
```

On Linux or macOS, run `./gradlew build`. `build` contacts no remote database and no real lookup API.

Outputs are `platform-bukkit/build/libs/OriginGate-Bukkit-<version>.jar`, `platform-bungeecord/build/libs/OriginGate-BungeeCord-<version>.jar`, and `platform-velocity/build/libs/OriginGate-Velocity-<version>.jar`. Each bundles the shared core and its dependencies. See [dependency notices](../../THIRD_PARTY_NOTICES.md) for packaging details.

The version is set only in `gradle.properties`. The build writes it into the generated `BuildInfo` class, which the plugin descriptor and the probe also use.

## Project layout

- `core`: config loading and validation, rules, lookup providers, cache, storage, logs, and commands. No Velocity imports.
- `platform-velocity`: the Velocity plugin (events, messages, command wrapper). The `probe` source set builds a test-only permission plugin that is never shipped.
- `platform-bukkit`: asynchronous prefetch, final permission checks, and a reconnect fallback for old offline-mode CraftBukkit.
- `platform-bungeecord`: an asynchronous post-login intent holds the first backend connection.
- `presentation`: shared MiniMessage templates and legacy chat conversion.
- `maxmind-modern` and `maxmind-legacy`: isolated reader dependencies selected by the running Java version.

## Dependencies

The wrapper checks its Gradle distribution checksum. Dependencies are locked (`gradle.lockfile`) and checked against `gradle/verification-metadata.xml`. The `velocity-api` snapshot is pinned to `3.4.0-20260121.190037-118`, the same build ConsentGate uses.

After changing dependencies, refresh the locks and metadata:

```powershell
.\gradlew.bat --write-locks --write-verification-metadata sha256 build :platform-velocity:probeJar :core:dependencies :platform-velocity:dependencies
```

Review new entries in `verification-metadata.xml` before committing. Do not regenerate it just to silence a checksum mismatch.

## GitHub Actions

`.github/workflows/build.yml` runs on pushes and pull requests to `main` and on manual runs. It uses Ubuntu 24.04, Temurin JDK 25 for builds, and JDK 8 for the packaged runtime check. It checks the probe scripts and docs, runs the build and tests, and keeps all three plugin JARs for 14 days and test reports for 7. Actions are pinned to commit hashes, permissions are read-only, and checkout keeps no credentials. Connection probes need separately supplied server JARs, so they are not part of CI.

## Releasing

1. Update `version` in `gradle.properties`.
2. Add a `## <version> (<date>)` section at the top of `CHANGELOG.md`. Modrinth and GitHub Releases use that section as the release notes.
3. Run the unit tests and the [loopback probe](testing.md#loopback-probe).
4. Commit and push, and wait for the GitHub Actions run on that commit to pass.
5. Publish the JAR from that run, with the license and third-party notices.
