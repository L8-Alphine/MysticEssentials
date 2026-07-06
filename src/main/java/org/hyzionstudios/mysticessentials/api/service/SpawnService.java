package org.hyzionstudios.mysticessentials.api.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.model.Home;
import org.hyzionstudios.mysticessentials.api.model.MysticLocation;

/**
 * Owns global spawn, per-world spawns, and the Homes submodule. Actual movement
 * is delegated to the {@link TeleportService}.
 */
public interface SpawnService {

    Optional<MysticLocation> getGlobalSpawn();

    void setGlobalSpawn(MysticLocation location);

    Optional<MysticLocation> getWorldSpawn(String world);

    void setWorldSpawn(String world, MysticLocation location);

    /** Resolves the respawn destination using the configured priority chain. */
    Optional<MysticLocation> resolveRespawn(UUID player);

    // ----- Homes submodule ---------------------------------------------------

    List<Home> getHomes(UUID player);

    Optional<Home> getHome(UUID player, String name);

    /** @return {@code true} if created; {@code false} if the player is at their home limit. */
    boolean setHome(UUID player, String name, MysticLocation location);

    boolean deleteHome(UUID player, String name);

    /** Renames a home. Fails if {@code oldName} is missing or {@code newName} is taken. */
    boolean renameHome(UUID player, String oldName, String newName);

    /** Maximum homes the player may own, resolved from permission-based limits. */
    int homeLimit(UUID player);
}
