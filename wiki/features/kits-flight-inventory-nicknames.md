---
title: Kits, Flight, Inventory, and Nicknames
description: Utility modules for rewards, movement, recovery, and display names.
---

# Kits, Flight, Inventory, and Nicknames

## Kits

Kits are configured bundles of item IDs and quantities. Each kit can have:

- A description.
- A cooldown.
- One-time claim behavior using `cooldownSeconds: -1`.
- Required online/playtime seconds.
- A permission requirement.
- An economy cost.

The `firstJoinKit` is granted automatically the first time a player joins.

Kit item example:

```json
{
  "itemId": "Food_Apple",
  "quantity": 8
}
```

## Flight

`/fly` toggles personal flight. `/fly <player>` toggles another player's flight for staff.

Paid flight charges `costPerMinute` once per minute. If a player cannot pay, flight is disabled. Players with `mysticessentials.fly.free` or `mysticessentials.fly.unlimited` are exempt.

Speed multipliers can adjust horizontal and vertical flight speed where supported by the Hytale server API.

## Inventory snapshots

Mystic can capture inventory snapshots on:

- Join.
- Leave.
- Death.
- Timed intervals.
- Manual clear operations.

Snapshots include sections, slots, item IDs, quantities, durability, max durability, and metadata where available.

Staff restore snapshots with:

```text
/inventory restore <player>
```

## Inventory clearing

Clear commands take snapshots first. `/clearinventory all` skips players with `mysticessentials.inventory.protect`.

## Nicknames

Nicknames can be set through command or UI. The visible length is checked after color codes are stripped.

Real usernames are blocked for other players, and configured blocked names prevent impersonation of staff/server identities.

The default marker is `~`, controlled by:

```json
{
  "nickMarker": "~",
  "nickFormat": "{marker}{nickname}"
}
```

Set `nickFormat` to `{nickname}` if you do not want a marker.
