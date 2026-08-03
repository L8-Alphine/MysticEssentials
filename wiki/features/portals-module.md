# Portals

The Portals module turns any block into a configurable portal. Walking into a portal block
teleports the player to another world, refers them to another server, or runs a command
sequence. Admins configure portals entirely in-game by pressing **Use (F)** on the portal
block.

This module replaces the standalone *PortalWorld* mod: the server-side behaviour is built
into Mystic Essentials, while the portal blocks themselves come from any asset pack that
references the `MysticPortal` interaction.

## Making a block a portal

In the block's item/block asset JSON, reference the `MysticPortal` interaction:

```json
"Interactions": {
  "CollisionEnter": {
    "Interactions": [ { "Type": "MysticPortal" } ]
  },
  "Use": {
    "Interactions": [ { "Type": "MysticPortal" } ]
  }
}
```

- `CollisionEnter` runs the portal action when a player walks in.
- `Use` opens the in-game config page for players with `mysticessentials.portal.admin`.

Optional codec fields seed the configuration of a brand-new portal the first time a block
of that type is triggered, so asset packs can ship pre-wired portals:

| Field | Effect on a newly created portal |
| --- | --- |
| `WorldName` | Pre-sets a world-teleport portal to that world |
| `Host` + `Port` | Pre-sets a server-transfer portal |

The first trigger anchors the portal at that block position and creates an entry in
`data/modules/portals/portals.json`. Breaking the anchor block deletes the portal (and its
map marker) automatically.

## Portal types

| Type | What happens on entry |
| --- | --- |
| Teleport to world | Sends the player to the target world's spawn, or to an exact position with an optional N/E/S/W facing |
| Send to server | Refers the player's client to another server (`host:port`) |
| Run command | Runs a command sequence as console or as the player |

Worlds that are known to the universe but not loaded are loaded on first entry; the player
is told to step in again once the world is up.

### Command sequences

Commands are separated with `;` (or `||`); `wait <ticks>` pauses the sequence. Placeholders:

```text
{PlayerUsername}  {PlayerUuid}  {PosX}  {PosY}  {PosZ}  {WorldName}
```

Example:

```text
spawn {PlayerUsername}; wait 20; give {PlayerUsername} torch --quantity=5
```

## Per-portal permission

Each portal can require a permission node (any node string). Players without it get the
`portal-no-permission` message instead of teleporting. Leave the field empty to allow
everyone.

## Map markers

Each portal can show a world-map marker with a custom label and icon. Icons are file names
from `Common/UI/WorldMap/MapMarkers` (vanilla ships `Portal.png`, the default; asset packs
can add their own). Markers are re-applied when players join a world and removed when the
portal is deleted or its anchor block is broken.

## Commands

| Command | What it does | Permission |
| --- | --- | --- |
| `/portal list` | List every portal with type, target, and location | `mysticessentials.portal.admin` |
| `/portal edit` | Open the config page for the nearest portal (8 blocks) | `mysticessentials.portal.admin` |
| `/portal remove <id>` | Delete a portal by id | `mysticessentials.portal.admin` |

Aliases: `/portals`.

## Storage

Portals are persisted to:

```text
data/modules/portals/portals.json
```

Entries are keyed by anchor block (`world:x:y:z`) and carry a stable `portal_<hex>` id used
by `/portal remove`.

## Anti-loop protection

Re-triggering the same portal within 2.5 seconds is ignored, and a 1.5 second teleport lock
prevents double-teleports while one is in flight — so return portals at the destination do
not bounce players straight back.
