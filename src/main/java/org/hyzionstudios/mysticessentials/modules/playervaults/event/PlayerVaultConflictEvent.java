package org.hyzionstudios.mysticessentials.modules.playervaults.event;

import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;

/**
 * Fired when a versioned save is rejected because the stored version moved on.
 * Intentionally <b>not</b> cancellable: nothing may bypass the version check, so
 * newer data always wins. A conflict snapshot of the losing attempt has already
 * been persisted when this fires.
 */
public final class PlayerVaultConflictEvent implements MysticEvent {

    private final UUID ownerUuid;
    private final int vaultNumber;
    private final long expectedVersion;
    private final long latestVersion;
    private final String conflictId;

    public PlayerVaultConflictEvent(UUID ownerUuid, int vaultNumber, long expectedVersion,
            long latestVersion, String conflictId) {
        this.ownerUuid = ownerUuid;
        this.vaultNumber = vaultNumber;
        this.expectedVersion = expectedVersion;
        this.latestVersion = latestVersion;
        this.conflictId = conflictId;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public int getVaultNumber() {
        return vaultNumber;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }

    public long getLatestVersion() {
        return latestVersion;
    }

    public String getConflictId() {
        return conflictId;
    }
}
