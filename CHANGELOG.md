# Mystic Essentials Changelog

## Unreleased — LootBox Creator (Phase 2)

The in-game LootBox creator. Pools, keys and crates can now be built, published,
placed and looked after without touching a file or typing a staff command, and
the player screens gained reduced motion and a readability pass. None of these
screens has been tried in a game client yet.

### LootBox

- The `/lootbox admin` Manager lists every pool with its draft status: Draft,
  Invalid (with the first problem), Ready to publish, Published or Disabled. It
  has a search box, a status filter, and one action bar for the selected pool.
- **New Pool** writes an empty draft, **Clone** copies a draft under a new id,
  and **Archive** moves a draft file to `lootboxes/_archive/` after asking. Nothing
  is ever deleted, and a published revision stays live until the pool is closed.
- **Publish** in the Manager now asks first, and only appears when the draft is
  valid and differs from the live revision.
- Draft files are read and written through a draft store that refuses to save
  over a file changed since it was loaded, and audits every create, save, clone
  and archive.
- **Pool editor** (Manager → Edit; New Pool opens it too). One working copy across
  four tabs:
  - General: name with a coloured preview, description, icon from a searchable
    item picker (or the held item), and odds mode. Switching between weights and
    exact percentages converts the numbers exactly where it can and lists every
    reward it had to round.
  - Keys & Rules: link published keys, cooldown, extra permission node.
  - Rewards: the list with each reward's live chance and "1 in N", Add,
    Duplicate, Up/Down and Delete (asks first). The selected reward has its id
    (fixed once published), name, rarity, weight or percent, and type. Item
    rewards take an item from the picker and a quantity; economy rewards an
    amount; permission rewards a node and duration; console commands a
    template, an allowlisted pattern and a "safe to repeat" switch. There is an
    announce switch, and the total weight, or how much of 100% is assigned.
    Typed text that does not parse stays as typed and blocks switching rewards,
    so it is never applied to the wrong one. Changing COMMAND rewards needs
    `rewards.command`.
  - Publish: what blocks publishing, and every change from the live revision with
    chances as old % → new %. Publish asks first and saves unsaved changes before
    publishing.
- Saving is explicit and never silent about conflicts: if the draft file changed
  since it was opened, Save is refused and offers Load From Disk or an audited
  Overwrite. Closing with unsaved changes asks first.
- **Key Manager** (Manager → Keys): every key definition with its status and the
  pools it opens, search, a status filter, New Key, Clone, Archive and Publish
  (both ask first; keys already given out keep their current look).
- **Key editor**: base item from the item picker, name with a colour picker and
  Bold/Italic switches, five lore lines, uses and expiry, a live preview of the
  key item, the pools it opens, and its problems and changes. Saving, conflicts
  and Publish work like the pool editor.
- New permission `mysticessentials.lootbox.edit` for New Pool, Clone, Archive and
  the editors; `mysticessentials.lootbox.keys` also opens the Key Manager.
- **Key grants** (Key Manager → Grants, or `/lootbox key grants <keyId>`): give a
  key to a typed player name or to online players picked from a list, look up and
  revoke a serial or a batch, and read the key's recent history. Giving more than
  one key, or to more than one player, asks first. A PAID source is still refused,
  and the refusal is recorded. Viewing needs `keys`, giving `keys.give` and
  revoking `keys.revoke`, each checked again on every press.
- **Key deposit and withdraw**: `/lootbox key deposit` turns the key in the active
  hotbar slot into virtual balance (a serialized key credits its remaining uses;
  keys that expire can't be deposited). `/lootbox key withdraw <keyId> [count]`
  turns balance back into key items, which open once and never expire; what
  doesn't fit goes to `/lootbox pending`. Both run as a recoverable saga, finished
  on join and by the 5-minute sweep, and every conversion is audited. The Catalog
  has a **Deposit Held Key** button too. `keys.allowConversion` switches both off.
- **Stackable keys** (opt-in per key, Stacking: On in the key editor): a grant is
  issued as one stack instead of separate items, and each item of the stack opens
  once. A stack is weaker against duplication: the batch counter, not the item,
  decides how many openings are left, so a copied stack item uses up the batch.
  Stacks only form when the base item stacks, and different grants never merge.
  `key info` and `key revoke` accept `batch-…` ids; revoking a batch kills every
  item in it. A stackable key must have 1 use; publishing refuses more.
- **Crate Manager** (Manager → Crates): every placed crate with its world,
  position, pool and status, with search, a filter, Edit, Disable/Enable, Remove
  (asks first) and Verify This World. **Convert Looked-At Block** opens the crate
  editor on the block you look at.
- **Crate editor**: the placement workflow as steps. Block and Check (the
  compatibility report and its warnings), Pool (published pools only), Use (preview
  first, or straight to the confirm screen, which still lists every reward with its
  exact odds), Protect (can only tighten the server's protection), Visuals, and
  Finish (Create or Save, and Test in World, which is a real opening and spends a
  key). Crates the server turned off get a Repair step: turn it back on, use the
  block that is there now, or remove it. A save is refused if someone else saved
  the crate since you opened it.
- New crate commands: `/lootbox crate edit [crateId]`, `crate rebind <crateId>
  <pool>`, `crate remove <crateId> [force]`, and `crate effects [on|off]` for
  players. `crate select` lists compatibility warnings.
- **Crate visuals**: idle and opening particles and an opening sound per crate,
  shown only to nearby players who haven't turned them off. New crates get the
  defaults from `crates.defaultIdleParticle`, `defaultOpenParticle` and
  `defaultOpenSound`; existing crates keep none. **Floating names** (off by
  default, `crates.nameplates`) are the same for everyone. Glow is not supported.
- **Generated block variants** (off by default, `crates.generatedVariants`): a
  plain block can become a crate through a look-alike written to
  `mods/MysticEssentialsGenerated/`. The swap keeps rotation, removing the crate
  puts the original back, and unused look-alikes are cleaned up at startup. Only
  single blocks without block data, states or connections qualify. Removing such a
  crate needs its chunk loaded; `crate remove <id> force` skips the restore.
- **Simulation** (Manager → Simulate): runs 100,000 draws at once (or
  `simulation.maxTrials` if lower), then any number up to that cap. Each reward
  shows its exact chance, the share it got, the count against the expected count
  and the gap, with two bars. It warns when the rarest reward is expected fewer
  than 10 times. Nothing is awarded, spent or recorded.
- **Audit History** (Manager → Audit, or `/lootbox audit`): filter by pool or
  target, actor or player, action or group, and time window, 10 records a page,
  newest first. **Export** writes the filtered records as JSON or CSV to
  `exports/` in the module's data folder and records the export.
- **Template Library** (Manager → Templates): save a key look from a key draft, a
  reward set from a pool draft, or a crate look (particles, sound, floating name)
  from a placed crate. Apply a look to a key draft or a crate, insert a set into
  a pool draft, rename or delete (to `templates/_archive/`). Applying shows what
  changes first and is refused if the target changed meanwhile. Applying a crate
  look also needs `crates.manage`.
- **Reduced motion**: `/lootbox motion [reduced|full|default]` and a Motion button
  on the Catalog. Reduced shows each reveal's result at once; the odds and the
  result never change. `opening.reducedMotionDefault` sets it for players who
  never chose.
- **Readability**: hints, notes and odds footers on the LootBox screens now read
  at 4.5:1 contrast or better. Long names shrink to fit their row instead of
  running past it; problem lists, diffs, confirmation lines, preview rules and the
  key lore preview wrap. The item picker's search box has focus when it opens. No
  LootBox window is larger than the game's own pages (1050 × 700).

### Changed

- The theme's dim text colour (section titles, key labels, counts) is a little
  lighter on every Mystic Essentials page (`#778292` → `#858e9d`), so it reads at
  4.5:1 on the window background.

### Fixed

- Text fields in the LootBox creator screens were bound to `##Id.Value`, so they
  would neither show nor send their values on a client.

### Verification

- `verifyLootBox` covers the editor's odds conversion (exact both ways where
  possible, reported rounding, refusals), the publish diff, the reward list
  operations, that the editor's chances equal the player preview's, key name
  styling (colour, bold, italic) and the key diff.
- New `verifyLootBoxDrafts` check: save/load round trips, conflict detection,
  clone and archive, and the Draft → Invalid → Ready → Published lifecycle.
- New `verifyLootBoxKeys`: the bounded grant history, the conversion saga's
  rules and recovery, and stack spending, splitting and revoking.
- New `verifyLootBoxCrates`: compatibility, repair decisions, protection and
  crate documents, including old ones.
- New `verifyLootBoxScreens`: simulation rows and warnings, audit filters,
  paging, export escaping and caps, and templates (key looks, reward sets with
  colliding ids, crate looks, optimistic saves).
- New `verifyLootBoxAccessibility`: reduced motion, the contrast of every theme
  text colour and every LootBox label's runtime colour, wrapping and fitting text,
  window sizes and focus.
- On a 0.7.2 dev server, republishing an existing pool and key still answers
  "unchanged" after the draft store change. Every creator screen, reduced motion,
  floating names and generated variants still need an in-game test.

## 1.0.6 — LootBox Module

Adds LootBox, a free module for reward pools that show their exact odds before
anything is spent, opened with signed keys from crates or a menu. It ships
disabled.

### Compatibility

- Built and verified against Hytale Server `0.7.2`; the supported range stays
  `>=0.7.0 <0.8.0`.
- The module is off by default. Enable it with `"lootbox": true` in the main
  config's `modules` map and `enabled: true` in `modules/lootbox/config.json`.

### LootBox

- Pools are authored as draft files in `modules/lootbox/lootboxes/` and frozen
  into immutable, numbered revisions with `/lootbox publish`. Publishing validates
  the whole draft and refuses it with a list of problems. `/lootbox rollback`
  republishes an older revision as a new one.
- Odds are `WEIGHTED` or `EXACT_PERCENT` (summing to exactly 100%). Previews,
  confirmations and receipts show each reward's exact chance, "1 in N" and the
  revision's odds id. A non-zero chance is never displayed as 0%.
- Keys are defined in `modules/lootbox/keys/`. Physical keys are any item carrying
  an HMAC-signed, serial-numbered marker; virtual keys are a per-player balance.
  Staff issue keys with `/lootbox key give` and can revoke a serial.
- Opening: the player confirms, then the key is spent and the result drawn with
  `SecureRandom` in a single write. A skippable reveal follows, then the reward is
  delivered. Interrupted openings finish on join and every 5 minutes, item
  deliveries that cannot be confirmed are held for `/lootbox reconcile` instead
  of being given twice, and repeated confirms are deduplicated.
- Rewards: items, economy deposits, LuckPerms permission nodes (optionally
  timed), and console commands that must match an allowlisted pattern. Items that
  do not fit go to a pending inbox (`/lootbox pending`).
- Crates: bind any block that has a Use interaction (chests, doors, benches) with
  `/lootbox crate select` and `/lootbox crate convert`. Crates are protected from
  breaking, damage and explosions. If a crate's block is replaced or destroyed,
  the crate disables itself, and staff are alerted when it is destroyed.
- New **Mystic Crate** block (`MysticEssentials_LootBox_Crate`) in the bundled
  asset pack, for builders who want a dedicated crate. The module logs at startup
  whether it loaded.
- Player screens: catalog, preview, confirmation, reveal, history and pending
  inbox. Staff get a manager screen with Publish, Close/Reopen and Simulate.
- `/lootbox simulate` draws up to a million times without awarding anything and
  compares expected with observed rates.
- The `FREE_GAMEPLAY_ONLY` policy profile (the default) refuses key grants marked
  `PAID`.
- Hourly audit counters are kept for `audit.retentionDays` (400).
- `LootBoxApi` (`getModule("lootbox")`) and `MysticEvent`s for addons.
- New permissions under `mysticessentials.lootbox.*`; see `PERMISSIONS.md`.

### Core API

- `PermissionService` gains `canGrant()` and `grant(player, node, duration)`,
  backed by LuckPerms. The LuckPerms node classes load only when LuckPerms is
  present, so servers without it are unaffected.

### Verification

- New `verifyLootBox` check: odds resolution, formatting, pick boundaries, a
  chi-square test over a million draws, key markers, command templates, the
  recovery decision table and the draft validator.
- New `verifyLootBoxSaga` check, run over an in-memory store:
  - a confirmation is refused if the odds were republished after the preview;
  - a doubled confirmation produces a single opening;
  - multi-use keys spend one use per opening;
  - virtual balances never go below zero under concurrent openings;
  - after a crash at each saga step, recovery delivers exactly once.
- Every new UI document passes `validateUiDocuments`.
- On a 0.7.2 dev server, from the console: publishing, simulating, rolling back,
  closing and reopening pools, publishing keys, and reloading after a restart.
  Player flows (screens, keys, crates) still need an in-game test.

## 1.0.5 — Hytale 0.7.0

Mystic Essentials 1.0.5 moves to Hytale Server 0.7.0, whose chunk and spawn APIs
changed shape.

### Compatibility

- Built and verified against Hytale Server `0.7.0`.
- Supports Hytale Server `>=0.7.0 <0.8.0`; 0.6.x servers stay on 1.0.4.
- Compiles against the MysticIdentity API `0.1.1`.

### Hytale 0.7.0 Migration

- `/tp world` and world portals resolve spawn points through the asynchronous
  spawn-provider API. A portal applies its teleport on the player's world thread
  once the point resolves.
- `/top` and random-teleport ground sampling read column heights from the chunk's
  `HeightmapColumn` component and blocks from chunk-section components, replacing
  the removed `WorldChunk#getHeight` and `BlockChunk#getBlock`. The height is the
  same opacity top as before, so destinations do not move.
- Event-title notifications use the `EventTitleStyle` overload instead of the
  deprecated `isMajor` flag.

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
