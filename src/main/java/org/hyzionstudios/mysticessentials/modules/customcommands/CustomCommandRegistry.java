package org.hyzionstudios.mysticessentials.modules.customcommands;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The live lookup table of <b>registrable</b> custom commands (loaded,
 * compiled, and validation-clean), indexed by primary name and by every label
 * (name + aliases). Rebuilt atomically on load/reload; the dynamic stubs and
 * the executor resolve against whatever is current, which is what makes
 * reloads take effect without re-registration.
 */
public final class CustomCommandRegistry {

    private volatile Map<String, CustomCommand> byName = Map.of();
    private volatile Map<String, CustomCommand> byLabel = Map.of();

    /** Replaces the registry contents with {@code definitions} (already validated). */
    public void rebuild(Collection<CustomCommand> definitions) {
        rebuild(definitions, java.util.Set.of());
    }

    /**
     * Replaces the registry contents, skipping any label in {@code excludedLabels}
     * (an alias that clashes with an existing command or another custom command).
     * The primary name is always kept — a command whose primary name conflicts is
     * excluded upstream by the validator, so it never reaches here.
     */
    public void rebuild(Collection<CustomCommand> definitions, java.util.Set<String> excludedLabels) {
        Map<String, CustomCommand> names = new LinkedHashMap<>();
        Map<String, CustomCommand> labels = new LinkedHashMap<>();
        for (CustomCommand definition : definitions) {
            names.put(definition.nameLower(), definition);
            for (String label : definition.labels()) {
                if (label.equals(definition.nameLower()) || !excludedLabels.contains(label)) {
                    labels.putIfAbsent(label, definition);
                }
            }
        }
        this.byName = Collections.unmodifiableMap(names);
        this.byLabel = Collections.unmodifiableMap(labels);
    }

    public Optional<CustomCommand> byLabel(String label) {
        return label == null ? Optional.empty()
                : Optional.ofNullable(byLabel.get(label.toLowerCase(Locale.ROOT)));
    }

    public Optional<CustomCommand> byName(String name) {
        return name == null ? Optional.empty()
                : Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
    }

    /** @return {@code true} if {@code label} resolves to a custom command (used for recursion routing). */
    public boolean isCustomCommand(String label) {
        return label != null && byLabel.containsKey(label.toLowerCase(Locale.ROOT));
    }

    public Collection<CustomCommand> all() {
        return byName.values();
    }

    public Set<String> labels() {
        return byLabel.keySet();
    }

    public int size() {
        return byName.size();
    }
}
