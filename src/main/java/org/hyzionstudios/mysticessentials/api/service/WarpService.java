package org.hyzionstudios.mysticessentials.api.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.model.MysticLocation;
import org.hyzionstudios.mysticessentials.api.model.Warp;

/** Owns server warps and the Player Warps submodule. */
public interface WarpService {

    Optional<Warp> getWarp(String name);

    /** Server warps visible to the given player (respecting visibility/permissions). */
    List<Warp> listServerWarps(UUID viewer);

    void setServerWarp(Warp warp);

    boolean deleteServerWarp(String name);

    // ----- Player Warps submodule --------------------------------------------

    /** Player warps owned by {@code owner}. */
    List<Warp> getPlayerWarps(UUID owner);

    /** Every player warp on the server (names are globally unique). */
    List<Warp> listAllPlayerWarps();

    /** Looks up a player warp by its globally unique name. */
    Optional<Warp> getPlayerWarp(String name);

    /**
     * @return {@code true} if created; {@code false} if the owner is at their
     *         player-warp limit or the name is already taken.
     */
    boolean createPlayerWarp(UUID owner, String name, MysticLocation location);

    boolean deletePlayerWarp(UUID owner, String name);

    /** Renames one of {@code owner}'s player warps. Fails if the new name is taken. */
    boolean renamePlayerWarp(UUID owner, String oldName, String newName);

    /** Maximum player warps the owner may create, resolved from permission-based limits. */
    int playerWarpLimit(UUID owner);
}
