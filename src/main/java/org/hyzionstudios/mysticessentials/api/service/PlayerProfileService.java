package org.hyzionstudios.mysticessentials.api.service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.hyzionstudios.mysticessentials.api.model.PlayerProfile;

/**
 * Owns the lifecycle and persistence of {@link PlayerProfile} records, including
 * identity, playtime accounting, and last-known/last-teleported locations.
 */
public interface PlayerProfileService {

    /** @return the cached, in-memory profile for an online player, if loaded. */
    Optional<PlayerProfile> getCached(UUID uuid);

    /** Loads (or creates) a profile, caching it. {@code username} refreshes the stored name. */
    CompletableFuture<PlayerProfile> load(UUID uuid, String username);

    /** Persists a profile to the active storage provider. */
    CompletableFuture<Void> save(PlayerProfile profile);

    /** Persists then evicts a profile from the cache (e.g. on quit). */
    CompletableFuture<Void> unload(UUID uuid);

    /** Persists every currently cached profile. */
    CompletableFuture<Void> saveAll();

    /**
     * Resolves a username to a UUID for any player who has joined before (checks
     * online players, then the username index built on connect). Enables
     * offline-by-name operations such as mail. Empty if the name was never seen.
     */
    CompletableFuture<Optional<UUID>> resolveUuid(String username);
}
