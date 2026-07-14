# Patch Notes

The Patch Notes module gives your server an in-game changelog. Players open a themed viewer to read categorized, filterable patch notes, and you can notify or auto-open the viewer for players when they join.

## Commands

The open command is `/patchnotes` by default, with aliases `/patches`, `/updates`, and `/changelog`.

| Command | What it does | Permission |
| --- | --- | --- |
| `/patchnotes` | Open the Patch Notes viewer | `mysticessentials.patchnotes.view` |
| `/patchnotes open [player]` | Open the viewer (optionally for another player) | `mysticessentials.patchnotes.view`; others need `mysticessentials.patchnotes.open.others` |
| `/patchnotes list` | List patch notes in chat | `mysticessentials.patchnotes.view` |
| `/patchnotes markread` | Mark all patch notes as read | `mysticessentials.patchnotes.view`; others need `mysticessentials.patchnotes.markread.others` |
| `/patchnotes reload` | Reload patch notes from disk | `mysticessentials.patchnotes.reload` |

`mysticessentials.patchnotes.admin` grants every admin node at once.

## Writing patch notes

Each patch note is a single JSON file in:

```text
mods/MysticEssentials/modules/patchnotes/patches/*.json
```

The module generates example files on first run (when `generateExamples` is on). Add a new note by dropping another `.json` file in the folder and running `/patchnotes reload`.

A note supports these fields:

| Field | Type | Meaning |
| --- | --- | --- |
| `id` | string | Stable unique id for the note |
| `title` | string | Headline shown in the list |
| `version` | string | Version label (e.g. `1.2.0`) |
| `date` | string | ISO date `yyyy-MM-dd` |
| `author` | string | Optional author name |
| `pinned` | boolean | Pinned notes always sort first |
| `priority` | number | Higher sorts first within the same pinned tier; ties break on `date` |
| `showOnLogin` | boolean | Counts toward join notices / auto-open |
| `requiredRead` | boolean | Marks the note as important to read |
| `summary` | string | Short blurb shown under the title |
| `tags` | list | Freeform tags |
| `categories` | list | Category ids this note belongs to |
| `sections` | list | Titled content blocks (see below) |

Each **section** is `{ "type": "<category-id>", "title": "...", "body": "..." }`. The `type` matches a category id from the config so the viewer's filter buttons work. The `body` is written with a light markup: `\n` for new lines, lines beginning with `+` (addition) or `-` (removal/fix) are color-coded, and backticks mark inline code.

```json
{
  "id": "1.2.0",
  "title": "Random Teleport & Rank Icons",
  "version": "1.2.0",
  "date": "2026-07-13",
  "author": "Staff",
  "pinned": true,
  "priority": 10,
  "showOnLogin": true,
  "summary": "Big update: /rtp, chat rank icons, and more.",
  "categories": ["additions", "fixes"],
  "sections": [
    {
      "type": "additions",
      "title": "Additions",
      "body": "+ Added `/rtp` random teleport with safe-search profiles.\n+ Added LuckPerms rank icons in chat."
    },
    {
      "type": "fixes",
      "title": "Fixes",
      "body": "- Fixed the home list not refreshing after a rename."
    }
  ]
}
```

The viewer's filter buttons are built from the config `categories` list (defaults: Additions, Fixes, Changes, Removals). Give a section a `type` outside that list and it simply won't have a dedicated filter button.

## Join behavior

Two independent behaviors can surface new notes when a player joins:

- **`showOnJoin`** — send a chat notice with the unread count (respecting `showOnlyUnreadOnJoin`).
- **`openOnJoin`** — automatically open the viewer instead. When the viewer auto-opens, the chat notice is suppressed for that join so players are not told about notes the viewer is already showing. A short `openOnJoinDelayTicks` delay lets the player finish loading before the UI opens.

Opening a note marks it read when `markReadOnView` is on.

## Configuration

File:

```text
modules/patchnotes/config.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `openCommand` | `"patchnotes"` | Primary command label |
| `aliases` | `patches`, `updates`, `changelog` | Command aliases |
| `showOnJoin` | `true` | Chat notice about unread notes on join |
| `showOnlyUnreadOnJoin` | `true` | Only count unread notes for the join notice |
| `openOnJoin` | `false` | Auto-open the viewer on join (suppresses the chat notice) |
| `openOnJoinDelayTicks` | `40` | Ticks to wait before auto-opening (1 tick = 50 ms) |
| `markReadOnView` | `true` | Mark a note read when opened |
| `defaultFilter` | `"all"` | Default category filter |
| `defaultSort` | `"newest"` | `newest` or `oldest` (pinned always first) |
| `maxPatchNotesShown` | `50` | Cap on listed notes; `0` = unlimited |
| `categories` | Additions/Fixes/Changes/Removals | Filter categories, in display order |
| `generateExamples` | `true` | Generate bundled example patches on first startup |

> The viewer's colors are baked into its `.ui` files because Hytale 0.5.6 Custom UI labels only support a static style, so they cannot be re-themed at runtime.

## See also

- [Permissions Reference](permissions)
- [Configuration Reference](configuration)
