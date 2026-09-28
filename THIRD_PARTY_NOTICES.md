# Dependency notices

OriginGate is GPL-3.0-only. Bundled dependencies keep the licenses below. Their full license texts are included in the plugin JAR.

| Bundled library | Version | License | Packaging |
| --- | --- | --- | --- |
| SnakeYAML | 2.7 | Apache-2.0 | Relocated to `io.github.origingate.internal.snakeyaml` |
| Xerial SQLite JDBC | 3.53.4.0 | Apache-2.0, with the included Zentus BSD notice and SQLite public-domain code | Included with its native SQLite libraries |
| MariaDB Connector/J | 3.5.10 | LGPL-2.1-or-later | Relocated to `io.github.origingate.internal.mariadb` |
| MaxMind DB Reader | 4.2.0 | Apache-2.0 | Relocated to `io.github.origingate.internal.maxmind` |

Relocation changes package names in bytecode and service descriptors. OriginGate does not edit these libraries' upstream Java sources. The Gradle scripts describe the changes. You can rebuild the project with a modified dependency and replace the resulting JAR; nothing prevents replacement.

Full notices are under `META-INF/licenses/`, plus SQLite's `META-INF/maven/org.xerial/sqlite-jdbc/LICENSE` and `LICENSE.zentus`. The project license is `META-INF/LICENSE`.

Velocity, Gson, Adventure (including MiniMessage), and SLF4J are supplied by the proxy at runtime and are not bundled. Development and test dependencies are listed in the Gradle lock files and verification metadata.

## Data sent to third parties

OriginGate sends each checked player's IP address, and that provider's key or token, to the lookup providers listed in `lookup.country-from` and `lookup.vpn-from`: proxycheck.io (by default, at `lookup.proxycheck.base-url`), IPHub, ip-api.com, or IPinfo. Only listed providers are contacted. When `lookup.maxmind.account-id` and `license-key` are set, those two values are sent to MaxMind to download the GeoLite2 file; no player data is sent to MaxMind. The GeoLite2 data is not bundled; it is downloaded under MaxMind's GeoLite End User License Agreement. Nothing else is sent anywhere.
