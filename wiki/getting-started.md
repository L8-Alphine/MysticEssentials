# Getting Started

## Requirements

Mystic Essentials targets Hytale Server `0.6.8` and the mod manifest supports `>=0.6.8 <0.7.0`.

Optional integrations:

| Integration | Purpose |
| --- | --- |
| LuckPerms | Permission checks, primary groups, prefixes, suffixes, and rank-aware chat formatting |
| PlaceholderAPI | Resolves external `%placeholder%` tokens and exposes Mystic placeholders as `%mystic_<name>%` |
| VaultUnlocked | Economy costs and payouts for paid warps, paid flight, kits, teleport costs, and AFK rewards |
| MysticVanish | Hides vanished players from TPA, private messages, suggestions, and join/leave/AFK announcements |
| MysticModeration | Optional moderation bridge, enabled by default when present |
| MysticIdentity | Applies managed-account communication policies to chat and private messages |
| MysticRPG | Adds content-level and safe-region checks to Random Teleport and richer item details |

Mystic Essentials works without the optional integrations. Missing integrations degrade safely: permissions fall back to Hytale permission checks, economy operations no-op successfully, and placeholders that cannot be resolved are left plain or empty according to the formatter.

## Installation

1. Build or download `MysticEssentials-1.0.4.jar`.
2. Place the jar in your server `mods/` folder.
3. Start the server once.
4. Stop the server, or keep it running if you plan to use `/mystic reload`.
5. Edit the generated files under `mods/MysticEssentials/`.
6. Run `/mystic reload` or restart the server.

The mod includes an asset pack for its custom UI resources, so keep `IncludesAssetPack` enabled in the manifest.

### Optional licensed CustomContent module

CustomGUIs and CustomDialogs are disabled by default and require a license. Join
the [Hyzion Discord](https://discord.gg/9aq3Gqg3Gy) and become a partner, purchase
an eligible [Hyzion Patreon membership](https://www.patreon.com/cw/Hyzion), then
sign in with Discord at [licenses.hyzion.net](https://licenses.hyzion.net) to link
your account and server. Run `/mystic license` to obtain the server licensing id.

Download `license.mclicense` from the licensing server, place it in `mods/MysticEssentials/`, and enable
`"customcontent": true` in the main module map, and run `/mystic license reload`
followed by `/mystic reload`. An invalid or missing license disables only this
module. See [CustomGUIs & CustomDialogs](custom-content-module) for setup and the
full authoring reference.

## First-run checklist

After first start, confirm:

| Check | Expected result |
| --- | --- |
| Server log | Mystic Essentials starts without errors and lists enabled modules |
| `mods/MysticEssentials/config.json` | Main config exists |
| `mods/MysticEssentials/modules/` | Per-module config folders exist |
| `/mystic` | Shows core information |
| `/mystic reload` | Works for staff with `mysticessentials.reload` |
| `/mystic license` | Shows license status and the server licensing id for staff with `mysticessentials.license` |
| `/spawn`, `/tpa`, `/rtp`, `/warps`, `/mail`, `/channel`, `/kit`, `/patchnotes`, `/craftblock` | Commands exist when their modules are enabled |

## Recommended setup order

1. Choose storage: JSON for testing, MySQL or MariaDB for production networks.
2. Enable Redis only if you need cross-server private messages, broadcasts, channel routing, or temporary channel restore after restart.
3. Install LuckPerms and create rank groups before assigning Mystic permissions.
4. Set spawn with `/setspawn` and, if needed, `/setworldspawn`.
5. Decide home and player-warp limits using dynamic permissions.
6. Configure chat formats and channels.
7. Configure kits, flight costs, AFK rewards, and mail limits.
8. Set up Random Teleport profiles in `modules/teleportation/rtp.json` if you want `/rtp`.
9. Review `modules/craftblock/config.json` and remove or replace the generated example ids.
10. Add your first patch notes and, if desired, enable Player Vaults.
11. Review the command and permission references before opening the server to players.
