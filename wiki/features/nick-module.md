# Nicknames

The Nick module lets players set a display nickname that replaces their username in chat and other Mystic surfaces. Nicknames are length-limited, screened against a blocklist, and color options are permission-gated.

## Commands

| Command | What it does | Permission |
| --- | --- | --- |
| `/nick`, `/nickname` | Open the Nickname UI | `mysticessentials.nick.use` |
| `/nick <name>` | Set your nickname | `mysticessentials.nick.use` (color needs `mysticessentials.nick.color`) |
| `/nick reset` | Remove your nickname | `mysticessentials.nick.use` |
| `/nick <player> <name>` | Set another player's nickname | `mysticessentials.nick.others` |

The nickname feeds the `{display_name}` placeholder used in [chat formatting](chat-formatting).

## Rules and the staff marker

- Nicknames must be between `minLength` and `maxLength` visible characters.
- Names in `blockedNames` (such as `admin`, `owner`, `server`, `console`) are rejected.
- Color codes require `mysticessentials.nick.color`.
- A `nickMarker` (default `~`) is applied through `nickFormat` so staff can tell a nickname from a real username.

## Configuration

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

## See also

- [Chat Formatting](chat-formatting)
- [Permissions Reference](permissions)
