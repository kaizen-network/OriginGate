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
| `type` | `VARCHAR(64)` | proxycheck.io network type |
| `checked_at` | `BIGINT` | Unix seconds, indexed |

Every query uses prepared statements. Saves use `REPLACE INTO`, an atomic upsert in both SQLite and MySQL, so several proxies can share one table. Long values are shortened to fit.

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

The driver is MariaDB Connector/J, bundled and relocated. It opens one connection per operation; the 4 lookup workers limit how many run at once.

If the database cannot be reached when OriginGate starts or reloads, OriginGate still starts, logs a warning, and checks connections without saved lookups. The table is created as soon as the database answers. A database that answers but refuses the setup (for example missing permissions) stops the load instead, since that needs a config fix.

While the database is down, each failed attempt makes OriginGate skip storage for 60 seconds, so logins are not slowed down by connection timeouts.

## Privacy

IP addresses and their lookup data are personal data.

- The lookup provider receives the player's IP address and your API key. Nothing is sent anywhere else.
- Stored lookups older than `keep-days` are deleted every hour (first run one minute after start). Daily log files older than `log-file-keep-days` are deleted at the same time. A value of `0` turns off that deletion. Each IP has one row holding its latest lookup, so a new lookup replaces the old one.
- `origingate cache clear <ip|all>` deletes lookups from memory and from OriginGate's table right away.
- The console lines and log files contain IP addresses. Velocity's own proxy log is separate and follows its own settings.
