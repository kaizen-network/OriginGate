---
title: Messages
description: Kick screens, bypass notices, staff alerts, and their placeholders in messages.yml.
order: 5
---

# Messages

`messages.yml` is in `plugins/origingate/`. Messages use [MiniMessage](https://docs.advntr.dev/minimessage/format.html). Run `origingate reload` after editing.

| Key | When |
| --- | --- |
| `kick.deny-addresses`, `kick.vpn`, `kick.proxy`, `kick.country` | Kick screen for that rule |
| `kick.lookup-failure` | Kick screen when `on-lookup-failure: deny` |
| `bypass-notice` | Chat message after joining the first server through a rule bypass. Empty turns it off |
| `alerts.denied`, `alerts.bypassed` | Chat alert to staff. Empty turns it off |

## Placeholders

| Placeholder | Value |
| --- | --- |
| `<username>`, `<uuid>`, `<ip>` | The connecting player |
| `<rule>` | `deny-addresses`, `vpn`, `proxy`, `country`, or `lookup-failure` |
| `<time>` | Unix time in seconds |
| `<provider>`, `<country>`, `<country_code>`, `<city>`, `<region>`, `<type>` | Lookup data |
| `<organisation>` | The VPN operator's name when it is longer than 3 characters, otherwise the network organisation |

Unknown values show as `-`. Placeholder values are inserted as plain text, so formatting tags inside a provider name are shown as text, not applied. The default kick screens do not show `<ip>`.

If a kick message cannot be built, the player is still kicked with a plain "You cannot join this server." text, and a warning is logged.
