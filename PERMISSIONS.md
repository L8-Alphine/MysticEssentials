# Mystic Essentials — Permission Reference

Every permission node lives in one place in the code:
[`api/Permissions.java`](src/main/java/org/hyzionstudios/mysticessentials/api/Permissions.java).
This file is the operator-facing reference generated from it.

All nodes are prefixed `mysticessentials.`.

## Core

| Node | Grants |
|---|---|
| `mysticessentials.reload` | `/mystic reload` |
| `mysticessentials.migrate` | `/mystic migrate scan`, `/mystic migrate import` |

## Teleportation

| Node | Grants |
|---|---|
| `mysticessentials.teleport.tpa` | `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny`, `/tpcancel` + the Teleport Requests UI (incl. favorites) |
| `mysticessentials.teleport.tphere` | `/tphere <player>` — teleport one player to you |
| `mysticessentials.teleport.tpall` | `/tpall` — teleport every online player to you |
| `mysticessentials.teleport.top` | `/top` — teleport to the highest block in your current column |
| `mysticessentials.teleport.back` | `/back` |
| `mysticessentials.teleport.bypass.warmup` | Skip teleport warmups |
| `mysticessentials.teleport.bypass.cooldown` | Skip teleport cooldowns |

## Spawn & Homes

| Node | Grants |
|---|---|
| `mysticessentials.spawn.use` | `/spawn` |
| `mysticessentials.spawn.set` | `/setspawn`, `/setworldspawn` (both refuse temporary worlds) |
| `mysticessentials.home.use` | `/home [name]`, `/homes` + Homes UI |
| `mysticessentials.home.set` | `/sethome [name]`, `/delhome [name]`, `/renamehome <old> <new>` |
| `mysticessentials.home.limit.<n>` | Dynamic home limit (highest wins; `.unlimited` = no limit) |

## Warps

| Node | Grants |
|---|---|
| `mysticessentials.warp.use` | `/warp [name]`, `/warps` + Warps UI |
| `mysticessentials.warp.set` | `/setwarp`, `/delwarp` + the in-UI warp admin editor |
| `mysticessentials.playerwarp.use` | `/pwarp` browse/teleport |
| `mysticessentials.playerwarp.create` | `/pwarp create <name>` |
| `mysticessentials.playerwarp.admin` | Delete/manage any player warp |
| `mysticessentials.playerwarp.limit.<n>` | Dynamic player-warp limit (`.unlimited` supported) |

## Mail

| Node | Grants |
|---|---|
| `mysticessentials.mail.use` | `/mail` + the Mail UI (read/delete/clear own inbox) |
| `mysticessentials.mail.send` | Sending mail (command + UI compose) |
| `mysticessentials.mail.send.offline` | Sending to offline players |
| `mysticessentials.mail.send.all` | `/mail sendall` + the server-wide mail row in the UI |

## Announcements

| Node | Grants |
|---|---|
| `mysticessentials.announcement.broadcast` | `/broadcast` (`/bc`) — uses the configurable `broadcastPrefix` |
| `mysticessentials.announcement.alert` | `/alert` — uses the configurable `alertPrefix` |

## AFK

| Node | Grants |
|---|---|
| `mysticessentials.afk.use` | `/afk [reason]` |
| `mysticessentials.afk.bypass.auto` | Never auto-flagged AFK |
| `mysticessentials.afk.rewards` | Eligible for AFK rewards (when enabled) |

## Chat

| Node | Grants |
|---|---|
| `mysticessentials.chat.private.message` | `/msg` |
| `mysticessentials.chat.private.reply` | `/reply` |
| `mysticessentials.chat.socialspy` | See other players' private messages |
| `mysticessentials.chat.socialspy.exempt` | Hidden from social spy |
| `mysticessentials.chat.channel.create.temp` | `/channel temp` — temporary channels |
| `mysticessentials.chat.channel.<id>[.speak/.listen/.moderator]` | Dynamic per-channel gates (configured per channel) |
| `mysticessentials.chat.color.legacy/hex/gradient/rainbow/minimessage/links` | Colour/markup styles in chat messages |
| `mysticessentials.chat.emoji.use/custom/staff`, `mysticessentials.chat.unicode.symbols` | Glyph/emoji tiers |

## Kits

| Node | Grants |
|---|---|
| `mysticessentials.kit.use` | `/kit`, `/kit list`, claiming kits |
| `mysticessentials.kit.<name>` | Dynamic: access to a kit with `requirePermission: true` |
| `mysticessentials.kit.bypass.cooldown` | Ignore kit cooldowns (incl. one-time kits) |
| `mysticessentials.kit.admin` | `/kit give <player> <kit>` |

## Flight

| Node | Grants |
|---|---|
| `mysticessentials.fly.use` | `/fly` |
| `mysticessentials.fly.others` | `/fly <player>` |
| `mysticessentials.fly.unlimited` | Free unlimited flight (paid mode exempt) |
| `mysticessentials.fly.free` | Exempt from paid-flight charges |

## Inventory

| Node | Grants |
|---|---|
| `mysticessentials.inventory.clear` | `/clearinventory` (`/clearinv`) on yourself, `/inventory clear` |
| `mysticessentials.inventory.clear.others` | `/clearinventory <player>` |
| `mysticessentials.inventory.clear.all` | `/clearinventory all` |
| `mysticessentials.inventory.protect` | Skipped by `/clearinventory all` |
| `mysticessentials.inventory.restore` | `/inventory restore <player>` + the Snapshot Restore UI |

## Nicknames

| Node | Grants |
|---|---|
| `mysticessentials.nick.use` | `/nick` (+ UI), `/nick <name>`, `/nick reset` |
| `mysticessentials.nick.color` | Colour options in the nick UI / colour codes in `/nick` |
| `mysticessentials.nick.others` | `/nick <player> <name>` |

## Dynamic limits

Numeric limits (`home.limit`, `playerwarp.limit`) are resolved by probing
`<base>.<n>` for n = 0..128 and taking the highest granted, with
`<base>.unlimited` meaning no limit. Configure via LuckPerms, e.g.
`lp group vip permission set mysticessentials.home.limit.10 true`.
