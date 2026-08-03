package org.hyzionstudios.mysticessentials.modules.customcontent.layout;

import java.util.Locale;

/**
 * Geometry and paint values resolved from a document element's
 * {@code data-custom-*} attributes.
 *
 * <p>Every field is emitted into Hytale {@code .ui} markup, so all values are
 * validated here rather than at emit time — an unparsable colour or dimension
 * silently falls back instead of producing markup the client would reject (a
 * markup error aborts the whole page).</p>
 */
public final class UiStyle {

    /** Layout direction of a container, which also decides where gaps go. */
    public enum Flow {
        /** Children stack downwards. */
        TOP("Top"),
        /** Children stack downwards inside a scroll viewport. */
        TOP_SCROLLING("TopScrolling"),
        /** Children flow left to right. */
        LEFT("Left"),
        /** Children flow left to right and wrap onto new lines. */
        WRAP("LeftCenterWrap"),
        /** Children are centred horizontally and vertically. */
        CENTER("CenterMiddle"),
        /** Children are centred horizontally. */
        CENTER_H("Center"),
        /** Children are centred vertically. */
        MIDDLE("Middle");

        final String markup;

        Flow(String markup) {
            this.markup = markup;
        }

        boolean horizontal() {
            return this == LEFT || this == WRAP;
        }

        boolean scrolling() {
            return this == TOP_SCROLLING;
        }
    }

    private static final String HEX = "#[0-9a-fA-F]{6}";

    Integer width;
    Integer height;
    Integer minWidth;
    Integer maxWidth;
    Integer flex;
    Integer marginTop;
    Integer marginBottom;
    Integer marginLeft;
    Integer marginRight;
    Integer padTop;
    Integer padBottom;
    Integer padLeft;
    Integer padRight;
    Integer gap;

    String background;
    String backgroundHovered;
    String backgroundPressed;
    String backgroundImage;
    Integer backgroundBorder;
    String accent;
    Integer accentWidth;

    Integer fontSize;
    String textColor;
    /** Tri-state: {@code null} leaves the element's own default in place. */
    Boolean bold;
    Boolean uppercase;
    Boolean wrap;
    String alignHorizontal;
    String alignVertical;

    Flow flow;
    Boolean scroll;
    /** Requested column count for a wrapping grid; {@code null} means auto. */
    Integer columns;

    /** @return true when the element asked for any box or paint treatment. */
    boolean styled() {
        return flow != null || scroll != null || width != null || height != null || flex != null
                || background != null || backgroundImage != null || padTop != null
                || padLeft != null || padRight != null || padBottom != null || gap != null;
    }

    /** @return the flow to use for a container, defaulting to {@code fallback}. */
    Flow flow(Flow fallback) {
        Flow resolved = flow == null ? fallback : flow;
        if (Boolean.TRUE.equals(scroll) && resolved == Flow.TOP) {
            return Flow.TOP_SCROLLING;
        }
        return resolved;
    }

    /**
     * Reads every recognised style attribute from {@code element}.
     *
     * @param reader attribute lookup for one element, already handling the
     *               {@code data-custom-} / legacy {@code data-ql-} prefixes
     */
    static UiStyle read(AttributeReader reader) {
        UiStyle style = new UiStyle();
        style.width = size(reader.get("width"));
        style.height = size(reader.get("height"));
        style.minWidth = size(reader.get("min-width"));
        style.maxWidth = size(reader.get("max-width"));
        style.flex = size(reader.get("flex"));

        Integer margin = size(reader.get("margin"));
        style.marginTop = firstNonNull(size(reader.get("margin-top")), margin);
        style.marginBottom = firstNonNull(size(reader.get("margin-bottom")), margin);
        style.marginLeft = firstNonNull(size(reader.get("margin-left")), margin);
        style.marginRight = firstNonNull(size(reader.get("margin-right")), margin);

        Integer pad = size(reader.get("pad"));
        Integer padX = firstNonNull(size(reader.get("pad-x")), pad);
        Integer padY = firstNonNull(size(reader.get("pad-y")), pad);
        style.padTop = firstNonNull(size(reader.get("pad-top")), padY);
        style.padBottom = firstNonNull(size(reader.get("pad-bottom")), padY);
        style.padLeft = firstNonNull(size(reader.get("pad-left")), padX);
        style.padRight = firstNonNull(size(reader.get("pad-right")), padX);
        style.gap = size(reader.get("gap"));

        style.background = color(reader.get("bg"));
        style.backgroundHovered = color(reader.get("bg-hover"));
        style.backgroundPressed = color(reader.get("bg-press"));
        style.backgroundImage = texture(reader.get("bg-image"));
        style.backgroundBorder = size(reader.get("bg-border"));
        style.accent = color(reader.get("accent"));
        style.accentWidth = size(reader.get("accent-width"));

        style.fontSize = size(reader.get("size"));
        style.textColor = color(reader.get("color"));
        style.bold = flag(reader, "bold");
        style.uppercase = flag(reader, "uppercase");
        style.wrap = flag(reader, "wrap");
        style.alignHorizontal = alignment(reader.get("align"));
        style.alignVertical = alignment(reader.get("valign"));

        style.flow = flow(reader.get("flow"));
        style.scroll = flag(reader, "scroll");
        style.applyClasses(reader.classes());
        return style;
    }

    /**
     * Applies the shorthand vocabulary from an element's {@code class}
     * attribute. Classes never override an explicit attribute.
     */
    private void applyClasses(String classes) {
        if (classes == null || classes.isBlank()) {
            return;
        }
        for (String token : classes.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            switch (token) {
                case "bold" -> bold = defaulted(bold, Boolean.TRUE);
                case "uppercase" -> uppercase = defaulted(uppercase, Boolean.TRUE);
                case "wrap" -> wrap = defaulted(wrap, Boolean.TRUE);
                case "scroll" -> scroll = defaulted(scroll, Boolean.TRUE);
                case "center" -> alignHorizontal = defaulted(alignHorizontal, "Center");
                case "right" -> alignHorizontal = defaulted(alignHorizontal, "End");
                case "tiny" -> fontSize = defaulted(fontSize, 11);
                case "small" -> fontSize = defaulted(fontSize, 13);
                case "large" -> fontSize = defaulted(fontSize, 20);
                case "huge" -> fontSize = defaulted(fontSize, 26);
                case "muted" -> textColor = defaulted(textColor, UiTheme.TEXT_MUTED);
                case "dim" -> textColor = defaulted(textColor, UiTheme.TEXT_DIM);
                case "accent" -> textColor = defaulted(textColor, UiTheme.ACCENT);
                case "danger" -> textColor = defaulted(textColor, UiTheme.DANGER);
                case "success" -> textColor = defaulted(textColor, UiTheme.SUCCESS);
                case "fill" -> flex = defaulted(flex, 1);
                default -> {
                    // Unknown classes are ignored so authors can keep their own hooks.
                }
            }
        }
    }

    /** @return {@code value} parsed as a non-negative pixel count, else {@code null}. */
    static Integer size(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim().replace("px", ""));
            return parsed < 0 ? null : Math.min(parsed, 4096);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * @return a colour literal safe to emit — {@code #rrggbb} or
     *         {@code #rrggbb(alpha)} — or {@code null} when unparsable
     */
    static String color(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        String named = UiTheme.named(trimmed);
        if (named != null) {
            return named;
        }
        if (!trimmed.startsWith("#")) {
            trimmed = "#" + trimmed;
        }
        if (trimmed.matches(HEX)) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        java.util.regex.Matcher alpha =
                java.util.regex.Pattern.compile("^(" + HEX + ")\\(\\s*(0?\\.\\d+|0|1(?:\\.0+)?)\\s*\\)$")
                        .matcher(trimmed);
        return alpha.matches() ? alpha.group(1).toLowerCase(Locale.ROOT) + "(" + alpha.group(2) + ")" : null;
    }

    /**
     * @return a texture path safe to emit, restricted to the characters the
     *         asset pipeline accepts, else {@code null}
     */
    static String texture(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.matches("[A-Za-z0-9_./-]{1,200}\\.(png|jpg)") ? trimmed : null;
    }

    /**
     * @return {@code TRUE} for a bare or truthy attribute, {@code FALSE} for an
     *         explicit falsy one, {@code null} when the attribute is absent —
     *         the distinction matters because some elements default to on
     */
    static Boolean flag(AttributeReader reader, String name) {
        if (!reader.has(name)) {
            return null;
        }
        String value = reader.get(name);
        return value.isBlank() || value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("1");
    }

    private static String alignment(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "start", "left", "top" -> "Start";
            case "center", "centre", "middle" -> "Center";
            case "end", "right", "bottom" -> "End";
            default -> null;
        };
    }

    private static Flow flow(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "row", "left", "horizontal" -> Flow.LEFT;
            case "column", "top", "vertical" -> Flow.TOP;
            case "wrap", "grid" -> Flow.WRAP;
            case "scroll", "scrolling" -> Flow.TOP_SCROLLING;
            case "center" -> Flow.CENTER;
            case "center-h" -> Flow.CENTER_H;
            case "middle" -> Flow.MIDDLE;
            default -> null;
        };
    }

    private static <T> T firstNonNull(T value, T fallback) {
        return value == null ? fallback : value;
    }

    private static <T> T defaulted(T current, T value) {
        return current == null ? value : current;
    }

    /** Attribute lookup for one authored element. */
    interface AttributeReader {
        String get(String name);

        boolean has(String name);

        String classes();
    }
}
