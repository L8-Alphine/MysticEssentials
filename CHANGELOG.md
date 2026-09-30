# Mystic Essentials Changelog

## Unreleased — Licensing on MysticLicenses v2

### Changed

- The CustomContent license moves from offline `license.mclicense` files to
  MysticLicenses v2. Claim your license in the portal and put its key in
  `mods/MysticEssentials/license.key`. The server activates online, then runs
  on a signed authorization it caches and renews in the background, with an
  offline grace period while the licensing service is unreachable. A server
  licensed before starts at once; a first start waits at most 5 seconds.
- The server no longer needs registering by hand: it appears in the portal once
  activated. Its licensing identity lives in `.mystic/` at the server root,
  shared with other Mystic mods.
- `/mystic license reload` re-reads `license.key`: a new key activates and
  frees the previous license's slot, the same key is re-checked at once, and a
  removed key releases the server.
- A license change while the server runs is logged, and `/mystic reload` now
  also stops a licensed module whose license lapsed (it previously only started
  newly licensed ones).
- The licensing service's address and public keys are set at build time
  (`mystic.licensing.url` / `mystic.licensing.keys`, shared by every Mystic
  mod); see `DEVELOPER_NOTES.md`. A build without them runs with licensed
  modules off.

### Removed

- `mystic-license-core` and `mystic-license-example-mod`, the retired
  prototype's offline verifier, replaced by the v2 SDK in `vendor/`.
  `license.mclicense`, `server-id.txt` and `license-request.json` are no
  longer read or written; a leftover `license.mclicense` gets a one-line
  notice at startup.

## 1.0.4 — Integration Upgrade and Lifecycle Reliability

Released August 30, 2026.

Mystic Essentials 1.0.4 upgrades every bundled integration dependency, corrects
optional-plugin metadata and load ordering, and makes integration reload and
shutdown behavior safe for long-running Update 6 servers.

### Compatibility

- Built and verified against Hytale Server `0.6.2`.
- Supports the Update 6 patch line: Hytale Server `>=0.6.0 <0.7.0`.
- Keeps every third-party plugin integration optional; missing providers no
  longer prevent Mystic Essentials from loading.

### Dependency Upgrades

- Updated the Hytale compile target from `0.6.0` to `0.6.2`.
- Updated VaultUnlocked to `2.20.1` and migrated economy calls to its current
  `BigDecimal` formatting and account-creation API.
- Updated HikariCP to `7.0.2`, MariaDB Connector/J to `3.5.8`, MySQL Connector/J
  to `26.7.0`, Jedis to `7.4.1`, and jsoup to `1.23.2`.
- Retained PlaceholderAPI-Hytale `1.0.8` and LuckPerms API `5.5`, which remain
  their compatible current API lines.

### Optional Plugin Integrations

- Corrected `manifest.json` so LuckPerms and VaultUnlocked are optional instead
  of hard dependencies.
- Added optional load-order declarations for MysticVanish and MysticRPG.
- Removed compile-time dependencies on local MysticVanish and MysticModeration
  jars; both bridges now remain unbundled and fail safely when absent.
- Reworked MysticVanish access around its current provider API.
- Fixed MysticModeration discovery when it loads after Essentials by resolving
  the live plugin through its own classloader, without creating a dependency
  cycle.
- Documented the complete integration surface: LuckPerms, PlaceholderAPI,
  VaultUnlocked, MysticVanish, MysticModeration, MysticRPG, QuestLines,
  SimpleEnchantments, LuxReforge, Redis, MySQL, and MariaDB.

### Cross-Server (Redis)

- Every connected server now publishes a roster of its online players over
  Redis. `/tpa`, `/tpahere`, `/msg` and `/mail send` tab-complete and resolve
  players on any server in the network, and the Teleport Requests UI lists
  remote players with their server id.
- Teleport requests to a player on another server are stored in Redis (keyed
  by the target, expiring with `requestExpirySeconds`) as well as announced
  over pub/sub. The target's server pulls the stored requests whenever the
  player opens the UI or runs `/tpaccept`/`/tpdeny`, so a request is never lost
  to a missed announcement or a server switch; `/tpcancel` withdraws the stored
  copies too. Incoming notifications and UI rows name the requester's server.
- Accepting a cross-server request refers the mover to the destination server
  and completes the teleport once they are ready in a world there (no warmup,
  no movement cancellation — a freshly spawned player settles for a moment and
  the old connect-time attempt was cancelling on that or firing before the
  entity was placed). Configurable via `crossServer.arrivalDelaySeconds` and
  `crossServer.arrivalTimeoutSeconds`.
- `/tp <player>` and `/tphere <player>` accept players on other servers: `/tp`
  sends the staff member to that server and completes the move on arrival,
  `/tphere` brings the player over and then to the staff member.
- The arrival details for a transfer travel in the engine's referral payload
  (`referToServer(host, port, data)` → `PlayerSetupConnectEvent.getReferralData()`
  on the destination), with the Redis record kept only as a fallback.
- Referral payloads are only honoured when they match the arrival record the
  referring server wrote to Redis, so a client cannot hand-craft one to be
  teleported to another player on connect.
- `storage.redis.proxyHost`/`proxyPort`: with a network proxy (MysticGate) in
  front, every referral goes to the proxy with the destination server in the
  payload, so backends can stay on private addresses. The payload contract a
  proxy must honour is documented under Storage.
- `/mystic network` (`mysticessentials.network`) shows this server's advertised
  address and every other server on the roster with its address, player count
  and heartbeat age. Referrals are logged on both ends.
- Servers advertise the address other servers refer players to. It is
  auto-detected from the engine (bound game port, bound interface or first
  LAN/public IPv4) and logged at startup; `storage.redis.advertisedHost` /
  `advertisedPort` override it behind NAT, Docker or a proxy. Accepting a
  request whose destination has no usable address now replies with a clear
  message and logs a warning instead of a generic "player is no longer online".
- Chat lines arriving from another server in a `crossServer` channel are
  prefixed with their origin (`channels.crossServerPrefix`, default
  `&8[&b{server_id}&8] `, skipped when the channel format already places
  `{server_id}`).
- Private messages to a player on another server resolve through the roster
  and relay by UUID; the sender's echo shows the recipient's name, and a player
  online nowhere falls back to mail instead of a relay nobody receives.

### Build

- The plain `jar` task now writes `MysticEssentials-<version>-thin.jar`. It
  used to share the shaded jar's file name, so a `gradle build` could leave a
  jar without Jedis/JDBC/jsoup in `build/libs` — deploying that made Redis fail
  at startup with a class-not-found wrapped as "connection failed" and every
  cross-server feature silently local-only. Deploy `MysticEssentials-<version>.jar`.

### Lifecycle and Reliability

- `/mystic reload` now reapplies LuckPerms, PlaceholderAPI, VaultUnlocked,
  MysticVanish, MysticModeration, and Redis configuration changes.
- PlaceholderAPI expansions and aliases now unregister cleanly on reload and
  shutdown; generation-aware retries cannot resurrect disabled registrations.
- LuckPerms and Vault provider state now resets correctly when an integration is
  disabled or disappears during reload.
- Economy calls now reject invalid UUIDs, negative amounts, and non-finite
  values, while safely containing provider failures.
- Redis now uses Jedis' supported pooled `RedisClient`, retains registered
  logical handlers across reconnects, and prevents stale subscriber threads
  from surviving a reload.
- Redis command clients now close safely during failed startup, reload, and
  server shutdown.

### Hytale API Corrections

- Migrated RTP block reads to Update 6's component-backed `BlockChunk` API.
- Migrated message BSON decoding to the current codec method with `ExtraInfo`.
- Replaced manual item-stack reconstruction with `ItemStack.withQuantity` where
  the current immutable API is available.
- Narrowly marked unavoidable raw item-metadata reads as compatibility
  boundaries; Hytale does not yet expose a non-deprecated API for lossless
  enumeration of complete stack BSON.
- Fixed rank prefixes and suffixes disappearing from the Server Players list on
  the map screen. Update 6 moved the engine's roster broadcast onto
  `PlayerReadyEvent`, which fires after `PlayerConnectEvent` and overwrote the
  decorated names; the list service now follows that event, which is keyed and
  so requires a global event registration.
- Decorated rows are now re-asserted when a player's spectator or
  remaining-lives state changes, because Update 6 re-broadcasts the plain
  username on both transitions.
- Player-list rows are no longer sent to a viewer who has that player hidden,
  matching how Update 6 filters the engine's own player-list broadcasts.
- Added strikethrough text support. Update 6 added a `strikethrough` field to
  the `FormattedMessage` protocol, so `&m` and the new
  `<strikethrough>`/`<st>`/`<s>` tags now render instead of being discarded;
  `&k` obfuscated still has no field and is still dropped.
- Tutorial page buttons with a `sound` action now play the sound. The action had
  been logged and skipped against an out-of-date note claiming no verified
  play-sound-to-player API existed; it uses the same
  `SoundEvent` asset-map lookup plus `SoundUtil.playSoundEvent2dToPlayer` path
  as the custom-command sound action.
- Re-verified every remaining Hytale API note against the 0.6.2 jar and
  corrected the ones Update 6 changed. Notably: the native `UpdateBossBar`
  packet is entity-bound (`entityNetworkId`, `name`, `hide`) with no progress
  value, so the notification banner stays a custom HUD document; death still has
  no event, so the inventory death-snapshot poll stays; processing benches still
  have no craft veto; and the `Inventory` convenience accessors
  (`getItemInHand` and its whole family) are now deprecated for removal with no
  replacement that expresses "item in hand".

### Interface Redesign

- Every custom UI page now shares one design system, `MysticTheme.ui`: a
  single palette and type scale, bordered panels for whatever is being viewed
  or edited, flat wells for lists, and gold reserved for "this one".
- Master/detail pages (Homes, Warps, Player Warps, Kits, Chat Channels, the
  dialog builder) now mark the selected row with a gold spine and tint instead
  of leaving the list unmarked.
- Rows gained an icon well for their colour chip or item icon, two-line
  name/meta typography, hover and press feedback with the game's button sounds.
- Action bars follow one rule everywhere: primary action first, destructive
  action pushed to the far end.
- Forms (Warp Admin, Portal, Vault Editor, temp channels, Nick, Mail Admin,
  Dialog Builder) use uppercase field eyebrows, consistent 38/44 px rows and
  grouped sections; long forms scroll while their footer stays fixed.
- Notification and mention settings toggles became full-width setting rows;
  filter and audience choices became tab strips.
- Patch-note added/removed lines now carry their green/red tint (the span
  colour was silently overriding the template before) and show +/- markers.
- HUD overlays (warmup, RTP status, banner, action bar) share one quiet
  accent-spine style.
- `validateUiDocuments` also checks `MysticTheme.ui` components, and requires
  every theme parameter to carry a default.
- Removed the unreferenced `ItemStatRow.ui` template.

### Verification

- Added `verifyIntegrationContracts` to Gradle's standard `check` lifecycle.
- The contract check protects optional manifest metadata and the external
  LuckPerms, PlaceholderAPI, VaultUnlocked, Redis, SQL, and GUI-parser APIs.
- Verified the shaded jar contains its required database, Redis, and jsoup
  runtime libraries while excluding all optional plugin APIs.
- Passed a clean compile, the complete compatibility suite, 347 UI component
  validations, and the shaded release build.

### Upgrade Notes

1. Back up the server and Mystic Essentials configuration directory.
2. Replace the previous jar with `MysticEssentials-1.0.4.jar`.
3. Restart the server for the jar upgrade. Later integration configuration
   changes can be applied with `/mystic reload`.
4. No configuration migration is required from 1.0.3.

## 1.0.3 — Update 6 Compatibility, Craft Blocking & Safer RTP

Mystic Essentials 1.0.3 completes the migration to Hytale Update 6, adds server-side craft blocking and MysticRPG-aware random teleport safety, expands ItemView metadata support, and refines notification history and sound behavior.

### Compatibility

- Requires Hytale Server `>=0.6.0 <0.7.0`.
- Built and verified against Hytale Server `0.6.0` and protocol `hytale/3`.
- Update 5 servers and clients are not compatible with this release.

### Hytale Update 6

- Migrated flight handling to Update 6's three-state `FlyMode` API.
- Updated commands that intentionally have no permission requirement to use the new explicit command API.
- Updated player-list packets for spectator state and remaining-lives data.
- Updated Editor Builder block-change packets to the expanded Update 6 layout.
- Migrated chunk and fluid checks to the new chunk-component APIs.
- Migrated inventory, mail, vault, and related item operations to ECS inventory components.
- Updated the bundled manifest and documentation for the new server version range and protocol.

### Craft Blocking

- Added a new CraftBlock module for denying configured recipes or output items during bench and hand crafting.
- Configure case-insensitive recipe or item IDs in `modules/craftblock/config.json`; `*` wildcards are supported.
- A matching output item blocks every recipe that produces it.
- Added `/craftblock` to inspect the active block list and `/craftblock check` to inspect the held item.
- Added global and per-item bypass permissions:
  - `mysticessentials.craftblock.bypass`
  - `mysticessentials.craftblock.bypass.<item id>`
- Added optional toast, chat, and server-log feedback with notification cooldown protection.
- Processing-bench recipes are not intercepted by Hytale's craft pre-event and are not covered by this module.

### MysticRPG-Aware Random Teleport

- Added an optional, soft integration with MysticRPG's World module.
- RTP now checks candidate and final destinations against the requesting player's current RPG level when the integration is available.
- The default accepted content range is 10 levels below through 3 levels above the player and is configurable under `mysticRpgSafety` in `modules/teleportation/rtp.json`.
- MysticRPG safe regions remain valid regardless of content level.
- Centered RTP shapes narrow inward when content is too high-level and expand outward when it is too low-level.
- Added configurable behavior for integration errors and additional admin diagnostics.
- Servers without MysticRPG continue to use the normal RTP safety checks.

### ItemView and Mod Metadata

- Expanded portable item metadata rendering for enchantments, LuxReforge, and MysticRPG gear.
- Added structured MysticRPG display support for names, descriptions, templates, rarity, quality, affixes, durability, binding, and gear details when present.
- Improved native item-quality resolution with runtime quality-asset lookup and validated configuration overrides.
- Improved Item Details layout and rich-text handling for larger and more complex metadata sets.
- Unknown metadata remains visible in technical form instead of being silently discarded.

### Notifications and Announcements

- Important and critical notices are stored in notification history by default.
- Low and normal notices are stored only when the sender explicitly opts in.
- Manual `/broadcast` and `/alert` messages are transient by default at every priority; use `--history` to retain one or `--no-history` to force transient delivery.
- Manual broadcasts and alerts play a sound only when `--sound` is supplied.
- Rotating announcements continue to use their configured broadcast sound.

### Reliability and Verification

- Added compatibility coverage for portable item metadata and native quality assets.
- Added Item Details layout checks.
- Added MysticRPG RTP level-safety and fluid-safety checks.
- Verified all UI assets through the component-instantiation validator.
- Verified the release with a clean Update 6 build.

### Upgrade Notes

1. Back up the server and Mystic Essentials configuration directory.
2. Replace the previous jar with `MysticEssentials-1.0.3.jar`.
3. Update the server and clients to Hytale Update 6 before starting this version.
4. Review the new `modules/craftblock/config.json` file before enabling recipe restrictions.
5. Review `mysticRpgSafety` in `modules/teleportation/rtp.json` if MysticRPG is installed.
6. Review notification automations that depended on implicit history or manual alert sounds; use `--history` and `--sound` explicitly where required.

Existing CustomContent licensing and fallback behavior are unchanged in 1.0.3.
