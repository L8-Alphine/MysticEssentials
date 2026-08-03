package org.hyzionstudios.mysticessentials.modules.customcontent.layout;

import java.util.Locale;
import java.util.Map;

/**
 * The default palette and metrics layout documents inherit when they do not
 * state their own. Values match the Hytale container chrome so authored panels
 * sit correctly inside {@code $C.@Container} without extra styling.
 */
public final class UiTheme {

    public static final String TEXT = "#d7e1ee";
    public static final String TEXT_STRONG = "#ffffff";
    public static final String TEXT_MUTED = "#96a9be";
    public static final String TEXT_DIM = "#6f7f93";
    public static final String ACCENT = "#e8a93b";
    public static final String DANGER = "#d46a6a";
    public static final String SUCCESS = "#7fd47f";

    /** Panel fill used by {@code <panel>} and {@code <card>} when unstyled. */
    public static final String PANEL_BACKGROUND = "#000000(0.18)";
    /** Hover and press fills for interactive rows, matching the builtin rows. */
    public static final String INTERACTIVE_HOVERED = "#7a9cc6(0.18)";
    public static final String INTERACTIVE_PRESSED = "#7a9cc6(0.28)";
    public static final String SEPARATOR = "#2b3542";

    static final int DEFAULT_FONT_SIZE = 14;
    static final int HEADING_FONT_SIZE = 20;
    static final int SECTION_FONT_SIZE = 13;
    static final int DEFAULT_GAP = 8;
    static final int DEFAULT_PANEL_PADDING = 12;
    static final int DEFAULT_LINE_HEIGHT = 20;
    static final int DEFAULT_ACCENT_WIDTH = 3;

    /** Page shell defaults, overridable per document with width/height. */
    static final int DEFAULT_PAGE_WIDTH = 900;
    static final int DEFAULT_PAGE_HEIGHT = 620;
    static final int MIN_PAGE_SIZE = 200;
    static final int MAX_PAGE_WIDTH = 1800;
    static final int MAX_PAGE_HEIGHT = 1000;

    private static final Map<String, String> NAMED = Map.ofEntries(
            Map.entry("text", TEXT),
            Map.entry("white", TEXT_STRONG),
            Map.entry("strong", TEXT_STRONG),
            Map.entry("muted", TEXT_MUTED),
            Map.entry("dim", TEXT_DIM),
            Map.entry("accent", ACCENT),
            Map.entry("gold", ACCENT),
            Map.entry("danger", DANGER),
            Map.entry("red", DANGER),
            Map.entry("success", SUCCESS),
            Map.entry("green", SUCCESS),
            Map.entry("blue", "#7a9cc6"),
            Map.entry("panel", PANEL_BACKGROUND),
            Map.entry("separator", SEPARATOR),
            Map.entry("transparent", "#000000(0.0)"));

    private UiTheme() {
    }

    /** @return the palette entry for a colour name, or {@code null} if unknown. */
    static String named(String value) {
        return NAMED.get(value.toLowerCase(Locale.ROOT));
    }
}
