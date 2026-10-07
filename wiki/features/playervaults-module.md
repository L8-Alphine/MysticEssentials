# Player Vaults

Player Vaults give each player one or more personal storage containers they can open from anywhere. Vaults support per-rank limits, customizable metadata (name, color, icon, description), item blacklists, cross-server safety, and full admin tooling.

The module ships **disabled**. To turn it on, set `enabled: true` in `modules/playervaults/config.json` **and** `"playervaults": true` in the main config's `modules` map.

## Commands

The command is `/playervault` with aliases `/pv`, `/vault`, `/vaults`, and `/playervaults`.

| Command | What it does | Permission |
| --- | --- | --- |
| `/pv` | Open your vault list | `mysticessentials.vaults.command`, `mysticessentials.vaults.command.open` |
| `/pv <number>` | Open a specific vault | `mysticessentials.vaults.command.open` |
| `/pv edit <number>` | Edit a vault's name/color/icon/description | `mysticessentials.vaults.command.edit` + the matching `vaults.editor.*` node |
| `/pv list` | List your vaults | `mysticessentials.vaults.command.list` |
| `/pv <number> <player>` | Open another player's vault (admin) | `mysticessentials.vaults.admin.open` |
| `/pv reload` | Reload the vault config | `mysticessentials.vaults.command.reload` |

Editing sub-permissions gate each metadata field: `vaults.editor.name`, `vaults.editor.color` (and `.color.hex`), `vaults.editor.icon`, `vaults.editor.description`, and `vaults.editor.reset`.

## Vault and row limits

Both the number of vaults and the rows per vault are permission-driven, with the highest granted numeric node winning:

```text
lp group default permission set mysticessentials.vaults.vault.1 true
lp group vip     permission set mysticessentials.vaults.vault.5 true
lp group default permission set mysticessentials.vaults.rows.3 true
lp group vip     permission set mysticessentials.vaults.rows.6 true
```

Players with no node get `defaultVaults` / `defaultRows`. Config ceilings `maxVaults` and `maxRows` cap everything regardless of permission (`maxRows` is a platform-safe cap).

## Admin tools

Staff with `mysticessentials.vaults.admin` can open (including offline owners), edit, inspect read-only, unlock stuck vaults, restore from backups, and view access logs. Admin opens/edits can be logged, and the owner optionally notified. `mysticessentials.vaults.admin.bypasslimit` recovers items stored beyond a player's current limits (overflow).

## Cross-server safety

With `crossServer.enabled`, vaults use Redis-backed distributed locks so the same vault cannot be edited on two servers at once. Locks auto-expire (`lockTtlSeconds`) for crash safety and renew while a vault is open. `requireRedis` makes the module fail safe — refusing to open vaults — if Redis is expected but unavailable.

## Saving and backups

Vaults save on close and on a timer (`saveIntervalSeconds`), with optional write-through to permanent storage (recommended on networks). Timestamped backups are kept up to `maxBackupsPerVault`, and version conflicts can be captured as snapshots.

## Configuration

File:

```text
modules/playervaults/config.json
```

Key top-level settings:

| Setting | Default | Description |
| --- | --- | --- |
| `enabled` | `false` | Master switch (also enable the module in the main config) |
| `defaultVaults` | `1` | Vaults with no `vaults.vault.<n>` permission |
| `defaultRows` | `3` | Rows with no `vaults.rows.<n>` permission |
| `maxVaults` | `100` | Hard ceiling on vault numbers |
| `maxRows` | `6` | Hard ceiling on rows per vault |
| `slotsPerRow` | `9` | Slots per row in the container UI |
| `allowVaultRenaming` / `allowVaultColors` / `allowVaultIcons` / `allowVaultDescriptions` | `true` | Enable each metadata-editing surface (permissions still apply) |
| `allowAnyItemAsIcon` | `true` | Allow any eligible item to represent a vault |
| `consumeIconItem` | `false` | Consume the selected icon item when saving |
| `showLockedVaults` | `true` | Show vaults the viewer lacks permission for as locked cards |
| `preventStorageOfBlacklistedItems` | `true` | Enforce `blockedItemIds` on insert/move/restore |
| `blockedItemIds` / `blockedIconItemIds` | `[]` | Storage and icon item blacklists |
| `maxNameLength` / `maxDescriptionLength` | `32` / `96` | Vault metadata length caps |
| `defaultIconItemId` | `Furniture_Crude_Chest_Small` | Icon shown on cards with no custom icon |
| `iconPickerResultLimit` | `45` | Maximum icon search results |

Grouped blocks:

| Block | Settings |
| --- | --- |
| `crossServer` | `enabled` (`false`), `requireRedis`, `lockVaults`, `lockTtlSeconds`, `lockRenewSeconds`, `allowReadOnlyAdminViewWhenLocked`, `allowAdminForceUnlock`, `cacheTtlSeconds`, and `pubSubChannel` |
| `saving` | Save-on-close, interval, write-through, backups/retention, and conflict snapshots |
| `ui` | `useCustomVaultListUi`, `useCustomEditorUi`, `useScrollableVaultContent`, `showVaultStats`, and `showLastOpened` |
| `admin` | `logAdminOpens`, `logAdminEdits`, `notifyOnlinePlayerWhenAdminOpensVault`, `defaultAdminMode`, and `maxLogEntriesPerPlayer` |

See the [Configuration Reference](configuration) for a shorter cross-module summary.

## See also

- [Permissions Reference](permissions)
- [Storage](storage)
