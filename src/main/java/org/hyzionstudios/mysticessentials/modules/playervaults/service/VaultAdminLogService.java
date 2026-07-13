package org.hyzionstudios.mysticessentials.modules.playervaults.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.playervaults.config.PlayerVaultConfig;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.VaultAdminLogEntry;
import org.hyzionstudios.mysticessentials.modules.playervaults.storage.PlayerVaultStorage;

/**
 * Append-only audit trail of staff actions against player vaults, stored per
 * target player and capped by {@code admin.maxLogEntriesPerPlayer}. Entries hold
 * only non-sensitive summary fields (actor, action, mode, item-count deltas) so
 * they are safe to surface in chat.
 */
public final class VaultAdminLogService {

    private final MysticCore core;
    private final PlayerVaultStorage storage;
    private PlayerVaultConfig config;

    public VaultAdminLogService(MysticCore core, PlayerVaultStorage storage, PlayerVaultConfig config) {
        this.core = core;
        this.storage = storage;
        this.config = config;
    }

    public void updateConfig(PlayerVaultConfig config) {
        this.config = config;
    }

    public String serverId() {
        return core.redis() != null && core.redis().serverId() != null ? core.redis().serverId() : "local";
    }

    /** Records an audit entry for {@code entry.targetUuid}, trimming to the retention cap. */
    public CompletableFuture<Void> record(VaultAdminLogEntry entry) {
        if (entry == null) {
            return CompletableFuture.completedFuture(null);
        }
        UUID target = UUID.fromString(entry.targetUuid);
        return storage.loadLogs(target).thenCompose(entries -> {
            entries.add(0, entry);
            int max = Math.max(1, config.admin.maxLogEntriesPerPlayer);
            while (entries.size() > max) {
                entries.remove(entries.size() - 1);
            }
            return storage.saveLogs(target, entries);
        });
    }

    public CompletableFuture<List<VaultAdminLogEntry>> list(UUID target) {
        return storage.loadLogs(target);
    }
}
