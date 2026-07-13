# Migration Guide

This guide explains how to move an existing Hytale server from another
essentials-style mod to Mystic Essentials using the built-in in-game migration
command.

The migration system is file-based. The old mod jar does not need to be loaded
on the server and does not need to be present in `mods/`. You only need a copy
of the old mod's data folder or exported data files.

## Supported Sources

| Source mod | Command source | Default folder checked when no path is passed |
| --- | --- | --- |
| EssentialsPlus | `essentialsplus` | `mods/EssentialsPlus` |
| Hyssentials | `hyssentials` | `mods/Hyssentials` |
| EliteEssentials | `eliteessentials` | `mods/EliteEssentials` |
| Essentials | `essentials` | `mods/Essentials` |
| HyEssentialsX | `hyessentialsx` | `mods/HyEssentialsX`, then `mods/hyessentialsx` |
| Automatic scan | `auto` | All supported sibling folders under `mods/` |

Use the specific source when you know which mod produced the data. Use `auto`
when you copied several legacy data folders into the server's `mods/` folder and
want Mystic to scan all likely locations.

## Permission

Only trusted administrators should run migration commands.

```text
mysticessentials.migrate
```

The command is available under all Mystic core command aliases:

```text
/mystic migrate
/mysticessentials migrate
/me migrate
```

## Command Summary

Always scan before importing.

```text
/mystic migrate scan <source> [path]
/mystic migrate import <source> [path] [--replace] [--dry-run]
```

Examples:

```text
/mystic migrate scan essentialsplus
/mystic migrate import essentialsplus
```

```text
/mystic migrate scan hyessentialsx "mods/HyEssentialsX"
/mystic migrate import hyessentialsx "mods/HyEssentialsX"
```

```text
/mystic migrate scan auto
/mystic migrate import auto --dry-run
```

```text
/mystic migrate import eliteessentials "backups/old-server/mods/EliteEssentials" --replace
```

## What The Importer Moves

The current built-in importer focuses on data that maps cleanly into Mystic
Essentials without needing the old mod's runtime classes.

| Legacy data | Mystic destination | Notes |
| --- | --- | --- |
| Homes | Player profile `moduleData.homes` | Requires an owner UUID in the file, object, folder name, or file name |
| Global spawn | `mods/MysticEssentials/modules/spawn/config.json` | Imports world, x, y, z, yaw, and pitch when present |
| Per-world spawns | `mods/MysticEssentials/modules/spawn/config.json` | Preserves world names or ids when available |
| Server warps | `mods/MysticEssentials/data/modules/warps/server.json` | Preserves name, location, description, permission, category, visibility, and cost when present |
| Player warps | `mods/MysticEssentials/data/modules/warps/playerwarps.json` | Requires owner UUID to be treated as a player warp |
| Kits | `mods/MysticEssentials/modules/kits/config.json` | Imports common JSON kit layouts and simple TOML kit files |
| Essentials TOML spawn settings | `modules/spawn/config.json` | Imports first-join and every-join spawn booleans when present |

The importer scans `.json` and `.toml` files. It understands common field names
such as `homes`, `warps`, `playerWarps`, `worldSpawns`, `globalSpawn`, `spawn`,
`kits`, `items`, `location`, `position`, `world`, `x`, `y`, `z`, `yaw`, and
`pitch`.

## What Still Needs Manual Migration

Some legacy essentials mods include features that do not belong to Mystic
Essentials or are not yet part of the built-in importer.

| Legacy data or feature | Recommended action |
| --- | --- |
| Economy balances | Move to your VaultUnlocked-compatible economy provider |
| Shops, auction houses, markets | Move to a dedicated economy/shop mod |
| Bans, mutes, warnings, freezes, command spy | Move to MysticModeration or another moderation mod |
| Vanish data | Move to MysticVanish |
| Ranks and groups | Rebuild in LuckPerms |
| Chat formats | Manually convert into `modules/chat/config.json` |
| MOTD, join, leave, first-join text | Manually convert into `modules/greetings/config.json` |
| Mail | Manual migration until a source-specific mail importer is added |
| Nicknames | Manual migration until a source-specific nickname importer is added |
| RTP | Keep a dedicated RTP mod or add a future Mystic module |
| Scoreboards, holograms, stats, rankup | Move to dedicated mods or custom addons |

## Before You Start

Do the first migration on a copy of your server, not on the live production
server.

1. Back up the full old server folder.
2. Back up old `mods/`, old mod data folders, databases, and config files.
3. Install Mystic Essentials on a test server.
4. Start the test server once so Mystic creates its default folders.
5. Stop the test server.
6. Copy the old essentials data folder into a readable location.
7. Start the test server and run the scan command.

Do not run two essentials mods that register the same commands on a live server.
Command collisions are likely.

## Preparing Source Files

The easiest setup is to copy the old data folder beside Mystic's folder under
`mods/`.

Example layout:

```text
mods/
  MysticEssentials/
  EssentialsPlus/
```

Then run:

```text
/mystic migrate scan essentialsplus
/mystic migrate import essentialsplus
```

You can also pass an explicit path:

```text
/mystic migrate scan essentials "backups/old-server/mods/Essentials"
```

If the path contains spaces, wrap it in quotes. The command parser joins path
tokens until it sees flags such as `--replace` or `--dry-run`.

## Scan Output

`scan` never writes data. It reports:

| Field | Meaning |
| --- | --- |
| Path | The folder Mystic scanned |
| Files scanned | Number of supported `.json` and `.toml` files read |
| Failed | Files that could not be parsed |
| Homes | Home locations found with owner UUIDs |
| Server warps | Server-owned warps found |
| Player warps | Player-owned warps found |
| Spawns | Global plus per-world spawns found |
| Kits | Kit definitions found |
| Warnings | Up to several parse or detection warnings |

If the scan finds `0` for everything, confirm that you pointed Mystic at the
data folder, not the old jar file.

## Import Behavior

`import` applies the data found by the same scanner.

Default behavior is merge-only:

- Existing Mystic homes, warps, spawns, and kits are kept.
- Legacy entries with the same name are skipped.
- New legacy entries are added.
- Source files are never deleted or modified.

Use `--replace` to overwrite existing Mystic entries with matching names:

```text
/mystic migrate import essentialsplus --replace
```

Use `--dry-run` with `import` to follow the import code path without writing:

```text
/mystic migrate import hyssentials --dry-run
```

## Backups

Before the importer changes a Mystic module file, it writes a timestamped backup
next to that file.

Example:

```text
mods/MysticEssentials/modules/spawn/config.json
mods/MysticEssentials/modules/spawn/config.json.bak-20260706-145500
```

Backups are created for module files such as:

```text
mods/MysticEssentials/modules/spawn/config.json
mods/MysticEssentials/modules/kits/config.json
mods/MysticEssentials/data/modules/warps/server.json
mods/MysticEssentials/data/modules/warps/playerwarps.json
```

Player profiles are saved through Mystic's active storage provider. If your
server uses JSON storage, they live in:

```text
mods/MysticEssentials/data/players/<uuid>.json
```

If your server uses MySQL or MariaDB storage, imported player profile data is
written through that configured storage provider.

## Rollback

For a full rollback on a test server:

1. Stop the server.
2. Restore `mods/MysticEssentials/` from your pre-migration backup.
3. If using SQL storage, restore the database backup too.
4. Start the server and verify `/spawn`, `/homes`, `/warps`, and `/kit`.

For a small rollback of module files only:

1. Stop the server.
2. Replace the changed file with the matching `.bak-YYYYMMDD-HHMMSS` backup.
3. Start the server or run `/mystic reload`.

Do not rely only on generated `.bak` files for production rollback. Keep a full
server backup.

## Per-source Notes

### EssentialsPlus

Use:

```text
/mystic migrate scan essentialsplus
/mystic migrate import essentialsplus
```

Likely clean imports:

- Homes.
- Server warps.
- Player warps when owner UUIDs are present.
- Global spawn and per-world spawns.
- Kits in common JSON layouts.

Manual review:

- Economy data should move to an economy provider.
- Moderation data should move to MysticModeration or another moderation mod.
- World border, sleep, protection, Discord, and custom commands need dedicated
  replacements.

### Hyssentials

Use:

```text
/mystic migrate scan hyssentials
/mystic migrate import hyssentials
```

Likely clean imports:

- Homes.
- Spawn data.
- Server warps.
- Basic location-shaped player warp data when owner UUIDs are present.

Manual review:

- Ranks should move to LuckPerms.
- Vanish should move to MysticVanish.
- RTP needs a separate RTP mod.
- Admin chat and chat formats should be rebuilt in Mystic's chat module.

### EliteEssentials

Use:

```text
/mystic migrate scan eliteessentials
/mystic migrate import eliteessentials
```

Likely clean imports:

- Homes.
- Global and per-world spawns.
- Server warps.
- Player warps.
- Kits.

Manual review:

- If the old server used SQL-only storage, export the tables or copy any local
  JSON export before using the file importer.
- Economy and moderation data should move to dedicated systems.
- Mail and nicknames need manual migration until source-specific import support
  is added.

### Essentials

Use:

```text
/mystic migrate scan essentials
/mystic migrate import essentials
```

Likely clean imports:

- Homes, spawns, and warps from JSON player/server data.
- `kits.toml` into Mystic kit definitions when item ids and quantities are in a
  simple TOML layout.
- `[spawn] first-join` and `[spawn] every-join` values from `config.toml`.

Manual review:

- Home limits from old config should become LuckPerms nodes such as
  `mysticessentials.home.limit.5`.
- Teleport delay and TPA expiration should be configured in Mystic's
  teleportation module.
- Chat and MOTD formats should be manually converted.

### HyEssentialsX

Use:

```text
/mystic migrate scan hyessentialsx
/mystic migrate import hyessentialsx
```

Likely clean imports:

- Homes.
- Spawns.
- Server warps.
- Player warps.
- Kits.

Manual review:

- Economy balances, shops, auctions, holograms, scoreboards, rankup, stats,
  moderation, and admin dashboard features need separate mods or future Mystic
  modules.
- If HyEssentialsX imported from another mod previously, migrate from the final
  HyEssentialsX data rather than importing the older source mod too.

## Permission Mapping

Use this table as a starting point when moving ranks to LuckPerms.

| Old concept | Mystic permission |
| --- | --- |
| Use spawn | `mysticessentials.spawn.use` |
| Set spawn | `mysticessentials.spawn.set` |
| Use homes | `mysticessentials.home.use` |
| Set, delete, rename homes | `mysticessentials.home.set` |
| Home limit | `mysticessentials.home.limit.<n>` or `.unlimited` |
| Use TPA | `mysticessentials.teleport.tpa` |
| Staff teleport here | `mysticessentials.teleport.tphere` |
| Teleport all players | `mysticessentials.teleport.tpall` |
| Back | `mysticessentials.teleport.back` |
| Top | `mysticessentials.teleport.top` |
| Use warps | `mysticessentials.warp.use` |
| Manage server warps | `mysticessentials.warp.set` |
| Use player warps | `mysticessentials.playerwarp.use` |
| Create player warps | `mysticessentials.playerwarp.create` |
| Manage all player warps | `mysticessentials.playerwarp.admin` |
| Player warp limit | `mysticessentials.playerwarp.limit.<n>` or `.unlimited` |
| Use kits | `mysticessentials.kit.use` |
| Per-kit permission | `mysticessentials.kit.<name>` |
| Kit admin | `mysticessentials.kit.admin` |
| Private message | `mysticessentials.chat.private.message` |
| Reply | `mysticessentials.chat.private.reply` |
| Social spy | `mysticessentials.chat.socialspy` |
| Mail | `mysticessentials.mail.use` |
| Send mail | `mysticessentials.mail.send` |
| Send offline mail | `mysticessentials.mail.send.offline` |
| Send all mail | `mysticessentials.mail.send.all` |
| Broadcast | `mysticessentials.announcement.broadcast` |
| Alert | `mysticessentials.announcement.alert` |
| Fly | `mysticessentials.fly.use` |
| Fly others | `mysticessentials.fly.others` |
| Nickname | `mysticessentials.nick.use` |
| Nickname colors | `mysticessentials.nick.color` |
| Nickname others | `mysticessentials.nick.others` |

## Post-migration Validation

Run through this checklist before launching the migrated server.

| Check | How to test |
| --- | --- |
| Spawn works | Join as a normal player and run `/spawn` |
| First-join behavior | Join with a fresh test account |
| Homes imported | Run `/homes`, then `/home <name>` |
| Home limits work | Try to create one more home than the rank allows |
| Server warps imported | Run `/warps`, then `/warp <name>` |
| Player warps imported | Run `/pwarp`, then `/pwarp <name>` |
| Kits imported | Run `/kit`, then `/kit <name>` |
| Kit cooldowns/costs work | Claim the same kit twice with a normal player |
| Permissions are correct | Test default, VIP, staff, and owner groups |
| Old commands are gone | Confirm the old essentials jar is not loaded |
| Logs are clean | Check server logs after startup and after each test |

## Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Scan finds zero data | Wrong path or old jar path passed instead of data folder | Point the command at the old data folder |
| Homes do not import | Owner UUID is missing | Check whether the old home files include UUIDs or are named by UUID |
| Player warps import as server warps | Owner UUID is missing | Add owner UUID data or migrate those warps manually |
| Imported locations use `default` as world | Legacy file did not include a world field | Edit affected Mystic JSON entries manually |
| Kits import with missing items | Legacy item ids do not match Hytale item ids | Edit `modules/kits/config.json` after import |
| Existing Mystic entries are skipped | Merge mode is protecting current data | Re-run with `--replace` only if overwriting is intended |
| Config file changed unexpectedly | Import was run on production or with `--replace` | Restore the timestamped backup or full server backup |

## Developer Notes

The migration command lives in:

```text
src/main/java/org/hyzionstudios/mysticessentials/core/migration/MigrationCommand.java
src/main/java/org/hyzionstudios/mysticessentials/core/migration/LegacyMigrationService.java
```

The importer does not load classes from legacy mods. It reads data files,
extracts known JSON/TOML shapes, converts them into Mystic's model classes, and
writes through Mystic's normal config and storage paths.

Source-specific importers can be added later by expanding
`LegacyMigrationService` with stricter parsers for known file layouts. Prefer
source-specific parsing when a mod's schema is known, and keep heuristic parsing
as the fallback for unknown or hand-edited data.