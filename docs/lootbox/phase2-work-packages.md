# LootBox Phase 2 — First Work Packages

| | |
|---|---|
| Plan | [implementation-plan.md](implementation-plan.md) §7 (P2-WP1 … P2-WP9) |
| Baseline | `feature/lootbox-module` @ `9c7d8c2` (Phase 1 plus the saga checks and the two recovery fixes) |
| Scope of this page | Splits Phase 2 into buildable packages and details the **first batch**: the in-game pool creator, from a blank pool to a published one. Keys, crates and visuals follow in batches 2 and 3 |

## 1. Gates and ground rules

**Gates (the in-game parts are yours to run):**

| Gate | What | Blocks |
|---|---|---|
| G1 | The Phase 1 in-game pass on the 1.0.6 jar: catalog, preview, confirm, reveal, history, pending inbox, a placed Mystic Crate, a disconnect mid-reveal, and no "Markup Error" in the client log | Nothing in batch 1 directly, but a broken Phase 1 screen would also break the editor. Fix those first |
| G2 | **S3 widget check** (`phase0-spike-results.md` §7, steps 12–14, about 10 minutes): `NumberField`, `ProgressBar`, `TabNavigation`, `ItemGrid`, `ReorderableList`, `ItemPreviewComponent`, `ColorPickerDropdownBox` | Only the *upgrades* in §4; batch 1 doesn't wait on it |
| G3 | S4 nameplate spike | Batch 3 (crate visuals) |
| G4 | S2 in-game steps 7–11 | Batch 3 (generated block variants) |

**Rules for every package:**

- **Build on widgets this repo already uses in the client:** `TextField` (54 uses), `DropdownBox`, `ColorPickerDropdownBox`, `ItemSlotButton`, and buttons with the `MysticTheme` row-selection overlay. Number inputs are `TextField`s parsed on the server; tabs are a button row. If G2 passes, swap in `NumberField`, `TabNavigation`, `ReorderableList` and `ItemGrid` behind the same element ids, so the Java code barely changes.
- **The editor writes draft files.** Pool drafts stay in `modules/lootbox/lootboxes/*.json` and key drafts in `keys/*.json`, so hand-editing keeps working. Publishing stays the only way into storage, and a published revision is never edited.
- **Every press is authorised again on the server**, and the permission node is checked again when the event arrives, because a button being visible proves nothing. Destructive actions (archive, delete a reward, publish) open a confirmation page, and every draft change is audited.
- **Unsaved input survives every rebuild.** Every button event carries the form's `@field` values, so a rebuild never drops what the player has typed. `VaultEditorUi.appendFieldDrafts` already does this. The working copy lives on the server in an editor session, not in the page.
- **Each package ships** with its `.ui` documents passing `validateUiDocuments`, new dynamic label ids in `MysticPage.UI_TEXT_COLORS`, and a clean `validate_hytale_code_refs` sweep. Engine-free logic gets a `verify*` check, and the package lists an in-game step for you to try.

## 2. Decisions

All four were settled on 19 Oct 2026 as recommended.

| # | Question | Decided | Alternative not taken |
|---|---|---|---|
| P2-D1 | Where do in-game edits go? | **Draft files on disk**, as the plan says. Simple, diffable, and hand edits still work | Drafts in storage (shared across a network), but then files and storage can disagree |
| P2-D2 | Two staff editing one draft | **Optimistic check**: an editor session remembers the draft's content hash when it loads; Save refuses if the file changed since then, and offers Reload or Overwrite (Overwrite is audited) | Last writer wins silently |
| P2-D3 | Permission for editing | **New `mysticessentials.lootbox.edit`**: create, clone, edit and archive drafts. Publishing stays on `publish`, and editing COMMAND rewards also needs `rewards.command` | Reuse `admin` for everything |
| P2-D4 | Fields the model doesn't know | **Accept the loss and document it**: Gson drops unknown fields on save, so a hand-added field disappears the first time the draft is saved in-game. The editor warns once per draft that had unknown fields | Round-trip raw JSON (more code, little gain) |

## 3. Batch 1: the in-game pool creator

Done when you can run `/lootbox admin`, then Create → fill in General → add rewards → link an existing key → Validate → Publish, and the new pool shows in `/lootbox` with the odds you entered. That needs no file edits and no commands apart from `/lootbox admin`. This is LB-001 for pools; keys are still authored as files until batch 2.

```
P2-A Draft store ─┐
                  ├─▶ P2-C Manager v2 ─▶ P2-D Pool editor shell ─▶ P2-E Rewards tab
P2-B Editor kit ──┘
```

P2-A and P2-B don't share files and can run at the same time. P2-C needs both. P2-E needs P2-D's tab shell.

**Status (19 Oct 2026):**

| Package | State | Where |
|---|---|---|
| P2-A | Done; build and code-ref sweep clean | `4462154`, merged in `533e691` |
| P2-B | Done; build, UI and code-ref sweeps clean | `b39b3d6`, merged in `6cac06f` |
| P2-C | Done; build, UI and code-ref sweeps clean, dev server boots. **Not yet tried in game** | `ef84a15` |
| P2-D | Built (not committed yet). Build, UI and code-ref sweeps clean, dev server boots. **Not yet tried in game** | `ui/editor/PoolEditorPage.java`, `OddsConverter.java`, `DraftDiff.java`, `LootBoxEditor.ui`, `LootBoxEditorToggleRow.ui` |
| P2-E | Not started; the Rewards tab is read-only until then | |

### P2-A — Draft store and lifecycle (engine-free) · M · part of P2-WP1

| | |
|---|---|
| **Adds** | `service/DraftStore.java`, generic over pool and key drafts: `list()`, `load(id)` → `Loaded(draft, hash, path)`, `save(draft, expectedHash, actor)` → `Saved / Conflict(currentHash) / Refused(reason)`, `create(id, displayName, actor)`, `clone(sourceId, newId, actor)` (copies the whole draft under the new pool id and reuses nothing from published revisions), `archive(id, actor)` (moves the file to `lootboxes/_archive/<id>-<yyyyMMddHHmmss>.json`; never deletes) |
| **Lifecycle** | `status(id)` computes the bible's states without storing them: `DRAFT` (unsaved, or never validated), `INVALID (n problems)` (fails `DraftValidator`), `READY` (valid and differs from the active revision), `PUBLISHED` (valid and its content hash equals the active revision's spec), `DISABLED`, `ARCHIVED`. `SUPERSEDED` stays a revision-level state, shown in the revision list |
| **Changes** | `LootBoxRegistry` uses `DraftStore` for reads, and a save updates the in-memory draft map straight away, with no full reload. File I/O runs on the async executor, never on the world thread. Writes go through `Json.writeFile`, which already writes a temp file and moves it into place atomically. Ids must match `LootBoxDraft` / `KeyDraft` patterns. A save never touches storage |
| **Audit** | `DRAFT_CREATE`, `DRAFT_SAVE`, `DRAFT_OVERWRITE` (after a conflict), `DRAFT_CLONE` and `DRAFT_ARCHIVE`, each with the actor and the content hash |
| **Permission** | Adds `LOOTBOX_EDIT` (`mysticessentials.lootbox.edit`) to `Permissions`, `PERMISSIONS.md` and the wiki |
| **Check** | `verifyLootBoxDrafts` over a temp folder: a save/load round trip; a conflict when the file changed underneath; refusing a bad id or a clone onto an existing id; archive moves the file and the draft leaves `list()`; status changes `DRAFT → INVALID → READY → PUBLISHED` as the draft and the active revision change |
| **In-game** | None (the Manager in P2-C exercises it) |

### P2-B — Editor UI kit · M · foundation for P2-WP1/WP2/WP3

| | |
|---|---|
| **Adds** | `platform/ui/ItemCatalog.java`: registry search moved out of `playervaults/service/VaultItemCatalog` (the vault class delegates to it, with no behaviour change). Adds an optional category filter and a "held item" lookup |
| | `modules/lootbox/ui/editor/EditorSession.java`: per-player working copy (draft, loaded hash, dirty flag, selected tab and row). Dropped on disconnect and when the module disables. One session per player |
| | `ui/editor/FormFields.java`: helpers that attach the form's `@field` values to every event, and parse them back with typed errors (`int 1–1000`, `decimal ≤ 6 places`, `id pattern`). It generalises `VaultEditorUi.appendFieldDrafts` |
| | Pages and documents: **ItemPicker** (`LootBoxItemPicker.ui` + row; search `TextField`, a result list of `ItemSlotButton` rows, "Use held item", Cancel; returns to the calling page through a callback), **ConfirmAction** (`LootBoxConfirmAction.ui`; title, body lines, confirm label, red styling when destructive; used for publish, archive and delete), and a **tab bar** fragment (`LootBoxEditorTabs.ui`: a button row with the `#Selected` overlay) |
| **Check** | `validateUiDocuments`; the vault icon picker still behaves the same (the catalog move is the only shared change) |
| **In-game** | Open the vault editor's icon search to confirm it still works. The item picker is tried in P2-C |

### P2-C — Manager v2 · S–M · P2-WP1

| | |
|---|---|
| **Changes** | `LootBoxManager.ui` and `ManagerPage` grow from Publish / Close / Simulate to the plan's Manager: a search `TextField`, a status filter `DropdownBox` (All, Draft, Invalid, Ready, Published, Disabled), and each row's status chip from `DraftStore.status`. Row buttons: **Edit**, Publish, Simulate, Close/Reopen, **Clone**, **Archive** (confirm). Header buttons: **Create** (a small id + display name form that opens the editor), Reload, and Keys / Crates placeholders (hidden until batch 2) |
| **Permission** | Edit / Create / Clone / Archive need `edit`, the existing buttons keep their nodes, and every press re-checks |
| **In-game** | `/lootbox admin`: search, filter, create `test_pool`, clone `example_supply`, then archive the clone (it disappears and its file is under `_archive/`) |
| **As built** | Six row buttons don't fit in the 900 px window, so the Manager is **master/detail**: rows are selectable (`#Selected` overlay), and one action bar under the list acts on the selected pool. **Publish** only shows for a `READY` draft and goes through `ConfirmActionPage`. **Archive** confirms as destructive and says whether the revision stays live and how many crates stay bound. **New Pool** and **Clone** share one inline form; Clone asks only for the new id. Search is the proven `$C.@HeaderSearch`. **Edit** moves to P2-D, because there is no editor to open yet. The Keys / Crates placeholders were left out rather than shipped hidden. `LootBoxUi.publish/toggle/reload` were removed, since only the old Manager used them |

### P2-D — Pool editor shell: General, Keys & Rules, Publish · L · P2-WP1

| | |
|---|---|
| **Adds** | `LootBoxEditor.ui` and `ui/editor/PoolEditorPage.java`, with tabs **General · Rewards · Keys & Rules · Publish** (Rewards is filled in by P2-E) |
| **General** | id (read-only), display name and description (`TextField`; MysticText markup with a rendered preview label), icon (opens the item picker; the swatch is an `ItemSlotButton`), mode (`WEIGHTED` / `EXACT_PERCENT` `DropdownBox`; switching converts the current numbers and says it did) |
| **Keys & Rules** | Toggle rows for every **published** key, so a pool can only link keys that exist (the validator refuses the rest anyway). Plus cooldown seconds, the extra permission node, and read-only "keys per opening: 1" (Phase 1 limit) |
| **Publish** | Live `DraftValidator` problems as a list, with a jump-to-tab button on each. A **diff against the active revision**: rewards added or removed, weight or percent changes shown as old % → new %, and whether the odds id will change. Buttons: **Save draft**, **Revert to saved**, **Publish** (confirm page repeating the diff; needs `publish`, plus `rewards.command` when the pool has command rewards) |
| **Behaviour** | Tab switches keep the working copy in the `EditorSession` and never write to disk. A dirty marker shows in the header. Closing a dirty editor asks Save / Discard / Cancel. A save conflict (P2-D2) shows Reload or Overwrite |
| **In-game** | Edit `test_pool`: change its name and icon, link `mystic:example_key`, then Save; check the file on disk. Make it invalid on purpose and check the Publish tab lists why |
| **As built** | `ui/editor/PoolEditorPage` over an `EditorSession`. Two engine-free helpers carry the logic and have checks in `verifyLootBox`: `OddsConverter` (percent → weights exact via ×10⁶ then GCD; weights → percent exact when every share terminates within 6 places, else half-up with the largest reward absorbing the remainder, and every moved reward listed) and `DraftDiff` (settings, keys, rewards added and removed, and chances compared as exact fractions, shown old % → new %). The Rewards tab is a read-only list with live chances until P2-E. Conflicts show Load From Disk / Overwrite in the footer; Overwrite asks first. Publish asks first, then saves unsaved changes before publishing the file. New Pool in the Manager now opens the editor; Close returns to the Manager with the pool selected |

### P2-E — Rewards tab (RewardEditor + ProbabilityEditor) · L · P2-WP2

| | |
|---|---|
| **List** | One row per reward: icon, name, rarity, weight or percent, **live chance** and "1 in N" computed from the working copy with `OddsEngine.resolveWeights` and `OddsFormatter` (the same code the preview uses, so the numbers match). A sum line: total weight, or "percent left: x" in `EXACT_PERCENT` mode. Up and down buttons reorder rows (`ReorderableList` after G2) |
| **Detail panel** | For the selected row: id, name, rarity, type `DropdownBox`, and the fields for that type. ITEM: item picker or held item, plus quantity. ECONOMY: amount, greyed out with the reason when no economy is installed. PERMISSION: node, plus duration in seconds, greyed out without LuckPerms. COMMAND: template, allowlisted pattern id from `rewards.commandAllowlist`, and an idempotent flag; only shown with `rewards.command`. Also the announce toggle |
| **Actions** | Add (default type ITEM, the next free id), Duplicate, Delete (confirm). Problems show inline on the field (never-0% display, bad decimal, unknown item) and also on the Publish tab |
| **Check** | Extend `verifyLootBox` with the editor's maths: the converter between modes (weights ↔ percents, exact where it terminates), and the guarantee that the list's percentages equal what the preview shows for the same draft |
| **In-game** | Build a 4-reward pool from scratch, flip it from WEIGHTED to EXACT_PERCENT and back, publish it, run `/lootbox preview test_pool`, and check every chance matches the editor |

## 4. Later batches

| Batch | Packages | Needs |
|---|---|---|
| **2: Keys and crates** | **P2-F KeyEditor + KeyManager** (P2-WP3): base item picker or held item; identity `METADATA_TAG` only; name and lore editor per segment with `ColorPickerDropdownBox` (already used in the vault editor) and bold/italic toggles; a tooltip preview as a rendered label (`ItemPreviewComponent` after G2); uses, expiry and compatible pools; grant, revoke and bulk grant (confirm, capped by `maxGrantPerCommand`) with history. Then the **physical ↔ virtual conversion** saga (`/lootbox key deposit` / `withdraw`), with its own saga check<br>**P2-G CrateEditor + placement** (P2-WP4): the bible's §09 seven steps from the Manager, covering selection status, the compatibility report, binding a pool and keys, `PREVIEW_THEN_CONFIRM` / `DIRECT_CONFIRM`, protection settings, "test in world", rebind, and a repair wizard for disabled crates | P2-A, P2-B |
| **3: Polish and engine features** | **P2-H** Simulation, AuditHistory and TemplateLibrary screens (P2-WP7; `ProgressBar` after G2, text bars before) · **P2-I** crate visuals (P2-WP5) · **P2-J** generated block variants (P2-WP6, using the RAW design in spike results §3) · **P2-K** stackable keys (P2-WP8) · **P2-L** accessibility pass (P2-WP9: reduced motion per player, focus order, contrast, wrapping, narrow screens) | P2-I needs G3; P2-J needs G4; the rest need batch 1 |

Phase 2 exit criteria (implementation plan §7) are met at the end of batch 3. Batch 1 alone meets LB-001 for pools.

## 5. Suggested order of work

1. You run G1, and G2 if you have the 10 minutes; it decides whether batch 1 starts on the nicer widgets.
2. P2-A and P2-B in parallel (separate sessions; no shared files).
3. P2-C, then P2-D, then P2-E, with an in-game check after each, since every one adds pages.
4. Batch 2 (P2-F and P2-G can run in parallel), then batch 3.
