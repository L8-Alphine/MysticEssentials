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
| `mysticessentials.teleport.tpa` | `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny`, `/tpcancel`, `/tptoggle` + the Teleport Requests UI (incl. favorites) |
| `mysticessentials.teleport.tp` | `/tp [player]` — teleport yourself to a player; no player opens the Teleport Requests UI |
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
| `mysticessentials.mail.use` | `/mail` + the Mail UI (inbox/sent/archived/deleted folders, read/archive/delete/claim own mail) |
| `mysticessentials.mail.send` | Sending mail (command + UI compose) |
| `mysticessentials.mail.send.offline` | Sending to offline players |
| `mysticessentials.mail.send.all` | `/mail sendall` (bulk text mail) |
| `mysticessentials.mail.attach` | Attach items (consumed from your inventory) to composed mail |
| `mysticessentials.mail.announce` | `/mailadmin` — the standalone Mail Admin Center (broadcast announcements with item + command rewards, audience targeting, sent history) |

## Announcements

| Node | Grants |
|---|---|
| `mysticessentials.announcement.broadcast` | `/broadcast` (`/bc`) — uses the configurable `broadcastPrefix` |
| `mysticessentials.announcement.alert` | `/alert` — uses the configurable `alertPrefix` |

## AFK

| Node | Grants |
|---|---|
| `mysticessentials.afk.use` | `/afk [reason]` |
| `mysticessentials.afk.bypass.auto` | Never auto-flagged AFK (movement still clears an existing AFK state) |
| `mysticessentials.afk.rewards` | Eligible for AFK rewards (when enabled) |
| `mysticessentials.afk.zone.admin` | `/afkzone` — create/delete/list AFK reward zones |
| *custom per-zone node* | Zones may require any node via `/afkzone permission <name> <node>`; it gates teleport-in, staying AFK inside, and earning there |

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

## Patch Notes

`mysticessentials.patchnotes.admin` is reserved as the umbrella admin node.

| Node | Grants |
|---|---|
| `mysticessentials.patchnotes.view` | `/patchnotes` (+ UI), `/patchnotes open`, `/patchnotes markread`, `/patchnotes list`, and the join notification |
| `mysticessentials.patchnotes.reload` | `/patchnotes reload` |
| `mysticessentials.patchnotes.open.others` | `/patchnotes open <player>` |
| `mysticessentials.patchnotes.markread.others` | `/patchnotes markread <player>` |

## Tutorial

`mysticessentials.tutorial.admin` grants **every** tutorial node below.

| Node | Grants |
|---|---|
| `mysticessentials.tutorial.list` | `/tutorial list` |
| `mysticessentials.tutorial.info` | `/tutorial info <tutorial>` |
| `mysticessentials.tutorial.play` | `/tutorial play <tutorial>` (self) + the `/tutorial <tutorial>` shortcut |
| `mysticessentials.tutorial.play.others` | `/tutorial play <tutorial> <player> [--force]` |
| `mysticessentials.tutorial.stop` | `/tutorial stop` (self) |
| `mysticessentials.tutorial.stop.others` | `/tutorial stop <player>` |
| `mysticessentials.tutorial.skip` | `/tutorial skip` (self; also allowed via `defaults.allowSkip` + `defaults.skipPermission`) |
| `mysticessentials.tutorial.skip.others` | `/tutorial skip <player>` |
| `mysticessentials.tutorial.reset` | `/tutorial reset <tutorial> <player>` (works for offline players) |
| `mysticessentials.tutorial.complete` | `/tutorial complete <tutorial> <player>` (works for offline players) |
| `mysticessentials.tutorial.status` | `/tutorial status` (self) |
| `mysticessentials.tutorial.status.others` | `/tutorial status <player>` |
| `mysticessentials.tutorial.page` | `/tutorial page <page>` (self) |
| `mysticessentials.tutorial.page.others` | `/tutorial page <page> <player>` |
| `mysticessentials.tutorial.reload` | `/tutorial reload` |
| `mysticessentials.tutorial.debug` | `/tutorial debug <on\|off>` |
| `mysticessentials.tutorial.scene` | `/tutorial scene <list\|info\|import\|play\|stop>` (cinematic scene tooling) |
| `mysticessentials.tutorial.bypassfirstjoin` | Exempt from the first-join tutorial |

## Custom Commands

`mysticessentials.customcommands.admin` grants **every** admin node below.
Access to the custom commands themselves is defined per command in its JSON
file (`permission.mode`: `none`, `single`, `all`, `any`).

| Node | Grants |
|---|---|
| `mysticessentials.customcommands.list` | `/customcommands list` |
| `mysticessentials.customcommands.info` | `/customcommands info <command>` |
| `mysticessentials.customcommands.reload` | `/customcommands reload` |
| `mysticessentials.customcommands.manage` | `/customcommands enable\|disable <command>` |
| `mysticessentials.customcommands.test` | `/customcommands test <command> [args...]` |
| `mysticessentials.customcommands.validate` | `/customcommands validate` |
| `mysticessentials.customcommands.bypass.cooldown` | Bypass every custom command cooldown |
| `mysticessentials.customcommands.bypass.cooldown.<name>` | Bypass one command's cooldown |
| `mysticessentials.customcommands.command.<name>` | Implicit use node for a command with `permission.mode: single` and no explicit `node` |

Per-command definitions may also name arbitrary nodes (e.g. `myserver.vip`) in
`permission.node` / `permission.nodes` and an extra `cooldown.bypassPermission`.

## Player Vaults

Disabled by default. `mysticessentials.vaults.admin` grants **every**
`mysticessentials.vaults.admin.*` node below.

| Node | Grants |
|---|---|
| `mysticessentials.vaults.command` | Base `/playervault` access (aliases `/pv`, `/vault`, `/vaults`, `/playervaults`) |
| `mysticessentials.vaults.command.edit` | `/pv edit <n>` (opens the editor) |
| `mysticessentials.vaults.command.list` | `/pv list` |
| `mysticessentials.vaults.command.reload` | `/pv reload` |
| `mysticessentials.vaults.vault.<n>` | Highest accessible vault number (grants vaults `1..n`) |
| `mysticessentials.vaults.rows.<n>` | Rows exposed per vault (capped by config `maxRows`) |
| `mysticessentials.vaults.editor` | Access to the vault editor UI |
| `mysticessentials.vaults.editor.name` | Rename a vault |
| `mysticessentials.vaults.editor.color` | Change vault colour |
| `mysticessentials.vaults.editor.color.hex` | Use raw `#RRGGBB` colours |
| `mysticessentials.vaults.editor.icon` | Change vault icon (any valid item) |
| `mysticessentials.vaults.editor.description` | Edit vault description |
| `mysticessentials.vaults.editor.reset` | Reset a vault's metadata to defaults |
| `mysticessentials.vaults.admin.open` | Open another player's vault (`/pv <n> <player>`) |
| `mysticessentials.vaults.admin.open.offline` | Open offline players' vaults |
| `mysticessentials.vaults.admin.edit` | Modify another player's vault contents |
| `mysticessentials.vaults.admin.readonly` | Open another player's vault read-only |
| `mysticessentials.vaults.admin.unlock` | `/pv admin unlock <player> <n>` (force-unlock a stuck lock) |
| `mysticessentials.vaults.admin.restore` | `/pv admin restore <player> <n> <backupId>` |
| `mysticessentials.vaults.admin.viewlogs` | `/pv admin logs <player>` |
| `mysticessentials.vaults.admin.bypasslimit` | View/recover overflow items beyond an owner's row/vault limits |

`vaults.vault.<n>` and `vaults.rows.<n>` resolve to the highest granted number
(see **Dynamic limits**); `vaults.vault.*` / `vaults.rows.*` grant the config
maximum.

## Dynamic limits

Numeric limits (`home.limit`, `playerwarp.limit`, `vaults.vault`, `vaults.rows`)
are resolved by probing `<base>.<n>` for n = 0..128/256 and taking the highest
granted, with `<base>.unlimited` (or `<base>.*` for vaults) meaning the maximum.
Configure via LuckPerms, e.g.
`lp group vip permission set mysticessentials.home.limit.10 true` or
`lp group vip permission set mysticessentials.vaults.vault.5 true`.
