# Permissions Reference

All permission nodes are prefixed with `mysticessentials.`. Dynamic nodes such as limits and per-kit permissions are described in their sections.

## Core

| Node | Grants |
| --- | --- |
| `mysticessentials.reload` | `/mystic reload` |
| `mysticessentials.migrate` | `/mystic migrate scan`, `/mystic migrate import` |

## Teleportation

| Node | Grants |
| --- | --- |
| `mysticessentials.teleport.tpa` | `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny`, `/tpcancel`, and the Teleport Requests UI |
| `mysticessentials.teleport.tphere` | `/tphere <player>` |
| `mysticessentials.teleport.tpall` | `/tpall` |
| `mysticessentials.teleport.top` | `/top` |
| `mysticessentials.teleport.back` | `/back` |
| `mysticessentials.teleport.bypass.warmup` | Skip teleport warmups |
| `mysticessentials.teleport.bypass.cooldown` | Skip teleport cooldowns |

## Spawn and homes

| Node | Grants |
| --- | --- |
| `mysticessentials.spawn.use` | `/spawn` |
| `mysticessentials.spawn.set` | `/setspawn`, `/setworldspawn` |
| `mysticessentials.home.use` | `/home`, `/homes`, Homes UI |
| `mysticessentials.home.set` | `/sethome`, `/delhome`, `/renamehome` |
| `mysticessentials.home.limit.<n>` | Numeric home limit; highest granted value wins |
| `mysticessentials.home.limit.unlimited` | Unlimited homes |

## Warps

| Node | Grants |
| --- | --- |
| `mysticessentials.warp.use` | `/warp`, `/warps`, Warps UI |
| `mysticessentials.warp.set` | `/setwarp`, `/delwarp`, server warp admin UI |
| `mysticessentials.playerwarp.use` | `/pwarp` browse, teleport, manager access, and own delete command |
| `mysticessentials.playerwarp.create` | `/pwarp create <name>` |
| `mysticessentials.playerwarp.admin` | Delete/manage any player warp |
| `mysticessentials.playerwarp.limit.<n>` | Numeric player-warp limit; highest granted value wins |
| `mysticessentials.playerwarp.limit.unlimited` | Unlimited player warps |

## Mail

| Node | Grants |
| --- | --- |
| `mysticessentials.mail.use` | `/mail`, inbox, read, delete, clear |
| `mysticessentials.mail.send` | Send mail to online players |
| `mysticessentials.mail.send.offline` | Send mail to offline known players |
| `mysticessentials.mail.send.all` | `/mail sendall` and server-wide mail UI row |

## Announcements

| Node | Grants |
| --- | --- |
| `mysticessentials.announcement.broadcast` | `/broadcast`, `/bc` |
| `mysticessentials.announcement.alert` | `/alert` |

## AFK

| Node | Grants |
| --- | --- |
| `mysticessentials.afk.use` | `/afk [reason]` |
| `mysticessentials.afk.bypass.auto` | Player is never automatically marked AFK |
| `mysticessentials.afk.rewards` | Player can earn AFK rewards when the reward submodule is enabled |

## Chat

| Node | Grants |
| --- | --- |
| `mysticessentials.chat.private.message` | `/msg`, `/tell`, `/w`, `/whisper` |
| `mysticessentials.chat.private.reply` | `/reply`, `/r` |
| `mysticessentials.chat.socialspy` | See other players' private messages |
| `mysticessentials.chat.socialspy.exempt` | Hide a player's private messages from social spy |
| `mysticessentials.chat.channel.create.temp` | Create temporary channels |
| `mysticessentials.chat.channel.<id>` | Dynamic per-channel gate |
| `mysticessentials.chat.channel.<id>.speak` | Dynamic speak gate for a channel |
| `mysticessentials.chat.channel.<id>.listen` | Dynamic listen gate for a channel |
| `mysticessentials.chat.channel.<id>.moderator` | Dynamic moderation/management gate for a channel |
| `mysticessentials.chat.color.legacy` | Use legacy `&a` style color codes in chat |
| `mysticessentials.chat.color.hex` | Use hex colors such as `&#ff8800` |
| `mysticessentials.chat.color.gradient` | Use gradients |
| `mysticessentials.chat.color.rainbow` | Use rainbow formatting |
| `mysticessentials.chat.color.minimessage` | Use MiniMessage-style tags |
| `mysticessentials.chat.color.links` | Use clickable links |
| `mysticessentials.chat.emoji.use` | Use standard emoji/glyph tier |
| `mysticessentials.chat.emoji.custom` | Use custom glyph aliases |
| `mysticessentials.chat.emoji.staff` | Use staff glyph tier |
| `mysticessentials.chat.unicode.symbols` | Use raw Unicode symbols |

## Kits

| Node | Grants |
| --- | --- |
| `mysticessentials.kit.use` | `/kit`, `/kits`, kit claiming |
| `mysticessentials.kit.<name>` | Access to a kit with `requirePermission: true` |
| `mysticessentials.kit.bypass.cooldown` | Ignore kit cooldowns and one-time kit locks |
| `mysticessentials.kit.admin` | `/kit give <player> <kit>` |

## Flight

| Node | Grants |
| --- | --- |
| `mysticessentials.fly.use` | `/fly` |
| `mysticessentials.fly.others` | `/fly <player>` |
| `mysticessentials.fly.unlimited` | Unlimited/free flight in paid flight mode |
| `mysticessentials.fly.free` | Exempt from paid flight charges |

## Inventory

| Node | Grants |
| --- | --- |
| `mysticessentials.inventory.clear` | Clear own inventory |
| `mysticessentials.inventory.clear.others` | Clear another player's inventory |
| `mysticessentials.inventory.clear.all` | Clear every online player's inventory |
| `mysticessentials.inventory.protect` | Player is skipped by `/clearinventory all` |
| `mysticessentials.inventory.restore` | Snapshot Restore UI |

## Nicknames

| Node | Grants |
| --- | --- |
| `mysticessentials.nick.use` | `/nick`, nickname UI, set/reset own nickname |
| `mysticessentials.nick.color` | Color options and color codes in nicknames |
| `mysticessentials.nick.others` | Set another player's nickname |

## Dynamic limit examples

LuckPerms examples:

```text
lp group default permission set mysticessentials.home.limit.3 true
lp group vip permission set mysticessentials.home.limit.10 true
lp group staff permission set mysticessentials.home.limit.unlimited true

lp group default permission set mysticessentials.playerwarp.limit.1 true
lp group supporter permission set mysticessentials.playerwarp.limit.5 true
```

Mystic probes numeric limit nodes from `0` through `128` and uses the highest granted value. The `.unlimited` suffix resolves as unlimited.