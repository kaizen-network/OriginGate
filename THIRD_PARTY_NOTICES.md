# Dependency notices

OriginGate is GPL-3.0-only. Bundled dependencies keep the licenses below. Their full license texts are included in the plugin JAR.

| Bundled library | Version | License | Packaging |
| --- | --- | --- | --- |
| SnakeYAML | 2.7 | Apache-2.0 | Relocated to `io.github.origingate.internal.snakeyaml` |
| Xerial SQLite JDBC | 3.53.4.0 | Apache-2.0, with the included Zentus BSD notice and SQLite public-domain code | Included with its native SQLite libraries; an isolated class loader prevents old server drivers from overriding it |
| MariaDB Connector/J | 3.5.10 | LGPL-2.1-or-later | Relocated to `io.github.origingate.internal.mariadb` |
| MaxMind DB Reader | 4.2.0 and 2.1.0 | Apache-2.0 | Isolated as `io.github.origingate.internal.maxmind17` and `io.github.origingate.internal.maxmind8`; Java 17+ uses 4.2.0, older Java uses 2.1.0 |
| Gson | 2.10.1 | Apache-2.0 | Relocated to `io.github.origingate.internal.gson` |
| Adventure, MiniMessage, legacy serializer, examination | 4.26.1 / 1.3.0 | MIT | Bukkit/BungeeCord relocate `net.kyori` to `io.github.origingate.internal.kyori` |
| SLF4J API and JUL binding | 2.0.17 | MIT | Bukkit/BungeeCord relocate to `io.github.origingate.internal.slf4j`; Velocity supplies its own binding |

Relocation changes package names in bytecode and service descriptors. OriginGate does not edit these libraries' upstream Java sources. The Gradle scripts describe the changes. You can rebuild the project with a modified dependency and replace the resulting JAR; nothing prevents replacement.

Full notices are under `META-INF/licenses/`, plus SQLite's `META-INF/maven/org.xerial/sqlite-jdbc/LICENSE` and `LICENSE.zentus`. The project license is `META-INF/LICENSE`.

Server and proxy APIs are not bundled. The Java 8 MaxMind reader predates later decoder fixes; only use trusted MaxMind databases. Development and test dependencies are listed in the Gradle lock files and verification metadata.

## Data sent to third parties

OriginGate sends each checked player's IP address, and that provider's key or token, to the lookup providers listed in `lookup.country-from` and `lookup.vpn-from`: proxycheck.io (by default, at `lookup.proxycheck.base-url`), IPHub, ip-api.com, or IPinfo. Only listed providers are contacted. When `lookup.maxmind.account-id` and `license-key` are set, those two values are sent to MaxMind to download the GeoLite2 file; no player data is sent to MaxMind. The GeoLite2 data is not bundled; it is downloaded under MaxMind's GeoLite End User License Agreement. Nothing else is sent anywhere.
