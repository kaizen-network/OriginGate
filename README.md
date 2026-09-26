# OriginGate

OriginGate is a Velocity plugin that checks where each player connects from. It can kick players who use a VPN or proxy, or who connect from a country you do not allow. The check runs during login, so a kicked player never reaches your backend servers.

- IP data comes from [proxycheck.io](https://proxycheck.io/) and is saved (SQLite, or MySQL/MariaDB), so each IP is looked up at most once every 30 days by default.
- Every rule is optional and has its own bypass permissions.
- Dry-run mode shows what would happen without kicking anyone.

## Requirements

- Velocity 3.4.0 or newer, on Java 21 or newer.
- A proxycheck.io API key is optional. Without one, proxycheck.io allows 100 lookups per day. A free account raises that to 1,000.

## Install

1. Put `OriginGate-Velocity-<version>.jar` in the proxy's `plugins` folder.
2. Start the proxy. OriginGate creates `plugins/origingate/config.yml` and `messages.yml`.
3. In `config.yml`, add your API keys and turn on the rules you want. Setting `dry-run: true` for the first days is a good idea.
4. Run `origingate reload` in the console.

Each check prints one line in the console, for example:

```
DENY rule=vpn player=ExamplePlayer ip=203.0.113.7 provider="Example Hosting" country="Netherlands" ... note="flagged as VPN"
```

`console-log` in `config.yml` sets how many of these lines you see.

## What gets checked

First, without looking anything up:

1. **Bypass list**: players, permissions, or IP addresses in `bypass` are let in right away.
2. **deny-addresses** rule: IP addresses or ranges you list are kicked.
3. **Private addresses** (LAN, localhost) are let in, since there is nothing to look up.

Then the IP is looked up (from memory, from storage, or from proxycheck.io), and the rules are checked in this order:

4. **vpn** rule: the IP belongs to a VPN.
5. **proxy** rule: the IP is a proxy, from a country not in `allowed-countries`.
6. **country** rule: the country is not on your allowlist (or is on your denylist).

The first rule that matches decides. A player with one of that rule's bypass permissions is let in and gets a short chat message. Everyone else is kicked with that rule's message from `messages.yml`.

If the lookup fails or takes longer than `wait-millis` (5 seconds by default), `on-lookup-failure` decides: `allow` (default) or `deny`.

## Commands

Use them in game with `/`, or in the console without it.

| Command | What it does | Permission |
| --- | --- | --- |
| `origingate check <player\|ip>` | Shows the IP data and which rule would apply | `origingate.command.check` |
| `origingate check <player\|ip> refresh` | Same, but asks proxycheck.io again | `origingate.command.check` |
| `origingate reload` | Reloads `config.yml` and `messages.yml`. A file with a mistake is refused and the current settings stay | `origingate.command.reload` |
| `origingate cache clear <ip\|all>` | Forgets saved lookups, so the next join looks the IP up again | `origingate.command.cache` |

## Permissions

| Permission | Effect |
| --- | --- |
| `origingate.bypass.vpn` | Not kicked by the vpn rule |
| `origingate.bypass.proxy` | Not kicked by the proxy rule |
| `origingate.bypass.country` | Not kicked by the country rule |
| `origingate.alerts` | Sees kicks and bypasses in chat |

These are the default names. You can change them, or list several per rule, in `config.yml`. Having any one of a rule's permissions is enough.

## More

- [How it works](docs/01-how-it-works.md)
- [Configuration and messages](docs/02-configuration.md)
- [Storage and privacy](docs/03-storage-and-privacy.md)
- [Development and test results](docs/04-development.md)
- [Rollout checklist](docs/05-rollout.md)

## License

GPL-3.0-only. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
