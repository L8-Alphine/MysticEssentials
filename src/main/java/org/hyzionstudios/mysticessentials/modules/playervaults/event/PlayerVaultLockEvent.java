package org.hyzionstudios.mysticessentials.modules.playervaults.event;

import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;
import org.hyzionstudios.mysticessentials.modules.playervaults.api.VaultOpenMode;

/** Fired when a vault edit lock is acquired or an acquisition is denied. */
public final class PlayerVaultLockEvent implements MysticEvent {

    private final UUID ownerUuid;
    private final int vaultNumber;
    private final UUID viewerUuid;
    private final VaultOpenMode mode;
    private final boolean acquired;

    public PlayerVaultLockEvent(UUID ownerUuid, int vaultNumber, UUID viewerUuid,
            VaultOpenMode mode, boolean acquired) {
        this.ownerUuid = ownerUuid;
        this.vaultNumber = vaultNumber;
        this.viewerUuid = viewerUuid;
        this.mode = mode;
        this.acquired = acquired;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public int getVaultNumber() {
        return vaultNumber;
    }

    public UUID getViewerUuid() {
        return viewerUuid;
    }

    public VaultOpenMode getMode() {
        return mode;
    }

    public boolean isAcquired() {
        return acquired;
    }
}
