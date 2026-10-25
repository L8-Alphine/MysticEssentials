# Server Wiki

An in-game, editable wiki. Staff write pages from inside the game; other mods
contribute their own pages at runtime; both kinds sit in one browser, share one
id space, and link to each other.

Module id: `wiki`. Enabled by default.

## Two kinds of page

| | Stored article | Contributed entry |
| --- | --- | --- |
| Written by | Staff, in game or in the JSON file | Another mod, in code |
| Stored where | `data/modules/wiki/articles.json` | Nowhere — rendered on open |
| Editable here | Yes | No |
| When the mod is removed | Unaffected | The page leaves with it |

Contributed entries are rendered from their owner **every time they are opened**,
so a mod's reference cannot go stale, and a page can be written for the reader —
the shell's own "Shortcuts" page lists the slots *that* player can actually use.

## Commands

| Command | What it does | Permission |
| --- | --- | --- |
| `/wiki`, `/codex` | Open the browser | `mysticessentials.wiki.use` |
| `/wiki <id>` | Open one page | `mysticessentials.wiki.use` |
| `/wiki <words…>` | Search the wiki | `mysticessentials.wiki.use` |
| `/wiki new <id>` | Start a page | `mysticessentials.wiki.edit` |
| `/wiki edit <id>` | Edit a page | `mysticessentials.wiki.edit` |
| `/wiki delete <id>` | Delete a stored page | `mysticessentials.wiki.edit` |

A page can also carry its own `viewPermission`; readers without it do not see
the page in the browser, the category counts, or by id.

## Writing a page

Press **New Page** in the browser, or **Edit** on any stored page. The markup is
small on purpose — the people writing these pages are typing into a text box in
game, not authoring with a preview pane:

```text
## A heading
A paragraph. Blank lines separate paragraphs.
- A bullet
---
[[another-page]] on its own line becomes a button.
Inline [[another-page|like this]] reads as a link and is
collected into Related at the foot of the page.
```

The page id is taken from the title the first time you save (lowercased,
hyphenated) and then **never changes** — renaming it would silently break every
`[[link]]` pointing at it, so a rename is a new page plus a delete, which at
least leaves you looking at both.

Article bodies are player-authored text that reaches other players' screens, so
nothing in them is interpreted as client markup. Colour codes typed by staff do
pass through, because the people who can edit articles can already colour chat.

## Categories

The browser's left rail lists categories in the order set by `categoryOrder`,
then anything else alphabetically — so a new category appears without a config
edit. Selecting a category filters the list; **typing in the search box searches
the whole wiki**, because a hit outside the selected category is still the
answer that was asked for.

## Configuration

`mods/MysticEssentials/modules/wiki/config.json`:

```json
{
  "title": "Server Wiki",
  "subtitle": "How this server works.",
  "categoryOrder": ["Getting Started", "Rules", "Commands", "Economy", "Guides"],
  "includeModEntries": true,
  "seedStarterArticle": true
}
```

Articles live in `data/modules/wiki/articles.json`, not in the config: they are
content, they change constantly once staff start writing, and a config file
rewritten on every edit loses its comments and fights with `/mystic reload`. The
file is a plain map of id to article and is meant to be hand-editable; `/mystic
reload` re-reads it.

## For developers

```java
WikiService wiki = MysticEssentialsProvider.get().getWikiService();
if (wiki != null) {
    wiki.registerEntry(new WikiEntry(
            "mymod:getting-started", "Getting Started", "Guides",
            "How to use my mod.", List.of("mymod", "help"), 10,
            null,                       // visible to everyone
            player -> buildBodyFor(player)));
}
```

Call `unregisterNamespace("mymod")` on disable so your pages leave with you.
Your `visibleTo` and `body` run inside the wiki's page build; one that throws
costs your page and nothing else, and the failure is logged.

Link into the wiki from your own pages with `[[mymod:getting-started]]`, and
link back out to the server's own pages the same way — ids are shared.

See also [Navigation Shell](shell-module), [Developer API](developer-api).
