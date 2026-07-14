package org.hyzionstudios.mysticessentials.api.service;

import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Permission checks and per-rank limit resolution. Backed by LuckPerms when
 * present; falls back to the Hytale permission system otherwise.
 */
public interface PermissionService {

    /** @return {@code true} if the LuckPerms integration is active. */
    boolean isLuckPermsAvailable();

    /** @return {@code true} if the (online) player has the permission node. */
    boolean has(UUID player, String permission);

    /** @return the player's primary group/rank name, if resolvable. */
    String primaryGroup(UUID player);

    /** @return the player's meta prefix (LuckPerms), or empty string when none/unavailable. */
    String prefix(UUID player);

    /** @return the player's meta suffix (LuckPerms), or empty string when none/unavailable. */
    String suffix(UUID player);

    /**
     * Reads an arbitrary LuckPerms meta value from the player's cached user data
     * (never loads storage). Used e.g. by the chat rank icon override key.
     *
     * @return the meta value, or {@code null} when unset or LuckPerms is absent
     */
    default String metaValue(UUID player, String key) {
        return null;
    }

    /**
     * Subscribes to LuckPerms user-data recalculation so caches keyed on group
     * or meta state can invalidate immediately instead of waiting for TTL.
     *
     * @return a handle that unsubscribes when closed, or {@code null} when the
     *         backing permission system exposes no such events
     */
    default AutoCloseable onUserDataRecalculated(Consumer<UUID> listener) {
        return null;
    }

    /**
     * Resolves a numeric limit encoded as permission suffixes, e.g.
     * {@code mysticessentials.home.limit.5}. Returns the highest matching value,
     * or empty when only an {@code .unlimited} node or nothing matches.
     *
     * @param unlimitedIsMax if {@code true}, an {@code .unlimited} node yields {@link Integer#MAX_VALUE}.
     */
    OptionalInt limit(UUID player, String basePermission, boolean unlimitedIsMax);
}
