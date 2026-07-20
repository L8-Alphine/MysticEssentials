# Commands Reference

Optional arguments use `[brackets]`. Required arguments use `<angle brackets>`.

## Core

| Command | Description | Permission |
| --- | --- | --- |
| `/mystic`, `/mysticessentials`, `/me` | Show Mystic Essentials information | None |
| `/mystic reload` | Reload core and module configuration | `mysticessentials.reload` |
| `/mystic migrate scan <source> [path]` | Preview file-based migration from a legacy essentials data folder | `mysticessentials.migrate` |
| `/mystic migrate import <source> [path] [--replace] [--dry-run]` | Import supported legacy data into Mystic Essentials | `mysticessentials.migrate` |

## Teleportation

| Command | Description | Permission |
| --- | --- | --- |
| `/tpa` | Open the Teleport Requests UI | `mysticessentials.teleport.tpa` |
| `/tpa <player>` | Request to teleport to a player | `mysticessentials.teleport.tpa` |
| `/tpahere` | Open the Teleport Requests UI | `mysticessentials.teleport.tpa` |
| `/tpahere <player>` | Request that a player teleports to you | `mysticessentials.teleport.tpa` |
| `/tpaccept [player]` | Accept the newest request or one from a specific player | `mysticessentials.teleport.tpa` |
| `/tpdeny [player]` | Deny the newest request or one from a specific player | `mysticessentials.teleport.tpa` |
| `/tpcancel` | Cancel outgoing teleport requests | `mysticessentials.teleport.tpa` |
| `/tphere <player>` | Teleport one player to you | `mysticessentials.teleport.tphere` |
| `/tpall` | Teleport every online player to you | `mysticessentials.teleport.tpall` |
| `/top` | Teleport to the highest available position above you | `mysticessentials.teleport.top` |
| `/back` | Return to your previous location | `mysticessentials.teleport.back` |

## Random Teleport

See the [Random Teleport](rtp-module) page for full details.

| Command | Description | Permission |
| --- | --- | --- |
| `/rtp` | Teleport to a random safe location (or open the RTP UI) | `mysticessentials.teleport.rtp.use` |
| `/rtp world <world> [player]` | RTP into a specific world | `mysticessentials.teleport.rtp.use` |
| `/rtp profile <profile> [player]` | RTP using a named profile | `mysticessentials.teleport.rtp.use` |
| `/rtp biome <biome>` | RTP into a biome | `mysticessentials.teleport.rtp.biome` |
| `/rtp <player>` | RTP another online player | `mysticessentials.teleport.rtp.others` |
| `/rtp cancel` / `status` / `info [profile]` | Cancel, check status, or inspect a profile | `mysticessentials.teleport.rtp.use` |
| `/rtpadmin`, `/rtpa` | Administer profiles, queue, and search (reload/test/preview/debug/enable/disable/setcenter/clearcache/queue/ui/cancel/spread/queue-login) | `mysticessentials.teleport.rtp.admin` |

## Spawn and homes

| Command | Description | Permission |
| --- | --- | --- |
| `/spawn` | Teleport to the global spawn, falling back to world spawn if needed | `mysticessentials.spawn.use` |
| `/setspawn` | Set the global spawn | `mysticessentials.spawn.set` |
| `/setworldspawn` | Set the current world's spawn | `mysticessentials.spawn.set` |
| `/home` | Open the Homes UI or use the default home behavior | `mysticessentials.home.use` |
| `/home <name>` | Teleport to a named home | `mysticessentials.home.use` |
| `/homes` | Open the Homes UI | `mysticessentials.home.use` |
| `/sethome [name]` | Create or update a home | `mysticessentials.home.set` |
| `/delhome [name]` | Delete a home | `mysticessentials.home.set` |
| `/renamehome <old> <new>` | Rename a home | `mysticessentials.home.set` |

## Warps

| Command | Description | Permission |
| --- | --- | --- |
| `/warp` | Open the Warps UI | `mysticessentials.warp.use` |
| `/warps` | Open the Warps UI, or list warps in console | `mysticessentials.warp.use` |
| `/warp <name>` | Teleport to a visible server warp | `mysticessentials.warp.use` |
| `/setwarp <name>` | Create or update a server warp at your position | `mysticessentials.warp.set` |
| `/delwarp <name>` | Delete a server warp | `mysticessentials.warp.set` |
| `/pwarp`, `/pwarps`, `/playerwarp`, `/playerwarps` | Browse player warps | `mysticessentials.playerwarp.use` |
| `/pwarp <name>` | Teleport to a player warp | `mysticessentials.playerwarp.use` |
| `/pwarp create <name>` | Create a player warp at your current position | `mysticessentials.playerwarp.create` |
| `/pwarp manage` | Open your Player Warp Manager | `mysticessentials.playerwarp.use` |
| `/pwarp delete <name>` | Delete your player warp; admins may delete any | `mysticessentials.playerwarp.use`, plus `mysticessentials.playerwarp.admin` for other owners |

## Portals

| Command | Description | Permission |
| --- | --- | --- |
| `/portal list` | List every portal with type, target, and location | `mysticessentials.portal.admin` |
| `/portal edit` | Open the config page for the nearest portal (8 blocks) | `mysticessentials.portal.admin` |
| `/portal remove <id>` | Delete a portal by id | `mysticessentials.portal.admin` |

Alias: `/portals`. Portals are configured in-game by pressing Use (F) on a portal block.

## Mail

| Command | Description | Permission |
| --- | --- | --- |
| `/mail` | Open the Mail UI | `mysticessentials.mail.use` |
| `/mail inbox` | View inbox in chat | `mysticessentials.mail.use` |
| `/mail read <id>` | Read a mail item | `mysticessentials.mail.use` |
| `/mail send <player> <message>` | Send mail | `mysticessentials.mail.send`; offline targets need `mysticessentials.mail.send.offline` |
| `/mail sendall <message>` | Send mail to all known players | `mysticessentials.mail.send.all` |
| `/mail delete <id>` | Delete a mail item | `mysticessentials.mail.use` |
| `/mail clear` | Clear your inbox | `mysticessentials.mail.use` |
| `/mailadmin` | Open the mail admin center (announcements, item/command rewards) | `mysticessentials.mail.announce` |

## Chat

| Command | Description | Permission |
| --- | --- | --- |
| `/msg <player> <message>` | Send a private message | `mysticessentials.chat.private.message` |
| `/tell`, `/w`, `/whisper` | Aliases for `/msg` | `mysticessentials.chat.private.message` |
| `/reply <message>`, `/r <message>` | Reply to the last private message | `mysticessentials.chat.private.reply` |
| `/channel`, `/ch` | Open the channel browser or show channel state | None by default |
| `/channel <name>` | Switch speaking channel | Channel permissions may apply |
| `/channel switch <name> [password]` | Switch speaking channel | Channel permissions may apply |
| `/channel join <name> [password]` | Listen to a channel | Channel permissions may apply |
| `/channel leave <name>` | Stop listening to a channel | None by default |
| `/channel temp <id> [password|-] [prefix|-] [alias1,alias2|-] [permission]` | Create a temporary channel | `mysticessentials.chat.channel.create.temp` |
| `/channel manage` | Manage your temporary channel | Owner/moderator rules apply |
| Configured aliases such as `/g`, `/global`, `/sc`, `/schat`, `/staffchat` | Switch channel quickly | Channel permissions may apply |
| `[item]` (chat tag) | Share your held item in chat as an [item link](itemlinks-module) | `mysticessentials.chat.itemlink.use` |
| `/itemview <code>` | Open the item viewer for a shared [item link](itemlinks-module) | None by default |
| `/iteminspect [latest\|<number>\|<code>]` | Inspect the latest, nth, or a specific shared item (alias `/inspectitem`) | None by default |
| `/itemlinks`, `/recentitems` | Browse items recently shared in chat | None by default |

## Announcements, AFK, and greetings

| Command | Description | Permission |
| --- | --- | --- |
| `/broadcast <message>`, `/bc <message>` | Broadcast with the configured broadcast prefix | `mysticessentials.announcement.broadcast` |
| `/alert <message>` | Broadcast with the configured alert prefix | `mysticessentials.announcement.alert` |
| `/afk [reason]` | Toggle AFK | `mysticessentials.afk.use` |

## Kits

| Command | Description | Permission |
| --- | --- | --- |
| `/kit`, `/kits` | Open/list kits | `mysticessentials.kit.use` |
| `/kit list` | List kits you can claim | `mysticessentials.kit.use` |
| `/kit <name>` | Claim a kit | `mysticessentials.kit.use`; some kits also need `mysticessentials.kit.<name>` |
| `/kit give <player> <kit>` | Give a kit while ignoring player gating | `mysticessentials.kit.admin` |

## Flight

| Command | Description | Permission |
| --- | --- | --- |
| `/fly` | Toggle your flight | `mysticessentials.fly.use` |
| `/fly <player>` | Toggle flight for another player | `mysticessentials.fly.others` |

## Inventory

| Command | Description | Permission |
| --- | --- | --- |
| `/clearinventory`, `/clearinv` | Clear your inventory after taking a snapshot | `mysticessentials.inventory.clear` |
| `/clearinventory <player>` | Clear another player's inventory | `mysticessentials.inventory.clear.others` |
| `/clearinventory all` | Clear every online player's inventory except protected players | `mysticessentials.inventory.clear.all` |
| `/inventory clear [player/all]`, `/inv clear [player/all]` | Inventory clear alias path | Same as above |
| `/inventory restore <player>` | Open the Snapshot Restore UI | `mysticessentials.inventory.restore` |

## Nicknames

| Command | Description | Permission |
| --- | --- | --- |
| `/nick`, `/nickname` | Open the Nickname UI | `mysticessentials.nick.use` |
| `/nick <name>` | Set your nickname | `mysticessentials.nick.use`; color needs `mysticessentials.nick.color` |
| `/nick reset` | Remove your nickname | `mysticessentials.nick.use` |
| `/nick <player> <name>` | Set another player's nickname | `mysticessentials.nick.others` |

## Player Vaults

See the [Player Vaults](playervaults-module) page. The module is disabled by default.

| Command | Description | Permission |
| --- | --- | --- |
| `/pv`, `/playervault`, `/vault`, `/vaults` | Open your vault list | `mysticessentials.vaults.command.open` |
| `/pv <number>` | Open a specific vault | `mysticessentials.vaults.command.open` |
| `/pv edit <number>` | Edit vault name/color/icon/description | `mysticessentials.vaults.command.edit` |
| `/pv list` | List your vaults | `mysticessentials.vaults.command.list` |
| `/pv <number> <player>` | Open another player's vault | `mysticessentials.vaults.admin.open` |
| `/pv reload` | Reload the vault config | `mysticessentials.vaults.command.reload` |

## Patch Notes

See the [Patch Notes](patchnotes-module) page.

| Command | Description | Permission |
| --- | --- | --- |
| `/patchnotes`, `/patches`, `/updates`, `/changelog` | Open the Patch Notes viewer | `mysticessentials.patchnotes.view` |
| `/patchnotes open [player]` | Open the viewer (optionally for another player) | `mysticessentials.patchnotes.view` (others: `mysticessentials.patchnotes.open.others`) |
| `/patchnotes list` | List patch notes in chat | `mysticessentials.patchnotes.view` |
| `/patchnotes markread` | Mark all notes read | `mysticessentials.patchnotes.view` (others: `mysticessentials.patchnotes.markread.others`) |
| `/patchnotes reload` | Reload patch notes from disk | `mysticessentials.patchnotes.reload` |

## Tutorial

See the [Tutorial module](tutorial-module) page for the full command tree.

| Command | Description | Permission |
| --- | --- | --- |
| `/tutorial play [player]` | Start the tutorial | `mysticessentials.tutorial.play` (others: `...play.others`) |
| `/tutorial stop`, `skip`, `status`, `page`, `list`, `info` | Control and inspect tutorial sessions | Matching `mysticessentials.tutorial.*` nodes |
| `/tutorial reload`, `reset`, `complete`, `debug`, `scene` | Admin tools | `mysticessentials.tutorial.admin` |

## Custom Commands

See the [Custom Commands module](customcommands-module) page. Operator-defined commands are also registered dynamically.

| Command | Description | Permission |
| --- | --- | --- |
| `/customcommands list`, `info`, `test`, `validate` | Inspect and test custom commands | Matching `mysticessentials.customcommands.*` nodes |
| `/customcommands enable`, `disable` | Toggle a custom command | `mysticessentials.customcommands.manage` |
| `/customcommands reload` | Reload custom command definitions | `mysticessentials.customcommands.reload` |