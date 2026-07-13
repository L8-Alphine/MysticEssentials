# Configuration Reference

Configuration files live under `mods/MysticEssentials/`. Each config file has a `configVersion` where supported. Mystic merges new default keys into existing files during load.

If a config file cannot be parsed, Mystic logs the error and uses in-memory defaults. The broken file is not overwritten.

## Main config

File:

```text
mods/MysticEssentials/config.json
```

Important sections:

| Path | Default | Description |
| --- | --- | --- |
| `storage.provider` | `"json"` | `json`, `mysql`, or `mariadb` |
| `storage.mysql.host` | `"localhost"` | SQL host |
| `storage.mysql.port` | `3306` | SQL port |
| `storage.mysql.database` | `"mystic_essentials"` | SQL database |
| `storage.mysql.username` | `"root"` | SQL username |
| `storage.mysql.password` | `"password"` | SQL password |
| `storage.mysql.poolSize` | `10` | HikariCP pool size |
| `storage.redis.enabled` | `false` | Enables Redis cache/pub-sub |
| `storage.redis.host` | `"localhost"` | Redis host |
| `storage.redis.port` | `6379` | Redis port |
| `storage.redis.password` | `""` | Redis password; blank for none |
| `storage.redis.serverId` | `"survival-1"` | Unique id for this server |
| `storage.redis.networkId` | `"mystic-network"` | Shared id for all servers in the network |
| `integrations.luckPerms` | `true` | Auto-detect LuckPerms |
| `integrations.placeholderAPI` | `true` | Auto-detect PlaceholderAPI |
| `integrations.vaultUnlocked` | `true` | Auto-detect VaultUnlocked |
| `integrations.mysticVanish` | `true` | Auto-detect MysticVanish |
| `integrations.mysticModeration` | `true` | Auto-detect MysticModeration |

## Teleportation

File:

```text
modules/teleportation/config.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `requestExpirySeconds` | `60` | Pending `/tpa` and `/tpahere` request lifetime |
| `tpaWarmupSeconds` | `3` | Warmup after a request is accepted |
| `tpaCooldownSeconds` | `5` | Cooldown between TPA uses |
| `backWarmupSeconds` | `0` | Warmup for `/back` |
| `backCooldownSeconds` | `5` | Cooldown between `/back` uses |

Players with `mysticessentials.teleport.bypass.warmup` skip warmups. Players with `mysticessentials.teleport.bypass.cooldown` skip cooldowns.

## Spawn and homes

File:

```text
modules/spawn/config.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `globalSpawnEnabled` | `true` | Enables one global spawn |
| `perWorldSpawnEnabled` | `true` | Enables world-specific spawns |
| `syncGlobalSpawnToWorldProvider` | `true` | Syncs global spawn to the world provider where possible |
| `teleportOnFirstJoin` | `true` | Teleports first-time players to spawn |
| `teleportOnJoin` | `false` | Teleports all joining players to spawn |
| `defaultHomeLimit` | `3` | Home limit when permission limits do not override |
| `globalSpawn` | `null` | Stored global spawn location |
| `worldSpawns` | `{}` | Stored per-world spawn map |

## Mail

File:

```text
modules/mail/config.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `maxInboxSize` | `50` | Maximum messages per inbox; `0` means unlimited |
| `maxMessageLength` | `256` | Maximum mail body length; `0` means unlimited |
| `notifyUnreadOnJoin` | `true` | Shows unread count when a player joins |

When an inbox is full, Mystic removes the oldest read message first. If no read messages exist, it removes the oldest message.

## Chat

File:

```text
modules/chat/config.json
```

Top-level settings:

| Setting | Default | Description |
| --- | --- | --- |
| `formatChat` | `true` | Enables Mystic's chat formatter |
| `maxMessageLength` | `256` | Maximum chat message length |
| `autoLinkPlainUrls` | `true` | Converts plain URLs into clickable links |
| `autoLinkPermission` | `null` | Optional permission required for auto-linking |
| `defaultFormat` | `{luckperms_prefix}{display_name} &8» &f{message}` | Fallback chat format |
| `formats` | Owner example | Priority-ordered permission formats |
| `messageColorPermissions` | See below | Permission gates for player-supplied chat formatting |

Default color permissions:

| Style | Permission |
| --- | --- |
| `legacy` | `mysticessentials.chat.color.legacy` |
| `hex` | `mysticessentials.chat.color.hex` |
| `gradient` | `mysticessentials.chat.color.gradient` |
| `rainbow` | `mysticessentials.chat.color.rainbow` |
| `minimessage` | `mysticessentials.chat.color.minimessage` |
| `links` | `mysticessentials.chat.color.links` |

Private messaging settings:

| Setting | Default | Description |
| --- | --- | --- |
| `privateMessaging.enabled` | `true` | Registers `/msg` and `/reply` |
| `privateMessaging.allowCrossServer` | `true` | Allows Redis-backed PM delivery |
| `privateMessaging.offlineToMail` | `true` | Can fall back to mail for offline players |
| `privateMessaging.socialSpyEnabled` | `true` | Enables social spy |

Glyph settings:

| Setting | Default | Description |
| --- | --- | --- |
| `glyphs.enabled` | `true` | Enables glyph processing |
| `glyphs.registerCommonAssets` | `true` | Registers bundled PNG glyphs as Hytale common assets |
| `glyphs.emitPrivateUseCodepoints` | `true` | Emits private-use codepoints for custom glyphs |
| `glyphs.allowRawUnicodeSymbols` | `true` | Allows raw Unicode symbols |
| `glyphs.stripUnsafeInvisibleCharacters` | `true` | Removes unsafe control characters while preserving emoji controls |
| `glyphs.fallbackWhenMissing` | `"text"` | How missing glyphs degrade |

Channel settings:

| Setting | Default | Description |
| --- | --- | --- |
| `channels.enabled` | `true` | Enables channel commands and routing |
| `channels.defaultSpeak` | `"global"` | Default speaking channel |
| `channels.defaultJoin` | `["global"]` | Channels players listen to by default |
| `channels.allowTemporaryChannels` | `true` | Allows session channels |
| `channels.temporaryChannelDefaultMinutes` | `120` | Redis TTL restore window for temporary channels |
| `channels.createTemporaryPermission` | `mysticessentials.chat.channel.create.temp` | Temporary channel permission |

Default channels:

| Id | Scope | Aliases | Notes |
| --- | --- | --- | --- |
| `global` | `server` | `g`, `global` | Default public channel |
| `staff` | `permission` | `sc`, `schat`, `staffchat` | Cross-server capable staff channel |

## Announcements

File:

```text
modules/announcements/config.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `autoBroadcastEnabled` | `true` | Enables automatic broadcast rotation |
| `intervalSeconds` | `300` | Delay between auto-broadcasts |
| `randomOrder` | `false` | Shuffle announcement order |
| `broadcastPrefix` | `&8[&dBroadcast&8] &f` | Prefix for `/broadcast` |
| `alertPrefix` | `&8[&c&lALERT&8] &c` | Prefix for `/alert` |
| `messages` | Welcome/home/TPA examples | Auto-broadcast entries |

Announcement messages can be strings or JSON objects:

```json
{
  "lines": [
    "&7Line one",
    "&fLine two"
  ],
  "click": {
    "action": "command",
    "value": "/spawn"
  }
}
```

Use `action: "link"` with an `https://...` value for clickable URLs.

## AFK

File:

```text
modules/afk/config.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `autoAfkEnabled` | `true` | Enables automatic AFK |
| `autoAfkSeconds` | `300` | Idle seconds before auto-AFK |
| `checkIntervalSeconds` | `10` | Idle check interval |
| `bypassPermission` | `mysticessentials.afk.bypass.auto` | Permission that prevents auto-AFK |
| `announce` | `true` | Announces AFK state changes |

Rewards:

| Setting | Default | Description |
| --- | --- | --- |
| `rewards.enabled` | `false` | Master toggle |
| `rewards.permission` | `mysticessentials.afk.rewards` | Required reward permission |
| `rewards.intervalSeconds` | `60` | Reward interval |
| `rewards.amountPerInterval` | `5.0` | Economy payout per interval |
| `rewards.maxSessionReward` | `500.0` | Per-session cap; `0` disables |
| `rewards.maxDailyReward` | `2000.0` | Daily cap; `0` disables |
| `rewards.requireInZone` | `false` | Require reward zone |
| `rewards.zoneCornerA` / `zoneCornerB` | `null` | Reward zone corners |
| `rewards.noRewardWithinCombatSeconds` | `15` | Combat lockout |

## Greetings

File:

```text
modules/greetings/config.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `motdEnabled` | `true` | Sends MOTD on join |
| `motd` | Mystic Essentials welcome lines | MOTD lines |
| `firstJoinEnabled` | `true` | Enables first-join message |
| `firstJoinMessage` | Welcome message | First-join broadcast |
| `joinEnabled` | `true` | Enables join message |
| `joinMessage` | `&8[&a+&8] &7{player_name}` | Join broadcast |
| `leaveEnabled` | `true` | Enables leave message |
| `leaveMessage` | `&8[&c-&8] &7{player_name}` | Leave broadcast |

## Kits

File:

```text
modules/kits/config.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `firstJoinKit` | `"starter"` | Kit granted on first join; blank disables |
| `kits` | Starter and daily examples | Kit definitions |

Kit fields:

| Field | Meaning |
| --- | --- |
| `items` | Ordered list of `{ "itemId": "...", "quantity": 1 }` |
| `cooldownSeconds` | Seconds between claims; `0` none; `-1` one-time |
| `requiredOnlineSeconds` | Total playtime required before claiming |
| `requirePermission` | Requires `mysticessentials.kit.<name>` |
| `cost` | Economy cost per claim |
| `description` | UI and list description |

## Flight

File:

```text
modules/flight/config.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `paidFlight` | `false` | Charges players while flying |
| `costPerMinute` | `10.0` | Economy cost per minute |
| `horizontalSpeedMultiplier` | `1.0` | Flight horizontal speed multiplier |
| `verticalSpeedMultiplier` | `1.0` | Flight vertical speed multiplier |

## Inventory

File:

```text
modules/inventory/config.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `snapshotOnJoin` | `true` | Snapshot inventory on join |
| `snapshotOnLeave` | `true` | Snapshot inventory on leave |
| `snapshotOnDeath` | `true` | Snapshot inventory on death |
| `timedSnapshotMinutes` | `0` | Periodic snapshot interval; `0` disables |
| `maxSnapshotsPerPlayer` | `24` | Retained snapshots per player |

## Nicknames

File:

```text
modules/nick/config.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `minLength` | `3` | Minimum visible nickname length |
| `maxLength` | `16` | Maximum visible nickname length |
| `blockedNames` | `admin`, `owner`, `server`, `console` | Names players cannot take |
| `nickMarker` | `~` | Staff-visible marker prefix |
| `nickFormat` | `{marker}{nickname}` | Stored/displayed nickname format |