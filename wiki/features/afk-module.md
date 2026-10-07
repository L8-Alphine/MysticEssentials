# AFK

The AFK module tracks when players are away from keyboard, both manually and automatically, and can optionally reward players for time spent AFK.

## Going AFK

| Command | What it does | Permission |
| --- | --- | --- |
| `/afk [reason]` | Toggle your AFK state, with an optional reason | `mysticessentials.afk.use` |

Players are also marked AFK automatically after `autoAfkSeconds` of no movement, chat, or interaction. Idle and zone state is checked once per second. Any activity clears the AFK state, except configured movement inside a reward zone. Players with `mysticessentials.afk.bypass.auto` are never auto-marked.

When `announce` is on, entering and leaving AFK is broadcast to the server.

## AFK rewards

Rewards are an optional, permission-gated way to pay players for idle time — commonly used with an AFK zone. Rewards require `mysticessentials.afk.rewards` and an economy provider (VaultUnlocked).

| Setting | Default | Description |
| --- | --- | --- |
| `rewards.enabled` | `false` | Master toggle |
| `rewards.permission` | `mysticessentials.afk.rewards` | Required reward permission |
| `rewards.intervalSeconds` | `60` | Reward interval |
| `rewards.amountPerInterval` | `5.0` | Payout per interval |
| `rewards.rewardPool` | `[]` | Optional weighted money, item, command, or message rewards; empty uses the fixed payout |
| `rewards.maxRollsPerDay` | `0` | Weighted-pool rolls allowed per UTC day; `0` = unlimited |
| `rewards.maxSessionReward` | `500.0` | Per-session cap; `0` disables |
| `rewards.maxDailyReward` | `2000.0` | Daily cap (per player per UTC day, kept in the player profile so a restart does not reset it); `0` disables |
| `rewards.requireInZone` | `false` | Require standing in the reward zone |
| `rewards.zones` | `[]` | Named reward-zone X/Z footprints |
| `rewards.defaultZone` | unset | Default auto-AFK teleport destination |
| `rewards.stayAfkWhileMovingInZone` | `true` | Keep players AFK while they move inside a reward zone |
| `rewards.teleportToZoneOnAfk` | `true` | Offer/choose a safe zone landing and restore the saved location on return |
| `rewards.noRewardWithinCombatSeconds` | `15` | Combat lockout before rewards resume |

Zones cover the X/Z footprint between their corners at every height. Safe
teleport scans random columns near the corner reference height for a solid,
non-blocked floor, enough air above it, and no blocked fluid. It prefers the
closest safe point rather than the topmost block, so roofed and underground AFK
rooms land players on their interior floor. If no safe position is found within
the configured attempts, the player stays where they are.

`rewards.safeTeleport` controls `enabled`, `attempts` (12),
`requiredHeadroom` (2), `verticalSearchRange` (24), `blockedBlocks`, and
`blockedFluids`. Water and slime are allowed by default so AFK pools continue to
work. The session/daily caps and combat lockout bound farming. Staff with
`mysticessentials.afk.zone.admin` can create, remove, list, and inspect zones.

### Managing reward zones

| Command | What it does |
| --- | --- |
| `/afkzone pos1`, `/afkzone pos2` | Save the two X/Z corners at your current positions |
| `/afkzone create <name>` | Create a zone from those corners |
| `/afkzone delete <name>` | Delete a zone |
| `/afkzone permission <name> <node\|->` | Require a permission for the zone, or clear it with `-` |
| `/afkzone default <name\|->` | Choose the default auto-AFK destination, or clear it |
| `/afkzone list` | List configured zones, dimensions, and permissions |
| `/afkzone check` | Show which zone contains your current position |

Every command above requires `mysticessentials.afk.zone.admin`. Zone edits are saved immediately.

## Configuration

File:

```text
modules/afk/config.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `autoAfkEnabled` | `true` | Enable automatic AFK |
| `autoAfkSeconds` | `300` | Idle seconds before auto-AFK |
| `checkIntervalSeconds` | `10` | Deprecated compatibility key; polling now runs on a fixed one-second tick |
| `bypassPermission` | `mysticessentials.afk.bypass.auto` | Permission that prevents auto-AFK |
| `announce` | `true` | Announce AFK state changes |

## See also

- [Permissions Reference](permissions)
- [Configuration Reference](configuration)
