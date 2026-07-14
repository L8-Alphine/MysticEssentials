package org.hyzionstudios.mysticessentials.api.rtp;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Public entry point for the Random Teleport subsystem of the Teleportation
 * module. Obtain it from {@code MysticEssentialsAPI} / the Teleportation module;
 * addons use it to trigger RTP, search destinations, and register custom
 * validators or claim-exclusion providers.
 */
public interface RandomTeleportService {

    /**
     * Runs the full RTP pipeline for a player: resolve profile &rarr; checks
     * &rarr; warmup &rarr; queued safe-destination search &rarr; reserve payment
     * &rarr; teleport &rarr; commit &rarr; events. Never completes exceptionally
     * for an expected failure — inspect {@link RtpResult#status()}.
     */
    CompletableFuture<RtpResult> teleport(RtpRequest request);

    /** Searches for a safe destination without moving anyone. */
    CompletableFuture<RtpDestinationResult> findDestination(RtpDestinationRequest request);

    /** @return the profile with this id, if it exists. */
    Optional<RtpProfile> getProfile(String profileId);

    /** @return the profiles this player is currently permitted to use. */
    Collection<RtpProfile> getAvailableProfiles(PlayerRef player);

    /** Cancels a player's active warmup, queued search, or pending teleport. @return {@code true} if one existed. */
    boolean cancel(UUID playerId, RtpCancelReason reason);

    /** Registers a custom candidate validator (see {@link RtpDestinationValidator}). */
    void registerValidator(RtpDestinationValidator validator);

    /** Registers a claim/region exclusion provider (see {@link RtpExclusionProvider}). */
    void registerExclusionProvider(RtpExclusionProvider provider);
}
