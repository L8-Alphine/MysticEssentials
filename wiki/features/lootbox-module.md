# LootBox

LootBox adds reward pools that players open with keys. Every pool shows its exact
odds before anything is spent, every opening is drawn and committed on the server,
and every result leaves a receipt the player can look up later.

- **Pools** are authored as draft files and frozen into immutable, numbered
  revisions with `/lootbox publish`. A published revision's odds never change; a
  new publish creates a new revision with a new odds id.
- **Keys** can be physical items (any item, signed and serial-numbered so they
  cannot be forged or duplicated) or a virtual balance.
- **Crates** are blocks in the world that open a pool's preview: any chest, door,
  bench or other interactive block, or the shipped **Mystic Crate** block.
- **Rewards** are items, economy deposits, allowlisted console commands, or
  LuckPerms permission nodes.

LootBox is a free module. It ships **disabled**: set `"lootbox": true` in the main
config's `modules` map **and** `enabled: true` in `modules/lootbox/config.json`.

## Player flow

1. `/lootbox` (alias `/lb`) opens the catalog of published pools, with the
   player's key balance and pending rewards.
2. Choosing a pool, or pressing **Use** on a crate, opens its **preview**: every
   reward, its rarity, its exact chance, "1 in N", the keys it takes and the
   pool's rules. Nothing is spent yet.
3. **Open** goes to a confirmation screen that repeats what will be spent.
4. **Confirm** spends one key and draws the result in a single server-side write,
   then plays the reveal (skippable) and delivers the reward.
5. Items that do not fit in the inventory, and rewards for offline players, go to
   the **pending inbox** (`/lootbox pending`), where they can be claimed later.

The catalog also has **Motion** (full or reduced reveal, see
[Accessibility](#accessibility)) and **Deposit Held Key**, which works like
`/lootbox key deposit`.

`/lootbox history` lists the player's own receipts: pool, revision, odds id,
reward, chance and delivery status.

## Commands

| Command | What it does | Permission |
| --- | --- | --- |
| `/lootbox` | Open the catalog | `mysticessentials.lootbox.use` |
| `/lootbox preview <pool>` | Open a pool's preview | `mysticessentials.lootbox.use` |
| `/lootbox pending` | Open the pending inbox | `mysticessentials.lootbox.use` |
| `/lootbox motion [reduced\|full\|default]` | Show or set your reveal motion. Reduced shows each result at once; `default` follows the server's setting | `mysticessentials.lootbox.use` |
| `/lootbox key deposit` | Turn the key in your active hotbar slot into virtual balance | `mysticessentials.lootbox.use` |
| `/lootbox key withdraw <keyId> [count]` | Turn virtual balance into key items | `mysticessentials.lootbox.use` |
| `/lootbox crate effects [on\|off]` | Show or set crate particles and sounds for yourself | `mysticessentials.lootbox.use` |
| `/lootbox history` | Your own receipts | `mysticessentials.lootbox.history` |
| `/lootbox history <player>` | Another player's receipts | `mysticessentials.lootbox.audit` |
| `/lootbox key give <player> <keyId> [count] [virtual] [source=…]` | Issue keys | `mysticessentials.lootbox.keys.give` |
| `/lootbox key revoke <serial\|batch-…>` | Revoke an issued physical key, or every item of a stackable batch | `mysticessentials.lootbox.keys.revoke` |
| `/lootbox key info <serial\|batch-…>` / `key list` | Inspect a key serial or batch / list published keys | `mysticessentials.lootbox.keys` |
| `/lootbox key grants <keyId>` | Open the key's Grants page | `mysticessentials.lootbox.keys` |
| `/lootbox key publish <keyId>` | Publish a key draft | `mysticessentials.lootbox.publish` |
| `/lootbox publish <pool>` | Publish a pool draft as a new revision | `mysticessentials.lootbox.publish` |
| `/lootbox rollback <pool> <revision>` | Republish an older revision as a new one | `mysticessentials.lootbox.publish` |
| `/lootbox disable <pool>` / `enable <pool>` | Close or reopen a pool | `mysticessentials.lootbox.admin` |
| `/lootbox simulate <pool> [draws]` | Draw many times without awarding anything | `mysticessentials.lootbox.simulate` |
| `/lootbox crate select` | Show the block you are looking at | `mysticessentials.lootbox.crates.place` |
| `/lootbox crate convert <pool> [crateId]` | Bind the block you are looking at to a pool | `mysticessentials.lootbox.crates.place` |
| `/lootbox crate info` / `list` / `verify` | Inspect crates | `mysticessentials.lootbox.crates.manage` |
| `/lootbox crate edit [crateId]` | Open the crate editor on the crate you look at, or the one named | `mysticessentials.lootbox.crates.manage` |
| `/lootbox crate rebind <crateId> <pool>` | Bind a crate to another published pool | `mysticessentials.lootbox.crates.place` |
| `/lootbox crate remove [crateId] [force]` / `enable <crateId>` | Unbind or reactivate a crate. `force` removes a generated crate without putting its original block back, for a world that no longer exists | `mysticessentials.lootbox.crates.manage` |
| `/lootbox reconcile <openingId> <regrant\|dismiss>` | Resolve a delivery held for review | `mysticessentials.lootbox.restore` |
| `/lootbox audit` | Open Audit History | `mysticessentials.lootbox.audit` |
| `/lootbox admin` | Open the Manager: every pool with its status (Draft, Invalid, Ready, Published, Disabled), search and a status filter | `mysticessentials.lootbox.admin` |
| Manager: New Pool, Clone, Archive | Write an empty draft (it opens in the editor), copy a draft under a new id, or move a draft to `lootboxes/_archive/` (asks first; nothing is deleted) | `mysticessentials.lootbox.edit` |
| Manager: Edit | The pool editor. **General**: name (with a coloured preview), description, icon from the item picker, and odds mode; switching between weights and exact percentages converts the numbers and says if anything had to be rounded. **Keys & Rules**: link published keys, cooldown, extra permission. **Rewards**: the reward list with each reward's live chance, plus Add, Duplicate, Up/Down and Delete (asks first). The selected reward's id (fixed once published), name, rarity, weight or percent, and type: an item from the picker with a quantity, an economy amount, a permission node with a duration, or a console command with its allowlisted pattern. Each reward also has an announce switch. Changing a COMMAND reward needs `rewards.command`. **Publish**: the problems that block publishing, and every change from the live revision, chances shown old % → new %. Save Draft writes the file; if someone else changed it meanwhile, the save is refused and you choose Load From Disk or Overwrite | `mysticessentials.lootbox.edit`; Publish also needs `publish` |
| Manager: Publish, Simulate, Close/Reopen | Publish asks first and only appears when the draft is valid and differs from the live revision. Simulate opens the Simulation screen | `publish`, `simulate`, `admin` |
| Manager: Crates | The Crate Manager and crate editor (see [Crates](#crates)) | `crates.manage` or `crates.place` |
| Manager: Audit | Audit History (see [Simulation, audit and templates](#simulation-audit-and-templates)) | `audit` |
| Manager: Templates | The Template Library | `edit` |
| Key Manager: Grants | Give a key to a typed name or to online players picked from a list, look up and revoke a serial or batch, and read the key's recent history. More than one key or player asks first; a PAID source is refused and recorded | `keys` to open; `keys.give` to give; `keys.revoke` to revoke |
| Manager: Keys | The Key Manager: every key with its status and the pools it opens, search, a status filter, New Key, Clone, Archive (asks first) and Publish (asks first; keys already given out keep their look) | `keys`, `edit` or `publish` to open; `edit` for New Key, Clone, Archive, Edit; `publish` for Publish |
| Key Manager: Edit | The key editor: base item from the picker, name with a colour picker and Bold/Italic switches, five lore lines, uses and expiry, a live preview of the key item, the pools it opens, and its problems and changes. Saves like the pool editor | `edit`; Publish also needs `publish` |
| `/lootbox reload` | Reload drafts and settings | `mysticessentials.lootbox.admin` |

Every subcommand also needs `mysticessentials.lootbox.use`, which gates the
command itself. The console can run every subcommand except the ones that open a
screen or look at a block. See [Permissions](permissions) for suggested groups.

## Authoring a pool

On first enable the module writes a working example to
`modules/lootbox/lootboxes/example_supply.json` and its key to
`modules/lootbox/keys/example_key.json`. Edit the drafts, then publish:

```text
/lootbox key publish example_key
/lootbox publish example_supply
/lootbox simulate example_supply 100000
```

A pool draft:

```json
{
  "id": "example_supply",
  "displayName": "<#E7B84D>Example Supply Crate",
  "description": "A starter pool.",
  "icon": "Furniture_Crude_Chest_Large",
  "mode": "WEIGHTED",
  "keys": ["mystic:example_key"],
  "cooldownSeconds": 0,
  "permission": "",
  "rewards": [
    { "id": "copper_pickaxe", "name": "Copper Pickaxe", "rarity": "Common", "type": "ITEM",
      "weight": 4500, "item": { "itemId": "Tool_Pickaxe_Copper", "quantity": 1 } },
    { "id": "gold_bars", "name": "Gold Bars x4", "rarity": "Legendary", "type": "ITEM",
      "weight": 100, "announce": true, "item": { "itemId": "Ingredient_Bar_Gold", "quantity": 4 } }
  ]
}
```

| Field | Meaning |
| --- | --- |
| `id` | `[a-z0-9_]{3,48}`; the name used in commands |
| `displayName`, `description` | Shown in menus; MysticText markup allowed |
| `icon` | Item id shown in the catalog; blank uses the first item reward |
| `mode` | `WEIGHTED` (positive integer `weight` per reward) or `EXACT_PERCENT` (`percent` strings with up to 6 decimals that sum to exactly 100) |
| `keys` | Key ids that open this pool (one key per opening) |
| `cooldownSeconds` | Wait between openings of this pool per player; 0 for none |
| `permission` | Extra node needed to open this pool; blank for none |
| `rewards[].type` | `ITEM`, `ECONOMY` (`amount`), `COMMAND` (`command`, `allowedPatternId`, `commandIdempotent`) or `PERMISSION` (`node`, `durationSeconds`) |
| `rewards[].announce` | Broadcast the win with `rewards.announceTemplate` |

Publishing validates the whole draft (item ids exist, keys are published, weights
are valid, command templates match the allowlist, economy and LuckPerms are present
when needed) and refuses it with a list of problems if anything is wrong. Publishing
an unchanged draft reports "unchanged" instead of creating a new revision.

### Command rewards

A `COMMAND` reward runs from the console. Its template may only use the
placeholders `{playerUuid}`, `{playerName}`, `{quantity}` and `{openingId}`, and
the rendered command must fully match the pattern named by `allowedPatternId` in
`rewards.commandAllowlist`. Publishing a pool with command rewards also needs
`mysticessentials.lootbox.rewards.command`. Set `commandIdempotent` only when
running the command twice is harmless; otherwise an interrupted delivery waits for
staff review instead of being retried.

## Keys

A key draft lives in `modules/lootbox/keys/`:

```json
{
  "id": "mystic:example_key",
  "baseItemId": "Ingredient_Bar_Copper",
  "name": "<#E7B84D>&lExample Crate Key",
  "lore": ["&7Opens: &fExample Supply Crate"],
  "uses": 1,
  "expiresAfterDays": 0,
  "stackable": false
}
```

- Ids are `namespace:name`; a bare name gets the `mystic:` namespace.
- **Physical keys** are the base item carrying a signed marker with a unique
  serial. The signature uses a per-server secret in
  `data/modules/lootbox/secret.key`; keep that file private and back it up, since
  keys signed with a lost secret stop working. Serialized keys never stack.
- **Virtual keys** (`/lootbox key give <player> <keyId> 5 virtual`) are a balance
  stored with the player.
- `keys.consumeOrder` decides which kind is spent first when a player has both.
- Physical keys given to an offline player go to their pending inbox (or become
  virtual credit with `keys.offlineGrant: VIRTUAL_CREDIT`).
- **Deposit and withdraw** convert between the two. `/lootbox key deposit` takes
  the key in the active hotbar slot: a serialized key credits its remaining uses,
  a stack deposits whole (refused if its batch has fewer units left than the stack
  holds), and keys that expire can't be deposited. `/lootbox key withdraw <keyId>
  [count]` issues key items, capped by `keys.maxGrantPerCommand`; each opens once
  and never expires, and what doesn't fit goes to the pending inbox. A conversion
  is a small saga: interrupted ones are finished on join and every 5 minutes, and
  each one is audited. `keys.allowConversion: false` turns both off.

### Stackable keys

Set `"stackable": true` (or Stacking: On in the key editor) to issue a key as one
stack per grant instead of separate items. Each item of the stack opens once, so a
stackable key must have `"uses": 1`; publishing refuses more.

The guarantee is weaker than a serialized key's. The stack's batch counter decides
how many openings are left, not the item, so a copied stack item uses up the
batch rather than being refused on sight. Stacks only form when the base item's
stack size is above 1, and keys from different grants never merge. Revoking the
batch (`/lootbox key revoke batch-…`) kills every item in it.

## Crates

**From the Manager:** `/lootbox admin` → Crates opens the **Crate Manager**: every
crate with its world, position, pool and status, with search, a filter, Edit,
Disable/Enable, Remove (asks first) and Verify This World. **Convert Looked-At
Block** opens the crate editor on the block you are looking at, as steps:

1. **Block** and **Check**: the block, and the compatibility report with any
   warnings.
2. **Pool**: a published pool to open.
3. **Use**: preview first (the default), or straight to the confirm screen, which
   still lists every reward with its exact odds.
4. **Protect**: protection this crate adds; it can only tighten the server's.
5. **Visuals**: particles, opening sound and floating name.
6. **Finish**: Create (or Save), and **Test in World**, which is a real opening
   and spends a key.

A crate the server turned off shows a **Repair** step: turn it back on, use the
block that is there now, or remove it. A save is refused if someone else saved
the crate since you opened it. Converting needs `crates.place`; Edit,
Disable/Enable, Remove and Verify need `crates.manage`; binding an existing crate
to another pool needs both.

**From commands:** look at a block and bind it:

```text
/lootbox crate select
/lootbox crate convert example_supply
```

A crate block must already have a **Use** interaction. That covers chests, doors,
benches and other interactive blocks; a converted chest's own contents become
unreachable while it is a crate. Plain blocks such as stone or planks are refused.

The asset pack also ships a **Mystic Crate** block (`MysticEssentials_LootBox_Crate`,
in the Furniture containers category). Place it, then convert it like any other
block. An unbound Mystic Crate tells players it is not set up yet and tells staff
how to bind it. At startup the module logs whether the shipped block loaded.

With `crates.preventPlayerBreak` and `crates.preventDamage` on (the default),
players cannot break or damage crate blocks, and explosions are cancelled before
they hit one. If a crate's block is replaced by another block, the crate disables
itself the next time someone uses it; if the environment destroys it, the crate is
disabled and online staff with `crates.manage` are alerted. `/lootbox crate verify`
checks every crate in your world.

### Crate visuals

Each crate can have idle particles, opening particles and an opening sound. They
show only to players within `crates.effectRadius` who haven't turned them off with
`/lootbox crate effects off`. New crates get the `crates.default*` effects;
crates placed before this update keep none until you choose some.

**Floating names** (`crates.nameplates`, off by default and not yet tested in a
game client) show the crate's name, or its pool's, above it. They are the same for
every player, and the effects switch doesn't hide them. Glow is not supported.

### Plain blocks (generated variants)

With `crates.generatedVariants: true` (off by default, not yet tested in a game
client), a plain block such as stone can become a crate. The module writes a
look-alike of the block that carries a Use interaction into
`mods/MysticEssentialsGenerated/` and swaps it in, keeping the rotation. Removing
the crate puts the original block back, which needs the crate's chunk loaded. At
startup every recorded look-alike is checked and unused ones are deleted. Only
single blocks without block data, states or connections qualify. Don't edit that
folder, and make sure `DefaultModsEnabled` allows it to load at boot.

## Simulation, audit and templates

- **Simulation** (Manager → Simulate): runs 100,000 draws straight away (or
  `simulation.maxTrials` if lower), then any number up to that cap. Each reward
  shows its exact chance, the share it actually got, the count against the
  expected count, and the gap, coloured by how unusual it is. If the rarest reward
  is expected fewer than 10 times, it says how many draws are needed. Nothing is
  awarded, spent or recorded, and each player runs one simulation at a time.
- **Audit History** (Manager → Audit, or `/lootbox audit`): filter by pool or
  target, actor or player (a name is looked up), action or group, and time window;
  10 records a page, newest first. **Export** writes the filtered records to
  `exports/audit-<yyyyMMdd-HHmmss>.json` or `.csv` in the module's data folder
  (times in UTC; CSV escapes values that look like spreadsheet formulas) and
  records the export. Reading is bounded: one hour of records at a time, at most
  744 hours a page, and 100,000 records an export.
- **Template Library** (Manager → Templates): save a **key look** (base item,
  name, lore, uses, expiry) from a key draft, a **reward set** from a pool draft,
  or a **crate look** (particles, sound, floating name) from a placed crate. Apply
  a look to a key draft or a crate, insert a set into a pool draft (reward ids
  already in the pool get new ones), rename, or delete (the file moves to
  `templates/_archive/`). Applying shows what changes first and is refused if the
  target changed meanwhile. It writes the key or pool draft only, never publishing;
  a crate look changes the placed crate at once and also needs `crates.manage`.

## Accessibility

- **Reduced motion:** `/lootbox motion reduced`, or the Catalog's Motion button,
  shows each reveal's result at once. The odds and the result never change, only
  how they are shown. `/lootbox motion default` follows `opening.reducedMotionDefault`.
  Crate particles and sounds have their own switch, `/lootbox crate effects`.
- Every reward's exact chance stays on screen through preview, confirm, reveal and
  result.
- Hints, notes and odds read at 4.5:1 contrast or better, long names shrink to fit
  their row, and long lines (problems, changes, lore) wrap.
- Windows are no larger than the game's own pages (1050 × 700), so they fit
  wherever the game's do.

## Safety and recovery

- The key is spent and the result drawn in one write, so a crash can never spend a
  key without a result or give a result without spending a key.
- Openings interrupted by a crash or disconnect are finished when the player
  joins, and every 5 minutes for players who stay online.
- An item delivery whose outcome cannot be confirmed is held for review rather
  than given twice. Staff resolve it with
  `/lootbox reconcile <openingId> regrant|dismiss`. If two staff members resolve
  the same opening at once, only the first goes through; the other is refused
  (already being delivered, or no longer under review).
- Repeated confirm presses and rapid openings are deduplicated
  (`opening.requestDedupeMinutes`, `opening.minIntervalMs`).
- Draws use `SecureRandom`; `/lootbox simulate` shows expected against observed
  rates without awarding anything.

## Policy profile

`policyProfile` controls how keys may be sourced:

| Profile | Effect |
| --- | --- |
| `FREE_GAMEPLAY_ONLY` (default) | Keys come from gameplay, quests, events and admins. Grants marked `PAID` are refused |
| `PAID_RANDOM_RESTRICTED` | Allows `PAID` grants, only when `allowPaidRandomProfile` is also `true`. Never use it on an All Ages server |
| `DISABLED` | Previews and receipts stay readable; opening is off |

## Configuration

File:

```text
modules/lootbox/config.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `enabled` | `false` | Master switch (also enable the module in the main config) |
| `policyProfile` | `FREE_GAMEPLAY_ONLY` | See above |
| `allowPaidRandomProfile` | `false` | Required for `PAID_RANDOM_RESTRICTED` |
| `command.name` / `command.aliases` | `lootbox` / `["lb"]` | Command name and aliases |
| `opening.minIntervalMs` | `1500` | Minimum gap between two openings by one player |
| `opening.requestDedupeMinutes` | `1440` | How long confirm requests are remembered |
| `opening.revealDurationMs` / `revealFramesPerSecond` | `4000` / `10` | Reveal animation; 0 ms reveals immediately |
| `opening.allowSkip` | `true` | Show the Skip button on the reveal |
| `opening.historySize` | `50` | Receipts kept per player |
| `opening.reducedMotionDefault` | `false` | Reduced motion for players who never chose |
| `keys.consumeOrder` | `VIRTUAL_FIRST` | Or `PHYSICAL_FIRST` |
| `keys.offlineGrant` | `PENDING_INBOX` | Or `VIRTUAL_CREDIT` |
| `keys.consoleGrantSource` | `ADMIN_GRANT` | Source recorded for console grants |
| `keys.maxGrantPerCommand` | `64` | Most keys one `key give` or `key withdraw` may issue |
| `keys.grantLogSize` | `100` | Entries kept in each key's grant history |
| `keys.allowConversion` | `true` | Allow `key deposit` and `key withdraw` |
| `crates.preventPlayerBreak` / `preventDamage` | `true` / `true` | Crate protection |
| `crates.selectReach` | `10` | How far `crate select`/`convert` look |
| `crates.idleEffectIntervalMs` | `2000` | How often idle particles play while players are near |
| `crates.effectRadius` | `24` | How near a player must be to see crate effects |
| `crates.defaultIdleParticle` / `defaultOpenParticle` / `defaultOpenSound` | `Block_Treasure_Sparks` / `Block_Break_Treasure` / `SFX_Chest_Legendary_Open` | Effects new crates start with |
| `crates.nameplates` / `nameplateHeight` | `false` / `1.25` | Floating crate names, and how high above the block |
| `crates.generatedVariants` | `false` | Let plain blocks become crates through generated look-alikes |
| `rewards.itemOverflow` | `PENDING_INBOX` | Where items that do not fit go |
| `rewards.pendingExpiryDays` | `30` | Pending rewards expire after this many days |
| `rewards.commandAllowlist` | one example | `{id, pattern}` pairs for command rewards |
| `rewards.announceTemplate` | — | Broadcast for `announce` rewards; `{player}` `{reward}` `{pool}` |
| `simulation.maxTrials` | `1000000` | Cap for `/lootbox simulate` and the Simulation screen |
| `display.minDecimals` / `maxDecimals` / `locale` | `2` / `8` / `en-US` | How chances are formatted |
| `audit.retentionDays` | `400` | Audit records older than this are swept; shown in Audit History |

## Data

| Path or namespace | Contents |
| --- | --- |
| `modules/lootbox/lootboxes/*.json` | Pool drafts (edit these) |
| `modules/lootbox/keys/*.json` | Key drafts (edit these) |
| `modules/lootbox/lootboxes/_archive/`, `keys/_archive/` | Archived drafts, named `<id>-<yyyyMMddHHmmss>.json` (UTC; a key id's `:` becomes `.`); never loaded. Move a file back to restore it |
| `modules/lootbox/templates/*.json`, `templates/_archive/` | Template Library files, and deleted ones |
| `modules/lootbox/exports/` | Audit History exports |
| `mods/MysticEssentialsGenerated/` | Generated crate look-alikes (don't edit) |
| `data/modules/lootbox/secret.key` | Key-signing secret (private) |
| `lootbox_def`, `lootbox_rev`, `lootbox_keydef` | Each pool's active revision and status, published pool revisions, published key definitions |
| `lootbox_player`, `lootbox_serial`, `lootbox_open`, `lootbox_pending` | Player balances and receipts, key serials, openings, pending inboxes |
| `lootbox_crate`, `lootbox_audit` | Crates and hourly audit counters |
| `lootbox_keylog`, `lootbox_batch`, `lootbox_crate_prefs` | Each key's grant history, stackable-key batches, players who turned crate effects off |

The storage namespaces live in the configured storage provider (JSON files under
`data/` by default).

## Developer API

The module implements `LootBoxApi`, resolvable through
`getModuleManager().getModule("lootbox")`: `activeRevision`, `grantKey` (with a
`GrantSource`), `receipts`, `openPreview` and `openCatalog`. It publishes
`MysticEvent`s for revisions, key grants and revocations, crate changes, openings
(`OpeningRequestedEvent` is cancellable), deliveries and pending claims.
