# AFK

The AFK module tracks when players are away from keyboard, both manually and automatically, and can optionally reward players for time spent AFK.

## Going AFK

| Command | What it does | Permission |
| --- | --- | --- |
| `/afk [reason]` | Toggle your AFK state, with an optional reason | `mysticessentials.afk.use` |

Players are also marked AFK automatically after `autoAfkSeconds` of no movement, chat, or interaction (checked every `checkIntervalSeconds`). Any activity clears the AFK state. Players with `mysticessentials.afk.bypass.auto` are never auto-marked.

When `announce` is on, entering and leaving AFK is broadcast to the server.

## AFK rewards

Rewards are an optional, permission-gated way to pay players for idle time — commonly used with an AFK zone. Rewards require `mysticessentials.afk.rewards` and an economy provider (VaultUnlocked).

| Setting | Default | Description |
| --- | --- | --- |
| `rewards.enabled` | `false` | Master toggle |
| `rewards.permission` | `mysticessentials.afk.rewards` | Required reward permission |
| `rewards.intervalSeconds` | `60` | Reward interval |
| `rewards.amountPerInterval` | `5.0` | Payout per interval |
| `rewards.maxSessionReward` | `500.0` | Per-session cap; `0` disables |
| `rewards.maxDailyReward` | `2000.0` | Daily cap; `0` disables |
| `rewards.requireInZone` | `false` | Require standing in the reward zone |
| `rewards.zones` | `[]` | Named reward-zone X/Z footprints |
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

## Configuration

File:

```text
modules/afk/config.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `autoAfkEnabled` | `true` | Enable automatic AFK |
| `autoAfkSeconds` | `300` | Idle seconds before auto-AFK |
| `checkIntervalSeconds` | `10` | Idle check interval |
| `bypassPermission` | `mysticessentials.afk.bypass.auto` | Permission that prevents auto-AFK |
| `announce` | `true` | Announce AFK state changes |

## See also

- [Permissions Reference](permissions)
- [Configuration Reference](configuration)
