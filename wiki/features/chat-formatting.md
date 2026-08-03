# Chat Formatting

The [Chat module](chat-module) rewrites every chat message through a formatter you control. This page covers the format templates, the color and link options players can use, and the placeholder pipeline. It applies whenever `formatChat` is `true` in `modules/chat/config.json`.

## Format templates

A chat format is a template string with placeholders. The `defaultFormat` is the fallback used for anyone who does not match a rank format:

```text
{luckperms_prefix}{display_name} &8» &f{message}
```

### Rank-specific formats

The `formats` list holds permission-gated overrides. Each entry is an object:

| Field | Meaning |
| --- | --- |
| `id` | Optional label for the format (auto-derived from the permission when omitted) |
| `priority` | Higher wins when a player matches more than one format |
| `permission` | The node a player must hold to receive this format |
| `format` | The template string |

The sender's format is chosen by scanning `formats` from **highest `priority` down** and taking the first whose `permission` the sender holds; if none match, `defaultFormat` is used.

```json
"defaultFormat": "{luckperms_prefix}{display_name} &8» &f{message}",
"formats": [
  {
    "id": "owner",
    "priority": 100,
    "permission": "mysticessentials.chat.format.owner",
    "format": "<gradient:#7b2cff:#00d4ff>&lOWNER</gradient> {display_name} &8» <#ffffff>{message}"
  },
  {
    "id": "vip",
    "priority": 50,
    "permission": "mysticessentials.chat.format.vip",
    "format": "{rank_icon}&8[&6VIP&8] {display_name} &8» &f{message}"
  }
]
```

Assign the gating nodes (`mysticessentials.chat.format.owner`, `...vip`) to the matching LuckPerms groups. Format templates themselves may use the full color and gradient syntax regardless of the player's own color permissions.

## Placeholders

| Placeholder | Value |
| --- | --- |
| `{player_name}` | The account username |
| `{display_name}` | Nickname if set, otherwise the username |
| `{channel}` | The sender's current channel display name |
| `{message}` | The player's message text |
| `{luckperms_prefix}`, `{luckperms_suffix}` | LuckPerms meta prefix/suffix (when LuckPerms is present) |
| `{playtime}` / `{playtime_total}` | Total playtime, formatted (`3d 4h 21m`) |
| `{playtime_active}`, `{playtime_idle}` | Playtime split by AFK state, formatted |
| `{playtime_session}` | Length of the current session, formatted |
| `{playtime_*_seconds}`, `{playtime_*_hours}` | The same counters as raw seconds or whole hours |

The **player's message is substituted last** — after all other placeholders resolve. This means chat text is never re-parsed for placeholders or rank-icon tokens, so players cannot inject formatting or icons through what they type.

To print a literal `{` or `%`, escape it with a backslash: `\{not_a_placeholder}`.

## Colors and formatting

Mystic understands several color/format syntaxes. In **format templates** all of them always work. For **text players type**, each style is gated by a permission (in the `messageColorPermissions` map); a style the player lacks is stripped, so color stays a controllable perk.

| Style | Examples | Permission (`messageColorPermissions` key) |
| --- | --- | --- |
| Legacy codes | `&a` green, `&l` bold, `&o` italic, `&r` reset | `legacy` → `mysticessentials.chat.color.legacy` |
| Hex colors | `&#ff8800` or `<#ff8800>` | `hex` → `mysticessentials.chat.color.hex` |
| Gradients | `<gradient:#7b2cff:#00d4ff>text</gradient>` | `gradient` → `mysticessentials.chat.color.gradient` |
| Rainbow | `<rainbow>text</rainbow>` | `rainbow` → `mysticessentials.chat.color.rainbow` |
| MiniMessage | `<red>`, `<bold>`, `<italic>` | `minimessage` → `mysticessentials.chat.color.minimessage` |
| Clickable links | inline `http(s)://` URLs | `links` → `mysticessentials.chat.color.links` |

You can repoint any style at a different permission by editing its value in `messageColorPermissions` (for example, to require a single `mysticessentials.chat.color.all` node for everything).

### Legacy color code reference

`&0`–`&9` and `&a`–`&f` are the 16 legacy colors; `&k` obfuscated, `&l` bold, `&m` strikethrough, `&n` underline, `&o` italic, `&r` reset.

## Links

When `autoLinkPlainUrls` is `true`, plain `http(s)://` URLs in a message become clickable automatically. You can require a permission for this with `autoLinkPermission`. Players with the links color permission can also format links explicitly.

## How rendering works

Internally the formatter runs one of two paths:

- **String path** (default): the template is colorized and placeholders resolved into a single formatted string.
- **Rich path** (when rank icons are enabled): the template is split into literal runs and typed tokens. Literal runs go through the exact same string pipeline; typed tokens such as `{rank_icon}` become image fragments. The pieces are then assembled into one message, with empty fragments dropped and adjacent text merged.

The rich pipeline is what lets inline images coexist with legacy color codes. Rank-icon rendering reads only from in-memory state, so formatting stays fast on the async chat thread.

## Related pages

- [Chat module](chat-module) — private messages, channels, glyphs, and the full command list
- [Configuration Reference](configuration) — every `modules/chat/config.json` field
- [Permissions Reference](permissions) — all chat permission nodes
