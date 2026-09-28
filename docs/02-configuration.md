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
| `lookup.country-from` | `[proxycheck]` | Providers asked for the country, in order. See [providers](01-how-it-works.md#providers) |
| `lookup.vpn-from` | `[proxycheck]` | Providers asked for the VPN check, in order: `proxycheck`, `iphub`, `ip-api`. `[]` for none, then the `vpn` and `proxy` rules must be off |
| `lookup.proxycheck.base-url` | `https://proxycheck.io/v3/` | Change only for testing |
| `lookup.proxycheck.api-keys` | `[]` | Up to 32 keys, used in turn |
| `lookup.iphub.api-keys` | `[]` | Up to 32 keys, used in turn. At least one when IPHub is listed |
| `lookup.ip-api.api-key` | `""` | Pro key. Empty uses the free service (plain HTTP, no commercial use) |
| `lookup.ipinfo.token` | `""` | Needed when IPinfo is listed |
| `lookup.*.request-timeout-millis` | `3500` | 500 to 20000, per request, for each web provider |
| `lookup.maxmind.file` | `data/GeoLite2-Country.mmdb` | Inside the plugin folder |
| `lookup.maxmind.edition` | `GeoLite2-Country` | `GeoLite2-Country` or `GeoLite2-City`, for downloads |
| `lookup.maxmind.account-id`, `license-key` | `0`, `""` | Both set: download and update the file automatically. Both empty: place the file yourself |
| `storage.type` | `sqlite` | `sqlite` or `mysql` (also MariaDB) |
| `storage.max-age-days` | `30` | 1 to 365. Lookups older than this are looked up again |
| `storage.keep-days` | `30` | 0, or `max-age-days` to 3650. Lookups older than this are deleted. `0` keeps them forever. Each IP keeps only its latest lookup |
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
| `log-file-keep-days` | `30` | 0 to 3650. Log files older than this are deleted. `0` keeps them forever |

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

The examples below show only the settings that change. Each provider block needs all of its settings, so if your `config.yml` is from before these settings existed, copy the `iphub`, `ip-api`, `ipinfo`, and `maxmind` blocks from the bundled `config.yml` first.

### Example: country from MaxMind, VPN check from proxycheck.io

```yaml
lookup:
  country-from: [maxmind, proxycheck]
  vpn-from: [proxycheck, iphub]
  iphub:
    api-keys: ["your-iphub-key"]
  maxmind:
    account-id: 123456
    license-key: "your_license_key"
```

The country comes from the local file, so proxycheck.io is only asked for the VPN check. If proxycheck.io fails, IPHub is asked.

### Example: country rules only, no web requests

```yaml
lookup:
  country-from: [maxmind]
  vpn-from: []
rules:
  vpn:
    enabled: false
  proxy:
    enabled: false
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
