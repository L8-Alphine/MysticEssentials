package org.hyzionstudios.mysticessentials.modules.wiki;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The small markup wiki bodies are written in, and the blocks it renders to.
 *
 * <p>Deliberately tiny. The people writing these pages are server staff typing
 * into a text box in game, not authors with a preview pane, so the rules have
 * to be guessable from one example:</p>
 *
 * <pre>
 * ## A heading
 * A paragraph. Blank lines separate paragraphs.
 * - A bullet
 * ---
 * [[another-page]] on its own line is a button.
 * Inline [[another-page|like this]] reads as a link and is collected
 * into Related at the foot of the page.
 * </pre>
 *
 * <p>Nothing here interprets the client's own rich-text markup: a body is
 * player-authored text that reaches other players' screens, so anything that
 * looks like markup is shown, not run. {@code MysticText} colour codes a staff
 * member types are the one exception and pass through, because the people who
 * can edit articles are the people who can already colour chat.</p>
 */
final class WikiMarkup {

    /** What a rendered line is. */
    enum Kind {
        HEADING, PARAGRAPH, BULLET, LINK, RULE
    }

    /**
     * One rendered line.
     *
     * @param target the article id, for {@link Kind#LINK}; empty otherwise
     */
    record Block(Kind kind, String text, String target) {
    }

    private static final Pattern LINK = Pattern.compile("\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?\\]\\]");

    private WikiMarkup() {
    }

    /** @return the body as rendered lines, paragraphs already joined. */
    static List<Block> parse(String body) {
        List<Block> blocks = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return blocks;
        }
        StringBuilder paragraph = new StringBuilder();
        for (String raw : body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty()) {
                flush(blocks, paragraph);
                continue;
            }
            if (line.startsWith("## ")) {
                flush(blocks, paragraph);
                blocks.add(new Block(Kind.HEADING, line.substring(3).strip(), ""));
                continue;
            }
            if (line.equals("---") || line.equals("***")) {
                flush(blocks, paragraph);
                blocks.add(new Block(Kind.RULE, "", ""));
                continue;
            }
            if (line.startsWith("- ") || line.startsWith("* ")) {
                flush(blocks, paragraph);
                blocks.add(new Block(Kind.BULLET, strip(line.substring(2).strip()), ""));
                continue;
            }
            Matcher whole = LINK.matcher(line);
            if (whole.matches()) {
                flush(blocks, paragraph);
                String target = whole.group(1).strip();
                String label = whole.group(2) == null ? target : whole.group(2).strip();
                blocks.add(new Block(Kind.LINK, label, target));
                continue;
            }
            if (paragraph.length() > 0) {
                paragraph.append(' ');
            }
            paragraph.append(line);
        }
        flush(blocks, paragraph);
        return blocks;
    }

    private static void flush(List<Block> blocks, StringBuilder paragraph) {
        if (paragraph.length() == 0) {
            return;
        }
        blocks.add(new Block(Kind.PARAGRAPH, strip(paragraph.toString()), ""));
        paragraph.setLength(0);
    }

    /**
     * Every inline link in the body, target to label, in the order they appear.
     * Whole-line links are buttons already, so they are left out: listing them
     * again under Related would be the same button twice.
     */
    static Map<String, String> relatedLinks(String body) {
        Map<String, String> related = new LinkedHashMap<>();
        if (body == null) {
            return related;
        }
        for (String raw : body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = raw.strip();
            if (LINK.matcher(line).matches()) {
                continue;
            }
            Matcher matcher = LINK.matcher(line);
            while (matcher.find()) {
                String target = matcher.group(1).strip();
                String label = matcher.group(2) == null ? target : matcher.group(2).strip();
                related.putIfAbsent(target, label);
            }
        }
        return related;
    }

    /**
     * Replaces inline {@code [[id|label]]} with its label text for display.
     *
     * <p>The function form of {@code replaceAll} appends what the function
     * returns verbatim — no {@code $} group expansion — so the label must NOT
     * be run through {@code Matcher.quoteReplacement}, which would leave its
     * escaping backslashes on screen.</p>
     */
    private static String strip(String text) {
        return LINK.matcher(text).replaceAll(match ->
                match.group(2) == null ? match.group(1).strip() : match.group(2).strip());
    }

    /** @return the body with no markup at all, for search matching. */
    static String plain(String body) {
        if (body == null) {
            return "";
        }
        return strip(body).replace("## ", "").replace("- ", "");
    }
}
