# Mystic Essentials

Mystic Essentials is a modular essentials foundation for Hytale servers. It provides day-to-day server features such as homes, spawn, server warps, player warps, teleport requests, back teleport, mail, private messages, chat formatting, managed chat channels, item sharing, mentions, notifications, announcements, AFK and playtime tracking, greetings and MOTD messages, kits, paid flight, inventory snapshots, nicknames, and an API for addon developers.

The mod is built and tested against Hytale Server `0.5.6` and declares support for server versions `>=0.5.0 <0.6.0`.

## Who this wiki is for

Players should start with the [Player Guide](player-guide). It explains the commands and UI flows they will use most often.

Server owners and staff should start with [Getting Started](getting-started), then read [Admin Guide](admin-guide), [Configuration Reference](configuration), [Permissions Reference](permissions), [Integrations](integrations), and [Storage](storage).

Developers should start with [Developer API](developer-api). Mystic Essentials exposes a service-based API through `MysticEssentialsProvider.get()` and keeps Hytale-specific implementation details behind the platform layer.

## Major features

| Area | What it adds |
| --- | --- |
| Teleportation | `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny`, `/tpcancel`, cross-world `/tp world`, `/tphere`, `/tpall`, `/top`, `/back`, warmups, cooldowns, movement/damage cancellation, TPA favorites UI |
| Random Teleport | `/rtp` and `/rtpadmin`, named profiles, shape-based safe search with caching, warmups, cooldowns, costs, per-world/profile permissions, admin editor UI |
| Spawn and homes | Global spawn, per-world spawn, first-join and join teleport options, `/home`, `/homes`, `/sethome`, `/delhome`, `/renamehome`, permission-based home limits |
| Warps | Server warps, in-game warp browser, admin warp editor, visibility rules, paid warps, player warps, player warp manager UI |
| Portals | Block-anchored portals via the `MysticPortal` interaction: world teleports, cross-server transfers, command sequences, per-portal permissions, map markers, in-game config UI |
| Mail | Online and offline mail, inbox UI, read/delete/clear, server-wide mail, unread join notices |
| Chat | Rank formats, private messages, reply, social spy, channel rosters and moderation, temporary-channel ownership transfer/succession, held-item snapshots, configurable player mentions |
| Notifications | Player Notification Center, history and filters, configurable delivery surfaces, priorities, targeted audiences, and extensible addon resolvers |
| Announcements | `/broadcast` and `/alert` through the notification engine, targeted audiences, titles, sounds, banners, toasts, action bars, history, and automatic rotation |
| AFK and playtime | Manual/automatic AFK, safe X/Z reward zones, active/idle/total/session playtime, economy payouts, and anti-abuse caps |
| Greetings | MOTD, first-join message, join and leave broadcasts |
| Kits | First-join kits, cooldowns, one-time kits, playtime gates, permission gates, economy costs, preview UI |
| Flight | `/fly`, staff flight for others, optional paid flight, speed multipliers |
| Inventory | Clear self/others/all, protected players, automatic snapshots, snapshot restore UI |
| Nicknames | Nickname UI, color-gated nicks, blocked names, staff-visible nickname marker |
| Player Vaults | Per-rank personal storage, customizable metadata, item blacklists, cross-server locking, admin tooling |
| Patch Notes | In-game changelog viewer, categories and filters, join notices and auto-open |
| Tutorial | Scripted first-join tutorial with scenes and pages |
| Custom Commands | Config-defined commands with actions, cooldowns, and permission gates |
| CustomContent (licensed) | Optional CustomGUIs, HUDs, CustomDialogs builder, live placeholders, reusable layouts, and QuestLines compatibility |

## Files generated on first run

After the server starts once with Mystic Essentials installed, the mod creates:

```text
mods/MysticEssentials/
  config.json
  license.mclicense        # only when installing a licensed feature
  server-id.txt            # generated licensing identity
  messages/en_us.json
  modules/<module>/config.json
  data/
  logs/
```

Use `/mystic reload` after editing configuration or restart the server.

## Quick links

- [Install the mod](getting-started)
- [All player commands](commands)
- [All permission nodes](permissions)
- [Migrate from other essentials mods](migration-guide)
- [Configure storage, Redis, and databases](storage)
- [Set up LuckPerms, PlaceholderAPI, and economy](integrations)
- [Customize chat colors, gradients, placeholders, and links](chat-formatting)
- [Share and inspect items in chat](itemlinks-module)
- [Understand notifications and player preferences](notifications)
- [Configure the licensed CustomContent module](custom-content-module)
- [Random Teleport profiles and safe search](rtp-module)
- [Build addons with the public API](developer-api)
