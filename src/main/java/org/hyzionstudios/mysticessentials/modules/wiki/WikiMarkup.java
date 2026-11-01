package org.hyzionstudios.mysticessentials.modules.wiki;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The markup wiki bodies are written in, and the blocks it renders to.
 *
 * <p>Still small enough to guess from one example. Staff type it into a text
 * box in game; the editor shows the rendered page beside it as they type.</p>
 *
 * <pre>
 * ## A heading            ### A smaller heading
 * A paragraph. Blank lines separate paragraphs.
 * - A bullet              &gt; A callout
 * ---                     (a rule)
 * &amp;6Gold&amp;r text, &lt;#67a5da&gt;any colour&lt;/#67a5da&gt;, &lt;gradient:#d9b572:#67a5da&gt;gradients&lt;/gradient&gt;
 * [[another-page]] or [[another-page|a label]] on its own line is a button
 * [Our Discord](https://discord.gg/...) or [Go to spawn](/spawn) on its own line too
 * {{image:Hyzion Wiki|A caption}}   an icon by picker name, custom:name or path
 * {{item:Tool_Pickaxe_Copper|Your first pick}}
 * {{icons:Hyzion Home, Hyzion Warp}}   {{items:Plant_Fruit_Apple, Tool_Hatchet_Copper}}
 * {{page:rules}}           the other page's body, inline
 * </pre>
 *
 * <p>Links written inside a paragraph are highlighted and collected under the
 * page as buttons: a text line in this UI cannot hold a button of its own.</p>
 *
 * <p>Nothing here interprets the client's own rich-text markup: a body is
 * player-authored text that reaches other players' screens. {@code MysticText}
 * colour codes are the one exception, because the people who can edit articles
 * are the people who can already colour chat.</p>
 */
final class WikiMarkup {

    /** What a rendered line is. */
    enum Kind {
        HEADING, SUBHEADING, PARAGRAPH, BULLET, QUOTE, LINK, RULE, IMAGE, ITEM, ICONS, ITEMS, EMBED
    }

    /**
     * One rendered line.
     *
     * @param text    the line's text, or an image/item caption
     * @param target  a link's target (page id, {@code https://} URL or {@code /command}),
     *                an image or item reference, or the embedded page id
     * @param refs    the references of a gallery ({@link Kind#ICONS}, {@link Kind#ITEMS})
     */
    record Block(Kind kind, String text, String target, List<String> refs) {

        Block(Kind kind, String text, String target) {
            this(kind, text, target, List.of());
        }
    }

    /** Widest gallery the reader draws; the rest of a longer list is dropped. */
    static final int MAX_GALLERY = 8;

    /** Highlight for a link inside running text (Hyzion accent.blue). */
    private static final String LINK_COLOUR = "<#8fc2ec><u>";
    private static final String LINK_END = "</u></#8fc2ec>";

    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?\\]\\]");
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)");
    private static final Pattern MACRO = Pattern.compile("\\{\\{\\s*([a-zA-Z]+)\\s*:\\s*([^}|]+?)\\s*(?:\\|\\s*([^}]*?)\\s*)?\\}\\}");

    private WikiMarkup() {
    }

    /** @return the body as rendered lines, paragraphs already joined. */
    static List<Block> parse(String body) {
        List<Block> blocks = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return blocks;
        }
        StringBuilder paragraph = new StringBuilder();
        for (String raw : lines(body)) {
            String line = raw.strip();
            if (line.isEmpty()) {
                flush(blocks, paragraph);
                continue;
            }
            Block block = lineBlock(line);
            if (block == null) {
                if (paragraph.length() > 0) {
                    paragraph.append(' ');
                }
                paragraph.append(line);
                continue;
            }
            flush(blocks, paragraph);
            blocks.add(block);
        }
        flush(blocks, paragraph);
        return blocks;
    }

    /** A line that is a block of its own, or {@code null} for paragraph text. */
    private static Block lineBlock(String line) {
        if (line.startsWith("### ")) {
            return new Block(Kind.SUBHEADING, inline(line.substring(4).strip()), "");
        }
        if (line.startsWith("## ")) {
            return new Block(Kind.HEADING, inline(line.substring(3).strip()), "");
        }
        if (line.equals("---") || line.equals("***")) {
            return new Block(Kind.RULE, "", "");
        }
        if (line.startsWith("- ") || line.startsWith("* ")) {
            return new Block(Kind.BULLET, inline(line.substring(2).strip()), "");
        }
        if (line.startsWith("> ")) {
            return new Block(Kind.QUOTE, inline(line.substring(2).strip()), "");
        }
        Matcher wiki = WIKI_LINK.matcher(line);
        if (wiki.matches()) {
            String target = wiki.group(1).strip();
            return new Block(Kind.LINK, wiki.group(2) == null ? target : wiki.group(2).strip(), target);
        }
        Matcher md = MD_LINK.matcher(line);
        if (md.matches()) {
            return new Block(Kind.LINK, md.group(1).strip(), md.group(2).strip());
        }
        Matcher macro = MACRO.matcher(line);
        if (macro.matches()) {
            String name = macro.group(1).toLowerCase(Locale.ROOT);
            String value = macro.group(2).strip();
            String caption = macro.group(3) == null ? "" : macro.group(3).strip();
            return switch (name) {
                case "image", "icon" -> new Block(Kind.IMAGE, caption, value);
                case "item" -> new Block(Kind.ITEM, caption, value);
                case "icons", "images" -> new Block(Kind.ICONS, caption, "", list(value));
                case "items" -> new Block(Kind.ITEMS, caption, "", list(value));
                case "page", "embed" -> new Block(Kind.EMBED, caption, value.toLowerCase(Locale.ROOT));
                default -> null;
            };
        }
        return null;
    }

    private static List<String> list(String value) {
        List<String> refs = new ArrayList<>();
        for (String part : value.split(",")) {
            String ref = part.strip();
            if (!ref.isEmpty() && refs.size() < MAX_GALLERY) {
                refs.add(ref);
            }
        }
        return refs;
    }

    private static void flush(List<Block> blocks, StringBuilder paragraph) {
        if (paragraph.length() == 0) {
            return;
        }
        blocks.add(new Block(Kind.PARAGRAPH, inline(paragraph.toString()), ""));
        paragraph.setLength(0);
    }

    /**
     * Every link in running text, target to label, in the order they appear.
     * Whole-line links are buttons already, so they are left out: listing them
     * again under the page would be the same button twice.
     */
    static Map<String, String> relatedLinks(String body) {
        Map<String, String> related = new LinkedHashMap<>();
        if (body == null) {
            return related;
        }
        for (String raw : lines(body)) {
            String line = raw.strip();
            if (WIKI_LINK.matcher(line).matches() || MD_LINK.matcher(line).matches()) {
                continue;
            }
            Matcher wiki = WIKI_LINK.matcher(line);
            while (wiki.find()) {
                String target = wiki.group(1).strip();
                related.putIfAbsent(target, wiki.group(2) == null ? target : wiki.group(2).strip());
            }
            Matcher md = MD_LINK.matcher(line);
            while (md.find()) {
                related.putIfAbsent(md.group(2).strip(), md.group(1).strip());
            }
        }
        return related;
    }

    /**
     * Running text with its links shown as highlighted labels.
     *
     * <p>The function form of {@code replaceAll} appends what the function
     * returns verbatim — no {@code $} group expansion — so a label must NOT be
     * run through {@code Matcher.quoteReplacement}, which would leave its
     * escaping backslashes on screen.</p>
     */
    static String inline(String text) {
        String withWiki = WIKI_LINK.matcher(text).replaceAll(match ->
                LINK_COLOUR + (match.group(2) == null ? match.group(1).strip() : match.group(2).strip()) + LINK_END);
        return MD_LINK.matcher(withWiki).replaceAll(match -> LINK_COLOUR + match.group(1).strip() + LINK_END);
    }

    /** @return the body with no markup at all, for search matching. */
    static String plain(String body) {
        if (body == null) {
            return "";
        }
        String text = WIKI_LINK.matcher(body).replaceAll(match ->
                match.group(2) == null ? match.group(1).strip() : match.group(2).strip());
        text = MD_LINK.matcher(text).replaceAll(match -> match.group(1).strip());
        text = MACRO.matcher(text).replaceAll(match -> match.group(3) == null ? "" : match.group(3));
        return text.replace("### ", "").replace("## ", "").replace("- ", "").replace("> ", "");
    }

    /** Where a link goes. */
    enum Target {
        PAGE, URL, COMMAND;

        static Target of(String target) {
            if (target.startsWith("https://") || target.startsWith("http://")) {
                return URL;
            }
            return target.startsWith("/") ? COMMAND : PAGE;
        }
    }

    private static String[] lines(String body) {
        return body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    }
}
