package org.hyzionstudios.mysticessentials.modules.patchnotes;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the safe Markdown subset used in patch-note section bodies into a flat
 * list of typed, pre-wrapped display {@link Line}s. Hytale 0.5.6 Custom UI Labels
 * are plain-text only and cannot be coloured per-instance at runtime, so colour
 * is carried as a {@link Line.Type} and mapped to a distinct row template in the
 * viewer (header = blue, addition = green, removal = red, else neutral). Inline
 * markers ({@code **bold**}, {@code *italic*}, {@code `code`}, {@code [text](t)})
 * are stripped to their visible text — inline styling is not representable.
 *
 * <p>Supported per-line forms:</p>
 * <ul>
 *   <li>{@code # } / {@code ## } / {@code ### } &rarr; header</li>
 *   <li>{@code + } &rarr; addition bullet (green)</li>
 *   <li>{@code - } / {@code * } &rarr; bullet, coloured by the section type
 *       (removals &rarr; red, additions &rarr; green, else neutral)</li>
 *   <li>blank line &rarr; a spacer</li>
 *   <li>anything else &rarr; a neutral paragraph, word-wrapped</li>
 * </ul>
 */
final class PatchMarkup {

    /** Approximate character budget per rendered line before wrapping (the right pane is ~700px wide). */
    private static final int WRAP = 66;
    private static final int BULLET_WRAP = 62;

    private PatchMarkup() {
    }

    enum Type {
        HEADER,
        TEXT,
        BULLET,
        ADD,
        REMOVE,
        BLANK
    }

    /** A single rendered line: its style {@link Type} and the plain text to show. */
    record Line(Type type, String text) {
    }

    /** Renders one section (title + body) into display lines, coloured by {@code sectionType}. */
    static List<Line> renderSection(String title, String body, String sectionType) {
        List<Line> lines = new ArrayList<>();
        if (title != null && !title.isBlank()) {
            lines.add(new Line(Type.HEADER, title.trim()));
        }
        lines.addAll(renderBody(body, sectionType));
        return lines;
    }

    /** Renders a raw Markdown-subset body into display lines. */
    static List<Line> renderBody(String body, String sectionType) {
        List<Line> lines = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return lines;
        }
        for (String rawLine : body.split("\r?\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                lines.add(new Line(Type.BLANK, ""));
                continue;
            }
            if (line.startsWith("#")) {
                lines.add(new Line(Type.HEADER, stripInline(line.replaceFirst("^#{1,6}\\s*", ""))));
            } else if (line.startsWith("+ ")) {
                wrapInto(lines, Type.ADD, "+ ", stripInline(line.substring(2)), BULLET_WRAP);
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                Type bulletType = bulletTypeFor(sectionType);
                String glyph = bulletType == Type.ADD ? "+ " : bulletType == Type.REMOVE ? "- " : "• ";
                wrapInto(lines, bulletType, glyph, stripInline(line.substring(2)), BULLET_WRAP);
            } else {
                wrapInto(lines, Type.TEXT, "", stripInline(line), WRAP);
            }
        }
        return lines;
    }

    private static Type bulletTypeFor(String sectionType) {
        if (sectionType == null) {
            return Type.BULLET;
        }
        return switch (sectionType.toLowerCase(java.util.Locale.ROOT)) {
            case "additions", "added", "add" -> Type.ADD;
            case "removals", "removed", "remove" -> Type.REMOVE;
            default -> Type.BULLET;
        };
    }

    /**
     * Word-wraps {@code text} to {@code width} characters, emitting one {@link Line}
     * per visual line. The {@code prefix} (e.g. a bullet glyph) is applied to the
     * first line and indented as blanks on continuation lines so wrapped bullets
     * stay aligned.
     */
    private static void wrapInto(List<Line> out, Type type, String prefix, String text, int width) {
        if (text.isEmpty()) {
            out.add(new Line(type, prefix.stripTrailing()));
            return;
        }
        String indent = " ".repeat(prefix.length());
        StringBuilder current = new StringBuilder();
        boolean first = true;
        for (String word : text.split("\\s+")) {
            int budget = width - (first ? prefix.length() : indent.length());
            if (current.length() == 0) {
                current.append(word);
            } else if (current.length() + 1 + word.length() <= budget) {
                current.append(' ').append(word);
            } else {
                out.add(new Line(type, (first ? prefix : indent) + current));
                first = false;
                current = new StringBuilder(word);
            }
            // A single word longer than the budget is emitted as-is rather than split mid-word.
        }
        if (current.length() > 0) {
            out.add(new Line(type, (first ? prefix : indent) + current));
        }
    }

    /**
     * Strips the inline Markdown markers this UI cannot render to styled text,
     * leaving the readable content: {@code **b**}/{@code *i*}/{@code __u__} emphasis,
     * {@code `code`} backticks, and {@code [label](target)} links (kept as the label).
     */
    static String stripInline(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String out = text;
        // Links [label](target) -> label
        out = out.replaceAll("\\[([^\\]]*)\\]\\(([^)]*)\\)", "$1");
        // Emphasis / code markers -> content
        out = out.replace("**", "").replace("__", "");
        out = out.replaceAll("(?<!\\w)[*_`]", "").replaceAll("[*_`](?!\\w)", "");
        return out;
    }
}
