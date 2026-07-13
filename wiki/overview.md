# Mystic Essentials

Mystic Essentials is a modular essentials foundation for Hytale servers. It provides day-to-day server features such as homes, spawn, server warps, player warps, teleport requests, back teleport, mail, private messages, chat formatting, chat channels, announcements, AFK tracking, greetings and MOTD messages, kits, paid flight, inventory snapshots, nicknames, and an API for addon developers.

The mod is built and tested against Hytale Server `0.5.6` and declares support for server versions `>=0.5.0 <0.6.0`.

## Who this wiki is for

Players should start with the [Player Guide](guides/player-guide). It explains the commands and UI flows they will use most often.

Server owners and staff should start with [Getting Started](getting-started), then read [Admin Guide](guides/admin-guide), [Configuration Reference](system/configuration.md), [Permissions Reference](system/permissions.md), and [Integrations and Storage](integrations-and-storage).

Developers should start with [Developer API](developer-api.md). Mystic Essentials exposes a service-based API through `MysticEssentialsProvider.get()` and keeps Hytale-specific implementation details behind the platform layer.

## Major features

| Area | What it adds |
| --- | --- |
| Teleportation | `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny`, `/tpcancel`, `/tphere`, `/tpall`, `/top`, `/back`, warmups, cooldowns, movement/damage cancellation, TPA favorites UI |
| Spawn and homes | Global spawn, per-world spawn, first-join and join teleport options, `/home`, `/homes`, `/sethome`, `/delhome`, `/renamehome`, permission-based home limits |
| Warps | Server warps, in-game warp browser, admin warp editor, visibility rules, paid warps, player warps, player warp manager UI |
| Mail | Online and offline mail, inbox UI, read/delete/clear, server-wide mail, unread join notices |
| Chat | Rank formats, PlaceholderAPI and LuckPerms placeholders, private messages, reply, social spy, channel browser, staff channels, temporary channels, Unicode and custom glyph support |
| Announcements | Manual `/broadcast` and `/alert`, auto-broadcast rotation, multi-line and clickable announcements |
| AFK | Manual AFK, automatic AFK, join/leave style announcements, optional AFK rewards with economy payouts and anti-abuse caps |
| Greetings | MOTD, first-join message, join and leave broadcasts |
| Kits | First-join kits, cooldowns, one-time kits, playtime gates, permission gates, economy costs, preview UI |
| Flight | `/fly`, staff flight for others, optional paid flight, speed multipliers |
| Inventory | Clear self/others/all, protected players, automatic snapshots, snapshot restore UI |
| Nicknames | Nickname UI, color-gated nicks, blocked names, staff-visible nickname marker |

## Files generated on first run

After the server starts once with Mystic Essentials installed, the mod creates:

```text
mods/MysticEssentials/
  config.json
  messages/en_us.json
  modules/<module>/config.json
  data/
  logs/
```

Use `/mystic reload` after editing configuration or restart the server.

## Quick links

- [Install the mod](getting-started)
- [All player commands](system/commands.md)
- [All permission nodes](system/permissions.md)
- [Migrate from other essentials mods](migration-guide)
- [Configure storage, Redis, and integrations](integrations-and-storage)
- [Customize chat colors, gradients, placeholders, and links](chat-formatting)
- [Build addons with the public API](developer-api)