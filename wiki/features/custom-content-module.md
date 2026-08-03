# CustomGUIs & CustomDialogs module

The `customcontent` module provides CustomDialogs and CustomGUIs through
MysticEssentials' module lifecycle. It is disabled by default and can be
hot-enabled or disabled through the main module map followed by `/mystic
reload`.

## License and access

CustomContent is a licensed feature. Access requires all of the following:

1. Join the [Hyzion Discord](https://discord.gg/9aq3Gqg3Gy) and become a partner.
2. Purchase an eligible [Hyzion Patreon membership](https://www.patreon.com/cw/Hyzion).
3. Sign in with Discord at [license.hyzion.net](https://license.hyzion.net) to
   link that Discord account.
4. Run `/mystic license` on the server and register the displayed server
   licensing id in the portal.
5. Download `license.mclicense` and place it at
   `mods/MysticEssentials/license.mclicense`.
6. Run `/mystic license reload`, followed by `/mystic reload` to start the newly
   unlocked module without restarting the server.

The Discord partnership, linked Discord account, and qualifying Patreon
membership must remain valid. A missing, expired, server-mismatched, or invalid
license only prevents `customcontent` from enabling; every unlicensed Mystic
Essentials feature continues to work and the server still starts. The
`mysticessentials.license` permission controls `/mystic license [reload]`.

## Enable

Set the following in `mods/MysticEssentials/config.json`:

```json
{
  "modules": {
    "customcontent": true
  }
}
```

The first enable generates
`mods/MysticEssentials/modules/customcontent/config.json`.

## Files and migration

- `modules/customcontent/dialogs.json` stores CustomDialogs definitions.
- `modules/customcontent/guis/*.gui.html` stores CustomGUIs.
- Dialogs export to `mods/QuestLines/quests/<id>.json`.
- Existing standalone `QuestLinesDialog/dialogs.json` and
  `QuestLinesGUI/guis/*.gui.html` files are copied on first enable when the
  corresponding Essentials destination does not already exist.
- Compatible QuestLines quest exports are imported into the builder without
  overwriting a dialog already held by Essentials.

Standalone source data is never removed or modified.

## Dialog builder

`/customdialogs` opens the native builder. It supports dialog title, NPC name, window
width, ordered pages, per-page continue labels, and ordered response buttons
with comma-separated QuestLines actions. Saving exports immediately. Assign NPC
starts QuestLines' assignment mode when the QuestLines integration is present.

## CustomGUIs

A `.gui.html` document describes a layout tree that the server compiles once at
load and renders into the client at open time. Structure is sent as inline
Hytale UI markup rather than shipped `.ui` files, so editing a document and
running `/customguis reload` changes the layout with no restart and no asset
pack rebuild.

Documents live in `modules/customcontent/guis/`. Three worked examples are
written there when the folder is empty: `hub.gui.html` (three-column info
centre), `nameplate.gui.html` (overlay) and `profilecard.gui.html` (overlay with
live counters).

### Document root

The root element is `<gui>` for a window or `<hud>` for a screen overlay.

| Attribute | Applies to | Meaning |
|---|---|---|
| `id` | both | Document id; defaults to the filename |
| `command` | both | Registers `/<command>` as an alias that opens it |
| `title` | page | Window title |
| `data-custom-frame` | page | `container` (default), `decorated`, `plain` |
| `data-custom-width` / `-height` | both | Window size in pixels. **Set both on a HUD**: an overlay with no width stretches to the opposite screen edge |
| `data-custom-lifetime` | page | `dismiss` (default), `interaction`, `locked` |
| `data-custom-refresh-interval` | both | Seconds between live value refreshes; `0` disables |
| `data-custom-close` / `-refresh` | both | Default close/rebuild behaviour after an action |
| `data-custom-anchor` | hud | `top-left`, `top`, `top-right`, `left`, `center`, `right`, `bottom-left`, `bottom`, `bottom-right` |
| `data-custom-offset-x` / `-offset-y` | hud | Distance from the anchored corner |
| `data-custom-z-order` | hud | Draw order against other overlays |
| `data-custom-auto-show` | hud | Show to every player on join |

Resizing a window is `data-custom-width` / `data-custom-height` plus a reload —
the size is applied to the shell at runtime, not baked into an asset.

### Layout elements

| Element | Renders as |
|---|---|
| `<row>` | Children flow left to right |
| `<column>` | Children stack downwards |
| `<grid data-custom-columns="n">` | Wrapping grid; cells share the grid width evenly |
| `<scroll>` | Scrolling column |
| `<center>` | Children centred both ways |
| `<panel>` | Container with a background, padding and an optional `data-custom-title` |
| `<spacer>`, `<separator>` | Fixed gap, horizontal rule |

A `<div>` carrying any style attribute is treated as a container, so plain HTML
wrappers lay out as written; a `<div>` with no styling is transparent and only
its children are kept.

### Content elements

| Element | Renders as |
|---|---|
| `<heading>`, `<h1>`–`<h3>` | Bold heading text |
| `<text>`, `<p>`, `<label>` | Body text (wraps by default) |
| `<section>` | Small uppercase divider label |
| `<card>` | Clickable tile with icon, `data-custom-title` and `data-custom-subtitle` |
| `<navbutton>` | Clickable sidebar row with an accent bar |
| `<button>` | Clickable box, or Hytale chrome with `data-custom-variant="primary\|secondary\|tertiary"` |
| `<toggle>` / `<input type="checkbox">` | On/off row running check/uncheck actions |
| `<select>` with `<option>` | Dropdown |
| `<field>` / `<input>` | Text input |
| `<search>` | Text input plus a submit button |
| `<item>` | Item icon from `data-custom-item-id` |
| `<image>` | Texture from `data-custom-src` |
| `<player-portrait>` | Downloaded/cached half-body portrait for the selected username |
| `<progress data-custom-value="0.65">` | Value bar |

Player portraits are enabled by default. `playerPortraitApiTemplate` controls
the HTTPS endpoint (`{username}` is URL encoded), and
`playerPortraitCacheHours` controls the disk cache under the module directory.
Set `playerPortraitsEnabled: false` to disable remote portrait fetching.

### Style attributes

Any element accepts: `data-custom-width`, `-height`, `-min-width`, `-max-width`,
`-flex`, `-margin[-top|-bottom|-left|-right]`, `-pad[-x|-y|-top|-bottom|-left|-right]`,
`-gap`, `-bg`, `-bg-hover`, `-bg-press`, `-bg-image`, `-accent`, `-color`,
`-size`, `-bold`, `-uppercase`, `-wrap`, `-align`, `-valign`, `-flow`, `-scroll`.

Colours are `#rrggbb`, `#rrggbb(alpha)`, or a palette name (`muted`, `accent`,
`danger`, `success`, `dim`, `panel`). The `class` attribute is a shorthand for
the common ones: `bold`, `uppercase`, `wrap`, `scroll`, `center`, `right`,
`tiny`, `small`, `large`, `huge`, `muted`, `dim`, `accent`, `danger`,
`success`, `fill`.

`data-custom-flex="1"` makes an element take the remaining space along its
parent's axis — the usual way to make a centre column fill a row.

### Actions

`data-custom-actions` holds a pipe-separated list that runs on click. These
verbs are built in, so a document works on a stock server with no companion
plugin — before, every action was forwarded to the compatibility plugin, and
without it a click did nothing at all:

| Action | Effect |
|---|---|
| `opengui:<id>` | Opens another document; swapped in place, so no reload flicker |
| `close` | Closes the window |
| `hud:show <id>` / `hud:hide <id>` | Toggles a HUD document (`showhud:`/`hidehud:` also work) |
| `command:<cmd>` | Runs the command as the clicking player |
| `console:<cmd>` | Runs the command as the server console |
| `message:<text>` | Sends the player a chat message (supports `&` colours) |
| `broadcast:<text>` | Sends the message to every online player |

A verb may be separated from its argument by `:` or a space, and the argument
goes through placeholder substitution first. Anything not in this table is
forwarded to the compatibility plugin unchanged; when no such plugin is
connected, the player is told which action failed and the server logs it,
rather than the click being dropped in silence.

Actions after a navigation still run; only a second `opengui` or `close` is
skipped, since it would fight the surface the first one just opened.

Input values reach actions through `{name}` interpolation. Control characters
are stripped from typed values, but interpolating an input directly into a
`console:` action still hands a player control over part of a console command —
put inputs in arguments (`console:say {q}`), never in the verb position.

### Behaviour

`data-custom-req` hides an element until its requirements pass, and
`data-custom-click-req` gates the click itself. Both are evaluated by the
compatibility plugin, so without one every element is visible and clickable.
Toggles use `data-custom-check-actions`, `-uncheck-actions` and
`-check-req`. Options carry their own actions, requirements and close/refresh
overrides. Give an input `data-custom-name="q"` and any action can interpolate
its current value with `{q}`.

### Placeholders

Any text in a document — labels, card titles, placeholder text, action
arguments — is resolved before it reaches the client. Three syntaxes work:

| Syntax | Resolved by |
|---|---|
| `{name}` | Mystic's own registry |
| `%mystic_name%` / `%mysticessentials_name%` | Mystic's registry, also published to PlaceholderAPI |
| `%name%` | PlaceholderAPI first; anything it leaves untouched falls back to Mystic's registry |

Mystic registers `player_name`, `server_name`, `group`, `playtime`,
`playtime_total`, `playtime_active`, `playtime_idle`, `playtime_session`, their
raw `_seconds`/whole `_hours` variants, `luckperms_prefix` and
`luckperms_suffix`. So
`%player_name%` works in a GUI whether or not any PlaceholderAPI expansion
publishes that name — an author has no reason to care which side of the
integration a value comes from. A `%token%` nobody resolves is left exactly as
written, so a typo is visible rather than silently blank.

Note that `{name}` is also the syntax for interpolating an input's value into
an action. An input named after a registered placeholder would shadow it, so
prefer distinct input names.

### Live values

Text containing a placeholder is re-sent on `data-custom-refresh-interval`
without rebuilding the surface, so scroll position and input focus survive a
refresh. Requirement-gated elements have their visibility re-evaluated on the
same tick. Placeholders resolve through the Mystic Essentials placeholder
service, plus the compatibility engine's variables when it is connected.

### HUD overlays

HUD documents render the same tree as pages but receive no clicks — the client
does not route events to overlays. Show one with
`/customguis hud show <id> [player]`, hide it with `hud hide`, or set
`data-custom-auto-show="true"` to give it to every player on join.

### Failure handling

Custom UI is unforgiving: appending a document the client does not have, or
setting a property on an element it cannot find, drops the player's connection
rather than being ignored. A mod that appends a HUD on join and ships a jar
missing that document makes the server unjoinable. The layout engine therefore:

- checks every shipped `.ui` path against the mod jar before appending it, and
  logs one `SEVERE` line at load listing anything missing rather than letting it
  reach a client;
- refuses to open a page or HUD at all when a shell document is absent, telling
  the player the interface is unavailable instead of disconnecting them;
- skips a widget whose template is missing and logs it, rendering the rest;
- restricts the refresh loop to ids it actually emitted, so a repeating tick can
  never address a stranger.

A `.ui` document that instantiates a `Common.ui` component must supply every
parameter that component requires. In `Common.ui` a parameter written
`@X = <default>` is optional; one that is only *referenced* is required — the
whole TextButton family references `Text: @Text` with no default. Omitting it
leaves the property unresolved, and the failure is **not contained to that
document**: the client stops resolving documents belonging to other asset
packs, so an unrelated mod appending its own HUD disconnects every player at
world join. The `validateUiDocuments` Gradle task derives these requirements
from the installed `Assets.zip` and fails the build on any violation.

Element ids are namespaced for the same reason. Selectors are matched by name
with nothing scoping them per document, so every id this module emits is
prefixed — shells use `#MysticLayout*`, overlays `#MysticLayoutHudRoot`, and
generated ids carry a per-document prefix (`#MePHub3`, `#MeHNameplate1`). Two
overlays from different mods that both name their root `#HudRoot` are a
collision waiting to happen; nothing here uses a generic id.

### Limits

`maxGuiElements` in the module config caps how many elements one document
compiles to (default 400), counting every nested container and label. Trees
nest up to 12 levels deep.

## Soft dependency behavior

QuestLines is discovered through a reflection bridge, so MysticEssentials still
loads when it is missing. Editing and file import/export continue to work.
Requirement checks, action execution, variable formatting, `opengui:`, quest
reload, and NPC assignment become active when QuestLines is available.
