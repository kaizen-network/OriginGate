---
title: Configuration
description: Every config.yml setting, its default, and example setups.
order: 4
---

# Configuration

`config.yml` is in `plugins/origingate/`. Run `origingate reload` after editing. A file with a mistake is refused, the old settings stay active, and the reply names the setting, for example `lookup.wait-millis must be a whole number from 1000 to 20000`. Unknown settings are refused too, so typos are caught.

Updates keep your file. New settings are not added automatically; compare with the [default config](https://github.com/kaizen-network/OriginGate/blob/main/core/src/main/resources/config.yml) after an update. Settings added in 1.1.0 (`max-lookups-per-minute`, `on-budget-exceeded`, `rules.deny-networks`, and `rules.hosting`) and 1.2.0 (`rules.allow-networks`) are optional: a config without them works as it did in 1.0.0.

## Settings

| Setting | Default | Notes |
| --- | --- | --- |
| `dry-run` | `false` | Log decisions, never kick |
| `console-log` | `matches` | `none`, `kicks`, `matches`, `all`, or `debug`. See [logging](how-it-works.md#logging) |
| `lookup.skip-private-addresses` | `true` | No lookup for loopback, LAN, link-local, and unique local addresses |
| `lookup.on-lookup-failure` | `allow` | `allow` or `deny` |
| `lookup.wait-millis` | `2500` | 1000 to 20000. Longest time a login is held. The default for new installs was 5000 before 1.1.0; an existing config keeps its value |
| `lookup.max-lookups-per-minute` | `0` | 0 to 100000. Most lookups sent to providers in any 60 seconds, so a wave of bots cannot use up a provider's daily quota. Logins over it are not looked up. `0` means no limit |
| `lookup.on-budget-exceeded` | not set | `allow` or `deny`, for logins over `max-lookups-per-minute`. Not set: the same as `on-lookup-failure`. See [flood protection](#flood-protection) |
| `lookup.country-from` | `[proxycheck]` | Providers asked for the country, in order. See [providers](providers.md) |
| `lookup.vpn-from` | `[proxycheck]` | Providers asked for the VPN check, in order: `proxycheck`, `iphub`, `ip-api`. `[]` for none; then the `vpn` and `proxy` rules must be off, and lookups are kept in memory only |
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
| `storage.memory-cache-size` | `10000` | 100 to 1,000,000 lookups kept in memory |
| `storage.sqlite.file` | `data/origingate.db` | Inside the plugin folder |
| `storage.mysql.*` | | See [storage](storage-and-privacy.md#mysql-and-mariadb) |
| `bypass.permissions` | `[]` | Any of these skips every check |
| `bypass.players` | `[]` | Names (any case) or UUIDs. Anyone can claim them in offline mode; see [offline mode and proxies](#offline-mode-and-proxies) |
| `bypass.addresses` | `[]` | IPs or CIDR ranges |
| `rules.deny-addresses` | off | `list` of IPs or CIDR ranges, `bypass-permissions` |
| `rules.deny-networks` | off | `asns` (AS numbers such as `AS64496`), `organisations` (text to look for in the network's provider, organisation, or VPN operator name, ignoring case, at least 3 characters), `bypass-permissions: [origingate.bypass.deny-networks]`. Works with every provider except `maxmind` |
| `rules.allow-networks` | off | `asns` only (AS numbers, no name matching, no bypass permissions). Lets in a listed network when `vpn`, `proxy`, or `hosting` would kick it; `deny-networks` and `country` still apply. Works with every provider except `maxmind` |
| `rules.vpn` | on | `bypass-permissions: [origingate.bypass.vpn]` |
| `rules.proxy` | on | `allowed-countries` (codes), `bypass-permissions: [origingate.bypass.proxy]` |
| `rules.hosting` | off | `bypass-permissions: [origingate.bypass.hosting]`. Needs `proxycheck` or `ip-api` in `vpn-from`. See [providers](providers.md#hosting-networks) |
| `rules.country` | off | `mode: allowlist` or `denylist`, `countries` (codes), `bypass-permissions: [origingate.bypass.country]` |
| `alerts.permissions` | `[origingate.alerts]` | Staff who see kick and bypass alerts, and lookup problems (one message when it starts, one when it is over) |
| `log-file` | `true` | Daily log files of kicks, bypasses, and lookup failures |
| `log-file-keep-days` | `30` | 0 to 3650. Log files older than this are deleted. `0` keeps them forever |

Country codes are ISO 3166-1 two-letter codes such as `US`, `GB`, `ID`, in any case, plus `XK` for Kosovo. Unknown codes are refused.

Changing `storage.type` takes effect on reload. Saved lookups are not copied between storage types.

## Examples

Each example shows only the settings that change. Each provider block needs all of its settings, so if your `config.yml` is from before these settings existed, copy the `iphub`, `ip-api`, `ipinfo`, and `maxmind` blocks from the default config first.

### Allow only some countries

```yaml
rules:
  country:
    enabled: true
    mode: allowlist
    countries: [US, CA]
    bypass-permissions: ["origingate.bypass.country"]
```

### Country from MaxMind, VPN check from proxycheck.io

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

### Kick hosting networks and listed networks

```yaml
lookup:
  vpn-from: [proxycheck]
rules:
  deny-networks:
    enabled: true
    asns: [AS64496]
    organisations: ["Example Hosting"]
    bypass-permissions: ["origingate.bypass.deny-networks"]
  hosting:
    enabled: true
    bypass-permissions: ["origingate.bypass.hosting"]
```

To let in a network that a provider wrongly flags, such as a school or a mobile carrier, list it under `allow-networks`. It only skips the `vpn`, `proxy`, and `hosting` rules, and the log shows `rule=allow-networks` when it did.

```yaml
rules:
  allow-networks:
    enabled: true
    asns: [AS64500]
```

Use `origingate check <ip>` to see an address's AS number. Names are not matched, because a name match could let in any VPN with that text in its name.

Hosting and datacenter networks are where VPN services and bots rent their servers, so the hosting rule catches many that no provider flags as a VPN. It also catches players who play through a cloud desktop or a hosted VPN of their own; give them `origingate.bypass.hosting`. Try it with `dry-run: true` first.

### Flood protection

```yaml
lookup:
  max-lookups-per-minute: 120
  on-budget-exceeded: deny
```

During a bot flood, every new address costs one provider lookup. With a limit, lookups over it are not sent, so the provider's daily quota lasts. The limit is off (`0`) by default. Turn it on, together with `on-budget-exceeded: deny`, when bot waves of new addresses use up your provider's daily lookups or you are under attack right now; pick a limit above the number of new addresses your busiest minute normally sees. A login over the limit follows `on-budget-exceeded`:

- Not set (the default): the same as `on-lookup-failure`, so with `allow` those players get in unchecked, as they would when the provider is down.
- `deny`: those players are kicked with the `lookup-failure` message and can join again once the flood is over. Players looked up within `storage.max-age-days` are still answered from memory or storage and are not affected. Use it as an attack mode while you are being flooded.

Staff with an alert permission get one message when the limit is reached and one after a full minute without a login over it, with how many addresses went unchecked. `origingate status` shows how much of the limit the last minute used.

Separately from the limit, and always on: a lookup still queued when every login waiting for it has given up (after `wait-millis`) is dropped without asking a provider while the queue is backed up (when it is picked up, at least as many lookups wait behind it as there are workers, which is 4), so a burst of logins does not spend lookups on answers nobody uses. In normal traffic a late lookup still runs and fills the cache, as in 1.0.0.

### Country rules only, no web requests

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

## Offline mode and proxies

In offline mode, anyone can join with any name. The UUID is made from the name, and permission plugins such as LuckPerms load permissions by that UUID before any login plugin runs. So in offline mode, `bypass.players` and every bypass permission (global or per rule) can be claimed by anyone who picks a name that has one. Only `bypass.addresses` holds up.

OriginGate tells you in two ways where names are not checked (offline mode, an offline-mode proxy, or BungeeCord IP forwarding):

- At startup and reload, a warning when you set `bypass.players` or `bypass.permissions`. The per-rule `bypass-permissions` in the default config do not count, since nobody holds them until you grant them.
- When a player gets in through a rule's bypass permission, the `BYPASS` log line ends with `caution="anyone can claim this name in offline mode"`, and the staff alert carries the same note. Global bypasses (`bypass.players` and `bypass.permissions`) are covered by the startup warning; their `ALLOW` lines carry the caution too, but only show with `console-log: all`.

| Where OriginGate runs | Startup warning |
| --- | --- |
| Online-mode server or proxy | No |
| Paper behind Velocity with modern forwarding (`proxies.velocity.enabled`, from an online-mode proxy) | No. The forwarded name and UUID are signed |
| Offline-mode server or proxy, or Paper behind an offline-mode proxy (`online-mode: false` in Paper's proxy settings) | Only when `bypass.players` or `bypass.permissions` is set |
| Spigot or Paper behind BungeeCord IP forwarding (`settings.bungeecord: true`) | Always, one line: anyone who can reach the server's own port can fake the name, UUID, and IP address and skip every check. It is only safe while the port accepts connections from the proxy alone |

Installing OriginGate on the proxy instead of the backends avoids the forwarding question entirely.
