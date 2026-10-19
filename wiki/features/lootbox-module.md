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

`/lootbox history` lists the player's own receipts: pool, revision, odds id,
reward, chance and delivery status.

## Commands

| Command | What it does | Permission |
| --- | --- | --- |
| `/lootbox` | Open the catalog | `mysticessentials.lootbox.use` |
| `/lootbox preview <pool>` | Open a pool's preview | `mysticessentials.lootbox.use` |
| `/lootbox pending` | Open the pending inbox | `mysticessentials.lootbox.use` |
| `/lootbox history` | Your own receipts | `mysticessentials.lootbox.history` |
| `/lootbox history <player>` | Another player's receipts | `mysticessentials.lootbox.audit` |
| `/lootbox key give <player> <keyId> [count] [virtual] [source=…]` | Issue keys | `mysticessentials.lootbox.keys.give` |
| `/lootbox key revoke <serial>` | Revoke an issued physical key | `mysticessentials.lootbox.keys.revoke` |
| `/lootbox key info <serial>` / `key list` | Inspect a key serial / list published keys | `mysticessentials.lootbox.keys` |
| `/lootbox key publish <keyId>` | Publish a key draft | `mysticessentials.lootbox.publish` |
| `/lootbox publish <pool>` | Publish a pool draft as a new revision | `mysticessentials.lootbox.publish` |
| `/lootbox rollback <pool> <revision>` | Republish an older revision as a new one | `mysticessentials.lootbox.publish` |
| `/lootbox disable <pool>` / `enable <pool>` | Close or reopen a pool | `mysticessentials.lootbox.admin` |
| `/lootbox simulate <pool> [draws]` | Draw many times without awarding anything | `mysticessentials.lootbox.simulate` |
| `/lootbox crate select` | Show the block you are looking at | `mysticessentials.lootbox.crates.place` |
| `/lootbox crate convert <pool> [crateId]` | Bind the block you are looking at to a pool | `mysticessentials.lootbox.crates.place` |
| `/lootbox crate info` / `list` / `verify` | Inspect crates | `mysticessentials.lootbox.crates.manage` |
| `/lootbox crate remove [crateId]` / `enable <crateId>` | Unbind or reactivate a crate | `mysticessentials.lootbox.crates.manage` |
| `/lootbox reconcile <openingId> <regrant\|dismiss>` | Resolve a delivery held for review | `mysticessentials.lootbox.restore` |
| `/lootbox admin` | Open the Manager: every pool with its status (Draft, Invalid, Ready, Published, Disabled), search and a status filter | `mysticessentials.lootbox.admin` |
| Manager: New Pool, Clone, Archive | Write an empty draft, copy a draft under a new id, or move a draft to `lootboxes/_archive/` (asks first; nothing is deleted). Editing a pool's contents in game comes in a later update | `mysticessentials.lootbox.edit` |
| Manager: Publish, Simulate, Close/Reopen | Publish asks first and only appears when the draft is valid and differs from the live revision | `publish`, `simulate`, `admin` |
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
  "expiresAfterDays": 0
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

## Crates

Look at a block and bind it:

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
| `keys.consumeOrder` | `VIRTUAL_FIRST` | Or `PHYSICAL_FIRST` |
| `keys.offlineGrant` | `PENDING_INBOX` | Or `VIRTUAL_CREDIT` |
| `keys.consoleGrantSource` | `ADMIN_GRANT` | Source recorded for console grants |
| `keys.maxGrantPerCommand` | `64` | Most keys one `key give` may issue |
| `crates.preventPlayerBreak` / `preventDamage` | `true` / `true` | Crate protection |
| `crates.selectReach` | `10` | How far `crate select`/`convert` look |
| `rewards.itemOverflow` | `PENDING_INBOX` | Where items that do not fit go |
| `rewards.pendingExpiryDays` | `30` | Pending rewards expire after this many days |
| `rewards.commandAllowlist` | one example | `{id, pattern}` pairs for command rewards |
| `rewards.announceTemplate` | — | Broadcast for `announce` rewards; `{player}` `{reward}` `{pool}` |
| `simulation.maxTrials` | `1000000` | Cap for `/lootbox simulate` |
| `display.minDecimals` / `maxDecimals` / `locale` | `2` / `8` / `en-US` | How chances are formatted |
| `audit.retentionDays` | `400` | Audit records older than this are swept |

## Data

| Path or namespace | Contents |
| --- | --- |
| `modules/lootbox/lootboxes/*.json` | Pool drafts (edit these) |
| `modules/lootbox/keys/*.json` | Key drafts (edit these) |
| `modules/lootbox/lootboxes/_archive/`, `keys/_archive/` | Archived drafts, named `<id>-<yyyyMMddHHmmss>.json` (UTC; a key id's `:` becomes `.`); never loaded. Move a file back to restore it |
| `data/modules/lootbox/secret.key` | Key-signing secret (private) |
| `lootbox_def`, `lootbox_rev`, `lootbox_keydef` | Each pool's active revision and status, published pool revisions, published key definitions |
| `lootbox_player`, `lootbox_serial`, `lootbox_open`, `lootbox_pending` | Player balances and receipts, key serials, openings, pending inboxes |
| `lootbox_crate`, `lootbox_audit` | Crates and hourly audit counters |

The storage namespaces live in the configured storage provider (JSON files under
`data/` by default).

## Developer API

The module implements `LootBoxApi`, resolvable through
`getModuleManager().getModule("lootbox")`: `activeRevision`, `grantKey` (with a
`GrantSource`), `receipts`, `openPreview` and `openCatalog`. It publishes
`MysticEvent`s for revisions, key grants and revocations, crate changes, openings
(`OpeningRequestedEvent` is cancellable), deliveries and pending claims.
