package org.hyzionstudios.mysticessentials.modules.lootbox.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An eligibility condition answered by another plugin (P3-WP3): a quest
 * completed, an RPG level, a guild. Pools and rewards name it by {@link #type()}
 * ({@code "conditions": [{"type": "QUEST_COMPLETED", "params": {...}}]}).
 *
 * <p>Conditions are evaluated against the immutable revision before every
 * preview and again at the roll; a change in between makes the confirmation
 * stale. An answer the provider cannot give ({@link Decision#UNAVAILABLE}) fails
 * closed: the opening is refused rather than guessed. A pool whose condition
 * provider is missing is invalid, like one with a missing reward provider.</p>
 *
 * <p>{@link #test} runs on lane threads; it must answer from a cache, quickly,
 * and never touch the world store.</p>
 */
public interface ConditionProvider {

    enum Decision {
        PASS,
        FAIL,
        /** The provider could not answer (not loaded yet, data missing): fail closed. */
        UNAVAILABLE
    }

    /** Upper-case condition type, e.g. {@code QUEST_COMPLETED}; {@code [A-Z0-9_]+}. */
    String type();

    /** Publish-time check of the parameters; empty means valid. */
    List<String> validate(Map<String, String> params);

    /** Plain-text rule shown to players, e.g. {@code Completed the quest "main:intro"}. */
    String describe(Map<String, String> params);

    Decision test(UUID player, Map<String, String> params);
}
