package org.hyzionstudios.mysticessentials.api.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** Strict, bounded compiler for v2 XML and compatible legacy HTML layouts. */
public final class UiCompiler {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.-]+(?::[a-z0-9_./-]+)?");
    private static final Set<String> CONTAINERS = Set.of("ui", "gui", "row", "column", "col", "stack",
            "grid", "wrap", "canvas", "scroll", "scroll-view", "virtual-list", "split-pane", "safe-area",
            "aspect-ratio", "center", "dock", "overlay-layer", "responsive", "panel", "container", "card",
            "router", "router-view", "route", "if", "then", "else", "switch", "case", "default", "repeat",
            "template", "slot", "slot-content", "component-definition", "theme", "styles", "style");
    private static final Set<String> LEAVES = Set.of("text", "label", "heading", "paragraph", "rich-text",
            "code-text", "localized-text", "placeholder-text", "image", "sprite", "icon", "glyph",
            "nine-slice", "player-portrait", "item-icon", "item-preview", "model-preview", "background",
            "gradient-overlay", "divider", "separator", "spacer", "button", "icon-button", "link-button",
            "toggle", "checkbox", "radio-group", "text-input", "text-area", "number-input", "search-input",
            "select", "multi-select", "slider", "step-slider", "color-picker", "keybind-input", "date-input",
            "pagination", "drag-handle", "file-reference", "command-input", "permission-input", "badge", "tag",
            "chip", "status-indicator", "progress-bar", "progress-ring", "meter", "stat-bar", "form-field");
    private static final Set<String> LEGACY_ATTRIBUTES = Set.of("data-custom-width", "data-custom-height",
            "data-custom-gap", "data-custom-flex", "data-ql-width", "data-ql-height", "data-ql-gap");

    private final int maxNodes;
    private final int maxDepth;

    public UiCompiler() {
        this(1000, 32);
    }

    public UiCompiler(int maxNodes, int maxDepth) {
        this.maxNodes = Math.max(1, maxNodes);
        this.maxDepth = Math.max(1, maxDepth);
    }

    public CompiledUiBlueprint compile(String sourceText, String sourceName) {
        if (sourceText == null || sourceText.isBlank()) {
            throw new IllegalArgumentException("UI source is empty");
        }
        String source = sourceName == null ? "<memory>" : sourceName;
        Document parsed = Jsoup.parse(sourceText, "", org.jsoup.parser.Parser.xmlParser());
        Element element = parsed.children().stream().filter(e -> !e.tagName().equals("#root")).findFirst()
                .orElseGet(() -> parsed.body() == null ? null : parsed.body().children().first());
        if (element == null) {
            throw new IllegalArgumentException("UI source has no root element");
        }

        List<UiDiagnostic> diagnostics = new ArrayList<>();
        String rootTag = element.normalName();
        boolean legacy = rootTag.equals("gui") || !"2".equals(element.attr("version"));
        int version = legacy ? 1 : 2;
        String fallback = source.replace('\\', '/');
        int slash = fallback.lastIndexOf('/');
        fallback = slash >= 0 ? fallback.substring(slash + 1) : fallback;
        fallback = fallback.replaceFirst("(?i)(\\.gui\\.html|\\.xml|\\.html)$", "");
        String id = normalizeId(first(element.attr("id"), fallback));
        if (id.isBlank() || !IDENTIFIER.matcher(id).matches()) {
            diagnostics.add(error(source, "Invalid or missing UI id"));
            id = "invalid:" + Math.abs(source.hashCode());
        }

        CompiledUiBlueprint.Kind kind = rootTag.equals("component-definition")
                ? CompiledUiBlueprint.Kind.COMPONENT
                : rootTag.equals("theme") ? CompiledUiBlueprint.Kind.THEME : CompiledUiBlueprint.Kind.SURFACE;
        UiSurface surface = kind == CompiledUiBlueprint.Kind.COMPONENT ? UiSurface.FRAGMENT
                : UiSurface.parse(first(element.attr("surface"), legacy ? "page" : null));
        if (surface == null) {
            diagnostics.add(error(source, "Unknown surface '" + element.attr("surface") + "'"));
            surface = UiSurface.PAGE;
        }
        if (legacy) {
            diagnostics.add(warning(source, "Loaded in version-one compatibility mode"));
        }

        List<String> imports = new ArrayList<>();
        for (Element imported : element.select("imports > component[src], imports > theme[src]")) {
            String ref = imported.attr("src").trim();
            if (!ref.isBlank()) imports.add(ref);
        }
        Map<String, Object> state = new LinkedHashMap<>();
        for (Element property : element.select("state > property[name]")) {
            state.put(property.attr("name"), scalar(property.attr("default"), property.attr("type")));
        }

        Counter counter = new Counter();
        Set<String> ids = new LinkedHashSet<>();
        CompiledUiBlueprint.Node root = node(element, source, diagnostics, ids, counter, 0);
        return new CompiledUiBlueprint(id, version, kind, surface, source,
                first(element.attr("command"), element.attr("data-custom-command")),
                element.attr("controller"), element.attr("theme"), imports, state, root, diagnostics);
    }

    private CompiledUiBlueprint.Node node(Element element, String source, List<UiDiagnostic> diagnostics,
            Set<String> ids, Counter counter, int depth) {
        counter.value++;
        if (counter.value > maxNodes) {
            diagnostics.add(error(source, "Component limit of " + maxNodes + " exceeded"));
            return new CompiledUiBlueprint.Node("truncated", null, Map.of(), Map.of(), List.of());
        }
        if (depth > maxDepth) {
            diagnostics.add(error(source, "Component depth limit of " + maxDepth + " exceeded"));
            return new CompiledUiBlueprint.Node("truncated", null, Map.of(), Map.of(), List.of());
        }

        String type = normalizeType(element.normalName());
        boolean customComponent = type.contains(":") || type.contains("-");
        if (!CONTAINERS.contains(type) && !LEAVES.contains(type) && !customComponent
                && !Set.of("imports", "state", "properties", "property", "preview-data").contains(type)) {
            diagnostics.add(warning(source, "Unknown component type <" + type + ">; treated as a container"));
        }
        String id = element.attr("id").trim();
        if (!id.isBlank() && !ids.add(id)) {
            diagnostics.add(error(source, "Duplicate component id '" + id + "'"));
        }

        Map<String, String> properties = new LinkedHashMap<>();
        Map<String, String> events = new LinkedHashMap<>();
        element.attributes().forEach(attribute -> {
            String name = attribute.getKey().toLowerCase(Locale.ROOT);
            String value = attribute.getValue();
            if (name.equals("id")) return;
            if (name.startsWith("on-") || name.equals("action") || name.startsWith("action.")) {
                events.put(name, value);
            } else {
                String normalized = legacyProperty(name);
                properties.put(normalized, value);
                if (LEGACY_ATTRIBUTES.contains(name)) {
                    diagnostics.add(warning(source, "Attribute '" + name + "' is deprecated; use '"
                            + normalized + "'"));
                }
            }
        });
        if (type.equals("repeat") && !properties.containsKey("key")) {
            diagnostics.add(warning(source, "Repeated content has no stable key"));
        }
        if (type.equals("virtual-list") && (!properties.containsKey("item-key")
                || !properties.containsKey("item-height"))) {
            diagnostics.add(error(source, "virtual-list requires item-key and item-height"));
        }
        validateBindings(properties, source, diagnostics);

        List<CompiledUiBlueprint.Node> children = new ArrayList<>();
        for (Element child : element.children()) {
            children.add(node(child, source, diagnostics, ids, counter, depth + 1));
        }
        return new CompiledUiBlueprint.Node(type, id, properties, events, children);
    }

    private static void validateBindings(Map<String, String> properties, String source,
            List<UiDiagnostic> diagnostics) {
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String value = entry.getValue();
            long opens = value.chars().filter(c -> c == '{').count();
            long closes = value.chars().filter(c -> c == '}').count();
            if (opens != closes) diagnostics.add(error(source, "Malformed binding in '" + entry.getKey() + "'"));
            if ((entry.getKey().equals("bind") || entry.getKey().equals("visible")) && value.isBlank()) {
                diagnostics.add(error(source, "Property '" + entry.getKey() + "' cannot be empty"));
            }
        }
    }

    private static Object scalar(String value, String type) {
        if ("boolean".equalsIgnoreCase(type)) return Boolean.parseBoolean(value);
        if ("int".equalsIgnoreCase(type) || "integer".equalsIgnoreCase(type)) {
            try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return 0; }
        }
        if ("number".equalsIgnoreCase(type) || "double".equalsIgnoreCase(type)) {
            try { return Double.parseDouble(value); } catch (NumberFormatException ignored) { return 0D; }
        }
        return value == null ? "" : value;
    }

    private static String normalizeType(String tag) {
        return switch (tag.toLowerCase(Locale.ROOT)) {
            case "col" -> "column";
            case "scroll" -> "scroll-view";
            case "label" -> "text";
            case "hr" -> "divider";
            default -> tag.toLowerCase(Locale.ROOT);
        };
    }

    private static String legacyProperty(String name) {
        if (name.startsWith("data-custom-")) return name.substring("data-custom-".length()).replace("flex", "grow");
        if (name.startsWith("data-ql-")) return name.substring("data-ql-".length()).replace("flex", "grow");
        return name;
    }

    private static String normalizeId(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_:./-]", "-");
    }

    private static String first(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static UiDiagnostic warning(String source, String message) {
        return new UiDiagnostic(UiDiagnostic.Severity.WARNING, source, 0, message);
    }

    private static UiDiagnostic error(String source, String message) {
        return new UiDiagnostic(UiDiagnostic.Severity.ERROR, source, 0, message);
    }

    private static final class Counter { int value; }
}
