package org.hyzionstudios.mysticessentials.modules.playervaults.event;

import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;

/** Fired when a vault UI session closes (after the final save is attempted). */
public final class PlayerVaultCloseEvent implements MysticEvent {

    private final UUID viewerUuid;
    private final UUID ownerUuid;
    private final int vaultNumber;
    private final boolean saved;

    public PlayerVaultCloseEvent(UUID viewerUuid, UUID ownerUuid, int vaultNumber, boolean saved) {
        this.viewerUuid = viewerUuid;
        this.ownerUuid = ownerUuid;
        this.vaultNumber = vaultNumber;
        this.saved = saved;
    }

    public UUID getViewerUuid() {
        return viewerUuid;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public int getVaultNumber() {
        return vaultNumber;
    }

    /** @return {@code true} if the closing save succeeded. */
    public boolean isSaved() {
        return saved;
    }
}
