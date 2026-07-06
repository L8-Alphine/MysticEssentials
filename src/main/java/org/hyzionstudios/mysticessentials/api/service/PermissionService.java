package org.hyzionstudios.mysticessentials.api.service;

import java.util.OptionalInt;
import java.util.UUID;

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
     * Resolves a numeric limit encoded as permission suffixes, e.g.
     * {@code mysticessentials.home.limit.5}. Returns the highest matching value,
     * or empty when only an {@code .unlimited} node or nothing matches.
     *
     * @param unlimitedIsMax if {@code true}, an {@code .unlimited} node yields {@link Integer#MAX_VALUE}.
     */
    OptionalInt limit(UUID player, String basePermission, boolean unlimitedIsMax);
}
