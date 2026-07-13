package org.hyzionstudios.mysticessentials.modules.playervaults.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.hyzionstudios.mysticessentials.modules.playervaults.api.results.VaultLockResult;
import org.hyzionstudios.mysticessentials.modules.playervaults.api.results.VaultSaveResult;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.PlayerVault;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.VaultMetadata;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Public API for the Player Vaults module, resolvable through the module manager
 * ({@code getModule("playervaults")}) when the module is enabled. Addons — RPG,
 * economy, dashboards — depend on this interface, not the implementation.
 *
 * <p>All storage-touching methods are async ({@link CompletableFuture}) and safe
 * to call from any thread; item mutations that reach the world are marshalled
 * onto the owning player's world thread internally.</p>
 */
public interface PlayerVaultService {

    /**
     * Loads a vault by owner and number, or empty if it has never been
     * materialized. Does not take a lock and does not open any UI.
     */
    CompletableFuture<Optional<PlayerVault>> getVault(UUID ownerUuid, int vaultNumber);

    /**
     * Persists {@code vault} only if {@code expectedVersion} still matches the
     * stored version (optimistic concurrency). On mismatch the save is rejected,
     * the newer stored data is preserved, and a conflict snapshot is created —
     * older data can never overwrite newer data.
     */
    CompletableFuture<VaultSaveResult> saveVault(PlayerVault vault, long expectedVersion);

    /**
     * Attempts to acquire the cross-server edit lock for a vault. A no-op success
     * when cross-server locking is disabled. See {@link #unlockVault}.
     */
    CompletableFuture<VaultLockResult> lockVault(UUID ownerUuid, int vaultNumber, UUID viewerUuid, VaultOpenMode mode);

    /** Releases a held lock, guarded by the {@code lockToken} that acquired it. */
    CompletableFuture<Void> unlockVault(UUID ownerUuid, int vaultNumber, String lockToken);

    /** @return the highest vault number this player may access (resolved from permissions). */
    int getAllowedVaultCount(PlayerRef player);

    /** @return the number of rows this player's vaults expose (resolved from permissions, capped by config). */
    int getAllowedRows(PlayerRef player);

    /** @return the metadata cards for every vault the given player has materialized. */
    CompletableFuture<List<VaultMetadata>> getVaultMetadata(UUID ownerUuid);

    /** Opens the caller's own vault list UI. */
    void openVaultList(PlayerRef player);

    /** Opens the caller's own numbered vault (permission-checked); shows a message on denial. */
    void openOwnVault(PlayerRef player, int vaultNumber);
}
