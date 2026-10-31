# Navigation Shell (shortcut rail and menu)

The shell is the front door to everything else: a vertical **shortcut rail**
drawn beside the chat panel, and an **Adventurer's Menu** (`/menu`) that
discovers the Mystic Ecosystem mods installed on the server and routes to them.

Module id: `shell`. Enabled by default.

## The nine slots

The slot assignment follows the Mystic Ecosystem UI/UX Design Bible:

| Slot | Default target | When it cannot be used |
| --- | --- | --- |
| 1 | Server channel | Chat module off, or no such channel configured |
| 2 | Trading channel | As above |
| 3 | Guild channel | "Join a guild to use this channel" |
| 4 | Party channel | "Join a party to use this channel" |
| 5 | Server store | "The server has not set up a store yet" |
| 6 | Server wiki | Wiki module off |
| 7 | Warps | Warps module off |
| 8 | Server browser | Browser turned off (`browser.enabled`) |
| 9 | Main menu | Always available |

A slot that cannot be used is never hidden — it dims and says why, on the tile's
tooltip, in the Shortcuts page, and in chat if you run it anyway.

## ALT+1–9

Hold **Alt** and press a number, and the matching slot runs. Your held item does
not change: the hotbar is put back the moment the shortcut fires.

There is no keybind API for server-side mods, so this works by reading two keys
the client already reports. **Alt is Hytale's default walk key**, so holding it
sets the player's *walking* state; a **digit selects a hotbar slot**. A slot
change that lands while walking is an ALT+digit press. (This is the same trick
Endgame & QoL uses for its journal key; the shell binds all nine.)

Because both halves are ordinary inputs, the shell has to tell a shortcut from
ordinary play:

| Situation | What happens |
| --- | --- |
| Alt held, digit pressed | Shortcut runs, hotbar restored |
| Alt released a moment before the digit | Still counts, for 800 ms |
| Walking for a while, then **scrolling** one slot | Not a shortcut: a one-slot move needs an Alt press under 350 ms old, since it could be the wheel |
| Digit reaches the server just before the walk state | Honoured if walking starts within 250 ms (jumps only, never one-slot moves) |
| A page is already open | Hotbar restored, no second page opened |

One honest trade-off: a player who walks with Alt held **and** presses a number
to change weapon gets the shortcut instead of the weapon. Turn
`keys.altShortcuts` off on a server where that matters; `/1`…`/9` keep
working either way. A player who rebound *walk* away from Alt uses their walk
key instead.

The rail itself is still a display: a HUD overlay carries no event bindings, so
it cannot be clicked. Every way of running a slot goes through one method,
`ShellService#activateSlot`:

| How | Notes |
| --- | --- |
| **Alt + 1–9** | When `keys.altShortcuts` is on (the default) |
| `/1` … `/9` | One keystroke plus Enter |
| `/shortcuts` | The same nine slots as real buttons |
| `/shortcuts <1-9>` | Fallback if the single-digit commands are refused |

The rail's footer reads `Alt+#` when the chord is live and `/1 – /9` when it is
off, so it never advertises a shortcut the server has switched off.

## Commands

| Command | What it does | Permission |
| --- | --- | --- |
| `/menu`, `/mysticmenu` | Open the Adventurer's Menu | — |
| `/shortcuts`, `/sc` | Open the Shortcuts page | — |
| `/shortcuts <1-9>` | Run a slot | — |
| `/shortcuts rail` | Show or hide the rail, remembered per player | — |
| `/shortcuts edit [1-9]` | Open the Rail Editor | `mysticessentials.shell.admin` |
| `/servers`, `/serverbrowser` | Open the Server Browser | — |
| `/1` … `/9` | Run that slot | — |

## Editing the rail in game

`/shortcuts edit` (or **Edit Rail** on the Shortcuts page, shown only to
holders of `mysticessentials.shell.admin`) opens the Rail Editor. Pick a slot on
the left, then set:

- **Does** — chat channel, store link, server wiki, warps, server browser, main
  menu, command, or nothing.
- **Label**, **Runs** (the channel id, command or link), **Letter** (shown when
  the slot has no icon) and **Permission**.
- **Icon** — pick from the game's own icons in the grid, or **Default** (the
  kind's own icon) or **Letter** (no icon).

Nothing is written until **Save**. Save re-checks the permission, writes
`modules/shell/config.json` off the world thread, and redraws every player's
rail at once. **Reset Slot** loads the shipped default into the form. Slot
numbers are fixed: Alt+N and `/N` always run slot N.

A **Command** slot runs as the player who presses it, under their own
permissions, so it cannot be used to run something the player could not run
by typing it.

## Server Browser

Slot 8, the menu's **Server Browser** card and `/servers` open the browser: region
tabs across the top, then server cards three to a row under group headings.
Each card shows the server's icon, region, description and one of:

| Status | Meaning | Connect |
| --- | --- | --- |
| *N online* | On the Redis network roster; vanished players are not counted | Moves the player |
| You are here | The server the player is on | — |
| Open | Listed by address only; no live count | Moves the player |
| Offline | Named in config but not on the roster, with no address to fall back on | — |
| Coming soon | `comingSoon: true` | — |
| Locked | The player lacks the card's `permission` | — |

Connect re-resolves the card first, so a server that went offline or a
permission revoked since the page opened is honoured. A server on the roster is
reached through it — through the proxy when `storage.redis.proxyHost` is set —
otherwise straight at its `host`/`port`.

With nothing configured and `showNetwork` on, the browser lists this server and
every other server on the network roster, so a Redis network works with no
setup. Point slot 8's `target` at a command or link to use your own selector
instead; the menu card and `/servers` follow it too.

## The menu

Sections follow the bible's information architecture: **Overview**, **Adventure**,
**Community**, **Services**, **Account**, **Settings**, **Staff**. A section with
nothing in it for you is left out of the rail rather than shown empty.

Cards come from three places:

1. **Essentials' own modules** — gated on the module being enabled.
2. **The first-party ecosystem** — MysticQuests, MysticRPG, MysticGuilds,
   MysticEconomy, MysticIdentity, MysticNameTags, MysticModeration and
   MysticGeneration, detected by plugin id with no compile-time dependency.
   A mod that is present but not started yet reads as "still starting up", not
   as ready.
3. **Cards other mods registered** through `ShellService#registerDestination`.

A module that is absent is shown and explained, not silently dropped: a menu
that quietly omits Guilds looks like a server with no guild feature.

**Hiding a card is not authorisation.** Every action re-checks on the server
when it runs, and a card is re-resolved from the catalogue when it is pressed,
so a card that went unavailable while the menu was open cannot be used.

## Configuration

`mods/MysticEssentials/modules/shell/config.json`:

```json
{
  "rail": {
    "enabled": true,
    "visibleByDefault": true,
    "offsetX": 12,
    "offsetY": 316,
    "title": "Chat",
    "hint": "",
    "separatorsAfter": [4, 6]
  },
  "keys": {
    "altShortcuts": true
  },
  "menu": {
    "enabled": true,
    "serverTitle": "",
    "hiddenModules": []
  },
  "browser": {
    "enabled": true,
    "showNetwork": true,
    "servers": [
      { "id": "survival-1", "name": "Survival", "description": "The main world.",
        "group": "Survival", "region": "NA", "icon": "Natural",
        "host": "", "port": 0, "maxPlayers": 100, "permission": "", "comingSoon": false }
    ]
  },
  "slots": [
    { "index": 1, "kind": "CHANNEL", "label": "Server Channel", "glyph": "S", "target": "global", "icon": "" }
  ]
}
```

- `offsetX` / `offsetY` place the rail beside the chat panel. The default
  centres it on the default chat panel. Raise `offsetX` on a server that moves
  chat inward; the rail must not overlap chat at the UI scales you support.
- `title` sits above the first tile. The rail is 34 px wide, so keep it to about
  four letters.
- Configs written before this version move from `offsetY: 196` and
  `"Chat Ch"` to the new defaults automatically, but only if they still held
  the old defaults; a position or title you chose is kept.
- `hint` overrides the footer text. Left empty, the module writes what actually
  activates a slot.
- `kind` is one of `CHANNEL`, `STORE`, `WIKI`, `WARPS`, `SERVERS`, `MENU`,
  `COMMAND`, `NONE`.
- `target` is a channel id for `CHANNEL`, and a command line (or an `http(s)`
  URL, which is sent as a clickable chat link) for the rest.
- `icon` is a vanilla icon: a picker name from the Rail Editor (`Portal`,
  `Fist Bump`, `Letter`…) or an asset path under `Common/`, for example
  `Icons/ItemCategories/Portal.png`. Left empty, each slot gets the icon that
  fits what it does, from Hytale's flat category icons (Party uses the
  fist-bump emote). `none` shows the letter mark from `glyph` instead. A server
  with its own asset pack can point this at its own art by path. An unknown
  name falls back to the default rather than drawing nothing.
- `browser.servers[]`: `id` is the server's `storage.redis.serverId` (gives the
  live count and routes through the roster); `host`/`port` is the fallback for
  a server that is not on the roster; `region` feeds the tabs (cards without
  one show under every tab); `group` is the heading; `maxPlayers` is shown as
  "12 / 100"; `permission` locks the card; `comingSoon` shows it without a
  Connect button. `showNetwork` adds roster servers you did not list.
- `glyph` is one or two characters, shown only when a slot has no icon.
- `keys.altShortcuts` turns ALT+1–9 on or off.

Each player's rail choice is stored in `data/modules/shell/rail.json` as the set
of players who *differ* from `visibleByDefault`, so changing the server default
moves everyone who never chose and leaves everyone who did.

## For developers

```java
ShellService shell = MysticEssentialsProvider.get().getShellService();
if (shell != null) {
    shell.registerDestination(MenuDestination.of(
            "mymod:dashboard", MenuSectionId.COMMUNITY, "My Dashboard", "D",
            "What my mod does.", 10, player -> openMyPage(player)));
}
```

Unregister on disable. `getShellService()` returns `null` when the shell module
is off, which is how you tell "no menu on this server" from "menu present, my
card was rejected".

See also [Server Wiki](wiki-module), [Chat](chat-module),
[Developer API](developer-api).
