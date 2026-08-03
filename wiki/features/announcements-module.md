# Announcements

The Announcements module sends manual and rotating notices through Mystic's
shared notification engine. Notices can target audiences, use multiple delivery
surfaces, store history, play sounds, and carry command or URL actions.

## Manual broadcasts

| Command | What it does | Permission |
| --- | --- | --- |
| `/broadcast [category] [priority] <message> [flags]`, `/bc ...` | Send a normal notice | `mysticessentials.announcement.broadcast` |
| `/alert [category] [priority] <message> [flags]` | Send a higher-priority notice | `mysticessentials.announcement.alert` |

The short forms remain compatible: `/broadcast The market is open` and `/alert
Restart soon` use the configured prefixes, title and sound. The precise form can
select a category (`announcement`, `event`, `maintenance`, `warning`, `critical`,
and others), a `low`, `normal`, `important`, or `critical` priority, and flags:

```text
/alert critical --title "Server Restart" --subtitle "60 seconds" \
  --message "Please move somewhere safe." --bossbar --duration 60 --audience all
```

Supported value flags are `--title`, `--subtitle`, `--message`, `--sound`,
`--icon`, `--source`, `--command`, `--url`, `--duration`, and `--audience`.
Surface switches are `--bossbar`/`--banner`, `--toast`, `--actionbar`,
`--no-chat`, `--no-history`, and `--sticky`.

Audiences include `all`, `staff`, `world:<name>`, `channel:<id>`,
`permission:<node>`, and `player:<name>`. `guild:<id>`, `party:<id>`, and
`region:<id>` work when an addon registers the matching audience resolver.

Priority controls the default delivery profile: low is chat-only, normal adds
action bar/toast/history, important adds a title, and critical also pins a
non-dismissible banner. Critical sending needs
`mysticessentials.notifications.critical` and cannot be suppressed by player
preferences unless the server explicitly changes that policy.

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
| `broadcastTitle` / `alertTitle` | `Announcement` / `Alert` | Built-in event-title headline |
| `broadcastSound` / `alertSound` | Hytale attention SFX | Sound used by short-form and rotating notices |
| `messages` | Welcome/home/TPA examples | Auto-broadcast entries |

## See also

- [Permissions Reference](permissions)
- [Configuration Reference](configuration)
