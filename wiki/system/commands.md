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