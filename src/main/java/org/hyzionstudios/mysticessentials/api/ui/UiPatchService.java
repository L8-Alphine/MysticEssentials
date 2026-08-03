package org.hyzionstudios.mysticessentials.api.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Produces minimal, validated property updates between two rendered states. */
public final class UiPatchService {
    public record Patch(String selectorProperty, Object value) {
        public Patch {
            if (selectorProperty == null || selectorProperty.isBlank()) {
                throw new IllegalArgumentException("Patch selector/property cannot be null or blank");
            }
            Objects.requireNonNull(value, "Patch value");
        }
    }

    public List<Patch> diff(Map<String, ?> previous, Map<String, ?> current) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        List<Patch> patches = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(current.keySet());
        for (String key : keys) {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("Null/blank UI selector");
            Object value = current.get(key);
            if (value == null) throw new IllegalArgumentException("Null UI value for " + key);
            if (!Objects.equals(previous.get(key), value)) patches.add(new Patch(key, value));
        }
        return List.copyOf(patches);
    }
}
