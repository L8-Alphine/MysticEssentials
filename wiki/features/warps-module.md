# Warps

Warps are named teleport destinations. There are two kinds:

- **Server warps** — created by staff (spawn areas, shops, arenas, event zones).
- **Player warps** — public destinations created by players (their shop, base, or town).

Both types are browsable through an in-game UI and teleport through Mystic's central teleport pipeline, so warmups, cooldowns, and costs all apply.

## Server warps

| Command | What it does | Permission |
| --- | --- | --- |
| `/warp`, `/warps` | Open the Warps UI (lists warps in console) | `mysticessentials.warp.use` |
| `/warp <name>` | Teleport to a visible server warp | `mysticessentials.warp.use` |
| `/setwarp <name>` | Create or update a server warp at your position | `mysticessentials.warp.set` |
| `/delwarp <name>` | Delete a server warp | `mysticessentials.warp.set` |

Server warps have a **visibility**: `PUBLIC` warps show to everyone, while `PERMISSION` warps require a per-warp permission (assigned automatically when a warp is created with a permission). Warps can also carry a **cost**, charged through VaultUnlocked when an economy provider is present (free otherwise).

## Player warps

| Command | What it does | Permission |
| --- | --- | --- |
| `/pwarp`, `/pwarps`, `/playerwarp`, `/playerwarps` | Browse player warps | `mysticessentials.playerwarp.use` |
| `/pwarp <name>` | Teleport to a player warp | `mysticessentials.playerwarp.use` |
| `/pwarp create <name>` | Create a player warp at your position | `mysticessentials.playerwarp.create` |
| `/pwarp manage` | Open your Player Warp Manager | `mysticessentials.playerwarp.use` |
| `/pwarp delete <name>` | Delete your player warp | `mysticessentials.playerwarp.use` (any owner needs `mysticessentials.playerwarp.admin`) |

The Player Warp Manager UI lets owners edit a warp's description and cost.

### Player-warp limits

How many warps a player may own is permission-driven; the highest granted numeric node wins:

```text
lp group default   permission set mysticessentials.playerwarp.limit.1 true
lp group supporter permission set mysticessentials.playerwarp.limit.5 true
lp group staff     permission set mysticessentials.playerwarp.limit.unlimited true
```

## Storage

Warp definitions are stored through Mystic's [storage layer](storage) (JSON, MySQL, or MariaDB); there is no separate warps config file.

## See also

- [Permissions Reference](permissions)
- [Storage](storage)
