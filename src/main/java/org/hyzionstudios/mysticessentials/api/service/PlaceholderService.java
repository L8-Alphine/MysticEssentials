package org.hyzionstudios.mysticessentials.api.service;

import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Resolves internal Mystic placeholders and, when available, bridges to
 * PlaceholderAPI. Internal placeholders use the {@code {key}} form; external
 * ones use the PlaceholderAPI {@code %expansion_id%} form.
 */
public interface PlaceholderService {

    /** @return {@code true} if the PlaceholderAPI integration is active. */
    boolean isPlaceholderApiAvailable();

    /** Registers an internal placeholder resolver keyed by {@code name} (the token between braces). */
    void register(String name, BiFunction<UUID, String, String> resolver);

    /**
     * Resolves every placeholder in {@code input} for the given player context
     * (which may be {@code null} for non-player contexts).
     */
    String resolve(UUID player, String input);
}
