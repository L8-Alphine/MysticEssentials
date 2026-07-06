# Mystic Essentials — Developer Notes

Status of the **first-milestone foundation**: an API-first, modular server
utility framework for Hytale. This document records the **real Hytale API** that
was discovered from the cached server jar and used as the source of truth, plus
the remaining adapter TODOs where a Hytale call still needs verification before
it can be enabled.

The mod **compiles and packages** (`./gradlew shadowJar` → `build/libs/MysticEssentials-1.0.0.jar`)
against `com.hypixel.hytale:Server:0.5.6`.

---

## How the API was discovered

The Hytale API is **not guessed**. It was read directly from the compiled server
classes with `javap`:

- Cached artifact: `~/.gradle/caches/.../com.hypixel.hytale/Server/0.5.6/Server-0.5.6.jar`
  (identical surface to `%APPDATA%/Hytale/install/.../Server/HytaleServer.jar`).
- Inspected with `jar tf` (class listing) and `javap -classpath <jar> -public <class>`.

The server is **ECS-based** (components/systems, `Ref<EntityStore>`,
`ComponentAccessor`). No Bukkit/Spigot/Sponge/Fabric API is used anywhere.

## Verified Hytale API in use (source of truth)

| Concern | Real API used |
|---|---|
| Plugin lifecycle | `server.core.plugin.JavaPlugin` → override `setup()` / `start()` / `shutdown()`; base gives `getLogger()`, `getDataDirectory()`, `getCommandRegistry()`, `getEventRegistry()`, `getTaskRegistry()`, `getName()`, `getBasePermission()` |
| Logging | `HytaleLogger` (flogger): `getLogger().at(Level).log(msg)` |
| Commands | `CommandRegistry.registerCommand(AbstractCommand)`; subclass `AbstractCommand`, override `execute(CommandContext)`; `requirePermission`, `addAliases` |
| Command sender/context | `CommandContext.sendMessage(Message)`, `isPlayer()`, `sender()`, `senderAs(Class)`, `getInputString()`; `CommandSender.getUsername()/getUuid()/hasPermission(String)` |
| Messages | `server.core.Message.parse/raw/empty`; chainable `.color/.param`; sent via `IMessageReceiver.sendMessage(Message)` |
| Players | `server.core.universe.PlayerRef` (implements `CommandSender`, `IMessageReceiver`): `getUuid`, `getUsername`, `sendMessage`, `hasPermission`, `getTransform`, `getWorldUuid`, `getReference`, `referToServer` (cross-server) |
| Player registry | `Universe.get()`: `getPlayers`, `getPlayer(UUID)`, `getPlayerByUsername(name, NameMatching)`, `getWorld(name/uuid)`, `getWorlds` |
| Worlds | `universe.world.World.getName()`, `World.execute(Runnable)` (run on world thread) |
| Location/pos | `math.vector.Location` (world + joml `Vector3d` + `Rotation3f`), `math.vector.Transform` |
| Events | `getEventRegistry().register(Class, Consumer)`; `PlayerConnectEvent` / `PlayerDisconnectEvent` (both `Void`-keyed, expose `getPlayerRef()`); `PlayerChatEvent` (async, cancellable) |
| JSON | Gson (`com.google.gson.*`, bundled on server classpath) |

Everything Hytale-specific is funneled through the **`platform`** package
(`HytalePlatform`, `Conversions`, `command/MysticCommand`,
`command/MysticCommandSender`) so the discovered-API surface is centralized and
easy to re-verify when the server version changes.

## Architecture (as built)

```
MysticessentialsPlugin (Hytale entry) → MysticCore (non-disableable) implements MysticEssentialsAPI
  Core services: ConfigManager, StorageService(+JsonStorageProvider, SQL/Redis skeletons),
    PlayerProfileService, MessageService(+ColorPipeline), PermissionService(LuckPerms),
    PlaceholderService(PlaceholderAPI), EconomyService(VaultUnlocked), TeleportService,
    SchedulerService, CooldownService, EventBus, RedisBridge
  Modules (config-toggled): teleportation, spawn(+homes), warps(+player warps),
    mail, announcements, afk, chat
  Public API: MysticEssentialsProvider.get() → MysticEssentialsAPI (service-based)
```

- **Core cannot be disabled.** Modules are toggled in `mods/MysticEssentials/config.json`.
- Modules communicate through **services and the EventBus**, not direct coupling.
- Storage is **provider-abstracted**; JSON is implemented, SQL/Redis are clean skeletons.
- All paths resolve through **`PathManager`** — no hardcoded paths.

## What actually works now

- Full boot pipeline with logging (config → storage → integrations → profiles → modules).
- Default `config.json` and per-module config/message generation on first run.
- Player profile load on connect / save+evict on disconnect (real events).
- Homes, player warps (persisted in profile module-data), server warps and mail
  (persisted via the storage service), announcements + auto-broadcast rotation,
  manual AFK, private messaging + reply + social spy + channel switching.
- **Integrations**: LuckPerms (groups/prefix/suffix), PlaceholderAPI (consume +
  expose `%mystic_...%`), VaultUnlocked economy — all guarded/optional.
- **Chat module**: root chat event pipeline plus focused submodules for private
  messaging, channel routing, and glyph replacement. Channels track both the
  channel a player speaks in and the channels they listen to, filter
  `PlayerChatEvent` targets from that state plus permissions, support
  configurable aliases/prefixes/passwords, support temporary channels, and
  publish cross-server envelopes over Redis when a channel is `crossServer=true`.
- **Chat formatting**: rank/permission formats via `PlayerChatEvent` with
  per-permission colour gating and glyph replacement before formatting.
- **Auto-AFK**: idle detection from movement (position polling), clicks
  (`PlayerMouseButtonEvent`), and chat, with a bypass permission and AFK
  announcements. Manual `/afk` too.
- Teleport **fully working**: cost → cooldown → back-location → warmup (with
  movement cancellation) → the real ECS move, run on the player's world thread
  (see below).

### Teleport threading & ECS move (implemented)

Hytale is multi-threaded with **one thread per world**, so all entity/component
access happens on that world's thread. The implementation mirrors Hytale's own
`AbstractPlayerCommand`:

1. `Ref<EntityStore> ref = player.getReference()` → `Store<EntityStore> store = ref.getStore()`
   → `World current = ((EntityStore) store.getExternalData()).getWorld()`.
2. Dispatch onto that world via `current.execute(...)` (`HytalePlatform.runOnEntityThread`).
3. On the world thread: `store.ensureAndGetComponent(ref, PendingTeleport.getComponentType())`
   then `pending.queueTeleport(Teleport.createForPlayer(destWorld, transform))`. A Hytale
   system applies it on the tick and handles cross-world moves, chunk loading, and
   velocity reset. `Teleport.setOnComplete(future)` drives our result future.

`Rotation3f` components are `(pitch, yaw, roll)` (verified: `x()==pitch()`,
`y()==yaw()`, `z()==roll()`); `Conversions` captures/builds rotation exactly.

Warmup cancellation: movement via position polling (0.2-block threshold) and
damage via `DamageDataComponent.getLastDamageTime()` read on the entity's world
thread (baseline captured at start; any later instant cancels; `Instant.MIN` is
the never-damaged baseline so a first hit still cancels). Also cancels on
disconnect/world change.

Any future feature that touches a player's components should use
`HytalePlatform.runOnEntityThread(player, (store, ref, world) -> ...)` — never
read/write components off the world thread.

## Remaining adapter TODOs

None. Every feature in the design is implemented; `grep -r "TODO("` over `src`
returns nothing.

### Message & colouring (implemented)

`MysticText` builds a coloured {@code Message} tree from markup. Key facts
discovered from the jar:

- `Message.parse(String)` is a **JSON reader** (via `RawJsonReader` + codec),
  **not** a markup parser — do not pass colour codes to it.
- The renderable model is a tree of `FormattedMessage` nodes with fields
  `rawText`, `children[]`, `color` (a `#RRGGBB` string — regex
  `^\s*#([0-9a-fA-F]{3}){1,2}$`, produced by `ColorParseUtil`), `bold`,
  `italic`, `monospace`, `underlined`, `link`. There is **no** strikethrough or
  obfuscated field.
- `Message.join(...)` is literally `new Message()` (empty root) + `insertAll(...)`
  — so `MysticText` uses the same empty-root + children shape.

`MysticText` supports legacy (`&a`, `&l`/`&o`/`&n`/`&r`; `&m`/`&k` dropped — no
field), hex (`&#rrggbb`, `<#rrggbb>`, 3-digit), MiniMessage-style tags
(`<red>`, `<color:#hex>`, `<bold>`/`<b>`, `<italic>`/`<i>`, `<underlined>`/`<u>`,
`<reset>`, closing `</…>`), `<gradient:#a:#b[:#c…]>…</gradient>`,
`<rainbow>…</rainbow>` (interpolated per character), `<link:target>…</link>`
(protocol `link` field), and `<lang:key>` (client translation via `messageId`).
Colours are set explicitly per segment, so rendering does not depend on
client-side markup. `underlined` is set via the public
`FormattedMessage.underlined` field (no `Message` setter).

**Hover is not supported** — the 0.5.6 `FormattedMessage` has no hover field, only
`link`. Only `link` interactivity is representable.

**Live-verified** on the real 0.5.6 server (`F:\Hytale Servers\0.5.6\Server`):
the mod loads with no errors, generates configs, and — with the server's
installed integrations — logs `Permission integration: LuckPerms connected`,
`Placeholder integration: PlaceholderAPI connected`, and registers the
`%mystic_…%` expansion. All 7 modules enable in dependency order.

### Offline mail (implemented)

Hytale's `PlayerStorage` is keyed only by UUID (`load(UUID)`, `getPlayers()` →
`Set<UUID>`) with **no name index**, so Mystic maintains its own: on every
connect, `PlayerProfileService` records `username → UUID` in memory and in the
`usernames` storage namespace. `resolveUuid(name)` checks online players, the
in-memory index, then storage. `/mail send <player>` to an offline (but
previously-seen) player resolves the UUID this way and writes to the recipient's
stored inbox (keyed by UUID), so it works while they are offline. On shared SQL
storage this is network-wide; on per-server JSON it is per-server.

### Redis (implemented)

`RedisBridge` provides a **cache** (`cacheGet`/`cacheSet` with TTL) and a
**pub/sub** bus (`publish`/`subscribe`) backed by **Jedis** (netty-free, so no
clash with the server's bundled netty; shaded with commons-pool2). Redis is a
cache/message layer, never the primary datastore.

- Channels and cache keys are namespaced by `networkId`; a single pattern
  subscription (`<networkId>:ch:*`) feeds a dynamic handler map. Every message
  carries the origin `serverId` so a server ignores its own echoes. The
  subscriber thread reconnects on drop.
- Fails safe: if Redis is disabled or unreachable at start, `isEnabled()` is
  false and every cross-server feature degrades to local-only.

**Cross-server consumers wired:**
- **Broadcasts** — `AnnouncementModule.broadcast` shows locally and publishes to
  `broadcast`; other servers receive and show it. Auto-broadcast rotation stays
  per-server (local only).
- **Private messages** — `/msg` and `/reply` deliver locally when the target is
  on this server, otherwise relay over the `pm` channel (by name for `/msg`, by
  UUID for `/reply`); whichever server has the recipient delivers it, with
  social-spy and reply-target wiring on that side.

### SQL storage (implemented)

`SqlStorageProvider` backs `storage.provider = "mysql"` or `"mariadb"` with a
**HikariCP** pool. Documents live in one table, shared model with the JSON
provider so modules are portable:

```sql
CREATE TABLE mystic_documents (
  namespace VARCHAR(128) NOT NULL,
  id        VARCHAR(128) NOT NULL,
  data      LONGTEXT     NOT NULL,
  PRIMARY KEY (namespace, id));
```

Reads/writes run on a dedicated pool-sized executor (`INSERT ... ON DUPLICATE KEY
UPDATE` for upserts). HikariCP, the MariaDB driver, the MySQL Connector/J driver,
and slf4j-api are **shaded into the mod jar** (no relocation — Hytale gives each
plugin an isolated `PluginClassLoader`); protobuf is excluded from the MySQL
driver. The driver class is set explicitly per flavour, so JDBC auto-discovery is
not relied on. If the DB is unreachable at start, the Core logs and **falls back
to JSON** so the server still boots. Configure host/port/db/credentials/poolSize
under `storage.mysql` in `config.json`.

### Integrations (wired, verified against the dependency jars)

- **LuckPerms** (`net.luckperms.api`): `LuckPermsProvider.get()` on start. Permission checks go through `PlayerRef.hasPermission` (the LuckPerms platform is Hytale's permission provider); `primaryGroup`, `prefix`, `suffix` read `User.getCachedData().getMetaData()`. Falls back cleanly when absent.
- **PlaceholderAPI-Hytale** (`at.helpch.placeholderapi`): external `%...%` resolved via `PlaceholderAPI.setPlaceholders(PlayerRef, String)`; Mystic exposes its own placeholders as `%mystic_<name>%` through a registered `PlaceholderExpansion` (`MysticExpansion`). Internal `{...}` placeholders always work.
- **VaultUnlocked** (Vault2, `net.milkbowl.vault2.economy.Economy` via `net.cfh.vault.VaultUnlocked.economy()`): balance/has/withdraw/deposit/format with `(pluginName, uuid, BigDecimal)` and `EconomyResponse.transactionSuccess()`; lazy account creation. No economy → safe no-op success.

All three are `compileOnly` and guarded (provider lookup in try/catch on start; call sites gated on availability), so a server missing any of them loads fine.

### Chat formatting (wired)

`ChatModule` registers an **async** `PlayerChatEvent` handler
(`registerAsyncGlobal`, via `HytalePlatform.onAsyncEvent`) that runs the original
chat pipeline: glyph replacement, colour permission filtering, channel routing,
and formatter installation. The formatter resolves the highest-priority
permission-gated format or channel format from `modules/chat/config.json`,
expands placeholders on the template only, then splices in the player's message
(so user text is never placeholder-expanded). Colour styles the sender lacks
permission for (legacy / hex / gradient / rainbow / MiniMessage / links) are
stripped from their message first (`ChatColors`).

`ChatGlyphSubModule` generates `modules/chat/glyphs.json` from the bundled
catalog at `Common/Resources/MysticEssentials/Chat/Glyphs/glyphs.json`,
registers each PNG as a Hytale CommonAsset, and maps aliases/raw symbols to
Private Use Area codepoints. That path mirrors the real `Assets.zip`
`Common/Resources/...` layout. The included `font_binding.example.json` is the
data handoff for the client text/font atlas binding once Hytale's final glyph
binding format is verified.

For full coverage requests, Mystic does **not** generate a PNG for every Unicode
character. Instead, `Common/Resources/MysticEssentials/Chat/Unicode` ships:

- `emoji-sequences.json`, generated from Unicode's official
  `emoji-test.txt` sample column (Emoji 17.0, 5,225 chart rows / 3,944
  fully-qualified rows).
- `unicode-symbol-policy.json`, documenting the all-valid-Unicode scalar policy
  and symbol-category ranges for permission/audit/future font coverage work.

The sanitizer preserves emoji-critical format controls (`U+200D`, `U+FE0E`,
`U+FE0F`, and emoji tag codepoints) while still removing unsafe control
characters. This keeps ZWJ family/profession emojis, variation-selector emojis,
and tag-sequence flags intact.

### Custom UI system (verified, template-row based)

All UIs live in `Common/UI/Custom/MysticEssentials/*.ui` and are opened with
`HytalePlatform.openPage(PlayerRef, CustomUIPage)` (resolves the `Player`
entity on its world thread and calls `PageManager.openCustomPage`). Pages
extend `platform.ui.MysticPage`, which centralizes payload parsing and the
reopen-to-refresh pattern.

Lists follow the builtin `WarpListPage` pattern verified from the 0.5.6 server
jar and `Assets.zip`:

- The page `.ui` declares an empty scrolling container
  (`Group #WarpList { LayoutMode: TopScrolling; }`).
- A separate row-template `.ui` file is appended once per entry:
  `cmd.append("#WarpList", "MysticEssentials/WarpRow.ui")`.
- Appended rows are addressed by index:
  `cmd.set("#WarpList[0] #Name.Text", ...)`, and event bindings target either
  the row root (`#WarpList[0]`) or a child (`#WarpList[0] #TpaButton`).
- `cmd.appendInline(container, snippet)` / `cmd.clear(container)` also exist.

Pages: `Warps.ui` + `WarpRow.ui` (browse; admins get a Manage button on the
selected warp) and `WarpAdmin.ui` (separate admin editor with a visibility
dropdown), `PlayerWarps.ui` + `PlayerWarpRow.ui` (browse all player
warps), `PlayerWarpManager.ui` (manage own player warps), `Homes.ui` +
`HomeRow.ui`, `TeleportRequests.ui` + `TpaPlayerRow.ui`/`TpaRequestRow.ui`,
`ChatChannels.ui` + `ChannelRow.ui`, `TempChannel.ui` (create) and
`TempChannelManage.ui` (manage own temp channel). Header search fields use
`$C.@HeaderSearch` with a `ValueChanged` binding on `#SearchInput`.

GOTCHA: `ResourceCommonAsset.of(clazz, name, path)` resolves the resource from
its SECOND argument via `Class.getResourceAsStream`, so pass the absolute
`"/Common/..."` form there; the third argument is only stored as the asset
path. (This was the cause of the historic "Missing bundled glyph asset"
warnings.)

### Player warp storage

Player warps are stored module-level in
`data/modules/warps/playerwarps.json` as one globally-named map
(name → `Warp`, with `owner`/`ownerName`), NOT in per-player profiles. This is
what makes the browse-all Player Warps UI and `/pwarp <name>` teleports
possible; names are globally unique.

Temporary channels are intentionally session-scoped when Redis is unavailable:
they have no local countdown and are cleared when the last player leaves or the
server restarts. When Redis is enabled, each temporary channel and the channel
index are cached for `temporaryChannelDefaultMinutes` (default `120`) minutes so
a restarted server can restore them while the Redis TTL is still alive.

## Building

```bash
./gradlew shadowJar        # -> build/libs/MysticEssentials-1.0.0.jar
./gradlew deployMod        # builds + copies to .hytale-server/mods
```

Requires JDK 25 (configured via the Gradle toolchain).
