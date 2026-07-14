# Kits

Kits are predefined bundles of items players can claim — a starter kit on first join, a daily kit on a cooldown, donor kits, and so on. Kits can be gated by cooldowns, playtime, permissions, and economy costs.

## Commands

| Command | What it does | Permission |
| --- | --- | --- |
| `/kit`, `/kits` | Open or list available kits | `mysticessentials.kit.use` |
| `/kit list` | List kits you can claim | `mysticessentials.kit.use` |
| `/kit <name>` | Claim a kit | `mysticessentials.kit.use` (plus `mysticessentials.kit.<name>` for gated kits) |
| `/kit give <player> <kit>` | Give a kit to a player, ignoring gating | `mysticessentials.kit.admin` |

The kit menu shows a preview of each kit's contents and its claim state (ready, on cooldown, or already claimed).

## First-join kit

Set `firstJoinKit` to a kit name to grant it automatically the first time a player joins. Leave it blank to disable.

## Anatomy of a kit

Kits live in the `kits` map, keyed by a lowercase id (the id is what players type: `/kit starter`). Each kit combines items with optional gating:

| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| `displayName` | string | id, title-cased | Pretty name shown in menus |
| `description` | string | — | Line shown in `/kit list` and the UI |
| `items` | list | `[]` | Items given, in order (see below) |
| `cooldownSeconds` | number | `0` | Seconds between claims. `0` = no cooldown, `-1` = **single-use ever** |
| `requiredOnlineSeconds` | number | `0` | Total playtime a player must have before claiming; `0` = none |
| `requirePermission` | boolean | `false` | When `true`, the kit also needs `mysticessentials.kit.<id>` |
| `cost` | number | `0.0` | Economy cost per claim (needs VaultUnlocked) |

Each entry in `items` is `{ "itemId": "<HytaleItemId>", "quantity": <n> }`. Items that no longer resolve are skipped with a log line rather than failing the whole claim.

Players with `mysticessentials.kit.bypass.cooldown` ignore both cooldowns and one-time locks.

## Worked example

```json
{
  "configVersion": 1,
  "firstJoinKit": "starter",
  "kits": {
    "starter": {
      "displayName": "Starter Kit",
      "description": "Basic tools for new players",
      "cooldownSeconds": -1,
      "requiredOnlineSeconds": 0,
      "requirePermission": false,
      "cost": 0.0,
      "items": [
        { "itemId": "Tool_Pickaxe_Copper", "quantity": 1 },
        { "itemId": "Tool_Hatchet_Copper", "quantity": 1 },
        { "itemId": "Plant_Fruit_Apple", "quantity": 8 }
      ]
    },
    "daily": {
      "displayName": "Daily Bundle",
      "description": "A small daily bundle",
      "cooldownSeconds": 86400,
      "requiredOnlineSeconds": 3600,
      "items": [
        { "itemId": "Plant_Fruit_Apple", "quantity": 4 }
      ]
    },
    "vip": {
      "displayName": "VIP Crate",
      "description": "Members only",
      "cooldownSeconds": 604800,
      "requirePermission": true,
      "cost": 250.0,
      "items": [
        { "itemId": "Tool_Pickaxe_Iron", "quantity": 1 }
      ]
    }
  }
}
```

This defines three kits: a **one-time** `starter` (`-1` cooldown) granted automatically on first join, a **daily** kit that also needs one hour of playtime, and a permission-gated, paid, weekly `vip` kit that requires `mysticessentials.kit.vip`.

Common patterns:

- **One-time kit** — `cooldownSeconds: -1`.
- **Daily/weekly kit** — `cooldownSeconds: 86400` / `604800`.
- **Rank-only kit** — `requirePermission: true`, then grant `mysticessentials.kit.<id>` to the rank.
- **Playtime reward** — set `requiredOnlineSeconds` to the seconds of playtime required.
- **Paid kit** — set `cost` (free when no economy plugin is installed).

## Configuration

File:

```text
modules/kits/config.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `configVersion` | `1` | Config schema version (managed by Mystic) |
| `firstJoinKit` | `"starter"` | Kit id granted on first join; blank disables |
| `kits` | Starter and daily examples | Kit definitions (see above) |

## See also

- [Permissions Reference](permissions)
- [Configuration Reference](configuration)
