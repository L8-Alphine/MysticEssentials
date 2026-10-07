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
- [Chat Formatting](chat-formatting) can use LuckPerms prefixes, suffixes, primary groups, and permission-selected formats.
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

Mystic Essentials keeps no server mutes of its own; MysticModeration enforces its
mutes and chat guard in public chat itself. Chat another mod hands to Mystic with
`ChatService.deliver` (guild or party chat) never passes through public chat, so
`deliver` asks MysticModeration at the moment of delivery:

- its active mute (`PunishmentService#activeMute`): a mute refuses the line and
  tells the sender why, a shadow mute shows the line to its author only;
- its chat guard (`ChatGuardService#evaluate`): the word filter, link, caps and
  spam rules, chat lock and slow mode block or rewrite the line, with
  MysticModeration's own bypass permission. The public `evaluate` does not record
  the line, so slow mode and the spam rules compare it with the player's last
  public chat line.

Without MysticModeration (or with `integrations.mysticModeration` off, or its
punishments / chat guard module disabled) these checks pass. While it is installed
but cannot answer, delivered lines are refused with `chat-moderation-unavailable`
and a warning is logged. A muted player's public line also never pings anyone
(`rules.mutedPlayersCanNotNotify` in `mentions.json`).

## MysticRPG

When MysticRPG and its World module are available, Random Teleport checks every candidate and final destination against the requesting player's RPG level. By default, content may range from 10 levels below through 3 levels above the player; MysticRPG safe regions are always accepted. Configure the offsets and fail-closed behavior under `mysticRpgSafety` in `modules/teleportation/rtp.json`.

Mystic also reads MysticRPG's stable item metadata contract for richer Item Details pages. Without MysticRPG, RTP keeps its terrain/config safety rules and ordinary item inspection continues.

## QuestLines

Licensed CustomContent detects QuestLines without a hard dependency. It can bridge requirements, actions, and placeholders through reflection and import/export compatible dialog and GUI data. Native CustomDialogs and CustomGUIs continue to work when QuestLines is absent.

## Item metadata providers

Item Details recognizes the stable BSON metadata written by SimpleEnchantments and LuxReforge without linking to their jars. Those sections are shown when metadata is present and omitted otherwise. Addons can also register their own inspection providers through the public API.

## Storage backends

Database and cache integrations (MySQL/MariaDB and Redis) are covered separately on the [Storage](storage) page.

## MysticIdentity

Managed (parentally supervised) accounts. With MysticIdentity installed, chat asks its policy at
the moment of delivery and never caches the answer:

- `/msg` and `/reply` are refused between a pair the child's policy keeps apart
  (`pm-blocked`); a relayed private message is judged again on the server the child is on.
- A cross-server channel line is delivered per listener — a child whose public chat is closed
  hears only their guardians and trusted staff, and only those hear them.
- A bridged Discord line skips a child whose cross-platform chat is off.
- A line another mod hands over with `ChatService.deliver` (guild or party chat) is
  delivered per listener, the same way as a cross-server channel line.

Guardians and staff holding MysticIdentity's trusted-contact node are exempt inside its own
answer, so nothing here special-cases them. Without MysticIdentity nobody is restricted.

## See also

- [Configuration Reference](configuration)
- [Storage](storage)
- [Developer API](developer-api)
- [Random Teleport](rtp-module)
- [Item Links](itemlinks-module)
