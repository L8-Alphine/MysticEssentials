package org.hyzionstudios.mysticessentials.modules.playervaults.event;

import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;

/** Fired when a vault edit lock is released — normally on close, or forcibly by an admin. */
public final class PlayerVaultUnlockEvent implements MysticEvent {

    private final UUID ownerUuid;
    private final int vaultNumber;
    private final UUID actorUuid;
    private final boolean forced;

    public PlayerVaultUnlockEvent(UUID ownerUuid, int vaultNumber, UUID actorUuid, boolean forced) {
        this.ownerUuid = ownerUuid;
        this.vaultNumber = vaultNumber;
        this.actorUuid = actorUuid;
        this.forced = forced;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public int getVaultNumber() {
        return vaultNumber;
    }

    /** @return who released the lock (the force-unlocking admin, or the session owner). */
    public UUID getActorUuid() {
        return actorUuid;
    }

    /** @return {@code true} if this was an admin force-unlock rather than a normal release. */
    public boolean isForced() {
        return forced;
    }
}
