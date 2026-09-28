# Development

## Build

Use JDK 25 to run the Gradle wrapper. Bytecode targets Java 21.

```powershell
.\gradlew.bat build
```

On Linux or macOS, run `./gradlew build`. `build` contacts no remote database and no real lookup API.

Output: `platform-velocity/build/libs/OriginGate-Velocity-<version>.jar`. It bundles the core, relocated SnakeYAML and MariaDB Connector/J, and SQLite JDBC with its native libraries. Gson, Adventure, and SLF4J come from Velocity.

The version is set only in `gradle.properties`. The build writes it into the generated `BuildInfo` class, which the plugin descriptor and the probe also use.

## Project layout

- `core`: config loading and validation, rules, lookup providers, cache, storage, logs, and commands. No Velocity imports.
- `platform-velocity`: the Velocity plugin (events, messages, command wrapper). The `probe` source set builds a test-only permission plugin that is never shipped.

A Paper or BungeeCord port would add another `platform-*` module on top of `core`.

## Dependencies

The wrapper checks its Gradle distribution checksum. Dependencies are locked (`gradle.lockfile`) and checked against `gradle/verification-metadata.xml`. The `velocity-api` snapshot is pinned to `3.4.0-20260121.190037-118`, the same build ConsentGate uses.

After changing dependencies, refresh the locks and metadata:

```powershell
.\gradlew.bat --write-locks --write-verification-metadata sha256 build :platform-velocity:probeJar :core:dependencies :platform-velocity:dependencies
```

Review new entries in `verification-metadata.xml` before committing. Do not regenerate it just to silence a checksum mismatch.

## GitHub Actions

`.github/workflows/build.yml` runs on pushes and pull requests to `main` and on manual runs: Ubuntu 24.04, Temurin JDK 25, a syntax check of the probe script, the docs check and its tests, then `build` and `probeJar`. It keeps the plugin JAR for 14 days and test reports for 7. Actions are pinned to commit hashes, permissions are read-only, and checkout keeps no credentials. The loopback probe needs a Velocity JAR, so it is not part of CI.

## Releasing

1. Update `version` in `gradle.properties`.
2. Add a `## <version> (<date>)` section at the top of `CHANGELOG.md`. Modrinth and GitHub Releases use that section as the release notes.
3. Run the unit tests and the [loopback probe](testing.md#loopback-probe).
4. Commit and push, and wait for the GitHub Actions run on that commit to pass.
5. Publish the JAR from that run, with the license and third-party notices.
