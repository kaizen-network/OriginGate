# Architecture

## Connection flow

OriginGate listens to Velocity's `LoginEvent` and returns an asynchronous `EventTask`, so the proxy's network threads are never blocked. The login is held while the check runs. A kick uses `ResultedEvent.ComponentResult.denied(...)`, and Velocity then disconnects the player before connecting to any backend server.

In Velocity's `AuthSessionHandler`, `LoginEvent` fires only after `PermissionsSetupEvent` has finished. `PostLoginEvent` and then `PlayerChooseInitialServerEvent` follow only when the login was allowed.

If another plugin already denied the login, OriginGate does nothing, so no API request is made.

## Permission timing (LuckPerms)

Bypass permissions must be checked after LuckPerms has loaded the player. From LuckPerms' `VelocityConnectionListener`:

- User data is loaded during `PermissionsSetupEvent`. LuckPerms holds that event until loading finishes.
- Its `LoginEvent` handlers run at `PostOrder.FIRST` (denies the login when loading failed) and at the default order (checks that the loaded data is there).

So permissions are ready by the time any `LoginEvent` handler runs. OriginGate subscribes with `priority = -100`, which in Velocity means after the default priority 0. It also runs after LuckPerms' failure check, which is why a login LuckPerms denied is skipped.

## ConsentGate

ConsentGate holds `PlayerChooseInitialServerEvent`, which fires after `LoginEvent`. A player kicked by OriginGate never reaches the consent dialog. See the [ConsentGate compatibility run](testing.md#consentgate-compatibility-run).

## Lookups

- Memory cache first, then storage (rows younger than `max-age-days`), then the providers.
- Memory and storage keep answers per address. An IPv6 answer that `Gate.match` says a rule acts on is also cached under its /64 (`Addresses.sharedNetwork`, which skips Teredo `2001::/32`). The order is: the address in memory, the address in storage, then its /64's entry, so an address's own answer always wins, also right after a restart. While storage cannot be read, the providers are asked before the /64 entry is used. When a login's own lookup fails for any reason, `Gate` uses `LookupService.networkFallback` (the /64 entry) before `on-lookup-failure` or `on-budget-exceeded`. Teredo and the well-known NAT64 prefixes have no /64 key. Clean answers are never shared, since hosts may split a /64 between customers.
- Single flight: one request per IP at a time; other logins for that IP wait on it. `refresh` never joins a normal lookup.
- 4 worker threads with a queue of 256. A full queue fails the lookup.
- Each caller passes how long it waits (`wait-millis` for logins, no limit for commands and the Bukkit paths nobody waits on). A flight keeps the latest deadline of its callers. A worker drops a flight whose deadline has passed only while the queue is backed up: at least as many flights still wait behind it as the executor has core threads. Otherwise it runs and fills the cache, as in 1.0.0. Bukkit's background lookup with no waiter passes a zero wait, so it is treated the same way.
- `max-lookups-per-minute` is a sliding 60-second window over provider lookups (not memory or storage answers, not `refresh`). Over it, the lookup fails with `BudgetExceededException`, and `Gate` applies `on-budget-exceeded`, which defaults to `on-lookup-failure`. The budget's recovery alert waits for 60 seconds without a rejected login. The time of the last rejection and the count of skipped logins live in `LookupStats`, so a reload during a flood does not end it.
- The result is handed to the waiting login before the save (an upsert with `REPLACE INTO`), so a slow database does not hold the login.
- A failed storage read or save pauses storage for 60 seconds.
- A result without a country code is a failure, is not saved, and pauses that IP (not its /64) for 5 minutes. `refresh` and `cache clear` skip the pause.
- A SQLite file that cannot be opened (no native library, damaged, locked, or not writable) gives memory-only storage with a logged error instead of no runtime. Config errors still leave no runtime, as in 1.0.0.
- The SQLite and MariaDB drivers live in their own class loader (`IsolatedDrivers`), created when storage first opens at startup. The JAR may be renamed, deleted, or replaced while the server runs: classes still load from the file opened at startup, but ServiceLoader opens the JAR again by its path on every database connection, so the loader serves the drivers' `META-INF/services` files from memory. `ConfigLoader` keeps the bundled `config.yml` and `messages.yml` in memory for the same reason. SQLite unpacks its native library on first use, so switching to SQLite by reload while the JAR is gone gives memory-only storage until a restart.
- Lookups with no VPN check (`vpn-from: []`) stay in memory only, so a later config with VPN checks does not reuse them.
- Provider keys that are refused or rate-limited are skipped for 60 seconds; a provider with all keys refused is skipped for 60 seconds.
- `ProviderChain` keeps each provider's health and reports a pause, or 3 or more failures in a row, as `Alerts.problem("provider <name>")`, and every answer as `Alerts.recovered`. `KeyRotation` does the same per key, but only for a key refused while another key answered. `RuntimeController` owns one `Alerts.Once`, which sends a problem only when it is new for its key and a recovery only after a sent problem, so a reload keeps the alert state. It logs alerts and hands them to the platform, which sends them to staff; a retired runtime's alerts are dropped.
- `LookupStats` counts answers by source, failures, drops, budget refusals, and provider requests per day. It lives in `RuntimeController`, so it survives reloads, and feeds `origingate status`.
- On reload, logins already in progress finish on the old runtime, which is then closed in the background.
- IP parsing is literal-only; no DNS lookups.
- Every query uses prepared statements.
