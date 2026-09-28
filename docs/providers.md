---
title: Lookup providers
description: Choosing and combining proxycheck.io, IPHub, ip-api.com, IPinfo, and MaxMind GeoLite2.
order: 6
---

# Lookup providers

A lookup has two jobs:

- **Country:** the providers in `country-from` are asked in order until one returns a known country code.
- **VPN check:** the providers in `vpn-from` are asked in order until one answers. A provider that already answered or failed in the same lookup is not asked again.

The result takes the country, region, and city from the country answer, and the VPN and proxy flags, type, and operator from the VPN answer. The network provider, organisation, and ASN come from the VPN answer, or from the country answer when the VPN answer has none.

## Comparison

| Provider | Kind | Country | VPN check | Free tier (checked 2026-09-27) |
| --- | --- | --- | --- | --- |
| `proxycheck` | Web API | Yes | Yes | 100 lookups per day without a key, 1,000 with a free account |
| `iphub` | Web API | Yes | Yes | 1,000 requests per day, key required |
| `ip-api` | Web API | Yes | Yes | 45 requests per minute, plain HTTP, no commercial use |
| `ipinfo` | Web API | Yes | No | Lite plan without a limit, token required |
| `maxmind` | Local file | Yes | No | Free account and license key |

Only proxycheck.io reports proxies separately from VPNs. The others set only the VPN flag.

## When a provider fails

The next provider is asked when one fails, times out, refuses its key, is rate-limited, or has no data. When either job gets no answer, the lookup fails and `on-lookup-failure` applies. No new provider is asked once `wait-millis` has passed.

A refused or rate-limited API key is skipped for 60 seconds. The provider itself is skipped for 60 seconds once all of its keys are refused (or right away when it has no keys).

With `vpn-from: []` there is no VPN check, and the `vpn` and `proxy` rules must be disabled. These lookups are kept in memory only and never saved, so a later config with VPN checks does not reuse them.

## proxycheck.io

OriginGate uses the v3 API (`https://proxycheck.io/v3/<ip>?key=<key>`), which returns location and network data without extra flags.

| OriginGate | v3 field |
| --- | --- |
| provider, organisation, asn, type | `network.provider`, `network.organisation`, `network.asn`, `network.type` |
| country, country code, region, city | `location.country_name`, `location.country_code`, `location.region_name`, `location.city_name` |
| vpn, proxy | `detections.vpn`, `detections.proxy` |
| operator name | `operator.name` (`operator` is `null` when unknown) |

Keys are used in turn. When proxycheck.io refuses a key (HTTP 401, 403, or 429, or `"status": "denied"`), that key is skipped for 60 seconds and the next key is tried, until one works or `wait-millis` has passed. When every key is refused, the next provider in the list is asked, or the lookup fails when there is none. Other errors are not retried.

An unknown or wrong key is not refused by the API (it answers `"status": "ok"`), so check your usage on the proxycheck.io dashboard.

## Other web providers

| Provider | Request | Country fields | VPN | Network fields |
| --- | --- | --- | --- | --- |
| IPHub | `https://v2.api.iphub.info/ip/<ip>`, key in `X-Key` | `countryCode`, `countryName` | `block: 1` (non-residential). `block: 2` is ignored, since IPHub says it may flag innocent users | `isp`, `asn` |
| ip-api | `http://ip-api.com/json/<ip>`, or `https://pro.ip-api.com/json/<ip>?key=` with a key | `countryCode`, `country`, `regionName`, `city` | `proxy: true` (proxy, VPN, or Tor exit) | `isp`, `org`, `as` |
| IPinfo Lite | `https://api.ipinfo.io/lite/<ip>?token=` | `country_code`, `country` | | `asn`, `as_name` |

## MaxMind GeoLite2

MaxMind is a local `.mmdb` file, so player IPs are never sent anywhere. It gives `country` (or `registered_country`), plus the first subdivision and city with a City file.

`lookup.maxmind.file` is read into memory at startup and reload.

With `account-id` and `license-key` set, OriginGate:

- downloads the file when it is missing
- checks for a new release every 24 hours with a HEAD request (MaxMind states these do not count toward the download limit)
- saves a new file next to the old one, checks it, then moves it over the old one and uses it without a reload
- records the release and the file's build date in `<file>.release`
- replaces a file it did not download itself, or a file of another edition after `edition` changes, with the latest release

MaxMind redirects downloads to its storage host. The account ID and license key are sent only to `download.maxmind.com`.

Without a key, you place the file yourself. When it is older than 30 days, a warning is logged at startup and reload, since MaxMind's GeoLite EULA asks for updates within 30 days of a new release.
