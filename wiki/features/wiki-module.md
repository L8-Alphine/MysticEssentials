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

## Reading

One window: search, categories and the page list on the left, the open page on
the right with its icon, summary and category. Typing in the search box updates
only the list, so the box keeps its focus. Links written inside the text appear
as buttons under the page (up to four); a page link opens here, a `/command`
runs as you once the window closes, and a web address is sent to your chat as
a clickable line, since a game window cannot open a browser.

## Writing a page

Press **New Page**, or **Edit** on any stored page. The editor (1440×860) shows
the page rendered beside the text as you type (**Preview**), and an **Images**
tab that searches every picture the client has:

| Filter | What it searches |
| --- | --- |
| Icons | The server's own (`custom:`), Hyzion's, the game's named icons and its other icons |
| Items | Every item, drawn as an item slot — adds `{{item:…}}` |
| Models | The game's model renders, then the textures models, blocks, NPCs and effects are painted with |
| All | Every PNG the client has (about twelve thousand) |

**Click** a result to add it to the body, **right-click** to make it the page's
icon, and hover to see its name or path. A path works anywhere an icon name
does, so `{{image:Icons/ModelsGenerated/Kweebec.png}}` is fine. The toolbar adds headings, lists, notes, rules, links,
embeds and colours. The game tells the server nothing about the cursor, so the
toolbar and pictures add **at the end** of the body.

```text
## A heading                ### A smaller heading
A paragraph. Blank lines separate paragraphs.
- A bullet                  > A note, in a gold-edged box
---                         (a line across the page)
&6Gold&r, <#67a5da>any colour</#67a5da>, <gradient:#d9b572:#67a5da>a gradient</gradient>
[[another-page]] or [[another-page|a label]] on its own line: a button
[Our Discord](https://discord.gg/...) or [Go to spawn](/spawn) on its own line: a button
{{image:Hyzion Wiki|A caption}}       an icon: picker name, custom:name or a path
{{item:Tool_Pickaxe_Copper|Your first pick}}
{{icons:Hyzion Home, Hyzion Warp}}     up to eight icons in a row
{{items:Plant_Fruit_Apple, Tool_Hatchet_Copper}}
{{page:rules}}                        another page's text, shown here
```

Links inside a paragraph are drawn blue and underlined and collected as buttons
under the page: a line of text in this UI cannot hold a button of its own.
An embedded page shows one level deep; an embed inside it becomes a link, so
two pages that embed each other cannot loop. Anything a page names that does
not exist — an icon, an item, a page — shows as a dim note saying so, which the
preview puts in front of you while you write.

Each stored page has an **icon** (the editor's Icon field): any picker name,
`custom:<name>` or `item:<ItemId>`. Left empty it shows the wiki's book.

The page id is taken from the title the first time you save (lowercased,
hyphenated) and then **never changes** — renaming it would silently break every
`[[link]]` pointing at it, so a rename is a new page plus a delete, which at
least leaves you looking at both.

Article bodies are player-authored text that reaches other players' screens, so
nothing in them is interpreted as client markup. Colour codes typed by staff do
pass through, because the people who can edit articles can already colour chat.

## Categories

The left column lists categories in the order set by `categoryOrder`,
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
