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

## Random Teleport

| Node | Grants |
| --- | --- |
| `mysticessentials.teleport.rtp.use` | `/rtp` and the selection UI |
| `mysticessentials.teleport.rtp.biome` | `/rtp biome <biome>` |
| `mysticessentials.teleport.rtp.others` | `/rtp <player>` (send another player) |
| `mysticessentials.teleport.rtp.cancel` / `.status` | Cancel / status forms |
| `mysticessentials.teleport.rtp.bypass.warmup` | Skip RTP warmups |
| `mysticessentials.teleport.rtp.bypass.cooldown` | Skip RTP cooldowns |
| `mysticessentials.teleport.rtp.bypass.cost` | Skip RTP costs |
| `mysticessentials.teleport.rtp.bypass.combat` | RTP while in combat |
| `mysticessentials.teleport.rtp.bypass.queue` | Skip the search queue |
| `mysticessentials.teleport.rtp.bypass.limits` | Ignore per-hour/day use limits |
| `mysticessentials.teleport.rtp.admin` | `/rtpadmin` (grants every `...rtp.admin.*` node) |
| `mysticessentials.teleport.rtp.world.<world>` | Dynamic: permission to RTP in a world |
| `mysticessentials.teleport.rtp.profile.<profile>` | Dynamic: permission to use a profile |
| `mysticessentials.teleport.rtp.cooldown.<seconds>` | Dynamic: per-rank cooldown override |
| `mysticessentials.teleport.rtp.limit.daily.<n>` / `.limit.hourly.<n>` | Dynamic: per-rank use caps |
| `mysticessentials.teleport.rtp.priority.<n>` | Dynamic: queue/profile priority |

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

## Portals

| Node | Grants |
| --- | --- |
| `mysticessentials.portal.admin` | `/portal` commands + the Use (F) portal config page |

Each portal can additionally require its own permission node (free-form, set in the portal
config page); players without it cannot use that portal.

## Mail

| Node | Grants |
| --- | --- |
| `mysticessentials.mail.use` | `/mail`, inbox, read, delete, clear |
| `mysticessentials.mail.send` | Send mail to online players |
| `mysticessentials.mail.send.offline` | Send mail to offline known players |
| `mysticessentials.mail.send.all` | `/mail sendall` and server-wide mail UI row |
| `mysticessentials.mail.attach` | Attach items to normal mail |
| `mysticessentials.mail.announce` | `/mailadmin` and reward-carrying announcements |

## Patch Notes

| Node | Grants |
| --- | --- |
| `mysticessentials.patchnotes.view` | Open, list, and mark-read patch notes |
| `mysticessentials.patchnotes.admin` | Every `mysticessentials.patchnotes.*` admin node |
| `mysticessentials.patchnotes.reload` | `/patchnotes reload` |
| `mysticessentials.patchnotes.open.others` | Open the viewer for another player |
| `mysticessentials.patchnotes.markread.others` | Mark notes read for another player |

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
| `mysticessentials.afk.zone.admin` | Manage the AFK reward zone |

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
| `mysticessentials.chat.itemlink.use` | Share your held item in chat with the `[item]` tag ([item links](itemlinks-module)) |

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

## Player Vaults

| Node | Grants |
| --- | --- |
| `mysticessentials.vaults.command` / `.command.open` | Open the vault list and individual vaults |
| `mysticessentials.vaults.command.edit` | Edit vault metadata |
| `mysticessentials.vaults.command.list` | `/pv list` |
| `mysticessentials.vaults.command.reload` | `/pv reload` |
| `mysticessentials.vaults.vault.<n>` | Dynamic: highest accessible vault number |
| `mysticessentials.vaults.rows.<n>` | Dynamic: rows per vault (capped by `maxRows`) |
| `mysticessentials.vaults.editor`, `.editor.name`, `.editor.color`, `.editor.color.hex`, `.editor.icon`, `.editor.description`, `.editor.reset` | Per-field metadata editing |
| `mysticessentials.vaults.admin` | Every `mysticessentials.vaults.admin.*` node |
| `mysticessentials.vaults.admin.open`, `.open.offline`, `.edit`, `.readonly`, `.unlock`, `.restore`, `.viewlogs`, `.bypasslimit` | Admin vault operations |

## Tutorial

| Node | Grants |
| --- | --- |
| `mysticessentials.tutorial.admin` | Every `mysticessentials.tutorial.*` node |
| `mysticessentials.tutorial.play` / `.play.others` | Start the tutorial for self / others |
| `mysticessentials.tutorial.stop`, `.skip`, `.status`, `.page` (and `.others` variants) | Control and inspect sessions |
| `mysticessentials.tutorial.list`, `.info`, `.reset`, `.complete`, `.reload`, `.debug`, `.scene` | Management and admin tools |
| `mysticessentials.tutorial.bypassfirstjoin` | Player is not auto-started on first join |

## Custom Commands

| Node | Grants |
| --- | --- |
| `mysticessentials.customcommands.admin` | Every `mysticessentials.customcommands.*` node |
| `mysticessentials.customcommands.list`, `.info`, `.test`, `.validate` | Inspect and test commands |
| `mysticessentials.customcommands.manage` | `/customcommands enable\|disable` |
| `mysticessentials.customcommands.reload` | Reload definitions |
| `mysticessentials.customcommands.bypass.cooldown[.<name>]` | Bypass all / one command's cooldown |
| `mysticessentials.customcommands.command.<name>` | Dynamic: implicit per-command use node |

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