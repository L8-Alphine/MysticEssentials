package org.hyzionstudios.mysticessentials.modules.wiki;

import java.util.List;
import java.util.Map;

import org.hyzionstudios.mysticessentials.modules.wiki.WikiMarkup.Block;
import org.hyzionstudios.mysticessentials.modules.wiki.WikiMarkup.Kind;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;

/**
 * Dependency-free checks of the wiki markup: which lines become which blocks,
 * how links are drawn and collected, and that search sees the words without
 * the markup. Also loads the page colour table, whose duplicate keys would
 * only otherwise surface as every page failing at runtime.
 */
public final class WikiMarkupTest {

    public static void main(String[] args) {
        blocks();
        links();
        macros();
        searchText();
        colourTableLoads();
        System.out.println("WikiMarkupTest: all checks passed.");
    }

    private static void blocks() {
        List<Block> blocks = WikiMarkup.parse("""
                ## Title
                ### Smaller
                First line
                joins the second.

                - a bullet
                > a note
                ---
                """);
        require(kinds(blocks).equals(List.of(Kind.HEADING, Kind.SUBHEADING, Kind.PARAGRAPH, Kind.BULLET,
                Kind.QUOTE, Kind.RULE)), "block kinds: " + kinds(blocks));
        require(blocks.get(2).text().equals("First line joins the second."), "paragraph lines join with a space");
        require(WikiMarkup.parse("  \n\n").isEmpty(), "blank bodies render nothing");
    }

    private static void links() {
        List<Block> blocks = WikiMarkup.parse("""
                [[rules|Read the rules]]
                [Discord](https://discord.gg/x)
                [Spawn](/spawn)
                See [[guides]] and [our site](https://example.com) for more.
                """);
        require(blocks.get(0).kind() == Kind.LINK && blocks.get(0).target().equals("rules")
                && blocks.get(0).text().equals("Read the rules"), "a whole-line [[link|label]] is a button");
        require(WikiMarkup.Target.of(blocks.get(1).target()) == WikiMarkup.Target.URL, "https is a web link");
        require(WikiMarkup.Target.of(blocks.get(2).target()) == WikiMarkup.Target.COMMAND, "/spawn is a command");
        require(WikiMarkup.Target.of("guides") == WikiMarkup.Target.PAGE, "anything else is a page id");
        String paragraph = blocks.get(3).text();
        require(paragraph.contains("guides") && paragraph.contains("our site") && !paragraph.contains("[["),
                "inline links read as their labels: " + paragraph);
        Map<String, String> related = WikiMarkup.relatedLinks("""
                [[rules]]
                See [[guides]] and [our site](https://example.com).
                """);
        require(related.keySet().equals(java.util.Set.of("guides", "https://example.com")),
                "only inline links are collected, whole-line ones are buttons already: " + related);
    }

    private static void macros() {
        List<Block> blocks = WikiMarkup.parse("""
                {{image:Hyzion Wiki|The wiki}}
                {{item: Tool_Pickaxe_Copper }}
                {{icons:A, B, C, D, E, F, G, H, I, J}}
                {{items:Plant_Fruit_Apple}}
                {{page:Rules}}
                {{nonsense:x}}
                """);
        require(kinds(blocks).equals(List.of(Kind.IMAGE, Kind.ITEM, Kind.ICONS, Kind.ITEMS, Kind.EMBED, Kind.PARAGRAPH)),
                "macro kinds: " + kinds(blocks));
        require(blocks.get(0).target().equals("Hyzion Wiki") && blocks.get(0).text().equals("The wiki"),
                "image reference and caption");
        require(blocks.get(1).target().equals("Tool_Pickaxe_Copper"), "item ids are trimmed");
        require(blocks.get(2).refs().size() == WikiMarkup.MAX_GALLERY, "galleries stop at " + WikiMarkup.MAX_GALLERY);
        require(blocks.get(4).target().equals("rules"), "embedded page ids are lowercased like every id");
        require(blocks.get(5).text().contains("nonsense"), "an unknown macro stays visible as text");
    }

    private static void searchText() {
        String plain = WikiMarkup.plain("## Claims\nUse [[land|claiming land]] {{image:Portal|portal art}} > tip");
        require(plain.contains("claiming land") && plain.contains("portal art") && !plain.contains("{{")
                && !plain.contains("##"), "search text keeps words, drops markup: " + plain);
    }

    private static void colourTableLoads() {
        require(!MysticPage.uiTextColor("LineHeading").isBlank(), "the page colour table loads");
    }

    private static List<Kind> kinds(List<Block> blocks) {
        return blocks.stream().map(Block::kind).toList();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
