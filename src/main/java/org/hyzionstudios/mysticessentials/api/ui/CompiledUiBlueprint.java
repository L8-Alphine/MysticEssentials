package org.hyzionstudios.mysticessentials.api.ui;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, player-independent output of the v2 UI compiler. */
public record CompiledUiBlueprint(
        String id,
        int version,
        Kind kind,
        UiSurface surface,
        String source,
        String command,
        String controller,
        String theme,
        List<String> imports,
        Map<String, Object> initialState,
        Node root,
        List<UiDiagnostic> diagnostics) {

    public enum Kind { SURFACE, COMPONENT, THEME }

    public CompiledUiBlueprint {
        id = Objects.requireNonNull(id, "id");
        kind = Objects.requireNonNull(kind, "kind");
        surface = Objects.requireNonNull(surface, "surface");
        source = source == null ? "<memory>" : source;
        command = blankToNull(command);
        controller = blankToNull(controller);
        theme = blankToNull(theme);
        imports = List.copyOf(imports == null ? List.of() : imports);
        initialState = Map.copyOf(initialState == null ? Map.of() : initialState);
        root = Objects.requireNonNull(root, "root");
        diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
    }

    public boolean valid() {
        return diagnostics.stream().noneMatch(d -> d.severity() == UiDiagnostic.Severity.ERROR);
    }

    public int nodeCount() {
        return root.totalNodes();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** A normalized component with immutable attributes, events and children. */
    public record Node(String type, String id, Map<String, String> properties,
            Map<String, String> events, List<Node> children) {
        public Node {
            type = Objects.requireNonNull(type, "type");
            id = blankToNull(id);
            properties = Map.copyOf(properties == null ? Map.of() : properties);
            events = Map.copyOf(events == null ? Map.of() : events);
            children = List.copyOf(children == null ? List.of() : children);
        }

        public int totalNodes() {
            return 1 + children.stream().mapToInt(Node::totalNodes).sum();
        }
    }
}
