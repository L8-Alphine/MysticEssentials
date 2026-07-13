package org.hyzionstudios.mysticessentials.modules.tutorial.storage;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.hyzionstudios.mysticessentials.modules.tutorial.player.TutorialPlayerData;

/**
 * Persistence contract for tutorial player data. The MVP ships a JSON
 * implementation ({@link JsonTutorialStorage}); a SQL-backed one can be added
 * later behind the same interface (config key {@code storage.type}).
 */
public interface TutorialStorage {

    /** Loads (or creates) the player's data and caches it until {@link #unload}. */
    CompletableFuture<TutorialPlayerData> load(UUID playerId, String username);

    /** @return the cached data for an online (loaded) player. */
    Optional<TutorialPlayerData> cached(UUID playerId);

    /** Marks a player's cached data as changed so the autosave flushes it. */
    void markDirty(UUID playerId);

    /** Persists one player's data immediately. */
    CompletableFuture<Void> save(UUID playerId);

    /** Persists a player's data and evicts it from the cache (on disconnect). */
    CompletableFuture<Void> unload(UUID playerId);

    /** Persists every dirty entry (autosave tick / shutdown). */
    CompletableFuture<Void> saveAll();

    /** Flushes everything and stops background work. */
    void shutdown();
}
