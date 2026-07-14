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

## Staff teleport tools

| Command | What it does | Permission |
| --- | --- | --- |
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

Players with `mysticessentials.teleport.bypass.warmup` skip warmups; `mysticessentials.teleport.bypass.cooldown` skips cooldowns. RTP has its own separate config file and bypass nodes.

## See also

- [Random Teleport (RTP)](rtp-module)
- [Permissions Reference](permissions)
- [Configuration Reference](configuration)
