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
| `normal` | Chat, action bar, toast, sound, and history according to category |
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

See [Announcements](announcements-module) for `/broadcast` and `/alert` syntax,
or [Developer API](developer-api) to send notifications from another mod.
