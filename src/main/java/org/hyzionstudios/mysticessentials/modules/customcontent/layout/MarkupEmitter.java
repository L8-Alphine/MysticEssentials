package org.hyzionstudios.mysticessentials.modules.customcontent.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns one {@link UiNode} into a Hytale {@code .ui} markup fragment for
 * {@code UICommandBuilder.appendInline}.
 *
 * <p>Only geometry, palette and element type reach the markup. Author-supplied
 * strings — labels, placeholders, texture paths, item ids — are pushed
 * separately as runtime values, so no document content can terminate a markup
 * literal. That matters more than usual here: a markup error aborts the parse
 * of the entire page on the client, and a value the client cannot coerce
 * disconnects the player.</p>
 *
 * <p>The fragment is emitted without children; the renderer appends children
 * into the node's own selector afterwards.</p>
 */
final class MarkupEmitter {

    /**
     * Scrollbar styling that references no textures, so it stays valid no
     * matter which document the inline fragment is resolved against.
     */
    private static final String SCROLLBAR_STYLE = "ScrollbarStyle: (Spacing: 6, Size: 6, "
            + "Background: (Color: #000000(0.25)), Handle: (Color: #7a9cc6(0.45)), "
            + "HoveredHandle: (Color: #96b8e0(0.60)), DraggedHandle: (Color: #96b8e0(0.80)));";

    private MarkupEmitter() {
    }

    /**
     * @param node       the node to emit
     * @param selector   the id this render pass assigned to the node
     * @param parentFlow layout of the container the node is appended into,
     *                   which decides whether gaps and sizes act horizontally
     * @param gap        spacing the parent applies between its children
     * @param cellWidth  width forced on grid cells, or {@code null}
     * @return a markup fragment declaring exactly one element
     */
    static String emit(UiNode node, String selector, UiStyle.Flow parentFlow, Integer gap,
            Integer cellWidth) {
        UiStyle style = node.style;
        List<String> properties = new ArrayList<>();

        String type = switch (node.kind) {
            case TEXT, HEADING, SECTION -> "Label";
            case BUTTON, TOGGLE -> "Button";
            case ITEM -> "ItemIcon";
            default -> "Group";
        };

        if (node.container()) {
            UiStyle.Flow flow = style.flow(defaultFlow(node));
            properties.add("LayoutMode: " + flow.markup + ";");
            if (flow.scrolling()) {
                properties.add(SCROLLBAR_STYLE);
            }
        }

        String anchor = anchor(node, parentFlow, gap, cellWidth);
        if (!anchor.isEmpty()) {
            properties.add("Anchor: (" + anchor + ");");
        }
        if (style.flex != null && style.flex > 0) {
            properties.add("FlexWeight: " + style.flex + ";");
        }
        String padding = padding(style);
        if (!padding.isEmpty()) {
            properties.add("Padding: (" + padding + ");");
        }

        switch (node.kind) {
            case BUTTON, TOGGLE -> properties.add(buttonStyle(style));
            case TEXT, HEADING, SECTION -> properties.add(labelStyle(node));
            default -> {
                if (style.background != null) {
                    properties.add("Background: " + style.background + ";");
                }
            }
        }

        return type + " " + selector + " {\n  " + String.join("\n  ", properties) + "\n}";
    }

    /** @return markup for the thin colour bar drawn down a node's leading edge. */
    static String accentBar(String selector, String color, Integer height) {
        return "Group " + selector + " {\n"
                + "  Anchor: (Width: " + UiTheme.DEFAULT_ACCENT_WIDTH
                + (height == null ? "" : ", Height: " + height) + ", Right: 8);\n"
                + "  Background: " + color + ";\n}";
    }

    /** @return markup for a progress track that is filled with flex weights. */
    static String progressTrack(String selector, int height) {
        return "Group " + selector + " {\n"
                + "  LayoutMode: Left;\n"
                + "  Anchor: (Height: " + height + ");\n"
                + "  Background: #000000(0.35);\n}";
    }

    /** @return markup for one weighted segment of a progress track. */
    static String progressSegment(String selector, int weight, String color) {
        return "Group " + selector + " {\n"
                + "  FlexWeight: " + Math.max(0, weight) + ";\n"
                + (color == null ? "" : "  Background: " + color + ";\n")
                + "}";
    }

    /** @return markup for a container that only exists to host an appended template. */
    static String templateHost(UiNode node, String selector, UiStyle.Flow parentFlow, Integer gap,
            Integer cellWidth) {
        List<String> properties = new ArrayList<>();
        properties.add("LayoutMode: " + (node.kind == UiNode.Kind.SEARCH ? "Left" : "Top") + ";");
        String anchor = anchor(node, parentFlow, gap, cellWidth);
        if (!anchor.isEmpty()) {
            properties.add("Anchor: (" + anchor + ");");
        }
        if (node.style.flex != null && node.style.flex > 0) {
            properties.add("FlexWeight: " + node.style.flex + ";");
        }
        return "Group " + selector + " {\n  " + String.join("\n  ", properties) + "\n}";
    }

    // ---------------------------------------------------------------- parts

    private static UiStyle.Flow defaultFlow(UiNode node) {
        return switch (node.kind) {
            case BUTTON, TOGGLE -> UiStyle.Flow.LEFT;
            default -> UiStyle.Flow.TOP;
        };
    }

    private static String anchor(UiNode node, UiStyle.Flow parentFlow, Integer gap, Integer cellWidth) {
        UiStyle style = node.style;
        List<String> parts = new ArrayList<>();
        Integer width = style.width != null ? style.width : cellWidth;
        if (width != null) {
            parts.add("Width: " + width);
        }
        if (style.height != null) {
            parts.add("Height: " + style.height);
        }
        if (style.minWidth != null) {
            parts.add("MinWidth: " + style.minWidth);
        }
        if (style.maxWidth != null) {
            parts.add("MaxWidth: " + style.maxWidth);
        }

        boolean horizontal = parentFlow != null && parentFlow.horizontal();
        Integer trailing = horizontal ? style.marginRight : style.marginBottom;
        if (trailing == null && gap != null && gap > 0) {
            trailing = gap;
        }
        addMargin(parts, "Top", style.marginTop);
        addMargin(parts, "Bottom", horizontal ? style.marginBottom : trailing);
        addMargin(parts, "Left", style.marginLeft);
        addMargin(parts, "Right", horizontal ? trailing : style.marginRight);
        return String.join(", ", parts);
    }

    private static void addMargin(List<String> parts, String name, Integer value) {
        if (value != null && value > 0) {
            parts.add(name + ": " + value);
        }
    }

    private static String padding(UiStyle style) {
        List<String> parts = new ArrayList<>();
        addMargin(parts, "Top", style.padTop);
        addMargin(parts, "Bottom", style.padBottom);
        addMargin(parts, "Left", style.padLeft);
        addMargin(parts, "Right", style.padRight);
        return String.join(", ", parts);
    }

    private static String buttonStyle(UiStyle style) {
        String base = style.background == null ? UiTheme.PANEL_BACKGROUND : style.background;
        String hovered = style.backgroundHovered == null ? UiTheme.INTERACTIVE_HOVERED : style.backgroundHovered;
        String pressed = style.backgroundPressed == null ? UiTheme.INTERACTIVE_PRESSED : style.backgroundPressed;
        return "Style: (Default: (Background: " + base + "), Hovered: (Background: " + hovered
                + "), Pressed: (Background: " + pressed + "));";
    }

    private static String labelStyle(UiNode node) {
        UiStyle style = node.style;
        List<String> parts = new ArrayList<>();
        parts.add("FontSize: " + fontSize(node));
        parts.add("TextColor: " + textColor(node));
        if (resolve(style.bold, node.kind == UiNode.Kind.HEADING)) {
            parts.add("RenderBold: true");
        }
        if (resolve(style.uppercase, node.kind == UiNode.Kind.SECTION)) {
            parts.add("RenderUppercase: true");
        }
        if (resolve(style.wrap, node.kind == UiNode.Kind.TEXT)) {
            parts.add("Wrap: true");
        }
        if (style.alignHorizontal != null) {
            parts.add("HorizontalAlignment: " + style.alignHorizontal);
        }
        parts.add("VerticalAlignment: " + (style.alignVertical == null ? "Center" : style.alignVertical));
        return "Style: (" + String.join(", ", parts) + ");";
    }

    /** @return the author's explicit choice, else the element's own default. */
    private static boolean resolve(Boolean explicit, boolean fallback) {
        return explicit == null ? fallback : explicit;
    }

    private static int fontSize(UiNode node) {
        if (node.style.fontSize != null) {
            return Math.max(8, Math.min(64, node.style.fontSize));
        }
        return switch (node.kind) {
            case HEADING -> UiTheme.HEADING_FONT_SIZE;
            case SECTION -> UiTheme.SECTION_FONT_SIZE;
            default -> UiTheme.DEFAULT_FONT_SIZE;
        };
    }

    private static String textColor(UiNode node) {
        if (node.style.textColor != null) {
            return node.style.textColor;
        }
        return switch (node.kind) {
            case HEADING -> UiTheme.TEXT_STRONG;
            case SECTION -> UiTheme.TEXT_MUTED;
            default -> UiTheme.TEXT;
        };
    }
}
