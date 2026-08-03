# Random Teleport (RTP)

Random Teleport is a subsystem of the [Teleportation module](teleportation-module). It sends players to a random, safety-checked location inside a configurable region. Destinations are described by named **profiles** (wilderness, resource world, donor-only, event, and so on), and a background search engine finds and caches safe spots so `/rtp` feels instant even on busy servers.

RTP is enabled by default. All of its behavior lives in a dedicated file, `modules/teleportation/rtp.json`, which is generated on first run with one worked-example `default-wilderness` profile.

## Player commands

The `/rtp` command is free-form parsed, so its many forms never collide.

| Command | What it does | Permission |
| --- | --- | --- |
| `/rtp` | Teleport using the default profile (or open the selection UI when `openUiOnRtp` is set) | `mysticessentials.teleport.rtp.use` |
| `/rtp menu`, `/rtp ui` | Open the RTP selection UI | `mysticessentials.teleport.rtp.use` |
| `/rtp world <world> [player]` | RTP into a specific world | `mysticessentials.teleport.rtp.use` |
| `/rtp profile <profile> [player]` | RTP using a named profile | `mysticessentials.teleport.rtp.use` |
| `/rtp biome <biome>` | Search for a destination in a biome | `mysticessentials.teleport.rtp.biome` |
| `/rtp <player>` | Send another online player (admin form) | `mysticessentials.teleport.rtp.others` |
| `/rtp cancel` | Cancel your active warmup, queued search, or pending teleport | `mysticessentials.teleport.rtp.use` |
| `/rtp status` | Show your active search phase, queue position, and per-profile cooldowns | `mysticessentials.teleport.rtp.use` |
| `/rtp info [profile]` | Show a profile's world, radius, shape, cost, warmup, and cooldown | `mysticessentials.teleport.rtp.use` |

Admin forms accept trailing flags: `--force` (skip warmup/cooldown/cost checks), `--silent` (no chat feedback to the target), and `--bypass-cost`.

## Admin commands

`/rtpadmin` (alias `/rtpa`) administers profiles and the search queue. It is a separate command so world and profile names can never be mistaken for player names.

| Command | What it does |
| --- | --- |
| `/rtpadmin reload` | Reload `rtp.json` and reconfigure the engine and service |
| `/rtpadmin test <profile>` | Run one destination search and report the coordinates and attempt count |
| `/rtpadmin preview <profile>` | Show a profile's world, shape, centre, radius, and Y range |
| `/rtpadmin debug <profile>` | Run a search and print a tally of candidate rejection reasons |
| `/rtpadmin enable <world>` / `disable <world>` | Toggle RTP for a world (disable wins over enable) |
| `/rtpadmin setcenter <profile>` | Set the profile's centre to your current X/Z |
| `/rtpadmin clearcache [profile]` | Clear cached destinations for one profile or all |
| `/rtpadmin queue` | Show active/queued search counts and the current queue |
| `/rtpadmin ui`, `/rtpadmin editor` | Open the in-game profile editor UI |
| `/rtpadmin cancel <player>` | Cancel another player's RTP |
| `/rtpadmin spread <profile> <all\|world:name>` | Random-teleport a group of players |
| `/rtpadmin queue-login <player> <profile>` | Queue an RTP that runs the next time an (offline) player logs in |

All `/rtpadmin` subcommands require `mysticessentials.teleport.rtp.admin`.

## How a teleport runs

Each `/rtp` runs the full pipeline through the `RandomTeleportService`:

1. **Resolve profile** — from the explicit argument, or from the configured selection mode (see below).
2. **Checks** — permission, per-profile cooldown, use limits, and combat state.
3. **Warmup** — a countdown the player must not interrupt.
4. **Queued search** — a safe destination is found by the background engine (respecting per-world and global concurrency limits).
5. **Reserve payment** — if the profile has a cost and the player is not exempt.
6. **Teleport and commit** — the move happens, arrival protection applies, and completion events fire.

The service never fails exceptionally for an expected outcome (no safe spot found, on cooldown, insufficient funds); callers inspect the returned status instead.

### Default selection mode

When `/rtp` is run with no world or profile, `randomTeleport.defaultSelectionMode` decides the destination:

| Mode | Behavior |
| --- | --- |
| `DEFAULT_WORLD` | Always use the configured default world/profile |
| `CURRENT_WORLD` | Use the player's current world when it has RTP enabled; otherwise fall through |
| `PER_WORLD_PROFILE` | Map the player's current world to a specific profile |
| `PERMISSION_PROFILE` | Use the highest-priority profile granted by the player's permissions |

### Warmup cancellation

Warmups can be cancelled by movement, damage, combat, world changes, or logout. Each trigger is individually toggleable under the `warmup` block, and `movementTolerance` sets how far a player may drift before movement counts.

## Profiles

Profiles are defined in the `profiles` map of `rtp.json`, keyed by id. Each profile controls its own region, safety rules, cost, and filters. Key fields:

| Field | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Whether the profile can be used |
| `displayName`, `icon`, `description` | — | Selection UI metadata |
| `world` | `"default"` | Destination world |
| `shape` | `CIRCLE` | Sampling shape (see below) |
| `center` | `0, 0` | Region centre (world X/Z) |
| `minimumRadius` | `1000` | Inner keep-out radius / ring inner edge (blocks) |
| `maximumRadius` | `15000` | Outer radius or half-extent (blocks) |
| `borderPadding` | `128` | Keep candidates this far inside the outer edge |
| `halfWidth`, `halfDepth` | `0` | `RECTANGLE` half-extents (fall back to `maximumRadius`) |
| `polygon` | `[]` | `POLYGON` vertices (world X/Z) |
| `minimumY`, `maximumY` | `20`, `300` | Vertical search band |
| `warmupSeconds` | `5` | Warmup before teleport |
| `cooldownSeconds` | `900` | Per-player cooldown |
| `searchTimeoutSeconds` | `20` | Give up a search after this long |
| `maximumSearchAttempts` | `80` | Candidate cap per search |
| `priority` | `0` | Higher wins under `PERMISSION_PROFILE` |
| `cost` | disabled | `{ enabled, amount, currency }` economy cost |
| `safety` | see below | Ground/headroom/hazard rules |
| `arrivalProtection` | see below | Post-arrival invulnerability and fall-damage grace |
| `filters` | see below | Biome, region, distance, and cross-module gates |
| `platformFallback` | disabled | Optionally build a small platform when no natural ground is found |

### Shapes

| Shape | Region sampled |
| --- | --- |
| `CIRCLE` | A filled disc between `minimumRadius` and `maximumRadius` |
| `SQUARE` | A filled square of half-extent `maximumRadius`, minus an inner `minimumRadius` square |
| `RECTANGLE` | An axis-aligned `halfWidth` × `halfDepth` rectangle |
| `RING` | Only the band between `minimumRadius` (inner) and `maximumRadius` (outer) |
| `POLYGON` | An arbitrary polygon from `polygon` vertices |
| `WORLD_BORDER` | The world border — degrades to a `maximumRadius` square where no border API is available |

### Safety and arrival

`safety` controls what counts as a valid landing spot: `requireSolidGround`, `requiredHeadroom`, `allowLiquids`, `allowLeaves`, `avoidHazardousBlocks`, `avoidStructures`, `avoidClaims`, and `avoidProtectedRegions`. `arrivalProtection` grants `invulnerabilitySeconds`, `preventFallDamageSeconds`, and can `freezeUntilLoaded` so players never fall through unloaded terrain.

### Filters and cross-module gates

The `filters` block adds optional constraints: included/excluded biomes and regions, minimum distance from spawn/other players/claims, allowed dimensions, block-tag requirements, a `permission` gate, and per-hour/per-day use caps. Fields that depend on APIs not verified on the running server — or on other Mystic mods (claims via MysticGuilds, level via MysticRPG) — are honored only when a matching capability or exclusion provider is registered; otherwise they are ignored so a search never silently fails.

## Search engine

The `searchEngine` block governs the shared background worker that finds destinations:

| Setting | Default | Description |
| --- | --- | --- |
| `maximumConcurrentSearches` | `4` | Global in-flight search cap |
| `maximumConcurrentSearchesPerWorld` | `2` | Per-world in-flight cap |
| `maximumQueueSize` | `200` | Pending search queue cap |
| `candidateChecksPerTick` | `8` | Candidates evaluated per engine tick |
| `tickIntervalMillis` | `100` | Milliseconds between engine ticks |
| `cache.enabled` | `true` | Pre-warm and reuse safe destinations |
| `cache.targetLocationsPerProfile` | `20` | Cache depth per profile |
| `cache.expirationMinutes` | `30` | Cache entry lifetime |
| `cache.revalidateBeforeTeleport` | `true` | Re-check a cached spot immediately before use |

## Queue-on-login

`/rtpadmin queue-login <player> <profile>` stores a pending RTP for an offline player. The next time they connect, the module waits a few seconds for their world to load, then runs the profile's search and teleports them — useful for staging new players or event participants.

## Permissions

See the [Permissions Reference](permissions) for the full list, including the dynamic `mysticessentials.teleport.rtp.world.<world>`, `...rtp.profile.<profile>`, `...rtp.cooldown.<seconds>`, `...rtp.limit.daily.<n>`, `...rtp.limit.hourly.<n>`, and `...rtp.priority.<n>` nodes.

## For developers

`RandomTeleportService` is a public API surface. Addons can trigger RTP, search destinations without moving anyone, and register custom `RtpDestinationValidator`s or `RtpExclusionProvider`s. See the [Developer API](developer-api) for details and the RTP event list.
