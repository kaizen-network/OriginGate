# Configuration and messages

Both files are in `plugins/origingate/`. Run `origingate reload` after editing. A file with a mistake is refused, the old settings stay active, and the reply names the setting, for example `lookup.wait-millis must be a whole number from 1000 to 20000`. Unknown settings are refused too, so typos are caught.

Updates keep your files. New settings are not added automatically; compare with the bundled defaults after an update.

## config.yml

| Setting | Default | Notes |
| --- | --- | --- |
| `dry-run` | `false` | Log decisions, never kick |
| `console-log` | `matches` | `none`, `kicks`, `matches`, `all`, or `debug`. See [logging](01-how-it-works.md#logging) |
| `lookup.skip-private-addresses` | `true` | No lookup for loopback, LAN, link-local, and unique local addresses |
| `lookup.on-lookup-failure` | `allow` | `allow` or `deny` |
| `lookup.wait-millis` | `5000` | 1000 to 20000. Longest time a login is held |
| `lookup.proxycheck.base-url` | `https://proxycheck.io/v3/` | Change only for testing |
| `lookup.proxycheck.api-keys` | `[]` | Up to 32 keys, used in turn |
| `lookup.proxycheck.request-timeout-millis` | `3500` | 500 to 20000, per request |
| `storage.type` | `sqlite` | `sqlite` or `mysql` (also MariaDB) |
| `storage.max-age-days` | `30` | 1 to 365. Lookups older than this are looked up again and deleted |
| `storage.memory-cache-size` | `10000` | 100 to 1000000 lookups kept in memory |
| `storage.sqlite.file` | `data/origingate.db` | Inside the plugin folder |
| `storage.mysql.*` | | See [storage](03-storage-and-privacy.md) |
| `bypass.permissions` | `[]` | Any of these skips every check |
| `bypass.players` | `[]` | Names (any case) or UUIDs |
| `bypass.addresses` | `[]` | IPs or CIDR ranges |
| `rules.deny-addresses` | off | `list` of IPs or CIDR ranges, `bypass-permissions` |
| `rules.vpn` | on | `bypass-permissions: [origingate.bypass.vpn]` |
| `rules.proxy` | on | `allowed-countries` (codes), `bypass-permissions: [origingate.bypass.proxy]` |
| `rules.country` | off | `mode: allowlist` or `denylist`, `countries` (codes), `bypass-permissions: [origingate.bypass.country]` |
| `alerts.permissions` | `[origingate.alerts]` | Staff who see kick and bypass alerts |
| `log-file` | `true` | Daily log files of kicks, bypasses, and lookup failures |

Country codes are ISO 3166-1 two-letter codes such as `US`, `GB`, `ID`, in any case, plus `XK` for Kosovo. Unknown codes are refused.

Changing `storage.type` takes effect on reload. Saved lookups are not copied between storage types.

### Example: allow only some countries

```yaml
rules:
  country:
    enabled: true
    mode: allowlist
    countries: [US, CA]
    bypass-permissions: ["origingate.bypass.country"]
```

## messages.yml

Messages use [MiniMessage](https://docs.advntr.dev/minimessage/format.html).

| Key | When |
| --- | --- |
| `kick.deny-addresses`, `kick.vpn`, `kick.proxy`, `kick.country` | Kick screen for that rule |
| `kick.lookup-failure` | Kick screen when `on-lookup-failure: deny` |
| `bypass-notice` | Chat message after joining the first server through a rule bypass. Empty turns it off |
| `alerts.denied`, `alerts.bypassed` | Chat alert to staff. Empty turns it off |

Placeholders:

| Placeholder | Value |
| --- | --- |
| `<username>`, `<uuid>`, `<ip>` | The connecting player |
| `<rule>` | `deny-addresses`, `vpn`, `proxy`, `country`, or `lookup-failure` |
| `<time>` | Unix time in seconds |
| `<provider>`, `<country>`, `<country_code>`, `<city>`, `<region>`, `<type>` | Lookup data |
| `<organisation>` | The VPN operator's name when it is longer than 3 characters, otherwise the network organisation |

Unknown values show as `-`. Placeholder values are inserted as plain text, so formatting tags inside a provider name are shown as text, not applied. The default kick screens do not show `<ip>`.
