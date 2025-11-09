# MysticEssentials
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Platform](https://img.shields.io/badge/Platforms-NeoForge%20%7C%20Fabric-green.svg)]()
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21%2B-orange.svg)]()
[![Status](https://img.shields.io/badge/Build-Stable-success.svg)]()
[![CodeFactor](https://www.codefactor.io/repository/github/l8-alphine/mysticessentials/badge/master)](https://www.codefactor.io/repository/github/l8-alphine/mysticessentials/overview/master)
![GitHub Release](https://img.shields.io/github/v/release/l8-alphine/mysticessentials?include_prereleases&sort=date&display_name=tag)

> **MysticEssentials** is a lightweight, server-side only utility mod inspired by *EssentialsX*, designed for **NeoForge** and **Fabric** environments.  
> Built for the **MysticHorizonsMC** network and other dedicated server operators who want powerful, configurable commands without bloat.

---

## 📦 Overview
MysticEssentials provides a complete suite of administrative and player-utility commands built natively for modern mod loaders.  
Every system is modular, permission-aware, and optimized for large servers and modded environments.

---

## ✨ Features

### General
- `/help` — Paginated, clickable help menu for all registered commands.
- `/modlist` — View installed mods (with filtering and optional paste export).
- `/spawn` — Instantly teleport players to the global spawn point.
- `/setspawn` — Define or move the global spawn point.

### Player Teleportation
- `/tpa`, `/tpaccept`, `/tpdeny` — Request-based teleportation system.
- `/back` — Return to your previous location or last death point.
- `/warp`, `/setwarp`, `/delwarp` — Warp management system.
- `/home`, `/sethome`, `/delhome` — Personal homes with configurable limits.

### AFK & Activity
- `/afk` — Toggle AFK mode manually.
- Automatic AFK detection.
- **AFK Pools** — configurable regions where movement keeps players active.
- Persistent AFK data between restarts.

### Moderation
- `/warn` — Issue warnings to players, stored persistently.
- `/mute`, `/unmute`, `/kick`, `/ban`, `/tempban` *(planned)* — Full moderation suite.
- Audit logging for staff actions.

### Admin Utilities
- `/gm` — Change game mode.
- `/heal`, `/feed`, `/fly`, `/god` *(planned)*.
- `/nick` — Change or reset player nickname.
- `/broadcast`, `/msg`, `/reply` — Server messaging utilities.

### System & Integration
- Platform-agnostic `ModInfoService` for NeoForge/Fabric.
- `PasteService` abstraction for log/modlist sharing.
- JSON-based persistent data stores:
    - `PlayerDataStore`
    - `HomesStore`
    - `AfkPoolsStore`
    - `PunishStore`
    - `AuditLogStore`
- Fully async and thread-safe operations.

---

## ⚙️ Configuration
All configuration files are located under:
```
config/mysticessentials/
```

Includes:
- `main.json` — Core settings (teleport cooldowns, homes limit, AFK timeout).
- `permissions.json` — Optional permission overrides.
- `afkpools.json` — Defined AFK pools and triggers.

---

## 🧩 Permissions
MysticEssentials uses a flexible permission system modeled after Bukkit-style nodes:

| Node | Description |
|------|--------------|
| `mysticessentials.help` | Access to `/help` |
| `mysticessentials.spawn` | Use `/spawn` |
| `mysticessentials.home` | Use `/home`, `/sethome`, `/delhome` |
| `mysticessentials.warp` | Use `/warp`, `/setwarp`, `/delwarp` |
| `mysticessentials.tp` | Teleport requests `/tpa`, `/tpaccept`, `/tpdeny` |
| `mysticessentials.afk` | Toggle AFK mode |
| `mysticessentials.admin` | Access to admin utilities |
| `mysticessentials.modlist` | Use `/modlist` |
| `mysticessentials.warn` | Issue player warnings |

*(Permissions integrate with the mod’s `Perms` and `PermNodes` APIs.)*

---

## 🧠 Technical Details
- **Language:** Java 21+
- **Loaders:** NeoForge, Fabric
- **Config Format:** JSON (Gson)
- **Thread Safety:** All persistent stores use concurrent maps and async IO.
- **Architecture:** Service-based modular design (AfkService, ModInfoService, PasteService, etc.)
- **Interop:** Compatible with other server-side mods (no mixins required for client).

---

## 🔧 Installation
1. Download the latest `.jar` from the [Releases](../../releases) page or Modrinth (coming soon).
2. Place it into your `mods/` directory on either:
    - A **server** running NeoForge or Fabric.
3. Restart your server — config files will be generated automatically.
4. Edit `/config/mysticessentials/` as needed.

---

## 🧪 Development & Contributing
### Requirements
- JDK 21+
- Gradle 8.7+
- Architectury Loom

### Build Instructions
```bash
git clone https://github.com/L8-Alphine/MysticEssentials.git
cd MysticEssentials
./gradlew build
