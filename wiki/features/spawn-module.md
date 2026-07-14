# Spawn and Homes

The Spawn module manages your server's spawn points and every player's personal homes.

## Spawn

There are two kinds of spawn: one **global spawn** for the whole server and optional **per-world spawns**. `/spawn` sends a player to the global spawn, falling back to the current world's spawn when needed.

| Command | What it does | Permission |
| --- | --- | --- |
| `/spawn` | Teleport to spawn | `mysticessentials.spawn.use` |
| `/setspawn` | Set the global spawn to your position | `mysticessentials.spawn.set` |
| `/setworldspawn` | Set the current world's spawn | `mysticessentials.spawn.set` |

New players can be sent to spawn automatically with `teleportOnFirstJoin`, and every join can be forced to spawn with `teleportOnJoin`.

## Homes

Players save personal teleport points as homes.

| Command | What it does | Permission |
| --- | --- | --- |
| `/home` | Open the Homes UI (or go to the default home) | `mysticessentials.home.use` |
| `/home <name>` | Teleport to a named home | `mysticessentials.home.use` |
| `/homes` | Open the Homes UI | `mysticessentials.home.use` |
| `/sethome [name]` | Create or update a home | `mysticessentials.home.set` |
| `/delhome [name]` | Delete a home | `mysticessentials.home.set` |
| `/renamehome <old> <new>` | Rename a home | `mysticessentials.home.set` |

### Home limits

How many homes a player may keep is controlled by permission. Grant a numeric node and the **highest** granted value wins:

```text
lp group default permission set mysticessentials.home.limit.3 true
lp group vip     permission set mysticessentials.home.limit.10 true
lp group staff   permission set mysticessentials.home.limit.unlimited true
```

When a player has no limit node, `defaultHomeLimit` (default `3`) applies.

## Configuration

File:

```text
modules/spawn/config.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `globalSpawnEnabled` | `true` | Enable one global spawn |
| `perWorldSpawnEnabled` | `true` | Enable world-specific spawns |
| `syncGlobalSpawnToWorldProvider` | `true` | Sync global spawn to the world provider where possible |
| `teleportOnFirstJoin` | `true` | Send first-time players to spawn |
| `teleportOnJoin` | `false` | Send all joining players to spawn |
| `defaultHomeLimit` | `3` | Home limit when no permission overrides |
| `globalSpawn` | `null` | Stored global spawn location |
| `worldSpawns` | `{}` | Stored per-world spawns |

## See also

- [Permissions Reference](permissions)
- [Configuration Reference](configuration)
