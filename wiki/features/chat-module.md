# Chat

The Chat module owns everything that happens to a chat message: how public chat is formatted, private messages, chat channels, custom glyphs and emoji, and LuckPerms rank icons. Each part is a sub-feature you can enable or disable independently in `modules/chat/config.json`.

Three of the larger sub-features have their own pages:

- [Chat Formatting](chat-formatting) — format templates, colors, gradients, links, and placeholders.
- [Item Links](itemlinks-module) — share your held item in chat with the `[item]` tag.

Players can also configure exact-name mentions with `/mentions` (alias
`/mentionsettings`). Mentions use the shared notification engine, so each player
can choose chat highlighting, title, action-bar and sound behavior, select who
may mention them, block senders, or enable do-not-disturb.

## Public chat formatting

When `formatChat` is on, every message is rewritten through a permission-selected format template. See [Chat Formatting](chat-formatting) for the full syntax, color permissions, and placeholder list.

## Private messages

| Command | What it does | Permission |
| --- | --- | --- |
| `/msg <player> <message>` | Send a private message | `mysticessentials.chat.private.message` |
| `/tell`, `/w`, `/whisper` | Aliases for `/msg` | `mysticessentials.chat.private.message` |
| `/reply <message>`, `/r <message>` | Reply to your last private message | `mysticessentials.chat.private.reply` |

Private messaging can deliver across servers over Redis (`allowCrossServer`) and fall back to [mail](mail-module) for offline players (`offlineToMail`). **Social spy** (`mysticessentials.chat.socialspy`) lets staff monitor private messages; players with `mysticessentials.chat.socialspy.exempt` are hidden from it.

## Channels

Channels split chat into separate streams such as global and staff.

| Command | What it does |
| --- | --- |
| `/channel`, `/ch` | Open the channel browser or show channel state |
| `/channel switch <name> [password]` | Switch your speaking channel |
| `/channel join <name> [password]` | Listen to a channel |
| `/channel leave <name>` | Stop listening to a channel |
| `/channel temp <id> ...` | Create a temporary channel (`mysticessentials.chat.channel.create.temp`) |
| `/channel manage` | Manage your temporary channel |
| `/mentions` | Open mention settings |

Servers can define quick aliases such as `/g`, `/global`, `/sc`, `/schat`, and `/staffchat`. Channels are gated by dynamic permissions: `mysticessentials.chat.channel.<id>` and its `.speak`, `.listen`, and `.moderator` variants. The bundled defaults are a server-wide `global` channel and a permission-gated cross-server `staff` channel.

The channel browser and management pages include compact and full member
rosters. Rows resolve live owner, channel-moderator, staff, member and listener
roles; show optional LuckPerms rank information; and expose recent text or
addon-provided voice activity. Temporary-channel owners can lock membership,
mute/remove members, change participation, promote moderators, and transfer
ownership. On owner disconnect, a configurable grace period and succession
policy can promote a moderator or the oldest member, retain ownership, or close
the channel. Ownership/moderator changes publish API events.

### Channel configuration

Channels are configured under `channels` in `modules/chat/config.json`. Top-level settings:

| Setting | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Enable channel commands and routing |
| `defaultSpeak` | `"global"` | Channel new players speak in |
| `defaultJoin` | `["global"]` | Channels new players listen to |
| `allowTemporaryChannels` | `true` | Allow player-created session channels |
| `temporaryChannelDefaultMinutes` | `120` | Redis restore window for temporary channels |
| `createTemporaryPermission` | `mysticessentials.chat.channel.create.temp` | Permission to create temporary channels |
| `channels` | global + staff | The channel definitions (see below) |
| `roster` | enabled | Member-list permission, tags, ranks, activity and visible-row cap |
| `tempManagement.ownershipTransfer` | enabled, acceptance required | Transfer request expiry and the previous owner's new role |
| `tempManagement.ownerDisconnect` | 300-second grace | Succession and fallback modes |
| `tempManagement.moderation` | safe defaults | Per-action moderator capabilities |

Each entry in `channels` supports:

| Field | Meaning |
| --- | --- |
| `id` | Channel id (used in `/channel <id>` and permission nodes) |
| `displayName` | Name shown in the browser and `{channel}` placeholder |
| `enabled` | Whether the channel is active |
| `scope` | `server` (everyone) or `permission` (gated by `joinPermission`) |
| `format` | The message format for this channel |
| `groupFormats` | Per-LuckPerms-group format overrides: `group → format` |
| `prefix` | Short prefix used in the browser |
| `aliases` | Quick-switch command aliases (e.g. `["g","global"]`) |
| `password` | Optional join password |
| `joinPermission` / `speakPermission` / `listenPermission` / `moderatorPermission` | Fine-grained gates |
| `crossServer` | Route this channel across servers over Redis |
| `redisTopic` | Redis topic name for cross-server routing |
| `radiusBlocks` | When > 0, only players within this many blocks receive messages (local/proximity chat) |
| `locked` | Prevent new members from joining until an owner/moderator unlocks it |

Channel formats use the same placeholders as public chat (see [Chat Formatting](chat-formatting)). The `groupFormats` map lets one channel render differently per rank:

```json
"channels": {
  "enabled": true,
  "defaultSpeak": "global",
  "defaultJoin": ["global"],
  "channels": [
    {
      "id": "global",
      "displayName": "Global",
      "scope": "server",
      "format": "&8[&aG&8] {luckperms_prefix}{display_name} &8» &f{message}",
      "prefix": "&8[&aG&8]",
      "aliases": ["g", "global"],
      "groupFormats": {
        "admin": "&8[&aG&8] &4[Admin] {luckperms_prefix}{display_name} &8» &f{message}"
      },
      "moderatorPermission": "mysticessentials.chat.channel.global.moderator"
    },
    {
      "id": "staff",
      "displayName": "Staff",
      "scope": "permission",
      "format": "&8[&bStaff&8] &f{display_name}: &b{message}",
      "prefix": "&8[&bStaff&8]",
      "aliases": ["sc", "schat", "staffchat"],
      "joinPermission": "mysticessentials.chat.channel.staff",
      "speakPermission": "mysticessentials.chat.channel.staff.speak",
      "listenPermission": "mysticessentials.chat.channel.staff.listen",
      "moderatorPermission": "mysticessentials.chat.channel.staff.moderator",
      "crossServer": true,
      "redisTopic": "staff"
    }
  ]
}
```

To add a **local/proximity** channel, set `scope: "server"` and a `radiusBlocks` such as `100`. Cross-server channels require Redis (see [Storage](storage)).

## Item links

Players can show off the item in their hand by typing `[item]` in chat. The tag becomes a rarity-colored item name with a short `(/itemview <code>)` hint; hovering the icon in the viewer shows the full native item tooltip. See [Item Links](itemlinks-module) for the workflow, commands, rarity rules, and configuration (`modules/chat/item-links.json`).

## Mentions

`@PlayerName` matching is case-insensitive and exact by default. Trailing
punctuation works, while URLs, email addresses, item data, and partial names do
not trigger a notification. Sender cooldowns, same-target cooldowns, a
per-message cap, a per-minute budget, recipient sound throttling, and a separate
five-minute mass-mention cooldown make abuse expensive.

Mass mentions (`@everyone`, `@online`, `@staff`, `@channel`) have separate
permissions. Addons can register relationship scopes such as friends, guild or
party through `ChatService.registerMentionScope(...)`; unavailable scopes stay
hidden from player settings instead of silently accepting a rule that cannot be
enforced. Server defaults live in `modules/chat/mentions.json`.

## Configuration

All chat settings live in one file:

```text
modules/chat/config.json
```

Item links use `modules/chat/item-links.json` and `modules/chat/item-view.json`;
mentions use `modules/chat/mentions.json`. See the [Configuration Reference](configuration)
for the key fields, and [Chat Formatting](chat-formatting) for template details.

## See also

- [Chat Formatting](chat-formatting)
- [Item Links](itemlinks-module)
- [Mail](mail-module)
- [Permissions Reference](permissions)
