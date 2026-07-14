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
| `rewards.zoneCornerA` / `zoneCornerB` | `null` | Reward zone corners |
| `rewards.noRewardWithinCombatSeconds` | `15` | Combat lockout before rewards resume |

The session and daily caps, plus the combat lockout, are anti-abuse guards so AFK farming stays bounded. Staff with `mysticessentials.afk.zone.admin` can manage the reward zone.

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
