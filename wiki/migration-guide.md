---
title: Migration Guide
description: Move from EssentialsPlus, Hyssentials, EliteEssentials, Essentials, or HyEssentialsX to Mystic Essentials.
---

# Migration Guide

This guide covers migration planning for these Hytale essentials mods:

| Source mod | Jar inspected | Manifest identity | Version in jar |
| --- | --- | --- | --- |
| EssentialsPlus | `EssentialsPlus-1.19.3.jar` | `fof1092:EssentialsPlus` | `1.19.3` |
| Hyssentials | `Hyssentials-3.2.jar` | `dev.hytalemodding:Hyssentials` | `3.1` in manifest |
| EliteEssentials | `EliteEssentials-2.0.9.jar` | `com.eliteessentials:EliteEssentials` | `2.0.9` |
| Essentials | `Essentials-1.8.0 (1).jar` | `com.nhulston:Essentials` | `1.8.0` |
| HyEssentialsX | `hyessentialsx-1.5.6.jar` | `xyz.thelegacyvoyage:HyEssentialsX` | `1.5.6` |

## In-game migration command

Mystic Essentials includes a file-based migration command. The old mod jar does not need to be loaded, and the old mod does not need to be installed. The legacy data files only need to be present on disk.

Grant staff the migration permission before running it:

```text
mysticessentials.migrate
```

Run a scan first:

```text
/mystic migrate scan <source> [path]
```

Then import:

```text
/mystic migrate import <source> [path] [--replace] [--dry-run]
```

Supported source names are `auto`, `essentialsplus`, `hyssentials`, `eliteessentials`, `essentials`, and `hyessentialsx`.

If `path` is omitted, Mystic checks sibling folders under the server `mods/` directory, such as `mods/EssentialsPlus`, `mods/Hyssentials`, `mods/EliteEssentials`, `mods/Essentials`, and `mods/HyEssentialsX`. If your old data lives somewhere else, pass the folder path explicitly. Paths with spaces can be wrapped in quotes.

By default, imports merge and skip existing Mystic data with the same name. Add `--replace` only when you intentionally want legacy data to overwrite existing Mystic homes, warps, spawns, or kits. The importer writes timestamped backups of Mystic module files before changing them.

Current command scope:

| Data type | Command support |
| --- | --- |
| Homes | Imported into player profiles when owner UUIDs are present |
| Global spawn | Imported into `modules/spawn/config.json` |
| Per-world spawns | Imported into `modules/spawn/config.json` |
| Server warps | Imported into `data/modules/warps/server.json` |
| Player warps | Imported into `data/modules/warps/playerwarps.json` when owner UUIDs are present |
| Kits | Imported into `modules/kits/config.json` from common JSON layouts and simple TOML kit files |
| Mail, nicknames, chat, MOTD | Use the manual mapping sections below until source-specific importers are added |

## What can migrate cleanly

These data types map well into Mystic Essentials:

| Source data | Mystic destination | Notes |
| --- | --- | --- |
| Global spawn | `modules/spawn/config.json -> globalSpawn` | Convert world, position, yaw, pitch |
| Per-world spawn | `modules/spawn/config.json -> worldSpawns` | Preserve by world name/id when available |
| Homes | Player profile module data used by spawn/homes | Preserve owner UUID, name, world, position, rotation |
| Server warps | `data/modules/warps/server.json` | Preserve name, location, description, permission, cost when source has them |
| Player warps | `data/modules/warps/playerwarps.json` | Preserve owner, name, location, description, price when source has them |
| Kits | `modules/kits/config.json -> kits` | Preserve item ids and quantities; review slot/metadata support manually |
| MOTD and join/leave text | `modules/greetings/config.json` | Convert placeholders and color markup |
| Chat format | `modules/chat/config.json` | Convert format placeholders and group/rank rules |
| Mail | Mystic `mail` storage namespace | Possible when source stores UUID, sender, body, read state, timestamp |
| Nicknames | Player profile/module data for nick | Possible when source stores UUID and nickname |

## What needs manual replacement

These features exist in one or more source mods but are not direct Mystic Essentials features:

| Source feature | Migration action |
| --- | --- |
| Economy balances, `/pay`, `/money`, baltop, shops, auctions | Move to a dedicated economy/shop mod. Mystic only consumes VaultUnlocked for costs and payouts. |
| Moderation bans, mutes, warnings, freezes, command spy, invsee, god, heal, repair | Move to MysticModeration or another moderation/admin mod. |
| Vanish | Move to MysticVanish. Mystic Essentials integrates with it but does not replace it. |
| RTP | Keep a separate RTP mod or add an RTP module to Mystic later. Mystic currently has TPA, back, top, spawn, homes, and warps. |
| World border, sleep percentage, spawn protection, build protection | Keep dedicated server/world-management mods or implement Mystic modules later. |
| Custom command systems | Rebuild as server commands or addon modules. |
| Rank/group systems | Prefer LuckPerms. Convert groups and permissions manually. |
| Holograms, scoreboards, tab list, leaderboards, stats, rankup | Move to dedicated feature mods or custom addons. |

## Safe migration process

1. Back up the entire old server folder, including `mods/`, mod data folders, databases, and config files.
2. Start Mystic Essentials once on a copy of the server to generate default config and data folders.
3. Stop the server.
4. Map old permissions to Mystic permissions in LuckPerms.
5. Run `/mystic migrate scan <source> [path]` and confirm the counts look right.
6. Run `/mystic migrate import <source> [path]` on the test copy.
7. Start with a small test group.
8. Verify spawn, homes, warps, kits, chat, mail, and nicknames.
9. Remove the old essentials mod only after validation passes.

Do not run two essentials mods that register the same commands on the live server. Command collisions are likely.

## Permission mapping

Use this as a starting point. Source nodes vary by mod and version, so verify against the source server's actual permission files.

| Old concept | Mystic permission |
| --- | --- |
| Use spawn | `mysticessentials.spawn.use` |
| Set spawn | `mysticessentials.spawn.set` |
| Use homes | `mysticessentials.home.use` |
| Set/delete homes | `mysticessentials.home.set` |
| Home limit | `mysticessentials.home.limit.<n>` or `.unlimited` |
| Use TPA | `mysticessentials.teleport.tpa` |
| Staff teleport here | `mysticessentials.teleport.tphere` |
| Teleport all | `mysticessentials.teleport.tpall` |
| Top | `mysticessentials.teleport.top` |
| Back | `mysticessentials.teleport.back` |
| Use warps | `mysticessentials.warp.use` |
| Manage warps | `mysticessentials.warp.set` |
| Use player warps | `mysticessentials.playerwarp.use` |
| Create player warps | `mysticessentials.playerwarp.create` |
| Player warp limit | `mysticessentials.playerwarp.limit.<n>` or `.unlimited` |
| Private message | `mysticessentials.chat.private.message` |
| Reply | `mysticessentials.chat.private.reply` |
| Social spy | `mysticessentials.chat.socialspy` |
| Send mail | `mysticessentials.mail.send` |
| Send offline mail | `mysticessentials.mail.send.offline` |
| Send all mail | `mysticessentials.mail.send.all` |
| Use kits | `mysticessentials.kit.use` |
| Per-kit access | `mysticessentials.kit.<name>` |
| Kit admin | `mysticessentials.kit.admin` |
| Fly | `mysticessentials.fly.use` |
| Fly others | `mysticessentials.fly.others` |
| Nick | `mysticessentials.nick.use` |
| Nick colors | `mysticessentials.nick.color` |
| Nick others | `mysticessentials.nick.others` |

## Placeholder conversion

Common conversions:

| Old placeholder | Mystic placeholder |
| --- | --- |
| `%player%` | `{player_name}` or `{display_name}` |
| `{player}` | `{player_name}` or `{display_name}` |
| `%message%` | `{message}` |
| `{message}` | `{message}` |
| LuckPerms prefix placeholders | `{luckperms_prefix}` |
| LuckPerms suffix placeholders | `{luckperms_suffix}` |
| Group/rank placeholder | `{group}` |

Color codes such as `&a`, `&c`, and `&#RRGGBB` are supported by Mystic. MiniMessage-style tags such as `<red>` and gradients are also supported.

## EssentialsPlus migration notes

The EssentialsPlus jar includes homes, warps, spawn, TPA, back, kits, mail, private messages, broadcast, MOTD, nickname, economy, moderation, world border, and several migration classes.

Likely clean imports:

- Homes.
- Server warps.
- Spawn data.
- Kits.
- Mail, if source mailbox files are available.
- Chat/MOTD/messages as manual config conversion.

Review manually:

- Economy balances should move to an economy provider, not Mystic.
- Moderation data should move to MysticModeration or a moderation mod.
- World border, sleep, protection, ignore, rules, Discord, and custom command features need replacements.

Command replacements:

| EssentialsPlus command area | Mystic replacement |
| --- | --- |
| `/home`, `/homes`, `/sethome`, `/delhome` | Same Mystic home commands |
| `/spawn`, `/setspawn` | Same Mystic spawn commands |
| `/warp`, `/warps`, `/setwarp`, `/delwarp` | Same Mystic server warp commands |
| `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny` | Same Mystic TPA commands |
| `/back`, `/top` | Same Mystic commands |
| `/kit` | Mystic kits |
| `/msg`, `/r` | Mystic private messaging |
| `/broadcast` | Mystic `/broadcast`; use `/alert` for urgent broadcasts |
| `/mail` | Mystic mail |
| `/nick` | Mystic nicknames |

## Hyssentials migration notes

The Hyssentials jar exposes commands for spawn, homes, warps, TPA, back, RTP, direct teleport, private messages, admin chat, ranks, fly, and vanish.

Likely clean imports:

- Homes.
- Warps.
- Spawn.
- Basic chat/admin chat settings into Mystic channels.

Manual replacements:

- Ranks should move to LuckPerms.
- Vanish should move to MysticVanish.
- RTP needs a separate RTP mod.
- Direct `/tp` should be handled by a staff teleport/moderation tool; Mystic includes `/tphere` and `/tpall`, but not a full `/tp <player> <player>` replacement.

Hyssentials uses MiniMessage-like text in language files. Mystic supports named tags and common formatting, but verify every custom tag after conversion.

## EliteEssentials migration notes

EliteEssentials stores a broad essentials feature set and includes migration services for other mods. Its jar exposes models for homes, warps, player warps, kits, mail, player files, nicknames, economy, and moderation.

Likely clean imports:

- Homes.
- Global/per-world spawns.
- Server warps.
- Player warps.
- Kits.
- Mail.
- Nicknames.
- Chat format group rules.
- MOTD and join/quit messages.

Manual replacements:

- Economy account balances should move to VaultUnlocked-compatible economy storage.
- Moderation features should move to MysticModeration.
- Player info, seen, join date, warnings, command spy, invsee, god, heal, repair, trash, rules, sleep, world border, Discord, and spawn protection are outside Mystic's current scope.

EliteEssentials supports SQL and local storage. A future importer should support both by reading its storage config, then importing from files or SQL tables.

## Essentials migration notes

The `com.nhulston:Essentials` jar includes embedded `config.toml`, `kits.toml`, `messages.toml`, and JSON metadata. Its features overlap strongly for homes, spawn, warps, TPA, back, top, kits, MOTD, chat formatting, rules, and some staff utilities.

Likely clean imports:

- `config.toml -> [homes].limits` into LuckPerms `mysticessentials.home.limit.<n>` assignments.
- `config.toml -> [teleport].delay` into Mystic teleport warmups.
- `config.toml -> [tpa].expiration` into `requestExpirySeconds`.
- `config.toml -> [spawn].first-join` into `teleportOnFirstJoin`.
- `config.toml -> [spawn].every-join` into `teleportOnJoin`.
- `config.toml -> [chat]` into Mystic chat formats.
- `kits.toml` into Mystic kit definitions.
- Existing player data homes, spawn, and warps into Mystic data.

Manual replacements:

- RTP, spawn protection, build protection, sleep percentage, freecam, god, heal, repair, trash, rules, and shout need other modules or manual deprecation.

## HyEssentialsX migration notes

HyEssentialsX is broad and includes commands for chat, mail, homes, warps, player warps, kits, flight, nicknames, AFK, economy, shops, moderation, holograms, scoreboards, rankup, stats, and import/migration classes.

Likely clean imports:

- Homes.
- Spawns.
- Server warps.
- Player warps.
- Kits.
- Mail.
- Nicknames.
- AFK settings.
- Flight settings.
- Announcement presets that map to Mystic auto-broadcasts.
- Basic chat/private-message settings.

Manual replacements:

- Economy balances, shops, auction house, holograms, scoreboards, rankup, stats, command spy, combat log, moderation, world border, sleep, day/night, and admin dashboard features need separate mods or future Mystic modules.

HyEssentialsX already contains migration classes for several other mods. If migrating from HyEssentialsX to Mystic, prefer reading HyEssentialsX's final normalized data rather than trying to import the older source mod too.

## Future importer scope

The first built-in importer keeps the blast radius tight by importing homes, spawns, warps, player warps, and kits from copied data files. Future source-specific passes can add:

| Phase | Import |
| --- | --- |
| 1 | Dry-run scanner, source detection, summary report |
| 2 | Homes, server warps, global/per-world spawns |
| 3 | Player warps, kits, mail, nicknames |
| 4 | Chat formats, MOTD, announcements, join/leave messages |
| 5 | SQL source support for EliteEssentials and HyEssentialsX |

The importer should never delete source data. It should write a timestamped backup of every Mystic file it changes and support `--dry-run` before importing.

## Post-migration validation

Run through this checklist before launch:

| Check | How |
| --- | --- |
| Spawn works | `/spawn` as a normal player |
| First join behavior | Join with a test account |
| Homes imported | `/homes`, `/home <name>` |
| Home limits correct | Try setting one more than the rank limit |
| Warps imported | `/warps`, `/warp <name>` |
| Player warps imported | `/pwarp`, `/pwarp <name>` |
| TPA works | `/tpa`, `/tpaccept`, `/tpdeny` |
| Back works | Teleport, then `/back` |
| Kits imported | `/kit`, `/kit <name>` |
| Mail imported | `/mail`, `/mail read <id>` |
| Chat format correct | Speak as each rank |
| Permissions correct | Test default, VIP, staff, and owner groups |
| Old commands removed | Confirm old essentials jar is not loaded |
