---
title: Integrations and Storage
description: LuckPerms, PlaceholderAPI, VaultUnlocked, MysticVanish, SQL storage, and Redis behavior.
---

# Integrations and Storage

## Integration behavior

Mystic Essentials auto-detects optional integrations when enabled in `config.json`.

| Integration | Behavior when present | Behavior when absent |
| --- | --- | --- |
| LuckPerms | Reads permissions, primary group, prefix, suffix | Uses Hytale permission checks where possible; group/prefix/suffix empty |
| PlaceholderAPI | Resolves `%...%` placeholders and registers `%mystic_<name>%` | Internal `{...}` placeholders still work |
| VaultUnlocked | Enables balances, withdrawals, deposits, formatted currency | Costs and payouts no-op successfully |
| MysticVanish | Hides vanished players from player-facing features | Features operate normally |
| MysticModeration | Enables moderation bridge behavior where implemented | Features operate normally |

## LuckPerms

LuckPerms is the recommended permission backend. Mystic uses it for:

- Permission checks.
- Primary group lookup.
- `{luckperms_prefix}` and `{luckperms_suffix}` placeholders.
- Chat group format overrides.
- Dynamic numeric limits.

Useful examples:

```text
lp group default permission set mysticessentials.spawn.use true
lp group default permission set mysticessentials.home.limit.3 true
lp group vip permission set mysticessentials.home.limit.10 true
lp group staff permission set mysticessentials.chat.channel.staff true
lp group staff permission set mysticessentials.chat.channel.staff.speak true
lp group staff permission set mysticessentials.chat.channel.staff.listen true
```

## PlaceholderAPI

Mystic supports two placeholder styles:

| Style | Example | Used by |
| --- | --- | --- |
| Internal | `{player_name}` | Mystic messages and formats |
| PlaceholderAPI | `%mystic_player_name%` or another expansion | Any PlaceholderAPI-compatible text |

Built-in internal placeholders include:

| Placeholder | Meaning |
| --- | --- |
| `{server_name}` | Server name fallback |
| `{player_name}` | Player username |
| `{display_name}` | Nickname-aware display name where available |
| `{luckperms_prefix}` | LuckPerms prefix |
| `{luckperms_suffix}` | LuckPerms suffix |
| `{group}` | LuckPerms primary group |

Developers can register more placeholders through `PlaceholderService`.

## VaultUnlocked

VaultUnlocked powers economy features:

| Feature | Economy behavior |
| --- | --- |
| Warp costs | Withdraws before teleport |
| Player warp costs | Withdraws before teleport |
| Kit costs | Withdraws before claim |
| Paid flight | Charges every minute while flying |
| AFK rewards | Deposits configured reward amount |

If VaultUnlocked is enabled but no economy provider is currently registered, Mystic checks lazily. A provider that registers later can be picked up without blocking startup.

## MysticVanish

When MysticVanish is installed and enabled:

- Vanished players are hidden from TPA UI rows and player suggestions.
- Vanished players are treated as offline for `/msg` and `/tpa`.
- Join, leave, and AFK announcements are suppressed for vanished players.

## JSON storage

JSON is the default provider and is best for single-server development or small servers.

Main data is stored under:

```text
mods/MysticEssentials/data/
```

Advantages:

- No external database.
- Easy backups and inspection.
- Good for local testing.

Limitations:

- Not suitable as shared storage across multiple servers.
- Manual migration is needed if you later move to SQL.

## MySQL and MariaDB storage

Set:

```json
{
  "storage": {
    "provider": "mysql"
  }
}
```

or:

```json
{
  "storage": {
    "provider": "mariadb"
  }
}
```

Mystic stores JSON documents in:

```sql
CREATE TABLE mystic_documents (
  namespace VARCHAR(128) NOT NULL,
  id        VARCHAR(128) NOT NULL,
  data      LONGTEXT     NOT NULL,
  PRIMARY KEY (namespace, id)
);
```

Use SQL for production servers and networks where player profiles, mail, and stored module data should survive server changes.

If the SQL database is unreachable at startup, Mystic logs the failure and falls back to JSON so the server can still boot.

## Redis

Redis is a cache and pub/sub layer, not the primary datastore.

Enable it with:

```json
{
  "storage": {
    "redis": {
      "enabled": true,
      "host": "localhost",
      "port": 6379,
      "password": "",
      "serverId": "survival-1",
      "networkId": "mystic-network"
    }
  }
}
```

Use the same `networkId` on all servers in the network. Use a different `serverId` for each server.

Redis-backed features:

- Cross-server broadcasts.
- Cross-server private messages.
- Cross-server chat channels.
- Temporary channel cache/restore.

If Redis is disabled or unreachable, cross-server features degrade to local-only.
