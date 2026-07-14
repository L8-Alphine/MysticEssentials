# Flight

The Flight module lets permitted players toggle creative-style flight, with optional paid flight and speed tuning.

## Commands

| Command | What it does | Permission |
| --- | --- | --- |
| `/fly` | Toggle your own flight | `mysticessentials.fly.use` |
| `/fly <player>` | Toggle flight for another player | `mysticessentials.fly.others` |

## Paid flight

When `paidFlight` is enabled, flying players are charged `costPerMinute` through the economy provider (VaultUnlocked). Two permissions exempt players:

| Node | Effect |
| --- | --- |
| `mysticessentials.fly.free` | Exempt from paid-flight charges |
| `mysticessentials.fly.unlimited` | Unlimited/free flight in paid-flight mode |

If a player cannot pay, their flight is disabled.

## Speed

`horizontalSpeedMultiplier` and `verticalSpeedMultiplier` scale flight speed for everyone who can fly.

## Configuration

File:

```text
modules/flight/config.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `paidFlight` | `false` | Charge players while flying |
| `costPerMinute` | `10.0` | Economy cost per minute |
| `horizontalSpeedMultiplier` | `1.0` | Horizontal flight speed multiplier |
| `verticalSpeedMultiplier` | `1.0` | Vertical flight speed multiplier |

## See also

- [Permissions Reference](permissions)
- [Configuration Reference](configuration)
