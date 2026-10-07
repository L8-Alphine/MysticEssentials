# Craft Blocking

The Craft Blocking module prevents configured recipes or output items from being crafted. It applies to normal crafting benches and hand crafting, is enabled by default, and is configured in `modules/craftblock/config.json`.

Blocked recipes still appear in the client crafting UI. When a player tries to craft one, Mystic cancels the craft before ingredients are consumed and shows a danger toast. Processing benches such as smelters are not covered because Hytale does not expose their recipes through the craft pre-event.

## Matching rules

Entries in `blockedItems` are case-insensitive and are checked against every recipe output id and the recipe id itself. `*` wildcards are supported, so a single entry such as `Mcw_*` can block a whole item family.

The generated config includes example workstation ids. Review these entries on first run and replace them with the item or recipe ids used by your server.

## Commands

| Command | What it does | Permission |
| --- | --- | --- |
| `/craftblock` | List the active block entries | `mysticessentials.craftblock.admin` |
| `/craftblock check` | Show the held item's id and whether it is blocked | `mysticessentials.craftblock.admin` |

Edit the config and run `/mystic reload` to change the live block list.

## Bypasses

| Permission | Grants |
| --- | --- |
| `mysticessentials.craftblock.bypass` | Craft every blocked item |
| `mysticessentials.craftblock.bypass.<matched id>` | Craft one blocked item or recipe; use the lowercased id that matched the rule |

## Configuration

File:

```text
modules/craftblock/config.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `blockedItems` | Example workstation ids | Case-insensitive item/recipe ids; `*` wildcards are allowed |
| `notifyPlayer` | `true` | Show a danger toast when a craft is denied |
| `messageInChat` | `false` | Also send the `craftblock-denied` chat message |
| `logAttempts` | `false` | Log denied attempts with the player and matched id |

Player-facing denial notifications are throttled per player so holding the craft button cannot spam chat or toasts. When `logAttempts` is enabled, every denied attempt is written to the server log.

## See also

- [Commands Reference](commands)
- [Permissions Reference](permissions)
- [Configuration Reference](configuration)
