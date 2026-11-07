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
| `origingate allow add <player\|ip\|cidr> [duration] [note]` | Adds a player name, UUID, IP address, or CIDR range to the allow list | `origingate.command.allow` |
| `origingate allow remove <player\|ip\|cidr>` | Removes an entry from the allow list | `origingate.command.allow` |
| `origingate allow list [page]` | Shows the allow list, 10 entries per page | `origingate.command.allow` |
| `origingate deny add <player\|ip\|cidr> [duration] [note]` | Adds an entry to the deny list and disconnects matching players who are online on this server | `origingate.command.deny` |
| `origingate deny remove <player\|ip\|cidr>` | Removes an entry from the deny list | `origingate.command.deny` |
| `origingate deny list [page]` | Shows the deny list, 10 entries per page | `origingate.command.deny` |

### Allow and deny lists

A duration is a number and a unit: `s` (seconds), `m` (minutes), `h` (hours), `d` (days), or `w` (weeks), from `1s` to `3650d`. Use `never` or leave it out for an entry that does not expire. If the word after the target is not a duration, it starts the note. A note can be up to 255 characters. A note whose first word looks like a duration (for example `5m`) is read as the duration.

Examples: `origingate allow add Kai 7d trusted tester`, `origingate deny add 203.0.113.0/24 never spam network`.

`origingate.command.allow` and `origingate.command.deny` are separate, so you can let someone manage one list and not the other. Both are for operators by default on Bukkit.

`deny add` disconnects matching players who are online on that server right away, with the `deny-list` kick message. With `dry-run: true` nobody is disconnected. Players on other servers that share the same MySQL database are turned away at their next login, within about 30 seconds.

If SQLite cannot run, the lists live in memory until a restart, and the reply says so.

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
