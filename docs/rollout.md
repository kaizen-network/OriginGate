---
title: Rollout checklist
description: Try OriginGate in dry-run first, and check IP addresses, permissions, and other login plugins.
order: 3
---

# Rollout checklist

## Steps

1. Try it on a test proxy first.
2. Install it on your proxy with `dry-run: true`. For a few days, read the `WOULD-DENY` and `BYPASS` lines (`console-log: matches` shows them) and check the players they name.
3. Set `dry-run: false` and run `origingate reload`. If another plugin also checks VPNs or countries, turn that check off.

## Check the IP address

OriginGate is only as good as the IP address it sees. After the first joins, compare the `ip=` field in the console with the player's real address, or run `origingate check <player>`.

- **Proxy protection services** (TCP or DDoS shields in front of the proxy): the service usually passes the real address with PROXY protocol or its own plugin. That must happen before Velocity's `LoginEvent`. Otherwise every player shows the service's address.
- **Bedrock players through Geyser and Floodgate:** they must show their own address, not the local address of Geyser. If they show a local address, `skip-private-addresses: true` lets them in without a check.
- **Offline mode:** anyone can join with any name, so name, UUID, and permission bypasses can be claimed by anyone. Only `bypass.addresses` holds up. OriginGate warns at startup when it finds this; see [offline mode and proxies](configuration.md#offline-mode-and-proxies).

## Check permissions

Give a test account one of the bypass permissions and join through a VPN. It should be let in with a chat notice. An account without it should be kicked. Permission plugins that load their data during Velocity's `PermissionsSetupEvent`, such as LuckPerms, are ready in time.

## Other login plugins

Plugins that deny logins at `LoginEvent` with a higher priority (ban plugins, for example) run first. OriginGate skips a login that is already denied, so it makes no API request for it.

## Settings to review

- `lookup.proxycheck.api-keys`: add your keys. Watch usage on the proxycheck.io dashboard, since an invalid key is not reported as an error.
- `lookup.on-lookup-failure` and `lookup.wait-millis`: how long a login may wait, and what happens when the lookup providers are down. Give `origingate.alerts` to staff so they hear when lookups stop working, and run `origingate status` to see the providers' state.
- `lookup.max-lookups-per-minute`: a limit keeps a bot flood from using up your daily lookups. See [flood protection](configuration.md#flood-protection).
- `rules.hosting` and `rules.deny-networks`: catch VPNs and bots on rented servers that providers do not flag. Try them in dry-run first.
- `storage`: for several proxies, use one MySQL/MariaDB database, so each IP is looked up once for all of them.
- `messages.yml`: add your support link or other text to the kick screens.
