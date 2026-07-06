---
title: Chat Formatting
description: Colors, gradients, placeholders, links, Unicode, glyphs, and channel formats.
---

# Chat Formatting

Mystic's message pipeline is:

```text
raw text -> internal placeholders -> PlaceholderAPI -> color/style parsing -> Hytale Message
```

The same formatter is used for chat, module messages, announcements, MOTD lines, and configurable prefixes.

## Placeholders

Internal placeholders use braces:

```text
{player_name}
{display_name}
{luckperms_prefix}
{luckperms_suffix}
{group}
```

PlaceholderAPI placeholders use percent signs:

```text
%mystic_player_name%
%some_other_expansion_value%
```

User chat message text is inserted after template placeholders are resolved. This prevents players from triggering server-side placeholder expansion by typing placeholder tokens into chat.

## Legacy colors

Legacy color and style codes are supported:

| Syntax | Meaning |
| --- | --- |
| `&0` through `&f` | Legacy colors |
| `&l` | Bold |
| `&o` | Italic |
| `&n` | Underline |
| `&r` | Reset |

Hytale's message model used by Mystic does not expose strikethrough or obfuscated fields, so unsupported legacy styles are dropped.

## Hex colors

Supported forms:

```text
&#ff8800
<#ff8800>
<#f80>
<color:#ff8800>text</color>
<c:#f80>text</c>
```

## Named colors and styles

Examples:

```text
<red>Red text</red>
<gold>Gold text</gold>
<bold>Bold text</bold>
<b>Bold text</b>
<italic>Italic text</italic>
<i>Italic text</i>
<underlined>Underlined text</underlined>
<u>Underlined text</u>
```

## Gradients and rainbow

Gradients support multiple color stops:

```text
<gradient:#7b2cff:#00d4ff>Gradient text</gradient>
<gradient:#ff0000:#ffff00:#00ff00>Three-color gradient</gradient>
```

Rainbow:

```text
<rainbow>Rainbow text</rainbow>
```

## Links

Clickable links:

```text
<link:https://example.com>Open website</link>
```

Plain URLs can be auto-linked when `autoLinkPlainUrls` is enabled. Set `autoLinkPermission` to require a permission for auto-linking.

Hytale Server `0.5.6` does not expose hover text in the message protocol used by Mystic. Links are supported; hover text is not.

## Player color permissions

Player-supplied chat formatting is gated by:

| Style | Permission |
| --- | --- |
| Legacy colors | `mysticessentials.chat.color.legacy` |
| Hex colors | `mysticessentials.chat.color.hex` |
| Gradients | `mysticessentials.chat.color.gradient` |
| Rainbow | `mysticessentials.chat.color.rainbow` |
| MiniMessage-style tags | `mysticessentials.chat.color.minimessage` |
| Links | `mysticessentials.chat.color.links` |

Players without a style permission have that style stripped from their message.

## Chat format templates

Default format:

```text
{luckperms_prefix}{display_name} &8» &f{message}
```

Example permission format:

```json
{
  "id": "owner",
  "priority": 100,
  "permission": "mysticessentials.chat.format.owner",
  "format": "<gradient:#7b2cff:#00d4ff>&lOWNER</gradient> {display_name} &8» <#ffffff>{message}"
}
```

Higher priority formats win.

## Channel formats

Each channel has its own format and can optionally override by LuckPerms primary group:

```json
{
  "id": "global",
  "displayName": "Global",
  "scope": "server",
  "format": "&8[&aG&8] {luckperms_prefix}{display_name} &8» &f{message}",
  "groupFormats": {
    "admin": "&8[&aG&8] &4[Admin] {luckperms_prefix}{display_name} &8» &f{message}"
  }
}
```

## Glyphs and Unicode

Mystic ships custom chat glyph PNGs in:

```text
Common/Resources/MysticEssentials/Chat/Glyphs/
```

The glyph system can:

- Register bundled PNGs as Hytale common assets.
- Replace configured aliases or raw symbols with private-use codepoints.
- Preserve emoji sequences that rely on zero-width joiners, variation selectors, and emoji tag characters.
- Strip unsafe invisible control characters.

Permission tiers:

| Tier | Permission |
| --- | --- |
| Emoji | `mysticessentials.chat.emoji.use` |
| Custom glyphs | `mysticessentials.chat.emoji.custom` |
| Staff glyphs | `mysticessentials.chat.emoji.staff` |
| Raw Unicode symbols | `mysticessentials.chat.unicode.symbols` |
