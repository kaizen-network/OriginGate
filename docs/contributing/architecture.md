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
- Single flight: one request per IP at a time; other logins for that IP wait on it. `refresh` never joins a normal lookup.
- 4 worker threads with a queue of 256. A full queue fails the lookup.
- The result is handed to the waiting login before the save (an upsert with `REPLACE INTO`), so a slow database does not hold the login.
- A failed storage read or save pauses storage for 60 seconds.
- A result without a country code is a failure, is not saved, and pauses that IP for 5 minutes. `refresh` and `cache clear` skip the pause.
- Lookups with no VPN check (`vpn-from: []`) stay in memory only, so a later config with VPN checks does not reuse them.
- Provider keys that are refused or rate-limited are skipped for 60 seconds; a provider with all keys refused is skipped for 60 seconds.
- On reload, logins already in progress finish on the old runtime, which is then closed in the background.
- IP parsing is literal-only; no DNS lookups.
- Every query uses prepared statements.
