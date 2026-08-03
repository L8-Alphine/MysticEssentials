package org.hyzionstudios.mysticessentials.modules.customcontent.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Compiles a {@code .gui.html} document into a {@link UiDocument}.
 *
 * <p>The vocabulary is a superset of the original flat format: every legacy
 * tag ({@code <button>}, {@code <select>}, {@code <input type=checkbox>},
 * headings, paragraphs, {@code .item-grid-slot}) still parses to the same
 * element, and legacy {@code data-ql-*} attributes are read alongside the
 * current {@code data-custom-*} names. Layout tags ({@code <row>},
 * {@code <column>}, {@code <grid>}, {@code <panel>}, {@code <card>}, …) are
 * additions, so a document written for the flat renderer keeps working — it
 * simply becomes a single scrolling column.</p>
 */
public final class LayoutParser {

    private static final int MAX_DEPTH = 12;

    private LayoutParser() {
    }

    /**
     * @param html       document source
     * @param filenameId fallback id when the root carries none
     * @param maxNodes   hard cap on tree size; deeper content is dropped
     * @return the compiled document, or {@code null} when the source has no
     *         usable root element
     */
    public static UiDocument parse(String html, String filenameId, int maxNodes) {
        if (html == null || html.isBlank()) {
            return null;
        }
        Document document = Jsoup.parseBodyFragment(html);
        Element root = document.body().children().first();
        if (root == null) {
            return null;
        }
        Attributes attributes = new Attributes(root);

        UiDocument.Builder builder = new UiDocument.Builder();
        builder.id = safeId(orElse(attributes.get("id"), filenameId));
        if (builder.id.isBlank()) {
            return null;
        }
        builder.command = normalizeCommand(attributes.get("command"));
        builder.surface = surface(root, attributes);
        builder.title = orElse(attributes.get("title"), root.attr("data-hyui-title"));
        if (builder.title.isBlank()) {
            builder.title = builder.id;
        }

        UiDocument.Frame frame = UiDocument.frame(attributes.get("frame"));
        if (frame != null) {
            builder.frame = frame;
        }
        CustomPageLifetimeHolder.apply(builder, attributes.get("lifetime"));

        // A HUD with no stated size hugs its content, so 0 means "auto" there;
        // a page always needs a concrete window size.
        boolean hud = builder.surface == UiDocument.Surface.HUD;
        builder.width = clamp(UiStyle.size(attributes.get("width")),
                hud ? 0 : UiTheme.DEFAULT_PAGE_WIDTH,
                hud ? 0 : UiTheme.MIN_PAGE_SIZE, UiTheme.MAX_PAGE_WIDTH);
        builder.height = clamp(UiStyle.size(attributes.get("height")),
                hud ? 0 : UiTheme.DEFAULT_PAGE_HEIGHT,
                hud ? 0 : UiTheme.MIN_PAGE_SIZE, UiTheme.MAX_PAGE_HEIGHT);
        builder.refreshSeconds = clamp(UiStyle.size(attributes.get("refresh-interval")), 0, 0, 3600);
        builder.defaultClose = bool(attributes.get("close"), false);
        builder.defaultRefresh = bool(attributes.get("refresh"), true);

        UiDocument.HudAnchor hudAnchor = UiDocument.hudAnchor(attributes.get("anchor"));
        if (hudAnchor != null) {
            builder.hudAnchor = hudAnchor;
        }
        builder.hudOffsetX = clamp(UiStyle.size(attributes.get("offset-x")), 20, 0, 4096);
        builder.hudOffsetY = clamp(UiStyle.size(attributes.get("offset-y")), 20, 0, 4096);
        builder.hudZOrder = clamp(UiStyle.size(attributes.get("z-order")), 1, 0, 64);
        builder.hudAutoShow = bool(attributes.get("auto-show"), false);

        UiStyle rootStyle = UiStyle.read(attributes);
        rootStyle.flow = rootStyle.flow == null
                ? (builder.surface == UiDocument.Surface.HUD ? UiStyle.Flow.TOP : UiStyle.Flow.TOP_SCROLLING)
                : rootStyle.flow;
        UiNode tree = new UiNode(UiNode.Kind.CONTAINER, rootStyle);
        Counter counter = new Counter(Math.max(1, maxNodes));
        for (Element child : root.children()) {
            collect(child, tree, counter, 1);
        }
        builder.root = tree;
        return new UiDocument(builder);
    }

    /**
     * Appends the compiled form of {@code element} to {@code parent}. Unknown
     * tags are transparent containers, so wrapper markup an author copied from
     * a web page does not change the layout.
     */
    private static void collect(Element element, UiNode parent, Counter counter, int depth) {
        if (counter.exhausted() || depth > MAX_DEPTH) {
            return;
        }
        Attributes attributes = new Attributes(element);
        UiStyle style = UiStyle.read(attributes);
        String tag = elementName(element, attributes);

        UiNode node = switch (tag) {
            case "row" -> container(style, UiStyle.Flow.LEFT);
            case "wrap" -> container(style, UiStyle.Flow.WRAP);
            case "column", "col", "stack", "safe-area", "canvas", "dock", "overlay-layer",
                    "aspect-ratio", "responsive", "variant", "slot-content", "router-view" ->
                    container(style, UiStyle.Flow.TOP);
            case "grid" -> grid(style, attributes);
            case "scroll", "scroll-view", "virtual-list" -> container(style, UiStyle.Flow.TOP_SCROLLING);
            case "center" -> container(style, UiStyle.Flow.CENTER);
            case "panel" -> panel(style, attributes, element);
            case "card" -> card(style, attributes, element);
            case "navbutton", "nav" -> navButton(style, attributes, element);
            case "button" -> button(style, attributes, element);
            case "section" -> section(style, attributes, element);
            case "heading", "h1", "h2", "h3" -> text(UiNode.Kind.HEADING, style, attributes, element);
            case "h4", "h5", "h6", "subtitle" -> subtitle(style, attributes, element);
            case "text", "p", "label", "span" -> text(UiNode.Kind.TEXT, style, attributes, element);
            case "image", "img" -> image(style, attributes, element);
            case "player-portrait" -> portrait(style, attributes);
            case "separator", "hr" -> separator(style);
            case "spacer" -> spacer(style);
            case "toggle", "checkbox" -> toggle(style, attributes, element);
            case "select", "dropdown" -> dropdown(style, attributes, element);
            case "field", "input", "textbox" -> field(style, attributes, element);
            case "search" -> search(style, attributes, element);
            case "item" -> item(style, attributes, element);
            case "slot" -> attributes.has("item-id") || element.hasAttr("data-hyui-item-id")
                    ? item(style, attributes, element) : container(style, UiStyle.Flow.TOP);
            case "progress" -> progress(style, attributes, element);
            default -> null;
        };

        if (node == null && style.styled()) {
            // A styled <div> is a container, so authors can lay out with plain
            // HTML wrappers instead of learning the layout tag names.
            node = container(style, UiStyle.Flow.TOP);
        }

        if (node == null) {
            // Transparent wrapper: keep the children, drop the element itself.
            if (element.children().isEmpty()) {
                String own = element.ownText().trim();
                if (!own.isBlank()) {
                    UiNode label = text(UiNode.Kind.TEXT, style, attributes, element);
                    attach(parent, label, attributes, counter);
                }
                return;
            }
            for (Element child : element.children()) {
                collect(child, parent, counter, depth + 1);
            }
            return;
        }

        if (!attach(parent, node, attributes, counter)) {
            return;
        }
        if (node.container()) {
            for (Element child : element.children()) {
                collect(child, node, counter, depth + 1);
            }
        }
    }

    private static boolean attach(UiNode parent, UiNode node, Attributes attributes, Counter counter) {
        node.requirements = attributes.list("req");
        node.clickRequirements = attributes.list("click-req");
        node.close = attributes.boolValue("close");
        node.refresh = attributes.boolValue("refresh");
        node.name = safeName(attributes.get("name"));
        if (!counter.take()) {
            return false;
        }
        parent.children.add(node);
        return true;
    }

    // ---------------------------------------------------------------- kinds

    private static UiNode container(UiStyle style, UiStyle.Flow fallback) {
        style.flow = style.flow(fallback);
        return new UiNode(UiNode.Kind.CONTAINER, style);
    }

    private static UiNode grid(UiStyle style, Attributes attributes) {
        style.flow = UiStyle.Flow.WRAP;
        if (style.gap == null) {
            style.gap = UiTheme.DEFAULT_GAP;
        }
        Integer columns = UiStyle.size(attributes.get("columns"));
        if (columns != null && columns > 0) {
            // Children without an explicit width are sized to share a row of
            // this many cells; the renderer needs the grid's width to do it.
            style.columns = Math.min(columns, 12);
        }
        return new UiNode(UiNode.Kind.CONTAINER, style);
    }

    private static UiNode panel(UiStyle style, Attributes attributes, Element element) {
        style.flow = style.flow(UiStyle.Flow.TOP);
        if (style.background == null) {
            style.background = UiTheme.PANEL_BACKGROUND;
        }
        if (style.padTop == null && style.padBottom == null
                && style.padLeft == null && style.padRight == null) {
            style.padTop = UiTheme.DEFAULT_PANEL_PADDING;
            style.padBottom = UiTheme.DEFAULT_PANEL_PADDING;
            style.padLeft = UiTheme.DEFAULT_PANEL_PADDING;
            style.padRight = UiTheme.DEFAULT_PANEL_PADDING;
        }
        if (style.accent != null) {
            // An accented panel is a left-edge bar beside a content column, so
            // the panel itself has to flow horizontally.
            style.flow = UiStyle.Flow.LEFT;
        }
        UiNode node = new UiNode(UiNode.Kind.PANEL, style);
        node.text = attributes.get("title");
        if (node.text.isBlank()) {
            node.text = directText(element);
        }
        return node;
    }

    private static UiNode card(UiStyle style, Attributes attributes, Element element) {
        UiNode node = new UiNode(UiNode.Kind.BUTTON, style);
        style.flow = style.flow(UiStyle.Flow.LEFT);
        applyInteractiveDefaults(style);
        node.text = orElse(attributes.get("title"), directText(element));
        node.meta = attributes.get("subtitle");
        node.resource = attributes.get("icon");
        node.actions = actions(attributes);
        return node;
    }

    private static UiNode navButton(UiStyle style, Attributes attributes, Element element) {
        UiNode node = new UiNode(UiNode.Kind.BUTTON, style);
        style.flow = style.flow(UiStyle.Flow.LEFT);
        applyInteractiveDefaults(style);
        if (style.height == null) {
            style.height = 32;
        }
        if (style.accent == null) {
            style.accent = UiTheme.ACCENT;
        }
        node.text = orElse(attributes.get("label"), directText(element));
        node.actions = actions(attributes);
        return node;
    }

    private static UiNode button(UiStyle style, Attributes attributes, Element element) {
        String variant = attributes.get("variant").toLowerCase(Locale.ROOT);
        boolean chromed = variant.equals("primary") || variant.equals("secondary")
                || variant.equals("tertiary");
        UiNode node = new UiNode(chromed ? UiNode.Kind.CHROME_BUTTON : UiNode.Kind.BUTTON, style);
        node.variant = chromed ? variant : "";
        if (!chromed) {
            style.flow = style.flow(UiStyle.Flow.CENTER);
            applyInteractiveDefaults(style);
            if (style.height == null) {
                style.height = 36;
            }
        }
        node.text = orElse(attributes.get("label"), directText(element));
        node.actions = actions(attributes);
        return node;
    }

    private static UiNode section(UiStyle style, Attributes attributes, Element element) {
        UiNode node = new UiNode(UiNode.Kind.SECTION, style);
        node.text = orElse(attributes.get("label"), directText(element));
        return node;
    }

    private static UiNode text(UiNode.Kind kind, UiStyle style, Attributes attributes, Element element) {
        UiNode node = new UiNode(kind, style);
        node.text = orElse(attributes.get("label"), element.text().trim());
        return node;
    }

    private static UiNode subtitle(UiStyle style, Attributes attributes, Element element) {
        UiNode node = text(UiNode.Kind.TEXT, style, attributes, element);
        if (style.fontSize == null) {
            style.fontSize = 16;
        }
        style.bold = true;
        return node;
    }

    private static UiNode image(UiStyle style, Attributes attributes, Element element) {
        UiNode node = new UiNode(UiNode.Kind.IMAGE, style);
        node.resource = orElse(attributes.get("src"), element.attr("src"));
        node.text = element.attr("alt");
        if (style.height == null) {
            style.height = 120;
        }
        return node;
    }

    private static UiNode separator(UiStyle style) {
        if (style.background == null) {
            style.background = UiTheme.SEPARATOR;
        }
        if (style.height == null) {
            style.height = 1;
        }
        return new UiNode(UiNode.Kind.SEPARATOR, style);
    }

    private static UiNode spacer(UiStyle style) {
        if (style.height == null && style.width == null) {
            style.height = UiTheme.DEFAULT_GAP;
        }
        return new UiNode(UiNode.Kind.SPACER, style);
    }

    private static UiNode toggle(UiStyle style, Attributes attributes, Element element) {
        UiNode node = new UiNode(UiNode.Kind.TOGGLE, style);
        style.flow = style.flow(UiStyle.Flow.LEFT);
        applyInteractiveDefaults(style);
        if (style.height == null) {
            style.height = 36;
        }
        node.text = orElse(attributes.get("label"), directText(element));
        node.actions = attributes.list("check-actions");
        node.alternateActions = attributes.list("uncheck-actions");
        node.stateRequirements = attributes.list("check-req");
        node.active = element.hasAttr("checked");
        return node;
    }

    private static UiNode dropdown(UiStyle style, Attributes attributes, Element element) {
        UiNode node = new UiNode(UiNode.Kind.DROPDOWN, style);
        node.text = orElse(attributes.get("label"), attributes.get("placeholder"));
        List<UiNode.Option> options = new ArrayList<>();
        for (Element option : element.select("> option")) {
            Attributes optionAttributes = new Attributes(option);
            List<String> actions = optionAttributes.list("actions");
            options.add(new UiNode.Option(
                    option.hasAttr("value") ? option.attr("value") : option.text(),
                    option.text(),
                    actions,
                    optionAttributes.list("req"),
                    optionAttributes.boolValue("close"),
                    optionAttributes.boolValue("refresh")));
        }
        node.options = List.copyOf(options);
        return node;
    }

    private static UiNode field(UiStyle style, Attributes attributes, Element element) {
        if (isCheckbox(element)) {
            return toggle(style, attributes, element);
        }
        UiNode node = new UiNode(UiNode.Kind.FIELD, style);
        node.text = attributes.get("label");
        node.placeholder = orElse(attributes.get("placeholder"), element.attr("placeholder"));
        node.meta = orElse(attributes.get("value"), element.attr("value"));
        return node;
    }

    private static UiNode search(UiStyle style, Attributes attributes, Element element) {
        UiNode node = new UiNode(UiNode.Kind.SEARCH, style);
        node.placeholder = orElse(attributes.get("placeholder"), "Search...");
        node.text = orElse(attributes.get("label"), directText(element));
        node.actions = actions(attributes);
        return node;
    }

    private static UiNode item(UiStyle style, Attributes attributes, Element element) {
        UiNode node = new UiNode(UiNode.Kind.ITEM, style);
        node.resource = orElse(attributes.get("item-id"), element.attr("data-hyui-item-id"));
        node.meta = orElse(attributes.get("quantity"), element.attr("data-hyui-quantity"));
        node.text = orElse(attributes.get("label"), directText(element));
        node.actions = actions(attributes);
        return node;
    }

    private static UiNode progress(UiStyle style, Attributes attributes, Element element) {
        UiNode node = new UiNode(UiNode.Kind.PROGRESS, style);
        node.text = orElse(attributes.get("label"), directText(element));
        node.meta = attributes.get("value");
        node.value = fraction(node.meta);
        return node;
    }

    private static UiNode portrait(UiStyle style, Attributes attributes) {
        UiNode node = new UiNode(UiNode.Kind.IMAGE, style);
        node.resource = "portrait:" + orElse(attributes.get("player"), "{username}");
        node.meta = attributes.get("fallback");
        if (style.width == null) style.width = 96;
        if (style.height == null) style.height = 96;
        return node;
    }

    /** Converts v2 typed-action attributes into the runtime's action queue. */
    private static List<String> actions(Attributes attributes) {
        List<String> legacy = attributes.list("actions");
        if (!legacy.isEmpty()) {
            return legacy;
        }
        String id = attributes.get("action");
        if (id.isBlank()) {
            return List.of();
        }
        String mapped = switch (id.toLowerCase(Locale.ROOT)) {
            case "ui.close" -> "close";
            case "ui.navigate" -> "opengui:" + attributes.get("action.route");
            case "command.player" -> "command:" + attributes.get("action.command");
            case "message.send" -> "message:" + attributes.get("action.text");
            default -> {
                StringBuilder encoded = new StringBuilder("typed:").append(id);
                for (org.jsoup.nodes.Attribute attribute : attributes.element.attributes()) {
                    if (attribute.getKey().startsWith("action.")) {
                        encoded.append(';').append(attribute.getKey().substring(7))
                                .append('=').append(attribute.getValue());
                    }
                }
                yield encoded.toString();
            }
        };
        return List.of(mapped);
    }

    private static void applyInteractiveDefaults(UiStyle style) {
        if (style.background == null) {
            style.background = UiTheme.PANEL_BACKGROUND;
        }
        if (style.backgroundHovered == null) {
            style.backgroundHovered = UiTheme.INTERACTIVE_HOVERED;
        }
        if (style.backgroundPressed == null) {
            style.backgroundPressed = UiTheme.INTERACTIVE_PRESSED;
        }
        if (style.padLeft == null && style.padRight == null) {
            style.padLeft = 10;
            style.padRight = 10;
        }
    }

    // ------------------------------------------------------------- helpers

    private static UiDocument.Surface surface(Element root, Attributes attributes) {
        String declared = attributes.get("type");
        if (declared.equalsIgnoreCase("hud") || root.tagName().equalsIgnoreCase("hud")) {
            return UiDocument.Surface.HUD;
        }
        return UiDocument.Surface.PAGE;
    }

    private static String elementName(Element element, Attributes attributes) {
        String declared = attributes.get("el");
        if (!declared.isBlank()) {
            return declared.trim().toLowerCase(Locale.ROOT);
        }
        String tag = element.tagName().toLowerCase(Locale.ROOT);
        if (element.hasClass("item-grid-slot")) {
            return "item";
        }
        if (tag.equals("div")) {
            // A bare <div> is a transparent wrapper unless it opts into a role.
            return element.hasClass("panel") ? "panel" : element.hasClass("card") ? "card" : "div";
        }
        return tag;
    }

    private static boolean isCheckbox(Element element) {
        return "input".equals(element.tagName()) && "checkbox".equalsIgnoreCase(element.attr("type"));
    }

    /** @return the element's own text, ignoring text belonging to children. */
    private static String directText(Element element) {
        String own = element.ownText().trim();
        return own.isBlank() && element.children().isEmpty() ? element.text().trim() : own;
    }

    private static double fraction(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            double parsed = Double.parseDouble(raw.trim().replace("%", ""));
            if (raw.contains("%") || parsed > 1) {
                parsed /= 100d;
            }
            return Math.max(0, Math.min(1, parsed));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int clamp(Integer value, int fallback, int min, int max) {
        if (value == null) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static boolean bool(String value, boolean fallback) {
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback) : value;
    }

    /** @return {@code value} reduced to the id charset used by commands and files. */
    public static String safeId(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "_")
                .replaceAll("_+", "_");
    }

    private static String safeName(String value) {
        return value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9_-]", "");
    }

    private static String normalizeCommand(String command) {
        if (command == null) {
            return null;
        }
        String normalized = command.trim().toLowerCase(Locale.ROOT)
                .replaceFirst("^/+", "").replaceAll("[^a-z0-9_-]", "");
        return normalized.isBlank() ? null : normalized;
    }

    /** Budget shared by the whole tree so one document cannot flood a client. */
    private static final class Counter {
        private int remaining;

        Counter(int remaining) {
            this.remaining = remaining;
        }

        boolean take() {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            return true;
        }

        boolean exhausted() {
            return remaining <= 0;
        }
    }

    /** Reads {@code data-custom-*} with a {@code data-ql-*} fallback. */
    static final class Attributes implements UiStyle.AttributeReader {
        private final Element element;

        Attributes(Element element) {
            this.element = element;
        }

        @Override
        public String get(String name) {
            String custom = "data-custom-" + name;
            if (element.hasAttr(custom)) {
                return element.attr(custom);
            }
            String legacy = "data-ql-" + name;
            if (element.hasAttr(legacy)) {
                return element.attr(legacy);
            }
            if (name.equals("label") && element.hasAttr("text")) {
                return element.attr("text");
            }
            if (name.equals("src") && element.hasAttr("source")) {
                return element.attr("source");
            }
            if (name.equals("flex") && element.hasAttr("grow")) {
                return element.attr("grow");
            }
            if (name.equals("pad") && element.hasAttr("padding")) {
                return element.attr("padding");
            }
            return element.hasAttr(name) && (PLAIN_ATTRIBUTES.contains(name) || name.startsWith("action."))
                    ? element.attr(name) : "";
        }

        @Override
        public boolean has(String name) {
            return element.hasAttr("data-custom-" + name) || element.hasAttr("data-ql-" + name)
                    || ((PLAIN_ATTRIBUTES.contains(name) || name.startsWith("action.")) && element.hasAttr(name));
        }

        @Override
        public String classes() {
            return element.className();
        }

        List<String> list(String name) {
            String value = get(name);
            if (value.isBlank()) {
                return List.of();
            }
            List<String> parts = new ArrayList<>();
            for (String part : value.split("\\|")) {
                if (!part.isBlank()) {
                    parts.add(part.trim());
                }
            }
            return List.copyOf(parts);
        }

        Boolean boolValue(String name) {
            String custom = "data-custom-" + name;
            String legacy = "data-ql-" + name;
            if (element.hasAttr(custom)) {
                return Boolean.parseBoolean(element.attr(custom));
            }
            if (element.hasAttr(legacy)) {
                return Boolean.parseBoolean(element.attr(legacy));
            }
            return PLAIN_ATTRIBUTES.contains(name) && element.hasAttr(name)
                    ? Boolean.parseBoolean(element.attr(name)) : null;
        }
    }

    /** Attributes readable without a prefix because HTML already defines them. */
    private static final java.util.Set<String> PLAIN_ATTRIBUTES = java.util.Set.of(
            "id", "version", "surface", "title", "name", "value", "placeholder", "src", "alt",
            "command", "controller", "theme", "frame", "lifetime", "width", "height", "min-width",
            "max-width", "refresh-interval", "refresh", "close", "anchor", "offset-x", "offset-y",
            "z-order", "auto-show", "flow", "align", "valign", "gap", "flex", "grow", "size",
            "margin", "margin-top", "margin-right", "margin-bottom", "margin-left", "pad", "pad-x",
            "pad-y", "pad-top", "pad-right", "pad-bottom", "pad-left", "bg", "bg-image", "bg-border",
            "bg-hover", "bg-press", "color", "accent", "accent-width", "columns", "icon", "label",
            "subtitle", "variant", "item-id", "quantity", "type", "el", "action", "actions", "req",
            "click-req", "check-actions", "uncheck-actions", "check-req", "player", "fallback");

    /** Keeps the lifetime lookup out of the long parse method. */
    private static final class CustomPageLifetimeHolder {
        static void apply(UiDocument.Builder builder, String value) {
            var lifetime = UiDocument.lifetime(value);
            if (lifetime != null) {
                builder.lifetime = lifetime;
            }
        }
    }
}
