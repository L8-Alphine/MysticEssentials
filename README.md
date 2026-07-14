# Mystic Essentials

The modular, API-first essentials foundation for Hytale servers — homes, warps,
spawn, teleport requests, mail, chat formatting, private messages, announcements,
AFK, greetings/MOTD, kits, flight, inventory snapshots, nicknames, and more.
Every feature is a module you can toggle in `config.json`, and it integrates
with LuckPerms, PlaceholderAPI, VaultUnlocked, and MysticVanish.

Built and tested against **Hytale Server 0.5.6**.

---

## Installation

1. Drop `MysticEssentials-1.0.0.jar` into your server's `mods/` folder.
2. Start the server once — it generates `mods/MysticEssentials/config.json`, the
   message bundle, and per-module config files.
3. (Optional) Install **LuckPerms**, **PlaceholderAPI**, and/or **VaultUnlocked**
   for permissions, placeholders, and economy features. They're auto-detected.
4. Edit configs, then run `/mystic reload` (or restart).

Storage defaults to local JSON. For production, set `storage.provider` to
`mysql` or `mariadb` (driver + pool are bundled).

---

## Migration support

Mystic Essentials includes an in-game, file-based migration flow for server
owners moving from other Hytale essentials mods. The old mod jar does not need
to be installed or loaded; only the old data files need to be present on disk.

Supported source names:

| Source | Command source |
|---|---|
| EssentialsPlus | `essentialsplus` |
| Hyssentials | `hyssentials` |
| EliteEssentials | `eliteessentials` |
| Essentials | `essentials` |
| HyEssentialsX | `hyessentialsx` |
| Automatic/sibling-folder scan | `auto` |

Recommended flow:

```text
/mystic migrate scan <source> [path]
/mystic migrate import <source> [path]
```

If `path` is omitted, Mystic checks sibling folders under `mods/`, such as
`mods/EssentialsPlus`, `mods/Hyssentials`, `mods/EliteEssentials`,
`mods/Essentials`, and `mods/HyEssentialsX`. Use quotes around paths with
spaces.

Current built-in imports:

| Legacy data | Mystic destination |
|---|---|
| Homes | Player profile `moduleData.homes` |
| Global spawn | `modules/spawn/config.json` |
| Per-world spawns | `modules/spawn/config.json` |
| Server warps | `data/modules/warps/server.json` |
| Player warps | `data/modules/warps/playerwarps.json` |
| Kits | `modules/kits/config.json` |

Imports merge by default and skip existing Mystic entries with the same name.
Use `--replace` only when you intentionally want legacy data to overwrite
existing Mystic data. Use `--dry-run` with `import` to preview the import path
without writing.

The importer writes timestamped backups of Mystic module files before changing
them and never deletes source data. Mail, nicknames, chat formats, MOTD,
economy balances, and moderation data still need manual migration or dedicated
source-specific import support.

For detailed planning and validation, see
[`wiki/migration-guide.md`](wiki/migration-guide.md).

---

## Commands

Optional arguments are shown in `[brackets]`, required in `<angle brackets>`.
Player, home, and warp names autocomplete.

### Core
| Command | Description | Permission |
|---|---|---|
| `/mystic [info\|reload]` | Show info or reload configs (aliases `/mysticessentials`, `/me`) | `mysticessentials.reload` (reload) |
| `/mystic migrate scan <source> [path]` | Preview a file-based import from a legacy essentials data folder | `mysticessentials.migrate` |
| `/mystic migrate import <source> [path] [--replace] [--dry-run]` | Import supported legacy homes, spawns, warps, player warps, and kits | `mysticessentials.migrate` |

### Teleportation
| Command | Description | Permission |
|---|---|---|
| `/tpa [player]` | Request to teleport to a player; no player opens the Teleport Requests UI | `mysticessentials.teleport.tpa` |
| `/tpahere [player]` | Request a player teleport to you; no player opens the UI | `mysticessentials.teleport.tpa` |
| `/tpaccept [player]` | Accept a request (latest, or from a specific player) | `mysticessentials.teleport.tpa` |
| `/tpdeny [player]` | Deny a request (latest, or from a specific player) | `mysticessentials.teleport.tpa` |
| `/tpcancel` | Cancel your outgoing requests | `mysticessentials.teleport.tpa` |
| `/tptoggle` | Toggle whether other players can send you teleport requests | `mysticessentials.teleport.tpa` |
| `/tp [player]` | Teleport to a player; no player opens the Teleport Requests UI | `mysticessentials.teleport.tp` |
| `/tphere <player>` | Teleport one player to you | `mysticessentials.teleport.tphere` |
| `/tpall` | Teleport every online player to you | `mysticessentials.teleport.tpall` |
| `/top` | Teleport to the highest block in your current column | `mysticessentials.teleport.top` |
| `/back` | Return to your previous location | `mysticessentials.teleport.back` |

The Teleport Requests UI (`/tpa` with no player) includes a **favorites list**:
add players with `FAV +`, remove with `FAV -`; online favorites get one-click
TPA / TPA HERE buttons and offline favorites stay listed.

### Spawn & Homes
| Command | Description | Permission |
|---|---|---|
| `/spawn` | Teleport to the global spawn (falls back to this world's spawn) | `mysticessentials.spawn.use` |
| `/setspawn` | Set the global spawn (refused in temporary worlds) | `mysticessentials.spawn.set` |
| `/setworldspawn` | Set this world's spawn (refused in temporary worlds) | `mysticessentials.spawn.set` |
| `/home [name]` | Teleport to a home; no name opens the Homes UI | `mysticessentials.home.use` |
| `/sethome [name]` | Create/update a home | `mysticessentials.home.set` |
| `/delhome [name]` | Delete a home | `mysticessentials.home.set` |
| `/renamehome <old> <new>` | Rename a home | `mysticessentials.home.set` |
| `/homes` | Open the Homes UI (teleport / rename / move / delete, shows home limit) | `mysticessentials.home.use` |

### Warps
| Command | Description | Permission |
|---|---|---|
| `/warp [name]` | Teleport to a server warp; no name opens the Warps UI (admins see a Manage button on the selected warp that opens the Warp Admin page) | `mysticessentials.warp.use` |
| `/warps` | Open the Warps UI | `mysticessentials.warp.use` |
| `/setwarp <name>` | Create/update a server warp | `mysticessentials.warp.set` |
| `/delwarp <name>` | Delete a server warp | `mysticessentials.warp.set` |
| `/pwarp` (aliases `/pwarps`, `/playerwarp`, `/playerwarps`) | Browse all player warps in a UI | `mysticessentials.playerwarp.use` |
| `/pwarp <name>` | Teleport to a player warp | `mysticessentials.playerwarp.use` |
| `/pwarp create <name>` | Create a player warp here, then opens its manager UI | `mysticessentials.playerwarp.create` |
| `/pwarp manage` | Open your Player Warp Manager (rename / describe / price / move / delete) | `mysticessentials.playerwarp.use` |
| `/pwarp delete <name>` | Delete your player warp (admins: any) | `mysticessentials.playerwarp.use` (+ `.admin`) |

### Mail
| Command | Description | Permission |
|---|---|---|
| `/mail` | Open the Mail UI — folder sidebar (Inbox / Compose / Sent / Archived / Deleted / Announcements / Attachments), reading pane with **item rewards + Claim**, and a compose pane that attaches items from your inventory | `mysticessentials.mail.use` |
| `/mailadmin` | Open the standalone **Mail Admin Center** — compose & broadcast announcements (item + command rewards) with audience targeting, plus a sent-announcement history with re-send | `mysticessentials.mail.announce` |
| `/mail inbox` | View your inbox in chat | `mysticessentials.mail.use` |
| `/mail read <id>` | Read a mail item | `mysticessentials.mail.use` |
| `/mail send <player> <message>` | Send mail (online or offline) | `mysticessentials.mail.send` (+ `.send.offline`) |
| `/mail sendall <message>` | Send text mail to all players | `mysticessentials.mail.send.all` |
| `/mail delete <id>` | Delete a mail item | `mysticessentials.mail.use` |
| `/mail clear` | Clear your inbox | `mysticessentials.mail.use` |

**Item & command rewards.** In the Mail UI's Compose pane, players holding `mysticessentials.mail.attach` can attach up to 9 item stacks picked from their own inventory — the items are **removed from the sender at send time** (server-authoritative, no duplication) and escrowed on the mail. Recipients see the rewards as item icons and **Claim** them once (items go to the inventory if there is room, then the mail is marked claimed). Admins with `mysticessentials.mail.announce` use the standalone **Mail Admin Center** (`/mailadmin`) to broadcast reward mail carrying both item rewards (free, from the item registry) and reward commands run as console on claim (`{player}` / `{uuid}` placeholders). The audience can be **all online players**, **all known players** (offline included), **players with a permission node**, or **a single player** (online or offline). Every send is logged to a **sent-announcement history** that can be re-sent to its original audience with one click.

### Chat
| Command | Description | Permission |
|---|---|---|
| `/msg <player> <message>` | Private message (aliases `/tell`, `/w`, `/whisper`) | — |
| `/reply <message>` | Reply to your last PM (alias `/r`) | — |
| `/channel` | Open the channel browser/menu (alias `/ch`) | — |
| `/channel <name>` or `/channel switch <name> [password]` | Switch the channel you speak in | — |
| `/channel join <name> [password]` | Listen to a channel you can access | — |
| `/channel leave <name>` | Stop listening to a channel | — |
| `/channel temp <id> [password\|-] [prefix\|-] [alias1,alias2\|-] [permission]` | Create a temporary channel | `mysticessentials.chat.channel.create.temp` |
| `/channel manage` | Open the manager UI for your temporary channel | — |
| Configured aliases, e.g. `/g`, `/global`, `/sc`, `/schat`, `/staffchat` | Quickly switch speaking channel | — |

### Announcements & AFK
| Command | Description | Permission |
|---|---|---|
| `/broadcast <message>` | Broadcast with the configurable `broadcastPrefix` (alias `/bc`) | `mysticessentials.announcement.broadcast` |
| `/alert <message>` | Broadcast with the attention-grabbing `alertPrefix` | `mysticessentials.announcement.alert` |
| `/afk [reason]` | Toggle AFK | `mysticessentials.afk.use` |
| `/afkzone pos1` / `/afkzone pos2` | Select a reward-zone corner at your position | `mysticessentials.afk.zone.admin` |
| `/afkzone create <name>` | Create an AFK reward zone from the selected corners | `mysticessentials.afk.zone.admin` |
| `/afkzone delete <name>` | Delete an AFK reward zone | `mysticessentials.afk.zone.admin` |
| `/afkzone permission <name> <node\|->` | Require a permission to use a zone (`-` clears) | `mysticessentials.afk.zone.admin` |
| `/afkzone list` | List AFK reward zones | `mysticessentials.afk.zone.admin` |
| `/afkzone check` | Show which zone you are standing in | `mysticessentials.afk.zone.admin` |

### Kits
| Command | Description | Permission |
|---|---|---|
| `/kit` or `/kit list` | List the kits you can claim (alias `/kits`) | `mysticessentials.kit.use` |
| `/kit <name>` | Claim a kit (cooldowns, playtime gating, and cost apply) | `mysticessentials.kit.use` (+ `mysticessentials.kit.<name>` when required) |
| `/kit give <player> <kit>` | Give a kit, ignoring gating | `mysticessentials.kit.admin` |

### Flight
| Command | Description | Permission |
|---|---|---|
| `/fly` | Toggle flight (paid per minute when `paidFlight` is on) | `mysticessentials.fly.use` |
| `/fly <player>` | Toggle flight for another player | `mysticessentials.fly.others` |

### Inventory
| Command | Description | Permission |
|---|---|---|
| `/clearinventory` | Clear your inventory (a backup snapshot is taken first; alias `/clearinv`) | `mysticessentials.inventory.clear` |
| `/clearinventory <player>` | Clear another player's inventory | `mysticessentials.inventory.clear.others` |
| `/clearinventory all` | Clear everyone's inventory (players with `mysticessentials.inventory.protect` are skipped) | `mysticessentials.inventory.clear.all` |
| `/inventory clear [player\|all]` | Same as above (alias `/inv`) | as above |
| `/inventory restore <player>` | Open the Snapshot Restore UI (join/leave/death/timed/manual snapshots with timestamps) | `mysticessentials.inventory.restore` |

### Nicknames
| Command | Description | Permission |
|---|---|---|
| `/nick` | Open the Nickname UI with colour options (alias `/nickname`) | `mysticessentials.nick.use` |
| `/nick <name>` | Set your nickname | `mysticessentials.nick.use` (+ `.color` for colour codes) |
| `/nick reset` | Remove your nickname | `mysticessentials.nick.use` |
| `/nick <player> <name>` | Set another player's nickname | `mysticessentials.nick.others` |

### Patch Notes

A searchable, categorised changelog in a two-pane Custom UI: a scrollable patch
list on the left, the selected patch's colour-coded content on the right with
category filter buttons. Patches are authored as JSON files with Markdown-style
bodies in `modules/patchnotes/patches/*.json` (two examples are generated on
first startup); per-player read state drives unread badges and the join
notification. Enabled by default. The open command and its aliases
(`patches`, `updates`, `changelog`) are configurable in
`modules/patchnotes/config.json`.

| Command | Description | Permission |
|---|---|---|
| `/patchnotes` | Open the Patch Notes UI | `mysticessentials.patchnotes.view` |
| `/patchnotes open [player]` | Open for yourself, or another player | `mysticessentials.patchnotes.view` / `.open.others` |
| `/patchnotes markread [player]` | Mark all notes read | `mysticessentials.patchnotes.view` / `.markread.others` |
| `/patchnotes list` | List patch notes in chat | `mysticessentials.patchnotes.view` |
| `/patchnotes reload` | Reload patches and config from disk | `mysticessentials.patchnotes.reload` |

Each patch file carries metadata (`id`, `title`, `version`, `date`, `author`,
`pinned`, `priority`, `showOnLogin`, `tags`) plus `sections[]`, each tagged with
a category `type` (`additions`/`fixes`/`changes`/`removals`) and a Markdown-subset
`body`. The body supports `#`/`##` headers, `-`/`+` bullet lines (coloured by
category — additions green, removals red), and blank-line spacing; inline
`**bold**`, `*italic*`, `` `code` ``, and `[label](target)` markers are shown as
their plain text (Hytale 0.5.6 Labels are plain-text only).

### Tutorial

Cinematic (machinima) and UI-page tutorials with a first-join flow. **Disabled
by default** — set `"tutorial": true` in the main config's modules map *and*
`"enabled": true` in `modules/tutorial/config.json`. Definitions live in
`modules/tutorial/tutorials/*.json`, UI pages in `modules/tutorial/pages/*.json`
(defaults are generated on first enable). `mysticessentials.tutorial.admin`
grants every tutorial node.

| Command | Description | Permission |
|---|---|---|
| `/tutorial list` | List tutorials | `mysticessentials.tutorial.list` |
| `/tutorial info <tutorial>` | Tutorial details | `mysticessentials.tutorial.info` |
| `/tutorial play <tutorial> [player] [--force]` | Start a tutorial (shortcut: `/tutorial <tutorial> [player]`) | `mysticessentials.tutorial.play` / `.play.others` |
| `/tutorial stop [player]` | Stop and restore the player | `mysticessentials.tutorial.stop` / `.stop.others` |
| `/tutorial skip [player]` | Skip the running tutorial | `mysticessentials.tutorial.skip` / `.skip.others` |
| `/tutorial reset <tutorial> <player>` | Clear a completion (offline OK) | `mysticessentials.tutorial.reset` |
| `/tutorial complete <tutorial> <player>` | Mark completed (offline OK) | `mysticessentials.tutorial.complete` |
| `/tutorial status [player]` | Session + completion overview | `mysticessentials.tutorial.status` / `.status.others` |
| `/tutorial page <page> [player]` | Open a tutorial UI page | `mysticessentials.tutorial.page` / `.page.others` |
| `/tutorial reload` | Reload config, tutorials, and pages | `mysticessentials.tutorial.reload` |
| `/tutorial debug <on\|off>` | Toggle debug logging | `mysticessentials.tutorial.debug` |
| `/tutorial scene <list\|info\|import\|play\|stop>` | Import and test cinematic scenes | `mysticessentials.tutorial.scene` |

Players are frozen/protected during tutorials per config, and always restored —
on completion, stop, skip, failure, timeout (failsafe), disconnect, and
shutdown. Page buttons support `close`, `page`, `command`, `console_command`,
`tutorial`, `message`, `url`, and `teleport` actions.

**Cinematic scenes.** Record a camera scene in the in-game machinima editor (it
saves to `%APPDATA%/Hytale/UserData/Scenes/<Name>.json`), then make it playable
server-side: drop the exported `.json` into
`mods/MysticEssentials/modules/tutorial/scenes/` (or its `import/` sub-folder,
then run `/tutorial scene import`). Playback is **server-driven** (`sceneProvider.type
= camera`): the server samples the scene's camera keyframes and steers the player's
camera along the path with the `SetServerCamera` packet — the client does not need
the scene locally. (The `machinima` provider, which sends the machinima packet, is
kept for a future client but is a no-op on 0.5.6, which has no receiver for it.)
A tutorial plays a scene via its `machinima.sceneId`; `machinima.placement` is
`fixed` (play at the scene's recorded world coordinates) or `relocate` (translate
the scene so its origin sits at `machinima.anchor` — `player` position, or explicit
`coords`), which makes one recording reusable at every player's location. Camera
timing/orientation is tuned under `cameraPlayback` in the module config
(`framesPerSecond`, `updateHz`, `positionLerpSpeed`/`rotationLerpSpeed`,
`smoothPath`, and orientation knobs `pitchFromLook`/`invertPitch`/`invertYaw`/
`yawOffsetDegrees`/`pitchOffsetDegrees`). Test any scene live with
`/tutorial scene play <sceneId> [player] [relocate]` and end it with
`/tutorial scene stop`.

### Custom Commands

Owner-defined commands from JSON files in `modules/customcommands/commands/`.
**Disabled by default** — set `"customcommands": true` in the main config's
modules map *and* `"enabled": true` in `modules/customcommands/config.json`.
Safe examples (`rules`, `discord`, `vote`, `store`) are generated on first
startup. `mysticessentials.customcommands.admin` grants every admin node.

| Command | Description | Permission |
|---|---|---|
| `/customcommands list` | All loaded commands with state and usage counts | `mysticessentials.customcommands.list` |
| `/customcommands info <command>` | Full definition details | `mysticessentials.customcommands.info` |
| `/customcommands reload` | Reload config + definitions (applies instantly; added/removed commands are registered/unregistered; Redis syncs it network-wide) | `mysticessentials.customcommands.reload` |
| `/customcommands enable\|disable <command>` | Toggle one command (persisted to its file) | `mysticessentials.customcommands.manage` |
| `/customcommands test <command> [args...]` | Run a command ignoring cooldowns/disabled state | `mysticessentials.customcommands.test` |
| `/customcommands validate` | Re-check every definition and report all issues | `mysticessentials.customcommands.validate` |

Aliases: `/ccmd`, `/customcmd`, `/mecustomcommands`.

Each definition supports: **aliases**; **permission modes** `none` / `single` /
`all` / `any`; **cooldowns** with bypass nodes; **typed arguments** (`string`,
`word`, `number`, `integer`, `boolean`, `player`, `offline_player`, `duration`,
`greedy_string`); **conditions** (`permission`, `world`, `server`,
`placeholder`); and **action chains** — `message`, `command`, `broadcast`,
`delay`, `condition`, `notification` (client toast), `sound` (2D sound event).
`command` actions run as `console`/`server`, `sender`, `arg:<argumentName>`, or
`player:<name>`, and may call other custom commands — guarded by recursion
detection, `safety.maxExecutionDepth`, a shared `safety.maxActionsPerChain`
budget, and the `safety.blockedCommands` list. Placeholders: `{player_name}`,
`{player_uuid}`, `{sender_name}`, `{sender_uuid}`, `{server_name}`,
`{server_online}`, `{arg:name}`, `{cooldown_remaining}`, plus PlaceholderAPI.

Example definition:

```json
{
  "name": "greet",
  "description": "Greets a player.",
  "aliases": ["hello"],
  "permission": { "mode": "single", "node": "myserver.greet" },
  "cooldown": { "seconds": 60 },
  "arguments": [
    { "name": "target", "type": "player", "required": true },
    { "name": "message", "type": "greedy_string", "required": false, "defaultValue": "Welcome!" }
  ],
  "runAs": "console",
  "actions": [
    { "type": "message", "text": "&7You greeted &f{arg:target}&7." },
    { "type": "command", "command": "msg {arg:target} {arg:message}", "runAs": "sender" }
  ]
}
```

Cooldowns and usage stats persist through the storage abstraction (network-wide
on shared SQL); with Redis enabled, cooldown starts and reloads sync across
servers. Optional usage/audit logging writes to `logs/customcommands.log`.

### Player Vaults

Permission-based personal storage with an optional Redis-backed cross-server
safe-save workflow. **Disabled by default** — set `"playervaults": true` in the
main config's modules map *and* `"enabled": true` in
`modules/playervaults/config.json`. Vault contents live in the storage
abstraction (source of truth); Redis is used only for distributed locks, a
short-lived cache, and pub/sub invalidation when cross-server mode is on.

| Command | Description | Permission |
|---|---|---|
| `/playervault` | Open the vault-list dashboard (aliases `/pv`, `/vault`, `/vaults`, `/playervaults`) | `mysticessentials.vaults.command` |
| `/pv <n>` | Open your vault `n` directly | `…command` + `vaults.vault.<n>` |
| `/pv <n> <player>` | Staff-open another player's vault (online or offline) | `mysticessentials.vaults.admin.open` |
| `/pv edit <n>` | Open the metadata editor (name/colour/icon/description) | `mysticessentials.vaults.command.edit` |
| `/pv list [player]` | Open your list, or a target's (staff) | `…command.list` / `…admin.open` |
| `/pv admin unlock <player> <n>` | Force-unlock a stuck vault lock | `mysticessentials.vaults.admin.unlock` |
| `/pv admin restore <player> <n> <backupId>` | Restore a vault from a backup | `mysticessentials.vaults.admin.restore` |
| `/pv admin logs <player>` | View the vault audit log | `mysticessentials.vaults.admin.viewlogs` |
| `/pv reload` | Reload the module config | `mysticessentials.vaults.command.reload` |

Vault count and rows are resolved from the highest `vaults.vault.<n>` /
`vaults.rows.<n>` node a player holds (capped by config `maxVaults` / `maxRows`).
Losing rank never deletes items — anything beyond the current row limit becomes
**inaccessible overflow** that returns when the rank is restored, or that an
admin with `vaults.admin.bypasslimit` can recover. Item movement is
server-authoritative (Deposit/Withdraw), so client input cannot dupe items.

**Cross-server safety:** each edit takes a distributed Redis lock
(`mysticessentials:vaults:lock:<uuid>:<n>`) with a TTL that auto-frees on crash
and renews while the UI is open; every save is version-checked, so a stale
cache or a slow peer can never overwrite newer data — a mismatch is rejected and
kept as a conflict snapshot. With `crossServer.enabled` + `requireRedis` set but
Redis unavailable, the module refuses to enable rather than risk duplication.

---

## Permissions reference

Every node is defined centrally in
`api/Permissions.java` and documented in **[PERMISSIONS.md](PERMISSIONS.md)** —
the complete operator reference, including the dynamic nodes (home/player-warp
limits, per-kit access, per-channel gates).

Numeric limits (homes, player warps) are resolved from the highest `.limit.<n>`
node a player holds, or `.limit.unlimited`.

---

## Message & colour formatting

Every message and config template supports this markup:

| Syntax | Effect |
|---|---|
| `&a` `&c` … `&f` | Legacy colours |
| `&l` `&o` `&n` `&r` | Bold, italic, underline, reset |
| `&#ff8800` or `<#ff8800>` | Hex colour (also 3-digit `#f80`) |
| `<red>` `<gold>` … | Named colours (with closing `</red>`) |
| `<color:#ff0000>` / `<c:#f00>` | Hex/named colour tag |
| `<bold>` `<b>`, `<italic>` `<i>`, `<underlined>` `<u>` | Styles (with closing tags) |
| `<gradient:#7b2cff:#00d4ff>text</gradient>` | Multi-stop gradient |
| `<rainbow>text</rainbow>` | Rainbow |
| `<link:https://…>text</link>` | Clickable link |
| `<lang:some.key>` | Client-translated text |
| `{player_name}`, `{luckperms_prefix}`, `{group}`, `%papi_placeholder%` | Placeholders |

> Hover text is not available — the Hytale 0.5.6 message protocol has no hover
> field (only links).

---

## Configuration

Files live under `mods/MysticEssentials/`:

```
config.json                 Main config (storage, integrations, module toggles)
messages/en_us.json         Core message strings
modules/<module>/config.json   Per-module settings
data/                       Player profiles, module data, cache
logs/
```

**Main `config.json`** toggles storage (`json` / `mysql` / `mariadb` + optional
Redis for cross-server), integrations (LuckPerms / PlaceholderAPI / VaultUnlocked),
and which modules are enabled. New modules added in updates are merged into your
existing config automatically.

Notable per-module settings:
- **teleportation** — `requestExpirySeconds` (pending /tpa lifetime),
  `tpaWarmupSeconds`, `tpaCooldownSeconds`, `backWarmupSeconds`,
  `backCooldownSeconds`, `worldWhitelist`, `worldBlacklist`.
- **mail** — `maxInboxSize` (0 = unlimited; oldest read/claimed mail dropped
  first when full — mail with unclaimed rewards is never evicted),
  `maxMessageLength` (default 2000; 0 = unlimited; longer bodies truncated),
  `notifyUnreadOnJoin`, `allowPlayerItemAttachments`, `maxAttachments` (default
  9), `allowAnnouncementCommands`, `blockedItemIds` (never mailable), `pageSize`,
  `broadcastBatchSize` (default 50 — admin broadcasts deliver in sequential
  batches so a send to thousands of offline players never floods storage).
  Mail bodies are stored as typed; the reading pane shows them as plain text
  (Hytale's Custom UI text elements in 0.5.6 render plain text only — inline
  colour/format markup is not displayable there, so any `&`/`<#hex>` codes are
  stripped for display rather than shown raw).
- **spawn** — `defaultHomeLimit`, `teleportOnFirstJoin`, `teleportOnJoin`.
- **chat** — `defaultFormat`, priority-ordered `formats` (permission-gated),
  per-colour-style permissions, private messaging, channel definitions, temporary
  channels, and configurable channel prefixes/aliases/passwords.
  `/channel` opens the packaged channel browser UI
  (`Common/UI/Custom/MysticEssentials/ChatChannels.ui`).
  Temporary channels are session channels: without Redis they stay open until
  the server is empty or restarts; with Redis enabled they can be restored after
  restart for `temporaryChannelDefaultMinutes` (default `120`) minutes.
- **announcements** — `autoBroadcastEnabled`, `intervalSeconds`, `randomOrder`,
  and `messages`. Message entries may be legacy strings or JSON objects:
  `{"lines":["&7Line one","&fLine two"],"click":{"action":"command","value":"/spawn"}}`.
  Use `action: "link"` with an `https://...` value to open a URL. Individual
  `lines` entries may also be objects with their own `text` and `click`.
- **afk** — `autoAfkSeconds`, plus a `rewards` block for the AFK Rewards
  submodule: payout interval, session/daily money caps, combat lockout, and
  named reward `zones` managed in-game with `/afkzone`. Movement always clears
  a player's AFK state unless they stay inside a reward zone
  (`stayAfkWhileMovingInZone`), so AFK pools that push players around keep
  working. Set `requireInZone: true` to pay rewards only inside zones.

  When zones exist and `teleportToZoneOnAfk` is on (default), `/afk` saves the
  player's location and teleports them to a random floor spot inside a
  permitted zone (same-world zones preferred; the zone floor is the lowest
  corner Y). With more than one permitted zone, `/afk` instead opens a zone
  picker UI (with a "Just AFK Here" option that skips the teleport); going AFK
  happens on selection. Leaving AFK — `/afk` again, any activity, or walking out of the
  zone — teleports them back to the saved location. The saved location is
  persisted in the player profile, so if the server restarts they are sent
  back on their next join. Each zone may also set a `permission` node
  (`/afkzone permission <name> <node|->`) restricting who can teleport in,
  stay AFK inside, and earn there, plus its own `rewardPool` that overrides
  the global pool for players AFK inside it — e.g. a VIP zone with better loot.

  Rewards come from a weighted `rewardPool`; each interval one entry is rolled
  per AFK player. When the pool is empty, the flat `amountPerInterval` money
  payout is used instead. Entry types are `money` (deposits `amount`, counts
  against `maxSessionReward`/`maxDailyReward`), `item` (gives `quantity` of
  `itemId`, overflow drops), and `command` (runs `command` as the console with
  `{player}`/`{uuid}` placeholders). `weight` sets relative odds, an optional
  `message` overrides the default reward message, and `maxRollsPerDay` caps
  total rolls per player per day (`0` = unlimited). Example:

  ```json
  "rewardPool": [
    { "type": "money",   "weight": 70, "amount": 5 },
    { "type": "item",    "weight": 25, "itemId": "Food_Apple", "quantity": 2 },
    { "type": "command", "weight": 5,  "command": "give {player} Ingredient_Gem_Diamond 1",
      "message": "&dRare AFK reward: &fa diamond!" }
  ]
  ```
- **greetings** — MOTD lines, first-join message, and optional join/leave broadcasts.
- **announcements** — also `broadcastPrefix` and `alertPrefix` for `/broadcast`
  and `/alert`.
- **kits** — kit definitions (`items`, `cooldownSeconds` with `-1` = one-time,
  `requiredOnlineSeconds`, `requirePermission`, `cost`, `description`) and
  `firstJoinKit` granted automatically on first join.
- **flight** — `paidFlight`, `costPerMinute`, and fly-speed multipliers.
- **inventory** — snapshot triggers (`snapshotOnJoin`/`Leave`/`Death`),
  `timedSnapshotMinutes`, and `maxSnapshotsPerPlayer`.
- **nick** — nickname length limits, `blockedNames`, and the `nickMarker`
  prefix shown before nicknames in chat.
- **chat channels** — each channel supports `groupFormats`, a map of LuckPerms
  primary group → chat format, overriding the channel `format` per rank.

Config files are **versioned**: each carries a `configVersion` and is migrated
(plus merged with new default keys) automatically on load.

---

## Integrations

- **LuckPerms** — permission checks, primary group, and `{luckperms_prefix}` /
  `{luckperms_suffix}` in chat and messages.
- **PlaceholderAPI** — resolves `%…%` placeholders, and exposes Mystic's own as
  `%mystic_<name>%` (e.g. `%mystic_player_name%`, `%mystic_group%`).
- **VaultUnlocked** — economy for paid warps, teleport costs, paid flight, kit
  costs, and AFK rewards. Requires an economy provider mod; without one, costs
  are free and payouts no-op.
- **MysticVanish** — vanished players are hidden from the TPA UI and player
  suggestions, treated as offline for `/msg` and `/tpa`, and join/leave/AFK
  announcements stay silent for them. Auto-detected; drop
  `MysticVanish-1.0.0.jar` in `mods/`.

Cross-server (multiple servers on one network) broadcasts and private messages
work when Redis is enabled in `config.json`.

Chat channel cross-server delivery also requires Redis.

---

## For developers

Mystic Essentials exposes a stable, service-based API. See
[DEVELOPER_NOTES.md](DEVELOPER_NOTES.md) for the architecture, the discovered
Hytale API surface, and how to build addons against
`MysticEssentialsProvider.get()`.

## Building

```bash
./gradlew shadowJar     # -> build/libs/MysticEssentials-1.0.0.jar
./gradlew deployMod     # build + copy to the project-local server mods folder
```

Requires JDK 25 (configured via the Gradle toolchain).
