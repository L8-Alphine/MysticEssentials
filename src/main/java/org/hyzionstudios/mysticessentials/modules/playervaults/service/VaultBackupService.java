package org.hyzionstudios.mysticessentials.modules.playervaults.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.hyzionstudios.mysticessentials.api.event.EventBus;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.playervaults.config.PlayerVaultConfig;
import org.hyzionstudios.mysticessentials.modules.playervaults.event.PlayerVaultBackupCreateEvent;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.PlayerVault;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.VaultBackup;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.VaultConflictSnapshot;
import org.hyzionstudios.mysticessentials.modules.playervaults.storage.PlayerVaultStorage;

/**
 * Owns vault backup and conflict-snapshot retention. Backups are taken before
 * every potentially destructive step (save-on-close, admin edit, restore,
 * migration) and capped per vault; conflict snapshots preserve the losing side
 * of a save race so nothing is silently lost.
 */
public final class VaultBackupService {

    private final MysticCore core;
    private final PlayerVaultStorage storage;
    private PlayerVaultConfig config;

    public VaultBackupService(MysticCore core, PlayerVaultStorage storage, PlayerVaultConfig config) {
        this.core = core;
        this.storage = storage;
        this.config = config;
    }

    public void updateConfig(PlayerVaultConfig config) {
        this.config = config;
    }

    /**
     * Snapshots a vault into its backup ring, dropping the oldest beyond
     * {@code maxBackupsPerVault}. No-op (completes immediately) when backups are
     * disabled. Fires {@link PlayerVaultBackupCreateEvent} on success.
     */
    public CompletableFuture<Optional<VaultBackup>> backup(PlayerVault vault, String reason, String actorUuid) {
        if (!config.saving.saveBackups || vault == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        UUID owner = UUID.fromString(vault.ownerUuid);
        VaultBackup backup = VaultBackup.of(vault, reason, actorUuid, serverId());
        return storage.loadBackups(owner, vault.vaultNumber).thenCompose(backups -> {
            backups.add(0, backup);
            int max = Math.max(1, config.saving.maxBackupsPerVault);
            while (backups.size() > max) {
                backups.remove(backups.size() - 1);
            }
            return storage.saveBackups(owner, vault.vaultNumber, backups).thenApply(v -> {
                EventBus bus = core.getEventBus();
                if (bus != null) {
                    bus.publish(new PlayerVaultBackupCreateEvent(owner, vault.vaultNumber,
                            backup.backupId, reason));
                }
                return Optional.of(backup);
            });
        });
    }

    public CompletableFuture<List<VaultBackup>> list(UUID owner, int vaultNumber) {
        return storage.loadBackups(owner, vaultNumber);
    }

    public CompletableFuture<Optional<VaultBackup>> find(UUID owner, int vaultNumber, String backupId) {
        return storage.loadBackups(owner, vaultNumber).thenApply(backups -> backups.stream()
                .filter(b -> b.backupId.equalsIgnoreCase(backupId) || b.shortId().equalsIgnoreCase(backupId))
                .findFirst());
    }

    /** Persists a conflict snapshot (bounded to the same ring size as backups). */
    public CompletableFuture<Void> recordConflict(VaultConflictSnapshot snapshot) {
        if (!config.saving.conflictSnapshots || snapshot == null) {
            return CompletableFuture.completedFuture(null);
        }
        UUID owner = UUID.fromString(snapshot.ownerUuid);
        return storage.loadConflicts(owner, snapshot.vaultNumber).thenCompose(conflicts -> {
            conflicts.add(0, snapshot);
            int max = Math.max(1, config.saving.maxBackupsPerVault);
            while (conflicts.size() > max) {
                conflicts.remove(conflicts.size() - 1);
            }
            return storage.saveConflicts(owner, snapshot.vaultNumber, conflicts);
        });
    }

    private String serverId() {
        return core.redis() != null && core.redis().serverId() != null ? core.redis().serverId() : "local";
    }
}
