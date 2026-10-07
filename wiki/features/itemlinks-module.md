# Item Links

Item Links let a player show off the item they are holding directly in chat. Typing the `[item]` tag captures a snapshot of the held item and rewrites the tag into a formatted, rarity-colored name that other players can open to inspect. It is a sub-feature of the [Chat module](chat-module) and is **enabled by default**.

```text
Alphine: Selling my [Frostbite Blade ×1] (/itemview a3f9) for 2,500 coins!
```

The `[Frostbite Blade ×1]` is colored by rarity and underlined; the `(/itemview a3f9)` is a short, typeable command that opens the item viewer. Recipients can also click either segment.

## How it works

1. A player types the tag (`[item]` by default) in any chat message.
2. Mystic Essentials captures a read-only **snapshot** of the item currently in their hand and stores it under a short 4-character code (e.g. `a3f9`).
3. The tag is rewritten into the item's display name — rarity color, `×quantity`, underline — followed by a `(/itemview <code>)` hint.
4. Anyone can open the read-only **Item Details** page by clicking the name, clicking or typing `/itemview <code>`, or using `/iteminspect`.

The snapshot is an immutable copy taken at send time. The sender changing, dropping, or losing the item afterwards does not change what recipients see, and the viewer is completely read-only — it can never create an inventory-compatible copy of the item.

## Viewing a shared item

| How | Result |
| --- | --- |
| Hover the item icon in the viewer | Shows Hytale's **full native item tooltip** — description (with colors), quality, durability, damage |
| Click the chat name or `(/itemview <code>)` | Opens the Item Details page |
| `/itemview <code>` | Opens the Item Details page for that code |
| `/iteminspect [latest\|<number>]` | Opens the most recent (or nth) item shared to you |
| `/itemlinks` | Opens the **Recently Shared Items** browser |

The rich, fully-colored tooltip (lore keywords, rarity, damage data) comes from the game's native item tooltip, shown when you hover the icon in the Details page or the Recent Links list. The page itself shows the icon, name, rarity, item level, id, a compact statistics list, and who shared it.

### Recently Shared Items

Each player keeps a short personal history of item links they have seen in chat. `/itemlinks` opens a scrollable list where each row shows the item icon, name, who shared it, and how long ago, with an **Inspect** button. History entries expire with their snapshots.

## Rarity

Because custom RPG items do not use the vanilla quality index, rarity is resolved by matching the **item id** against an ordered list of rules (first match wins). This rarity drives the chat name color, the Details page accent bar, and the rarity label.

The bundled rules match common keywords in the item id:

| Item id contains | Rarity | Color |
| --- | --- | --- |
| `mythic` | Mythic | red |
| `legendary` | Legendary | gold |
| `endgame`, `epic` | Epic | purple |
| `rare` | Rare | blue |
| `uncommon` | Uncommon | green |
| *(no match)* | Common | white |

Edit `classificationRules` in `modules/chat/item-view.json` to match your own naming convention. Each rule supports a plain case-insensitive substring, or a regular expression when `regex` is `true`, and may provide quality, rarity, tier, grade, and color independently.

## Commands

| Command | What it does |
| --- | --- |
| `/itemview <code>` | Open the Item Details page for a shared item code |
| `/iteminspect [latest\|<number>\|<code>]` | Inspect the latest, the nth, or a specific shared item (aliases `/inspectitem`, `/itemview`) |
| `/itemlinks` | Open the Recently Shared Items browser (alias `/recentitems`) |

The inspect and browse commands are open to everyone; only typing the `[item]` tag is permission-gated (see below).

## Configuration

File:

```text
modules/chat/item-links.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Master toggle for the subsystem |
| `tag` | `"[item]"` | The literal tag players type to share their held item |
| `usePermission` | `mysticessentials.chat.itemlink.use` | Permission to use the tag (blank = everyone) |
| `maxTagsPerMessage` | `3` | Max tags expanded per message (all resolve to the one held item) |
| `linkChatNameToInspect` | `true` | Wrap the chat name in a click-to-open link |
| `showViewCommandInChat` | `true` | Append the visible, typeable `(/itemview <code>)` hint |
| `viewCommand` | `"itemview"` | The command shown/used to open the viewer (also registered as an alias) |
| `underlineChatName` | `true` | Underline the chat name to hint interactivity |
| `showQuantityInChat` | `true` | Show the `×quantity` suffix on the chat name |
| `expiredLabel` | `[Item Link Expired]` | Replacement text for an expired snapshot |
| `unavailableLabel` | `[Item Unavailable]` | Replacement text for an unknown snapshot |
| `noItemLabel` | `[no item]` | Replacement text when the sender holds no item |

Inspection, classification, snapshot lifetime, and recent history use a second file:

```text
modules/chat/item-view.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Master toggle for item inspection |
| `display.showQuality` / `showRarity` / `showTier` / `showGrade` | `true` | Classification badges shown by the viewer |
| `display.showItemId` / `showSourceMod` | `false` / `true` | Technical identity shown in the summary |
| `display.showOriginalTooltipButton` | `true` | Show the native tooltip control |
| `display.compactBelowWidth` | `900` | Switch to the compact single-column layout below this width |
| `sections.classification` / `primaryStatistics` / `secondaryStatistics` / `modifiers` / `customSections` | `true` | Classification and statistic sections |
| `sections.requirements` / `description` / `lore` / `durability` / `shareInformation` / `technicalInformation` | `true` | Remaining detail sections |
| `fallback.hideMissingQuality` | `true` | Hide the quality badge instead of inventing one when quality is absent |
| `fallback.missingQualityDisplayName` | `Unclassified` | Label used only when missing quality is not hidden |
| `fallback.neutralAccentColor` | `#7a9cc6` | Accent used when no classification provides one |
| `snapshots.expirationMinutes` | `30` | How long a shared item stays inspectable |
| `snapshots.maxPerPlayer` | `50` | Snapshots retained per sharing player |
| `snapshots.maximumSnapshots` | `500` | Global cap on live snapshots |
| `snapshots.maximumMetadataBytes` | `65536` | Serialized metadata cap for one snapshot |
| `snapshots.historyEntriesPerPlayer` | `25` | Recent entries kept per recipient |
| `qualities` | Common through Mythic | Engine quality-index names and colors |
| `classificationRules` | keyword rules | Item-id classification overrides (see [Rarity](#rarity)) |
| `providers.catchProviderErrors` / `logProviderErrors` | `true` | Contain and log failing addon metadata providers |
| `providers.errorLogCooldownSeconds` | `60` | Suppress repeat provider logs within this window |

## Permissions

| Node | Grants |
| --- | --- |
| `mysticessentials.chat.itemlink.use` | Use the `[item]` tag to share a held item |

The `/itemview`, `/iteminspect`, and `/itemlinks` commands are not gated, so anyone can inspect items shared to them. See the [Permissions Reference](permissions).

## See also

- [Chat](chat-module)
- [Chat Formatting](chat-formatting)
