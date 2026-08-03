# Player Guide

This page explains the features players use most often. Some commands may be unavailable if the server owner disabled a module or did not grant the required permission.

## Teleport requests

Use teleport requests when you want to visit another player or invite them to you.

| Command | What it does |
| --- | --- |
| `/tpa <player>` | Ask to teleport to a player |
| `/tpahere <player>` | Ask a player to teleport to you |
| `/tpaccept [player]` | Accept the newest request, or a request from a specific player |
| `/tpdeny [player]` | Deny the newest request, or a request from a specific player |
| `/tpcancel` | Cancel your outgoing requests |
| `/tpa` | Open the Teleport Requests UI |

Requests expire after the server's configured timeout. Accepted teleports may have a warmup. If you move or take damage during warmup, the teleport can be cancelled.

The Teleport Requests UI includes favorites. Use favorite buttons to keep common friends in the list and quickly send TPA or TPA Here requests.

## Spawn, homes, and back

| Command | What it does |
| --- | --- |
| `/spawn` | Teleport to server spawn |
| `/sethome [name]` | Save your current position as a home |
| `/home [name]` | Teleport to a home, or open the Homes UI with no name |
| `/homes` | Open the Homes UI |
| `/delhome [name]` | Delete a home |
| `/renamehome <old> <new>` | Rename a home |
| `/back` | Return to your previous location |

Home names are case-insensitive internally. If you do not provide a name, the default home name is usually used by the command implementation.

Your home limit is controlled by permissions. For example, a VIP group may have more homes than the default group.

## Random teleport

If the server enables it, `/rtp` drops you at a random safe spot out in the world — handy for finding unclaimed land.

| Command | What it does |
| --- | --- |
| `/rtp` | Teleport to a random safe location |
| `/rtp profile <name>` | Use a specific destination profile (e.g. a resource world) |
| `/rtp status` | See your current search and per-profile cooldowns |
| `/rtp cancel` | Cancel a pending RTP |

There may be a short warmup before you are moved — standing still and out of combat keeps it from cancelling. Some profiles have a cooldown or a cost. See [Random Teleport](rtp-module) for more.

## Warps and player warps

Server warps are destinations created by staff. Player warps are public destinations created by players.

| Command | What it does |
| --- | --- |
| `/warp` or `/warps` | Open the server warps UI |
| `/warp <name>` | Teleport to a server warp |
| `/pwarp` | Browse player warps |
| `/pwarp <name>` | Teleport to a player warp |
| `/pwarp create <name>` | Create a player warp at your current position |
| `/pwarp manage` | Open your player warp manager |
| `/pwarp delete <name>` | Delete one of your player warps |

Some warps may cost money if the server uses VaultUnlocked. If there is no economy provider installed, costs are treated as free.

## Mail

Mail lets players send messages even when someone is offline.

| Command | What it does |
| --- | --- |
| `/mail` | Open the Mail UI |
| `/mail inbox` | List your inbox in chat |
| `/mail read <id>` | Read a mail item |
| `/mail send <player> <message>` | Send mail |
| `/mail delete <id>` | Delete a mail item |
| `/mail clear` | Clear your inbox |

If your inbox is full, the server drops the oldest read mail first. If none are read, it drops the oldest mail overall.

## Private messages and chat channels

| Command | What it does |
| --- | --- |
| `/msg <player> <message>` | Send a private message |
| `/tell`, `/w`, `/whisper` | Aliases for `/msg` |
| `/reply <message>` or `/r <message>` | Reply to your last private message |
| `/channel` or `/ch` | Open the channel browser |
| `/channel join <name> [password]` | Listen to a channel |
| `/channel leave <name>` | Stop listening to a channel |
| `/channel switch <name> [password]` | Speak in a channel |

Servers may configure channel aliases such as `/g`, `/global`, `/sc`, `/schat`, or `/staffchat`. Running a channel alias switches your speaking channel.

Channel pages can show the owner, moderators, staff, members, listeners, server
ranks, and recent text/voice activity. Temporary-channel owners and permitted
moderators can lock a channel, mute/remove members, change participation,
promote moderators, and transfer ownership from `/channel manage`.

Type an exact player name such as `@Aether` to mention that player. Open
`/mentions` to choose who may mention you, switch chat/title/action-bar/sound
delivery, block a sender, or enable do-not-disturb. Mentions inside links, email
addresses, and item data do not ping you.

## Sharing items in chat

Type `[item]` in a chat message to show off the item you are holding. It becomes a rarity-colored name with a short `(/itemview <code>)` link:

```text
Selling my [Frostbite Blade ×1] (/itemview a3f9)!
```

Anyone can open the item by clicking the name, clicking or typing `/itemview <code>`, or using `/iteminspect` for the most recent one. **Hover the item icon** in the viewer to see its full tooltip — description, rarity, and stats. `/itemlinks` lists items recently shared to you. Shared items are read-only snapshots, so they cannot be taken or copied.

## Notifications

`/notifications` (alias `/notifs`) opens your Notification Center. Review unread
and previous notices, filter categories, follow command/page/item/URL actions,
mark notices read, and adjust delivery preferences. Routine categories can be
muted; server-critical alerts may bypass preferences so maintenance or emergency
warnings are not missed.

## AFK

Use `/afk [reason]` to toggle AFK manually. The server may also mark you AFK automatically after you stop moving, chatting, or clicking for long enough.

Some servers enable AFK rewards. Rewards are optional, permission-gated, and may require standing inside a configured reward zone.

## Kits

| Command | What it does |
| --- | --- |
| `/kit` or `/kits` | Open or list available kits |
| `/kit list` | List kits you can claim |
| `/kit <name>` | Claim a kit |

Kits can have cooldowns, one-time claim rules, playtime requirements, permission requirements, and economy costs.

## Flight

Use `/fly` to toggle flight if you have permission. If the server enables paid flight, you may be charged every minute while flying. Players with free or unlimited flight permissions are exempt.

## Nicknames

| Command | What it does |
| --- | --- |
| `/nick` | Open the nickname UI |
| `/nickname` | Alias for `/nick` |
| `/nick <name>` | Set your nickname |
| `/nick reset` | Remove your nickname |

Nickname length, blocked names, and color permissions are controlled by the server.
