---
title: Messages
description: Kick screens, bypass notices, staff alerts, and their placeholders in messages.yml.
order: 5
---

# Messages

`messages.yml` is in `plugins/origingate/`. Messages use [MiniMessage](https://docs.advntr.dev/minimessage/format.html). Run `origingate reload` after editing.

Bukkit/Spigot/Paper and BungeeCord receive messages as legacy text, so hover and click events are only shown on Velocity. Hex colours and gradients work on 1.16+ servers (Bukkit) or for 1.16+ clients (BungeeCord); otherwise each colour becomes the nearest of the 16 named ones.

| Key | When |
| --- | --- |
| `kick.deny-list`, `kick.deny-addresses`, `kick.deny-networks`, `kick.vpn`, `kick.proxy`, `kick.hosting`, `kick.country` | Kick screen for that rule |
| `kick.lookup-failure` | Kick screen when `on-lookup-failure: deny`, or `on-budget-exceeded: deny` for a login over the lookup budget |
| `bypass-notice` | Chat message after joining the first server through a rule bypass. Empty turns it off |
| `alerts.denied`, `alerts.bypassed` | Chat alert to staff. Empty turns it off |
| `alerts.lookup-problem`, `alerts.lookup-recovered` | Chat alert to staff when lookups start failing and when they work again. The text is in `<message>`; the other placeholders do not apply. Empty turns it off |

Messages added in 1.1.0 (`kick.deny-networks`, `kick.hosting`, `alerts.lookup-problem`, and `alerts.lookup-recovered`) and 1.2.0 (`kick.deny-list`) may be missing from an older `messages.yml`. Then the bundled text is used.

## Placeholders

| Placeholder | Value |
| --- | --- |
| `<username>`, `<uuid>`, `<ip>` | The connecting player |
| `<rule>` | `deny-list`, `deny-addresses`, `deny-networks`, `vpn`, `proxy`, `hosting`, `country`, or `lookup-failure` |
| `<time>` | Unix time in seconds |
| `<provider>`, `<country>`, `<country_code>`, `<city>`, `<region>`, `<type>` | Lookup data |
| `<organisation>` | The VPN operator's name when it is longer than 3 characters, otherwise the network organisation |

Unknown values show as `-`. Placeholder values are inserted as plain text, so formatting tags inside a provider name are shown as text, not applied. The default kick screens do not show `<ip>`.

If a kick message cannot be built, the player is still kicked with a plain "You cannot join this server." text, and a warning is logged.
