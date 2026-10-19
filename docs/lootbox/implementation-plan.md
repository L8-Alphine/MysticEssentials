# LootBox Module — Implementation Plan (Phases 0–4)

| | |
|---|---|
| Spec | *MysticEssentials LootBox Master Design Bible v1.0* (18 Oct 2026) — referred to below as "the bible", sections as §NN |
| Engine baseline | **Hytale release 0.7.2.** Engine facts were checked against the 0.7.2 source with Hytale Workshop on 18 Oct 2026; each one names the class it came from |
| Repo baseline | `update/hytale-0.7.0` @ `b8c9ea0` (still compiles against 0.7.0 — see Phase 0) |
| Status | Phase 0 done (server side). Phase 1 built on `feature/lootbox-module` and checked from the console; in-game pass pending (see §6, "Phase 1 status") |
| Sizing | S ≈ up to 1 day · M ≈ 2–4 days · L ≈ 1–2 weeks of focused work. Rough, for ordering the work only |

## Contents
1. [Summary](#1-summary)
2. [Verified engine facts and changes from the bible](#2-verified-engine-facts-and-changes-from-the-bible)
3. [Architecture](#3-architecture)
4. [Core designs (shared by all phases)](#4-core-designs-shared-by-all-phases)
5. [Phase 0 — Prerequisites](#5-phase-0--prerequisites)
6. [Phase 1 — Core Engine](#6-phase-1--core-engine)
7. [Phase 2 — Full Creator](#7-phase-2--full-creator)
8. [Phase 3 — Advanced Behaviour](#8-phase-3--advanced-behaviour)
9. [Phase 4 — Network and Analytics](#9-phase-4--network-and-analytics)
10. [Spikes](#10-spikes)
11. [Acceptance and failure matrices](#11-acceptance-and-failure-matrices)
12. [Risks](#12-risks)
13. [Decisions](#13-decisions)
14. [Bible coverage checklist](#14-bible-coverage-checklist)

---

## 1. Summary

Most of the bible can be built as written. Four parts have to change to fit the engine and this codebase:

1. **A plain block can't become a crate on its own.** 0.7.2 fires `UseBlockEvent.Pre` only for blocks whose asset declares a `Use` interaction. So there are three kinds of crate: interactive blocks (chests, doors, benches) convert directly; crate blocks shipped in the asset pack cover "place new"; decorative blocks get swapped for a generated look-alike that has the interaction (Phase 2, if spike S2 passes).
2. **Key lore is one rich-text `Description`, not a list of lines.** `ItemDisplayMetadata` only has `Name` and `Description`.
3. **There are no multi-document transactions.** `StorageService` is a key-value store that serializes updates per key. The opening is therefore a recoverable saga, and its commit point is the single document write that spends the key and records the outcome. The bible allows a saga in §12.
4. **UI files follow the repo's layout.** They go in flat `Common/UI/Custom/MysticEssentials/LootBox*.ui` files built on `MysticTheme.ui`, not in the bible's nested `Custom/LootBox/Admin/...` folders.

| Phase | Ships | Release gate (bible §20) | Size |
|---|---|---|---|
| 0 Prerequisites | Bump the build to 0.7.2, settle the open decisions, start spikes S1–S7 | Decisions D1–D8 answered; S2, S3, S6 and S7 have results | S |
| 1 Core Engine | JSON draft authoring with publish and rollback, exact odds, ITEM/COMMAND/ECONOMY/PERMISSION rewards, serialized physical keys and virtual keys, the opening saga with recovery, crates on interactive blocks and shipped crate blocks, all player screens, audit | "Can publish and open a transparent pool safely" | 3 × L |
| 2 Full Creator | Every in-game admin editor, key name and lore colour editor, crate selection tool and visuals, generated block variants (needs S2), simulation, audit and template screens, opt-in stackable keys | "Builders can operate entirely in-game" | 3 × L |
| 3 Advanced Behaviour | Bundles, bonus rolls, pity, duplicate protection, scheduled revisions, eligibility conditions, provider API, Mystic integrations, native custom key items (needs S1) | "Player-specific odds remain exact" | 2 × L |
| 4 Network and Analytics | Multi-server authority, cross-server locks, shared ledger, statistics, anomaly detection, retention tooling | "No double spends under partition/failover tests" | 2 × L |

---

## 2. Verified engine facts and changes from the bible

### 2.1 Engine facts (0.7.2)

| Topic | Fact | Source | Effect on the plan |
|---|---|---|---|
| Block use event | `UseBlockEvent.Pre` is only built after `blockType.getInteractions().get(type)` returns non-null. A block with no interaction for that type returns `Failed` first, so no event fires | `UseBlockInteraction#doInteraction` | The three crate kinds in §4.6 |
| Cancelling use | `UseBlockEvent.Pre implements ICancellableEcsEvent`. Cancelling it fails the native interaction, so a chest won't open | `UseBlockEvent` | A crate cancels the native use and opens its preview instead |
| How use is dispatched | Sent through `commandBuffer.invoke(ref, event)`, so it needs an `EntityEventSystem<EntityStore, UseBlockEvent.Pre>` | same | `CrateUseSystem`, registered once (the `PortalBreakSystem` pattern) |
| Player break | `BreakBlockEvent extends CancellableEcsEvent` and is caused by an entity | `BreakBlockEvent` | Protects crates from players |
| Environmental break | `EnvironmentBreakBlockEvent` can't be cancelled and fires after the block is already gone. It reaches a `WorldEventSystem` on `EntityStore` | event Javadoc; `TriggerVolumeBlockEventSystems.EnvironmentBlockBroken` | Disable the crate and alert staff; never restore the block blindly |
| Block damage | `DamageBlockEvent extends CancellableEcsEvent`, with `setDamage(float)` and `getTargetBlock()` | `DamageBlockEvent` | Crates take no damage. It's not used for selection; see "Picking a block" |
| Per-item name and lore | `ItemDisplayMetadata` holds `Name` and `Description`, both `Message`, under metadata key `"ItemDisplay"` | `ItemDisplayMetadata` | Lore is one multi-line Description |
| Writing item metadata | `withMetadata(KeyedCodec<T>, T)`, `getFromMetadataOrNull(KeyedCodec)`, `new ItemStack(id, qty, dur, maxDur, BsonDocument)` | `ItemStack`; repo `platform/ItemStackMetadata` | The key's identity marker |
| Stacking | `isStackableWith` compares metadata | `ItemStack#isStackableWith` | Serialized keys never stack |
| Registering assets at runtime | `AssetStore.decode(pack, key, bson)` resolves `Parent`. `loadAssets(pack, list, query, force)` registers assets live (the Asset Editor and `BlockType#getBlockIdOrUnknown` both do this). **Ran on a dev server (Phase 0):** a variant registers live in about 40 ms with every checked field inherited and its inline `Use` root resolving. A minimal `Parent` + inline document written to a directory pack in `mods/` loads at boot. `writeAssetToDisk` writes a flattened asset with no `Parent` that **fails validation at boot**, and it doesn't create folders | `AssetStore`; [phase0-spike-results.md](phase0-spike-results.md) §1 | Generated variants use the RAW design (results §3). Native key items (S1) should work the same way |
| Writing blocks | `BlockOperations.setBlock(chunkStore, sectionRef, x, y, z, id, type, rotation, filler, settings)`; read with `BlockSection.get / getRotationIndex / getFiller`. Chunk saves store block **keys**, so a missing variant loads as an "unknown" placeholder that keeps its key | `BlockOperations`, `BlockSection#deserialize`, `PrefabUtil#paste` | Swapping a block keeps its rotation; a variant whose asset is missing never damages the save |
| Picking a block | `TargetUtil.getTargetBlock(ref, maxDistance, accessor)` raycasts along the look direction and works on every block. `DamageBlockEvent` is **skipped** for blocks without `Gathering` and for hits with no tool power (bare hand or weapon on stone). Creative-mode breaks fire `BreakBlockEvent` for any block | `TargetUtil`, `BlockHarvestUtils#damageSingleBlock`, `BreakBlockInteraction` | Crate selection is a raycast (§4.6) |
| Explosion damage | Damage with no instigator fires a cancellable `DamageBlockEvent` at **world level** before any damage. The final no-instigator break is the non-cancellable `EnvironmentBreakBlockEvent` | `BlockHarvestUtils#damageSingleBlock`, `#performBlockBreak` | A world-level system protects crates from unattributed explosions (§4.6) |
| Interaction codecs | Must be registered in the plugin's `setup()` before assets are parsed | `MysticessentialsPlugin#setup` (`MysticPortal`) | `MysticCrate` is registered there |
| ECS systems | Keyed by class, with no unregister. Register once and check whether the module is on | `HytalePlatform#registerEntitySystem` | Every crate system checks `LootBoxModule.active()` |
| Giving items | `Player.giveItem(stack, ref, store).getRemainder()` | `KitModule`, `MailModule` | Overflow goes to the pending inbox, not the ground |
| Inventory slots | `InventoryComponent.{Hotbar,Storage,Backpack,…}` containers with `setItemStackForSlot` | `InventoryModule`, `MailModule` | Keys are removed on the world thread |
| Live UI updates | `CustomUIPage#sendUpdate(UICommandBuilder)` updates a page that is already open | `CustomUIPage`; repo `LayoutPage` | Reveal animation frames without rebuilding the page |
| UI widgets | The client registers `ColorPickerDropdownBox`, `NumberField`, `MultilineTextField`, `DropdownBox`, `ItemGrid`, `ItemSlotButton`, `TabNavigation`, `ReorderableList`, `ItemPreviewComponent`, `ProgressBar` and `TimerLabel` | Workshop UI tag registry (recovered from the **0.5.6** client) | Admin editors. Check them in the 0.7.2 client (S3) |
| Particles | `ParticleUtil.spawnParticleEffect(name, pos, List<Ref> players, accessor)` | `ParticleUtil` | Reveal effects shown only to the players you pick |
| Floating labels | A holder entity with `Nameplate` and `DisplayNameComponent` | `PrefabPathSystems.NameplateHolderSystem` | Crate names (S4) |
| Storage | Key-value documents. `update()` serializes read-modify-write per key; no transaction spans keys | `StorageService` | Saga with a single-document commit point |
| Economy | `EconomyService.deposit(UUID, double)` returns a boolean and takes no idempotency key | `EconomyService` | ECONOMY rewards record the attempt and go to review if the outcome is unknown |
| Permissions | `PermissionService` can check and read limits, but can't grant | `PermissionService` | Phase 1 adds a LuckPerms grant method (§6, P1-WP7) |
| 0.7.0 → 0.7.2 | 3 methods added and 17 modified (QUIC transport, spawn markers). The `Interaction#handle` text is identical in both versions | `diff_hytale_versions` | Bumping the version is the only compatibility work |

**Not yet verified** (needs the game client; see the checklist in [phase0-spike-results.md](phase0-spike-results.md) §7): whether the client renders a variant registered mid-session identically and fires `UseBlockEvent.Pre` on it, which of the eight admin widgets render in 0.7.2, how the client handles frequent `sendUpdate` frames, the smallest set of components a nameplate holder entity needs (S4), and which events fire and player-caused explosions produce (S5).

### 2.2 Changes from the bible

| Bible | Change | Why |
|---|---|---|
| §09 "almost any individual block" | Three crate kinds: **INTERACTIVE** (direct), **SHIPPED_BLOCK** (place new) and **GENERATED_VARIANT** (swap for a look-alike, Phase 2, needs S2). Until S2 passes, plain blocks are refused with a clear message | The use event only fires for blocks that have interactions |
| §08 multi-line lore | Lore lines are joined with `\n` into one `Description` Message; each span keeps its own colour | Shape of `ItemDisplayMetadata` |
| §08 key authenticity | Add an HMAC signature to the marker as an extra layer. The serial ledger is still the real authority | Any tool that writes item BSON could otherwise forge a marker |
| §12 "one local transactional boundary" | A saga whose commit is one document write that spends the key and records the outcome | No multi-document transactions |
| §13 relational tables | The logical tables become `StorageService` namespaces, which work on both the JSON and SQL providers. Phase 4 may add an indexed SQL ledger | The existing storage layer |
| §11 asset folders | `Common/UI/Custom/MysticEssentials/LootBox*.ui` on top of `MysticTheme.ui` | Repo convention and the `validateUiDocuments` build check |
| §04 permission names | `mysticessentials.lootbox.*` constants in `api/Permissions.java`, documented in `PERMISSIONS.md` | Repo convention |
| §06 ECONOMY through MysticEconomy | Phase 1 uses Essentials' `EconomyService`, so any installed provider works. The typed MysticEconomy adapter comes in Phase 3 | MysticEconomy has no visible idempotent deposit |
| §05 in-game wizard from the start | Phase 1 authors pools in JSON draft files and publishes with commands. The in-game editor is Phase 2 | Matches the bible's own phase gates |
| §18 package layout | Mapped onto the playervaults layout (`api/command/config/model/service/storage/ui`) plus `odds/`, `validate/`, `reward/` and `hytale/` | Repo convention |

---

## 3. Architecture

### 3.1 Module shape

- `LootBoxModule extends AbstractMysticModule` with id `lootbox` and name "LootBox". It ships **disabled**: it has to be switched on both in the main config's `modules` map and in `modules/lootbox/config.json` (`enabled: false`), the same as playervaults.
- It's registered in `ModuleBootstrap` and implements `LootBoxApi`, which addons reach through `getModule("lootbox")`, the same pattern as `PlayerVaultModule`.
- The `MysticCrate` interaction codec is always registered in `MysticessentialsPlugin#setup()` and does nothing until the module is on, like `MysticPortal`.
- The ECS systems are registered the first time the module enables and stay inert while it's off.
- It's a free module (D1): no `licensedFeature()` override, so it runs on every server with no license.

### 3.2 Package layout

```
modules/lootbox/
├── LootBoxModule.java              lifecycle and wiring; implements LootBoxApi
├── api/                            stable, addon-facing
│   ├── LootBoxApi.java             preview/open/receipt, grantKey, register*Provider
│   ├── RewardProvider.java         validate/describe/grant/reconcile + DeliveryGuarantee
│   ├── ConditionProvider.java      (Phase 3)
│   ├── GrantSource.java            ADMIN_GRANT, GAMEPLAY, QUEST, API, PAID
│   ├── results/                    PreviewResult, OpenResult, Receipt, GrantResult …
│   └── event/                      §4.9
├── model/                          immutable records (Definition, Revision, RewardEntry, RewardPayload*,
│                                   KeyDefinition, KeySerial, PlayerLootState, CrateInstance, Opening,
│                                   Fulfillment, PendingReward, AuditEvent, PolicyProfile)
├── odds/                           OddsEngine, Rational, OddsSnapshot, OddsFormatter, SnapshotHasher, SecureDraw
├── validate/                       DraftValidator, PolicyValidator, CommandTemplate
├── service/                        LootBoxRegistry, KeyService, CrateService, OpeningService, RecoveryService,
│                                   RewardExecutionService, PendingRewardService, AuditService,
│                                   SimulationService, PlayerLanes
├── reward/                         ItemReward, CommandReward, EconomyReward, PermissionReward,
│                                   (P3) BundleReward, ExtensionReward
├── storage/                        LootBoxStorage, one repository per aggregate, SchemaMigrations
├── hytale/                         engine adapters
│   ├── CrateInteraction.java       "MysticCrate" (SimpleInstantInteraction)
│   ├── CrateUseSystem.java         EntityEventSystem<UseBlockEvent.Pre>
│   ├── CrateBreakSystem.java       EntityEventSystem<BreakBlockEvent>
│   ├── CrateDamageSystem.java      EntityEventSystem<DamageBlockEvent>
│   ├── CrateWorldDamageSystem.java WorldEventSystem<DamageBlockEvent> (explosions)
│   ├── CrateEnvironmentSystem.java WorldEventSystem<EnvironmentBreakBlockEvent>
│   ├── KeyItemFactory.java         ItemStack + ItemDisplayMetadata + marker
│   ├── InventoryAdapter.java       find/remove key stacks, give with remainder
│   ├── CrateVisuals.java           (P2) nameplates, particles, sounds
│   └── GeneratedAssetService.java  (P2/P3) block variants, native key items
├── command/                        LootBoxCommand and its sub-commands
├── config/                         LootBoxConfig, CommandAllowlist, DisplayConfig
└── ui/                             LootBoxUiController, player pages (P1), admin pages (P2)
```

**Rule:** `model/`, `odds/`, `validate/`, `reward/` and the logic in `service/` never import `com.hypixel.*`. That keeps them unit-testable without the server jar, which is where the money-like logic lives.

### 3.3 Threading

- Inventory, block checks, opening and updating UI, and particles run on the player's world thread. Interaction and system callbacks are already on it; anything else goes through `world.execute`.
- Storage I/O is asynchronous (`CompletableFuture`) and never blocks the world thread.
- **PlayerLanes** gives each player UUID one serial executor (striped and bounded). Every opening, key debit, grant and inbox claim for that player runs in order. Together with the per-key ordering of `StorageService.update()`, that removes races on a single server. Two players on the same crate (LB-012) are independent by construction, because a crate holds no per-opening state.
- Simulations run on an asynchronous worker, with a trial cap and cancellation.

### 3.4 Storage namespaces

| Namespace | Key | Document | Notes |
|---|---|---|---|
| `lootbox_def` | lootboxId | status, activeRevision, revision history, disabled flag, updatedBy | Small and mutable |
| `lootbox_rev` | `{id}@{rev}` | Full immutable spec, `contentHash`, publisher, publishedAt, startsAt/endsAt | Never rewritten |
| `lootbox_keydef` | keyId | KeyDefinition plus version history | |
| `lootbox_serial` | serial (128-bit, base32) | keyId, defVersion, owner, state, usesLeft, reservedBy, redeemedBy{openingId, outcomeId, snapshotHash}, source | **Commit point for physical keys** |
| `lootbox_player` | player UUID | Virtual balances, version, commits[] (bounded), seen requestIds (dedupe window), per-pool state {pity, lastOpen, owned}, receipt index, activeOpening | **Commit point for virtual keys** |
| `lootbox_open` | openingId | The opening: state, revision, snapshot, roll, outcome, debitRef, fulfillments[] | Projection used for recovery and receipts |
| `lootbox_pending` | player UUID | Pending rewards [{fulfillmentId, item snapshot, createdAt, expiresAt}] | Inbox |
| `lootbox_crate` | crateId | CrateInstance | Loaded in full into the position index |
| `lootbox_audit` | `{yyyyMMddHH}-{serverId}` | Append-only event list | Bucketed by hour so retention can drop whole buckets |
| `lootbox_meta` | fixed keys | schemaVersion, active HMAC key id | The secret itself is a file in the data folder |

Every document carries a `schema` integer, and `SchemaMigrations` upgrades it when read, like `ConfigManager` does for configs.

**Drafts** are files: `modules/lootbox/lootboxes/<id>.json` and `modules/lootbox/keys/<id>.json`. Operators can edit them by hand and keep them in git, and they match how kits and warps are configured. Only **published revisions** live in storage. The Phase 2 editor writes the same draft files.

### 3.5 Identifiers

| Id | Format |
|---|---|
| lootboxId | `[a-z0-9_]{3,48}` |
| keyId | `ns:name`, both `[a-z0-9_]+`; default namespace `mystic` |
| rewardId | unique within a definition |
| crateId | `[a-z0-9_]{3,64}`, or generated |
| openingId, serial | 128 random bits from `SecureRandom`, base32 |
| fulfillmentId | `{openingId}#{index}` |
| requestId | Generated by the server for each confirm screen it renders, embedded in that screen's event data, and checked against the UI session. A double click on one screen therefore deduplicates |

---

## 4. Core designs (shared by all phases)

### 4.1 Definition lifecycle and publishing

```
draft file ──validate──▶ READY ──publish──▶ PUBLISHED(rev N) ──publish──▶ SUPERSEDED
     │                     │                     │
     └──▶ INVALID          │                     ├──▶ DISABLED (opens blocked; previews and receipts stay)
                           │                     └──▶ ARCHIVED (soft delete)
rollback(rev K) = publish a copy of rev K's spec as rev N+1, with a fresh contentHash and a diff
```

- **Publish** validates the draft, copies it into an immutable `lootbox_rev` document, computes `contentHash` (SHA-256 of canonical JSON), and switches `activeRevision` in one write to `lootbox_def`. It records the publisher, the time and a diff against the previous revision. Openings already committed keep pointing at their own revision.
- **Validation gates** (§05):
  - Ids are unique and well-formed.
  - At least one outcome is eligible for each player class allowed to open.
  - EXACT_PERCENT entries sum to exactly 100%, or WEIGHTED entries are all positive.
  - No negative, NaN or infinite values.
  - No outcome displays as 0%.
  - Every base item and asset exists in the registry.
  - COMMAND templates match the allowlist.
  - Every reward provider is present and can deliver safely.
  - Every linked key definition is published and compatible.
  - The policy profile check passes.
- **Scheduled revisions** (Phase 3) add `startsAt`/`endsAt`. The preview shows the start date and any time-limited odds.
- **Cloning** copies a definition's editable content into a new draft with a new id. It never copies revision numbers, serials, crate ids or opening history (§05).
- **Simulations and previews name their revision.** A simulation of an unpublished draft is labelled "draft", so its results can't be mistaken for those of a published revision (§05).

### 4.2 Odds engine

- **Arithmetic.** Weights are positive `long`s. In EXACT_PERCENT mode, admins type decimal strings with up to 6 decimal places, which are scaled by 10⁶ into integer weights that must sum to exactly 100 × 10⁶. Each probability is an exact `Rational(w, W)` built on BigInteger. Floating point is only used when formatting.
- **Formatting** (§07):
  - Start at `minDecimals` (2) and add decimal places until the value shows at least 2 significant digits, up to `maxDecimals` (8).
  - A non-zero chance is **never** shown as `0.00%`. If it would still round to zero at the cap, show `<0.00000001%` with the exact form "1 in 1,234,567,890" next to it.
  - If the rounded lines don't add up to 100.00%, show a footnote explaining the rounding. The real probabilities are never adjusted to make the display add up.
  - The formatter takes a `Locale` (`display.locale` in config, or the player's locale if the engine exposes it), so decimal separators match the language without losing precision (§11).
- **Snapshot hash** (`oddsRevisionHash`): SHA-256 of canonical JSON containing `{lootboxId, revision, contentHash, eligible ids with effective weights sorted by id, bonus distributions, modifier state (pity and duplicate exclusions), policyProfile}`. The first 8 hex characters appear as "Odds ID" in the screen footer and on receipts.
- **Draw:** `SecureRandom` used as a `RandomGenerator`. `nextLong(W)` is unbiased by the `RandomGenerator` contract on Java 17 and later (the project uses 25). Walk the cumulative weights in a stable order sorted by id. Record `roll` and `W` on the opening so anyone can audit it. Tests inject a seeded generator; production never seeds.
- **Simulation** (§07): runs asynchronously with a cap (`simulation.maxTrials`, default 1,000,000). It shows theoretical against observed frequencies and warns when the rarest outcome's expected count is under 10. It never awards anything, and it uses the same `OddsEngine`.
- **Chance of getting item X** (Phase 3): sum the exclusive one-of-N outcomes that contain X, then combine independent bonus rolls as `1 − Π(1 − pⱼ)`, exactly.

### 4.3 The opening saga

**States:** `REQUESTED → VALIDATING → PREPARED → RESERVED → COMMITTED → DELIVERING → DELIVERED → ACKNOWLEDGED`. Side exits: `STALE` (the snapshot changed; nothing happens), `DENIED` (not eligible), `ABORTED` (failed before commit; any key reservation is released) and `REVIEW` (a delivery outcome is unknown).

**Commit point: one document write that spends the key and records the outcome.**

- *Virtual key:* one `update(lootbox_player/<uuid>)` checks the balance and that `commits[]` doesn't already contain this openingId. It then subtracts from the balance, appends `{openingId, lootboxId, rev, outcomeId, snapshotHash}`, and updates pity, owned flags and lastOpen, all in the same write.
- *Physical serialized key:* one `update(lootbox_serial/<serial>)` moves the serial from `RESERVED(openingId)` to `REDEEMED{openingId, outcomeId, snapshotHash}`. Multi-use keys subtract one use and go back to `ISSUED` while uses remain. Pity and the receipt index are then applied to the player document in a follow-up step keyed by openingId, which recovery repeats safely.

**Steps for a physical key** (§12):

| # | Where | Step |
|---|---|---|
| 1 | Lane | Deduplicate `requestId`, returning the existing opening if seen. Refuse if the player has an unfinished opening; recover it instead |
| 2 | Lane | Load the active revision. Check the policy, eligibility, cooldown and limits. Recompute the snapshot. If its hash differs from the UI session's, return `STALE` and show the preview again |
| 3 | World | Find the key stack: valid marker, valid HMAC, serial `ISSUED`, compatible with this pool |
| 4 | Lane | Draw the outcome. Write the opening as `PREPARED` and wait for the write. Set `player.activeOpening` |
| 5 | Lane | Serial `ISSUED → RESERVED(openingId)`. If the serial wasn't `ISSUED`, abort with "key already used" |
| 6 | World | Remove that exact stack, checking the slot still holds the same serial. If the key moved, search again. If it's gone, abort and release the reservation |
| 7 | Lane | **Commit:** serial `RESERVED → REDEEMED`. Mark the opening `COMMITTED`, update pity and receipts, emit `OpeningCommitted` |
| 8 | Lane/World | Deliver the rewards (§4.4) |
| 9 | World | The reveal shows the result. The result was fixed at step 4 but is only shown after step 7 |

**Recovery for physical keys.** This runs when a player joins, before any new opening, because it needs their inventory:

| Opening | Serial | Key in inventory | Action |
|---|---|---|---|
| PREPARED | ISSUED | — | ABORT. Nothing was spent |
| PREPARED | RESERVED(op) | present | ABORT, release the reservation. The player keeps the key and gets no reward |
| PREPARED | RESERVED(op) | absent | COMMIT. The key was removed, so deliver the recorded outcome |
| any | REDEEMED(op) | present | Make sure the opening is COMMITTED and remove the dead key stack as cleanup |
| any | REDEEMED(op) | absent | Make sure the opening is COMMITTED and resume delivery |

The table also holds when the engine loses recent inventory writes in a crash. If the key comes back in the inventory, the opening aborts and nothing is lost. If the serial was already `REDEEMED`, the returned item is a dead key that can't open anything. A copied key carries the same serial, so it's rejected too.

**Recovery for virtual keys** runs when the module enables. The commit is a single document, so recovery only has to make the opening projection match `player.commits[]`.

**A periodic sweep** every 5 minutes finds openings stuck in `DELIVERING` past a threshold, retries the actions that are safe to replay, and moves the rest to `REVIEW`. A delivery is stuck when this server isn't running it (a crash ended it), or has been running it for 10 minutes (`DeliveryRules.STALLED_MS`), because the platform can drop a world task and that delivery never completes. The join-time recovery uses the same test, so a quick rejoin doesn't race a give that is still in flight.

### 4.4 Delivering rewards

Each action has `fulfillmentId = openingId#i` and moves through `PLANNED → ATTEMPTED → DELIVERED`, or ends in `REVIEW` or `FAILED`.

| Reward | How | Guarantee | If found in ATTEMPTED after a crash |
|---|---|---|---|
| ITEM to inventory | `Player.giveItem` on the world thread. Any remainder goes to the pending inbox | Once, except for a crash between giving the item and recording it | REVIEW: an admin can re-grant or dismiss it. Never replayed automatically |
| ITEM to pending inbox | `update(lootbox_pending)` adds the item only if its fulfillmentId isn't there yet | Exactly once | Safe to replay |
| PERMISSION | LuckPerms node set, optionally temporary | Exactly-once effect, since setting a node twice is harmless | Safe to replay |
| COMMAND | An allowlisted template run from the console | At most once | REVIEW, unless the operator marks the template as idempotent |
| ECONOMY (`EconomyService`) | `deposit` | At most once | REVIEW |
| ECONOMY (MysticEconomy, P3) | Deposit with a reference, checked against the ledger | Exactly once, if MysticEconomy supports references (P3-WP7) | Reconciled automatically against the ledger |
| EXTENSION (P3) | The provider declares `IDEMPOTENT`, `RECONCILABLE` or `AT_MOST_ONCE` | Whatever the provider declares | Handled according to that declaration |

**Command templates** (§06): a template is parsed into fixed text and typed placeholders: `{playerUuid}`, `{playerName}` (restricted to `[A-Za-z0-9_]`), `{quantity}` and `{openingId}`. The rendered command has to match the regex of its `allowedPatternId` in `rewards.commandAllowlist`. Only holders of `mysticessentials.lootbox.rewards.command` can author command rewards. Every run is audited.

**Announcements** (§06 Presentation): a reward can set `announce: true` with a message template. After the opening commits, the win is broadcast through `MessageService`, with optional sound and particles at the crate. A broadcast is never sent before commit, so it can't announce a result that later aborts.

**Pending inbox:** the player opens it with `/lootbox pending`. Claiming re-runs `giveItem` and puts any remainder back. Items can expire after a configurable time, and staff are warned before that happens. Optionally the reward can go to the Mail module instead (`rewards.itemOverflow: MAIL`), using its item attachments.

### 4.5 Keys

**Marker document**, stored under metadata key `mysticessentials:lootbox_key`:

```json
{ "k": "mystic:avalon_royal_key", "v": 3, "s": "J3K8QW2M…", "m": "SERIALIZED", "kid": 1,
  "h": "<base64url HMAC-SHA256(k|v|s|m)>" }
```

- **Display:** the definition at issue time renders `ItemDisplayMetadata{Name, Description}` from coloured spans, with lore lines joined by `\n`. Allowed styles are bold, italic and colour. Name and lore are **never** used to validate a key (LB-004).
- **Secret:** 32 random bytes in `modules/lootbox/secret.key`, created on first enable and kept out of `config.json`. `kid` allows rotating it. In Phase 4 the secret is shared across the network.
- **Validation order:** the marker is present → the HMAC matches → the definition is active and the version is known → the serial document exists, is `ISSUED`, has the same keyId, and belongs to this owner if the key is bound → the key is compatible with the pool → it hasn't expired.
- **Modes:**

  | Mode | Phase | Notes |
  |---|---|---|
  | SERIALIZED | P1, default | Never stacks; full anti-clone protection |
  | VIRTUAL | P1 | A balance on the player document |
  | STACKABLE | P2, opt-in | Shares a batch marker; documented as weaker against duplication exploits; recovery prefers abort |

- **Granting** (`KeyService.issue`): online players get the key in their inventory (or the inbox if it's full). Offline players get a virtual credit or an inbox entry, depending on config (D5). Every grant records a `GrantSource`, and the policy validator uses it (§4.10).
- **Identity modes:**

  | Mode | Phase | How |
  |---|---|---|
  | METADATA_TAG | P1 | Marker on a normal base item |
  | REGISTERED_CUSTOM_ASSET | P3, needs S1 | Generates an Item asset `MysticLootBox_<Key>` with `Parent: <baseItemId>`, via `AssetStore.decode` plus `writeAssetToDisk` into a writable pack. `customItemId` is only reported once `Item.getAssetMap().getAsset(id) != null` (LB-006) |

### 4.6 Crates

**Kinds:**

| Kind | Phase | How it works |
|---|---|---|
| INTERACTIVE | P1 | The block type already has a `Use` interaction (chest, door, bench, interactive modded block). `CrateUseSystem` cancels `UseBlockEvent.Pre` and opens the preview, so the chest itself never opens. Converting warns about chests (their contents are unreachable while it's a crate), doors, benches and portals |
| SHIPPED_BLOCK | P1 | The asset pack (`IncludesAssetPack: true`) ships 1–3 crate blocks with `"Interactions": {"Use": {"Interactions": [{"Type": "MysticCrate"}]}}` and a vanilla chest-style `Parent` for the model. A builder places one like any block, then binds it |
| GENERATED_VARIANT | P2, needs S2 | For a decorative block: write our own minimal `{"Parent": <original>, "Interactions": {"Use": {inline MysticCrate}}}` into a generated directory pack in `mods/`, make it live with `loadAssetsFromPaths`, swap the block with `BlockOperations.setBlock` keeping rotation and filler, and record the original key so the swap can be undone. Never use `writeAssetToDisk` (Phase 0: its flattened output fails at boot). Full design: [phase0-spike-results.md](phase0-spike-results.md) §3 |

**Dispatch** has one route, so a crate never opens twice:

```
UseBlockEvent.Pre (type == Use) ─▶ index[world][pack(x,y,z)]
    ├─ no crate ─▶ return (normal Hytale behaviour)
    └─ crate ─▶ cancel the event
          ├─ disabled ─▶ "This crate is inactive"
          ├─ block type ≠ recorded block ─▶ disable, alert staff, audit
          └─ open the preview on the world thread
MysticCrate interaction (runs only when Pre wasn't cancelled, i.e. an unbound shipped crate block)
    ├─ player has crates.manage ─▶ "Bind this crate" prompt (P1: chat with the command; P2: CrateEditor)
    └─ everyone else ─▶ "This crate isn't set up yet"
```

- **Index:** `Map<UUID world, Map<Long packedPos, String crateId>>`. It's loaded in full at enable (expected to be at most a few thousand crates) and doesn't depend on chunks loading. Block identity is checked when a crate is used. `/lootbox crate verify [world]` sweeps loaded chunks. Optional `visualAnchors[]` (P2) send several blocks to one crate for multiblock visuals.
- **Selecting a block:** `/lootbox crate select` raycasts the block the player is looking at (`TargetUtil.getTargetBlock`, which works on every block) and captures `{worldUuid, x, y, z, blockTypeId, rotation}`. `/lootbox crate convert <lootboxId> [crateId]` then creates the crate. As a convenience, staff in creative mode can also break a block during a 60-second selection window, because creative breaks fire the cancellable `BreakBlockEvent` for any block. "Hit to select" in survival was dropped: `DamageBlockEvent` is skipped for many blocks and held items (Phase 0, S6). There's also a coordinate form: `convert <lootboxId> <x> <y> <z>`.
- **Refused blocks:** air or empty, fluids, the engine's generated "unknown" placeholder blocks, and blocks without a `Use` interaction until S2 passes.
- **What a crate may override:** name, visuals and access rules only. A crate always uses its pool's published revision and can never change the odds (§03).
- **No state, no exploits:** a crate holds no keys and no rewards, so placing, breaking or moving its block can never duplicate a key or grant a reward (§09).
- **Systems are global:** the entity-store registry covers every world, so the crate systems are registered once rather than per world. The index is keyed by world UUID.
- **Protection:**

  | Event | Response |
  |---|---|
  | `BreakBlockEvent` | Cancelled for everyone; staff use `/lootbox crate remove` |
  | `DamageBlockEvent` | Damage set to 0 |
  | `DamageBlockEvent` at world level (no instigator, e.g. explosions) | Cancelled for crate blocks, before any damage lands (`CrateWorldDamageSystem`, a `WorldEventSystem`) |
  | `EnvironmentBreakBlockEvent` (can't be cancelled) | Mark `DISABLED_BROKEN`, alert online staff, audit. "Restore" only puts the original block back if the spot is empty, and otherwise refuses with "position now contains X" |
  | Builder tools and prefab pastes (no event) | Caught by the identity check on use and by the verify sweep |

### 4.7 UI

**Player screens (Phase 1).** All are `MysticPage` subclasses using `MysticTheme.ui` components and row templates appended per entry:

| File | Contents |
|---|---|
| `LootBoxCatalog.ui` + `LootBoxCatalogRow.ui` | Pools you can open, with your key counts |
| `LootBoxPreview.ui` + `LootBoxRewardRow.ui` | Every reward with icon (`ItemIcon`), name, rarity accent, **exact odds column**, your key balance, the rules ("duplicates possible · no pity · uses 1 key"), Odds ID, and the buttons [Open with ‹Key›] [Close] |
| `LootBoxConfirm.ui` | The same odds list in compact form, "Consumes 1 ‹Key›", eligibility, Confirm and Cancel. The snapshot is held in the server-side UI session; the client only echoes `requestId` |
| `LootBoxReveal.ui` | Animation area plus an **odds sidebar that stays visible**, Skip, and a result card showing Selected / Delivered / Pending in inbox / Under review, with the receipt id |
| `LootBoxHistory.ui` + row | Time, pool and revision, reward, quantity, key spent, odds at commit, Odds ID |
| `LootBoxPending.ui` + row | Unclaimed rewards, with Claim and Claim all |

**Odds are always on screen** (§07, §11): the odds column is part of every player screen's layout, never a tooltip or a separate page. Long lists scroll, but every visible row shows its odds and a "N rewards — scroll" hint. The reveal sidebar always has the full list.

**Reveal animation:** the server drives it with `sendUpdate` frames that move a highlight across the sidebar rows, slowing down over `opening.revealDurationMs` and landing on the outcome already committed. Frames are scheduled with `core.scheduler()` onto the world thread, at a rate set by S7. Skip jumps to the last frame. Disconnecting has nothing to undo. Reduced motion (stored per player) shows the result immediately.

**Double clicks and spam:** the UI session's `openingInFlight` flag, PlayerLanes and `requestId` deduplication handle them, and UI events are rate-limited per player (§17). Greying out the button is only cosmetic.

**Reconnecting mid-opening** (§10): the catalog and the reveal screen first check `player.activeOpening`. If there is one, they show the committed result and its delivery status instead of starting a new roll.

**Required on every new screen** (from the repo's UI rules):
- Each `Common.ui` and `MysticTheme.ui` parameter gets a value, including `@Text = ""`, because a missing one breaks *other* mods' UI and disconnects players.
- New dynamic label ids get an entry in `MysticPage.UI_TEXT_COLORS`.
- `validateUiDocuments` passes.
- The selector audit passes.
- One test in the client, watching the client log for "Markup Error".

**Admin screens (Phase 2):** Manager, LootBoxEditor (TabNavigation tabs: General, Rewards, Keys, Crates, Rules, Visuals, Publish), RewardEditor, ProbabilityEditor, KeyEditor, KeyManager, CrateEditor, Simulation, AuditHistory and TemplateLibrary. Details are in Phase 2.

### 4.8 Commands and permissions

| Command | Permission (`mysticessentials.lootbox.` + …) | Phase |
|---|---|---|
| `/lootbox` (catalog) | `use` | 1 |
| `/lootbox preview <id>` | `use` | 1 |
| `/lootbox history` · `history <player>` | `history` · `audit` | 1 |
| `/lootbox pending` | `use` | 1 |
| `/lootbox key give <player> <keyId> [count] [--virtual]` | `keys.give` | 1 |
| `/lootbox key revoke <serial>` · `key info <serial>` · `key list [player]` | `keys.revoke` · `keys` | 1 |
| `/lootbox publish <id>` · `rollback <id> <rev>` · `disable/enable <id>` | `publish` · `publish` · `admin` | 1 |
| `/lootbox crate select\|convert\|remove\|info\|verify\|rebind` | `crates.place` (select, convert) · `crates.manage` (the rest) | 1 (rebind in 2) |
| `/lootbox simulate <id> [rolls]` | `simulate` | 1 |
| `/lootbox reconcile <openingId> [regrant\|dismiss]` | `restore` | 1 |
| `/lootbox reload` | `admin` | 1 |
| `/lootbox admin` | `admin` (each screen and action checks again, §04) | 1 (minimal), 2 (full) |
| authoring command rewards | `rewards.command` | 1 |
| `/lootbox stats` | `audit` | 4 |

The bible's roles become suggested LuckPerms groups, documented in `PERMISSIONS.md`:

| Role | Permissions |
|---|---|
| Player | `use`, `history` |
| Builder | `crates.place` |
| Designer | `simulate`, plus draft editing (`edit`, Phase 2) |
| Publisher | `publish` |
| Auditor | `audit` |
| Administrator | `admin`, `keys.*`, `crates.manage`, `restore`, `rewards.command` |

**Every admin action is checked again on the server** inside its handler. Hiding a button doesn't count as enforcement.

### 4.9 Public API and events

`LootBoxApi`: `preview(UUID, lootboxId)`, `open(UUID, lootboxId, snapshotHash, requestId)`, `receipt(openingId)`, `grantKey(UUID, keyId, count, GrantSource, reason)`, `registerRewardProvider` / `registerConditionProvider` (P3), and `pendingRewards(UUID)`.

Events are dispatched like the ones in `playervaults/event/*`, and their payloads are immutable records:
- **Before commit, cancellable:** `OpeningRequestedEvent`.
- **Notifications only**, so listeners can't change a committed result: `LootBoxRevisionPublished`, `KeyDefinitionCreated`, `KeyGranted`, `KeyRevoked`, `CrateConverted`, `CrateRemoved`, `OpeningCommitted`, `RewardDeliveryCompleted`, `RewardDeliveryFailed`, `OddsConfigurationChanged`, `OpeningFlagged`, `PendingRewardClaimed`.

### 4.10 Policy profiles (§16)

| Profile | Behaviour |
|---|---|
| `FREE_GAMEPLAY_ONLY` (default; locked unless `allowPaidRandomProfile: true` and a publisher acknowledges) | Grants with `GrantSource.PAID` are rejected. The publish validator refuses pools that list a paid acquisition route. There's no cash-out and no trading in keys |
| `PAID_RANDOM_RESTRICTED` | Only on eligible, non-All-Ages servers. Requires age gating through MysticIdentity (P3), disclosure of expected cost, maximum guarantee spend and duplicates, purchase confirmation, and at least 12 months of odds history |
| `DISABLED` | Opening and key spending are off; previews and receipts stay readable |

**What this can't enforce:** the validator only governs grants that pass through LootBox. A store plugin that runs `/lootbox key give` from the console can't be detected. The console therefore defaults to `ADMIN_GRANT`, and the documentation tells operators of All Ages servers never to connect store deliveries to key grants (§16 "IMPORTANT DISTINCTION"). The policy text the bible cites (Operator Policies v1.1) has to be checked again against the official page before each release.

### 4.11 Audit, metrics and retention

- **Audit events:** actor, action, target, before and after hashes, time and server id, appended to hourly buckets. Each opening logs a separate event at each stage — request received, result committed, delivery attempted, delivered, and manually reconciled — so the stages are never merged (§12). A **receipt** contains openingId, time, pool and revision, reward and quantity, key spent, the delivery method, the exact probability shown at commit, and the Odds ID (§17).
- **Metrics** (in memory, shown by `/lootbox stats` in P4): previews, openings, commit latency, failures by stage, age of retries, pending inbox size, rate of key mismatches, stale confirmations, crate repairs.
- **Retention:** `audit.retentionDays` (default 400), raised to at least 365 under `PAID_RANDOM_RESTRICTED`. Raw identifiers are only visible with `audit`.

---

## 5. Phase 0 — Prerequisites

| WP | Work | Size |
|---|---|---|
| P0-1 | **Done 18 Oct.** Bumped `gradle.properties` `hytale_version` and `server_version` from 0.7.0 to **0.7.2**. The build and all 10 `verify*` checks pass, and the mod boots on a 0.7.2 dev server. The manifest range `>=0.7.0 <0.8.0` already includes 0.7.2. The branch wasn't renamed (D7) | S |
| P0-2 | **Done 18 Oct.** Decisions D1–D8 settled (§13) | S |
| P0-3 | **Server side done 18 Oct**; the in-game checklist is waiting on the client. Spikes S2, S3, S6 and S7 (§10, [phase0-spike-results.md](phase0-spike-results.md)). S1, S4 and S5 can run during Phase 1 | M |
| P0-4 | Add `docs/lootbox/` to the developer docs index. Decide whether this plan is tracked in git (today `wiki/*` is ignored, but `docs/` isn't) | S |

---

## 6. Phase 1 — Core Engine

**Goal:** an operator can author a pool in JSON, publish it, put a crate down (interactive block or shipped crate block) and hand out keys. A player can preview every exact chance, spend exactly one key, get a reward that survives a crash, and see a receipt.

| WP | Work | Files | Size |
|---|---|---|---|
| P1-WP1 | **Module skeleton:** `LootBoxModule` and `LootBoxConfig` with defaults (the config below), registration in `ModuleBootstrap`, disabled by default, default messages, `Permissions` constants, `PERMISSIONS.md` | `modules/lootbox/*`, `api/Permissions.java` | S |
| P1-WP2 | **Model and storage:** records, Gson mapping through `core.util.Json`, repositories per namespace (§3.4), `schema` field and migration scaffold, PlayerLanes | `model/`, `storage/`, `service/PlayerLanes` | M |
| P1-WP3 | **Odds engine:** `Rational`, percent-to-weight conversion, `OddsFormatter` (never 0.00%, rounding footnote), `SnapshotHasher`, `SecureDraw` | `odds/` | M |
| P1-WP4 | **Drafts, validation and publishing:** load draft files, the Phase 1 subset of `DraftValidator` (all of §4.1 except providers and conditions), publish, rollback, disable/enable, and `reload` (re-reads drafts and config only and never touches published revisions) | `validate/`, `service/LootBoxRegistry` | M |
| P1-WP5 | **Keys:** `KeyDefinition` drafts and publishing, `KeyItemFactory` (ItemDisplay, marker and HMAC), the serial ledger, virtual balances, the validation order (§4.5), `key give/revoke/info/list`, `GrantSource`, offline grants (D5) | `service/KeyService`, `hytale/KeyItemFactory`, `hytale/InventoryAdapter` | L |
| P1-WP6 | **Opening saga and recovery:** `OpeningService` (preview, commit), both commit points, `RecoveryService` (the join-time table, the enable-time virtual check, the 5-minute sweep), deduplication, cooldowns and per-pool limits, events | `service/OpeningService`, `service/RecoveryService` | L |
| P1-WP7 | **Rewards and inbox:** ITEM (stores item id, quantity, durability, metadata and quality through the `ItemStackMetadata.rebuild` pattern), COMMAND (template parser and allowlist), ECONOMY (`EconomyService`; refuses to publish if economy isn't available), PERMISSION (new `PermissionServiceImpl.grant(uuid, node, Duration)` through LuckPerms; the reward type is unavailable without LuckPerms), `PendingRewardService` with claiming | `reward/`, `service/RewardExecutionService`, `service/PendingRewardService`, `core/permission/PermissionServiceImpl` | M |
| P1-WP8 | **Crates:** `CrateInstance`, the position index, registering the `MysticCrate` codec in `MysticessentialsPlugin#setup`, `CrateUseSystem`, `CrateBreakSystem`, `CrateDamageSystem`, `CrateWorldDamageSystem`, `CrateEnvironmentSystem`, selection by raycast (plus creative-mode break capture) and by coordinates, the compatibility check, 1–3 shipped crate blocks under `src/main/resources/Server/...` (check every id with the `hytale-asset-validator` agent), `crate verify` | `hytale/Crate*`, `service/CrateService`, resources | M |
| P1-WP9 | **Player screens:** Catalog, Preview, Confirm, Reveal with result, History and Pending (§4.7), row templates, `UI_TEXT_COLORS` entries, the animation driver (S7), the reduced-motion setting | `ui/`, `Common/UI/Custom/MysticEssentials/LootBox*.ui` | L |
| P1-WP10 | **Basic admin tools:** a minimal `/lootbox admin` Manager (definitions with their status, plus Publish, Disable and Simulate buttons, each checked again on the server), text output for `simulate`, `reconcile` | `ui/LootBoxManagerPage`, `command/` | S |
| P1-WP11 | **Audit and receipts:** `AuditService` buckets, receipt index, `/lootbox history`, metric counters | `service/AuditService` | S |
| P1-WP12 | **Documentation:** `wiki/features/lootbox-module.md` (that folder is tracked), `PERMISSIONS.md`, `CHANGELOG.md`, a commands and config reference, a JSON authoring guide with the bible's §18 example ported | docs | S |
| P1-WP13 | **Tests:** unit tests (below) and the in-game pass for LB tests (§11) | `src/test/.../modules/lootbox/` | M |

**Phase 1 config** (`modules/lootbox/config.json`, generated with defaults):

```json
{
  "enabled": false,
  "policyProfile": "FREE_GAMEPLAY_ONLY",
  "allowPaidRandomProfile": false,
  "command": { "name": "lootbox", "aliases": ["lb"] },
  "opening": { "minIntervalMs": 1500, "requestDedupeMinutes": 1440, "revealDurationMs": 4000,
               "allowSkip": true, "reducedMotionDefault": false },
  "keys": { "defaultMode": "SERIALIZED", "offlineGrant": "PENDING_INBOX", "consoleGrantSource": "ADMIN_GRANT" },
  "crates": { "preventPlayerBreak": true, "zeroDamage": true, "onEnvironmentBreak": "DISABLE_AND_ALERT" },
  "rewards": { "itemOverflow": "PENDING_INBOX", "pendingExpiryDays": 30,
               "commandAllowlist": [ { "id": "examplegrant-v1", "pattern": "^examplegrant [0-9a-f-]{36} [0-9]{1,4}$" } ] },
  "simulation": { "maxTrials": 1000000 },
  "display": { "minDecimals": 2, "maxDecimals": 8 },
  "audit": { "retentionDays": 400 }
}
```

**Unit tests** (engine-free, JUnit, alongside the 9 existing tests):
- Odds: weights sum to W; exactly 100% in EXACT_PERCENT mode; rejects negative, NaN, infinite, zero and overflowing values; never shows 0.00% across random weight sets (seeded generator); rounding footnote; snapshot hash is stable and changes when any weight, eligibility or revision changes.
- Draw: chi-square check on 10⁶ seeded draws; the boundary values `roll = 0` and `roll = W − 1`.
- Saga: every row of the recovery table; deduplication; stale snapshot; double commit; multi-use key decrement; a virtual balance that never goes negative.
- Key marker: HMAC round trip, tampered fields rejected, rotation through `kid`; a renamed or relored stack still validates and a lookalike without the marker doesn't (LB-004 logic).
- Command templates: placeholder typing, escaping, allowlist rejection, injection attempts (`;`, `&&`, quotes, newlines).
- Validator: every gate in §4.1, one failing case each.

**Exit criteria:** LB-002, LB-004, LB-007 (interactive and shipped blocks), LB-008, LB-009, LB-010, LB-011, LB-012, LB-013 (for built-in types), LB-014 (grant-source path) and LB-015 pass. LB-001 passes through JSON and commands; the in-game-only version arrives in Phase 2. The build runs clean, including `validateUiDocuments`, and the in-client markup check is clean.

**Phase 1 status (19 Oct 2026):**

- **Built:** WP1–WP12. Package layout follows §3.2 with small merges: recovery lives in `OpeningService` (`recover`, `reconcile`) and reward delivery in `service/RewardService`; the crate systems are nested classes of `hytale/CrateSystems`.
- **Checked on a 0.7.2 dev server, from the console:** key and pool publishing, the "unchanged" republish, simulate (200,000 draws within 0.15 points of each expected rate), rollback, disable/enable, `key list`, `crate list`, reload from storage after a restart, and the shipped crate block loading with a resolvable `Use` root.
- **Shipped crate block:** one, `MysticEssentials_LootBox_Crate` (`Parent: Furniture_Ancient_Crate`), plus `Server/Languages/en-US/server.lang` for its name. Breaking an unbound one drops itself rather than the parent's barrel loot.
- **Config differences from the block above:** `opening.revealFramesPerSecond` and `opening.historySize` were added; `crates.zeroDamage` is `crates.preventDamage`; `keys.defaultMode`, `opening.reducedMotionDefault` and `crates.onEnvironmentBreak` aren't there (keys are always serialized in Phase 1, there is no reduced-motion setting yet, and an environment break always disables and alerts). `keys.consumeOrder`, `keys.maxGrantPerCommand`, `crates.selectReach`, `rewards.announceTemplate` and `display.locale` are extra.
- **Tests:** `LootBoxEngineTest` (`verifyLootBox`) covers odds, the formatter, pick boundaries, the hash, chi-square on 10⁶ draws, ids, command templates, key markers, the recovery table and the validator. `LootBoxSagaTest` (`verifyLootBoxSaga`) covers the saga: a confirmation of odds that were republished after the preview is refused without a single write; the same confirmation sent twice (the second queued behind the first) gives one opening and `DUPLICATE`; a 3-use key goes `ISSUED → ISSUED → REDEEMED`, one use per opening, and a copy of the spent item is refused; a virtual balance never drops below zero, with two openings on one lane or 16 commit writes racing on one document; after a crash at each step (serial reserved, key taken, serial or player-document commit, mid-delivery) recovery follows `RecoveryPolicy` and hands the reward out exactly once, or sends an interrupted ITEM to REVIEW instead of replaying it; and recovery that runs while a delivery is still between its ATTEMPTED mark and settling leaves it to finish (no REVIEW for an ITEM, no second PERMISSION grant) until it has stalled past `DeliveryRules.STALLED_MS`. `OpeningService` needs the engine, so the rules it applies now live in the engine-free `service/OpeningRules` and `service/DeliveryRules` (behaviour unchanged). The test runs them in `OpeningService`'s order through the real `PlayerLanes` and `LootBoxStorage`, over an in-memory store that orders each document's operations like `StorageServiceImpl`. The in-game pass still has to cover `OpeningService`'s own sequencing and the world-thread steps.
- **Still open:** the in-game LB pass and the client markup check (`phase0-spike-results.md` §7), and the reduced-motion setting.

---

## 7. Phase 2 — Full Creator

**Goal:** builders and designers do everything in-game (§05 wizard, §08 key wizard, §09 placement workflow).

The breakdown into buildable packages, with the first batch in detail, is in [phase2-work-packages.md](phase2-work-packages.md).

| WP | Work | Size |
|---|---|---|
| P2-WP1 | **Admin shell:** Manager (search, filter by status, Create, Clone, Archive, Logs, Keys, Crates) and LootBoxEditor (TabNavigation: General, Rewards, Keys, Crates, Rules, Visuals, Publish). It edits draft files, so publishing stays the only way into storage. Every action is checked again on the server; destructive actions and bulk grants ask for confirmation and are audited. Adds permission `edit` | L |
| P2-WP2 | **RewardEditor and ProbabilityEditor:** reward type dropdown, item picker (`ItemGrid` over the item registry with search, plus "use held item"), quantity and weight `NumberField`s, live sum and effective-odds warnings, the never-0% check, reordering with `ReorderableList` (S3) | L |
| P2-WP3 | **KeyEditor and KeyManager:** base item picker, identity mode (METADATA_TAG; native only after S1/Phase 3), name and lore editor by segment with `ColorPickerDropdownBox` and bold/italic toggles, live tooltip preview (`ItemPreviewComponent` if S3 confirms it, otherwise a rendered `Message` label), mechanics (uses, binding, expiry, compatible pools, mode), publishing, grant/revoke/bulk/history. The base item can be picked from the registry or captured from the held item (§08). Also **physical ↔ virtual conversion** (`/lootbox key deposit` / `withdraw`), done as a small saga: serial `RESERVED(conversionId)` → remove the item → serial `CONVERTED` and the balance credited in one player-document update keyed by `conversionId`. Recovery uses the same rules as §4.3. Withdrawing issues a fresh serial. Every conversion is audited (§08) | L |
| P2-WP4 | **CrateEditor and placement tool UX:** selection status, compatibility report, binding a pool and keys, behaviour (`PREVIEW_THEN_CONFIRM` by default; a `DIRECT_CONFIRM` shortcut that still shows the confirm screen with full odds), protection settings, "test in world", `rebind` and the repair wizard | M |
| P2-WP5 | **Crate visuals:** floating name through a nameplate holder (S4), idle and opening particles with `ParticleUtil`, sounds (ids checked with `list_hytale_registry sound_event`). Nameplates can't be shown per player (they're global), so this is documented as unsupported; particles can be per player. Glow (§09) has no verified engine API, so it's dropped unless S4 finds one | M |
| P2-WP6 | **Generated block variants** (needs S2): `GeneratedAssetService` (where the writable pack lives, naming, `Parent` plus interaction), swapping the block with its rotation, restoring it on unconvert, load order at startup, removing variants that are no longer used. If S2 fails, plain blocks stay refused and the docs recommend shipped crate blocks | L |
| P2-WP7 | **Simulation, AuditHistory and TemplateLibrary screens:** simulation shows theoretical against observed with a `ProgressBar` and small-sample warnings; audit history has filters and exports JSON or CSV to the data folder; the template library holds key visuals, crate looks and reward sets | M |
| P2-WP8 | **Stackable keys (opt-in):** batch marker, rules for splitting and merging stacks, spending one from a stack, documentation of the weaker guarantee | M |
| P2-WP9 | **Accessibility (§10/§19):** reduced motion per player, focus order, contrast through MysticTheme tokens, long coloured lore wrapping, narrow screens | S |

**Exit criteria:** LB-001 entirely in-game, LB-005, LB-006 (the "reports no native id" path), and LB-007/LB-008 for generated variants if S2 passed. Builders run the bible's §09 seven-step placement workflow without typing a command.

---

## 8. Phase 3 — Advanced Behaviour

**Goal:** the bible's §06–§07 advanced mechanics, with personalised odds that stay exact and disclosed.

| WP | Work | Size |
|---|---|---|
| P3-WP1 | **Provider API:** finalise `RewardProvider` and `ConditionProvider`, add `IntegrationRegistry`. If a provider disappears, the pool becomes `INVALID`, new openings are blocked, receipts and pending deliveries keep working, and nothing is ever substituted silently (§15, LB-013) | M |
| P3-WP2 | **BUNDLE rewards and bonus rolls:** one outcome can carry several guaranteed actions. Bonus rolls have their own labelled distributions ("10% chance of one additional cosmetic") with separate snapshot sections and their own UI rows | M |
| P3-WP3 | **Eligibility conditions:** permission, world or region, date window, MysticRPG level and class, MysticQuests completion. They're evaluated against the immutable revision before each roll. Each pool says whether ineligible outcomes are renormalised or block the opening, and that's disclosed | M |
| P3-WP4 | **Pity, guarantees and duplicate protection:** hard and soft pity, owned flags, a personalised "your next opening" table, a plain-language summary of guarantees, and an exact effective distribution (LB-003) | L |
| P3-WP5 | **Scheduled and seasonal revisions:** `startsAt`/`endsAt`, a visible start date, a banner for time-limited odds, scheduled rollback | S |
| P3-WP6 | **"Chance of getting X" calculator** (§4.2), shown in the preview's detail panel | S |
| P3-WP7 | **Integrations**, each behind a class-presence bridge like `ManagedAccountsBridge`, failing closed for policy checks: MysticEconomy (typed deposit adapter; **needs a MysticEconomy API change** for an external reference plus lookup by reference, a task in that repo), MysticQuests (key grants with `GrantSource.QUEST`, a condition provider), MysticRPG (XP and class rewards, conditions), MysticIdentity (managed-account and age checks for `PAID_RANDOM_RESTRICTED`), MysticModeration (`OpeningFlagged`), MysticGeneration (NPC and event rewards through `grantKey`), MysticGuilds (group eligibility as a condition provider; each member's odds stay individual and visible), PlaceholderAPI (`%mysticessentials_lootbox_keys_<key>%`, `%…_pending%`). **MysticDungeons** has no repo under `F:/Development` yet. The API covers post-boss key grants, but crates inside instance worlds built from prefabs need anchors relative to the prefab. That design waits until the mod exists | L |
| P3-WP8 | **REGISTERED_CUSTOM_ASSET keys** (needs S1): generate the Item asset, detect support at startup, report `customItemId` only once the asset really exists (LB-006, native path) | M |
| P3-WP9 | **COSMETIC and RPG reward types** through providers, with explicit handling of duplicates | M |

**Exit criteria:** LB-003, LB-006 (native path if S1 passed) and LB-013 (providers) pass, and the personalised odds shown match the server draw in property tests across pity and duplicate states.

---

## 9. Phase 4 — Network and Analytics

**Goal:** many servers share key balances and openings without ever spending a key twice (§15 MysticGate architecture).

| WP | Work | Size |
|---|---|---|
| P4-WP1 | **Where the authority lives** (D2). Recommended: the shared SQL storage provider is the source of truth for `lootbox_serial`, `lootbox_player`, `lootbox_rev` and `lootbox_open`, with Redis for locks and invalidation. Essentials' `RedisBridge` and the `PlayerVaultLockService` pattern already exist. MysticGate takes part only through what it really provides (identity and routing) | M |
| P4-WP2 | **Cross-server opening lock:** a Redis lock per player with a TTL and renewal, plus a **fencing token** written into the commit document, so a lock that went stale can't spend a key twice. Each pool has `scope: LOCAL\|NETWORK`. Network keys fail closed when the shared store or Redis is unreachable; local pools keep working | L |
| P4-WP3 | **Spreading revisions:** publishing on one server notifies the others over Redis pub/sub so they refresh. At commit, `contentHash` is checked against the shared store, and a mismatch means `STALE` | M |
| P4-WP4 | **Statistics and anomalies:** counts per pool, observed against theoretical, a binomial z-test per reward per time window, `/lootbox stats`, `OpeningFlagged` sent to MysticModeration, metrics exported to MysticWatchDog (once its API is known) | M |
| P4-WP5 | **Retention and export:** a retention sweeper, auditor exports, privacy-aware access to identifiers | S |
| P4-WP6 | **Chaos tests:** kill a server mid-commit, take Redis down, take SQL down, send duplicate packets, open on two servers at once against the same virtual balance, partition the network during step 6 | L |

**Exit criteria:** the chaos suite shows no double spends and no lost committed rewards (outcomes may end up in `REVIEW`, never wrong).

---

## 10. Spikes

| # | Question | Method | Needed by | If it fails |
|---|---|---|---|---|
| S1 | Can a key Item asset (`Parent: <base>`) be registered mid-session, given out, rendered by the client, and still be there after a restart? Which pack is writable? | `AssetStore.decode` + `loadAssets`, then `writeAssetToDisk` into a pack under the server's mods or data folder | P3-WP8 | METADATA_TAG only. LB-006 still passes, because no native id is ever reported |
| S2 | Can a BlockType variant (`Parent: <plain block>` plus a `Use` interaction) be registered, swapped in with its rotation, look identical, fire `UseBlockEvent.Pre`, and survive a restart without becoming an "unknown" placeholder? Which API writes a block in 0.7.2? | Find the block write path (`BlockSection` or chunk accessor), then test with stone, planks, a slab and a rotated log | P2-WP6 | Plain blocks stay unsupported; document the shipped crate blocks. **Phase 0: server side passes with the RAW design; the client half is pending** |
| S3 | Do these widgets exist and render in the 0.7.2 client: ColorPickerDropdownBox, NumberField, ItemGrid, ItemSlotButton, TabNavigation, ReorderableList, ItemPreviewComponent, ProgressBar? | A throwaway test page, watching the client log for Markup Error | P2 (P1 only uses widgets already proven in the repo) | TextField with hex input and a preview swatch; buttons for tabs; up/down buttons to reorder |
| S4 | What's the smallest entity with a Nameplate that floats at a block? Should it be saved with the world, or recreated from the crate index when its chunk loads? | Follow `PrefabPathSystems.NameplateHolderSystem` | P2-WP5 | No floating names; use particles and the block itself |
| S5 | Which events fire for player-caused explosions, NPC explosions, fire spread, builder tools and prefab pastes over a crate? | Instrument all four block event systems | P2 (protection claims) | Document the gaps; rely on the identity check and the verify sweep |
| S6 | Does `DamageBlockEvent` reach an `EntityEventSystem` with the player's ref for every hittable block? | Log in a test system | P1-WP8 (selection tool) | Select by coordinates, or by use on interactive blocks. **Phase 0: no, per the source. Resolved by switching selection to a raycast** |
| S7 | How many `sendUpdate` frames per second does the client draw smoothly without flicker? | A test page with 5/10/20 Hz highlight updates | P1-WP9 | A shorter, slower animation or instant reveal; the odds sidebar stays either way |

---

## 11. Acceptance and failure matrices

### 11.1 Bible acceptance tests (§19)

| Test | Phase | Verified by | Notes |
|---|---|---|---|
| LB-001 Create, edit and publish a 100% pool in-game only | 2 (JSON in 1) | In game | |
| LB-002 Every preview and opening screen shows every outcome's odds | 1 | In game plus a layout review | §4.7 rules |
| LB-003 Pity and eligibility change the next-roll odds, and they match the draw | 3 | Property tests plus in game | |
| LB-004 A renamed or reskinned base item isn't accepted as a key | 1 | Unit plus in game | HMAC plus serial ledger |
| LB-005 Coloured name and multi-line lore render correctly | 1 (from JSON), 2 (editor) | In game | One Description |
| LB-006 A native id is only reported when the asset really exists | 2 (no-native path), 3 (native) | In game | S1 |
| LB-007 One converted block works and identical blocks elsewhere don't | 1 (interactive, shipped), 2 (variants) | In game | |
| LB-008 Crates survive chunk unloads, restarts and backup restores | 1 | In game | The index doesn't depend on chunks |
| LB-009 Interact → preview → confirm spends exactly one key | 1 | In game plus unit | |
| LB-010 Cancel or failure before commit spends nothing; a repeat returns the same result | 1 | Unit (recovery table) plus in game | |
| LB-011 A full inventory sends the reward to the pending inbox | 1 | In game | |
| LB-012 Two players open one crate at once without interfering | 1 | In game | Crates hold no per-opening state |
| LB-013 A missing provider blocks new openings; receipts and recovery still work | 1 (built-in), 3 (providers) | Unit plus in game | |
| LB-014 A paid source on an All Ages server is rejected | 1 | Unit | GrantSource; limits in §4.10 |
| LB-015 The audit shows revision, exact odds and reward for every opening | 1 | Unit plus in game | |

### 11.2 Failure matrix (§19)

| Failure | Handled by |
|---|---|
| Player disconnects during the animation | The outcome was already committed. The receipt reappears on rejoin; nothing is re-rolled |
| Server crash after commit | The sweep and the join-time recovery resume delivery. Unknown outcomes go to `REVIEW` |
| Server crash before commit | The recovery table (§4.3): abort, or commit when the key provably left the inventory |
| Full inventory | The pending inbox, with a notice to the player |
| Crate block removed outside the game | `DISABLED_BROKEN`, staff alert, restore only into an empty spot |
| Pool edited during a preview | Snapshot hash mismatch → `STALE` → the preview shows again |
| Duplicated key | The serial is `REDEEMED` or `RESERVED`, or the HMAC fails → rejected and audited (`OpeningFlagged`) |
| Shared network store unavailable | P4: network keys fail closed with a safe error and nothing spent |
| Unknown result of a non-idempotent command | `REVIEW`, with `/lootbox reconcile`; never retried automatically |

### 11.3 Performance and quality gates (§19)

- Crate dispatch is O(1) (`index[world][packedPos]`) and never scans every crate.
- Reward lists page or scroll with 500 entries and stay responsive. This is part of the P1-WP9 test.
- Simulations run on an asynchronous worker with a cap and can be cancelled.
- Load test: 50 simulated openings per second across 20 players on one server (P1), and the cross-server chaos suite (P4).
- Controller, mouse and keyboard; long coloured lore; narrow screens; reduced motion (P2-WP9).

---

## 12. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| S2 fails, so plain blocks can't become crates | Low (server side passed in Phase 0) | Medium (the bible's headline feature is narrower) | Interactive and shipped crate blocks cover most uses; keep shipped crate looks in a template library |
| The client rejects assets registered mid-session | Medium | Medium | The RAW variant file is in the generated pack, so it loads at the next start anyway; show a "restart required" status in the editor |
| Operators run with `DefaultModsEnabled` off, so the generated pack doesn't load at boot | Medium | Medium | Detect it at enable and tell the operator to enable `MysticEssentialsGenerated`. Variant blocks keep their keys and come back once it's enabled |
| Lost inventory writes after a crash cause dupes or losses | Low | High | The serial state machine (§4.3) holds either way; tested in P1 recovery tests |
| Item delivery is ambiguous (crash between give and record) | Low | Medium | `REVIEW` plus a reconcile tool; never replayed automatically |
| A UI widget is missing in 0.7.2 | Medium | Low | S3 fallbacks |
| A missing UI parameter breaks other mods' screens | Low | High | The `validateUiDocuments` build gate and the selector audit |
| Policy text changes | Medium | High | Profiles are config-driven; policy is checked again before release |
| MysticEconomy lacks an idempotent deposit | High | Low | Phase 1 uses `REVIEW`; a cross-repo change in P3 |
| JSON storage is slow with many openings | Medium | Medium | Hourly audit buckets, bounded player documents, the SQL provider recommended for live servers |
| Engine API changes in 0.8 | Certain later | Medium | Engine calls are isolated in `hytale/`; run the `hytale-migrator` agent at each bump |

---

## 13. Decisions

All eight decisions were settled on 18 Oct 2026. You chose D1; D2–D8 take the recommended option.

| # | Decision | Options | Status |
|---|---|---|---|
| D1 | Free or licensed module | Free · licensed as `module.lootbox` (like customcontent) | **Decided: free.** `LootBoxModule` doesn't override `licensedFeature()`, and MysticGate's entitlements don't change |
| D2 | Where the Phase 4 network authority lives | Shared SQL + Redis inside Essentials · a new MysticGate service | **Decided: SQL + Redis.** It exists today and playervaults already uses the same lock pattern |
| D3 | How Phase 1 pools are authored | JSON drafts + commands · bring the in-game editor into P1 | **Decided: JSON drafts + commands**, as the bible's phases intend |
| D4 | Default key mode | SERIALIZED (keys don't stack) · STACKABLE | **Decided: SERIALIZED**, the bible's safe default |
| D5 | Physical keys granted to offline players | Pending inbox · virtual credit | **Decided: pending inbox**, so the player chooses when to claim |
| D6 | Command name and aliases | `/lootbox` + `/lb` · add `/crates` | **Decided: `/lootbox` + `/lb`.** Check for conflicts with installed plugins before release |
| D7 | 0.7.2 build bump and branch name | Bump on `update/hytale-0.7.0` · a new `update/hytale-0.7.2` branch | **Decided: bumped in Phase 0** on the current branch `update/hytale-0.7.0`. The branch wasn't renamed; that's still up to you |
| D8 | Ambiguous item delivery | REVIEW · redeliver automatically | **Decided: REVIEW.** Redelivering automatically can duplicate items |

---

## 14. Bible coverage checklist

| Bible § | Covered in |
|---|---|
| 01 Document control | Header |
| 02 Vision and scope | §1, phase table |
| 03 Architecture and domain model | §3, §3.4 |
| 04 Roles, permissions, commands | §4.8 |
| 05 Creator and publication workflow | §4.1, P1-WP4, P2-WP1 |
| 06 Reward editor and execution | §4.4, P1-WP7, P2-WP2, P3-WP2/9 |
| 07 Probability engine and disclosure | §4.2, §4.7, P3-WP4/6 |
| 08 Custom key creator | §4.5, P1-WP5, P2-WP3, P3-WP8 |
| 09 Block-to-crate conversion | §2.2, §4.6, P1-WP8, P2-WP4/5/6 |
| 10 Player experience | §4.3, §4.7 |
| 11 UI screen inventory | §4.7, P1-WP9, P2 |
| 12 Opening transaction integrity | §4.3, §4.4 |
| 13 Persistence and versioning | §3.4, §4.1 |
| 14 Service APIs and events | §3.2, §4.9 |
| 15 Ecosystem integrations | P3-WP7, §9 |
| 16 Policies and monetization | §4.10 |
| 17 Security and observability | §4.4 (commands), §4.5, §4.11, P4-WP4 |
| 18 Configuration and asset organization | §3.2, §6 config, §2.2 |
| 19 Acceptance tests | §11 |
| 20 Phases, risks, decisions | §5–§9, §12, §13 |
| 21 References and checklist | §2.1 sources; definition of done = Phase 2 exit plus LB-001…LB-015 |
