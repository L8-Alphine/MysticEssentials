---
title: Troubleshooting
description: Common Mystic Essentials setup and runtime issues.
---

# Troubleshooting

## `/mystic reload` says I do not have permission

Grant:

```text
mysticessentials.reload
```

If you use LuckPerms:

```text
lp user <name> permission set mysticessentials.reload true
```

## A module command does not exist

Check:

1. The module is enabled in `mods/MysticEssentials/config.json`.
2. The server log shows the module enabled successfully.
3. Any hard dependency is also enabled.
4. You restarted after changing module toggles.

## Config changes did not apply

Use `/mystic reload` for most config changes. Restart for:

- Storage provider changes.
- SQL connection changes.
- Redis connection changes.
- New or removed integration jars.
- Mod jar updates.

If a file has invalid JSON, Mystic logs the error and uses defaults without overwriting the broken file.

## SQL storage falls back to JSON

Mystic falls back to JSON if SQL is unreachable on startup.

Check:

- `storage.provider` is `mysql` or `mariadb`.
- Host, port, database, username, and password are correct.
- The database server accepts connections from the game server.
- The SQL user can create and update the `mystic_documents` table.

## Cross-server private messages or channels do not work

Check Redis:

- `storage.redis.enabled` is `true`.
- All servers share the same `networkId`.
- Each server has a unique `serverId`.
- Redis host, port, and password are correct.
- Firewall rules allow connections.

Without Redis, cross-server features become local-only.

## Players cannot use colors in chat

Grant the style permission they need:

```text
mysticessentials.chat.color.legacy
mysticessentials.chat.color.hex
mysticessentials.chat.color.gradient
mysticessentials.chat.color.rainbow
mysticessentials.chat.color.minimessage
mysticessentials.chat.color.links
```

Mystic strips styles players are not allowed to use.

## Chat placeholders are empty

Check:

- LuckPerms is installed and enabled for prefix, suffix, and group placeholders.
- PlaceholderAPI is installed and enabled for `%...%` placeholders.
- The placeholder name is correct.
- Player context is available for player-specific placeholders.

Internal `{player_name}` style placeholders work without PlaceholderAPI.

## `/mail send <offlinePlayer>` fails

Offline mail by name only works for known players. The target must have joined before so Mystic can store their username-to-UUID mapping.

On networks, use shared SQL storage if multiple servers should know the same offline players.

## A player cannot create more homes or player warps

Grant a higher dynamic limit:

```text
mysticessentials.home.limit.10
mysticessentials.playerwarp.limit.5
```

Or grant:

```text
mysticessentials.home.limit.unlimited
mysticessentials.playerwarp.limit.unlimited
```

The highest numeric limit wins.

## `/setspawn`, `/setworldspawn`, `/setwarp`, or `/pwarp create` fails

These commands refuse temporary worlds. Move to a persistent world and run the command again.

## Paid features do not charge money

Check VaultUnlocked and an economy provider are installed and active. If no economy is available, Mystic treats costs as successful no-ops so players are not blocked.

## AFK rewards do not pay

Check:

- `rewards.enabled` is `true`.
- Player has `mysticessentials.afk.rewards`.
- VaultUnlocked and an economy provider are active.
- The player is AFK long enough for `rewards.intervalSeconds`.
- Session and daily caps are not reached.
- If `requireInZone` is true, zone corners are configured and the player is inside the zone.
- The player has not taken damage within `noRewardWithinCombatSeconds`.

## `/clearinventory all` skipped a player

Players with `mysticessentials.inventory.protect` are intentionally skipped.

## Nickname color does not work

Grant:

```text
mysticessentials.nick.color
```

Also make sure the visible nickname length is within `minLength` and `maxLength` after color codes are stripped.

## Custom glyphs do not render

Check:

- `glyphs.enabled` is true.
- `glyphs.registerCommonAssets` is true.
- The server includes Mystic's asset pack.
- The client has the needed font/asset binding for private-use glyph codepoints.
- The player has the required glyph permission tier.

If glyph assets are missing from logs, verify the jar contains `Common/Resources/MysticEssentials/Chat/Glyphs/`.
