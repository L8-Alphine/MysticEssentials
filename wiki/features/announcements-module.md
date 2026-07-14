# Announcements

The Announcements module provides manual broadcasts and an automatic broadcast rotation. Announcements can be single lines, multi-line blocks, and clickable.

## Manual broadcasts

| Command | What it does | Permission |
| --- | --- | --- |
| `/broadcast <message>`, `/bc <message>` | Broadcast with the broadcast prefix | `mysticessentials.announcement.broadcast` |
| `/alert <message>` | Broadcast with the alert prefix | `mysticessentials.announcement.alert` |

The two commands differ only in their configured prefix, so `/alert` can stand out from routine `/broadcast` messages.

## Auto-broadcasts

When `autoBroadcastEnabled` is on, Mystic rotates through the `messages` list every `intervalSeconds`. Set `randomOrder` to shuffle instead of cycling in order.

### Message formats

Each entry in `messages` is one of two shapes:

- **A plain string** — a single colored line.
- **An object** with a `lines` array (one or more lines) and an optional `click` action.

A `click` object has:

| Field | Values | Meaning |
| --- | --- | --- |
| `action` | `command` or `link` | Run a command as the player, or open a URL |
| `value` | `/command` or `https://...` | The command (with leading `/`) or the URL |

A complete `messages` list mixing all forms:

```json
"messages": [
  {
    "lines": [
      "&7Welcome to the server!",
      "&8Click this announcement to run &f/mystic&8."
    ],
    "click": { "action": "command", "value": "/mystic" }
  },
  "&7Set a home with &f/sethome &7and return with &f/home&7.",
  {
    "lines": [
      "&7Join our community!",
      "&8Click to open our Discord."
    ],
    "click": { "action": "link", "value": "https://discord.gg/example" }
  }
]
```

Both `broadcastPrefix` and `alertPrefix` accept color codes; set either to an empty string to disable that prefix entirely.

## Configuration

File:

```text
modules/announcements/config.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `autoBroadcastEnabled` | `true` | Enable automatic broadcast rotation |
| `intervalSeconds` | `300` | Delay between auto-broadcasts |
| `randomOrder` | `false` | Shuffle announcement order |
| `broadcastPrefix` | `&8[&dBroadcast&8] &f` | Prefix for `/broadcast` |
| `alertPrefix` | `&8[&c&lALERT&8] &c` | Prefix for `/alert` |
| `messages` | Welcome/home/TPA examples | Auto-broadcast entries |

## See also

- [Permissions Reference](permissions)
- [Configuration Reference](configuration)
