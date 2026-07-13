package org.hyzionstudios.mysticessentials.modules.playervaults.event;

import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;

/** Fired after a vault backup snapshot is created. */
public final class PlayerVaultBackupCreateEvent implements MysticEvent {

    private final UUID ownerUuid;
    private final int vaultNumber;
    private final String backupId;
    private final String reason;

    public PlayerVaultBackupCreateEvent(UUID ownerUuid, int vaultNumber, String backupId, String reason) {
        this.ownerUuid = ownerUuid;
        this.vaultNumber = vaultNumber;
        this.backupId = backupId;
        this.reason = reason;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public int getVaultNumber() {
        return vaultNumber;
    }

    public String getBackupId() {
        return backupId;
    }

    public String getReason() {
        return reason;
    }
}
