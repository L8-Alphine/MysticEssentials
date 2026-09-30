# Teleportation

The Teleportation module provides player-to-player teleport requests, staff teleport tools, a `/back` command, and the [Random Teleport (RTP)](rtp-module) subsystem. All teleports flow through Mystic's central teleport pipeline, so warmups, cooldowns, costs, movement/damage cancellation, and back-location tracking behave consistently.

## Teleport requests (TPA)

| Command | What it does | Permission |
| --- | --- | --- |
| `/tpa` | Open the Teleport Requests UI | `mysticessentials.teleport.tpa` |
| `/tpa <player>` | Ask to teleport to a player | `mysticessentials.teleport.tpa` |
| `/tpahere <player>` | Ask a player to teleport to you | `mysticessentials.teleport.tpa` |
| `/tpaccept [player]` | Accept the newest request, or one from a specific player | `mysticessentials.teleport.tpa` |
| `/tpdeny [player]` | Deny the newest request, or one from a specific player | `mysticessentials.teleport.tpa` |
| `/tpcancel` | Cancel your outgoing requests | `mysticessentials.teleport.tpa` |

Requests expire after `requestExpirySeconds`. When a request is accepted, the mover waits out `tpaWarmupSeconds`; moving or taking damage during the warmup cancels the teleport. A `tpaCooldownSeconds` cooldown then applies.

The Teleport Requests UI includes **favorites**, so players can keep frequent friends handy and fire TPA / TPA Here requests with one click.

### Cross-server requests (Redis)

With Redis enabled (see [Storage](storage)), `/tpa`, `/tpahere` and the Teleport Requests UI see every player on the network, and a request can target a player on another server:

- `/tpa <player>` and `/tpahere <player>` tab-complete network-wide names; players on other servers are listed with their server id in the UI.
- The request is stored in Redis (keyed by the target, expiring with `requestExpirySeconds`) **and** announced over pub/sub. The target's server pulls the stored requests whenever the player opens the UI or runs `/tpaccept` / `/tpdeny`, so a request still shows up if the announcement was missed or the target switched servers meanwhile. Notifications and UI rows name the requester's server, e.g. `Aether (survival-2) wants to teleport to you`.
- On accept, the player who has to move is referred to the other server through the engine's own transfer (`ClientReferral`: the client reconnects to that server's address itself). The arrival details travel in the referral payload the engine hands the destination on connect, with a Redis copy as fallback. Once the player is ready in a world there, the teleport completes automatically after `crossServer.arrivalDelaySeconds` — with no warmup and no movement check, since consent was already given and a freshly spawned player settles for a moment.
- `/tpcancel` also withdraws the copies stored in Redis.
- The staff tools `/tp <player>` and `/tphere <player>` accept players on other servers too: `/tp` sends you to that server and completes the move on arrival, `/tphere` brings the player to your server and then to you. `/tp world`, `/tpall` and the other player-name commands (`/fly`, `/kit give`, `/nick`, inventory tools) act on this server only.

`/mystic network` (`mysticessentials.network`) shows what this server advertises and which other servers it sees; a transfer also logs `Referring <player> to '<server>' at host:port` on the sending server and `Arrival for <player> received in the referral payload` on the destination.

Behind a network proxy (MysticGate), set `storage.redis.proxyHost`/`proxyPort` on every server instead: players are referred to the proxy, which routes on the payload's destination server — see the proxy contract in [Storage](storage). Otherwise, server handoff needs each server to advertise the address clients should connect to. That is detected automatically (the bound game port plus the bound interface or first LAN/public IPv4) and logged at startup; set `storage.redis.advertisedHost` / `advertisedPort` in `config.json` only where the detected address is not what players can reach (NAT, Docker, a proxy). Accepting a request whose destination has no usable address replies `Cross-server teleport is not available: server <id> has no advertised address.` and logs a warning.

## Staff teleport tools

| Command | What it does | Permission |
| --- | --- | --- |
| `/tp world <player> <world>` | Send a player to another world's spawn point | `mysticessentials.teleport.tp.world` |
| `/tphere <player>` | Teleport one player to you | `mysticessentials.teleport.tphere` |
| `/tpall` | Teleport every online player to you | `mysticessentials.teleport.tpall` |
| `/top` | Teleport to the highest position above you | `mysticessentials.teleport.top` |

## Back

`/back` returns you to your previous location (before your last teleport or, where tracked, death). It has its own `backWarmupSeconds` and `backCooldownSeconds`.

| Command | What it does | Permission |
| --- | --- | --- |
| `/back` | Return to your previous location | `mysticessentials.teleport.back` |

## Random Teleport

`/rtp` and `/rtpadmin` are documented on their own page — see [Random Teleport (RTP)](rtp-module).

## Configuration

File:

```text
modules/teleportation/config.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `requestExpirySeconds` | `60` | Pending `/tpa` and `/tpahere` lifetime |
| `tpaWarmupSeconds` | `3` | Warmup after a request is accepted |
| `tpaCooldownSeconds` | `5` | Cooldown between TPA uses |
| `backWarmupSeconds` | `0` | Warmup for `/back` |
| `backCooldownSeconds` | `5` | Cooldown between `/back` uses |
| `crossServer.enabled` | `true` | Use Redis for network-wide requests and server handoff (needs `storage.redis.enabled`) |
| `crossServer.arrivalDelaySeconds` | `1` | Delay after a transferred player is ready in the destination world before the teleport completes |
| `crossServer.arrivalTimeoutSeconds` | `30` | How long an accepted transfer waits for the player to arrive before it is dropped |
| `worldWhitelist` / `worldBlacklist` | `[]` | Destination worlds allowed / blocked for every teleport routed through the module |

Players with `mysticessentials.teleport.bypass.warmup` skip warmups; `mysticessentials.teleport.bypass.cooldown` skips cooldowns. RTP has its own separate config file and bypass nodes.

## See also

- [Random Teleport (RTP)](rtp-module)
- [Permissions Reference](permissions)
- [Configuration Reference](configuration)
