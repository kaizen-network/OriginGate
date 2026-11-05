---
title: Commands and permissions
description: Check an IP, see lookup status, reload settings, clear saved lookups, and the bypass and alert permissions.
order: 8
---

# Commands and permissions

## Commands

Use them in game with `/`, or in the console without it.

| Command | What it does | Permission |
| --- | --- | --- |
| `origingate check <player\|ip>` | Shows the IP data and which rule would apply | `origingate.command.check` |
| `origingate check <player\|ip> refresh` | Same, but asks the lookup providers again | `origingate.command.check` |
| `origingate status` | Lookups since the plugin started (from memory, storage, or providers, and how many failed, were dropped, or were over the budget), the cache hit rate, the lookup budget, and for each provider: working, failing, or paused, its requests today, and any paused API keys | `origingate.command.status` |
| `origingate reload` | Reloads `config.yml` and `messages.yml`. A file with a mistake is refused and the current settings stay | `origingate.command.reload` |
| `origingate cache clear <ip\|all>` | Forgets saved lookups from memory and storage, so the next join looks the IP up again. For an IPv6 address, an answer kept for its /64 is forgotten too | `origingate.command.cache` |

## Permissions

| Permission | Effect |
| --- | --- |
| `origingate.bypass.deny-networks` | Not kicked by the deny-networks rule |
| `origingate.bypass.vpn` | Not kicked by the vpn rule |
| `origingate.bypass.proxy` | Not kicked by the proxy rule |
| `origingate.bypass.hosting` | Not kicked by the hosting rule |
| `origingate.bypass.country` | Not kicked by the country rule |
| `origingate.alerts` | Sees kicks and bypasses in chat, plus one message when a lookup provider stops answering or the lookup budget is used up, and one when that is over |

These are the default names. You can change them, or list several per rule, in `config.yml`. Having any one of a rule's permissions is enough. `bypass.permissions` in `config.yml` can also list permissions that skip every check.

In offline mode, anyone can join with any name, and permissions follow the name. See [offline mode and proxies](configuration.md#offline-mode-and-proxies).
