---
title: Teleport, Spawn, Homes, and Warps
description: Detailed guide for movement-related Mystic Essentials features.
---

# Teleport, Spawn, Homes, and Warps

## Teleport pipeline

All player movement routes through Mystic's `TeleportService` so behavior is consistent across TPA, spawn, homes, warps, player warps, `/top`, and `/back`.

The pipeline is:

```text
request -> cost check -> cooldown check -> warmup -> cancellation watch -> move -> cooldown applied -> back location updated
```

Warmups can be cancelled by movement, damage, disconnects, or world changes. Admin/system calls can use immediate teleports internally.

## TPA

Players can request movement in two directions:

- `/tpa <player>` asks to go to the target.
- `/tpahere <player>` asks the target to come to the requester.

Players accept or deny with `/tpaccept [player]` and `/tpdeny [player]`. If multiple requests are pending, omitting the player uses the newest request.

The `/tpa` UI shows request state and favorite players. Vanished players are hidden when MysticVanish is installed.

## Admin teleports

`/tphere <player>` teleports one player to the executor.

`/tpall` teleports every online player to the executor. Use this carefully on live servers.

`/top` finds the highest available position above the player and teleports them there.

## Back

`/back` returns players to their previously tracked location. Mystic records back locations through the player profile service so modules can share the same history.

## Spawn

Global spawn is used by `/spawn`. Per-world spawn can be used for world-specific behavior. First-join and join teleport behavior are controlled in `modules/spawn/config.json`.

`/setspawn` and `/setworldspawn` cannot be used in temporary worlds.

## Homes

Homes are per-player saved locations. Players can use commands or the Homes UI to teleport, rename, move, and delete homes.

Home limits are resolved from:

1. `mysticessentials.home.limit.unlimited`
2. Highest granted `mysticessentials.home.limit.<n>`
3. `defaultHomeLimit` from spawn config

## Server warps

Server warps are staff-owned destinations. They support:

- Name.
- Description.
- Location.
- Optional permission.
- Visibility mode.
- Economy cost.

Visibility rules:

| Visibility | Behavior |
| --- | --- |
| Public | Everyone with `mysticessentials.warp.use` can see/use |
| Permission | Only players with the warp permission can see/use |
| Hidden | Hidden from normal lists |

## Player warps

Player warps are globally named public destinations created by players. A player's limit is resolved from:

1. `mysticessentials.playerwarp.limit.unlimited`
2. Highest granted `mysticessentials.playerwarp.limit.<n>`
3. Default of `1`

Player warps can have descriptions and economy costs. Owners manage them in `/pwarp manage`.
