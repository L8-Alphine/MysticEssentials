# Integrations

Mystic Essentials auto-detects a handful of optional plugins and mods. Each integration is best-effort: when the dependency is present it is used automatically, and when it is absent the related feature degrades gracefully instead of failing.

Detection is controlled in the main config, `mods/MysticEssentials/config.json`:

```json
"integrations": {
  "luckPerms": true,
  "placeholderAPI": true,
  "vaultUnlocked": true,
  "mysticVanish": true,
  "mysticModeration": true
}
```

Set a value to `false` to force Mystic to ignore an integration even if it is installed.

## LuckPerms

Provides permission checks, group/rank resolution, meta prefixes and suffixes, and numeric limit nodes.

- Chat formats can use `{luckperms_prefix}` and `{luckperms_suffix}`.
- [Rank Icons](rankicons-module) resolve a player's primary group (and optional meta override) to an inline icon.
- Numeric limits (homes, player warps, vaults) are read from permission suffixes such as `mysticessentials.home.limit.10`.

Without LuckPerms, Mystic falls back to the server's basic permission checks and rank-based features resolve against defaults only.

## PlaceholderAPI

Bridges Mystic's placeholders with the wider PlaceholderAPI ecosystem.

- Mystic's internal placeholders are exposed as `%mystic_<name>%` (and `%mysticessentials_<name>%`).
- Placeholders from other plugins can be used inside Mystic message templates.

See [Chat Formatting](chat-formatting) for how placeholders resolve in chat.

## VaultUnlocked

Supplies the economy used by paid features: warp costs, kit costs, paid flight, AFK rewards, and RTP profile costs. When no economy provider is installed, all costs are treated as free and payouts are skipped, so economy-gated features still work — just without charging.

## MysticVanish

When present, Mystic respects vanished players: they are hidden from teleport targeting, RTP admin actions, and other player-visibility checks so staff stay invisible.

## MysticModeration

Ties Mystic into the MysticModeration suite for moderation-aware behavior across chat and player actions.

## Storage backends

Database and cache integrations (MySQL/MariaDB and Redis) are covered separately on the [Storage](storage) page.

## See also

- [Configuration Reference](configuration)
- [Storage](storage)
- [Developer API](developer-api)
