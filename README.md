# MysticEssentials
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Platform](https://img.shields.io/badge/Platforms-NeoForge%20%7C%20Fabric-green.svg)]()
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.x-orange.svg)]()
[![CodeFactor](https://www.codefactor.io/repository/github/l8-alphine/mysticessentials/badge/master)](https://www.codefactor.io/repository/github/l8-alphine/mysticessentials/overview/master)
![GitHub Release](https://img.shields.io/github/v/release/l8-alphine/mysticessentials?include_prereleases&sort=date&display_name=tag)

> **MysticEssentials** is a lightweight, server-side utility mod inspired by *EssentialsX*, designed for **NeoForge** and **Fabric**.  
> Built for the **MysticHorizonsMC** network and other dedicated server operators who want powerful, configurable commands **without bloat**.

---

## 📦 Overview

MysticEssentials provides a complete suite of administrative, quality-of-life, and chat utilities built natively for modern modded servers:

- Modular services (teleports, AFK, chat, placeholders, vaults, etc.)
- Permission-aware and LuckPerms-friendly
- Designed for large modpacks and production networks
- Zero client requirement — server-side only

---

## ✨ Feature Highlights

### 📚 General & Help

- `/mhelp` — Paginated, clickable help menu for all registered MysticEssentials commands.
- `/modlist` — View installed mods with:
  - Optional filtering
  - Paste export via pluggable `PasteService` (e.g. mclo.gs / hastebin style)
- `/spawn` — Teleport players to the global spawn point.
- `/setspawn` — Define or move the global spawn point.

> **Note:** Legacy `/help` is replaced by `/mhelp`.

---

### 🧭 Player Teleportation

- `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny`
  - Request-based teleportation with warmups, cooldowns, and cancel-on-move (configurable).
- `/back`
  - Return to previous location (including last death, if enabled).
- `/warp`, `/setwarp`, `/delwarp`
  - Global warp management with permission-based access.
- `/home`, `/sethome`, `/delhome`, `/homes`
  - Per-player homes with:
    - Group-based limits
    - Permission overrides
    - Fully configurable in `main.json`.

All teleportation goes through a central **Teleport Service**, so warmups, cooldowns, and safety checks (void/bedrock) are consistent across commands.

---

### 💤 AFK & Activity

- `/afk` — Manually toggle AFK.
- Automatic AFK detection based on:
  - Movement
  - Chat
  - Interactions (configurable)
- **AFK Pools**
  - Configurable regions where players stay “active” when inside.
  - Persisted to `afkpools.json` with hot-reload support.
- AFK state is persisted across restarts.
- Optional AFK mention handling (e.g. “pinging” AFK players in chat with a message).

---

### 🧰 Admin Utilities

- `/gm` — Change your or another player’s gamemode.
- `/heal`, `/feed` — Basic maintenance tools.
- `/fly` — Toggle flight.
- `/god` — Damage-immune mode.
- `/nick` — Set or reset nicknames with formatting support (controlled via permissions).
- Inventory tools:
  - `/invshare` — Open a snapshot inventory shared by another player.
  - `/invsee` — View another player’s inventory using safe snapshot containers.
- Messaging:
  - `/msg`, `/reply` — Private messages with formatting.
  - `/broadcast` — Server-wide announcements with custom prefixes and colors.

---

### 🧱 Vaults (Virtual Storage)

> Full docs: `docs/Vaults.md` (see repo)

- Per-player virtual vaults (`/vault`, `/vault <number>`).
- Server-side only, no client mods required.
- Configurable:
  - Default number of vaults
  - Rows/size per vault
  - Permission-based extra vaults (e.g. `messentials.vaults.5`).
- Uses snapshot-safe containers to avoid item loss and supports modded items & NBT.

---

### 🚨 Moderation & Punishments

- `/warn` — Issue persistent player warnings.
- Punishment data stored in `punish_store.json` (backed by `PlayerDataStore`).
- Planned / WIP:
  - `/mute`, `/unmute`
  - `/kick`
  - `/ban`, `/tempban`
- **Audit Logging**
  - Staff actions can be logged to `audit_log.json`.
  - Designed for future integrations (web panels, Discord relays, etc.).

---

### 💬 Chat, Placeholders & LuckPerms

MysticEssentials includes a modern, pluggable **chat module**:

- Formatted chat with support for:
  - Prefix / suffix from **LuckPerms** (via its API).
  - Custom placeholders through an internal placeholder system.
- Supports:
  - Player name, display name, world, coordinates, rank, and more.
  - Rank/tag placeholders via a LuckPerms placeholder bridge.
- Internal placeholder system is used across:
  - Chat formats
  - Messages/config strings
  - Holograms (where enabled)
- Optional mixin to make `http` / `https` links in chat clickable without re-implementing the vanilla chat system.

---

### 🌐 Cross-Server & Redis Integration

For networks (like MysticHorizonsMC), MysticEssentials can integrate with Redis:

- **Redis Chat Bridge**
  - Pluggable `RedisClientAdapter` with a NeoForge/Fabric implementation (Lettuce).
  - Allows global / network chat sync between servers.
- Designed so Redis is **optional**:
  - When enabled, cross-server events (chat, staff actions, etc.) can be propagated.
  - When disabled, everything works as a single-server install.


---

### 🔁 Update Checking

* Built-in **Modrinth update checker**:

  * Runs on startup.
  * Compares local version (loaded from `gradle.properties`/mod metadata) with latest Modrinth release.
  * Logs update notifications to console so you know when a new build is available.

---

## ⚙️ Configuration

All MysticEssentials configuration lives under:

```text
config/mysticessentials/
```

Key files:

* `main.json`

  * Core settings:

    * Teleport warmups/cooldowns
    * Cancel-on-move behavior
    * AFK timeouts
    * Home/warp limits
    * Vault defaults

* `afkpools.json`

  * AFK pool definitions, including:

    * World & coordinates
    * Radius/shape
    * Flags (disable flight, etc.).
* `messages.json` *(name may vary)*

  * All player-facing messages and chat formats.
  * Supports the internal placeholder system.

Config is hot-reload friendly where possible; otherwise reboot is required.

---

## 🧩 Permissions

MysticEssentials uses a Bukkit-style node system, with a **root** of:

> `messentials`

(Older docs may reference `mysticessentials.*`; the current root is `messentials`.)

Common nodes:

| Node                     | Description                                    |
| ------------------------ | ---------------------------------------------- |
| `messentials.help`       | Access to `/mhelp`                             |
| `messentials.spawn`      | Use `/spawn`                                   |
| `messentials.home.use`   | Use `/home`, `/homes`                          |
| `messentials.home.set`   | Use `/sethome`                                 |
| `messentials.home.del`   | Use `/delhome`                                 |
| `messentials.warp.use`   | Use `/warp`                                    |
| `messentials.warp.admin` | Use `/setwarp`, `/delwarp`                     |
| `messentials.tpa`        | Use `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny` |
| `messentials.afk`        | Toggle AFK with `/afk`                         |
| `messentials.vaults.use` | Open vaults via `/vault`                       |
| `messentials.vaults.X`   | Extra vaults (X = number)                      |
| `messentials.modlist`    | Use `/modlist`                                 |
| `messentials.warn`       | Issue warnings                                 |
| `messentials.admin`      | Access core admin tools (`/gm`, `/heal`, etc.) |

Integration with the mod’s `Perms`/`PermNodes` APIs allows you to add or check permissions from other mods or custom code.

---

## 🧠 Technical Details

* **Language:** Java 21+
* **Build System:** Gradle 8.7+
* **Loaders:** NeoForge & Fabric (shared codebase via Architectury)
* **Config Format:** JSON (Gson with HTML escaping disabled where needed)
* **Data Stores:**

  * `PlayerDataStore`
  * `HomesStore`
  * `AfkPoolsStore`
  * `PunishStore`
  * `AuditLogStore`
  * Vault storage
* **Architecture:**

  * Service-oriented (e.g. `AfkService`, `TeleportService`, `ChatModule`, `ModInfoService`, `PasteService`, `RedisClientAdapter`)
  * Fully async IO and concurrent maps for thread safety
* **Interop:**

  * Designed to coexist with other server-side mods (no required client changes).
  * Uses `ModInfoService` abstraction to query installed mods across NeoForge/Fabric.
  * Optional hard-integration with **LuckPerms** for ranks and placeholders.

---

## 🔧 Installation

1. Download the latest `.jar` from:

  * [GitHub Releases](../../releases)
  * [Modrinth](https://modrinth.com/mod/mysticessentials).

> You will need the mod's depends.
> Download links for dependencies can be found on the mod's Modrinth Version Page
2. Drop the jar into your server’s `mods/` folder:

  * NeoForge 1.21.x
  * Fabric 1.21.x
3. Start the server:

  * MysticEssentials will generate its config and data directories under `config/mysticessentials/`.
4. Configure as needed:

  * Edit `main.json`, `permissions.json`, `afkpools.json`, and message formats.
5. Reload or restart the server to apply changes.

---

## 🧪 Development & Contributing

### Requirements

* JDK 21+
* Gradle 8.7+
* Architectury Loom
* NeoForge / Fabric dev environment

### Build Instructions

```bash
git clone https://github.com/L8-Alphine/MysticEssentials.git
cd MysticEssentials
./gradlew build
```

### Contributing

Pull requests and issue reports are welcome:

* Report bugs with logs, config snippets, and reproduction steps.
* Open feature requests with:

  * Use-case / motivation
  * Expected behavior
  * Permission nodes and config ideas

---

## 📜 License

MysticEssentials is licensed under the **GNU GPLv3**.
You are free to use, modify, and redistribute under the terms of that license.