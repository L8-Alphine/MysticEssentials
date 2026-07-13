package org.hyzionstudios.mysticessentials.modules.playervaults.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.event.EventBus;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.playervaults.api.PlayerVaultService;
import org.hyzionstudios.mysticessentials.modules.playervaults.api.VaultOpenMode;
import org.hyzionstudios.mysticessentials.modules.playervaults.api.results.VaultLockResult;
import org.hyzionstudios.mysticessentials.modules.playervaults.api.results.VaultSaveResult;
import org.hyzionstudios.mysticessentials.modules.playervaults.config.PlayerVaultConfig;
import org.hyzionstudios.mysticessentials.modules.playervaults.event.PlayerVaultConflictEvent;
import org.hyzionstudios.mysticessentials.modules.playervaults.event.PlayerVaultLoadEvent;
import org.hyzionstudios.mysticessentials.modules.playervaults.event.PlayerVaultLockEvent;
import org.hyzionstudios.mysticessentials.modules.playervaults.event.PlayerVaultSaveEvent;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.PlayerVault;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.PlayerVaultProfile;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.VaultConflictSnapshot;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.VaultMetadata;
import org.hyzionstudios.mysticessentials.modules.playervaults.storage.PlayerVaultRedisBridge;
import org.hyzionstudios.mysticessentials.modules.playervaults.storage.PlayerVaultStorage;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Core orchestration for Player Vaults: loading, the versioned save workflow, and
 * lock brokering. Provides the primitives the UI controller and command compose
 * into full open/edit/close sessions.
 *
 * <p><b>The versioned save is the anti-dupe heart of the module.</b> A save
 * always re-reads the authoritative version from permanent storage (never from
 * the Redis cache) and refuses to write when it no longer matches the version
 * the editor captured at open. On mismatch the newer stored data is preserved,
 * the editor's attempt is kept as a conflict snapshot, and nothing is lost — so
 * stale cache, a slow peer, or an admin force-unlock can never roll back or
 * duplicate items.</p>
 */
public final class PlayerVaultServiceImpl implements PlayerVaultService {

    private final MysticCore core;
    private final PlayerVaultStorage storage;
    private final PlayerVaultRedisBridge redisBridge;
    private final PlayerVaultLockService lockService;
    private final PlayerVaultPermissionService permissionService;
    private final VaultBackupService backupService;
    private PlayerVaultConfig config;

    /** Set after construction to break the service&harr;UI cycle. */
    private org.hyzionstudios.mysticessentials.modules.playervaults.ui.PlayerVaultUiController uiController;

    public PlayerVaultServiceImpl(MysticCore core, PlayerVaultStorage storage,
            PlayerVaultRedisBridge redisBridge, PlayerVaultLockService lockService,
            PlayerVaultPermissionService permissionService, VaultBackupService backupService,
            PlayerVaultConfig config) {
        this.core = core;
        this.storage = storage;
        this.redisBridge = redisBridge;
        this.lockService = lockService;
        this.permissionService = permissionService;
        this.backupService = backupService;
        this.config = config;
    }

    public void setUiController(org.hyzionstudios.mysticessentials.modules.playervaults.ui.PlayerVaultUiController ui) {
        this.uiController = ui;
    }

    public void updateConfig(PlayerVaultConfig config) {
        this.config = config;
    }

    public PlayerVaultConfig config() {
        return config;
    }

    // ----- PlayerVaultService: reads --------------------------------------------

    @Override
    public CompletableFuture<Optional<PlayerVault>> getVault(UUID ownerUuid, int vaultNumber) {
        return storage.loadVault(ownerUuid, vaultNumber);
    }

    @Override
    public int getAllowedVaultCount(PlayerRef player) {
        return permissionService.allowedVaultCount(player);
    }

    @Override
    public int getAllowedRows(PlayerRef player) {
        return permissionService.allowedRows(player);
    }

    @Override
    public CompletableFuture<List<VaultMetadata>> getVaultMetadata(UUID ownerUuid) {
        return storage.loadProfile(ownerUuid).thenCompose(profileOpt -> {
            if (profileOpt.isEmpty()) {
                return CompletableFuture.completedFuture(List.of());
            }
            List<Integer> numbers = profileOpt.get().vaultNumbers;
            List<CompletableFuture<Optional<PlayerVault>>> loads = new ArrayList<>();
            for (int number : numbers) {
                loads.add(storage.loadVault(ownerUuid, number));
            }
            return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).thenApply(v -> {
                List<VaultMetadata> metadata = new ArrayList<>();
                for (CompletableFuture<Optional<PlayerVault>> load : loads) {
                    load.join().ifPresent(vault -> metadata.add(vault.metadata));
                }
                return metadata;
            });
        });
    }

    /**
     * Loads a vault, or synthesizes a fresh empty one if it has never been saved.
     * Fires {@link PlayerVaultLoadEvent}. Reads from permanent storage (the
     * source of truth), never the cache, so a captured version is always sound.
     */
    public CompletableFuture<PlayerVault> loadOrCreate(UUID owner, int vaultNumber, int rows) {
        return storage.loadVault(owner, vaultNumber).thenApply(existing -> {
            PlayerVault vault = existing.orElse(null);
            PlayerVaultLoadEvent.Source source;
            if (vault == null) {
                vault = new PlayerVault(owner.toString(), vaultNumber, Math.max(1, rows));
                source = PlayerVaultLoadEvent.Source.NEW;
            } else {
                source = PlayerVaultLoadEvent.Source.STORAGE;
            }
            EventBus bus = core.getEventBus();
            if (bus != null) {
                bus.publish(new PlayerVaultLoadEvent(vault, source));
            }
            return vault;
        });
    }

    // ----- PlayerVaultService: locks --------------------------------------------

    @Override
    public CompletableFuture<VaultLockResult> lockVault(UUID ownerUuid, int vaultNumber,
            UUID viewerUuid, VaultOpenMode mode) {
        VaultLockResult result = lockService.acquire(ownerUuid, vaultNumber, viewerUuid, mode);
        EventBus bus = core.getEventBus();
        if (bus != null) {
            bus.publish(new PlayerVaultLockEvent(ownerUuid, vaultNumber, viewerUuid, mode, result.acquired()));
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Void> unlockVault(UUID ownerUuid, int vaultNumber, String lockToken) {
        lockService.release(ownerUuid, vaultNumber, lockToken);
        return CompletableFuture.completedFuture(null);
    }

    public PlayerVaultLockService lockService() {
        return lockService;
    }

    // ----- PlayerVaultService: versioned save -----------------------------------

    @Override
    public CompletableFuture<VaultSaveResult> saveVault(PlayerVault vault, long expectedVersion) {
        return versionedSave(vault, expectedVersion, null, false, "API_SAVE");
    }

    /**
     * The full save workflow.
     *
     * @param working         the editor's working copy to persist
     * @param expectedVersion the version captured when the vault was opened
     * @param actorUuid       who is saving (for snapshot/backup attribution), or {@code null}
     * @param backupBeforeSave snapshot the <i>previous</i> stored state first (close/admin/restore)
     * @param reason          backup/snapshot reason tag
     */
    public CompletableFuture<VaultSaveResult> versionedSave(PlayerVault working, long expectedVersion,
            UUID actorUuid, boolean backupBeforeSave, String reason) {
        UUID owner;
        try {
            owner = UUID.fromString(working.ownerUuid);
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(VaultSaveResult.storageError());
        }

        // Pre-save veto: cancelling must leave stored data untouched.
        EventBus bus = core.getEventBus();
        if (bus != null) {
            PlayerVaultSaveEvent pre = bus.publish(
                    new PlayerVaultSaveEvent(working, PlayerVaultSaveEvent.Phase.PRE, expectedVersion));
            if (pre.isCancelled()) {
                return storage.loadVault(owner, working.vaultNumber)
                        .thenApply(latest -> VaultSaveResult.saved(latest.orElse(working)));
            }
        }

        return storage.loadVault(owner, working.vaultNumber).thenCompose(latestOpt -> {
            long latestVersion = latestOpt.map(v -> v.version).orElse(0L);

            // Version mismatch: preserve newer data, snapshot the losing attempt.
            if (latestVersion != expectedVersion) {
                return handleConflict(working, expectedVersion, latestVersion, actorUuid,
                        latestOpt.orElse(working));
            }

            CompletableFuture<Void> pre = backupBeforeSave && latestOpt.isPresent()
                    ? backupService.backup(latestOpt.get(), reason,
                            actorUuid == null ? null : actorUuid.toString()).thenApply(b -> null)
                    : CompletableFuture.completedFuture(null);

            return pre.thenCompose(ignored -> {
                working.version = latestVersion + 1;
                working.updatedAt = System.currentTimeMillis();
                working.lastOpenedServer = redisBridge.serverId();
                return storage.saveVault(working)
                        .thenCompose(v -> trackProfile(owner, working))
                        .thenApply(v -> {
                            redisBridge.cacheVault(working);
                            redisBridge.publishUpdate(owner, working.vaultNumber, working.version);
                            EventBus post = core.getEventBus();
                            if (post != null) {
                                post.publish(new PlayerVaultSaveEvent(working,
                                        PlayerVaultSaveEvent.Phase.POST, expectedVersion));
                            }
                            return VaultSaveResult.saved(working);
                        });
            });
        }).exceptionally(error -> {
            core.log(Level.WARNING, "[playervaults] save failed for " + owner + " vault "
                    + working.vaultNumber + ": " + error);
            return VaultSaveResult.storageError();
        });
    }

    private CompletableFuture<VaultSaveResult> handleConflict(PlayerVault working, long expectedVersion,
            long latestVersion, UUID actorUuid, PlayerVault latest) {
        VaultConflictSnapshot snapshot = VaultConflictSnapshot.of(working, expectedVersion, latestVersion,
                actorUuid == null ? null : actorUuid.toString(), redisBridge.serverId());
        return backupService.recordConflict(snapshot).thenApply(v -> {
            // Invalidate our (now-proven-stale) cache and adopt the stored truth.
            redisBridge.invalidate(UUID.fromString(working.ownerUuid), working.vaultNumber);
            EventBus bus = core.getEventBus();
            if (bus != null) {
                bus.publish(new PlayerVaultConflictEvent(UUID.fromString(working.ownerUuid),
                        working.vaultNumber, expectedVersion, latestVersion, snapshot.conflictId));
            }
            core.log(Level.INFO, "[playervaults] conflict on " + working.ownerUuid + " vault "
                    + working.vaultNumber + " (expected v" + expectedVersion + ", stored v"
                    + latestVersion + "); preserved stored data, snapshot " + snapshot.conflictId);
            return VaultSaveResult.conflict(latest);
        });
    }

    private CompletableFuture<Void> trackProfile(UUID owner, PlayerVault vault) {
        return storage.loadProfile(owner).thenCompose(profileOpt -> {
            PlayerVaultProfile profile = profileOpt.orElseGet(() ->
                    new PlayerVaultProfile(owner, resolveName(owner)));
            profile.trackVault(vault.vaultNumber);
            String name = resolveName(owner);
            if (name != null) {
                profile.lastKnownName = name;
            }
            return storage.saveProfile(profile);
        });
    }

    private String resolveName(UUID owner) {
        return core.getPlayerProfileService().getCached(owner)
                .map(p -> p.getUsername()).orElse(null);
    }

    // ----- UI convenience -------------------------------------------------------

    @Override
    public void openVaultList(PlayerRef player) {
        if (uiController != null) {
            uiController.openVaultList(player);
        }
    }

    @Override
    public void openOwnVault(PlayerRef player, int vaultNumber) {
        if (uiController != null) {
            uiController.openOwnVault(player, vaultNumber);
        }
    }
}
