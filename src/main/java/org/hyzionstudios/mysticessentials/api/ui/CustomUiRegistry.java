package org.hyzionstudios.mysticessentials.api.ui;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe registry shared by MysticEssentials and third-party mods. */
public final class CustomUiRegistry {

    private final Map<String, CompiledUiBlueprint> surfaces = new ConcurrentHashMap<>();
    private final Map<String, CompiledUiBlueprint> components = new ConcurrentHashMap<>();
    private final Map<String, CompiledUiBlueprint> themes = new ConcurrentHashMap<>();

    public void register(CompiledUiBlueprint blueprint) {
        Objects.requireNonNull(blueprint, "blueprint");
        if (!blueprint.valid()) {
            throw new IllegalArgumentException("Cannot register invalid UI blueprint '" + blueprint.id() + "'");
        }
        target(blueprint.kind()).put(blueprint.id(), blueprint);
    }

    public void registerPage(String id, CompiledUiBlueprint blueprint) {
        require(id, blueprint, CompiledUiBlueprint.Kind.SURFACE);
        if (blueprint.surface() != UiSurface.PAGE && blueprint.surface() != UiSurface.WINDOW) {
            throw new IllegalArgumentException("Blueprint is not a page/window: " + blueprint.surface());
        }
        surfaces.put(id, blueprint);
    }

    public void registerComponent(String id, CompiledUiBlueprint blueprint) {
        require(id, blueprint, CompiledUiBlueprint.Kind.COMPONENT);
        components.put(id, blueprint);
    }

    public void registerTheme(String id, CompiledUiBlueprint blueprint) {
        require(id, blueprint, CompiledUiBlueprint.Kind.THEME);
        themes.put(id, blueprint);
    }

    public Optional<CompiledUiBlueprint> surface(String id) { return optional(surfaces, id); }
    public Optional<CompiledUiBlueprint> component(String id) { return optional(components, id); }
    public Optional<CompiledUiBlueprint> theme(String id) { return optional(themes, id); }

    public Collection<CompiledUiBlueprint> surfaces() { return ListView.copy(surfaces.values()); }
    public Collection<CompiledUiBlueprint> components() { return ListView.copy(components.values()); }
    public Collection<CompiledUiBlueprint> themes() { return ListView.copy(themes.values()); }

    public Map<UiSurface, Integer> surfaceCounts() {
        Map<UiSurface, Integer> counts = new EnumMap<>(UiSurface.class);
        surfaces.values().forEach(value -> counts.merge(value.surface(), 1, Integer::sum));
        return Collections.unmodifiableMap(counts);
    }

    public void unregister(String id) {
        if (id == null) return;
        surfaces.remove(id);
        components.remove(id);
        themes.remove(id);
    }

    private Map<String, CompiledUiBlueprint> target(CompiledUiBlueprint.Kind kind) {
        return switch (kind) {
            case SURFACE -> surfaces;
            case COMPONENT -> components;
            case THEME -> themes;
        };
    }

    private static Optional<CompiledUiBlueprint> optional(Map<String, CompiledUiBlueprint> map, String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(map.get(id));
    }

    private static void require(String id, CompiledUiBlueprint blueprint, CompiledUiBlueprint.Kind kind) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(blueprint, "blueprint");
        if (!id.equals(blueprint.id())) throw new IllegalArgumentException("Registry id must match blueprint id");
        if (blueprint.kind() != kind) throw new IllegalArgumentException("Expected " + kind + " blueprint");
        if (!blueprint.valid()) throw new IllegalArgumentException("Cannot register invalid blueprint");
    }

    private static final class ListView {
        static <T> Collection<T> copy(Collection<T> values) { return java.util.List.copyOf(values); }
    }
}
