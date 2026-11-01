package org.hyzionstudios.mysticessentials.modules.wiki;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

import org.hyzionstudios.mysticessentials.modules.wiki.WikiCategories.Node;

/**
 * Nested categories, and the pages written for other installed mods: the
 * category tree, the docs a mod ships, and the names its pages get.
 */
public final class ModWikiTest {

    public static void main(String[] args) {
        categories();
        docsIndex();
        markdown();
        names();
        System.out.println("ModWikiTest: all checks passed.");
    }

    private static void categories() {
        require(WikiCategories.normalize(" Mods // Endgame & QoL /").equals("Mods/Endgame & QoL"),
                "paths are trimmed and empty parts dropped");
        require(WikiCategories.normalize("  ").equals("General"), "a blank category files under General");
        require(WikiCategories.within("Mods/Mermaids/For Owners", "mods"), "a page under a node is within it");
        require(WikiCategories.within("Mods", "MODS"), "a node is within itself, whatever the case");
        require(!WikiCategories.within("Modsets", "Mods"), "a longer name is not a child");
        require(WikiCategories.display("Mods/Mermaids").equals("Mods / Mermaids"), "paths read with spaced slashes");

        List<String> ordered = List.of("Getting Started", "Mods", "Mods/Endgame & QoL", "Mods/Mermaids",
                "Mods/Mermaids/For Owners", "Rules");
        List<String> pages = List.of("Getting Started", "Mods/Endgame & QoL", "Mods/Mermaids",
                "Mods/Mermaids/For Owners", "Mods/Mermaids/For Owners", "Rules");

        List<Node> closed = WikiCategories.rows(ordered, pages, null);
        require(closed.size() == 3, "with nothing selected only the top level shows: " + names(closed));
        Node mods = closed.get(1);
        require(mods.name().equals("Mods") && mods.count() == 4 && mods.hasChildren() && !mods.open(),
                "a parent counts everything under it and starts closed: " + mods);

        List<Node> open = WikiCategories.rows(ordered, pages, "mods/mermaids");
        require(names(open).equals(List.of("Getting Started", "Mods", "Endgame & QoL", "Mermaids", "For Owners", "Rules")),
                "selecting a node opens the way to it and its own children: " + names(open));
        Node mermaids = open.get(3);
        require(mermaids.depth() == 1 && mermaids.selected() && mermaids.open() && mermaids.count() == 3,
                "the selected node is marked, open, and counts its sub-category: " + mermaids);
        require(open.get(4).depth() == 2 && !open.get(4).hasChildren(), "a leaf sits one level deeper");

        List<Node> orphan = WikiCategories.rows(List.of("Guides/Combat"), List.of("Guides/Combat"), "Guides");
        require(names(orphan).equals(List.of("Guides", "Combat")) && orphan.get(0).count() == 1,
                "a parent with no pages of its own still appears: " + names(orphan));
    }

    private static void docsIndex() {
        String json = """
                {"documentations": [
                  {"group": "Siren", "id": "Mermaids", "name": "Mermaids", "enabled": true, "sortIndex": 300,
                   "topics": [{"file": "index.md"},
                              {"file": "owners.md", "subTopics": [{"file": "Owners/commands.md"},
                                                                   {"file": "../../secret.md"}]}]},
                  {"group": "Siren", "id": "Old", "name": "Old", "enabled": false, "topics": []}
                ]}""";
        List<ModDocs.Documentation> sets = ModDocs.documentations(json);
        require(sets.size() == 1, "a disabled documentation is skipped");
        ModDocs.Documentation set = sets.get(0);
        require(set.topics().size() == 2 && set.topics().get(1).subTopics().size() == 1,
                "sub-topics nest, and a path out of the docs folder is refused: " + set.topics());
        require(ModDocs.documentations("not json").isEmpty() && ModDocs.documentations("{}").isEmpty(),
                "a file that is not a docs index yields nothing");
        require(!ModDocs.safeFile("C:/x.md") && !ModDocs.safeFile("/abs.md") && !ModDocs.safeFile("a.txt")
                && ModDocs.safeFile("Owners/commands.md"), "only relative Markdown paths are read");
    }

    private static void markdown() {
        String md = """
                ---
                name: Mermaids Commands
                description: "Mermaids Mod - Commands"
                ---
                # Commands
                #
                Some **bold** text with <br> a tag and `code`.

                - /mermaids ui
                  - Description: Opens the menu.
                1. First step
                | Command | Permission |
                |---------|------------|
                | /mermaids | mermaids.ui |
                See [the config](config.md#top), [the site](https://mermaids.dev/a?b=1&c=2) and [a run](/op me).
                &cRed <red>tag</red> {{page:x}}
                ```
                code line
                ```
                ![logo](logo.png)
                """;
        Map<String, String> ids = Map.of("config.md", "mods:siren-mermaids.config");
        ModDocs.Doc doc = ModDocs.convert(md, "fallback", ids::get);
        require(doc.title().equals("Mermaids Commands") && doc.summary().equals("Mermaids Mod - Commands"),
                "the front matter names the page: " + doc);
        String body = doc.body();
        require(body.startsWith("## Commands\n"), "a top heading becomes a wiki heading; a bare '#' is dropped: " + body);
        require(body.contains("Some bold text with a tag and code."), "emphasis, tags and code marks are dropped: " + body);
        require(body.contains("- /mermaids ui\n> Description: Opens the menu."), "a nested bullet reads as an aside: " + body);
        require(body.contains("- 1. First step"), "numbered items keep their number: " + body);
        require(body.contains("- Command  \u00b7  Permission") && body.contains("- /mermaids  \u00b7  mermaids.ui")
                && !body.contains("---------"), "table rows become bullets and the rule row goes: " + body);
        require(body.contains("[[mods:siren-mermaids.config|the config]]"), "a link to another topic opens its page: " + body);
        require(body.contains("[the site](https://mermaids.dev/a?b=1&c=2)"), "a web address is kept exactly: " + body);
        require(body.contains("and a run.") && !body.contains("/op me"), "a link that would run a command is text: " + body);
        require(body.contains("& cRed tag { {page:x}}"),
                "another mod's colour codes and macros are shown, not applied, and its tags dropped: " + body);
        require(ModDocs.clean("a <b c").equals("a < b c"), "a stray '<' cannot open a tag");
        require(body.contains("> code line") && !body.contains("```") && !body.contains("logo"),
                "code reads as an aside, images are dropped: " + body);

        ModDocs.Doc untitled = ModDocs.convert("Just text.", "Fallback Title", null);
        require(untitled.title().equals("Fallback Title") && untitled.body().equals("Just text."),
                "a file without front matter uses the file's name");

        byte[] cp1252 = "- \u201cConfig-Information\u201d".getBytes(Charset.forName("windows-1252"));
        String decoded = ModDocs.decode(cp1252);
        require(decoded.equals("- \u201cConfig-Information\u201d"), "Windows-1252 quotes survive: " + decoded);
        require(ModDocs.convert(decoded, "x", null).body().equals("- \"Config-Information\""),
                "curly quotes become plain ones the font has");
        require(ModDocs.decode("caf\u00e9".getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals("caf\u00e9"),
                "valid UTF-8 is read as UTF-8");
    }

    private static void names() {
        require(ModWikiPages.displayName("Endgame&QoL").equals("Endgame & QoL"), ModWikiPages.displayName("Endgame&QoL"));
        require(ModWikiPages.displayName("MmoMobScaling").equals("Mmo Mob Scaling"), "camel case splits");
        require(ModWikiPages.displayName("MMOSkillTree").equals("MMO Skill Tree"), ModWikiPages.displayName("MMOSkillTree"));
        require(ModWikiPages.displayName("Alec's Tamework!").equals("Alec's Tamework!"), "punctuation stays");
        require(ModWikiPages.displayName("serverowners").equals("Serverowners"), "a file name is capitalised");
        require(ModWikiPages.slug("Config:Endgame&QoL").equals("config-endgame-qol"), ModWikiPages.slug("Config:Endgame&QoL"));
        require(ModWikiPages.slug("Alechilles:Alec's Tamework!").equals("alechilles-alec-s-tamework"),
                ModWikiPages.slug("Alechilles:Alec's Tamework!"));
    }

    private static List<String> names(List<Node> nodes) {
        return nodes.stream().map(Node::name).toList();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
