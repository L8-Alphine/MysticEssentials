# Notifications

Mystic Essentials has one notification engine for broadcasts, alerts, mentions,
mail, teleports, addon notices, and other player-facing events. This prevents
each feature from inventing incompatible titles and sounds, while giving players
one place to manage routine notices.

## Notification Center

`/notifications` (alias `/notifs`) opens a player's history and preferences.
Players can review unread notices, filter them, open a command, URL, item,
channel, or page action, mark records read, dismiss them, and tune delivery by
category.

History defaults to 50 stored records per player with a 24-hour expiration.
Critical records can survive reconnects and may remain visible after they are
read.

## Priorities and surfaces

| Priority | Default behavior |
| --- | --- |
| `low` | Chat only; no history |
| `normal` | Use the category's configured default profile |
| `important` | Chat, title/subtitle, toast, sound, and history |
| `critical` | Important surfaces plus a pinned, non-dismissible banner |

Server owners can edit every profile in
`modules/core/notifications.json`. Categories separately define the display
name, accent, sound, chat prefix, default profile, minimum priority, and whether
players may disable them.

Critical and emergency categories default to a critical minimum and cannot be
disabled by players. Sending a critical notice requires
`mysticessentials.notifications.critical`.

## Audiences

Built-in targets include all players, one or more players, a permission, world,
channel, nearby radius, staff, or an API predicate. Guild, party, region, and
other relationship owners can register named audience resolvers through the
public API.

## Configuration

File:

```text
modules/core/notifications.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Master notification-engine switch |
| `history.enabled` | `true` | Store notifications whose selected profile enables history |
| `history.maximumPerPlayer` | `50` | Stored records retained per player |
| `history.persistCritical` | `true` | Keep critical records across reconnects even after read |
| `history.defaultExpirationHours` | `24` | Routine record lifetime |
| `critical.allowPlayerDisable` | `false` | Allow players to suppress critical alerts |

Each profile selects `chat`, `title`, `subtitle`, `actionbar`, `toast`, `banner`, `sound`, and `history`, plus title fade/stay timing and banner `durationSeconds`/`dismissible`. Each category supplies `displayName`, `icon`, `accent`, `sound`, `defaultProfile`, `minimumPriority`, `chatPrefix`, and `playerDisableable`.

See [Announcements](announcements-module) for `/broadcast` and `/alert` syntax,
or [Developer API](developer-api) to send notifications from another mod.
