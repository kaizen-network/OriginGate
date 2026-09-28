# How it works

## Connection flow

OriginGate listens to Velocity's `LoginEvent` and returns an asynchronous `EventTask`, so the proxy's network threads are never blocked. The login is held while the check runs. A kick uses `ResultedEvent.ComponentResult.denied(...)`, and Velocity then disconnects the player before connecting to any backend server.

In Velocity's `AuthSessionHandler`, `LoginEvent` fires only after `PermissionsSetupEvent` has finished. `PostLoginEvent` and then `PlayerChooseInitialServerEvent` follow only when the login was allowed.

If another plugin already denied the login, OriginGate does nothing, so no API request is made.

## Permission timing (LuckPerms)

Bypass permissions must be checked after LuckPerms has loaded the player. From LuckPerms' `VelocityConnectionListener` (master branch, checked on 2026-09-26):

- User data is loaded during `PermissionsSetupEvent`. LuckPerms holds that event until loading finishes.
- Its `LoginEvent` handlers run at `PostOrder.FIRST` (denies the login when loading failed) and at the default order (checks that the loaded data is there).

So permissions are ready by the time any `LoginEvent` handler runs. OriginGate subscribes with `priority = -100`, which in Velocity means after the default priority 0. It also runs after LuckPerms' failure check, which is why a login LuckPerms denied is skipped.

## Order of checks

1. Global bypass: `bypass.players` (name or UUID), `bypass.addresses`, `bypass.permissions`.
2. `deny-addresses`, when enabled.
3. Private address skip (`lookup.skip-private-addresses`).
4. Lookup, waiting at most `lookup.wait-millis`.
5. `vpn`, `proxy`, `country`, when enabled.

The first rule that matches decides the result: kick, or let in through that rule's bypass permission. Later rules are not checked. For example, a player with the VPN bypass who uses a VPN from a country outside the allowlist is let in, because the vpn rule matched first.

A `bypass.addresses` entry wins over a `deny-addresses` entry.

## Lookups

1. Memory cache (bounded, least recently used entries are dropped first).
2. OriginGate's table, rows younger than `max-age-days`.
3. The lookup providers (see [Providers](#providers)).

Only one request per IP runs at a time. Other logins from the same IP wait for that request. `origingate check <ip> refresh` always makes its own request. Lookups run on 4 worker threads with a queue of 256. When the queue is full, the lookup fails and `on-lookup-failure` applies.

A new result is given to the waiting login first and saved afterwards with an upsert, so a slow database does not hold the login.

When a storage read or save fails, storage is skipped for 60 seconds and lookups go straight to the providers. This keeps a dead database from using up `wait-millis` on every login.

When no provider returns a country code, the lookup counts as failed and is not saved, since country rules need it. That IP is not asked again for 5 minutes, so a player reconnecting in a loop cannot drain the API quota. `refresh` and `cache clear` skip this pause.

### Providers

A lookup has two jobs:

- Country: the providers in `country-from` are asked in order until one returns a known country code.
- VPN check: the providers in `vpn-from` are asked in order until one answers. A provider that already answered or failed in the same lookup is not asked again.

The result takes the country, region, and city from the country answer, and the VPN and proxy flags, type, and operator from the VPN answer. The network provider, organisation, and ASN come from the VPN answer, or from the country answer when the VPN answer has none.

The next provider is asked when one fails, times out, refuses its key, is rate-limited, or has no data. When either job gets no answer, the lookup fails and `on-lookup-failure` applies. No new provider is asked once `wait-millis` has passed. A refused or rate-limited API key is skipped for 60 seconds, and the provider itself is skipped for 60 seconds once all of its keys are refused (or right away when it has no keys).

With `vpn-from: []` there is no VPN check, and the `vpn` and `proxy` rules must be disabled.

| Provider | Kind | Country | VPN check | Free tier (checked 2026-09-27) |
| --- | --- | --- | --- | --- |
| `proxycheck` | Web API | Yes | Yes | 100 lookups per day without a key, 1,000 with a free account |
| `iphub` | Web API | Yes | Yes | 1,000 requests per day, key required |
| `ip-api` | Web API | Yes | Yes | 45 requests per minute, plain HTTP, no commercial use |
| `ipinfo` | Web API | Yes | No | Lite plan without a limit, token required |
| `maxmind` | Local file | Yes | No | Free account and license key |

Fields used:

| Provider | Country fields | VPN | Network fields |
| --- | --- | --- | --- |
| IPHub (`https://v2.api.iphub.info/ip/<ip>`, key in `X-Key`) | `countryCode`, `countryName` | `block: 1` (non-residential). `block: 2` is ignored, since IPHub says it may flag innocent users | `isp`, `asn` |
| ip-api (`http://ip-api.com/json/<ip>`, or `https://pro.ip-api.com/json/<ip>?key=` with a key) | `countryCode`, `country`, `regionName`, `city` | `proxy: true` (proxy, VPN, or Tor exit) | `isp`, `org`, `as` |
| IPinfo Lite (`https://api.ipinfo.io/lite/<ip>?token=`) | `country_code`, `country` | | `asn`, `as_name` |
| MaxMind (local `.mmdb` file) | `country` (or `registered_country`), first subdivision and city in a City file | | |

Only proxycheck.io reports proxies separately from VPNs. The others set only the VPN flag.

### MaxMind file

`lookup.maxmind.file` is read into memory at startup and reload. With `account-id` and `license-key` set, OriginGate downloads the file when it is missing and checks for a new release every 24 hours with a HEAD request (MaxMind states these do not count toward the download limit). A new file is saved next to the old one, checked, then moved over it, and used without a reload. MaxMind redirects downloads to its storage host; the account ID and license key are sent only to `download.maxmind.com`.

Without a key, you place the file yourself. When it is older than 30 days, a warning is logged at startup and reload, since MaxMind's GeoLite EULA asks for updates within 30 days of a new release.

### proxycheck.io

OriginGate uses the v3 API (`https://proxycheck.io/v3/<ip>?key=<key>`). v3 returns location and network data without extra flags. Fields used:

| OriginGate | v3 field |
| --- | --- |
| provider, organisation, asn, type | `network.provider`, `network.organisation`, `network.asn`, `network.type` |
| country, country code, region, city | `location.country_name`, `location.country_code`, `location.region_name`, `location.city_name` |
| vpn, proxy | `detections.vpn`, `detections.proxy` |
| operator name | `operator.name` (`operator` is `null` when unknown) |

Keys are used in turn. When proxycheck.io refuses a key (HTTP 401, 403, or 429, or `"status": "denied"`), that key is skipped for 60 seconds and the next key is tried, until one works. When every key is refused, the next provider in the list is asked, or the lookup fails when there is none. Other errors are not retried. An unknown or wrong key is not refused by the API: it answered `"status": "ok"` in a test on 2026-09-26, so check your usage on the proxycheck.io dashboard.

## Failures and dry-run

- `on-lookup-failure: allow` lets the player in when the lookup fails or times out. `deny` kicks them with the `lookup-failure` message.
- If a kick message cannot be built, the player is still kicked, with a plain "You cannot join this server." text, and a warning is logged.
- On reload, logins that already started finish with the previous settings. The previous settings are then closed in the background.
- `dry-run: true` runs everything and logs `WOULD-DENY` instead of kicking. No chat notices or staff alerts are sent in dry-run.

## Logging

Each decision is one line, for example `DENY rule=vpn player=Alex uuid=... ip=... provider="..." organisation="..." country="..." country_code=.. city="..." type="..." vpn=yes proxy=no source=proxycheck note="flagged as VPN"`. Labels: `ALLOW`, `BYPASS`, `DENY`, `WOULD-DENY`. `source` is `memory`, `storage`, or the providers that answered, such as `maxmind+proxycheck`.

`console-log` sets which lines reach the console. Each level includes the ones before it:

| Level | Shows |
| --- | --- |
| `none` | No login lines. Startup, reload, and errors still show |
| `kicks` | `DENY` and `WOULD-DENY` |
| `matches` (default) | Every login where a rule matched or the lookup failed: kicks, bypasses, and lookup failures that let the player in |
| `all` | Every login |
| `debug` | Every login, plus each step of the check |

`log-file: true` writes the same lines as `matches` to `plugins/origingate/logs/<date>.log`, whatever the console level is. Files older than `log-file-keep-days` are deleted.

## ConsentGate

ConsentGate holds `PlayerChooseInitialServerEvent`, which fires after `LoginEvent`. A player kicked by OriginGate never reaches the consent dialog, and no consent record is written. Players who are let in still see the dialog as usual. The two plugins share no code, data folder, or tables. See [development](04-development.md#consentgate-compatibility-run) for the test.
