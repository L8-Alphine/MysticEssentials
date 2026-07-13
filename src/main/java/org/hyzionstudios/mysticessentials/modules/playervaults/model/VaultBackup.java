package org.hyzionstudios.mysticessentials.modules.playervaults.model;

import java.util.UUID;

/**
 * An immutable point-in-time snapshot of a vault, taken before a potentially
 * destructive operation (save-on-close, admin edit, restore, migration) so the
 * previous state can always be recovered. Retained per vault up to
 * {@code saving.maxBackupsPerVault}, oldest dropped first.
 */
public final class VaultBackup {

    public String backupId;
    public String ownerUuid;
    public int vaultNumber;
    /** Actor who triggered the backup (UUID string), or {@code "system"}. */
    public String createdByUuid;
    /** Why the backup was taken: SAVE_ON_CLOSE, PRE_ADMIN_EDIT, PRE_RESTORE, PRE_MIGRATION, CONFLICT. */
    public String reason;
    public String sourceServerId;
    /** The {@code version} of the vault at snapshot time. */
    public long vaultVersion;
    public long createdAt;
    /** Full deep copy of the vault state at snapshot time. */
    public PlayerVault snapshot;

    public VaultBackup() {
    }

    public static VaultBackup of(PlayerVault vault, String reason, String createdByUuid, String serverId) {
        VaultBackup backup = new VaultBackup();
        backup.backupId = UUID.randomUUID().toString();
        backup.ownerUuid = vault.ownerUuid;
        backup.vaultNumber = vault.vaultNumber;
        backup.createdByUuid = createdByUuid == null ? "system" : createdByUuid;
        backup.reason = reason;
        backup.sourceServerId = serverId;
        backup.vaultVersion = vault.version;
        backup.createdAt = System.currentTimeMillis();
        backup.snapshot = vault.copy();
        return backup;
    }

    /** @return the first 8 characters of the backup id, for chat display. */
    public String shortId() {
        return backupId == null ? "?" : backupId.substring(0, Math.min(8, backupId.length()));
    }
}
