package org.hyzionstudios.mysticessentials.api.service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.hyzionstudios.mysticessentials.api.model.MysticLocation;
import org.hyzionstudios.mysticessentials.api.model.TeleportRequest;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Central teleport authority. Every module that moves a player routes through
 * here so warmups, cooldowns, cost checks, movement/damage cancellation, and
 * back-location tracking are applied consistently.
 *
 * @see TeleportRequest
 */
public interface TeleportService {

    /**
     * Runs the full teleport pipeline for {@code player}:
     * permission/cost/cooldown checks &rarr; warmup &rarr; cancellation watch
     * &rarr; execute &rarr; apply cooldown &rarr; fire event.
     *
     * @return a future completing with the outcome (never completes exceptionally
     *         for an expected failure such as a failed check).
     */
    CompletableFuture<Result> teleport(PlayerRef player, TeleportRequest request);

    /** Immediate teleport with no warmup/cooldown/checks (admin/system use). */
    CompletableFuture<Result> teleportNow(PlayerRef player, MysticLocation destination);

    /** @return remaining cooldown in seconds for a cooldown key, or {@code 0} if ready. */
    long remainingCooldown(UUID player, String cooldownKey);

    /** Outcome of a teleport attempt. */
    enum Result {
        SUCCESS,
        NO_PERMISSION,
        ON_COOLDOWN,
        NOT_ENOUGH_MONEY,
        CANCELLED_MOVED,
        CANCELLED_DAMAGED,
        INVALID_DESTINATION,
        FAILED
    }
}
