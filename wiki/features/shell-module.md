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

## About ALT+1–9

**The rail cannot be driven with ALT+1–9, and that is an engine limit, not a
missing feature.** On Hytale `0.7.3` a server-side mod cannot bind a client key:

- there is no keybind registry in the server API;
- the client's input bindings are not asset-driven, so a mod asset pack cannot
  add one (`HotkeyLabel.InputBindingKey` only *displays* an existing binding);
- the custom UI protocol's one key event, `CustomUIEventBindingType.KeyDown`,
  is a **page** binding — it fires only while a page is open and an element has
  focus, which is exactly when the player is not in the world;
- and a HUD overlay cannot even be clicked: the `CustomHud` packet carries
  `commands` but no `eventBindings`, so no press, hover or key on an overlay
  ever reaches the server.

The serverside Noesis UI preview in `0.7.3` *does* allow interactive HUD widgets,
but it refuses to start without `-Dhytale.serverside_ui_preview` and reports
itself as "still in development and not available yet", so nothing here depends
on it.

So the rail is a **display**, and the slots are reached three ways that work
today:

| How | Notes |
| --- | --- |
| `/1` … `/9` | One keystroke plus Enter — the closest thing to ALT+N |
| `/shortcuts` | The same nine slots as real buttons |
| `/shortcuts <1-9>` | Fallback if the single-digit commands are refused |

All three funnel through one method (`ShellService#activateSlot`), so if a future
build ships client keybinds, the change is one new caller. The rail's footer hint
is written from the module rather than hardcoded, so it never advertises a
shortcut that does not work on the build you are running.

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
    "tileSize": 46,
    "title": "Chat Ch",
    "hint": "",
    "separatorsAfter": [4, 6]
  },
  "menu": {
    "enabled": true,
    "serverTitle": "",
    "hiddenModules": []
  },
  "slots": [
    { "index": 1, "kind": "CHANNEL", "label": "Server Channel", "glyph": "S", "target": "global" }
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
- `glyph` is one or two characters. These are letter marks, not icons: the mod
  ships no textures, and a `TexturePath` that does not resolve inside an
  always-on overlay breaks **every** custom UI document on that client. Any text
  works, so a server with its own asset pack can use a glyph from its own font.

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
