# Integrations

Mystic Essentials auto-detects a handful of optional plugins and mods. Each integration is best-effort: when the dependency is present it is used automatically, and when it is absent the related feature degrades gracefully instead of failing.

Detection is controlled in the main config, `mods/MysticEssentials/config.json`:

```json
"integrations": {
  "luckPerms": true,
  "placeholderAPI": true,
  "vaultUnlocked": true,
  "mysticVanish": true,
  "mysticModeration": true,
  "mysticIdentity": true
}
```

Set a value to `false` to force Mystic to ignore an integration even if it is installed.

## LuckPerms

Provides permission checks, group/rank resolution, meta prefixes and suffixes, and numeric limit nodes.

- Chat formats can use `{luckperms_prefix}` and `{luckperms_suffix}`.
- [Chat Formatting](chat-formatting) rank icons resolve a player's primary group (and optional meta override) to an inline icon.
- Numeric limits (homes, player warps, vaults) are read from permission suffixes such as `mysticessentials.home.limit.10`.

Without LuckPerms, Mystic falls back to the server's basic permission checks and rank-based features resolve against defaults only.

## PlaceholderAPI

Bridges Mystic's placeholders with the wider PlaceholderAPI ecosystem.

- Mystic's internal placeholders are exposed as `%mystic_<name>%` (and `%mysticessentials_<name>%`). Anything a module registers with the `PlaceholderService` is published automatically — no per-placeholder wiring — and the live list is advertised to PlaceholderAPI so it appears in expansion info.
- Playtime is available to any PlaceholderAPI-aware mod (scoreboards, holograms, tab lists) as `%mystic_playtime_total%`, `%mystic_playtime_active%`, `%mystic_playtime_idle%`, `%mystic_playtime_session%`, plus the `_seconds` and `_hours` variants.
- Mod load order is not guaranteed, so if PlaceholderAPI comes up after Mystic the expansion registration is retried on a backoff; the server log records whether it ultimately succeeded.
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

## MysticIdentity

Managed (parentally supervised) accounts. With MysticIdentity installed, chat asks its policy at
the moment of delivery and never caches the answer:

- `/msg` and `/reply` are refused between a pair the child's policy keeps apart
  (`pm-blocked`); a relayed private message is judged again on the server the child is on.
- A cross-server channel line is delivered per listener — a child whose public chat is closed
  hears only their guardians and trusted staff, and only those hear them.
- A bridged Discord line skips a child whose cross-platform chat is off.

Guardians and staff holding MysticIdentity's trusted-contact node are exempt inside its own
answer, so nothing here special-cases them. Without MysticIdentity nobody is restricted.
