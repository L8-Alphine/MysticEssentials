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

## Commands

Optional arguments are shown in `[brackets]`, required in `<angle brackets>`.
Player, home, and warp names autocomplete.

### Core
| Command | Description | Permission |
|---|---|---|
| `/mystic [info\|reload]` | Show info or reload configs (aliases `/mysticessentials`, `/me`) | `mysticessentials.reload` (reload) |

### Teleportation
| Command | Description | Permission |
|---|---|---|
| `/tpa [player]` | Request to teleport to a player; no player opens the Teleport Requests UI | `mysticessentials.teleport.tpa` |
| `/tpahere [player]` | Request a player teleport to you; no player opens the UI | `mysticessentials.teleport.tpa` |
| `/tpaccept [player]` | Accept a request (latest, or from a specific player) | `mysticessentials.teleport.tpa` |
| `/tpdeny [player]` | Deny a request (latest, or from a specific player) | `mysticessentials.teleport.tpa` |
| `/tpcancel` | Cancel your outgoing requests | `mysticessentials.teleport.tpa` |
| `/tphere <player>` | Teleport one player to you | `mysticessentials.teleport.tphere` |
| `/tpall` | Teleport every online player to you | `mysticessentials.teleport.tpall` |
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
| `/mail` | Open the Mail UI (inbox, read, delete, compose; admins get a server-wide mail row) | `mysticessentials.mail.use` |
| `/mail inbox` | View your inbox in chat | `mysticessentials.mail.use` |
| `/mail read <id>` | Read a mail item | `mysticessentials.mail.use` |
| `/mail send <player> <message>` | Send mail (online or offline) | `mysticessentials.mail.send` (+ `.send.offline`) |
| `/mail sendall <message>` | Send to all players | `mysticessentials.mail.send.all` |
| `/mail delete <id>` | Delete a mail item | `mysticessentials.mail.use` |
| `/mail clear` | Clear your inbox | `mysticessentials.mail.use` |

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
  `backCooldownSeconds`.
- **mail** — `maxInboxSize` (0 = unlimited; oldest read mail dropped first when
  full), `maxMessageLength` (0 = unlimited; longer bodies truncated),
  `notifyUnreadOnJoin`.
- **spawn** — `defaultHomeLimit`, `teleportOnFirstJoin`, `teleportOnJoin`.
- **chat** — `defaultFormat`, priority-ordered `formats` (permission-gated),
  per-colour-style permissions, private messaging, channel definitions, temporary
  channels, configurable channel prefixes/aliases/passwords, and `glyphs`
  settings. `/channel` opens the packaged channel browser UI
  (`Common/UI/Custom/MysticEssentials/ChatChannels.ui`).
  Temporary channels are session channels: without Redis they stay open until
  the server is empty or restarts; with Redis enabled they can be restored after
  restart for `temporaryChannelDefaultMinutes` (default `120`) minutes.
  `modules/chat/glyphs.json` is generated from the bundled glyph catalog.
- **announcements** — `autoBroadcastEnabled`, `intervalSeconds`, `randomOrder`,
  and `messages`. Message entries may be legacy strings or JSON objects:
  `{"lines":["&7Line one","&fLine two"],"click":{"action":"command","value":"/spawn"}}`.
  Use `action: "link"` with an `https://...` value to open a URL. Individual
  `lines` entries may also be objects with their own `text` and `click`.
- **afk** — `autoAfkSeconds`, plus a `rewards` block (reward zone corners, amount,
  session/daily caps, combat lockout) for the AFK Rewards submodule.
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

Chat channel cross-server delivery also requires Redis. Custom chat glyph PNGs
ship in `Common/Resources/MysticEssentials/Chat/Glyphs/`, mirroring Hytale's
`Assets.zip` layout, and are registered as Hytale CommonAssets. The bundled
`font_binding.example.json` maps private-use codepoints to those PNGs for the
final Hytale text/font atlas binding step.

Emoji and Unicode coverage is broader than the custom PNG set:
`Common/Resources/MysticEssentials/Chat/Unicode/emoji-sequences.json` is
generated from Unicode's official `emoji-test.txt` sample data, while
`unicode-symbol-policy.json` documents the all-valid-Unicode chat policy and
symbol-category ranges. Mystic preserves emoji variation selectors, zero-width
joiners, and emoji tag sequences so complex emojis do not get broken by chat
normalization.

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
