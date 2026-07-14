# Inventory

The Inventory module provides inventory-clearing commands and automatic inventory snapshots so a cleared or lost inventory can be restored.

## Clearing inventories

| Command | What it does | Permission |
| --- | --- | --- |
| `/clearinventory`, `/clearinv` | Clear your inventory (after taking a snapshot) | `mysticessentials.inventory.clear` |
| `/clearinventory <player>` | Clear another player's inventory | `mysticessentials.inventory.clear.others` |
| `/clearinventory all` | Clear every online player's inventory | `mysticessentials.inventory.clear.all` |
| `/inventory clear [player/all]`, `/inv clear [player/all]` | Alias path for the above | Same as above |

Players with `mysticessentials.inventory.protect` are skipped by `/clearinventory all`, so staff and event NPCs keep their gear during a mass wipe. Every clear takes a snapshot first, so it can be undone.

## Snapshots and restore

Mystic automatically snapshots inventories on join, leave, and death (and optionally on a timer), keeping up to `maxSnapshotsPerPlayer` per player. Staff can browse and restore them.

| Command | What it does | Permission |
| --- | --- | --- |
| `/inventory restore <player>` | Open the Snapshot Restore UI | `mysticessentials.inventory.restore` |

## Configuration

File:

```text
modules/inventory/config.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `snapshotOnJoin` | `true` | Snapshot inventory on join |
| `snapshotOnLeave` | `true` | Snapshot inventory on leave |
| `snapshotOnDeath` | `true` | Snapshot inventory on death |
| `timedSnapshotMinutes` | `0` | Periodic snapshot interval; `0` disables |
| `maxSnapshotsPerPlayer` | `24` | Retained snapshots per player |

## See also

- [Permissions Reference](permissions)
- [Configuration Reference](configuration)
