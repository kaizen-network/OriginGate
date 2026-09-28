---
title: Installation
description: Install OriginGate on a Velocity proxy and turn on your first rules.
order: 2
---

# Installation

## Requirements

- Velocity 3.4.0 or newer, on Java 21 or newer.
- A proxycheck.io API key is optional. Without one, proxycheck.io allows 100 lookups per day; a free account raises that to 1,000. Other providers are optional; see [providers](providers.md).

Nothing else is needed. SQLite is built in, and MySQL/MariaDB support is bundled.

## Install

1. Download `OriginGate-Velocity-<version>.jar` from the [releases page](https://github.com/kaizen-network/OriginGate/releases), or [build it from source](https://github.com/kaizen-network/OriginGate/blob/main/docs/contributing/development.md).
2. Put it in the proxy's `plugins` folder and start the proxy. OriginGate creates `plugins/origingate/config.yml` and `messages.yml`.
3. In `config.yml`, add your API keys and turn on the rules you want. Setting `dry-run: true` for the first days is a good idea.
4. Run `origingate reload` in the console.

Each check prints one line in the console, for example:

```
DENY rule=vpn player=ExamplePlayer ip=203.0.113.7 provider="Example Hosting" country="Netherlands" ... note="flagged as VPN"
```

`console-log` in `config.yml` sets how many of these lines you see. See [logging](how-it-works.md#logging).

Before kicking real players, follow the [rollout checklist](rollout.md).

## Updating

Stop the proxy, replace the JAR, and start it again. Your `config.yml` and `messages.yml` are kept, so new settings are not added to them automatically. Compare with the [default config](https://github.com/kaizen-network/OriginGate/blob/main/core/src/main/resources/config.yml) and [default messages](https://github.com/kaizen-network/OriginGate/blob/main/core/src/main/resources/messages.yml), and check the [changelog](https://github.com/kaizen-network/OriginGate/blob/main/CHANGELOG.md).
