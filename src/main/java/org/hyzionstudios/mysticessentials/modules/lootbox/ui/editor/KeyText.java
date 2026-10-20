package org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a key's name markup into what the key editor's controls edit (a colour,
 * bold, italic, and the text) and puts it back together, and tidies lore lines.
 * Whatever markup the text itself carries is kept as it is. Engine-free;
 * {@code verifyLootBox} checks it.
 */
public final class KeyText {

    /** A leading {@code <#RRGGBB>}, then any {@code &l} / {@code &o}, then the rest. */
    private static final Pattern LEAD = Pattern.compile("^(?:<(#[0-9A-Fa-f]{6})>)?((?:&[lLoO])*)(.*)$", Pattern.DOTALL);
    private static final Pattern HEX = Pattern.compile("#?([0-9A-Fa-f]{6})(?:[0-9A-Fa-f]{2})?");

    /**
     * A name as the editor shows it.
     *
     * @param color {@code #RRGGBB} in upper case, or {@code null} for the default colour
     */
    public record Style(String color, boolean bold, boolean italic, String text) {

        public Style withColor(String next) {
            return new Style(next, bold, italic, text);
        }

        public Style withBold(boolean next) {
            return new Style(color, next, italic, text);
        }

        public Style withItalic(boolean next) {
            return new Style(color, bold, next, text);
        }

        public Style withText(String next) {
            return new Style(color, bold, italic, next);
        }
    }

    private KeyText() {
    }

    public static Style parse(String name) {
        Matcher matcher = LEAD.matcher(name == null ? "" : name);
        if (!matcher.matches()) {
            return new Style(null, false, false, name == null ? "" : name);
        }
        String flags = matcher.group(2).toLowerCase(Locale.ROOT);
        return new Style(color(matcher.group(1)), flags.contains("&l"), flags.contains("&o"), matcher.group(3));
    }

    public static String compose(Style style) {
        StringBuilder name = new StringBuilder();
        if (style.color() != null) {
            name.append('<').append(style.color()).append('>');
        }
        if (style.bold()) {
            name.append("&l");
        }
        if (style.italic()) {
            name.append("&o");
        }
        return name.append(style.text() == null ? "" : style.text()).toString();
    }

    /** {@code #RRGGBB} in upper case from a picker or typed value (an alpha byte is dropped), else {@code null}. */
    public static String color(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = HEX.matcher(raw.trim());
        return matcher.matches() ? "#" + matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    /** The lines as typed, without trailing blank ones; inner blank lines stay as spacing. */
    public static List<String> lore(List<String> lines) {
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            kept.add(line == null ? "" : line.stripTrailing());
        }
        while (!kept.isEmpty() && kept.get(kept.size() - 1).isBlank()) {
            kept.remove(kept.size() - 1);
        }
        return kept;
    }
}
