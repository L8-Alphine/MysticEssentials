package org.hyzionstudios.mysticessentials.modules.playervaults.event;

import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;
import org.hyzionstudios.mysticessentials.modules.playervaults.api.VaultOpenMode;

/**
 * Fired before a vault UI opens. Cancellable: an external system (RPG lock,
 * moderation hold) may veto access, in which case the vault does not open and no
 * lock is taken.
 */
public final class PlayerVaultOpenEvent implements MysticEvent.Cancellable {

    private final UUID viewerUuid;
    private final UUID ownerUuid;
    private final int vaultNumber;
    private final VaultOpenMode mode;
    private boolean cancelled;

    public PlayerVaultOpenEvent(UUID viewerUuid, UUID ownerUuid, int vaultNumber, VaultOpenMode mode) {
        this.viewerUuid = viewerUuid;
        this.ownerUuid = ownerUuid;
        this.vaultNumber = vaultNumber;
        this.mode = mode;
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

    public VaultOpenMode getMode() {
        return mode;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
