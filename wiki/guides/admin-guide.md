# Admin Guide

This page focuses on how to operate Mystic Essentials on a live server.

## Reloading safely

Use:

```text
/mystic reload
```

This reloads core configuration and enabled modules. It is safe for most text, permission, channel, kit, and module setting changes. Restart the server when changing storage provider, database connection details, Redis connection details, installed integrations, or the mod jar itself.

## Module management

Modules are toggled in `mods/MysticEssentials/config.json` under `modules`.

Default modules:

```json
{
  "modules": {
    "teleportation": true,
    "spawn": true,
    "warps": true,
    "mail": true,
    "announcements": true,
    "afk": true,
    "chat": true,
    "greetings": true,
    "kits": true,
    "flight": true,
    "inventory": true,
    "nick": true,
    "patchnotes": true,
    "tutorial": true,
    "customcommands": true,
    "playervaults": false
  }
}
```

Some modules depend on others. Warps and spawn features route movement through the teleport service, so keep `teleportation` enabled when you use spawn, homes, warps, player warps, RTP, or AFK rewards. **Player Vaults ships disabled** — set `"playervaults": true` here and `enabled: true` in its own config to turn it on.

## Staff setup checklist

Recommended staff permissions:

| Staff role | Suggested nodes |
| --- | --- |
| Helper | `mysticessentials.teleport.tpa`, `mysticessentials.spawn.use`, `mysticessentials.home.*`, `mysticessentials.mail.use`, `mysticessentials.chat.private.*` |
| Moderator | Helper nodes plus `mysticessentials.teleport.tphere`, `mysticessentials.teleport.back`, `mysticessentials.chat.socialspy`, staff channel nodes |
| Admin | Moderator nodes plus `mysticessentials.reload`, `mysticessentials.spawn.set`, `mysticessentials.warp.set`, `mysticessentials.mail.send.all`, `mysticessentials.inventory.restore` |
| Owner | Full `mysticessentials.*` or explicit all-module nodes |

Avoid giving broad admin-only commands such as `/tpall`, `/clearinventory all`, `/mail sendall`, and `/kit give` to general staff unless they need them.

## Setting spawn

Use `/setspawn` at the location players should land when running `/spawn`. Use `/setworldspawn` when each world needs its own spawn.

Relevant settings in `modules/spawn/config.json`:

| Setting | Default | Notes |
| --- | --- | --- |
| `globalSpawnEnabled` | `true` | Enables a single global spawn |
| `perWorldSpawnEnabled` | `true` | Enables per-world spawn tracking |
| `syncGlobalSpawnToWorldProvider` | `true` | Keeps global spawn aligned with the world provider when supported |
| `teleportOnFirstJoin` | `true` | Sends new players to spawn |
| `teleportOnJoin` | `false` | Sends every joining player to spawn |
| `defaultHomeLimit` | `3` | Used when no permission limit overrides it |

`/setspawn` and `/setworldspawn` refuse temporary worlds.

## Managing server warps

Use `/setwarp <name>` to create or move a warp to your current position. Use `/delwarp <name>` to delete.

Admins can also use the Warps UI. Players with `mysticessentials.warp.set` see a Manage button on selected warps and can edit:

| Field | Meaning |
| --- | --- |
| Name | Public warp name |
| Description | Shown in the UI |
| Permission | Optional permission required to see/use the warp |
| Cost | Economy cost charged on teleport |
| Visibility | Public, permission-gated, or hidden |
| Location | Captured from the admin when saving or when explicitly moving |

Server warps are stored in `data/modules/warps/server.json`.

## Managing player warps

Player warps are globally named. Two players cannot own a player warp with the same name.

Owners can use `/pwarp manage` to rename, describe, price, move, or delete their own warps. Staff with `mysticessentials.playerwarp.admin` can delete any player warp.

Player warps are stored in `data/modules/warps/playerwarps.json`.

## Random Teleport setup

RTP is configured in `modules/teleportation/rtp.json`. A worked-example `default-wilderness` profile is generated on first run.

1. Edit or add profiles under `profiles` — set each profile's `world`, `shape`, `center`, `minimumRadius`/`maximumRadius`, and `cooldownSeconds`.
2. Use `/rtpadmin test <profile>` to run a search and confirm it finds safe spots, and `/rtpadmin debug <profile>` to see why candidates are rejected.
3. Use `/rtpadmin setcenter <profile>` to set a profile's centre to your current position, and `/rtpadmin preview <profile>` to review its bounds.
4. Gate premium worlds/profiles with `mysticessentials.teleport.rtp.world.<world>` and `...rtp.profile.<profile>`.
5. `/rtpadmin reload` applies config changes; `/rtpadmin clearcache` drops cached destinations after big edits.

See the [Random Teleport](rtp-module) page for the full profile field list.

## Rank icons setup

Rank icons (`modules/chat/rank-icons.json`) map LuckPerms groups to inline chat images. They are disabled by default and require LuckPerms.

1. Set `enabled: true` and add `{rank_icon}` (and optionally `{rank_icon_space}`) to your chat formats.
2. Drop PNGs into the rank-icon uploads directory, then `/rankicon import <icon> <file> <group>`.
3. `/rankicon validate` and `/rankicon status` confirm files, hashes, and mappings are healthy.
4. `/rankicon previewrank <player>` shows what a given player resolves to; `/rankicon debug <player>` prints the full resolution trace.

Newly staged assets may report `pending restart` until `/rankicon rebuild` or a restart registers them. See the [Rank Icons](rankicons-module) page.

## Mail operations

Use `/mail sendall <message>` for server-wide mail. The Mail UI also exposes a server-wide row for admins with `mysticessentials.mail.send.all`.

Mail can target offline players who have joined before. Mystic maintains a username-to-UUID index from player joins, so offline mail by name works only after a player is known to the server or shared SQL storage.

## Inventory recovery

Mystic can snapshot inventories on join, leave, death, and timed intervals. Staff can open the restore UI with:

```text
/inventory restore <player>
```

Before clearing inventories, Mystic takes a manual snapshot. Players with `mysticessentials.inventory.protect` are skipped by `/clearinventory all`.

## Cross-server networks

Use MySQL or MariaDB for shared persistent data. Use Redis for live cross-server features:

| Feature | Needs Redis |
| --- | --- |
| Cross-server broadcasts | Yes |
| Cross-server private messages | Yes |
| Cross-server chat channels | Yes |
| Temporary channel restore after restart | Yes |
| Shared player profiles/mail/warps | No, use SQL storage |

Each server should have a unique `storage.redis.serverId` and the same `storage.redis.networkId`.

## Backups

For JSON storage, back up:

```text
mods/MysticEssentials/config.json
mods/MysticEssentials/messages/
mods/MysticEssentials/modules/
mods/MysticEssentials/data/
```

For SQL storage, back up the `mystic_documents` table and still keep copies of configuration and message files from disk.