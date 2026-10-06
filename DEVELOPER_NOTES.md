# Mystic Essentials — Developer Notes

Status of the **first-milestone foundation**: an API-first, modular server
utility framework for Hytale. This document records the **real Hytale API** that
was discovered from the cached server jar and used as the source of truth, plus
the remaining adapter TODOs where a Hytale call still needs verification before
it can be enabled.

The mod **compiles and packages** (`./gradlew shadowJar` → `build/libs/MysticEssentials-1.0.4.jar`)
against `com.hypixel.hytale:Server:0.6.2` (Update 6).

Update 6 compatibility work includes the three-state `FlyMode`, up-front command
permissions via `requireNoPermission()`, spectator and hardcore-life player-list
fields, expanded builder-tool packet layouts, component-backed chunk/fluid reads,
and the ECS `InventoryComponent` model. The manifest intentionally targets only
the Update 6 patchline (`>=0.6.0 <0.7.0`) because Update 6 changed the protocol CRC.

---

## How the API was discovered

The Hytale API is **not guessed**. It was read directly from the compiled server
classes with `javap`:

- Cached artifact: `~/.gradle/caches/.../com.hypixel.hytale/Server/0.6.2/Server-0.6.2.jar`
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
| Server Players list | `protocol.packets.interface_.{AddToServerPlayerList, RemoveFromServerPlayerList, UpdateServerPlayerList, UpdateServerPlayerListPing}` + the `ServerPlayerListPlayer` row; written with `PlayerRef.getPacketHandler().write(packet)` |

Everything Hytale-specific is funneled through the **`platform`** package
(`HytalePlatform`, `Conversions`, `command/MysticCommand`,
`command/MysticCommandSender`) so the discovered-API surface is centralized and
easy to re-verify when the server version changes.

## Architecture (as built)

```
MysticessentialsPlugin (Hytale entry) → MysticCore (non-disableable) implements MysticEssentialsAPI
  Core services: ConfigManager, StorageService(+JSON/SQL providers, Redis transport),
    PlayerProfileService, MessageService(+ColorPipeline), PermissionService(LuckPerms),
    PlaceholderService(PlaceholderAPI), EconomyService(VaultUnlocked), TeleportService,
    SchedulerService, CooldownService, EventBus, RedisBridge
  Modules (config-toggled): teleportation, spawn(+homes), warps(+player warps),
    mail, announcements, afk, chat
  Public API: MysticEssentialsProvider.get() → MysticEssentialsAPI (service-based)
```

- **Core cannot be disabled.** Modules are toggled in `mods/MysticEssentials/config.json`.
- Modules communicate through **services and the EventBus**, not direct coupling.
- Storage is **provider-abstracted**; JSON and SQL are durable providers, while
  Redis is intentionally a separate cache/pub-sub/locking transport.
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
  messaging and channel routing. Channels track both the
  channel a player speaks in and the channels they listen to, filter
  `PlayerChatEvent` targets from that state plus permissions, support
  configurable aliases/prefixes/passwords, support temporary channels, and
  publish cross-server envelopes over Redis when a channel is `crossServer=true`.
- **Chat formatting**: rank/permission formats via `PlayerChatEvent` with
  per-permission colour gating before formatting.
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

### Server Players list decoration (implemented)

`core/playerlist/PlayerListService` puts rank prefixes/suffixes and an AFK
marker on the map-screen roster. Findings from the 0.6.0 jar and client:

- The engine's `server.core.modules.serverplayerlist.ServerPlayerListModule`
  builds every row in `createServerPlayerListPlayer(PlayerRef)` as
  `new ServerPlayerListPlayer(uuid, playerRef.getUsername(), worldUuid, ping,
  spectating, livesRemaining)`. Mystic Essentials preserves the Update 6
  spectator and hardcore-life fields when it replaces the display name.
  The name is a **plain protocol string** with no server-side hook, so the only
  way to change it is to send a replacement row for the same UUID afterwards.
- It sends the full roster to a connecting player and that player's single row
  to everyone else from **both** `PlayerConnectEvent` **and**
  `PlayerReadyEvent`, a `RemoveFromServerPlayerList` on disconnect, and a ping
  map every 10s. Update 6 moved the send that the client actually keeps onto
  `PlayerReadyEvent` (0.5.6 used `AddPlayerToWorldEvent`), which lands well
  after `PlayerConnectEvent` — decorating only on connect is silently
  overwritten, which is what made ranks disappear from the roster on 0.6.
  `PlayerReadyEvent` is keyed, so it needs `registerGlobal`
  (`HytalePlatform.onGlobalEvent`), not a plain `register`.
- Update 6 also added `broadcastListEntry(PlayerRef)`, which the engine calls
  from `ServerPlayerListSystems.OnSpectatingChange` and on a hardcore-lives
  change. Both re-send the plain username, so `PlayerListService` keys its
  change detection on `(name, spectating, lives)` — a spectating or lives
  transition therefore re-asserts the decorated name on the next refresh.
- Update 6 filters every one of those broadcasts through the viewer's
  `HiddenPlayersManager`. `PlayerListService.send` does the same, or a replaced
  row would put a vanished player back on the map roster.
- The client has `ProcessAddToServerPlayerListPacket` /
  `ProcessRemoveFromServerPlayerListPacket` /
  `ProcessUpdateServerPlayerListPacket` /
  `ProcessUpdateServerPlayerListPingPacket` handlers, and renders each row from
  `Data/Game/Interface/InGame/Pages/MapPagePlayerListEntry.ui` — a bare
  `Label #Username` with a single `TextColor`. **No inline markup**, hence
  `MysticText.stripMarkup` before sending.
- Whether the client upserts rows by UUID or appends them was not established
  from the AOT binary, so `playerList.rebuildEntries` (default on) sends
  `RemoveFromServerPlayerList` immediately before the add. That pair is correct
  under either semantic, and netty preserves the write order.
- Registration is at `EventPriority.LAST` so our rows land after the engine's;
  a one-shot resync 1.5s after **ready** repairs the roster if that ordering
  ever changes.

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

`MysticText` supports legacy (`&a`, `&l`/`&o`/`&n`/`&m`/`&r`; only `&k`
obfuscated is dropped — no field), hex (`&#rrggbb`, `<#rrggbb>`, 3-digit),
MiniMessage-style tags (`<red>`, `<color:#hex>`, `<bold>`/`<b>`,
`<italic>`/`<i>`, `<underlined>`/`<u>`,
`<strikethrough>`/`<st>`/`<s>`, `<reset>`, closing `</…>`),
`<gradient:#a:#b[:#c…]>…</gradient>`,
`<rainbow>…</rainbow>` (interpolated per character), `<link:target>…</link>`
(protocol `link` field), and `<lang:key>` (client translation via `messageId`).
Colours are set explicitly per segment, so rendering does not depend on
client-side markup. `underlined` is set via the public
`FormattedMessage.underlined` field (no `Message` setter); `strikethrough` —
the field Update 6 added — goes through `Message.strikethrough(boolean)`.

All runtime Custom UI text is assigned through `.TextSpans` and
`MysticPage.uiText(...)`. Do not mix `.Text` and `.TextSpans` on the same
control, including by declaring `Text:` in a `.ui` template that is later
updated through `.TextSpans`. UI spans also need an explicit colour on every
node because they do not reliably inherit `LabelStyle.TextColor`;
`uiText(...)` supplies the control's semantic fallback colour and preserves
explicit `MysticText` formatting.

**Hover is not supported** — `FormattedMessage` still has no hover field on
0.6.2, only `link`. Only `link` interactivity is representable. Update 6 added
`strikethrough` to that struct, which is why `&m` renders now instead of being
dropped.

**Live-verified** on a real server (originally 0.5.6; the Update 6 surface is
re-verified against the 0.6.2 jar):
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
**pub/sub** bus (`publish`/`subscribe`) backed by **Jedis 7.4.1** (netty-free, so no
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
and slf4j-api (like Jedis and jsoup) are **shaded into the mod jar and relocated**
under `org.hyzionstudios.mysticessentials.libs`, so another plugin's copy of the
same library can never mix with ours (`verifyShadedJar` checks the shipped jar);
protobuf is excluded from the MySQL driver. The driver class is set explicitly per flavour, so JDBC auto-discovery is
not relied on. If the DB is unreachable at start, the Core logs and **falls back
to JSON** so the server still boots. Configure host/port/db/credentials/poolSize
under `storage.mysql` in `config.json`.

### Integrations (wired and contract-checked)

- **LuckPerms API 5.5** (`net.luckperms.api`): `LuckPermsProvider.get()` on start. Permission checks go through `PlayerRef.hasPermission`; `primaryGroup`, `prefix`, and `suffix` read cached user metadata. Reinitialization clears a previous provider before probing again.
- **PlaceholderAPI-Hytale 1.0.8** (`at.helpch.placeholderapi`): external `%...%` values resolve through `PlaceholderAPI.setPlaceholders(PlayerRef, String)`. `MysticExpansion` publishes the `%mystic_...%` and `%mysticessentials_...%` namespaces. Registration, aliases, late-start retries, reload, and shutdown are generation-safe and unregister stale expansions.
- **VaultUnlocked 2.20.1** (Vault2): provider lookup uses `VaultUnlocked.economy()`. All money enters the current `BigDecimal` API through the plugin namespace; account creation and formatting use the 2.20 signatures. UUIDs and amounts are validated, provider exceptions fail safely, and no economy means no-op success.
- **MysticVanish 1.x**: `VanishBridge` reflects only the stable provider/API methods, so no local Vanish jar is compiled or shaded. It fails visible when the provider disappears or returns an error.
- **MysticModeration 1.x**: deliberately not a reverse manifest dependency because MysticModeration already optionally depends on Essentials. `ModerationBridge` discovers the running plugin and loads its provider through that plugin's classloader, avoiding both a dependency cycle and startup-order cache poisoning.
- **MysticIdentity 0.1.x**: `ManagedAccountsBridge` reflects `MysticIdentityProvider.get()` → `managed()` → `check` / `checkInteraction` and the `ManagedCapability` enum by name, so no local jar is compiled or shaded. Chat asks it at delivery time only — `PrivateMessagingSubModule.privateMessage` and `handleRemotePm` (`TEXT_PRIVATE`; the receiving server judges again because the origin may not know a remote child), `ChannelsSubModule.deliverInbound` (`TEXT_PUBLIC` per listener for a remote player, `TEXT_CROSS_PLATFORM` per listener for a bridged line). Local chat is already narrowed by MysticIdentity itself on the native chat event at `FIRST`, which `localRecipients` honours by intersecting with the event's targets. Fails open.
- **MysticRPG 1.x**: declared as an optional load-order dependency. RTP's reflection bridge checks the installed World API for player level and safe-region content; ItemView reads the published GearStamp/display metadata without mutating MysticRPG's signature.
- **QuestLines**: Custom Content locates the configured plugin id through the Hytale plugin manager and reflects its requirements, actions, substitutions, and GUI registration API. Compatible QuestLinesDialog/QuestLinesGUI data also has file-based import/export paths.
- **SimpleEnchantments and LuxReforge**: ItemView consumes their stable BSON stack metadata contracts. This is passive interoperability and therefore does not require their classes or manifest entries.
- **Redis / SQL**: Jedis 7.4.1 uses the supported pooled `RedisClient` for commands and a dedicated subscriber connection. HikariCP 7.0.2, MariaDB Connector/J 3.5.8, and MySQL Connector/J 26.7.0 are shaded for network storage.

`LuckPerms`, PlaceholderAPI, and VaultUnlocked are `compileOnly`; every Mystic
plugin bridge is reflection-only. The manifest's `OptionalDependencies` map is
load ordering, not installation requirements. `verifyIntegrationContracts`
checks the manifest and external method signatures, and is part of `check`.
`/mystic reload` reinitializes every config-toggled bridge and reconnects Redis.

### Chat formatting (wired)

`ChatModule` registers an **async** `PlayerChatEvent` handler
(`registerAsyncGlobal`, via `HytalePlatform.onAsyncEvent`) that runs the original
chat pipeline: colour permission filtering, channel routing,
and formatter installation. The formatter resolves the highest-priority
permission-gated format or channel format from `modules/chat/config.json`,
expands placeholders on the template only, then splices in the player's message
(so user text is never placeholder-expanded). Colour styles the sender lacks
permission for (legacy / hex / gradient / rainbow / MiniMessage / links) are
stripped from their message first (`ChatColors`).

### Custom UI system (verified, template-row based)

All UIs live in `Common/UI/Custom/MysticEssentials/*.ui` and are opened with
`HytalePlatform.openPage(PlayerRef, CustomUIPage)` (resolves the `Player`
entity on its world thread and calls `PageManager.openCustomPage`). Pages
extend `platform.ui.MysticPage`, which centralizes payload parsing and the
reopen-to-refresh pattern.

Lists follow the builtin `WarpListPage` pattern verified from the server
jar and `Assets.zip` (unchanged through 0.6.2):

- The page `.ui` declares an empty scrolling container
  (`Group #WarpList { LayoutMode: TopScrolling; }`).
- A separate row-template `.ui` file is appended once per entry:
  `cmd.append("#WarpList", "MysticEssentials/WarpRow.ui")`.
- Appended rows are addressed by index:
  `cmd.set("#WarpList[0] #Name.TextSpans", ...)`, and event bindings target either
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
path.

### UI design system (`MysticTheme.ui`)

Every page and row template imports `$M = "MysticTheme.ui";` next to
`$C = "../Common.ui";`. Window chrome, primary buttons, inputs and dropdowns
still come from the game's `Common.ui` so a Mystic page sits beside a native
one without a seam; everything inside the window is built from the theme:

- **Tokens**: palette (`@Ink0`…, `@TextHi/Body/Muted/Dim/Faint`, `@Gold`,
  `@Blue`, `@Success/Warn/Danger`) and one type scale (`@DisplayStyle` for the
  selected item, `@HeadingStyle`/`@RowNameStyle`, `@EyebrowStyle` for section
  labels, `@Body/Meta/CaptionStyle`, `@Key/ValueStyle` for key/value rows).
- **Surfaces**: `@Panel` (the game's bordered header-tab frame) for the thing
  being viewed or edited; `@Well` (flat inset) for lists and empty states —
  put ONLY the list scroller inside a Well so appended rows keep their indices.
- **Rows**: `Button #Row { Style: $M.@RowStyle; $M.@RowSelection #Selected {}
  Group { Anchor: (Full: 0); LayoutMode: Left; ... } }`. The row root is
  absolutely positioned so the selection overlay (gold spine + tint) draws
  behind the body. Master/detail pages set `#Selected.Visible` on the resolved
  selection. `#Swatch` / `#Accent` / `#TagChip` stay plain-colour Groups because
  the server recolours them through `.Background`.
- **Helpers**: `@Section` (eyebrow + rule), `@FieldLabel`, `@KvRow` + `@Key`,
  `@Hairline`, `@IconWell`, `@Pill`, `@Tab`, `@SettingRow`, `@ActionBar` +
  `@Spacer` (put the destructive button after the spacer), `@HeaderMeta`,
  `@ColumnDivider`, `@Bullet`.

Sizing facts that shape the layouts: `Common.ui` buttons are a fixed 44 px
(small variants 32), text fields 38, dropdowns 32 × 330 — the component sets
those AFTER spreading `@Anchor`, so pass margins through `@Anchor` but override
`Anchor:` directly on the instance (as the game's own pages do) when a
different Width/Height is genuinely needed. An input beside a button sits in
a 44 px row with `@Anchor = (Vertical: 3)`.

Runtime text colour comes from `MysticPage.UI_TEXT_COLORS` (a `TextSpans`
value carries its own colour and overrides the label style), so that table
mirrors the theme tokens; add an entry there when introducing a new dynamic
label id.

Every component parameter in `MysticTheme.ui` has a default, and
`validateUiDocuments` enforces both that and every `$M.@X` / `$C.@X`
instantiation (an omitted required parameter breaks OTHER mods' documents on
the client).

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

## Shared services for other mods

Two services live on Core rather than in a module, because they outlive any one
module: an ItemView provider registered by MysticRPG must survive the chat
module being toggled, and a guild warning must send whether or not chat is
enabled. Both hang off `MysticEssentialsAPI`.

### ItemView providers

Register an `ItemViewProvider` to contribute structured data about your own
items. Every ItemView on the server picks it up — chat item links, the details
panel, and any other mod's lookups.

```java
api.getItemInspectionService().registerProvider(new ItemViewProvider() {
    public String getProviderId() { return "example_rpg"; }
    public int getPriority()      { return 100; }

    public boolean supports(ItemStack item, ItemInspectionContext context) {
        return "example_rpg".equals(ItemNames.namespaceOf(item.getItemId()));
    }

    public void populate(ItemStack item, ItemInspectionContext ctx, ItemViewBuilder b) {
        b.quality("example_rpg:null")      // a quality literally named "Null"
         .rarity("example_rpg:epic")
         .tier("example_rpg:maelstrom")
         .category("Weapon").subcategory("One-Handed")
         .addModifier("haste", 8.1)
         .addModifier("defense", -4.8)
         .addSection(ItemViewSection.builder("resonance", "Resonance")
                 .row("Storm", "2 / 5 pieces")
                 .build());
    }
});
```

Rules worth knowing before you write one:

- **Providers supply data, never layout.** Section placement is a *hint*; Mystic
  Essentials clamps it into the fixed order so no mod can push its block above
  the identity panel or split the statistics table. UI consistency, text
  sanitization, localization, scrolling, and permission checks stay here.
- **Scalar setters overwrite, collection methods append.** The generic native
  inspection runs first, then providers in ascending priority, so the
  highest-priority provider has the last word on fields it sets and leaves the
  rest intact. Passing `null` to a setter is a no-op, not a clear — use the
  `clearQuality()` family to state absence deliberately.
- **Absence is `null`, never a string.** A quality named `Null`, `None`,
  `Unknown`, or `Undefined` is real data and is rendered verbatim. Only a
  missing object means the item has no value on that axis. Do not write
  `if ("null".equalsIgnoreCase(name))` — that destroys a valid quality.
- **Failures are contained.** An exception from `populate` is logged
  (rate-limited per provider) and skipped; the player still gets a working
  ItemView from everything else. Do not rely on it, but the UI will never fail
  to open because a third-party mod threw.
- **Called on the owning world thread.** Component reads are safe; blocking
  calls are not.

### Notifications

One engine backs mentions, broadcasts, alerts, quest updates, guild warnings,
economy notices, mail, moderation notices, and restarts. Use it instead of
sending your own titles and sounds so your notices obey the same profiles,
player preferences, and history rules as everything else.

```java
api.getNotificationService().send(
    Notification.builder()
        .category(NotificationCategory.GUILD)
        .priority(NotificationPriority.CRITICAL)
        .title("Guild Claim Under Attack")
        .subtitle("Eastern Outpost")
        .message("Your guild's eastern claim is under attack.")
        .sound("SFX_Attn_Loud")
        .showAsBossBar(true)
        .storeInHistory(true)
        .source("mysticguilds:claims")
        .build(),
    NotificationAudience.guild(guildId));
```

- Describe **content and intent**; the delivery layer picks the surfaces from
  the category's profile. The per-surface flags on `Notification` are overrides
  — leaving them unset is what lets a server admin retune every event
  notification at once.
- **Audiences your mod owns** resolve through a registered resolver:
  `registerAudienceResolver("guild", id -> membersOf(id))`. Without one,
  `NotificationAudience.guild(...)` delivers to nobody rather than erroring.
- **History is reserved for durable notices.** Important and critical profiles
  are stored by default even when nothing was shown, so a player in
  do-not-disturb can still find them in `/notifications`. Low and normal sends
  must explicitly opt in with `storeInHistory(true)`.
- **Critical notifications bypass player preferences** unless the server sets
  `critical.allowPlayerDisable` in `notifications.json`. That rule is enforced in one
  place, not at each call site.

- **Notification Center tabs are registerable.** Mystic Essentials ships only
  `all`, `unread`, `mentions`, and `system`; anything grouped by a concept it
  does not own is contributed by the mod that owns it, and a filter nobody
  registers is not shown — a hardcoded "Guild" tab on a server with no guild mod
  is a button that can only ever report an empty list.

  ```java
  notifications.registerFilter(new NotificationFilter() {
      public String getId()          { return "mysticguilds:guild"; }
      public String getDisplayName() { return "Guild"; }
      public int getSortOrder()      { return 40; }
      public boolean matches(NotificationRecord r) {
          return "guild".equals(r.category().id());
      }
  });
  ```

  Same contract as mention scopes: namespace the id, `isAvailable()` hides a tab
  temporarily, re-registering replaces, and a throwing `matches` costs that tab
  rather than the page.
- **`chatPrefix(...)`** overrides the category's chat prefix for one send, for
  senders that already own a configured prefix (this is how the announcements
  module keeps applying `broadcastPrefix`/`alertPrefix`). Chat surface only —
  titles, toasts, and history entries are never prefixed. `""` means "no prefix",
  which is distinct from unset.
- **Staff override.** `bypassPlayerPreferences(true)` delivers regardless of the
  recipient's muted categories, disabled surfaces, and do-not-disturb. It is a
  per-send decision rather than a permission the sender simply holds, so a
  moderator's contact gets through while their ordinary chatter does not. It
  still honours `critical.allowPlayerDisable`, so a server that
  has explicitly handed control to players keeps that promise.

### Mention scopes

The "who may mention you" list in `/mentions` is built at runtime. Mystic
Essentials registers only `everyone` and `nobody` — the two scopes it can
enforce without help. Anything based on a relationship this mod does not model
is contributed by the mod that owns it:

```java
chat.registerMentionScope(new MentionScopeProvider() {
    public String getId()          { return "mysticguilds:guild"; }
    public String getDisplayName() { return "Guild Members Only"; }
    public int getSortOrder()      { return 20; }

    public boolean isAvailable() { return guilds.isLoaded(); }

    public boolean allows(UUID sender, UUID target) {
        return guilds.shareGuild(sender, target);
    }
});
```

- **Namespace your id** (`mysticguilds:guild`), so two mods cannot collide on
  `friends`. Re-registering an id replaces the previous provider.
- **A scope with no provider is not shown.** Offering "Friends Only" on a server
  with no friend system is a setting that silently does nothing, which is worse
  than a missing one.
- **`isAvailable()` hides an option temporarily** while your system is unloaded,
  without removing it.
- **A stored choice survives your mod being absent.** If a player selected your
  scope and it later disappears, their preference is kept (the UI shows it as
  unavailable) and mentions fall back to *allowed* rather than blocked — the
  missing provider is a server-side condition the player had no part in. The one
  exception is `nobody`, which is built in and therefore always enforceable, so
  the choice that unambiguously means "leave me alone" never degrades.
- **`allows` runs on the chat path**, once per candidate mention. Keep it to an
  in-memory lookup; a throw is treated as "no" and logged, never breaking the
  message.

Config lives in `modules/core/notifications.json` (profiles + category
catalogue), `modules/chat/item-view.json` (inspection + panel display), and
`modules/chat/mentions.json` (matching, limits, and rules such as
`rules.staffBypassPlayerSettings`, `rules.ignoredPlayersCanNotNotify` and
`rules.mutedPlayersCanNotNotify`).

### Chat from other mods (`ChatService.deliver`)

Guild, officer, party or settlement chat that another mod owns never passes
through `PlayerChatEvent`, so nothing that enforces chat rules on that event sees
it. `ChatService.deliver(sender, recipients, channelLabel, message[, format])`
applies them instead and returns a `ChatDeliveryResult` (status, delivered,
skipped, reason):

```java
ChatDeliveryResult result = chat.deliver(senderId, memberIds, guild.name(), text);
```

- **Mutes.** Mystic Essentials keeps no server mutes; `ModerationBridge.lookupMute`
  asks MysticModeration (`ModerationServiceRegistry#find(PunishmentService)` then
  `PunishmentService#activeMute(UUID)`) — the lookup its own chat gate makes. A mute
  refuses the line (`MUTED`, the sender gets `chat-you-muted`); a `SHADOW_MUTE` shows
  it to the sender alone (`SHADOW_MUTED`, `isDelivered()` still true, so the caller
  must not relay it). The public `activeMute(player)` is the same lookup, empty on
  failure; `activeMute(player, channelId)` adds a temporary channel's moderation mute.
- **Chat guard and tutorial.** `ModerationBridge.checkChat` runs
  `ChatGuardService#evaluate(uuid, name, message)` (filter, chat lock, slow mode;
  `BLOCK` refuses with its detail as feedback, `REWRITE` replaces the text), and
  `TutorialService.isChatBlocked` refuses lines while a tutorial blocks chat; both
  give `BLOCKED`. MysticModeration absent, disabled in the config, or with the module
  unpublished means "allow"; installed but failing (`ModerationUnavailableException`)
  refuses the line with `chat-moderation-unavailable`.
- **Ignores.** `isIgnoring(recipient, sender)` is the recipient's ignore list
  (`/ignore`, `NotificationPreferences.ignoredPlayers`, UUIDs edited under the
  preferences' lock); such a recipient is skipped. Names stored before the list was
  kept by UUID (`blockedMentioners`) are resolved with `PlayerProfileService.resolveUuid`
  when the preferences load; an unresolved one is logged and keeps applying by name.
  Public chat (`ChatDelivery.withoutIgnoring` on the event targets) and lines from
  other servers (`deliverInbound`) apply the same list, mentions honour it while
  `rules.ignoredPlayersCanNotNotify` is on, and private messages are refused on every
  path: locally, on the target's server for a relayed one (a `pm-notice` Redis
  message tells the sender's server, which replies `pm-blocked`), and from the stored
  profile for an offline target's mail fallback. `mysticessentials.chat.ignore.exempt`
  senders always get through (the relay carries the flag as `ignoreExempt`).
- **Managed accounts.** Each recipient is judged on the pair with
  `TEXT_PUBLIC`, as for a cross-server channel line.
- **Text safety.** The message goes through `preparePlayerMessage` (token
  delimiters, colour permissions, length) and is filled into the format with
  `MessageServiceImpl.fillParams`, so neither it, the nickname nor the caller's
  label is ever parsed for placeholders; the label keeps colour codes only.
- **Audience.** Recipients not online on this server are skipped, the sender
  always gets their own line and it counts as their AFK activity
  (`AfkService.markActivity`). The line is echoed to the server log and published as
  `ChatDeliveredEvent` for moderation tooling; nothing is relayed over Redis and
  `ChatMessagePublishedEvent` is not fired, so bridges never repeat it.

The recipient rules live in `ChatDelivery.plan`, free of engine types and checked
by `verifyChatDelivery`.

Mystic Essentials' built-in sounds use vanilla AssetMap ids: routine notices use
`SFX_Attn_Quiet`, announcements use `SFX_Attn_Moderate`, alerts use
`SFX_Attn_Loud`, and critical notices use `SFX_Attn_VeryLoud`. The announcement
module exposes `broadcastTitle` and `broadcastSound` for rotating announcements
and `alertTitle` for the manual alert headline. Both manual commands render
through Hytale's `EventTitleUtil` surface and only play a sound when one is
explicitly supplied.

## Building

```bash
./gradlew shadowJar        # -> build/libs/MysticEssentials-1.0.4.jar (deploy this one)
                           #    the plain `jar` task now writes *-thin.jar so it can no longer
                           #    overwrite the shaded jar during `gradle build`
./gradlew runServer        # local dev server in run/ (hytale-tools)
```

Requires JDK 25 (configured via the Gradle toolchain). The build uses AzureDoom's
`com.azuredoom.hytale-tools` plugin; the Hytale version and manifest fields live in
`gradle.properties`, and `manifest.json` is rewritten from them on every build.
