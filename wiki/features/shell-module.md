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
| 8 | Server browser | "No server browser is configured" |
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
| `/1` … `/9` | Run that slot | — |

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
    "offsetY": 196,
    "title": "Chat Ch",
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
  "slots": [
    { "index": 1, "kind": "CHANNEL", "label": "Server Channel", "glyph": "S", "target": "global", "icon": "" }
  ]
}
```

- `offsetX` / `offsetY` place the rail beside the chat panel. Raise `offsetX` on
  a server that moves chat inward; the rail must not overlap chat at the UI
  scales you support.
- `hint` overrides the footer text. Left empty, the module writes what actually
  activates a slot.
- `kind` is one of `CHANNEL`, `STORE`, `WIKI`, `WARPS`, `SERVERS`, `MENU`,
  `COMMAND`, `NONE`.
- `target` is a channel id for `CHANNEL`, and a command line (or an `http(s)`
  URL, which is sent as a clickable chat link) for the rest.
- `icon` is a vanilla icon, given as its asset path under `Common/` — for
  example `Icons/ItemCategories/Portal.png`. Left empty, each slot gets the icon
  that fits what it does, from Hytale's flat category icons (Party uses the
  fist-bump emote). `none` shows the letter mark from `glyph` instead. A server
  with its own asset pack can point this at its own art the same way.
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
