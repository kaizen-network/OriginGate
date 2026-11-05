---
title: Storage and privacy
description: Where lookups are saved, MySQL/MariaDB setup, what is sent to providers and bStats, and how long data is kept.
order: 9
---

# Storage and privacy

## Table

OriginGate creates its own table, `origingate_ip_cache`, on start:

| Column | Type | Notes |
| --- | --- | --- |
| `ip` | `VARCHAR(45)` primary key | Canonical text form, for example `2001:db8:0:0:0:0:0:1` |
| `provider`, `organisation`, `operator_name`, `city`, `region`, `country` | `VARCHAR(255)` | `NULL` when unknown |
| `country_code` | `CHAR(2)` | |
| `asn` | `VARCHAR(32)` | |
| `vpn`, `proxy` | `BOOLEAN` | |
| `type` | `VARCHAR(64)` | Network type (proxycheck.io only) |
| `checked_at` | `BIGINT` | Unix seconds, indexed |

Saves replace the previous row for the same IP in one step, in both SQLite and MySQL, so several proxies can share one table. Long values are shortened to fit.

## SQLite

The default. The file is `plugins/origingate/data/origingate.db`. Nothing to set up.

## MySQL and MariaDB

1. Create a database (utf8mb4) and a user with `CREATE`, `SELECT`, `INSERT`, and `DELETE` on it. `DELETE` is needed for replacing and expiring rows.
2. Fill in `storage.mysql` and set `storage.type: mysql`.
3. Run `origingate reload`. The table is created automatically.

```yaml
storage:
  type: mysql
  mysql:
    host: db.example.com
    port: 3306
    database: origingate
    username: origingate
    password: "your-password"
    ssl-mode: verify-full   # verify-full, verify-ca, or disable
    connect-timeout-millis: 3000
    socket-timeout-millis: 5000
```

The password is used as written; environment variables are not expanded. The driver (MariaDB Connector/J) is bundled. It opens one connection per operation, and the 4 lookup workers limit how many run at once.

If the database cannot be reached when OriginGate starts or reloads, OriginGate still starts, logs a warning, and checks connections without saved lookups. The table is created as soon as the database answers. A database that answers but refuses the setup (for example, missing permissions) stops the load instead, since that needs a config fix.

While the database is down, each failed attempt makes OriginGate skip storage for 60 seconds, so logins are not slowed down by connection timeouts.

## Privacy

IP addresses and their lookup data are personal data.

- Each provider in `country-from` and `vpn-from` receives the player's IP address and your key or token when it is asked. The free ip-api.com service receives them over plain HTTP. MaxMind receives only your account ID and license key when the file is downloaded, never player IPs. Apart from these and [bStats](#bstats), nothing is sent anywhere.
- Stored lookups older than `keep-days` are deleted every hour (first run one minute after start). Daily log files older than `log-file-keep-days` are deleted at the same time. A value of `0` turns off that deletion.
- Each IP has one row holding its latest lookup, so a new lookup replaces the old one.
- `origingate cache clear <ip|all>` deletes lookups from memory and from OriginGate's table right away.
- The console lines and log files contain IP addresses. Velocity's own proxy log is separate and follows its own settings.

## bStats

OriginGate uses [bStats](https://bstats.org) to count how many servers run it. It is on by default. The data is anonymous: no player names, UUIDs, or IP addresses are sent.

About every 30 minutes, OriginGate sends bStats:

- A random server ID, made by bStats the first time it runs
- The server or proxy software and its version
- Online mode and the number of players online, plus the number of backend servers on a proxy
- The Java version, operating system, and number of CPU cores
- The OriginGate version

bStats also records the server's country. It says IP addresses are used only for rate limiting and are never linked to the data. See bStats' [notes for server owners](https://bstats.org/docs/server-owners).

### Turn off bStats

Set `enabled` to false in the bStats file, then restart the server or proxy:

| Platform | File | Setting |
| --- | --- | --- |
| Bukkit, Spigot, Paper, BungeeCord, Waterfall | `plugins/bStats/config.yml` | `enabled: false` |
| Velocity | `plugins/bStats/config.txt` | `enabled=false` |

This file is shared, so it turns off bStats for every plugin on that server, and for the server software too if it uses bStats.

On Velocity, keep the `server-uuid` line. bStats replaces a `config.txt` without it, and turns bStats back on.
